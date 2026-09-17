package com.example.bilimonitor.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.ExportSnapshotStatus
import com.example.bilimonitor.data.local.ExportType
import com.example.bilimonitor.data.local.FollowImportStatus
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.RestoreConflictAction
import com.example.bilimonitor.data.local.RestoreConflictType
import com.example.bilimonitor.data.local.RestoreFinishReason
import com.example.bilimonitor.data.local.RestoreRunStatus
import com.example.bilimonitor.data.local.StatisticsEligibility

@Entity(tableName = "monitoring_config")
data class MonitoringConfigEntity(
    @PrimaryKey val singletonId: Int,
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
    val freshnessStaleSeconds: Int,
    val backgroundKeepAliveChoice: BackgroundKeepAliveChoice,
    val noGuaranteeAcknowledged: Boolean,
    val noGuaranteeAcknowledgedAt: Long?,
    val startConfirmationCount: Int,
    val endConfirmationCount: Int,
    val placeholderText: String,
    /**
     * 触发重试/熔断的**最小失败占比**（百分比，用户可配，默认 50）。
     *
     * 语义：本轮失败主播数 / 本轮参与检查的主播数 **严格大于** 该值才触发重试与熔断。
     * 为什么需要它：原实现里一个持续失败的房间就能把**全局**熔断顶开，
     * 之后所有主播都不再被检测（用户报告的 bug）。
     * v9 新增列；`DEFAULT 50` 与实体默认值必须一致，否则 Room 迁移后校验会失败。
     */
    @ColumnInfo(defaultValue = "50") val retryFailureRatioPercent: Int = 50,
    /**
     * 处于「封禁中」的主播的**复查间隔**（秒，用户可配，默认 1800 = 30 分钟）。
     * v9 新增列；`DEFAULT 1800` 同上。
     */
    @ColumnInfo(defaultValue = "1800") val banRecheckIntervalSeconds: Int = 1800,
    /**
     * 熔断总开关（v10 新增列，默认 **true**）。
     *
     * 为什么默认必须是 true：全新安装的默认行为要与引入本开关之前**逐字一致** ——
     * 老安装升级后也不能因为"默认关"而悄悄改变既有的暂停请求行为。
     * 想要"失败就一直重试、不暂停"的用户才显式关掉它。
     *
     * `DEFAULT 1` 与 [com.example.bilimonitor.data.local.db.AppMigrations.V9_10__circuitBreakerSwitch]
     * 里的 DDL 必须逐字一致，否则 Room 迁移后的全量 schema 校验会直接报
     * "Migration didn't properly handle ..."（开库即崩、无自愈路径）。
     */
    @ColumnInfo(defaultValue = "1") val circuitBreakerEnabled: Boolean = true,
    val updatedAt: Long
)

@Entity(tableName = "monitoring_config_revision")
data class MonitoringConfigRevisionEntity(
    @PrimaryKey val configVersion: Long,
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
    val freshnessStaleSeconds: Int,
    val backgroundKeepAliveChoice: BackgroundKeepAliveChoice,
    val noGuaranteeAcknowledged: Boolean,
    val noGuaranteeAcknowledgedAt: Long?,
    val startConfirmationCount: Int,
    val endConfirmationCount: Int,
    val placeholderText: String,
    /** 修订快照同样带上 v9 的两个新字段，避免"当前值有、历史值无"的口径分叉。 */
    @ColumnInfo(defaultValue = "50") val retryFailureRatioPercent: Int = 50,
    @ColumnInfo(defaultValue = "1800") val banRecheckIntervalSeconds: Int = 1800,
    /** 修订快照同样带上 v10 的熔断开关，避免"当前值有、历史值无"的口径分叉。 */
    @ColumnInfo(defaultValue = "1") val circuitBreakerEnabled: Boolean = true,
    val createdAt: Long
)

@Entity(tableName = "source_data_revision")
data class SourceDataRevisionEntity(
    @PrimaryKey val singletonId: Int,
    val sourceDataVersion: Long,
    val updatedAt: Long
)

@Entity(tableName = "system_runtime_lock")
data class SystemRuntimeLockEntity(
    @PrimaryKey val singletonId: Int,
    val maintenanceMode: MaintenanceMode,
    val monitoringLeaseId: String?,
    val runtimeInstanceId: String?,
    val monitorGeneration: Long,
    val fencingToken: String?,
    val leaseUntilWall: Long?,
    val leaseUntilElapsed: Long?,
    val leaseBootId: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "streamer_monitor_policy",
    foreignKeys = [ForeignKey(
        entity = StreamerEntity::class, parentColumns = ["id"],
        childColumns = ["streamerId"], onDelete = ForeignKey.CASCADE
    )]
)
data class StreamerMonitorPolicyEntity(
    @PrimaryKey val streamerId: Long,
    val enabled: Boolean,
    val intervalSeconds: Int?,
    val notifyStart: Boolean,
    val notifyEnd: Boolean,
    val notifyRound: Boolean,
    /**
     * 直播标题变化通知（用户定稿功能）。**默认关闭**。
     * 标题变化在直播中可能很频繁，默认打开会变成骚扰，所以做成显式选择。
     */
    @ColumnInfo(defaultValue = "0") val notifyTitleChange: Boolean = false,
    /** 直播分区变化通知（用户定稿功能）。**默认关闭**，理由同上。 */
    @ColumnInfo(defaultValue = "0") val notifyAreaChange: Boolean = false,
    /** NULL = 跟随全局；非空则覆盖全局（0.6.47.3）。 */
    val startConfirmationCount: Int?,
    val endConfirmationCount: Int?,
    val aggregationEnabledOverride: Boolean?,
    val aggregationThresholdOverride: Int?,
    val overrideQuietHours: Boolean?,
    val updatedAt: Long
)

@Entity(tableName = "follow_import_task", indices = [Index("status"), Index("startedAt"),
    Index(value = ["status", "startedAt"])])
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
    indices = [Index("sourceDataVersion"), Index("calculatedAt"), Index(value = ["queryFingerprint"], unique = true)]
)
data class StatisticsCacheEntity(
    @PrimaryKey val cacheKey: String,
    val statisticsVersion: Long,
    val sourceDataVersion: Long,
    /** canonicalJson 原文；cacheKey = SHA-256(UTF8(queryFingerprint))（0.6.17）。 */
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

@Entity(tableName = "export_snapshot", indices = [Index("status"), Index("expiresAt"), Index("createdAt"),
    Index(value = ["status", "expiresAt"])])
data class ExportSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val exportType: ExportType,
    val sourceDataVersion: Long,
    val createdAt: Long,
    val expiresAt: Long?,
    val status: ExportSnapshotStatus
)

@Entity(
    tableName = "export_snapshot_live_session",
    primaryKeys = ["snapshotId", "sessionStableId"],
    foreignKeys = [ForeignKey(entity = ExportSnapshotEntity::class, parentColumns = ["snapshotId"],
        childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("snapshotId")])
data class ExportSnapshotLiveSessionEntity(
    val snapshotId: String,
    val sessionStableId: String,
    val rowJson: String
)

@Entity(tableName = "restore_run", indices = [Index("backupId"), Index("status"),
    Index(value = ["backupId", "startedAt"])])
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
    indices = [Index("restoreRunId"), Index("type"), Index(value = ["restoreRunId", "type"])]
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
