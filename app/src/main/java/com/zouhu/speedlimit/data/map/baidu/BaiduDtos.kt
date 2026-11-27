package com.zouhu.speedlimit.data.map.baidu

import kotlinx.serialization.Serializable

@Serializable
data class BaiduResponse(
    val status: Int = -1,
    val message: String = "",
    val result: BaiduResult? = null,
)

@Serializable
data class BaiduResult(
    val total: Int = 0,
    val routes: List<BaiduRoute> = emptyList(),
)

@Serializable
data class BaiduRoute(
    val route_id: String = "",
    val data_version: String = "",
    val distance: Int = 0,
    val linkids: String = "",
    val camera_list: List<BaiduCamera> = emptyList(),
    val lane_info: List<BaiduLaneInfo> = emptyList(),
)

@Serializable
data class BaiduCamera(
    val type: Int = 0,
    val priority: Int = 0,
    val position: String = "",
    val linkid: Long = 0,
    val offset: Int = 0,
    val speed_limit: Int? = null,
)

@Serializable
data class BaiduLaneInfo(
    val position: String = "",
    val lanes: List<BaiduLane> = emptyList(),
)

@Serializable
data class BaiduLane(
    val direction: List<Int> = emptyList(),
)