package com.example.bilimonitor.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.domain.policy.NameRefreshPolicy

@Composable
internal fun MonitorSettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    SettingsPage(SettingsGroup.MONITOR.title, viewModel, onBack) {
        item("mode") {
            SettingSectionCard("运行与确认") {
                Text("运行模式", fontWeight = FontWeight.Medium)
                listOf(
                    MonitoringMode.REALTIME to "实时监控（前台服务，锁屏实时会增加耗电）",
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
        }
        item("names") {
            val nameRefresh by viewModel.nameRefresh.collectAsStateWithLifecycle()
            SettingSectionCard("主播名称") {
                Text("获取失败时保留旧名，只重试失败的主播。系统限制后台运行时可能延后。")
                NumberInputSetting("正常获取间隔（小时）", config?.nameRefreshHours ?: 24,
                    NameRefreshPolicy.hours, strictInput = true) { hours ->
                    viewModel.update(syncMonitoring = false) { it.copy(nameRefreshHours = hours) }
                }
                NumberInputSetting("失败重试间隔（分钟）", config?.nameRetryMinutes ?: 1,
                    NameRefreshPolicy.minutes, strictInput = true) { minutes ->
                    viewModel.update(syncMonitoring = false) { it.copy(nameRetryMinutes = minutes) }
                }
                Button(onClick = viewModel::refreshAllNames, enabled = !nameRefresh.running && config != null) {
                    Text(if (nameRefresh.running) "正在获取主播名称…" else "立即重新获取所有主播名称")
                }
                nameRefresh.result?.let { result ->
                    Text(if (result.success + result.retry + result.skipped == 0) "没有可刷新的主播"
                        else "成功 ${result.success}，待重试 ${result.retry}，跳过 ${result.skipped}（含锁名、已删除或已变更）")
                }
                if (nameRefresh.failed) Text("名称刷新未完成，请重试", color = MaterialTheme.colorScheme.error)
            }
        }
        item("requests") {
            val freshnessExpiryHintEnabled by viewModel.freshnessExpiryHintEnabled.collectAsStateWithLifecycle()
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
        }
    }
}
