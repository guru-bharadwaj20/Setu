package `in`.setu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Emergency red primary, high contrast, no dynamic colour so it looks the same on every phone. */
val SosRed = Color(0xFFB3261E)
val SosRedDark = Color(0xFFFF6E62)
val SafeGreen = Color(0xFF1B7F3B)
val SafeGreenDark = Color(0xFF6FD08C)

private val Light = lightColorScheme(
    primary = SosRed,
    onPrimary = Color.White,
    secondary = Color(0xFF4A5568),
    tertiary = SafeGreen,
    background = Color(0xFFFFFBFA),
    surface = Color(0xFFFFFBFA),
)

private val Dark = darkColorScheme(
    primary = SosRedDark,
    onPrimary = Color(0xFF3A0300),
    secondary = Color(0xFFB0B8C4),
    tertiary = SafeGreenDark,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
)

@Composable
fun SetuTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
