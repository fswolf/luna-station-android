package imbored.luna.station.audio

import imbored.luna.station.audio.SpeechGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {
    private val rate = 16000
    private val quiet = ShortArray(rate / 10)
    private val loud = ShortArray(rate / 10) { if (it % 2 == 0) 8000 else -8000 }

    private fun SpeechGate.run(block: ShortArray, ms: Int): Verdict {
        var v = Verdict.LISTEN
        repeat(ms / 100) { v = feed(block, block.size) }
        return v
    }

    @Test
    fun nobodySpeaks() {
        val g = SpeechGate(rate)
        assertEquals(Verdict.LISTEN, g.run(quiet, 7900))
        assertEquals(Verdict.NOTHING, g.run(quiet, 100))
    }

    @Test
    fun speechThenAPause() {
        val g = SpeechGate(rate)
        g.run(quiet, 500)
        assertEquals(Verdict.LISTEN, g.run(loud, 2000))
        assertEquals(Verdict.LISTEN, g.run(quiet, 1200))       // a breath, not the end
        assertEquals(Verdict.DONE, g.run(quiet, 100))
        assertTrue(g.heard)
    }

    @Test
    fun aBreathDoesntEndIt() {
        val g = SpeechGate(rate)
        g.run(loud, 1000)
        g.run(quiet, 1000)
        assertEquals(Verdict.LISTEN, g.run(loud, 500))
        assertEquals(Verdict.LISTEN, g.run(quiet, 1200))
    }

    @Test
    fun hardCap() {
        val g = SpeechGate(rate)
        assertEquals(Verdict.LISTEN, g.run(loud, 29900))
        assertEquals(Verdict.DONE, g.run(loud, 100))
    }
}
