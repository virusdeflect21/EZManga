package app.ezmanga.tv.ui

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The same dark color scheme used by Mihon's default theme, so the TV app uses
 * the same surfaces, blue accents, and green status badges as the reader.
 */
private val MihonDarkColors = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF00429B),
    onPrimaryContainer = Color(0xFFD9E2FF),
    inversePrimary = Color(0xFF0058CA),
    secondary = Color(0xFFB0C6FF),
    onSecondary = Color(0xFF002D6E),
    secondaryContainer = Color(0xFF00429B),
    onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF7ADC77),
    onTertiary = Color(0xFF003909),
    tertiaryContainer = Color(0xFF005312),
    onTertiaryContainer = Color(0xFF95F990),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF211F26),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceTint = Color(0xFFB0C6FF),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF1B1B1F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    surfaceContainerLowest = Color(0xFF1A181D),
    surfaceContainerLow = Color(0xFF1E1C22),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF292730),
    surfaceContainerHighest = Color(0xFF302E38),
)

@Composable
fun EzMangaTvTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MihonDarkColors,
        content = content,
    )
}
