package com.example.bilimonitor.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.background.MonitoringController
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.entity.MonitoringConfigEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.repository.AddStreamerResult
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.data.repository.GroupWithCount
import com.example.bilimonitor.data.repository.HealthRepository
import com.example.bilimonitor.data.repository.InputMode
import com.example.bilimonitor.data.repository.OverallHealth
import com.example.bilimonitor.data.repository.StreamerRepository
import com.example.bilimonitor.data.repository.TagWithCount
import com.example.bilimonitor.domain.policy.RoomBanCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    /**
     * 只表示"首帧数据还没到"。
     *
     * 切换筛选/敲字时列表流会保留上一次的值，界面不会出现中间态的空列表，
     * 所以不该在这里再举一次 loading —— 那只会让列表闪成转圈。真正的空态区分
     * 交给 [query] / [filter] / 标签 / 分组，见 HomeScreen 的空态文案。
     */
    val loading: Boolean = true,
    val query: String = "",
    val filter: HomeFilter = HomeFilter.ALL,
    val streamers: List<StreamerEntity> = emptyList(),
    val config: MonitoringConfigEntity? = null,
    val health: OverallHealth? = null,
    val message: String? = null,
    /** 已选中、且**当前筛选下可见**的主播 id（筛选一变就跟着收敛，见 [HomeViewModel.pruneSelection]） */
    val selectedIds: Set<Long> = emptySet(),
    val tags: List<TagWithCount> = emptyList(),
    /**
     * 已选中的标签 id 集合（用户定稿：标签行同级多选，多选之间取**交集/AND**）。
     *
     * 用 Set 而不是 List：每一项都是纯粹的"开 / 关"，重复值没有意义，
     * toggle 与"是否选中"各自只要一行。空集 = 不限标签（与上一版的 `tagFilterId == null` 同义）。
     * 名字刻意与 [selectedIds] 区分开：那个是批量操作选中的**主播**，
     * 这个只管筛选；两者混用的后果是"筛出来的列表"和"批量操作的对象"对不上。
     */
    val tagFilterIds: Set<Long> = emptySet(),
    val groups: List<GroupWithCount> = emptyList(),
    val groupFilterId: Long? = null,
    /**
     * 卡片上要显示的标签：streamerId -> 标签名（已按标签表自身的展示顺序排好）。
     *
     * 由 ViewModel **一次性批量**取回（见 [HomeViewModel.reloadStreamerTags]），卡片只做展示：
     * 让每张卡片各自去查一次库会变成 N+1 查询，而首页列表本身就会频繁重组。
     */
    val streamerTags: Map<Long, List<String>> = emptyMap(),
    /**
     * 当前列表里**被封禁**的主播 id，卡片据此把状态标签显示成「封禁中」
     * （文案口径见 `ui.common.streamerStatusLabel`）。
     *
     * **为什么在这里解、而不是让 Compose 每次重组解**：列表会随监控 tick 频繁发射，
     * 重组的次数远多于数据变化的次数；放在这里，每轮列表更新只解一次 `streamer.lastError`，
     * 界面只拿到一个布尔值（`streamer.id in bannedIds`），不做任何 JSON 解析。
     * 判定复用 `RoomBanCodec.isBanned` —— 解析失败 / 无前缀 / null 一律 false
     * （宁可少算也不虚报，这条契约由 `StreamerStatusLabelTest` 的用例守着）。
     *
     * **为什么是并列的 id 集合，而不是给 [streamers] 换一种行模型**：`uiState` 那个 combine
     * 已经是 5 路（Kotlin 具名 combine 重载的上限），本字段是在**既有变换里**从已经拿到的列表
     * 派生出来的 —— 没有新增 combine 路数，也不动行类型，其余调用点
     * （全选 / 筛选 / 标签映射）一行都不用改。
     */
    val bannedIds: Set<Long> = emptySet()
)

