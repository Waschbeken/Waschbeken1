package de.waschbeken.heliolink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.ui.loadChangelog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The "Was ist neu" notes shipped in the app can be read and every version has points. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ChangelogAssetTest {
    @Test
    fun changelogIsReadable() {
        val entries = loadChangelog(ApplicationProvider.getApplicationContext())
        assertTrue("Changelog leer", entries.isNotEmpty())
        entries.forEach { assertTrue("Version ${it.version} ohne Punkte", it.items.isNotEmpty()) }
    }
}
