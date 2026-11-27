package com.zouhu.speedlimit.data.nav

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.BNRoutePlanNode
import com.baidu.navisdk.adapter.BNaviCommonParams
import com.baidu.navisdk.adapter.IBaiduNaviManager
import com.baidu.navisdk.adapter.IBNRouteGuideManager
import com.baidu.navisdk.adapter.IBNRoutePlanManager
import com.baidu.navisdk.adapter.IBNaviListener
import com.baidu.navisdk.adapter.struct.BNCameraInfo
import com.baidu.navisdk.adapter.struct.BNGuideConfig
import com.baidu.navisdk.adapter.struct.BNTrafficLight
import com.baidu.navisdk.adapter.struct.BNaviInfo
import com.baidu.navisdk.adapter.struct.BNaviInitConfig
import com.baidu.navisdk.adapter.struct.BNavLineItem
import com.baidu.navisdk.adapter.struct.GuidePanelMessage
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.NavCamera
import com.zouhu.speedlimit.domain.model.NavEta
import com.zouhu.speedlimit.domain.model.NavManeuver
import com.zouhu.speedlimit.domain.model.NavPoint
import com.zouhu.speedlimit.domain.model.NavState
import com.zouhu.speedlimit.domain.model.NavTrafficLight
import com.zouhu.speedlimit.domain.model.NavTurn
import com.zouhu.speedlimit.domain.model.NavUiState
import com.zouhu.speedlimit.domain.model.TrafficLightState
import com.zouhu.speedlimit.data.tts.NavSpeaker
import com.zouhu.speedlimit.data.tts.navVoiceCue
import com.zouhu.speedlimit.data.settings.SecretRedaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real navigation bridge (Phase 2). Implements the frontend [NavSource] contract on
 * top of Baidu Map-Navi, option B (ADD_MAP=false): no Baidu map is created, our own
 * TextureMapView stays visible and the Velora HUD renders [state].
 *
 * G0 mitigations baked in: lanes via [LaneDrawableMapper], speed/limit via
 * [NavSentinels], per-light countdown only when the SDK actually delivers it.
 */
class BaiduNavSource : NavSource {
    private val _state = MutableStateFlow(NavUiState())
    override val state: StateFlow<NavUiState> = _state.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _status = MutableStateFlow("导航未初始化")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _routeTabs = MutableStateFlow<List<RouteTab>>(emptyList())
    val routeTabs: StateFlow<List<RouteTab>> = _routeTabs.asStateFlow()

    private val _selectedRouteIndex = MutableStateFlow(0)
    val selectedRouteIndex: StateFlow<Int> = _selectedRouteIndex.asStateFlow()

    /** Guide view is hosted behind our map so its hidden HUD never steals map gestures. */
    private val _guideView = MutableStateFlow<View?>(null)
    val guideView: StateFlow<View?> = _guideView.asStateFlow()

    /**
     * Incremented whenever navigation ends and the shared native map engine is handed
     * back to cruise. The cruise map re-applies its own camera/style on every bump.
     */
    private val _sceneRevision = MutableStateFlow(0)
    val sceneRevision: StateFlow<Int> = _sceneRevision.asStateFlow()

    /** User preference: "day" | "night" | "system" (never clock based). */
    @Volatile
    private var dayNightMode: String = MapSceneController.MODE_DAY

    fun setDayNightMode(mode: String) {
        if (!MapSceneController.isValidMode(mode)) return
        dayNightMode = mode
        val a = activity ?: return
        if (guideActive) MapSceneController.applyNaviDayNight(a, mode)
    }

    /**
     * "总览" toggle. The navi engine re-fits the camera to the whole route while it is in
     * full-view mode, which is why a manual zoom used to snap straight back; leaving that
     * mode is the only way to make the driving view stick.
     */
    fun setFullView(on: Boolean) {
        val manager = guideManager
        NavTrace.log("GUIDE", "fullView=$on manager=${manager != null}")
        if (manager == null) return
        runCatching { manager.fullView(on) }
            .onFailure { NavTrace.log("GUIDE", "fullView threw ${it.javaClass.simpleName}") }
        update { it.copy(fullView = on) }
        if (on) {
            // The user asked for the overview: let the engine fit the whole route.
            MapSceneController.setOverviewAutoZoomInterrupted(false)
        } else {
            // Leaving the overview must land on the driving zoom, not the SDK's wide
            // default — and must stay there. The task belongs to this guide session;
            // a stop/start inside 250 ms must not let it pin a newer guide (VA-N04).
            val session = guideSession
            mainHandler.postDelayed({
                if (session == guideSession && guideActive) MapSceneController.pinNaviView()
            }, 250L)
        }
    }

    data class RouteTab(val label: String, val lengthM: Int, val timeSec: Int, val lights: Int)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** VA-N05: every plan response must match the newest request before writing state. */
    private val planOwner = PlanRequestOwner()
    private var planHandler: Handler? = null
    private var captureRetry: Runnable? = null

    /**
     * Voice is driven by the nav source, not by Compose. `collectAsStateWithLifecycle`
     * stops at ON_STOP, which used to silence instructions once the app was backgrounded
     * or the phone locked (VA-F03).
     */
    private val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var voiceJob: Job? = null

    @Volatile
    private var speaker: NavSpeaker? = null

    private var activity: Activity? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var lifecycleObserver: LifecycleEventObserver? = null

    @Volatile
    private var guideManager: IBNRouteGuideManager? = null
    private var naviInited = false
    private var initStarted = false
    private var pendingPlan: (() -> Unit)? = null
    private var pendingPlanResult: ((Boolean) -> Unit)? = null
    private var routePaths: List<List<NavPoint>> = emptyList()
    private var routeReady = false

