package com.xradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xradar.app.data.preferences.AppPreferences
import com.xradar.app.data.preferences.ThemeMode
import com.xradar.app.designsystem.theme.XRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Read the stored scheme before the first frame, so the app never flashes
        // the default theme on launch.
        AppPreferences.init(applicationContext)
        setContent {
            val settings by AppPreferences.settings.collectAsStateWithLifecycle()
            // Dark-first, but the driver chooses in Réglages.
            val dark = when (settings.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            XRadarTheme(darkTheme = dark) {
                XRadarApp()
            }
        }
    }
}
