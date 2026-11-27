package com.zouhu.speedlimit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.domain.engine.TrafficLightResolver
import com.zouhu.speedlimit.domain.model.TrafficLightFlow
import com.zouhu.speedlimit.domain.model.TrafficLightInfo
import com.zouhu.speedlimit.domain.model.TrafficLightState
import com.zouhu.speedlimit.ui.theme.LocalSlmColors
import kotlinx.coroutines.delay

private val SlmEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private data class LightRow(
    val flow: TrafficLightFlow?,
    val state: TrafficLightState,
    val seconds: Int?,
)

@Composable
fun TrafficLightCapsule(
    info: TrafficLightInfo?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = LocalSlmColors.current
    var nowMs by remember(info) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(info) {
        while (info != null) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }

    val current = info
    val rows = if (current == null) {
        emptyList()
    } else {
        val directions = current.directions
        if (directions.isNotEmpty()) {
            directions
                .sortedBy { it.flow.ordinal }
                .mapNotNull { direction ->
                    val reading = TrafficLightResolver.resolve(
                        TrafficLightInfo(
                            distanceM = current.distanceM,
                            timestampMs = current.timestampMs,
                            phases = direction.phases,
                        ),
                        nowMs,
                    ) ?: return@mapNotNull null
                    if (reading.state == TrafficLightState.OFF ||
                        reading.state == TrafficLightState.UNKNOWN
                    ) {
                        null
                    } else {
                        LightRow(direction.flow, reading.state, reading.seconds)
                    }
                }
        } else {
            val reading = TrafficLightResolver.resolve(current, nowMs)
            if (reading == null ||
                reading.state == TrafficLightState.OFF ||
                reading.state == TrafficLightState.UNKNOWN
            ) {
                emptyList()
            } else {
                listOf(LightRow(null, reading.state, reading.seconds))
            }
        }
    }
    val visible = current != null && current.distanceM <= 300.0 && rows.isNotEmpty()
    val flash = flashingAlpha()

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(400)) +
            slideInVertically(tween(550, easing = SlmEase)) { it } +
            expandVertically(tween(550, easing = SlmEase)),
        exit = fadeOut(tween(300)) +
            slideOutVertically(tween(450, easing = SlmEase)) { it } +
            shrinkVertically(tween(450, easing = SlmEase)),
    ) {
        Column(
            modifier = Modifier
                .padding(top = if (compact) 8.dp else 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surface)
                .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .padding(
                    horizontal = if (compact) 13.dp else 16.dp,
                    vertical = if (compact) 8.dp else 10.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (rows.size > 1) 5.dp else 0.dp),
        ) {
            rows.forEach { row ->
                val alpha = if (row.state == TrafficLightState.FLASHING_YELLOW) flash else 1f
                LightLine(
                    row = row,
                    alpha = alpha,
                    compact = compact,
                    showFlow = row.flow != null,
                )
            }
        }
    }
}

@Composable
private fun LightLine(
    row: LightRow,
    alpha: Float,
    compact: Boolean,
    showFlow: Boolean,
) {
    val colors = LocalSlmColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
    ) {
        if (showFlow && row.flow != null) {
            FlowGlyph(
                flow = row.flow,
                color = colors.text.copy(alpha = 0.66f),
                size = if (compact) 11.dp else 14.dp,
            )
            Spacer(Modifier.width(if (compact) 1.dp else 2.dp))
        }
        // Driving readability: 8dp dots were unreadable at a glance.
        val dotSize = if (compact) 9.dp else 13.dp
        LightDot(
            lit = row.state == TrafficLightState.RED,
            color = colors.red,
            alpha = alpha,
            size = dotSize,
        )
        LightDot(
            lit = row.state == TrafficLightState.YELLOW ||
                row.state == TrafficLightState.FLASHING_YELLOW,
            color = colors.amber,
            alpha = alpha,
            size = dotSize,
        )
        LightDot(
            lit = row.state == TrafficLightState.GREEN,
            color = colors.green,
            alpha = alpha,
            size = dotSize,
        )
        if (row.state != TrafficLightState.FLASHING_YELLOW &&
            row.seconds != null && row.seconds > 0
        ) {
            Spacer(Modifier.width(if (compact) 3.dp else 4.dp))
            Text(
                row.seconds.toString(),
                color = colors.text,
                fontSize = if (compact) 14.5.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun FlowGlyph(
    flow: TrafficLightFlow,
    color: Color,
    size: Dp,
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val c = w / 2f
        val stroke = 1.5.dp.toPx()
        val cap = StrokeCap.Round
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke, cap = cap)
        }
        when (flow) {
            TrafficLightFlow.STRAIGHT -> {
                line(c, h * 0.86f, c, h * 0.18f)
                line(c, h * 0.18f, c - w * 0.20f, h * 0.40f)
                line(c, h * 0.18f, c + w * 0.20f, h * 0.40f)
            }
            TrafficLightFlow.LEFT -> {
                line(w * 0.88f, c, w * 0.16f, c)
                line(w * 0.16f, c, w * 0.38f, c - h * 0.22f)
                line(w * 0.16f, c, w * 0.38f, c + h * 0.22f)
            }
            TrafficLightFlow.RIGHT -> {
                line(w * 0.12f, c, w * 0.84f, c)
                line(w * 0.84f, c, w * 0.62f, c - h * 0.22f)
                line(w * 0.84f, c, w * 0.62f, c + h * 0.22f)
            }
            TrafficLightFlow.UTURN -> {
                line(w * 0.72f, h * 0.86f, w * 0.72f, h * 0.42f)
                line(w * 0.72f, h * 0.42f, w * 0.34f, h * 0.42f)
                line(w * 0.34f, h * 0.42f, w * 0.34f, h * 0.70f)
                line(w * 0.34f, h * 0.70f, w * 0.18f, h * 0.54f)
                line(w * 0.34f, h * 0.70f, w * 0.50f, h * 0.54f)
            }
            TrafficLightFlow.OTHER -> {
                drawCircle(color, radius = w * 0.12f, center = Offset(c, c))
            }
        }
    }
}

@Composable
private fun flashingAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "trafficLightFlash")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse),
        label = "trafficLightFlashAlpha",
    )
    return alpha
}

@Composable
private fun LightDot(
    lit: Boolean,
    color: Color,
    alpha: Float,
    size: Dp = 8.dp,
) {
    val colors = LocalSlmColors.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (lit) color.copy(alpha = alpha) else colors.text.copy(alpha = 0.12f)),
    )
}
