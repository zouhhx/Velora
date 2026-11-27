package com.zouhu.speedlimit.domain.engine

import com.zouhu.speedlimit.domain.model.CameraInfo
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalTrackerTest {
    private fun start(distance: Double, offset: Double = 100.0) = CameraInfo(
        type = CameraType.INTERVAL,
        rawType = 5,
        linkId = "link-1",
        offsetM = offset,
        distanceM = distance,
    )

    private fun end(distance: Double, offset: Double = 1100.0) = CameraInfo(
        type = CameraType.INTERVAL,
        rawType = 9,
        linkId = "link-1",
        offsetM = offset,
        distanceM = distance,
    )

    @Test
    fun `start ahead builds approach with offset length`() {
        val next = IntervalTracker.update(
            IntervalSnapshot(),
            listOf(start(500.0), end(1500.0)),
            speedMps = 10.0,
            dtS = 0.0,
        )
        assertEquals(IntervalPhase.APPROACH, next.phase)
        assertEquals(500.0, next.distanceToStartM, 0.01)
        assertEquals(1000.0, next.lengthM, 0.01)
    }

    @Test
    fun `approach advances by speed and dt`() {
        val next = IntervalTracker.update(
            IntervalSnapshot(IntervalPhase.APPROACH, 500.0, lengthM = 1000.0),
            listOf(start(400.0), end(1400.0)),
            speedMps = 10.0,
            dtS = 5.0,
        )
        assertEquals(IntervalPhase.APPROACH, next.phase)
        assertEquals(450.0, next.distanceToStartM, 0.01)
    }

    @Test
    fun `crossing the start enters inside`() {
        val next = IntervalTracker.update(
            IntervalSnapshot(IntervalPhase.APPROACH, 50.0, lengthM = 1000.0),
            listOf(start(40.0), end(1040.0)),
            speedMps = 10.0,
            dtS = 5.0,
        )
        assertEquals(IntervalPhase.INSIDE, next.phase)
        assertEquals(1000.0, next.lengthM, 0.01)
    }

    @Test
    fun `inside accumulates average speed`() {
        val next = IntervalTracker.update(
            IntervalSnapshot(
                phase = IntervalPhase.INSIDE,
                traveledM = 100.0,
                elapsedS = 10.0,
                avgKmh = 36.0,
                lengthM = 1000.0,
            ),
            listOf(end(900.0)),
            speedMps = 10.0,
            dtS = 5.0,
        )
        assertEquals(IntervalPhase.INSIDE, next.phase)
        assertEquals(150.0, next.traveledM, 0.01)
        assertEquals(15.0, next.elapsedS, 0.01)
        assertEquals(36.0, next.avgKmh, 0.01)
    }

    @Test
    fun `reaching the end closes the interval`() {
        val next = IntervalTracker.update(
            IntervalSnapshot(
                phase = IntervalPhase.INSIDE,
                traveledM = 990.0,
                elapsedS = 100.0,
                avgKmh = 35.0,
                lengthM = 1000.0,
            ),
            listOf(end(5.0)),
            speedMps = 10.0,
            dtS = 1.0,
        )
        assertEquals(IntervalPhase.NONE, next.phase)
    }

    @Test
    fun `cross link interval length uses distance difference`() {
        val next = IntervalTracker.update(
            IntervalSnapshot(),
            listOf(
                start(500.0, offset = 100.0).copy(linkId = "link-1"),
                end(1500.0, offset = 1100.0).copy(linkId = "link-2"),
            ),
            speedMps = 10.0,
            dtS = 0.0,
        )
        assertEquals(IntervalPhase.APPROACH, next.phase)
        assertEquals(1000.0, next.lengthM, 0.01)
    }

    @Test
    fun `nearest start is paired with the following end not a passed end`() {
        val next = IntervalTracker.update(
            IntervalSnapshot(),
            listOf(
                end(50.0, offset = 0.0).copy(linkId = "passed-end"),
                start(500.0, offset = 100.0).copy(linkId = "section-start"),
                end(1500.0, offset = 1100.0).copy(linkId = "section-end"),
                start(2000.0, offset = 1900.0).copy(linkId = "later-start"),
                end(3000.0, offset = 2900.0).copy(linkId = "later-end"),
            ),
            speedMps = 10.0,
            dtS = 0.0,
        )
        assertEquals(IntervalPhase.APPROACH, next.phase)
        assertEquals(500.0, next.distanceToStartM, 0.01)
        assertEquals(1000.0, next.lengthM, 0.01)
    }

    @Test
    fun `no interval cameras keeps idle state`() {
        val current = IntervalSnapshot()
        assertEquals(current, IntervalTracker.update(current, emptyList(), 10.0, 1.0))
    }
}