enum class HomeFilter(val label: String) {
    ALL("全部"), LIVE("直播中"), OFFLINE("未开播"), FAVORITE("收藏")
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val streamerRepository: StreamerRepository,
    private val configRepository: ConfigRepository,
    private val monitoringController: MonitoringController,
    private val taxonomyRepository: com.example.bilimonitor.data.repository.TaxonomyRepository,
    private val appearanceRepository: com.example.bilimonitor.data.repository.AppearanceSettingsRepository,
    /** 进程被杀后重建时用来恢复首页自己的界面状态（目前是筛选区是否展开） */
    private val savedStateHandle: SavedStateHandle,
    healthRepository: HealthRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    /**
     * 首页筛选区是否展开（用户要求：记住上次离开时的状态）。
     *
     * 真值存在 [SavedStateHandle] 里：原来只用 mutableStateOf，进程被系统回收后重建就退回
     * 默认展开，"记住状态"只在 Activity 还活着时成立。这里另外保留一份 Compose State 做镜像，
     * 是因为 HomeScreen 直接读 `viewModel.filterExpanded` —— SavedStateHandle 是普通容器、
     * 不是可观察对象，只读写它的话开关筛选区不会触发重组。
     */
    private val filterExpandedState = androidx.compose.runtime.mutableStateOf(
        savedStateHandle.get<Boolean>(KEY_FILTER_EXPANDED) ?: true
    )
    val filterExpanded: Boolean get() = filterExpandedState.value

    fun setFilterExpanded(value: Boolean) {
        filterExpandedState.value = value
        savedStateHandle[KEY_FILTER_EXPANDED] = value
    }

