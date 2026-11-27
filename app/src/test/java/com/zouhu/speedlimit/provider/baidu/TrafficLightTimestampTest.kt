package com.zouhu.speedlimit.provider.baidu

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficLightTimestampTest {
    @Test
    fun `server seconds normalize to milliseconds`() {
        assertEquals(
            1_709_009_028_000L,
            localTrafficLightTimestampMs(
                rawStamp = 1_709_009_028L,
                clockSkewMs = 0L,
                localNowMs = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun `server stamp is mapped onto the local clock`() {
        val localNow = 1_709_009_045_000L
        // Message arrived 3.72s before the phone clock in this simulation.
        val skew = 1_709_009_041_280L - localNow
        assertEquals(
            1_709_009_031_720L,
            localTrafficLightTimestampMs(
                rawStamp = 1_709_009_028L,
                clockSkewMs = skew,
                localNowMs = localNow,
            ),
        )
    }

    @Test
    fun `missing stamp falls back to local now`() {
        assertEquals(
            123L,
            localTrafficLightTimestampMs(rawStamp = 0L, clockSkewMs = -5_000L, localNowMs = 123L),
        )
    }
}
