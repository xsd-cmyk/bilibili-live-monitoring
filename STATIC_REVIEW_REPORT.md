# 稳定性与健壮性静态审查报告

> 审查对象：`D:\ai-workspace\dsh\bilibili-live-monitoring-2`（Kotlin / Compose / Room / Hilt / WorkManager）
> 方式：**纯静态阅读**，未编译、未运行测试、未修改任何代码
> 规模：`app/src/main` 下 **82 个 .kt / 18,518 行**；测试 `app/src/test` 5 个文件 48 个用例、`app/src/androidTest` 1 个文件 4 个用例
> 审查日期：2026-09-13

---

## 0. 结论摘要

五片全部完成（我自己的跨切面复核 11 条 + 五个分片 74 条，去重后约 60 条独立问题）。

| 级别 | 条目 |
|---|---|
| **严重（3）** | **B1** `DuplicateOpenSessionSignal` 被吞后正常返回 → 半截事务提交：状态已 LIVE 却没有场次/事件/通知，且下一 Tick 不再产生转换 → **这段直播在历史/统计/导出中永久缺失**；**A1** 前台服务 Tick 循环的 `getSnapshot()` 在 try 之外 → 服务假活（显示"监控中"但零检查）或崩溃循环；**A2** 进程死亡跨越的场次被写成"时长=整个断档"的假场次（13 小时）并进入历史/统计/导出/备份 |
| **高（约 12）** | C1 WBI 密钥永不按天刷新（跨天后导入稳定 -400）；C2 `live_status` 缺省 0 把"字段缺失"当"未开播"（可致全部主播同时被确认关播）；2.1/C4/B3/D1 恢复·导入·导出全链路在主线程（ANR）；2.2/B5 未知枚举读表即崩、2.4/B2 库损坏与索引重建失败都没有自愈路径；2.5/C3 凭证库构造失败=启动即崩 + 云备份带来解不开的密文；A3/B13 冷启动清理与「恢复/立即清理」被租约挡住（清理对活跃用户**永不生效**）；A4 延迟任务里 `dispatchDue` 无保护→进程崩溃；A5 Tick 全有或全无（最坏 31 分钟）；E1 PROCESSING 卡死无运行期回收；E2 `createSystemProblem` 三处不在事务内；E3 通知投递占用 Tick 互斥锁（最坏 300s）；B4 容量清理超 SQLite 变量上限后永久失败；D2 导入无并发保护；D3 二维码主线程生成；D4 历史页重复订阅；D5 提示条不参与 combine；D6 chips 挤出屏幕不可达；D7 全站未用 `collectAsStateWithLifecycle` |
| **中（约 25）** | 见 2.x 与 3.x（Cookie 持久化丢字段、Cookie 登录先落盘再校验、统计口径与历史/导出不一致、恢复子行静默失败、诊断包迁移清单停在 5→6、封面解码无采样、过期通知照投、熔断按尝试计数、冷却在串行时失效、时区只写不读、导入取消无效……） |
| **低（约 20）** | 死代码/陷阱常量（`followingsUrl(ps=50)`、`lastTitle` 语义反了）、`!!` 两处、bootId 兜底常量、健康事件丢原因、测试覆盖、lint 闸门关闭、日志无长度上限、全表加载…… |

整体判断：这个项目的**状态机与 CAS 纪律**（租约 + fencing token + 确认策略 + 幂等 eventKey）
是少见的严谨，多处注释记录了"曾经错在哪、为什么这么写"，属于有意识设计的健壮性。
主要风险不在设计，而是集中在四类：

1. **线程归属**：仓库层没有统一约定，只有 `CoverRepository` 切了 IO，恢复/导入/导出全在主线程；
2. **失败可见性**：190 处 `runCatching`，87 处直接吞掉，`StartupRecovery` 与 `MonitoringWorker` 是重灾区；
3. **没有自愈路径**：库损坏、索引重建失败、凭证密钥失效、未知枚举 —— 全部只能清数据；
4. **同一根因修了一半**：维护锁接管只改了导出（恢复/清理还在用严格版）、
   冷却 delay 挪出闸外导致串行时节流失效、封面写入切了 IO 而导出没切。

---

## 0.5 最值得先看的 6 条（如果只修一部分）

| # | 问题 | 为什么它排在前面 | 修复量 |
|---|---|---|---|
| 1 | **B1** 半截事务提交（LIVE 但无场次） | 数据静默丢失，用户永远不知道少了哪一场 | 十几行（catch 内重新 throw，或改为复用已有 OPEN session） |
| 2 | **A2** 假场次（时长=断档） | 错误数据会进入历史/统计/导出/备份，越晚修污染越多 | 几十行（关播前读盲区截断 endTime） |
| 3 | **C1** WBI 密钥不刷新 | "昨天好的今天全废"，用户无法自救 | 一行判断 |
| 4 | **C2** `live_status` 缺省 0 | 一次上游字段变动 = 全部主播被确认关播 + 批量误报 | 十几行（改可空 + 校验） |
| 5 | **A3/B13** 清理/恢复被租约挡住 | 清理与容量预警对活跃用户**从未生效**；「立即清理」「执行恢复」随机失败且理由错误 | 两行（换成接管版维护锁） |
| 6 | **2.1/C4/B3/D1** 仓库层切 IO | 一条统一约定，覆盖恢复/导入/导出/诊断四条链路，消除 ANR 风险 | 每处一行 `withContext` |

主要风险集中在**三类**：

1. **线程归属**——除 `CoverRepository` 外，所有仓库层重活都跑在调用方（=主线程）上；
2. **失败可见性**——大量 `runCatching` 只取 `getOrNull()`，失败既不重试也不留痕（190 处 `runCatching`，其中 87 处直接吞掉）；
3. **没有自愈路径**——数据库损坏、凭证文件损坏、维护标记残留这类"持久化状态坏了"的场景，应用只能靠用户清数据。

---

## 1. 审查方法与分工

按关注点切成五片并行深读，每片都要求给出 `文件:行号` 级证据、区分"已确认"与"疑似"，并附"已核对无问题"的部分：

| 分片 | 范围 |
|---|---|
| A 监控引擎 / 调度 / 生命周期 | `background/**`、租约与围栏令牌在引擎侧的使用 |
| B 持久化层 | `data/local/**`、Room 迁移链、事务与 CAS、备份恢复、清理容量 |
| C 网络 / 凭证 / 导入导出 | `data/remote/**`、`core/CredentialStore`、`WbiSigner`、`AppModule` 的 OkHttp |
| D UI 层 | `ui/**`（Compose + ViewModel） |
| E 通知流水线 / 核心工具 / 诊断 | `notify/**`、Outbox 写入器、`core/**`、`DiagnosticExporter` |

以下第 2 节是**我自己复核**的跨切面问题（不依赖分片，均已读代码确认）。

---

## 2. 跨切面问题（已确认）

### 2.1 【高】仓库层的重活全部跑在主线程，只有 `CoverRepository` 例外

**证据**：`grep withContext|Dispatchers. app/src/main/java/.../data/repository` 只命中一处：

```
data/repository/CoverRepository.kt:53  suspend fun saveCover(...) = withContext(Dispatchers.IO) {
```

而所有 UI 调用方都是 `viewModelScope.launch { ... }`（= `Dispatchers.Main.immediate`），
仓库里的 suspend 函数除非自己切线程，否则**除 Room 的挂起调用之外全部在主线程执行**：

| 位置 | 主线程上做的事 |
|---|---|
| `BackupRepository.precheck()` | `parseBackupFile()` 全量 JSON 解码 + `sha256(json.encodeToString(body))` 重编码后哈希 |
| `BackupRepository.readPickedBackup()` / `readLatestBackup()` | `ContentResolver.openInputStream().readBytes()` 读整个文件 |
| `BackupRepository.applyRestore()` | 事务外还有 `parseBackupFile` + 冲突决策装配 |
| `ExportRepository.exportHistory()` | `buildHtml()` 对 N 行做字符串拼装 + `writeToDownloads()` 写盘 |
| `ExportRepository.exportStats()` | `buildStatsHtml()`（含 SVG 生成）+ 写盘 |
| `DiagnosticExporter.export()` | 手工 JSON 拼装 + 写 zip |

