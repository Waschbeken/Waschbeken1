package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ChangelogTest {
    private val text = """
        # Was ist neu

        ## 1.0.10
        - Zehn
        ## 1.0.9
        - Neun eins
        - Neun zwei, die etwas
          länger ist
        ## 1.0.8
        Freitext ohne Punkt wird ignoriert
        - Acht
    """.trimIndent()

    @Test
    fun parsesVersionsNewestFirst() {
        val entries = Changelog.parse(text)
        assertEquals(listOf("1.0.10", "1.0.9", "1.0.8"), entries.map { it.version })
        assertEquals(listOf("Neun eins", "Neun zwei, die etwas länger ist"), entries[1].items)
        assertEquals(listOf("Acht"), entries[2].items)
    }

    @Test
    fun picksWhatIsNewSinceTheLastVersion() {
        val entries = Changelog.parse(text)
        assertEquals(listOf("1.0.10", "1.0.9"), Changelog.since(entries, "1.0.8", "1.0.10").map { it.version })
        assertEquals(listOf("1.0.9"), Changelog.since(entries, "1.0.8", "1.0.9").map { it.version })
        assertEquals(listOf("1.0.10"), Changelog.since(entries, null, "1.0.10").map { it.version })
        assertEquals(emptyList(), Changelog.since(entries, "1.0.10", "1.0.10"))
    }
}
