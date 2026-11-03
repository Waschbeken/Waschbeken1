package de.waschbeken.heliolink.core

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * A reminder the strap gives by vibrating, e.g. "Tabletten" at 08:00 on weekdays. [days] is a bit
 * mask with Monday = bit 0 … Sunday = bit 6 (0 = every day); [pulses] the number of vibrations.
 */
data class Reminder(
    val id: Int,
    val text: String,
    val hour: Int,
    val minute: Int,
    val days: Int = 0,
    val pulses: Int = 3,
    val enabled: Boolean = true,
) {
    fun onDay(day: DayOfWeek) = days == 0 || days and (1 shl (day.value - 1)) != 0

    /** "08:00 · Mo–Fr", "21:30 · täglich", "12:00 · Mo, Mi, Fr". */
    val schedule: String
        get() = "%02d:%02d · ".format(hour, minute) + daysText(days)

    fun encode() = "$id|$hour|$minute|$days|$pulses|${if (enabled) 1 else 0}|${text.replace("|", " ").replace("\n", " ")}"

    companion object {
        private val NAMES = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

        fun daysText(days: Int): String = when (days and 0x7f) {
            0, 0x7f -> "täglich"
            0x1f -> "Mo–Fr"
            0x60 -> "Sa, So"
            else -> (0..6).filter { days and (1 shl it) != 0 }.joinToString(", ") { NAMES[it] }
        }

        fun decode(line: String): Reminder? {
            val p = line.split('|', limit = 7)
            if (p.size < 7) return null
            return Reminder(
                id = p[0].toIntOrNull() ?: return null,
                hour = p[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null,
                minute = p[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null,
                days = p[3].toIntOrNull() ?: 0,
                pulses = p[4].toIntOrNull()?.coerceIn(1, 5) ?: 3,
                enabled = p[5] == "1",
                text = p[6],
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
            for (offset in 0L..7L) {
                val day = now.toLocalDate().plusDays(offset)
                if (!r.onDay(day.dayOfWeek)) continue
                val at = day.atTime(r.hour, r.minute).atZone(now.zone)
                if (!at.isAfter(now)) continue
                val b = best
                when {
                    b == null || at.isBefore(b) -> {
                        best = at
                        due.clear()
                        due += r
                    }
                    at.isEqual(b) -> due += r
                }
                break
            }
        }
        return best?.let { it to due.toList() }
    }
}
