package de.waschbeken.heliolink

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.ble.LinkState
import de.waschbeken.heliolink.core.TapProbe
import de.waschbeken.heliolink.data.ThemeMode
import de.waschbeken.heliolink.ui.HelioTheme
import de.waschbeken.heliolink.ui.settings.TapTestDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The tap test from start to result, with a strap that answers the taps. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-rDE")
class TapTestDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()

    @After
    fun noController() {
        app.repo.controller = null
    }

    private fun message(source: String, hex: String) {
        Thread.sleep(5)
        app.repo.trace.value = app.repo.trace.value + TapProbe.Message(System.currentTimeMillis(), source, hex)
        Thread.sleep(5)
    }

    private fun seconds(s: Int) {
        repeat(s * 10) {
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
        }
    }

    @Test
    fun findsWhatOnlyComesWhileTapping() {
        val strap = FakeStrap(app)
        app.repo.traceReady.value = null
        app.repo.controller = strap
        app.repo.update { it.copy(link = LinkState.READY) }
        compose.mainClock.autoAdvance = false
        compose.setContent { HelioTheme(mode = ThemeMode.LIGHT) { TapTestDialog(onDismiss = {}) } }
        compose.waitForIdle()

        compose.onAllNodes(hasText("Starten") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        seconds(2)
        // left alone: only the answer to the control request (battery level)
        message("Endpunkt 0029", "0464")
        app.repo.update { it.copy(batteryAt = System.currentTimeMillis()) }
        app.repo.log("Akku 64 %")
        seconds(9)
        compose.onNodeWithText("Schritt 2 von 3: 3× tippen").assertExists()
        message("Endpunkt 0029", "0463")
        message("Kanal 0010", "0b01")
        seconds(21)

        compose.onNodeWithText("Der Strap hat reagiert").assertExists()
        compose.onNodeWithText("Nur beim Tippen kam: Kanal 0010 0b", substring = true).assertExists()
        // the report: control, battery on the normal way, the app's log of the test time
        compose.onNodeWithText("Akku-Antwort auf dem normalen Weg: angekommen", substring = true).assertExists()
        compose.onNodeWithText("Akku 64 %", substring = true).assertExists()
        assertEquals(listOf(true, false), strap.calls)
    }

    @Test
    fun preparationThatNeverFinishesIsReported() {
        val strap = object : de.waschbeken.heliolink.service.StrapController by FakeStrap(app) {
            override fun traceTaps(on: Boolean) = Unit
        }
        app.repo.controller = strap
        app.repo.traceReady.value = null
        app.repo.update { it.copy(link = LinkState.READY) }
        compose.mainClock.autoAdvance = false
        compose.setContent { HelioTheme(mode = ThemeMode.LIGHT) { TapTestDialog(onDismiss = {}) } }
        compose.waitForIdle()
        compose.onAllNodes(hasText("Starten") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        seconds(2)
        compose.onNodeWithText("Vorbereiten …").assertExists()
        seconds(90)
        compose.onNodeWithText("Vorbereitung hat nicht geklappt").assertExists()
    }

    @Test
    fun withoutTheControlTheResultIsUncertain() {
        app.repo.controller = FakeStrap(app)
        app.repo.traceReady.value = null
        app.repo.trace.value = emptyList()
        app.repo.update { it.copy(link = LinkState.READY) }
        compose.mainClock.autoAdvance = false
        compose.setContent { HelioTheme(mode = ThemeMode.LIGHT) { TapTestDialog(onDismiss = {}) } }
        compose.waitForIdle()
        compose.onAllNodes(hasText("Starten") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        seconds(32)
        compose.onNodeWithText("Nichts empfangen").assertExists()
    }
}
