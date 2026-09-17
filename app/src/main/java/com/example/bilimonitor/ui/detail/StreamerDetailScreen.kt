package com.example.bilimonitor.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import com.example.bilimonitor.data.local.entity.StatusHistoryEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.local.entity.StreamerMonitorPolicyEntity
import com.example.bilimonitor.data.repository.StreamerRepository
import com.example.bilimonitor.domain.policy.RoomBanCodec
import com.example.bilimonitor.data.local.dao.LiveSessionDao
import com.example.bilimonitor.data.local.dao.StatusHistoryDao
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.streamerStatusLabel
import com.example.bilimonitor.ui.home.formatDuration
import com.example.bilimonitor.ui.home.formatTime
import com.example.bilimonitor.ui.home.zoneOf
import com.example.bilimonitor.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StreamerDetailState(
    val streamer: StreamerEntity? = null,
    /**
     * 该主播当前是否被封禁（由 [StreamerDetailViewModel.load] 从 `streamer.lastError` 解一次）。
     *
     * 与首页同一个纯函数口径（`ui.common.streamerStatusLabel`）：封禁主播的
     * `confirmedLiveStatus` 永远是 UNKNOWN，不带上这个标记，本页就会写着「待确认」——
     * 从首页点进来的用户会以为两页说的不是同一位主播的状态。
     * 默认 false = 未封禁，与 `RoomBanCodec` 的"解析不出来就是没封禁"一致。
     */
    val banned: Boolean = false,
    val policy: StreamerMonitorPolicyEntity? = null,
    val sessions: List<LiveSessionEntity> = emptyList(),
    val history: List<StatusHistoryEntity> = emptyList(),
    val tags: List<com.example.bilimonitor.data.local.entity.TagEntity> = emptyList(),
    /** 该主播已归属的分组。 */
    val groups: List<com.example.bilimonitor.data.local.entity.GroupEntity> = emptyList(),
    /**
     * 库里**已存在**的全部标签（与具体主播无关），供「这张卡片的标签」对话框勾选。
     *
     * 与 [tags] 的区别是刻意保留的：对话框要把 [tags]（该主播已归属的）当初始勾选状态，
     * 同时用 [allTags] 列出所有可选项 —— 只有前者就选不到别的标签，只有后者就不知道哪些已勾上。
     */
    val allTags: List<com.example.bilimonitor.data.local.entity.TagEntity> = emptyList(),
    /** 库里**已存在**的全部分组（与具体主播无关），供「这张卡片的分组」对话框选择。 */
    val allGroups: List<com.example.bilimonitor.data.local.entity.GroupEntity> = emptyList(),
    /**
     * [allTags] / [allGroups] 这两份**全局目录**是否已经从数据库回过一次值（内容为空也算回过）。
     *
     * 为什么要单独一个标志：目录订阅发出第一次值之前，两个列表同样是 emptyList()，
     * 与"库里确实一个都没有"在界面上完全一样。对话框的空态写的是"还没有任何分组"，
     * 目录一旦没加载出来，这句话就是**假话** —— 用户会以为自己的分组丢了
     * （本页真出过这个问题：load() 把这两份目录冲成空表，用户看到的正是"还没有任何分组"）。
     * 有这一个标志，对话框才能把"正在读取"与"确实一个都没有"分开说。
     */
    val taxonomyLoaded: Boolean = false,
    /**
     * 数据库**已经回过一次**且主播为空 = 这条记录不存在（或已被删除）。
     *
     * 为什么要单独一个字段：`observeById(id)` 对不存在的 id 会立刻发一次 null，
     * 而初始状态里 streamer 也是 null —— 两者在界面上完全一样，
     * 于是"点进一个已被删除的主播"（例如从旧通知深链进来）会永远转圈，
     * 用户既不知道是加载慢还是根本没这条记录。这里用"是否已经有过一次发射"把两种 null 分开。
     */
    val notFound: Boolean = false,
    val message: String? = null
)

/**
 * [StreamerDetailViewModel.load] 那四路查询的结果容器。
 *
 * 刻意**不复用** [StreamerDetailState] 来当中转：用整份 state 承载中间结果时，
 * 没被显式赋值的字段会静默退回声明处的默认值（`allTags = emptyList()`、`allGroups = emptyList()`），
 * 写回 `_state.value` 时就把「库里已有的全部标签 / 分组」清空了 ——
 * 详情页两个对话框里"找不到自己建过的标签 / 分组"就是这么来的。
 * 这个类只有四个字段，写回时漏掉任何一个都编译不过，同类事故不可能再悄悄发生。
 */
private data class LoadedCore(
    val streamer: StreamerEntity?,
    val policy: StreamerMonitorPolicyEntity?,
    val sessions: List<LiveSessionEntity>,
    val history: List<StatusHistoryEntity>
)

