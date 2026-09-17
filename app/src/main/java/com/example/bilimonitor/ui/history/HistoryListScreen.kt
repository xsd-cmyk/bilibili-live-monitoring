package com.example.bilimonitor.ui.history

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
import com.example.bilimonitor.data.repository.ExportRepository
import com.example.bilimonitor.data.repository.SessionSortOrder
import com.example.bilimonitor.ui.common.ExportCandidate
import com.example.bilimonitor.ui.common.ExportSelectDialog
import com.example.bilimonitor.ui.common.FloatingSearchField
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.HistoryFilter
import com.example.bilimonitor.ui.common.SessionSortSelector
import com.example.bilimonitor.ui.common.TimeRangeDialog
import com.example.bilimonitor.ui.common.performHistoryExport
import com.example.bilimonitor.ui.common.recentSessionText
import com.example.bilimonitor.ui.common.sortedBySessionSortOrder
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

data class StreamerHistorySummary(
    val streamerId: Long,
    val name: String,
    val avatarUrl: String?,
    /** 最新一场的**开播**时刻：排序键之一（选项 2「按开播时间」是它的主场；选项 1/3 用它给同刻的行兜底）。 */
    val latestSessionStartAt: Long?,
    /** 同一场的**下播**时刻，界面「最近：…」显示的就是它；null = 这一场还在直播中（选项 1/3 据此判定在播）。 */
    val latestSessionEndAt: Long?,
    /**
     * 该主播**最近一场"有下播时间"的场次**的下播时刻（null = 从没结束过直播）。
     *
     * 与 [latestSessionEndAt] 的区别：后者只看「最新一场」，而最新一场可能**正在进行**（endTime 为 null），
     * 此时这里退回到上一场 —— 也就是"这个主播最近一次真正下播是什么时候"。
     * 只有选项 4（按下播时间、在播的按上一场）读它，规则见 ui/common/SessionSort.kt。
     */
    val lastClosedEndAt: Long?
)

data class HistoryListUiState(
    val loading: Boolean = true,
    val query: String = "",
    val summaries: List<StreamerHistorySummary> = emptyList(),
    val message: String? = null,
    /** 当前排序方式（用户四选一，默认第 3 种）：下拉入口用它标出选中项 */
    val sessionSortOrder: SessionSortOrder = SessionSortOrder.DEFAULT,
    /** 导出：已勾选的主播；非空表示正在选择 */
    val exportSelecting: Boolean = false,
    val exportSelected: Set<Long> = emptySet(),
    val exportQuery: String = "",
    /** 导出：已选定主播、等待选择时间范围（null 表示不在这一步） */
    val exportRangeFor: List<Long>? = null
)

/**
 * 直播历史·第 1 级：主播卡片列表（只搜主播名），点进第 2 级看该主播的场次。
 * 响应式：场次表（Room Flow）或主播表变化时自动刷新"最近"时间——修正/补录后立即生效。
 * 场次与时长统计归统计页，此卡片不再展示。
 *
 * 导出（用户定稿）：本页顶栏「导出」→ 勾选一位或多位主播（多位则合并成一份文件）
 * → 选择时间范围 → 写出到 Download。
 */
