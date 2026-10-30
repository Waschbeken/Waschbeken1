package de.waschbeken.heliolink

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.data.ThemeMode
import de.waschbeken.heliolink.ui.AppContent
import de.waschbeken.heliolink.ui.HelioTheme
import de.waschbeken.heliolink.ui.Period
import de.waschbeken.heliolink.ui.Routes
import de.waschbeken.heliolink.ui.ScreenLoads
import de.waschbeken.heliolink.ui.metrics.Metric
import de.waschbeken.heliolink.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Opens every screen of the app – once with an empty database (fresh install) and once with five
 * weeks of demo data – and fails on any crash. A screenshot of each screen lands in
 * app/build/screenshots (uploaded by CI), so changes can be looked at without a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()
    private var route by mutableStateOf<String?>(null)
    private lateinit var shots: File

    @Before
    fun strict() {
        ScreenLoads.strict = true
    }

    @After
    fun relaxed() {
        ScreenLoads.strict = false
    }

    @Test
    fun emptyDatabase() = visitAll("leer")

    @Test
    fun demoData() {
        DemoData.fill(app.db)
        app.prefs.goals = listOf(
            de.waschbeken.heliolink.core.Goal(de.waschbeken.heliolink.core.Goal.Kind.SLEEP, 420, 5),
            de.waschbeken.heliolink.core.Goal(de.waschbeken.heliolink.core.Goal.Kind.STEPS, 8000, 7),
            de.waschbeken.heliolink.core.Goal(de.waschbeken.heliolink.core.Goal.Kind.WITHOUT, 0, 7, "Nikotin"),
        )
        app.prefs.withoutTags = listOf("Nikotin", "Alkohol")
        visitAll("demo")
    }

    private fun visitAll(name: String) {
        shots = File("build/screenshots/$name").apply { mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HelioTheme(mode = ThemeMode.LIGHT) { AppContent(route, onRouteShown = { route = null }, onForget = {}) }
        }
        settle()
        shot("01-heute")

        open(Routes.DATA, "02-daten")
        listOf("Puls", "Schlaf", "Schlaf-Woche", "Schritte", "Schritte-Woche", "Stress", "SpO₂", "HRV", "Temperatur", "Atmung", "PAI", "Akku")
            .forEachIndexed { i, tab -> click(tab); shot("02-daten-${i + 1}-$tab") }

        open(Routes.SLEEP, "03-schlaf")
        periods("03-schlaf")
        open(Routes.TRAINING, "04-sport")
        open(Routes.BODY, "05-koerper")
        open(Routes.SETTINGS, "06-mehr")

        Metric.entries.forEach { m ->
            open(Routes.metric(m.key), "10-${m.key}")
            periods("10-${m.key}")
        }
        SettingsCategory.entries.forEach { c -> open(Routes.settings(c.key), "20-${c.key}") }

        open(Routes.RECORDING, "30-aufzeichnung")
        open(Routes.ALARMS, "31-wecker")
        open(Routes.alarm(0), "32-wecker-bearbeiten")
        open(Routes.alarm(-1), "33-wecker-neu")
        open(Routes.TOOLS_BREATHE, "34-atemuebung")
        open(Routes.TOOLS_FOCUS, "35-fokus")
        open(Routes.sleepEdit(LocalDate.now().toEpochDay()), "36-schlaf-bearbeiten")
        app.db.workouts().forEach { w -> open(Routes.workout(w.id), "37-training-${w.id}") }
        open(Routes.PROGRESS, "40-fortschritt")
        listOf("Woche", "Jahr").forEach { click(it); shot("40-fortschritt-$it") }
        listOf("Schritte", "Ruhepuls").forEach { click(it); shot("40-fortschritt-kalender-$it") }
        open(Routes.HOME, "01-heute-zurueck")
    }

    private fun open(target: String, shotName: String) {
        route = target
        settle()
        shot(shotName)
    }

    /** Every period of the screen; screenshots of a week and of a long one. */
    private fun periods(prefix: String) {
        Period.entries.drop(1).forEach { p ->
            click(p.label)
            if (p == Period.WEEK || p == Period.MONTHS3) shot("$prefix-${p.name.lowercase()}")
        }
        click(Period.DAY.label)
    }

    private fun click(text: String) {
        val nodes = compose.onAllNodes(hasText(text) and hasClickAction())
        if (nodes.fetchSemanticsNodes().isEmpty()) return
        nodes.onFirst().performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** Lets navigation, animations and the database loads finish. */
    private fun settle() {
        var quiet = 0
        var rounds = 0
        // At least a second: navigation fades and pager scrolls take up to 700 ms.
        while ((quiet < 3 || rounds < 10) && rounds < 300) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            if (ScreenLoads.running.get() == 0) quiet++ else {
                quiet = 0
                Thread.sleep(20)
            }
            rounds++
        }
    }

    /** Rows down to the last one that differs from the background at the bottom. */
    private fun contentHeight(b: Bitmap): Int {
        val background = b.getPixel(0, b.height - 1)
        val row = IntArray(b.width)
        for (y in b.height - 1 downTo 1) {
            b.getPixels(row, 0, b.width, 0, y, b.width, 1)
            if (row.any { it != background }) return minOf(b.height, y + 24)
        }
        return b.height
    }

    private fun shot(name: String) {
        try {
            val bitmap = try {
                compose.onRoot().captureToImage().asAndroidBitmap()
            } catch (e: Throwable) {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
            // Half size, cut below the content, as JPEG: small enough to keep every run.
            val height = contentHeight(bitmap)
            val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, height), bitmap.width / 2, height / 2, true)
            File(shots, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Throwable) {
            println("Kein Bildschirmfoto für $name: $e")
        }
    }
}
