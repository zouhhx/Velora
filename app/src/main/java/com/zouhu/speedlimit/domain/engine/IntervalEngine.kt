package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import com.zouhu.speedlimit.domain.model.OverSpeedState

object IntervalEngine {
    /** Below this coverage the average is too dependent on missing samples to display. */
    const val MIN_SPEED_COVERAGE = 0.70

    fun averageState(avgKmh: Double, limit: Int?, thresholdPct: Int): OverSpeedState {
        if (limit == null) return OverSpeedState.NORMAL
        if (avgKmh > limit * (1 + thresholdPct / 100.0)) return OverSpeedState.SEVERE
        if (avgKmh > limit) return OverSpeedState.OVER
        return OverSpeedState.NORMAL
    }

    fun advance(
        current: IntervalSnapshot,
        speedMps: Double,
        dtS: Double,
        lengthM: Double,
        hasSpeed: Boolean = true,
    ): IntervalSnapshot {
        if (current.phase == IntervalPhase.NONE) return current
        val dt = dtS.coerceAtLeast(0.0)
        val travel = if (hasSpeed) speedMps.coerceAtLeast(0.0) * dt else 0.0
        return when (current.phase) {
            IntervalPhase.APPROACH -> {
                val dist = current.distanceToStartM - travel
                if (dist <= 0.0) {
                    IntervalSnapshot(
                        phase = IntervalPhase.INSIDE,
                        distanceToStartM = 0.0,
                        traveledM = 0.0,
                        elapsedS = 0.0,
                        avgKmh = 0.0,
                        lengthM = lengthM,
                    )
                } else {
                    current.copy(distanceToStartM = dist, lengthM = lengthM)
                }
            }
            IntervalPhase.INSIDE -> {
                val traveled = current.traveledM + travel
                val elapsed = current.elapsedS + dt
                val valid = current.validSpeedS + if (hasSpeed) dt else 0.0
                val avg = if (elapsed > 0.0) traveled / elapsed * 3.6 else 0.0
                if (traveled >= lengthM) {
                    IntervalSnapshot(phase = IntervalPhase.NONE, lengthM = lengthM)
                } else {
                    current.copy(
                        traveledM = traveled,
                        elapsedS = elapsed,
                        avgKmh = avg,
                        lengthM = lengthM,
                        validSpeedS = valid,
                    )
                }
            }
            IntervalPhase.NONE -> current
        }
    }

    /**
     * Q-24 option B: hide the average when too much of the section had no speed sample,
     * instead of showing a number that is mathematically dragged toward zero.
     */
    fun hasReliableAverage(snapshot: IntervalSnapshot): Boolean =
        snapshot.phase == IntervalPhase.INSIDE &&
            snapshot.elapsedS > 0.0 &&
            snapshot.speedCoverage >= MIN_SPEED_COVERAGE
}