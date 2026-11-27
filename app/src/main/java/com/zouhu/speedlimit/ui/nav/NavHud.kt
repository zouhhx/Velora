package com.zouhu.speedlimit.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.domain.model.LaneDirection
import com.zouhu.speedlimit.domain.model.NavEta
import com.zouhu.speedlimit.domain.model.NavLane
import com.zouhu.speedlimit.domain.model.NavManeuver
import com.zouhu.speedlimit.domain.model.NavTurn
import com.zouhu.speedlimit.domain.model.NavUiState
import com.zouhu.speedlimit.domain.engine.TrafficLightResolver
import com.zouhu.speedlimit.domain.model.NavTrafficLight
import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.domain.model.TrafficLightPhase
import com.zouhu.speedlimit.ui.component.CameraCapsule
import com.zouhu.speedlimit.ui.component.MiniLimitSign
import com.zouhu.speedlimit.ui.component.TrafficLightCapsule
import com.zouhu.speedlimit.ui.theme.LocalSlmColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SlmEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * All arrow geometry lives here, normalised to 0..1 and stroked with a heavy round cap.
 *
 * Everything is drawn as a vector rather than a text glyph: a "←" has a hairline stroke
 * that stays thin at any font size, and text glyphs cannot express "straight or right"
 * as one shape. The same stroke weight is reused by the turn card and the lane row so
 * the two no longer look like they came from different apps.
 */
private fun maneuverPath(p: Path, m: NavManeuver, w: Float, h: Float) {
    fun move(x: Float, y: Float) = p.moveTo(x * w, y * h)
    fun line(x: Float, y: Float) = p.lineTo(x * w, y * h)
    when (m) {
        NavManeuver.ARRIVE -> {
            move(0.40f, 0.90f); line(0.40f, 0.18f)
            move(0.40f, 0.20f); line(0.74f, 0.32f); line(0.40f, 0.46f)
        }
        NavManeuver.LEFT -> {
            move(0.64f, 0.92f); line(0.64f, 0.52f); line(0.32f, 0.52f)
            move(0.48f, 0.32f); line(0.24f, 0.52f); line(0.48f, 0.72f)
        }
        NavManeuver.RIGHT -> {
            move(0.36f, 0.92f); line(0.36f, 0.52f); line(0.68f, 0.52f)
            move(0.52f, 0.32f); line(0.76f, 0.52f); line(0.52f, 0.72f)
        }
        NavManeuver.SLIGHT_LEFT -> {
            move(0.64f, 0.92f); line(0.44f, 0.30f)
            move(0.26f, 0.44f); line(0.42f, 0.22f); line(0.62f, 0.32f)
        }
        NavManeuver.SLIGHT_RIGHT -> {
            move(0.36f, 0.92f); line(0.56f, 0.30f)
            move(0.74f, 0.44f); line(0.58f, 0.22f); line(0.38f, 0.32f)
        }
        NavManeuver.UTURN -> {
            move(0.68f, 0.92f); line(0.68f, 0.50f)
            p.cubicTo(0.68f * w, 0.24f * h, 0.34f * w, 0.24f * h, 0.34f * w, 0.50f * h)
            line(0.34f, 0.64f)
            move(0.18f, 0.50f); line(0.34f, 0.72f); line(0.50f, 0.50f)
        }
        NavManeuver.ROUNDABOUT -> {
            p.addArc(
                androidx.compose.ui.geometry.Rect(0.22f * w, 0.20f * h, 0.78f * w, 0.80f * h),
                120f, 285f,
            )
            move(0.36f, 0.12f); line(0.60f, 0.22f); line(0.40f, 0.36f)
        }
        else -> {
            move(0.50f, 0.92f); line(0.50f, 0.26f)
            move(0.30f, 0.46f); line(0.50f, 0.22f); line(0.70f, 0.46f)
        }
    }
}

/**
 * "Straight *or* right" as one shape: a through shaft plus a branch leaving it, which is
 * how Baidu's own lane icons read. Two side-by-side glyphs ("→↑") never conveyed this.
 *
 * The branch leaves high on the shaft (0.64) and runs at 45°, so the two strokes separate
 * as fast as possible — the previous version left at 0.74 and hugged the shaft, which at
 * 26dp with a 0.16 stroke made one blob.
 */
