package de.waschbeken.heliolink.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Oxygen drops in sleep: how often SpO₂ falls by at least [DROP] points below the level just
 * before. Many drops per hour can point to breathing pauses (sleep apnea). The strap only measures
 * every few minutes, so short drops can be missed – the count is a lower bound. Not a diagnosis.
 */
object OxygenDrops {
    const val DROP = 3

    data class Result(
        val samples: Int,
        val drops: Int,
        val perHour: Double,
        val lowest: Int,
        val minutesBelow90: Int,
        /** Typical minutes between two measurements. */
        val spacingMinutes: Double,
    ) {
        val rating: String
            get() = when {
                perHour < 5 -> "unauffällig"
                perHour < 15 -> "leicht erhöht"
                perHour < 30 -> "deutlich erhöht"
                else -> "stark erhöht"
            }
    }

    /** [values] are the SpO₂ measurements during the night, [sleepMinutes] the time asleep. */
    fun analyze(values: List<ValueSample>, sleepMinutes: Int): Result? {
        val v = values.filter { it.value in 60..100 }.sortedBy { it.timestamp }
        if (v.size < 6 || sleepMinutes < 60) return null
        val gaps = v.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 60.0 }.filter { it > 0 }
        val spacing = BodyCheck.median(gaps)
        var drops = 0
        var inDrop = false
        var below90 = 0.0
        for (i in v.indices) {
            val s = v[i]
            // Level before: median of the measurements of the 15 minutes before (at least one).
            val before = v.subList(0, i).filter { it.timestamp >= s.timestamp - 15 * 60 }.map { it.value.toDouble() }
            if (before.isNotEmpty()) {
                val base = BodyCheck.median(before)
                if (!inDrop && s.value <= base - DROP) {
                    drops++
                    inDrop = true
                } else if (inDrop && s.value >= base - 1) {
                    inDrop = false
                }
            }
            if (s.value < 90) below90 += minOf(if (i + 1 < v.size) (v[i + 1].timestamp - s.timestamp) / 60.0 else spacing, 10.0)
        }
        return Result(v.size, drops, drops * 60.0 / sleepMinutes, v.minOf { it.value }, below90.roundToInt(), spacing)
    }
}

/**
 * At which time falling asleep goes along with the best nights: the sleep score per hour window of
 * the bedtime, compared with all other nights.
 */
object BedtimeInsight {
    data class Result(
        /** Start of the best hour window, minutes after midnight. */
        val from: Int,
        val to: Int,
        val nights: Int,
        val score: Double,
        val otherScore: Double,
        val total: Int,
    ) {
        val difference get() = score - otherScore
    }

    /**
     * [nights] are (minute of the day the night started, score). Needs [minTotal] nights and
     * [minInWindow] in the best window; null if no window stands out by at least [minDifference].
     */
    fun analyze(nights: List<Pair<Int, Int>>, minTotal: Int = 10, minInWindow: Int = 3, minDifference: Double = 3.0): Result? {
        if (nights.size < minTotal) return null
        // Shifted by 12 h, so the evening and the early morning lie next to each other.
        val shifted = nights.map { ((it.first + 12 * 60) % (24 * 60)) to it.second }
        var best: Result? = null
        var start = 0
        while (start < 24 * 60) {
            val inWindow = shifted.filter { it.first in start until start + 60 }
            if (inWindow.size >= minInWindow) {
                val outside = shifted.filter { it.first !in start until start + 60 }
                if (outside.size >= minInWindow) {
                    val r = Result(
                        from = (start + 12 * 60) % (24 * 60),
                        to = (start + 13 * 60) % (24 * 60),
                        nights = inWindow.size,
                        score = inWindow.map { it.second }.average(),
                        otherScore = outside.map { it.second }.average(),
                        total = nights.size,
                    )
                    if (best == null || r.score > best.score) best = r
                }
            }
            start += 30
        }
        return best?.takeIf { it.difference >= minDifference }
    }
}

/**
 * Checks a stretch of beat intervals for an irregular rhythm: large beat-to-beat changes (RMSSD
 * relative to the mean), a random up and down (turning points) and a wide spread of intervals
 * (Shannon entropy). Regular rhythms, also with normal breathing variation, stay below. Optical
 * sensors produce artefacts, so single hits mean little. Not a diagnosis – that needs an ECG.
 */
object RhythmCheck {
    const val BEATS = 64

    data class Window(val beats: Int, val heartRate: Int, val nRmssd: Double, val turningPoints: Double, val entropy: Double) {
        val irregular get() = nRmssd > 0.1 && turningPoints in 0.54..0.77 && entropy > 0.7
    }

    /** Analyses [rr] (ms); null if fewer than [BEATS] usable intervals. */
    fun analyze(rr: List<Int>): Window? {
        val plausible = rr.filter { it in 300..2000 }
        if (plausible.size < BEATS) return null
        val med = BodyCheck.median(plausible.map { it.toDouble() })
        // Missed or doubled beats from the sensor, not from the heart.
        val clean = plausible.filter { it > med * 0.5 && it < med * 1.8 }
        if (clean.size < BEATS) return null
        val x = clean.takeLast(BEATS).map { it.toDouble() }
        val mean = x.average()
        val rmssd = sqrt(x.zipWithNext { a, b -> (b - a) * (b - a) }.average())
        var turns = 0
        for (i in 1 until x.size - 1) {
            if ((x[i] > x[i - 1] && x[i] > x[i + 1]) || (x[i] < x[i - 1] && x[i] < x[i + 1])) turns++
        }
        // Entropy of the intervals without the 8 shortest and longest, in 16 equal bins.
        val trimmed = x.sorted().drop(8).dropLast(8)
        val lo = trimmed.first()
        val width = max((trimmed.last() - lo) / 16, 1e-9)
        val counts = IntArray(16)
        for (v in trimmed) counts[((v - lo) / width).toInt().coerceIn(0, 15)]++
        val entropy = counts.filter { it > 0 }.sumOf { c -> val p = c.toDouble() / trimmed.size; -p * ln(p) } / ln(16.0)
        return Window(x.size, (60_000 / mean).roundToInt(), rmssd / mean, turns.toDouble() / (x.size - 2), entropy)
    }

    /** Whether repeated findings are worth a hint: at least [minWindows] irregular stretches on [minDays] days. */
    fun worthAHint(irregularDays: List<Long>, minWindows: Int = 4, minDays: Int = 2): Boolean =
        irregularDays.size >= minWindows && irregularDays.toSet().size >= minDays
}
