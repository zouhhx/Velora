package com.zouhu.speedlimit.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

/**
 * 风格化矢量地图背景（复刻原型装饰地图，非真实瓦片）。
 * 在 400x800 坐标空间绘制道路/路线/定位点/标记。
 */
@Composable
fun MapBackdrop(modifier: Modifier = Modifier) {
    val colors = LocalSlmColors.current
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val s = maxOf(size.width / 400f, size.height / 800f)
        val ox = (size.width - 400f * s) / 2f
        val oy = (size.height - 800f * s) / 2f
        fun pt(x: Int, y: Int) = Offset(ox + x * s, oy + y * s)
        fun w(v: Number) = v.toFloat() * s

        drawRect(colors.mapBg)

        val park = Path().apply {
            moveTo(pt(-20, 120).x, pt(-20, 120).y)
            quadraticBezierTo(pt(140, 80).x, pt(140, 80).y, pt(240, 150).x, pt(240, 150).y)
            quadraticBezierTo(pt(340, 220).x, pt(340, 220).y, pt(420, 120).x, pt(420, 120).y)
        }
        drawPath(park, colors.mapPark.copy(alpha = 0.65f), style = Stroke(w(54), cap = StrokeCap.Round))

        polyline(listOf(pt(300, -20), pt(285, 200), pt(330, 420), pt(300, 820))).let {
            drawPath(it, colors.mapRoad, style = Stroke(w(24), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        polyline(listOf(pt(-20, 300), pt(180, 320), pt(320, 280), pt(420, 320))).let {
            drawPath(it, colors.mapRoad, style = Stroke(w(16), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        polyline(listOf(pt(80, -20), pt(120, 260), pt(90, 500), pt(160, 820))).let {
            drawPath(it, colors.mapRoad2, style = Stroke(w(13), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        polyline(listOf(pt(-20, 580), pt(160, 560), pt(300, 600), pt(420, 560))).let {
            drawPath(it, colors.mapRoad2, style = Stroke(w(12), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val route = polyline(listOf(pt(305, 820), pt(305, 440), pt(290, 280), pt(285, 200), pt(300, 60)))
        drawPath(route, colors.mint.copy(alpha = 0.14f), style = Stroke(w(16), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(route, colors.mint.copy(alpha = 0.95f), style = Stroke(w(7), cap = StrokeCap.Round, join = StrokeJoin.Round))

        mapSign(pt(305, 500), w(17), colors.sign, w(5), "120", textMeasurer, 14f * s)
        mapSign(pt(290, 280), w(17), colors.sign, w(5), "100", textMeasurer, 14f * s)

        drawRoundRect(
            color = colors.surface,
            topLeft = pt(291, 360),
            size = Size(w(26), w(20)),
            cornerRadius = CornerRadius(w(4)),
        )
        drawRoundRect(
            color = colors.red,
            topLeft = pt(291, 360),
            size = Size(w(26), w(20)),
            cornerRadius = CornerRadius(w(4)),
            style = Stroke(w(1.5f)),
        )
        drawCircle(colors.red, radius = w(4), center = pt(304, 370))

        drawCircle(colors.mint.copy(alpha = 0.18f), radius = w(26), center = pt(305, 620))
        drawCircle(colors.mint, radius = w(11), center = pt(305, 620))
        drawCircle(colors.mapBg, radius = w(11), center = pt(305, 620), style = Stroke(w(3)))
    }
}

private fun DrawScope.mapSign(
    center: Offset,
    radius: Float,
    ring: Color,
    ringWidth: Float,
    label: String,
    textMeasurer: TextMeasurer,
    textSizePx: Float,
) {
    drawCircle(Color.White, radius = radius, center = center)
    drawCircle(ring, radius = radius, center = center, style = Stroke(ringWidth))
    val layout = textMeasurer.measure(
        AnnotatedString(label),
        style = TextStyle(
            color = Color(0xFF1A1C20),
            fontSize = (textSizePx / (density * fontScale)).sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            center.x - layout.size.width / 2f,
            center.y - layout.size.height / 2f,
        ),
    )
}

private fun polyline(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
}