package com.zouhu.speedlimit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zouhu.speedlimit.R
import com.zouhu.speedlimit.di.AppGraph
import com.zouhu.speedlimit.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class CruiseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val channelId = "cruise"
    private val notifId = 1
    private var locationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "巡航测速", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(notifId, buildNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(notifId, buildNotification(null))
            }
        } catch (e: Exception) {
            // Notification/FGS restrictions must never crash the app; degrade to stopped.
            stopSelf()
            return START_NOT_STICKY
        }
        if (locationJob?.isActive != true) {
            val locationRepo = AppGraph.locationRepository(this)
            AppGraph.attachService(this)
            locationRepo.start()
            locationJob = scope.launch {
                // Provider updates follow new samples only, so a status expiry cannot
                // duplicate the same GPS point into the detection track.
                launch {
                    locationRepo.latest.collect { sample ->
                        AppGraph.provider.updateLocation(sample)
                    }
                }
                // The notification must follow the same validity as the gauge. When the
                // GPS stream stops, status expires even though latest no longer emits.
                launch {
                    combine(locationRepo.latest, locationRepo.status) { sample, status ->
                        sample to status
                    }.collect { (sample, status) ->
                        // Never print a placeholder 0: without a live satellite speed the
                        // notification just describes the service, not a speed.
                        val speed = if (status.speed) sample.speedKmh.roundToInt() else null
                        getSystemService(NotificationManager::class.java)
                            .notify(notifId, buildNotification(speed))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(speedKmh: Int?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Velora · 巡航中")
            .setContentText(if (speedKmh != null) "当前车速 $speedKmh km/h" else "限速巡航运行中")
            .setSmallIcon(R.drawable.ic_stat_velora)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        AppGraph.locationRepository(this).stop()
        AppGraph.detachService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}