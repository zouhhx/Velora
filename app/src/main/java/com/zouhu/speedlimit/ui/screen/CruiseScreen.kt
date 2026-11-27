package com.zouhu.speedlimit.ui.screen

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.OverSpeedState
import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.ui.CruiseUiState
import com.zouhu.speedlimit.ui.CruiseViewModel
import com.zouhu.speedlimit.ui.component.CameraCapsule
import com.zouhu.speedlimit.ui.component.IntervalAvgCircle
import com.zouhu.speedlimit.ui.component.LaneGuide
import com.zouhu.speedlimit.ui.component.LimitSign
import com.zouhu.speedlimit.ui.component.SpeedGauge
import com.zouhu.speedlimit.data.nav.BaiduNavSource
import com.zouhu.speedlimit.data.nav.NavTrace
import com.zouhu.speedlimit.data.settings.SecretRedaction
import com.zouhu.speedlimit.data.tts.TtsPlayer
import com.zouhu.speedlimit.di.AppGraph
import com.zouhu.speedlimit.domain.model.NavPoint
import com.zouhu.speedlimit.domain.model.NavUiState
import com.zouhu.speedlimit.ui.nav.NavHud
import com.zouhu.speedlimit.ui.nav.NavSearchScreen
import com.zouhu.speedlimit.ui.nav.NavStartGate
import com.zouhu.speedlimit.ui.nav.NavStartWindowEffect
import com.zouhu.speedlimit.ui.component.TrafficLightCapsule
import com.zouhu.speedlimit.ui.theme.LocalSlmColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private val SlmEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun CruiseScreen(viewModel: CruiseViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    var mapExpanded by remember { mutableStateOf(false) }
    var mapSettled by remember { mutableStateOf(false) }
    // Bumped by the 归中 button and when the map page collapses, so the shared map view
    // returns to the car instead of leaving the home corner map where the user browsed.
    var recenterKey by remember { mutableStateOf(0) }
    // 归中 button restores the default zoom; collapsing the map only re-centres so the
    // home corner map does not change scale while animating.
    var recenterResetZoom by remember { mutableStateOf(true) }
    val mapProgress = remember { Animatable(0f) }

    // Navigation lives inside the expanded map page (no separate nav screen).
    val navSource = remember { BaiduNavSource() }
    // Voice lives at the screen's app scope, but its trigger is inside BaiduNavSource:
    // nav callbacks keep arriving while the Activity is stopped or the screen is locked.
    val appContext = LocalContext.current.applicationContext
    val settingsRepo = remember(appContext) { AppGraph.settingsRepository(appContext) }
    val tts = remember(appContext) {
        TtsPlayer(appContext) { settingsRepo.currentMimoKey() }
    }
    LaunchedEffect(navSource) {
        navSource.attachVoice(tts) { viewModel.state.value.ttsEnabled }
    }
    DisposableEffect(navSource) {
        onDispose {
            navSource.detachVoice()
            tts.stop()
        }
    }
    val cruise by viewModel.state.collectAsStateWithLifecycle()
    val navActive by navSource.active.collectAsStateWithLifecycle()
    val realNav by navSource.state.collectAsStateWithLifecycle()
    val sceneRevision by navSource.sceneRevision.collectAsStateWithLifecycle()
    val nav = if (navActive) {
        realNav
    } else {
        NavUiState(
            speedKmh = cruise.speedKmh,
            speedLimit = cruise.limit,
            route = realNav.route,
        )
    }

    BackHandler(enabled = showSettings || mapExpanded || navActive) {
        when {
            showSettings -> showSettings = false
            navActive -> navSource.stop()
            else -> mapExpanded = false
        }
    }
    LaunchedEffect(navActive) {
        viewModel.setNavActive(navActive)
    }
    // Keep the navi scene palette in sync with the user's choice, including while a
    // navigation session is already running.
    LaunchedEffect(cruise.dayNightMode) { navSource.setDayNightMode(cruise.dayNightMode) }
    DisposableEffect(Unit) {
        onDispose {
            navSource.stop()
            viewModel.setNavActive(false)
        }
    }

    LaunchedEffect(mapExpanded) {
        mapProgress.animateTo(
            if (mapExpanded) 1f else 0f,
            tween(620, easing = SlmEase),
        )
    }
    LaunchedEffect(mapExpanded) {
        if (!mapExpanded) {
            recenterResetZoom = false
            recenterKey += 1
        }
        if (mapExpanded) {
            delay(640)
            mapSettled = true
        } else {
            mapSettled = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(LocalSlmColors.current.bg)) {
        val landscape = maxWidth > maxHeight

        // Host the Baidu guide view behind the persistent map. ADD_MAP=false means
        // it only owns invisible HUD callbacks; keeping it behind our map preserves
        // map gestures and prevents the SDK view from being detached on recompose.
        NaviGuideHost(navSource = navSource, modifier = Modifier.fillMaxSize())

        // One continuous map layer: the same decorative map lives in the corner and expands.
        MapTransitionLayer(
            viewModel = viewModel,
            progress = mapProgress,
            showStatic = mapSettled,
            loadStatic = mapExpanded,
            landscape = landscape,
            route = nav.route,
            // Route fit belongs to the search preview map. Keeping the persistent
            // map camera untouched avoids changing home/detail zoom continuity.
            fitRoute = false,
            resetKey = sceneRevision,
            navActive = navActive,
            navFullView = nav.fullView,
            recenterKey = recenterKey,
            recenterZoomReset = recenterResetZoom,
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = !mapExpanded,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(434, easing = SlmEase)) +
                scaleIn(tween(620, easing = SlmEase), initialScale = 0.98f),
            exit = fadeOut(tween(434, easing = SlmEase)) +
                scaleOut(tween(620, easing = SlmEase), targetScale = 0.98f),
        ) {
            CruiseLayer(
                viewModel = viewModel,
                landscape = landscape,
                onOpenMap = { mapExpanded = true },
            )
        }

        AnimatedVisibility(
            visible = mapExpanded,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(527, easing = SlmEase)),
            exit = fadeOut(tween(527, easing = SlmEase)),
        ) {
            MapChromeHost(
                viewModel = viewModel,
                live = mapExpanded,
                onClose = { mapExpanded = false },
                onOpenSettings = { showSettings = true },
                onRecenter = {
                    recenterResetZoom = true
                    recenterKey += 1
                },
                navSource = navSource,
                nav = nav,
                navActive = navActive,
                cruise = cruise,
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(tween(320, easing = SlmEase)) { it } +
                fadeIn(tween(180, easing = SlmEase)),
            exit = slideOutHorizontally(tween(260, easing = SlmEase)) { it } +
                fadeOut(tween(160, easing = SlmEase)),
        ) {
            SettingsHost(viewModel = viewModel, onClose = { showSettings = false })
        }
    }
}

