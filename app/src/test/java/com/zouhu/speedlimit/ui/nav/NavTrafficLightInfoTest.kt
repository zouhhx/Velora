package com.zouhu.speedlimit.ui.nav

import com.zouhu.speedlimit.domain.engine.TrafficLightResolver
import com.zouhu.speedlimit.domain.model.NavTrafficLight
import com.zouhu.speedlimit.domain.model.TrafficLightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** VA-L03: the HUD must anchor the countdown to the reading, not to recomposition. */
class NavTrafficLightInfoTest {

    @Test
    fun `stamp comes from the reading and stays stable across recompositions`() {
        val light = NavTrafficLight(TrafficLightState.RED, 7, receivedAtMs = 1_234L)
        val first = navTrafficLightInfo(light)
        val second = navTrafficLightInfo(light)

        assertEquals(1_234L, first?.timestampMs)
        assertEquals(first, second)
        assertEquals(7, first?.phases?.single()?.countdownSec)
        assertEquals(8, first?.phases?.single()?.periodSec)
        // The capsule range gate, not a displayed distance.
        assertEquals(0.0, first?.distanceM ?: -1.0, 0.0)
    }

    @Test
    fun `unknown countdown keeps the state without a number`() {
        val light = NavTrafficLight(TrafficLightState.GREEN, null, receivedAtMs = 5L)
        val info = navTrafficLightInfo(light)

        assertEquals(TrafficLightState.GREEN, info?.phases?.single()?.state)
        assertEquals(TrafficLightResolver.UNKNOWN_COUNTDOWN, info?.phases?.single()?.countdownSec)
    }

    @Test
    fun `no light maps to no capsule`() {
        assertNull(navTrafficLightInfo(null))
    }
}