private fun forkPath(p: Path, w: Float, h: Float, toRight: Boolean) {
    val s = if (toRight) 1f else -1f
    val trunk = if (toRight) 0.34f else 0.66f
    fun move(x: Float, y: Float) = p.moveTo(x * w, y * h)
    fun line(x: Float, y: Float) = p.lineTo(x * w, y * h)
    // Through movement.
    move(trunk, 0.95f); line(trunk, 0.26f)
    move(trunk - 0.15f, 0.44f); line(trunk, 0.18f); line(trunk + 0.15f, 0.44f)
    // Branch leaving the shaft, pointing up-and-out at 45°.
    val startY = 0.64f
    val endX = trunk + s * 0.40f
    val endY = 0.24f
    move(trunk, startY); line(endX, endY)
    val dirX = (endX - trunk)
    val dirY = (endY - startY)
    val len = kotlin.math.hypot(dirX, dirY).coerceAtLeast(1e-3f)
    val ux = dirX / len
    val uy = dirY / len
    val px = -uy
    val py = ux
    val headLen = 0.17f
    val headHalf = 0.10f
    move(endX - ux * headLen + px * headHalf, endY - uy * headLen + py * headHalf)
    line(endX, endY)
    line(endX - ux * headLen - px * headHalf, endY - uy * headLen - py * headHalf)
}

/** Straight + left + right: one shaft, two branches. */
private fun forkBothPath(p: Path, w: Float, h: Float) {
    fun move(x: Float, y: Float) = p.moveTo(x * w, y * h)
    fun line(x: Float, y: Float) = p.lineTo(x * w, y * h)
    move(0.5f, 0.95f); line(0.5f, 0.22f)
    move(0.35f, 0.40f); line(0.5f, 0.16f); line(0.65f, 0.40f)

    fun branch(targetX: Float) {
        val startY = 0.66f
        val endY = 0.34f
        move(0.5f, startY); line(targetX, endY)
        val dx = targetX - 0.5f
        val dy = endY - startY
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
        val ux = dx / len
        val uy = dy / len
        val px = -uy
        val py = ux
        val headLen = 0.16f
        val headHalf = 0.09f
        move(targetX - ux * headLen + px * headHalf, endY - uy * headLen + py * headHalf)
        line(targetX, endY)
        line(targetX - ux * headLen - px * headHalf, endY - uy * headLen - py * headHalf)
    }
    branch(0.12f)
    branch(0.88f)
}

/**
 * Left *or* right with no through movement: a V from the lane centre. The old mapping
 * drew this case as straight+right, which pointed the driver the wrong way.
 */
private fun doubleBranchPath(p: Path, w: Float, h: Float) {
    fun move(x: Float, y: Float) = p.moveTo(x * w, y * h)
    fun line(x: Float, y: Float) = p.lineTo(x * w, y * h)
    fun branch(targetX: Float) {
        val startY = 0.95f
        val endY = 0.30f
        move(0.5f, startY); line(targetX, endY)
        val dx = targetX - 0.5f
        val dy = endY - startY
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
        val ux = dx / len
        val uy = dy / len
        val px = -uy
        val py = ux
        val headLen = 0.17f
        val headHalf = 0.10f
        move(targetX - ux * headLen + px * headHalf, endY - uy * headLen + py * headHalf)
        line(targetX, endY)
        line(targetX - ux * headLen - px * headHalf, endY - uy * headLen - py * headHalf)
    }
    branch(0.18f)
    branch(0.82f)
}

/**
 * Which shape a lane box draws. Pure so the mapping can be unit-tested — getting this
 * wrong points the driver the wrong way (left+right used to render as straight+right,
 * and any unlisted mix fell back to a plain straight arrow).
 */
internal enum class LaneGlyph {
    STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, UTURN,
    FORK_LEFT, FORK_RIGHT, FORK_BOTH, DOUBLE_BRANCH, UTURN_COMBO,
}

internal fun laneGlyphFor(dirs: List<LaneDirection>): LaneGlyph {
    val set = dirs.toSet()
    if (set.isEmpty()) return LaneGlyph.STRAIGHT
    if (set.size == 1) return when (dirs.first()) {
        LaneDirection.LEFT -> LaneGlyph.LEFT
        LaneDirection.RIGHT -> LaneGlyph.RIGHT
        LaneDirection.SLIGHT_LEFT -> LaneGlyph.SLIGHT_LEFT
        LaneDirection.SLIGHT_RIGHT -> LaneGlyph.SLIGHT_RIGHT
        LaneDirection.UTURN -> LaneGlyph.UTURN
        else -> LaneGlyph.STRAIGHT
    }
    val straight = LaneDirection.STRAIGHT in set
    val left = set.any { it == LaneDirection.LEFT || it == LaneDirection.SLIGHT_LEFT }
    val right = set.any { it == LaneDirection.RIGHT || it == LaneDirection.SLIGHT_RIGHT }
    return when {
        LaneDirection.UTURN in set -> LaneGlyph.UTURN_COMBO
        straight && left && right -> LaneGlyph.FORK_BOTH
        straight && right -> LaneGlyph.FORK_RIGHT
        straight && left -> LaneGlyph.FORK_LEFT
        left && right -> LaneGlyph.DOUBLE_BRANCH
        // Unknown mix (slight-left + slight-right): keep the primary movement.
        else -> dirs.first().toManeuverGlyph()
    }
}

