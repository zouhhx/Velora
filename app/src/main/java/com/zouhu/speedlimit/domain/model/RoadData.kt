package com.zouhu.speedlimit.domain.model

data class RoadData(
    val roadId: String? = null,
    val pathId: String? = null,
    val roadName: String? = null,
    /** Current road/link limit. Must never be filled with a camera's point limit. */
    val speedLimit: Int? = null,
    val speedLimitType: Int? = null,
    /** Limit of the selected nearest camera, independent of [speedLimit]. */
    val cameraSpeedLimit: Int? = null,
    /** Limit of the interval section, used only for interval average judgement. */
    val intervalSpeedLimit: Int? = null,
    val cameras: List<CameraInfo> = emptyList(),
    val lanes: List<Lane> = emptyList(),
    val trafficLight: TrafficLightInfo? = null,
    val cameraAhead: Boolean = false,
    val cameraType: CameraType = CameraType.OTHER,
    val distanceToCameraM: Double = 0.0,
    val interval: IntervalSnapshot = IntervalSnapshot(),
    val confidence: Double = 0.0,
    val dataVersion: String? = null,
    val timestampMs: Long = 0L,
    val isValid: Boolean = false,
) {
    val limitKnown: Boolean get() = speedLimit != null
    val laneKnown: Boolean get() = lanes.isNotEmpty()
}