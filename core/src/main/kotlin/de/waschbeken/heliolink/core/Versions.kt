package de.waschbeken.heliolink.core

/** Version helpers for the in-app updater (release tags look like `app-v1.2.3`). */
object Versions {
    private val TAG = Regex("^(?:app-)?v?(\\d+(?:\\.\\d+){0,3})(?:[-+].*)?$")

    fun ofTag(tag: String): String? = TAG.matchEntire(tag.trim())?.groupValues?.get(1)

    /** Numeric comparison of dotted versions ("1.10.0" > "1.9.3"); suffixes like "-debug" are ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').substringBefore('+').removePrefix("v")
            .split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
