package cz.kulturadar.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RadarGreen = Color(0xFF61F59A)
private val RadarMint = Color(0xFFA7F7C4)
private val RadarCyan = Color(0xFF7CE8D1)
private val Ink = Color(0xFF020504)
private val Surface = Color(0xFF08120D)
private val SurfaceRaised = Color(0xFF0D1C14)

private val KulturadarDark = darkColorScheme(
    primary = RadarGreen,
    onPrimary = Color(0xFF00210D),
    primaryContainer = Color(0xFF123C24),
    onPrimaryContainer = Color(0xFFD6FFE3),
    secondary = RadarMint,
    onSecondary = Color(0xFF062014),
    secondaryContainer = Color(0xFF173426),
    onSecondaryContainer = Color(0xFFD8FFE6),
    tertiary = RadarCyan,
    onTertiary = Color(0xFF00201A),
    tertiaryContainer = Color(0xFF0D3B32),
    onTertiaryContainer = Color(0xFFC6FFF1),
    background = Ink,
    onBackground = Color(0xFFF1F8F3),
    surface = Surface,
    onSurface = Color(0xFFF1F8F3),
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = Color(0xFFA9B9AF),
    outline = Color(0xFF2C4A38),
    outlineVariant = Color(0xFF172A1E),
    error = Color(0xFFFF7188),
    onError = Color(0xFF3A0010)
)

@Composable
fun KulturadarTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = KulturadarDark,
    content = content
)
