package com.zouhu.speedlimit.domain.engine

import java.util.Locale
import kotlin.math.roundToInt

object DistanceFormat {
    fun format(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m >= 1000.0) {
            String.format(Locale.US, "%.1fkm", m / 1000.0)
        } else {
            "${m.roundToInt()}m"
        }
    }
}