    // SDK callbacks arrive on their own threads; these flags/fields are read there.
    @Volatile
    private var guideActive = false

    /** Invalidates delayed guide commands from a previous session (VA-N04). */
    @Volatile
    private var guideSession = 0
    @Volatile
    private var guideStarted = false
    @Volatile
    private var guideResumed = false
    @Volatile
    private var currentRoad: String = ""
    @Volatile
    private var remainLights: Int = 0
    @Volatile
    private var lastSpeed = Int.MIN_VALUE
    @Volatile
    private var lastLimit = Int.MIN_VALUE
    private var heartbeat: Runnable? = null
    private var stopping = false

    /**
     * Attach the speech sink once per screen. [enabled] is evaluated per instruction so a
     * settings change does not need to re-attach.
     */
    fun attachVoice(speaker: NavSpeaker, enabled: () -> Boolean) {
        this.speaker = speaker
        voiceJob?.cancel()
        voiceJob = voiceScope.launch {
            _state.map { navVoiceCue(it) }
                .distinctUntilChanged()
                .collectLatest { cue ->
                    if (cue == null) return@collectLatest
                    // A new instruction supersedes the previous one; do not talk over it.
                    runCatching { speaker.stop() }
                    runCatching { speaker.speak(cue.text, enabled()) }
                }
        }
    }

    fun detachVoice() {
        voiceJob?.cancel()
        voiceJob = null
        runCatching { speaker?.stop() }
        speaker = null
    }

    fun attach(a: Activity) {
        NavTrace.init(a.applicationContext)
        NavTrace.log("ATTACH", "activity=${a.javaClass.simpleName}")
        if (activity !== a) {
            activity = a
            (a as? LifecycleOwner)?.let { observeLifecycle(it) }
        }
        ensureInit(a)
    }