**后果**：备份/导出体积随场次数量线性增长。当前数据量（16 场、28 KB）无感，
但 500 个主播 × 数千场次时，恢复预检会在主线程解码+哈希数 MB JSON —— 直接 ANR
（Android 对前台应用 5 秒无响应即弹 ANR，用户会认为"恢复功能把应用卡死了"）。

**修复**：在这些 suspend 函数的入口包一层 `withContext(Dispatchers.IO)`
（与 `CoverRepository` 一致），或让调用方 `viewModelScope.launch(Dispatchers.IO)`。
注意 Room 的 `withTransaction` 内部自带调度，包一层 IO 不会与之冲突。

### 2.2 【高】枚举列是强解析，未知值直接抛异常；且迁移链与枚举扩展没有绑定机制

**证据**：`data/local/convert/DshTypeConverters.kt` 全部 40+ 个转换器都是
`v?.let { XxxEnum.valueOf(it) }`（第 20–130 行），没有任何容错分支。

**这是真实发生过的**：本项目测试期间用 sqlite 直接写入 `lifecycleState = 'CLOSED'`，
应用立刻陷入"启动即崩、反复重启"——`IllegalArgumentException: No enum constant ...LiveSessionLifecycleState.CLOSED`
抛在 Room 的 `Flow` 收集路径上，`logcat` 里只有 `FATAL EXCEPTION`。

**当前防护**：Room 的 `version` 守卫能挡住"装回旧版本"（旧版本打开新库会因缺迁移而拒绝启动），
所以"新版本写入新枚举值 → 用户降级"这条路径被挡住了。但仍有两条敞口：

1. 同一 DB 版本内新增枚举常量（例如只加 `NotificationEventType` 而忘记 bump `DB_VERSION`）——
   下次降级安装就不再有版本差保护；
2. 恢复路径从**用户可编辑的 JSON** 读枚举名：`BackupRepository:503`（`ConfirmedLiveStatus.valueOf`）、
   `:577`（`LiveEventType.valueOf`）、`:612`（`DataSource.valueOf`）都是裸 `valueOf`，
   而同一函数里另外几处却用了 `runCatching { ... }.getOrNull()`，口径不一致。

**后果**：任何一处命中，都是**读表即崩**（而不是"跳过这条脏数据"），且用户无法自救（只能清数据）。

**修复**（三选一，建议全做）：
- 转换器改为宽容解析：`enumValues<X>().firstOrNull { it.name == v }`，失败时记一条 `application_error_log` 并回退到安全默认值；
- 恢复路径统一 `runCatching { valueOf }`，坏行记 warning 后跳过（与既有 `restoreSessionReason` 的"跳过"语义一致）；
- 在 `AppMigrations` 里加一条注释约定：**新增枚举常量必须同时 bump `DB_VERSION`**（并写一条空迁移）。

### 2.3 【高】`applyRestore` 没有 catch：异常时恢复运行停留在 APPLYING，且完成 CAS 的返回值被丢弃

**证据**：

```
BackupRepository.kt:920  restoreDao.casTransition(runId, APPLYING, COMPLETED, ...)   // 返回值未检查
BackupRepository.kt:932  } finally { runtimeLockRepository.exitMaintenance(RESTORE) } // 只有 finally，没有 catch
```

- 事务内的任何异常（2.2 的枚举、外键冲突、磁盘满）都会**冒泡出去**：
  维护权会正确释放（`finally` ✓），但 `restore_run` 行永远停在 `APPLYING`；
  只有**下次冷启动**的 `StartupRecovery` 会把它改成 FAILED（`StartupRecovery.kt:57-65`）。
  同一进程内该行既不阻塞新恢复、也无法在诊断页解释"上次恢复到底成没成"。
- 第 920 行 CAS 若返回 0（行已被别处改状态），函数**仍然返回 `RestoreSummary` 当作成功**，
  UI 会显示"恢复完成"而库里并没有标记完成。

**修复**：加 `catch (e: Exception) { casTransition(APPLYING → FAILED, reason) ; throw }`，
并检查第 920 行的 CAS 结果（0 行时至少追加一条 warning）。

### 2.4 【高】数据库损坏 / 磁盘写满没有任何处理，`DATABASE_REPAIR` 是个空壳

**证据**：全仓库搜索 `integrity_check|SQLiteDatabaseCorruptException|SQLiteFullException` → **0 命中**；
`MaintenanceMode.DATABASE_REPAIR` 只被 `MaintenanceRepository.runCleanup()` 当作锁标签使用
（`MaintenanceRepository.kt:176, 322`），没有任何完整性检查或重建逻辑。

**后果**：本地优先的应用把全部业务数据放在一个 SQLite 文件里。掉电写坏 WAL、磁盘写满、
`SQLiteDatabaseCorruptException` —— 任一发生，Room 打不开库，
`BiliMonitorApp.onCreate` 里注入 `StartupRecovery` 就会抛，
**应用启动即崩且无法自愈**（唯一出路是用户手动清除应用数据，等于丢掉全部历史）。

**修复建议**：
- 启动时（`StartupRecovery`）做一次轻量 `PRAGMA quick_check`（可设频率，如每 N 次启动一次）；
- 捕获 `SQLiteDatabaseCorruptException`/`SQLiteFullException`：
  前者把库文件改名留档 + 重建空库 + 写 `health_event`/`problem_state` 让用户在诊断页看到；
  后者只发"存储空间不足"问题通知并暂停写入（而不是每条记录都抛）；
- 把 `DATABASE_REPAIR` 真正用起来，或在文档里删掉这个概念。

### 2.5 【中】`allowBackup="true"` 且没有任何排除规则：云备份会带走数据库与加密凭证文件

**证据**：`app/src/main/AndroidManifest.xml:22` `android:allowBackup="true"`，
全文件没有 `android:dataExtractionRules` / `android:fullBackupContent`。

**两层问题**：
1. **隐私/宣称不符**：应用与文档都说"数据全部保存在本机"（设置页原文），
   但 `allowBackup=true` 会把 `databases/bilibili_monitor.db`（主播列表、直播历史）
   与 DataStore 偏好同步到用户的 Google Drive 备份。
2. **换机崩溃**：`EncryptedSharedPreferences` 的密文文件会一起被备份，
   而解密它的密钥在**原设备的 Keystore** 里，不会跟着走。恢复后
   `CredentialStore`（`core/CredentialStore.kt:20`）在字段初始化里直接
   `EncryptedSharedPreferences.create(...)`，**没有任何 try/catch** ——
   密钥不存在/文件损坏时抛 `GeneralSecurityException`/`InvalidProtocolBufferException`，
   而它是 `@Singleton` 且被 `BiliCookieJar`（OkHttp 构造依赖）使用，
   失败会沿着依赖图变成"首个网络调用即崩"。

**修复**：
- 加 `android:dataExtractionRules` 排除 `bili_credentials` 与（按需）数据库；
- `CredentialStore` 的创建包 `runCatching`，失败时删除损坏的 prefs 文件重建，
  并写一条 `application_error_log`（"登录凭证已失效，请重新登录"），而不是让应用崩。

### 2.6 【中】`runCatching` 吞异常已成规模，多处失败既不重试也不留痕

**证据**（全量统计）：

```
runCatching 出现次数                       : 190
其中直接 .getOrNull()/.getOrDefault() 收尾 : 87   （占 46%）
空 catch {} 块                             : 0    ← 这点很好
```

热点文件：`BackupRepository`(24)、`FollowImportRepository`(13)、
**`StartupRecovery`(13，全部静默)**、`AuthRepository`(12)、`DiagnosticExporter`(9)、
`MonitoringEngine`(9)、`RecoverySessionRepository`(8)。

代表性例子：

