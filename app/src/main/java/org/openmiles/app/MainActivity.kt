package org.openmiles.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openmiles.app.ui.OpenMilesApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Spreadsheet exports are temporary. Clear any left over from last time.
        java.io.File(cacheDir, "exports").deleteRecursively()
        setContent {
            OpenMilesTheme {
                val vm: AppViewModel = viewModel()
                OpenMilesApp(vm)
            }
        }
    }
}

@Composable
fun OpenMilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
