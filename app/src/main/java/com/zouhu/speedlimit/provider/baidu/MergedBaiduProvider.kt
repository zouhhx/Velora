package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import com.zouhu.speedlimit.domain.model.RoadData
import com.zouhu.speedlimit.provider.SpeedLimitProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

object RoadDataMerger {
    fun merge(horizon: RoadData, http: RoadData): RoadData {
        val horizonUsable = horizon.isValid
        val httpUsable = http.isValid
        // Only a valid source may contribute an object identity or an enforcement
        // value. A stale/invalid snapshot must not lend its speed limit, cameras or
        // newer timestamp to a freshly valid one from the other source.
        val horizonLimit = horizon.takeIf { horizonUsable }?.speedLimit
        val httpLimit = http.takeIf { httpUsable }?.speedLimit
        val hasHorizonSpeedCameras = horizonUsable &&
            horizon.cameras.any { it.type.isSpeedEnforcement }
        val cameraSource = when {
            hasHorizonSpeedCameras -> horizon
            httpUsable -> http
            else -> null
        }
        val horizonInterval = horizon.takeIf {
            horizonUsable && it.interval.phase != IntervalPhase.NONE
        }
        val intervalSource = horizonInterval ?: http.takeIf { httpUsable }
        val laneSource = horizon.takeIf { horizonUsable && it.lanes.isNotEmpty() }
            ?: http.takeIf { httpUsable }
        val lightSource = horizon.takeIf { horizonUsable && it.trafficLight != null }
            ?: http.takeIf { httpUsable && it.trafficLight != null }
        val identitySource = horizon.takeIf { horizonUsable } ?: http.takeIf { httpUsable }
        val timestampMs = when {
            horizonUsable && httpUsable -> maxOf(horizon.timestampMs, http.timestampMs)
            horizonUsable -> horizon.timestampMs
            httpUsable -> http.timestampMs
            else -> maxOf(horizon.timestampMs, http.timestampMs)
        }
        val confidence = when {
            horizonUsable && httpUsable -> maxOf(horizon.confidence, http.confidence)
            horizonUsable -> horizon.confidence
            httpUsable -> http.confidence
            else -> maxOf(horizon.confidence, http.confidence)
        }
        return RoadData(
            roadId = identitySource?.roadId,
            pathId = identitySource?.pathId,
            roadName = identitySource?.roadName,
            // Road and camera limits are separate objects. HTTP currently has no
            // current-link limit, so this only falls back if a future source supplies a
            // real road limit, never a camera point limit.
            speedLimit = horizonLimit ?: httpLimit,
            speedLimitType = if (horizonLimit != null) {
                horizon.speedLimitType
            } else {
                httpLimit?.let { http.speedLimitType }
            },
            cameraSpeedLimit = cameraSource?.cameraSpeedLimit,
            cameras = cameraSource?.cameras ?: emptyList(),
            lanes = laneSource?.lanes ?: emptyList(),
            trafficLight = lightSource?.trafficLight,
            cameraAhead = cameraSource?.cameraAhead ?: false,
            cameraType = cameraSource?.cameraType ?: CameraType.OTHER,
            distanceToCameraM = cameraSource?.distanceToCameraM ?: 0.0,
            interval = intervalSource?.interval ?: IntervalSnapshot(),
            intervalSpeedLimit = intervalSource?.intervalSpeedLimit,
            confidence = confidence,
            dataVersion = identitySource?.dataVersion,
            timestampMs = timestampMs,
            isValid = horizonUsable || httpUsable,
        )
    }

    fun connectionStatus(a: ConnectionStatus, b: ConnectionStatus): ConnectionStatus = when {
        a == ConnectionStatus.ONLINE || b == ConnectionStatus.ONLINE -> ConnectionStatus.ONLINE
        a == ConnectionStatus.CONNECTING || b == ConnectionStatus.CONNECTING -> ConnectionStatus.CONNECTING
        else -> ConnectionStatus.OFFLINE
    }
}

class MergedBaiduProvider(
    private val http: SpeedLimitProvider,
    private val horizon: SpeedLimitProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SpeedLimitProvider {
    private val road = MutableStateFlow(RoadData())
    private val status = MutableStateFlow(ConnectionStatus.OFFLINE)

    /** Permanent: once closed the merge collectors are cancelled and never restarted. */
    @Volatile
    private var closed = false
    override val connectionStatus: StateFlow<ConnectionStatus> = status
    override fun observeRoadData(): StateFlow<RoadData> = road

    init {
        scope.launch {
            combine(http.observeRoadData(), horizon.observeRoadData()) { h, w ->
                RoadDataMerger.merge(horizon = w, http = h)
            }.collect { road.value = it }
        }
        scope.launch {
            combine(http.connectionStatus, horizon.connectionStatus) { a, b ->
                RoadDataMerger.connectionStatus(a, b)
            }.collect { status.value = it }
        }
    }

    override fun connect() {
        if (closed) return
        http.connect()
        horizon.connect()
    }

    override fun disconnect() {
        http.disconnect()
        horizon.disconnect()
    }

    override fun startCruise() {
        if (closed) return
        http.startCruise()
        horizon.startCruise()
    }

    override fun stopCruise() {
        http.stopCruise()
        horizon.stopCruise()
    }

    override fun updateLocation(sample: GpsSample) {
        if (closed) return
        http.updateLocation(sample)
        horizon.updateLocation(sample)
    }

    /**
     * Called when AppGraph replaces this provider, not when the network drops. The old
     * code only disconnected the children; the two `combine` collectors kept the merged
     * object (and both providers) alive for every AK switch (VA-P04).
     */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { stopCruise() }
        runCatching { disconnect() }
        scope.cancel()
    }
}
