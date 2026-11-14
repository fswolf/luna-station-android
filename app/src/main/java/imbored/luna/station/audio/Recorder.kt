package imbored.luna.station.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import imbored.luna.station.station.StationError

/**
 * One recording: 16 kHz mono, the mic open only for as long as it runs (the phone's
 * mic indicator goes off between turns). Blocking: run it off the main thread.
 *
 * VOICE_RECOGNITION is the source tuned for speech-to-text: no automatic gain, no
 * heavy processing, so Whisper and her voice prints get the voice as it was.
 */
class Recorder {
    @Volatile private var ending = false
    @Volatile private var cancelled = false

    /** Tap to send: stop now and keep what was said. */
    fun finish() { ending = true }

    /** Leaving the screen: stop now and throw it away. */
    fun cancel() { cancelled = true; ending = true }

    /** The recording, or null if nobody spoke (or it was cancelled). */
    @SuppressLint("MissingPermission")   // the screen asks for RECORD_AUDIO before starting this
    fun record(): ShortArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw StationError("The microphone wouldn't open. Is another app using it?")
        }
        val gate = SpeechGate(RATE)
        val chunks = ArrayList<ShortArray>()
        val block = ShortArray(RATE / 10)            // 100 ms
        try {
            rec.startRecording()
            while (!ending) {
                val n = rec.read(block, 0, block.size)
                if (n < 0) throw StationError("The microphone stopped working (error $n).")
                if (n == 0) continue
                chunks += block.copyOf(n)
                when (gate.feed(block, n)) {
                    SpeechGate.Verdict.DONE -> break
                    SpeechGate.Verdict.NOTHING -> return null
                    SpeechGate.Verdict.LISTEN -> {}
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (cancelled || !gate.heard) return null
        val out = ShortArray(chunks.sumOf { it.size })
        var at = 0
        chunks.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    companion object {
        const val RATE = 16000
    }
}
