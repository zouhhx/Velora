package com.zouhu.speedlimit.provider.baidu

import android.util.Log
import android.os.SystemClock
import com.zouhu.speedlimit.data.location.LocationQuality
import com.zouhu.speedlimit.data.log.JsonlDebugLogger
import com.zouhu.speedlimit.data.map.baidu.BaiduMappers
import com.zouhu.speedlimit.data.map.baidu.CruiseRoadApi
import com.zouhu.speedlimit.data.map.baidu.CruiseTraceSink
import com.zouhu.speedlimit.data.map.baidu.CoordUtil
import com.zouhu.speedlimit.domain.engine.IntervalTracker
import com.zouhu.speedlimit.domain.model.CameraInfo
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import com.zouhu.speedlimit.data.nav.NavTrace
import com.zouhu.speedlimit.data.service.ServiceFailureKind
import com.zouhu.speedlimit.data.service.ServiceFailurePolicy
import com.zouhu.speedlimit.data.service.ServiceHealth
import com.zouhu.speedlimit.data.service.ServiceStatusSink
import com.zouhu.speedlimit.data.service.ServiceStatusStore
import com.zouhu.speedlimit.data.settings.SecretRedaction
import com.zouhu.speedlimit.domain.model.RoadData
import com.zouhu.speedlimit.provider.SpeedLimitProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BaiduCruiseProvider(
    private val api: CruiseRoadApi,
    private val logger: JsonlDebugLogger? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = 5_000L,
    /** VA-C01 capture; null in production defaults and in most tests. */
    private val trace: CruiseTraceSink? = null,
    /** AppGraph passes false when the server AK is blank; no request is attempted. */
    private val enabled: Boolean = true,
    private val statusSink: ServiceStatusSink = ServiceStatusStore,
    private val authPauseMs: Long = ServiceFailurePolicy.AUTH_PAUSE_MS,
) : SpeedLimitProvider {
    private val requestPolicy = CruiseRequestPolicy()
    private val _roadData = MutableStateFlow(RoadData())
    private val _status = MutableStateFlow(ConnectionStatus.OFFLINE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _status
    override fun observeRoadData(): StateFlow<RoadData> = _roadData

    private var job: Job? = null

    private val trackLock = Any()
    private val track = ArrayList<GpsSample>()
    private val roadLock = Any()
    @Volatile
    private var latestGps: GpsSample? = null
    private var lastGpsAt = 0L
    private var intervalCooldownUntil = 0L

    /** Auth/quota pause; no request while now < pausedUntil. */
    private var pausedUntil = 0L
    private var lastFailureLogKey: String? = null

    /** Incremented by every start/stop; late responses from an older run are rejected. */
    @Volatile
    private var runGeneration = 0
    /** Incremented whenever road state is invalidated; a response must match its snapshot. */
    @Volatile
    private var dataGeneration = 0

    override fun connect() {
        _status.value = ConnectionStatus.CONNECTING
    }

    override fun disconnect() {
        stopCruise()
    }

    override fun startCruise() {
        if (job?.isActive == true) return
        if (!enabled) {
            statusSink.update { it.copy(cruise = ServiceHealth.UNCONFIGURED) }
            return
        }
        val generation = ++runGeneration
        // A new cruise session must not inherit the previous trip's road, track or
        // interval accumulation. The interval carry below is only for one running
        // session across an HTTP refresh, never across start/stop.
        clearState(setOffline = false)
        requestPolicy.resetSession()
        pausedUntil = 0L
        lastFailureLogKey = null
        statusSink.update { it.copy(cruise = ServiceHealth.CONNECTING) }
        val initialDataGeneration = synchronized(roadLock) { dataGeneration }
        job = scope.launch {
            var loopDataGeneration = initialDataGeneration
            while (isActive) {
                if (generation != runGeneration) return@launch
                val loopNow = nowMs()
                if (loopNow < pausedUntil) {
                    delay(pollIntervalMs)
                    continue
                }
                // A stale-fix/explicit reset invalidates the previous road epoch. Reset
                // the request schedule too, otherwise a fresh GPS fix could have to wait
                // out the old 30 s refresh timer before any road data is requested.
                val currentDataGeneration = synchronized(roadLock) { dataGeneration }
                if (currentDataGeneration != loopDataGeneration) {
                    loopDataGeneration = currentDataGeneration
                }
                // Do not keep a road result alive after the last trusted fix expired.
                // This is deliberately later than the UI's 2.5 s hold and only clears
                // a genuinely stale fix, not every short low-quality sample.
                if (lastFixStale()) {
                    api.cancelCurrent()
                    clearState()
                    loopDataGeneration = synchronized(roadLock) { dataGeneration }
                }
                val snapshot = synchronized(trackLock) { track.toList() }
                val gps = latestGps ?: snapshot.lastOrNull() ?: GpsSample()
                // Only query when the position can actually be matched to a road. A
                // coarse network fix would return a limit for the wrong street.
                if (usable(gps) && snapshot.isNotEmpty()) {
                    val now = nowMs()
                    val decision = requestPolicy.shouldRequest(
                        nowMs = now,
                        sample = gps,
                        hasValidRoadData = _roadData.value.isValid,
                    )
                    if (decision.request) {
                        try {
                            val dataGen = synchronized(roadLock) { dataGeneration }
                            val resp = if (decision.detection) api.detection(snapshot) else api.refresh(snapshot)
                            if (generation != runGeneration || !isActive) return@launch
                            val quotaBlocked = requestPolicy.onResponse(now, resp.status)
                            val failure = ServiceFailurePolicy.classify(resp.status, resp.message)
                            if (failure != ServiceFailureKind.NONE) {
                                _status.value = ConnectionStatus.OFFLINE
                                if (ServiceFailurePolicy.isServicePaused(failure)) {
                                    pausedUntil = now + when (failure) {
                                        ServiceFailureKind.AUTH -> authPauseMs
                                        else -> ServiceFailurePolicy.QUOTA_PAUSE_MS
                                    }
                                    clearState()
                                    statusSink.update {
                                        it.copy(
                                            cruise = if (failure == ServiceFailureKind.AUTH) {
                                                ServiceHealth.EXPIRED
                                            } else {
                                                ServiceHealth.QUOTA
                                            },
                                        )
                                    }
                                    logFailureOnce(failure, resp.status, resp.message)
                                } else {
                                    statusSink.update { it.copy(cruise = ServiceHealth.UNAVAILABLE) }
                                    logFailureOnce(failure, resp.status, resp.message)
                                }
                                if (quotaBlocked) {
                                    val remain = requestPolicy.quotaRemainingMs(now)
                                    NavTrace.log(
                                        "HTTP",
                                        "quota 302, blocked ${remain / 60_000}min status=${resp.status}",
                                    )
                                }
                            } else {
                                val mapped = BaiduMappers.toRoadData(resp, gps, now)
                                synchronized(roadLock) {
                                    if (generation == runGeneration && isActive &&
                                        dataGen == dataGeneration
                                    ) {
                                        val previous = _roadData.value
                                        // A refresh replaces the whole RoadData; keep the
                                        // interval accumulator only when the same section
                                        // is still present.
                                        val road = mapped.copy(
                                            interval = carryIntervalAcrossRefresh(previous, mapped),
                                        )
                                        _roadData.value = road
                                        _status.value = if (road.isValid) {
                                            ConnectionStatus.ONLINE
                                        } else {
                                            ConnectionStatus.OFFLINE
                                        }
                                        if (road.isValid) {
                                            statusSink.update { it.copy(cruise = ServiceHealth.ACTIVE) }
                                        }
                                        lastFailureLogKey = null
                                        logger?.log(road, gps)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (generation == runGeneration && isActive) {
                                _status.value = ConnectionStatus.OFFLINE
                                statusSink.update { it.copy(cruise = ServiceHealth.UNAVAILABLE) }
                                logFailureOnce(ServiceFailureKind.TRANSIENT, null, e.message)
                            }
                        }
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    override fun stopCruise() {
        runGeneration++
        job?.cancel()
        job = null
        api.cancelCurrent()
        pausedUntil = 0L
        lastFailureLogKey = null
        clearState()
        statusSink.update {
            it.copy(cruise = if (enabled) ServiceHealth.IDLE else ServiceHealth.UNCONFIGURED)
        }
    }

    private fun logFailureOnce(kind: ServiceFailureKind, status: Int?, message: String?) {
        val key = "$kind:$status"
        if (key == lastFailureLogKey) return
        lastFailureLogKey = key
        val safe = SecretRedaction.redact(message)
        Log.w(TAG, "cruise service $kind status=$status msg=$safe")
        NavTrace.log("HTTP", "service $kind status=$status")
    }

    override fun updateLocation(sample: GpsSample) {
        if (isExplicitReset(sample)) {
            api.cancelCurrent()
            clearState()
            return
        }
        // A coarse fix never enters the track: Baidu's point_list is a driving track,
        // and one 500 m outlier poisons the whole detection window.
        if (!usable(sample)) {
            // Keep the 2.5 s anti-flicker semantics: a single bad sample does not clear
            // the road. Only a last trusted fix older than the 5 s data window does.
            if (lastFixStale()) {
                api.cancelCurrent()
                clearState()
            }
            return
        }
        latestGps = sample
        // Diagnostic only: records at most 1 Hz and never participates in the data path.
        runCatching { trace?.onGps(sample) }
        synchronized(trackLock) {
            track.add(sample)
            if (track.size > 25) track.removeAt(0)
        }
        recomputeCameraDistances(sample)
    }

    /**
     * AppGraph sends a default [GpsSample] to mean "the service stopped". It has no fix
     * and no timestamp, so it is an explicit reset rather than a low-quality fix.
     */
    private fun isExplicitReset(sample: GpsSample): Boolean =
        !sample.hasFix && sample.latitude == 0.0 && sample.longitude == 0.0 &&
            sample.timestampMs == 0L

    private fun lastFixStale(): Boolean {
        val last = latestGps ?: return false
        return LocationQuality.ageMs(
            last,
            SystemClock.elapsedRealtimeNanos(),
            nowMs(),
        ) > LocationQuality.DATA_MAX_AGE_MS
    }

    private fun clearState(setOffline: Boolean = true) {
        synchronized(roadLock) {
            dataGeneration++
            _roadData.value = RoadData()
        }
        latestGps = null
        synchronized(trackLock) { track.clear() }
        lastGpsAt = 0L
        intervalCooldownUntil = 0L
        if (setOffline) _status.value = ConnectionStatus.OFFLINE
    }

    private fun usable(sample: GpsSample): Boolean =
        LocationQuality.canFeedRoadData(
            sample,
            SystemClock.elapsedRealtimeNanos(),
            nowMs(),
        )

    private fun recomputeCameraDistances(gps: GpsSample) {
        if (!usable(gps)) return
        synchronized(roadLock) {
            val current = _roadData.value
            if (!current.isValid || current.cameras.isEmpty()) return
            val dtS = if (lastGpsAt > 0L) {
                ((gps.timestampMs - lastGpsAt) / 1000.0).coerceIn(0.0, 5.0)
            } else {
                0.0
            }
            lastGpsAt = gps.timestampMs
            val (lat, lng) = CoordUtil.wgs84ToGcj02(gps.latitude, gps.longitude)
            val cameras = current.cameras.map { camera ->
                if (camera.latitude == 0.0 && camera.longitude == 0.0) {
                    camera
                } else {
                    camera.copy(distanceM = BaiduMappers.haversine(lat, lng, camera.latitude, camera.longitude))
                }
            }
            val nearest = cameras
                .filter { it.type.isSpeedEnforcement }
                .minByOrNull { it.distanceM }
            val intervalSpeedLimit = cameras
                .filter { (it.rawType == INTERVAL_START_RAW || it.rawType == INTERVAL_END_RAW) &&
                    (it.speedLimit ?: 0) > 0 }
                .minByOrNull { it.distanceM }
                ?.speedLimit
            val interval = if (current.interval.phase == IntervalPhase.NONE &&
                gps.timestampMs < intervalCooldownUntil
            ) {
                current.interval
            } else {
                val next = IntervalTracker.update(
                    current = current.interval,
                    cameras = cameras,
                    speedMps = gps.speedMps,
                    dtS = dtS,
                    hasSpeed = gps.hasSpeed,
                )
                if (current.interval.phase == IntervalPhase.INSIDE && next.phase == IntervalPhase.NONE) {
                    // The end camera may stay in the list for a while; do not immediately
                    // restart the same section from its start camera.
                    intervalCooldownUntil = gps.timestampMs + INTERVAL_COOLDOWN_MS
                }
                next
            }
            val updated = current.copy(
                cameras = cameras,
                interval = interval,
                intervalSpeedLimit = intervalSpeedLimit,
                cameraAhead = nearest != null,
                cameraType = nearest?.type ?: CameraType.OTHER,
                distanceToCameraM = nearest?.distanceM ?: 0.0,
            )
            if (updated != current) _roadData.value = updated
        }
    }

    private companion object {
        const val TAG = "VeloraCruise"
        const val INTERVAL_COOLDOWN_MS = 60_000L
    }
}

/**
 * Keeps the interval accumulator across an HTTP refresh, but only while at least one
 * endpoint of that same section is still present in the refreshed camera list.
 *
 * The mapper builds a fresh [RoadData] with the default NONE interval on every
 * response. Replacing the old snapshot unconditionally would reset traveled/time/avg
 * every 30 s while moving. Carrying it unconditionally would be equally wrong after a
 * real road change, so the endpoint identity (raw type + link + offset) is the gate.
 */
internal fun carryIntervalAcrossRefresh(previous: RoadData, next: RoadData): IntervalSnapshot {
    val carried = previous.interval
    if (carried.phase == IntervalPhase.NONE) return next.interval
    val endpoints = previous.cameras.filter {
        it.rawType == INTERVAL_START_RAW || it.rawType == INTERVAL_END_RAW
    }
    if (endpoints.isEmpty()) return next.interval

    fun matches(previous: CameraInfo, nextCamera: CameraInfo): Boolean =
        previous.rawType == nextCamera.rawType &&
            previous.linkId == nextCamera.linkId &&
            kotlin.math.abs(previous.offsetM - nextCamera.offsetM) <= INTERVAL_ENDPOINT_TOLERANCE_M

    val persists = when (carried.phase) {
        // Approaching: the start camera must still be there, otherwise this is a new
        // section (or the old one is already behind us).
        IntervalPhase.APPROACH -> endpoints.any { p ->
            p.rawType == INTERVAL_START_RAW && next.cameras.any { n -> matches(p, n) }
        }
        // Inside: either endpoint is enough. The end camera can legitimately disappear
        // for a while; the accumulator must keep running until the state machine closes.
        IntervalPhase.INSIDE -> endpoints.any { p -> next.cameras.any { n -> matches(p, n) } }
        else -> false
    }
    return if (persists) carried else next.interval
}

private const val INTERVAL_START_RAW = 5
private const val INTERVAL_END_RAW = 9
private const val INTERVAL_ENDPOINT_TOLERANCE_M = 1.0