private fun LaneDirection.toManeuverGlyph(): LaneGlyph = when (this) {
    LaneDirection.LEFT -> LaneGlyph.LEFT
    LaneDirection.RIGHT -> LaneGlyph.RIGHT
    LaneDirection.SLIGHT_LEFT -> LaneGlyph.SLIGHT_LEFT
    LaneDirection.SLIGHT_RIGHT -> LaneGlyph.SLIGHT_RIGHT
    LaneDirection.UTURN -> LaneGlyph.UTURN
    else -> LaneGlyph.STRAIGHT
}

@Composable
private fun ManeuverIcon(m: NavManeuver, color: Color, size: Dp = 56.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path()
        maneuverPath(p, m, w, h)
        drawPath(
            p,
            color,
            style = Stroke(width = w * 0.15f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun LaneIcon(dirs: List<LaneDirection>, color: Color, size: Dp = 32.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val set = dirs.toSet()
        val straight = LaneDirection.STRAIGHT in set
        val left = set.any { it == LaneDirection.LEFT || it == LaneDirection.SLIGHT_LEFT }
        val right = set.any { it == LaneDirection.RIGHT || it == LaneDirection.SLIGHT_RIGHT }
        val uturn = LaneDirection.UTURN in set

        // Thinner relative stroke than the turn card: at this size a 0.16 stroke turned
        // combined arrows into a blob. 0.11 keeps the same visual weight after scaling.
        fun emit(p: Path, aw: Float) {
            drawPath(
                p,
                color,
                style = Stroke(width = aw * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        when (laneGlyphFor(dirs)) {
            LaneGlyph.STRAIGHT -> {
                val p = Path(); maneuverPath(p, NavManeuver.STRAIGHT, w, h); emit(p, w)
            }
            LaneGlyph.LEFT -> {
                val p = Path(); maneuverPath(p, NavManeuver.LEFT, w, h); emit(p, w)
            }
            LaneGlyph.RIGHT -> {
                val p = Path(); maneuverPath(p, NavManeuver.RIGHT, w, h); emit(p, w)
            }
            LaneGlyph.SLIGHT_LEFT -> {
                val p = Path(); maneuverPath(p, NavManeuver.SLIGHT_LEFT, w, h); emit(p, w)
            }
            LaneGlyph.SLIGHT_RIGHT -> {
                val p = Path(); maneuverPath(p, NavManeuver.SLIGHT_RIGHT, w, h); emit(p, w)
            }
            LaneGlyph.UTURN -> {
                val p = Path(); maneuverPath(p, NavManeuver.UTURN, w, h); emit(p, w)
            }
            LaneGlyph.FORK_LEFT -> {
                val p = Path(); forkPath(p, w, h, toRight = false); emit(p, w)
            }
            LaneGlyph.FORK_RIGHT -> {
                val p = Path(); forkPath(p, w, h, toRight = true); emit(p, w)
            }
            LaneGlyph.FORK_BOTH -> {
                val p = Path(); forkBothPath(p, w, h); emit(p, w)
            }
            LaneGlyph.DOUBLE_BRANCH -> {
                val p = Path(); doubleBranchPath(p, w, h); emit(p, w)
            }
            LaneGlyph.UTURN_COMBO -> {
                // U-turn plus the other movement, side by side at reduced size — never
                // silently drop a direction.
                val second = set.firstOrNull { it != LaneDirection.UTURN }
                val aw = w * 0.54f
                val p1 = Path(); maneuverPath(p1, NavManeuver.UTURN, aw, h); emit(p1, aw)
                if (second != null) {
                    val p2 = Path(); maneuverPath(p2, second.toManeuver(), aw, h)
                    translate(left = w - aw) { emit(p2, aw) }
                }
            }
        }
    }
}

private fun LaneDirection.toManeuver(): NavManeuver = when (this) {
    LaneDirection.LEFT -> NavManeuver.LEFT
    LaneDirection.RIGHT -> NavManeuver.RIGHT
    LaneDirection.SLIGHT_LEFT -> NavManeuver.SLIGHT_LEFT
    LaneDirection.SLIGHT_RIGHT -> NavManeuver.SLIGHT_RIGHT
    LaneDirection.UTURN -> NavManeuver.UTURN
    else -> NavManeuver.STRAIGHT
}

@Composable
private fun NavAnimated(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(400)) +
            slideInVertically(tween(550, easing = SlmEase)) { it } +
            expandVertically(tween(550, easing = SlmEase)),
        exit = fadeOut(tween(300)) +
            slideOutVertically(tween(450, easing = SlmEase)) { it } +
            shrinkVertically(tween(450, easing = SlmEase)),
    ) { content() }
}

@Composable
private fun TurnCard(turn: NavTurn) {
    val colors = LocalSlmColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ManeuverIcon(turn.maneuver, colors.mint, size = 56.dp)
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    turn.distanceM.toString(),
                    color = colors.text,
                    fontSize = 40.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(" 米", color = colors.muted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(
                turn.roadName,
                color = colors.text.copy(alpha = 0.82f),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun NavLaneGuide(lanes: List<NavLane>) {
    val colors = LocalSlmColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        lanes.forEach { lane ->
            // Only SDK-recommended lanes are tinted; everything else stays neutral so
            // the row reads as "this one" instead of "several blue boxes".
            val tint = if (lane.recommended) colors.mint else colors.text
            Box(
                Modifier
                    .size(width = 46.dp, height = 54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(
                        if (lane.recommended) 1.5.dp else 1.dp,
                        if (lane.recommended) colors.mint else colors.text.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LaneIcon(lane.directions, tint, size = 32.dp)
            }
        }
    }
}

@Composable
private fun EtaStrip(
    eta: NavEta?,
    fullView: Boolean,
    onToggleFullView: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = LocalSlmColors.current
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (eta != null) "${eta.remainingSec / 60} 分钟" else "--",
                color = colors.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (eta != null) "${"%.1f".format(eta.distanceM / 1000.0)} 公里 · ${fmt.format(Date(eta.arrivalEpoch))} 到达" else "",
                color = colors.muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (fullView) "取消总览" else "总览",
                color = if (fullView) colors.mint else colors.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(
                        1.5.dp,
                        if (fullView) colors.mint else colors.text.copy(alpha = 0.22f),
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onToggleFullView)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                "退出",
                color = colors.text.copy(alpha = 0.82f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.5.dp, colors.text.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                    .clickable(onClick = onExit)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun NavHud(
    nav: NavUiState,
    onExit: () -> Unit,
    onToggleFullView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSlmColors.current
    Box(modifier.fillMaxSize()) {
        NavAnimated(
            visible = nav.reroute || nav.arrived,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp, start = 14.dp, end = 14.dp),
        ) {
            Text(
                if (nav.arrived) "已到达目的地" else "偏航 · 正在重新规划路线…",
                color = if (nav.arrived) colors.green else colors.amber,
                fontSize = 12.5.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, (if (nav.arrived) colors.green else colors.amber).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // Turn card and lane row share a column: the road-name line can wrap, so a fixed
        // top offset for the lanes would overlap once the card grows.
        Column(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 14.dp, start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavAnimated(visible = nav.turn != null && !nav.reroute && !nav.arrived) {
                nav.turn?.let { TurnCard(it) }
            }
            NavAnimated(visible = nav.lanes.isNotEmpty()) {
                NavLaneGuide(nav.lanes)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CameraCapsule(
                visible = nav.camera != null,
                cameraType = nav.camera?.type ?: com.zouhu.speedlimit.domain.model.CameraType.SPEED,
                distText = "${nav.camera?.distanceM ?: 0}m",
                label = "测速",
                limit = nav.camera?.limit,
                compact = true,
            )
            TrafficLightCapsule(
                info = navTrafficLightInfo(nav.trafficLight),
                compact = true,
            )
        }

        Row(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 14.dp, bottom = 84.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(nav.speedKmh.toString(), color = colors.text, fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.Light)
                Text("KM/H", color = colors.muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            }
            MiniLimitSign(nav.speedLimit, size = 38.dp)
        }

        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp)) {
            EtaStrip(nav.eta, nav.fullView, onToggleFullView, onExit)
        }
    }
}

/**
 * Navi light -> shared capsule model.
 *
 * The timestamp must come from the reading, not from composition: keying the capsule on
 * a fresh `now` restarts the countdown whenever an unrelated HUD field changes (VA-L03).
 * `distanceM` is only the capsule's range gate here, not a displayed distance.
 */
internal fun navTrafficLightInfo(tl: NavTrafficLight?): TrafficLightInfo? {
    if (tl == null) return null
    val countdown = tl.countdownSec
    return TrafficLightInfo(
        distanceM = 0.0,
        timestampMs = tl.receivedAtMs,
        phases = listOf(
            TrafficLightPhase(
                tl.state,
                countdown ?: TrafficLightResolver.UNKNOWN_COUNTDOWN,
                (countdown ?: 60) + 1,
            ),
        ),
    )
}
