package com.example.bilimonitor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.DataConfidence
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.DataSource
import com.example.bilimonitor.data.local.GapReason
import com.example.bilimonitor.data.local.GapScope
import com.example.bilimonitor.data.local.IntervalStatus
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.data.local.ReliableIntervalInvalidReason

@Entity(
    tableName = "streamer",
    indices = [
        Index(value = ["stableId"], unique = true),
        Index(value = ["uid"], unique = true),
        Index("roomId"),
        Index("deletedAt"),
        Index("confirmedLiveStatus"),
        Index("isFavorite"),
        Index("monitorPolicyStableId"),
        Index(value = ["deletedAt", "confirmedLiveStatus", "isFavorite"])
    ]
)
data class StreamerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 跨设备稳定身份；运行期一旦建立绑定不得自动改写（0.6.22.2）。 */
    val stableId: String,
    /** 主播业务身份；UNIQUE NOT NULL（249.9.1）。 */
    val uid: Long,
    /** 可变远程资料；拿不到就保持 NULL，不得写占位值（249.9.1）。 */
    val roomId: Long?,
    val shortRoomId: Long?,
    /** NOT NULL；昵称缺失时写 placeholderText（249.9.1.1）。 */
    val name: String,
    /** 1 = name 为用户手工编辑或占位文案，远程刷新不得覆盖。 */
    val nameLocked: Boolean,
    val avatarUrl: String?,
    val roomTitle: String?,
    val parentAreaName: String?,
    val areaName: String?,
    val coverUrl: String?,
    val liveUrl: String?,
    /** 唯一可写的确认业务状态；不得写入 ERROR（0.6.1）。 */
    val confirmedLiveStatus: ConfirmedLiveStatus,
    /** 本次观察结果；错误只在这里表达，不进入 confirmedLiveStatus。 */
    val lastObservationResult: ObservationResult,
    /** 每主播单调递增的观察序号；CAS 条件之一（0.6.35）。 */
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
    /** 软删除标记；非空即视为已删除（0.6.22.1）。 */
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "tag", indices = [Index(value = ["stableId"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableId: String,
    val name: String,
    val colorHex: String?,
    val sortOrder: Int
)

@Entity(tableName = "group", indices = [Index(value = ["stableId"], unique = true)])
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableId: String,
    val name: String,
    val sortOrder: Int,
    val collapsed: Boolean
)

@Entity(
    tableName = "streamer_tag_cross_ref",
    primaryKeys = ["streamerId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = StreamerEntity::class, parentColumns = ["id"], childColumns = ["streamerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId")]
)
data class StreamerTagCrossRefEntity(val streamerId: Long, val tagId: Long)

@Entity(
    tableName = "streamer_group_cross_ref",
    primaryKeys = ["streamerId", "groupId"],
    foreignKeys = [
        ForeignKey(entity = StreamerEntity::class, parentColumns = ["id"], childColumns = ["streamerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("groupId")]
)
data class StreamerGroupCrossRefEntity(val streamerId: Long, val groupId: Long)

enum class LiveSessionLifecycleState { ACTIVE, ABANDONED }

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
        Index(value = ["sessionKey"], unique = true),
        Index("endTime"),
        Index("lifecycleState")
    ]
)
data class LiveSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableId: String,
    /** 业务幂等键，唯一；固定为 "LIVEsession:<sessionStableId>"（0.6.4）。 */
    val sessionKey: String,
    val streamerId: Long,
    /** 语义固定为"软件确认本场次进入 LIVE 的时刻"，不是主播真实开播时间（0.6.5）。 */
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
    /** 自由文本诊断字段，不承载枚举语义。 */
    val endReason: String?,
    val lifecycleState: LiveSessionLifecycleState,
    val currentReliableIntervalId: String?,
    val lastConfirmedLiveAt: Long?,
    val confirmedOfflineAt: Long?,
    val autoUpdateProtected: Boolean,
    val correctionVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val note: String?,
    /** 开场确认时刻所在时区（IANA ID，用户定稿：记录历史创建时的时区）；旧数据为 NULL。 */
    val startTimeZone: String? = null,
    /** 关播确认时刻所在时区；旧数据或进行中场次为 NULL。 */
    val endTimeZone: String? = null
)

@Entity(
    tableName = "live_event",
    indices = [Index("streamerId"), Index("streamerStableId"), Index("sessionStableId"),
        Index("eventConfirmedAt"), Index("transitionId"),
        Index(value = ["streamerStableId", "eventConfirmedAt"]),
        Index(value = ["sessionStableId", "eventConfirmedAt"]),
        Index("createdAt")]
)
data class LiveEventEntity(
    @PrimaryKey val eventId: String,
    val streamerId: Long,
    val streamerStableId: String,
    val sessionStableId: String?,
    val eventType: com.example.bilimonitor.data.local.LiveEventType,
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

@Entity(
    tableName = "status_history",
    foreignKeys = [ForeignKey(
        entity = StreamerEntity::class,
        parentColumns = ["id"],
        childColumns = ["streamerId"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index("streamerId"), Index("streamerStableId"), Index("observationSequence"),
        Index(value = ["streamerId", "occurredAt"]),
        Index(value = ["streamerStableId", "occurredAt"])]
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
    val configVersion: Long?,
    val createdAt: Long
)

@Entity(
    tableName = "streamer_pending_transition",
    foreignKeys = [ForeignKey(
        entity = StreamerEntity::class,
        parentColumns = ["id"],
        childColumns = ["streamerId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class StreamerPendingTransitionEntity(
    @PrimaryKey val streamerId: Long,
    val monitorGeneration: Long,
    val streamerMonitorGeneration: Long,
    val pendingTransition: PendingTransition,
    val confirmationCount: Int,
    val lastObservationSequence: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "reliable_monitor_interval",
    indices = [
        Index("streamerStableId"),
        Index("sessionStableId"),
        Index("monitorGeneration"),
        Index("streamerMonitorGeneration"),
        Index("invalidatedAt"),
        Index(value = ["sessionStableId", "startedAt"])
    ]
)
data class ReliableMonitorIntervalEntity(
    @PrimaryKey val intervalId: String,
    val streamerStableId: String,
    val sessionStableId: String?,
    val startedAt: Long,
    val invalidatedAt: Long?,
    val invalidReason: ReliableIntervalInvalidReason?,
    val lastReliableObservationSequence: Long,
    val monitorGeneration: Long,
    val streamerMonitorGeneration: Long,
    val fencingToken: String,
    val status: IntervalStatus,
    val updatedAt: Long
)

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
    indices = [Index("gapId"), Index("streamerStableId"), Index("affectedEndAt"),
        Index(value = ["streamerStableId", "affectedStartAt"])]
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

/**
 * 场次标题变更记录（用户定稿功能）：同一场次内直播间标题每次变化记一行，
 * PK(sessionStableId, title) 天然去重；会话删除级联清理。
 * 说明：此表为用户功能新增，超出文档 0.6.29 基线，已在交付说明中登记。
 */
@Entity(
    tableName = "live_session_title",
    primaryKeys = ["sessionStableId", "title"],
    foreignKeys = [ForeignKey(
        entity = LiveSessionEntity::class,
        parentColumns = ["stableId"],
        childColumns = ["sessionStableId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionStableId")]
)
data class LiveSessionTitleEntity(
    val sessionStableId: String,
    val title: String,
    val observedAt: Long
)

/**
 * 场次**直播分区**变更记录（用户定稿功能）：同一场次内分区每次变化记一行 ——
 * 一场直播里主播可能换分区（例如 虚拟主播 → 唱见），要求全部记录下来。
 * PK(sessionStableId, areaLabel) 天然去重（换回原分区不会重复记）；会话删除级联清理。
 *
 * 分区数据来自**公开**的直播状态接口（`area_v2_parent_name` / `area_name`），
 * 不需要登录 —— 与观看历史导入那类账号接口无关。
 */
@Entity(
    tableName = "live_session_area",
    primaryKeys = ["sessionStableId", "areaLabel"],
    foreignKeys = [ForeignKey(
        entity = LiveSessionEntity::class,
        parentColumns = ["stableId"],
        childColumns = ["sessionStableId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionStableId")]
)
data class LiveSessionAreaEntity(
    val sessionStableId: String,
    /** 展示用分区名：有父分区时写成"父分区 · 子分区"，否则只有子分区。 */
    val areaLabel: String,
    val parentAreaName: String?,
    val areaName: String?,
    val observedAt: Long
)

@Entity(tableName = "live_session_correction", indices = [Index("sessionStableId")])
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
