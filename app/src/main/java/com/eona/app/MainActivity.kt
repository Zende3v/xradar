package com.eona.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eona.app.data.preferences.AppPreferences
import com.eona.app.designsystem.theme.EonaTheme
import com.eona.app.location.LocationRepository
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Read the stored scheme before the first frame, so the app never flashes
        // the default theme on launch.
        AppPreferences.init(applicationContext)
        setContent {
            val settings by AppPreferences.settings.collectAsStateWithLifecycle()
            val location by LocationRepository.location.collectAsStateWithLifecycle()
            // "Thème général": the whole app, the map and the HUD together. At "Auto" the sky
            // decides again at each fix and every minute (sunrise with the car parked); only a
            // change of day or night recomposes.
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(THEME_TICK_MS)
                    now = System.currentTimeMillis()
                }
            }
            val dark by remember { derivedStateOf { settings.theme.isDark(location, now) } }
            // "Couleur de l'app": every screen, the route and the arrow take it at once.
            EonaTheme(darkTheme = dark, accent = settings.accent.rgb) {
                EonaApp()
            }
        }
    }
}

private const val THEME_TICK_MS = 60_000L
