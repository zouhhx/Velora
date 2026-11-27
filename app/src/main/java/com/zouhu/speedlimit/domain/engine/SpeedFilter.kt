package com.zouhu.speedlimit.domain.engine

object SpeedFilter {
    fun filter(rawKmh: Double, deadbandKmh: Double = 2.0): Double {
        val v = rawKmh.coerceAtLeast(0.0)
        return if (v < deadbandKmh) 0.0 else v
    }
}