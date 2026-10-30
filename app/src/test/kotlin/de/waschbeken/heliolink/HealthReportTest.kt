package de.waschbeken.heliolink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.ui.progress.HealthReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The PDF for the doctor, with and without data; a copy lands next to the screenshots. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-rDE")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HealthReportTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()

    private fun create(days: Int, name: String) {
        val file = try {
            HealthReport.create(app, app.db, app.prefs, days)
        } catch (e: LinkageError) {
            Assume.assumeNoException("PDF wird von Robolectric nicht unterstützt", e)
            return
        }
        val bytes = file.readBytes()
        assertEquals("%PDF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertTrue("PDF zu klein: ${bytes.size} Bytes", bytes.size > 1000)
        file.copyTo(File("build/screenshots/bericht/$name.pdf").apply { parentFile?.mkdirs() }, overwrite = true)
    }

    @Test
    fun withoutData() = create(30, "leer-30")

    @Test
    fun withDemoData() {
        DemoData.fill(app.db)
        app.db.addEvent(System.currentTimeMillis() / 1000 - 3600, "Medikament", "Ibuprofen 400")
        create(30, "demo-30")
        create(14, "demo-14")
    }
}
