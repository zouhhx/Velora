package com.zouhu.speedlimit.domain.model

data class Lane(
    val directions: Set<LaneDirection> = setOf(LaneDirection.STRAIGHT),
    val validityPeriod: String? = null,
) {
    val isSpecial: Boolean get() = directions.any { it.isSpecial }
}