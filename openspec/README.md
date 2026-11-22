# 主播监控：现状基线与设计依据

初始基线对应提交 `b147c69` 的业务代码（`0622.4` / Room v12）。`0622.5` 封禁历史与开发树名称刷新分别使用过不同的 v13；`0622.6` 的观察时钟库为 v14。`0622.7` / code 10 将两条路径并入 v15。这里的“已实现”指能找到实际执行路径，不表示所有设备均已验证。复现方法见[编译、测试与验收](verification.md)。

## 从哪份规范开始

源码入口均相对于 [Kotlin 源码根目录](../app/src/main/java/com/example/bilimonitor/)。先看场景，再沿入口查调用者和测试。

| 已实现能力 | 主要执行入口 | 可核对的测试或清单 |
| --- | --- | --- |
| [主播管理与可选账号导入](specs/streamer-management/spec.md) | `ui/home`、`ui/detail`、`ui/login`；`StreamerRepository`、`TaxonomyRepository`、`AuthRepository`、`FollowImportRepository` | `ParseStreamerInputTest`；登录与去重须用可授权的测试账号验收 |
| [直播观察、确认与场次](specs/monitor-state/spec.md) | `BiliLiveApiDataSource` → `MonitoringEngine` → `StateConfirmationPolicy` → `MonitorRepository` | `StateConfirmationPolicyTest`、`PendingTransitionGenerationTest`、`SessionEndTruncationTest`、`RoomBanPolicyTest` |
| [调度、保活与启动恢复](specs/background-runtime/spec.md) | `MonitoringController`、`MonitoringService`、`MonitoringWorker`、`KeepAliveHeartbeat`、`StartupRecovery` | `MonitoringCadencePolicyTest`、`KeepAliveCommandsTest`；锁屏、权限与 ROM 见真机清单 |
| [通知投递与点击](specs/notification-delivery/spec.md) | `NotificationOutboxWriter`、`NotificationDeliveryCoordinator`、`NotificationDeliveryGuard`、`NotificationDispatcher`、`NotificationPoster`、`NotificationDeepLinkResolver` | `NotificationTimelinessPolicyTest`、`NotificationAggregationInstrumentedTest`、`NotificationNavigationInstrumentedTest` |
| [场次历史与统计](specs/history-statistics/spec.md) | `ui/history`、`ui/stats`；`HistoryRepository`、`StatsRepository`、`StatsSeriesRepository` | `NotificationAggregationInstrumentedTest` 中的旧历史检索与备注回归；跨午夜分桶可按验收表复核 |
| [报告、业务备份与恢复](specs/data-portability/spec.md) | `ExportRepository`、`ExportNaming`、`BackupRepository`、`ui/data/DataScreen` | `ExportNamingTest`、`BackupChecksumTest`；恢复后调度切换见 0622.4 清单 |
| [诊断与数据维护](specs/diagnostics-maintenance/spec.md) | `HealthRepository`、`DiagnosticExporter`、`MaintenanceRepository`、`MaintenanceWorker` | `DiagnosticExporterTest`；真库测试覆盖维护令牌、监控人数与审计保留 |
| [外观与应用入口](specs/appearance-navigation/spec.md) | `AppNavHost`、`AppearanceSettingsRepository`、`IconPresetSwitcher`、`LauncherEntryRescueReceiver` | `CropMathTest`、`ColorHexTest`、`BackgroundRegionMathTest`；启动器刷新与渲染需设备验收 |

## 架构与已作出的决定