@HiltViewModel
class StreamerDetailViewModel @Inject constructor(
    private val streamerRepository: StreamerRepository,
    private val streamerDao: com.example.bilimonitor.data.local.dao.StreamerDao,
    private val policyDao: com.example.bilimonitor.data.local.dao.PolicyDao,
    private val taxonomyRepository: com.example.bilimonitor.data.repository.TaxonomyRepository,
    private val liveSessionDao: LiveSessionDao,
    private val statusHistoryDao: StatusHistoryDao,
    private val coverRepository: com.example.bilimonitor.data.repository.CoverRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StreamerDetailState())
    val state: StateFlow<StreamerDetailState> = _state

    private var currentId: Long = 0

    /**
     * 「库里已存在的标签 / 分组」是**全局目录**，与具体主播无关，所以只在 ViewModel 建立时订阅一次。
     *
     * 为什么用**订阅**而不是"打开对话框时查一次"：
     *  - 一次性查询只能挂在对话框的 `LaunchedEffect` 上，而对话框开合本身就是重组，
     *    很容易退化成"每次重组都查库"；订阅只在数据库真的变了时发一次值，
     *    打开对话框时目录一定已经是现成的。
     *  - 订阅让**别处**的改动（设置页的标签 / 分组管理）自动出现在本页对话框里，不用退出重进。
     * 不放进 [load]：load 用主播 id 闩锁，换主播时会再走一遍，而这两份目录永远只有一份。
     *
     * 顺便刷新"这张卡片已归属的标签 / 分组"：设置页删除标签时会级联删掉关联行，
     * 目录表本身也在变，所以这次发射正好也是发现"归属变了"的时机。
     * 但它**取代不了** [reloadTaxonomy]：取消勾选走的是
     * [com.example.bilimonitor.data.repository.TaxonomyRepository.unassign]，
     * 只删关联表里的行、两张目录表一行都没动 —— 目录流不会发射，只有 reloadTaxonomy 能刷新归属。
     */
    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                taxonomyRepository.observeTags(),
                taxonomyRepository.observeGroups()
            ) { tags, groups -> tags to groups }.collect { (tags, groups) ->
                val id = currentId
                // 先把要查的东西查完，再做"读旧状态 + 写新状态"这一步，中间不能有挂起点：
                // 若把 tagsOf/groupsOf 直接写在 `_state.value.copy(...)` 的参数里，接收者
                // `_state.value` 会在挂起**之前**就被求值，恢复后写回的是旧快照，
                // 正好会覆盖掉并发的 load() 结果 —— 与"列表为空"是同一个毛病的另一种写法。
                val myTags = if (id != 0L) taxonomyRepository.tagsOf(id) else null
                val myGroups = if (id != 0L) taxonomyRepository.groupsOf(id) else null
                val current = _state.value
                // 查询期间主播可能已经被切走（load() 换了 currentId）：上面查到的是**上一位**主播的
                // 归属，写回去会把新主播的归属盖成旧的，所以这种情况一律不覆盖归属。
                val sameStreamer = currentId == id
                _state.value = current.copy(
                    allTags = tags,
                    allGroups = groups,
                    taxonomyLoaded = true,
                    // id 还是 0 = 还没进具体主播页面，此时别去查归属：
                    // 查出来必然是空表，写回去会把 load() 刚取到的归属抹掉
                    tags = if (sameStreamer) myTags ?: current.tags else current.tags,
                    groups = if (sameStreamer) myGroups ?: current.groups else current.groups
                )
            }
        }
    }

    fun load(streamerId: Long) {
        // 只用 id 闩锁，不附加 `_state.value.streamer != null`：
        // 主播不存在（例如从旧通知深链点进已删除的主播）时那条数据永远不会到，
        // 附加条件会让每次重组都重新建一条 Room 管线，叠加成多条并行管线。
        if (currentId == streamerId) return
        currentId = streamerId
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                streamerDao.observeById(streamerId),
                policyDao.observe(streamerId),
                liveSessionDao.observeByStreamer(streamerId, 100),
                statusHistoryDao.observeByStreamer(streamerId, 30)
            ) { streamer, policy, sessions, history ->
                // 只装这四路真正查回来的东西，绝不整份重建 StreamerDetailState：
                // 那样 allTags / allGroups 会退回默认值 emptyList()，而下面这四张表
                // 被监控 tick 频繁写入，每发射一次就把对话框的候选列表清空一次。
                LoadedCore(streamer, policy, sessions, history)
            }.collect { core ->
                // 先查库（挂起点在写之前），再一次性赋值：把 tagsOf/groupsOf 写进
                // `_state.value.copy(...)` 的参数里会让接收者 `_state.value` 在挂起前求值，
                // 恢复后写回旧快照，同样会冲掉并发的目录订阅结果。
                val myTags = taxonomyRepository.tagsOf(streamerId)
                val myGroups = taxonomyRepository.groupsOf(streamerId)
                val current = _state.value
                _state.value = current.copy(
                    streamer = core.streamer,
                    // 封禁标记跟着**同一次发射**一起算：`observeById` 会在 `lastError` 变化时重新发射，
                    // 所以引擎解封清掉标记后，这个布尔值也会自己变回 false，不需要额外订阅。
                    // 解一次即可，界面不做任何 JSON 解析（同首页，见 HomeViewModel.bannedIds）。
                    banned = core.streamer?.let { RoomBanCodec.isBanned(it.lastError) } ?: false,
                    policy = core.policy,
                    sessions = core.sessions,
                    history = core.history,
                    // 能走到这里就说明数据库已经发过一次值：streamer 仍为 null 即"不存在"
                    notFound = core.streamer == null,
                    tags = myTags,
                    groups = myGroups
                )
            }
        }
    }

    fun addTag(name: String) {
        viewModelScope.launch {
            val id = currentId
            runCatching { taxonomyRepository.createAndAssign(name, id) }
                .onSuccess { reloadTaxonomy() }
                .onFailure { setMessage(it.message ?: "添加失败") }
        }
    }

    fun removeTag(tagId: Long) {
        viewModelScope.launch {
            runCatching { taxonomyRepository.unassign(tagId, currentId) }
                .onSuccess { reloadTaxonomy() }
                .onFailure { setMessage("移除失败：${it.message}") }
        }
    }

    /**
     * 应用「这张卡片的标签」对话框的勾选结果（多选）。
     *
     * 两个方向都走仓库现有方法，不为"从已有列表里选"另开一条旁路：
     *  - 取消勾选 → [com.example.bilimonitor.data.repository.TaxonomyRepository.unassign]
     *    （与卡片上「×」完全同一条路径）；
     *  - 勾选已有标签 **与** 新建标签 → 统一交给
     *    [com.example.bilimonitor.data.repository.TaxonomyRepository.createAndAssign]。
     *    它内部按**名字**查重并复用已有标签，所以"选中列表里那个标签"和"照着名字新建一个"
     *    落到同一行 `tag`、同一条关联记录；也正因如此这里只需要传名字，不用再加一个按 id 关联的入口。
     *    仓库的校验（名字非空）与去重因此对两条路都生效，不会被绕过。
     */
    fun applyTagSelection(removeTagIds: Set<Long>, assignNames: List<String>) {
        viewModelScope.launch {
            val id = currentId
            runCatching {
                removeTagIds.forEach { taxonomyRepository.unassign(it, id) }
                // distinct：勾选项和新建项可能是同一个名字，重复调用只会多插一次被 IGNORE 掉的关联行
                assignNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    .forEach { taxonomyRepository.createAndAssign(it, id) }
                reloadTaxonomy()
            }.onFailure { setMessage("保存标签失败：${it.message}") }
        }
    }

    /**
     * 重新读一次"该主播已归属的标签 / 分组"。
     *
     * 界面上这两个列表只在四路 combine（主播 / 策略 / 场次 / 状态历史）发射时才被一起读取，
     * 而那几张表跟标签、分组无关 —— 不主动重读的话，刚添加的标签要等下一次监控 tick
     * 改动了场次或状态历史才会出现在卡片上，用户会以为按钮没生效（原先用「×」移除后同样不刷新）。
     */
    private suspend fun reloadTaxonomy() {
        val id = currentId
        // 两次查询先做完再写：把 tagsOf/groupsOf 放进 copy(...) 的参数里会让接收者
        // `_state.value` 在挂起前就被求值，恢复后写回的是旧快照（见 init 里的同一条说明）。
        val myTags = taxonomyRepository.tagsOf(id)
        val myGroups = taxonomyRepository.groupsOf(id)
        // 查询期间主播可能已经被切走：这份归属属于上一位主播，写回去会把新主播的盖成旧的。
        // 直接丢掉这次刷新即可 —— 新主播的 load() 会自己把归属填上。
        if (currentId != id) return
        _state.value = _state.value.copy(tags = myTags, groups = myGroups)
    }

    // ---- 分组（原规范 16）----

    fun addGroup(name: String) {
        viewModelScope.launch {
            val id = currentId
            runCatching { taxonomyRepository.createGroupAndAssign(name, id) }
                .onSuccess { reloadTaxonomy(); setMessage("已加入分组：${it.name}") }
                .onFailure { setMessage(it.message ?: "加入分组失败") }
        }
    }

    fun removeGroup(groupId: Long) {
        viewModelScope.launch {
            runCatching { taxonomyRepository.unassignGroup(groupId, currentId) }
                .onSuccess { reloadTaxonomy() }
                .onFailure { setMessage("移出分组失败：${it.message}") }
        }
    }

    /**
     * 应用「这张卡片的分组」对话框的勾选结果（**多选**：一个主播可以同时归属多个分组）。
     *
     * 与「这张卡片的标签」的 [applyTagSelection] 同构：只处理**用户真正改动过**的那部分 ——
     * 取消勾选的才解除关联、勾选的才建立关联。刻意不写成"先清空该主播的全部分组再写回勾选项"：
     * 那样最终集合虽然一样，却会对没碰过的归属做一次删除 + 插入（关联表带外键、还会记审计日志），
     * 中途失败更会留下"分组全没了"的中间态。
     *
     * 落库路径全部是仓库现有方法，没有为对话框另写 SQL 或旁路：
     *  - [removeGroupIds] 取消勾选 → [com.example.bilimonitor.data.repository.TaxonomyRepository.unassignGroup]
     *    （与分组卡片上「×」完全同一条路径）；
     *  - [assignGroupIds] 勾选的已有分组 → [com.example.bilimonitor.data.repository.TaxonomyRepository.assignGroup]，
     *    按 **id** 关联：用户勾的就是列表里那一行，用名字绕一圈的话库里有同名分组时会落到另一行；
     *  - [newNames] 新名字 → [com.example.bilimonitor.data.repository.TaxonomyRepository.createGroupAndAssign]，
     *    它内部按名字查重并复用已有分组，所以"勾已有的"与"照名字新建"不会造出重复分组，
     *    仓库的非空校验与去重对两条路都生效。
     *
     * 注意这里**不再有**"把其余归属一并移出"的逻辑：多选语义下"没勾上"就等于"要移出"，
     * 意图只由 [removeGroupIds] 表达。上一版单选实现里那句"目标分组之外一律解除关联"的收尾
     * 必须彻底去掉 —— 留着会把用户在同一对话框里刚勾上的分组再逐个删掉
     * （多选会被它悄悄退化成单选，而且界面上看不出是谁删的）。
     */
    fun applyGroupSelection(removeGroupIds: Set<Long>, assignGroupIds: List<Long>, newNames: List<String>) {
        viewModelScope.launch {
            val id = currentId
            runCatching {
                // 改动前的归属：收尾提示要靠它算出"这次到底加了哪些、移出哪些"
                val beforeNames = _state.value.groups.associate { it.id to it.name }
                removeGroupIds.forEach { taxonomyRepository.unassignGroup(it, id) }
                assignGroupIds.forEach { taxonomyRepository.assignGroup(it, id) }
                // distinct：同一个名字不会重复建组，重复调用只会白跑一次事务
                newNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    .forEach { taxonomyRepository.createGroupAndAssign(it, id) }
                reloadTaxonomy()
                // 提示按**重新读库后的实际归属**算，而不是按传进来的参数算：
                // 新建的名字可能与库里已有分组同名而被复用，参数与结果并不总是一一对应。
                val afterNames = _state.value.groups.associate { it.id to it.name }
                val added = (afterNames.keys - beforeNames.keys).mapNotNull { afterNames[it] }
                val removed = (beforeNames.keys - afterNames.keys).mapNotNull { beforeNames[it] }
                // 一个都没变时不提示：避免用户点了「保存」但没动任何勾选也弹一句"已加入分组"
                val parts = mutableListOf<String>()
                if (added.isNotEmpty()) parts += "已加入分组：${added.joinToString("、")}"
                if (removed.isNotEmpty()) parts += "已移出分组：${removed.joinToString("、")}"
                if (parts.isNotEmpty()) setMessage(parts.joinToString("；"))
            }.onFailure { setMessage("保存分组失败：${it.message}") }
        }
    }

    fun toggleFavorite() {
        val s = _state.value.streamer ?: return
        viewModelScope.launch {
            // 与 HomeViewModel.toggleFavorite 保持一致：仓储抛错（数据库忙/磁盘满）时
            // 必须让用户看到失败，而不是静默什么都不发生 —— 否则界面上星星不动，
            // 用户只会以为"点了没反应"，反复点击。
            runCatching { streamerRepository.setFavorite(s.id, !s.isFavorite) }
                .onFailure { setMessage("收藏操作失败：${it.message}") }
        }
    }

    fun rename(name: String) {
        val s = _state.value.streamer ?: return
        viewModelScope.launch {
            runCatching { streamerRepository.setUserName(s.id, name) }
                .onSuccess { setMessage("昵称已更新（远程刷新不再覆盖）") }
                .onFailure { setMessage("修改失败：${it.message}") }
        }
    }

    fun refreshMetadata() {
        viewModelScope.launch {
            val ok = streamerRepository.refreshMetadata(currentId)
            setMessage(if (ok) "资料已刷新" else "刷新失败")
        }
    }

    /**
     * 进页面时如果还没有封面，自动拉一次。
     *
     * 封面靠监控 Tick 顺带刷新（最长一个检查间隔），首次进入时可能还是空的；
     * 这里补一次实时请求，省得用户以为"没有这个功能"。
     * 只在不为空时触发，避免每次进页面都多打一次接口。
     */
    private var autoRefreshedCover = false

    fun refreshCoverIfMissing() {
        val s = _state.value.streamer ?: return
        if (autoRefreshedCover || !s.coverUrl.isNullOrBlank()) return
        autoRefreshedCover = true
        viewModelScope.launch { runCatching { streamerRepository.refreshMetadata(currentId) } }
    }

    /** 保存当前直播间封面到本地相册（Pictures/主播监控）。 */
    fun saveCover() {
        viewModelScope.launch {
            val result = coverRepository.saveCover(currentId)
            setMessage(result.fold(
                onSuccess = { "封面已保存到相册：${it.relativePath}/${it.fileName}" },
                onFailure = { "保存失败：${it.message}" }
            ))
        }
    }

    fun softDelete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { streamerRepository.softDelete(currentId) }
                .onSuccess { onDeleted() }
                .onFailure { setMessage("删除失败：${it.message}") }
        }
    }

    fun updatePolicy(
        notifyStart: Boolean? = null,
        notifyEnd: Boolean? = null,
        startCount: Int? = null,
        endCount: Int? = null,
        intervalSeconds: Int? = null,
        notifyTitleChange: Boolean? = null,
        notifyAreaChange: Boolean? = null
    ) {
        viewModelScope.launch {
            runCatching {
                streamerRepository.updatePolicy(
                    currentId,
                    notifyStart = notifyStart,
                    notifyEnd = notifyEnd,
                    startConfirmationCount = startCount,
                    endConfirmationCount = endCount,
                    intervalSeconds = intervalSeconds,
                    notifyTitleChange = notifyTitleChange,
                    notifyAreaChange = notifyAreaChange
                )
            }.onSuccess { setMessage("策略已保存") }
                .onFailure { setMessage("保存失败：${it.message}") }
        }
    }

    fun setMessage(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

@Composable
fun StreamerDetailScreen(
    streamerId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    /**
     * 快捷入口（用户要求）：跳到**这个主播**的直播历史二级页。
     *
     * 为什么带 id（`(Long) -> Unit`）而不是无参 lambda：本项目所有"打开某个主播"的回调
     * 都是这个形状（HomeScreen / HistoryListScreen / StatsListScreen 的 onOpenStreamer），
     * 页面只上报"哪个主播"，路由由导航层拼 —— 这里跟着同一条规矩走，点的是谁写在参数上，
     * 导航层也就不必从组合作用域里"顺手"读一个 streamerId。
     *
     * **刻意不给默认值**：本项目出过"回调漏传 → 按钮看着能点、点下去没反应"的静默失效，
     * 没有默认值就能让漏传在编译期直接失败。
     */
    onOpenHistory: (Long) -> Unit,
    /** 同上，跳到这个主播的统计二级页。 */
    onOpenStats: (Long) -> Unit,
    viewModel: StreamerDetailViewModel = hiltViewModel()
) {
    androidx.compose.runtime.LaunchedEffect(streamerId) { viewModel.load(streamerId) }
    // 用 collectAsStateWithLifecycle：进后台后订阅会随生命周期解除，
    // 否则 Room 的四路 combine（场次 + 状态历史 + 策略）会在后台一直跑
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 主播数据到位后，若还没有封面就自动拉一次实时封面
    androidx.compose.runtime.LaunchedEffect(state.streamer?.stableId) {
        if (state.streamer != null) viewModel.refreshCoverIfMissing()
    }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    // 这两个对话框的状态提到屏幕顶层（原先声明在 LazyColumn 的 item 内）：
    // item 被回收时 remember 会丢失，用户滚动列表就会让正在输入的对话框凭空消失。
    var showGroupDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }


    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { Text(state.streamer?.name ?: "主播详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshMetadata() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新资料")
                    }
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        val fav = state.streamer?.isFavorite == true
                        Icon(
                            if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = "收藏",
                            tint = Color(0xFFE8A03C)
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            )
        }
    ) { padding ->
        val streamer = state.streamer
        if (streamer == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                // 加载失败等错误在主播数据为空时同样必须可见
                com.example.bilimonitor.ui.common.MessageBanner(
                    message = state.message,
                    onDismiss = { viewModel.clearMessage() }
                )
                // Box 是短名导入（本文件其它地方也这么用），这里顺手统一
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // notFound 与"还没加载出来"必须分开显示，否则点进已删除的主播会永远转圈
                    if (state.notFound) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "该主播不存在或已被删除",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onBack) { Text("返回") }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 操作反馈（原实现只有 message 状态、没有任何渲染点，导致失败信息全部丢失）
            com.example.bilimonitor.ui.common.MessageBanner(
                message = state.message,
                onDismiss = { viewModel.clearMessage() }
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            // 快捷入口（用户要求）：一键跳到**该主播**的直播历史 / 统计二级页。
            //
            // 为什么放在列表**第一项**（封面卡之前），而不是塞进封面卡或排在它后面：
            //  - 这两件事都是"从这张卡片跳出去看更多"，是导航动作，不是这张卡片的内容；
            //  - 封面卡本身很高（16:9 封面 + 头像行 + 分区 + 标题 + 进入直播间 ≈ 430dp），
            //    入口放进它内部或它后面，小屏与横屏就要滚动才看得见 —— 那就不叫"一键"了；
            //  - 单独一张卡、左右各占一半：最显眼、点击区域有整个按钮那么大（48dp 高），
            //    而且是**纯新增一行**，下面任何既有信息一格都没被挤掉。
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 文案用「直播历史」而不是 Dock 上那个「历史」：本页自己就有「直播记录」
                        // 与「状态历史（最近）」两个分区，只写"历史"根本分不清跳的是哪一个；
                        // 「直播统计」与它成对，含义与 Dock 的「统计」一致。
                        //
                        // 两个按钮都收窄左右内边距：M3 的 Button 默认左右各 24dp，半屏宽下
                        // 会挤掉文字的位置（历史页标题栏那两个按钮踩过同一个坑）。
                        //
                        // 图标一律 contentDescription = null：按钮文字已经把动作说清楚了，
                        // 再给图标挂一个描述，读屏会把同一个按钮念两遍。
                        //
                        // 两个按钮回传的 id 都是本页参数 streamerId（与 viewModel.load() 同一个值），
                        // 不另取 state.streamer.id：两个来源一旦分叉，按钮就会跳到另一位主播。
                        Button(
                            onClick = { onOpenHistory(streamerId) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("直播历史")
                        }
                        Button(
                            onClick = { onOpenStats(streamerId) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Icon(
                                Icons.Filled.BarChart,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("直播统计")
                        }
                    }
                }
            }

            item {
                Card {
                    // 直播间封面（用户要求：实时封面显示在二级页面，并支持保存到本地）。
                    // 16:9 比例、圆角裁切；无封面时给出明确的占位说明而不是空白。
                    val cover = streamer.coverUrl
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (!cover.isNullOrBlank()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = "直播间封面",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            // 状态角标：一眼看出封面是"正在直播"还是历史快照
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        // 封禁中同样走警示色（见 statusColorOf）：封面角标与头像行
                                        // 的状态必须一致，不然"封面写着封禁中、下面写着待确认"。
                                        statusColorOf(streamer.confirmedLiveStatus, state.banned)
                                            .copy(alpha = 0.85f)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    streamerStatusLabel(streamer.confirmedLiveStatus, state.banned),
                                    style = MaterialTheme.typography.labelSmall,
                                    // 未封禁时维持原来的白字（四种状态色都够深）；
                                    // 封禁时底色换成警示色，必须配 M3 的 onError ——
                                    // 深色主题下 error 是**浅**红，白字压上去几乎看不清。
                                    color = if (state.banned) MaterialTheme.colorScheme.onError
                                    else Color.White
                                )
                            }
                        } else {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("暂无直播间封面", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "点右上角 ⟳ 刷新资料即可获取当前封面",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "封面来自最近一次检查到的直播间画面",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { viewModel.saveCover() },
                            enabled = !cover.isNullOrBlank()
                        ) { Text("保存封面") }
                    }
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = streamer.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(streamer.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                "UID ${streamer.uid}" + (streamer.roomId?.let { " · 房间 $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                // 与首页同一个纯函数：封禁 ⇒「封禁中」，未封禁时与改动前逐字一致
                                streamerStatusLabel(streamer.confirmedLiveStatus, state.banned),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColorOf(streamer.confirmedLiveStatus, state.banned)
                            )
                        }
                        TextButton(onClick = { showRenameDialog = true }) { Text("改名") }
                    }
                    // 直播分区（用户要求：获取到的分区要显示在监控的二级页面）。
                    // 与历史页/导出同一口径：父分区·子分区；没有拿到时给一句说明而不是留空。
                    Row(
                        Modifier.padding(horizontal = 14.dp).padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "直播分区：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            liveAreaLabel(streamer.parentAreaName, streamer.areaName)
                                ?: "暂无（等待下一次检查）",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (liveAreaLabel(streamer.parentAreaName, streamer.areaName) != null)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (streamer.roomTitle != null) {
                        Text(
                            streamer.roomTitle!!,
                            Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // 跳转直播间（用户要求）：监控的二级页面直接给一个"进直播间"的入口。
                    //
                    // 地址来源与通知里用的是同一份数据（`persistRemoteMetadata` 每次成功观察
                    // 写回的 `liveUrl`，缺失时用 roomId 拼官方地址），因此不需要额外请求。
                    // 打开前一律过一遍 `UrlPolicy`（仅 https + bilibili 域名白名单）：
                    // 这个地址来自接口返回并存在本地库里，不能不加校验就交给系统去 startActivity。
                    LiveRoomEntry(
                        liveUrl = streamer.liveUrl,
                        roomId = streamer.roomId,
                        onMessage = { viewModel.setMessage(it) }
                    )
                }
            }

            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("分组", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        // 「+ 加入分组」固定在行首、不参与横向滚动：
                        // 分组数量没有上限，全塞进一行会把"+"挤出屏幕且无法滚到，
                        // 等于把入口锁死。现在左侧固定入口，右侧的已选分组可以横向滚动。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = { showGroupDialog = true }, label = { Text("+ 加入分组") })
                            if (state.groups.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    state.groups.forEach { g ->
                                        AssistChip(
                                            onClick = { viewModel.removeGroup(g.id) },
                                            label = { Text("${g.name} ×", style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }
                                }
                            }
                        }
                        if (state.groups.isEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "未加入任何分组；分组后可在首页按分组筛选",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { PolicyCard(state.policy, onChange = { start, end, ns, ne, interval, ntc, nac ->
                viewModel.updatePolicy(
                    startCount = start, endCount = end, notifyStart = ns, notifyEnd = ne,
                    intervalSeconds = interval, notifyTitleChange = ntc, notifyAreaChange = nac
                )
            }) }

            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        // 同上：入口固定在行首，标签多了也不会把「+ 添加标签」挤出去
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = { showTagDialog = true }, label = { Text("+ 添加标签") })
                            if (state.tags.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    state.tags.forEach { tag ->
                                        AssistChip(
                                            onClick = { viewModel.removeTag(tag.id) },
                                            label = { Text("${tag.name} ×", style = MaterialTheme.typography.labelMedium) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "直播记录",
                        // 文字居中（用户要求）：fillMaxWidth 让 Text 占满卡片宽度，
                        // 再由 textAlign 把文字摆到中线；只加 textAlign 而不撑满宽度是不生效的。
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (state.sessions.isEmpty()) {
                item { Text("暂无直播记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.sessions, key = { it.stableId }) { session ->
                    SessionRow(session)
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "状态历史（最近）",
                        // 文字居中（用户要求）：fillMaxWidth 让 Text 占满卡片宽度，
                        // 再由 textAlign 把文字摆到中线；只加 textAlign 而不撑满宽度是不生效的。
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
            items(state.history, key = { it.historyId }) { h ->
                Card {
                    Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${labelOf(h.oldStatus)} → ${labelOf(h.newStatus)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatTime(h.occurredAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除主播") },
            text = { Text("将停止监控该主播；历史直播记录会保留，可随时重新添加恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.softDelete(onDeleted)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }
    if (showRenameDialog) {
        var text by remember { mutableStateOf(state.streamer?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("修改昵称") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    if (text.isNotBlank()) viewModel.rename(text)
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

    // 「这张卡片的分组 / 标签」两个对话框放在**屏幕顶层**而不是 LazyColumn 的 item 里：
    // item 里的 remember 会随 item 被回收而丢失 —— 用户往下滚一点，
    // 正在输入的对话框就会凭空消失、已敲的内容也没了。这里与删除/改名对话框保持一致。
    //
    // 两个对话框共用同一个 [TaxonomyPickerDialog]，只有名词不同：同构是**代码层面**保证的，
    // 而不是"照着抄一遍"—— 用户在一处学会"上面勾归属、下面新建"，另一处看到的逐字相同。
    if (showGroupDialog) {
        // 勾选初值 = 打开这一刻这张卡片已归属的分组（多选：一个主播可以同时在多个分组里）。
        // remember 只活在这次 if 分支里：关闭即离开组合，下次打开重新取初值，
        // 上一次没点确认键的临时勾选不会残留到下一次。
        val initialIds = remember { state.groups.map { it.id }.toSet() }
        TaxonomyPickerDialog(
            noun = "分组",
            all = state.allGroups,
            idOf = { it.id },
            nameOf = { it.name },
            loaded = state.taxonomyLoaded,
            initiallyChecked = initialIds,
            onDismiss = { showGroupDialog = false },
            onConfirm = { removeIds, keptIds, newNames ->
                showGroupDialog = false
                // 取消勾选的传 id 去解除关联；勾选的已有分组按 id 关联（勾的就是列表里那一行）；
                // 新建名字交给 createGroupAndAssign（按名字复用已有分组）—— 三条路都是仓库现有方法。
                // 确认键刻意不加"至少勾一个"的限制：多选下"一个都不勾"就是"移出所有分组"，
                // 加了限制反而没法用这个对话框清空分组。
                viewModel.applyGroupSelection(
                    removeGroupIds = removeIds,
                    assignGroupIds = keptIds,
                    newNames = newNames
                )
            }
        )
    }
    if (showTagDialog) {
        // 与分组对话框逐字同构，只有名词不同：勾选初值 = 打开这一刻这张卡片已有的标签
        val initialIds = remember { state.tags.map { it.id }.toSet() }
        TaxonomyPickerDialog(
            noun = "标签",
            all = state.allTags,
            idOf = { it.id },
            nameOf = { it.name },
            loaded = state.taxonomyLoaded,
            initiallyChecked = initialIds,
            onDismiss = { showTagDialog = false },
            onConfirm = { removeIds, keptIds, newNames ->
                showTagDialog = false
                // 取消勾选的传 id 去解除关联；勾选的已有标签与新建名字都按名字交给
                // createAndAssign 同一套仓库方法（它按名字复用已有标签），
                // 因此"选已有"和"新建"落库路径完全一致，不存在绕过校验/去重的旁路。
                viewModel.applyTagSelection(
                    removeTagIds = removeIds,
                    assignNames = state.allTags.filter { it.id in keptIds }.map { it.name } + newNames
                )
            }
        )
    }
}

/**
 * 「这张卡片的分组 / 标签」对话框。[noun] 是两处**唯一**的差别（"分组" / "标签"），
 * 所以两个对话框是逐字同构的：用户在一处学会"上面勾归属、下面新建"，另一处完全一样。
 *
 * 为什么重做：上一版把两件事混在一个列表里 —— 标题写「加入分组」，列表里既列已有分组
 * （勾选 = 归属），又把"待新建"的名字塞进同一个列表、还配了一个恒为勾选的复选框。
 * 用户在同一个列表里分不清"我在选已有的"还是"我在造一个新的"，也看不出这个对话框
 * 管的是"这张卡片"还是"整个库"。现在按**行为**分区，两个分区各有自己的小标题：
 *   ① 归属：勾选列表（主体，加入与移出都由它表达）；
 *   ② 新建：独立分区 + 输入行 + 待新建 chip（次要，并明确说明"保存时才真正创建"）。
 *
 * @param noun 「分组」/「标签」，只用于拼文案。
 * @param all 库里已存在的全部条目，来自 ViewModel 的 Room 订阅（别处改了会自动跟上）。
 * @param idOf / nameOf 取条目的 id 与名字：两个实体类型不同，用取值函数代替复制两份实现。
 * @param loaded 全局目录是否已经回过一次值 —— 用来区分"正在读取"与"确实一个都没有"。
 *   没有它的话，目录还没加载出来时"还没有任何分组"这句空态就是**假话**，
 *   用户会以为自己的分组丢了（本页真出过这个 bug）。
 * @param initiallyChecked 这张卡片当前已归属的 id，作为勾选初值。
 * @param onConfirm (要移出的 id, 勾选保留的已有 id, 要新建的名字)。
 */
@Composable
private fun <T> TaxonomyPickerDialog(
    noun: String,
    all: List<T>,
    idOf: (T) -> Long,
    nameOf: (T) -> String,
    loaded: Boolean,
    initiallyChecked: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (removeIds: Set<Long>, assignIds: List<Long>, newNames: List<String>) -> Unit
) {
    // 这几份状态只活在对话框这一轮组合里：关闭即离开组合，下次打开重新取初值，
    // 上一次没点确认键的临时勾选 / 临时新建都不会残留（与删除、改名对话框同一处理）。
    var checkedIds by remember { mutableStateOf(initiallyChecked) }
    // 这次要新建的名字：确认时与勾选项一起落库；在②里以 chip 展示，点一下即撤销
    var newNames by remember { mutableStateOf(listOf<String>()) }
    var input by remember { mutableStateOf("") }
    // 「新建」按钮：把输入框里的名字收进「待新建」
    val submitInput: () -> Unit = {
        val name = input.trim()
        val existing = all.firstOrNull { nameOf(it) == name }
        // 与已有条目重名时**不新建**，直接把已有的那一个勾上 ——
        // 否则列表里会出现两个同名条目，落库时又会被 createAndAssign / createGroupAndAssign
        // 合并成一个，界面显示与实际结果对不上。
        if (existing != null) checkedIds = checkedIds + idOf(existing)
        else if (name.isNotEmpty() && name !in newNames) newNames = newNames + name
        input = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // 标题点名"这张卡片"：既交代作用域（只管这一个主播，不是整个库），
        // 也说明它同时管加入与移出。不叫「管理分组」是因为设置页已有一个全局的
        // 标签 / 分组管理入口，两个不同的东西同名只会更晕。
        title = { Text("这张卡片的${noun}") },
        text = {
            Column {
                // ---- ① 归属（主体）----
                Text(
                    "归属：这张卡片在哪些${noun}里",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "勾选即加入，取消勾选即移出；可以同时选多个。改动要保存后才生效，「取消」不做任何改动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                when {
                    // 目录还没回过值：宁可说"正在读取"，也不能说"一个都没有"（那是假话）
                    !loaded -> Text(
                        "正在读取已创建的${noun}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    all.isEmpty() -> Text(
                        "还没有任何${noun}，可在下面「新建${noun}」里创建第一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        // heightIn(max) 而不是写死高度：AlertDialog 的 text 槽本身不滚动，
                        // 条目多了会把下面的新建输入框和按钮一起顶出可视区（与 ExportDialogs 同一处理）。
                        LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(all, key = { idOf(it) }) { item ->
                                // 勾选 = 这次要归到它；取消勾选 = 这次要从它移出（"一个都不勾"= 全部移出）
                                val id = idOf(item)
                                Row(
                                    Modifier.fillMaxWidth().clickable { checkedIds = checkedIds.toggle(id) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = id in checkedIds,
                                        onCheckedChange = { checkedIds = checkedIds.toggle(id) }
                                    )
                                    Text(nameOf(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                // ---- ② 新建（次要，独立分区）----
                Text(
                    "新建${noun}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "输入名字后点「新建」，它会加进上面的归属，并在保存时创建；" +
                        "同名会复用已有的${noun}，不会重复创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        singleLine = true, label = { Text("新${noun}名") },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = submitInput, enabled = input.isNotBlank()) { Text("新建") }
                }
                if (newNames.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "待新建（点右侧的 × 即可移除）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        newNames.forEach { name ->
                            AssistChip(
                                onClick = { newNames = newNames - name },
                                label = { Text("$name ×", style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }
        },
        // 确认键的文案与**这次真正会发生的事**一致：只动了勾选就是「保存更改」，
        // 排了待新建条目就是「保存并新建」（新建与归属一起落库）。
        // 不写死成"保存卡片到分组"这类只覆盖一半动作的措辞 —— 那正是用户看不出在做什么的原因。
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    initiallyChecked - checkedIds,
                    all.filter { idOf(it) in checkedIds }.map { idOf(it) },
                    newNames
                )
            }) { Text(if (newNames.isEmpty()) "保存更改" else "保存并新建") }
        },
        // 取消 = 什么都不做：临时勾选与"待新建"都随对话框离开组合而被丢掉
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 勾选 / 取消勾选一个 id（[TaxonomyPickerDialog] 里"整行可点"与"复选框"共用：标签 id、分组 id 都是 Long）。
 *
 * 抽成一处是因为这两个手势必须触发同一次切换：
 * 两处各抄一遍 `if (id in set) set - id else set + id`，一旦写歪就是
 * "点行没反应、只有点小方框才行"这种最难排查的交互 bug，且两处行为还会漂移。
 */
private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

/**
 * 分区展示文案：与 `MonitorRepository.areaLabel()` / 历史页 / 导出**同一口径**（父·子）。
 * 三处各写一套拼接逻辑迟早会不一致（一处用"·"、一处用"/"），这里集中一次。
 */
private fun liveAreaLabel(parentAreaName: String?, areaName: String?): String? {
    val parent = parentAreaName?.takeIf { it.isNotBlank() }
    val child = areaName?.takeIf { it.isNotBlank() }
    return when {
        parent != null && child != null -> "$parent·$child"
        child != null -> child
        parent != null -> parent
        else -> null
    }
}

@Composable
private fun PolicyCard(
    policy: StreamerMonitorPolicyEntity?,
    onChange: (
        startCount: Int?, endCount: Int?, notifyStart: Boolean?, notifyEnd: Boolean?,
        intervalSeconds: Int?, notifyTitleChange: Boolean?, notifyAreaChange: Boolean?
    ) -> Unit
) {
    var notifyStart by remember(policy?.streamerId, policy?.updatedAt) {
        mutableStateOf(policy?.notifyStart ?: true)
    }
    var notifyEnd by remember(policy?.streamerId, policy?.updatedAt) {
        mutableStateOf(policy?.notifyEnd ?: true)
    }
    // 标题/分区变化通知：**默认关闭**（用户定稿）。直播中改标题可能很频繁，
    // 默认打开就是骚扰；必须是用户显式选择。
    var notifyTitleChange by remember(policy?.streamerId, policy?.updatedAt) {
        mutableStateOf(policy?.notifyTitleChange ?: false)
    }
    var notifyAreaChange by remember(policy?.streamerId, policy?.updatedAt) {
        mutableStateOf(policy?.notifyAreaChange ?: false)
    }
    Card {
        Column(Modifier.padding(14.dp)) {
            Text("监控策略", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开播通知", Modifier.weight(1f))
                Switch(checked = notifyStart, onCheckedChange = {
                    notifyStart = it
                    onChange(null, null, it, null, null, null, null)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("关播通知", Modifier.weight(1f))
                Switch(checked = notifyEnd, onCheckedChange = {
                    notifyEnd = it
                    onChange(null, null, null, it, null, null, null)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("直播标题变化通知")
                    Text(
                        "直播中改标题时通知我（默认关闭）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = notifyTitleChange, onCheckedChange = {
                    notifyTitleChange = it
                    onChange(null, null, null, null, null, it, null)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("直播分区变化通知")
                    Text(
                        "直播中换分区时通知我（默认关闭）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = notifyAreaChange, onCheckedChange = {
                    notifyAreaChange = it
                    onChange(null, null, null, null, null, null, it)
                })
            }
        }
    }
}

/**
 * "进入直播间"入口（用户要求）。
 *
 * **打开优先级：哔哩哔哩客户端 → 系统默认处理（浏览器）**：
 *  1. 先用 `bilibili://live/<roomId>` 深链唤起客户端。用 **scheme 而不是硬编码包名**
 *     （`tv.danmaku.bili`）：scheme 由应用自己注册、由系统解析，换包名/多版本共存都不会失效，
 *     也不需要我们在 manifest 里声明 `<queries>`（Android 11+ 的包可见性会让"先查询再启动"漏判）。
 *  2. 没装客户端时 `startActivity` 抛 `ActivityNotFoundException`，此时再退到
 *     https 地址（系统会交给浏览器，装了客户端且声明了 App Links 也会直接进客户端）。
 *
 * 两个刻意的选择：
 *  - 不用"先 `resolveActivity` 判断能不能打开"：包可见性限制下它可能返回 null 而实际能打开；
 *    直接尝试 + 捕获异常是唯一在所有版本上都正确的做法。
 *  - 地址一律过 [com.example.bilimonitor.notify.UrlPolicy]（仅 https + bilibili 域名白名单）：
 *    这个 URL 来自接口返回并存在本地数据库里，属于**数据**而不是可信输入；
 *    深链里的房间号同样要求是纯数字，不允许把库里的任意字符串拼进去。
 */
@Composable
private fun LiveRoomEntry(liveUrl: String?, roomId: Long?, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    // 深链用的房间号：优先用库里的 roomId，其次从 liveUrl 里解析（例如 .../live.bilibili.com/21692711）
    val deepLinkRoomId = roomId ?: roomIdFromUrl(liveUrl)
    // https 兜底地址：优先用接口给的，没有就用房间号拼官方地址
    val safeUrl = com.example.bilimonitor.notify.UrlPolicy.validate(
        liveUrl?.takeIf { it.isNotBlank() } ?: deepLinkRoomId?.let { "https://live.bilibili.com/$it" }
    )
    val canOpen = deepLinkRoomId != null || safeUrl != null

    Row(
        Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                // ① 优先唤起哔哩哔哩客户端
                if (deepLinkRoomId != null) {
                    val openedInApp = runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("bilibili://live/$deepLinkRoomId")
                            )
                        )
                    }
                    if (openedInApp.isSuccess) return@Button
                    // 失败通常是"未安装客户端"，也可能是 scheme 未被注册；
                    // 记一条日志便于排查，然后继续走浏览器，不打扰用户。
                    android.util.Log.i(
                        "StreamerDetail",
                        "哔哩哔哩深链打开失败，改用浏览器：${openedInApp.exceptionOrNull()?.message}"
                    )
                }
                // ② 退回 https（浏览器；若客户端声明了 App Links，系统也会直接进客户端）
                if (safeUrl != null) {
                    val openedInBrowser = runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(safeUrl)
                            )
                        )
                    }
                    if (openedInBrowser.isFailure) {
                        onMessage("打不开直播间：${openedInBrowser.exceptionOrNull()?.message ?: safeUrl}")
                    }
                } else {
                    onMessage("没有找到可以打开直播间的应用（未安装哔哩哔哩，也没有可用浏览器）")
                }
            },
            enabled = canOpen
        ) { Text("进入直播间") }
        if (!canOpen) {
            Spacer(Modifier.width(10.dp))
            Text(
                "尚未获取到直播间地址，等下一次检查后再试",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 从直播间地址里取房间号：只接受**纯数字**的最后一段
 * （`https://live.bilibili.com/21692711`、`.../h5/21692711` 都能取到；其余一律返回 null）。
 *
 * 之所以严格要求纯数字：这个值会被拼进 `bilibili://live/<id>` 交给系统打开，
 * 而它来自本地数据库 —— 不允许把任意字符串变成一次外部跳转。
 */
private fun roomIdFromUrl(url: String?): Long? {
    val last = url?.trim()?.trimEnd('/')?.substringAfterLast('/') ?: return null
    return last.toLongOrNull()?.takeIf { it > 0 }
}

@Composable
private fun SessionRow(session: LiveSessionEntity) {
    // 场次时间按该场记录的时区显示（库里存了 startTimeZone），
    // 与历史页保持同一口径；为 null 或解析失败时 zoneOf 回退设备时区
    val sessionZone = remember(session.startTimeZone) { zoneOf(session.startTimeZone) }
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatTime(session.startTime, sessionZone)} 开始",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                // 只保留"进行中"这一条有实际含义的状态；
                // 「开播/关播是否人工修正」的标签已按用户要求删除（没人看，还挤占版面）。
                if (session.endTime == null) {
                    Text(
                        "进行中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                "${formatTime(session.endTime, sessionZone)} 结束 · 时长 ${formatDuration(session.durationSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 本页状态标签的**颜色**（封面角标 + 头像行两处共用）。
 *
 * 必须读 `MaterialTheme` 才能拿到警示色，所以是 @Composable：
 * 文案（纯规则）在 `ui.common.streamerStatusLabel` 里、由单测逐字锁定，颜色留在这里。
 */
@Composable
private fun statusColorOf(status: ConfirmedLiveStatus, banned: Boolean) = when {
    // 封禁中走主题的警示色：与首页卡片同一口径（见 HomeScreen.statusColor 的说明）——
    // 按确认状态取色只会得到绿/橙/灰，都读作"正常"，分不出"这个房间被封了"。
    banned -> MaterialTheme.colorScheme.error
    else -> when (status) {
        ConfirmedLiveStatus.LIVE -> StatusColors.Live
        ConfirmedLiveStatus.ROUND -> StatusColors.Round
        ConfirmedLiveStatus.OFFLINE -> StatusColors.Offline
        ConfirmedLiveStatus.UNKNOWN -> StatusColors.Unknown
    }
}

private fun labelOf(status: ConfirmedLiveStatus): String = when (status) {
    ConfirmedLiveStatus.LIVE -> "直播"
    ConfirmedLiveStatus.ROUND -> "轮播"
    ConfirmedLiveStatus.OFFLINE -> "下播"
    ConfirmedLiveStatus.UNKNOWN -> "未知"
}
