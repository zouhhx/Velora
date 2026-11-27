package com.zouhu.speedlimit.data.nav

import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.LaneDirection
import com.zouhu.speedlimit.domain.model.NavCamera
import com.zouhu.speedlimit.domain.model.NavEta
import com.zouhu.speedlimit.domain.model.NavLane
import com.zouhu.speedlimit.domain.model.NavManeuver
import com.zouhu.speedlimit.domain.model.NavPoint
import com.zouhu.speedlimit.domain.model.NavState
import com.zouhu.speedlimit.domain.model.NavTrafficLight
import com.zouhu.speedlimit.domain.model.NavTurn
import com.zouhu.speedlimit.domain.model.NavUiState
import com.zouhu.speedlimit.domain.model.TrafficLightState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

/** Navigation data source contract. Backend navi bridge will implement this. */
interface NavSource {
    val state: StateFlow<NavUiState>
}

/**
 * Scripted simulation so the navigation frontend is runnable and reviewable before
 * the backend navi bridge lands. Loops cruise -> turn -> intersection -> light ->
 * camera -> reroute -> arrived. Cadence: light/speed 4Hz, turn/eta 1Hz.
 */
class SimNavSource : NavSource {
    private val _state = MutableStateFlow(NavUiState())
    override val state: StateFlow<NavUiState> = _state.asStateFlow()

    private val route = listOf(
        NavPoint(32.0980, 118.9050),
        NavPoint(32.1000, 118.9050),
        NavPoint(32.1020, 118.9052),
        NavPoint(32.1020, 118.9090),
        NavPoint(32.1040, 118.9090),
        NavPoint(32.1060, 118.9092),
    )

    fun run(scope: CoroutineScope): kotlinx.coroutines.Job {
        val t0 = System.currentTimeMillis()
        return scope.launch {
            while (isActive) {
                val t = ((System.currentTimeMillis() - t0) / 1000.0) % CYCLE
                _state.value = sample(t)
                delay(250)
            }
        }
    }

    internal fun sample(t: Double): NavUiState {
        val speed = (52 + 14 * sin(t / 3.0)).roundToInt().coerceIn(0, 120)
        val limit = 60
        val remainingSec = (CYCLE - t).roundToInt() * 12
        val eta = NavEta(remainingSec, remainingSec * 12, System.currentTimeMillis() + remainingSec * 1000L)
        return when {
            t < 8 -> NavUiState(NavState.CRUISE, speed, limit, eta = eta, route = route)
            t < 20 -> NavUiState(
                NavState.TURN, speed, limit,
                turn = NavTurn(((20 - t) * 25).roundToInt(), "智谷大道", NavManeuver.RIGHT, "仙林大道"),
                eta = eta, route = route,
            )
            t < 28 -> NavUiState(
                NavState.INTERSECTION, speed, limit,
                turn = NavTurn(((28 - t) * 12).roundToInt(), "智谷大道", NavManeuver.RIGHT, "仙林大道"),
                lanes = listOf(
                    NavLane(listOf(LaneDirection.LEFT), false),
                    NavLane(listOf(LaneDirection.STRAIGHT), false),
                    NavLane(listOf(LaneDirection.STRAIGHT, LaneDirection.RIGHT), true),
                ),
                eta = eta, route = route,
            )
            t < 40 -> NavUiState(
                NavState.LIGHT, speed, limit,
                turn = NavTurn(120, "智谷大道", NavManeuver.RIGHT, "仙林大道"),
                trafficLight = NavTrafficLight(
                    if (t < 32) TrafficLightState.RED else TrafficLightState.GREEN,
                    (if (t < 32) 32 - t else 40 - t).roundToInt(),
                    receivedAtMs = System.currentTimeMillis(),
                ),
                eta = eta, route = route,
            )
            t < 48 -> NavUiState(
                NavState.CAMERA, speed, limit,
                camera = NavCamera(CameraType.SPEED, ((48 - t) * 20).roundToInt(), limit),
                eta = eta, route = route,
            )
            t < 54 -> NavUiState(NavState.REROUTE, speed, limit, reroute = true, eta = eta, route = route)
            else -> NavUiState(NavState.ARRIVED, 0, limit, arrived = true, eta = NavEta(0, 0, System.currentTimeMillis()), route = route)
        }
    }

    private companion object {
        const val CYCLE = 60.0
    }
}