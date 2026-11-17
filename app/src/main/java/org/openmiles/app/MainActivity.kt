package org.openmiles.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openmiles.app.ui.Appearance
import org.openmiles.app.ui.OpenMilesApp
import org.openmiles.app.ui.OpenMilesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The header is navy in both light and dark looks, so the status bar icons stay white.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Spreadsheet exports are temporary. Clear any left over from last time.
        java.io.File(cacheDir, "exports").deleteRecursively()
        Appearance.load(this)
        setContent {
            val mode by Appearance.mode.collectAsStateWithLifecycle()
            OpenMilesTheme(mode) {
                val vm: AppViewModel = viewModel()
                OpenMilesApp(vm)
            }
        }
    }
}
