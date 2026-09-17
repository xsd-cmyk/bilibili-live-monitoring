package com.example.bilimonitor.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.repository.StatsGranularity
import com.example.bilimonitor.data.repository.StatsSeriesRepository
import com.example.bilimonitor.data.repository.StatsSeriesResult
import com.example.bilimonitor.ui.common.FloatingCard
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.TimeRangeDialog
import com.example.bilimonitor.ui.home.formatDuration
import com.example.bilimonitor.ui.home.formatTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StreamerStatsUiState(
    val streamerName: String = "",
    val loading: Boolean = true,
    val granularity: StatsGranularity = StatsGranularity.DAY,
    val bucketValue: String? = null,
    val bucketValues: List<String> = emptyList(),
    val customStart: Long? = null,
    val customEnd: Long? = null,
    val usingCustom: Boolean = false,
    val result: StatsSeriesResult? = null,
    val error: String? = null,
    /** 导出统计图表的反馈。 */
    val exportMessage: String? = null
)

/** 统计·第 2 级：单个主播的统计（按日/按周/按月/按年 + 自定义起止日期）。 */
@HiltViewModel
class StreamerStatsViewModel @Inject constructor(
    private val seriesRepository: StatsSeriesRepository,
    private val exportRepository: com.example.bilimonitor.data.repository.ExportRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StreamerStatsUiState())
    val state: StateFlow<StreamerStatsUiState> = _state

    /** 导出统计图表：非空表示正在选择导出时间范围。 */
    private val exportRange = MutableStateFlow<com.example.bilimonitor.ui.common.HistoryFilter?>(null)
    val exportRangeFlow: StateFlow<com.example.bilimonitor.ui.common.HistoryFilter?> = exportRange

    /**
     * 导出进行中。二级页虽然没有勾选对话框，但导出仍是"取维护锁 → 聚合 → 生成 HTML/SVG → 写盘"
     * 的长流程：进行中再点一次会产出重复文件、并让两次导出互相抢维护锁。
     * 与一级页（StatsListScreen）保持同一套守卫语义。
     */
    private val exportRunning = MutableStateFlow(false)
    val exportRunningFlow: StateFlow<Boolean> = exportRunning

    fun startExport() { exportRange.value = com.example.bilimonitor.ui.common.HistoryFilter() }
    fun cancelExport() { exportRange.value = null }

    /** 导出本主播的统计图表（规则与命名和历史导出完全一致）。 */
    fun confirmExport(range: com.example.bilimonitor.ui.common.HistoryFilter) {
        val id = streamerId
        if (id <= 0L) return
        if (exportRunning.value) return
        exportRange.value = null
        exportRunning.value = true
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(exportMessage = "正在导出…")
                _state.value = _state.value.copy(
                    exportMessage = com.example.bilimonitor.ui.common.performStatsExport(
                        exportRepository, listOf(id), range
                    )
                )
            } finally {
                // 失败/取消也要复位，否则导出入口会永久置灰
                exportRunning.value = false
            }
        }
    }

    fun clearExportMessage() { _state.value = _state.value.copy(exportMessage = null) }

    private var streamerId: Long = 0

    fun load(streamerId: Long) {
        // 只用 id 闩锁，不附加 `_state.value.result != null`：
        // 该主播还没有任何场次时 result 会一直是 null，附加条件等于没闩住 ——
        // 每次重组都会重跑 setGranularity（连带 refresh 查库），叠成多条并行任务。
        // 与 StreamerDetailScreen.load 的写法保持一致。
        if (this.streamerId == streamerId) return
        this.streamerId = streamerId
        setGranularity(StatsGranularity.DAY)
    }

    fun setGranularity(g: StatsGranularity) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true, granularity = g, usingCustom = false, error = null
            )
            val values = runCatching { seriesRepository.allBucketValues(g) }.getOrDefault(emptyList())
            val latest = runCatching { seriesRepository.currentBucketValue(g) }.getOrNull()
            val value = values.firstOrNull() ?: latest
            _state.value = _state.value.copy(bucketValues = values, bucketValue = value)
            refresh()
        }
    }

    fun setBucketValue(v: String) {
        if (v == _state.value.bucketValue) return
        _state.value = _state.value.copy(bucketValue = v)
        refresh()
    }

    /** 自定义起止日期（yyyy-MM-dd，含当天）。 */
    fun setCustomRange(start: Long, end: Long) {
        if (end < start) {
            _state.value = _state.value.copy(error = "结束日期不能早于开始日期")
            return
        }
        _state.value = _state.value.copy(
            usingCustom = true, customStart = start, customEnd = end, error = null
        )
        refresh()
    }

    private fun refresh() {
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(loading = true, error = null)
            val result = when {
                s.usingCustom -> runCatching {
                    val cs = s.customStart ?: throw IllegalStateException("请先设置开始日期")
                    val ce = s.customEnd ?: throw IllegalStateException("请先设置结束日期")
                    // ★ 自定义区间上界（台账 B20）：customStart/customEnd 存的是**所选日期当天 00:00**
                    //   （对话框标签写的就是「结束日期（含当天）」），而 DAO 的 `t < :to` 是排他上界。
                    //   原先把 customEnd 直接下传 = 上界"结束日 00:00"，整个结束日被排除：
                    //   选到"今天"时今天的场次一条都不出现，图表末尾还多一根空柱。
                    //   这里统一走 inclusiveDayRange()（ui/common/TimeRange.kt，全项目唯一换算点），
                    //   拿到 [起, 止) 后由 seriesCustom 按半开区间查询，结束日整天都包含在内。
                    val (from, toExclusive) =
                        com.example.bilimonitor.ui.common.inclusiveDayRange(cs, ce)
                    seriesRepository.seriesCustom(streamerId, from, toExclusive)
                }
                else -> {
                    val value = s.bucketValue ?: run {
                        _state.value = s.copy(loading = false, result = null)
                        return@launch
                    }
                    runCatching { seriesRepository.seriesFor(streamerId, s.granularity, value) }
                }
            }.getOrElse {
                _state.value = _state.value.copy(loading = false, error = it.message ?: "查询失败")
                return@launch
            }
            _state.value = _state.value.copy(loading = false, result = result, streamerName = result.streamerName)
        }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }
}

