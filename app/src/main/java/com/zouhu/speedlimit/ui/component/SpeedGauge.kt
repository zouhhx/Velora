package com.zouhu.speedlimit.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.domain.model.OverSpeedState
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

@Composable
fun SpeedGauge(
    speedKmh: Int,
    overSpeed: OverSpeedState,
    modifier: Modifier = Modifier,
    diameter: Dp = 296.dp,
) {
    val colors = LocalSlmColors.current
    val frac = (speedKmh / 200f).coerceIn(0f, 1f)
    val flash = overSpeed == OverSpeedState.SEVERE
    val infinite = rememberInfiniteTransition(label = "gauge")
    val blink by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink",
    )
    val a = if (flash) blink else 1f
    val over = overSpeed == OverSpeedState.OVER || overSpeed == OverSpeedState.SEVERE
    val numColor = if (over) colors.red else colors.text
    val track = colors.gaugeTrack
    val fillBrush = if (over) SolidColor(colors.red) else Brush.linearGradient(listOf(colors.mint, colors.blue))
    val numberSize = (diameter.value * 0.365f).sp
    val compact = diameter < 220.dp

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = diameter.toPx() * 0.0405f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 45f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (frac > 0f) {
                drawArc(
                    brush = fillBrush,
                    startAngle = 45f,
                    sweepAngle = 270f * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                speedKmh.toString(),
                color = numColor.copy(alpha = a),
                fontSize = numberSize,
                lineHeight = numberSize,
                fontWeight = FontWeight.Light,
            )
            Text(
                "KM/H",
                color = colors.dim,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = if (compact) 6.dp else 12.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = if (compact) 10.dp else 18.dp)
                    .height(if (compact) 22.dp else 27.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusBadge(overSpeed, compact = compact)
            }
        }
    }
}