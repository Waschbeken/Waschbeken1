package de.waschbeken.heliolink.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import de.waschbeken.heliolink.data.ThemeMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import de.waschbeken.heliolink.core.SleepStage

object Palette {
    val Coral = Color(0xFFFF6B4A)
    val Deep = Color(0xFF3949AB)
    val Light = Color(0xFF64B5F6)
    val Rem = Color(0xFFBA68C8)
    val Awake = Color(0xFFFFB74D)
    val Steps = Color(0xFF26A69A)
    val Stress = Color(0xFFFFA726)
    val zones = listOf(Color(0xFF90A4AE), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726), Color(0xFFEF5350))

    fun stage(s: SleepStage) = when (s) {
        SleepStage.AWAKE -> Awake
        SleepStage.REM -> Rem
        SleepStage.LIGHT -> Light
        SleepStage.DEEP -> Deep
    }
}

private val darkScheme = darkColorScheme(
    primary = Palette.Coral,
    onPrimary = Color.White,
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1C232B),
    surfaceContainer = Color(0xFF1A2027),
    surfaceContainerHigh = Color(0xFF212932),
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFFE5532F),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFF6F7F9),
    surfaceVariant = Color(0xFFE6E9EE),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
)

@Composable
fun HelioTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkScheme
        else -> lightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun stageName(s: SleepStage) = when (s) {
    SleepStage.AWAKE -> "Wach"
    SleepStage.REM -> "REM"
    SleepStage.LIGHT -> "Leicht"
    SleepStage.DEEP -> "Tief"
}
