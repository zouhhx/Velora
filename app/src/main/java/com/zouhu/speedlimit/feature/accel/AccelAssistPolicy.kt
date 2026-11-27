package com.zouhu.speedlimit.feature.accel

/**
 * Pure policy for the optional accelerometer launch assist. Sensor callbacks are only
 * subscribed while the AccelActivity is foregrounded and a test session is waiting or
 * running; there is no cruise-time sensor registration.
 */
object AccelAssistPolicy {
    fun shouldRegister(
        assistEnabled: Boolean,
        foreground: Boolean,
        sessionActive: Boolean,
        sessionFinished: Boolean,
        sensorAvailable: Boolean,
    ): Boolean = assistEnabled && foreground && sessionActive && !sessionFinished && sensorAvailable

    fun canToggle(sessionRunning: Boolean): Boolean = !sessionRunning
}
