package com.example.bilimonitor.data.repository

import com.example.bilimonitor.data.local.LiveEventType
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.dao.LiveEventDao
import com.example.bilimonitor.data.local.dao.NotificationAggregateDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.domain.policy.NotificationRoutingPolicy
import com.example.bilimonitor.notify.NotificationClickHint
import javax.inject.Inject
import javax.inject.Singleton

/** Resolve the original event, not the mutable current screen or the first batch member. */
@Singleton
class NotificationDeepLinkResolver @Inject constructor(
    private val outboxDao: NotificationOutboxDao,
    private val liveEventDao: LiveEventDao,
    private val aggregateDao: NotificationAggregateDao,
    private val streamerDao: StreamerDao
) {
    sealed interface Target {
        data class Streamer(val streamerId: Long, val eventKey: String) : Target
        data class StreamerHistory(
            val streamerId: Long, val eventKey: String, val sessionStableId: String? = null
        ) : Target
        data class Batch(val eventKey: String) : Target
        data class Diagnostics(val eventKey: String) : Target
        data class Unknown(val eventKey: String) : Target
    }

    data class BatchMember(
        val eventKey: String, val name: String, val streamerId: Long?,
        val sessionStableId: String?, val url: String?, val hint: String
    )
    data class BatchDetails(val isEnd: Boolean, val members: List<BatchMember>)

    suspend fun resolve(eventKey: String?, hintJson: String? = null): Target? {
        if (eventKey.isNullOrBlank()) return null
        val outbox = outboxDao.findByEventKey(eventKey)
        val hint = NotificationClickHint.decode(hintJson, eventKey)
        val type = outbox?.eventType ?: hint?.eventType
        if (type != null) {
            when (NotificationRoutingPolicy.destination(type)) {
                NotificationRoutingPolicy.Destination.BATCH -> return Target.Batch(eventKey)
                NotificationRoutingPolicy.Destination.DIAGNOSTICS -> return Target.Diagnostics(eventKey)
                else -> Unit
            }
            val payload = outbox?.let { NotificationPayloads.decodeLive(it.payloadJson) }
            val stableId = payload?.streamerStableId ?: hint?.streamerStableId
            val streamer = outbox?.streamerId?.let { streamerDao.findById(it) }
                ?: stableId?.let { streamerDao.findByStableId(it) }
            if (streamer != null && streamer.deletedAt == null) {
                return targetFor(streamer.id, type == NotificationEventType.END_CONFIRMED,
                    eventKey, payload?.sessionStableId ?: hint?.sessionStableId)
            }
        }
        if (eventKey.startsWith("evt:")) {
            val event = liveEventDao.findById(eventKey.removePrefix("evt:"))
            val streamer = event?.let { streamerDao.findById(it.streamerId) }
            if (event != null && streamer != null && streamer.deletedAt == null) {
                return targetFor(streamer.id, event.eventType == LiveEventType.END,
                    eventKey, event.sessionStableId)
            }
        }
        if (eventKey.startsWith("agg:") && batchDetails(eventKey) != null) return Target.Batch(eventKey)
        if (eventKey.startsWith("problem:")) return Target.Diagnostics(eventKey)
        return Target.Unknown(eventKey)
    }

    suspend fun batchDetails(eventKey: String): BatchDetails? {
        val outbox = outboxDao.findByEventKey(eventKey)
        val payload = outbox?.let { NotificationPayloads.decodeBatch(it.payloadJson) }
        val aggregate = if (payload == null) aggregateDao.findById(eventKey.removePrefix("agg:")) else null
        val isEnd = outbox?.eventType == NotificationEventType.BATCH_END || payload?.kind == "END" ||
            aggregate?.aggregateKind == com.example.bilimonitor.data.local.NotificationAggregateKind.END
        val items = payload?.items ?: run {
            // Compatibility with older notifications whose outbox has already been removed.
            val aggregateId = eventKey.removePrefix("agg:")
            aggregateDao.listActiveEvents(aggregateId).mapNotNull { binding ->
                val event = liveEventDao.findById(binding.eventId) ?: return@mapNotNull null
                val streamer = streamerDao.findById(event.streamerId) ?: return@mapNotNull null
                LiveNotificationPayload("evt:${event.eventId}", streamer.stableId, event.sessionStableId,
                    event.eventId, streamer.name, streamer.roomTitle, streamer.areaName, streamer.liveUrl, null)
            }
        }
        if (items.isEmpty()) return null
        return BatchDetails(isEnd, items.map { item ->
            val streamer = item.streamerStableId?.let { streamerDao.findByStableId(it) }
                ?.takeIf { it.deletedAt == null }
            BatchMember(item.eventKey, item.streamerName, streamer?.id, item.sessionStableId,
                item.url, NotificationClickHint(item.eventKey,
                    if (isEnd) NotificationEventType.END_CONFIRMED else NotificationEventType.START_CONFIRMED,
                    item.streamerStableId, item.sessionStableId).encode())
        })
    }

    private fun targetFor(id: Long, end: Boolean, key: String, session: String?): Target =
        if (end) Target.StreamerHistory(id, key, session) else Target.Streamer(id, key)
}
