package cz.kulturadar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import cz.kulturadar.app.ui.KulturadarPlayApp
import cz.kulturadar.app.ui.KulturadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { KulturadarTheme { KulturadarPlayApp() } }
    }
}
