package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.CameraInfo
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot

/**
 * Turns the HTTP cruise camera list into the interval-section state machine.
 *
 * Baidu camera types: 5 = interval start, 9 = interval end. Start/end are paired by
 * progression along the returned camera list: nearest start + the nearest end that is
 * at or ahead of it. Same-link sections use the offset difference; cross-link sections
 * use end.distance - start.distance as a linear approximation. The old sum of the two
 * distances was wrong whenever both endpoints were ahead of the car. The UI only needs
 * phase/distance/avg, and all of that stays in [IntervalSnapshot].
 */
object IntervalTracker {
    private const val START_TYPE = 5
    private const val END_TYPE = 9

    fun update(
        current: IntervalSnapshot,
        cameras: List<CameraInfo>,
        speedMps: Double,
        dtS: Double,
        hasSpeed: Boolean = true,
    ): IntervalSnapshot {
        val dt = dtS.coerceIn(0.0, 5.0)
        val pair = selectIntervalPair(cameras)
        val start = pair.start
        val end = pair.end

        return when (current.phase) {
            IntervalPhase.NONE -> {
                if (start == null) return current
                val length = lengthBetween(start, end)
                if (start.distanceM <= 5.0 && end != null) {
                    IntervalSnapshot(
                        phase = IntervalPhase.INSIDE,
                        lengthM = length.takeIf { it > 1.0 } ?: end.distanceM.coerceAtLeast(0.0),
                    )
                } else {
                    IntervalSnapshot(
                        phase = IntervalPhase.APPROACH,
                        distanceToStartM = start.distanceM.coerceAtLeast(0.0),
                        lengthM = length,
                    )
                }
            }

            IntervalPhase.APPROACH -> {
                var length = current.lengthM
                if (length <= 1.0 && end != null) {
                    length = current.distanceToStartM + end.distanceM
                }
                var next = IntervalEngine.advance(
                    current.copy(lengthM = length.coerceAtLeast(0.0)),
                    speedMps,
                    dt,
                    length,
                    hasSpeed = hasSpeed,
                )
                if (next.phase == IntervalPhase.INSIDE && next.lengthM <= 1.0 && end != null) {
                    next = next.copy(lengthM = next.traveledM + end.distanceM.coerceAtLeast(0.0))
                }
                if (next.phase == IntervalPhase.INSIDE && end != null && end.distanceM <= 5.0) {
                    IntervalSnapshot()
                } else {
                    next
                }
            }

            IntervalPhase.INSIDE -> {
                var length = current.lengthM
                if (length <= 1.0 && end != null) {
                    length = current.traveledM + end.distanceM
                }
                if (length <= 1.0) {
                    // No end camera yet: keep accumulating so the average circle still works.
                    val travel = if (hasSpeed) speedMps.coerceAtLeast(0.0) * dt else 0.0
                    val traveled = current.traveledM + travel
                    val elapsed = current.elapsedS + dt
                    val valid = current.validSpeedS + if (hasSpeed) dt else 0.0
                    current.copy(
                        traveledM = traveled,
                        elapsedS = elapsed,
                        avgKmh = if (elapsed > 0.0) traveled / elapsed * 3.6 else 0.0,
                        validSpeedS = valid,
                    )
                } else {
                    val next = IntervalEngine.advance(
                        current.copy(lengthM = length),
                        speedMps,
                        dt,
                        length,
                        hasSpeed = hasSpeed,
                    )
                    if (next.phase == IntervalPhase.INSIDE && end != null && end.distanceM <= 5.0) {
                        IntervalSnapshot()
                    } else {
                        next
                    }
                }
            }
        }
    }

    private fun lengthBetween(start: CameraInfo, end: CameraInfo?): Double {
        if (end == null) return 0.0
        if (start.linkId != null && end.linkId != null &&
            start.linkId == end.linkId && end.offsetM > start.offsetM
        ) {
            return end.offsetM - start.offsetM
        }
        // Cross-link: both distances are measured from the car, so the section length
        // is the difference, never the sum. This remains a linear approximation for
        // curved roads and needs real camera-list/route evidence to refine further.
        return (end.distanceM - start.distanceM).coerceAtLeast(0.0)
    }

    private data class IntervalPair(val start: CameraInfo?, val end: CameraInfo?)

    private fun selectIntervalPair(cameras: List<CameraInfo>): IntervalPair {
        val starts = cameras.filter { it.rawType == START_TYPE }.sortedBy { it.distanceM }
        val ends = cameras.filter { it.rawType == END_TYPE }.sortedBy { it.distanceM }
        val start = starts.firstOrNull()
            ?: return IntervalPair(null, ends.firstOrNull())
        // The end must belong to the same forward section, not an already-passed end
        // or the end of a later interval. A 5 m tolerance covers the start crossing.
        val end = ends.firstOrNull { it.distanceM >= start.distanceM - 5.0 }
        return IntervalPair(start, end)
    }
}
