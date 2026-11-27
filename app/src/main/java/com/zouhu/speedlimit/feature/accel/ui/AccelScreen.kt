package com.zouhu.speedlimit.feature.accel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zouhu.speedlimit.feature.accel.AccelAction
import com.zouhu.speedlimit.feature.accel.AccelUiState
import com.zouhu.speedlimit.feature.accel.AccelViewModel
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

private val tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun AccelScreen(viewModel: AccelViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalSlmColors.current

    BackHandler(onBack = onBack)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val gaugeSize = if (maxWidth < 340.dp) (maxWidth - 34.dp).coerceAtLeast(220.dp) else 296.dp
        Column(Modifier.fillMaxSize()) {
            Header(
                state = state,
                onBack = onBack,
                onToggleAssist = viewModel::setAssistEnabled,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(30.dp))
                AccelGauge(state, gaugeSize)
                Spacer(Modifier.height(18.dp))
                StatusLine(state)
                Spacer(Modifier.height(22.dp))
                ScoreStrip(state)
                Spacer(Modifier.height(20.dp))
            }
            ActionBar(state, viewModel)
        }
    }
}

@Composable
private fun Header(
    state: AccelUiState,
    onBack: () -> Unit,
    onToggleAssist: (Boolean) -> Unit,
) {
    val colors = LocalSlmColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.62f, size.height * 0.2f)
                    lineTo(size.width * 0.35f, size.height * 0.5f)
                    lineTo(size.width * 0.62f, size.height * 0.8f)
                }
                drawPath(
                    path,
                    colors.text,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Text(
            "零百加速",
            color = colors.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        AssistPill(state, onToggleAssist)
    }
}

@Composable
private fun AssistPill(state: AccelUiState, onToggle: (Boolean) -> Unit) {
    val colors = LocalSlmColors.current
    val available = state.assistAvailable
    val toggleEnabled = available && state.assistToggleEnabled
    val label = when {
        !available -> "辅助不可用"
        state.assistEnabled -> "辅助 开"
        else -> "辅助 关"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (state.assistEnabled && available) colors.mint.copy(alpha = 0.10f) else colors.surface,
            )
            .border(
                1.dp,
                if (state.assistEnabled && available) colors.mint.copy(alpha = 0.32f)
                else colors.text.copy(alpha = 0.10f),
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = toggleEnabled) { onToggle(!state.assistEnabled) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !available -> colors.dim
                state.assistEnabled -> colors.mint
                else -> colors.muted
            },
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AccelGauge(state: AccelUiState, diameter: Dp) {
    val colors = LocalSlmColors.current
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = diameter.toPx() * 0.0405f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = colors.gaugeTrack,
                startAngle = 45f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (state.progress > 0f) {
                drawArc(
                    color = colors.mint,
                    startAngle = 45f,
                    sweepAngle = 270f * state.progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                state.timerText,
                color = colors.text,
                fontSize = (diameter.value * 0.30f).sp,
                lineHeight = (diameter.value * 0.32f).sp,
                fontWeight = FontWeight.ExtraLight,
                style = tabular,
            )
            Text(
                "SEC",
                color = colors.dim,
                fontSize = 10.sp,
                letterSpacing = 2.6.sp,
                modifier = Modifier.padding(top = 9.dp),
            )
            Text(
                "${state.speedKmh} km/h",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Light,
                style = tabular,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(state: AccelUiState) {
    val colors = LocalSlmColors.current
    val text = when {
        state.statusTitle == "完成" || state.statusTitle == "无效" ->
            "${state.statusTitle} · ${state.statusDetail}"
        !state.hasFix -> "等待 GPS 定位"
        else -> state.statusDetail
    }
    Text(
        text,
        color = if (state.statusTitle == "无效") colors.amber else colors.muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
}

@Composable
private fun ScoreStrip(state: AccelUiState) {
    val colors = LocalSlmColors.current
    val result = state.result
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreMetric("0–60", result?.zeroTo60Text ?: "—", Modifier.weight(1f), colors)
        ThinDivider(colors)
        ScoreMetric("0–100", result?.zeroTo100Text ?: "—", Modifier.weight(1f), colors)
        ThinDivider(colors)
        ScoreMetric("历史最佳", result?.bestText ?: state.bestText, Modifier.weight(1f), colors)
    }
}

@Composable
private fun ThinDivider(colors: com.zouhu.speedlimit.ui.theme.SlmColors) {
    Box(
        Modifier
            .width(1.dp)
            .height(24.dp)
            .background(colors.text.copy(alpha = 0.07f)),
    )
}

@Composable
private fun ScoreMetric(
    label: String,
    value: String,
    modifier: Modifier,
    colors: com.zouhu.speedlimit.ui.theme.SlmColors,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = colors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            style = tabular,
            maxLines = 1,
        )
        Text(
            label,
            color = colors.dim,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun ActionBar(state: AccelUiState, viewModel: AccelViewModel) {
    val colors = LocalSlmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (state.action == AccelAction.END) colors.red.copy(alpha = 0.08f)
                    else colors.mint.copy(alpha = 0.08f),
                )
                .border(
                    1.dp,
                    if (state.action == AccelAction.END) colors.red.copy(alpha = 0.38f)
                    else colors.mint.copy(alpha = 0.38f),
                    RoundedCornerShape(999.dp),
                )
                .clickable { viewModel.onPrimaryAction() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                actionLabel(state.action),
                color = if (state.action == AccelAction.END) colors.red else colors.mint,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            "重置",
            color = colors.muted,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable { viewModel.reset() }
                .padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}

private fun actionLabel(action: AccelAction): String = when (action) {
    AccelAction.START -> "开始准备"
    AccelAction.CANCEL_STANDBY -> "取消准备"
    AccelAction.END -> "结束"
    AccelAction.RETRY -> "再测一次"
}
