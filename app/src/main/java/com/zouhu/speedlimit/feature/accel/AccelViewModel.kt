package com.zouhu.speedlimit.feature.accel

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zouhu.speedlimit.data.location.LocationRepository
import com.zouhu.speedlimit.di.AppGraph
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource
import com.zouhu.speedlimit.feature.accel.core.AccelSpeedFilter
import com.zouhu.speedlimit.feature.accel.core.GnssFix
import com.zouhu.speedlimit.feature.accel.core.PerformanceCalculator
import com.zouhu.speedlimit.feature.accel.core.TestMode
import com.zouhu.speedlimit.feature.accel.core.TestRecord
import com.zouhu.speedlimit.feature.accel.core.TestStore
import com.zouhu.speedlimit.feature.accel.core.ZeroHundredSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class AccelAction {
    START,
    CANCEL_STANDBY,
    END,
    RETRY,
}

data class AccelResultUi(
    val durationText: String,
    val zeroTo60Text: String,
    val zeroTo100Text: String,
    val bestText: String,
    val valid: Boolean,
    val qualityText: String,
    val reason: String?,
    val isNewBest: Boolean,
)

data class AccelUiState(
    val modeLabel: String = "0–100 km/h",
    val statusTitle: String = "等待 GPS",
    val statusDetail: String = "首次定位约 5–30 秒",
    val timerText: String = "0.00",
    val speedKmh: Int = 0,
    val progress: Float = 0f,
    val hasFix: Boolean = false,
    val bestText: String = "—",
    val assistEnabled: Boolean = false,
    val assistAvailable: Boolean = true,
    val assistToggleEnabled: Boolean = true,
    val action: AccelAction = AccelAction.START,
    val result: AccelResultUi? = null,
)

/**
 * Read-only adapter around Velora's existing location flow.
 *
 * It never starts a network provider and never touches Baidu; the old zero-to-100
 * state machine and calculator consume the same GpsSample values as the cruise UI.
 */
class AccelViewModel(app: Application) : AndroidViewModel(app) {
    private val mode = TestMode.ZERO_100
    private val location: LocationRepository = AppGraph.locationRepository(app)
    private val store = TestStore(app)
    private val filter = AccelSpeedFilter()
    private val recent = ArrayDeque<GnssFix>()
    private val prefs = app.getSharedPreferences("velora_accel_prefs", Context.MODE_PRIVATE)
    private var assistEnabled = prefs.getBoolean(KEY_ASSIST, false)
    private val assistSensor = AccelAssistSensor(app) { linear, gyro, eventNs ->
        session?.onAccel(linear, gyro, eventNs)
    }
    private var assistAvailable = assistSensor.available

    private var session: ZeroHundredSession? = null
    private var lastRecord: TestRecord? = null
    private var bestRecord: TestRecord? = null
    private var lastFixNanos = 0L
    private var foreground = false
    private var newBest = false

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(AccelUiState())
    val state: kotlinx.coroutines.flow.StateFlow<AccelUiState> = _state

    private val listener = object : ZeroHundredSession.Listener {
        override fun onSessionUpdated() {
            updateState()
        }

        override fun onTestFinished(record: TestRecord?) {
            lastRecord = record
            newBest = false
            if (record != null && record.valid) {
                val oldBest = bestRecord?.durationMs
                newBest = oldBest == null || oldBest <= 0L || record.durationMs < oldBest
            }
            updateState()
            assistSensor.stop()
            if (record != null) saveRecord(record)
        }
    }

