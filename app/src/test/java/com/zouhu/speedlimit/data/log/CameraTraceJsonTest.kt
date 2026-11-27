package com.zouhu.speedlimit.data.log

import com.zouhu.speedlimit.data.map.baidu.BaiduCamera
import com.zouhu.speedlimit.data.map.baidu.BaiduResponse
import com.zouhu.speedlimit.data.map.baidu.BaiduResult
import com.zouhu.speedlimit.data.map.baidu.BaiduRoute
import com.zouhu.speedlimit.data.map.baidu.CruiseCallTrace
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the VA-C01 capture schema before real road data is collected. If a field is
 * renamed here without updating the evaluator, the format document is wrong.
 */
class CameraTraceJsonTest {

    private fun response(): BaiduResponse = BaiduResponse(
        status = 0,
        message = "ok",
        result = BaiduResult(
            total = 1,
            routes = listOf(
                BaiduRoute(
                    route_id = "route-1",
                    data_version = "dv-1",
                    distance = 1200,
                    linkids = "111,112",
                    camera_list = listOf(
                        BaiduCamera(
                            type = 5,
                            priority = 0,
                            position = "120.100,30.100",
                            linkid = 111L,
                            offset = 10,
                            speed_limit = 80,
                        ),
                        BaiduCamera(
                            type = 1,
                            priority = 2,
                            position = "120.200,30.200",
                            linkid = 112L,
                            offset = 20,
                            speed_limit = null,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun trace(body: String = "{\"status\":0}") = CruiseCallTrace(
        endpoint = "detection",
        origin = "30.000,120.000",
        gpsDirection = 87,
        speedMps = 22.5,
        pointList = "120.0,30.0,0,0,0,22,87,5,1,2,4,1700000000;" +
            "120.1,30.1,0,0,0,23,88,5,1,2,4,1700000001",
        rawBody = body,
        response = response(),
    )

    @Test
    fun httpLineKeepsRequestContextAndEveryCameraField() {
        val json = Json.parseToJsonElement(CameraTraceJson.httpLine(trace(), 1_700_000_100_000)).jsonObject

        assertEquals(1, json["v"]!!.jsonPrimitive.content.toInt())
        assertEquals("camera_list", json["kind"]!!.jsonPrimitive.content)
        assertEquals("detection", json["endpoint"]!!.jsonPrimitive.content)

        val req = json["req"]!!.jsonObject
        assertEquals("30.000,120.000", req["origin"]!!.jsonPrimitive.content)
        assertEquals(87, req["gps_direction"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, req["point_count"]!!.jsonPrimitive.content.toInt())

        val route = json["resp"]!!.jsonObject["routes"]!!.jsonArray[0].jsonObject
        assertEquals("route-1", route["route_id"]!!.jsonPrimitive.content)
        assertEquals(2, route["camera_count"]!!.jsonPrimitive.content.toInt())
        val cameras = route["camera_list"]!!.jsonArray
        assertEquals(5, cameras[0].jsonObject["type"]!!.jsonPrimitive.content.toInt())
        assertEquals(111L, cameras[0].jsonObject["linkid"]!!.jsonPrimitive.content.toLong())
        assertEquals(80, cameras[0].jsonObject["speed_limit"]!!.jsonPrimitive.content.toInt())
        assertEquals("null", cameras[1].jsonObject["speed_limit"].toString())
    }

    @Test
    fun blankPointListIsNotReportedAsOnePoint() {
        val json = Json.parseToJsonElement(
            CameraTraceJson.httpLine(trace().copy(pointList = ""), 0),
        ).jsonObject
        assertEquals(0, json["req"]!!.jsonObject["point_count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun oversizedBodyIsTruncatedButOriginalSizeIsKept() {
        val body = "x".repeat(CameraTraceJson.MAX_BODY_CHARS + 10)
        val json = Json.parseToJsonElement(CameraTraceJson.httpLine(trace(body), 0)).jsonObject
        val raw = json["raw"]!!.jsonObject

        assertTrue(raw["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(CameraTraceJson.MAX_BODY_CHARS, raw["body"]!!.jsonPrimitive.content.length)
        assertEquals(body.length, raw["bytes"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun httpLineHasNoAkOrUrl() {
        val line = CameraTraceJson.httpLine(trace(), 0)
        assertFalse(line.contains("ak="))
        assertFalse(line.contains("api.map.baidu.com"))
    }

    @Test
    fun gpsLineKeepsMissingBearingAndSpeedAsUnknown() {
        val sample = GpsSample(
            latitude = 30.0,
            longitude = 120.0,
            timestampMs = 1_700_000_000_000,
            hasFix = true,
            source = LocationSource.GPS,
            hasBearing = false,
            hasSpeed = false,
        )
        val json = Json.parseToJsonElement(CameraTraceJson.gpsLine(sample, 1_700_000_000_500)).jsonObject

        assertEquals("gps", json["kind"]!!.jsonPrimitive.content)
        assertEquals("false", json["has_bearing"].toString())
        assertEquals("false", json["has_speed"].toString())
        assertEquals("true", json["has_position"].toString())
        assertEquals(0.0, json["bearing"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun metaLineCarriesBuildAndDevice() {
        val json = Json.parseToJsonElement(
            CameraTraceJson.metaLine("2.9.2", 34, "samsung SM-S9210", "Android 16", 36, 1L),
        ).jsonObject
        assertEquals("2.9.2(34)", json["app"]!!.jsonPrimitive.content)
        assertEquals(36, json["sdk"]!!.jsonPrimitive.content.toInt())
    }
}
