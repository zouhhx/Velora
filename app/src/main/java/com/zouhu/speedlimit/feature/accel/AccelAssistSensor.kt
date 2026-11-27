package com.zouhu.speedlimit.feature.accel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Direct port of the old zero-to-100 sensor listener:
 * linear acceleration is used when available, otherwise raw accelerometer with the
 * same 0.80 gravity high-pass; gyro magnitude rejects turning false positives.
 *
 * Registration is controlled by AccelViewModel and only lives while the independent
 * performance screen is foregrounded with an active preparation/test session.
 */
class AccelAssistSensor(
    context: Context,
    private val onReading: (linearMagnitude: Float, gyroMagnitude: Float, eventNs: Long) -> Unit,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val linearSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val available: Boolean get() = sensorManager != null && (linearSensor != null || rawAccelSensor != null)

    private var registered = false
    private var useRawAccelerometer = false
    private var gravityReady = false
    private val gravity = FloatArray(3)
    private var gyroMagnitude = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!registered) return
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                gyroMagnitude = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2],
                )
                return
            }

            var ax = event.values[0]
            var ay = event.values[1]
            var az = event.values[2]
            if (useRawAccelerometer) {
                if (!gravityReady) {
                    gravity[0] = ax
                    gravity[1] = ay
                    gravity[2] = az
                    gravityReady = true
                    return
                }
                val alpha = 0.80f
                gravity[0] = alpha * gravity[0] + (1f - alpha) * ax
                gravity[1] = alpha * gravity[1] + (1f - alpha) * ay
                gravity[2] = alpha * gravity[2] + (1f - alpha) * az
                ax -= gravity[0]
                ay -= gravity[1]
                az -= gravity[2]
            }

            val magnitude = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
            onReading(magnitude, gyroMagnitude, event.timestamp)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(): Boolean {
        if (!available) return false
        if (registered) return true
        val manager = sensorManager ?: return false
        val accel = linearSensor ?: rawAccelSensor ?: return false
        useRawAccelerometer = linearSensor == null
        gravityReady = false
        gyroMagnitude = 0f
        val accelOk = manager.registerListener(
            listener,
            accel,
            SensorManager.SENSOR_DELAY_GAME,
            mainHandler,
        )
        if (!accelOk) return false
        gyroSensor?.let {
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, mainHandler)
        }
        registered = true
        return true
    }

    fun stop() {
        if (registered) {
            sensorManager?.unregisterListener(listener)
        }
        registered = false
        gravityReady = false
        gyroMagnitude = 0f
    }
}
