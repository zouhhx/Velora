package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.domain.model.GpsSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q-24-ish quota guard for the HTTP cruise API; no Android, no network. */
class CruiseRequestPolicyTest {

    private val t0 = 1_000_000L

    private fun gps(
        speedKmh: Double = 0.0,
        lat: Double = 32.0,
        lng: Double = 118.0,
    ) = GpsSample(
        latitude = lat,
        longitude = lng,
        speedMps = speedKmh / 3.6,
        hasFix = true,
        hasSpeed = true,
    )

    @Test
    fun `first request is allowed and uses detection`() {
        val policy = CruiseRequestPolicy()
        val d = policy.shouldRequest(t0, gps(), hasValidRoadData = false)
        assertTrue(d.request)
        assertTrue(d.detection)
    }

    @Test
    fun `stationary with valid data waits for movement`() {
        val policy = CruiseRequestPolicy()
        assertTrue(policy.shouldRequest(t0, gps(), false).request)

        val tenSeconds = policy.shouldRequest(t0 + 10_000, gps(), true)
        assertFalse(tenSeconds.request)

        // 20 m is below the 25 m threshold.
        val twentyMetres = policy.shouldRequest(t0 + 20_000, gps(lat = 32.00018), true)
        assertFalse(twentyMetres.request)

        // ~33 m is enough even while stationary.
        val moved = policy.shouldRequest(t0 + 30_000, gps(lat = 32.00030), true)
        assertTrue(moved.request)
    }

    @Test
    fun `moving is rate limited to thirty seconds`() {
        val policy = CruiseRequestPolicy()
        assertTrue(policy.shouldRequest(t0, gps(speedKmh = 30.0), false).request)
        assertFalse(policy.shouldRequest(t0 + 10_000, gps(speedKmh = 30.0), true).request)
        assertTrue(policy.shouldRequest(t0 + 31_000, gps(speedKmh = 30.0), true).request)
    }

    @Test
    fun `no valid data retries after thirty seconds while stationary`() {
        val policy = CruiseRequestPolicy()
        assertTrue(policy.shouldRequest(t0, gps(), false).request)
        assertFalse(policy.shouldRequest(t0 + 10_000, gps(), false).request)
        assertTrue(policy.shouldRequest(t0 + 31_000, gps(), false).request)
    }

    @Test
    fun `status 302 blocks for fifteen minutes`() {
        val policy = CruiseRequestPolicy()
        assertTrue(policy.shouldRequest(t0, gps(), false).request)
        assertTrue(policy.onResponse(t0, 302))

        assertFalse(policy.shouldRequest(t0 + 60_000, gps(lat = 32.1), false).request)
        assertFalse(policy.shouldRequest(t0 + 14 * 60_000, gps(lat = 32.1), false).request)
        assertTrue(policy.shouldRequest(t0 + 15 * 60_000 + 1, gps(lat = 32.1), false).request)
    }

    @Test
    fun `status 0 counts as success and detection resumes after a minute`() {
        val policy = CruiseRequestPolicy()
        assertTrue(policy.shouldRequest(t0, gps(), false).request)
        assertFalse(policy.onResponse(t0, 0))

        val afterThirty = policy.shouldRequest(t0 + 31_000, gps(), false)
        assertTrue(afterThirty.request)
        assertFalse(afterThirty.detection)

        val afterSixty = policy.shouldRequest(t0 + 62_000, gps(lat = 32.1), false)
        assertTrue(afterSixty.request)
        assertTrue(afterSixty.detection)
    }
}
