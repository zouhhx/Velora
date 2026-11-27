package com.zouhu.speedlimit.domain.model

/** Where the fix came from. Decides whether speed/bearing can be trusted at all. */
enum class LocationSource { GPS, FUSED, NETWORK, UNKNOWN }

/**
 * One location sample.
 *
 * Field order matters: positional construction (tests, diagnostics) relies on the
 * first seven fields staying put, so every quality-related field is appended below.
 *
 * [hasSpeed] / [hasBearing] exist because [speedMps] and [bearingDeg] cannot express
 * "unknown" — a missing Doppler speed used to be stored as 0.0, which is
 * indistinguishable from standing still (and made a 0 km/h gauge look like a fact).
 */
data class GpsSample(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedMps: Double = 0.0,
    val bearingDeg: Double = 0.0,
    val accuracyM: Float = 0f,
    val timestampMs: Long = 0L,
    val hasFix: Boolean = false,
    /** Doppler speed accuracy in m/s; meaningful only when [hasSpeedAccuracy]. */
    val speedAccuracyMps: Float = 0f,
    val hasSpeedAccuracy: Boolean = false,
    val hasAccuracy: Boolean = false,
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,
    val source: LocationSource = LocationSource.UNKNOWN,
    val isMock: Boolean = false,
    /** Monotonic clock of the fix; 0 when unknown (JVM tests, legacy samples). */
    val elapsedRealtimeNanos: Long = 0L,
) {
    val speedKmh: Double get() = speedMps * 3.6

    /** Coordinate present and inside valid ranges; (0,0) is never a real fix. */
    val hasPosition: Boolean
        get() = hasFix &&
            latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}
