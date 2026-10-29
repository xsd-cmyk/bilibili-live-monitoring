package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.NotificationEventType

/** Shared by the notification PendingIntent and the in-app resolver. */
object NotificationRoutingPolicy {
    enum class Destination { LIVE_ROOM, HISTORY, BATCH, STREAMER, DIAGNOSTICS }

    fun destination(type: NotificationEventType): Destination = when (type) {
        NotificationEventType.START_CONFIRMED, NotificationEventType.LIVE_RECONFIRMED,
        NotificationEventType.TITLE_CHANGED, NotificationEventType.AREA_CHANGED -> Destination.LIVE_ROOM
        NotificationEventType.END_CONFIRMED -> Destination.HISTORY
        NotificationEventType.BATCH_LIVE, NotificationEventType.BATCH_END -> Destination.BATCH
        NotificationEventType.BAN_ENTERED, NotificationEventType.BAN_LIFTED -> Destination.STREAMER
        NotificationEventType.SYSTEM_PROBLEM, NotificationEventType.SYSTEM_RECOVERED -> Destination.DIAGNOSTICS
    }
}
