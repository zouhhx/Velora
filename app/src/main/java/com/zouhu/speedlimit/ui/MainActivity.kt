package com.zouhu.speedlimit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zouhu.speedlimit.data.map.baidu.BaiduMapSdk
import com.zouhu.speedlimit.data.nav.NavTrace
import com.zouhu.speedlimit.di.AppGraph
import com.zouhu.speedlimit.service.CruiseService
import com.zouhu.speedlimit.ui.screen.CruiseScreen
import com.zouhu.speedlimit.ui.theme.SpeedLimitMeterTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingStart = false
    private var serviceStarted = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val granted = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            // A coarse→fine upgrade must add the GPS/fused listeners to the running
            // location flow; starting the service alone did not re-register them (F07).
            AppGraph.locationRepository(this).refreshPermissions()
            pendingStart = true
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                pendingStart = false
                serviceStarted = true
                startCruiseService()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BaiduMapSdk.initialize(this)
        // Single crash sink. NavTrace also re-arms itself after the navi SDK replaces
        // the default handler at init (that handler exits the process silently).
        NavTrace.init(this)

        if (hasLocationPermission()) {
            pendingStart = true
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(perms.toTypedArray())
        }

        val settingsRepo = AppGraph.settingsRepository(this)
        scope.launch {
            settingsRepo.settings.collect { s ->
                if (s.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            SpeedLimitMeterTheme {
                val viewModel: CruiseViewModel = viewModel()
                CruiseScreen(viewModel)
            }
        }

        requestHighRefreshRate()
    }

    // Ask the panel for its highest refresh rate so the home⇄map scale transition can
    // run at 120fps on high-refresh displays. Window/UI hint only; the cruise service,
    // location and permission logic are untouched.
    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() {
        val maxHz = windowManager.defaultDisplay.supportedModes.maxOfOrNull { it.refreshRate } ?: return
        if (maxHz > 60f) {
            window.attributes = window.attributes.apply { preferredRefreshRate = maxHz }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        // Starting a foreground service before the activity is resumed throws on Android 12+.
        if (hasLocationPermission() && (!serviceStarted || pendingStart)) {
            pendingStart = false
            serviceStarted = true
            startCruiseService()
        }
    }

    private fun startCruiseService() {
        startForegroundService(Intent(this, CruiseService::class.java))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}