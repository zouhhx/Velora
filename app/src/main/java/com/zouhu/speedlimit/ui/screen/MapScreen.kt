package com.zouhu.speedlimit.ui.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.model.LatLngBounds
import com.baidu.mapapi.map.Marker
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.Polyline
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.map.UiSettings
import com.baidu.mapapi.model.LatLng
import com.zouhu.speedlimit.data.map.baidu.BaiduMapSdk
import com.zouhu.speedlimit.data.map.baidu.BaiduMappers
import com.zouhu.speedlimit.data.map.baidu.CoordUtil
import com.zouhu.speedlimit.data.map.baidu.MapImageCache
import com.zouhu.speedlimit.data.nav.NavTrace
import com.zouhu.speedlimit.domain.model.NavPoint
import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.ui.component.MapBackdrop
import com.zouhu.speedlimit.ui.component.MiniLimitSign
import com.zouhu.speedlimit.ui.component.SettingsButton
import com.zouhu.speedlimit.ui.component.TrafficLightCapsule
import com.zouhu.speedlimit.ui.theme.LocalSlmColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log2
import kotlin.math.roundToInt

// Keep the on-screen content scale identical in home and detail. In portrait the
// home map is rendered at zoom 18 and downscaled by 0.38, so the detail render
// zoom must be 18 + log2(0.38) = 16.604. Landscape home is translation-only
// (scale 1), so its detail zoom is 18.
private const val HOME_MAP_ZOOM = 18f
private const val MAP_ZOOM_ANIM_MS = 620
private const val MAP_RECENTER_ANIM_MS = 450
/** Preview before the route arrives: roughly "city block" level, not Baidu's default. */
private const val PREVIEW_IDLE_ZOOM = 13f
/** Guidance zoom that still renders lane-level detail (the SDK's own guide default). */
private const val NAV_ZOOM = 17.5f
/** Drag beyond this many pixels before we decide the engine is ignoring pans. */
private const val PAN_SLOP_PX = 12f
/** How long the guidance map stays where the user dragged it before gliding back. */
private const val NAV_DRAG_RECENTER_MS = 6_000L

private fun detailMapZoom(homeScale: Float): Float = HOME_MAP_ZOOM + log2(homeScale)