```
StartupRecovery.kt:44-93   每一步都是 runCatching { ... }，没有 onFailure，也不写 health_event
BiliMonitorApp.kt:45-47    appScope.launch(IO) { runCatching { startupRecovery.run() } }  // 外层再吞一次
MonitoringWorker.kt:36-39  三处 runCatching 全部吞掉，且无条件返回 Result.success()
```

这与仓库自己的硬规则（`MonitoringEngine` 注释："任何降级都不允许静默发生"）相矛盾。
**后果**：崩溃恢复没跑完、迁移清理失败、通知结算失败——用户在诊断包里什么都看不到，
问题只能靠复现来定位（本项目已经为这类"看不见的失败"付过代价）。

**修复**：给 `StartupRecovery` 的每一步加 `onFailure { logAppError(...) }`；
`MonitoringWorker.doWork` 至少把失败写成 `AppError`，并返回 `Result.retry()`
（周期性 Worker 不需要 retry，但**必须留痕**）。

### 2.7 【中】`MonitoringWorker` 没有网络约束，离线时会自行制造"网络不可用"问题通知

**证据**：`MonitoringController.kt:112-121`

```kotlin
val request = PeriodicWorkRequestBuilder<MonitoringWorker>(minutes.toLong(), TimeUnit.MINUTES)
    .build()                       // 没有 setConstraints(NetworkType.CONNECTED)，也没有 backoff
```

省电模式最短 15 分钟一次 Tick（第 113 行 `coerceIn(15, 240)`）。
设备离线时整批观察失败 → `onTransportFailure` 连续 3 次即开熔断并写
`SYSTEM_PROBLEM`（"网络不可用"）→ 用户离线 45 分钟后收到一条通知，
内容对他而言是废话，且熔断打开还会抑制真实故障的判定。

**修复**：`.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())`，
离线时让 WorkManager 直接不派发（比"跑完再报错"更接近用户预期）。

### 2.8 【中】13 处非空断言 `!!`：11 处有守卫，2 处依赖"理论上不会发生"

已逐条核对（`grep` 全部命中 + 上下文阅读）：

| 位置 | 判断 |
|---|---|
| `AuthRepository:78` `nav!!` | 安全（上一行刚判 `nav != null && isLogin`） |
| `BackupRepository:701` `sidToInt[...]!!` | 安全（`restoreSessionReason` 已把 null 分支挡掉） |
| `FollowImportRepository:185/197` `firstObj!!`/`secondObj!!` | 安全（`codeOf(null) = -1`，只有非 null 才会走到） |
| `HistoryRepository:250` `findByStableId(stableId)!!` | 安全（同一事务内刚插入） |
| `StreamerRepository:91/145/251/284` `findById(...)!!` | 安全（同一事务内刚插入/恢复） |
| `StreamerDetailScreen:432` `roomTitle!!` | 安全（外层 `if (roomTitle != null)`） |
| `LoginScreen:608` `importStage!!` | 安全（`== null \|\|` 短路） |
| **`NotificationDispatcher:76`** `outboxDao.findById(outboxId)!!` | **有风险**：claim 之后、这里之前若有清理任务删掉了该行（`cleanupFinished`），直接 NPE |
| **`BackupRepository:609`** `it.backupStableId!!` | **有风险**：列本身可空（实体类型 `String?`），逐条恢复冲突时若历史脏数据里为 null 即 NPE |

**修复**：这两处改为 `?: return` / `?: continue`（各一行）。

### 2.9 【低】自动化测试集中在纯策略类，风险最高的代码零覆盖

**证据**：

```
app/src/test:      StateConfirmationPolicyTest(13)  RetryPolicyTest(10)
                   ParseStreamerInputTest(10)  QuietHoursPolicyTest(8)  BackupChecksumTest(7)
app/src/androidTest: DiagnosticExporterTest(4)
```

没有测试覆盖：**Room 迁移（v1→v8）**、**恢复流程**、**引擎 Tick**、**导出/导入**、**任何 DAO 唯一索引与 CAS 语义**。
而这一轮改动里已经出现过"迁移链 + 枚举 + 部分索引"组合才会暴露的问题。
建议优先补三个 instrumented 测试：迁移链完整性（`MigrationTestHelper` 从 v1 一路升到 v8）、
`idx_*` 部分唯一索引的实际约束力、恢复流程的幂等与失败回滚。

### 2.10 【低】`lint { abortOnError = false; checkReleaseBuilds = false }`

`app/build.gradle.kts:91-97`：release 不跑 lintVital，致命问题（例如缺少权限声明、
`PendingIntent` 不可变标志）不会在构建期被拦住。当前代码里 `PendingIntent` 用了 `FLAG_IMMUTABLE` ✓，
但把这道闸门永久关掉，长期看是负债。建议至少对 `release` 打开 `checkReleaseBuilds`。

### 2.11 【低-中，疑似】`bootId` 的兜底值会让"跨开机"的判断失效

**证据**：`core/AppClock.kt:33-39`

```kotlin
val bootCount = runCatching { Settings.Global.getInt(..., BOOT_COUNT, -1) }.getOrDefault(-1)
return if (bootCount >= 0) "boot-$bootCount" else "boot-unknown"
```

`bootId` 的语义是"开机实例标识"，用于 `NotificationAggregateDao.findInProgress(bootId)`（`NotificationDaos.kt:253`）
与 `MonitoringEngine.kt:225` 的 `windows.filter { it.bootId == clock.bootId() }` ——
即"聚合窗口只在同一次开机内复用"。但兜底值 `boot-unknown` **在每次开机都相同**，
于是当 `BOOT_COUNT` 读不到时（权限/定制 ROM/读取失败），上一轮开机遗留的
`COLLECTING` 聚合窗口会被当成"本次开机的窗口"，而它的 `windowEndElapsed`
来自上一次开机的 `elapsedRealtime`（开机会归零）——跨 boot 比较 elapsed 正是这套双时钟规则
明令禁止的事，后果是批量开播通知的窗口结算时机错误（早触发或长时间不触发）。

**修复**（一行）：兜底值改用"开机时刻锚点"而不是常量，例如
`System.currentTimeMillis() - SystemClock.elapsedRealtime()` 的取整值——
同一进程内稳定、跨开机必然不同。

---

## 3. 各分片审查结果

> 分片 A（监控引擎）、B（持久化）、D（UI）仍在深读中；下面先并入已完成的两片。
> 标注 **[已复核]** 的条目由我本人再次读代码确认；其余为分片结论（均带 file:line 证据）。

### 3.1 分片 C：网络 / 凭证 / 导入导出

**C1【严重】WBI 密钥永不刷新，跨天后所有签名请求稳定 -400** **[已复核]**
`core/WbiSigner.kt:33/39`：`cachedAtSeconds` **只写不读**（全仓库 grep 确认），KDoc 声称的"按天缓存"
没有实现；`ready()`（`:48`）只判断 `cachedMixinKey != null`。而 `AuthRepository.ensureMainSiteReady()`
**只在 `!ready()` 时**才刷新密钥（`:157-159`）。
→ B 站 mixinKey 每日轮换，进程存活期间旧 key 让 `ready()` 恒为 true，此后每次 `w_rid` 都算错，
导入功能"昨天好好的、今天全废"，唯一自愈是打开登录页或杀进程。
**修复**：`ready()`/`sign()` 判 `now/1000 - cachedAtSeconds >= 6h`（或跨自然日）即视为过期并触发一次 `nav` 刷新。

**C2【高】`live_status` 缺省 0 = 把"字段缺失"当成"未开播"，且标记为可信** **[已复核]**
`BiliDtos.kt:23/54`（`val liveStatus: Int = 0`）、`BiliLiveApi.kt:122-127`（`0 -> OFFLINE`）、
`:140`（`remoteStatusValid = true` 硬编码）。
→ 上游改名/裁剪字段/风控降级返回部分字段时，kotlinx 用默认值 0 → 判定 OFFLINE 且"响应有效"；
默认关播确认 2 次，两个 Tick 后**全部主播同时被确认关播**：进行中的场次被关闭（时长/历史写错）
并批量误报"已下播"。
**修复**：DTO 改 `Int? = null`，缺失/越界 → 返回 null（走 `missingUids` → `INVALID`），只有合法取值才 `remoteStatusValid = true`。

