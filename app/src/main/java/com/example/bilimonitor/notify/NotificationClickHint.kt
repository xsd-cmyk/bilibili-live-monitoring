package com.example.bilimonitor.notify

import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.repository.NotificationPayloads
import kotlinx.serialization.Serializable

/** Small, version-compatible navigation snapshot; never includes credentials or batch contents. */
@Serializable
data class NotificationClickHint(
    val eventKey: String,
    val eventType: NotificationEventType,
    val streamerStableId: String? = null,
    val sessionStableId: String? = null
) {
    fun encode(): String = NotificationPayloads.json.encodeToString(serializer(), this)

    companion object {
        const val EXTRA = "notification_click_hint"

        fun decode(raw: String?, eventKey: String): NotificationClickHint? {
            if (raw == null || raw.length > 8192) return null
            return runCatching {
                NotificationPayloads.json.decodeFromString(serializer(), raw)
            }.getOrNull()?.takeIf { it.eventKey == eventKey }
        }
    }
}
