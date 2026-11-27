package com.zouhu.speedlimit.provider.baidu

import android.os.SystemClock
import android.util.Log
import com.zouhu.speedlimit.data.location.LocationQuality
import com.zouhu.speedlimit.data.map.baidu.BaiduHorizonClient
import com.zouhu.speedlimit.data.map.baidu.BaiduMappers
import com.zouhu.speedlimit.data.map.baidu.CoordUtil
import com.zouhu.speedlimit.data.map.baidu.HorizonCamera
import com.zouhu.speedlimit.data.map.baidu.HorizonEvent
import com.zouhu.speedlimit.data.map.baidu.HorizonLaneArrow
import com.zouhu.speedlimit.data.map.baidu.HorizonLink
import com.zouhu.speedlimit.data.map.baidu.HorizonPosition
import com.zouhu.speedlimit.data.map.baidu.HorizonProtocol
import com.zouhu.speedlimit.data.map.baidu.HorizonTrafficLight
import com.zouhu.speedlimit.data.map.baidu.HorizonTrafficLightStatus
import com.zouhu.speedlimit.data.map.baidu.LampDynamicClient
import com.zouhu.speedlimit.data.service.ServiceFailureKind
import com.zouhu.speedlimit.data.service.ServiceFailurePolicy
import com.zouhu.speedlimit.data.service.ServiceHealth
import com.zouhu.speedlimit.data.service.ServiceStatusSink
import com.zouhu.speedlimit.data.service.ServiceStatusStore
import com.zouhu.speedlimit.data.settings.SecretRedaction
import com.zouhu.speedlimit.domain.model.CameraInfo
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.DirectionalTrafficLight
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.Lane
import com.zouhu.speedlimit.domain.model.RoadData
import com.zouhu.speedlimit.domain.model.TrafficLightFlow
import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.domain.model.TrafficLightPhase
import com.zouhu.speedlimit.domain.model.TrafficLightState
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
import java.util.UUID
import kotlin.math.abs

