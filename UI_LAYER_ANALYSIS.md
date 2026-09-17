# B站主播直播监控 App —— UI 层功能与问题分析报告

- 工程：`D:\ai-workspace\dsh\bilibili-live-monitoring-2`，包名 `com.example.bilimonitor`
- 分析范围：`app\src\main\java\com\example\bilimonitor\ui\` 下全部 17 个文件（含任务未列出但必需的 `nav\AppNavHost.kt`）
- 性质：**只读分析**，未修改任何文件
- 约定：行号均指当前工作区文件的真实行号；`文件:行号` 可直接跳转

> 说明：本报告只陈述代码中真实存在的内容。凡推断、无法确证之处均显式标注「不确定」。

---

## 0. 全局导航结构（`ui\nav\AppNavHost.kt`，181 行）

### 路由表（`Routes` 对象，L40–L57）

| 常量 | 路由串 | 参数 |
|---|---|---|
| `ONBOARDING` | `onboarding` | — |
| `HOME` | `home` | — |
| `HISTORY` | `history` | — |
| `HISTORY_STREAMER` | `history/streamer/{streamerId}` | `Long` |
| `STATS` | `stats` | — |
| `STATS_STREAMER` | `stats/streamer/{streamerId}` | `Long` |
| `SETTINGS` | `settings` | — |
| `LOGIN` | `login` | — |
| `DATA` | `data` | — |
| `NOTIFICATIONS` | `notifications` | — |
| `DIAGNOSTICS` | `diagnostics` | — |
| `DETAIL` | `detail/{streamerId}` | `Long` |

辅助函数：`Routes.detail(id)`、`Routes.historyStreamer(id)`、`Routes.statsStreamer(id)`。

### 可见交互

- **底部导航栏**（`NavigationBar`，L88–L103），仅在 4 个一级路由显示（`showBottomBar`，L79），四个 `NavigationBarItem`：
  1. `主播`（`Icons.Filled.Home`）→ `home`
  2. `历史`（`Icons.Filled.History`）→ `history`
  3. `统计`（`Icons.Filled.BarChart`）→ `stats`
  4. `设置`（`Icons.Filled.Settings`）→ `settings`
  - 点击行为：`popUpTo(findStartDestination) { saveState = true }` + `launchSingleTop = true` + `restoreState = true`（L93–L97）
- `OnboardingScreen.onDone` 回调：先调 `onRequestNotificationPermission()`，再 `navigate(HOME) { popUpTo(ONBOARDING) { inclusive = true } }`（L113–L120）
- `StreamerDetailScreen.onDeleted` → `popBackStack()`（L176）
- `SettingsScreen` 的回调接线（L151–L162）：`onReonboard`、`onOpenLogin`、`onOpenData`、`onOpenNotifications`、`onOpenDiagnostics`

### 本文件发现的问题

| 位置 | 问题 |
|---|---|
| `AppNavHost.kt:81-83` | `LaunchedEffect(pendingUrl) { if (pendingUrl != null) onUrlConsumed() }` —— **深链 URL 被「消费」但从未被使用**：既不导航也不打开任何页面，只是把值清空。全工程唯一写入方是 `MainActivity.kt:83`（`pendingUrl = safe`），且 `MainActivity.kt:84-89` 已自行 `startActivity(ACTION_VIEW)` 交给外部浏览器。因此 `pendingUrl`/`onUrlConsumed` 整条链路在当前 UI 中是纯空转（不是崩溃级 bug，但属于未完成/冗余设计）。 |
| `AppNavHost.kt:154-156` | `onReonboard` 里 `popUpTo(Routes.HOME) { inclusive = false }` 是无效操作：`popUpTo` 在 `navigate(ONBOARDING)` 时执行，而当前栈顶是 `settings`，`HOME` 需在回退栈中；从 `settings` 直接 `navigate` 会压栈，此处 `popUpTo` 实际基本无效果。**不确定**是否有意为之。 |
| `AppNavHost.kt:132`、`:145`、`:172` | `entry.arguments?.getLong("streamerId") ?: 0L` —— 参数缺失时**静默退化为 0L**，随后会以 `streamerId = 0` 去查询（详情页表现为永远转圈、历史/统计页表现为空）。无错误提示。 |
| `AppNavHost.kt:20` | `import androidx.navigation.NavHostController` 未使用（死导入）。 |

---

## 1. 首页 · `ui\home\HomeScreen.kt`（411 行）+ `ui\home\HomeViewModel.kt`（228 行）

### 1.1 页面结构与状态

- `HomeUiState`（`HomeViewModel.kt:26-37`）：`loading / query / filter / streamers / config / health / message / selectedIds / tags / tagFilterId`
- `HomeFilter` 枚举（`HomeViewModel.kt:39-41`）：`ALL("全部")`、`LIVE("直播中")`、`OFFLINE("未开播")`、`FAVORITE("收藏")`
- 排序规则（`HomeViewModel.kt:89-95`）：状态优先级「直播中 → 轮播 → 未开播 → 待确认」→ 收藏优先 → 按 `name` 升序

### 1.2 具体交互与功能点

**普通模式顶部栏**（`HomeScreen.kt:107-114`）
- 标题：`主播监控`
- 图标按钮 `Icons.Filled.Refresh`，`contentDescription = "手动刷新"`

**选择（多选）模式顶部栏**（`HomeScreen.kt:90-105`），进入条件 `state.selectedIds.isNotEmpty()`（L87）
- 标题：`已选 N 位`
- 导航图标 `Icons.Filled.Close`，`contentDescription = "取消选择"`
- 5 个文字按钮：`全选`、`收藏`、`恢复`、`暂停`、`删除`

**悬浮按钮**（L117-123）：`FloatingActionButton` + `Icons.Filled.Add`，`contentDescription = "添加主播"`（仅非选择模式显示）

**健康横幅** `HealthBanner`（L143-148 调用、L236-247 定义）
- 文案来自 `state.health.userMessage`
- 使用 `secondaryContainer` 卡片

**搜索框**（L149-159）
- `OutlinedTextField`，占位符 `搜索主播昵称`，前置 `Icons.Filled.Search`，圆角 `24.dp`

**状态筛选 chips**（L160-171）：`全部` / `直播中` / `未开播` / `收藏`

**标签筛选 chips**（L172-190，仅 `state.tags` 非空时显示）
- `全部标签`（点击 → `setTagFilter(null)`）
- 每个标签：`${t.name}(${t.count})`，再次点击同一标签会取消筛选（L185）

**主播卡片** `StreamerCard`（L251-336）
- 头像 `AsyncImage`（L271）+ 右下角状态色小圆点（L279-284）
- 主标题：`streamer.name`
- 副标题：`streamer.roomTitle ?: streamer.areaName ?: "暂无直播间标题"`（L295）
- 状态行：`statusLabel(...)`（`直播中`/`轮播中`/`未开播`/`待确认`，L374-379）
- 若 `freshnessStatus == STALE` 追加 `· 数据过期`（L309-316）
- 若 `lastCheckedAt != null` 追加 `· ${relativeTime(it)}`（L317-324；`relativeTime` 产出 `刚刚` / `N 分钟前` / `N 小时前` / `MM-dd HH:mm`）
- 右侧收藏按钮 `IconButton`：`Icons.Filled.Star` ↔ `Icons.Outlined.StarOutline`，`contentDescription = "收藏"`，选中色 `Color(0xFFE8A03C)`（L327-333）
- 点击语义：选择模式 → 切换选中；否则 → 打开详情（L211-214）
- 长按 → 进入/切换选择（L215）
- 选中态视觉：`primaryContainer` 底色（L265-266）+ `Modifier.padding(1.dp)`（L263）

**空态文案**（L191-203）
- 有查询：`没有匹配的主播`
- 无查询：`还没有主播，点击 + 添加\n支持 UID、直播间链接`
- `state.loading == true` 时显示 `CircularProgressIndicator`

**消息提示**（L124-140）：`snackbarHost` 槽位内自绘 `Card`（`inverseSurface` 底 + `inverseOnSurface` 字），`LaunchedEffect(state.message)` 延时 2500ms 后 `clearMessage()`（L75-80）

**添加主播对话框** `AddStreamerDialog`（L225-233 调用、L338-365 定义）
- 标题 `添加主播`
- 说明文字 `输入主播 UID 或直播间链接\n例如：672328094 或 https://live.bilibili.com/12345`
- 输入框占位符 `UID / 链接`
- 按钮 `添加`（`enabled = text.isNotBlank()`）、`取消`

**生命周期**：`LifecycleResumeEffect(Unit)` 中调 `viewModel.refreshTags()`，`onPauseOrDispose { }` 为空（L82-85）

### 1.3 使用的 ViewModel / Repository 方法

| 调用点 | 方法 |
|---|---|
| `HomeScreen.kt:72` | `HomeViewModel.uiState`（`StateFlow`） |
| `:78` | `HomeViewModel.clearMessage()` |
| `:83` | `HomeViewModel.refreshTags()` |
| `:94` | `HomeViewModel.clearSelection()` |
| `:99` | `HomeViewModel.selectAll(state.streamers)` |
| `:100/101/102/103` | `HomeViewModel.batchFavorite(true)` / `batchResume()` / `batchPause()` / `batchDelete()` |
| `:110` | `HomeViewModel.refreshNow()` |
| `:151` | `HomeViewModel.setQuery(it)` |
| `:167` | `HomeViewModel.setFilter(f)` |
| `:179/185` | `HomeViewModel.setTagFilter(...)` |
| `:212/215` | `HomeViewModel.inSelectionMode` / `toggleSelect(id)` |
| `:216` | `HomeViewModel.toggleFavorite(streamer)` |
| `:229` | `HomeViewModel.addStreamer(it)` |

Repository（经 ViewModel）：
- `StreamerRepository`：`observeStreamers(query)`、`observeByTag(tagId)`、`addStreamer(input)`、`setFavorite(id, fav)`、`batchSetFavorite`、`batchSetMonitoringEnabled`、`batchSoftDelete`
- `ConfigRepository.getConfigFlow()`
- `TaxonomyRepository.tagsWithCount()`
- `HealthRepository.observeHealth()`
- `MonitoringController.refreshNow()`

以上方法均已在对应类中确认存在，签名匹配（`StreamerRepository.kt:48/51/57/162/167/182/210`、`TaxonomyRepository.kt:27`、`HealthRepository.kt:44`）。

### 1.4 问题与可疑点

| 位置 | 问题 |
|---|---|
| `HomeScreen.kt:146` + `:237` | `HealthBanner(text = health.userMessage, onClick = null)` —— **唯一调用点恒传 `onClick = null`**，导致 L242 的 `Modifier.clickable { onClick() }` 分支永远不会生效。横幅设计为可点击但实际永远不可点（`onClick` 参数为死参数）。 |
| `HomeScreen.kt:100` | `viewModel.batchFavorite(true)` **硬编码 `true`**。`HomeViewModel.batchFavorite(favorite: Boolean)` 支持 `false`（并已实现 `已批量取消收藏` 文案，L188），但 UI 无任何入口触发取消收藏 → 批量「取消收藏」功能缺失。 |
| `HomeViewModel.kt:134` | `refreshNow()` 无条件 `message.value = "已触发手动刷新"`，不反映 `monitoringController.refreshNow()`（`MonitoringController.kt:33` → `engine.refreshNowAsync("manual")`，返回 `Unit`）的真实结果。若监控被关闭，仍会提示"已触发"。 |
| `HomeViewModel.kt:68` | 选中标签筛选后走 `streamerRepository.observeByTag(tagId)`，**该查询不带 `query` 参数**（`StreamerRepository.kt:51`），而 `StreamerRepository.observeStreamers(query)`（L48）才做服务端搜索。结果：**选定标签后搜索框失效**（输入关键词不会过滤），但搜索框仍可见可输入 —— 行为不一致。 |
| `HomeViewModel.kt:66-73` | `base.combine(configRepository.getConfigFlow())` 仅用于 `f to list`，`config` 值被丢弃后又在外层重新 combine 一次 `configRepository.getConfigFlow()`（L77）。属于冗余订阅（非功能性 bug）。 |
| `HomeViewModel.kt:83-88` + `:98-105` | 筛选语义：`HomeFilter.OFFLINE` 实为 `confirmedLiveStatus != LIVE`，即**包含 `ROUND`（轮播）和 `UNKNOWN`（待确认）**。代码注释（L96-97）明确说明是有意为之（"轮播归入未开播"）。但 `UNKNOWN` 也被并进"未开播"，注释未提及 —— 不确定是否符合预期。 |
| `HomeViewModel.kt:77` vs `:135` | `state.message` 同时承载「Snackbar 消息」职责，且 `LaunchedEffect(state.message)`（`HomeScreen.kt:75`）以 message 为 key：连续两次相同文本的消息不会重触发计时器（`StateFlow` 去重后 `LaunchedEffect` 不会重启）。极端情况下第二条同文案消息不会自动消失。 |
| `HomeScreen.kt:381` | `private val timeFormatter` 在 411 行文件中实际**未被使用**（`relativeTime` L390-391 与 `formatTime` L398 各自内联构造了 `DateTimeFormatter`）。死代码。 |
| `HomeScreen.kt:263` | `.then(if (selected) Modifier.padding(1.dp) else Modifier)` —— 选中态仅加 1dp padding，不影响容器宽度/描边，视觉上几乎不可见（真正的选中反馈只靠 `containerColor`）。可疑但不致错。 |
| `HomeScreen.kt:211-214` | 点击语义分叉依赖 `viewModel.inSelectionMode`（`HomeViewModel.kt:160`，读 `selectedIds.value`，**非 Compose 状态**）。因为 `inSelectionMode` 变化必然伴随 `selectedIds` 这一 Compose 状态变化而重组，此处通常正确；但它是命令式读取，**不确定**在极端重组时序下是否可靠。 |

