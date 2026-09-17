package com.example.bilimonitor.data.local.convert

import androidx.room.TypeConverter
import com.example.bilimonitor.data.local.AggregateEventBindingStatus
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.BackupScopeType
import com.example.bilimonitor.data.local.BatchResponseValidity
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.DataConfidence
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.DataSource
import com.example.bilimonitor.data.local.DeliveryAttemptResult
import com.example.bilimonitor.data.local.ExportSnapshotStatus
import com.example.bilimonitor.data.local.ExportType
import com.example.bilimonitor.data.local.FollowImportStatus
import com.example.bilimonitor.data.local.GapReason
import com.example.bilimonitor.data.local.GapScope
import com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState
import com.example.bilimonitor.data.local.IntervalStatus
import com.example.bilimonitor.data.local.LiveEventType
import com.example.bilimonitor.data.local.LiveSessionOrigin
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.NotificationAggregateStatus
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.NotificationHistoryDeliveryStatus
import com.example.bilimonitor.data.local.NotificationOutboxStatus
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.data.local.RecoveryFinishReason
import com.example.bilimonitor.data.local.RecoverySessionStatus
import com.example.bilimonitor.data.local.ReliableIntervalInvalidReason
import com.example.bilimonitor.data.local.RestoreConflictAction
import com.example.bilimonitor.data.local.RestoreConflictType
import com.example.bilimonitor.data.local.RestoreFinishReason
import com.example.bilimonitor.data.local.RestoreRunStatus
import com.example.bilimonitor.data.local.StatisticsEligibility
import com.example.bilimonitor.data.local.StatisticsInclusionState
import com.example.bilimonitor.data.local.TransitionReason

/**
 * 枚举列的持久化解析（台账 H19-2.2 的落地）。
 *
 * 历史写法是裸 `Xxx.valueOf(it)`：**读到一个不认识的字符串就抛异常**，
 * 而且异常发生在 Room 读取行的路径上 —— 表现为"应用读到那一行就崩，反复重启都崩"。
 * 本项目测试期间真实踩过：手工把 `lifecycleState` 写成 `'CLOSED'`（合法值只有 ACTIVE/ABANDONED），
 * 应用立刻进入启动即崩的死循环，用户唯一的自救手段是清除应用数据。
 *
 * 什么时候会出现"不认识的枚举值"：
 *  - 外部工具/脚本直接改库（本项目在测试与排障时就会这么做）；
 *  - 恢复了一份来自**更新版本**的配置文件（那个版本有本版本还没有的枚举常量）；
 *  - 从别处拷来的数据库文件。
 * （"装回旧版本 APK"这条路径另有 Room 的 `user_version` 守卫拦着，不靠这里兜底。）
 *
 * 因此读路径一律**宽容**：认不出就回退到该枚举里语义最保守的取值，并记录一次日志。
 * 宁可让用户看到一条"未知/保守"的数据，也不要让整个应用打不开。
 * 写路径不受影响（仍然写 `name`，与 DDL 里的字面量逐字一致）。
 */
internal object EnumSafe {

    /** 同一个未知值只记一次，避免每读一行刷一条日志。 */
    private val reported: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * 攒下的未知值，供 `StartupRecovery` 落进 `application_error_log`。
     *
     * 为什么不能在这里直接写库：这里跑在 **Room 读取行的途中**，再发一次数据库写入
     * 既可能死锁又可能递归触发同一处解析失败；而且 Converter 是纯函数，不该依赖仓库。
     * 第二轮审查指出：原实现只写 logcat，而诊断包不读 logcat —— 用户在诊断页按提示
     * "导出诊断包排查"却什么也查不到。所以改为"攒起来 + 启动时统一落库"。
     */
    private val unknownValues = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun drainUnknownValues(): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(unknownValues.poll() ?: break)
        return out
    }

    fun <T : Enum<T>> parse(values: Array<T>, raw: String?, fallback: T): T? {
        if (raw == null) return null
        values.firstOrNull { it.name == raw }?.let { return it }
        if (reported.add(raw)) {
            val enumName = values.first().declaringJavaClass.simpleName
            unknownValues.add("$enumName=$raw")
            android.util.Log.e(
                "DshTypeConverters",
                "枚举列出现未知值 `$raw`（未在 $enumName 中定义），" +
                    "已回退到 ${fallback.name}；启动后会写入应用错误日志供诊断包排查"
            )
        }
        return fallback
    }
}

