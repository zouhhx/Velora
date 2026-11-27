package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.domain.model.TrafficLightPhase
import com.zouhu.speedlimit.domain.model.TrafficLightState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficLightResolverTest {
    // Mirrors the sample in Baidu's horizon docs: phases advance by period, and the
    // yellow phase has an unmined countdown (10000).
    private val info = TrafficLightInfo(
        distanceM = 80.0,
        timestampMs = 1_000_000L,
        phases = listOf(
            TrafficLightPhase(TrafficLightState.RED, 56, 56),
            TrafficLightPhase(TrafficLightState.GREEN, 23, 23),
            TrafficLightPhase(TrafficLightState.YELLOW, 10_000, 4),
            TrafficLightPhase(TrafficLightState.RED, 93, 93),
        ),
    )

    @Test
    fun `countdown runs from the stamped instant`() {
        val reading = TrafficLightResolver.resolve(info, 1_000_000L)
        assertEquals(TrafficLightState.RED, reading?.state)
        assertEquals(56, reading?.seconds)
    }

    @Test
    fun `countdown decreases with elapsed time`() {
        val reading = TrafficLightResolver.resolve(info, 1_010_000L)
        assertEquals(TrafficLightState.RED, reading?.state)
        assertEquals(46, reading?.seconds)
    }

    @Test
    fun `phases advance by period not by countdown`() {
        val reading = TrafficLightResolver.resolve(info, 1_060_000L)
        assertEquals(TrafficLightState.GREEN, reading?.state)
        assertEquals(19, reading?.seconds)
    }

    @Test
    fun `unmined countdown still shows the lamp state`() {
        val reading = TrafficLightResolver.resolve(info, 1_080_000L)
        assertEquals(TrafficLightState.YELLOW, reading?.state)
        assertNull(reading?.seconds)
    }

    @Test
    fun `last phase keeps counting until its period ends`() {
        val reading = TrafficLightResolver.resolve(info, 1_084_000L)
        assertEquals(TrafficLightState.RED, reading?.state)
        assertEquals(92, reading?.seconds)
    }

    @Test
    fun `stale data past the last period resolves to null`() {
        assertNull(TrafficLightResolver.resolve(info, 1_200_000L))
    }

    @Test
    fun `clock skew before the stamp does not go negative`() {
        val reading = TrafficLightResolver.resolve(info, 999_000L)
        assertEquals(56, reading?.seconds)
    }

    @Test
    fun `zero or missing phases resolve to null`() {
        assertNull(TrafficLightResolver.resolve(null, 1_000_000L))
        assertNull(TrafficLightResolver.resolve(TrafficLightInfo(), 1_000_000L))
    }

    @Test
    fun `countdown is capped by the confidence period`() {
        val capped = TrafficLightInfo(
            timestampMs = 1_000_000L,
            phases = listOf(
                TrafficLightPhase(TrafficLightState.RED, 30, 20),
                TrafficLightPhase(TrafficLightState.GREEN, 40, 40),
            ),
        )
        assertEquals(15, TrafficLightResolver.resolve(capped, 1_005_000L)?.seconds)
        assertEquals(TrafficLightState.GREEN, TrafficLightResolver.resolve(capped, 1_020_000L)?.state)
        assertEquals(40, TrafficLightResolver.resolve(capped, 1_020_000L)?.seconds)
        assertEquals(15, TrafficLightResolver.resolve(capped, 1_045_000L)?.seconds)
    }

    @Test
    fun `short countdown holds zero until the period ends`() {
        val shorter = TrafficLightInfo(
            timestampMs = 1_000_000L,
            phases = listOf(TrafficLightPhase(TrafficLightState.RED, 10, 20)),
        )
        val reading = TrafficLightResolver.resolve(shorter, 1_012_000L)
        assertEquals(TrafficLightState.RED, reading?.state)
        assertEquals(0, reading?.seconds)
        assertNull(TrafficLightResolver.resolve(shorter, 1_021_000L))
    }

    @Test
    fun `phase without a confidence period is unusable`() {
        val invalid = TrafficLightInfo(
            timestampMs = 1_000_000L,
            phases = listOf(TrafficLightPhase(TrafficLightState.RED, 30, 0)),
        )
        assertNull(TrafficLightResolver.resolve(invalid, 1_010_000L))
    }

    @Test
    fun `baidu lamp codes map to states`() {
        assertEquals(TrafficLightState.OFF, TrafficLightState.fromLampStatus(11))
        assertEquals(TrafficLightState.RED, TrafficLightState.fromLampStatus(21))
        assertEquals(TrafficLightState.YELLOW, TrafficLightState.fromLampStatus(22))
        assertEquals(TrafficLightState.GREEN, TrafficLightState.fromLampStatus(23))
        assertEquals(TrafficLightState.FLASHING_YELLOW, TrafficLightState.fromLampStatus(24))
        assertEquals(TrafficLightState.UNKNOWN, TrafficLightState.fromLampStatus(99))
    }

    @Test
    fun `missing timestamp hides the light instead of anchoring to now`() {
        val noStamp = info.copy(timestampMs = 0L)
        assertNull(TrafficLightResolver.resolve(noStamp, 1_000_000L))
    }

    @Test
    fun `far future timestamp is treated as unreliable`() {
        val future = info.copy(timestampMs = 1_000_000L + 10 * 60_000L)
        assertNull(TrafficLightResolver.resolve(future, 1_000_000L))
    }
}