class BaiduHorizonProvider(
    private val ak: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val statusSink: ServiceStatusSink = ServiceStatusStore,
    private val authPauseMs: Long = ServiceFailurePolicy.AUTH_PAUSE_MS,
) : SpeedLimitProvider {
    private val road = MutableStateFlow(RoadData())
    private val status = MutableStateFlow(ConnectionStatus.OFFLINE)
    override val connectionStatus: StateFlow<ConnectionStatus> = status
    override fun observeRoadData(): StateFlow<RoadData> = road

    private val lock = Any()
    private val entityId = UUID.randomUUID().toString()
    private var connected = false
    private var running = false
    private var socket: BaiduHorizonClient? = null

    /** Identifies the current socket; callbacks from an older one are dropped (VA-P05). */
    private var socketGeneration = 0
    private var authenticated = false
    private var reconnectJob: Job? = null
    private var locJob: Job? = null
    private var watchdogJob: Job? = null
    private var backoffMs = 2_000L
    @Volatile
    private var latest: GpsSample? = null
    private var lastLocSentAt = 0L

    private var version = 0
    private var position: HorizonPosition? = null
    private var positionAt = 0L
    private val links = LinkedHashMap<Long, HorizonLink>()
    private val cameras = LinkedHashMap<String, HorizonCamera>()
    private var laneArrows: List<HorizonLaneArrow> = emptyList()
    private var trafficLights: List<HorizonTrafficLight> = emptyList()
    private var lightClockSkewMs = 0L
    private var lampLight: TrafficLightInfo? = null
    private var lampJob: kotlinx.coroutines.Job? = null
    private val lampClient by lazy { LampDynamicClient(ak) }
    private var authPausedUntil = 0L
    private var authFailureLogged = false
    private var lampPausedUntil = 0L
    private var lampFailureLogged = false

    override fun connect() = synchronized(lock) {
        connected = true
        if (status.value == ConnectionStatus.OFFLINE) status.value = ConnectionStatus.CONNECTING
    }

    override fun startCruise() {
        if (ak.isBlank()) {
            statusSink.update {
                it.copy(horizon = ServiceHealth.UNCONFIGURED, lamp = ServiceHealth.UNCONFIGURED)
            }
            return
        }
        synchronized(lock) {
            if (running || !connected) return
            running = true
            authenticated = false
            authPausedUntil = 0L
            authFailureLogged = false
            lampPausedUntil = 0L
            lampFailureLogged = false
            resetSnapshotLocked()
            status.value = ConnectionStatus.CONNECTING
            statusSink.update {
                it.copy(horizon = ServiceHealth.CONNECTING, lamp = ServiceHealth.CONNECTING)
            }
            openSocketLocked()
        }
        if (locJob?.isActive != true) {
            locJob = scope.launch {
                while (isActive) {
                    sendLatest()
                    delay(1_000)
                }
            }
        }
        if (watchdogJob?.isActive != true) {
            watchdogJob = scope.launch {
                while (isActive) {
                    invalidateIfStale()
                    delay(1_000)
                }
            }
        }
        if (lampJob?.isActive != true) {
            lampJob = scope.launch {
                var lastInterval = -1L
                while (isActive) {
                    pollLamp()
                    val interval = lampDelayMs()
                    if (interval != lastInterval) {
                        lastInterval = interval
                        Log.i(TAG, "lamp poll interval=${interval}ms near=${hasNearbyLight()}")
                    }
                    delay(interval)
                }
            }
        }
    }

    /**
     * Dynamic lamp queries are only useful near an intersection. Without a known
     * light, poll at 10 s for discovery; once a light is within 200 m, use 1 s for
     * the countdown. This keeps a parked car from spending the daily quota.
     */
    private fun hasNearbyLight(): Boolean = synchronized(lock) {
        trafficLights.any { it.distanceToPos in 0.0..200.0 } ||
            (lampLight?.distanceM ?: Double.MAX_VALUE) <= 200.0
    }

    private fun lampPollIntervalMs(): Long = if (hasNearbyLight()) 1_000L else 10_000L

    /** While an auth/quota pause is active, sleep instead of polling every 10 s. */
    private fun lampDelayMs(): Long {
        val remaining = (lampPausedUntil - clock()).coerceAtLeast(0L)
        return if (remaining > 0L) remaining else lampPollIntervalMs()
    }

    private suspend fun pollLamp() {
        val now = clock()
        if (now < lampPausedUntil) return
        val gps = latest
        if (gps == null || !usable(gps)) {
            synchronized(lock) {
                lampLight = null
                rebuildLocked()
            }
            return
        }
        val result = lampClient.query(
            lat = gps.latitude,
            lng = gps.longitude,
            bearingDeg = gps.bearingDeg,
            speedKmh = gps.speedKmh,
            nowMs = now,
        )
        val staticStatus = lampClient.lastStaticStatus
        val dynamicStatus = lampClient.lastDynamicStatus
        val failure = listOf(
            ServiceFailurePolicy.classify(staticStatus, lampClient.lastMessage),
            ServiceFailurePolicy.classify(dynamicStatus, lampClient.lastMessage),
        ).maxByOrNull { failurePriority(it) } ?: ServiceFailureKind.NONE
        synchronized(lock) {
            lampLight = result?.let {
                TrafficLightInfo(
                    distanceM = it.distanceM,
                    timestampMs = it.timestampMs,
                    phases = it.primaryPhases,
                    directions = it.directions.map { d ->
                        DirectionalTrafficLight(d.flow, d.phases)
                    },
                )
            }
            rebuildLocked()
        }
        when {
            failure == ServiceFailureKind.AUTH || failure == ServiceFailureKind.QUOTA -> {
                lampPausedUntil = now + when (failure) {
                    ServiceFailureKind.AUTH -> authPauseMs
                    else -> ServiceFailurePolicy.QUOTA_PAUSE_MS
                }
                if (!lampFailureLogged) {
                    lampFailureLogged = true
                    val safe = SecretRedaction.redact(lampClient.lastMessage)
                    Log.w(TAG, "lamp service $failure static=$staticStatus dynamic=$dynamicStatus msg=$safe")
                }
                statusSink.update {
                    it.copy(
                        lamp = if (failure == ServiceFailureKind.AUTH) {
                            ServiceHealth.EXPIRED
                        } else {
                            ServiceHealth.QUOTA
                        },
                    )
                }
            }
            result != null -> {
                lampFailureLogged = false
                statusSink.update { it.copy(lamp = ServiceHealth.ACTIVE) }
            }
            failure == ServiceFailureKind.NONE -> {
                // A valid empty discovery response means the service is reachable;
                // it just has no lamp for this location.
                lampFailureLogged = false
                statusSink.update { it.copy(lamp = ServiceHealth.ACTIVE) }
            }
            else -> {
                if (!lampFailureLogged) {
                    lampFailureLogged = true
                    val safe = SecretRedaction.redact(lampClient.lastMessage)
                    Log.w(TAG, "lamp service $failure static=$staticStatus dynamic=$dynamicStatus msg=$safe")
                }
                statusSink.update { it.copy(lamp = ServiceHealth.UNAVAILABLE) }
            }
        }
    }

    private fun failurePriority(kind: ServiceFailureKind): Int = when (kind) {
        ServiceFailureKind.NONE -> 0
        ServiceFailureKind.TRANSIENT -> 1
        ServiceFailureKind.QUOTA -> 2
        ServiceFailureKind.AUTH -> 3
    }

    override fun stopCruise() = synchronized(lock) {
        running = false
        // Invalidate every in-flight callback, including one already queued on OkHttp.
        socketGeneration++
        connected = false
        authenticated = false
        socket?.close()
        socket = null
        reconnectJob?.cancel()
        reconnectJob = null
        locJob?.cancel()
        locJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        lampJob?.cancel()
        lampJob = null
        lampLight = null
        authPausedUntil = 0L
        authFailureLogged = false
        lampPausedUntil = 0L
        lampFailureLogged = false
        resetSnapshotLocked()
        road.value = RoadData()
        status.value = ConnectionStatus.OFFLINE
        statusSink.update {
            it.copy(
                horizon = if (ak.isBlank()) ServiceHealth.UNCONFIGURED else ServiceHealth.IDLE,
                lamp = if (ak.isBlank()) ServiceHealth.UNCONFIGURED else ServiceHealth.IDLE,
            )
        }
    }

    override fun disconnect() = stopCruise()

    override fun updateLocation(sample: GpsSample) {
        // Keep the last *trustworthy* fix. Sending a coarse point to the horizon service
        // yields a low-probability link, and the UI would render it as fact.
        if (usable(sample)) latest = sample
    }

    private fun usable(sample: GpsSample): Boolean =
        LocationQuality.canFeedRoadData(
            sample,
            SystemClock.elapsedRealtimeNanos(),
            System.currentTimeMillis(),
        )

    private fun openSocketLocked() {
        if (clock() < authPausedUntil) return
        socket?.close()
        socketGeneration++
        statusSink.update { it.copy(horizon = ServiceHealth.CONNECTING) }
        socket = BaiduHorizonClient(ak, entityId, SocketListener(socketGeneration), clock = clock)
            .also { it.open() }
    }

    private inner class SocketListener(private val generation: Int) : BaiduHorizonClient.Listener {
        override fun onAuthenticated() {
            synchronized(lock) {
                if (!running || generation != socketGeneration) return
                authenticated = true
                backoffMs = 2_000L
                authFailureLogged = false
                status.value = ConnectionStatus.CONNECTING
                statusSink.update { it.copy(horizon = ServiceHealth.CONNECTING) }
                resetSnapshotLocked()
                Log.i(TAG, "horizon authenticated")
            }
        }

        override fun onEvent(event: HorizonEvent) = synchronized(lock) {
            if (!running || generation != socketGeneration) return
            when (event) {
                is HorizonEvent.Position -> onPositionLocked(event)
                is HorizonEvent.Links -> onLinksLocked(event)
                is HorizonEvent.Cameras -> onCamerasLocked(event)
                is HorizonEvent.Lanes -> onLanesLocked(event)
                is HorizonEvent.TrafficLights -> onTrafficLightsLocked(event)
                HorizonEvent.Unknown, is HorizonEvent.Ack, is HorizonEvent.Auth -> Unit
            }
        }

        override fun onClosed(code: Int, reason: String) = synchronized(lock) {
            if (!running || generation != socketGeneration) return
            handleFailureLocked(error = null, code = code, reason = reason)
        }

        override fun onFailure(error: Throwable) = synchronized(lock) {
            if (!running || generation != socketGeneration) return
            handleFailureLocked(error = error)
        }
    }

    private fun handleFailureLocked(
        error: Throwable?,
        code: Int? = null,
        reason: String? = null,
    ) {
        authenticated = false
        socket = null
        status.value = ConnectionStatus.OFFLINE
        position = null
        road.value = RoadData()
        val kind = error?.let { ServiceFailurePolicy.classify(it) }
            ?: ServiceFailurePolicy.classify(null, reason).let {
                if (it == ServiceFailureKind.NONE) ServiceFailureKind.TRANSIENT else it
            }
        val now = clock()
        when {
            kind == ServiceFailureKind.AUTH || kind == ServiceFailureKind.QUOTA -> {
                val pause = if (kind == ServiceFailureKind.AUTH) {
                    authPauseMs
                } else {
                    ServiceFailurePolicy.QUOTA_PAUSE_MS
                }
                authPausedUntil = now + pause
                statusSink.update {
                    it.copy(
                        horizon = if (kind == ServiceFailureKind.AUTH) {
                            ServiceHealth.EXPIRED
                        } else {
                            ServiceHealth.QUOTA
                        },
                    )
                }
                if (!authFailureLogged) {
                    authFailureLogged = true
                    val detail = SecretRedaction.redact(error?.message ?: reason)
                    Log.w(TAG, "horizon service $kind code=$code msg=$detail")
                }
                scheduleReconnectLocked(pause)
            }
            else -> {
                statusSink.update { it.copy(horizon = ServiceHealth.UNAVAILABLE) }
                val detail = SecretRedaction.redact(error?.message ?: reason)
                Log.w(TAG, "horizon closed code=$code reason=$detail")
                scheduleReconnectLocked()
            }
        }
    }

    private fun scheduleReconnectLocked(waitMs: Long = -1L) {
        if (!running || reconnectJob?.isActive == true) return
        val wait = if (waitMs >= 0L) waitMs else backoffMs
        if (waitMs < 0L) {
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        reconnectJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                if (running) {
                    reconnectJob = null
                    openSocketLocked()
                }
            }
        }
    }

    private fun onPositionLocked(event: HorizonEvent.Position) {
        if (event.version <= 0) return
        if (!ensureVersionLocked(event.version)) return
        position = event.position
        positionAt = clock()
        status.value = ConnectionStatus.ONLINE
        statusSink.update { it.copy(horizon = ServiceHealth.ACTIVE) }
        rebuildLocked()
    }

    private fun onLinksLocked(event: HorizonEvent.Links) {
        if (!ensureVersionLocked(event.version)) return
        for (link in event.links) links[link.linkId] = link
        while (links.size > 512) links.remove(links.keys.first())
        Log.i(TAG, "links=${event.links.size} withLimit=${event.links.count { (it.speedLimit ?: 0) > 0 }}")
        rebuildLocked()
    }

    private fun onCamerasLocked(event: HorizonEvent.Cameras) {
        if (!ensureVersionLocked(event.version)) return
        for (camera in event.cameras) cameras[cameraKey(camera)] = camera
        while (cameras.size > 256) cameras.remove(cameras.keys.first())
        rebuildLocked()
    }

    private fun onLanesLocked(event: HorizonEvent.Lanes) {
        if (!ensureVersionLocked(event.version)) return
        laneArrows = event.arrows
        Log.i(TAG, "laneArrows=${event.arrows.size}")
        rebuildLocked()
    }

    private fun onTrafficLightsLocked(event: HorizonEvent.TrafficLights) {
        if (!ensureVersionLocked(event.version)) return
        trafficLights = event.lights
        Log.i(TAG, "trafficLights=${event.lights.size}")
        // Map the server clock onto the local clock so a skewed phone clock does
        // not shift the lamp phase/countdown. Keep the last calibration if this
        // particular message carries no ts.
        if (event.serverTsMs > 0L) lightClockSkewMs = event.serverTsMs - clock()
        rebuildLocked()
    }

    private fun ensureVersionLocked(v: Int): Boolean {
        if (v <= 0) return true
        if (v < version) return false
        if (v > version) {
            version = v
            links.clear()
            cameras.clear()
            laneArrows = emptyList()
            trafficLights = emptyList()
            lightClockSkewMs = 0L
        }
        return true
    }

    private fun sendLatest() = synchronized(lock) {
        if (!running || !authenticated) return@synchronized
        val sample = latest ?: return@synchronized
        val now = clock()
        if (!sample.hasFix || sample.timestampMs <= 0 || now - sample.timestampMs > 60_000) return@synchronized
        // Keep 1 Hz while driving; slow down to 1/3 Hz while parked to protect service quota.
        val minInterval = if (sample.speedKmh >= 3.0) 900L else 3_000L
        if (now - lastLocSentAt < minInterval) return@synchronized
        if (socket?.sendLoc(sample) == true) lastLocSentAt = now
    }

    private fun invalidateIfStale() = synchronized(lock) {
        if (!running) return@synchronized
        val pos = position ?: return@synchronized
        if (clock() - positionAt <= 5_000) return@synchronized
        position = null
        road.value = RoadData(timestampMs = clock(), isValid = false)
        status.value = ConnectionStatus.CONNECTING
    }

    private fun resetSnapshotLocked() {
        version = 0
        position = null
        positionAt = 0L
        lastLocSentAt = 0L
        links.clear()
        cameras.clear()
        laneArrows = emptyList()
        trafficLights = emptyList()
        lightClockSkewMs = 0L
        lampLight = null
        road.value = RoadData()
    }

    private fun rebuildLocked() {
        val pos = position
        if (pos == null) {
            road.value = RoadData(timestampMs = clock(), isValid = false)
            return
        }
        val currentLink = links[pos.linkId] ?: links.values.firstOrNull {
            it.pathId == pos.pathId && pos.offset >= it.offset && pos.offset <= it.offset + it.length
        }
        val limit = currentLink
            ?.takeIf { it.speedLimitType != 4 }
            ?.speedLimit
            ?.takeIf { it > 0 }
        val cameraInfos = cameras.values.mapNotNull { camera ->
            cameraInfo(pos, camera)
        }
        val speedCameras = cameraInfos.filter { it.type.isSpeedEnforcement }
        val nearest = speedCameras.minByOrNull { it.distanceM }
        road.value = RoadData(
            roadId = currentLink?.linkId?.toString(),
            roadName = currentLink?.roadName,
            speedLimit = limit,
            speedLimitType = currentLink?.speedLimitType,
            cameraSpeedLimit = nearest?.speedLimit?.takeIf { it > 0 },
            cameras = cameraInfos,
            lanes = currentLanesLocked(pos),
            trafficLight = trafficLightInfo(pos) ?: lampLight,
            cameraAhead = nearest != null,
            cameraType = nearest?.type ?: CameraType.OTHER,
            distanceToCameraM = nearest?.distanceM ?: 0.0,
            confidence = if (pos.probability > 1.0) (pos.probability / 100.0).coerceIn(0.0, 1.0) else pos.probability.coerceIn(0.0, 1.0),
            dataVersion = version.toString(),
            timestampMs = clock(),
            isValid = true,
        )
    }

    private fun cameraInfo(position: HorizonPosition, camera: HorizonCamera): CameraInfo? {
        val samePath = camera.pathId == position.pathId
        val offset = camera.offset
        val signed = if (samePath && offset != null) offset - position.offset else null
        if (signed != null && signed < -5.0) return null
        val loc = camera.loc
        val distance = when {
            signed != null -> signed.coerceAtLeast(0.0)
            loc != null && loc.size >= 2 -> {
                val gps = latest
                if (gps == null || !gps.hasFix) return null
                // camera.loc is bd09; compare in bd09 (not gcj02).
                val (gLat, gLng) = CoordUtil.wgs84ToBd09(gps.latitude, gps.longitude)
                BaiduMappers.haversine(gLat, gLng, loc[1], loc[0])
            }
            else -> camera.distanceToPos.takeIf { it >= 0.0 } ?: return null
        }
        if (!distance.isFinite() || distance > 2_000.0) return null
        return CameraInfo(
            type = CameraType.fromBaiduCode(camera.type),
            speedLimit = camera.speedLimit?.takeIf { it > 0 },
            latitude = loc?.getOrNull(1) ?: 0.0,
            longitude = loc?.getOrNull(0) ?: 0.0,
            linkId = camera.linkid?.toString(),
            offsetM = offset ?: 0.0,
            distanceM = distance,
            rawType = camera.type,
        )
    }

    private fun trafficLightInfo(position: HorizonPosition): TrafficLightInfo? {
        val candidates = trafficLights.mapNotNull { light ->
            val distance = when {
                light.pathId == position.pathId -> light.offset - position.offset
                light.loc != null && light.loc.size >= 2 -> {
                    val gps = latest
                    if (gps == null || !gps.hasFix) return@mapNotNull null
                    // light.loc is bd09; compare in bd09 (not gcj02) or distance is wrong.
                    val (gLat, gLng) = CoordUtil.wgs84ToBd09(gps.latitude, gps.longitude)
                    BaiduMappers.haversine(gLat, gLng, light.loc[1], light.loc[0])
                }
                else -> light.distanceToPos.takeIf { it > 0.0 } ?: return@mapNotNull null
            }
            if (!distance.isFinite() || distance < -5.0 || distance > LIGHT_ALERT_RANGE_M) {
                return@mapNotNull null
            }
            val phases = horizonTrafficLightPhases(light.status) ?: return@mapNotNull null
            TrafficLightCandidate(distance.coerceAtLeast(0.0), light, phases)
        }
        val nearest = candidates.minByOrNull { it.distanceM } ?: return null
        // One intersection can expose straight/left/right lamps at the same offset.
        // Without a planned route, prefer the on-route straight lamp.
        val chosen = candidates
            .filter { it.distanceM <= nearest.distanceM + LIGHT_SAME_INTERSECTION_M }
            .sortedWith(
                compareBy<TrafficLightCandidate> { if (it.light.onRoute == 1) 0 else 1 }
                    .thenBy { if (it.light.isStraightLight) 0 else 1 }
                    .thenBy { it.distanceM },
            )
            .first()
        val timestampMs = localTrafficLightTimestampMs(
            rawStamp = chosen.light.timestamp,
            clockSkewMs = lightClockSkewMs,
            localNowMs = clock(),
        )
        return TrafficLightInfo(
            distanceM = chosen.distanceM,
            timestampMs = timestampMs,
            phases = chosen.phases,
            directions = listOf(
                DirectionalTrafficLight(
                    flow = TrafficLightFlow.fromRoadFlow(
                        chosen.light.roadFlowDirection ?: chosen.light.simpleEightDirection,
                    ),
                    phases = chosen.phases,
                ),
            ),
        )
    }

    private fun currentLanesLocked(position: HorizonPosition): List<Lane> {
        if (laneArrows.isEmpty()) return emptyList()
        val samePath = laneArrows.filter { it.pathId == position.pathId }
        // Fall back to all arrows when path ids disagree, so present lane data is
        // not silently dropped by a path-id mismatch.
        val pool = samePath.ifEmpty { laneArrows }
        val ahead = pool.filter { it.offset >= position.offset - 5.0 }
        val arrow = ahead.minByOrNull { it.offset - position.offset }
            ?: pool.minByOrNull { abs(it.offset - position.offset) }
            ?: return emptyList()
        return HorizonProtocol.laneList(arrow)
    }

    private fun cameraKey(camera: HorizonCamera): String {
        val loc = camera.loc?.joinToString(",") ?: ""
        return "${camera.type}:${camera.linkid}:${camera.offset}:$loc"
    }

    private companion object {
        const val TAG = "VeloraHorizon"
        const val LIGHT_ALERT_RANGE_M = 300.0
        const val LIGHT_SAME_INTERSECTION_M = 8.0
    }
}

