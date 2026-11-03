package de.waschbeken.heliolink.core

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * A reminder the strap gives by vibrating, e.g. "Tabletten" at 08:00 on weekdays. [days] is a bit
 * mask with Monday = bit 0 … Sunday = bit 6 (0 = every day); [pulses] the number of vibrations.
 * With [repeatMinutes] > 0 it repeats from the start time every so many minutes until [endHour]:[endMinute].
 */
data class Reminder(
    val id: Int,
    val text: String,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val pulses: Int = 3,
    val enabled: Boolean = true,
    val repeatMinutes: Int = 0,
    val endHour: Int = hour,
    val endMinute: Int = minute,
) {
    fun onDay(day: DayOfWeek) = days == 0 || days and (1 shl (day.value - 1)) != 0

    /** Minutes of the day at which it is due on a day it is active. */
    fun times(): List<Int> {
        val start = hour * 60 + minute
        if (repeatMinutes <= 0) return listOf(start)
        val end = endHour * 60 + endMinute
        return (start..maxOf(start, end) step repeatMinutes).toList()
    }

    /** "08:00 · Mo–Fr", "21:30 · täglich", "09:00–18:00 alle 2 h · Mo–Fr". */
    val schedule: String
        get() = (
            if (repeatMinutes > 0) {
                "%02d:%02d–%02d:%02d alle ".format(hour, minute, endHour, endMinute) +
                    (if (repeatMinutes % 60 == 0) "${repeatMinutes / 60} h" else "$repeatMinutes min")
            } else {
                "%02d:%02d".format(hour, minute)
            }
            ) + " · " + daysText(days)

    fun encode() = "$id|$hour|$minute|$days|$pulses|${if (enabled) 1 else 0}|$repeatMinutes|$endHour|$endMinute|" +
        text.replace("|", " ").replace("\n", " ")

    companion object {
        private val NAMES = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

        fun daysText(days: Int): String = when (days and 0x7f) {
            0, 0x7f -> "täglich"
            0x1f -> "Mo–Fr"
            0x60 -> "Sa, So"
            else -> (0..6).filter { days and (1 shl it) != 0 }.joinToString(", ") { NAMES[it] }
        }

        fun decode(line: String): Reminder? {
            // Version 1 had 7 fields (text last), version 2 adds the repetition before the text.
            val p = line.split('|', limit = 10)
            if (p.size < 7) return null
            val v2 = p.size == 10
            val hour = p[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = p[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return Reminder(
                id = p[0].toIntOrNull() ?: return null,
                hour = hour,
                minute = minute,
                days = p[3].toIntOrNull() ?: 0,
                pulses = p[4].toIntOrNull()?.coerceIn(1, 5) ?: 3,
                enabled = p[5] == "1",
                repeatMinutes = if (v2) p[6].toIntOrNull()?.coerceIn(0, 720) ?: 0 else 0,
                endHour = if (v2) p[7].toIntOrNull()?.takeIf { it in 0..23 } ?: hour else hour,
                endMinute = if (v2) p[8].toIntOrNull()?.takeIf { it in 0..59 } ?: minute else minute,
                text = if (v2) p[9] else p.drop(6).joinToString("|"),
            )
        }
    }
}

object Reminders {
    /** The next time any enabled reminder is due after [now], with the reminders due then. */
    fun next(list: List<Reminder>, now: ZonedDateTime): Pair<ZonedDateTime, List<Reminder>>? {
        var best: ZonedDateTime? = null
        val due = ArrayList<Reminder>()
        for (r in list.filter { it.enabled }) {
            val at = (0L..7L).asSequence()
                .map { now.toLocalDate().plusDays(it) }
                .filter { r.onDay(it.dayOfWeek) }
                .flatMap { day -> r.times().asSequence().map { m -> day.atTime(m / 60, m % 60).atZone(now.zone) } }
                .firstOrNull { it.isAfter(now) } ?: continue
            val b = best
            when {
                b == null || at.isBefore(b) -> {
                    best = at
                    due.clear()
                    due += r
                }
                at.isEqual(b) -> due += r
            }
        }
        return best?.let { it to due.toList() }
    }
}
