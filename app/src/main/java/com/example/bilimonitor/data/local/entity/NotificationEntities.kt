package com.example.bilimonitor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.bilimonitor.data.local.AggregateEventBindingStatus
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.DeliveryAttemptResult
import com.example.bilimonitor.data.local.LiveEventType
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.NotificationAggregateStatus
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.NotificationHistoryDeliveryStatus
import com.example.bilimonitor.data.local.NotificationOutboxStatus

@Entity(
    tableName = "notification_aggregate",
    indices = [Index("bootId"), Index("status"), Index("expiresAt"), Index("createdAt")]
)
data class NotificationAggregateEntity(
    @PrimaryKey val aggregateId: String,
    val createdAt: Long,
    val windowStartWall: Long,
    val windowEndWall: Long,
    val windowStartElapsed: Long,
    val windowEndElapsed: Long,
    val bootId: String,
    val threshold: Int,
    /** 派生缓存；权威值 = COUNT(notification_aggregate_event)（0.6.9.1）。 */
    val eventCount: Int,
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
    indices = [Index("aggregateId"), Index("eventId"), Index("active"), Index("bindingStatus"),
        Index(value = ["aggregateId", "bindingStatus"]), Index(value = ["eventId", "boundAt"])]
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
    indices = [Index(value = ["eventKey"], unique = true), Index("status"), Index("nextAttemptAt"),
        Index("expiresAt"), Index("streamerId"), Index("aggregateId"), Index("sourceEventId"),
        Index("problemKey"),
        Index(value = ["status", "nextAttemptAt"]), Index(value = ["status", "leaseUntilWall"]),
        Index(value = ["streamerId", "createdAt"])]
)
data class NotificationOutboxEntity(
    @PrimaryKey val outboxId: String,
    val eventKey: String,
    /** 单事件通知路径的幂等锚点；批量与系统问题通知为 null（0.6.9.1）。 */
    val sourceEventId: String?,
    val streamerId: Long?,
    val aggregateId: String?,
    /** 系统问题通知使用；三种形态互斥。 */
    val problemKey: String?,
    /** 非空：只能来自 notification_id_registry。 */
    val notificationId: Int,
    val eventType: NotificationEventType,
    val payloadJson: String,
    val status: NotificationOutboxStatus,
    val nextAttemptAt: Long?,
    val attemptCount: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val processingStartedAt: Long?,
    val leaseUntilWall: Long?,
    val leaseUntilElapsed: Long?,
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
    indices = [Index("outboxId"), Index("eventKey"), Index("finishedAt"),
        Index(value = ["deliveryAttemptId"], unique = true),
        Index(value = ["eventKey", "startedAt"])]
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
    /** 由启动恢复流程把租约过期的未完成 attempt 结算为 DELIVERY_UNKNOWN 时写入（0.6.34）。 */
    val recoveredBy: String?,
    val error: String?
)

/** registry 先于 Outbox 写入占位，因此不得声明指向 Outbox 的外键（0.6.12）。 */
@Entity(tableName = "notification_id_registry", indices = [Index(value = ["notificationId"], unique = true)])
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
    indices = [Index("outboxId"), Index("eventKey"), Index("streamerStableId"), Index("recordedAt"),
        Index(value = ["eventKey", "recordedAt"]), Index(value = ["streamerStableId", "recordedAt"])]
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
    /** 送达时刻（SENT 时写入）。 */
    val deliveredAt: Long?,
    /**
     * **预留列：当前没有任何写入者**（2026 复查核实）。
     *
     * 失败原因一直写在 [detail] 里（Dispatcher 把 Outbox 的 `lastError` 传给它），
     * 界面也读 [detail] —— 这一列留着只是为了"将来要独立的短原因码"时不必改 schema，
     * 不要按列名假定它有值（诊断包导出时两列都会带上，查原因请看 detail）。
     */
    val reason: String?,
    val recordedAt: Long,
    val updatedAt: Long,
    val detail: String?
)

@Entity(tableName = "notification_cooldown")
data class NotificationCooldownEntity(
    @PrimaryKey val problemKey: String,
    val lastNotifiedAt: Long?,
    val cooldownUntil: Long?,
    val recoveredNotified: Boolean,
    val updatedAt: Long
)

@Entity(tableName = "problem_state", indices = [Index("active"), Index("updatedAt"), Index(value = ["active", "updatedAt"])])
data class ProblemStateEntity(
    @PrimaryKey val problemKey: String,
    val active: Boolean,
    val episodeId: String?,
    val firstObservedAt: Long?,
    val recoveredAt: Long?,
    val updatedAt: Long
)

@Entity(tableName = "audit_log", indices = [
    Index(value = ["operationId", "action"], unique = true),
    Index("occurredAt"),
    Index(value = ["targetType", "targetStableId", "occurredAt"])
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

@Entity(tableName = "monitoring_error_log", indices = [Index("occurredAt"), Index("problemKey"),
    Index("streamerStableId"), Index(value = ["problemKey", "occurredAt"]),
    Index(value = ["streamerStableId", "occurredAt"])])
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

@Entity(tableName = "health_event", indices = [Index("occurredAt"), Index("episodeId"),
    Index(value = ["episodeId", "occurredAt"])])
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
    val status: com.example.bilimonitor.data.local.RecoverySessionStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val finishReason: com.example.bilimonitor.data.local.RecoveryFinishReason?
)