private data class TrafficLightCandidate(
    val distanceM: Double,
    val light: HorizonTrafficLight,
    val phases: List<TrafficLightPhase>,
)

private val HorizonTrafficLight.isStraightLight: Boolean
    get() = roadFlowDirection == "11" || simpleEightDirection == "11"

/**
 * Keeps consecutive timed phases. An unrecognized lamp with a valid period is
 * kept as UNKNOWN so later phases stay anchored; the UI hides UNKNOWN. A phase
 * with no confidence period/countdown ends the list because later phases can no
 * longer be timed.
 */
internal fun horizonTrafficLightPhases(statuses: List<HorizonTrafficLightStatus>?): List<TrafficLightPhase>? {
    val phases = ArrayList<TrafficLightPhase>()
    for (status in statuses.orEmpty()) {
        val period = status.period?.takeIf { it in 1..600 }
            ?: status.countDown.takeIf { it in 1..600 }
            ?: break
        phases += TrafficLightPhase(
            state = TrafficLightState.fromLampStatus(status.lampStatus),
            countdownSec = status.countDown.coerceAtLeast(0),
            periodSec = period,
        )
    }
    if (phases.isEmpty()) {
        // Fallback: a single usable current phase when the timeline is truncated
        // (unknown lamp / missing period) but a color+countdown is present.
        val first = statuses.orEmpty().firstOrNull {
            TrafficLightState.fromLampStatus(it.lampStatus) != TrafficLightState.UNKNOWN && it.countDown > 0
        }
        if (first != null) {
            phases += TrafficLightPhase(
                state = TrafficLightState.fromLampStatus(first.lampStatus),
                countdownSec = first.countDown,
                periodSec = first.countDown,
            )
        }
    }
    return phases.takeIf { it.isNotEmpty() }
}

/**
 * Converts Baidu's server timestamp (seconds or milliseconds) into the phone's
 * clock domain. [clockSkewMs] is serverTime - localTime measured on receipt.
 */
internal fun localTrafficLightTimestampMs(
    rawStamp: Long,
    clockSkewMs: Long,
    localNowMs: Long,
): Long {
    if (rawStamp <= 0L) return localNowMs
    val rawMs = if (rawStamp < 100_000_000_000L) rawStamp * 1000L else rawStamp
    return if (clockSkewMs != 0L) rawMs - clockSkewMs else rawMs
}
