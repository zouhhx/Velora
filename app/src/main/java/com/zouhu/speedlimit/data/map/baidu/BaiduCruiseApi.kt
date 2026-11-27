package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.GpsSample
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Test seam for the cruise Provider: the production implementation is [BaiduCruiseApi],
 * while lifecycle tests can inject a controllable fake without changing the data path.
 */
interface CruiseRoadApi {
    fun detection(track: List<GpsSample>): BaiduResponse
    fun refresh(track: List<GpsSample>): BaiduResponse
    fun cancelCurrent()
}

/** One HTTP exchange, as seen by the debug capture. Never contains the request URL/AK. */
data class CruiseCallTrace(
    val endpoint: String,
    val origin: String,
    val gpsDirection: Int,
    val speedMps: Double,
    val pointList: String,
    val rawBody: String,
    val response: BaiduResponse,
)

/**
 * VA-C01 evidence sink. Implementations must be best-effort; the API wraps every call
 * in runCatching so a full disk or a broken serializer can never fail a cruise request.
 */
interface CruiseTraceSink {
    fun onHttp(trace: CruiseCallTrace)
    fun onGps(sample: GpsSample)
}

class BaiduCruiseApi(
    private val ak: String,
    private val baseUrl: String = "https://api.map.baidu.com",
    private val trace: CruiseTraceSink? = null,
) : CruiseRoadApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val callLock = Any()
    @Volatile
    private var activeCall: Call? = null

    override fun detection(track: List<GpsSample>): BaiduResponse = call("detection", track)

    override fun refresh(track: List<GpsSample>): BaiduResponse = call("refresh", track)

    /**
     * Cancel the blocking request that is currently on the wire. Coroutine cancellation
     * alone cannot interrupt OkHttp's synchronous execute(); the provider pairs this with
     * a generation check before it commits a response.
     */
    override fun cancelCurrent() {
        val call = synchronized(callLock) {
            activeCall.also { activeCall = null }
        }
        runCatching { call?.cancel() }
    }

    private fun call(endpoint: String, track: List<GpsSample>): BaiduResponse {
        val cur = track.lastOrNull() ?: return BaiduResponse(status = -1)
        val pointList = track.joinToString(";") { g ->
            listOf(
                g.longitude, g.latitude, 0, 0, 0,
                g.speedMps.toInt().coerceIn(0, 56),
                g.bearingDeg.toInt().coerceIn(0, 359),
                g.accuracyM.toInt().coerceIn(0, 2000),
                1, 2, 4,
                g.timestampMs / 1000,
            ).joinToString(",")
        }
        val url = "$baseUrl/api_cruise/v1/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("ak", ak)
            .addQueryParameter("origin", "${cur.latitude},${cur.longitude}")
            .addQueryParameter("gps_direction", cur.bearingDeg.toInt().toString())
            .addQueryParameter("speed", cur.speedMps.toString())
            .addQueryParameter("point_list", pointList)
            .addQueryParameter("ext_info", "20")
            .addQueryParameter("coord_type", "wgs84")
            .addQueryParameter("ret_coordtype", "gcj02")
            .build()
        val req = Request.Builder().url(url).get().build()
        val call = client.newCall(req)
        synchronized(callLock) { activeCall = call }
        try {
            call.execute().use { resp ->
                val body = resp.body?.string() ?: return BaiduResponse(status = -1)
                val parsed = json.decodeFromString(BaiduResponse.serializer(), body)
                trace?.let { sink ->
                    runCatching {
                        sink.onHttp(
                            CruiseCallTrace(
                                endpoint = endpoint,
                                origin = "${cur.latitude},${cur.longitude}",
                                gpsDirection = cur.bearingDeg.toInt(),
                                speedMps = cur.speedMps,
                                pointList = pointList,
                                rawBody = body,
                                response = parsed,
                            ),
                        )
                    }
                }
                return parsed
            }
        } finally {
            synchronized(callLock) {
                if (activeCall === call) activeCall = null
            }
        }
    }
}