---

## 2. 主播详情 · `ui\detail\StreamerDetailScreen.kt`（485 行，含内联 `StreamerDetailViewModel`）

### 2.1 状态

`StreamerDetailState`（L67-74）：`streamer / policy / sessions / history / tags / message`

### 2.2 具体交互与功能点

**顶部栏**（L204-227）
- 标题：`state.streamer?.name ?: "主播详情"`
- 返回：`Icons.AutoMirrored.Filled.ArrowBack`，`contentDescription = "返回"`
- 3 个动作图标：
  1. `Icons.Filled.Refresh`，`contentDescription = "刷新资料"` → `refreshMetadata()`
  2. `Icons.Filled.Star` / `Icons.Outlined.StarOutline`，`contentDescription = "收藏"` → `toggleFavorite()`
  3. `Icons.Filled.Delete`，`contentDescription = "删除"` → 打开删除对话框

**主播信息卡**（L243-278）
- 64dp 圆形头像
- `streamer.name`（`titleLarge` + Bold）
- `UID ${streamer.uid}` + 可选 ` · 房间 ${roomId}`（L258）
- 状态文案（`直播中`/`轮播中`/`未开播`/`待确认`，L466-471）+ 状态色
- 文字按钮 `改名` → 打开改昵称对话框（L268）
- 若 `roomTitle != null` 额外展示该标题（L270-276）

**监控策略卡** `PolicyCard`（L280-282 调用、L392-431 定义）
- 标题 `监控策略`
- 开关 `开播通知` → `Switch`（L409-414）
- 开关 `关播通知` → `Switch`（L416-421）
- 只读说明文本：`开播确认 ${policy.startConfirmationCount ?: 1} 次 · 关播确认 ${policy.endConfirmationCount ?: 2} 次（跟随全局默认）`（L422-428）

**标签卡**（L284-321）
- 标题 `标签`
- 已有标签以 `AssistChip` 展示，文案 `"${tag.name} ×"`，点击 → `removeTag(tag.id)`（L291-296）
- `AssistChip` `+ 添加标签` → 打开添加对话框（L297）
- 添加标签对话框：标题 `添加标签`，`OutlinedTextField` label `标签名`，按钮 `添加`（`enabled = tagName.isNotBlank()`）、`取消`（L301-320）

**直播记录**（L323-332）
- 小节标题 `直播记录`
- 空态：`暂无直播记录`
- 每条 `SessionRow`（L433-464）：`${formatTime(startTime)} 开始`、`${formatTime(endTime)} 结束 · 时长 ${formatDuration(durationSeconds)}`
- 三个 `AssistChip`：`开播:${startConfidence?.name ?: "—"}`、`关播:${endConfidence?.name ?: "—"}`、`endTime == null` 时 `进行中`

**状态历史（最近）**（L334-353）
- 小节标题 `状态历史（最近）`
- 每条卡片显示 `"${labelOf(h.oldStatus)} → ${labelOf(h.newStatus)}"` + `formatTime(h.occurredAt)`
- `labelOf`（L480-484）产出 `直播` / `轮播` / `下播` / `未知`

**删除确认对话框**（L357-370）
- 标题 `删除主播`
- 正文 `将停止监控该主播；历史直播记录会保留，可随时重新添加恢复。`
- `删除` / `取消`

**修改昵称对话框**（L371-389）
- 标题 `修改昵称`
- 单行 `OutlinedTextField`
- `保存` / `取消`；`text.isNotBlank()` 才调 `rename`

**加载态**：`streamer == null` 时整页 `CircularProgressIndicator`（L231-237）

### 2.3 使用的 ViewModel / Repository 方法

| 调用点 | 方法 |
|---|---|
| `:197` | `StreamerDetailViewModel.load(streamerId)` |
| `:212` | `refreshMetadata()` |
| `:215` | `toggleFavorite()` |
| `:266/293` | `removeTag(tag.id)` |
| `:314` | `addTag(tagName)` |
| `:281` | `updatePolicy(startCount, endCount, notifyStart, notifyEnd, intervalSeconds)` |
| `:365` | `softDelete(onDeleted)` |
| `:384` | `rename(text)` |

ViewModel 内部（L91-186）：`StreamerDao.observeById`、`PolicyDao.observe`、`LiveSessionDao.observeByStreamer(id, 100)`、`StatusHistoryDao.observeByStreamer(id, 30)`、`TaxonomyRepository.tagsOf / createAndAssign / unassign`、`StreamerRepository.setFavorite / setUserName / refreshMetadata / softDelete / updatePolicy`

以上方法均已确认存在（`TaxonomyRepository.kt:35/38/60`、`StreamerRepository.kt:162/256/318/135/260`、`MonitorDaos.kt:128/205/272/364`）。

### 2.4 问题与可疑点

| 位置 | 问题 |
|---|---|
| `StreamerDetailScreen.kt`（全文） | **`state.message` 从未被渲染**：`StreamerDetailState.message`（L73）在 UI 中没有任何消费点。而 ViewModel 会向它写值：`setMessage`（L180-182）被 `rename`（L138/139）、`refreshMetadata`（L146）、`softDelete`（L154）、`updatePolicy`（L175/176）、`addTag`（L118）、`removeTag`（L125）调用。**结果：保存策略、改名、刷新资料、删除失败等所有反馈都静默丢失，用户零提示。** 这是本页最明显的问题。 |
| `StreamerDetailScreen.kt:184-186` | `clearMessage()` 定义后**无任何调用点**（UI 未渲染 message，自然也不会清）；`message` 还会在 `combine` 中被反复复制（L106）。死代码 + 状态泄漏。 |
| `StreamerDetailScreen.kt:220` | 收藏图标 `tint = androidx.compose.ui.graphics.Color(0xFFE8A03C)` **无条件写死**（对比 `HomeScreen.kt:331` 有 `if (isFavorite)` 分支）。未收藏时 `StarOutline` 也是橙金色，收藏/未收藏的视觉区分只剩字形。 |
| `StreamerDetailScreen.kt:372` | `var text by remember { mutableStateOf(state.streamer?.name ?: "") }` —— `remember` **无 key**。若对话框打开时 `streamer` 仍为 null（或详情异步到达晚于对话框创建），输入框会永远停在空串。 |
| `StreamerDetailScreen.kt:422-428` | 「开播确认 N 次 · 关播确认 M 次」**仅展示、无编辑入口**，而 ViewModel 的 `updatePolicy` 已支持 `startCount`/`endCount`（L158-164，参数却从未被 UI 传入）。功能半成品。 |
| `StreamerDetailScreen.kt:290-298` | 标签 `Row` **无横向滚动也无换行**（非 `LazyRow` / `FlowRow`）。标签较多时会横向溢出被裁剪。 |
| `StreamerDetailScreen.kt:449-460` | `AssistChip(onClick = {}, ...)` 出现 3 处（`:451`、`:455`、`:459`）——**空实现的点击回调**。Chip 外观（含涟漪）暗示可点，实际无任何行为。 |
| `StreamerDetailScreen.kt:398-403` | `remember(policy?.streamerId, policy?.updatedAt)` 使开关只在策略行更新时重读；但 `onChange` 只发一个字段（其余传 `null`，L412/419），若 `updatePolicy` 失败，开关视觉状态已变而数据库未变（与 §4 的 message 丢失叠加，用户完全无感）。 |
| `StreamerDetailScreen.kt:334-353` | `状态历史（最近）` 小节**没有空态处理**：`state.history` 为空时只显示标题，下面空白（对比 `直播记录` L326-327 有 `暂无直播记录` 空态）。 |
| `StreamerDetailScreen.kt:452/456` | 直接输出 `DataConfidence` 的**枚举原始名**（`startConfidence?.name`，如 `CORRECTED`/`CONFIRMED`），未本地化 —— 与页面上其余全中文文案不一致，属用户可见的英文枚举泄漏。`HistoryScreen.kt:477` 同样问题。 |
| `StreamerDetailScreen.kt:92` | `if (currentId == streamerId && _state.value.streamer != null) return` —— 已加载过则直接返回。若之前加载的 `streamer` 记录被删除（`observeById` 发射 null），`_state.streamer` 变 null，再次 `load` 才会重新订阅；此外**没有取消旧订阅**，切换主播时旧的 `viewModelScope.launch` 仍持有旧 id 的 collect（新 `load` 会再起一个协程）。资源泄漏风险，**不确定**在实际导航模式下是否会触发（详情页每次是新的 NavBackStackEntry → 新的 ViewModel 实例，可能不会复现）。 |
| `StreamerDetailScreen.kt:106` | `message = _state.value.message` 在 combine 内读取命令式状态，`setMessage` 后又靠 `_state.value = loaded.copy(...)`（L109）回填。逻辑能跑通但很脆弱。 |

---

## 3. 直播历史 · `ui\history\HistoryScreen.kt`（824 行）+ `ui\history\HistoryListScreen.kt`（192 行）

### 3.1 第 1 级：`HistoryListScreen`（主播卡片列表）

**具体交互**
- 顶部栏标题 `直播历史`（L122）
- 搜索框占位符 `搜索主播名字`，`Icons.Filled.Search`，圆角 24dp（L125-135）
- 主播卡片（L150-186）：头像 + `s.name` + `最近：${formatTime(s.lastSessionAt)}`（无记录时 `—`）+ 右侧 `查看 ›`
- 整卡 `clickable` → `onOpenStreamer(s.streamerId)`（L155）
- 空态文案（L142-146）：无查询 → `还没有直播记录\n确认开播后会自动记录`；有查询 → `没有匹配的主播`

**ViewModel / DAO 方法**（`HistoryListViewModel`，L72-111）
- `LiveSessionDao.observeRecent(1000)`（L81）、`StreamerDao.observeActive()`（L82）
- 自带逻辑：过滤 `endTime != null` 的已结束场次（L85）、按 `streamerId` 分组、`maxOfOrNull { endTime ?: startTime ?: 0L }` 取最近时间（L95）、按 `lastSessionAt` 降序（L98）
- `HistoryListViewModel.setQuery`（L109）

**问题**
| 位置 | 问题 |
|---|---|
| `HistoryListScreen.kt:63`、`:77`、`:104`、`:106`、`:110` | `HistoryListUiState.message` / `MutableStateFlow message` / `clearMessage()` 构成**完整死链路**：`message` 恒为 `null`（L77 创建后无人写入），UI 也从不读取。**本页没有任何错误/成功提示机制**（例如 `observeRecent(1000)` 失败时无任何反馈）。 |
| `HistoryListScreen.kt:174` | `if (s.lastSessionAt in 1..Long.MAX_VALUE)` —— 该写法排除了 `null` 与 `0`，等价于 `(s.lastSessionAt ?: 0L) > 0`。语义正确但可读性差；**不确定**是否有意为之。 |
| `HistoryListScreen.kt:81` | `observeRecent(1000)` 固定取 1000 行，无分页；仅用于取每主播的 `lastSessionAt`，大库下为全表扫描式取数。 |
| `HistoryListScreen.kt:42` | `import com.example.bilimonitor.data.local.entity.StreamerEntity` **未使用**（死导入）。 |
| `HistoryScreen.kt:14` | `import androidx.compose.foundation.lazy.LazyRow` **未使用**（死导入）。 |
| `HistoryScreen.kt:29` | `import androidx.compose.material3.FilterChip` **未使用**（死导入）。 |

### 3.2 第 2 级：`StreamerHistoryScreen`（单主播场次列表）

**顶部栏**（L352-364）
- 标题：`state.streamer?.name ?: "直播历史"`
- 返回箭头 `contentDescription = "返回"`
- 文字按钮 `手工补录`（L361）→ 打开补录对话框

**搜索 + 筛选行**（L368-391）
- 搜索框占位符 `搜索标题关键词`，前置 Search 图标（L372-380）
- `IconButton` + `Icons.Filled.Tune`，`contentDescription = "筛选"`（L382-390）
- 图标底色随筛选状态变化：非 `ALL` 时 `primaryContainer`，否则 `surface`（L385-387）

**当前筛选提示条**（L394-411，仅 `filter.type != ALL`）
- `AssistChip` 显示 `filterLabel(state.filter)`（L401），点击 → 再次打开筛选对话框
- 文字按钮 `清除筛选` → `setFilter(HistoryFilter())`
- `共 N 场`

`filterLabel`（L535-542）产出：
- `全部` / `按日 · {日期}` / `按周 · 自 {日期} 起的一周` / `按月 · {YYYY-MM}` / `按年 · {YYYY} 年` / `自定义 · {起} ~ {止}`

