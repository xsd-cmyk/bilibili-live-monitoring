package com.example.bilimonitor.ui.history

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.local.dao.LiveSessionDao
import com.example.bilimonitor.data.local.dao.LiveSessionTitleDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.repository.CorrectionResult
import com.example.bilimonitor.data.repository.HistoryRepository
import com.example.bilimonitor.data.repository.ManualCorrection
import com.example.bilimonitor.ui.common.DateTimeFields
import com.example.bilimonitor.ui.common.DateTimeParts
import com.example.bilimonitor.ui.common.FloatingSearchField
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.HistoryFilter
import com.example.bilimonitor.ui.common.HistoryFilterType
import com.example.bilimonitor.ui.common.TimeRangeDialog
import com.example.bilimonitor.ui.common.bounds
import com.example.bilimonitor.ui.common.label
import com.example.bilimonitor.ui.home.formatDuration
import com.example.bilimonitor.ui.home.formatTime
import com.example.bilimonitor.ui.home.zoneOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

private val historyDateTimeFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 标题搜索的防抖窗口。
 *
 * 200ms 是"人手连续键入的间隔上限"与"人眼能察觉的延迟下限"之间的常用折中：
 * 再短就起不到合并作用，再长会让搜索显得迟钝。
 */
private const val TITLE_QUERY_DEBOUNCE_MS = 200L