    /**
     * 首页标题用的应用名（用户可在设置里自定义）。
     *
     * 单独一条流而不是塞进 [uiState]：那个 combine 已经是 5 路（Kotlin 具名重载的上限），
     * 为一个展示字符串去动它、或者再嵌一层，风险与收益不成比例。
     */
    val appName: StateFlow<String> = appearanceRepository.flow
        .map { it.name }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_NAME
        )

    /**
     * 「开启数据过期提示」开关（高级设置里可关，**默认开启**）。
     *
     * 与 [appName] 完全同一条理由单独成流：`uiState` 那个 combine 已经是 5 路
     * （Kotlin 具名 combine 重载的上限），为一个展示开关去动它、或者再嵌一层，
     * 风险与收益不成比例。
     *
     * 它只影响**展示**：关掉之后首页卡片不再标「· 数据过期」，但时间行照常显示
     * （用户要求"仍显示时间"），监控与落库完全不受影响。
     */
    val freshnessExpiryHintEnabled: StateFlow<Boolean> = appearanceRepository.flow
        .map { it.freshnessExpiryHintEnabled }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.example.bilimonitor.data.repository.AppearanceSettingsRepository
                .DEFAULT_FRESHNESS_EXPIRY_HINT
        )

    /**
     * 当前底部导航样式（DOCK 悬浮圆角栏 / NORMAL 贴底整条）。
     *
     * 首页要用它算"贴底元素抬多高"：DOCK 是浮层（栏体约 60dp，上下再各留 26dp），
     * NORMAL 是贴底的 NavigationBar（约 80dp）—— 一个数字不可能同时贴住两种样式，
     * 用一个折中值必然在其中一种下悬空或压住栏体。同样是单独一条流，不动那个 combine。
     */
    val navigationStyle: StateFlow<com.example.bilimonitor.data.repository.NavigationStyle> =
        appearanceRepository.flow
            .map { it.navigationStyle }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.example.bilimonitor.data.repository.NavigationStyle.DOCK
            )

    private val filter = MutableStateFlow(HomeFilter.ALL)
    private val message = MutableStateFlow<String?>(null)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val tagFilterIds = MutableStateFlow<Set<Long>>(emptySet())
    private val tags = MutableStateFlow<List<TagWithCount>>(emptyList())
    private val groupFilterId = MutableStateFlow<Long?>(null)
    private val groups = MutableStateFlow<List<GroupWithCount>>(emptyList())

    /**
     * 标签 / 分组目录"是否已经**真正读到过一次库**"。
     *
     * 这两个标志专门用来区分**空目录**的两种完全不同的含义（收敛逻辑见 [pruneTagFilter]）：
     *  - false + 空目录 = 数据库还没读回来（[tags] / [groups] 的构造期占位值），**不能**据此清筛选 ——
     *    冷启动时若照"目录里没有它"处理，会把用户刚选好的筛选一起抹掉；
     *  - true + 空目录 = 库里确实一个都不剩（管理页把最后一个标签 / 分组删了），**必须**清：
     *    筛选行整行都是按"目录非空"决定要不要显示的（HomeFilterCard 的 visible 参数），
     *    留着悬空 id 会让列表恒为空，而且连「全部标签」都点不到，用户没有任何自救手段。
     *
     * 这个区分之所以站得住：用户点得出选择的前提是 chips 已经渲染过，而 chips 只在目录非空时渲染，
     * 于是恒有"筛选非空 ⇒ 标志必为 true"，据此收敛不会误伤冷启动那次空目录。
     */
    private var tagCatalogLoaded = false
    private var groupCatalogLoaded = false

    /**
     * 目录的**唯一写入口**（init / [refreshTags] / [reloadStreamerTags] 三处写目录都走它）。
     *
     * 先置位、再发布值：收集侧是另一条协程，"看到目录值"与"标志已置位"之间没有任何同步点，
     * 顺序反过来就可能出现"值到了、标志还没到"，那次收敛会被当成首帧空目录而跳过（或反之误判）。
     * 两条协程其实同在主线程上、不会真的交错，但把置位写在前面成本是零，
     * 将来谁把 [tags] 的收集挪到别的调度器上也依然成立。
     */
    private fun setTagCatalog(catalog: List<TagWithCount>) {
        tagCatalogLoaded = true
        tags.value = catalog
    }

    private fun setGroupCatalog(catalog: List<GroupWithCount>) {
        groupCatalogLoaded = true
        groups.value = catalog
    }

    /**
     * 卡片要显示的标签（streamerId -> 标签名）。
     *
     * 存的是**批量**结果而不是"每张卡片自己去查"：唯一的写入口是 [reloadStreamerTags]，
     * 于是"卡片上的标签"只有一个口径，不会出现某些卡片用的是旧数据。
     */
    private val streamerTags = MutableStateFlow<Map<Long, List<String>>>(emptyMap())

    init {
        viewModelScope.launch {
            setTagCatalog(taxonomyRepository.tagsWithCount())
            setGroupCatalog(taxonomyRepository.groupsWithCount())
        }

        // 目录一变，就把"正被用于筛选"的 id 收敛到目录里仍然存在的那些上：标签求交集、
        // 分组不在目录里就置空（见 [pruneTagFilter] / [pruneGroupFilter]）。
        //
        // 为什么挂在**目录流**上，而不是挂在"删除"这个动作上：
        // 删除发生在「标签 / 分组管理」页，首页与它之间唯一的通道就是 [refreshTags]
        // （HomeScreen 的 LifecycleResumeEffect 在回到首页时调它）重新读回来的目录 ——
        // 首页根本看不到"用户点了删除"这件事，只能看到目录变了。挂在流上还顺带覆盖了
        // 详情页新建标签触发的目录兜底重读（[reloadStreamerTags] 里那次），
        // 将来再加新的目录写入口也自动被覆盖，不必回来补一行。
        //
        // [tagCatalogLoaded] / [groupCatalogLoaded] 把"首帧占位空目录"挡在外面，
        // 否则冷启动那一帧会按"目录里什么都没有"清掉用户在进程内已经选好的筛选。
        viewModelScope.launch {
            tags.collect { catalog -> if (tagCatalogLoaded) pruneTagFilter(catalog) }
        }
        viewModelScope.launch {
            groups.collect { catalog -> if (groupCatalogLoaded) pruneGroupFilter(catalog) }
        }
    }

    private val streamers = combine(query, filter, tagFilterIds, groupFilterId) { q, f, tagIds, groupId ->
        Quad(q, f, tagIds, groupId)
    }
        .flatMapLatest { (q, f, tagIds, groupId) ->
            // 标签 / 分组 / 关键词三者同时生效：原实现在选中标签后走不带 query 的分支，
            // 搜索框仍可见可输入但完全不起作用。
            //
            // 分组与标签也不再二选一：上一版是"选中标签就把分组清掉"，于是两个维度在查询层
            // 根本不可能共存。用户定稿的语义是 **分组 ∧ 标签 ∧ 关键词**，标签多选之间再取
            // 交集（requireAll = true），所以这里按"此刻哪几个条件在场"组合 ——
            // 四个分支互不覆盖，任意组合都有对应查询，都不在场时才退回全量。
            // 空集 = 不限标签（同名参数的 SQL 空 `IN ()` 语法坑由仓储兜底，
            // 见 StreamerRepository.observeByTags，**不要绕过仓储直接调 DAO**）。
            val base = when {
                groupId != null && tagIds.isNotEmpty() ->
                    streamerRepository.observeByGroupAndTags(groupId, tagIds.toList(), q, requireAll = true)
                tagIds.isNotEmpty() ->
                    streamerRepository.observeByTags(tagIds.toList(), q, requireAll = true)
                groupId != null -> streamerRepository.observeByGroup(groupId, q)
                else -> streamerRepository.observeStreamers(q)
            }
            base.combine(configRepository.getConfigFlow()) { list, _ -> f to list }
        }
        .combine(healthRepository.observeHealth()) { (f, list), health ->
            Triple(f, list, health)
        }
        // 新的可见列表一到，就把选中集合收敛到可见范围。
        // 不收敛的后果是有数据损失面的：勾选 3 位后切到"收藏"（只剩 1 位可见），
        // 标题仍写"已选 3 位"，批量删除/暂停/收藏会连带操作两位用户已经看不见的主播。
        // 收敛点选在这里而不是 uiState 的 combine 里：这里已经拿到"新的可见列表"，
        // 可以一次到位；写进 combine 的变换里等于给纯函数塞副作用，而且要晚一帧。
        .onEach { (f, list, _) ->
            pruneSelection(f, list)
            // 卡片上的标签跟着"当前可见的这批主播"走：筛选/搜索一变可见集合就变，
            // 必须按新集合重取一次（一次 IN 查询，见 [reloadStreamerTags]）。
            // 不重取的后果是上一批人的标签留在新列表上，卡片出现张冠李戴的标签。
            reloadStreamerTags(list.filter { f.matches(it) })
        }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    val uiState: StateFlow<HomeUiState> = combine(
        streamers,
        configRepository.getConfigFlow(),
        message,
        selectedIds,
        // 第 5 路本来就把"标签目录 + 分组目录"打包成一个值传进来，卡片的标签映射搭同一班车：
        // 具名 combine 的重载到 5 路为止，再开一路既没地方放、也没必要。
        combine(tags, groups, streamerTags) { t, g, byStreamer -> Triple(t, g, byStreamer) }
    ) { (f, list, health), config, msg, sel, taxonomy ->
        val (tagList, groupList, streamerTagsByStreamer) = taxonomy
        // 排序：直播中置顶 → 轮播 → 未开播 → 待确认；同组内收藏优先、按名字排序
        fun statusPriority(s: StreamerEntity): Int = when (s.confirmedLiveStatus) {
            ConfirmedLiveStatus.LIVE -> 0
            ConfirmedLiveStatus.ROUND -> 1
            ConfirmedLiveStatus.OFFLINE -> 2
            ConfirmedLiveStatus.UNKNOWN -> 3
        }
        val sorted = list.sortedWith(
            compareBy(
                { statusPriority(it) },
                { !it.isFavorite },
                { it.name }
            )
        )
        // 直播中筛选只包含真正在直播的主播（轮播不算）；
        // 轮播归入"未开播"（对观众而言轮播不是真直播）。判定规则见 [matches]。
        val filtered = sorted.filter { f.matches(it) }
        // 输出侧再求一次交集：pruneSelection 是"列表到达 → 写回选中"的异步一步，
        // 极端时序下 sel 可能还没收敛。而这里出去的 selectedIds 同时决定了标题里的
        // "已选 N 位"、多选模式判定和批量操作的目标，必须是裁剪后的那一份。
        val visibleIds = filtered.mapTo(HashSet<Long>()) { it.id }
        // 「封禁中」标记：在**这里**（映射层）解一次，界面只拿布尔值。
        // 只对**当前可见**的这批主播解，与 [HomeUiState.streamerTags] 同一口径：
        // 卡片永远拿不到"看不见的那批人"的标记。`isBanned` 的"解不出来就是没封禁"
        // 保证一段普通错误文本不会被算成封禁（封禁标记 = `lastError` 里的 `ROOM_BANNED ` 前缀）。
        val bannedIds = filtered
            .filter { RoomBanCodec.isBanned(it.lastError) }
            .mapTo(HashSet<Long>()) { it.id }
        HomeUiState(
            // 走到这一行说明列表与配置都已经产出过数据，"首帧加载"结束
            loading = false,
            query = query.value,
            filter = f,
            streamers = filtered,
            config = config,
            health = health,
            message = msg,
            selectedIds = sel intersect visibleIds,
            tags = tagList,
            tagFilterIds = tagFilterIds.value,
            groups = groupList,
            groupFilterId = groupFilterId.value,
            streamerTags = streamerTagsByStreamer,
            bannedIds = bannedIds
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun setFilter(f: HomeFilter) {
        filter.value = f
    }

    fun clearMessage() {
        message.value = null
    }

    fun refreshNow() {
        monitoringController.refreshNow()
        message.value = "已触发手动刷新"
    }

    fun addStreamer(input: String, mode: InputMode = InputMode.ROOM) {
        viewModelScope.launch {
            val outcome = runCatching { streamerRepository.addStreamer(input, mode) }
                .getOrElse { message.value = "添加失败：${it.message}"; return@launch }
            when (val result = outcome) {
                is AddStreamerResult.Added -> message.value = "已添加：${result.streamer.name}"
                is AddStreamerResult.Restored -> message.value = "已恢复：${result.streamer.name}"
                AddStreamerResult.AlreadyExists -> message.value = "该主播已在监控列表中"
                is AddStreamerResult.NotFound -> message.value = result.reason ?: "找不到该主播"
                // 已添加，但该直播间当前被封禁：这是**成功**提示（产品决策：允许添加已封禁的直播间）。
                // 文案自带"哪种封禁 / 它现在不会开播 / 不会被误报 / 会按较低频率复查"，
                // 由 RoomBanPolicy.addSuccessMessage 生成（与落库、调度同一份 lock_till 解析）。
                is AddStreamerResult.AddedBanned -> message.value = result.message
                is AddStreamerResult.Invalid -> message.value = result.reason
            }
        }
    }

    fun toggleFavorite(streamer: StreamerEntity) {
        viewModelScope.launch {
            runCatching { streamerRepository.setFavorite(streamer.id, !streamer.isFavorite) }
                .onFailure { message.value = "操作失败：${it.message}" }
        }
    }

    // ---- 批量操作（原规范 53）----

    /**
     * 是否处于多选模式。
     *
     * 读**裁剪后**的选中集合（即 uiState.selectedIds），不能读原始集合：筛选把选中的主播
     * 全部挡到屏外之后，原始集合仍然非空、而顶栏已经消失，于是点卡片只会"切换选中"
     * （卡片颜色不变、详情也打不开），变成一个没法解释的鬼状态。
     */
    val inSelectionMode: Boolean get() = uiState.value.selectedIds.isNotEmpty()

    /**
     * 把选中集合裁剪到"当前筛选下可见的 id"。
     *
     * - 幂等：[Set.intersect] 满足 A ∩ V ∩ V = A ∩ V，且 MutableStateFlow 丢弃相等的新值，
     *   所以这次写回最多再引起一轮 uiState 重组就稳定，不会自激成死循环。
     * - 不额外查库：可见列表就是列表流这次吐出来的结果，只是重新过一遍同一个筛选规则。
     */
    private fun pruneSelection(f: HomeFilter, list: List<StreamerEntity>) {
        val current = selectedIds.value
        if (current.isEmpty()) return
        val kept = current intersect list.asSequence()
            .filter { f.matches(it) }
            .map { it.id }
            .toHashSet()
        if (kept.size != current.size) selectedIds.value = kept
    }

    /**
     * 把标签筛选收敛到"目录里仍然存在的 id"上：`tagFilterIds ∩ 目录 id`。
     *
     * 不收敛留下的是一个**没有出口**的死状态：正被选中的标签在「标签 / 分组管理」页被删除后，
     * 筛选行里已经没有这个 chip 可点（目录里没有它了），用户无法通过点击取消它；
     * 查询却仍然带着这个 id（`observeByTags` 的 `IN (...)`），列表恒为空 ——
     * 界面既不说明原因，也不给恢复手段。极端情况下被删的是最后一个标签，
     * 筛选行整行跟着消失（HomeFilterCard 里 `visible = tags.isNotEmpty()`），
     * 连「全部标签」都没得点，只能杀进程重来。
     *
     * 幂等：交集满足 A ∩ C ∩ C = A ∩ C，且 MutableStateFlow 丢弃相等的新值，
     * 所以这次写回最多再引起一轮重算就稳定，不会自激成循环 —— 与 [pruneSelection] 同一套路。
     *
     * 这里刻意**不写**"目录为空就跳过"：首帧那个空目录已经由 [tagCatalogLoaded] 挡掉了
     * （调用点见 init），能走到这里的空目录只有一个含义 —— 库里真的一个标签都不剩，
     * 那时筛选必须清掉，否则就是上面那个死状态。
     */
    private fun pruneTagFilter(catalog: List<TagWithCount>) {
        val current = tagFilterIds.value
        if (current.isEmpty()) return
        val kept = current intersect catalog.mapTo(HashSet()) { it.id }
        if (kept.size != current.size) tagFilterIds.value = kept
    }

    /**
     * 分组筛选是**单选**（[groupFilterId] 是 Long?），所以只需判断选中的那个 id 还在不在目录里，
     * 不在就置空、回到「全部分组」。
     *
     * 理由与幂等性和 [pruneTagFilter] 完全一致：悬空的 groupFilterId 会让列表恒为空，
     * 而那一行 chips 里已经没有对应的可点项；置空之后再收敛就是空操作，不会来回抖。
     */
    private fun pruneGroupFilter(catalog: List<GroupWithCount>) {
        val current = groupFilterId.value ?: return
        if (catalog.none { it.id == current }) groupFilterId.value = null
    }

    /**
     * 批量操作的目标 = 界面此刻显示的选中集合（已裁剪到可见范围）。
     * 不能用原始 selectedIds：在"筛选刚切换、裁剪还没写回"的窗口里，
     * 原始集合可能还留着用户已经看不见的主播 id。
     */
    private fun batchTargets(): List<Long> = uiState.value.selectedIds.toList()

    fun toggleSelect(id: Long) {
        // 以裁剪后的集合为基准，顺带把已经看不见的 id 从原始集合里剔掉：
        // 不剔的话它会一直攒着，用户切回原来的筛选时"诈尸"成选中态。
        val cur = uiState.value.selectedIds
        selectedIds.value = if (id in cur) cur - id else cur + id
    }

    fun selectAll(visible: List<StreamerEntity>) {
        selectedIds.value = visible.map { it.id }.toSet()
    }

    fun clearSelection() { selectedIds.value = emptySet() }

    fun refreshTags() {
        viewModelScope.launch {
            // 走 setXxxCatalog 而不是直接赋值：它们顺带置"目录已加载"标志，
            // 而这两行正是"管理页删掉标签 / 分组后首页唯一的感知入口" ——
            // 目录值一变，init 里那两条收集就会把筛选里的悬空 id 收敛掉。
            setTagCatalog(taxonomyRepository.tagsWithCount())
            setGroupCatalog(taxonomyRepository.groupsWithCount())
            // 详情页可以改归属（加/减标签），而首页列表**不会**因此重新查询：
            // observeActive 只观察 streamer 表，关联表的写入不触发它。首页的
            // LifecycleResumeEffect 已经在调这个方法，顺路把卡片标签一起刷新 ——
            // 否则用户加完标签返回首页会看到卡片毫无变化，以为操作没生效。
            reloadStreamerTags(uiState.value.streamers)
        }
    }

    /**
     * 取回"这批主播各自有哪些标签"，供卡片显示。
     *
     * 一次 [com.example.bilimonitor.data.repository.TaxonomyRepository.tagRefsOf]（单条
     * `IN (...)` 语句）拿回全部关联行，再在内存里按 streamerId 分组 —— 刻意**不是**每张卡片
     * 各查一次库：那样列表里有多少位主播就有多少次查询（N+1）。
     *
     * 标签名取自 [tags]（筛选区那份目录缓存），顺序也沿用标签表自己的展示顺序（sortOrder, name）：
     * 卡片上只露前几个，露出的那几个必须和筛选区里排在最前面的是同一批。
     * 若某条关联行指向目录里还没有的标签（刚在详情页新建、缓存尚未刷新），就整体重载一次目录兜底：
     * 卡片上宁可晚一帧出现，也不能少画一个标签（静默降级比慢一帧糟糕得多）。
     */
    private suspend fun reloadStreamerTags(streamers: List<StreamerEntity>) {
        // 这里必须就地兜住异常，理由是它有两个"绝不能"：
        //  ① 不能把异常抛回 onEach —— 那是列表流的中转站，一抛整条 uiState 流就永久停更，
        //     首页会僵在旧数据上（比崩一次更难排查）；
        //  ② 不能当作什么都没发生 —— 于是失败时清空映射（宁可整行不画）并走既有的消息条说明，
        //     而不是留着上一个筛选条件下的标签，让用户以为这些标签是这位主播的。
        runCatching {
            val ids = streamers.map { it.id }
            if (ids.isEmpty()) {
                streamerTags.value = emptyMap()
                return@runCatching
            }
            val refs = taxonomyRepository.tagRefsOf(ids)
            if (refs.isEmpty()) {
                streamerTags.value = emptyMap()
                return@runCatching
            }
            var catalog = tags.value
            val knownTagIds = catalog.mapTo(HashSet()) { it.id }
            if (refs.any { it.tagId !in knownTagIds }) {
                catalog = taxonomyRepository.tagsWithCount()
                // 走唯一写入口：这次重读同样是权威目录值，顺带把"目录已加载"标志置上
                // （init 那次若读库失败，标志还停在 false，收敛会一直缺席）。
                setTagCatalog(catalog)
            }
            val tagIdsByStreamer = refs.groupBy({ it.streamerId }, { it.tagId })
            streamerTags.value = ids.mapNotNull { id ->
                val owned = tagIdsByStreamer[id]?.toHashSet() ?: return@mapNotNull null
                val names = catalog.filter { it.id in owned }.map { it.name }
                if (names.isEmpty()) null else id to names
            }.toMap()
        }.onFailure {
            streamerTags.value = emptyMap()
            message.value = "标签显示加载失败：${it.message}"
        }
    }

    /**
     * 点击标签 chip（用户定稿：标签行同级多选，多选之间取**交集**）。
     *
     * [tagId] 传 null 表示点的是「全部标签」，即清空整个标签条件；传一个已选中的 id =
     * 只取消这一个标签、**其余选中项原样保留**。toggle 逻辑放在这里而不是界面里：
     * 上一版由 chip 自己算 `if (已选中) null else id`，那在单选下恰好等价，
     * 到了多选就会变成"每点一次把整组选择覆盖成单个 id"。
     * 状态怎么变只能有一个权威入口，界面只负责报告"点了谁"。
     */
    fun setTagFilter(tagId: Long?) {
        tagFilterIds.value = when {
            tagId == null -> emptySet()
            tagId in tagFilterIds.value -> tagFilterIds.value - tagId
            else -> tagFilterIds.value + tagId
        }
        // 这里**刻意不再清空 groupFilterId**（删掉了原来那行
        // `if (tagId != null) groupFilterId.value = null`）：分组与标签是两个独立维度，
        // 用户定稿的语义是 AND。留着它的话，"先选分组、再点标签"会把分组悄悄抹掉 ——
        // 界面上的结果与用户刚做的操作不符，而且这种消失没有任何提示。
    }

    fun setGroupFilter(groupId: Long?) {
        groupFilterId.value = groupId
        // 分组仍是**单选**（[groupId] 保持 Long?，筛选区那一行也仍是 FilterChipRow）。
        // 同样删掉了原来那行 `if (groupId != null) tagFilterId.value = null`：
        // 选分组不该丢掉已经选好的标签。
    }

    fun batchFavorite(favorite: Boolean) {
        val ids = batchTargets()
        viewModelScope.launch {
            runCatching { streamerRepository.batchSetFavorite(ids, favorite) }
                .onFailure { message.value = "操作失败：${it.message}" }
                .onSuccess {
                clearSelection(); refreshTags()
                message.value = "已批量${if (favorite) "收藏" else "取消收藏"} ${ids.size} 位主播"
            }
        }
    }

    fun batchPause() {
        val ids = batchTargets()
        viewModelScope.launch {
            runCatching { streamerRepository.batchSetMonitoringEnabled(ids, false) }
                .onFailure { message.value = "操作失败：${it.message}" }
                .onSuccess {
                    clearSelection()
                    message.value = "已暂停 ${ids.size} 位主播的监控"
                }
        }
    }

    fun batchResume() {
        val ids = batchTargets()
        viewModelScope.launch {
            runCatching { streamerRepository.batchSetMonitoringEnabled(ids, true) }
                .onFailure { message.value = "操作失败：${it.message}" }
                .onSuccess {
                    clearSelection()
                    message.value = "已恢复 ${ids.size} 位主播的监控"
                }
        }
    }

    fun batchDelete() {
        val ids = batchTargets()
        viewModelScope.launch {
            runCatching { streamerRepository.batchSoftDelete(ids) }
                .onFailure { message.value = "操作失败：${it.message}" }
                .onSuccess {
                    clearSelection(); refreshTags()
                    message.value = "已删除 ${ids.size} 位主播（历史保留，可重新添加恢复）"
                }
        }
    }

    private companion object {
        /** SavedStateHandle 的键。换字符串等于丢掉已经存进去的值，只能改语义不能改名。 */
        const val KEY_FILTER_EXPANDED = "home.filterExpanded"
    }
}

/**
 * 状态筛选的判定规则。
 *
 * 抽出来是因为它有两个消费方：列表展示（[HomeViewModel.uiState]）与选中集合裁剪
 * （[HomeViewModel.pruneSelection]）。两处各写一份 when 迟早会漂移，而漂移的表现
 * 就是"看得见的选不上、选中的看不见"。
 *
 * 直播中只包含真正在直播的主播（轮播不算）；轮播归入"未开播"—— 对观众而言轮播不是真直播。
 */
private fun HomeFilter.matches(streamer: StreamerEntity): Boolean = when (this) {
    HomeFilter.ALL -> true
    HomeFilter.LIVE -> streamer.confirmedLiveStatus == ConfirmedLiveStatus.LIVE
    HomeFilter.OFFLINE -> streamer.confirmedLiveStatus != ConfirmedLiveStatus.LIVE
    HomeFilter.FAVORITE -> streamer.isFavorite
}