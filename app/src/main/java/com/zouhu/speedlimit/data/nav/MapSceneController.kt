package com.zouhu.speedlimit.data.nav

import android.content.Context
import android.content.res.Configuration
import com.baidu.mapsdkplatform.comapi.commonutils.DayNightModeManager
import com.baidu.navisdk.adapter.BaiduNaviManagerFactory
import com.baidu.navisdk.adapter.IBNMapManager
import com.baidu.navisdk.comapi.mapcontrol.BNMapController
import com.baidu.navisdk.comapi.mapcontrol.NavMapManager

/**
 * Controls the *shared* Baidu map engine that both the map-SDK view and the navi engine
 * render into.
 *
 * Confirmed on device (2026-11-15/16):
 *  - Navigation switches the engine into a "nav scene": tilted camera, navi palette,
 *    highlighted route layer and the navi car logo. `onDestroy(false)` does not undo it,
 *    so the cruise map (and the home corner map) kept rendering the nav scene — dark,
 *    with a leftover green route and the car cursor.
 *  - The palette is ours to choose via [BNMapController.setDayNightMode]; the SDK's own
 *    day/night logic re-decides by wall-clock time unless we re-assert.
 *  - `onDestroy(false)` does NOT remove the route/alternative-route/comparison layers
 *    either (confirmed 2026-11-17: the route survived an explicit exit). Those are engine
 *    layers, so they must be cleared here and the layer mode returned to BROWSE_MAP.
 */
object MapSceneController {
    const val MODE_DAY = "day"
    const val MODE_NIGHT = "night"
    const val MODE_SYSTEM = "system"

    // com.baidu.navisdk.comapi.mapcontrol.MapParams.Const.MapStyleMode
    private const val STYLE_MAP_DAY = 2
    private const val STYLE_NAV_DAY = 4
    private const val STYLE_NAV_NIGHT = 5

    // MapParams.Const.LayerMode / LayerType
    private const val LAYER_MODE_BROWSE_MAP = 0
    private const val LAYER_MODE_ROUTE_GUIDE = 3
    private const val LAYER_TYPE_POI = 3
    private const val LAYER_TYPE_POI_BKG = 4
    private const val LAYER_TYPE_ROUTE = 10
    private const val LAYER_TYPE_POPUP = 11
    private const val LAYER_TYPE_BASE_POI = 12
    private const val LAYER_TYPE_ROUTE_SPEC = 13
    private const val LAYER_TYPE_AVOIDLINE = 17
    private const val LAYER_TYPE_UGC_POPUP = 31
    private const val LAYER_TYPE_UGC_REROUTE_POPUP = 32
    private const val LAYER_TYPE_ROUTE_UGC = 6016

    fun isValidMode(mode: String): Boolean = mode in setOf(MODE_DAY, MODE_NIGHT, MODE_SYSTEM)

    /** "system" follows the phone's dark-mode flag; never the clock. */
    fun naviStyle(context: Context, mode: String): Int = when (mode) {
        MODE_DAY -> STYLE_NAV_DAY
        MODE_NIGHT -> STYLE_NAV_NIGHT
        else -> {
            val night = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (night) STYLE_NAV_NIGHT else STYLE_NAV_DAY
        }
    }

    /**
     * Entering navigation: the engine is shared with cruise and the previous session left
     * it flat, so clear the 2D flag and restore the car visuals before the guide renders
     * its first frame.
     */
    fun enterNaviScene() {
        val c = controller() ?: return
        runCatching { c.setMap2DLook(false) }
            .onFailure { NavTrace.log("SCENE", "enter: setMap2DLook threw ${it.javaClass.simpleName}") }
        runCatching { c.setCarLogoVisible(true) }
        runCatching { c.showCarResultLayer(true) }
        NavTrace.log("SCENE", "enter nav scene 2d=false carLogo=true")
    }

