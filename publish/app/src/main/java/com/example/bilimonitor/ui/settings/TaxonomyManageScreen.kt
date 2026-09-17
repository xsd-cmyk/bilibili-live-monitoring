package com.example.bilimonitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.example.bilimonitor.data.local.entity.GroupEntity
import com.example.bilimonitor.data.local.entity.TagEntity
import com.example.bilimonitor.data.repository.TaxonomyRepository
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.FloatingTopBarTitle
import com.example.bilimonitor.ui.common.MessageBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 标签管理页的渲染态。
 *
 * 可见性必须是 `internal` 而不能是 `private`：[TaxonomyManageViewModel] 是公开类，
 * 它公开的 `state` 属性的类型不能比它自己更封闭（`private` 在这里直接编不过）。
 *
 * `tags`/`groups` 为 null = 这一侧还没拿到第一次查询结果（区别于"确实一条都没有"）；
 * `tagCounts`/`groupCounts` 为 null = 这一次没有查到（由 scan 回填上一轮的值）。
 */
data class TaxonomyUiState(
    val tags: List<TagEntity>? = null,
    val groups: List<GroupEntity>? = null,
    val tagCounts: Map<Long, Int>? = emptyMap(),
    val groupCounts: Map<Long, Int>? = emptyMap()
)

/**
 * 「标签 / 分组管理」页（用户要求：标签与分组要能删除、能排序）。
 *
 * 数据层此前**已经**备好删除与排序能力（`TaxonomyRepository.deleteTag/deleteGroup/moveTag/moveGroup`、
 * `streamerCountOfTag/streamerCountOfGroup`），但界面上一个调用点都没有 —— 也就是说
 * 用户拿得到功能、只是摸不到。本页唯一的职责就是把这几条既有通道接到界面上。
 *
 * 因此这里刻意**不重写任何数据逻辑**：
 *  - 排序不自己算 `sortOrder`。`moveTag` / `moveGroup` 收的是"当前展示顺序里的下标"，
 *    内部会整表重排成 0..n-1。上层若自己算一个值再写库，既会踩到历史遗留的并列 0，
 *    也会在越界时把顺序写坏（见 TaxonomyRepository.moveTag 的注释）。
 *  - 关联主播数一律走 `tagsWithCount()` / `groupsWithCount()`（内部是 `streamerCountOfTag/Group`
 *    的同源口径：只数未软删主播）。界面不自己 JOIN、不自己过滤 deletedAt。
 */
