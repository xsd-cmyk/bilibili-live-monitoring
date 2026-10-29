package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.NotificationEventType

data class NotificationTimelinessSettings(
    val liveMinutes: Int = 5,
    val endMinutes: Int = 10,
    val changeMinutes: Int = 3
) {
    fun isValid(): Boolean = listOf(liveMinutes, endMinutes, changeMinutes).all { it in 1..1440 }
}

object NotificationTimelinessPolicy {
    fun lifetimeMs(type: NotificationEventType, settings: NotificationTimelinessSettings): Long =
        60_000L * when (type) {
            NotificationEventType.START_CONFIRMED, NotificationEventType.LIVE_RECONFIRMED,
            NotificationEventType.BATCH_LIVE -> settings.liveMinutes
            NotificationEventType.END_CONFIRMED, NotificationEventType.BATCH_END -> settings.endMinutes
            NotificationEventType.TITLE_CHANGED, NotificationEventType.AREA_CHANGED -> settings.changeMinutes
            NotificationEventType.BAN_ENTERED, NotificationEventType.BAN_LIFTED -> 10
            NotificationEventType.SYSTEM_PROBLEM, NotificationEventType.SYSTEM_RECOVERED -> 5
        }

    fun ageMs(nowWall: Long, nowElapsed: Long, bootId: String,
        confirmedAt: Long, confirmedElapsed: Long?, confirmedBootId: String?): Long =
        if (confirmedBootId == bootId && confirmedElapsed != null) {
            (nowElapsed - confirmedElapsed).coerceAtLeast(0L)
        } else (nowWall - confirmedAt).coerceAtLeast(0L)
}
