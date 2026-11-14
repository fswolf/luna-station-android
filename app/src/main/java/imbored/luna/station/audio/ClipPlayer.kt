package imbored.luna.station.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Plays her reply clips in order, as they arrive: she starts talking a sentence in.
 * [hush] is the Stop button: whatever is playing stops and nothing queued before it plays.
 * [onStart] hears each clip as it starts, so her portrait's mouth can move with it.
 */
class ClipPlayer(private val onStart: (turn: String, n: Int) -> Unit = { _, _ -> }) {
    private class Item(val round: Int, val pcm: Pcm, val turn: String, val n: Int)

    private val queue = LinkedBlockingQueue<Item>()
    private val round = AtomicInteger()
    private val owed = AtomicInteger()       // queued or playing: idle only at 0
    private val lock = Any()
    private var current: AudioTrack? = null
    @Volatile private var closed = false

    private val worker = thread(name = "luna-voice", isDaemon = true) {
        while (!closed) {
            val item = try { queue.take() } catch (e: InterruptedException) { break }
            try {
                if (item.round == round.get()) {
                    runCatching { onStart(item.turn, item.n) }
                    play(item)
                }
            } catch (e: Exception) {
                // a clip that won't play is skipped; the text is on screen either way
            } finally {
                owed.decrementAndGet()
            }
        }
    }

    /** Queue clip [n] of [turn]. A broken one is dropped. */
    fun add(wav: ByteArray, turn: String, n: Int) {
        val pcm = try { Wav.decode(wav) } catch (e: Exception) { return }
        owed.incrementAndGet()
        queue.put(Item(round.get(), pcm, turn, n))
    }

    fun hush() {
        round.incrementAndGet()
        val dropped = ArrayList<Item>()
        queue.drainTo(dropped)
        owed.addAndGet(-dropped.size)
        synchronized(lock) { current?.let { runCatching { it.pause(); it.flush() } } }
    }

    /** Nothing playing and nothing waiting. */
    val idle: Boolean get() = owed.get() == 0

    fun close() {
        closed = true
        hush()
        worker.interrupt()
    }

    private fun play(item: Item) {
        val pcm = item.pcm
        val mask = if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.rate).setChannelMask(mask).build()
        val min = AudioTrack.getMinBufferSize(pcm.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)          // follows the media volume
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, pcm.rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        synchronized(lock) { current = track }
        try {
            track.play()
            var at = 0
            val step = pcm.rate / 10
            while (at < pcm.samples.size && item.round == round.get()) {
                val n = track.write(pcm.samples, at, minOf(step, pcm.samples.size - at))
                if (n < 0) return
                at += n
            }
            // write() returns once the audio is buffered, not heard: wait for the tail
            val frames = pcm.samples.size / pcm.channels
            val giveUp = System.currentTimeMillis() + frames * 1000L / pcm.rate + 2000   // a stuck track can't hang the queue
            while (item.round == round.get() && track.playbackHeadPosition < frames &&
                System.currentTimeMillis() < giveUp) Thread.sleep(20)
        } finally {
            synchronized(lock) { current = null }
            runCatching { track.stop() }
            track.release()
        }
    }
}