- **单个 Android 应用，不依赖自有服务端。** `:app` 使用 Kotlin、Compose/Material 3、Navigation、ViewModel 与 Flow。`BiliMonitorApp` 是进程入口，`MainActivity` 装配 UI；`di/AppModule.kt` 用 Hilt 装配 Room、仓储、OkHttp/Retrofit 与应用级协程。直播接口和账号接口分开；登录仅服务于可选导入，不分割本地监控空间。
- **一次观察不是一次状态转换。** 实时服务、周期 Worker 和手动刷新共用引擎。引擎按配置快照和各主播的到期时间分批，限制并发、超时与重试；`domain/policy` 判定方向、计数、故障归因与新鲜度，仓储决定是否提交。失败比例按本轮实际检查的主播去重；可选熔断器按连续失败批次及恢复窗口另行控制暂停。一个房间出问题不等于全局断网。
- **写入由数据库裁决。** [监控仓储](../app/src/main/java/com/example/bilimonitor/data/repository/MonitorRepository.kt)把确认状态、场次、可靠区间、事件及合格通知的入队操作置于同一 Room 事务。观察 CAS 检查序号递增、监控启用、全局代际、围栏令牌与非维护状态；挂起计数另按主播和全局代际过滤。CAS 未命中时不继续写副作用。主动维护与定时清理的抢占策略不同，取消时按所有者令牌释放，避免监控被永久停住。
- **一个事实源，多种派生视图。** 主播、场次、事件、修正、盲区在 Room；统计快照和缓存可按 `sourceDataVersion` 重建，查询键还区分时区、范围及口径。历史导出先把所选行固化为快照，再渲染文件；不是在整个文件写出期间保持读事务。DataStore 保存外观、提醒有效期等偏好，Keystore 支持的 `EncryptedSharedPreferences` 保存登录凭证；业务备份不复制这些存储文件。
- **并发不靠界面按钮或进程锁兜底。** [数据库](../app/src/main/java/com/example/bilimonitor/data/local/db/AppDatabase.kt)使用 WAL、外键、忙等待；[迁移](../app/src/main/java/com/example/bilimonitor/data/local/db/AppMigrations.kt)维护 1→15 显式链及部分唯一索引，例如每主播至多一个活跃未结束场次、每个源事件至多一个对应单条出站项。新装／开库也检查索引；仓内导出的 schema 从 v2 到 v15，不代表每条升级路径均已实测。
- **两套时间不可混用。** `AppClock` 提供可校准墙钟、单调时钟和 `bootId`。界面与业务时间用墙钟；同次开机的轮询节拍、租约和通知期限优先用单调时间，遗留行无锚点或跨开机时通知时间线回退墙钟。记录开播或关播时间不等于获知平台真实发生时刻。
- **事件、队列和展示相分离。** 事件是业务事实，Outbox 负责可重试投递，History 记录处理结果；系统通知 ID 由注册表分配。投递协调器不等待整轮巡检或封面下载。开播与下播各自开窗口，避免把意义相反的通知合成一条；只有当前连续可靠区间有资格生成正常关播提醒。
- **保活报告实际效果，不承诺持续在线。** `specialUse` 前台服务、周期工作、心跳、开机／更新恢复与无障碍重绑是不同入口；系统拒绝、撤权和强行停止不等于普通进程回收。高级保活可选，检查实际生效项；不采集无障碍内容，不以悬浮窗作为保活前提。

## 容易从旧文档读错的现状

| 问题 | 当前依据与结论 |
| --- | --- |
| “默认 180 秒、关播确认 2 次”？ | 见 `DbSeed.ensureSingletonRows`：新库是 `POWER_SAVING`、保活选择 `UNSET`、配置间隔 **60 秒**、开／关播确认各 **1 次**。省电实际下限 **15 分钟**。种子只插入缺失行，不用新默认覆盖老库；引导后的用户选择再决定模式。 |
| “三种引导选项，选无障碍就等于常驻服务”？ | `OnboardingScreen` 有四种选择（含“双保险”）；`UNSET` 是未选择，不是第五个按钮。`completeOnboarding` 会把无障碍单选记作实时意图，但 `syncWithConfig` 的常驻分支只接受前台通知或双选，其余走周期工作；重绑又可触发 `ensureRunning`。无障碍单选在特定 ROM 的最终运行状态**待确认**，不能从选择值推断。 |
| “点批量通知去第一个主播”？ | 以 `NotificationRoutingPolicy`、`AppNavHost` 和 0622.3 清单为准：先看批次再选成员。默认窗口 5 秒、阈值 4，超过阈值才合并。默认有效期为开播 5 分钟、下播 10 分钟、标题／分区变化 3 分钟；可配置，不是送达时限。 |
| “诊断 ZIP 固定六个分片，设备测试只有一个”？ | 当前 `DiagnosticExporter.export` 写出七个命名 JSON，含 `notification_pipeline.json`；区分实现文件数与测试检查的必需子集。当前有三份设备测试源文件；发布验证数字见[验收指南](verification.md)，不是本次运行结果。 |
| “打开统计图就是读快照缓存”？ | `StatsRepository.compute` 有按数据版本读写快照／缓存的实现；当前 `StatsScreen` 和 `ExportRepository.exportStats` 调用的却是 `StatsSeriesRepository`。不可把前者的缓存命中当作页面性能保证。 |
| “本地优先意味着系统不能备份数据库”？ | 应用没有自己的云端同步服务。另一个边界是 Android 系统备份：清单允许备份，[系统提取规则](../app/src/main/res/xml/data_extraction_rules.xml)在云备份及设备迁移中排除凭证偏好文件，没有排除整个数据库。系统备份与应用导出的业务 JSON 是两条路径。 |

