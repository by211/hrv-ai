package quest.byai.hrv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF235B4E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC5E9DC),
    onPrimaryContainer = Color(0xFF062019),
    secondary = Color(0xFF5B5F54),
    secondaryContainer = Color(0xFFE1E4D7),
    background = Color(0xFFF7F8F4),
    surface = Color(0xFFFDFDF8),
    surfaceVariant = Color(0xFFE8EAE2),
    error = Color(0xFF9F403A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9D6C8),
    onPrimary = Color(0xFF0B382E),
    primaryContainer = Color(0xFF174C40),
    secondary = Color(0xFFC5C8BC),
    background = Color(0xFF111512),
    surface = Color(0xFF171B18),
)

@Composable
fun ResonanceTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