@HiltViewModel
class TaxonomyManageViewModel @Inject constructor(
    private val taxonomy: TaxonomyRepository
) : ViewModel() {

    val message = MutableStateFlow<String?>(null)

    /** 提示是否为"错误"：错误用 errorContainer 配色且不自动消失，成功/中性提示 2.5 秒后自动收起。 */
    val messageIsError = MutableStateFlow(false)

    /**
     * 列表 + 计数。
     *
     * `observeTags()/observeGroups()` 是 Room 流，排序或删除后**自动**重发（界面不需要手动刷新）；
     * `tagsWithCount()/groupsWithCount()` 是挂起查询，所以跟着在 `mapLatest` 里重算。
     *
     * `scan` 的作用是**把上一轮的计数带过去**：排序会让 Room 重发、而计数要再查一次，
     * 中间那一瞬间若把 counts 清空，屏幕上会闪一下"关联 — 位主播"。保留旧值、只覆盖新算出的部分。
     *
     * 一侧计数查询失败时**只**退回该侧的旧值：`runCatching` 必须分别包住两次查询，
     * 包在外面的话分组查询失败会把已经查对的标签计数一起丢掉。
     *
     * `WhileSubscribed(5_000)`：页面离开 5 秒后停掉 Room 订阅，避免后台一直占着查询。
     */
    val state: StateFlow<TaxonomyUiState> =
        combine(taxonomy.observeTags(), taxonomy.observeGroups()) { tags, groups -> tags to groups }
            .mapLatest { (tags, groups) -> fetchCounts(tags, groups) }
            // ★ 不要给 scan 写显式类型参数：原先写的是 scan<TaxonomyUiState?, TaxonomyUiState>，
            //   类型参数顺序是 <累加器 R, 到达值 T>，于是"可空"落在了**到达值**上——
            //   next.tags 被当成"可空接收者"而编译失败。真正可空的是**字段**（每次只有一侧到达），
            //   累加器与到达值本身都是非空的，交给类型推断即可。
            .scan(TaxonomyUiState()) { prev, next ->
                // 两条流各自到达时只有一侧有值，另一侧为 null，表示"这一次没动这一侧"，沿用上一轮的值。
                TaxonomyUiState(
                    tags = next.tags ?: prev.tags,
                    groups = next.groups ?: prev.groups,
                    tagCounts = next.tagCounts ?: prev.tagCounts,
                    groupCounts = next.groupCounts ?: prev.groupCounts
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaxonomyUiState())

    /** 查询两段列表的关联主播数；失败的那一侧返回 null（由 scan 回填旧值），并把失败说出来。 */
    private suspend fun fetchCounts(
        tags: List<TagEntity>?,
        groups: List<GroupEntity>?
    ): TaxonomyUiState {
        val tagCounts = runCatching { taxonomy.tagsWithCount().associate { it.id to it.count } }
            .getOrElse { error ->
                messageIsError.value = true
                message.value = "读取标签的关联主播数失败：${error.message ?: error::class.simpleName}"
                null
            }
        val groupCounts = runCatching { taxonomy.groupsWithCount().associate { it.id to it.count } }
            .getOrElse { error ->
                messageIsError.value = true
                message.value = "读取分组的关联主播数失败：${error.message ?: error::class.simpleName}"
                null
            }
        return TaxonomyUiState(tags = tags, groups = groups, tagCounts = tagCounts, groupCounts = groupCounts)
    }

    /**
     * 上移/下移。`delta` 只用来算出"目标下标"，写库交给仓库的整表归一化，
     * 所以这里**不需要**知道任何一项当前的 sortOrder，也不会越界。
     * 下标越界（列表刚刷新、界面还拿着旧下标）由仓库当 no-op 处理，不抛异常。
     */
    fun moveTag(index: Int, delta: Int) = mutate("标签排序失败") { taxonomy.moveTag(index, index + delta) }

    fun moveGroup(index: Int, delta: Int) = mutate("分组排序失败") { taxonomy.moveGroup(index, index + delta) }

    fun deleteTag(tagId: Long) = mutate("删除标签失败") { taxonomy.deleteTag(tagId) }

    fun deleteGroup(groupId: Long) = mutate("删除分组失败") { taxonomy.deleteGroup(groupId) }

    private fun mutate(failureLabel: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    // 成功一般无需多话（列表会自己变），但删除后明确回一句，用户才知道真的删了
                    messageIsError.value = false
                    message.value = if (failureLabel.startsWith("删除")) "已删除" else null
                }
                .onFailure { error ->
                    messageIsError.value = true
                    message.value = "$failureLabel：${error.message ?: error::class.simpleName}"
                }
        }
    }

    /** 删除前把影响面查准：确认框每次打开都会调它，不复用列表里可能已过期的计数。 */
    suspend fun streamerCount(pending: PendingDelete): Int =
        if (pending.isTag) taxonomy.streamerCountOfTag(pending.id)
        else taxonomy.streamerCountOfGroup(pending.id)

    fun consumeMessage() {
        message.value = null
    }
}

/** 待确认的删除请求。**不含**关联主播数：那个数字由确认框打开时现查（见 [DeleteConfirmDialog]）。 */
data class PendingDelete(
    val isTag: Boolean,
    val id: Long,
    val name: String
)

