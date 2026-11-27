package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import com.zouhu.speedlimit.domain.model.OverSpeedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalEngineTest {
    @Test fun averageTiers() {
        assertEquals(OverSpeedState.NORMAL, IntervalEngine.averageState(200.0, null, 10))
        assertEquals(OverSpeedState.NORMAL, IntervalEngine.averageState(120.0, 120, 10))
        assertEquals(OverSpeedState.OVER, IntervalEngine.averageState(125.0, 120, 10))
        assertEquals(OverSpeedState.OVER, IntervalEngine.averageState(132.0, 120, 10))
        assertEquals(OverSpeedState.SEVERE, IntervalEngine.averageState(133.0, 120, 10))
    }

    @Test fun approachDecreasesDistance() {
        val s = IntervalSnapshot(phase = IntervalPhase.APPROACH, distanceToStartM = 100.0, lengthM = 500.0)
        val next = IntervalEngine.advance(s, speedMps = 10.0, dtS = 1.0, lengthM = 500.0)
        assertEquals(IntervalPhase.APPROACH, next.phase)
        assertEquals(90.0, next.distanceToStartM, 0.001)
    }

    @Test fun approachEntersInside() {
        val s = IntervalSnapshot(phase = IntervalPhase.APPROACH, distanceToStartM = 5.0, lengthM = 500.0)
        val next = IntervalEngine.advance(s, 10.0, 1.0, 500.0)
        assertEquals(IntervalPhase.INSIDE, next.phase)
        assertEquals(0.0, next.traveledM, 0.001)
        assertEquals(0.0, next.avgKmh, 0.001)
    }

    @Test fun insideAccumulatesAverage() {
        val s = IntervalSnapshot(phase = IntervalPhase.INSIDE, traveledM = 0.0, elapsedS = 0.0, lengthM = 500.0)
        val next = IntervalEngine.advance(s, 10.0, 1.0, 500.0)
        assertEquals(IntervalPhase.INSIDE, next.phase)
        assertEquals(10.0, next.traveledM, 0.001)
        assertEquals(36.0, next.avgKmh, 0.001)
    }

    @Test fun insideExitsWhenComplete() {
        val s = IntervalSnapshot(phase = IntervalPhase.INSIDE, traveledM = 495.0, elapsedS = 49.5, lengthM = 500.0)
        val next = IntervalEngine.advance(s, 10.0, 1.0, 500.0)
        assertEquals(IntervalPhase.NONE, next.phase)
    }

    @Test fun noneStaysNone() {
        val s = IntervalSnapshot(phase = IntervalPhase.NONE)
        assertEquals(IntervalPhase.NONE, IntervalEngine.advance(s, 10.0, 1.0, 500.0).phase)
    }

    @Test fun missingSpeedTimeIsNotCountedAsValid() {
        val s = IntervalSnapshot(
            phase = IntervalPhase.INSIDE,
            traveledM = 100.0,
            elapsedS = 10.0,
            avgKmh = 36.0,
            lengthM = 1000.0,
            validSpeedS = 6.0,
        )
        val next = IntervalEngine.advance(s, speedMps = 10.0, dtS = 1.0, lengthM = 1000.0, hasSpeed = false)

        assertEquals(11.0, next.elapsedS, 0.001)
        assertEquals(6.0, next.validSpeedS, 0.001)
        assertEquals(100.0, next.traveledM, 0.001)
    }

    @Test fun averageIsHiddenBelowCoverageThreshold() {
        val low = IntervalSnapshot(
            phase = IntervalPhase.INSIDE,
            elapsedS = 10.0,
            lengthM = 1000.0,
            validSpeedS = 6.0,
        )
        val passing = low.copy(validSpeedS = 7.0)
        assertFalse(IntervalEngine.hasReliableAverage(low))
        assertTrue(IntervalEngine.hasReliableAverage(passing))
    }
}