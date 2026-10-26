package app.lockin

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.lockin.core.data.PreferencesRepository
import app.lockin.core.designsystem.theme.LockInTheme
import app.lockin.core.model.isDark
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Null for the first frame or two while DataStore loads; the window background shows meanwhile.
            val prefs by preferences.preferences.collectAsState(initial = null)
            val p = prefs
            if (p != null) {
                val dark = p.themeMode.isDark(isSystemInDarkTheme())
                // The user can force light or dark against the phone's setting, so the status and
                // navigation bar icons follow the app's choice, not the system's.
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                    )
                    onDispose {}
                }
                LockInTheme(darkTheme = dark, accent = p.accent) {
                    LockInApp()
                }
            }
        }
    }

    private companion object {
        // The defaults enableEdgeToEdge() uses for 3-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
