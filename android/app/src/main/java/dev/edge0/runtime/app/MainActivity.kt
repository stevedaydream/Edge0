// MainActivity.kt - single-Activity host (edge-to-edge; Compose-drawn; theme follows settings).
package dev.edge0.runtime.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.edge0.runtime.app.api.ApiService
import dev.edge0.runtime.app.data.AppSettings
import dev.edge0.runtime.app.ui.AppRoot
import dev.edge0.runtime.app.ui.theme.Edge0Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as Edge0App).container
        // resume the local API if the user left it on (foreground start is allowed from an Activity)
        lifecycleScope.launch {
            if (container.settings.flow.first().apiEnabled) ApiService.start(this@MainActivity)
        }
        setContent {
            val settings by container.settings.flow
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            Edge0Theme(mode = settings.themeMode, fontScale = settings.fontScale) {
                AppRoot()
            }
        }
    }
}
