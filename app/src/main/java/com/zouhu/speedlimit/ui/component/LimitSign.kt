package com.zouhu.speedlimit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

@Composable
fun LimitSign(limit: Int?, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val colors = LocalSlmColors.current
    var lastLimit by remember { mutableStateOf(limit ?: 0) }
    if (limit != null) lastLimit = limit
    AnimatedVisibility(
        visible = limit != null,
        modifier = modifier,
        enter = fadeIn(tween(450)) + scaleIn(tween(500), initialScale = 0.5f),
        exit = fadeOut(tween(450)) + scaleOut(tween(500), targetScale = 0.5f),
    ) {
        LimitSignFace(lastLimit, size, size * 0.111f, colors.sign)
    }
}

@Composable
fun MiniLimitSign(limit: Int?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = LocalSlmColors.current
    var lastLimit by remember { mutableStateOf(limit ?: 0) }
    if (limit != null) lastLimit = limit
    AnimatedVisibility(
        visible = limit != null,
        modifier = modifier,
        enter = fadeIn(tween(300)) + expandHorizontally(tween(450), expandFrom = Alignment.Start),
        exit = fadeOut(tween(300)) + shrinkHorizontally(tween(450), shrinkTowards = Alignment.Start),
    ) {
        LimitSignFace(lastLimit, size, size * 0.128f, colors.sign)
    }
}

@Composable
private fun LimitSignFace(value: Int, size: Dp, border: Dp, ring: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .border(border, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value.toString(),
            color = Color(0xFF1A1C20),
            fontSize = (size.value * 0.43f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
