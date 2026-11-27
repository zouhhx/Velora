package com.zouhu.speedlimit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.domain.model.OverSpeedState
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

@Composable
fun StatusBadge(state: OverSpeedState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = LocalSlmColors.current
    val visible = state == OverSpeedState.OVER || state == OverSpeedState.SEVERE
    var lastState by remember { mutableStateOf(OverSpeedState.OVER) }
    if (visible) lastState = state
    val flash = state == OverSpeedState.SEVERE
    val infinite = rememberInfiniteTransition(label = "badge")
    val blink by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink",
    )
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(300)),
    ) {
        val text: String
        val fg: Color
        val bg: Color
        when (lastState) {
            OverSpeedState.SEVERE -> { text = "超速 · 前方测速"; fg = Color.White; bg = colors.red }
            else -> { text = "超速"; fg = colors.red; bg = colors.red.copy(alpha = 0.16f) }
        }
        Box(
            modifier = Modifier
                .alpha(if (flash) blink else 1f)
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .padding(
                    horizontal = if (compact) 11.dp else 15.dp,
                    vertical = if (compact) 4.dp else 6.dp,
                ),
        ) {
            Text(text, color = fg, fontSize = if (compact) 10.sp else 11.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}