**C3【高】`EncryptedSharedPreferences` 构造失败即全局崩溃，且云备份会把解不开的密文带到新设备** **[已复核，与 2.5 同源]**
见 2.5（`CredentialStore.kt:20-26` + `AndroidManifest.xml:22`）。分片补充了**崩溃链路**：
`CredentialStore` → `provideCookieJar`(`AppModule.kt:60-62`) → `provideOkHttp`(`:93-98`)
→ 所有网络仓库无法注入 → **每次冷启都崩，无自愈路径**。

**C4【高】导出全链路在主线程做磁盘 IO** **[已复核，与 2.1 同源]**
见 2.1（`ExportRepository.kt:523-537`，全仓库只有 `CoverRepository`/`NotificationPoster` 切了 IO）。
分片补充：`BackupRepository.readLatestBackup()`(`:961-979`，**非 suspend**) 与 `readPickedBackup()`(`:990`)
也在主线程做 ContentResolver 查询+整文件读取，且 `DataScreen` 未用 runCatching 包住 ——
`resolver.query` 抛 SecurityException 会直接崩在 `viewModelScope` 里。

**C5【中】Cookie 持久化丢 domain/path/expiresAt，且不处理删除语义**
`CredentialStore.kt:57-68`（启动时硬编码 `domain("bilibili.com").path("/")` 重建）、`:71-82`
（`saveFromResponse` 只 `groupBy{name}.mapValues{last()}`，不看 expires/domain/path，也不处理 `Max-Age=0`）。
→ 服务端登出/过期不会清除本地值，空值照样带 `x=` 发出；SESSDATA 侧失效后本地仍长期携带，
表现为"接口说未登录、界面以为已登录"。
**修复**：持久化完整 cookie 字段（或 `Cookie.toString()` + `Cookie.parse`），尊重删除语义。

**C6【中】设备 cookie 写一次后永不刷新**
`AuthRepository.kt:87-107`（`if (cookieJar.hasDeviceCookie()) return`）+ `CredentialStore.kt:161-163`
（只看有没有、非空）。buvid3 一旦失效就永远补齐不上，主站接口持续 -400 且诊断看不出原因。
**修复**：记时间戳/写 expires，超 N 天强制重取；连续 -400 时主动清掉 buvid3 重取。

**C7【中】Cookie 登录"先落盘再校验"，校验失败不回滚 —— 会破坏原本可用的登录态**
`AuthRepository.kt:125-134`（`:126` 先 `importCookieHeader` 写库，`:129-134` 才用 nav 校验）。
→ 粘贴过期/不完整 Cookie 会覆盖掉原有的有效 SESSDATA，提示"Cookie 无效"的同时把用户踢下线。
**修复**：先解析到临时 Map 验证 nav，通过后再落库（最小改动：失败时回写备份）。

**C8【中】统计"已排除场次"结构性恒为 0，且同一查询跑两遍**
`StatsSeriesRepository.kt:168` 与 `:196-203` 是**完全相同**的查询与过滤，
而 DAO 的 SQL 明确 `WHERE endTime IS NOT NULL`（`MonitorDaos.kt:335`）→ `excluded` 恒为 0，
注释描述的数据根本没有来源。统计页/导出里是一个恒 0 的假指标 + 一次多余的全表区间扫描。
**修复**：真正统计未结束/不合格场次，或删掉该字段不再展示。

**C9【中】解析失败被归类成"网络失败"：熔断误开、用户看到"网络不可用"**
`BiliLiveApi.kt:63-79` 只捕 `HttpException` 与 `IOException`；`SerializationException`
（字段类型变化、HTML 错误页、空响应体）会逃出去，被引擎统一吞成 `null`
（`MonitoringEngine.kt:365-375`）→ 计为传输失败 → 连续 N 次开熔断 + 写 `NETWORK_UNAVAILABLE`。
为"响应不可解析"准备的 `BatchResponseValidity.INVALID` 永远不会被用到；HTTP 4xx（如 412 风控）
同样被归成 TRANSPORT_ERROR。
**修复**：补 `catch (SerializationException) → INVALID`，用 `HttpException.code()` 区分服务端拒绝与传输失败。

**C10【中】文件已经写出去了，却仍可能报"导出失败"**
`ExportRepository.kt:161-174`：`writeToDownloads` 成功后才做 CAS 与 `insertAudit`，
任一抛异常即被 `:181-191` 吞掉并 rethrow → 返回 `Result.failure`，而文件已在 Download 里。
用户会重复点击产生一堆同名副本。统计导出 `:292-304` 同型。
**修复**：写入成功即视为成功；后续记账各自 runCatching，失败降级为告警。

**C11【中】写入路径缺口：全量内存缓冲 + 失败残留 `IS_PENDING=1` 行**
`ExportRepository.kt:149`（一次取全部行）、`:533`（`content.toByteArray()`）、`:531-537`
（**无 catch**，失败留下对用户不可见的 pending 行，只能等系统 7 天回收）——
而封面路径 `CoverRepository.kt:98-105` **有** `resolver.delete(uri)` 清理，属"修了一半"。
类 KDoc 声称的"流式写出"与实现不符。
**修复**：抽公共 `writeToMediaStore(...)`（内部 catch → delete + 兜底置 IS_PENDING=0）；
封面下载加字节上限；导出改分块 append 写 OutputStream。

**C12【中】"从 Download 恢复"把"看不到"说成"不存在"**
`BackupRepository.kt:961-979` 任何失败都 `return null`，UI 提示"Download 中没有找到备份文件"。
Android 11+ 应用只能看到**自己创建的** MediaStore 行，本会话实测过：文件确实在
`/sdcard/Download/`，应用查询返回 0 行 → 提示把用户引向错误方向。
**修复**：以 SAF「选择文件导入」为唯一主入口；保留扫描时区分"不存在/不可见"，并把 query 包 runCatching。

**C13–C15【低】**
- `FollowImportRepository.kt:176-177` 手工拼 query 不过滤/不编码，而 `WbiSigner.kt:70-75` 的 `w_rid` 按**过滤后**的值算 → 当前参数全是数字故安全，一旦有 `!'()*` 或非 ASCII 即稳定 -400 且难定位；建议抽 `buildSignedUrl()` 单点实现。
- `ui/login/LoginScreen.kt:564-580` 凭证输入框明文回显且登录后不清空 → 加 `PasswordVisualTransformation` + 提交后清空。
- 死代码/陷阱：`BiliAccountApi.kt:32-33` `followingsUrl(..., ps = 50)` **默认值违反 ps≤30 上限**且无人调用（谁用谁踩已查过一轮的坑）；`postRaw`/`genWebTicketUrl`/`hasTicket()`/`streamerSummaries()` 均为废弃遗留；`LiveSessionTitleDao.kt:15` `lastTitle` 名为 last 实取最早一条，与新增的 `latestTitle` 并存极易误用。

### 3.2 分片 E：通知流水线 / 核心工具 / 诊断

**E1【高】`PROCESSING` 卡死态：实时模式下没有运行期回收器** **[已复核]**
`settleExpiredDeliveries` 的调用点只有两处：`StartupRecovery.kt:44`（冷启动）与
`MonitoringWorker.kt:36`（省电 Worker）。而实时模式会 `cancelPowerSavingWork()`
（`MonitoringController.kt:81-86`），引擎 Tick 只做 `processWindowEnds` + `dispatchDue`
（`MonitoringEngine.kt:211-212`，已逐行确认**没有** settle 调用）。
投递租约只有 30s（`NotificationDispatcher.kt:190`）→ 进程存活期间任何中断的投递
**永久停在 PROCESSING**：`claimOutbox` 只认 PENDING/RETRY_WAIT（`NotificationDaos.kt:31-34`），
该通知再也不会被投递，还会被 `countActiveOutbox` 算作活跃积压，只有重启才自愈。
附带：`dispatchDue` 把所有异常吞成一条日志（`NotificationDispatcher.kt:42-54`），
`finishAttempt` 的 CAS 返回值被丢弃（`:161-168`）。
**修复**：把 settle 挂到 Tick 开头（或 dispatchDue 首行）做运行期回收；catch 内显式置回 RETRY_WAIT/EXPIRED；检查 `finishAttempt` 返回 0 时记 app error。