    /**
     * Drop every host-owned reference (VA-N07). ON_DESTROY used to call stop() only, so
     * the observer, the Activity and any queued plan stayed reachable from this
     * process-scoped source. The navi SDK init itself is process-level and is kept.
     */
    fun detach() {
        NavTrace.log("DETACH", "activity=${activity?.javaClass?.simpleName}")
        if (guideActive || guideManager != null) stop()
        lifecycleObserver?.let { observer ->
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
        lifecycleObserver = null
        lifecycleOwner = null
        activity = null
        detachVoice()
        cancelPlan("detach")
        stopHeartbeat()
        _guideView.value = null
        _active.value = false
    }

    private fun observeLifecycle(owner: LifecycleOwner) {
        if (lifecycleOwner === owner) return
        lifecycleObserver?.let { lifecycleOwner?.lifecycle?.removeObserver(it) }
        val observer = LifecycleEventObserver { _, event ->
            NavTrace.log("LIFE", event.name)
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (guideStarted && guideResumed) {
                        runCatching { guideManager?.onPause() }
                            .onFailure { Log.w(TAG, "guide onPause failed: ${it.message}") }
                        guideResumed = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (guideStarted && !guideResumed) {
                        runCatching { guideManager?.onResume() }
                            .onFailure { Log.w(TAG, "guide onResume failed: ${it.message}") }
                        guideResumed = true
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    stop()
                    detach()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        lifecycleOwner = owner
        lifecycleObserver = observer
    }

    private fun ensureInit(a: Activity) {
        if (initStarted) return
        initStarted = true
        val naviRoot = java.io.File(a.getExternalFilesDir(null) ?: a.filesDir, "BaiduNaviSDK").apply { mkdirs() }
        val listener = object : IBaiduNaviManager.INaviInitListener {
            override fun onAuthResult(status: Int, msg: String?) {
                val safeMsg = redactAuthMessage(msg)
                Log.i(TAG, "navi auth status=$status msg=$safeMsg")
                NavTrace.log("AUTH", "status=$status msg=$safeMsg")
                _status.value = if (status == 0) "导航授权OK" else "导航授权失败 status=$status"
            }

            override fun initStart() = Unit

            override fun initSuccess() {
                // The SDK installs its crash handler during init; take the slot back
                // before any later SDK callback can throw.
                NavTrace.arm("navi-init-success")
                naviInited = true
                runCatching { BaiduNaviManagerFactory.getCommonSettingManager().setMultiRouteEnable(true) }
                Log.i(TAG, "navi initSuccess")
                NavTrace.log("INIT", "success")
                _status.value = "导航就绪"
                pendingPlan?.invoke()
                pendingPlan = null
                pendingPlanResult = null
            }

            override fun initFailed(errorCode: Int) {
                resetFailedInit("initFailed code=$errorCode")
                _status.value = "导航初始化失败 code=$errorCode"
            }
        }
        runCatching {
            val config = BNaviInitConfig.Builder()
                .sdcardRootPath(naviRoot.absolutePath)
                .naviInitListener(listener)
                .build()
            BaiduNaviManagerFactory.getBaiduNaviManager().enableOutLog(true)
            BaiduNaviManagerFactory.getBaiduNaviManager().init(a.applicationContext, config)
        }.onFailure {
            resetFailedInit("init throw ${it.javaClass.simpleName}: ${it.message}")
            _status.value = "导航初始化异常: ${it.javaClass.simpleName}"
            Log.w(TAG, "navi init throw: ${it.message}")
        }
    }

    /**
     * A failed init is a terminal state for this attempt, not for the source. Allow the
     * next explicit attach/plan to retry, and tell the queued plan it did not succeed.
     * This deliberately does not auto-retry forever.
     */
    private fun resetFailedInit(reason: String) {
        initStarted = false
        naviInited = false
        pendingPlan = null
        pendingPlanResult?.invoke(false)
        pendingPlanResult = null
        Log.w(TAG, "navi $reason")
        NavTrace.log("INIT", "failed $reason; retry allowed")
    }

    fun plan(start: NavPoint, end: NavPoint, endUid: String?, onResult: (Boolean) -> Unit) {
        NavTrace.log("ROUTE", "plan request naviInited=$naviInited uid=${!endUid.isNullOrBlank()}")
        // A new destination supersedes the old request: drop its handler and its queued
        // callback instead of letting a late SUCCESS overwrite the new route.
        cancelPlan("new-request")
        val generation = planOwner.begin()
        // GPS start is WGS84; Baidu route plan expects bd09. End (POI) is already bd09.
        val (sLat, sLng) = com.zouhu.speedlimit.data.map.baidu.CoordUtil.wgs84ToBd09(start.lat, start.lng)
        val s = BNRoutePlanNode.Builder().name("起点").latitude(sLat).longitude(sLng).build()
        val eb = BNRoutePlanNode.Builder().name("终点").latitude(end.lat).longitude(end.lng)
        if (!endUid.isNullOrBlank()) eb.id(endUid)
        routeReady = false
        _routeTabs.value = emptyList()
        routePaths = emptyList()
        _selectedRouteIndex.value = 0
        _state.value = _state.value.copy(route = emptyList())
        _status.value = "算路中…"
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (!planOwner.isCurrent(generation)) {
                    NavTrace.log("ROUTE", "drop stale plan message what=${msg.what}")
                    return
                }
                when (msg.what) {
                    IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_SUCCESS -> {
                        NavTrace.log("ROUTE", "SUCCESS")
                        if (captureRoute()) {
                            _status.value =
                                "算路成功 ${routePaths.firstOrNull()?.size ?: 0}点 · ${_routeTabs.value.size}条"
                        } else {
                            _status.value = "算路成功，等待路线数据…"
                            scheduleCapture(generation)
                        }
                        routeReady = true
                        onResult(true)
                    }

                    IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_TO_NAVI -> {
                        NavTrace.log("ROUTE", "TO_NAVI (observed, not a hard gate)")
                        // Route geometry sometimes becomes complete only at TO_NAVI.
                        captureRoute()
                        routeReady = true
                    }

                    IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_FAILED,
                    IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_CANCELED,
                    -> {
                        NavTrace.log("ROUTE", "FAILED/CANCELED what=${msg.what}")
                        _status.value = "算路失败 msg=${msg.what}"
                        routeReady = false
                        planHandler = null
                        cancelCaptureRetry()
                        onResult(false)
                    }
                }
            }
        }
        planHandler = handler
        val doPlan = {
            if (!planOwner.isCurrent(generation)) {
                NavTrace.log("ROUTE", "skip stale queued plan")
            } else {
                val ok = runCatching {
                    BaiduNaviManagerFactory.getRoutePlanManager()
                        .routePlan(
                            listOf(s, eb.build()),
                            IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DEFAULT,
                            Bundle(),
                            handler,
                        )
                }.onFailure { Log.w(TAG, "routePlan throw: ${it.message}") }.getOrDefault(false)
                if (!ok) {
                    _status.value = "算路请求失败"
                    planHandler = null
                    onResult(false)
                }
            }
        }
        // routePlan only works after navi initSuccess; queue until then.
        if (naviInited) {
            doPlan()
        } else {
            // A newer request already cleared the previous queued plan in cancelPlan();
            // dropping its callback is correct (superseded is not a failure).
            pendingPlan = doPlan
            pendingPlanResult = onResult
        }
    }

    /**
     * Drop the in-flight plan handler and its 700 ms retry. The callback is intentionally
     * not invoked: this request was superseded or cancelled, not failed.
     */
    private fun cancelPlan(reason: String) {
        planOwner.cancel()
        planHandler?.let { handler ->
            runCatching {
                BaiduNaviManagerFactory.getRoutePlanManager().removeRequestByHandler(handler)
            }.onFailure {
                NavTrace.log("ROUTE", "removeRequestByHandler threw ${it.javaClass.simpleName}")
            }
        }
        planHandler = null
        cancelCaptureRetry()
        pendingPlan = null
        pendingPlanResult = null
        NavTrace.log("ROUTE", "plan cancelled: $reason")
    }

    private fun scheduleCapture(generation: Int) {
        cancelCaptureRetry()
        val retry = Runnable {
            if (planOwner.isCurrent(generation)) captureRoute()
        }
        captureRetry = retry
        mainHandler.postDelayed(retry, 700)
    }

    private fun cancelCaptureRetry() {
        captureRetry?.let { mainHandler.removeCallbacks(it) }
        captureRetry = null
    }

    /**
     * Route tabs are real choices, not decoration. Selection updates the preview
     * immediately and asks the navi engine to switch its active route.
     */
    /**
     * Dismissing the search preview must also drop the previewed route from the shared
     * map (VA-M03). This is not a navigation stop: it refuses to touch an active guide.
     * Any in-flight plan is cancelled so a late SUCCESS cannot restore the line.
     */
    fun clearPreviewRoute() {
        if (guideActive || guideManager != null) return
        NavTrace.log("ROUTE", "clear preview route")
        cancelPlan("preview-cancelled")
        routeReady = false
        routePaths = emptyList()
        _routeTabs.value = emptyList()
        _selectedRouteIndex.value = 0
        _state.value = NavUiState()
        _status.value = ""
    }

    fun selectRoute(index: Int) {
        if (index !in _routeTabs.value.indices) return
        NavTrace.log("ROUTE", "selectRoute index=$index tabs=${_routeTabs.value.size} guideActive=$guideActive")
        _selectedRouteIndex.value = index
        routePaths.getOrNull(index)?.let { points ->
            _state.value = _state.value.copy(route = points)
        }
        _status.value = "已选择路线 ${index + 1}"
        if (guideActive) {
            runCatching { guideManager?.selectRoute(index) }
                .onFailure { Log.w(TAG, "guide selectRoute failed: ${it.message}") }
            // Selecting a tab must also drop the other candidate layers, not just swap
            // the app-drawn polyline.
            applyNaviDisplaySettings(true)
            return
        }
        val listener = object : IBNRoutePlanManager.SelectRouteListener {
            override fun onSelectComplete() {
                mainHandler.post {
                    // A later tap may already have selected another tab; never let the
                    // slower callback overwrite the newer choice (VA-N05).
                    if (_selectedRouteIndex.value != index) {
                        NavTrace.log("ROUTE", "drop stale select callback index=$index")
                        return@post
                    }
                    routePaths.getOrNull(index)?.let { points ->
                        _state.value = _state.value.copy(route = points)
                    }
                    _status.value = "已选择路线 ${index + 1}"
                }
            }
        }
        runCatching {
            BaiduNaviManagerFactory.getRoutePlanManager().selectRoute(index, listener)
        }.onFailure {
            Log.w(TAG, "routePlan selectRoute failed: ${it.message}")
            _status.value = "切换路线失败"
        }
    }

    private fun captureRoute(): Boolean {
        val info = runCatching { BaiduNaviManagerFactory.getRoutePlanManager().getRoutePlanInfo() }
            .getOrNull() ?: return false
        val paths = info.routeInfoLatLngLists.orEmpty()
            .map { path -> path.orEmpty().flatten().map { NavPoint(it.latitude, it.longitude) } }
            .filter { it.size >= 2 }
        if (paths.isNotEmpty()) routePaths = paths
        val tabs = info.routeTabInfos.orEmpty().mapIndexed { index, tab ->
            RouteTab(
                label = tab.pusLabelName?.takeIf { it.isNotBlank() } ?: "路线 ${index + 1}",
                lengthM = tab.length.toInt(),
                timeSec = tab.passTime.toInt(),
                lights = tab.lights,
            )
        }
        if (tabs.isNotEmpty()) _routeTabs.value = tabs
        NavTrace.log(
            "ROUTE",
            "capture paths=${routePaths.map { it.size }} tabs=${tabs.map { "${it.label}:${it.lengthM}m/${it.lights}灯" }}",
        )
        val index = _selectedRouteIndex.value.coerceIn(0, (routePaths.size - 1).coerceAtLeast(0))
        val points = routePaths.getOrNull(index).orEmpty()
        if (points.isNotEmpty()) {
            _state.value = _state.value.copy(route = points)
            return true
        }
        return false
    }

    /**
     * Roll back the shared scene/display settings after a start failure. This does not
     * blindly call a full guide lifecycle on a partially created manager: the SDK's
     * onCreate failure semantics are not proven, so we only restore the global state
     * that this class changed and drop the App-owned view/flags.
     */
    private fun failGuideStart(reason: String): Boolean {
        stopHeartbeat()
        applyNaviDisplaySettings(false)
        MapSceneController.restoreCruiseScene("start-failed")
        guideManager = null
        guideActive = false
        guideStarted = false
        guideResumed = false
        _guideView.value = null
        _active.value = false
        NavTrace.log("GUIDE", "start failed rollback reason=$reason")
        return false
    }

    fun startGuide(): Boolean {
        val a = activity
        NavTrace.log(
            "GUIDE",
            "start request naviInited=$naviInited routeReady=$routeReady " +
                "route=${_state.value.route.size} paths=${routePaths.size} tabs=${_routeTabs.value.size} " +
                "selected=${_selectedRouteIndex.value} activity=${a?.javaClass?.simpleName}",
        )
        if (a == null) {
            _status.value = "导航宿主未就绪"
            return false
        }
        if (!naviInited) {
            _status.value = "导航 SDK 未就绪"
            return false
        }
        if (!routeReady || _state.value.route.size < 2) {
            _status.value = "请先完成算路"
            return false
        }
        if (guideActive) stop()
        val manager = runCatching { BaiduNaviManagerFactory.getRouteGuideManager() }.getOrElse {
            _status.value = "导航组件不可用"
            Log.w(TAG, "guide manager unavailable: ${it.message}")
            return false
        }
        val bundle = Bundle().apply {
            putBoolean(BNaviCommonParams.ProGuideKey.ADD_MAP, false) // option B: our map
            putBoolean(BNaviCommonParams.ProGuideKey.IS_REALNAVI, true)
            putBoolean(BNaviCommonParams.ProGuideKey.IS_SUPPORT_FULL_SCREEN, false)
        }
        // add_map was A/B tested on 2026-11-15 19:22: false and true fail identically,
        // so the guide-UI crash was never about who owns the map.
        NavTrace.log("GUIDE", "params addMap=false realNavi=true fullScreen=false")
        try {
            // The navi scene owns the shared map engine; fix the palette before the guide
            // renders its first frame, otherwise it opens on the SDK's clock-based choice.
            MapSceneController.enterNaviScene()
            MapSceneController.applyNaviDayNight(a, dayNightMode)
            // Default is to open guidance in "whole route overview". At a standstill the SDK
            // stays there and re-fits the camera on every manual zoom, which is exactly the
            // "map keeps snapping back to the overview" report. Start in driving view and
            // let the user ask for the overview from the HUD.
            val startByFullView = runCatching {
                BaiduNaviManagerFactory.getProfessionalNaviSettingManager().setStartByFullView(false)
            }.onFailure {
                NavTrace.log("GUIDE", "setStartByFullView threw ${it.javaClass.simpleName}")
            }
            NavTrace.log("GUIDE", "setStartByFullView(false) ok=${startByFullView.isSuccess}")
            applyNaviDisplaySettings(true)
            val view = runCatching {
                NavTrace.arm("guide-pre-oncreate")
                NavTrace.log("GUIDE", "onCreate begin")
                manager.onCreate(a, BNGuideConfig.Builder().params(bundle).build())
            }.onFailure {
                Log.e(TAG, "guide onCreate failed", it)
                NavTrace.log("GUIDE", "onCreate failed ${it.javaClass.simpleName}: ${it.message}")
                _status.value = "导航启动失败: ${it.javaClass.simpleName}"
            }.getOrNull()
            if (view == null) {
                NavTrace.log("GUIDE", "onCreate returned null")
                _status.value = "导航启动失败：无法创建导航视图"
                return failGuideStart("onCreate null")
            }
            // onCreate spawns the navi engine; if it throws on its own thread the SDK
            // handler would exit the process silently. Re-arm immediately.
            NavTrace.arm("guide-post-oncreate")
            NavTrace.log("GUIDE", "onCreate returned ${view.javaClass.simpleName}")
            // Guide creation reconfigures the map scene; re-assert the user's choice.
            MapSceneController.applyNaviDayNight(a, dayNightMode)

            guideManager = manager
            guideSession++
            guideActive = true
            guideStarted = false
            guideResumed = false
            _guideView.value = view
            _active.value = true
            _status.value = "导航启动中…"
            startHeartbeat()
            // Keep the guide view VISIBLE and laid out for the native engine. It is
            // hosted behind the persistent TextureMapView, so its own HUD is covered
            // while callbacks/lifecycle still run.
            view.alpha = 1f
            view.visibility = View.VISIBLE
            view.isClickable = false
            view.isFocusable = false
            Log.i(TAG, "guide view created, attaching visible behind map")
            runCatching {
                manager.setNaviListener(naviListener)
                manager.setTrafficLightListener(trafficLightListener)
            }.onFailure { Log.w(TAG, "guide listener attach failed: ${it.message}") }
            _state.value = _state.value.copy(
                navState = NavState.CRUISE,
                route = routePaths.getOrNull(_selectedRouteIndex.value).orEmpty(),
                arrived = false,
                reroute = false,
            )
            startWhenLaidOut(view, manager, 0)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "guide start failed", t)
            NavTrace.log("GUIDE", "start failed ${t.javaClass.simpleName}: ${t.message}")
            _status.value = "导航启动失败: ${t.javaClass.simpleName}"
            return failGuideStart(t.javaClass.simpleName)
        }
    }

    /**
     * A 2s heartbeat is the only reliable trace for a native crash: the Java
     * uncaught handler never runs, but the last line in nav-trace.log tells us
     * whether the process died before onCreate, after layout, or during onStart.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        val runnable = object : Runnable {
            override fun run() {
                if (!guideActive) return
                val v = _guideView.value
                NavTrace.log(
                    "HEARTBEAT",
                    "started=$guideStarted resumed=$guideResumed " +
                        "attached=${v?.isAttachedToWindow} size=${v?.width}x${v?.height} " +
                        "route=${_state.value.route.size} idx=${_selectedRouteIndex.value} " +
                        "state=${_state.value.navState} speed=${_state.value.speedKmh} " +
                        "limit=${_state.value.speedLimit}",
                )
                mainHandler.postDelayed(this, 2_000L)
            }
        }
        heartbeat = runnable
        mainHandler.postDelayed(runnable, 2_000L)
    }

    private fun stopHeartbeat() {
        heartbeat?.let { mainHandler.removeCallbacks(it) }
        heartbeat = null
    }

    private fun hostLifecycle(): HostLifecycle = when (lifecycleOwner?.lifecycle?.currentState) {
        Lifecycle.State.CREATED -> HostLifecycle.CREATED
        Lifecycle.State.STARTED -> HostLifecycle.STARTED
        Lifecycle.State.RESUMED -> HostLifecycle.RESUMED
        Lifecycle.State.DESTROYED -> HostLifecycle.DESTROYED
        else -> HostLifecycle.PAUSED
    }

    /**
     * Abort before onStart: remove the App-owned view and roll back the scene without
     * calling the native onPause/onStop/onDestroy chain on a host that is not RESUMED.
     */
    private fun abortGuideStart(reason: String, view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        _status.value = "导航启动已取消：$reason"
        failGuideStart(reason)
    }

    private fun startWhenLaidOut(view: View, manager: IBNRouteGuideManager, attempt: Int) {
        mainHandler.postDelayed({
            // A stale runnable from a previous guide must never touch the new one.
            if (_guideView.value !== view) return@postDelayed
            if (!guideActive) return@postDelayed
            val host = hostLifecycle()
            val decision = GuideStartGate.decide(
                active = guideActive,
                viewMatches = true,
                attached = view.isAttachedToWindow,
                width = view.width,
                height = view.height,
                host = host,
            )
            NavTrace.log(
                "GUIDE",
                "layout attempt=$attempt attached=${view.isAttachedToWindow} " +
                    "size=${view.width}x${view.height} host=$host decision=$decision",
            )
            when (decision) {
                GuideStartDecision.ABORT -> {
                    abortGuideStart("host-$host", view)
                    return@postDelayed
                }
                GuideStartDecision.WAIT_FOR_LAYOUT -> {
                    if (attempt < 40) {
                        startWhenLaidOut(view, manager, attempt + 1)
                    } else {
                        _status.value = "导航启动失败：视图未布局"
                        abortGuideStart("layout-timeout", view)
                    }
                    return@postDelayed
                }
                GuideStartDecision.START -> Unit
            }
            // Re-check immediately before handing control to the native guide; this is
            // the last point at which a backgrounded Activity can be rejected safely.
            if (hostLifecycle() != HostLifecycle.RESUMED) {
                abortGuideStart("host-changed", view)
                return@postDelayed
            }
            runCatching {
                NavTrace.arm("guide-pre-onstart")
                NavTrace.log("GUIDE", "onStart begin")
                manager.onStart()
                NavTrace.log("GUIDE", "onResume begin")
                manager.onResume()
            }.onSuccess {
                NavTrace.arm("guide-started")
                guideStarted = true
                guideResumed = true
                _status.value = "导航中"
                NavTrace.log("GUIDE", "started addMap=false")
                Log.i(TAG, "guide started, addMap=false")
                // onStart is when the SDK re-applies its own auto day/night decision;
                // assert the user's choice again after it.
                activity?.let { MapSceneController.applyNaviDayNight(it, dayNightMode) }
                // onlyMainRoute is not implemented by carnavi 9.0.2; re-assert only the
                // traffic-light overlay after the guide is actually running.
                applyNaviDisplaySettings(true)
                // Belt and braces: some builds still enter overview on the first frame.
                runCatching { manager.fullView(false) }
                update { it.copy(fullView = false) }
                // Stop the engine from auto-zooming back out (that part worked: a manual
                // zoom now sticks). Do NOT call setLevel here — the navi level scale is
                // not the map zoom scale, and asserting it is what pulled the map away
                // from the good driving zoom right after start.
                mainHandler.postDelayed({
                    if (!guideActive) return@postDelayed
                    MapSceneController.pinNaviView()
                }, 300L)
                NavTrace.log("GUIDE", "onStart done, fullView=false")
                if (_selectedRouteIndex.value > 0) {
                    runCatching { manager.selectRoute(_selectedRouteIndex.value) }
                        .onFailure { Log.w(TAG, "guide selectRoute failed: ${it.message}") }
                }
            }.onFailure {
                Log.e(TAG, "guide start lifecycle failed", it)
                NavTrace.log("GUIDE", "start failed ${it.javaClass.simpleName}: ${it.message}")
                _status.value = "导航启动失败: ${it.javaClass.simpleName}"
                stop()
            }
        }, if (attempt == 0) 30L else 60L)
    }

    /**
     * Guidance display switches.
     *
     * - `setIsOnlyDisplayMainRoute(true)`: the SDK shows the alternative routes and their
     *   "更近/更远 慢N分钟" bubbles by default. Turning it off during guidance is purely a
     *   config change on our side — it is NOT an SDK limitation.
     * - `setMapTrafficLightEnable(true)`: the map's own traffic-light icons were drawn but
     *   without state/countdown because this was never enabled.
     *
     * Both are restored on exit so the next session starts from the SDK defaults.
     */
    private fun applyNaviDisplaySettings(enable: Boolean) {
        // carnavi:9.0.2 does not implement
        // JNIGuidanceInterface.setIsOnlyDisplayMainRoute(long, boolean); calling it throws
        // UnsatisfiedLinkError on every start/stop. Do not retry it or add delays.
        val light = runCatching {
            BaiduNaviManagerFactory.getProfessionalNaviSettingManager()
                .setMapTrafficLightEnable(enable)
        }
        NavTrace.log(
            "GUIDE",
            "displaySettings onlyMainRoute=unsupported-by-carnavi-9.0.2 " +
                "trafficLight=$enable ok=${light.isSuccess}",
        )
    }

    fun stop() {
        // Can be reached from SDK callback threads (onNaviGuideEnd) or from the UI.
        // Removing the guide view off the main thread throws, so marshal first.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            NavTrace.log("GUIDE", "stop off-main thread=${Thread.currentThread().name}, posting")
            mainHandler.post { stop() }
            return
        }
        if (stopping) {
            NavTrace.log("GUIDE", "stop ignored: already stopping")
            return
        }
        stopping = true
        try {
            NavTrace.log("GUIDE", "stop requested active=$guideActive")
            // Any route plan still in flight belongs to the session that is ending.
            cancelPlan("nav-stop")
            stopHeartbeat()
            val manager = guideManager
            val view = _guideView.value
            // Mark the session inactive before any SDK lifecycle call, but keep the
            // manager handle until the final destroy has actually been attempted.
            guideActive = false
            guideStarted = false
            guideResumed = false
            _guideView.value = null
            if (manager != null) {
                // P1 timing, part 1: drop the navi layers while the engine is still alive.
                // After onDestroy the controller can already be unusable.
                MapSceneController.restoreCruiseScene("pre-destroy")
                runCatching { manager.removeNaviListener(naviListener) }
                    .onFailure { NavTrace.log("GUIDE", "removeNaviListener threw ${it.javaClass.simpleName}") }
            }
            // Each lifecycle step is isolated: an exception in onPause/onStop must not
            // skip the final onDestroy. There is no proven removeTrafficLightListener,
            // so the listener is only neutralised by guideActive/callback guards.
            if (manager != null) {
                runCatching { manager.onPause() }
                    .onFailure { NavTrace.log("GUIDE", "onPause threw ${it.javaClass.simpleName}") }
                runCatching { manager.onStop() }
                    .onFailure { NavTrace.log("GUIDE", "onStop threw ${it.javaClass.simpleName}") }
                runCatching { manager.onDestroy(false) }
                    .onFailure { NavTrace.log("GUIDE", "onDestroy threw ${it.javaClass.simpleName}") }
            }
            guideManager = null
            val parent = view?.parent as? ViewGroup
            NavTrace.log(
                "HOST",
                "guideView remove parent=${parent?.javaClass?.simpleName ?: "none"} " +
                    "children=${parent?.childCount ?: 0}",
            )
            parent?.removeView(view)
            applyNaviDisplaySettings(false)
            // Part 2 — unconditional on purpose. If `guideManager` was already null (e.g.
            // the SDK ended guidance itself before we got here) the old code skipped the
            // whole restore, which left the destination pin, the navi palette and the
            // guidance zoom on the shared engine until the process restarted.
            MapSceneController.restoreCruiseScene("post-destroy")
            _sceneRevision.value += 1
            val session = guideSession
            mainHandler.postDelayed({
                // A new guide within 600 ms owns the scene now; the old verify must not
                // restore cruise over it (VA-N04).
                if (session == guideSession && !guideActive) {
                    MapSceneController.restoreCruiseScene("verify")
                }
            }, 600L)
            _active.value = false
            routeReady = false
            pendingPlan = null
            pendingPlanResult = null
            _state.value = NavUiState()
            // Route tabs/paths are per-destination UI state; do not let them reappear
            // in the next search session after a real navigation ends.
            routePaths = emptyList()
            _routeTabs.value = emptyList()
            _selectedRouteIndex.value = 0
            // The next guide may start with the same speed/limit as the previous one;
            // keeping these two values would make onSpeedUpdate drop the first update.
            lastSpeed = Int.MIN_VALUE
            lastLimit = Int.MIN_VALUE
            _status.value = "导航已结束"
            // Cut any instruction that is still playing; navigation is over.
            mainHandler.post { runCatching { speaker?.stop() } }
        } finally {
            stopping = false
        }
    }

