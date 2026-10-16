package center.mycrm.driver.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** MyCRM brand: orange accent; light theme uses the core's #FBF0D9 background. */
object Palette {
    val Orange = Color(0xFFE8872B)
    val Green = Color(0xFF2E9E5B)
    val Red = Color(0xFFD64545)
    val Yellow = Color(0xFFE0B341)
    val Grey = Color(0xFF8A8F98)
}

private val LightScheme = lightColorScheme(
    primary = Palette.Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF3A1E05),
    secondary = Color(0xFF2F6FB2),
    onSecondary = Color.White,
    background = Color(0xFFFBF0D9),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFFF8EA),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFF1E3C6),
    onSurfaceVariant = Color(0xFF5B5547),
    outline = Color(0xFFB9AE97),
    error = Color(0xFFB3261E),
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = Palette.Orange,
    onPrimary = Color(0xFF1A1004),
    primaryContainer = Color(0xFF5C3611),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFF7FB3E6),
    onSecondary = Color(0xFF0B1A2A),
    background = Color(0xFF15161C),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF1E2028),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF2A2D38),
    onSurfaceVariant = Color(0xFFB8BCC8),
    outline = Color(0xFF555A68),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000)
)

@Composable
fun DriverTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = Typography(),
        content = content
    )
}
