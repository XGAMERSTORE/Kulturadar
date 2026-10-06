package cz.kulturadar.app.ui

// Legacy screen kept for compatibility. All user-facing features are free.
// Reuse the current production Kulturadar UI so old entry points still compile.

@androidx.compose.runtime.Composable
fun KulturadarPlayApp() {
    KulturadarProApp()
}
