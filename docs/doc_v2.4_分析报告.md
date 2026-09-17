# doc_v2.4.md 只读分析报告 — 哔哩哔哩主播监控（com.example.bilimonitor）

> 分析对象：本仓库根目录的 `doc_v2.4.md`（UTF-8，LF，**16882 行 / 341,772 字符**，文档版本 v2.4，编写日期 2026-09-13）。
> 分析方式：全文只读（read 工具分段 + 只读正则检索），**未修改任何文件**；本报告为新建文件。
> 行号均为 `doc_v2.4.md` 实际行号，可直接核对。

## 0. 先决口径（务必先读，否则会读错全文）

**0.1 行数勘误**：任务描述称文档 12843 行，实测 **16882 行**（第 100 章末尾 16882 行）。第 0 章 1–3973、第 01 章 4423–5275、第 02 章 5275+ 的定位与实测一致，可继续沿用。

**0.2 文档只有两个层级，且权威性严格分层**（14–27、3836–3841、16378–16383）：

```text
0.6《最终实现契约：唯一模型、DDL、枚举、状态机与接口》  ← 唯一代码生成契约（1–3973）
   > 规范主体 00–15 主章节
   > 附录 A（16416–16809）
   > §100 v2.3 审查清单（16821–16882，已被 0.6.43/0.6.46 完全取代）
```

- 所有标题带「【历史说明：不可直接实现】」的章节（第 00 章起到第 14 章几乎全部）**不得作为实现依据**，只保留设计演进与交互背景（16、27、3792–3794、16381–16382）。
- 0.6.42.2 明确：唯一权威顺序 `0.6 全部小节 > 规范主体 00–15 > 附录 A > §100`；§15「全局一致性闭环索引」只是自检索引、不是规则来源（16375–16384）。
- 0.6.42.1（3796–3831）给出**废弃别名登记表**（唯一权威），例如 `AggregateStatus→NotificationAggregateStatus`、`MonitorMode→MonitoringMode`、`TimeDataSource`（永久废弃无替代）、`startedAt/endedAt→startTime/endTime`、`MonitoringGap(id,startTime,affectedFrom/To,resolved)→MonitoringGapEntity(gapId,startedAt,…)`、导出「长只读事务方案 A」→方案 B（临时快照表）。
- 阅读顺序陷阱：0.6.30–0.6.32（外围 DTO/DDL）物理位置在 0.6.46 之后，但编号更小、必须在 0.6.46 之前读（3968–3970）；0.6.47 亦插在 0.6.14 与 0.6.15 之间（1456–1591）。

**0.3 平台基线**：目标平台 Android 12（API 31）及以上（9）；`minSdk 31`，compileSdk/targetSdk 用开发时最新稳定版（4053–4055）；应用包名 `com.example.bilimonitor`（4287）。

---

## 1. 产品定位与设计边界

### 1.1 是什么（10、3997–4006、4630–4659）

- **Android 本地优先的 Bilibili 主播直播监控工具**；核心能力固定为：可靠监控、主播管理、开播/关播通知、直播历史、统计、备份恢复、可恢复的后台运行（10）。
- 工程目标四条：可靠性（不误判、不重复通知、异常可恢复、状态可诊断）、可维护性（接口/后台/通知机制可替换）、数据完整性（LiveSession 为历史事实来源、统计可重建、备份可恢复）、可扩展性（外围能力不改核心事实模型）（4001–4006）。
- 产品理念（第一性原则，优先于一切 UI/统计/自动化功能）：**可靠监控 / 可靠通知 / 可靠恢复 / 诚实统计**；「知道就说确定，只能判断就说约，不知道就说不确定」（4632–4659）；另有 §98「宁可告诉用户不知道，不要告诉用户错误答案」（14481）。
- 第一版最值得优先保证的三件事：状态检测准确不误报、通知可靠不重复、后台监控符合 Android 系统限制（4185–4191）。

### 1.2 明确不做什么（4008–4018、2489?、4628、16338、244.8、248.9）

```text
不承诺 Android 能 100% 保证进程永不被杀
不承诺 Bilibili 私有接口永久稳定
不承诺未获官方权限时一定能读取用户关注列表
不承诺 WorkManager 可替代秒级实时监控
不提供实时多人协作、实时多设备同步、账号隔离式监控空间
跨设备仅指用户主动导入/恢复备份文件，无后台实时同步机制
不设计「绕过 Android 限制」的后台技巧（9407）；不使用 while(true)+delay 的常驻线程（9431–9442）
不把 dataSync 当作永久后台轮询方案（111、9385、9500）
不允许以猜数据「修得好看」（7991、12608–12614）
```

### 1.3 账号与登录边界（89–107、4032–4043、6617–6631、248.9）

- 登录**不是核心监控前置条件**；不登录即可手动添加主播并使用全部核心监控能力；导入完成后监控不依赖登录状态（4034、8033–8041）。
- 优先官方开放平台授权/Android SDK 路线，不得为「看起来完整」硬编码未授权私有接口（4036–4043）；必须把「登录成功」与「有权限读取关注列表」作为两个状态（14086–14094）。
- 切换账号按本地 UID 增量导入，不建立多账号监控空间；退出登录只影响凭证与关注导入能力（91、14114–14116、248.9）。
- 关注导入用 `importTaskId + authGeneration` 隔离；退出登录递增 authGeneration，旧任务结果只能写入隔离缓冲（93–105、8121–8125）。

---

## 2. 用户可见功能总表（按模块）

> 标注约定：`[契约]` = 0.6 最终实现契约；`[历史]` = 历史章节（业务规则可参考，模型/字段不得照抄）。

### 2.1 主播监控（4429–4448、4486–4501、2.1、9.x）

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 手动添加/编辑/软删除主播 | 添加主播必须有数据完整性与空值保护；昵称缺失写占位文案 `[待获取]` | 4488–4491、13921+、249.9.1–249.9.1.1（13565–13614） |
| 单主播监控开关 | 每主播独立 `monitoringEnabled`；软删除=从监控列表移除但保留历史 | 4025、6314–6319、2338 |
| 开播/关播检测 | 必须用「状态转换」而非布尔判断；不允许每次轮询都通知 | 7168–7193、3988、15493 |
| 状态确认与误报保护 | 候选态 `SUSPECTED_OFFLINE/SUSPECTED_LIVE` + 确认计数；默认开播 1 次、关播 **2** 次 | 6196–6208、0.6.47.3（1551–1591） |
| 监控模式与可配置检查间隔 | 关闭 / 省电 / 实时；快捷档位 30/60/90/120/300 秒，默认 60；高级自定义安全范围 **15–3600 秒** | 9446–9492、8908–8915 |
| 网络/API 错误隔离 | 错误只进 `ObservationResult`，绝不改写 `confirmedLiveStatus` | 5313、244.1（6841–6855） |
| 批量状态查询 | 批量请求优先，`DEFAULT_BATCH_SIZE=50`（可配置，不得假设 50 永远有效） | 8523–8547 |
| 自动恢复 | RecoveryCheck 优先级高于普通检查；必须两阶段探测 | 7559–7595、7779–7841 |
| 手动刷新 | 首页下拉刷新只触发一次显式检查，不改变自动监控计划 | 4721–4723 |
| 主播卡片 | 头像/昵称/状态/UID/直播标题/分区/封面/标签/分组/⭐重点/监控开关/[进入直播间]；必须展示数据新鲜度（如「状态 37 分钟未更新」） | 4752–4790、4929–4943、7599–7632 |
| 主播详情页 | 头像/昵称/UID、当前状态、直播间信息、标签、分组、监控设置、通知设置、最近直播、直播统计、通知历史；操作=编辑/进入直播间/刷新/开关监控/管理标签/移动分组 | 4791–4818 |
| 进入直播间 | 统一 `RoomNavigator.openRoom(context, roomUrl, preference): NavigationResult`，默认优先 Bilibili App，失败回退浏览器 | 5115–5137、16105–16117、205.4 |
| 主播级监控策略 | `StreamerMonitorPolicyEntity`：enabled、intervalSeconds?（null=跟随全局）、notifyStart/End/Round、start/endConfirmationCount?、aggregationEnabledOverride?、aggregationThresholdOverride?、overrideQuietHours? | 0.6.31（2981–2997）、5141–5175 |
| 重点主播 | `isFavorite`；用于首页置顶、更高刷新优先级、特殊通知声音/震动、突破免打扰 | 5179–5202 |
| 状态颜色语义 | 逻辑状态 `StatusVisual{UNKNOWN,OFFLINE,LIVE,ROUND,ERROR}`，颜色由 UI 层映射 | 4773–4787 |

### 2.2 直播历史（LiveSession）（0.6.3、5712–5941、11640–11960）

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 三项核心时间字段 | 开播时间 `startTime` / 下播时间 `endTime` / 直播时长 `durationSeconds` | 5796–5804、2547 |
| 任意两项自动算第三项 | 三种组合：开播+下播⇒算时长；开播+时长⇒算下播；下播+时长⇒算开播；**禁止三项全空** | 5806–5843 |
| 计算校验 | `endTime >= startTime`、`duration > 0`、时长不超过配置合理上限；三项同时填写且矛盾 ⇒ **拒绝保存**并提示「三个时间存在矛盾。请修改其中一项。」 | 5845–5858、11678–11694 |
| 跨午夜 | 必须按完整日期时间计算，不得只比时分 | 5860–5867 |
| 时间精度 | 界面精度默认**分钟**（`YYYY-MM-DD HH:mm`），内部毫秒时间戳 | 5869–5881 |
| 手工创建记录 | 历史页「+ 添加直播记录」：主播/日期/开播/下播/时长/标题?/分区?/备注?，至少填两项 | 5911–5941 |
| 历史编辑器 | 入口「编辑直播记录」；日期/时间选择器 + 时长输入；展示来源（用户修正）与状态（已确认）；保存前显示最终结果供确认 | 11640–11900 |
| 时长输入格式 | 支持「小时+分钟 / 小时 / 分钟 / HH:mm」，示例 `2小时30分钟 / 2.5小时 / 150分钟 / 02:30`；文本解析 `2:30 / 02:30 / 2小时30分钟 / 150分钟` 统一转秒；禁止用字符串保存时长 | 11904–11936、11869–11900 |
| 日历视图 | 历史→日历；日期标记「● 有直播 / ● 多场直播 / ⚠ 有异常记录」；点日期看当日列表，可与统计联动 | 12089–12116 |
| 时间线视图 | 主播详情页时间线，含开播✅/下播✅/时长/⚠监控中断，用于快速显示数据完整程度 | 12120–12140 |
| 备注 | 用户可写备注（如「B站显示的开播时间有延迟」），**不参与统计计算** | 12144–12160 |
| 合并记录 | 同场被切成两段时可合并；原始两条 Session 不删除并记合并审计 | 11496–11517 |
| 拆分记录 | 一条错误 Session 拆成两条真实 Session | 11450–11492 |
| 重叠冲突 | 同主播重叠 ⇒ 必须产生 `CONFLICT_OVERLAP`，用户可选 编辑/合并/拆分/保留两场/忽略；统计默认不得把重叠时长重复相加 | 7131–7158、11450–11465 |
| 明显异常检测 | 判据：`duration < 0`、`startTime > endTime`、时长 > 24 小时、同主播同刻两个重叠 Session；**只标记「⚠ 疑似异常」，不自动删除** | 11419–11446 |
| 批量修正 | 批量「标记已确认/标记排除/恢复统计/删除人工修正」；时间字段必须逐条确认，不允许无提示批量覆盖 | 11828–11845 |
| 撤销与恢复 | 撤销最近一次修正 / 恢复自动检测值 / 恢复到指定版本；**数据库永远保留审计记录** | 11849–11865、11521–11535 |
| 历史容量 | 按主播条数管理：`UNLIMITED` 或 `MAX_RECORDS=N`；不再使用统一「按天数保留」；95% 预警、90% 低水位 | 13388–13421、13406–13492、9064 |
| 数据来源与锁定 | 每字段 `startSource/endSource/durationSource`（`DataSource`）与 `*Locked`（仅表示「自动刷新不得覆盖」） | 6278–6306、2532–2540 |
| 导入兼容 | 旧数据导入默认 `confidence=UNKNOWN`、`source=IMPORTED`；不得假设 IMPORTED=CONFIRMED | 11940–11959 |

### 2.3 统计与数据质量（4667–4682、11166–12696）

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 统计维度 | 本周/本月/自定义区间、平均直播时长、最近一次开播、次数/总时长/最长/最短/连续天数/按星期/按小时/按分区 | 4671–4680、12052–12085 |
| 可重建原则 | 统计必须可从 LiveSession 重建；`StatisticsCache` 只是缓存不是事实；缓存损坏时删除缓存→重扫 LiveSession→重建 | 4682、11166–11225、12513–12543 |
| 统计口径 | 三档：全部可用数据 / 仅准确确认数据（默认建议）/ 包含部分确认数据 | 12343–12368 |
| 数据可信度 | 五层 `RAW/PROVISIONAL/CONFIRMED/CORRECTED/INVALID`；默认「准确统计」= CONFIRMED + CORRECTED；INVALID 永不计入 | 0.6.2（135–141）、12416–12455 |
| 来源追溯 | 每个统计数字必须可下钻到具体 Session 构成（计入/排除/不完整/被修正） | 11341–11375、11369–11374（`StatisticExplanation`） |
| 完整度展示 | 必须显示数据完整度百分比与「已确认/估算」拆分；不得把估算包装成精确 | 11229–11252 |
| 跨区间场次 | 次数按 `startTime` 归属单一周期；时长按区间切片累计（如 9/30 23:00–10/1 02:00 ⇒ 9月1小时+10月2小时） | 126.1（12023–12050） |
| 时区 | 默认设备时区，但计算必须显式传 IANA timezone；改时区不改历史时间戳、不推进 sourceDataVersion，但必须使相关缓存失效重建 | 12021、12368 |
| 重算触发 | LiveSession 新增/删除排除/改时间/合并/拆分/筛选范围变更/导入/配置恢复/数据库修复 ⇒ 必须自动重算；改头像封面主题声音 ⇒ 不必重算 | 12164–12187 |
| 缓存一致性 | 必须含 `statisticsVersion/sourceDataVersion/calculatedAt`；旧版本结果不得覆盖新版本；`cacheKey=SHA-256(canonicalJson(query…+sourceDataVersion))` | 12191–12225、0.6.17（1931–2030） |
| 数据质量中心 | 新页面展示：完整/部分确认/待修正/冲突/无效 记录数与数据完整度，点击进入问题列表 | 15170–15190、11733–11765 |
| 提醒优先级 | `INFO / WARNING / ACTION_REQUIRED`；示例「⚠ 发现1条直播记录的开播/下播时间互相矛盾。[立即修正]」 | 12261–12285 |
| 防骚扰 | 同一异常不反复通知；通知键 `DATA_{sessionId}_{problemType}_{revision}`；处理后 `resolved=true`；新问题 revision+1 | 12289–12313 |
| 每日数据质量摘要 | 可选每日报告（记录数/完整/待修正/中断次数），**默认关闭**由用户开启 | 12317–12339 |
| 禁止伪造修复 | 可自动修复：缺失缓存、重复缓存、过期 Outbox、孤儿关系；禁止猜测：真实下播时间、用户未提供的开播时间、盲区内的真实时长 | 12597–12622 |
| 用户可见状态标记 | ✅已确认 / 🟡部分确认 / ✏已修正 / ⚠待修正 / ❌已排除 | 12459–12509 |

