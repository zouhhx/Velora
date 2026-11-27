package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.TrafficLightState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 红绿灯倒计时 (lamp api) — the standalone WebAPI the user activated ("红绿灯服务").
 * POST probe_lamp uploads the current fix and returns the ahead crossing's lamp
 * state + countdown. This is independent of the 超视距 WS traffic_light push (which
 * requires a separate advanced activation), so it works with the opened 红绿灯服务.
 */
class LampProbeClient(
    private val ak: String,
    private val entityName: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class Lamp(
        val distanceM: Int,
        val state: TrafficLightState,
        val countdownSec: Int?,
        val periodSec: Int?,
        val flow: String,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Volatile
    var lastStatus: Int? = null
        private set

    @Volatile
    var lastMessage: String? = null
        private set

    suspend fun probe(lat: Double, lng: Double, speedKmh: Double, dir: Int, timeSec: Long): List<Lamp> =
        withContext(Dispatchers.IO) {
            runCatching {
                val form = FormBody.Builder()
                    .add("entity_name", entityName)
                    .add("lng", lng.toString())
                    .add("lat", lat.toString())
                    .add("loc_time", timeSec.toString())
                    .add("speed", speedKmh.toString())
                    .add("direction", dir.toString())
                    .add("coord_type", "wgs84")
                    .add("ak", ak)
                    .add("first_crossing", "0")
                    .build()
                val request = Request.Builder()
                    .url("https://api.map.baidu.com/api_lamp_service/v1/probe_lamp")
                    .post(form)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val root = json.parseToJsonElement(resp.body?.string() ?: return@use emptyList()).jsonObject
                    lastStatus = root["status"]?.jsonPrimitive?.intOrNull
                    lastMessage = root["message"]?.jsonPrimitive?.contentOrNull
                    if (lastStatus != 0) return@use emptyList()
                    val results = root["result"]?.jsonArray ?: return@use emptyList()
                    results.flatMap { entry ->
                        val obj = entry.jsonObject
                        val distance = obj["crossing"]?.jsonObject?.get("distance")?.jsonPrimitive?.intOrNull ?: 0
                        obj["lamps"]?.jsonArray.orEmpty().mapNotNull { lampEl ->
                            val lamp = lampEl.jsonObject
                            val status = lamp["lamp_status_1"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                            val cd = lamp["count_down_1"]?.jsonPrimitive?.intOrNull
                            val period = lamp["period_1"]?.jsonPrimitive?.intOrNull
                            Lamp(
                                distanceM = distance,
                                state = TrafficLightState.fromLampStatus(status),
                                countdownSec = cd?.takeIf { it in 1..9999 },
                                periodSec = period?.takeIf { it > 0 },
                                flow = lamp["road_flow_direction"]?.jsonPrimitive?.contentOrNull ?: "",
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
}