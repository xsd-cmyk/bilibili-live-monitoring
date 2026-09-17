package com.example.bilimonitor.data.repository

import com.example.bilimonitor.data.local.LiveEventType
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.dao.LiveEventDao
import com.example.bilimonitor.data.local.dao.NotificationAggregateDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知点击深链解析（原规范 20 / 236）。
 *
 * 原实现的链路是断的：`NotificationPoster` 会写入 `EXTRA_EVENT_KEY`，
 * `MainActivity` 把 URL 抛给系统浏览器后**从不读取 eventKey** ——
 * 点通知只是打开一个网页，应用内的具体位置从未被导航。
 *
 * 这里把 eventKey 解析成"应用内目标"：
 * ```
 * evt:{eventId}         → 开播 / 变化类：该场次所属主播的详情页
 *                         下播类（END_CONFIRMED）：该主播的**直播历史二级页**（用户要求）
 * agg:{aggregateId}     → 该聚合包含的首个主播详情页（批量通知没有单一目标）
 * problem:{problemKey}  → 无主播目标，交由调用方落到健康中心
 * ```
 *
 * ★ 落点必须按**事件类型**分流，不能只看 eventKey 前缀：`evt:` 前缀由开播与下播共用，
 *   而"点通知该进哪个页面"对两者是不同的（开播 → 详情页；下播 → 历史页）。
 *   事件类型不另造协议，回到既有数据里取：outbox 行的 eventType 优先，
 *   它被清理时退到 live_event 的 LiveEventType。
 *
 * 调用方只有 `MainActivity`（启动 Intent 与 onNewIntent 两条路径）；新增目标变体时
 * 唯一的 UI 落点是 `AppNavHost` 里对 Target 的那处 when。
 */
@Singleton
class NotificationDeepLinkResolver @Inject constructor(
    private val outboxDao: NotificationOutboxDao,
    private val liveEventDao: LiveEventDao,
    private val aggregateDao: NotificationAggregateDao
) {
    sealed interface Target {
        /** 打开某主播详情页（开播 / 变化类通知的应用内落点）。 */
        data class Streamer(val streamerId: Long, val eventKey: String) : Target

        /**
         * 打开某主播的**直播历史二级页**（下播通知专用，用户要求）。
         *
         * 为什么不复用 [Streamer]：`evt:` 前缀由开播类与下播类共用，而两类的落点不同 ——
         * 开播类要回详情页（用户此刻关心"在播什么"），下播类要看历史（关心"这一场播了多久"）。
         * 若直接把 [Streamer] 的语义改成历史页，开播通知在 `LiveRoomRedirectActivity` 打不开
         * 客户端 / 浏览器而回落到应用内（携带同一 `evt:` key）的那条路径会被一并改掉；
         * 反过来若让 UI 层自己按 eventType 猜，解析职责就漏到了导航层。
         * 因此加一个独立变体：由解析器判定，UI 只做"目标 → 路由"的映射
         * （`AppNavHost` → `Routes.historyStreamer`）。
         */
        data class StreamerHistory(val streamerId: Long, val eventKey: String) : Target

        /** 系统类通知：没有单一主播目标，应落到健康中心。 */
        data class Diagnostics(val eventKey: String) : Target

        /** 无法解析（事件已被清理等）：不导航，仅回到首页。 */
        data class Unknown(val eventKey: String) : Target
    }

    suspend fun resolve(eventKey: String?): Target? {
        if (eventKey.isNullOrBlank()) return null

        // 1) Outbox 上直接有 streamerId 时优先使用（最快、最准）。
        //    它的 eventType 就是"当初发这条通知时"记下的类型，下播判定直接读这里，
        //    不必再查一遍 live_event —— 单事件通知（含下播）走的就是这条路径。
        runCatching { outboxDao.findByEventKey(eventKey) }.getOrNull()?.let { outbox ->
            val streamerId = outbox.streamerId
            if (streamerId != null) {
                return targetFor(
                    streamerId = streamerId,
                    // 判据与 NotificationPoster.isOfflineEvent 保持同一口径（END_CONFIRMED）。
                    endOfLive = outbox.eventType == NotificationEventType.END_CONFIRMED,
                    eventKey = eventKey
                )
            }
        }

        return when {
            eventKey.startsWith("evt:") -> {
                val eventId = eventKey.removePrefix("evt:")
                val event = runCatching { liveEventDao.findById(eventId) }.getOrNull()
                if (event == null) {
                    Target.Unknown(eventKey)
                } else {
                    // outbox 行已被清理（过期 / 库重建）时的兜底：同一事实在 live_event 上
                    // 还有一份记录 —— 下播事件是 LiveEventType.END。
                    // 这里不再判 streamerId 是否为空：LiveEventEntity.streamerId 本就是非空列。
                    targetFor(
                        streamerId = event.streamerId,
                        endOfLive = event.eventType == LiveEventType.END,
                        eventKey = eventKey
                    )
                }
            }

            eventKey.startsWith("agg:") -> {
                val aggregateId = eventKey.removePrefix("agg:")
                // 批量开播通知包含多位主播：取聚合内首个仍活跃的绑定，打开它的详情页。
                // ★ 批量通知只可能是开播类（BATCH_LIVE，通知点击也走客户端跳板），
                //   所以这里固定落详情页，**不参与**下播的历史页分流 —— 保持原样。
                val firstEventId = runCatching {
                    aggregateDao.listActiveEvents(aggregateId).firstOrNull()?.eventId
                }.getOrNull()
                val streamerId = firstEventId
                    ?.let { runCatching { liveEventDao.findById(it) }.getOrNull()?.streamerId }
                if (streamerId != null) Target.Streamer(streamerId, eventKey)
                else Target.Unknown(eventKey)
            }

            eventKey.startsWith("problem:") -> Target.Diagnostics(eventKey)

            else -> Target.Unknown(eventKey)
        }
    }

    /**
     * 目标分流：下播类 → 该主播的直播历史二级页；其余（开播 / 变化类）→ 详情页。
     *
     * 抽成一个函数是为了让上面两条解析路径（outbox 快路径、live_event 兜底路径）
     * 用**同一个判据**，避免将来只改其中一条、两条路径对"下播"的理解分叉。
     */
    private fun targetFor(streamerId: Long, endOfLive: Boolean, eventKey: String): Target =
        if (endOfLive) Target.StreamerHistory(streamerId, eventKey)
        else Target.Streamer(streamerId, eventKey)
}
