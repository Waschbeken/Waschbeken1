package de.waschbeken.heliolink.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import de.waschbeken.heliolink.core.SleepStage
import de.waschbeken.heliolink.data.ThemeMode

/** Accent presets offered in the settings (same idea as in the Lernkarten app). */
val AccentPresets = listOf(
    0xFFE5532F to "Koralle",
    0xFF4F46E5 to "Indigo",
    0xFF0EA5E9 to "Himmelblau",
    0xFF059669 to "Smaragd",
    0xFFD97706 to "Bernstein",
    0xFFDC2626 to "Rot",
    0xFFDB2777 to "Pink",
    0xFF7C3AED to "Violett",
    0xFF475569 to "Schiefer",
)

/** Fixed colors for data, so a zone or a sleep stage always looks the same. */
object Palette {
    val Heart = Color(0xFFE5484D)
    val Steps = Color(0xFF12A594)
    val Sleep = Color(0xFF6E56CF)
    val Stress = Color(0xFFF76B15)
    val Spo2 = Color(0xFF0090FF)
    val Hrv = Color(0xFF30A46C)
    val Temperature = Color(0xFFE54666)
    val Pai = Color(0xFFFFB224)
    val Breath = Color(0xFF00A2C7)
    val Battery = Color(0xFF46A758)

    val Deep = Color(0xFF3E4EB8)
    val Light = Color(0xFF6CA8F7)
    val Rem = Color(0xFFB57EDC)
    val Awake = Color(0xFFF5B34C)
    val zones = listOf(Color(0xFF8DA4B8), Color(0xFF3E9BF7), Color(0xFF46A758), Color(0xFFF59E0B), Color(0xFFE5484D))

    fun stage(s: SleepStage) = when (s) {
        SleepStage.AWAKE -> Awake
        SleepStage.REM -> Rem
        SleepStage.LIGHT -> Light
        SleepStage.DEEP -> Deep
    }
}

fun stageName(s: SleepStage) = when (s) {
    SleepStage.AWAKE -> "Wach"
    SleepStage.REM -> "REM"
    SleepStage.LIGHT -> "Leicht"
    SleepStage.DEEP -> "Tief"
}

private fun lightScheme(accent: Color): ColorScheme = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = lerp(accent, Color.White, 0.82f),
    onPrimaryContainer = lerp(accent, Color.Black, 0.6f),
    secondary = lerp(accent, Color(0xFF64748B), 0.55f),
    secondaryContainer = lerp(accent, Color.White, 0.9f),
    onSecondaryContainer = lerp(accent, Color.Black, 0.65f),
    tertiary = lerp(accent, Color(0xFF0D9488), 0.6f),
    tertiaryContainer = lerp(Color(0xFF0D9488), Color.White, 0.85f),
    surface = Color(0xFFFCFCFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = lerp(accent, Color.White, 0.97f),
    surfaceContainer = lerp(accent, Color.White, 0.95f),
    surfaceContainerHigh = lerp(accent, Color.White, 0.93f),
    surfaceContainerHighest = lerp(accent, Color.White, 0.9f),
    background = Color(0xFFFCFCFF),
)

private fun darkScheme(accent: Color): ColorScheme {
    val light = lerp(accent, Color.White, 0.35f)
    return darkColorScheme(
        primary = light,
        onPrimary = lerp(accent, Color.Black, 0.7f),
        primaryContainer = lerp(accent, Color.Black, 0.45f),
        onPrimaryContainer = lerp(accent, Color.White, 0.8f),
        secondary = lerp(light, Color(0xFF94A3B8), 0.5f),
        secondaryContainer = lerp(accent, Color.Black, 0.65f),
        onSecondaryContainer = lerp(accent, Color.White, 0.85f),
        tertiary = lerp(light, Color(0xFF5EEAD4), 0.6f),
        tertiaryContainer = lerp(Color(0xFF0D9488), Color.Black, 0.55f),
        surface = Color(0xFF121218),
        surfaceContainerLowest = Color(0xFF0C0C11),
        surfaceContainerLow = lerp(accent, Color(0xFF121218), 0.94f),
        surfaceContainer = lerp(accent, Color(0xFF121218), 0.91f),
        surfaceContainerHigh = lerp(accent, Color(0xFF121218), 0.88f),
        surfaceContainerHighest = lerp(accent, Color(0xFF121218), 0.84f),
        background = Color(0xFF121218),
    )
}

@Composable
fun HelioTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    accent: Long = 0xFFE5532F,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkScheme(Color(accent))
        else -> lightScheme(Color(accent))
    }
    MaterialTheme(colorScheme = scheme, typography = scaledTypography(fontScale), content = content)
}

private fun scaledTypography(scale: Float): Typography {
    val base = Typography()
    if (scale == 1f) return base
    fun androidx.compose.ui.text.TextStyle.s() = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
    return base.copy(
        displayLarge = base.displayLarge.s(), displayMedium = base.displayMedium.s(), displaySmall = base.displaySmall.s(),
        headlineLarge = base.headlineLarge.s(), headlineMedium = base.headlineMedium.s(), headlineSmall = base.headlineSmall.s(),
        titleLarge = base.titleLarge.s(), titleMedium = base.titleMedium.s(), titleSmall = base.titleSmall.s(),
        bodyLarge = base.bodyLarge.s(), bodyMedium = base.bodyMedium.s(), bodySmall = base.bodySmall.s(),
        labelLarge = base.labelLarge.s(), labelMedium = base.labelMedium.s(),
        labelSmall = base.labelSmall.copy(fontSize = (base.labelSmall.fontSize.value * scale).coerceAtLeast(10f).sp),
    )
}
