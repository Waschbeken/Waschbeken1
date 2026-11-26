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

/*
 * Calm surfaces: white cards on a light grey page (dark grey cards on near black at night), only
 * a hint of the accent in the greys. The accent itself is kept for what you can tap and for the
 * selected tab; the data brings its own colors.
 */
private val Ink = Color(0xFF1A1C21)
private val InkSoft = Color(0xFF5C616C)
private val Teal = Color(0xFF0D9488)

private fun lightScheme(accent: Color): ColorScheme {
    fun grey(c: Long) = lerp(Color(c), accent, 0.025f)
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = lerp(accent, Color.White, 0.86f),
        onPrimaryContainer = lerp(accent, Color.Black, 0.62f),
        secondary = lerp(accent, Color(0xFF64748B), 0.55f),
        secondaryContainer = lerp(accent, Color.White, 0.9f),
        onSecondaryContainer = lerp(accent, Color.Black, 0.65f),
        tertiary = lerp(accent, Teal, 0.6f),
        tertiaryContainer = lerp(Teal, Color.White, 0.88f),
        onTertiaryContainer = lerp(Teal, Color.Black, 0.6f),
        errorContainer = Color(0xFFFDE4E1),
        onErrorContainer = Color(0xFF7A1710),
        background = grey(0xFFF2F3F6),
        onBackground = Ink,
        surface = grey(0xFFF2F3F6),
        onSurface = Ink,
        onSurfaceVariant = InkSoft,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = grey(0xFFF7F8FA),
        surfaceContainer = Color.White,
        surfaceContainerHigh = grey(0xFFF0F1F4),
        surfaceContainerHighest = grey(0xFFE5E7EC),
        surfaceVariant = grey(0xFFE5E7EC),
        outline = Color(0xFF7C818C),
        outlineVariant = grey(0xFFDADDE3),
    )
}

private fun darkScheme(accent: Color): ColorScheme {
    val light = lerp(accent, Color.White, 0.35f)
    val card = lerp(Color(0xFF1C1E24), accent, 0.03f)
    fun grey(c: Long) = lerp(Color(c), accent, 0.03f)
    return darkColorScheme(
        primary = light,
        onPrimary = lerp(accent, Color.Black, 0.7f),
        primaryContainer = lerp(accent, card, 0.72f),
        onPrimaryContainer = lerp(accent, Color.White, 0.82f),
        secondary = lerp(light, Color(0xFF94A3B8), 0.5f),
        secondaryContainer = lerp(accent, card, 0.8f),
        onSecondaryContainer = lerp(accent, Color.White, 0.85f),
        tertiary = lerp(light, Color(0xFF5EEAD4), 0.6f),
        tertiaryContainer = lerp(Teal, card, 0.75f),
        onTertiaryContainer = lerp(Teal, Color.White, 0.8f),
        errorContainer = lerp(Color(0xFFE5484D), card, 0.7f),
        onErrorContainer = Color(0xFFFFDAD6),
        background = grey(0xFF101115),
        onBackground = Color(0xFFE6E7EB),
        surface = grey(0xFF101115),
        onSurface = Color(0xFFE6E7EB),
        onSurfaceVariant = Color(0xFFA3A8B2),
        surfaceContainerLowest = grey(0xFF0B0C0F),
        surfaceContainerLow = grey(0xFF16171C),
        surfaceContainer = card,
        surfaceContainerHigh = grey(0xFF25272E),
        surfaceContainerHighest = grey(0xFF2F3239),
        surfaceVariant = grey(0xFF2F3239),
        outline = Color(0xFF8A8F99),
        outlineVariant = grey(0xFF363940),
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
    val plain = Typography()
    // Titles a little firmer, so a card's heading stands out from its values and text.
    val base = plain.copy(
        titleLarge = plain.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleMedium = plain.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        titleSmall = plain.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
    )
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
