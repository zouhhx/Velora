package com.zouhu.speedlimit.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zouhu.speedlimit.BuildConfig
import com.zouhu.speedlimit.data.service.ServiceHealth
import com.zouhu.speedlimit.data.service.ServiceStatus
import com.zouhu.speedlimit.data.service.label
import com.zouhu.speedlimit.data.settings.AppSigningInfo
import com.zouhu.speedlimit.feature.accel.ui.AccelActivity
import com.zouhu.speedlimit.ui.theme.LocalSlmColors

data class SettingsUi(
    val thresholdPct: Int = 10,
    val soundEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val ttsEnabled: Boolean = true,
    val dayNightMode: String = "day",
    val serverAkMasked: String = "",
    val serverAkConfigured: Boolean = false,
    val serverSkMasked: String = "",
    val serverSkConfigured: Boolean = false,
    val mimoMasked: String = "",
    val mimoConfigured: Boolean = false,
    val mapAkMasked: String = "",
    val mapAkConfigured: Boolean = false,
    val serviceStatus: ServiceStatus = ServiceStatus(),
)

@Composable
fun SettingsScreen(
    state: SettingsUi,
    onThreshold: (Int) -> Unit,
    onSound: (Boolean) -> Unit,
    onTts: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onDayNight: (String) -> Unit,
    onSaveServerAk: (String) -> Unit,
    onSaveServerSk: (String) -> Unit,
    onSaveMimoKey: (String) -> Unit,
    onSaveMapAk: (String) -> Unit,
    onResetServerAk: () -> Unit,
    onResetServerSk: () -> Unit,
    onResetMimoKey: () -> Unit,
    onResetMapAk: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalSlmColors.current
    val context = LocalContext.current
    var showDataServices by remember { mutableStateOf(false) }

    BackHandler(enabled = showDataServices) { showDataServices = false }

    if (showDataServices) {
        DataServicesPage(
            state = state,
            onSaveServerAk = onSaveServerAk,
            onSaveMimoKey = onSaveMimoKey,
            onSaveMapAk = onSaveMapAk,
            onResetServerAk = onResetServerAk,
            onResetMimoKey = onResetMimoKey,
            onResetMapAk = onResetMapAk,
            onBack = { showDataServices = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClose),
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
                "设置",
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionLabel("提醒")
            SettingsCard {
                Text("超速阈值", color = colors.muted, fontSize = 12.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 5, 10, 15, 20).forEach { value ->
                        Segment(
                            text = "+$value%",
                            selected = state.thresholdPct == value,
                            modifier = Modifier.weight(1f),
                        ) { onThreshold(value) }
                    }
                }
                ToggleRow("超速提示音", state.soundEnabled, onSound)
                ToggleRow("导航语音播报", state.ttsEnabled, onTts)
            }

            SectionLabel("显示")
            SettingsCard {
                ToggleRow("保持屏幕常亮", state.keepScreenOn, onKeepScreenOn)
                Text("导航地图日夜", color = colors.muted, fontSize = 12.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "day" to "白天",
                        "night" to "夜间",
                        "system" to "跟随系统",
                    ).forEach { (value, label) ->
                        Segment(
                            text = label,
                            selected = state.dayNightMode == value,
                            modifier = Modifier.weight(1f),
                        ) { onDayNight(value) }
                    }
                }
                Text(
                    "导航开始时生效；不按时段自动切换。",
                    color = colors.dim,
                    fontSize = 11.sp,
                )
            }

            SectionLabel("性能测试")
            SettingsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(context, AccelActivity::class.java))
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "零百加速",
                            color = colors.text,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "0–100 km/h · 分段计时 / 历史最佳",
                            color = colors.muted,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Text("›", color = colors.dim, fontSize = 20.sp)
                }
            }

            SectionLabel("服务")
            SettingsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showDataServices = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "数据服务与密钥",
                            color = colors.text,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            serviceSummary(state),
                            color = colors.muted,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Text("›", color = colors.dim, fontSize = 20.sp)
                }
            }

            if (BuildConfig.DEBUG) {
                SectionLabel("诊断（debug）")
                SettingsCard {
                    val ctx = LocalContext.current
                    Text(
                        "超视距路测诊断",
                        color = colors.mint,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        Intent().setClassName(
                                            ctx.packageName,
                                            "com.zouhu.speedlimit.diag.HorizonDiagActivity",
                                        ),
                                    )
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                    Text(
                        "导航诊断 / 导出日志",
                        color = colors.mint,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    ctx.startActivity(
                                        Intent().setClassName(
                                            ctx.packageName,
                                            "com.zouhu.speedlimit.diag.NavDiagActivity",
                                        ),
                                    )
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            SectionLabel("关于")
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Velora", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(BuildConfig.VERSION_NAME, color = colors.muted, fontSize = 12.5.sp)
                }
                Text(
                    "本机保存的 AK/Key 只保存在应用私有加密存储中，不写入日志；开源构建默认不含任何真实密钥。",
                    color = colors.dim,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private enum class SecretField(
    val title: String,
    val help: String,
    val placeholder: String,
) {
    SERVER_AK(
        "服务端 AK",
        "百度服务端 AK（超视距/HTTP 巡航/红绿灯）。保存后立即重连。",
        "粘贴服务端 AK",
    ),
    MIMO(
        "MiMo / TTS Key",
        "小米 MiMo 语音合成 Key；保存后下一次播报生效。",
        "粘贴 MiMo Key",
    ),
    MAP(
        "Android 应用端 AK",
        "地图/导航/搜索 AK，必须绑定包名 com.zouhu.speedlimit 与签名 SHA-1；保存后重启 App。",
        "粘贴 Android 应用端 AK",
    ),
}

@Composable
private fun DataServicesPage(
    state: SettingsUi,
    onSaveServerAk: (String) -> Unit,
    onSaveMimoKey: (String) -> Unit,
    onSaveMapAk: (String) -> Unit,
    onResetServerAk: () -> Unit,
    onResetMimoKey: () -> Unit,
    onResetMapAk: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalSlmColors.current
    val context = LocalContext.current
    var editing by remember { mutableStateOf<SecretField?>(null) }
    var editText by remember { mutableStateOf("") }
    var resetTarget by remember { mutableStateOf<SecretField?>(null) }

    fun openEditor(field: SecretField) {
        editing = field
        editText = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
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
                "数据服务与密钥",
                color = colors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsCard {
                SecretRow(
                    label = "服务端 AK",
                    masked = state.serverAkMasked,
                    configured = state.serverAkConfigured,
                    onEdit = { openEditor(SecretField.SERVER_AK) },
                    onReset = { resetTarget = SecretField.SERVER_AK },
                )
                SecretRow(
                    label = "Android 应用端 AK",
                    masked = state.mapAkMasked,
                    configured = state.mapAkConfigured,
                    onEdit = { openEditor(SecretField.MAP) },
                    onReset = { resetTarget = SecretField.MAP },
                )
                SecurityCodeRow(context)
                SecretRow(
                    label = "MiMo / TTS Key",
                    masked = state.mimoMasked,
                    configured = state.mimoConfigured,
                    onEdit = { openEditor(SecretField.MIMO) },
                    onReset = { resetTarget = SecretField.MIMO },
                )
                ServiceStatusRow("超视距", state.serviceStatus.horizon)
                ServiceStatusRow("红绿灯", state.serviceStatus.lamp)
                ServiceStatusRow("HTTP 巡航", state.serviceStatus.cruise)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { field ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(field.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(field.help, color = colors.muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text(field.placeholder) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "留空不会保存；如需清除本机值，请使用“恢复内置”。",
                        color = colors.dim,
                        fontSize = 10.5.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editText.isNotBlank(),
                    onClick = {
                        val value = editText.trim()
                        when (field) {
                            SecretField.SERVER_AK -> onSaveServerAk(value)
                            SecretField.MIMO -> onSaveMimoKey(value)
                            SecretField.MAP -> onSaveMapAk(value)
                        }
                        editing = null
                        editText = ""
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }

    resetTarget?.let { field ->
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text("恢复内置：${field.title}") },
            text = {
                Text(
                    if (field == SecretField.MAP) {
                        "将清除本机保存的地图 AK，改用构建内置值。重启 App 后生效。"
                    } else {
                        "将清除本机保存的值，改用构建内置值；若构建也未配置，则该项保持未配置。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (field) {
                        SecretField.SERVER_AK -> onResetServerAk()
                        SecretField.MIMO -> onResetMimoKey()
                        SecretField.MAP -> onResetMapAk()
                    }
                    resetTarget = null
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { resetTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SecurityCodeRow(context: Context) {
    val colors = LocalSlmColors.current
    val sha1 = remember(context) { AppSigningInfo.sha1(context) }
    val packageName = context.packageName
    val copyText = "SHA-1: $sha1; package: $packageName"
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "当前安全码（SHA-1;包名）",
                color = colors.muted,
                fontSize = 10.sp,
            )
            Text(
                sha1,
                color = colors.text,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                packageName,
                color = colors.dim,
                fontSize = 9.5.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Text(
            "复制",
            color = colors.mint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Velora signing", copyText))
                    Toast.makeText(context, "安全码已复制", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

private fun serviceSummary(state: SettingsUi): String {
    if (!state.serverAkConfigured) return "未配置服务端 AK"
    val http = when (state.serviceStatus.cruise) {
        ServiceHealth.ACTIVE -> "HTTP 正常"
        ServiceHealth.EXPIRED -> "HTTP 不可用"
        ServiceHealth.QUOTA -> "HTTP 配额用尽"
        ServiceHealth.CONNECTING -> "HTTP 连接中"
        else -> "HTTP 待连接"
    }
    val advanced = when {
        state.serviceStatus.horizon == ServiceHealth.ACTIVE &&
            state.serviceStatus.lamp == ServiceHealth.ACTIVE -> "超视距/红绿灯正常"
        state.serviceStatus.horizon == ServiceHealth.EXPIRED ||
            state.serviceStatus.lamp == ServiceHealth.EXPIRED -> "超视距/红绿灯不可用"
        state.serviceStatus.horizon == ServiceHealth.QUOTA ||
            state.serviceStatus.lamp == ServiceHealth.QUOTA -> "超视距/红绿灯配额用尽"
        else -> "超视距/红绿灯检查中"
    }
    return "$http · $advanced"
}

@Composable
private fun SecretRow(
    label: String,
    masked: String,
    configured: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalSlmColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.text, fontSize = 13.sp)
            Text(
                if (configured) "已配置：$masked" else "未配置",
                color = if (configured) colors.muted else colors.amber,
                fontSize = 10.5.sp,
            )
        }
        Text(
            "修改",
            color = colors.mint,
            fontSize = 11.sp,
            modifier = Modifier.clickable(onClick = onEdit).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        )
        if (configured) {
            Text(
                "恢复内置",
                color = colors.dim,
                fontSize = 10.5.sp,
                modifier = Modifier.clickable(onClick = onReset).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun ServiceStatusRow(label: String, health: ServiceHealth) {
    val colors = LocalSlmColors.current
    val color = when (health) {
        ServiceHealth.ACTIVE -> colors.mint
        ServiceHealth.EXPIRED, ServiceHealth.QUOTA, ServiceHealth.UNAVAILABLE -> colors.amber
        ServiceHealth.CONNECTING -> colors.blue
        ServiceHealth.UNCONFIGURED, ServiceHealth.IDLE -> colors.dim
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.muted, fontSize = 11.5.sp)
        Text(
            if (health == ServiceHealth.UNCONFIGURED) "未配置（不请求）" else health.label(),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalSlmColors.current
    Text(text, color = colors.muted, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val colors = LocalSlmColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.text.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalSlmColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.text, fontSize = 13.5.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.mint,
                uncheckedThumbColor = colors.muted,
                uncheckedTrackColor = colors.surface3,
                uncheckedBorderColor = colors.text.copy(alpha = 0.12f),
            ),
        )
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalSlmColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.mint.copy(alpha = 0.16f) else colors.surface2)
            .border(1.dp, if (selected) colors.mint else colors.text.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) colors.mint else colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