private fun parseHistoryDt(text: String): Long? = runCatching {
    java.time.LocalDateTime.parse(text.trim(), historyDateTimeFmt)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

private fun formatHistoryDt(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault())
        .format(historyDateTimeFmt)

// 时间范围的语义与选择组件已提取到 ui.common，与导出共用同一套口径
// （否则"筛出来的范围"与"导出的范围"可能不一致，这类问题最难查）。

data class StreamerSessionRow(
    val session: LiveSessionEntity,
    val titles: List<String>,
    /** 该场次记录到的全部分区（可能换过多次，按观察时间排序）。 */
    val areas: List<String> = emptyList()
)

data class StreamerHistoryUiState(
    val streamer: StreamerEntity? = null,
    val rows: List<StreamerSessionRow> = emptyList(),
    val filter: HistoryFilter = HistoryFilter(),
    val titleQuery: String = "",
    val message: String? = null
)

/** 直播历史·第 2 级：单个主播的场次列表（标题搜索 + 日/周/月/年/自定义筛选）。 */
@HiltViewModel
class StreamerHistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val streamerDao: StreamerDao,
    private val liveSessionDao: LiveSessionDao,
    private val titleDao: LiveSessionTitleDao,
    private val areaDao: com.example.bilimonitor.data.local.dao.LiveSessionAreaDao,
    private val exportRepository: com.example.bilimonitor.data.repository.ExportRepository
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val titleQuery = MutableStateFlow("")
    private val filter = MutableStateFlow(HistoryFilter())

    /**
     * 已经建立过管线的场次 id（幂等守卫）。
     *
     * `LaunchedEffect(streamerId)` 在配置变更/重组后会再跑一次，而 ViewModel 仍然存活 ——
     * 没有这个守卫就会再起一条 Room 管线：两条管线并行写同一个 `_uiState`，
     * 旧管线还永不释放（写法与 `StreamerDetailScreen.load` 保持一致）。
     */
    private var loadedStreamerId: Long = 0

    /**
     * 标题搜索的**防抖**副本。
     *
     * 为什么要分成两份：输入框必须立刻回显用户敲的字，但**过滤**不该每个字符都重算 ——
     * 原先每敲一个字都要重跑整条管线（最多 600 场次的 groupBy + 排序 + 过滤），
     * 输入 "abc" 就是三次全量重算。这里 `titleQuery` 继续驱动输入框显示，
     * 防抖 200ms 后的副本才参与过滤，连续键入被合并成一次计算。
     *
     * 空串按 0ms 放行：`debounce(200)` 对**首个值**同样延迟发射，
     * 而外层 combine 要等这一路才能出第一帧 —— 进页面会先闪一下"暂无直播记录"。
     * 搜索框初始就是空的，所以空串没有必要防抖。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val debouncedTitleQuery: kotlinx.coroutines.flow.Flow<String> =
        titleQuery.debounce { q -> if (q.isBlank()) 0L else TITLE_QUERY_DEBOUNCE_MS }

    /** 嵌套 combine 的输入载体（避免 Pair/Triple 层层嵌套后读不懂字段含义）。 */
    private data class TitleInputs(
        val immediate: String,
        val debounced: String,
        val filter: HistoryFilter,
        val message: String?
    )

    /** 导出：非空表示正在为该主播选择导出时间范围。 */
    private val exportRange = MutableStateFlow<HistoryFilter?>(null)
    val exportRangeFlow: StateFlow<HistoryFilter?> = exportRange

    /**
     * 导出进行中。二级页虽然没有勾选对话框，但导出仍然是"取维护锁 → 构建快照 → 写 Download"
     * 的长流程：进行中再点一次会产出重复文件、并让两次导出互相抢维护锁。
     * 与一级页（HistoryListScreen/StatsListScreen）保持同一套守卫语义。
     */
    private val exportRunning = MutableStateFlow(false)
    val exportRunningFlow: StateFlow<Boolean> = exportRunning

    fun startExport() { exportRange.value = HistoryFilter() }
    fun cancelExport() { exportRange.value = null }

    /**
     * 导出本主播的直播历史（用户定稿：单主播只需划定时间范围）。
     * 导出范围与页面上的筛选是两件事，因此这里用独立的范围选择。
     */
    fun confirmExport(range: HistoryFilter) {
        val streamerId = _uiState.value.streamer?.id ?: return
        if (exportRunning.value) return
        exportRange.value = null
        exportRunning.value = true
        viewModelScope.launch {
            try {
                message.value = "正在导出…"
                message.value = com.example.bilimonitor.ui.common.performHistoryExport(
                    exportRepository, listOf(streamerId), range
                )
            } finally {
                // 失败/取消也要复位，否则导出入口会永久置灰
                exportRunning.value = false
            }
        }
    }

    fun load(streamerId: Long) {
        if (loadedStreamerId == streamerId) return
        loadedStreamerId = streamerId
        viewModelScope.launch {
            // 直接 collect 写 _uiState，不再套一层 stateIn(WhileSubscribed(5_000))：
            // 这个 collect 是 ViewModel 自己永久持有的订阅，WhileSubscribed 的 5 秒超时
            // 永远不会触发 —— 那层 stateIn 既不省资源，又让注释看起来像"没人看就停上游"，
            // 与实际行为相反。初值由 _uiState 自己的默认值提供。
            combine(
                streamerDao.observeById(streamerId),
                liveSessionDao.observeByStreamer(streamerId, 600),
                titleDao.observeAll(),
                areaDao.observeAll(),
                // message 必须作为 flow 输入（而不是在 transform 里读 message.value）：
                // 否则 clearMessage() 只是改了内部值、不会让管线重新发射，
                // 那条提示要一直挂到下一次数据库变更（例如下一个监控 Tick 写库）才消失。
                combine(titleQuery, debouncedTitleQuery, filter, message) { q, dq, f, m ->
                    TitleInputs(immediate = q, debounced = dq, filter = f, message = m)
                }
            ) { arr ->
                @Suppress("UNCHECKED_CAST")
                val streamer = arr[0] as StreamerEntity?
                @Suppress("UNCHECKED_CAST")
                val sessions = arr[1] as List<LiveSessionEntity>
                @Suppress("UNCHECKED_CAST")
                val rawTitles =
                    arr[2] as List<com.example.bilimonitor.data.local.entity.LiveSessionTitleEntity>
                @Suppress("UNCHECKED_CAST")
                val rawAreas =
                    arr[3] as List<com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity>
                @Suppress("UNCHECKED_CAST")
                val inputs = arr[4] as TitleInputs
                val tq = inputs.immediate
                // 过滤用防抖后的值；显示仍用即时值，保证输入框不回退
                val filterQuery = inputs.debounced
                val f = inputs.filter

                // 变更表按本主播的场次过滤：observeAll() 是全表读，
                // 会随库里任何一条标题/分区写入而发射；这里只关心当前这 600 条场次，
                // 先过滤再分组可以避免为整表做 groupBy/排序（DAO 层改按主播查询属于 data 层）。
                val sessionIds = sessions.mapTo(HashSet()) { it.stableId }
                val titles = rawTitles.filter { it.sessionStableId in sessionIds }
                val areas = rawAreas.filter { it.sessionStableId in sessionIds }

                val titlesBySession = titles.groupBy { it.sessionStableId }
                    .mapValues { (_, list) -> list.sortedBy { it.observedAt }.map { it.title } }

                // 分区：一场直播可能换过多次分区，全部按观察顺序展示
                val areasBySession = areas.groupBy { it.sessionStableId }
                    .mapValues { (_, list) ->
                        list.sortedBy { it.observedAt }.map { it.areaLabel }.distinct()
                    }

                var rows = sessions.map { s ->
                    StreamerSessionRow(
                        session = s,
                        titles = (titlesBySession[s.stableId].orEmpty() +
                            listOfNotNull(s.titleAtStart, s.titleAtEnd)).distinct(),
                        // 兜底：迁移回填之外，老场次可能只有 session.areaAtStart
                        areas = areasBySession[s.stableId].orEmpty()
                            .ifEmpty { listOfNotNull(s.areaAtStart, s.areaAtEnd).distinct() }
                    )
                }

                // 周期筛选
                val bounds = f.bounds()
                if (bounds != null) {
                    rows = rows.filter {
                        val t = it.session.startTime ?: it.session.createdAt
                        t >= bounds.first && t < bounds.second
                    }
                }

                // 标题关键词（在筛选结果内搜索）
                if (filterQuery.isNotBlank()) {
                    rows = rows.filter { r -> r.titles.any { it.contains(filterQuery.trim()) } }
                }

                StreamerHistoryUiState(
                    streamer = streamer,
                    // 排序按"直播时间"（开播时间）倒序，createdAt 仅作兜底 ——
                    // 与 DAO（observeByStreamer）保持同一口径，避免两处排序键不同导致列表跳动
                    rows = rows.sortedByDescending { it.session.startTime ?: it.session.createdAt },
                    filter = f,
                    titleQuery = tq,
                    message = inputs.message
                )
            }.collect { _uiState.value = it }
        }
    }

    private val _uiState = MutableStateFlow(StreamerHistoryUiState())
    val uiState: StateFlow<StreamerHistoryUiState> = _uiState

    fun setTitleQuery(q: String) { titleQuery.value = q }

    fun setFilter(f: HistoryFilter) { filter.value = f }

    /** 手工补录（主播=当前页面主播）。三项填二补全第三。 */
    fun createManualSession(
        start: Long?,
        end: Long?,
        duration: Long?,
        title: String,
        note: String?,
        area: String? = null
    ) {
        viewModelScope.launch {
            val stableId = _uiState.value.streamer?.stableId ?: run {
                message.value = "主播不存在"; return@launch
            }
            val created = runCatching {
                historyRepository.createManualSession(
                    stableId, start, end, duration,
                    title.takeIf { it.isNotBlank() }, note?.takeIf { it.isNotBlank() },
                    area?.takeIf { it.isNotBlank() }
                )
            }.getOrElse { m -> message.value = "创建失败：${m.message}"; return@launch }
            message.value = when (created) {
                CorrectionResult.Applied -> "已创建手工场次"
                CorrectionResult.Conflict -> "创建失败"
                CorrectionResult.NotFound -> "主播不存在"
                is CorrectionResult.Invalid -> created.reason
            }
        }
    }

    fun applyCorrection(correction: ManualCorrection) {
        viewModelScope.launch {
            val outcome = runCatching { historyRepository.applyManualCorrection(correction) }
                .getOrElse { message.value = "保存失败：${it.message}"; return@launch }
            message.value = when (val result = outcome) {
                CorrectionResult.Applied -> "修正已保存"
                CorrectionResult.Conflict -> "保存冲突：记录已被其他修改更新，请重试"
                CorrectionResult.NotFound -> "记录不存在"
                is CorrectionResult.Invalid -> result.reason
            }
        }
    }

    fun clearMessage() { message.value = null }

    /**
     * 删除一条直播记录（用户要求：历史记录要有删除按钮）。
     *
     * 会连带清理该场次的事件、修正审计与关播资格区间，并递增 sourceDataVersion
     * 让统计/诊断立即重算 —— 详见 `HistoryRepository.deleteSession`。
     */
    fun deleteSession(sessionStableId: String) {
        viewModelScope.launch {
            val deleted = runCatching { historyRepository.deleteSession(sessionStableId) }
                .getOrElse { message.value = "删除失败：${it.message}"; return@launch }
            message.value = if (deleted > 0) "已删除该条直播记录" else "记录不存在（可能已被删除）"
        }
    }
}

