package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.domain.model.CameraInfo
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.ConnectionStatus
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import com.zouhu.speedlimit.domain.model.Lane
import com.zouhu.speedlimit.domain.model.LaneDirection
import com.zouhu.speedlimit.domain.model.RoadData
import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.domain.model.TrafficLightPhase
import com.zouhu.speedlimit.domain.model.TrafficLightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadDataMergerTest {
    private val horizon = RoadData(
        roadName = "新金桥路",
        speedLimit = 60,
        speedLimitType = 0,
        cameraSpeedLimit = 40,
        cameras = listOf(CameraInfo(type = CameraType.SPEED, speedLimit = 40, distanceM = 120.0)),
        lanes = listOf(Lane(setOf(LaneDirection.STRAIGHT))),
        cameraAhead = true,
        cameraType = CameraType.SPEED,
        distanceToCameraM = 120.0,
        timestampMs = 200,
        isValid = true,
    )
    private val http = RoadData(
        // HTTP has no current-link limit field; 50 belongs to the camera only.
        speedLimit = null,
        cameraSpeedLimit = 50,
        cameras = listOf(CameraInfo(type = CameraType.SPEED, speedLimit = 50, distanceM = 80.0)),
        cameraAhead = true,
        cameraType = CameraType.SPEED,
        distanceToCameraM = 80.0,
        timestampMs = 100,
        isValid = true,
    )

    @Test
    fun `horizon road limit lanes and cameras win`() {
        val merged = RoadDataMerger.merge(horizon, http)
        assertEquals(60, merged.speedLimit)
        assertEquals("新金桥路", merged.roadName)
        assertEquals(1, merged.lanes.size)
        assertEquals(120.0, merged.distanceToCameraM, 0.0)
        assertEquals(200, merged.timestampMs)
        assertTrue(merged.isValid)
    }

    @Test
    fun `http camera limit is not promoted to the road limit`() {
        val merged = RoadDataMerger.merge(
            RoadData(timestampMs = 300, isValid = true),
            http,
        )
        assertEquals(null, merged.speedLimit)
        assertEquals(50, merged.cameraSpeedLimit)
        assertEquals(80.0, merged.distanceToCameraM, 0.0)
        assertEquals(1, merged.cameras.size)
    }

    @Test
    fun `road and camera limits stay separate when both exist`() {
        val merged = RoadDataMerger.merge(horizon, http)
        assertEquals(60, merged.speedLimit)
        assertEquals(40, merged.cameraSpeedLimit)
    }

    @Test
    fun `horizon non-speed cameras do not mask an http speed camera`() {
        val horizonOnlyOther = RoadData(
            cameras = listOf(CameraInfo(type = CameraType.OTHER, distanceM = 30.0)),
            cameraAhead = false,
            cameraType = CameraType.OTHER,
            distanceToCameraM = 0.0,
            timestampMs = 300,
            isValid = true,
        )
        val httpSpeed = RoadData(
            cameraSpeedLimit = 60,
            cameras = listOf(
                CameraInfo(type = CameraType.SPEED, speedLimit = 60, distanceM = 120.0),
            ),
            cameraAhead = true,
            cameraType = CameraType.SPEED,
            distanceToCameraM = 120.0,
            timestampMs = 200,
            isValid = true,
        )

        val merged = RoadDataMerger.merge(horizonOnlyOther, httpSpeed)

        assertTrue(merged.cameraAhead)
        assertEquals(CameraType.SPEED, merged.cameraType)
        assertEquals(120.0, merged.distanceToCameraM, 0.0)
        assertEquals(60, merged.cameraSpeedLimit)
        assertEquals(1, merged.cameras.size)
    }

    @Test
    fun `invalid horizon does not lend stale fields to valid http data`() {
        val staleHorizon = RoadData(
            roadId = "link-stale",
            speedLimit = 120,
            cameraSpeedLimit = 120,
            cameras = listOf(CameraInfo(type = CameraType.SPEED, speedLimit = 120, distanceM = 10.0)),
            cameraAhead = true,
            cameraType = CameraType.SPEED,
            distanceToCameraM = 10.0,
            confidence = 0.99,
            timestampMs = 999,
            isValid = false,
        )

        val merged = RoadDataMerger.merge(staleHorizon, http)

        assertEquals(null, merged.speedLimit)
        assertEquals(50, merged.cameraSpeedLimit)
        assertEquals(CameraType.SPEED, merged.cameraType)
        assertEquals(80.0, merged.distanceToCameraM, 0.0)
        assertEquals(null, merged.roadId)
        assertEquals(100, merged.timestampMs)
        assertEquals(0.0, merged.confidence, 0.0)
        assertTrue(merged.isValid)
    }

    @Test
    fun `interval limit follows the interval source`() {
        val horizonWithInterval = horizon.copy(
            interval = IntervalSnapshot(phase = IntervalPhase.INSIDE),
            intervalSpeedLimit = 100,
        )
        val httpWithInterval = http.copy(
            interval = IntervalSnapshot(phase = IntervalPhase.APPROACH),
            intervalSpeedLimit = 50,
        )

        val merged = RoadDataMerger.merge(horizonWithInterval, httpWithInterval)

        assertEquals(IntervalPhase.INSIDE, merged.interval.phase)
        assertEquals(100, merged.intervalSpeedLimit)
    }

    @Test
    fun `horizon traffic light survives the merge`() {
        val light = TrafficLightInfo(
            distanceM = 90.0,
            timestampMs = 500,
            phases = listOf(TrafficLightPhase(TrafficLightState.RED, 20, 20)),
        )
        val merged = RoadDataMerger.merge(horizon.copy(trafficLight = light), http)
        assertEquals(light, merged.trafficLight)

        val cleared = RoadDataMerger.merge(horizon, http)
        assertEquals(null, cleared.trafficLight)
    }

    @Test
    fun `status prefers online then connecting`() {
        assertEquals(
            ConnectionStatus.ONLINE,
            RoadDataMerger.connectionStatus(ConnectionStatus.OFFLINE, ConnectionStatus.ONLINE),
        )
        assertEquals(
            ConnectionStatus.CONNECTING,
            RoadDataMerger.connectionStatus(ConnectionStatus.OFFLINE, ConnectionStatus.CONNECTING),
        )
        assertEquals(
            ConnectionStatus.OFFLINE,
            RoadDataMerger.connectionStatus(ConnectionStatus.OFFLINE, ConnectionStatus.OFFLINE),
        )
    }

    @Test
    fun `invalid sources produce invalid data`() {
        val merged = RoadDataMerger.merge(RoadData(), RoadData())
        assertFalse(merged.isValid)
        assertEquals(null, merged.speedLimit)
    }
}