@Composable
private fun NaviGuideHost(navSource: BaiduNavSource, modifier: Modifier = Modifier) {
    val guideView by navSource.guideView.collectAsStateWithLifecycle()
    AndroidView(
        factory = { FrameLayout(it) },
        update = { host ->
            val view = guideView
            if (view == null) {
                if (host.childCount > 0) {
                    NavTrace.log("HOST", "guideView removed children=${host.childCount}")
                    host.removeAllViews()
                }
            } else if (view.parent !== host) {
                (view.parent as? ViewGroup)?.removeView(view)
                NavTrace.log(
                    "HOST",
                    "attach guideView=${view.javaClass.simpleName} attached=${view.isAttachedToWindow} " +
                        "size=${view.width}x${view.height}",
                )
                host.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun MapTransitionLayer(
    viewModel: CruiseViewModel,
    progress: Animatable<Float, AnimationVector1D>,
    showStatic: Boolean,
    loadStatic: Boolean,
    landscape: Boolean,
    route: List<NavPoint> = emptyList(),
    fitRoute: Boolean = false,
    resetKey: Int = 0,
    navActive: Boolean = false,
    navFullView: Boolean = false,
    recenterKey: Int = 0,
    recenterZoomReset: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val homeScale = if (landscape) 1f else 0.38f
    Box(
        modifier.fillMaxSize().graphicsLayer {
            val p = progress.value.coerceIn(0f, 1f)
            if (landscape) {
                translationX = (1f - p) * size.width / 2f
            } else {
                val scale = homeScale + (1f - homeScale) * p
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(1f, 0f)
            }
            compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithContent {
            drawContent()
            val a = (1f - progress.value).coerceIn(0f, 1f)
            // Erase the map's OWN left/bottom edge alpha to transparent (inside the
            // map's offscreen layer) so the page background shows through. Being part
            // of the map layer itself, it can never be mis-ordered against the map.
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = a),
                    0.13f to Color.Black.copy(alpha = a * 0.5f),
                    0.42f to Color.Transparent,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstOut,
            )
            if (!landscape) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0.78f to Color.Transparent,
                        0.90f to Color.Black.copy(alpha = a * 0.5f),
                        1f to Color.Black.copy(alpha = a),
                    ),
                    blendMode = BlendMode.DstOut,
                )
            }
        },
    ) {
        MapLayerHost(
            viewModel = viewModel,
            showStatic = showStatic,
            loadStatic = loadStatic,
            homeScale = homeScale,
            progress = progress,
            route = route,
            fitRoute = fitRoute,
            resetKey = resetKey,
            navActive = navActive,
            navFullView = navFullView,
            recenterKey = recenterKey,
            recenterZoomReset = recenterZoomReset,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CruiseLayer(
    viewModel: CruiseViewModel,
    landscape: Boolean,
    onOpenMap: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                CruiseContent(state, true, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.weight(1f))
            }
        } else {
            CruiseContent(state, false, Modifier.fillMaxSize())
        }

        // The map region itself is the entry (tap the map to open it), matching the HTML map-hit.
        Box(
            Modifier
                .align(if (landscape) Alignment.CenterEnd else Alignment.TopEnd)
                .then(
                    // HTML map-hit is 44% x 38% in portrait and the whole right half in landscape.
                    if (landscape) Modifier.fillMaxHeight().fillMaxWidth(0.5f)
                    else Modifier.fillMaxWidth(0.44f).fillMaxHeight(0.38f)
                )
                .clickable(onClick = onOpenMap)
        )
    }
}

