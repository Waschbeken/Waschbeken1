package de.waschbeken.heliolink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionsTest {
    @Test
    fun parsesTags() {
        assertEquals("1.2.3", Versions.ofTag("app-v1.2.3"))
        assertEquals("1.0", Versions.ofTag("v1.0"))
        assertNull(Versions.ofTag("nightly"))
    }

    @Test
    fun comparesNumerically() {
        assertTrue(Versions.compare("1.10.0", "1.9.3") > 0)
        assertEquals(0, Versions.compare("1.0.0-debug", "1.0"))
        assertTrue(Versions.compare("0.1.0", "1.0.0") < 0)
    }
}