**场次卡片**（L429-488）
- 标题：`row.titles.firstOrNull() ?: "（无标题记录）"`，`maxLines = 1`（折叠时）
- 标题多于 1 个时：折叠显示 `期间标题变更 N 个（点按展开全部）`（L451，`primary` 色，可点），展开后逐条显示 `① ` 首项 / `→ ` 后续项（L442-448）
- 时间行：`${formatTime(startTime)} → ${formatTime(endTime)}`（L460）
- 时长：`formatDuration(durationSeconds)`（`primary` 色，L469）
- `AssistChip`：`开播:${startConfidence?.name ?: "—"} 关播:${endConfidence?.name ?: "—"}`（L474-480，`onClick = {}` 空实现）
- 文字按钮 `修正`（L481）→ 打开人工修正对话框
- 若有 `note`：`备注：$it`（L483-485）
- 空态（L419-423）：全部/无搜索 → `暂无直播记录\n确认开播后会自动记录；也可手工补录`；否则 `没有匹配的直播记录`

**筛选对话框** `FilterDialog`（L495-502 调用、L555-687 定义）
- 标题 `筛选直播记录`
- 6 个 `RadioButton` 单选（`HistoryFilterType.entries`）：`全部`、`按日`、`按周`、`按月`、`按年`、`自定义`（L584-598）
- 选 `按日/按周/按月/按年` 时（L600-621）：
  - `已选：${valueDescription(value)}`（`未选择` 或具体值）
  - 文字按钮 `打开日历`（L608）
  - 提示文案随类型变化：`选择具体日期` / `选择任意一天，自动定位到该天所在的一周（周一起算）` / `选择该月任意一天` / `选择该年任意一天`
- 内嵌 `DatePickerDialog`（L658-686）：`确定`（把选中日期按类型归一化为 key，L678）+ `取消`
- 选 `自定义` 时（L623-627）：两组 `DateFields`，label 为 `开始日期` 与 `结束日期（含当天）`
- 校验提示（L628-633）：`存在无效日期（如 2 月 30 日），请检查`
- 按钮 `应用` / `取消`；校验错误（L644/645/649）：`请完整填写开始和结束的年月日`、`结束日期不能早于开始日期`、`请先打开日历选择日期`

**手工补录对话框** `ManualDialog`（L504-512 调用、L689-749 定义）
- 标题 `手工补录直播场次`
- `直播间标题（可选）`（单行）
- `DateTimeFields(label = "开播时间")` —— 年/月/日/时/分 五框
- `DateTimeFields(label = "下播时间（可留空=进行中）")`
- `DurationFields(label = "时长（时/分/秒，与上两项互为补全）")` —— 时/分/秒 三框
- `备注（可选）`（多行）
- 按钮 `创建` / `取消`
- 校验（L739-743）：`开播/下播/时长至少填写两项`、`存在无效日期（如 2 月 30 日），请检查`

**人工修正对话框** `EditSessionDialog`（L514-532 调用、L750-823 定义）
- 标题 `人工修正`
- 说明 `开播 / 下播 / 时长填任意两项，自动补全第三项。`
- `直播间标题（可选）`、`开播时间`、`下播时间`、`时长（时/分/秒）`、`备注`
- 按钮 `保存` / `取消`
- 校验（L807-818）：`至少需要填写两项时间，或修改标题/备注`、`存在无效日期（如 2 月 30 日），请检查`、`下播时间不能早于开播时间`

**消息**：`LaunchedEffect(state.message)` 延时 2500ms 清空（L344-349）—— 但**没有任何 Composable 渲染 `state.message`**（见问题表）

**`DurationParts`**（L293-311）：`hour/minute/second` 三个 `mutableStateOf`；`isComplete()` 要求三框全非空；`toSeconds()` 计算总秒；`setSeconds(total)` 反向填充

**`DurationFields`**（L314-329）：调用 `ui.common.smallTimeBox("时", ..., 5)` / `("分", ..., 2)` / `("秒", ..., 2)`

**ViewModel / DAO 方法**（`StreamerHistoryViewModel`，L116-290）
- `StreamerDao.observeById(streamerId)`（L130）
- `LiveSessionDao.observeByStreamer(streamerId, 600)`（L131）
- `LiveSessionTitleDao.observeAll()`（L132）
- `HistoryRepository.createManualSession(stableId, start, end, duration, title, note)`（L214-217）
- `HistoryRepository.applyManualCorrection(ManualCorrection)`（L230）
- `setTitleQuery`（L203）、`setFilter`（L205）、`clearMessage`（L241）
- 静态工具：`bucketDay/bucketWeek/bucketMonth/bucketYear`（L247-256）、`filterBounds`（L258-288）

`CorrectionResult` 分支文案（L219-224、L232-237）：`已创建手工场次` / `创建失败` / `主播不存在` / `修正已保存` / `保存冲突：记录已被其他修改更新，请重试` / `记录不存在`

### 3.3 问题与可疑点（重点）

| 位置 | 问题 |
|---|---|
| `HistoryScreen.kt:702-709` 与 `:763-770` | **`deriveFromStart()` / `deriveFromEnd()` 定义了但从未被调用**。全文件仅 4 处出现，全部是定义（grep 确认：702、706、763、767）。两者是唯一实现「三项填二补全第三」的联动逻辑，却没有任何 `onValueChange` 挂接它们。**结果：`ManualDialog` 与 `EditSessionDialog` 中"填两项自动补全"完全不会发生**，而两个对话框都向用户明示了这一行为（L701 注释、L724 label `时长（时/分/秒，与上两项互为补全）`、L778 正文 `开播 / 下播 / 时长填任意两项，自动补全第三项。`）——**UI 文案承诺了未实现的功能**。为已由代码证实的明显缺陷。 |
| `HistoryScreen.kt:344-349` | `LaunchedEffect(state.message)` 调 `viewModel.clearMessage()`，但**页面从不显示 `state.message`**（全文件无 `state.message?.let` / `Text(state.message)`）。所有 `CorrectionResult` 分支文案（L219-224、L232-237）包括 `已创建手工场次`、`修正已保存`、`保存冲突：…`、`记录不存在` 对用户完全不可见。**手工补录/修正的结果零反馈**（与 §2 详情页同类缺陷）。 |
| `HistoryScreen.kt:745` / `:820` | `onSave(...) titleText ...` 传入**未 trim 的原始文本**；`:744` 与 `:819` 又用 `titleText.ifBlank { null }`，即工具栏允许提交纯空格标题——不过 `HistoryRepository.kt:216` 有 `title?.takeIf { it.isNotBlank() }` 兜底，实际不会写入空白标题。属于无害但重复的校验。 |
| `HistoryScreen.kt:481` `:474-480` | `AssistChip(onClick = {}, ...)`（开播/关播置信度）**空实现回调**，外观可点但无行为（同 §2）。 |
| `HistoryScreen.kt:477` | 置信度直接打印 `DataConfidence` **枚举英文名**（`CORRECTED` 等），未本地化。 |
| `HistoryScreen.kt:132` + `:146-154` | `titleDao.observeAll()` **无 limit 地加载全表标题行**，再在内存中 `groupBy { it.sessionStableId }`（L146）。随场次增长，数据量与内存/重组开销线性上升。性能隐患。 |
| `HistoryScreen.kt:131` | `liveSessionDao.observeByStreamer(streamerId, 600)` —— 固定 600 场上限，**无分页**，且用户无从得知被截断。 |
| `HistoryScreen.kt:195-196` | `… .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …).collect { _uiState.value = it }` —— 先造 `StateFlow` 再立刻在**同一个 `viewModelScope` 内永久 collect**，使 `WhileSubscribed` 形同虚设（订阅永不中断），等于多绕一层。结构冗余（`_uiState` 与内部 `stateIn` 双份状态）。 |
| `HistoryScreen.kt:172-185` | 桶值（日/周/月/年）由**全部场次**（`sessions`，未经过滤）计算（L175），而列表 `rows` 已按周期过滤。语义上"可选桶列表"取全集是合理的，但与 `共 N 场` 的口径不同，**不确定**是否会引起用户误解。 |
| `HistoryScreen.kt:200-220` | `StatsGranularity` 同款问题在此处不适用，但桶值 chips 在 `StreamerHistoryScreen` 中**根本不存在 UI**（`bucketValues` 仅被传入 `FilterDialog` 的 `bucketValues` 参数，而 `FilterDialog` 内**未使用该参数**，见 L557-561 定义与 L583-635 函数体）。**`bucketValues` 是一个被计算、被传递、却从未渲染的死参数。** |
| `HistoryScreen.kt:557-561` | `FilterDialog(bucketValues: List<String>)` 形参在整个函数体（L562-687）中无任何引用 → 死参数（与上一条同因）。 |
| `HistoryScreen.kt:565-566` | `var showCalendar by remember { mutableStateOf(false) }` 与其上 `DateParts` 的 `remember` 均**无 key**：`FilterDialog` 每次打开都是新的 composable 实例（由 `if (showFilterDialog)` 控制），故实际不会残留旧值；但若未来改为常驻挂载会立刻出现脏状态。**不确定**当前是否有可复现缺陷。 |
| `HistoryScreen.kt:670` | `val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)` 在 `if (showCalendar)` 内创建 —— 每次打开日历是新的 `remember` 作用域，`initialMillis` 会生效，行为正确。 |
| `HistoryScreen.kt:696` + `:302` | `ManualDialog` 的 `DurationParts` / `DateTimeParts` 用无 key `remember`，但由 `if (showManualDialog)` 控制存在性，重新打开会重置 —— 符合 L694 注释"不默认当前时间"的意图。 |
| `HistoryScreen.kt:663-665` | `YearMonth.parse(v, DateTimeFormatter.ofPattern("yyyy-MM"))` 用 `ofPattern` 而非 `DateTimeFormatter.ofPattern(..., Locale)`；在非公历/非拉丁 locale 下 `yyyy` 的解析可能异常，但此处 `v` 由 `deriveKeyFromCalendarDate`（L550，`String.format("%04d-%02d")`）生成，格式受控。**不确定**是否在非英语 locale 机器上有风险。 |
| `HistoryScreen.kt:694` | 注释 `// 不默认当前时间：留空表示由用户填写` 与 `ManualDialog` 实际行为一致；但结合"补全逻辑未接线"，用户必须手填三项中的两项以上且无法获得自动补全辅助。 |
| `HistoryScreen.kt`（`StreamerHistoryScreen` 全文） | **没有 `Scaffold` 的 snackbar 承载**、也没有任何 `Text` 渲染 `message` —— 与 §2 相同的"反馈通道缺失"。 |

---

## 4. 统计 · `ui\stats\StatsScreen.kt`（416 行）+ `ui\stats\StatsListScreen.kt`（186 行）

### 4.1 第 1 级：`StatsListScreen`（主播统计列表）

**具体交互**
- 顶部栏标题 `统计`（L113）
- 搜索框占位符 `搜索主播名字`，`Icons.Filled.Search`（L116-126）
- 主播卡片（L141-180）：
  - 头像 + `s.name`
  - `最近：${formatTime(s.lastSessionAt)}`（无则 `—`）
  - 右侧两行：`${s.sessionCount} 场`（`labelMedium`）+ `formatDuration(s.totalSeconds)`（`primary` 色，`titleSmall`）
- 整卡 `clickable` → `onOpenStreamer(s.streamerId)`（L146）
- 空态（L134）：无查询 → `还没有统计数据\n确认开播后会自动记录`；有查询 → `没有匹配的主播`
- 列表按 `totalSeconds` 降序（L89）

**ViewModel / DAO 方法**（`StatsListViewModel`，L62-102）
- `LiveSessionDao.observeRecent(1000)`（L70）、`StreamerDao.observeActive()`（L71）
- 自算 `sessionCount = list.size`（L84）、`totalSeconds = list.sumOf { it.durationSeconds ?: 0L }`（L85）
- `setQuery`（L99）

### 4.2 第 2 级：`StreamerStatsScreen`

**顶部栏**（L168-177）：标题 `state.streamerName.ifBlank { "统计" }` + 返回箭头（`contentDescription = "返回"`）

**粒度 chips**（L181-197）
- `StatsGranularity.entries`（`StatsGranularity.kt:15` 定义：`DAY("按日")`、`WEEK("按周")`、`MONTH("按月")`、`YEAR("按年")`）
- 额外 chip `自定义`（L192-196）→ 打开自定义区间对话框
- 选中态：`!state.usingCustom && state.granularity == g`（即选了自定义时粒度 chip 全部取消高亮）

**桶值 chips**（`LazyRow`，L200-220，仅 `!usingCustom && bucketValues.isNotEmpty()`）
- 每个值一个 `FilterChip`；`WEEK` 类型显示为 `自 $v 起的一周`，其余直接显示值（L211-214）

**自定义区间展示**（L221-231）
- 文本 `自定义区间：{起} ~ {止}（含当天）`（`labelMedium` + `primary` 色）
- 文字按钮 `修改区间` → 重新打开对话框

**错误提示**（L233-240）：`state.error` 以 `error` 色展示；`LaunchedEffect(state.error)` 3000ms 后 `clearError()`（L160-165）

**加载态**（L242-248）：整块 `CircularProgressIndicator`

