package com.zouhu.speedlimit.diag

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.zouhu.speedlimit.BuildConfig
import com.zouhu.speedlimit.data.map.baidu.BaiduHorizonClient
import com.zouhu.speedlimit.data.map.baidu.HorizonEvent
import com.zouhu.speedlimit.data.settings.SecretRedaction
import com.zouhu.speedlimit.domain.model.GpsSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug-only road-test diagnostic for the 超视距 (horizon) link. Connects with the
 * HORIZON AK (distinct from map/navi AK), feeds real GPS, and logs auth / links /
 * lanes / traffic-lights / server-position vs GPS to a shareable file so we can
 * tell "no auth" vs "wrong coords" vs "data present but filtered".
 */
class HorizonDiagActivity : AppCompatActivity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusView: TextView
    private var file: File? = null
    private var client: BaiduHorizonClient? = null
    private var lm: LocationManager? = null
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private var lastGps: GpsSample? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private val listener = object : BaiduHorizonClient.Listener {
        override fun onAuthenticated() {
            log("AUTH", "OK (horizon AK accepted)")
            runOnUiThread { statusView.text = "超视距已授权，等待数据…" }
        }

        override fun onEvent(event: HorizonEvent) {
            when (event) {
                is HorizonEvent.Position -> {
                    val p = event.position
                    log("POS", "path=${p.pathId} link=${p.linkId} offset=${p.offset} prob=${p.probability} speed=${p.speed}")
                }
                is HorizonEvent.Links -> log("LINKS", "count=${event.links.size} withLimit=${event.links.count { (it.speedLimit ?: 0) > 0 }}")
                is HorizonEvent.Lanes -> log("LANES", "arrows=${event.arrows.size}")
                is HorizonEvent.TrafficLights -> log("LIGHTS", "count=${event.lights.size}")
                is HorizonEvent.Cameras -> log("CAMERAS", "count=${event.cameras.size}")
                else -> {
                    val raw = com.zouhu.speedlimit.data.map.baidu.HorizonProtocol.lastUnknownRaw
                    log("EVT", event.javaClass.simpleName + (raw?.let { " raw=" + it.take(300) } ?: ""))
                }
            }
        }

        override fun onClosed(code: Int, reason: String) {
            log("CLOSED", "code=$code reason=$reason")
            runOnUiThread { statusView.text = "连接关闭 code=$code" }
        }

        override fun onFailure(error: Throwable) {
            log("FAIL", error.message ?: error.javaClass.simpleName)
            runOnUiThread { statusView.text = "失败: ${SecretRedaction.redact(error.message)}" }
        }
    }

    private val locListener = LocationListener { loc: Location ->
        val s = GpsSample(
            latitude = loc.latitude,
            longitude = loc.longitude,
            speedMps = loc.speed.toDouble(),
            bearingDeg = loc.bearing.toDouble(),
            accuracyM = loc.accuracy,
            timestampMs = System.currentTimeMillis(),
            hasFix = true,
        )
        lastGps = s
        client?.sendLoc(s)
        log("GPS", "(${loc.latitude},${loc.longitude}) speed=${loc.speed} acc=${loc.accuracy}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        statusView = TextView(this).apply { text = "超视距诊断：连接中…"; textSize = 16f }
        val share = Button(this).apply { text = "分享日志" }
        logView = TextView(this).apply { textSize = 11f }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(statusView)
        root.addView(share)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val dir = getExternalFilesDir("diag") ?: File(filesDir, "diag").apply { mkdirs() }
        file = File(dir, "horizon-diag.log")
        share.setOnClickListener { shareLog() }

        log("INIT", "horizonAkPresent=${BuildConfig.HORIZON_AK.isNotBlank()}")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else {
            start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else log("PERM", "location denied")
    }

    private fun start() {
        // onCreate/onRequestPermissionsResult already gate this, but keep the check
        // next to the call so the permission requirement is explicit and lint-clean.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            log("PERM", "location denied")
            return
        }
        val entity = "diag-" + UUID.randomUUID()
        client = BaiduHorizonClient(BuildConfig.HORIZON_AK, entity, listener).also { it.open() }
        lm = getSystemService(LocationManager::class.java)
        runCatching {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, mainLooper)
        }.onFailure { log("GPS", "gps provider unavailable: ${it.message}") }
        // static + dynamic lamp API (no 鹰眼 service_id needed); probe_lamp is
        // intentionally not used because this account has no 鹰眼 track service.
        val lamp = com.zouhu.speedlimit.data.map.baidu.LampDynamicClient(BuildConfig.HORIZON_AK)
        scope.launch {
            while (isActive) {
                val gps = lastGps
                if (gps != null) {
                    val result = lamp.query(
                        gps.latitude,
                        gps.longitude,
                        gps.bearingDeg,
                        gps.speedKmh,
                        System.currentTimeMillis(),
                    )
                    log(
                        "LAMP",
                        "static=${lamp.lastStaticStatus} dynamic=${lamp.lastDynamicStatus} " +
                            "msg=${lamp.lastMessage} " +
                            (result?.let { r ->
                                val age = ((System.currentTimeMillis() - r.timestampMs) / 1000L)
                                    .coerceAtLeast(0L)
                                "dist=${r.distanceM.toInt()}m links=${r.linkId} " +
                                    "serverAge=${age}s " +
                                    r.directions.joinToString { d ->
                                        "${d.flow}:" + d.phases.joinToString("/") { p ->
                                            "${p.state}@${p.countdownSec}s(${p.periodSec}s)"
                                        }
                                    }
                            } ?: "no-lamp"),
                    )
                }
                delay(2000)
            }
        }
    }

    private fun shareLog() {
        val f = file ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.probe.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享 horizon-diag.log"))
        }.onFailure { log("SHARE", it.message ?: "fail") }
    }

    private fun log(tag: String, msg: String) {
        val line = "${clock.format(Date())} [$tag] ${SecretRedaction.redact(msg)}"
        android.util.Log.i("VeloraHorizonDiag", line)
        runCatching { file?.appendText(line + "\n") }
        runOnUiThread {
            lines.addLast(line)
            while (lines.size > 400) lines.removeFirst()
            logView.text = lines.joinToString("\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { lm?.removeUpdates(locListener) }
        client?.close()
        super.onDestroy()
    }
}