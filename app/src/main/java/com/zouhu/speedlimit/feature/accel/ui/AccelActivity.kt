package com.zouhu.speedlimit.feature.accel.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.zouhu.speedlimit.feature.accel.AccelViewModel
import com.zouhu.speedlimit.ui.theme.SpeedLimitMeterTheme

/**
 * Standalone performance-test host, reached only from Settings.
 *
 * The cruise home is untouched: no zero-to-100 button, overlay or lifecycle change is
 * added to CruiseScreen. This activity reads the app's existing location StateFlow and
 * uses the ported pure-Java acceleration core.
 */
class AccelActivity : AppCompatActivity() {
    private val viewModel: AccelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            // The performance screen deliberately uses the same light SlmColors as the
            // settings/home family even when the system is in dark mode.
            SpeedLimitMeterTheme(darkTheme = false) {
                AccelScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onHostStart()
    }

    override fun onStop() {
        viewModel.onHostStop()
        super.onStop()
    }
}