**汇总卡**（L256-279）
- 标题：`自定义区间` 或 `periodTitle(granularity, bucketValue)`（L411-415 产出 `按日 · {v}` / `按周 · 自 {v} 起的一周` / `按月 · {v}` / `按年 · {v} 年`）
- `场次 N`（`result?.totals?.sessionCount ?: 0`）
- `总时长 {formatDuration(monitoredSeconds)}`（`primary` 色）
- `已修正 N`（`correctedSessionCount`）
- `暂定 N`（`provisionalSessionCount`）

**直播时长分布卡**（L280-305）
- 标题 `直播时长分布`
- `SeriesBarChart`（L343-367，`Canvas` 绘制圆角柱，高度 130dp）
- 轴标签：按 `step = ((pts.size + 7) / 8).coerceAtLeast(1)` 抽稀显示 `p.label`（L290-302）
- 空数据时 `return@Canvas`（L351 无占位文案）

**该时段场次明细卡**（L306-322）
- 标题 `该时段场次明细`
- 对 `sessionCount > 0` 的点逐条 `"${p.label}：${p.sessionCount} 场 · ${formatDuration(p.seconds)}"`
- 无数据时（L317-319）：`该时段没有已结束的场次`

**自定义时间范围对话框** `CustomRangeDialog`（L328-340 调用、L369-409 定义）
- 标题 `自定义时间范围`
- `DateFields(label = "开始日期")`、`DateFields(label = "结束日期（含当天）")`
- 无效日期提示 `存在无效日期（如 2 月 30 日），请检查`（L393）
- 按钮 `应用` / `取消`；错误 `请完整填写开始和结束的年月日`（L402）、`结束日期不能早于开始日期`（L403）
- 默认值：`今天-6天` ~ `今天`（L330-333）

**ViewModel / Repository 方法**（`StreamerStatsViewModel`，L75-147）
- `StatsSeriesRepository.allBucketValues(g)`（L95）
- `StatsSeriesRepository.currentBucketValue(g)`（L96）
- `StatsSeriesRepository.seriesFor(streamerId, granularity, value)`（L136）
- `StatsSeriesRepository.seriesCustom(streamerId, cs, ce)`（L129）
- `load(streamerId)`（L84）、`setGranularity(g)`（L90）、`setBucketValue(v)`（L103）、`setCustomRange(s, e)`（L110）、`clearError()`（L146）

以上方法全部存在（`StatsSeriesRepository.kt:85/118/121/127`）。

### 4.3 问题与可疑点

| 位置 | 问题 |
|---|---|
| `StatsScreen.kt:90-101` + `:110-119` | `setGranularity` 会置 `usingCustom = false`（L93），但**不清空 `customStart`/`customEnd`**；`setCustomRange` 置 `usingCustom = true` 但**不改 `granularity`**。状态组合本身自洽（用 `usingCustom` 单开关），但 `state.customStart/customEnd` 会长期残留旧值，`showCustomDialog` 每次以之为初值（L330-333）——**行为正确但状态冗余**。 |
| `StatsScreen.kt:121-144` | **`refresh()` 存在竞态**：L122 `val s = _state.value` 先快照，L124 在 `viewModelScope.launch` 内把 `s.copy(loading = true, ...)` 写回（L124），随后 await 挂起（L126/136 的 suspend 查询），L142 又用 `_state.value.copy(...)` 写回。若用户在挂起期间改了粒度或桶值，`_state.value = s.copy(...)`（L133 的早退分支）与后续赋值可能**用陈旧快照覆盖新选择**。典型触发：快速连点「按日 → 按周」再点某个桶值 chip。**代码层面可确认存在该窗口**；是否稳定复现**不确定**。 |
| `StatsScreen.kt:95-98` | `allBucketValues(g)` / `currentBucketValue(g)` 失败时被 `getOrDefault(emptyList())` / `getOrNull()` **静默吞掉**（L95-96）。若两者都为空/失败，`value` 为 null，`refresh()` 在 L132-135 直接 `result = null; loading = false` 返回 —— 页面显示「场次 0 / 总时长 —」而**不提示任何错误**，用户会误以为真的没有数据。 |
| `StatsScreen.kt:376-377` | `CustomRangeDialog` 的 `remember { DateParts().apply { setEpochDate(initialStart) } }` **无 key**。对话框由 `if (showCustomDialog)` 控制存在性，重新打开会重置，当前无实际缺陷；但 `initialStart`/`initialEnd` 参数（L330-333 每次重组都重新计算 `LocalDate.now()`）传入后**只被首次消费**，属隐含契约。 |
| `StatsScreen.kt:348` `:351` | `SeriesBarChart` 在 `points.isEmpty()` 时直接 `return@Canvas`，**留白无文案**；而同页明细卡有 `该时段没有已结束的场次`（L318）。图表区空数据无解释。 |
| `StatsScreen.kt:17` `:25` `:26` | 3 个**未使用的 import**：`androidx.compose.material.icons.filled.Tune`、`androidx.compose.material3.OutlinedTextField`、`androidx.compose.material3.RadioButton`（日期改由自定义 `DateFields` 五框/三框输入实现，属重构遗留）。 |
| `StatsScreen.kt:311-316` | 明细卡用 `forEach` 直接在 `Column` 内展开所有行（非懒加载）。粒度选择「按日」且区间很宽时可能有较多子项；受 `points` 规模约束，风险有限。 |
| `StatsListScreen.kt:42` | `import com.example.bilimonitor.data.repository.StatsSeriesRepository` **未使用**（死导入）。 |
| `StatsListScreen.kt:62-90` | `StatsListViewModel` 自行用 `observeRecent(1000)` + 内存 groupBy 计算汇总，**未复用**已存在的 `StatsSeriesRepository.streamerSummaries(nameQuery)`（`StatsSeriesRepository.kt:66`）。同页第 2 级用的却是该 Repository —— 两级数据口径由两套代码维护（注意 `streamerSummaries` 与 UI 自算是否完全一致，**未逐字段核对，标注不确定**）。 |
| `StatsListScreen.kt:84-85` | 与 `HistoryListScreen.kt:85` 同款：`observeRecent(1000)` 硬上限，超过 1000 场后统计会**静默少算**。 |

---

## 5. 设置 · `ui\settings\SettingsScreen.kt`（335 行）+ `ui\settings\SettingsViewModel.kt`（75 行）

### 5.1 具体交互与功能点（5 个分区卡片）

**分区一 `监控`**（L78-128）
- 开关 `开启监控`，副文案 `关闭后不再执行任何检查`（L88-91）
- 单选 `运行模式`，3 个 `RadioButton`（L95-110）：
  - `实时监控（前台服务，间隔更短）` → `MonitoringMode.REALTIME`
  - `省电监控（系统调度，约 15 分钟以上）` → `MonitoringMode.POWER_SAVING`
  - `手动（仅打开 App 时检查）` → `MonitoringMode.MANUAL`
- `NumberInputSetting` label `检查间隔（秒，60–3600）`，`validRange = 60..3600`，默认值 `180`（L112-117）
- `StepperSetting` label `开播确认次数：N（默认 1，立即通知）`（L118-122）
- `StepperSetting` label `关播确认次数：N（默认 2，防误报）`（L123-127）

**分区二 `通知聚合`**（L130-150）
- 开关 `批量开播合并通知`，副文案 `多位主播同时开播时合并为一条通知`（L133-143）
- `StepperSetting` label `合并阈值：N 个开播事件`，默认 4（L145-149）

**分区三 `后台保活`**（L152-170）
- 只读当前值（`keepAliveLabel`，L266-271）：
  - `当前：前台常驻通知` / `当前：无障碍服务（实验性）` / `当前：不启用保活` / `尚未选择（实时监控不会启动）`
- 按钮 `修改保活方式` → 打开对话框
- 文字按钮 `查看说明` → `onReonboard`（跳到 Onboarding 页）
- 权限提示（L162-169）：未授权时显示 `通知权限未授予：后台仍可监控，但无法发出通知`

**保活选择对话框**（L217-263）
- 标题 `选择后台保活方式`
- 3 个 `RadioButton`：`前台常驻通知（推荐）`、`无障碍服务（实验性）`、`不启用保活（可能漏掉通知）`
- 选 `NO_GUARANTEE` 时出现 `Checkbox` + `我了解可能漏掉通知`（L240-248）
- 按钮 `保存`（`enabled` 条件：非 `UNSET` 且（非 NO_GUARANTEE 或已勾选），L257-258）/ `取消`

**分区四 `高级请求策略`**（L172-190）
- `NumberInputSetting` label `单批主播数（10–100）`，`validRange = 10..100`，默认 50
- `NumberInputSetting` label `请求超时（秒，5–60）`，`validRange = 5..60`，默认 15
- `StepperSetting` label `最大重试次数：N`，默认 2

**分区五 `账号与数据`**（L192-203）
- 按钮 `B 站账号 / 关注导入` → `onOpenLogin`
- 按钮 `备份 / 恢复 / 导出` → `onOpenData`
- `OutlinedButton` `通知历史` → `onOpenNotifications`
- `OutlinedButton` `健康中心 / 诊断` → `onOpenDiagnostics`

**分区六 `关于`**（L205-212）
- `哔哩哔哩主播监控 v1.0`（**版本号硬编码**）
- `本地优先的直播监控工具；数据全部保存在本机。`

**复用组件**
- `SettingSectionCard(title, content)`（L273-282）：`titleMedium` + SemiBold 标题
- `NumberInputSetting`（L287-325）：数字键盘、`take(6)`、`isError`、错误文案 `请输入 ${first}–${last} 之间的整数`、`保存`（`enabled = !invalid && parsed != value`）
- `StepperSetting`（L327-334）：`−` / `+` 两个 `TextButton`，**边界为 `value > 1` 与 `value < 10`**

**消息**：`LaunchedEffect(message)` 2000ms 后清空（L60-65）—— 但**页面不渲染 `message`**（见问题表）

### 5.2 ViewModel / Repository 方法

| 方法 | 调用点 |
|---|---|
| `SettingsViewModel.config`（`StateFlow`） | `SettingsScreen.kt:55` |
| `SettingsViewModel.message`（`MutableStateFlow`，直接置 null） | `:56`、`:63` |
| `SettingsViewModel.notificationsEnabled()` | `:162` |
| `SettingsViewModel.setMonitoringEnabled(it)` | `:90` |
| `SettingsViewModel.update { it.copy(...) }` | `:106`、`:116`、`:121`、`:126`、`:142`、`:148`、`:177`、`:183`、`:188` |
| `SettingsViewModel.changeKeepAliveChoice(selected, acknowledged)` | `:254` |

ViewModel（`SettingsViewModel.kt`）：`ConfigRepository.getConfigFlow()`（L33）、`ConfigRepository.update(ConfigUpdate)`（L44）、`ConfigRepository.completeOnboarding(choice, acknowledged)`（L59）、`MonitoringController.syncWithConfig()`（L48、L62）、`ContextCompat.checkSelfPermission(POST_NOTIFICATIONS)`（L39-40）

### 5.3 问题与可疑点（含一处确定性功能缺陷）

| 位置 | 问题 |
|---|---|
| `SettingsScreen.kt:331` `:333` | `StepperSetting` 的边界是 **`> 1` 与 `< 10`**，即允许 1–10。该组件被用于 3 处，但 `ConfigRepository.merge()` 的校验是：`if (retries < 0 \|\| retries > 5) return null`（`ConfigRepository.kt:277`）。**`最大重试次数` 的 UI 可设置 6–10，而后端静默拒绝**，仅返回 `ConfigUpdateResult.Invalid("参数非法")`（L105），UI 显示 `保存失败：…` 或 `参数非法`（`SettingsViewModel.kt:52`）。**用户可以选中一个永远保存不上的值** —— 确定性的范围错配缺陷。`开播/关播确认次数` 与 `合并阈值` 无上限校验（仅 `>= 1`，`ConfigRepository.kt:278`），故 1–10 对它们安全。 |
| `SettingsScreen.kt:56` `:60-65` | `val message by viewModel.message.collectAsState()` 被收集、被计时清空，但**页面从不渲染 `message`**。因此 `设置已保存`、`设置已被其他修改更新，请重试`、`保存失败：…`、`保活方式已更新`、`更新失败，请重试`（`SettingsViewModel.kt:49/51/52/63/65`）**全部对用户不可见**。这是本页最严重的问题（与 §2、§3 同类）。 |
| `SettingsScreen.kt:162` | `if (!viewModel.notificationsEnabled())` 在 **Composable 组合体内直接调用**（内部执行 `checkSelfPermission`，L39-40）。每次重组都会做一次权限查询；且它不是 Compose 状态，**用户在系统设置里授权后返回本页不会自动刷新**（提示文字会一直显示到下次重组/重进页面）。 |
| `SettingsScreen.kt:294` | `var text by remember(value) { mutableStateOf(value.toString()) }` —— key 为传入的 `value`。场景：用户把 `检查间隔` 改成 5000（非法）→ 点保存按钮被禁用（`invalid`）→ 只能改回合法值；若用户输入合法但保存因版本冲突失败（`VersionConflict`），`config` 不变 → `value` 不变 → `remember` 不重置，输入框显示的是**未落库的值**，而 `保存` 按钮因 `parsed != value` 仍可点。属轻微状态不同步。 |
| `SettingsScreen.kt:218-221` | 对话框内 `remember { mutableStateOf(config?.backgroundKeepAliveChoice ?: UNSET) }` / `remember { mutableStateOf(config?.noGuaranteeAcknowledged ?: false) }` **无 key**。若对话框打开期间 `config` 异步到达（首帧 `config == null`），初值会停在 `UNSET`/`false`，且 `保存` 按钮因 `selected != UNSET` 被禁用 → **快速点击时对话框可能呈"不可保存"状态**。**不确定**实际触发频率（`config` 通常已在 `SettingsScreen` 首帧前完成 `stateIn` 初始 null → 首帧必为 null）。 |
| `SettingsScreen.kt:24` | `import androidx.compose.material3.Slider` **未使用**（死导入，且 L284-285 注释明确写「用户定稿：不用滑块」，属重构遗留）。 |
| `SettingsViewModel.kt:75` | `private fun ConfigUpdateResult.done(): Boolean = this == ConfigUpdateResult.Applied` —— **定义了但没有任何调用点**的私有扩展函数。死代码。 |
| `SettingsViewModel.kt:11` | `import com.example.bilimonitor.data.local.MonitoringMode` **未使用**（死导入）。 |
| `SettingsViewModel.kt:44` | `configRepository.update(transform(ConfigUpdate()))` —— 依赖 `ConfigUpdate` 全部字段默认 `null`（`ConfigRepository.kt:24-44`）与 `merge()` 的「null 表示不修改」语义（L268-278）。**当前实现正确**；但这是隐式契约：一旦给 `ConfigUpdate` 增加带非 null 默认值的字段，`update{}` 会把该字段无条件写成默认值，属隐患（非现存 bug）。 |
| `SettingsScreen.kt:206` | 版本号 `v1.0` 硬编码在 UI 中，与 `BuildConfig`/`versionName` 无关联。**不确定**是否为有意。 |
| `SettingsScreen.kt:160` | 按钮 `查看说明` 触发 `onReonboard`，实际行为是**重新进入 Onboarding 页**（`AppNavHost.kt:153-157`）。按钮文案与行为不完全对应（用户预期"查看说明"，实际进入选择页）。 |
| `SettingsScreen.kt:236` | `Text(label)` 未指定样式（默认 `bodyLarge`），与同卡内 L108 的 `style = bodyMedium` 不一致。纯样式瑕疵。 |

