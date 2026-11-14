package imbored.luna.station.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test
    fun roundTrip() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234)
        val wav = Wav.encode(samples, 16000)
        assertEquals(44 + samples.size * 2, wav.size)
        val pcm = Wav.decode(wav)
        assertEquals(16000, pcm.rate)
        assertEquals(1, pcm.channels)
        assertArrayEquals(samples, pcm.samples)
    }

    @Test
    fun headerIsWhatLunaExpects() {
        val wav = Wav.encode(ShortArray(10), 16000)
        assertEquals("RIFF", String(wav, 0, 4))      // station.py refuses anything else on /talk
        assertEquals("WAVE", String(wav, 8, 4))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 20, b.getInt(4))
        assertEquals(32000, b.getInt(28))             // byte rate
    }

    @Test
    fun skipsExtraChunksLikeLIST() {
        // RIFF, fmt, a LIST chunk with an odd size (padded), then data: what some encoders write
        val data = shortArrayOf(5, -5, 7)
        val b = ByteBuffer.allocate(12 + 24 + 8 + 4 + 8 + 6).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(0); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(24000); b.putInt(48000); b.putShort(2); b.putShort(16)
        b.put("LIST".toByteArray()); b.putInt(3); b.put(byteArrayOf(1, 2, 3, 0))
        b.put("data".toByteArray()); b.putInt(6); data.forEach { b.putShort(it) }
        val pcm = Wav.decode(b.array())
        assertEquals(24000, pcm.rate)
        assertArrayEquals(data, pcm.samples)
    }

    @Test
    fun streamedSizeTakesWhatIsThere() {
        val wav = Wav.encode(shortArrayOf(1, 2, 3, 4), 16000)
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1)   // 0xFFFFFFFF
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), Wav.decode(wav).samples)
    }

    @Test
    fun refusesNonWav() {
        assertThrows(IllegalArgumentException::class.java) { Wav.decode("not audio at all".toByteArray()) }
    }
}
