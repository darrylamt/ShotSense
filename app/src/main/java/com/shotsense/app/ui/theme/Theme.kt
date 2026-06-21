package com.shotsense.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Instrument-panel palette: amber = sound, teal = recoil, red = confirmed shot. */
object Palette {
    val Bg = Color(0xFF0B0E12)
    val Surface = Color(0xFF14181E)
    val SurfaceAlt = Color(0xFF1B212A)
    val Amber = Color(0xFFFFB300)
    val Teal = Color(0xFF1DE9B6)
    val Red = Color(0xFFFF5252)
    val Green = Color(0xFF69F0AE)
    val TextPrimary = Color(0xFFE6E8EB)
    val TextDim = Color(0xFF8A929B)
    val Line = Color(0xFF2A323D)
}

private val ShotSenseColors = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Color.Black,
    secondary = Palette.Teal,
    onSecondary = Color.Black,
    background = Palette.Bg,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceAlt,
    error = Palette.Red,
    onError = Color.Black,
)

// Roboto is the platform default sans on Android (FontFamily.Default == Roboto),
// so it needs no bundled font files or downloadable-fonts provider. Numeric
// readouts use tabular figures ("tnum") so digits stay column-aligned like an
// instrument panel.
private val Sans = FontFamily.Default
private const val TABULAR = "tnum"

private val ShotSenseTypography = Typography(
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 2.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontFeatureSettings = TABULAR),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 13.sp, fontFeatureSettings = TABULAR),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontSize = 11.sp, letterSpacing = 1.sp),
)

@Composable
fun ShotSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShotSenseColors,
        typography = ShotSenseTypography,
        content = content,
    )
}
