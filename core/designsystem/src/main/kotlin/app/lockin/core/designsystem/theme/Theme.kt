package app.lockin.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dark-first: sessions run for hours, often at night. Low-luminance surfaces, one calm accent,
 * no pure black (OLED smear on scroll) and no pure white text (glare).
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DC3FF),
    onPrimary = Color(0xFF0B1D36),
    primaryContainer = Color(0xFF1E3554),
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFA9CDB8),
    onSecondary = Color(0xFF14352A),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE2E4E9),
    surface = Color(0xFF0F1216),
    onSurface = Color(0xFFE2E4E9),
    surfaceVariant = Color(0xFF1B2027),
    onSurfaceVariant = Color(0xFFB3BAC5),
    surfaceContainer = Color(0xFF171B21),
    surfaceContainerHigh = Color(0xFF1E232A),
    outline = Color(0xFF59616D),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5E9E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF0B1D36),
    secondary = Color(0xFF3F6A56),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFE3E6EC),
    onSurfaceVariant = Color(0xFF44494F),
    surfaceContainer = Color(0xFFEDEFF3),
    surfaceContainerHigh = Color(0xFFE6E9EE),
    outline = Color(0xFF8A9099),
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

@Composable
fun LockInTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LockInTypography,
        content = content,
    )
}
