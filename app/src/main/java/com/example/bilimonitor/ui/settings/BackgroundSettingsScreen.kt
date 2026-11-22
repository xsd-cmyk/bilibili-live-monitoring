package com.example.bilimonitor.ui.settings

import com.example.bilimonitor.ui.common.AppAlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice

@Composable
internal fun BackgroundSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onReonboard: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val advancedKeepAlive by viewModel.advancedKeepAlive.collectAsStateWithLifecycle()
    val privilegeEnv by viewModel.privilegeEnv.collectAsStateWithLifecycle()
    val keepAliveReport by viewModel.keepAliveReport.collectAsStateWithLifecycle()
    val keepAliveBusy by viewModel.keepAliveBusy.collectAsStateWithLifecycle()
    val powerExemption by viewModel.powerExemption.collectAsStateWithLifecycle()
    val wakelockHeld by viewModel.wakelockHeld.collectAsStateWithLifecycle()
    val wakelockStatus by viewModel.wakelockStatus.collectAsStateWithLifecycle()
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    DisposableEffect(viewModel) {
        viewModel.enterBackgroundSettings()
        onDispose { viewModel.leaveBackgroundSettings() }
    }
    var notificationsEnabled by remember { mutableStateOf(viewModel.notificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        notificationsEnabled = viewModel.notificationsEnabled()
        // 电池优化白名单/悬浮窗都是**系统状态**：从系统对话框返回时必须重读，
        // 否则界面会一直显示"未加入"，用户以为申请没生效又反复点。
        viewModel.refreshPowerExemption()
        onPauseOrDispose { }
    }
    SettingsPage(SettingsGroup.BACKGROUND.title, viewModel, onBack) {
        item("choice") {
            SettingSectionCard("后台保活") {
                Text(
                    keepAliveLabel(config?.backgroundKeepAliveChoice),
                    style = MaterialTheme.typography.bodyMedium
                )
                // 选择了无障碍保活时，展示**实际**开启状态与跳转入口
                // （规格 0.6.47.2：不得显示用户选择暗示的乐观状态）。
                // 双保险模式同样要展示 —— 无障碍那一半失效时用户必须能立刻看到并去重新开启。
                if (com.example.bilimonitor.background.MonitoringController
                        .accessibilitySelected(config?.backgroundKeepAliveChoice)
                ) {
                    Spacer(Modifier.height(8.dp))
                    com.example.bilimonitor.ui.common.AccessibilityEnableRow()
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showKeepAliveDialog = true }) { Text("修改保活方式") }
                    TextButton(onClick = onReonboard) { Text("查看说明") }
                }
                if (!notificationsEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "通知权限未授予：后台仍可监控，但无法发出通知",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item("advanced") {
            AdvancedKeepAliveCard(
                wakelockEnabled = advancedKeepAlive.wakelockEnabled,
                wakelockHeld = wakelockHeld,
                wakelockStatus = wakelockStatus,
                currentLevel = advancedKeepAlive.privilegeLevel,
                power = powerExemption,
                onRequestIgnoreBattery = { viewModel.requestIgnoreBatteryOptimizations() },
                onOpenAutoStart = { viewModel.openAutoStartSettings() },
                onOpenExactAlarm = { viewModel.openExactAlarmSettings() },
                env = privilegeEnv,
                report = keepAliveReport,
                busy = keepAliveBusy,
                onToggleWakelock = { viewModel.setAdvancedWakelock(it) },
                onDetect = { viewModel.refreshPrivilegeEnvironment(true) },
                onRequestShizuku = { viewModel.requestShizukuPermission() },
                onApply = { viewModel.applyAdvancedKeepAlive(it) },
                onRevert = { viewModel.revertAdvancedKeepAlive() },
                onProbe = { viewModel.probeAdvancedStatus() }
            )
        }
    }
    if (showKeepAliveDialog) {
        var selected by remember { mutableStateOf(config?.backgroundKeepAliveChoice ?: BackgroundKeepAliveChoice.UNSET) }
        var acknowledged by remember {
            mutableStateOf(config?.noGuaranteeAcknowledged ?: false)
        }
        AppAlertDialog(
            onDismissRequest = { showKeepAliveDialog = false },
            title = { Text("选择后台保活方式") },
            text = {
                Column {
                    listOf(
                        BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION to "前台常驻通知（推荐）",
                        BackgroundKeepAliveChoice.ACCESSIBILITY to "无障碍服务（实验性）",
                        // 双保险（用户要求）：无障碍打开之后仍可同时启用前台通知保活。
                        // 两种机制互不冲突 —— 无障碍让系统不容易回收进程，前台服务保证实时循环，
                        // 叠加起来存活率最高（代价是多一条常驻通知）。
                        BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND to
                            "无障碍服务 + 前台常驻通知（双保险）",
                        BackgroundKeepAliveChoice.NO_GUARANTEE to "不启用保活（可能漏掉通知）"
                    ).forEach { (choice, label) ->
                        // 整行可点（理由同"运行模式"的单选项）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == choice,
                                    role = Role.RadioButton,
                                    onClick = { selected = choice }
                                )
                        ) {
                            RadioButton(selected = selected == choice, onClick = null)
                            Text(label)
                        }
                    }
                    if (selected == BackgroundKeepAliveChoice.ACCESSIBILITY ||
                        selected == BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND
                    ) {
                        Text(
                            "无障碍服务需要在系统设置里手动开启；双保险模式下会同时保留一条前台常驻通知。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected == BackgroundKeepAliveChoice.NO_GUARANTEE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = acknowledged,
                                onCheckedChange = { acknowledged = it }
                            )
                            Text("我了解可能漏掉通知")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.changeKeepAliveChoice(selected, acknowledged)
                        showKeepAliveDialog = false
                    },
                    enabled = selected != BackgroundKeepAliveChoice.UNSET &&
                        !(selected == BackgroundKeepAliveChoice.NO_GUARANTEE && !acknowledged)
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showKeepAliveDialog = false }) { Text("取消") } }
        )
    }
}