/**
 * 唯一 TypeConverters（0.6.4.2）：所有枚举列以 Enum.name 持久化，
 * 与 DDL CHECK 字面量逐字一致。
 *
 * 读路径的容错策略见 [EnumSafe]：未知值 → 保守缺省 + 日志，不再抛异常把应用打死。
 */
class DshTypeConverters {
    @TypeConverter fun toConfirmedLiveStatus(v: String?): ConfirmedLiveStatus? =
        EnumSafe.parse(ConfirmedLiveStatus.values(), v, ConfirmedLiveStatus.UNKNOWN)
    @TypeConverter fun fromConfirmedLiveStatus(v: ConfirmedLiveStatus?): String? = v?.name

    @TypeConverter fun toObservationResult(v: String?): ObservationResult? =
        EnumSafe.parse(ObservationResult.values(), v, ObservationResult.INVALID)
    @TypeConverter fun fromObservationResult(v: ObservationResult?): String? = v?.name

    @TypeConverter fun toPendingTransition(v: String?): PendingTransition? =
        EnumSafe.parse(PendingTransition.values(), v, PendingTransition.NONE)
    @TypeConverter fun fromPendingTransition(v: PendingTransition?): String? = v?.name

    @TypeConverter fun toDataConfidence(v: String?): DataConfidence? =
        EnumSafe.parse(DataConfidence.values(), v, DataConfidence.PROVISIONAL)
    @TypeConverter fun fromDataConfidence(v: DataConfidence?): String? = v?.name

    @TypeConverter fun toDataSource(v: String?): DataSource? =
        EnumSafe.parse(DataSource.values(), v, DataSource.MONITOR_OBSERVED)
    @TypeConverter fun fromDataSource(v: DataSource?): String? = v?.name

    @TypeConverter fun toLiveSessionOrigin(v: String?): LiveSessionOrigin? =
        EnumSafe.parse(LiveSessionOrigin.values(), v, LiveSessionOrigin.AUTO_MONITOR)
    @TypeConverter fun fromLiveSessionOrigin(v: LiveSessionOrigin?): String? = v?.name

    @TypeConverter fun toStatisticsInclusionState(v: String?): StatisticsInclusionState? =
        EnumSafe.parse(StatisticsInclusionState.values(), v, StatisticsInclusionState.UNKNOWN)
    @TypeConverter fun fromStatisticsInclusionState(v: StatisticsInclusionState?): String? = v?.name

    @TypeConverter fun toTransitionReason(v: String?): TransitionReason? =
        EnumSafe.parse(TransitionReason.values(), v, TransitionReason.FIRST_OBSERVATION)
    @TypeConverter fun fromTransitionReason(v: TransitionReason?): String? = v?.name

    @TypeConverter fun toMonitoringMode(v: String?): MonitoringMode? =
        EnumSafe.parse(MonitoringMode.values(), v, MonitoringMode.MANUAL)
    @TypeConverter fun fromMonitoringMode(v: MonitoringMode?): String? = v?.name

    @TypeConverter fun toBackgroundKeepAliveChoice(v: String?): BackgroundKeepAliveChoice? =
        EnumSafe.parse(BackgroundKeepAliveChoice.values(), v, BackgroundKeepAliveChoice.NO_GUARANTEE)
    @TypeConverter fun fromBackgroundKeepAliveChoice(v: BackgroundKeepAliveChoice?): String? = v?.name

    @TypeConverter fun toBatchResponseValidity(v: String?): BatchResponseValidity? =
        EnumSafe.parse(BatchResponseValidity.values(), v, BatchResponseValidity.INVALID)
    @TypeConverter fun fromBatchResponseValidity(v: BatchResponseValidity?): String? = v?.name

    @TypeConverter fun toDeliveryAttemptResult(v: String?): DeliveryAttemptResult? =
        EnumSafe.parse(DeliveryAttemptResult.values(), v, DeliveryAttemptResult.FAILED)
    @TypeConverter fun fromDeliveryAttemptResult(v: DeliveryAttemptResult?): String? = v?.name