/** 时长分框状态：时/分/秒 三框；toSeconds() 全填且合法时返回总秒数。 */
class DurationParts {
    var hour by mutableStateOf("")
    var minute by mutableStateOf("")
    var second by mutableStateOf("")

    fun isComplete(): Boolean = hour.isNotBlank() && minute.isNotBlank() && second.isNotBlank()

    fun toSeconds(): Long? = try {
        if (!isComplete()) null
        else hour.toLong() * 3600 + minute.toLong() * 60 + second.toLong()
    } catch (e: Exception) { null }

    fun setSeconds(total: Long?) {
        if (total == null || total < 0) { hour = ""; minute = ""; second = ""; return }
        hour = (total / 3600).toString()
        minute = ((total % 3600) / 60).toString()
        second = (total % 60).toString()
    }
}

/**
 * 三框时长输入：时 / 分 / 秒。
 * [onChanged] 在任一分框内容变化后触发（用于"填两项自动补全第三项"）。
 */
@Composable
fun DurationFields(
    label: String,
    parts: DurationParts,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {}
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.example.bilimonitor.ui.common.smallTimeBox("时", parts.hour, 5, { parts.hour = it; onChanged() }, Modifier.weight(1f))
            com.example.bilimonitor.ui.common.smallTimeBox("分", parts.minute, 2, { parts.minute = it; onChanged() }, Modifier.weight(1f))
            com.example.bilimonitor.ui.common.smallTimeBox("秒", parts.second, 2, { parts.second = it; onChanged() }, Modifier.weight(1f))
        }
    }
}

