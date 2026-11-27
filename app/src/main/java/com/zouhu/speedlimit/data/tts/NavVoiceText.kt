package com.zouhu.speedlimit.data.tts

import com.zouhu.speedlimit.domain.model.NavManeuver
import com.zouhu.speedlimit.domain.model.NavUiState

/**
 * One spoken navigation cue.
 *
 * [key] is the dedupe identity: same key means "already announced, do not repeat".
 * It deliberately contains the distance *node*, not the raw distance, so a turn does
 * not re-announce on every metre change (Q-27 option B).
 */
data class NavVoiceCue(val key: String, val text: String)

/** Fixed announcement nodes, matching the product decision. */
private val DISTANCE_NODES = intArrayOf(1000, 500, 300, 100, 50)

/**
 * Navigation instruction, independent of Compose.
 *
 * Moving this out of `CruiseScreen` is what lets the voice path keep running when the
 * Activity is stopped or the screen is locked (VA-F03).
 */
fun navVoiceCue(nav: NavUiState): NavVoiceCue? {
    if (nav.arrived) return NavVoiceCue("arrive", "已到达目的地")
    if (nav.reroute) return NavVoiceCue("reroute", "偏航，正在重新规划路线")
    val turn = nav.turn ?: return null
    // Above the farthest node there is nothing new to say yet.
    if (turn.distanceM > DISTANCE_NODES.first()) return null
    val node = DISTANCE_NODES.firstOrNull { turn.distanceM >= it } ?: DISTANCE_NODES.last()
    return NavVoiceCue(
        key = "turn|${turn.maneuver}|${turn.roadName}|$node",
        text = "前方${node}米${maneuverText(turn.maneuver)}进入${turn.roadName}",
    )
}

fun maneuverText(m: NavManeuver): String = when (m) {
    NavManeuver.LEFT -> "左转"
    NavManeuver.RIGHT -> "右转"
    NavManeuver.UTURN -> "掉头"
    NavManeuver.SLIGHT_LEFT -> "稍向左转"
    NavManeuver.SLIGHT_RIGHT -> "稍向右转"
    NavManeuver.ROUNDABOUT -> "进入环岛"
    NavManeuver.ARRIVE -> "到达"
    NavManeuver.STRAIGHT -> "直行"
}
