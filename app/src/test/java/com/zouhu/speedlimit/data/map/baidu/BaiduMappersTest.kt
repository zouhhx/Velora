package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Test

class BaiduMappersTest {
    @Test
    fun `http camera speed limit never becomes the road limit`() {
        val now = 1_700_000_000_000L
        val response = BaiduResponse(
            status = 0,
            result = BaiduResult(
                routes = listOf(
                    BaiduRoute(
                        route_id = "route-1",
                        camera_list = listOf(
                            BaiduCamera(
                                type = 1,
                                position = "121.0044,30.9979",
                                linkid = 10L,
                                offset = 100,
                                speed_limit = 60,
                            ),
                            BaiduCamera(
                                type = 5,
                                position = "121.0005,31.0005",
                                linkid = 11L,
                                offset = 200,
                                speed_limit = 80,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val gps = GpsSample(
            latitude = 31.0000,
            longitude = 121.0000,
            speedMps = 12.0,
            bearingDeg = 90.0,
            accuracyM = 5f,
            timestampMs = now,
            hasFix = true,
            hasAccuracy = true,
            hasSpeed = true,
            hasBearing = true,
            source = LocationSource.GPS,
        )

        val road = BaiduMappers.toRoadData(response, gps, now)

        assertEquals(null, road.speedLimit)
        assertEquals(60, road.cameraSpeedLimit)
        assertEquals(80, road.intervalSpeedLimit)
        assertEquals(CameraType.SPEED, road.cameraType)
    }
}