**E2【高】`createSystemProblem` 三处调用点都不在事务内，违反写入器硬契约**
契约见 `NotificationOutboxWriter.kt:88-92`（"所有方法必须在调用方已开启的事务内执行"），
违规点：`MonitoringEngine.kt:467-479`、`:506-518`、`MaintenanceRepository.kt:139-161`。
→ `getOrCreateNotificationId` 的 `MAX(notificationId)+1` 与 INSERT 不再原子，
并发时撞 UNIQUE 索引后退化到 `allIds()` 全表装载 + 逐 id 线性探测。
**修复**：三处各包 `db.withTransaction { }`；或把"eventKey 已存在"与"id 被占用"分开处理。

**E3【高】通知投递在 Tick 互斥锁内串行执行，最坏 300s 拖住整轮监控**
`MonitoringEngine.kt:93`（`checkOnce` 全程持 `tickMutex`）与 `:212`（`dispatchDue()` 在锁内）；
`NotificationDispatcher.kt:38-56` 串行最多 20 条，每条封面下载最长 15s（`AppModule.kt:99-100`）。
→ 期间手动刷新、Worker、恢复补检全部阻塞，表现为"监控卡住"。
**修复**：`dispatchDue` 移出 `tickMutex`（锁内只 claim）；封面下载加更短超时与整体预算。

**E4–E8【中】**
- **诊断包迁移路径硬编码停在 5→6**（`DiagnosticExporter.kt:165-171`），而 DB 已到 8；同一文件 `:271-275` 刚把版本号改成引用共享常量，迁移列表却漏改 → 诊断包谎报"升不上去"。**这与本轮 v6→v7→v8 的改动直接相关，建议立即修**。
- **通知大图标解码无尺寸采样**（`NotificationPoster.kt:158-183`）：只限压缩字节（2MB），不限解码尺寸，高压缩大图可解码成数百 MB → OOM，且若异常抛出 `post()` 之外会把通知推进 E1 的卡死态。修：`inJustDecodeBounds` + `inSampleSize`。
- **`claimOutbox` 不判 `expiresAt`**（`NotificationDaos.kt:24-39`）：停机一天后重启，过期 24h 的开播通知照样投递（"XX 正在直播"时人早已下播）。
- **`deleteUnstartedAttempts()` 恒为 0 的死清理**（`NotificationDaos.kt:205-210`）：实体 `startedAt: Long` 非空（`NotificationEntities.kt:118-131`），条件永不成立；`MaintenanceRepository.kt:242` 统计的"清理占位 attempt"永远是 0。
- **bootId 退化为常量**（`AppClock.kt:33-39`）：与 2.11 同源，分片独立确认了后果路径 —— `NotificationRepository.kt:42-46` 用 bootId 相同/不同来选 elapsed 还是墙钟，而 `findInProgress` 每进程只取 1 行，一旦 `boot-unknown` 复现，陈旧聚合窗口会长期占位、批量通知被一直绑进永不结算的窗口。

**E9–E14【低】**
- `UrlPolicy.validateImage`（`NotificationPoster.kt:203-210`）放行 http，与自身注释和严格版 `NotificationChannels.kt:54-62` 不一致。
- `CrashRecorder.kt:31-53` 崩溃路径 `runBlocking` 落库**无超时** → 写锁被占时会卡住不退出（被系统强杀），建议 `withTimeoutOrNull(1000)`。
- 诊断包缺通知流水线证据（无 outbox/attempt/history 快照），`countActiveOutbox()` 零引用 → 用户报"没收到通知"时无法区分"没入队/卡 PROCESSING/投递失败"。
- 日志 `detail` 无长度上限（`MonitoringEngine.kt:546-555`、`NotificationDispatcher.kt:46-52`），诊断包全量导出 → 一次超长 message 即成倍膨胀 DB 与 zip。
- `recoveredNotified` 只写不读（死字段）。
- `TaxonomyRepository.kt:76/135` 删除静默无操作、`:123-130` assign 无审计。

**分片 E 已核对无问题**（摘要）：Outbox 抢占/回写是原子 CAS（不会双投递/旧 worker 覆盖新状态）；
notificationId 对同一 eventKey 稳定复用；渠道一定会创建；API 33 的 POST_NOTIFICATIONS 分支正确；
PendingIntent 用 `FLAG_IMMUTABLE` + URL 白名单；免打扰只抑制通知不影响状态与历史；
终态清理用 `NOT EXISTS` 保证顺序无关；冷却跨重启生效；`recoverExpiredProcessing`
一条 UPDATE 内区分 EXPIRED/RETRY_WAIT 并清 lease。

### 3.3 分片 B：持久化层（Room / DAO / 迁移 / 事务 / 备份恢复 / 清理）

**B1【严重】`DuplicateOpenSessionSignal` 被吞掉后"正常返回" → 半截事务被提交，这一段直播永久缺失** **[已复核]**
`MonitorRepository.kt:237-250` 先把 `confirmedLiveStatus` CAS 成 `transition.to`（LIVE）；
`:252-260` 把 `handleObservationSideEffects` + 标题/分区记录包在 `try/catch(DuplicateOpenSessionSignal)`
里，捕获后 `if (!applied) return@withTransaction ...`；信号来自 `:733-738` 的
`liveSessionDao.insert` 撞唯一约束（注释写"调用方重读后复用"，**但调用方只是 return**）；
`:425` 的外层 catch 同型；引擎侧 `MonitoringEngine.kt:341-342` 只记一条 app error，不重试。

Room 的 `withTransaction` **正常返回即提交**（台账 E3 记过同一语义，这里没修完）。于是已提交的部分是：
LIVE 状态、`status_history` 转换行、盲区/区间副作用、标题/分区变更行；**没提交**的是：
session、START 事件、开播通知、`noteLiveStarted`。
下一 Tick `from == to == LIVE` 不再产生 Transition → **session 永远不会被创建** ——
这一段直播在历史、统计、导出里完全不存在，界面却长期显示"直播中"，且没有任何用户可见的错误。

**修复**：信号必须穿透事务（在块内重新 `throw` 让 Room 回滚），由引擎侧重读并复用已有 OPEN session；
或让 `openNewSession` 遇到约束冲突时直接"重读 `findActiveOpenSession` 并复用"，从根上消除这个信号。
> 顺带核对了全部 16 处 `return@withTransaction`：其余位置都写在"尚未发生任何写入"之前，
> 只有 `MonitorRepository:260` 与 `:425` 出现在状态 CAS 之后，属真问题。

**B2【高】部分索引在每次 `onOpen` 无条件重建且无异常保护 → 数据一旦违反唯一性，应用永久打不开库**
`AppDatabase.kt:181-186` 的 `onOpen` 顺序执行 `PRAGMA foreign_keys` → `DbSeed.ensureSingletonRows`
→ `AppMigrations.createPartialIndexes`，**全程无 try/catch**；而 `AppMigrations.kt:40-105` 的 16 条
`CREATE UNIQUE INDEX IF NOT EXISTS ... WHERE ...` 在索引不存在时是**对既有数据的校验**
（含 `idx_gap_one_open ON monitoring_gap(scope) WHERE endTime IS NULL` 这种全局唯一）。
任一违反 → `SQLiteConstraintException` 从 `onOpen` 抛出 → **每次启动都崩在开库阶段，无修复入口**。
可达性需要"既有违规数据"（外部改库、继承的脏数据、恢复写入重复 OPEN 场次），属"一旦发生无法自愈"。
**修复**：逐条包 try/catch + 记诊断 + 提供"按 WHERE 条件去重后再建索引"的降级路径。

