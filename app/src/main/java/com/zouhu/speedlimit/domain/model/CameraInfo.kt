package com.zouhu.speedlimit.domain.model

data class CameraInfo(
    val type: CameraType = CameraType.OTHER,
    val speedLimit: Int? = null,
    val priority: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val linkId: String? = null,
    val offsetM: Double = 0.0,
    val distanceM: Double = 0.0,
    /** Baidu raw type; needed to distinguish interval start (5) / end (9). */
    val rawType: Int = 0,
)