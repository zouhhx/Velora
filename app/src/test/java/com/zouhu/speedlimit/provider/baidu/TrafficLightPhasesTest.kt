package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.data.map.baidu.HorizonTrafficLightStatus
import com.zouhu.speedlimit.domain.model.TrafficLightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficLightPhasesTest {
    @Test
    fun `baidu sample phases keep periods and unknown countdown`() {
        val phases = horizonTrafficLightPhases(
            listOf(
                HorizonTrafficLightStatus(countDown = 56, lampStatus = 21, period = 56),
                HorizonTrafficLightStatus(countDown = 23, lampStatus = 23, period = 23),
                HorizonTrafficLightStatus(countDown = 10_000, lampStatus = 22, period = 4),
                HorizonTrafficLightStatus(countDown = 93, lampStatus = 21, period = 93),
            ),
        )
        assertEquals(4, phases?.size)
        assertEquals(TrafficLightState.RED, phases?.get(0)?.state)
        assertEquals(56, phases?.get(0)?.periodSec)
        assertEquals(TrafficLightState.GREEN, phases?.get(1)?.state)
        assertEquals(TrafficLightState.YELLOW, phases?.get(2)?.state)
        assertEquals(10_000, phases?.get(2)?.countdownSec)
        assertEquals(4, phases?.get(2)?.periodSec)
        assertEquals(93, phases?.get(3)?.periodSec)
    }

    @Test
    fun `timed unknown lamp anchors later phases`() {
        val phases = horizonTrafficLightPhases(
            listOf(
                HorizonTrafficLightStatus(countDown = 20, lampStatus = 21, period = 20),
                HorizonTrafficLightStatus(countDown = 20, lampStatus = 99, period = 20),
                HorizonTrafficLightStatus(countDown = 20, lampStatus = 23, period = 20),
            ),
        )
        assertEquals(3, phases?.size)
        assertEquals(TrafficLightState.RED, phases?.get(0)?.state)
        assertEquals(TrafficLightState.UNKNOWN, phases?.get(1)?.state)
        assertEquals(TrafficLightState.GREEN, phases?.get(2)?.state)
    }

    @Test
    fun `untimed unknown lamp ends the list`() {
        val phases = horizonTrafficLightPhases(
            listOf(
                HorizonTrafficLightStatus(countDown = 20, lampStatus = 21, period = 20),
                HorizonTrafficLightStatus(countDown = 0, lampStatus = 99, period = 0),
                HorizonTrafficLightStatus(countDown = 20, lampStatus = 23, period = 20),
            ),
        )
        assertEquals(1, phases?.size)
    }

    @Test
    fun `zero period with unknown countdown ends the list`() {
        val phases = horizonTrafficLightPhases(
            listOf(
                HorizonTrafficLightStatus(countDown = 20, lampStatus = 21, period = 20),
                HorizonTrafficLightStatus(countDown = 10_000, lampStatus = 21, period = 0),
                HorizonTrafficLightStatus(countDown = 20, lampStatus = 23, period = 20),
            ),
        )
        assertEquals(1, phases?.size)
        assertNull(horizonTrafficLightPhases(emptyList()))
    }

    @Test
    fun `missing period falls back to a plausible countdown`() {
        val phases = horizonTrafficLightPhases(
            listOf(HorizonTrafficLightStatus(countDown = 30, lampStatus = 23, period = null)),
        )
        assertEquals(30, phases?.get(0)?.periodSec)
    }
}
