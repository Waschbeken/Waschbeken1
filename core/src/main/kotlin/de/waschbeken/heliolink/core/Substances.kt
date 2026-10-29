package de.waschbeken.heliolink.core

import kotlin.math.pow

/**
 * How much caffeine or nicotine from the quick entries is still in the body. Simple model: the
 * whole amount counts from the moment of the entry and halves every half-life (caffeine about
 * 5 h, nicotine about 2 h; both differ from person to person).
 */
object Substances {
    enum class Kind(val label: String, val halfLifeHours: Double, val unit: String) {
        CAFFEINE("Koffein", 5.0, "mg"),
        NICOTINE("Nikotin", 2.0, "mg"),
    }

    data class Intake(val timestamp: Long, val amount: Double)

    /** Which substance an entry stands for, and roughly how much of it; null for other entries. */
    fun of(tag: String, caffeinePerEntry: Double = 80.0, nicotinePerEntry: Double = 1.0): Pair<Kind, Double>? {
        val t = tag.lowercase()
        return when {
            "espresso" in t -> Kind.CAFFEINE to 65.0
            "cola" in t -> Kind.CAFFEINE to 35.0
            "mate" in t -> Kind.CAFFEINE to 80.0
            "energy" in t -> Kind.CAFFEINE to 80.0
            "grüntee" in t || "schwarztee" in t || t == "tee" -> Kind.CAFFEINE to 40.0
            "koffein" in t || "kaffee" in t || "coffee" in t -> Kind.CAFFEINE to caffeinePerEntry
            "nikotin" in t || "zigarette" in t || "rauchen" in t || "vape" in t || "snus" in t || "e-zig" in t -> Kind.NICOTINE to nicotinePerEntry
            else -> null
        }
    }

    /** Amount still in the body at [at] (epoch seconds). */
    fun level(intakes: List<Intake>, kind: Kind, at: Long): Double =
        intakes.filter { it.timestamp <= at }.sumOf { it.amount * 0.5.pow((at - it.timestamp) / 3600.0 / kind.halfLifeHours) }

    /** First time from [from] on at which the level drops below [threshold], or null if not within 48 h. */
    fun timeBelow(intakes: List<Intake>, kind: Kind, threshold: Double, from: Long): Long? {
        var t = from
        val end = from + 48 * 3600
        while (t <= end) {
            if (level(intakes, kind, t) < threshold) return t
            t += 300
        }
        return null
    }
}