---

## 6. 登录 / 关注导入 · `ui\login\LoginScreen.kt`（407 行，含内联 `LoginViewModel`）

### 6.1 具体交互与功能点

**顶部栏**（L217-223）：标题 `B 站账号（可选）`；`navigationIcon` 位置放的是 `TextButton("返回")`（非箭头图标）

**账号卡**（L229-257）
- 说明：`登录仅用于「关注列表导入」；核心监控不需要登录。`
- `state.account.checking` → `CircularProgressIndicator(20dp)`
- 已登录（L235-244）：`已登录：${uname}`、`UID ${mid}`、按钮 `开始导入`、`OutlinedButton` `退出登录`
- 未登录（L246）：按钮 `扫码登录`
- 错误：`state.error`（`error` 色）；结果：`state.importResult`（`primary` 色）

**导入设置卡**（L259-306，仅 `state.account.loggedIn`）
- 标题 `导入设置`
- `ImportSource.entries` 单选（`FollowImportRepository.kt:29`）：
  - `ImportSource.LIVE_WATCH_HISTORY` → 说明 `从观看历史中的直播分类，获取最近看过的主播`
  - `ImportSource.FOLLOWINGS` → 说明 `从关注列表获取（分批获取，不全量拉取）`
- 数量输入（L290-303）：`获取数量：` + `OutlinedTextField`（宽 100dp，数字键盘，`take(4)`），本地初值 `countText = "50"`

**二维码区**（L308-330，`when (state.qrState)`）
| 状态 | 展示 |
|---|---|
| `QrReady` | 卡片内 `Image(bitmap = state.qrBitmap, contentDescription = "登录二维码")` 220dp + 文案 `用哔哩哔哩 App 扫码登录` |
| `ScannedWaitingConfirm` | `已扫码，请在手机上确认…` |
| `WaitingScan` | `等待扫码…` |
| `Expired` | `二维码已过期` + 按钮 `重新生成` |
| `Confirmed` | `登录成功：${qr.account.uname}` |
| `Failed` | `qr.message`（`error` 色） |
| `else` | `{}` —— **空分支** |

**导入预览对话框** `ImportPreviewDialog`（L335-346 调用、L349-407 定义）
- 显示条件：`showImportPreview && state.importStage == "PREVIEW"`
- 标题：`选择要导入的主播（已选数/总数）`
- 搜索框占位符 `搜索主播名`（`RoundedCornerShape(12.dp)`）
- `LazyColumn(Modifier.height(360.dp))` 列表项：`Checkbox` + 32dp 头像 + `f.name` + `f.uid`
- 整行 `clickable` 与 `Checkbox.onCheckedChange` 都调 `onToggle(f.uid)`（L383、L385）
- 空列表时显示 `CircularProgressIndicator(24dp)`（L376-377）
- 按钮 `导入所选`（`enabled = state.selectedUids.isNotEmpty()`）/ `取消`

### 6.2 ViewModel / Repository 方法

`LoginViewModel`（L82-204）：
- `AuthRepository.refreshAccount()`（L92）
- `AuthRepository.account`（Flow，L94）
- `AuthRepository.qrState`（Flow，L97）
- `AuthRepository.generateQr()`（L107）
- `AuthRepository.pollQrOnce(key)`（L110）
- `AuthRepository.logout()`（L192）
- `FollowImportRepository.startImport(source, count)`（L130）
- `FollowImportRepository.latestTask()`（L146）
- `FollowImportRepository.listStaging(taskId)`（L148）
- `FollowImportRepository.applyImport(taskId, selectedUids)`（L178）
- 本地：`setImportSource`（L118）、`setImportCount`（L122）、`setPreviewQuery`（L165）、`toggleSelect`（L169）、`clearError`（L195）、`qrBitmap(content, size = 560)`（L197-203，用 ZXing `QRCodeWriter`）

以上方法均存在（`AuthRepository.kt:54/66/80/133`、`FollowImportRepository.kt:56/54/247/171`）。

`LoginScreen` 调用：`state`（L209）、`startFollowImport`（L240）、`logout`（L244）、`startQrLogin`（L246、L325）、`clearError`（L214）、`toggleSelect`（L338）、`setPreviewQuery`（L339）、`applyImport`（L341）

### 6.3 问题与可疑点

| 位置 | 问题 |
|---|---|
| `LoginScreen.kt:329` | `else -> {}` —— **空分支，渲染任何内容**。`QrLoginState` 的 sealed interface（`AuthRepository.kt:26-33`）包含 `Idle` 对象；页面初始 `qrState = QrLoginState.Idle`（L69）。`Idle` 与 `QrReady`/`WaitingScan`/`ScannedWaitingConfirm`/`Expired`/`Confirmed`/`Failed` 已被显式覆盖（L309-328），因此 `else` **恰好只兜住 `Idle`**。结果：**未登录且未生成二维码时，二维码区域完全空白**（无占位、无提示）。可确认为设计缺陷（`Idle` 应显示提示或"生成二维码"按钮）。 |
| `LoginScreen.kt:239-242` + `:335` `:340-343` | **`开始导入` 的预览弹窗时序缺陷**：点击按钮时同时 `viewModel.startFollowImport()` 与 `showImportPreview = true`（L240-241），但对话框真正的显示条件还要求 `state.importStage == "PREVIEW"`（L335）。`importStage` 只会由 `pollImportTask` 在轮询到 `FollowImportStatus.PREVIEW` 时置位（L150）。**若导入失败或超时**（L155-161），`showImportPreview` 永远停在 `true` 而 `importStage` 不是 `PREVIEW` → **对话框永不出现**，用户只看到卡片里一行错误文字，且**没有重试/关闭入口**。 |
| `LoginScreen.kt:376-377` | `if (filtered.isEmpty()) { CircularProgressIndicator(...) }` —— **空结果被当作"加载中"**。两种误报：① 搜索关键词无匹配时应显示"没有匹配的主播"，却显示转圈；② 预览列表本身为空（后端返回 0 条）时同样无限转圈。用户无法区分"正在加载"与"没有数据"。 |
| `LoginScreen.kt:211` + `:294` | `var countText by remember { mutableStateOf("50") }` 是**独立于 ViewModel 的本地状态**，其初值 `"50"` 与 `LoginUiState.importCount` 默认值 `50`（L72）靠巧合一致。若二者将来不同步，UI 显示与提交值会不一致。且 `remember` 无 key，不受 VM 影响。 |
| `LoginScreen.kt:295-298` | `countText.toIntOrNull()?.let { viewModel.setImportCount(it) }` —— 输入框清空或超范围时**不回调**，VM 保留旧值；而 `setImportCount` 内部有 `coerceIn(1, 500)`（L123），**UI 输入框显示的却是未 clamp 的原文**（例如输入 `9999` → 框内显示 `9999`，VM 收到 `500`）。显示与生效值不一致，无任何提示。 |
| `LoginScreen.kt:143-163` | `pollImportTask` 用 `followImportRepository.latestTask()` **只比对"最新任务"**（L147、L155 都要求 `task?.importTaskId == taskId`）。若用户在轮询期间再次发起导入，`latestTask()` 会变成新任务 → 旧轮询既拿不到 `PREVIEW` 也拿不到 `FAILED`，只能空转到 **60 × 1.5s ≈ 90 秒**后报 `导入超时`（L161）。 |
| `LoginScreen.kt:109-113` | 扫码轮询 `for (i in 1..90) { pollQrOnce(key); delay(2000) }` 固定在 **`viewModelScope`** 上，**共约 3 分钟且无取消机制**。用户离开 `LoginScreen` 时 NavBackStackEntry 弹出 → `LoginViewModel` 被清除 → 协程随之取消（依赖 Hilt/Compose 的 ViewModel 作用域），**大概率会被正确取消**；但若用户在 3 分钟内返回该页，是**新的 ViewModel 实例**，旧二维码的轮询已随旧实例销毁。**不确定**是否存在边缘泄漏（例如 `onBack` 用 `popBackStack` 后 VM 清理时机）。 |
| `LoginScreen.kt:197-203` | `qrBitmap()` **在 `authRepository.qrState` 的 collect 中同步执行**（L98），每次状态发射都对 560×560 = 313,600 个像素做 ZXing 编码 + `Bitmap.createBitmap`，位于协程（默认 `Dispatchers.Main` 的 `viewModelScope`）→ **主线程同步重计算**。有卡帧风险。 |
| `LoginScreen.kt:379` | `LazyColumn(Modifier.height(360.dp))` 放在 `AlertDialog` 的 `text` 槽内 —— 固定高度卡片式弹窗，屏幕较矮或字体放大时可能挤压/溢出。**不确定**是否有实际布局问题。 |
| `LoginScreen.kt:357-358` | 预览过滤只按 `it.name.contains(...)`，**不匹配 UID**；而列表项右侧显著展示 UID（L394），用户可能预期可搜 UID。 |
| `LoginScreen.kt:1-65` | 导入语句顺序不符合 ktlint 字典序（`androidx.compose.ui.text.input.KeyboardType` 在 L45、`androidx.compose.foundation.text.KeyboardOptions` 在 L46），纯风格问题。 |

---

## 7. 数据管理 · `ui\data\DataScreen.kt`（373 行，含内联 `DataViewModel`）

### 7.1 具体交互与功能点

**顶部栏**（L187-189）：标题 `数据管理`；`navigationIcon` 为 `TextButton("返回")`

**状态提示区**
- `state.busy` → 卡片 `正在操作（已进入维护模式，监控暂停）…` + `CircularProgressIndicator`（L196-201）
- `state.message` → 普通卡片文本（L203-205），4000ms 后 `clearMessage()`（L182-184）
- `state.restoreSummary` → `primary` 色卡片（L206-208）

**恢复预检卡**（L211-248，`state.precheck != null`）
- 标题 `恢复预检通过`
- 信息：`备份时间：{...}` —— 实为 `java.time.Instant.ofEpochMilli(pre.manifest.createdAt)` 的默认 `toString()` 直接入模板（L216，**未调用任何 Formatter**，产出 ISO-8601 UTC 形式）
- `包含主播 N、场次 M`（`pre.manifest.recordCounts["streamers"]` / `["sessions"]`）
- `将新增主播 N，可恢复场次 M`
- 无冲突：`无身份冲突`（`primary` 色）
- 有冲突（L224-241）：`身份冲突 N 条：`，每条 `· {stableId 后 8 位}…（{描述}）当前决策：{decision ?: "未决策"}`；描述映射（L226-230）：`STABLE_ID_DIFFERENT_UID_SAME` → `同 stableId 不同 UID`；`STABLE_ID_SAME_UID_DIFFERENT` → `同 UID 不同 stableId`；其他 → 原始 `c.type.name`
- 3 个 `OutlinedButton`：`全部采用备份`、`全部保留本地`、`全部跳过`
- `Button` `执行恢复` + `OutlinedButton` `取消`

**分区 `备份与恢复`**（L250-260）
- `Button` `导出备份`（`enabled = !state.busy`）
- `OutlinedButton` `从 Download 恢复`（`enabled = !state.busy`）
- 说明：`备份包含主播、场次、事件、修正、标签与策略；不含运行时状态与登录凭证。范围内以备份文件为权威，范围外本地数据不变。`

