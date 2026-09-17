package com.example.bilimonitor.data.local

/**
 * 唯一基础枚举（0.6.1 / 0.6.2）。所有枚举列经 DshTypeConverters 以 Enum.name 持久化。
 * 不得在本文件之外声明第二套同名枚举。
 *
 * ★ 关于"枚举列受 DDL CHECK 约束、新增取值必须迁移"这一说法（本轮核实结论）：
 *   **不成立**。`app/schemas/com.example.bilimonitor.data.local.db.AppDatabase/9.json` 里
 *   `monitoring_error_log.errorCode` / `application_error_log.errorCode` 都是
 *   `errorCode TEXT NOT NULL`，全库 schema 搜不到任何 `CHECK(`；Room 也不为枚举生成 CHECK
 *   （枚举经 TypeConverter 存 TEXT）。因此**新增取值不需要迁移、不需要改 DB_VERSION**，
 *   读路径由 EnumSafe.parse 兜底（认不出 → 该枚举里最保守的取值）。
 *   所以 [AppError.RATE_LIMITED] 可以直接加，而不是继续借用"网络不可用"来记限流。
 */

enum class ConfirmedLiveStatus { UNKNOWN, OFFLINE, LIVE, ROUND }

enum class ObservationResult { SUCCESS, TIMEOUT, NETWORK_ERROR, API_ERROR, PARTIAL, INVALID }

enum class PendingTransition { NONE, SUSPECTED_OFFLINE, SUSPECTED_LIVE }

enum class DataConfidence { RAW, PROVISIONAL, CONFIRMED, CORRECTED, INVALID }

enum class DataSource { MONITOR_OBSERVED, USER_ENTERED, USER_CORRECTED, CALCULATED, IMPORTED, RECOVERED, ESTIMATED }

enum class StatisticsEligibility { CONFIRMED_ONLY, INCLUDE_PROVISIONAL, INCLUDE_CORRECTED, INCLUDE_ESTIMATED }

enum class DataFreshness { FRESH, STALE, UNKNOWN }

enum class MonitoringMode { REALTIME, POWER_SAVING, MANUAL }

enum class MaintenanceMode { OFF, DATABASE_REPAIR, BACKUP, RESTORE, MIGRATION }

enum class RecoverySessionStatus { REQUESTED, RUNNING, COMPLETED, FAILED, TIMEOUT, CANCELLED }

enum class RecoveryFinishReason { HEALTHY, TIMEOUT, FAILED, CANCELLED, SUPERSEDED }

enum class MonitorHealthStatus { HEALTHY, DEGRADED, RECOVERING, BLOCKED, STOPPED }

enum class AppError {
    NETWORK_UNAVAILABLE, NETWORK_TIMEOUT, API_REJECTED, API_INVALID_RESPONSE, DATABASE_ERROR,
    PERMISSION_DENIED, NOTIFICATION_UNAVAILABLE, BACKGROUND_EXECUTION_RESTRICTED,
    CONFIG_INVALID, RESTORE_CONFLICT, RESTORE_INCOMPATIBLE, EXPORT_FAILED, UNKNOWN,

    /**
     * 服务端限流（HTTP 429 / 带 `Retry-After` 的 4xx，实测 `code=-352` 风控）。
     *
     * 为什么必须与 NETWORK_UNAVAILABLE 分开：限流意味着"服务端在正常工作，只是让我们退避"，
     * 而网络不可用意味着"链路断了" —— 两者的处置完全不同（退避 vs 等网络恢复）。
     * 借用"网络不可用"记限流会把用户引向错误的排查方向（查网络，而真实原因是风控），
     * 也会让系统级盲区与主播级盲区互相矛盾。
     *
     * 新增在**末尾**：既有的持久化值是 `Enum.name`（不是 ordinal），放末尾只是为了让
     * 任何按 ordinal 取值的下游（历史导出、外部脚本）保持稳定，纯属保守。
     */
    RATE_LIMITED
}