    /**
     * Apply the user's choice to the navi map. Called before the guide is created, right
     * after it is created, and again after onStart — the SDK re-applies its own auto
     * decision at several points, so one call is not enough.
     */
    fun applyNaviDayNight(context: Context, mode: String): Boolean {
        val style = naviStyle(context, mode)
        val c = controller()
        val applied = runCatching { c?.setDayNightMode(style) ?: false }
            .onFailure { NavTrace.log("SCENE", "setDayNightMode($style) threw ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        NavTrace.log(
            "SCENE",
            "navi dayNight mode=$mode style=$style applied=$applied mapSdkMode=${mapSdkMode()}",
        )
        return applied
    }

    /**
     * Hand the engine back to cruise. Called twice per session on purpose (P1 timing):
     *  - `pre-destroy`: while the engine still accepts layer calls;
     *  - `post-destroy`: after teardown, because the SDK's async teardown can put the car
     *    logo / navi palette back.
     *
     * Route cleanup is the important part here: `onDestroy(false)` does NOT drop the
     * highlighted route, the alternative-route lines or the comparison bubbles — they are
     * engine layers, which is exactly what stayed on screen after an exit. Leaving the
     * guide layer mode is also what restores single-finger panning.
     */
    fun restoreCruiseScene(stage: String) {
        val c = controller()
        if (c == null) {
            NavTrace.log("SCENE", "restore($stage) skipped: controller unavailable")
            return
        }
        val modeBefore = runCatching { c.layerMode }.getOrNull()
        val logo = runCatching { c.setCarLogoVisible(false) }.getOrDefault(false)
        runCatching { c.showCarResultLayer(false) }
            .onFailure { NavTrace.log("SCENE", "restore($stage) showCarResultLayer threw ${it.javaClass.simpleName}") }
        // Layer mode first: ROUTE_GUIDE(3) suppresses panning and keeps the guide overlay
        // stack alive; BROWSE_MAP(0) is the plain map mode.
        runCatching { c.setLayerMode(LAYER_MODE_BROWSE_MAP) }
            .onFailure { NavTrace.log("SCENE", "restore($stage) setLayerMode threw ${it.javaClass.simpleName}") }
        // The navi map mode is a separate switch from layerMode. Leaving it in
        // ROUTE/SLIGHT keeps the nav scene (no POI, pan ignored). NORMAL=1.
        val mapMode = runCatching {
            BaiduNaviManagerFactory.getMapManager()
                ?.setNaviMapMode(IBNMapManager.MapTypeEnum.NORMAL)
            true
        }.onFailure {
            NavTrace.log("SCENE", "restore($stage) setNaviMapMode threw ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        // Page type 1 maps to NaviMapMode.NORMAL in NavMapManager.getNaviMapMode().
        // This closes the navi black layer and restores the normal theme/scene.
        val themeScene = runCatching {
            NavMapManager.getInstance().handleMapThemeAndScene(1)
            true
        }.onFailure {
            NavTrace.log("SCENE", "restore($stage) handleMapThemeAndScene threw ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        // Re-show overlays that the navi engine hid on entry (POI, etc.).
        val overlays = runCatching {
            NavMapManager.getInstance().resetMapOverlays()
            true
        }.onFailure {
            NavTrace.log("SCENE", "restore($stage) resetMapOverlays threw ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        // Reversible belt-and-braces for the route layer itself (bubbles may live in
        // POPUP/ROUTE_SPEC, which is why the layer-mode reset is the primary action).
        runCatching { c.showLayer(LAYER_TYPE_ROUTE, false) }
            .onFailure { NavTrace.log("SCENE", "restore($stage) showLayer threw ${it.javaClass.simpleName}") }
        // Official route-layer cleanup: the AAR implementation calls
        // BNRouteGuider.removeRoute(0) and hides the car-result layer. This is stronger
        // than showLayer(ROUTE,false) for the native route-plan result.
        val routeClear = runCatching {
            BaiduNaviManagerFactory.getMapManager()?.clearRouteLayer()
            true
        }.onFailure {
            NavTrace.log("SCENE", "restore($stage) clearRouteLayer threw ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        // Comparison cards ("走文宗路/大路多 时间相近") are native POPUP/ROUTE_SPEC/UGC
        // layers, not the main route polyline. Clear each known route-popup layer.
        val popupLayers = listOf(
            LAYER_TYPE_POPUP,
            LAYER_TYPE_ROUTE_SPEC,
            LAYER_TYPE_AVOIDLINE,
            LAYER_TYPE_UGC_POPUP,
            LAYER_TYPE_UGC_REROUTE_POPUP,
            LAYER_TYPE_ROUTE_UGC,
        )
        val popupCleared = popupLayers.map { type ->
            runCatching { c.clearLayer(type) }.getOrDefault(false)
        }
        // The destination pin is its own node layer — that is the marker that survived on
        // screen after an exit even when the route line was gone.
        runCatching { c.setEndNodeIconVisible(false) }
        runCatching { c.setEndNodeNameVisible(false) }
        runCatching { c.setMap2DLook(true) }
            .onFailure { NavTrace.log("SCENE", "restore($stage) setMap2DLook threw ${it.javaClass.simpleName}") }
        val day = runCatching { c.setDayNightMode(STYLE_MAP_DAY) }.getOrDefault(false)
        // Apply style first, then the layer switches: a style change can reset POI/traffic
        // flags, and the last write must be the browse state.
        val trafficOff = runCatching { c.showTrafficMap(false) }.isSuccess
        val poiScene = runCatching { c.setMapPoiScene(true) }.isSuccess
        val poiLayers = listOf(LAYER_TYPE_POI, LAYER_TYPE_POI_BKG, LAYER_TYPE_BASE_POI)
            .map { type -> runCatching { c.showLayer(type, true) }.getOrDefault(false) }
        val modeAfter = runCatching { c.layerMode }.getOrNull()
        NavTrace.log(
            "SCENE",
            "restore($stage) layerMode=$modeBefore->$modeAfter carLogoOff=$logo " +
                "map2d=true mapDay=$day mapModeNormal=$mapMode themeScene=$themeScene overlays=$overlays " +
                "routeClear=$routeClear popup=$popupCleared " +
                "trafficOff=$trafficOff poi=$poiScene poiLayers=$poiLayers mapSdkMode=${mapSdkMode()}",
        )
    }

    /**
     * Navi guidance zoom. The SDK's own guidance default is level 18
     * (`MapParams.Const.NAV_DEFAULT_SCALE_LEVEL_GUIDE`); the map was instead sitting at
     * the minimum guide level (~15), which is exactly where the lane-level road detail
     * disappears. Applied a couple of times at start, never continuously.
     */
    fun applyNaviZoom(level: Float = 18f): Boolean {
        val c = controller() ?: return false
        val ok = runCatching { c.setLevel(level) }
            .onFailure { NavTrace.log("SCENE", "setLevel threw ${it.javaClass.simpleName}") }
            .isSuccess
        val now = runCatching { c.mapLevel }.getOrNull()
        NavTrace.log("SCENE", "navi level=$level applied=$ok now=$now")
        return ok
    }

    /**
     * Stop the guidance camera from pulling itself back out.
     *
     * Symptoms this targets: the map opens zoomed out, and any manual zoom-in is
     * reverted a moment later. Both point at the engine's overview/auto-zoom logic
     * rather than at our own camera writes (which are disabled during guidance).
     */
    /**
     * Turn the engine's "zoom back out to the overview" behaviour on or off.
     *
     * On while driving (so a manual zoom is not reverted), off while the user is in the
     * explicit 总览 mode (so the overview itself is allowed to fit the whole route).
     */
    fun setOverviewAutoZoomInterrupted(interrupted: Boolean) {
        val c = controller() ?: return
        runCatching { c.setInterruptOverviewAutoZoom(interrupted) }
            .onFailure { NavTrace.log("SCENE", "interruptOverview threw ${it.javaClass.simpleName}") }
        NavTrace.log("SCENE", "interruptOverviewAutoZoom=$interrupted")
    }

    fun pinNaviView() {
        val c = controller() ?: return
        val setting = runCatching { BaiduNaviManagerFactory.getProfessionalNaviSettingManager() }
            .getOrNull()
        val autoBefore = if (setting != null) {
            runCatching { "${setting.isAutoScale(0)}/${setting.isAutoScale(1)}" }.getOrElse { "n/a" }
        } else "n/a"
        setOverviewAutoZoomInterrupted(true)
        val autoOff = if (setting != null) {
            runCatching { setting.setAutoScale(false, 0) }
                .onFailure { NavTrace.log("SCENE", "setAutoScale threw ${it.javaClass.simpleName}") }
                .isSuccess
        } else false
        NavTrace.log("SCENE", "pin autoBefore=$autoBefore autoScaleOff=$autoOff")
    }

    /** Current guide layer mode: 0 = plain browse map, 3 = route guidance. */
    fun layerMode(): Int? = runCatching { controller()?.layerMode }.getOrNull()

    /**
     * Switch the guide layer mode for the duration of a user drag.
     *
     * Route-guidance mode owns the camera and swallows pans, which is why single-finger
     * dragging does nothing during guidance even though `isScrollGesturesEnabled` is true.
     * Dropping to browse mode while the finger is down gives the map back to the user;
     * the caller restores guidance mode on finger up.
     */
    fun setLayerMode(mode: Int): Boolean {
        val c = controller() ?: return false
        val ok = runCatching { c.setLayerMode(mode) }
            .onFailure { NavTrace.log("SCENE", "setLayerMode($mode) threw ${it.javaClass.simpleName}") }
            .isSuccess
        NavTrace.log("SCENE", "setLayerMode($mode) ok=$ok now=${layerMode()}")
        return ok
    }

    fun browseMode(): Int = LAYER_MODE_BROWSE_MAP

    fun guideMode(): Int = LAYER_MODE_ROUTE_GUIDE

    private fun controller(): BNMapController? = runCatching { BNMapController.getInstance() }
        .onFailure { NavTrace.log("SCENE", "controller unavailable: ${it.javaClass.simpleName}") }
        .getOrNull()

    /** Map-SDK global day/night mode, diagnostics only. 1=day 2=night 3=auto. */
    private fun mapSdkMode(): String = runCatching {
        DayNightModeManager.getInstance().currentMode.toString()
    }.getOrElse { "n/a" }
}
