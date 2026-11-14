package imbored.luna.station.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM audio and its sample rate. */
class Pcm(val rate: Int, val channels: Int, val samples: ShortArray)

/** The plain RIFF WAV that both sides speak: 16-bit PCM, little-endian. */
object Wav {
    fun encode(samples: ShortArray, rate: Int): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)        // PCM, mono
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }

    /** Reads the fmt and data chunks, skipping any others (LIST and friends). */
    fun decode(bytes: ByteArray): Pcm {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && tag(b, 0) == "RIFF" && tag(b, 8) == "WAVE") { "not a WAV file" }
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(b, pos)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            val truncated = size < 0 || body + size > bytes.size
            if (truncated && id != "data") break
            when (id) {
                "fmt " -> {
                    require(b.getShort(body).toInt() == 1) { "only plain PCM WAV is supported" }
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(rate > 0 && bits == 16) { "only 16-bit WAV is supported" }
                    // a streamed WAV may claim more (or 0xFFFFFFFF) than it has: take what's there
                    val end = if (truncated) bytes.size else body + size
                    val out = ShortArray((end - body) / 2)
                    for (i in out.indices) out[i] = b.getShort(body + i * 2)
                    return Pcm(rate, channels, out)
                }
            }
            pos = body + size + (size and 1)          // chunks are word-aligned
        }
        throw IllegalArgumentException("WAV has no audio data")
    }

    private fun tag(b: ByteBuffer, at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
}