**B3【高】导出/备份全链路在主线程** **[与 2.1 / C4 同源]**：见 2.1。

**B4【高】容量清理：一次性绑定全部 id（超 SQLite 变量上限即永久失败）+ 不清 `reliable_monitor_interval`**
`MaintenanceRepository.kt:191-194` 用 `oldestClosedIds(excess)`（`SELECT id ... LIMIT :limit`）
再 `DELETE ... WHERE id IN (:ids)`（`MonitorDaos.kt:365-369`）。本机 SQLite 3.44.3 的
`SQLITE_MAX_VARIABLE_NUMBER = 32766`：用户把"最多保留"从"不限制"改小后 `excess` 可能远超上限 →
`too many SQL variables` 被外层 `runCatching` 吞成失败，且**每次重试同样失败**（阈值条件不变）→ 容量清理永久失效。
附带：容量清理只清 `live_event`/`live_session_correction`，**没清** `reliable_monitor_interval`
（该表无 FK），而手动删除路径 `HistoryRepository.kt:296-306` 是清的 → 留下 `sessionStableId` 悬空的区间行。
**修复**：分批删除（`take(500)` 循环）、同步清理区间表、显式夹住 `excess` 上限。

**B5【中】枚举列零容忍** **[与 2.2 同源]**：见 2.2（分片补充：本会话已实测复现该崩溃）。

**B6【中】恢复终态 CAS 返回值被丢弃** **[与 2.3 同源]**：见 2.3（分片补充：`cancelRun()` 并发取消后仍报"恢复完成"）。

**B7【中】恢复期子行写入静默失败，用户只看到数字偏小**
`BackupRepository.kt:740-753` 的 `restoredEvents++` 写在 `runCatching` **内部**，异常被吞且不写 warning；
标题（`:761`）、分区（`:774`）同样整段包在 `runCatching` 里。
例如同一 session 的重复 START 事件撞 `idx_live_event_one_start`（`AppMigrations.kt:53-56`）会被静默丢弃，
用户只看到"事件 3"，无从知道有 7 条没进去。`MaintenanceRepository.kt:289-293` 同型。
**修复**：`onFailure { warnings += ... }` 并纳入 `skippedCounts`。

**B8【中】`readLatestBackup()` 主线程 IO + 依赖 MediaStore 归属权** **[与 C12 同源]**：见 3.1/C12。

**B9【中】同一个"时间范围"在两条 SQL 里语义不同 → 统计与历史/导出对不上**
`MonitorDaos.kt:336`（统计用）：`WHERE endTime IS NOT NULL AND endTime >= :from AND endTime < :to`；
`MonitorDaos.kt:344-356`（历史页与导出用）：`COALESCE(startTime, createdAt) >= :from AND < :to`。
→ 开播在区间之前、关播在区间内的场次**被统计计入却不出现在历史/导出明细里**，反之亦然；
同一时间范围导出的历史表与统计图表可以互相矛盾。
附带：`StatsSeriesRepository.computeFor` 对越界场次 `indexOf` 返回 `-1` → 不计入任何分桶却被计入 `totals`，
同一页面的"总计"与柱状图/分桶表自相矛盾。
**修复**：`listClosedBetween` 统一为 `COALESCE(startTime, createdAt)` 口径；
分桶对"起点早于区间"的场次按起点夹取而不是丢弃。

**B10–B15【低】**
- `AppMigrations.kt:115-138`（v2→v3）：写 revision 用 JOIN 依赖当前 version 的 revision 存在，
  但随后的 `UPDATE` 无条件推进 `configVersion` → JOIN 不命中时版本与修订历史脱节。
- `ConfigRepository.kt:118-175`：先 `insertConfigRevision(newVersion)` 再 CAS，
  CAS 返回 0（VersionConflict）时 revision 已提交且 `insertConfigRevision` 是 `IGNORE`
  （`ConfigDaos.kt:50-51`）→ 后续同版本 revision 被静默跳过，修订历史不可信（**疑似**，写入被 SQLite 串行化，可达性受限）。
- `AppDatabase.kt:177/184`：`DbSeed.ensureSingletonRows(db, System.currentTimeMillis())` 用原始设备时钟，
  而全项目其余时间都走网络校准的 `AppClock` → 单例行 `updatedAt` 与业务时间轴不同源。
- **恢复/清理仍用非接管版维护锁**：导出已改 `takeOverForMaintenance`（`ExportRepository.kt:75`），
  但恢复是 `enterMaintenance(RESTORE)`（`BackupRepository.kt:587`）、清理是 `DATABASE_REPAIR`
  （`MaintenanceRepository.kt:176`）；DAO 要求"租约已过期或不存在"（`ConfigDaos.kt:184-195`），
  而引擎租约 TTL 90s、Tick 间隔 180s → **约一半时间窗口内点"执行恢复"/"立即清理"必然失败**，
  提示还是与真实原因无关的"导出/恢复/清理正在进行"。**这是今天修导出时漏掉的两条同源路径，建议一并改。**
- `NotificationOutboxWriter.kt:322` 注释仍称"三形态互斥（0.6.29 CHECK）"，但运行库
  `sqlite_master` 里 `notification_outbox` **只有 4 个 FK、没有任何 CHECK**（台账 E1 同类）；
  本次新增的 change 通知正合法依赖"三列全 NULL"。
- 全表加载：`BackupRepository.kt:266-274/322/328` 导出时六张表 `listAll()`；
  `ExportRepository.kt:98` 只为选中的场次拼分区却 `liveSessionAreaDao().listAll()` 读全表；长期使用后有 OOM/GC 抖动风险。

**分片 B 已核对无问题**（摘要）：迁移链 1→8 连续无缺口（运行库 `user_version=8` 实证跑通）；
`@ColumnInfo(defaultValue="0")` ↔ ALTER 语句 ↔ `schemas/8.json` 三处一致；
16 条部分索引的 DROP 清单与 CREATE 清单名称一一对应；v3→v4 / v6→v7 建表 DDL 与 8.json 逐字一致；
维护锁"取锁在前、finally 释放"四处对称；CAS 返回值检查普遍到位；
清理的外键顺序用 `NOT EXISTS` 守卫（与批次/顺序无关）；软删除单事务完成 7 件事且不撞唯一索引；
`sourceDataVersion` 递增点齐全。

### 3.4 分片 A：监控引擎 / 调度 / 进程生命周期

**A1【严重】前台服务 Tick 循环没有异常边界：异常即"服务假活"或崩溃循环** **[已复核]**
`MonitoringService.kt:73-87`：

```kotlin
while (currentCoroutineContext().isActive) {
    val snapshot = configRepository.getSnapshot()   // ← 在 try 之外
    if (snapshot?.monitoringEnabled != true || ...) break
    try { engine.checkOnce("fgs") } catch (e: Exception) { }
    delay(snapshot.intervalSeconds * 1000L)
}
stopSelf()                                          // ← 不在 finally
```

`getSnapshot()` 是可抛的：Room 调用 + `quietHoursRepository.current()` **按设计不吞异常**
（其 KDoc 明确要求向上暴露）。一旦抛出：循环异常退出 → **`stopSelf()` 被跳过**，
前台通知留着、`isRunning` 仍为 true → `HealthRepository.kt:109-110` 显示"监控中（实时）"，
但一个 Tick 都不会再跑；同时异常经 `lifecycleScope`（无 `CoroutineExceptionHandler`）冒泡到
默认处理器 → `CrashRecorder` 记录后终止进程。成因持久（DataStore 损坏）时就是
"每次冷启动拉起 FGS → 立刻崩"的崩溃循环。
**修复**：把 75-84 行整体纳入 try/catch（catch 内 `logAppError` + delay 后 continue），`stopSelf()` 移入 `finally`。