    @TypeConverter fun toFollowImportStatus(v: String?): FollowImportStatus? =
        EnumSafe.parse(FollowImportStatus.values(), v, FollowImportStatus.FAILED)
    @TypeConverter fun fromFollowImportStatus(v: FollowImportStatus?): String? = v?.name

    @TypeConverter fun toExportType(v: String?): ExportType? =
        EnumSafe.parse(ExportType.values(), v, ExportType.HTML)
    @TypeConverter fun fromExportType(v: ExportType?): String? = v?.name

    @TypeConverter fun toExportSnapshotStatus(v: String?): ExportSnapshotStatus? =
        EnumSafe.parse(ExportSnapshotStatus.values(), v, ExportSnapshotStatus.FAILED)
    @TypeConverter fun fromExportSnapshotStatus(v: ExportSnapshotStatus?): String? = v?.name

    @TypeConverter fun toStatisticsEligibility(v: String?): StatisticsEligibility? =
        EnumSafe.parse(StatisticsEligibility.values(), v, StatisticsEligibility.CONFIRMED_ONLY)
    @TypeConverter fun fromStatisticsEligibility(v: StatisticsEligibility?): String? = v?.name

    @TypeConverter fun toDataFreshness(v: String?): DataFreshness? =
        EnumSafe.parse(DataFreshness.values(), v, DataFreshness.UNKNOWN)
    @TypeConverter fun fromDataFreshness(v: DataFreshness?): String? = v?.name

    @TypeConverter fun toMaintenanceMode(v: String?): MaintenanceMode? =
        // 缺省 OFF：宁可认为"没在维护"，也不要让应用卡在某个维护模式里什么都不做。
        EnumSafe.parse(MaintenanceMode.values(), v, MaintenanceMode.OFF)
    @TypeConverter fun fromMaintenanceMode(v: MaintenanceMode?): String? = v?.name

    @TypeConverter fun toRecoverySessionStatus(v: String?): RecoverySessionStatus? =
        EnumSafe.parse(RecoverySessionStatus.values(), v, RecoverySessionStatus.FAILED)
    @TypeConverter fun fromRecoverySessionStatus(v: RecoverySessionStatus?): String? = v?.name

    @TypeConverter fun toRecoveryFinishReason(v: String?): RecoveryFinishReason? =
        EnumSafe.parse(RecoveryFinishReason.values(), v, RecoveryFinishReason.FAILED)
    @TypeConverter fun fromRecoveryFinishReason(v: RecoveryFinishReason?): String? = v?.name

    @TypeConverter fun toMonitorHealthStatus(v: String?): MonitorHealthStatus? =
        EnumSafe.parse(MonitorHealthStatus.values(), v, MonitorHealthStatus.DEGRADED)
    @TypeConverter fun fromMonitorHealthStatus(v: MonitorHealthStatus?): String? = v?.name

    @TypeConverter fun toAppError(v: String?): AppError? =
        EnumSafe.parse(AppError.values(), v, AppError.UNKNOWN)
    @TypeConverter fun fromAppError(v: AppError?): String? = v?.name

    @TypeConverter fun toNotificationEventType(v: String?): NotificationEventType? =
        EnumSafe.parse(NotificationEventType.values(), v, NotificationEventType.START_CONFIRMED)
    @TypeConverter fun fromNotificationEventType(v: NotificationEventType?): String? = v?.name

    @TypeConverter fun toLiveEventType(v: String?): LiveEventType? =
        EnumSafe.parse(LiveEventType.values(), v, LiveEventType.START)
    @TypeConverter fun fromLiveEventType(v: LiveEventType?): String? = v?.name

    @TypeConverter fun toReliableIntervalInvalidReason(v: String?): ReliableIntervalInvalidReason? =
        EnumSafe.parse(
            ReliableIntervalInvalidReason.values(), v,
            ReliableIntervalInvalidReason.INVALID
        )
    @TypeConverter fun fromReliableIntervalInvalidReason(v: ReliableIntervalInvalidReason?): String? = v?.name

