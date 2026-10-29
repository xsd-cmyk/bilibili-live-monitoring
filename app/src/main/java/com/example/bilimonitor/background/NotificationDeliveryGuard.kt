package com.example.bilimonitor.background

import com.example.bilimonitor.core.timelineNow
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.NotificationOutboxStatus
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.NotificationOutboxEntity
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.data.repository.LiveNotificationPayload
import com.example.bilimonitor.data.repository.NotificationDeliverySettingsRepository
import com.example.bilimonitor.data.repository.NotificationPayloads
import com.example.bilimonitor.data.repository.QuietHoursRepository
import com.example.bilimonitor.domain.policy.FreshnessPolicy
import com.example.bilimonitor.domain.policy.NotificationTimelinessPolicy
import com.example.bilimonitor.domain.policy.NotificationTimelinessSettings
import com.example.bilimonitor.domain.policy.QuietHoursPolicy
import com.example.bilimonitor.domain.policy.RoomBanCodec
import javax.inject.Inject
import javax.inject.Singleton

/** Validate the original fact and its current usefulness immediately before claiming a notification. */
@Singleton
class NotificationDeliveryGuard @Inject constructor(
    private val db: AppDatabase,
    private val configRepository: ConfigRepository,
    private val settingsRepository: NotificationDeliverySettingsRepository,
    private val quietHoursRepository: QuietHoursRepository,
    private val clock: AppClock
) {
    sealed interface Result {
        data class Ready(val row: NotificationOutboxEntity, val deferred: List<LiveNotificationPayload> = emptyList()) : Result
        data class Skip(val reason: String, val status: NotificationOutboxStatus = NotificationOutboxStatus.CANCELLED) : Result
        data object AwaitObservation : Result
    }

    suspend fun prepare(row: NotificationOutboxEntity): Result {
        val now = clock.nowWall()
        val interaction = db.notificationHistoryDao().findByOutboxId(row.outboxId)?.reason
        if (interaction == "USER_CLICKED" || interaction == "USER_DISMISSED")
            return Result.Skip("USER_INTERACTED", NotificationOutboxStatus.SENT)
        val config = configRepository.getSnapshot() ?: error("缺少监控配置")
        val settings = settingsRepository.current()
        val quiet = quietHoursRepository.current()
        if (quiet.enabled && QuietHoursPolicy.contains(QuietHoursPolicy.nowMinutesOfDay(now), quiet.startMinutes, quiet.endMinutes))
            return Result.Skip("SUPPRESSED_QUIET_HOURS")
        if (!config.monitoringEnabled) return Result.Skip("MONITORING_DISABLED")
        val lifetime = NotificationTimelinessPolicy.lifetimeMs(row.eventType, settings)
        val period = FreshnessPolicy.tickPeriodSeconds(config.mode, config.intervalSeconds)
        if (row.eventType == NotificationEventType.BATCH_LIVE || row.eventType == NotificationEventType.BATCH_END) {
            val batch = NotificationPayloads.decodeBatch(row.payloadJson) ?: return Result.Skip("PAYLOAD_INVALID")
            val singleType = if (row.eventType == NotificationEventType.BATCH_END)
                NotificationEventType.END_CONFIRMED else NotificationEventType.START_CONFIRMED
            val kept = mutableListOf<LiveNotificationPayload>()
            var earliestDeadline = Long.MAX_VALUE
            val deferred = mutableListOf<LiveNotificationPayload>()
            for (item in batch.items) {
                when (val result = checkItem(row, item, singleType, settings, period)) {
                    is Result.Ready -> {
                        kept += item
                        earliestDeadline = minOf(earliestDeadline, result.row.expiresAt)
                    }
                    Result.AwaitObservation -> deferred += item
                    is Result.Skip -> Unit
                }
            }
            if (kept.isEmpty() && deferred.isNotEmpty()) return Result.AwaitObservation
            if (kept.isEmpty()) return Result.Skip("BATCH_NO_LONGER_RELEVANT", NotificationOutboxStatus.EXPIRED)
            return Result.Ready(row.copy(payloadJson = NotificationPayloads.encodeBatch(batch.copy(items = kept)),
                expiresAt = minOf(row.expiresAt, earliestDeadline)), deferred)
        }
        val payload = NotificationPayloads.decodeLive(row.payloadJson)
        if (payload != null && row.streamerId != null) return checkItem(row, payload, row.eventType, settings, period)
        if (row.timelineNow(clock) - row.createdAt >= lifetime) return Result.Skip("NOTIFICATION_STALE", NotificationOutboxStatus.EXPIRED)
        if (row.streamerId != null) {
            val streamer = db.streamerDao().findById(row.streamerId)
            if (streamer == null || streamer.deletedAt != null) return Result.Skip("STREAMER_DELETED")
            if (!streamer.monitoringEnabled) return Result.Skip("MONITORING_DISABLED")
            if (row.eventType == NotificationEventType.BAN_ENTERED && !RoomBanCodec.isBanned(streamer.lastError))
                return Result.Skip("STATE_SUPERSEDED")
            if (row.eventType == NotificationEventType.BAN_LIFTED && RoomBanCodec.isBanned(streamer.lastError))
                return Result.Skip("STATE_SUPERSEDED")
        }
        row.problemKey?.let { key ->
            val problem = db.problemDao().get(key)
            if (problem != null && ((row.eventType == NotificationEventType.SYSTEM_PROBLEM && !problem.active) ||
                (row.eventType == NotificationEventType.SYSTEM_RECOVERED && problem.active)))
                return Result.Skip("STATE_SUPERSEDED")
        }
        return Result.Ready(row.copy(expiresAt = minOf(row.expiresAt, row.createdAt + lifetime)))
    }

    private suspend fun checkItem(row: NotificationOutboxEntity, item: LiveNotificationPayload,
        type: NotificationEventType, settings: NotificationTimelinessSettings, period: Int): Result {
        val now = clock.nowWall()
        val event = item.eventId?.takeIf { it.isNotBlank() }?.let { db.liveEventDao().findById(it) }
        val confirmedAt = item.confirmedAt ?: event?.eventConfirmedAt ?: row.createdAt
        val age = NotificationTimelinessPolicy.ageMs(row.timelineNow(clock), clock.nowElapsed(), clock.bootId(),
            confirmedAt, item.confirmedElapsed, item.confirmedBootId)
        val remaining = NotificationTimelinessPolicy.lifetimeMs(type, settings) - age
        if (remaining <= 0) return Result.Skip("NOTIFICATION_STALE", NotificationOutboxStatus.EXPIRED)
        val streamer = item.streamerStableId?.let { db.streamerDao().findByStableId(it) }
            ?: row.streamerId?.let { db.streamerDao().findById(it) }
        if (streamer == null || streamer.deletedAt != null) return Result.Skip("STREAMER_DELETED")
        if (!streamer.monitoringEnabled) return Result.Skip("MONITORING_DISABLED")
        val isLive = type != NotificationEventType.END_CONFIRMED
        if (isLive && streamer.confirmedLiveStatus != ConfirmedLiveStatus.LIVE) return Result.Skip("STATE_SUPERSEDED")
        if (!isLive && (streamer.lastLiveStartedAt ?: Long.MIN_VALUE) > confirmedAt) return Result.Skip("STATE_SUPERSEDED")
        val session = item.sessionStableId?.let { db.liveSessionDao().findByStableId(it) }
        if (isLive && item.sessionStableId != null && (session == null || session.endTime != null ||
                session.lifecycleState != com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState.ACTIVE))
            return Result.Skip("STATE_SUPERSEDED")
        if (type == NotificationEventType.TITLE_CHANGED && item.roomTitle != streamer.roomTitle)
            return Result.Skip("STATE_SUPERSEDED")
        val currentArea = listOfNotNull(streamer.parentAreaName?.takeIf { it.isNotBlank() },
            streamer.areaName?.takeIf { it.isNotBlank() }).joinToString("·")
        if (type == NotificationEventType.AREA_CHANGED && item.areaName != currentArea)
            return Result.Skip("STATE_SUPERSEDED")
        // Await the ordinary monitor rather than issuing a new network request for every queued item.
        if (isLive && (streamer.lastConfirmedAt == null || row.timelineNow(clock) - streamer.lastConfirmedAt > maxOf(30_000L, period * 2_000L)))
            return Result.AwaitObservation
        return Result.Ready(row.copy(expiresAt = minOf(row.expiresAt, row.timelineNow(clock) + remaining)))
    }
}
