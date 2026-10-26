package app.lockin.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lockin.core.model.AccentColor
import app.lockin.core.model.AccentTones

/**
 * Dark-first: sessions run for hours, often at night. Low-luminance surfaces, one calm accent,
 * no pure black (OLED smear on scroll) and no pure white text (glare).
 *
 * These are the neutral surfaces plus the Blue accent. [LockInTheme] swaps in the accent the user
 * picked (AccentColor in core:model); surfaces, text and error colours never change.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DC3FF),
    onPrimary = Color(0xFF0B1D36),
    primaryContainer = Color(0xFF1E3554),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFA9CDB8),
    onSecondary = Color(0xFF14352A),
    // Selection containers (tab indicator, segmented buttons, tonal buttons): calm blue, not M3's default purple.
    secondaryContainer = Color(0xFF243447),
    onSecondaryContainer = Color(0xFFD5E3FF),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE2E4E9),
    surface = Color(0xFF0F1216),
    onSurface = Color(0xFFE2E4E9),
    surfaceVariant = Color(0xFF1B2027),
    onSurfaceVariant = Color(0xFFB3BAC5),
    surfaceContainerLowest = Color(0xFF0B0E11),
    surfaceContainerLow = Color(0xFF13171C),
    surfaceContainer = Color(0xFF171B21),
    surfaceContainerHigh = Color(0xFF1E232A),
    surfaceContainerHighest = Color(0xFF262C34),
    surfaceBright = Color(0xFF2A3038),
    surfaceDim = Color(0xFF0F1216),
    outline = Color(0xFF59616D),
    outlineVariant = Color(0xFF3A414B),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5E9E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF0B1D36),
    secondary = Color(0xFF3F6A56),
    secondaryContainer = Color(0xFFDCE6F5),
    onSecondaryContainer = Color(0xFF0B1D36),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFE3E6EC),
    onSurfaceVariant = Color(0xFF44494F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F7),
    surfaceContainer = Color(0xFFEDEFF3),
    surfaceContainerHigh = Color(0xFFE6E9EE),
    surfaceContainerHighest = Color(0xFFE0E3E8),
    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFFC4C9D1),
)

private val BaseTypography = Typography()

/** Typography with a dedicated clock style: tabular figures so digits don't jitter as they change. */
val LockInTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(
        fontSize = 76.sp,
        lineHeight = 84.sp,
        fontWeight = FontWeight.Light,
        fontFeatureSettings = "tnum",
        letterSpacing = (-1).sp,
    ),
)

object LockInTextStyles {
    val clock: TextStyle @Composable get() = MaterialTheme.typography.displayLarge
}

/** Heatmap cells, from no study time to the most. */
@Immutable
class HeatmapColors(val levels: List<Color>)

/** The heatmap ramp for the current accent and light/dark. Provided by [LockInTheme]. */
val LocalHeatmapColors = staticCompositionLocalOf { HeatmapColors(emptyList()) }

@Composable
fun LockInTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AccentColor = AccentColor.BLUE,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColors else LightColors
    val tones = accent.tonesFor(darkTheme)
    val scheme = base.withAccent(tones, inversePrimary = Color(accent.tonesFor(!darkTheme).primary))
    val heatmap = HeatmapColors(listOf(scheme.surfaceVariant) + tones.heat.map { Color(it) })
    CompositionLocalProvider(LocalHeatmapColors provides heatmap) {
        MaterialTheme(
            colorScheme = scheme,
            typography = LockInTypography,
            content = content,
        )
    }
}

private fun ColorScheme.withAccent(t: AccentTones, inversePrimary: Color) = copy(
    primary = Color(t.primary),
    onPrimary = Color(t.onPrimary),
    primaryContainer = Color(t.primaryContainer),
    onPrimaryContainer = Color(t.onPrimaryContainer),
    secondaryContainer = Color(t.selection),
    onSecondaryContainer = Color(t.onSelection),
    surfaceTint = Color(t.primary),
    inversePrimary = inversePrimary,
)
