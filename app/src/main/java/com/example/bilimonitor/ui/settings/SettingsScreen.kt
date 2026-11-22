package com.example.bilimonitor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.ui.common.AppCard
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.FloatingTopBarTitle
import com.example.bilimonitor.ui.nav.LocalDockClearance

internal enum class SettingsGroup(val route: String, val title: String, val description: String) {
    MONITOR("settings/monitor", "监控策略", "运行模式、检查间隔、主播名称和高级请求策略"),
    NOTICES("settings/notices", "通知设置", "通知有效期、批量合并、免打扰和常驻通知内容"),
    BACKGROUND("settings/background", "后台运行", "保活方式、电源豁免、锁屏与高级保活")
}

@Composable
internal fun SettingsScreen(
    onOpenGroup: (SettingsGroup) -> Unit,
    onOpenLogin: () -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenTaxonomy: () -> Unit = {},
    onOpenIconPreset: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    SettingsPage("设置", viewModel) {
        item("monitor") {
            SettingSectionCard("监控") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启监控", fontWeight = FontWeight.Medium)
                        Text(
                            "关闭后不再检查直播状态",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        enabled = config != null,
                        checked = config?.monitoringEnabled ?: false,
                        onCheckedChange = { viewModel.setMonitoringEnabled(it) }
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingsGroup.entries.forEach { group ->
                    SettingsEntry(group.title, group.description) { onOpenGroup(group) }
                }
            }
        }
        item("appearance") {
            SettingSectionCard("外观") {
                SettingsEntry(
                    title = "外观与主题",
                    description = "名称与图标、主题色、背景图、卡片、夜间模式、底部导航",
                    onClick = onOpenAppearance
                )
                // 「更换应用图标」放在这一张卡里，而不是自己新开一张：
                //  1. 它是**纯外观偏好**（不影响监控、通知、数据任何一条业务链路），
                //     与同一张卡里的名称/主题色/背景图是同一类东西；
                //  2. 设置页此前已经被用户吐槽过"入口太散"（见上方「数据」卡里的同款取舍）——
                //     为一个入口新开一张卡，只会让页面又多一段只有一行的高度、更难找；
                //  3. 「外观」卡原本只有一行，加进来正好成了一个像样的分组。
                // 注意与「外观与主题 → 应用图标」区分：那一项是上传自定义图片做**桌面快捷方式**，
                // 这里换的是应用**预设图标清单**，所以描述刻意写成"从预设图片中选择"。
                SettingsEntry(
                    title = "更换应用图标",
                    description = "从预设图片中选择应用图标",
                    onClick = onOpenIconPreset
                )
            }
        }
        item("data") {
            SettingSectionCard("数据") {
                // 标签 / 分组放在「数据」而不是新开一张卡：它们和这里已有的三项是**同一类**东西
                // —— 用户自己积累、又需要单独维护的本机数据（备份/恢复/导出处理的是"整库数据"，
                // 通知历史是"系统写的数据"，诊断是"数据的健康度"）。新开一张卡只为一个入口，
                // 会让设置页多出一段只有一行的高度，反而更难找（用户此前反馈过入口太散）。
                SettingsEntry(
                    title = "标签 / 分组管理",
                    description = "调整标签与分组的显示顺序，删除不再需要的项",
                    onClick = onOpenTaxonomy
                )
                SettingsEntry(
                    title = "备份 / 恢复 / 导出",
                    description = "导出直播历史、统计图表，或整体备份与恢复",
                    onClick = onOpenData
                )
                SettingsEntry(
                    title = "通知历史",
                    description = "查看已发出的开播/关播等通知记录",
                    onClick = onOpenNotifications
                )
                SettingsEntry(
                    title = "健康中心 / 诊断",
                    description = "保活状态、监控盲区与请求错误排查",
                    onClick = onOpenDiagnostics
                )
            }
        }
        item("account") {
            SettingSectionCard("账号") {
                SettingsEntry(
                    title = "B 站账号 / 关注导入",
                    description = "登录后可导入关注列表；不登录也能正常监控",
                    onClick = onOpenLogin
                )
            }
        }
        item("about") {
            SettingSectionCard("关于") {
                // 版本号从包信息读取，避免与 build.gradle.kts 的 versionName 脱节
                // （此前硬编码 v1.0，而 versionName 已升到 1.1）。
                Text("${stringResource(id = com.example.bilimonitor.R.string.app_name)} ${appVersionLabel()}", fontWeight = FontWeight.Medium)
                Text(
                    "本地优先的直播监控工具；数据全部保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // AI 声明（用户要求）：与上面那句简介同观感（bodySmall + onSurfaceVariant），
                // 但语义不同 —— 上面讲"应用是什么"，这句讲"它是怎么做出来的、可信度边界在哪"，
                // 所以中间留一点间距分段，避免被读成同一句话的续写。
                // 文案里引号用「」而不是中文书名号/英文引号，与全应用既有写法保持一致。
                Spacer(Modifier.height(8.dp))
                Text(
                    "AI 声明：本应用的代码、文档与测试在开发过程中由 AI 协助完成，并经人工审查与实机验证。" +
                        "AI 可能产生不准确的内容，如发现异常请在「健康中心 / 诊断」中查看详情并反馈。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun SettingsPage(
    title: String, viewModel: SettingsViewModel, onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    val clearance = if (onBack == null) LocalDockClearance.current ?: 24.dp else 16.dp
    Scaffold(topBar = {
        FloatingTopBar(title = { FloatingTopBarTitle(title) }, navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            com.example.bilimonitor.ui.common.MessageBanner(message = message, autoDismissMillis = 2000L,
                onDismiss = { viewModel.message.value = null })
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = clearance + 16.dp),
                content = content)
        }
    }
}

/**
 * 前台常驻通知的内容自定义（用户要求）。
 *
 * 三个设计点：
 *  1. **给出占位符而不是纯自由文本**：常驻通知最适合顺带报一句"在监控多少位、几位在直播"，
 *     所以支持 `{count}` / `{live}`，并在卡片里直接列出可用占位符。
 *  2. **实时预览**：通知栏里的效果取决于系统字体与截断，光看输入框想象不出来；
 *     预览用真实渲染函数（[NotificationTemplate.render]），占位符以示例数字代入。
 *  3. **长度上限写在输入框下方**：超长文本会被系统截断或换行错乱，
 *     而常驻通知"难看一次要看很久"，所以宁可提前告诉用户，也不让他写完才发现。
 */
@Composable
internal fun NotificationContentCard(
    template: com.example.bilimonitor.data.repository.ForegroundNotificationTemplate,
    onSave: (title: String, text: String) -> Unit,
    onReset: () -> Unit
) {
    var title by rememberSaveable(template.title) { mutableStateOf(template.title) }
    var text by rememberSaveable(template.text) { mutableStateOf(template.text) }
    // ★ 预览必须走**保存时同一套规整**（换行→空格、trim、按上限截断），否则预览不是所见即所得：
    //   原先直接渲染原文，输入 60 字时预览显示 60 字，保存后系统里只剩 40 字，且没有任何提示
    //   （复查发现的缺陷）。规整函数与 NotificationSettingsRepository.set 用的是同两个。
    val tpl = com.example.bilimonitor.data.repository.NotificationTemplate
    val titleSanitized = tpl.sanitizeTitle(title)
    val textSanitized = tpl.sanitizeText(text)
    val preview = tpl.render(titleSanitized, 12, 3)
    val previewText = tpl.render(textSanitized, 12, 3)

    SettingSectionCard("前台通知内容") {
        Text(
            "自定义常驻通知的标题与正文。可用占位符：{count} = 监控中的主播数，{live} = 正在直播的主播数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "最多 ${com.example.bilimonitor.data.repository.NotificationTemplate.MAX_TITLE_LENGTH} 字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("正文") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "最多 ${com.example.bilimonitor.data.repository.NotificationTemplate.MAX_TEXT_LENGTH} 字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        // 预览：用真实的渲染函数 + 示例数字（12 位在监控、3 位在直播）
        AppCard {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "预览（示例：12 位在监控、3 位在直播）" +
                        if (titleSanitized != title || textSanitized != text)
                            "　·　已按上限截断并把换行换成空格，预览即最终效果"
                        else "",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(preview.ifBlank { "（标题为空，将使用默认值）" }, style = MaterialTheme.typography.bodyMedium)
                Text(previewText.ifBlank { "（正文为空，将使用默认值）" }, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(title, text) }) { Text("保存") }
            TextButton(onClick = { onReset() }) { Text("恢复默认") }
        }
    }
}

/**
 * 统一的设置入口行（标题 + 说明 + 右侧箭头）。
 *
 * 抽出来的原因（用户反馈"可选项太多导致 UI 变形"）：原来五个入口混用 `Button`/`OutlinedButton`、
 * 还出现"一行放两个"，宽度与高度都不一致，卡片看着就乱。
 * 现在所有入口都是同一形状的整行，卡片再长也是整齐的一列。
 */
@Composable
private fun SettingsEntry(title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 用文本箭头而不是 Icons.*：这套依赖里可用图标集不稳定，
        // 一个入口行没必要为图标名承担编译风险。
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun keepAliveLabel(choice: BackgroundKeepAliveChoice?): String = when (choice) {
    BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION -> "当前：前台常驻通知"
    BackgroundKeepAliveChoice.ACCESSIBILITY -> "当前：无障碍服务（实验性）"
    BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND -> "当前：无障碍服务 + 前台常驻通知（双保险）"
    BackgroundKeepAliveChoice.NO_GUARANTEE -> "当前：不启用保活"
    else -> "尚未选择（实时监控不会启动）"
}

@Composable
internal fun SettingSectionCard(title: String, content: @Composable () -> Unit) {
    // fillMaxWidth 必须有：Card 默认按内容宽度包裹，内容短的卡片（如「关于」）会明显比
    // 其它卡片窄一截，看起来像没对齐（用户反馈）。这一处修好，所有分节卡片一起对齐。
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * 数值输入设置项（用户定稿：不用滑块）——数字键盘输入 + 保存按钮，范围外禁用保存。
 */
@Composable
internal fun NumberInputSetting(
    label: String,
    value: Int,
    validRange: IntRange,
    strictInput: Boolean = false,
    onCommit: (Int) -> Unit
) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in validRange
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = if (strictInput) input else input.filter { ch -> ch.isDigit() }.take(6) },
                singleLine = true,
                isError = invalid && text.isNotEmpty(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { parsed?.let(onCommit) },
                enabled = !invalid && parsed != value
            ) { Text("保存") }
        }
        if (invalid && text.isNotEmpty()) {
            Text(
                "请输入 ${validRange.first}–${validRange.last} 之间的整数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
internal fun StepperSetting(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    min: Int = 1,
    max: Int = 10
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // 到边界时用 enabled = false 表达，而不是"按钮可点、点了没反应"（读屏会念出"已停用"，
        // 用户立刻知道到顶了）；符号本身没有语义，再补一句 contentDescription。
        TextButton(
            onClick = { onCommit(value - 1) },
            enabled = value > min,
            modifier = Modifier.semantics { contentDescription = "减小" }
        ) { Text("−") }
        Text("$value", fontWeight = FontWeight.Medium)
        TextButton(
            onClick = { onCommit(value + 1) },
            enabled = value < max,
            modifier = Modifier.semantics { contentDescription = "增大" }
        ) { Text("+") }
    }
}

// ---- 免打扰时段输入（HH:mm）----

/** 从包信息读取版本名，失败时回退占位文案。 */
@Composable
private fun appVersionLabel(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.let { "v$it" } ?: "v?"
    }
}

internal fun QuietHoursPolicyFormat(minutes: Int): String =
    com.example.bilimonitor.domain.policy.QuietHoursPolicy.format(minutes)

/** 解析 HH:mm / H:mm；非法返回 null。 */
internal fun parseTimeOfDay(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@Composable
internal fun TimeOfDayInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit
) {
    val valid = parseTimeOfDay(value) != null
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                // 只允许数字与冒号，最长 5 字符（HH:mm）
                onValueChange(input.filter { it.isDigit() || it == ':' }.take(5))
            },
            singleLine = true,
            isError = !valid,
            modifier = Modifier.width(110.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onCommit, enabled = valid) { Text("保存") }
    }
}
