package de.waschbeken.heliolink.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One night's vital signs. Null = not measured that night. */
data class NightVitals(
    val date: LocalDate,
    /** Resting heart rate of the day (strap) or the lowest heart rate of the night. */
    val restingHr: Double? = null,
    /** Average HRV of the night in ms. */
    val hrv: Double? = null,
    /** Typical (median) skin temperature of the night in °C. */
    val temperature: Double? = null,
    /** Average breaths per minute during sleep. */
    val breath: Double? = null,
)

/**
 * Early warning: when several night values move away from your own normal at once – skin
 * temperature and resting heart rate up, HRV down, breathing faster – the body is often fighting
 * something, typically a day before you feel ill. Not a diagnosis.
 */
object BodyCheck {
    enum class Vital(val label: String, val unit: String, val decimals: Int) {
        TEMPERATURE("Hauttemperatur", "°C", 1),
        RESTING_HR("Ruhepuls", "bpm", 0),
        HRV("HRV", "ms", 0),
        BREATH("Atemfrequenz", "/min", 1),
    }

    enum class Level { NORMAL, WATCH, ALERT }

    data class Signal(val vital: Vital, val value: Double, val usual: Double, val strong: Boolean) {
        val deviation get() = value - usual

        /** E.g. "+0,6 °C" or "−18 %" (HRV as percent). */
        val text: String
            get() = if (vital == Vital.HRV) {
                val pct = (deviation / usual * 100).roundToInt()
                (if (pct >= 0) "+" else "−") + abs(pct) + " %"
            } else {
                (if (deviation >= 0) "+" else "−") + "%.${vital.decimals}f".format(abs(deviation)) + " " + vital.unit
            }
    }

    data class Result(
        val date: LocalDate,
        val level: Level,
        val signals: List<Signal>,
        /** Nights in a row (up to the checked one) that looked like this. */
        val nights: Int,
        /** Nights the normal range is based on. */
        val baselineNights: Int,
    )

    /** Smallest spread assumed for each value, so a very steady history does not make tiny changes look big. */
    private fun minSpread(vital: Vital, median: Double) = when (vital) {
        Vital.TEMPERATURE -> 0.15
        Vital.RESTING_HR -> 1.5
        Vital.HRV -> median * 0.1
        Vital.BREATH -> 0.4
    }

    /** How far a value must move (in the unhealthy direction) to count, and to count as strong. */
    private fun limits(vital: Vital, median: Double): Pair<Double, Double> = when (vital) {
        Vital.TEMPERATURE -> 0.3 to 0.6
        Vital.RESTING_HR -> 3.0 to 6.0
        Vital.HRV -> median * 0.12 to median * 0.25
        Vital.BREATH -> 0.8 to 1.5
    }

    private fun value(n: NightVitals, vital: Vital) = when (vital) {
        Vital.TEMPERATURE -> n.temperature
        Vital.RESTING_HR -> n.restingHr
        Vital.HRV -> n.hrv
        Vital.BREATH -> n.breath
    }

    fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /**
     * Checks the last night of [history] against the up to [baselineDays] nights before it. Null if
     * there are not yet [minBaseline] earlier nights for any value.
     */
    fun check(history: List<NightVitals>, minBaseline: Int = 7, baselineDays: Int = 30): Result? {
        val sorted = history.sortedBy { it.date }
        val last = sorted.lastOrNull() ?: return null
        val result = checkNight(sorted, sorted.lastIndex, minBaseline, baselineDays) ?: return null
        if (result.level == Level.NORMAL) return result
        // Count the nights in a row before that looked the same.
        var nights = 1
        var i = sorted.lastIndex - 1
        while (i >= 0 && last.date.toEpochDay() - sorted[i].date.toEpochDay() == nights.toLong()) {
            val before = checkNight(sorted, i, minBaseline, baselineDays) ?: break
            if (before.level == Level.NORMAL) break
            nights++
            i--
        }
        return result.copy(nights = nights)
    }