    @TypeConverter fun toGapReason(v: String?): GapReason? =
        EnumSafe.parse(GapReason.values(), v, GapReason.UNKNOWN)
    @TypeConverter fun fromGapReason(v: GapReason?): String? = v?.name

    @TypeConverter fun toBackupScopeType(v: String?): BackupScopeType? =
        EnumSafe.parse(BackupScopeType.values(), v, BackupScopeType.ALL_DATA)
    @TypeConverter fun fromBackupScopeType(v: BackupScopeType?): String? = v?.name

    @TypeConverter fun toRestoreFinishReason(v: String?): RestoreFinishReason? =
        EnumSafe.parse(RestoreFinishReason.values(), v, RestoreFinishReason.FAILED)
    @TypeConverter fun fromRestoreFinishReason(v: RestoreFinishReason?): String? = v?.name

    @TypeConverter fun toNotificationAggregateStatus(v: String?): NotificationAggregateStatus? =
        EnumSafe.parse(NotificationAggregateStatus.values(), v, NotificationAggregateStatus.EXPIRED)
    @TypeConverter fun fromNotificationAggregateStatus(v: NotificationAggregateStatus?): String? = v?.name

    @TypeConverter fun toNotificationOutboxStatus(v: String?): NotificationOutboxStatus? =
        EnumSafe.parse(NotificationOutboxStatus.values(), v, NotificationOutboxStatus.FAILED)
    @TypeConverter fun fromNotificationOutboxStatus(v: NotificationOutboxStatus?): String? = v?.name

    @TypeConverter fun toAggregateEventBindingStatus(v: String?): AggregateEventBindingStatus? =
        EnumSafe.parse(
            AggregateEventBindingStatus.values(), v,
            AggregateEventBindingStatus.RELEASED
        )
    @TypeConverter fun fromAggregateEventBindingStatus(v: AggregateEventBindingStatus?): String? = v?.name

    @TypeConverter fun toIntervalStatus(v: String?): IntervalStatus? =
        EnumSafe.parse(IntervalStatus.values(), v, IntervalStatus.CLOSED)
    @TypeConverter fun fromIntervalStatus(v: IntervalStatus?): String? = v?.name

    @TypeConverter fun toGapScope(v: String?): GapScope? =
        EnumSafe.parse(GapScope.values(), v, GapScope.STREAMER)
    @TypeConverter fun fromGapScope(v: GapScope?): String? = v?.name

    @TypeConverter fun toRestoreRunStatus(v: String?): RestoreRunStatus? =
        EnumSafe.parse(RestoreRunStatus.values(), v, RestoreRunStatus.FAILED)
    @TypeConverter fun fromRestoreRunStatus(v: RestoreRunStatus?): String? = v?.name

    @TypeConverter fun toRestoreConflictAction(v: String?): RestoreConflictAction? =
        EnumSafe.parse(RestoreConflictAction.values(), v, RestoreConflictAction.SKIP)
    @TypeConverter fun fromRestoreConflictAction(v: RestoreConflictAction?): String? = v?.name

    @TypeConverter fun toRestoreConflictType(v: String?): RestoreConflictType? =
        EnumSafe.parse(RestoreConflictType.values(), v, RestoreConflictType.STABLE_ID_MATCH)
    @TypeConverter fun fromRestoreConflictType(v: RestoreConflictType?): String? = v?.name

    @TypeConverter fun toNotificationHistoryDeliveryStatus(v: String?): NotificationHistoryDeliveryStatus? =
        EnumSafe.parse(
            NotificationHistoryDeliveryStatus.values(), v,
            NotificationHistoryDeliveryStatus.FAILED
        )
    @TypeConverter fun fromNotificationHistoryDeliveryStatus(v: NotificationHistoryDeliveryStatus?): String? = v?.name

    @TypeConverter fun toLiveSessionLifecycleState(v: String?): LiveSessionLifecycleState? =
        // 未知状态视为"已废弃"：不会被当成 ACTIVE 而挡住下一场直播的部分唯一索引。
        EnumSafe.parse(
            LiveSessionLifecycleState.values(), v,
            LiveSessionLifecycleState.ABANDONED
        )
    @TypeConverter fun fromLiveSessionLifecycleState(v: LiveSessionLifecycleState?): String? = v?.name
}
