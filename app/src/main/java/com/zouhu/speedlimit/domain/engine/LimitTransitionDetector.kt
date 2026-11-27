package com.zouhu.speedlimit.domain.engine

class LimitTransitionDetector(private val stableCount: Int = 3) {
    private var stable: Int? = null
    private var candidate: Int? = null
    private var candidateHits = 0

    fun update(raw: Int?): Int? {
        if (raw == stable) {
            candidate = null
            candidateHits = 0
            return stable
        }
        if (raw == candidate) {
            candidateHits++
        } else {
            candidate = raw
            candidateHits = 1
        }
        if (candidateHits >= stableCount) {
            stable = candidate
            candidate = null
            candidateHits = 0
        }
        return stable
    }

    fun reset(value: Int? = null) {
        stable = value
        candidate = null
        candidateHits = 0
    }
}