**分区 `导出`**（L262-271）
- `Button` `导出 HTML 报告` → 打开范围对话框
- 说明：`按导出快照机制生成：先落一致快照再读取，导出期间监控短暂进入维护模式。`

**分区 `历史容量`**（L273-290）
- 当前值：`当前：不限制` 或 `当前：最多保留 N 条已结束场次`
- `OutlinedButton` `修改容量`、`OutlinedButton` `立即清理`（`enabled = !state.busy`）
- 说明：`清理只删除最旧的已结束场次；通知 Outbox 保留 30 天、通知历史保留 90 天。`

**导出范围对话框**（L295-315）
- 标题 `导出范围`
- 4 个 `RadioButton`：`最近 7 天`(7)、`最近 30 天`(30)、`最近 90 天`(90)、`最近一年`(365)；本地初值 `days = 30`
- 按钮 `导出` / `取消`

**历史容量对话框**（L317-361）
- 标题 `历史容量`
- `RadioButton` `不限制`（`MODE_UNLIMITED`）
- `RadioButton` `最多保留：`（`MODE_MAX_RECORDS`）
- 选 `MODE_MAX_RECORDS` 时出现 4 个 `OutlinedButton`：`100`、`500`、`1000`、`5000`，当前值 `primary` 色
- 按钮 `保存` / `取消`

**辅助组件** `SettingCard(title, content)`（L364-373）

**消息文案**（`DataViewModel`）：`备份已导出到 Download/$it`、`备份失败：…`、`HTML 已导出到 Download/${it.fileName}（${it.sessionCount} 场）`、`导出失败：…`、`Download 中没有找到备份文件（先执行导出）`、`预检未通过：…`、`恢复失败：…`、`清理完成：场次 N、Outbox M、通知历史 K`、`清理失败：…`、`恢复完成：新增主播 …、场次 …、事件 …，跳过场次 …；警告：…`

### 7.2 ViewModel / Repository 方法

`DataViewModel`（L60-171）：
- `BackupRepository.exportBackup()`（L78）
- `BackupRepository.readLatestBackup()`（L100）
- `BackupRepository.precheck(content)`（L105）
- `BackupRepository.decideAll(runId, action)`（L117）
- `BackupRepository.listConflicts(runId)`（L118）
- `BackupRepository.applyRestore(runId, content)`（L130）
- `BackupRepository.cancelRun(runId)`（L147）
- `ExportRepository.exportHtml(days)`（L89）
- `MaintenanceRepository.runCleanup()`（L154）
- `MaintenanceRepository.setCapacity(mode, max)`（L163、L167）
- `MaintenanceRepository.capacityMode` / `maxRecords`（Flow，L70-73）
- `clearMessage()`（L170）

UI 调用：`exportBackup`（L252）、`precheckLatestBackup`（L253）、`exportHtml`（L311）、`runCleanup`（L283）、`decideAll`（L237-239）、`applyRestore`（L243）、`cancelRestore`（L244）、`setCapacityMaxRecords` / `setCapacityUnlimited`（L354-355）、`state`/`capacityMode`/`maxRecords`（L176-178）

所有被引用成员均存在（`BackupRepository.kt:248/691/303/679/688/395/683`、`ExportRepository.kt:39`、`MaintenanceRepository.kt:64/54/51/52`、`BackupRepository.kt:121 RestoreSummary`、`:291 PrecheckResult`）。`RestoreSummary` 的 `restoredCounts`/`skippedCounts`/`warnings`（L121-127）与 `PrecheckResult.Ready` 的 `restoreRunId/manifest/conflicts/newStreamers/sessionsToRestore`（L292-299）字段名与 UI 用法逐一匹配。

### 7.3 问题与可疑点

| 位置 | 问题 |
|---|---|
| `DataScreen.kt:226-230` | 冲突类型用 **`when (c.type.name)` 字符串字面量**判断（`"STABLE_ID_DIFFERENT_UID_SAME"` / `"STABLE_ID_SAME_UID_DIFFERENT"`），而非直接 `when (c.type)`。枚举重命名或新增值时不会编译报错，会静默退化到 `else -> c.type.name` 并**把英文枚举名暴露给用户**（L229）。脆弱且不一致。 |
| `DataScreen.kt:216-217` | `java.time.Instant.ofEpochMilli(pre.manifest.createdAt)` **未调用任何 Formatter**，直接进字符串模板 → 显示为 ISO-8601（如 `2024-05-01T12:34:56.789Z`，UTC），**与全工程其它时间显示（`formatTime` → `yyyy-MM-dd HH:mm`，本地时区）不一致**。用户可见的格式突兀点。 |
| `DataScreen.kt:318-319` | `var selected by remember { mutableStateOf(capacityMode) }` / `var max by remember { mutableStateOf(maxRecords) }` **无 key**，与 §5 同类问题（对话框由 `if (showCapacityDialog)` 控制存在性，当前不会残留）。 |
| `DataScreen.kt:70-74` + `:64` | `DataViewModel` 同时通过 `stateIn` 暴露 `capacityMode`/`maxRecords`（L70-73），又把 `maintenanceRepository` **以 `val`（public）暴露**（L64）。UI 用的是前者（L177-178），后者无 UI 使用点 —— 冗余的公开面。 |
| `DataScreen.kt:199` | 文案 `正在操作（已进入维护模式，监控暂停）…` 是一个**笼统的全局提示**。但 `MaintenanceRepository.runCleanup()` 进入的是 `MaintenanceMode.DATABASE_REPAIR`（`MaintenanceRepository.kt:65`），而 `exportHtml`/`applyRestore` 各自可能进入不同的维护模式。**不确定**该文案对"导出/恢复"路径是否同样准确（未逐条核对 `ExportRepository`/`BackupRepository` 的维护模式取值）。 |
| `DataScreen.kt:163` | `setCapacityUnlimited()` 调 `maintenanceRepository.setCapacity(MODE_UNLIMITED, 1000)` —— 传了 `maxRecords = 1000`，而 `setCapacity` 内只在 `mode == MODE_MAX_RECORDS` 时写入该值（`MaintenanceRepository.kt:57`），故 1000 是无害占位。**不是 bug**，但语义上易误读。 |
| `DataScreen.kt:341` | 容量档位硬编码 `listOf(100, 500, 1000, 5000)`；`setCapacity` 的合法区间是 `coerceIn(10, 100000)`（`MaintenanceRepository.kt:57`）。UI 只提供 4 档，**无自定义输入**。功能受限，非缺陷。 |
| `DataScreen.kt:127-133` | `items(state.problems.size) { i -> … }` 等用法风格不一致（同工程其它列表用 `items(list, key = …)`）。此处**未提供 key**，列表项复用依赖索引。纯健壮性/风格问题。 |
| `DataScreen.kt:31` `:28` | `import ...getValue` / `...setValue` 实际由 `by` 委托使用，非未使用（脚本误报）。无问题。 |

---

## 8. 通知历史 · `ui\notifications\NotificationHistoryScreen.kt`（111 行）

### 8.1 具体交互与功能点

- 顶部栏（L51-53）：标题 `通知历史`；`navigationIcon` 为 `TextButton("返回")`
- 空态（L55-60）：`暂无通知记录`
- 列表（`LazyColumn`，L62-85），每项一张 `Card`（`items(items, key = { it.historyId })`）：
  - 左侧：`eventLabel(h.eventKey, h.detail)`（`FontWeight.Medium`，`weight(1f)`）
  - 右侧：`statusLabel(h.deliveryStatus.name)`，颜色由 `statusColor(...)` 决定
  - 副行：`"${formatTime(h.recordedAt)} · 投递 ${h.attemptCount} 次"` + 可选 `" · ${h.reason}"`（L75-80）
- `eventLabel` 映射（L89-95）：
  - `evt:` 前缀 → `开播/关播通知`
  - `agg:` 前缀 → `批量开播通知`
  - 含 `:recovered` 或 `detail == "RECOVERED"` → `系统恢复通知`
  - `problem:` 前缀 → `系统问题通知`
  - 其他 → `通知`
- `statusLabel` 映射（L97-104）：`SENT` → `已送达`；`FAILED` → `失败`；`EXPIRED` → `已过期`；`CANCELLED` → `已取消`；`PENDING` → `待投递`；其他 → 原样返回
- `statusColor`（L106-110）：`SENT` → `primary`；`FAILED`/`EXPIRED` → `error`；其他 → `onSurfaceVariant`

### 8.2 ViewModel / DAO 方法

- `NotificationHistoryViewModel.items`（`StateFlow`，L41-42）← `NotificationHistoryDao.observeRecent(200)`（`NotificationDaos.kt:253`，SQL 为 `ORDER BY recordedAt DESC LIMIT :limit`）
- UI 仅消费 `viewModel.items`（L49）

### 8.3 问题与可疑点

| 位置 | 问题 |
|---|---|
| `NotificationHistoryScreen.kt:97-104` | **`statusLabel` 覆盖不全**：`NotificationHistoryDeliveryStatus` 枚举实际值为 `CREATED, PENDING, PROCESSING, SENT, DELIVERY_UNKNOWN, FAILED, EXPIRED, CANCELLED`（`data\local\*.kt`）。其中 **`CREATED`、`PROCESSING`、`DELIVERY_UNKNOWN` 未映射**，会走 `else -> status` **把英文枚举名直接显示给用户**。而 `NotificationDispatcher.kt:102` 会写入 `PROCESSING` 状态 → **该英文文案在真实数据中必然出现**。确定为本地化遗漏。 |
| `NotificationHistoryScreen.kt:89-95` | `eventLabel` 基于**字符串前缀/子串匹配**（`startsWith`/`contains`）而非结构化字段，且匹配顺序敏感：`eventKey.contains(":recovered")`（L92）排在 `startsWith("problem:")`（L93）之前 —— 对 `problem:{key}:recovered`（其生成规则见 `NotificationOutboxWriter.kt:332-335`）会正确命中"系统恢复通知"。**当前逻辑正确**，但依赖隐式约定，且 `eventKey.contains(":recovered")` 也会误命中任何含该子串的单事件 key。脆弱但暂无错。 |
| `NotificationHistoryScreen.kt:106` | `@androidx.compose.runtime.Composable` 用了**全限定名而非 import**（与文件其余部分风格不一致，且 `statusColor` 定义为 `private` Composable 但只有一行 `when` 返回颜色）。纯风格。 |
| `NotificationHistoryScreen.kt`（全文） | 页面**只读**：无删除、无清空、无重试、无筛选/搜索。200 条硬上限（`observeRecent(200)`）且用户不可见该限制。功能范围问题，非缺陷。 |
| `NotificationHistoryScreen.kt:76-80` | `h.reason` 直接拼接展示，未做本地化/截断；长错误文本可能撑高卡片。**不确定**实际文案长度。 |

---

## 9. 健康中心 / 诊断 · `ui\diagnostics\DiagnosticsScreen.kt`（147 行）

### 9.1 具体交互与功能点

- 顶部栏（L84-86）：标题 `健康中心`；`navigationIcon` 为 `TextButton("返回")`
- **当前状态卡**（L93-106）
  - 标题 `当前状态`
  - `state.health?.userMessage ?: "…"`
  - `监控 N 位主播 · 其中 M 位在播 · 前台服务：运行中/未运行`（L99-103，`foregroundServiceRunning`）
- **`活动问题（N）`** 小节（L107-115）
  - 空态 `无活动问题`
  - 每条卡片：`p.problemKey` + `自 ${formatTime(p.firstObservedAt)} 起`
- **`健康事件（最近）`** 小节（L116-124）
  - 空态 `暂无记录`
  - 每条卡片：`${formatTime(h.occurredAt)} · ${h.component}: ${h.fromStatus?.name ?: "—"} → ${h.toStatus.name}`
- **`监控错误（最近 N）`** 小节（L125-133）
  - 空态 `暂无监控错误`
  - 每条卡片：`${formatTime(e.occurredAt)} · ${e.errorCode.name}` + 可选 `主播 {stableId 后 8 位}…`
- 辅助组件：`SectionTitle`（L139-142）、`EmptyHint`（L144-147）

### 9.2 ViewModel / DAO 方法

`DiagnosticsViewModel`（L54-76）：
- `HealthRepository.observeHealth()`（L62）
- `MonitoringGapDao.observeRecent(50)`（L63）
- `ProblemDao.observeActive()`（L64）
- `LogDao.observeHealthEvents(50)`（L65）
- `LogDao.observeMonitoringErrors(50)`（L66）

`DiagnosticsScreen` 仅消费 `viewModel.state`（L82）。`OverallHealth` 字段用法（`userMessage`/`monitoredCount`/`liveCount`/`foregroundServiceRunning`）与 `HealthRepository.kt:21-31` 定义一致。

### 9.3 问题与可疑点（重点）

