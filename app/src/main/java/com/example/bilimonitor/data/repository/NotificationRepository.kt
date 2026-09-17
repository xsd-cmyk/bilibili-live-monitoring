package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.NotificationAggregateStatus
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.dao.LiveEventDao
import com.example.bilimonitor.data.local.dao.NotificationAggregateDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知域维护：窗口到期结算、崩溃恢复、投递恢复。
 * 事务边界遵守 0.6.10 / 0.6.34。
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val db: AppDatabase,
    private val aggregateDao: NotificationAggregateDao,
    private val outboxDao: NotificationOutboxDao,
    private val liveEventDao: LiveEventDao,
    private val streamerDao: StreamerDao,
    private val clock: AppClock,
    // 窗口到期补发也要看免打扰（原先这条路径完全不判，见 processWindowEnds 的注释）
    private val quietHoursRepository: QuietHoursRepository
) {

    /**
     * 窗口到达 windowEnd 的定案流程（0.6.10）：
     *  - 达到阈值：冻结 + Batch Outbox；
     *  - 未达阈值：RELEASED → CANCELLED → 逐条创建单事件 Outbox（顺序不可颠倒）。
     */
    suspend fun processWindowEnds(): Int {
        val now = clock.nowWall()
        val elapsed = clock.nowElapsed()
        val bootId = clock.bootId()
        val quiet = quietHoursActiveNow(now)
        val windows = aggregateDao.listInProgress()
        var processed = 0
        for (agg in windows) {
            val windowEnded = if (agg.bootId == bootId) {
                elapsed >= agg.windowEndElapsed
            } else {
                now >= agg.windowEndWall
            }
            if (!windowEnded) continue
            val handled = db.withTransaction {
                val fresh = aggregateDao.findById(agg.aggregateId) ?: return@withTransaction false
                if (fresh.status != NotificationAggregateStatus.COLLECTING &&
                    fresh.status != NotificationAggregateStatus.READY
                ) return@withTransaction false
                val bindings = aggregateDao.listActiveEvents(fresh.aggregateId)
                if (bindings.size >= fresh.threshold) {
                    aggregateDao.casStatus(fresh.aggregateId, fresh.status, NotificationAggregateStatus.FROZEN, null, bindings.size)
                    NotificationOutboxWriter.createBatchOutbox(db, clock, fresh.aggregateId, now, fresh.configVersion)
                } else {
                    // 1) 先释放绑定；2) 再取消窗口；3) 再建单事件 Outbox。
                    aggregateDao.releaseAll(fresh.aggregateId, now)
                    aggregateDao.casStatus(fresh.aggregateId, fresh.status, NotificationAggregateStatus.CANCELLED, null, bindings.size)
                    for (binding in bindings) {
                        val event = liveEventDao.findById(binding.eventId) ?: continue
                        val streamer = streamerDao.findById(event.streamerId) ?: continue
                        val payload = NotificationPayloads.live(
                            eventKey = Ids.eventKeyForEvent(event.eventId),
                            streamerStableId = streamer.stableId,
                            sessionStableId = event.sessionStableId,
                            eventId = event.eventId,
                            name = streamer.name,
                            title = streamer.roomTitle,
                            area = streamer.areaName,
                            url = streamer.liveUrl,
                            cover = streamer.coverUrl
                        )
                        val eventType = when (event.eventType) {
                            com.example.bilimonitor.data.local.LiveEventType.END -> NotificationEventType.END_CONFIRMED
                            com.example.bilimonitor.data.local.LiveEventType.LIVE_RECONFIRMED -> NotificationEventType.LIVE_RECONFIRMED
                            else -> NotificationEventType.START_CONFIRMED
                        }
                        // ★ 与直发路径对齐：命中免打扰就只留痕、不投递（见 quietHoursActiveNow）。
                        //   原先这里硬编码 enabled = true，于是"22:59:58 起窗、23:00:03 结算"
                        //   或"进程在窗口内被杀、下次 Tick 才结算"时，免打扰期间照样弹通知。
                        if (quiet) {
                            NotificationOutboxWriter.recordSuppressed(
                                db = db, eventKey = Ids.eventKeyForEvent(event.eventId),
                                streamerId = streamer.id, sourceEventId = event.eventId,
                                eventType = eventType, payloadJson = payload, now = now,
                                reason = NotificationOutboxWriter.SUPPRESSED_QUIET_HOURS,
                                configVersion = fresh.configVersion
                            )
                        } else {
                            NotificationOutboxWriter.createSingle(
                                db = db, clock = clock, event = event, streamerId = streamer.id,
                                eventType = eventType, payloadJson = payload, now = now,
                                enabled = true, configVersion = fresh.configVersion
                            )
                        }
                    }
                }
                true
            }
            if (handled) processed++
        }
        return processed
    }

    /**
     * 崩溃恢复（0.6.10）：status = CANCELLED 但仍有 active = 1 绑定的窗口，
     * 原子补齐 RELEASED 与单事件 Outbox，且不得重复创建。
     */
    suspend fun recoverCrashedAggregates(): Int {
        val ids = aggregateDao.findCancelledWithActiveBindings()
        if (ids.isEmpty()) return 0
        val now = clock.nowWall()
        val quiet = quietHoursActiveNow(now)
        var recovered = 0
        for (id in ids) {
            val ok = db.withTransaction {
                val bindings = aggregateDao.listActiveEvents(id)
                aggregateDao.releaseAll(id, now)
                for (binding in bindings) {
                    val event = liveEventDao.findById(binding.eventId) ?: continue
                    val streamer = streamerDao.findById(event.streamerId) ?: continue
                    val payload = NotificationPayloads.live(
                        eventKey = Ids.eventKeyForEvent(event.eventId),
                        streamerStableId = streamer.stableId,
                        sessionStableId = event.sessionStableId,
                        eventId = event.eventId,
                        name = streamer.name,
                        title = streamer.roomTitle,
                        area = streamer.areaName,
                        url = streamer.liveUrl,
                        cover = streamer.coverUrl
                    )
                    val eventType = when (event.eventType) {
                        com.example.bilimonitor.data.local.LiveEventType.END -> NotificationEventType.END_CONFIRMED
                        com.example.bilimonitor.data.local.LiveEventType.LIVE_RECONFIRMED -> NotificationEventType.LIVE_RECONFIRMED
                        else -> NotificationEventType.START_CONFIRMED
                    }
                    if (quiet) {
                        NotificationOutboxWriter.recordSuppressed(
                            db = db, eventKey = Ids.eventKeyForEvent(event.eventId),
                            streamerId = streamer.id, sourceEventId = event.eventId,
                            eventType = eventType, payloadJson = payload, now = now,
                            reason = NotificationOutboxWriter.SUPPRESSED_QUIET_HOURS
                        )
                    } else {
                        NotificationOutboxWriter.createSingle(
                            db = db, clock = clock, event = event, streamerId = streamer.id,
                            eventType = eventType, payloadJson = payload, now = now,
                            enabled = true
                        )
                    }
                }
                true
            }
            if (ok) recovered++
        }
        return recovered
    }

    /**
     * 启动/省电恢复（0.6.34 唯一写入者语句）：
     *  - 租约过期的 PROCESSING → DELIVERY_UNKNOWN + RETRY_WAIT/EXPIRED；
     *  - 从未被认领就已超期（`expiresAt <= now`）的 PENDING/RETRY_WAIT → EXPIRED。
     *
     * 后半句是必须的：`claimOutbox`/`findDue` 现在都带 `expiresAt > now`，
     * 过期行如果不在这里结算，就会永久停留在"投不出去也清不掉"的中间态。
     */
    suspend fun settleExpiredDeliveries(runtimeInstanceId: String): Int {
        val now = clock.nowWall()
        // 租约判定要 boot 感知（见 NotificationOutboxDao.findExpiredProcessing 的说明）
        val elapsed = clock.nowElapsed()
        val bootId = clock.bootId()
        return db.withTransaction {
            outboxDao.recoverOrphanDeliveryUnknown(now)
            val expiredPending = outboxDao.expireOverduePending(now)
            val settled = if (outboxDao.findExpiredProcessing(now, elapsed, bootId, 100).isEmpty()) {
                expiredPending
            } else {
                outboxDao.settleExpiredAttempts(now, elapsed, bootId, runtimeInstanceId)
                outboxDao.recoverExpiredProcessing(now, elapsed, bootId) + expiredPending
            }
            // ★ 上面这些语句只改 Outbox，而通知历史是**另一张表**：不回写它，用户就会在
            //   「通知历史」里看到一行永远停在"投递中"的记录（复查发现的缺陷）。
            db.notificationHistoryDao().reconcileTerminalStatus(now)
            settled
        }
    }

    /**
     * 此刻是否处于免打扰时段（2026 复查新增，供窗口结算路径使用）。
     *
     * 为什么在这里现读而不是从 Tick 的快照传进来：本函数也会被**省电 Worker** 与**冷启动**
     * 调用，那两条路径手上没有 Tick 快照；而免打扰是纯偏好设置，现读一次的代价可以忽略。
     * 主播级覆盖列 `overrideQuietHours` 至今没有任何写入点（见 BackupRepository 的说明），
     * 所以全局设置就是唯一输入，"按主播判定"目前不存在语义差异。
     *
     * ★ 读不出来时**按"不抑制"处理并留痕**：把读取失败当成"免打扰生效"会静默吞掉通知，
     *   当成"没事"又什么都不说 —— 两者都违反仓库硬规则，所以选择"照常投递 + 写一条错误日志"。
     */
    private suspend fun quietHoursActiveNow(now: Long): Boolean = runCatching {
        val q = quietHoursRepository.current()
        q.enabled && com.example.bilimonitor.domain.policy.QuietHoursPolicy.contains(
            nowMinutesOfDay = com.example.bilimonitor.domain.policy.QuietHoursPolicy.nowMinutesOfDay(now),
            startMinutes = q.startMinutes,
            endMinutes = q.endMinutes
        )
    }.getOrElse { e ->
        runCatching {
            db.logDao().insertAppError(
                com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                    errorId = Ids.newId(), operationId = null, occurredAt = now,
                    errorCode = com.example.bilimonitor.data.local.AppError.CONFIG_INVALID,
                    detail = "读取免打扰设置失败，本轮按「不抑制」投递：${'$'}{e.message}"
                )
            )
        }
        false
    }

    /** 通知能力恢复后：未过期且 FAILED 的行有限转 RETRY_WAIT（0.6.11，带上限）。 */
    suspend fun reviveFailed(now: Long, limit: Int = 50): Int = outboxDao.reviveFailed(now, limit)
}
