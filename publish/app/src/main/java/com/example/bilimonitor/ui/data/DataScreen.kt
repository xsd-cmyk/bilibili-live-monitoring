package com.example.bilimonitor.ui.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.local.RestoreConflictAction
import com.example.bilimonitor.data.repository.BackupRepository
import com.example.bilimonitor.data.repository.MaintenanceRepository
import com.example.bilimonitor.ui.common.FloatingTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DataUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val precheck: BackupRepository.PrecheckResult.Ready? = null,
    val backupContent: String? = null,
    /** 正在恢复的文件名（来自 Download 或用户挑选的文件）。 */
    val backupFileName: String? = null,
    val restoreSummary: String? = null
)

@HiltViewModel
class DataViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    val maintenanceRepository: MaintenanceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state

    val capacityMode = maintenanceRepository.capacityMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceRepository.MODE_UNLIMITED)
    val maxRecords = maintenanceRepository.maxRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1000)

    /**
     * 长流程的统一入口：**先把 busy 置位再进协程**，并用 try/catch/finally 保证一定复位。
     *
     * 原来的写法是 `launch { busy = true; ...; busy = false }`，有两个漏洞：
     *  1. `busy = true` 在协程里才执行，两次点击之间来得及再进一次（重复导出/重复预检）；
     *  2. 没有任何 catch —— 只要中间抛异常（预检尾部的 insertRun/insertConflicts、
     *     同步 IO 的 SecurityException 等），`busy` 就永远是 true，
     *     整页按钮（导出/导入/恢复/清理）全部永久置灰，用户只能重启 App。
     * 现在异常会转成页面上可见的提示，busy 在 finally 里无条件复位。
     */
    private fun runBusy(task: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "$task：${e.message ?: "未知错误"}")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun exportBackup() = runBusy("备份失败") {
        val result = backupRepository.exportBackup()
        _state.value = _state.value.copy(message = result.fold(
            onSuccess = { "备份已导出到 Download/$it" },
            onFailure = { "备份失败：${it.message}" }
        ))
    }

    // 直播历史导出已按用户要求移出设置：入口改到「主播卡片」与「直播历史一级界面」
    // （导出需要选择主播 + 时间范围，放在设置里既拿不到主播上下文，也只能按"最近 N 天"猜范围）。

    fun precheckLatestBackup() = runBusy("预检失败") {
        _state.value = _state.value.copy(restoreSummary = null)
        val latest = backupRepository.readLatestBackup()
        if (latest == null) {
            _state.value = _state.value.copy(message = "Download 中没有找到备份文件（先执行导出）")
            return@runBusy
        }
        precheckContent(latest.first, latest.second)
    }

    /** 用户用系统文件选择器挑中的配置文件（可以是任意目录/网盘/外置存储）。 */
    fun precheckPickedFile(uri: android.net.Uri) = runBusy("预检失败") {
        _state.value = _state.value.copy(restoreSummary = null)
        val picked = backupRepository.readPickedBackup(uri)
        if (picked == null) {
            _state.value = _state.value.copy(message = "无法读取所选文件（可能已被移动或没有读取权限）")
            return@runBusy
        }
        precheckContent(picked.first, picked.second)
    }

    private suspend fun precheckContent(name: String, content: String) {
        when (val r = backupRepository.precheck(content)) {
            is BackupRepository.PrecheckResult.Ready ->
                _state.value = _state.value.copy(
                    precheck = r, backupContent = content, backupFileName = name
                )
            is BackupRepository.PrecheckResult.Rejected ->
                _state.value = _state.value.copy(message = "预检未通过：${r.reason}（$name）")
        }
    }

    fun decideAll(action: RestoreConflictAction) {
        val runId = _state.value.precheck?.restoreRunId ?: return
        viewModelScope.launch {
            backupRepository.decideAll(runId, action)
            val conflicts = backupRepository.listConflicts(runId)
            _state.value = _state.value.copy(
                precheck = _state.value.precheck?.copy(conflicts = conflicts)
            )
        }
    }

    fun applyRestore() {
        val pre = _state.value.precheck ?: return
        val content = _state.value.backupContent ?: return
        // 走统一的 runBusy：busy 在进协程前就置位（重入守卫），
        // 且无论成功/失败/抛异常都会在 finally 复位
        runBusy("恢复失败") {
            val summary = try {
                backupRepository.applyRestore(pre.restoreRunId, content)
            } catch (e: Exception) {
                // ★ 失败必须留在页面上：原先只写 message，而 message 会在 4 秒后自动清除
                //   （见下面 LaunchedEffect），用户根本来不及看清"哪一步失败了"。
                //   restoreSummary 是常驻卡片，改用它承载失败原因。
                _state.value = _state.value.copy(
                    restoreSummary = "恢复失败：${e.message ?: "未知错误"}"
                )
                return@runBusy
            }
            // 摘要必须把"没进去的"和"更新过的"都报出来：
            //   只报成功数字时，用户没有任何办法判断"我原来的东西少了没有"；
            //   原先只报跳过场次，跳过的主播/事件/标题/分区与冲突数都被淹掉了。
            val skippedParts = listOf(
                "主播" to (summary.skippedCounts["streamers"] ?: 0),
                "场次" to (summary.skippedCounts["sessions"] ?: 0),
                "事件" to (summary.skippedCounts["events"] ?: 0),
                "标题" to (summary.skippedCounts["titles"] ?: 0),
                "分区" to (summary.skippedCounts["areas"] ?: 0)
            ).filter { it.second > 0L }
            val summaryText = buildString {
                append("恢复完成：新增主播 ${summary.restoredCounts["streamers"] ?: 0}")
                append("、更新主播 ${summary.restoredCounts["updated"] ?: 0}")
                append("、场次 ${summary.restoredCounts["sessions"] ?: 0}")
                append("、事件 ${summary.restoredCounts["events"] ?: 0}")
                // 归属行单独报数：这两行数据以前**根本不恢复**（分组归属）或者恢复了也不报
                //（标签归属），用户没有任何办法判断"我原来的标签/分组还在不在"。
                append("、标签新建 ${summary.restoredCounts["tagsNew"] ?: 0}")
                append("（已存在 ${summary.restoredCounts["tagsExisting"] ?: 0}）")
                append("、分组 ${summary.restoredCounts["groups"] ?: 0}")
                append("、标签归属 ${summary.restoredCounts["tagRefs"] ?: 0}")
                append("、分组归属 ${summary.restoredCounts["groupRefs"] ?: 0}")
                append("、设置项 ${summary.restoredCounts["settings"] ?: 0}")
                if (skippedParts.isNotEmpty()) {
                    append("；跳过 ")
                    append(skippedParts.joinToString("、") { "${it.first} ${it.second}" })
                }
                if (summary.conflictCounts.isNotEmpty()) {
                    append("；冲突 ")
                    append(summary.conflictCounts.entries.joinToString("、") { "${it.key} ${it.value}" })
                }
                if (summary.warnings.isNotEmpty()) {
                    append("；警告：")
                    append(summary.warnings.joinToString("；"))
                }
            }
            _state.value = _state.value.copy(
                precheck = null, backupContent = null, backupFileName = null,
                restoreSummary = summaryText
            )
        }
    }

    fun cancelRestore() {
        val runId = _state.value.precheck?.restoreRunId ?: return
        viewModelScope.launch { backupRepository.cancelRun(runId) }
        _state.value = _state.value.copy(precheck = null)
    }

    fun runCleanup() = runBusy("清理失败") {
        val r = maintenanceRepository.runCleanup()
        _state.value = _state.value.copy(message = r.fold(
            onSuccess = {
                "清理完成：场次 ${it.sessionsDeleted}、通知记录 ${it.outboxDeleted}、" +
                    "通知历史 ${it.historyDeleted}、投递记录 ${it.attemptsDeleted}、" +
                    "聚合 ${it.aggregatesDeleted}、日志 ${it.monitoringErrorsDeleted + it.appErrorsDeleted}"
            },
            onFailure = { "清理失败：${it.message}" }
        ))
    }

    fun setCapacityUnlimited() {
        viewModelScope.launch { maintenanceRepository.setCapacity(MaintenanceRepository.MODE_UNLIMITED, 1000) }
    }

    fun setCapacityMaxRecords(max: Int) {
        viewModelScope.launch { maintenanceRepository.setCapacity(MaintenanceRepository.MODE_MAX_RECORDS, max) }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

// FlowRow 在 foundation 1.7.6 仍是 @ExperimentalLayoutApi —— 这个 OptIn 是**必要**的
// （与本次删掉的那些"只为已不存在的 TopAppBar 而留"的 OptIn 不同）：
// 窄屏（360dp 屏的卡片内可用宽度约 300dp）一行放不下三个 6 字按钮，
// 不换行的 Row 会把第三个裁掉，FlowRow 才会按可用宽度自动折行。
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataScreen(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    // collectAsStateWithLifecycle：进后台后自动解除订阅，
    // 否则 ViewModel 里 WhileSubscribed(5s) 的 Room 管线会在后台一直跑
    val state by viewModel.state.collectAsStateWithLifecycle()
    val capacityMode by viewModel.capacityMode.collectAsStateWithLifecycle()
    val maxRecords by viewModel.maxRecords.collectAsStateWithLifecycle()
    var showCapacityDialog by remember { mutableStateOf(false) }
    // 配置文件导入：系统文件选择器（SAF），可挑任意目录/网盘/外置存储里的 json
    val pickBackupFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.precheckPickedFile(uri) }

    LaunchedEffect(state.message) {
        if (state.message != null) { delay(4000); viewModel.clearMessage() }
    }

    Scaffold(
        topBar = { FloatingTopBar(title = { Text("数据管理") }, navigationIcon = {
            // 与全项目其它页面统一：返回用箭头图标，而不是中文「返回」文字按钮
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.busy) {
                Card { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).height(20.dp))
                    Text("正在操作（已进入维护模式，监控暂停）…")
                } }
            }

            state.message?.let {
                Card { Text(it, Modifier.fillMaxWidth().padding(14.dp)) }
            }
            state.restoreSummary?.let {
                Card { Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.primary) }
            }

            // 恢复流程（预检 → 冲突决策 → 应用）
            state.precheck?.let { pre ->
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("恢复预检通过", fontWeight = FontWeight.SemiBold)
                        state.backupFileName?.let {
                            Text("文件：$it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "备份时间：${java.time.Instant.ofEpochMilli(pre.manifest.createdAt)}\n" +
                                "包含主播 ${pre.manifest.recordCounts["streamers"]}、场次 ${pre.manifest.recordCounts["sessions"]}\n" +
                                "将新增主播 ${pre.newStreamers}，可恢复场次 ${pre.sessionsToRestore}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (pre.conflicts.isEmpty()) {
                            Text("无身份冲突", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("身份冲突 ${pre.conflicts.size} 条：", fontWeight = FontWeight.Medium)
                            pre.conflicts.forEach { c ->
                                val desc = when (c.type.name) {
                                    "STABLE_ID_DIFFERENT_UID_SAME" -> "同 stableId 不同 UID"
                                    "STABLE_ID_SAME_UID_DIFFERENT" -> "同 UID 不同 stableId"
                                    else -> c.type.name
                                }
                                Text(
                                    "· ${c.backupStableId?.takeLast(8)}…（$desc）当前决策：${c.decision ?: "未决策"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            // 三个 6 字按钮在 360dp 屏的卡片里（可用宽度约 300dp）一行放不下，
                            // 不换行的 Row 会把最后一个裁掉；FlowRow 按可用宽度自动折行。
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.decideAll(RestoreConflictAction.USE_BACKUP) }) { Text("全部采用备份") }
                                OutlinedButton(onClick = { viewModel.decideAll(RestoreConflictAction.KEEP_LOCAL) }) { Text("全部保留本地") }
                                OutlinedButton(onClick = { viewModel.decideAll(RestoreConflictAction.SKIP) }) { Text("全部跳过") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 「执行恢复」与同一页其它长流程按钮一致：进行中即禁用。
                            // 恢复会往库里写大量记录，重复触发只会互相干扰。
                            Button(onClick = { viewModel.applyRestore() }, enabled = !state.busy) {
                                Text("执行恢复")
                            }
                            OutlinedButton(onClick = { viewModel.cancelRestore() }, enabled = !state.busy) {
                                Text("取消")
                            }
                        }
                    }
                }
            }

            SettingCard("配置文件（备份 / 恢复）") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.exportBackup() }, enabled = !state.busy) { Text("导出配置文件") }
                    OutlinedButton(onClick = { pickBackupFile.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        enabled = !state.busy) { Text("选择文件导入") }
                }
                OutlinedButton(onClick = { viewModel.precheckLatestBackup() }, enabled = !state.busy) {
                    Text("导入 Download 里最新的备份")
                }
                Text(
                    "配置文件是一个 JSON 文件，包含：" +
                        "主播卡片（含标签/分组/单独监控策略）、直播历史（场次/事件/标题/人工修正）、" +
                        "以及应用设置（监控参数、免打扰时段、历史容量、导入上限）。\n" +
                        "不含登录凭证、运行时锁与设备绑定项（后台保活方式等）。\n" +
                        "也**不含通知历史与投递记录**（那些只在本机，保留 90 天 / 30 天，" +
                        "需要排查时用诊断包导出）。\n" +
                        "统计由场次实时算出，恢复后自动重算；范围内以文件为权威，范围外本地数据不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingCard("历史容量") {
                Text(
                    when (capacityMode) {
                        MaintenanceRepository.MODE_UNLIMITED -> "当前：不限制"
                        else -> "当前：最多保留 $maxRecords 条已结束场次"
                    },
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showCapacityDialog = true }) { Text("修改容量") }
                    OutlinedButton(onClick = { viewModel.runCleanup() }, enabled = !state.busy) { Text("立即清理") }
                }
                Text(
                    "清理只删除最旧的已结束场次；通知 Outbox 保留 30 天、通知历史保留 90 天。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCapacityDialog) {
        var selected by remember { mutableStateOf(capacityMode) }
        var max by remember { mutableStateOf(maxRecords) }
        AlertDialog(
            onDismissRequest = { showCapacityDialog = false },
            title = { Text("历史容量") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected == MaintenanceRepository.MODE_UNLIMITED,
                            onClick = { selected = MaintenanceRepository.MODE_UNLIMITED }
                        )
                        Text("不限制")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected == MaintenanceRepository.MODE_MAX_RECORDS,
                            onClick = { selected = MaintenanceRepository.MODE_MAX_RECORDS }
                        )
                        Text("最多保留：")
                    }
                    if (selected == MaintenanceRepository.MODE_MAX_RECORDS) {
                        // 四个预设按钮同理：窄屏/大字体下会挤出去，交给 FlowRow 折行
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(100, 500, 1000, 5000).forEach { n ->
                                OutlinedButton(onClick = { max = n },
                                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (max == n) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )) { Text("$n") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selected == MaintenanceRepository.MODE_MAX_RECORDS) viewModel.setCapacityMaxRecords(max)
                    else viewModel.setCapacityUnlimited()
                    showCapacityDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showCapacityDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
