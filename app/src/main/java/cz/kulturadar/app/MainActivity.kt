package cz.kulturadar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import cz.kulturadar.app.ui.KulturadarTheme
import cz.kulturadar.app.ui.KulturadarWithApps

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { KulturadarTheme { KulturadarWithApps() } }
    }
}