@Composable
fun StreamerStatsScreen(
    streamerId: Long,
    onBack: () -> Unit,
    viewModel: StreamerStatsViewModel = hiltViewModel()
) {
    LaunchedEffect(streamerId) { viewModel.load(streamerId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exportRange by viewModel.exportRangeFlow.collectAsStateWithLifecycle()
    val exportRunning by viewModel.exportRunningFlow.collectAsStateWithLifecycle()
    var showCustomDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { Text(state.streamerName.ifBlank { "统计" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 导出进行中禁用入口：避免重复触发产出多个文件、并与自己抢维护锁
                    TextButton(
                        onClick = { viewModel.startExport() },
                        enabled = !exportRunning
                    ) { Text("导出") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            com.example.bilimonitor.ui.common.MessageBanner(
                message = state.exportMessage,
                onDismiss = { viewModel.clearExportMessage() }
            )
            // 粒度 chips
            // 粒度与桶值 chips 收进一张浮动卡片（用户要求：统计二级页的各个标签也卡片化）。
            // 与首页筛选卡同一套语言：22dp 圆角 + surfaceContainer + 阴影，
            // 因此自动继承外观设置里的卡片透明度/明暗与玻璃效果。
            // ★ 与首页筛选卡 / 浮动标题栏 / 搜索卡 / Dock 同一套外观规则（ui/common/FloatingCard.kt）：
            //   tonalElevation 恒为 0、**半透明时不给阴影** —— 后者即"卡片下面还有一个淡淡的
            //   白色方框"的来源（半透明卡片下沿那圈黑色平台阴影），理由见该文件"规则二"。
            FloatingCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
            Column(Modifier.padding(vertical = 6.dp)) {
            // 粒度行用 LazyRow：5 个 chip 塞在不可滚动、不换行的 Row 里，卡片化后 360dp 屏
            // 只剩约 312dp 可用宽度，默认字体就要 266~292dp —— 字体放大或窄屏时第 5 个
            // 会被 Surface 圆角裁掉。紧随其后的桶值行本来就是 LazyRow，两行行为也不该不一致。
            LazyRow(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(StatsGranularity.entries, key = { it.name }) { g ->
                    FilterChip(
                        selected = !state.usingCustom && state.granularity == g,
                        onClick = { viewModel.setGranularity(g) },
                        label = { Text(g.label) }
                    )
                }
                item {
                    FilterChip(
                        selected = state.usingCustom,
                        onClick = { showCustomDialog = true },
                        label = { Text("自定义", maxLines = 1, softWrap = false) }
                    )
                }
            }

            // 桶值（数据驱动；自定义时显示当前区间）
            if (!state.usingCustom && state.bucketValues.isNotEmpty()) {
                LazyRow(
                    Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.bucketValues, key = { it }) { v ->
                        FilterChip(
                            selected = state.bucketValue == v,
                            onClick = { viewModel.setBucketValue(v) },
                            label = {
                                Text(
                                    when (state.granularity) {
                                        StatsGranularity.WEEK -> "自 $v 起的一周"
                                        else -> v
                                    }
                                )
                            }
                        )
                    }
                }
            }
            }   // Column
            }   // FloatingCard
            if (state.usingCustom) {
                Text(
                    "自定义区间：${state.customStart?.let { formatTime(it) }} ~ ${state.customEnd?.let { formatTime(it) }}（含当天）",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { showCustomDialog = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("修改区间")
                }
            }

            state.error?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (state.loading) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                return@Column
            }

            val result = state.result
            LazyColumn(
                Modifier.fillMaxSize(),
                // 本页是二级路由：底栏 Dock 只在一级路由渲染（AppNavHost 的 showBottomBar），
                // 这里没有浮层要避让，所以不用 Dock 推导出的底部内边距；
                // 保持原来的 16dp + 末尾 24dp Spacer，仅把最后一张卡片垫离系统导航栏
                // （内容区没有保留底部 system inset）。
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                if (state.usingCustom) "自定义区间" else periodTitle(state.granularity, state.bucketValue),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("场次 ${result?.totals?.sessionCount ?: 0}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "总时长 ${formatDuration(result?.totals?.monitoredSeconds ?: 0)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("已修正 ${result?.totals?.correctedSessionCount ?: 0}", style = MaterialTheme.typography.bodySmall)
                                Text("暂定 ${result?.totals?.provisionalSessionCount ?: 0}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("直播时长分布", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            SeriesBarChart(
                                points = result?.points.orEmpty(),
                                modifier = Modifier.fillMaxWidth().height(130.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val pts = result?.points.orEmpty()
                                val step = ((pts.size + 7) / 8).coerceAtLeast(1)
                                pts.forEachIndexed { i, p ->
                                    if (i % step == 0) {
                                        Text(
                                            p.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 场次明细：按年看最多 365 行。原先用 forEach 全铺在**一个** LazyColumn item 里，
                // 进页面就要一次性组合所有行（长区间直接卡住），LazyColumn 的按需组合完全失效 ——
                // 改成 items(...) 逐行懒加载。
                val detailPoints = result?.points.orEmpty().filter { it.sessionCount > 0 }
                item {
                    Card {
                        Column(Modifier.padding(14.dp)) {
                            Text("该时段场次明细", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (detailPoints.isEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text("该时段没有已结束的场次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                items(detailPoints) { p ->
                    Card {
                        Text(
                            "${p.label}：${p.sessionCount} 场 · ${formatDuration(p.seconds)}",
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCustomDialog) {
        CustomRangeDialog(
            initialStart = state.customStart ?: java.time.LocalDate.now().minusDays(6)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            initialEnd = state.customEnd ?: java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onApply = { s, e ->
                viewModel.setCustomRange(s, e)
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false }
        )
    }

    // 导出该主播的统计图表：只需要选时间范围（与历史导出二级页一致）
    exportRange?.let { current ->
        TimeRangeDialog(
            current = current,
            title = "导出「${state.streamerName.ifBlank { "主播" }}」的统计图表",
            confirmLabel = "导出",
            allowAll = true,
            onApply = { viewModel.confirmExport(it) },
            onDismiss = { viewModel.cancelExport() }
        )
    }
}

@Composable
private fun SeriesBarChart(
    points: List<com.example.bilimonitor.data.repository.SeriesPoint>,
    modifier: Modifier = Modifier
) {
    val maxSeconds = (points.maxOfOrNull { it.seconds } ?: 0L).coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val slot = size.width / points.size
        // 柱宽下限必须跟着 slot 收缩：按年时有 365 个点、slot 只有约 1px，
        // 原来的 coerceAtLeast(2f) 会把柱体撑得比 slot 还宽，相邻柱体互相重叠成一整块。
        // 这里保证 barWidth ≤ slot，slot 极小时自然退化成一根根紧贴的竖线。
        val barWidth = (slot * 0.62f).coerceIn(minOf(1f, slot), slot)
        // 圆角不能超过柱宽的一半：1px 的柱体上画 6px 圆角会画出毛刺
        val radius = minOf(6f, barWidth / 2f)
        points.forEachIndexed { index, point ->
            val h = if (point.seconds <= 0) 0f
            else (point.seconds.toFloat() / maxSeconds) * (size.height - 6f)
            if (h > 0f) {
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
        }
    }
}

@Composable
private fun CustomRangeDialog(
    initialStart: Long?,
    initialEnd: Long?,
    onApply: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
    val startParts = remember { com.example.bilimonitor.ui.common.DateParts().apply { setEpochDate(initialStart) } }
    val endParts = remember { com.example.bilimonitor.ui.common.DateParts().apply { setEpochDate(initialEnd) } }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义时间范围") },
        text = {
            Column {
                com.example.bilimonitor.ui.common.DateFields(
                    label = "开始日期", parts = startParts, modifier = Modifier.fillMaxWidth()
                )
                com.example.bilimonitor.ui.common.DateFields(
                    label = "结束日期（含当天）", parts = endParts, modifier = Modifier.fillMaxWidth()
                )
                if ((startParts.isComplete() && startParts.toEpoch() == null) ||
                    (endParts.isComplete() && endParts.toEpoch() == null)
                ) {
                    Text("存在无效日期（如 2 月 30 日），请检查", color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = if (startParts.isComplete()) startParts.toEpoch() else null
                val e = if (endParts.isComplete()) endParts.toEpoch() else null
                if (s == null || e == null) { error = "请完整填写开始和结束的年月日"; return@TextButton }
                if (e < s) { error = "结束日期不能早于开始日期"; return@TextButton }
                onApply(s, e)
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun periodTitle(g: StatsGranularity, value: String?): String = when (g) {
    StatsGranularity.DAY -> "按日 · $value"
    StatsGranularity.WEEK -> "按周 · 自 $value 起的一周"
    StatsGranularity.MONTH -> "按月 · $value"
    StatsGranularity.YEAR -> "按年 · $value 年"
}