### 2.4 通知（0.2、0.6.9.1、0.6.10、12.x、233–242）

**通知类别（`NotificationEventType`，0.6.2 第 221–228 行）**：`START_CONFIRMED / LIVE_RECONFIRMED / END_CONFIRMED / BATCH_LIVE / SYSTEM_PROBLEM / SYSTEM_RECOVERED`。

**通知渠道（至少 3 个）**：`live`（直播状态：正在直播与关播）、`monitor_service`（后台监控状态）、`system`（系统错误/登录失效/配置错误）（9711–9727）。

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 「正在直播」通知 | 普通开播确认与异常恢复后重新确认**统一文案**；标题 `{主播名} 正在直播`，内容 `{主播名} 正在直播{：直播标题}`；**禁止**「刚刚开播/刚才开播」 | 50、10929–10952 |
| 关播通知 | 标题 `{主播名} 已下播`，内容「本次直播结束」；仅在**当前连续可靠监控区间**具备资格时发送 | 9755–9793、10954–11030 |
| 通知去重 | 稳定 `eventKey` 与业务事件一一对应；`eventKey = evt:{eventId} / agg:{aggregateId} / problem:{problemKey}`；同一 eventKey 最多一条 Outbox、最多一个 notificationId | 9795–9807、1277–1280、1167–1172 |
| Outbox 可靠投递 | 任何开播/下播/批量/系统通知必须先落 `notification_outbox` 再异步 Dispatcher 发送；状态机 8 态 | 9826–9844、248.16（6754–6790） |
| 稳定 notificationId | 只能来自 `notification_id_registry`（不得 hashCode/时间戳派生）；范围 `[1, 2_000_000]`，含分配/回收/回绕算法 | 1322–1344、3946 |
| 批量开播聚合 | 阈值默认 **4 位**、窗口默认 **5 秒**；两者同时满足才聚合；以 `eventConfirmedAt` 判断「同时」；UI 可选 从不合并/2/4/5/10/自定义（min 1、max 1000） | 10069–10122、244.4（6872–6881） |
| 批量通知内容 | 标题「发现多位主播正在直播」/内容「共有 N 位主播正在直播」+ 操作 [查看直播中的主播]（跳 LIVE 列表）；数据库仍保存 N 个独立事件 | 10124–10176 |
| 通知过期 | 超过 `expiresAt` → `EXPIRED`，永不再进入发送流程 | 10583–10613、6773–6788 |
| 通知冷却 | 同类问题通知有冷却期，避免轰炸；`notification_cooldown(problemKey, lastNotifiedAt, cooldownUntil, recoveredNotified)` | 10548–10582、10614–10638、3067–3074 |
| 系统问题通知 | 三种互斥 Outbox 形态（单事件 `sourceEventId` / 批量 `aggregateId` / 系统问题 `problemKey`）；`problemKey="{component}:{problemType}:{scopeKey}"`，每次激活追加 `:{episodeId}` | 1282–1291、0.6.9.1 |
| 异常等级 | `INFO/WARNING/ERROR/CRITICAL`；单次网络错误不提醒、持续异常→WARNING、监控停止→ERROR、数据库/通知系统不可用→CRITICAL | 10492–10547、15108–15135 |
| 通知点击跳转 | 统一 payload DTO（eventKey/destination/streamerStableId/sessionStableId/url）；URL 必须过 UrlPolicy 校验协议与 host；默认优先 Bilibili App，可设偏好，失败写日志且不崩溃 | 0.6.41（3774–3786）、10856–10926、236.x |
| 通知历史中心 | 记录通知业务事件审计结果，与 Outbox 生命周期分离；`historyId="nh:{eventKey}"` 每个 eventKey 一行；保留 `max(90天, 最近5000条)` | 10639–10678、13710–13759、1307 |
| 权限 | Android 13+ `POST_NOTIFICATIONS` 运行时权限；拒绝时明确提示「后台仍可监控，但无法正常发出通知」 | 9809–9822 |
| 静默/免打扰时段 | 「免打扰时段」内不显示普通开播/关播通知，监控仍继续（示例 23:00–07:00）；`quietHoursEnabled=false`、`quietStart=23:00`、`quietEnd=07:00`；主播级 `overrideQuietHours`；重点主播可突破 | 4697–4705、14130–14132、5162、5201 |
| 错误恢复状态 | 恢复成功可生成恢复报告：本次中断时长/受影响主播/成功/仍异常/通知事件恢复数，「查看详情」进诊断页 | 10708–10741、15090–15104 |
| 错误聚合 | 监控错误自动聚合、软件错误自动聚合；日志关联 ID（correlationId） | 10742–10821、224 |

### 2.5 搜索 / 筛选 / 排序 / 标签 / 分组（14–17、52）

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 模糊搜索 | 转小写、去空格与常见符号、contains 匹配 + 评分（完全相同 100 / 前缀 80 / 连续子串 60 / 分词 40 / 其他 0） | 4947–4985 |
| 拼音搜索 | 「doudou→豆豆」属 P2/后续，不作为 MVP 必须功能 | 4987–4995、4471 |
| 标签 | 新建/修改/删除/改颜色/给主播增删/按标签筛选；筛选默认「任意匹配 OR」，高级支持「全部匹配 AND」 | 4999–5033 |
| 分组 | 名称/排序/折叠/主播数量；首页支持按分组显示或全部平铺 | 5037–5066 |
| 筛选 | 状态（全部/直播中/未开播/轮播/异常）+ 标签 + 分组可组合；`StreamerFilter{keyword,statuses,tagIds,groupIds}` | 5070–5111、5105–5110 |
| 统一查询模型 | `StreamerQuery{keyword,statuses,tagIds,groupIds,favoriteOnly,monitoringOnly,sort}` | 5206–5219 |
| 排序 | `StreamerSort{DEFAULT,LIVE_FIRST,FAVORITE_FIRST,NAME_ASC,NAME_DESC,RECENTLY_STARTED,RECENTLY_ENDED,RECENTLY_ADDED,RECENTLY_UPDATED,CUSTOM}`；默认排序 重点→直播中→最近更新 | 5222–5247 |
| 最终契约约束 | 搜索/标签/分组/重点主播/主播策略全部引用同一 `StreamerEntity`；页面只能用 `stableId` 导航；标签/分组身份用 `stableId`，不得用名称做跨设备身份 | 3770–3772、5469–5480 |

### 2.6 批量操作（53、186）

- 多选模式下：批量开启监控 / 批量关闭监控 / 批量添加标签 / 批量移除标签 / 批量移动分组 / 批量设为重点 / 批量取消重点 / 批量删除（5251–5264）。
- 删除必须二次确认并显示数量：「确认删除 27 名主播？此操作不会删除你的 Bilibili 关注关系。」（5266–5271）。
- 批量操作必须**幂等**（P1-7，15065–15089；197-17 于 16057）。

### 2.7 数据管理：导出 / 备份 / 恢复 / 容量（08 章、0.6.18–0.6.20、0.6.38–0.6.40）

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 两类文件严格区分 | ①普通应用配置导出（只含设置与主播管理配置，`schemaVersion`）②直播历史备份配置文件（业务数据全量/范围备份） | 12764–12842、13204–13221 |
| 配置导入导出 | JSON + `schemaVersion`，由 `ConfigMigrator` 升级；只更新配置域，不触发 LiveSession/Statistics/容量清理；失败可回滚 | 12702–12763、14140–14163、2395 |
| 导出类型 | `ExportType{HTML, JSON, BACKUP, DIAGNOSTIC}` | 305 |
| 导出快照一致性 | **唯一方案 B：临时快照表**；在一个写事务内把范围内实体复制到 `export_snapshot_live_session` 等临时表并置 READY，之后只读临时表分页流式写文件；方案 A（长只读事务）**不得实现** | 2032–2067、3718–3747 |
| 磁盘防护 | 复制前评估空间（预计=记录字节×1.3），不足则取消并提示「存储空间不足，导出已取消」；分页阶段超阈值/取消必须删临时文件，不得返回部分成功 | 2054–2067、6728–6739 |
| HTML 报告 | 结构与图表、可信度展示、数据范围选择、导出级别、附加数据；离线可打开；不得含任何账号凭证/Cookie | 12968–13176、15859–15861 |
| HTML 安全 | 远程文本与用户文本一律 Escape；内嵌 JSON 由序列化器生成；URL 过协议校验 | 248.15（6741–6752） |
| 导出文件名/日期 | 使用实际最早/最晚直播日期；无记录时不得伪造日期或生成成功文件 | 13496–13523、16182+ |
| 备份 Manifest | 必须声明恢复范围；三个版本号分离：`formatVersion`（容器）/`backupSchemaVersion`（业务字段）/`roomSchemaVersion`（Room 结构）；`recordCounts` 必须逐项校验 | 0.6.19（2069–2101）、249.3（13452–13475） |
| 备份范围 | `BackupScopeType{ALL_DATA, SELECTED_STREAMERS, SINGLE_STREAMER, DATE_RANGE}`；必须形成**业务闭包**（Session/Event/Correction/Interval/GapStreamer/策略/必要标签分组关系）；Runtime Lease、fencingToken、本机 notificationId、access token、UI 偏好**不入备份** | 258–263、3749–3762、12581–12595 |
| 恢复模式 | 用户必须显式选择「增量恢复 / 覆盖恢复」；增量=范围外完全不动、范围内以备份为准；覆盖=范围内最终状态与备份一致、范围外不变；覆盖属高风险须二次确认 | 12843–12880、12947–12964、248.13（6698–6712） |
| 恢复完整流程 | 格式校验→版本兼容→完整性校验→范围预览→模式选择→用户确认→安全点→导入→导入后一致性检查→统计重算/缓存失效 | 12852–12878 |
| 版本兼容矩阵 | 高版本备份→恢复可识别字段+忽略未知字段+摘要明确提示「文件没有完整恢复」；低版本备份→已知字段恢复、新增字段用安全默认或「无历史值」；缺不可推导必需字段→拒绝该部分恢复 | 12882–12915 |
| 冲突处理 | `RestoreConflictType{STABLE_ID_MATCH, STABLE_ID_DIFFERENT_UID_SAME, STABLE_ID_SAME_UID_DIFFERENT, STABLE_ID_AND_UID_DIFFERENT, UID_COLLISION}`；决策 `USE_BACKUP/KEEP_LOCAL/MERGE/SKIP`；必须先预检、再决策、再单事务执行 | 292–298、2134–2143、0.6.40（3764–3768） |
| 恢复状态机 | `RestoreRunStatus{PRECHECK,WAITING_DECISIONS,APPLYING,COMPLETED,FAILED,CANCELLED}`；APPLYING 崩溃视为不完整恢复→置 FAILED + AuditLog，不得自动重跑 | 0.6.33.1（3486–3518） |
| 幂等与标识 | 每次恢复有 `restoreRunId`；同 backupId+文件 checksum 重复执行必须可识别，不产生第二套等价事实 | 3516–3517、12941–12943 |
| 校验和 / Migration 验证 / 导入隔离 | P1：导入文件校验和（13266–13303）、MigrationVerifier（13304–13328）、导入历史数据预检与隔离（13329–13357） | 同左 |
| 互斥 | 导出/清理/恢复/迁移必须互斥（共用一个维护事务）；存在活动 export_snapshot 时不得删除其范围内数据 | 249.7（13528–13541）、1316–1317 |

### 2.8 账号登录与关注导入（18、59、0.4、248.12）

- 流程：点击「导入我的关注」→检查登录→（未登录）登录→获得授权身份→请求关注列表→筛选存在直播间的用户→批量读取直播间资料→**预览**→用户确认→写入 Room（14015–14039）。
- 必须支持「仅新增」与「新增+更新已有信息」（默认后者）；更新**仅作用于 Bilibili-owned 字段**（昵称/头像/直播间资料/分区/封面/直播链接），不得覆盖用户标签、分组、排序、备注、监控/通知策略（14041–14066、14048、248.12）。
- 不得删除未出现在本次关注列表中的本地主播（14048）；命中已软删除 UID 时不得自动恢复，预览标记「已删除主播」并由用户选「恢复原记录（清 deletedAt，保留 stableId/历史/用户配置）」或「跳过」（14050–14058）。
- 凭证：Android Keystore + 加密存储；数据库只保存登录 UID/登录状态/最后刷新时间；敏感凭证独立保存；禁止明文密码/Cookie、禁止打印 token（13963–14013、3985、15495–15496）。
- 接口：`AuthProvider{login,logout,getCurrentUser,refreshTokenIfNeeded}` + `FollowingDataSource{getFollowings}`；`AuthSessionEntity{authSessionId,authGeneration,accountHint,createdAt,revokedAt}`（13982–13989、14080–14084、3135–3142）。
- 任务模型：`FollowImportTaskEntity(importTaskId, authGeneration, status, startedAt, finishedAt, stagingCount, error)`、`FollowImportStagingEntity(importTaskId, uid, authGeneration, payloadJson, createdAt)`（0.6.31，2999–3023）。

### 2.9 诊断与健康检查（11 章、57/60/64/91/94/98/100/206–219）

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 健康检查页面 | 至少区分：监控服务 / 通知权限 / 后台权限 / 网络 / 关注导入登录状态；登录状态不属于核心健康 | 4725–4737 |
| OverallHealth 决策矩阵 | 各子系统先独立计算，再由统一 `OverallHealthEvaluator` 汇总；页面不得自行拼接结论 | 15147–15168、244.17（7003–7014） |
| 运行三态 | 必须区分 `desiredState / actualState / healthState`，UI 允许并应当显示「监控意图：开启 / 监控服务：已停止 / 状态：异常」 | 7707–7775、9247–9293 |
| 心跳 | `MonitoringHeartbeat{lastAttemptAt,lastSuccessfulCheckAt,lastHeartbeatAt,lastStateChangeAt,consecutiveFailures}` | 7725–7736 |
| 数据新鲜度 | `DataFreshness{FRESH,STALE,UNKNOWN}`；判定以 `now-lastConfirmedAt` 时间年龄为主；阈值唯一来源 `freshnessStaleSeconds` | 7599–7666、0.6.16.1 |
| 启动恢复 | 所有入口统一走 `StartupRecoveryCoordinator`；最多一个 ACTIVE；读 ApplicationExitInfo 判定崩溃/ANR/低内存/系统杀/用户停止/UNKNOWN | 7670–7703、7900–7963 |
| 一致性检查 | 启动/异常恢复后/DB 升级后执行 ConsistencyChecker；必查 10 项 + 0.6.22.2 的双列身份一致性 SQL | 7967–7992、2246–2272 |
| 「昨天发生了什么」 | 专门诊断页面回答「这段时间发生了什么」 | 14520–14553 |
| 监控恢复提醒 | 恢复后向用户提示，并给恢复报告入口 | 15002–15026、15090–15104 |
| 两类日志严格分离 | 监控错误日志（`monitoring_error_log`）与软件运行错误日志（`application_error_log`）必须有独立 Entity/DAO/Repository | 14554–14711、16105–16123 |
| 日志级别与结构化 | 必须有 severity（INFO/WARNING/ERROR/CRITICAL）、timestamp、correlationId；必须结构化 | 14712–14795、224 |
| 日志脱敏与保留 | 一律脱敏；禁止记录 Token/Cookie/Authorization Header；有保留策略与数量上限；清理可追踪 | 14772–14847、221–223 |
| 用户侧日志中心 | 日志中心页、监控错误日志页、软件运行错误日志页；日志筛选/聚合；生命周期管理 | 14848–14938、15027–15064 |
| 开发者诊断模式 | 开发者诊断模式开关 | 14939–14966、231 |
| 一键导出诊断包 | 导出诊断包，不得包含认证凭证 | 14967–15001、16097 |
| 崩溃记录 | `CrashRecord{crashedId,bootId,occurredAt,processInstanceId,exitReason,recoveredAt}` | 3182–3190 |
| 故障审计日志 | `audit_log(auditId,operationId,actor,action,targetType,targetStableId,occurredAt,detailJson)`；`(operationId,action)` 唯一 | 3144–3159、14443–14480 |
| 可靠度评分（P1-2） | 组成：API 成功率/后台运行率/有效检查率/通知成功率/数据完整度；示例「监控可靠度 98.7%」，且**不得伪装成科学精确值**，须同时给出组成项与统计周期 | 7846–7870 |
| 数据问题与系统问题分开 | 系统异常通知与数据质量提醒不得使用同一个「错误」概念 | 12656–12678 |

