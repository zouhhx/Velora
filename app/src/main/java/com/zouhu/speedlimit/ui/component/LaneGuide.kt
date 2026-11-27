package com.zouhu.speedlimit.ui.component

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.domain.model.Lane
import com.zouhu.speedlimit.domain.model.LaneDirection
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

private val SlmEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun LaneGuide(
    lanes: List<Lane>,
    visible: Boolean,
    modifier: Modifier = Modifier,
    cellHeight: Dp = 54.dp,
    cellMinWidth: Dp = 46.dp,
) {
    AnimatedVisibility(
        visible = visible && lanes.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(tween(400)) +
            slideInVertically(tween(500, easing = SlmEase)) { it } +
            expandVertically(tween(500, easing = SlmEase)),
        exit = fadeOut(tween(300)) +
            slideOutVertically(tween(450, easing = SlmEase)) { it } +
            shrinkVertically(tween(450, easing = SlmEase)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            lanes.forEach { lane ->
                LaneCell(lane, cellHeight, cellMinWidth)
            }
        }
    }
}

@Composable
private fun LaneCell(lane: Lane, cellHeight: Dp, cellMinWidth: Dp) {
    val colors = LocalSlmColors.current
    Box(
        modifier = Modifier
            .widthIn(min = cellMinWidth)
            .height(cellHeight)
            .clip(RoundedCornerShape(cellHeight * 0.24f))
            .background(colors.surface)
            .border(1.dp, colors.text.copy(alpha = 0.08f), RoundedCornerShape(cellHeight * 0.24f))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            lane.directions.forEach { dir ->
                DirectionGlyph(dir, colors.text, Modifier.size(cellHeight * 0.41f, cellHeight * 0.59f))
            }
        }
    }
}

@Composable
private fun DirectionGlyph(dir: LaneDirection, color: Color, modifier: Modifier = Modifier) {
    when (dir) {
        LaneDirection.BUS -> GlyphText("公", color, modifier)
        LaneDirection.VARIABLE -> GlyphText("变", color, modifier)
        LaneDirection.HOV -> GlyphText("H", color, modifier)
        LaneDirection.TIDAL -> GlyphText("潮", color, modifier)
        LaneDirection.EMPTY -> Box(modifier)
        else -> Canvas(modifier) {
            val sx = size.width / 24f
            val sy = size.height / 32f
            val sw = 2.3f * sx
            val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun Path.m(x: Number, y: Number) { moveTo(x.toFloat() * sx, y.toFloat() * sy) }
            fun Path.l(x: Number, y: Number) { lineTo(x.toFloat() * sx, y.toFloat() * sy) }
            fun Path.q(cx: Number, cy: Number, x: Number, y: Number) {
                quadraticBezierTo(cx.toFloat() * sx, cy.toFloat() * sy, x.toFloat() * sx, y.toFloat() * sy)
            }
            val path = Path()
            val head = Path()
            when (dir) {
                LaneDirection.STRAIGHT -> {
                    path.m(12, 29); path.l(12, 11)
                    head.m(12, 3); head.l(18, 12); head.l(6, 12); head.close()
                }
                LaneDirection.LEFT -> {
                    path.m(18, 29); path.l(18, 16); path.q(18, 11, 13, 11); path.l(8, 11)
                    head.m(3, 11); head.l(12, 5.5f); head.l(12, 16.5f); head.close()
                }
                LaneDirection.RIGHT -> {
                    path.m(6, 29); path.l(6, 16); path.q(6, 11, 11, 11); path.l(16, 11)
                    head.m(21, 11); head.l(12, 5.5f); head.l(12, 16.5f); head.close()
                }
                LaneDirection.UTURN -> {
                    path.m(19, 29); path.l(19, 13); path.q(19, 6, 12, 6); path.q(5, 6, 5, 13); path.l(5, 17)
                    head.m(5, 25); head.l(0.5f, 17); head.l(9.5f, 17); head.close()
                }
                LaneDirection.SLIGHT_LEFT -> {
                    path.m(16, 29); path.l(16, 17); path.q(16, 12, 12, 9); path.l(8, 6)
                    head.m(4, 2); head.l(13, 3); head.l(8, 10); head.close()
                }
                LaneDirection.SLIGHT_RIGHT -> {
                    path.m(8, 29); path.l(8, 17); path.q(8, 12, 12, 9); path.l(16, 6)
                    head.m(20, 2); head.l(11, 3); head.l(16, 10); head.close()
                }
                else -> {}
            }
            drawPath(path, color, style = stroke)
            drawPath(head, color)
        }
    }
}

@Composable
private fun GlyphText(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}