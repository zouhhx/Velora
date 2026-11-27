package com.zouhu.speedlimit.data.location

import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.LocationSource

/**
 * How usable a fix is. Three levels only — the app never renders "unknown" text,
 * it renders *less UI*: NONE = nothing, APPROXIMATE = map only, GOOD = everything.
 */
enum class LocationTier { NONE, APPROXIMATE, GOOD }

/**
 * Single source of truth for "what may this fix be used for".
 *
 * Deliberately pure (caller passes both clocks) so it can be unit-tested without
 * Android and so the same rule is applied by the UI and by the data providers.
 */
object LocationQuality {
    /** A map without a position is pointless, but a 2-minute-old one is still roughly right. */
    const val MAP_MAX_AGE_MS = 120_000L

    /** Road matching (limit/camera/lights) must be current, or it describes another road. */
    const val DATA_MAX_AGE_MS = 5_000L

    /** A speedometer is only allowed to show a speed that is being measured right now. */
    const val SPEED_MAX_AGE_MS = 3_000L

    /** Beyond this the point can snap to the wrong link; below it, ordinary urban driving. */
    const val GOOD_ACCURACY_M = 100f

    /** Doppler speed error we accept on the gauge (2.5 m/s ≈ 9 km/h). */
    const val SPEED_ACCURACY_LIMIT_MPS = 2.5f

    /** Fallback when the platform does not report speed accuracy. */
    const val SPEED_FALLBACK_ACCURACY_M = 50f


    fun ageMs(sample: GpsSample, nowElapsedNanos: Long, nowWallMs: Long): Long {
        if (sample.elapsedRealtimeNanos > 0L && nowElapsedNanos > 0L) {
            return ((nowElapsedNanos - sample.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        }
        if (sample.timestampMs > 0L && nowWallMs > 0L) {
            return (nowWallMs - sample.timestampMs).coerceAtLeast(0L)
        }
        return Long.MAX_VALUE
    }

    /** Reported horizontal accuracy, or null when the platform gave none. */
    fun accuracyOf(sample: GpsSample): Float? =
        sample.accuracyM.takeIf { it.isFinite() && it > 0f }


    fun tierOf(sample: GpsSample, nowElapsedNanos: Long, nowWallMs: Long): LocationTier {
        if (!sample.hasPosition) return LocationTier.NONE
        if (ageMs(sample, nowElapsedNanos, nowWallMs) > MAP_MAX_AGE_MS) return LocationTier.NONE
        // A mocked fix may draw a map, but must never drive road data or a speed reading.
        if (sample.isMock) return LocationTier.APPROXIMATE
        if (sample.source == LocationSource.NETWORK) return LocationTier.APPROXIMATE
        // VA-G02 / Q-28: an unreported accuracy is unknown, not a good 30 m guess.
        // Unknown precision may draw the map but must not drive road data.
        val accuracy = accuracyOf(sample) ?: return LocationTier.APPROXIMATE
        return if (accuracy > GOOD_ACCURACY_M) {
            LocationTier.APPROXIMATE
        } else {
            LocationTier.GOOD
        }
    }

    fun canShowMap(sample: GpsSample, nowElapsedNanos: Long, nowWallMs: Long): Boolean =
        tierOf(sample, nowElapsedNanos, nowWallMs) != LocationTier.NONE

    /** Limit / camera / traffic-light lookups: needs a current, link-accurate position. */
    fun canFeedRoadData(sample: GpsSample, nowElapsedNanos: Long, nowWallMs: Long): Boolean =
        tierOf(sample, nowElapsedNanos, nowWallMs) == LocationTier.GOOD &&
            ageMs(sample, nowElapsedNanos, nowWallMs) <= DATA_MAX_AGE_MS

    /** The speedometer: only a live, satellite-derived, accuracy-checked speed. */
    fun canShowSpeed(sample: GpsSample, nowElapsedNanos: Long, nowWallMs: Long): Boolean {
        if (tierOf(sample, nowElapsedNanos, nowWallMs) != LocationTier.GOOD) return false
        if (!sample.hasSpeed) return false
        if (sample.source != LocationSource.GPS && sample.source != LocationSource.FUSED) return false
        if (ageMs(sample, nowElapsedNanos, nowWallMs) > SPEED_MAX_AGE_MS) return false
        return if (sample.hasSpeedAccuracy && sample.speedAccuracyMps.isFinite()) {
            sample.speedAccuracyMps <= SPEED_ACCURACY_LIMIT_MPS
        } else {
            (accuracyOf(sample) ?: return false) <= SPEED_FALLBACK_ACCURACY_M
        }
    }
}