### 2.10 后台保活与运行模式（05 章、0.5、0.6.27、0.6.47）

| 功能点 | 规格要点 | 出处 |
|---|---|---|
| 首次启动三选一引导 | **不可跳过**的选择页，未选择前 `backgroundKeepAliveChoice=UNSET` 且不得以实时模式启动 | 0.6.47.1（1460–1523） |
| 三个选项与后果文案 | ①前台常驻通知（Foreground Service，需通知权限，通知栏常驻，Android 15+ 有累计时长限制）②无障碍服务（实验性，需手动授权，承诺不读取无关屏幕内容/输入/个人数据）③不启用保活（必须用户确认「不保证后台一直存活、可能漏通知」） | 1473–1497 |
| 落库与审计 | 选择写入 `monitoring_config.backgroundKeepAliveChoice` 并同事务写 `AuditLog(action='SET_KEEPALIVE_CHOICE')`；NO_GUARANTEE 必须置 `noGuaranteeAcknowledged=1` + 时间；改选必须走同一路径，禁止直接 UPDATE 绕过审计 | 1499–1523 |
| 运行态映射（不得伪装正常） | 用户选择 ≠ 实际能力；8 行映射表（如通知权限被拒→需要通知权限；后台启动 FGS 被拒/达上限→系统已限制后台运行）；任何降级必须写 HealthEvent 并更新首页态 | 0.6.47.2（1525–1549） |
| 三种监控模式 | 模式 A 关闭监控；模式 B 省电监控（WorkManager，官方周期下限 15 分钟）；模式 C 实时监控（用户主动开启 FGS + 固定前台通知 + 可配置间隔批量轮询） | 9446–9492、9464 |
| 检查间隔 | 统一可选 `30/60/90/120/300` 秒，默认 `60` 秒；高级自定义安全范围 15–3600 秒；禁止 0/1/5 秒；不要默认 5 秒 | 9482–9492、8908–8931 |
| FGS 类型与权限 | Android 14+ 必须声明合法 `foregroundServiceType` 与权限；`specialUse` 须在 Manifest 说明用途；**不得把 dataSync 当永久后台轮询**（Android 15+ 24 小时窗口累计 6 小时限制） | 9494–9500、9383–9387、111 |
| 电池优化引导 | 设置页「后台运行保护 [已开启/未开启]」+「去系统设置」；只能引导，不得声称 100% 杀不死 | 9502–9514 |
| 重启/升级恢复 | 监听 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`，读取库中监控设置；不得在所有版本上从 Receiver 无条件启动 FGS | 9516–9527 |
| 前台常驻通知内容 | 「监控中：N 位主播 / 最后检查：HH:mm:ss / 状态：运行正常」+ 操作 [暂停监控] [打开应用]；通知更新必须节流 | 9577–9595 |
| 无障碍服务边界 | 默认关闭、明确用途、用户主动授权、独立模块、最小权限、可随时关闭、状态可观测；不得读取/上传/记录与监控无关的屏幕内容与输入事件；不得成为 MonitoringEngine 唯一运行基础 | 9599–9637、244.9（6920–6934）、0.6.41（3788） |
| 单实例租约 | 数据库租约（`system_runtime_lock` 单行表）+ 定期续租 + `runtimeInstanceId`/`monitorGeneration`/`fencingToken`；禁止只用内存 Mutex/Boolean | 9295–9369、0.6.35（3622–3672） |
| RuntimeState | `MonitoringRuntimeState{desiredRunning,actualRunning,mode,monitoredCount,lastKnownLiveCount,lastAttemptAt,lastSuccessfulCheckAt,lastRemoteSuccessAt,consecutiveFailures,currentBackoffSeconds,runtimeInstanceId,monitorGeneration,health,lastError}` | 9247–9279 |

### 2.11 可配置项汇总（最终契约默认值，0.6.4.5 seeds，813–838）

| 配置 | 默认值 | 约束/范围 |
|---|---|---|
| `monitoringEnabled` | 1（开） | 但 `mode=POWER_SAVING`，引导完成前不得进入 REALTIME（844–846） |
| `mode` | `POWER_SAVING` | `REALTIME/POWER_SAVING/MANUAL` |
| `intervalSeconds` | 300 | 快捷档 30/60/90/120/300（默认 60）；高级 15–3600 |
| `batchSize` | 50 | 由接口实际限制决定且可配置 |
| `maxConcurrency` | 4 | — |
| `timeoutSeconds` | 15 | 网络层建议 connect 10s / read 15s / write 15s（8558–8560） |
| `maxRetries` | 2 | 只对网络失败/超时/5xx 重试；不得无限重试 |
| `retryBaseSeconds` | 5 | 退避基础等待建议 1 秒 + 指数因子 2 + Full Jitter（8575–8586） |
| `maxRetryDelaySeconds` | 60 | 最高等待上限 |
| `circuitBreakerThreshold` | 3 | 按 API 类别隔离熔断 |
| `circuitBreakerRecoverySeconds` | 60 | HALF_OPEN 只允许单 Probe |
| `aggregationEnabled` | 1 | `false` 才是「从不合并」的唯一正确表达（1188） |
| `aggregationThreshold` | 4 | `>=1`；UI 可选 从不合并/2/4/5/10/自定义（min1,max1000） |
| `aggregationWindowSeconds` | 5 | 固定锚点窗口 `[T,T+5s]` |
| `batchCooldownSeconds` | 30 | 批次冷却 |
| `freshnessStaleSeconds` | 300 | `FRESH→STALE` 唯一阈值；变更须在维护事务内重算全部 freshness |
| `backgroundKeepAliveChoice` | `UNSET` | `FOREGROUND_NOTIFICATION/ACCESSIBILITY/NO_GUARANTEE/UNSET` |
| `noGuaranteeAcknowledged(At)` | 0 / NULL | 仅 NO_GUARANTEE 时为 1 |
| `startConfirmationCount` | 1 | `>=1`；主播级优先 |
| `endConfirmationCount` | 2 | `>=1`；只有 OFFLINE 方向默认 2 |
| `placeholderText` | `[待获取]` | 昵称等远程资料缺失时的唯一占位来源 |

其他界面侧默认值（历史章节）：通知开关 开播 ON / 关播 ON / 首次同步 OFF / 声音 ON / 震动 ON（4844–4852）；免打扰默认关闭、23:00–07:00（14130–14132）；`autoStartMonitoring=false`；`openRoomWithBilibiliAppFirst=true`（14133–14134）。

---

## 3. 关键业务规则

### 3.1 状态确认状态机（0.6.1/0.6.2/0.6.7/0.6.15/0.6.47.3；166、247.3）

```text
UNKNOWN ──首次成功观察──► OFFLINE / LIVE（建基线，不产生普通 START 通知）
OFFLINE ──连续 N_live 次可靠 LIVE──► LIVE（START 事件 + “正在直播”）
LIVE ──出现 OFFLINE 候选──► SUSPECTED_OFFLINE（计数）──连续 N_end 次──► OFFLINE（END）
LIVE ──观察为 ROUND──► ROUND（不关场次、不发关播、区间继续有效）
任意确认状态 + TIMEOUT/NETWORK_ERROR/API_ERROR/PARTIAL/INVALID
        ──► confirmedLiveStatus 保持不变，只更新 lastObservationResult / 健康 / 区间资格
```

- 候选态枚举只有 `PendingTransition{NONE, SUSPECTED_OFFLINE, SUSPECTED_LIVE}`；**禁止** `confirmedLiveStatus=ERROR`（128、5313、6852）。
- 确认计数必须持久化在 `streamer_pending_transition`（含 `monitorGeneration`/`streamerMonitorGeneration`/`lastObservationSequence`），递增必须 CAS；影响行数 0 ⇒ 重读重算，不得强行覆盖（904–921、8296–8310）。
- **阈值单一来源（0.6.47.3，1551–1591）**：一个方向只有一个阈值 N，同一事务内一次性完成「提交状态 + 写 LiveEvent + 写 StatusHistory/复位 PendingTransition + 创建 Outbox」。禁止两段式（先发通知后改状态）、禁止第二个通知阈值列、禁止 `confirmationCount < N` 时预建事件或占位 Outbox。
  - LIVE 方向默认 `1`；OFFLINE 方向默认 `2`；主播级 `streamer_monitor_policy.*ConfirmationCount` 非空即完全覆盖全局，不做插值；两列均 `>=1`（DB CHECK + UseCase 双校验）。
  - 通知延迟上界 ≈ `(N-1) × 实际监控间隔`；Tick 期间**不得重读**阈值（快照固定，见 0.6.16）。
- 状态抖动保护（P0-1，7322–7365）：单次 ERROR 不得产生下播；单次 OFFLINE 只进入「疑似下播」；连续确认后才生成 Ended；`LIVE→OFFLINE→LIVE` 短抖动不得重复生成无效 Session。
- 状态转换必须**原子 CAS**（248.1、0.6.35，3597–3620）：WHERE 同时校验 `observationSequence < :new`（严格递增）、`streamerMonitorGeneration`、以及 `EXISTS(system_runtime_lock WHERE monitorGeneration AND fencingToken AND maintenanceMode='OFF')`；影响行数 0 ⇒ 返回 `StaleOrFenced / StaleSequence`，**禁止放宽条件重试**，也不得视为数据库异常（794、3620）。
- 统一返回类型 `ApplyObservationResult{Applied, StaleSequence, StaleOrFenced, ConfigStale, DuplicateOpenSession, Invalid}` + `CasWriteResult{APPLIED, STALE_SEQUENCE, STALE_OR_FENCED, CONFIG_STALE, DUPLICATE, INVALID}`（1640–1680）。
- `transitionId = "{streamerStableId}:{eventSequence}:{from}->{to}"`，同一事务内生成一次，与 `eventConfirmedAt` 一一对应；禁止用随机数/时间戳单独生成（1877–1881）。
- 首次 UNKNOWN→LIVE 及异常恢复→LIVE 的完整规则见 0.6.5（853–866）、44.1（6212–6236）、244.11（6952–6958）。
- 转换原因枚举 `TransitionReason{FIRST_OBSERVATION, CONFIRMATION_COUNT_REACHED, RECOVERY_RECONFIRM, MANUAL_STOP, STREAMER_DELETED}` 必须逐一映射到状态机动作，不得只当日志标签（336–347）。

### 3.2 LIVE / OFFLINE / ROUND / UNKNOWN 语义

| 状态 | 语义与后果 | 出处 |
|---|---|---|
| `UNKNOWN` | 从未确认或无法判断；**不等于 OFFLINE**；UI 必须能区分「未开播」与「暂时无法确认」 | 5313、12695、15758 |
| `OFFLINE` | 可靠确认未开播；是唯一允许关闭 Session 的方向（需满足区间资格） | 0.6.6、240.1 |
| `LIVE` | 可靠确认正在直播；建立/复用 ACTIVE OPEN Session 与 ReliableMonitorInterval | 0.6.5、0.6.6 |
| `ROUND` | **轮播是成功观察到的另一种业务状态，不属于异常**：`LIVE→ROUND` 不关闭 LiveSession、不产生 END、不发关播通知，当前区间继续有效；统计默认把 ROUND 时间计入当前场次监控时长，另可派生 `roundSeconds`，但不得改变 Session OPEN/CLOSED 语义 | 0.6.23（2274–2276）、7194–7242 |
| `ROUND→LIVE` | 仍属同一直播连续场次则恢复 LIVE、不新建 Session；只有产品显式配置「ROUND 视为场次结束」才允许关闭旧 Session，且该配置必须显式记录并进入审计 | 7206–7208 |

- `endTime == null` 是 OPEN 的**唯一**事实来源；`lifecycleState` 只区分 ACTIVE/ABANDONED，**不得替代 endTime 判断**；也不得再引入 `isCompleted` 第二套完成状态（420、6082、248.8）。
- 首次确认 LIVE 的 `startTime` 只表示「软件确认进入本场次时刻」，`startSource=MONITOR_OBSERVED`、`startConfidence=PROVISIONAL`，**不得伪造真实开播时间**（856–866、6232、248.10）。

### 3.3 连续可靠监控区间（ReliableMonitorInterval）与关播资格

这是本文档最核心、也最容易实现错的产品规则（0.2、0.6.6、240.1、249.10.1）：

```text
确认 LIVE / 异常恢复后重新确认 LIVE
        ↓ 建立当前连续可靠监控区间（ReliableMonitorInterval，status=ACTIVE）
区间内出现 TIMEOUT/NETWORK_ERROR/API_ERROR/PARTIAL/INVALID 或有效 MonitoringGap？
   是 → 同一业务事务内把当前区间置 INVALIDATED（立即失去关播资格）
   否 → 持续 SUCCESS 续期（更新 lastReliableObservationSequence）
        ↓
   可靠确认 OFFLINE 且当前区间仍 ACTIVE、代际完全匹配
        ↓
   发送关播通知（END_CONFIRMED）
