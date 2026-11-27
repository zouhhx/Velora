package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.OverSpeedState
import org.junit.Assert.assertEquals
import org.junit.Test

class OverspeedDetectorTest {
    private fun classify(
        speed: Double,
        limit: Int? = 120,
        camera: Boolean = true,
        phase: IntervalPhase = IntervalPhase.NONE,
        th: Int = 10,
    ) = OverspeedDetector.classify(speed, limit, camera, phase, th)

    @Test fun unknownLimitNeverJudged() {
        assertEquals(OverSpeedState.UNKNOWN, classify(200.0, limit = null))
        assertEquals(OverSpeedState.UNKNOWN, classify(200.0, limit = null, camera = false))
    }

    @Test fun noCameraNeverRed() {
        assertEquals(OverSpeedState.NORMAL, classify(200.0, camera = false))
        assertEquals(OverSpeedState.NORMAL, classify(130.0, camera = false))
    }

    @Test fun atLimitNormal() {
        assertEquals(OverSpeedState.NORMAL, classify(120.0))
    }

    @Test fun overWithinThresholdSteadyRed() {
        assertEquals(OverSpeedState.OVER, classify(125.0))
        assertEquals(OverSpeedState.OVER, classify(132.0))
    }

    @Test fun beyondThresholdFlash() {
        assertEquals(OverSpeedState.SEVERE, classify(133.0))
        assertEquals(OverSpeedState.SEVERE, classify(200.0))
    }

    @Test fun intervalInsideInstantSteadyOnly() {
        assertEquals(OverSpeedState.OVER, classify(125.0, phase = IntervalPhase.INSIDE))
        assertEquals(OverSpeedState.OVER, classify(200.0, phase = IntervalPhase.INSIDE))
        assertEquals(OverSpeedState.NORMAL, classify(110.0, phase = IntervalPhase.INSIDE))
    }

    @Test fun thresholdZeroAnyOverspeedFlash() {
        assertEquals(OverSpeedState.SEVERE, classify(121.0, th = 0))
    }

    @Test fun thresholdTwenty() {
        assertEquals(OverSpeedState.OVER, classify(140.0, th = 20))
        assertEquals(OverSpeedState.SEVERE, classify(150.0, th = 20))
    }
}