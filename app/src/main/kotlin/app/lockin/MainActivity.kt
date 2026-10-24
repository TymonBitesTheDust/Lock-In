package app.lockin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.lockin.core.designsystem.theme.LockInTheme
import app.lockin.feature.timer.TimerRoute
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Single screen for now; Navigation 3 arrives with stats and groups (see ARCHITECTURE.md).
        setContent {
            LockInTheme {
                TimerRoute()
            }
        }
    }
}
