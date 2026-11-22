# 主播监控代码审查与测试报告（2026-11-20）

**结论：不建议把此次工作树直接作为新版发布。** 现有 JVM 测试和 Android 设备测试全部通过，但另外 12 项定向验证中有 11 项业务断言失败，另 1 项迁移矩阵通过。优先处理四类错误：只改备注会删除分区历史；断网后会高估直播时长；暂停前发出的请求在恢复后仍可创建场次；选择跳过备份冲突仍会覆盖另一 UID 的通知设置。发布前还需提升应用版本号，防止旧包覆盖已升级的数据库。

审查对象是 `main` 提交 `b147c69c32bc` **加上当时的本地改动和未跟踪文件**，包括名称刷新和 Room v13，不是只看提交差异。依据为 [八项 OpenSpec 能力](../../../openspec/README.md)、[名称刷新差异](../../../openspec/changes/schedule-streamer-name-refresh/specs/streamer-management/spec.md)、[v2.4 §0.6 最终契约](../../doc_v2.4.md) 和[缺陷台账](../../DEFECT_LEDGER.md)。此次未修改业务源码、原测试、schema、规范或历史验收记录，也没有提交 Git 变更。

## 已复现：优先修复

| 优先级 | 触发条件和实际结果 | 根因、修复与验收 |
|---|---|---|
| P1 · 只改备注丢失历史 | 一场直播先后有分区 A、B。在历史页只改备注，保存成功，但分区时间线从 **`[A,B]` 变成 `[A]`**。操作没有要求用户确认删除。 | [编辑框](../../../app/src/main/java/com/example/bilimonitor/ui/history/HistoryScreen.kt#L953)回传预填的 A；[仓储](../../../app/src/main/java/com/example/bilimonitor/data/repository/HistoryRepository.kt#L146)算出了实际变化，却用原始 `correction.area` 删除并重建所有分区。只对确实修改的字段写库；只改或清空备注后，逐条核对原分区及观察时间不变。 |
| P1 · 盲区被计入直播时长 | 最后一次确认在播后断网；四小时后网络恢复，连续两次观察到下播，达到关播阈值 2。场次的结束时间比最后一次确认在播**晚 14,520 秒（4 小时 2 分）**。这会污染历史和按场次时长导出的统计。 | 第一次下播观察还在计数，[却已把 `lastConfirmedAt` 更新为当前时间](../../../app/src/main/java/com/example/bilimonitor/data/repository/MonitorRepository.kt#L239)；第二次关播[把该字段当成最后见到在播的时间](../../../app/src/main/java/com/example/bilimonitor/data/repository/MonitorRepository.kt#L1016)。分开保存“最后有效观察”和“最后可靠在播”，分别验收阈值 1、2、3，以及中途一次失败的序列。 |
| P1 · 暂停前的请求可在恢复后开播 | 旧请求发出时主播代际为 1；同一全局租约下暂停再恢复，主播代际变成 3；交回旧开播观察。预期仍为 `OFFLINE`、0 个场次，**实际为 `LIVE`、1 个新场次**。一直保持暂停与暂停后又恢复是两个不同场景。 | [引擎](../../../app/src/main/java/com/example/bilimonitor/background/MonitoringEngine.kt#L1434)在收到响应后才重读主播并生成序号和代际；[观察 CAS](../../../app/src/main/java/com/example/bilimonitor/data/local/dao/MonitorDaos.kt#L76)只要求当下仍启用、序号递增和全局写权有效。请求出发前保存每个 UID 的代际和观察顺序，在观察、挂起计数及状态提交入口校验；失配时场次、事件和队列均不得写入。 |
| P1 · “全部跳过”仍改写不同 UID 的策略 | 备份和本地主播 `stableId` 相同、UID 不同，本地开播提醒设为关。选择“全部跳过”后，恢复摘要确实报跳过 1 位，**本地提醒却从关变成开**。 | 场次使用获准主播集合，而[策略恢复](../../../app/src/main/java/com/example/bilimonitor/data/repository/BackupRepository.kt#L1154)使用的是[全库主播映射](../../../app/src/main/java/com/example/bilimonitor/data/repository/BackupRepository.kt#L930)。事件、标签和分组关联也直接查这份映射，未把这些子项的影响混算为已复现。为每个备份身份生成唯一裁决映射；跳过后逐表比对本地策略、关联和事件均不变。 |
| 发布阻断 · 旧包可以覆盖新库 | 同签名 v12 → v13 覆盖安装成功。反向仅用 `adb install -r` 再装旧 APK，**未加降级参数，系统也允许安装**；旧程序打开原库时报 `A migration from 13 to 12 was required but not found`。重装本次 APK 后仍能读回样本，未观察到数据被删除。 | [应用版本](../../../app/build.gradle.kts#L21)仍和旧包相同（`0622.4` / `versionCode 7`），[Room 已是 13](../../../app/src/main/java/com/example/bilimonitor/data/local/db/AppDatabase.kt#L160)。新发布包须递增 `versionCode` 并同步版本说明；验证正常升级保留数据、普通安装拒绝旧版。**不要**通过清库或破坏性降级解决。 |

## 已复现：其他缺陷

以下各项均在 API 35 模拟器调用当前生产代码得到；证据不是仅凭注释或源码搜索推断。P2 表示条件较窄或影响局部功能，不代表结果正确。

| 优先级 | 发现与实测 | 修复方向 |
|---|---|---|
| P2 · 通知回拨后无故等待 | 已准备好投递的开播提醒，在同次开机仅经过 10 秒、墙钟回拨 1 小时且刚刚重新确认在播后，从 `Ready` 变成 `AwaitObservation`。提醒可能等到过期。 | [投递守卫](../../../app/src/main/java/com/example/bilimonitor/background/NotificationDeliveryGuard.kt#L121)混用了入队时锚定的时间轴和新观察的墙钟。为观察新鲜度保存同次开机可比较的时间或观察序号；跨开机单独回退。 |
| P2 · 没裁决就执行恢复 | 同一稳定身份对应不同 UID，预检返回一条未决冲突；没选择任何处理方式便执行，任务从 `WAITING_DECISIONS` 直接到 **`COMPLETED`**。这与 §0.6.33.1 明确要求拒绝未决恢复不符，与是否提供逐项裁决无关。 | [仓储只记警告并默认跳过](../../../app/src/main/java/com/example/bilimonitor/data/repository/BackupRepository.kt#L778)，[界面按钮仅检查忙碌状态](../../../app/src/main/java/com/example/bilimonitor/ui/data/DataScreen.kt#L344)。在事务内检查未决数，界面同步禁用，拒绝后保留可继续决策的预检。 |
| P2 · 取消的恢复任务能重新执行 | 通过仓储取消后，用同一个 `restoreRunId` 再次调用执行，终态从 **`CANCELLED` 变成 `COMPLETED`**。普通取消按钮会收起预检；本项验证的是仓储对迟到调用的保护，不是声称按钮可连续点击。 | [CAS 使用刚读出的任意当前状态](../../../app/src/main/java/com/example/bilimonitor/data/repository/BackupRepository.kt#L741)。显式列出允许进入 `APPLYING` 的前态；已取消、失败、完成、执行中的运行均不得再次取得执行权。 |
| P2 · 首次在播建场遗漏区间关联 | `UNKNOWN → LIVE` 后，区间指向新场次，但场次的 `currentReliableIntervalId` 为 **`null`**。不是所有开播/关播通知都因此失败；已证实的是同一事务的两边记录不一致。 | [插入场次时忽略自增行号](../../../app/src/main/java/com/example/bilimonitor/data/repository/MonitorRepository.kt#L1183)，返回的实体 `id` 仍是 0，随后[按它关联区间](../../../app/src/main/java/com/example/bilimonitor/data/repository/MonitorRepository.kt#L492)。使用 DAO 返回的真实主键，并检查条件更新的行数。 |
| P2 · 恢复在播事件没有关联到新场次 | 本地最后状态为 `LIVE`、但没有可复用场次；执行恢复确认，新常规场次已建，恢复区间与 `LIVE_RECONFIRMED` 事件的 `sessionStableId` **同时为 `null`**。按新场次查询会遗漏这条恢复事件。 | [恢复分支](../../../app/src/main/java/com/example/bilimonitor/data/repository/MonitorRepository.kt#L393)先用空场次写区间，建场后又沿用旧变量。先取得复用或新建的最终场次，再统一关联区间、事件及通知载荷。 |
| P2 · 人工补录被算作可靠监控时长 | 只人工补录 1 小时，没有任何可靠监控区间；统计序列仍给出 **`reliableMonitoredSeconds = 3600`**，[HTML](../../../app/src/main/java/com/example/bilimonitor/data/repository/ExportRepository.kt#L538)将其显示为“可靠监控时长”。 | [实际查询路径](../../../app/src/main/java/com/example/bilimonitor/data/repository/StatsSeriesRepository.kt#L240)把人工修正标志当作连续监控覆盖证明；§0.6.17 要求区分两者。按区间覆盖计算，或没有覆盖证据时明确标为未知；不能仅把 `CORRECTED` 改成另一个可信度枚举。 |
| P2 · 单主播统计默认显示别人的日期 | 甲、乙分别在相隔 3 天的日期各有一场；打开甲的统计，实际 ViewModel 默认选择乙的日期、显示 **0 场**，不是甲唯一有记录的日期。没有把乙的数据泄露进甲的计数，错的是可选日期和默认选择。 | [日期查询](../../../app/src/main/java/com/example/bilimonitor/data/repository/StatsSeriesRepository.kt#L79)全库读场次，[页面](../../../app/src/main/java/com/example/bilimonitor/ui/stats/StatsScreen.kt#L143)默认取其中最新项。按当前主播在 DAO 取桶；用两个主播、两个日期验收，同时避免每次切粒度全表载入。 |

## 静态发现与文案

- **P1 候选：退出账号与导入提交之间有竞态。** [关注导入](../../../app/src/main/java/com/example/bilimonitor/data/repository/FollowImportRepository.kt#L763)先读登录代际，之后更新任务、读暂存名单和占位配置，[最后才进入主播写入事务](../../../app/src/main/java/com/example/bilimonitor/data/repository/FollowImportRepository.kt#L775)。如果退出恰在这两步之间提交，写入前没有第二道代际条件。另外，已经检测出过代时仓储返回三个零，[界面仍会显示“导入完成”](../../../app/src/main/java/com/example/bilimonitor/ui/login/LoginScreen.kt#L448)。建议把代际、任务前态和业务写入放进同一事务；取消返回明确结果。**未做真实账号或并发时序实测**，不要和已复现项混计。
- **P3：首次引导误述常驻服务的时长限制。** API 35 引导实际显示“这类前台服务有累计运行时长限制，到达上限后会被系统停止”（[文案](../../../app/src/main/java/com/example/bilimonitor/ui/onboarding/OnboardingScreen.kt#L128)）。本应用[声明 `specialUse`](../../../app/src/main/AndroidManifest.xml#L129)，[启动也传入该类型](../../../app/src/main/java/com/example/bilimonitor/background/MonitoringService.kt#L470)；Android 官方 [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) 所述 Android 15 累计 6 小时额度针对 `dataSync` 和 `mediaProcessing`。应提示后台仍可能受系统和厂商限制，不应拿其他类型的强制时限解释本服务。未做六小时持续运行实验。
- **名称刷新有一处验收口径需明确。** [场景](../../../openspec/changes/schedule-streamer-name-refresh/specs/streamer-management/spec.md#L35)写“跳过人工锁名和软删除项”并显示跳过数；[查询](../../../app/src/main/java/com/example/bilimonitor/data/local/dao/NameTarget.kt#L10)先排除软删除，[既有设备测试](../../../app/src/androidTest/java/com/example/bilimonitor/data/repository/StreamerNamesInstrumentedTest.kt#L55)中 1 个锁名加 1 个软删除只计跳过 1。没有向这些主播发请求是正确的；应明确是否只统计未删除名单，避免把计数差异误作请求错误。

## 本次测试与复现

[设备验收摘录](device-evidence.txt)保留了原设备套件计数、每项定向断言的实际返回值、升级后的数据库读数及 UI 冒烟结果。

环境：Windows、Microsoft OpenJDK 17.0.19、仓库 Gradle Wrapper 8.11.1、Android SDK 35；专用 `BiliMonitor_Test` 只读 AVD，Android 15 / API 35。使用合成样本，不使用个人账号，不变更原 AVD 数据。原项目有 136 份生产 Kotlin 源文件、37 份 JVM 测试源文件和 4 份设备测试源文件；下表数字是实际执行用例数，不是文件数或覆盖率。

| 层次 | 实际操作 | 本次结果 |
|---|---|---|
| 规范与工作区 | `openspec list --specs`；`openspec validate --all --strict --no-interactive`；`git -c core.whitespace=cr-at-eol diff --check` | 8 项能力、1 项在途变更，严格校验 9/9 通过。语法校验不代表业务场景通过。 |
| 构建与 Debug JVM | `gradlew.bat --offline --continue :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --rerun :app:lintDebug :app:assembleRelease` | 两个 Debug APK 就绪；强制重跑的 JVM **373/373**，0 错误、失败或跳过。这条组合命令整体初次失败：本机缺少离线 Lint 依赖，未把失败伪作通过。 |
| Release、Lint 与设备 | 允许下载构建依赖，运行 `gradlew.bat --continue :app:lintDebug :app:assembleRelease :app:connectedDebugAndroidTest`；另在同一 AVD 用 `adb shell am instrument -w -r` 直接运行原测试包 | 联网重跑成功，生成**未签名** Release APK。Gradle 设备 XML **39/39**、0 失败/跳过；直接运行同一套亦为 39/39，不重复计数。 |
| Release JVM、Lint | `gradlew.bat --offline --continue :app:testReleaseUnitTest --rerun :app:lintRelease` | Release JVM **373/373**，0 错误、失败或跳过；和 Debug 基本为同一套断言，不宣称是 746 个独立用例。 |
| Lint XML 实读 | 两个变体的 `app/build/reports/lint-results-{debug,release}.xml` | Debug **2 error / 205 warning / 3 information**；Release **2 / 164 / 3**。两个 error 都是 `MissingPermission`、`ProduceStateDoesNotAssignValue`。[权限包装检查](../../../app/src/main/java/com/example/bilimonitor/notify/NotificationPoster.kt#L311)和[通知调用异常兜底](../../../app/src/main/java/com/example/bilimonitor/background/LauncherEntryRescueReceiver.kt#L381)均存在；[计时器](../../../app/src/main/java/com/example/bilimonitor/ui/home/HomeScreen.kt#L109)也确实给 `value` 赋值。未据此宣称通知崩溃或首页不刷新。当前 [abortOnError=false](../../../app/build.gradle.kts#L98)，应消除或有依据地局部处置误报，再为新增高置信度错误设门禁。 |
| 定向 Room / ViewModel 验证 | 在工程副本添加[回归断言补丁](room-regressions.patch)，`gradlew.bat --offline :app:assembleDebugAndroidTest`，仅替换测试 APK，设备仍运行原工作树的生产 Debug APK | **12 项中 1 项通过、11 项失败**。11 项均为预期业务值与实际值不同的断言，不是构建或夹具初始化失败。通过项对仓内 **v2–v12 的 11 份结构快照**逐一建空库并走真实 Room 迁移至 v13：版本、16 个部分索引数量、外键检查均通过。v1 没有导出的快照；此项不证明所有历史用户数据都已覆盖。 |
| 安装、升级与回退 | 从 `git archive b147c69` 单独构建 v12 Debug APK；全新安装、建立人工锁名样本，再用 `adb install -r` 升级；最后验证同版本号旧包反向覆盖并装回新版 | v12→v13 后样本名、人工锁和观察序号 8 保留，新名称尝试时间为 `NULL`，默认间隔 24 小时/1 分钟，16 个部分索引，`PRAGMA foreign_key_check` 零行。同版本旧包可安装但打不开库，装回 v13 后原样本仍在。 |
| UI 冒烟 | 模拟器完成“不启用保活”风险确认，拒绝通知权限，依次打开主播、历史、统计和设置 | 不登录可进入四个页面，升级样本可见；数据库记录 `POWER_SAVING / NO_GUARANTEE`。这不等于通知已实际送达或厂商系统下可整夜保活。 |

**重复定向验证：** 先复制本次完整工作树（包括未跟踪的 v13 文件），在副本根目录执行以下 PowerShell。补丁只添加测试，断言的是应有行为；不把失败测试混入项目原有的 39 项设备测试。修复后这些断言应转绿。

```powershell
$patch = 'docs/reviews/2026-11-20/room-regressions.patch'
git apply --check $patch
git apply $patch
$fixture = 'com.example.bilimonitor.data.repository.NotificationAggregationInstrumentedTest'
$tests = (Select-String -Path $patch -Pattern '^\+\s*@Test fun (audit\w+)\(').Matches |
    ForEach-Object { "$fixture#$($_.Groups[1].Value)" }
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$($tests -join ',')"
```

## 边界与下一步

- 已沿主播管理/名称刷新、监控/后台、通知、历史/统计、备份/维护、诊断和外观入口检查主要调用链，编译和 Lint 覆盖整个应用。没有采集逐行覆盖率，也没有把全工程检查等同于每个分支、每种 UI 或每款手机均已验证。
- 未测试实体手机、API 31–34、厂商后台清理、整夜锁屏功耗、Root/Shizuku、真实账号导入或真实 B 站客户端跳转，也未做 64 MiB 临界大库的内存压测。已有记录提到的前台启动超时没有在本轮得到可归因的复现，不列为已证实缺陷。
- 建议先为四个 P1 修复保留本报告中的正常行为断言，再补上账号代际竞争和合格发布版本号的回归；之后复测迁移、历史导出及 API 31/35 设备。不能用历史发布测试或“构建成功”代替这些验收。
