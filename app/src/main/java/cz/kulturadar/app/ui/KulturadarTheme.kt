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
    "wine" -> AccentPalette(
        primary = Color(0xFFFF5E89),
        secondary = Color(0xFFFFA2BB),
        tertiary = Color(0xFFD9A2FF),
        primaryContainer = Color(0xFF59142A),
        outline = Color(0xFF673447)
    )
    "purple" -> AccentPalette(
        primary = Color(0xFFC985FF),
        secondary = Color(0xFFE0B6FF),
        tertiary = Color(0xFF9AA7FF),
        primaryContainer = Color(0xFF40205A),
        outline = Color(0xFF563A67)
    )
    "blue" -> AccentPalette(
        primary = Color(0xFF72A7FF),
        secondary = Color(0xFFAEC9FF),
        tertiary = Color(0xFF7CE8D1),
        primaryContainer = Color(0xFF173A67),
        outline = Color(0xFF315170)
    )
    "red" -> AccentPalette(
        primary = Color(0xFFFF6472),
        secondary = Color(0xFFFFA1AA),
        tertiary = Color(0xFFFFB078),
        primaryContainer = Color(0xFF5A1D24),
        outline = Color(0xFF6A353C)
    )
    "gold" -> AccentPalette(
        primary = Color(0xFFE4BC64),
        secondary = Color(0xFFF4DCA4),
        tertiary = Color(0xFFFFB878),
        primaryContainer = Color(0xFF493714),
        outline = Color(0xFF5E5030)
    )
    else -> AccentPalette(
        primary = Color(0xFF61F59A),
        secondary = Color(0xFFA7F7C4),
        tertiary = Color(0xFF7CE8D1),
        primaryContainer = Color(0xFF123C24),
        outline = Color(0xFF2C4A38)
    )
}

@Composable
fun KulturadarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val savedAccent = context.getSharedPreferences("settings", 0).getString("accent_theme", "wine") ?: "wine"
    val accent = palette(savedAccent)

    val colors = darkColorScheme(
        primary = accent.primary,
        onPrimary = Color(0xFF24000D),
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = Color(0xFFFFE9F0),
        secondary = accent.secondary,
        onSecondary = Color(0xFF251017),
        secondaryContainer = Color(0xFF352028),
        onSecondaryContainer = Color(0xFFFFE8EF),
        tertiary = accent.tertiary,
        onTertiary = Color(0xFF201326),
        tertiaryContainer = Color(0xFF34283A),
        onTertiaryContainer = Color(0xFFF8E9FF),
        background = Color(0xFF050506),
        onBackground = Color(0xFFF7F3F5),
        surface = Color(0xFF0B0A0D),
        onSurface = Color(0xFFF7F3F5),
        surfaceVariant = Color(0xFF151318),
        onSurfaceVariant = Color(0xFFC9C0C5),
        outline = accent.outline,
        outlineVariant = Color(0xFF2A252C),
        error = Color(0xFFFF647D),
        onError = Color(0xFF3A0010)
    )

    MaterialTheme(colorScheme = colors, content = content)
}
