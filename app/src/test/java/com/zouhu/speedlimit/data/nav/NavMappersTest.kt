package com.zouhu.speedlimit.data.nav

import com.zouhu.speedlimit.domain.model.LaneDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneDrawableMapperTest {
    @Test
    fun parsesG0Sample() {
        val lanes = LaneDrawableMapper.fromResourceNames(
            listOf(
                "nsdk_ic_lane_turn_left_90_gray",
                "nsdk_ic_lane_2cross_left_straight_gray",
                "nsdk_ic_lane_turn_right_90",
            ),
        )
        assertEquals(3, lanes.size)
        assertEquals(listOf(LaneDirection.LEFT), lanes[0].directions)
        assertFalse(lanes[0].recommended)
        assertTrue(lanes[1].directions.contains(LaneDirection.LEFT))
        assertTrue(lanes[1].directions.contains(LaneDirection.STRAIGHT))
        assertFalse(lanes[1].recommended)
        assertEquals(listOf(LaneDirection.RIGHT), lanes[2].directions)
        // Plain shapes are legal-but-neutral: only `_bright` is a recommendation.
        assertFalse(lanes[2].recommended)
    }

    @Test
    fun onlyBrightIsRecommended() {
        val lanes = LaneDrawableMapper.fromResourceNames(
            listOf("nsdk_ic_lane_straight_gray", "nsdk_ic_lane_straight", "nsdk_ic_lane_straight_bright"),
        )
        assertEquals(1, lanes.count { it.recommended })
        assertTrue(lanes[2].recommended)
    }

    @Test
    fun brightVariantWinsOverPlain() {
        // Shipped 9.0.2 resources carry plain / _bright / _gray per shape. When the SDK
        // marks a lane, it uses _bright; plain lanes are "allowed but not recommended"
        // and must stay neutral, otherwise several lanes light up at once.
        val lanes = LaneDrawableMapper.fromResourceNames(
            listOf(
                "nsdk_ic_lane_turn_left_90_gray",
                "nsdk_ic_lane_straight",
                "nsdk_ic_lane_straight_and_right_bright",
            ),
        )
        assertEquals(3, lanes.size)
        assertFalse(lanes[0].recommended)
        assertFalse(lanes[1].recommended)
        assertTrue(lanes[2].recommended)
        assertEquals(1, lanes.count { it.recommended })
    }

    @Test
    fun combinedDirectionsKeepStableOrder() {
        val lane = LaneDrawableMapper.parse("nsdk_ic_lane_straight_and_right_bright")
        assertEquals(listOf(LaneDirection.STRAIGHT, LaneDirection.RIGHT), lane?.directions)
    }

    @Test
    fun ignoresNonLaneResources() {
        assertNull(LaneDrawableMapper.parse("nsdk_ic_turn_right"))
        assertNull(LaneDrawableMapper.parse("abc"))
    }

    @Test
    fun emptyWhenNoNames() {
        assertTrue(LaneDrawableMapper.fromResourceNames(emptyList()).isEmpty())
    }
}

class NavSentinelsTest {
    @Test
    fun limitSentinels() {
        assertNull(NavSentinels.limit(500))
        assertNull(NavSentinels.limit(0))
        assertNull(NavSentinels.limit(-1))
        assertEquals(60, NavSentinels.limit(60))
    }

    @Test
    fun speedSentinels() {
        assertNull(NavSentinels.speed(-1))
        assertEquals(0, NavSentinels.speed(0))
        assertEquals(62, NavSentinels.speed(62))
    }
}