private data class MapTarget(
    val ak: String,
    val latitude: Double,
    val longitude: Double,
    /** Any usable position, coarse or precise: the map may be revealed. */
    val hasPosition: Boolean,
    /** Position good enough to follow continuously (avoids chasing a jittering network fix). */
    val follow: Boolean,
    val accuracyM: Float,
)

private data class MapStatus(
    val speedKmh: Int,
    val limit: Int?,
    val over: Boolean,
    val trafficLight: TrafficLightInfo?,
)

@Composable
private fun MapLayerHost(
    viewModel: CruiseViewModel,
    showStatic: Boolean,
    loadStatic: Boolean,
    homeScale: Float,
    progress: Animatable<Float, AnimationVector1D>,
    route: List<NavPoint> = emptyList(),
    fitRoute: Boolean = false,
    resetKey: Int = 0,
    navActive: Boolean = false,
    navFullView: Boolean = false,
    recenterKey: Int = 0,
    recenterZoomReset: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val initialTarget = remember(viewModel) {
        val s = viewModel.state.value
        MapTarget(s.mapAk, s.latitude, s.longitude, s.hasPosition, s.showCruiseData, s.locationAccuracyM)
    }
    var target by remember(viewModel) { mutableStateOf(initialTarget) }
    LaunchedEffect(viewModel) {
        while (true) {
            val s = viewModel.state.value
            target = MapTarget(s.mapAk, s.latitude, s.longitude, s.hasPosition, s.showCruiseData, s.locationAccuracyM)
            delay(500)
        }
    }
    MapLayer(
        ak = target.ak,
        latitude = target.latitude,
        longitude = target.longitude,
        hasPosition = target.hasPosition,
        follow = target.follow,
        accuracyM = target.accuracyM,
        showStatic = showStatic,
        loadStatic = loadStatic,
        homeScale = homeScale,
        progress = progress,
        route = route,
        fitRoute = fitRoute,
        resetKey = resetKey,
        navActive = navActive,
        navFullView = navFullView,
        recenterKey = recenterKey,
        recenterZoomReset = recenterZoomReset,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun SettingsHost(viewModel: CruiseViewModel, onClose: () -> Unit) {
    val settingsFlow = remember(viewModel) {
        viewModel.state
            .map {
                SettingsUi(
                    thresholdPct = it.thresholdPct,
                    soundEnabled = it.soundEnabled,
                    keepScreenOn = it.keepScreenOn,
                    ttsEnabled = it.ttsEnabled,
                    dayNightMode = it.dayNightMode,
                    serverAkMasked = SecretRedaction.mask(it.directAk),
                    serverAkConfigured = it.directAk.isNotBlank(),
                    serverSkMasked = SecretRedaction.mask(it.serverSk),
                    serverSkConfigured = it.serverSk.isNotBlank(),
                    mimoMasked = SecretRedaction.mask(it.mimoKey),
                    mimoConfigured = it.mimoKey.isNotBlank(),
                    mapAkMasked = SecretRedaction.mask(it.mapAk),
                    mapAkConfigured = it.mapAk.isNotBlank(),
                    serviceStatus = it.serviceStatus,
                )
            }
            .distinctUntilChanged()
    }
    val state by settingsFlow.collectAsStateWithLifecycle(initialValue = SettingsUi())
    SettingsScreen(
        state = state,
        onThreshold = viewModel::setThreshold,
        onSound = viewModel::setSoundEnabled,
        onTts = viewModel::setTtsEnabled,
        onKeepScreenOn = viewModel::setKeepScreenOn,
        onDayNight = viewModel::setDayNightMode,
        onSaveServerAk = viewModel::setDirectAk,
        onSaveServerSk = viewModel::setServerSk,
        onSaveMimoKey = viewModel::setMimoKey,
        onSaveMapAk = viewModel::setMapAk,
        onResetServerAk = viewModel::resetDirectAk,
        onResetServerSk = viewModel::resetServerSk,
        onResetMimoKey = viewModel::resetMimoKey,
        onResetMapAk = viewModel::resetMapAk,
        onClose = onClose,
    )
}

@Composable
private fun MapChromeHost(
    viewModel: CruiseViewModel,
    live: Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onRecenter: () -> Unit,
    navSource: BaiduNavSource,
    nav: NavUiState,
    navActive: Boolean,
    cruise: CruiseUiState,
) {
    val context = LocalContext.current
    var showSearch by remember { mutableStateOf(false) }
    val startGate = remember { NavStartGate() }
    var pendingStart by remember { mutableStateOf(0) }

    fun cancelPendingStart() {
        startGate.cancel()
        pendingStart = 0
    }

    NavStartWindowEffect(
        pendingStart = pendingStart,
        live = live,
        showSearch = showSearch,
        consume = startGate::consume,
        onCancel = { cancelPendingStart() },
        onStart = {
            pendingStart = 0
            // Let the preview screen's TextureMapView finish onPause/onDestroy before
            // creating and starting the guide view. G0 only tested one map + guideView.
            NavTrace.log("NAV", "search closed; starting guide after preview map disposal")
            if (!navSource.startGuide()) {
                showSearch = true
            }
        },
    )
    val initialStatus = remember(viewModel) {
        val s = viewModel.state.value
        MapStatus(
            speedKmh = s.speedKmh,
            limit = s.limit,
            over = s.overSpeed == OverSpeedState.OVER || s.overSpeed == OverSpeedState.SEVERE,
            trafficLight = s.trafficLight,
        )
    }
    var status by remember(viewModel) { mutableStateOf(initialStatus) }
    LaunchedEffect(viewModel, live) {
        if (!live) return@LaunchedEffect
        // No text invalidation during the transform; resume the live speed once it settles.
        delay(560)
        while (true) {
            val s = viewModel.state.value
            status = MapStatus(
                speedKmh = s.speedKmh,
                limit = s.limit,
                over = s.overSpeed == OverSpeedState.OVER || s.overSpeed == OverSpeedState.SEVERE,
                trafficLight = s.trafficLight,
            )
            delay(120)
        }
    }
    LaunchedEffect(live) { if (live) (context as? Activity)?.let { navSource.attach(it) } }

    Box(Modifier.fillMaxSize()) {
        if (navActive) {
            NavHud(
                nav = nav,
                onExit = { navSource.stop() },
                onToggleFullView = { navSource.setFullView(!nav.fullView) },
            )
        } else {
            MapChrome(
                speedKmh = status.speedKmh,
                over = status.over,
                limit = status.limit,
                trafficLight = status.trafficLight,
                showSpeed = cruise.showSpeed,
                onClose = onClose,
                onOpenSettings = onOpenSettings,
                onOpenSearch = {
                    cancelPendingStart()
                    showSearch = true
                },
                onRecenter = onRecenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showSearch) {
            NavSearchScreen(
                source = navSource,
                cruise = cruise,
                onStartNav = {
                    // A new destination gets a new token; any older delayed start is
                    // invalid even if its 320 ms delay has not expired yet.
                    pendingStart = startGate.request()
                    showSearch = false
                },
                onBack = {
                    cancelPendingStart()
                    navSource.clearPreviewRoute()
                    showSearch = false
                },
            )
        }
    }
}

@Composable
private fun CruiseContent(state: CruiseUiState, landscape: Boolean, modifier: Modifier = Modifier) {
    val inside = state.cameraType == CameraType.INTERVAL && state.interval.phase == IntervalPhase.INSIDE
    val gaugeSize = if (landscape) 180.dp else 296.dp
    val avgSize = if (landscape) 104.dp else 128.dp
    val signSize = gaugeSize * if (landscape) 0.311f else 0.243f
    val topPad = if (landscape) 18.dp else 54.dp
    val bottomPad = if (landscape) 16.dp else 30.dp
    val avgGap by animateDpAsState(if (inside) 26.dp else 0.dp, tween(500, easing = SlmEase))
    val avgEnter = fadeIn(tween(500, easing = SlmEase)) +
        scaleIn(tween(500, easing = SlmEase), initialScale = 0.85f)
    val avgExit = fadeOut(tween(300, easing = SlmEase)) +
        scaleOut(tween(300, easing = SlmEase), targetScale = 0.85f)

    Column(
        modifier = modifier.padding(top = topPad, bottom = bottomPad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (landscape) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // No gauge without a live, accuracy-checked satellite speed. The
                    // absence of the element *is* the status — no "unknown" wording.
                    if (state.showSpeed) GaugeWithSign(state, gaugeSize, signSize)
                    Spacer(Modifier.width(avgGap))
                    AnimatedVisibility(
                        visible = inside && state.intervalAvgVisible,
                        enter = avgEnter,
                        exit = avgExit,
                    ) {
                        IntervalAvgCircle(
                            state.interval.avgKmh.roundToInt(), state.intervalAvg, state.limit,
                            diameter = avgSize,
                        )
                    }
                }
            } else {
                if (state.showSpeed) GaugeWithSign(state, gaugeSize, signSize)
                androidx.compose.animation.AnimatedVisibility(
                    visible = inside && state.intervalAvgVisible,
                    enter = avgEnter,
                    exit = avgExit,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                ) {
                    IntervalAvgCircle(
                        state.interval.avgKmh.roundToInt(), state.intervalAvg, state.limit,
                        diameter = avgSize,
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        ) {
            LaneGuide(
                lanes = state.lanes,
                visible = state.lanes.isNotEmpty(),
                cellHeight = if (landscape) 42.dp else 54.dp,
                cellMinWidth = if (landscape) 34.dp else 46.dp,
            )
            CameraCapsule(
                visible = state.cameraAhead,
                cameraType = state.cameraType,
                distText = state.cameraDistText,
                label = state.cameraLabel,
                limit = state.cameraLimit,
                compact = landscape,
            )
            TrafficLightCapsule(
                info = state.trafficLight,
                compact = landscape,
            )
        }
    }
}

@Composable
private fun GaugeWithSign(state: CruiseUiState, gaugeSize: androidx.compose.ui.unit.Dp, signSize: androidx.compose.ui.unit.Dp) {
    Box(contentAlignment = Alignment.Center) {
        SpeedGauge(state.speedKmh, state.overSpeed, diameter = gaugeSize)
        LimitSign(
            state.limit,
            modifier = Modifier.align(Alignment.CenterStart).offset(x = (-6).dp),
            size = signSize,
        )
    }
}