**A2【严重】进程死亡跨越的场次被写成"时长=整个断档"的假场次**
`MonitorRepository.kt:392-402` 关播时 `endTime = now`、`duration = (now - startTime)`；
`:495-496` 的 `lastReliableAt`、`MonitorDaos.kt:518-525` 的盲区行**只写不读**；
`StartupRecovery` 也没有"场次时长清算"。
→ 主播 09:00 开播、App 10:00 被杀、22:00 才恢复监控：观测到 OFFLINE 时会以 `endTime=22:00` 关闭，
得到 **13 小时的假场次**，并进入历史页、统计总时长/平均、HTML 导出与配置文件备份。
**修复**：关播提交前读该主播未恢复的 streamer 盲区，若 `lastReliableAt` 早于 now 超过阈值
（如 > 2×interval）则 `endTime = lastReliableAt`，`confirmedOfflineAt` 仍记 now。

**A3【高】冷启动的保留策略清理被它自己刚续上的租约挡住 → 永久静默跳过** **[已复核，与 B13 同根]**
`StartupRecovery.kt:107-112` 的顺序是"先 `runRecoveryCheck()`（跑一整轮 Tick，`ensureLease()`
写入 `leaseUntilWall = now + 90s`）→ 紧接着 `runCleanup()`"，而 `MaintenanceRepository.kt:175-176`
用的是**严格版** `enterMaintenance(DATABASE_REPAIR)`（要求租约为空或已过期，`ConfigDaos.kt:184-195`）
→ 返回 0 行 → `check{}` 抛异常 → 被 `runCatching` 吞掉。
→ 活跃用户（监控开启）的**场次保留上限、Outbox/历史/日志清理、95% 容量预警全部永不生效**；
同一原因让数据页「立即清理」在实时模式下约一半概率报与事实无关的"导出/恢复正在进行"。
**修复**：清理与恢复都改用 `takeOverForMaintenance(...)`（导出早已改），失败至少写一条 AppError。

**A4【高】聚合窗口延迟任务里的 `dispatchDue()` 没有异常保护 → 一次 DB 失败即进程崩溃**
`MonitoringEngine.kt:221-233` 的 `scope.launch { delay(...); runCatching{processWindowEnds()}; dispatchDue() }`：
第二行无保护，而 `NotificationDispatcher.kt:38-55` 的 `outboxDao.findDue(...)` 在 try 之外
（只有 for 内部逐条 try）；`appScope`（`AppModule.kt:47-50`）又没有 `CoroutineExceptionHandler`
→ `findDue` 抛 SQLiteException 时异常直达默认处理器 → 进程被杀。
**修复**：该 launch 内整体 `runCatching` + 写 AppError，或给 appScope 挂异常处理器。

**A5【高】Tick 全有或全无且没有 Tick 级预算：单个慢批次能拖住整轮提交最长约 31 分钟**
`MonitoringEngine.kt:136-164` 必须等**所有**批次返回后才统一 apply（`:166-199`）；
每批最多 6 次尝试 × `withTimeout(≤65s)` + 5 次退避 × ≤300s ≈ **31 分钟**（`ConfigRepository.kt:294-296`
注释已自认 `withTimeout` 不覆盖 delay）。期间**已成功的批次也不写入**（状态/事件/通知全部积压），
FGS 仍报"监控中"，下一 Tick 从 Tick 结束后才开始计时。
**修复**：加整体 `withTimeout(tickBudget)`，或改为"每批返回即 apply"。

**A6–A10【中】**
- **A6**：`logHealth` 的 `note` 参数未使用（`MonitoringEngine.kt:534-544`），写库时 `fromStatus=null`、
  `episodeId=null` → 诊断页只有一条"MONITORING → BLOCKED"，看不出是 `LEASE_LOST` 还是维护占用；
  而 `HealthRepository.kt:61-70` 也不消费健康事件。与仓库硬规则"任何降级都不允许静默发生"直接冲突。
- **A7**：每次冷启动固定多跑**一整轮**观察（`BiliMonitorApp.kt:45-47` → `StartupRecovery` 的
  `runRecoveryCheck` = 完整 Tick），省电模式下 WorkManager 每次唤醒又会再跑一次（`MonitoringWorker.kt:38`）
  → 请求与写放大一倍。建议恢复检查加前置条件（有未恢复崩溃 / 距上次 Tick > 2×interval）。
- **A8**：省电 Worker 丢弃 `checkOnce` 的结果与异常，只排除 `MANUAL` 而不校验是否真处于 POWER_SAVING
  （`MonitoringWorker.kt:31-40`）→ 与 FGS 并存时多跑一轮；引擎持续失败时**完全静默**。
- **A9** **[已复核]**：批次冷却在 `parallelism <= 1` 时**完全失效** ——
  `MonitoringEngine.kt:139-158` 先 `gate.release()`（143-148）再 `delay(cooldownMs)`（156-158），
  许可已释放，下一批 async 立刻 acquire 并发请求；注释宣称"串行请求时它才是不要打满上游的手段"，
  实际此时它不起作用（台账 G3"冷却占着并发槽睡觉"的修复引入了这个反效果）。
  正确做法是"上次请求结束时间 + cooldown"的全局时间闸，而不是把 delay 挪出闸外。
- **A10**：熔断按"每次 HTTP 尝试"计数（`MonitoringEngine.kt:363-374` + `RetryPolicy.kt:39-51`），
  而配置文案是"连续 N 次请求失败" → 阈值 3 时**一个批次**的 3 次重试失败就能打开全局熔断，
  其余批次直接记为 NETWORK_ERROR（配了 maxRetries 反而更快熔断）；熔断状态仅存内存，重启清零。

**A11–A15【低】**
- `startRealtime()` 前台启动失败被静默吞掉（`MonitoringController.kt:95-106`），只降级调度不留痕；
- 监控租约从不主动释放（`RuntimeLockRepository.release` 全仓库无调用者）→ 停止后 90 秒内重启的第一次 Tick 必定 `lease_lost`；
- 无障碍可用性被常驻订阅，内部每 10 秒在主线程轮询一次（`MonitoringController.kt:40-63` + `AccessibilityKeepAlive.kt:53-68`），与用户是否选择无障碍无关；
- `BootReceiver` 每次广播新建永不取消的作用域，且要在 `goAsync()` 的 ~10s 窗口内完成 DB + WorkManager 调用（`MonitoringWorker.kt:44-60`）；
- `onTransportFailure(snapshot, batchSize)` 的第二个参数零引用，两个调用点还传了语义不同的值（`MonitoringEngine.kt:433`、调用点 `118/206`）。

**分片 A 已核对无问题**（摘要）：围栏/代际失效路径（CAS 同时校验 sequence + streamerMonitorGeneration +
`EXISTS(monitorGeneration/fencingToken/maintenanceMode='OFF')`，被抢租约时逐条落日志，无静默丢弃）；
Tick 重入（四个入口全经 `tickMutex`，单进程无 `android:process`）；
熔断 `@Volatile` + `breakerMutex` 可见性；配置校验有完整上下界；维护模式泄漏有 `finally` + 启动清理；
WorkManager 按需初始化正确；前台服务接线（START_STICKY / specialUse / API<34 传 0 / 先落库再停服务）；
单主播失败隔离 `applySafely` 覆盖完整提交链路；盲区状态机无卡死路径；时钟 monotonic 且未混用 wall/elapsed。

### 3.5 分片 D：UI 层（Compose + ViewModel）

**D1【严重/高】恢复与导入链路在主线程读整份备份 + JSON 解析 + SHA-256** **[与 2.1 / C4 / B3/B8 同源]**：见 2.1。

**D2【高】「开始导入」没有忙状态与并发保护**
`LoginScreen.kt:351-354` 的按钮无 `enabled`/busy 判定，而 `startFollowImport` 每次都 `insertTask`
并起一条轮询协程（同 VM 的扫码路径有 `qrLoginJob` 幂等守卫，`135-163`）。
连点 N 次 = N 个任务 + N 路分页抓取（"全部获取"最多 17 页/人）→ 上游限流、暂存行堆积、UI 状态互相覆盖。
**修复**：进行中禁用按钮，或加同款 Job 守卫。

