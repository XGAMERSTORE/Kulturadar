package cz.kulturadar.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private data class AccentPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val primaryContainer: Color,
    val outline: Color
)

private fun palette(id: String): AccentPalette = when (id) {
    "wine" -> AccentPalette(Color(0xFFFF5E89), Color(0xFFFFA2BB), Color(0xFFD9A2FF), Color(0xFF59142A), Color(0xFF673447))
    "purple" -> AccentPalette(Color(0xFFC985FF), Color(0xFFE0B6FF), Color(0xFF9AA7FF), Color(0xFF40205A), Color(0xFF563A67))
    "blue" -> AccentPalette(Color(0xFF72A7FF), Color(0xFFAEC9FF), Color(0xFF7CE8D1), Color(0xFF173A67), Color(0xFF315170))
    "red" -> AccentPalette(Color(0xFFFF6472), Color(0xFFFFA1AA), Color(0xFFFFB078), Color(0xFF5A1D24), Color(0xFF6A353C))
    "gold" -> AccentPalette(Color(0xFFE4BC64), Color(0xFFF4DCA4), Color(0xFFFFB878), Color(0xFF493714), Color(0xFF5E5030))
    else -> AccentPalette(Color(0xFF61F59A), Color(0xFFA7F7C4), Color(0xFF7CE8D1), Color(0xFF123C24), Color(0xFF2C4A38))
}

@Composable
fun KulturadarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val savedAccent = context.getSharedPreferences("settings", 0).getString("accent_theme", "green") ?: "green"
    val accent = palette(savedAccent)

    val colors = darkColorScheme(
        primary = accent.primary,
        onPrimary = Color(0xFF001B0D),
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = Color(0xFFE6FFEF),
        secondary = accent.secondary,
        onSecondary = Color(0xFF082014),
        secondaryContainer = Color(0xFF173126),
        onSecondaryContainer = Color(0xFFE6FFEF),
        tertiary = accent.tertiary,
        onTertiary = Color(0xFF0A211C),
        tertiaryContainer = Color(0xFF193630),
        onTertiaryContainer = Color(0xFFE5FFF7),
        background = Color(0xFF020604),
        onBackground = Color(0xFFF4FFF8),
        surface = Color(0xFF07100B),
        onSurface = Color(0xFFF4FFF8),
        surfaceVariant = Color(0xFF101A14),
        onSurfaceVariant = Color(0xFFC4D2C8),
        outline = accent.outline,
        outlineVariant = Color(0xFF25352B),
        error = Color(0xFFFF6B7A),
        onError = Color(0xFF3A0010)
    )

    MaterialTheme(colorScheme = colors, content = content)
}