    init {
        loadHistory()
        viewModelScope.launch {
            location.latest.collect { sample -> onLocation(sample) }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(50L)
                if (foreground) updateState()
            }
        }
    }

    fun onHostStart() {
        foreground = true
        syncAssistSensor()
        updateState()
    }

    fun onHostStop() {
        foreground = false
        assistSensor.stop()
        // Same contract as the old zero-to-100 page: leaving while timing invalidates
        // the run; waiting/finished sessions stay visible if the screen comes back.
        session?.let { if (it.isRunningLike) it.stopAndInvalidate() }
        updateState()
    }

    fun setAssistEnabled(enabled: Boolean) {
        if (session?.isRunningLike == true) return
        assistEnabled = enabled
        prefs.edit().putBoolean(KEY_ASSIST, enabled).apply()
        session?.setAssistEnabled(enabled)
        syncAssistSensor()
        updateState()
    }

    private fun syncAssistSensor() {
        if (!assistAvailable) {
            assistSensor.stop()
            session?.setAssistSensorMissing(true)
            return
        }
        val current = session
        val should = AccelAssistPolicy.shouldRegister(
            assistEnabled = assistEnabled,
            foreground = foreground,
            sessionActive = current != null,
            sessionFinished = current?.isFinishedLike == true,
            sensorAvailable = assistAvailable,
        )
        if (should) {
            if (!assistSensor.start()) {
                assistAvailable = false
                session?.setAssistSensorMissing(true)
            }
        } else {
            assistSensor.stop()
        }
        session?.setAssistEnabled(assistEnabled)
    }

    fun onPrimaryAction() {
        val current = session
        when {
            current == null -> startPrepare()
            current.isRunningLike -> current.cancel()
            current.isFinishedLike -> {
                resetSession()
                startPrepare()
            }
            else -> {
                // Cancel standby before the run has started.
                session = null
                lastRecord = null
                assistSensor.stop()
                updateState()
            }
        }
    }

    fun reset() {
        session?.reset()
        session = null
        lastRecord = null
        newBest = false
        assistSensor.stop()
        filter.reset()
        updateState()
    }

    private fun startPrepare() {
        if (session != null) return
        val next = ZeroHundredSession(listener, mode)
        next.setAssistEnabled(assistEnabled)
        next.setForcedStart(false)
        next.setAssistSensorMissing(!assistAvailable)
        if (mode.isStationary) {
            // Reuse the old page's pre-roll injection so a driver who just stopped
            // does not have to wait another full two seconds after pressing start.
            val cutoff = SystemClock.elapsedRealtimeNanos() - 4_000_000_000L
            for (fix in recent) {
                if (fix.elapsedNanos >= cutoff && fix.speedKmh() < 0.8) {
                    next.onGnssFix(fix)
                }
            }
        }
        lastRecord = null
        newBest = false
        session = next
        syncAssistSensor()
        updateState()
    }

    private fun resetSession() {
        session?.reset()
        session = null
        lastRecord = null
        newBest = false
        assistSensor.stop()
        updateState()
    }

    private fun onLocation(sample: GpsSample) {
        if (!sample.hasFix || !sample.hasSpeed || sample.elapsedRealtimeNanos <= 0L) return
        if (sample.source != LocationSource.GPS && sample.source != LocationSource.FUSED) return

        val fix = GnssFix(
            sample.elapsedRealtimeNanos,
            sample.timestampMs,
            sample.speedMps,
            if (sample.hasAccuracy) sample.accuracyM.toDouble() else Double.NaN,
            if (sample.hasSpeedAccuracy) sample.speedAccuracyMps.toDouble() else Double.NaN,
            sample.latitude,
            sample.longitude,
        )
        lastFixNanos = fix.elapsedNanos
        recent.addLast(fix)
        while (recent.isNotEmpty() && recent.first.elapsedNanos < fix.elapsedNanos - 6_000_000_000L) {
            recent.removeFirst()
        }
        filter.onFix(sample.speedMps, fix.elapsedNanos)
        session?.onGnssFix(fix)
        updateState()
    }

    private fun updateState() {
        val now = SystemClock.elapsedRealtimeNanos()
        val current = session
        val hasFix = lastFixNanos > 0L && now - lastFixNanos <= 3_000_000_000L
        val displayKmh = if (hasFix) filter.tick(now) else 0.0
        val rate = current?.gpsRateHz ?: 0.0
        val elapsed = current?.getElapsedMs(now) ?: 0.0
        val timer = formatTimer(elapsed)
        val progress = when {
            current?.isFinishedLike == true -> 1f
            else -> (displayKmh / 100.0).toFloat().coerceIn(0f, 1f)
        }
        val title = when {
            current != null -> current.statusTitle
            hasFix -> "准备就绪"
            else -> "等待 GPS"
        }
        val detail = when {
            current != null -> {
                val base = current.statusDetail
                if (!hasFix) "$base · GPS 准备中" else base
            }
            hasFix -> "停稳 2 秒后起步"
            else -> "首次定位约 5–30 秒"
        }
        val action = when {
            current == null -> AccelAction.START
            current.isRunningLike -> AccelAction.END
            current.isFinishedLike -> AccelAction.RETRY
            else -> AccelAction.CANCEL_STANDBY
        }
        _state.value = AccelUiState(
            modeLabel = mode.label,
            statusTitle = title,
            statusDetail = detail,
            timerText = timer,
            speedKmh = displayKmh.roundToInt().coerceAtLeast(0),
            progress = progress,
            hasFix = hasFix,
            bestText = bestRecord?.takeIf { it.durationMs > 0L }?.let {
                formatDuration(it.durationMs, it.gpsRateHz) + " s"
            } ?: "—",
            assistEnabled = assistEnabled,
            assistAvailable = assistAvailable,
            assistToggleEnabled = AccelAssistPolicy.canToggle(current?.isRunningLike == true),
            action = action,
            result = lastRecord?.let { buildResult(it) },
        )
    }

    private fun buildResult(record: TestRecord): AccelResultUi {
        val rate = record.gpsRateHz
        val zeroTo60 = PerformanceCalculator.crossing(record.samples, 60.0)?.tMs
        val quality = qualityLabel(record.quality)
        return AccelResultUi(
            durationText = if (record.valid) formatDuration(record.durationMs, rate) + " s" else "—",
            zeroTo60Text = if (record.valid && zeroTo60 != null) {
                formatDuration(zeroTo60.roundToLong(), rate) + " s"
            } else {
                "—"
            },
            zeroTo100Text = if (record.valid) formatDuration(record.durationMs, rate) + " s" else "—",
            bestText = bestRecord?.takeIf { it.durationMs > 0L }?.let {
                formatDuration(it.durationMs, it.gpsRateHz) + " s"
            } ?: "—",
            valid = record.valid,
            qualityText = quality,
            reason = record.reason?.takeIf { !record.valid && it.isNotBlank() },
            isNewBest = newBest && record.valid,
        )
    }

    private fun saveRecord(record: TestRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(record) }
            val best = runCatching { store.bestValid(mode) }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                bestRecord = best
                updateState()
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val best = runCatching { store.bestValid(mode) }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                bestRecord = best
                updateState()
            }
        }
    }

    private fun formatTimer(ms: Double): String {
        if (ms <= 0.0) return "0.00"
        return String.format(Locale.US, "%.2f", ms / 1000.0)
    }

    private fun formatDuration(ms: Long, rate: Double): String {
        if (ms <= 0L) return "—"
        val seconds = ms / 1000.0
        return if (rate >= 5.0) {
            String.format(Locale.US, "%.2f", seconds)
        } else {
            String.format(Locale.US, "%.1f", seconds)
        }
    }

    private fun qualityLabel(quality: String?): String = when (quality) {
        TestRecord.QUALITY_GOOD -> "正常"
        TestRecord.QUALITY_FAIR -> "一般"
        TestRecord.QUALITY_POOR -> "低置信"
        TestRecord.QUALITY_INTERRUPTED -> "信号中断"
        TestRecord.QUALITY_ABORTED -> "已取消"
        else -> "数据不足"
    }

    private companion object {
        const val KEY_ASSIST = "zero100_assist"
    }

    override fun onCleared() {
        store.close()
        assistSensor.stop()
        super.onCleared()
    }
}
