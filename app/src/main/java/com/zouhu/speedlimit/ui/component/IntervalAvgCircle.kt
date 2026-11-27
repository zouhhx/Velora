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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.domain.model.OverSpeedState
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

@Composable
fun IntervalAvgCircle(
    avgKmh: Int,
    state: OverSpeedState,
    limit: Int?,
    modifier: Modifier = Modifier,
    diameter: Dp = 128.dp,
) {
    val colors = LocalSlmColors.current
    val frac = if (limit != null && limit > 0) (avgKmh / (limit * 1.2f)).coerceIn(0f, 1f) else 0f
    val flash = state == OverSpeedState.SEVERE
    val infinite = rememberInfiniteTransition(label = "avg")
    val blink by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink",
    )
    val a = if (flash) blink else 1f
    val over = state == OverSpeedState.OVER || state == OverSpeedState.SEVERE
    val ringColor = if (over) colors.red else colors.mint
    val numColor = if (over) colors.red else colors.text

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = diameter.toPx() * 0.0667f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = colors.text.copy(alpha = 0.08f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke),
            )
            if (frac > 0f) {
                drawArc(
                    color = ringColor.copy(alpha = a),
                    startAngle = -90f,
                    sweepAngle = 360f * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                avgKmh.toString(),
                color = numColor.copy(alpha = a),
                fontSize = (diameter.value * 0.30f).sp,
                lineHeight = (diameter.value * 0.30f).sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                "区间平均",
                color = colors.dim,
                fontSize = (diameter.value * 0.078f).sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}