```

- 区间是**关播通知资格的持久化事实**，必须在库内、必须由 `EndNotificationEligibilityEvaluator` 判定，不能由普通 `LIVE→OFFLINE` 检测直接发送，也不能只依据内存状态（7225、10956）。
- 资格 6 条件（7231–7238）：存在有效 LiveSession + 已建立连续监控区间 + 区间内无异常 + 区间内无 MonitoringGap + 最终 OFFLINE 可靠确认 + 当前主播关播通知策略开启。
- 四个判定示例（10988–11028）：断网期间已下播⇒不发；全程正常后确认 OFFLINE⇒发；断网恢复仍直播后再正常至 OFFLINE⇒发；多次异常后以最后一段新区间为准⇒发。
- 恢复成功不是「恢复整场历史资格」，而是从恢复并重新确认 LIVE 的时刻**建立新区间**（11030、13706）。
- 主动取消不算网络错误，但若因此产生无法覆盖的监控空窗，则该区间不能作为关播资格区间（10986、244.18）。
- 软删除/停止监控/退出应用**都不是下播事实**：当前区间失效、Session 转 ABANDONED、`endTime` 保持 NULL、不发关播通知（0.6.22，2162–2185；176.1，6332–6370）。

### 3.4 异常恢复（RecoveryCheck / RecoverySession / StartupRecovery）

- 存在盲区后恢复不能只做普通轮询：`RecoveryCheck` 优先级高于普通检查，必须与普通 MonitoringTick 互斥（7559–7595、7801、P0-12）。
- 网络恢复事件不得直接视为 B 站服务已恢复：必须两阶段探测 `Network Transport Recovered → Connectivity Probe → Bilibili API Probe → RecoveryCheck`；探测失败继续退避，不得立即对全部主播发起恢复请求（7781–7793、8170）。
- 每次恢复记录 `startTime/finishedAt/affectedCount/successCount/failureCount` + `finishReason{HEALTHY,TIMEOUT,FAILED,CANCELLED,SUPERSEDED}`；达到最大恢复时长必须结束并保留未完成对象，**不得假装全部恢复成功**（7822–7839、244.16）。
- `RecoverySession` 状态机 `REQUESTED→RUNNING→COMPLETED | FAILED | TIMEOUT | CANCELLED`，全局最多一个 RUNNING，重复触发合并；崩溃遗留的 RUNNING 行按「租约过期」结算为 `TIMEOUT + finishReason='TIMEOUT'`，**不得伪造 HEALTHY**；终态不可逆（0.6.33，3443–3484）。
- 恢复时**不恢复**旧设备的 `MonitoringLease / runtimeInstanceId / fencingToken`；恢复库中的 `monitoringEnabled` 只是用户意图，实际运行必须由当前设备重新建立运行时（7698、248.20）。
- `StartupRecovery` 全局单实例：所有入口（App Start、BOOT_COMPLETED、MY_PACKAGE_REPLACED、Service 重建、WorkManager）必须经统一协调器，重复只置 `recoveryRequested`，不得并发跑多份一致性检查（248.2、7672）。
- 崩溃区分：至少 `NORMAL_EXIT / CRASH / ANR / LOW_MEMORY / USER_STOPPED / SYSTEM_KILLED / UNKNOWN`，无法确认用 UNKNOWN；不得把任何退出都标记成崩溃（7949–7963）。

### 3.5 监控盲区（MonitoringGap / MonitoringGapStreamer）

- 分层：系统级原因创建 Global Gap；部分主播/单 UID 缺失至少写 `MonitoringGapStreamer`，**不得因为一个主播失败就把其他正常主播一并标记受影响**（7424–7435）。
- `endTime` 是 Gap 关闭的**唯一**事实来源（NULL=OPEN）；历史字段 `resolved` 降级为派生，禁止形成第二套可独立变化的完成状态（7371、7453）。
- 同一连续不可监控区间只允许一个 OPEN Gap；同一主播的 GapStreamer 区间不得重叠（248.3、7440–7442、0.6.9）。
- RecoveryCheck 若 TIMEOUT、再次失败或只获得 PARTIAL/UNKNOWN，**都不得关闭 Gap**（7422）。
- `affectedEndAt`/`affectedStartAt` 是主播级时间事实，不能用全局 Gap 时间代替；`affectedStreamerCount` 只是缓存派生字段（993）。

### 3.6 通知规则（类型 / 文案 / 聚合 / 冷却 / 过期 / 静默时段）

1. **事件类型**：`START_CONFIRMED / LIVE_RECONFIRMED / END_CONFIRMED / BATCH_LIVE / SYSTEM_PROBLEM / SYSTEM_RECOVERED`（221–228）。
2. **文案**：直播中统一「{主播名} 正在直播」/「{主播名} 正在直播{：直播标题}」；禁止「刚刚开播/刚才开播/刚刚开始直播」；关播「{主播名} 已下播」/「本次直播结束」；批量「发现多位主播正在直播」/「共有 N 位主播正在直播」（10940–10952、9762–9768、10131–10132）。
3. **身份与去重**：`eventKey = evt:{eventId} | agg:{aggregateId} | problem:{problemKey}`；同一 eventKey 最多一条 Outbox、最多一个 notificationId；单事件路径必须写 `sourceEventId` 并由部分唯一索引保证幂等，**禁止用 `LIKE 'evt:%'` 之类字符串解析判断**（1277–1280、1295、1172）。
4. **聚合状态机**：`COLLECTING → (达阈值) READY → (windowEnd) FROZEN → (发送成功) DISPATCHED`，另有 `CANCELLED / EXPIRED`；`threshold=1` 等价关闭聚合，「从不合并」只能用 `aggregationEnabled=false` 表达（0.6.10，1175–1226）。
   - 未达阈值窗口的定案必须**按固定顺序**在同一事务内：先 RELEASED 绑定行 → 再 CANCELLED Aggregate → 再逐条建单事件 Outbox → 再占位 notificationId；顺序颠倒会踩唯一索引（1190–1216）。
   - 崩溃恢复只认一种可识别不一致：「status=CANCELLED 但仍有 active=1 绑定行」，必须原子补齐且不得重复创建（1224–1225）。
   - 达到阈值不改变窗口边界；窗口冻结后不得追加事件；迟到事件走新的独立通知决策，不支持无限迟到回填（248.17、246.6）。
5. **Outbox 投递状态机**：`PENDING/PROCESSING/DELIVERY_UNKNOWN/SENT/RETRY_WAIT/FAILED/EXPIRED/CANCELLED`；`status` 是唯一事实来源，`sentAt` 只辅助；`PROCESSING` 必须带租约（`processingStartedAt/leaseUntilWall/leaseUntilElapsed/leaseBootId/workerInstanceId/deliveryAttemptId`）（0.6.11、248.16）。
   - `notify()` 永远在数据库事务之外：事务A claim+lease+attempt(started) → 事务外 notify → 事务B 写结果；崩溃/结果未知 ⇒ `DELIVERY_UNKNOWN`，**不得伪造 SENT**；重投必须复用同一 notificationId（0.6.34，3544–3594）。
   - 错误分类固定：临时网络/IO ⇒ RETRY_WAIT；权限/渠道禁用或明确不可恢复 ⇒ FAILED（不得无限重试）；超 `expiresAt` ⇒ EXPIRED（永久终止）；业务撤销 ⇒ CANCELLED（6773–6788）。
   - 权限恢复由 `NotificationCapabilityMonitor` 触发**有上限、分页**的扫描，把未过期 FAILED 转 RETRY_WAIT（1258、3558）。
6. **problemKey（系统问题通知）**：`{component}:{problemType}:{scopeKey}`，component ∈ MONITORING/DATABASE/NETWORK/NOTIFICATION/BACKGROUND/STATISTICS/RESTORE/EXPORT，problemType 为 `AppError` 枚举名，scopeKey = SYSTEM 或 streamerStableId；每次由「未激活→激活」追加 `:{episodeId}`，`problem_state.episodeId` 与 problemKey 必须同时写入（1282–1291）。
7. **冷却/过期/问题键三机制职责分离**，不得合并；主播直播通知不得被系统故障 problemKey 的冷却策略错误抑制（249.10.3，13801–13816）。
8. **静默时段（免打扰）**：文档术语是「免打扰时段」，时段内不显示普通开播/关播通知但监控继续（4697–4705）；配置项 `quietHoursEnabled=false`、`quietStart="23:00"`、`quietEnd="07:00"`（14130–14132）；主播级 `overrideQuietHours`（2995）；重点主播可突破免打扰（5201）。**注意：0.6 最终契约中没有任何免打扰表/字段/状态机定义**（见 §7 空白）。
9. **通知点击跳转**：统一 `RoomNavigator.openRoom(context, roomUrl, preference): NavigationResult`；`RoomOpenPreference{BILIBILI_APP_FIRST, IN_APP_FIRST, BROWSER_ONLY}`；默认优先 B 站 App，失败降级浏览器，URL 非法提示「无法打开直播间」，**不得闪退**；跳转失败写 ApplicationErrorLog，且不改变直播历史与监控状态（205.4，14248–14278；16105–16117）。
10. **通知可靠性验收 39 项**在 11045–11087（含「默认批量阈值 4」「默认窗口 5 秒」「首次发现 LIVE 不产生普通开播通知」「系统时间跳变不破坏超时/租约/5 秒窗口」等）。

### 3.7 人工修正与双层数据模型

- 双层：原始监控观察与人工修正结果**分离保存**，绝不物理删除原始数据（5969–6015、15851）。
- 修正优先级（降序）：`用户明确修正 > 可靠导入数据 > 自动监控确认数据 > 自动计算数据 > 暂定/推测数据`（11543–11551）。
- 自动覆盖保护：`startLocked/endLocked/durationLocked`（默认对用户确认字段开启）仅表示「后台自动刷新不得覆盖当前值」，**不表示用户不能编辑/撤销/恢复**；不得使用含义模糊的 `isLocked`（6294–6306、11577–11589）。
- 每次修正必须记录 `LiveSessionCorrectionEntity(correctionId, sessionStableId, baseCorrectionVersion, newCorrectionVersion, changedFieldsJson, source, createdAt, operationId)`；`operationId` 为幂等键；必须通过 `correctionVersion` CAS，防止旧页面提交覆盖新版本（0.6.21，2145–2160；249.12，13911–13913）。
- 撤销本身也是一次 Revision，审计记录永存；支持「撤销最近一次 / 恢复自动检测值 / 恢复到指定版本」（7104–7128、11849–11865）。
- 修改时间必须走版本/CAS 并写 Correction/AuditLog；批量修正必须逐条确认时间字段，不允许无提示批量覆盖（11828–11845）。

### 3.8 数据可信度与统计口径

- 五层 `DataConfidence{RAW, PROVISIONAL, CONFIRMED, CORRECTED, INVALID}`；来源 `DataSource{MONITOR_OBSERVED, USER_ENTERED, USER_CORRECTED, CALCULATED, IMPORTED, RECOVERED, ESTIMATED}`；单条记录纳入结果 `StatisticsInclusionState{INCLUDED, PARTIALLY_INCLUDED, EXCLUDED, UNKNOWN}`；查询口径参数是另一个枚举 `StatisticsEligibility{CONFIRMED_ONLY, INCLUDE_PROVISIONAL, INCLUDE_CORRECTED, INCLUDE_ESTIMATED}`（135–164、301–319）。
- 默认「准确统计」= `CONFIRMED + CORRECTED`；PROVISIONAL 只在「全部数据」模式显示；INVALID 永远不计入（12416–12455）。
- 统计必须可由 LiveSession 重建；`StatisticsCache/StatisticsSnapshot` 只是派生结果，**不是最终事实**；禁止直接修改统计总数（11166–11225、12513–12543）。
- 缓存键：`queryFingerprint = canonicalJson(range, timezone, eligibility, filters, grouping, sourceDataVersion)`，`cacheKey = SHA-256(UTF8(queryFingerprint))`，fingerprint 唯一；canonicalJson 规则固定（key 字典序、数组保序、null 显式、无浮点、IANA 时区、枚举名、epoch-millis）（0.6.17，1931–2030、0.6.37）。
- `sourceDataVersion` 必须由单条原子 UPDATE 递增（禁止先读后加再写）；推进集合**封闭**：LiveSession 创建/关闭/时间修正/合并/拆分/删除、correctionVersion 递增、confidence 变更、LiveEvent 新增或失效、StatusHistory 新增或重算、Interval 失效导致口径变化、Eligibility 相关字段变更、容量清理实际删除统计范围内记录；**不**推进：改统计时区、改监控/通知配置、改 UI 偏好、写诊断日志（1432–1445）。
- 跨区间：次数按 `startTime` 归一个周期，时长按区间**切片**累计；报表必须显示统计口径（126.1，12023–12050）。
- 统计三问（从哪些记录算出 / 哪些自动哪些人工 / 是否存在未解决盲区）：无法回答则**不得标记为「完全准确」**（12404–12412）。

### 3.9 备份 / 恢复范围与冲突处理

- **Manifest 声明范围；范围内以备份为最终权威，范围外本地数据保持不变**；「增量恢复」只表示不触碰范围外数据，**不表示范围内按时间戳竞争**；不得以 `updatedAt` 较新为由拒绝恢复（83–85、12939、248.13）。
- 覆盖恢复 ≠ 无条件 DELETE 全库：范围内最终状态与备份一致，范围外不变，且系统迁移元数据/当前恢复审计/当前运行安全状态不被覆盖（12947–12964）。
- 三个版本号职责分离：`formatVersion`（容器能否解析，不匹配直接 REJECTED）/`backupSchemaVersion`（业务字段能否理解，可按兼容矩阵降级）/`roomSchemaVersion`（目标库结构是否兼容，不匹配只能走「导入为历史数据」路径）（0.6.19，2093–2099）。
- `recordCounts` 必须覆盖范围内每类业务表，预检逐项比对，任何一项不一致不得进入 APPLYING（2101）。
- 稳定 ID 恢复矩阵（0.6.20，5 类冲突）+ 决策 `USE_BACKUP/KEEP_LOCAL/MERGE/SKIP`；MERGE 规则固定：保留备份 stableId、UID 采用用户选择来源、历史引用按 stableId 原子重映射、旧本地主键不迁移；无法保证引用闭包则 MERGE 不可执行（2136–2143、3764–3768）。
- 恢复链路必须带 `restoreRunId`；预检阶段不得写正式业务表；失败整体回滚；同 backupId+checksum 重复恢复必须可识别（3493–3518、12941–12945）。
- 恢复提交后**先推进 sourceDataVersion，再单独执行统计重建**；重建失败不回滚已恢复业务数据，保留「统计缓存待重建」状态（12875）。
- 范围必须形成**业务闭包**（Streamer → Tag/Group 关系 → LiveSession → Correction → LiveEvent(若纳入) → 关联业务元数据），缺父实体则拒绝该子记录恢复并计入 RestoreSummary 警告（3749–3760、13818–13831、0.6.39）。
- 导出与清理互斥：一致性快照建立后，历史清理不得改变快照已确定内容；资源超阈值必须安全取消导出并保留原始数据（249.7/249.13，13528–13541、13915–13917）。

### 3.10 删除、软删除与重新添加

- 软删除（默认）：置 `deletedAt`；当前 ACTIVE OPEN Session → ABANDONED（endTime 保持 NULL）；当前区间 INVALIDATED（reason=`STREAMER_DELETED`）；关闭 OPEN GapStreamer；删除 PendingTransition；**保留** LiveSession/LiveEvent/StatusHistory/Outbox/NotificationHistory/AuditLog；**不得触碰通知域**；未发送的 Outbox 置 CANCELLED，已 SENT 的与全部 History 原样保留；不发送关播通知（0.6.22.1，2191–2204）。
- 迟到响应保护：写回前必须同时满足 `streamer exists && deletedAt == null && monitorGeneration 有效 && observationSequence 未过期`，否则只能记录过期/丢弃，不得复活主播、不得新建 Session/Event/Outbox（6321–6330、245.4）。
- 硬删除（用户显式确认「同时删除历史」）：必须在单一维护事务内按 10 步固定顺序删除（attempt → history → registry → outbox → aggregate/event → gap/interval/status/event → correction/session → cross-ref/policy/pending → streamer → AuditLog），**禁止依赖 SQLite 级联**；被删的 notificationId 永久退休不得回收；完成后推进 sourceDataVersion 并做一致性检查（2208–2223）。
- 重新添加已软删除 UID = **恢复原实体**（清 deletedAt、保留 stableId/历史/用户配置），不得先删后增（249.10.0，13629–13643；9357–9369）。

### 3.11 并发、代际与时钟（最硬的实现约束）

| 机制 | 规则 | 出处 |
|---|---|---|
| 单实例租约 | `system_runtime_lock` 单行表 + Room Transaction；获取时递增 `monitorGeneration` 并换新 `fencingToken`；续期**不**递增 generation/不换 token；续期周期建议 = 租约/3；Tick 开始前必须先续期，失败即放弃本轮全部写入并写 HealthEvent | 0.6.35（3622–3672）、42.1（9295–9369） |
| MaintenanceMode 互斥 | `CHECK (maintenanceMode='OFF' OR monitoringLeaseId IS NULL)`；夺取维护权与清空租约必须在**同一条 UPDATE** 内完成；影响行数 0 ⇒ 放弃维护 | 1415、3697 |
| 主播级代际 | `streamerMonitorGeneration` 必须在 6 类事件递增（软/硬删除、重新添加且清上下文、监控开关 0→1、影响判定的策略变更、恢复重写策略、uid/roomId 手工修正导致观察目标变化）；同事务必须同步 `streamer_pending_transition.streamerMonitorGeneration`、把 ACTIVE 区间置 `GENERATION_CHANGED`；**不改动 confirmedLiveStatus** | 1898–1926 |
| 配置版本 | `configVersion` 表示「本 Tick 用了哪套配置」，**不是业务事实有效性开关**；参数必须完整落 `monitoring_config_revision` 才能重放；同一 Tick 所有批次共用一个不可变快照 | 0.6.16/0.6.16.1（1864–1871）、247.4（8312–8337） |
| fencing | 必须 `UPDATE/INSERT ... WHERE fencingToken = :token` 原子校验，**禁止先 SELECT 再应用层比较** | 247.2（8230–8259） |
| Tick 合并 | 同一时刻最多一个 RUNNING Tick；到期冲突只记 `missedTick`，连续漏掉最多合并为一次补偿 Tick | 41.1（9225–9243） |
| 双时钟 | 持续时间/超时/租约/退避/冷却/聚合窗口用 `elapsedRealtime`；墙上时钟只存业务时间点；跨 bootId 不得比较 elapsed；系统时间跳变时宁可提前冻结窗口 | 244.13（6970–6974）、93.1（7654–7666）、0.6.27 |
| 请求乱序 | 每次真实刷新带 `refreshGeneration/observationSequence`；提交必须满足 `incomingSequence > storedSequence`；保护必须在 Repository/事务层，不能只依赖内存 Coordinator | 244.2、167.x（8741–8756） |
| 取消传播 | 取消必须贯穿 `Controller → Scheduler → Coordinator → Coroutine → HTTP`；已取消结果必须被丢弃；主动取消不计入失败率/熔断/连续失败/Gap | 244.18（7016–7020）、249.10.6 |
| 事务边界 | 事务内禁止调用 `NotificationManager.notify()`、发起网络请求；CAS 影响 0 行不得当作数据库异常；组合写入必须 `withTransaction` | 788–795 |
| 锁重试 | 仅对 `SQLITE_BUSY/SQLiteDatabaseLockedException` 退避 50/150/400 ms，最多 3 次 | 516 |

### 3.12 网络、限流、熔断与错误分类

- 请求链固定顺序：`CircuitBreaker → RateLimiter/ConcurrencyLimiter → HTTP → 错误分类 → 可重试判定 → Backoff+Full Jitter → 回到 CircuitBreaker`；**禁止绕过限流器/熔断器直接重试**（246.5，6470–6494）。
- 超时建议 `connect 10s / read 15s / write 15s`；退避基础 1s、指数因子 2、上限 1/2/4s、实际等待 `random(0, 当前上限)`，不超过 `maxRetryDelay`（8558–8586）。
- 重试只针对网络失败/超时/5xx；**429 必须单列**，若服务端给 `Retry-After` 优先遵循并仍受本地上限约束（246.5）。
- 熔断按 API 类别隔离：至少 `LIVE_STATUS / ROOM_METADATA / FOLLOWING / AUTH`；`LIVE_STATUS` 必须独立，避免资料/登录接口故障连带停掉公开状态监控；`CLOSED/OPEN/HALF_OPEN`，HALF_OPEN 只允许**单 Probe**（8774–8820、248.4）。
- 批量响应 `BatchRoomStatusResponse{requestedUids, resultsByUid, missingUids, duplicateUids, responseValidity}`；`responseValidity` 只描述整体，真正进状态机的必须是**每 UID 的 ObservationResult**；`PARTIAL` 不得整体当作 SUCCESS；缺失 UID 一律记 `PARTIAL/UNKNOWN`，**绝不能推断为 OFFLINE**（0.6.25，2368–2387；8653–8671、8770）。
- 资料刷新与状态检查解耦：`LiveStatusCheck`（高频、最小字段）与 `RoomMetadataRefresh`（低频/状态变化时；昵称/头像/标题/分区/封面）（原规范 45，6240–6274）。
- 远程字段空值保护：缺失/未知 ⇒ 保留旧值；明确删除 ⇒ 才允许清空；新值有效 ⇒ 更新（P0-13，7022–7045）；`uid` 是身份、`roomId` 等可变，**不得写 0/-1 等占位冒充**（249.9.1，13565–13569）；`ObservationResult.SUCCESS` 需同时满足 5 个条件（249.9.2，13615–13627）。
- Endpoint：用户自定义 URL 仅允许 HTTPS，默拒 `file:// / content:// / javascript: / intent:// / 本地任意端口`；正式发布版默认关闭任意 Endpoint 编辑；自定义 Host **永远不得**继承 B 站 Cookie/Token/Authorization，必须用独立 `CustomEndpointClient`（8868–8890、244.8）。
- 间隔：快照档位 30/60/90/120/300（默认 60）；高级自定义安全范围 **15–3600 秒**；省电模式单独定义分钟级参数，不与实时共用秒级语义（8908–8917、244.7）。

