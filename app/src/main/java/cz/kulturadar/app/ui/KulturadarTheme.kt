package cz.kulturadar.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Kulturadar palette inspired by the app icon: deep black-green surfaces + neon emerald radar glow.
val RadarEmerald = Color(0xFF4DFF9A)
val RadarEmeraldBright = Color(0xFF76FFB3)
val RadarMint = Color(0xFFB5FFD5)
val RadarBlack = Color(0xFF010705)
val RadarSurface = Color(0xFF06130E)
val RadarSurfaceRaised = Color(0xFF0B2117)
val RadarOutline = Color(0xFF1F5B3D)

private val Dark = darkColorScheme(
    primary = RadarEmerald,
    onPrimary = Color(0xFF001B0D),
    primaryContainer = Color(0xFF0E3A24),
    onPrimaryContainer = RadarMint,
    secondary = RadarEmeraldBright,
    onSecondary = Color(0xFF002312),
    secondaryContainer = Color(0xFF103323),
    onSecondaryContainer = Color(0xFFD7FFE7),
    tertiary = Color(0xFF7EFBC0),
    background = RadarBlack,
    onBackground = Color(0xFFF2FFF7),
    surface = RadarSurface,
    onSurface = Color(0xFFF2FFF7),
    surfaceVariant = RadarSurfaceRaised,
    onSurfaceVariant = Color(0xFFB6CFC0),
    outline = RadarOutline,
    outlineVariant = Color(0xFF113524),
    error = Color(0xFFFF6B82),
    onError = Color(0xFF31000B)
)

@Composable
fun KulturadarTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = Dark,
    content = content
)
