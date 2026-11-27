package com.zouhu.speedlimit.data.nav

import com.zouhu.speedlimit.domain.model.LaneDirection
import com.zouhu.speedlimit.domain.model.NavLane

/**
 * G0 finding: Baidu navi exposes lanes only as drawable resource names
 * (nsdk_ic_lane_*), never structured directions/recommended. Derive structure from
 * the name tokens.
 *
 * Recommendation: the shipped 9.0.2 resources carry three variants of each shape —
 * plain, `_bright` and `_gray`. Only `_bright` means "recommended"; plain is a legal but
 * un-highlighted lane and must stay neutral, otherwise a payload without `_bright`
 * lights up every lane that is not `_gray` (the old rule) and the row reads as several
 * competing recommendations.
 *
 * We deliberately do NOT derive the recommendation from the current manoeuvre: the lane
 * set can belong to a nearer junction than the one on the turn card, and guessing there
 * would highlight the wrong lane — a driving-safety problem, not a cosmetic one.
 */
object LaneDrawableMapper {
    private const val BRIGHT = "_bright"
    private const val GRAY = "_gray"

    /** `resName` is a platform type from the SDK and can be null; drop it, never throw. */
    fun fromResourceNames(names: List<String?>): List<NavLane> =
        names.filterNotNull().mapNotNull { parse(it) }

    fun parse(rawName: String): NavLane? {
        val name = rawName.substringAfterLast('/')
        if (!name.startsWith("nsdk_ic_lane")) return null
        val recommended = name.endsWith(BRIGHT)
        val body = name
            .removePrefix("nsdk_ic_lane_")
            .removeSuffix(BRIGHT)
            .removeSuffix(GRAY)
        val dirs = ArrayList<LaneDirection>(2)
        if (body.contains("uturn")) dirs.add(LaneDirection.UTURN)
        if (body.contains("slight_left") || body.contains("left_front")) dirs.add(LaneDirection.SLIGHT_LEFT)
        if (body.contains("slight_right") || body.contains("right_front")) dirs.add(LaneDirection.SLIGHT_RIGHT)
        if (body.contains("left") && !body.contains("slight_left") && !body.contains("left_front")) dirs.add(LaneDirection.LEFT)
        if (body.contains("right") && !body.contains("slight_right") && !body.contains("right_front")) dirs.add(LaneDirection.RIGHT)
        if (body.contains("straight") || body.contains("2cross") || dirs.isEmpty()) dirs.add(LaneDirection.STRAIGHT)
        // Fixed display order: through movement first, then the branch. The old insertion
        // order produced "→↑" for a straight-and-right lane, which reads as two unrelated
        // directions instead of one lane.
        val ordered = dirs.distinct().sortedBy { orderOf(it) }
        return NavLane(ordered, recommended)
    }

    private fun orderOf(d: LaneDirection): Int = when (d) {
        LaneDirection.UTURN -> 0
        LaneDirection.LEFT -> 1
        LaneDirection.SLIGHT_LEFT -> 2
        LaneDirection.STRAIGHT -> 3
        LaneDirection.SLIGHT_RIGHT -> 4
        LaneDirection.RIGHT -> 5
        else -> 6
    }
}
