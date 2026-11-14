package imbored.luna.station.audio

import kotlin.math.sqrt

/**
 * When to stop listening: the same rules as Luna's web station page. Speech is any
 * 100 ms block louder than [threshold]; after speech, [quietMs] of quiet ends it;
 * nothing at all for [giveUpMs] means nobody spoke; [maxMs] is the hard cap.
 *
 * A plain loudness check, so a loud room can keep it open until the cap. Phase 4 swaps
 * in Silero VAD for the hands-free station.
 */
class SpeechGate(
    private val rate: Int,
    private val threshold: Double = 0.015,
    private val quietMs: Int = 1300,
    private val giveUpMs: Int = 8000,
    private val maxMs: Int = 30000,
) {
    enum class Verdict { LISTEN, DONE, NOTHING }

    var heard = false
        private set
    private var samples = 0L
    private var quietSince = -1L

    fun feed(block: ShortArray, n: Int): Verdict {
        var sum = 0.0
        for (i in 0 until n) {
            val v = block[i] / 32768.0
            sum += v * v
        }
        samples += n
        val now = samples * 1000 / rate
        val loud = n > 0 && sqrt(sum / n) > threshold
        if (loud) {
            heard = true
            quietSince = -1
        } else if (heard && quietSince < 0) {
            quietSince = (samples - n) * 1000 / rate        // when this quiet block began
        }
        return when {
            heard && quietSince >= 0 && now - quietSince >= quietMs -> Verdict.DONE
            now >= maxMs -> if (heard) Verdict.DONE else Verdict.NOTHING
            !heard && now >= giveUpMs -> Verdict.NOTHING
            else -> Verdict.LISTEN
        }
    }
}
