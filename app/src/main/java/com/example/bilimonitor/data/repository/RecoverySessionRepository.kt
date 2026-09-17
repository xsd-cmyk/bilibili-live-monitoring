package com.example.bilimonitor.data.repository

import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.RecoveryFinishReason
import com.example.bilimonitor.data.local.RecoverySessionStatus
import com.example.bilimonitor.data.local.dao.RecoverySessionDao
import com.example.bilimonitor.data.local.entity.RecoverySessionEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 恢复会话（0.6.33 / 原规范 177「恢复检查模式」）。
 *
 * 原实现只调用了 `settleStaleRunning`：`recovery_session` 表**恒为空**，
 * "崩溃后进入恢复检查模式"这条能力实际不存在 —— 进程被杀之后，
 * 没有任何机制记录"这段时间没监控到"，也没有补检的起点。
 *
 * 本类补齐状态机闭环：
 * ```
 * 冷启动 → 作废上一轮的 REQUESTED → 新建 REQUESTED → CAS 到 RUNNING
 *        → 补检一次（MonitoringEngine.checkOnce("recovery")）
 *        → CAS 到终态（COMPLETED / FAILED），并如实结算 finishReason
 * ```
 * 与 `StartupRecovery` 的分工：StartupRecovery 负责"作废上一轮"，
 * 本类负责"开启并跑完本轮"。
 */
@Singleton
class RecoverySessionRepository @Inject constructor(
    private val recoverySessionDao: RecoverySessionDao,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val monitoringEngine: com.example.bilimonitor.background.MonitoringEngine,
    private val clock: AppClock
) {
    /**
     * 开启一次恢复检查并立即执行。
     *
     * @param monitorGeneration 由调用方提供，用于 CAS（代际变化时旧会话自动失效）。
     * @return true 表示本轮确实以 COMPLETED 收尾。
     */
    suspend fun runRecoveryCheck(monitorGeneration: Long): Boolean {
        val now = clock.nowWall()
        val sessionId = Ids.newId()

        // 1) 登记 REQUESTED（单实例约束由 idx_recovery_one_requested 的部分唯一索引保证）。
        val inserted = runCatching {
            recoverySessionDao.insert(
                RecoverySessionEntity(
                    recoverySessionId = sessionId,
                    runtimeInstanceId = runtimeLockRepository.runtimeInstanceId,
                    monitorGeneration = monitorGeneration,
                    status = RecoverySessionStatus.REQUESTED,
                    startedAt = now,
                    finishedAt = null,
                    finishReason = null
                )
            )
        }.getOrDefault(0L)
        if (inserted <= 0L) {
            // 已有并发的 REQUESTED/RUNNING：本轮让位，不重复补检。
            return false
        }

        // 2) CAS 到 RUNNING；失败说明本会话未被本进程接管（并发请求或状态已被改写），
        //    必须把这一行结算掉：否则残留的 REQUESTED 行会被
        //    idx_recovery_one_requested 挡住后续所有插入，恢复检查在本进程内静默失效。
        val running = runCatching { recoverySessionDao.casToRunning(sessionId, monitorGeneration) }
            .getOrDefault(0)
        if (running != 1) {
            val cancelled = runCatching {
                recoverySessionDao.casRequestedToTerminal(
                    sessionId, RecoverySessionStatus.CANCELLED,
                    RecoveryFinishReason.SUPERSEDED, clock.nowWall()
                )
            }.getOrDefault(0)
            if (cancelled == 0) {
                // 行已被别处推进到 RUNNING 或其他终态：按 RUNNING 再试一次结算，
                // 保证不会留下"半开"会话。
                runCatching {
                    recoverySessionDao.casToTerminal(
                        sessionId, RecoverySessionStatus.CANCELLED,
                        RecoveryFinishReason.SUPERSEDED, clock.nowWall()
                    )
                }
            }
            return false
        }

        // 3) 执行一次补检。失败（网络等）不算"恢复失败"，如实记为 FAILED 由用户可见。
        val completed = runCatching { monitoringEngine.checkOnce("recovery") }
            .getOrNull()
            ?.ran == true

        runCatching {
            recoverySessionDao.casToTerminal(
                sessionId,
                if (completed) RecoverySessionStatus.COMPLETED else RecoverySessionStatus.FAILED,
                if (completed) RecoveryFinishReason.HEALTHY else RecoveryFinishReason.FAILED,
                clock.nowWall()
            )
        }
        return completed
    }

    /** 作废上一轮残留的 REQUESTED（冷启动时调用）。 */
    suspend fun cancelStaleRequested(): Int =
        runCatching { recoverySessionDao.cancelStaleRequested(clock.nowWall()) }.getOrDefault(0)

    suspend fun recent(limit: Int = 20): List<RecoverySessionEntity> =
        runCatching { recoverySessionDao.recent(limit) }.getOrDefault(emptyList())
}
