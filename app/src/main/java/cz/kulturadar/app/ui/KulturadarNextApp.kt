package cz.kulturadar.app.ui

// Legacy screen kept for compatibility. All user-facing features are free.
// The active Play/Pro UI no longer depends on a Premium build flag.

@androidx.compose.runtime.Composable
fun KulturadarNextApp() {
    KulturadarProApp()
}