## 未实现、范围外与待确认

以下不是 `specs/` 中的现行承诺。差异不自动等于缺陷或开发排期。

| 状态 | 原需求或方向 | 已有边界／确认办法 |
| --- | --- | --- |
| 部分实现 | v2.4 §0.6.39 定义全量、单主播、选定主播、日期范围备份 | `BackupRepository.serializeBackup` 固定生成 `ALL_DATA`。`BackupBody` 无盲区、通知业务历史等原设计字段；HTML 能选范围不代表 JSON 能按范围备份。完整业务闭包是否仍为目标**待确认**。 |
| 部分实现 | v2.4 §0.6.40 的逐冲突裁决；§0.6.33 的恢复生命周期 | 有预检、冲突表和摘要，界面只提供批量三种操作；不能将 `MERGE` 枚举视为逐项合并功能。启动时将中断的 `APPLYING` 标为失败，非自动续跑。若要支持逐项／续跑，需另定冲突样本与恢复验收。 |
| 部分实现 | v2.4 §0.6.41 要求所有页面只以 `stableId` 导航 | 备份、场次目标使用稳定身份，但 `AppNavHost` 中详情、历史、统计的主播参数仍是本地 `Long streamerId`。是否迁移站内路由**待确认**；不能把本机行号当作跨设备身份。 |
| 尚未实现 | 超大历史库的端到端流式导出 | 历史报告有一致性快照，但仍拼装完整 HTML；JSON 有 64 MiB 上限，构造业务对象不等于逐表流式读取。需用大库记录峰值内存和输出一致性。 |
| 尚未实现／非当前承诺 | 数据库损坏自动重建、按主播热度自适应巡检、卡片实时折射 | 当前有 `quick_check` 检测与上报、人工配置的间隔、Dock 取景与卡片外观；不是自愈、热度算法或所有卡片实时取景。是否采用见[项目总结](../PROJECT_SUMMARY.md)的后续方向，须另行裁决。 |
| 明确范围外 | 实时多人／多设备同步、独立多账号空间、服务端推送 | v2.4 项目定位及现有单机架构不作这些承诺；换机使用文件恢复，不等价于同步。 |
| 待确认（验证缺口） | 全链迁移、恢复幂等及回滚、设备保活效果 | v2–v13 建库到 v14 的 schema 矩阵，以及带数据的 v12→v13→v14、v12→v14 覆盖安装见[本轮验收](../docs/reviews/2026-11-20/closure.md)。合成旧库不能代替所有客户数据；仓内无独立 v1 样本。Root／Shizuku、厂商后台清理、客户端跳转和整夜功耗需要设备及实测记录，无统一时延／耗电保证。 |

## 证据的用途

- [v2.4 设计原文](../docs/doc_v2.4.md)与[分析报告](../docs/doc_v2.4_分析报告.md)用于找目标与冲突。原文 §0.6 优先于历史附录；实体数量、健康枚举及日志定义等历史冲突不可直接作为当前建表依据。
- [缺陷台账](../docs/DEFECT_LEDGER.md)用于追溯裁决与修复，尤其 H77–H79；[0622.3](../docs/TEST_CHECKLIST_0622.3.md)、[0622.4](../docs/TEST_CHECKLIST_0622.4.md)清单用于重做验收。不要把空结果栏补写成通过。
- [代码审查](../docs/CODE_REVIEW_REPORT.md)、[静态审查](../docs/STATIC_REVIEW_REPORT.md)和 [UI 分析](../docs/UI_LAYER_ANALYSIS.md)是历史线索；须回到当前源码及回归记录确认，不直接作为未修缺陷清单。