@HiltViewModel
class HistoryListViewModel @Inject constructor(
    private val streamerDao: StreamerDao,
    liveSessionDao: LiveSessionDao,
    private val exportRepository: ExportRepository,
    private val appearanceRepository: AppearanceSettingsRepository
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val exportSelecting = MutableStateFlow(false)
    private val exportSelected = MutableStateFlow<Set<Long>>(emptySet())
    private val exportQuery = MutableStateFlow("")
    private val exportRangeFor = MutableStateFlow<List<Long>?>(null)

    /**
     * 列表排序（用户四选一，默认第 3 种＝"按下播时间 + 直播中置顶"）。
     *
     * 初值直接给 [SessionSortOrder.DEFAULT]，不等 DataStore 读盘：读盘是异步的，
     * 拿 null 兜底会让列表先按一种顺序渲染、再跳成存档里的顺序（用户看到列表"抖一下"）。
     *
     * `distinctUntilChanged` 不是可有可无的优化：外观偏好里还有玻璃参数、背景图等十几个键，
     * 用户拖一次模糊滑块就会让整个 flow 重发；没有它，每拖一帧都要把上千条场次重新分组、排序。
     */
    private val sortOrder: StateFlow<SessionSortOrder> = appearanceRepository.flow
        .map { it.sessionSortOrder }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionSortOrder.DEFAULT)

    // 显式标注可空类型：stateIn 的初值传 null 时靠推断得到 `List<…>?`，
    // 写清楚比让读者去推更省事（下面的 loading 判断也依赖这个可空性）。
    private val summaries: StateFlow<List<StreamerHistorySummary>?> = combine(
        liveSessionDao.observeRecent(1000),
        streamerDao.observeActive(),
        query,
        sortOrder
    ) { sessions, streamers, q, order ->
        // 分组必须带上**进行中**的场次（endTime == null）：最新一场常常正是正在直播的那一场，
        // 把它整体过滤掉会让"最近"退回上一场（旧实现干脆显示"—"），与"最近"这个词自相矛盾。
        val grouped = sessions.groupBy { it.streamerId }
        streamers
            .filter { q.isBlank() || it.name.contains(q.trim()) }
            .map { s ->
                val list = grouped[s.id].orEmpty()
                // 「最新一场」= 开播时间最晚的那一场，键与 DAO 的 COALESCE(startTime, createdAt) 同源：
                // 同一主播的场次首尾相接、不重叠，"开播最晚"就是"最近的一场"；反过来按 endTime 取最大，
                // 在数据交叠（人工补录、异常中断留下的重叠场次）时会挑中一场更早的场次，
                // 而且进行中的场次 endTime 为 null，会被整场丢掉 —— 正在直播的主播就查无此场了。
                val latest = list.maxByOrNull { it.startTime ?: it.createdAt }
                // "最近一场已结束的场次"＝这一组里 endTime 非空的最大值，**不能**拿「最新一场」代替：
                // 正在直播时最新一场的 endTime 就是 null，根本取不到"上一次下播"。
                // 也不能靠"按 endTime 排序取首条"：人工补录 / 异常中断会留下交叠的场次，
                // 只有"下播时刻最大"才真的等于"这个主播最近一次结束直播"。
                val lastClosedEnd = list.mapNotNull { it.endTime }.maxOrNull()
                StreamerHistorySummary(
                    streamerId = s.id,
                    name = s.name,
                    avatarUrl = s.avatarUrl,
                    latestSessionStartAt = latest?.let { it.startTime ?: it.createdAt },
                    // 下播时间可能为 null（还在直播）：交给展示层说"直播中"，界面不会出现 null/空白
                    latestSessionEndAt = latest?.endTime,
                    lastClosedEndAt = lastClosedEnd
                )
            }
            // 排序方式由用户在顶栏「排序」里四选一（默认第 3 种：下播时间 + 直播中置顶）：
            // H13 那条"按开播时间从新到旧"的要求没有被删掉，它成了选项 2，不再是唯一口径。
            // 比较规则本身在 ui/common/SessionSort.kt —— 与统计页共用同一份，两页不会各排各的。
            .sortedBySessionSortOrder(
                order = order,
                startAt = { it.latestSessionStartAt },
                endAt = { it.latestSessionEndAt },
                // ★ 必须真的传：选项 4 靠它给"正在直播"的行定位。漏传（沿用默认的恒 null）时，
                //   在播的行在选项 4 下会与选项 1 一样沉底，用户切过去看不出任何变化。
                lastClosedEndAt = { it.lastClosedEndAt }
            )
    // 初值用 null 而不是 emptyList()：界面要区分"还没查完"与"确实没有记录"，
    // 否则进页面会先闪一下"还没有直播记录"再刷出列表（loading 字段此前恒为 false，是个死字段）。
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 导出选择对话框要的是非空列表（它自己也区分不了加载中），这里顺手兜底为空
    val allStreamers: StateFlow<List<StreamerHistorySummary>> = summaries
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<HistoryListUiState> = combine(
        summaries,
        query,
        message,
        sortOrder,
        combine(exportSelecting, exportSelected, exportQuery, exportRangeFor) { a, b, c, d ->
            ExportSelection(a, b, c, d)
        }
    ) { list, q, msg, order, exp ->
        HistoryListUiState(
            loading = list == null, query = q, summaries = list.orEmpty(), message = msg,
            sessionSortOrder = order,
            exportSelecting = exp.selecting,
            exportSelected = exp.selected,
            exportQuery = exp.query,
            exportRangeFor = exp.rangeFor
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryListUiState())

    private data class ExportSelection(
        val selecting: Boolean,
        val selected: Set<Long>,
        val query: String,
        val rangeFor: List<Long>?
    )

    fun setQuery(q: String) { query.value = q }
    fun clearMessage() { message.value = null }

    /**
     * 切换列表排序（顶栏「排序」下拉）。
     *
     * 走仓库写盘而不是本地 MutableStateFlow：两个列表页共用同一个键，改一处两页都变；
     * 只改内存的话，用户切到统计页会看到另一个顺序，退出重进又被打回原样。
     */
    fun setSessionSortOrder(order: SessionSortOrder) {
        viewModelScope.launch { appearanceRepository.setSessionSortOrder(order) }
    }

    // ===== 导出 =====

    fun startExportSelection() {
        exportSelected.value = emptySet()
        exportQuery.value = ""
        exportSelecting.value = true
    }

    fun setExportQuery(q: String) { exportQuery.value = q }

    fun toggleExportSelection(streamerId: Long) {
        val cur = exportSelected.value
        exportSelected.value = if (streamerId in cur) cur - streamerId else cur + streamerId
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

    /** 勾选完成 → 进入时间范围选择 */
    fun confirmExportSelection() {
        val ids = exportSelected.value.toList()
        if (ids.isEmpty()) return
        exportSelecting.value = false
        exportRangeFor.value = ids
    }

    /**
     * 导出进行中。
     *
     * 为什么要显式守卫：导出是"构建快照 → 生成 HTML → 写 Download"的长流程，
     * 期间用户完全可能再点一次「导出」（或从范围对话框快速双击确认）。
     * 两次导出会各自写一个文件、并且后一次的提示会覆盖前一次，用户看到的结果自相矛盾。
     * 这里既拦重复调用，也用来把入口按钮置灰。
     */
    private val exportRunning = MutableStateFlow(false)
    val exportRunningFlow: StateFlow<Boolean> = exportRunning

    /** 范围确定 → 真正导出（多主播合并成一个文件）。 */
    fun confirmExportRange(filter: HistoryFilter) {
        val ids = exportRangeFor.value ?: return
        if (exportRunning.value) return
        exportRangeFor.value = null
        exportRunning.value = true
        viewModelScope.launch {
            try {
                message.value = "正在导出…"
                message.value = performHistoryExport(exportRepository, ids, filter)
            } finally {
                // 失败/取消也要复位，否则入口会永久置灰
                exportRunning.value = false
            }
        }
    }
}

@Composable
fun HistoryListScreen(
    onOpenStreamer: (Long) -> Unit,
    viewModel: HistoryListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val allStreamers by viewModel.allStreamers.collectAsStateWithLifecycle()
    val exportRunning by viewModel.exportRunningFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { Text("直播历史") },
                // 排序入口放在左侧、与右侧「导出」呈镜像（用户要求）：
                // 顶栏两个按钮分居两侧，标题的可用宽度不再被两个按钮一起挤压。
                navigationIcon = {
                    // 排序入口放在本页顶栏而不是设置页：排序是"我此刻怎么看这张表"，
                    // 就地能切；且两页共用同一个组件与同一个键，选项与选中项不会各说各话。
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
                // 首帧：还在查库，转圈而不是先显示"还没有直播记录"
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
                        if (state.query.isBlank()) "还没有直播记录\n确认开播后会自动记录"
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
                                Text(
                                    "查看 ›",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.exportSelecting) {
        ExportSelectDialog(
            title = "选择要导出的主播",
            streamers = allStreamers.map { ExportCandidate(it.streamerId, it.name) },
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
        val names = allStreamers.filter { it.streamerId in ids }.map { it.name }
        TimeRangeDialog(
            current = HistoryFilter(),
            title = if (ids.size == 1) "导出「${names.firstOrNull() ?: "主播"}」的直播历史"
            else "导出 ${ids.size} 位主播的直播历史（合并）",
            confirmLabel = "导出",
            allowAll = true,
            onApply = { viewModel.confirmExportRange(it) },
            onDismiss = { viewModel.cancelExport() }
        )
    }
}