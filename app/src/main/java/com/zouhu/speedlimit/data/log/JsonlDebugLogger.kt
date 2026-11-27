package com.zouhu.speedlimit.data.log

import android.content.Context
import com.zouhu.speedlimit.domain.model.GpsSample
import com.zouhu.speedlimit.domain.model.RoadData
import java.io.File

class JsonlDebugLogger(context: Context) {
    private val file = File(context.filesDir, "slm_debug.jsonl")
    private val maxLines = 5000
    private var counter = 0

    @Synchronized
    fun log(road: RoadData, gps: GpsSample) {
        val line = buildString {
            append("{")
            append("\"ts\":${road.timestampMs},")
            append("\"lat\":${gps.latitude},\"lng\":${gps.longitude},")
            append("\"speed_kmh\":${gps.speedKmh},\"heading\":${gps.bearingDeg},")
            append("\"limit\":${road.speedLimit ?: "null"},")
            append("\"camera_ahead\":${road.cameraAhead},\"distance_m\":${road.distanceToCameraM},")
            append("\"lanes\":${road.lanes.size},\"valid\":${road.isValid}")
            append("}")
        }
        file.appendText(line + "\n")
        if (++counter % 200 == 0) trim()
    }

    private fun trim() {
        if (!file.exists()) return
        val lines = file.readLines()
        if (lines.size > maxLines) file.writeText(lines.takeLast(maxLines).joinToString("\n") + "\n")
    }

    @Synchronized
    fun clear() {
        file.delete()
        counter = 0
    }
}