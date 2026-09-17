package com.example.bilimonitor.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.bilimonitor.data.local.dao.LiveSessionDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.repository.AppearanceSettingsRepository
import com.example.bilimonitor.data.repository.SessionSortOrder
import com.example.bilimonitor.data.repository.StreamerStatsSummary
import com.example.bilimonitor.ui.common.ExportCandidate
import com.example.bilimonitor.ui.common.ExportSelectDialog
import com.example.bilimonitor.ui.common.FloatingSearchField
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.HistoryFilter
import com.example.bilimonitor.ui.common.SessionSortSelector
import com.example.bilimonitor.ui.common.TimeRangeDialog
import com.example.bilimonitor.ui.common.recentSessionText
import com.example.bilimonitor.ui.common.sortedBySessionSortOrder
import com.example.bilimonitor.ui.home.formatDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 列表底部内边距 —— 由浮层 Dock 的**实际占位**推导，不是随手取的数：
 *  - Dock 本体 ≈72dp：图标 24 + 上下 padding 3+3 + 标签行高 16 + 间距 2 + 行/列内边距各 6；
 *  - 再加 26dp 悬浮外边距（`DockNavigationBar` 外层 Box 的 vertical padding，见 AppNavHost.kt）。
 * 遮挡带合计 ≈98dp，取 96dp（普通导航样式下 NavigationBar 约 80dp，96dp 也够）。
 * 只用在**有底栏的一级页面**上：Dock 只在这 4 个路由渲染（AppNavHost 的 showBottomBar），
 * 二级页面没有浮层，保持原有间距即可。
 */
private val LIST_BOTTOM_INSET = 136.dp

data class StatsListUiState(
    val loading: Boolean = true,
    val query: String = "",
    val summaries: List<StreamerStatsSummary> = emptyList(),
    val message: String? = null,
    /** 当前排序方式（用户四选一，默认第 3 种）：下拉入口用它标出选中项 */
    val sessionSortOrder: SessionSortOrder = SessionSortOrder.DEFAULT,
    val exportSelecting: Boolean = false,
    val exportSelected: Set<Long> = emptySet(),
    val exportQuery: String = "",
    val exportRangeFor: List<Long>? = null
)