/**
 * 通知事件类型。
 *
 * ★ 新增取值一律加在**末尾**：持久化用的是 `Enum.name`（不是 ordinal），放末尾只是为了让
 *   任何按 ordinal 取值的下游保持稳定 —— 与上面 [AppError] 同一条约定。
 *   `BAN_ENTERED` / `BAN_LIFTED` 是 2026 复查新增的"封禁状态变化"通知
 *   （此前封禁只写 audit_log，用户在通知栏里看不到任何东西）。
 */
enum class NotificationEventType { START_CONFIRMED, LIVE_RECONFIRMED, END_CONFIRMED, BATCH_LIVE, SYSTEM_PROBLEM, SYSTEM_RECOVERED, TITLE_CHANGED, AREA_CHANGED, BAN_ENTERED, BAN_LIFTED }

enum class LiveEventType { START, END, LIVE_RECONFIRMED }

enum class ReliableIntervalInvalidReason {
    TIMEOUT, NETWORK_ERROR, API_ERROR, PARTIAL, INVALID,
    MONITORING_GAP, MANUAL_STOP, STREAMER_DELETED, GENERATION_CHANGED, SESSION_CLOSED
}

enum class GapReason { NETWORK_UNAVAILABLE, API_UNAVAILABLE, DATABASE_BLOCKED, MONITORING_STOPPED, RUNTIME_CRASH, UNKNOWN }

enum class BackupScopeType { ALL_DATA, SELECTED_STREAMERS, SINGLE_STREAMER, DATE_RANGE }

enum class RestoreFinishReason { COMPLETED, FAILED, CANCELLED, REJECTED, PARTIAL_WITH_WARNING }

enum class NotificationAggregateStatus { COLLECTING, READY, FROZEN, DISPATCHED, CANCELLED, EXPIRED }

enum class NotificationOutboxStatus { PENDING, PROCESSING, DELIVERY_UNKNOWN, SENT, RETRY_WAIT, FAILED, EXPIRED, CANCELLED }

enum class AggregateEventBindingStatus { PENDING_AGGREGATION, BOUND, RELEASED }

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

enum class NotificationHistoryDeliveryStatus { CREATED, PENDING, PROCESSING, SENT, DELIVERY_UNKNOWN, FAILED, EXPIRED, CANCELLED }

enum class ExportType { HTML, JSON, BACKUP, DIAGNOSTIC }

enum class ExportSnapshotStatus { BUILDING, READY, CONSUMING, COMPLETED, FAILED, CANCELLED, EXPIRED }

enum class FollowImportStatus { REQUESTED, FETCHING, PREVIEW, APPLYING, COMPLETED, FAILED, CANCELLED }

enum class StatisticsInclusionState { INCLUDED, PARTIALLY_INCLUDED, EXCLUDED, UNKNOWN }

enum class LiveSessionOrigin { AUTO_MONITOR, USER_ENTERED, IMPORTED, RECOVERED }

enum class BackgroundKeepAliveChoice { FOREGROUND_NOTIFICATION, ACCESSIBILITY, ACCESSIBILITY_AND_FOREGROUND, NO_GUARANTEE, UNSET }

enum class TransitionReason {
    FIRST_OBSERVATION,
    CONFIRMATION_COUNT_REACHED,
    RECOVERY_RECONFIRM,
    MANUAL_STOP,
    STREAMER_DELETED
}

/** 一次投递尝试的结论（0.6.11）。DELIVERY_UNKNOWN 表示"已调用 notify 但结果未知"。 */
enum class DeliveryAttemptResult { SENT, DELIVERY_UNKNOWN, FAILED }

/** 全部 CAS 写入的统一返回语义（0.6.15）。 */
enum class CasWriteResult { APPLIED, STALE_SEQUENCE, STALE_OR_FENCED, CONFIG_STALE, DUPLICATE, INVALID }

enum class BatchResponseValidity { VALID, PARTIAL, INVALID, TRANSPORT_ERROR }
