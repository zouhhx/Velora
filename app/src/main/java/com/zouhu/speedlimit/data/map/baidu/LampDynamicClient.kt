package com.zouhu.speedlimit.data.map.baidu

import android.util.Log
import com.zouhu.speedlimit.data.settings.SecretRedaction
import com.zouhu.speedlimit.domain.model.DirectionalTrafficLight
import com.zouhu.speedlimit.domain.model.TrafficLightFlow
import com.zouhu.speedlimit.domain.model.TrafficLightPhase
import com.zouhu.speedlimit.domain.model.TrafficLightState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 红绿灯倒计时 (lamp api) via static + dynamic queries.
 *
 * Why not probe_lamp: probe_lamp needs an 鹰眼 track service (service_id) and this
 * account has none, so it always returns status=5 / "not found service_id".
 * static_lamp_search + dynamic_lamp_search only need the server AK, and dynamic
 * returns multiple road_flow_direction entries (straight/left/right), which is what
 * the multi-direction UI needs.
 */
class LampDynamicClient(
    private val ak: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class Direction(
        val flow: TrafficLightFlow,
        val phases: List<TrafficLightPhase>,
    )

    data class Result(
        val distanceM: Double,
        val directions: List<Direction>,
        /** Baidu data generation time (ms), not the local request time. */
        val timestampMs: Long,
        val linkId: Long,
    ) {
        val primaryPhases: List<TrafficLightPhase>
            get() = directions.firstOrNull { it.flow == TrafficLightFlow.STRAIGHT }?.phases
                ?: directions.firstOrNull()?.phases
                ?: emptyList()
    }

    private data class DynamicResponse(
        val timestampMs: Long,
        val directions: List<Direction>,
    )

    private data class StaticLamp(
        val linkId: Long,
        val distanceM: Double,
        val lat: Double,
        val lng: Double,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Volatile
    var lastStaticStatus: Int? = null
        private set

    @Volatile
    var lastDynamicStatus: Int? = null
        private set

    @Volatile
    var lastMessage: String? = null
        private set

    private var cached: StaticLamp? = null
    private var cachedAt = 0L

    suspend fun query(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        speedKmh: Double,
        nowMs: Long,
    ): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val fresh = cached == null || nowMs - cachedAt > STATIC_CACHE_MS
            val (gLat, gLng) = CoordUtil.wgs84ToGcj02(lat, lng)
            val lamp = if (fresh) {
                val forward = staticLamps(lat, lng, bearingDeg, speedKmh, withForward = true)
                val lamps = forward.ifEmpty {
                    // A straight forward projection can leave a curved road; fall back
                    // to the single current point so binding still works.
                    staticLamps(lat, lng, bearingDeg, speedKmh, withForward = false)
                }
                chooseAhead(lamps, lat, lng, bearingDeg, speedKmh)?.also {
                    cached = it
                    cachedAt = nowMs
                }
            } else {
                cached?.let {
                    it.copy(distanceM = BaiduMappers.haversine(gLat, gLng, it.lat, it.lng))
                }
            } ?: return@runCatching null

            val dynamic = dynamicLamps(lamp.linkId)
            if (dynamic.directions.isEmpty()) {
                // Let the next tick re-bind; the intersection may have been passed.
                cached = null
                return@runCatching null
            }
            Result(
                distanceM = lamp.distanceM,
                directions = dynamic.directions,
                timestampMs = dynamic.timestampMs,
                linkId = lamp.linkId,
            )
        }.onFailure {
            Log.w(
                TAG,
                "lamp query failed: ${it.javaClass.simpleName}: ${SecretRedaction.redact(it.message)}",
            )
        }
            .getOrNull()
    }

    private fun staticLamps(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        speedKmh: Double,
        withForward: Boolean,
    ): List<StaticLamp> {
        // A single point only binds the current link; a short forward trajectory lets
        // the service bind the approach and return the next intersection earlier.
        val points = ArrayList<String>(6)
        points += "$lng,$lat"
        if (withForward && speedKmh >= 3.0 && bearingDeg.isFinite()) {
            for (meters in FORWARD_POINTS_M) {
                val (pLat, pLng) = offset(lat, lng, bearingDeg, meters)
                points += "$pLng,$pLat"
            }
        }
        val body = FormBody.Builder()
            .add("point_list", points.joinToString(prefix = "[", postfix = "]") { """{"pt":"$it"}""" })
            .add("coord_type", "wgs84")
            .add("ak", ak)
            .build()
        val request = Request.Builder().url(STATIC_URL).post(body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = json.parseToJsonElement(resp.body?.string() ?: return emptyList()).jsonObject
            lastStaticStatus = root["status"]?.jsonPrimitive?.intOrNull
            lastMessage = root["message"]?.jsonPrimitive?.contentOrNull
            if (lastStaticStatus != 0) return emptyList()
            val resultEl = root["result"] ?: return emptyList()
            val lamps = when (resultEl) {
                is JsonObject -> resultEl["lamps"]?.jsonArray
                is JsonArray -> resultEl
                else -> null
            } ?: return emptyList()
            // Static lamp location is gcj02; compare with the gcj02-converted fix.
            val (gLat, gLng) = CoordUtil.wgs84ToGcj02(lat, lng)
            return lamps.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["linkid"]?.jsonPrimitive?.longOrNull
                    ?: obj["linkid"]?.jsonPrimitive?.intOrNull?.toLong()
                    ?: return@mapNotNull null
                val loc = obj["location"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val parts = loc.split(',')
                if (parts.size != 2) return@mapNotNull null
                val lampLng = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
                val lampLat = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                val distance = BaiduMappers.haversine(gLat, gLng, lampLat, lampLng)
                if (!distance.isFinite() || distance > MAX_STATIC_DISTANCE_M) return@mapNotNull null
                StaticLamp(id, distance, lampLat, lampLng)
            }
        }
    }

    private fun chooseAhead(
        lamps: List<StaticLamp>,
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        speedKmh: Double,
    ): StaticLamp? {
        if (lamps.isEmpty()) return null
        val (gLat, gLng) = CoordUtil.wgs84ToGcj02(lat, lng)
        // At low speed GPS bearing is noisy; do not discard candidates.
        val filterAhead = speedKmh >= 8.0
        val ahead = if (filterAhead) {
            lamps.filter { lamp ->
                val toLamp = bearingTo(gLat, gLng, lamp.lat, lamp.lng)
                angleDiff(toLamp, bearingDeg) <= 100.0
            }
        } else {
            lamps
        }
        return (ahead.ifEmpty { lamps }).minByOrNull { it.distanceM }
    }

    private fun offset(
        lat: Double,
        lng: Double,
        bearingDeg: Double,
        meters: Double,
    ): Pair<Double, Double> {
        val rad = Math.toRadians(bearingDeg)
        val dLat = cos(rad) * meters / 111_320.0
        val dLng = sin(rad) * meters / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        return (lat + dLat) to (lng + dLng)
    }

    private fun bearingTo(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun dynamicLamps(linkId: Long): DynamicResponse {
        val url = DYNAMIC_URL.toHttpUrl().newBuilder()
            .addQueryParameter("links", linkId.toString())
            .addQueryParameter("ak", ak)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return DynamicResponse(System.currentTimeMillis(), emptyList())
            val root = json.parseToJsonElement(resp.body?.string() ?: return DynamicResponse(System.currentTimeMillis(), emptyList())).jsonObject
            lastDynamicStatus = root["status"]?.jsonPrimitive?.intOrNull
            lastMessage = root["message"]?.jsonPrimitive?.contentOrNull
            if (lastDynamicStatus != 0) return DynamicResponse(System.currentTimeMillis(), emptyList())
            val arr = root["result"]?.jsonArray ?: return DynamicResponse(System.currentTimeMillis(), emptyList())
            val parsed = arr.mapNotNull { el ->
                val obj = el.jsonObject
                val flow = TrafficLightFlow.fromRoadFlow(
                    obj["road_flow_direction"]?.jsonPrimitive?.contentOrNull,
                )
                val phases = ArrayList<TrafficLightPhase>(4)
                for (i in 1..4) {
                    val status = obj["lamp_status_$i"]?.jsonPrimitive?.intOrNull ?: break
                    val countDown = obj["count_down_$i"]?.jsonPrimitive?.intOrNull ?: 0
                    val period = obj["period_$i"]?.jsonPrimitive?.intOrNull ?: 0
                    val usablePeriod = period.takeIf { it in 1..600 }
                        ?: countDown.takeIf { it in 1..600 }
                        ?: break
                    phases += TrafficLightPhase(
                        state = TrafficLightState.fromLampStatus(status),
                        countdownSec = countDown.coerceAtLeast(0),
                        periodSec = usablePeriod,
                    )
                }
                if (phases.isEmpty()) null else Direction(flow, phases)
            }.distinctBy { it.flow }
            val useful = parsed.filter { it.flow != TrafficLightFlow.OTHER }
            val directions = if (useful.isNotEmpty()) useful else parsed
            // Baidu's timestamp is the data generation time. The countdown is relative
            // to it, so the UI must resolve against this stamp, not "now".
            val tsSec = arr.firstOrNull()?.jsonObject
                ?.get("timestamp")?.jsonPrimitive?.longOrNull
                ?: 0L
            val tsMs = when {
                tsSec > 10_000_000_000L -> tsSec          // already milliseconds
                tsSec > 0L -> tsSec * 1000L               // Baidu docs: seconds
                // VA-L02: a missing stamp must not be replaced by the phone clock.
                // 0 means "age unknown"; the resolver hides it instead of presenting
                // a stale countdown as freshly generated data.
                else -> 0L
            }
            return DynamicResponse(tsMs, directions)
        }
    }

    private companion object {
        const val TAG = "VeloraLamp"
        const val STATIC_URL = "https://api.map.baidu.com/api_lamp_service/v1/static_lamp_search"
        const val DYNAMIC_URL = "https://api.map.baidu.com/api_lamp_service/v1/dynamic_lamp_search"
        const val STATIC_CACHE_MS = 3_000L
        const val MAX_STATIC_DISTANCE_M = 2_000.0
        val FORWARD_POINTS_M = doubleArrayOf(50.0, 100.0, 150.0, 200.0)
    }
}
