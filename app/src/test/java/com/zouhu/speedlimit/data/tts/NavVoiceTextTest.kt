package com.zouhu.speedlimit.data.tts

import com.zouhu.speedlimit.domain.model.NavManeuver
import com.zouhu.speedlimit.domain.model.NavTurn
import com.zouhu.speedlimit.domain.model.NavUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** VA-F03 / Q-27: instruction cues are produced outside Compose and deduped by node. */
class NavVoiceTextTest {

    @Test
    fun `arrival wins over a stale turn`() {
        val nav = NavUiState().copy(
            arrived = true,
            turn = NavTurn(120, "仙林大道", NavManeuver.RIGHT),
        )
        assertEquals("已到达目的地", navVoiceCue(nav)?.text)
    }

    @Test
    fun `reroute is spoken without distances`() {
        assertEquals("偏航，正在重新规划路线", navVoiceCue(NavUiState().copy(reroute = true))?.text)
    }

    @Test
    fun `turn uses the fixed distance node`() {
        val nav = NavUiState().copy(turn = NavTurn(480, "仙林大道", NavManeuver.RIGHT))
        val cue = navVoiceCue(nav)
        assertEquals("前方300米右转进入仙林大道", cue?.text)
        assertEquals("turn|RIGHT|仙林大道|300", cue?.key)
    }

    @Test
    fun `distance changes inside one node do not produce a new cue`() {
        val a = NavUiState().copy(turn = NavTurn(480, "仙林大道", NavManeuver.RIGHT))
        val b = NavUiState().copy(turn = NavTurn(430, "仙林大道", NavManeuver.RIGHT))
        assertEquals(navVoiceCue(a), navVoiceCue(b))
    }

    @Test
    fun `far turn is silent until the first node`() {
        assertNull(navVoiceCue(NavUiState().copy(turn = NavTurn(5_000, "仙林大道", NavManeuver.RIGHT))))
    }

    @Test
    fun `idle state has nothing to say`() {
        assertNull(navVoiceCue(NavUiState()))
    }

    @Test
    fun `maneuver words match the existing UI wording`() {
        assertEquals("左转", maneuverText(NavManeuver.LEFT))
        assertEquals("稍向右转", maneuverText(NavManeuver.SLIGHT_RIGHT))
        assertEquals("进入环岛", maneuverText(NavManeuver.ROUNDABOUT))
        assertEquals("到达", maneuverText(NavManeuver.ARRIVE))
        assertEquals("直行", maneuverText(NavManeuver.STRAIGHT))
    }
}
