package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.Lane
import com.zouhu.speedlimit.domain.model.LaneDirection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class HorizonPosition(
    @SerialName("path_id") val pathId: Int = 0,
    @SerialName("link_id") val linkId: Long = 0L,
    val offset: Double = 0.0,
    val speed: Double = 0.0,
    val dir: Double = 0.0,
    val probability: Double = 0.0,
    val elevated: Int = 0,
)

@Serializable
data class HorizonLink(
    @SerialName("link_id") val linkId: Long,
    @SerialName("path_id") val pathId: Int = 0,
    val offset: Double = 0.0,
    val length: Double = 0.0,
    @SerialName("distance_to_pos") val distanceToPos: Double = 0.0,
    @SerialName("speed_limit") val speedLimit: Int? = null,
    @SerialName("speed_limit_type") val speedLimitType: Int? = null,
    @SerialName("road_name") val roadName: String? = null,
    val lanenum: Int? = null,
    @SerialName("road_grade") val roadGrade: Int? = null,
)

@Serializable
data class HorizonCamera(
    @SerialName("distance_to_pos") val distanceToPos: Double = 0.0,
    val type: Int = 0,
    @SerialName("speed_limit") val speedLimit: Int? = null,
    val loc: List<Double>? = null,
    val linkid: Long? = null,
    val offset: Double? = null,
    @SerialName("path_id") val pathId: Int? = null,
    val angle: Int? = null,
)

@Serializable
data class HorizonLaneArrow(
    @SerialName("path_id") val pathId: Int = 0,
    val offset: Double = 0.0,
    val linkid: Long? = null,
    val direction: String? = null,
    val loc: List<Double>? = null,
    @SerialName("distance_to_pos") val distanceToPos: Double = 0.0,
    @SerialName("lane_type") val laneType: String? = null,
)

@Serializable
data class HorizonTrafficLightStatus(
    @SerialName("count_down") val countDown: Int = 0,
    @SerialName("lamp_status") val lampStatus: Int = 0,
    val period: Int? = null,
)

@Serializable
data class HorizonTrafficLight(
    @SerialName("path_id") val pathId: Int = 0,
    @SerialName("link_id") val linkId: Long = 0L,
    val offset: Double = 0.0,
    @SerialName("distance_to_pos") val distanceToPos: Double = 0.0,
    val timestamp: Long = 0L,
    val status: List<HorizonTrafficLightStatus>? = null,
    val loc: List<Double>? = null,
    @SerialName("road_flow_direction") val roadFlowDirection: String? = null,
    @SerialName("simple_eight_direction") val simpleEightDirection: String? = null,
    @SerialName("on_route") val onRoute: Int? = null,
    val comfrom: Int? = null,
)

@Serializable
data class HorizonPayload(
    val position: HorizonPosition? = null,
    val link: List<HorizonLink>? = null,
    @SerialName("camera_list") val cameraList: List<HorizonCamera>? = null,
    @SerialName("lane_arrow") val laneArrow: List<HorizonLaneArrow>? = null,
    @SerialName("traffic_light") val trafficLight: List<HorizonTrafficLight>? = null,
    val ack: List<Long>? = null,
    val version: Int? = null,
    @SerialName("route_id") val routeId: String? = null,
)

sealed interface HorizonEvent {
    data class Auth(val status: Int, val message: String?) : HorizonEvent
    data class Position(val position: HorizonPosition, val version: Int) : HorizonEvent
    data class Links(val links: List<HorizonLink>, val version: Int) : HorizonEvent
    data class Cameras(val cameras: List<HorizonCamera>, val version: Int) : HorizonEvent
    data class Lanes(val arrows: List<HorizonLaneArrow>, val version: Int) : HorizonEvent
    data class TrafficLights(
        val lights: List<HorizonTrafficLight>,
        val version: Int,
        val serverTsMs: Long = 0L,
    ) : HorizonEvent
    data class Ack(val ids: List<Long>) : HorizonEvent
    data object Unknown : HorizonEvent
}

object HorizonProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Raw text of the last message that parsed to Unknown (for diagnostics). */
    @Volatile
    var lastUnknownRaw: String? = null
        private set

    fun parse(raw: String): HorizonEvent? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val qt = root["qt"]?.jsonPrimitive?.contentOrNull ?: return null
        val data = root["data"] as? JsonObject ?: return null
        if (qt == "auth") {
            return HorizonEvent.Auth(
                status = data["status"]?.jsonPrimitive?.intOrNull ?: -1,
                message = data["message"]?.jsonPrimitive?.contentOrNull,
            )
        }
        if (qt != "horizon") {
            lastUnknownRaw = raw
            return HorizonEvent.Unknown
        }
        val payload = runCatching {
            json.decodeFromJsonElement(HorizonPayload.serializer(), data)
        }.getOrNull() ?: return null
        val version = payload.version ?: 0
        val rawServerTs = runCatching { root["ts"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
        val serverTsMs = when {
            rawServerTs <= 0L -> 0L
            rawServerTs < 100_000_000_000L -> rawServerTs * 1000L
            else -> rawServerTs
        }
        return when {
            payload.position != null -> HorizonEvent.Position(payload.position, version)
            payload.link != null -> HorizonEvent.Links(payload.link, version)
            payload.cameraList != null -> HorizonEvent.Cameras(payload.cameraList, version)
            payload.laneArrow != null -> HorizonEvent.Lanes(payload.laneArrow, version)
            payload.trafficLight != null ->
                HorizonEvent.TrafficLights(payload.trafficLight, version, serverTsMs)
            payload.ack != null -> HorizonEvent.Ack(payload.ack)
            else -> {
                lastUnknownRaw = raw
                HorizonEvent.Unknown
            }
        }
    }

    fun laneList(arrow: HorizonLaneArrow): List<Lane> {
        val groups = arrow.direction?.split(',') ?: return emptyList()
        return groups.mapNotNull { group ->
            val directions = group.mapNotNull(::laneDirection).toSet()
            if (directions.isEmpty()) null else Lane(directions = directions)
        }
    }

    private fun laneDirection(code: Char): LaneDirection? = when (code) {
        '1' -> LaneDirection.STRAIGHT
        '2' -> LaneDirection.LEFT
        '3' -> LaneDirection.RIGHT
        '4' -> LaneDirection.UTURN
        '5' -> LaneDirection.SLIGHT_LEFT
        '6' -> LaneDirection.SLIGHT_RIGHT
        '7' -> LaneDirection.UTURN
        else -> null
    }
}
