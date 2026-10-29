package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.math.exp

/**
 * Training load from the heart rate (Banister TRIMP): every minute counts by how far the pulse is
 * into the heart rate reserve, weighted exponentially – one hour of hard training counts far more
 * than a day of walking. Minutes below [MIN_RESERVE] of the reserve (everyday life) do not count.
 */
object TrainingLoad {
    const val MIN_RESERVE = 0.4

    /** Load of one minute at [bpm]. */
    fun minute(bpm: Int, rest: Int, max: Int, male: Boolean): Double {
        if (max <= rest) return 0.0
        val r = ((bpm - rest).toDouble() / (max - rest)).coerceIn(0.0, 1.0)
        if (r < MIN_RESERVE) return 0.0
        return if (male) r * 0.64 * exp(1.92 * r) else r * 0.86 * exp(1.67 * r)
    }

    /** Load of a list of minute heart rates. */
    fun of(minutes: List<Int>, rest: Int, max: Int, male: Boolean): Double = minutes.sumOf { minute(it, rest, max, male) }

    /** A stretch of effort: consecutive loaded minutes with gaps of at most 10 minutes. */
    data class Session(val start: Long, val end: Long, val load: Double)

    /** Sessions in per-minute heart rates (epoch seconds of the minute, bpm). */
    fun sessions(points: List<Pair<Long, Int>>, rest: Int, max: Int, male: Boolean, maxGapMinutes: Int = 10): List<Session> {
        val out = ArrayList<Session>()
        var start = -1L
        var last = -1L
        var load = 0.0
        for ((t, bpm) in points.sortedBy { it.first }) {
            val l = minute(bpm, rest, max, male)
            if (l <= 0) continue
            if (start >= 0 && t - last > maxGapMinutes * 60L) {
                out += Session(start, last + 60, load)
                start = -1
                load = 0.0
            }
            if (start < 0) start = t
            last = t
            load += l
        }
        if (start >= 0) out += Session(start, last + 60, load)
        return out
    }

    /**
     * Hours until recovered: each session needs about 0.3 h per load point (a hard hour ≈ 30 h),
     * at most 72 h, minus the time since it ended. The longest remaining need counts.
     */
    fun recoveryHours(sessions: List<Session>, now: Long): Double =
        sessions.maxOfOrNull { s -> (minOf(s.load * 0.3, 72.0) - (now - s.end) / 3600.0) }?.coerceAtLeast(0.0) ?: 0.0

    enum class Status(val label: String, val text: String) {
        LOW("Wenig", "Weniger Belastung als in den Wochen davor – gut zur Erholung, auf Dauer verlierst du Form."),
        OPTIMAL("Im grünen Bereich", "Deine Belastung passt zu dem, was du gewohnt bist."),
        HIGH("Hoch", "Mehr als gewohnt. Für eine Woche okay – achte auf Schlaf und Erholung."),
        OVERLOAD("Zu viel", "Deutlich mehr als du gewohnt bist. Das Verletzungs- und Überlastungsrisiko steigt – plane Ruhetage ein."),
    }

    data class Balance(val acute: Double, val chronic: Double, val ratio: Double, val status: Status)

    /**
     * Last 7 days against the last 28 (per day). Null with fewer than [minDays] days of data or
     * almost no training.
     */
    fun balance(daily: Map<LocalDate, Double>, today: LocalDate, minDays: Int = 14): Balance? {
        val known = daily.filterKeys { it > today.minusDays(28) && !it.isAfter(today) }
        if (known.size < minDays) return null
        val acute = (0L..6L).sumOf { daily[today.minusDays(it)] ?: 0.0 } / 7
        val chronic = (0L..27L).sumOf { daily[today.minusDays(it)] ?: 0.0 } / 28
        if (chronic < 5) return null
        val ratio = acute / chronic
        val status = when {
            ratio < 0.8 -> Status.LOW
            ratio <= 1.3 -> Status.OPTIMAL
            ratio <= 1.5 -> Status.HIGH
            else -> Status.OVERLOAD
        }
        return Balance(acute, chronic, ratio, status)
    }
}

/** Heart rate recovery: how far the pulse drops in the first minute after stopping. */
object HeartRateRecovery {
    fun rating(drop: Int): String = when {
        drop < 12 -> "schwach"
        drop < 20 -> "normal"
        drop < 30 -> "gut"
        else -> "sehr gut"
    }
}

/**
 * Fitness estimate (VO₂max) from maximum and resting heart rate (Uth et al. 2004:
 * 15.3 × HFmax / HFruhe). Only a rough estimate – it is as good as the maximum heart rate behind it.
 */
object Vo2Max {
    fun estimate(maxHr: Int, restingHr: Int): Double? =
        if (restingHr in 30..110 && maxHr in 120..230 && maxHr > restingHr) 15.3 * maxHr / restingHr else null

    // Lower limits of "mittel", "gut", "sehr gut" and "hervorragend" per age group (<30, 30s, 40s, 50s, 60+).
    private val men = listOf(intArrayOf(37, 42, 46, 52), intArrayOf(35, 40, 44, 50), intArrayOf(33, 37, 41, 47), intArrayOf(30, 34, 38, 44), intArrayOf(26, 30, 34, 40))
    private val women = listOf(intArrayOf(31, 35, 39, 44), intArrayOf(29, 33, 37, 42), intArrayOf(27, 31, 35, 40), intArrayOf(24, 28, 32, 37), intArrayOf(22, 26, 30, 35))

    /** Rating for [age] and sex, roughly after the ACSM tables. */
    fun rating(vo2: Double, age: Int, male: Boolean): String {
        val group = when {
            age < 30 -> 0
            age < 40 -> 1
            age < 50 -> 2
            age < 60 -> 3
            else -> 4
        }
        val limits = (if (male) men else women)[group]
        return when {
            vo2 < limits[0] -> "schwach"
            vo2 < limits[1] -> "mittel"
            vo2 < limits[2] -> "gut"
            vo2 < limits[3] -> "sehr gut"
            else -> "hervorragend"
        }
    }
}
