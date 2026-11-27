package com.zouhu.speedlimit.domain.model

enum class TrafficLightState {
    RED,
    YELLOW,
    GREEN,
    FLASHING_YELLOW,
    OFF,
    UNKNOWN,
    ;

    companion object {
        fun fromLampStatus(code: Int): TrafficLightState = when (code) {
            21 -> RED
            22 -> YELLOW
            23 -> GREEN
            24 -> FLASHING_YELLOW
            11 -> OFF
            else -> UNKNOWN
        }
    }
}

/** Driving flow a lamp belongs to, matching Baidu road_flow_direction. */
enum class TrafficLightFlow {
    STRAIGHT,
    LEFT,
    RIGHT,
    UTURN,
    OTHER,
    ;

    companion object {
        fun fromRoadFlow(code: String?): TrafficLightFlow = when (code?.trim()) {
            "11", "21", "22", "24" -> STRAIGHT
            "12", "23" -> LEFT
            "13" -> RIGHT
            "31" -> UTURN
            else -> OTHER
        }
    }
}

data class TrafficLightPhase(
    val state: TrafficLightState,
    val countdownSec: Int,
    val periodSec: Int,
)

/** One direction's phase timeline. A crossing usually has straight + left. */
data class DirectionalTrafficLight(
    val flow: TrafficLightFlow,
    val phases: List<TrafficLightPhase>,
)

data class TrafficLightInfo(
    val distanceM: Double = 0.0,
    val timestampMs: Long = 0L,
    val phases: List<TrafficLightPhase> = emptyList(),
    /** Multi-direction data (straight/left/right...). Empty for legacy single-lamp sources. */
    val directions: List<DirectionalTrafficLight> = emptyList(),
)
