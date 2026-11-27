package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.CameraInfo
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.Lane
import com.zouhu.speedlimit.domain.model.LaneDirection
import com.zouhu.speedlimit.domain.model.RoadData
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object BaiduMappers {
    fun toRoadData(resp: BaiduResponse, gps: GpsSample, now: Long): RoadData {
        if (resp.status != 0) return RoadData(timestampMs = now, isValid = false)
        val route = resp.result?.routes?.firstOrNull() ?: return RoadData(timestampMs = now, isValid = false)
        val (gLat, gLng) = CoordUtil.wgs84ToGcj02(gps.latitude, gps.longitude)
        val cameras = route.camera_list.map { c ->
            val (lng, lat) = parsePos(c.position)
            CameraInfo(
                type = CameraType.fromBaiduCode(c.type),
                speedLimit = c.speed_limit,
                priority = c.priority,
                latitude = lat,
                longitude = lng,
                linkId = c.linkid.toString(),
                offsetM = c.offset.toDouble(),
                distanceM = haversine(gLat, gLng, lat, lng),
                rawType = c.type,
            )
        }
        val speedCams = cameras.filter { it.type.isSpeedEnforcement }
        // One selected camera supplies type, distance and its own limit. Do not pick a
        // second, farther camera just because it happens to carry a speed_limit.
        val nearest = speedCams.minByOrNull { it.distanceM }
        // An interval section has its own enforcement limit. Derive it only from the
        // interval start/end cameras, never from the road or an unrelated speed camera.
        val intervalLimit = cameras
            .filter { (it.rawType == INTERVAL_START_TYPE || it.rawType == INTERVAL_END_TYPE) &&
                (it.speedLimit ?: 0) > 0 }
            .minByOrNull { it.distanceM }
            ?.speedLimit
            ?.takeIf { it > 0 }
        // 百度车道按右→左下发，反转成左→右显示。
        val lanes = route.lane_info.firstOrNull()?.lanes?.reversed()?.map { l ->
            Lane(directions = l.direction.map { LaneDirection.fromBaiduCode(it) }.toSet())
        } ?: emptyList()
        return RoadData(
            roadId = route.route_id,
            dataVersion = route.data_version,
            // This response has no current-link speed limit field. A camera's
            // speed_limit must not be promoted to the road limit.
            speedLimit = null,
            cameraSpeedLimit = nearest?.speedLimit?.takeIf { it > 0 },
            intervalSpeedLimit = intervalLimit,
            cameras = cameras,
            lanes = lanes,
            cameraAhead = nearest != null,
            cameraType = nearest?.type ?: CameraType.OTHER,
            distanceToCameraM = nearest?.distanceM ?: 0.0,
            timestampMs = now,
            isValid = true,
        )
    }

    private fun parsePos(s: String): Pair<Double, Double> {
        val p = s.split(",")
        if (p.size != 2) return 0.0 to 0.0
        return (p[0].trim().toDoubleOrNull() ?: 0.0) to (p[1].trim().toDoubleOrNull() ?: 0.0)
    }

    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

private const val INTERVAL_START_TYPE = 5
private const val INTERVAL_END_TYPE = 9