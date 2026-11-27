package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.OverSpeedState

/**
 * 精确复刻原型 classify() 的安全契约：
 * 限速未知→UNKNOWN（不判超速）；无电子眼→NORMAL（永不标红）；
 * 区间内瞬时只 OVER 不 SEVERE；有电子眼时超阈值→SEVERE、超限速→OVER。
 */
object OverspeedDetector {
    fun classify(
        speedKmh: Double,
        limit: Int?,
        cameraAhead: Boolean,
        intervalPhase: IntervalPhase,
        thresholdPct: Int,
    ): OverSpeedState {
        if (limit == null) return OverSpeedState.UNKNOWN
        if (!cameraAhead) return OverSpeedState.NORMAL
        if (intervalPhase == IntervalPhase.INSIDE) {
            return if (speedKmh > limit) OverSpeedState.OVER else OverSpeedState.NORMAL
        }
        if (speedKmh > limit * (1 + thresholdPct / 100.0)) return OverSpeedState.SEVERE
        if (speedKmh > limit) return OverSpeedState.OVER
        return OverSpeedState.NORMAL
    }
}