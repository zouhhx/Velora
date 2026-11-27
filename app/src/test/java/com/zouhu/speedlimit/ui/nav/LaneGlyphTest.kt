package com.zouhu.speedlimit.ui.nav

import com.zouhu.speedlimit.domain.model.LaneDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lane shape mapping is a safety surface: drawing "left+right" as "straight+right" points
 * the driver the wrong way, and silently collapsing an unknown mix to a straight arrow
 * removes information.
 */
class LaneGlyphTest {
    private val L = LaneDirection.LEFT
    private val R = LaneDirection.RIGHT
    private val S = LaneDirection.STRAIGHT
    private val U = LaneDirection.UTURN
    private val SL = LaneDirection.SLIGHT_LEFT
    private val SR = LaneDirection.SLIGHT_RIGHT

    @Test
    fun singleDirections() {
        assertEquals(LaneGlyph.LEFT, laneGlyphFor(listOf(L)))
        assertEquals(LaneGlyph.RIGHT, laneGlyphFor(listOf(R)))
        assertEquals(LaneGlyph.STRAIGHT, laneGlyphFor(listOf(S)))
        assertEquals(LaneGlyph.UTURN, laneGlyphFor(listOf(U)))
        assertEquals(LaneGlyph.SLIGHT_LEFT, laneGlyphFor(listOf(SL)))
        assertEquals(LaneGlyph.SLIGHT_RIGHT, laneGlyphFor(listOf(SR)))
        assertEquals(LaneGlyph.STRAIGHT, laneGlyphFor(emptyList()))
    }

    @Test
    fun twoWayCombinations() {
        assertEquals(LaneGlyph.FORK_RIGHT, laneGlyphFor(listOf(S, R)))
        assertEquals(LaneGlyph.FORK_LEFT, laneGlyphFor(listOf(S, L)))
        // The regression: left+right used to be drawn as straight+right.
        assertEquals(LaneGlyph.DOUBLE_BRANCH, laneGlyphFor(listOf(L, R)))
        // Slight variants join the same semantics.
        assertEquals(LaneGlyph.FORK_RIGHT, laneGlyphFor(listOf(S, SR)))
        assertEquals(LaneGlyph.DOUBLE_BRANCH, laneGlyphFor(listOf(SL, SR)))
    }

    @Test
    fun threeWayCombination() {
        assertEquals(LaneGlyph.FORK_BOTH, laneGlyphFor(listOf(S, L, R)))
        assertEquals(LaneGlyph.FORK_BOTH, laneGlyphFor(listOf(S, SL, SR)))
    }

    @Test
    fun uTurnCombinationsKeepBothDirections() {
        assertEquals(LaneGlyph.UTURN_COMBO, laneGlyphFor(listOf(U, L)))
        assertEquals(LaneGlyph.UTURN_COMBO, laneGlyphFor(listOf(U, S)))
        assertEquals(LaneGlyph.UTURN_COMBO, laneGlyphFor(listOf(U, S, R)))
    }

    @Test
    fun orderDoesNotMatter() {
        assertEquals(laneGlyphFor(listOf(S, R)), laneGlyphFor(listOf(R, S)))
        assertEquals(laneGlyphFor(listOf(L, R)), laneGlyphFor(listOf(R, L)))
    }
}