### 3.13 健康状态聚合

- 子系统各自独立计算（`MonitoringHealth / NetworkHealth / DatabaseHealth / NotificationHealth`），再由统一 `OverallHealthEvaluator` 汇总；**页面不得自行拼接健康结论**（244.17、188.5，15147–15168）。
- 矩阵要点：数据库 FAILED/CRITICAL ⇒ ERROR；监控 STOPPED/SYSTEM_RESTRICTED 且 desiredRunning ⇒ USER_ACTION_REQUIRED/SYSTEM_RESTRICTED；通知 FAILED 但监控正常 ⇒ DEGRADED（**不得伪装成监控停止**）；网络 DEGRADED 但仍能部分可靠观察 ⇒ DEGRADED；核心子系统正常 ⇒ HEALTHY。
- 优先级链（249.10.7，13879–13892）：数据库不可用 > 监控实际停止且用户意图仍要求运行 > 受系统限制无法达到用户要求 > 通知不可用但监控可运行 > 网络暂时异常正在恢复 > 健康。
- 用户选择保活方式 ≠ 实际能力：必须显示实际状态，任何降级都要写 HealthEvent 并更新首页态；`NO_GUARANTEE` 下被系统停止**不属于错误**（不得弹错误通知），但恢复时必须如实标记该段为监控盲区（0.6.47.2，1525–1549）。
- 通知权限关闭/渠道禁用**不能停止主播状态检测**；数据库不可写时也不能假装结果已可靠保存（7775、11122）。

---

## 4. 技术约束

### 4.1 技术栈与架构（原规范 3/4/38/74，4047–4193）

Kotlin / Gradle Kotlin DSL / Jetpack Compose + Material 3 + Navigation Compose / ViewModel / Coroutines+Flow / Room + DataStore / Kotlin Serialization / OkHttp + Retrofit / Coil / Hilt / WorkManager + Foreground Service + BroadcastReceiver + NotificationManager；架构 `UI → ViewModel → UseCase → Repository → (Room/DataStore | Retrofit/OkHttp)` + `MonitoringService/WorkManager → MonitoringEngine → MonitorRepository → Room+API → State Transition → Notification`。
强约束：UI 不得直连网络、DAO、通知或 Service；网络 DTO 不得进 UI；必须存在 Domain Model；`@Dao` 只允许出现在 0.6.15 登记的接口中，DAO 不得暴露给 ViewModel 写业务状态（3772、3931、15487–15490、1829）。工程目录建议见 4285–4418。

### 4.2 数据库：唯一 Schema 基线（0.6.29 + 0.6.32 合并，共 42 张表）

`@Database(version = 1, exportSchema = true, autoMigrations = [])`，实体清单见 443–471（**清单内共 42 个 `@Entity`**，注意 0.6.43 检查表写的是「44 个」，见 §7）。按域分组：

| 域 | 表（DDL 行号） |
|---|---|
| 核心业务事实 | `streamer`(2452)、`tag`(2489)、`group`(2497)、`streamer_tag_cross_ref`(2505)、`streamer_group_cross_ref`(2514)、`live_session`(2523)、`live_event`(2570)、`status_history`(2671)、`streamer_pending_transition`(2603)、`reliable_monitor_interval`(2614)、`monitoring_gap`(2637)、`monitoring_gap_streamer`(2651)、`live_session_correction`(2860) |
| 通知 | `notification_aggregate`(2696)、`notification_aggregate_event`(2720)、`notification_outbox`(2747)、`notification_delivery_attempt`(2811)、`notification_id_registry`(2828)、`notification_history`(2838)、`notification_cooldown`(3298)、`problem_state`(3305) |
| 配置与运行态 | `monitoring_config`(2871，单行)、`monitoring_config_revision`(2900)、`source_data_revision`(2928，单行)、`system_runtime_lock`(2934，单行)、`streamer_monitor_policy`(3217) |
| 统计 | `statistics_cache`(3257)、`statistics_snapshot`(3273)、`statistics_revision`(3291) |
| 备份/恢复/导出 | `export_snapshot`(3315)、`export_snapshot_live_session`(3328)、`restore_run`(3337)、`restore_conflict`(3349) |
| 账号与外围 | `auth_session`(3362)、`follow_import_task`(3236)、`follow_import_staging`(3247)、`audit_log`(3371)、`monitoring_error_log`(3385)、`application_error_log`(3398)、`crash_record`(3407)、`health_event`(3417)、`recovery_session`(3428) |

关键结构约束：

- **16 条带 WHERE 的部分唯一索引必须由 `Migration.execSQL` 创建**（0.6.4.3，713–779）：`idx_live_session_one_active_open`、`idx_live_session_one_abandoned_open`、`idx_live_event_transition_effect`、`idx_live_event_one_start`、`idx_live_event_one_end`、`idx_interval_one_active`、`idx_gap_one_open`、`idx_gap_streamer_one_open`、`idx_outbox_aggregate_one`、`idx_outbox_one_per_source_event`、`idx_aggregate_one_in_progress`、`idx_aggregate_event_one_active`、`idx_recovery_one_running`、`idx_recovery_one_requested`、`idx_status_history_transition`、`idx_attempt_unfinished`。普通/完整唯一索引由 Room `@Entity.indices` 建立，两类都不允许缺失（2442–2449）。
- 单行表：`monitoring_config` / `source_data_revision` / `system_runtime_lock`（`CHECK(singletonId = 1)`）；**首次建库必须幂等写入单例行**（`INSERT OR IGNORE`），否则所有 CAS 因 `EXISTS` 不成立而影响 0 行，表现为「不报错、日志只有 STALE_OR_FENCED、监控永远不工作」（0.6.4.5，797–851）。种子默认值见 §2.11。
- 运行期并发契约：`journal_mode=WAL`、`busy_timeout=5000`（必须在 `onOpen` 与 `onConfigure` 都设置，覆盖池内每条连接）、`foreign_keys=ON`（每次打开强制）（509–558）。
- 枚举列序列化固定为 `Enum.name`，与 DDL `CHECK ... IN ('...')` 字面量逐字一致；未知值不得静默回退默认值（0.6.4.2，560–705）。
- Migration 固定顺序：父表 → 业务字段与默认值 → 实体/关系表 → 普通索引 → **最后**创建 WHERE 部分唯一索引 → `PRAGMA foreign_keys=ON` 后一致性检查 → 审计；Migration 内不得依赖级联删除（3897–3913、786）。
- 并发「最多一个」约束必须同时有应用事务与部分唯一索引保障，不得只靠单线程协程（0.6.36，3699–3712）。
- 唯一性双列检查（ConsistencyChecker 必执行）：`live_event`/`status_history` 的 `streamerId` 与 `streamerStableId` 必须指向同一主播（2246–2255）。
- 导出临时表方案：每种导出实体各建 `export_snapshot_<业务表名>`，主键 `(snapshotId, 稳定身份列)` + `rowJson TEXT NOT NULL` + 外键 CASCADE + snapshotId 索引；新增临时表必须同步写入 0.6.32 基线（0.6.38，3735–3747）。
- 通知域清理与保留（0.6.12，1299–1320）：Outbox（SENT/EXPIRED/CANCELLED）保留 `max(30天, 最近2000条)`；Aggregate 保留 30 天；Attempt 保留 30 天或跟随 Outbox；**NotificationHistory 不随 Outbox 清理而删除**，保留 `max(90天, 最近5000条)`；**notification_id_registry 永久保留不得回收**。清理必须排除可投递状态行、单次有上限（如 500 行）并分页、与导出/备份/恢复共用维护互斥。

### 4.3 枚举清单（0.6.2 唯一最终定义，134–347）