| 位置 | 问题 |
|---|---|
| `DiagnosticsScreen.kt:63` + `:70` | **`gapDao.observeRecent(50)` 的结果被主动丢弃**：`combine` 的第 2 个流 `gaps` 被接收，但构造 `DiagnosticsState` 时硬写 `openStreamGaps = emptyList()`（L70）。`DiagnosticsState.openStreamGaps`（L47）在整个文件中**只有这一处赋值，且恒为空列表**，UI 也从未渲染它。**结果：数据库流被订阅、数据被计算，却 100% 被丢掉** —— 确定的死代码 + 无效订阅（每 50 条 gap 变化都会触发一次无意义的 `combine` 重算）。 |
| `DiagnosticsScreen.kt:61-67` | `combine(health, gaps, problems, healthEvents, monErrors)` 只用了 5 个流中的 4 个结果（见上）。另外 `LogDao.observeAppErrors(50)`（`ConfigDaos.kt:271`，`ApplicationErrorLogEntity` 流）**存在但未被订阅**，`DiagnosticsState.appErrors`（L51）**从未被赋值**（保持默认 `emptyList()`），UI 也不渲染。即：**应用级错误日志在诊断页完全不可见**，尽管 state 里有这个字段、DAO 里有对应流。 |
| `DiagnosticsScreen.kt:121` | 健康事件显示 `h.fromStatus?.name` / `h.toStatus.name` —— **枚举英文名**（如 `HEALTHY`/`DEGRADED`）直接暴露给用户，未本地化（同 §2、§3、§8 的同类问题）。 |
| `DiagnosticsScreen.kt:130` | `e.errorCode.name` —— 同上，`MonitoringErrorCode` 枚举英文名直接展示。 |
| `DiagnosticsScreen.kt:131` | 主播标识用 `it.takeLast(8)` **只显示 stableId 的后 8 位**（如 `…a1b2c3d4`），用户无法对应到具体主播（既不显示昵称也不显示完整 ID）。可用性问题。 |
| `DiagnosticsScreen.kt:112` | `p.problemKey` **原样展示内部 key**（如 `MONITOR_ERROR_RATE`）而非中文描述。用户可读性差。 |
| `DiagnosticsScreen.kt:127-133` | `items(state.monitoringErrors.size) { i -> … }`（以及 L109、L118 同样写法）**不提供 key**，列表项复用靠索引。同 §7 风格问题。 |
| `DiagnosticsScreen.kt:98` | `state.health?.userMessage ?: "…"` —— 加载中的占位符是单个省略号 `…`，无骨架/转圈，首屏观感差。 |
| `DiagnosticsScreen.kt:44` | `val health: OverallHealth? = null` 默认 null；`combine` 首帧即给出真实值（`observeHealth()` 是 Flow），故 `"…"` 只会在极短瞬间出现。 |

---

## 10. 首次引导 · `ui\onboarding\OnboardingScreen.kt`（181 行）

### 10.1 具体交互与功能点

无 `Scaffold`/`TopAppBar`，纯 `Column` + `verticalScroll`（L86-91）。

- 标题 `欢迎使用主播监控`（`headlineSmall` + Bold，L92）
- 副标题 `首次使用，请选择一种后台保活方式。选择后可随时在设置页修改。`（L94-98）
- 三张可选卡片 `KeepAliveOptionCard`（L101-134）：
  1. `选项一：前台常驻通知` → `FOREGROUND_NOTIFICATION`，4 条说明（L105-110）：
     - `用途：在通知栏常驻一条"正在监控 xx 位主播"的通知，用前台服务换取后台持续运行。`
     - `需要：通知权限（Android 13+ 需用户授权）。`
     - `代价：通知栏会一直有一条通知；Android 15+ 上系统对这类前台服务有累计运行时长限制，到达上限后会被系统停止。`
     - `可随时：在设置页切换为其他方式。`
  2. `选项二：无障碍服务（实验性）` → `ACCESSIBILITY`，5 条说明（L117-123），含 `承诺：本服务不读取、不上传、不记录与主播监控无关的屏幕内容、输入事件或个人数据。`
  3. `选项三：不启用保活（明确告知风险）` → `NO_GUARANTEE`，2 条说明（L130-133）
- 选中视觉：`primaryContainer` vs `surfaceVariant` 底色（L163-165）；整卡 `onClick = onSelect`（L166）
- 选 `NO_GUARANTEE` 时显示 `Checkbox` + `我已了解：不保证后台一直存活，可能漏掉通知`（L135-141）
- 底部 `Button`（`fillMaxWidth`，L143-149）：文案 `确认选择`，保存中为 `保存中…`；`enabled = !saving && !(selected == NO_GUARANTEE && !acknowledged)`

`KeepAliveOptionCard`（L154-181）：`Card` + `RadioButton(selected, onClick = onSelect)` + 标题 + 逐行说明。

### 10.2 ViewModel / Repository 方法

`OnboardingViewModel`（L39-74）：
- `selected` / `noGuaranteeAcknowledged` / `saving`（均为 `MutableStateFlow`，L45-47）
- `select(choice)`（L49-54，切走时重置 ack）
- `acknowledge(value)`（L56-58）
- `complete(onDone)`（L60-73）→ `ConfigRepository.completeOnboarding(choice, noGuaranteeAcknowledged)`（L65-68）+ `MonitoringController.syncWithConfig()`（L69）+ `onDone()`

UI 调用：`viewModel.selected`/`noGuaranteeAcknowledged`/`saving`（L82-84）、`select`（L104/116/129）、`acknowledge`（L138）、`complete(onDone)`（L144）

### 10.3 问题与可疑点

| 位置 | 问题 |
|---|---|
| `OnboardingScreen.kt:65-71` | **`ConfigRepository.completeOnboarding(...)` 的 `Boolean` 返回值被完全忽略**（L65-68 未接收返回值），随后无条件 `monitoringController.syncWithConfig()`（L69）、`saving.value = false`（L70）、`onDone()`（L71）。该方法在 `ConfigRepository.kt:176-182` 中会因 `casUpdateConfig` 影响行数为 0 而返回 `false`（例如配置行缺失时 `updateKeepAliveChoice` 返回 0，`rows > 0` 为假，L260）。**结果：落库失败时 UI 仍当作成功并跳转首页**；由于 `MainActivity.kt:52-57` 依据 `backgroundKeepAliveChoice == UNSET` 决定起始页，下次冷启动会**再次回到 Onboarding**，用户会陷入"选了但没保存，重启又要选"的循环且无任何错误提示。可确认为缺陷（触发需 `rows == 0` 的边界条件，**不确定**在正常首次安装流程下是否常见，但代码路径确实存在）。 |
| `OnboardingScreen.kt:60-73` | `complete()` 中若输入非法则**静默 `return`**（L62：`if (choice == NO_GUARANTEE && !acknowledged) return`）。UI 侧虽有 `enabled` 兜底（L145），双保险；但静默 return 本身缺少任何反馈通道。 |
| `OnboardingScreen.kt:143-149` | `saving` 期间按钮变 `保存中…` 但**其余控件（3 张卡片、Checkbox）未禁用**，用户可在保存过程中继续点击改变选择（`selected` 会变，但 `complete` 已捕获旧值 `choice`，L61）。存在轻微竞态窗口。 |
| `OnboardingScreen.kt:86-91` | 页面**未使用 `Scaffold`**，因此没有 `systemBars` padding 处理（对比其余页面均用 `Scaffold`）。在刘海屏/手势条设备上底部 `Button` 可能被系统栏遮挡。**不确定**实际表现（`MainActivity` 是否设置 `enableEdgeToEdge` 未核对）。 |
| `OnboardingScreen.kt:106` `:132` | 文案中 `xx 位主播`（L106）是**未替换的占位符**，直接展示给用户；L132 的引号内容 `"这段时间没有监控到"` 是产品文案的一部分。`xx 位主播` 疑为文案遗留（**不确定**是否有意作为说明性占位）。 |

---

## 11. 通用输入组件 · `ui\common\TimeInput.kt`（145 行）

### 11.1 具体内容

- **`DateTimeParts`**（L25-59）：5 个 `mutableStateOf` 字段 `year/month/day/hour/minute`
  - `isComplete()`（L32-34）：`year.length == 4 && month/day/hour/minute` 均非空
  - `toEpoch()`（L37-44）：完整且合法时返回 `LocalDateTime.of(...).atZone(systemDefault()).toInstant().toEpochMilli()`；否则（含 2 月 30 日、时=99 等）返回 `null`（try/catch 吞异常）
  - `setEpoch(millis)`（L46-58）：null 则全清空；否则按 `%02d` 填充月/日/时/分
- **`DateParts`**（L62-89）：仅 `year/month/day`；`toEpoch()` 返回**当天 00:00** 的 epoch 毫秒；`setEpochDate(millis)`
- **`smallTimeBox(label, value, maxLength, onValueChange, modifier)`**（L92-107）：`OutlinedTextField`，`onValueChange` 内 `input.filter { it.isDigit() }.take(maxLength)` + `KeyboardType.Number`
- **`DateTimeFields(label, parts, modifier)`**（L111-127）：label + `Row` 五框，宽度权重 `年 1.4f`、其余 `1f`，label 依次 `年/月/日/时/分`
- **`DateFields(label, parts, modifier)`**（L131-145）：label + `Row` 三框 `年/月/日`

### 11.2 使用方

| 使用方 | 用法 |
|---|---|
| `HistoryScreen.kt:625-626` | `DateFields("开始日期")`、`DateFields("结束日期（含当天）")`（自定义筛选） |
| `HistoryScreen.kt:722-725` | `DateTimeFields("开播时间")`、`DateTimeFields("下播时间（可留空=进行中）")`、`DurationFields("时长（时/分/秒，与上两项互为补全）")` |
| `HistoryScreen.kt:789-792` | `DateTimeFields("开播时间")`、`DateTimeFields("下播时间")`、`DurationFields("时长（时/分/秒）")` |
| `HistoryScreen.kt:324-326` | `smallTimeBox("时",…,5)` / `("分",…,2)` / `("秒",…,2)` |
| `StatsScreen.kt:384-389` | `DateFields("开始日期")`、`DateFields("结束日期（含当天）")` |

### 11.3 问题与可疑点

| 位置 | 问题 |
|---|---|
| `TimeInput.kt:32-34` vs `:37-44` | 「完整性」与「合法性」分离：`isComplete()` 只看长度/非空，`toEpoch()` 才做合法性判断并返回 null。**调用方必须同时检查两者**，否则会出现"看似填完但值为 null"。对比 `HistoryScreen.kt:736-743`、`StatsScreen.kt:400-403`、`StatsScreen.kt:642-646` —— **这些调用方都做了双重检查**（`isComplete() && toEpoch() == null` → 报"存在无效日期"），使用正确。 |
| `TimeInput.kt:101` | `smallTimeBox` 只做"仅数字 + 截断长度"，**不做范围上限校验**：`时` 框可输入 `99`（`DateTimeFields` L123 `maxLength = 2`），`分` 可输入 `99`。这些值会在 `toEpoch()` 的 `LocalDateTime.of` 抛异常时被 catch 成 `null`（L42-44），最终由调用方报"存在无效日期（如 2 月 30 日）"。**行为安全，但错误提示措辞对"时=99"这类输入并不贴切**（提示只举了日期例子）。 |
| `TimeInput.kt:67` | `DateParts.isComplete()` 要求 `year.length == 4`，即用户必须补齐 4 位年份；输入 `24` 会被判为不完整，而 `保存/应用` 按钮不会因"不完整"报错（调用方用 `if (x.isComplete()) x.toEpoch() else null` → 静默当作"未填写"）。**不确定**这是否是期望交互（相比 `DateTimeParts` 一致）。 |
| `TimeInput.kt:120` `:140` | 权重 `1.4f` / `1f` 硬编码；窄屏 + 大字体时 5 个 `OutlinedTextField` 并排（每个带浮动 label 与边框）空间可能非常紧张。**不确定**是否触发裁剪。 |
| `TimeInput.kt:54-57` | `setEpoch` 产出 `month/day/hour/minute` 均为 2 位零填充字符串（如 `"05"`），而 `smallTimeBox` 的 `take(2)` 允许 2 位 —— 一致。无问题。 |
| `TimeInput.kt:14` `:16` | 脚本报 `getValue`/`setValue` 未使用是**误报**（`by mutableStateOf` 委托需要它们）。无问题。 |

---

## 12. 主题 · `ui\theme\Theme.kt`（47 行）

### 12.1 内容

- 颜色常量：`BiliPink = Color(0xFFFB7299)`、`BiliPinkDark = Color(0xFFC4667F)`、`LiveGreen = Color(0xFF2E9E5B)`（L10-12）
- `LightColors = lightColorScheme(...)`（L14-21）：`primary = BiliPink`、`onPrimary = White`、`primaryContainer = 0xFFFFE1EA`、`onPrimaryContainer = 0xFF3A0020`、`secondary = 0xFF6EA6FF`、`surface = 0xFFFFFBFF`
- `DarkColors = darkColorScheme(...)`（L23-28）：`primary = BiliPinkDark`、`onPrimary = White`、`primaryContainer = 0xFF5C1132`、`onPrimaryContainer = 0xFFFFD9E4`
- `BiliMonitorTheme(darkTheme = isSystemInDarkTheme(), content)`（L30-39）：`MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content)`
- `object StatusColors`（L41-47）：`Live = LiveGreen`、`Offline = 0xFF9E9E9E`、`Round = 0xFFE8A03C`、`Unknown = 0xFF757575`、`Stale = 0xFFBF7A28`

### 12.2 使用方（全部经 `StatusColors`）

- `HomeScreen.kt:283/307/314/368-371`
- `StreamerDetailScreen.kt:474-477`