/** 统计·第 1 级：主播卡片列表（只搜主播名），点进第 2 级看该主播的统计。 */
@HiltViewModel
class StatsListViewModel @Inject constructor(
    streamerDao: StreamerDao,
    liveSessionDao: LiveSessionDao,
    private val exportRepository: com.example.bilimonitor.data.repository.ExportRepository,
    private val appearanceRepository: AppearanceSettingsRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    /**
     * 列表排序（用户四选一，默认第 3 种＝"按下播时间 + 直播中置顶"）。
     *
     * 与直播历史一级页读的是**同一个键**：用户挑的是"这两张表怎么看"，
     * 一页一个顺序会让同一个主播在两页的名次对不上（详见 ui/common/SessionSort.kt 的说明）。
     *
     * 初值直接给 [SessionSortOrder.DEFAULT]（不等读盘）、再加 `distinctUntilChanged`：
     * 理由与 HistoryListViewModel 同 —— 避免先渲染一种顺序再跳，也避免外观偏好里
     * 拖一次玻璃滑块就把上千条场次重排一遍。
     */
    private val sortOrder: StateFlow<SessionSortOrder> = appearanceRepository.flow
        .map { it.sessionSortOrder }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionSortOrder.DEFAULT)

    // ===== 导出统计图表（规则与历史导出完全一致）=====
    private val exportSelecting = MutableStateFlow(false)
    private val exportSelected = MutableStateFlow<Set<Long>>(emptySet())
    private val exportQuery = MutableStateFlow("")
    private val exportRangeFor = MutableStateFlow<List<Long>?>(null)
    private val message = MutableStateFlow<String?>(null)

    /**
     * 导出进行中。与直播历史列表同样的理由：导出是长流程（快照 → HTML/SVG → 写 Download），
     * 期间重复点击会写出两个文件、提示文案互相覆盖，所以既拦重复调用也把入口置灰。
     */
    private val exportRunning = MutableStateFlow(false)
    val exportRunningFlow: StateFlow<Boolean> = exportRunning

    fun startExportSelection() {
        exportSelected.value = emptySet()
        exportQuery.value = ""
        exportSelecting.value = true
    }

    fun setExportQuery(q: String) { exportQuery.value = q }
    fun toggleExportSelection(id: Long) {
        val cur = exportSelected.value
        exportSelected.value = if (id in cur) cur - id else cur + id
    }
    /**
     * 「全选」：选中对话框里**当前可见**的那批主播（调用方传的是已按搜索词过滤后的列表，
     * 不是整份列表 —— 否则搜完再点全选会把没显示的主播也勾上）。
     *
     * 用并集而不是整体替换：用户可能先勾几位、再搜索着补几位，
     * 直接覆盖会把他之前勾的（此刻不可见）静默清掉。
     */
    fun selectAllForExport(ids: List<Long>) { exportSelected.value = exportSelected.value + ids }
    fun clearExportSelection() { exportSelected.value = emptySet() }
    fun cancelExport() {
        exportSelecting.value = false
        exportSelected.value = emptySet()
        exportRangeFor.value = null
    }
    fun confirmExportSelection() {
        val ids = exportSelected.value.toList()
        if (ids.isEmpty()) return
        exportSelecting.value = false
        exportRangeFor.value = ids
    }
    fun confirmExportRange(filter: com.example.bilimonitor.ui.common.HistoryFilter) {
        val ids = exportRangeFor.value ?: return
        if (exportRunning.value) return
        exportRangeFor.value = null
        exportRunning.value = true
        viewModelScope.launch {
            try {
                message.value = "正在导出…"
                message.value = com.example.bilimonitor.ui.common.performStatsExport(
                    exportRepository, ids, filter
                )
            } finally {
                // 失败/取消也要复位，否则入口会永久置灰
                exportRunning.value = false
            }
        }
    }
    fun clearMessage() { message.value = null }

    // 显式标注可空类型：stateIn 的初值传 null 时靠推断得到 `List<…>?`，
    // 写清楚比让读者去推更省事（下面的 loading 判断也依赖这个可空性）。
    private val summaries: StateFlow<List<StreamerStatsSummary>?> = combine(
        liveSessionDao.observeRecent(1000),
        streamerDao.observeActive(),
        query,
        sortOrder
    ) { sessions, streamers, q, order ->
        // 场次计数 / 总时长只认**已结束**的场次（进行中的场次没有最终时长，计进去就是假数据）；
        // 但「最近」必须看得到进行中的那一场，否则正在直播的主播会退回显示上一场。
        val closedByStreamer = sessions
            .filter { it.endTime != null }
            .groupBy { it.streamerId }
        val allByStreamer = sessions.groupBy { it.streamerId }
        // 每个主播"最近一次**真正结束**直播"的时刻（排序选项 4 的键）：直接复用上面那份
        // "只含已结束场次"的分组，取下播时刻的最大值。
        // 不能用「最新一场」的 endTime 代替：正在直播时它就是 null，于是"最近一场已结束的场次"
        // 才是唯一能回答"这个主播上一次下播是什么时候"的量。用最大值而不是"最新一场的 endTime"，
        // 也是为了容忍人工补录 / 异常中断留下的交叠场次。
        // ★ 这里用局部 Map 而不是给 StreamerStatsSummary 加字段：那个数据类定义在
        //   StatsSeriesRepository.kt，本次改动范围只含本文件。口径与历史页的 lastClosedEndAt 完全一致。
        val lastClosedEndByStreamer: Map<Long, Long?> = closedByStreamer.mapValues { (_, list) ->
            list.mapNotNull { it.endTime }.maxOrNull()
        }
        streamers
            .filter { q.isBlank() || it.name.contains(q.trim()) }
            .map { s ->
                val closed = closedByStreamer[s.id].orEmpty()
                // 「最新一场」的判定与直播历史一级页同一份规则：开播时间最晚的那一场（含进行中），
                // 显示它的**下播**时间；两页只要有一页换了算法，同一个主播就会出现两个"最近"。
                val latest = allByStreamer[s.id].orEmpty().maxByOrNull { it.startTime ?: it.createdAt }
                StreamerStatsSummary(
                    streamerId = s.id,
                    name = s.name,
                    avatarUrl = s.avatarUrl,
                    sessionCount = closed.size,
                    totalSeconds = closed.sumOf { it.durationSeconds ?: 0L },
                    latestSessionStartAt = latest?.let { it.startTime ?: it.createdAt },
                    // 下播时间可能为 null（还在直播）：交给展示层说"直播中"，界面不会出现 null/空白
                    latestSessionEndAt = latest?.endTime
                )
            }
            // ★ 本页原先"一律按总时长从大到小"的排序**改了**，这是本次需求的一部分：
            //   用户要求列表排序做成四选一，且两个列表页口径必须一致 —— 若本页继续按总时长排，
            //   用户在历史页挑的排序到了统计页就不作数，同一个主播在两页的名次也对不上。
            //   原来的"按总时长"没有被丢掉，而是**降级为组内兜底键**（tieBreak）：只有在
            //   下播/开播时刻完全相同的行之间才轮到它，等于"新口径优先、旧口径不再主导"。
            .sortedBySessionSortOrder(
                order = order,
                startAt = { it.latestSessionStartAt },
                endAt = { it.latestSessionEndAt },
                // ★ 必须真的传（与历史页同一个键）：漏传（沿用默认的恒 null）时，正在直播的行
                //   在选项 4 下会与选项 1 一样沉底 —— 用户切到新选项看不出任何变化。
                lastClosedEndAt = { lastClosedEndByStreamer[it.streamerId] },
                tieBreak = { it.totalSeconds }
            )
    // 初值用 null 而不是 emptyList()：界面要区分"还没查完"与"确实没有记录"，
    // 否则进页面会先闪一下空态文案再刷出列表（loading 字段此前恒为 false，是个死字段）。
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<StatsListUiState> = combine(
        summaries,
        query,
        sortOrder,
        combine(exportSelecting, exportSelected, exportQuery, exportRangeFor) { a, b, c, d ->
            ExportState(a, b, c, d)
        },
        message
    ) { list, q, order, exp, msg ->
        StatsListUiState(
            loading = list == null, query = q, summaries = list.orEmpty(), message = msg,
            sessionSortOrder = order,
            exportSelecting = exp.selecting,
            exportSelected = exp.selected,
            exportQuery = exp.query,
            exportRangeFor = exp.rangeFor
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsListUiState())

    private data class ExportState(
        val selecting: Boolean,
        val selected: Set<Long>,
        val query: String,
        val rangeFor: List<Long>?
    )

    fun setQuery(q: String) {
        query.value = q
    }

    /** 切换列表排序：与直播历史一级页同一个键，两页同时生效（理由见 HistoryListViewModel）。 */
    fun setSessionSortOrder(order: SessionSortOrder) {
        viewModelScope.launch { appearanceRepository.setSessionSortOrder(order) }
    }
}

@Composable
fun StatsListScreen(
    onOpenStreamer: (Long) -> Unit,
    viewModel: StatsListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportRunning by viewModel.exportRunningFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { Text("统计") },
                // 排序入口放在左侧、与右侧「导出」呈镜像（用户要求）：
                // 顶栏两个按钮分居两侧，标题的可用宽度不再被两个按钮一起挤压。
                navigationIcon = {
                    // 与直播历史一级页同一个入口、同一个键：两页的排序必须是一份口径，
                    // 用户在哪一页切都算数（组件见 ui/common/SessionSort.kt）。
                    SessionSortSelector(
                        current = state.sessionSortOrder,
                        onSelect = { viewModel.setSessionSortOrder(it) }
                    )
                },
                actions = {
                    // 导出进行中禁用入口：避免重复触发写出多个文件
                    TextButton(
                        onClick = { viewModel.startExportSelection() },
                        enabled = !exportRunning
                    ) { Text("导出") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            com.example.bilimonitor.ui.common.MessageBanner(
                message = state.message,
                onDismiss = { viewModel.clearMessage() }
            )
            FloatingSearchField(
                value = state.query,
                onValueChange = { viewModel.setQuery(it) },
                placeholder = "搜索主播名字"
            )
            if (state.loading && state.summaries.isEmpty()) {
                // 首帧：还在查库，转圈而不是先显示"还没有统计数据"
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { androidx.compose.material3.CircularProgressIndicator() }
            } else if (state.summaries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (state.query.isBlank()) "还没有统计数据\n确认开播后会自动记录"
                        else "没有匹配的主播",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Dock 已不在 Scaffold 的 bottomBar 槽里，内容区不会自动避让：
                // 没有这段底部内边距，列表最后一张卡片会被浮层永久压住（滚到底也露不全）。
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LIST_BOTTOM_INSET)
                ) {
                    items(state.summaries, key = { it.streamerId }) { s ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { onOpenStreamer(s.streamerId) }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = s.avatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        s.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        recentSessionText(s.latestSessionStartAt, s.latestSessionEndAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${s.sessionCount} 场", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        formatDuration(s.totalSeconds),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 导出统计图表：选择一位或多位主播（与直播历史导出同一套对话框与规则）
    if (state.exportSelecting) {
        ExportSelectDialog(
            title = "选择要导出统计的主播",
            streamers = state.summaries.map { ExportCandidate(it.streamerId, it.name) },
            selected = state.exportSelected,
            query = state.exportQuery,
            onQuery = { viewModel.setExportQuery(it) },
            onToggle = { viewModel.toggleExportSelection(it) },
            // 传进来的是**对话框里当前可见（已按 query 过滤）**的那批主播：
            // 全选必须与用户看到的列表一致，否则"先搜索、再全选"会把没显示的主播也勾上。
            onSelectAll = { visible -> viewModel.selectAllForExport(visible.map { it.id }) },
            onClearAll = { viewModel.clearExportSelection() },
            onConfirm = { viewModel.confirmExportSelection() },
            onDismiss = { viewModel.cancelExport() }
        )
    }

    state.exportRangeFor?.let { ids ->
        val names = state.summaries.filter { it.streamerId in ids }.map { it.name }
        TimeRangeDialog(
            current = HistoryFilter(),
            title = if (ids.size == 1) "导出「${names.firstOrNull() ?: "主播"}」的统计图表"
            else "导出 ${ids.size} 位主播的统计图表（合并）",
            confirmLabel = "导出",
            allowAll = true,
            onApply = { viewModel.confirmExportRange(it) },
            onDismiss = { viewModel.cancelExport() }
        )
    }
}