@Composable
fun StreamerHistoryScreen(
    streamerId: Long,
    onBack: () -> Unit,
    viewModel: StreamerHistoryViewModel = hiltViewModel()
) {
    LaunchedEffect(streamerId) { viewModel.load(streamerId) }
    // 用 collectAsStateWithLifecycle 而不是 collectAsState：
    // 后者把订阅挂在组合上，Activity 进后台后组合仍在、订阅不解除，
    // 界面会在不可见时继续接收并重组这份状态（这里每帧都带 groupBy/排序结果），纯属耗电。
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportRange by viewModel.exportRangeFlow.collectAsStateWithLifecycle()
    val exportRunning by viewModel.exportRunningFlow.collectAsStateWithLifecycle()
    var showFilterDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LiveSessionEntity?>(null) }
    var deleting by remember { mutableStateOf<LiveSessionEntity?>(null) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { Text(state.streamer?.name ?: "直播历史", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 导出进行中禁用入口：避免重复触发产出多个文件、并与自己抢维护锁
                    // 收紧两个按钮的内边距：默认 TextButton 左右各 12dp、最小宽 64dp，
                    // 两个并排会占到卡片右半屏，把「导出」推到靠中间、与标题打架（用户反馈）。
                    TextButton(
                        onClick = { viewModel.startExport() },
                        enabled = !exportRunning,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("导出") }
                    TextButton(
                        onClick = { showManualDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("手工补录") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 补录 / 修正结果反馈（原实现只有 message 状态与自动清理计时器，没有渲染点，
            // 页面上已有 LaunchedEffect(history) 负责 2.5 秒后清除，故这里不再自带计时）。
            com.example.bilimonitor.ui.common.MessageBanner(
                message = state.message,
                autoDismissMillis = null,
                onDismiss = { viewModel.clearMessage() }
            )
            // 标题关键词搜索 + 筛选按钮
            //
            // 这一行**不加水平 padding**：FloatingSearchField 自带 12dp 水平内边距，
            // 外面再包 16dp 会让搜索卡片缩进 28dp（与同行按钮、下方 16dp 卡片都不齐），
            // 还白吃掉 32dp 可用宽度。筛选按钮只补 12dp 右内边距，与搜索卡片外缘对齐。
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingSearchField(
                    value = state.titleQuery,
                    onValueChange = { viewModel.setTitleQuery(it) },
                    placeholder = "搜索标题关键词",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { showFilterDialog = true },
                    modifier = Modifier
                        .padding(end = 12.dp)
                        // "已筛选"原来只由按钮底色表达，而底色对读屏用户不存在，
                        // contentDescription 又恒为"筛选"。用 stateDescription 把当前筛选状态说出来。
                        .semantics {
                            stateDescription = if (state.filter.type != HistoryFilterType.ALL) {
                                state.filter.label()
                            } else "未筛选"
                        },
                    colors = IconButtonDefaults.iconButtonColors(
                        // 底色用 surfaceContainer：这个按钮与搜索卡片同处一行浮层，
                        // surface 会比周围卡片明显更"平"，看起来像没画完。
                        containerColor = if (state.filter.type != HistoryFilterType.ALL)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = "筛选")
                }
            }

            // 当前筛选条件提示
            if (state.filter.type != HistoryFilterType.ALL) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { showFilterDialog = true },
                        label = { Text(state.filter.label(), style = MaterialTheme.typography.labelMedium) }
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { viewModel.setFilter(HistoryFilter()) }) { Text("清除筛选") }
                    Text(
                        "共 ${state.rows.size} 场",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.rows.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (state.filter.type == HistoryFilterType.ALL && state.titleQuery.isBlank())
                            "暂无直播记录\n确认开播后会自动记录；也可手工补录"
                        else "没有匹配的直播记录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.session.stableId }) { row ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                var expanded by remember(row.session.stableId) { mutableStateOf(false) }
                                Text(
                                    row.titles.firstOrNull() ?: "（无标题记录）",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (row.titles.size > 1) {
                                    Spacer(Modifier.height(4.dp))
                                    if (expanded) {
                                        row.titles.forEachIndexed { i, t ->
                                            Text(
                                                (if (i == 0) "① " else "→ ") + t,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Text(
                                            "期间标题变更 ${row.titles.size} 个（点按展开全部）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { expanded = true }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                // 场次时间按该场记录的时区显示（库里存了 startTimeZone；
                                // 为 null 或已失效时 zoneOf 会回退设备时区）——
                                // 否则跨时区后历史整体偏移，而库里正确的时区被忽略。
                                // remember 一下避免每次重组都重新解析时区名。
                                val sessionZone = remember(row.session.startTimeZone) {
                                    zoneOf(row.session.startTimeZone)
                                }
                                Text(
                                    "${formatTime(row.session.startTime, sessionZone)} → " +
                                        formatTime(row.session.endTime, sessionZone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // 直播分区（用户定稿功能）：一场直播换过多个分区时全部列出
                                if (row.areas.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (row.areas.size == 1) "分区：${row.areas.first()}"
                                        else "分区变更 ${row.areas.size} 个：" +
                                            row.areas.joinToString(" → "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // 时长 + 操作按钮一行（用户要求：删掉"开播/关播是否人工修正"的标签）——
                                // 那个 chip 既没人看又占掉大半行宽度，去掉后这里一行就能放下时长与按钮。
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        formatDuration(row.session.durationSeconds),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { editing = row.session }) { Text("修正") }
                                    TextButton(onClick = { deleting = row.session }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                row.session.note?.let {
                                    Text("备注：$it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    // 24dp 就够：本页是二级路由，底栏 Dock 不渲染（AppNavHost 的 showBottomBar
                    // 只认 4 个一级路由），这里只需把最后一张卡片垫离系统导航栏。
                    // 一级列表页（HistoryListScreen）才需要按 Dock 占位给的底部内边距。
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showFilterDialog) {
        TimeRangeDialog(
            current = state.filter,
            title = "筛选直播记录",
            confirmLabel = "应用",
            allowAll = true,
            onApply = { viewModel.setFilter(it); showFilterDialog = false },
            onDismiss = { showFilterDialog = false }
        )
    }

    // 单主播导出：只选时间范围（含"全部"），文件名 = 时间范围 + 主播名
    exportRange?.let { current ->
        TimeRangeDialog(
            current = current,
            title = "导出「${state.streamer?.name ?: "主播"}」的直播历史",
            confirmLabel = "导出",
            allowAll = true,
            onApply = { viewModel.confirmExport(it) },
            onDismiss = { viewModel.cancelExport() }
        )
    }

    if (showManualDialog) {
        ManualDialog(
            onDismiss = { showManualDialog = false },
            onSave = { start, end, duration, title, note, area ->
                viewModel.createManualSession(start, end, duration, title, note, area)
                showManualDialog = false
            }
        )
    }

    // 删除单条直播记录（二次确认：删除不可撤销，且会连带清掉该场次的事件/修正记录）
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条直播记录？") },
            text = {
                val sessionZone = remember(session.startTimeZone) { zoneOf(session.startTimeZone) }
                // 连带删除必须写进二次确认：删场次会一并清掉该场次事件产生的通知记录
                //（notification_outbox / notification_history 被 live_event 以 NO ACTION 外键引用，
                //  不先删它们就删不掉事件）。任何连带删除都不允许静默发生。
                Text(
                    "${formatTime(session.startTime, sessionZone)} → " +
                        "${formatTime(session.endTime, sessionZone)}\n" +
                        "${session.titleAtStart ?: "（无标题记录）"}\n\n" +
                        "该场次的事件、人工修正记录，以及这些事件产生的通知记录会一并删除，无法撤销。" +
                        (if (session.endTime == null) "\n\n注意：这一场仍在进行中，删除后监控会重新开始记录。" else "")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session.stableId)
                    deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }

    editing?.let { session ->
        EditSessionDialog(
            session = session,
            onDismiss = { editing = null },
            onSave = { start, end, duration, title, note, area ->
                viewModel.applyCorrection(
                    ManualCorrection(
                        sessionStableId = session.stableId,
                        startTime = start,
                        endTime = end,
                        durationSeconds = duration,
                        note = note,
                        title = title,
                        area = area
                    )
                )
                editing = null
            }
        )
    }
}

@Composable
private fun ManualDialog(
    onDismiss: () -> Unit,
    onSave: (Long?, Long?, Long?, String, String?, String?) -> Unit
) {
    val start = remember { DateTimeParts() }   // 不默认当前时间：留空表示由用户填写
    val end = remember { DateTimeParts() }
    val duration = remember { DurationParts() }
    var titleText by remember { mutableStateOf("") }
    // 分区可留空（用户要求）：留空就什么都不写，不伪造"未知分区"
    var areaText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 三项填二补全第三（0.6.108.2 / 原规范 108.2）。
    //
    // 原实现按"这次改了哪一项"去反算它自己 —— 由于 onChanged 是在**每个分框每次按键**后触发的，
    // 用户刚敲进去的那个值会立刻被另外两项反算覆盖：改开播 → 被"下播-时长"写回原值；
    // 改下播 → 被"开播+时长"写回原值；改时长 → 被"下播-开播"写回原值（且开播被挪走）。
    // 表现就是"这三项怎么也改不动"。
    //
    // 现规则（仍是填二补第三，不是三项都要手填）：
    //  1) 以"用户最后编辑的那一项"为基准，只反算另外两项，**绝不回写正在编辑的那一项**；
    //  2) 正在键入（日期/时长还没填满）时不推导，避免边打字边互相打架；
    //  3) 用户亲手清空的项不再自动补（时长被清空后又改开播时，按清空前的时长把下播整体平移）。
    var lastDuration by remember { mutableStateOf<Long?>(null) }   // 清空前的时长，供整体平移用
    // "正在编辑"标记：某一项只要不是完整状态就说明用户正在动它（清空后逐字符键入的中间态），
    // 此时既不能拿它当基准，也不能把它当"空项"去补 —— 否则用户刚敲的字会被立刻覆盖。
    var startEditing by remember { mutableStateOf(false) }
    var endEditing by remember { mutableStateOf(false) }
    var durationEditing by remember { mutableStateOf(false) }
    // 空 = 五项全空（setEpoch(null) 的结果）
    fun empty(p: DateTimeParts) = p.year.isBlank() && p.month.isBlank() && p.day.isBlank() &&
        p.hour.isBlank() && p.minute.isBlank()
    fun durEmpty(d: DurationParts) = !d.isComplete() && d.hour.isBlank() && d.minute.isBlank()

    /** 补出"仍为空、且用户没在动"的项。 */
    fun fillEmptyFrom(skip: String) {
        val s = start.toEpoch()
        val e = end.toEpoch()
        if (skip != "duration" && !durationEditing && durEmpty(duration) && s != null && e != null && e >= s) {
            duration.setSeconds((e - s) / 1000); return
        }
        val d = duration.toSeconds()
        if (skip != "end" && !endEditing && empty(end) && s != null && d != null) {
            end.setEpoch(s + d * 1000); return
        }
        if (skip != "start" && !startEditing && empty(start) && e != null && d != null) {
            start.setEpoch((e - d * 1000).coerceAtLeast(0))
        }
    }

    fun deriveFrom(field: String) {
        val editing = when (field) {
            "start" -> startEditing; "end" -> endEditing; else -> durationEditing
        }
        if (editing) return   // 还在键入 → 本项不作基准，也不回写它
        val s = start.toEpoch(); val e = end.toEpoch(); val d = duration.toSeconds()
        if (field == "start" && s != null && durEmpty(duration) && lastDuration != null) {
            // 用户清空了时长、此刻又在填开播：沿用清空前的时长把下播一起平移，
            // 否则会拿"新开播 → 旧下播"当跨度，把时长悄悄改掉。
            duration.setSeconds(lastDuration); end.setEpoch(s + lastDuration!! * 1000); return
        }
        if (field == "start" && s != null) {
            if (d != null) { end.setEpoch(s + d * 1000); return }           // 保时长 → 推下播
            if (e != null) { duration.setSeconds((e - s) / 1000); return }  // 时长还空 → 由起止补
        } else if (field == "end" && e != null) {
            if (s != null) { duration.setSeconds((e - s) / 1000); return }  // 保开播 → 推时长
            if (d != null) { start.setEpoch((e - d * 1000).coerceAtLeast(0)); return }
        } else if (field == "duration" && d != null) {
            if (s != null) { end.setEpoch(s + d * 1000); return }           // 保开播 → 推下播
            if (e != null) { start.setEpoch((e - d * 1000).coerceAtLeast(0)); return }
        }
        fillEmptyFrom(field)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手工补录直播场次") },
        text = {
            Column {
                OutlinedTextField(
                    value = titleText, onValueChange = { titleText = it },
                    label = { Text("直播间标题（可选）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = areaText, onValueChange = { areaText = it },
                    label = { Text("直播分区（可选，留空则不填）") },
                    placeholder = { Text("例如：虚拟主播 · 唱见") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                DateTimeFields(
                    label = "开播时间", parts = start, modifier = Modifier.fillMaxWidth(),
                    onChanged = { startEditing = !start.isComplete(); deriveFrom("start") }
                )
                DateTimeFields(
                    label = "下播时间（可留空=进行中）", parts = end, modifier = Modifier.fillMaxWidth(),
                    onChanged = { endEditing = !end.isComplete(); deriveFrom("end") }
                )
                DurationFields(
                    label = "时长（时/分/秒，与上两项互为补全）", parts = duration,
                    modifier = Modifier.fillMaxWidth(),
                    onChanged = {
                        if (duration.isComplete()) lastDuration = duration.toSeconds()  // 记住时长，供整体平移
                        durationEditing = !duration.isComplete()
                        deriveFrom("duration")
                    }
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val startE = if (start.isComplete()) start.toEpoch() else null
                val endE = if (end.isComplete()) end.toEpoch() else null
                val d = if (duration.isComplete()) duration.toSeconds() else null
                val provided = listOf(start.isComplete(), end.isComplete(), d != null).count { it }
                if (provided < 2) { error = "开播/下播/时长至少填写两项"; return@TextButton }
                if ((start.isComplete() && startE == null) || (end.isComplete() && endE == null)) {
                    error = "存在无效日期（如 2 月 30 日），请检查"; return@TextButton
                }
                onSave(startE, endE, d, titleText, note.ifBlank { null }, areaText.ifBlank { null })
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
@Composable
private fun EditSessionDialog(
    session: LiveSessionEntity,
    onDismiss: () -> Unit,
    onSave: (Long?, Long?, Long?, String?, String?, String?) -> Unit
) {
    val start = remember { DateTimeParts().apply { setEpoch(session.startTime) } }
    val end = remember { DateTimeParts().apply { setEpoch(session.endTime) } }
    val duration = remember { DurationParts().apply { setSeconds(session.durationSeconds) } }
    var titleText by remember { mutableStateOf(session.titleAtStart ?: "") }
    // 分区框预填当前记录，留空 = 不改动（用户要求：手工补录/修正可以不填这一项）
    var areaText by remember { mutableStateOf(session.areaAtStart ?: "") }
    var noteText by remember { mutableStateOf(session.note ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    // 与"手工补录"同一套填二补第三规则（详见 ManualDialog 上方的说明）：
    // 以"用户最后编辑的那一项"为基准由另外两项算出第三项，**绝不回写正在编辑的那一项**。
    // 本对话框三项都预填了完整值，所以旧实现（反算的是正在编辑的那一项自己）在这里
    // 100% 复现"改不动"：改完立刻被另外两项的原值写回。
    var lastDuration by remember { mutableStateOf<Long?>(null) }
    var startEditing by remember { mutableStateOf(false) }
    var endEditing by remember { mutableStateOf(false) }
    var durationEditing by remember { mutableStateOf(false) }
    fun empty(p: DateTimeParts) = p.year.isBlank() && p.month.isBlank() && p.day.isBlank() &&
        p.hour.isBlank() && p.minute.isBlank()
    fun durEmpty(d: DurationParts) = !d.isComplete() && d.hour.isBlank() && d.minute.isBlank()

    /** 补出"仍为空、且用户没在动"的项。 */
    fun fillEmptyFrom(skip: String) {
        val s = start.toEpoch()
        val e = end.toEpoch()
        if (skip != "duration" && !durationEditing && durEmpty(duration) && s != null && e != null && e >= s) {
            duration.setSeconds((e - s) / 1000); return
        }
        val d = duration.toSeconds()
        if (skip != "end" && !endEditing && empty(end) && s != null && d != null) {
            end.setEpoch(s + d * 1000); return
        }
        if (skip != "start" && !startEditing && empty(start) && e != null && d != null) {
            start.setEpoch((e - d * 1000).coerceAtLeast(0))
        }
    }

    fun deriveFrom(field: String) {
        val editing = when (field) {
            "start" -> startEditing; "end" -> endEditing; else -> durationEditing
        }
        if (editing) return   // 还在键入 → 本项不作基准，也不回写它
        val s = start.toEpoch(); val e = end.toEpoch(); val d = duration.toSeconds()
        if (field == "start" && s != null && durEmpty(duration) && lastDuration != null) {
            // 时长被用户清空后又改开播：沿用清空前的时长整体平移（避免把"新开播→旧下播"当跨度）
            duration.setSeconds(lastDuration); end.setEpoch(s + lastDuration!! * 1000); return
        }
        if (field == "start" && s != null) {
            if (d != null) { end.setEpoch(s + d * 1000); return }           // 保时长 → 推下播
            if (e != null) { duration.setSeconds((e - s) / 1000); return }
        } else if (field == "end" && e != null) {
            if (s != null) { duration.setSeconds((e - s) / 1000); return }  // 保开播 → 推时长
            if (d != null) { start.setEpoch((e - d * 1000).coerceAtLeast(0)); return }
        } else if (field == "duration" && d != null) {
            if (s != null) { end.setEpoch(s + d * 1000); return }           // 保开播 → 推下播
            if (e != null) { start.setEpoch((e - d * 1000).coerceAtLeast(0)); return }
        }
        fillEmptyFrom(field)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人工修正") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "开播 / 下播 / 时长填任意两项，自动补全第三项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = titleText, onValueChange = { titleText = it },
                    label = { Text("直播间标题（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = areaText, onValueChange = { areaText = it },
                    label = { Text("直播分区（可选，留空=不改动）") },
                    placeholder = { Text("例如：虚拟主播 · 唱见") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                DateTimeFields(
                    label = "开播时间", parts = start, modifier = Modifier.fillMaxWidth(),
                    onChanged = { startEditing = !start.isComplete(); deriveFrom("start") }
                )
                DateTimeFields(
                    label = "下播时间", parts = end, modifier = Modifier.fillMaxWidth(),
                    onChanged = { endEditing = !end.isComplete(); deriveFrom("end") }
                )
                DurationFields(
                    label = "时长（时/分/秒）", parts = duration,
                    modifier = Modifier.fillMaxWidth(),
                    onChanged = {
                        if (duration.isComplete()) lastDuration = duration.toSeconds()
                        durationEditing = !duration.isComplete()
                        deriveFrom("duration")
                    }
                )
                OutlinedTextField(
                    value = noteText, onValueChange = { noteText = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val startE = if (start.isComplete()) start.toEpoch() else null
                val endE = if (end.isComplete()) end.toEpoch() else null
                val d = if (duration.isComplete()) duration.toSeconds() else null
                val provided = listOf(start.isComplete(), end.isComplete(), d != null).count { it }
                if (provided < 2 && titleText == (session.titleAtStart ?: "") &&
                    noteText == (session.note ?: "") && areaText == (session.areaAtStart ?: "")
                ) {
                    error = "至少需要填写两项时间，或修改标题/分区/备注"
                    return@TextButton
                }
                if ((start.isComplete() && startE == null) || (end.isComplete() && endE == null)) {
                    error = "存在无效日期（如 2 月 30 日），请检查"; return@TextButton
                }
                if (startE != null && endE != null && endE < startE) {
                    error = "下播时间不能早于开播时间"; return@TextButton
                }
                onSave(
                    startE, endE, d, titleText.ifBlank { null }, noteText.ifBlank { null },
                    areaText.ifBlank { null }
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}