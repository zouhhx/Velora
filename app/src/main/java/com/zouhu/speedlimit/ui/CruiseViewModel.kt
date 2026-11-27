package com.zouhu.speedlimit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zouhu.speedlimit.data.audio.BeepPlayer
import com.zouhu.speedlimit.data.location.LocationStatus
import com.zouhu.speedlimit.data.location.LocationTier
import com.zouhu.speedlimit.data.service.ServiceStatus
import com.zouhu.speedlimit.data.service.ServiceStatusStore
import com.zouhu.speedlimit.data.settings.AppSettings
import com.zouhu.speedlimit.di.AppGraph
import com.zouhu.speedlimit.domain.engine.DistanceFormat
import com.zouhu.speedlimit.domain.engine.IntervalEngine
import com.zouhu.speedlimit.domain.engine.OverspeedDetector
import com.zouhu.speedlimit.domain.engine.SpeedFilter
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import com.zouhu.speedlimit.domain.model.Lane
import com.zouhu.speedlimit.domain.model.OverSpeedState
import com.zouhu.speedlimit.domain.model.RoadData
import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Show the camera capsule / enable over-speed enforcement this far ahead. */
private const val CAMERA_ALERT_RANGE_M = 800.0

data class CruiseUiState(
    val speedKmh: Int = 0,
    val limit: Int? = null,
    val lanes: List<Lane> = emptyList(),
    val overSpeed: OverSpeedState = OverSpeedState.NORMAL,
    val cameraAhead: Boolean = false,
    val cameraType: CameraType = CameraType.OTHER,
    val cameraDistText: String = "",
    val cameraLabel: String = "",
    /** Limit of the selected camera, not the current road limit. */
    val cameraLimit: Int? = null,
    val trafficLight: TrafficLightInfo? = null,
    val interval: IntervalSnapshot = IntervalSnapshot(),
    val intervalAvg: OverSpeedState = OverSpeedState.NORMAL,
    /** False when too much of the section had no speed sample (VA-I03/Q-24). */
    val intervalAvgVisible: Boolean = false,
    val thresholdPct: Int = 10,
    val soundEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val dayNightMode: String = "day",
    /** Any usable position: the map may show. */
    val hasPosition: Boolean = false,
    /** Current link-accurate position: limit / lanes / camera / light may show. */
    val showCruiseData: Boolean = false,
    /** Live satellite speed: the gauge may render. */
    val showSpeed: Boolean = false,
    val locationTier: LocationTier = LocationTier.NONE,
    /** Horizontal accuracy in metres, 0 when unknown. Sizes the map follow deadband. */
    val locationAccuracyM: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val directAk: String = "",
    /** Optional server SK; never displayed raw. */
    val serverSk: String = "",
    /** TTS key for the masked Settings row. */
    val mimoKey: String = "",
    /** Map/navi/search AK; static-image fallback must not borrow the server AK. */
    val mapAk: String = "",
    /** Optional Baidu services health for a gentle Settings status. */
    val serviceStatus: ServiceStatus = ServiceStatus(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CruiseViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsRepo = AppGraph.settingsRepository(app)
    private val locationRepo = AppGraph.locationRepository(app)
    private val _state = MutableStateFlow(CruiseUiState())
    val state: StateFlow<CruiseUiState> = _state
    private val beepPlayer = BeepPlayer()
    private var lastOverSpeed = OverSpeedState.NORMAL
    private val _navActive = MutableStateFlow(false)

    /** Navigation arbitration: while nav is active, cruise capsules/beep are suppressed. */
    fun setNavActive(v: Boolean) { _navActive.value = v }

    init {
        viewModelScope.launch {
            val roadFlow = AppGraph.providerFlow.flatMapLatest { it.observeRoadData() }
            val settingsAndService = combine(
                settingsRepo.settings,
                ServiceStatusStore.state,
            ) { settings, service -> settings to service }
            combine(
                roadFlow,
                locationRepo.latest,
                locationRepo.status,
                settingsAndService,
                _navActive,
            ) { road, gps, status, settingsAndStatus, navActive ->
                build(
                    road,
                    gps,
                    status,
                    settingsAndStatus.first,
                    navActive,
                    settingsAndStatus.second,
                )
            }
                .flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .collect { next ->
                    val enteringSevere = next.overSpeed == OverSpeedState.SEVERE &&
                        lastOverSpeed != OverSpeedState.SEVERE
                    lastOverSpeed = next.overSpeed
                    _state.value = next
                    if (enteringSevere && next.soundEnabled && !_navActive.value) beepPlayer.beep()
                }
        }
        AppGraph.attachUi(app)
    }

    private fun build(
        road: RoadData,
        gps: GpsSample,
        status: LocationStatus,
        settings: AppSettings,
        navActive: Boolean,
        serviceStatus: ServiceStatus,
    ): CruiseUiState {
        // Road data is withheld unless the position is current and link-accurate: a 500 m
        // network fix would otherwise produce a confident speed limit for the wrong road.
        val roadUsable = status.cruiseData && !navActive
        val speed = if (status.speed) SpeedFilter.filter(gps.speedKmh) else 0.0
        val limit = if (status.cruiseData) road.speedLimit else null
        val th = settings.thresholdPct
        val intervalPhase = if (status.cruiseData) road.interval.phase else IntervalPhase.NONE
        // The interval section is its own state machine. Do not let the nearest camera
        // type decide whether the section UI exists: a normal speed camera can be
        // closer than the interval endpoint and used to hide the average circle.
        val inside = intervalPhase == IntervalPhase.INSIDE
        val approaching = intervalPhase == IntervalPhase.APPROACH
        // Only surface a camera when it is actually close. Inside an interval section
        // the section UI stays visible even if the next camera is farther away.
        val cameraNear = status.cruiseData && road.cameraAhead &&
            road.distanceToCameraM <= CAMERA_ALERT_RANGE_M
        val cameraAhead = (cameraNear || inside) && roadUsable
        val cameraLimit = if (cameraAhead) road.cameraSpeedLimit else null
        val over = OverspeedDetector.classify(speed, limit, cameraAhead, intervalPhase, th)
        val label: String
        val distText: String
        when {
            inside -> { label = "区间测速结束"; distText = DistanceFormat.format(road.interval.remainingM) }
            !cameraAhead -> { label = ""; distText = "" }
            approaching -> { label = "进入区间测速"; distText = DistanceFormat.format(road.interval.distanceToStartM) }
            else -> { label = "测速拍照"; distText = DistanceFormat.format(road.distanceToCameraM) }
        }
        // The interval average uses the interval section's own enforcement limit,
        // not the current road limit and not a nearby point camera's limit.
        val intervalLimit = if (intervalPhase != IntervalPhase.NONE) road.intervalSpeedLimit else null
        val avg = IntervalEngine.averageState(road.interval.avgKmh, intervalLimit, th)
        val avgVisible = inside && IntervalEngine.hasReliableAverage(road.interval)
        return CruiseUiState(
            speedKmh = speed.roundToInt(),
            limit = limit,
            lanes = if (roadUsable) road.lanes else emptyList(),
            overSpeed = over,
            cameraAhead = cameraAhead,
            cameraType = road.cameraType,
            cameraDistText = distText,
            cameraLabel = label,
            cameraLimit = cameraLimit,
            trafficLight = if (roadUsable) road.trafficLight else null,
            interval = if (status.cruiseData) road.interval else IntervalSnapshot(),
            intervalAvg = avg,
            intervalAvgVisible = avgVisible,
            thresholdPct = th,
            soundEnabled = settings.soundEnabled,
            ttsEnabled = settings.ttsEnabled,
            keepScreenOn = settings.keepScreenOn,
            dayNightMode = settings.dayNightMode,
            hasPosition = status.hasPosition,
            showCruiseData = status.cruiseData,
            showSpeed = status.speed,
            locationTier = status.tier,
            locationAccuracyM = status.accuracyM ?: 0f,
            latitude = gps.latitude,
            longitude = gps.longitude,
            directAk = settings.directAk,
            serverSk = settings.serverSk,
            mimoKey = settings.mimoKey,
            mapAk = settings.mapAk,
            serviceStatus = serviceStatus,
        )
    }

    fun setThreshold(v: Int) { viewModelScope.launch { settingsRepo.setThreshold(v) } }
    fun setSoundEnabled(v: Boolean) { viewModelScope.launch { settingsRepo.setSoundEnabled(v) } }
    fun setTtsEnabled(v: Boolean) { viewModelScope.launch { settingsRepo.setTtsEnabled(v) } }
    fun setKeepScreenOn(v: Boolean) { viewModelScope.launch { settingsRepo.setKeepScreenOn(v) } }
    fun setDayNightMode(v: String) { viewModelScope.launch { settingsRepo.setDayNightMode(v) } }
    fun setDirectAk(v: String) { viewModelScope.launch { settingsRepo.setDirectAk(v) } }
    fun setServerSk(v: String) { viewModelScope.launch { settingsRepo.setServerSk(v) } }
    fun setMimoKey(v: String) { viewModelScope.launch { settingsRepo.setMimoKey(v) } }
    fun setMapAk(v: String) { viewModelScope.launch { settingsRepo.setMapAk(v) } }

    fun resetDirectAk() { viewModelScope.launch { settingsRepo.resetDirectAk() } }
    fun resetServerSk() { viewModelScope.launch { settingsRepo.resetServerSk() } }
    fun resetMimoKey() { viewModelScope.launch { settingsRepo.resetMimoKey() } }
    fun resetMapAk() { viewModelScope.launch { settingsRepo.resetMapAk() } }

    override fun onCleared() {
        beepPlayer.release()
        AppGraph.detachUi()
        super.onCleared()
    }
}
