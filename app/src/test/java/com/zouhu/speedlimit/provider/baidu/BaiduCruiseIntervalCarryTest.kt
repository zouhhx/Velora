package com.zouhu.speedlimit.provider.baidu

import com.zouhu.speedlimit.domain.model.CameraInfo
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.domain.model.IntervalPhase
import com.zouhu.speedlimit.domain.model.IntervalSnapshot
import com.zouhu.speedlimit.domain.model.RoadData
import org.junit.Assert.assertEquals
import org.junit.Test

class BaiduCruiseIntervalCarryTest {
    private fun camera(rawType: Int, link: String, offset: Double, distance: Double) = CameraInfo(
        type = CameraType.INTERVAL,
        rawType = rawType,
        linkId = link,
        offsetM = offset,
        distanceM = distance,
    )

    private fun road(interval: IntervalSnapshot, cameras: List<CameraInfo>) = RoadData(
        cameras = cameras,
        interval = interval,
        isValid = true,
    )

    private val inside = IntervalSnapshot(
        phase = IntervalPhase.INSIDE,
        traveledM = 120.0,
        elapsedS = 10.0,
        avgKmh = 43.2,
        lengthM = 1_000.0,
    )

    @Test
    fun `inside interval survives a refresh while its endpoint is still present`() {
        val previous = road(
            inside,
            listOf(
                camera(5, "link-1", 100.0, 0.0),
                camera(9, "link-1", 1_100.0, 900.0),
            ),
        )
        val next = road(
            IntervalSnapshot(),
            listOf(camera(9, "link-1", 1_100.0, 800.0)),
        )

        val carried = carryIntervalAcrossRefresh(previous, next)

        assertEquals(IntervalPhase.INSIDE, carried.phase)
        assertEquals(120.0, carried.traveledM, 0.01)
        assertEquals(10.0, carried.elapsedS, 0.01)
        assertEquals(43.2, carried.avgKmh, 0.01)
        assertEquals(1_000.0, carried.lengthM, 0.01)
    }

    @Test
    fun `inside interval resets when no endpoint belongs to the old section`() {
        val previous = road(
            inside,
            listOf(
                camera(5, "link-1", 100.0, 0.0),
                camera(9, "link-1", 1_100.0, 900.0),
            ),
        )
        val next = road(
            IntervalSnapshot(),
            listOf(camera(9, "link-2", 1_100.0, 900.0)),
        )

        val carried = carryIntervalAcrossRefresh(previous, next)

        assertEquals(IntervalPhase.NONE, carried.phase)
    }

    @Test
    fun `approach survives only when the start camera is still present`() {
        val approach = IntervalSnapshot(
            phase = IntervalPhase.APPROACH,
            distanceToStartM = 300.0,
            lengthM = 1_000.0,
        )
        val previous = road(
            approach,
            listOf(
                camera(5, "link-1", 100.0, 300.0),
                camera(9, "link-1", 1_100.0, 1_300.0),
            ),
        )

        val sameStart = road(
            IntervalSnapshot(),
            listOf(camera(5, "link-1", 100.0, 250.0)),
        )
        val onlyEnd = road(
            IntervalSnapshot(),
            listOf(camera(9, "link-1", 1_100.0, 1_250.0)),
        )

        assertEquals(IntervalPhase.APPROACH, carryIntervalAcrossRefresh(previous, sameStart).phase)
        assertEquals(IntervalPhase.NONE, carryIntervalAcrossRefresh(previous, onlyEnd).phase)
    }

    @Test
    fun `no previous interval stays idle`() {
        val previous = road(IntervalSnapshot(), emptyList())
        val next = road(IntervalSnapshot(), listOf(camera(5, "link-1", 100.0, 100.0)))

        assertEquals(IntervalPhase.NONE, carryIntervalAcrossRefresh(previous, next).phase)
    }
}
