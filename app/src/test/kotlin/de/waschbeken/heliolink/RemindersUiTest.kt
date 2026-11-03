package de.waschbeken.heliolink

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.data.ThemeMode
import de.waschbeken.heliolink.service.StrapUiState
import de.waschbeken.heliolink.ui.HelioTheme
import de.waschbeken.heliolink.ui.settings.RemindersPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Creating a reminder through the dialog like a user would. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-rDE")
class RemindersUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()

    private fun tap(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    @Test
    fun createRepeatingReminder() {
        compose.setContent {
            HelioTheme(mode = ThemeMode.LIGHT) {
                Column(Modifier.verticalScroll(rememberScrollState())) { RemindersPage(StrapUiState()) }
            }
        }
        compose.waitForIdle()
        tap("Neue Erinnerung")
        tap("Trinken")
        tap("Speichern")
        val list = app.prefs.reminders
        assertEquals(1, list.size)
        assertEquals("Trinken", list[0].text)
        assertEquals(120, list[0].repeatMinutes)
        assertEquals(0, list[0].days)
    }
}
