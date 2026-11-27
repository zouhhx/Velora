package com.zouhu.speedlimit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.domain.model.CameraType
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

private val SlmEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun CameraCapsule(
    visible: Boolean,
    cameraType: CameraType,
    distText: String,
    label: String,
    limit: Int?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = LocalSlmColors.current
    val gap = if (compact) 9.dp else 12.dp
    val signSize = if (compact) 32.dp else 40.dp
    val iconSize = if (compact) 18.dp else 22.dp
    val fontSize = if (compact) 11.5.sp else 13.5.sp
    val leadingGap by animateDpAsState(if (limit != null) gap else 0.dp, tween(450, easing = SlmEase))
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
        Row(
            modifier = Modifier
                .padding(top = if (compact) 10.dp else 14.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surface)
                .border(1.dp, colors.text.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .padding(
                    start = if (compact) 7.dp else 10.dp,
                    end = if (compact) 15.dp else 20.dp,
                    top = if (compact) 6.dp else 9.dp,
                    bottom = if (compact) 6.dp else 9.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniLimitSign(limit, size = signSize)
            Spacer(Modifier.width(leadingGap))
            CamIcon(cameraType, colors.red, Modifier.size(iconSize))
            Spacer(Modifier.width(gap))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Bold)) { append(distText) }
                    withStyle(SpanStyle(color = colors.muted)) { append(" 后 · $label") }
                },
                fontSize = fontSize,
            )
        }
    }
}

@Composable
fun CamIcon(type: CameraType, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun pt(x: Number, y: Number) = Offset(x.toFloat() * sx, y.toFloat() * sy)
        fun Path.m(x: Number, y: Number) { moveTo(x.toFloat() * sx, y.toFloat() * sy) }
        fun Path.l(x: Number, y: Number) { lineTo(x.toFloat() * sx, y.toFloat() * sy) }
        val sw = 1.8f * sx
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (type == CameraType.INTERVAL) {
            drawCircle(color, radius = 8f * sx, center = pt(12, 13), style = stroke)
            val hand = Path().apply { m(12, 9); l(12, 13); l(14.6f, 14.6f) }
            drawPath(hand, color, style = stroke)
            drawLine(color, pt(9.5f, 3), pt(14.5f, 3), sw, StrokeCap.Round)
            drawLine(color, pt(12, 3), pt(12, 5.5f), sw, StrokeCap.Round)
        } else {
            drawRoundRect(
                color = color,
                topLeft = pt(3, 7),
                size = Size(13f * sx, 10f * sy),
                cornerRadius = CornerRadius(2f * sx),
                style = stroke,
            )
            val lens = Path().apply { m(16, 10); l(21, 8); l(21, 16); l(16, 14); close() }
            drawPath(lens, color, style = stroke)
            drawCircle(color, radius = 2.4f * sx, center = pt(9.5f, 12), style = stroke)
        }
    }
}