    private fun checkNight(sorted: List<NightVitals>, index: Int, minBaseline: Int, baselineDays: Int): Result? {
        val night = sorted[index]
        val first = night.date.minusDays(baselineDays.toLong())
        // The two nights right before are left out, so a cold that started earlier does not hide itself.
        val base = sorted.subList(0, index).filter { it.date >= first && it.date < night.date.minusDays(2) }
        val signals = ArrayList<Signal>()
        var used = 0
        for (vital in Vital.entries) {
            val v = value(night, vital) ?: continue
            val history = base.mapNotNull { value(it, vital) }
            if (history.size < minBaseline) continue
            used = max(used, history.size)
            val med = median(history)
            val spread = max(1.4826 * median(history.map { abs(it - med) }), minSpread(vital, med))
            // Unhealthy direction: HRV down, everything else up.
            val off = if (vital == Vital.HRV) med - v else v - med
            val (limit, strongLimit) = limits(vital, med)
            if (off >= limit && off >= 2 * spread) {
                signals += Signal(vital, v, med, strong = off >= strongLimit && off >= 3 * spread)
            }
        }
        if (used == 0) return null
        var score = 0
        for (s in signals) {
            score += if (s.strong) 2 else 1
            if (s.vital == Vital.TEMPERATURE) score += 1
        }
        val level = when {
            score >= 4 && signals.size >= 2 -> Level.ALERT
            score >= 2 -> Level.WATCH
            else -> Level.NORMAL
        }
        return Result(night.date, level, signals, 1, used)
    }
}

/**
 * HRV status like on sports watches: the average of the last 7 nights compared with your usual
 * range from the weeks before.
 */
object HrvStatus {
    enum class Status(val label: String, val text: String) {
        BALANCED("Ausgeglichen", "Deine HRV liegt in deinem üblichen Bereich – der Körper ist im Gleichgewicht."),
        LOW("Niedrig", "Die HRV liegt unter deinem üblichen Bereich: mehr Belastung als sonst (Stress, wenig Schlaf, Training, Alkohol, Krankheit)."),
        POOR("Sehr niedrig", "Die HRV liegt deutlich unter deinem Bereich. Gönn dir Ruhe und Schlaf; hält das an, kann eine Krankheit dahinterstecken."),
        HIGH("Ungewöhnlich hoch", "Die HRV liegt über deinem üblichen Bereich. Meist gut erholt – manchmal auch eine Gegenreaktion nach starker Belastung."),
    }

    data class Result(
        val status: Status,
        val weekAverage: Double,
        val low: Double,
        val high: Double,
        val nights: Int,
        /** 7-night averages per day of the last weeks, for a chart. */
        val series: List<Pair<LocalDate, Double>>,
    )

    /** 7-night average ending on each day that has at least 3 nights in its window. */
    fun rolling(nightly: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> {
        val out = ArrayList<Pair<LocalDate, Double>>()
        var d = from
        while (!d.isAfter(to)) {
            val window = (0L..6L).mapNotNull { nightly[d.minusDays(it)] }
            if (window.size >= 3) out += d to window.average()
            d = d.plusDays(1)
        }
        return out
    }

    /** Null until there are at least [minNights] nights in the last 60 days and 3 in the last week. */
    fun status(nightly: Map<LocalDate, Double>, today: LocalDate, minNights: Int = 14): Result? {
        val recent = nightly.filterKeys { it > today.minusDays(60) && !it.isAfter(today) }
        if (recent.size < minNights) return null
        val week = (0L..6L).mapNotNull { recent[today.minusDays(it)] }
        if (week.size < 3) return null
        val avg = week.average()
        // Usual range: spread of the 7-night averages before this week.
        val before = rolling(recent, today.minusDays(59), today.minusDays(7)).map { it.second }
        val base = if (before.size >= 7) before else recent.filterKeys { it <= today.minusDays(7) }.values.toList()
        if (base.size < 5) return null
        val mean = base.average()
        val sd = sqrt(base.sumOf { (it - mean) * (it - mean) } / base.size)
        val half = max(sd * 1.28, mean * 0.05) // about the middle 80 %, at least ±5 %
        val low = mean - half
        val high = mean + half
        val status = when {
            avg < low - half -> Status.POOR
            avg < low -> Status.LOW
            avg > high -> Status.HIGH
            else -> Status.BALANCED
        }
        return Result(status, avg, low, high, recent.size, rolling(recent, today.minusDays(41), today))
    }
}
