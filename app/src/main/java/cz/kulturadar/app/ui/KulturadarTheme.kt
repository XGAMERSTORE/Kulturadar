package cz.kulturadar.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF55E68A)
private val Dark = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF05210F),
    primaryContainer = Green.copy(alpha = .20f),
    onPrimaryContainer = Color(0xFFD7FFE2),
    secondary = Color(0xFF9FE6B7),
    background = Color(0xFF020604),
    onBackground = Color(0xFFF2F7F3),
    surface = Color(0xFF0B1710),
    onSurface = Color(0xFFF2F7F3),
    surfaceVariant = Color(0xFF12241A),
    onSurfaceVariant = Color(0xFFAAB8AE),
    outline = Color(0xFF294535),
    error = Color(0xFFFF6F83)
)

@Composable
fun KulturadarTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Dark, content = content)
