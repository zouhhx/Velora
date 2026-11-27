package com.zouhu.speedlimit.data.location

import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-level rule is the contract the whole UI now depends on:
 * NONE = nothing, APPROXIMATE = map only, GOOD = everything.
 */
class LocationQualityTest {

    private val nowElapsed = 5_000_000_000_000L
    private val nowWall = 1_700_000_000_000L

    private fun gps(
        accuracy: Float = 8f,
        ageMs: Long = 0,
        speed: Boolean = true,
        speedAccuracy: Float = 0.5f,
        source: LocationSource = LocationSource.GPS,
    ) = GpsSample(
        latitude = 31.23,
        longitude = 121.47,
        speedMps = 12.0,
        bearingDeg = 90.0,
        accuracyM = accuracy,
        timestampMs = nowWall - ageMs,
        hasFix = true,
        speedAccuracyMps = speedAccuracy,
        hasSpeedAccuracy = speedAccuracy > 0f,
        hasAccuracy = accuracy > 0f,
        hasSpeed = speed,
        hasBearing = true,
        source = source,
        elapsedRealtimeNanos = nowElapsed - ageMs * 1_000_000L,
    )

    private fun tier(s: GpsSample) = LocationQuality.tierOf(s, nowElapsed, nowWall)

    @Test
    fun `no fix is NONE`() {
        assertEquals(LocationTier.NONE, tier(GpsSample()))
        assertFalse(LocationQuality.canShowMap(GpsSample(), nowElapsed, nowWall))
    }

    @Test
    fun `zero coordinates are never a position`() {
        val s = gps().copy(latitude = 0.0, longitude = 0.0)
        assertEquals(LocationTier.NONE, tier(s))
    }

    @Test
    fun `network fix shows the map but nothing else`() {
        val s = gps(accuracy = 300f, speed = false, source = LocationSource.NETWORK)
        assertEquals(LocationTier.APPROXIMATE, tier(s))
        assertTrue(LocationQuality.canShowMap(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canFeedRoadData(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }

    @Test
    fun `network fix is approximate even when it claims a good accuracy`() {
        val s = gps(accuracy = 10f, source = LocationSource.NETWORK)
        assertEquals(LocationTier.APPROXIMATE, tier(s))
        assertFalse(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }

    @Test
    fun `satellite fix with speed is good and drives everything`() {
        val s = gps()
        assertEquals(LocationTier.GOOD, tier(s))
        assertTrue(LocationQuality.canShowMap(s, nowElapsed, nowWall))
        assertTrue(LocationQuality.canFeedRoadData(s, nowElapsed, nowWall))
        assertTrue(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }

    @Test
    fun `coarse satellite fix still draws a map but no data`() {
        val s = gps(accuracy = 150f)
        assertEquals(LocationTier.APPROXIMATE, tier(s))
        assertTrue(LocationQuality.canShowMap(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canFeedRoadData(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }

    @Test
    fun `good position without doppler speed keeps road data but hides the gauge`() {
        val s = gps(speed = false)
        assertEquals(LocationTier.GOOD, tier(s))
        assertTrue(LocationQuality.canFeedRoadData(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }

    @Test
    fun `speed accuracy gate`() {
        assertTrue(LocationQuality.canShowSpeed(gps(speedAccuracy = 2.0f), nowElapsed, nowWall))
        assertFalse(LocationQuality.canShowSpeed(gps(speedAccuracy = 3.0f), nowElapsed, nowWall))
    }

    @Test
    fun `position older than two minutes disappears entirely`() {
        val s = gps(ageMs = LocationQuality.MAP_MAX_AGE_MS + 1_000)
        assertEquals(LocationTier.NONE, tier(s))
        assertFalse(LocationQuality.canShowMap(s, nowElapsed, nowWall))
    }

    @Test
    fun `road data goes stale long before the map does`() {
        val s = gps(ageMs = LocationQuality.DATA_MAX_AGE_MS + 500)
        assertEquals(LocationTier.GOOD, tier(s))
        assertTrue(LocationQuality.canShowMap(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canFeedRoadData(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }

    @Test
    fun `mocked fix may draw a map but never drives data`() {
        val s = gps().copy(isMock = true)
        assertEquals(LocationTier.APPROXIMATE, tier(s))
        assertFalse(LocationQuality.canFeedRoadData(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }

    @Test
    fun `legacy samples without a monotonic clock fall back to wall time`() {
        val s = gps().copy(elapsedRealtimeNanos = 0L, timestampMs = nowWall - 1_000)
        assertEquals(
            LocationTier.GOOD,
            LocationQuality.tierOf(s, 0L, nowWall),
        )
    }

    @Test
    fun `missing accuracy is approximate and never drives road data`() {
        val s = gps().copy(accuracyM = 0f, hasAccuracy = false)
        assertEquals(LocationTier.APPROXIMATE, tier(s))
        assertTrue(LocationQuality.canShowMap(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canFeedRoadData(s, nowElapsed, nowWall))
        assertFalse(LocationQuality.canShowSpeed(s, nowElapsed, nowWall))
    }
}
