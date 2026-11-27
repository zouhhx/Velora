package com.zouhu.speedlimit.domain.model

data class IntervalSnapshot(
    val phase: IntervalPhase = IntervalPhase.NONE,
    val distanceToStartM: Double = 0.0,
    val traveledM: Double = 0.0,
    val elapsedS: Double = 0.0,
    val avgKmh: Double = 0.0,
    val lengthM: Double = 0.0,
    /** Time inside the section for which a real speed sample existed (VA-I03/Q-24). */
    val validSpeedS: Double = 0.0,
) {
    val remainingM: Double get() = (lengthM - traveledM).coerceAtLeast(0.0)

    /** 1.0 = every tick had speed; lower means the average is built on missing samples. */
    val speedCoverage: Double
        get() = if (elapsedS > 0.0) (validSpeedS / elapsedS).coerceIn(0.0, 1.0) else 1.0
}