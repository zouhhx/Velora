package com.zouhu.speedlimit.feature.accel.core

/**
 * Port of the old SpeedEngine's between-fix speed prediction and correction filter.
 *
 * The timing state machine always consumes the raw GNSS/Doppler speed; this filter is
 * only for the live speed/gauge display so a 1 Hz receiver does not make the number
 * jump once per second. It never invents a timestamp, never sends a request and never
 * replaces a raw sample used for a result.
 */
class AccelSpeedFilter {
    private var lastFixNanos = 0L
    private var baseNanos = 0L
    private var lastTickNanos = 0L
    private var measuredSpeed = 0.0
    private var baseSpeed = 0.0
    private var displaySpeed = 0.0
    private var correction = 0.0
    private var accelEstimate = 0.0
    private var intervalEmaSeconds = 0.0
    private var maxKmh = 0.0

    fun onFix(speedMps: Double, elapsedNanos: Long) {
        if (!speedMps.isFinite() || speedMps < 0.0 || elapsedNanos <= 0L) return
        if (lastFixNanos > 0L && elapsedNanos <= lastFixNanos) return

        val dtFix = if (lastFixNanos > 0L) (elapsedNanos - lastFixNanos) / 1e9 else 0.0
        if (dtFix > 0.02 && dtFix < 5.0) {
            var rawAccel = (speedMps - measuredSpeed) / dtFix
            rawAccel = rawAccel.coerceIn(-MAX_PLAUSIBLE_ACCEL, MAX_PLAUSIBLE_ACCEL)
            if (speedMps < 0.6 && measuredSpeed < 0.6) rawAccel = 0.0
            accelEstimate = accelEstimate * 0.65 + rawAccel * 0.35
            intervalEmaSeconds = if (intervalEmaSeconds <= 0.0) {
                dtFix
            } else {
                intervalEmaSeconds * 0.70 + dtFix * 0.30
            }
        }

        var correctionAtFix = displaySpeed - speedMps
        if (dtFix > 3.0) correctionAtFix = 0.0
        baseSpeed = speedMps
        baseNanos = elapsedNanos
        correction = correctionAtFix
        measuredSpeed = speedMps
        lastFixNanos = elapsedNanos
        if (speedMps > 0.8) maxKmh = maxOf(maxKmh, speedMps * MPS_TO_KMH)
    }

    fun tick(nowNanos: Long): Double {
        if (lastFixNanos <= 0L) {
            displaySpeed = 0.0
            lastTickNanos = nowNanos
            return 0.0
        }
        if (lastTickNanos == 0L) {
            lastTickNanos = nowNanos
            return displayKmh()
        }
        var dt = (nowNanos - lastTickNanos) / 1e9
        if (dt <= 0.0) return displayKmh()
        if (dt > 0.25) dt = 0.25

        val age = (nowNanos - lastFixNanos) / 1e9
        val accel = if (age <= PREDICT_MAX_AGE_SECONDS) accelEstimate else 0.0
        var predicted = baseSpeed + accel * ((nowNanos - baseNanos) / 1e9)
        if (predicted < 0.0) predicted = 0.0
        correction *= Math.exp(-dt / CORRECTION_TAU_SECONDS)
        displaySpeed = predicted + correction
        if (displaySpeed < 0.0) displaySpeed = 0.0
        lastTickNanos = nowNanos
        return displayKmh()
    }

    fun displayKmh(): Double {
        val v = if (displaySpeed < 0.40) 0.0 else displaySpeed
        return v * MPS_TO_KMH
    }

    fun maxKmh(): Double = maxKmh

    fun gpsRateHz(): Double {
        if (intervalEmaSeconds <= 0.0) return 0.0
        return (1.0 / intervalEmaSeconds).coerceIn(0.0, 20.0)
    }

    fun reset() {
        lastFixNanos = 0L
        baseNanos = 0L
        lastTickNanos = 0L
        measuredSpeed = 0.0
        baseSpeed = 0.0
        displaySpeed = 0.0
        correction = 0.0
        accelEstimate = 0.0
        intervalEmaSeconds = 0.0
        maxKmh = 0.0
    }

    private companion object {
        const val MPS_TO_KMH = 3.6
        const val MAX_PLAUSIBLE_ACCEL = 12.0
        const val PREDICT_MAX_AGE_SECONDS = 2.0
        const val CORRECTION_TAU_SECONDS = 0.12
    }
}
