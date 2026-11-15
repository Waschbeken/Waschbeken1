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
import de.waschbeken.heliolink.core.HealthSetting
import de.waschbeken.heliolink.core.IntervalPlan
import de.waschbeken.heliolink.core.StrapAlarm
import de.waschbeken.heliolink.core.TapProbe
import de.waschbeken.heliolink.data.ThemeMode
import de.waschbeken.heliolink.service.StrapController
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

    /** Remembers only what the test switches on and off. */
    private class FakeStrap : StrapController {
        val calls = ArrayList<Boolean>()
        override fun traceTaps(on: Boolean) { calls += on }
        override fun syncNow() = Unit
        override fun probeSleepCode() = Unit
        override fun saveAlarm(alarm: StrapAlarm, label: String) = Unit
        override fun deleteAlarm(position: Int) = Unit
        override fun refreshAlarms() = Unit
        override fun setLiveHeartRate(enabled: Boolean) = Unit
        override fun findStrap() = Unit
        override fun refresh() = Unit
        override fun applySettings(values: Map<HealthSetting, Int>, lists: Map<HealthSetting, List<Int>>) = Unit
        override fun startWorkout(sport: String, gps: Boolean) = Unit
        override fun startIntervals(sport: String, plan: IntervalPlan, gps: Boolean) = Unit
        override fun startRecording(seconds: Int?) = Unit
        override fun setPaused(paused: Boolean) = Unit
        override fun stopWorkout() = Unit
        override fun buzz(count: Int, onMs: Long) = Unit
        override fun startFocus(plan: IntervalPlan) = Unit
        override fun stopFocus() = Unit
        override fun reconnect() = Unit
        override fun shutdown() = Unit
    }

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
        val strap = FakeStrap()
        app.repo.controller = strap
        app.repo.update { it.copy(link = LinkState.READY) }
        compose.mainClock.autoAdvance = false
        compose.setContent { HelioTheme(mode = ThemeMode.LIGHT) { TapTestDialog(onDismiss = {}) } }
        compose.waitForIdle()

        compose.onAllNodes(hasText("Starten") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        seconds(2)
        // left alone: only the battery level
        message("Endpunkt 0029", "0464")
        seconds(9)
        compose.onNodeWithText("Schritt 2 von 3: 3× tippen").assertExists()
        message("Endpunkt 0029", "0463")
        message("Kanal 0010", "0b01")
        seconds(21)

        compose.onNodeWithText("Der Strap hat reagiert").assertExists()
        compose.onNodeWithText("Nur beim Tippen kam: Kanal 0010 0b", substring = true).assertExists()
        assertEquals(listOf(true, false), strap.calls)
    }
}
