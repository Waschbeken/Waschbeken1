package de.waschbeken.heliolink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
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

/**
 * The report for the doctor, with and without data. Robolectric cannot write PDFs, so the pages
 * are drawn into bitmaps (saved next to the screenshots); the PDF itself is checked where possible.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-rDE")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HealthReportTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()

    private class BitmapSink : HealthReport.PageSink {
        val pages = ArrayList<Bitmap>()

        override fun begin(number: Int): Canvas {
            val b = Bitmap.createBitmap(HealthReport.W, HealthReport.H, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            pages += b
            return Canvas(b)
        }

        override fun end() = Unit
    }

    private fun draw(days: Int, name: String): Int {
        val sink = BitmapSink()
        HealthReport.draw(sink, app.db, app.prefs, days)
        val dir = File("build/screenshots/bericht").apply { mkdirs() }
        sink.pages.forEachIndexed { i, page ->
            File(dir, "$name-seite-${i + 1}.jpg").outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return sink.pages.size
    }

    private fun pdfSupported(): Boolean {
        val doc = PdfDocument()
        return try {
            doc.finishPage(doc.startPage(PdfDocument.PageInfo.Builder(10, 10, 1).create()))
            true
        } catch (e: IllegalStateException) {
            false
        } finally {
            doc.close()
        }
    }

    @Test
    fun withoutData() {
        assertTrue(draw(30, "leer-30") >= 1)
    }

    @Test
    fun withDemoData() {
        DemoData.fill(app.db)
        app.db.addEvent(System.currentTimeMillis() / 1000 - 3600, "Medikament", "Ibuprofen 400")
        assertTrue(draw(30, "demo-30") >= 2)
        draw(90, "demo-90")
    }

    @Test
    fun pdfFile() {
        Assume.assumeTrue("Robolectric kann keine PDFs schreiben", pdfSupported())
        DemoData.fill(app.db)
        val bytes = HealthReport.create(app, app.db, app.prefs, 30).readBytes()
        assertEquals("%PDF", String(bytes, 0, 4, Charsets.US_ASCII))
    }
}