/**
 * 标签 / 分组管理页。
 *
 * 结构：`Scaffold` + 浮动标题栏（返回箭头，与全项目其它二级页一致）
 * → 可滚动的一列：提示条 → 标签卡 → 分组卡。
 *
 * 为什么用 `Column + verticalScroll` 而不是 `LazyColumn`：两段内容都是"短列表"（标签/分组
 * 是用户自己维护的个位数到几十条），一次全量组合的代价可以忽略，换来的是**不用处理嵌套滚动**
 * ——两张卡各自是 `LazyColumn` 的话，各自的高度约束与滚动嵌套都会变成新的坑。
 */
@Composable
fun TaxonomyManageScreen(
    onBack: () -> Unit,
    viewModel: TaxonomyManageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isError by viewModel.messageIsError.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { FloatingTopBarTitle("标签 / 分组管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 排序/删除的结果必须可见（原规范 28）。错误不自动消失：失败了用户需要时间读完原因。
            MessageBanner(
                message = message,
                isError = isError,
                autoDismissMillis = if (isError) null else 2500L,
                onDismiss = { viewModel.consumeMessage() }
            )

            // 首次查询还没回来时转圈。注意判据是"两个列表都为 null"，
            // 而不是"都为空" —— 后者会让"确实一条都没有"也显示转圈（见 NotificationHistoryScreen 同款处理）。
            if (state.tags == null && state.groups == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            TaxonomySectionCard(
                title = "标签",
                emptyHint = "还没有标签。可以在主播详情页给主播添加标签，之后回到这里排序或删除。",
                items = state.tags.orEmpty().map { item ->
                    TaxonomyItem(item.id, item.name, state.tagCounts?.get(item.id))
                },
                unitLabel = "标签",
                onMove = { index, delta -> viewModel.moveTag(index, delta) },
                onDelete = { _, item -> pendingDelete = PendingDelete(isTag = true, id = item.id, name = item.name) }
            )

            TaxonomySectionCard(
                title = "分组",
                emptyHint = "还没有分组。可以在主播详情页把主播归入分组，之后回到这里排序或删除。",
                items = state.groups.orEmpty().map { item ->
                    TaxonomyItem(item.id, item.name, state.groupCounts?.get(item.id))
                },
                unitLabel = "分组",
                onMove = { index, delta -> viewModel.moveGroup(index, delta) },
                onDelete = { _, item -> pendingDelete = PendingDelete(isTag = false, id = item.id, name = item.name) }
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { pending ->
        DeleteConfirmDialog(
            pending = pending,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                if (pending.isTag) viewModel.deleteTag(pending.id) else viewModel.deleteGroup(pending.id)
            },
            // 复用页面这一份 ViewModel：hiltViewModel() 在这里会取到**同一个** NavBackStackEntry
            // 作用域下的实例，所以确认框里的现查与列表计数走的是同一套数据层口径。
            viewModel = viewModel
        )
    }
}

/** 列表项的最小渲染模型：id 用于 key 与回调，count 为 null 表示计数尚未就绪。 */
private data class TaxonomyItem(val id: Long, val name: String, val count: Int?)

/**
 * 一段（标签或分组）的卡片。
 *
 * 视觉与 `SettingsScreen.SettingSectionCard` 保持一致（`Card` + 卡片内 14dp 内边距 +
 * titleMedium 半粗标题），行内容用 `Surface` + `surfaceContainerHigh` + 14dp 圆角，
 * 与 `SettingsScreen.SettingsEntry` 同款 —— 这样它看起来就是设置页的自然延伸，
 * 而不是另一个 App 的页面。
 */
@Composable
private fun TaxonomySectionCard(
    title: String,
    emptyHint: String,
    items: List<TaxonomyItem>,
    unitLabel: String,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int, TaxonomyItem) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "顺序即筛选栏中的显示顺序；用 ↑ ↓ 调整，两端已到头。删除后不可恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                items.forEachIndexed { index, item ->
                    TaxonomyRow(
                        item = item,
                        position = index + 1,
                        total = items.size,
                        unitLabel = unitLabel,
                        // 边界就在这里表达：首项没有"上移"、末项没有"下移"，
                        // 按钮以 enabled=false 呈现（读屏会念"已停用"），而不是可点却没反应。
                        upEnabled = index > 0,
                        downEnabled = index < items.lastIndex,
                        onMoveUp = { onMove(index, -1) },
                        onMoveDown = { onMove(index, +1) },
                        onDelete = { onDelete(index, item) }
                    )
                }
            }
        }
    }
}

