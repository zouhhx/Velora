package com.zouhu.speedlimit.feature.accel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelSpeedFilterTest {
    @Test
    fun `stationary noise is suppressed`() {
        val filter = AccelSpeedFilter()
        filter.onFix(0.1, 1_000_000_000L)
        assertEquals(0.0, filter.tick(1_100_000_000L), 0.001)
    }

    @Test
    fun `short prediction stays bounded and finite`() {
        val filter = AccelSpeedFilter()
        filter.onFix(0.0, 1_000_000_000L)
        filter.tick(1_010_000_000L)
        filter.onFix(10.0, 2_000_000_000L)
        val value = filter.tick(2_050_000_000L)
        assertTrue(value.isFinite())
        assertTrue(value >= 0.0)
        assertTrue(value <= 36.0 + 2.0)
        assertEquals(36.0, filter.maxKmh(), 0.001)
    }

    @Test
    fun `reset clears prediction state`() {
        val filter = AccelSpeedFilter()
        filter.onFix(20.0, 1_000_000_000L)
        filter.tick(1_100_000_000L)
        filter.reset()
        assertEquals(0.0, filter.tick(2_000_000_000L), 0.001)
        assertEquals(0.0, filter.maxKmh(), 0.001)
    }
}
