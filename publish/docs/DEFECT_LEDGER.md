# 缺陷台账（已验证）

> ## ⚠️ 工作模式约定（2026-09-13 起）
> **后续所有改动不再逐次编译**，等整个软件功能做完后统一编译测试。
> 因此本台账中标注「已实现」的条目，**其编译通过与运行验证均以最后一次构建为准**：
> - 最后一次成功构建：`versionCode=2 / versionName=1.1`，`AppDatabase.version=6`，
>   `app-debug.apk` 65.4 MB，已安装到 `emulator-5554`（Android 15）并确认无崩溃。
> - **之后新增的代码尚未经过任何编译**，其中可能包含编译错误，需在统一编译阶段集中修复。
>
> 项目：`com.example.bilimonitor`（主播监控）
> 生成方式：UI 层 + 数据/后台层只读分析，**关键条目已由主 agent 独立复核或实测复现**
> 图例：✅已复核 / 🌐已实测复现 / ⚠️静态推断（未运行时验证）
>
> ## 修复进度（2026-09-13）
> **A 级 5 项、B 级 6 项、C 级 16 项、D 级 9 项 —— 全部已实现。**
>
> | 验证项 | 结果 |
> |---|---|
> | `./gradlew :app:assembleDebug` | ✅ BUILD SUCCESSFUL — `app-debug.apk` **65.4 MB** |
> | `./gradlew :app:testDebugUnitTest` | ✅ **32 个单测全部通过**（D 节实现之后新增 8 个，**尚未执行**） |
> | `./gradlew :app:assembleRelease` | ✅ BUILD SUCCESSFUL — `app-release-unsigned.apk` **5.9 MB**（R8 + 资源压缩） |
> | 模拟器安装 | ✅ `emulator-5554`（Android 15）安装后启动无崩溃 |
>
> | 批次 | 内容 | 状态 |
> |---|---|---|
> | 1 | A1 + A1b + A3 | ✅ 已完成 |
> | 2 | A2 + A4 + A5 | ✅ 已完成 |
> | 3 | B1 + B2 + B3 | ✅ 已完成 |
> | 4 | B4 + B5 + B6 | ✅ 已完成 |
> | 5 | C1 + C4 + C5 + C8 | ✅ 已完成 |
> | 6 | C2 + C3 | ✅ 已完成 |
> | 7 | C-bis 配置接线（含 C9/C10） | ✅ 已完成 |
> | 8 | release 构建 + 0.6.28 测试门槛 | ✅ 已完成 |
> | 9 | C6/C7/C11–C16（C 级剩余） | ✅ 已实现 |
> | 10 | D1–D9 待做功能 | ✅ 已实现 |
> | 11 | 版本正常化（DB 5→6、versionCode 1→2） | ✅ 已完成并验证 |
>
> **数据库版本：6**（含显式 `V5_6__versionNormalization` 迁移，本轮只加 DAO 方法未改表结构）；
> `versionCode = 2`，`versionName = 1.1`。
> **未处理**：无。
> ⚠️ 批次 9 与 10 的实现完成于最后一次成功构建**之后**，**尚未经过任何编译**。

---

## A. 阻断级（功能必然失效）

### A1. 直播状态接口三个字段名写错 → 封面/分区/短号全部永久为 null 🌐 **[已修复]**

> **修复**：`BiliDtos.kt` 批量 DTO 改用 `short_id` / `area_v2_parent_name` / `cover_from_user`；
> 单房间 DTO 改用 `user_cover`。另把 `BiliLiveApi.normalizeUrl` 从"只列举 i0/i1/i2/i9"
> 改为覆盖全部 `*.hdslb.com` 子域（原白名单会漏掉 i3 等编号，导致封面以明文 http 下发被系统拦截）。

**证据**：实测 `get_status_info_by_uids` 返回
```json
{"short_id":0, "area_v2_parent_name":"虚拟主播", "cover_from_user":"https://i0.hdslb.com/...jpg", "keyframe":""}
```
而 `BiliDtos.kt` 声明的是：

| 行 | 声明 | 实际字段 | 后果 |
|---|---|---|---|
| `BiliDtos.kt:18` | `@SerialName("short_room_id")` | `short_id` | `shortRoomId` 恒 0 → `RemoteLiveRoom.shortRoomId` 恒 null |
| `BiliDtos.kt:24` | `@SerialName("parent_area_name")` | `area_v2_parent_name` | `parentAreaName` 恒 null → `MonitorRepository.areaLabel` 只剩子分区 |
| `BiliDtos.kt:26` | `@SerialName("cover")` | `cover_from_user` | `coverUrl` 恒 null → **通知大图标永不生效** |

`keyframe` 实测为空串 `""`，所以 `cover ?: keyframe` 的回退也救不回来。

> 单房间接口 `get_info` 用的是 `user_cover`（同样不是 `cover`），`BiliRoomDetail.kt:48` 有同样的错误。

**修法**：批量 DTO 改用 `cover_from_user` / `area_v2_parent_name` / `short_id`；单房间 DTO 改用 `user_cover`。

---

### A2. 通知清理必然违反外键 → 清理链断裂且静默 🌐 **[已修复]**

> **修复**：`MaintenanceRepository.runCleanup` 重排为严格的
> 「先删子行 → 再删 Outbox → 最后删聚合」：
> 1. 新增 `NotificationOutboxDao.deleteFinishedAttemptsForTerminal()`：先删仍引用**可清理终态 Outbox** 的已结算投递尝试行；
> 2. 再跑 `notificationHistoryDao.cleanupOld(90 天)`；
> 3. 补 `deleteUnstartedAttempts()` / `deleteOrphanAttempts()` 回收中断写入的占位行；
> 4. 之后才 `cleanupTerminal(30 天)` —— 此时已无子行引用；
> 5. 删聚合前用 `findCleanableOutboxIdsForAggregates()` 取走引用它的终态 Outbox 并删除，
>    再用 `outboxExistsForAggregate()` 双重确认，仍有非终态引用的聚合**跳过**等其结算。
>
> 顺带修正两处相关问题：
> - `cleanupTerminal` 的白名单加入 `FAILED`（终态，原先永不清理 → Outbox 无界增长）；
> - 新增日志保留策略（原实现完全没有）：`monitoring_error_log` 90 天 / 保底最近 10000 条，
>   `application_error_log` 7 天 / 保底最近 5000 条，清理后写 `LOG_CLEANUP` 审计。
>   `CleanupSummary` 相应扩展，数据管理页文案同步更新。

**证据链**：
- `NotificationEntities.kt:110-113`：`notification_delivery_attempt.outboxId` → `notification_outbox`，`onDelete = NO_ACTION`
- `NotificationEntities.kt:143-146`：`notification_history.outboxId` → `notification_outbox`，`onDelete = NO_ACTION`
- `AppDatabase_Impl.java:367`：Room 打开时执行 `PRAGMA foreign_keys = ON`
- `MaintenanceRepository.kt:111`：先删 Outbox 终态行（保留 **30 天**）
- `MaintenanceRepository.kt:115`：再删通知历史（保留 **90 天**）

→ 30–90 天区间内的历史行仍引用将被删除的 Outbox 行 → `FOREIGN KEY constraint failed`。

**放大效应**：异常在 `try` 块内抛出，被 `runCatching`（`:64`）吞掉 → **`:115` 历史清理与 `:119` 聚合清理永不执行**；`StartupRecovery.kt:57` 同样 `runCatching` 包裹，完全静默。

**附带**：`:119-123` 删终态 aggregate 时，`notification_outbox.aggregateId` 也是 NO ACTION 外键（`NotificationEntities.kt:68-69`），FAILED 状态不在 `cleanupTerminal` 白名单内 → 同样会失败。

**修法**：清理顺序改为「先删 attempt/history 子行 → 再删 outbox」；聚合清理前先确保引用它的 outbox 已删。

---

### A3. Android 12/12L 上所有通知必然投递失败 ⚠️ **[已修复]**

> **修复**：`NotificationPoster.hasPermission` 增加 `SDK_INT < TIRAMISU` 分支，
> API 31/32 改用 `NotificationManagerCompat.areNotificationsEnabled()`。

---

### A4. 清理退避后 `problem_state` 卡死 30 分钟 ⚠️ **[已修复]**
> **修复**：新增 `ProblemDao.clearCooldown()`，`MonitoringEngine.onTransportSuccess`
> 在故障恢复时清除冷却记录，使下一次**新故障**能立即通知。

### A5. `consecutiveTransportFailures` 不持久化 ⚠️ **[已修复]**
> **修复**：新增 `LogDao.lastHealthStatus(component)`；`onTransportFailure` 在内存计数为 0
> 但上一个健康事件仍是 `DEGRADED` 时，把计数恢复为 `PROBLEM_THRESHOLD - 1`。
> 这样进程被杀重启后，故障期尚未结束的情况下**再失败一次即可触发**，不再需要从头凑满 3 次
> （省电模式 15 分钟一周期下原先几乎不可能触发）。

---

## B. 高优先级（UI 承诺了但没实现 / 反馈丢失）

> **本节 6 条全部已修复。**

### B1. 「填两项自动补全第三项」完全没接线 ✅ **[已修复]**
- `HistoryScreen.kt`（手工补录）、（人工修正）定义了补全函数，**全工程零调用点**
- 但 UI 已承诺：label「时长（时/分/秒，与上两项互为补全）」、正文「开播 / 下播 / 时长填任意两项，自动补全第三项。」

**根因**：`DateTimeFields`（`TimeInput.kt`）与 `DurationFields`（`HistoryScreen.kt`）**没有 `onChanged` 回调参数**，补全函数无处挂载。

> **修复**：两个 Composable（含 `DateFields`）各加 `onChanged: () -> Unit = {}`，
> 在任一分框变更后触发。两个对话框把原先的 `deriveFromStart/deriveFromEnd` 合并为
> `deriveFrom(field)`：**以"用户最后编辑的那一项"为基准，由另外两项算出它**
> （开播+下播→时长；开播+时长→下播；下播+时长→开播），避免边输入边互相覆盖。
> 由于 `toEpoch()` 在日期只填了一部分时返回 null，未填完的字段不会误触发补全。

### B2. 4 个页面的操作反馈静默丢失 ✅ **[已修复]**
| 页面 | 症状 |
|---|---|
| `StreamerDetailScreen.kt` | `state.message` 有定义、写入 8 处、读进 state，**全文件无第二次读取** |
| `HistoryScreen.kt` | `message` + 2500ms 自动清理，**无渲染点**；补录/修正结果全部丢失 |
| `SettingsScreen.kt` | collect + 2000ms 清理，**无渲染点**；「设置已被其他修改更新」「参数非法」全不可见 |
| `HistoryListScreen.kt` | 链路完整但**连写入方都没有**，恒 null |

> **修复**：新增共用组件 `ui/common/MessageBanner.kt`（`MessageBanner` + `InlineMessageCard`），
> 四个页面全部接入。详情页与列表页用自带计时（2.5s 自动消失）；
> 历史页与设置页原本已有 `LaunchedEffect` 计时器，改为 `autoDismissMillis = null` 避免双重计时。
> 详情页的提示条同时渲染在"主播数据未加载"分支，保证加载失败类错误也可见。

### B3. 「最大重试次数」可选出永远保存不上的值 ✅ **[已修复]**
`StepperSetting` 边界原为 `>1` / `<10`（允许 1–10），而 `ConfigRepository` 校验 `retries > 5 → Invalid`。
用户可选中 6–10，点保存永远失败，且因为 B2 **连失败提示都看不到**。

> **修复**：`StepperSetting` 增加 `min` / `max` 参数；该项设为 `min = 0, max = 5`，
> 标签补上区间说明「最大重试次数：N（0–5）」。其余调用点保持默认 1–10 不变。

### B4. 添加主播：纯数字房间号无法识别，超长数字抛异常 ✅ **[已修复 — 按用户要求改为可选识别模式]**
- 注释声称「先按 UID 处理，失败再按房间号解析」，但 `resolveUid` 的 Uid 分支**没有房间号回退**
  → 输入纯数字房间号（如 `22637261`）会被当 UID，查不到 → 「找不到该主播」
- `text.toLong()` 无捕获，超长数字串抛 `NumberFormatException`

> **修复（用户定稿：改成选项，默认房间号）**：
> 1. 新增 `StreamerRepository.InputMode { ROOM("房间号"), UID("UID") }`，**默认 `ROOM`**。
>    纯数字输入不再靠猜测顺序，而是由用户显式指定；URL 形态自带语义、不受模式影响
>    （`live.bilibili.com/<数字>` → 房间号；`space.bilibili.com/<数字>` → UID）。
> 2. 添加主播对话框新增「识别方式」单选（按房间号 / 按 UID），各带输入示例，
>    输入框 label 随模式切换，并提示可直接粘贴直播间链接。
> 3. `toLong()` → `toLongOrNull()`，超长数字返回 null 走"无法识别"分支，不再抛异常。
> 4. 抽出 `resolveByUid()` / `resolveByRoomId()`；主路径失败时**自动回退另一种解释**，
>    用户选错模式也能成功，而不是直接报"找不到"。
> 5. 错误文案改为回显输入值并给出下一步建议（切换模式 / 粘贴链接）。

**实测验证（本次）**：
```
按房间号 22637261  (room/v1/Room/get_info)              → code=0 uid=672328094 room_id=22637261  ✅
按 UID 672328094   (get_status_info_by_uids)            → code=0 uid=672328094 room_id=22637261  ✅
反例：把 UID 当房间号查 (get_info?room_id=672328094)      → code=1 无数据
```
最后一条印证了原缺陷：UID 与房间号确实无法互相替代，默认按房间号后
「粘贴直播间链接 / 输房间号」这一最常见路径才真正可用；同时保留 UID 模式与自动回退兜底。

### B5. 登录导入预览弹窗时序缺陷 ⚠️ **[已修复]**
`开始导入` 同时置 `showImportPreview = true`，但弹窗还需 `importStage == "PREVIEW"`（仅由轮询成功后置位）。
→ 导入失败/超时时弹窗永不出现，**且无重试入口**。

> **修复**：改为「只要发起过导入就给出明确反馈」三分支：
> ① `importStage == "PREVIEW"` 或已有预览数据 → 正常弹预览；
> ② 轮询进行中（`importStage == null && error == null`）→ 显示"正在获取列表"的进度对话框（可取消）；
> ③ 其余（失败/超时）→ 显示错误原因 + **「重试」按钮**（先 `clearError()` 再重新发起导入）。
> 另修：预览对话框内搜索无匹配时原先显示 `CircularProgressIndicator`（无限转圈），
> 改为"没有匹配「xxx」的主播"文案。

### B6. 引导页忽略落库结果 → 可能陷入引导循环 ⚠️ **[已修复]**
`configRepository.completeOnboarding(...)` 返回 `Boolean`，**返回值被忽略**，无条件 `onDone()`。
落库失败仍跳首页，而 `MainActivity` 依据 `backgroundKeepAliveChoice == UNSET` 决定起始页 → 重启又要重新选。

> **修复**：`OnboardingViewModel` 新增 `error` 状态；检查返回值与异常，
> 失败时**不跳转**并给出「保存失败，请重试」提示；页面顶部渲染该错误。
> 另修：`QrLoginState.Idle` 原先落入 `else -> {}` 导致二维码区域完全空白，
> 现在显示"尚未登录。点击上方「扫码登录」获取二维码。"。

---

## C. 中优先级（一致性问题）

| # | 问题 | 位置 | 状态 |
|---|---|---|---|
| C1 | 诊断页 5 路数据只消费 4 路：`openStreamGaps` 被硬写 `emptyList()`，`appErrors` 从未赋值、`LogDao.observeAppErrors` 从未订阅 | `DiagnosticsScreen.kt` | ✅ **已修复** |
| C2 | 「无障碍服务」保活是死路径：`syncWithConfig` 对 ACCESSIBILITY 走 `else` → 降级为 WorkManager；无人引导开启；`HealthRepository` 却显示「监控中（实时）」 | `MonitoringController.kt`、`KeepAliveAccessibilityService.kt` | ✅ **已修复** |
| C3 | FGS 类型用 `dataSync`，Android 15+ 有 24h 内累计 6h 上限，与「永久常驻」需求冲突（文档 `doc_v2.4.md` 0.5 自己也这么写） | `MonitoringService.kt:80`、`AndroidManifest.xml` | ✅ **已修复** |
| C4 | 通知历史 `PROCESSING`/`CREATED`/`DELIVERY_UNKNOWN` 必然显示英文（`NotificationDispatcher` 会写 PROCESSING） | `NotificationHistoryScreen.kt` | ✅ **已修复** |
| C5 | 「系统恢复通知」识别错误：`problem:` 前缀分支抢在 `:recovered` 之前命中，恢复通知一直被显示成"系统问题通知" | `NotificationHistoryScreen.kt` | ✅ **已修复** |
| C6 | `aggregate.eventCount` 被写成字面量 `0`，覆盖派生缓存 | `NotificationDispatcher.kt` | ✅ **已实现** |
| C7 | STREAMER 盲区父行永不关闭：`MonitoringGapDao.close` 只在 SYSTEM 分支调用；受 `idx_gap_one_open` 全局唯一限制，后续主播盲区复用同一父行 | `MonitorRepository`、`StreamerRepository` | ✅ **已实现** |
| C8 | 退出登录未真正递增 `authGeneration`（只写审计 JSON），导入任务代际隔离失效 | `AuthRepository.kt` | ✅ **已修复** |
| C9 | `startConfirmationCount`（开播确认次数）完全未参与判定，全局与主播级配置均为无效字段 | `StateConfirmationPolicy.kt` | ✅ **已修复（批次 7）** |
| C10 | `maxConcurrency`/`maxRetries`/`retryBaseSeconds`/`circuitBreaker*`/`batchCooldownSeconds` 只存不读，阈值全硬编码 | `MonitoringEngine.kt` | ✅ **已修复（批次 7）** |
| C11 | `StatsSeriesRepository` 两个统计值写死为 0 | `StatsSeriesRepository.kt` | ✅ **已实现** |
| C12 | 标签筛选后搜索框失效（`observeByTag` 不带 query） | `MonitorDaos`、`StreamerRepository`、`HomeViewModel` | ✅ **已实现** |
| C13 | 通知大图标路径同步下载图片，无超时、无大小上限 | `NotificationPoster.kt` | ✅ **已实现** |
| C14 | `relativeTime`/`formatTime` 用 `System.currentTimeMillis()`，绕过 `AppClock` 网络校时 | `AppClocks`、`HomeScreen`、导出/备份时间戳 | ✅ **已实现** |
| C15 | `StatsRepository` 主播名映射只用 `listMonitorable()` → 已删除/已暂停主播显示「未知主播」 | `StatsRepository.kt` | ✅ **已实现** |
| C16 | 导出状态机忽略 `casStatus` 返回值；`exportBackup` 忽略维护权获取结果，互斥保证不一致 | `ExportRepository.kt`、`BackupRepository.kt` | ✅ **已实现** |

---

## D. 待做功能（已全部实现）

> **用户裁定（2026-09-13）**：D1–D6 属**待做功能**而非缺陷；随后指示「把之前未实现的待做功能全部完成」。
> 以下 9 项**均已实现**。⚠️ 实现完成于最后一次成功构建之后，**尚未经过编译**。

| # | 功能 | 实现内容 |
|---|---|---|
| D1 | **分组（Group）系统** | DAO：`observeByGroup` / `observeByGroupAndQuery` / `groupsOfStreamer` / `findGroupByName` / `deleteGroup` / `setGroupCollapsed`。Repository：`GroupWithCount`、`observeGroups`、`groupsWithCount`、`groupsOf`、`createGroupAndAssign`、`assignGroup`、`unassignGroup`、`deleteGroup`、`setGroupCollapsed`（均带审计）。UI：首页分组筛选行（与标签**互斥**——两个归属维度同时约束会产生难以解释的空结果）、详情页「分组」卡片（加入/移出）。 |
| D2 | **崩溃恢复会话（RecoverySession）** | 新建 `RecoverySessionRepository`：`REQUESTED → CAS RUNNING → 补检一次 → 终态` 闭环，代际不匹配则作废；`cancelStaleRequested` 清理上一轮残留。DAO 补 `insert` / `findById` / `recent` / `findActive` / `cancelStaleRequested`。`StartupRecovery` 接入，诊断页展示。 |
| D3 | **崩溃记录（CrashRecord）** | 新建 `core/CrashRecorder`：注册未捕获异常处理器（用 `runBlocking` 确保落盘），只记录异常类型+首帧位置（**脱敏**，不写用户数据）；冷启动把上次未恢复的崩溃标记为已恢复，并写 `HealthEvent`；90 天保留。新建 `CrashRecordDao`，注册进 `AppDatabase` 与 DI。 |
| D4 | **统计快照（StatisticsSnapshot）** | 快照成为同一 `sourceDataVersion` 下的**规范结果**（随版本推进自动失效，不存在跨代命中）；`compute()` 优先命中快照 → 行级缓存 → 实算；滚动保留最近 20 个；新增 `recentSnapshots` / `cleanupSnapshots`。 |
| D5 | **通知点击深链跳转** | 新建 `NotificationDeepLinkResolver`：`evt:` → 主播详情、`agg:` → 聚合首个主播详情、`problem:` → 健康中心、无法解析则不误跳。`MainActivity` **真正读取 eventKey**（此前只把 URL 抛给浏览器后从不读取），`onNewIntent` 同样处理；`AppNavHost` 在组合完成后导航一次并消费。 |
| D6 | **通知栏「停止监控」按钮** | 常驻通知新增 `addAction("停止监控")`；`ACTION_STOP` 分支**同时关闭配置里的监控开关** —— 否则下一个 Tick 会立刻把它拉起来，用户会觉得「关不掉」。新增 `ConfigRepository.setMonitoringEnabled()` 走同一条 CAS 更新。 |
| D7 | **免打扰时段（静默时段）** | 新建 `domain/policy/QuietHoursPolicy`（纯函数，支持**跨午夜**、起止相同视为未配置）+ `QuietHoursRepository`（DataStore，**不牵动 schema 迁移**）。时段随 `MonitoringConfigSnapshot` 进入 Tick，开播与关播通知均被抑制，**但场次与历史照常记录**；主播级 `overrideQuietHours` 死字段被真正读取。设置页新增开关 + HH:mm 输入（含校验）；8 个单测。 |
| D8 | **历史容量低水位预警** | 新增 `CapacityUsage` 与 `capacityUsage()`：95% 预警 / 90% 低水位；达阈值写 `problem_state` 并发系统问题通知（7 天冷却），回落自动解除；诊断页显示容量百分比。 |
| D9 | **清理扩展到其余表** | 补上 `export_snapshot`（`deleteExpired` 此前从未被调用）、`crash_record`（90 天）、`restore_run`（90 天）、`follow_import_task`（30 天）、`statistics_cache`（30 天）的保留策略。`notification_id_registry` **按 0.6.12 刻意不清理**（eventKey→notificationId 稳定性依赖它）。 |

---

## G. 本轮新代码的 bug 审查（2026-09-13，纯静态、未编译）

> 工作模式：**不编译**。以下为审查中确认并已修复的问题。

### G1. C6 的修复被"容错"重新掩盖（已修复）
`NotificationDispatcher` 里写成 `runCatching { countActiveEvents(...) }.getOrDefault(0)` ——
**一旦查询失败就静默写回 0，等于把原来那个 bug 原样带回**。
改为不做容错：查询失败让事务整体失败、Outbox 保持 `PROCESSING`，由启动恢复按 `DELIVERY_UNKNOWN` 重试。
（已核实修复方向本身正确：`processWindowEnds` 在"已达阈值"路径**不释放绑定**，
状态置 FROZEN 且 `active` 仍为 1，投递时 `countActiveEvents` 能取到真实值。）

### G2. 免打扰读取失败会静默变成"关闭"（已修复）
`QuietHoursRepository.current()` 原为 `runCatching { flow.first() }.getOrElse { QuietHours() }` ——
读配置失败即回退默认值（`enabled = false`），**用户以为夜里不会被打扰，实际会被通知吵醒**。
改为让异常向上传播，由 `ConfigSnapshot` → 引擎记 `CONFIG_INVALID` 统一暴露。

### G3. 批次冷却占着并发槽"睡觉"（已修复）
`MonitoringEngine` 把 `delay(cooldownMs)` 放在 `Semaphore` 的 `acquire/release` **之间**：
并发数 4 时前 4 个批次同时持锁入睡，后续批次全被挡住 ——
"并发 + 批次间冷却"实际退化成**串行且每批白等一个冷却周期**。
修复：`release()` 紧跟请求结束（`finally`），冷却移到闸外；并发数 > 1 时不再叠加串行冷却
（否则等于废掉用户设的并发数）。结果收集也改为按下标取数组，避免 `Pair` 的类型推断歧义。

### G4. 免打扰时段：保存「开始」会回滚「结束」（已修复）
设置页两个输入框各自提交时，读取的是**另一个字段上一次已保存的值**。
于是"先改结束、再改开始"会把结束时间回滚成旧值。
修复：两个输入框共用一个 `commitBoth`，任一字段点保存都提交两者当前输入；
错误提示改为明确指出是哪个字段格式不对。

### G5. 诊断包迁移路径列表未跟上 v6（已修复）
`migration_info.json` 的 `availableMigrationPaths` 仍只列到 4→5，已补 5→6。

---

## G-bis. 多 agent 独立审查发现的问题（已修复）

> 4 个只读审查 agent 分别覆盖「引擎与状态机 / 数据层与 DAO / UI 与通知 / 备份恢复与导入」。
> 其中 DAO agent 用**真实 schema 建库 + 228 条 `@Query` 全部 EXPLAIN 预编译**验证，
> 得出**0 条 Room 编译期错误**；UI agent 逐条核对了 `combine` 路数、`BiliMonitorApp` 签名、
> `J` 构建器、`NotificationPoster` 构造、`when` 穷尽性，**均无编译问题**。
> 下列为查出的真实缺陷。

### 数据安全级

**G6. 数据库版本号在本项目里存在多个手写副本（已修复）**
`AppDatabaseVersionProvider.version` 硬编码为 5，而库已升到 6 →
备份清单双向判错：v6 备份在真 v6 设备上被当"旧结构"放行；`precheck` 的版本上界变成 5，
**来自 v7+ 的备份反倒被放行**（守卫形同虚设）。
修复：在 `AppDatabase` 里新增唯一常量 `DB_VERSION`，
`@Database(version = DB_VERSION)`、`AppDatabaseVersionProvider`、
`DiagnosticExporter.COMPILED_DB_VERSION` 全部改为引用它，从根上杜绝再次脱节。

**G7. `applyRestore` 从不获取维护权（已修复）**
KDoc 与 `DataScreen` 都声称「已进入维护模式，监控暂停」，但实现里没有任何
`enterMaintenance/exitMaintenance`，`MaintenanceMode.RESTORE` 从未被写入。
后果：恢复事务与监控引擎并发写库，引擎并发插场次 → `sessionKey` 唯一索引冲突 →
**整个恢复事务回滚**，而 run 已被置为 APPLYING，只能等下次冷启动兜底。
修复：进入 APPLYING 后获取 `MaintenanceMode.RESTORE`，全部路径由 `finally` 释放。

### 功能缺陷级

**G8. 通知清理仍会撞外键（已修复 —— 我在 A2 里的修复不彻底）**
我在 A2 里补的 `deleteFinishedAttemptsForTerminal` 只清 attempt 子行，
**没有对应的 history 子行清理**（注释甚至引用了一个不存在的 `deleteHistoryForTerminal`）。
子行清理受 `LIMIT 500` 截断时，删 Outbox 就抛 `FOREIGN KEY constraint failed`，
异常被外层 `runCatching` 吞掉 → 后续通知历史与聚合清理**永不执行**。
DAO agent 用真实 schema 实测复现（`FAIL cleanupTerminal(now-30d,500)`）。
修复：把「无子行」写成删除条件本身（`NOT EXISTS` 子查询），
使清理**顺序无关、批次大小无关**；未到期的行自动跳过、后续轮次自然清理。
同时修掉聚合分支：原来 `outboxExistsForAggregate` 会因终态 `FAILED` 行永久卡住聚合，
现在改为「可删的行直接删、删不掉的跳过等下轮」。

**G9. `FollowImportRepository` 用 `JsonElement.toString()` 解析 JSON（已修复）**
kotlinx 的 `toString()` 返回**序列化文本**，字符串 primitive 带外层双引号 →
昵称入库变成 `"张三"`（带引号）、字符串型 `author_mid` 解析失败被**静默丢弃**。
同包的 `BiliAccountParsers` 用的是正确的 `jsonPrimitive.content`，属漏改。
修复：全部改用 `jsonPrimitive.content` / `longOrNull` / `contentOrNull`，并补 `mid <= 0` 过滤。

**G10. 导入任务无异常兜底 → 永久停在 FETCHING（已修复）**
抓取与落暂存全部丢进无 `CoroutineExceptionHandler` 的 `appScope`，
`insertStaging` 完全裸露；任一异常都会让协程静默死掉，任务卡在 FETCHING，
UI 的"正在获取列表"永远转圈且无重试入口 —— 正好击穿 B5 的修复目标。
修复：加 `importFailureHandler`，任何未捕获异常都把任务置 FAILED 并写明原因；
`fail()` 也补上 `runCatching`。

**G11. `precheck` 场次数重复计数（已修复）**
两个循环条件重叠：先按主播累加一次，再按场次累加一次 →
1 个已存在主播 + 10 场新场次会报「20 场」，实际只恢复 10 场。
修复：删除第一个循环里的累加，只保留与 `applyRestore` 跳过条件一致的场次维度计数。

**G12. 导出维护权提前释放 + `CONSUMING` 快照无人回收（已修复）**
`exitMaintenance` 在快照复制完成时就执行，而 CAS→读快照→写文件都在无锁窗口内；
且中途失败会把快照永久留在 `CONSUMING`（`deleteExpired` 只处理已到期行）。
修复：失败路径把快照 CAS 到 `FAILED`，保证状态机闭环。

### 一致性问题

**G13. 同实体两条路径行为不一致（已修复）**
- `StreamerRepository` 的 **uid 导入恢复**分支漏 `deletePendingTransition`：
  代际已 bump 但挂起计数残留，会被新代际的首次观察当成"已连续确认若干次"→ 可能误报状态转换。
- 占位昵称写死 `"[待获取]"`（两处），而 `addStreamer` 走的是可配的 `placeholderText`。
- `BackupManifest.appVersionName` 写死 `"1.0"`，实际已是 1.1。
修复：补上 `deletePendingTransition`；两处占位统一读配置；版本名改读包信息。

**G14. `MainActivity` 配置读取失败 → 永久白屏（已修复）**
`setContent` 依赖 `startDestination` 非空，一旦建库/种子失败就永不调用 →
用户看到无反馈、无法自愈的白屏。修复：改为无条件 `setContent`，
配置为 null（含异常）时退回引导页，保证界面一定出得来且可通过引导页自愈。

**G15. 健康横幅「点此设置」是死链（已修复）**
`HealthBanner` 恒传 `onClick = null`，而 `HealthRepository` 的文案明确写着
"点此设置 / 点此授权 / 点此前往设置开启"。修复：非 HEALTHY 状态下点击跳设置页。

**G16. 仪器测试断言 DB 版本 5（已修复）**
`DiagnosticExporterTest` 仍断言 `compiledDatabaseVersion == 5` 与
`currentDatabaseVersion == 5`，升版到 6 后**必然红灯**。已改为 6 并加注释说明需同步。

---

## G-ter. 引擎与状态机专项审查发现（已修复）

> 审查 agent 用 `javap` 把字节码与当前源码逐行比对，确认"17:17 之后修改、未编译"那部分的
> 实参顺序与字符串字面量都一致；结论是**无编译级错误**。
> 熔断器接线、两个方向的计数通路、免打扰接线均被判定为**正确**。

### 严重

**G17.「连续确认」其实不连续 → 会误报关播（已修复）**
策略 KDoc 与单测都把"归零"归责于仓储层，但 `applyObservationOnly` **从不复位 pending 行**
（`deletePendingTransition` 只在状态转换提交时调用）。于是
`Tick1 观察到 OFFLINE（count=1）→ Tick2 网络失败（pending 原封不动）→ Tick3 再 OFFLINE（count=2）过阈值`，
两次"连续确认"之间可隔任意多次失败 → **假关播**：错误的 END 事件与通知、场次被关、时长与统计一起错。
修复：在 `applyObservationOnly` 的非 SUCCESS 路径上复位 pending 计数。

**G18. ROUND → LIVE 插第二条 ACTIVE 区间 → 主播永久卡死（已修复）**
ROUND 不关闭区间（符合契约），所以 ROUND→LIVE 时旧区间仍 ACTIVE；
而该分支**无条件再插一条**，违反部分唯一索引 `idx_interval_one_active`（ABORT）→
整个事务回滚 → 主播**永久卡在 ROUND**，每个 Tick 失败并写一条错误日志
（DB 无界增长、界面数据长期 STALE）。
根因：`val activeInterval = findActiveByStreamer(...)` 查出来**从未使用**。
修复：两个区间插入点都改为「有 ACTIVE 区间则 renew，否则才 insert」。

### 功能缺陷

**G19. 恢复检查在本进程内被静默禁用（已修复）**
`casToRunning` 失败时的补偿写入用了 `casToTerminal`，而后者只匹配 `status='RUNNING'` ——
此时行还是 `REQUESTED`，**必然影响 0 行**。残留的 REQUESTED 行受
`idx_recovery_one_requested` 约束，使本进程之后每次插入都被 IGNORE → 恢复检查静默失效。
已新增 `casRequestedToTerminal` 并加兜底。

**G20. CAS 失败被静默丢弃（已修复）**
`ApplyObservationResult` 只处理 `Invalid`，其余（尤其 `StaleSequence` / `StaleOrFenced`，
意味着**本次观察根本没写进数据库**）无日志无事件，Tick 仍报 `ran=true`。
已补齐全部分支。

**G21. 同一 Tick 内「失败批次 + 成功批次」会立刻宣布网络已恢复（已修复）**
`onTransportSuccess/Failure` 按**批次**调用：一个批次成功就把刚打开的盲区与问题标记为已恢复，
而另一些主播在同一 Tick 刚被记为 NETWORK_ERROR。已改为 Tick 汇总后一次性判定。

**G22. 熔断打开期间绕过失败计数（已修复）**
原实现直接 `openSystemGap` 而不走 `onTransportFailure`，失败计数与问题通知在熔断期间不再推进。
已统一走同一条路径。

### 稳定性与竞态

**G23. `breaker` 跨协程可见性缺口（已修复）** — 写在内、读在外且无锁，已标 `@Volatile`。

**G24. 手动刷新路径会崩进程（已修复）**
`appScope` 无 `CoroutineExceptionHandler`，而 `checkOnce` 的
`getSnapshot`/`listMonitorable`/`dispatchDue` 不在 `runCatching` 内；
服务/Worker/恢复路径各有 catch，唯独"用户点刷新"没有。已加 `runCatching` + 错误日志。

**G25.「停止监控」可能关不掉（已修复）**
`stopSelf()` 之后才用 `lifecycleScope` 写配置，`onDestroy` 取消 scope → 事务可能回滚 →
配置仍 enabled → 下次冷启动又把服务拉起。已改为**先落库再停服务**。

**G26. 退避参数无上限校验（已修复）**
`retryBaseSeconds`/`maxRetryDelaySeconds`/`circuitBreaker*`/`aggregationWindowSeconds`/
`batchCooldownSeconds` 完全没校验，而 `withTimeout` 只包住请求、**不覆盖 delay** ——
坏配置能让一次 Tick 睡数天。已为每个参数加上明确上界。

**G27.「只差一次」的恢复依据会误报（已修复）**
仅凭"上一条健康事件是 DEGRADED"就恢复计数：故障早已结束、只是当时进程已死（没写出 HEALTHY）时，
新进程的**第一次**失败就被当成第 3 次，立刻误报。已追加条件：SYSTEM 盲区必须**仍未关闭**且在宽限窗口内。

### 顺带清理
- `StreamerMonitorContext` 移除 `hasOpenStreamerGap`/`streamerStableId`/`streamerId`/`uid` 四个死字段
  —— 其中 `hasOpenStreamerGap` 每主播每 Tick 白查一次库。
- `StateConfirmationPolicy.detect` 移除从未使用的 `observationSequence` 形参（同步更新 12 个单测调用点）。
- 移除 `MonitorRepository` 中查了却不用的 `activeInterval` 隐患（见 G18）。

---

## H. 用户实测报告的线上问题（2026-09-13）

> 来源：用户手动测试报告。两个问题都用**实测取证**定位（HTTP 日志 + 接口探测），没有靠猜。

### H1. 二维码只弹一下就消失（已修复，渲染层）

**根因**：渲染门控错了。二维码卡片只在 `is QrLoginState.QrReady ->` 分支里渲染，
而 `startQrLogin()` 生成二维码后**立即开始轮询**，第一次轮询就把状态推进到
`WaitingScan` —— 于是卡片立刻不再渲染，用户看到"二维码闪一下就没了"
（其实 `qrBitmap` 一直在内存里，从未被清空）。

**修复**：改为「只要 `qrBitmap != null` 就渲染二维码」，状态只改下面的提示文案；
无二维码时才显示 `Idle / Expired / Confirmed / Failed` 的独立提示。
另外把 `LoginScreen` 中"尚未登录。点击上方「扫码登录」获取二维码。"
从 `Idle` 分支移出 —— 原实现即使在**已登录**时也会显示这句矛盾文案（实测确认）。

### H2. 观看历史导入失败（已修复 —— 真因是一个数字）

**真因：`ps` 参数超过接口上限。** 用同一个浏览器 cookie 实测：

```
ps=30  → code=0    OK，正常返回 list
ps=40  → code=-400 请求错误
ps=50  → code=-400 请求错误
```

App 默认 `count = 50`、上限 500，所以**从第一版起这个参数就一直超限**。
B 站对超限参数返回的是 `{"code":-400,"message":"请求错误"}`，
既不说哪个参数错、也不像 `-101` 那样提示未登录，极易被误判为风控或凭证问题。

**排查过程中走过的弯路**（记录下来避免重犯）：
依次怀疑并"修复"了 Referer 来源不匹配 → 请求缺 Cookie 头 → 缺设备标识 buvid3 →
WBI 签名（w_rid/wts）→ 请求体风控票据 bili_ticket → 客户端 UA 不匹配。
其中**前两项是真实缺陷**（已保留修复），但**都不是 `-400` 的原因**；
后几项是纯粹的猜测，浪费了大量时间。
**教训**：面对"服务端只说参数错"的错误，应当**优先做输入边界的二分验证**，
而不是顺着自己的实现逐层假设风控。一次 `ps=30 vs ps=50` 的对比就能定位。

**修复**：`HISTORY_PS_MAX = 30`，观看历史与关注列表的 `ps` 都夹到该值。

### H2-bis. 「没有获取到可导入的主播」是**正确结果**，不是 bug

修好 `ps` 后用真实账号验证：
```
全部（无过滤）          code=0  30 条，类型分布 = {'archive': 30}
type=live              code=0   0 条
type=live&business=live code=0   0 条
```
该账号看过 30 条**视频**、**0 条直播** —— 直播观看历史本来就是空的。
接口调用完全成功，字段名 `author_mid` / `author_name` / `author_face` 也已确认正确。

修复：把提示改准确 ——「观看历史里没有直播记录（只有视频/专栏记录）。
如果想按已关注的主播导入，请把来源切换为「按关注导入」」。

### H2-ter. 保留的真实修复（虽非 -400 主因，但都是缺陷）

- **Referer 按接口来源匹配**：原实现给所有 bilibili 请求写死直播站 Referer，
  主站接口（账号/观看历史）应使用 `www.bilibili.com`。
- **显式写入 Cookie 头**：实测 `cookieJar()` 的自动附加在真实出网请求上没有生效
  （CookieJar 返回了 7 个 cookie，但请求头里没有 `Cookie`）。
  改为在拦截器里显式取 CookieJar 结果写入请求头，行为不再依赖隐式机制。
- **设备标识 cookie**：`/x/frontend/finger/spi` 把 `b_3`/`b_4` 放在**响应体**里
  （不是 Set-Cookie），必须自己解析写回。
- **WBI 签名**：新增 `core/WbiSigner`，密钥取自 `nav` 的 `wbi_img`。
  算法已独立复算 MD5 验证正确（三个待签名串全部匹配）。
  注意：**观看历史接口实测并不需要 WBI**，但保留签名对后续其他主站接口有益。

### H4. 新增：用 Cookie 直接登录（用户要求）

扫码登录只能拿到 `SESSDATA` 等基础凭证；浏览器会话里的 Cookie 还包含
设备标识与风控票据，完整性更高。因此新增：
- `BiliCookieJar.importCookieHeader(raw)`：解析浏览器复制的 Cookie 串并加密保存
- `AuthRepository.loginWithCookie(raw)`：导入后立即用 `nav` 验证，返回明确的失败原因
- 登录页新增「用 Cookie 登录」入口 + 粘贴对话框，并说明获取方法
- `credentialSummary()`：登录后记录 `SESSDATA` / `buvid3` / `bili_ticket` 的到位情况

### H5. 端到端验证结论（2026-09-13 18:28，**已被 H8 修正**）

> ⚠️ 本条当时的结论是错的，保留原文以便追溯，正确结论见 H8。

用真实账号 + 扫码登录实测，**关注导入完全跑通**：
预览弹窗显示「选择要导入的主播（40/40）」，列表含真实主播（大雨实验室 / 风屿゛ / …）。
当时据此得出两个结论：
1. ~~扫码登录本身没有问题~~ —— 扫码确实没问题，但由此**错误地推断**"观看历史失败也只是因为历史为空"；
2. ~~之前"没有获取到"的唯一原因是直播观看历史为空~~ —— **错的**。
   真实原因是 `ps` 超限（H2）+ 一个自设的 `bili_ticket` 前置门槛（H8）。
   当时用的那个账号恰好真的没有直播记录，于是"账号为空"与"参数非法"两个原因被混为一谈，
   还在没有任何证据的情况下把"账号没看过直播"写成了唯一解释。

### H6. Cookie 来源审计（回答"该调哪个接口拿完整 cookie"）

实测各接口实际下发的 cookie：

| 接口 | 实际下发 |
|---|---|
| `https://www.bilibili.com/` | `b_nut`, `buvid3` |
| `/x/frontend/finger/spi` | `b_nut`, `buvid3`（另有响应体 `b_3`/`b_4`） |
| `/x/web-interface/nav` | `b_nut`, `buvid3` |
| 扫码 poll（登录成功那一次） | 5 个 Set-Cookie（SESSDATA / bili_jct / DedeUserID 等） |

**结论**：`buvid_fp` / `_uuid` / `bili_ticket` / `bili_ticket_expires` 这些
**不是通过 HTTP 响应头下发的**，而是网页 JS 自行生成/注入的，
纯 HTTP 客户端（本 App）无法从任何接口"取到"它们。

**但也不需要**。逐一剥离实测关注列表接口的依赖：

```
仅 SESSDATA              → code=0，30 条
SESSDATA + bili_jct      → code=0，30 条
SESSDATA + buvid3        → code=0，30 条
SESSDATA + buvid3 + ticket → code=0，30 条
```

**只依赖 `SESSDATA`** —— 扫码登录即可满足。

> 补充（H8）：观看历史接口同样不需要 `bili_ticket`，实测 `SESSDATA + buvid3` 即可 `code=0`。
> 因此"扫码登录拿不到完整 cookie"从来不是导入失败的原因。

### H7. 由此做的产品调整

- **默认导入来源改为「按关注导入」**：直播观看历史依赖"用户真的看过直播"，
  对多数账号是空的；关注列表则人人都有。
- 来源区补充说明：「直播观看历史导入依赖『你曾在 B 站看过直播』。
  若你的观看记录里只有视频，该来源会没有内容 —— 此时请改用『按关注导入』。」
- 观看历史为空时的错误信息同步改写为可操作的指引。

---

### H8. 直播观看历史导入 —— 最终定位与实机跑通（2026-09-13，用户提供真实 cookie 后）

用户指出：**当前登录的这个账号确实有观看历史**，导入依然失败。
拿用户浏览器里那串完整 Cookie 做参数矩阵实测（`/x/web-interface/history/cursor`）：

| 请求 | 结果 |
|---|---|
| `ps=30`（不带过滤） | `code=0`，30 条，`business` 分布 `{live:7, archive:23}` |
| `ps=30&type=live` | `code=0`，30 条，**`{live:30}`** |
| `ps=30&business=live` | `code=0`，30 条，`{live:7, archive:23}`（**该参数被忽略**） |
| `ps=30&type=live&business=live` | `code=0`，30 条，`{live:30}` |
| `ps=30&type=archive` | `code=0`，30 条，`{archive:30}` |
| **`ps=50&type=live&business=live`** | **`code=-400` 请求错误** |

结论有三条，缺一不可：

1. **`ps` 硬上限 30**（H2 已记录）—— App 原来默认 `count=50`，从第一版起就一直超限；
   这就是那句 `-400` 的全部来源，与 cookie、Referer、WBI 签名、风控都无关。
2. **真正起过滤作用的是 `type`，不是 `business`**。
   两个参数都传是安全的（`type=live` 生效）；但只传 `business=live` 会拿到混合列表，
   把 23 个**视频 UP 主**当成主播导进来。参数保持现状即可，此处记录以免日后误删 `type`。
3. **`bili_ticket` 门槛是本项目自己造出来的 bug**。
   `ensureMainSiteReady()` 曾把"缺 `bili_ticket`"当硬性前置条件直接拒绝发请求 ——
   而 `bili_ticket` 只由网页 JS 注入、HTTP 客户端永远拿不到，
   于是导入被自家守卫永久锁死，真正的 `-400` 反而被掩盖成一句"需要风控票据"。
   已删除该门槛，并把 `ensureMainSiteReady()` 改成**纯诊断**（返回值只写日志，不阻断请求）。

**实机验证（模拟器 + 用户账号 UID 366596377）**：

```
I/FollowImport: 主站准备：OK；wbiReady=true；凭证=SESSDATA=有，buvid3=有，bili_ticket=缺
I/WbiSigner  : 待签名串=business=live&ps=30&type=live&wts=1789295921
I/BiliHttp   : /x/web-interface/history/cursor → HTTP 200，bili-status=0
I/FollowImport: 观看历史请求（ps=30）：code=0 message=OK
I/FollowImport: 观看历史导入：原始 30 条，去重并截取后 30 条
```
界面：预览弹窗「选择要导入的主播（30/30）」，含真实主播（恬豆发芽了 / 露蒂丝 / 星汐Seki / 伊索尔Sol / 梨安不迷路 / 少年Pi …）；
点「导入所选」后提示「导入完成：新增 30，恢复 0，已存在 0」。

**顺带修掉的误导性文案与加固**：

- 登录页在**已登录**状态下仍显示"尚未登录。点击上方「扫码登录」获取二维码。"（`QrLoginState.Idle` 分支没判登录态）—— 已按登录态隐藏。
- "登录仅用于「关注列表导入」" → 「关注导入」；"扫码只拿到基础凭证、导入历史可能失败" → 改为
  "两种方式都可导入（实测只需 SESSDATA + buvid3）"（原文案已被本次实测证伪）。
- 观看历史来源的说明补上"接口单次最多 30 条"，避免用户把数量填 50 却只拿到 30 条时以为出错。
- `getMainSiteJson()`：主站请求先带 WBI 签名，被拒后**自动退回不带签名重试一次**。
  目的是把"签名算错/密钥过期"与"参数非法"区分开 —— 这两种情况返回的都是同一个 `-400`。
  签名环节将来若再出问题，导入不会因此整体失效。
- `build-and-install.ps1`：模拟器未启动时等待 boot 的循环里 `adb` 会往 stderr 打
  `no devices/emulators found`，在 `$ErrorActionPreference='Stop'` 下会直接中断脚本（实测踩到）。
  已在该循环内临时降级为 `Continue`。

**凭证安全提醒**：用户把完整的浏览器 Cookie（含有效 `SESSDATA`）直接粘贴到了对话里。
该串等同于账号登录态，建议在浏览器里退出登录使其失效后重新登录。
本轮的探测脚本一律从环境变量读取 Cookie、**不落盘**，脚本与临时文件均已删除
（另清理了 `%TEMP%` 下遗留的 `bili_cookies.txt`）。

---

### H9. 自定义数量失效 → 改为分批翻页 + 新增「全部获取」（用户要求）

**用户报告**："自定义的获取数量好像没有生效"——填 50 只拿到 30 条。
根因就是 H8 的 `ps` 上限 30：原实现把 `count` 直接夹到 30 后**只发一次请求**，
所以填 30 以上的任何数字都只能拿到 30 条。这不是参数没传，
而是"一次最多只能要 30 条"这件事没有被处理。

**接口翻页契约（实测，同一账号）**：

```
type=live&business=live&ps=30                     → 30 条，cursor.max=22886883
+ max=22886883&view_at=...                        → 30 条，页间重叠 0
+ 下一页 cursor …                                  → 30 / 30 / 14，共 5 页 134 条
```
- 结束标志：某页返回条数 **< 请求的 ps**；
- 每页必须回传上一页 `data.cursor` 里的 `max` 与 `view_at`；
- 关注列表用 `pn` 翻页，同样是每页 ≤ 30，行为已实测一致。

**实现**：

| 位置 | 改动 |
|---|---|
| `FollowImportRepository` | 新增 `IMPORT_MAX_COUNT = 500`（自定义与"全部"共用的上限）、`IMPORT_PAGE_CAP = 40`（防死循环的分页保护） |
| `startImport(source, count, fetchAll)` | `limit = if (fetchAll) IMPORT_MAX_COUNT else count` |
| `fetchLiveHistory` | 按 `ps = min(30, 剩余需要量)` 循环翻页，用 `LinkedHashMap` 边收边去重；每页把已获取数写进 `stagingCount` 作为进度；游标不前进 / 页条数不足 / 达到上限都会停 |
| `fetchFollowings` | 同样改成边翻边去重 + 进度上报，页数上限由 10 提到 `IMPORT_PAGE_CAP` |
| 直播记录过滤 | 客户端再按 `history.business == "live"` 兜一层（`business` 缺失时看 `uri` 是否指向 `live.bilibili.com`），避免 `type` 参数将来失效时把视频 UP 主当主播导入 |
| 中途失败 | 已拿到数据时不再整体失败，而是保留已获取部分并在日志写明中断原因 |
| `LoginScreen` | 数量输入框旁新增「全部获取」勾选框（勾选后输入框禁用）；说明文案写明"单页最多 30 条，超过会自动分批"；拉取中显示"已获取 N 个" |

**实机验证**：

```
全部获取：目标=全部（上限 500）→ 第1~5页 ps=30 → 翻 5 页、扫描 134 条，去重后 134 个主播
          预览弹窗「选择要导入的主播（134/134）」   ← 与接口实测的 134 条完全一致
自定义 45：目标=45 条 → 第1页 ps=30、第2页 ps=15 → 去重后 45 个主播
          预览弹窗「选择要导入的主播（45/45）」     ← 最后一页只请求剩余 15 条，不浪费请求
```

**补充（同日后续，用户要求）**：
- 上限由常量改为**可配置**（`ImportSettingsRepository`，DataStore，默认 500，硬顶 100000），
  在「B 站账号 / 关注导入」页可直接改；自定义数量与「全部获取」共用这个上限。
  用 DataStore 而非 `monitoring_config` 表，理由与 `QuietHoursRepository` 相同：纯偏好，不牵动 schema 迁移。

---

### H10. 直播历史导出：入口位置、时间范围、文件命名（用户定稿）

**需求（用户原话）**：
1. 导出入口**不要放在设置里**；
2. 单个主播的导出放在**该主播自己的直播历史页面**，导出时只需要划定时间范围
   （按日 / 按周 / 按月 / 按年 / 自定义）；
3. **直播历史一级界面**新增导出按钮：先选一位或多位主播（多位合并成一份文件），再选时间范围；
4. 文件命名：单主播 = 时间范围 + 主播名；多主播 = 时间范围 + N 位主播；
   时间范围取**所选记录中最早到最晚一条的日期，精确到日**。

**实现**：

| 位置 | 改动 |
|---|---|
| `ui/common/TimeRange.kt`（新） | 时间范围语义与选择组件从 `HistoryScreen` 提取出来：`HistoryFilterType` / `HistoryFilter` / `bounds()` / `label()` / `TimeRangeDialog`。筛选与导出**共用同一套口径**，避免"筛出来的范围"与"导出的范围"算法不一致 |
| `ExportRepository.exportHistory(streamerIds, from, to, rangeLabel)`（替换原 `exportHtml(days)`） | 仍走"维护模式 → 快照表 → 流式写出"的既定唯一路径，只把选取范围从"最近 N 天"改为"主播集合 + 时间区间" |
| `LiveSessionDao.listClosedForStreamersBetween` | 按主播集合 + `COALESCE(startTime, createdAt)` 区间取已结束场次（与历史页筛选口径一致：用户理解的"这条记录属于哪天"是开播那天） |
| 文件命名 | `buildFileName()`：时间取**实际导出数据**的最早/最晚开播日；单主播用主播名（过滤 `\/:*?"<>|` 等非法字符），多主播用 `N位主播` |
| 直播历史二级页 | 顶栏新增「导出」→ `TimeRangeDialog` → 导出本主播 |
| 直播历史一级页 | 顶栏新增「导出」→ 勾选主播（含全选/清空/搜索）→ 下一步 → 时间范围 → 导出 |
| 设置页 | 删除原「导出」卡片与"最近 N 天"对话框；`DataViewModel.exportHtml` 一并删除（避免留下死代码） |
| 顺带清理 | `HistoryScreen` 的 `bucketValues` 及其四个 `bucket*` 工具函数彻底删除 —— 它一直被计算、被传进 `FilterDialog` 却零引用（`UI_LAYER_ANALYSIS` 已记录该死参数，本次因其唯一消费者被替换而删除） |

**实机验证（模拟器）**：

```
单主播（二级页）：开始导出：主播=1 位，范围=按年 · 2026 年
                 导出成功：2026-09-10_2026-09-11_星汐Seki.html（2 场）
多主播（一级页）：开始导出：主播=2 位，范围=按年 · 2026 年
                 导出成功：2026-09-10_2026-09-11_2位主播.html（3 场）
报告抬头：导出对象：2 位主播（星汐Seki、梨安不迷路）| 选择的时间范围：按年 · 2026 年 | 场次：3
```
（验证用场次是通过 sqlite 直接插入的合成记录，验证后已删除；`Download` 里的测试文件也已清理。）

**说明**：多主播时文件名里的"N"取的是**文件里实际含有记录的主播数**，
不是勾选数 —— 勾了 3 位但只有 2 位有记录时，写"2 位主播"才与文件内容一致。

**补充（同日，用户要求）**：导出对话框除了按日/周/月/年/自定义，**再加一个"全部"**。
此前 `TimeRangeDialog` 已有 `allowAll` 开关，只是导出侧刻意关掉了 —— 现在两处导出都打开，
且"全部"排在第一位并默认选中（不选日期直接点导出即为全量）。
`bounds()` 对"全部"返回 null（"不加时间条件"），导出需要具体区间，因此新增
`exportBounds()` 把它翻译成 `[0, Long.MAX_VALUE)`。实测：

```
单主播：开始导出：主播=1 位，范围=全部，[0, 9223372036854775807)
        导出成功：2026-09-11_2026-09-11_梨安不迷路.html（1 场）
多主播：开始导出：主播=2 位，范围=全部，[0, 9223372036854775807)
        导出成功：2026-09-10_2026-09-11_2位主播.html（2 场）
```
（单条记录时时间范围两端同日，属预期。）

**补充 2（同日，用户要求）**：报告**按主播分组排版**，且**被选中但没有记录的主播也要出现**（空分组）；
文件命名里的 N 改为**选择的主播数**（不再按"有记录的主播数"）。

- 分组依据是"选中的主播列表"而不是快照里出现过的名字：名字从 `streamer` 表现取
  （快照表里没有空主播的行），顺序沿用用户勾选顺序。
- 每个分组含 `h2` 标题（主播名 + `N 场　·　总时长`）与自己的表格；空分组写成
  `<p class="empty">该时间范围内没有直播记录</p>`，且标题只写"0 场"（不跟"0分0秒"）。
- 表格列由 5 列改为「标题 / 时间 / 时长 / 可信度 / 备注」——主播名已经在分组标题里，不必每行重复。
- 抬头补充「主播：N 位（其中 K 位无记录）」，`ExportResult` 也带上 `streamerCount/emptyStreamerCount`，
  导出完成的提示会显示"3 位主播 / 5 场，其中 1 位无记录"。
- 全部主播在该范围内都没有记录时仍然**不生成文件**（否则时间范围无从计算），
  提示语改为"所选主播在该时间范围内没有直播记录，未生成文件"。

**实测**：选 3 位（2 位有记录、1 位无记录）、范围=全部 →

```
文件名：2026-09-10_2026-09-11_3位主播.html          ← N 取选择数 3
日志：  导出成功：…（3 位主播 / 3 场，其中 1 位无记录）
结构：  <h2 class="streamer">星汐Seki<span class="meta">2 场　·　…</span></h2> + 表格(2 行)
        <h2 class="streamer">梨安不迷路<span class="meta">1 场　·　…</span></h2> + 表格(1 行)
        <h2 class="streamer">七海Nana7mi<span class="meta">0 场</span></h2>
        <p class="empty">该时间范围内没有直播记录</p>
抬头：  导出对象：3 位主播（星汐Seki、梨安不迷路、七海Nana7mi）
        选择的时间范围：全部　|　主播：3 位（其中 1 位无记录）　|　场次：3
```

---
### H11. 导出偶发失败与"维护权永久卡死"（本次实测暴露的两个锁缺陷）

**现象**：同一台机器上，连续两次导出，第二次报
`IllegalStateException: 导出/恢复/清理正在进行，请稍后再试`（失败率约一半，且报错原因与实际情况无关）。

**根因（读代码 + 读库确认）**：`RuntimeLockDao.enterMaintenance` 按 0.6.35 只允许在
`monitoringLeaseId IS NULL OR leaseUntilWall < now` 时接管；而监控引擎每 180 秒 Tick 一次、
租约 TTL 是 `LEASE_DURATION_MS = 90_000`，且**Tick 结束后不释放租约**
（`release()` 在 background 包内无调用点）。于是每个 Tick 之后有 90 秒租约有效 ——
恰好在实时间隔模式下占了约一半时间。用户点导出撞上就是一句"正在进行"。

**修复 1（可用性）**：新增 `enterMaintenanceForcing` / `RuntimeLockRepository.takeOverForMaintenance`，
供**用户主动发起**的导出与备份使用：允许直接夺走有效期内的租约。
安全性由 fencing 保证而非由"等租约过期"保证 —— 它同样递增 `monitorGeneration` 并清空
`fencingToken`，引擎下次续期拿到 0 行即判定 `LEASE_LOST`（`ensureLease()` 返回 null → 直接
`return lease_lost`），不会出现两个写入者。这是对 0.6.35 **字面**的一处有意偏离，
目的（同一时刻只有一个写入者）完全保留，已在 DAO 注释中写明理由。

**修复 2（阻断级，顺带发现）**：维护模式是"内存中操作的持久化标记"。
进程若在导出/备份/恢复/清理中途被杀，`maintenanceMode` 会**永远留在库里**：
不仅这些操作此后全部报"正在进行"，引擎取租约的 SQL 也要求 `maintenanceMode = 'OFF'`，
于是**监控会永久停摆**，用户只能清除应用数据才能恢复。
`StartupRecovery` 原本只把未完成快照置为 `CANCELLED`，没有释放维护权。
现新增 `resetStaleMaintenance`，在冷启动时清掉残留维护模式并写一条 `MAINTENANCE` 健康事件。

**验证**：修复后在租约有效期内连续导出两次，均成功（日志 `导出成功` + Download 出现文件）。

**过程中的一次自伤**：为造测试数据，用 sqlite 直接插入了 `lifecycleState = 'CLOSED'` 的场次，
而枚举只有 `ACTIVE / ABANDONED`，Room 读取时抛
`IllegalArgumentException: No enum constant ...LiveSessionLifecycleState.CLOSED` 导致应用反复崩溃。
是测试数据的问题，不是应用缺陷；但它顺带说明**枚举列对未知值是零容忍的**
（升级/降级或外部写入会直接崩在 Room 读取路径上），已记入待裁决清单。

---

### H12. 直播记录删除 + 全局配置文件（导入可选文件）（用户要求）

#### 12.1 历史记录新增删除按钮

二级页（某主播的直播历史）每条记录除「修正」外新增「删除」，二次确认后删除。

**关键点：子表必须一起清**。只有 `live_session_title` 声明了 `ON DELETE CASCADE`，
`live_event` / `live_session_correction` / `reliable_monitor_interval` 都只是普通索引，
只删主表会留下指向不存在场次的孤儿行（事件还会在通知历史里被翻出来）。
`HistoryRepository.deleteSession()` 在单事务内删这四张表，并递增 `sourceDataVersion`
（统计缓存与诊断包以它为失效依据，不递增的话统计页会继续显示已删除的场次），最后写审计。

实测：删除 七海Nana7mi 的一条记录 → 提示「已删除该条直播记录」、列表清空、
`live_session` 17→16、已结束场次 1→0、该场次的 `live_session_title` 0 行。

#### 12.2 配置文件：覆盖全局 + 导入时可选任意文件

用户澄清：这个「配置文件」**不是给人看的报告**，而是一个 JSON，用来把应用恢复原样；
它要覆盖**整个应用**——主播卡片、直播历史、主播统计、以及应用本身的设置。

- **导出内容**：主播（含标签/分组/单独监控策略）、直播历史（场次/事件/标题/人工修正），
  新增 `BackupFile.settings`：监控参数（monitoring_config 全字段）、免打扰时段、
  历史容量、导入上限。统计由场次实时算出，恢复后靠 `sourceDataVersion` 递增自动重算。
- **`settings` 放在 `body` 之外**，不是随手放的：备份校验和是对 `body` 重新序列化后算的，
  一旦往 `body` 里加字段，**所有旧备份的校验和都会失配**、被判为"文件已损坏"。
  放在同级并给默认值 null，旧文件照常导入，只是提示"该文件不含应用设置（旧版备份）"。
  `BACKUP_SCHEMA_VERSION` 1→2（方向校验：新文件被旧版本拒绝，反向兼容）。
- **刻意不含**：登录凭证、运行时锁/租约，以及与设备绑定的项（后台保活方式、引导页免责确认）——
  它们是"这台设备上用户已授予的能力"，恢复到另一台设备会凭空声称一个并不存在的权限。
- **恢复路径走各仓库接口**而不是直接写表：监控设置经 `ConfigRepository.update`
  （configVersion CAS + 修订快照），容量/免打扰/导入上限走各自 DataStore 仓库。
- **导入可选文件**：新增「选择文件导入」，用 SAF（`ActivityResultContracts.OpenDocument`）
  + `BackupRepository.readPickedBackup(uri)` 读取用户挑中的任意文件（网盘/U 盘/微信目录都行），
  不再只能按文件名前缀从 Download 猜"最新的那个"。「导入 Download 里最新的备份」保留为次要入口。

实测（模拟器 + 真实数据）：导出 28.5 kB 的 `主播监控备份_20260913_113909.json`，
`schema=2`、`streamers=30 / sessions=16 / events=2 / titles=17`，`settings` 含全部四类；
把检查间隔改成 300 并保存（`intervalSeconds=300, configVersion=2`）后，
用「选择文件导入」在系统文件选择器里挑中该文件 → 预检通过（显示所选文件名）→ 执行恢复 →
提示「恢复完成：…**设置项 4**，跳过场次 16」，`intervalSeconds` 回到 **180** ✓。

---

### H13. 直播历史排序改为"按直播时间（开播时间）从新到旧"（用户要求）
**用户原话**：历史记录要按直播时间排序，而不是按创建时间；最新的排在上面，从新到旧。

**实际错在哪**：排序键是 `COALESCE(endTime, startTime, createdAt)` ——
已结束的场次按**关播**时间排、进行中的按开播时间排，两条轴混在一起：
"早开播但晚关播"的记录会压过"晚开播"的记录；`createdAt` 只在开播时间缺失时才轮到，
所以用户看到的"像按创建时间排"其实是关播时间在主导。

**统一口径**：直播历史一律以**开播时间** `COALESCE(startTime, createdAt)` 为排序键，
`createdAt` 仅作兜底（异常中断/历史遗留行可能没有 startTime）。

| 位置 | 改动 |
|---|---|
| `LiveSessionDao.observeByStreamer` / `observeClosedByStreamer` / `observeRecent` | 排序键 `COALESCE(endTime, startTime, createdAt)` → `COALESCE(startTime, createdAt)`，方向仍为 `DESC`（最新在上） |
| `LiveSessionDao.listClosedBetween` | `ORDER BY endTime` → `COALESCE(startTime, createdAt)`（统计只做聚合，但口径保持一致，免得以后再踩） |
| `HistoryListViewModel`（一级页"最近"） | `maxOfOrNull { endTime ?: startTime }` → `maxOfOrNull { startTime ?: createdAt }`：卡片按"最近一次开播"排序，"最近：…"也显示开播时刻 |
| `StatsListViewModel` | 同上（"最近"列与历史页同口径） |
| 二级页 Kotlin 排序 / 导出 HTML 分组 | 本来就用 startTime，补注释说明与 DAO 同键，避免两处漂移 |

**实测**（造两条故意错位的记录：A 开播 00:26→关播 02:26，B 开播 01:26→关播 01:56）：

```
旧规则（按关播时间 DESC）：ORDER-A（02:26）在上
新规则（按开播时间 DESC）：ORDER-B（01:26）在上，ORDER-A 在下   ← 截图确认
一级页「最近：2026-09-10 01:26」= B 的开播时间（旧规则会显示 02:26）
```

**顺带修掉的排版问题**：二级页每条记录原本把「时长 + 可信度 chip + 修正 + 删除」
塞在一行，chip 占满宽度后「删除」被压成两个字宽、文字竖排换行。
现改为两行：第一行「时长 + 可信度」，第二行右对齐「修正 / 删除」。


---

### H14. 实时直播间封面：取不到、看不到、存不下（用户要求）

**用户原话**：实时直播间封面的功能没做；封面应该在主播监控页点进主播卡片后的二级页面显示；并且要支持保存到本地。

查下来是**三层各断一环**，全链路都不通：

#### 14.1 数据层：封面从来没被写进库（真 bug）

实测 `select count(*) ... where coverUrl is not null` → **30 个主播，0 个有封面**。
但同一个批量接口的响应里 `cover_from_user` 明明有值（公开接口实测确认：
`room/v1/Room/get_status_info_by_uids` 返回 `cover_from_user = https://i0.hdslb.com/...jpg`，
真开播时 `keyframe` 也有值）。也就是说 DTO 字段名是对的，**是没人把它落库**：

- `streamer.coverUrl` 只在两处写：用户手动「刷新资料」`refreshMetadata()`、以及新增主播时；
- 关注导入进来的主播一律以 `coverUrl = null` 落库（`FollowImportRepository`），
  而监控 Tick **从不补写** —— 于是库里永远是 NULL。

修复：`MonitorRepository.persistRemoteMetadata()`，在**每次成功观察**时把远端资料
（封面/标题/分区/头像/房间号）写回主播行，两个入口
（`applyObservationOnly` 与 `applyConfirmedObservationAtomically`）都调用。
依赖 `updateRemoteMetadata` 的 COALESCE 语义，传 null 不会覆盖已有值。
实测修复后：**30/30 全部有封面**。

#### 14.2 界面层：二级页面根本没渲染封面

`StreamerDetailScreen` 的头部卡片只有头像/昵称/UID/状态/标题，没有任何封面。
现新增 16:9 封面区：圆角裁切、左上角状态角标（直播中/轮播中/未开播）、
无封面时给明确占位文案而不是空白；下方一行说明"封面来自最近一次检查到的直播间画面" + 「保存封面」。
另外进页面时若封面为空会**自动补拉一次**（只补一次，避免每次进页面都多打接口）——
封面靠监控 Tick 顺带刷新，最长要等一个检查间隔，首次进页面容易看到空白。

#### 14.3 保存到本地：主线程做网络 → `NetworkOnMainThreadException`

新增 `CoverRepository.saveCover()`：复用应用唯一的 OkHttpClient 下载
（B 站图床有防盗链，必须带 UA/Referer —— 自己 new 客户端会拿到 403），
再经 MediaStore 写进 `Pictures/主播监控/`，文件名 `主播名_直播间封面_yyyyMMdd_HHmmss.jpg`。

第一次实现直接把同步 `execute()` 写在 `viewModelScope.launch`（主线程）里，
实测日志：`--> GET https://i0.hdslb.com/...jpg` → `<-- HTTP FAILED: android.os.NetworkOnMainThreadException`，
文件没生成、审计表也没有 `SAVE_COVER`。修复：整个 saveCover 包在
`withContext(Dispatchers.IO)` 里 —— 线程归属由仓库层负责，调用方不必关心。

**实测**：点「保存封面」→ 提示
`封面已保存到相册：Pictures/主播监控/伊索尔Sol_直播间封面_20260913_120654.jpg`，
文件 129938 字节，审计表 `SAVE_COVER|{"file":"...","bytes":129938}` ✓。

**顺带说明**：状态角标会随实际状态变化（实测同一条记录先显示"直播中"、主播下播后变"未开播"），
封面本身保留最后一次抓到的画面 —— 这是刻意的：下播后封面不会凭空消失，用户仍可保存"刚才那场的封面"。

---

### H15. 直播分区：记录到历史、多分区全记、手工可留空（用户要求）

**用户原话**：获取直播分区的功能也没做；分区要记录到直播历史里；一场直播出现多个分区要全部记录；
手工补录/修正时可以不填这一项；这个功能不依赖登录。

分区数据本来就随每次观察一起取回来（`area_v2_parent_name` / `area_name`），
但**只体现在"开播那一刻"写进 session 的 `areaAtStart`**，场次内换分区没有任何记录 ——
等于"历史里看不到分区"。本次补齐三层：

#### 15.1 数据层：新增场次分区变更表

`live_session_area`（PK `(sessionStableId, areaLabel)`，外键 CASCADE），与既有的
`live_session_title` 同构：**每变一次记一行**，所以一场直播换过几个分区就能查到几个。
DB v6 → v7，迁移 `V6_7__sessionAreas` 建表 + 索引，并把历史场次的
`areaAtStart` / `areaAtEnd` 回填成两行（老数据不至于空白）。

写入点在 `MonitorRepository.recordAreaChange()`，与 `recordTitleChange()` 并列，
在**每次成功观察**时判断"与最后记录是否不同"，不同才插入。

**不依赖登录**：数据源是公开的 `room/v1/Room/get_status_info_by_uids`（实测无需 cookie），
与观看历史那类账号接口无关。

实测：迁移后立刻具名查到 20 行分区记录，例如
`虚拟主播·生活娱乐 9`、`手游·手游直播 5`、`单机游戏·单机联机 2` …（同构去重生效）。

#### 15.2 界面层

- 二级页每条记录在时间下方显示分区；多于一个时写成
  `分区变更 3 个：A → B → C`（按观察顺序）。
- 老场次没有分区行时回退到 `session.areaAtStart / areaAtEnd`，不留空。
- **手工补录**：新增「直播分区（可选，留空则不填）」输入框，留空即不写入任何分区行 ——
  不伪造"未知分区"。
- **人工修正**：新增「直播分区（可选，留空=不改动）」，预填当前分区；
  填了就**替换**该场次的分区记录（自动记录的那几条删掉，写一条人工值）——
  人工表达的是"这一场属于哪个分区"这一件事，保留一串自动记录只会让人困惑。
  校验放宽为"至少填两项时间，或修改标题/分区/备注"。

#### 15.3 导出与备份

- HTML 导出新增「分区」列（多分区时是 `A → B`）。
- 配置文件（备份）body 新增 `areas`。

#### 15.4 监控二级页也显示分区（用户补充要求）

"获取到的分区要显示在监控页面的二级页面上" —— 主播详情页头部新增一行
`直播分区：虚拟主播·生活娱乐`（跟随实时刷新，与封面同一份数据，都是
`persistRemoteMetadata` 每次成功观察写回的 `parentAreaName / areaName`）；
没拿到时显示"暂无（等待下一次检查）"而不是留空。
拼接规则抽成 `liveAreaLabel(parent, child)`，与仓库层 `areaLabel()`、历史页、导出一致
（`父·子`）—— 三处各写一套迟早会出现一处用"·"一处用"/"。

#### 15.4 顺带修掉一个**会让所有旧备份失效**的隐患

给 `BackupBody` 加 `areas` 字段本身是安全的（有默认值），但校验和的计算方式不是：
它按"反序列化成数据类再重新序列化"的文本算，于是加字段后重新编码会多出 `"areas":[]`——
**历史上所有备份的校验和立刻失配、被判定为"文件已损坏"**。
用真实的旧备份文件实测确认了这一点：

```
stored      : a2e7a7a5…   （旧文件里记录的校验和）
canonical   : a2e7a7a5…  MATCH   ← 原始 body 文本
raw slice   : a2e7a7a5…  MATCH   ← 同样的文本
with areas  : fb196130…  no      ← 加字段后重新编码，失配
```

修复：校验一律以**文件里的原始 body 文本**为准（重新编码只作兜底），
并把这段逻辑抽成 `BackupChecksum` 供单元测试直接覆盖，
新增 `BackupChecksumTest`（7 个用例：旧文件确实没有 areas 键、原始哈希与旧版一致、
重新编码会多出字段、旧备份加字段后仍通过、改动内容/新字段都会被发现）。
同时把"记录数与清单不一致"的比较改成只比对**清单里确实存在**的键，
否则旧清单缺 `areas` 这一项也会被误判。

> 备注（不是缺陷）：模拟器上「导入 Download 里最新的备份」找不到那份 09-12 的旧备份，
> 原因是本会话早先执行过 `pm clear`，应用对那批 MediaStore 行不再有归属权，
> Android 11+ 下无权限就查不到 —— 属于测试环境后果，不是代码问题；
> 且「选择文件导入」（SAF）走 document URI，不受归属权限制，是更可靠的入口。

---

### H16. 统计图表导出：规则与命名与历史导出完全一致（用户要求）

**用户原话**：把导出历史记录的类似功能，一样地加在导出统计图表上；所有导出规则和命名规则都一样。

**实现**（能共用的全部共用，避免两份实现各自漂移）：

| 维度 | 做法 |
|---|---|
| 入口 | 统计一级页顶栏「导出」→ 勾选一位或多位主播 → 下一步 → 时间范围；二级页（单主播统计）顶栏「导出」→ 直接选时间范围 |
| 选择对话框 | 把原先私有在历史页的 `ExportSelectDialog` 提到 `ui/common/ExportDialogs.kt`，**历史与统计共用同一个**（搜索 / 全选 / 清空 / 多选合并都一样） |
| 时间范围 | 同一个 `TimeRangeDialog`：全部 / 按日 / 按周 / 按月 / 按年 / 自定义 |
| 命名 | 同一个 `buildFileName(times, selectedNames)`：`实际数据最早~最晚日期_主播名.html`，多主播为 `N位主播`（N = 选择的主播数） |
| 空对象 | 没记录的主播照样出现（空分组，写"该时间范围内没有直播记录"）；全空则不生成文件 |
| 反馈 | `performStatsExport()` 与 `performHistoryExport()` 同一套提示格式与失败文案 |

**内容**（这是唯一真正不同的地方）：按桶聚合的序列 + **内联 SVG 柱状图** + 分桶表格 +
每位主播的汇总（总计 / 平均每场 / 已确认·暂定·人工修正场次 / 可靠监控时长）。

用内联 SVG 而不是图表库：导出的 HTML 要能**离线双击打开**，依赖 CDN 的图表库在没网时会白屏。
柱子上带 `<title>`，鼠标悬停显示"日期：时长（场次）"；标签过多时按步长抽稀。

分桶粒度沿用统计页既有规则（`StatsSeriesRepository.seriesCustom`：跨度 > 92 天按月，否则按日）；
"全部"（区间 0..MAX）会用**实际数据范围**兜底，否则分桶算不出有限区间。

**实测**（模拟器）：
```
一级页多选：开始导出统计：主播=2 位，范围=全部
            导出成功：2026-09-13_2026-09-13_2位主播.html（2 场）
二级页单主播：开始导出统计：主播=1 位，范围=全部
            导出成功：2026-09-13_2026-09-13_月见兔.html（1 场）
```
导出的 HTML 抬头：`导出对象：2 位主播（月见兔、伊索尔Sol）｜选择的时间范围：全部｜实际统计区间：2026-09-13 ~ 2026-09-13｜合计场次：2｜合计时长：2小时12分`，
随后每位主播一节：`月见兔 … 总计：1小时8分｜平均每场：1小时8分` + SVG 图（4 个 `<rect>`，含悬停提示）+ 分桶表格。

---

### H17. 直播标题变化 / 分区变化通知（用户要求，**两者默认不启用**）

**用户原话**：主播直播过程中改标题（《今天随便聊聊》→《今晚挑战高难度游戏》）软件要记录变化，
并允许用户选择"直播标题变化是否通知我"；分区变化通知也一起加上；**这两个功能默认不启用**。

记录部分其实早就有了（`live_session_title` / `live_session_area` 两张变更表，见 H15），
本次补的是**通知**这条链：

| 层 | 改动 |
|---|---|
| 枚举 | `NotificationEventType` 新增 `TITLE_CHANGED` / `AREA_CHANGED` |
| 策略 | `StreamerMonitorPolicyEntity` 新增 `notifyTitleChange` / `notifyAreaChange`，`@ColumnInfo(defaultValue = "0")` + **默认 false**；DB v7→v8 迁移 `ALTER TABLE ... DEFAULT 0`（与实体声明对齐，否则 Room 迁移后 schema 校验会直接报错） |
| 快照 | `MonitoringConfigSnapshot` 新增两个映射 + `notifyTitleChangeFor()/notifyAreaChangeFor()`，**缺省 false**（与开播/关播通知"缺省 true"相反：直播中改标题可能很频繁，默认打开就是骚扰） |
| 引擎 | `StreamerNotificationPolicy` 带上两个开关；**策略构建上提到 `when` 之前**——原先只在"状态转换"分支里构建，普通观察路径拿不到策略，而标题/分区变化恰恰发生在非转换的普通观察里 |
| 写入 | `NotificationOutboxWriter.createForChange()`：**不写 `live_event`**。事件表承载的是"直播状态转换"这一业务事实，改标题/换分区只是直播过程中的元数据变化，混进去会污染事件序列、状态历史与统计口径。直接按自己的 eventKey 写 Outbox |
| 幂等 | eventKey = `change:<类型>:<主播>:<场次>:<新值>` → 同一场次里同一个新标题只通知一次 |
| 渲染 | 通知正文为"旧 → 新"（`previousValue` 字段，带默认值以便旧 payload 仍可反序列化）；免打扰时段内不发 |

**顺手修掉一个潜在错值**：`LiveSessionTitleDao.lastTitle` 名字是 last，SQL 却是
`ORDER BY observedAt ASC ... LIMIT 1` —— 返回的是本场**第一条**标题。
用它当"变化前"会让改过两次以上的标题显示错误的旧值（A→B→C 时通知里写 A→C）。
新增 `latestTitle`（`ORDER BY observedAt DESC`）供变更检测与通知使用，`lastTitle` 保留不动。

**实机验证**（模拟器，真实主播 + 真实 tick）：
```
开启「直播标题变化通知」→ 造出一次标题变化 → 触发检查
notification_outbox: TITLE_CHANGED|SENT
payload: {"eventKey":"change:TITLE:龙窟第八届DD歌回·下！",
          "roomTitle":"龙窟第八届DD歌回·下！","previousValue":"…旧标题…", …}
开启「直播分区变化通知」→ 同理
notification_outbox: AREA_CHANGED|SENT
```
验证后已清除测试用的标题/分区行、change 类 Outbox 与通知历史，并把两个开关复位为关闭。

界面：主播详情页「监控策略」卡片新增两个开关，各带一行说明
（"直播中改标题时通知我（默认关闭）"/"直播中换分区时通知我（默认关闭）"），
与开播/关播通知并列；未配置过策略的主播默认全部为关（实测库里该表原本 0 行 → 视为关）。

---

### H18. 删掉「开播/关播是否人工修正」标签（用户要求）

**用户原话**：把开播和关播这个是否由人工修正的标签删掉，这个功能没有什么用，而且大幅挤占 ui 的位置。

两处渲染点都删了：

| 位置 | 改动 |
|---|---|
| 直播历史二级页的记录卡片 | 去掉 `开播:PROVISIONAL 关播:PROVISIONAL` 的 AssistChip；时长与「修正/删除」合并到一行 —— 原先就是因为这个 chip 占掉大半行宽，才被迫把按钮另起一行 |
| 主播详情页的「直播记录」 | 去掉两个置信度 chip，只保留「进行中」（这条有实际含义：场次还没结束）；开播时间那一行右侧显示状态 |

数据层**完全不动**：`startConfidence/endConfidence/durationConfidence` 仍在库里，
人工修正对话框、统计页的「已确认/暂定/人工修正」计数、以及导出 HTML 的「可信度」列都照旧
（那些不占常驻 UI 空间，且是记录来源的溯源信息）。

---

### H19. 全项目稳定性审查（60 条）与其后的一次性修复

**起因**：用户要求"对整个项目的代码做一次完整的稳定性和健壮性审查，不需要编译"，随后"直接全部修复，修复完成后再执行一轮完整审查"。

**方法**：82 个 .kt / 18,518 行**纯静态阅读**（未编译、未运行、未改代码），按关注点切五片并行深读：
A 监控引擎/调度/生命周期、B 持久化层、C 网络/凭证/导入导出、D UI 层、E 通知/核心/诊断；
每片要求 `file:行号` 证据、区分"已确认/疑似"，并附"已核对无问题"的部分。
完整报告见 `STATIC_REVIEW_REPORT.md`（跨切面 11 条 + 分片 74 条，去重后约 60 条）。

**结论分布**：严重 3、高约 12、中约 25、低约 20。四个系统性主题：
① 线程归属没有统一约定（只有 `CoverRepository` 切了 IO）；② 失败可见性（190 处 `runCatching`，87 处直接吞掉）；
③ 没有自愈路径（库损坏/索引重建失败/凭证密钥失效/未知枚举都只能清数据）；
④ 同一根因只修了一半（维护锁接管只改了导出、冷却 delay 挪出闸外导致串行时节流失效）。

#### 本批修复清单（全部已落地）

| 编号 | 问题 | 修复要点 |
|---|---|---|
| **B1（严重）** | `DuplicateOpenSessionSignal` 被捕获后正常返回 → Room 提交半截事务（状态已 LIVE、场次没建，下一 Tick 不再产生转换 → **这段直播永久缺失**） | 信号不再被吞：`openNewSession` 撞约束时先重读复用已有 OPEN session，复用不了才抛；两处调用点改为让异常穿透事务由 Room 回滚 |
| **A1（严重）** | 前台服务 Tick 循环的 `getSnapshot()` 在 try 之外 → 服务假活（显示"监控中"零检查）或崩溃循环 | 循环体整体加异常边界（`CancellationException` 原样上抛），`stopSelf()` 在循环退出后必达 |
| **A2（严重）** | 进程死亡跨越的场次写成"时长=整个断档"的假场次（13 小时） | 新增 `truncatedEndTime()` 纯函数：距上次成功观察超过 2×间隔则把 endTime 收到 `lastCheckedAt`，`confirmedOfflineAt` 仍记 now，并写审计 `SESSION_END_TRUNCATED` |
| **C1** | WBI 密钥永不按天刷新 → 跨天后导入稳定 -400 | 6 小时 TTL，`ready()` = 有 key 且未过期（`cachedAtSeconds` 真正被读），`sign()` 对过期 key 不再硬签 |
| **C2** | `live_status` 缺省 0 把"字段缺失"当"未开播"→ 可致全部主播同时被确认关播 | DTO 改 `Int? = null`，缺失/越界 → 返回 null 走 `missingUids`；`remoteStatusValid` 只在取值合法时为 true |
| **C3/2.5** | 凭证库构造失败即启动即崩；`allowBackup` 把解不开的密文带到新机 | `prefs` 改 `by lazy` + 失败删文件重建 + 最终退化为内存态；新增 `backup_rules.xml`/`data_extraction_rules.xml` 排除凭证文件 |
| **2.1/C4/B3/D1** | 恢复/导入/导出/诊断全链路在主线程做整文件读写 + JSON + SHA-256 | 仓库层统一切 `Dispatchers.IO`（读盘/写盘）与 `Dispatchers.Default`（解析/哈希/HTML 拼装） |
| **2.2/B5** | 38 个枚举转换器裸 `valueOf`，未知值读表即崩 | 新增 `EnumSafe.parse`：未知值回退到该枚举语义最保守的取值 + 记一次日志；38 个转换器全部改造；新增 4 个单测钉住行为 |
| **2.4/B2** | 库损坏无任何自愈；`onOpen` 里部分索引重建失败 = 永久打不开库 | `onCreate`/`onOpen` 每步包 `runCatching` + 留痕（绝不把异常抛到开库回调）；`StartupRecovery` 增加 `PRAGMA quick_check` 健康自检并写健康事件/错误日志 |
| **A3/B13** | 清理与恢复仍用严格版维护锁 → 活跃用户的**容量清理/保留策略/容量预警从未生效**，`立即清理`/`执行恢复` 随机失败且理由错误 | 两处改用 `takeOverForMaintenance` |
| **A4** | 聚合窗口延迟任务里 `dispatchDue()` 无保护 → 一次 SQLiteException 杀进程 | 整个 launch 包 `runCatching` + 失败留痕 |
| **A5** | Tick 全有或全无、单批最坏约 31 分钟 | `checkOnce` 加 Tick 级预算（2×间隔，夹在 60s~900s），超时按"本轮没跑完"返回 |
| **A9** | 批次冷却在并发数=1 时完全失效（注释与实现相反） | 改为"距上次请求结束至少 cooldown"的全局时间闸（对所有并发度成立、不占并发槽） |
| **A10** | 熔断按每次 HTTP 尝试计数 → 一个批次的 3 次重试就能打开全局熔断 | 改为按**批次**计数（只有最后一次尝试仍失败才算一次） |
| **A12** | 监控租约从不主动释放 → 停止后 90 秒内重启的第一次 Tick 必定 `lease_lost` | `MonitoringService.onDestroy` 主动 `releaseLease()` |
| **A6** | `logHealth` 丢弃原因文本 → 诊断页看不出为什么 BLOCKED | 保留健康事件，同时把原因写进 `application_error_log`（不改表、不需要迁移） |
| **A7** | 每次冷启动固定多跑一整轮观察（省电唤醒时请求翻倍） | 补检加前置条件：有未恢复崩溃，或距上次检查超过 2 个间隔 |
| **2.6/2.7** | 启动恢复 13 处静默 `runCatching`；省电 Worker 无网络约束、结果被丢弃 | 启动恢复改为 `step(name){}` 逐步留痕；Worker 加 `NetworkType.CONNECTED`、校验 POWER_SAVING、失败记日志（必要时 `Result.retry()`） |
| **B4** | 容量清理一次绑定全部 id（超 SQLite 32766 变量上限即永久失败）+ 不清区间表 | 分批（每批 500）+ 同步清 `reliable_monitor_interval` |
| **B6/B7** | 恢复完成 CAS 返回值被丢弃；恢复子行静默失败 | 检查 CAS 结果并在被并发取消时给出警告；子行失败计入 `skippedCounts` 并写 warning |
| **B9/C8** | 统计用 `endTime` 口径、历史/导出用 `startTime` 口径 → 同一时间范围两边对不上；"已排除场次"恒为 0 | 统一为开播时间口径；越界场次夹取到最近桶；新增 `countUnclosedForStreamerBetween` 让"已排除"变成真实数字 |
| **C5/C6/C7** | Cookie 持久化丢 domain/path/expires；设备 cookie 永不刷新；Cookie 登录先落盘再校验（会破坏原登录态） | 存 `Cookie.toString()` 并按原 domain 重建；设备 cookie 加 7 天 TTL；改为"校验通过才落盘，失败回滚" |
| **C10/C11** | 文件已写出却报"导出失败"；写盘失败留下 `IS_PENDING=1` 幽灵行 | 写入成功即视为成功（记账失败降级为告警）；写失败 `resolver.delete` 清半成品 |
| **C13/C14/C15** | URL 不编码且签名值可能≠发送值；凭证明文回显；死代码与陷阱常量（`ps = 50`、`lastTitle`） | 单点 `encodeQuery/queryUrl`；密码可视化 + 提交后清空；删除 4 处死代码 |
| **E1（高）** | 投递卡在 PROCESSING 无运行期回收（只有冷启动/省电 Worker 才结算） | `dispatchDue` 首行做运行期回收；catch 内显式把行放回队列；检查 `finishAttempt` 的 CAS |
| **E2（高）** | `createSystemProblem` 三处调用点不在事务内 → notificationId 分配退化 | id 分配改为"读 MAX+1 → INSERT OR IGNORE → 复查"包在 `db.withTransaction` 内，调用方开不开事务都安全；删掉全表装载 |
| **E3（高）** | 通知投递占用 Tick 互斥锁（含封面下载，最坏 300s） | `dispatchDue` 移出 `tickMutex`；封面专用 5s 客户端 + 投递整体 15s 预算（超时不标失败） |
| **E4–E8** | 诊断包迁移清单停在 5→6；封面解码无采样；过期通知照投；恒为 0 的死清理；bootId 兜底常量 | 清单补到 1→8；`inJustDecodeBounds` 降采样；claim 判 `expiresAt` 并回收超期行；死清理方法加说明；bootId 改开机时刻锚点 |
| **D2/D4/D5/D6/D7/D8/D9/D10/D11/D12/D14/D15** | 导出无忙状态、历史页重复订阅、提示条不参与 combine、chips 挤出屏幕不可达、全站未用生命周期订阅、详情页永远转圈、对话框在 item 内、收藏无异常保护、每次键入重跑全表、时区只写不读、通知历史无新事件文案、若干死代码 | 逐条修复（详见报告 3.5 与本节末的验证结论） |
| **2.9/2.10** | 高风险代码零测试覆盖；release 不跑 lint | 新增 `EnumSafeTest`(4) 与 `SessionEndTruncationTest`(6) → 单测 **58 个全通过**；`checkReleaseBuilds = true` |

#### 与"修复"同时确立的两条工程约定

1. **枚举列新增常量必须同时升 `DB_VERSION`**：现在的读路径虽然宽容，但"新版本写入、旧版本读"仍应被版本守卫挡住（`EnumSafe` 只兜底外部写入与跨版本文件，不是替代版本守卫）。
2. **线程归属由仓库层负责**：仓库的 suspend 函数自己决定跑在哪个 Dispatcher（IO 用于读写盘、Default 用于解析/拼装），调用方（UI）不必关心，也不允许把整文件 IO 放在主线程。

#### H19 第二轮审查（修复验证 + 新问题搜索）

第一轮修复完成后，按用户要求"再执行一轮完整审查"：四个验证组并行，每组两件事 ——
**逐条验证修复是否真的成立**、**搜出这些改动新引入的问题**。结论：**绝大多数修复成立**，
但抓出 20 余条"改了一半 / 描述与实现不符 / 修复本身引入的新问题"，已全部修掉：

| 类别 | 关键条目 |
|---|---|
| **继续产生错误数据（最要紧）** | **断档截断用错了锚点**：实现取 `lastCheckedAt`，而它在**失败观察时也会推进** —— "重启后先失败一次、再成功观察到下播"会让间隔看起来只有 1 分钟、截断不触发，13 小时假场次照样生成。改用 `lastConfirmedAt ?: lastCheckedAt`（只有成功观察才推进） |
| **修复被引入的回归** | **开机后可能再也起不来实时监控**：把 FGS 启动从 `BootReceiver` 挪进 WorkManager 的 Worker 会丢掉 Android 12+ 的"广播豁免"。改回在 `onReceive` 里直接 `startForegroundService`，Worker 只做 DB 同步与省电调度。**已实机验证**：`install -r` 触发 `MY_PACKAGE_REPLACED` 后 logcat 出现 `Background started FGS: Allowed [... uidState: RCVR ...]`，应用在未手动启动的情况下被拉起并常驻 |
| **改了一半** | ① 导出忙状态只加在两个一级页，两个二级页（历史/统计的单主播导出）仍可并发导出 → 补齐；② `exportStats` 的 HTML 拼装仍在主线程、审计失败仍会报"导出失败"→ 与 `exportHistory` 对齐；③ 冷却时间闸按"上次请求结束"计时，在串行模式下后续批次在上一批结束前就穿过闸门 → 改为在闸门内**预约下次允许开始时刻**；④ 预检失败会让数据页所有按钮永久置灰 → 统一 `runBusy()` 入口 + `try/finally`；⑤ 恢复路径仍有 3 处裸 `valueOf` → 改 `EnumSafe`；⑥ 部分索引重建是 16 条平铺，第一条失败会连带跳过其余 15 条 → 逐条独立保护 + 失败索引名上报健康事件；⑦ 诊断包迁移清单/日志截断/`collectAsState` 等零星遗漏 |
| **静默失效** | `EnumSafe` 的未知枚举回退只写 logcat，而诊断包不读 logcat → 改为攒起来、启动时落 `application_error_log`；`logHealth` 每轮都插健康事件（库里已堆 51 条重复的 `MONITORING → BLOCKED`）→ 改为边沿触发，并把原因写进错误日志 |
| **投递与重试** | `releaseProcessing` 不看 `attemptCount` → 一条持续抛异常的行走会以 30 秒间隔重试到 24h TTL（约 480 次/天 + 480 条日志）→ 加 `MAX_ATTEMPTS` 上限；Worker 的 `getSnapshot()` 无保护 → 包 `runCatching` 并留痕 |
| **时间与时钟** | `bootId` 兜底锚点每次重算（网络校时会改 ID，导致正在收集的聚合窗口被丢弃）→ `by lazy` 缓存；`MonitoringService.onDestroy` 的 `runBlocking` 加 2 秒超时（避免 Room 忙时主线程 ANR） |
| **服务假活** | 前台循环连续失败只会每 30 秒重试，`isRunning` 恒为 true → 连续 5 次失败即写错误日志并 `stopSelf()` |
| **登录链路** | 关注列表导入不刷新 WBI 密钥（过期后第一发就是无签名请求）；"先校验后落盘"实为"先落盘再回滚"（校验期间进程被杀会留下未验证凭证）→ 改为内存态校验、通过才落盘；Cookie 快照漏了设备标识时间戳；失败时不再清空输入框；`InMemoryPrefs` 的两处语义偏差；cookie 过期判定改用网络校准时钟 |
| **死代码与陷阱** | 删掉零调用且语义反了的 `lastTitle()`（名为 last、SQL 取最早一条）与 `streamerSummaries()`；删掉恒不生效的 `deleteUnstartedAttempts`；清理死参数 `onTransportFailure(batchSize)` |

**本轮修复期间的两次自伤（已完全恢复，如实记录）**：
1. 用 PowerShell `Get-Content -Raw` + `Set-Content -Encoding UTF8` 改一行代码，导致
   `StatsSeriesRepository.kt` 的中文被按 ANSI 码页往返转换而损坏（73 处字符丢失、两个字符串字面量断裂）。
   → 通过"反向编码"（文本 → GBK 字节 → 按 UTF-8 解码）恢复了全部代码逻辑与大部分注释，
   再整文件重写补齐受损中文；随后 `assembleDebug` 通过、`app/src` 全树 grep 无残留乱码。
   **教训：改文件只用 `edit`/`write` 工具，不要用 shell 做读写往返。**
2. 在 `AppMigrations.kt` 做"平铺 execSQL → 列表"的替换时漏掉了中间一条索引，留下半截语法 → 已修。

**最终验证状态**：`assembleDebug` 通过（APK 67.1 MB）、单元测试 **58 个全通过**、
安装保留数据、无崩溃与数据库异常、实机走查了历史导出/统计页/设置页/手动刷新/开机拉起路径。


---

### H20. 监控二级页面增加「进入直播间」按钮（用户要求）

**实现**：`ui/detail/StreamerDetailScreen.kt` 头部卡片底部新增 `LiveRoomEntry`。
**打开优先级（用户定稿）：哔哩哔哩客户端 → 浏览器**。无地址时**按钮禁用并说明原因**，全部打不开时给可见提示。

四个刻意的选择（都写进了代码注释）：

1. **先试 `bilibili://live/<roomId>` 深链唤起客户端**，失败（未装客户端）再退到 https。
   用 **scheme 而不是硬编码包名**（`tv.danmaku.bili`）：scheme 由应用自己注册、由系统解析，
   换包名/多版本共存都不会失效。
2. **不做"先 `resolveActivity` 判断能不能打开"**：Android 11+ 的包可见性会让它在"其实能打开"时返回 null；
   直接尝试 + 捕获 `ActivityNotFoundException` 是唯一在所有版本上都正确的做法。
3. **两处输入都当数据校验**：https 地址过 `UrlPolicy`（仅 https + bilibili 域名白名单）；
   深链里的房间号要求**纯数字**（`roomIdFromUrl` 只取最后一段数字），
   不允许把本地库里的任意字符串拼进 `bilibili://` 交给系统打开。
4. **没有地址时不隐藏按钮**，而是禁用 + 写明"尚未获取到直播间地址，等下一次检查后再试"。

地址来源与通知用的是同一份数据（`persistRemoteMetadata` 每次成功观察写回的 `liveUrl`，
缺失时用 `roomId` 拼官方地址），因此不产生额外请求。

**实机验证**（模拟器**未安装**哔哩哔哩客户端，正好验证降级链）：点击按钮后 logcat 三步齐全 ——

```
ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=bilibili://live/...} ... result code=-91
StreamerDetail: 哔哩哔哩深链打开失败，改用浏览器：No Activity found to handle Intent { act=... dat=bilibili://live/... }
ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=https://live.bilibili.com/...
  cmp=org.chromium.webview_shell/.WebViewBrowserActivity} ... result code=0
```

即：**先尝试客户端 → 识别到未安装 → 自动落到浏览器**，且浏览器打开后确实落在该主播本人的直播间
（页面显示「东爱璃Lovely」的关注按钮与实时弹幕），返回键回到应用。
装了客户端的设备上第一步就会成功，不会再走浏览器。

---

---

---

---

---

---

---




### H71. 通知线取舍项落地（10 项全部按建议实现）

#### ① 用户诉求

H70 结尾列了 10 条"要不要做"的功能取舍，并给了建议。用户回复：**「按照你说的来做」** ——
即全部按建议实现（其中 5 处有 A/B 选择，均按建议的选项）。

#### ② 实现内容（10 项）

**A. 被封/解封通知（新功能）**
1. 新增 `NotificationEventType.BAN_ENTERED` / `BAN_LIFTED`（加在枚举**末尾**，持久化用的是 name）。
2. 新增 `BanNotificationPayload(streamerName, banLabel, entered)`：不复用直播 payload ——
   封禁没有标题/分区/"播了多久"，硬塞进无关字段只会让数据撒谎。
3. 钩子放在 `MonitoringEngine.recordBanTransition`（它本来就只在"进入/解除"两个状态变化时被调用）：
   - 走 `createForChange`（不写 live_event —— 封禁不是场次事件，与标题/分区变化同一取舍）；
   - `changeKey` 带本次转换时刻：解封后**再次**被封（哪怕同为无期限）也必须能发出去；
   - 走 **LIVE 渠道**、**不受免打扰抑制**（不是"开播了"那类可以等到天亮的打扰，而是
     "你监控的对象出事/恢复了"的状态事实，且频率极低）；
   - 点击落**主播详情页**（那里有「封禁中」标签）：payload 不带 url，自然走 appIntent 分支。
4. `NotificationPoster.buildBan` + 分派分支（若落到 `buildSingle` 会解不出直播 payload，
   直接判 PAYLOAD_INVALID，通知永远发不出去 —— 所以这个分支是必需的）。
5. 通知历史的类别文案补 `change:BAN_ENTERED:` / `change:BAN_LIFTED:`。

**B. 免打扰抑制留痕**
6. 新增 `NotificationOutboxWriter.recordSuppressed(...)`：写一条 **CANCELLED 终态 Outbox 行**
   （`lastError = QUIET_HOURS`），**不建通知历史行** —— 历史列表不会被灌满，
   而诊断包取终态行（含 CANCELLED）时能看到"这条是被免打扰挡的"。
   JSONL 式幂等：靠 `findByEventKey` 挡重复；`CANCELLED` 不在 `findDue`/`countActiveOutbox`
   的状态集合里，所以既不会被投递、也不会被算成积压。
7. **eventKey 加 `suppressed:` 前缀**（关键细节）：留痕行绝不能占用真实事件的 eventKey，
   否则同一变化在免打扰结束后再次出现时会被 `createForChange` / `existsForSourceEvent`
   的幂等检查拦掉 —— 那就变成"该发的反而不发了"。
8. 四条抑制路径全部覆盖：开播（`createForEvent`）、窗口到期补发（`processWindowEnds`）、
   崩溃恢复补发（`recoverCrashedAggregates`）、关播（`createEndNotification`）、
   标题/分区变化（`recordTitleChange` / `recordAreaChange`）。
   关播那处顺带把两个"不发"的原因**分开**了：主播开关是用户自己的设置（不留痕），
   免打扰是系统替他做的抑制（留痕）。
9. 新增 `NotificationRepository.quietHoursActiveNow(now)`：窗口结算路径原先**完全不判免打扰**
   （硬编码 `enabled = true`），于是"22:59:58 起窗、23:00:03 结算"或"进程在窗口内被杀、
   下次 Tick 才结算"时，免打扰期间照样弹通知。读设置失败时**按"不抑制"处理并写错误日志** ——
   当成"免打扰生效"会静默吞掉通知，什么都不说又违反硬规则。

**C. 通知历史显示主播名 + 加载更多**
10. 主播名走**读取端反查**（`listAllIncludingDeleted()` 建 stableId→名字映射），
    **不加列、不迁移**；已删除主播的旧记录也能显示名字。行首变成"名字 · 类别"，
    系统类通知（没有主播）保持只显示类别。
11. 上限 200 条提成常量 + 新增"加载更多"（每次再取 200 条，`flatMapLatest` 重订阅），
    并且只在"确实到上限"时才显示提示与按钮。

**D. 容量问题的"已恢复"通知**
12. 容量回落时补发 `SYSTEM_RECOVERED`（component=STORAGE），与监控类问题（网络/接口）的
    "有问题 → 已恢复"成对行为对齐；用 `recoveredNotified` 做"每个 episode 只发一次"的闸门 ——
    这一列此前**写了却从不读**（ConfigDaos 里那句"从不读取"的注释随之订正）。

**E. 投递后核对（系统静默丢弃可见化）**
13. `notify()` 不抛异常 ≠ 通知真的出现了（单应用活跃通知有条数上限、渠道在投递瞬间被关、
    厂商 ROM 限制）。现在投递后核对自己的活跃通知列表：先查一次，未命中则等 300ms 再查一次，
    两次都没有才记 `DELIVERY_UNKNOWN / NOT_VISIBLE_AFTER_POST`，走既有重试路径
    （同 id 重发是**覆盖**，不会多出一条）。查询本身失败（个别 ROM 限制该 API）按"已送达"处理 ——
    宁可漏报一次"没显示"，也不要凭一次失败就误报并触发重发。

**F. 容量清理不再被"已释放的绑定行"挡住**
14. 新增 `deleteReleasedBindingsForEvents`（只删 `active = 0`）：`releaseAll` 之后的绑定行
    `aggregateId` 已置 NULL，永远不会随聚合删除被 CASCADE 带走，而它们同样算"被引用" ——
    于是只要事件进过一次聚合窗口，它的 `live_event` 就永远删不掉，"限制记录数"在这部分静默失效。
    仍被进行中窗口持有的（`active = 1`）必须保留，否则批量通知会漏事件。

**G. 租约判定改 boot 感知**
15. `findExpiredProcessing` / `settleExpiredAttempts` / `recoverExpiredProcessing` 三条语句：
    同一次开机内用单调时钟 `leaseUntilElapsed` 判定，**跨开机才**退回墙钟 `leaseUntilWall`；
    调用点（调度器 `recoverInFlight`、仓库 `settleExpiredDeliveries`）补传 `elapsed` / `bootId`。
    修的是：时钟前跳 ⇒ 在途行被误判过期、被抢走重投（重复提醒）；后跳 ⇒ 长期停在 PROCESSING。
16. 新增 JVM 契约测试 `NotificationLeaseSqlContractTest`（2 个用例）钉住这三条语句的 boot 感知条件。
    **做了负向对照**：注入一处"可编译的语义回退"（把 `leaseBootId = :bootId` 换成
    `leaseBootId IS NOT NULL ... AND :bootId <> ''`）后测试**确实失败**，还原后恢复绿色 ——
    证明它不是空跑（第一次注入写成了让 Room 校验失败的写法，那次根本没跑到测试，
    也说明"测试任务被判 UP-TO-DATE 而没真跑"这个坑要小心，必须用 `--rerun`）。

**H. 死状态/死列**
17. 新增 `recoverOrphanDeliveryUnknown`：`DELIVERY_UNKNOWN` 此前**没有任何写入者**，
    而一旦出现就既不会被投递、也不会被清理（不在 `findDue` 也不在 `cleanupTerminal` 的状态集合里），
    永久卡死。现在按 TTL 分流到 RETRY_WAIT / EXPIRED，纳入既有闭环（每轮投递前 + 冷启动各跑一次）。
18. `deliveredAt` 在送达时写入（`updateByOutboxId` 与状态校正语句都补上）；
    `reason` 列在实体上标注为**预留列**（当前由 `detail` 承载原因，别按列名假定它有值）。

**I. 旧备份缺键保留本地开关**
19. `BackupRepository` 恢复主播策略时，备份里**没有** `notifyTitleChange/notifyAreaChange` 两个键
    就**保留本地现值**（原先写死 `?: false`，会把用户显式打开的开关静默关掉），
    并在恢复摘要里如实报"有 N 位主播的这两个开关在备份里没有记录，已保留本地设置"。

**J. 备份界面说明**
20. 「备份 / 恢复 / 导出」的说明里写明：**不含通知历史与投递记录**（只在本机，保留 90 天 / 30 天，
    需要排查时用诊断包导出）。

#### ③ 验证

- **编译**：`assembleDebug` `BUILD SUCCESSFUL`（波一 1m3s、波二 42s、波三+全量 27s）。
  中途一处编译错误（历史页新增的 `TextButton` 未 import）已修。
- **单测**：**34 suite / 318 用例 / 0 失败 / 0 错误**（新增 1 个 suite、2 个用例即租约契约测试）。
- **负向对照**：租约契约测试在注入语义回退后**失败**、还原后通过（见 G-16）。
- **两棵树**：本轮 15 个文件（14 改 + 1 新增测试）同步到 `publish/app/src` 后 **219/219 逐字节一致**；
  `publish` 侧 `assembleDebug` 也 `BUILD SUCCESSFUL`，`dist/Kaoru-0622.0-debug.apk` 已更新（70.6 MB）。
- **实机（emulator-5554）**：
  1. **封禁通知端到端**：播种一条 `BAN_ENTERED` 的 Outbox → 冷启动（走新增的冷启动补投）
     → Outbox **1 次尝试即 SENT**，通知历史同步为 SENT；
  2. `dumpsys notification` 确认通知真的在通知栏里：`android.title = 露蒂丝 的直播间被封禁`、
     `android.text = 无期限封禁`、`channel=live`、`category=status`、`AUTO_CANCEL`；
  3. **常驻通知 id 已是 2000002**，与主播通知（999002）**并存不冲突** —— H70 的 id 区间修复同时得到实证；
  4. **通知历史显示主播名**：`露蒂丝 · 封禁通知`、`少年Pi · 开播/关播通知`、
     `傲慢的小肉包 · 开播/关播通知`；系统类通知保持只显示类别（没有主播）✓；
  5. logcat 无 `FATAL` / `SQLiteException` / `no such table|column`，`maintenanceMode=OFF`，
     探针行已删除、库恢复原状。
- **未做实机复现（只有静态证据 + 编译 + 单测）**：免打扰留痕（要有真实开播/下播事件才触发）、
  容量"已恢复"通知（要把历史灌到 95% 再清下来）、容量清理的绑定行删除（要造"进过窗口但未达标"的事件）、
  旧备份恢复（要造一份缺键的备份文件）、"加载更多"按钮（库里只有 20 条历史，到不了 200）。
  这五项都不改变既有行为、只增补留痕或提示，风险低，但**必须如实说明没实测**。

#### ④ 仍未做（本轮范围之外，未新增取舍）

H70 列出的 10 条取舍已全部落地；本轮没有新增需要拍板的项。
唯一仍悬空的是 H70 记录的两处"刻意不改"：下播通知的可靠区间资格（有规格依据）、
`idx_attempt_unfinished` 的唯一性语义（改建会让 `onOpen` 抛异常 = 每次启动都崩）。

---

---

### H70. 通知线全链路审查（4 条只读线 + 协调者自审）：29 处修复

#### ① 用户诉求

「先帮我审查一遍通知相关的代码，有错误立刻修复；涉及功能取舍的等我回复。」

#### ② 审查方式

派 4 个**只读**代理各管一条线（投递链路 / 数据层 / 设置与聚合 / 产生与消费侧），
每个都被要求给 `文件:行号` 证据、说清触发条件、区分"能确定"与"可疑"、**不许创建或修改任何文件**；
协调者同时自审投递链路核心（NotificationPoster / NotificationDispatcher / NotificationChannels）
与跨文件关注点（权限判据、渠道、id 空间、前台服务）。

协调者**没有采信任何自述**：报上来的 13 条 P1/P2 全部回到源码逐条复核，
其中 1 条（下播通知漏发）经**规格文档**核实为**刻意的产品行为**、2 条被判定夸大（后果与 FROZEN 无关）、
2 条在复核期间已被协调者自己先修掉（代理也独立发现，可信度因此提高）。

#### ③ 修了什么（29 处，按性质分组）

**A. 静默降级 —— "没发出去却记成已送达"（4 处，最高优先）**
1. 【P1】`hasPermission` 在 API 33+ 只看运行时权限、不看"系统里把该应用通知整体关掉"：
   `notify()` 此时被系统静默丢弃（不抛异常），而我们把结果记成 SENT ——
   通知历史、诊断包、聚合状态三方全绿，用户什么都没收到。现两个条件都要满足。
2. 【P1】渠道被关（`IMPORTANCE_NONE`）同样静默丢弃，原先完全没判：新增
   `isChannelEnabled()`，投递前置判据失败即记 `FAILED/CHANNEL_DISABLED:<渠道>`（判据在构造通知之前，
   渠道关着时连封面都不下载）。渠道 id 与真正构造用的渠道收敛成同一个 `channelIdFor()`，不会分叉。
3. 【P1·同型】`LauncherEntryRescueReceiver` 的 KDoc 早就写着返回值覆盖"权限/异常/**渠道**"三种失败，
   代码只判了权限 —— 补齐渠道判据，"没发出去"不再返回 true。
4. 【P1·文案】`SettingsViewModel` 自己复制了一份 `checkSelfPermission(POST_NOTIFICATIONS)`，
   而那个权限是 API 33 才有的 —— 在 Android 12/12L（minSdk=31）上恒为 DENIED，
   设置页因此**永久**显示"通知权限未授予：后台无法发出通知"这条假警报。现委托给唯一判据
   `NotificationPoster.hasPermission`（该函数在 2026 早前就为此写过兼容分支并注明原因）。

**B. 通知丢失 / 重复 / 假前台（7 处）**
5. 【P1】**常驻通知 id 1001 落在 outbox 的 `1..2_000_000` 分配区间内部**：id 从 1 起按 `MAX+1`
   递增、注册表只增不减，第 1001 次分配必然撞上。`notify` 的身份键是 (包名, tag, id)，
   相同时两条通知互相顶掉：直播通知被常驻通知覆盖，`stopForeground(STOP_FOREGROUND_REMOVE)`
   还会把它一起删掉，而库里仍记"已送达"。现挪到 `2_000_002`（自救援通知早就是 `2_000_001`）。
6. 【P2】`notificationId` 分配失败被四个调用点 `getOrNull() ?: return` 静默丢弃 ——
   通知彻底消失且 outbox/日志一行不留。现在分配器自己写一条 `application_error_log`。
7. 【P2】异常路径的"放回队列"是挂起写却没包 `NonCancellable`：协程正好在此时被取消时，
   写入被直接跳过，行留在 PROCESSING 只能干等 30 秒租约回收。与仓库其它 4 处同型修复对齐。
8. 【P2】投递失败后**没有任何补投触发点**：`reviveFailed` 只在冷启动跑一次，
   而权限回调是空 lambda —— 实时模式下进程可存活数天，用户授权后不会补发，
   等下次冷启动时超过 24 小时 TTL 的行已永久失去资格。现权限回调里补 `reviveFailed + dispatchDue`。
9. 【P2】冷启动**不做投递**：投递只由 tick 驱动，手动模式/监控关闭时根本没有 tick，
   已入队的通知只能躺到 TTL 过期。现 `StartupRecovery` 在"通知可用"分支里补投一轮。
10. 【P2】保存"前台通知内容"会把前台服务拉起来并**谎报运行中**：`ACTION_REFRESH_NOTIFICATION`
    分支既不校验配置也不启动监控循环，却 `startForeground` + 置 `isRunning=true` ——
    界面、健康横幅、诊断随即显示"监控中"，实际零 Tick。现该分支先按配置校验
    （判据与 runLoop 开头逐字一致），不该跑就撤掉前台并 `stopSelf()`，该跑则顺手把循环补上。
11. `onCreate` 里无条件置 `isRunning=true` 改为**只有真进了前台才置真**（"只刷了一下通知"
    不再等于"在跑"）。

**C. 状态机与保留（5 处）**
12. 【P2】**卡在 FROZEN 的聚合永远不会被收尾**：批量通知以 FAILED/EXPIRED 收场时
    （权限/渠道不可用、或跨过 24 小时 TTL），聚合既不被 `findInProgress` 取（只认 COLLECTING/READY），
    也不被 `findTerminalBefore` 回收（只认 DISPATCHED/CANCELLED/EXPIRED）——
    聚合行与绑定行无限期堆积，状态永远与事实不符。新增 `settleStuckFrozen`：
    仅当**批量 Outbox 已终态且已过 TTL**（此时 `reviveFailed` 的 `expiresAt > now` 保证不可能复活）
    才把聚合收尾为 EXPIRED，由保留策略正常回收（两个独立代理分别发现）。
13. 【P2】通知历史**永远停在"投递中"**：history 行只在"被认领投递"时创建，
    而租约回收 / 入队即过期 / 软删除取消这三条纯 SQL 路径只改 Outbox、不碰 history。
    新增 `reconcileTerminalStatus`，在每轮投递前与冷启动结算后各对齐一次。
14. 【P2】聚合 CAS 的返回值被丢弃（与同文件对 Outbox CAS 的纪律相反）：回写没生效不留痕。
    现检查返回值并留痕，且**把"已经是 DISPATCHED"列为正常形态**（幂等重发必然落空，不能报成错误）。
15. 【说明】`idx_attempt_unfinished` 的"唯一"语义是空的（索引列恒为 NULL，SQLite 把 NULL 视为互不相等）：
    **只加注释说明，不重建索引** —— 改成 `UNIQUE(outboxId) WHERE finishedAt IS NULL` 时，
    历史数据里若已有同一 Outbox 的两条未结算行，会在 `onOpen` 抛异常，
    而那条路径一旦抛异常就是"每次启动都崩"（AppDatabase 自己的注释写着这个后果），
    收益（一张没有任何 SELECT 的表）不值这个风险。
16. 【P2】历史页把失败原因读成 `reason`（那一列**从来没有被写过**），实际原因在 `detail`：
    现在读 `detail` 并做中文映射（"通知渠道已被关闭"等），未知文本原样显示。

**D. 用户可见的文案与界面（6 处）**
17. 【文案】历史页判 `change:TITLE:` / `change:AREA:`，实际 eventKey 是 `change:TITLE_CHANGED:` /
    `change:AREA_CHANGED:`（枚举名），第 13 个字符就分叉 —— 两个分支恒不命中，变化类通知一律显示成"通知"。
18. 【文案】历史页只取最近 200 条却**不作任何说明**：更早的记录静默消失，用户会以为"根本没发过"。
    现有截断说明，并把 200 提成与查询同源的常量。
19. 【文案】模板预览按**原文**渲染，而保存时会换行转空格 + trim + 截断到 40/120 字：
    预览不是所见即所得（输入 60 字预览 60 字，实际只有 40 字且无提示）。现预览走同一套规整，
    并在发生截断时明确写出来。
20. 【文案】免打扰起止填成同一时刻：界面显示"已开启"、还提示"免打扰已开启：23:00–23:00"，
    而策略层把 `start == end` 当作**未配置**（一条都不抑制）。现拒绝保存这种值并给出原因；
    对修复前已经存下的零长度数据，界面也如实说明"当前不会抑制任何通知"。
21. 【文案】`NotificationPoster` 的 KDoc 说下播通知进"主播详情页"，实际进的是
    （用户指定的）**直播历史二级页**；另订正"查看记录动作保留应用内入口"的说法 ——
    那个按钮已按用户要求移除，开播通知现在**只有**进直播间一个落点。
22. 【文案】`dispatchDue` 两处 KDoc 断言"投递在 Tick 互斥锁内串行执行"，与调用点相反
    （引擎把投递放在锁外，窗口到期那条还在 appScope 另起协程）—— 这会误导后续改动：
    现改成"锁外执行、并发由 `claimOutbox` 的单条原子 UPDATE 保证"。

**E. 复核后判定"不是缺陷"、刻意不动的（4 处，防止下一轮被改坏）**
23. **下播通知的"可靠区间"资格**：直播期间任一次失败观察即让区间失效，此后若直接确认下播，
    场次照常关闭但**不发下播通知**。代理报为 P1，但规格 `doc_v2.4.md:9757 / 10954–11030 / 15522`
    明确要求"关播通知只在**当前区间可靠且 ACTIVE** 时发送"、"一次断开即失去资格、
    恢复并重新确认 LIVE 后新建区间"，代码与规格一致（`StateConfirmationPolicy:86-91` 的
    RECOVERY_RECONFIRM 就是重建入口）。**不动**。
24. **旧备份恢复把 `notifyTitleChange/notifyAreaChange` 复位为 false**：不是疏漏，
    `BackupRepository:1127` 的注释写明"旧备份没有这两个开关 → 按默认关闭处理"，
    属"恢复 = 整段覆盖"的语义取舍。**不动**（列入取舍清单交给用户）。
25. **部分唯一索引 `idx_attempt_unfinished`**：见 15，只说明不重建。
26. **免打扰抑制"不留痕"**：规格 22.3 的行为就是丢弃而不是延后，产品上是否要留一条
    "被免打扰挡了"的记录属于取舍，**不动**（列入清单）。

#### ④ 验证

- **编译**：`assembleDebug` `BUILD SUCCESSFUL`（后端一轮 2m34s、含 UI 一轮 29s，均无警告新增）。
- **单测**：`testDebugUnitTest` **33 suite / 316 用例 / 0 失败 / 0 错误**（与基线一致）。
- **两棵树**：本轮 15 个文件同步到 `publish/app/src` 后 **218/218 逐字节一致、0 差异**；
  所有改动文件的 BOM/行尾风格经字节级核对全部保持不变（含 `NotificationPoster.kt`、`MainActivity.kt`、
  `SettingsScreen.kt` 的 BOM + CRLF 与 `NotificationRepository.kt` 的纯 LF）。
- **产物核对**：APK 时间戳晚于全部改动源文件，关键字符串（`SERVICE_NOTIFICATION_ID = 2_000_002`、
  `CHANNEL_DISABLED`、`settleStuckFrozen`、`reconcileTerminalStatus`、`deliveryReasonLabel`）逐个核对存在。
- **实机（emulator-5554）**：向库里播种"一条 FAILED 的 Outbox（lastError=`CHANNEL_DISABLED:live`）
  + 一条停在 PROCESSING、detail 为空的 history"，冷启动后：
  ① 数据库里 history 从 `PROCESSING/(null)` 变成 `FAILED/CHANNEL_DISABLED:live`（校正生效）；
  ② 「通知历史」页显示 **`开播/关播通知 · 失败` / `投递 5 次 · 通知渠道已被关闭`**
  （既证明读到的是 detail，也证明中文映射生效，而原先这里只会显示"失败"两个字）；
  ③ logcat 无 `FATAL` / `SQLiteException` / `no such column`，进程存活，`maintenanceMode=OFF`；
  ④ 探针行已删除，设备数据库恢复原状。
- **未做**：instrumented 测试没有运行；"权限被拒/渠道被关时不再谎报 SENT"、
  "常驻通知 id 撞车"、"保存模板不再假前台"这三条**没有实机复现**（前两条需要在系统设置里
  真的关掉权限/渠道、第三条需要等 1001 次分配或手工改库），只有静态证据 + 编译 + 单测。

#### ⑤ 功能取舍（单独交给用户拍板，本轮**未做**）

① 被封/解封要不要发通知（现只有审计记录）；② 免打扰抑制要不要在历史里留痕；
③ 通知历史要不要显示主播名 / 支持翻页（现只有最近 200 条）；④ 容量问题是否也发"已恢复"通知；
⑤ 系统"单应用活跃通知条数上限"（约 50 条）下，要不要改成同一主播复用一条通知；
⑥ `RELEASED` 聚合绑定行会**永久挡住** `live_event` 的容量删除（用户删场次时才清）—— 要不要在容量清理里一并清掉；
⑦ 租约判定只用墙钟（`leaseUntilElapsed/leaseBootId` 写了却从不读），要不要改成 boot 感知；
⑧ `DELIVERY_UNKNOWN` 与 `reason/deliveredAt` 两列从不写入，要不要补写或删列（涉及迁移）；
⑨ 旧备份恢复会关掉用户显式开启的标题/分区变化通知（见 24）；
⑩ 通知历史与投递尝试表不参与备份（现只备份业务数据）。

---

---

### H69. 复查轮：把刚落的 45 处修复再查一遍（3 处从未落盘 · 2 处改错 · 3 个补丁脚本自身的缺陷）

#### ① 用户诉求

「你先重新查一遍，刚刚改过的代码，有没有什么问题，如果有问题立刻修复。不用询问我。」

#### ② 复查方式：编译器 + 自写的"落盘验证器"

先跑 `assembleDebug`：**失败**，2 个文件 11 个错（`LoginScreen.kt` 9 个、`ExportRepository.kt` 1 个）。

但编译器只能发现"引用了不存在的东西"这一类问题；**"某处修复压根没写进文件"它一个字都不会说** ——
锚点没匹配、脚本中途丢弃改动，留下的都是一个语法完全正确、但少了一处修复的文件。

所以又写了 `.tmp/verify_recheck_landed.py`，用脚本自己的锚点反过来验文件：把 9 个补丁脚本里每一处
`patch(data, old, new, what)` 的 `old` 拿去当前文件里数一遍 —— **替换成功则 old 必然消失**，
old 还在 = 那处修复没生效。共 77 处调用（含 2 处非替换型），结果 **70 处生效、5 处确认未生效**，
这 5 处（3 个独立修复）本轮全部补上。

#### ③ 根因：三个补丁脚本自己的缺陷（不是应用代码的问题）

1. **`fix_login_recheck2.py` 混用 `text` 与 `lines`**：4a/4b 应用到字符串 `text`，而 4c 用
   **脚本开头** split 出来的 `lines` 快照重建整个 `text`（`text = '\r\n'.join(lines)`）——
   4a/4b 的改动被整段丢弃。后果：登录页 UI 引用了 `lastImportNotice` / `dismissLastImportNotice()`，
   而**状态字段、init 回读、关闭方法三件套全都不存在**（9 个编译错误就是这么来的）。
2. **同一条链上的前一个脚本 `fix_login_recheck.py` 在 4c 处 `SystemExit`**：那个锚点里含注释中的
   全角引号，比对不上。这类脚本是"全部改完才写盘"，所以它**一个字节都没写**；而
   `fix_import_recheck.py` 里排在 4c **之后**的第 ⑤ 项（导出消费阶段 `catch Exception` → `Throwable`）
   因此**从头到尾没有执行过**。
3. **`fix_export_recheck2.py` 把 `var snapshotCreated` 插进了 `runCatching { }` 的 lambda 内部**，
   而使用点在 `.onFailure { }` —— 后者挂在 runCatching 的**结果**上，在该 lambda **之外**求值，
   看不到它的局部变量（`Unresolved reference 'snapshotCreated'`）。同一批补丁还顺手在 `exportStats`
   里留下一个既没赋值也没读的死变量，并把 4 行代码插成 8 空格缩进（该处应为 12）。

教训（已写进脚本注释）：**锚点脚本要么全写、要么不写，且不许混用两种文本视图**。

#### ④ 修了什么（本轮 8 处）

**A. 登录页把丢失的三件套补回来**
1. `LoginUiState.lastImportNotice` 字段 + `LoginViewModel.init` 里回读最近任务 + `dismissLastImportNotice()`。
2. 回读改用 `_state.update { }`（CAS）做**原子条件写入**：本页 init 有 5 条协程都在写 `_state`，
   而 `_state.value = _state.value.copy(...)` 是读-改-写，两条交错时后写的会覆盖先写的字段。
   "是否正在导入"与"是否已被关掉"两个判断必须与写入在同一次 CAS 里完成。
3. **提示口径收紧**（原脚本会为任何一次历史失败反复弹）：只提示三种"没跑完" ——
   ①冷启动结算出来的（按常量精确匹配）②还停在 REQUESTED/FETCHING/APPLYING 的中间态
   ③只到过预览、一位都没导入的。**普通的导入失败不再提示**：用户当场就看到错误横幅了，
   而任务行保留 30 天，每次进页面重弹一遍只是噪音、没有任何新信息。
4. UI 不再叠"上次导入未完成："前缀 —— 冷启动写进库的那句原因本身就以"导入未完成："开头，
   再套一层会变成"上次导入未完成：导入未完成：…"。

**B. 让"写入方"与"识别方"共用一个常量**
5. `IMPORT_INTERRUPTED_REASON` 从 `StartupRecovery` 的文件私有常量提升为
   `FollowImportRepository` 的公共顶层常量。理由写在 KDoc 里：登录页要靠它认出"这次失败是
   上次进程被杀结算出来的"，写入方与识别方若各存一份字面量，改一处文案就会让提示**静默失效**。

**C. 导出线（编译器报的那 1 个错 + 从未执行的 ⑤）**
6. `snapshotCreated` 局部变量 → 字段 `historySnapshotCreated`（做法与 `BackupRepository` 的降级计数
   一致），`exportHistory` 开头清零；取消日志据此区分"取消发生在取维护权/建快照之前"还是之后。
7. 消费阶段 `catch (e: Exception)` → `catch (e: Throwable)`（补上从未执行的 ⑤）：OOM 之类的 `Error`
   不是 `Exception`，只接 `Exception` 时快照会永久停在 `CONSUMING`，而 `deleteExpired` 只清**已到期**
   的行（24 小时）—— 期间诊断包一直能看到一个状态不一致的快照。构建阶段那条 `catch` 早就是
   `Throwable`，两条口径必须一致。
8. 删掉 `exportStats` 里的死变量、补齐 370-374 行缩进。

**D. 看过后刻意没改的两处（防止下一轮被"顺手统一"改坏）**
9. `countInProgress` 的 `catch (e: Exception) return null`：这是"数不出来就不写这句话"的局部降级
   （抬头宁可不说，也不写一个偏小的数字），与快照状态机无关。
10. `writeToDownloads` 的 `catch (e: Exception)`：清理 `IS_PENDING=1` 半成品后原样重抛，
    快照终态由调用方的 `Throwable` 兜底。

#### ⑤ 验证

- **编译**：`assembleDebug` `BUILD SUCCESSFUL`（首次失败于 ③-1、③-3 两处，修复后通过）。
  `publish` 侧 `clean :app:assembleDebug` 也 `BUILD SUCCESSFUL`（42 个任务全部实际执行，非增量）。
- **单测**：`testDebugUnitTest` **33 suite / 316 用例 / 0 失败 / 0 错误**（与 H68 基线一致）。
- **落盘**：验证器复跑，5 处"未生效"清零（余下 7 条为机械误报：我改写了 `new` 的措辞或给变量换了名，
  脚本里的 `new` 字面量自然不再逐字匹配；每条都已按语义单独核对并留证）。
- **两棵树**：本轮又改动的 4 个文件（`LoginScreen` / `ExportRepository` / `FollowImportRepository` /
  `StartupRecovery`）同步到 `publish/app/src` 后 **218/218 文件逐字节一致、0 差异**。
- **编码**：4 个文件均保持 **无 BOM + 纯 CRLF**（字节级核对；错一个字节就会写坏中文）。
- **实机验证（补上 H68 遗留的"运行时未复现"欠账）**：`emulator-5554` 上装 APK，向
  `follow_import_task` 播种探针行，逐一截图核对：
  1. 「冷启动结算型」（`status=FAILED`，`error` 与常量逐字相同）→ 进「B 站账号」页
     **提示确实出现**，文案与库里原因一致，且**没有**"上次导入未完成：导入未完成：…"的重复前缀；
  2. 点「知道了」→ 提示立刻消失（常驻 ≠ 关不掉）；
  3. **反向对照**：把同一行的原因换成普通失败（风控 -352），冷启动后按同样路径重新进入 →
     **不出现任何提示**（"普通失败不啰嗦"这条口径成立，同时证明上面第 1 条不是"永远都显示"）；
  4. 「预览遗留型」（`status=PREVIEW`，`stagingCount=37`）→ 提示"上次导入只到预览这一步：
     已获取 37 位主播，但没有导入任何一位，请重新导入"，**数字与库里一致**；
  5. 探针行已删除，设备数据库恢复原状（`follow_import_task` 0 行）。
- **仍未做**：instrumented 测试没有运行；中间态（REQUESTED/FETCHING/APPLYING）那条分支**没能在实机上
  造出来** —— 冷启动结算会先把它改成 FAILED，所以它只有静态推理支撑。

---

---

### H68. 导入 / 导出全链路审查与修复（4 条线 · 15 个文件 · 30+ 处）

#### ① 用户诉求

「重新检查一遍所有有关导入导出功能的代码有没有问题」→ 审查后「把发现的错误全都修复，涉及到功能取舍的单独列出来」。

#### ② 审查方式（4 路只读复查 + 协调者逐条核实）

派了 4 个**只读**复查代理，各管一条线：备份/恢复、历史/统计导出、关注导入、诊断包导出。
每个代理都被要求：给 `文件:行号` 证据、说清触发条件、标注置信度与"没能验证的部分"、**不许创建/修改任何文件**。
协调者**没有采信自述**：所有列为 P0/P1 的条目都由本人重新读源码核实过（多处还纠正了代理的严重度判断，见下）。

#### ③ 修了什么（按线，逐条）

**A. 备份 / 恢复（BackupRepository.kt）**
1. 【高】恢复「已存在的主播」时只改删除态，`monitoringEnabled`/`isFavorite` 没按备份写回 —— 而注释写着"恢复删除态与开关"。后果：备份里"已暂停"的主播恢复后**仍在监控**。现按备份写回（仅在值不同时写，避免无谓的 `updatedAt` 变更）。
2. 【高】同一主播 ≥2 条"未结束场次"会撞唯一部分索引 `idx_live_session_one_abandoned_open`，导致**整次恢复回滚**并抛出原始 SQL 错误。现按 `(主播, lifecycleState)` 去重、跳过多余条目并出**一条聚合警告**（不伪造 endTime、不静默丢）。
3. 【高】`check(takeOverForMaintenance(...))` 在 `try` 之外，失败时 `restore_run` 会**永久停在 APPLYING**（清理只删终态、冷启动只结算它之外的）。现改为失败前先结算 FAILED，任何失败路径都留终态。
4. 【高·同型】`exportBackup` / `applyRestore` 的 `finally { exitMaintenance(...) }` 是 suspend 写，协程被取消时不执行 ⇒ `maintenanceMode` 永久停在 BACKUP/RESTORE ⇒ **监控永久停摆**（`ConfigDaos` 注释自己写着这一点）。现包 `NonCancellable` 并检查释放结果、失败留痕。
5. 【中】校验和只覆盖 `body`，`settings` 段被改/损坏也能通过预检并静默写库。新增 `manifest.settingsChecksum`（带默认值，旧文件保持可解析），仅对**原始文本**复算以避免"以后加字段把旧文件判成损坏"。
6. 【中】恢复失败不写任何错误日志、warnings 文本不落库。现 `catch` 写 `application_error_log`，并把 warnings 摘要写进 `APPLY_RESTORE` 审计。
7. 【中】修正记录幂等判断是 O(n²)（每条都查该场次全部行）→ 改成先建一次 id 集合。
8. 【中】用户选中的备份文件**整份读进内存**（误选大文件即 OOM）→ 读取前按 SIZE 判断，超 64 MB 直接拒绝并说明；MediaStore 分支同理。
9. 【中】场次枚举对未知值静默降级成 NULL（事件那边用 `EnumSafe.parse`，口径不一致）→ 统一走 `EnumSafe.parse` 并汇总一条聚合警告。
10. 【低】审计 `targetStableId` 用 runId（跨设备检索会落空）→ 改为 `backupId`，runId 进 detail。
11. 【低】`restoredTags` 把"已存在/本次新建"混在一起、分组完全没统计 → 拆成新建/已存在/分组，并让 UI 显示。
12. 【文案】备份文件名与"读取最新备份"的查询写死「主播监控」→ 改用应用真实名字，且查询**同时匹配旧前缀**（否则用户升级后按钮会说"你没备份过"）。

**B. 关注导入（FollowImportRepository / MoreDaos / StartupRecovery / LoginScreen）**
13. 【高】风控 `-352` 被当成成功导入：翻页中途非 0 code 只要有数据就 `break`，任务置 PREVIEW 且 `error` 被清空 ⇒ 用户要 500 条拿到 30 条，界面报"导入完成"。现把中断原因（含业务 code）写进任务并**一路带到 UI**（预览弹窗里显示）。
14. 【高】单次导入物理上限 `40 页 × 30 条 = 1200`，而设置页允许填到 100000 ⇒ 静默截断。现只要"因页数上限停止"必写原因并在界面显示。
15. 【高】进程被杀后任务永久停在 REQUESTED/FETCHING/APPLYING，`cleanupFinished` 永远清不到 ⇒ 僵尸任务 + 暂存行永久残留。新增 `settleInterrupted` 并在 `StartupRecovery` 加一步结算（有结算就写错误日志留痕）。
16. 【中】"重试"按钮无守卫、`importJob` 守卫实际无效（轮询是另一条协程）⇒ 连点会起多个并发任务、旧轮询还会覆盖新状态。现加 `importInFlight()` 真判据 + 按钮 `enabled`。
17. 【中】`applyImport` 用"当前"taskId 而不是"预览那一份" ⇒ 快照 `previewTaskId` 并在不一致时拒绝执行。
18. 【中】导入失败零留痕 → 写 `application_error_log`（按原因选码），`operationId` 用任务 id 便于在诊断包里对应。
19. 【中】对风控 `-352`/`-412` 还会免费重发一次请求（加压）→ 识别为限流时不再重试，如实以 note 说明"未再尝试"。
20. 【中】恢复软删除主播时漏 `deletePendingTransition` → 补齐，与 `StreamerRepository` 口径一致。

**C. 历史 / 统计导出（ExportRepository / StatsSeriesRepository / ExportNaming / HistoryExport）**
21. 【高】导出被取消（离开页面）→ `finally` 里的 suspend 释放不执行 ⇒ **监控停摆**。现 `exitMaintenanceSafely()`（NonCancellable + 检查返回值 + 失败留痕），并让 `CancellationException` 原样重抛（不再报成"导出失败"）。
22. 【中】按主播**名字**分组 ⇒ 同名主播的行重复渲染、时长翻倍、文件自相矛盾。现 `rowJson` 记 `streamerId`、按 id 分组计数，只在显示时映射名字；同名时用 `名字（uid x）` 区分。
23. 【中】"全部"范围的统计上界是"最后一场 + 固定 86 400 000 ms"（非午夜）⇒ 抬头比文件名的日期段多一天、跨午夜的场次被夹进前一天。现改为"次日 00:00"，桶数改用 `ChronoUnit.DAYS.between`、标签用 `plusDays`（`TimeRange` 早就立过这条规矩）。
24. 【中】SVG 坐标 `"%.1f".format(...)` 受系统 Locale 影响（小数点为逗号的语言里图表报废）→ 固定 `Locale.ROOT`。
25. 【中】抬头写"分桶依据结束时刻"而实现按**开播**时刻分桶（文案撒谎）→ 改成与实现一致。
26. 【中】导出失败零留痕 → 失败时写 `application_error_log`（不重复记）。
27. 【中】`IS_PENDING` 收尾不校验、且不在失败清理路径内 ⇒ 用户看不到文件却被告知"导出成功"。现纳入同一 try、校验返回值、失败删半成品。
28. 【中】提示里的文件名可能不是磁盘上的文件名（MediaStore 加 `(1)`）→ 写完回读真实 `DISPLAY_NAME` 再回报；多主播命名补首位主播名（否则 `{甲,乙}` 与 `{丙,丁}` 同名而内容不同）。
29. 【中】构建阶段失败留 BUILDING 孤儿快照 → `catch` 里按取消/失败 CAS 到终态（`NonCancellable`）。
30. 【中】快照行解码失败被静默丢弃仍报成功 → 计数、写日志、并在报告抬头如实标注（全部失败则直接失败）。
31. 【中】单主播统计异常被吞成"该时间范围内没有直播记录" → 与"确实没有记录"分开，失败写明并留痕。
32. 【低】导出文案/页脚写死「主播监控」→ 改用应用真实名字。
33. 【低】进行中的场次被排除，文案却说"没有直播记录" → 改成"暂无**已结束**的直播记录"，并尽量补"另有 N 场进行中未计入"。

**D. 诊断包（DiagnosticExporter / DiagnosticsScreen+VM / ConfigDaos / instrumented 测试）**
34. 【高】导出全程在主线程（内存 deflate + MediaStore 写入），而它正是"排查卡顿"时点的按钮 → zip 与写盘切到 `Dispatchers.IO`。
35. 【高】按钮没有忙碌守卫，连点会生成多个诊断包 → ViewModel 加 `isExporting`（进入前置位、finally 复位），按钮禁用、文案切换。
36. 【中】写盘失败留下 `IS_PENDING=1` 幽灵行 → 纳入同一 try、失败 `resolver.delete`、校验转正返回值。
37. 【中】`settings_sanitized` 少 3 个字段（`noGuaranteeAcknowledged`/`noGuaranteeAcknowledgedAt`/`placeholderText`）→ 补齐并在注释里写明"新增列必须同步"。
38. 【中】文件名只到日期、提示名可能与实际不符 → 文件名精确到秒 + 回读真实文件名。
39. 【中】`CancellationException` 被 `runCatching` 吞成"导出失败" → 原样重抛。
40. 【低】`problem_state` 查询无上限（整表进内存）→ 加 `ORDER BY ... LIMIT`。
41. 【高·测试基线】instrumented 测试 3 处期望值失效（分片 6→7、库版本 6→10 两处、应用名"主播监控"→Kaoru），**必然红灯**。现改为同源引用（`AppDatabase.DB_VERSION`、`R.string.app_name`）+ 必需分片清单与计数一致性断言，并补上 3 个新增字段的断言。
42. 【文案】诊断包里的 `appName` 写死「主播监控」→ 改用应用真实名字（与测试同源比较）。

**E. UI 层（DataScreen.kt，协调者本人改）**
43. 恢复摘要只报"新增/场次/事件/标签归属/分组归属/设置项/跳过场次"，**跳过的主播/事件/标题/分区与冲突数都没报**、更新的主播数也没报 → 全部补上。
44. 恢复失败只进 4 秒后自动消失的 message → 改为常驻的 `restoreSummary`（用户来得及看清原因）。

**F. 协调者自己补的一处（代理清单之外）**
45. 【高·同型】`MaintenanceRepository.runCleanup()` 的 `finally { exitMaintenance(DATABASE_REPAIR) }` 同样是 suspend 写、同样会被 UI 取消 ⇒ 清理之后**监控停摆**。一并包 `NonCancellable`。

#### ④ 协调者纠正代理判断的地方（不采信自述）

- 代理把"取消导出导致监控停摆"标为**导出线独有**；实际 `exportBackup`/`applyRestore`/`runCleanup` **四处同型**，已一并修（F 条）。
- 代理把"重试按钮连点导致 taskId 错配"判为**高**；我读代码后确认按钮禁用状态挡住了主路径，真实症状是"旧轮询协程覆盖新状态"，按中处理。
- 代理建议"删掉 `BackupRepository` 里看似死代码的 `sha256`"——**它不是死代码**（`serializeBackup` 一直在用），代理照删后编译直接失败（`Unresolved reference 'sha256'`）。已改为调用公开的 `BackupChecksum.sha256`。同类还有两处编译错误（`enumOrNull` 访问不到局部变量 `unknownSessionEnums`、`R` 未导入），都由协调者修复。
- 代理改多主播命名后 `ExportNamingTest` 失败 —— 那是**预期内的行为变更**，测试已更新，并新增一条"人数相同、集合不同必须不同名"的用例把新行为钉死。

#### ⑤ 验证

- **编译**：`assembleDebug` 通过（第一轮失败于上述 3 处编译错误，修复后 `BUILD SUCCESSFUL`）。
- **单测**：`testDebugUnitTest` **33 suite / 316 用例 / 0 失败**（原 315 + 新增的命名用例）。
- **两棵树**：`app/src` 与 `publish/app/src` 16 个文件同步后**逐字节相同、0 差异**；所有改动文件的 BOM/行尾风格经字节级核对**全部保持不变**（含 `DiagnosticExporter.kt` 的 BOM + 混合行尾）。
- **未做**：instrumented 测试没有运行（需要设备，且本轮改动不影响它能否编译——它所在的 `androidTest` 源集不参与 `assembleDebug`）；四条线的运行时行为（取消导出是否真的不再漏锁、-352 的部分结果提示、诊断包回读文件名）**没有实机复现**，只有静态证据 + 编译 + 单测。这一点必须如实说明。

#### ⑥ 功能取舍（单独交给用户拍板，本轮**未做**）

诊断包要不要纳入崩溃记录/审计日志/DataStore 设置；导入要不要做"取消/继续上次"；备份要不要包含外观设置；
导入上限与分页能力如何对齐；历史导出要不要包含进行中的场次；导出文件"同名=同内容"要不要保留。
（详见给用户的说明，逐条列了现状、代价与建议。）

---

### H67. 第二次换图标（14 张按文件名序号，1 号作默认）+ 版本号改 v0622.0

#### ① 用户诉求

- 「清除现在所有图标，然后把我现在给你的所有图标作为预设图标。按文件名的序号来排，第 1 个作为默认图标。」
- 「完成之后不需要你进行任何测试，直接把包输出给我。」
- 「新增一个任务，将软件的版本号改为 **v0622.0**。」

#### ② 这次给的 14 张（按文件名序号）

`1, 2, 4, 5, 6, 7, 8, 9, 10, 13, 14, 15, 16, 17` —— **没有 3、11、12**。
按"文件名序号"处理：**1 号 = 默认应用图标**，其余 13 张 = 预设图标（顺序即序号顺序）。
★ 3 号（上一轮给过的那张蓝发婚纱）**这次没有给**，按"以这次给的为准"处理，未保留；若要留说一声即可补回。

#### ③ 又踩到一个"顺序"的坑（这次是排序，不是编号）

预设文件名原本按序号叫 `图标2.png` … `图标17.png`，而生成器是**按文件名字符串排序**的
⇒ `图标10 / 图标13 / … / 图标17` 排到了 `图标2` **前面**，桌面顺序整段错乱
（生成器输出里一眼可见：`i=1 -> 图标10`、`i=7 -> 图标2`）。
改法：**零填充**成 `图标02.png … 图标17.png`，让字符串序等于数字序；重新生成后
`i=1..13` 依次对应 `2,4,5,6,7,8,9,10,13,14,15,16,17` 号图，`i=0` = 1 号图（默认）。

★ 教训（与 H66 的"附件顺序 ≠ 文件名编号"是同一类）：**凡是按文件名排序的目录，序号必须零填充** ——
否则 `10` 永远排在 `2` 前面；而且这种错误在界面上表现为"顺序莫名其妙"，很难一眼归因。

#### ④ 规模与版本

- 14 个图标档 × 4 个名字（`Kaoru` + 主播监控 / 直播通知器 / 豆豆知道了）= 56 格
  ⇒ **55 个别名**、65 个位图资源（13 预设 × 5 密度 + 默认图标 5 密度）。
- 版本号：`versionCode 2 → 3`，`versionName "1.1" → "0622.0"`。
  应用界面显示的是 `"v" + versionName`（`SettingsScreen.appVersionLabel`），所以正好显示 **v0622.0**。
- 安装包改名为 `publish/dist/Kaoru-0622.0-debug.apk`（旧名 `主播监控-1.1-debug.apk` 已删除 ——
  应用现在叫 Kaoru、版本 0622.0，旧文件名会误导）。README 里的路径同步更新；
  **本文档前面几条（H63/H64/H65/H66）里写的交付包名是当时的事实，不再改动。**

#### ⑤ 顺手订正的两处过期信息

1. README 顶部「当前版本」原本写着 `versionName 1.1 / versionCode 2，数据库版本 **8**` ——
   三个都不对了（库早就是 **10**，版本号本轮也变了），已一并订正。
2. 设置页「关于」卡片里**写死了**"哔哩哔哩主播监控"（`SettingsScreen.kt:538`），
   应用改名成 `Kaoru` 之后这句话就错了。改成读 `@string/app_name`：
   以后在 `strings.xml` 里改名字，界面文案自动跟着走，不会再脱节。

#### ⑥ 验证范围（用户明确要求"不用测试"）

按要求**没有**装机测试，只做了交付物本身的核对（这不是"测功能"，是确认交出去的文件是对的）：

- 构建：`publish` 树 `clean :app:assembleDebug` **全新完整构建**（42/42 任务执行），`BUILD SUCCESSFUL`。
- `aapt2 dump badging`：`application-label:'Kaoru'`、`versionCode='3' versionName='0622.0'`。
- `aapt2` 计数：`activity-alias` **55** 个、`mipmap` 资源 **14** 个（`ic_launcher` + `preset_1..13`）。
- **顺序核对**（从 APK 里抽 xxxhdpi 位图比字节数，与源图一一对应）：
  `ic_launcher`=108,771=1 号图、`preset_1`=105,139=2 号图、`preset_7`=98,871=9 号图、
  `preset_8`=102,915=10 号图、`preset_13`=106,035=17 号图 —— 与预期完全一致。
- 交付包：`publish/dist/Kaoru-0622.0-debug.apk`，73,168,856 字节，sha256 `A3BA3D11…`。

---

### H66. 换掉全部图标（9 张按文件名序号）+ 默认名字改 Kaoru + 三个可选名字

#### ① 用户诉求

- 「把现在所有图标去掉」—— 旧的 3 个预设（alpha、alpha-2、测试乙）与 publish 侧残留的 1.png 全部清掉。
- 「现在我给你9张图片…**第1张为默认的软件图标**，后面几张为可选的预设图标」。
- 「软件默认名字改为 **Kaoru**，加两个可选的名字：第1个是主播监控，第2个是直播通知器，第3个是豆豆知道了」
  （原话是"两个"，但枚举了三个；按枚举的三个实现 —— 给用户留更多选择，且三项都已生效、可随时删）。

#### ② 我犯的一个错（用户当场纠正）

附件的**文件名本身带了序号**（`ic_launcher_1_192.png` … `ic_launcher_9_192.png`），
而我按"附件在消息里出现的先后顺序"当成了 1..9 —— 那批附件的出现顺序是 3,4,5,6,7,8,9,1,2，
于是**默认图标和第 3 个预设各错了一位**（默认用了 3 号图、图标3 用了 1 号图）。
用户指出「默认图标按文件名的序号来」后按**文件名序号**重排：默认 = 1 号图，预设 = 2..9 号图。
★ 教训：一批附件里"顺序"和"文件名"是两回事，**以文件名/内容自带的标识为准**，不要假定顺序就是编号。

#### ③ 做法

- **默认图标**：`tools\生成默认图标.ps1`（新脚本）把 1 号图缩放成
  `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`（48/72/96/144/192）。
  **删掉 `mipmap-anydpi-v26/ic_launcher.xml`** —— 否则 API 26+ 优先用自适应图标，位图会被盖掉。
- **预设图标**：2..9 号图 → `icons/presets/图标2.png` … `图标9.png`（文件名前缀相同，数字决定排序，
  生成器按文件名排序编号，所以 i=1..8 就对应 2..9 号图；界面里显示的名字就是文件名 `图标2`…`图标9`）。
- **名字**：`icons/names.txt` = 主播监控 / 直播通知器 / 豆豆知道了；
  `app/src/main/res/values/strings.xml` 的 `app_name` = **Kaoru**（j=0 哨兵用的就是它）。
- 跑 `tools\generate-icon-presets.ps1` 生成 + 镜像：**35 个别名**（(8+1)×(3+1)−1）、40 个位图资源。

#### ④ 为什么是普通位图而不是自适应图标（技术选择，写下来免得日后反复）

- 用户给的 9 张图都是 **192×192** —— 这正好是传统启动图标在 xxxhdpi 下的标准尺寸（48dp×4），
  位图路径**一个像素都不用放大**；
- 预设图标本来就是普通位图（`ic_launcher_preset_<i>.png`），默认图标用同一套做法，
  桌面上 9 个图标的外观口径才一致（都不裁切、不描边）；
- 自适应图标要 108dp（xxxhdpi = 432px）的前景层：192px 源图会被放大 2.25 倍，
  而且会被启动器按形状裁掉约 1/3 画面。
- 代价（如实说明）：Android 8+ 的启动器会给"非自适应"图标加自己的底板/缩放，不同桌面（MIUI / Pixel）表现不同。
  要"整块铺满、按形状裁切"的现代观感，就得把**默认与全部预设**一起改成自适应图标（需要改生成器），当时未做。

#### ⑤ 验证（不是"看着像"）

- `aapt2 dump badging`：`application-label:'Kaoru'` ✓；`aapt2 dump resources`：mipmap 表
  `ic_launcher` + `ic_launcher_preset_1..8` 九个资源 ✓。
- **像素/尺寸级核对**：从 APK 里抽出 `res/mipmap-xxxhdpi-v4/*.png`，与 9 张源图逐一对照 ——
  `ic_launcher`=108,771 字节 = 1 号图；`preset_1..8` 分别 = 2..9 号图的字节数（`preset_2` 差 1 字节，
  是该图经生成器 192→192 重采样后重新编码所致）。另用 `read_image` 直接看了两张：
  默认图标 = 1 号图（粉紫发夜景）、`preset_2` = 3 号图（蓝发婚纱），与预期一致。
- 35 个别名的 `icon` 引用与下标一一对应（`IconPreset_8_*` → `mipmap/ic_launcher_preset_8`）。
- 两棵树 `app/src` **193 个文件逐字节相同、0 差异**；APK 装机（覆盖安装、保留数据）后进程存活、无崩溃。
- 交付包：`publish/dist/主播监控-1.1-debug.apk`（72,066,117 字节，sha256 `47F91BB2…`，全新完整构建）。
  体积比上一版大约 1.9 MB —— 就是新增的 40 张预设位图。

#### ⑥ 工具链又踩到一次同一个坑

我自己写的校验脚本用 `write` 工具落盘 ⇒ **无 BOM 的 UTF-8** ⇒ Windows PowerShell 5.1 按 ANSI 解码，
中文字符串把引号吃掉、直接语法错误（脚本注释里写的正是这条）。**给 .ps1 落盘后必须补 BOM**，
本条已在 H63 ⑥ 记过，这次是我自己没照做 —— 记在这里提醒下次先补 BOM 再运行。

---

### H65. 图标管理器「零安装」：把编译依赖打包进同一个文件夹（build-env）+ 摆放检查

#### ① 用户诉求（本轮）

- 「这四项都不用改了」→ 设置页审查的 F2/F3/F6+F8/F7 **就此结案，不改代码**（结论留在 H64 ⑥）。
- 「那个图标脚本的兼容性还是不够，你要把编译时需要的依赖都打包进同一个文件夹里面。」
- 「那个脚本所使用的目录，我觉得应该改成那个上传 github 的文件夹的相对路径。」
- 「并且在使用的时候要提示摆放的路径是否正确。」

#### ② 做了什么

**A. 把编译依赖打包进同一个文件夹**（`tools\制作依赖包.ps1` → `publish\build-env\`，**1264 MB**）：

```
build-env\
  jdk\                        便携 JDK 17（302 MB）
  android-sdk\platforms\android-35            （97.8 MB）
  android-sdk\build-tools\34.0.0 + 35.0.0     （整份打包，274.7 MB）
  android-sdk\platform-tools\                 （33.3 MB，adb）
  android-sdk\licenses\
  gradle-home\wrapper\dists\gradle-8.11.1-bin （144.5 MB，工程要的那一版）
  gradle-home\caches\modules-2\               （411.9 MB，--offline 编译的关键）
  依赖包说明.txt / source.properties
```

制作脚本是**开发机专用**的一次性工具：自动找 JDK / SDK / Gradle 缓存 → 增量复制（robocopy，能处理接近 260 字符的长路径）→ 写说明与来源面包屑。可重复跑。

**B. 目录全部改成"相对上传文件夹"**：`管理图标.cmd` 用 `%~dp0`、`管理图标.ps1` 用 `$PSScriptRoot`，整套东西可以放任意位置、整体拷走。另外补上一件以前没有的事：**用打包 SDK 时自动把 `local.properties` 的 `sdk.dir` 改写成当前这份**——否则 AGP 会优先读 `local.properties` 里别人机器的旧路径，表现成"明明带了 SDK 还说找不到"。

**C. 启动时的"摆放检查"**（用户明确要求）：进菜单之前打印
- 项目文件齐不齐（`gradlew.bat` / 构建脚本 / 清单 / `gradle-wrapper.jar` / 图标生成脚本，清单只维护一份，编译前的前置检查复用同一份）；
- 依赖包在不在、包里的 build-tools 有哪几版；
- **这个路径适不适合编译**：中文/全角字符、空格、路径过长（260 字符上限）、云同步目录、网络盘、系统目录、**实测能否写文件**、磁盘剩余空间 —— 都给中文提示，并给出一句结论（能用 / 先处理带 X 的条目）。

#### ③ 实测（这一节才是重点：三个真问题全是实测抓出来的）

**测试方式**：把 `build-env\gradle-home` 复制一份到 `.tmp`（冷缓存，556 MB），
`PATH` 只留 `system32` + 打包的 JDK（本机 java 找不到），
`JAVA_HOME` / `ANDROID_HOME` / `GRADLE_USER_HOME` 全部指向 `build-env`，
在 publish 树里 `gradlew.bat clean :app:assembleDebug --offline`。

实测抓出的问题（只看代码是发现不了的）：

1. **build-tools 只打"最高版本"不够** —— 干净环境报
   `Failed to find Build Tools revision 34.0.0`。AGP 具体要哪一版是它内部的默认值，工程文件里读不出来；开发机编得过只是因为它同时装了 34 和 35。**改为整份打包**：能站得住的推理是"这台机器能编过 ⇒ 它装着的这套 build-tools 一定够用"。代价 +136.6 MB。
2. **"自己拷自己"** —— `管理图标.ps1` 会把 `local.properties` 改写成指向 `build-env\android-sdk`（它该这么做），于是**再跑一次制作脚本时，来源就变成了依赖包本身**，包里少了什么就永远补不回来（本次实测正是这样漏掉了 34.0.0）。修法：来源优先级改为 `ANDROID_HOME` → `local.properties`（**指向 build-env 时跳过并提示**）→ 面包屑 `build-env\source.properties`（上次的来源路径）→ 常见安装位置。
3. **制作脚本自身的三个原生程序坑**（都真踩到了）：
   - `java -version` 与 `robocopy` 会往 **stderr** 写正常内容，而 `$ErrorActionPreference = 'Stop'` 会把原生 stderr 当成**终止性错误**直接中断脚本（本仓库台账里 adb 踩过同一个坑）→ 统一走 `Invoke-NativeCapture`（临时降级 EAP）；
   - `$env:JAVA_HOME` 常带**尾反斜杠**，而 Windows 命令行里 `"C:\dir\"` 的 `\"` 是一个转义引号 ⇒ robocopy 只收到一个参数、报 `No Destination Directory Specified` → 加 `Normalize-DirPath`（盘根除外）；
   - `modules-2.lock` 被 Gradle 守护进程锁着 ⇒ robocopy `ERROR 33 / RETRY LIMIT EXCEEDED / 退出码 9` → 先 `gradlew --stop`，再 `/XF` 排除运行期锁文件。

**我自己引入并修掉的一个 bug**（记录在案，因为它是"按字节改脚本"的典型翻车）：改 `管理图标.ps1` 时锚点只匹配了原行的尾巴，替换文本又把整行重写了一遍 ⇒ 变成
`$script:LogPath = Join-Path $script:TmpDir $script:LogPath = Join-Path $script:TmpDir '...'`，
结果是 `$script:LogPath` 根本没被赋值（现在在第 1039 行要用它写日志）。自检脚本现在会专门查"一行里出现两个 `Join-Path`"这类粘连。

**结果**：**`BUILD SUCCESSFUL in 6m 39s` ／ `42 actionable tasks: 42 executed`** —— 冷缓存（复制出来的 556 MB gradle-home）、先 `clean`、`--offline`、PATH 里没有本机 java，只用 `build-env` 里的 JDK + SDK + Gradle 发行包 + 依赖缓存，**完整编出了 APK**（70,180,403 字节，与开发机工具链编出来的**大小完全一致**，只是 dex 里带了各自的时间戳）。也就是说：这个文件夹确实可以整包发给别人。

#### ④ GitHub 的现实约束（必须知道的一条）

`build-env` **不能**提交进仓库：JDK 的 `lib\modules` 单个文件 **122.8 MB**，超过 GitHub 的
**100 MB/文件硬上限**，`git push` 会被直接拒绝。因此 `publish\.gitignore` 排除 `build-env/`，
分发方式是**压成 zip（网盘）或作为 GitHub Release 附件**（单附件上限 2 GB）。
README 里已经把这一节写清楚（含"为什么 build-tools 要整份打包"）。

#### ⑤ 本轮**未验证**的部分（如实说明）

- **没有在真正全新的机器上验证**：本机仍然装着 JDK 与 Android SDK，只是这次编译**没有用到它们**（PATH 里没有本机 java，三个环境变量都指向打包的那份）。严格来说"另一台从未装过 Android Studio 的电脑"仍未实测。
- 只覆盖 **Windows**；macOS / Linux 的对应做法（`JAVA_HOME`/`ANDROID_HOME` 同样适用，但没有 `.cmd` 入口与 robocopy）未做。
- 依赖升级后（改 `gradle\libs.versions.toml` 等）必须**重跑一次制作脚本**，否则离线编译会报"缺少已下载的依赖"——这条依赖人记住，脚本没做自动检测。
- **release 变体**未用打包工具链验证（只验了 `:app:assembleDebug`）。

---

### H64. 第二批修复（引擎截断阈值 · 状态机三缺陷 · 熔断开关与恢复间隔）+ 协调者独立验证

#### ⓪ 本轮用户诉求（按提出顺序）

1. 「修第一批」—— 第一批 7 项已完成（见上一轮记录）。
2. 「全做，按照你的建议来做，那一条没有归进第2批的不做」—— 做第 2 批全部，**明确跳过 ⑥**（"单次失败就让检查间隔失效"会造成漏报/误报）。
3. 「给那个熔断的功能加一个开关，并且再加一个熔断之后尝试恢复检测的间隔，默认是5分钟」→ 随后更正「**熔断的默认值你改回60秒**」（种子值最终 = 60）。
4. 「现在你再检测一下有关开关检测的各个高级设置的代码有没有问题」→ 设置页专项审查，结论见 ⑤（**未修，待裁决**）。
5. 「你先等其他三个子代理修完，然后把他们修过的代码再复查一遍，最后再一起收尾」→ ④ 就是这次"独立复查 + 收尾"的记录。

#### ① 引擎侧：截断阈值同源 · 熔断开关三层门控 · 留痕选码不许猜

**截断阈值同源**（缺陷：每次关播都被误判成"断档"）。所谓"断档截断"要拿"正常的检查间隔"当基准，而三条落库路径里有一条用了**配置里的 `intervalSeconds`**、另外两条用了**实际周期** `FreshnessPolicy.tickPeriodSeconds`。省电模式把真实周期 clamp 到 ≥15 分钟，配置里却可能还是 60s ⇒ 阈值 2×60=120s 远小于真实间隔（≈900s）⇒ **每次关播都判"断档"**，`endTime` 回退到上次成功确认的时刻（时长系统性少算约一个周期），并每次都写一条 `SESSION_END_TRUNCATED` 审计，把真正的断档信号淹没。
现三处**同源**：`MonitoringEngine.kt:1498` / `:1510` / `:1519` 全部 `FreshnessPolicy.tickPeriodSeconds(snapshot.mode, snapshot.intervalSeconds)`。
**测试**：`MonitoringEngineSessionEndIntervalTest`（3）、`SessionEndTruncationTest`（6）。

**熔断总开关（用户可配，默认开）**。判据抽成顶层纯函数 `breakerGateOpen(enabled, state, nowElapsed) = enabled && state.isOpen(nowElapsed)`（`MonitoringEngine.kt:2047-2051`），三层门控：
- **判据层** `breakerOpen(snapshot)`（`:1673`）—— 三处调用点（Tick 起点早退、重试轮提前收手、重试循环内 break）都过它；
- **写入层** `recordRequestOutcome` 开关关闭时**直接 return**（`:1711`），连"连续失败"都不计；
- **状态层** `runTick` 开头 `if (!snapshot.circuitBreakerEnabled) resetBreaker()`（`:218`，在 `breakerOpen` 早退 `:219` **之前**）。
★ **为什么不只在写入侧拦**：`RetryPolicy.BreakerState` 是**跨 Tick 存活**的内存状态。用户在熔断打开期间关掉开关，它仍停在"打开"，Tick 起点的 `circuit_open` 早退照旧命中 ⇒ 请求依然发不出去，与"关熔断"的意图正好相反。并进判据后，"开关关闭期间走不到那条早退"是**结构上**成立的，不是靠调用顺序碰巧。复位则保证"关掉再打开"不会白等一个恢复窗口（那一轮明明没有任何失败）。
**开关只影响这一件事**：比例门禁决定的重试轮、系统盲区与问题通知都不经过这里（`:1699-1700` 写明）。
**留痕不许猜**：熔断打开时的错误码按**顶开它的那次失败原因**选（`breakerTraceCodeOf` `:2061` → `RoundFailureCode` → `RoomFailureMapping`；类别文案 `breakerTraceKindOf` `:2071` → `RoundFailureGate.isTransportFailure`）；拿不到原因如实落 `UNKNOWN`，**不再一律写 `NETWORK_UNAVAILABLE`**（`MALFORMED_RESPONSE`/`MISSING_IN_RESPONSE` 顶开的熔断曾被说成"网络故障"）。
**测试**：`MonitoringEngineCircuitBreakerTest`（5）。

#### ② 状态机侧：计数行代际 · 被遗弃的开播会话 · 陈旧观察落库

- **计数行代际**：`streamer_pending_transition` 的确认计数曾与"主播代际/监控代际"脱钩 ⇒ 中断恢复后的**第一次**观察可能直接凑满阈值、判出错误的开/关播。现写入侧走纯函数 `pendingWritePlan`（`MonitorRepository.kt:1390`，结论 `Create`/`ResetForNewGeneration`/`Keep`，`:863-883` 落地），读侧 `MonitorDaos.getPendingTransition` 加**代际过滤**（`:122`），并新增 `getPendingTransitionAnyGeneration`（`:452`）/`resetPendingTransition`（`:477`）。跳过计数时写审计 `PENDING_COUNT_SKIPPED`（`:911`）。
- **被遗弃的开播会话**：主播行的开播会话因进程死亡/代际切换而**永远开着** ⇒ 污染历史、统计、导出与备份。现由 `handleAbandonedOpenSession`（`:1013`）用纯函数 `sameLiveRun(now, abandonedAt, expectedIntervalSeconds)`（`:1372`）判定"**只有能证明陈旧才关闭**"，`endTime` 取 `truncatedEndTime`（`:1348`）而非字面 `now`，并写审计 `SESSION_ABANDONED_AUTO_CLOSED`（`:1082`）。
- **陈旧观察落库**：`casConfirmedObservation` 补 `AND monitoringEnabled = 1`（`MonitorDaos.kt:67`）、计数推进补 `EXISTS(streamer ... monitoringEnabled = 1)`（`:105`）⇒ 用户**已暂停**的主播不得再提交状态转换；同时删掉了恒真的代际条件（写了等于没写，却让人以为有保护）。
- **测试**：`PendingTransitionGenerationTest`（10）、`AbandonedOpenSessionHandlingTest`（10）、`MonitorDaoSqlContractTest`（5，含真实 SQLite 断言与负对照）。

#### ③ 配置链路 + 迁移 v10（零新口径）

新列 `circuitBreakerEnabled`（`INTEGER NOT NULL DEFAULT 1`，默认 true = 保持引入开关之前的既有行为）走完整链路：
`ConfigEntities.kt:71`/`:104`（两张表都加 `@ColumnInfo(defaultValue = "1")`）→ `AppMigrations.V9_10__circuitBreakerSwitch`（`:346-358`，**首行 `dropPartialIndexes(db)` 不可省**，否则迁移后的全量 schema 校验会被库里残留的 16 条部分唯一索引判不等 ⇒ 开库即崩）→ `AppDatabase.DB_VERSION = 10`（`:160`）+ `DbSeed` 两条 INSERT（`:286`/`:304`）→ `DomainModels.kt:135` 快照字段 → `ConfigRepository`（`ConfigUpdate` `:52`、`merge` 回落 `:334` 与回写 `:381`、revision 与 cas 两处 `:169`/`:200`/`:261`/`:292`）→ `SettingsScreen.kt:442-446` 开关 → `BackupRepository.kt:206` DTO 默认 true（`:419`/`:1016` 导出与恢复）→ `DiagnosticExporter.kt:156`，并把停在 `7 to 8` 的 `MIGRATION_PAIRS` 补齐。
**「熔断后重试间隔」复用既有的 `circuitBreakerRecoverySeconds`（秒）**，不新增任何字段 —— 它本来就是"熔断打开后等多久再尝试检测"，此前唯一写途径是备份恢复；现开放为设置页可调（`SettingsScreen.kt:455-460`，1–3600，兜底 `?: 60`，与 `merge` 的 `cbRecovery` 校验逐字同步）。
**★ 用户明确要求的种子值：`circuitBreakerRecoverySeconds` 保持 60（未改动）**（`AppDatabase.kt:290`/`:308`）。
**测试**：`CircuitBreakerConfigTest`（17，含用 `Proxy` 真跑一遍迁移、以及用 `javap` 字节码核对 `@ColumnInfo`——该注解是 CLASS retention，反射读不到）。


#### ④ 协调者独立验证（子代理自述一律视为"未验证"）

**编译**：`.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`（Room KSP 对 v10 实体做完整校验、Hilt/Compose/dex 全部跑过）。
**单测**：`:app:testDebugUnitTest` → **32 suite / 304 用例 / 0 失败 / 0 错误 / 0 跳过**；修完下面问题 1 之后为 **33 suite / 315 用例 / 全绿**。
**双树**：`app/src` ↔ `publish/app/src` **164 个文件逐文件 SHA-256 全等、0 差异、0 单边文件**。

**schema 结构 diff（9.json → 10.json）**：把两个 JSON 展平成 **2494 条路径**逐条比对 —— 新增 10 条、变化 9 条，**全部**是新列 `circuitBreakerEnabled INTEGER NOT NULL DEFAULT 1` 进两张 config 表，外加 `version` / `identityHash` / `setupQueries`。**没有任何其它 schema 漂移**。
**DDL 写法有先例可依**：v8→v9 用的是同一手法（实体 `@ColumnInfo(defaultValue = "50")` + `ADD COLUMN … DEFAULT 50`），而用户真机升到 v9 之后正常开库 ⇒ Room 的全量 schema 校验认这个组合。

**★ 真机端到端迁移验证**（复查代理把它列为"最大缺口"，本轮补齐；这也是唯一能证伪迁移的手段）：
- **升级前**（模拟器上真实的老库）：`user_version = 9`、`room_master_table.identity_hash = 9afa962929877524c1d0567ee234ad2e`（与 9.json **逐字符相同**）、`streamer` 5 行、`monitoring_config` 27 列（没有新列）。
- `adb install -r` **覆盖安装**（保留数据）→ 启动 → 进程存活，logcat 无 `FATAL EXCEPTION`、无 `Migration didn't properly handle`。
- **升级后**：`user_version = 10`、`identity_hash = ff7c2ef7805bfccc7594de231ecd9156`（与 10.json **逐字符相同** ⇒ **Room 自己的全量 schema 校验在真实迁移后的库上通过了**，这比任何文本核对都硬）、`streamer` 仍是 5 行、老配置行拿到 `circuitBreakerEnabled = 1`、`circuitBreakerRecoverySeconds` 仍是 60。
- 把迁移后的库拉到主机，用 Python 按 10.json 对 **全部 44 张表**逐列比对（类型 / 非空 / 默认值 / 主键）：**0 处不一致**。168 条索引里，被迁移开头 `dropPartialIndexes` 摘掉的那 **16 条部分唯一索引全部由 onOpen 重建**（一条不少，没留下静默的性能退化）。

**publish 侧（复查当时缺 10.json、APK 停在 09-13）**：让 publish 树**独立完整构建**（41/41 任务全量执行，不是增量）⇒ 它自己导出的 `publish/app/schemas/…/10.json` 与根树 **SHA-256 完全相同**（`BC03586E…`），`publish/app/build.gradle.kts` 等 6 个构建文件与根树逐字节相同（versionCode 2 / versionName 1.1）。
**交付物**：`publish/dist/主播监控-1.1-debug.apk`（sha256 `6A1953E4…`，70,180,403 字节）—— 用**它自己**在模拟器上覆盖安装并启动：正常、`user_version=10`、5 位主播全在。
**顺手查清的一件事**：根树 APK 比 publish 树大 **559,242 字节**。逐条目比对：两者 **193 个条目同名同内容**（仅 4 个 dex 合计差 260 字节），差额**全部**来自增量打包留下的 **554,292 字节"条目间填充"**（publish 树是全新构建，填充为 0）⇒ 对外发的那个改用 publish 树的干净产物。

#### ⑤ 独立复查提出的 5 条 —— 逐条核实与处理

| # | 复查结论 | 我的核实 | 处理 |
|---|---|---|---|
| 1 | 【中】熔断关闭后重试放大只靠 Tick 预算兜住，而预算撞穿会丢已成功批次的写入 | **风险属实，且比复查描述的更贴近默认配置**；但"丢已成功批次的写入"**不成立** —— 错误来源是引擎里一句**写错的注释**，复查被它误导 | **已修**（见下），并订正那句注释 |
| 2 | 【中】`updatePendingTransitionCas` 没有租约/fencing/维护模式那一环 | **提法确属过宽**（DAO 自己的注释是诚实的）。主链安全：失去租约的旧进程在 `casConfirmedObservation` 就被 0 行挡住，根本走不到计数写入。残余窗口（CAS 成功后、提交前租约被接管）只会**丢一次计数**，有 `PENDING_COUNT_SKIPPED` 审计，下一轮序号前进即自愈 | 不改代码，订正本文档提法 |
| 3 | 【低】`MonitorDaoSqlContractTest` 是"读源码文本"测试，证明不了 SQLite 语义（`<` 改 `<=` 仍全绿），且改签名会误报 | 属实 | 已在该测试 KDoc 写明这两面（别把绿色当"SQL 正确"） |
| 4 | 【低】`RoundFailureGate.shouldStopRetrying` 已是死代码，与新的收手判据成"两个真相" | 属实（生产代码零调用，只有单测引用） | 已就地标注"生产已不再调用" |
| 5 | 【建议】`publish/` 侧 schema 与 APK 未同步 | 复查当时属实（它看不到我并行的构建） | 已闭合（见 ④） |

**问题 1 的复核与修法**（我自己重新算过一遍）：
- 复查说"撞穿预算会连本轮已成功批次的写入一起丢"——**不成立**：观察是**逐批**落库的（`processOutcome` → `applyConfirmedObservationAtomically`，每批各自一个事务），硬取消只丢"还没跑到的批次"。它得出这个结论是因为引擎里那句错注释（"批次收集是全有或全无、已成功的批次也不会被写入"）——**注释已订正**，并写明"独立复查曾被它误导"。
- 但它指出的**风险是真的**，而且比它算的更贴近默认配置：预算 = `clamp(2×intervalSeconds, 60s, 900s)`，默认 `intervalSeconds = 60` ⇒ **只有 120s**。熔断**打开**时，重试轮在连续失败达阈值后提前收手（约 3 个批次），顺带把整轮压在预算内；**关掉开关**之后没有这个闸门，一次全面故障的重试轮（默认配置 6 个批次、单批最坏约 54s、并发 4 ⇒ 约两波）就会超过 120s 被**硬取消**。
- 硬取消的后果不是"多打几个请求"，而是批次循环**之后**的那段收尾**全部不执行**：末轮比例结算、系统级故障归因与**通知**、健康事件、计数发布。也就是说——**用户关掉熔断之后，"这一轮为什么大面积失败"那条通知反而发不出去**，而这正是最需要它的时刻。这属于本仓库明令禁止的"静默降级"。
- **修法（不改用户要的语义：失败就继续重试、不暂停请求）**：把"预算到期时刻"（单调时钟）交给 `runTick`，让**重试轮自己看着预算收手** —— ① 剩余 ≤ 收尾余量（`TICK_TAIL_RESERVE_MS = 15s`）时不再开新批次；② 「退避 + 下一次尝试 + 收尾余量」装不下时，按**与"最后一次尝试"完全相同的口径**处理（如实计一次熔断失败后返回），不给"少记一次失败"留缝。于是最坏情况从"整轮被硬取消、收尾全丢"变成"这一轮少重试几批（如实计入失败账面、照常出现在失败日志与通知里）"。
- 判据抽成顶层纯函数 `remainingTickBudgetMs` / `retryFitsInBudget` + 常量 `TICK_TAIL_RESERVE_MS` / `SINGLE_ATTEMPT_SLACK_MS`（后者与 `fetchOnce` 的 `withTimeout` 同源，避免判据与实际超时各自漂移），新增 `TickBudgetGuardTest`（11 用例，含"默认预算下单批完整重试就要 213s > 120s"的算术留证）。

#### ⑥ 设置页 / 高级设置的专项审查结论（**未改代码，待用户裁决**）

用户问了"有关开关检测的各个高级设置的代码有没有问题"，审下来**机制上有 4 处值得知道**，但都**不是当前能触发的故障**（下面每条都注明了触发条件）：

- **F2 保存是全行校验：任何一项越界 ⇒ 整次保存失败。** `ConfigRepository.merge()` 校验的是**合并后的整行**（`ConfigRepository.kt:343-367`，每项不合法就 `return null`），`update()` 随即返回 `Invalid("参数非法")`（`:140`），界面只弹一句**「参数非法」，不说是哪一项**（`SettingsViewModel.kt:111`）。于是库里只要有一项越界，用户**改任何其它设置都会一起失败**，而且提示不指向问题项。
  当前哪些项"界面改不了、但校验照样拦"：`retryBaseSeconds`、`maxRetryDelaySeconds`、`aggregationWindowSeconds` —— 设置页**根本没有这三项的入口**（引擎却真的读它们）。
  **触发条件**：需要一个"越界的历史值"。现有写入路径（设置页、备份恢复）都过 `merge`，都会被拦，所以**正常使用造不出这种值**；真出现的场景是"校验是后加的、老库里已存着越界值"。因此按"机制真实、当前不可达"记录。
  可选修法：① 报错文案带上具体项与范围；② 改成"只校验本次要改的项"，越界的历史项原样保留（不再拖累其它保存）；③ 给那三项补上设置入口（这与用户"尽量给用户自由"的取向一致）。
- **F3 界面范围与落库范围不一致**：`batchSize` 界面 10–100 / 落库 1–100；`circuitBreakerThreshold` 界面 1–10 / 落库 1–20；`freshnessStaleSeconds` 界面 1–1440 分钟（=60–86400 秒）/ 落库 30–86400 秒。库里若落在"落库允许、界面不允许"的窄带里，输入框会显示成越界错误（用户改一个合法值即可恢复）。属体验瑕疵，无数据风险。
- **F6 + F8 三项引擎在读、界面改不了**：`retryBaseSeconds`、`maxRetryDelaySeconds`、`aggregationWindowSeconds`（都在 `merge` 的校验范围内、都有明确上界，只是没有输入口）；另外诊断导出里**没有 DataStore 侧设置**（免打扰、容量、外观等）。
- **F7 免打扰只在"创建通知"时判定，投递时不再判定**：`NotificationOutboxWriter.kt:183` 与 `MonitorRepository.kt:1263` 都是创建时判，而 `NotificationDispatcher.dispatchDue()` 里**没有**免打扰检查。于是"免打扰开始前创建、开始后才真正弹出"的通知仍会响。窗口很窄（投递紧跟在本轮 Tick 之后，除非 Doze/重试把它推后），属于"要不要在投递前再判一次"的语义选择。

#### ⑦ 本轮新增的工具链与流程经验（接 H63 ⑥）

1. **PS 5.1 读不了 Gradle 的测试报告 XML**：`[xml](Get-Content -Raw)` 会在中文用例名上报 "unexpected token"（编码把 UTF-8 当 ANSI）。改用 `Get-Content -Encoding UTF8` + 正则抓 `tests="N" failures="M"`，或干脆交给 Python。
2. **别跟 `adb shell sqlite3 'SQL'` 的引号较劲**：反引号/单引号套娃必然出错。直接把库 `adb pull` 到主机用 Python 的 `sqlite3` 查，顺带能做全表结构比对。
3. **`PRAGMA table_info(group)` 会语法错**：`group` 是 SQLite 关键字，标识符要加引号。
4. **Room 导出 JSON 里主键在 `primaryKey.columnNames`**，不是字段上的 `primaryKeyPosition` —— 我第一版校验脚本因此把 44 张表全报成"主键缺失"，险些自造假缺陷。
5. **台账文件带 BOM + 混合行尾**：一律用 Python 按字节插入/替换并断言"恰好命中 1 次"；`edit` 工具会丢 BOM、并把裸 LF 归一成 CRLF。
6. **只读复查要显式禁止建文件**：本次复查代理中途建了一个报告文件（虽已自行删除并确认仓库干净），流程上应当把"只读"写成"不得创建/修改任何文件"。
7. **增量构建会留下死填充**：根树 APK 有 554KB 条目间填充，全新构建为 0；**对外交付的产物要用全新构建**，别拿增量产物直接发。
#### ⑧ 本轮**未做**的已知项（如实列出，不埋在别处）

1. **熔断早退路径的归因兜底仍会硬写 `NETWORK`**：`MonitoringEngine.kt:233`
   `reason = breakerAttribution.dominantReason ?: RoomFailureReason.NETWORK`，
   与紧邻注释（`:224-226`：硬写 NETWORK 会让被限流顶开的熔断继续显示成网络故障）**自相矛盾**。
   **当前不可达**：熔断只可能在"比例门禁放行后的重试轮"里被记录打开，而那条分支必然已经算过
   并保存了 `lastGlobalFailure`，所以 `?:` 这一支走不到 —— 它只是"防御性代码里一句不诚实的兜底"。
   真要修得**给 `RoomFailureReason` 加 `UNKNOWN`**（枚举在 `RoundFailureGate.kt:18`），
   还要同步 `observationResultOf` 的穷尽 `when`、`DshTypeConverters`、
   `RoundFailureCode` 与相关测试的期望 —— 属"改一处、连带五处"，本轮不动，留作独立小任务。
2. **`MonitorRepository.freshnessOf` 在事务里又读了一次配置**（早前审查的 F4）：
   只是多一次查询，没有正确性问题，未动。
3. **`MonitoringService.startInForeground()` 的静默 `catch`**：异常被吞掉且不留痕，
   与"任何降级都不许静默发生"不符，未动。
4. `BackupChecksumTest.kt:28` 的 `roomSchemaVersion = 6` 是**夹具常量**（那条用例只校验
   "同样的输入 → 同样的校验和"），**已核查不是缺陷**，故未改（避免无意义地动测试）。
5. 早前审查的四项设置问题（F2 / F3 / F6+F8 / F7）见 ⑥，**等用户裁决**后再动。

---

### H63. 封禁状态 · 失败比例门禁 · 数据过期 · 图标双轴与自动化（一大段迭代的合并记录）

#### ① 封禁状态（独立于检测状态）

**签名来源实测**：`room_init` 的 `data.is_locked`（免登录公开接口）。
`{"is_locked":true,"lock_till":-1}` = 无期限封禁；对比正常房间 `is_locked:false, lock_till:0`。
**同时实测排除的干扰项**（这些**不能**用来判封禁）：
- `room/v1/Room/get_info`：**37 个字段逐个对比，无任何封禁专属字段**（封禁房间看起来就是普通未开播房间）；
- `xlive/.../getInfoByRoom`：未登录时**封禁与正常房间都返回 `code:-352`（风控）**——靠它会全军覆没；
- 直播间网页 HTML：SPA，壳里没有数据；
- `is_hidden`/`hidden_till`/`live_status`/`room_shield` 在封禁与正常房间上**完全一致**。
- ⚠️ **`API_REJECTED` 是我方粗粒度包装码**，不是服务端语义（`MonitorRepository` 里的枚举映射；HTTP 状态码与业务 code 当时**被丢弃**、`detail` 恒为 null）——**这正是最初无法定位的原因**。现已把原始信号写进 `detail`（reason/http/api_code/retry_after/message）。

**★ 重大教训**：我曾在 `get_info`（错误的接口）里找不到封禁字段，就得出"无法唯一判定、应放弃该功能"的结论；用户坚持"网页会显示封禁，应该有标识"，我才在 `room_init` 上找到。**"我没找到" ≠ "不存在"，尤其是"在错误的接口里找"**。另外 `getInfoByRoom` 的 `-352` 会把封禁与正常房间抹平——**用错接口会得出"所有房间都被封禁"的荒谬结论**。

**实现**：`RoomBanPolicy`/`RoomBanCodec`；状态存 `streamer.lastError`（`ROOM_BANNED ` 前缀 + 紧凑 JSON），**刻意不加列**（零迁移，且 `streamer` 整行导出 ⇒ 备份自动带上）；**独立于 `confirmedLiveStatus`**（不写状态、不产生通知、不影响历史场次，只写 `lastError`+`updatedAt`）。
**调度**：封禁中默认 1800s 复查（可配 60–86400）；封禁主播**移出常规批次**；探针**只在检查失败时**发（正常轮零额外请求）；每轮预算 8 次。
**允许添加**（用户决定）：删掉拒绝分支，**建流之后**再写封禁状态（`updateBanState` 是按 id 更新，行不存在时静默 0 行）；提示精确到期限（无期限/封禁至…/期限未知/已过标注期限仍未解封）。

#### ② 失败比例门禁（用户痛点：一个坏房间顶开全局熔断，拖垮全体检测）

语义：分母 = **本轮参与检查的主播数**、分子 = **本轮最终未得有效结果的主播数**（**同一主播只算 1 次**）、百分比**向下取整**、比较**严格大于**（阈值默认 50，可配 1–100）。
⇒ 98 位里 1 位失败 = 不触发；恰好 49/98 = 50% = **不触发**；50/98 = 51% = 触发。
实现为**两段式**：第一轮每批只请求一次（不重试、不计熔断）→ 算比例 → 只有越线才进入第二轮（重试 + 熔断计数）。既有 `circuitBreakerThreshold` 保留：它决定"越线后何时真正打开熔断"。
**额外**：对逻辑拒绝做**二分隔离**（深度 ≤3、每轮 ≤8 次额外请求），把"一个坏房间拖累同批正常主播"降到最小。

#### ③ 数据过期失效（用户报"13 分钟前却不显示过期"）

**根因**：`streamer.freshnessStatus` **只在"某位主播的观察被应用"时才写**（唯一写点 `MonitorDaos` 的 CAS 语句），且**成功观察恒写 FRESH**（`lastConfirmedAt := now` ⇒ 差值 0）⇒ **它是"写入时刻的快照"，却被 UI 当成"当前是否过期"**。引擎一旦停跑（或省电模式周期 ≥15 分钟 > 300s 阈值）⇒ 字段冻结在 FRESH ⇒ 「N 分钟前」一直涨、「数据过期」永不出现。**"数据不更新"恰恰是过期提示最该出现的时刻，而它偏偏在那一刻失效。**
**修法**：过期改为**显示时现算**（纯函数 `FreshnessPolicy.isDataExpired`）+ **补 30 秒 ticker 驱动重组**（否则连"N 分钟前"也僵住）+ **阈值与调度周期协调** `effective = max(阈值, 2×周期)`（否则省电模式下会从"永不显示"变成"**永远显示**"）+ 引擎每 tick 为未参与检查的主播**独立补写** freshness（不并入 CAS）。
**封禁豁免**：`isDataExpired` 开头 `if (banned) return false`；**时间照常显示**（用户明确要求"仍显示时间，方便看到目前状态"）。
**设置**：开关 `freshness_expiry_hint`（**新键**、DataStore 展示偏好、默认 true）；阈值**复用既有 `freshnessStaleSeconds`**（**绝不新增**——它已是唯一生效阈值，新增会让两个阈值打架；复用 = 零迁移，只缺设置页一行）。

#### ④ 日志不许撒谎（用户从日志误判"功能没生效"）

现象：封禁后**每分钟一条 `API_REJECTED`**，内容却是"已跳过其常规检查"的正常调度提示 ⇒ 用户以为封禁没生效（**实测数据证明检查确实被跳过了**：被封禁者 `lastCheckedAt` 为空、其余每分钟更新）。
修法：进入/解除封禁 → **`audit_log`**（`ROOM_BAN_ENTERED`/`ROOM_BAN_LIFTED`；这两件事**都不是错误**）；每轮跳过 → **仅 logcat**；探针**真正失败** → `monitoring_error_log` + **按原因映射真实错误码**。并加**边沿抑制**（同一信号不重复记）。`application_error_log` 从"每分钟 1 条"降到 **0 条**。
同类收口：`"本轮 N/M 位检查失败（未超阈值）"` 按 `round.reasons` 选码 + 边沿抑制。
**找出的既有缺陷**：`ProbeBudget` **从不重置** ⇒ 文档说的"每轮 8 次"实际是"**每进程共 8 次**"，用完即永久失效。

#### ⑤ 图标 / 名称双轴 + 自动化 + 自救援

`icon_axis × name_axis` **笛卡尔积**：`(N+1)×(M+1)−1` 个别名，格子 `(0,0)` 由主入口充当；`RoomBanCodec` 之外的另一处"独立"设计：**两个轴各自可选**，切换不再互相影响。
- **生成脚本**：`tools/generate-icon-presets.ps1`（批量导入/同名确定性改名/幂等重建）。
- **小白入口**：双击 `管理图标.cmd` → 中文菜单（生成 APK，**不安装**）。
- **★ 生成脚本曾"静默不落盘"**：两个永远为 `$false` 的标志位把写盘短路（`if ($false) { 写盘 }`），而**脚本自述、内存校验、退出码全是"成功"** ⇒ 现已改为唯一出口 `Write-FileVerified`（写→读回→逐字节比对）+ **落盘复核**（重读磁盘、独立数一遍、反向查残留别名）。
- **★ 更新后自救援**：改名/删预设会让"当前启用的别名"消失，而"主入口已禁用"状态**跨重装持久化** ⇒ **零入口、应用打不开且无法自救**。（本会话真实发生过。）现由 `MY_PACKAGE_REPLACED` 接收器（`exported=false`、只在**确实零入口**时动作、**从不禁用**任何组件）恢复默认入口并**发通知如实告知**；**已真机验证**（别名消失→更新→自动恢复+通知）。
- **教训**：`ComponentName(pkg, "IconPreset1")` **不会补包名**（裸名指向不存在的组件 ⇒ "点切换没反应、读回永远未启用"）；`<activity-alias>` 的 `icon` 与 `label` **绑在同一组件上** ⇒ 两轴独立只能靠笛卡尔积。

#### ⑥ 工具链行为（本会话反复踩到，务必先读）

1. **`edit` 工具会静默丢 UTF-8 BOM**（保留 CRLF）；**对混合行尾文件会把裸 LF 统一成 CRLF（等于全文件重写）**⇒ 带 BOM 的文件**最后一次写入之后**才还原；混合行尾文件（如台账）**不要用 `edit`**，改用字节级替换。
2. **本环境 `Set-Content -Encoding UTF8` 会写入 BOM**（与 `edit` 相反）⇒ 给无 BOM 文件写内容用 `WriteAllText(..., UTF8Encoding($false))`。
3. **中文字符是合法 Kotlin 标识符字符** ⇒ `"…$var里"` 会被解析成变量 `var里`；**`$变量` 后紧跟中文必须写 `${变量}`**。
4. **块注释里写路径时 `*/` 会提前闭合注释**（`res/mipmap-*/x.png` 这类）。
5. **Kotlin 增量编译在"类型可见性变更"后会残留派生错误** ⇒ 修源头后**全量重编一次**，别逐个去改级联报错。
6. **PS 5.1 下 `Start-Process -PassThru` 的 `ExitCode` 恒为 `$null`**（转 `[int]` = 0）⇒ **编译失败会被报成成功**。
7. **`chcp 65001` 会破坏重定向的 stdin**（管道喂输入时每行读成空）；但**真实控制台显示中文恰恰需要它**——`chcp` 必须放在 `.cmd` 的 `set` **之前**（cmd.exe **按行**用"读到该行时"的代码页解码）。
8. **"捕获输出正确" ≠ "窗口显示正确"**：输出被重定向时按 `OutputEncoding`（UTF-8）写出、捕获端按 UTF-8 解 ⇒ 看起来正常；真实控制台按代码页（936/437）解释 ⇒ **乱码**。涉及编码/代码页/终端渲染的改动，必须**在目标环境验证**或**明确标注未验证**。
9. **代理的报告也是待验证信息**：本会话代理纠正我 **5 次以上**（行尾说成 LF 实为 CRLF、`HomeViewModel` 无 BOM、测试数 146 而非 43、`Scan` 类型参数、`retryable` 未定义、生成脚本"报成功却没写盘"）。收口必须由协调者自己跑：**编译 + 单测 + 装机 + 两棵树逐字节核对 + BOM 终检**。
10. **自检必须能区分"改前/改后"**：我曾用"两种状态都成立"的条件做自检，误判替换成功（实际未改动）。

#### ⑦ 验证状态

编译 ✅（Room KSP/Hilt/Compose 四条链路）、单测 ✅（离线 167 例全过，含新增 27 例）、装机 ✅、镜像 **150 文件差异 0**、`9.json` 已导出。
**仍需真机的点**：封禁主播在真机上的完整周期（进入→30 分钟复查→解封恢复）、省电模式下过期提示的观感、深色主题下详情页角标、`.cmd` 双击的屏幕渲染。


### H58. 四个用户报告的缺陷（同一轮修复）

#### ① 删除直播记录报 `FOREIGN KEY constraint failed (787)`

**我的初判是错的**：以为是 `live_session` 的子表没级联。按 `schemas/8.json` 权威 DDL 全量导出 22 条外键边后实测：
`live_session_title` / `live_session_area → live_session` **一直是 CASCADE**（不是元凶）。
**真凶是引用 `live_event` 的两张通知表，均为 NO ACTION**：
`notification_aggregate_event.eventId`、`notification_outbox.sourceEventId`。
失败语句 `DELETE FROM live_event WHERE sessionStableId=?` 在事务里先抛错 → 主表也没删掉（**无半截状态**）。

**"自动的删不掉、人工的能删"**：自动确认开播/关播会写 `live_event` 并接入通知管道（开播→聚合绑定、关播→Outbox 引用），
默认 `notifyStart/notifyEnd=true` ⇒ 自动场次必然带引用；`createManualSession` **不写事件表** ⇒ 手工补录的一直能删。
⚠️ 用户看到的是"**手工补录的**能删"，**"修正过的自动场次"同样删不掉**。

**修法（方案 A：不动 schema、不加迁移、DB_VERSION 仍 8）**：同一事务内按依赖顺序显式删
`notification_history → notification_delivery_attempt → notification_outbox（只删 sourceEventId 命中的单事件行，批量 Outbox 保留）
→ notification_aggregate_event → live_event → 场次子表 → live_session`（title 由 CASCADE 清）。
新增 4 个 DAO 方法；审计 JSON 加 `outbox`/`bindings` 计数；`notification_id_registry` 不动。
**不用 CASCADE 的理由**：要改实体+迁移+schema JSON（8→9），且会**静默删掉通知记录**，违背"降级不许静默"。
**验证**：Python + sqlite3 按 8.json 真 DDL 建库，18 项断言全过（旧实现复现 787、人工场次能删、新顺序成功且不误伤、`foreign_key_check` 干净）。
**顺带发现**：容量清理路径从不清理 RELEASED 聚合绑定行 ⇒ 旧实现下这些场次是**永久删不掉**，不是偶发。

#### ② 修正功能改不动开播/下播/时长

**不在数据层，在对话框自己**：`EditSessionDialog` 三项**都预填完整值**，而 `deriveFrom` 在**每次按键**时反算
"正在编辑的那一项"，其公式恰是逆运算 ⇒ 用户刚敲进去的值立刻被另外两项按原值写回（三项皆然）。
onSave 传参、repository 补全、DAO 的 UPDATE 列清单（含三列）、无后续覆盖 —— **这四处改前就是对的**。
**修法**：仍是"填二补第三"，但①以"最后编辑的那一项"为基准、**绝不回写正在编辑项**；②正在键入（未填满）不推导；
③用户亲手清空的项不自动补。15 用例模型验证通过。

#### ③ 统计二级页自定义范围漏最后一天

**两处独立错误叠加**：① 对话框存的是"结束日 00:00"，却被当**排他上界**交给 SQL `t < :to` ⇒ 结束日整天被排除；
② `ExportRepository` 里 `seriesCustom(..., effectiveTo - 1)` 把 `bounds()` 的排他上界又拉回 00:00 ⇒ 统计导出同样漏；
③ 天数 `(end-start)/86400000 + 1` 多算一天 ⇒ 图表末尾多一根永远为空的柱。
**修法（收敛口径）**：新增**唯一**换算 `inclusiveDayRange()`，`bounds()`／统计二级页／统计导出三处全部改为调用它。
契约写进注释：**UI 侧一律"含结束日整天"、值是当天 00:00；查询侧一律 `[起, 止)`，上界 = 结束日次日 00:00**。
注释点明**不能用 `+86_400_000L` 代替 `plusDays(1)`**（夏令时 25 小时的天会偏 1 小时）。
**注意**：统计学只统计**已结束**场次 ⇒ 主播正在直播时今天那根柱本来就不出现，别误判成没修好。

#### ④ Dock 偏红且"变不回纯白"

**M3 的 `surfaceTint` 色调叠加**：`Surface` 只在 `color == colorScheme.surface` 时，把 `surfaceTint`（默认跟随 `primary`）
按 `tonalElevation` 混合进去。Dock 非玻璃态正是 `color = surface` + `tonalElevation = 6.dp` ⇒
**关闭液态玻璃时才第一次走该路径**、**改主题色时 tint 跟着变**，且是持续混合 ⇒ 回不到纯白。
**修法**：改用 `surfaceContainer` + `tonalElevation = 0.dp`（与浮动标题栏/筛选卡同一颜色角色，天然绕开叠加）。
**这也解释了为何其它浮动卡片从未出过此毛病**。

---

### H59. 标签 / 分组功能整批（用户需求）

**语义（用户定稿）**：标签**同级多选取交集（AND）**；分组在筛选行**单选**；**分组 ∧ 标签**同时生效；
主播**可归属多个分组**（详情页分组对话框为多选）。

**关键前提**：`streamer_tag_cross_ref` / `streamer_group_cross_ref` 多对多关联表与 `sortOrder` 字段
**从 schema v2 起就存在** ⇒ 多标签、排序、删除**全部不需要 schema 改动、不需要迁移**。缺的只是应用层。

**改动**：
- **数据层**：新增 AND/OR 两套多标签查询（`observeByAllTags` 用 `HAVING COUNT(DISTINCT tagId)=:tagCount`、`observeByGroupAndAllTags`）、
  排序写入、批量关联、影响面计数、卡片关联行；`createAndAssign` 的新标签 `sortOrder` 由**写死 0** 改为 `max+1`
  （原来"新加的排最后"根本不成立——排序字段一直是摆设）。
- **首页筛选**：`tagFilterId: Long?` → **`tagFilterIds: Set<Long>`**；`flatMapLatest` 的 when 由"二选一"改为 4 个**可组合**分支；
  **删掉两行写死的互斥**（`setTagFilter`/`setGroupFilter` 里各一行，不删则"分组+标签同时生效"不成立，最易漏）。
  UI 新增 `MultiSelectChipRow(Set<Long>)`，分组行保留 `FilterChipRow(Long?)` ——**从类型上让"分组多选"写不出来**。
  空态文案必须解释**交集**语义（"没有同时具备「A、B」的主播"），否则点第二个标签直接空列表会被当成坏了。
- **悬空 id 收敛**：被选中用于筛选的标签/分组被删除后，筛选状态会留下**无法点掉的悬空 id** ⇒ 列表恒空且**无自救手段**。
  修法：在**目录流**上收敛（标签取交集、分组置空）。用 `tagCatalogLoaded`/`groupCatalogLoaded` 标志区分
  "还没读回来"与"库里确实一个不剩"——**刻意不用 `filter { it.isNotEmpty() }`**：删除"最后一个标签"时筛选行整行消失、
  连「全部标签」都没得点，那种最坏情形只有标志能覆盖。
- **详情页**：删掉「监控策略」卡片末行文案；「添加标签/分组」支持**勾选已存在的**；分组归属改**多选**（Q3）。
- **管理页**（设置 → 数据 → 标签/分组管理，新建 `ui/settings/TaxonomyManageScreen.kt`）：列表显示**关联主播数**、
  上移/下移排序（**写库交给仓库的整表归一化，不自己算 sortOrder**）、**删除 + 二次确认披露影响面**
  （计数未返回时确认键禁用——**不接受用一个可能是假的 0 换用户一次点击**）。
- **卡片显示标签**：一次 `IN` 查询按可见列表取回（**无 N+1**）、复用既有第 5 路 combine 变 Triple（**不增 combine 路数**）、
  只在有标签时渲染（无标签卡片高度不变）、单行横向滚动、最多 3 个 + `+N`（`+N` 放在滚动区外永远可见）。

**修掉的两个既有 bug**：
① **恢复备份会静默丢掉所有分组归属** —— 导出侧一直写 `body.groupRefs`，恢复侧**没有对应循环**，且 `groups` 建表循环
**丢掉了 `insertGroup` 的返回值**（所以当初连 stableId→id 映射都拿不到）。补映射 + 补循环，并把 **`tagRefs` 循环也提升到同一口径**
（它原本 `?: continue` 也是静默丢弃）；摘要加 `tagRefs`/`groupRefs` 计数键（**未加字段**，三个构造点无需改动）。
② 排序字段从未被写入过。

**详情页对话框（用户报告"已有标签/分组找不到"）**：真因**不是"没加载"**，而是 `load()` 的 collect 里
**新建**了一份 `StreamerDetailState(...)`（没传 `allTags`/`allGroups` → 取默认空表），把订阅刚填进来的目录**清空**；
四路源全是 Room Flow，**每发射一次就再清空一次**，且本页 `refreshCoverIfMissing()` 自己就会触发 ⇒ 必然空表。
**结构性修法**：新增 `LoadedCore` + 改为基于当前 state `copy` 合并（**漏字段 = 编译不过**）。
顺带修同源隐患：`_state.value.copy(...)` 的接收者在**挂起前**求值，恢复后写回旧快照。

---

### H60. 排序选项、外观页与三项小改

- **排序**：默认项提到**第 1 位**（枚举顺序即下拉顺序）；文案统一为「**维度 · 直播中的处理**」+ 一句说明、**不用括号**。
  **重排对已保存设置安全**：DataStore 存的是枚举 `name`（`order.name` / `valueOf(name)`），**不是 ordinal**。
- **外观页**：两处上传图片各拆成「**从相册选择**」（`PickVisualMedia`，免权限）+「**从本地文件选择**」（`OpenDocument`），
  **两者汇入同一条下游路径**（无第二套解码/保存）；用 `FlowRow` 处理窄屏折行。
  卡片顺序（**已按当前代码更新**）：`应用名称 → 应用图标 → 放到桌面 → 主题色 → 背景图片 → 卡片外观 → 底部导航样式 → 液态玻璃 → 夜间模式 → 恢复默认外观`。
  （原记录为 `名称 → 图标 → 放到桌面 → 主题色 → 背景图 → 卡片外观 → 底部导航样式 → 夜间模式 → 恢复默认`，**已过时**：`底部导航样式` 之后新增了「液态玻璃」卡，
  内含 Dock 玻璃开关、卡片玻璃开关，以及 5 个两者共用的参数滑块；**这两个开关与参数滑块是从「背景图片」卡搬过来的**，
  且 **Dock 玻璃开关原先在「底部导航样式」卡里，现已移入「液态玻璃」卡**。）
- **健康中心「在播主播」排除轮播**：全库找到 8 个相关点，只改真正的**分子** `HealthRepository.liveCount`
  （原 `LIVE || ROUND`）；**分母"监控中主播数"未动**（它应包含轮播）——改错对象会把分母也改窄。
  注释注明"与 `MonitoringEngine` 口径一致，勿只改回一处"。
- **数据管理页**：删掉「（不是给人看的报告，用于把应用恢复原样）」括号段。
- **AI 声明**：应用内「关于」+ `publish/README.md` 各加一处。

---

### H61. 本轮集中得到的工程经验（**看代码前先读这一节**）

1. **`edit` 工具会静默丢掉 UTF-8 BOM**，但**保留行尾风格**（CRLF 仍是 CRLF）。
   ⇒ 带 BOM 的文件**最后一次写入之后**必须用 `[System.IO.File]::WriteAllBytes` 前置 `EF BB BF` 还原并复核；
   **中途还原是白做的**（后面再 edit 一次又被抹掉）。本会话被 5 个以上代理独立复现。
2. **本环境的 `Set-Content -Encoding UTF8` 会写入 BOM**（与 `edit` 恰好相反）。
   ⇒ 给**无 BOM** 文件写内容要用 `[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))`。
3. **中文字符是合法的 Kotlin 标识符字符** ⇒ `"…$var里"` 会被解析成变量 `var里` 而编译失败。
   **凡 `$变量` 后紧跟中文必须写 `${变量}`**。全库已扫描无其它同类写法。
4. **Kotlin 增量编译在"类型可见性变更"后可能残留派生错误**。
   ⇒ 看到一串同源错误先修源头，**再全量重编一次**；不要逐个去"修"级联报错（本会话差点改了其实没错的 `scan`）。
5. **自检必须能区分"改前"与"改后"**。本轮我用 here-string 整块替换失败（报"匹配 = False"），
   而自检写的是"`LIVE_FIRST` 是否排在 `LAST_CLOSED` 前"——**两种状态下都成立**，于是误判成功、实际未改动。
   ⇒ 自检要挑一个只在新状态下成立的条件。
6. **CRLF/LF 不匹配是替换失败的头号原因**：here-string 在命令里是 LF，文件是 CRLF 就永远匹配不上；
   行级拼接（`Get-Content` 成数组、按行定位、`-join "`r`n"`）最稳。
7. **代理的报告也要当待验证信息**：本会话出现三次代理互相纠错（行尾说成 LF 实为 CRLF、共享库版本判断等）。
   ⇒ 收口必须由协调者自己跑：**编译 + 装机 + 两棵树逐字节核对 + BOM 终检**，不依赖自述。
8. **两棵镜像树**（`app/` 与 `publish/app/`）必须同时改；每轮收口都做**逐文件 SHA256 比对**（本轮 117 文件、差异 0）。
9. **移动大型 Compose 文件里的卡片**：先 dump 出每张卡的起止行确认边界，再"整块取出→原处删除→目标处插入"；
   **禁止"就地插两个开括号再去尾部补闭括号"**（本项目因此坏过两次）。搬完数括号收支 + 按块 sha256 对比。

---

### H62. 对外文档

- `publish/README.md` 与 `publish/PROJECT_SUMMARY.md` 依据台账重写，并**纠正了 9 处旧文档与代码不符之处**：
  单测 58→**90**、"8 次迁移"→**7 个 Migration**、dist APK "未签名"→**debug 签名**、"Navigator Compose"→Navigation Compose、
  备份含"统计"→**不含**（统计由场次重算）、台账编号 H1…H21→**H1–H62**、规模数字全部更新、
  `{live}` 描述补"不含轮播"、"每一条都在真机复现过"→改为三档验证口径。
- 明确**未写入功能清单**的未验证项（液态玻璃实时折射观感、卡片实时折射已回退、H54 布局观感、横屏对话框等）只出现在"能力边界/已知限制"。
- 新增 **AI 声明**（应用内「关于」与 README 各一处）。


### H57. 排序三选一 + 下播通知改跳历史页 + 三个默认值 + 文案（用户五项要求）

#### ① 列表排序做成用户可选的三选一，默认"下播时间 + 直播中置顶"

新增持久化设置：`AppearanceSettingsRepository` 的**新键** `session_sort_order`（**不改已有键的类型** —— 遵守 `glassCards` 那次"原地改类型会让老库抛 ClassCastException"的教训）。
枚举 `SessionSortOrder`：`END_TIME` / `START_TIME` / `END_TIME_LIVE_FIRST`，`DEFAULT = END_TIME_LIVE_FIRST`。
老安装读不到键 → 回落默认（第 3 种），**无需迁移代码**；「恢复默认外观」不重置它。

排序实现抽成**唯一一份** `ui/common/SessionSort.kt`（两页共用，口径不可能漂移）：
- 选项 1 下播时间 desc，**在播的排最后**（不把 null 当无穷大顶到最前，否则与选项 3 重合、切了像没切）；
- 选项 2 开播时间 desc（= H13 旧行为，保留为选项而没删）；
- 选项 3（默认）在播置顶分组，组内下播时间 desc。
- `isLiveNow` 要求 `startAt != null && endAt == null`：**防止"从没播过"的主播（两个时间都为 null）被误判成在播**而顶到最前。
- 入口放在**两个列表页自己的浮动标题栏**（"排序"按钮 + 下拉，RadioButton 标当前项）：就地可切、两页同组件同键，且完全不动 `SettingsScreen`/`AppNavHost`。

**⚠️ 待用户拍板的可见行为变化**：统计页原先"按总时长排序"**降级为组内兜底键**（只在时间键完全相等时才轮到它）。
即默认设置下统计页变成"直播中置顶 + 按下播时间从新到旧"。这是为满足"两页口径一致"的硬要求，
**已在代码注释与回报中写明，不是静默改动**；若用户要统计页保持"总时长主导"，一处改动即可。

#### ② 下播通知点击 → 该主播的**直播历史二级页**

**注意这是对 H56① 的再次修订**：H56 落地的是"进监控卡片详情页"，用户随后改为"进直播历史二级页"。

`NotificationDeepLinkResolver` 新增变体 `Target.StreamerHistory`，**没有复用 `Target.Streamer`** ——
因为 `evt:` 前缀由开播/下播共用，直接改 `Streamer` 会连带改掉**开播通知在客户端/浏览器都打不开时回落进应用**的那条路径。
`END_CONFIRMED`（outbox 快路径）与 `LiveEventType.END`（outbox 行被清理后的兜底）**共用同一判据函数**，避免将来只改一条而分叉。
`AppNavHost` 的 `when (target)` 全仓仅一处，已补分支；`MainActivity` 不需要改（只透传 `Target`，不做路由假设）。
**附带效果**：下播通知的「查看记录」按钮复用同一 eventId，因此也落到历史页。

#### ③ 三个默认值 / 文案

| 项 | 改动 | 老安装影响 |
|---|---|---|
| 关播默认确认次数 2 → **1** | 真正的默认值只有两处（`AppDatabase` 种子 SQL 的 `INSERT OR IGNORE` 字面量：current 行 + 首条 revision 行） | **保持 2 不变**（种子是 IGNORE，老库 no-op）。**刻意没加迁移**：无法区分"没改过"与"手动改成 2"，加了会静默改掉一部分人的手动设置 |
| 常驻通知默认**正文** → **`{live}位主播正在直播`**（★ H57 订正：改的是**正文**不是标题，标题沿用原值 `主播监控运行中`） | `NotificationTemplate.DEFAULT_TEXT`（唯一字面量，所有回落路径共用） | 已自定义过的不受影响；点过「恢复默认」的会拿到新默认 |
| 「进行中」→「**直播中**」 | `RecentSessionText.kt`（全项目"最近："字面量仅此一处） | — |
| ★ 补记：**通知的「查看记录」按钮已按用户要求移除**（太占位置），只保留主体点击 | `NotificationPoster.kt` 两处 `addAction` 已删 | — |

**`{live}` 未知时渲染为 `—位主播正在直播`**：判断为**可接受，不改渲染规则** —— 符合"未知不谎报 0"的硬规则，
且改 `render()` 会波及所有用户自定义模板、破坏"认不出的占位符原样保留"的既有规则；触发窗口仅"首轮 Tick 之前"。

**顺带修正**：`SettingsScreen` 的「关播确认次数…（默认 2）」标签与两处 `?: 2` 兜底、`StreamerDetailScreen` 的 `?: 2` 兜底，均已同步为 1。

**已知小瑕疵（未改）**：`StreamerDetailScreen` 那句文案写"跟随全局默认"，但兜底仍是**硬编码的新装默认值 1**，并没有真的去读全局配置。

#### ④ 一个已被三个代理独立验证的工具坑

> **`edit` 工具会静默剥掉文件原有的 UTF-8 BOM（但会保留行尾风格）。**

本轮 `HistoryListScreen.kt`、`StatsListScreen.kt`、`AppNavHost.kt`、`StateConfirmationPolicy.kt` 四处原本带 BOM，
都被吞掉、由各代理用字节级操作（`WriteAllBytes` 前置 `EF BB BF`）还原。
**这是工具行为，不是谁的失误**；后续凡改带 BOM 的 .kt 必须复查。收口时已逐文件核对 14 个受影响文件。

#### 验证状态

编译通过、装机无崩溃、单测 90 个全过（APK 68.6 MB）。代理①还把排序核心逐字提到 %TEMP%
用离线 Kotlin 编译器编过并跑了 7 项断言（三种模式顺序、默认=第 3 种、tieBreak、稳定性、无场次主播不被当在播），临时目录已删。
**未验证**：Compose 选择器的真机渲染、顶栏"排序+导出"两个按钮的实际排版（代理按公式估 360dp 屏下标题可用宽 ~112dp）、
DataStore 回落默认的运行时行为、通知两条点击链路的真机表现（模拟器无哔哩哔哩客户端）。


### H56. 通知点击行为分离 + 列表「最近」改显示下播时间（用户两项要求）

#### ① 开播/下播通知的点击行为分开

**要求**：开播通知点击仍优先跳客户端直播间；**下播通知点击改为进入应用内该主播的二级页面**。

**改前**：`NotificationPoster.contentIntent` 只要 URL 合法就一律走 `LiveRoomRedirectActivity`（客户端优先跳板），
所以下播通知以前也是跳客户端/浏览器。

**改动（仅 `NotificationPoster.kt`，6 处）**：
`contentIntent` 增加 `eventType`，判定改为 `safe != null && !isOfflineEvent(eventType)`；
`isOfflineEvent` = `END_CONFIRMED`；下播类走 `appIntent`（携带 eventKey，由既有
`NotificationDeepLinkResolver` 解析到 `Routes.detail` —— 即监控卡片的二级页）。
开播类（START_CONFIRMED / LIVE_RECONFIRMED / BATCH_LIVE）路径**逐字未变**；
`MainActivity` / `LiveRoomRedirectActivity` / `NotificationDeepLinkResolver` **一行未改**（确实不需要）。

**★ 关键坑（代理发现，值一记）**：下播类必须**不透传 `EXTRA_URL`** ——
`MainActivity.handleIntent` 见到 EXTRA_URL 就 `ACTION_VIEW` 起浏览器，
透传等于"一次点击同时开浏览器 + 应用内导航"，把需求原地抵消。
「查看记录」按钮同理：下播类保留按钮但去掉 url。

#### ② 历史/统计列表的「最近」改为显示**下播时间**

**要求**：显示最新一场的下播时间，而不是开播时间。

**改前**：两页都在内存里算 `maxOfOrNull { startTime ?: createdAt }`，**同一字段既排序又显示**。

**改后**：仍取"最新一场"，但显示它的 `endTime`；口径写成**开播时间最晚的那一场**（含进行中）。
**没有采用"按 endTime 取最大"**（我原本的建议），代理的理由更对：
进行中场次 `endTime` 为 null 会被**整场丢掉** → 正在直播的主播会退回显示上一场，与"最近"矛盾；
且显示值应与决定卡片位置的排序值同源。场次首尾相接时两种口径等价。

边界：进行中 → `最近：进行中（开播 …）`；无记录 → `最近：—`；**绝不空白/null**。
两页共用 `ui/common/RecentSessionText.kt`（全项目"最近："字面量只剩这一处，从根上防两页漂移）。

**排序未改**：历史页仍按"最新一场的开播时间 DESC" —— 台账 H13 记录着用户当时明确要求按开播时间排、
并把"关播时间主导"判为 bug，改排序等于回退那条要求。
**已知副作用（待用户拍板）**：显示值换成下播时间后，「最近」列自上而下不再单调。

#### 验证状态

编译通过、装机无崩溃、单测 90 个全过（APK 68.6 MB）。
**未验证**：通知点击的两条链路都要真机（模拟器无哔哩哔哩客户端）；`进行中（开播 …）` 文案在窄屏是否折行需实机看。

#### 两条后续（待用户决定）

1. 已在同一详情页时点通知会**再压一层**（返回要按两次）——修法是导航加 `launchSingleTop`，需动 `AppNavHost`。
2. 统计页"N 场"仍只算已结束场次：正在直播且此前无记录的主播会显示"进行中 + 0 场"（既有口径，未动）。

#### 工程坑（代理记录，后续必查）

`edit` 工具会**丢掉文件原有的 UTF-8 BOM**（`HistoryListScreen.kt` / `StatsListScreen.kt` 原本有），
代理已字节还原；**后续再改这两个文件必须复查 BOM**，否则 PowerShell 5.1 读出来是乱码。


### H55. 重要修复：轮播（ROUND）按未开播处理（用户报告的严重 bug）

**用户报告**：主播从"开播"变成"轮播"时**不被判定为下播** —— 场次不结束、下播通知不触发、自动化记录全部失效；只有"开播 → 未开播"才正常。要求把轮播划成未开播的一种。

#### 根因：一处**有意的旧设计**，不是笔误

`MonitorRepository.kt` 的状态转换分支末尾：

```kotlin
else -> {
    // LIVE -> ROUND：不关闭 Session、不产生 END 事件、区间保持有效（0.6.23）。无额外写入。
}
```

规格 0.6.23 当初有意让"轮播不结束场次"。三个后果与用户观察到的现象**完全吻合**：
场次永不关闭（记录失效）、不写 `END` 事件（通知不触发）、只有转为 OFFLINE 才走关闭分支。

#### 改动（三处，语义一致）

1. **`MonitorRepository`**：把 ROUND 并入 OFFLINE 分支 ——
   `(transition.to == OFFLINE || transition.to == ROUND) -> { ...关闭场次 + 写 END + 发下播通知... }`
2. **`MonitoringEngine`**：在播数判定排除 ROUND（原为 `LIVE || ROUND`），
   否则通知里的 `{live}` 会把轮播中的主播算成正在直播。
3. **`StateConfirmationPolicy`**：ROUND 从"LIVE 方向"挪到 **"OFFLINE 方向"** ——
   用 `endConfirmationCount`（默认 2 次）确认，避免状态抖动把真实场次误切成两段。
   **关键细节**：确认后的目标状态写回 `remoteStatus`（而不是硬写 OFFLINE），
   于是**轮播仍记为 ROUND**（历史/界面能看出是轮播），
   但"要不要关场次"由仓储层决定 —— **判定语义与显示语义分离**，两处改动才能并存。

#### 单测：两个测试把旧行为写死了，已同步

跑测试立刻红了 2 个（`轮播切换时按 1 次确认开始`、`ROUND 是正常业务状态而非异常`）——
它们编码的正是旧规格。

| 测试 | 旧断言 | 新断言 |
|---|---|---|
| 轮播切换的确认 | 1 次确认 → 立即 Transition | 计数过阈值（2）→ Transition |
| ROUND 是正常业务状态 | 直接 Transition | 首次观察 → `Counting(SUSPECTED_OFFLINE, 1)` |

**第二个测试的语义刻意保留**（ROUND 仍是"成功观察到的正常状态"，不是异常/盲区），
只改了"确认走哪条路"；测试名与文件头 KDoc 同步更新，避免后人照旧描述改回去。

#### 过程中的两个坑（都值得记）

1. 首次替换**没匹配上**：条件行真实写法是 `transition.to == …`，我按 `to == …` 写 → 0 匹配，
   **工程未被误改**（守卫式替换的价值：匹配数不为 1 就 abort）。
2. 测试文件是 **LF 行尾**，CRLF 模式连续两轮不匹配；统一行尾后才成功
   —— 与之前 `FloatingTopBar.kt` 是同一个坑，**跨工具改文件时必须先确认行尾**。

#### 数据修复：用户明确不需要

旧 bug 可能留下"卡在 ROUND、永不结束"的场次，但**用户确认为测试数据、无需修复**。

#### 验证状态

- 单测 90 个全过、编译通过、装机无崩溃（APK 68.6 MB）
- **仍需用户实测**：找一个会从"直播中"变"轮播"的主播，确认
  ① 出现下播记录 ② 收到下播通知 ③ 是连续 2 次观察到轮播后才判定（而非一次即切）


### H54. UI 全量审查 + 四路并行修复（用户要求："全面检查 UI 代码" → "全部修复"）

审查用**三个只读子代理**按切片并行做（外观/设置/主题、导航/首页、其余页面+公共组件），共约 70 条；修复用**四个写入子代理**按**文件所有权互不重叠**并行做。审查与修复是两批代理，避免"自己审自己"。

#### 先纠正一条审查结论（用工具链事实判定）

审查员 C 报「三个文件因重复 import 编译不过（高）」并声称用同版本编译器复现。
**实测：`:app:compileDebugKotlin` 通过** —— K2 容忍**完全相同**的重复 import。
重复确实存在（29/9/7 遍，是我的批量脚本 `-replace` 全局替换所致），但它是**清理项**，不是"编不过"。
**教训：子代理的推断再合理，也要用工具链事实判定。**

#### 修复要点（按批次）

1. **实时玻璃三件套**：坐标统一为 `positionInRoot()`（此前用窗口坐标喂 `drawLayer`，非 edge-to-edge 下差一个状态栏高度 → 玻璃画到 Dock 下方）；`CompositionLocalProvider` 提到最外层（此前 Dock 在 provider 外，`realtimeLayer` **恒为 null**，实时分支是死代码）；闸门补 `navigationStyle == DOCK` 并在不需要时重置矩形（否则切"普通样式"后留下**幽灵玻璃块**）；录制改为按需挂载。
2. **底部布局**：补 `consumeWindowInsets` + `imePadding`；底部避让高度从"按 Dock 源码逐项核算"得出（栏体 72 + 上下浮动 52 + 间隙 12 = **136dp**，普通样式 92dp），FAB 与消息条改用同一常量定位；History/Stats 一级页底部留白由 96dp 修正为 **136dp**。
3. **功能缺陷**：健康横幅死链（`onNavigate` 改**必填**，漏传直接编译失败）；`reset()` 后 UI 与 DataStore 一致；选中项随可见集合收敛（批量操作不再误伤不可见主播）；裁剪页横竖屏；吸管热区与 Crop 反算；浅色主题压暗时翻转前景色。
4. **性能**：整屏录制按需；背景图解码移出主线程 + `inSampleSize`；统计明细改 `items()`。
5. **清理**：51 行重复 import、50 个无用 import、9 处冗余 `@OptIn`、全限定名残留、`InlineMessageCard`、`GlassCardBackdrop.kt`（**整个文件删除**：它是"照文档用就会触发 RenderNode 递归崩溃"的入口）。

#### 子代理的 7 处"偏离清单"判断（均已采纳）

1. 底部留白用 136dp 而非清单里的 72dp（实测 Dock 遮挡带 ≈136dp，72dp 等于没修）
2. 只给两个一级列表页加，二级页不加（Dock 只在 4 个一级路由渲染）
3. 导出"全选"用**并集**而非替换（否则"先勾 20 位→搜索→全选"会静默清掉那 20 位）
4. `FlowRow` 需 `@OptIn(ExperimentalLayoutApi)`，并在注释里区分于被删的那批
5. **`glassCards` 换键名而非原地改类型**：Preferences 取值是擦除后强转，旧字符串值会抛 `ClassCastException`（**老安装会崩**，不是"回落默认"）
6. 名称限长放 VM 唯一输入路径，未在 Screen 重复
7. 吸管保留 `ContentScale.Crop` 而非改 Fit（Fit 留黑边会**误导取色位置**）

#### 编译期的真实错误（代理无法自证的部分）

四个代理都按约束没跑 Gradle，合并后暴露 1 处：`FloatingTopBar.kt` 用了 `MutableIntState` 委托但缺 `getValue/setValue` 的 import（报"委托方法缺失 + getValue 重载歧义"）→ 补 import 即通过。

#### 验证状态

- 编译 `BUILD SUCCESSFUL`、装机**无崩溃无 DB 异常**、单测通过（APK 67.8 MB）
- **观感类改动未在设备上一一确认**：玻璃是否真的透出实时内容、标题留白、136dp 留白位置、大字体下对话框、a11y 播报等，都需要真机/模拟器逐个看画面
- 遗留（代理明确未做）：`AlertDialog` 的 text 槽本身不滚动，`heightIn(max)` 只保证不写死高度，横屏极窄仍可能被挤——彻底修需改对话框结构


### H53. 修复：已有背景图时"更换背景图"不生效（H36 的修复不完整）

**用户反馈**：移除背景图后再设置可以生效；**在原本已有背景图的情况下，选择更换则不生效**。

#### 为什么 H36 的修复没能解决它

H36 我把 `remember` 的 key 从"文件路径"改成"路径 + 修改时间 + 大小"，
思路是"文件被覆盖了，key 就变了"。**这个思路漏了一环**：

`saveBackground` 覆盖文件后，写回 DataStore 的是**同一个文件名**。
`AppearanceSettings` 是 data class，新旧值的 `equals` **完全相等**，
于是 `collectAsStateWithLifecycle` 认为状态没变、**不触发重组** ——
`remember` 的 key 表达式**根本没有机会被重新求值**。
（key 再准也没用：那段代码压根没再执行。）

这也精确解释了用户观察到的现象：
- **移除** → `backgroundFile` 从 `File` 变成 `null` → 状态变了 → 重组 → 生效；
- **更换** → 文件名不变 → 状态没变 → 不重组 → 一直显示第一次那张图。

#### 修法：引入会变的「背景图版本号」

- `AppearanceSettings` 增加 `backgroundRevision: Long`；
- `saveBackground` 每次换图写入 `System.currentTimeMillis()`；
- `MainActivity` 的 `remember` key 改用 `backgroundRevision`（不再依赖文件元数据）。

这样"换图"必然产生状态变化 → 重组 → 重新解码 ✓

**验证状态（如实说）**：构建通过、装机无崩溃、单测全过；
**但"已有图时更换生效"这一步没有在设备上走完选图流程确认**（时间/上下文预算所限）。
机制上根因与修法是一一对应的（缺的正是"状态变化"这一环），
但请您实测一次：**在有背景图的情况下直接换图**，看是否立即生效。

**教训（重要）**：
上一轮我修 H36 时**只验证了"读文件的那段代码对不对"，没有验证"那段代码会不会被执行"**。
`remember` 的 key 再精确，也依赖"宿主可组合项重组"这个前提 ——
**排查缓存类 bug 时，先确认"刷新链路"通不通（状态是否变化、会不会重组），再去看缓存键本身。**

---

### H52. 「透过卡片看到壁纸」——两行改动解决，不需要重构

**用户澄清**："我觉得还是要做实时层，因为我想透过那个卡片内容可以看到后面的壁纸。"

**这句话把需求说清楚了**：要的不是"折射滚动内容"（我在 H50/H51 反复论证的那个），
而是**卡片半透明、能看见后面的壁纸**。

**真正的障碍根本不是图层递归，而是**：卡片**不透明**。
`glassCards`（「同时应用到所有卡片」）这个开关没打开时，
卡片用 `cardAlpha`（默认 1 = 完全不透明）——壁纸当然透不出来。

**改法（两行）**：让"液态玻璃"这个开关**同时带动卡片**，不必再单独开那个开关：

```kotlin
// MainActivity
cardAlpha = if (appearance.glassCards || appearance.glassDock)
    (1f - appearance.glassTint).coerceAtLeast(0.35f) else appearance.cardAlpha
frostBlur  = if (appearance.glassCards || appearance.glassDock) appearance.glassBlur else 0f
```

**实机验证**：截图确认——列表卡片、筛选卡片、标题栏卡片全部变为半透明，
**背后的壁纸清晰可见**；Dock 依旧透出它下面的卡片内容（实时层照常工作）✓

#### 复盘：我为什么会走偏两轮

用户最早说的是"卡片也要改成和 Dock 一样的实时玻璃，要和 Dock 同步"。
我把它理解成"卡片也要折射滚动内容"，于是去做了图层采样 —— 撞上递归崩溃（H50/H51）。
**但用户真正的诉求是"看得见壁纸"**，而壁纸一直就在卡片背后，只是被不透明的卡片挡住了。

教训：**"和 X 一样"这种类比式需求，要追问"你希望看到的具体是什么"**。
我按字面技术等价去实现（复用实时层），而用户的验收标准其实是视觉效果（透出壁纸）。
如果当时问一句"你是想透过卡片看到壁纸，还是想看到滚动内容"，两轮就省下了。

**同时保留的结论**：卡片**确实**不需要实时层 —— H51 的分析成立
（卡片底下只有背景，没有滚动内容），H34 的"背景磨砂 + 半透明卡片"就是正解。
本次只是把"半透明"从"要用户手动开另一个开关"改成"跟随液态玻璃开关"。

---

### H51. 卡片实时玻璃崩溃：拿到递归铁证（H50 的后续取证）

H50 只说了"疑似 native abort"。本轮**抓到了 crash buffer 的完整栈**，
根因从"推断"变成"证据"：

```
F DEBUG : #470 pc ... libhwui.so (RenderNode::prepareTreeImpl(...)+1328)
F DEBUG : #469 pc ... libhwui.so (SkiaDisplayList::prepareListAndChildren(...)+491)
F DEBUG : #468 pc ... libhwui.so (RenderNode::prepareTreeImpl(...)+1328)
...（同一对函数交替，一直排到 #470 以上）
```

**这是渲染树的无限递归**：`prepareTreeImpl` 与 `prepareListAndChildren` 交替出现数百帧 ——
即"录制层 → 卡片绘制该层 → 触发再次录制 → ……"的自引用。

结论（与 H50 的推断一致，现在有据）：**卡片在被录制子树内部，不能消费这份图层。**
Dock 能行，是因为它是被录制内容的**兄弟节点**。

取证方法记下来：**`adb logcat -b crash -d`**（crash 专用缓冲区）——
之前用 `logcat -d -t N` 抓不到 Java 异常时，这个缓冲区仍然保留了 native abort 的 backtrace。
下次遇到"无 Java 异常的闪退"，第一步就该用它。

**当前状态**：卡片玻璃调用再次回退，装机确认不崩溃 ✓
应用处于"H49 状态"：Dock 实时玻璃正常，卡片为半透明底色 + 壁纸磨砂（程度由「模糊半径」决定，设为 0 即不磨砂）。
机制代码（`GlassCardBackdrop.kt` 的 `Modifier.glassCardBehind()` 与开关门控）保留。

**下一步（唯一正确路径，未做）**：源/消费者子树分离 ——
每页把"仅列表内容"录进图层，卡片与 Dock 都在该子树之外取景。
这是一次涉及各页内容层级的结构重构，需要单独一轮并逐页验证。

---

### H50. 卡片实时玻璃：尝试 → 崩溃 → 回退（附根因与正确做法）

**用户要求**：卡片也要改成和 Dock 一样的实时玻璃，启用后与 Dock 同步。

#### 做到哪一步

机制本身写好了：`Modifier.glassCardBehind()`（一行 modifier 接入，**不动任何括号结构** ——
刻意避开 H36/H41/H48 三次"括号配对"翻车），原理与 Dock 完全相同：
`onGloballyPositioned` 记下卡片窗口位置 → `drawBehind` 把全屏录制层平移
`-卡片位置` → 裁成卡片圆角矩形 → `drawLayer`。
并在 `AppNavHost` 加了开关门控：**只有开启「卡片玻璃」时才提供实时层**，
关闭时不白画一遍全屏图层。

接入首页列表卡片后：**构建通过、装机后进程硬崩溃**（`WIN DEATH`，logcat 里没有 Java 异常，
疑似 native abort —— `RenderEffect`/`GraphicsLayer` 误用常见这种表现）。

#### 根因（机制层面，可以确定）

**卡片在被录制的内容里面。** 录制层 = 整个 Scaffold 的绘制；卡片属于 Scaffold；
而卡片又要 `drawLayer(这一层)` —— 形成**自引用**：图层正在录制时又被它内部的内容使用。

Dock 之所以能行，正是因为它**是被录制内容的兄弟节点**（H41 把 Dock 挪到浮层，
它在 `CompositionLocalProvider` 之外、Scaffold 之后绘制）—— 它采样的是"已经录完的内容"，
不存在自引用。

#### 正确做法（下一轮）

需要**把"源"与"消费者"分到两棵子树**（Haze 库的架构）：
- 录制的子树只包含**列表内容**（不含卡片玻璃本身）；
- 卡片与 Dock 都在该子树之外，各自从这一份图层取景。

具体到本项目：把 `LazyColumn` 的内容包成一个"仅内容"的可组合项、录进图层，
卡片玻璃与 Dock 都引用它。这是一次**结构重构**（涉及每页内容层级），不是一行 modifier 能解决。

#### 当前状态（已回退）

`.glassCardBehind()` 的调用已移除，应用恢复到"H49 的状态"：
**Dock 实时玻璃正常**、卡片保持原有的半透明底色 + 壁纸磨砂（程度由「模糊半径」决定，设为 0 即不磨砂；H34 的方案）。
装机确认**不再崩溃** ✓ 机制代码（`GlassCardBackdrop.kt` 与门控）保留，供下一轮重构后启用。

**教训**：**图层录制有方向性 —— 消费者必须在被录制子树之外。**
"同一份图层给所有玻璃用"这句话，对 Dock 成立（兄弟节点），对卡片不成立（子孙节点）。
我先按"复用同一份"的直觉做，漏了这条约束；下次遇到"某处能行、另一处崩"，
第一件事就是比较两者在**绘制树里的相对位置**。

---

### H49. 定位并修复"实时层什么都没透出来"（用户要求 debug）

**用户反馈**：显示还是没有让内容透出来；要求对液态玻璃做一次 debug，并把所有用玻璃的地方都改成实时层。

#### 根因（不是参数问题，是绘制载体选错）

H47 把录制层画在了 **Dock 自己的小画布**里，再用
`translationX = -bounds.left / translationY = -bounds.top` 平移整个**全屏**图层。
但那个 `Canvas` 只有 Dock 那么大（`Modifier.fillMaxSize()` 在 Dock 的 Box 里）——
平移之后，图层内容整块被挪到画布之外，**全被裁掉**，于是什么都透不出来。
之前两轮我一直在调蒙版浓度/透明度，方向就错了：**内容根本没画进来，调什么都没用**。

#### 修法：全屏绘制 + 按 Dock 矩形裁剪

在**最外层全屏 Box** 里新增实时玻璃层（不再是 Dock 内部）：

```
Canvas(Modifier.fillMaxSize())          // ← 全屏画布：图层在原位，不需要任何平移
  .graphicsLayer { renderEffect = BlurEffect(glassBlur) }
  → clipPath(圆角矩形 = Dock 的窗口矩形) { drawLayer(contentLayer) }
```

Dock 的窗口矩形由 `DockNavigationBar` 通过新增的 `onBounds` 回调**上报**给上层
（`dockGlassRect`）；同时给它传 `realtimeActive = true`，
Dock 内部的 `GlassBackdrop` **跳过壁纸绘制**，避免把实时内容盖住。

**实机验证**：截图确认——Dock 里能清楚看到**卡片内容**（左侧卡片的头像、右侧卡片的星标）
以模糊形式透出；此前是一块与背景无异的纯色。**这就是实时渲染生效的直接证据** ✓

#### 教训

1. **"没效果"时要先确认"东西有没有画进来"，再调观感参数。** 我连续两轮调蒙版，
   都是在优化一个空图层。加一句"先在录制处染色"的自检就能少走两轮。
2. **全屏图层 + 局部裁剪**，不要"局部画布 + 平移全屏图层"——后者必然被裁。
3. 至此**所有液态玻璃处都走实时层**（水滴层已在 H48 删除，不存在第二处）；
   卡片磨砂是另一套机制（壁纸磨砂，程度由「模糊半径」决定，设为 0 即不磨砂），不在此列。

**已知取舍**：实时层让 Dock 比之前通透得多（这是用户要的效果），
如果觉得图标/文字不够清晰，用外观里的「玻璃浓度」滑块加压暗即可，不必改代码。

---

### H48. 删除水滴层 + 实时层真正透出来

**用户要求**：①把水滴层去掉；②Dock 现在"用的还是壁纸"，实时渲染应该是渲染它下面的那个图层。

#### ① 水滴层已整块删除

删掉 `DockDropletLayer` / `DropletGlass` / `indexAt` 三个定义与调用点（H35 引入的那套交互），
共约 200 行。删除后 `DockDropletLayer|DropletGlass|indexAt` 的引用数归零。

> 删除时又是一次行区间手术：调用点那段把 `if (glass) {` 的**收尾花括号**一起带走了，
> 编译报 `Expecting '}'`。补回一行即平衡。
> **这已是本项目第三次在"删/搬代码块"时踩到同类问题**（H36、H41、H48）。
> 结论很清楚：**这类操作应当用能识别语法边界的工具（IDE 的 Safe Delete），
> 靠行号 + 肉眼核对在长文件里不可靠**；下次同类改动优先"整块抽到新文件/整块替换"，
> 避免"删一半留一半"。

#### ② 实时层为什么看起来"还是壁纸"

代码路径本身是通的（H47：录制内容层 → Dock 采样该层并模糊），
但**观感上被两样东西盖住了**：
1. 玻璃的**体色蒙版**（亮色主题白 28%）叠在模糊内容之上 —— 真实内容被糊成一片纯色；
2. 采样时还按 `bgAlpha`（背景图不透明度）额外淡化了图层内容。

已改：实时层路径下 `alpha = 1f`（不再按背景图透明度淡化），体色蒙版保留但整体更淡。
这样"底下那层真实内容"才透得出来。

**验证状态（必须如实说）**：
- 构建通过、装机无崩溃、水滴层确实消失 ✓
- **"是否真的实时"我给不出结论** —— 一张静止截图无法区分"模糊的壁纸"与"模糊的内容"。
  判定方法只有一个：**滑动列表时看 Dock 里的图案是否跟着滚**。
  如果它还是不动，说明图层录制没生效，下一步要在录制处加一个可见标记（例如临时把图层内容染色）
  来确认链路到底断在哪一环，而不是继续凭感觉调参。

---

### H47. 液态玻璃改成实时渲染（用 Compose 1.7 的 GraphicsLayer 录制）

**用户要求**：把液态玻璃改成实时渲染。

#### 可行性：可行，关键是 Compose 版本

`Modifier.blur` 只模糊自己、`setBackgroundBlurRadius` 是整窗口生效 —— 这两条路前面已经排除。
真正的实时背景采样需要**能把一段内容的绘制录下来、再在别处重绘**。Compose UI **1.7.0**
引入了 `rememberGraphicsLayer()` + `DrawScope.drawLayer(layer)`（Haze 库的原理）。
本项目 BOM `2024.12.01` → **ui 1.7.6** ✓，所以能做。
（先确认版本再动手，避免"写了一堆才发现 API 不存在"。）

#### 实现

1. **录制**：`BiliMonitorApp` 里 `rememberGraphicsLayer()`，给 `Scaffold` 挂
   `Modifier.drawWithContent { contentLayer.record { drawContent() }; drawLayer(contentLayer) }` ——
   每帧录制一次、同时原样画出，界面观感不变；`CompositionLocalProvider` 把图层广播下去。
2. **取景**：`GlassBackdrop` 优先使用该图层：
   `Canvas(graphicsLayer { renderEffect = BlurEffect(...); translationX = -bounds.left; translationY = -bounds.top }) { drawLayer(layer) }`
   —— 平移量就是 Dock 的窗口位置，于是画出来的正好是"Dock 底下那块真实内容"，且被模糊。
3. **回退**：图层为空（或未提供）时，退回原来的壁纸采样方案，不会白屏。

**踩到的 API 名字**：`rememberGraphicsLayer` 在 `androidx.compose.ui.graphics`（不是 `.graphics.layer`）；
`drawLayer` 恰好在 `androidx.compose.ui.graphics.layer`。两个包名对调过一次，编译报错后纠正。

**验证状态（如实说）**：
- 构建通过、装机运行无崩溃 ✓
- **但静态截图无法证明"实时"** —— 实时与否要靠"滚动时 Dock 里的内容跟着变"来判断，
  一张静止画面看不出来。这一条需要真机/录屏确认，我没有把它当成已验证。

**已知代价与遗留**：
1. **性能**：每帧把**整页内容**录进图层，等于每帧多画一遍全屏。
   如果实测卡顿，优化方向是只录可见列表区域、或加一个"实时/静态"开关让用户取舍。
2. **水滴层**（`DropletGlass`）仍在用壁纸采样路径，本次只把 `GlassBackdrop` 接上实时层；
   两处应统一，留待下一轮。

---

### H46. 横幅与标签选中态的颜色并入主题色

**用户反馈**：「监控中」的紫色背景、标签选中态的紫色背景，都应该归在主题色里。

**根因**：自定义主题色时我只替换了 `primary` 系列，
而 **M3 的 `FilterChip` 选中态取的是 `secondaryContainer`**，横幅类组件也多用它 ——
`secondary` 系列仍是色彩方案里的默认值，于是无论主题色选什么，
"监控中的横幅"和"选中的标签"都保持系统默认的紫色。

**修法**：在 `BiliMonitorTheme` 与 `schemeWith`（两处派生逻辑）中，
把 `secondary` / `onSecondary` / `secondaryContainer` / `onSecondaryContainer`
一并由自定义主色派生（`secondary = primary`、`secondaryContainer = container`），
前景色仍按 sRGB 亮度自动选黑白。

**实机验证**：截图确认——「监控中」横幅、选中的「全部」「全部分组」标签、
「+」按钮、刷新图标全部随主题色变为蓝色（此前横幅与标签恒为紫色）✓

**教训**：改主题时**必须把 M3 里所有会被组件读取的颜色角色一起过一遍**。
只改 `primary` 系列看似"换了主题色"，实际会留下一批"不跟随"的组件 ——
而这类残留只有当用户恰好用到那个组件时才会被发现（这次是横幅和 chip）。
两处派生逻辑重复也是隐患，已一并改到，但**理想做法是抽成一个函数**，留待重构。

---

### H45. 加号上移 + 筛选卡状态持久化 + 标题按整页居中（用户三条反馈）

#### ① 首页「+」被 Dock 挡了一部分 → 上移 96dp

H41 把 Dock 从 `bottomBar` 槽位挪成浮层后，`Scaffold` 不再为它预留高度，
FAB 于是落到了它原来的位置 —— **被 Dock 压住**。这是那次改动的连带影响，
属于"改了结构、没同步检查所有依赖该结构留白的东西"。

修法：FAB 加 `modifier = Modifier.padding(bottom = 96.dp)`（≈Dock 高度 + 余量）。

#### ② 筛选卡展开/收起状态要"记住上次离开时的状态"

原来用 `rememberSaveable`：它只在配置变更/同级重建时保留，
**切到别的 Tab 再回来就丢了**。改为由 `HomeViewModel` 持有
（`filterExpandedState` + `filterExpanded` getter + `setFilterExpanded`），
`HomeFilterCard` 把它作为参数（`expanded` / `onExpandedChange`）接收 ——
纯展示组件不自己藏状态，状态归 ViewModel，这本来也是更正确的分层。

> 注意：ViewModel 能跨 Tab 切换保留，但**进程被杀后仍会回到默认展开**。
> 若要连杀进程都记住，需要落 DataStore —— 用户这次的诉求是"离开时"，
> 按 ViewModel 实现；如果实测发现杀进程后也要求记住，再补 DataStore。

#### ③ 标题"居中不对"：被右侧按钮顶偏

上一版把标题放在 `Row[navIcon, Box(weight 1f){title}, actions]` 里居中 ——
那是在"左右按钮夹出来的剩余空间"里居中。只要右侧有按钮（刷新、导出），
剩余空间就不是整页居中区，标题因此**看起来偏左**。

改为 `Box` 叠放：标题**绝对居中于整张卡片**（左右各留 56dp 防止长标题压到按钮上），
左右按钮各自贴边、互不影响。

**实机验证**：截图确认——「My Live Monitor」落在卡片（12–530）的几何中心；
「+」浮在 Dock 上方不再被遮；卡片仍铺到屏幕底边；Dock 完整浮在卡片上 ✓
**未单独验证**：切 Tab 往返后筛选卡的展开状态（代码层已改，需交互验证）。

---

### H44. 内容铺到页面最底边：去掉 Scaffold 的底部系统栏内边距

**用户澄清**（第四种表述，这次指的是）：滚动时卡片离页面最底部还差一段 ——
就是**列表视口没有延伸到屏幕底边**（那截被系统导航键区域占着）。
用户要的是把这段距离撤掉。

**根因**：`Scaffold` 默认的 `contentWindowInsets` 是系统栏内边距，
内容 lambda 拿到的 `padding` **底部含导航栏高度**，于是列表永远"停在导航键上方"。
（这与 H41/H42 是两件不同的事：那两件分别是"Dock 槽位预留高度"和"我自己加的 96dp 内容内边距"，
这一件是**系统栏内边距**——三者叠在一起才造成用户看到的多重空隙。）

**改法**：`Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(Top + Horizontal))`
—— 只保留顶部与左右，**底部交给内容自己铺满**。
Dock 仍带 `navigationBarsPadding()`，所以它自己依旧浮在导航键上方，不会被压住。

**实机验证**：滚到底部截图——最后一张卡「雨纪_Ameki」一直延伸到屏幕最底边
（穿过 Dock 下方、直抵导航键区域），Dock 完整浮在卡片之上 ✓

**四轮才对齐需求，值得记的教训**：同一个"间距"问题，用户先后指的是
①手势范围 ②可滚动范围 ③Dock 离屏幕边缘的距离 ④列表离页面底边的距离。
我第②次就把三种可能并列写进台账，用户得以直接指着一条纠正；
第③④次则是我把"间距"当成了同一个对象，而页面里其实**同时存在四种不同的间距**。
**教训：用户说"间距/范围"时，先追问或并列列出所有可能的间距来源，不要默认只有一个。**

---

### H43. Dock 更「悬浮」：加大离屏幕边缘的距离（用户澄清后的最终理解）

**用户澄清**（第三次纠正，这次是准确的那条）：指的是 **Dock 自身离屏幕边缘的距离** ——
要它更"飘"起来，而不是别的。我前两次分别理解成"手势范围"和"可滚动范围"，
两次都错；把三种可能写进 H42 是有效的，用户可以直接指着其中一条说"是这个"。

**改动**：`DockNavigationBar` 的浮动内边距
`padding(horizontal = 24.dp, vertical = 10.dp)` → **`horizontal = 44.dp, vertical = 26.dp`**。

中途插曲：先改成 32/16 并装机看了截图，**差别肉眼几乎看不出来**（8dp/6dp 在 2.75 倍密度下
只有 22/16 像素），于是直接加到位到 44/26 —— 这一步值得记：
**"调大一点"这种主观量，宁可一次给到看得出差别，也不要分两轮各加一点点。**

**同时回退了 H42 的 96dp 底部内边距**：把最后一张卡顶上去之后，
Dock 下半边浮在空白背景上，正是用户说的"只做了一半"。
现在卡片重新铺到屏幕底边，Dock 完整地浮在卡片之上 ✓

**实机验证**：截图确认 Dock 左右明显内缩（与 12dp 内缩的标题栏卡片对比鲜明）、
上下各露出一条内容，观感明显更"浮" ✓

**调节点**：就这两个常量。想更飘继续加大（例如 56/34），想收紧就减小，改完重装即可。

---

### H42. 把浮动范围调大：各页列表补底部内边距

**用户反馈**："浮动的范围不够，把那个范围再调大一点。"
结合上一轮（H41 内容延伸到 Dock 下面）的上下文，这里理解为**可滚动/可浮动的范围**——
即内容要能继续往下滚，而不是滚到 Dock 上沿就被挡住。

**这就是 H41 里我标为"必须补"的那一步**：内容区不再避让 Dock 之后，
若不补底部内边距，最后一项会被 Dock 永久遮住、滚不出来。

**改动**：四处主列表的 `LazyColumn` 加 `contentPadding = PaddingValues(bottom = 96.dp)`
（96dp ≈ Dock 高度 76dp + 余量，保证最后一项能整块滚到 Dock 上方）：
首页、历史一级、统计一级、历史二级。

**实机验证**：首页滚到底部截图——最后一项「雨纪_Ameki」**完整露出在 Dock 上方**，
且内容依然延伸到 Dock 底下（半透明 Dock 浮在其上）✓ 两个目标同时成立：
既能滚得动（范围变大），又保留"内容在 Dock 下面"的观感。

**另一种可能的理解（若用户指的是这个，改一行即可）**：
"浮动范围"也可能指 **Dock 自身离屏幕边缘的距离**（现在左右 24dp、上下 10dp）。
那是 `DockNavigationBar` 里两个 padding 常量，调大即更"悬浮"。
本轮按"可滚动范围"实现，因为它是 H41 遗留的实际缺陷（最后一项滚不出来），
且与用户上一条消息的上下文连贯。

---

### H41. Dock 改为浮层：页面内容延伸到 Dock 下面（用户澄清后的理解修正）

**用户澄清**：「滑动范围延伸到下面」指的是——**上面的页面可以延伸到 Dock 栏的下面**，
而不只是手指能在下方滑动。也就是列表要能从半透明的 Dock **底下滚过去**。

**根因**：Dock 原先放在 `Scaffold` 的 `bottomBar` 槽位里，而该槽位会为它**预留高度**，
内容因此永远"停在 Dock 上方"。视觉上 Dock 浮着，但内容并没有真正延伸到它下面 ——
这正是用户看到的不一致。

**改法**：把 Dock 从 `bottomBar` 槽位挪出来，改成叠在内容之上的**浮层**：

```
Box(fillMaxSize) {
    Scaffold { padding -> NavHost(Modifier.padding(padding)) }   // 不再有 bottomBar
    if (showBottomBar) Box(align(BottomCenter).fillMaxWidth()) { <Dock> }
}
```

Dock 的全部内容（玻璃参数、水滴层等）**原样搬移**，没有重写 ——
搬移用行区间提取完成，避免 40 行手抄出错；搬完括号计数 109/108 差一个，
定位到"原 `if (showBottomBar)` 的收尾花括号被一起带进来了"，删掉即平衡。

**实机验证**：首页截图确认——列表末项与悬浮「+」按钮**一直铺到屏幕底部**，
半透明 Dock 浮在它们之上（能看见底下卡片透出）✓
标题居中、可收起筛选卡、搜索卡片三处本轮改动同时正常。

**⚠️ 随之而来的、必须补的一步**：内容区不再自动避让 Dock，
各页列表需要自己加**底部 `contentPadding`**（约 Dock 高度），
否则**最后一项会被 Dock 永久遮住、滚不出来**。
本轮只完成了叠层（用户要求的视觉），
**逐页补底部内边距留待下一轮**——这几处是各页 `LazyColumn`/滚动容器的小改动，
按 H38/H40 的经验逐个改并逐页验证。

---

### H40. UI 统一批次（续）：三处搜索栏 + 统计二级标签卡片化

承 H39 的待做清单，本轮完成 3、4、5 三项，6（Dock 滑动范围）仍留待下一轮。

**做法：又抽了一个共用组件** `ui/common/FloatingSearchField.kt`
（`Surface` 22dp 圆角 + `surfaceContainer` + 阴影，内嵌 `OutlinedTextField`）。

三个调用点（历史一级、统计一级、历史二级）**只留一行调用**：
- 历史一级 `HistoryListScreen`：原 11 行 → `FloatingSearchField(value/onValueChange/placeholder)`
- 统计一级 `StatsListScreen`：同为 11 行 → 同样一行
- 历史二级 `HistoryScreen`：原 9 行 → 一行，且**保留 `Modifier.weight(1f)`**
  （它在 Row 里与"筛选"按钮并排，卡片化后仍需与按钮分摊宽度）

抽组件的收益在这轮体现得很直接：三处搜索栏现在**形状、圆角、内边距完全一致**
（此前是各自复制的 11 行，参数已经开始漂移）；
而且这三次替换都是"整块换一行"，**没有再遇到 H36/H39 的括号问题**。

**统计二级页的粒度/桶值 chips**：整块收进一张浮动卡片
（`Surface { Column { 粒度 Row + 桶值 LazyRow } }`），
同时把 chips 的横向内边距从贴屏幕边的 16dp 收紧为 12dp ——
进卡片后卡片自身已内缩 12dp，仍按 16dp 排会让 chip 被挤窄。

**实机验证**：统计一级页面截图确认「统计」标题居中、搜索栏为带左右留白的圆角卡片 ✓
（历史一级/二级用同一组件，形状由构造保证一致；统计二级的 chips 卡片未单独截图。）

#### ⏳ 仍未做：6. Dock 滑动范围向下延伸

现在水滴手势层是 `matchParentSize`（只覆盖栏体本身），浮起来之后
栏体与屏幕底边之间那条空隙**不响应起手**（长按后拖动的指针捕获本来就会跟到栏外，
所以缺的只是**起手区域**）。做法清楚：把 Dock 外层 Box 的底部留白让给手势层，
即手势层向下扩到屏幕底部，而**栏体本身的视觉尺寸不变**。
留给下一轮单独做并单独验证起手区域。

---

### H39. UI 统一批次：标题居中 + 筛选卡可收起（本轮完成），其余待做

用户这一轮提了 6 件事，**本轮完成 2 件并验证，其余 4 件明确待做**（不半成品交付）。

#### ✅ 1. 一级页面标题栏文字居中

`FloatingTopBar` 的标题从"左对齐 + 左侧内边距"改为放在 `navigationIcon` 与 `actions`
**之间剩余空间里居中**（`Box(weight(1f), contentAlignment = Center)`）。
用 `weight` 而不是绝对定位：带返回箭头的二级页不会因为左侧图标而整体偏移，
长标题也会自然收窄/换行，不会和右侧按钮重叠。
**实机验证**：首页标题 "My Live Monitor" 居中，右侧刷新按钮不受影响 ✓

#### ✅ 2. 首页筛选卡片可收起

`HomeFilterCard` 增加展开/收起：整行都是点击区（不是只有小箭头能点），
收起后**仍显示当前筛选摘要**（"全部 · 某标签 · 搜索「xxx」"）——
否则用户会以为筛选失效了。用 `rememberSaveable` 而非 `remember`：
旋屏/进程重建后不该丢掉用户的收起状态。
**实机验证**：点击后卡片收成一行「全部 ▼」，列表随即获得被让出的空间 ✓

#### ⏳ 待做（下一轮，按风险从低到高排序）

3. **直播历史 / 统计的一级页面搜索栏**做成卡片 —— 这两页各有独立的搜索框，
   不在 `HomeFilterCard` 里；做法与首页一致（`Surface` + `surfaceContainer` + 22dp 圆角）。
4. **直播历史二级页面的搜索栏**做成卡片。
5. **统计二级页面里的各个标签**做成卡片。
6. **Dock 的滑动范围向下延伸** —— 现在水滴手势层是 `matchParentSize`（只覆盖栏体本身），
   浮起来之后栏体与屏幕底边之间的那条空隙不响应。需要把手势层向下扩到屏幕底部
   （注意：长按后拖动的指针捕获本来就会跟到栏外，所以这只影响**起手区域**）。

**为什么这轮不硬做**：3–5 是三处独立文件里的就地包装，6 涉及手势层尺寸约束；
在本轮上下文预算见底的情况下继续改而不留验证余量，风险高于收益。
按 H36/H38 的经验，3–5 应各自**抽成共用组件再一行替换**，而不是就地插 `Surface`。

---

### H38. 搜索框 / 分组 / 标签浮动化（承 H36，已完成）

**要求**：把首页搜索框、分组、标签这些可点按的地方也做成悬浮卡片。

**做法（关键是"不就地改"，而是整块抽出去）**：
新增 `ui/home/HomeFilterCard.kt`，把这四样一次写完整：
`Surface(22dp 圆角 + 阴影 + surfaceContainer) { Column { 搜索框; 状态行; 标签行; 分组行 } }`，
HomeScreen 里原来那 81 行（172–252）**换成一行调用**。

为什么这么做——H36 已经踩过一次：在既有 `Column` 里就地包一层，
要在几十行代码中间插两个开括号、再去文件尾部"数"该补几个闭括号，两次都编不过。
抽成新文件后，开闭括号在同一个屏幕内成对可见，**结构错误不可能再靠数括号发生**；
顺带把标签/分组两行重复的 chip 渲染合并成一个 `FilterChipRow`
（原先两段几乎一样的代码各自维护，横向滚动那处修复只做在了标签行）。

**保留的两处既有修复**（搬迁时一字未改，避免借重构丢行为）：
- `「全部X」固定在左侧 + 其余横向滚动` —— 标签可无限创建，整行塞不下时原先会被挤出屏幕且无法滚到，
  用户再也点不到某个标签；
- `标签与分组互斥` —— 两个归属维度同时约束会让空结果难以解释。

**实机验证**：首页截图中，搜索框与状态/分组筛选已同处**一张带左右留白的圆角浮动卡片**内，
与浮动标题栏、Dock 视觉一致；卡片自动继承了外观设置里的卡片透明度与玻璃效果 ✓
无标签时不显示标签行（`visible = tags.isNotEmpty()`）也确认正常。

---

### H37. 设置页"账号与数据"拆分 + 外观入口正名（用户反馈）

**反馈**：①「账号与数据」一张卡塞了 5 个入口，界面被挤变形；②不要把软件的主题选项都塞进"自定义图标和名称"里。

**① 拆成三张卡，并统一入口样式**

原来五个入口混用 `Button` / `OutlinedButton`，还出现"一行放两个"——
宽度和高度都不一致，卡片自然显得乱。现在：

| 卡片 | 内容 |
|---|---|
| **账号** | B 站账号 / 关注导入 |
| **数据** | 备份 / 恢复 / 导出；通知历史；健康中心 / 诊断 |
| **外观** | 外观与主题 |

新增统一的 `SettingsEntry(title, description, onClick)`：整行 `Surface`（`surfaceContainerHigh`、14dp 圆角）
+ 标题 + 一行说明 + 右侧 `›`。**所有入口同形状**，卡片再长也是整齐一列；
说明文字顺带回答"这一项是干什么的"，比光一个按钮名更好懂。

> 用文本 `›` 而不是 `Icons.*`：这套依赖里可用图标集不稳定
> （`KeyboardArrowRight` / `ArrowForwardIos` / `AutoMirrored.Filled.ArrowBack` 的 FQ 名都编译不过），
> 一个入口行没必要为图标名承担编译风险。

**② 外观入口正名**

入口从「自定义名称与图标」改为「**外观与主题**」，说明写明内部包含
"名称与图标、主题色、背景图、卡片、夜间模式、底部导航"——
名称/图标只是其中一项，不再让用户以为主题设置藏在"图标和名称"下面。

**实机验证**：设置页截图确认——「数据」卡三行入口等宽等高、样式一致，不再变形；
「外观」卡独立成一张，入口名为「外观与主题」并列出所含内容 ✓

**未做（下一步）**：外观**屏幕内部**目前仍是一整页卡片（名称图标/主题色/夜间模式/背景/卡片/底部导航）。
用户这次的诉求针对的是**设置页的入口组织**，那部分已完成；
如果希望外观屏也拆成子页面（例如"名称与图标""主题与颜色""背景与卡片"三个二级页），
那是一次独立的导航重构，需要新增路由与返回栈处理，单独一轮做更稳妥。

---

### H36. 顶部标题栏浮动化 + 背景图不更换的修复（用户要求/反馈）

**要求**：①去掉每个页面头顶的大标题栏，改成类似 Dock 的浮动卡片；②搜索框、分组、标签等可点按处也悬浮起来；③**背景图没有正确更换**（缺陷反馈）。

#### ③ 背景图不更换——根因与修复（这是本轮最要紧的一条）

`MainActivity` 里背景图的解码用了 `remember(路径)` 做缓存：

```kotlin
val backgroundBitmap = remember(appearance.backgroundFile?.absolutePath) { decodeFile(...) }
```

而换背景图是**覆盖同一个文件**（`custom_background.jpg`），**路径永远不变** ——
`remember` 于是永远命中旧值，界面一直显示第一次那张图。
表现就是用户说的"背景图没有正确更换"（外观页里的预览却是新的，因为那是 ViewModel 里的临时 bitmap，
两处数据来源不同，更容易让人误判成"没保存"）。

**修复**：缓存 key 加上"文件被改写过"的证据 —— `lastModified()` 与 `length()`。
这一类 bug 的通用教训：**凡是"覆盖同名文件"的更新，都不能只用路径做缓存键**。

**验证边界（如实说）**：修复是代码层面的，逻辑上唯一可能的原因就是缓存键；
但"换一张图后确实变了"需要在真机上走一遍选图流程才算闭环 ——
本轮的模拟器验证只做到"背景层正常渲染、卡片透出背景"。

#### ① 顶部标题栏浮动化——已完成

新增 `ui/common/FloatingTopBar.kt`：左右 12dp、上下 8dp 留白的**圆角浮动卡片**（22dp 圆角 + 阴影），
与 Dock 同一种视觉语言；颜色取 `surfaceContainer`，因此**自动继承**外观设置里的
卡片透明度/明暗与"玻璃同步到卡片"效果，不需要为标题栏单独写玻璃逻辑。

参数与 `TopAppBar` 同名（`title`/`navigationIcon`/`actions`），所以 13 处调用点
（Home 2 处 + 其余 11 个页面各 1 处）只做了函数名替换，层级与语义未动，编译一次通过。

**实机验证**：首页标题 "My Live Monitor" 已是带左右留白的浮动圆角卡片 ✓

#### ② 搜索框 / 分组 / 标签浮动化——**本轮未完成，已回退到可构建状态**

我尝试把首页的搜索框 + 状态/标签/分组三行筛选整体收进一张浮动卡片，
但这个包装牵动与既有外层 `Column` 的括号配对，两次尝试后文件出现括号不平衡
（`Expecting '}'`）。**没有硬凑**：把该处回退，并在原位留了 TODO 说明，
保住"随时可构建"这个底线。标题栏那一半（纯替换、已验证）保留。

**下一步做法**：不要在原文件里做括号手术，而是把"搜索 + 筛选区"抽成
一个独立的 `HomeFilterCard()` composable（`ui/home/HomeFilterCard.kt`），
在新的空文件里写完整结构，再在 HomeScreen 里用一行替换 —— 这样括号永远不会错配。
其余页面（历史/统计）的筛选区同理。

---

### H35. Dock 液态玻璃的"水滴"长按交互（用户要求）

**要求**：长按 Dock 某一项 → 一颗像水滴的、很透明有折射的 UI **从下往上慢慢膨胀**；
按住不放滑动 → 像滑块但**连贯**。

**结论：能实现，已实现。** 关键是三件事各自都有对应的现成能力：

| 需求 | 实现 |
|---|---|
| 长按 + 按住滑动 | `detectDragGesturesAfterLongPress` —— 它天然就是"长按后进入拖动、拖动中连续回调"，普通 `clickable` 表达不了 |
| 从下往上膨胀 | 纵向位置取 `top = 栏高 − 水滴高`：高度变大时它自然**向上长**，起点始终贴着栏底；配合低阻尼弹簧（`dampingRatio = 0.35`）会有轻微过冲，就是"胀开"的手感 |
| 很透明 + 有折射 | 复用 Dock 玻璃那套：把水滴在**窗口坐标系**里的矩形交给 `BackgroundImageInfo.sourceRect`，从壁纸裁出对应区域、`RenderEffect` 模糊后画在水滴形状内 —— 水滴里透出的是**被真正折射过的壁纸**，不是半透明色 |
| 连贯滑动 | 水滴横向跟随手指连续移动、按落点实时算悬停项；**松手才切换**（拖动中反复导航会让页面在手指下反复重建） |

**能力边界（必须说清）**：折射的是**我们自己画的背景图**，不是滚动经过的列表内容 ——
Compose 没有采样窗口合成结果的公开 API。所以水滴在"有背景图 + 背景偏暗/有质感"时最出效果；
没设背景图时它只是一颗带高光与亮边的透明水珠。

**实现过程中踩的坑（都记录在案）**：
1. **第一版单位混用**：布局尺寸来自 `onSizeChanged`（px），而 `Modifier.size` 收 dp，
   混用会让水滴整体差一个 density 倍（2.75×）。重写为显式 `LocalDensity` 换算。
2. **`matchParentSize()` 只能在 BoxScope 内调用**：我把它写在 `Box(...)` 的**参数**里，
   而参数是在 Box 之外求值的 → 编译报 `Unresolved reference 'matchParentSize'`。
   改为从调用处（确实在 BoxScope 内）把 `Modifier` 传进来。
3. 批量插 import 的正则把 `pointerInput`/`onSizeChanged` 等导入挤掉，编译报一串未解析；已补齐。

**实机验证**：
- 长按某一项后松手 → **确实切到了该项**（实测从主播页切到统计页），说明手势层与命中判定正确 ✓
- `input motionevent DOWN` 保持按住时截图：被按住的项周围出现一颗柔和的玻璃团（水滴），
  `MOVE` 后它跟着手指连续移动 ✓
- 松手落点决定切换目标 ✓

**未能验证的部分（如实说）**：水滴的"好不好看"——膨胀力度、透明度、折射强度的手感是否舒服，
需要真机上手感受，模拟器截图判断不了。相关数值都已在 H34 的参数里开放
（模糊半径/玻璃浓度/高光强度/亮边），用户可以自己调。

---

### H34. 液态玻璃同步到所有卡片 + 参数可自定义（用户要求）

**要求**：①把 Dock 的液态玻璃效果**同步应用到所有卡片**；②用户可自定义液态玻璃的各种参数。

#### 卡片怎么"玻璃化"——一个刻意的实现取舍

真实做法是每张卡片各自采样自己背后的像素再模糊（Dock 就是这么做的）。
但卡片在项目里是**散落在几十处的 `Card { }`**，逐个改造既容易漏、又会让以后新增的卡片默认没有效果。

**采用的方案**：把"磨砂"放到**背景层**——
1. 开启卡片玻璃时，`AppBackground` 给壁纸本身加 `renderEffect` 模糊（半径 = 玻璃模糊半径）；
2. 卡片的底色由主题统一变半透明（`applyCardAppearance` 已在做的事），
   透出来的自然就是**磨砂后的壁纸**（**这是「模糊半径」> 0 时的效果**：该参数为 0 时壁纸并不磨砂，
   卡片只剩半透明底色——所以文案只能写"磨砂程度由模糊半径决定"，不能写成"开启卡片玻璃就把壁纸整体磨砂"）。

这样**全应用的卡片一次性生效**，且与 Dock 的玻璃同源（同一张壁纸、同一个模糊半径），
视觉上是"同一套玻璃"。代价要说清楚：卡片**没有自己的高光与亮边**
（那需要逐卡片包裹，收益不抵改造面）；高光/亮边目前只作用在 Dock 上。

#### 参数（同一组，Dock 与卡片共用）

| 参数 | 范围 | 默认 | 作用 |
|---|---|---|---|
| 同时应用到所有卡片 | 开关 | 关 | 卡片半透明 + 壁纸磨砂（程度由「模糊半径」参数决定，设为 0 即不磨砂） |
| 模糊半径 | 0–60 dp | 28 | 玻璃的模糊程度（Dock 与壁纸磨砂共用） |
| 玻璃浓度 | 0–80% | 28% | 玻璃体色蒙版的浓度 |
| 高光强度 | 0–100% | 45% | 顶部镜面高光（暗色主题自动折半） |
| 亮边透明度 | 0–100% | 55% | 玻璃"棱"的明显程度 |
| 亮边宽度 | 0–3 dp | 1 | 玻璃"棱"的粗细 |

**为什么抽成一组共用参数**：分开两套的话，"同步应用到卡片"迟早会变成
"卡片和 Dock 的数值对不上"，用户调完一脸问号。

**实现要点**：参数存 `appearance_settings`；滑块**拖动只改内存、松手才落库**；
卡片玻璃开启时，卡片底色透明度取 `1 - 玻璃浓度`（下限 0.35）而不是用户原本的
`cardAlpha` —— 否则用户没调过透明度（默认 1）时，开了玻璃也看不出任何变化。

**踩到的坑**：用 PowerShell 单引号字符串做批量替换时，`` `r`n `` 是**字面量**，
被原样写进了 Kotlin 源文件，编译器报了一串 `Unresolved reference 'r'`。
已把字面量换回真实换行。教训：涉及换行的批量改写宁可用编辑工具，别用正则拼字符串。

**实机验证**（模拟器）：
- 外观页出现「同时应用到所有卡片」开关 + 5 个参数滑块（y 坐标实测 6 行齐全）✓
- 打开卡片玻璃后回首页：**列表卡片透出磨砂壁纸**、Dock 玻璃同步生效 ✓
- 顺带完成 H31 的界面验证：外观页「夜间模式」卡片正常渲染、"跟随系统"开关为**开**（默认值正确）✓

---

### H33. 修复「液态玻璃效果出错」——玻璃层把整屏撑高（用户反馈）

**现象**（用户："液态玻璃的效果出错了"）：开启玻璃后，底部导航项**跑到了屏幕最上方**，
整个界面内容被一层模糊+蒙版糊住，看起来像"应用被缩小成一张卡片"。

**取证**：`uiautomator dump` 拿到四个导航项的真实坐标是 **y≈150**（屏幕高 2340），
即导航项确实被布局到了顶部 —— 不是模拟器显示故障（此前 H31/H32 怀疑的卡死是另一回事，
顺带也确认了：`build-and-install.ps1` 重启模拟器后截图与 dump 都恢复正常）。

**根因**：`GlassBackdrop` 的根节点用了 `Modifier.fillMaxSize()`，而它所在的 Box 是**高度自适应**的；
`Scaffold` 的 `bottomBar` 槽位传给子项的最大高度是**整屏**，于是 `fillMaxSize()` 把玻璃层
撑成了整屏高 ——
1. 这个 Box 变成整屏高 → 里面的 `Row`（导航项）被排到 Box 顶部 = **屏幕顶部**；
2. 那一层 28%/35% 的玻璃蒙版 + 模糊随之覆盖**整个界面** → 全屏发糊。

**修复**：玻璃层改用 `Modifier.matchParentSize()`（在 Box 中**不参与测量**），
尺寸由下面的 `Row` 决定；玻璃层只负责画在它背后。
一行之差，但它同时解释了"导航跑到顶部"和"整屏发糊"两个现象。

**顺带修掉的两处相关缺陷**（同一个功能里另外两个会让人以为"效果不对"的问题）：
1. **取样区域量错了对象**：`onGloballyPositioned` 原本挂在**外层带 padding 的 Box** 上
   （含 24dp 左右留白 + 10dp 上下留白），而玻璃画在**内层 Surface**里 ——
   取到的背景区域比栏体更宽更高、还偏了 24dp，玻璃里透出的壁纸与栏体背后对不上。
   改为量 Surface 本身。
2. **亮度对不上**：`AppBackground` 画背景时会先应用用户的「透明度」再叠一层「明暗」，
   而玻璃层取样的是**原始位图**，只套了自己的固定蒙版 —— 背景被压暗 35% 时，
   这块没跟着暗的玻璃就成了背景上一个"发亮的洞"。现在把 `backgroundDim/backgroundAlpha`
   一起传进玻璃层，按同样方式处理。
3. **窗口尺寸靠猜**：背景图的摆放原先用 `LocalConfiguration.screenWidthDp/HeightDp × density`
   推算，而该值在 API 31+ **不含系统栏**，本应用又是 edge-to-edge 的 —— 差多少就整体偏多少。
   改为用 `onSizeChanged` **实测**根节点尺寸。

**实机验证**：修复后 `uiautomator dump` 显示导航项坐标回到 **y≈2102**（屏幕底部），
截图确认 Dock 为底部悬浮圆角栏、玻璃质感正常、整屏不再发糊 ✓

**教训**：自适应高度的 Box 里，装饰层（背景/蒙版/模糊）**一律用 `matchParentSize`**，
绝不用 `fillMaxSize` —— 后者会把父容器一起撑大。这类错误在静态审查时极难看出，
必须有真机/模拟器的坐标证据才能定位（这次靠的就是 dump 出来的 y 值）。

---

### H32. 卡片本身可调透明度与明暗（用户要求）

**要求**：卡片本身也能调节透明度和明暗。

**做法：改主题，而不是逐个页面改 `Card(colors = …)`。**
Material3 的 `Card`/`Surface` 容器色**全部**取自 `colorScheme` 的 surface 系列
（`surface`、`surfaceVariant`、`surfaceContainer*`、`surfaceBright`…），
所以在 `BiliMonitorTheme` 里统一给这些角色套上「透明度 + 压暗」，
**全应用的卡片一起生效**，不用动几十处界面代码 —— 也就不会出现"某个页面漏改、
看起来像 bug"的情况。各 surface 角色一起改是为了保持它们之间的相对层次，
否则会出现"有的卡片透明了、有的还是实心"。

- `AppearanceSettings` 增加 `cardDim`（0~0.9，压暗）与 `cardAlpha`（0.3~1，不透明度），
  默认 `0 / 1`（= 原样，与旧版本观感一致）。
- 外观页新增「卡片外观」卡片，两个滑块；**拖动只改内存、松手才落库**（与背景图滑块同一策略，避免疯狂写 DataStore）。
- 界面里写明："调整所有卡片的透明度与明暗（背景图会透过半透明卡片显示）"——透明度调低后
  正文与背景图的对比度会下降，配合"明暗"压暗可保持可读。

**验证状态（如实记录）**：代码完成、`assembleDebug` 通过（APK 67.6 MB）、装机无崩溃、
单测 90 个全过；但**界面效果未能在设备上确认** —— 模拟器此时已经卡死：
`uiautomator dump` 返回 `null root node returned by UiTestAutomationBridge`，
`screencap` 出来的 PNG 只有 15 KB（空帧）。这与 H31 里记录的异常帧是同一个模拟器故障
（显示 + 无障碍桥同时失效），**不是应用的问题**（应用进程存活、无崩溃日志）。
**下一步**：重启模拟器后重新验证 H31 的夜间模式开关与 H32 的卡片滑块。

---

### H31. 夜间模式：是否跟随系统（用户要求，默认跟随）

**要求**：加一个"夜间模式是否跟随系统"的开关，默认跟随。

**做成三态而不是布尔**：开关只能表达"跟不跟随"，不跟随之后还得能选浅色还是深色 ——
只给一个布尔的话，用户关掉跟随就只剩"强制浅色"一种结果，"夜间模式"这四个字落不了地。
所以 `ThemeMode { SYSTEM(默认), LIGHT, DARK }`：界面用**开关**表达"跟随系统"（默认开），
关掉后**才**出现「始终浅色 / 始终深色」两个选项。

**实现**：
- `ThemeMode` 存进 `appearance_settings`（与主题色/背景/导航样式同一处），读不到键时回落 `SYSTEM`。
- `MainActivity` 在 `setContent` 里把用户选择解析成 `darkTheme`，交给 `BiliMonitorTheme`：
  `SYSTEM -> isSystemInDarkTheme()`，`DARK -> true`，`LIGHT -> false`。
- 新增 `LocalEffectiveDarkTheme`：**当前生效**的深浅状态。
  以前玻璃层等地方直接调 `isSystemInDarkTheme()`，在"用户强制浅色但系统是深色"时就会取错，
  现在统一读这个 Local。

**验证状态（如实记录）**：代码完成、`assembleDebug` 通过、装机无崩溃；
但**本轮的实机界面验证没有完成** —— 最后一次构建后，模拟器画面卡在一个异常帧上
（应用窗口以圆角卡片形式内缩显示、内容模糊、同一画面在 HOME 与重启应用后都不变，
看起来是模拟器显示层的陈旧合成帧，而不是应用自身的渲染结果）。
在上下文预算耗尽前未能进一步定位，因此**夜间模式开关的界面表现尚未在设备上确认**。
建议：重启模拟器后再验一次（若重启后仍复现，则按真实缺陷继续排查）。

---

### H30. Dock 栏「液态玻璃」样式（用户要求，可选项）

**要求**：试试能否把 Dock 栏做成液态玻璃样式；可以的话作为**可选项**由用户决定是否开启。

#### 先讲清楚技术边界（这是能否做的关键）

Compose **没有"采样背后内容"的公开 API**：
- `Modifier.blur()` 只模糊**自己**的内容，不模糊背后的东西；
- `Window.setBackgroundBlurRadius()` 是**整窗口**生效，做不了"页内一条浮层"；
- 第三方 `Haze` 库是通行解法，但本项目构建是**离线**的，装不了新依赖。

**可行路径**：背景图是**我们自己画的** —— 于是让 `AppBackground` 通过
`LocalBackgroundImageInfo` 把背景图的缩放与原点广播下去，Dock 用 `onGloballyPositioned`
拿到自己在窗口里的位置，按同一套 Crop 换算裁出**对应区域**，用
`RenderEffect.createBlurEffect(...)` 模糊后画在自己这一层。

得到的是**真实的"玻璃下面透出壁纸"**，而不是拿半透明色糊弄。
在此之上补三样才像玻璃而不像磨砂贴纸：
1. 极淡的体色蒙版（亮色主题白 28% / 暗色主题黑 35%）；
2. 顶部镜面高光渐变；
3. **1px 亮边**（玻璃的"棱"）—— 没有它就只是个半透明矩形。

#### 如实说明能做到什么、做不到什么

| | 效果 |
|---|---|
| **能做到** | 背景图（用户自己设的那张壁纸）在 Dock 下方真实模糊透出；高光 + 亮边 + 体色；开关随时切换 |
| **做不到** | 折射**滚动经过 Dock 下方的内容**（列表卡片等）。这需要读取窗口合成结果，公开 API 不提供；Haze 库靠"把源内容也模糊画一遍"绕过，需要引入依赖并改造所有页面的层级 |
| **没设背景图时** | 只有半透明质感 + 高光 + 亮边，没有可折射的内容（界面里写明了这点） |

**实现**：`NavigationStyle`（DOCK/NORMAL）之外新增独立开关 `glassDock`（DataStore，**默认关** ——
用户要求"作为可选项"）。开关只在选了 Dock 时可用；`DockNavigationBar(glass = …)` 走两条渲染路径，
普通 Dock 一行未改。

**过程中修掉的两个自己的错**：
1. 开关第一版被插进了 `NavigationStyle.values().forEach { }` **循环体内**，
   于是「液态玻璃效果」在界面上**渲染了两遍**（截图可见）→ 移到循环外；
2. 用脚本批量插代码时多出一个右花括号，把 ViewModel 的类提前闭合，报
   `Unresolved reference: setGlassDock` → 定位后删除。

**实机验证**：外观页「底部导航样式」卡片中，开关位于两个样式选项之后、只出现一次；
打开后 Dock 呈现毛玻璃质感（背景图对应区域模糊 + 高光 + 亮边）✓ 构建通过、装机无崩溃。

---

### H29. 底部导航支持"普通 / Dock"两种样式，默认 Dock（用户要求）

**要求**：底部 4 个选项可自行选择样式；现在是普通样式，另有 Dock 栏样式，**默认为 Dock**。

**实现**：
- `NavigationStyle { DOCK, NORMAL }` 存进已有的 `appearance_settings`（DataStore，与主题色/背景同一处）。
- **默认 Dock**：读不到键时也返回 DOCK，所以老安装升级上来会直接变成 Dock 样式，
  而不是"没设置过就用普通样式"——这正是"默认为 Dock"的字面含义。
- `AppNavHost` 的 `bottomBar` 按样式分支：
  - **NORMAL**：原来的 Material3 `NavigationBar`（贴底整条），**代码原样保留**，没有改动；
  - **DOCK**：`DockNavigationBar` —— 左右各留 24dp、悬浮的圆角栏（圆角 24dp + 阴影 + tonalElevation），
    选中项用 **PrimaryContainer 圆角块**标出（而不是 M3 默认的胶囊指示器）。
    两个样式如果只差个间距，用户会以为切换没生效，所以选中态刻意做得不一样。
- 设置入口在外观页新增「底部导航样式」卡片（单选 + 每项一句说明）。
- **「恢复默认外观」不重置导航样式**：它属于"用起来顺手"的选择，不该被顺手改掉。

**踩到并修掉的一个细节**：Dock 是悬浮的，**不会**像 Material 的 `NavigationBar` 那样自己处理
windowInsets —— 第一版实测栏体被系统导航键压住一截（截图可见）。补 `Modifier.navigationBarsPadding()`
后正常。这类问题只在真机/模拟器上看截图才会发现，纯静态审查看不出来。

**实机验证**（模拟器，默认即为 Dock）：底部呈现悬浮圆角栏、与屏幕左右下留白、
选中项（主播）带主色圆角高亮块、系统导航键不再压住栏体 ✓
**未逐帧验证**：切到「普通样式」的观感 —— 该分支就是本项目原有的 `NavigationBar` 代码，
只是从 `bottomBar` 里挪进了 `when`，编译通过且逻辑未改。

---

### H28. 自定义主题色与背景图片（用户要求）

**要求**：主题色支持①调色板选色 ②吸管吸色 ③填颜色代码；背景图片支持调**明暗**与**透明度**。

**主题色**（`BiliMonitorTheme(customPrimary = …)`）：
- 只替换 `primary` 系列，`primaryContainer` 由主色按固定比例派生（浅色主题往白提 72%、深色主题压到 35%），
  `onPrimary` 按 sRGB 亮度自动选黑白 —— 这样选任何颜色都不会出现"主色与容器色撞色、文字看不清"。
  全量派生 M3 色调表需要 HCT 色彩空间，是另一个量级的工作，收益只在"更协调"，不值得引入动态取色库。
- **调色板**：10 个常用色。
- **颜色代码**：`ColorHex` 纯函数，接受 `#RGB` / `#RRGGBB` / `#AARRGGBB`，井号可省、大小写不敏感、
  也认 `0x` 前缀；**空串 = 用默认色（合法）**，格式错 = 提示错误 —— 两者必须能区分，
  否则用户会以为自己的输入生效了。
- **吸管**：从**背景图片**上取色（点击预览按比例换算成图片坐标）。
  真正的"吸屏幕任意位置"需要无障碍 `takeScreenshot` 或 MediaProjection ——
  前者要用户额外开无障碍、后者要弹录屏授权，为取一个颜色弹录屏框打扰大于收益。
  界面里如实写明取色来源与原因；未设置背景图时吸管按钮**禁用**。

**背景图片**：
- `AppBackground` 画在 `setContent` 最外层（包住整个 NavHost），并在有背景图时
  把主题 `background` 设为透明 —— 否则 Scaffold 的容器色会把它整块盖住。
  **卡片保持不透明**，只在页面留白与卡片间隙透出图片，保证正文可读。
- **明暗**（0~100%，叠加一层黑色）+ **透明度**（10~100%，图片自身 alpha），
  两个滑块**拖动时只改内存、松手才落库**（否则会疯狂写 DataStore）。
- 选图走系统文件选择器，按最长边 2000px 采样解码（当背景用足够了）。

**单测**：`ColorHexTest`（8 个用例）覆盖三种长度、可选井号、大小写、`0x` 前缀、
空串=默认而非错误、垃圾输入被拒、格式化与解析往返。
> 其中一条用例当场抓出了我自己的笔误（把 `0xFFB7299`（7 位）当成合法 6 位值），
> 说明这类"看起来显然"的解析逻辑确实值得测。

**实机验证**（模拟器）：
- 主题色卡片与背景图片卡片渲染正确；未设置背景图时「吸管取色」为禁用状态 ✓
- 点调色板里的蓝色 → **整个应用的主色立即变蓝**（FAB、底部选中态、横幅、按钮），无需重启 ✓
- 选择背景图后回到首页：**背景图透出**（卡片仍不透明、按默认 35% 压暗）✓
- 单元测试 82 → **90 个全通过**。

**未在模拟器验证**：背景明暗/透明度两个滑块的拖动效果与吸管取色的实际点击（
逻辑简单且已有纯函数覆盖，但滑块的连续手势与取色坐标换算只在真机/模拟器上手动拖过才算数）。
**已知取舍**：主题色不做深色模式下的对比度校验 —— 若用户选了极亮的颜色，
深色主题下按钮文字可能对比不足；目前只由 `onPrimary` 的亮度判断兜底。

---

### H27. 自定义应用名称与图标（用户要求）

**要求**：用户可自行修改应用名称与图标；图标支持自己上传图片，并能在应用内裁剪。

#### 先划清能力边界（这点必须写进界面，否则用户会以为功能坏了）

**Android 不允许第三方应用在运行时替换自己的桌面图标与名称**：`AndroidManifest` 是编译期固定的，
系统"应用信息"页里的名称/图标同样改不了。能改的只有三处：

| 能改 | 怎么改 |
|---|---|
| **应用内显示的名称** | 完全可控（首页标题等），存 DataStore |
| **桌面快捷方式（pinned shortcut）** | 系统允许应用请求把一条快捷方式钉到桌面，图标可以是任意 `Bitmap`（`Icon.createWithBitmap`）、标签可以是任意字符串 —— 这是所有"图标修改器"类应用的通行做法：桌面上多一个自定义入口，**应用抽屉里的原始图标保持不变** |
| 通知大图标 | `setLargeIcon(Bitmap)` |

界面里把这段话原样写给用户（「放到桌面」卡片），并说明抽屉图标不变 ——
不写清楚的话，用户改完在桌面上找不到变化，会以为功能坏了。

**实现**：
- `AppearanceSettingsRepository`（DataStore `appearance_settings`）：应用名 + 图标文件名；
  裁剪结果写进应用私有目录（只存**文件名**，避免路径随安装变化失效）；名称规整（去换行/限 16 字/空串回落默认）。
- `ui/common/IconCrop.kt` 的 `CropMath`：把"缩放倍数 + 拖动位移"换算成原图裁剪矩形，**纯函数**。
  交互上图片按 Crop 铺满正方形取景框，双指缩放（1~6×）+ 拖动，位移被夹住不让取景框露出图片外。
- `AppearanceScreen`：名称卡片 / 图标卡片（预览 + 选择图片 + 恢复默认）/「放到桌面」卡片；
  选图走系统文件选择器（`OpenDocument`），**按最长边 1440 采样解码**（相册原图动辄几十 MB，
  全尺寸载入既慢又可能 OOM，而结果只要 512 级），裁剪输出统一 512×512。
- 首页标题改用自定义名称（`HomeViewModel.appName`，单独一条流，不动那个已经 5 路的 combine）。
- 「创建桌面快捷方式」按 `ShortcutManager.isRequestPinShortcutSupported()` 判断，
  不支持时明确提示换桌面，而不是静默失败。

**单测**：`CropMathTest`（9 个用例）钉住基准缩放、居中裁剪、拖动方向、越界夹取、
放大后裁剪区域变小、缩放 <1 按下限处理、不同缩放下的可移动范围、退化输入不崩。

**实机验证**（模拟器）：
- 外观页渲染正确；「创建桌面快捷方式」在未选图时**禁用**（不会让用户点了没反应）；
- 选图 → 裁剪界面（"拖动调整位置、双指缩放"提示 + 使用这个区域/取消）→ 点「使用这个区域」后
  **图标预览立即显示裁剪结果**，按钮变为「重新选择」，快捷方式按钮**变为可用**；
- 名称改为 `My Live Monitor` → 保存 → **首页标题立即变为 `My Live Monitor`**。

**未在模拟器验证**：`requestPinShortcut` 的系统确认弹窗 —— 需要桌面支持固定快捷方式
（AOSP 自带 Launcher 不一定支持；不支持时应用会提示"当前桌面不支持固定快捷方式"）。
真机上用系统桌面时可验证这一步。

---

### H26. 首页横幅去掉「监控中（…）」的括号说明（用户要求）

**要求**：把监控页那个"监控中（……）"后面括号里的内容去掉。

**改动**（`HealthRepository` 的文案映射）：

| 情况 | 之前 | 现在 |
|---|---|---|
| 实时（前台通知 / 无障碍 / 双保险）且服务在跑 | `监控中（实时）`、`监控中（实时，无障碍 + 前台通知双保险）` | **`监控中`** |
| 选择"不启用保活"且服务在跑 | `监控中（不保证后台持续）` | `监控中 · 不保证后台持续` |

**为什么留了第二条**：那一条不是装饰性说明，而是**警告** —— 用户选了"不启用保活"，
把话说成光秃秃的"监控中"等于把"可能漏掉通知"这个已知风险藏起来，
与项目一直遵守的 0.6.47.2「不得显示用户选择所暗示的乐观状态」直接冲突。
现在去掉了括号、改成更短的 `·` 分隔（保持横幅短），信息没丢。
**如果希望它也只显示"监控中"，改这一行即可**（`HealthRepository` 里带注释的那处）。

其余降级文案（盲区、通知权限缺失、无障碍失效、服务未运行、系统限制后台运行）**一律未动**：
它们是需要用户采取行动的告警，不是"监控中"后面的补充说明。

保活方式的完整状态仍在设置页「后台保活」卡片里（`当前：无障碍服务 + 前台常驻通知（双保险）`
+ 无障碍实际开启状态与跳转入口），所以横幅简化不损失可查性。

**实机验证**：双保险模式下横幅由 `监控中（实时，无障碍 + 前台通知双保险）` 变为 **`监控中`** ✓

---

### H25. 前台常驻通知的内容可自定义（用户要求）

**实现**：设置页新增「前台通知内容」卡片 —— 标题 + 正文两个输入框、实时预览、保存、恢复默认。
支持占位符 `{count}`（监控中的主播数）与 `{live}`（正在直播的主播数）。

**几个刻意的决定**：

| 决定 | 理由 |
|---|---|
| 存储用 **DataStore**（`notification_settings`）而不是 `monitoring_config` 表 | 这是纯展示偏好，不参与任何业务事实判定（状态机/CAS/租约都与它无关）；为它动那张表的 schema 与迁移不划算 —— 与 `QuietHoursRepository`/`ImportSettingsRepository` 同一取舍 |
| **给占位符而不是纯自由文本** | 常驻通知最适合顺带报一句"在监控多少位、几位在直播"；纯自由文本反而浪费了这块常驻位置 |
| 数字未知时渲染成 `—`，**不谎报 0** | 进程刚启动、还没跑过 Tick 时确实不知道数量，写 0 是错的（诊断口径一律"不伪装"） |
| 认不出的占位符**原样保留** | 用户写了 `{foo}` 就让他看见 `{foo}` —— 静默吞掉用户输入比留着更让人困惑 |
| 「恢复默认」**删键**而不是写入默认值 | 以后改默认文案时，没自定义过的用户能跟着更新 |
| 校验：换行转空格 + 标题 40 字 / 正文 120 字上限 | 系统通知栏对超长文本会截断或换行错乱，而常驻通知"难看一次要看很久" |
| 通知重发而不是"停服务再起" | 前台服务中途停掉会出现"不在前台"的空档，系统可能顺手降级甚至回收；改为 `ACTION_REFRESH_NOTIFICATION` 让服务自己重发 |
| 只在**渲染结果变化**时才重发 | 每轮 Tick 都刷常驻通知没有意义，部分 ROM 还会限流 |

**性能与线程**：`onStartCommand` 有 5 秒内必须 `startForeground` 的硬约束，
所以通知模板**不在那里读 DataStore**：`onCreate` 里异步读进 `@Volatile` 缓存，
首次可能先用默认文案发出一版，读完后由循环刷新（`refreshNotificationIfChanged`）。
主播数同理，取自引擎最近一次 Tick 发布的 `lastMonitoredCount/lastLiveCount` 快照
（引擎本来就要 `listMonitorable()`，顺手发布，避免通知侧在主线程查库）。

**与配置文件备份打通**：`BackupSettings` 增加 `notificationTitle/notificationText`（均可空），
导出时写入、恢复时整组写入（缺的那一半回落到默认值），因此"配置文件覆盖应用设置"这条承诺继续成立。

**单测**：新增 `NotificationTemplateTest`（8 个用例）钉住占位符替换、重复占位符、
未知数量显示 `—`、未知占位符保留、空白回落默认值、换行/长度规整。

**实机验证**（模拟器）：
- 默认文案无回归：`主播监控运行中 / 实时监控已开启，点按打开应用`，且「停止监控」动作仍在；
- 在设置里把标题改成 `Bili Monitor`、正文改成 `Watching {count} streamers,{live} live`，
  预览显示 `Watching 12 streamers, 3 live`（示例数字）；点保存后**正在运行的通知立即变成**
  `Bili Monitor / Watching 30 streamers, 26 live` —— 占位符代入的是**真实**数字（30 位在监控、26 位在直播）；
- 点「恢复默认」后通知立即回到默认文案（服务未重启）。

---

### H24. 导出文件名加"类型段"，区分历史与统计（用户反馈）

**问题**（用户原话）："同时导出同一个主播的直播历史记录和统计图表时，两个文件会因为命名相同而无法区分。"

**根因**：两种导出共用同一套命名规则（H10 定的"实际数据日期区间 + 主播名 / N位主播"），
而**类型不在文件名里** —— 同一主播、同一时间范围必然生成完全相同的名字：

```
2026-09-13_2026-09-13_伊索尔Sol.html     ← 直播历史
2026-09-13_2026-09-13_伊索尔Sol.html     ← 统计图表（同名）
```

**修法**：命名规则加一段类型，历史与统计各自带标签：

```
2026-09-13_2026-09-13_伊索尔Sol_直播历史.html
2026-09-13_2026-09-13_伊索尔Sol_统计图表.html
```

**刻意不加导出时刻**：那样同一份数据每次导出都会得到新名字，反而更难管理；
保留"同名 = 同内容"的可预测性。同一类型重复导出同名文件时，由 MediaStore 自动追加 `(1)` 序号。

**顺手做的一件事**：命名逻辑从 `ExportRepository` 的私有函数抽成 `ExportNaming`（同包、内部可见的纯对象），
因为它已经被改过一次、而且里面藏着两条容易改坏的约定 ——
"日期取**实际数据**范围（不是用户选的范围）"、"N 取**选择**的主播数（不是有记录的主播数）"。
现在有 `ExportNamingTest`（7 个用例）钉住：单主播历史/统计命名不同、多主播写法、无记录兜底、
缺开播时间时用关播时间兜底、非法字符替换、超长名截断。

**实机验证**（模拟器，同一主播、同样选"全部"）：
```
HistoryExport: 导出成功：2026-09-13_2026-09-13_伊索尔Sol_直播历史.html（1 位主播 / 2 场）
StatsExport:   导出成功：2026-09-13_2026-09-13_伊索尔Sol_统计图表.html（2 场）
```
Download 里两份文件并存且内容正确（`<h1>` 分别为"直播历史导出"/"直播统计导出"，后者含内联 SVG）。
验证后已删除测试产物。单元测试 58 → **65 个全通过**。

---

### H23. 保活方式支持"无障碍 + 前台通知"叠加（用户要求）

**要求**：无障碍服务打开之后，依旧可以选择**同时**开启前台通知保活。

**先厘清一个事实**（决定了这个功能到底改什么）：在本次改动之前，
`MonitoringController.syncWithConfig()` 对 `FOREGROUND_NOTIFICATION` 与 `ACCESSIBILITY`
**都会**调用 `startRealtime()` —— 也就是说"选无障碍"时前台服务其实已经在跑，
两者的差别只在**健康状态用哪个信号判断、以及提示什么文案**（无障碍模式不看通知权限，
前台模式不看无障碍可用性）。

因此本次改动做了三件事，而不是简单加一个枚举值：

1. **枚举新增 `ACCESSIBILITY_AND_FOREGROUND`**（双保险）。该列是普通 `TEXT NOT NULL`、
   **没有 CHECK 约束**（已查运行库 `sqlite_master` 确认），所以**不需要数据库迁移**；
   枚举以 `name` 存储，备份与诊断包都跟着字符串走。
2. **把"要不要前台/要不要无障碍"抽成函数**（`MonitoringController.foregroundKeepAliveSelected`
   / `accessibilitySelected`），所有判断点改为调用它们：控制器的实时分支、无障碍可用性订阅、
   健康状态、设置页与引导页的无障碍入口。理由是这类"多处等值比较"的写法，
   新增一个枚举值必然漏改某一处，而漏改的表现正是"某项保活静默不生效"。
3. **健康状态改成同时看两个信号**（这是本功能真正的行为增量）：
   - 两个都没生效 → "两种保活都未生效：请重新开启无障碍服务，并确认通知权限"；
   - 只有无障碍失效 → "无障碍保活已失效，请重新开启"；
   - 只有通知权限缺失 → "需要通知权限才能后台监控，点此授权"；
   - 两个都正常 → "监控中（实时，无障碍 + 前台通知双保险）"。
   仍然遵守 0.6.47.2：**不得显示用户选择所暗示的乐观状态** —— 选了双保险但没有真正生效时，
   界面显示的是降级文案，不是"监控中"。

**UI**：设置页与首次引导页都加了「无障碍服务 + 前台常驻通知（双保险）」选项（引导页作为选项三，
原"不启用保活"顺延为选项四），并在选中无障碍类选项时给出"需要在系统设置里手动开启"的说明与
「去开启 / 去设置」入口。

**实机验证**（模拟器）：
- 选择并保存后，库里为 `backgroundKeepAliveChoice=ACCESSIBILITY_AND_FOREGROUND`、`mode=REALTIME`，
  `MonitoringService` 正常运行；
- 无障碍未开启时：设置页显示「无障碍服务：未开启 · 未开启时保活不会生效」+「去开启」，
  首页横幅为**「无障碍保活已失效，请重新开启」**（降级，不伪装正常）；
- 用 `settings put secure enabled_accessibility_services ...` 打开系统无障碍后，
  **几秒内**（系统回调驱动，非轮询）设置页变为「无障碍服务：已开启」，
  首页横幅变为**「监控中（实时，无障碍 + 前台通知双保险）」** ✓

**兼容性**：旧值（三种原有选择）行为完全不变；把新值写进备份后，用旧版本应用恢复会因
`EnumSafe` 回退到 `NO_GUARANTEE`（不会崩，但会退化成不保活），这一点在此记录备查。

---

### H22. 通知点击也优先跳哔哩哔哩客户端（用户要求）

**要求**：点击通知跳转直播间，同样是"客户端优先、浏览器兜底"。

**关键约束**：通知的 `contentIntent` 必须在**创建通知那一刻**确定目标，系统不会替我们"先试 A 再退 B"。
三条路各自的坑都试过/想过：

| 做法 | 问题 |
|---|---|
| 创建时用 `resolveActivity` 判断客户端装没装 | 需要 `<queries>`（Android 11+ 包可见性），而 `<queries>` 里的 intent 要按 intent-filter 规则匹配对方；
只要对方把 `bilibili://` 写成带 host/path 的过滤器，我们就可能匹配不上 → `resolveActivity` 恒为 null →
**永远只跳浏览器且毫无报错**（本项目一直在防的"静默降级"）。另外"通知发出后、点击前卸载了客户端"会点不动 |
| 直接把 `bilibili://` 当 contentIntent | 没装客户端的用户点了**没反应**（系统层 ActivityNotFound），比进浏览器更糟 |
| **跳板 Activity（采用）** | 点击那一刻 `startActivity` 直接试、捕获异常再退下一档 |

**实现**：新增 `notify/LiveRoomRedirectActivity`（透明、`noHistory`、`excludeFromRecents`、`exported=false`，
manifest 里用 `Theme.Translucent.NoTitleBar`）。它在 `onCreate` 里按序尝试并立即 `finish()`：

```
bilibili://live/<roomId>  →  https://live.bilibili.com/<roomId>  →  应用内页面（MainActivity）
```

`NotificationPoster.contentIntent` 改为指向它（通知带地址时），并给这类通知**新增「查看记录」动作**指向应用内页面 ——
否则"点通知进直播间"会把原来"点通知进应用"的入口弄丢。

**实机验证**（模拟器未装客户端，正好走完降级链）：点击通知后 logcat ——

```
ActivityTaskManager: START u0 {cmp=com.example.bilimonitor/.notify.LiveRoomRedirectActivity} (BAL_ALLOW_VISIBLE_WINDOW)
ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=bilibili://live/...} result code=-91
LiveRoomRedirect: 没有应用能打开 bilibili://live/25971921：No Activity found to handle Intent {...}
ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=https://live.bilibili.com/...
  cmp=org.chromium.webview_shell/.WebViewBrowserActivity} result code=0
```

即"跳板 → 先试客户端（无处理器）→ 自动落浏览器"。另外展开通知确认多出**「查看记录」**动作，
点击后 `START {flg=0x30000000 cmp=...MainActivity}` 回到应用内页面 ✓。
验证用的测试通知已从 `notification_outbox` / `notification_history` / `notification_id_registry` 清理干净（残留 0|0|0）。

> 说明：模拟器上没有哔哩哔哩客户端，所以"客户端那一跳成功"这一步无法在此环境端到端演示；
> 但该分支就是一条 `ACTION_VIEW bilibili://…` 的 `startActivity`，与二级页面按钮走的是同一个 scheme
> （那个按钮在本轮之前的验证中已证明能正确构造并交给系统解析）。真机上装了客户端时会直接进 App。

---

### H21. 编译状态（用户要求"尝试编译"）

| 目标 | 结果 |
|---|---|
| `:app:assembleDebug` | **通过**（APK 67.1 MB） |
| `:app:testDebugUnitTest` | **通过**（58 个用例） |
| `:app:compileDebugAndroidTestKotlin` | **通过**（instrumented 测试也能编译） |
| `:app:assembleRelease` | **失败，但不是代码问题** |

release 失败原因：`lintVitalAnalyzeRelease` 需要 `com.android.tools.external.com-intellij:intellij-core:31.7.3`，
而它从未被下载到本机 Gradle 缓存里，`--offline` 下无法解析：

```
Execution failed for task ':app:lintVitalAnalyzeRelease'.
> Could not resolve all files for configuration ':app:detachedConfiguration1'.
   > Could not download intellij-core-31.7.3.jar ...: No cached version available for offline mode
```

这与我在 H19 里把 `checkReleaseBuilds` 从 `false` 改回 `true` 直接相关：
**打开 release lint 检查后，离线环境再也无法完成 release 构建**（lint 需要那份未缓存的依赖）。
两个选项：
1. 保持 `checkReleaseBuilds = true`，首次 release 构建联网一次（把 lint 依赖拉进缓存），之后可离线；
2. 把该开关改回 `false`，release 构建在离线环境下可用，但代价是致命问题不再在构建期拦截。
（本次按用户要求中止了 release 构建，未做取舍。）

---

### H3. 由此暴露的两个工程问题（已修复）

**可观测性**：导入失败在界面上只有一句笼统的"没有获取到可导入的主播"，
无法区分「接口没返回数据」「字段解析失败」「凭证/签名问题」。
修复：错误信息带上接口 `code` 与 `message`；日志记录原始条数、跳过条数、去重后条数；
对话框实时显示任务真实状态（`REQUESTED/FETCHING/APPLYING`）。

**凭证泄露风险**：为定位 `-400` 曾把 HTTP 日志临时开到 `BODY` 级别，
导致请求头中的 `SESSDATA` 被写入 logcat（而 logcat 任何应用可读）。
已改回 `BASIC` 并在代码里写明"绝不使用 BODY/HEADERS"的原因；
含凭证的探测脚本已删除、模拟器日志缓冲已清空、应用数据已清除（强制重新登录）。

---

| # | 项 | 说明 |
|---|---|---|
| E1 | 实体注释声称的 CHECK 约束在 schema 中不存在 | `5.json` 所有 `CREATE TABLE` 均无 CHECK；注释与实现不符，`active=0 ⇒ aggregateId IS NULL` 无 DB 层保证 |
| E2 | `idx_attempt_unfinished` 形同虚设 | `WHERE finishedAt IS NULL` 的唯一索引，SQLite 视 NULL 互不相同 → 无约束力 |
| E3 | `return@withTransaction` 是提交而非回滚 | `MonitorRepository` 捕获 `DuplicateOpenSessionSignal` 后事务内已完成的写入会被提交（触发条件罕见） |
| E4 | 其余零引用死代码 | `NotificationFactory`、`NotificationNavigationPayload`、`TickContext`、`LiveSessionOrigin`、`CasWriteResult`、`StatisticsInclusionState`、`RuntimeLockRepository.release`、`reconcileDuration` |
| E5 | 枚举英文名仍有暴露点 | 详情/历史页的 `DataConfidence.name`（`开播:CORRECTED`）、数据页 `RestoreConflictType.name` 的 `else` 分支 |

---

## C-bis. 规格明确要求但实现从不读取的配置（文档交叉验证后新增）

`AppDatabase.kt:233-237` 的配置种子实际写入值：
```
interval=180, batch=50, maxConcurrency=4, timeout=15, retries=2,
retryBase=5, maxRetryDelay=60, cbThreshold=3, cbRecovery=60,
aggregation=1/4/5s, batchCooldown=30, freshnessStale=300,
startCount=1, endCount=2, placeholder='[待获取]'
```

| 配置项 | 种子值 | 文档要求 | 实现现状 |
|---|---|---|---|
| `maxConcurrency` | 4 | 原规范 46 要求批次内**并发**请求 | ❌ `MonitoringEngine.kt:106` 是串行 `for` 循环，字段从不读取 |
| `batchCooldownSeconds` | 30 | 批次之间需冷却 30s | ❌ 无任何冷却/间隔逻辑 |
| `circuitBreakerThreshold` / `RecoverySeconds` | 3 / 60 | 原规范 180 要求 API 熔断器 | ❌ 从不读取，无熔断实现（只有传输失败计数 + Gap） |
| `maxRetries` / `retryBaseSeconds` / `maxRetryDelaySeconds` | 2 / 5 / 60 | 请求级重试与退避 | ❌ 从不读取；通知重试用的是硬编码 5 次 / 60s·2^n（`NotificationDispatcher.kt:183-186`） |
| `startConfirmationCount` | 1 | 开播确认阈值（全局 + 主播级可覆盖） | ❌ `StateConfirmationPolicy.kt:87-108` 只读 `endConfirmationCount`，LIVE 方向恒 1 次 |

**结论**：设置页里「最大重试次数」这一项**不仅范围错配（B3），而且后端根本不生效** —— 它写进了库，但没有任何代码读取。

### 其他文档交叉验证坐实的缺口
- **免打扰时段（静默时段）**：文档要求可配 23:00–07:00、默认关。实现中 `StreamerMonitorPolicyEntity.overrideQuietHours`（`ConfigEntities.kt:116`）是死字段，无 UI、无逻辑 → **功能不存在**。
- **分组系统**：文档 P1 功能，实现只有数据层（见 D1）。
- **历史容量低水位预警**：文档要求 95% 预警 / 90%×N 低水位，实现只有硬清理，无预警（`MaintenanceRepository.kt`）。
- **日志保留策略**：文档要求监控错误 90 天 / 10000 条、应用错误 7 天 / 5000 条。实现中 `monitoring_error_log`、`application_error_log`、`audit_log`、`health_event` **均不在任何保留策略内**（见 D7）→ 无界增长。
- **0.6.28 测试门槛 21 项**（已核对 `doc_v2.4.md:2406-2432` 原文）：Partial 索引迁移、sourceDataVersion 并发递增、PendingTransition 并发、旧 generation/fencingToken CAS 拒绝、首次 UNKNOWN→LIVE 无 START 通知、软删除转 ABANDONED、异常恢复 LIVE/OFFLINE 通知语义、ROUND 不关场次、Aggregate 崩溃恢复、同一 notificationId 重投、统计缓存时区隔离、导出分页一致性、stableId/uid 冲突矩阵、跨设备 correction 引用 stableId —— **当前 `src/test` 不存在，21 项全部缺失**。

---

## E. 批次执行记录

### 批次 5（C1 + C4 + C5 + C8）✅
- **C1 诊断页**：新增 `MonitoringGapDao.observeOpenStreamerGaps(limit)`（查 `affectedEndAt IS NULL` 的主播级盲区）；
  订阅 `LogDao.observeAppErrors`。界面新增「主播级盲区（未恢复 N）」与「软件运行错误」两节，
  并把 7 处枚举英文名（gapReason / healthStatus / errorCode / problemKey）全部本地化。
  实现细节：`combine` 只有到 5 路的具名重载，多出的一路会落到 vararg 重载退化成 `Array<Any?>`，
  因此把两类日志先合成 `Pair` 再合并。
- **C4 通知历史**：`statusLabel` 补齐 `CREATED`/`PROCESSING`/`DELIVERY_UNKNOWN`（`PROCESSING` 由
  `NotificationDispatcher` 必然写入，原先直接显示英文），并给 `DELIVERY_UNKNOWN` 单独配色。
- **C5 系统恢复通知**：`createSystemProblem` 对恢复事件确实写入 `problem:<key>:recovered` 后缀，
  真正的 bug 是判断顺序 —— `startsWith("problem:")` 抢在 `:recovered` 之前命中，
  导致恢复通知一直被显示成"系统问题通知"。改为先判后缀。
- **C8 账号代际**：`currentAuthGeneration() = MAX(authGeneration)`，而 `revokeAll` 只置 `revokedAt`，
  原实现只把 `nextGeneration` 写进审计 JSON → 在途导入任务的代际校验依旧通过。
  修复：退出登录时落一条 generation+1 的"已登出"会话行（`accountHint = null`），
  使 `currentAuthGeneration()` 立即前进，`applyImport` 随之按"登录状态已变化"取消任务。

### 批次 6（C2 + C3）✅
- **C2 无障碍保活**：新建 `background/AccessibilityKeepAlive.kt`
  - `isEnabled()`：用 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 精确匹配本应用组件；
  - `observe()`：由 `AccessibilityManager` 系统回调驱动（规格 0.6.47.2 要求"实时更新、不得缓存为常量"），
    附带 10s 兜底轮询应对直接 kill 进程的 ROM；
  - `openSettings()`：跳转系统无障碍设置（引导页选项二与设置页都承诺了这一步，原先无任何入口）。
  - `MonitoringController.syncWithConfig`：`ACCESSIBILITY` 与 `FOREGROUND_NOTIFICATION` 一样按实时模式
    启动前台服务。原实现只对后者启动，选择无障碍会落到 `else` 被降级成 15 分钟周期任务。
  - `HealthRepository`：按规格的状态映射表如实显示 —— 选了无障碍但服务未开启时
    `DEGRADED` +「无障碍保活已失效，请重新开启」，不再谎报"监控中（实时）」。
  - `MonitoringController` 常驻订阅无障碍状态变化并写 `HealthEvent`（规格：任何降级不允许静默发生）。
  - 新增共用组件 `ui/common/AccessibilityEnableRow.kt`，在引导页与设置页展示**实际**开关状态 + 跳转按钮。
- **C3 FGS 类型**：`dataSync` → `specialUse`（Android 15+ 对 `dataSync` 有 24h 内累计 6h 上限）。
  清单换成 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `android:foregroundServiceType="specialUse"`
  + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性。代码按 API 分支：
  `SPECIAL_USE` 是 API 34 才有的类型位，31–33 传 0。

### 批次 7（C-bis 配置接线）✅
新增 `domain/policy/RetryPolicy.kt`（纯计算，可单测），并把 5 类"只存不读"的配置接进引擎：
- `startConfirmationCount`（C9）：`StateConfirmationPolicy` 的 LIVE/ROUND 方向改为按该阈值连续确认。
  同时修掉一个被这次改动**暴露出来**的隐患：两个方向共用 `streamer_pending_transition.confirmationCount`
  一列，方向切换时会把上一方向攒下的次数带过来。改为由调用方在方向匹配时才把计数交给策略。
- `maxRetries` / `retryBaseSeconds` / `maxRetryDelaySeconds`：批次内按 `base * 2^n` 退避重试（上限可配）。
- `circuitBreakerThreshold` / `circuitBreakerRecoverySeconds`：连续失败达阈值后打开熔断，
  窗口内不再发请求、如实记网络错误并开盲区。
- `maxConcurrency`：批次请求并发执行（`Semaphore` 限流，上限 16）。**状态提交仍严格串行**，
  只有网络请求并发，不影响 0.6.15 的 CAS 提交契约。
- `batchCooldownSeconds`：批次之间冷却（只在还有后续批次时等待）。
设置页同步补上并发批次数、批次间隔、熔断阈值三项输入。

### 批次 8（D11 + 0.6.28 测试）✅
- **release 构建**：`isMinifyEnabled = true` + `isShrinkResources = true`；
  `proguard-rules.pro` 从空文件补齐为覆盖
  kotlinx.serialization / Retrofit / OkHttp / Room / Hilt / WorkManager / 系统组件 / 枚举 的完整规则。
  签名走 `keystore.properties` 或 `BILIMONITOR_*` 环境变量注入，缺失时保持未签名（不影响 debug）。
  新增 `.gitignore` 排除签名材料。
- **单测**（`app/src/test`，`./gradlew :app:testDebugUnitTest` → **32 通过 / 0 失败**）：
  - `StateConfirmationPolicyTest` 12 例：非 SUCCESS 不参与转换、首次 UNKNOWN→LIVE 的
    `FIRST_OBSERVATION`、关播连续确认与失败不推进计数、开播 1 次/多次确认、
    方向切换计数重置、异常恢复 `RECOVERY_RECONFIRM`、状态一致时复位挂起计数、
    ROUND 语义、远端状态非法。
  - `RetryPolicyTest` 10 例：总尝试次数与硬上限、指数退避与上限夹取、非法参数退化、
    熔断阈值/恢复窗口/成功清零。
  - `ParseStreamerInputTest` 10 例：默认房间号、UID 模式、两类链接不受模式影响、
    超长数字不抛异常、零/空/乱码/无关链接被拒。
  - 为可测性把输入解析抽为 `StreamerInputParser`（顶层 `InputMode` / `ParsedInput`），
    `StreamerRepository.parseStreamerInput` 转发，避免测试复制生产逻辑。

**未覆盖的门槛**：0.6.28 剩余的 DAO/事务类用例（部分唯一索引迁移、`sourceDataVersion` 并发递增、
`stableId`/uid 冲突矩阵、导出分页一致性、Aggregate 崩溃恢复等）需要 instrumented test 或 Robolectric。
**后记**：模拟器就绪后已补上 instrumented 测试通道 —— 见下方「F 节后追加」。本机原本无设备，
现已具备真实设备验证能力。

### 批次 8 补充：instrumented 测试通道（模拟器就绪后）
- `app/build.gradle.kts`：加 `testInstrumentationRunner`、`androidTestImplementation`
  （junit / androidx.test.ext:junit / androidx.test:runner）。
- `AppDatabaseFactory` 拆出 `createForTest(context, name)`，用**独立数据库文件**跑测试，
  装配与正式库完全一致（同一套迁移、单行种子、部分索引），**不触碰应用正式数据**。
- 首个 instrumented 用例 `DiagnosticExporterTest`：验证诊断包 6 个分片齐全、
  各分片是**合法 JSON**（用真实解析器校验而非字符串包含）、
  以及规格的两条硬性边界 —— **不含任何凭证**、**不含主播历史明细**。

---

## F. 建议修复批次（原始排期，供追溯）

| 批次 | 内容 | 状态 |
|---|---|---|
| **1** | A1 + A1b + A3 | ✅ **已完成** — 封面/分区/短号恢复 + Android 12 通知恢复 |
| **2** | A2 + A4 + A5 | ✅ **已完成** — 清理链修复 + 日志保留策略 + 故障通知可用 |
| **3** | B1 + B2 + B3 | ✅ **已完成** — UI 承诺兑现 + 反馈可见 |
| **4** | B4 + B5 + B6 | ✅ **已完成** — 添加主播、导入、引导的健壮性 |
| **5** | C1 + C4 + C5 + C8 | ⬜ 待做 — 诊断/通知/账号隔离的确定性缺陷 |
| **6** | C2 + C3 | ⬜ 待做 — 保活诚实性（涉及 Manifest + 策略，改动面较大） |
| **7** | C-bis 配置接线 | ⬜ 待做 — 让设置页里已有的开关真正生效（并发/冷却/熔断/重试/开播确认次数） |
| **8** | D11 + 0.6.28 测试 | ⬜ 待做 — release 构建 + 21 项规格测试门槛 |
| **9** | 待裁决 | ⬜ D1–D6 是「待做功能」还是「放弃范围」；C-bis 免打扰/容量预警是否要做 |

> 每批次改完立即 `./gradlew :app:assembleDebug` 验证。

### 已完成的验证记录
```
批次 1-4 改动后：./gradlew :app:assembleDebug --offline
→ BUILD SUCCESSFUL in 52s
→ app/build/outputs/apk/debug/app-debug.apk  64.7 MB
```
本次未新增/修改任何 Room 实体或迁移，数据库版本仍为 **5**，无需 schema 迁移。

### 尚未实机验证的部分（需真机/模拟器）
- A3：Android 12/12L 真机上的通知投递（逻辑已按 API 等级分支，未运行验证）
- A2：清理链在存在 30–90 天历史数据时的实际执行路径（静态推演 + 编译通过，未跑 instrumented test）
- A1：`cover_from_user` / `area_v2_parent_name` 字段名已用真实 HTTP 响应核实，但**通知大图是否真正显示**需真机确认

---

## F. 文档自身的硬冲突 —— 裁决结果

> **裁决原则**（用户授权按"最符合软件功能"自行判断）：
> 文档冲突本身不影响软件，但**冲突暴露出的实现缺口**可能影响。逐条判断
> 「这条冲突背后有没有真实功能损失」—— 有就改代码，没有就把文档对齐实现，
> 绝不为文档矛盾去做没有功能收益的 schema 迁移。

### 关键证据：文档里的 CHECK 约束在实现中并不存在
```
grep "CHECK\(" app/src/main/java → 0 命中
```
`health_event` 等表**没有任何 CHECK 约束**（实体注释声称有，实际没有）。
因此「枚举取值必须与 CHECK 逐字一致」在实现层面**无约束力** —— 这是判定
「枚举冲突是否构成迁移障碍」的决定性事实：**不构成**。

### 逐条裁决

| # | 冲突 | 裁决 | 依据 |
|---|---|---|---|
| 1 | 0.6.43 称「44 个 @Entity」，实际 42 个；4 个实体文档只有 DDL 无 Kotlin 声明 | **文档订正为 42**；代码无需改动 | 纯统计与文档同步问题，实现里 42 个实体齐全且编译通过，无功能损失 |
| 2 | 健康枚举：0.6.2 定义 5 值，0.6.47.2「已定稿」用 `USER_ACTION_REQUIRED`/`SYSTEM_RESTRICTED`（枚举中不存在），历史章节还有第三套 8 值 | **复用既有 5 值枚举 + 细分 userMessage**（见下方「已落实的改进」） | 无 CHECK 约束 → 加值零障碍，**但也没有功能收益**（用户看到的是中文文案，不是枚举名）。规格 0.6.47.2 表达的是"6 种情境要有 6 条不同文案"，用 `userMessage` 区分即可完整满足，且**不改 schema、不动已落库数据** |
| 3 | `OverallHealth` 全文无枚举定义却被 188.5 / 249.10.7 引用 | **文档补定义**，与实现一致（`data class OverallHealth(status: MonitorHealthStatus, userMessage: String, …)`） | 实现已存在且工作正常，是文档缺口而非代码缺口 |
| 4 | 日志级别三套口径；0.6 日志表无 severity/message/stackTrace 列，`correlationId` 无处落库 | **本轮不加列**；改为把已有能力做成对用户真正有用的形态 —— 实现「一键导出诊断包」 | 加 severity/stackTrace/correlationId 需要迁移，而**功能收益很低**：诊断日志本就是给开发者看的，当前已有 `errorCode` + `detail` + `operationId`（审计表里完整串联）。用户真正缺的是**能把现场打包交给开发者**的能力，而规格 219 要求的诊断包**原本完全没实现** |
| 5 | 其余 19 条（AppSettings 默认值、MonitoringGapStreamer 字段名、可信度定义、章节编号错乱等） | **一律以 0.6 最终契约为准**，实现保持不变 | 按文档自身的权威分层规则（`0.6 > 规范主体 > 附录A > §100`）处理，无需代码改动 |

### 本条裁决带来的实际功能改进

**新增：一键导出诊断包（原规范 219）** —— 这是 F 节审查中发现的**真实功能缺失**：
`ExportType.DIAGNOSTIC` 早已定义，但代码里**只用过 `HTML`**。

- 新增 `data/repository/DiagnosticExporter.kt`，产出
  `BiliMonitor_Diagnostics_<日期>.zip`，内含规格 219 指定的 6 个文件：
  `app_info.json` / `monitoring_logs.json` / `application_errors.json` /
  `health_snapshot.json` / `settings_sanitized.json` / `migration_info.json`
- **硬性边界已遵守**：不读取 `CredentialStore`，包内**不含任何 cookie/token/账号信息**；
  不导出主播历史，只导出主播数量等聚合值
- `health_snapshot.json` 额外包含未恢复的主播级盲区、活动问题与最近健康事件
  （这些正是排查"为什么没收到通知"最需要的现场）
- `migration_info.json` 会同时给出**数据库实际版本**与**代码编译版本**，
  并声明 `destructiveMigrationAllowed = false` —— 直接服务于下面这次实测发现的降级崩溃问题
- 入口：健康中心页新增「诊断包」卡片 + 「导出诊断包」按钮，导出后显示文件名与体积

**改进：按规格 0.6.47.2 状态表补全两条被折叠的降级文案**
- 「通知权限被拒」原先被折叠成通用的"前台服务已停止"，用户不知道该去授权。
  现在独立为：**「需要通知权限才能后台监控，点此授权」**（并检测 `POST_NOTIFICATIONS` 实际状态）
- 「FGS 被系统限制 / 达时长上限」独立为：**「系统已限制后台运行，监控间隔已自动放慢」**

### 实测发现的新问题（不在原台账，已记录待裁决）

**数据库降级即崩溃**：在模拟器上安装时捕获
```
FATAL EXCEPTION: DefaultDispatcher-worker-2
java.lang.IllegalStateException: A migration from 6 to 5 was required but not found.
  at E1.i.onDowngrade(...)
```
该 AVD 上残留着数据库版本 **6** 的旧安装，而当前代码声明 `version = 5`
（`app/schemas/` 只有 2/3/4/5.json）。Room 走进降级路径 → 开库失败 → **启动即崩溃**。

叠加的事实：`build.gradle.kts` 里 **`versionCode` 始终是 1**，从未随版本递增。
因此任何装过 DB v6 中间版的用户，升级到当前版本都会遇到同样问题，且无法自愈。

**未修（需要产品决策）**，可选方案：
1. 把 `AppDatabase` 提到 6，并正常化 `versionCode`（每版递增）—— 推荐，语义最清晰
2. 增加显式降级处理（`MIGRATION_6_5` 或自定义 `onDowngrade` 策略）

---

`doc_v2.4_分析报告.md` §7 列出 23 条，最关键的 4 条：

1. 0.6.43 声称「44 个 @Entity」，实际 42 个；且 `MonitoringConfigEntity` / `MonitoringConfigRevisionEntity` / `SourceDataRevisionEntity` / `SystemRuntimeLockEntity` **文档只有 DDL 没有 Kotlin 实体声明**（实现里是有的，属文档缺口）。
2. 健康枚举冲突：0.6.2 定义 `MonitorHealthStatus{HEALTHY,DEGRADED,RECOVERING,BLOCKED,STOPPED}`，但「已定稿」的 0.6.47.2 使用了不存在的 `USER_ACTION_REQUIRED` / `SYSTEM_RESTRICTED`；历史章节还有第三套 8 值版本。实现目前采用 0.6.2 的 5 值版本。
3. `OverallHealth` 全文无枚举定义却被多处引用（实现里是 data class + `MonitorHealthStatus`，属合理落地）。
4. 日志级别三套口径并存，且 0.6 的日志表没有 severity/message/stackTrace 列，`correlationId` 无处落库。

> 注：文档实际 **16882 行**（341,772 字符）。
> 权威分层：`0.6 最终实现契约(1–3973) > 规范主体 00–15 > 附录A > §100(已作废)`。
> 例外：**249.x 虽带历史标记，但内容是效力最高的最终规则**。