`ConfirmedLiveStatus{UNKNOWN,OFFLINE,LIVE,ROUND}`、`ObservationResult{SUCCESS,TIMEOUT,NETWORK_ERROR,API_ERROR,PARTIAL,INVALID}`、`PendingTransition{NONE,SUSPECTED_OFFLINE,SUSPECTED_LIVE}`、`DataConfidence{RAW,PROVISIONAL,CONFIRMED,CORRECTED,INVALID}`、`DataSource{...7值}`、`StatisticsEligibility{4值}`、`DataFreshness{FRESH,STALE,UNKNOWN}`、`MonitoringMode{REALTIME,POWER_SAVING,MANUAL}`、`MaintenanceMode{OFF,DATABASE_REPAIR,BACKUP,RESTORE,MIGRATION}`、`RecoverySessionStatus{6值}`、`RecoveryFinishReason{HEALTHY,TIMEOUT,FAILED,CANCELLED,SUPERSEDED}`、`MonitorHealthStatus{HEALTHY,DEGRADED,RECOVERING,BLOCKED,STOPPED}`、`AppError{13值}`、`NotificationEventType{6值}`、`LiveEventType{START,END,LIVE_RECONFIRMED}`、`ReliableIntervalInvalidReason{10值}`、`GapReason{NETWORK_UNAVAILABLE,API_UNAVAILABLE,DATABASE_BLOCKED,MONITORING_STOPPED,RUNTIME_CRASH,UNKNOWN}`、`BackupScopeType{4值}`、`RestoreFinishReason{5值}`、`NotificationAggregateStatus{COLLECTING,READY,FROZEN,DISPATCHED,CANCELLED,EXPIRED}`、`NotificationOutboxStatus{8值}`、`AggregateEventBindingStatus{PENDING_AGGREGATION,BOUND,RELEASED}`、`IntervalStatus{ACTIVE,INVALIDATED,CLOSED}`、`GapScope{SYSTEM,STREAMER}`、`RestoreRunStatus{6值}`、`RestoreConflictAction{USE_BACKUP,KEEP_LOCAL,MERGE,SKIP}`、`RestoreConflictType{5值}`、`NotificationHistoryDeliveryStatus{8值}`、`ExportType{HTML,JSON,BACKUP,DIAGNOSTIC}`、`ExportSnapshotStatus{7值}`、`FollowImportStatus{7值}`、`StatisticsInclusionState{4值}`、`LiveSessionOrigin{AUTO_MONITOR,USER_ENTERED,IMPORTED,RECOVERED}`、`BackgroundKeepAliveChoice{FOREGROUND_NOTIFICATION,ACCESSIBILITY,NO_GUARANTEE,UNSET}`、`TransitionReason{5值}`、`DeliveryAttemptResult{SENT,DELIVERY_UNKNOWN,FAILED}`、`LiveSessionLifecycleState{ACTIVE,ABANDONED}`、`CasWriteResult{6值}`、`BatchResponseValidity{VALID,PARTIAL,INVALID,TRANSPORT_ERROR}`。
废弃别名（0.6.42.1，3796–3831）：`AggregateStatus/MonitorMode/MonitoringHealth/TimeDataSource`（永久废弃）、`DataFreshness.AGING`、`MaintenanceMode{NONE,CONSISTENCY_CHECK,IMPORT}`、`RecoveryFinishReason{COMPLETED,ABORTED,STILL_UNAVAILABLE}`、`startedAt/endedAt`、`isCompleted`、`isLocked` 等。

### 4.4 Android 后台限制（0.5、0.6.27、58、11、30、237–239）

```text
后台启动 FGS：Android 12+ 受限 ⇒ 实时监控启动应由用户在应用可见状态下主动操作
FGS 类型与权限：Android 14+ 必须声明匹配的 foregroundServiceType + 权限，否则可能拒绝启动（非可选）
dataSync 限制：Android 15+ 对 dataSync/mediaProcessing FGS 施加 24 小时窗口内累计 6 小时限制
              ⇒ 不得把 dataSync 当永久后台轮询方案；不得为保活申请不匹配类型
WorkManager：PeriodicWorkRequest 最小重复间隔 15 分钟 ⇒ 不能实现 30/60 秒级实时监控
特殊类型：确符合 specialUse 才用，且 Manifest 必须写明用途并考虑商店审核
精确闹钟：不默认申请 SCHEDULE_EXACT_ALARM，正常监控不应依赖
重启/升级：监听 BOOT_COMPLETED 与 MY_PACKAGE_REPLACED，读取库内监控设置；
          不得在所有版本上从 Receiver 无条件启动 FGS
电池优化：只能引导用户去系统设置，不得声称“100% 杀不死”；不同厂商 ROM 仍有额外限制
降级链：FGS 不可继续 → 记 DEGRADED → 转允许的低频调度 → 条件恢复后按当前规则重启实时方案
禁止：任何“绕过 Android 限制”的技巧；禁止 while(true)+delay 常驻线程；禁止虚假显示“后台运行中”
```

出处：111、0.5、9383–9444、9494–9527、9637、9641–9671、11111–11112。真机测试必须覆盖 Android 12/13/14/15/16 与小米/华为/OPPO/vivo/三星等 ROM（15403–15421、15699–15707）。

### 4.5 安全与隐私

- 严禁入库/入日志/入导出：`access_token / refresh_token / SESSDATA / bili_jct / Cookie / 密码 / Authorization Header / 完整登录响应`（13963–13978、12750–12762、13190–13200、14371–14377、14468–14477、14774–14790、16090）。
- 凭证用 Android Keystore + 加密存储，库内只存登录 UID/登录状态/最后刷新时间（13991–14013）。
- 日志必须结构化 + 分级 + 脱敏 + 可筛选 + 可清理 + 默认不含凭证；URL 查询参数必须脱敏（14391–14415、14747–14794、15027–15063）。
- 两类日志（监控错误 vs 软件运行错误）必须表/Repository/查询/页面/保留周期/导出方式全部分离，不得合成一张「大杂烩错误表」（14554–14710、16086–16087）。
- HTML 生成必须把远程文本与用户文本视为不可信：HTML Escape、JSON 由序列化器生成、JS 不得拼接用户输入、URL 过白名单协议（248.15，6741–6752）。
- 无障碍服务：默认关闭、独立模块、最小权限、用户主动授权、可随时关闭、状态可观测；不得读取/上传/记录与监控无关的屏幕内容与输入事件；不得作为 MonitoringEngine 唯一运行基础、不得成为数据访问或隐蔽行为入口（244.9、238、0.6.41）。

### 4.6 最低测试与并发门槛（0.6.28，2406–2432）

进入 P1/发布前必须存在并通过 21 项测试，含：部分唯一索引 Migration 冷启动后的真实 SQLite 检查；`sourceDataVersion` 100 并发事务不丢号不重复；PendingTransition 并发 2 次 OFFLINE 计数正确；旧 generation/fencing 必须 CAS 失败；首次 UNKNOWN→LIVE 建 PROVISIONAL OPEN 且无 START 通知；软删除 ACTIVE OPEN → ABANDONED 不填 endTime；多 ABANDONED OPEN 修复至最多一个；异常恢复 LIVE 发「正在直播」并建新区间；恢复 OFFLINE 双不发；新区间正常至 OFFLINE 发关播；ROUND 不关场次不发关播且 Interval 保持 ACTIVE；Aggregate 崩溃恢复不重复不遗漏；Aggregate+Outbox 原子创建无孤儿；DELIVERY_UNKNOWN 后租约超时重试复用同一 notificationId；权限恢复后未过期 FAILED 可重入 RETRY_WAIT、过期不重试；统计缓存时区/筛选/口径不同不得错误命中；导出分页在同一只读事务中看到一致数据；stableId/uid 两类冲突必须进入决策；跨设备恢复的 Correction 必须引用 stableId/correctionId 而非本地 Long id。

---

## 5. 「必须实现 / P0 / P1 / P2 / 验收门槛」条目清单（带行号）

> **重要前提**：P0/P1/P2 在本文档中存在**多套并存且粒度不一致**的历史优先级表（原规范 40 / 104 / 159 / 163 / 195 / 196 / 231），历史上从未收敛为单一权威。**真正具有交付约束力的门槛只有三处**：0.6.43（生成前）、0.6.46（生成完成前）、0.6.28（最低并发/一致性测试）。其余 P0/P1/P2 表按「范围清单」使用，不计入门槛（16381–16383 明确禁止引用历史章节作为定义来源）。

### 5.1 三套权威门槛（逐条）

**① 0.6.43 最终实现检查表（生成前门槛，3842–3883）** — 五组，共 29 项：
- 【装配层】`AppDatabase 已声明全部 @Entity（原文写 44 个）、exportSchema=true、version 已固定`(3848)；`DshTypeConverters 覆盖 0.6.2 与 0.6.43 登记的全部枚举，序列化为 Enum.name`(3849)；`AppMigrations.ALL 覆盖全部带 WHERE 的部分唯一索引（逐条与 0.6.4.3 比对）`(3850)；`journal_mode=WAL / busy_timeout / foreign_keys=ON 均已配置`(3851)；`所有 @Dao 只在 0.6.15 登记，无第二处 DAO 定义`(3852)。
- 【模型唯一性】每个最终 enum 都有 Kotlin 定义且无第二套同名定义(3855)；每个当前功能都有唯一 Entity/DTO 入口(3856)；每个业务表 ↔ 恰好一个 @Entity(3857)；0.6.29+0.6.32 覆盖所有持久化表，无未声明表（含临时快照表）(3858)；0.6.42.1 别名登记表名称全文未被用作类型/列/枚举值(3859)。
- 【约束与并发】所有部分唯一索引由 Migration.execSQL 建立(3862)；业务「最多一个」同时有 DB 与事务级保障(3863)；CAS SQL 同时校验 streamer generation + global fencing(3864)；关键枚举列均有 CHECK 且字面量与 Kotlin 枚举名逐字一致(3865)；每个指向业务表的 FK 子列都有索引（Room 编译零 warning）(3866)。
- 【业务闭环】Outbox 必有 eventType+payloadJson+nextAttemptAt+attemptCount+configVersion(3869)；单事件通知幂等由 sourceEventId 唯一索引保证、无字符串解析 eventKey(3870)；系统问题通知三种互斥形态 CHECK 已覆盖 problemKey(3871)；Aggregate ↔ Outbox 双向不变量在同一事务成立(3872)；Aggregate 崩溃恢复不重复不遗漏(3873)；所有配置都能由 configVersion 完整重放（monitoring_config_revision 必备）(3874)；sourceDataVersion 触发集合封闭且仅由单条原子 UPDATE 递增(3875)；StatisticsCache key 包含 query+timezone+eligibility+sourceDataVersion(3876)；queryFingerprint 与 cacheKey 关系唯一(3877)；ExportSnapshot 具有真实一致性机制（长只读事务与临时快照表不得混用）(3878)；RestoreRun/Conflict 可跨进程恢复(3879)；FollowImportTask 与 authGeneration 隔离(3880)；Runtime state/Lease/token 不进入业务备份(3881)；历史章节不得再创建第二套同名模型(3882)。

**② 0.6.46 AI Coding Agent 最终门槛（生成完成前「零自行发明」检查，3933–3966）** — 17 项通用 + 9 项 0.6.47 专项：
- 通用：每个 enum 来自 0.6.2 或专用状态机章节(3938)；每张表都有 DDL 与 @Entity(3939)；每个 Entity 映射唯一表(3940)；所有 WHERE 部分索引都有 Migration.execSQL(3941)；没有第二个 RoomDatabase、没有 0.6.15 之外的 DAO(3942)；所有枚举字段经 DshTypeConverters、无 ordinal 隐式序列化(3943)；关键并发写都有 CAS WHERE(3944)；Outbox/Attempt/History 的 eventKey 链路一致(3945)；notificationId 只能来自 registry（不得 hashCode/时间戳派生）(3946)；单事件幂等由 sourceEventId 唯一索引保证(3947)；AggregateEvent binding 不产生第二套事件状态、RELEASED 后可重新绑定(3948)；StatisticsCache key 可由 canonicalJson 确定重算(3949)；ExportSnapshot 真正使用一致性读且未混用两套语义(3950)；RestoreRun/Conflict 可跨进程恢复(3951)；UI/外围不创建第二套业务事实(3952)；历史章节不得产生新模型/新表/新枚举(3953)；别名登记表名称未出现在生成代码标识符中(3954)。
- 0.6.47 专项：首次启动引导页存在、三选项文案完整、未选择时不得自动启动实时监控(3957)；`backgroundKeepAliveChoice` 落库 + 每次改选写 AuditLog、无绕过审计的直接 UPDATE(3958)；NO_GUARANTEE 必须记录 `noGuaranteeAcknowledged/At`(3959)；用户选择与系统能力不一致时显示「实际状态」、无静默降级(3960)；确认次数为单一阈值、无两段式实现、无第二个通知阈值列(3961)；主播级确认次数 NULL 时正确回退全局且 Tick 期间不重读(3962)；导出使用方案 B、代码中不存在长只读事务导出分支(3963)；昵称占位只来自 `monitoring_config.placeholderText`、未写入历史快照与用户自定义字段(3964)；`nameLocked=1` 时远程刷新确实跳过 name(3965)。
- 二者关系（0.6.43.1，3885–3891）：0.6.43 = 进入代码生成前的设计自洽检查；0.6.46 = 生成完成前的实现检查；**不可互相替代，任一未全部勾选即不得进入下一阶段**。

**③ 0.6.28 最低并发/一致性测试门槛（2406–2432）** — 见 §4.6（21 项）。

### 5.2 P0 清单（多套，按出处逐条）

**原规范 40.1「P0：核心可靠闭环」16 条（4429–4448）**：①手动添加/编辑/删除主播 ②单主播监控开关 ③开播/关播检测 ④状态确认与误报保护 ⑤MonitoringController/Scheduler ⑥MonitoringRuntimeState 与 MonitoringLease/fencing ⑦NotificationOutbox 与通知去重/重试 ⑧网络/API 错误隔离 ⑨批量状态查询 ⑩数据库一致性与 Migration ⑪直播历史 LiveSession ⑫状态/历史事件审计 ⑬统计可重建与数据质量保护 ⑭直播历史备份配置文件导出/恢复 ⑮HTML 直播历史导出 ⑯Android 12+ 后台/权限适配。

**原规范 195「P0/P1 验收总表」P0 23 项（15935–15957，原文均为 `[ ]`）**：状态抖动保护｜RefreshCoordinator｜监控心跳｜desired/actual/health 三态｜数据库完整性检查｜LiveSession 来源记录｜人工修正锁定｜修正可撤销｜统计可重建｜Session 时间冲突检测｜ExportSnapshot｜原子导出｜通知过期｜主播删除保留历史｜RecoverySession｜主播资料空值保护｜登录与监控解耦｜RecoveryCheck 与普通 MonitoringTick 互斥｜旧刷新响应不能覆盖新刷新结果｜API 部分响应的缺失 UID 不得被视为 OFFLINE｜Outbox PROCESSING 具有租约并可恢复｜聚合窗口固定锚点且进入窗口后先等待聚合决定｜同一事件使用稳定 notificationId 防止重复系统通知。

**原规范 163「P0：必须进入正式开发基础」9 项（16646–16658）**：手工直播历史｜两项时间自动计算｜历史异常修正｜原始数据与修正数据分离｜修正审计日志｜统计重新计算｜数据可信度｜统计来源追溯｜数据质量提醒。

**原规范 104「新增 P0 功能」16 项（16535–16552）**：DataConfidence｜MonitoringGap｜LiveSession 完整生命周期｜网络异常与 UNKNOWN 状态｜崩溃/进程终止识别｜StartupRecovery｜ConsistencyChecker｜RecoveryCheck｜统计可重建｜异常数据排除｜数据新鲜度｜分级异常通知｜异常通知冷却与去重｜监控恢复提醒｜故障审计日志｜昨日监控报告（16554：不应全部作为未来可选功能处理）。

**带编号 P0-x 专章（正文散落）**：P0-1 状态抖动保护(7322)｜P0-2 RefreshCoordinator(8675)｜P0-3 监控心跳与运行三态(7707)｜P0-4 数据库完整性检查(7049)｜P0-5 LiveSession 数据来源与锁定(6278)｜P0-6 人工修正必须可撤销(7104)｜P0-7 统计可重建(12513)｜P0-8 Session 时间冲突检测(7131)｜P0-9 导出快照与原子导出(13222)｜P0-10 通知过期控制(10583)｜P0-11 主播删除与历史数据解耦(6310)｜P0-12 恢复检查模式(7779)｜P0-13 主播资料空值保护(7022)｜P0-14 登录状态与监控解耦(8031)。
另：246/247/248 为「P0/P1 深层稳定性修正」三轮专章（6392、6572、8206），各带不变量集合（245.13 于 8195–8204、247.8 于 8437–8446、248.20 于 6817–6835）。