**D3【高】二维码位图在主线程生成**（`LoginScreen.kt:118` + `303-309`）：ZXing 编码 + 560×560
ARGB 位图（≈1.25 MB 分配）都在 `qrState.collect` 里做 → 掉帧。**修复**：`withContext(Dispatchers.Default)`。

**D4【高】`StreamerHistoryViewModel.load()` 无幂等守卫 → 重复订阅、上游永不解绑**
`HistoryScreen.kt:149-220`：每次 `load` 新建一条 `combine(...).stateIn(...).collect{}` 管线且永不结束
（`StreamerDetailScreen.kt:99-101` 有 `currentId` 守卫，这边没有）→ 配置变更后两条 Room 管线并行写同一个 `_uiState`。

**D5【高】历史二级页的提示条不参与 `combine`：自动消失失效、提示可能丢失**
`HistoryScreen.kt:215` 在 transform 里**读值**（`message.value`）而不是把它作为 flow 输入，
`clearMessage()`（`:271`）只改内部 flow 不触发重发 → 不伴随 DB 变更的提示（补录参数非法、修正冲突、
删除 0 行）在 2.5s 后不会消失，会一直挂到下一次监控 Tick 写库（可能数分钟）。
**修复**：把 `message` 作为 combine 输入（嵌套保持 ≤5 路），或让 `clearMessage` 直写 `_uiState`。

**D6【高】标签/分组 chips 用 `Row` 且无横向滚动 → "+ 添加"被挤出屏幕后不可达**
`HomeScreen.kt:183-221`、`StreamerDetailScreen.kt:446-454/510-518`：标签/分组数量无上限，
条目一多后面被挤出屏幕且**无法滚动**，等于把"添加标签/加入分组"入口锁死。**修复**：`LazyRow`/`horizontalScroll`。

**D7【高】全站 `collectAsState()` 而非 `collectAsStateWithLifecycle()`（23 处）**
VM 全部 `stateIn(..., WhileSubscribed(5_000), ...)`，但订阅挂在**组合**而不是**生命周期**上
→ Activity 进后台后 Room 查询与 combine 变换（含 `observeAll` 全表、1000 行分组）继续跑，耗电。
依赖已具备（`app/build.gradle.kts:110`）。**修复**：机械替换。

**D8–D14【中】**
- **D8**：详情页"主播不存在"永远转圈（`StreamerDetailScreen.kt:295-309`，状态里没有 loading 字段）；
  通知深链指向已删除主播（`AppNavHost.kt:91-92`）就会进入这个状态。
- **D9**：`showGroupDialog`/`showTagDialog` 的 `remember` 声明在 **LazyColumn 的 item 内**（`440-441/504-505`）
  → item 被回收时对话框凭空消失、输入丢失。修复：提到 Screen 顶层（与删除/改名对话框一致）。
- **D10**：详情页收藏切换没有异常保护（`StreamerDetailScreen.kt:158-161`），仓储抛错即崩；`HomeViewModel` 有 `runCatching`。
- **D11**：历史二级页每次键入都重跑整条管线，且 `titleDao/areaDao.observeAll()` 是**无 WHERE/LIMIT 的全表读**
  （`HistoryScreen.kt:154-155`）；变更表只增不减 → 成本随时间线性变差。修复：按会话过滤 + 搜索 debounce。
- **D12**：`startTimeZone/endTimeZone` **只写不读**（写入 `MonitorRepository.kt:400/730`，显示一律
  `ZoneId.systemDefault()`，`HomeScreen.kt:465-469`），而迁移注释写明"NULL 才回退设备时区" → 跨时区后整体偏移。
- **D13**：导入对话框的「取消」并不取消任务（`LoginScreen.kt:637` 只关弹窗），后台继续翻页抓取最长 90s；
  再点导入会出现两个任务同时写 staging。建议改文案或实现真取消。
- **D14**：通知历史的 `eventLabel` 只认 `evt:/agg:/problem:`（`NotificationHistoryScreen.kt:98-104`），
  本次新增的 `change:TITLE:…`/`change:AREA:…` 落到 `else -> "通知"`；且列表无 loading 标志，进页面先闪"暂无通知记录"。

**D15【低】**：`StreamerDetailScreen.kt:636` 死状态 `showEditor`；`SettingsViewModel.kt:97` 零调用的 `done()`；
`HistoryListScreen.kt:126-128` 单流程 `combine` 包装；首页批量删除无二次确认（详情页单条删除有）；
深色模式下 `StatusColors` 固定色的对比度**疑似**偏低。

**分片 D 已核对无问题**（摘要）：`combine` 全部 ≤5 路或已显式嵌套（无 6 路退化成 `Array<Any?>` 的用法）；
"永远转圈"只在详情页 not-found 一处；9 个页面都有反馈渲染点；对话框关闭时都显式复位；
`IconButton` 均带 `contentDescription`；底部导航 `popUpTo/launchSingleTop/restoreState` 正确；
`formatTime/formatDuration` 对 null 返回 "—"，卡片文本都做了 `maxLines+Ellipsis`。



---

## 4. 建议的修复顺序

**第 0 批：会导致"数据错/应用打不开"的**
1. **B1**（`DuplicateOpenSessionSignal` → 半截事务提交）—— 唯一一条"严重"，且修复方式很局部（catch 内重新 throw 或改为复用已有 OPEN session）；
2. **2.2 / B5**（枚举容错）—— 已实测复现过的崩溃；
3. **2.4**（库损坏自愈）+ **B2**（部分索引重建失败即永久打不开库）—— 这两条合起来才是"数据库层能不能自愈"；
4. **A2**（进程死亡跨场次被写成 13 小时假场次）—— 数据错误会进入历史/统计/导出/备份；
5. **A3 / B13**（StartupRecovery 的清理被自己刚续的租约挡住 + 恢复/清理仍用非接管版维护锁）—— 同一条根因，两行改动；
6. **C2**（`live_status` 缺省 0 被当成"未开播"）—— 会让**全部主播同时被确认关播**并批量误报。

**第 1 批：能不能用**
7. **C1**（WBI 密钥不刷新 → 跨天导入全废）—— 一行判断；
8. **2.1 / C4 / B3 / D1**（仓库层切 IO）—— 一条统一约定，受益面最大；
9. **2.5 / C3**（备份排除规则 + 凭证库重建）—— 换机/恢复场景的启动崩溃；
10. **A1**（FGS Tick 循环没有异常边界）+ **A4**（延迟任务里 `dispatchDue` 无保护）—— 崩溃循环与服务假活；
11. **E1**（PROCESSING 卡死无运行期回收）+ **E2**（三处不在事务内）+ **E3**（投递占用 Tick 锁）。

**第 2 批：一致性与可观测性**
12. **B9**（统计与历史/导出的时间口径不一致）+ **C8**（恒为 0 的"已排除场次"）；
13. **A5**（Tick 全有或全无、最坏 31 分钟）+ **A9**（串行时冷却失效）；
14. **2.3 / B6**（恢复失败落 FAILED + CAS 检查）+ **B7**（恢复子行静默失败）；
15. **2.6 / 2.7 / A6 / A8 / A11**（失败留痕、WorkManager 网络约束、健康事件丢原因、Worker 结果被丢弃、降级不留痕）；
16. **C9**（解析失败被当成网络失败）+ **C10**（假失败）+ **C12 / B8**（"看不到"≠"不存在"）。

**第 3 批：收尾**
17. **D2/D3/D4/D5/D6/D7**（UI 并发点击、位图主线程、重复订阅、提示条不参与 combine、chips 被挤出屏幕、`collectAsStateWithLifecycle`）；
18. **E4–E8**（诊断包迁移清单停在 5→6、封面解码采样、过期通知、死清理、bootId）；
19. **2.8 / 2.9 / 2.10 / C13–C15 / B10–B12 / B14–B15 / D15**（`!!` 两处、测试覆盖、lint 闸门、死代码与陷阱常量、注释与 DDL 不符、全表加载、死状态）。