    /**
     * Atomic compound update. Several SDK callbacks can arrive concurrently; a plain
     * `_state.value = f(_state.value)` would lose whichever field the other thread wrote
     * between the read and the write (VA-N06).
     */
    private fun update(f: (NavUiState) -> NavUiState) {
        _state.update(f)
    }

    /**
     * The SDK invokes these callbacks on its own threads, and anything thrown there is
     * "uncaught" from the app's point of view: the navi SDK's handler then exits the
     * process. A HUD-side null must never be able to kill navigation, so every callback
     * body is contained and traced instead.
     */
    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            NavTrace.log("CALLBACK", "$what threw ${t.javaClass.name}: ${t.message}")
            NavTrace.log("CALLBACK", t.stackTraceToString())
        }
    }

    /**
     * A stopped guide can still deliver callbacks. Without this gate a late lane/light
     * event repopulates the state that stop() just cleared, which looks like "导航结束
     * 后 HUD 又亮了一下" and can bleed into the next session (VA-N06).
     */
    private fun guardedActive(what: String, block: () -> Unit) {
        // During startGuide the manager exists slightly before guideActive flips true;
        // those first callbacks are legitimate. After stop the manager is null and the
        // session is over, so late callbacks must not repopulate the cleared state.
        if (!guideActive && guideManager == null) {
            NavTrace.log("CALLBACK", "drop $what session=ended")
            return
        }
        guarded(what, block)
    }

    private val naviListener = object : IBNaviListener() {
        override fun onGuideInfoUpdate(info: BNaviInfo?, panel: GuidePanelMessage?) = guardedActive("onGuideInfoUpdate") {
            if (info == null) return@guardedActive
            val nextManeuver = maneuver(info.turnIconName)
            val nextRoad = info.roadName ?: currentRoad
            update {
                // Lane guidance is only valid for the junction it was sent for. When the
                // manoeuvre changes (we cleared a junction and the card moved on to the
                // next one) the previous lane row must go, otherwise old lanes sit under
                // a new turn instruction and look wrong.
                val maneuverChanged = it.turn != null &&
                    (it.turn.maneuver != nextManeuver || it.turn.roadName != nextRoad)
                it.copy(
                    navState = if (it.arrived) NavState.ARRIVED else NavState.TURN,
                    turn = NavTurn(info.distance, nextRoad, nextManeuver),
                    lanes = if (maneuverChanged) emptyList() else it.lanes,
                )
            }
        }

        override fun onRoadNameUpdate(name: String?) = guardedActive("onRoadNameUpdate") {
            currentRoad = name ?: ""
        }

        override fun onRemainInfoUpdate(remainDistance: Int, remainTime: Int) = guardedActive("onRemainInfoUpdate") {
            update { it.copy(eta = NavEta(remainTime, remainDistance, System.currentTimeMillis() + remainTime * 1000L)) }
        }

        override fun onLaneInfoUpdate(action: IBNaviListener.Action?, items: MutableList<BNavLineItem>?) =
            guardedActive("onLaneInfoUpdate") {
                val names = items.orEmpty().map { it.resName }
                // Raw resource names are the only way to tell "SDK recommends these
                // lanes" from "our heuristic guessed": the payload has no structured
                // direction/recommended data. Keep it next to the current turn so the
                // lane/turn mismatch can be judged from one log line.
                NavTrace.log(
                    "LANE",
                    "action=$action turn=${_state.value.turn?.distanceM}/${_state.value.turn?.maneuver} " +
                        "res=${names.joinToString(",")}",
                )
                val lanes = if (action != IBNaviListener.Action.HIDE) {
                    LaneDrawableMapper.fromResourceNames(names)
                } else emptyList()
                update { it.copy(lanes = lanes, navState = if (lanes.isNotEmpty()) NavState.INTERSECTION else it.navState) }
            }

        override fun onSpeedUpdate(speed: Int, speedLimit: Int) = guardedActive("onSpeedUpdate") {
            if (speed == lastSpeed && speedLimit == lastLimit) return@guardedActive
            lastSpeed = speed
            lastLimit = speedLimit
            update { it.copy(speedKmh = NavSentinels.speed(speed) ?: 0, speedLimit = NavSentinels.limit(speedLimit)) }
        }

        override fun onCameraInfoChanged(list: MutableList<BNCameraInfo>?) = guardedActive("onCameraInfoChanged") {
            val c = list.orEmpty().firstOrNull()
            update {
                it.copy(
                    camera = c?.let { cam ->
                        NavCamera(CameraType.fromBaiduCode(cam.type), cam.dist, NavSentinels.limit(cam.speedLimit))
                    },
                    navState = if (c != null) NavState.CAMERA else it.navState,
                )
            }
        }

        override fun onRemainLightsUpdate(remainLights: Int, remainTime: Int) = guardedActive("onRemainLightsUpdate") {
            this@BaiduNavSource.remainLights = remainLights
        }

        override fun onArriveDestination() = guardedActive("onArriveDestination") {
            NavTrace.log("NAVI", "onArriveDestination")
            update { it.copy(arrived = true, navState = NavState.ARRIVED, turn = null, lanes = emptyList()) }
        }

        override fun onStartYawing(reason: String?) = guardedActive("onStartYawing") {
            NavTrace.log("NAVI", "onStartYawing reason=$reason")
            update { it.copy(reroute = true, navState = NavState.REROUTE) }
        }

        override fun onYawingSuccess() = guardedActive("onYawingSuccess") {
            NavTrace.log("NAVI", "onYawingSuccess")
            update { it.copy(reroute = false) }
        }

        override fun onNaviGuideEnd() = guardedActive("onNaviGuideEnd") {
            NavTrace.log("NAVI", "onNaviGuideEnd")
            // This is the SDK ending guidance on its own (arrival, internal quit, ...).
            // Setting active=false alone left the guide alive and the map engine parked
            // in the nav scene, which is exactly the "exit keeps the black 3D map"
            // symptom. Tear the session down like an explicit exit does.
            stop()
        }

        override fun onDayNightChanged(mode: IBNaviListener.DayNightMode?) = guardedActive("onDayNightChanged") {
            // The SDK re-decides day/night by wall-clock time. Treat every change as a
            // hint to re-assert the user's choice and log it, instead of silently
            // letting the map flip under the driver.
            NavTrace.log("SCENE", "onDayNightChanged sdk=$mode user=$dayNightMode")
            // The SDK may call this on a non-main thread; the scene controller is not
            // thread-safe. Apply the user's choice on the main thread and only while
            // this guide is still the active one.
            mainHandler.post {
                if (!guideActive) return@post
                activity?.let { MapSceneController.applyNaviDayNight(it, dayNightMode) }
            }
        }
    }

    private val trafficLightListener = object : IBNRouteGuideManager.TrafficLightListener {
        override fun onTrafficLightUpdate(lights: ArrayList<BNTrafficLight>?) = guardedActive("onTrafficLightUpdate") {
            val detail = lights.orEmpty().firstOrNull()?.detailList?.firstOrNull { it.isMainFlow }
                ?: lights.orEmpty().firstOrNull()?.detailList?.firstOrNull()
            // Delivery evidence: whether the navi listener actually ships per-light state.
            // "The engine draws a light icon" is not proof that this callback delivered.
            NavTrace.log(
                "NAVI",
                "TL n=${lights?.size ?: 0} main=${detail?.isMainFlow} " +
                    "color=${detail?.color} countdown=${detail?.countdownTime} " +
                    "direction=${detail?.direction}",
            )
            if (detail == null) {
                update { it.copy(trafficLight = null) }
                return@guardedActive
            }
            update {
                it.copy(
                    trafficLight = NavTrafficLight(
                        color(detail.color),
                        detail.countdownTime.takeIf { c -> c > 0 },
                        receivedAtMs = System.currentTimeMillis(),
                    ),
                    navState = if (it.arrived || it.reroute) it.navState else NavState.LIGHT,
                )
            }
        }
    }

    private fun maneuver(turnIconName: String?): NavManeuver {
        val n = turnIconName.orEmpty()
        return when {
            n.contains("uturn") -> NavManeuver.UTURN
            n.contains("slight_left") -> NavManeuver.SLIGHT_LEFT
            n.contains("slight_right") -> NavManeuver.SLIGHT_RIGHT
            n.contains("left") -> NavManeuver.LEFT
            n.contains("right") -> NavManeuver.RIGHT
            n.contains("round") -> NavManeuver.ROUNDABOUT
            else -> NavManeuver.STRAIGHT
        }
    }

    // SDK color codes verified against real-device logs in Phase 2; default UNKNOWN.
    private fun color(code: Int): TrafficLightState = when (code) {
        1 -> TrafficLightState.RED
        2 -> TrafficLightState.GREEN
        3 -> TrafficLightState.YELLOW
        else -> TrafficLightState.UNKNOWN
    }

    private companion object {
        const val TAG = "VeloraNav"
    }
}/** Auth JSON contains ak/sk/token/uid; never write them to logcat or nav-trace. */
private val AUTH_SECRET_PATTERN =
    Regex("(\"(?:ak|sk|token|uid)\"\\s*:\\s*\")[^\"]*(\")")

internal fun redactAuthMessage(msg: String?): String {
    if (msg.isNullOrBlank()) return ""
    val jsonSafe = msg.replace(AUTH_SECRET_PATTERN) { m ->
        m.groupValues[1] + "***" + m.groupValues[2]
    }
    return SecretRedaction.redact(jsonSafe)
}