### 5.3 P1 清单

**原规范 40.2「P1：重要体验与管理能力」12 条（4450–4465）**：标签/分组｜搜索/筛选/排序｜主播详情页｜重点主播｜主播级监控/通知策略｜免打扰｜批量操作｜通知历史｜健康检查与诊断中心｜历史容量管理与自动清理｜配置导入预览/回滚｜数据修正、合并、拆分与审计。

**原规范 195 的 P1 15 项（15963–15977）**：API 熔断器｜监控可靠性评分｜日志生命周期｜通知冷却｜导入校验和｜MigrationVerifier｜批量操作幂等｜异常恢复报告｜严重异常分级提醒｜数据质量中心｜统计缓存失效｜历史导入隔离｜禁止猜测式自动修复｜全局一致性检查｜设置变更串行化。

**原规范 163 的 P1 7 项（16660–16670）**：Session 合并｜Session 拆分｜日历视图｜时间线视图｜批量异常处理｜统计质量面板｜HTML 完整报告。

**原规范 231 的 P1 14 项（16345–16358）**：高级检测间隔自定义｜高级网络参数自定义｜安全的 Endpoint 配置展示/开发配置能力｜Bilibili App 优先跳转｜用户跳转偏好｜监控错误日志｜软件运行错误日志｜日志筛选｜日志聚合｜日志生命周期管理｜开发者诊断模式｜一键诊断包导出｜correlationId｜崩溃恢复日志联动。

**带编号 P1-x 专章**：P1-1 API 熔断器(8774)｜P1-2 监控可靠性评分(7846)｜P1-3 日志生命周期管理(15027)｜P1-4 通知冷却机制(10614)｜P1-5 导入文件校验和(13266)｜P1-6 MigrationVerifier(13304)｜P1-7 批量操作幂等性(15065)｜P1-8 异常恢复报告(15090)｜P1-9 用户可见的严重异常等级(15108)｜P1-10 数据质量中心(15170)｜P1-11 统计缓存失效策略(12547)｜P1-12 导入历史数据的预检与隔离(13329)｜P1-13 数据质量异常不自动伪造修复(12597)｜P1-14 全局一致性检查任务(12626)｜P1-15 设置变更的串行化(15194)。

### 5.4 P2 清单

- **原规范 40.3「P2：可选增强」8 条（4467–4478）**：Bilibili 官方账号授权与关注列表导入｜拼音搜索｜直播预约提醒｜自定义通知模板｜自动标签/自动分组｜云端持续监控｜Web 控制台｜多平台推送。
- **原规范 163 的 P2 6 项（16675–16680）**：HTML 高级图表｜多主播对比｜标签/分组统计｜热力图｜自定义报告模板｜自动生成周期报告。
- **原规范 231 的 P2 5 项（16364–16368）**：多套 API 配置预设｜高级请求策略模板｜远程诊断辅助｜自动生成开发者故障摘要｜错误趋势分析。
- 另：P2-10「双列一致性检查（ConsistencyChecker 必须执行）」（2247）；分组策略作为 P2 预留（5175）。

### 5.5 验收门槛与指标类条目

| 出处 | 性质 | 要点 |
|---|---|---|
| 原规范 73（4547–4577） | v2.0 最终验收原则 25 条 `[ ]` | 能加主播/准确显示状态/开播能通知/关播能通知/ERROR 不触发下播/同一事件不重复/通知失败可重试或留记录/可搜索/可标签分组筛选排序/可进直播间/可手动刷新/可批量管理/可看详情/可看历史/可看通知历史/可看健康/可导入导出配置/可备份恢复/凭证不入日志导出/Android 13+ 通知权限/Android 14+ FGS 类型/Android 15+ 不把 dataSync 当永久方案/后台异常后有明确降级恢复/Room Migration 有测试/核心状态机有单元测试 |
| 原规范 66（15616–15639） | 可靠性指标（**无具体数值 SLA**） | 8 条指标式验收 + 5 个可记录指标名：`monitor_success_rate / api_error_rate / notification_success_rate / average_check_latency / consecutive_failure_max` |
| 原规范 102（12679–12696） | 可靠性验收 12 条 | 网络异常绝不产生下播｜崩溃不伪造精确下播时间｜未确认时间段必须可识别｜LiveSession 支持未完成态｜统计可从源数据重算｜用户能看到监控中断｜重要异常不无限重复通知｜恢复后立即补偿检查｜Outbox 未发送完成可恢复｜修复不能以猜测代替事实｜卡片不能把过期数据伪装成实时｜用户可区分「未开播」与「暂时无法确认」 |
| 原规范 158（15868–15926） | 验收场景 6 组 | 手工时间三组合计算；三项矛盾拒绝保存；自动覆盖保护（19:03→用户改 19:00→再刷新仍 19:00）；数据异常不得凭空获得准确下播时间；统计重算；导出 HTML 可离线打开且不含 Token/Cookie |
| 原规范 230（16121–16169） | 稳定性新增验收 4 组 | 高级设置（间隔过小被拒/过大被限/非 HTTPS 被拒/恢复默认/改间隔取消旧任务）；跳转 4 例；日志 5 例；崩溃恢复 7 步链 |
| 原规范 250（16173–16199） | 深层稳定性验收 21 条 | 含「旧版本恢复高版本备份须明确提示部分恢复」「容量 95% 预警」「容量清理不删 OPEN」「导出无记录不伪造日期」「关注导入不覆盖用户配置」「Export 与 Cleanup 并发不产生混合快照」「Migration 与 Monitoring 并发旧 Engine 无法写入」「旧 Worker 失 Lease 无法发送」「旧实例失 fencing 无法提交」「跨月场次次数归一个周期、时长切片」「两编辑页同时保存旧提交失败」「HTTP 200 但关键字段异常不得标 SUCCESS」等 |
| 原规范 241（11040–11087） | 通知可靠性验收 39 条 | 见 §3.6 第 10 点 |
| 原规范 100.5（16867–16882） | **已被取代**的 v2.3 交付门槛 10 条 | 16823–16825 明确作废，仅作追溯：Entity/DTO/Enum 无同名第二定义｜部分索引都有 Migration SQL｜关键 update 都有 CAS｜来源字段 nullable 语义明确｜跨设备实体有稳定身份｜状态机枚举有终态与恢复路径｜通知异常有 Outbox 最终状态｜统计缓存有 query/timezone/eligibility 维度｜导出/恢复有一致性边界｜P0 并发测试列入测试套件 |

### 5.6 AI Coding Agent 强制规则类条目

- **原规范 35（15479–15531）47 条最高优先级工程规则**：分层与解耦（15484–15490）、后台检查必须经 MonitoringEngine(15491)、通知必须基于状态转换(15492)、不得每次轮询都通知(15493)、不得把网络错误当已下播(15494)、凭证不入库不入日志(15495–15496)、URL 不散落(15497)、接口抽象(15498)、超时/可取消/批量优先/不得无限重试(15499–15502)、Android 12+/13+/14+/15+ 约束(15504–15507)、改字段必须 Migration(15508)、配置导入域隔离(15509–15510)、输入校验与文案集中(15511–15512)、关键 UseCase 必须有单测(15513)、不得臆造接口字段(15515)、`confirmedLiveStatus` 不得含 ERROR(15516)、逐主播 ObservationResult(15517)、确认上下文必须持久化(15518)、关键写入必须带 CAS(15519)、UNKNOWN→LIVE 规则(15520)、软删除规则(15521)、关播资格判定器(15522)、恢复通知规则(15523)、notificationId 注册表(15524)、DELIVERY_UNKNOWN/FAILED/EXPIRED 恢复(15525)、Aggregate 同事务(15526)、stableId/uid 冲突必须走 Resolver(15527)、ROUND 语义(15528)、Lease/Maintenance 数据库级互斥(15529)、单一 configVersion(15530)。
- **原规范 197（16036–16061）20 条 P0/P1 强制规则**、**227 高级设置 11 条**（16065–16079）、**228 日志系统 15 条**（16083–16101）、**229 直播间跳转 9 条**（16105–16117）、**242 通知与保活 30 条**（11094–11123）、**249.11 最终实现禁令 11 条**（13894–13908）、**70 开发协议 10 条 + 输出顺序**（16420–16455）、**199 高级设置与可配置化（P1）**（9675–9703）、**105 可靠性优先 20 条**（15743–15766）、**157 直播历史 20 条**（15842–15864）——均已在上文按主题引用，此处不再重复展开。

---

## 6. 文档末尾（10000 行之后）额外章节总结

> 说明：文档实际 16882 行，因此「10000 行之后」覆盖第 06 章尾部至全文结束。其中第 12–15 章、附录 A/B、§100 是真正意义上的「末尾额外章节」，此前章节已在 §2–§5 展开，这里按章给出总结与价值判断。

| 章节 | 行号范围 | 内容摘要 | 对实现的价值 |
|---|---|---|---|
| 06 通知业务尾部 | ~10000–11164 | 批量开播聚合（233/234/235）、异常等级（89）、去重冷却（90）、通知过期（175）、冷却机制（183）、通知历史中心（48）、日志与通知关系（220–225）、点击跳转（236）、通知内容最终规范（240）、通知可靠性验收（241）、通知与保活强制规则（242）、三层职责总结（243） | 高：通知文案、聚合阈值/窗口、验收 39 项 |
| 07 直播历史/修正/统计 | 11164–12700 | 统计可重建（83）、可信度展示（84）、异常排除（85）、修正系统（115）、来源追溯（116）、四种纳入状态（117）、明显错误检测（121）、重叠与拆分（122）、合并（123）、可再修改（124）、修正优先级（112）、保护机制（113）、审计（114）、编辑器 UI（111）、异常 UI（118–120）、批量修正（140）、撤销恢复（141）、防误操作（150）、时长输入（151）、导入兼容（152）、统计架构（125）、时间区间（126 + 126.1）、分类扩展（127）、日历（128）、时间线（129）、备注（130）、重算触发（142）、缓存一致性（143）、数据版本化（144）、质量提醒与防骚扰（146–148）、准确数据优先（149）、最终原则（160–162）、P0-7/P1-11/P1-13/P1-14、190.5 备份闭包、145 提醒分类、102 验收 | 高：直播历史与统计的完整规则与 UI |
| 08 导出/备份/恢复/迁移 | 12700–13918 | 配置导入导出（19）、配置 vs 历史备份（55.1/55.2）、恢复模式（55.3/55.3.1/55.5）、HTML 报告（131–138）、两类导出区分（153）、导出快照与原子导出（174 P0-9）、校验和（184）、MigrationVerifier（185）、导入预检隔离（191）、高级配置导入导出（226）、容量与清理（56）、**249 全篇归一化（249.1–249.13）** | 极高：249.x 是文档中效力最高的一层最终规则（含通知语义、占位文案、容量、导出命名、互斥、禁令、编辑 CAS） |
| 09 主播管理与账号 | 13919–14097 | 添加主播与完整性（54）、登录与关注导入（18.1–18.4）、账号边界（59） | 高：关注导入只更新 Bilibili-owned 字段、软删除 UID 需用户选择 |
| 10 设置/配置版本/高级配置 | 14098–14313 | 配置项模型与 `AppSettings` 默认值（31）、配置版本管理与 ConfigMigrator（32）、跳转策略升级（205.1–205.4 统一导航接口）、设置页「数据可靠性」分区（99） | 高：设置项默认值与 RoomNavigator 唯一签名 |
| 11 健康中心/日志/诊断/安全 | 14314–15218 | 健康检查中心（57，10+1 项）、安全与隐私（60）、日志体系（64）、用户可见健康（91）、故障审计（94）、**宁可说不知道（98）**、昨日诊断（100）、日志总体设计（206）、两类日志（207–209）、级别（210）、结构化（211）、脱敏（212）、保留（213）、数量上限（214）、日志中心（215–217）、开发者模式（218）、诊断包（219）、恢复提醒（88）、日志生命周期（182）、批量幂等（186）、恢复报告（187）、严重等级（188）、**194.5 诊断历史生命周期**、**188.5 OverallHealth 矩阵**、数据质量中心（189）、设置串行化（194） | 高：健康/诊断/日志的完整规格；194.5 与 188.5 **未带历史标记**，是当前契约候选 |
| 12 UI/ViewModel/DI | 15219–15317 | 仅 3 条建议：HomeViewModel/HomeUiState（27）、Compose 纯函数与「UI 不得直连 DB/API/通知/Service」（28）、6 个 Hilt Module（29） | 低（**无页面清单、无导航实现**；信息架构须回看 4887–4911） |
| 13 测试/验收/开发顺序/AI 协议 | 15318–16230 | 测试方案（33，含 18 条 P0/P1 测试组）、开发顺序五阶段（34）、AI 47 条规则（35）、提示词（36）、可靠性指标（66）、测试矩阵 v2（67）、可靠性测试新增（101）、可靠性优先 20 条（105）、新增 UseCase 14 个（155）、新增 Repository 接口（156）、直播历史 20 条（157）、验收场景（158）、P0/P1 验收总表（195）、推荐实现顺序六优先级（196）、P0/P1 强制规则（197）、高级设置/日志/跳转规则（227–229）、稳定性验收（230）、深层稳定性验收 21 条（250）、参考资料（37） | 高：开发顺序与全部验收清单 |
| 14 产品路线与未来扩展 | 16231–16372 | MVP 12 项 / V1.5 10 项 / V2.0 9 项 / V3.0 3 项（68）、后续功能池 A 智能通知/B 自动化/C 多端/D 云端（69）、v2.0 功能优先级补充 P1 14 + P2 5（231） | 中：明确「直播历史/统计/备份恢复属于当前 v2.0，不是未来功能」（15461、16235） |
| 15 全局一致性闭环索引 | 16373–16414 | 权威性声明 4 条 + C1–C25 闭环表（每行给出 0.6 权威落点） | 高：可当作「提交前自检清单」；**不是规则来源**，与 0.6 冲突时以 0.6 为准 |
| 附录 A 原始设计演进 | 16416–16809 | 开发协议 v2（70）、v2.0 变更摘要（75）、四种状态概念不得混成一个 Boolean（103）、新增 P0 16 项（104）、核心架构图（106）、功能清单 26 项（159）、P0/P1/P2（163）、最终补充总结（164）、稳定性最终目标（198）、三种用户视图与最终原则（232） | 中：**明确「附录不得作为实现优先级依据」**（16418）；但含产品理念原文（16715、16803） |
| 附录 B Android 约束核验来源 | 16811–16818 | 4 条 Android Developers 官方资料条目名（无 URL） | 低（仅溯源；URL 见 16205–16227） |
| §100 v2.3 审查清单 | 16821–16882 | 100.1 模型唯一性、100.2 数据库唯一性、100.3 事务与并发、100.4 业务通知、100.5 交付门槛 | 低：**已被 0.6.43/0.6.46 完全取代，不得作为门槛**（16823–16825） |

