package com.example.bilimonitor.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.repository.HealthRepository
import com.example.bilimonitor.data.repository.OverallHealth
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.MonitoringGapDao
import com.example.bilimonitor.data.local.dao.ProblemDao
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.HealthEventEntity
import com.example.bilimonitor.data.local.entity.MonitoringErrorLogEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapStreamerEntity
import com.example.bilimonitor.data.local.entity.ProblemStateEntity
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.home.formatTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsState(
    val health: OverallHealth? = null,
    /** 当前未恢复的主播级盲区（affectedEndAt IS NULL）。 */
    val openStreamerGaps: List<MonitoringGapStreamerEntity> = emptyList(),
    val problems: List<ProblemStateEntity> = emptyList(),
    val healthEvents: List<HealthEventEntity> = emptyList(),
    val monitoringErrors: List<MonitoringErrorLogEntity> = emptyList(),
    /** 软件运行错误日志（与监控错误严格区分，原规范 209）。 */
    val appErrors: List<ApplicationErrorLogEntity> = emptyList(),
    /** 崩溃记录（原规范 81/225）。 */
    val crashes: List<com.example.bilimonitor.data.local.entity.CrashRecordEntity> = emptyList(),
    /** 恢复检查会话（原规范 177）。 */
    val recoverySessions: List<com.example.bilimonitor.data.local.entity.RecoverySessionEntity> = emptyList(),
    /** 容量使用率（原规范 56 低水位预警）。 */
    val capacity: com.example.bilimonitor.data.repository.MaintenanceRepository.CapacityUsage? = null
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    healthRepository: HealthRepository,
    gapDao: MonitoringGapDao,
    problemDao: ProblemDao,
    logDao: LogDao,
    private val crashDao: com.example.bilimonitor.data.local.dao.CrashRecordDao,
    private val recoverySessionDao: com.example.bilimonitor.data.local.dao.RecoverySessionDao,
    private val maintenanceRepository: com.example.bilimonitor.data.repository.MaintenanceRepository,
    private val diagnosticExporter: com.example.bilimonitor.data.repository.DiagnosticExporter
) : ViewModel() {

    /** 导出诊断包的一次性反馈。 */
    private val _exportMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage

    /**
     * 导出中标志：按钮置灰与文案切换的依据（真值来源在 ViewModel，不在 Composable 里）。
     */
    private val _isExporting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    /** 崩溃记录与恢复会话按需刷新（非响应式表，进入页面时拉一次）。 */
    private val crashes = kotlinx.coroutines.flow.MutableStateFlow(
        emptyList<com.example.bilimonitor.data.local.entity.CrashRecordEntity>()
    )
    private val recoverySessions = kotlinx.coroutines.flow.MutableStateFlow(
        emptyList<com.example.bilimonitor.data.local.entity.RecoverySessionEntity>()
    )
    private val capacity = kotlinx.coroutines.flow.MutableStateFlow<com.example.bilimonitor.data.repository.MaintenanceRepository.CapacityUsage?>(null)

    init {
        refreshAuxiliary()
    }

    fun refreshAuxiliary() {
        viewModelScope.launch {
            crashes.value = runCatching { crashDao.recent(20) }.getOrDefault(emptyList())
            recoverySessions.value = runCatching { recoverySessionDao.recent(10) }.getOrDefault(emptyList())
            capacity.value = runCatching { maintenanceRepository.capacityUsage() }.getOrNull()
        }
    }

    fun exportDiagnostics() {
        // ★ 守卫与置位都必须在 `launch` 之外：放进协程里的话，两次点击之间来得及再进一次
        //   （与 DataScreen.runBusy 同一个教训）。连点的后果不是"多导出一份"——MediaStore 会把
        //   第二个落成 `... (1).zip`，而提示里只报了一个文件名，用户容易以为只有一份。
        if (_isExporting.value) return
        _isExporting.value = true
        viewModelScope.launch {
            try {
            _exportMessage.value = "正在生成诊断包…"
            val result = diagnosticExporter.export()
            _exportMessage.value = result.fold(
                onSuccess = { "诊断包已导出到 Download/${it.fileName}（${it.entryCount} 个文件，${it.bytes / 1024} KB）" },
                onFailure = { "导出失败：${it.message}" }
            )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点完导出立刻返回上一页 → viewModelScope 被取消。这只是"这次操作被取消"，
                // 不是失败：报"导出失败"会让用户以为包坏了或空间不够，还会去重试；
                // 原样重抛，把取消语义继续往上传。
                throw e
            } finally {
                // 成功/失败/取消三条路都要复位，否则按钮会永久置灰。
                _isExporting.value = false
            }
        }
    }

    fun clearExportMessage() { _exportMessage.value = null }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<DiagnosticsState> = combine(
        // 先用嵌套把 7 路压到 5 路：combine 只有到 5 路的具名重载，
        // 多出来的会落到 vararg 重载并退化成 Array<Any?>。
        kotlinx.coroutines.flow.combine(
            healthRepository.observeHealth(),
            gapDao.observeOpenStreamerGaps(50),
            problemDao.observeActive()
        ) { health, gaps, problems -> Triple(health, gaps, problems) },
        logDao.observeHealthEvents(50),
        kotlinx.coroutines.flow.combine(
            logDao.observeMonitoringErrors(50),
            logDao.observeAppErrors(50)
        ) { mon, app -> mon to app },
        kotlinx.coroutines.flow.combine(crashes, recoverySessions, capacity) { c, r, cap ->
            Triple(c, r, cap)
        }
    ) { head, healthEvents, logs, aux ->
        val (health, gaps, problems) = head
        val (monErrors, appErrors) = logs
        val (crashList, recoveryList, capacityUsage) = aux
        DiagnosticsState(
            health = health,
            openStreamerGaps = gaps,
            problems = problems,
            healthEvents = healthEvents,
            monitoringErrors = monErrors,
            appErrors = appErrors,
            crashes = crashList,
            recoverySessions = recoveryList,
            capacity = capacityUsage
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsState())
}

/** 健康中心 / 诊断（原规范 57 / 100「昨天发生了什么」简化版）。 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    // collectAsStateWithLifecycle：诊断页挂着 7 路 Room 查询（含 gap/problem/log 全表观察），
    // 用 collectAsState 会让它们在应用进后台后继续跑
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportMessage by viewModel.exportMessage.collectAsStateWithLifecycle()
    // 与按钮的 enabled/文案联动：导出中把文案换成"正在生成诊断包…"，
    // 让用户知道刚才那一下已经生效，而不是又点一次。
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(exportMessage) {
        if (exportMessage != null) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearExportMessage()
        }
    }
    Scaffold(
        topBar = { FloatingTopBar(title = { Text("健康中心") }, navigationIcon = {
            // 与全项目其它页面统一：返回用箭头图标，而不是中文「返回」文字按钮
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 一键导出诊断包（原规范 219）：便于排查问题时提供给开发者。
            // 包含运行信息、日志、健康快照与脱敏配置；不含登录凭证与主播历史。
            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("诊断包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "导出运行信息、监控错误、软件错误、健康状态与脱敏配置（不含登录凭证与主播历史）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Button(
                            onClick = { viewModel.exportDiagnostics() },
                            // 导出期间置灰：包体可能有几 MB，用户看不到进度就会连点，
                            // 而连点只会让 MediaStore 多留几个 `... (1).zip`。
                            enabled = !isExporting
                        ) {
                            Text(if (isExporting) "正在生成诊断包…" else "导出诊断包")
                        }
                        exportMessage?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("当前状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(state.health?.userMessage ?: "…")
                        Text(
                            "监控 ${state.health?.monitoredCount ?: 0} 位主播 · 其中 ${state.health?.liveCount ?: 0} 位在播 · " +
                                "封禁中：${state.health?.bannedCount ?: 0} 位 · " +
                                "前台服务：${if (state.health?.foregroundServiceRunning == true) "运行中" else "未运行"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { SectionTitle("主播级盲区（未恢复 ${state.openStreamerGaps.size}）") }
            if (state.openStreamerGaps.isEmpty()) item { EmptyHint("无未恢复的主播级盲区") }
            items(state.openStreamerGaps.size) { i ->
                val g = state.openStreamerGaps[i]
                Card { Column(Modifier.padding(10.dp)) {
                    Text(
                        "主播 ${g.streamerStableId.takeLast(8)}… · ${gapReasonText(g.reason.name)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("自 ${formatTime(g.affectedStartAt)} 起", style = MaterialTheme.typography.labelSmall)
                } }
            }
            item { SectionTitle("活动问题（${state.problems.size}）") }
            if (state.problems.isEmpty()) item { EmptyHint("无活动问题") }
            items(state.problems.size) { i ->
                val p = state.problems[i]
                Card { Column(Modifier.padding(10.dp)) {
                    Text(problemText(p.problemKey), style = MaterialTheme.typography.bodySmall)
                    Text("自 ${formatTime(p.firstObservedAt)} 起", style = MaterialTheme.typography.labelSmall)
                } }
            }
            item { SectionTitle("健康事件（最近）") }
            if (state.healthEvents.isEmpty()) item { EmptyHint("暂无记录") }
            items(state.healthEvents.size) { i ->
                val h = state.healthEvents[i]
                Card { Text(
                    "${formatTime(h.occurredAt)} · ${h.component}: ${healthStatusText(h.fromStatus?.name)} → ${healthStatusText(h.toStatus.name)}",
                    Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall
                ) }
            }
            item { SectionTitle("监控错误（最近 ${state.monitoringErrors.size}）") }
            if (state.monitoringErrors.isEmpty()) item { EmptyHint("暂无监控错误") }
            items(state.monitoringErrors.size) { i ->
                val e = state.monitoringErrors[i]
                Card { Column(Modifier.padding(10.dp)) {
                    Text("${formatTime(e.occurredAt)} · ${errorText(e.errorCode.name)}", style = MaterialTheme.typography.bodySmall)
                    (e.streamerStableId?.let { Text("主播 ${it.takeLast(8)}…", style = MaterialTheme.typography.labelSmall) })
                } }
            }
            // 软件运行错误：与"监控错误"严格区分（原规范 209）。
            // 该数据此前完全没有被订阅，用户看不到任何应用侧异常。
            item { SectionTitle("软件运行错误（最近 ${state.appErrors.size}）") }
            if (state.appErrors.isEmpty()) item { EmptyHint("暂无软件错误") }
            items(state.appErrors.size) { i ->
                val e = state.appErrors[i]
                Card { Column(Modifier.padding(10.dp)) {
                    Text("${formatTime(e.occurredAt)} · ${errorText(e.errorCode.name)}", style = MaterialTheme.typography.bodySmall)
                    e.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }
            }
            // 容量使用率（原规范 56 低水位预警）：达到 95% 会在问题列表里出现 SYSTEM_PROBLEM。
            state.capacity?.let { cap ->
                item { SectionTitle("历史容量") }
                item {
                    Card { Column(Modifier.padding(10.dp)) {
                        Text(
                            "已结束场次 ${cap.closedSessions}" +
                                (cap.limit?.let { " / $it（${cap.percentText}）" } ?: "（不限制）"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (cap.warning) {
                            Text(
                                "已达容量上限的 ${cap.percentText}，继续增长将自动删除最旧的已结束场次",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } }
                }
            }

            // 恢复检查会话（原规范 177）：此前 recovery_session 表恒为空。
            item { SectionTitle("恢复检查会话（最近 ${state.recoverySessions.size}）") }
            if (state.recoverySessions.isEmpty()) item { EmptyHint("暂无恢复会话") }
            items(state.recoverySessions.size) { i ->
                val r = state.recoverySessions[i]
                Card { Column(Modifier.padding(10.dp)) {
                    Text(
                        "${recoveryStatusText(r.status.name)}（代际 ${r.monitorGeneration}）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "${formatTime(r.startedAt)}" +
                            (r.finishedAt?.let { " → ${formatTime(it)}" } ?: " → 进行中"),
                        style = MaterialTheme.typography.labelSmall
                    )
                } }
            }

            // 崩溃记录（原规范 81/225）：此前 crash_record 表从无读写。
            item { SectionTitle("崩溃记录（最近 ${state.crashes.size}）") }
            if (state.crashes.isEmpty()) item { EmptyHint("暂无崩溃记录") }
            items(state.crashes.size) { i ->
                val c = state.crashes[i]
                Card { Column(Modifier.padding(10.dp)) {
                    Text(c.exitReason, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${formatTime(c.occurredAt)}" +
                            (if (c.recoveredAt != null) " · 已恢复" else " · 未恢复"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (c.recoveredAt == null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

// ---- 枚举/内部 key → 用户可读文案 ----

private fun gapReasonText(reason: String): String = when (reason) {
    "NETWORK_UNAVAILABLE" -> "网络不可用"
    "API_UNAVAILABLE" -> "接口不可用"
    "DATABASE_BLOCKED" -> "数据库阻塞"
    "MONITORING_STOPPED" -> "监控停止"
    "RUNTIME_CRASH" -> "进程异常"
    else -> "未知原因"
}

private fun healthStatusText(status: String?): String = when (status) {
    null -> "—"
    "HEALTHY" -> "正常"
    "DEGRADED" -> "降级"
    "RECOVERING" -> "恢复中"
    "BLOCKED" -> "阻塞"
    "STOPPED" -> "已停止"
    else -> status
}

private fun recoveryStatusText(status: String): String = when (status) {
    "REQUESTED" -> "已请求"
    "RUNNING" -> "进行中"
    "COMPLETED" -> "已完成"
    "FAILED" -> "失败"
    "TIMEOUT" -> "超时"
    "CANCELLED" -> "已取消"
    else -> status
}

private fun errorText(code: String): String = when (code) {
    "NETWORK_UNAVAILABLE" -> "网络不可用"
    "NETWORK_TIMEOUT" -> "网络超时"
    "API_REJECTED" -> "接口拒绝"
    // 限流与"网络不可用"是两件事（缺陷 2）：服务端在正常工作，只是要求我们退避。
    "RATE_LIMITED" -> "接口被限流"
    "API_INVALID_RESPONSE" -> "接口返回异常"
    "DATABASE_ERROR" -> "数据库错误"
    "PERMISSION_DENIED" -> "权限被拒绝"
    "NOTIFICATION_UNAVAILABLE" -> "通知不可用"
    "BACKGROUND_EXECUTION_RESTRICTED" -> "后台执行受限"
    "CONFIG_INVALID" -> "配置无效"
    "RESTORE_CONFLICT" -> "恢复冲突"
    "RESTORE_INCOMPATIBLE" -> "恢复不兼容"
    "EXPORT_FAILED" -> "导出失败"
    else -> "未知错误"
}

/** problemKey 形如 `MONITORING:NETWORK_UNAVAILABLE:SYSTEM:<episodeId>`，只展示前两段。 */
private fun problemText(problemKey: String): String {
    val parts = problemKey.split(':')
    val component = when (parts.getOrNull(0)) {
        "MONITORING" -> "监控"
        else -> parts.getOrNull(0) ?: problemKey
    }
    val type = when (parts.getOrNull(1)) {
        "NETWORK_UNAVAILABLE" -> "网络不可用"
        // 系统级盲区现在按**主导原因**选 GapReason（缺陷 2），接口侧事故会落成 API_UNAVAILABLE。
        "API_UNAVAILABLE" -> "接口不可用"
        "UNKNOWN" -> "未知问题"
        else -> parts.getOrNull(1) ?: ""
    }
    return if (type.isBlank()) component else "$component · $type"
}