### 12.3 问题与可疑点

| 位置 | 问题 |
|---|---|
| `Theme.kt:23-28` | `DarkColors` **未定义 `surface`**，而 `LightColors` 定义了 `surface = Color(0xFFFFFBFF)`（L20）。深色模式下 `surface` 回落到 Material3 默认深色值，浅色模式下是自定义值 —— **两侧不一致**。由于 `HomeScreen.kt:266`、`StreamerDetailScreen`、`OnboardingScreen.kt:164` 都用 `colorScheme.surface` 作卡片底色，深色主题的卡片底色与设计意图（`0xFFFFFBFF` 的对应深色）**不确定**是否相符。 |
| `Theme.kt:10` `:11` | `BiliPink` / `BiliPinkDark` 仅在本文件使用 —— 正常（私有常量）。 |
| `Theme.kt:12` | `LiveGreen` 仅被 `StatusColors.Live` 引用 —— 正常。 |
| `Theme.kt`（全文） | 无动态取色（`dynamicColorScheme`）、无自定义 `Typography`、无 `Shapes` 覆盖。功能范围问题，非缺陷。 |
| `HomeScreen.kt:331` / `StreamerDetailScreen.kt:220` | 收藏星色 `Color(0xFFE8A03C)` 与 `StatusColors.Round`（`Theme.kt:44`）**是同一个色值**，但前者在两处硬编码而非引用 `StatusColors`。配色常量未集中管理（语义上"收藏色"与"轮播色"共用值，**不确定**是否有意）。 |

---

## 13. 跨页面横向问题汇总

### 13.1 消息 / 反馈通道缺失（同一模式重复 3 次）

| 页面 | 症状 |
|---|---|
| `StreamerDetailScreen.kt` | `state.message` 定义（L73）、写入（L138/139/146/154/175/176/118/125）、清空（L184-186）齐备，**但无渲染点** → 全部反馈丢失 |
| `HistoryScreen.kt`（`StreamerHistoryScreen`） | `message` + `clearMessage()`（L241）+ 2500ms 自动清理（L344-349）齐备，**但无渲染点** → 补录/修正结果全部丢失 |
| `SettingsScreen.kt` | `message` 被 collect + 2000ms 清理（L60-65），**但无渲染点** → 全部保存结果（含"设置已被其他修改更新"、"参数非法"）丢失 |
| `HistoryListScreen.kt` | `message` 链路完整（L63/77/104/106/110）但**连写入方都没有**，恒 null |

> 对照：`HomeScreen.kt:124-140`（自绘 Snackbar）、`DataScreen.kt:203-205`（卡片）、`StatsScreen.kt:233-240`（error 文本）、`LoginScreen.kt:248-255`（error/importResult 文本）**有**渲染点。即工程内已有 4 处正确范式，上述 4 处属遗漏。

### 13.2 枚举英文名直接暴露给用户

| 位置 | 暴露内容 |
|---|---|
| `StreamerDetailScreen.kt:452` `:456` | `DataConfidence.name`（`开播:CORRECTED`） |
| `HistoryScreen.kt:477` | `DataConfidence.name` |
| `DataScreen.kt:229` | `RestoreConflictType.name`（`else` 分支） |
| `NotificationHistoryScreen.kt:103` | `NotificationHistoryDeliveryStatus.name`（`CREATED`/`PROCESSING`/`DELIVERY_UNKNOWN` 必然出现） |
| `DiagnosticsScreen.kt:121` | `MonitorHealthStatus.name`（`fromStatus`/`toStatus`） |
| `DiagnosticsScreen.kt:130` | `MonitoringErrorCode.name` |
| `DiagnosticsScreen.kt:112` | `ProblemStateEntity.problemKey`（内部 key） |

### 13.3 空实现的点击回调（外观可点、实际无行为）

- `StreamerDetailScreen.kt:451`、`:455`、`:459` —— `AssistChip(onClick = {}, ...)` × 3
- `HistoryScreen.kt:475` —— `AssistChip(onClick = {}, ...)` × 1
- `HomeScreen.kt:146` + `:242` —— `HealthBanner(onClick = null)`，`clickable` 分支永不生效

### 13.4 定义了但无调用点的函数 / 参数

| 位置 | 对象 |
|---|---|
| `HistoryScreen.kt:702-709` | `ManualDialog.deriveFromStart()` / `deriveFromEnd()` |
| `HistoryScreen.kt:763-770` | `EditSessionDialog.deriveFromStart()` / `deriveFromEnd()` |
| `HistoryScreen.kt:557-561` | `FilterDialog` 的 `bucketValues` 形参（函数体内零引用） |
| `SettingsViewModel.kt:75` | `ConfigUpdateResult.done()` |
| `StreamerDetailScreen.kt:184-186` | `clearMessage()` |
| `HistoryListScreen.kt:110` | `clearMessage()` |
| `HomeScreen.kt:381` | `timeFormatter` |
| `AppNavHost.kt:81-83` + `MainActivity.kt:63-64` | `pendingUrl` / `onUrlConsumed` 整条链路 |
| `MainActivity.kt:105` + `NotificationPoster.kt:71` | `EXTRA_EVENT_KEY`（写入方存在，**无任何读取方**，全工程 grep 确认） |
| `DiagnosticsScreen.kt:51` + `:70` | `DiagnosticsState.appErrors`（字段存在、DAO 存在、从未赋值、从未渲染） |
| `DiagnosticsScreen.kt:47` + `:70` | `DiagnosticsState.openStreamGaps`（流已订阅、恒被覆盖为空列表） |
| `HistoryListScreen.kt:63`/`:77`/`:106` | `HistoryListUiState.message` 全链路（无写入方） |

### 13.5 未使用的 import（重构遗留）

- `HistoryScreen.kt:14` `LazyRow`、`:29` `FilterChip`
- `StatsScreen.kt:17` `Tune`、`:25` `OutlinedTextField`、`:26` `RadioButton`
- `StatsListScreen.kt:42` `StatsSeriesRepository`
- `HistoryListScreen.kt:42` `StreamerEntity`
- `SettingsScreen.kt:24` `Slider`（注释明示"不用滑块"）
- `SettingsViewModel.kt:11` `MonitoringMode`
- `AppNavHost.kt:20` `NavHostController`

### 13.6 数值范围 / 校验口径不一致

| UI 声明 | 后端校验 | 结论 |
|---|---|---|
| `SettingsScreen.kt:186-188` `最大重试次数` 可设 **1–10**（`StepperSetting` L331/L333） | `ConfigRepository.kt:277` `retries < 0 \|\| retries > 5` → Invalid | **确定的范围错配**：可选中 6–10 但永远保存不上 |
| `SettingsScreen.kt:112-117` `检查间隔（秒，60–3600）` | `ConfigRepository.kt:276` `interval < 15` → Invalid | UI 更严，无冲突 |
| `SettingsScreen.kt:173-178` `单批主播数（10–100）` | `ConfigRepository.kt:276` `batch < 1 \|\| batch > 100` | UI 更严，无冲突 |
| `SettingsScreen.kt:179-184` `请求超时（秒，5–60）` | `ConfigRepository.kt:276` `timeout < 5` | UI 有上限 60，后端无上限，无冲突 |
| `SettingsScreen.kt:118-127` 开播/关播确认次数、`:145-149` 合并阈值：**上限 10** | `ConfigRepository.kt:278` 仅要求 `>= 1`，无上限 | UI 人为限制为 10（**不确定**是否有产品依据） |
| `DataScreen.kt:341` 容量档位 `100/500/1000/5000` | `MaintenanceRepository.kt:57` `coerceIn(10, 100000)` | 无冲突 |
| `LoginScreen.kt:123` `coerceIn(1, 500)` | 输入框 `take(4)` 允许 9999 | **显示值与生效值不一致**（§6） |

### 13.7 列表无 key / 风格不一致

- `StatsScreen.kt:311`（`forEach`）、`DataScreen.kt:109/118/127`、`DiagnosticsScreen.kt:109/118/127` 均用 `items(size) { index -> }` 不提供 key。
- 对照：`HomeScreen.kt:206`、`HistoryScreen.kt:428`、`HistoryListScreen.kt:150`、`StatsListScreen.kt:141`、`NotificationHistoryScreen.kt:67`、`StreamerDetailScreen.kt:337` 均正确使用 `key = { ... }`。

### 13.8 时间显示不一致

- 统一使用 `formatTime`（`HomeScreen.kt:395-399`，`yyyy-MM-dd HH:mm`，本地时区）的页面：详情、历史、统计、通知历史、诊断。
- **例外**：`DataScreen.kt:216` 用 `Instant.ofEpochMilli(...)` 默认 `toString()` → ISO-8601 UTC。

### 13.9 硬上限 / 无分页（静默截断）

| 位置 | 上限 | 用户是否可见 |
|---|---|---|
| `HistoryListScreen.kt:81` | `observeRecent(1000)` | 否 |
| `StatsListScreen.kt:70` | `observeRecent(1000)` | 否 |
| `HistoryScreen.kt:131` | `observeByStreamer(id, 600)` | 否 |
| `HistoryScreen.kt:132` + `:146` | `titleDao.observeAll()` **无上限** | — |
| `StreamerDetailScreen.kt:98` | `observeByStreamer(id, 100)` | 否 |
| `StreamerDetailScreen.kt:99` | `observeByStreamer(id, 30)` | 否（标题已写"最近"） |
| `NotificationHistoryScreen.kt:42` | `observeRecent(200)` | 否 |
| `DiagnosticsScreen.kt:63/65/66` | `observeRecent(50)` / `observeHealthEvents(50)` / `observeMonitoringErrors(50)` | 部分（"最近 N"） |

### 13.10 未发现的问题类别（明确说明）

- **`TODO` / `FIXME` / `XXX` / `HACK` 注释：全工程 `app\src\main\java\com\example\bilimonitor` 范围内 grep 结果为空。**
- **写死的假数据 / 硬编码 mock 列表：未发现。** 所有列表数据均来自 Room `Flow` 或 Repository suspend 查询。
- **UI 调用了不存在的方法：未发现。** 报告中列出的每个 ViewModel / Repository 方法均已在源码中逐一确认存在，签名匹配。
- **对已删除 API 的引用：未发现。**（未做 gradle 编译验证，仅做静态交叉引用核对；如需 100% 确认，**建议**在修复前跑一次 `./gradlew :app:assembleDebug`。本次为只读分析，未执行构建。）

---

## 14. 建议修复优先级（按确定性 × 影响面排序）

| 优先级 | 项目 | 位置 |
|---|---|---|
| P0 | `deriveFromStart` / `deriveFromEnd` 未接线 → "填两项补全第三项"完全失效，且文案已承诺 | `HistoryScreen.kt:702/706/763/767` |
| P0 | 4 个页面有 message 状态但无渲染点 → 所有操作反馈静默丢失 | `StreamerDetailScreen.kt`、`HistoryScreen.kt`、`SettingsScreen.kt`、`HistoryListScreen.kt` |
| P0 | `最大重试次数` UI 可设 6–10 而后端拒绝（>5 Invalid） | `SettingsScreen.kt:331/333` vs `ConfigRepository.kt:277` |
| P1 | `开始导入` 预览弹窗时序缺陷 → 失败/超时时弹窗永不出现且无重试入口 | `LoginScreen.kt:239-242`、`:335` |
| P1 | 导入预览空结果误显示为加载中 | `LoginScreen.kt:376-377` |
| P1 | `OnboardingViewModel.complete()` 忽略落库返回值 → 保存失败仍跳首页，可能陷入"重启又要选"循环 | `OnboardingScreen.kt:65-71` |
| P1 | 通知历史状态枚举本地化缺失（`PROCESSING` 等必然出现英文） | `NotificationHistoryScreen.kt:97-104` |
| P1 | 诊断页 `openStreamGaps` 恒为空 + `appErrors` 从未订阅 → 两类诊断数据完全不可见 | `DiagnosticsScreen.kt:63/70`、`:51` |
| P1 | `QrLoginState.Idle` 落入 `else -> {}` → 二维码区空白 | `LoginScreen.kt:329` |
| P2 | 标签筛选后搜索框失效（`observeByTag` 不带 query） | `HomeViewModel.kt:68` |
| P2 | 批量取消收藏无入口（`batchFavorite(true)` 硬编码） | `HomeScreen.kt:100` |
| P2 | `StatsScreen.refresh()` 快照竞态可能覆盖新选择 | `StatsScreen.kt:121-144` |
| P2 | 深链 `pendingUrl` / `EXTRA_EVENT_KEY` 空转 | `AppNavHost.kt:81-83`、`MainActivity.kt:105` |
| P2 | 详情页收藏图标 tint 无条件写死，与首页行为不一致 | `StreamerDetailScreen.kt:220` |
| P3 | 枚举英文名暴露（7 处）、时间格式不一致（DataScreen） | 见 §13.2、§13.8 |
| P3 | 空实现点击回调（4 处 chip + 1 处 banner） | 见 §13.3 |
| P3 | 死代码清理（死导入 8 处、死函数/死参数 8 处） | 见 §13.4、§13.5 |
| P3 | 深色主题未覆盖 `surface` | `Theme.kt:23-28` |
| P3 | 列表缺 key、无分页硬上限 | 见 §13.7、§13.9 |
