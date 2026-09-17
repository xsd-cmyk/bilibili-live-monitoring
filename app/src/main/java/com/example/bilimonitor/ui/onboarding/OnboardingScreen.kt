package com.example.bilimonitor.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.background.MonitoringController
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.repository.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val monitoringController: MonitoringController
) : ViewModel() {

    val selected = MutableStateFlow(BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION)
    val noGuaranteeAcknowledged = MutableStateFlow(false)
    val saving = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun select(choice: BackgroundKeepAliveChoice) {
        selected.value = choice
        if (choice != BackgroundKeepAliveChoice.NO_GUARANTEE) {
            noGuaranteeAcknowledged.value = false
        }
    }

    fun acknowledge(value: Boolean) {
        noGuaranteeAcknowledged.value = value
    }

    fun complete(onDone: () -> Unit) {
        val choice = selected.value
        if (choice == BackgroundKeepAliveChoice.NO_GUARANTEE && !noGuaranteeAcknowledged.value) return
        viewModelScope.launch {
            saving.value = true
            error.value = null
            // completeOnboarding 返回是否真的写入了选择。
            // 原实现忽略返回值、无条件 onDone()，而 MainActivity 依据
            // backgroundKeepAliveChoice == UNSET 决定起始页，因此写入失败时
            // 用户会被放到首页，但重启后又回到引导页，形成"选了但没保存"的循环。
            val saved = runCatching {
                configRepository.completeOnboarding(
                    choice = choice,
                    noGuaranteeAcknowledged = noGuaranteeAcknowledged.value
                )
            }.getOrElse {
                saving.value = false
                error.value = "保存失败：${it.message}"
                return@launch
            }
            if (!saved) {
                saving.value = false
                error.value = "保存失败，请重试"
                return@launch
            }
            monitoringController.syncWithConfig()
            saving.value = false
            onDone()
        }
    }
}

/** 首次启动不可跳过的后台保活选择页（0.6.47.1，三选项文案与后果逐字呈现）。 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val acknowledged by viewModel.noGuaranteeAcknowledged.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("欢迎使用主播监控", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "首次使用，请选择一种后台保活方式。选择后可随时在设置页修改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        KeepAliveOptionCard(
            title = "选项一：前台常驻通知",
            selected = selected == BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION,
            onSelect = { viewModel.select(BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION) },
            lines = listOf(
                "用途：在通知栏常驻一条“正在监控 xx 位主播”的通知，用前台服务换取后台持续运行。",
                "需要：通知权限（Android 13+ 需用户授权）。",
                "代价：通知栏会一直有一条通知；Android 15+ 上系统对这类前台服务有累计运行时长限制，到达上限后会被系统停止。",
                "可随时：在设置页切换为其他方式。"
            )
        )
        Spacer(Modifier.height(12.dp))
        KeepAliveOptionCard(
            title = "选项二：无障碍服务（实验性）",
            selected = selected == BackgroundKeepAliveChoice.ACCESSIBILITY,
            onSelect = { viewModel.select(BackgroundKeepAliveChoice.ACCESSIBILITY) },
            lines = listOf(
                "用途：借助系统无障碍能力提高后台存活率。",
                "需要：跳到系统设置，由用户手动开启本应用的无障碍服务。",
                "代价：可能增加耗电、带来系统兼容性差异；属于实验性能力。",
                "承诺：本服务不读取、不上传、不记录与主播监控无关的屏幕内容、输入事件或个人数据。",
                "可随时：在系统设置或 App 设置页关闭。"
            )
        )
        // 选项二承诺"跳到系统设置"，这里给出实际入口（双保险模式同样需要开启无障碍）
        if (com.example.bilimonitor.background.MonitoringController.accessibilitySelected(selected)) {
            Spacer(Modifier.height(6.dp))
            com.example.bilimonitor.ui.common.AccessibilityEnableRow()
        }
        Spacer(Modifier.height(12.dp))
        KeepAliveOptionCard(
            title = "选项三：双保险（无障碍 + 前台通知）",
            selected = selected == BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND,
            onSelect = { viewModel.select(BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND) },
            lines = listOf(
                // Compose 的 Text 不渲染 Markdown：原来写成 **同时** 会字面显示星号
                "用途：两种保活方式「同时」生效 —— 无障碍让系统不容易回收进程，前台服务保证实时检查循环。",
                "需要：系统设置里的无障碍服务 + 通知权限。",
                "代价：通知栏常驻一条通知，且同时承担两种方式的耗电与兼容性差异。",
                "适合：通知经常收不到、或系统杀后台比较激进的机型。",
                "可随时：在设置页改回只保留其中一种。"
            )
        )
        Spacer(Modifier.height(12.dp))
        KeepAliveOptionCard(
            title = "选项四：不启用保活（明确告知风险）",
            selected = selected == BackgroundKeepAliveChoice.NO_GUARANTEE,
            onSelect = { viewModel.select(BackgroundKeepAliveChoice.NO_GUARANTEE) },
            lines = listOf(
                "用途：什么都不额外开启，App 只在系统允许的时机执行监控。",
                "代价：不保证后台一直存活；系统可能随时停止后台监控，期间不会检查主播状态，因此可能漏掉开播/关播通知；打开 App 后会立即补一次检查并如实显示“这段时间没有监控到”。"
            )
        )
        if (selected == BackgroundKeepAliveChoice.NO_GUARANTEE) {
            Spacer(Modifier.height(8.dp))
            // 整行可点：原来只有 20dp 的 Checkbox 本体能点，旁边那行说明文字点了没反应，
            // 触达区域只有一行里的一小块。toggleable + Role.Checkbox 让读屏也把整行报成
            // "复选框"；Checkbox 自身回调置 null，避免同一行出现两个可点/可读的目标。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = acknowledged,
                        role = Role.Checkbox,
                        onValueChange = { viewModel.acknowledge(it) }
                    )
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = null)
                Text("我已了解：不保证后台一直存活，可能漏掉通知")
            }
        }
        Spacer(Modifier.height(20.dp))
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { viewModel.complete(onDone) },
            enabled = !saving && !(selected == BackgroundKeepAliveChoice.NO_GUARANTEE && !acknowledged),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "保存中…" else "确认选择")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KeepAliveOptionCard(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    lines: List<String>
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onSelect
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