@Composable
fun MapLayer(
    ak: String,
    latitude: Double,
    longitude: Double,
    hasPosition: Boolean,
    showStatic: Boolean,
    loadStatic: Boolean,
    homeScale: Float,
    progress: Animatable<Float, AnimationVector1D>,
    /**
     * Continuous camera follow. False for a coarse (network) fix: a Wi-Fi position can
     * jump by hundreds of metres between updates, and chasing it looks like a broken map.
     */
    follow: Boolean = false,
    /** Horizontal accuracy, used to size the follow deadband. */
    accuracyM: Float = 0f,
    route: List<NavPoint> = emptyList(),
    fitRoute: Boolean = false,
    preview: Boolean = false,
    resetKey: Int = 0,
    navActive: Boolean = false,
    /** True while the navi HUD is showing the whole-route overview (VA-F08). */
    navFullView: Boolean = false,
    recenterKey: Int = 0,
    recenterZoomReset: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSlmColors.current
    val liveMap = BaiduMapSdk.available
    // The static-image fallback only exists for builds without the Android map AK/SDK.
    val canLoadStatic = !liveMap && ak.isNotBlank() && hasPosition
    val centerLat = remember(loadStatic, canLoadStatic) { latitude }
    val centerLng = remember(loadStatic, canLoadStatic) { longitude }
    val cacheKey = remember(ak, centerLat, centerLng) {
        "$ak:${(centerLat * 200).roundToInt()}:${(centerLng * 200).roundToInt()}"
    }
    var image by remember(cacheKey) { mutableStateOf(MapImageCache.get(cacheKey)) }

    LaunchedEffect(cacheKey, canLoadStatic, loadStatic) {
        if (!canLoadStatic || !loadStatic || image != null) return@LaunchedEffect
        // Legacy fallback only: prefetch while expanding, display after it settles.
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val (bdLat, bdLng) = CoordUtil.wgs84ToBd09(centerLat, centerLng)
                val url = "https://api.map.baidu.com/staticimage/v2?ak=$ak" +
                    "&center=$bdLng,$bdLat&zoom=16&width=1024&height=1024" +
                    "&markers=$bdLng,$bdLat&markerStyles=l,A"
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                connection.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (loaded != null) {
            MapImageCache.put(cacheKey, loaded)
            image = loaded
        }
    }

    val fallbackImage = if (!liveMap && showStatic) image else null
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    var mapVisible by remember { mutableStateOf(false) }
    val revealAlpha by animateFloatAsState(
        targetValue = if (mapVisible) 1f else 0f,
        animationSpec = tween(280),
        label = "mapRevealAlpha",
    )
    val snapshotImage = remember(snapshot) { snapshot?.asImageBitmap() }

    // A fix can flicker before the first reveal; never let an older location's
    // snapshot become the edge of a newer location.
    LaunchedEffect(hasPosition, mapVisible) {
        if (!mapVisible && !hasPosition) snapshot = null
    }

    Box(modifier.fillMaxSize().background(colors.mapBg)) {
        if (liveMap) {
            LiveBaiduMap(
                latitude = latitude,
                longitude = longitude,
                hasPosition = hasPosition,
                follow = follow,
                accuracyM = accuracyM,
                interactive = showStatic,
                detail = loadStatic,
                detailZoom = detailMapZoom(homeScale),
                mapAlpha = if (mapVisible) revealAlpha else 0.001f,
                edgeReady = snapshot != null,
                onSnapshot = { bitmap -> if (mapVisible || hasPosition) snapshot = bitmap },
                onRevealed = { mapVisible = true },
                route = route,
                fitRoute = fitRoute,
                preview = preview,
                resetKey = resetKey,
                navActive = navActive,
                navFullView = navFullView,
                recenterKey = recenterKey,
                recenterZoomReset = recenterZoomReset,
                modifier = Modifier.fillMaxSize(),
            )
            // Same edge treatment as before, but cloned from a snapshot of the live
            // map: 1/4 resolution + mask union keeps the RenderEffect cheap, and a
            // TextureView itself cannot be captured by Compose's blur layer. The
            // edge fade shares the map's reveal alpha so neutral -> real is one fade.
            val edgeImage = snapshotImage
            if (!showStatic && mapVisible && edgeImage != null) {
                MapEdgeBlur(progress, revealAlpha) {
                    SnapshotVisual(edgeImage, Modifier.fillMaxSize())
                }
            }
        } else {
            Crossfade(
                targetState = fallbackImage,
                animationSpec = tween(280),
                label = "mapImage",
            ) { bitmap ->
                MapVisual(bitmap, Modifier.fillMaxSize())
            }
            if (!showStatic) {
                MapEdgeBlur(progress) {
                    MapVisual(fallbackImage, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun MapEdgeBlur(
    progress: Animatable<Float, AnimationVector1D>,
    revealFactor: Float = 1f,
    content: @Composable () -> Unit,
) {
    // Blurred clone of the SAME map, masked so blur hugs the left + bottom-left
    // edges and fades out toward the focal right side. Two stacked masks reproduce
    // the HTML union (to-top-right ∪ to-right). Rendered at 1/4 resolution then
    // scaled back up: blur is low-frequency so this looks identical, but the
    // per-frame RenderEffect is ~16x cheaper (keeps the transition at 120fps).
    // Skipped entirely once the map is expanded/settled (blur alpha would be 0).
    Box(
        Modifier
            .fillMaxSize(0.25f)
            .graphicsLayer {
                alpha = ((1f - progress.value) * revealFactor).coerceIn(0f, 1f)
                scaleX = 4f
                scaleY = 4f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .blur(1.75.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Black,
                            0.14f to Color.Black,
                            0.60f to Color.Transparent,
                        ),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        content()
    }
    Box(
        Modifier
            .fillMaxSize(0.25f)
            .graphicsLayer {
                alpha = ((1f - progress.value) * revealFactor).coerceIn(0f, 1f)
                scaleX = 4f
                scaleY = 4f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .blur(1.75.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0f to Color.Black,
                            0.07f to Color.Black,
                            0.48f to Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, 0f),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        content()
    }
}

@Composable
private fun MapVisual(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "当前地点地图",
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        MapBackdrop(modifier)
    }
}

@Composable
private fun SnapshotVisual(image: ImageBitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun LiveBaiduMap(
    latitude: Double,
    longitude: Double,
    hasPosition: Boolean,
    follow: Boolean,
    accuracyM: Float,
    interactive: Boolean,
    detail: Boolean,
    detailZoom: Float,
    mapAlpha: Float,
    edgeReady: Boolean,
    onSnapshot: (Bitmap) -> Unit,
    onRevealed: () -> Unit,
    route: List<NavPoint> = emptyList(),
    fitRoute: Boolean = false,
    preview: Boolean = false,
    resetKey: Int = 0,
    navActive: Boolean = false,
    navFullView: Boolean = false,
    recenterKey: Int = 0,
    recenterZoomReset: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = LocalSlmColors.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentOnSnapshot by rememberUpdatedState(onSnapshot)
    val currentOnRevealed by rememberUpdatedState(onRevealed)
    val fullViewNow by rememberUpdatedState(navFullView)
    val navActiveNow by rememberUpdatedState(navActive)
    val markerIcon = remember(colors.mint) {
        BitmapDescriptorFactory.fromBitmap(locationDot(context, colors.mint.toArgb()))
    }
    val mapHolder = remember { arrayOfNulls<TextureMapView>(1) }
    val markerHolder = remember { arrayOfNulls<Marker>(1) }
    val routeHolder = remember { arrayOfNulls<Polyline>(1) }
    /** Last point the camera was moved to, for the follow deadband. */
    val lastFollowed = remember { arrayOfNulls<LatLng>(1) }
    val routeWidth = remember { (5f * context.resources.displayMetrics.density).coerceAtLeast(4f) }
    val pendingSnapshot = remember { arrayOfNulls<Runnable>(1) }
    val snapshotGeneration = remember { AtomicInteger(0) }
    val firstFrameSeen = remember { AtomicBoolean(false) }
    val firstFrameNow = remember { AtomicBoolean(false) }
    val hasPositionNow = remember { AtomicBoolean(false) }
    val cameraMoving = remember { AtomicBoolean(false) }
    val gesturesOn = remember { booleanArrayOf(true) }
    // Navigation-only manual pan state (see the gesture fallback in dispatchTouchEvent).
    val navNow = remember { booleanArrayOf(false) }
    val posNow = remember { arrayOfNulls<LatLng>(1) }
    val dragGen = remember { intArrayOf(0) }
    val dragX = remember { floatArrayOf(0f) }
    val dragY = remember { floatArrayOf(0f) }
    val selfPan = remember { booleanArrayOf(false) }
    val downTarget = remember { arrayOfNulls<LatLng>(1) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val active = remember { booleanArrayOf(true) }
    var firstFrame by remember { mutableStateOf(false) }
    var revealStarted by remember { mutableStateOf(false) }
    var initialCameraSet by remember { mutableStateOf(false) }
    var zoomTransitionInitialized by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    // The map view is created once and kept for the whole composition. AndroidView's
    // onRelease and the composition's onDispose can both run on teardown; this flag
    // makes the native pause/destroy idempotent instead of tearing down the shared
    // Baidu engine twice.
    val createdView = remember { arrayOfNulls<TextureMapView>(1) }
    val mapDestroyed = remember { AtomicBoolean(false) }
    fun destroyMapOnce(view: TextureMapView) {
        if (mapDestroyed.compareAndSet(false, true)) {
            // VA-M02 evidence: one create/destroy pair per view id, no double teardown.
            NavTrace.log("MAPVIEW", "destroy id=${System.identityHashCode(view)}")
            runCatching {
                view.onPause()
                view.onDestroy()
            }
        }
    }
    val deliverSnapshot: (Bitmap, Int) -> Unit = { bitmap, generation ->
        scope.launch(Dispatchers.Default) {
            val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
                runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: bitmap
            } else {
                bitmap
            }
            val width = (source.width / 4).coerceAtLeast(1)
            val height = (source.height / 4).coerceAtLeast(1)
            val scaled = runCatching { source.scale(width, height) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (active[0] && generation == snapshotGeneration.get() && scaled != null) {
                    currentOnSnapshot(scaled)
                }
            }
        }
    }

    val scheduleSnapshot: (TextureMapView, Long) -> Unit = { view, delayMs ->
        pendingSnapshot[0]?.let { view.removeCallbacks(it) }
        val generation = snapshotGeneration.incrementAndGet()
        val runnable = Runnable {
            pendingSnapshot[0] = null
            // Never capture a mid-animation frame, the SDK default area, or a
            // previous location: only a settled camera after a fix/reveal.
            if (active[0] && !cameraMoving.get() && firstFrameNow.get() &&
                (hasPositionNow.get() || revealStarted)
            ) {
                runCatching {
                    view.map.snapshot { bitmap ->
                        if (bitmap != null) deliverSnapshot(bitmap, generation)
                    }
                }
            }
        }
        pendingSnapshot[0] = runnable
        view.postDelayed(runnable, delayMs)
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapHolder[0] ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            active[0] = false
            pendingSnapshot[0]?.let { runnable -> mapHolder[0]?.removeCallbacks(runnable) }
            pendingSnapshot[0] = null
            mapHolder[0]?.let { destroyMapOnce(it) }
            mapHolder[0] = null
            createdView[0] = null
        }
    }

    // Neutral loading surface until the map has both a first frame and a GPS fix,
    // and the snapshot for its edge is ready. Then map + blurred edge fade in over
    // the neutral surface as one crossfade.
    LaunchedEffect(firstFrame, hasPosition, edgeReady) {
        if (firstFrame && hasPosition && edgeReady && !revealStarted) {
            revealStarted = true
            currentOnRevealed()
            // Re-capture after reveal so the edge matches tiles that finished later.
            mapHolder[0]?.let { scheduleSnapshot(it, 200) }
        }
    }

    // Never hold the home surface hostage if the snapshot never arrives.
    LaunchedEffect(firstFrame, hasPosition) {
        if (firstFrame && hasPosition && !revealStarted) {
            delay(1_800)
            if (!revealStarted) {
                revealStarted = true
                currentOnRevealed()
                mapHolder[0]?.let { scheduleSnapshot(it, 250) }
            }
        }
    }

    // Do not keep the map hidden forever if the SDK never reports a first frame.
    LaunchedEffect(Unit) {
        delay(3_000)
        firstFrameNow.set(true)
        firstFrame = true
    }

    // First fix: establish the camera at the zoom for the current state. One-time
    // set while the map is still neutral/hidden; never a lower zoom.
    LaunchedEffect(hasPosition, detail, navActive) {
        if (!hasPosition || initialCameraSet || fitRoute || preview || navActive) return@LaunchedEffect
        val view = mapHolder[0] ?: return@LaunchedEffect
        val target = mapTarget(latitude, longitude) ?: return@LaunchedEffect
        view.map.setMapStatus(
            MapStatusUpdateFactory.newLatLngZoom(
                target,
                if (detail) detailZoom else HOME_MAP_ZOOM,
            ),
        )
        initialCameraSet = true
    }

    // Explicit open/close only: animate the render zoom so the on-screen content
    // scale stays equal to the home equivalent (portrait: 18 * 0.38 = 16.604).
    // The center is preserved; recenter happens separately after the transform.
    LaunchedEffect(detail, detailZoom) {
        if (!zoomTransitionInitialized) {
            zoomTransitionInitialized = true
            return@LaunchedEffect
        }
        if (!fitRoute && !preview && !navActive) {
            if (!initialCameraSet) return@LaunchedEffect
            val view = mapHolder[0] ?: return@LaunchedEffect
            view.map.animateMapStatus(
                MapStatusUpdateFactory.zoomTo(
                    if (detail) detailZoom else HOME_MAP_ZOOM,
                ),
                MAP_ZOOM_ANIM_MS,
            )
        }
    }

    // Recenter only after the shared-element transform settles, preserving the
    // current zoom so a manual zoom in detail is never overwritten by data or
    // lifecycle state changes. Route preview owns the camera instead; a GPS
    // recenter during preview would otherwise invalidate the fit.
    // Continuous position follow for home + detail (center only, zoom preserved).
    // Keyed on position so the mini-map tracks the car without opening the full map.
    //
    // Two guards keep a poor fix from dragging the camera around:
    //  - `follow` is false for coarse (network) fixes, which can jump hundreds of metres;
    //  - the camera only moves once the fix has moved further than its own error, so a
    //    stationary car with 40 m accuracy does not get a jittering map.
    LaunchedEffect(hasPosition, follow, latitude, longitude, fitRoute, preview, navActive) {
        // P5: during navigation the navi engine owns the camera (car lock + auto scale).
        // Our own per-sample recenter used to fight it every second — the map drifted
        // back and forth and manual pans were overridden. Hand ownership over instead
        // of trying to debounce.
        if (!hasPosition || !follow || fitRoute || preview || navActive) return@LaunchedEffect
        val view = mapHolder[0] ?: return@LaunchedEffect
        val target = mapTarget(latitude, longitude) ?: return@LaunchedEffect
        val last = lastFollowed[0]
        if (last != null) {
            val moved = BaiduMappers.haversine(last.latitude, last.longitude, target.latitude, target.longitude)
            val deadband = maxOf(20.0, accuracyM.toDouble() * 0.8)
            if (moved < deadband) return@LaunchedEffect
        }
        lastFollowed[0] = target
        view.map.animateMapStatus(
            MapStatusUpdateFactory.newLatLng(target),
            MAP_RECENTER_ANIM_MS,
        )
    }

    // Route preview fit.
    //
    // This used to run inside AndroidView.update, which executes *before* the frame's
    // layout: it read the previous (taller) viewport, and the same frame then added the
    // route cards and shrank the map. The resulting zoom was too high, so the first
    // route was cropped top and bottom; switching routes re-fit against a settled
    // viewport and looked correct. Fit here instead, keyed on the *measured* size, with
    // a short debounce so a size change restarts the effect rather than fitting twice.
    LaunchedEffect(route, fitRoute, preview, mapSize, firstFrame) {
        if (!firstFrame || mapSize.width == 0 || mapSize.height == 0) return@LaunchedEffect
        val pts = route.map { LatLng(it.lat, it.lng) }.filter {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
        if (!fitRoute || pts.size < 2) {
            // Preview opens before the route exists: park on the car. Without this the
            // map shows Baidu's default centre (Beijing) until the first fit.
            if (preview) {
                val idleView = mapHolder[0]
                val target = mapTarget(latitude, longitude)
                if (idleView != null && target != null) {
                    runCatching {
                        idleView.map.setMapStatus(
                            MapStatusUpdateFactory.newLatLngZoom(target, PREVIEW_IDLE_ZOOM),
                        )
                    }
                }
            }
            if (fitRoute) NavTrace.log("FIT", "skip invalid points=${pts.size}/${route.size}")
            return@LaunchedEffect
        }
        // Debounce: layout settles a frame or two after the cards appear.
        delay(60)
        val view = mapHolder[0] ?: return@LaunchedEffect
        runCatching {
            val bounds = LatLngBounds.Builder().apply { pts.forEach { include(it) } }.build()
            // `newLatLngBounds(bounds, w, h)` fits the bounds into a w x h *pixel rect*
            // (there is no padding overload in 9.0.2). Pass the measured size minus a
            // visual margin.
            val margin = (28 * context.resources.displayMetrics.density).toInt()
            val fitW = (mapSize.width - margin * 2).coerceAtLeast(1)
            val fitH = (mapSize.height - margin * 2).coerceAtLeast(1)
            val applied = view.map.setMapStatus(
                MapStatusUpdateFactory.newLatLngBounds(bounds, fitW, fitH),
            )
            NavTrace.log(
                "FIT",
                "points=${pts.size}/${route.size} view=${mapSize.width}x${mapSize.height} " +
                    "fit=${fitW}x${fitH} applied=$applied zoom=${view.map.mapStatus?.zoom}",
            )
        }.onFailure {
            NavTrace.log("FIT", "failed ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    // P5 completeness: skipping new writes is not enough — an animateMapStatus started
    // just before navigation would still run for up to 450ms and fight the navi car
    // lock. Setting the current status once cancels any in-flight animation.
    LaunchedEffect(navActive) {
        if (!navActive) return@LaunchedEffect
        val view = mapHolder[0] ?: return@LaunchedEffect
        runCatching {
            val status = view.map.mapStatus ?: return@runCatching
            view.map.setMapStatus(MapStatusUpdateFactory.newMapStatus(status))
            NavTrace.log("CAM", "handover to sdk, cancel app camera zoom=${status.zoom}")
        }
    }

    // The navi engine applies its own "start view" roughly a second after onStart: it
    // zooms out and parks the car near the bottom. Auto-zoom is now disabled, so a single
    // write *after* it settles is enough — and that is what keeps the good driving zoom.
    LaunchedEffect(navActive) {
        if (!navActive) return@LaunchedEffect
        delay(1_200)
        // The user may have opened 总览 inside this window. That is the newer intent;
        // do not drag the camera back to the driving view (VA-F08).
        if (fullViewNow) {
            NavTrace.log("CAM", "skip start camera: overview active")
            return@LaunchedEffect
        }
        val view = mapHolder[0] ?: return@LaunchedEffect
        val target = mapTarget(latitude, longitude) ?: return@LaunchedEffect
        runCatching {
            view.map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(target, NAV_ZOOM))
            NavTrace.log("CAM", "nav start camera zoom=$NAV_ZOOM")
        }
        delay(2_500)
        val after = mapHolder[0]?.map?.mapStatus
        NavTrace.log("CAM", "nav camera +3.7s zoom=${after?.zoom} target=${after?.target}")
    }

    // User-initiated re-center. Two flavours:
    //  - the 归中 button resets the zoom to the default driving level, because coming back
    //    to the car while zoomed far out leaves the dot tiny and unreadable;
    //  - collapsing the map keeps the current zoom, so the home corner map does not jump
    //    (zoom continuity is a frozen product decision).
    LaunchedEffect(recenterKey) {
        if (recenterKey == 0) return@LaunchedEffect
        val view = mapHolder[0] ?: return@LaunchedEffect
        val target = mapTarget(latitude, longitude) ?: return@LaunchedEffect
        val defaultZoom = if (detail) detailZoom else HOME_MAP_ZOOM
        val zoom = if (recenterZoomReset) defaultZoom else (view.map.mapStatus?.zoom ?: defaultZoom)
        view.map.animateMapStatus(
            MapStatusUpdateFactory.newLatLngZoom(target, zoom),
            MAP_RECENTER_ANIM_MS,
        )
        NavTrace.log("CAM", "recenter key=$recenterKey zoom=$zoom reset=$recenterZoomReset")
    }

    // Navigation hands the shared native map engine back in the navi scene (3D/tilted,
    // navi palette, route highlighted). Re-assert the cruise map's own look after every
    // navigation session so the home and expanded map return to the flat 2D style.
    LaunchedEffect(resetKey) {
        if (resetKey == 0) return@LaunchedEffect
        // A new guide can start before this reset effect runs. Never let the old
        // session's reset pull the map back to the home camera over the new guide.
        if (navActiveNow) {
            NavTrace.log("MAP", "reset skipped: nav active")
            return@LaunchedEffect
        }
        val view = mapHolder[0] ?: return@LaunchedEffect
        val target = mapTarget(latitude, longitude) ?: return@LaunchedEffect
        lastFollowed[0] = null
        runCatching {
            view.onResume()
            view.map.setMapType(BaiduMap.MAP_TYPE_NORMAL)
            view.map.setBuildingsEnabled(false)
            // The navi scene has its own POI/traffic toggles; MapType alone does not
            // restore the browse map's base POIs.
            view.map.showMapPoi(true)
            view.map.setTrafficEnabled(false)
            val zoom = if (detail) detailZoom else HOME_MAP_ZOOM
            val status = MapStatus.Builder()
                .target(target)
                .zoom(zoom)
                .overlook(0f)
                .rotate(0f)
                .build()
            val applied = view.map.setMapStatus(MapStatusUpdateFactory.newMapStatus(status))
            NavTrace.log("MAP", "reset after nav applied=$applied zoom=$zoom target=$target")
            initialCameraSet = true
        }.onFailure {
            NavTrace.log("MAP", "reset failed ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    AndroidView(
        modifier = modifier
            .graphicsLayer { alpha = mapAlpha }
            .onSizeChanged { mapSize = it },
        // The AndroidView node is the guard FrameLayout, so destroy the map view we
        // actually created (a leaked TextureMapView keeps the native map context alive).
        onRelease = {
            createdView[0]?.let { mapView -> destroyMapOnce(mapView) }
            createdView[0] = null
            mapHolder[0] = null
        },
        factory = { ctx ->
            // TextureMapView is final, so wrap it: a parent's dispatchTouchEvent runs
            // before the map's own gesture detector, which is the only place early enough
            // to re-enable a flag the navi engine turned off.
            val gestureLogAt = longArrayOf(0L)
            // After a user drag during guidance, glide back to the car. The navi engine
            // does not resume its own car-lock once we have taken the camera over, so the
            // return has to be ours. Cancelled as soon as the user touches again.
            fun scheduleNaviRecenter() {
                val gen = ++dragGen[0]
                mainHandler.postDelayed({
                    if (gen != dragGen[0] || !navNow[0]) return@postDelayed
                    val v = mapHolder[0] ?: return@postDelayed
                    val t = posNow[0] ?: return@postDelayed
                    val zoom = v.map.mapStatus?.zoom ?: 18f
                    v.map.animateMapStatus(
                        MapStatusUpdateFactory.newLatLngZoom(t, zoom),
                        MAP_RECENTER_ANIM_MS,
                    )
                    NavTrace.log("CAM", "auto recenter after drag")
                }, NAV_DRAG_RECENTER_MS)
            }
            val guard = object : FrameLayout(ctx) {
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    val mapView = mapHolder[0]
                    if (mapView != null) {
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                dragGen[0]++
                                dragX[0] = ev.x
                                dragY[0] = ev.y
                                selfPan[0] = false
                                downTarget[0] = mapView.map.mapStatus?.target
                                val settings = mapView.map.uiSettings
                                val wasEnabled = settings.isScrollGesturesEnabled
                                if (!wasEnabled && gesturesOn[0]) {
                                    settings.isScrollGesturesEnabled = true
                                    settings.isZoomGesturesEnabled = true
                                    settings.isRotateGesturesEnabled = true
                                }
                                val now = System.currentTimeMillis()
                                if (now - gestureLogAt[0] > 2_000) {
                                    gestureLogAt[0] = now
                                    NavTrace.log(
                                        "GESTURE",
                                        "down scrollWas=$wasEnabled forced=${gesturesOn[0]} " +
                                            "now=${settings.isScrollGesturesEnabled}",
                                    )
                                }
                            }
                            MotionEvent.ACTION_MOVE -> if (ev.pointerCount == 1 && navNow[0]) {
                                val dx = ev.x - dragX[0]
                                val dy = ev.y - dragY[0]
                                if (!selfPan[0] && kotlin.math.hypot(dx, dy) > PAN_SLOP_PX) {
                                    // Did the engine move the camera itself? If not, the
                                    // guide mode is swallowing pans and we take over.
                                    val before = downTarget[0]
                                    val nowTarget = mapView.map.mapStatus?.target
                                    val engineMoved = before != null && nowTarget != null &&
                                        (kotlin.math.abs(nowTarget.latitude - before.latitude) > 1e-7 ||
                                            kotlin.math.abs(nowTarget.longitude - before.longitude) > 1e-7)
                                    if (!engineMoved) {
                                        selfPan[0] = true
                                        // Keep the evidence: if this line shows up and the
                                        // map still does not move, the guide mode is
                                        // ignoring camera writes from this view.
                                        NavTrace.log("GESTURE", "engine ignored pan, taking over")
                                    }
                                }
                                if (selfPan[0]) {
                                    runCatching {
                                        val projection = mapView.map.projection
                                        val pt = Point(
                                            (mapView.width / 2 - dx).toInt(),
                                            (mapView.height / 2 - dy).toInt(),
                                        )
                                        mapView.map.setMapStatus(
                                            MapStatusUpdateFactory.newLatLng(
                                                projection.fromScreenLocation(pt),
                                            ),
                                        )
                                        dragX[0] = ev.x
                                        dragY[0] = ev.y
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (selfPan[0] && navNow[0]) scheduleNaviRecenter()
                                selfPan[0] = false
                            }
                        }
                    }
                    return super.dispatchTouchEvent(ev)
                }
            }
            val view = TextureMapView(ctx)
            NavTrace.log("MAPVIEW", "create id=${System.identityHashCode(view)}")
            createdView[0] = view
            guard.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            view.apply {
                val mapView = this
                onCreate(ctx, null)
                map.setOnMapLoadedCallback {
                    NavTrace.log("MAPVIEW", "loaded id=${System.identityHashCode(view)}")
                }
                // A re-created map starts at the SDK default centre (Beijing); park it on
                // the car immediately instead of waiting for the reveal effects.
                mapTarget(latitude, longitude)?.let { target ->
                    runCatching {
                        map.setMapStatus(
                            MapStatusUpdateFactory.newLatLngZoom(
                                target,
                                if (detail) detailZoom else HOME_MAP_ZOOM,
                            ),
                        )
                    }
                }
                showZoomControls(false)
                showScaleControl(false)
                setLogoPosition(LogoPosition.logoPostionCenterBottom)
                map.apply {
                    setMapType(BaiduMap.MAP_TYPE_NORMAL)
                    setTrafficEnabled(false)
                    setBuildingsEnabled(false)
                    uiSettings.isCompassEnabled = false
                    uiSettings.isOverlookingGesturesEnabled = false
                    setInteractive(uiSettings, interactive)
                    // Second line of defence: also re-assert on the map's own gesture
                    // callback (runs later, but covers the case where the flag is flipped
                    // between dispatch and detection).
                    setOnMapTouchListener { event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN && gesturesOn[0]) {
                            uiSettings.isScrollGesturesEnabled = true
                            uiSettings.isZoomGesturesEnabled = true
                            uiSettings.isRotateGesturesEnabled = true
                        }
                    }
                    setOnFirstFrameDrawListener {
                        if (firstFrameSeen.compareAndSet(false, true)) {
                            post {
                                firstFrameNow.set(true)
                                firstFrame = true
                                scheduleSnapshot(mapView, 250)
                            }
                        }
                    }
                    setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
                        override fun onMapStatusChangeStart(status: MapStatus) {
                            cameraMoving.set(true)
                            pendingSnapshot[0]?.let { mapView.removeCallbacks(it) }
                            pendingSnapshot[0] = null
                        }

                        override fun onMapStatusChangeStart(status: MapStatus, reason: Int) {
                            cameraMoving.set(true)
                            pendingSnapshot[0]?.let { mapView.removeCallbacks(it) }
                            pendingSnapshot[0] = null
                        }

                        override fun onMapStatusChange(status: MapStatus) = Unit
                        override fun onMapStatusChangeFinish(status: MapStatus) {
                            cameraMoving.set(false)
                            scheduleSnapshot(mapView, 250)
                        }
                    })
                }
                mapHolder[0] = this
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onResume()
            }
            guard
        },
        update = { _ ->
            val view = mapHolder[0] ?: return@AndroidView
            gesturesOn[0] = interactive
            navNow[0] = navActive
            posNow[0] = mapTarget(latitude, longitude)
            setInteractive(view.map.uiSettings, interactive)
            hasPositionNow.set(hasPosition)

            // Route plan points are already bd09; do NOT re-convert via mapTarget.
            // Reconcile the route before the position early-return: a planned route is
            // independent of the current fix, and an old line must not survive after
            // the route becomes empty just because hasPosition is false.
            val rawRoutePts = route.map { LatLng(it.lat, it.lng) }
            val routePts = rawRoutePts.filter {
                it.latitude.isFinite() && it.longitude.isFinite() &&
                    it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
            }
            // Route fit lives in its own effect (keyed on the measured view size) —
            // running it here read the pre-layout viewport and cropped the first route.
            val poly = routeHolder[0]
            if (routePts.size >= 2) {
                if (poly == null) {
                    routeHolder[0] = view.map.addOverlay(
                        PolylineOptions()
                            .points(routePts)
                            .width(routeWidth)
                            .color(colors.mint.toArgb()),
                    ) as? Polyline
                } else {
                    poly.points = routePts
                }
            } else if (poly != null) {
                poly.remove()
                routeHolder[0] = null
            }

            val target = mapTarget(latitude, longitude)
            val marker = markerHolder[0]
            if (!hasPosition || target == null) {
                // A stale car marker is worse than no marker: remove it as soon as the
                // position is no longer usable instead of leaving it on the map.
                marker?.remove()
                markerHolder[0] = null
                return@AndroidView
            }
            if (marker == null) {
                markerHolder[0] = view.map.addOverlay(
                    MarkerOptions()
                        .position(target)
                        .icon(markerIcon)
                        .anchor(0.5f, 0.5f)
                        .zIndex(10)
                        .flat(true),
                ) as? Marker
            } else {
                marker.position = target
            }
        },
    )
}

private fun setInteractive(settings: UiSettings, interactive: Boolean) {
    settings.isScrollGesturesEnabled = interactive
    settings.isZoomGesturesEnabled = interactive
    settings.isRotateGesturesEnabled = interactive
    settings.setDoubleClickZoomEnabled(interactive)
    settings.setTwoTouchClickZoomEnabled(interactive)
    settings.isOverlookingGesturesEnabled = false
}

private fun mapTarget(latitude: Double, longitude: Double): LatLng? {
    if (latitude == 0.0 && longitude == 0.0) return null
    val (bdLat, bdLng) = CoordUtil.wgs84ToBd09(latitude, longitude)
    if (!bdLat.isFinite() || !bdLng.isFinite()) return null
    return LatLng(bdLat, bdLng)
}

private fun locationDot(context: Context, color: Int): Bitmap {
    val size = (36 * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
    val bitmap = createBitmap(size, size)
    val canvas = AndroidCanvas(bitmap)
    val center = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = ColorUtils.setAlphaComponent(color, 48)
    canvas.drawCircle(center, center, center, paint)
    paint.color = color
    canvas.drawCircle(center, center, center * 0.54f, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = center * 0.18f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, center * 0.54f, paint)
    return bitmap
}

@Composable
fun MapChrome(
    speedKmh: Int,
    over: Boolean,
    limit: Int?,
    trafficLight: TrafficLightInfo?,
    showSpeed: Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSlmColors.current
    val floatGap by animateDpAsState(if (limit != null) 14.dp else 0.dp, tween(450))
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CollapseButton(onClose)
            Spacer(Modifier.weight(1f))
            Text(
                "搜索目的地",
                color = colors.text,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onOpenSearch)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
            SettingsButton(onClick = onOpenSettings, size = 42.dp)
        }

        TrafficLightCapsule(
            info = trafficLight,
            compact = true,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp),
        )

        // Bottom-left readout. It doubles as "close the map", so it must never render an
        // empty shell: with no live speed and no known limit the whole card is omitted
        // and the top-left collapse button remains the way back.
        if (showSpeed || limit != null) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 18.dp, bottom = 18.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSpeed) {
                    Column {
                        Text(
                            speedKmh.toString(),
                            color = if (over) colors.red else colors.text,
                            fontSize = 36.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            "KM/H",
                            color = colors.dim,
                            fontSize = 9.5.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(floatGap))
                }
                MiniLimitSign(limit, size = 46.dp)
            }
        }

        // The legend describes the decorative fallback map; the live SDK map has no
        // route/camera/sign overlays, so keeping it would be misleading.
        if (!BaiduMapSdk.available) {
            MapLegend(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 18.dp),
            )
        } else {
            // One-tap "back to my position". Lives opposite the speed card so it never
            // collides with the collapse-on-tap readout.
            LocateButton(
                onClick = onRecenter,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun LocateButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSlmColors.current
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val r = size.minDimension / 2f
            val c = center
            val stroke = 2.2.dp.toPx()
            drawCircle(color = colors.mint, radius = r * 0.42f, center = c, style = Stroke(stroke))
            drawCircle(color = colors.mint, radius = r * 0.10f, center = c)
            // Four ticks, like a crosshair.
            listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
                drawLine(
                    color = colors.mint,
                    start = Offset(c.x + dx * r * 0.62f, c.y + dy * r * 0.62f),
                    end = Offset(c.x + dx * r * 0.98f, c.y + dy * r * 0.98f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun CollapseButton(onClose: () -> Unit) {
    val colors = LocalSlmColors.current
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surface)
            .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.62f, size.height * 0.2f)
                lineTo(size.width * 0.35f, size.height * 0.5f)
                lineTo(size.width * 0.62f, size.height * 0.8f)
            }
            drawPath(
                path,
                colors.text,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
private fun MapLegend(modifier: Modifier = Modifier) {
    val colors = LocalSlmColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, colors.text.copy(alpha = 0.08f), RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LegendRow(colors.mint, "当前路线", ring = false)
        LegendRow(colors.red, "电子眼测速", ring = false)
        LegendRow(Color.White, "限速牌", ring = true)
    }
}

@Composable
private fun LegendRow(color: Color, label: String, ring: Boolean) {
    val colors = LocalSlmColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
                .then(if (ring) Modifier.border(2.dp, colors.sign, RoundedCornerShape(3.dp)) else Modifier),
        )
        Text(label, color = colors.muted, fontSize = 11.sp)
    }
}