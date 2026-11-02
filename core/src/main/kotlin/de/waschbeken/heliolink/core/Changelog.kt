package de.waschbeken.heliolink.core

/**
 * "Was ist neu": the release notes shipped with the app (assets/changelog.md). Each version starts
 * with a line "## 1.2.3", followed by its points as "- …" lines; other lines are ignored.
 */
object Changelog {
    data class Entry(val version: String, val items: List<String>)

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var version: String? = null
        var items = ArrayList<String>()
        fun flush() {
            val v = version ?: return
            if (items.isNotEmpty()) out += Entry(v, items)
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    flush()
                    version = line.removePrefix("## ").trim()
                    items = ArrayList()
                }
                line.startsWith("- ") && version != null -> items += line.removePrefix("- ").trim()
                // A longer point may continue on the next line.
                line.isNotEmpty() && version != null && items.isNotEmpty() && !line.startsWith("#") ->
                    items[items.lastIndex] = items.last() + " " + line
            }
        }
        flush()
        return out.sortedWith { a, b -> Versions.compare(b.version, a.version) }
    }

    /** The entries newer than [lastSeen] up to [current], newest first; with no [lastSeen] only [current]. */
    fun since(entries: List<Entry>, lastSeen: String?, current: String): List<Entry> = entries.filter { e ->
        Versions.compare(e.version, current) <= 0 &&
            if (lastSeen.isNullOrBlank()) Versions.compare(e.version, current) == 0 else Versions.compare(e.version, lastSeen) > 0
    }
}
