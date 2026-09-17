package com.example.bilimonitor.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 通知渠道（原规范 12.1）：live / monitor_service / system。 */
@Singleton
object NotificationChannels {
    const val LIVE = "live"
    const val SERVICE = "monitor_service"
    const val SYSTEM = "system"

    fun ensureCreated(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(LIVE, "直播状态通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "主播开播、关播通知"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE, "后台监控状态", NotificationManager.IMPORTANCE_MIN).apply {
                description = "前台监控服务常驻通知"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SYSTEM, "系统问题通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "网络异常、权限缺失等系统级问题"
            }
        )
    }
}

/** 通知点击跳转 payload（0.6.41）：destination 由应用内定义，UI 禁止拼接不受信任 URL。 */
data class NotificationNavigationPayload(
    val eventKey: String,
    val destination: String,
    val streamerStableId: String?,
    val sessionStableId: String?,
    val url: String?
)

/** URL 策略校验（0.6.41）：仅允许 https 且 host 属于 bilibili 域。 */
object UrlPolicy {
    private val allowedHosts = setOf(
        "live.bilibili.com", "www.bilibili.com", "b23.tv", "space.bilibili.com"
    )

    fun validate(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val uri = java.net.URI(url.trim())
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme == "https" && host != null && host in allowedHosts) url.trim() else null
        }.getOrNull()
    }
}

@Singleton
class NotificationFactory @Inject constructor(@ApplicationContext private val context: Context) {
    init {
        NotificationChannels.ensureCreated(context)
    }

    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()
}
