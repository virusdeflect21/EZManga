package app.ezmanga.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val EzMangaColors = darkColorScheme(
    primary = Color(0xFFCDBDFF),
    onPrimary = Color(0xFF2A1854),
    primaryContainer = Color(0xFF3B2A6B),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFFFB3C8),
    onSecondary = Color(0xFF561D2E),
    secondaryContainer = Color(0xFF6B2E43),
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFF8FE3D0),
    onTertiary = Color(0xFF00382F),
    background = Color(0xFF08080C),
    onBackground = Color(0xFFF2EFF7),
    surface = Color(0xFF14131A),
    onSurface = Color(0xFFF2EFF7),
    surfaceVariant = Color(0xFF24222D),
    onSurfaceVariant = Color(0xFFB8B3C4),
    outline = Color(0xFF494455),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val EzMangaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 36.sp,
        lineHeight = 39.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun EzMangaMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EzMangaColors,
        typography = EzMangaTypography,
        content = content,
    )
}
