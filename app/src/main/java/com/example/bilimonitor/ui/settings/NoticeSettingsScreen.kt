package com.example.bilimonitor.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun NoticeSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    SettingsPage(SettingsGroup.NOTICES.title, viewModel, onBack) {
        item("delivery") { NotificationDeliverySettingsCard() }
        item("aggregation") {
            SettingSectionCard("通知聚合") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("批量开播合并通知", fontWeight = FontWeight.Medium)
                        Text(
                            // 措辞要点：阈值是"超过多少位才合并"，不是"每次合并多少位" ——
                            // 用户明确纠正过这个区别，文案不许再写成后者。
                            "同一批（几秒内）需要通知的主播「超过」下面的阈值时，" +
                                "把这一批「全部」合并成一条通知；不超过则逐个单独发",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config?.aggregationEnabled ?: true,
                        onCheckedChange = { viewModel.update { s -> s.copy(aggregationEnabled = it) } }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("批量下播合并通知", fontWeight = FontWeight.Medium)
                        Text(
                            // 与开播方向同一套语义（阈值 = 触发条件），并点明两者互相独立：
                            // 它们各自一个聚合窗口，同一批里既有开播又有下播也不会串在一起。
                            "同一批（几秒内）下播的主播「超过」下面的阈值时，把这一批「全部」合并成一条通知；" +
                                "与开播合并各自成窗、互不影响",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config?.endAggregationEnabled ?: true,
                        onCheckedChange = { viewModel.update { s -> s.copy(endAggregationEnabled = it) } }
                    )
                }
                StepperSetting(
                    // 措辞与开关说明一致：阈值 = "超过多少位就合并"，不是"每次合并多少位"。
                    label = "超过 ${config?.aggregationThreshold ?: 4} 位主播需要通知时合并" +
                        "（当前 ${config?.aggregationThreshold ?: 4}）",
                    value = config?.aggregationThreshold ?: 4,
                    onCommit = { viewModel.update { s -> s.copy(aggregationThreshold = it) } }
                )
            }
        }
        item("quiet") {
            val quietHours by viewModel.quietHours.collectAsStateWithLifecycle()
            SettingSectionCard("免打扰时段") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启免打扰", fontWeight = FontWeight.Medium)
                        Text(
                            "时段内不发送开播/关播通知；场次与历史照常记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = quietHours.enabled,
                        onCheckedChange = { on ->
                            viewModel.setQuietHours(on, quietHours.startMinutes, quietHours.endMinutes)
                        }
                    )
                }
                if (quietHours.enabled) {
                    // 起止相同的历史数据（本修复之前存下的）也要如实说明它不生效 ——
                    // 否则用户看着"已开启"却永远收不到抑制效果，只能怀疑是应用坏了。
                    val savedZeroLength = quietHours.startMinutes == quietHours.endMinutes
                    Text(
                        "当前时段：${QuietHoursPolicyFormat(quietHours.startMinutes)}–" +
                            "${QuietHoursPolicyFormat(quietHours.endMinutes)}（可跨午夜）" +
                            if (savedZeroLength) "　起止相同 = 未设置，当前不会抑制任何通知" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    var startText by rememberSaveable(quietHours.startMinutes) {
                        mutableStateOf(QuietHoursPolicyFormat(quietHours.startMinutes))
                    }
                    var endText by rememberSaveable(quietHours.endMinutes) {
                        mutableStateOf(QuietHoursPolicyFormat(quietHours.endMinutes))
                    }
                    // 两个输入框共用一个提交入口：任一字段点「保存」时都提交**两者当前输入的内容**。
                    // 原实现各自提交时只读取"另一个字段上一次已保存的值"，
                    // 于是改了「结束」再改「开始」，结束时间会被回滚成旧值。
                    val commitBoth = {
                        val s = parseTimeOfDay(startText)
                        val e = parseTimeOfDay(endText)
                        // ★ 起止相同 = 零长度时段，策略层明确按"未配置"处理
                        //   （QuietHoursPolicy.contains 在 start == end 时恒返回 false），
                        //   而这里原先照存不误 —— 界面显示"免打扰已开启：23:00–23:00"、
                        //   提示"已开启"，实际一条通知都不会被抑制（复查发现的缺陷）。
                        //   直接拒绝写入，并在下面给出原因。
                        if (s != null && e != null && s != e) viewModel.setQuietHours(true, s, e)
                    }
                    TimeOfDayInput(
                        label = "开始",
                        value = startText,
                        onValueChange = { startText = it },
                        onCommit = commitBoth
                    )
                    TimeOfDayInput(
                        label = "结束",
                        value = endText,
                        onValueChange = { endText = it },
                        onCommit = commitBoth
                    )
                    val startBad = parseTimeOfDay(startText) == null
                    val endBad = parseTimeOfDay(endText) == null
                    val sameBad = !startBad && !endBad &&
                        parseTimeOfDay(startText) == parseTimeOfDay(endText)
                    if (startBad || endBad || sameBad) {
                        Text(
                            when {
                                startBad && endBad -> "开始与结束时间格式应为 HH:mm（如 23:00）"
                                startBad -> "开始时间格式应为 HH:mm（如 23:00）"
                                endBad -> "结束时间格式应为 HH:mm（如 07:00）"
                                else -> "开始与结束不能相同：起止相同等于未设置，免打扰不会生效"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "跨午夜无需特殊设置：例如 23:00–07:00 即表示当天 23:00 到次日 07:00。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (com.example.bilimonitor.background.MonitoringController.foregroundKeepAliveSelected(config?.backgroundKeepAliveChoice)) {
            item("template") {
                val template by viewModel.notificationTemplate.collectAsStateWithLifecycle()
                NotificationContentCard(
                    template = template,
                    onSave = { title, text -> viewModel.saveNotificationTemplate(title, text) },
                    onReset = { viewModel.resetNotificationTemplate() }
                )
            }
        }
    }
}
