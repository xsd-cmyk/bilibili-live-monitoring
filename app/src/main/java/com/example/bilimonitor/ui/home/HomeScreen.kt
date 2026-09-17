package com.example.bilimonitor.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.repository.InputMode
import com.example.bilimonitor.domain.policy.FreshnessPolicy
import com.example.bilimonitor.ui.theme.StatusColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.streamerStatusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenStreamer: (Long) -> Unit,
    // 应用内跳转：健康横幅的"点此设置 / 点此授权 / 点此前往设置开启"要跳到设置页。
    // 故意**不给默认值**。原来写成 `= {}`，调用方漏传，横幅照样渲染成 clickable、
    // 文案照样写着"点此设置"，点下去却什么都不会发生 —— 静默死链，编译器拦不住、
    // 评审也看不出来。改成必填后，漏传直接编译失败，比"再传一次"更能防复发。
    onNavigate: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appName by viewModel.appName.collectAsStateWithLifecycle()
    val navigationStyle by viewModel.navigationStyle.collectAsStateWithLifecycle()
    // 「开启数据过期提示」开关（高级设置里可关，默认开）。
    val freshnessExpiryHintEnabled by viewModel.freshnessExpiryHintEnabled.collectAsStateWithLifecycle()
    /**
     * 低频时钟：每 [DataExpiryTickMillis] 推进一次 `now`。
     *
     * ★ 为什么必须有它（本次修复的另一半）：卡片上的「N 分钟前」是**现算**的
     *   （`relativeTime` 用 `AppClocks.nowWall()`），但它的数据源是 Room 的 Flow ——
     *   引擎一停跑就再也没有写入、也就再也没有发射，没有别的时钟推它，
     *   这个数字会停在最后一次重组时的值上（用户看到的"数字僵住"）。
     *   有了这个 ticker，即使一条数据库写入都没有，界面每 30 秒也会按真实时间重算一次。
     *
     * ★ 为什么不放进 `HomeViewModel.uiState` 那个 combine：它已经是 5 路
     *   （Kotlin 具名 combine 重载的上限，见 HomeViewModel 里的注释），
     *   而这个时钟与业务数据无关，纯粹是"让组合期重新执行一次"。
     *
     * ★ 周期 30 秒：过期判定与「N 分钟前」的最小单位都是分钟，30 秒足够及时，
     *   而它每 30 秒只做一次 `Long` 赋值 —— 真正会重组的是读取了它的那部分作用域，
     *   且卡片是按 `freshnessRow` **等值**跳过的（文案没变就不会重组）。
     */
    val now by produceState(
        initialValue = com.example.bilimonitor.core.AppClocks.nowWall(),
        key1 = Unit
    ) {
        while (true) {
            kotlinx.coroutines.delay(DataExpiryTickMillis)
            value = com.example.bilimonitor.core.AppClocks.nowWall()
        }
    }
    // 底部导航占掉多高不是一个定值：悬浮 Dock 和贴底整条差别很大，见文件末尾的常量说明。
    val bottomBarClearance =
        if (navigationStyle == com.example.bilimonitor.data.repository.NavigationStyle.NORMAL) {
            BottomBarClearanceNormal
        } else {
            BottomBarClearanceDock
        }
    var showAddDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearMessage()
        }
    }
    // 从详情页（可能新增/移除标签）返回时刷新标签筛选
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refreshTags()
        onPauseOrDispose { }
    }

    val inSelection = state.selectedIds.isNotEmpty()
    Scaffold(
        topBar = {
            if (inSelection) {
                FloatingTopBar(
                    // 多选态右侧 5 个按钮会铺满整张卡片，标题栏里没有空位可放计数
                    // （实测：居中、靠左、缩短都还是会压住按钮）。
                    // 干脆不占标题位，把数字作为 actions 的**第一项** —— 布局顺序天然保证
                    // 它紧挨在「全选」左边，永远不会与按钮重叠。
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        Text(
                            "${state.selectedIds.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { viewModel.selectAll(state.streamers) }) { Text("全选") }
                        TextButton(onClick = { viewModel.batchFavorite(true) }) { Text("收藏") }
                        TextButton(onClick = { viewModel.batchResume() }) { Text("恢复") }
                        TextButton(onClick = { viewModel.batchPause() }) { Text("暂停") }
                        // 批量删除加二次确认：详情页单条删除一直有确认，首页批量删除却点一下直接生效，
                        // 误触的代价反而更大（一次删掉选中的全部主播）。软删除能恢复，
                        // 但用户并不知道这一点，确认框顺带把"历史保留"说清楚。
                        TextButton(onClick = { showBatchDeleteDialog = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            } else {
                FloatingTopBar(
                    // 应用名可在设置里自定义（只影响应用内显示与桌面快捷方式；
                    // 系统"应用信息"页里的名字由安装包决定，见外观设置页的说明）
                    title = { Text(appName) },
                    actions = {
                        IconButton(onClick = { viewModel.refreshNow() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "手动刷新")
                        }
                    }
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.health?.let { health ->
                // 健康横幅的文案里明确写着"点此设置 / 点此授权 / 点此前往设置开启"，
                // 原实现恒传 onClick = null，clickable 分支永不生效 —— 三处引导全是死链。
                // 需要用户操作的降级状态跳到设置页；纯信息性的状态（如"监控中"）保持不可点。
                val needsAction = health.status != com.example.bilimonitor.data.local.MonitorHealthStatus.HEALTHY
                HealthBanner(
                    text = health.userMessage,
                    onClick = if (needsAction) {
                        { onNavigate(com.example.bilimonitor.ui.nav.Routes.SETTINGS) }
                    } else {
                        null
                    }
                )
            }
            // 搜索框 + 状态/标签/分组筛选，整体收进一张浮动卡片
            // （用户要求：这些可点按的地方也像 Dock 一样悬浮起来）。
            // 结构在 HomeFilterCard.kt 里一次写完整，这里只留一行调用 ——
            // 就地包一层的做法会与下面的括号配对纠缠（见台账 H36）。
            HomeFilterCard(
                query = state.query,
                onQueryChange = { viewModel.setQuery(it) },
                filter = state.filter,
                onFilterChange = { viewModel.setFilter(it) },
                tagFilterIds = state.tagFilterIds,
                onTagFilterChange = { viewModel.setTagFilter(it) },
                groups = state.groups,
                groupFilterId = state.groupFilterId,
                onGroupFilterChange = { viewModel.setGroupFilter(it) },
                expanded = viewModel.filterExpanded,
                onExpandedChange = { viewModel.setFilterExpanded(it) },
                tags = state.tags
            )
            if (state.streamers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        CircularProgressIndicator()
                    } else {
                        // 空列表 ≠ 库里没有主播：只要筛选条件生效，"空"的含义就完全变了。
                        // 原实现只看 query，于是"收藏是空的""这个标签下没有主播"都被报成
                        // "还没有主播，点击 + 添加" —— 把筛选无结果说成没有数据，
                        // 用户会去加一个他早就加过的主播。这里按生效的条件给不同措辞。
                        // （不引入"每次筛选都转圈"的加载态：列表流在切换筛选时保留上一次的值，
                        //   界面本来就不会闪出中间空列表，加转圈只会制造闪烁。）
                        //
                        // 标签名按**标签列表自身的顺序**取（与筛选区收起态的摘要同一口径）：
                        // 遍历 Set 会随哈希顺序变化，同一组选择可能写出不同的顺序，
                        // 而这段文字是用户判断"是不是点错了"的唯一依据。
                        val selectedTagNames = state.tags.filter { it.id in state.tagFilterIds }.map { it.name }
                        val selectedTagLabel = selectedTagNames.joinToString("、")
                        val selectedGroupName = state.groups
                            .firstOrNull { it.id == state.groupFilterId }?.name
                        Text(
                            text = when {
                                // 标签多选取的是**交集**（用户定稿），于是"点第二个标签"经常直接把
                                // 列表筛空 —— 用户看到空列表的第一反应是"筛选坏了"。
                                // 所以这一种空态必须自己把原因说出来（"同时具备"），并把被它排除掉的
                                // 每个标签名都列全；有分组时一并交代（分组 ∧ 标签同属 AND）。
                                selectedTagNames.size >= 2 && selectedGroupName != null ->
                                    "分组「$selectedGroupName」下没有同时具备「$selectedTagLabel」的主播"
                                selectedTagNames.size >= 2 ->
                                    "没有同时具备「$selectedTagLabel」的主播"
                                state.query.isNotBlank() -> "没有匹配「${state.query}」的主播"
                                // 只选一个标签时不存在"同时具备几个"的问题，沿用原来的措辞；
                                // 但若同时有分组，把分组也写进去，否则用户会以为分组没生效。
                                selectedTagNames.size == 1 && selectedGroupName != null ->
                                    "分组「$selectedGroupName」下没有标签「$selectedTagLabel」的主播"
                                selectedTagNames.size == 1 -> "该标签下还没有主播"
                                state.groupFilterId != null -> "该分组下还没有主播"
                                state.filter == HomeFilter.FAVORITE ->
                                    "还没有收藏的主播\n点卡片右侧的星标即可收藏"
                                state.filter == HomeFilter.LIVE -> "当前没有正在直播的主播"
                                state.filter == HomeFilter.OFFLINE -> "没有未开播的主播"
                                else -> "还没有主播，点击 + 添加\n支持 UID、直播间链接"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.streamers, key = { it.id }) { streamer ->
                        val selected = streamer.id in state.selectedIds
                        val banned = streamer.id in state.bannedIds
                        StreamerCard(
                            streamer = streamer,
                            // 标签是 ViewModel 批量取回的（一次 IN 查询），卡片自己不查库；
                            // 取不到就是空列表，卡片退化成"没有标签行"的样子。
                            tags = state.streamerTags[streamer.id].orEmpty(),
                            // 封禁标记同样由 ViewModel 解好（一次映射），卡片只拿布尔值：
                            // 每帧解 `lastError` 里的 JSON 会把解析放进重组路径里。
                            banned = banned,
                            // 「数据过期」+「N 分钟前」都由这一个纯函数算好（见 FreshnessPolicy）：
                            //  ★ **不再读库里的 `streamer.freshnessStatus`** —— 那一列是"写入时刻的快照"，
                            //    引擎停跑时它会一直冻结在 FRESH，被当成"此刻是否过期"就是用户报的 bug；
                            //  ★ 判定用**当前时间**现算，`now` 由上面的 30s ticker 推进，
                            //    所以引擎停跑时这两个显示照样自己更新；
                            //  ★ 阈值取 `max(配置阈值, 2 × 实际周期)`：省电模式周期 15 分钟起，
                            //    不抬高阈值会变成"永远显示过期"（同样是 bug）。
                            freshnessRow = FreshnessPolicy.freshnessRowOf(
                                now = now,
                                lastConfirmedAt = streamer.lastConfirmedAt,
                                lastCheckedAt = streamer.lastCheckedAt,
                                thresholdSeconds = state.config?.freshnessStaleSeconds
                                    ?: FreshnessPolicy.DEFAULT_THRESHOLD_SECONDS,
                                tickPeriodSeconds = state.config?.let {
                                    FreshnessPolicy.tickPeriodSeconds(it.mode, it.intervalSeconds)
                                } ?: 0,
                                banned = banned,
                                hintEnabled = freshnessExpiryHintEnabled,
                                timeTextOf = { at -> relativeTime(at, now) }
                            ),
                            selected = selected,
                            onClick = {
                                if (viewModel.inSelectionMode) viewModel.toggleSelect(streamer.id)
                                else onOpenStreamer(streamer.id)
                            },
                            onLongClick = { viewModel.toggleSelect(streamer.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(streamer) }
                        )
                    }
                    // 列表末端的留白 = 浮层 Dock 的高度，最后一张卡片才能完整滚出来
                    // （Dock 不在 Scaffold 的 bottomBar 槽位里，不会自动为内容让位）
                    item { Spacer(Modifier.height(bottomBarClearance)) }
                }
            }
        }
    }

    // FAB 与消息条都是"贴在底部导航上方"的元素，因此都放在 Scaffold 之外的浮层里自己定位。
    // 为什么不放 Scaffold 的槽位：
    //  · 消息条：M3 把 snackbarHost 的底边摆在 snackbarHeight + (fabOffsetFromBottom
    //    ?: bottomBarHeight ?: inset) 之上；而本页没有 bottomBar，此时
    //    fabOffsetFromBottom = FAB 实测高度 + FabSpacing(16dp) + inset —— FAB 又带着
    //    96dp 的底部内边距，于是"有 FAB"时消息底边离底 56+96+16 = 168dp，进入多选
    //    （FAB 不组合）后这个偏移掉成 inset，消息贴底、整条被浮层 Dock 盖住。
    //    位置既然由 Scaffold 推算，就不可能稳定；这里改由 bottomBarClearance 单独决定，
    //    与"FAB 是否存在"彻底无关。
    //  · FAB：Scaffold 在没有 bottomBar 时会把底部系统栏内边距算进 FAB 偏移，而 Dock 自己是靠
    //    navigationBarsPadding 抬起来的。搬进同一个浮层、用同一个手段，两者才必然落在同一条
    //    基准线上（也免得 Scaffold 版本一换，FAB 相对 Dock 的位置又变）。
    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        if (!inSelection) {
            FloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = bottomBarClearance),
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加主播")
            }
        }
        state.message?.let { message ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 右侧给 FAB 让出通道：两者处在同一条高度带上，不避让就会叠在一起。
                    // 让位量恒为 88dp（FAB 56 + 边距 16 + 间隙 16），有/无 FAB 都一样 ——
                    // 消息的横向位置因此也不会随多选模式跳。
                    .padding(start = 16.dp, end = 88.dp, bottom = bottomBarClearance),
                contentAlignment = Alignment.Center
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface)) {
                    Text(
                        message, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddStreamerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { input, mode ->
                viewModel.addStreamer(input, mode)
                showAddDialog = false
            }
        )
    }

    // 批量删除的二次确认（与详情页单条删除同一风格）：
    // 一次会影响多位主播，误触代价比单条更大，所以动作与"取消"并列而非直接执行。
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("删除选中的主播？") },
            text = {
                Text(
                    "将停止监控已选的 ${state.selectedIds.size} 位主播；" +
                        "历史直播记录会保留，之后可重新添加恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteDialog = false
                    viewModel.batchDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HealthBanner(text: String, onClick: (() -> Unit)?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StreamerCard(
    streamer: StreamerEntity,
    /**
     * 该主播的标签（已按标签表自身的展示顺序排好）。
     *
     * 故意**不给默认值**：这是"卡片必须显示标签"这条需求的唯一入口，调用方漏传时
     * 编译器直接报错，比默默画出一张没有标签的卡片强（同本文件顶部 onNavigate 的理由）。
     * 空列表是合法输入 —— 表示这位主播确实一个标签都没有，此时整行不渲染。
     */
    tags: List<String>,
    /**
     * 该主播当前是否被封禁（来自 [HomeUiState.bannedIds]，由 ViewModel 从 `streamer.lastError`
     * 解出；卡片自己不解析任何东西）。
     *
     * 同样**不给默认值**：封禁标记决定状态标签写「封禁中」还是「待确认」，
     * 是这次需求的全部内容 —— 漏传就等于悄悄退回「待确认」，必须让漏传在编译期失败
     * （同本文件顶部 onNavigate 的理由）。
     */
    banned: Boolean,
    /**
     * 状态行要渲染的内容：是否标「· 数据过期」+ 时间文案（由 [FreshnessPolicy.freshnessRowOf] 算好）。
     *
     * 为什么由调用方算、并且**不给默认值**：判定要读"当前时间 + 配置阈值 + 调度周期 + 展示开关"，
     * 全塞进卡片会让每张卡片各自去解析配置；而漏传又必须能在编译期就失败（同 [banned] 的理由）。
     * 时间文案也在这里传递，是为了让"过期与否**不影响**时间行"这条用户要求能被单测锁定
     * （Composable 里的 if 分支在本项目没有测试基建）。
     */
    freshnessRow: FreshnessPolicy.FreshnessRow,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleFavorite: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        // 选中态只由下面的容器色（primaryContainer）表达，不再叠 1dp 内缩：
        // 那会让选中卡的尺寸比未选中时小 2dp，勾选时整列卡片跟着上下轻微跳动。
        // 表达"选中"不能用会改变布局尺寸的手段。
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AsyncImage(
                    model = streamer.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Box(
                    Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .background(statusColor(streamer.confirmedLiveStatus, banned), CircleShape)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    streamer.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = streamer.roomTitle ?: streamer.areaName ?: "暂无直播间标题"
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 文案口径抽在 ui.common.streamerStatusLabel（纯函数，有单测逐字锁定）：
                        // 封禁优先于「待确认」，未封禁时与改动前逐字一致。
                        streamerStatusLabel(streamer.confirmedLiveStatus, banned),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(streamer.confirmedLiveStatus, banned)
                    )
                    if (freshnessRow.showExpiredMark) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "· 数据过期",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.Stale
                        )
                    }
                    // 时间行：**与过期、封禁、开关全都无关**（用户要求「仍显示时间，方便看到目前状态」）。
                    // 渲染条件与文案都由 [freshnessRow] 决定（timeText 非空 ⇔ lastCheckedAt 非空），
                    // 这里只负责画出来；封禁中显示的是"最后一次**常规**检查"的时间，
                    // 封禁期间它本来就不会推进（主播在发请求前被过滤，低频复查只写 lastError），
                    // 因此这个数字持续变大是符合预期的。
                    freshnessRow.timeText?.let { timeText ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "· $timeText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 标签行：**只在真有标签时**才占高度 —— 没标签的卡片保持与从前完全一样的高度
                // 和排版，不会因为"卡片支持标签"就让整列卡片集体变高、把列表撑松。
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    // 单行 + 横向滚动，刻意**不**折行（FlowRow）：标签数量没有上限，折行会让卡片
                    // 越撑越高；而主播名/标题/状态行都是 maxLines = 1，它们不会让位、只会被挤掉，
                    // 被挤掉的就是信息本身。这里与 HomeFilterCard 的筛选 chip 行同一套"挤不下"
                    // 处理：滚得到，而且永远只占一个确定的行高。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            // weight(fill = false)：内容窄就按内容占位，内容宽则最多占满剩余宽度、
                            // 超出部分交给滚动 —— 这样右侧的"+N"永远留在视口内、不会被滚走
                            //（它是"还有几个标签没画"的唯一提示）。
                            Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tags.take(MaxCardTags).forEach { StreamerTagChip(it) }
                        }
                        if (tags.size > MaxCardTags) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "+${tags.size - MaxCardTags}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (streamer.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = "收藏",
                    tint = if (streamer.isFavorite) Color(0xFFE8A03C) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 卡片上的标签胶囊（纯展示，不可点）。
 *
 * 刻意**不用** M3 的 FilterChip / AssistChip：它们自带 32dp 最小高度和一整套点击态，
 * 摆在 48dp 头像旁边会把整张卡片顶高一截 —— 这里要表达的是"一眼看到这位主播有哪些标签"，
 * 不是一个可点控件。取全圆角 + secondaryContainer，与 HomeFilterCard 的筛选 chip
 * 同一套颜色语言（跟着主题/外观设置走），只是更紧凑：labelSmall + 上下各 1dp ≈ 18dp 高。
 */
@Composable
private fun StreamerTagChip(name: String) {
    Surface(
        // 单个标签名可能很长：给它一个宽度上限，超出部分在胶囊内部省略，
        // 而不是让一个标签独吞整行、把另外两个挤到视口之外（那样"看得到几个标签"
        // 就完全取决于标签名的长度，用户没法预期）。
        modifier = Modifier.widthIn(max = 96.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            name,
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 卡片上最多画几个标签。
 *
 * 标签可以无限创建，全画出来会把卡片撑成一大块（真正要看的直播状态反被压下去）。
 * 多出来的用"+N"交代清楚，且 N 放在滚动区之外、永远可见 —— 用户不会误以为"就这几个标签"。
 * 取 3：两个字左右的标签在 360dp 屏上一行放得下三个，基本不触发滚动。
 */
private const val MaxCardTags = 3

@Composable
private fun AddStreamerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, InputMode) -> Unit
) {
    var text by remember { mutableStateOf("") }
    // 默认按房间号识别：用户手上通常是直播间地址或房间号。
    var mode by remember { mutableStateOf(InputMode.ROOM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加主播") },
        text = {
            Column {
                Text("识别方式", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                InputMode.entries.forEach { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { mode = m }
                    ) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Column {
                            Text("按${m.label}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when (m) {
                                    InputMode.ROOM ->
                                        "输入直播间房间号，例如 22637261"
                                    InputMode.UID ->
                                        "输入主播 UID，例如 672328094"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "也可以直接粘贴直播间链接（自动按房间号识别）：\nhttps://live.bilibili.com/22637261",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(if (mode == InputMode.ROOM) "房间号" else "UID") },
                    placeholder = { Text("纯数字 / 直播间链接") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text, mode) },
                enabled = text.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 卡片上那枚状态点与状态文字的**颜色**。
 *
 * 封禁中走主题的警示色（`colorScheme.error`）：用户明确要求「封禁中」不能被看成正常状态，
 * 而按 [ConfirmedLiveStatus] 取色只会给出绿（直播中）/ 橙（轮播）/ 灰（未开播、待确认）——
 * 三种颜色都读作"正常"，分不出"这个房间被封了"。
 * 圆点与文字都从这个函数取色，两者天然一致，不会出现"点变红了、字还是灰的"。
 *
 * 为什么颜色不跟文案一起放进 `ui.common.streamerStatusLabel`：文案是纯规则（要能被单测逐字锁定），
 * 而颜色要读 `MaterialTheme`，会随卡片外观 / 深色模式变化，只能留在 Composable 里。
 */
@Composable
private fun statusColor(status: ConfirmedLiveStatus, banned: Boolean): Color =
    if (banned) MaterialTheme.colorScheme.error
    else when (status) {
        ConfirmedLiveStatus.LIVE -> StatusColors.Live
        ConfirmedLiveStatus.ROUND -> StatusColors.Round
        ConfirmedLiveStatus.OFFLINE -> StatusColors.Offline
        ConfirmedLiveStatus.UNKNOWN -> StatusColors.Unknown
    }

private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 相对时间。基准时间取网络校时后的墙上时间（244.13），
 * 而非 `System.currentTimeMillis()` —— 后者会被用户改系统时间影响，
 * 使"数据过期""刚刚"这类判断集体失真。
 *
 * [now] 可显式传入（首页把 30 秒 ticker 的当前时间传进来）：这样"这个文案依赖哪个时间"
 * 在签名上就是明确的，不需要靠"调用方恰好会重组"这种隐式约定。
 * 不给默认值时默认取 [com.example.bilimonitor.core.AppClocks.nowWall]，
 * 其余调用点（只关心"此刻"的展示）行为完全不变。
 */
fun relativeTime(
    epochMillis: Long,
    now: Long = com.example.bilimonitor.core.AppClocks.nowWall()
): String {
    val diff = now - epochMillis
    return when {
        diff < 0 -> "刚刚"
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            .format(timeFormatter)
    }
}

/**
 * 场次时间展示。
 *
 * [zoneId] 可选：库里每一场都记了 `startTimeZone / endTimeZone`（迁移注释写明
 * "NULL 才回退设备时区"），但显示端一直只用设备时区 —— 用户跨时区后，
 * 历史列表与统计会整体偏移，而库里正确的时区被静默忽略。
 * 保留默认参数是为了不动其余调用点（相对时间、通知时间等只关心"此刻"，
 * 用设备时区才对）。
 */
fun formatTime(epochMillis: Long?, zoneId: ZoneId? = null): String {
    if (epochMillis == null) return "—"
    val zone = zoneId ?: ZoneId.systemDefault()
    return Instant.ofEpochMilli(epochMillis).atZone(zone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

/**
 * 把库里存的 IANA 时区名解析成 [ZoneId]；为空或已失效（例如旧数据里的时区被系统移除）时
 * 回退设备时区，绝不因为一个展示字段让整页崩掉。
 */
fun zoneOf(timeZoneId: String?): ZoneId =
    timeZoneId?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()

fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}

/**
 * 悬浮 Dock 样式下，底部导航在内容之上占掉的高度 —— FAB、消息条、列表末端留白都要避让它。
 *
 * 组成（Dock 是浮层，下面这 124dp 全部压在内容之上）：
 *   26dp  栏体下沿与屏幕底部的浮动留白（AppNavHost 里 Dock 自己的 `padding(vertical = 26.dp)`）
 *  +72dp  栏体本身：
 *           图标 24 + 上下各 3dp = 30
 *           图标与文字之间 2
 *           文字约 16（labelSmall 的 lineHeight）
 *           → 每项内容 48，加选项 Column 的上下各 6dp = 60
 *           → 再加外层 Row 的上下各 6dp = 72
 *  +26dp  栏体上沿的浮动留白
 *  =124dp，再加 12dp 视觉间隙 → 136dp。
 *
 * 系统栏（导航键 / 手势条）余量**故意不写进这个数**：Dock 自带 navigationBarsPadding、
 * 上面浮层也加了同一个 padding，两者已经共享同一条基准线，再算一次就是双重留白。
 *
 * 取舍：栏体那 72dp 只能照 Dock 的内部尺寸估算（跨文件测量会把首页和导航栏耦死，
 * 而这一版不允许改 Dock 所在文件），而且它随系统字体放大而变化 —— 超大字号下仍可能压住
 * 一点点。Dock 内部尺寸一变，这个常量必须跟着改。
 */
private val BottomBarClearanceDock = 136.dp

/**
 * 贴底整条样式（NORMAL）下需要避让的高度：material3 1.3.1 的 NavigationBar 高度取自
 * `NavigationBarTokens.ContainerHeight = 80dp`，再加上 12dp 视觉间隙 → 92dp
 * （NavigationBar 自带 systemBars 的 windowInsets，系统栏余量同样不重复计）。
 *
 * 之前不管哪种样式都抬 96dp（照 Dock 调的魔数）：切到 NORMAL 时 FAB 会悬在栏体上方一大截，
 * 出现肉眼可见的断层；而浮层样式下 96dp 又不够 —— Dock 实际占到 124dp，FAB 会压进栏体里。
 */
private val BottomBarClearanceNormal = 92.dp

/**
 * 首页低频时钟的周期（毫秒）：每 30 秒让「N 分钟前」与「数据过期」按真实时间重算一次。
 *
 * 为什么必须有它：这两个展示原先只跟着 Room 的 Flow 重组，引擎一停跑就没有任何写入、
 * 也就没有任何发射，数字会僵在最后一次重组时的值上（用户报的 bug 之一）。
 * 为什么是 30 秒：两者最小单位都是分钟，30 秒足够及时；而它每 30 秒只做一次 `Long` 赋值，
 * 卡片又是按 `freshnessRow` 等值跳过的（文案没变就不重组），开销可以忽略。
 */
private const val DataExpiryTickMillis = 30_000L