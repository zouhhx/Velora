package com.zouhu.speedlimit.data.nav

import com.zouhu.speedlimit.domain.model.NavState
import com.zouhu.speedlimit.domain.model.TrafficLightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimNavSourceTest {
    private val src = SimNavSource()

    @Test
    fun cruiseSegment() {
        val s = src.sample(2.0)
        assertEquals(NavState.CRUISE, s.navState)
        assertNotNull(s.eta)
        assertTrue(s.route.size >= 2)
    }

    @Test
    fun turnSegmentHasStructuredTurn() {
        val s = src.sample(12.0)
        assertEquals(NavState.TURN, s.navState)
        assertNotNull(s.turn)
        assertTrue(s.turn!!.distanceM > 0)
    }

    @Test
    fun intersectionHasRecommendedLane() {
        val s = src.sample(24.0)
        assertEquals(NavState.INTERSECTION, s.navState)
        assertEquals(3, s.lanes.size)
        assertEquals(1, s.lanes.count { it.recommended })
    }

    @Test
    fun lightSegmentCountdown() {
        val red = src.sample(30.0)
        assertEquals(NavState.LIGHT, red.navState)
        assertEquals(TrafficLightState.RED, red.trafficLight!!.state)
        assertTrue(red.trafficLight!!.countdownSec!! > 0)
        val green = src.sample(35.0)
        assertEquals(TrafficLightState.GREEN, green.trafficLight!!.state)
    }

    @Test
    fun cameraSegment() {
        val s = src.sample(44.0)
        assertEquals(NavState.CAMERA, s.navState)
        assertNotNull(s.camera)
        assertNotNull(s.camera!!.limit)
    }

    @Test
    fun rerouteAndArrived() {
        assertEquals(NavState.REROUTE, src.sample(50.0).navState)
        assertTrue(src.sample(50.0).reroute)
        val a = src.sample(57.0)
        assertEquals(NavState.ARRIVED, a.navState)
        assertTrue(a.arrived)
    }
}