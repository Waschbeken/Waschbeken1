package de.waschbeken.heliolink.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Heute fällt auf": the few things about last night and yesterday that differ clearly from the
 * user's own normal, as short sentences. Nothing is said about values within the usual range.
 */
object DailyInsights {
    enum class Tone { GOOD, NEUTRAL, WATCH }

    data class Insight(val text: String, val tone: Tone, val weight: Double)

    data class Input(
        val sleepMinutes: Int? = null,
        /** Average sleep of the nights before. */
        val usualSleepMinutes: Double? = null,
        /** Fell asleep, minutes from midnight (evening negative, e.g. 23:30 = −30). */
        val bedtime: Int? = null,
        val usualBedtime: Double? = null,
        val restingHr: Int? = null,
        val usualRestingHr: Double? = null,
        val hrv: Double? = null,
        val usualHrv: Double? = null,
        /** Skin temperature of the night compared with the usual one, °C. */
        val temperatureDeviation: Double? = null,
        val stepsYesterday: Int? = null,
        val usualSteps: Double? = null,
        /** Minutes of sleep missing to the goal over the last 7 nights (nights above the goal do not make up for it). */
        val sleepDebtMinutes: Int? = null,
    )

    private fun hm(minutes: Int): String {
        val m = abs(minutes)
        return if (m >= 60) "%d:%02d h".format(m / 60, m % 60) else "$m min"
    }

    private fun clock(minutes: Double): String {
        val m = ((minutes.roundToInt() % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** At most [max] insights, the most striking first. */
    fun of(input: Input, max: Int = 3): List<Insight> {
        val out = ArrayList<Insight>()
        with(input) {
            if (sleepMinutes != null && usualSleepMinutes != null && usualSleepMinutes > 0) {
                val diff = (sleepMinutes - usualSleepMinutes).roundToInt()
                if (abs(diff) >= 45) {
                    out += Insight(
                        "Du hast ${hm(diff)} ${if (diff > 0) "länger" else "kürzer"} geschlafen als sonst (Ø ${hm(usualSleepMinutes.roundToInt())}).",
                        if (diff > 0) Tone.NEUTRAL else Tone.WATCH,
                        abs(diff) / 45.0,
                    )
                }
            }
            if (bedtime != null && usualBedtime != null) {
                val diff = (bedtime - usualBedtime).roundToInt()
                if (abs(diff) >= 60) {
                    out += Insight(
                        "Du bist ${hm(diff)} ${if (diff > 0) "später" else "früher"} eingeschlafen als üblich (sonst gegen ${clock(usualBedtime)}).",
                        if (diff > 0) Tone.WATCH else Tone.NEUTRAL,
                        abs(diff) / 60.0 * 0.8,
                    )
                }
            }
            if (restingHr != null && usualRestingHr != null) {
                val diff = (restingHr - usualRestingHr).roundToInt()
                if (diff >= 3) {
                    out += Insight(
                        "Ruhepuls $diff bpm über deinem Schnitt – der Körper arbeitet mehr (Stress, Alkohol, wenig Schlaf oder ein Infekt).",
                        Tone.WATCH, diff / 3.0 * 1.2,
                    )
                } else if (diff <= -3) {
                    out += Insight("Ruhepuls ${-diff} bpm unter deinem Schnitt – gut erholt.", Tone.GOOD, -diff / 3.0)
                }
            }
            if (hrv != null && usualHrv != null && usualHrv > 0) {
                val pct = ((hrv - usualHrv) / usualHrv * 100).roundToInt()
                if (pct <= -15) {
                    out += Insight("HRV ${-pct} % unter deinem Schnitt – gönn dir heute etwas Ruhe.", Tone.WATCH, -pct / 15.0 * 1.1)
                } else if (pct >= 15) {
                    out += Insight("HRV $pct % über deinem Schnitt – dein Nervensystem ist entspannt.", Tone.GOOD, pct / 15.0 * 0.9)
                }
            }
            if (temperatureDeviation != null && abs(temperatureDeviation) >= 0.4) {
                val text = String.format(Locale.GERMANY, "%+.1f", temperatureDeviation)
                out += Insight(
                    "Hauttemperatur nachts $text °C gegenüber normal" +
                        if (temperatureDeviation > 0) " – zusammen mit hohem Ruhepuls oft ein frühes Zeichen für einen Infekt." else ".",
                    if (temperatureDeviation > 0) Tone.WATCH else Tone.NEUTRAL,
                    abs(temperatureDeviation) / 0.4 * 1.1,
                )
            }
            if (sleepDebtMinutes != null && sleepDebtMinutes >= 180) {
                out += Insight(
                    "In den letzten 7 Nächten fehlen dir zusammen ${hm(sleepDebtMinutes)} Schlaf zu deinem Ziel – heute früher ins Bett hilft.",
                    Tone.WATCH,
                    sleepDebtMinutes / 180.0 * 0.9,
                )
            }
            if (stepsYesterday != null && usualSteps != null && usualSteps >= 1000) {
                val pct = ((stepsYesterday - usualSteps) / usualSteps * 100).roundToInt()
                val steps = String.format(Locale.GERMANY, "%,d", stepsYesterday)
                if (pct >= 40) {
                    out += Insight("Gestern $steps Schritte – $pct % mehr als sonst. Stark!", Tone.GOOD, pct / 40.0 * 0.7)
                } else if (pct <= -40) {
                    out += Insight("Gestern nur $steps Schritte – ${-pct} % weniger als sonst.", Tone.NEUTRAL, -pct / 40.0 * 0.6)
                }
            }
        }
        return out.sortedByDescending { it.weight }.take(max)
    }
}
