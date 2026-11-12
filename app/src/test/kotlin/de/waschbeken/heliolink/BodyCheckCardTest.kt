package de.waschbeken.heliolink

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.waschbeken.heliolink.core.BodyCheck
import de.waschbeken.heliolink.data.ThemeMode
import de.waschbeken.heliolink.ui.HelioTheme
import de.waschbeken.heliolink.ui.home.BodyCheckCard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** "Dein Körper arbeitet mehr als sonst" folds up to its title and values, and stays that way. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "de-rDE")
class BodyCheckCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<HelioApp>()

    @Test
    fun foldsAndRemembers() {
        val result = BodyCheck.Result(
            LocalDate.now(), BodyCheck.Level.ALERT,
            listOf(
                BodyCheck.Signal(BodyCheck.Vital.TEMPERATURE, 34.9, 34.2, strong = true),
                BodyCheck.Signal(BodyCheck.Vital.RESTING_HR, 63.0, 56.0, strong = true),
            ),
            nights = 1, baselineNights = 28,
        )
        compose.setContent { HelioTheme(mode = ThemeMode.LIGHT) { BodyCheckCard(result, listOf("Alkohol")) } }
        compose.waitForIdle()
        val advice = "Gönn dir heute Ruhe"
        compose.onNodeWithText(advice, substring = true).assertExists()

        compose.onAllNodes(hasText("Dein Körper arbeitet mehr als sonst") and hasClickAction()).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(advice, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ruhepuls", substring = true).assertExists()
        assertFalse(app.prefs.isExpanded("home-bodycheck", true))

        compose.onAllNodes(hasText("Dein Körper arbeitet mehr als sonst") and hasClickAction()).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(advice, substring = true).assertExists()
        assertTrue(app.prefs.isExpanded("home-bodycheck", false))
    }
}