补充：文末「三个用户视图互不干扰」（16789–16799）与最终原则「**可配置、可观察、可恢复、可诊断，但不能因为追求可配置而牺牲安全性和数据真实性**」（16803）；稳定性最终目标要求软件能回答 6 问：我们不知道 / 为什么不知道 / 从什么时候开始不知道 / 哪些数据仍然可信 / 恢复以后做了什么 / 哪些数据需要你确认（16750–16755）。

---

## 7. 文档内部矛盾、空白与风险清单（供主 agent 决策，均已核对）

| # | 问题 | 证据行号 | 影响与建议 |
|---|---|---|---|
| 1 | **总行数勘误**：任务描述 12843 行，实测 16882 行 | 实测 | 后续按 16882 行检索 |
| 2 | **@Entity 数量不一致**：0.6.43 写「全部 44 个 @Entity」，实列 42 个；DDL 也是 42 张表 | 3848 vs 443–471 | 以 42 为准 |
| 3 | **4 个实体只有 DDL 没有 Kotlin 声明**：`MonitoringConfigEntity / MonitoringConfigRevisionEntity / SourceDataRevisionEntity / SystemRuntimeLockEntity` 列在 `@Database.entities` 与 DDL 中，但全文无 `data class` 定义 | 458–459、2871–2956 | 违反 0.6.43「每个业务表映射恰好一个 @Entity」的可核验性；需补齐实体 |
| 4 | **健康状态枚举三套并存**：0.6.2 唯一枚举为 `MonitorHealthStatus{HEALTHY,DEGRADED,RECOVERING,BLOCKED,STOPPED}`；`health_event` 的 CHECK 也是这 5 值；但 0.6.47.2（「已定稿」）与多处正文使用 `USER_ACTION_REQUIRED / SYSTEM_RESTRICTED`；历史章节 9269–9278 的 `MonitoringHealth` 是第三套 8 值（含 PAUSED/ERROR） | 197–203 vs 1532–1533、9637、13494、15155 vs 9269–9278、3420–3421 | 直接冲突：`0.6.47.2` 的映射表无法用唯一枚举表达。实现前必须先裁决（建议扩 0.6.2 枚举并同步 CHECK） |
| 5 | **`OverallHealth` 无枚举定义**，但 188.5 与 249.10.7 都在使用其取值 | 15147–15168、13879–13892；`enum class OverallHealth` 全文 0 命中 | 需补定义 |
| 6 | **日志相关四套口径**：`LogSeverity{DEBUG..CRITICAL}`(14717)、182 的 `DEBUG/INFO/WARNING/ERROR/AUDIT`(15031)、188 的 `INFO/WARNING/ERROR/CRITICAL`(15112)、0.6.31 的 `monitoring_error_log/application_error_log` 实际只有 `errorCode: AppError`（无 severity 列） | 14717–14723、15031–15037、15112–15116、3161–3180、3385–3404 | 历史模型与最终 DDL 不一致，须以 0.6 为准；`severity/message/stackTrace/appVersion/deviceInfo/retryCount/resolved` 等列在最终表中不存在 |
| 7 | **`correlationId` 无处落库**：日志模型无该字段（0.6 的两张日志表也没有），但 LiveEvent 有、规则 228-8 与 224 要求用它串联 | 10349 vs 3161–3180、16093、10791–10818 | 需明确它是列还是仅 detail JSON 内字段 |
| 8 | **`MonitoringErrorCategory` 全文仅 1 处引用、无定义** | 14618 | 须删除或补定义 |
| 9 | **`AppSettings` 默认值与最终种子冲突**：历史默认 `monitorMode=REALTIME`、`monitorIntervalSeconds=60`；0.6.4.5 种子为 `POWER_SAVING / 300`，且「引导完成前不得进入 REALTIME」 | 14122–14123 vs 820–824、844–846 | 以 0.6.4.5 为准；历史默认仅作 UI 文案参考 |
| 10 | **免打扰（静默时段）无最终契约**：仅存在于历史章节与 `AppSettings`/`streamer_monitor_policy.overrideQuietHours`；0.6 没有免打扰表、字段（除 policy 的 override 列）、状态机或与 Outbox/聚合的交互规则 | 4697–4705、14130–14132、2995、5201 | 若必须实现「静默时段」，需要在 0.6 补契约（否则会与「关播通知资格」「批量聚合」产生未定义交互） |
| 11 | **可靠性指标无任何具体数值 SLA**（无可用率百分比、无延迟 p95/毫秒阈值）；仅有指标名与示例值（如「监控可靠度 98.7%」） | 15616–15639、7867、11237、13066、15187 | 若要硬性 SLA，属文档空白 |
| 12 | **多套 P0/P1/P2 并存、无单一权威** | 40(4429–4478)、104(16531–16554)、159(16610–16640)、163(16644–16681)、195(15930–15978)、196(15982–16032)、231(16340–16369) | 交付判据应取 0.6.43/0.6.46/0.6.28；历史表仅作范围参考 |
| 13 | **`MonitoringGapStreamer` 字段两套命名且未声明唯一来源** | 7388–7395 vs 7459–7467 | 以 0.6.9 的 DDL（`gapId/streamerStableId/affectedStartAt/affectedEndAt/reason/lastReliableAt/recoveredAt`）为准 |
| 14 | **数据可信度历史定义三套**（85 四值 / 161 五值 / 149 三值） | 11263–11266、12421–12434、12368 | 以 0.6.2 的五值 + `StatisticsInclusionState` 为准 |
| 15 | **同一表在两段 DDL 中重复定义**：`notification_id_registry`(1263,2828)、`monitoring_config`(1363,2871)、`source_data_revision`(1397,2928)、`system_runtime_lock`(1403,2934) | 同左 | 文档声明「逐字一致」，实现时须人工核对一致性 |
| 16 | **章节编号与物理顺序错乱**：0.6.47 插在 0.6.14/0.6.15 之间；0.6.30–0.6.32 排在 0.6.46 之后；249.10.0 排在 249.10 之前；55.3.1→55.5（无 55.4）；19.1–19.3 与父节同级；235.3/235.4 各出现两次；§243 标题与 `---` 同行 | 1456、2957、13629、12882/12947、12702/12706、10278/10338、11126 | 按「小节编号递增」阅读，不按物理位置（3968–3970 亦有此说明） |
| 17 | **249.x 标题带历史标记但内容是最终规则**：`## 原规范 249` 标题含「【历史说明：不可直接实现】」，而其 249.1–249.13 被 249.1 定义为最高效力 | 13422 vs 13426–13438 | **不能整章丢弃**；249 的容量低水位、通知语义、占位文案、导出命名、禁令、编辑 CAS 都是现行规则 |
| 18 | **容量低水位自述版本不符**：「v2.0 默认低水位定义为 90% × N」出现在 v2.4 文档中 | 13492 | 需确认现行值 |
| 19 | **可靠性数据版本号 7 个易混**：`roomSchemaVersion / backupSchemaVersion / formatVersion / sourceDataVersion / statisticsVersion / snapshotId / backupId` | 12229–12259、0.6.19 | 三者（容器/业务字段/DB 结构）在恢复时的判定分支不同 |
| 20 | **导出「方案 A」已废弃但历史上多次出现**，别名表也登记为禁止实现 | 2048–2051、3826 | 代码中不得保留二选一开关 |
| 21 | **`isCompleted` / `isLocked` / `resolved` / `isIncludedInStatistics` 均被降级或禁止**为事实字段 | 6082、11589、7453、11258 | 不得新增这些列 |
| 22 | **§100 交付门槛已作废**、附录 A/B 非规范、§15 非规则来源 | 16823–16825、16418、16375–16384 | 引用时须标注效力层级 |
| 23 | 原文瑕疵：L13518 行尾游离引号；原规范 36 与 157 编号跳号；原规范 158 标题层级不一致；附录 B 未给 URL；`Option`/术语「轮播」与 `ROUND` 混用 | 13518、15564/15567、15847、16813–16816 | 不影响规则效力，引用时注意 |

---

## 附录：章节—行号速查表（全文 16 章 + 3 附录）

| 行号 | 章节 |
|---|---|
| 1–13 | 标题、版本信息（v2.4 / 2026-09-13 / Android 12+ / 项目定位） |
| 14–115 | 0 章：规范使用说明、0.1 唯一事实来源、0.2 通知最终规则、0.3 备份恢复跨设备、0.4 账号边界、0.5 Android 后台核验 |
| 115–1591 | 0.6 最终实现契约：0.6.1 枚举 → 0.6.4.5 单例行 → 0.6.5–0.6.13 → **0.6.47 引导三项定稿（1456–1591）** → 0.6.15 CAS DAO → 0.6.16 快照 → 0.6.16.1 落库规则 → 0.6.17 统计缓存键 → 0.6.18 导出快照 → 0.6.19 备份 DTO → 0.6.20 恢复矩阵 → 0.6.21 修正 → 0.6.22 软/硬删除 → 0.6.23 ROUND → 0.6.24 事件时间 → 0.6.24.1 StreamerEntity → 0.6.25 批量响应 → 0.6.26 事务边界 → 0.6.27 FGS/bootId → 0.6.28 测试门槛 |
| 2435–2956 | 0.6.29 最终 Room/SQLite 结构总基线（核心 DDL，42 表之一部） |
| 2957–3442 | 0.6.30 外围模块契约总览、0.6.31 外围 Entity DTO、0.6.32 外围完整 DDL |
| 3443–3973 | 0.6.33 三个状态机（Recovery/Restore/Export）→ 0.6.34 投递崩溃恢复 → 0.6.35 CAS SQL 模板 → 0.6.36 并发约束 → 0.6.37 canonicalJson → 0.6.38 临时快照表 → 0.6.39 BackupScope → 0.6.40 冲突决策 → 0.6.41 UI/搜索/标签引用 → 0.6.42 引用规则与别名表 → **0.6.43 生成前门槛 → 0.6.44 DDL 完整性 → 0.6.45 外围要求 → 0.6.46 生成完成前门槛** |
| 3974–4422 | 00 章 总则：文档目的、v2.0 目标与边界、核心功能、技术栈、总体架构、工程目录 |
| 4423–5274 | 01 章 产品边界：功能优先级矩阵 P0/P1/P2、当前功能总表、验收原则、功能总结、产品理念、直播历史与增强、主界面、详情页、设置页、信息架构、UI/UX、搜索、标签、分组、筛选、跳转、主播级策略、重点主播、搜索排序升级、批量操作 |
| 5275–7161 | 02 章 身份/领域模型/数据库：核心模型、直播状态、连续可靠监控区间、标签分组、Room 设计、LiveSession 模型、三项时间字段、双层数据模型、DataConfidence、可靠性模型、状态一致性、状态机、检查与资料刷新解耦、P0-5/P0-11、246 第二轮、248 第四轮、244 本轮修正、P0-13/P0-4/P0-6/P0-8 |
| 7162–8447 | 03 章 直播状态/会话/故障盲区：9 算法、166 P0-1、79 Gap、80 网络异常、87 补偿检查、92/93 新鲜度、93.1 双时钟、97 启动恢复、168 P0-3、177 P0-12、181 P1-2、65/81/82/96/179/76/165、245 第一轮、247 第三轮 |
| 8448–9097 | 04 章 数据访问/请求协调/网络：8 数据访问、24 网络策略、23 错误处理、46 批次并发熔断、167 P0-2、180 P1-1、200 Endpoint、201 冷却轮询、202 高级请求、203 护栏、204 恢复默认 |
| 9098–9706 | 05 章 监控控制器/调度/后台：10 Engine、41 Controller/Scheduler、41.1 Tick 合并、42 RuntimeState、42.1 租约、58 后台策略 v2、11 后台监控设计、30 Manifest、237 常驻通知、238 无障碍、239 运行模式模型、199 高级设置 |
| 9707–11163 | 06 章 通知/聚合/Outbox/历史：12 通知系统、26 Outbox、47 Outbox v2 + 47.1 注册表、233–235 批量聚合、89 异常等级、90 去重冷却、175 P0-10、183 P1-4、48 通知历史、220–225 日志关系、236 跳转、240 内容最终规范、241 验收、242 强制规则、243 总结 |
| 11164–12699 | 07 章 直播历史/人工修正/统计：83–85、115–117、121–124、112–114、111、118–120、139–141、150–152、125–130、142–144、146–149、160–162、172 P0-7、190 P1-11 + 190.5、192 P1-13、193 P1-14、145、102 |
| 12700–13918 | 08 章 导出/备份/恢复/迁移：19、55.1–55.5、131–138、153、174 P0-9、184 P1-5、185 P1-6、191 P1-12、226、56 容量、**249 归一化（249.1–249.13）** |
| 13919–14097 | 09 章 主播管理/账号：54 添加主播、18.1–18.4 登录与关注导入、59 账号边界 |
| 14098–14313 | 10 章 设置：31 配置项模型、32 配置版本、205 跳转策略（205.1–205.4）、99 数据可靠性设置 |
| 14314–15218 | 11 章 健康/日志/诊断/安全：57 健康中心、60 安全隐私、64 日志体系、91 健康状态、94 审计、98 原则、100 昨日诊断、206–219 日志系统、88 恢复提醒、182 P1-3、186 P1-7、187 P1-8、188 P1-9、194.5 诊断生命周期、188.5 OverallHealth、189 P1-10、194 P1-15 |
| 15219–15317 | 12 章 UI/ViewModel/DI（仅 27/28/29 三条） |
| 15318–16230 | 13 章 测试/验收/开发顺序/AI 协议：33–37、66、67、101、105、155–158、195–197、227–230、250 |
| 16231–16372 | 14 章 产品路线与未来扩展：68、69、231 |
| 16373–16415 | 15 章 v2.4 全局一致性闭环索引（C1–C25，自检用） |
| 16416–16810 | 附录 A：70、75、103、104、106、159、163、164、198、232 |
| 16811–16820 | 附录 B：Android 官方约束核验来源（4 条） |
| 16821–16882 | §100 v2.3 实现闭环审查清单（**已作废**，100.1–100.5） |

---

### 报告结束语（给主 agent 的执行建议）

1. **先读 0.6（1–3973）再读任何其他章节**；把 0.6.42.1 别名表当作「替换字典」，把 0.6.43/0.6.46 当作 pre-flight/post-flight 检查清单，把 0.6.28 当作测试套件的最低要求。
2. **产品范围**以第 01 章功能总表 + 02–11 章细节为准，但**模型/字段/状态机一律以 0.6 为准**；凡历史章节与 0.6 冲突，按 0.6 实现（27 行明文规则）。
3. **最易踩坑的三处**：(a) 关播通知资格必须由「当前连续可靠监控区间」判定，不能用「整场无异常」；(b) 首次 UNKNOWN→LIVE 不产生 START 通知但确实创建 PROVISIONAL OPEN Session；(c) 所有关键写入必须带 `observationSequence + streamerMonitorGeneration + monitorGeneration + fencingToken` 的原子 CAS。
4. **动工前必须先裁决 §7 的 4 个硬冲突**（@Entity 数量与缺失实体、MonitorHealthStatus 枚举、OverallHealth 定义、日志模型/级别），否则生成代码会自相矛盾。
5. 如需硬性 SLA/静默时段/免打扰的完整规格，文档中**不存在**，须补充设计而不是从历史章节取值。
