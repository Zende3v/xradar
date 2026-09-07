package com.xradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xradar.app.designsystem.theme.XRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Dark-first: the app forces the dark scheme regardless of device setting.
            XRadarTheme(darkTheme = true) {
                XRadarApp()
            }
        }
    }
}
