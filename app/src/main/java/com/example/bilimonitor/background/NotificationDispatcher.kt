package com.example.bilimonitor.background

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.DeliveryAttemptResult
import com.example.bilimonitor.data.local.NotificationAggregateStatus
import com.example.bilimonitor.data.local.NotificationHistoryDeliveryStatus
import com.example.bilimonitor.data.local.NotificationOutboxStatus
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.NotificationAggregateDao
import com.example.bilimonitor.data.local.dao.NotificationHistoryDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.NotificationDeliveryAttemptEntity
import com.example.bilimonitor.data.local.entity.NotificationHistoryEntity
import com.example.bilimonitor.data.repository.RuntimeLockRepository
import com.example.bilimonitor.notify.DeliveryOutcome
import com.example.bilimonitor.notify.NotificationPoster
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通知 Dispatcher（0.6.11 事务边界）：
 * 事务 A claim + lease + Attempt(started) → 事务外 notify() → 事务 B Attempt(finished) + Outbox CAS。
 */
@Singleton
class NotificationDispatcher @Inject constructor(
    private val db: AppDatabase,
    private val outboxDao: NotificationOutboxDao,
    private val historyDao: NotificationHistoryDao,
    private val aggregateDao: NotificationAggregateDao,
    private val logDao: LogDao,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val poster: NotificationPoster,
    private val clock: AppClock
) {
    /**
     * 投递本轮到期通知。
     *
     * **调用点在 Tick 互斥锁之外**（MonitoringEngine：投递含封面下载，放锁内会把手动刷新、
     * Worker、恢复补检全堵住），而窗口到期那条补投路径还在 appScope 里另起协程 ——
     * 所以**两次 dispatchDue 并发是可能的**：正确性靠 [NotificationOutboxDao.claimOutbox]
     * 的单条原子条件 UPDATE（`status IN ('PENDING','RETRY_WAIT') AND nextAttemptAt <= :now`
     * 且 `attemptCount = attemptCount + 1`）保证，同一行只会有一个执行者抢到。
     * 也正因为不持锁，本函数必须快速返回：
     *  ① 开头先做一次"过期在途行回收"（见 [recoverInFlight]），
     *  ② 单条封面下载收紧到 5s（见 NotificationPoster），
     *  ③ 整个函数有 [DISPATCH_BUDGET_MS] 总预算，超时直接返回，
     *     未处理的行保持原状态留到下一轮（**不标记失败**）。
     */
    suspend fun dispatchDue(limit: Int = 20) {
        val now = clock.nowWall()
        // 运行期回收：原先只有冷启动与省电 Worker 会做，实时模式下进程可存活数天，
        // 一次中断的投递就会永久卡在 PROCESSING（findDue 不再取它）→ 通知静默丢失。
        recoverInFlight(now)

        val budget = withTimeoutOrNull(DISPATCH_BUDGET_MS) {
            val due = outboxDao.findDue(now, limit)
            for (outbox in due) {
                try {
                    deliverOne(outbox.outboxId)
                } catch (e: CancellationException) {
                    // 总预算耗尽：本轮到此为止。已 claim 的行由下一轮的 recoverInFlight 结算。
                    throw e
                } catch (e: Exception) {
                    // **不能只写日志**：行还停在 PROCESSING，必须显式放回队列，
                    // 否则这条通知在这一进程生命周期内再也不会被投递。
                    // ★ 这段"放回队列"是挂起写，必须包 NonCancellable（复查发现的缺陷）：
                    //   协程若正好在此时被取消（服务停止、进程退出流程），挂起写会被直接跳过 ——
                    //   行留在 PROCESSING，findDue 不再取它，只能干等 30 秒租约回收。
                    val releaseResult = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching {
                            db.withTransaction {
                                outboxDao.releaseProcessing(
                                    outboxId = outbox.outboxId,
                                    now = clock.nowWall(),
                                    retryAt = clock.nowWall() + RETRY_AFTER_FAILURE_MS,
                                    lastError = "DISPATCH_EXCEPTION",
                                    // ★ 与事务 B 的 FAILED 分支同一口径（第二轮审查发现）：
                                    //   原先这里只看 expiresAt，一条持续抛异常的行走会以固定 30 秒间隔
                                    //   一直重试到 24 小时 TTL（实时模式约 480 次/天），每次还写一条错误日志。
                                    maxAttempts = MAX_ATTEMPTS
                                )
                                outboxDao.settleAttemptByOutbox(
                                    outboxId = outbox.outboxId,
                                    now = clock.nowWall(),
                                    error = e.message ?: e.javaClass.simpleName
                                )
                            }
                        }
                    }
                    val detail = buildString {
                        append("dispatch failed: ").append(e.message)
                        releaseResult.exceptionOrNull()?.let { append("; release failed: ").append(it.message) }
                    }
                    runCatching {
                        logDao.insertAppError(
                            com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                                errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                                errorCode = com.example.bilimonitor.data.local.AppError.NOTIFICATION_UNAVAILABLE,
                                detail = detail.take(MAX_ERROR_DETAIL)
                            )
                        )
                    }
                }
            }
        }
        if (budget == null) {
            runCatching {
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = com.example.bilimonitor.data.local.AppError.NOTIFICATION_UNAVAILABLE,
                        detail = "dispatch budget exhausted (${DISPATCH_BUDGET_MS}ms)，本轮剩余通知留待下一轮"
                    )
                )
            }
        }
    }

    /**
     * 运行期回收：把**租约已过期**的 PROCESSING 行结算掉（DELIVERY_UNKNOWN → RETRY_WAIT/EXPIRED），
     * 并把从未被认领就已超期的 PENDING/RETRY_WAIT 行置为 EXPIRED。
     *
     * 与 `NotificationRepository.settleExpiredDeliveries` 是同一组语句；
     * 这里直接走 DAO 而不是注入仓库，是为了避免 Dispatcher ↔ Repository 的循环依赖，
     * 同时保证"投递前先回收"这件事不依赖调用方是否记得做。
     */
    private suspend fun recoverInFlight(now: Long) {
        // 租约判定要 boot 感知，两个量都在这里取一次（见 findExpiredProcessing 的说明）
        val elapsed = clock.nowElapsed()
        val bootId = clock.bootId()
        runCatching {
            db.withTransaction {
                outboxDao.expireOverduePending(now)
                // 孤儿 DELIVERY_UNKNOWN 行（当前无写入者，属防御）也要纳入闭环，见 DAO 的说明
                outboxDao.recoverOrphanDeliveryUnknown(now)
                if (outboxDao.findExpiredProcessing(now, elapsed, bootId, RECOVER_LIMIT).isNotEmpty()) {
                    outboxDao.settleExpiredAttempts(
                        now, elapsed, bootId, runtimeLockRepository.runtimeInstanceId
                    )
                    outboxDao.recoverExpiredProcessing(now, elapsed, bootId)
                }
                // 这三条语句只改 Outbox，而通知历史是另一张表：不回写它，用户就会看到
                // 一行永远停在"投递中"的记录（复查发现的缺陷）。每轮投递前顺手对齐一次。
                historyDao.reconcileTerminalStatus(now)
            }
        }.onFailure { e ->
            runCatching {
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = com.example.bilimonitor.data.local.AppError.DATABASE_ERROR,
                        detail = "recover in-flight failed: ${e.message}".take(MAX_ERROR_DETAIL)
                    )
                )
            }
        }
    }

    private suspend fun deliverOne(outboxId: String) {
        val now = clock.nowWall()
        val runtimeInstanceId = runtimeLockRepository.runtimeInstanceId
        val deliveryAttemptId = Ids.newId()
        val attemptId = Ids.newId()
        val leaseMs = LEASE_MS

        // 事务 A：原子抢占 + Attempt(started)（245.10）。
        val claimed = db.withTransaction {
            val rows = outboxDao.claimOutbox(
                outboxId = outboxId, now = now,
                leaseUntilWall = now + leaseMs,
                leaseUntilElapsed = clock.nowElapsed() + leaseMs,
                bootId = clock.bootId(),
                workerInstanceId = runtimeInstanceId,
                deliveryAttemptId = deliveryAttemptId
            )
            if (rows > 0) {
                // 不用 `!!`：claim 与这里之间若发生清理（终态行被删）或软删除取消，
                // 行可能已经不在了 —— 那种情况下本次投递直接放弃（返回 null 视作未 claim），
                // 而不是把 NPE 抛到 Tick 里。
                val outbox = outboxDao.findById(outboxId)
                if (outbox != null) {
                    outboxDao.insertAttempt(
                        NotificationDeliveryAttemptEntity(
                            attemptId = attemptId,
                            outboxId = outboxId,
                            eventKey = outbox.eventKey,
                            notificationId = outbox.notificationId,
                            workerInstanceId = runtimeInstanceId,
                            deliveryAttemptId = deliveryAttemptId,
                            startedAt = now,
                            finishedAt = null,
                            result = null,
                            recoveredBy = null,
                            error = null
                        )
                    )
                    if (historyDao.findByOutboxId(outboxId) == null) {
                        historyDao.insert(
                            NotificationHistoryEntity(
                                historyId = Ids.newId(),
                                eventKey = outbox.eventKey,
                                outboxId = outboxId,
                                attemptId = attemptId,
                                notificationId = outbox.notificationId,
                                streamerStableId = outbox.streamerId?.let { db.streamerDao().findById(it)?.stableId },
                                aggregateId = outbox.aggregateId,
                                deliveryStatus = NotificationHistoryDeliveryStatus.PROCESSING,
                                attemptCount = outbox.attemptCount,
                                deliveredAt = null,
                                reason = null,
                                recordedAt = now,
                                updatedAt = now,
                                detail = null
                            )
                        )
                    }
                }
            }
            rows > 0
        }
        if (!claimed) return

        val outbox = outboxDao.findById(outboxId) ?: return

        // 事务外：真正调用 notify()。
        val outcome = poster.post(outbox)

        // 事务 B：Attempt(finished) + Outbox CAS 回写。
        db.withTransaction {
            val finishedAt = clock.nowWall()
            outboxDao.finishAttemptRow(attemptId, finishedAt, outcome.result, outcome.error)
            val attemptNo = outbox.attemptCount
            val (newStatus, sentAt, nextAttemptAt, lastError) = when (outcome.result) {
                DeliveryAttemptResult.SENT -> {
                    if (outbox.aggregateId != null) {
                        // eventCount 是派生缓存，权威值 = COUNT(notification_aggregate_event)。
                        // 原实现固定写 0，使已投递聚合在界面/统计里显示事件数为 0。
                        //
                        // 这里**不做容错**：写错一个"看起来正常"的 0 会把缺陷藏起来
                        // （正是原来那个 bug 的形态）。查询失败就让事务整体失败、
                        // Outbox 保持 PROCESSING，由启动恢复按 DELIVERY_UNKNOWN 重试。
                        val actualEventCount = aggregateDao.countActiveEvents(outbox.aggregateId)
                        val aggregateMoved = aggregateDao.casStatus(
                            outbox.aggregateId, NotificationAggregateStatus.FROZEN,
                            NotificationAggregateStatus.DISPATCHED, finishedAt, actualEventCount
                        )
                        // ★ 与下面 Outbox 的 CAS 同一条纪律：回写没生效必须留痕。原先这里丢弃返回值，
                        //   聚合状态与事实不符时（例如投递成功后聚合仍停在 FROZEN）整条链路不留任何痕迹。
                        //   注意"已经是 DISPATCHED"是**正常**形态：投递成功但被记成 DELIVERY_UNKNOWN
                        //   之后重发，第二次 CAS 必然落空（幂等重发），这种情况不能报成错误。
                        if (aggregateMoved == 0) {
                            val current = runCatching {
                                aggregateDao.findById(outbox.aggregateId)?.status
                            }.getOrNull()
                            if (current != NotificationAggregateStatus.DISPATCHED) {
                                runCatching {
                                    logDao.insertAppError(
                                        com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                                            errorId = Ids.newId(), operationId = null, occurredAt = finishedAt,
                                            errorCode = com.example.bilimonitor.data.local.AppError.NOTIFICATION_UNAVAILABLE,
                                            detail = ("聚合 CAS miss: aggregate=" + outbox.aggregateId +
                                                " status=" + current).take(MAX_ERROR_DETAIL)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    Quad(NotificationOutboxStatus.SENT, finishedAt, null, null)
                }
                DeliveryAttemptResult.DELIVERY_UNKNOWN -> {
                    if (finishedAt > outbox.expiresAt) {
                        Quad(NotificationOutboxStatus.EXPIRED, null, null, outcome.error)
                    } else {
                        Quad(NotificationOutboxStatus.RETRY_WAIT, null, finishedAt + backoff(attemptNo), outcome.error)
                    }
                }
                DeliveryAttemptResult.FAILED -> {
                    if (finishedAt > outbox.expiresAt) {
                        Quad(NotificationOutboxStatus.EXPIRED, null, null, outcome.error)
                    } else if (attemptNo >= MAX_ATTEMPTS) {
                        Quad(NotificationOutboxStatus.FAILED, null, null, outcome.error)
                    } else {
                        Quad(NotificationOutboxStatus.RETRY_WAIT, null, finishedAt + backoff(attemptNo), outcome.error)
                    }
                }
            }
            // CAS 返回 0 行说明这一行已被别处改写（收到了 SENT 又崩、被清理取消等）——
            // 原实现丢弃返回值，于是"回写没生效"这件事在整条链路上不留任何痕迹。
            val written = outboxDao.finishAttempt(
                outboxId = outboxId,
                deliveryAttemptId = deliveryAttemptId,
                newStatus = newStatus,
                sentAt = sentAt,
                nextAttemptAt = nextAttemptAt,
                lastError = lastError
            )
            if (written == 0) {
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = finishedAt,
                        errorCode = com.example.bilimonitor.data.local.AppError.NOTIFICATION_UNAVAILABLE,
                        detail = "finishAttempt CAS miss: outbox=$outboxId want=$newStatus".take(MAX_ERROR_DETAIL)
                    )
                )
            }
            historyDao.updateByOutboxId(
                outboxId = outboxId,
                status = when (newStatus) {
                    NotificationOutboxStatus.SENT -> NotificationHistoryDeliveryStatus.SENT
                    NotificationOutboxStatus.EXPIRED -> NotificationHistoryDeliveryStatus.EXPIRED
                    NotificationOutboxStatus.RETRY_WAIT -> NotificationHistoryDeliveryStatus.PENDING
                    else -> NotificationHistoryDeliveryStatus.FAILED
                },
                attemptCount = attemptNo,
                detail = lastError,
                now = finishedAt
            )
        }
    }

    private fun backoff(attempt: Int): Long =
        (BACKOFF_BASE_MS * (1L shl attempt.coerceAtMost(6))).coerceAtMost(MAX_BACKOFF_MS)

    data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        const val LEASE_MS = 30_000L
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_BASE_MS = 60_000L
        const val MAX_BACKOFF_MS = 60 * 60 * 1000L

        /**
         * 一次 `dispatchDue` 的总时间预算。
         *
         * 它不在 Tick 互斥锁内（见 [dispatchDue]），但**被这一轮 tick 等待**：
         * 20 条 × 封面下载 5s 最坏就是 100s，那期间本轮 tick 迟迟不结束、下一轮被无限推迟，
         * 手动刷新与省电 Worker 也只能排在同一个 Room 执行器后面，表现为"监控卡住"。
         * 给一个总预算，超时就返回，
         * 剩下的行保持原状态（PENDING/RETRY_WAIT 下一轮照常取；已 claim 的靠下一轮回收）。
         */
        const val DISPATCH_BUDGET_MS = 15_000L

        /** 单轮回收扫描上限（与启动恢复保持一致）。 */
        const val RECOVER_LIMIT = 100

        /** 投递抛异常后的重试间隔：短一点，让瞬时故障尽快重来。 */
        const val RETRY_AFTER_FAILURE_MS = 30_000L

        /** 落库的日志 detail 上限，防止一条超长 message 把日志表与诊断包撑大。 */
        const val MAX_ERROR_DETAIL = 500
    }
}
