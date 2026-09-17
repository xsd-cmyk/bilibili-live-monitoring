package com.example.bilimonitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.FloatingTopBarTitle

@Composable
fun SettingsScreen(
    onReonboard: () -> Unit,
    onOpenLogin: () -> Unit = {},
    onOpenData: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenTaxonomy: () -> Unit = {},
    // 与 onOpenTaxonomy 同一写法：**给默认值**，这样新增入口不会破坏其它调用方
    // （任何一个已经在调 SettingsScreen(...) 的地方都不会因为少传这个参数而编译失败）。
    onOpenIconPreset: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val quietHours by viewModel.quietHours.collectAsStateWithLifecycle()
    val template by viewModel.notificationTemplate.collectAsStateWithLifecycle()
    // 数据过期提示开关（DataStore 展示偏好，默认开）：与 config 不同源，单独收一条流。
    val freshnessExpiryHintEnabled by viewModel.freshnessExpiryHintEnabled.collectAsStateWithLifecycle()
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    // 通知权限是**系统状态**，不是 Compose 状态：在组合期直接调 checkSelfPermission 的话，
    // 用户去系统设置里授权/撤销后回到本页，读到的仍是进入页面那一刻的值 —— 页面也不会重组，
    // 提示就会一直停在错误的旧结论上。每次回到前台（ON_RESUME）重新查一次并写进 state。
    var notificationsEnabled by remember { mutableStateOf(viewModel.notificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        notificationsEnabled = viewModel.notificationsEnabled()
        onPauseOrDispose { }
    }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.message.value = null
        }
    }

    Scaffold(
        topBar = { FloatingTopBar(title = { FloatingTopBarTitle("设置") }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 保存结果反馈（原实现 collect 了 message 并 2 秒后清除，却没有任何渲染点，
            // 导致"设置已被其他修改更新""参数非法"等结果全部不可见）。
            // 已有 LaunchedEffect(message) 负责清除，故这里不自带计时。
            com.example.bilimonitor.ui.common.MessageBanner(
                message = message,
                autoDismissMillis = null,
                onDismiss = { viewModel.message.value = null }
            )
            SettingSectionCard("监控") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启监控", fontWeight = FontWeight.Medium)
                        Text(
                            "关闭后不再执行任何检查",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config?.monitoringEnabled ?: false,
                        onCheckedChange = { viewModel.setMonitoringEnabled(it) }
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("运行模式", fontWeight = FontWeight.Medium)
                listOf(
                    MonitoringMode.REALTIME to "实时监控（前台服务，间隔更短）",
                    MonitoringMode.POWER_SAVING to "省电监控（系统调度，约 15 分钟以上）",
                    MonitoringMode.MANUAL to "手动（仅打开 App 时检查）"
                ).forEach { (mode, label) ->
                    // 整行可点：RadioButton 本体只有 ~20dp，只让它响应点击会让人以为
                    // "点文字没反应"，读屏下也不够 48dp 触控区
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = config?.mode == mode,
                                role = Role.RadioButton,
                                onClick = { viewModel.update { it.copy(mode = mode) } }
                            )
                    ) {
                        // onClick = null：点击交给整行，避免两个可点区域
                        RadioButton(selected = config?.mode == mode, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // 检查间隔：下限 30s、新装默认 60s（用户要求）。
                // 这三处必须同步 —— 设置页范围（本控件）/ 仓储 merge() 的校验下限 / DbSeed 的种子值，
                // 任何一处不同步都会出现"设置页显示的值"与"实际生效的值"互相打脸。
                // `?: 60` 只是 config 还没从库里发射（null）时的展示兜底，取种子同值以免数字跳变。
                NumberInputSetting(
                    label = "检查间隔（秒，30–3600）",
                    value = config?.intervalSeconds ?: 60,
                    validRange = 30..3600,
                    onCommit = { viewModel.update { s -> s.copy(intervalSeconds = it) } }
                )
                StepperSetting(
                    label = "开播确认次数：${config?.startConfirmationCount ?: 1}（默认 1，立即通知）",
                    value = config?.startConfirmationCount ?: 1,
                    onCommit = { viewModel.update { s -> s.copy(startConfirmationCount = it) } }
                )
                StepperSetting(
                    label = "关播确认次数：${config?.endConfirmationCount ?: 1}（默认 1，防误报）",
                    value = config?.endConfirmationCount ?: 1,
                    onCommit = { viewModel.update { s -> s.copy(endConfirmationCount = it) } }
                )
            }

            SettingSectionCard("通知聚合") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("批量开播合并通知", fontWeight = FontWeight.Medium)
                        Text(
                            "多位主播同时开播时合并为一条通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config?.aggregationEnabled ?: true,
                        onCheckedChange = { viewModel.update { s -> s.copy(aggregationEnabled = it) } }
                    )
                }
                StepperSetting(
                    label = "合并阈值：${config?.aggregationThreshold ?: 4} 个开播事件",
                    value = config?.aggregationThreshold ?: 4,
                    onCommit = { viewModel.update { s -> s.copy(aggregationThreshold = it) } }
                )
            }

            // 免打扰时段（原规范 22.3）：规格要求可配、默认关。此前完全没有实现。
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
                    var startText by remember(quietHours.startMinutes) {
                        mutableStateOf(QuietHoursPolicyFormat(quietHours.startMinutes))
                    }
                    var endText by remember(quietHours.endMinutes) {
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

            // 前台常驻通知的内容可自定义（用户要求）。
            // 只在"确实会用前台保活"时才显示这一块，避免给不用它的人增加噪声。
            if (com.example.bilimonitor.background.MonitoringController
                    .foregroundKeepAliveSelected(config?.backgroundKeepAliveChoice)
            ) {
                NotificationContentCard(
                    template = template,
                    onSave = { title, text -> viewModel.saveNotificationTemplate(title, text) },
                    onReset = { viewModel.resetNotificationTemplate() }
                )
            }

            SettingSectionCard("高级请求策略") {
                NumberInputSetting(
                    label = "单批主播数（10–100）",
                    value = config?.batchSize ?: 50,
                    validRange = 10..100,
                    onCommit = { viewModel.update { s -> s.copy(batchSize = it) } }
                )
                NumberInputSetting(
                    label = "并发批次数（1–16）",
                    value = config?.maxConcurrency ?: 4,
                    validRange = 1..16,
                    onCommit = { viewModel.update { s -> s.copy(maxConcurrency = it) } }
                )
                // 批次间隔：新装默认 5s（用户要求）。`?: 5` 是 config 为 null 时的展示兜底，
                // 必须与 DbSeed 的种子值一致（理由同「检查间隔」）。单位是秒，引擎按 ms 使用。
                NumberInputSetting(
                    label = "批次间隔（秒，0–300）",
                    value = config?.batchCooldownSeconds ?: 5,
                    validRange = 0..300,
                    onCommit = { viewModel.update { s -> s.copy(batchCooldownSeconds = it) } }
                )
                // 请求超时：新装默认 8s（用户要求），兜底与种子同值。
                // 单位是秒；引擎侧按 `timeoutSeconds * 1000 + 5000` 作为单批预算（秒 → 毫秒）。
                NumberInputSetting(
                    label = "请求超时（秒，5–60）",
                    value = config?.timeoutSeconds ?: 8,
                    validRange = 5..60,
                    onCommit = { viewModel.update { s -> s.copy(timeoutSeconds = it) } }
                )
                StepperSetting(
                    label = "最大重试次数：${config?.maxRetries ?: 2}（0–5）",
                    value = config?.maxRetries ?: 2,
                    min = 0,
                    max = 5,
                    onCommit = { viewModel.update { s -> s.copy(maxRetries = it) } }
                )
                StepperSetting(
                    label = "熔断阈值：连续 ${config?.circuitBreakerThreshold ?: 3} 次失败后暂停请求",
                    value = config?.circuitBreakerThreshold ?: 3,
                    min = 1,
                    max = 10,
                    onCommit = { viewModel.update { s -> s.copy(circuitBreakerThreshold = it) } }
                )
                // ★ 用户诉求：一个坏房间不得拖垮全体检测。
                //   下面这一项决定"这一轮要不要进入重试与熔断"：只有失败占比**超过**它才触发；
                //   少数主播失败（例如一个被封禁的房间）不重试、不熔断，其余主播照常检测。
                //   范围 1–100 与 ConfigRepository.merge 的校验、RoundFailureRatio.THRESHOLD_RANGE 三处同步。
                NumberInputSetting(
                    label = "触发重试/熔断的最小失败比例（%，1–100）",
                    value = config?.retryFailureRatioPercent
                        ?: com.example.bilimonitor.domain.policy.RoundFailureRatio.DEFAULT_THRESHOLD_PERCENT,
                    validRange = com.example.bilimonitor.domain.policy.RoundFailureRatio.THRESHOLD_RANGE,
                    onCommit = { viewModel.update { s -> s.copy(retryFailureRatioPercent = it) } }
                )
                Text(
                    "本轮失败主播占比超过该比例才重试并计入熔断；低于它时只记录失败，" +
                        "不重试、不熔断，其他主播照常检测。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // ★ 「封禁中」主播的复查间隔：封禁判定依据 room_init 的 is_locked（公开接口，免登录），
                //   未解除期间按这个间隔低频复查，解封后立即恢复正常间隔。
                //   范围 60–86400 与 ConfigRepository.merge、RoomBanPolicy.RECHECK_RANGE 三处同步。
                NumberInputSetting(
                    label = "封禁中的检查间隔（秒，60–86400）",
                    value = config?.banRecheckIntervalSeconds
                        ?: com.example.bilimonitor.domain.policy.RoomBanPolicy.DEFAULT_RECHECK_SECONDS,
                    validRange = com.example.bilimonitor.domain.policy.RoomBanPolicy.RECHECK_RANGE,
                    onCommit = { viewModel.update { s -> s.copy(banRecheckIntervalSeconds = it) } }
                )
                Text(
                    "封禁中的主播按此间隔复查一次（默认 1800 秒 = 30 分钟），不再参与每轮常规检查；" +
                        "解封后自动恢复正常间隔。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // ★ 「开启数据过期提示」（用户要求：高级设置里可开关，**默认开启**）。
                //   它只决定首页卡片要不要追加「· 数据过期」这一个标记：
                //   时间行（「N 分钟前」）无论开关如何都会显示 —— 用户明确要求"仍显示时间，
                //   方便看到目前状态"。存放位置是 DataStore 展示偏好（AppearanceSettingsRepository），
                //   不是 monitoring_config：纯展示开关，塞进配置表要动 schema/迁移/备份/诊断四处。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启数据过期提示", fontWeight = FontWeight.Medium)
                        Text(
                            "数据超过阈值没有更新时，在首页卡片上标注「数据过期」；关闭后不标注，" +
                                "但仍显示上次检查时间",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = freshnessExpiryHintEnabled,
                        onCheckedChange = { viewModel.setFreshnessExpiryHint(it) }
                    )
                }
                // ★ 数据过期阈值（用户要求：分钟粒度输入，1–1440）。
                //   **复用既有的 `freshnessStaleSeconds`（秒）**，不新增任何字段 ——
                //   它已经是唯一生效的阈值（引擎判 FRESH/STALE 用的就是它），再加一个
                //   "过期阈值"只会出现两个互相打架的阈值；复用则是零迁移：
                //   列已存在、ConfigUpdate 已支持、校验已有（30–86400 秒）、备份 DTO 与诊断导出都已带上。
                //   界面用**分钟**：提交时 ×60、展示时秒 → 分钟（向上取整，见 FreshnessPolicy.thresholdMinutesOf）。
                //   已知取舍：既有的 30–59 秒无法用整分钟表达，界面会显示成 1 分钟（不精确，
                //   但填进合法范围、且用户不点「保存」就不会写库）。
                NumberInputSetting(
                    label = "数据过期阈值（分钟，1–1440）",
                    value = com.example.bilimonitor.domain.policy.FreshnessPolicy.thresholdMinutesOf(
                        config?.freshnessStaleSeconds
                            ?: com.example.bilimonitor.domain.policy.FreshnessPolicy.DEFAULT_THRESHOLD_SECONDS
                    ),
                    validRange = com.example.bilimonitor.domain.policy.FreshnessPolicy.THRESHOLD_MINUTES_RANGE,
                    onCommit = { minutes ->
                        viewModel.update { s ->
                            s.copy(
                                freshnessStaleSeconds =
                                    com.example.bilimonitor.domain.policy.FreshnessPolicy
                                        .thresholdSecondsOf(minutes)
                            )
                        }
                    }
                )
                Text(
                    "默认 5 分钟。超过该时长没有成功确认过状态就标注「数据过期」；" +
                        "实际生效的阈值不会小于监控周期的 2 倍 —— 省电模式周期 15 分钟起，" +
                        "因此那里的阈值至少按 30 分钟算，否则会永远显示过期。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // ★ 熔断开关（用户诉求：给熔断加一个开关）。
                //   默认 **开**：新开关的默认值必须保持"引入它之前"的既有行为，
                //   所以 checked 的兜底是 true —— 与实体默认值、DDL 的 `DEFAULT 1` 三处一致。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用熔断", fontWeight = FontWeight.Medium)
                        Text(
                            "连续失败达到阈值后暂停请求，等过了下面的重试间隔再尝试恢复检测；" +
                                "关闭后失败的批次会一直重试、继续请求，不再暂停。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config?.circuitBreakerEnabled ?: true,
                        onCheckedChange = { viewModel.update { s -> s.copy(circuitBreakerEnabled = it) } }
                    )
                }
                // ★ 熔断后的恢复检测间隔（用户诉求：这个间隔要能调）。
                //   **复用既有的 `circuitBreakerRecoverySeconds`（秒）**，不新增任何字段 ——
                //   它本来就是"熔断打开后等多久再尝试检测"，此前的问题只是**设置页改不了**
                //   （唯一写途径是备份恢复），于是把它开放出来即可，零迁移、零新口径。
                //   单位就是**秒**，提交时不做 ×60 之类换算。
                //   范围 1–3600 与 ConfigRepository.merge 的 `cbRecovery` 校验逐字同步
                //   （只改一处会出现"界面能填、保存却报参数非法"的割裂）。
                //   展示兜底 `?: 60` 必须与 DbSeed 的种子值一致（新装默认 60 秒 = 1 分钟）。
                NumberInputSetting(
                    label = "熔断后重试间隔（秒，1–3600，默认 60 = 1 分钟）",
                    value = config?.circuitBreakerRecoverySeconds ?: 60,
                    validRange = 1..3600,
                    onCommit = { viewModel.update { s -> s.copy(circuitBreakerRecoverySeconds = it) } }
                )
                Text(
                    "熔断打开后等这么久再尝试恢复检测（默认 60 秒 = 1 分钟）；" +
                        "到时先试一批，成功就立刻恢复正常检测。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "全部配置项均已在监控引擎中生效；修改后立即作用于下一次检查。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 用户反馈：「账号与数据」一张卡塞了 5 个入口，界面被挤变形。
            // 拆成三张语义清晰的卡，并且**统一**每个入口的样式（整行 OutlinedButton + 一行说明），
            // 而不是原来那种"有的 Button、有的 OutlinedButton、还有一行放两个"的混排。
            SettingSectionCard("账号") {
                SettingsEntry(
                    title = "B 站账号 / 关注导入",
                    description = "登录后可导入关注列表；不登录也能正常监控",
                    onClick = onOpenLogin
                )
            }

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

            // 外观相关集中在一处（用户要求：不要把软件的主题选项都塞进"自定义图标和名称"里）。
            // 入口名改为「外观与主题」，与屏幕内的分卡一致；名称/图标只是其中一项。
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
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showKeepAliveDialog) {
        var selected by remember { mutableStateOf(config?.backgroundKeepAliveChoice ?: BackgroundKeepAliveChoice.UNSET) }
        var acknowledged by remember {
            mutableStateOf(config?.noGuaranteeAcknowledged ?: false)
        }
        AlertDialog(
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
private fun NotificationContentCard(
    template: com.example.bilimonitor.data.repository.ForegroundNotificationTemplate,
    onSave: (title: String, text: String) -> Unit,
    onReset: () -> Unit
) {
    var title by remember(template.title) { mutableStateOf(template.title) }
    var text by remember(template.text) { mutableStateOf(template.text) }
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
        Card {
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
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
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
}

private fun keepAliveLabel(choice: BackgroundKeepAliveChoice?): String = when (choice) {    BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION -> "当前：前台常驻通知"
    BackgroundKeepAliveChoice.ACCESSIBILITY -> "当前：无障碍服务（实验性）"
    BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND -> "当前：无障碍服务 + 前台常驻通知（双保险）"
    BackgroundKeepAliveChoice.NO_GUARANTEE -> "当前：不启用保活"
    else -> "尚未选择（实时监控不会启动）"
}

@Composable
private fun SettingSectionCard(title: String, content: @Composable () -> Unit) {
    // fillMaxWidth 必须有：Card 默认按内容宽度包裹，内容短的卡片（如「关于」）会明显比
    // 其它卡片窄一截，看起来像没对齐（用户反馈）。这一处修好，所有分节卡片一起对齐。
    Card(Modifier.fillMaxWidth()) {
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
private fun NumberInputSetting(
    label: String,
    value: Int,
    validRange: IntRange,
    onCommit: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in validRange
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { ch -> ch.isDigit() }.take(6) },
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
private fun StepperSetting(
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

private fun QuietHoursPolicyFormat(minutes: Int): String =
    com.example.bilimonitor.domain.policy.QuietHoursPolicy.format(minutes)

/** 解析 HH:mm / H:mm；非法返回 null。 */
private fun parseTimeOfDay(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

@Composable
private fun TimeOfDayInput(
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
