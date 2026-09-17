# 哔哩哔哩主播监控软件

## Android 项目代码设计文档 / AI 开发规格说明

> **v2.4 最终实现全覆盖与代码生成约束版**：在 v2.1 的规范重排基础上，本版继续把“原则正确但模型/接口/状态机未闭环”的缺口落实为明确的数据结构、数据库约束、事务边界、状态转换、恢复矩阵和 AI Coding Agent 强制规则。文末附录仅用于追溯演进，不得推翻本版规范主体。

**文档版本：** v2.4
**编写日期：** 2026-09-13
**目标平台：** Android 12（API 31）及以上
**项目定位：** Android 本地优先的 Bilibili 主播直播监控工具；核心能力为可靠监控、主播管理、开播/关播通知、直播历史、统计、备份恢复和可恢复后台运行。登录仅服务于可选的关注导入；不设计实时多人同步、实时多设备同步或独立多账号监控空间。

---

# 0. 规范使用说明与最终规则优先级

本文件分为“最终实现契约”和“历史追溯资料”。**只有最终实现契约允许直接指导代码、Entity、DAO、Migration、UseCase 与测试。**历史追溯资料只保留设计演进背景，不得作为实现依据。

当不同位置出现冲突时，按以下固定顺序处理：

```text
0.6《最终实现契约：唯一模型、DDL、枚举、状态机与接口》
    > 本章定义的最终规则与明确的业务不变量
    > 规范主体中对最终契约的局部解释
    > 历史说明、原规范章节、旧伪代码、旧示例
```

所有标题以“原规范”开头的章节，其模型、伪代码、字段列表与 SQL 只用于追溯；若与 0.6 或规范主体冲突，**必须按 0.6 修改实现，不得照抄历史定义**。

## 0.1 唯一事实来源原则

```text
Streamer / LiveSession / Correction / LiveEvent 等业务事实
    → Room 持久化数据

StatisticsCache / StatisticsSnapshot
    → 可重建的派生结果，不是最终事实

Remote API
    → 观察来源，不直接成为本地业务状态

NotificationOutbox
    → 可靠投递状态，不等同于业务事件事实

NotificationHistory
    → 通知审计结果，不等同于 Outbox 生命周期
```

## 0.2 通知最终规则

主播仍在直播时，无论是正常首次确认还是异常恢复后重新确认，用户可见直播通知统一使用：**“正在直播”**。两种来源仍必须保留独立业务事件身份，不得仅因为文案相同而错误去重。

关播通知不依据“整场直播从开播到结束零异常”判断，而依据**当前连续可靠监控区间**：

```text
确认 LIVE / 异常恢复后重新确认 LIVE
        ↓
建立当前连续可靠监控区间
        ↓
区间出现异常？
   是              否
   ↓                ↓
当前区间失去       持续正常监控
关播资格            ↓
             可靠确认 OFFLINE
                    ↓
             发送关播通知
```

异常后重新联网：

```text
恢复时主播仍 LIVE
→ 发送“正在直播”
→ 从恢复确认点建立新区间
→ 该新区间持续正常至 OFFLINE
→ 发送关播通知

恢复时主播已 OFFLINE
→ 不发送“正在直播”
→ 不发送关播通知
```

## 0.3 备份、恢复与跨设备最终规则

备份 Manifest 必须明确恢复范围。**范围内以备份文件为最终权威，范围外本地数据保持不变**；“增量恢复”只表示不触碰范围外数据，不表示范围内采用时间戳竞争。

跨设备迁移依赖 `stableId/eventId/aggregateId` 等业务稳定身份，不依赖本地自增主键，也不恢复原设备 Runtime、Lease、fencing token 等运行时状态。

## 0.4 账号最终边界

登录不是核心监控的前置条件，只服务于可选的关注列表导入。切换账号后按照本地 UID 增量导入，不建立多账号监控空间。

关注导入使用 `importTaskId + authGeneration` 隔离。退出登录时递增 authGeneration；旧任务返回的数据只能写入隔离缓冲，不得继续修改主播监控业务数据。

建议模型：`FollowImportTask(importTaskId, authGeneration, status, startedAt, finishedAt, stagingCount, error)`。正式导入写入 Room 前必须先完成预览与冲突处理。

关注导入必须拥有独立 `importTaskId` 与凭证代际 `authGeneration`。用户退出登录时：

```text
立即撤销后续新的认证请求资格
→ authGeneration 递增
→ 已经提交且使用旧合法凭证的 HTTP 结果，即使返回，也只能写入对应 importTaskId 的隔离缓冲
→ 不得修改监控状态
→ 用户可看到该任务被取消/结果作废
```

退出登录不得直接取消核心 MonitoringEngine，也不得让过期导入任务覆盖新的本地主播数据。关注导入只能更新 Bilibili-owned 字段；用户标签、分组、备注、排序、监控/通知策略和人工修正不得被覆盖。命中软删除 UID 时必须由用户选择恢复或跳过。

## 0.5 当前 Android 后台约束的核验

本版本涉及 Android 12+ 前台服务、Android 15+ `dataSync` 超时约束以及 WorkManager 周期任务下限。根据 2026-09-13 核验的 Android Developers 官方资料：目标 Android 12+ 的应用从后台启动前台服务受到限制；Android 14+ 要求声明匹配的前台服务类型及相应权限；Android 15+ 对 `dataSync` / `mediaProcessing` 前台服务增加 24 小时窗口累计 6 小时限制；WorkManager `PeriodicWorkRequest` 的最小重复间隔为 15 分钟。因此，30 秒/60 秒级的“实时监控”不能由普通 WorkManager 周期任务直接实现，且不能把 `dataSync` 当成永久后台轮询方案。

实现时仍必须结合具体目标 SDK、前台服务类型和系统版本做条件分支，并把“期望监控间隔”和“实际获得的系统运行能力”分开表达。

## 0.6 最终实现契约：唯一模型、DDL、枚举、状态机与接口

本节是本项目的**唯一代码生成契约**。同名历史模型全部视为废弃草稿。AI Coding Agent、人工开发者、测试代码、Migration 与导出格式必须以本节为准。

### 0.6.1 唯一 LiveStatus / Observation 枚举

```kotlin
// 唯一最终定义见 0.6.1 与 0.6.2，本节不得重复声明（避免出现第二套同名枚举）：
//   ConfirmedLiveStatus = { UNKNOWN, OFFLINE, LIVE, ROUND }
//   ObservationResult   = { SUCCESS, TIMEOUT, NETWORK_ERROR, API_ERROR, PARTIAL, INVALID }
//   PendingTransition   = { NONE, SUSPECTED_OFFLINE, SUSPECTED_LIVE }
```

**禁止**存在可写入 `Streamer.confirmedLiveStatus` 的 `ERROR` 值。错误只存在于 `lastObservationResult`、诊断日志、健康状态或 UI 派生显示中。

### 0.6.2 唯一基础枚举：DataConfidence / DataSource / Statistics / Monitoring / Notification / Restore

以下枚举是**唯一最终定义**，历史章节中的同名枚举全部废弃。不得创建第二套同义枚举。

```kotlin
enum class DataConfidence {
    RAW,
    PROVISIONAL,
    CONFIRMED,
    CORRECTED,
    INVALID
}

enum class DataSource {
    MONITOR_OBSERVED,
    USER_ENTERED,
    USER_CORRECTED,
    CALCULATED,
    IMPORTED,
    RECOVERED,
    ESTIMATED
}

enum class StatisticsEligibility {
    CONFIRMED_ONLY,
    INCLUDE_PROVISIONAL,
    INCLUDE_CORRECTED,
    INCLUDE_ESTIMATED
}

enum class DataFreshness {
    FRESH,
    STALE,
    UNKNOWN
}

enum class MonitoringMode {
    REALTIME,
    POWER_SAVING,
    MANUAL
}

enum class MaintenanceMode {
    OFF,
    DATABASE_REPAIR,
    BACKUP,
    RESTORE,
    MIGRATION
}

enum class RecoverySessionStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMEOUT,
    CANCELLED
}

enum class RecoveryFinishReason {
    HEALTHY,
    TIMEOUT,
    FAILED,
    CANCELLED,
    SUPERSEDED
}

enum class MonitorHealthStatus {
    HEALTHY,
    DEGRADED,
    RECOVERING,
    BLOCKED,
    STOPPED
}

enum class AppError {
    NETWORK_UNAVAILABLE,
    NETWORK_TIMEOUT,
    API_REJECTED,
    API_INVALID_RESPONSE,
    DATABASE_ERROR,
    PERMISSION_DENIED,
    NOTIFICATION_UNAVAILABLE,
    BACKGROUND_EXECUTION_RESTRICTED,
    CONFIG_INVALID,
    RESTORE_CONFLICT,
    RESTORE_INCOMPATIBLE,
    EXPORT_FAILED,
    UNKNOWN
}

enum class NotificationEventType {
    START_CONFIRMED,
    LIVE_RECONFIRMED,
    END_CONFIRMED,
    BATCH_LIVE,
    SYSTEM_PROBLEM,
    SYSTEM_RECOVERED
}

enum class LiveEventType {
    START,
    END,
    LIVE_RECONFIRMED
}

enum class ReliableIntervalInvalidReason {
    TIMEOUT,
    NETWORK_ERROR,
    API_ERROR,
    PARTIAL,
    INVALID,
    MONITORING_GAP,
    MANUAL_STOP,
    STREAMER_DELETED,
    GENERATION_CHANGED,
    SESSION_CLOSED
}

enum class GapReason {
    NETWORK_UNAVAILABLE,
    API_UNAVAILABLE,
    DATABASE_BLOCKED,
    MONITORING_STOPPED,
    RUNTIME_CRASH,
    UNKNOWN
}

enum class BackupScopeType {
    ALL_DATA,
    SELECTED_STREAMERS,
    SINGLE_STREAMER,
    DATE_RANGE
}

enum class RestoreFinishReason {
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED,
    PARTIAL_WITH_WARNING
}

// 专用枚举
enum class NotificationAggregateStatus {
    COLLECTING, READY, FROZEN, DISPATCHED, CANCELLED, EXPIRED
}

enum class NotificationOutboxStatus {
    PENDING, PROCESSING, DELIVERY_UNKNOWN, SENT, RETRY_WAIT, FAILED, EXPIRED, CANCELLED
}

enum class AggregateEventBindingStatus {
    PENDING_AGGREGATION,
    BOUND,
    RELEASED
}

enum class IntervalStatus { ACTIVE, INVALIDATED, CLOSED }
enum class GapScope { SYSTEM, STREAMER }
enum class RestoreRunStatus { PRECHECK, WAITING_DECISIONS, APPLYING, COMPLETED, FAILED, CANCELLED }
enum class RestoreConflictAction { USE_BACKUP, KEEP_LOCAL, MERGE, SKIP }
enum class RestoreConflictType {
    STABLE_ID_MATCH,
    STABLE_ID_DIFFERENT_UID_SAME,
    STABLE_ID_SAME_UID_DIFFERENT,
    STABLE_ID_AND_UID_DIFFERENT,
    UID_COLLISION
}

// 外围模块专用枚举（此前只存在于 DDL 的 CHECK 字面量中，现登记为唯一枚举）
enum class NotificationHistoryDeliveryStatus {
    CREATED, PENDING, PROCESSING, SENT, DELIVERY_UNKNOWN, FAILED, EXPIRED, CANCELLED
}

enum class ExportType { HTML, JSON, BACKUP, DIAGNOSTIC }
enum class ExportSnapshotStatus { BUILDING, READY, CONSUMING, COMPLETED, FAILED, CANCELLED, EXPIRED }
enum class FollowImportStatus { REQUESTED, FETCHING, PREVIEW, APPLYING, COMPLETED, FAILED, CANCELLED }

/**
 * 单条历史记录的统计纳入状态（用于数据质量展示）。
 * 注意：与 StatisticsEligibility 是两个不同概念——后者是"查询口径参数"，前者是"记录纳入结果"。
 * 历史章节中曾把本枚举误命名为 StatisticsEligibility，该命名已废弃。
 */
enum class StatisticsInclusionState {
    INCLUDED,
    PARTIALLY_INCLUDED,
    EXCLUDED,
    UNKNOWN
}

/** LiveSession.startType/endType 的业务来源分类（仅用于 UI 与诊断展示，不进入统计缓存键）。 */
enum class LiveSessionOrigin { AUTO_MONITOR, USER_ENTERED, IMPORTED, RECOVERED }

/** 用户所选的后台保活方式；语义、文案与运行态映射见 0.6.47.1 / 0.6.47.2。 */
enum class BackgroundKeepAliveChoice {
    FOREGROUND_NOTIFICATION,
    ACCESSIBILITY,
    NO_GUARANTEE,
    UNSET
}

/**
 * 一次候选转换的成因，由 StateConfirmationPolicy 产出，用于状态机分支与诊断。
 * 必须逐一映射到状态机动作，不得只作为日志标签：
 */
enum class TransitionReason {
    /** 首次观察到该主播（UNKNOWN → LIVE/OFFLINE）：建立基线，不产生 START 通知（见 0.6.5）。 */
    FIRST_OBSERVATION,
    /** 连续确认次数达到阈值：提交状态变更 + LiveEvent + Outbox（见 0.6.47.3）。 */
    CONFIRMATION_COUNT_REACHED,
    /** 异常/盲区结束后重新确认 LIVE：产生 LIVE_RECONFIRMED 与"正在直播"通知（见 0.6.5）。 */
    RECOVERY_RECONFIRM,
    /** 用户停止监控：Session 转 ABANDONED，不填 endTime（见 0.6.22）。 */
    MANUAL_STOP,
    /** 主播被删除：Interval 失效，Session 转 ABANDONED（见 0.6.22）。 */
    STREAMER_DELETED
}
```

`TimeDataSource` 永久废弃。`CORRECTED` 是可信度；`USER_CORRECTED` 是来源，两者可以同时存在。`StatisticsEligibility` 由统计查询显式传入，不得隐含于缓存。`DataFreshness` 是派生健康状态，不是业务事实。

**本节的同名冲突已消除**：`DataFreshness`、`MaintenanceMode`、`RecoveryFinishReason`、`StatisticsEligibility` 在历史章节中的第二套定义已改为引用或改名，实现必须以本节为准（见 0.6.42 别名登记表）。

### 0.6.3 唯一 LiveSessionEntity

```kotlin
@Entity(
    tableName = "live_session",
    foreignKeys = [
        ForeignKey(
            entity = StreamerEntity::class,
            parentColumns = ["id"],
            childColumns = ["streamerId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("streamerId"),
        Index(value = ["stableId"], unique = true),
        /** sessionKey 是业务幂等键，必须唯一（与 0.6.29 DDL 的 sessionKey TEXT NOT NULL UNIQUE 一致）。 */
        Index(value = ["sessionKey"], unique = true),
        Index("endTime"),
        Index("lifecycleState")
        // idx_live_session_one_active_open / idx_live_session_one_abandoned_open 是带 WHERE 的部分唯一索引，
        // Room @Index 无法表达，必须由 Migration.execSQL 建立（见 0.6.4.3）。
    ]
)
data class LiveSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableId: String,
    /** 业务幂等键，唯一；生成规则固定为 "LIVEsession:<sessionStableId>"，见 0.6.4。 */
    val sessionKey: String,
    val streamerId: Long,
    val startTime: Long?,
    val endTime: Long?,
    val startSource: DataSource?,
    val endSource: DataSource?,
    val durationSource: DataSource?,
    val startConfidence: DataConfidence?,
    val endConfidence: DataConfidence?,
    val durationConfidence: DataConfidence?,
    val startLocked: Boolean,
    val endLocked: Boolean,
    val durationLocked: Boolean,
    val titleAtStart: String?,
    val titleAtEnd: String?,
    val areaAtStart: String?,
    val areaAtEnd: String?,
    val coverUrlAtStart: String?,
    val coverUrlAtEnd: String?,
    val durationSeconds: Long?,
    val endReason: String?,
    val lifecycleState: LiveSessionLifecycleState,
    val currentReliableIntervalId: String?,
    val lastConfirmedLiveAt: Long?,
    val confirmedOfflineAt: Long?,
    val autoUpdateProtected: Boolean,
    val correctionVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val note: String?
)

enum class LiveSessionLifecycleState {
    ACTIVE,
    ABANDONED
}
```

最终约束：`endTime == null` 表示 OPEN；`endTime != null` 表示 CLOSED。`lifecycleState` 只用于区分 ACTIVE 与 ABANDONED 生命周期，**不得替代 endTime 判断 OPEN/CLOSED**。`startTime/endTime/durationSeconds` 可以独立为空；未结束场次的 `endSource/endConfidence/durationSource/durationConfidence` 必须允许 NULL。`lastConfirmedLiveAt/confirmedOfflineAt` 是诊断/确认锚点，不是第二套结束事实。

### 0.6.4 LiveSession OPEN 唯一索引

最终唯一约束只有这一条：同一主播最多一个 ACTIVE 且 OPEN Session；ABANDONED OPEN Session 可以存在。

```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_live_session_one_active_open
ON live_session(streamerId)
WHERE endTime IS NULL AND lifecycleState = 'ACTIVE';
```

Room 的 `@Index(unique = true)` 无法表达上述带 `WHERE` 的部分索引；**必须在 Room Migration 中使用 `database.execSQL(...)` 手写创建**。不得再使用 `UNIQUE(streamerId, endTime)`，也不得创建 `WHERE endTime IS NULL` 的全体唯一索引。

`sessionKey` 是业务幂等键，生成规则固定为 `LIVEsession:<sessionStableId>` 的稳定字符串编码；不得使用可变时间字段拼接。跨设备恢复保留原 `sessionStableId/sessionKey`，冲突即进入恢复预检。

同一主播最多一个 `ABANDONED + endTime IS NULL`。软删除时若已有 ABANDONED OPEN，不得再生成第二条；再次删除/恢复时复用或先处理现有记录。

### 0.6.4.1 唯一 Room 数据库装配契约

`0.6.29` 与 `0.6.32` 两段 DDL **不直接作为运行期建库脚本**：运行期由 Room 依据 `@Entity` 生成结构，由 `Migration` 补齐 Room 无法表达的部分索引与 CHECK。两者必须逐列一致，差异只允许出现在 Room 无法表达的部分索引上。

```kotlin
@Database(
    entities = [
        // 核心业务事实
        StreamerEntity::class, TagEntity::class, GroupEntity::class,
        StreamerTagCrossRefEntity::class, StreamerGroupCrossRefEntity::class,
        LiveSessionEntity::class, LiveEventEntity::class, StatusHistoryEntity::class,
        StreamerPendingTransitionEntity::class, ReliableMonitorIntervalEntity::class,
        MonitoringGapEntity::class, MonitoringGapStreamerEntity::class,
        LiveSessionCorrectionEntity::class,
        // 通知
        NotificationAggregateEntity::class, NotificationAggregateEventEntity::class,
        NotificationOutboxEntity::class, NotificationDeliveryAttemptEntity::class,
        NotificationIdRegistryEntity::class, NotificationHistoryEntity::class,
        NotificationCooldownEntity::class, ProblemStateEntity::class,
        // 配置与运行态
        MonitoringConfigEntity::class, MonitoringConfigRevisionEntity::class,
        SourceDataRevisionEntity::class, SystemRuntimeLockEntity::class,
        StreamerMonitorPolicyEntity::class,
        // 统计
        StatisticsCacheEntity::class, StatisticsSnapshotEntity::class,
        StatisticsRevisionEntity::class,
        // 备份恢复导出
        ExportSnapshotEntity::class, ExportSnapshotLiveSessionEntity::class,
        RestoreRunEntity::class, RestoreConflictEntity::class,
        // 账号与外围
        AuthSessionEntity::class, FollowImportTaskEntity::class, FollowImportStagingEntity::class,
        AuditLogEntity::class, MonitoringErrorLogEntity::class, ApplicationErrorLogEntity::class,
        CrashRecordEntity::class, HealthEventEntity::class, RecoverySessionEntity::class
    ],
    version = 1,
    exportSchema = true,
    autoMigrations = []
)
@TypeConverters(DshTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun streamerDao(): StreamerDao
    abstract fun liveSessionDao(): LiveSessionDao
    abstract fun notificationOutboxDao(): NotificationOutboxDao
    abstract fun statisticsCacheDao(): StatisticsCacheDao
    // 其余 DAO 见 0.6.15；DAO 不得暴露给 ViewModel 直接写业务状态（见 0.6.41）。
}

object AppDatabaseFactory {
    fun create(context: Context, clock: Clock): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "bilibili_monitor.db")
            // WAL 是"读事务与写事务并发"可用的前提；不得使用默认 journal_mode。
            // 导出唯一使用临时快照表方案（见 0.6.18），因此不需要独立只读连接。
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(BusyTimeoutOpenHelperFactory(BUSY_TIMEOUT_MS = 5_000))
            .addMigrations(*AppMigrations.ALL)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // 见 0.6.4.5：单例行缺失会让所有 CAS 静默失败（影响 0 行且不报错）。
                    seedSingletonRows(db, clock.now())
                }
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // 每次打开都强制开启外键；Room 默认只在部分路径启用。
                    db.execSQL("PRAGMA foreign_keys = ON")
                    // 自检并补种单例行（见 0.6.4.5 第 5 条）。
                    ensureSingletonRows(db, clock.now())
                }
            })
            .build()
}
```

**运行期并发契约（唯一允许的实现方式）：**

```text
journal_mode      = WAL                       （必须；默认 DELETE 下长只读事务与写事务互斥）
busy_timeout      = 5000 ms                   （必须）
foreign_keys      = ON                        （onOpen 强制）
写事务             = 单写者串行；跨 DAO 的组合写入必须使用 RoomDatabase.withTransaction
锁重试             = 仅对 SQLITE_BUSY / SQLiteDatabaseLockedException 退避重试 50/150/400ms，最多 3 次
CAS 失败           = 返回 STALE_OR_FENCED / STALE_WRITE，禁止重试，禁止放宽 WHERE 条件
导出               = 唯一使用临时快照表方案（见 0.6.18）；不得使用长只读事务，
                     因此不存在"导出期间独占读连接"的问题
```

**`busy_timeout` 的正确设置方式。** 契约要求 5000ms，但必须落在**每一个**连接上（Room 默认连接池有多个读连接），
因此不得只在某个连接上执行一次 `PRAGMA`：

```kotlin
// 推荐：通过 SupportSQLiteOpenHelper 回调，在每个连接建立后设置。
// 注意：Android 框架没有公开的 "SupportSQLiteOpenHelperFactoryWithBusyTimeout" 类，
// 必须自己在 Configuration.Builder 上组合 openHelperFactory 与 callback（或用
// android.database.sqlite.SQLiteDatabase#setBusyTimeout 包装 OpenHelper）。
Room.databaseBuilder(context, AppDatabase::class.java, "bilibili_monitor.db")
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .openHelperFactory(object : SupportSQLiteOpenHelper.Factory {
        private val delegate = FrameworkSQLiteOpenHelperFactory()
        override fun create(config: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val c = config.copy(callback = object : SupportSQLiteOpenHelper.Callback(config.callback.version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    config.callback.onCreate(db)
                    db.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) =
                    config.callback.onUpgrade(db, old, new)
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                    db.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
                    config.callback.onOpen(db)
                }
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    config.callback.onConfigure(db)
                    db.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
                }
            })
            return delegate.create(c)
        }
    })
```

`onOpen` 与 `onConfigure` 都必须设置，因为 Room 的连接池会为不同用途创建多条连接；
只在 `onOpen` 设置会留下没有超时保护的连接。

### 0.6.4.2 唯一 TypeConverters 契约

Room 不能自动持久化枚举。**所有枚举列必须且只能通过本节的转换器写入**，序列化格式固定为**枚举名原文（`Enum.name`）**，与 DDL 中 `CHECK ... IN ('...')` 的字面量逐字一致。

```kotlin
class DshTypeConverters {
    // 反序列化遇到未知值时禁止静默回退默认值，必须抛错以便被 INVALID 分支捕获并记录诊断。
    @TypeConverter fun toConfirmedLiveStatus(v: String?): ConfirmedLiveStatus? =
        v?.let { runCatching { ConfirmedLiveStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromConfirmedLiveStatus(v: ConfirmedLiveStatus?): String? = v?.name

    @TypeConverter fun toObservationResult(v: String?): ObservationResult? =
        v?.let { runCatching { ObservationResult.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromObservationResult(v: ObservationResult?): String? = v?.name

    @TypeConverter fun toPendingTransition(v: String?): PendingTransition? =
        v?.let { runCatching { PendingTransition.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromPendingTransition(v: PendingTransition?): String? = v?.name

    @TypeConverter fun toDataConfidence(v: String?): DataConfidence? =
        v?.let { runCatching { DataConfidence.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromDataConfidence(v: DataConfidence?): String? = v?.name

    @TypeConverter fun toDataSource(v: String?): DataSource? =
        v?.let { runCatching { DataSource.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromDataSource(v: DataSource?): String? = v?.name

    @TypeConverter fun toStatisticsEligibility(v: String?): StatisticsEligibility? =
        v?.let { runCatching { StatisticsEligibility.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromStatisticsEligibility(v: StatisticsEligibility?): String? = v?.name

    @TypeConverter fun toDataFreshness(v: String?): DataFreshness? =
        v?.let { runCatching { DataFreshness.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromDataFreshness(v: DataFreshness?): String? = v?.name

    @TypeConverter fun toMonitoringMode(v: String?): MonitoringMode? =
        v?.let { runCatching { MonitoringMode.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromMonitoringMode(v: MonitoringMode?): String? = v?.name

    @TypeConverter fun toMaintenanceMode(v: String?): MaintenanceMode? =
        v?.let { runCatching { MaintenanceMode.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromMaintenanceMode(v: MaintenanceMode?): String? = v?.name

    @TypeConverter fun toRecoverySessionStatus(v: String?): RecoverySessionStatus? =
        v?.let { runCatching { RecoverySessionStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromRecoverySessionStatus(v: RecoverySessionStatus?): String? = v?.name

    @TypeConverter fun toRecoveryFinishReason(v: String?): RecoveryFinishReason? =
        v?.let { runCatching { RecoveryFinishReason.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromRecoveryFinishReason(v: RecoveryFinishReason?): String? = v?.name

    @TypeConverter fun toMonitorHealthStatus(v: String?): MonitorHealthStatus? =
        v?.let { runCatching { MonitorHealthStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromMonitorHealthStatus(v: MonitorHealthStatus?): String? = v?.name

    @TypeConverter fun toAppError(v: String?): AppError? =
        v?.let { runCatching { AppError.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromAppError(v: AppError?): String? = v?.name

    @TypeConverter fun toNotificationEventType(v: String?): NotificationEventType? =
        v?.let { runCatching { NotificationEventType.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromNotificationEventType(v: NotificationEventType?): String? = v?.name

    @TypeConverter fun toLiveEventType(v: String?): LiveEventType? =
        v?.let { runCatching { LiveEventType.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromLiveEventType(v: LiveEventType?): String? = v?.name

    @TypeConverter fun toReliableIntervalInvalidReason(v: String?): ReliableIntervalInvalidReason? =
        v?.let { runCatching { ReliableIntervalInvalidReason.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromReliableIntervalInvalidReason(v: ReliableIntervalInvalidReason?): String? = v?.name

    @TypeConverter fun toGapReason(v: String?): GapReason? =
        v?.let { runCatching { GapReason.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromGapReason(v: GapReason?): String? = v?.name

    @TypeConverter fun toBackupScopeType(v: String?): BackupScopeType? =
        v?.let { runCatching { BackupScopeType.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromBackupScopeType(v: BackupScopeType?): String? = v?.name

    @TypeConverter fun toRestoreFinishReason(v: String?): RestoreFinishReason? =
        v?.let { runCatching { RestoreFinishReason.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromRestoreFinishReason(v: RestoreFinishReason?): String? = v?.name

    @TypeConverter fun toNotificationAggregateStatus(v: String?): NotificationAggregateStatus? =
        v?.let { runCatching { NotificationAggregateStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromNotificationAggregateStatus(v: NotificationAggregateStatus?): String? = v?.name

    @TypeConverter fun toNotificationOutboxStatus(v: String?): NotificationOutboxStatus? =
        v?.let { runCatching { NotificationOutboxStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromNotificationOutboxStatus(v: NotificationOutboxStatus?): String? = v?.name

    @TypeConverter fun toAggregateEventBindingStatus(v: String?): AggregateEventBindingStatus? =
        v?.let { runCatching { AggregateEventBindingStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromAggregateEventBindingStatus(v: AggregateEventBindingStatus?): String? = v?.name

    @TypeConverter fun toIntervalStatus(v: String?): IntervalStatus? =
        v?.let { runCatching { IntervalStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromIntervalStatus(v: IntervalStatus?): String? = v?.name

    @TypeConverter fun toGapScope(v: String?): GapScope? =
        v?.let { runCatching { GapScope.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromGapScope(v: GapScope?): String? = v?.name

    @TypeConverter fun toRestoreRunStatus(v: String?): RestoreRunStatus? =
        v?.let { runCatching { RestoreRunStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromRestoreRunStatus(v: RestoreRunStatus?): String? = v?.name

    @TypeConverter fun toRestoreConflictAction(v: String?): RestoreConflictAction? =
        v?.let { runCatching { RestoreConflictAction.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromRestoreConflictAction(v: RestoreConflictAction?): String? = v?.name

    @TypeConverter fun toRestoreConflictType(v: String?): RestoreConflictType? =
        v?.let { runCatching { RestoreConflictType.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromRestoreConflictType(v: RestoreConflictType?): String? = v?.name

    @TypeConverter fun toLiveSessionLifecycleState(v: String?): LiveSessionLifecycleState? =
        v?.let { runCatching { LiveSessionLifecycleState.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromLiveSessionLifecycleState(v: LiveSessionLifecycleState?): String? = v?.name

    @TypeConverter fun toNotificationHistoryDeliveryStatus(v: String?): NotificationHistoryDeliveryStatus? =
        v?.let { runCatching { NotificationHistoryDeliveryStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromNotificationHistoryDeliveryStatus(v: NotificationHistoryDeliveryStatus?): String? = v?.name

    @TypeConverter fun toExportType(v: String?): ExportType? =
        v?.let { runCatching { ExportType.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromExportType(v: ExportType?): String? = v?.name

    @TypeConverter fun toExportSnapshotStatus(v: String?): ExportSnapshotStatus? =
        v?.let { runCatching { ExportSnapshotStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromExportSnapshotStatus(v: ExportSnapshotStatus?): String? = v?.name

    @TypeConverter fun toBackgroundKeepAliveChoice(v: String?): BackgroundKeepAliveChoice? =
        v?.let { runCatching { BackgroundKeepAliveChoice.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromBackgroundKeepAliveChoice(v: BackgroundKeepAliveChoice?): String? = v?.name

    @TypeConverter fun toFollowImportStatus(v: String?): FollowImportStatus? =
        v?.let { runCatching { FollowImportStatus.valueOf(it) }.getOrNull() }
    @TypeConverter fun fromFollowImportStatus(v: FollowImportStatus?): String? = v?.name

    // Boolean <-> INTEGER（Room 可推断，但显式声明以保证与 DDL 的 DEFAULT 0/1 一致）
    @TypeConverter fun fromBoolean(v: Boolean?): Int? = v?.let { if (it) 1 else 0 }
    @TypeConverter fun toBoolean(v: Int?): Boolean? = v?.let { it != 0 }
}
```

上述转换器**只为 0.6.2 与本节新登记的枚举服务**。任何新增枚举必须在 0.6.2 登记并在此处补齐转换器，否则视为违反 0.6.46。

### 0.6.4.3 唯一 Migration 契约

Room `@Index` 只能表达普通索引与完整唯一索引；**全部带 `WHERE` 的部分唯一索引必须由 `Migration.execSQL()` 建立**（见 0.6.44）。初始版本建库时，Room 依据 `@Entity` 建表与建普通索引，Migration 只负责补齐下列部分索引：

```kotlin
object AppMigrations {
    val V1_0__createPendingIndexIfMissing = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_live_session_one_active_open " +
                    "ON live_session(streamerId) WHERE endTime IS NULL AND lifecycleState = 'ACTIVE'"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_live_session_one_abandoned_open " +
                    "ON live_session(streamerId) WHERE endTime IS NULL AND lifecycleState = 'ABANDONED'"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_live_event_transition_effect " +
                    "ON live_event(streamerId, transitionId) WHERE transitionId IS NOT NULL"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_live_event_one_start " +
                    "ON live_event(sessionStableId) WHERE sessionStableId IS NOT NULL AND eventType = 'START'"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_live_event_one_end " +
                    "ON live_event(sessionStableId) WHERE sessionStableId IS NOT NULL AND eventType = 'END'"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_interval_one_active " +
                    "ON reliable_monitor_interval(streamerStableId) WHERE status = 'ACTIVE'"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_gap_one_open " +
                    "ON monitoring_gap(scope) WHERE endTime IS NULL"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_gap_streamer_one_open " +
                    "ON monitoring_gap_streamer(streamerStableId) WHERE affectedEndAt IS NULL"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_aggregate_one " +
                    "ON notification_outbox(aggregateId) WHERE aggregateId IS NOT NULL"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_one_per_source_event " +
                    "ON notification_outbox(sourceEventId) WHERE sourceEventId IS NOT NULL"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_aggregate_one_in_progress " +
                    "ON notification_aggregate(bootId) WHERE status IN ('COLLECTING','READY')"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_aggregate_event_one_active " +
                    "ON notification_aggregate_event(eventId) WHERE active = 1"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_one_running " +
                    "ON recovery_session(status) WHERE status = 'RUNNING'"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_one_requested " +
                    "ON recovery_session(status) WHERE status = 'REQUESTED'"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_status_history_transition " +
                    "ON status_history(streamerId, transitionId) WHERE transitionId IS NOT NULL"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_attempt_unfinished " +
                    "ON notification_delivery_attempt(finishedAt) WHERE finishedAt IS NULL"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(V1_0__createPendingIndexIfMissing)
}
```

Migration **必须**按 0.6.44 的固定顺序执行；每一步成功后写入审计记录。`PRAGMA foreign_keys` 在 Migration 过程中不可依赖，因此 Migration 内部不得依赖级联删除，必须显式处理子表。

### 0.6.4.4 唯一数据库打开与事务不变式

```text
每次数据库打开：foreign_keys = ON，journal_mode = WAL，busy_timeout = 5000
每个业务事务：先取 system_runtime_lock 代际校验 → 再执行 CAS 写入 → 最后推进 sourceDataVersion（若适用）
事务内禁止：调用 NotificationManager.notify()、发起网络请求、读取系统时间以外的不确定源
事务失败：整体回滚；不得把部分写入当作成功，不得把 CAS 影响行数为 0 当作数据库异常
```

#### 0.6.4.5 数据库首次创建必须写入的单例行（**漏掉即全系统静默失效**）

`system_runtime_lock` 的 CAS 依赖 `EXISTS (SELECT 1 FROM system_runtime_lock WHERE singletonId = 1 ...)`。
如果这张表在首次建库后是**空的**，那么 §0.6.35 的每一条关键写入都会因 `EXISTS` 不成立而影响 0 行，
表现为：**不抛任何异常、日志也只有 STALE_OR_FENCED，但所有观察结果被静默丢弃、监控永远不工作。**
同理，`monitoring_config` 空表会让 `MonitoringConfigSnapshot` 无法生成，Tick 直接无法启动。

因此**建库回调与 Migration 结束时都必须保证下列单例行存在**（幂等 `INSERT OR IGNORE`）：

```sql
INSERT OR IGNORE INTO system_runtime_lock(singletonId, maintenanceMode, monitorGeneration, updatedAt)
VALUES (1, 'OFF', 0, :now);

INSERT OR IGNORE INTO source_data_revision(singletonId, sourceDataVersion, updatedAt)
VALUES (1, 0, :now);

INSERT OR IGNORE INTO monitoring_config(
    singletonId, configVersion, monitoringEnabled, mode, intervalSeconds, batchSize, maxConcurrency,
    timeoutSeconds, maxRetries, retryBaseSeconds, maxRetryDelaySeconds, circuitBreakerThreshold,
    circuitBreakerRecoverySeconds, aggregationEnabled, aggregationThreshold, aggregationWindowSeconds,
    batchCooldownSeconds, freshnessStaleSeconds, backgroundKeepAliveChoice, noGuaranteeAcknowledged,
    noGuaranteeAcknowledgedAt, startConfirmationCount, endConfirmationCount, placeholderText, updatedAt
) VALUES (
    1, 1, 1, 'POWER_SAVING', 300, 50, 4,
    15, 2, 5, 60, 3,
    60, 1, 4, 5,
    30, 300, 'UNSET', 0,
    NULL, 1, 2, '[待获取]', :now);

INSERT OR IGNORE INTO monitoring_config_revision(
    configVersion, monitoringEnabled, mode, intervalSeconds, batchSize, maxConcurrency,
    timeoutSeconds, maxRetries, retryBaseSeconds, maxRetryDelaySeconds, circuitBreakerThreshold,
    circuitBreakerRecoverySeconds, aggregationEnabled, aggregationThreshold, aggregationWindowSeconds,
    batchCooldownSeconds, freshnessStaleSeconds, backgroundKeepAliveChoice, noGuaranteeAcknowledged,
    noGuaranteeAcknowledgedAt, startConfirmationCount, endConfirmationCount, placeholderText, createdAt
) VALUES (
    1, 1, 'POWER_SAVING', 300, 50, 4,
    15, 2, 5, 60, 3,
    60, 1, 4, 5,
    30, 300, 'UNSET', 0,
    NULL, 1, 2, '[待获取]', :now);
```

种子数据契约：

```text
1. 必须是幂等语句（INSERT OR IGNORE / 先查后插同一事务），重复执行不得报错、不得覆盖用户已有配置。
2. 默认 monitoringEnabled = 1 但 mode = 'POWER_SAVING'：
   首次启动尚未完成 0.6.47.1 引导，不得直接进入 REALTIME（见 0.6.47.1 第 4 条）。
3. 默认 intervalSeconds = 300（省电），由引导完成后按用户所选保活方式改写为对应间隔。
4. 种子写入必须与建库同一事务；失败即视为数据库不可用，不得留下"表在但行缺失"的半初始化状态。
5. 启动自检必须检测"单例行缺失"这一情形：缺失时立即补种并写 HealthEvent，
   不得让监控以 STALE_OR_FENCED 的形式静默空转。
6. system_runtime_lock 的 monitorGeneration 初值为 0；首次获取租约后变为 1。
```

### 0.6.5 首次 UNKNOWN → LIVE 以及异常恢复 → LIVE

```text
UNKNOWN/OFFLINE + 连续确认 LIVE
→ 创建或复用当前 ACTIVE OPEN LiveSession
→ 若新建：startTime = eventConfirmedAt
→ startSource = MONITOR_OBSERVED
→ startConfidence = PROVISIONAL
→ 建立 ReliableMonitorInterval
→ 普通首次确认不产生 START 通知
→ 异常恢复后重新确认 LIVE：产生“正在直播”通知
```

首次确认 LIVE 时不允许伪造主播真实开播时间；`startTime` 只表示“软件确认进入本场次时刻”。若当前只有 ABANDONED OPEN，则 RecoveryCheck 必须先依据结果处理该场次，再决定是否允许新建 ACTIVE Session。

### 0.6.6 ReliableMonitorInterval 唯一模型

```kotlin
@Entity(
    tableName = "reliable_monitor_interval",
    indices = [
        Index("streamerStableId"),
        Index("sessionStableId"),
        Index("monitorGeneration"),
        Index("streamerMonitorGeneration"),
        Index("invalidatedAt")
    ]
)
data class ReliableMonitorIntervalEntity(
    @PrimaryKey val intervalId: String,
    val streamerStableId: String,
    val sessionStableId: String?,
    val startedAt: Long,
    val invalidatedAt: Long?,
    val invalidReason: String?,
    val lastReliableObservationSequence: Long,
    val monitorGeneration: Long,
    val streamerMonitorGeneration: Long,
    val fencingToken: String,
    val status: IntervalStatus,
    val updatedAt: Long
)
```

```kotlin
// 唯一最终定义见 0.6.2「专用枚举」。本节不得重复声明，避免出现第二套同名枚举。
// IntervalStatus = { ACTIVE, INVALIDATED, CLOSED }
```

`ACTIVE` 区间只能由 SUCCESS 观察续期。`TIMEOUT/NETWORK_ERROR/API_ERROR/PARTIAL/INVALID` 或进入有效 MonitoringGap 时，必须在同一业务事务中使当前区间 `INVALIDATED`。最终 OFFLINE 只有在当前区间仍为 `ACTIVE` 且代际完全匹配时才具备关播通知资格。`ROUND` 不是异常：只要观察结果为 SUCCESS，区间继续有效。

### 0.6.7 PendingTransition 唯一事实来源

`Streamer` 不再保存可写的 `pendingTransition/pendingConfirmationCount`。这两个字段如在旧数据库存在，Migration 必须迁移到独立表后删除；UI 只能通过 Repository 查询派生值。

```kotlin
@Entity(tableName = "streamer_pending_transition")
data class StreamerPendingTransitionEntity(
    @PrimaryKey val streamerId: Long,
    val monitorGeneration: Long,
    val streamerMonitorGeneration: Long,
    val pendingTransition: PendingTransition,
    val confirmationCount: Int,
    val lastObservationSequence: Long,
    val updatedAt: Long
)
```

所有递增都必须使用带条件的 SQL CAS；影响行数为 0 时表示上下文已经变化，调用方必须重新读取当前状态，不得强行覆盖。

### 0.6.8 唯一 StatusHistoryEntity

```kotlin
@Entity(
    tableName = "status_history",
    foreignKeys = [ForeignKey(
        entity = StreamerEntity::class,
        parentColumns = ["id"],
        childColumns = ["streamerId"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index("streamerId"), Index("streamerStableId"), Index("observationSequence")]
)
data class StatusHistoryEntity(
    @PrimaryKey val historyId: String,
    val streamerId: Long,
    val streamerStableId: String,
    val sessionStableId: String?,
    val oldStatus: ConfirmedLiveStatus,
    val newStatus: ConfirmedLiveStatus,
    val observationResult: ObservationResult,
    val occurredAt: Long,
    val observationSequence: Long,
    val monitorGeneration: Long,
    val streamerMonitorGeneration: Long,
    val fencingToken: String,
    val transitionId: String?,
    val createdAt: Long
)
```

### 0.6.9 唯一 MonitoringGap / MonitoringGapStreamer

```kotlin
@Entity(
    tableName = "monitoring_gap",
    indices = [Index("scope"), Index("startedAt"), Index("endTime")]
)
data class MonitoringGapEntity(
    @PrimaryKey val gapId: String,
    val startedAt: Long,
    val endTime: Long?,
    val scope: GapScope,
    val reason: GapReason,
    val createdAt: Long
)

@Entity(
    tableName = "monitoring_gap_streamer",
    primaryKeys = ["gapId", "streamerStableId"],
    foreignKeys = [ForeignKey(
        entity = MonitoringGapEntity::class,
        parentColumns = ["gapId"],
        childColumns = ["gapId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("gapId"), Index("streamerStableId"), Index("affectedEndAt")]
)
data class MonitoringGapStreamerEntity(
    val gapId: String,
    val streamerStableId: String,
    val affectedStartAt: Long,
    val affectedEndAt: Long?,
    val reason: GapReason,
    val lastReliableAt: Long?,
    val recoveredAt: Long?,
    val createdAt: Long
)
```

`MonitoringGapStreamer.affectedStartAt/affectedEndAt` 是主播级缺口的时间事实，不能用全局 Gap 的 startedAt/endTime 代替。一个连续主播级缺口不得重叠。`affectedStreamerCount` 如保留，只能作为缓存派生字段，不是事实来源。

`reason` 的类型固定为 `GapReason`（见 0.6.2），不得使用 `String`，也不得使用 `ReliableIntervalInvalidReason` 或裸 `ObservationResult` 值。历史章节中的 `MonitoringGap(id: Long, startTime, affectedFrom/affectedTo, resolved, details)` 与 `MonitoringGapStreamer(gapId: Long, streamerId: Long)` 全部废弃，字段名以本节的 DDL 列名为准（`gapId` / `startedAt` / `affectedStartAt` / `affectedEndAt`）。

### 0.6.9.1 唯一 Notification 组实体（Aggregate / Outbox / Attempt / Registry / History）

```kotlin
@Entity(
    tableName = "notification_aggregate",
    indices = [Index("bootId"), Index("status"), Index("expiresAt"), Index("createdAt")]
)
data class NotificationAggregateEntity(
    @PrimaryKey val aggregateId: String,
    val createdAt: Long,
    val windowStartWall: Long,          // 见 0.6.24；不得命名为 windowStartAt
    val windowEndWall: Long,
    val windowStartElapsed: Long,
    val windowEndElapsed: Long,
    val bootId: String,
    val threshold: Int,
    val eventCount: Int,                // 派生缓存；权威值 = COUNT(notification_aggregate_event)
    val status: NotificationAggregateStatus,
    val sentAt: Long?,
    val expiresAt: Long?,
    val configVersion: Long
)

@Entity(
    tableName = "notification_aggregate_event",
    foreignKeys = [
        ForeignKey(entity = NotificationAggregateEntity::class, parentColumns = ["aggregateId"],
            childColumns = ["aggregateId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LiveEventEntity::class, parentColumns = ["eventId"],
            childColumns = ["eventId"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [Index("aggregateId"), Index("eventId"), Index("active"), Index("bindingStatus")]
)
data class NotificationAggregateEventEntity(
    @PrimaryKey val bindingId: String,
    /** 事件当前归属的 Aggregate；RELEASED 后必须置 NULL（由 CHECK 保证）。 */
    val aggregateId: String?,
    val eventId: String,
    /** 1 = 当前归属 aggregateId；0 = 已释放，仅保留审计。 */
    val active: Int,
    val bindingStatus: AggregateEventBindingStatus,
    val boundAt: Long,
    val releasedAt: Long?
)

@Entity(
    tableName = "notification_outbox",
    foreignKeys = [
        ForeignKey(entity = LiveEventEntity::class, parentColumns = ["eventId"],
            childColumns = ["sourceEventId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = StreamerEntity::class, parentColumns = ["id"],
            childColumns = ["streamerId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = NotificationAggregateEntity::class, parentColumns = ["aggregateId"],
            childColumns = ["aggregateId"], onDelete = ForeignKey.NO_ACTION),
        ForeignKey(entity = ProblemStateEntity::class, parentColumns = ["problemKey"],
            childColumns = ["problemKey"], onDelete = ForeignKey.NO_ACTION)
    ],
    indices = [Index("eventKey", unique = true), Index("status"), Index("nextAttemptAt"),
        Index("expiresAt"), Index("streamerId"), Index("aggregateId"), Index("sourceEventId")]
)
data class NotificationOutboxEntity(
    @PrimaryKey val outboxId: String,
    val eventKey: String,
    /**
     * 单事件通知路径的幂等锚点：指向产生本通知的 live_event。
     * 批量聚合通知与系统问题通知为 null。配合部分唯一索引
     * idx_outbox_one_per_source_event 保证"同一事件最多一条单事件 Outbox"。
     */
    val sourceEventId: String?,
    val streamerId: Long?,
    val aggregateId: String?,
    val problemKey: String?,            // 系统问题通知使用；三种形态互斥，见 DDL CHECK
    val notificationId: Int,            // 非空：只能来自 notification_id_registry
    val eventType: NotificationEventType,
    val payloadJson: String,
    val status: NotificationOutboxStatus,
    val nextAttemptAt: Long?,
    val attemptCount: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val processingStartedAt: Long?,     // 历史章节的 claimedAt 已废弃
    val leaseUntilWall: Long?,          // 历史章节的 leaseUntilWallClock 已废弃
    val leaseUntilElapsed: Long?,       // 历史章节的 leaseUntilElapsedRealtime 已废弃
    val leaseBootId: String?,
    val workerInstanceId: String?,
    val deliveryAttemptId: String?,
    val sentAt: Long?,
    val lastError: String?,
    val configVersion: Long
)

@Entity(
    tableName = "notification_delivery_attempt",
    foreignKeys = [ForeignKey(
        entity = NotificationOutboxEntity::class, parentColumns = ["outboxId"],
        childColumns = ["outboxId"], onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index("outboxId"), Index("eventKey"), Index("finishedAt"), Index("deliveryAttemptId", unique = true)]
)
data class NotificationDeliveryAttemptEntity(
    @PrimaryKey val attemptId: String,
    val outboxId: String,
    val eventKey: String,
    val notificationId: Int,
    val workerInstanceId: String,
    val deliveryAttemptId: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val result: DeliveryAttemptResult?,
    /** 由启动恢复流程把租约过期的未完成 attempt 结算为 DELIVERY_UNKNOWN 时写入（见 0.6.34）。 */
    val recoveredBy: String?,
    val error: String?
)

// DeliveryAttemptResult = { SENT, DELIVERY_UNKNOWN, FAILED }（唯一最终定义见 0.6.11，本节不重复声明）

@Entity(tableName = "notification_id_registry", indices = [Index("notificationId", unique = true)])
data class NotificationIdRegistryEntity(
    @PrimaryKey val eventKey: String,
    val notificationId: Int,
    val createdAt: Long
)

@Entity(
    tableName = "notification_history",
    foreignKeys = [ForeignKey(
        entity = NotificationOutboxEntity::class, parentColumns = ["outboxId"],
        childColumns = ["outboxId"], onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index("outboxId"), Index("eventKey"), Index("streamerStableId"), Index("recordedAt")]
)
data class NotificationHistoryEntity(
    @PrimaryKey val historyId: String,
    val eventKey: String,
    val outboxId: String?,
    val attemptId: String?,
    val notificationId: Int?,
    val streamerStableId: String?,
    val aggregateId: String?,
    val deliveryStatus: NotificationHistoryDeliveryStatus,
    val attemptCount: Int,
    val deliveredAt: Long?,
    val reason: String?,
    val recordedAt: Long,
    val updatedAt: Long,
    val detail: String?
)

@Entity(tableName = "live_event", indices = [Index("streamerId"), Index("streamerStableId"),
    Index("sessionStableId"), Index("eventConfirmedAt"), Index("transitionId")])
data class LiveEventEntity(
    @PrimaryKey val eventId: String,
    val streamerId: Long,
    val streamerStableId: String,
    val sessionStableId: String?,
    val eventType: LiveEventType,
    val eventConfirmedAt: Long,
    val eventSequence: Long,
    val observationSequence: Long?,
    val monitorGeneration: Long?,
    val streamerMonitorGeneration: Long?,
    val fencingToken: String?,
    val transitionId: String?,
    val configVersion: Long?,
    val createdAt: Long
)
```

**通知链路唯一性总结（与 DDL 部分索引一一对应）：**

```text
同一 live_event（单事件路径） → 最多一条 notification_outbox（sourceEventId 唯一）
同一 aggregateId            → 最多一条 Batch Outbox
同一 eventId                → 最多一条绑定中的 Aggregate 关系（bindingStatus='BOUND'）
同一 Aggregate              → 最多一个进行中的 COLLECTING/READY 窗口（按 bootId 唯一）
同一 eventKey               → 最多一条 Outbox、最多一个 notificationId
```

### 0.6.10 唯一通知聚合状态机

`PENDING_AGGREGATION` 不是 OutboxStatus，也不是 AggregateStatus；它是 `LiveEvent` 的**通知聚合处理阶段语义**。实现层统一用事件关系表 `notification_aggregate_event` 表示事件已进入某个 Aggregate，事件本身不增加第二个会漂移的发送状态字段。

```text
窗口创建 → COLLECTING
达到 threshold → READY（仍未冻结）
windowEnd 到达 → FROZEN（停止收集）
Outbox 发送成功 → DISPATCHED
业务取消 → CANCELLED
超过发送价值窗口且未发送 → EXPIRED
```

`threshold = 1` 时等同于不聚合，直接走单事件通知路径；“从不合并”必须使用显式配置 `aggregationEnabled = false`，不得使用魔法阈值。

**窗口结束时未达阈值的定案流程（必须按顺序，否则会踩唯一约束）：**

```text
窗口到达 windowEndWall 且 eventCount < threshold
  ↓
在**同一事务**内按下列顺序处理该窗口的每一条 active 绑定：
  1) UPDATE notification_aggregate_event
       SET active = 0, aggregateId = NULL, bindingStatus = 'RELEASED', releasedAt = :now
       WHERE aggregateId = :aggregateId AND active = 1;
  2) UPDATE notification_aggregate SET status = 'CANCELLED' WHERE aggregateId = :aggregateId;
  3) 对每条被释放的事件，创建对应的单事件 Outbox：
       eventKey      = 'evt:' || eventId
       sourceEventId = eventId
       streamerId    = 该事件的 streamerId
       eventType     = 按 LiveEvent.eventType 映射
  4) 为每条 Outbox 先经 notification_id_registry 占位取得 notificationId；
  5) UPDATE notification_aggregate.status = 'CANCELLED' 已完成，不再占用 in-progress 窗口名额。

顺序不可颠倒的原因：
  - 必须先 RELEASED 再建 Outbox：idx_outbox_one_per_source_event 只保证"同一事件最多一条 Outbox"，
    但若该事件仍被标记 active，后续任何"重新入窗"的路径都会与 idx_aggregate_event_one_active 冲突；
  - 必须先释放再取消 Aggregate：Aggregate 一旦 CANCELLED，其 FAILED/CANCELLED 之外的语义
    仍需要能查到当初绑定了哪些事件，因此关系行只置 active = 0，不得删除。

反向情形（达到阈值）也必须遵守：窗口内事件保持 active = 1 与 bindingStatus = 'BOUND'，
冻结窗口、创建 Batch Outbox（aggregateId 非空、sourceEventId 为 NULL），三条记录同事务创建。
```

**并发禁止事项：**

```text
1. 同一 eventId 不得同时存在于两条 active = 1 的绑定行（由 idx_aggregate_event_one_active 保证）。
2. 未达阈值时**不得**预先创建单事件 Outbox 再尝试合并；这会产生"先发单条、后补批量"的重复通知。
3. 窗口冻结后不得再向该窗口追加事件（否则 eventCount 与 Batch Outbox 的 payload 不一致）。
4. 崩溃恢复：若进程在步骤 1–5 之间崩溃，恢复扫描必须优先处理"status = 'CANCELLED' 但仍有 active = 1 绑定行"
   的窗口——这是唯一可识别的不一致形态，必须原子补齐 RELEASED 与单事件 Outbox，且不得重复创建。
```

### 0.6.11 NotificationOutbox / DeliveryAttempt 唯一模型与事务边界

**唯一模型见 0.6.9.1**（`NotificationOutboxEntity` / `NotificationDeliveryAttemptEntity` / `NotificationIdRegistryEntity` / `NotificationHistoryEntity`）。
本节不再重复声明，仅固定 Order 语义与事务边界。历史版本中出现过的、缺少类声明且字段命名不一致的
`data class NotificationDeliveryAttemptEntity { attemptId, eventKey, notificationId, workerInstanceId, deliveryAttemptId, startedAt, finishedAt, result, error }`
片段已废弃，原因：缺少所属接口、缺少 `outboxId` 关联列、缺少启动恢复必需的 `recoveredBy`。

```kotlin
// 唯一最终定义见 0.6.9.1（逐字段一致）：
//   NotificationOutboxEntity(outboxId, eventKey, sourceEventId, streamerId, aggregateId, problemKey,
//                            notificationId, eventType, payloadJson, status, nextAttemptAt, attemptCount,
//                            createdAt, expiresAt, processingStartedAt, leaseUntilWall, leaseUntilElapsed,
//                            leaseBootId, workerInstanceId, deliveryAttemptId, sentAt, lastError, configVersion)
//   NotificationDeliveryAttemptEntity(attemptId, outboxId, eventKey, notificationId, workerInstanceId,
//                            deliveryAttemptId, startedAt, finishedAt, result, recoveredBy, error)

/** 一次投递尝试的结论；唯一最终定义。DELIVERY_UNKNOWN 表示"已调用 notify 但结果未知"，不得当成成功或失败。 */
enum class DeliveryAttemptResult { SENT, DELIVERY_UNKNOWN, FAILED }
```

发送流程必须是：

```text
事务 A：claim Outbox + lease + 写 DeliveryAttempt(started)
      ↓
事务外：调用 Android NotificationManager.notify()
      ↓
事务 B：写 DeliveryAttempt(finished/result) + 按 CAS 更新 Outbox
```

调用 `notify()` 后进程崩溃时，不得猜测是否已展示，进入 `DELIVERY_UNKNOWN`。Lease 超时后允许重新 claim，但必须继续使用 `notification_id_registry` 中同一个 notificationId。超过 `expiresAt` → `EXPIRED`。用户恢复通知权限/频道能力后，由 `NotificationCapabilityMonitor` 触发有限扫描，将未过期且可重试的 `FAILED` 转为 `RETRY_WAIT`，扫描必须带上限和分页。

### 0.6.12 Notification ID 注册表

```sql
CREATE TABLE notification_id_registry(
    eventKey TEXT PRIMARY KEY NOT NULL,
    notificationId INTEGER NOT NULL UNIQUE,
    createdAt INTEGER NOT NULL
);
```

**外键决策（此前文档内两版互相矛盾，现固定为无外键）**：registry 必须先于 `notification_outbox` 写入占位（见本节末），因此这里**不得**声明 `FOREIGN KEY(eventKey) REFERENCES notification_outbox(eventKey)`——否则先插 registry 会立即触发外键违例。registry 与 Outbox 的对应关系由 0.6.13 的事务不变量与 0.6.46 检查表保证，不由数据库外键保证。同理，清理 Outbox 时不得级联删除 registry 行（`notificationId` 必须长期稳定，见 0.6.9.1）。

`eventKey → notificationId` 映射只要求在当前安装实例内稳定；跨设备恢复不复制本地通知 ID，只恢复业务事件身份。生成 ID 必须碰撞检测并在注册表事务中原子占用。

`eventKey` 生成规则固定（**唯一版本**；历史章节中"或使用 streamerId + sessionStableId/transitionId + eventType 组合键"的写法已废弃，因为它会为同一事件产出多个幂等键）：

```text
单主播业务事件 → evt:{eventId}          （eventId 直接来自 live_event.eventId）
批量聚合通知 → agg:{aggregateId}
系统问题通知 → problem:{problemKey}     （与 problem_state.problemKey 完全一致；不使用 episodeId）
```

`problemKey` 的构成同样固定，用于把"同一类故障的同一段持续时间"折叠为一个通知身份：

```text
problemKey = "{component}:{problemType}:{scopeKey}"
component   = MONITORING | DATABASE | NETWORK | NOTIFICATION | BACKGROUND | STATISTICS | RESTORE | EXPORT
problemType = AppError 的枚举名
scopeKey    = SYSTEM 或 streamerStableId
```

每次故障从"未激活"转为"激活"时生成新的 `problemKey`（追加 `:{episodeId}`），因此同一类故障的不同时间片段不会互相顶掉；`problem_state.episodeId` 与 `problemKey` 必须同时写入。

Outbox 的 `eventKey` 必须与 `notification_id_registry.eventKey` 完全相同；registry 先占位、Outbox 再引用不得产生两个不同幂等键。每个 eventKey 最多一个 Outbox。

**`eventKey → live_event` 的引用不得只靠字符串解析。** 单事件通知路径必须把 `live_event.eventId` 显式写入 `notification_outbox.sourceEventId`（见 0.6.9.1 与 0.6.29 DDL），由部分唯一索引 `idx_outbox_one_per_source_event` 保证"同一事件最多一条单事件 Outbox"。禁止用 `LIKE 'evt:%'` 之类的字符串匹配判断事件是否已通知。

**通知域保留与清理策略（此前完全缺失，会导致长跑无限膨胀）：**

`notification_outbox`、`notification_aggregate`、`notification_aggregate_event`、`notification_delivery_attempt`、`notification_id_registry` 与 `notification_history` 此前只有"防重复"约束，**没有任何清理规则**。手机端长期运行必须定稿：

| 表 | 保留规则 | 清理触发 | 清理时不得触碰 |
|---|---|---|---|
| `notification_outbox`（`SENT` / `EXPIRED` / `CANCELLED`） | 保留 `max(30 天, 最近 2000 条)` | 维护事务 + 启动清理 | `PROCESSING`、`PENDING`、`RETRY_WAIT`、`DELIVERY_UNKNOWN` 的行 |
| `notification_aggregate`（`DISPATCHED` / `CANCELLED` / `EXPIRED`） | 保留 30 天 | 同上 | 仍为 `COLLECTING` / `READY` 的行 |
| `notification_aggregate_event` | 跟随其 Aggregate；`active = 0` 且 Aggregate 已清理时删除 | 级联 | `active = 1` 的行 |
| `notification_delivery_attempt` | 保留 30 天，或跟随 Outbox | 同上 | `finishedAt IS NULL` 的行（启动恢复仍需要它） |
| `notification_history` | **不随 Outbox 清理而删除**（见 249.10.2）；保留 `max(90 天, 最近 5000 条)` | 独立清理任务 | 无（它是审计结果，允许独立保留窗口） |
| `notification_id_registry` | **永久保留，不得回收**（见 0.6.22.1） | 仅随主播硬删除而定 | 其他任何 eventKey 行 |

```text
硬性规则：
1. 任何清理都必须先确认目标行不处于可投递状态；"清理"不得等价于"取消发送"。
2. 清理必须推进 sourceDataVersion 吗？——不需要（通知域不是统计事实）。
   但删除 notification_history 行时必须写 AuditLog，因为它属于审计数据。
3. 清理必须有单次上限与分页（例如单次最多 500 行），不得在启动路径上做全表删除。
4. 清理必须与导出/备份/恢复共用同一维护互斥规则（见 249.7）：
   存在活动 export_snapshot 时不得删除其范围内的通知数据。
5. 达到保留上限但无法清理（例如全部行都是 PROCESSING）时，必须写 HealthEvent 并让
   存储占用进入健康中心，不得强行删除未结算的行。
```

**`notificationId` 分配必须带上限，且必须有回收规则：**

```text
问题：registry 只增不减（notificationId 必须跨重试稳定），若无上限，分配空间会被耗尽，
      而"碰撞则重新分配"的写法在空间耗尽后会变成死循环。

固定规则：
  notificationId 取值范围 = [1, 2_000_000]（远小于 Android 的 Int 上限，避免与系统/其他库的
  通知 ID 区间重叠，也便于排查）。
  分配算法（必须在 registry 的同一事务内完成，且使用记录在案的确定性序列）：
    1) 若 eventKey 已存在 → 直接复用，结束；
    2) 取候选 = (SELECT COALESCE(MAX(notificationId), 0) + 1 FROM notification_id_registry)；
    3) 候选 > 2_000_000 时，回绕为 1 并从最小值开始线性探测第一个未占用的 ID（有上限的循环）；
    4) 探测超过上限（即整个区间已满）→ 返回 ERROR，写 HealthEvent，不得无限循环；
    5) 写入 registry，UNIQUE(notificationId) 冲突即按步骤 3 重试，最多重试 N 次后失败。
  释放规则：
    - 只有"该 notificationId 对应的 eventKey 已不存在于任何 Outbox 与 NotificationHistory"时才可回收；
    - 回收必须与分配在同一事务口径下判定，避免竞态下两个 eventKey 拿到同一 ID；
    - 回收不是必需的优化项：区间耗尽时的线性探测已能保证正确性，禁止用"直接复用编号"替代探测。

配套：NotificationIdRegistry.getOrCreate 必须返回 Result 而不是裸 Int，
      "区间耗尽"是一种必须被上层处理的可观测失败，而不是异常。
```

**跨设备恢复的重映射步骤（此前未规定）**：

```text
1. 备份中不包含 notification_id_registry（本地通知 ID 不跨设备）。
2. 恢复 notification_outbox 时，按其 eventKey 逐条调用 NotificationIdRegistry.getOrCreate 重新分配本机 notificationId。
3. 同步为恢复出的 notification_history 行回填新的 notificationId。
4. sourceEventId 必须指向本次恢复同时写入的 live_event.eventId；若父事件不在备份范围内，则该 Outbox 不得恢复（按 0.6.39 DATE_RANGE 规则计入 RestoreSummary 警告）。
```

### 0.6.13 NotificationAggregate ↔ Outbox 双向不变量

最终化且需要发送的 Aggregate 必须且只能对应一个 Batch Outbox；Batch Outbox 的 `aggregateId` 必须存在且引用合法 Aggregate。`notification_aggregate_event + aggregate + outbox` 必须在同一数据库事务中创建。Outbox 不得先出现一个不存在的 Aggregate 指针。

### 0.6.14 监控配置与 sourceDataVersion 的唯一存储

```sql
-- 本节与 0.6.29 的同名定义必须逐字一致；以本节为唯一版本源。
CREATE TABLE monitoring_config(
    singletonId INTEGER PRIMARY KEY CHECK(singletonId = 1),
    configVersion INTEGER NOT NULL,
    monitoringEnabled INTEGER NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('REALTIME','POWER_SAVING','MANUAL')),
    intervalSeconds INTEGER NOT NULL,
    batchSize INTEGER NOT NULL,
    maxConcurrency INTEGER NOT NULL,
    timeoutSeconds INTEGER NOT NULL,
    maxRetries INTEGER NOT NULL,
    retryBaseSeconds INTEGER NOT NULL,
    maxRetryDelaySeconds INTEGER NOT NULL,
    circuitBreakerThreshold INTEGER NOT NULL,
    circuitBreakerRecoverySeconds INTEGER NOT NULL,
    aggregationEnabled INTEGER NOT NULL,
    aggregationThreshold INTEGER NOT NULL CHECK(aggregationThreshold >= 1),
    aggregationWindowSeconds INTEGER NOT NULL,
    batchCooldownSeconds INTEGER NOT NULL,
    freshnessStaleSeconds INTEGER NOT NULL,
    /** 开机引导用户所选的后台保活方式，见 0.6.47。 */
    backgroundKeepAliveChoice TEXT NOT NULL DEFAULT 'UNSET'
        CHECK(backgroundKeepAliveChoice IN ('FOREGROUND_NOTIFICATION','ACCESSIBILITY','NO_GUARANTEE','UNSET')),
    /** 是否已明确告知"不保证后台一直存活"；用于审计与再次提醒，不阻断流程。 */
    noGuaranteeAcknowledged INTEGER NOT NULL DEFAULT 0,
    noGuaranteeAcknowledgedAt INTEGER,
    /** 全局默认确认次数（触发 LIVE 方向）。主播级 streamer_monitor_policy 优先，null 表示跟随本列。 */
    startConfirmationCount INTEGER NOT NULL DEFAULT 1 CHECK(startConfirmationCount >= 1),
    /** 全局默认确认次数（触发 OFFLINE 方向）。主播级优先。 */
    endConfirmationCount INTEGER NOT NULL DEFAULT 2 CHECK(endConfirmationCount >= 1),
    /** 昵称等远程资料缺失时写入的占位文案，见 249.9.1。 */
    placeholderText TEXT NOT NULL DEFAULT '[待获取]',
    updatedAt INTEGER NOT NULL
);

CREATE TABLE source_data_revision(
    singletonId INTEGER PRIMARY KEY CHECK(singletonId = 1),
    sourceDataVersion INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL
);

CREATE TABLE system_runtime_lock(
    singletonId INTEGER PRIMARY KEY CHECK(singletonId = 1),
    maintenanceMode TEXT NOT NULL CHECK(maintenanceMode IN ('OFF','DATABASE_REPAIR','BACKUP','RESTORE','MIGRATION')),
    monitoringLeaseId TEXT,
    runtimeInstanceId TEXT,
    monitorGeneration INTEGER NOT NULL,
    fencingToken TEXT,
    leaseUntilWall INTEGER,
    leaseUntilElapsed INTEGER,
    leaseBootId TEXT,
    updatedAt INTEGER NOT NULL,
    -- MaintenanceMode 与 MonitoringLease 数据库级原子互斥（见 248.18）：维护中不得持有租约。
    CHECK (maintenanceMode = 'OFF' OR monitoringLeaseId IS NULL)
);
```

所有影响 MonitoringTick 的参数都必须存在于 `monitoring_config`。`retryPolicyVersion` 如保留，只能作为引用/审计号；旧 Tick 必须仍能通过 `configVersion` 读取完整参数，因此实现**必须**建立 `monitoring_config_revision(configVersion PRIMARY KEY, 完整参数...)`，修改配置时先写 revision，再原子推进单行 current version。`MonitoringConfigSnapshot` 必须完整携带执行所需参数，不得只保存一个无法重放的版本号。

`freshnessStaleSeconds` 是 `DataFreshness` 由 `FRESH` 转 `STALE` 的唯一时间阈值（见 0.6.2 / 248.6）。`freshnessStatus` 是**缓存派生列**：任何影响新鲜度的写入必须按同一 `freshnessStaleSeconds` 重算，阈值改变时必须使全部 `streamer.freshnessStatus` 在维护事务内重算，不得依赖历史写入时的旧阈值。

`sourceDataVersion` 只允许通过下列语句原子递增，禁止“先 SELECT 再应用层 +1 再 UPDATE”：

```sql
UPDATE source_data_revision
SET sourceDataVersion = sourceDataVersion + 1,
    updatedAt = :now
WHERE singletonId = 1;
```

必须推进 `sourceDataVersion` 的事件集合是**封闭**的（不得在此基础上缩减）：

```text
LiveSession 创建 / 关闭 / 时间字段修正 / 合并 / 拆分 / 删除
LiveSession.correctionVersion 递增（任何人工修正或撤销）
LiveSession.startConfidence / endConfidence / durationConfidence 变更
LiveEvent 新增或失效
StatusHistory 新增或重算
ReliableMonitorInterval 失效导致统计口径变化
StatisticsEligibility 相关字段（DataConfidence / DataSource）变更
历史容量清理实际删除了统计范围内的记录
```

明确**不**推进 `sourceDataVersion` 的操作：修改统计时区、修改监控/通知配置、修改 UI 偏好、写入诊断日志（MonitoringErrorLog / ApplicationErrorLog / CrashRecord / HealthEvent）。

`statistics_revision.statisticsVersion` 的推进同样必须在数据库事务内完成，禁止应用层自增：

```sql
INSERT INTO statistics_revision(statisticsVersion, sourceDataVersion, createdAt)
SELECT COALESCE(MAX(statisticsVersion), 0) + 1, :sourceDataVersion, :now FROM statistics_revision;
```

任何基于旧 `statisticsVersion` 完成的统计计算不得覆盖更新版本结果（写入前必须校验 `statisticsVersion` 未变化）。

### 0.6.47 首次启动后台保活引导与运行态表达（**已定稿**）

本节固定 v2.4 遗留的三项产品决策：开机引导三选一、确认次数单一来源、占位文案填充策略。三者都是**唯一实现方式**，不得自行发明第二种。

#### 0.6.47.1 首次启动必须让用户在三种后台保活方式中明确选择

**首次启动 App 时必须展示一次不可跳过的选择页**（系统语言与深色模式适配，但选项文案与后果说明固定）。用户未做出选择前，`backgroundKeepAliveChoice` 保持 `UNSET`，监控不得以"实时模式"启动。

```kotlin
// 枚举 BackgroundKeepAliveChoice 的唯一最终定义见 0.6.2「专用枚举」，本节不得重复声明。
// 取值与含义：
//   FOREGROUND_NOTIFICATION  选项一：前台常驻通知（Foreground Service）
//   ACCESSIBILITY            选项二：无障碍服务（AccessibilityService，实验性）
//   NO_GUARANTEE             选项三：不启用任何保活，用户已明确接受"不保证后台一直存活"
//   UNSET                    尚未选择（仅引导期间存在；不得作为长期状态）
```

三个选项的**用户可见文案与后果必须逐字包含**下列要点，不得弱化：

```text
选项一：前台常驻通知
  用途：在通知栏常驻一条"正在监控 xx 位主播"的通知，用前台服务换取后台持续运行。
  需要：通知权限（Android 13+ 需用户授权）。
  代价：通知栏会一直有一条通知；Android 15+ 上系统对这类前台服务有累计运行时长限制，
        到达上限后会被系统停止。
  可随时：在设置页切换为其他方式。

选项二：无障碍服务（实验性）
  用途：借助系统无障碍能力提高后台存活率。
  需要：跳到系统设置，由用户手动开启本应用的无障碍服务。
  代价：可能增加耗电、带来系统兼容性差异；属于实验性能力。
  承诺：本服务不读取、不上传、不记录与主播监控无关的屏幕内容、输入事件或个人数据。
  可随时：在系统设置或 App 设置页关闭。

选项三：不启用保活（明确告知风险）
  用途：什么都不额外开启，App 只在系统允许的时机执行监控。
  代价（必须明确告知，且必须由用户确认一次）：
        不保证后台一直存活；
        系统可能随时停止后台监控，期间不会检查主播状态，
        因此可能漏掉开播/关播通知；
        打开 App 后会立即补一次检查并如实显示"这段时间没有监控到"。
```

选择结果必须落库并产生审计记录：

```kotlin
suspend fun completeOnboarding(
    choice: BackgroundKeepAliveChoice,
    noGuaranteeAcknowledged: Boolean,   // 仅当 choice == NO_GUARANTEE 时必须为 true
    now: Long
): OnboardingResult
```

规则：

```text
1. 选择写入 monitoring_config.backgroundKeepAliveChoice，并在同一事务内写 AuditLog(action='SET_KEEPALIVE_CHOICE')。
2. choice == NO_GUARANTEE 时必须把 noGuaranteeAcknowledged 置 1 并记录 noGuaranteeAcknowledgedAt；
   choice 为其他值时 noGuaranteeAcknowledged 必须为 0。
3. MonitoringConfigSnapshot 必须携带 backgroundKeepAliveChoice 与 noGuaranteeAcknowledged（见 0.6.16）。
4. 用户未选择（UNSET）时：
   - 禁止自动启动实时监控；
   - HomeUiState 必须显示"尚未选择后台保活方式，点此设置"；
   - 不得用默认值代替用户选择。
5. 用户在设置页可随时改选；改选必须走同一条 completeOnboarding 路径（同样写 AuditLog），
   不得直接 UPDATE 该列绕过审计。
6. 引导页只展示一次；若用户已选择，后续启动不得重复弹出（UNSET 除外）。
```

#### 0.6.47.2 保活方式必须映射到可观测的运行态，且不得伪装正常

引导页的选择只是"用户意图"，**实际能不能保活由系统决定**。因此必须把"用户选择"与"实际能力"分开表达：

| 用户选择 | 授权/能力实际可用 | `MonitorHealthStatus` | 用户可见文案 |
|---|---|---|---|
| `FOREGROUND_NOTIFICATION` | FGS 启动成功且未达系统时长上限 | `HEALTHY` | 正常监控（实时） |
| `FOREGROUND_NOTIFICATION` | 通知权限被拒 | `USER_ACTION_REQUIRED` | 需要通知权限才能后台监控 |
| `FOREGROUND_NOTIFICATION` | 后台启动 FGS 被拒 / 达时长上限 | `SYSTEM_RESTRICTED` | 系统已限制后台运行，监控间隔已自动放慢 |
| `ACCESSIBILITY` | 无障碍服务已授权且运行中 | `HEALTHY` | 正常监控（实时） |
| `ACCESSIBILITY` | 无障碍服务被关闭或未授权 | `DEGRADED` | 无障碍保活已失效，请重新开启 |
| `NO_GUARANTEE` | 系统允许运行 | `HEALTHY` | 监控中（不保证后台持续） |
| `NO_GUARANTEE` | 系统停止后台 / 进程被杀 | `DEGRADED` | 后台监控已停止，打开 App 可恢复 |
| 任意 | 处于有效 `MonitoringGap` | `DEGRADED` | 监控盲区：某段时间未监控到 |

硬性规则：

```text
1. 用户选择与系统能力不一致时，界面必须显示"实际状态"，不得显示用户选择所暗示的乐观状态。
2. 任何降级都不允许静默发生：必须写 HealthEvent 并更新 HomeUiState。
3. NO_GUARANTEE 下系统停止后台监控**不属于错误**，不得弹错误通知；
   但恢复时必须按 249.10.1 如实标记该段为监控盲区，不得补造开播/关播事实。
4. 无障碍服务"可用性"由系统回调实时更新；不得缓存为常量，也不得作为 MonitoringEngine 的唯一运行基础。
5. 三种方式都不可用时，进入 MANUAL 模式并停止后台 tick，界面明确提示需要用户打开 App。
```

#### 0.6.47.3 确认次数使用单一阈值（状态与通知同源）

**已定稿决策**：确认次数采用**单一阈值**，同一个计数同时决定"状态是否变更"和"是否发送通知"，不再区分两个数字。

```text
一个主播的一个方向（LIVE 方向 / OFFLINE 方向）只有一个确认阈值 N：
  confirmationCount 连续达到 N
      ↓
  同一数据库事务内一次性完成：
      · 提交 confirmedLiveStatus 变更
      · 写 LiveEvent（START / END / LIVE_RECONFIRMED）
      · 写 StatusHistory / PendingTransition 复位
      · 创建 NotificationOutbox（若该主播与全局的通知开关允许）

禁止：
  · 用"第一次观察到就发通知、状态稍后再改"的两段式实现；
  · 为通知单独维护第二个计数或第二个阈值列；
  · 在 confirmationCount < N 时预先创建 LiveEvent 或预先占位 Outbox。
```

**阈值取值与默认值（唯一权威）**：

| 阈值 | 存储位置 | 默认 | 取值来源优先级 |
|---|---|---|---|
| LIVE 方向确认次数 | `streamer_monitor_policy.startConfirmationCount`（可空）→ 回退 `monitoring_config.startConfirmationCount` | 全局 `1` | 主播级 > 全局 |
| OFFLINE 方向确认次数 | `streamer_monitor_policy.endConfirmationCount`（可空）→ 回退 `monitoring_config.endConfirmationCount` | 全局 `2` | 主播级 > 全局 |

- 两列都必须 `>= 1`，由 DB CHECK 与 UseCase 双重校验。
- **只有 OFFLINE 方向默认 2**（防止网络抖动造成假关播通知与假结束场次）；LIVE 方向默认 1（首次可靠观察到 LIVE 立即确认，及时通知）。
- 阈值的解析必须在同一处完成（`EffectiveAppConfig` → `MonitoringConfigSnapshot`），**Tick 执行期间不得重读**（见 0.6.16）。
- 主播级字段允许为 `NULL` 表示"跟随全局"；一旦为具体值即完全覆盖全局，不做插值。

**通知时机的如实表达**：确认阈值 N 与本轮间隔共同决定通知延迟上界：

```text
最坏延迟 ≈ (N - 1) × 实际监控间隔
LIVE 方向默认 N = 1 → 首次观察到即确认，无额外延迟
OFFLINE 方向默认 N = 2 → 最坏延迟约 1 个监控间隔
```

`endConfirmationCount` 此前在文档中**没有任何代码引用**；本节起它是 OFFLINE 方向确认次数的唯一权威来源，`EndNotificationEligibilityEvaluator` 与状态机必须读取它。

### 0.6.15 关键 CAS DAO 接口

```kotlin
suspend fun applyConfirmedObservationAtomically(
    remote: RemoteLiveRoom,
    transition: DetectedTransition,
    observationSequence: Long,
    monitorGeneration: Long,
    streamerMonitorGeneration: Long,
    fencingToken: String,
    configVersion: Long,
    eventConfirmedAt: Long
): ApplyObservationResult
```

DAO 层必须使用 `WHERE expectedGeneration = :generation AND lastObservationSequence < :sequence ...` 等条件完成 CAS；不得先读实体、在内存修改后无条件 `update(entity)`。

```kotlin
suspend fun updatePendingTransitionCas(
    streamerId: Long,
    expectedMonitorGeneration: Long,
    expectedStreamerGeneration: Long,
    expectedSequence: Long,
    newTransition: PendingTransition,
    newCount: Int,
    sequence: Long
): Boolean
```

`ApplyObservationResult`、`DetectedTransition`、`RemoteLiveRoom`、`StreamerObservation` 之前只被引用而未定义，现补全为**唯一契约**：

```kotlin
/** 一次状态转换的候选判定结果；由 StateConfirmationPolicy 依据观察序列产生，不携带数据库身份。 */
data class DetectedTransition(
    val streamerStableId: String,
    val from: ConfirmedLiveStatus,
    val to: ConfirmedLiveStatus,
    val transitionId: String,
    val observationSequence: Long,
    val streamerMonitorGeneration: Long,
    val reason: TransitionReason
)

// TransitionReason 的唯一最终定义见 0.6.2「专用枚举」；本节不得重复声明。
// 取值：FIRST_OBSERVATION / CONFIRMATION_COUNT_REACHED / RECOVERY_RECONFIRM / MANUAL_STOP / STREAMER_DELETED

/** applyConfirmedObservationAtomically 的唯一返回类型。影响行数为 0 时必须返回下列失败值之一，不得抛数据库异常。 */
sealed interface ApplyObservationResult {
    /** CAS 成功，且这是本代际内首次提交该 sequence。 */
    data class Applied(val committedSequence: Long, val newStatus: ConfirmedLiveStatus) : ApplyObservationResult
    /** 该 sequence 已被更大或相同的 sequence 提交：旧响应到达，必须整体丢弃。 */
    data object StaleSequence : ApplyObservationResult
    /** streamerMonitorGeneration / monitorGeneration / fencingToken / maintenanceMode 任一不匹配。 */
    data object StaleOrFenced : ApplyObservationResult
    /** 配置版本已变化且本次写入依赖旧配置参数。 */
    data object ConfigStale : ApplyObservationResult
    /** 唯一索引冲突：并发路径已创建 ACTIVE OPEN Session 或 START 事件，调用方必须重读后复用。 */
    data object DuplicateOpenSession : ApplyObservationResult
    /** 反序列化或约束失败；必须记录诊断，不得重试。 */
    data class Invalid(val reason: String) : ApplyObservationResult
}

/** 单个主播的一次观察结果；与 BatchRoomStatusResponse.responseValidity 无关（见 0.6.25）。 */
data class StreamerObservation(
    val streamerStableId: String,
    val uid: Long,
    val result: ObservationResult,
    val observedAt: Long
)

/** 远程房间资料；除 uid 外全部可空，遵循 249.9.1「不得写占位值」。 */
data class RemoteLiveRoom(
    val uid: Long,
    val roomId: Long?,
    val shortRoomId: Long?,
    val name: String?,
    val avatarUrl: String?,
    val roomTitle: String?,
    val parentAreaName: String?,
    val areaName: String?,
    val coverUrl: String?,
    val liveUrl: String?,
    val remoteLiveStatus: ConfirmedLiveStatus?,
    val remoteStatusValid: Boolean
)

/** 全部 CAS 写入的统一返回语义。 */
enum class CasWriteResult { APPLIED, STALE_SEQUENCE, STALE_OR_FENCED, CONFIG_STALE, DUPLICATE, INVALID }
```

**DAO 必须遵守的写入契约（与 0.6.35 CAS SQL 模板逐条对应）：**

```kotlin
@Dao
interface StreamerDao {
    /**
     * 唯一允许写 Streamer 确认状态与观察结果的入口。必须：
     *  1) 校验 observationSequence < :observationSequence（严格递增，旧响应不覆盖）
     *  2) 校验 streamerMonitorGeneration = :streamerMonitorGeneration
     *  3) EXISTS(system_runtime_lock WHERE monitorGeneration=:g AND fencingToken=:t AND maintenanceMode='OFF')
     *  4) 同时刷新 lastCheckedAt / lastObservationResult / lastConfirmedAt / freshnessStatus
     * 影响行数 0 → 返回 ApplyObservationResult.StaleOrFenced / StaleSequence，禁止放宽条件重试。
     */
    @Query("""
        UPDATE streamer
        SET confirmedLiveStatus   = :newStatus,
            lastObservationResult = :observationResult,
            observationSequence   = :observationSequence,
            lastCheckedAt         = :observedAt,
            lastConfirmedAt       = :eventConfirmedAt,
            freshnessStatus       = :freshness,
            updatedAt             = :updatedAt
        WHERE id = :streamerId
          AND observationSequence < :observationSequence
          AND streamerMonitorGeneration = :streamerMonitorGeneration
          AND EXISTS (
              SELECT 1 FROM system_runtime_lock
              WHERE singletonId = 1
                AND monitorGeneration = :monitorGeneration
                AND fencingToken = :fencingToken
                AND maintenanceMode = 'OFF'
          )
    """)
    suspend fun casConfirmedObservation(
        streamerId: Long, newStatus: ConfirmedLiveStatus, observationResult: ObservationResult,
        observationSequence: Long, streamerMonitorGeneration: Long,
        monitorGeneration: Long, fencingToken: String,
        observedAt: Long, eventConfirmedAt: Long, freshness: DataFreshness, updatedAt: Long
    ): Int

    /** PendingTransition 递增：上下文变化时必须返回 0，调用方重读后重算，不得强行覆盖。 */
    @Query("""
        UPDATE streamer_pending_transition
        SET pendingTransition = :newTransition, confirmationCount = :newCount,
            lastObservationSequence = :sequence, updatedAt = :updatedAt
        WHERE streamerId = :streamerId
          AND monitorGeneration = :expectedMonitorGeneration
          AND streamerMonitorGeneration = :expectedStreamerGeneration
          AND lastObservationSequence < :sequence
    """)
    suspend fun updatePendingTransitionCas(
        streamerId: Long, expectedMonitorGeneration: Long, expectedStreamerGeneration: Long,
        expectedSequence: Long, newTransition: PendingTransition, newCount: Int,
        sequence: Long, updatedAt: Long
    ): Int

    @Query("SELECT * FROM streamer WHERE stableId = :stableId AND deletedAt IS NULL")
    suspend fun findActiveByStableId(stableId: String): StreamerEntity?

    /** 唯一允许的 sourceDataVersion 递增语句；见 0.6.14。 */
    @Query("UPDATE source_data_revision SET sourceDataVersion = sourceDataVersion + 1, updatedAt = :now WHERE singletonId = 1")
    suspend fun bumpSourceDataVersion(now: Long): Int
}

@Dao
interface LiveSessionDao {
    /** 复用而非重复创建 ACTIVE OPEN Session；唯一索引冲突时调用方必须重读并复用。 */
    @Query("SELECT * FROM live_session WHERE streamerId = :streamerId AND endTime IS NULL AND lifecycleState = 'ACTIVE' LIMIT 1")
    suspend fun findActiveOpenSession(streamerId: Long): LiveSessionEntity?

    @Query("SELECT * FROM live_session WHERE streamerId = :streamerId AND endTime IS NULL AND lifecycleState = 'ABANDONED' LIMIT 1")
    suspend fun findAbandonedOpenSession(streamerId: Long): LiveSessionEntity?

    /** 关播资格与人工修正共用：correctionVersion 必须 CAS。 */
    @Query("""
        UPDATE live_session
        SET endTime = :endTime, endSource = :endSource, endConfidence = :endConfidence,
            confirmedOfflineAt = :confirmedOfflineAt, correctionVersion = correctionVersion + 1, updatedAt = :updatedAt
        WHERE id = :id AND endTime IS NULL AND correctionVersion = :expectedCorrectionVersion
    """)
    suspend fun casCloseSession(
        id: Long, endTime: Long, endSource: DataSource, endConfidence: DataConfidence,
        confirmedOfflineAt: Long, expectedCorrectionVersion: Long, updatedAt: Long
    ): Int
}

@Dao
interface NotificationOutboxDao {
    /**
     * 唯一允许的 Outbox Claim。必须原子抢占：只有 PENDING/RETRY_WAIT 且到达 nextAttemptAt 的行可被认领，
     * 且不允许先读后写（见 245.10）。影响行数 0 表示已被其他 worker 抢占。
     */
    @Query("""
        UPDATE notification_outbox
        SET status = 'PROCESSING', processingStartedAt = :now,
            leaseUntilWall = :leaseUntilWall, leaseUntilElapsed = :leaseUntilElapsed,
            leaseBootId = :bootId, workerInstanceId = :workerInstanceId,
            deliveryAttemptId = :deliveryAttemptId, attemptCount = attemptCount + 1
        WHERE outboxId = :outboxId
          AND status IN ('PENDING','RETRY_WAIT')
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
    """)
    suspend fun claimOutbox(
        outboxId: String, now: Long, leaseUntilWall: Long, leaseUntilElapsed: Long,
        bootId: String, workerInstanceId: String, deliveryAttemptId: String
    ): Int

    /** 启动恢复：PROCESSING 且租约已过期的行（索引 idx_outbox_processing_lease）。 */
    @Query("SELECT * FROM notification_outbox WHERE status = 'PROCESSING' AND leaseUntilWall <= :now LIMIT :limit")
    suspend fun findExpiredProcessing(now: Long, limit: Int): List<NotificationOutboxEntity>

    /** 取件查询（索引 idx_outbox_due）。 */
    @Query("""
        SELECT * FROM notification_outbox
        WHERE status IN ('PENDING','RETRY_WAIT') AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
        ORDER BY createdAt ASC LIMIT :limit
    """)
    suspend fun findDue(now: Long, limit: Int): List<NotificationOutboxEntity>

    /** 结果回写：必须带 status CAS，防止已 SENT 的行被旧 worker 覆盖。 */
    @Query("""
        UPDATE notification_outbox
        SET status = :newStatus, sentAt = :sentAt, lastError = :lastError,
            nextAttemptAt = :nextAttemptAt, leaseUntilWall = NULL, workerInstanceId = NULL
        WHERE outboxId = :outboxId AND status = 'PROCESSING' AND deliveryAttemptId = :deliveryAttemptId
    """)
    suspend fun finishAttempt(
        outboxId: String, deliveryAttemptId: String, newStatus: NotificationOutboxStatus,
        sentAt: Long?, nextAttemptAt: Long?, lastError: String?
    ): Int
}

@Dao
interface StatisticsCacheDao {
    @Query("SELECT * FROM statistics_cache WHERE cacheKey = :cacheKey AND sourceDataVersion = :sourceDataVersion LIMIT 1")
    suspend fun findValid(cacheKey: String, sourceDataVersion: Long): StatisticsCacheEntity?

    /** 旧版本结果不得覆盖新版本（见 245.8）。 */
    @Query("DELETE FROM statistics_cache WHERE sourceDataVersion < :sourceDataVersion")
    suspend fun evictOlderThan(sourceDataVersion: Long): Int

    @Query("DELETE FROM statistics_cache WHERE calculatedAt < :before")
    suspend fun evictCalculatedBefore(before: Long): Int
}
```

`@Dao` 只允许出现在本节的接口中。任何模块需要新写入路径时必须先在本节登记，不得在别处新建 DAO 绕过 CAS。

### 0.6.16 配置快照完整字段

```kotlin
data class MonitoringConfigSnapshot(
    val configVersion: Long,
    val monitoringEnabled: Boolean,
    val mode: MonitoringMode,
    val intervalSeconds: Int,
    val batchSize: Int,
    val maxConcurrency: Int,
    val timeoutSeconds: Int,
    val maxRetries: Int,
    val retryBaseSeconds: Int,
    val maxRetryDelaySeconds: Int,
    val circuitBreakerThreshold: Int,
    val circuitBreakerRecoverySeconds: Int,
    val aggregationEnabled: Boolean,
    val aggregationThreshold: Int,
    val aggregationWindowSeconds: Int,
    val batchCooldownSeconds: Int,
    /** 见 0.6.47.1；引导页选择结果，Tick 只读快照，不在执行期间重读配置。 */
    val backgroundKeepAliveChoice: BackgroundKeepAliveChoice,
    val noGuaranteeAcknowledged: Boolean,
    /** 全局默认确认次数；主播级 policy 非空时优先，见 0.6.47.3。 */
    val startConfirmationCount: Int,
    val endConfirmationCount: Int,
    /** 远程资料缺失时的占位文案，见 249.9.1。 */
    val placeholderText: String
)
```

同一 Tick 的所有批次只使用一个 Snapshot。配置改变不会自动使已经获得合法观察序列的旧业务事实失效；写回时仍必须通过代际 + sequence + fencing CAS。

### 0.6.16.1 configVersion / transitionId / configVersion 落库与新鲜度写入规则

**1) `configVersion` 必须随业务事实落库。** `live_event` 与 `status_history` 已各自新增 `configVersion` 列；`reliable_monitor_interval`、`monitoring_gap`、`monitoring_gap_streamer` 通过其父事务的 `configVersion` 归因，不得另建第二版本号。
```text
写入规则：任何由 MonitoringTick 产生的事实行，必须把该 Tick 使用的 MonitoringConfigSnapshot.configVersion 写入该行的 configVersion 列。
读取规则：诊断与复算只允许按 configVersion 读取 monitoring_config_revision 的完整参数；revision 缺失即视为不可重放，必须记录 CONFIG_INVALID 诊断。
CAS 规则：configVersion 不参与 streamer 行 CAS（代际由 streamerMonitorGeneration + monitorGeneration + fencingToken 负责），但写入前必须存在于 monitoring_config_revision。
```

**2) `transitionId` 的唯一语义。** `transitionId` 表示"一次候选状态转换被确认"的业务身份，此前被 `live_event` 与 `status_history` 引用却从未定义：

```text
生成时机：在确认状态转换的同一数据库事务内生成一次，与该事务的 eventConfirmedAt 一一对应。
生成规则：transitionId = "{streamerStableId}:{eventSequence}:{fromStatus}->{toStatus}"
唯一范围：同一主播内唯一（idx_live_event_transition_effect / idx_status_history_transition 保证）。
生命周期：不单独建表；它不是实体，只是转换身份。若 transitionId 为 NULL，表示该行不是状态转换产生的行
          （例如资料刷新、诊断补记），此时不参与任何"最多一条"约束。
禁止：不得使用随机数或时间戳单独生成，否则崩溃重放会产出第二个 transitionId 并绕过唯一索引。
```

**3) `freshnessStatus` 的唯一写入规则。** `DataFreshness` 是派生健康状态（见 0.6.2），但为列表排序性能而缓存到 `streamer.freshnessStatus`：

```text
写入者：只有 StreamerDao.casConfirmedObservation 与资料刷新事务可以写该列，且必须在同一语句内完成。
判定式：freshness = if (now - lastConfirmedAt <= freshnessStaleSeconds) FRESH
                    else if (lastConfirmedAt IS NULL) UNKNOWN
                    else STALE
阈值来源：monitoring_config.freshnessStaleSeconds（唯一来源；不得在代码中硬编码）
阈值变更：必须在维护事务内批量重算全部 streamer.freshnessStatus，重算完成前 UI 必须显示"新鲜度重算中"
禁止：不得由 UI 层写入、不得由统计任务写入、不得用"检查次数"代替时间年龄判断（见 248.6）
```

**4) `sourceDataVersion` 的触发集合** 见 0.6.14；本节不重复定义，避免出现第二份清单。

**5) `streamerMonitorGeneration` 的唯一递增规则。** 该列此前只出现在 CAS 的 `WHERE` 条件里，**全文没有任何一句递增语句**——若不定义，它永远是 0，CAS 退化为"只用 sequence + fencing 校验"。现固定：

```text
语义：主播级监控代际。表示"该主播的监控上下文被整体重置"，用于让重置之前的在途写入全部作废。

必须递增（每条主播各自 +1，在同一事务内完成）：
  1. 该主播被软删除或硬删除时（与 streamer.deletedAt 同事务）；
  2. 该主播被重新添加/恢复、且旧上下文（PendingTransition、ReliableMonitorInterval）被清理时；
  3. 该主播的监控开关从 0 变为 1（重新纳入监控）时；
  4. 该主播的监控策略（intervalSeconds / 确认次数 / 通知开关）发生**影响判定结果**的变更时；
  5. 恢复（Restore）重写了该主播的监控策略行时；
  6. 该主播的 uid 或 roomId 被用户手工修正、导致后续观察目标发生变化时。

明确**不**递增：
  - 全局配置版本变化（configVersion 变化不递增本列；由 fencingToken 负责作废在途写入）；
  - 单纯的资料刷新（昵称/头像/分区更新）；
  - 通知冷却、聚合阈值等与状态判定无关的设置变更。

递增语句（必须用单条 UPDATE，不得先读后写）：
  UPDATE streamer
  SET streamerMonitorGeneration = streamerMonitorGeneration + 1, updatedAt = :now
  WHERE id = :streamerId;

配套要求：
  - 同一事务内必须把 streamer_pending_transition 的 streamerMonitorGeneration 同步更新为该新值，
    否则递增后第一次 updatePendingTransitionCas 必然返回 0 行（`AND streamerMonitorGeneration = :expected`）；
  - 递增后当前 ACTIVE 的 ReliableMonitorInterval 必须置 INVALIDATED 且 invalidReason = 'GENERATION_CHANGED'；
  - 递增不得改动 confirmedLiveStatus：代际只作废在途写入，不改变已确认的业务事实。
```

> 说明：`configVersion` 变化只影响"配置能否重放"，不使已获得合法观察序列的旧事实失效（见 0.6.16）；
> 而 `streamerMonitorGeneration` 表示**业务上下文被重置**，两者职责不同，不得互相替代。

### 0.6.17 统计缓存唯一键

统计缓存不得只以 `sourceDataVersion` 判断命中。`canonicalJson` 的**输入结构此前只被引用而未定义**，现固定为下列 DTO（唯一契约）：

```kotlin
data class StatisticsQuery(
    val range: StatisticsQueryRange,
    val timezone: String,                    // IANA Zone ID 原文，不得使用固定偏移量字符串
    val eligibility: StatisticsEligibility,  // 查询口径参数，见 0.6.2
    val filters: StatisticsFilters,
    val grouping: StatisticsGrouping
)

data class StatisticsQueryRange(
    /** 闭区间，epoch-millis，统计口径为 [startInclusive, endExclusive)。 */
    val startInclusive: Long,
    val endExclusive: Long
)

data class StatisticsFilters(
    val streamerStableIds: Set<String>,      // 空集 = 不按主播过滤（不得用 null 表达同一语义）
    val tagStableIds: Set<String>,
    val groupStableIds: Set<String>,
    val includeDeletedStreamers: Boolean,
    val minDurationSeconds: Long?,
    val maxDurationSeconds: Long?,
    val onlyWithGaps: Boolean
)

data class StatisticsGrouping(
    val bucket: StatisticsBucket,            // DAY / WEEK / MONTH / STREAMER / TAG / GROUP / NONE
    val sortKey: StatisticsSortKey,          // START_TIME / DURATION / STREAMER_NAME
    val sortAscending: Boolean
)

enum class StatisticsBucket { NONE, DAY, WEEK, MONTH, STREAMER, TAG, GROUP }
enum class StatisticsSortKey { START_TIME, DURATION, STREAMER_NAME }

data class StatisticsResult(
    val statisticsVersion: Long,
    val sourceDataVersion: Long,
    val timezone: String,
    val eligibility: StatisticsEligibility,
    val generatedAt: Long,
    val totals: StatisticsTotals,
    val buckets: List<StatisticsBucketResult>,
    val explanations: List<StatisticExplanation>
)

data class StatisticsTotals(
    val sessionCount: Long,
    val confirmedSessionCount: Long,
    val provisionalSessionCount: Long,
    val correctedSessionCount: Long,
    val excludedSessionCount: Long,
    val monitoredSeconds: Long,
    val roundSeconds: Long,
    val gapSeconds: Long,
    /** 仅统计"可靠监控覆盖"的时长，口径见 249.10.1；不得与 monitoredSeconds 混用。 */
    val reliableMonitoredSeconds: Long
)

data class StatisticsBucketResult(
    val bucketKey: String,                   // epoch-millis 桶起点、stableId 或枚举名
    val label: String,
    val totals: StatisticsTotals
)

data class StatisticExplanation(
    val metric: String,
    /** 一律使用 stableId；本地 id 只允许在 DAO JOIN 内部使用（见 0.6.41）。 */
    val includedSessionStableIds: List<String>,
    val excludedSessionStableIds: List<String>,
    val provisionalSessionStableIds: List<String>,
    val reasons: List<String>
)

data class DataQualityReport(
    val sourceDataVersion: Long,
    val generatedAt: Long,
    val inclusionStates: Map<String, StatisticsInclusionState>,  // sessionStableId -> 纳入结果
    val staleCacheCount: Int,
    val openConflictCount: Int,
    val warnings: List<String>
)
```

最终键：

```text
queryFingerprint = canonicalJson(queryRange, timezone, statisticsEligibility, filters, grouping, sourceDataVersion)
cacheKey         = SHA-256(UTF8(queryFingerprint))

说明：
  - queryFingerprint 就是 canonicalJson 原文，必须原样持久化到 statistics_cache.queryFingerprint；
  - cacheKey 是它的 SHA-256 十六进制小写串；
  - queryFingerprint 必须唯一（见 0.6.32 的唯一索引），因此同一 fingerprint 不允许出现两个 cacheKey。
```

缓存至少保存 `cacheKey/statisticsVersion/sourceDataVersion/queryFingerprint/calculatedAt`。`filters` 中的集合必须先按字典序排序再进入 canonicalJson；`null` 与"空集合"语义不同，不得互相替换。统计时区改变只使相关缓存失效/重建，不修改历史 `LiveSession` 时间，也不推进 `sourceDataVersion`，除非同时发生业务事实变更。

### 0.6.18 ExportSnapshot 一致性实现

不能把 `snapshotId + sourceDataVersion` 当作数据库快照本身。**已定稿决策：默认且唯一使用方案 B（临时快照表）。**

```text
方案 B（默认，唯一实现的导出路径）
  1. 进入 MaintenanceMode.BACKUP（与 MonitoringLease 数据库级互斥，见 0.6.35）。
  2. 写 export_snapshot 记录，status = BUILDING。
  3. 在**一个写事务**内把范围内实体复制到以 snapshotId 分区的临时表
     （export_snapshot_live_session 等，见 0.6.38）。
  4. 同一事务内把 export_snapshot.status 置为 READY，提交。
  5. 提交后释放 MaintenanceMode，监控与通知恢复正常。
  6. 跨多个事务分页读取**临时表**并流式写临时文件；此阶段不得再读业务表。
  7. 成功 → status = COMPLETED 并原子提交文件；取消/失败 → status = CANCELLED/FAILED 并删除临时文件。
  8. 由 Maintenance/Worker Cleanup 按 expiresAt 清理临时表与 export_snapshot 行。

方案 A（长只读事务）**不得作为实现路径**：
  它要求在整个导出期间独占数据库并冻结写入事务，会让监控与通知停摆，
  与"用户点导出不应影响监控"的产品目标冲突。本版起方案 A 仅作为设计演进记录保留，
  代码生成不得生成该分支，也不得保留"运行期二选一"的开关。
```

方案 B 的硬约束：

```text
1. 复制阶段必须在单事务内完成；部分复制即视为失败，不得进入 READY。
2. 复制阶段必须评估剩余磁盘空间：预计占用 = 范围内记录字节数 × 1.3；
   不足时在进入复制前取消，并明确告知用户"存储空间不足，导出已取消"。
3. HTML、备份配置文件与可选 JSON 若来自同一次导出，必须使用同一个 snapshotId 的临时表数据。
4. 导出建立快照后，历史容量清理不得改变该快照已确定的数据内容（见 249.13）。
5. 临时表名必须以 snapshotId 分区；禁止复用同一张表存放多次导出的数据。
6. 分页读取阶段允许检测到磁盘/内存超阈值或用户取消 → 删除临时文件并返回未完成，
   不得返回"导出成功"但只写出部分数据。
7. 崩溃恢复：启动时把 status 处于 BUILDING/READY/CONSUMING 且已过期的 export_snapshot
   连同其临时表清理掉；不得让临时表无限累积。
```

### 0.6.19 Backup / Restore DTO 唯一模型

```kotlin
data class BackupManifest(
    val backupId: String,
    /** 备份业务数据结构版本：字段集合/语义的兼容性判定依据。 */
    val backupSchemaVersion: Int,
    /** 容器格式版本：文件封装、压缩、编码方式的兼容性判定依据。与 backupSchemaVersion 独立递增。 */
    val formatVersion: Int,
    /** Room 数据库结构版本：由 AppDatabase.version 提供，用于判定"能否在目标设备直接入库"。 */
    val roomSchemaVersion: Int,
    val createdAt: Long,
    val sourceDataVersion: Long,
    val scopeType: BackupScopeType,
    val selectedStreamerStableIds: List<String>,
    /** 必须与 checksum 覆盖的文件内容严格对应；恢复时必须逐项校验，不一致即拒绝。 */
    val recordCounts: Map<String, Long>,
    val checksumAlgorithm: String,
    val checksum: String,
    val appVersionName: String,
    val producerDeviceBootId: String?
)
```

三个版本号的职责必须分离，不得互相代替：

```text
formatVersion       容器能否被本版本解析          不匹配 → REJECTED，不得尝试部分读取
backupSchemaVersion 业务字段能否被本版本理解       不匹配 → 允许按 55.3.1 兼容矩阵降级或拒绝
roomSchemaVersion   目标设备数据库结构是否兼容     不匹配 → 只允许"导入为历史数据"路径，不得直接入库
```

`recordCounts` 必须覆盖备份范围内每一类业务表；恢复预检阶段必须逐项比对实际读出的记录数，任何一项不一致都不得进入 APPLYING。

data class RestoreConflict(
    val conflictId: String,
    val backupId: String,
    val stableId: String?,
    val backupUid: Long?,
    val localStableId: String?,
    val localUid: Long?,
    val type: RestoreConflictType
)

data class RestoreConflictDecision(
    val conflictId: String,
    val action: RestoreConflictAction
)

data class RestoreRun(
    val restoreRunId: String,
    val backupId: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: RestoreRunStatus
)

data class RestoreSummary(
    val restoreRunId: String,
    val restoredCounts: Map<String, Long>,
    val skippedCounts: Map<String, Long>,
    val conflictCounts: Map<String, Long>,
    val warnings: List<String>
)

### 0.6.20 stableId / UID 恢复矩阵

| stableId | uid | 处理 |
|---|---|---|
| 相同 | 相同 | 同一实体，按备份范围规则恢复 |
| 相同 | 不同 | 高风险身份冲突，禁止静默覆盖；用户选择 MERGE/USE_BACKUP/KEEP_LOCAL/SKIP，并完整重映射历史引用 |
| 不同 | 相同 | UID 冲突；不得新增第二条；用户选择 MERGE/KEEP_LOCAL/USE_BACKUP/SKIP |
| 不同 | 不同 | 默认视为不同实体，可新增 |

冲突处理必须先预检、再决策、再单事务执行。`uid UNIQUE` 冲突不能靠 catch Exception 后继续写来解决。

### 0.6.21 Correction 唯一模型

```kotlin
data class LiveSessionCorrectionEntity(
    @PrimaryKey val correctionId: String,
    val sessionStableId: String,
    val baseCorrectionVersion: Long,
    val newCorrectionVersion: Long,
    val changedFieldsJson: String,
    val source: DataSource,
    val createdAt: Long,
    val operationId: String
)
```

`id: Long/sessionId: Long` 不能作为跨设备修正身份。每次人工修正必须提供唯一 `correctionId + operationId` 并通过 correctionVersion CAS。

### 0.6.22 Recovery / ABANDONED OPEN Session 规则

软删除、关闭监控、退出应用都**不是**下播事实，不得直接填写 `endTime`。对已存在的 `ACTIVE OPEN` Session：

```text
软删除 / 明确停止监控
→ 当前 ReliableMonitorInterval 失效
→ Session 转 ABANDONED
→ endTime 保持 NULL
```

同一主播最多一个 ABANDONED OPEN。重新启用时 RecoveryCheck：

```text
确认 OFFLINE
→ 关闭该 ABANDONED Session，endTime = 本次可靠确认时间
→ endConfidence = PROVISIONAL
→ 不因历史异常发送关播通知

确认 LIVE
→ 复用该 Session 为 ACTIVE
→ 建立新的 ReliableMonitorInterval
→ 后续持续正常至 OFFLINE 时才可按新区间资格发送关播通知
```

若数据修复发现历史上已有多个 ABANDONED OPEN，ConsistencyChecker 不得选择性丢弃；必须按 `createdAt DESC` 逐个处理并记录修复 AuditLog，最终恢复到最多一个 ABANDONED OPEN。

#### 0.6.22.1 软删除 / 硬删除与通知域的交互（此前未定义，现固定）

**软删除（默认路径，`streamer.deletedAt != NULL`）**：不删除 `streamer` 行，因此所有引用它的外键（`live_session.streamerId`、`live_event.streamerId`、`status_history.streamerId`、`notification_outbox.streamerId`、`streamer_pending_transition.streamerId`、`streamer_monitor_policy.streamerId`）全部保持有效。**软删除不得触碰 notification_outbox / notification_history。**

```text
软删除主播
→ streamer.deletedAt = now（同一事务）
→ 当前 ACTIVE OPEN Session 转 ABANDONED，endTime 保持 NULL
→ 当前 ReliableMonitorInterval 置 INVALIDATED，invalidReason = 'STREAMER_DELETED'
→ 关闭 Open MonitoringGapStreamer（写 affectedEndAt/recoveredAt）
→ 删除 streamer_pending_transition 行
→ 保留 LiveSession / LiveEvent / StatusHistory / NotificationOutbox / NotificationHistory / AuditLog
→ 不发送关播通知（软删除不是下播事实）
→ 未过期且未发送的 Outbox：置 CANCELLED，reason='STREAMER_DELETED'
→ 已 SENT 的 Outbox 与全部 NotificationHistory：原样保留，不得删除
```

**通知点击降级规则**：`NotificationNavigationPayload.streamerStableId` 指向的主播若已软删除，跳转目标降级为"已删除主播的历史详情页"，**不得回退到网络重新查询该主播**，也不得静默跳到首页。payload 中的 `url` 与 `sessionStableId` 只有在目标记录仍可读时才允许使用。

**硬删除（用户显式确认，不可撤销）**：只有在用户明确选择"同时删除历史"时才允许，且必须在单一维护事务内按固定顺序删除，禁止依赖 SQLite 级联（多数外键是 `NO ACTION`）：

```text
1. notification_delivery_attempt（按 outboxId）
2. notification_history（按 streamerStableId 或 outboxId）
3. notification_id_registry（按第 2 步涉及的 eventKey）
4. notification_outbox（按 streamerId）
5. notification_aggregate_event / notification_aggregate（按聚合窗口归属）
6. monitoring_gap_streamer / reliable_monitor_interval / status_history / live_event
7. live_session_correction（按 sessionStableId）/ live_session
8. streamer_tag_cross_ref / streamer_group_cross_ref / streamer_monitor_policy / streamer_pending_transition
9. streamer
10. 写 AuditLog(action='HARD_DELETE_STREAMER', operationId=唯一)
```

`notification_id_registry` 在第 3 步删除后，其 `notificationId` 视为**永久退休**，不得回收复用（否则旧系统通知会被新事件覆盖）。硬删除完成前必须进入 `MaintenanceMode.RESTORE`，完成后必须推进 `sourceDataVersion` 并做一次一致性检查。

**已软删除主播的重新添加**：按 §249.10.0 走"恢复原实体"，因此 `streamer.uid UNIQUE` 不会冲突；不得先删除后新增。

#### 0.6.22.2 主播身份的 UID 变更与运行期冲突（此前只在恢复期定义，现补运行期）

§0.6.20 只定义了**备份恢复期**的 `stableId/uid` 冲突矩阵；运行期（远程接口返回的 uid 与本地记录不一致、同一 uid 被平台重新分配）此前无规则。现固定：

```text
1. uid 是主播业务身份，stableId 是跨设备身份；两者一旦建立绑定，运行期不得自动改写。
2. 观察结果中的 uid 与本地 streamer.uid 不匹配 → 该 UID 的观察结果判为 ObservationResult.INVALID，
   写 MonitoringErrorLog(errorCode=API_INVALID_RESPONSE)，不得据此修改任何主播状态。
3. 远程资料刷新（RoomMetadataRefresh）返回的 uid 与请求 uid 不一致 → 丢弃本次资料，判为 PARTIAL，
   不得写入 name/roomId/avatarUrl 等字段（遵循 249.9.2）。
4. 用户手工编辑 uid 属于身份变更，必须：
   → 先与目标 uid 的现有主播做冲突预检（复用 0.6.20 的矩阵与 RestoreConflictType）
   → 命中冲突时由用户选择 MERGE / KEEP_LOCAL / USE_BACKUP / SKIP
   → MERGE 时按 stableId 原子重映射全部历史引用，并写 AuditLog
5. 禁止仅凭 uid 相等就自动合并两条主播记录。
```

`live_event` / `status_history` 同时存在 `streamerId`（本地）与 `streamerStableId`（跨设备）两列，二者必须指向同一主播。一致性检查必须包含下列项，任何不一致都不得自动修复，只能标记并进入数据质量中心：

```sql
-- P2-10：双列一致性检查（ConsistencyChecker 必须执行）
SELECT e.eventId FROM live_event e
JOIN streamer s ON s.id = e.streamerId
WHERE s.stableId <> e.streamerStableId;

SELECT h.historyId FROM status_history h
JOIN streamer s ON s.id = h.streamerId
WHERE s.stableId <> h.streamerStableId;
```

一致性检查的完整必查项（追加到 §193 全局一致性检查任务）：

```text
同一主播多个 ACTIVE OPEN Session
同一主播多个 ABANDONED OPEN Session
同一主播多个 ACTIVE ReliableMonitorInterval
同一主播多个 OPEN MonitoringGapStreamer
同一 sessionStableId 多个 START / 多个 END Event
同一 eventId 多条 BOUND Aggregate 关系
同一 aggregateId 多条 Batch Outbox
同一 eventKey 多条 Outbox / 多条 Registry
sourceEventId 指向不存在的 live_event
notification_outbox.streamerId 与 notification_outbox.sourceEventId 所属主播不一致
live_event / status_history 双列身份不一致（上列两条 SQL）
notification_history 与 notification_outbox 行数不匹配
```

### 0.6.23 ROUND 最终语义

默认：`LIVE → ROUND` 不关闭 LiveSession、不产生 END 事件、不发送关播通知。ROUND 是成功观察到的另一种业务状态，不属于异常，因此当前 `ReliableMonitorInterval` 继续有效。统计默认将 ROUND 时间纳入当前直播场次的监控时长；若产品需要区分轮播时间，可额外派生 `roundSeconds`，但不得改变 Session OPEN/CLOSED 语义。

### 0.6.24 eventConfirmedAt 唯一生成规则与聚合时钟

`eventConfirmedAt` 在**确认状态转换的数据库业务事务中生成一次**，表示该转换被软件确认的墙上时间。它不是 API 批次收到时间，也不能在每个后续分页/重试中重新生成。相同 Tick 中不同主播若分别在不同事务确认，可有不同时间；不得因为同一 HTTP 请求而强制同秒。

聚合窗口同时持久化：

```text
bootId
windowStartWall
windowEndWall
windowStartElapsed
windowEndElapsed
```

同一 `bootId` 内优先用 elapsedRealtime 判断窗口结束；跨重启后使用 wall 时间安全恢复，并以“宁可提前冻结，不跨越时间跳变继续合并”为原则。系统时间异常跳变时，不得无限延长窗口。

### 0.6.24.1 唯一 StreamerEntity

`streamer` 是全部业务事实的父表（`live_session` / `live_event` / `status_history` / `streamer_pending_transition` / `notification_outbox` / `streamer_monitor_policy` 都以它为主键引用），但此前**从未声明过 Kotlin 实体**，只存在于 DDL 中。现补全为唯一契约，字段顺序与 `0.6.29` DDL 逐列一致：

```kotlin
@Entity(
    tableName = "streamer",
    indices = [
        Index(value = ["stableId"], unique = true),
        Index(value = ["uid"], unique = true),
        Index("roomId"),
        Index("deletedAt"),
        Index("confirmedLiveStatus"),
        Index("isFavorite"),
        Index("monitorPolicyStableId")
    ]
)
data class StreamerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 跨设备稳定身份；运行期一旦建立绑定不得自动改写（见 0.6.22.2）。 */
    val stableId: String,
    /** 主播业务身份；UNIQUE，且 NOT NULL（见 249.9.1）。 */
    val uid: Long,
    /** 可变远程资料；拿不到就保持 NULL，不得写占位值（见 249.9.1）。 */
    val roomId: Long?,
    val shortRoomId: Long?,
    /** NOT NULL；昵称缺失时写 placeholderText，见 249.9.1.1。 */
    val name: String,
    /** 1 = name 为用户手工编辑，远程刷新不得覆盖，见 249.9.1.1。 */
    val nameLocked: Boolean,
    val avatarUrl: String?,
    val roomTitle: String?,
    val parentAreaName: String?,
    val areaName: String?,
    val coverUrl: String?,
    val liveUrl: String?,
    /** 唯一可写的确认业务状态；不得写入 ERROR（见 0.6.1）。 */
    val confirmedLiveStatus: ConfirmedLiveStatus,
    /** 本次观察结果；错误只在这里表达，不进入 confirmedLiveStatus。 */
    val lastObservationResult: ObservationResult,
    /** 每主播单调递增的观察序号；CAS 条件之一（见 0.6.35）。 */
    val observationSequence: Long,
    /** 主播级监控代际；与全局 monitorGeneration 共同参与 CAS。 */
    val streamerMonitorGeneration: Long,
    val monitoringEnabled: Boolean,
    val isFavorite: Boolean,
    val monitorPolicyStableId: String?,
    val lastCheckedAt: Long?,
    val lastConfirmedAt: Long?,
    /** 缓存派生列；判定式与写入者见 0.6.16.1 第 3 条。 */
    val freshnessStatus: DataFreshness,
    val lastLiveStartedAt: Long?,
    val lastLiveEndedAt: Long?,
    val lastError: String?,
    /** 软删除标记；非空即视为已删除，见 0.6.22.1。 */
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)
```

约束补充：

```text
1. confirmedLiveStatus 的合法值只有 UNKNOWN / OFFLINE / LIVE / ROUND；不得写入 ERROR。
2. name 为 NOT NULL，但语义上分为三类：真实昵称、占位文案（name == placeholderText）、用户手工编辑
   （nameLocked = 1）。三类的判定与覆盖规则见 249.9.1.1。
3. uid 为业务身份且 UNIQUE：手工重新添加已软删除的 UID 必须走 249.10.0 的"恢复原实体"路径，
   不得先删除后新增（否则会破坏历史外键）。
4. 本实体不含 pendingTransition / pendingConfirmationCount；这两个字段只在
   streamer_pending_transition 中（见 0.6.7）。
5. freshnessStatus 与 lastError 是派生/诊断列，不得作为业务事实参与状态机判断。
```

### 0.6.25 BatchRoomStatusResponse 唯一语义

```kotlin
enum class BatchResponseValidity {
    VALID,
    PARTIAL,
    INVALID,
    TRANSPORT_ERROR
}

data class BatchRoomStatusResponse(
    val requestedUids: List<Long>,
    val resultsByUid: Map<Long, RemoteLiveRoom?>,
    val missingUids: Set<Long>,
    val duplicateUids: Set<Long>,
    val responseValidity: BatchResponseValidity
)
```

`responseValidity` 只描述批量响应整体；真正进入状态机的必须是**每个 UID 的 ObservationResult**。`PARTIAL` 批量响应不得被整体当成所有主播 SUCCESS。

### 0.6.26 Backup / Restore / Export 的数据库事务边界

```text
Backup 创建：只读一致性事务读取 → 写临时文件 → 校验 checksum → 原子提交
Restore：预检 → 冲突决策 → 单维护事务应用范围内最终状态 → 一致性检查 → 提交/失败整体回滚
Export：单只读一致性事务贯穿分页或临时快照表 → 流式写临时文件 → 成功后提交
Config Import：只更新配置域，不触发 LiveSession/Statistics/Capacity Cleanup
```

恢复操作必须有 `restoreRunId`，重复执行同一备份必须是幂等/可识别的，不得产生不可解释的重复业务事实。

### 0.6.27 FGS / bootId 实现契约

前台服务具体类型和权限必须根据目标 SDK 与设备版本条件分支；文档中的 `specialUse` 只能作为候选类型，不能直接假定所有版本都可无条件声明。Manifest、权限、ServiceInfo 与降级策略必须在实现章节逐项验证。

运行时间戳跨重启需要 `bootId + elapsedRealtime + wallClock` 三元信息。优先使用 Android 提供的稳定开机计数作为 `bootId`；若当前 API/设备条件下不可用，则在应用启动时生成本次进程/开机实例标识，并同时保存启动 wall 时间用于恢复判定。**不得把不同 bootId 的 elapsedRealtime 直接比较。**

### 0.6.28 最低并发/一致性测试门槛

在实现进入 P1/发布前，以下测试必须存在并通过：

```text
部分唯一索引 Migration：Room 冷启动后真实 SQLite 检查
sourceDataVersion 并发递增：100 个并发事务不得丢号/重复
PendingTransition 并发 2 次 OFFLINE：确认次数最终正确
旧 generation 响应：必须 CAS 失败
旧 fencingToken：必须拒绝写入
首次 UNKNOWN→LIVE：创建 PROVISIONAL OPEN Session 且无 START 通知
软删除 ACTIVE OPEN：转 ABANDONED，不填 endTime
多个 ABANDONED OPEN 修复：最终最多一个
异常恢复 LIVE：发送“正在直播”并建立新区间
异常恢复 OFFLINE：不发送“正在直播”，不发送关播
异常后新区间持续正常→OFFLINE：发送关播
ROUND：不关闭 Session、不发关播、Interval 保持 ACTIVE
Aggregate 崩溃恢复：不重复事件、不遗漏 Outbox
Aggregate + Outbox 原子创建：禁止孤儿
Delivery UNKNOWN 后 lease 超时：重试使用同 notificationId
FAILED 权限恢复：未过期可重新入 RETRY_WAIT，过期不重试
统计缓存：时区/筛选/口径不同不得错误命中
导出分页：在同一只读事务中看到一致数据
stableId 相同 + uid 不同：必须进入冲突决策
stableId 不同 + uid 相同：必须进入冲突决策
历史 Correction 跨设备恢复：引用 stableId/correctionId 而非本地 Long id
```


### 0.6.29 最终 Room/SQLite 结构总基线（与 0.6.32 外围 DDL 合并后形成唯一完整 DDL；不得新增未声明表）

以下 SQL 是实现、Migration 和自动化测试的唯一结构基线。字段可使用 Room TypeConverter 映射 enum（见 0.6.4.2），但数据库列名/NULL 语义必须保持一致。

**索引归属规则（唯一，不得混用）：**

```text
普通索引 + 完整 UNIQUE 索引  → 由 Room 依据 @Entity(indices=[...]) 建立。
                                 其中普通唯一约束（列级 UNIQUE）由 Room 从 @Entity 字段推断。
带 WHERE 的部分唯一索引      → 由 Migration.execSQL 建立（见 0.6.4.3），
                                 本基线的 CREATE UNIQUE INDEX ... WHERE 语句只用于说明语义与测试断言，
                                 不得作为运行期建库脚本重复执行。
```

对应关系必须逐条成立：DDL 中每个不带 `WHERE` 的 `CREATE INDEX` / `CREATE UNIQUE INDEX`，都必须在对应 `@Entity` 的 `indices` 中出现（否则 Room 建库会缺少该索引）；DDL 中每个带 `WHERE` 的唯一索引，都必须在 `0.6.4.3` 的 `AppMigrations` 中出现（否则约束在运行期不存在）。两类都不允许缺失。

```sql
CREATE TABLE streamer (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  stableId TEXT NOT NULL UNIQUE,
  uid INTEGER NOT NULL UNIQUE,
  roomId INTEGER,
  shortRoomId INTEGER,
  -- NOT NULL；远程昵称缺失时写入 monitoring_config.placeholderText，见 249.9.1.1。
  name TEXT NOT NULL DEFAULT '[待获取]',
  /** 1 = 当前 name 是占位文案或用户手工编辑，远程刷新不得覆盖；见 249.9.1.1。 */
  nameLocked INTEGER NOT NULL DEFAULT 0,
  avatarUrl TEXT,
  roomTitle TEXT,
  parentAreaName TEXT,
  areaName TEXT,
  coverUrl TEXT,
  liveUrl TEXT,
  confirmedLiveStatus TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(confirmedLiveStatus IN ('UNKNOWN','OFFLINE','LIVE','ROUND')),
  lastObservationResult TEXT NOT NULL DEFAULT 'INVALID' CHECK(lastObservationResult IN ('SUCCESS','TIMEOUT','NETWORK_ERROR','API_ERROR','PARTIAL','INVALID')),
  observationSequence INTEGER NOT NULL DEFAULT 0,
  streamerMonitorGeneration INTEGER NOT NULL DEFAULT 0,
  monitoringEnabled INTEGER NOT NULL DEFAULT 1,
  isFavorite INTEGER NOT NULL DEFAULT 0,
  monitorPolicyStableId TEXT,
  lastCheckedAt INTEGER,
  lastConfirmedAt INTEGER,
  freshnessStatus TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(freshnessStatus IN ('FRESH','STALE','UNKNOWN')),
  lastLiveStartedAt INTEGER,
  lastLiveEndedAt INTEGER,
  lastError TEXT,
  deletedAt INTEGER,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);
CREATE INDEX idx_streamer_room ON streamer(roomId);
CREATE INDEX idx_streamer_deleted ON streamer(deletedAt);
CREATE INDEX idx_streamer_list ON streamer(deletedAt, confirmedLiveStatus, isFavorite);

CREATE TABLE tag (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  stableId TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  colorHex TEXT,
  sortOrder INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE `group` (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  stableId TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  sortOrder INTEGER NOT NULL DEFAULT 0,
  collapsed INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE streamer_tag_cross_ref (
  streamerId INTEGER NOT NULL,
  tagId INTEGER NOT NULL,
  PRIMARY KEY (streamerId, tagId),
  FOREIGN KEY (streamerId) REFERENCES streamer(id) ON DELETE CASCADE,
  FOREIGN KEY (tagId) REFERENCES tag(id) ON DELETE CASCADE
);
CREATE INDEX idx_streamer_tag_by_tag ON streamer_tag_cross_ref(tagId);

CREATE TABLE streamer_group_cross_ref (
  streamerId INTEGER NOT NULL,
  groupId INTEGER NOT NULL,
  PRIMARY KEY (streamerId, groupId),
  FOREIGN KEY (streamerId) REFERENCES streamer(id) ON DELETE CASCADE,
  FOREIGN KEY (groupId) REFERENCES `group`(id) ON DELETE CASCADE
);
CREATE INDEX idx_streamer_group_by_group ON streamer_group_cross_ref(groupId);

CREATE TABLE live_session (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  stableId TEXT NOT NULL UNIQUE,
  sessionKey TEXT NOT NULL UNIQUE,
  streamerId INTEGER NOT NULL,
  -- startTime 的语义固定为"软件确认本场次进入 LIVE 的时刻"，不是主播真实开播时间（见 0.6.5）；
  -- 人工录入的真实开播时间必须同时把 startSource 置为 USER_ENTERED 并置 startLocked=1。
  startTime INTEGER,
  endTime INTEGER,
  startSource TEXT CHECK(startSource IS NULL OR startSource IN ('MONITOR_OBSERVED','USER_ENTERED','USER_CORRECTED','CALCULATED','IMPORTED','RECOVERED','ESTIMATED')),
  endSource TEXT CHECK(endSource IS NULL OR endSource IN ('MONITOR_OBSERVED','USER_ENTERED','USER_CORRECTED','CALCULATED','IMPORTED','RECOVERED','ESTIMATED')),
  durationSource TEXT CHECK(durationSource IS NULL OR durationSource IN ('MONITOR_OBSERVED','USER_ENTERED','USER_CORRECTED','CALCULATED','IMPORTED','RECOVERED','ESTIMATED')),
  startConfidence TEXT CHECK(startConfidence IS NULL OR startConfidence IN ('RAW','PROVISIONAL','CONFIRMED','CORRECTED','INVALID')),
  endConfidence TEXT CHECK(endConfidence IS NULL OR endConfidence IN ('RAW','PROVISIONAL','CONFIRMED','CORRECTED','INVALID')),
  durationConfidence TEXT CHECK(durationConfidence IS NULL OR durationConfidence IN ('RAW','PROVISIONAL','CONFIRMED','CORRECTED','INVALID')),
  startLocked INTEGER NOT NULL DEFAULT 0,
  endLocked INTEGER NOT NULL DEFAULT 0,
  durationLocked INTEGER NOT NULL DEFAULT 0,
  titleAtStart TEXT,
  titleAtEnd TEXT,
  areaAtStart TEXT,
  areaAtEnd TEXT,
  coverUrlAtStart TEXT,
  coverUrlAtEnd TEXT,
  durationSeconds INTEGER,
  -- 自由文本诊断字段，不承载枚举语义，因此不设 CHECK。
  -- 需要机器可判定时由 confidence/source 列表达，不要解析 endReason 字符串。
  endReason TEXT,
  lifecycleState TEXT NOT NULL CHECK(lifecycleState IN ('ACTIVE','ABANDONED')),
  currentReliableIntervalId TEXT,
  lastConfirmedLiveAt INTEGER,
  confirmedOfflineAt INTEGER,
  autoUpdateProtected INTEGER NOT NULL DEFAULT 0,
  correctionVersion INTEGER NOT NULL DEFAULT 0,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  note TEXT,
  FOREIGN KEY (streamerId) REFERENCES streamer(id) ON DELETE NO ACTION
);
CREATE INDEX idx_live_session_streamer ON live_session(streamerId);
CREATE UNIQUE INDEX idx_live_session_one_active_open
  ON live_session(streamerId)
  WHERE endTime IS NULL AND lifecycleState = 'ACTIVE';
CREATE UNIQUE INDEX idx_live_session_one_abandoned_open
  ON live_session(streamerId)
  WHERE endTime IS NULL AND lifecycleState = 'ABANDONED';

CREATE TABLE live_event (
  eventId TEXT PRIMARY KEY,
  streamerId INTEGER NOT NULL,
  streamerStableId TEXT NOT NULL,
  sessionStableId TEXT,
  eventType TEXT NOT NULL CHECK(eventType IN ('START','END','LIVE_RECONFIRMED')),
  eventConfirmedAt INTEGER NOT NULL,
  eventSequence INTEGER NOT NULL,
  observationSequence INTEGER,
  monitorGeneration INTEGER,
  streamerMonitorGeneration INTEGER,
  fencingToken TEXT,
  transitionId TEXT,
  configVersion INTEGER,
  createdAt INTEGER NOT NULL,
  FOREIGN KEY (streamerId) REFERENCES streamer(id) ON DELETE NO ACTION
);
CREATE UNIQUE INDEX idx_live_event_transition_effect
  ON live_event(streamerId, transitionId)
  WHERE transitionId IS NOT NULL;
CREATE UNIQUE INDEX idx_live_event_one_start
  ON live_event(sessionStableId)
  WHERE sessionStableId IS NOT NULL AND eventType = 'START';
CREATE UNIQUE INDEX idx_live_event_one_end
  ON live_event(sessionStableId)
  WHERE sessionStableId IS NOT NULL AND eventType = 'END';
-- 历史/统计页按主播 + 时间读取事件。
CREATE INDEX idx_live_event_streamer_time
  ON live_event(streamerStableId, eventConfirmedAt);
CREATE INDEX idx_live_event_session
  ON live_event(sessionStableId, eventConfirmedAt);
CREATE INDEX idx_live_event_created ON live_event(createdAt);

CREATE TABLE streamer_pending_transition (
  streamerId INTEGER PRIMARY KEY,
  monitorGeneration INTEGER NOT NULL,
  streamerMonitorGeneration INTEGER NOT NULL,
  pendingTransition TEXT NOT NULL CHECK(pendingTransition IN ('NONE','SUSPECTED_OFFLINE','SUSPECTED_LIVE')),
  confirmationCount INTEGER NOT NULL,
  lastObservationSequence INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  FOREIGN KEY (streamerId) REFERENCES streamer(id) ON DELETE CASCADE
);

CREATE TABLE reliable_monitor_interval (
  intervalId TEXT PRIMARY KEY,
  streamerStableId TEXT NOT NULL,
  sessionStableId TEXT,
  startedAt INTEGER NOT NULL,
  invalidatedAt INTEGER,
  -- 取值必须来自 ReliableIntervalInvalidReason（见 0.6.2）；不写入 ObservationResult 的裸值。
  invalidReason TEXT CHECK(invalidReason IS NULL OR invalidReason IN (
      'TIMEOUT','NETWORK_ERROR','API_ERROR','PARTIAL','INVALID',
      'MONITORING_GAP','MANUAL_STOP','STREAMER_DELETED','GENERATION_CHANGED','SESSION_CLOSED')),
  lastReliableObservationSequence INTEGER NOT NULL,
  monitorGeneration INTEGER NOT NULL,
  streamerMonitorGeneration INTEGER NOT NULL,
  fencingToken TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('ACTIVE','INVALIDATED','CLOSED')),
  updatedAt INTEGER NOT NULL,
  CHECK (status <> 'INVALIDATED' OR (invalidatedAt IS NOT NULL AND invalidReason IS NOT NULL))
);
CREATE UNIQUE INDEX idx_interval_one_active
  ON reliable_monitor_interval(streamerStableId)
  WHERE status = 'ACTIVE';
CREATE INDEX idx_interval_session ON reliable_monitor_interval(sessionStableId, startedAt);

CREATE TABLE monitoring_gap (
  gapId TEXT PRIMARY KEY,
  startedAt INTEGER NOT NULL,
  endTime INTEGER,
  scope TEXT NOT NULL CHECK(scope IN ('SYSTEM','STREAMER')),
  -- 取值必须来自 GapReason（见 0.6.2）。
  reason TEXT NOT NULL CHECK(reason IN ('NETWORK_UNAVAILABLE','API_UNAVAILABLE','DATABASE_BLOCKED','MONITORING_STOPPED','RUNTIME_CRASH','UNKNOWN')),
  createdAt INTEGER NOT NULL,
  CHECK (endTime IS NULL OR endTime >= startedAt)
);
CREATE UNIQUE INDEX idx_gap_one_open
  ON monitoring_gap(scope)
  WHERE endTime IS NULL;

CREATE TABLE monitoring_gap_streamer (
  gapId TEXT NOT NULL,
  streamerStableId TEXT NOT NULL,
  affectedStartAt INTEGER NOT NULL,
  affectedEndAt INTEGER,
  -- 取值必须来自 GapReason（见 0.6.2）。
  reason TEXT NOT NULL CHECK(reason IN ('NETWORK_UNAVAILABLE','API_UNAVAILABLE','DATABASE_BLOCKED','MONITORING_STOPPED','RUNTIME_CRASH','UNKNOWN')),
  lastReliableAt INTEGER,
  recoveredAt INTEGER,
  createdAt INTEGER NOT NULL,
  PRIMARY KEY (gapId, streamerStableId),
  FOREIGN KEY (gapId) REFERENCES monitoring_gap(gapId) ON DELETE CASCADE,
  CHECK (affectedEndAt IS NULL OR affectedEndAt >= affectedStartAt)
);
CREATE UNIQUE INDEX idx_gap_streamer_one_open
  ON monitoring_gap_streamer(streamerStableId)
  WHERE affectedEndAt IS NULL;
CREATE INDEX idx_gap_streamer_by_streamer
  ON monitoring_gap_streamer(streamerStableId, affectedStartAt);

CREATE TABLE status_history (
  historyId TEXT PRIMARY KEY,
  streamerId INTEGER NOT NULL,
  streamerStableId TEXT NOT NULL,
  sessionStableId TEXT,
  oldStatus TEXT NOT NULL CHECK(oldStatus IN ('UNKNOWN','OFFLINE','LIVE','ROUND')),
  newStatus TEXT NOT NULL CHECK(newStatus IN ('UNKNOWN','OFFLINE','LIVE','ROUND')),
  observationResult TEXT NOT NULL CHECK(observationResult IN ('SUCCESS','TIMEOUT','NETWORK_ERROR','API_ERROR','PARTIAL','INVALID')),
  occurredAt INTEGER NOT NULL,
  observationSequence INTEGER NOT NULL,
  monitorGeneration INTEGER NOT NULL,
  streamerMonitorGeneration INTEGER NOT NULL,
  fencingToken TEXT NOT NULL,
  transitionId TEXT,
  configVersion INTEGER,
  createdAt INTEGER NOT NULL,
  FOREIGN KEY (streamerId) REFERENCES streamer(id) ON DELETE NO ACTION
);
CREATE INDEX idx_status_history_streamer_time ON status_history(streamerId, occurredAt);
CREATE INDEX idx_status_history_stable ON status_history(streamerStableId, occurredAt);
CREATE INDEX idx_status_history_sequence ON status_history(observationSequence);
CREATE UNIQUE INDEX idx_status_history_transition
  ON status_history(streamerId, transitionId)
  WHERE transitionId IS NOT NULL;

CREATE TABLE notification_aggregate (
  aggregateId TEXT PRIMARY KEY,
  createdAt INTEGER NOT NULL,
  windowStartWall INTEGER NOT NULL,
  windowEndWall INTEGER NOT NULL,
  windowStartElapsed INTEGER NOT NULL,
  windowEndElapsed INTEGER NOT NULL,
  bootId TEXT NOT NULL,
  threshold INTEGER NOT NULL CHECK(threshold >= 2),
  eventCount INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL CHECK(status IN ('COLLECTING','READY','FROZEN','DISPATCHED','CANCELLED','EXPIRED')),
  sentAt INTEGER,
  expiresAt INTEGER,
  configVersion INTEGER NOT NULL,
  CHECK (windowEndWall > windowStartWall),
  -- 聚合窗口与 Outbox 过期时间闭环（见 §0.6.10 / §47）：窗口冻结前不得让过期时间早于窗口结束。
  CHECK (expiresAt IS NULL OR expiresAt >= windowEndWall)
);
-- 保证同一 bootId 内最多一个"进行中"窗口（COLLECTING 或 READY）。
-- 只约束 bootId，不约束 status 单值，否则同一 boot 内无法先冻结再开新窗口。
CREATE UNIQUE INDEX idx_aggregate_one_in_progress
  ON notification_aggregate(bootId)
  WHERE status IN ('COLLECTING','READY');

CREATE TABLE notification_aggregate_event (
  bindingId TEXT PRIMARY KEY,
  aggregateId TEXT,
  eventId TEXT NOT NULL,
  -- active = 1 表示事件当前归属 aggregateId；RELEASED 时置 0 并把 aggregateId 置 NULL，
  -- 这样事件可以重新绑定到其他 Aggregate，同时保留历史绑定行供审计。
  active INTEGER NOT NULL CHECK(active IN (0,1)),
  bindingStatus TEXT NOT NULL CHECK(bindingStatus IN ('PENDING_AGGREGATION','BOUND','RELEASED')),
  boundAt INTEGER NOT NULL,
  releasedAt INTEGER,
  FOREIGN KEY (aggregateId) REFERENCES notification_aggregate(aggregateId) ON DELETE CASCADE,
  FOREIGN KEY (eventId) REFERENCES live_event(eventId) ON DELETE NO ACTION,
  CHECK (active = 1 OR releasedAt IS NOT NULL),
  -- 注意：必须是 IS NULL / IS NOT NULL，不能用 "aggregateId != NULL"（在 SQLite 中恒为 NULL 即视为通过）。
  CHECK (active = 1 OR aggregateId IS NULL),
  CHECK (active = 0 OR aggregateId IS NOT NULL),
  CHECK (active = 0 OR bindingStatus IN ('PENDING_AGGREGATION','BOUND'))
);
-- “一个事件最多属于一个活跃 Aggregate”：只对 active = 1 生效；RELEASED 后可重新绑定。
CREATE UNIQUE INDEX idx_aggregate_event_one_active
  ON notification_aggregate_event(eventId)
  WHERE active = 1;
CREATE INDEX idx_aggregate_event_by_aggregate
  ON notification_aggregate_event(aggregateId, bindingStatus);
CREATE INDEX idx_aggregate_event_event
  ON notification_aggregate_event(eventId, boundAt);

CREATE TABLE notification_outbox (
  outboxId TEXT PRIMARY KEY,
  eventKey TEXT NOT NULL UNIQUE,
  sourceEventId TEXT,
  streamerId INTEGER,
  aggregateId TEXT,
  problemKey TEXT,
  notificationId INTEGER NOT NULL,
  eventType TEXT NOT NULL CHECK(eventType IN ('START_CONFIRMED','LIVE_RECONFIRMED','END_CONFIRMED','BATCH_LIVE','SYSTEM_PROBLEM','SYSTEM_RECOVERED')),
  payloadJson TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('PENDING','PROCESSING','DELIVERY_UNKNOWN','SENT','RETRY_WAIT','FAILED','EXPIRED','CANCELLED')),
  nextAttemptAt INTEGER,
  attemptCount INTEGER NOT NULL DEFAULT 0,
  createdAt INTEGER NOT NULL,
  expiresAt INTEGER NOT NULL,
  processingStartedAt INTEGER,
  leaseUntilWall INTEGER,
  leaseUntilElapsed INTEGER,
  leaseBootId TEXT,
  workerInstanceId TEXT,
  deliveryAttemptId TEXT,
  sentAt INTEGER,
  lastError TEXT,
  configVersion INTEGER NOT NULL,
  FOREIGN KEY (sourceEventId) REFERENCES live_event(eventId) ON DELETE NO ACTION,
  FOREIGN KEY (streamerId) REFERENCES streamer(id) ON DELETE NO ACTION,
  FOREIGN KEY (aggregateId) REFERENCES notification_aggregate(aggregateId) ON DELETE NO ACTION,
  FOREIGN KEY (problemKey) REFERENCES problem_state(problemKey) ON DELETE NO ACTION,
  -- 三种互斥形态：单主播事件通知 / 批量聚合通知 / 系统问题通知。
  -- 系统问题通知既不属于某个主播也不属于某个 Aggregate，因此 problemKey 参与互斥判定。
  --
  -- 注意：不得写成裸的 OR 形式。SQLite 的 CHECK 只在结果为 **假（0）** 时拒绝，
  -- 结果为 NULL 视为通过。若写成
  --     CHECK ((streamerId IS NOT NULL AND ...) OR (streamerId IS NULL AND ...) ...)
  -- 则当 streamerId 为 NULL 且 aggregateId 也为 NULL 时，第一个分支恒为假、
  -- 后续分支的 `aggregateId IS NOT NULL` 又为 NULL，整个 OR 结果为 NULL → **约束被绕过**，
  -- 从而允许插入三个指针全空的"不可见 Outbox"（通知永远不会被处理）。
  -- 因此每个分支都必须带上总能定值的 IS NULL 判断，并为兜底分支补 ELSE 0。
  CHECK (
    CASE
      WHEN streamerId IS NOT NULL AND aggregateId IS NULL     AND problemKey IS NULL THEN 1
      WHEN streamerId IS NULL     AND aggregateId IS NOT NULL AND problemKey IS NULL THEN 1
      WHEN streamerId IS NULL     AND aggregateId IS NULL     AND problemKey IS NOT NULL THEN 1
      ELSE 0
    END = 1
  ),
  CHECK (eventType <> 'BATCH_LIVE' OR aggregateId IS NOT NULL),
  CHECK (eventType NOT IN ('SYSTEM_PROBLEM','SYSTEM_RECOVERED') OR problemKey IS NOT NULL),
  CHECK (status <> 'PROCESSING' OR (leaseUntilWall IS NOT NULL AND workerInstanceId IS NOT NULL)),
  CHECK (status <> 'SENT' OR sentAt IS NOT NULL)
);
CREATE UNIQUE INDEX idx_outbox_aggregate_one ON notification_outbox(aggregateId) WHERE aggregateId IS NOT NULL;
-- 单事件通知幂等闭环：同一 live_event 最多产生一条单事件 Outbox（批量路径 sourceEventId 为 NULL）。
CREATE UNIQUE INDEX idx_outbox_one_per_source_event
  ON notification_outbox(sourceEventId)
  WHERE sourceEventId IS NOT NULL;
-- 通知 Worker 取件查询：status + nextAttemptAt。
CREATE INDEX idx_outbox_due ON notification_outbox(status, nextAttemptAt);
-- 启动恢复扫描：PROCESSING 且租约过期的行。
CREATE INDEX idx_outbox_processing_lease ON notification_outbox(status, leaseUntilWall);
-- 过期清理扫描。
CREATE INDEX idx_outbox_expires ON notification_outbox(expiresAt);
CREATE INDEX idx_outbox_streamer ON notification_outbox(streamerId, createdAt);

CREATE TABLE notification_delivery_attempt (
  attemptId TEXT PRIMARY KEY,
  outboxId TEXT NOT NULL,
  eventKey TEXT NOT NULL,
  notificationId INTEGER NOT NULL,
  workerInstanceId TEXT NOT NULL,
  deliveryAttemptId TEXT NOT NULL UNIQUE,
  startedAt INTEGER NOT NULL,
  finishedAt INTEGER,
  result TEXT CHECK(result IS NULL OR result IN ('SENT','DELIVERY_UNKNOWN','FAILED')),
  recoveredBy TEXT,
  error TEXT,
  FOREIGN KEY (outboxId) REFERENCES notification_outbox(outboxId) ON DELETE NO ACTION
);
CREATE INDEX idx_attempt_unfinished ON notification_delivery_attempt(finishedAt) WHERE finishedAt IS NULL;
CREATE INDEX idx_attempt_eventkey ON notification_delivery_attempt(eventKey, startedAt);

CREATE TABLE notification_id_registry (
  eventKey TEXT PRIMARY KEY,
  notificationId INTEGER NOT NULL UNIQUE,
  createdAt INTEGER NOT NULL
);
-- 说明：registry 必须先于 notification_outbox 写入占位（见 0.6.12），
-- 因此这里不能声明 REFERENCES notification_outbox(eventKey) 外键；
-- 该引用关系由 0.6.13 的事务不变量与 0.6.46 检查表保证。
CREATE INDEX idx_registry_notification_id ON notification_id_registry(notificationId);

CREATE TABLE notification_history (
  historyId TEXT PRIMARY KEY,
  eventKey TEXT NOT NULL,
  outboxId TEXT,
  attemptId TEXT,
  notificationId INTEGER,
  streamerStableId TEXT,
  aggregateId TEXT,
  deliveryStatus TEXT NOT NULL CHECK(deliveryStatus IN ('CREATED','PENDING','PROCESSING','SENT','DELIVERY_UNKNOWN','FAILED','EXPIRED','CANCELLED')),
  attemptCount INTEGER NOT NULL DEFAULT 0,
  deliveredAt INTEGER,
  -- 自由文本原因（业务文案键或诊断串）；机器可判定状态一律看 deliveryStatus，不解析本列。
  reason TEXT,
  recordedAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL,
  detail TEXT,
  FOREIGN KEY (outboxId) REFERENCES notification_outbox(outboxId) ON DELETE NO ACTION
);
CREATE INDEX idx_history_recorded ON notification_history(recordedAt);
CREATE INDEX idx_history_eventkey ON notification_history(eventKey, recordedAt);
CREATE INDEX idx_history_streamer ON notification_history(streamerStableId, recordedAt);

CREATE TABLE live_session_correction (
  correctionId TEXT PRIMARY KEY,
  sessionStableId TEXT NOT NULL,
  baseCorrectionVersion INTEGER NOT NULL,
  newCorrectionVersion INTEGER NOT NULL,
  changedFieldsJson TEXT NOT NULL,
  source TEXT NOT NULL CHECK(source IN ('MONITOR_OBSERVED','USER_ENTERED','USER_CORRECTED','CALCULATED','IMPORTED','RECOVERED','ESTIMATED')),
  createdAt INTEGER NOT NULL,
  operationId TEXT NOT NULL UNIQUE
);

CREATE TABLE monitoring_config (
  singletonId INTEGER PRIMARY KEY CHECK(singletonId = 1),
  configVersion INTEGER NOT NULL,
  monitoringEnabled INTEGER NOT NULL,
  mode TEXT NOT NULL CHECK(mode IN ('REALTIME','POWER_SAVING','MANUAL')),
  intervalSeconds INTEGER NOT NULL,
  batchSize INTEGER NOT NULL,
  maxConcurrency INTEGER NOT NULL,
  timeoutSeconds INTEGER NOT NULL,
  maxRetries INTEGER NOT NULL,
  retryBaseSeconds INTEGER NOT NULL,
  maxRetryDelaySeconds INTEGER NOT NULL,
  circuitBreakerThreshold INTEGER NOT NULL,
  circuitBreakerRecoverySeconds INTEGER NOT NULL,
  aggregationEnabled INTEGER NOT NULL,
  aggregationThreshold INTEGER NOT NULL CHECK(aggregationThreshold >= 1),
  aggregationWindowSeconds INTEGER NOT NULL,
  batchCooldownSeconds INTEGER NOT NULL,
  freshnessStaleSeconds INTEGER NOT NULL,
  backgroundKeepAliveChoice TEXT NOT NULL DEFAULT 'UNSET'
      CHECK(backgroundKeepAliveChoice IN ('FOREGROUND_NOTIFICATION','ACCESSIBILITY','NO_GUARANTEE','UNSET')),
  noGuaranteeAcknowledged INTEGER NOT NULL DEFAULT 0,
  noGuaranteeAcknowledgedAt INTEGER,
  startConfirmationCount INTEGER NOT NULL DEFAULT 1 CHECK(startConfirmationCount >= 1),
  endConfirmationCount INTEGER NOT NULL DEFAULT 2 CHECK(endConfirmationCount >= 1),
  placeholderText TEXT NOT NULL DEFAULT '[待获取]',
  updatedAt INTEGER NOT NULL
);

CREATE TABLE monitoring_config_revision (
  configVersion INTEGER PRIMARY KEY,
  monitoringEnabled INTEGER NOT NULL,
  mode TEXT NOT NULL CHECK(mode IN ('REALTIME','POWER_SAVING','MANUAL')),
  intervalSeconds INTEGER NOT NULL,
  batchSize INTEGER NOT NULL,
  maxConcurrency INTEGER NOT NULL,
  timeoutSeconds INTEGER NOT NULL,
  maxRetries INTEGER NOT NULL,
  retryBaseSeconds INTEGER NOT NULL,
  maxRetryDelaySeconds INTEGER NOT NULL,
  circuitBreakerThreshold INTEGER NOT NULL,
  circuitBreakerRecoverySeconds INTEGER NOT NULL,
  aggregationEnabled INTEGER NOT NULL,
  aggregationThreshold INTEGER NOT NULL CHECK(aggregationThreshold >= 1),
  aggregationWindowSeconds INTEGER NOT NULL,
  batchCooldownSeconds INTEGER NOT NULL,
  freshnessStaleSeconds INTEGER NOT NULL,
  backgroundKeepAliveChoice TEXT NOT NULL DEFAULT 'UNSET'
      CHECK(backgroundKeepAliveChoice IN ('FOREGROUND_NOTIFICATION','ACCESSIBILITY','NO_GUARANTEE','UNSET')),
  noGuaranteeAcknowledged INTEGER NOT NULL DEFAULT 0,
  noGuaranteeAcknowledgedAt INTEGER,
  startConfirmationCount INTEGER NOT NULL DEFAULT 1 CHECK(startConfirmationCount >= 1),
  endConfirmationCount INTEGER NOT NULL DEFAULT 2 CHECK(endConfirmationCount >= 1),
  placeholderText TEXT NOT NULL DEFAULT '[待获取]',
  createdAt INTEGER NOT NULL
);

CREATE TABLE source_data_revision (
  singletonId INTEGER PRIMARY KEY CHECK(singletonId = 1),
  sourceDataVersion INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);

CREATE TABLE system_runtime_lock (
  singletonId INTEGER PRIMARY KEY CHECK(singletonId = 1),
  maintenanceMode TEXT NOT NULL CHECK(maintenanceMode IN ('OFF','DATABASE_REPAIR','BACKUP','RESTORE','MIGRATION')),
  monitoringLeaseId TEXT,
  runtimeInstanceId TEXT,
  monitorGeneration INTEGER NOT NULL,
  fencingToken TEXT,
  leaseUntilWall INTEGER,
  leaseUntilElapsed INTEGER,
  leaseBootId TEXT,
  updatedAt INTEGER NOT NULL,
  -- MaintenanceMode 与 MonitoringLease 数据库级原子互斥（见 248.18）。
  CHECK (maintenanceMode = 'OFF' OR monitoringLeaseId IS NULL)
);
```

> 注意（本段已按 v2.4 修订；旧描述中"`eventId` 作为 PRIMARY KEY 强制一个事件最多进入一个 Aggregate"已废弃）：
>
> - `notification_aggregate_event` 的主键是独立 `bindingId`；"一个事件最多属于一个**活跃** Aggregate" 由部分唯一索引 `idx_aggregate_event_one_active`（`WHERE active = 1`）保证。释放时把 `active` 置 0、`aggregateId` 置 NULL，因此 `RELEASED` 后事件可以重新绑定到其他 Aggregate，同时保留历史绑定行供审计。
> - `notification_outbox` 的 CHECK 强制**三种**互斥形态之一：单主播通知（`streamerId`）/ 批量通知（`aggregateId`）/ 系统问题通知（`problemKey`）。系统问题通知既无主播也无 Aggregate，必须由 `problemKey` 参与互斥判定，否则 `SYSTEM_PROBLEM` / `SYSTEM_RECOVERED` 无法入队。
> - 单事件通知的幂等由 `idx_outbox_one_per_source_event`（`notification_outbox.sourceEventId` 唯一）保证，不得依赖字符串解析 `eventKey`。
> - 所有带 `WHERE` 的部分索引必须由 Migration `execSQL()` 建立（见 0.6.4.3 与 0.6.44）。

### 0.6.30 外围模块最终契约总览

以下模块即使不参与核心状态机，也属于当前版本功能，因此必须有最终契约；历史章节只能解释 UI/交互背景，不得再发明数据模型：

| 模块 | 唯一最终入口 |
|---|---|
| 主播监控策略 | `StreamerMonitorPolicyEntity` + DAO |
| 关注导入 | `FollowImportTaskEntity` + staging 表 |
| 统计 | `StatisticsCacheEntity` / `StatisticsSnapshotEntity` / `StatisticsRevisionEntity` |
| 审计与诊断 | `AuditLogEntity` / `MonitoringErrorLogEntity` / `ApplicationErrorLogEntity` / `CrashRecordEntity` / `HealthEventEntity` |
| 通知冷却 | `NotificationCooldownEntity` |
| 系统问题键 | `ProblemStateEntity` |
| 导出 | `ExportSnapshotEntity` |
| 恢复 | `RestoreRunEntity` / `RestoreConflictEntity` |
| 登录凭证 | `AuthSessionEntity`，只保存必要凭证元数据；核心监控不依赖登录 |
| UI 搜索/标签/分组 | 继续使用 `streamer/tag/group` 及其 cross-ref，不创建第二套主播身份 |
| 通知点击跳转 | payload DTO 统一生成，不能由 UI 拼接不受信任 URL |
| 无障碍服务 | 只作为可选扩展，不拥有任何业务事实写权限 |

### 0.6.31 外围最终 Entity DTO

以下每个类型都是**唯一**的 Room `@Entity`，表名与 0.6.29 / 0.6.32 的 DDL 逐字对应。不得在别处再声明同名或同表的第二套模型。

```kotlin
@Entity(tableName = "streamer_monitor_policy")
data class StreamerMonitorPolicyEntity(
    @PrimaryKey val streamerId: Long,
    val enabled: Boolean,
    val intervalSeconds: Int?,
    val notifyStart: Boolean,
    val notifyEnd: Boolean,
    val notifyRound: Boolean,
    /** NULL = 跟随全局；非空则覆盖全局，见 0.6.47.3。 */
    val startConfirmationCount: Int?,
    /** NULL = 跟随全局；非空则覆盖全局，见 0.6.47.3。 */
    val endConfirmationCount: Int?,
    val aggregationEnabledOverride: Boolean?,
    val aggregationThresholdOverride: Int?,
    val overrideQuietHours: Boolean?,
    val updatedAt: Long
)

@Entity(tableName = "follow_import_task", indices = [Index("status"), Index("startedAt")])
data class FollowImportTaskEntity(
    @PrimaryKey val importTaskId: String,
    val authGeneration: Long,
    val status: FollowImportStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val stagingCount: Long,
    val error: String?
)

@Entity(
    tableName = "follow_import_staging",
    primaryKeys = ["importTaskId", "uid"],
    foreignKeys = [ForeignKey(entity = FollowImportTaskEntity::class, parentColumns = ["importTaskId"],
        childColumns = ["importTaskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("importTaskId")]
)
data class FollowImportStagingEntity(
    val importTaskId: String,
    val uid: Long,
    val authGeneration: Long,
    val payloadJson: String,
    val createdAt: Long
)

@Entity(
    tableName = "statistics_cache",
    indices = [Index("sourceDataVersion"), Index("calculatedAt"), Index("queryFingerprint", unique = true)]
)
data class StatisticsCacheEntity(
    @PrimaryKey val cacheKey: String,
    val statisticsVersion: Long,
    val sourceDataVersion: Long,
    /** canonicalJson 原文；cacheKey = SHA-256(UTF8(queryFingerprint))，见 0.6.17。 */
    val queryFingerprint: String,
    val timezone: String,
    val eligibility: StatisticsEligibility,
    val calculatedAt: Long,
    val payloadJson: String
)

@Entity(
    tableName = "statistics_snapshot",
    foreignKeys = [ForeignKey(entity = StatisticsCacheEntity::class, parentColumns = ["cacheKey"],
        childColumns = ["cacheKey"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("cacheKey"), Index("generatedAt"), Index("expiresAt")]
)
data class StatisticsSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val cacheKey: String?,
    val statisticsVersion: Long,
    val sourceDataVersion: Long,
    val timezone: String,
    val eligibility: StatisticsEligibility,
    val queryFingerprint: String,
    val generatedAt: Long,
    val expiresAt: Long?,
    val payloadJson: String
)

@Entity(tableName = "statistics_revision", indices = [Index("sourceDataVersion")])
data class StatisticsRevisionEntity(
    @PrimaryKey val statisticsVersion: Long,
    val sourceDataVersion: Long,
    val createdAt: Long
)

@Entity(tableName = "notification_cooldown")
data class NotificationCooldownEntity(
    @PrimaryKey val problemKey: String,
    val lastNotifiedAt: Long?,
    val cooldownUntil: Long?,
    val recoveredNotified: Boolean,
    val updatedAt: Long
)

@Entity(tableName = "problem_state", indices = [Index("active"), Index("updatedAt")])
data class ProblemStateEntity(
    @PrimaryKey val problemKey: String,
    val active: Boolean,
    val episodeId: String?,
    val firstObservedAt: Long?,
    val recoveredAt: Long?,
    val updatedAt: Long
)

@Entity(tableName = "export_snapshot", indices = [Index("status"), Index("expiresAt"), Index("createdAt")])
data class ExportSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val exportType: ExportType,
    val sourceDataVersion: Long,
    val createdAt: Long,
    val expiresAt: Long?,
    val status: ExportSnapshotStatus
)

@Entity(tableName = "export_snapshot_live_session",
    primaryKeys = ["snapshotId", "sessionStableId"],
    foreignKeys = [ForeignKey(entity = ExportSnapshotEntity::class, parentColumns = ["snapshotId"],
        childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("snapshotId")])
data class ExportSnapshotLiveSessionEntity(
    val snapshotId: String,
    val sessionStableId: String,
    val rowJson: String
)

@Entity(tableName = "restore_run", indices = [Index("backupId"), Index("status")])
data class RestoreRunEntity(
    @PrimaryKey val restoreRunId: String,
    val backupId: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: RestoreRunStatus,
    val finishReason: RestoreFinishReason?,
    val warningCount: Int
)

@Entity(
    tableName = "restore_conflict",
    foreignKeys = [ForeignKey(entity = RestoreRunEntity::class, parentColumns = ["restoreRunId"],
        childColumns = ["restoreRunId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("restoreRunId"), Index("type")]
)
data class RestoreConflictEntity(
    @PrimaryKey val conflictId: String,
    val restoreRunId: String,
    val type: RestoreConflictType,
    val backupStableId: String?,
    val backupUid: Long?,
    val localStableId: String?,
    val localUid: Long?,
    val decision: RestoreConflictAction?
)

@Entity(tableName = "auth_session", indices = [Index("authGeneration")])
data class AuthSessionEntity(
    @PrimaryKey val authSessionId: String,
    val authGeneration: Long,
    val accountHint: String?,
    val createdAt: Long,
    val revokedAt: Long?
)

@Entity(tableName = "audit_log",
    indices = [
        Index(value = ["operationId", "action"], unique = true),
        Index("occurredAt"),
        Index("targetType", "targetStableId")
    ])
data class AuditLogEntity(
    @PrimaryKey val auditId: String,
    val operationId: String,
    val actor: String,
    val action: String,
    val targetType: String,
    val targetStableId: String?,
    val occurredAt: Long,
    val detailJson: String?
)

@Entity(tableName = "monitoring_error_log",
    indices = [Index("occurredAt"), Index("problemKey"), Index("streamerStableId")])
data class MonitoringErrorLogEntity(
    @PrimaryKey val errorId: String,
    val problemKey: String,
    val streamerStableId: String?,
    val observationSequence: Long?,
    val occurredAt: Long,
    val errorCode: AppError,
    val detail: String?
)

@Entity(tableName = "application_error_log", indices = [Index("occurredAt")])
data class ApplicationErrorLogEntity(
    @PrimaryKey val errorId: String,
    val operationId: String?,
    val occurredAt: Long,
    val errorCode: AppError,
    val detail: String?
)

@Entity(tableName = "crash_record", indices = [Index("occurredAt")])
data class CrashRecordEntity(
    @PrimaryKey val crashId: String,
    val bootId: String,
    val occurredAt: Long,
    val processInstanceId: String?,
    val exitReason: String,
    val recoveredAt: Long?
)

@Entity(tableName = "health_event", indices = [Index("occurredAt"), Index("episodeId")])
data class HealthEventEntity(
    @PrimaryKey val healthEventId: String,
    val component: String,
    val fromStatus: MonitorHealthStatus?,
    val toStatus: MonitorHealthStatus,
    val occurredAt: Long,
    val episodeId: String?
)

@Entity(tableName = "recovery_session", indices = [Index("startedAt")])
data class RecoverySessionEntity(
    @PrimaryKey val recoverySessionId: String,
    val runtimeInstanceId: String,
    val monitorGeneration: Long,
    val status: RecoverySessionStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val finishReason: RecoveryFinishReason?
)
```

### 0.6.32 外围模块完整 DDL 基线

```sql
CREATE TABLE streamer_monitor_policy (
  streamerId INTEGER PRIMARY KEY,
  enabled INTEGER NOT NULL DEFAULT 1,
  intervalSeconds INTEGER,
  notifyStart INTEGER NOT NULL DEFAULT 1,
  notifyEnd INTEGER NOT NULL DEFAULT 1,
  notifyRound INTEGER NOT NULL DEFAULT 0,
  -- NULL = 跟随 monitoring_config 的全局默认确认次数；非空则完全覆盖全局（见 0.6.47.3）。
  startConfirmationCount INTEGER CHECK(startConfirmationCount IS NULL OR startConfirmationCount >= 1),
  endConfirmationCount INTEGER CHECK(endConfirmationCount IS NULL OR endConfirmationCount >= 1),
  aggregationEnabledOverride INTEGER,
  aggregationThresholdOverride INTEGER,
  overrideQuietHours INTEGER,
  updatedAt INTEGER NOT NULL,
  FOREIGN KEY(streamerId) REFERENCES streamer(id) ON DELETE CASCADE,
  CHECK (intervalSeconds IS NULL OR intervalSeconds > 0),
  CHECK (aggregationThresholdOverride IS NULL OR aggregationThresholdOverride >= 1)
);

CREATE TABLE follow_import_task (
  importTaskId TEXT PRIMARY KEY,
  authGeneration INTEGER NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('REQUESTED','FETCHING','PREVIEW','APPLYING','COMPLETED','FAILED','CANCELLED')),
  startedAt INTEGER NOT NULL,
  finishedAt INTEGER,
  stagingCount INTEGER NOT NULL DEFAULT 0,
  error TEXT
);
CREATE INDEX idx_follow_import_status ON follow_import_task(status, startedAt);

CREATE TABLE follow_import_staging (
  importTaskId TEXT NOT NULL,
  uid INTEGER NOT NULL,
  authGeneration INTEGER NOT NULL,
  payloadJson TEXT NOT NULL,
  createdAt INTEGER NOT NULL,
  PRIMARY KEY(importTaskId, uid),
  FOREIGN KEY(importTaskId) REFERENCES follow_import_task(importTaskId) ON DELETE CASCADE
);

CREATE TABLE statistics_cache (
  cacheKey TEXT PRIMARY KEY,
  statisticsVersion INTEGER NOT NULL,
  sourceDataVersion INTEGER NOT NULL,
  -- queryFingerprint = canonicalJson(queryRange, timezone, statisticsEligibility, filters, grouping, sourceDataVersion)
  -- cacheKey          = SHA-256(UTF8(queryFingerprint))；两者关系固定，见 0.6.17。
  queryFingerprint TEXT NOT NULL,
  timezone TEXT NOT NULL,
  eligibility TEXT NOT NULL CHECK(eligibility IN ('CONFIRMED_ONLY','INCLUDE_PROVISIONAL','INCLUDE_CORRECTED','INCLUDE_ESTIMATED')),
  calculatedAt INTEGER NOT NULL,
  payloadJson TEXT NOT NULL
);
CREATE INDEX idx_statistics_cache_version ON statistics_cache(sourceDataVersion);
CREATE INDEX idx_statistics_cache_calculated ON statistics_cache(calculatedAt);
CREATE UNIQUE INDEX idx_statistics_cache_fingerprint ON statistics_cache(queryFingerprint);

CREATE TABLE statistics_snapshot (
  snapshotId TEXT PRIMARY KEY,
  cacheKey TEXT,
  statisticsVersion INTEGER NOT NULL,
  sourceDataVersion INTEGER NOT NULL,
  timezone TEXT NOT NULL,
  eligibility TEXT NOT NULL CHECK(eligibility IN ('CONFIRMED_ONLY','INCLUDE_PROVISIONAL','INCLUDE_CORRECTED','INCLUDE_ESTIMATED')),
  queryFingerprint TEXT NOT NULL,
  generatedAt INTEGER NOT NULL,
  expiresAt INTEGER,
  payloadJson TEXT NOT NULL,
  -- 持久化快照：由 statistics_cache 定案后固化，供导出/跨版本对比使用；
  -- 与 cache 的区别是 snapshot 不参与缓存命中判定，只按 snapshotId 读取。
  FOREIGN KEY(cacheKey) REFERENCES statistics_cache(cacheKey) ON DELETE SET NULL
);
CREATE INDEX idx_statistics_snapshot_generated ON statistics_snapshot(generatedAt);
CREATE INDEX idx_statistics_snapshot_expires ON statistics_snapshot(expiresAt);

CREATE TABLE statistics_revision (
  statisticsVersion INTEGER PRIMARY KEY,
  sourceDataVersion INTEGER NOT NULL,
  createdAt INTEGER NOT NULL
);
CREATE INDEX idx_statistics_revision_source ON statistics_revision(sourceDataVersion);

CREATE TABLE notification_cooldown (
  problemKey TEXT PRIMARY KEY,
  lastNotifiedAt INTEGER,
  cooldownUntil INTEGER,
  recoveredNotified INTEGER NOT NULL DEFAULT 0,
  updatedAt INTEGER NOT NULL
);
CREATE TABLE problem_state (
  problemKey TEXT PRIMARY KEY,
  active INTEGER NOT NULL,
  episodeId TEXT,
  firstObservedAt INTEGER,
  recoveredAt INTEGER,
  updatedAt INTEGER NOT NULL
);
CREATE INDEX idx_problem_state_active ON problem_state(active, updatedAt);

CREATE TABLE export_snapshot (
  snapshotId TEXT PRIMARY KEY,
  exportType TEXT NOT NULL CHECK(exportType IN ('HTML','JSON','BACKUP','DIAGNOSTIC')),
  sourceDataVersion INTEGER NOT NULL,
  createdAt INTEGER NOT NULL,
  expiresAt INTEGER,
  status TEXT NOT NULL CHECK(status IN ('BUILDING','READY','CONSUMING','COMPLETED','FAILED','CANCELLED','EXPIRED'))
);
CREATE INDEX idx_export_snapshot_status ON export_snapshot(status, expiresAt);
CREATE INDEX idx_export_snapshot_created ON export_snapshot(createdAt);

-- 导出临时快照表（方案 B 的唯一实现载体，见 0.6.18 / 0.6.38）。
-- 新增其他实体的临时表时必须以同一形状扩展并同步更新本基线。
CREATE TABLE export_snapshot_live_session (
  snapshotId TEXT NOT NULL,
  sessionStableId TEXT NOT NULL,
  rowJson TEXT NOT NULL,
  PRIMARY KEY(snapshotId, sessionStableId),
  FOREIGN KEY(snapshotId) REFERENCES export_snapshot(snapshotId) ON DELETE CASCADE
);
CREATE INDEX idx_export_snapshot_ls ON export_snapshot_live_session(snapshotId);

CREATE TABLE restore_run (
  restoreRunId TEXT PRIMARY KEY,
  backupId TEXT NOT NULL,
  startedAt INTEGER NOT NULL,
  finishedAt INTEGER,
  status TEXT NOT NULL CHECK(status IN ('PRECHECK','WAITING_DECISIONS','APPLYING','COMPLETED','FAILED','CANCELLED')),
  finishReason TEXT CHECK(finishReason IS NULL OR finishReason IN ('COMPLETED','FAILED','CANCELLED','REJECTED','PARTIAL_WITH_WARNING')),
  warningCount INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_restore_run_backup ON restore_run(backupId, startedAt);
CREATE INDEX idx_restore_run_status ON restore_run(status);

CREATE TABLE restore_conflict (
  conflictId TEXT PRIMARY KEY,
  restoreRunId TEXT NOT NULL,
  type TEXT NOT NULL CHECK(type IN ('STABLE_ID_MATCH','STABLE_ID_DIFFERENT_UID_SAME','STABLE_ID_SAME_UID_DIFFERENT','STABLE_ID_AND_UID_DIFFERENT','UID_COLLISION')),
  backupStableId TEXT,
  backupUid INTEGER,
  localStableId TEXT,
  localUid INTEGER,
  decision TEXT CHECK(decision IS NULL OR decision IN ('USE_BACKUP','KEEP_LOCAL','MERGE','SKIP')),
  FOREIGN KEY(restoreRunId) REFERENCES restore_run(restoreRunId) ON DELETE CASCADE
);
CREATE INDEX idx_restore_conflict_run ON restore_conflict(restoreRunId, type);

CREATE TABLE auth_session (
  authSessionId TEXT PRIMARY KEY,
  authGeneration INTEGER NOT NULL,
  accountHint TEXT,
  createdAt INTEGER NOT NULL,
  revokedAt INTEGER
);
CREATE INDEX idx_auth_session_generation ON auth_session(authGeneration);

CREATE TABLE audit_log (
  auditId TEXT PRIMARY KEY,
  operationId TEXT NOT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  targetType TEXT NOT NULL,
  targetStableId TEXT,
  occurredAt INTEGER NOT NULL,
  detailJson TEXT
);
CREATE UNIQUE INDEX idx_audit_operation_once ON audit_log(operationId, action);
CREATE INDEX idx_audit_occurred ON audit_log(occurredAt);
CREATE INDEX idx_audit_target ON audit_log(targetType, targetStableId, occurredAt);

CREATE TABLE monitoring_error_log (
  errorId TEXT PRIMARY KEY,
  problemKey TEXT NOT NULL,
  streamerStableId TEXT,
  observationSequence INTEGER,
  occurredAt INTEGER NOT NULL,
  errorCode TEXT NOT NULL CHECK(errorCode IN ('NETWORK_UNAVAILABLE','NETWORK_TIMEOUT','API_REJECTED','API_INVALID_RESPONSE','DATABASE_ERROR','PERMISSION_DENIED','NOTIFICATION_UNAVAILABLE','BACKGROUND_EXECUTION_RESTRICTED','CONFIG_INVALID','RESTORE_CONFLICT','RESTORE_INCOMPATIBLE','EXPORT_FAILED','UNKNOWN')),
  detail TEXT
);
CREATE INDEX idx_mon_error_occurred ON monitoring_error_log(occurredAt);
CREATE INDEX idx_mon_error_problem ON monitoring_error_log(problemKey, occurredAt);
CREATE INDEX idx_mon_error_streamer ON monitoring_error_log(streamerStableId, occurredAt);

CREATE TABLE application_error_log (
  errorId TEXT PRIMARY KEY,
  operationId TEXT,
  occurredAt INTEGER NOT NULL,
  errorCode TEXT NOT NULL CHECK(errorCode IN ('NETWORK_UNAVAILABLE','NETWORK_TIMEOUT','API_REJECTED','API_INVALID_RESPONSE','DATABASE_ERROR','PERMISSION_DENIED','NOTIFICATION_UNAVAILABLE','BACKGROUND_EXECUTION_RESTRICTED','CONFIG_INVALID','RESTORE_CONFLICT','RESTORE_INCOMPATIBLE','EXPORT_FAILED','UNKNOWN')),
  detail TEXT
);
CREATE INDEX idx_app_error_occurred ON application_error_log(occurredAt);

CREATE TABLE crash_record (
  crashId TEXT PRIMARY KEY,
  bootId TEXT NOT NULL,
  occurredAt INTEGER NOT NULL,
  processInstanceId TEXT,
  exitReason TEXT NOT NULL,
  recoveredAt INTEGER
);
CREATE INDEX idx_crash_occurred ON crash_record(occurredAt);

CREATE TABLE health_event (
  healthEventId TEXT PRIMARY KEY,
  component TEXT NOT NULL,
  fromStatus TEXT CHECK(fromStatus IS NULL OR fromStatus IN ('HEALTHY','DEGRADED','RECOVERING','BLOCKED','STOPPED')),
  toStatus TEXT NOT NULL CHECK(toStatus IN ('HEALTHY','DEGRADED','RECOVERING','BLOCKED','STOPPED')),
  occurredAt INTEGER NOT NULL,
  episodeId TEXT
);
CREATE INDEX idx_health_event_occurred ON health_event(occurredAt);
CREATE INDEX idx_health_event_episode ON health_event(episodeId, occurredAt);

CREATE TABLE recovery_session (
  recoverySessionId TEXT PRIMARY KEY,
  runtimeInstanceId TEXT NOT NULL,
  monitorGeneration INTEGER NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('REQUESTED','RUNNING','COMPLETED','FAILED','TIMEOUT','CANCELLED')),
  startedAt INTEGER NOT NULL,
  finishedAt INTEGER,
  -- 取值必须来自 RecoveryFinishReason（见 0.6.2）。
  finishReason TEXT CHECK(finishReason IS NULL OR finishReason IN ('HEALTHY','TIMEOUT','FAILED','CANCELLED','SUPERSEDED'))
);
CREATE UNIQUE INDEX idx_recovery_one_running ON recovery_session(status) WHERE status = 'RUNNING';
CREATE UNIQUE INDEX idx_recovery_one_requested ON recovery_session(status) WHERE status = 'REQUESTED';
CREATE INDEX idx_recovery_started ON recovery_session(startedAt);
```

### 0.6.33 RecoverySession 最终状态机

```text
REQUESTED → RUNNING → COMPLETED
                   ↘ FAILED
                   ↘ TIMEOUT
                   ↘ CANCELLED
```

同一时刻全局最多一个 `RUNNING` RecoverySession。重复触发只能合并到当前实例。每次 RecoverySession 必须持久化 `recoverySessionId`、`runtimeInstanceId`、`monitorGeneration`、`startedAt/finishedAt/finishReason`。`TIMEOUT` 不等于健康恢复完成；完成后是否允许恢复正常 Tick 必须重新读取 OverallHealth。

**状态迁移必须是 CAS（此前只有状态图、没有任何 SQL）：**

```sql
-- 创建：REQUESTED 也必须有唯一性（idx_recovery_one_requested），因此插入冲突即视为"已有待启动的恢复"。
INSERT OR IGNORE INTO recovery_session(recoverySessionId, runtimeInstanceId, monitorGeneration, status, startedAt)
VALUES (:id, :runtimeInstanceId, :monitorGeneration, 'REQUESTED', :now);

-- REQUESTED → RUNNING：只有当前代际匹配才允许接管。
UPDATE recovery_session
SET status = 'RUNNING'
WHERE recoverySessionId = :id
  AND status = 'REQUESTED'
  AND monitorGeneration = :monitorGeneration;

-- RUNNING → 终态：必须带 status CAS，防止已被 TIMEOUT 结算的行被旧实例改写。
UPDATE recovery_session
SET status = :terminalStatus,          -- COMPLETED / FAILED / TIMEOUT / CANCELLED
    finishedAt = :now,
    finishReason = :finishReason       -- HEALTHY / TIMEOUT / FAILED / CANCELLED / SUPERSEDED
WHERE recoverySessionId = :id AND status = 'RUNNING';
```

```text
1. 影响行数为 0 时必须重读当前状态：可能是他人已接管、已被结算，或代际已变化；禁止重试覆盖。
2. 进程崩溃留下的 RUNNING 行必须在启动恢复中按"租约已过期"处理：
   直接结算为 TIMEOUT + finishReason = 'TIMEOUT'（不得伪造 HEALTHY），随后允许新建 REQUESTED。
3. 进入 RUNNING 前必须先取得 MonitoringLease 并校验 fencingToken；
   RecoverySession 的 monitorGeneration 必须与当时的 system_runtime_lock.monitorGeneration 相同。
4. 三张状态机表（recovery_session / restore_run / export_snapshot）都必须遵守同一条规则：
   终态不可逆；任何从终态回到非终态的写入都是缺陷。
```

### 0.6.33.1 RestoreRun / RestoreConflict 状态机（此前只有枚举，无迁移规则）

```text
PRECHECK → WAITING_DECISIONS → APPLYING → COMPLETED
         ↘ FAILED / CANCELLED（任一阶段均可进入）
```

```sql
-- 创建：同一 backupId 允许重复尝试，但必须能区分"同一次运行"，因此用 restoreRunId 标识。
INSERT INTO restore_run(restoreRunId, backupId, startedAt, status, warningCount)
VALUES (:restoreRunId, :backupId, :now, 'PRECHECK', 0);

-- 迁移必须带 status CAS。
UPDATE restore_run SET status = :next
WHERE restoreRunId = :id AND status = :expectedCurrent;

-- 冲突决策必须带"尚未决策"CAS，防止两个界面同时提交不同决策。
UPDATE restore_conflict SET decision = :action
WHERE conflictId = :conflictId AND decision IS NULL;
```

```text
1. PRECHECK → WAITING_DECISIONS 仅当存在至少一条未决策冲突时进入；无冲突则直接 APPLYING。
2. WAITING_DECISIONS → APPLYING 必须校验"所有冲突均已决策"，否则拒绝（不得用默认值补齐）。
3. APPLYING 是唯一允许写业务表的阶段，且必须在**单个维护事务**内完成（见 0.6.26）；
   失败必须整体回滚到 PRECHECK 之前的本地状态，并把 status 置 FAILED。
4. 崩溃恢复：启动时发现 status = APPLYING 的行，必须视为**不完整恢复**：
   回滚未提交事务、把该行置 FAILED + finishReason = 'FAILED'，并写 AuditLog；
   不得假设它已完成，也不得自动重跑（重跑必须由用户确认）。
5. COMPLETED 之后必须写 RestoreSummary 并推进 sourceDataVersion（恢复改变了业务事实）。
6. 幂等：同一个 backupId + 同一个 backup 文件校验和重复执行，必须能被识别
   （按 backupId + 文件 checksum 查询已有 COMPLETED 的 restoreRun），不得产生第二套等价业务事实。
```

### 0.6.33.2 ExportSnapshot 状态机（此前只有枚举，无迁移规则）

```text
BUILDING → READY → CONSUMING → COMPLETED
    ↘ FAILED / CANCELLED / EXPIRED（任一阶段均可进入）
```

```sql
UPDATE export_snapshot SET status = :next
WHERE snapshotId = :id AND status = :expectedCurrent;
```

```text
1. BUILDING → READY 必须与"临时表复制完成"在同一写事务内提交（见 0.6.18 步骤 3–4）；
   不存在"READY 但临时表为空"的合法状态。
2. READY → CONSUMING 表示已开始分页读取；此阶段不得再修改临时表内容。
3. 崩溃恢复：启动时 status ∈ {BUILDING, READY, CONSUMING} 的行一律视为**未完成的导出**：
   删除其临时表与文件、置 CANCELLED。不得尝试续传（导出结果对用户是原子的）。
4. EXPIRED 只能由清理任务按 expiresAt 写入，且只有 READY / CONSUMING 可以被置为 EXPIRED；
   COMPLETED 的行到期后只清理文件与临时表，status 保持不变（保留审计）。
5. 同一时刻允许存在多个导出快照吗？——**不允许**：导出必须进入 MaintenanceMode.BACKUP，
   而维护模式是全库唯一的（见 0.6.35）。因此实现必须用维护权作为导出互斥，而不是自造第二把锁。
```

### 0.6.34 Notification Delivery 崩溃恢复契约

通知投递不是数据库事务包住 `NotificationManager.notify()`，而是明确的跨事务状态机：

```text
PENDING/RETRY_WAIT
  ↓ 事务A：claim + lease + DeliveryAttempt(started)
PROCESSING
  ↓ 事务外：NotificationManager.notify()
  ├─ 返回成功 → 事务B：attempt=SENT + outbox=SENT
  ├─ 明确失败 → 事务B：attempt=FAILED + outbox=FAILED/RETRY_WAIT
  └─ 进程崩溃/结果未知 → lease 超时后标记 DELIVERY_UNKNOWN
```

启动恢复扫描 `PROCESSING + leaseExpired + unfinishedAttempt`：不能判断是否已展示，因此不得伪造 SENT；统一将本次 attempt 结论标记为 `DELIVERY_UNKNOWN`，再依据 expiresAt/重试策略决定是否 RETRY_WAIT。重新 claim 时**必须复用同一个 notificationId**。不得区分“notify 尚未调用”和“notify 已调用但结果未落库”，因为 Android 系统没有提供可靠的调用原子性；两者均按 UNKNOWN 处理。若用户设备通知权限/频道能力不可用，保留 FAILED；能力恢复事件由 `NotificationCapabilityMonitor` 触发分页扫描，只有 `expiresAt > now` 才转 RETRY_WAIT，且单次扫描有上限。超过 `expiresAt` 永久 EXPIRED。

**启动恢复的唯一写入者与语句。** 此前只规定"lease 超时后标记 DELIVERY_UNKNOWN"，未定义由谁、在哪张表、用哪条语句完成结算；现固定为一个恢复事务、两条语句：

```sql
-- 由 StartupRecovery 的 DeliveryRecoveryWorker 在单一事务内执行，是唯一允许写 recoveredBy 的地方。
-- 步骤 1：把租约过期仍未完成的 attempt 结算为 DELIVERY_UNKNOWN。
UPDATE notification_delivery_attempt
SET finishedAt = :now,
    result     = 'DELIVERY_UNKNOWN',
    recoveredBy = :runtimeInstanceId
WHERE finishedAt IS NULL
  AND outboxId IN (
      SELECT outboxId FROM notification_outbox
      WHERE status = 'PROCESSING' AND leaseUntilWall <= :now
  );

-- 步骤 2：按 expiresAt 决定重投或终止；绝不写 SENT。
UPDATE notification_outbox
SET status = CASE WHEN expiresAt <= :now THEN 'EXPIRED' ELSE 'RETRY_WAIT' END,
    nextAttemptAt = CASE WHEN expiresAt <= :now THEN NULL ELSE :now END,
    lastError = 'DELIVERY_UNKNOWN_ON_RECOVERY',
    leaseUntilWall = NULL,
    leaseUntilElapsed = NULL,
    workerInstanceId = NULL,
    deliveryAttemptId = NULL
WHERE status = 'PROCESSING' AND leaseUntilWall <= :now;
```

规则补充：

```text
1. recoveredBy 必须写入 runtimeInstanceId，使"由哪次启动结算"可追溯；不得留空。
2. 若同一 outbox 在同一启动周期内被结算两次，第二次因 finishedAt IS NOT NULL 而影响 0 行，天然幂等。
3. 恢复结算与 NotificationCapabilityMonitor 的 FAILED→RETRY_WAIT 扫描不得在同一事务中混用。
4. 结算后的再次 claim 必须复用 notification_id_registry 中同一 notificationId（见 0.6.12）。
5. outbox 进入 EXPIRED 后永不再进入发送流程；NotificationHistory 按 249.10.2 追加最终结果，不得删除历史。
```

### 0.6.35 CAS SQL 模板

关键更新必须在 SQL 中同时校验主播代际与全局 Runtime Lock；不能先 SELECT 再 UPDATE：

```sql
UPDATE streamer
SET confirmedLiveStatus = :newStatus,
    lastObservationResult = :observationResult,
    observationSequence = :observationSequence,
    lastConfirmedAt = :eventConfirmedAt,
    updatedAt = :updatedAt
WHERE id = :streamerId
  AND observationSequence < :observationSequence
  AND streamerMonitorGeneration = :streamerMonitorGeneration
  AND EXISTS (
      SELECT 1 FROM system_runtime_lock
      WHERE singletonId = 1
        AND monitorGeneration = :monitorGeneration
        AND fencingToken = :fencingToken
        AND maintenanceMode = 'OFF'
  );
```

若更新影响行数为 0，UseCase 必须把结果解释为 `STALE_OR_FENCED`，不得把它当成数据库异常，也不得无条件重试旧观察。创建 LiveEvent、更新 LiveSession、更新 PendingTransition、失效 ReliableMonitorInterval、推进 sourceDataVersion、创建 Outbox 必须在同一个业务事务中完成。

Lease 获取/维护模式切换同样使用条件 UPDATE：

```sql
-- 获取或接管租约：仅在无租约、或租约已过期、（或本实例就是当前持有者）时成功。
-- 注意 `monitoringLeaseId = :leaseId` 这一支不可省略：续期时未过期的租约仍属于本实例，
-- 否则续期语句会因 `leaseUntilWall < :now` 不成立而影响 0 行，导致实例把自己的租约判为失效。
UPDATE system_runtime_lock
SET monitoringLeaseId = :leaseId,
    runtimeInstanceId = :runtimeInstanceId,
    monitorGeneration = monitorGeneration + 1,
    fencingToken = :newFencingToken,
    leaseUntilWall = :leaseUntilWall,
    leaseUntilElapsed = :leaseUntilElapsed,
    leaseBootId = :bootId,
    updatedAt = :now
WHERE singletonId = 1
  AND maintenanceMode = 'OFF'
  AND (monitoringLeaseId IS NULL
       OR monitoringLeaseId = :leaseId
       OR leaseUntilWall < :now);
```

**续期必须是独立语句，不得复用上面的获取语句。** 续期只延长到期时间、**不递增 `monitorGeneration`、不更换 `fencingToken`**（否则每次心跳都会让在途写入全部 CAS 失败）：

```sql
-- 心跳续期：只有当前持有者可以续期；影响行数 0 表示租约已被他人接管，必须立即停止 tick。
UPDATE system_runtime_lock
SET leaseUntilWall = :newLeaseUntilWall,
    leaseUntilElapsed = :newLeaseUntilElapsed,
    updatedAt = :now
WHERE singletonId = 1
  AND monitoringLeaseId = :leaseId
  AND fencingToken = :fencingToken
  AND maintenanceMode = 'OFF';
```

续期契约：

```text
1. 续期周期必须显著小于租约时长（推荐 租约时长 / 3），否则一次 GC 停顿或系统休眠即可导致租约被他人抢走。
2. 每次 MonitoringTick 开始前必须先续期；续期影响行数为 0 → 立即放弃本轮全部写入（不进入 CAS），
   按 LEASE_LOST 处理，并写 HealthEvent。
3. 续期失败不得重试同一 leaseId；必须重新走"获取或接管"语句并接受新的 monitorGeneration / fencingToken。
4. 释放租约（用户关闭监控、进程正常退出）必须显式执行，避免等一个完整租约时长：
   UPDATE system_runtime_lock
   SET monitoringLeaseId = NULL, leaseUntilWall = NULL, leaseUntilElapsed = NULL,
       fencingToken = NULL, workerInstanceId = NULL, updatedAt = :now
   WHERE singletonId = 1 AND monitoringLeaseId = :leaseId;
5. leaseUntilElapsed 与 leaseUntilWall 必须同时维护：同一 bootId 内用 elapsed 判定（不受用户改系统时间影响），
   跨 bootId 只能用 wall 判定（见 0.6.27）。
```

**进入 MaintenanceMode 的 CAS（与"释放租约"的先后顺序不可颠倒）：**

```sql
-- 第一步：CAS 夺取维护权。只有当前无租约（或租约已过期）时才允许进入维护。
UPDATE system_runtime_lock
SET maintenanceMode = :mode,          -- DATABASE_REPAIR / BACKUP / RESTORE / MIGRATION
    monitoringLeaseId = NULL,
    leaseUntilWall = NULL,
    leaseUntilElapsed = NULL,
    fencingToken = NULL,
    workerInstanceId = NULL,
    updatedAt = :now
WHERE singletonId = 1
  AND maintenanceMode = 'OFF'
  AND (monitoringLeaseId IS NULL OR leaseUntilWall < :now);

-- 第二步：影响行数为 0 时必须放弃维护，禁止退化为"只改 maintenanceMode 字段"。
-- 第三步：退出维护必须写回 maintenanceMode = 'OFF'，并记录 AuditLog。
UPDATE system_runtime_lock
SET maintenanceMode = 'OFF', updatedAt = :now
WHERE singletonId = 1 AND maintenanceMode = :mode;
```

注意：由于 `CHECK (maintenanceMode = 'OFF' OR monitoringLeaseId IS NULL)`，夺取维护权与清空租约**必须在同一条 UPDATE 内完成**，分成两条语句会触发 CHECK 失败。这也是"不能存在内存 Boolean 互斥"的数据库级保障。

### 0.6.36 PendingTransition / ReliableInterval / Gap 并发约束

```text
同一主播：最多一个 ACTIVE ReliableMonitorInterval
同一主播：最多一个 OPEN MonitoringGapStreamer
同一主播：最多一个 ACTIVE LiveSession OPEN
同一主播：最多一个 ABANDONED OPEN Session
同一 Session：最多一个 START Event、最多一个 END Event
同一 eventId：最多进入一个 Aggregate
同一 Aggregate：最多一个 Batch Outbox
同一 eventKey：最多一个 Outbox
```

所有“最多一个”约束必须同时具有应用事务和 SQLite 部分唯一索引/UNIQUE 约束；不得只依靠单线程 Coroutine。

### 0.6.37 统计缓存 canonicalJson 唯一规则

`canonicalJson` 固定采用：对象 key UTF-8 字典序排序；数组保留业务顺序；缺省值显式使用 `null`；整数使用十进制无前导零；浮点禁止进入统计查询键；时区统一使用 IANA Zone ID 原文；枚举使用最终枚举名；日期范围统一为 epoch-millis。这样不同平台实现得到的 SHA-256 才能一致。

### 0.6.38 ExportSnapshot / 临时快照表

**已定稿决策**：导出唯一使用方案 B（临时快照表），见 0.6.18。创建 `export_snapshot` 业务记录后，在**一个写事务**内把范围内实体复制到下列临时表。

```sql
-- 本表是 §0.6.32 DDL 基线的一部分（在那里正式声明），此处给出定义以便阅读。
-- 与 export_snapshot 的关系：ON DELETE CASCADE，清理快照即清理其全部临时表。
CREATE TABLE export_snapshot_live_session (
  snapshotId TEXT NOT NULL,
  sessionStableId TEXT NOT NULL,
  rowJson TEXT NOT NULL,
  PRIMARY KEY(snapshotId, sessionStableId),
  FOREIGN KEY(snapshotId) REFERENCES export_snapshot(snapshotId) ON DELETE CASCADE
);
CREATE INDEX idx_export_snapshot_ls ON export_snapshot_live_session(snapshotId);
```

临时表扩展规则（**允许扩展，但必须遵循同一形状**）：

```text
1. 每种需要导出的实体各建一张临时表，命名固定为 export_snapshot_<业务表名>。
   当前基线只声明 export_snapshot_live_session；新增时必须同步写入 0.6.32 DDL 基线，
   不得只在迁移脚本里出现（否则违反 0.6.44 的"不得创建未声明表"）。
2. 每张临时表都必须满足：主键 (snapshotId, <该实体的稳定身份列>)、
   rowJson TEXT NOT NULL、外键 snapshotId → export_snapshot(snapshotId) ON DELETE CASCADE、
   以及 snapshotId 上的索引。
3. 所有临时表必须以 snapshotId 分区；禁止复用同一张表存放多次导出的数据。
4. 导出成功/取消/失败均由 Maintenance/Worker Cleanup 按 expiresAt 清理快照与其全部临时表；
   不得依赖 sourceDataVersion 判断分页一致性（它只是版本标识）。
```

### 0.6.39 BackupScope 与闭包

```text
ALL_DATA
→ Streamer + tags/groups + relations + LiveSession + LiveEvent + Corrections + Gaps + notification business history + Statistics snapshots（若启用）

SELECTED_STREAMERS / SINGLE_STREAMER
→ 必须包含所选 Streamer 的完整业务闭包：相关 Session、Event、Correction、ReliableInterval（历史）、GapStreamer 关系、用户策略、必要标签/分组关系。

DATE_RANGE
→ 只能恢复命中范围内可独立解释的记录；跨范围父子关系必须带父记录，否则该子记录拒绝恢复并进入 RestoreSummary 警告。
```

Runtime Lease、fencingToken、通知本机 notificationId、登录 access token、UI 偏好不得进入业务备份。

### 0.6.40 stableId/UID 冲突最终决策

`stableId 相同 + uid 不同` 的 MERGE 规则固定为：保留备份 `stableId`；当前 UID 采用用户选择的来源；历史 `LiveSession/LiveEvent/Correction` 全部按 stableId 重映射；旧本地主键不迁移。若无法保证引用闭包，则 MERGE 不可执行，必须改为 USE_BACKUP / KEEP_LOCAL / SKIP。

`stableId 不同 + uid 相同` 的 MERGE 规则固定为：只能产生一个 UID 实体；选定的 stableId 成为最终身份，另一方所有历史引用必须原子重映射。禁止先删除再新增导致引用断裂。

### 0.6.41 UI / 搜索 / 标签 / 分组 / 诊断最终引用

UI 与外围模块不允许直接操作 DAO 修改业务事实，必须调用 UseCase。所有页面只能通过 `stableId` 导航/绑定业务实体；本地 `id` 只用于数据库内部 JOIN。搜索、标签、分组、重点主播和主播策略全部引用同一 `StreamerEntity`。

通知点击 payload 使用明确 DTO：

```kotlin
data class NotificationNavigationPayload(
    val eventKey: String,
    val destination: String,
    val streamerStableId: String?,
    val sessionStableId: String?,
    val url: String?
)
```

URL 必须由 UrlPolicy 校验协议与 host；UI 禁止直接拼接远程字符串为可执行链接。

无障碍服务默认关闭，只能读取 UI，不得直接写入 Streamer/LiveSession/NotificationOutbox 等业务表。

### 0.6.42 历史章节引用规则

原规范中的 UI、搜索、标签、分组、设置、日志中心、健康中心、诊断包、通知跳转、无障碍服务等章节不再标记为“不可直接实现”。它们属于当前版本外围功能，但其实现只能引用 0.6.30–0.6.41 的最终契约。

历史章节若包含与最终 DTO/Entity 不一致的字段，只可作为交互说明；代码生成必须忽略其中的旧模型。

#### 0.6.42.1 废弃别名登记表（唯一权威）

以下名称在历史章节中出现过，**全部视为已废弃别名**。代码生成遇到别名时必须替换为右列的最终名称，不得为别名生成任何类型、列、表或枚举值。

| 废弃别名 | 唯一最终名称 | 说明 |
|---|---|---|
| `AggregateStatus` | `NotificationAggregateStatus` | 见 0.6.2 |
| `MonitorMode` | `MonitoringMode` | 见 0.6.2 |
| `MonitoringHealth` | `MonitorHealthStatus` | 见 0.6.2 |
| `StatisticsEligibility { INCLUDED, PARTIALLY_INCLUDED, EXCLUDED, UNKNOWN }` | `StatisticsInclusionState` | 同名但语义不同的第二套定义；`StatisticsEligibility` 只保留查询口径语义 |
| `DataFreshness.AGING` | （无） | 展示层派生值，不得持久化 |
| `MaintenanceMode { NONE, CONSISTENCY_CHECK, IMPORT }` | `OFF` / `DATABASE_REPAIR` / `RESTORE` | 见 0.6.2 |
| `RecoveryFinishReason { COMPLETED, ABORTED, STILL_UNAVAILABLE }` | `HEALTHY` / `CANCELLED` / `TIMEOUT` | 见 0.6.2 |
| `TimeDataSource` | `DataSource` | 永久废弃，无替代枚举 |
| `RoomNavigator.openRoom(context, roomUrl)` | `openRoom(context, roomUrl, preference): NavigationResult` | 见 §205.4 |
| `data class NotificationOutbox` | `NotificationOutboxEntity` | 见 0.6.9.1 |
| `data class NotificationAggregate` | `NotificationAggregateEntity` | 见 0.6.9.1 |
| `data class StreamerPendingTransition` | `StreamerPendingTransitionEntity` | 见 0.6.7；不得与 `PendingTransition` 枚举混用 |
| `MonitoringGap(id, startTime, affectedFrom/affectedTo, resolved, details)` | `MonitoringGapEntity(gapId, startedAt, …)` | 见 0.6.9 |
| `LiveSessionCorrection(id, sessionId, fieldName, version: Int)` | `LiveSessionCorrectionEntity(correctionId, sessionStableId, baseCorrectionVersion, …)` | 见 0.6.21 |
| `NotificationOutbox.claimedAt` | `processingStartedAt` | — |
| `NotificationOutbox.leaseUntilElapsedRealtime` | `leaseUntilElapsed` | — |
| `NotificationOutbox.leaseUntilWallClock` | `leaseUntilWall` | — |
| `NotificationAggregate.windowStartAt / windowEndAt` | `windowStartWall / windowEndWall` | 见 0.6.24；`Elapsed` 系列为 `windowStartElapsed / windowEndElapsed` |
| `NotificationAggregate.eventIds` | `notification_aggregate_event` 关系表 | 不是数据库字段 |
| `BatchRoomStatusResponse.responseValidity: ObservationResult` | `BatchResponseValidity` | 见 0.6.25 |
| `startedAt / endedAt`（LiveSession 时间字段） | `startTime / endTime` | 见 249.10 |
| `data class StatisticsSnapshot`（历史版） | `StatisticsSnapshotEntity` | 见 0.6.30 / 0.6.31 |
| `restore_finish_reason` 的任意字符串 | `RestoreFinishReason` | 见 0.6.2 |
| `data class StreamerEntity`（历史未声明） | `StreamerEntity`（见 0.6.24.1） | 原文只在 DDL 中存在，无 Kotlin 实体 |
| 导出的"长只读事务"方案 A | 方案 B（临时快照表） | 见 0.6.18；方案 A 不得实现 |
| 昵称的空值 / `''` / `未命名` 等临时写法 | `monitoring_config.placeholderText` | 见 249.9.1.1；唯一来源 |
| 用于判断"昵称是否占位"的独立布尔列 | `name == placeholderText` 比较 | 见 249.9.1.1；`nameLocked` 只表达"用户手工编辑" |
| 第二个"通知确认阈值" | 与状态确认共用同一阈值 | 见 0.6.47.3 |

任何未被本表登记、但与该表右列同义的新名称，一律视为违反 0.6.46 的“零自行发明”检查。

#### 0.6.42.2 文档内优先级与编号规则

```text
1. 唯一权威顺序：0.6 全部小节 > 规范主体（00–15 主章节） > 附录 A > §100 历史清单。
2. §15「全局一致性闭环索引」只是自检索引，不是规则来源；其"规范落点"列一律使用 0.6.x 编号，
   不得指向标题含"历史说明：不可直接实现"的章节。
3. §100「v2.3 实现闭环审查清单」已被本版 0.6.43 与 0.6.46 取代，仅作追溯，不得作为门槛。
4. 0.6.x 小节编号在文档中的物理顺序必须递增；引用后续小节时必须同时给出小节号与语义名，便于流式读取定位。
```
### 0.6.43 最终实现检查表（生成**前**门槛）

在 AI 进入代码生成前，必须逐项满足：

```text
【装配层】
[ ] AppDatabase 已声明全部 44 个 @Entity，exportSchema = true，version 已固定
[ ] DshTypeConverters 已覆盖 0.6.2 与 0.6.43 登记的全部枚举，序列化格式为 Enum.name
[ ] AppMigrations.ALL 已覆盖全部带 WHERE 的部分唯一索引（逐条与 0.6.4.3 比对）
[ ] journal_mode = WAL、busy_timeout、foreign_keys = ON 均已配置
[ ] 所有 @Dao 只在 0.6.15 登记，无第二处 DAO 定义

【模型唯一性】
[ ] 0.6 中每个最终 enum 都有 Kotlin 定义，且全文无第二套同名定义
[ ] 每个当前功能都有唯一 Entity/DTO 入口
[ ] 每个业务表都能映射到恰好一个 @Entity，且每个 @Entity 都能映射到恰好一张表
[ ] 0.6.29 + 0.6.32 覆盖所有持久化表，无未声明表（含临时快照表）
[ ] 0.6.42.1 废弃别名登记表中的名称，全文未被用作类型/列/枚举值

【约束与并发】
[ ] 所有部分唯一索引均由 Migration.execSQL 建立
[ ] 业务"最多一个"规则同时有 DB 与事务级保障
[ ] CAS SQL 同时校验 streamer generation + global fencing
[ ] 关键枚举列均有 CHECK，且字面量与 Kotlin 枚举名逐字一致
[ ] 每个指向业务表的 FK 子列都有索引（Room 编译零 warning）

【业务闭环】
[ ] 所有 Outbox 都有 eventType + payloadJson + nextAttemptAt + attemptCount + configVersion
[ ] 单事件通知幂等：sourceEventId 唯一索引存在，且无字符串解析 eventKey 的实现
[ ] 系统问题通知可入队（三种互斥形态 CHECK 已覆盖 problemKey）
[ ] Aggregate ↔ Outbox 双向不变量在**同一事务**内成立
[ ] Aggregate 崩溃恢复既不重复事件、也不遗漏 Outbox
[ ] 所有配置都能由 configVersion 完整重放（monitoring_config_revision 必备）
[ ] sourceDataVersion 触发集合封闭，且仅由单条原子 UPDATE 递增
[ ] StatisticsCache key 包含 query + timezone + eligibility + sourceDataVersion
[ ] queryFingerprint 与 cacheKey 的关系唯一（fingerprint 为 canonicalJson 原文）
[ ] ExportSnapshot 具有真实数据库一致性机制（长只读事务与临时快照表不得混用）
[ ] RestoreRun/Conflict 可跨进程恢复
[ ] FollowImportTask 与 authGeneration 隔离
[ ] Runtime state/Lease/token 不进入业务备份
[ ] 历史章节不得再创建第二套同名模型
```

### 0.6.43.1 两者关系

```text
0.6.43（本节）  = 进入代码生成前的设计校验：文档本身是否已自洽、是否已具备可生成条件。
0.6.46          = 代码生成完成前的实现校验：产物是否满足零自行发明的实现门槛。
两者不可互相替代；任一未全部勾选即不得进入下一阶段。
```



---

### 0.6.44 DDL 完整性规则与 Migration 顺序

`0.6.29` 与 `0.6.32` 两段 SQL 合并后才构成本项目的**完整唯一 Schema 基线**。任何代码生成任务不得创建未列在两段基线中的业务表。若功能需要新增表，必须先更新本节，再写 Entity/DAO。

Migration 顺序固定：

```text
1. 创建/迁移父表
2. 迁移业务字段与默认值
3. 创建实体/关系表
4. 创建普通索引
5. 最后创建所有 WHERE 部分唯一索引
6. 执行 PRAGMA foreign_keys=ON 后运行一致性检查
7. 成功后写入 schema/app migration 完成审计
```

Room `@Index` 只用于普通/完整唯一索引；所有部分唯一索引必须 `Migration.migrate()` 中 `execSQL()`。CHECK、FOREIGN KEY、UNIQUE 是数据库级约束；Repository/UseCase 仍需提供更友好的业务错误。

### 0.6.45 最终外围模块实现要求

设置页、日志中心、健康中心、搜索、标签、分组、重点主播、通知点击、诊断包、关注导入和可选无障碍服务均属于当前版本外围模块。它们不得自行定义业务身份或状态：

```text
主播身份 → Streamer.stableId
直播场次 → LiveSession.stableId
业务事件 → LiveEvent.eventId
通知幂等 → eventKey
统计缓存 → cacheKey
备份恢复 → backupId / restoreRunId / conflictId
关注导入 → importTaskId / authGeneration
审计 → auditId / operationId
故障事件 → episodeId / healthEventId
```

UI 与无障碍层只能调用 UseCase；DAO 不得暴露给 ViewModel 直接写业务状态。

### 0.6.46 AI Coding Agent 最终门槛（生成**完成前**门槛）

代码生成完成前必须通过“零自行发明”检查（与 0.6.43 互补，不得互相替代）：

```text
[ ] 每个 enum 都来自 0.6.2 或专用状态机章节
[ ] 每个业务表都有 0.6.29/0.6.32 DDL，且每张表都有对应 @Entity
[ ] 每个 Entity 都能映射到一个唯一表
[ ] 所有 WHERE 部分索引都有 Migration.execSQL
[ ] 没有 @Database 之外的第二个 RoomDatabase；没有 0.6.15 之外的第二个 DAO
[ ] 所有枚举字段都经 DshTypeConverters；没有依赖 ordinal 的隐式序列化
[ ] 所有关键并发写入都有 CAS WHERE 条件
[ ] Outbox/Attempt/History 的 eventKey 链路一致
[ ] notificationId 只能来自 registry（不得由 hashCode 或时间戳派生）
[ ] 单事件通知幂等由 sourceEventId 唯一索引保证，而非字符串解析
[ ] AggregateEvent binding 不产生第二套事件状态；RELEASED 后确实可重新绑定
[ ] StatisticsCache key 可由 canonicalJson 确定重算
[ ] ExportSnapshot 真正使用一致性读机制，且未混用两套语义
[ ] RestoreRun/Conflict 可跨进程恢复
[ ] UI/外围模块不创建第二套业务事实
[ ] 历史章节不得产生新模型、新表、新枚举
[ ] 0.6.42.1 别名登记表中的名称未出现在生成代码的任何标识符中

【0.6.47 三项已定稿决策】
[ ] 首次启动引导页存在，三选项文案与后果说明完整，且未选择时不得自动启动实时监控
[ ] backgroundKeepAliveChoice 落库 + 每次改选写 AuditLog，无绕过审计的直接 UPDATE
[ ] NO_GUARANTEE 必须记录 noGuaranteeAcknowledged / noGuaranteeAcknowledgedAt
[ ] 用户选择与系统能力不一致时界面显示"实际状态"，无静默降级
[ ] 确认次数为单一阈值：无"先发通知后改状态"的两段式实现，无第二个通知阈值列
[ ] 主播级确认次数为 NULL 时正确回退全局默认，且 Tick 期间不重读
[ ] 导出使用方案 B（临时快照表）；代码中不存在长只读事务导出分支
[ ] 昵称占位值只来自 monitoring_config.placeholderText，未写入历史快照字段与用户自定义字段
[ ] nameLocked = 1 时远程刷新确实跳过 name 字段
```

> 备注：本章靠后的 §0.6.30–§0.6.32 是外围模块的模型与 DDL 基线，与 §0.6.29 合并后才构成完整 Schema。
> 本节（0.6.46）与其后的 0.6.30–0.6.32 之间存在交叉引用，是因为 0.6.29 的 DDL 引用了 0.6.32 的外围表；
> 阅读顺序以**小节编号递增**为准，不以下文物理位置为准。

---

# 00 总则、范围与最终规则优先级

## 原规范 1：文档目的【历史说明：不可直接实现】

本文件不是普通的产品需求说明，而是一份可以直接交给代码型 AI（例如代码生成 Agent、IDE AI、Cursor 类工具）参考的**工程设计基线**。

AI 在实现项目时必须遵守以下原则：

1. 先按照本文件建立完整工程骨架，再逐模块实现，不要一次生成一个巨大 MainActivity。
2. UI、业务逻辑、网络访问、数据库、后台监控必须解耦。
3. Bilibili 接口不得散落在 UI 代码中，统一放在 data/remote 层，并通过接口抽象。
4. 不得把账号密码、SESSDATA、access token 等敏感凭证写入普通日志、普通 SharedPreferences 或明文导出文件。
5. 后台监控必须遵守 Android 系统限制，不得以“无限循环后台线程”作为核心方案。
6. 所有网络请求都必须有超时、重试、限流、错误分类和取消机制。
7. 主播开播/关播通知必须使用状态转换判断，不能每次轮询都重复通知。
8. 用户的主播、分组、标签、监控开关等数据必须以本地数据库为准，网络数据只负责刷新。
9. 所有 Bilibili 非稳定/非公开接口都必须集中在适配器层，以便将来替换。
10. 任何接口出现字段变化时，不得让数据库和 UI 直接依赖原始 JSON；必须经过 DTO -> Mapper -> Domain Model。

---

## 原规范 39：v2.0 升级目标与设计边界【历史说明：不可直接实现】

v2.0 的设计目标不是只覆盖“监控骨架”，而是完成一个可长期使用的本地优先主播监控工具。当前版本范围包括核心监控、可靠通知、主播管理、后台恢复、直播历史、基础统计、数据质量、HTML 导出、直播历史备份/恢复和可选关注导入。

工程目标：

```text
可靠性：不误判、不重复通知、异常可恢复、状态可诊断
可维护性：Bilibili 接口、后台机制、通知机制可替换
数据完整性：LiveSession 为历史事实来源，统计可重建，备份可恢复
可扩展性：外围能力通过接口扩展，不改变核心事实模型
```

v2.0 明确不承诺：

```text
- Android 可以 100% 保证进程永不被杀。
- Bilibili 私有接口永久稳定。
- 未获得官方权限时一定能够读取用户关注列表。
- WorkManager 可以替代秒级实时监控。
- 提供实时多人协作、实时多设备同步或账号隔离式监控。
```

跨设备场景仅指用户主动导入/恢复备份文件，不存在后台实时同步机制。

## 原规范 2：核心功能【历史说明：不可直接实现】

## 2.1 主播监控

- 监控指定 Bilibili 主播是否开播。
- 每个主播可独立开启/关闭监控。
- 支持直播状态：未开播、直播中、轮播中/其他状态。
- 默认将“直播中”视为开播事件；是否把轮播视为开播由全局设置决定。
- 检测到“未开播 -> 直播中”时，在符合对应通知策略时发送“正在直播”通知。
- 检测到“直播中 -> 未开播”时，只有当前连续可靠监控区间具备关播通知资格才发送关播通知。
- 通知必须去重，避免网络重试造成重复推送。

## 2.2 Bilibili 账号登录（仅用于可选的关注导入）

登录不是本软件核心监控功能的前置条件。用户无需登录即可手动添加主播并使用全部核心监控能力。登录功能仅用于可选的“导入我的关注”操作；导入完成后，监控系统不得依赖登录状态继续运行。

优先顺序：

1. 优先使用 Bilibili 官方开放平台提供的账号授权/Android SDK 能力。
2. 通过授权得到合法的用户身份凭证后，再调用当时开放平台实际提供的关注关系数据能力。
3. 如果官方公开能力不足，不得为了“功能看起来完整”而在客户端硬编码未经授权的私有接口。
4. 将登录功能设计成 AuthProvider 接口，以便未来切换实现方式。

Bilibili 开放平台当前文档明确提供账号授权、Android SDK 等能力；因此代码设计应首先围绕官方授权路线构建，而不是把账号密码登录作为第一方案。

---

## 原规范 3：推荐技术栈【历史说明：不可直接实现】

## 3.1 基础

- Kotlin
- Gradle Kotlin DSL
- Android Gradle Plugin：开发时使用最新稳定版
- compileSdk / targetSdk：使用开发时最新稳定 API
- minSdk：31

## 3.2 UI

- Jetpack Compose
- Material 3
- Navigation Compose
- ViewModel
- Kotlin Coroutines + Flow

## 3.3 数据

- Room：主播、标签、分组、状态历史等结构化数据
- DataStore：应用设置、用户偏好、轻量配置
- Kotlin Serialization：配置导入导出、DTO/JSON

Android 官方资料指出，DataStore 适合小型设置数据；复杂数据、部分更新和关系数据更适合 Room，因此本项目采用 Room + DataStore 的组合。

## 3.4 网络

- OkHttp
- Retrofit
- Kotlin Serialization Converter（或 Moshi，二选一）
- Coil：头像、直播封面、用户图片

## 3.5 后台

- WorkManager：低频、容错型后台任务
- Foreground Service：严格监控模式
- BroadcastReceiver：开机恢复、应用更新后的恢复逻辑
- NotificationManager：通知

## 3.6 依赖注入

- Hilt

---

## 原规范 4：总体架构【历史说明：不可直接实现】

采用：

**MVVM + Repository + UseCase + Clean Architecture 风格分层**

总体结构：

```text
UI / Compose
   |
ViewModel
   |
UseCase
   |
Repository
   |----------------------|
Local Data              Remote Data
   |                      |
Room / DataStore     Retrofit / OkHttp
                          |
                    Bilibili API

后台：
MonitoringService / WorkManager
           |
     MonitoringEngine
           |
    MonitorRepository
           |
      Room + API
           |
   State Transition
           |
      Notification
```

原则：

- UI 只负责展示和用户操作。
- ViewModel 不直接访问 Retrofit。
- Repository 不负责显示 Snackbar、Toast 或 Notification。
- MonitoringEngine 不直接操作 Compose。
- NotificationManager 封装为独立服务。
- 数据库 Entity 不直接暴露给 UI。

---

## 原规范 38：最终架构总结【历史说明：不可直接实现】

本项目应该被理解为：

```text
                 ┌──────────────────────┐
                 │      Compose UI      │
                 └──────────┬───────────┘
                            ↓
                    ┌──────────────┐
                    │   ViewModel  │
                    └───────┬──────┘
                            ↓
                    ┌──────────────┐
                    │    UseCase   │
                    └───────┬──────┘
                            ↓
                    ┌──────────────┐
                    │ Repository   │
                    └──────┬───────┘
                     ┌─────┴─────┐
                     ↓           ↓
                ┌─────────┐  ┌───────────┐
                │  Room   │  │ Bilibili  │
                │DataStore│  │   API     │
                └─────────┘  └───────────┘
                     ↑           ↑
                     │           │
              ┌──────┴───────────┴──────┐
              │     MonitoringEngine    │
              └────────────┬────────────┘
                           ↓
                  ┌──────────────────┐
                  │ State Transition │
                  │    Detector      │
                  └────────┬─────────┘
                           ↓
                   ┌──────────────┐
                   │ Notification │
                   └──────────────┘
```

这套结构的核心价值不是“代码看起来复杂”，而是让未来 Bilibili 接口变化、Android 后台策略变化、UI 改版以及后续外围能力扩展时，可以只替换对应模块，而不用重写整个程序。当前版本已经包含直播历史与统计，不应再把它们描述为尚未实现的未来功能。

**第一版最值得优先保证的三件事：**

```text
1. 直播状态检测准确，不误报。
2. 通知可靠，不重复。
3. 后台监控符合 Android 系统限制。
```

这三个基础打牢后，在同一可靠性架构上提供标签、分组、直播历史、统计、备份恢复以及可选关注导入等能力；可选账号能力不能成为核心监控依赖。


---

## 原规范 74：v2.0 最终架构图【历史说明：不可直接实现】

```text
                           ┌─────────────────────┐
                           │       Compose       │
                           │        UI           │
                           └──────────┬──────────┘
                                      ↓
                           ┌─────────────────────┐
                           │      ViewModel      │
                           └──────────┬──────────┘
                                      ↓
                           ┌─────────────────────┐
                           │       UseCase       │
                           └──────────┬──────────┘
                                      ↓
                           ┌─────────────────────┐
                           │     Repository      │
                           └───────┬───────┬─────┘
                                   ↓       ↓
                          ┌────────────┐  ┌───────────────┐
                          │    Room    │  │  Bilibili API │
                          └─────┬──────┘  └───────┬───────┘
                                │                 │
                 ┌──────────────┴─────────────────┴──────────────┐
                 │                                               │
                 ↓                                               ↓
        ┌──────────────────┐                           ┌──────────────────┐
        │ Monitoring       │                           │ Auth / Config    │
        │ Controller       │                           │ Manager          │
        └────────┬─────────┘                           └──────────────────┘
                 ↓
        ┌──────────────────┐
        │ Monitoring       │
        │ Scheduler        │
        └────────┬─────────┘
                 ↓
        ┌──────────────────┐
        │ MonitoringEngine │
        └────────┬─────────┘
                 ↓
        ┌──────────────────┐
        │ State Detector   │
        │ + Confirmation   │
        └────────┬─────────┘
                 ↓
         ┌───────────────┐
         │ LiveEvent     │
         └───────┬───────┘
                 ↓
         ┌───────────────┐
         │ Room TX       │
         │ Session       │
         │ History       │
         │ Outbox        │
         └───────┬───────┘
                 ↓
        ┌──────────────────┐
        │ Notification     │
        │ Dispatcher       │
        └──────────────────┘
```

后台执行层：

```text
             MonitoringController
                       │
          ┌────────────┼────────────┐
          ↓            ↓            ↓
      App 前台      系统允许的     WorkManager
                    FGS 模式       低频模式
          │            │            │
          └────────────┼────────────┘
                       ↓
               MonitoringScheduler
                       ↓
                MonitoringEngine
```

这套 v2.0 架构的核心不是“让代码更多”，而是明确把“业务状态”“监控运行状态”“Android 后台执行状态”“通知发送状态”四类状态分开。这样以后即使 Bilibili 接口、Android 后台政策或 UI 发生变化，核心业务仍然可以独立演进。


---

## 原规范 5：推荐工程目录【历史说明：不可直接实现】

```text
app/
└── src/main/java/com/example/bilimonitor/
    ├── App.kt
    │
    ├── core/
    │   ├── common/
    │   │   ├── Result.kt
    │   │   ├── AppError.kt
    │   │   ├── Constants.kt
    │   │   └── DispatcherProvider.kt
    │   ├── network/
    │   │   ├── NetworkClient.kt
    │   │   ├── NetworkErrorMapper.kt
    │   │   └── RateLimiter.kt
    │   ├── notification/
    │   │   ├── NotificationHelper.kt
    │   │   ├── NotificationChannels.kt
    │   │   └── NotificationActionHandler.kt
    │   ├── security/
    │   │   ├── SecureStorage.kt
    │   │   └── TokenProtector.kt
    │   └── util/
    │       ├── TimeUtil.kt
    │       ├── UriUtil.kt
    │       └── FuzzySearch.kt
    │
    ├── data/
    │   ├── local/
    │   │   ├── AppDatabase.kt
    │   │   ├── dao/
    │   │   │   ├── StreamerDao.kt
    │   │   │   ├── TagDao.kt
    │   │   │   ├── GroupDao.kt
    │   │   │   ├── StreamerTagDao.kt
    │   │   │   ├── StreamerGroupDao.kt
    │   │   │   ├── MonitorObservationDao.kt
    │   │   │   ├── LiveSessionDao.kt
    │   │   │   ├── MonitoringGapDao.kt
    │   │   │   ├── NotificationOutboxDao.kt
    │   │   │   └── NotificationHistoryDao.kt
    │   │   └── entity/
    │   │       ├── StreamerEntity.kt
    │   │       ├── TagEntity.kt
    │   │       ├── GroupEntity.kt
    │   │       ├── StreamerTagCrossRef.kt
    │   │       ├── StreamerGroupCrossRef.kt
    │   │       ├── StatusHistoryEntity.kt
    │   │       ├── MonitorObservationEntity.kt
    │   │       ├── LiveSessionEntity.kt
    │   │       ├── MonitoringGapEntity.kt
    │   │       ├── MonitoringGapStreamerEntity.kt
    │   │       ├── NotificationOutboxEntity.kt
    │   │       └── NotificationHistoryEntity.kt
    │   │
    │   ├── remote/
    │   │   ├── bilibili/
    │   │   │   ├── BiliLiveApi.kt
    │   │   │   ├── BiliAccountApi.kt
    │   │   │   ├── BiliFollowApi.kt
    │   │   │   ├── dto/
    │   │   │   └── mapper/
    │   │   └── auth/
    │   │       ├── AuthProvider.kt
    │   │       └── BiliOfficialAuthProvider.kt
    │   │
    │   └── repository/
    │       ├── StreamerRepositoryImpl.kt
    │       ├── MonitorRepositoryImpl.kt
    │       ├── AuthRepositoryImpl.kt
    │       ├── ConfigRepositoryImpl.kt
    │       └── HistoryRepositoryImpl.kt
    │
    ├── domain/
    │   ├── model/
    │   │   ├── Streamer.kt
    │   │   ├── ConfirmedLiveStatus.kt
    │   │   ├── ObservationResult.kt
    │   │   ├── Tag.kt
    │   │   ├── Group.kt
    │   │   ├── MonitorSettings.kt
    │   │   ├── LiveEvent.kt
    │   │   ├── LiveSession.kt
    │   │   ├── MonitoringGap.kt
    │   │   ├── ReliableMonitorInterval.kt
    │   │   ├── StreamerPendingTransition.kt
    │   │   ├── NotificationAggregate.kt
    │   │   └── NotificationOutbox.kt
    │   ├── repository/
    │   │   ├── StreamerRepository.kt
    │   │   ├── MonitorRepository.kt
    │   │   ├── AuthRepository.kt
    │   │   ├── ConfigRepository.kt
    │   │   ├── HistoryRepository.kt
    │   │   ├── NotificationRepository.kt
    │   │   ├── StatisticsRepository.kt
    │   │   └── DiagnosticsRepository.kt
    │   └── usecase/
    │       ├── RefreshStreamerUseCase.kt
    │       ├── RefreshAllStreamersUseCase.kt
    │       ├── DetectLiveEventUseCase.kt
    │       ├── ImportFollowingsUseCase.kt
    │       ├── SearchStreamersUseCase.kt
    │       ├── ImportConfigUseCase.kt
    │       └── ExportConfigUseCase.kt
    │
    ├── feature/
    │   ├── home/
    │   │   ├── HomeScreen.kt
    │   │   ├── HomeViewModel.kt
    │   │   └── components/
    │   │       ├── StreamerCard.kt
    │   │       ├── SearchBar.kt
    │   │       ├── FilterSheet.kt
    │   │       └── StatusBadge.kt
    │   ├── streamer/
    │   │   ├── StreamerDetailScreen.kt
    │   │   ├── StreamerEditScreen.kt
    │   │   └── StreamerViewModel.kt
    │   ├── tag/
    │   ├── group/
    │   ├── settings/
    │   ├── login/
    │   ├── config/
    │   └── history/
    │
    ├── background/
    │   ├── MonitoringService.kt
    │   ├── MonitoringEngine.kt
    │   ├── MonitoringWorker.kt
    │   ├── BootReceiver.kt
    │   └── ServiceController.kt
    │
    └── MainActivity.kt
```

---

# 01 产品边界与用户可见功能

## 原规范 40：功能优先级矩阵【历史说明：不可直接实现】

以下优先级描述的是当前 v2.0 的实现优先级，而不是“是否存在于文档设计中”。

## 40.1 P0：核心可靠闭环

```text
1. 手动添加/编辑/删除主播
2. 单主播监控开关
3. 开播/关播检测
4. 状态确认与误报保护
5. MonitoringController / Scheduler
6. MonitoringRuntimeState 与 MonitoringLease/fencing
7. NotificationOutbox 与通知去重/重试
8. 网络/API 错误隔离
9. 批量状态查询
10. 数据库一致性与 Migration
11. 直播历史 LiveSession
12. 状态/历史事件审计
13. 统计可重建与数据质量保护
14. 直播历史备份配置文件导出/恢复
15. HTML 直播历史导出
16. Android 12+ 后台/权限适配
```

## 40.2 P1：重要体验与管理能力

```text
1. 标签/分组
2. 搜索/筛选/排序
3. 主播详情页
4. 重点主播
5. 主播级监控/通知策略
6. 免打扰
7. 批量操作
8. 通知历史
9. 健康检查与诊断中心
10. 历史容量管理与自动清理
11. 配置导入预览/回滚
12. 数据修正、合并、拆分与审计
```

## 40.3 P2：可选增强

```text
1. Bilibili 官方账号授权与关注列表导入
2. 拼音搜索
3. 直播预约提醒
4. 自定义通知模板
5. 自动标签/自动分组
6. 云端持续监控
7. Web 控制台
8. 多平台推送
```

云端能力如未来实现，也必须作为独立服务边界处理；当前 Android 本地应用不设计实时多人协作或实时多设备同步。

## 原规范 71：当前版本功能总表【历史说明：不可直接实现】

本项目当前设计已经覆盖以下能力：

```text
主播管理
├── 手动添加
├── 编辑/软删除
├── 批量管理
└── 主播详情

监控
├── 开播/关播监控
├── 单主播监控开关
├── 监控模式与可配置检查间隔
├── 状态确认与误报保护
├── 网络/API 错误隔离
├── 批量查询
├── 自动恢复
└── 健康检查

分类
├── 标签
├── 分组
├── 重点主播
├── 搜索
├── 筛选
└── 排序

通知
├── 开播/下播
├── 5 秒批量开播聚合
├── 去重
├── Outbox
├── 重试/过期控制
├── 通知历史
└── 免打扰/主播级策略

数据
├── Room / DataStore
├── 应用配置导入/导出
├── LiveSession 直播历史
├── 数据修正与审计
├── 统计与数据质量
├── HTML 历史报告
├── 直播历史备份配置文件导出/恢复
├── Schema Version / Migration
└── 主播级历史容量与自动清理

账号
├── Bilibili 授权接口抽象
├── 可选登录
├── 退出登录
├── Token 安全存储
└── 关注导入（仅登录后按实际官方权限使用）

后台
├── App 前台监控
├── WorkManager 低频模式
├── Foreground Service 用户主动实时模式
├── Boot/Update 恢复
├── Recovery
└── Android 12+ / 13+ / 14+ / 15+ / 16+ 适配
```

## 原规范 73：v2.0 最终验收原则【历史说明：不可直接实现】

当 AI 声称“本项目开发完成”时，至少满足：

```text
[ ] 可以手动添加主播
[ ] 可以准确显示直播状态
[ ] OFFLINE → LIVE 能通知
[ ] LIVE → OFFLINE 能通知
[ ] API/网络 ERROR 不会触发下播通知
[ ] 同一事件不会重复通知
[ ] 通知失败可以重试或留下明确记录
[ ] 可以搜索主播
[ ] 可以标签、分组、筛选、排序
[ ] 可以进入直播间
[ ] 可以手动刷新
[ ] 可以批量管理
[ ] 可以查看主播详情
[ ] 可以查看直播历史
[ ] 可以查看通知历史
[ ] 可以查看监控健康状态
[ ] 可以导出/导入配置
[ ] 可以备份/恢复数据
[ ] 敏感凭证不会进入普通日志/导出文件
[ ] Android 13+ 通知权限得到正确处理
[ ] Android 14+ Foreground Service 类型正确声明
[ ] Android 15+ 不把 dataSync 当永久后台监控方案
[ ] 后台异常后能进入明确的降级/恢复状态
[ ] Room Migration 有测试
[ ] 核心状态机有单元测试
```

---

## 原规范 72：当前功能与未来功能总结【历史说明：不可直接实现】

## 当前 v2.0

第一层是核心监控：

```text
监控主播 → 获取直播状态 → 状态确认 → LiveEvent/LiveSession → Outbox → 通知
```

第二层是主播管理：

```text
添加/编辑/软删除 → 标签 → 分组 → 搜索 → 筛选 → 排序 → 批量管理
```

第三层是可靠性：

```text
状态确认 → Error 隔离 → CAS/fencing → Outbox → 去重/重试 → 健康检查 → 诊断
```

第四层是数据能力：

```text
LiveSession → 人工修正/审计 → 统计 → 数据质量 → HTML 导出 → 备份/恢复 → 容量管理
```

第五层是可选账号工具：

```text
官方授权 → 登录 → 关注导入
```

登录和关注导入不参与核心监控运行。

## 后续扩展

```text
直播预约
自定义通知模板
自动标签/自动分组
云端持续监控
Web 控制台
多平台通知
```

后续扩展不得改变当前“本地优先、无实时多人/多设备同步”的边界。

## 原规范 107：v2.0 更新后的最终产品理念【历史说明：不可直接实现】

本软件的核心不是“尽可能多地保存数据”，而是：

```text
可靠监控
可靠通知
可靠恢复
诚实统计
```

当软件知道：

```text
就告诉用户“确定”。
```

当软件只能判断：

```text
就告诉用户“约”。
```

当软件不知道：

```text
就告诉用户“不确定”。
```

这一原则应优先于一切 UI、统计和自动化功能。

---

## 原规范 22：当前数据与可选增强功能【历史说明：不可直接实现】

本章节不再把直播历史和基础统计视为“以后才实现”的能力。v2.0 已包含 LiveSession 历史、统计、数据质量、HTML 历史报告以及直播历史备份/恢复。

## 22.1 直播历史与基础统计

当前支持：

```text
主播
开播时间
下播时间
直播时长
本周/本月/自定义区间统计
平均直播时长
最近一次开播时间
数据质量与完整度
```

统计必须从 LiveSession 重建，统计缓存不能作为事实来源。

## 22.2 自定义通知模板

属于可选增强功能，可在当前通知 Outbox 体系之上扩展。

例如：

```text
{主播名} 开播啦！

标题：{直播标题}
分区：{分区}
```

## 22.3 免打扰时段

属于当前通知系统可选策略。监控仍继续执行，但在免打扰时段内不显示普通开播/关播通知；高优先级系统告警是否绕过免打扰由后续策略定义。

例如：

```text
23:00 - 07:00
```

## 22.4 重点主播特殊通知

可基于主播级通知策略扩展：

```text
声音更大
开启震动
单独通知频道
```

## 22.5 网络诊断

健康检查中心已经提供基础诊断能力；更详细的请求耗时、失败率和接口分类属于诊断中心的展示内容。

## 22.6 手动刷新

首页提供下拉刷新。手动刷新只触发一次显式检查，不改变自动监控计划。

## 22.7 健康检查

健康检查页面至少区分：

```text
监控服务：正常
通知权限：正常
后台权限：需检查
网络：正常
关注导入登录状态：正常（仅在使用关注导入时）
```

登录状态不是核心监控健康状态的一部分；用户未使用关注导入时不应因为未登录而显示错误。

## 原规范 13：主界面设计【历史说明：不可直接实现】

## 13.1 顶部

```text
┌───────────────────────────────┐
│ 哔哩哔哩主播监控      🔔  ⚙   │
├───────────────────────────────┤
│ 🔎 搜索主播…                  │
│ [全部] [直播中] [未开播] [筛选] │
└───────────────────────────────┘
```

## 13.2 主播卡片

每张卡片必须至少展示：

```text
┌────────────────────────────────┐
│ [头像]  主播名字      ●直播中  │
│        UID: 123456             │
│                                │
│ 直播标题：今天晚上来聊天       │
│ 分区：虚拟主播 / 杂谈          │
│                                │
│ [直播封面]                     │
│                                │
│ 标签：#VTuber  #游戏           │
│ 分组：我的最爱                  │
│                                │
│ [监控：开]       [进入直播间]  │
└────────────────────────────────┘
```

## 13.3 状态颜色

逻辑上定义状态，不把颜色写死在业务层：

```kotlin
enum class StatusVisual {
    UNKNOWN,
    OFFLINE,
    LIVE,
    ROUND,
    ERROR
}
```

UI 层自行映射颜色。

---

## 原规范 49：主播详情页【历史说明：不可直接实现】

首页卡片只展示摘要；详情页展示完整信息。

```text
StreamerDetailScreen
├── 头像 / 昵称 / UID
├── 当前直播状态
├── 当前直播间信息
├── 标签
├── 分组
├── 监控设置
├── 通知设置
├── 最近直播
├── 直播统计
└── 通知历史
```

支持操作：

```text
编辑
进入直播间
刷新
开启/关闭监控
管理标签
移动分组
```

---

## 原规范 21：设置页面【历史说明：不可直接实现】

建议包含：

## 监控

```text
监控模式
○ 关闭
○ 省电
● 实时

检查间隔
○ 30 秒
● 60 秒
○ 90 秒
○ 120 秒
○ 300 秒
```

可选检查间隔统一为 `30 / 60 / 90 / 120 / 300` 秒，默认 `60` 秒；省电模式的实际执行频率仍由系统调度决定，不受此秒级间隔保证。

## 通知

```text
开播通知       ON
关播通知       ON
首次同步通知   OFF
声音            ON
震动            ON
```

## 状态

```text
后台服务：运行中
最后成功检查：21:32:08
监控主播：57
最近一次错误：无
```

## 数据

```text
导入应用配置
导出应用配置
导出直播历史
恢复直播历史备份
历史容量设置
清理历史记录
恢复默认设置
```

“导出直播历史”与“恢复直播历史备份”属于直播历史数据管理；普通应用配置只迁移设置与主播管理配置。

## 系统

```text
电池优化设置
通知权限
开机启动
```

---

## 原规范 62：页面信息架构 v2【历史说明：不可直接实现】

```text
MainActivity
└── AppNavigation
    ├── Home
    ├── StreamerDetail
    ├── AddStreamer
    ├── EditStreamer
    ├── Groups
    ├── Tags
    ├── History
    │   ├── LiveHistory
    │   └── NotificationHistory
    ├── Diagnostics
    ├── Settings
    │   ├── MonitorSettings
    │   ├── NotificationSettings
    │   ├── DataSettings
    │   ├── AccountSettings
    │   └── About
    └── Login
```

首页负责“看”和“快速操作”；详情页负责“管理”；设置页负责“系统行为”。

---

## 原规范 63：UI/UX v2 建议【历史说明：不可直接实现】

首页顶部：

```text
哔哩哔哩主播监控

[搜索主播……]           [筛选]

● 8 个直播中    ○ 72 个未开播    ⚠ 1 个异常
```

卡片提供：

```text
头像 + 昵称
状态
UID
直播标题
分区
封面
标签
分组
⭐重点
监控开关
[进入直播间]
```

卡片不要堆叠过多按钮；高级操作放在详情页或长按多选菜单中。

---

## 原规范 14：搜索功能【历史说明：不可直接实现】

## 14.1 基本要求

支持：

```text
主播名字模糊搜索
```

例如：

```text
输入：dou
```

可以匹配：

```text
doudou
DouDou_Official
```

基础实现建议：

1. 统一转小写。
2. 去除空格和常见符号。
3. 判断 contains。
4. 对多个命中结果评分。

评分建议：

```text
完全相同：100
前缀匹配：80
连续子串：60
分词命中：40
其他：0
```

## 14.2 中文搜索增强

后续版本可以加入拼音搜索：

```text
“doudou” -> “豆豆”
```

但不作为 MVP 必须功能，避免一开始引入过多依赖。

---

## 原规范 15：标签系统【历史说明：不可直接实现】

主播可以有多个标签。

示例：

```text
游戏
歌回
杂谈
虚拟主播
重点关注
小号
```

支持：

- 新建标签
- 修改标签
- 删除标签
- 修改标签颜色
- 给主播增加/删除标签
- 根据标签筛选

筛选模式默认支持：

```text
任意匹配 OR
```

高级设置支持：

```text
全部匹配 AND
```

---

## 原规范 16：分组系统【历史说明：不可直接实现】

建议提供：

```text
全部主播
├── 重点关注
├── VTuber
├── 游戏主播
└── 其他
```

组具备：

- 名称
- 排序
- 折叠
- 主播数量

首页支持：

```text
按分组显示
```

以及：

```text
所有主播统一平铺
```

---

## 原规范 17：筛选系统【历史说明：不可直接实现】

支持组合筛选：

```text
直播状态：
[全部]
[直播中]
[未开播]
[轮播]
[异常]

标签：
[游戏]
[歌回]
[重点]

分组：
[重点关注]
[VTuber]
```

搜索 + 筛选可以组合：

```text
搜索：豆
AND
标签：游戏
AND
状态：直播中
```

最终形成：

```kotlin
data class StreamerFilter(
    val keyword: String = "",
    val statuses: Set<ConfirmedLiveStatus> = emptySet(),
    val tagIds: Set<Long> = emptySet(),
    val groupIds: Set<Long> = emptySet()
)
```

---

## 原规范 20：直播间跳转【历史说明：不可直接实现】

点击卡片“进入直播间”：

优先：

```text
Intent.ACTION_VIEW
https://live.bilibili.com/{roomId}
```

由系统选择用户已安装的 Bilibili App 或浏览器。

通知点击也使用同一套 DeepLink/Intent 逻辑。

必须统一实现（唯一最终签名见 §205.4；本节历史版本只有 2 个参数且非 suspend，已废弃）：

```kotlin
// 唯一最终签名见 §205.4；本节不得重复声明。RoomOpenPreference 与 NavigationResult 同样定义在 §205.4。
//   suspend fun openRoom(context: Context, roomUrl: String, preference: RoomOpenPreference): NavigationResult
```

避免卡片和通知分别写一套跳转代码。

---

## 原规范 50：主播级监控策略【历史说明：不可直接实现】

全局设置只是默认值；主播可以覆盖部分设置。

```kotlin
// 唯一实体是 StreamerMonitorPolicyEntity（见 0.6.30 / 0.6.32）。
// 本节历史版本的差异已废弃：notifyStart/notifyEnd/notifyRound 在最终实体中为非空 Boolean，
// 且必须补齐 startConfirmationCount / endConfirmationCount / aggregationEnabledOverride /
// aggregationThresholdOverride 与 updatedAt。null 语义只保留给：
//   intervalSeconds（null = 跟随全局）、aggregationEnabledOverride、aggregationThresholdOverride。
data class StreamerMonitorPolicy(
    val streamerId: Long,
    val enabled: Boolean,
    val intervalSeconds: Int?,              // null = 跟随全局监控间隔
    val notifyStart: Boolean,
    val notifyEnd: Boolean,
    val notifyRound: Boolean,
    val startConfirmationCount: Int,
    val endConfirmationCount: Int,
    val aggregationEnabledOverride: Boolean?,
    val aggregationThresholdOverride: Int?,
    val overrideQuietHours: Boolean?,
    val updatedAt: Long
)
```

其中 `null` 表示跟随全局设置。

最终有效配置：

```text
主播设置 > 分组策略 > 全局设置
```

v2.0 可以先实现“主播设置 > 全局设置”；分组策略作为 P2 预留。

---

## 原规范 51：重点主播【历史说明：不可直接实现】

新增独立字段：

```text
isFavorite BOOLEAN
```

与标签不同：

```text
标签 = 用户自定义分类
重点主播 = 系统级优先级
```

重点主播可用于：

```text
首页置顶
更高刷新优先级
特殊通知声音
震动增强
突破免打扰（用户明确开启时）
```

---

## 原规范 52：搜索、筛选、排序升级【历史说明：不可直接实现】

统一模型：

```kotlin
data class StreamerQuery(
    val keyword: String = "",
    val statuses: Set<ConfirmedLiveStatus> = emptySet(),
    val tagIds: Set<Long> = emptySet(),
    val groupIds: Set<Long> = emptySet(),
    val favoriteOnly: Boolean = false,
    val monitoringOnly: Boolean = false,
    val sort: StreamerSort = StreamerSort.DEFAULT
)
```

排序：

```kotlin
enum class StreamerSort {
    DEFAULT,
    LIVE_FIRST,
    FAVORITE_FIRST,
    NAME_ASC,
    NAME_DESC,
    RECENTLY_STARTED,
    RECENTLY_ENDED,
    RECENTLY_ADDED,
    RECENTLY_UPDATED,
    CUSTOM
}
```

默认排序：

```text
重点主播
↓
直播中
↓
最近更新
```

---

## 原规范 53：批量操作【历史说明：不可直接实现】

进入多选模式后支持：

```text
批量开启监控
批量关闭监控
批量添加标签
批量移除标签
批量移动分组
批量设为重点
批量取消重点
批量删除
```

删除必须再次确认，并明确显示数量：

```text
确认删除 27 名主播？
此操作不会删除你的 Bilibili 关注关系。
```

---

# 02 身份、领域模型与数据库事实来源

## 原规范 6：核心数据模型【历史说明：不可直接实现】

## 6.1 主播

> 【最终实现约束】Streamer 的最终可实现模型以 0.6 的数据库定义为准。下列历史领域模型仅用于说明；其中 `pendingTransition/pendingConfirmationCount` 已从 Streamer 移除，统一由 `streamer_pending_transition` 持久化。

```text
历史模型：不可直接生成代码。
最终字段来源：streamer 表 + streamer_pending_transition 表。
```

## 6.2 直播状态

```kotlin
enum class ConfirmedLiveStatus {
    UNKNOWN,
    OFFLINE,
    LIVE,
    ROUND
}

enum class ObservationResult {
    SUCCESS,
    TIMEOUT,
    NETWORK_ERROR,
    API_ERROR,
    PARTIAL,
    INVALID
}

enum class PendingTransition {
    NONE,
    SUSPECTED_OFFLINE,
    SUSPECTED_LIVE
}

`ERROR` 不属于确认业务状态。网络、API、解析、超时、部分响应等异常只能写入 `ObservationResult`、诊断日志和健康状态，不能写入 `confirmedLiveStatus`。

若 UI 需要显示“异常”，必须由 `ObservationResult + MonitoringHealth` 派生，不得扩展第二个业务状态真相字段。数据库迁移时如果历史数据库可能存在旧字段值 `ERROR`，必须将其转换为：保留最近一次非 ERROR 的 `confirmedLiveStatus`；若不存在可信历史状态则使用 `UNKNOWN`，同时记录一次迁移诊断事件。
```



### 6.2.1 主播运行态与连续可靠监控区间

为避免把“本次观察异常”“候选状态”“已确认业务状态”混成一个字段，运行态必须形成以下持久化闭环：

```kotlin
data class StreamerRuntimeState(
    val streamerId: Long,
    val confirmedLiveStatus: ConfirmedLiveStatus,
    val lastObservationResult: ObservationResult,
    val pendingTransition: PendingTransition,
    val pendingCount: Int,
    val observationSequence: Long,
    val streamerMonitorGeneration: Long,
    val updatedAt: Long
)

data class ReliableMonitorInterval(
    val intervalId: String,
    val streamerStableId: String,
    val sessionStableId: String?,
    val startedAt: Long,
    val invalidatedAt: Long?,
    val invalidReason: ReliableIntervalInvalidReason?,
    val lastReliableObservationSequence: Long,
    val monitorGeneration: Long,
    val fencingToken: String
)
```

`ReliableMonitorInterval` 是**关播通知资格的持久化事实**。它不等价于 `MonitoringGap`：Gap 表示系统/主播不可可靠观察；Interval 表示当前一段仍可为“确认下播通知”提供资格的连续可靠观察区间。

规则：

```text
确认 LIVE
→ 创建新的有效 Interval

每次 SUCCESS 且结果足以维持可靠观察
→ 更新 lastReliableObservationSequence

TIMEOUT / NETWORK_ERROR / API_ERROR / PARTIAL / INVALID / MonitoringGap
→ 在同一事务中使当前 Interval 失效

后续恢复并确认仍 LIVE
→ 创建新的 Interval

最终确认 OFFLINE
→ 仅检查当前 Interval 是否仍有效且属于当前 Session
```

---

## 6.3 标签

```kotlin
data class Tag(
    val id: Long,              // 本地数据库主键
    val stableId: String,      // 跨设备恢复稳定 ID
    val name: String,
    val colorHex: String?,
    val sortOrder: Int
)
```

## 6.4 分组

```kotlin
data class Group(
    val id: Long,              // 本地数据库主键
    val stableId: String,      // 跨设备恢复稳定 ID
    val name: String,
    val sortOrder: Int,
    val collapsed: Boolean
)
```

建议主播与标签为多对多；主播与分组可以先设计为多对多，后续也可根据 UI 改成“一主播一个主分组 + 多标签”。数据库结构不要提前锁死。

---

## 原规范 7：Room 数据库设计【历史说明：不可直接实现】

## 7.1 streamer 表

字段建议：

```text
id                 INTEGER PRIMARY KEY
stableId           TEXT UNIQUE NOT NULL
uid                INTEGER UNIQUE NOT NULL
roomId             INTEGER
shortRoomId        INTEGER
name               TEXT NOT NULL
avatarUrl          TEXT
roomTitle          TEXT
parentAreaName     TEXT
areaName           TEXT
coverUrl           TEXT
liveUrl                    TEXT
confirmedLiveStatus        TEXT NOT NULL DEFAULT 'UNKNOWN'
lastObservationResult      TEXT NOT NULL DEFAULT 'INVALID'
observationSequence        INTEGER NOT NULL DEFAULT 0
streamerMonitorGeneration INTEGER NOT NULL DEFAULT 0
monitoringEnabled          INTEGER NOT NULL DEFAULT 1
lastCheckedAt      INTEGER
lastLiveStartedAt  INTEGER
lastLiveEndedAt    INTEGER
lastError          TEXT
deletedAt          INTEGER
createdAt          INTEGER NOT NULL
updatedAt          INTEGER NOT NULL
```

## 7.2 tag 表

```text
id INTEGER PRIMARY KEY
stableId TEXT UNIQUE NOT NULL
name TEXT NOT NULL
colorHex TEXT
sortOrder INTEGER NOT NULL DEFAULT 0
```

## 7.3 group 表

```text
id INTEGER PRIMARY KEY
stableId TEXT UNIQUE NOT NULL
name TEXT NOT NULL
sortOrder INTEGER NOT NULL DEFAULT 0
collapsed INTEGER NOT NULL DEFAULT 0
```

## 7.4 主播-标签关系

```text
streamerId INTEGER
 tagId INTEGER
PRIMARY KEY(streamerId, tagId)
```

## 7.5 主播-分组关系

```text
streamerId INTEGER
 groupId INTEGER
PRIMARY KEY(streamerId, groupId)
```

## 7.6.0 Tag / Group 名称与稳定身份

`Tag.stableId` / `Group.stableId` 是跨设备身份；名称只是可编辑显示属性，不得因为名称相同就推断为同一实体。数据库不使用全局 `name UNIQUE` 作为跨设备身份判定。

恢复时：

```text
stableId 相同 → 同一实体，按备份规则恢复
stableId 不同但 name 相同 → 允许并存，除非用户显式执行合并
```

如产品后续需要限制同名标签/分组，应通过明确的业务作用域规则处理，而不是拿显示名称替代稳定业务身份。

## 7.6.0 外键与删除策略

关系表必须在数据库层声明真实外键，不得只依赖应用层校验。

```text
StreamerTagCrossRef.streamerId → streamer.id
StreamerTagCrossRef.tagId → tag.id
StreamerGroupCrossRef.streamerId → streamer.id
StreamerGroupCrossRef.groupId → group.id
LiveSession.streamerId → streamer.id
StatusHistory.streamerId → streamer.id
MonitorObservation.streamerId → streamer.id
```

默认软删除只修改 `Streamer.deletedAt`，不得通过级联删除把历史业务数据一并清掉。只有用户明确执行“彻底删除全部历史”时，才由统一维护事务清理依赖数据并执行一致性检查。

## 7.6.1 本地主键与跨设备稳定 ID

`id` 只用于当前数据库内部关联；所有需要进入备份文件并参与跨设备恢复的实体必须同时拥有稳定业务 ID。稳定 ID 在实体首次创建时生成，后续导入、迁移、软删除和恢复都不得改变。

```text
当前数据库：id → 本地外键关系
备份/恢复：stableId / eventId / aggregateId → 跨设备身份
```

恢复时严禁直接比较或复用不同设备上的本地自增 `Long id`。

## 7.6 LiveSession OPEN 唯一性实现说明

数据库必须保证同一 `streamerId` 最多存在一个 `ACTIVE` 且 `endTime IS NULL` 的 LiveSession；`ABANDONED + endTime IS NULL` 允许存在且同一主播最多一条。

最终唯一索引：

```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_live_session_one_active_open
ON live_session(streamerId)
WHERE endTime IS NULL AND lifecycleState = 'ACTIVE';
```

该部分索引不能通过 Room `@Index(unique=true)` 表达，必须在 Migration 中 `execSQL` 创建。不得使用 `UNIQUE(streamerId, endTime)`，也不得使用 `WHERE endTime IS NULL` 的全体唯一索引。

## 7.6 状态历史

用于诊断、直播历史形成以及统计源数据追踪。

```text
historyId TEXT PRIMARY KEY
streamerId INTEGER NOT NULL
streamerStableId TEXT NOT NULL
sessionStableId TEXT
oldStatus TEXT NOT NULL
newStatus TEXT NOT NULL
observationResult TEXT NOT NULL
occurredAt INTEGER NOT NULL
observationSequence INTEGER NOT NULL
monitorGeneration INTEGER NOT NULL
streamerMonitorGeneration INTEGER NOT NULL
fencingToken TEXT NOT NULL
transitionId TEXT
createdAt INTEGER NOT NULL
```

---

## 原规范 61：数据库 v2.0 建议结构【历史说明：不可直接实现】

建议主要表：

```text
streamer
streamer_monitor_policy
streamer_tag_cross_ref
tag
group
streamer_group_cross_ref
live_session
status_history
notification_outbox
notification_history
app_event_log
```

辅助一致性表：`streamer_pending_transition`、`reliable_monitor_interval`、`notification_id_registry`、`notification_aggregate_event`、`system_runtime_lock`。

其中核心字段必须分别为：`streamer_pending_transition(streamerId PRIMARY KEY, monitorGeneration, streamerMonitorGeneration, pendingTransition, confirmationCount, lastObservationSequence, updatedAt)`；`reliable_monitor_interval(intervalId PRIMARY KEY, streamerStableId, sessionStableId, startedAt, invalidatedAt, invalidReason, lastReliableObservationSequence, monitorGeneration, fencingToken)`；`notification_id_registry(eventKey PRIMARY KEY, notificationId UNIQUE, createdAt)`。
`notification_aggregate_event` 必须保证 `eventId` 全局唯一归属；`system_runtime_lock` 必须为单行状态表。

关系：

```text
Streamer
 ├── 1 : 1 MonitorPolicy
 ├── N : N Tag
 ├── N : N Group
 ├── 1 : N LiveSession
 ├── 1 : N StatusHistory
 ├── 1 : N NotificationHistory
 └── 1 : N NotificationOutbox
```

所有外键关系必须根据删除策略明确。默认软删除不级联删除业务历史：

```text
软删除 → 保留 LiveSession / StatusHistory / NotificationHistory / AuditLog
硬删除 → 仅在用户明确确认“删除全部历史”后，在同一事务中按依赖顺序删除
```

或者采用软删除时：

```text
deletedAt != null
```

v2.0 最终规则：默认“删除主播”只将主播从监控列表移除并保留历史数据，推荐通过 `deletedAt` 软删除实现，以避免 `LiveSession`、`StatusHistory`、`NotificationHistory` 等外键失效。

只有用户明确选择“彻底删除主播及全部历史”时，才执行物理删除和依赖数据清理，并进行二次确认。

---

## 原规范 95：数据库新增表建议【历史说明：不可直接实现】

v2.0 可靠性模块建议至少增加：

```text
live_session
monitoring_gap
monitoring_gap_streamer
crash_record
notification_outbox
notification_history
health_event
app_audit_log
live_session_corrections
statistics_revisions
statistics_snapshots
monitoring_lease
statistics_cache（可选）
```

其中核心表至少应满足以下稳定性字段要求：

```text
live_session
  id INTEGER PRIMARY KEY
  stableId TEXT UNIQUE NOT NULL
  streamerId INTEGER NOT NULL
  startTime INTEGER
  endTime INTEGER
  ...

notification_outbox
  id INTEGER PRIMARY KEY
  eventKey TEXT UNIQUE NOT NULL
  deliveryAttemptId TEXT
  workerInstanceId TEXT
  leaseUntilElapsed INTEGER
  leaseUntilWall INTEGER
  leaseBootId TEXT
  status TEXT NOT NULL
  ...
```

`stableId` 用于跨设备恢复；`deliveryAttemptId` 用于一次 Outbox Claim 的发送尝试 fencing。

其中：

```text
LiveSession       用户业务数据
MonitoringGap     可靠性数据
CrashRecord       进程生命周期数据
Outbox            通知可靠投递数据
NotificationHistory 通知审计数据
HealthEvent       健康状态变化
AuditLog          系统操作记录
StatisticsCache   可重建缓存
```

---

## 原规范 154：新增数据库表【历史说明：不可直接实现】

建议新增：

```text
live_session_corrections
monitoring_gaps
crash_records
statistics_revisions
statistics_snapshots
```

如已有相关表，则通过 Migration 扩展。

## live_session_corrections

```text
id
sessionId
correctedAt
fieldName
oldValue
newValue
reason
version
```

## statistics_revisions

```text
id
revision
createdAt
reason
```

## statistics_snapshots

```text
id
revision
rangeStart
rangeEnd
calculatedAt
payload
```

统计快照仅作为缓存。

---

## 原规范 43：LiveSession 直播场次模型【历史说明：不可直接实现】

`StatusHistory` 用于系统状态变化和诊断；`LiveSession` 用于用户理解“一场直播”。

```kotlin
// 唯一实体是 LiveSessionEntity（见 0.6.3），字段与 NULL 语义必须以 0.6.3 为准。
// LiveSessionLifecycleState 的唯一最终定义也在 0.6.3；本节不得重复声明。
// 本节历史版本中的以下写法已废弃，不得照抄：
//  - startSource/endSource 为 String?（应为 DataSource?，枚举名原文入库）
//  - startConfidence 为非空 DataConfidence（DDL 允许 NULL，宿主类型必须可空）
//  - 缺少 durationSource/durationConfidence/startLocked/endLocked/durationLocked/
//    titleAtEnd/areaAtEnd/coverUrlAtEnd/confirmedOfflineAt/autoUpdateProtected/
//    correctionVersion/createdAt/updatedAt/note 等 0.6.3 已声明字段
//  - 重复声明 enum class LiveSessionLifecycleState（已在 0.6.3 定义，本节不再重复）
// 本节的 data class LiveSession 声明已移除（避免与 0.6.3 产生第二个同名类型）；
// 字段全集、NULL 语义与索引一律以 LiveSessionEntity + 0.6.29 DDL 为准。
//
// 唯一保留的关键约束（其余见 0.6.3）：
//   id          本地主键，仅用于本机 JOIN，不得作为跨设备身份
//   stableId    跨设备备份/恢复稳定 ID
//   sessionKey  业务幂等键，唯一，规则见 0.6.4
//   endTime == null 表示 OPEN，且是 OPEN/CLOSED 的唯一事实来源
```

建议增加表：

```text
live_session
```

状态历史必须携带并发上下文：

```kotlin
data class StatusHistory(
    val historyId: String,
    val streamerStableId: String,
    val sessionStableId: String?,
    val oldStatus: ConfirmedLiveStatus,
    val newStatus: ConfirmedLiveStatus,
    val observationResult: ObservationResult,
    val occurredAt: Long,
    val observationSequence: Long,
    val monitorGeneration: Long,
    val fencingToken: String,
    val transitionId: String?
)
```

状态、历史与业务事件必须同事务提交；StatusHistory 不反向覆盖确认状态。

典型生命周期：

```text
OFFLINE
   ↓
LIVE confirmed
   ↓
创建 LiveSession
   ↓
LIVE
   ↓
OFFLINE confirmed
   ↓
填写 endTime / durationSeconds
```

如果 App 曾经离线，不能凭空假设准确的开始或结束时间；必须记录来源与置信度。

---

## 原规范 108：直播历史自定义记录系统（新增 P0）【历史说明：不可直接实现】

直播历史不仅来自自动监控，也允许用户手工创建、修改和校正。

本功能的目标是：

```text
自动监控负责尽可能发现直播
用户负责在必要时提供真实时间
软件负责计算、校验、保存和标记数据来源
```

不得因为用户手工修正而删除原始监控证据；原始观察记录和人工修正结果必须分离保存。

## 108.1 三项核心时间字段

每一场直播至少支持：

```text
startTime     开播时间
endTime       下播时间
duration      直播时长
```

其中用户只需要准确填写其中任意两个，第三个由软件自动计算。

允许组合：

```text
开播时间 + 下播时间
开播时间 + 直播时长
下播时间 + 直播时长
```

禁止：

```text
三个字段全部为空
```

## 108.2 自动计算规则

### 开播时间 + 下播时间

```text
经计算：
duration = endTime - startTime
```

### 开播时间 + 直播时长

```text
经计算：
endTime = startTime + duration
```

### 下播时间 + 直播时长

```text
经计算：
startTime = endTime - duration
```

计算结果必须自动校验：

```text
endTime >= startTime
 duration > 0
 duration 不超过系统配置的合理上限
```

如果用户同时填写三项时间字段：

```text
三项一致 → 全部保留，来源标记为 USER_ENTERED / USER_CONFIRMED
三项矛盾 → 拒绝保存，要求用户修正
```

对于跨午夜直播，例如：

```text
2026-09-12 23:30
2026-09-13 02:00
```

必须根据完整日期时间计算，不得只比较时分。

## 108.3 时间精度

默认精度：分钟。

内部数据库建议使用毫秒时间戳保存，同时允许 UI 以：

```text
YYYY-MM-DD HH:mm
```

展示。

未来如需要，可扩展为秒级精度。

## 108.4 例子

用户输入：

```text
开播：2026-09-12 19:30
下播：2026-09-12 22:15
```

软件自动显示：

```text
直播时长：2小时45分钟
```

用户只输入：

```text
开播：19:30
时长：2小时45分钟
```

软件自动计算：

```text
下播：22:15
```

## 108.5 手工创建直播记录

允许用户在历史页面点击：

```text
+ 添加直播记录
```

随后选择：

```text
主播
直播日期
开播时间
下播时间
直播时长
标题（可选）
分区（可选）
备注（可选）
```

至少填写三项时间字段中的两项。

手工新建的记录默认：

```text
source = MANUAL
confidence = CONFIRMED
```

因为这是用户主动提供的真实数据，但必须保留“由用户填写”的来源标记。

---

## 原规范 109：LiveSession 数据模型升级【历史说明：不可直接实现】

本节模型已经被 0.6.3 唯一 `LiveSessionEntity` 取代。尤其：

```text
startSource/endSource/durationSource → DataSource?，可空
startLocked/endLocked/durationLocked → 最终字段
startConfidence/endConfidence/durationConfidence → 可空
lifecycleState → ACTIVE / ABANDONED
```

`TimeDataSource` 永久废弃，不得创建第二套时间来源枚举。

这样可以明确知道：

```text
这个时间是监控到的？
用户填写的？
软件计算出来的？
还是从备份文件恢复的？
```

---

## 原规范 110：自动记录与人工修正的双层数据模型【历史说明：不可直接实现】

不要直接覆盖原始监控数据。

推荐分为：

```text
Raw Monitoring Data
        ↓
Normalized LiveSession
        ↓
User Correction
        ↓
Final LiveSession
```

数据库至少保留：

```text
原始开播观察时间
原始下播观察时间
人工修正后的开播时间
人工修正后的下播时间
人工修正后的直播时长
```

例如：

```text
原始检测：19:03 开播
用户修正：19:00 开播
```

最终统计使用：

```text
19:00
```

但审计记录仍然保留：

```text
原始观察：19:03
人工修正：19:00
```

绝对禁止直接物理删除原始数据。

---

## 原规范 77：数据可信度模型 DataConfidence【历史说明：不可直接实现】

所有可能影响直播历史和统计的数据都必须带有可信度。

```text
【历史定义，废弃】DataConfidence 统一以 0.6.2 的 RAW/PROVISIONAL/CONFIRMED/CORRECTED/INVALID 为准。
```

含义：

```text
CONFIRMED
明确由成功 API 响应和完整状态转换确认。

PROVISIONAL
根据前后两个确认点推断出的时间范围或估算结果。

UNKNOWN
存在监控盲区，无法可靠判断。

INVALID
发现数据损坏、逻辑冲突或明确不可用，应排除在正常统计之外。
```

禁止：

```text
网络请求失败 -> OFFLINE
应用崩溃 -> 自动填充 endTime
监控恢复 -> 假设中间一直 LIVE/OFFLINE
```

---

## 原规范 78：LiveSession v2 完整可靠性模型【历史说明：不可直接实现】

补充：`startTime` 必须标注来源语义。若由状态确认自动建立，只能表示“软件确认进入当前直播场次的时间”，不得直接声称为主播真实开播时间。真实开播时间若未知，应通过 `DataConfidence` / `startSource` 表达未知或估算；`LiveEvent.eventConfirmedAt` 与 `LiveSession.startTime` 不得无条件视为同义字段。



`LiveSession` 是用户层面的“单次直播记录”。

建议字段：

```kotlin
// 唯一实体是 LiveSessionEntity（见 0.6.3）；本节历史版本的以下写法已废弃：
//  - startConfidence / endConfidence 为非空（DDL 允许 NULL，必须可空）
//  - startSource / endSource 为 String?（应为 DataSource?）
//  - 缺少 durationSource / durationConfidence / startLocked / endLocked / durationLocked /
//    lifecycleState / currentReliableIntervalId / autoUpdateProtected / correctionVersion / note
// lastConfirmedLiveAt / confirmedOfflineAt 是诊断/确认锚点，不是第二套结束事实。
//
// 本节的 data class LiveSession 声明已移除（避免与 0.6.3 产生第二个同名类型）。
// 字段全集与 NULL 语义一律以 LiveSessionEntity 为准，见 0.6.3 与 0.6.29 DDL。
```

数据库必须允许：

```text
startTime = 有值
endTime = NULL
```

`endTime` 是场次是否结束的唯一事实来源：`endTime == NULL` 表示 OPEN，`endTime != NULL` 表示 CLOSED。不得再维护一个可独立变化的 `isCompleted` 数据库事实字段；UI 或统计层如需要可由 `endTime` 派生。

这表示：

> 已确认开始，但尚未确认结束。

如果存在监控盲区，不得将盲区结束时间直接当成真实下播时间。

例如：

```text
19:00  LIVE confirmed
19:03  最后成功确认 LIVE
19:04  App 崩溃
20:10  App 恢复
20:11  API 返回 OFFLINE
```

应该保存：

```text
lastConfirmedLiveAt = 19:03
confirmedOfflineAt = 20:11
```

并把实际下播时间表示为：

```text
19:03 < 实际下播时间 <= 20:11
```

用户界面可显示：

```text
约 20:11 前下播

直播时长：约 1小时08分钟～1小时11分钟
数据存在监控盲区
```

更简单的产品显示方式可以写成：

```text
直播时长：约 1小时
⚠ 数据存在监控中断，时长为估算值
```

---

## 原规范 25：状态一致性设计（最终规范）【历史说明：不可直接实现】

网络响应不是可以直接覆盖本地业务事实的“绝对真相”，而是一次带有来源、时间和有效性的观察结果。所有状态变化必须经过统一状态确认器，并在一个数据库事务中完成业务事实、历史和通知意图的原子提交。

最终流程：

```text
Remote DTO
   ↓
Schema / Business Validation
   ↓
ObservationResult
   ↓
State Confirmation / Debouncer
   ↓
DB CAS + monitorGeneration + observationSequence + fencingToken
   ↓
Room Transaction
   ├── 更新已确认状态
   ├── 写 LiveSession / LiveEvent（如产生）
   ├── 写 StatusHistory
   └── 写 NotificationOutbox（如需要）
```

禁止：

```text
网络请求成功
↓
直接修改 confirmedLiveStatus
↓
直接 notify()
```

任何并发任务在提交时发现版本、代际或 fencing 条件不匹配，都必须放弃本次提交并重新读取最新事实，不得再次生成重复事件。

## 原规范 44：直播状态确认状态机【历史说明：不可直接实现】

v2.0 增加“候选状态”概念，避免瞬时错误造成误报。

```text
              ┌──────────────┐
              │    UNKNOWN   │
              └──────┬───────┘
                     ↓
          ┌────────────────────┐
          │     OFFLINE        │
          └────────┬───────────┘
                   │ LIVE
                   ↓
             ┌───────────┐
             │ LIVE      │
             └────┬──────┘
                  │ OFFLINE candidate
                  ↓
          ┌────────────────────┐
          │ SUSPECTED_OFFLINE  │
          └────────┬───────────┘
                   │ confirm
                   ↓
              OFFLINE
```

默认策略：

```text
OFFLINE -> LIVE：1 次明确成功响应即可确认
LIVE -> OFFLINE：连续 2 次明确 OFFLINE 才确认
ERROR / UNKNOWN / PARTIAL：不参与状态转换，并保持上一次已确认状态
连续确认必须要求：每次观察均为 SUCCESS 且中间不存在 ERROR、UNKNOWN、PARTIAL 或 MonitoringGap；否则确认计数归零。
```

提供高级设置：

```text
下播确认次数：1 / 2 / 3
开播确认次数：1 / 2
```

注意：确认次数不是越高越好。次数越高，误报越低，但下播通知延迟越高。

## 44.1 首次观察到 LIVE 的特殊规则

主播第一次加入监控、数据库中没有历史确认状态时：

```text
UNKNOWN + SUCCESS + LIVE
```

默认必须原子完成：

```text
1. confirmedLiveStatus = LIVE
2. 创建 OPEN LiveSession
   startTime = eventConfirmedAt
   startSource = MONITOR_OBSERVED
   startConfidence = PROVISIONAL
3. 创建当前 ReliableMonitorInterval
4. 不产生普通 START 通知
```

这里的 `startTime` 明确表示“软件第一次可靠确认当前处于 LIVE 的时间”，不是主播真实开播时间。不得在 UI 或统计中把它表述为真实开播时刻。

如果数据库存在同主播 `ABANDONED` 的旧 OPEN Session，首次 UNKNOWN → LIVE 默认创建新的 ACTIVE Session；旧 Session 不得被自动猜测结束。

同一规则适用于首次恢复同步：如果只是恢复后第一次知道当前为 LIVE，不能因为“旧状态未知”就伪造实际 START 时刻，但仍必须建立一个可持续管理的 OPEN Session。

---

## 原规范 45：监控检查与资料刷新解耦【历史说明：不可直接实现】

v2.0 明确拆成两个任务：

### LiveStatusCheck

高频运行，仅获取状态和必要的最小字段。

```text
目标：低请求量、低延迟。
```

### RoomMetadataRefresh

低频或状态变化时运行，获取：

```text
昵称
头像
直播标题
分区
直播间封面
直播间基础信息
```

推荐：

```text
普通状态：只检查状态
检测到 LIVE：刷新详细资料
标题变化：按需刷新
手动下拉刷新：强制刷新资料
```

这样避免每 30~60 秒都重新下载所有主播图片和资料。

---

## 原规范 170：P0-5 LiveSession 数据来源与锁定【历史说明：不可直接实现】

LiveSession 中所有关键时间必须记录来源。

```text
【历史定义，废弃】DataSource 以 0.6.2 的最终枚举为准。
```

关键字段：

```text
startSource
endSource
durationSource
```

另外增加“自动覆盖保护”状态：

```text
startLocked
endLocked
durationLocked
```

这些字段的 `Locked` 语义仅表示“后台自动刷新不得覆盖当前值”，不表示用户不能编辑、撤销或恢复。

用户明确确认的数据，默认开启对应字段的自动覆盖保护；用户再次编辑、撤销修正或恢复自动数据时，可以按新的业务操作更新该状态。

所有涉及锁定状态或人工修正的修改都必须走版本/CAS，并记录 Correction/AuditLog。

---

## 原规范 176：P0-11 主播删除与历史数据解耦【历史说明：不可直接实现】

默认删除主播时：

```text
从监控列表移除
保留 LiveSession
保留历史统计基础数据
保留必要的审计信息
```

所有异步监控结果写回 `Streamer` 或其当前状态前，必须再次检查：

```text
streamer exists
AND deletedAt == null
AND monitorGeneration 有效
AND observationSequence 未过期
```

若结果返回时主播已经软删除，则只能记录为过期/丢弃结果，不得重新激活主播，也不得创建新的 LiveSession、LiveEvent 或 NotificationOutbox。

### 176.1 软删除与 OPEN Session 生命周期

软删除本身不是“主播下播”，因此不得填写 `endTime`，也不得生成 END 事件。为了避免 OPEN Session 永久阻塞新的业务 Session，同时保留原始证据，LiveSession 增加独立于 `endTime` 的运行生命周期：

```text
ACTIVE    → 当前被系统当作活动场次管理
ABANDONED → 因主播软删除/停止监控而失去继续自动追踪能力；endTime 仍可为空
```

数据库唯一索引只约束 `ACTIVE + endTime IS NULL`：

```sql
CREATE UNIQUE INDEX idx_live_session_one_active_open
ON live_session(streamerId)
WHERE endTime IS NULL AND lifecycleState = 'ACTIVE';
```

软删除时：

```text
OPEN ACTIVE Session
→ lifecycleState = ABANDONED
→ 保留 endTime = NULL
→ 当前 ReliableMonitorInterval 失效
→ 不产生 END Event / 关播通知
```

主播重新启用/重新添加后必须先 RecoveryCheck：

```text
确认 OFFLINE
→ 可以把最近一个 ABANDONED Session 关闭为 PROVISIONAL endTime = confirmedOfflineAt

确认 LIVE
→ 不自动把旧 ABANDONED Session 当作当前场次
→ 创建新的 ACTIVE Session + 新 ReliableMonitorInterval
```

这样既不伪造历史下播事实，也不让旧 OPEN 记录永久阻塞后续新场次。

另提供高风险操作：

```text
彻底删除主播及全部历史
```

必须二次确认。

建议用户看到：

```text
删除主播

主播将停止监控，但历史记录仍会保留。

[取消] [删除]
```

---

## 原规范 246：P0/P1 深层稳定性修正（第二轮）【历史说明：不可直接实现】

本节继续补充模块组合场景下的并发、幂等、生命周期与事务边界约束。

## 246.1 LiveSession 单开放场次约束

最终语义以 0.6.4 为准：同一 streamer 最多一个 `ACTIVE + endTime IS NULL`；最多一个 `ABANDONED + endTime IS NULL`。ABANDONED 不受 ACTIVE 部分索引约束，但必须由业务事务保证同主播最多一条。

```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_live_session_one_active_open
ON live_session(streamerId)
WHERE endTime IS NULL AND lifecycleState = 'ACTIVE';
```

Room `@Entity(indices=...)` 无法表达 `WHERE` 部分索引；必须由 Migration 手写 SQL。MonitoringTick 与 Recovery 竞争创建时，若唯一索引冲突，第二方重新读取现有 ACTIVE OPEN Session，不得再次创建或生成重复 START 事件。

## 246.2 MonitoringEngine 协程异常隔离

MonitoringEngine 的批次、主播级任务必须使用 `supervisorScope`、`SupervisorJob` 或等价的异常隔离机制。单个主播、单个批次、单次资料刷新抛出的未处理异常不得取消整个 MonitoringEngine。

规则：

```text
单任务异常
   ↓
记录错误
   ↓
标记该主播/批次失败
   ↓
其余任务继续
```

只有 Controller 明确执行 stop/cancel，或发生不可恢复的引擎级异常时，才允许结束整个监控实例。

## 246.3 RecoverySession 单实例与抖动合并

同一 `MonitoringController` 生命周期内同一时刻最多只能存在一个 `RUNNING` 的 `RecoverySession`。短时间内重复收到网络恢复事件、系统网络状态抖动或多个恢复触发源时，不得创建多个 RecoverySession。

已有恢复任务运行时：

```text
新的恢复事件
    ↓
标记 recoveryRequested = true
    ↓
并入当前 RecoverySession
```

当前 RecoverySession 完成后，如果 `recoveryRequested` 仍为真且系统确实仍存在恢复需求，最多再启动一次新的 RecoverySession。不得无限递归启动恢复任务。

## 246.4 Outbox Delivery Attempt Fencing

`PROCESSING` 租约之外，每次成功 Claim 必须生成唯一的 `deliveryAttemptId`。

建议模型增加：

```text
deliveryAttemptId
```

发送前必须再次验证：

```text
当前 Outbox.workerInstanceId == 当前 Worker
AND
当前 Outbox.deliveryAttemptId == 当前 Attempt
AND
当前 leaseUntil 仍有效
```

任意条件不满足：

```text
不得调用 NotificationManager.notify()
```

这样可以阻止旧 Worker 在租约被新 Worker 接管后继续发送。

## 246.5 Retry / RateLimiter / CircuitBreaker 执行顺序

所有 Bilibili 请求必须统一采用：

```text
请求意图产生
    ↓
CircuitBreaker 判断是否允许
    ↓
RateLimiter / ConcurrencyLimiter 获取许可
    ↓
HTTP Request
    ↓
错误分类
    ↓
判断是否可 Retry
    ↓
Backoff + Full Jitter
    ↓
重新从 CircuitBreaker 进入下一次请求
```

禁止绕过限流器或熔断器直接执行 Retry。

HTTP `429 Too Many Requests` 必须作为独立的限流类结果处理；如果响应提供 `Retry-After`，应优先遵循服务端给出的等待建议，并仍受本地最大等待上限约束。不得把 429 与普通网络失败完全等价处理。

## 246.6 聚合迟到事件策略

`eventConfirmedAt` 属于事件时间；事件进入 Aggregator 的处理时间属于处理时间。两者必须分开。

窗口关闭后到达的迟到事件默认不得重新打开已经完成的 Aggregate，也不得修改已经发送的 Aggregate。

默认规则：

```text
窗口关闭
    ↓
Aggregate 定案
    ↓
迟到事件 → 新的独立通知决策
```

如果后续产品需要支持迟到容忍，必须显式增加固定的 `lateArrivalTolerance` 配置，并且该容忍期结束后 Aggregate 不得再次变化。

因此当前 v2.0 默认不支持“无限迟到回填聚合”。

## 246.7 Confirmed State Transition 原子提交单元

一次已经确认的业务状态转换必须作为一个原子提交单元。默认 START/END 状态转换应在同一 Room transaction 内完成：

```text
Confirmed State Transition
        │
        ├── 当前主播确认状态
        ├── StatusHistory
        ├── LiveSession 创建或结束
        ├── LiveEvent
        └── NotificationOutbox / Notification Intent
```

事务全部成功才视为该状态转换已经提交；任一关键写入失败则整体回滚。

NotificationManager 的实际发送仍在数据库事务之外执行，但 Outbox 意图必须属于同一个原子业务提交结果。

禁止出现：

```text
主播状态已更新
但 LiveSession 没有更新

或

LiveSession 已创建
但对应 LiveEvent / Outbox 意图永久缺失
```

## 246.8 MonitoringTick 与配置/代际的联合约束

每个 Tick 除了保存 `MonitoringConfigSnapshot` 外，还必须保存：

```text
monitorGeneration
configVersion
fencingToken
```

数据库提交时必须同时验证这些代际是否仍然有效。任一代际失效，整批结果不得写入业务事实。

## 246.9 P0/P1 第二轮稳定性不变量

```text
一个主播最多一个 ACTIVE OPEN LiveSession；ABANDONED OPEN 最多一条
一个 RecoverySession 同时最多一个 RUNNING 实例
单批次异常不能取消整个 MonitoringEngine
旧 Outbox Worker 不能在租约被接管后继续发送
Retry 永远不能绕过 RateLimiter / CircuitBreaker
429 不得按普通网络失败无限快速重试
关闭的 Aggregate 不会被迟到事件无限回填
Confirmed State Transition 不允许出现半提交事实
Tick 结果必须同时属于有效的 monitor/config/lease 代际
```

## 原规范 248：P0/P1 深层稳定性修正（第四轮）【历史说明：不可直接实现】

本节继续修复上一轮自查发现的实现级一致性问题；P2 暂不处理。

## 248.1 状态转换必须使用原子 CAS

同一个主播可能被两个并发任务同时观察到状态变化。状态转换检测不能仅在内存中判断后再写库，必须将旧状态、最新观察版本、`monitorGeneration` 与 `fencingToken` 作为数据库原子更新条件的一部分。

```text
旧状态匹配 + 版本匹配 + 代际有效
            ↓
        原子提交
            ↓
     成功 → 唯一转换
     失败 → 读取最新状态，不重复建事件
```

## 248.2 StartupRecovery 全局单实例

所有启动/恢复入口必须统一进入 `StartupRecoveryCoordinator`。同一时刻最多一个 ACTIVE StartupRecovery。重复触发只合并为一次后续恢复请求。

## 248.3 MonitoringGap 不允许重叠

同一连续监控盲区只允许一个 OPEN Gap。多个故障来源在同一盲区内作为原因信息合并；不得用多个重叠 Gap 表示同一个连续不可监控区间。

## 248.4 HALF_OPEN 只允许单 Probe

每个 API 类别的 CircuitBreaker 进入 `HALF_OPEN` 后，只允许一个 Probe 获得探测资格；Probe 结束前不得让其他普通请求同时进入 HALF_OPEN。

## 248.5 sourceDataVersion 必须原子递增

影响统计的业务变更必须在数据库事务内原子推进 `sourceDataVersion`。任何基于旧版本完成的统计计算不得覆盖更新版本结果。

## 248.6 DataFreshness 以时间年龄为主

`DataFreshness` 主要由 `now - lastConfirmedAt` 决定，而不是简单依赖“经过了多少次检查”。退避、熔断、系统调度弹性和用户自定义监控间隔都不得导致新鲜度判断失真。

## 248.7 PENDING_AGGREGATION 必须可恢复

聚合等待状态必须持久化；进程崩溃后根据原窗口截止时间继续等待或定案。不得因重启重复建立窗口或重复发送同一事件。

## 248.8 LiveSession 完成状态唯一来源

`endTime` 是 OPEN/CLOSED 的唯一事实来源。不得再通过独立的 `isCompleted` 字段形成第二套完成状态。

## 248.9 登录仅用于可选关注导入

本项目不建立独立的账号监控数据空间，也不让登录状态成为监控核心状态机的一部分。Bilibili 登录只是一个可选的辅助工具，用于读取当前登录账号的关注列表。

```text
账号登录
   ↓
读取关注列表
   ↓
与本地 UID 集合增量比对
   ↓
新增主播 + 按策略更新已有主播资料
```

更换登录账号再次导入时继续采用增量更新；不得清空现有主播，不得切换核心监控数据空间。退出登录只影响认证凭证和关注导入能力，不影响已有监控。登录请求与关注导入任务也不得参与 MonitoringEngine、LiveSession、LiveEvent 或 NotificationOutbox 的核心状态代际判断。

## 248.10 Session / Event 时间语义

`LiveEvent.eventConfirmedAt` 表示软件可靠确认状态转换的时间；`LiveSession.startTime` 若由自动监控产生，只能表示当前场次被软件确认开始的时间。真实主播开播时间未知时不得互换二者语义。v2.0 禁止继续使用早期 `startedAt/endedAt` 字段命名；数据库、Entity、DAO、UseCase 和导出格式统一使用 `startTime/endTime`。

## 248.11 跨设备稳定业务 ID

所有需要进入备份并支持跨设备增量/覆盖恢复的业务实体，必须区分“本地数据库主键”和“跨设备稳定业务 ID”。至少包括：

```text
Streamer.stableId
Tag.stableId
Group.stableId
LiveSession.stableId
LiveEvent.eventId
NotificationAggregate.aggregateId
```

本地 `Long id` 只用于当前数据库内部外键关系，恢复时不得拿不同设备上的本地 ID 直接比较。备份、恢复、导入合并和审计关联必须使用稳定业务 ID。

Repository 约束：

```text
本地查询 / 外键 / 当前设备编辑 → 可以使用 id
导出 / 备份 / 恢复 / 跨设备合并 / 审计关联 → 必须使用 stableId / eventId / aggregateId
```

跨设备数据交换 DTO 不得把本地自增 `id` 当作业务身份字段。

### 248.11.1 stableId / UID 恢复冲突矩阵

`stableId` 是跨设备首选身份，但 `Streamer.uid` 在数据库中仍必须保持 UNIQUE。因此恢复时必须显式处理“stableId 不同、UID 相同”的冲突，禁止让 SQLite UNIQUE 约束成为业务决策。

```text
1. stableId 相同
   → 视为同一 Streamer
   → 在恢复范围内按备份内容更新

2. stableId 不同 + uid 不同
   → 两个独立 Streamer，可分别存在

3. stableId 不同 + uid 相同
   → 业务身份冲突
   → 不自动静默合并
   → RestoreConflictResolver 生成冲突项并要求用户选择：
        A. 合并为同一 Streamer
        B. 保留本地、跳过该备份 Streamer
        C. 以备份为准替换本地实体（必须重映射引用）
```

恢复前预检必须先解析冲突。只有选择完成后，才进入实际 Room Transaction。若选择合并：

```text
保留一个本地 Streamer.id
→ stableId 按用户明确选择的业务身份规则确定
→ 所有 LiveSession / Event / Correction / Relation / History 引用重映射
→ sourceDataVersion 原子递增
→ AuditLog 记录 conflictId + 原始 stableId/uid
```

任何冲突未解决时，不得直接执行“部分成功恢复”，避免留下 UID UNIQUE 与稳定身份不一致的半成品。

## 248.12 关注导入不得覆盖用户自定义配置

关注导入只能更新 Bilibili-owned 字段；用户自定义的标签、分组、排序、备注、监控/通知策略等字段不得被远程资料覆盖。导入任务的默认策略是“新增 + 更新远程资料”，而不是整行替换 `Streamer`。

## 248.13 Backup Manifest 与恢复范围

每个备份文件必须声明完整的恢复范围。恢复算法不得通过“文件中是否出现某条记录”自行猜测删除语义。

```text
增量恢复
→ 备份范围外本地数据不变
→ 备份范围内以备份为准

覆盖恢复
→ 备份范围内最终状态必须与备份一致
→ 备份范围外保持不变
```

备份明确记录的 `deletedAt`、关系和配置状态必须照原样恢复；因此用户先删除主播、随后选择恢复该备份时，主播仍应按照备份中的状态恢复，而不能因为本地 `deletedAt` 较新而拒绝恢复。

## 248.14 ExportSnapshot 一致性

导出必须冻结：

```text
snapshotId
sourceDataVersion
request
```

并在同一个一致性读取快照中完成所有分页读取。`sourceDataVersion` 只是版本标识，不能单独替代一致性读取快照。HTML、备份配置文件和可选 JSON 必须来源于同一个 `ExportSnapshot`。

大数据量导出必须分页/流式读取并写临时文件；磁盘、WAL、内存达到安全阈值或用户取消时，删除临时文件并返回未完成，不得把部分文件标记为成功。

### 248.14.1 大数据量导出边界

`ExportSnapshot` 表示一致性视图，不代表必须一次把全量 Session 加载进 JVM 内存。实现必须支持分页/流式读取与写出：

```text
固定 snapshotId/sourceDataVersion
→ 分页读取稳定排序数据
→ 逐块写临时文件
→ 完成后原子提交目标文件
```

导出过程中若可用磁盘空间、WAL 大小或内存超过安全阈值，必须安全取消并删除临时文件；不得返回“导出成功”但只写出部分数据。用户取消同样必须走可恢复清理路径。

## 248.15 HTML 输出安全边界

HTML 生成器必须把所有 Bilibili 远程文本和用户可编辑文本视为不可信输入。

```text
HTML 文本 → HTML Escape
内嵌 JSON → 由 JSON 序列化器生成，不得字符串拼接
JavaScript → 不得直接拼接用户输入生成代码
URL → 经过允许协议与安全校验
```

不得因为导出文件是本地 HTML 就省略输出编码和注入防护。

## 248.16 Outbox 最终状态机统一

`NotificationOutbox` 的规范状态统一为：

```text
PENDING
PROCESSING
DELIVERY_UNKNOWN
SENT
RETRY_WAIT
FAILED
EXPIRED
CANCELLED
```

`DELIVERY_UNKNOWN` 表示“Android notify 调用后，数据库尚未来得及确认最终结果”；`EXPIRED` 表示超过事件价值窗口；`CANCELLED` 表示业务上主动取消。`DISMISSED` 不再作为 Outbox 发送状态；用户在通知中心手动清除通知属于 UI/NotificationHistory 行为，不应反向污染 Outbox 发送语义。

错误分类与最终状态必须固定：

```text
临时网络错误 / 可重试 IO / 短时系统错误
    → RETRY_WAIT

通知权限关闭 / Channel 被用户禁用 / 明确不可恢复的安全或参数错误
    → FAILED（不得无限重试）

事件已经超过 expiresAt
    → EXPIRED

业务主动撤销该通知事件
    → CANCELLED

调用 Android notify 后无法确认是否已经展示
    → DELIVERY_UNKNOWN
```

每次成功 Claim 必须写入唯一 `deliveryAttemptId`，并与 `workerInstanceId + leaseUntil` 一起参与发送前的最终 fencing 检查。

## 248.17 NotificationAggregate 冻结规则

达到阈值只表示“当前窗口已足以形成批量通知”，不立即改变窗口截止时间。窗口仍按创建时固定的 `[windowStartWall, windowEndWall]` 收集事件；只有窗口结束并完成原子定案后才创建对应 Outbox。

## 248.17.1 Aggregate 与 Outbox 存在性不变量

批量 Aggregate 与 Batch Outbox 必须满足双向存在性约束：

```text
已定案且需要发送的 Aggregate → 必须存在唯一对应 Batch Outbox
Batch Outbox.aggregateId → 必须指向已存在 Aggregate
```

事件关系、Aggregate 和对应 Outbox 必须在同一 Room transaction 中提交。ConsistencyChecker 发现孤儿状态时应执行幂等补建或标记待修复，不得创建第二个等价 Aggregate。

## 248.18 维护模式与 Lease 原子互斥

`MaintenanceMode` 与 `MonitoringLease` 必须由数据库级状态共同决定：任何一方处于冲突状态，都不得成功获取另一方。禁止仅通过内存 Boolean 检查维护状态。

## 248.19 备份安全点必须是可验证快照

恢复安全点必须记录 `backupId/snapshotId/createdAt/checksum` 等元数据，并能够在恢复后执行校验。安全点只有在恢复事务完成且一致性检查通过后才允许清理。

## 248.20 第四轮稳定性不变量

```text
状态转换提交必须是原子 CAS
同一连续盲区不得存在多个 OPEN MonitoringGap
CircuitBreaker HALF_OPEN 同时最多一个 Probe
sourceDataVersion 必须原子递增
DataFreshness 主要基于时间年龄
PENDING_AGGREGATION 崩溃后必须可恢复且不重复消费事件
LiveSession 的 OPEN/CLOSED 只由 endTime 决定
备份恢复范围必须由 Manifest 明确声明
备份范围内以备份文件为最终权威，范围外本地数据保持不变；用户明确选择恢复时不得因为本地更新时间较新而阻止备份生效
恢复后的 monitoringEnabled 只是持久化的用户监控意图，实际运行必须由当前设备重新建立 Runtime/Lease
跨设备恢复不得依赖本地自增主键
导出必须来自一致性 ExportSnapshot
HTML 输出必须进行上下文相关的 Escape / 安全序列化
Outbox 每次 Claim 必须有独立 deliveryAttemptId
MaintenanceMode 与 MonitoringLease 必须数据库级原子互斥
关注导入只能更新 Bilibili-owned 字段
登录仅服务于关注导入，不属于监控核心状态机
```

## 原规范 244：v2.0 稳定性修正补充（本轮）【历史说明：不可直接实现】

本节用于记录本轮发现的稳定性实现约束；后续如果继续发现问题，应继续以增量方式补充，不要求现在重写全文。

## 244.1 状态、观察结果与错误必须分离

```text
LiveStatus（Confirmed Business State） = 主播已确认的直播业务状态
ObservationResult   = 本次请求的观察结果
PendingTransition   = 尚未确认的候选状态
```

禁止：

```text
网络错误 → ObservationResult.NETWORK_ERROR；confirmedLiveStatus 保持上一次确认值
网络错误 → OFFLINE
批量响应缺失 UID → OFFLINE
```

## 244.2 刷新结果必须防止乱序覆盖

每次真正执行的刷新任务必须拥有 `refreshGeneration` / `observationSequence`。旧响应晚于新响应返回时，不得覆盖数据库中更新的观察结果。

## 244.3 RecoveryCheck 与普通检查必须互斥

```text
NORMAL
RECOVERING
PAUSED
STOPPED
```

进入 `RECOVERING` 后，普通监控 tick 必须暂停、取消或并入 RefreshCoordinator。恢复检查完成后才能恢复正常周期。RecoverySession 即使因为 TIMEOUT 结束，也只能恢复到实际健康状态，不能直接强制标记为 HEALTHY。

## 244.4 批量通知采用固定锚点窗口

默认：

```text
threshold = 4
window = 5 秒
```

第一事件进入后建立固定窗口 `[T, T+5s]`。进入该窗口的事件先等待聚合决定，不能先发送单条通知后再尝试合并。

## 244.5 Outbox 崩溃恢复

`PROCESSING` 必须带租约：

```text
processingStartedAt
leaseUntil
workerInstanceId
```

租约过期后可回收。最终系统通知必须使用稳定 `notificationId`，降低“通知已经显示但数据库尚未标记 SENT”导致重复通知的风险。

## 244.6 删除主播默认保留历史

默认删除采用软删除/归档逻辑；只有用户明确执行“彻底删除”时才删除历史数据。

## 244.7 实时模式参数与省电模式参数分离

```text
实时模式 → 秒级应用轮询参数
省电模式 → WorkManager 等系统调度参数
```

两者不得共用一个 `monitorIntervalSeconds` 的语义。

## 244.8 自定义 Endpoint 与认证凭证隔离

正式发布版本默认关闭任意 Endpoint 编辑。开发/测试版即使允许配置，也必须保证：

```text
CustomEndpointClient
    ≠
AuthenticatedBilibiliClient
```

自定义 Host 永远不得自动继承 Bilibili Cookie、Token 或 Authorization Header。

## 244.9 无障碍服务属于可选用户授权功能

开源版本可以提供实验性 AccessibilityService 辅助能力，但必须：

```text
默认关闭
明确说明用途
用户主动授权
独立模块
最小权限
可随时关闭
状态可观测
```

该功能不得成为数据访问、屏幕采集或隐蔽行为的入口。

## 244.10 MonitoringEngine 单实例租约

监控主循环必须通过数据库租约保证单实例主动执行：

```text
acquire lease
   ↓
renew lease
   ↓
正常运行
   ↓
release / expire
```

`runtimeInstanceId` 用于区分不同进程/实例，`monitorGeneration` 用于区分不同生命周期。新实例接管后必须递增 generation，所有旧任务返回的数据都必须被拒绝。

## 244.11 首次发现 LIVE 不等于发现 START

```text
UNKNOWN → LIVE
```

默认表示“现在确认正在直播”，不表示“现在刚刚开播”。因此默认不建立普通 START 事件。

## 244.12 主播级观察版本

每个主播必须拥有可比较的最新观察版本：

```text
streamerId + observationSequence + monitorGeneration
```

数据库事务只能接受严格更新或明确允许的同版本幂等写入。过期结果一律丢弃。

## 244.13 双时钟规则

所有持续时间逻辑优先使用 `SystemClock.elapsedRealtime()`（或等价 monotonic clock）。墙上时钟只保存业务时间点。通知聚合、Outbox lease、Recovery timeout、retry backoff、cooldown 均不得依赖用户可修改的系统时间差。

对于已经持久化的 `eventConfirmedAt`，事件排序仍使用墙上时间；运行中的聚合窗口使用单调计时器计算截止时间。若检测到系统墙上时间发生异常跳变，当前尚未提交的聚合窗口应提前关闭或转入 `UNKNOWN`，不得跨越时间跳变继续强行合并事件。系统重启后不得把上一进程的 `elapsedRealtime` 与新进程直接比较。

## 244.14 NotificationAggregate 并发保护

聚合创建、事件关联、状态变更、Outbox 创建必须在同一数据库事务中完成，并使用唯一约束阻止同一 `eventId` 重复绑定。

## 244.15 Outbox 的最终语义

系统只保证：

```text
一个业务事件 → 一个稳定 notificationId
```

不保证 Android 系统层 `notify()` exactly-once。若发送结果未知，允许进入 `DELIVERY_UNKNOWN` 并在后续通过稳定 notificationId 恢复。

## 244.16 RecoverySession 完成语义

RecoveryCheck 必须有：

```text
最大恢复时长
完成条件
终止条件
finishReason
```

不得因为“请求循环结束”就自动宣称全部主播已经恢复。

## 244.17 子系统健康状态隔离

```text
NetworkHealth
DatabaseHealth
MonitoringHealth
NotificationHealth
        ↓
   OverallHealth
```

故障只影响其对应子系统；跨模块影响必须通过明确的依赖规则计算，而不能简单把任意异常映射成全局 ERROR。

## 244.18 取消传播与失效提交保护

用户停止监控、修改配置、切换模式或 RecoverySession 被终止时，必须取消未完成请求；任何迟到的网络结果即使返回，也必须因为 generation/sequence 已失效而不能写入业务状态。

主动取消不是一次失败观察：`CancellationException` 或等价取消结果不计入网络失败率、CircuitBreaker、连续失败计数或 MonitoringGap 触发条件，也不产生 `ObservationResult.NETWORK_ERROR`。取消任务如需记录，仅作为调度/诊断事件保存。

## 原规范 178：P0-13 主播资料空值保护【历史说明：不可直接实现】

远程响应中的 `null`、缺失字段和明确删除必须区分。

规则：

```text
字段缺失/未知
    保留旧值

明确返回删除状态
    才允许清空

新值有效
    更新
```

避免 Bilibili 某次接口返回不完整导致：

```text
头像突然消失
标题突然变空
分区突然变空
```

---

## 原规范 169：P0-4 数据库完整性检查【历史说明：不可直接实现】

新增：

```kotlin
DatabaseIntegrityChecker
```

启动时和重要数据操作后执行轻量检查。

必须检查：

```text
重复 UID
重复唯一键
孤儿 TagRelation
孤儿 GroupRelation
孤儿 LiveSession
孤儿 StatusHistory
孤儿 NotificationOutbox
非法时间
结束时间早于开始时间
duration < 0
Session 重复引用
PROCESSING 状态长期未完成的 Outbox
```

发现问题：

```text
先记录
再标记
最后尝试安全修复
```

不能直接静默删除异常数据。

## 169.1 MaintenanceMode 与业务写入互斥

涉及迁移、数据库修复、结构一致性修复或大规模导入时必须进入维护模式：

```kotlin
// 唯一最终定义见 0.6.2 与 0.6.14；本节不得重复声明。
//   MaintenanceMode = { OFF, DATABASE_REPAIR, BACKUP, RESTORE, MIGRATION }
// 历史取值 NONE / CONSISTENCY_CHECK / IMPORT 全部废弃；
// system_runtime_lock.maintenanceMode 的 CHECK 约束只接受上述取值集合。
// "一致性检查"落在 DATABASE_REPAIR，"批量导入"落在 RESTORE，均不再单列枚举值。
```

除轻量只读检查外，维护操作必须与 MonitoringEngine、RecoverySession、Statistics 重建等可能修改相同事实的数据任务互斥。进入维护模式后，新业务写任务不得开始；正在进行的可取消任务应先安全结束或取消。

维护操作完成前，不得基于旧快照覆盖更晚产生的数据。

---

## 原规范 171：P0-6 人工修正必须可撤销【历史说明：不可直接实现】

所有用户修正必须记录：

```text
revisionId
sessionId
fieldName
oldValue
newValue
modifiedAt
reason
source
```

允许：

```text
撤销最近一次修正
查看修正历史
恢复某一个历史版本
```

撤销本身也是一次 Revision，不得直接覆盖历史审计记录。

---

## 原规范 173：P0-8 Session 时间冲突检测【历史说明：不可直接实现】

同一主播的完整 Session 不允许出现无提示的异常重叠。

例如：

```text
Session A 19:00-21:00
Session B 20:30-22:00
```

必须产生：

```text
CONFLICT_OVERLAP
```

用户可：

```text
编辑
合并
拆分
保留两场
忽略冲突
```

统计默认不得把异常重叠时长简单重复相加，除非用户明确选择“按记录累计”。

---

# 03 直播状态、会话与故障盲区

## 原规范 9：开播/关播检测核心算法【历史说明：不可直接实现】

这是本项目最重要的业务逻辑之一。

## 9.1 不允许直接这样写

```kotlin
if (remoteLive) {
    // 错误示例：业务层不得直接发送通知
    notificationOutbox.enqueue(...)
}
```

否则每 30 秒会重复通知。

## 9.2 必须采用“状态转换”

```text
UNKNOWN -> LIVE     确认 LIVE；创建 OPEN Session 与新 ReliableMonitorInterval；默认不产生普通 START 事件，但用户可见直播通知统一为“正在直播”
OFFLINE -> LIVE     生成 START 业务事件，并发送“正在直播”通知
ROUND -> LIVE       按 RoundSemanticsPolicy 判断是否进入新的 Session；若进入则发送“正在直播”
LIVE -> OFFLINE     仅当当前 ReliableMonitorInterval 仍有效且满足 EndNotificationEligibility 时发送关播通知
LIVE -> ROUND       默认保持当前 Session OPEN；不发送关播通知；ROUND 不得被解释为“已下播”
OFFLINE -> OFFLINE  不通知
LIVE -> LIVE        不重复发送普通“正在直播”通知
任意确认状态 + 异常 ObservationResult → 只更新观察/健康/Interval 资格，不覆盖 confirmedLiveStatus
```

“正在直播”是普通开播确认与异常恢复后再次确认仍在直播时的统一用户可见语义。两者内部可以使用不同的事件来源，但展示文案保持一致。

### 9.2.1 ROUND 语义

`ROUND` 不得与“下播”混用。当前版本采用保守规则：

```text
LIVE -> ROUND
→ confirmedLiveStatus = ROUND
→ 当前 LiveSession 保持 OPEN
→ 不产生 END 事件
→ 不发送关播通知
→ ROUND 时段不自动计入普通直播时长，单独的 roundSeconds/轮播统计属于可选增强

ROUND -> LIVE
→ 若仍属于同一直播连续场次，则恢复 LIVE，不新建 Session
→ 若产品明确配置“ROUND 视为场次结束”，才允许关闭旧 Session；该配置必须显式记录并进入审计
```

默认行为不得通过猜测主播实际行为来拆分/合并场次。

实现接口：

```kotlin
interface RoundSemanticsPolicy {
    fun decide(current: LiveSession, remoteStatus: ConfirmedLiveStatus): RoundDecision
}

interface EndNotificationEligibilityEvaluator {
    fun canNotifyEnd(session: LiveSession, interval: ReliableMonitorInterval, offlineConfirmedAt: Long): Boolean
}
```

`canNotifyEnd` 必须读取持久化 Interval，而不能只根据当前内存状态判断。

关播通知资格不是单纯的 `LIVE -> OFFLINE` 状态转换，而必须由统一的 `EndNotificationEligibilityEvaluator` 判断当前连续监控区间是否满足条件。

这里的“当前连续监控区间”是指：从一次可靠地确认主播处于 LIVE、并开始持续正常监控的时间点起，到最终确认 OFFLINE 为止的连续区间。

```text
当前存在有效 LiveSession
+ 当前连续监控区间已经建立
+ 该区间内没有 TIMEOUT / NETWORK_ERROR / API_ERROR / PARTIAL / INVALID
+ 该区间内没有 MonitoringGap
+ 最终 OFFLINE 得到可靠确认
+ 当前主播关播通知策略开启
```

如果之前发生过异常，但恢复时主播仍然在直播，则从“恢复后重新确认 LIVE”开始建立新的连续监控区间。只要这一个新区间随后一直保持可靠监控直到 OFFLINE，就可以发送关播通知。早先已经结束的异常区间不会永久剥夺后续新区间的关播通知资格。

如果当前连续监控区间再次发生异常，则该区间立即失去关播通知资格；只有下一次恢复时主播仍在直播，并重新建立新的连续可靠监控区间后，才重新具备后续关播通知的可能性。

## 9.3 首次同步规则

用户第一次添加主播时，如果主播此刻已经在直播：

默认不通知“开播”，避免用户刚添加就收到一条误以为刚开播的通知。

但 UI 必须显示“直播中”。

提供设置：

```text
[ ] 首次添加时若正在直播可按设置发送“正在直播”通知
```

## 9.4 网络错误规则

假设：

```text
LIVE
  |
请求失败
  |
ERROR
  |
请求失败
  |
ERROR
```

不能因为 ERROR 就发送“已下播”。

只有真正得到明确 OFFLINE 状态，并且当前连续可靠监控区间满足关播通知资格，才能触发关播通知。

状态转换提交必须是数据库级 compare-and-set：读取到的旧 confirmed state、latest observation version、monitorGeneration 和 fencingToken 必须作为原子更新条件的一部分。若条件不匹配，表示另一任务已先提交该转换，本次任务必须放弃创建重复 transition/event。

---

## 原规范 86：故障期间的直播事件处理【历史说明：不可直接实现】

必须避免以下错误：

```text
LIVE
 ↓
App 崩溃
 ↓
恢复
 ↓
OFFLINE
```

直接生成：

```text
startTime = 崩溃前
endTime = 崩溃恢复时刻
```

这是错误的。

正确做法：

```text
startTime = 最后确认的开始时间
lastConfirmedLiveAt = 崩溃前最后一次确认
confirmedOfflineAt = 恢复后确认 OFFLINE 的时间
endConfidence = PROVISIONAL
```

只有在系统能够证明真实下播时间时，才使用：

```text
endConfidence = CONFIRMED
```

---

## 原规范 166：P0-1 状态抖动保护【历史说明：不可直接实现】

## 166.1 目的

防止 Bilibili 接口短时抖动、缓存差异或异常响应造成：

```text
LIVE -> OFFLINE -> LIVE
```

从而错误触发多次开播/关播通知。

## 166.2 设计

增加：

```kotlin
StateDebouncer
StateConfirmationPolicy
```

默认策略：

```text
OFFLINE -> LIVE
    1次明确成功观察即可确认

LIVE -> OFFLINE
    默认连续2次明确 OFFLINE 才确认

ERROR / UNKNOWN
    不改变上一次已确认状态
```

所有确认次数必须可配置。

## 166.3 验收

```text
[ ] 单次 ERROR 不产生下播
[ ] 单次 OFFLINE 可以进入疑似下播
[ ] 连续确认后才能生成 Ended
[ ] LIVE -> OFFLINE -> LIVE 的短抖动不会重复生成无效 Session
```

---

## 原规范 79：MonitoringGap 监控盲区【历史说明：不可直接实现】

当系统无法持续获取可靠状态时，必须创建监控盲区记录。Gap 的关闭条件统一由 `endTime` 表示；`resolved` 不作为独立事实来源。

```kotlin
data class MonitoringGap(
    val id: Long,
    val startTime: Long,
    val endTime: Long?,
    val reason: GapReason,
    val affectedStreamerCount: Int,
    val lastKnownGoodAt: Long?,
    val recoveredAt: Long?,
    // resolved 仅作为向后兼容的派生字段；最终是否关闭以 endTime != NULL 为准
    val resolved: Boolean,
    val details: String?
)

// 全局 Gap 之外还必须存在主播级影响关系，才能支持“全局故障但部分主播正常”的统计与数据质量归因。
data class MonitoringGapStreamer(
    val gapId: Long,
    val streamerId: Long,
    val lastKnownGoodAt: Long?,
    val affectedFrom: Long,
    val affectedTo: Long?,
    val resolvedAt: Long?
)
```

典型流程：

```text
正常监控
   ↓
连续失败超过阈值
   ↓
创建 MonitoringGap
   ↓
记录 lastKnownGoodAt
   ↓
状态进入 UNKNOWN / DEGRADED
   ↓
恢复
   ↓
立即执行补偿检查
   ↓
可靠恢复确认？
   /        \
 否          是
 ↓            ↓
Gap 继续 OPEN   设置 endTime / recoveredAt / resolved
```

RecoveryCheck TIMEOUT、网络再次失败或仅获得 PARTIAL/UNKNOWN 时，都不得关闭 Gap。

全局 Gap 只表示系统级不可可靠观察；单主播失败至少写 `MonitoringGapStreamer`，不得把其他正常主播一起标记为受影响。Global Gap 的 `endTime` 只由系统级恢复探测关闭。`affectedFrom/affectedTo` 是主播级区间事实，`affectedStreamerCount` 仅为派生缓存。

全局 Gap 与主播级 GapStreamer 必须分层：

```text
系统级探测/网络栈/数据库等原因导致“整个监控系统不可可靠观察”
→ 创建/延长 Global MonitoringGap

仅部分主播 API 结果失败、单 UID 缺失或主播自身异常
→ 至少创建对应 MonitoringGapStreamer 影响记录
→ 不得因为一个主播失败就把其他正常主播全部标记为受影响
```

Global Gap 的 `endTime` 只由系统级恢复探测关闭；不能要求所有主播同时恢复。
`MonitoringGapStreamer.affectedFrom/affectedTo` 是主播级区间事实，统计与关播资格应优先使用该主播级区间，而不是用 Global Gap 的时间范围猜测。`affectedStreamerCount` 只能作为派生缓存，不是关系表的事实来源。

同一 `streamerId` 的连续 GapStreamer 区间不得重叠。恢复后重新发生故障时必须建立新的区间。

数据库层应为主播级 Gap 提供防重叠保护；至少保证同一 streamer 的 OPEN GapStreamer 只有一条，且新 `affectedFrom` 不得早于上一条 OPEN 区间的 `affectedTo`/关闭时间。

单次失败不一定产生 Gap：

```text
单次超时：记录错误
连续失败：DEGRADED
超过配置阈值：创建 Gap
恢复：执行 RecoveryCheck
```

同一连续不可监控区间只能存在一个 OPEN Gap。`endTime` 是 Gap 是否关闭的唯一事实来源：`endTime == NULL` 表示 OPEN，`endTime != NULL` 表示 CLOSED；`resolved` 只能作为派生/兼容字段，禁止形成第二套可独立变化的完成状态。多个故障原因必须合并到该 Gap 的原因集合或详细信息中。对于主播级 `MonitoringGapStreamer` 关系，同一 `streamerId` 在时间上重叠的区间也只能存在一个 OPEN 关系记录；并发恢复任务必须通过数据库 CAS/唯一约束避免重复创建。

## 79.1 主播级影响范围

全局 Gap 只能描述“系统存在一段不可可靠观测时间”，不能假定所有主播都同样受影响。每个 Gap 必须能够关联实际受影响的主播集合，推荐增加关系表：

```text
monitoring_gap_streamer
------------------------
gapId
streamerId
reason
lastReliableAt
recoveredAt
```

这样统计和历史页面可以准确判断：

```text
主播 A → 昨日存在 20 分钟监控盲区
主播 B → 昨日无盲区
```

即使多个主播共享同一个全局 Gap，也只有真正未获得可靠观察的主播进入该关系表。

## 原规范 80：网络异常处理规则【历史说明：不可直接实现】

## 80.1 单次失败

```text
正常
 ↓
请求超时
 ↓
重试
```

不改变主播的已确认直播状态。

例如：

```text
lastConfirmedStatus = LIVE
currentObservation = UNKNOWN
```

UI：

```text
🟢 直播中
⚠ 最近一次检查失败
```

## 80.2 连续失败

例如：

```text
失败 1 次
失败 2 次
失败 3 次
```

进入：

```text
MonitoringHealth.DEGRADED
```

## 80.3 长时间失败

超过 `monitoringGapThreshold` 后：

```text
创建 MonitoringGap
记录受影响主播数
停止产生基于未知状态的统计事件
```

## 80.4 网络恢复

必须立即触发：

```text
Connectivity recovery
        ↓
High priority check
        ↓
重新读取所有需要监控的主播
```

并根据结果决定：

```text
LIVE -> LIVE
保持原直播 Session

LIVE -> OFFLINE
结束已有 Session，但结束时间标记为“恢复后确认”

OFFLINE -> LIVE
创建新的 Session，开始时间可能为“首次确认时间”或估算时间
```

---

## 原规范 87：“补偿检查”机制【历史说明：不可直接实现】

当存在监控盲区时，恢复后不能只做普通轮询。

必须执行：

```text
RecoveryCheck
```

优先级高于普通检查。

流程：

```text
恢复
 ↓
获取所有重点主播
 ↓
获取所有当前为 LIVE 的主播
 ↓
获取所有存在未完成 LiveSession 的主播
 ↓
批量检查
 ↓
关闭/更新 Gap
 ↓
更新 LiveSession
 ↓
生成必要的事件
```

但是：

```text
RecoveryCheck 不能伪造盲区内的精确直播开始/结束时间。
```

---

## 原规范 92：主播卡片的“数据新鲜度”【历史说明：不可直接实现】

每个主播不能只显示：

```text
未开播
```

还应该知道这个状态什么时候确认。

例如：

```text
● 未开播
3分钟前确认
```

数据太旧时：

```text
⚠ 未开播
状态 37 分钟未更新
```

这样可以防止用户误以为软件当前仍然实时知道主播状态。

建议模型增加：

```kotlin
val lastConfirmedAt: Long?
val freshness: DataFreshness
```

---

## 原规范 93：DataFreshness 数据新鲜度【历史说明：不可直接实现】

```kotlin
// 唯一最终定义见 0.6.2；历史取值 AGING 已废弃。
//   DataFreshness = { FRESH, STALE, UNKNOWN }
// "正在变旧"属于 UI 展示层派生，不得成为第四个持久化取值，
// 因为 streamer.freshnessStatus 的 CHECK 约束只接受 FRESH/STALE/UNKNOWN。
// 新鲜度阈值唯一来源为 monitoring_config.freshnessStaleSeconds（见 0.6.14 / 0.6.16.1）。
```

示例规则：

```text
没有成功检查：UNKNOWN

实际 `DataFreshness` 必须以 `now - lastConfirmedAt` 的时间年龄为主要判断依据；检查周期数只可作为辅助信息。不同监控间隔、退避、熔断或系统调度弹性不得让同样的“2 次检查”被误认为同样的新鲜程度。阈值应使用明确的时间范围配置。
```

阈值必须配置化，不得散落在 UI 中。

## 93.1 墙上时钟与单调时钟的职责分离

时间字段必须明确区分：

```text
Wall Clock
→ 事件发生/确认的历史时间点、UI 展示、导出

Monotonic Clock / elapsedRealtime
→ 超时、重试退避、租约、冷却、调度周期、聚合窗口
```

Android 系统时间可能因为自动校时、用户修改时间、时区变化而跳变，因此持续时间判断不得直接依赖墙上时钟差值。`eventConfirmedAt` 可以继续保存墙上时间；实现聚合窗口时应同时记录用于运行时计时的单调时间锚点，避免系统时间变化破坏 5 秒窗口。

---

## 原规范 97：启动恢复完整流程【历史说明：不可直接实现】

所有可能触发启动恢复的入口（App Start、BOOT_COMPLETED、MY_PACKAGE_REPLACED、Service 重建、WorkManager 等）必须先经过统一 `StartupRecoveryCoordinator`。同一时刻最多允许一个 ACTIVE StartupRecovery；多个触发源只记录 `recoveryRequested = true` 并合并处理，不得并发执行多份 ConsistencyChecker/RecoveryCheck。

```text
App Start
   ↓
读取 AppRuntimeState
   ↓
读取 ApplicationExitInfo
   ↓
Crash/Exit 分类
   ↓
ConsistencyChecker
   ↓
检测 MonitoringGap
   ↓
检查 LiveSession
   ↓
检查 Outbox
   ↓
执行 RecoveryCheck
   ↓
重新建立 MonitoringController
   ↓
恢复备份/本地持久化中的监控意图设置
   ↓
由当前 MonitoringController 重新评估是否实际启动监控
（不恢复旧设备的 Lease / runtimeInstanceId / fencingToken）
   ↓
更新健康中心
   ↓
必要时发送系统级“监控已恢复”通知；若同时确认主播仍在直播，主播状态通知仍统一使用“正在直播”
```

---

## 原规范 168：P0-3 监控心跳与运行三态【历史说明：不可直接实现】

监控系统必须区分：

```text
desiredState    用户期望
actualState     实际运行
healthState     健康程度
```

示例：

```text
desired = RUNNING
actual = STOPPED
health = CRITICAL
```

## 168.1 MonitoringHeartbeat

至少保存：

```text
lastAttemptAt
lastSuccessfulCheckAt
lastHeartbeatAt
lastStateChangeAt
consecutiveFailures
```

## 168.2 用户界面

不能只显示：

```text
监控：开启
```

必须允许显示：

```text
监控意图：开启
监控服务：已停止
状态：异常
```

这样避免出现“开关看起来开着、实际上完全没有监控”的假象。

## 168.3 子系统健康状态

不要让单一通知、数据库或网络故障直接把整个监控系统标记为 `ERROR`。建议分别维护：

```text
MonitoringHealth
NetworkHealth
DatabaseHealth
NotificationHealth
OverallHealth
```

例如：

```text
MonitoringHealth = HEALTHY
NotificationHealth = UNAVAILABLE
OverallHealth = DEGRADED
```

通知权限关闭、通知渠道被禁用等情况不能停止主播状态检测；反之数据库不可写时，监控结果也不能被假装成已可靠保存。

---

## 原规范 177：P0-12 恢复检查模式【历史说明：不可直接实现】

网络状态恢复事件不得直接视为 Bilibili 服务已恢复。RecoverySession 启动前必须至少经过两阶段探测：

```text
Network Transport Recovered
        ↓
Connectivity Probe
        ↓
Bilibili API Probe
        ↓
RecoveryCheck
```

仅当传输层与实际 Bilibili API 均达到可用判定条件，才允许进入大规模 RecoveryCheck；探测失败则继续退避等待，不得立即对全部主播发起恢复请求。

当系统发现：

```text
监控中断时间 > 阈值
```

恢复后不得立即高并发补查全部主播。RecoveryCheck 必须与普通 MonitoringTick 互斥；RecoveryCheck RUNNING 时，普通检查应暂停或合并进入 RefreshCoordinator。

流程：

```text
检测到恢复
↓
创建 RecoverySession
↓
按批次恢复检查
↓
限制并发
↓
记录恢复结果
↓
恢复正常监控周期
```

每次 RecoverySession 必须记录：

```text
startTime
finishedAt
affectedCount
successCount
failureCount
```

恢复检查必须定义明确完成条件和终止原因。建议增加：

```kotlin
// 唯一最终定义见 0.6.2；本节不得重复声明。
//   RecoveryFinishReason = { HEALTHY, TIMEOUT, FAILED, CANCELLED, SUPERSEDED }
// 历史取值 COMPLETED / ABORTED / STILL_UNAVAILABLE 全部废弃，
// 分别映射为 HEALTHY / CANCELLED / TIMEOUT；
// recovery_session.finishReason 的 CHECK 只接受 0.6.2 的取值集合。
```

默认完成条件：所有受影响主播至少获得一次新的成功确认，或者达到最大恢复时长；若达到最大恢复时长，必须结束本次 RecoverySession 并保留未完成对象，不得假装“全部恢复成功”。

`RecoverySession` 结束后是否回到 `HEALTHY`，必须同时参考 Network、Monitoring、Database、Notification 等子系统状态。


---

## 原规范 181：P1-2 监控可靠性评分【历史说明：不可直接实现】

增加用户可理解的指标：

```text
监控可靠度
```

建议由以下指标综合：

```text
API成功率
后台运行率
有效检查率
通知发送成功率
数据完整度
```

例如：

```text
监控可靠度：98.7%
```

评分不应伪装成科学精确值，应同时提供组成项和统计周期。

---

## 原规范 65：自动恢复与生命周期【历史说明：不可直接实现】

支持事件：

```text
App 启动
App 回到前台
网络恢复
系统重启
应用升级
时间变化
系统限制变化
FGS 停止
Worker 被调度
```

统一交给：

```text
MonitoringController
```

不得在各个 Receiver 中复制监控逻辑。

---

## 原规范 81：崩溃与进程异常恢复【历史说明：不可直接实现】

Android 进程可能因为崩溃、低内存、系统终止、ANR 或其他原因退出。

应用启动时必须执行：

```text
StartupRecovery
```

流程：

```text
App 启动
  ↓
读取上次运行状态
  ↓
读取 ApplicationExitInfo（系统允许时）
  ↓
判断是否异常退出
  ↓
检查未完成监控任务
  ↓
检查未完成 LiveSession
  ↓
检查 MonitoringGap
  ↓
检查 Notification Outbox
  ↓
执行一致性检查
  ↓
执行恢复检查
```

建议增加：

```kotlin
data class CrashRecord(
    val id: Long,
    val occurredAt: Long,
    val reason: String?,
    val wasMonitoringRunning: Boolean,
    val lastSuccessfulCheckAt: Long?,
    val lastSuccessfulRemoteUpdateAt: Long?,
    val appVersion: String,
    val osVersion: String
)
```

不要把“任何应用退出”都标记成崩溃。

至少区分：

```text
NORMAL_EXIT
CRASH
ANR
LOW_MEMORY
USER_STOPPED
SYSTEM_KILLED
UNKNOWN
```

如果系统提供对应退出原因，则保存原因；无法确认时使用 UNKNOWN。

---

## 原规范 82：启动一致性检查 ConsistencyChecker【历史说明：不可直接实现】

每次应用启动、从异常恢复后，以及数据库升级后执行。

必须检查：

```text
1. 是否存在状态不合法的主播。
2. 是否存在没有 endTime 的旧 LiveSession。
3. 是否存在 PROCESSING 超时的通知 Outbox。
4. 是否存在未关闭的 MonitoringGap。
5. 是否存在无法对应主播的 History。
6. 是否存在重复 UID。
7. 是否存在重复 eventKey。
8. 是否存在数据库版本与应用版本不一致。
9. 是否存在统计缓存与源数据明显不一致。
10. 是否存在异常的时间戳。
```

修复原则：

```text
可安全修复 -> 自动修复
无法确认 -> 标记 UNKNOWN / INVALID
绝不为了“修得好看”而猜数据
```

---

## 原规范 96：推荐的可靠性数据流【历史说明：不可直接实现】

```text
                 Bilibili API
                       ↓
                 RemoteResult
                 /          \
            Success          Error
               ↓                ↓
         StateDetector       UNKNOWN
               ↓                ↓
        StateTransition    FailureTracker
          /         \             ↓
       Confirmed   Candidate   MonitoringGap
          ↓             ↓           ↓
          └──────┬──────┴───────────┘
                 ↓
           Room Transaction
      ┌──────────┼────────────┐
      ↓          ↓            ↓
   Streamer  LiveSession   History
                 ↓
             Outbox Event
                 ↓
          Notification
                 ↓
             AuditLog
                 ↓
        StatisticsCalculator
                 ↓
             Statistics
```

---

## 原规范 179：P0-14 登录状态与监控解耦【历史说明：不可直接实现】

Bilibili 登录用于：

```text
账号信息
关注列表导入
账号相关功能
```

已有主播的公开直播状态监控不得无条件依赖登录状态。

例如：

```text
Token失效
↓
停止关注列表同步
↓
已有主播继续监控
```

只有实际 API 明确要求授权时才进入授权失败处理。

---

## 原规范 76：数据可靠性与故障恢复系统【历史说明：不可直接实现】

本章为 v2.0 的新增核心规范。

本项目必须遵循一个基本原则：

```text
监控结果 ≠ 永远可信的事实
统计结果 ≠ 永远可信的缓存
```

网络异常、Bilibili 接口异常、应用崩溃、进程被系统终止、数据库事务中断、通知发送失败，都可能导致“部分时间窗口没有可靠观测”。

因此系统必须保存：

```text
事实结果
观察结果
数据可信度
监控盲区
恢复过程
异常记录
```

不得为了让界面看起来完整而伪造未知时间段的数据。

---

## 原规范 165：v2.0 P0/P1 稳定性强化规范【历史说明：不可直接实现】

本章为 v2.0 的强制工程规范补充。

目标不是增加表面功能，而是保证：

```text
网络异常不会制造虚假状态
软件崩溃不会制造虚假历史
重复任务不会重复写入
人工确认不会被自动同步覆盖
统计异常可以定位、修正、重算
导出异常不会产生损坏文件
监控异常能够被用户及时发现
```

所有 P0 条款均视为核心正确性要求；P1 条款视为正式稳定版本的强烈要求。

---

## 原规范 245：P0/P1 深层稳定性修正（本轮）【历史说明：不可直接实现】

本节仅补充稳定性实现约束，不改变核心产品功能。

## 245.1 Fencing Token

`runtimeInstanceId` 用于识别实例，`monitorGeneration` 用于识别生命周期，`fencingToken` 用于最终阻断失效实例写入。所有 MonitoringEngine 关键写操作均必须在事务层验证 fencingToken。

旧 token 的写操作必须被拒绝，不得因为旧协程晚恢复而覆盖新实例数据。

## 245.2 MonitoringTick Coalescing

同一时间只能有一个 RUNNING Tick。计划时间到达而上一轮仍在运行时，只记录 `missedTick`，不创建并发 Tick；连续漏掉多个周期最多合并为一次补偿 Tick。

## 245.3 关注导入任务隔离

登录账号只用于“导入我的关注”，不属于监控核心状态机。为避免用户在一次关注导入尚未完成时再次发起导入，导入任务自身必须拥有独立的 `importTaskId`。

所有关注导入相关异步请求必须携带创建时的 `importTaskId`；返回时如果任务已经结束、取消或被新的导入任务替代，旧结果只能丢弃，不得覆盖当前导入预览、导入进度或导入结果。

更换登录账号后不清空本地主播数据，也不创建新的监控数据空间。重新导入时与本地主播 UID 集合做增量比对：不存在则新增，已存在则按照用户选择的导入策略更新资料。退出登录只删除认证凭证并结束账号相关导入能力，不影响已有主播监控、LiveSession、LiveEvent 或通知数据。

关注导入必须明确字段所有权，避免远程资料覆盖用户长期配置：

```text
Bilibili 原始资料：昵称、头像、直播间资料、分区、封面、直播链接等
    → 允许关注导入按策略更新

用户资料：标签、分组、排序、备注、主播级监控/通知策略
    → 关注导入不得覆盖

系统运行资料：confirmedLiveStatus、ObservationResult、PendingTransition、MonitoringGap、LiveSession、LiveEvent、NotificationOutbox
    → 关注导入不得修改其业务语义
```

因此“新增 + 更新已有信息”中的“更新”仅针对 Bilibili-owned 字段，不得成为覆盖用户自定义配置的通道。

---

## 245.4 Soft Delete 迟到响应保护

异步结果写回前必须同时满足主播存在、`deletedAt == null`、`monitorGeneration` 有效、`observationSequence` 未过期。软删除对象不得被迟到响应重新激活。

## 245.5 MonitoringConfigSnapshot

每个 Tick 使用单一不可变配置快照；配置修改只影响后续 Tick。

## 245.6 NotificationAggregate 配置冻结

聚合窗口创建后冻结 `windowStartWall`、`windowEndWall`、`threshold` 和 `configVersion`。

## 245.7 MaintenanceMode

迁移、数据库修复、一致性修复、大规模导入等可能修改业务事实的操作必须与 MonitoringEngine、RecoverySession、Statistics 重建建立明确互斥。

`MaintenanceMode` 不能只作为内存标志。获取 `MonitoringLease` 时必须在数据库级原子条件中检查当前维护状态；进入维护模式也必须以数据库 CAS/事务方式建立全局互斥。这样才能阻止“恢复准备进入 IMPORT”与“旧/新 MonitoringEngine 同时抢 Lease”的竞态。

## 245.8 StatisticsCache sourceDataVersion

统计缓存统一使用已有的 `sourceDataVersion` 表示所依据的源数据版本；旧版本的计算结果不得覆盖新版本缓存。

`StatisticsCache` 属于可重建派生数据，不应成为备份恢复成功所必需的事实数据。备份只需保存足以重建统计的源数据版本与业务源数据；恢复后可直接使缓存失效并从 `LiveSession` / Correction 等源数据重建。若备份为了加速恢复附带缓存，则该缓存必须被视为可丢弃项，不能覆盖更高版本的当前缓存。

## 245.9 Network Recovery Probe

网络传输层恢复后，必须先经过 Connectivity Probe 和 Bilibili API Probe，确认目标 API 实际可用后再进入 RecoveryCheck。

## 245.10 补充：Outbox Claim 必须是原子抢占

Outbox Worker 获取 `PENDING/RETRY_WAIT` 记录时，必须通过单条条件更新或等价的数据库事务完成“状态检查 + 写入 workerInstanceId + 设置 leaseUntil”。多个 Worker 同时竞争时最多只有一个能够成功 Claim；失败方必须重新读取记录，不得直接发送。

## 245.11 补充：事件聚合采用独立事件身份

`eventKey`、`transitionId`、`sessionId`、`aggregateId` 的职责必须分开：

```text
eventId / transitionId → 单个业务事件
sessionId              → 一场直播
aggregateId            → 一次通知展示聚合
eventKey               → Outbox 幂等身份
```

任何一个字段都不得被跨层复用为另一种语义。

## 245.12 补充：Notification 权限故障不得形成无限重试

`POST_NOTIFICATIONS` 被拒绝、通知渠道被用户关闭等明确的永久/用户操作型失败，不得按普通网络错误无限重试。Outbox 应进入可诊断的不可投递状态，并在用户恢复通知能力后重新评估是否还有发送价值。

## 245.13 P0/P1 稳定性不变量

```text
新实例不能被旧实例写污染
新观察不能被旧观察覆盖
删除对象不能被迟到响应复活
新配置不能改写已经开始的 Tick
新设置不能改写已经建立的聚合窗口
新统计不能被旧缓存覆盖
维护操作不能与业务写入互相覆盖
网络恢复不能被误判为 Bilibili API 已恢复
```

## 原规范 247：P0/P1 深层一致性修正（第三轮）【历史说明：不可直接实现】

本节继续补充跨重启时间、事务 fencing、状态机代际、事件唯一性和通知聚合冻结规则。它们属于在前两轮稳定性机制之上进一步收紧的组合一致性约束。

## 247.1 Persistent Lease 的跨重启时间语义

`System.currentTimeMillis()` 与 `SystemClock.elapsedRealtime()` 的职责必须分开。任何需要跨进程、跨任务生命周期判断超时的运行时逻辑，在同一次设备启动周期内优先使用单调时钟；持久化到数据库后必须同时具备可跨重启识别的启动代际信息。

建议：

```text
bootId
leaseUntilElapsed
leaseUntilWall
```

规则：

1. `bootId` 每次设备启动周期变化。
2. 同一 `bootId` 内，Lease / Outbox / Recovery 的持续时间判断优先使用 `elapsedRealtime`。
3. 发现持久化记录的 `bootId` 与当前不同，原有 elapsed deadline 不得直接继续使用；必须按安全失效/重新认领规则处理。
4. `wallClock` 仅作为持久化审计和跨重启辅助判断，不得单独作为严格的短时超时计时器。
5. 不得因为用户修改系统时间而把已经失效的 Lease 判定为长期有效。

## 247.2 Fencing 必须是原子 CAS，而不是先读后写

`fencingToken` 的校验必须与最终业务写入处于同一原子事务语义中，禁止采用：

```text
SELECT 当前 token
↓
应用层比较
↓
UPDATE / INSERT
```

必须采用带 token 条件的原子更新、数据库锁或等价的 compare-and-set 方案：

```text
UPDATE / INSERT
WHERE 当前 fencingToken == 本次 token
```

如果条件不成立：

```text
写入失败
↓
判定当前 Worker / Engine 已失去写权限
↓
停止本次业务提交
```

任何旧实例、旧 Worker 即使已经通过了更早的预检查，也不能凭借旧 token 完成提交。

## 247.3 StateDebouncer 必须绑定监控代际

`PendingTransition`、`confirmationCount` 等状态确认上下文必须绑定当前监控生命周期与主播监控生命周期。

以下任一事件发生时，旧确认上下文必须清空：

```text
监控实例代际改变
主播重新启用监控
主播从删除状态恢复监控
进入新的明确 Recovery 生命周期
```

推荐模型：

```kotlin
data class DebounceContext(
    val monitorGeneration: Long,
    val streamerMonitorGeneration: Long,
    val pendingTransition: PendingTransition,
    val confirmationCount: Int
)
```

数据库必须持久化确认上下文（唯一实体定义见 0.6.7，此处仅为字段对照，不得据此另建模型）：

```kotlin
// 唯一实体是 StreamerPendingTransitionEntity（见 0.6.7）。
// 本节历史上的 data class StreamerPendingTransition 已废弃，避免与 PendingTransition 枚举混淆。
// 字段语义与 0.6.7 逐项一致：streamerId(PK) / monitorGeneration / streamerMonitorGeneration /
// pendingTransition / confirmationCount / lastObservationSequence / updatedAt。
```

并提供数据库级 CAS：

```sql
UPDATE streamer_pending_transition
SET confirmationCount = :newCount,
    lastObservationSequence = :sequence,
    monitorGeneration = :monitorGeneration,
    streamerMonitorGeneration = :streamerGeneration
WHERE streamerId = :streamerId
  AND monitorGeneration = :expectedMonitorGeneration
  AND streamerMonitorGeneration = :expectedStreamerGeneration
  AND lastObservationSequence < :sequence;
```

返回影响行数为 0 时不得假定更新成功。进程重启后不得丢失确认次数；代际变化时必须清零旧上下文。

规则：旧生命周期的确认次数不得与新生命周期累加。

## 247.4 配置版本与业务事实版本分离

`configVersion` 表示“本次任务使用了哪套执行配置”，不等价于“业务事实是否仍然有效”。

影响 MonitoringTick 的配置必须归属于单一版本源。推荐统一放在 Room `monitoring_config` 单行表；DataStore 只保存不影响 Tick 的 UI 偏好。若暂时跨存储读取，不得宣称存在 ACID 跨存储原子快照。同一个 Tick 的所有批次必须使用同一 Snapshot。

### 247.4.1 MonitoringConfigSnapshot 单一版本源

任何影响 MonitoringTick 行为的配置必须归属于同一个版本源。推荐将以下字段统一持久化到 Room `monitoring_config` 单行表：

```text
monitoringEnabled
mode
interval
batchSize
maxConcurrency
timeout
retryPolicyVersion
aggregation/notification policy versions（仅当影响 Tick）
configVersion
updatedAt
```

DataStore 只保存 UI 偏好、主题、默认排序等不参与 Tick 业务决策的配置。若历史实现暂时横跨 Room + DataStore，则 `MonitoringConfigSnapshot` 必须以 Room 的 `configVersion` 为业务快照基准，并在读取时检测 DataStore 依赖；不能宣称跨两个存储引擎存在 ACID 原子快照。

同一个 Tick 的所有批次必须使用同一 `MonitoringConfigSnapshot`，不得中途读取新配置。

因此：

```text
配置改变
≠
旧观察事实自动失效
```

例如：

```text
旧 Tick 使用 configVersion = 10
新设置改为 configVersion = 11
```

如果旧 Tick 得到的是一个已经完成且可信的远程观察结果，不能仅因为配置版本不同就无条件删除该业务事实。

是否允许提交，应由：

```text
monitorGeneration

fencingToken
streamer 是否仍有效
observationSequence
```

共同决定；`configVersion` 主要用于审计、执行语义和需要严格依赖配置的结果，而不能被滥用为全局数据失效开关。

## 247.5 LiveEvent / LiveSession 事件唯一性

必须增加数据库级最终不变量：

```text
同一 transitionId
    → 最多一个有效 LiveEvent

同一 LiveSession
    → 最多一个有效 START Event
    → 最多一个有效 END Event
```

普通轮询、Recovery、重试、进程恢复等任何路径都必须经过统一事件创建 UseCase 或等价事务入口。

发生唯一约束冲突时：

```text
不得再次创建事件
↓
重新读取已有事件
↓
将其视为本次状态转换的既有结果
```

## 247.6 LiveSession 创建必须统一竞争入口

自动监控、Recovery、导入和用户手工修正若可能创建/补全同一场直播，都必须经过统一的 `LiveSessionCoordinator` 或等价的领域层入口。

数据库唯一约束仍是最终防线，但应用层不能依赖“插入失败”作为正常业务流程的唯一设计。

## 247.7 NotificationAggregate 的冻结时机

为了明确“5 秒内同时开播”的语义，当前 v2.0 采用：

```text
第一事件到达
↓
创建固定窗口 [T, T+5s]
↓
窗口内收集事件
↓
达到 threshold → READY
↓
继续收集直到窗口结束
↓
冻结 Aggregate
↓
交给 Outbox
```

达到阈值本身不立即改变窗口边界。这样：

```text
A 19:00:00
B 19:00:01
C 19:00:03
D 19:00:04
E 19:00:05
```

若 E 在固定窗口截止点以内，则仍可属于同一 Aggregate。

窗口一旦冻结，后续迟到事件不得修改该 Aggregate。

`threshold`、`windowStartWall`、`windowEndWall`、`configVersion` 在窗口创建时冻结；设置修改只影响后续窗口。

## 247.8 第三轮稳定性不变量

```text
持久化 Lease 不得把旧 boot 的 elapsed deadline 当作当前 deadline
fencing 必须以原子 CAS 语义保护最终写入
不同监控代际的 debounce 计数不得累加
configVersion 不得被误用为业务事实失效版本
同一 transitionId 不得生成多个有效 LiveEvent
同一 LiveSession 不得存在多个有效 START/END Event
所有 Session 创建路径必须经过统一竞争入口
Aggregate 达到阈值后仍遵循原固定窗口，窗口结束后才冻结
```

# 04 Bilibili 数据访问、请求协调与网络策略

## 原规范 8：Bilibili 数据访问设计【历史说明：不可直接实现】

## 8.1 API 适配原则

不要让业务层记住 Bilibili URL。

业务层只看到：

```kotlin
interface BiliLiveDataSource {
    suspend fun getRoomStatus(uidList: List<Long>): BatchRoomStatusResponse
    suspend fun getRoomDetail(roomId: Long): RemoteLiveRoom
}

// 批量响应必须明确表达请求集合、已返回结果、缺失 UID、重复 UID 与整体结构有效性。
// 唯一最终定义见 0.6.25，本节不得重复声明。字段全集（与 0.6.25 逐字一致）：
//   requestedUids: List<Long>             保留请求顺序，便于判定重复与缺失
//   resultsByUid:  Map<Long, RemoteLiveRoom?>  value 可为 null（有响应但资料无效，不等同于缺失）
//   missingUids:   Set<Long>
//   duplicateUids: Set<Long>
//   responseValidity: BatchResponseValidity   不得复用 ObservationResult
//                                            （ObservationResult 只描述单个 UID 的观察结果）
// BatchResponseValidity = { VALID, PARTIAL, INVALID, TRANSPORT_ERROR }（定义见 0.6.25）
```

实现类：

```kotlin
class BiliLiveApiDataSource(
    private val api: BiliLiveApi
) : BiliLiveDataSource
```

## 8.2 可参考的直播接口

公开的社区 API 文档目前记录了如下直播数据接口：

```text
GET https://api.live.bilibili.com/room/v1/Room/get_status_info_by_uids
```

它可按主播 UID 批量查询直播状态。

此外可使用：

```text
GET https://api.live.bilibili.com/xlive/web-room/v1/index/getRoomBaseInfo
```

查询直播间标题、分区、封面、主播名称等基础信息。

这些接口属于公开网络接口的实践资料，并不意味着 Bilibili 承诺其长期稳定。因此必须通过 RemoteDataSource 层进行隔离；不要在 UI 或 UseCase 中直接写 URL。

## 8.3 推荐的数据刷新流程

```text
Room UID list
     |
批量状态请求
     |
Remote DTO
     |
Mapper
     |
Domain Streamer
     |
State Transition Detector
     |
Room update
     |
Notification event
```

## 8.4 批量请求优先

不要一个主播一个 HTTP 请求。

例如有 80 个主播：

错误：

```text
80 个主播 -> 80 次串行 HTTP 请求
```

推荐：

```text
80 个主播 -> 分批构造请求 -> 批量获取 -> 本地匹配
```

每批大小必须由接口实际限制决定，并且配置化，例如：

```kotlin
const val DEFAULT_BATCH_SIZE = 50
```

但不得假设 50 永远有效，应允许后续调整。

---

## 原规范 24：网络请求策略【历史说明：不可直接实现】

## 超时

建议初始值：

```text
connectTimeout = 10s
readTimeout = 15s
writeTimeout = 15s
```

## 重试

仅对适合重试的错误：

```text
网络失败
超时
5xx
```

不要无限重试。

建议使用指数退避 + Full Jitter：

```text
基础等待：1 秒
指数因子：2
第 1 次上限：1 秒
第 2 次上限：2 秒
第 3 次上限：4 秒
实际等待 = random(0, 当前上限)
```

最高等待不超过 `maxRetryDelay` 配置上限。随机抖动用于避免大量主播在同一时刻失败后同时重试，形成二次请求峰值。

## 限流

多个主播同时刷新时必须：

```text
批量请求 > 并发限制 > 失败退避
```

不要创建数百个 Coroutine 同时请求 Bilibili。

---

## 原规范 23：错误处理规范【历史说明：不可直接实现】

统一错误类型：

```kotlin
sealed interface AppError {
    data object NetworkUnavailable : AppError
    data class Http(val code: Int) : AppError
    data object Timeout : AppError
    data object Unauthorized : AppError
    data object RateLimited : AppError
    data object ParseFailed : AppError
    data object BilibiliApiError : AppError
    data object DatabaseError : AppError
    data object NotificationPermissionDenied : AppError
    data object Unknown : AppError
}
```

不要把原始 Exception 文本直接显示给用户。

UI 使用：

```text
网络异常，请稍后重试

Bilibili 登录状态已失效，请重新登录

请求过于频繁，请降低检查频率
```

日志中可以保存技术细节，但敏感数据必须脱敏。

---

## 原规范 46：监控批次、并发和熔断【历史说明：不可直接实现】

新增：

```kotlin
data class MonitorBatchPolicy(
    val batchSize: Int,
    val maxConcurrency: Int,
    val maxRetries: Int,
    val retryBaseSeconds: Long,
    val maxRetryDelaySeconds: Long,
    val circuitBreakerThreshold: Int,
    val cooldownSeconds: Long
)
```

逻辑：

```text
批量请求
 ↓
成功 → 正常
 ↓
部分响应/缺失 UID → 缺失主播标记为 PARTIAL/UNKNOWN，不得视为 OFFLINE
 ↓
部分失败 → 只记录失败批次
 ↓
连续失败达到阈值
 ↓
Circuit Breaker
 ↓
临时降低请求频率
 ↓
成功后逐步恢复
```

不得因为一个主播请求失败而停止整个监控系统。

---

## 原规范 167：P0-2 RefreshCoordinator 请求协调器【历史说明：不可直接实现】

## 167.1 问题

以下请求可能同时出现：

```text
用户下拉刷新
后台定时刷新
网络恢复刷新
App启动恢复刷新
手动点击主播刷新
```

如果不协调，可能重复请求和重复更新数据库。

## 167.1.1 同请求合并与取消语义

同一刷新 key 在短时间内重复请求时，实际 HTTP 请求只保留一个；多个调用方共享同一次执行结果。

```text
UI 刷新 A ─┐
后台 Tick ─┼→ 一个 RefreshOperation → 一个 HTTP
通知点击 ─┘
```

单个调用方取消等待，不得自动取消其他调用方仍在等待的共享请求。只有当没有有效消费者且请求已无业务价值时，Coordinator 才可以取消底层 HTTP。

## 167.2 规则

所有刷新必须进入：

```text
RefreshCoordinator
```

同一主播或同一批 UID 在短时间内只能存在一个有效刷新任务。相同刷新 Key 在已有任务 RUNNING 时必须复用该任务，不得重复发起 HTTP。后续调用方作为等待者订阅同一任务结果；任务完成后把相同结果分发给所有等待者。只有请求参数或刷新原因确实不同且需要更新版本时，才建立新的有效任务。

建议状态：

```text
IDLE
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELLED
```

## 167.3 合并请求

例如：

```text
后台请求 [1,2,3]
用户请求 [2,3,4]
```

可以合并成：

```text
[1,2,3,4]
```

但不得因为合并而降低用户主动刷新请求的优先级。

每次实际发起的刷新必须带 `refreshGeneration` 或单调递增 `observationSequence`。数据库事务只允许应用不早于当前记录的最新序号结果；旧请求晚到时必须被丢弃，防止“旧响应覆盖新响应”。这项保护必须位于 Repository/事务层，而不能只依赖内存中的 Coordinator。

`observationSequence` 应按 `streamerId` 独立维护或至少能映射到主播级最新观察版本。不得只把“整批请求编号”当作最终覆盖判断依据。推荐数据库保存：

```text
latestObservationSequence
latestObservationGeneration
```

提交结果时必须满足：

```text
incomingSequence > storedSequence
```

否则丢弃该结果。

另外，取消也必须贯穿完整调用链：

```text
MonitoringController
→ Scheduler
→ RefreshCoordinator
→ Coroutine
→ HTTP Call
```

任务取消后，底层网络请求必须尽快取消；即使网络层取消不及时，Repository 仍必须依靠 generation/sequence 拒绝这次已失效结果。

批量 API 返回中缺失某个已请求 UID 时，该 UID 只能记为 `PARTIAL/UNKNOWN`，不得推断为 OFFLINE。

---

## 原规范 180：P1-1 API 熔断器【历史说明：不可直接实现】

新增：

```kotlin
ApiCircuitBreaker
```

熔断状态建议至少按 API 类别隔离，而不是一个全局 Bilibili 熔断器。至少区分：

```text
LIVE_STATUS
ROOM_METADATA
FOLLOWING
AUTH
```

其中 `LIVE_STATUS` 必须独立，避免资料接口或登录接口短时故障连带停止已有主播的公开直播状态监控。

状态：

```text
CLOSED
OPEN
HALF_OPEN
```

建议逻辑：

```text
连续失败达到阈值
↓
OPEN
↓
暂缓普通请求
↓
等待冷却
↓
HALF_OPEN
↓
只允许一个 HALF_OPEN Probe
↓
成功 -> CLOSED
失败 -> OPEN
```

`HALF_OPEN` 状态必须有并发门闩/租约，保证同一时间最多一个探测请求；其他请求必须等待、合并或直接拒绝，不得并发放行多个 Probe。不能无限重试。

---

## 原规范 200：Bilibili 检测接口配置【历史说明：不可直接实现】

## 200.1 设计目标

Bilibili 的网络接口可能发生变化，因此不得把具体 URL、请求参数和接口策略散落在业务代码中。

用户可以在高级设置中查看当前实际使用的监控数据源，但默认不要求普通用户修改。

建议配置模型：

```kotlin
data class BiliApiSettings(
    val liveStatusEndpoint: String,
    val roomDetailEndpoint: String,
    val requestMode: ApiRequestMode,
    val timeoutSeconds: Int
)

// 重试、批次冷却和熔断参数不在此重复保存；统一由 MonitorBatchPolicy / AdvancedNetworkSettings 提供。
```

## 200.2 Endpoint 配置

开发/测试版本允许配置；正式开源发布版本默认关闭任意 Endpoint 编辑，仅显示当前数据源和版本信息。

```text
直播状态接口
直播间资料接口
账号接口
关注列表接口
```

但必须区分：

```text
官方已确认接口
社区公开接口
开发者自定义接口
```

界面必须显示数据来源和风险提示。

正式发布版本默认使用开发者提供的安全预设，不建议普通用户随意填写第三方地址。

## 200.3 Endpoint 安全校验

用户自定义 URL 必须：

```text
仅允许 HTTPS
```

正式发布版本默认不开放任意自定义 Endpoint；开发者模式如允许配置，必须启用独立的 `CustomEndpointClient`，不得复用携带 Bilibili Cookie、Token 或 Authorization Header 的认证客户端。还应对 Host、重定向和凭证作用域进行独立校验。

默认拒绝：

```text
file://
content://
javascript:
intent://
本地任意端口
```

并禁止通过自定义接口配置绕过登录安全、证书校验或应用内部权限控制。

不得允许普通接口配置直接执行任意代码。

---

## 原规范 201：检测冷却时间与轮询策略【历史说明：不可直接实现】

用户可以自定义（仅在当前监控模式允许的范围内）：

```text
实时检测间隔
批次之间间隔
错误重试间隔
恢复检查延迟
```

实时监控模式最终可选值：

```text
30 秒
60 秒
90 秒
120 秒
300 秒
```

高级自定义仍使用同一个 `intervalSeconds` 配置字段；当前实时模式允许的安全范围为 **15～3600 秒**，15 秒仅是高级自定义下限，不作为普通快捷选项。

省电监控模式使用 WorkManager 等系统调度，不与实时轮询共用“秒级”参数；省电模式应单独定义分钟级调度参数，并明确存在系统调度弹性。

实时模式可允许高级自定义值，例如：

```text
15 ~ 3600 秒
```

但必须设置应用安全上下限，不能让用户配置成：

```text
0 秒
1 秒
5 秒
```

从而产生过高请求频率。

## 201.1 配置优先级

最终生效间隔：

```text
主播独立设置
    ↓
分组默认设置（如启用）
    ↓
全局监控设置
    ↓
应用安全最小值
```

高级设置只是同一全局配置的参数化入口，不建立独立的“高级设置生效值”。

无论用户如何配置，都不能低于应用安全最小值。这里的“应用安全最小值”是本软件为控制请求量、耗电和风控风险设定的策略，不等同于 Android 平台硬性要求。

## 201.2 动态调整

检测间隔发生修改后：

```text
修改设置
    ↓
MonitoringController 收到配置变化
    ↓
取消旧调度
    ↓
应用新策略
    ↓
建立新调度
```

不得同时存在两个旧的监控循环。

## 201.3 MonitoringConfigSnapshot

每次真正开始执行 `MonitoringTick` 时必须生成不可变配置快照：

```kotlin
// 唯一最终定义见 0.6.16，必须逐字段一致。本节不得重复声明（避免第二个同名类型）。
// 历史 6 字段版本已废弃，原因是：
//  - intervalSeconds 在最终契约中为 Int（不是 Long）
//  - 不得只携带 retryPolicyVersion；重试参数必须显式进入快照，否则旧 Tick 无法重放
//  - 必须包含 aggregationEnabled / aggregationThreshold / aggregationWindowSeconds /
//    batchCooldownSeconds / circuitBreakerRecoverySeconds，否则聚合窗口与熔断恢复无法按同一快照执行
//
// 字段全集（与 0.6.16 逐字一致，仅供核对）：
//   configVersion, monitoringEnabled, mode, intervalSeconds, batchSize, maxConcurrency,
//   timeoutSeconds, maxRetries, retryBaseSeconds, maxRetryDelaySeconds,
//   circuitBreakerThreshold, circuitBreakerRecoverySeconds, aggregationEnabled,
//   aggregationThreshold, aggregationWindowSeconds, batchCooldownSeconds
```

同一 Tick 在整个生命周期内只能使用该 Snapshot。执行过程中修改设置只影响后续 Tick，不得使同一 Tick 前后读取两套参数。

---

## 原规范 202：高级请求策略【历史说明：不可直接实现】

建议允许用户调整：

```text
连接超时时间
读取超时时间
重试次数
指数退避上限
批量请求大小
批次间冷却
连续失败阈值
熔断恢复时间
```

所有设置必须保存为：

```kotlin
data class AdvancedNetworkSettings(
    val connectTimeoutSeconds: Int,
    val readTimeoutSeconds: Int,
    val maxBatchSize: Int,
    val batchCooldownSeconds: Int,
    val circuitBreakerThreshold: Int,
    val circuitBreakerRecoverySeconds: Int
)

`MonitorBatchPolicy.maxRetries` 是重试次数唯一权威来源；不得在 `AdvancedNetworkSettings` 中维护 `maxRetryCount` 的第二份副本。生成 `MonitoringConfigSnapshot` 时一次性读取统一配置并冻结。
```

每个参数都需要：

```text
默认值
最小值
最大值
推荐值
```

---

## 原规范 203：高级设置的安全护栏【历史说明：不可直接实现】

用户即使进入高级设置，也不能破坏核心安全机制。

以下能力不得关闭：

```text
网络异常不能伪装成 OFFLINE
数据库事务
数据来源记录
数据可信度
通知去重
认证凭证保护
日志脱敏
导出隐私保护
```

以下能力可以调整：

```text
检查间隔
重试次数
批量大小
超时
日志保留时间
通知冷却时间
各主播 LiveSession 容量策略（UNLIMITED / MAX_RECORDS=N）
```

直播历史不再使用统一的“按天数保留”策略；历史数据容量统一按照第 56 节定义的“按主播记录条数”规则管理。

---

## 原规范 204：高级设置恢复默认值【历史说明：不可直接实现】

支持：

```text
恢复当前参数默认值
恢复全部高级设置
```

恢复后必须立即显示：

```text
已恢复默认设置
```

并重新通知相关后台组件应用新配置。

恢复默认不会删除：

```text
主播
标签
分组
直播历史
通知历史
错误日志
```

---

# 05 监控控制器、调度与 Android 后台运行

## 原规范 10：MonitoringEngine 设计【历史说明：不可直接实现】

核心接口：

```kotlin
interface MonitoringEngine {
    suspend fun checkOnce(): MonitoringResult
    suspend fun startContinuous()
    suspend fun stop()
}
```

推荐的单轮流程：

```kotlin
suspend fun checkOnce(snapshot: MonitoringConfigSnapshot, lease: MonitoringLease) {
    val monitored = repository.getEnabledStreamers()

    for (batch in monitored.chunked(snapshot.batchSize)) {
        val response = remoteDataSource.getRoomStatus(batch.map { it.uid })

        for (local in batch) {
            val remote = response.resultsByUid[local.uid]
            val observation = observationMapper.toPerStreamerObservation(
                requestedUid = local.uid,
                response = response,
                remote = remote
            )

            val transition = detector.detect(
                previous = local.confirmedLiveStatus,
                observation = observation,
                pending = local.pendingTransition
            )

            repository.applyConfirmedObservationAtomically(
                remote = remote,
                transition = transition,
                observation = observation,
                observationSequence = observation.sequence,
                monitorGeneration = lease.monitorGeneration,
                fencingToken = lease.fencingToken,
                configVersion = snapshot.configVersion
            )
        }
    }
}
```

// 该方法不是普通 CRUD。DAO 必须使用带 WHERE 条件的 CAS/版本校验，
// 不允许“先读出实体 → 内存判断 → 无条件 UPDATE”。

强制接口签名（唯一签名见 0.6.15）：

```kotlin
suspend fun applyConfirmedObservationAtomically(
    remote: RemoteLiveRoom?,
    transition: DetectedTransition,
    observation: StreamerObservation,
    observationSequence: Long,
    monitorGeneration: Long,
    fencingToken: String,
    configVersion: Long
): ApplyObservationResult
```

实现层必须在同一 Room Transaction 内完成：确认状态、观察结果、PendingTransition/计数、ReliableMonitorInterval、StatusHistory、LiveEvent、LiveSession、sourceDataVersion、NotificationAggregate/PENDING_AGGREGATION、NotificationOutbox 等相关变更。若 CAS 条件失败，只能返回 `STALE_WRITE`，不得“放宽条件后重试覆盖当前值”。

注意：如果一次请求失败，不能让整个任务失败；应该尽可能更新成功的主播，并记录失败信息。

---

## 原规范 41：MonitoringController / MonitoringScheduler【历史说明：不可直接实现】

v2.0 新增统一监控控制层。任何页面、通知动作、BootReceiver、设置修改都不能直接控制底层 Service。

核心接口：

```kotlin
interface MonitoringController {
    suspend fun start(mode: MonitorMode): Result<Unit>
    suspend fun stop(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun resume(): Result<Unit>
    suspend fun refreshNow(): Result<Unit>
    fun observeRuntimeState(): Flow<MonitoringRuntimeState>
}
```

调度器负责：

```text
用户开启监控
设置修改
App 前台/后台切换
网络恢复
系统重启
应用更新
服务异常退出
监控模式降级
```

统一流转：

```text
UI / Receiver
     ↓
MonitoringController
     ↓
MonitoringScheduler
     ↓
MonitoringEngine
     ↓
Repository
```

禁止：

```text
HomeScreen -> startService()
SettingsScreen -> startService()
BootReceiver -> 自己执行监控
NotificationReceiver -> 自己请求 Bilibili API
```

## 41.1 MonitoringTick 不重叠与合并调度

同一 `MonitoringEngine` 生命周期内，同一时刻最多允许一个 `MonitoringTick` 处于 `RUNNING`。调度器不得使用会产生并行重叠执行的 fixed-rate 方式直接启动检查。

如果下一次计划时间到达而上一轮仍在运行：

```text
上一轮 RUNNING
    ↓
不创建第二轮 Tick
    ↓
记录 missedTick = true
    ↓
上一轮结束
    ↓
若仍满足运行条件，则合并为下一次 Tick
```

`missedTick` 不应形成无限任务队列；连续多个到期点最多合并为一次补偿 Tick。停止、暂停、Recovery、模式切换等控制事件优先于普通补偿 Tick。

---

## 原规范 42：MonitoringRuntimeState【历史说明：不可直接实现】

“用户要求监控”和“当前实际正在监控”必须分开。

```kotlin
data class MonitoringRuntimeState(
    val desiredRunning: Boolean,
    val actualRunning: Boolean,
    val mode: MonitorMode,
    val monitoredCount: Int,
    val lastKnownLiveCount: Int,
    val lastAttemptAt: Long?,
    val lastSuccessfulCheckAt: Long?,
    val lastRemoteSuccessAt: Long?,
    val consecutiveFailures: Int,
    val currentBackoffSeconds: Long?,
    val runtimeInstanceId: String?,
    val monitorGeneration: Long,
    val health: MonitoringHealth,
    val lastError: AppError?
)

enum class MonitoringHealth {
    HEALTHY,
    DEGRADED,
    RECOVERING,
    PAUSED,
    STOPPED,
    SYSTEM_RESTRICTED,
    USER_ACTION_REQUIRED,
    ERROR
}
```

UI 必须明确区分：

```text
监控意图：已开启
运行状态：正常
```

与：

```text
监控意图：已开启
运行状态：已停止 / 系统限制 / 网络异常
```

## 42.1 MonitoringEngine 单实例运行租约

`MonitoringController` 必须保证同一时刻只有一个实例拥有“主动执行监控”的运行租约。不能仅依赖 Service 数量、Coroutine Job 是否存在或内存布尔值判断单实例。

MaintenanceMode 与 MonitoringLease 必须使用数据库原子互斥，推荐：

```text
单行 system_runtime_lock 表
+ Room Transaction
+ SQLite BEGIN IMMEDIATE 语义
```

获取 MonitoringLease 时：

```text
同一事务检查 maintenanceMode == OFF
→ 写入新 generation + fencingToken
→ 提交
```

进入 MaintenanceMode 时：

```text
同一事务写 maintenanceMode = ON
→ 使现有 Lease 失效/标记 fencingToken 不再有效
→ 提交
```

禁止只使用内存 `Mutex` 或 Boolean 实现跨组件互斥。

建议增加：

```text
MonitoringLease
----------------
leaseId
runtimeInstanceId
monitorGeneration
acquiredAt
renewedAt
bootId
leaseUntilElapsed
leaseUntilWall
fencingToken
status
```

启动时：

```text
申请运行租约
   ↓
成功 → 启动 MonitoringEngine
失败 → 复用/等待已有实例，不得启动第二个主动监控实例
```

运行期间定期续租。实例崩溃后，新的实例只有在 `leaseUntil` 过期或旧实例明确释放租约后才能接管。接管时必须生成新的 `runtimeInstanceId`，并递增 `monitorGeneration`，并获得新的 `fencingToken`。

所有由 MonitoringEngine 发起、可能改变运行态或业务数据的关键写操作都必须携带当前 `fencingToken`。Repository/事务层必须校验该 token 仍属于当前有效 MonitoringLease；旧实例即使在租约过期后恢复执行，也不得继续提交状态、健康、失败计数、Recovery 或其他监控业务写入。

数据库中的租约是最终一致性依据；内存锁只能作为同进程优化，不能作为跨进程/崩溃恢复的唯一防线。

手工重新添加一个已经软删除的 UID 时，不得创建第二条 `Streamer` 记录：

```text
重新添加已软删除 UID
    ↓
恢复原 Streamer 记录
    ↓
清除 deletedAt
    ↓
保留 stableId / 历史 / 用户配置
```

因此 `uid UNIQUE` 与软删除可以同时成立；重新添加的业务语义是“恢复原实体”，而不是新建实体。

---

## 原规范 58：Android 后台策略 v2【历史说明：不可直接实现】

## 58.1 省电模式

使用 WorkManager 等系统调度机制执行低频检查，不保证秒级实时性。

## 58.2 用户主动实时模式

只有当用户明确开启实时监控时，才进入实时监控策略。开源版本可以提供前台服务、系统允许的其他后台机制，以及经过用户明确知情并主动授权的 AccessibilityService 高级辅助方案。不同方案必须分别显示实际运行状态、耗电影响和系统限制，不得声称任何方案能够保证永久运行。

Android 14+ 要求 Foreground Service 声明适当类型及对应权限；不匹配时系统可能拒绝启动。

对于本项目，不能为了“永远轮询”而强行选择 `dataSync`。Android 15+ 目标版本下，dataSync 类型前台服务有 24 小时窗口内累计 6 小时的运行限制。

如业务场景确实符合 `specialUse`，才使用 `specialUse`；必须在 Manifest 中提供清晰用途说明，并考虑 Google Play 审核要求。

## 58.3 恢复策略

当 FGS 不可继续时：

```text
实时监控
 ↓
服务被停止 / 超时 / 系统限制
 ↓
记录 DEGRADED
 ↓
转入允许的低频调度
 ↓
网络/系统条件恢复
 ↓
再次根据当前版本规则启动实时方案
```

禁止设计“绕过 Android 限制”的技巧。

## 58.4 后台状态提示

用户必须能看到：

```text
实时监控
● 正常运行
```

或者：

```text
实时监控
⚠ 当前受系统限制，已切换到省电模式
```

而不是虚假显示“后台运行中”。

---

## 原规范 11：后台监控设计【历史说明：不可直接实现】

## 11.1 不采用“后台无限线程”

不能使用：

```kotlin
while (true) {
    check()
    delay(30000)
}
```

并希望 Android 永远允许它运行。

Android 12 及以上对后台启动 Foreground Service 有限制；Android 14 及以上要求声明对应的 Foreground Service 类型。Android 官方也明确建议根据工作性质使用 WorkManager、用户发起任务或 Foreground Service，而不能把后台服务当成无限制常驻进程。

## 11.2 运行模式

应用提供三种监控模式：

### 模式 A：关闭监控

不执行任何后台检查。

### 模式 B：省电监控

使用 WorkManager 执行周期检查。

适合：

- 主播数量少
- 对秒级通知不敏感
- 用户希望降低耗电

注意：WorkManager 的周期任务存在系统调度弹性，官方文档给出的周期粒度下限为 15 分钟，因此不能将它当成 30 秒实时监控方案。

### 模式 C：实时监控

用户主动开启后启动 Foreground Service。

服务通过固定前台通知维持运行，并以可配置间隔进行批量轮询。

默认：

```text
检查间隔：60 秒
网络失败重试：指数退避
并发请求：受限
```

可选：

```text
30 秒
60 秒
90 秒
120 秒
300 秒
```

v2.0 当前版本统一使用 `30 / 60 / 90 / 120 / 300` 秒作为可选检查间隔，默认 `60` 秒。

不要默认设置成 5 秒，以降低服务器压力、耗电量和账号风控风险。

## 11.3 Android 14+ 前台服务类型

项目必须在 Manifest 中根据实际前台服务用途声明合法的 `foregroundServiceType` 和相应权限。Android 14 起这不是可选项。

对于本项目这种“用户主动要求长期执行网络轮询”的特殊场景，若选择 `specialUse`，必须在 Manifest 中填写明确的用途说明，并在发布到应用商店时遵守平台审核要求；不能为了绕过系统限制而随便使用其他不匹配的类型。

不要把 `dataSync` 当作永久后台监控的万能类型，尤其是针对 Android 15+ 的目标版本，因为系统已经对 dataSync 类型前台服务的长期运行增加了时间约束。

## 11.4 电池优化

设置页增加：

```text
后台运行保护
[已开启/未开启]

系统可能因为省电策略停止后台任务。
[去系统设置]
```

只能引导用户进行系统设置，不能声称“100% 杀不死”。不同厂商 ROM 仍可能存在额外限制。

## 11.5 重启恢复

监听：

```text
BOOT_COMPLETED
MY_PACKAGE_REPLACED
```

读取数据库中的监控设置。

如果用户曾开启“实时监控”，按照 Android 当前版本允许的方式恢复调度；不要在所有版本上直接从 Receiver 无条件启动 Foreground Service。

---

## 原规范 30：Manifest 设计要点【历史说明：不可直接实现】

最终的 Manifest 会根据 targetSdk 版本和实际监控方案动态确定权限。

至少需要认真处理：

```text
INTERNET
POST_NOTIFICATIONS（Android 13+）
FOREGROUND_SERVICE
```

如使用特定 Foreground Service 类型，还要声明该类型对应的权限与 `android:foregroundServiceType`。

如果涉及精确闹钟，不要默认申请 `SCHEDULE_EXACT_ALARM`。Android 官方要求只在确有用户可见、时间敏感用途时使用精确闹钟。

本项目正常监控不应依赖精确闹钟。

---

## 原规范 237：前台常驻通知监控模式【历史说明：不可直接实现】

本项目可以提供：

```text
前台监控通知
```

作为用户主动选择的监控运行方式。

Foreground Service 的系统通知本身具有明确的用户可见性，适合表达“应用正在执行持续监控任务”。Android 官方将前台服务定位为用户能够感知的持续任务，并要求遵守相应启动、权限和服务类型规则。

## 237.1 用户控制

设置：

```text
后台监控方式

○ 关闭
● 常驻通知监控
○ 系统允许的省电监控
```

实时监控启动应优先由用户在应用可见状态下主动操作，以符合 Android 12+ 后台启动限制。

## 237.2 常驻通知内容

前台监控通知可以显示：

```text
Bilibili 主播监控
监控中：86 位主播
最后检查：22:31:08
状态：运行正常
```

同时提供操作：

```text
[暂停监控]
[打开应用]
```

通知更新应节流，避免每次主播状态变化都频繁刷新服务通知。

---

## 原规范 238：无障碍服务使用边界【历史说明：不可直接实现】

本项目为开源软件，可将 AccessibilityService 作为**实验性高级后台运行扩展**提供给愿意自行承担系统兼容性差异的用户。该能力不是核心功能，不默认开启，用户必须主动阅读用途说明并在 Android 系统设置中完成授权。

使用前 UI 必须明确告知：

```text
此功能会启用 Android 无障碍服务。
它可能增加后台稳定性，但可能带来额外耗电、兼容性和系统行为差异。
是否启用由用户自行决定。
```

设计原则：

```text
用户明确知情
        ↓
用户主动授权
        ↓
AccessibilityService 独立模块
        ↓
向 MonitoringController 提供“保活辅助能力”状态
```

该服务不得读取、上传或记录与主播监控无关的屏幕内容、输入事件或个人数据；实现时应采用最小权限原则。

它也不能成为 MonitoringEngine 的唯一运行基础：

```text
AccessibilityService 可用
    ↓
作为可选增强

AccessibilityService 不可用
    ↓
MonitoringController 仍使用系统允许的其他监控路径
```

如果用户关闭或撤销无障碍授权，软件必须检测到这一状态并将运行状态更新为对应的 `DEGRADED` / `SYSTEM_RESTRICTED`，而不是假装后台监控仍然正常。

---

## 原规范 239：后台运行模式最终模型【历史说明：不可直接实现】

最终建议：

```text
                    MonitoringController
                           │
        ┌──────────────────┼──────────────────┐
        ↓                  ↓                  ↓
     前台监控         前台服务监控        省电监控
     App Visible      User-visible FGS      WorkManager
        │                  │                  │
        └──────────────────┼──────────────────┘
                           ↓
                    MonitoringScheduler
                           ↓
                    MonitoringEngine
```

用户界面只显示：

```text
监控目标：已开启
实际状态：运行中 / 已停止 / 受系统限制 / 异常
```

不能只显示一个含义不清的：

```text
监控 ON
```

---

## 原规范 199：高级设置与可配置化系统（新增 P1）【历史说明：不可直接实现】

本项目支持将部分高级运行参数开放给用户自行调整，但必须遵循：

```text
可配置 ≠ 无限制
可配置 ≠ 允许用户破坏系统
```

所有高级参数都必须经过合法范围校验，并提供：

```text
当前值
推荐值
允许范围
参数说明
恢复默认值
恢复全部高级设置
```

高级设置默认隐藏在：

```text
设置
  ↓
高级设置
```

普通用户无需修改即可正常使用。

---

# 06 通知业务、聚合、Outbox 与历史

## 原规范 12：通知系统【历史说明：不可直接实现】

## 12.1 通知类别

至少建立 3 个 Notification Channel：

```text
live             直播状态相关通知（“正在直播”与关播）
monitor_service  后台监控状态通知
system           系统错误/登录失效/配置错误
```

普通开播确认与异常恢复后确认仍在直播均使用 `live` Channel，并统一使用“正在直播”这一用户可见语义。事件来源仍需独立记录，不能因为文案一致而把业务事件合并。

另外建议：

```text
system           系统错误/登录失效/配置错误
```

## 12.2 “正在直播”通知

普通开播确认与异常恢复后重新确认仍在直播，统一使用同一套用户可见文案。

标题示例：

```text
恬豆发芽了 正在直播
```

内容：

```text
恬豆正在直播：今天继续玩游戏
```

不得默认使用“刚刚开播”“刚才开播”等无法由恢复检查证明的表述。恢复检查只能证明当前仍在直播；它不能证明真实开播时刻。

显示：

- 主播头像
- 主播昵称
- 当前标题
- 分区
- 点击跳转直播间

## 12.3 关播通知

关播通知只允许在**当前连续可靠监控区间**具备资格时发送。一次断网、服务异常或其他监控异常不会永久禁止该场直播后续发送关播通知；如果恢复时主播仍在直播，则从恢复确认 LIVE 的时刻重新建立一个新的监控区间。该新区间持续正常直到确认 OFFLINE 时，可以发送关播通知。

标题：

```text
恬豆 已下播
```

内容：

```text
本次直播结束
```

以下情况不得发送：

```text
10:00 开播
10:01 断网
11:00 恢复并发现已经下播
→ 不发送关播通知
```

以下情况可以发送：

```text
10:00 开播
10:01 断网
11:00 恢复并发现主播仍在直播
11:00 之后持续获得可靠 SUCCESS 观察，没有新的异常
12:00 确认下播
→ 发送关播通知
```

如果当前连续监控区间发生异常，则该区间不再具有关播通知资格；但之后若恢复时主播仍在直播，可以从新的恢复确认点重新建立资格。只有“当前这一个连续区间”最终无异常并可靠确认 OFFLINE 时，才发送关播通知。历史异常本身只影响已经结束的旧区间，不会永久污染后续新区间。

可靠时长存在盲区时，也不得把估算值包装成精确事实。

## 12.4 通知去重键

通知业务事件必须生成稳定、与业务事件一一对应的 `eventKey`；开播/下播事件应关联稳定的 LiveSession/Event 身份，不得把本地自增 `sessionId` 当作跨设备身份。

数据库保存 Outbox 与通知历史，通过 `eventKey` / `notificationId` 完成幂等与追踪。

这样即使：

```text
网络超时 -> 重试 -> 成功 -> 再次重试
```

也不会重复通知。

## 12.5 Android 13+ 通知权限

Android 13 及以上需要处理 `POST_NOTIFICATIONS` 运行时权限。

首次进入实时监控功能时：

```text
解释为什么需要通知权限
      ↓
请求通知权限
      ↓
用户允许 → 启用通知
用户拒绝 → UI 明确提示“后台仍可监控，但无法正常发出通知”
```

---

## 原规范 26：Notification Outbox（最终规范）【历史说明：不可直接实现】

Notification Outbox 是核心可靠性组件，不是后续可选增强。任何需要向用户发送开播、下播、批量聚合或可靠系统通知的业务事件，都必须先形成持久化 Outbox 记录，再由 Dispatcher 异步发送。

```text
业务事务
├── 业务事实
├── LiveEvent
└── NotificationOutbox
          ↓
NotificationDispatcher
          ↓
Android NotificationManager
```

禁止任何业务 UseCase 绕过 Outbox 直接调用 `NotificationManager.notify()`。

Outbox 必须具备稳定 `eventKey`、稳定 `notificationId`、Claim 租约、`deliveryAttemptId`、过期策略和可恢复状态机。系统通知层不能声称绝对 exactly-once，只能保证同一业务事件拥有稳定的通知身份并尽量避免重复显示。

## 原规范 47：Notification Outbox v2【历史说明：不可直接实现】

通知系统从“状态变化后直接 notify”升级为：

```text
状态确认
    ↓
创建 LiveEvent
    ↓
Room Transaction
    ├── 更新主播状态
    ├── 写 LiveSession
    ├── 写 StatusHistory
    └── 写 NotificationOutbox
              ↓
       NotificationDispatcher
              ↓
        Android Notification
              ↓
        成功 → SENT
        失败 → RETRY
```

数据模型（**唯一实体为 `NotificationOutboxEntity`，见 0.6.9.1；本节不再声明第二套模型**）：

```kotlin
// 历史版本 data class NotificationOutbox 已废弃。与最终实体的关键差异：
//  - id: Long            → outboxId: String（跨设备稳定身份，禁止用本地自增 id）
//  - claimedAt           → processingStartedAt
//  - leaseUntilElapsedRealtime → leaseUntilElapsed
//  - leaseUntilWallClock → leaseUntilWall
//  - expiresAt: Long?    → expiresAt: Long（非空；过期语义必须有确定值）
//  - notificationId: Int → 非空且只能来自 notification_id_registry
//  - 新增 sourceEventId（单事件通知幂等锚点，见 0.6.9.1）、problemKey（系统问题通知）、configVersion
//  - 三种形态互斥：单主播 / 批量 / 系统问题；不得再假定"二者互斥"
```

状态：

```text
PENDING
PROCESSING
DELIVERY_UNKNOWN
SENT
RETRY_WAIT
FAILED
EXPIRED
CANCELLED
```

`eventKey` 必须唯一，并且必须对应一个独立的业务事件身份，推荐使用 `eventId`，或使用包含 `streamerId + sessionStableId/transitionId + eventType` 的稳定组合键。不得只使用 `streamerId + eventType`，否则主播再次开播时可能被错误去重。

单主播通知与批量通知必须在模型层明确区分：

```text
单主播通知：streamerId != NULL，aggregateId = NULL
批量通知：  streamerId = NULL，aggregateId != NULL
```

二者不能同时有值，也不能同时为空。批量通知的主播集合必须通过 `NotificationAggregate` 及其关系记录取得，不得把“第一个主播”冒充为批量通知所属主播。

另外，`eventKey` 唯一并不能保证 Android 系统通知 exactly-once。NotificationDispatcher 必须使用由 `eventKey`/`aggregateId` 派生的稳定 `notificationId`，重复发送同一事件时应更新同一条系统通知，而不是创建新的通知。

### 47.1 notificationId 持久化注册表

通知身份必须由数据库注册表管理：

```text
notification_id_registry
-------------------------
eventKey TEXT PRIMARY KEY
notificationId INTEGER NOT NULL UNIQUE
createdAt INTEGER NOT NULL
```

`notificationId` 的“稳定”语义是：**同一安装中的同一业务事件重试/恢复时保持相同整数 ID**。不得要求不同设备、不同数据库安装之间复用同一个 Android notificationId。跨设备恢复也不得依赖它建立业务身份。

分配过程必须在数据库事务中完成：

```text
eventKey 已存在
→ 直接复用 notificationId

eventKey 不存在
→ 分配候选 Int
→ 检查 UNIQUE(notificationId)
→ 冲突则重新分配
→ 写入 registry
```

不得直接使用普通 `hashCode()` 作为最终 ID。

由于 Android `notificationId` 为 `Int`，不得把不可控的普通 `hashCode()` 直接视为最终身份。实现必须使用可持久化的稳定映射或等价碰撞检测机制，确保不同业务事件不会无提示地共用活动通知身份；若发生碰撞，必须重新分配并持久化映射，不得覆盖另一业务事件的通知。

`status` 是 Outbox 发送生命周期的唯一事实来源；`sentAt` 只是 `SENT` 状态的辅助时间字段，不得通过 `sentAt != null` 单独推断已发送。`PROCESSING` 必须具有租约：至少保存 `processingStartedAt`、`leaseUntilElapsed`、`leaseUntilWall`、`leaseBootId`、`workerInstanceId`。同一 `leaseBootId` 内以 elapsedRealtime 判断；跨启动周期不得直接比较旧 elapsed 值，必须按安全回收规则重新认领，不能永久卡死。

通知发送存在一个不可完全消除的窗口：Android 系统通知可能已经显示，但 App 还未来得及把数据库状态写成 `SENT` 就崩溃。因此不能声称系统 UI 层“exactly once”。推荐增加：

```text
DELIVERY_UNKNOWN
```

或在诊断层明确记录“发送结果未知”。所有重试仍必须使用同一稳定 `notificationId`，把目标定义为“同一业务事件对应同一系统通知身份”，而不是保证底层 `notify()` 永远只被调用一次。

`NotificationAggregate` 是“批量展示聚合对象”，`NotificationOutbox` 是“最终可靠发送队列”。聚合完成后，应为该聚合创建一个对应的 Outbox 发送对象；不要让 Aggregate 和 Outbox 各自独立成为两个并行发送队列。

批量 Aggregate 对应的 Outbox 必须通过 `aggregateId` 关联；该 Outbox 不得伪造单一 `streamerId`。`notification_aggregate_event` 是事件归属的数据库权威关系，Aggregate 的 `eventIds` 只是查询结果。

数据库必须增加以下不变量：

```text
NotificationAggregate.aggregateId UNIQUE
NotificationOutbox.aggregateId UNIQUE（仅批量 Outbox 使用）
notification_aggregate_event PK(aggregateId, eventId)
notification_aggregate_event UNIQUE(eventId)
```

业务不变量：

```text
冻结完成且 status=READY/SENT 的 Aggregate
→ 必须恰有一个对应的批量 Outbox

批量 Outbox
→ 必须能解析到一个有效 Aggregate

Aggregate + relation rows + Outbox
→ 必须在同一 Room Transaction 中完成创建/冻结
```

`eventCount` 不是真实来源，始终以 relation 表 `COUNT(*)` 为权威；如果模型保留 `eventCount`，只能作为缓存并必须在同事务内校正。

聚合阈值语义：

```text
aggregationEnabled = false / “从不合并”
→ 事件直接走单条 Outbox，不进入聚合窗口

aggregationEnabled = true 且 threshold >= 2
→ 使用固定窗口聚合

threshold = 1
→ 不允许解释成“批量通知”；实现上等价于 aggregationEnabled = false
```

因此“从不合并”不得通过一个超大整数魔法值表达。

聚合窗口与 Outbox 的过期时间必须闭环：`expiresAt < windowEndWall` 时禁止进入 READY；推荐 `expiresAt >= windowEndWall + dispatchSafetyMargin`。

---

## 原规范 233：通知策略修正：异常恢复后的批量开播合并通知【历史说明：不可直接实现】

本项目不采用“恢复网络后逐条补发所有开播通知”的策略。

原因：

```text
网络异常期间无法知道准确的开播时刻
        ↓
网络恢复后可能一次发现多个主播已经处于 LIVE
        ↓
如果逐个发送通知
        ↓
短时间内可能连续弹出大量通知
```

因此，网络恢复、监控恢复或长时间异常结束后，如果一次检查发现多个新的开播事件，应支持“批量开播通知”。

## 233.1 不声明无法确认的开播时刻

开播通知正文禁止默认使用：

```text
刚刚开播
刚刚开始直播
刚才开始直播
```

因为如果此前存在：

```text
网络中断
监控服务停止
App 进程被杀
监控盲区
```

软件无法证明主播具体在什么时间开播。

默认通知应使用事实性表达：

```text
主播开播了
主播正在直播
发现主播正在直播
```

例如：

```text
恬豆 开播了
```

或：

```text
发现 1 位主播正在直播
```

如以后需要展示时间，必须区分：

```text
系统确认开播时间
≈
实际开播时间
```

只有在数据来源足够可靠时才能显示“开播时间”；否则不要把检测时间冒充实际开播时间。

---

## 原规范 234：批量开播通知【历史说明：不可直接实现】

## 234.1 默认触发阈值与“同时开播”定义

批量开播通知同时满足两个条件时才触发：

```text
条件 A：参与聚合的主播数量达到阈值
条件 B：这些开播事件发生在同一个时间窗口内
```

默认数量阈值：

```text
4 个主播
```

默认时间窗口：

```text
5 秒
```

即：**多个主播在 5 秒之内被可靠确认进入 LIVE，并且数量达到 4 个或以上时，视为“同时开播”，进入批量通知模式。**

“同时”以事件的有效确认时间 `eventConfirmedAt` 判断，而不是以 API 请求完成时间判断。

如果不同主播的确认时间差超过 5 秒，则不得因为它们恰好处于同一轮批量 API 查询中而强行合并。

数量阈值和时间窗口均属于用户可配置参数，但必须经过系统安全范围校验。

## 234.2 用户可自定义阈值

高级设置提供：

```text
批量开播通知阈值

○ 从不合并
○ 2 位及以上
● 4 位及以上
○ 5 位及以上
○ 10 位及以上
○ 自定义
```

用户自定义范围必须经过安全边界校验。

推荐：

```text
min = 1
max = 1000
```

但具体可用上限由应用实际 UI 与性能测试确定。

## 234.3 批量通知内容

当批量阈值触发时，不默认列出所有主播名称。

推荐：

```text
标题：发现多位主播正在直播
内容：共有 6 位主播正在直播
```

通知可以增加操作：

```text
[查看直播中的主播]
```

点击后直接打开应用，并自动进入：

```text
状态 = LIVE
```

的主播列表。

## 234.4 批量通知不代表忽略单独事件

批量通知只是通知展示策略，不改变数据库中的真实事件。

例如：

```text
主播 A → LIVE
主播 B → LIVE
主播 C → LIVE
主播 D → LIVE
主播 E → LIVE
```

数据库仍然必须分别保存：

```text
5 个 LiveEvent
5 个状态转换
```

只有 Notification 层把它们聚合成：

```text
1 条批量通知
```

这样统计、历史和审计数据不会丢失。

---

## 原规范 235：批量通知时间窗口【历史说明：不可直接实现】

批量开播通知的默认时间窗口为：

```text
5 秒
```

时间比较使用毫秒精度；聚合截止时间建议使用单调时钟计算，`eventConfirmedAt` 仍保存墙上时钟时间用于事件记录与排序。

定义：

```text
第一位进入聚合的开播事件确认时间 = T
后续事件确认时间必须满足：
T <= eventConfirmedAt <= T + 5 秒
```

只有在时间窗口内，并且主播数量达到当前配置阈值时，才视为“同时开播”并发送批量通知。

例如，默认阈值为 4：

```text
A 19:00:00
B 19:00:01
C 19:00:03
D 19:00:04
```

=> 4 位主播在 5 秒内确认开播，合并为一条通知。

而：

```text
A 19:00:00
B 19:00:02
C 19:00:04
D 19:00:06
```

=> 默认不把 A～D 视为同一批，因为 D 超出以 A 为起点的 5 秒窗口。

## 235.1 聚合窗口的实现要求

`NotificationAggregator` 不得依据“同一轮 API 请求”或“同一批次处理完成时间”判断是否同时开播。

必须依据每个 `LiveEvent.eventConfirmedAt` 判断。

v2.0 采用固定锚点窗口，不采用滑动窗口：

```text
收到第一事件
    ↓
创建固定聚合窗口 [T, T+5s]
    ↓
事件进入 PENDING_AGGREGATION
    ↓
继续收集窗口内新增事件
    ↓
达到数量阈值？
  /      \
是        否
↓          ↓
标记 READY   继续等待窗口结束
  \        /
   ↓      ↓
窗口结束后统一冻结
        ↓
达到阈值 → Batch Aggregate → Batch Outbox
未达到阈值 → Single Notification Outbox
```

进入聚合窗口的事件必须先进入 `PENDING_AGGREGATION`。达到数量阈值只表示“该窗口已经满足批量通知条件”，不改变固定 5 秒窗口的截止时间，也不得立即发送。窗口继续收集直到 `windowEndWall`，随后一次性冻结窗口并创建批量 Outbox。若窗口结束时未达到阈值，则这些事件分别转为普通通知。

窗口规则：`windowStartWall = 第一事件 eventConfirmedAt`，`windowEndWall = windowStartWall + aggregationWindowSeconds`。例如 19:00:05.000 算入，19:00:05.001 不算入。窗口关闭后不得再向该窗口追加事件。

用户可以在高级设置中修改时间窗口，但必须设置最小值、最大值和推荐值。

## 235.2 批量阈值与时间窗口是两个独立参数

```text
aggregationThreshold = 4
aggregationWindowSeconds = 5
```

两者不能混为一个参数。

例如用户可以设置：

```text
阈值：3
窗口：5 秒
```

表示 5 秒内出现至少 3 个新的开播事件就合并。

一个聚合窗口创建后，必须冻结创建时的 `threshold`、`aggregationWindow` 与 `configVersion`；后续修改通知设置不得改变已有窗口的边界或阈值。

## 235.3 网络恢复场景

网络恢复后，软件**不执行“历史漏发通知逐条补发”**。

RecoveryCheck 的首要职责是重新确认当前状态并修复数据，而不是把断网期间无法发送的通知逐条追补。

如果恢复检查发现主播当前仍然是 LIVE，则即使此前已经因为网络异常暂时失去观测，也可以产生一次“正在直播”类恢复发现通知，并从本次恢复确认点开始建立新的连续监控区间。这里通知的是“当前仍在直播”的事实，不是补发历史开播通知。该通知与普通开播通知使用同一用户可见模板，但必须拥有独立的恢复通知意图身份，不能冒充新的 START 业务事件。多个主播同时满足条件时仍进入 `NotificationAggregator`；聚合通知的用户可见文案仍统一采用“正在直播”的事实性表述。

推荐流程：

```text
网络恢复
   ↓
MonitoringScheduler 进入 RECOVERING
   ↓
暂停/合并普通 MonitoringTick
   ↓
RecoveryCheck
   ↓
批量获取主播状态
   ↓
计算状态变化
   ↓
筛选当前仍有通知价值的 LIVE 事件
   ↓
按 eventConfirmedAt 判断 5 秒时间窗口
   ↓
达到数量阈值？
  /       \
是         否
↓           ↓
批量通知     普通通知策略
```

不得因为恢复检查属于同一批 API 请求，就跳过 5 秒事件时间窗口判断。

## 235.4 批量窗口与去重

同一个 `LiveEvent` 不得因为：

```text
第一次检查
↓
恢复检查
↓
再次检查
```

而重复进入批量通知队列。

必须使用独立且稳定的通知意图身份作为幂等键。

普通开播：使用 `START eventId / transitionId`。

异常恢复后仍在直播：使用 `RecoverySession / 恢复确认周期 + streamerStableId`。

不得使用单纯的 `streamerId + eventType`，也不得把恢复发现伪装成新的 START Event。

---

## 235.3 LiveEvent 批量聚合字段规范

每个开播事件必须记录：

```kotlin
data class LiveEvent(
    val eventId: String,
    val streamerId: Long,              // 当前数据库本地主键，仅用于本机关系
    val streamerStableId: String,   // 跨设备备份/恢复使用
    val eventType: LiveEventType,
    val eventConfirmedAt: Long,
    val correlationId: String?,
    val sessionId: Long?,          // 当前数据库本地主键，仅用于本机快速关联
    val sessionStableId: String?, // 跨设备备份/恢复时使用的稳定场次身份
    val source: EventSource,
    val createdAt: Long
)
```

其中 `sessionId` 仅用于当前设备数据库快速关联；跨设备备份/恢复和审计关联必须使用 `sessionStableId` 建立 LiveEvent → LiveSession 的关系。

其中：

```text
eventConfirmedAt
```

表示“软件可靠确认该状态转换发生的时间”，不是“主播实际开播时间”。

这一字段专门服务于：

```text
状态事件排序
通知聚合
事件去重
恢复场景下的批量通知判断
```

不得把 `notificationCreatedAt`、`notificationSentAt` 或 HTTP 请求完成时间当作 `eventConfirmedAt`。

## 235.4 批量通知的数据一致性

批量通知只是多个独立事件的展示聚合：

```text
LiveEvent A ─┐
LiveEvent B ─┤
LiveEvent C ─┤→ NotificationAggregate
LiveEvent D ─┘
```

数据库仍保存所有独立 `LiveEvent`。

`NotificationAggregate` 至少保存：

```text
aggregateId
createdAt
windowStartWall
windowEndWall
threshold
eventCount
eventIds
status
```

这样可以追溯：

```text
这条“6位主播正在直播”的通知
到底由哪6个开播事件组成？
```

## 235.3.1.0 PENDING_AGGREGATION 崩溃恢复

`PENDING_AGGREGATION` 必须持久化其 `windowStartWall`、`windowEndWall`、阈值、配置版本和已绑定事件关系，不能只保存在内存。

进程崩溃或系统重启后：

```text
StartupRecovery
    ↓
读取 PENDING_AGGREGATION
    ↓
检查 windowEndWall
    ├─ 未到期 → 恢复等待/调度
    └─ 已到期 → 原子定案
                  ↓
             达到阈值 → Batch Aggregate
             未达阈值 → Single Notification Outbox
```

任何情况下不得重复消费同一个 `LiveEvent`，也不得因为重启重新建立第二个等价窗口。

---

## 235.3.1.1 聚合并发与数据库幂等

多个线程可能同时发现达到聚合阈值，因此聚合创建必须具备数据库级并发保护。

推荐使用独立关系表：

```text
notification_aggregate_event
-----------------------------
aggregateId
eventId
createdAt
```

并建立：

```text
PRIMARY KEY (aggregateId, eventId)
UNIQUE (eventId)
```

其中 `UNIQUE(eventId)` 用于保证同一个 `LiveEvent` 最多属于一个有效聚合。`NotificationAggregate` 与其事件关系、Outbox 创建必须在同一个 Room Transaction 中提交。

如果并发事务发生唯一键冲突，失败方必须重新读取已存在的聚合，而不是创建第二个聚合。


---

## 235.3.1 NotificationAggregate 模型

批量通知本身也需要作为独立的通知聚合对象记录（**唯一实体为 `NotificationAggregateEntity`，见 0.6.9.1**）：

```kotlin
// 历史版本 data class NotificationAggregate 已废弃。与最终实体的关键差异：
//  - windowStartAt / windowEndAt → windowStartWall / windowEndWall（见 0.6.24，必须区分 wall 与 elapsed）
//  - status 类型 AggregateStatus → NotificationAggregateStatus（AggregateStatus 是废弃别名）
//  - 新增 windowStartElapsed / windowEndElapsed / bootId / expiresAt / configVersion（缺一不可重放）
//  - eventIds 不是数据库字段；权威关系来自 notification_aggregate_event
```

要求：

```text
1. aggregateId 必须全局唯一。
2. eventIds 仅作为逻辑读取结果；数据库中的真实事件归属必须由 notification_aggregate_event 关系表决定，每个 LiveEvent 最多属于一个已生效 Aggregate。
3. 批量通知失败可以重试，但不能重新生成另一组相同聚合。
4. 聚合对象只描述通知展示，不改变 LiveEvent / LiveSession 的事实数据。
5. 用户点击批量通知后，进入 App 的“当前正在直播”筛选结果，而不是伪造某个具体主播的通知时间。
6. `notification_aggregate_event` 建议作为独立关系表，使用 `(aggregateId, eventId)` 复合主键，不建议把 `eventIds` 仅序列化为一个 JSON 字段。
7. 一个 `LiveEvent` 最多只能归属于一个已生效的通知聚合。
```

---

## 原规范 89：异常通知等级【历史说明：不可直接实现】

为避免通知骚扰，异常采用三级。

## Level 1：普通异常

例如：

```text
单次超时
单次网络失败
短暂 DNS 失败
```

仅日志记录，不推送系统通知。

## Level 2：重要异常

例如：

```text
连续多次失败
连续 5 分钟以上无法获得可靠数据
```

发送一次：

```text
⚠ 监控数据异常
Bilibili 数据暂时无法稳定获取，软件正在自动恢复。
```

恢复时可发送一次系统级恢复通知：

```text
✅ 监控已恢复
```

该通知用于描述“监控系统恢复”，与主播状态通知是不同业务事件。若恢复检查确认某主播仍在直播，该主播状态通知的用户可见文案仍统一为“正在直播”，不得用“监控已恢复”替代主播状态通知。

## Level 3：严重异常

例如：

```text
监控服务已停止
用户要求监控，但后台运行状态为 STOPPED
数据库严重错误
通知功能不可用
```

发送：

```text
🔴 主播监控已停止
软件当前无法继续执行后台监控，请打开应用查看原因。
```

---

## 原规范 90：异常通知去重与冷却【历史说明：不可直接实现】

异常本身也必须去重。

例如网络连续失败 30 次：

```text
错误 1
错误 2
错误 3
...
错误 30
```

只能向用户提醒一次，不能发送 30 条通知。

建议采用：

```text
problemKey
cooldownUntil
lastNotifiedAt
recoveredNotified
```

示例：

```text
NETWORK_BILIBILI_UNAVAILABLE
```

恢复以后再次允许创建下一次异常通知。

---

## 原规范 175：P0-10 通知过期控制【历史说明：不可直接实现】

Notification Outbox 增加：

```text
expiresAt
```

如果事件已经失去用户价值，例如：

```text
19:00 开播
20:10 手机恢复
```

不得在 20:10 再推送“刚刚开播”。

状态：

```text
PENDING
PROCESSING
SENT
RETRY_WAIT
FAILED
EXPIRED
CANCELLED
```

---

## 原规范 183：P1-4 通知冷却机制【历史说明：不可直接实现】

即使事件去重成功，也要避免状态抖动造成通知轰炸。

增加：

```text
cooldownKey
cooldownUntil
```

例如：

```text
同主播同事件类型5分钟内最多通知一次
```

但 `cooldownKey` 不得只使用“主播 + 事件类型”，否则快速结束后重新开播可能被错误抑制。应至少包含新的 `sessionId`、`sessionGeneration` 或等价的独立事件身份。去重与冷却是两个不同机制：去重禁止同一事件发送两次；冷却用于抑制不同事件的通知轰炸。

具体值允许设置。

重点主播可覆盖默认策略。

---

## 原规范 48：通知历史中心【历史说明：不可直接实现】

新增页面：

```text
通知历史
```

支持：

```text
全部
开播
下播
失败
```

每条记录：

```text
时间
主播
事件
直播标题
发送结果
```

详情：

```text
事件产生时间
状态变化
通知创建时间
尝试次数
最终结果
失败原因
```

---

## 原规范 220：日志与用户通知的关系【历史说明：不可直接实现】

日志记录 ≠ 通知用户。

推荐：

```text
单次 WARNING
→ 记录
→ 不打扰
```

```text
连续 ERROR
→ 记录
→ 状态栏提示
```

```text
CRITICAL / 监控停止
→ 记录
→ 系统通知
→ 健康中心红色状态
```

这样可以避免用户被日志刷屏。

---

## 原规范 221：错误恢复状态【历史说明：不可直接实现】

错误记录增加：

```text
OPEN
ACKNOWLEDGED
RECOVERED
IGNORED
```

例如：

```text
API timeout
→ OPEN

用户查看
→ ACKNOWLEDGED

网络恢复
→ RECOVERED
```

这样可以让日志中心区分：

```text
还在发生的问题
已经解决的问题
用户已经知道的问题
```

---

## 原规范 222：监控错误自动聚合【历史说明：不可直接实现】

同一个错误连续发生，不要生成几千条完全重复的数据而无法阅读。

例如：

```text
21:00 timeout
21:01 timeout
21:02 timeout
...
21:30 timeout
```

日志中心可以聚合显示：

```text
Bilibili API 请求持续超时
持续：30分钟
发生次数：31
影响主播：57
```

数据库仍可以保存必要的原始事件摘要，但 UI 默认进行聚合。

---

## 原规范 223：软件错误自动聚合【历史说明：不可直接实现】

同一个异常，例如：

```text
IOException
HtmlExport
```

短时间连续发生时，UI 显示：

```text
HTML 导出模块发生异常
最近1小时：8次

[查看详情]
```

这样开发者能够更快识别重复问题。

---

## 原规范 224：日志关联 ID【历史说明：不可直接实现】

一次完整监控任务需要一个：

```text
correlationId
```

例如：

```text
monitor-20260912-213200-001
```

同一任务产生的：

```text
API请求
状态判断
数据库更新
通知Outbox
错误
恢复
```

都可以通过 correlationId 串联。

这会大幅提高开发者排查问题的效率。

---

## 原规范 225：软件崩溃后的日志恢复【历史说明：不可直接实现】

应用异常退出以后，重新启动时：

```text
StartupRecovery
 ↓
读取上次运行标记
 ↓
检查 ApplicationExitInfo
 ↓
创建 ApplicationErrorLog / CrashRecord
 ↓
检查监控盲区
 ↓
恢复监控
```

如果确认存在崩溃：

用户可以看到：

```text
上次运行异常结束
时间：20:31

监控已经恢复
期间存在 12 分钟监控盲区
```

而不是默默恢复，让用户误以为全程正常。

---

## 原规范 236：通知点击跳转策略【历史说明：不可直接实现】

通知、主播卡片和主播详情页进入直播间必须统一使用：

```text
RoomNavigator
```

## 236.1 默认优先 Bilibili App

用户点击：

```text
[进入直播间]
```

应优先尝试：

```text
Bilibili App
```

如果设备已安装并能够处理对应直播间链接：

```text
拉起 Bilibili App
```

如果失败：

```text
回退到系统浏览器
```

## 236.2 用户设置

设置页提供：

```text
直播间打开方式

● 优先使用 Bilibili App
○ 优先使用浏览器
○ 每次询问
```

高级设置不得绕过系统默认应用选择机制，不得使用不透明的方式强行劫持其他应用。

## 236.3 跳转失败处理

必须区分：

```text
Bilibili App 不存在
Bilibili App 无法处理链接
Intent 被拒绝
直播间链接失效
系统浏览器不可用
```

用户看到的是友好的提示：

```text
无法打开 Bilibili 直播间
请检查 Bilibili App 是否已安装。
```

技术原因记录到 ApplicationErrorLog。

---

## 原规范 240：通知内容最终规范【历史说明：不可直接实现】

## “正在直播”通知

以下两种情况统一使用同一套用户可见语义：

```text
普通开播确认
异常恢复后重新确认当前仍在直播
```

默认：

```text
标题：{主播名} 正在直播
内容：{主播名} 正在直播{：直播标题}
```

恢复场景不得改成“监控已恢复”后再另外弹出一条表示主播状态的不同文案；如果恢复检查确认主播仍在直播，主播通知直接使用“正在直播”。

禁止默认使用：

```text
刚刚开播
刚才开播
```

## 240.1 关播通知资格判定

关播通知必须由统一的 `EndNotificationEligibilityEvaluator` 判断，不能由普通 `LIVE -> OFFLINE` 检测直接发送。

```text
LiveSession
   ↓
读取本场 MonitorObservation
   +
读取本场 MonitoringGapStreamer
   ↓
本场是否从开始到结束持续存在可靠监控？
   /                         \
 是                           否
 ↓                              ↓
最终 OFFLINE 可靠确认？        不具备关播通知资格
 ↓
是 → 创建 END NotificationOutbox
否 → 不发送
```

当前连续监控区间只要出现过以下任一种异常，该区间就失去关播通知资格：

```text
TIMEOUT
NETWORK_ERROR
API_ERROR
PARTIAL
INVALID
MonitoringGap
```

主动取消本身不算网络错误；但如果因此产生无法覆盖的监控空窗，则当前连续区间不能作为关播通知资格区间。下一次恢复时若主播仍在直播，可以重新建立新的连续监控区间。

示例一：

```text
10:00 开播
10:01 断网
11:00 恢复，发现主播已经下播
→ 不发送关播通知
```

示例二：

```text
10:00 开播
之后持续获得可靠 SUCCESS 观察，没有异常
11:00 确认 OFFLINE
→ 发送关播通知
```

示例三：

```text
10:00 开播
10:01 断网
11:00 恢复，发现主播仍在直播
11:00 之后持续获得可靠 SUCCESS 观察，没有新的异常
12:00 确认 OFFLINE
→ 发送关播通知
```

示例四：

```text
10:00 开播
10:01 断网
11:00 恢复，发现主播仍在直播
11:30 再次发生网络异常
12:00 再次恢复，发现主播仍在直播
12:00 之后持续正常监控
13:00 确认 OFFLINE
→ 可以根据 12:00～13:00 这一段新的连续可靠监控区间发送关播通知
```

恢复成功不是简单“恢复整场历史资格”，而是从恢复并重新确认 LIVE 的时刻开始建立新的连续监控区间。

## 240.2 通知事件身份与文案解耦

普通 START、异常恢复后的 `LIVE_RECONFIRMED`（或等价通知意图）和 END 是不同业务语义；其中 START 与 LIVE_RECONFIRMED 的用户可见直播中通知统一显示“正在直播”。END 只有在当前连续可靠监控区间满足资格时才可以发送。

“同文案”不等于“同事件”；每个通知意图必须拥有独立稳定的 `eventKey`，避免恢复通知与真实 START 相互去重。

---

## 原规范 241：通知可靠性验收新增项目【历史说明：不可直接实现】

本节必须额外覆盖：

```text
[ ] 普通开播确认通知显示“正在直播”
[ ] 异常恢复后发现主播仍在直播时通知仍显示“正在直播”
[ ] 恢复通知不能冒充新的 START Event
[ ] 恢复后发现主播已经下播时不发送关播通知
[ ] 当前连续监控区间发生任何异常后，该区间即失去关播通知资格
[ ] 当前连续监控区间全程存在可靠 SUCCESS 观察且最终 OFFLINE 可靠确认时才发送关播通知
[ ] 恢复时发现主播仍在直播，会从恢复确认点重新建立新的关播通知资格
[ ] 恢复时已经下播时不发送关播通知
[ ] 后续新区间的资格不受已经结束的旧异常区间永久锁死
```

```text
[ ] 网络恢复后不会逐条补发大量历史开播通知
[ ] 达到批量阈值时能够合并通知
[ ] 批量阈值可以自定义
[ ] 批量通知默认阈值为4
[ ] 默认同时开播时间窗口为5秒
[ ] 5秒内达到阈值的事件会合并
[ ] 超过5秒窗口的事件默认不会合并
[ ] 可以设置从不合并
[ ] 批量聚合判断使用eventConfirmedAt
[ ] 同一批API请求但确认时间超过5秒时不会强行合并
[ ] 批量通知不改变底层 LiveEvent 数量
[ ] 同一事件不会被批量通知重复消费
[ ] 不显示无法证明的“刚刚开播”
[ ] 单个主播通知显示事实性描述
[ ] 通知点击优先尝试 Bilibili App
[ ] Bilibili App 无法打开时回退浏览器
[ ] 跳转失败不会导致 App 崩溃
[ ] 前台服务监控拥有用户可见通知
[ ] 用户可以关闭实时监控
[ ] 无障碍服务属于默认关闭、用户主动授权的高级辅助能力
[ ] 无障碍服务状态不可用时不会伪装成正常运行
[ ] 同一时间不会存在两个主动 MonitoringEngine
[ ] 旧刷新响应不会覆盖新刷新响应
[ ] 首次发现 LIVE 不产生普通开播通知
[ ] 系统时间跳变不会破坏超时、租约或5秒聚合窗口
[ ] 两个并发聚合任务不会生成重复 Aggregate
[ ] PROCESSING Outbox 租约过期后可以恢复
[ ] 通知已显示但数据库未及时写 SENT 时不会产生重复系统通知身份
[ ] RecoverySession 超时后不会伪装成完整恢复
[ ] 通知模块故障不会停止主播状态检测
```

---

## 原规范 242：AI Coding Agent 通知与保活强制规则【历史说明：不可直接实现】

```text
1. 不得把监控检测时间当成主播实际开播时间。
2. 默认通知文案不得声称“刚刚开播”。
3. 网络恢复后不得默认逐条补发断网期间积压的开播通知。
4. NotificationAggregator 必须支持批量开播通知。
5. 默认批量阈值为4个主播。
6. 默认同时开播时间窗口为5秒。
7. 批量阈值与时间窗口必须可配置，并受安全边界限制。
8. 批量通知只改变通知层，不改变 LiveEvent/LiveSession 数据。
9. 批量通知必须具有幂等键。
10. 批量聚合必须依据 eventConfirmedAt，而不是简单依据同一批 API 请求。
11. 用户点击直播间必须优先尝试 Bilibili App。
12. App 跳转失败必须回退浏览器或安全提示。
13. RoomNavigator 必须统一处理卡片、详情页和通知点击。
14. 前台监控必须使用用户可见的前台服务通知。
15. 不允许把常驻通知描述为“100% 永不被杀”。
16. AccessibilityService 不是默认功能；如果作为开源版本的高级后台辅助方案提供，必须默认关闭、明确说明用途、要求用户主动授权，并独立记录运行状态。
17. AccessibilityService 不得采集或上传与主播监控无关的屏幕内容、输入事件或个人数据；不得将其作为隐蔽运行或绕过用户授权的手段。
18. 实时监控启动必须遵守 Android 12+ 前台服务后台启动限制。
19. 不允许为了保活而申请与实际用途不匹配的前台服务类型。
20. 批量通知失败不能导致监控任务失败。
21. 通知聚合器异常不能影响状态检测和 LiveSession 数据写入。
22. MonitoringEngine 同一时刻只能有一个有效运行租约；旧实例租约未释放且未过期时，不得启动第二个主动监控实例。
23. `observationSequence` 必须至少具备主播级覆盖判断；旧网络响应不得覆盖更新的数据。
24. 首次从 UNKNOWN 发现 LIVE 默认只确认当前直播状态，不产生普通开播事件和“刚刚开播”文案。
25. 持续时间计算必须使用单调时钟；墙上时钟仅用于历史时间点、展示与导出。
26. NotificationAggregate 的事件绑定、聚合状态与对应 Outbox 必须事务化提交；同一 LiveEvent 不得进入两个有效聚合。
27. Android 通知发送不得承诺 exactly-once；必须通过稳定 notificationId 实现幂等展示。
28. RecoverySession 必须有明确的完成条件、最大时长和 finishReason。
29. 网络、数据库、通知、监控等子系统健康状态应独立计算，单个通知故障不得直接停止监控。
30. 取消 MonitoringTick 时必须沿 Coordinator → Coroutine → HTTP Call 传播；已取消结果即使返回也必须在 Repository 层丢弃。
```

---# 243. 本次设计调整总结

本次修改后，通知系统最终原则为：

```text
监控系统负责发现事实
        ↓
事件系统负责记录事实
        ↓
通知系统负责选择合适的展示方式
```

因此：

```text
一个主播开播
→ 一条普通开播通知

多个主播同时开播
→ 根据阈值决定是否聚合

网络恢复后一次发现大量开播
→ 优先批量通知

无法知道实际开播时间
→ 不声称“刚刚开播”

无法保证后台永久运行
→ 不承诺“永不被杀”
```

最终产品目标：

> **通知应当准确表达“软件现在知道什么”，而不是假装知道软件实际上不知道的事情。**


---

# 07 直播历史、人工修正与统计系统

## 原规范 83：统计可重建原则【历史说明：不可直接实现】

统计系统必须采用“源数据优先”。

真正的数据源：

```text
LiveSession
```

统计缓存：

```text
StatisticsCache
```

统计关系：

```text
LiveSession
    ↓
StatisticsCalculator
    ↓
StatisticsResult
    ↓
StatisticsCache（可选）
```

因此：

```text
缓存损坏
   ↓
删除缓存
   ↓
重新扫描 LiveSession
   ↓
重新计算统计
```

例如：

```text
本月直播次数
= COUNT(符合条件的 LiveSession)

本月直播总时长
= SUM(confirmed duration)

平均直播时长
= AVG(confirmed/estimated duration)
```

统计页面必须支持：

```text
全部数据
仅确认数据
包含估算数据
```

---

## 原规范 84：统计数据可信度【历史说明：不可直接实现】

统计结果必须允许显示数据完整度。

例如：

```text
本月直播总时长：126小时
数据完整度：96.4%

已确认：118小时
估算：8小时
```

主播详情：

```text
本月直播 12 次

✅ 完整确认：10 次
🟡 部分确认：2 次
```

不得把估算数据伪装成精确数据。

---

## 原规范 85：异常数据排除策略【历史说明：不可直接实现】

统计系统不再把 `isIncludedInStatistics` 作为独立事实字段保存。`DataConfidence` 是事实层来源，`StatisticsEligibility` 是根据统计口径即时计算的结果。

默认映射：

```text
CONFIRMED   -> INCLUDED
PROVISIONAL -> 根据统计设置决定 INCLUDED / PARTIALLY_INCLUDED
UNKNOWN     -> EXCLUDED，除非存在足以计算该指标的独立已知时间信息
INVALID     -> EXCLUDED
```

用户可在统计页面设置：

```text
数据统计范围
○ 仅确认数据
● 确认 + 估算
```

对于明确错误的记录：

```text
INVALID
```

不得进入正常统计。

---

## 原规范 115：错误统计记录的修正系统【历史说明：不可直接实现】

统计数据出现错误时，不允许直接修改一个“总数”。

正确方式是：

```text
发现统计错误
      ↓
定位源 LiveSession
      ↓
修正 LiveSession
      ↓
重新计算统计
```

例如：

```text
错误统计：
本月直播总时长 = 81小时
```

检查发现某一场：

```text
原记录：19:00 - 23:00
错误持续时间：4小时
实际：19:00 - 22:00
```

修正 Session：

```text
duration = 3小时
```

统计系统自动重新计算：

```text
本月直播总时长 = 80小时
```

禁止出现：

```text
LiveSession = 4小时
Statistics = 3小时
```

这种源数据与统计数据不一致的情况。

---

## 原规范 116：统计来源追溯【历史说明：不可直接实现】

所有统计数字必须能够追溯到具体 Session。

例如：

```text
本月直播总时长：80小时12分钟
```

点击数字可以进入：

```text
构成该统计的 37 场直播
```

用户可以查看：

```text
哪些记录被计入
哪些记录被排除
哪些记录不完整
哪些记录存在人工修正
```

建议增加：

```kotlin
data class StatisticExplanation(
    val metric: String,
    val sessionIds: List<Long>,
    val excludedSessionIds: List<Long>,
    val provisionalSessionIds: List<Long>
)
```

---

## 原规范 117：统计记录的四种状态【历史说明：不可直接实现】

每个 LiveSession 对统计系统有四种状态：

```text
INCLUDED
```

正常纳入统计。

```text
PARTIALLY_INCLUDED
```

部分数据可用，例如开播时间确定，下播时间只是区间。

```text
EXCLUDED
```

用户确认该记录错误，不参与正常统计。

```text
UNKNOWN
```

暂时无法判断是否应该统计。

推荐增加：

```kotlin
// 唯一最终定义见 0.6.2「专用枚举」，本节不得重复声明。
// 注意：本枚举表示"单条历史记录的纳入结果"，与 StatisticsEligibility（查询口径参数，见 0.6.2）
// 是两个不同概念。历史版本曾把本枚举误命名为 StatisticsEligibility，该命名已废弃（见 0.6.42.1 别名登记表）。
//
//   StatisticsInclusionState = { INCLUDED, PARTIALLY_INCLUDED, EXCLUDED, UNKNOWN }
```

---

## 原规范 121：自动检测“明显错误的历史数据”【历史说明：不可直接实现】

除了网络/崩溃产生的不完整记录，还要检测异常值。

例如：

```text
duration < 0
startTime > endTime
持续时间 > 24小时
同一主播同一时刻存在两个重叠 Session
```

如果发现：

```text
持续时间 = 51小时
```

不要自动删除。

应该标记：

```text
⚠ 疑似异常
```

等待用户确认。

---

## 原规范 122：Session 重叠与拆分【历史说明：不可直接实现】

直播历史允许出现：

```text
Session A：19:00 - 20:00
Session B：19:30 - 21:00
```

系统应提示：

```text
发现同一主播的直播记录存在重叠。
请确认：
[合并] [保留两条] [删除错误记录]
```

同时支持：

```text
一条错误 Session
        ↓
拆分为两条真实 Session
```

例如：

```text
19:00 - 23:00
```

用户发现中间其实是两场直播：

```text
19:00 - 21:00
22:00 - 23:00
```

提供：

```text
拆分记录
```

---

## 原规范 123：合并直播记录【历史说明：不可直接实现】

对于自动监控把同一场直播错误切成两段：

```text
19:00 - 20:30
20:31 - 22:00
```

用户可以：

```text
合并直播记录
```

合并后：

```text
19:00 - 22:00
```

原始两条 Session 不直接删除，记录合并审计。

---

## 原规范 124：“用户确认”不等于“永远不可修改”【历史说明：不可直接实现】

即使某条记录已经手动修正，也允许：

```text
再次编辑
撤销修正
重新计算
恢复自动数据
```

但每次动作都必须写入审计日志。

这样不会产生不可逆的数据锁死。

---

## 原规范 112：手工修正优先级规则【历史说明：不可直接实现】

最终统计时间采用：

```text
用户明确修正
    >
可靠导入数据
    >
自动监控确认数据
    >
自动计算数据
    >
暂定/推测数据
```

对于某一字段：

```text
USER_ENTERED
```

一旦用户保存并确认，自动刷新不得覆盖该字段。

例如：

```text
自动监控：开播 19:03
用户修改：19:00

下次刷新：19:05
```

不能重新把开播时间写回 19:03。

---

## 原规范 113：人工修正保护机制【历史说明：不可直接实现】

用户保存人工修正后，自动监控不得覆盖用户已经明确确认的字段。这里的“保护”不是永久只读锁；用户仍然可以再次编辑、撤销修正或恢复自动数据。

```text
用户确认某字段
        ↓
该字段进入 USER_CONFIRMED / USER_ENTERED 保护状态
        ↓
自动监控刷新
        ↓
禁止覆盖该字段
```

不再使用含义模糊的 `isLocked` 作为“禁止所有修改”的数据库事实。如果实现需要保存锁定信息，应明确表示“防止自动覆盖”的业务语义，而不是阻止用户操作。所有人工确认、撤销和恢复动作都必须写入 Correction/AuditLog，并通过版本/CAS 防止旧页面提交覆盖更新后的记录。

## 原规范 114：修正历史与审计日志【历史说明：不可直接实现】

每一次人工修改必须记录：

```kotlin
// 唯一实体是 LiveSessionCorrectionEntity（见 0.6.21）。本节历史版本已废弃，原因是它违反了 0.6.21 的硬性要求：
//  - id: Long / sessionId: Long 不得作为跨设备修正身份（0.6.21 明文禁止）
//  - 缺少 baseCorrectionVersion / newCorrectionVersion，无法做 correctionVersion CAS
//  - 缺少 operationId，无法实现"每次人工修正必须提供唯一 correctionId + operationId"的幂等要求
//  - 单行只表达一个字段，无法承载一次原子修正；最终模型用 changedFieldsJson 承载字段集合
data class LiveSessionCorrection(
    val correctionId: String,          // 跨设备稳定身份
    val sessionStableId: String,       // 引用 LiveSession.stableId，不得用本地 sessionId
    val baseCorrectionVersion: Long,   // CAS 基线
    val newCorrectionVersion: Long,    // CAS 目标
    val changedFieldsJson: String,     // 本次修正的字段集合与新旧值
    val source: DataSource,            // USER_CORRECTED / USER_ENTERED / IMPORTED ...
    val createdAt: Long,
    val operationId: String            // 幂等键，UNIQUE
)
```

例如：

```text
2026-09-12 23:10
开播时间
19:03 → 19:00
原因：查看直播回放确认
```

历史详情页提供：

```text
修正记录
```

支持查看：

```text
第1版
第2版
第3版
```

并支持撤销到上一版。

---

## 原规范 111：历史记录编辑器【历史说明：不可直接实现】

历史详情页增加：

```text
编辑直播记录
```

UI：

```text
┌─────────────────────────────┐
│ 编辑直播历史                │
├─────────────────────────────┤
│ 主播：XXX                   │
│                             │
│ 开播时间                    │
│ [2026-09-12 19:00]         │
│                             │
│ 下播时间                    │
│ [2026-09-12 22:15]         │
│                             │
│ 直播时长                    │
│ [2小时45分钟]               │
│                             │
│ 三项中填写任意两项即可       │
│ 第三项会自动计算             │
│                             │
│ 来源：用户修正               │
│ 状态：已确认                 │
│                             │
│ [取消]        [保存修正]    │
└─────────────────────────────┘
```

如果用户同时修改了三项并且数值不一致：

```text
开播：19:00
下播：22:15
时长：2小时30分钟
```

软件必须阻止保存，并提示：

```text
三个时间存在矛盾。
请修改其中一项。
```

同时可以显示：

```text
根据开播与下播计算：2小时45分钟
```

---

## 原规范 118：异常历史记录 UI【历史说明：不可直接实现】

对于存在问题的历史：

```text
┌──────────────────────────────┐
│ ⚠ 数据异常                   │
│                              │
│ 开播：19:03                  │
│ 下播：未知                   │
│ 时长：未知                   │
│                              │
│ 监控曾中断 37 分钟            │
│                              │
│ [修正时间] [查看详情]        │
└──────────────────────────────┘
```

已经存在人工修正：

```text
┌──────────────────────────────┐
│ ✅ 已人工修正                │
│ 开播 19:00                   │
│ 下播 22:15                   │
│ 时长 2小时15分钟              │
│                              │
│ 来源：用户确认               │
└──────────────────────────────┘
```

数据异常不得隐藏。

---

## 原规范 119：数据异常总览【历史说明：不可直接实现】

历史页面增加：

```text
数据质量
```

显示：

```text
全部直播记录      328
完整记录          307
部分确认           12
待修正              6
无效记录            3
```

点击：

```text
待修正 6
```

进入：

```text
需要用户处理的历史记录
```

这是“发现错误”比单独在某个页面显示一个黄色图标更有效的方式。

---

## 原规范 120：用户修正向导【历史说明：不可直接实现】

当用户打开异常记录时，提供快捷向导：

```text
检测到该记录时间不完整。
请选择你知道的信息：

○ 我知道准确开播和下播时间
○ 我知道准确开播时间和直播时长
○ 我知道准确下播时间和直播时长
```

然后展示对应输入界面。

保存后：

```text
验证
 ↓
自动计算第三项
 ↓
显示修正前后对比
 ↓
用户确认
 ↓
事务提交
 ↓
统计自动重算
```

---

## 原规范 139：统计页面新增“数据修正”入口【历史说明：不可直接实现】

在统计页面遇到异常时：

```text
本月总直播时长
80小时12分钟
⚠ 3条记录存在异常

[检查异常记录]
```

点击后直接进入：

```text
需要修正的 LiveSession
```

修正后：

```text
重新计算统计
```

无需用户手动计算总时长。

---

## 原规范 140：批量修正【历史说明：不可直接实现】

增加：

```text
批量选择异常记录
```

允许批量执行：

```text
标记已确认
标记排除
恢复统计
删除人工修正（恢复原始）
```

时间字段仍建议逐条确认，不允许无提示地批量覆盖准确时间。

---

## 原规范 141：撤销与恢复机制【历史说明：不可直接实现】

历史编辑必须支持：

```text
撤销最近一次修正
恢复自动检测值
恢复到指定版本
```

但：

```text
数据库永远保留审计记录
```

恢复只是改变“当前有效版本”，不是删除历史修改痕迹。

---

## 原规范 150：用户手工输入的防误操作【历史说明：不可直接实现】

时间编辑控件必须：

```text
日期选择器
时间选择器
时长输入
```

禁止完全依赖自由文本。

文本输入仍允许，但必须解析：

```text
2:30
02:30
2小时30分钟
150分钟
```

统一转换成秒。

保存前显示最终结果：

```text
开播：19:00
下播：21:30
时长：2小时30分钟
```

让用户确认后再保存。

---

## 原规范 151：时长输入规则【历史说明：不可直接实现】

支持：

```text
小时 + 分钟
小时
分钟
HH:mm
```

例如：

```text
2小时30分钟
2.5小时
150分钟
02:30
```

内部统一保存：

```text
durationSeconds
```

`durationSeconds` 表示当前记录中最终用于统计/展示的时长值；来源由 `durationSource` 标记，可信度由 `durationConfidence` 标记。

若只能根据监控盲区得到估算时长，仍只保存 `durationSeconds`；估算语义由 `durationSource` / `durationConfidence` 表达。UI 如需显示区间，应根据 `lastConfirmedLiveAt`、`confirmedOfflineAt` 等事实重新计算或展示。

UI/Domain 层可以把该值展示为“直播时长”，数据库和序列化字段统一使用 `durationSeconds`，避免同时维护两个语义不同的时长事实字段。

避免使用字符串保存时长。

---

## 原规范 152：历史数据修正与导入兼容【历史说明：不可直接实现】

从旧版本导入没有可信度字段的数据时：

```text
confidence = UNKNOWN
source = IMPORTED
```

不要默认假设：

```text
IMPORTED = CONFIRMED
```

用户确认后才能提升为：

```text
CONFIRMED
```

---

## 原规范 125：统计计算架构升级【历史说明：不可直接实现】

统计系统采用：

```text
LiveSession Source of Truth
            ↓
     StatisticsCalculator
            ↓
    StatisticsSnapshot
            ↓
          UI
```

其中：

```text
StatisticsSnapshot
```

只是缓存，不是最终事实。

缓存损坏时：

```text
删除缓存
 ↓
重新扫描 LiveSession
 ↓
重新生成统计
```

---

## 原规范 126：统计时间区间统一规则【历史说明：不可直接实现】

所有统计必须明确：

```text
统计时间范围
统计时区
统计口径
```

支持：

```text
今日
昨日
本周
上周
本月
上月
本季度
本年度
自定义范围
```

默认使用用户当前设备时区，但历史原始时间戳不能因为用户跨时区而改变。统计请求应在计算时明确传入 `timezone`，同一份历史数据在不同统计时区下可以得到不同的日期归属，这是统计视图变化，不是历史数据被修改。

## 126.1 跨统计区间的直播场次

如果一场直播跨越日期、月份或年份边界：

```text
开播次数
→ 按 startTime 归属到一个统计周期

直播时长
→ 按统计区间切片累计
```

例如：

```text
2026-09-30 23:00
        ↓
2026-10-01 02:00
```

则：

```text
9月统计：1小时
10月统计：2小时
```

“场次”不会被重复计数，但“时长”可以跨区间切片。统计报表必须显示该统计口径。

## 原规范 127：统计分类扩展【历史说明：不可直接实现】

在直播次数、总时长、平均时长之外，建议预留：

```text
开播次数
总直播时长
平均直播时长
最长直播
最短直播
连续直播天数
首次开播时间
最近开播时间
最近下播时间
按星期统计
按小时统计
按分区统计
```

后续还可以支持：

```text
主播之间对比
分组之间对比
标签之间对比
```

例如：

```text
游戏标签
本月直播 48 次
总时长 102 小时
```

---

## 原规范 128：直播历史日历视图【历史说明：不可直接实现】

建议加入：

```text
历史 → 日历
```

例如：

```text
       2026年9月
一 二 三 四 五 六 日
  1  2  3  4  5  6
  7  8  9 10 11 12 13
```

每天可以显示：

```text
● 有直播
● 多场直播
⚠ 有异常记录
```

点击日期查看当日直播列表。

这个视图可以与统计页面联动。

---

## 原规范 129：直播历史时间线【历史说明：不可直接实现】

主播详情页建议增加：

```text
2026-09-12
│
├── 19:00 开播 ✅
│
├── 22:15 下播 ✅
│   时长 3小时15分钟
│
2026-09-10
│
├── 20:02 开播 ⚠
│   监控中断
│
└── 22:30 下播
```

视觉上快速显示数据完整程度。

---

## 原规范 130：直播历史备注【历史说明：不可直接实现】

允许用户填写：

```text
备注
```

例如：

```text
本场提前10分钟实际开始。
B站显示的开播时间有延迟。
通过录播回放确认。
```

备注属于用户数据，不参与自动统计计算。

---

## 原规范 142：统计重算触发条件【历史说明：不可直接实现】

以下情况必须自动重新计算：

```text
LiveSession 新增
LiveSession 删除/排除
LiveSession 时间修改
LiveSession 合并
LiveSession 拆分
统计筛选范围修改
数据导入
配置恢复
数据库修复
```

以下情况不需要重新计算历史统计：

```text
修改主播头像
修改主播封面
修改界面主题
修改通知声音
```

---

## 原规范 143：统计缓存一致性【历史说明：不可直接实现】

`sourceDataVersion` 必须在数据库事务内原子递增。任何“读取当前版本 → 应用层 +1 → 写回”的非原子实现均禁止。



统计缓存必须包含：

```text
statisticsVersion
sourceDataVersion
calculatedAt
```

例如：

```text
LiveSession数据版本：1832
统计缓存来源版本：1832
```

如果：

```text
LiveSession数据版本：1833
统计缓存来源版本：1832
```

UI 标记：

```text
正在更新统计…
```

然后重新计算。

---

## 原规范 144：数据版本化【历史说明：不可直接实现】

本项目统一区分以下版本/标识，不允许把多个概念混成一个“version”：

```text
roomSchemaVersion   = Room/数据库结构版本
backupSchemaVersion = 备份业务数据结构版本，用于业务字段兼容性判断
formatVersion       = 备份/导出文件格式版本
sourceDataVersion   = 业务源数据版本，数据库事务内原子递增
statisticsVersion = 统计缓存自身版本
snapshotId        = 一次一致性导出快照的唯一标识
backupId          = 一份备份文件的唯一标识
```

`DataRevision` 如果实现，只能作为 `sourceDataVersion` 的审计/变更记录，不得成为第二套独立的数据版本体系。

影响业务事实或统计结果的事务必须在数据库内原子推进 `sourceDataVersion`；禁止“先 SELECT 再应用层 +1 再 UPDATE”的非原子方式。

至少以下变更属于统计源数据变更，必须推进版本：

```text
LiveSession 的 startTime / endTime / durationSeconds
LiveSession 的合并 / 拆分 / 删除 / 恢复
影响时长计算的 source / confidence 修正
影响是否计入统计的业务状态变化
相关人工 correction 的创建、撤销、重做
```

仅改变展示元数据且不改变统计事实的字段可以不推进版本，但这个判断必须集中在统一的数据变更层，不能由各 UI 页面自行决定。

统计缓存通过其保存的 `sourceDataVersion` 判断是否过期；缓存不是源数据。

## 原规范 146：数据质量提醒优先级【历史说明：不可直接实现】

```text
INFO
仅提示，不打扰

WARNING
建议用户查看

ACTION_REQUIRED
需要用户主动修正
```

例如：

```text
WARNING
昨日有2条直播记录数据不完整。
```

```text
ACTION_REQUIRED
发现1条直播记录的开播/下播时间互相矛盾。
[立即修正]
```

---

## 原规范 147：数据异常通知防骚扰机制【历史说明：不可直接实现】

同一条数据异常不能反复通知。

通知键：

```text
DATA_{sessionId}_{problemType}_{revision}
```

用户已经处理：

```text
resolved = true
```

后不再重复提醒。

如果同一 Session 后来再次发生新的问题：

```text
revision + 1
```

允许再次提醒。

---

## 原规范 148：每日数据质量摘要【历史说明：不可直接实现】

建议增加可选通知：

```text
每日数据质量报告
```

示例：

```text
昨日监控数据报告

直播记录：23场
完整记录：21场
待修正：2场
监控中断：1次

⚠ 有2场直播时间不完整
[查看]
```

默认关闭，由用户主动开启。

---

## 原规范 149：“准确数据优先”统计模式【历史说明：不可直接实现】

统计页提供：

```text
统计口径
○ 全部可用数据
● 仅准确确认数据
○ 包含部分确认数据
```

默认建议：

```text
仅准确确认数据
```

避免异常记录悄悄污染统计结果。

数据完整度必须统一计算：

```text
数据完整度 = 完整且符合当前统计口径的 LiveSession 数 ÷ 纳入该质量评估范围的 LiveSession 总数 × 100%
```

`PARTIAL / UNKNOWN / INVALID` 或存在未解释监控盲区的记录不得计入“完整记录”。不同页面不得自行定义另一套百分比公式。统计时区属于统计配置；修改统计时区不修改历史时间戳、不推进 `sourceDataVersion`，但必须使受影响的 StatisticsCache 失效并按新时区重建。

---

## 原规范 160：统计系统最终设计原则【历史说明：不可直接实现】

直播历史统计必须遵循：

```text
原始监控数据 ≠ 最终事实
自动计算数据 ≠ 用户确认数据
统计缓存 ≠ 统计事实
异常数据 ≠ 正常数据
未知数据 ≠ 下播数据
```

最终可信链路：

```text
Monitor Observation
        ↓
Data Validation
        ↓
LiveSession
        ↓
User Correction（如有）
        ↓
Final Valid Session
        ↓
Statistics Calculator
        ↓
Statistics Result
        ↓
HTML / UI / Report
```

任何统计数字都应该能回答三个问题：

```text
1. 这个数字从哪些直播记录计算出来？
2. 这些直播记录哪些是自动检测、哪些是人工确认？
3. 是否存在尚未解决的数据盲区或异常？
```

如果无法回答，则该统计结果不应标记为“完全准确”。

---

## 原规范 161：v2.0 完整数据可信度最终模型【历史说明：不可直接实现】

最终采用五层数据可信度：

```text
RAW
原始观察

PROVISIONAL
暂定数据

CONFIRMED
确认数据

CORRECTED
用户修正数据

INVALID
确认无效
```

统计系统默认：

```text
CONFIRMED + CORRECTED
```

计入“准确统计”。

```text
PROVISIONAL
```

可在“全部数据”模式中显示，但不默认计入准确统计。

```text
INVALID
```

永远不计入正常统计。

---

## 原规范 162：面向用户的最终产品体验【历史说明：不可直接实现】

用户最终不需要理解内部数据库结构。

他只需要看到：

```text
直播历史

✅ 已确认
🟡 部分确认
✏ 已修正
⚠ 待修正
❌ 已排除
```

当发现问题：

```text
⚠ 发现3条历史记录存在时间异常

[立即修正]
```

修正时：

```text
只需要填写开播时间、下播时间、直播时长中的任意两个。
剩余一项由软件自动计算。
```

统计页面：

```text
本月直播总时长
80小时12分钟

已确认数据：78小时34分钟
部分确认数据：1小时38分钟
待修正：2场

[查看数据构成]
```

导出：

```text
[导出 HTML 报告]
```

最终用户可以得到一个完整的、可离线打开的数据报告，而不是一堆难以阅读的 CSV 数字。

---

## 原规范 172：P0-7 统计可重建【历史说明：不可直接实现】

统计结果不得作为唯一事实来源。

原则：

```text
LiveSession = 原始业务数据
StatisticsCalculator = 计算规则
StatisticsCache = 性能缓存
```

如果缓存损坏：

```text
删除缓存
↓
重新扫描 LiveSession
↓
重新计算
↓
生成新缓存
```

禁止：

```text
直接修改“总时长=80小时”
```

用户修正 Session 后，统计必须自动失效并重算。

---

## 原规范 190：P1-11 统计缓存失效策略【历史说明：不可直接实现】

以下操作必须触发统计缓存失效：

```text
修改开始时间
修改结束时间
修改直播时长
合并Session
拆分Session
删除/恢复Session
修改数据可信度
```

推荐：

```text
statisticsVersion += 1
```

导出报告记录使用的统计版本。

统计缓存还必须保存源数据版本，并统一使用已有的 `sourceDataVersion` 字段：

```text
sourceDataVersion
calculatedAt
```

只有基于不早于当前源数据版本计算出的结果才允许覆盖现有缓存。并发重建时，旧 `sourceDataVersion` 的结果必须被丢弃。

---


### 190.5 备份恢复范围必须形成业务闭包

恢复某个 Streamer 的业务数据时，Manifest 范围必须覆盖维持关系所需的父实体和关系，禁止产生悬空业务记录。

```text
Streamer.stableId
  ↓
LiveSession.stableId
  ↓
LiveEvent.eventId / sessionStableId
  ↓
Correction / Audit / Statistics source relationship
```

若选择的恢复范围无法形成完整闭包，必须在预检阶段明确指出缺失父实体/关系并阻止不安全的部分恢复；不得恢复没有 Streamer 父实体的 LiveSession，也不得恢复无法解析 Session 归属的 LiveEvent。

## 原规范 192：P1-13 数据质量异常不自动伪造修复【历史说明：不可直接实现】

软件可以自动修复：

```text
缺失缓存
重复缓存
过期 Outbox
孤儿关系
```

但不允许自动猜测：

```text
真实下播时间
用户没有提供的开播时间
网络盲区内的真实直播时长
```

此类数据只能：

```text
标记
估算
等待用户确认
```

---

## 原规范 193：P1-14 全局一致性检查任务【历史说明：不可直接实现】

建议每天或用户手动触发一次轻量检查：

```text
ConsistencyChecker
```

检查：

```text
Streamer ↔ LiveSession
Streamer ↔ Tag
Streamer ↔ Group
LiveSession ↔ History
LiveSession ↔ Statistics
Event ↔ Outbox
```

输出：

```text
一致
轻微问题
需要修复
无法自动修复
```

---

## 原规范 145：用户提醒原则：数据问题与系统问题分开【历史说明：不可直接实现】

系统异常通知：

```text
网络异常
Bilibili API异常
监控停止
软件恢复
```

数据质量提醒：

```text
某场直播时间无法确认
某场直播存在冲突
统计包含不完整记录
```

两者不得使用同一个“错误”概念。

---

## 原规范 102：可靠性验收标准【历史说明：不可直接实现】

以下规则视为必须满足：

```text
1. 网络异常绝不能自动产生“下播”事实。
2. 软件崩溃不能自动伪造精确下播时间。
3. 所有未确认时间段必须能够识别。
4. LiveSession 必须支持未完成状态。
5. 统计必须能够从源数据重新计算。
6. 用户必须能够看到监控中断。
7. 重要异常不能无限重复通知。
8. 恢复后必须立即执行补偿检查。
9. Outbox 未发送完成时必须可恢复。
10. 数据修复不能以猜测代替事实。
11. 主播卡片不能把过期数据伪装成实时数据。
12. 用户可以区分“未开播”和“暂时无法确认”。
```

---

# 08 导出、备份、恢复与数据迁移

## 原规范 19：应用配置文件导入/导出【历史说明：不可直接实现】

本章节只定义“普通应用配置文件”，不承担直播历史恢复。直播历史的备份与恢复统一遵循第 55、161、248 节的最终规范。

## 19.1 文件格式

使用版本化 JSON。示例：

```json
{
  "formatVersion": 1,
  "schemaVersion": 1,
  "exportedAt": 1789200000000,
  "settings": {},
  "groups": [],
  "tags": [],
  "streamers": []
}
```

普通应用配置至少包含应用设置、监控设置、主播管理配置、标签、分组及用户偏好；不得包含 LiveSession 历史、通知完整历史、诊断日志或任何认证凭证。

## 19.2 导入原则

应用配置导入必须执行：

```text
读取文件
↓
格式/版本校验
↓
数据校验
↓
预览
↓
用户确认
↓
单事务导入
↓
一致性校验
```

导入失败必须完整回滚，不允许出现“导入一半”的成功状态。

普通应用配置的覆盖/合并只影响它自身声明的配置域；不得借此删除或覆盖直播历史。

## 19.3 认证信息

禁止导出或恢复：

```text
access_token
refresh_token
SESSDATA
bili_jct
Cookie
密码
内部认证凭证
```

登录状态不属于普通应用配置文件，也不参与恢复业务状态。

## 原规范 55：应用配置导出 vs 直播历史备份配置文件【历史说明：不可直接实现】

本项目必须明确区分“普通应用配置导出”和“直播历史导出生成的备份配置文件”，二者用途不同，不能复用同一个数据格式。

## 55.1 普通应用配置导出

用途是迁移界面与监控配置，不承担历史数据备份。

包含：

```text
主播
标签
分组
监控开关
筛选/排序偏好
应用设置
```

不包含：

```text
LiveSession
状态历史
完整通知日志
完整诊断日志
账号密码
Cookie
Token
```

## 55.2 直播历史备份配置文件

“导出直播历史”时，用户可以选择生成 HTML 文件或“备份配置文件”。

备份配置文件不是展示报告，而是可被本项目识别并用于数据恢复的结构化备份包，至少应包含本次导出范围内恢复所需的：

```text
主播基础信息
标签与分组
监控相关配置（按导出范围）
LiveSession
必要的历史状态数据
与直播历史直接相关的人工修正记录
统计源数据版本信息
统计源数据与重建所需信息；StatisticsCache 仅可作为可丢弃的加速缓存
数据格式版本
backupSchemaVersion
backupId
导出时间
sourceDataVersion
恢复范围 Manifest
跨设备稳定业务 ID
```

不允许包含：

```text
账号密码
Cookie
access_token
refresh_token
SESSDATA
bili_jct
设备唯一标识
内部认证凭证
```

备份配置文件必须能够在没有登录状态的情况下完成本地恢复。登录仅用于关注主播导入，不属于恢复所需的数据。

进入正式备份文件并需要恢复关联的实体，必须满足以下二选一：

```text
1. 具有稳定业务 ID；
2. 明确声明为纯本机运行/诊断数据，并在恢复时重建而不是直接复原本地 ID。
```

因此 `StatusHistory`、`MonitoringGap`、`MonitoringGapStreamer`、`LiveSessionCorrection`、`AuditLog` 等若进入备份，必须通过稳定身份或可确定的父实体稳定身份建立关系；仅属于本机运行态的 Worker/Lease/临时任务状态不得从备份恢复。

## 55.3 恢复模式

导入备份配置文件时，必须由用户明确选择：

```text
增量恢复
覆盖恢复
```

两种模式都必须先执行：

```text
格式校验
  ↓
版本兼容性检查
  ↓
数据完整性校验
  ↓
恢复范围预览
  ↓
用户选择“增量恢复”或“覆盖恢复”
  ↓
用户最终确认
  ↓
安全点/回滚保护
  ↓
导入
  ↓
导入后一致性检查
  ↓
统计重算/缓存失效处理

统计重算必须在恢复事务成功提交之后单独执行，不得为了恢复统计把大规模 StatisticsCalculator 长时间嵌套在业务恢复事务中。恢复提交后先推进 `sourceDataVersion`，再启动受维护协调器保护的统计重建；重建失败时不回滚已经成功恢复的业务数据，而是保留“统计缓存待重建”状态。
  ↓
完成
```

覆盖恢复属于高风险操作，必须明确展示可能被替换的数据范围，并二次确认。

## 55.3.1 备份版本兼容性

本项目采用“尽可能恢复、明确告知缺失能力”的兼容策略：

```text
任何历史版本的备份
        ↓
允许当前版本尝试恢复
```

规则：

```text
当前 App 能识别备份中的全部业务字段
→ 完整恢复

备份来自更高版本，当前 App 不认识部分字段
→ 恢复全部当前版本可识别的字段
→ 忽略未知字段
→ 恢复摘要明确提示“文件没有完整恢复”

备份来自更低版本，备份缺少当前版本新增字段
→ 已知字段正常恢复
→ 新增字段使用安全默认值或“无历史值”
→ 不因此判定备份损坏

备份缺少当前恢复范围内不可安全推导的必需字段
→ 拒绝受影响数据的恢复
→ 明确说明缺失字段及原因
```

高版本备份恢复到旧版本时，旧版本不得把自己无法识别的字段静默当成默认值后显示“完整恢复”。用户必须能够看到哪些字段/能力没有恢复。未知字段可以被安全跳过，但不得改变同文件中已知字段的值。

任何版本向更高版本恢复时，不要求旧备份拥有未来字段；升级后的新增字段使用当前版本默认策略初始化。

增量恢复表示：只处理备份文件 Manifest 明确声明的恢复范围；范围外本地数据完全不动。恢复范围内，备份配置文件是最终权威来源。

必须依据跨设备稳定业务 ID 建立对应关系：

```text
Streamer：stableId
LiveSession：stableId
人工修正：stable correctionId / revision 标识
标签、分组：stableId
LiveEvent：eventId
NotificationAggregate：aggregateId
```

规则：

```text
备份范围内本地不存在 → 新增
备份范围内本地已存在且不同 → 按备份内容更新
备份明确为已删除 → 恢复删除状态
备份范围外本地数据 → 保持不变
```

不得根据 `updatedAt` 推断“本地较新所以拒绝恢复”。用户既然明确选择恢复，且数据属于备份声明范围，就以备份文件为准。所有新增、更新、跳过、删除状态恢复都必须形成恢复摘要并记录 AuditLog。

每次恢复生成 `restoreRunId`，贯穿预检、事务、摘要和审计；预检阶段不得写入正式业务表，失败不得留下部分恢复结果。

每次恢复操作必须生成独立 `restoreRunId`，贯穿预检、事务、摘要和 AuditLog。重复导入同一个备份文件不是幂等要求，但同一 `restoreRunId` 的重试不得重复产生第二套业务变更。

实际恢复必须先完成完整预检，再进入单次业务事务；预检失败不得留下部分恢复结果。

## 55.5 覆盖恢复规则

覆盖恢复表示：使备份 Manifest 声明的业务恢复范围最终与备份文件完全一致。不得简单理解为无条件 `DELETE` 全库。

```text
备份范围内 → 最终状态与备份一致
备份范围外 → 保持不变
系统迁移元数据 / 当前恢复审计 / 当前运行安全状态 → 不被备份业务数据覆盖
```

覆盖前必须创建事务一致、可验证的本地安全点；只有恢复验证成功后才能清理安全点。

恢复时采用的总体原则：

```text
校验 → Manifest 范围确认 → 预览 → 模式选择
→ 用户确认 → 安全点 → 导入 → 验证 → 统计处理 → 完成
```

---

## 原规范 132：HTML 报告结构【历史说明：不可直接实现】

建议：

```text
┌────────────────────────────────────┐
│ 哔哩哔哩主播监控 · 直播历史报告     │
├────────────────────────────────────┤
│ 数据范围：2026-01-01 ~ 2026-09-12  │
│ 主播：86                            │
│ 直播：1248场                        │
│ 总时长：xxxx小时                    │
├────────────────────────────────────┤
│             统计图表                │
│                                    │
│ 直播次数趋势                       │
│ 直播时长趋势                       │
│ 主播排行                            │
│ 分组统计                            │
│ 标签统计                            │
├────────────────────────────────────┤
│              详细记录               │
└────────────────────────────────────┘
```

---

## 原规范 131：直播历史导出 HTML【历史说明：不可直接实现】

新增正式导出格式：

```text
HTML
```

导出目标：

```text
可以直接用手机/电脑浏览器打开
无需安装本软件
可视化展示直播历史
```

推荐生成：

```text
直播历史报告.html
```

HTML 应自包含：

```text
HTML
CSS
JavaScript
```

默认不依赖外部 CDN，以便离线打开。

---

## 原规范 133：HTML 可视化图表【历史说明：不可直接实现】

第一版建议包含：

```text
1. 每日直播次数
2. 每周直播时长
3. 每月直播时长
4. 主播直播次数排行
5. 主播直播时长排行
6. 分组统计
7. 标签统计
8. 直播时间段分布
```

后续可以增加：

```text
9. 星期 × 小时热力图
10. 主播直播规律趋势
11. 开播时间分布
12. 直播时长箱线/区间图
```

图表数据直接从导出的 JSON 数据生成，不需要联网。

---

## 原规范 134：HTML 报告的数据可信度展示【历史说明：不可直接实现】

这是非常重要的一项。

HTML 报告不能只展示漂亮图表，还必须展示数据质量。

例如：

```text
数据完整度：96.8%

✅ 已确认记录：1188
🟡 部分确认：39
⚠ 待修正：15
❌ 已排除：6
```

每个统计图表应注明：

```text
统计基于：已确认 + 部分确认记录
```

或者：

```text
仅统计已确认数据
```

用户导出后，即使脱离 App，也能知道数据是否完整。

---

## 原规范 135：直播历史导出的数据范围选择【历史说明：不可直接实现】

“导出直播历史”允许快速按以下方式选择：

```text
按日：选择一个或多个具体日期
按月：选择一个或多个完整月份
按年：选择一个或多个完整年份
全部：选择全部可用历史
自定义：自由选择起止日期或跳跃日期集合
```

也可以提供常用快捷范围：

```text
今天
本周
本月
本季度
本年度
```

同时允许手动勾选具体日期。手动勾选可以是连续日期，也可以是跳跃日期；系统不要求用户选择连续区间。

除此之外，还可以选择：

```text
主播
分组
标签
数据可信度
是否包含异常记录
是否包含人工修正记录
```

导出任务开始时必须冻结最终选择，并将选择写入 `LiveHistoryExportRequest` / `ExportSnapshot`。导出过程中修改首页筛选、主播资料或统计设置不得影响已经开始的导出任务。

日期快捷方式和具体选择最终都转换为明确的 `selectedDateSet` / `startAt` / `endAt` 语义；不得在后台根据当前 UI 状态再次推导范围。

---

## 原规范 136：HTML 导出级别【历史说明：不可直接实现】

提供三种：

```text
简洁报告
```

仅包含核心统计和简要历史。

```text
完整报告
```

包含所有直播记录、图表和数据质量。

```text
原始数据报告
```

额外包含监控观察、数据来源、修正记录等技术信息。

默认使用：

```text
完整报告
```

---

## 原规范 137：直播历史附加数据导出【历史说明：不可直接实现】

HTML 是面向用户查看、分享和离线保存的历史报告格式；直播历史备份配置文件是唯一正式的业务数据恢复来源。

如后续提供 JSON/CSV 数据交换，可作为分析或其他软件读取格式，但不得作为另一套隐式“备份恢复”机制。

```text
HTML        → 查看/分享/离线报告
BACKUP_CONFIG → 正式备份与恢复
JSON/CSV    → 可选数据交换/分析（如实现）
```

HTML 中需要图表数据时，优先把已经生成的快照数据内嵌到 HTML，不要求联网。

任何数据交换格式都必须来自同一个 `ExportSnapshot`，不得分别读取数据库形成互相矛盾的结果。

## 原规范 138：数据导出中的隐私规则【历史说明：不可直接实现】

HTML 默认只包含：

```text
主播 UID
主播昵称
直播历史
分组
标签
统计数据
```

严禁包含：

```text
access_token
refresh_token
SESSDATA
bili_jct
Cookie
```

任何身份认证凭证都不能进入 HTML、JSON、CSV、ZIP 导出。

---

## 原规范 153：配置导出与直播历史导出的区分【历史说明：不可直接实现】

本项目只保留两类“导出/恢复”语义，避免把普通配置和业务数据备份混为一谈：

```text
普通应用配置导出
→ 用于恢复软件设置、主播管理配置、标签、分组和用户偏好
→ 不包含 LiveSession 历史

直播历史导出
→ HTML 用于查看、分析、分享
→ 直播历史备份配置文件用于正式恢复业务数据
```

HTML 报告不是恢复源；普通应用配置也不能代替直播历史备份。

本项目当前不提供实时同步、多用户协作或实时多设备同步；跨设备数据迁移仅通过用户主动选择备份文件完成。

## 原规范 174：P0-9 导出快照与原子导出【历史说明：不可直接实现】

直播历史导出必须创建：

```kotlin
ExportSnapshot
```

快照冻结：

```text
主播范围
时间范围
筛选条件
排序条件
统计版本
数据版本
创建时间
```

导出过程使用：

```text
report.tmp
    ↓
生成
    ↓
完整性校验
    ↓
rename/move
    ↓
report.html
```

如果中途崩溃：

```text
report.html 不应该存在半成品
```

只清理 `.tmp` 文件即可。

---

## 原规范 184：P1-5 导入文件校验和【历史说明：不可直接实现】

应用配置和直播历史导入文件都可以携带校验和。

例如：

```json
{
  "schemaVersion": 2,
  "checksum": "...",
  "payload": {}
}
```

导入流程：

```text
读取文件
↓
解析
↓
验证schema
↓
验证checksum
↓
数据质量预检
↓
冲突检查
↓
用户确认
↓
事务导入
```

校验失败直接拒绝，不修改数据库。

---

## 原规范 185：P1-6 MigrationVerifier【历史说明：不可直接实现】

每次 Room Migration 后进行轻量验证：

```text
主播数量
Tag 数量
Group 数量
LiveSession 数量
StatusHistory 数量
关系表数量
```

允许出现用户主动删除造成的数量变化，但迁移程序不能无故丢失大量历史数据。

如果验证异常：

```text
停止进一步破坏性操作
记录迁移错误
提示用户
```

---

## 原规范 191：P1-12 导入历史数据的预检与隔离【历史说明：不可直接实现】

导入大量直播历史时，必须先写入临时导入区：

```text
ImportBuffer
```

完成：

```text
Schema验证
字段验证
时间验证
UID验证
冲突检测
可信度检测
```

用户确认后再进入正式表。

导入失败：

```text
正式历史数据库不发生变化
```

---

## 原规范 226：高级功能的配置导入导出规则【历史说明：不可直接实现】

高级设置可以包含在“应用配置导出”中，但日志和诊断数据必须独立。

```text
应用配置
├── 普通设置
├── 高级设置
├── 主播
├── 标签
└── 分组

直播历史导出
└── LiveSession

诊断导出
├── 监控错误日志
├── 软件运行错误日志
└── 健康快照
```

默认：

```text
日志不进入普通配置导出
直播历史不进入普通配置导出
```

---

## 原规范 56：直播历史容量与自动清理策略【历史说明：不可直接实现】

直播历史按“每个主播的 LiveSession 条数”管理容量。每个主播只能选择两种模式：

```text
UNLIMITED：不按条数自动清理
MAX_RECORDS=N：最多保留 N 条 LiveSession
```

这里的记录仅指该主播的 LiveSession，不包含 NotificationOutbox、通知历史、诊断日志等其他数据。

## 56.1 UNLIMITED

`UNLIMITED` 下不因数量达到某个阈值自动删除直播历史。系统仍可显示存储占用，并允许用户手动导出和清理。

## 56.2 MAX_RECORDS=N

```text
当前记录数 < 95% N
    → 正常

当前记录数 >= 95% N
    → 对应主播卡片/房间区域显示容量预警，并提示尽快备份

当前记录数 >= N
    → 启动一次自动清理评估
```

达到上限后的清理只允许删除该主播最旧的 CLOSED `LiveSession`。`OPEN` Session 永远不得因为容量策略被删除。

若最近的 CLOSED Session 正在导出、恢复、迁移或其他受保护操作中，则跳过受保护记录，继续寻找其他可清理的 CLOSED Session；若没有可安全清理的记录，则不强制删除，改为在健康中心提示用户处理容量。

清理与导出/恢复必须受同一数据维护互斥规则约束，并在删除前再次校验 Session 状态和保护标记。

## 原规范 249：全篇规范归一化：最终规则优先级【历史说明：不可直接实现】

本节用于解决本文件多次迭代后可能出现的旧示例、历史草稿与最终规则冲突。

## 249.1 规则优先级

当文档不同章节出现矛盾时，优先级固定为：

```text
本节最终规则
    > 248 系列深层稳定性不变量
    > 165～247 稳定性强化规范
    > 109～164 历史/统计/导出详细模型
    > 1～108 早期基础草稿
```

早期章节仅用于说明设计演进，不得作为最终实现依据。若早期章节与后续最终规范冲突，必须以最终规范为准，不允许实现者选择更容易实现的旧方案。

## 249.2 数据身份

```text
本地 id
→ 仅用于当前数据库关系

stableId / eventId / aggregateId
→ 跨设备备份恢复的稳定身份
```

本项目不设计实时多人协作、实时多设备同步或账号隔离的监控架构。跨设备仅指用户通过备份文件主动迁移/恢复数据。

## 249.3 备份版本兼容原则

原则：**所有版本都允许尝试恢复备份。**

恢复流程必须区分：

```text
备份完全包含当前版本支持的字段
    → 完整恢复

备份包含当前版本不认识的新字段
    → 忽略未知字段，但不得破坏已知字段

当前 App 版本缺少备份中的部分能力/字段
    → 允许恢复已知部分，并明确提示“文件没有完整恢复”

文件缺少当前业务必需且无法安全推导的字段
    → 对受影响数据拒绝导入，并明确说明原因
```

旧版本 App 恢复高版本备份时，不得假装 100% 恢复成功；必须在恢复摘要中列出被旧版本忽略或无法恢复的字段/功能。未知字段不允许因为解析器版本过低而被默默当成默认值。

高版本 App 恢复低版本备份时，应尽可能正常恢复；新增字段没有历史数据时使用当前版本安全默认值或标记为“无历史值”，不得因为旧备份没有新字段而判定文件损坏。

## 249.4 历史容量最终规则

直播历史容量按主播分别计算。每个主播可以选择：

```text
UNLIMITED
或
MAX_RECORDS = N
```

当：

```text
count >= 95% × N
```

该主播必须显示容量预警标记，并提供提前备份入口。达到上限后，自动清理从该主播最早的 CLOSED LiveSession 开始执行，直到恢复到低水位。v2.0 默认低水位定义为 `90% × N`；清理目标不得低于该低水位，除非用户手动执行更彻底的清理。

若没有任何可安全清理的 CLOSED Session（例如剩余记录全部为 OPEN 或均处于受保护状态），不得强制删除 OPEN Session，也不得无限重复清理任务；应保持超限状态并将主播标记为 `USER_ACTION_REQUIRED`。

## 249.5 导出日期与文件名最终规则

快捷范围与选择方式：

```text
按日 / 按月 / 按年 / 自定义 / 全部
```

并可提供今天、本周、本月、本季度、本年度等常用快捷入口。

用户也可以跳跃选择日期。文件名使用本次实际导出结果中的最早直播日期、最晚直播日期，以及导出对象范围：

```text
单主播：
2026-01-01_2026-09-12_主播A.html

多主播：
2026-01-01_2026-09-12_20位主播.html
```

日期只取本次实际成功导出的 LiveSession 中可确定的直播日期；跳跃日期不改变命名算法，不列出中间缺失日期。

如果导出范围内有记录，但没有任何可确定的直播日期，则禁止伪造日期（例如 1970-01-01），改用不含日期的安全文件名，并在导出结果中明确提示“部分记录缺少可确定日期”。'

文件名日期必须按本次导出使用的统计时区，把实际成功导出的记录转换成本地日期后再计算，不能直接截取 UTC/raw epoch。

文件名中的“最早实际直播日期/最晚实际直播日期”必须按**本次导出使用的统计时区**把 LiveSession 时间转换为本地日期后再计算；不得直接使用 UTC 日期。跨午夜 Session 按统计时区计算其归属日期。

## 249.6 关注导入最终规则

关注导入只更新 Bilibili-owned 字段；标签、分组、排序、备注、监控/通知策略以及用户手工修正均属于用户数据，不得被关注导入覆盖。重新登录不同账号后继续按照本地 UID 做增量更新，不清空历史，不建立独立监控空间。 若命中已软删除 UID，必须进入用户选择流程，由用户选择“恢复原记录 / 跳过”；不得自动恢复。

## 249.7 导出/清理/恢复/迁移互斥

以下操作必须经过统一维护协调器：

```text
备份恢复
数据库 Migration
一致性修复
历史清理
大规模统计重建
```

导出使用一致性快照，不得生成“数据库中从未真实存在过”的混合结果。恢复、Migration、清理不得破坏活动快照的语义；必要时应等待、分阶段执行或安全终止任务。

## 249.8 数据库失败的业务语义

任何影响核心业务事实的事务提交失败，均不得被当成普通日志错误后继续认为“业务已经完成”。

```text
远程观察成功
↓
本地事务提交失败
↓
该业务事实仍未完成持久化
↓
记录可恢复失败状态
↓
后续重新处理 / Recovery
```

禁止在事务失败后提前发送“已保存/已恢复/已更新”的成功 UI。

## 249.9 Migration 与运行监控

数据库 Migration 开始前必须停止或冻结使用旧 Schema 的 MonitoringEngine。必须先进入维护状态并使旧 Lease/fencing 失效，Migration 完成后通过 ConsistencyChecker，再由当前版本重新建立运行 Lease。旧进程不得在新 Schema 上继续执行旧版本数据库写入。


## 249.9.1 远程资料为空值与身份字段规则

`uid` 是主播业务身份；`roomId`、昵称、封面、分区等属于可变的远程资料。远程接口暂时无法返回有效 `roomId` 时，不得写入虚构的 `0`、`-1` 或其他占位值冒充真实房间。

数据库字段是否允许 NULL 必须与实际 API 语义一致；对于“主播 UID 有效但当前没有可用直播间资料”的情况，应保留主播实体并把资料刷新结果标记为 `PARTIAL/UNKNOWN`，而不是破坏已有可靠历史。

### 249.9.1.1 昵称缺失时的占位文案（**已定稿**）

`streamer.name` 是 `NOT NULL`，但远程昵称可能暂时拿不到。**已定稿决策：写入占位文案，不做额外标记，下次成功获取时直接覆盖。**

```text
判定"昵称不可用"：
  远程未返回 name 字段，或返回 null，或 trim 后为空字符串，或仅含空白字符
      → 视为 UNRECOVERABLE_NAME_THIS_ROUND（本轮昵称不可用）

写入规则（同一事务内，且仅当 streamer.nameLocked = 0）：
  streamer.name              = monitoring_config.placeholderText（唯一来源，默认 '[待获取]'）
  streamer.nameLocked        = 0   （占位值本身不算"已锁定"，下次成功刷新即可覆盖）
  streamer.lastObservationResult = 'PARTIAL'
  streamer.updatedAt         = now
  不修改 confirmedLiveStatus；不修改 freshnessStatus 的判定依据（仍以 lastConfirmedAt 为准）
  不写 MonitoringErrorLog（昵称缺失属于正常降级，不是错误，避免污染诊断日志）

覆盖规则（仅当 streamer.nameLocked = 0）：
  下一次 RoomMetadataRefresh 成功返回有效 name 时，直接覆盖该占位值：
  streamer.name = <remote name>，同时把 lastObservationResult 按本轮真实结果写回
  覆盖不需要用户确认，也不需要保留占位值的历史痕迹

用户手工编辑昵称：
  streamer.name = <用户输入>，同时置 streamer.nameLocked = 1
  此后远程刷新一律跳过 name 字段（不覆盖，也不报错）
  用户恢复"跟随远程"时置 nameLocked = 0，下一次刷新即写回远程昵称
```

必须遵守的边界：

```text
1. 占位文案只能来自 monitoring_config.placeholderText，禁止在代码里硬编码第二份。
2. nameLocked 用于表达"name 不可被远程覆盖"（用户手工编辑 = 1）。
   判定"当前是占位值"必须用 name == monitoring_config.placeholderText 比较，
   不得用 nameLocked 推断，否则用户改了占位文案设置后旧行会被误判。
3. 占位值必须与真实昵称可区分：实现不得把占位值写入任何"用户自定义"字段
   （备注 note、标签、分组名），也不得让占位值参与搜索排序的字母序（应按"资料缺失"分组置后）。
4. 占位值不得写入 LiveSession.titleAtStart / titleAtEnd 等历史快照字段：
   这些字段拿不到就保持 NULL，不得用占位文案伪造当时的标题。
5. 占位值不得进入导出/备份作为"真实昵称"，但必须原样出现在备份中（否则恢复后无法与真实昵称区分）。
   导出 HTML 报告时必须把占位主播单列一节说明"以下主播昵称尚未获取"。
6. 占位状态不是错误，不进健康中心的故障计数；但数据质量中心可以统计"昵称待获取的主播数"。
```

## 249.9.2 远程响应有效性分层

HTTP 成功不等于业务观察成功。最终 `ObservationResult.SUCCESS` 必须同时满足：

```text
Transport 成功
+ API 响应结构有效
+ UID 能正确匹配本地主播
+ 直播状态字段有效
+ 必要业务字段满足当前操作要求
```

缺失、重复、类型错误、UID 错配等情况必须分类为 `PARTIAL` / `INVALID` / `API_ERROR` 等，不得为了让批次“看起来成功”而用默认值补齐。

## 249.10.0 已软删除主播的重新添加

手工重新添加一个已经软删除的 UID 时，统一走恢复原实体：

```text
重新添加已软删除 UID
    ↓
恢复原 Streamer 记录
    ↓
清除 deletedAt
    ↓
保留 stableId / 历史 / 用户配置
```

关注导入命中已软删除 UID 时不得自动恢复，必须让用户选择“恢复原记录 / 跳过”。

## 249.10 直播通知最终语义

直播中通知的用户可见文案统一为“正在直播”：

```text
普通 START 确认 → “正在直播”
异常恢复后重新确认 LIVE → “正在直播”
```

两者业务事件身份仍然独立，不得因为文案相同而错误去重。

关播通知采用“连续监控区间”资格模型：

```text
LIVE / 恢复后重新确认 LIVE
        ↓
建立当前连续可靠监控区间
        ↓
区间内出现异常？
   是              否
   ↓                ↓
当前区间失去资格    持续监控
                    ↓
              可靠确认 OFFLINE
                    ↓
              发送关播通知
```

若区间中出现异常，后续恢复时主播仍在直播，则建立新的连续监控区间；新的区间可以重新获得关播通知资格。恢复时已确认下播，则不得发送关播通知。

## 249.10.1 通知与连续监控区间边界

直播中通知与关播通知必须遵循同一套业务事实，但用户可见语义和通知资格分别处理：

```text
普通 START 确认
    → “正在直播”

异常恢复后重新确认 LIVE
    → “正在直播”

最终确认 OFFLINE
    → 仅当“当前连续可靠监控区间”满足资格时发送关播通知
```

一次连续可靠监控区间从“确认 LIVE 且监控重新进入可靠连续状态”的时刻开始，到该区间发生异常或最终确认 OFFLINE 为止。

```text
新区间建立
  ↓
持续 SUCCESS 观察
  ↓
发生异常？ ── 是 → 当前区间立即失去关播资格
  │
  否
  ↓
确认 OFFLINE
  ↓
发送关播通知
```

如果异常后恢复时主播仍在 LIVE，则从该恢复确认点建立新区间；旧异常区间不会永久禁止后续新区间发送关播通知。恢复时已经 OFFLINE，则不发送主播的“正在直播”恢复通知，也不发送关播通知。

系统级“监控已恢复”通知与主播级“正在直播”通知可以同时存在，但二者必须拥有独立事件身份、独立 `eventKey` 和独立审计记录。

## 249.10.2 NotificationHistory 与 Outbox 生命周期

`NotificationOutbox` 负责“是否需要可靠投递以及当前投递状态”；`NotificationHistory` 负责“通知业务事件的审计结果”。两者不是同一个状态机。

```text
LiveEvent / NotificationIntent
        ↓
NotificationOutbox
        ↓
投递尝试
        ↓
NotificationHistory（记录本次用户可见通知的审计结果）
```

`DELIVERY_UNKNOWN` 必须如实记录为结果未知，不得在没有证据时改写为 `SENT`。NotificationHistory 可以同时保存 `deliveryStatus=UNKNOWN`、`eventKey`、`notificationId`、尝试次数和最终已知原因。

NotificationHistory 的写入规则：

```text
Outbox 首次进入可投递状态
→ 可创建业务通知历史草稿（CREATED）

每次 DeliveryAttempt 完成
→ 在同一数据库事务写入/更新该 attempt 的结果

DELIVERY_UNKNOWN
→ 写 deliveryStatus=UNKNOWN，不得写 SENT

SENT
→ 写 deliveryStatus=SENT + deliveredAt（若可确认）

EXPIRED / CANCELLED / FAILED
→ 写最终已知结果和原因
```

`NotificationHistory` 不决定 Outbox 当前生命周期；Outbox 的 `status` 仍是投递状态唯一事实来源。

### 249.10.2.0 NotificationDeliveryAttempt 事务边界与 History 关系

NotificationManager.notify() 永远在数据库事务之外执行。`NotificationDeliveryAttempt` 的开始记录、Claim 与 Lease 属于发送前事务；notify 返回后另起事务记录 SENT/DELIVERY_UNKNOWN/FAILED，并同步写入 NotificationHistory 的投递结果快照。`DELIVERY_UNKNOWN` 不得被记录成 SENT。用户手动清除通知只影响系统通知栏/History 展示，不修改 Outbox 业务发送状态。

**249.10.2.0.1 History 行的定位键（此前缺失，现固定）**

`notification_history` 的写入分为"创建草稿"与"更新结果"两种，必须用**稳定键**定位要更新的行，不得每次 attempt 都插入新行：

```text
historyId  = "nh:{eventKey}"     （每个 eventKey 恰好一行审计记录）
定位键      = historyId 或 eventKey（两者等价且唯一）
attemptId  = 最近一次结算的 NotificationDeliveryAttempt.attemptId（可空，覆盖写，只保留最后一次）
attemptCount = 累计尝试次数（每次 claim 后 +1，与 outbox.attemptCount 同事务同步）
deliveredAt  = 仅当 deliveryStatus = SENT 时非空
reason       = 最后一次失败/过期/取消的已知原因（业务文案键，不是原始异常字符串）
```

写入规则与 `deliveryStatus` 的取值映射：

```text
Outbox 首次进入可投递状态        → INSERT historyId="nh:{eventKey}", deliveryStatus=CREATED
事务A（claim + attempt started）  → UPDATE deliveryStatus=PROCESSING, attemptCount=attemptCount+1, updatedAt
事务B：notify 返回成功            → UPDATE deliveryStatus=SENT, deliveredAt=now, attemptId, reason=NULL
事务B：明确失败                   → UPDATE deliveryStatus=FAILED, attemptId, reason=<分类原因>
启动恢复结算为 DELIVERY_UNKNOWN   → UPDATE deliveryStatus=DELIVERY_UNKNOWN, attemptId, reason='DELIVERY_UNKNOWN_ON_RECOVERY'
超过 expiresAt                    → UPDATE deliveryStatus=EXPIRED, reason='EXPIRED'
业务主动取消                      → UPDATE deliveryStatus=CANCELLED, reason=<原因>
```

全部为 `INSERT ... ON CONFLICT(historyId) DO UPDATE` 的幂等写法；`updatedAt` 每次必须刷新，`recordedAt` 只在首次创建时写入。History 行数必须与 `notification_outbox` 行数一一对应；ConsistencyChecker 发现孤儿 History（无对应 Outbox）时按"审计保留"处理，不得自动删除。

## 249.10.2.1 Outbox 状态恢复

```text
DELIVERY_UNKNOWN
→ deliveryAttempt lease 到期后重新 claim
→ 使用同一 eventKey + 同一 notificationId
→ 重新尝试

FAILED（失败原因属于通知权限/通道暂不可用）
→ 用户重新允许通知 / Channel 恢复可用
→ 重新评估 expiresAt
→ 未过期：转 RETRY_WAIT
→ 已过期：EXPIRED

FAILED（永久性业务错误）
→ 不自动无限重试
→ 保持 FAILED 并记录原因
```

Outbox 到达 `expiresAt` 后永远不得重新进入发送流程。`DELIVERY_UNKNOWN` 不是“发送失败”，也不是“发送成功”，只能作为不确定结果进入恢复分支。

Outbox 可以过期、取消或清理，但如果 NotificationHistory 被产品定义为历史审计，则不能因为 Outbox 清理而自动删除历史结果。

## 249.10.3 通知冷却、事件过期与故障问题键职责分离

以下机制不得合并：

```text
事件冷却 / 去重
→ 控制是否再次创建同类通知意图

event expiresAt
→ 控制已经存在的 Outbox 是否仍具有发送价值

problemKey + cooldown
→ 控制同一类系统故障告警是否重复提醒
```

主播直播通知不得因为系统故障 `problemKey` 的冷却策略而被错误抑制；异常恢复后的“正在直播”通知也必须独立判断其事件身份与发送价值。

## 249.10.4 Backup Scope 必须形成业务闭包

任何可恢复的备份范围必须包含恢复所需的最小业务闭包，不得产生悬空关系。

```text
Streamer
  ├── StreamerTag / StreamerGroup
  ├── LiveSession
  │     ├── LiveSessionCorrection
  │     └── LiveEvent（如纳入备份范围）
  └── 与上述对象关联的业务元数据
```

Manifest 声明的范围不足以支撑关系恢复时，预检必须拒绝受影响关系，而不是创建悬空外键或静默丢失关系。导出报告可以是非恢复格式；正式 BACKUP_CONFIG 才承担上述恢复闭包。

## 249.10.5 统计口径与时区变更

统计源数据必须与统计展示配置分离：

```text
LiveSession / Correction
→ sourceDataVersion

统计时区 / 筛选条件 / 分组方式
→ StatisticsQuery / StatisticsConfig
```

仅修改统计时区、统计筛选或展示分组，不修改历史时间戳，不推进 `sourceDataVersion`；但会使对应统计结果失效或要求按新查询重新计算。

统计口径必须明确区分：

```text
CONFIRMED / 可准确计算
PROVISIONAL / 可使用但需标记不确定性
UNKNOWN / 无法可靠用于要求精确值的统计
INVALID / 不得进入正常统计
```

“数据完整度”指标必须采用统一公式，由统计模块唯一计算。当前规范定义：

```text
数据完整度 = 在指定统计周期内、满足统计所需最小时间信息且未被 INVALID/EXCLUDED 排除的 LiveSession 数
          ÷ 指定周期内全部纳入检查的 LiveSession 数
```

如果分母为 0，结果为 `N/A`，不得显示 0% 伪装成“完整度很低”。不同页面不得自行定义同名百分比。

## 249.10.6 RefreshCoordinator 请求共享与取消

同一资源键存在 RUNNING 刷新任务时，新的相同刷新请求默认复用已有请求结果，而不是再次发起 HTTP。

```text
请求 A ─┐
请求 B ─┼→ 同一个实际 HTTP
请求 C ─┘
          ↓
      一个共享结果
```

单个调用方取消等待不得取消其他调用方仍需要的共享请求；只有所有订阅者都退出，且任务本身允许取消时，才可以取消底层 HTTP。主动取消不得计入 API 失败、CircuitBreaker、MonitoringGap 或统计失败率。

## 249.10.7 OverallHealth 决策

`OverallHealth` 必须由统一健康聚合器计算，不允许不同页面自行用 `MonitoringHealth`、`NetworkHealth` 等字段拼接。至少遵循：

```text
数据库不可用
    > 监控实际停止且用户意图仍要求运行
    > 监控处于受系统限制且无法达到用户要求
    > 通知不可用但监控仍可运行
    > 网络暂时异常但系统正在恢复
    > 健康
```

该优先级用于确定首页与健康中心的主状态；各子系统详细原因仍需分别展示。

## 249.11 最终实现禁令

```text
禁止业务层直接 notify()
禁止网络错误映射为 OFFLINE
禁止使用本地自增 id 作为跨设备身份
禁止用 updatedAt 猜测恢复冲突胜负
禁止把缺失的历史记录自动解释为删除
禁止恢复旧设备运行态
禁止让旧监控实例绕过 fencing 写库
禁止让旧 Worker 在失去 Claim 后继续发送
禁止把 StatisticsCache 当作最终事实
禁止把 HTML 当作恢复源
禁止把登录状态作为监控核心依赖
```


## 249.12 人工编辑 CAS

所有 LiveSession 用户编辑、撤销、合并、拆分操作必须携带编辑开始时读取的版本或 correctionVersion。保存时若版本已变化，则拒绝旧提交并要求基于最新记录重新编辑，禁止静默覆盖后台/其他操作产生的新版本。

## 249.13 导出与清理资源边界

导出建立一致性快照后，历史清理不得改变该快照已经确定的数据内容。若导出采用长事务造成 WAL、磁盘或数据库锁资源达到安全阈值，必须安全取消导出并保留原始数据；不得通过强制删除快照所依赖的数据来解除压力。

# 09 主播管理、标签分组、添加与账号关注导入

## 原规范 54：添加主播与数据完整性【历史说明：不可直接实现】

支持输入：

```text
UID
Bilibili 用户空间 URL
直播间 URL
短链接（如可可靠解析）
```

流程：

```text
用户输入
 ↓
解析 UID / roomId
 ↓
远程查询
 ↓
数据校验
 ↓
展示预览
 ↓
用户确认
 ↓
Room Transaction
```

数据库唯一约束：

```text
uid UNIQUE
roomId 可建立索引
```

同 UID 重复添加时不能产生第二条主播记录。

---

## 原规范 18：Bilibili 登录与关注导入【历史说明：不可直接实现】

## 18.1 不允许把登录密码长期保存

禁止：

```text
SharedPreferences.username
SharedPreferences.password
```

也禁止：

```text
Log.d("Login", response.body.toString())
```

因为响应可能包含 token/cookie。

## 18.2 认证接口抽象

```kotlin
interface AuthProvider {
    suspend fun login(): AuthResult
    suspend fun logout()
    suspend fun getCurrentUser(): BiliUser?
    suspend fun refreshTokenIfNeeded(): Boolean
}
```

## 18.3 凭证存储

使用 Android Keystore + 加密存储。

设计为：

```text
Keystore
   |
密钥
   |
Encrypted token storage
```

数据库只保存：

```text
登录用户 UID
登录状态
最后刷新时间
```

敏感凭证独立保存。

## 18.4 关注导入

流程：

```text
点击“导入我的关注”
        ↓
检查登录状态
        ↓
未登录 → 登录
        ↓
获得授权身份
        ↓
请求关注主播列表
        ↓
筛选存在直播间的用户
        ↓
批量读取直播间资料
        ↓
预览即将导入的主播
        ↓
用户确认
        ↓
写入 Room
```

必须支持：

```text
仅新增
新增 + 更新已有信息
```

“更新已有信息”仅作用于 Bilibili-owned 字段，例如昵称、头像、直播间资料、分区、封面和直播链接；不得覆盖用户自定义的标签、分组、排序、备注、主播级监控/通知策略等字段。关注导入不得删除未出现在本次关注列表中的本地主播，也不得改变核心监控数据空间。

若本地已经存在相同 UID 且 `deletedAt != null`，不得自动恢复。导入预览必须将其标记为“已删除主播”，并让用户选择：

```text
D. 让用户选择
    ├─ 恢复原记录 → 清除 deletedAt，保留 stableId / 历史 / 用户配置
    └─ 跳过 → 保持当前软删除状态
```

只有用户明确选择恢复时，关注导入才可以重新启用该主播；这仍然是恢复原 `Streamer` 实体，而不是新建记录。

默认选：

```text
新增 + 更新已有信息
```

不要把整条 `Streamer` 作为一个对象无条件覆盖写回。

---

## 原规范 59：Bilibili 账号与关注导入边界【历史说明：不可直接实现】

账号层统一使用（与 18.2 为同一接口，本节不得重复声明）：

```kotlin
// 唯一最终签名见 §18.2：login / logout / getCurrentUser / refreshTokenIfNeeded
```

关注导入层统一使用：

```kotlin
interface FollowingDataSource {
    suspend fun getFollowings(): List<BiliFollowedUser>
}
```

必须把“登录成功”和“有权限读取关注列表”作为两个状态。

```text
AUTHENTICATED
FOLLOWING_PERMISSION_AVAILABLE
FOLLOWING_IMPORTABLE
```

没有对应开放权限时，不得假装实现成功。应明确告诉用户当前版本无法从官方能力读取该数据，并提供手动导入/配置导入等替代路径。

---

# 10 设置、配置版本与高级配置

## 原规范 31：配置项模型【历史说明：不可直接实现】

登录账号不属于监控核心状态。Bilibili 登录上下文仅用于关注导入：

```text
登录账号
   ↓
读取当前账号关注列表
   ↓
与本地主播 UID 集合做增量比对
   ↓
仅新增不存在的主播；已存在主播按导入策略刷新资料
```

更换登录账号不创建新的监控数据空间，也不清空已有主播、直播历史或监控状态。退出登录只删除本地认证凭证，不影响已经存在的主播监控。

所有账号相关异步请求只允许更新“关注导入临时结果”和导入任务状态；不得通过账号上下文改变核心 `Streamer`、`LiveSession`、`LiveEvent` 的归属语义。

```kotlin
配置单一来源约束：UI 和 UseCase 不得直接分别读取 `MonitorBatchPolicy`、`BiliApiSettings`、`AdvancedNetworkSettings` 等不同来源决定一次请求的参数。所有持久化配置先由统一 ConfigRepository 读取，组合成 `EffectiveAppConfig`，再在 Tick/Recovery/NotificationAggregate 创建时生成不可变 Snapshot；运行中的任务只读取对应 Snapshot。

data class AppSettings(
    val monitorMode: MonitorMode = MonitorMode.REALTIME,
    val monitorIntervalSeconds: Int = 60,
    val notifyLiveStart: Boolean = true,
    val notifyLiveEnd: Boolean = true,
    val notifyFirstSyncLive: Boolean = false,
    val notifyRoundLive: Boolean = false,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietStart: String = "23:00",
    val quietEnd: String = "07:00",
    val autoStartMonitoring: Boolean = false,
    val openRoomWithBilibiliAppFirst: Boolean = true
)
```

---

## 原规范 32：配置版本管理【历史说明：不可直接实现】

导入配置必须包含：

```json
"schemaVersion": 1
```

未来修改 JSON 时：

```text
v1 -> v2
v2 -> v3
```

由：

```kotlin
ConfigMigrator
```

负责升级。

绝对不要让新版程序直接假设所有旧文件都是最新格式。

---

## 原规范 205：直播间跳转策略升级【历史说明：不可直接实现】

用户点击：

```text
进入直播间
```

必须优先尝试拉起已安装的 Bilibili App。

推荐策略：

```text
点击
 ↓
检查 Bilibili App 是否可处理目标链接
 ↓
可以
 ↓
使用 Android Intent / Bilibili Deep Link
 ↓
拉起 Bilibili App
```

如果不能：

```text
Bilibili App 不存在
        ↓
浏览器打开 https://live.bilibili.com/{roomId}
```

## 205.1 跳转顺序

默认顺序：

```text
1. Bilibili App
2. 用户默认浏览器
3. 系统可处理该 URL 的其他应用
```

不得强行要求用户安装 Bilibili App。

## 205.2 用户设置

支持：

```text
打开直播间
● 优先使用 Bilibili App
○ 始终使用浏览器
○ 每次询问
```

默认：

```text
● 优先使用 Bilibili App
```

## 205.3 失败处理

如果 App 拉起失败：

```text
尝试 Bilibili App
 ↓
失败
 ↓
打开浏览器
```

如果 URL 本身无效：

```text
无法打开直播间
```

不得闪退。

## 205.4 统一导航接口

```kotlin
// 唯一最终定义（RoomNavigator / RoomOpenPreference / NavigationResult 均只在此处声明）
interface RoomNavigator {
    suspend fun openRoom(
        context: Context,
        roomUrl: String,
        preference: RoomOpenPreference
    ): NavigationResult
}

enum class RoomOpenPreference {
    BILIBILI_APP_FIRST,   // 默认：优先 Bilibili App
    IN_APP_FIRST,         // 优先 App 内 WebView / 详情页
    BROWSER_ONLY          // 仅系统浏览器
}

sealed interface NavigationResult {
    data object OpenedInBilibiliApp : NavigationResult
    data object OpenedInBrowser : NavigationResult
    data object OpenedInApp : NavigationResult
    data class Failed(val reason: String) : NavigationResult
}
```

跳转失败必须按 §205.3 降级：App 未安装 → 浏览器；浏览器也失败 → 提示"无法打开直播间"，**不得闪退**。通知点击同一律使用该接口，不得在 UI 层拼接 URL。

所有卡片、通知、历史页面、HTML 报告中的 App 内跳转入口，都必须尽可能复用同一导航策略。

---

## 原规范 99：设置页新增“数据可靠性”设置【历史说明：不可直接实现】

建议增加：

```text
数据可靠性

下播确认次数
○ 1
● 2
○ 3

网络异常阈值
○ 3 分钟
● 5 分钟
○ 10 分钟

异常通知
● 重要异常
● 严重异常
○ 普通异常

统计数据
● 仅确认数据
○ 包含估算数据

恢复后自动补偿检查
● 开启
```

高级模式可以允许修改阈值，但普通用户使用默认值即可。

---

# 11 健康中心、日志、诊断与安全

## 原规范 57：健康检查中心 v2【历史说明：不可直接实现】

检查项至少包括：

```text
[✓] 通知权限
[✓] 网络连接
[✓] Bilibili API
[✓] 数据库
[✓] 监控意图
[✓] 实际监控运行状态
[✓] 最后成功检查
[✓] 后台运行能力
[✓] 电池限制提醒
[!] 关注导入登录状态（仅在用户使用关注导入时需要）
```

并显示：

```text
最近成功检查：21:31:20
最近失败：21:30:58
连续失败：0
最近请求平均耗时：410ms
```

提供“一键诊断”按钮：

```text
网络 → API → 数据库 → 通知 → 调度 → 服务状态
```

---

## 原规范 60：安全与隐私增强【历史说明：不可直接实现】

敏感数据：

```text
Access Token
Refresh Token
Cookie
SESSDATA
bili_jct
```

必须：

```text
Android Keystore
+ 加密存储
+ 日志脱敏
+ 导出排除
```

禁止：

```text
Logcat 打印 token
Crash report 上报 cookie
明文 JSON 导出 cookie
截图/调试页面显示完整 token
```

同时增加：

```text
退出登录
删除本地账号凭证
删除全部本地数据
导出个人数据
```

---

## 原规范 64：日志体系【历史说明：不可直接实现】

分为：

```text
APP_EVENT
MONITOR_EVENT
API_EVENT
NOTIFICATION_EVENT
AUTH_EVENT
DATA_EVENT
```

日志必须：

```text
结构化
可筛选
可清理
默认不包含敏感凭证
```

调试模式下可以提高详细程度，但生产构建仍必须脱敏。

---

## 原规范 91：用户可见的健康状态【历史说明：不可直接实现】

首页顶部建议显示一个小型监控状态条：

```text
● 监控正常
```

异常：

```text
● 监控受限
```

严重：

```text
● 监控已停止
```

点击进入健康中心。

说明：全文统一使用第 42 节定义的 `MonitoringHealth`，本处不再重新定义枚举。不同页面只允许使用该枚举的不同文案映射，不得创建第二套健康状态枚举。

---

## 原规范 94：故障审计日志【历史说明：不可直接实现】

增加：

```text
AuditLog
```

建议记录：

```text
监控启动
监控停止
模式切换
API连续失败
创建监控盲区
恢复监控
服务异常退出
应用崩溃
数据库修复
配置恢复
通知发送失败
通知重试
```

但必须避免记录：

```text
access_token
refresh_token
SESSDATA
bili_jct
cookie
密码
```

---

## 原规范 98：用户体验原则：宁可告诉用户“不知道”，不要告诉用户错误答案【历史说明：不可直接实现】

这是可靠性模块的最高优先级原则。

当系统无法判断：

```text
显示未知
```

当系统只能估计：

```text
显示约 / 估算
```

当数据可能错误：

```text
标记异常
```

当监控出现盲区：

```text
明确告诉用户
```

不得：

```text
补一个看起来合理的时间
为了统计好看修改历史
把网络失败伪装成下播
为了避免用户担心而隐藏监控中断
```

---

## 原规范 100：“昨天发生了什么”诊断页面【历史说明：不可直接实现】

建议增加一项非常用户友好的页面：

```text
昨日监控报告
```

示例：

```text
监控开始：18:00
监控结束：08:00

正常监控：13小时22分钟
监控中断：38分钟

监控主播：86
检查次数：782

开播事件：19
关播事件：17

⚠ 发现 2 条不完整直播记录
⚠ 其中 1 条直播时长为估算值

[查看异常]
[查看直播历史]
```

用户不需要理解内部数据库，但可以知道昨晚软件是否正常工作。

---

## 原规范 206：日志系统总体设计【历史说明：不可直接实现】

本项目正式区分两类日志：

```text
A. 监控错误日志（Monitoring Error Log）
B. 软件运行错误日志（Application Error Log）
```

两者必须：

```text
数据表分开
Repository 分开
查询接口分开
UI 页面分开
保留周期可分别设置
导出方式可分别控制
```

不得把两类日志全部写进一张“大杂烩错误表”。

---

## 原规范 207：监控错误日志【历史说明：不可直接实现】

监控错误日志只记录与：

```text
Bilibili 主播监控
网络请求
直播状态检测
状态转换
监控调度
批量检测
监控恢复
```

有关的问题。

例如：

```text
API timeout
HTTP 429
HTTP 503
解析失败
某 UID 查询失败
批次请求失败
监控状态转换异常
MonitoringGap 创建
恢复检查失败
```

数据模型建议：

```kotlin
data class MonitoringErrorLog(
    val id: Long,
    val occurredAt: Long,
    val streamerId: Long?,
    val uid: Long?,
    val requestType: String?,
    val endpointId: String?,
    val errorCategory: MonitoringErrorCategory,
    val severity: LogSeverity,
    val message: String,
    val technicalDetail: String?,
    val retryCount: Int,
    val monitoringSessionId: String?,
    val resolved: Boolean,
    val createdAt: Long
)
```

---

## 原规范 208：软件运行错误日志【历史说明：不可直接实现】

软件运行错误日志记录应用本身的技术异常，例如：

```text
数据库异常
Compose 状态异常
IllegalStateException
数据映射错误
文件导出异常
配置解析异常
通知模块异常
导航异常
Worker 异常
Service 生命周期异常
未捕获异常
```

模型建议：

```kotlin
data class ApplicationErrorLog(
    val id: Long,
    val occurredAt: Long,
    val errorType: String,
    val severity: LogSeverity,
    val message: String,
    val stackTrace: String?,
    val component: String?,
    val appVersion: String,
    val osVersion: String,
    val deviceInfo: String?,
    val processState: String?,
    val createdAt: Long
)
```

注意：设备信息必须最小化收集，不保存不必要的个人数据。

---

## 原规范 209：两类日志的区别【历史说明：不可直接实现】

监控错误日志回答：

```text
“Bilibili 监控为什么失败？”
```

软件运行错误日志回答：

```text
“这个 Android 软件本身为什么出问题？”
```

例如：

```text
网络断开
→ 监控错误日志
```

```text
数据库访问抛出 Exception
→ 软件运行错误日志
```

```text
监控 API 返回 429
→ 监控错误日志
```

```text
HTML 导出模块 NullPointerException
→ 软件运行错误日志
```

一个问题可以同时产生两类日志，但不能互相替代。

---

## 原规范 210：日志严重级别【历史说明：不可直接实现】

统一定义：

```kotlin
enum class LogSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    CRITICAL
}
```

推荐行为：

```text
DEBUG
短期保存，不提醒用户

INFO
用于运行审计，不提醒用户

WARNING
必要时显示状态提示

ERROR
记录并根据用户影响决定是否通知

CRITICAL
记录、通知、进入诊断中心
```

---

## 原规范 211：日志必须结构化【历史说明：不可直接实现】

禁止只保存：

```text
“网络请求失败”
```

推荐：

```json
{
  "category": "API_TIMEOUT",
  "severity": "ERROR",
  "uid": 123456,
  "requestType": "LIVE_STATUS",
  "retryCount": 2,
  "occurredAt": 1789200000000
}
```

这样开发者可以快速筛选、统计和定位问题。

---

## 原规范 212：日志脱敏【历史说明：不可直接实现】

无论哪一类日志，都严禁写入：

```text
access_token
refresh_token
SESSDATA
bili_jct
Cookie
Authorization Header
完整登录响应
```

URL 如果可能包含敏感查询参数，必须经过脱敏：

```text
https://example.com/api?access_token=***
```

而不是记录原值。

---

## 原规范 213：日志保留策略【历史说明：不可直接实现】

两类日志分别设置生命周期。

默认建议：

```text
监控错误日志：90天
软件运行错误日志：90天
DEBUG：7天
```

高级设置允许调整，例如：

```text
7天
30天
90天
180天
365天
永久
```

但不得无上限累积。

---

## 原规范 214：日志数量限制【历史说明：不可直接实现】

除了按时间清理，还需要按最大记录数保护数据库。

例如：

```text
监控错误日志最大 10000 条
软件运行错误日志最大 5000 条
```

超过后：

```text
优先删除 DEBUG/INFO
↓
再删除已解决的 WARNING
↓
ERROR / CRITICAL 尽量保留
```

如果发生自动清理，要写入维护日志。

---

## 原规范 215：用户侧日志中心【历史说明：不可直接实现】

设置页面新增：

```text
诊断与日志
```

进入后：

```text
监控错误日志
软件运行错误日志
```

必须明确显示两类入口。

界面示例：

```text
诊断与日志

监控错误日志      12条
软件运行错误      2条

[查看监控错误]
[查看软件错误]
```

---

## 原规范 216：监控错误日志页面【历史说明：不可直接实现】

显示：

```text
时间
主播
错误类型
严重程度
是否已恢复
```

例如：

```text
21:31:04  XXX   API超时    WARNING
21:31:35  XXX   429       ERROR
21:33:01  XXX   已恢复    INFO
```

支持筛选：

```text
时间
主播
错误等级
错误类型
是否已恢复
```

---

## 原规范 217：软件运行错误日志页面【历史说明：不可直接实现】

技术信息可以更加详细。

例如：

```text
时间：21:32:08
模块：HtmlExport
类型：IOException
等级：ERROR

消息：无法创建临时文件

详细堆栈：
...
```

普通用户默认看到简化信息：

```text
HTML 导出失败
```

开发者模式可以查看详细技术信息。

---

## 原规范 218：开发者诊断模式【历史说明：不可直接实现】

设置中增加：

```text
开发者模式
```

开启后可以显示：

```text
监控任务 ID
请求耗时
批次大小
API 请求状态
状态转换
数据库事务结果
Notification Outbox 状态
Worker/Service 生命周期
详细 StackTrace
```

默认关闭。

开启开发者模式不会关闭安全脱敏。

---

## 原规范 219：一键导出诊断包【历史说明：不可直接实现】

这是非常值得增加的稳定性功能。

开发者排查问题时，用户可以：

```text
诊断与日志
    ↓
导出诊断包
```

生成：

```text
BiliMonitor_Diagnostics_2026-09-12.zip
```

内容建议：

```text
app_info.json
monitoring_logs.json
application_errors.json
health_snapshot.json
settings_sanitized.json
migration_info.json
```

严禁包含登录凭证。

默认也不导出完整主播历史，以避免诊断包过大；可以让用户勾选“包含主播基本信息”。

---

## 原规范 88：“监控恢复”用户提醒【历史说明：不可直接实现】

用户不需要看到底层技术细节。

推荐提示：

```text
监控已恢复

检测到约 23 分钟监控中断。
中断期间部分主播的状态无法确认。
已重新检查 57 位主播。

[查看详情]
```

如果没有影响：

```text
监控已恢复
已重新连接 Bilibili，监控正在正常运行。
```

---

## 原规范 182：P1-3 日志生命周期管理【历史说明：不可直接实现】

日志等级：

```text
DEBUG
INFO
WARNING
ERROR
AUDIT
```

保留策略：

```text
DEBUG：短期
INFO：有限保留
WARNING：中期
ERROR：长期
AUDIT：长期
```

数据库或文件日志达到大小阈值后自动轮转。

禁止无限增长。

日志必须脱敏，不得出现：

```text
access_token
refresh_token
SESSDATA
bili_jct
Cookie
```

---

## 原规范 186：P1-7 批量操作幂等性【历史说明：不可直接实现】

以下操作必须支持重复执行而不会产生重复数据：

```text
批量加入标签
批量移动分组
批量开启监控
批量关闭监控
批量导入主播
```

例如：

```text
主播A已有“游戏”标签
再次加入“游戏”
↓
保持一份关系
```

不能出现两条相同关系。

---

## 原规范 187：P1-8 异常恢复报告【历史说明：不可直接实现】

恢复成功后可以生成：

```text
监控已恢复

本次中断：23分钟
受影响主播：57
恢复检查成功：56
仍存在异常：1
通知事件恢复：4
```

用户点击“查看详情”后进入诊断页面。

---

## 原规范 188：P1-9 用户可见的严重异常等级【历史说明：不可直接实现】

定义：

```text
INFO
WARNING
ERROR
CRITICAL
```

推荐用户通知策略：

```text
单次网络错误
    -> 不提醒

持续异常
    -> WARNING

监控服务停止
    -> ERROR

核心数据库/通知系统无法工作
    -> CRITICAL
```

同类异常必须有冷却时间，避免通知轰炸。

---



## 194.5 诊断历史生命周期

`LiveSession` 等核心业务历史与 `AuditLog / HealthEvent / CrashRecord / NotificationHistory` 等诊断历史采用不同生命周期。诊断数据必须有明确的时间、数量或磁盘安全阈值，避免长期运行无限增长。

诊断清理不得删除仍被未完成恢复、审计链或当前 ExportSnapshot 引用的记录；清理必须经过统一维护协调器。

## 188.5 OverallHealth 决策矩阵

各子系统先独立计算健康状态，再由统一 `OverallHealthEvaluator` 汇总；页面不得自行拼接健康结论。建议：

```text
DatabaseHealth = FAILED / CRITICAL
→ OverallHealth = ERROR

MonitoringHealth = STOPPED / SYSTEM_RESTRICTED 且 desiredRunning = true
→ OverallHealth = USER_ACTION_REQUIRED / SYSTEM_RESTRICTED

NotificationHealth = FAILED，但监控仍正常
→ OverallHealth = DEGRADED；不得伪装成监控停止

NetworkHealth = DEGRADED，但仍能获得部分可靠观察
→ OverallHealth = DEGRADED

核心子系统正常
→ OverallHealth = HEALTHY
```

首页、设置页、诊断页只允许映射统一的 OverallHealth。

## 原规范 189：P1-10 数据质量中心【历史说明：不可直接实现】

新增页面：

```text
数据质量
```

展示：

```text
完整直播记录：982
部分确认：23
待修正：6
冲突记录：3
无效记录：1

数据完整度：97.1%
```

支持点击进入对应问题列表。

---

## 原规范 194：P1-15 设置变更的串行化【历史说明：不可直接实现】

以下设置发生变化时：

```text
监控模式
监控间隔
通知开关
免打扰
主播独立策略
```

全部通过：

```text
MonitoringController
SettingsCoordinator
```

串行处理。

不得因为用户连续快速点击而生成多个并发监控实例。

---

# 12 UI、ViewModel、DI 与页面实现

## 原规范 27：首页 ViewModel【历史说明：不可直接实现】

建议：

```kotlin
class HomeViewModel(
    private val streamers: StreamerRepository,
    private val refreshAll: RefreshAllStreamersUseCase,
    private val monitorController: MonitorController
) : ViewModel() {

    val uiState: StateFlow<HomeUiState>

    fun onSearchChanged(keyword: String)
    fun onFilterChanged(filter: StreamerFilter)
    fun refresh()
    fun toggleMonitoring(streamerId: Long, enabled: Boolean)
    fun openStreamer(streamerId: Long)
}
```

UI State：

```kotlin
data class HomeUiState(
    val loading: Boolean = false,
    val streamers: List<Streamer> = emptyList(),
    val filter: StreamerFilter = StreamerFilter(),
    val monitoringRunning: Boolean = false,
    val lastRefreshAt: Long? = null,
    val errorMessage: String? = null
)
```

---

## 原规范 28：Compose UI 设计原则【历史说明：不可直接实现】

每个 UI 组件尽量保持纯函数式。

例如：

```kotlin
@Composable
fun StreamerCard(
    streamer: Streamer,
    onClick: () -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
    onOpenRoom: () -> Unit
)
```

不要在 `StreamerCard()` 里直接：

```text
访问数据库
访问 Bilibili API
发通知
启动 Service
```

这些都交给 ViewModel / UseCase。

---

## 原规范 29：DI 模块建议【历史说明：不可直接实现】

```text
AppModule
DatabaseModule
NetworkModule
RepositoryModule
DispatcherModule
NotificationModule
```

例如：

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = ...

    @Provides
    @Singleton
    fun provideBiliLiveApi(
        client: OkHttpClient
    ): BiliLiveApi = ...
}
```

---

# 13 测试、验收、开发顺序与 AI Coding Agent 协议

## 原规范 33：测试方案【历史说明：不可直接实现】

## 33.1 单元测试

必须增加以下 P0/P1 测试组：

```text
ERROR 不进入 confirmedLiveStatus
UNKNOWN -> LIVE 创建 PROVISIONAL OPEN Session
软删除 ACTIVE OPEN Session → ABANDONED 且允许创建后续 ACTIVE Session
PendingTransition 并发 CAS：两个 OFFLINE 观察不得丢失计数
旧 observationSequence / generation / fencingToken 不得覆盖新状态
ReliableMonitorInterval 异常后失效、恢复 LIVE 后创建新区间
恢复时已 OFFLINE → 不产生“正在直播”与关播通知
恢复时仍 LIVE + 后续连续 SUCCESS → 可以产生关播通知
notificationId 碰撞重新分配且旧事件不被覆盖
DELIVERY_UNKNOWN lease 超时 → 可重试同一 notificationId
权限恢复 → 未过期 FAILED 可重新 RETRY_WAIT
Aggregate READY ↔ Outbox 一一对应，事务失败不可留下半成品
stableId 相同 / uid 冲突 / 双冲突三类恢复矩阵
ROUND 不关闭 Session / 不发送关播
Global Gap 与 GapStreamer 独立关闭
DataStore 与 Room 配置版本不一致时拒绝宣称原子快照
```


重点测试：

### 状态转换

```text
OFFLINE -> LIVE = START
LIVE -> OFFLINE = END
LIVE -> LIVE = NONE
ERROR / UNKNOWN / PARTIAL -> 旧确认状态保持不变
NETWORK_ERROR / TIMEOUT / API_ERROR -> 不产生 OFFLINE
```

### 搜索

```text
完全匹配
前缀匹配
包含匹配
大小写
空字符串
特殊字符
```

### 配置导入

```text
正常 JSON
缺字段
多字段
旧版本 schema
错误 JSON
重复主播
```

## 33.2 Repository 测试

- API 成功
- API 超时
- API 返回空
- API 部分主播失败
- 数据库已有主播
- UID 重复

## 33.3 Service 测试

- 服务启动
- 服务停止
- 检查间隔变化
- 网络断开
- 网络恢复
- 通知去重

## 33.4 真机测试

重点覆盖：

```text
Android 12
Android 13
Android 14
Android 15
Android 16（可用时）
```

以及至少：

```text
原生 Android
小米/HyperOS
华为
OPPO/ColorOS
vivo
三星
```

后台限制在不同 ROM 上差异很大，因此“后台长时间运行”必须以真实设备测试为准。

---

## 原规范 34：开发顺序与最终功能边界【历史说明：不可直接实现】

开发顺序服从稳定性依赖，不允许为了提前展示功能而绕过最终规范。

## 第一阶段：核心监控闭环

1. Room 数据库与 Migration 基础
2. 手动添加主播
3. Bilibili 状态查询与 DTO/Mapper
4. 状态确认与误报保护
5. LiveSession
6. LiveEvent
7. NotificationOutbox
8. 通知 Dispatcher
9. 手动刷新

## 第二阶段：主播管理

1. 标签/分组
2. 搜索/筛选/排序
3. 批量操作
4. 主播详情
5. 主播级监控/通知策略

## 第三阶段：后台可靠运行

1. MonitoringController / Scheduler
2. WorkManager 省电模式
3. Foreground Service 实时模式
4. Lease / fencing
5. 网络异常与 Recovery
6. StartupRecovery
7. Health / Diagnostics

## 第四阶段：历史与数据能力

这一阶段表示开发依赖顺序，不表示这些能力属于未来版本。它们全部属于当前 v2.0 功能基线；只有在核心监控、状态确认、Outbox 和后台可靠运行基础完成后，才按依赖关系实现。

1. LiveSession 历史
2. 人工修正
3. 统计与数据质量
4. HTML 导出
5. 直播历史备份配置文件导出/恢复
6. 历史容量管理与自动清理

## 第五阶段：可选账号工具

1. 官方授权
2. 关注列表导入
3. Token 刷新
4. 登录失效处理

登录和关注导入不是核心监控前置条件。

## 原规范 35：AI 开发时必须遵守的规则【历史说明：不可直接实现】

将下面内容视为 AI Coding Agent 的最高优先级工程规则：

```text
1. 使用 Kotlin，不使用 Java 作为主要业务代码。
2. 使用 Jetpack Compose 构建 UI。
3. 使用 MVVM / Repository 分层。
4. 不允许 UI 直接请求网络。
5. 不允许 UI 直接访问 Room DAO。
6. 不允许网络 DTO 直接进入 UI。
7. 必须存在 Domain Model。
8. 所有后台检查都必须经过 MonitoringEngine。
9. 所有开播/关播通知必须基于状态转换。
10. 不允许每次轮询都发送通知。
11. 不允许把网络错误当作“已下播”。
12. 不允许明文保存账号密码或 Cookie。
13. 不允许在 Logcat 打印 access_token、refresh_token、SESSDATA、bili_jct。
14. 不允许将 Bilibili URL 散落在项目各处。
15. 所有 API 均通过接口抽象。
16. 所有网络请求必须设置超时。
17. 所有网络请求必须允许取消。
18. 批量请求优先于一个主播一个请求。
19. 不允许使用无限重试。
20. 不得声称 Android 可以 100% 保证后台永不被杀。
21. 必须考虑 Android 12+ 的后台启动限制。
22. 必须考虑 Android 14+ 的 Foreground Service 类型限制。
23. 必须考虑 Android 13+ 通知权限。
24. 必须考虑 Android 15+ 的前台服务长期运行变化。
25. 修改数据库字段时必须升级 Room migration。
26. 配置 JSON 必须带 schemaVersion。普通应用配置导入仅处理配置域，不触发 LiveSession/Statistics 业务数据恢复、历史容量清理或完整备份恢复流程。
27. 配置导入失败时必须可回滚。
28. 所有用户输入都必须经过校验。
29. 所有 UI 文案集中管理，避免散落字符串。
30. 所有关键 UseCase 都必须配单元测试。
31. 每次生成代码时优先修改少量文件，不要无理由重构整个项目。
32. 如果 Bilibili 接口无法确认，不允许臆造字段；应在 adapter 层标记 TODO 或使用可配置映射。
33. `confirmedLiveStatus` 只允许 UNKNOWN/OFFLINE/LIVE/ROUND；禁止把 ERROR 写入业务确认状态。
34. 每次观察必须形成逐主播 `ObservationResult`，批量接口整体 responseValidity 只能用于诊断，不能覆盖单 UID 结果。
35. 所有状态确认计数、PendingTransition、ReliableMonitorInterval 都必须持久化或在同一数据库事务中更新；禁止仅存在内存中。
36. 所有状态/Session/Event/Outbox 关键写入必须携带 observationSequence + monitorGeneration + fencingToken，并使用 DAO CAS 条件。
37. 首次 UNKNOWN -> LIVE 必须创建 OPEN LiveSession，startTime 只能表示软件确认时间且为 PROVISIONAL；不得伪造真实开播时间。
38. 软删除主播不得伪造下播；OPEN Session 必须进入 ABANDONED 隔离状态，不能永久阻塞后续 ACTIVE Session。
39. 关播通知资格只能由当前 ReliableMonitorInterval + EndNotificationEligibilityEvaluator 判定。
40. 异常恢复时若主播仍 LIVE，用户可见通知统一为“正在直播”；若恢复时已 OFFLINE，不发送“正在直播”也不发送关播。
41. `notification_id_registry` 必须持久化 eventKey → notificationId；不得直接使用 hashCode 作为最终通知 ID。
42. `DELIVERY_UNKNOWN`、FAILED、EXPIRED 的恢复规则必须显式实现，禁止无限重试或永久卡死。
43. NotificationAggregate、relation rows 和对应批量 Outbox 必须在同一事务创建/冻结；Aggregate 没有对应 Outbox 时不得进入可发送状态。
44. stableId/uid 冲突必须进入 RestoreConflictResolver，不得让 UNIQUE constraint exception 代替业务决策。
45. ROUND 默认不表示下播，不关闭 LiveSession，不发送关播；如改变语义必须显式配置。
46. MaintenanceMode 与 MonitoringLease 必须使用数据库级原子互斥，禁止只依赖内存锁。
47. 所有影响 MonitoringTick 的配置必须有单一 configVersion 来源；同一 Tick 不得跨版本读取配置。
```

---

## 原规范 36：推荐的 AI 开发提示词【历史说明：不可直接实现】

将下面这段直接复制给代码 AI：

```text
你现在负责开发一个 Android 12+ 应用，项目名称为“哔哩哔哩主播监控软件”。

请严格按照《哔哩哔哩主播监控软件_项目代码设计文档》实现。

技术栈：
- Kotlin
- Jetpack Compose
- Material 3
- MVVM
- Room
- DataStore
- Retrofit + OkHttp
- Kotlin Coroutines + Flow
- Hilt
- WorkManager
- Foreground Service

核心功能：
1. 监控 Bilibili 主播直播状态。
2. 开播发送通知。
3. 关播发送通知。
4. 可选支持 Bilibili 授权，并在用户主动执行时导入关注主播；核心监控不依赖登录。
5. 支持普通应用配置导入导出；直播历史使用独立 BACKUP_CONFIG 进行正式备份/恢复。
6. 支持 LiveSession 直播历史、人工修正、统计、数据质量、HTML 报告、历史容量管理和自动清理。
7. 每个主播使用卡片展示头像、昵称、UID、直播标题、直播分区、封面、监控状态、当前直播状态。
7. 支持点击卡片或通知进入直播间。
8. 支持标签。
9. 支持分组。
11. 支持模糊搜索。
12. 支持按标签、分组、状态筛选。
13. 支持省电监控和实时监控。
14. 支持后台恢复、健康检查和错误记录。

代码要求：
- UI 不直接访问 API/DAO。
- API 必须通过 Repository/DataSource 隔离。
- 开播/关播必须使用状态转换判断。
- ERROR 状态不能直接视为 OFFLINE。
- 首次同步到 LIVE 默认不发送开播通知。
- 必须避免通知重复发送。
- 不允许明文保存任何敏感登录凭证。
- 所有网络请求都有超时和错误处理。
- 不允许无限重试。
- 不允许用死循环模拟永久后台任务。
- 遵守 Android 12+ 后台限制、Android 13+ 通知权限、Android 14+ Foreground Service 类型要求以及 Android 15+ 长时间前台服务限制。

开发方式：
第一步只创建 Gradle 配置、Application、MainActivity、Theme、基础目录、Room/DataStore/Hilt/Network 的骨架，并确保项目能编译运行。
不要在第一步实现所有业务。

第二步再实现 Room Entity、DAO、Repository、Domain Model。

第三步实现 Bilibili API DataSource 和 DTO -> Domain Mapper。

第四步实现 MonitoringEngine 和状态转换检测。

第五步实现通知系统。

第六步实现 Compose 首页、主播卡片、搜索和筛选。

第七步实现后台监控服务与 WorkManager。

第八步实现登录、关注导入和配置导入导出。

每完成一个阶段，都先给出：
1. 修改了哪些文件。
2. 每个文件的作用。
3. 完整代码。
4. 需要我执行的 Gradle/Android Studio 操作。
5. 如何验证这一阶段正确。

不要一次输出几十个互相依赖但未经验证的文件。
```

---

## 原规范 66：可靠性指标（工程验收标准）【历史说明：不可直接实现】

建议以指标验收，而不是只看“能不能跑”。

```text
状态检测：错误不会直接转换成 OFFLINE
通知：同一 eventKey 不得发送两次
数据库：状态更新与事件创建保持事务一致
故障：单主播/单批次失败不得使全局监控停止
恢复：网络恢复后可继续工作
导入：失败可回滚
升级：Room Migration 不丢用户数据
权限：通知权限拒绝时用户能看到明确状态
```

可以进一步记录：

```text
monitor_success_rate
api_error_rate
notification_success_rate
average_check_latency
consecutive_failure_max
```

---

## 原规范 67：测试矩阵 v2【历史说明：不可直接实现】

## 单元测试

```text
状态转换
状态确认
通知去重
免打扰
搜索
筛选
排序
配置迁移
数据库映射
```

## 集成测试

```text
API → Mapper → Repository → Room
状态变化 → LiveSession → Outbox
Outbox → Notification
```

## 故障测试

```text
无网络
DNS失败
超时
429
500
返回空数据
字段变化
部分主播失败
数据库异常
通知权限关闭
```

## 生命周期测试

```text
启动 App
进入后台
返回前台
停止 Service
网络切换
手机重启
系统升级
应用升级
```

## Android 版本测试

至少：

```text
Android 12
Android 13
Android 14
Android 15
Android 16
```

并在常见厂商 ROM 上验证后台策略。

---

## 原规范 101：可靠性测试新增要求【历史说明：不可直接实现】

必须新增以下单元测试：

```text
NETWORK ERROR 不改变 LIVE -> OFFLINE
UNKNOWN 不产生下播通知
连续 OFFLINE 满足阈值后才能结束 Session
Session 缺少 endTime 时不会生成确认时长
MonitoringGap 创建与关闭正确
崩溃恢复后不会复制 LiveSession
通知 Outbox PROCESSING 超时可以恢复
统计缓存损坏后能够从 LiveSession 重建
INVALID Session 不进入统计
PROVISIONAL Session 根据设置决定是否进入统计
```

故障注入测试：

```text
检查过程中断网
检查过程中杀死进程
数据库事务中断
通知发送失败
网络恢复
Bilibili 返回 429
Bilibili 返回 500
恢复过程中再次崩溃
```

---

## 原规范 105：AI Coding Agent 新增开发规则：可靠性优先【历史说明：不可直接实现】

```text
1. 任何网络失败不得自动映射为 OFFLINE。
2. 任何进程异常退出不得自动生成精确 endTime。
3. 不允许用当前时间填补未知时间段而不标记数据可信度。
4. 所有 LiveSession 必须支持未完成状态。
5. 所有统计必须拥有可追溯的源数据。
6. StatisticsCache 必须可以删除后重建。
7. MonitoringGap 必须可创建、关闭、查询。
8. 恢复逻辑必须独立于 UI。
9. StartupRecovery 不得直接假定上次状态是真实状态。
10. ConsistencyChecker 只能做确定性的自动修复。
11. 无法确定的数据必须标记 UNKNOWN/PROVISIONAL，而不是猜测。
12. 异常通知必须去重并有冷却期。
13. 用户界面必须明确区分 OFFLINE 与 UNKNOWN。
14. 主播卡片必须显示必要的数据新鲜度。
15. 任何修改 LiveSession 的 UseCase 都必须有单元测试。
16. 任何状态恢复逻辑都必须有崩溃恢复测试。
17. 修改数据库结构必须同步更新 Room Migration 和备份/导入逻辑。
18. 不得删除历史异常记录来掩盖问题。
19. 所有自动修复都要写入 AuditLog。
20. 生成代码时优先保证数据真实性，再考虑界面上的“好看”和统计完整度。
```

---

## 原规范 155：新增 UseCase【历史说明：不可直接实现】

建议新增：

```text
CreateManualLiveSessionUseCase
EditLiveSessionUseCase
CalculateLiveSessionTimesUseCase
ValidateLiveSessionUseCase
CorrectLiveSessionUseCase
UndoLiveSessionCorrectionUseCase
MergeLiveSessionsUseCase
SplitLiveSessionUseCase
RebuildStatisticsUseCase
ValidateStatisticsUseCase
GenerateHtmlReportUseCase
GenerateJsonReportUseCase
ExportLiveHistoryUseCase
GetDataQualityReportUseCase
```

---

## 原规范 156：新增 Repository 接口【历史说明：不可直接实现】

```kotlin
interface LiveHistoryRepository {
    // 本机 UI/编辑操作可以使用 sessionId；跨设备/备份/恢复必须使用 stableId。
    suspend fun createManualSession(input: ManualLiveSessionInput): Long
    suspend fun updateSession(session: LiveSession): Result<Unit>
    suspend fun getSession(sessionId: Long): LiveSession?
    suspend fun getSessionByStableId(stableId: String): LiveSession?
    suspend fun getSessions(filter: LiveHistoryFilter): List<LiveSession>
    suspend fun saveCorrection(correction: LiveSessionCorrection): Result<Unit>
    suspend fun mergeSessions(sessionIds: List<Long>): Result<Long>
    suspend fun splitSession(sessionId: Long, splitAt: Long): Result<List<Long>>
}

// 导出、备份、恢复、审计关联和跨设备迁移必须使用 LiveSession.stableId，不能使用本地 sessionId。
```

统计：

```kotlin
interface StatisticsRepository {
    suspend fun calculate(query: StatisticsQuery): StatisticsResult
    suspend fun rebuild(query: StatisticsQuery): StatisticsResult
    suspend fun getDataQualityReport(): DataQualityReport
}

恢复与通知幂等专用接口（唯一签名，本节只声明一次）：

```
```kotlin
interface RestoreConflictResolver {
    suspend fun detectConflict(backupStreamer: BackupStreamer, localStreamer: Streamer?): RestoreConflict?
    suspend fun resolve(conflict: RestoreConflict, decision: RestoreConflictDecision): Result<Unit>
}

interface NotificationIdRegistry {
    /**
     * 事务内原子占用：eventKey 已存在则复用；不存在则分配候选 Int 并做 UNIQUE 碰撞检测后写入。
     * 重复调用必须返回同一 notificationId（见 0.6.12）。
     */
    suspend fun getOrCreate(eventKey: String): Int
}
```

这些接口不是“可选增强”；它们直接承载恢复与通知幂等性约束。

---

## 原规范 157：AI Coding Agent 新增规则：直播历史【历史说明：不可直接实现】

```text
1. LiveSession 必须允许 startTime/endTime/duration 任意一个字段为空，但最终可用记录至少必须由其中两个时间信息推导完整结果。
2. 用户输入任意两个时间字段后，第三个必须自动计算。
3. 若用户同时输入三个字段且三者矛盾，禁止保存。
5. 跨午夜必须使用完整日期计算。
6. 自动监控数据不得覆盖用户手工确认的数据。
7. 手工修正必须记录审计日志。
8. 用户修正不得删除原始监控观察。
9. 统计必须从 LiveSession 计算，不得直接手工修改统计总数。对于缺失一个时间字段但可由另外两个字段唯一推导的记录，先在 LiveSession 层完成推导；对于仍无法形成完整时间结果的记录，不能参与依赖完整时间区间的时长/场次指标。每项统计必须明确是否纳入 `CONFIRMED`、`PROVISIONAL` 数据。
10. 统计缓存必须可以删除后重新构建。
11. 异常记录必须可视化标记。
12. 用户必须能够手工修正异常历史。
13. 修正后必须自动重新计算相关统计。
14. 合并、拆分、恢复等操作必须写入审计日志。
15. 所有统计数字必须能够追溯到源 LiveSession。
16. HTML 导出不得包含任何账号凭证或 Cookie。
17. HTML 必须支持离线打开。
18. 导出的统计必须显示数据完整度和统计口径。
19. 不得为了生成“完整”的统计而猜测未知时间。
20. 用户确认的数据优先于自动监控数据。
```

---

## 原规范 158：新增验收测试【历史说明：不可直接实现】

## 手工时间计算

```text
开播 + 下播
=> 自动计算时长

开播 + 时长
=> 自动计算下播

下播 + 时长
=> 自动计算开播
```

## 矛盾检测

```text
开播 19:00
下播 22:00
时长 2小时
=> 拒绝保存
```

## 自动覆盖保护

```text
自动：19:03
用户：19:00
再次自动刷新：19:05
=> 最终仍为19:00
```

## 数据异常

```text
LIVE
→ 网络异常
→ 应用崩溃
→ 恢复
=> Session 不得凭空获得准确下播时间
```

## 统计重算

```text
Session 2小时
→ 修改为3小时
=> 统计自动 +1小时
```

## 导出

```text
HTML
=> 浏览器可直接打开
=> 无网络也能查看图表
=> 无 Token/Cookie
```

---

## 原规范 195：P0/P1 验收总表【历史说明：不可直接实现】

## P0

```text
[ ] 状态抖动保护
[ ] RefreshCoordinator
[ ] 监控心跳
[ ] desired/actual/health 三态
[ ] 数据库完整性检查
[ ] LiveSession 来源记录
[ ] 人工修正锁定
[ ] 修正可撤销
[ ] 统计可重建
[ ] Session 时间冲突检测
[ ] ExportSnapshot
[ ] 原子导出
[ ] 通知过期
[ ] 主播删除保留历史
[ ] RecoverySession
[ ] 主播资料空值保护
[ ] 登录与监控解耦（登录仅用于可选关注导入）
[ ] RecoveryCheck 与普通 MonitoringTick 互斥
[ ] 旧刷新响应不能覆盖新刷新结果
[ ] API 部分响应的缺失 UID 不得被视为 OFFLINE
[ ] Outbox PROCESSING 具有租约并可恢复
[ ] 聚合窗口固定锚点且进入窗口后先等待聚合决定
[ ] 同一事件使用稳定 notificationId 防止重复系统通知
```

## P1

```text
[ ] API 熔断器
[ ] 监控可靠性评分
[ ] 日志生命周期
[ ] 通知冷却
[ ] 导入校验和
[ ] MigrationVerifier
[ ] 批量操作幂等
[ ] 异常恢复报告
[ ] 严重异常分级提醒
[ ] 数据质量中心
[ ] 统计缓存失效
[ ] 历史导入隔离
[ ] 禁止猜测式自动修复
[ ] 全局一致性检查
[ ] 设置变更串行化
```

---

## 原规范 196：P0/P1 推荐实现顺序【历史说明：不可直接实现】

```text
第一优先级
数据模型与 Room
↓
LiveSession
↓
DataConfidence
↓
Revision / Lock

第二优先级
StateDebouncer
↓
MonitoringHeartbeat
↓
MonitoringController
↓
RefreshCoordinator

第三优先级
Notification Outbox
↓
NotificationCooldown
↓
Notification Expiration

第四优先级
ConsistencyChecker
↓
RecoverySession
↓
DatabaseIntegrityChecker

第五优先级
StatisticsCalculator
↓
Cache Invalidation
↓
Data Quality Center

第六优先级
ExportSnapshot
↓
Atomic Export
↓
ImportBuffer
↓
Checksum
```

---

## 原规范 197：AI Coding Agent P0/P1 强制规则【历史说明：不可直接实现】

向 AI Coding Agent 提供以下硬性要求：

```text
1. 任何状态变化必须经过 StateDebouncer / StateTransitionDetector。
2. 网络错误永远不能直接映射成 OFFLINE。
3. 用户手动确认的 LiveSession 字段不得被自动刷新覆盖。
4. 所有手工修改必须产生 Revision。
5. 所有关键写入必须使用数据库事务。
6. 所有统计必须可由 LiveSession 重新计算。
7. 不允许直接修改统计总数来“修复”统计错误。
8. 所有导出都必须基于冻结快照。
9. 导出必须写临时文件，成功后原子提交。
10. 所有后台任务必须经过统一 Coordinator。
11. 禁止创建多个并行监控循环。
12. 同类通知必须支持去重、冷却、过期。
13. API 连续失败必须支持退避和熔断。
14. 缺失远程字段不得无条件覆盖已有有效字段。
15. 删除主播默认不删除历史。
16. 数据迁移后必须验证核心记录数量和关系完整性。
17. 批量操作必须幂等。
18. 自动修复不得猜测用户未提供的真实时间。
19. 所有严重异常都必须有用户可理解的提示。
20. 每加入一个新的数据字段，都必须考虑来源、可信度、迁移和导入导出。
```

---

## 原规范 227：AI Coding Agent：高级设置规则【历史说明：不可直接实现】

```text
1. 所有高级参数必须有默认值。
2. 所有高级参数必须有最小值和最大值。
3. 不允许用户配置绕过安全机制。
4. 检测间隔不得低于系统安全下限。
5. 修改检测间隔必须通过 MonitoringController。
6. 禁止因配置改变而创建多个监控循环。
7. Endpoint 必须集中管理。
8. Endpoint 配置不得允许执行任意代码。
9. 非 HTTPS Endpoint 默认拒绝。
10. 高级设置修改必须写入 AuditLog。
11. 恢复默认设置不得删除业务数据。
```

---

## 原规范 228：AI Coding Agent：日志系统规则【历史说明：不可直接实现】

```text
1. 监控错误日志与软件运行错误日志必须分离。
2. 两类日志必须有独立 Entity、DAO、Repository。
3. 不得把 StackTrace 直接暴露给普通用户界面。
4. 所有日志必须脱敏。
5. 不得记录 Token、Cookie、Authorization Header。
6. 日志必须具备 severity。
7. 关键日志必须具备 timestamp。
8. 监控任务日志应具备 correlationId。
9. 连续重复错误必须支持聚合。
10. 日志必须具备生命周期清理机制。
11. 日志导出不得进入普通配置导出。
12. 诊断包不得包含认证凭证。
13. 自动清理日志必须可追踪。
14. CRITICAL 错误必须进入健康中心。
15. 软件崩溃后必须执行 StartupRecovery。
```

---

## 原规范 229：AI Coding Agent：直播间跳转规则【历史说明：不可直接实现】

```text
1. 通知和卡片必须使用统一 RoomNavigator。
2. 默认优先尝试 Bilibili App。
3. Bilibili App 不可用时回退浏览器。
4. 跳转失败不得导致 App 崩溃。
5. 不允许硬编码某个设备上的包名作为唯一判断依据。
6. 必须通过 Android PackageManager / Intent 能力判断目标应用可用性。
7. 用户可以选择优先 App、浏览器或每次询问。
8. 所有跳转失败写入软件运行错误日志。
9. 跳转失败不应改变直播历史和监控状态。
```

---

## 原规范 230：稳定性新增验收测试【历史说明：不可直接实现】

必须加入：

```text
高级设置
├── 过小检测间隔 → 被拒绝
├── 超大检测间隔 → 被限制/提醒
├── Endpoint 非 HTTPS → 拒绝
├── 恢复默认 → 正常
└── 修改间隔 → 旧任务被取消
```

跳转：

```text
Bilibili App 已安装 → 拉起 App
Bilibili App 未安装 → 浏览器
目标链接非法 → 不崩溃
用户选择浏览器 → 浏览器
```

日志：

```text
API timeout → 监控错误日志
数据库异常 → 软件运行错误日志
连续同错 → 聚合
日志过期 → 自动清理
日志导出 → 不含 Token
```

崩溃恢复：

```text
模拟异常退出
 ↓
重新启动
 ↓
发现 Crash
 ↓
创建错误日志
 ↓
创建 MonitoringGap
 ↓
恢复检查
 ↓
监控重新工作
```

---

## 原规范 250：深层稳定性验收补充【历史说明：不可直接实现】

以下测试必须纳入最终验收：

```text
旧版本恢复高版本备份 → 明确提示部分恢复
高版本恢复旧版本备份 → 使用安全默认值恢复
备份范围内已删除主播 → 按备份删除状态恢复
增量恢复范围外数据 → 完全不变
达到主播容量 95% → 对应主播显示容量预警
达到容量上限 → 只清理最早 CLOSED Session
容量清理遇到 OPEN Session → 不删除 OPEN
导出跳跃日期 → 文件名仍使用实际最早/最晚直播日期
导出无记录 → 不伪造日期、不生成成功文件
关注导入 → 不覆盖用户标签/分组/备注/监控策略
Export 与 Cleanup 并发 → 不产生混合快照
Migration 与 Monitoring 并发 → 旧 Engine 无法写入
SQLite 事务提交失败 → 不显示业务成功
旧 Outbox Worker 失去 Lease → 无法发送
旧监控实例失去 fencing → 无法提交状态转换
跨月 LiveSession → 次数只归一个周期，时长按区间切片
统计跨时区 → 使用明确统计时区，不修改历史时间戳
两个编辑页面同时保存 → 旧版本提交失败，不覆盖新版本
全局 Gap 但部分主播正常 → 只有受影响主播进入 Gap 关系
HTTP 200 但 UID/关键字段异常 → 不标记为 SUCCESS
导出期间自动清理 → 不破坏已建立的 ExportSnapshot
```

## 原规范 37：参考资料【历史说明：不可直接实现】

本项目设计时应持续以 Android 官方文档为准，尤其关注后台服务和通知权限，因为这些规则会随 Android 版本变化。

- Android 12 后台启动 Foreground Service 限制：
  https://developer.android.com/about/versions/12/behavior-changes-12

- Foreground Service 启动规则：
  https://developer.android.com/develop/background-work/services/fgs/launch

- Foreground Service 类型：
  https://developer.android.com/develop/background-work/services/fgs/service-types

- Android 14 行为变化：
  https://developer.android.com/about/versions/14/behavior-changes-14

- Alarm / WorkManager 调度：
  https://developer.android.com/develop/background-work/services/alarms

- DataStore：
  https://developer.android.com/topic/libraries/architecture/datastore

- Bilibili 开放平台：
  https://open.bilibili.com/doc

- Bilibili 直播接口参考资料（非官方社区整理）：
  https://github.com/pskdje/bilibili-API-collect/blob/main/docs/live/info.md

---

# 14 产品路线与未来扩展（非当前核心规则）

## 原规范 68：MVP → V1.5 → V2.0 → V3.0 产品路线【历史说明：不可直接实现】

本节用于说明产品演进阶段；它不代表当前 v2.0 已设计的能力属于“未来未设计”。当前文档中关于 v2.0 的直播历史、统计、备份和可靠性规则均视为本版本设计基线。

## MVP

```text
手动添加
主播卡片
状态查询
开播通知
关播通知
标签
分组
搜索
筛选
排序
手动刷新
直播间跳转
```

## V1.5：可靠性基础演进阶段（属于 v2.0 的实现前置阶段）

这里描述的是开发过程中优先完成的基础能力，不表示这些功能在 v2.0 中属于“旧版本能力”或已经移除。

```text
MonitoringController
状态确认
Notification Outbox
通知历史
LiveSession 基础能力
诊断中心
批量操作
主播详情页
重点主播
主播级通知设置
后台恢复基础
```

## V2.0：完整当前设计基线

```text
核心监控与可靠通知
后台可靠运行与恢复
主播管理与分类
直播历史与人工修正
统计与数据质量
HTML 历史报告
直播历史备份配置文件导出/恢复
容量预警与自动清理
可选官方授权与关注导入
```

因此，“MVP / V1.5 / V2.0”在本文件中描述的是实现演进路径；是否属于当前 v2.0 功能，以本节的“V2.0：完整当前设计基线”和第 71 节当前版本功能总表为准。

## V3.0：外围扩展候选

```text
云端持续监控（可选）
Web 控制台（可选）
多平台通知（可选）
```

## 原规范 69：后续新增功能池【历史说明：不可直接实现】

以下功能不属于当前 v2.0 核心闭环，作为后续 backlog；已经在 v2.0 设计中的直播历史、统计、备份恢复和数据质量不再列入此处。

## A. 智能通知

```text
直播开始延迟通知
主播连续开播提醒
“终于等到”提醒
重点主播突破免打扰
自定义通知声音
```

## B. 自动化

```text
按直播分区自动打标签
按昵称关键词自动打标签
按标签自动分组
按时间段自动调整监控频率
```

## C. 多端/外围服务

```text
桌面客户端
Web Dashboard
Telegram
邮件
其他合法通知渠道
```

## D. 云端能力

```text
云端任务调度
服务器持续监控
云端事件历史
远程查看监控状态
```

本地客户端当前不提供实时多设备同步；如未来增加云端能力，应另行定义独立的同步协议和数据所有权边界。

## 原规范 231：v2.0 功能优先级补充【历史说明：不可直接实现】

## P1

```text
✅ 高级检测间隔自定义
✅ 高级网络参数自定义
✅ 安全的 Endpoint 配置展示/开发配置能力
✅ Bilibili App 优先跳转
✅ 用户跳转偏好
✅ 监控错误日志
✅ 软件运行错误日志
✅ 日志筛选
✅ 日志聚合
✅ 日志生命周期管理
✅ 开发者诊断模式
✅ 一键诊断包导出
✅ correlationId
✅ 崩溃恢复日志联动
```

## P2

```text
✅ 多套 API 配置预设
✅ 高级请求策略模板
✅ 远程诊断辅助
✅ 自动生成开发者故障摘要
✅ 错误趋势分析
```

---

# 15. v2.4 全局一致性闭环索引（自检索引，非规则来源）

本节**不是**第二套业务规则，也**不是**权威定义。它只是把 0.6 已经固定的关键不变量集中列出，供 AI Coding Agent 在提交代码前自检。

```text
权威性声明：
  1. 本表右列一律给出 0.6.x 编号；0.6 是唯一规则来源。
  2. 本表任何一行与 0.6 冲突时，一律以 0.6 为准，本表视为过期，必须先修正本表再继续。
  3. 本表不得引用标题含"历史说明：不可直接实现"的章节作为定义来源；如某闭环只能在历史章节找到，
     说明该闭环尚未进入 0.6，必须先把定义补入 0.6。
  4. 本表不是门槛表；门槛见 0.6.43（生成前）与 0.6.46（完成前）。
```

| 编号 | 关键闭环 | 0.6 权威落点 |
|---|---|---|
| C1 | confirmedLiveStatus 不含 ERROR | 0.6.1 / 0.6.2（`AppError` 是唯一错误枚举） |
| C2 | UNKNOWN → LIVE 创建 PROVISIONAL OPEN Session | 0.6.5 |
| C3 | OPEN Session 的 ACTIVE/ABANDONED 生命周期与部分唯一索引 | 0.6.3 / 0.6.4 / 0.6.4.3 |
| C4 | ReliableMonitorInterval 是关播资格事实 | 0.6.6 |
| C5 | 状态确认上下文持久化 + CAS | 0.6.7 / 0.6.15 |
| C6 | 所有关键写入携带 sequence/generation/fencing | 0.6.15 / 0.6.35 |
| C7 | stableId/uid 恢复冲突必须预检并显式决策 | 0.6.20 / 0.6.40 |
| C8 | notificationId 注册表与 DELIVERY_UNKNOWN 恢复 | 0.6.12 / 0.6.34 |
| C9 | Aggregate ↔ Outbox 一一对应 + 原子创建 | 0.6.13 / 0.6.9.1 |
| C10 | NotificationHistory 与 Outbox 生命周期分离 | 0.6.9.1 / 0.6.34 |
| C11 | Global Gap 与 GapStreamer 分层 | 0.6.9 |
| C12 | ROUND 不默认等价于下播 | 0.6.23 |
| C13 | MonitoringLease / MaintenanceMode 数据库级互斥 | 0.6.14 / 0.6.35 |
| C14 | MonitoringConfigSnapshot 单一版本源 | 0.6.14 / 0.6.16 / 0.6.16.1 |
| C15 | 配置导入与业务备份域隔离 | 0.6.26 / 0.6.39 |
| C16 | StatusHistory 携带 sequence/generation/fencing/transition | 0.6.8 / 0.6.16.1 |
| C17 | formatVersion / backupSchemaVersion / roomSchemaVersion 三者分离 | 0.6.19 |
| C18 | ExportSnapshot 支持分页/流式写出与一致性读 | 0.6.18 / 0.6.38 |
| C19 | Aggregate window 与 expiresAt 闭环 | 0.6.10 / 0.6.29 |
| C20 | 软删除保留历史；硬删除显式确认并一致删除 | 0.6.22 |
| C21 | 单事件通知幂等（sourceEventId 唯一） | 0.6.9.1 / 0.6.29 |
| C22 | 系统问题通知的第三种 Outbox 形态 | 0.6.12 / 0.6.29 |
| C23 | sourceDataVersion 触发集合封闭 | 0.6.14 |
| C24 | TypeConverter 与枚举名序列化契约 | 0.6.4.2 |
| C25 | 部分唯一索引全部由 Migration.execSQL 建立 | 0.6.4.3 / 0.6.44 |

AI Coding Agent 在实现任一跨模块 UseCase 时，必须检查其是否同时满足以上相关闭环。

# 附录 A：原始设计演进与阶段性资料（非规范）

> 本附录保留本项目历史上已形成的阶段性总结、重复汇总和增量修正，主要用于追溯设计演进。**附录不得作为实现优先级依据**；当其内容与规范主体冲突时，以规范主体为准。

## 原规范 70：AI Coding Agent 开发协议 v2【历史说明：不可直接实现】

AI 每次修改代码都必须：

```text
1. 先阅读本设计文档中与本任务相关的章节。
2. 先说明将修改哪些文件。
3. 不得一次性无理由重构整个项目。
4. 不得擅自更换架构或技术栈。
5. 数据库字段改变必须提供 Migration。
6. API 字段不确定时必须停留在 DTO/Adapter 层，不得臆造 Domain 字段。
7. Android 系统限制相关实现必须优先参考官方文档。
8. 每新增 UseCase 至少添加对应测试。
9. 每完成一个阶段必须给出验证方法。
10. 发现当前设计存在冲突时，应优先报告冲突，而不是悄悄覆盖既有行为。
```

AI 输出顺序：

```text
修改目标
↓
架构影响
↓
文件清单
↓
代码
↓
Migration（如有）
↓
测试
↓
运行/验证步骤
↓
已知限制
```

---

## 原规范 75：v2.0 变更摘要【历史说明：不可直接实现】

相比 v1.0，本版主要变化：

```text
+ MonitoringController / MonitoringScheduler
+ MonitoringRuntimeState
+ LiveSession
+ 状态确认与 SUSPECTED_OFFLINE
+ 状态检查 / 资料刷新分离
+ 批次并发与熔断
+ Notification Outbox 完整状态机
+ 通知历史
+ 主播详情页
+ 主播级监控/通知策略
+ 重点主播
+ 排序
+ 批量操作
+ 普通应用配置与直播历史备份配置文件分离
+ 历史自动清理
+ 诊断中心
+ Android 15+ FGS 长期运行约束说明更新
+ P0 / P1 / P2 产品路线
+ AI Coding Agent 开发协议升级
```

Android 官方目前明确：Android 14 目标应用需要为 Foreground Service 指定适当类型及对应权限；`specialUse` 需要在 Manifest 中说明具体用途。Android 15 对 `dataSync` 等前台服务引入了 24 小时窗口内累计 6 小时的运行限制。因此，本项目 v2.0 不把 `dataSync` 设计成永久后台轮询方案，并把实时监控设计为“用户明确开启 + 系统允许 + 受约束运行 + 自动降级/恢复”。

## 原规范 103：v2.0 数据可靠性最终原则【历史说明：不可直接实现】

整个项目将主播状态分成四种不同概念：

```text
Confirmed State
确认的事实

Observed State
当前一次请求观察到的结果

Runtime State
监控系统当前运行状态

Confidence
这条数据到底有多可信
```

它们不得混成一个 Boolean。

推荐最终关系：

```text
Confirmed State
       +
Observed State
       +
Monitoring Runtime
       +
Monitoring Gap
       +
Data Confidence
       ↓
LiveSession
       ↓
Statistics
```

这套设计可以避免本项目最危险的一类 Bug：

> **软件不知道发生了什么，却把“不知道”写成了“确定发生了什么”。**

---

## 原规范 104：v2.0 功能总表补充：数据可靠性与故障恢复【历史说明：不可直接实现】

新增 P0 功能：

```text
✅ DataConfidence
✅ MonitoringGap
✅ LiveSession 完整生命周期
✅ 网络异常与 UNKNOWN 状态
✅ 崩溃/进程终止识别
✅ StartupRecovery
✅ ConsistencyChecker
✅ RecoveryCheck
✅ 统计可重建
✅ 异常数据排除
✅ 数据新鲜度
✅ 分级异常通知
✅ 异常通知冷却与去重
✅ 监控恢复提醒
✅ 故障审计日志
✅ 昨日监控报告
```

这些功能与“开播通知”“关播通知”“后台监控”同属核心可靠性能力，不应全部作为未来可选功能处理。

---

## 原规范 106：v2.0 文档更新后的核心架构【历史说明：不可直接实现】

```text
                         ┌──────────────────┐
                         │    Compose UI    │
                         └────────┬─────────┘
                                  ↓
                           ViewModel / State
                                  ↓
                              UseCase
                                  ↓
                             Repository
                         ┌────────┴────────┐
                         ↓                 ↓
                       Room           Remote API
                         ↑                 ↓
                         │           RemoteResult
                         │                 ↓
                         │          State Detector
                         │                 ↓
                         │       Confidence / Transition
                         │            ┌────┴────┐
                         │            ↓         ↓
                         │        Confirmed   Unknown
                         │            ↓         ↓
                         │       LiveSession  MonitoringGap
                         │            ↓         ↓
                         └───────────┬─────────┘
                                     ↓
                              Notification Outbox
                                     ↓
                                Notification
                                     ↓
                                  AuditLog
                                     ↓
                           Statistics Calculator
                                     ↓
                              Rebuildable Stats

       Android Lifecycle / Crash / Network / Service Events
                              ↓
                       StartupRecovery
                              ↓
                       ConsistencyChecker
                              ↓
                         RecoveryCheck
                              ↓
                     MonitoringController
```

---

## 原规范 159：v2.0 功能清单补充【历史说明：不可直接实现】

新增核心功能：

```text
✅ 手工创建直播历史
✅ 开播时间手工录入
✅ 下播时间手工录入
✅ 直播时长手工录入
✅ 任意两项自动计算第三项
✅ 时间矛盾检测
✅ 跨午夜计算
✅ 历史数据人工修正
✅ 错误历史标记
✅ 修正前后对比
✅ 修正审计日志
✅ 修正版本
✅ 撤销修正
✅ 合并 Session
✅ 拆分 Session
✅ 统计自动重算
✅ 统计来源追溯
✅ 数据质量总览
✅ 日历视图
✅ 时间线视图
✅ HTML 数据可视化导出
✅ HTML 离线查看
✅ HTML 数据质量说明
✅ HTML 自定义筛选
✅ 可选 JSON 数据交换导出（不作为备份恢复来源）
```

---

## 原规范 163：本次新增功能优先级【历史说明：不可直接实现】

## P0：必须进入正式开发基础

```text
✅ 手工直播历史
✅ 两项时间自动计算
✅ 历史异常修正
✅ 原始数据与修正数据分离
✅ 修正审计日志
✅ 统计重新计算
✅ 数据可信度
✅ 统计来源追溯
✅ 数据质量提醒
```

## P1：建议第一版稳定后加入

```text
✅ Session 合并
✅ Session 拆分
✅ 日历视图
✅ 时间线视图
✅ 批量异常处理
✅ 统计质量面板
✅ HTML 完整报告
```

## P2：后续增强

```text
✅ HTML 高级图表
✅ 多主播对比
✅ 标签/分组统计
✅ 热力图
✅ 自定义报告模板
✅ 自动生成周期报告
```

---

## 原规范 164：v2.0 最终补充总结【历史说明：不可直接实现】

本项目的直播历史系统现在形成：

```text
自动监控
    ↓
记录观察
    ↓
判断可信度
    ↓
生成 LiveSession
    ↓
发现异常
    ↓
提醒用户
    ↓
用户修正
    ↓
保存修正历史
    ↓
重新计算统计
    ↓
生成准确统计
    ↓
HTML 可视化导出
```

核心思想：

> **软件负责发现和计算，用户负责在必要时确认事实；任何不确定的数据都必须诚实地标出来。**

这套机制既能解决网络异常、软件崩溃造成的统计污染，也能让用户主动录入自己知道的准确直播时间，并让整个统计系统在多年使用后仍然可以被检查、修正、重算和导出。

---

## 原规范 198：v2.0 稳定性最终目标【历史说明：不可直接实现】

本项目的稳定性不以“永远不出错”为目标，而以：

```text
错误发生
    ↓
能够识别
    ↓
不会污染可信数据
    ↓
能够记录
    ↓
能够恢复
    ↓
能够告诉用户
    ↓
能够人工修正
    ↓
能够重新计算
    ↓
能够追溯历史
```

作为最终目标。

真正可靠的监控软件不是“永远显示一个答案”，而是在不知道的时候明确告诉用户：

```text
我们不知道
为什么不知道
从什么时候开始不知道
哪些数据仍然可信
恢复以后做了什么
哪些数据需要你确认
```

这套原则适用于直播状态、直播历史、统计、通知、导出和后台监控的全部核心模块。

---

## 原规范 232：v2.0 稳定性最终原则补充【历史说明：不可直接实现】

本项目最终采用：

```text
用户可定制
        ↓
安全边界校验
        ↓
统一调度
        ↓
可靠执行
        ↓
独立记录
        ↓
异常分类
        ↓
自动恢复
        ↓
用户提示
        ↓
开发者诊断
```

软件应该做到：

```text
普通用户看到的是：
“监控正常 / 有异常 / 已恢复”

高级用户看到的是：
“检测间隔 / API / 请求策略 / 健康度”

开发者看到的是：
“错误日志 / StackTrace / correlationId / 状态链路”
```

三种用户视图互不干扰。

最终原则：

> **可配置、可观察、可恢复、可诊断，但不能因为追求可配置而牺牲安全性和数据真实性。**

---



---

## 附录 B：2026-09-13 Android 官方约束核验来源

- Android Developers：Restrictions on starting a foreground service from the background
- Android Developers：Launch a foreground service
- Android Developers：Behavior changes: Apps targeting Android 15 and higher
- Android Developers：Define work requests

本附录只记录本次重构时用于核验 Android 系统行为的官方资料，不改变业务规则。


# 100 附：v2.3 实现闭环审查清单（**已被取代，仅作历史追溯**）

> **作废声明**：本清单是 v2.3 的交付门槛，**已被 v2.4 的 §0.6.43（生成前门槛）与 §0.6.46（完成前门槛）完全取代**。
> 本节不新增独立业务规则，也不得作为任何阶段的门槛；所有规则以 §0.6 最终实现契约与 §15 的自检索引为准。
> 若本节内容与 §0.6 冲突，一律以 §0.6 为准，且视为本节已过期。

本附录仅用于追溯 v2.3 的审查范围；所有规则均回指 0.6 最终实现契约。

## 100.1 模型唯一性

- LiveSession 仅允许一个最终 Entity；历史定义不可实现。
- DataConfidence / DataSource 仅使用 0.6.2。
- ConfirmedLiveStatus 不含 ERROR。
- PendingTransition 仅由 `streamer_pending_transition` 持久化。
- ReliableMonitorInterval 是关播通知资格唯一持久化依据。
- AggregateStatus 与 OutboxStatus 不交叉复用。

## 100.2 数据库唯一性

- ACTIVE OPEN LiveSession 使用部分唯一索引。
- ABANDONED OPEN 最多一条由业务事务维护。
- 关系表有外键。
- `source_data_revision`、`monitoring_config`、`system_runtime_lock` 均是单行表。
- notification_id_registry 的 eventKey 和 notificationId 均唯一。

## 100.3 事务与并发

- 状态转换必须 CAS。
- PendingTransition 必须 CAS。
- sourceDataVersion 必须事务内原子递增。
- Lease/Maintenance 必须数据库级互斥。
- Notify 调用不放在数据库事务内。
- Aggregate + relation + Outbox 同事务。
- Restore 是维护事务；Export/Backup 使用一致性只读事务或临时快照。

## 100.4 业务通知

```text
首次 UNKNOWN→LIVE：无 START 通知
普通确认 LIVE：按策略发送“正在直播”
异常恢复仍 LIVE：发送“正在直播”
恢复后已 OFFLINE：不发送“正在直播”，不发送关播
当前可靠区间异常：本区间关播资格失效
新区间持续正常至 OFFLINE：发送关播
```

## 100.5 交付门槛

只有以下条件全部满足，AI Coding Agent 才允许进入代码生成阶段：

```text
Entity / DTO / Enum 不存在同名第二定义
所有部分索引都有 Migration SQL
所有关键 update 都有 WHERE CAS 条件
所有来源字段都有明确 nullable 语义
所有跨设备实体都有 stableId/eventKey/correctionId 等稳定身份
所有状态机枚举都有终态与恢复路径
所有通知发送异常都有 Outbox 最终状态
所有统计缓存都有 query/timezone/eligibility 维度
所有导出/恢复都有一致性边界
所有 P0 并发测试均已列入测试套件
```
