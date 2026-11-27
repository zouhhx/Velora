package com.zouhu.speedlimit.probe

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.PoiInfo
import com.baidu.mapapi.search.poi.OnGetPoiSearchResultListener
import com.baidu.mapapi.search.poi.PoiCitySearchOption
import com.baidu.mapapi.search.poi.PoiDetailResult
import com.baidu.mapapi.search.poi.PoiDetailSearchResult
import com.baidu.mapapi.search.poi.PoiIndoorResult
import com.baidu.mapapi.search.poi.PoiResult
import com.baidu.mapapi.search.poi.PoiSearch
import com.baidu.mapapi.search.sug.OnGetSuggestionResultListener
import com.baidu.mapapi.search.sug.SuggestionResult
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.BNRoutePlanNode
import com.baidu.navisdk.adapter.BNaviCommonParams
import com.baidu.navisdk.adapter.IBaiduNaviManager
import com.baidu.navisdk.adapter.IBNRouteGuideManager
import com.baidu.navisdk.adapter.IBNRoutePlanManager
import com.baidu.navisdk.adapter.IBNaviListener
import com.baidu.navisdk.adapter.IBNaviViewListener
import com.baidu.navisdk.adapter.struct.BNCameraInfo
import com.baidu.navisdk.adapter.struct.BNGuideConfig
import com.baidu.navisdk.adapter.struct.BNRoadCondition
import com.baidu.navisdk.adapter.struct.BNTrafficLight
import com.baidu.navisdk.adapter.struct.BNavLineItem
import com.baidu.navisdk.adapter.struct.BNaviInfo
import com.baidu.navisdk.adapter.struct.BNaviInitConfig
import com.baidu.navisdk.adapter.struct.BNaviLocation
import com.baidu.navisdk.adapter.struct.GuidePanelMessage
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProbeActivity : AppCompatActivity() {

    private lateinit var logger: ProbeLog
    private lateinit var mapHost: FrameLayout
    private lateinit var guideLayer: FrameLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var mapView: TextureMapView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mapReady = false
    private var naviInited = false
    private var authOk = false
    private var routeReady = false
    private var guideActive = false
    private var guideManager: IBNRouteGuideManager? = null
    private var guideAddMap: Boolean? = null
    private var poiSearch: PoiSearch? = null
    private var suggestionSearch: SuggestionSearch? = null
    private var lastPoi: PoiInfo? = null
    private var lastScreenshot: File? = null
    private var memoryTask: Runnable? = null
    private var snapshotSeq = 0
    private var lastSpeed = Int.MIN_VALUE
    private var lastLimit = Int.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val probeDir = (getExternalFilesDir("probe") ?: File(filesDir, "probe")).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        logger = ProbeLog(File(probeDir, "velora-probe-$stamp.log"))
        setContentView(buildLayout())
        logger.attach(logView, logScroll)
        installCrashHandler()
        logEnvironment()
        initSdk()
        startMemorySampler()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        if (guideActive) runCatching { guideManager?.onResume() }
            .onFailure { logger.logThrowable("VIEW", it) }
    }

    override fun onPause() {
        if (guideActive) runCatching { guideManager?.onPause() }
            .onFailure { logger.logThrowable("VIEW", it) }
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        memoryTask?.let { mainHandler.removeCallbacks(it) }
        if (guideActive) {
            runCatching {
                guideManager?.onPause()
                guideManager?.onStop()
                guideManager?.onDestroy(false)
            }.onFailure { logger.logThrowable("VIEW", it) }
            guideActive = false
        }
        runCatching { poiSearch?.destroy() }
        runCatching { suggestionSearch?.destroy() }
        if (::mapView.isInitialized) runCatching { mapView.onDestroy() }
        logger.log("LIFE", "onDestroy")
        logger.close()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 16))
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }
        val title = TextView(this).apply {
            text = "Velora Phase 0 探针"
            setTextColor(Color.WHITE)
            textSize = 19f
            gravity = Gravity.START
        }
        statusView = TextView(this).apply {
            text = "地图:…  Navi:…  auth:…  路线:…"
            setTextColor(Color.rgb(140, 220, 190))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(6))
        }
        mapHost = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(24, 26, 28))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.25f,
            )
        }
        guideLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        mapHost.addView(guideLayer)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        controls.addView(buttonRow(
            button("快照") { captureSnapshot("manual") },
            button("搜索") { runSearch() },
            button("算路") { planRoute() },
        ))
        controls.addView(buttonRow(
            button("A 专业导航") { startGuide(true) },
            button("B 无地图") { startGuide(false) },
            button("停止") { stopGuide() },
        ))
        controls.addView(buttonRow(
            button("截屏") { captureScreen("manual") },
            button("分享截图") { shareLatestScreenshot() },
            button("分享日志") { shareLog() },
        ))

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(Color.rgb(16, 18, 20))
        }
        logView = TextView(this).apply {
            setTextColor(Color.rgb(190, 200, 200))
            textSize = 9.5f
            setPadding(dp(4), dp(4), dp(4), dp(40))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll.addView(logView)

        root.addView(title)
        root.addView(statusView)
        root.addView(mapHost)
        root.addView(controls)
        root.addView(logScroll)
        return root
    }

    private fun buttonRow(vararg buttons: Button): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@ProbeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it) }
            })
        }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { action() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = dp(6)
            layoutParams = params
        }

    private fun logEnvironment() {
        logger.log("ENV", "brand=${Build.BRAND} model=${Build.MODEL} device=${Build.DEVICE}")
        logger.log("ENV", "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString()}")
        logger.log("ENV", "app=${packageName} version=${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()}")
        logger.log("ENV", "mapAkPresent=${com.zouhu.speedlimit.BuildConfig.BAIDU_MAP_ENABLED}")
        logger.log("ENV", "signerSha1=${signerSha1()}")
        val apk = runCatching { File(packageManager.getApplicationInfo(packageName, 0).sourceDir).length() }.getOrDefault(0L)
        logger.log("ENV", "apkBytes=$apk (${apk / 1024 / 1024} MB)")
        logger.log("ENV", "logFile=${logger.file.absolutePath}")
    }

    private fun signerSha1(): String = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = packageManager.getPackageInfo(packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val bytes = signatures?.firstOrNull()?.toByteArray() ?: return "none"
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString(":") { "%02X".format(it) }
    }.getOrElse { "error:${it.javaClass.simpleName}" }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logger.log("CRASH", "thread=${thread.name}")
                logger.logThrowable("CRASH", throwable)
                logger.flush()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun initSdk() {
        logger.log("INIT", "setAgreePrivacy + SDKInitializer.initialize")
        runCatching {
            SDKInitializer.setAgreePrivacy(applicationContext, true)
            SDKInitializer.initialize(applicationContext)
        }.onFailure { logger.logThrowable("INIT", it) }
        logger.log("INIT", "mapInitialized=${SDKInitializer.isInitialized()}")
        initMap()
        initNavi()
    }

    private fun initMap() {
        mapView = runCatching { TextureMapView(this) }.getOrElse {
            logger.logThrowable("MAP", it)
            return
        }
        runCatching { mapView.onCreate(this, null) }
        mapView.showZoomControls(false)
        mapView.showScaleControl(false)
        mapHost.addView(
            mapView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        runCatching {
            mapView.map.setOnFirstFrameDrawListener(
                BaiduMap.OnFirstFrameDrawListener {
                    mapReady = true
                    logger.log("MAP", "onFirstFrameDraw")
                    updateStatus()
                    mainHandler.postDelayed({ captureSnapshot("firstframe") }, 800)
                },
            )
        }.onFailure { logger.logThrowable("MAP", it) }
        runCatching {
            mapView.map.setMapStatus(
                MapStatusUpdateFactory.newLatLngZoom(LatLng(39.9088, 116.3975), 16f),
            )
        }.onFailure { logger.logThrowable("MAP", it) }
        logger.log("MAP", "TextureMapView created, camera=39.9088,116.3975 z16")
        mainHandler.postDelayed({ captureSnapshot("t+2500") }, 2_500)
        mainHandler.postDelayed({ captureSnapshot("t+6000") }, 6_000)
    }

    private fun initNavi() {
        val naviRoot = File(getExternalFilesDir(null) ?: filesDir, "BaiduNaviSDK").apply { mkdirs() }
        logger.log("NAVI", "init sdcardRoot=${naviRoot.absolutePath}")
        val listener = object : IBaiduNaviManager.INaviInitListener {
            override fun onAuthResult(status: Int, msg: String?) {
                authOk = status == 0
                logger.log("NAVI", "onAuthResult status=$status msg=$msg")
                updateStatus()
            }

            override fun initStart() {
                logger.log("NAVI", "initStart")
            }

            override fun initSuccess() {
                naviInited = true
                val version = runCatching {
                    BaiduNaviManagerFactory.getBaiduNaviManager().getSDKVersion()
                }.getOrNull()
                logger.log("NAVI", "initSuccess sdkVersion=$version")
                runCatching {
                    BaiduNaviManagerFactory.getCommonSettingManager().setMultiRouteEnable(true)
                }.onFailure { logger.logThrowable("NAVI", it) }
                updateStatus()
            }

            override fun initFailed(errorCode: Int) {
                logger.log("NAVI", "initFailed errorCode=$errorCode")
                updateStatus()
            }
        }
        runCatching {
            val config = BNaviInitConfig.Builder()
                .sdcardRootPath(naviRoot.absolutePath)
                .naviInitListener(listener)
                .build()
            BaiduNaviManagerFactory.getBaiduNaviManager().enableOutLog(true)
            BaiduNaviManagerFactory.getBaiduNaviManager().init(applicationContext, config)
        }.onFailure { logger.logThrowable("NAVI", it) }
    }

    private fun updateStatus() {
        statusView.text = buildString {
            append("地图:").append(if (mapReady) "ready" else "…")
            append("  Navi:").append(if (naviInited) "ready" else "…")
            append("  auth:").append(if (authOk) "ok" else "…")
            append("  路线:").append(if (routeReady) "ready" else "…")
        }
    }

    private fun captureSnapshot(reason: String) {
        if (!::mapView.isInitialized) return
        val map = runCatching { mapView.map }.getOrNull() ?: return
        val seq = ++snapshotSeq
        val started = SystemClock.elapsedRealtime()
        logger.log("SNAP", "request #$seq reason=$reason")
        runCatching {
            map.snapshot { bitmap ->
                val elapsed = SystemClock.elapsedRealtime() - started
                if (bitmap == null) {
                    logger.log("SNAP", "#$seq null after ${elapsed}ms")
                } else {
                    logger.log(
                        "SNAP",
                        "#$seq ok ${bitmap.width}x${bitmap.height} config=${bitmap.config} " +
                            "bytes=${bitmap.byteCount} after ${elapsed}ms",
                    )
                    saveBitmap(bitmap, "snapshot-$seq-$reason.png")
                }
            }
        }.onFailure {
            logger.logThrowable("SNAP", it)
        }
    }

    private fun runSearch() {
        runCatching { poiSearch?.destroy() }
        runCatching { suggestionSearch?.destroy() }
        logger.log("SEARCH", "POI city=北京 keyword=北京南站")
        val search = runCatching { PoiSearch.newInstance() }.getOrElse {
            logger.logThrowable("SEARCH", it)
            return
        }
        poiSearch = search
        search.setOnGetPoiSearchResultListener(object : OnGetPoiSearchResultListener {
            override fun onGetPoiResult(result: PoiResult?) {
                if (result == null) {
                    logger.log("SEARCH", "POI result=null")
                    return
                }
                val pois = result.allPoi.orEmpty()
                logger.log(
                    "SEARCH",
                    "POI error=${result.error} status=${result.status} total=${result.totalPoiNum} " +
                        "page=${result.currentPageNum}/${result.totalPageNum} count=${pois.size}",
                )
                pois.take(8).forEachIndexed { index, poi ->
                    logger.log(
                        "SEARCH",
                        "POI[$index] name=${poi.name} uid=${poi.uid} addr=${poi.address} " +
                            "loc=${poi.location?.latitude},${poi.location?.longitude} dist=${poi.distance}",
                    )
                }
                lastPoi = pois.firstOrNull { it.location != null }
                lastPoi?.let {
                    logger.log("SEARCH", "route end candidate=${it.name} uid=${it.uid}")
                }
            }

            override fun onGetPoiDetailResult(result: PoiDetailResult?) {
                logger.log("SEARCH", "POI detail(legacy) error=${result?.error}")
            }

            override fun onGetPoiDetailResult(result: PoiDetailSearchResult?) {
                logger.log("SEARCH", "POI detail error=${result?.error}")
            }

            override fun onGetPoiIndoorResult(result: PoiIndoorResult?) {
                logger.log("SEARCH", "POI indoor error=${result?.error}")
            }
        })
        val poiStarted = runCatching {
            search.searchInCity(
                PoiCitySearchOption().city("北京").keyword("北京南站").pageNum(0).pageCapacity(10),
            )
        }.onFailure { logger.logThrowable("SEARCH", it) }.getOrDefault(false)
        logger.log("SEARCH", "searchInCity returned=$poiStarted")

        val suggestion = runCatching { SuggestionSearch.newInstance() }.getOrNull() ?: return
        suggestionSearch = suggestion
        suggestion.setOnGetSuggestionResultListener(object : OnGetSuggestionResultListener {
            override fun onGetSuggestionResult(result: SuggestionResult?) {
                val list = result?.allSuggestions.orEmpty()
                logger.log(
                    "SEARCH",
                    "SUG error=${result?.error} status=${result?.status} count=${list.size}",
                )
                list.take(8).forEachIndexed { index, item ->
                    logger.log(
                        "SEARCH",
                        "SUG[$index] key=${item.key} city=${item.city} district=${item.district} " +
                            "uid=${item.uid} loc=${item.pt?.latitude},${item.pt?.longitude}",
                    )
                }
            }
        })
        val sugStarted = runCatching {
            suggestion.requestSuggestion(
                SuggestionSearchOption().city("北京").keyword("北京南").citylimit(true),
            )
        }.onFailure { logger.logThrowable("SEARCH", it) }.getOrDefault(false)
        logger.log("SEARCH", "requestSuggestion returned=$sugStarted")
    }

    private fun planRoute() {
        if (!naviInited) {
            logger.log("ROUTE", "navi not initialized yet")
            return
        }
        if (guideActive) stopGuide()
        routeReady = false
        updateStatus()
        val endLatLng = lastPoi?.location ?: LatLng(39.8652, 116.3786)
        val endUid = lastPoi?.uid
        val startNode = BNRoutePlanNode.Builder()
            .name("探针起点")
            .description("探针起点")
            .latitude(39.9088)
            .longitude(116.3975)
            .build()
        val endBuilder = BNRoutePlanNode.Builder()
            .name(lastPoi?.name ?: "探针终点")
            .description("探针终点")
            .latitude(endLatLng.latitude)
            .longitude(endLatLng.longitude)
        if (!endUid.isNullOrBlank()) endBuilder.id(endUid)
        val endNode = endBuilder.build()
        logger.log(
            "ROUTE",
            "plan start=${startNode.getLatitude()},${startNode.getLongitude()} " +
                "end=${endNode.getLatitude()},${endNode.getLongitude()} uid=$endUid multi=true",
        )
        val accepted = runCatching {
            BaiduNaviManagerFactory.getRoutePlanManager().routePlan(
                listOf(startNode, endNode),
                IBNRoutePlanManager.RoutePlanPreference.ROUTE_PLAN_PREFERENCE_DEFAULT,
                Bundle(),
                routeHandler,
            )
        }.onFailure { logger.logThrowable("ROUTE", it) }.getOrDefault(false)
        logger.log("ROUTE", "routePlan returned=$accepted")
    }

    private val routeHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_START ->
                    logger.log("ROUTE", "MSG_START arg1=${msg.arg1} arg2=${msg.arg2} obj=${msg.obj}")

                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_SUCCESS -> {
                    logger.log("ROUTE", "MSG_SUCCESS arg1=${msg.arg1} arg2=${msg.arg2} obj=${msg.obj}")
                    routeReady = true
                    updateStatus()
                    logRoutePlanInfo("success")
                    mainHandler.postDelayed({ logRoutePlanInfo("success+1500") }, 1_500)
                }

                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_FAILED -> {
                    logger.log("ROUTE", "MSG_FAILED arg1=${msg.arg1} arg2=${msg.arg2} obj=${msg.obj}")
                    routeReady = false
                    updateStatus()
                }

                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_CANCELED ->
                    logger.log("ROUTE", "MSG_CANCELED arg1=${msg.arg1} obj=${msg.obj}")

                IBNRoutePlanManager.MSG_NAVI_ROUTE_PLAN_TO_NAVI -> {
                    logger.log("ROUTE", "MSG_TO_NAVI arg1=${msg.arg1} obj=${msg.obj}")
                    routeReady = true
                    updateStatus()
                }

                else -> logger.log("ROUTE", "msg what=${msg.what} arg1=${msg.arg1} obj=${msg.obj}")
            }
        }
    }

    private fun logRoutePlanInfo(stage: String) {
        val info = runCatching {
            BaiduNaviManagerFactory.getRoutePlanManager().getRoutePlanInfo()
        }.getOrNull()
        if (info == null) {
            logger.log("ROUTE", "$stage info=null")
            return
        }
        val tabs = info.routeTabInfos.orEmpty()
        logger.log(
            "ROUTE",
            "$stage tabs=${tabs.size} cities=${info.cityLists?.size} details=${info.routeDetailLists?.size} " +
                "pathSets=${info.routeInfoLatLngLists?.size} limits=${info.limitInfo}",
        )
        tabs.take(3).forEachIndexed { index, tab ->
            logger.log(
                "ROUTE",
                "$stage tab[$index] length=${tab.length}(${tab.lengthStr}) pass=${tab.passTime}(${tab.passTimeStr}) " +
                    "lights=${tab.lights} toll=${tab.toll} label=${tab.pusLabelName}",
            )
        }
        info.routeDetailLists.orEmpty().take(3).forEachIndexed { index, steps ->
            logger.log("ROUTE", "$stage detail[$index] steps=${steps?.size}")
            steps?.take(8)?.forEachIndexed { stepIndex, step ->
                logger.log(
                    "ROUTE",
                    "$stage detail[$index][$stepIndex] idx=${step.index} icon=${step.iconId} " +
                        "desc=${step.description} dist=${step.distance} cond=${step.roadConditions?.size}",
                )
            }
        }
        info.routeInfoLatLngLists.orEmpty().take(3).forEachIndexed { index, paths ->
            val points = paths?.flatten().orEmpty()
            logger.log(
                "ROUTE",
                "$stage path[$index] segments=${paths?.size} points=${points.size} " +
                    "first=${points.firstOrNull()} last=${points.lastOrNull()}",
            )
        }
    }

    private fun startGuide(addMap: Boolean) {
        if (!naviInited) {
            logger.log("GUIDE", "navi not initialized yet")
            return
        }
        if (!routeReady) {
            logger.log("GUIDE", "route not ready; press 算路 first")
            return
        }
        if (guideActive) stopGuide()
        val manager = runCatching { BaiduNaviManagerFactory.getRouteGuideManager() }.getOrElse {
            logger.logThrowable("GUIDE", it)
            return
        }
        guideManager = manager
        guideAddMap = addMap
        logger.log("GUIDE", "start ADD_MAP=$addMap IS_REALNAVI=false (simulated)")
        runCatching {
            val bundle = Bundle().apply {
                putBoolean(BNaviCommonParams.ProGuideKey.ADD_MAP, addMap)
                putBoolean(BNaviCommonParams.ProGuideKey.IS_REALNAVI, false)
                putBoolean(BNaviCommonParams.ProGuideKey.IS_SUPPORT_FULL_SCREEN, false)
            }
            val config = BNGuideConfig.Builder().params(bundle).build()
            val guideView = manager.onCreate(this, config)
            if (guideView == null) {
                logger.log("GUIDE", "onCreate returned null")
                return
            }
            logger.log(
                "GUIDE",
                "onCreate view=${guideView.javaClass.name} size=${guideView.width}x${guideView.height}",
            )
            guideLayer.removeAllViews()
            guideLayer.addView(
                guideView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            manager.setNaviListener(naviListener)
            manager.setNaviViewListener(viewListener)
            manager.setTrafficLightListener(trafficLightListener)
            guideActive = true
            manager.onStart()
            manager.onResume()
            if (addMap) {
                mapView.visibility = View.GONE
                mapView.onPause()
            } else {
                mapView.visibility = View.VISIBLE
                mapView.onResume()
            }
            logger.log("GUIDE", "onCreate→onStart→onResume done")
            mainHandler.postDelayed({
                inspectGuide(if (addMap) "A" else "B", addMap)
                captureScreen(if (addMap) "A" else "B")
                captureSnapshot(if (addMap) "A" else "B")
            }, 2_500)
        }.onFailure { logger.logThrowable("GUIDE", it) }
    }

    private fun stopGuide() {
        if (!guideActive) {
            logger.log("GUIDE", "stop ignored (not active)")
            return
        }
        logger.log("GUIDE", "stop active addMap=$guideAddMap")
        guideActive = false
        runCatching {
            guideManager?.onPause()
            guideManager?.onStop()
            guideManager?.onDestroy(false)
        }.onFailure { logger.logThrowable("GUIDE", it) }
        guideLayer.removeAllViews()
        guideAddMap = null
        if (::mapView.isInitialized) {
            mapView.visibility = View.VISIBLE
            mapView.onResume()
        }
        logger.log("GUIDE", "destroyed; our TextureMapView restored")
    }

    private fun inspectGuide(tag: String, addMap: Boolean) {
        val root = guideLayer.getChildAt(0)
        logger.log(
            "GUIDE",
            "[$tag] addMap=$addMap guideRoot=${root?.javaClass?.simpleName} " +
                "size=${root?.width}x${root?.height} shown=${root?.isShown} alpha=${root?.alpha} " +
                "background=${(root?.background as? ColorDrawable)?.color}",
        )
        if (root != null) {
            val counts = LinkedHashMap<String, Int>()
            countViews(root, counts)
            logger.log("GUIDE", "[$tag] viewTypes=$counts")
        }
        val naviMap = runCatching { BaiduNaviManagerFactory.getMapManager().getMapView() }.getOrNull()
        logger.log(
            "GUIDE",
            "[$tag] mapManager.getMapView=${naviMap?.javaClass?.name} attached=${naviMap?.isAttachedToWindow} " +
                "shown=${naviMap?.isShown} size=${naviMap?.width}x${naviMap?.height} " +
                "parent=${naviMap?.parent?.javaClass?.simpleName}",
        )
        logger.log(
            "GUIDE",
            "[$tag] ourTextureMap attached=${mapView.isAttachedToWindow} shown=${mapView.isShown} " +
                "visibility=${mapView.visibility} size=${mapView.width}x${mapView.height}",
        )
    }

    private fun countViews(view: View, counts: MutableMap<String, Int>) {
        counts.merge(view.javaClass.simpleName, 1, Int::plus)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) countViews(view.getChildAt(index), counts)
        }
    }

    private val naviListener = object : IBNaviListener() {
        override fun onRoadNameUpdate(name: String?) {
            logger.log("DATA", "roadName=$name")
        }

        override fun onRemainInfoUpdate(remainDistance: Int, remainTime: Int) {
            logger.log("DATA", "remain dist=$remainDistance time=$remainTime")
        }

        override fun onGuideInfoUpdate(info: BNaviInfo?, panel: GuidePanelMessage?) {
            if (info == null) {
                logger.log("DATA", "guideInfo=null")
                return
            }
            val icon = info.getTurnIcon()
            logger.log(
                "DATA",
                "guide road=${info.getRoadName()} dist=${info.getDistance()} " +
                    "turn=${info.getTurnIconName()} fuzzy=${info.getIsFuzzy()} " +
                    "icon=${icon?.width}x${icon?.height} panel=${panel?.stringBuilder}",
            )
        }

        override fun onLaneInfoUpdate(action: IBNaviListener.Action?, items: MutableList<BNavLineItem>?) {
            val names = items.orEmpty().map { it.getResName() }
            logger.log("DATA", "lane action=$action count=${items?.size} res=${names.joinToString(",")}")
        }

        override fun onSpeedUpdate(speed: Int, speedLimit: Int) {
            if (speed != lastSpeed || speedLimit != lastLimit) {
                lastSpeed = speed
                lastLimit = speedLimit
                logger.log("DATA", "speed=$speed limit=$speedLimit")
            }
        }

        override fun onOverSpeed(speed: Int, speedLimit: Int) {
            logger.log("DATA", "overspeed speed=$speed limit=$speedLimit")
        }

        override fun onCameraInfoChanged(list: MutableList<BNCameraInfo>?) {
            logger.log("DATA", "cameras count=${list?.size}")
            list.orEmpty().take(12).forEachIndexed { index, camera ->
                logger.log(
                    "DATA",
                    "camera[$index] type=${camera.getType()} dist=${camera.getDist()} " +
                        "priority=${camera.getPriority()} icon=${camera.getIconId()} " +
                        "limit=${camera.getSpeedLimit()} loc=${camera.getLatLng()}",
                )
            }
        }

        override fun onRoadConditionInfoUpdate(progress: Double, conditions: MutableList<BNRoadCondition>?) {
            val types = conditions.orEmpty().groupingBy { it.getType() }.eachCount()
            logger.log("DATA", "roadCondition progress=$progress count=${conditions?.size} types=$types")
        }

        override fun onLocationChange(location: BNaviLocation?) {
            if (location == null) return
            logger.log(
                "DATA",
                "location=${location.getLatitude()},${location.getLongitude()} " +
                    "speed=${location.getSpeed()} dir=${location.getDirection()} " +
                    "acc=${location.getAccuracy()} sats=${location.getSatellitesNum()}",
            )
        }

        override fun onRemainLightsUpdate(remainLights: Int, remainTime: Int) {
            logger.log("DATA", "remainLights=$remainLights remainTime=$remainTime")
        }

        override fun onArriveDestination() {
            logger.log("DATA", "arriveDestination")
        }

        override fun onArrivedWayPoint(index: Int) {
            logger.log("DATA", "arrivedWayPoint=$index")
        }

        override fun onStartYawing(reason: String?) {
            logger.log("DATA", "startYawing=$reason")
        }

        override fun onYawingSuccess() {
            logger.log("DATA", "yawingSuccess")
        }

        override fun onMainRouteChange(routeId: Int) {
            logger.log("DATA", "mainRouteChange=$routeId")
        }

        override fun onRouteForceSwitch(
            reason: String?,
            distance: Long,
            type: Int,
            location: BNaviLocation?,
        ) {
            logger.log("DATA", "routeForceSwitch reason=$reason dist=$distance type=$type loc=$location")
        }

        override fun onNaviGuideEnd() {
            logger.log("DATA", "naviGuideEnd")
        }

        override fun onHeavyTraffic() {
            logger.log("DATA", "heavyTraffic")
        }
    }

    private val trafficLightListener =
        object : IBNRouteGuideManager.TrafficLightListener {
            override fun onTrafficLightUpdate(lights: ArrayList<BNTrafficLight>?) {
                logger.log("DATA", "trafficLights count=${lights?.size}")
                lights.orEmpty().forEachIndexed { lightIndex, light ->
                    val details = light.getDetailList()
                    logger.log(
                        "DATA",
                        "light[$lightIndex] x=${light.getX()} y=${light.getY()} details=${details?.size}",
                    )
                    details.orEmpty().forEachIndexed { index, detail ->
                        logger.log(
                            "DATA",
                            "light[$lightIndex][$index] mainFlow=${detail.isMainFlow()} " +
                                "direction=${detail.getDirection()} color=${detail.getColor()} " +
                                "countdown=${detail.getCountdownTime()} blinkPeriod=${detail.getBlinkPeriod()} " +
                                "showCountdown=${detail.isBlinkShowCountDown()} blinkText=${detail.getBlinkText()}",
                        )
                    }
                }
            }
        }

    private val viewListener = object : IBNaviViewListener() {
        override fun onNaviBackClick() {
            logger.log("VIEW", "onNaviBackClick")
            mainHandler.post { stopGuide() }
        }

        override fun onZoomLevelChange(level: Int) {
            logger.log("VIEW", "zoomLevel=$level")
        }

        override fun onMapClicked(latitude: Double, longitude: Double) {
            logger.log("VIEW", "mapClicked=$latitude,$longitude")
        }

        override fun onMapMoved() {
            logger.log("VIEW", "mapMoved")
        }

        override fun onFullViewButtonClick(fullView: Boolean) {
            logger.log("VIEW", "fullViewButton=$fullView")
        }

        override fun onNaviSettingClick() {
            logger.log("VIEW", "naviSettingClick")
        }

        override fun onRefreshBtnClick() {
            logger.log("VIEW", "refreshBtnClick")
        }

        override fun onMainAuxiliaryOrBridgeClick(type: Int) {
            logger.log("VIEW", "mainAuxiliaryOrBridge=$type")
        }
    }

    private fun captureScreen(tag: String) {
        val decor = window?.decorView ?: return
        if (decor.width <= 0 || decor.height <= 0) {
            mainHandler.postDelayed({ captureScreen(tag) }, 500)
            return
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            window,
            bitmap,
            { result ->
                if (result == PixelCopy.SUCCESS) {
                    logger.log("SHOT", "$tag ok ${bitmap.width}x${bitmap.height}")
                    lastScreenshot = saveBitmap(bitmap, "screen-$tag-${System.currentTimeMillis()}.png")
                } else {
                    logger.log("SHOT", "$tag PixelCopy result=$result")
                }
            },
            mainHandler,
        )
    }

    private fun saveBitmap(bitmap: Bitmap, name: String): File? {
        val file = File(logger.file.parentFile, name)
        Thread {
            runCatching {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                logger.log("FILE", "saved ${file.name} bytes=${file.length()}")
            }.onFailure { logger.logThrowable("FILE", it) }
        }.start()
        return file
    }

    private fun shareLatestScreenshot() {
        val file = lastScreenshot
        if (file == null || !file.exists()) {
            logger.log("SHARE", "no screenshot yet")
            return
        }
        shareFile(file, "image/png")
    }

    private fun shareLog() {
        logger.log("SHARE", "share log ${logger.file.name}")
        logger.flush()
        shareFile(logger.file, "text/plain")
    }

    private fun shareFile(file: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.probe.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
        }.onFailure { logger.logThrowable("SHARE", it) }
    }

    private fun startMemorySampler() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val task = object : Runnable {
            override fun run() {
                runCatching {
                    val runtime = Runtime.getRuntime()
                    val memoryInfo = Debug.MemoryInfo()
                    Debug.getMemoryInfo(memoryInfo)
                    val systemInfo = ActivityManager.MemoryInfo().also {
                        activityManager.getMemoryInfo(it)
                    }
                    logger.log(
                        "MEM",
                        "totalPss=${memoryInfo.totalPss / 1024}MB " +
                            "jvmUsed=${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}MB " +
                            "jvmMax=${runtime.maxMemory() / 1_048_576}MB " +
                            "nativeAlloc=${Debug.getNativeHeapAllocatedSize() / 1_048_576}MB " +
                            "avail=${systemInfo.availMem / 1_048_576}MB low=${systemInfo.lowMemory}",
                    )
                }
                mainHandler.postDelayed(this, 5_000)
            }
        }
        memoryTask = task
        mainHandler.postDelayed(task, 1_000)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
