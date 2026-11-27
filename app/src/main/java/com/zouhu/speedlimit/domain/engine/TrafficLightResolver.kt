package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.domain.model.TrafficLightState

data class TrafficLightReading(
    val state: TrafficLightState,
    /** Remaining seconds, or null when Baidu could not mine a countdown. */
    val seconds: Int?,
)

object TrafficLightResolver {
    /** Baidu sends 10000 when the countdown is unknown. */
    const val UNKNOWN_COUNTDOWN = 10_000

    /** Beyond this much clock skew a "future" stamp is treated as unreliable. */
    const val FUTURE_TOLERANCE_MS = 60_000L

    /**
     * Phases are stamped at one instant and each carries a confidence period. Advance
     * through them by period, not by countdown: Baidu switches lamp state when the
     * period elapses even if the displayed countdown has not reached zero.
     */
    fun resolve(info: TrafficLightInfo?, nowMs: Long): TrafficLightReading? {
        if (info == null || info.phases.isEmpty()) return null
        // VA-L02: without a generation stamp the age is unknown. Showing a countdown
        // anchored to "now" would silently turn a stale response into fresh data.
        if (info.timestampMs <= 0L) return null
        val skewMs = nowMs - info.timestampMs
        if (skewMs < -FUTURE_TOLERANCE_MS) return null
        var elapsed = (skewMs.coerceAtLeast(0L)) / 1000L
        for (phase in info.phases) {
            if (phase.periodSec <= 0) return null
            if (elapsed < phase.periodSec) {
                val seconds = if (phase.countdownSec in 1 until UNKNOWN_COUNTDOWN) {
                    // The lamp switches at the confidence period even when the mined
                    // countdown is longer, so never display beyond that period.
                    val displayCountdown = minOf(phase.countdownSec, phase.periodSec)
                    (displayCountdown - elapsed).toInt().coerceAtLeast(0)
                } else {
                    null
                }
                return TrafficLightReading(phase.state, seconds)
            }
            elapsed -= phase.periodSec
        }
        return null
    }
}