/**
 * 一行：名称 + 关联主播数 + 上移/下移/删除。
 *
 * 三个按钮的分工与边界表达：
 *  - 上移/下移用 `IconButton(enabled = ...)`，**首项禁用上移、末项禁用下移** ——
 *    禁用态会被读屏念成"已停用"，用户立刻知道到头了；比"能点但点了没反应"清楚得多
 *    （与 `SettingsScreen.StepperSetting` 的取舍一致）。
 *  - 删除独立放在最右、用 error 配色：它是破坏性操作，不该和排序按钮长得一样。
 */
@Composable
private fun TaxonomyRow(
    item: TaxonomyItem,
    position: Int,
    total: Int,
    unitLabel: String,
    upEnabled: Boolean,
    downEnabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                // 计数与位次都写出来：位次是给"排序"功能的即时反馈（也是给读屏用户的唯一顺序线索
                // —— 纯图标按钮不携带语义）；计数则是"这一项影响多大"的事前预告。
                Text(
                    buildString {
                        append(if (item.count == null) "关联 — 位主播" else "关联 ${item.count} 位主播")
                        append("　·　第 $position / $total 位")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMoveUp, enabled = upEnabled) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "上移$unitLabel「${item.name}」"
                )
            }
            IconButton(onClick = onMoveDown, enabled = downEnabled) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "下移$unitLabel「${item.name}」"
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除$unitLabel「${item.name}」",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 删除的二次确认。
 *
 * 文案必须**披露影响面**：删标签/分组不是"少一个名字"——关联行随外键级联清理，
 * 这些主播会立刻失去该标签/分组（首页筛选里也就再也筛不出来）。所以确认框给的是
 * "当前关联 N 位主播 + 后果"，而不是干巴巴一句"确定删除吗"。
 *
 * 那个 N 在**对话框打开时现查**（`LaunchedEffect`），不复用列表里那份快照：
 * 从列表渲染到用户点"删除"之间，别处完全可能刚改过关联关系，
 * 拿过期数字去描述影响面等于骗用户。
 *
 * `count == null` 期间显示"正在确认…"并**禁用**确认按钮 —— 宁可让用户多等一拍，
 * 也不能让他看着一句还没算出来的影响面就把东西删了（这个 null 与"关联 0 位"是两件事）。
 */
@Composable
private fun DeleteConfirmDialog(
    pending: PendingDelete,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    viewModel: TaxonomyManageViewModel
) {
    val kind = if (pending.isTag) "标签" else "分组"
    var count by remember(pending.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(pending.id) {
        // 查失败就留在 null：确认按钮保持禁用，用户会看到"正在确认…"而不是一个假的 0
        count = runCatching { viewModel.streamerCount(pending) }.getOrNull()
    }
    val actual = count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除$kind「${pending.name}」？") },
        text = {
            Text(
                if (actual == null) {
                    "正在确认「${pending.name}」当前关联的主播数……"
                } else {
                    "「${pending.name}」当前关联 $actual 位主播，删除后这些主播将不再带有该$kind" +
                        "；此操作无法恢复。"
                }
            )
        },
        confirmButton = {
            // 破坏性操作：确认键用 error 红，且必须等影响面查出来才可点
            TextButton(onClick = onConfirm, enabled = actual != null) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
