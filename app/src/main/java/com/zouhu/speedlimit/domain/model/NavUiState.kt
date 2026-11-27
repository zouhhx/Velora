package com.zouhu.speedlimit.domain.model

/**
 * Navigation UI contract (frontend-owned). The backend / navi bridge must emit
 * exactly this shape; the frontend renders it without re-deriving guidance.
 * Lanes are structured (count / directions / recommended), never drawables.
 */
enum class NavState { CRUISE, TURN, INTERSECTION, LIGHT, CAMERA, REROUTE, ARRIVED }

enum class NavManeuver { STRAIGHT, LEFT, RIGHT, UTURN, SLIGHT_LEFT, SLIGHT_RIGHT, ROUNDABOUT, ARRIVE }

data class NavPoint(val lat: Double, val lng: Double)

data class NavTurn(
    val distanceM: Int,
    val roadName: String,
    val maneuver: NavManeuver,
    val nextRoad: String? = null,
)

data class NavLane(
    val directions: List<LaneDirection>,
    val recommended: Boolean,
)

data class NavTrafficLight(
    val state: TrafficLightState,
    val countdownSec: Int?,
    /** When this reading arrived. The HUD anchors the countdown to it, not to each recomposition (VA-L03). */
    val receivedAtMs: Long = 0L,
)

data class NavCamera(
    val type: CameraType,
    val distanceM: Int,
    val limit: Int?,
)

data class NavEta(
    val remainingSec: Int,
    val distanceM: Int,
    val arrivalEpoch: Long,
)

data class NavTts(val text: String, val priority: Int)

data class NavUiState(
    val navState: NavState = NavState.CRUISE,
    val speedKmh: Int = 0,
    val speedLimit: Int? = null,
    val turn: NavTurn? = null,
    val lanes: List<NavLane> = emptyList(),
    val trafficLight: NavTrafficLight? = null,
    val camera: NavCamera? = null,
    val eta: NavEta? = null,
    val route: List<NavPoint> = emptyList(),
    val reroute: Boolean = false,
    val arrived: Boolean = false,
    /** True while the navi map is showing the whole route ("总览") instead of driving view. */
    val fullView: Boolean = false,
    val tts: List<NavTts> = emptyList(),
)