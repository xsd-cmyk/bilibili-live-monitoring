package com.example.bilimonitor.data.repository

import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.DataConfidence
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.DataSource
import com.example.bilimonitor.data.local.GapReason
import com.example.bilimonitor.data.local.GapScope
import com.example.bilimonitor.data.local.IntervalStatus
import com.example.bilimonitor.data.local.LiveEventType
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.data.local.ReliableIntervalInvalidReason
import com.example.bilimonitor.data.local.dao.ConfigDao
import com.example.bilimonitor.data.local.dao.LiveEventDao
import com.example.bilimonitor.data.local.dao.LiveSessionDao
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.MonitoringGapDao
import com.example.bilimonitor.data.local.dao.ReliableIntervalDao
import com.example.bilimonitor.data.local.dao.RuntimeLockDao
import com.example.bilimonitor.data.local.dao.StatusHistoryDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.entity.LiveEventEntity
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import com.example.bilimonitor.data.local.entity.MonitoringErrorLogEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapStreamerEntity
import com.example.bilimonitor.data.local.entity.ReliableMonitorIntervalEntity
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.StatusHistoryEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.local.entity.StreamerPendingTransitionEntity
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.domain.model.ApplyObservationResult
import com.example.bilimonitor.domain.model.DetectedTransition
import com.example.bilimonitor.domain.model.MonitoringLease
import com.example.bilimonitor.domain.model.RemoteLiveRoom
import com.example.bilimonitor.domain.model.StreamerObservation
import com.example.bilimonitor.domain.policy.FreshnessPolicy
import com.example.bilimonitor.domain.policy.StateConfirmationPolicy
import androidx.room.withTransaction
import android.database.sqlite.SQLiteConstraintException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Tick 携带的通知策略快照（0.6.47.3：确认次数与通知开关，Tick 期间不重读配置）。 */
data class StreamerNotificationPolicy(
    val notifyStart: Boolean,
    val notifyEnd: Boolean,
    val aggregationEnabled: Boolean,
    val aggregationThreshold: Int,
    val aggregationWindowSeconds: Int,
    /** 直播标题变化通知（默认关闭，用户显式开启才发）。 */
    val notifyTitleChange: Boolean = false,
    /** 直播分区变化通知（默认关闭）。 */
    val notifyAreaChange: Boolean = false,
    /**
     * 当前是否处于免打扰时段（原规范 22.3）。
     * 由引擎在 Tick 内用快照中的时段 + 墙上时间算出，Tick 期间不再重算。
     */
    val quietHoursActive: Boolean = false
)

/** 监控租约管理（0.6.35 CAS 模板）。 */
@Singleton
class RuntimeLockRepository @Inject constructor(
    private val runtimeLockDao: RuntimeLockDao,
    private val clock: AppClock
) {
    val runtimeInstanceId: String = "rt-" + UUID.randomUUID().toString()

    /** 获取或接管租约；成功返回携带新 monitorGeneration/fencingToken 的租约。 */
    suspend fun acquireLease(leaseDurationMs: Long = LEASE_DURATION_MS): MonitoringLease? {
        val now = clock.nowWall()
        val leaseId = "lease-" + UUID.randomUUID().toString()
        val fencingToken = "ft-" + UUID.randomUUID().toString()
        val rows = runtimeLockDao.acquireOrTakeoverLease(
            leaseId = leaseId,
            runtimeInstanceId = runtimeInstanceId,
            newFencingToken = fencingToken,
            leaseUntilWall = now + leaseDurationMs,
            leaseUntilElapsed = clock.nowElapsed() + leaseDurationMs,
            bootId = clock.bootId(),
            now = now
        )
        if (rows == 0) return null
        val lock = runtimeLockDao.get() ?: return null
        return MonitoringLease(
            leaseId = leaseId,
            runtimeInstanceId = runtimeInstanceId,
            monitorGeneration = lock.monitorGeneration,
            fencingToken = fencingToken,
            leaseUntilWall = now + leaseDurationMs,
            leaseUntilElapsed = clock.nowElapsed() + leaseDurationMs,
            bootId = clock.bootId()
        )
    }

    /** 心跳续期：0 行 = 租约已被接管（LEASE_LOST）。 */
    suspend fun renew(lease: MonitoringLease, leaseDurationMs: Long = LEASE_DURATION_MS): Boolean {
        val now = clock.nowWall()
        val rows = runtimeLockDao.renewLease(
            leaseId = lease.leaseId,
            fencingToken = lease.fencingToken,
            newLeaseUntilWall = now + leaseDurationMs,
            newLeaseUntilElapsed = clock.nowElapsed() + leaseDurationMs,
            now = now
        )
        return rows > 0
    }

    suspend fun release(lease: MonitoringLease) {
        runtimeLockDao.releaseLease(lease.leaseId, clock.nowWall())
    }

    suspend fun enterMaintenance(mode: MaintenanceMode): Boolean =
        runtimeLockDao.enterMaintenance(mode, clock.nowWall()) > 0

    /**
     * 用户主动发起的维护（导出/备份/恢复/清理）：允许夺走有效期内的监控租约。
     * 详见 [RuntimeLockDao.enterMaintenanceForcing] 的说明。
     */
    suspend fun takeOverForMaintenance(mode: MaintenanceMode): Boolean =
        runtimeLockDao.enterMaintenanceForcing(mode, clock.nowWall()) > 0
    suspend fun exitMaintenance(mode: MaintenanceMode): Boolean =
        runtimeLockDao.exitMaintenance(mode, clock.nowWall()) > 0

    /** 当前监控代际（恢复检查会话的 CAS 依据）。 */
    suspend fun monitorGeneration(): Long = runtimeLockDao.currentMonitorGeneration()

    companion object {
        const val LEASE_DURATION_MS = 90_000L
    }
}

@Singleton
class MonitorRepository @Inject constructor(
    private val db: AppDatabase,
    private val streamerDao: StreamerDao,
    private val liveSessionDao: LiveSessionDao,
    private val liveEventDao: LiveEventDao,
    private val statusHistoryDao: StatusHistoryDao,
    private val reliableIntervalDao: ReliableIntervalDao,
    private val monitoringGapDao: MonitoringGapDao,
    private val liveSessionTitleDao: com.example.bilimonitor.data.local.dao.LiveSessionTitleDao,
    private val liveSessionAreaDao: com.example.bilimonitor.data.local.dao.LiveSessionAreaDao,
    private val configDao: ConfigDao,
    private val logDao: LogDao,
    private val clock: AppClock
) {

    /**
     * 观测失败留痕的**边沿抑制器**（缺陷 3：无去重 ⇒ 98 条/分钟）。
     *
     * 为什么加在这里而不是"查库判断有没有记过"：失败路径（全网故障时每轮 98 次）
     * 上最不该加的是一次额外的 DB 查询；纯内存指纹的代价只是"进程重启后重新记一条"。
     * 为什么 key 是主播 + 原因是分：见 [com.example.bilimonitor.domain.policy.ErrorLogEdgeGate]。
     *
     * 与引擎里那个 `roundFailureLogGate`（汇总日志）**互相独立**：两者抑制的是不同粒度的
     * 记录（每人一行 vs 每轮一行），共用一张表会互相冲掉对方的指纹。
     */
    private val failureLogGate = com.example.bilimonitor.domain.policy.ErrorLogEdgeGate()

    /**
     * 写入 / 清除某位主播的「封禁中」标记（用户诉求 1）。
     *
     * ★ 独立性：这个方法**只**动 `streamer.lastError` 与 `updatedAt`，
     *   - 不碰 `confirmedLiveStatus`（封禁不是一种"未开播"）；
     *   - 不碰 `observationSequence` / 代际 / fencing（不参与 CAS 状态机）；
     *   - 不写 `status_history` / `live_event` / `live_session`（不影响历史场次）；
     *   - 因此也**不可能**产生开播/下播通知。
     *
     * 标记内容由 [com.example.bilimonitor.domain.policy.RoomBanCodec] 编码；
     * `state == null` 表示解除封禁（把 `lastError` 清回 NULL）。
     */
    suspend fun applyBanState(streamerId: Long, state: com.example.bilimonitor.domain.policy.RoomBanState?): Boolean {
        val now = clock.nowWall()
        val encoded = state?.let { com.example.bilimonitor.domain.policy.RoomBanCodec.encode(it) }
        return streamerDao.updateBanState(streamerId, encoded, now) > 0
    }

    /**
     * 统一观察应用入口：非转换观察（刷新 lastCheckedAt/盲区/计数）。
     * 转换观察走 applyConfirmedObservationAtomically。
     */
    suspend fun applyObservationOnly(
        observation: StreamerObservation,
        remote: RemoteLiveRoom?,
        counting: StateConfirmationPolicy.Decision.Counting?,
        observationSequence: Long,
        monitorGeneration: Long,
        fencingToken: String,
        policy: StreamerNotificationPolicy? = null,
        configVersion: Long = 0L,
        /**
         * 本次观察的失败原始信号（可空：转换观察/成功观察不需要）。
         * 只用于错误日志的 detail 与错误码映射，不参与任何状态判定。
         */
        callFailure: com.example.bilimonitor.domain.policy.CallFailure? = null,
        /**
         * 本轮配置下**实际**的检查周期（秒），用于数据新鲜度阈值的协调
         * （见 [FreshnessPolicy.effectiveThresholdSeconds]：有效阈值 = max(配置阈值, 2 × 周期)）。
         *
         * 为什么必须由调用方传入、不能在这里写死配置值：省电模式的调度周期被 clamp 到 15–240 分钟，
         * 而配置里的 `intervalSeconds` 可能只有 60 秒 —— 拿配置值去算，省电模式下会永远判 STALE。
         * 默认 0 = "调用方不知道周期"（不抬高阈值），行为与改动前逐字相同。
         */
        expectedIntervalSeconds: Int = 0
    ): ApplyObservationResult = db.withTransaction {
        val streamer = streamerDao.findActiveByStableId(observation.streamerStableId)
            ?: return@withTransaction ApplyObservationResult.Invalid("streamer_missing")
        val now = clock.nowWall()
        // 数据新鲜度语义（用户定稿）：成功检查即重新确认 → 确认时间刷新为 FRESH；
        // 失败观察不推进确认时间，数据随时间自然老化 → STALE；从未成功确认 → UNKNOWN。
        // 阈值与判定式已抽到 domain.policy.FreshnessPolicy（引擎与界面同源，避免两个真相）；
        // 这里额外把"实际周期"传进去，否则省电模式下这一列会与界面说的是两回事。
        val confirmedAt = if (observation.result == ObservationResult.SUCCESS) now else streamer.lastConfirmedAt
        val freshness = freshnessOf(confirmedAt, now, expectedIntervalSeconds)
        // ★ 缺陷 3：这里**不再**传 streamer.streamerMonitorGeneration 当 CAS 条件 ——
        //   它就是在同一个事务里从这一行读出来的（上面那句 findActiveByStableId），
        //   拿它当栅栏是恒真式。真正的保护链写在 StreamerDao.casConfirmedObservation 的注释里：
        //   单调观察序号 + 租约（monitorGeneration + fencingToken + maintenanceMode）/ + monitoringEnabled = 1。
        val rows = streamerDao.casConfirmedObservation(
            streamerId = streamer.id,
            newStatus = streamer.confirmedLiveStatus,
            observationResult = observation.result,
            observationSequence = observationSequence,
            monitorGeneration = monitorGeneration,
            fencingToken = fencingToken,
            observedAt = now,
            eventConfirmedAt = confirmedAt,
            freshness = freshness,
            updatedAt = now
        )
        if (rows == 0) return@withTransaction staleResult(streamer.id, observationSequence)

        handleObservationSideEffects(streamer, observation.result, now, callFailure)
        if (observation.result == ObservationResult.SUCCESS) {
            persistRemoteMetadata(streamer, remote, now)
            recordTitleChange(streamer, remote, now, policy, configVersion)
            recordAreaChange(streamer, remote, now, policy, configVersion)
        }

        if (counting != null) {
            updateCounting(streamer, counting, monitorGeneration, observationSequence, now)
        } else if (observation.result != ObservationResult.SUCCESS) {
            // 「连续确认」的连续性由这里保证（StateConfirmationPolicy 的 KDoc 与
            // StateConfirmationPolicyTest 都把"归零"归责于仓储层）：
            // 非 SUCCESS 观察必须复位挂起计数。
            //
            // 原实现只在状态转换提交时（applyConfirmedObservationAtomically）复位，
            // 失败观察完全不动 pending 行 —— 于是
            //   Tick1 观察到 OFFLINE（count=1）→ Tick2 网络失败（pending 原封不动）
            //   → Tick3 再观察到 OFFLINE（count=2）直接过阈值
            // 两次确认之间可以隔任意多次失败，与"连续 N 次确认"的契约相反，
            // 会产生**假关播**：错误的 END 事件与通知，场次被关闭、时长与统计一起错。
            streamerDao.deletePendingTransition(streamer.id)
        }
        ApplyObservationResult.Applied(observationSequence, streamer.confirmedLiveStatus)
    }

    /**
     * 唯一的状态转换提交入口（0.6.15）：一个业务事务内完成
     * 状态 CAS → 盲区/区间维护 → StatusHistory → LiveEvent → LiveSession →
     * ReliableMonitorInterval → PendingTransition 复位 → Outbox → sourceDataVersion。
     */
    suspend fun applyConfirmedObservationAtomically(
        remote: RemoteLiveRoom?,
        transition: DetectedTransition,
        observation: StreamerObservation,
        observationSequence: Long,
        monitorGeneration: Long,
        fencingToken: String,
        configVersion: Long,
        policy: StreamerNotificationPolicy,
        /**
         * 本轮配置的检查间隔（秒）。用于判断"两次成功观察之间是否中断过"——
         * 断档截断（关播时刻回退到 lastCheckedAt）依赖它，不能写死阈值：
         * 省电模式的调度周期可以是 15~240 分钟，写死 15 分钟会把正常的长周期误判成断档。
         */
        expectedIntervalSeconds: Int = 0,
        /**
         * 失败原始信号。转换只可能由**成功**观察产生，所以这里通常为 null；
         * 保留参数是为了让"转换路径也可能带错误信息"这件事在类型上成立
         * （例如将来把 PARTIAL 计入转换时不必再改签名）。
         */
        callFailure: com.example.bilimonitor.domain.policy.CallFailure? = null
    ): ApplyObservationResult = db.withTransaction {
        val streamer = streamerDao.findActiveByStableId(observation.streamerStableId)
            ?: return@withTransaction ApplyObservationResult.Invalid("streamer_missing")
        val now = clock.nowWall()

        val lastConfirmedAt = streamer.lastConfirmedAt
        val eventConfirmedAt = now
        // ★ 缺陷 3 的修复点：`monitoringEnabled = 1` 现在写在 CAS 的 WHERE 里
        //   （见 StreamerDao.casConfirmedObservation）。Tick 中途暂停某位主播（0.6.22 第 1 条）
        //   之后，**在途的这一次转换观察**会在下一句 CAS 上拿到 0 行 → 直接走
        //   staleResult 返回 StaleOrFenced，既不写状态、也不建/复活场次、更不发通知。
        val rows = streamerDao.casConfirmedObservation(
            streamerId = streamer.id,
            newStatus = transition.to,
            observationResult = observation.result,
            observationSequence = observationSequence,
            monitorGeneration = monitorGeneration,
            fencingToken = fencingToken,
            observedAt = now,
            eventConfirmedAt = eventConfirmedAt,
            freshness = DataFreshness.FRESH,
            updatedAt = now
        )
        if (rows == 0) return@withTransaction staleResult(streamer.id, observationSequence)

        // ★ 这里**绝不能**吞掉信号后正常返回：Room 的 withTransaction 是"正常返回即提交"，
        //   而此时事务里已经写入 confirmedLiveStatus=LIVE、status_history、区间副作用与标题/分区变更 ——
        //   一旦提交，下一 Tick 的 from==to==LIVE 就不再产生转换，**这个场次永远不会被创建**，
        //   这段直播会从历史/统计/导出里静默消失（台账 H19）。
        //   因此信号必须穿透事务让 Room 回滚；回滚后下一 Tick 会重新走一次 LIVE 转换，
        //   届时 openNewSession 会发现已有 OPEN session 并复用（并发赢家创建的那条）。
        handleObservationSideEffects(streamer, observation.result, now, callFailure)
        recordTitleChange(streamer, remote, now, policy, configVersion)
        recordAreaChange(streamer, remote, now, policy, configVersion)

        val eventSequence = liveEventDao.nextEventSequence(streamer.id)
        val transitionId = StateConfirmationPolicy.transitionIdOf(
            streamer.stableId, eventSequence, transition.from, transition.to
        )
        statusHistoryDao.insert(
            StatusHistoryEntity(
                historyId = Ids.newId(),
                streamerId = streamer.id,
                streamerStableId = streamer.stableId,
                sessionStableId = null,
                oldStatus = transition.from,
                newStatus = transition.to,
                observationResult = observation.result,
                occurredAt = now,
                observationSequence = observationSequence,
                monitorGeneration = monitorGeneration,
                streamerMonitorGeneration = streamer.streamerMonitorGeneration,
                fencingToken = fencingToken,
                transitionId = transitionId,
                configVersion = configVersion,
                createdAt = now
            )
        )

        val activeInterval = reliableIntervalDao.findActiveByStreamer(streamer.stableId)
        var committedStatus = transition.to

        when {
            transition.reason == com.example.bilimonitor.data.local.TransitionReason.RECOVERY_RECONFIRM -> {
                // 异常/盲区恢复且主播仍 LIVE：写 LIVE_RECONFIRMED + "正在直播"通知 + 新区间（0.6.5）。
                // 若已有 ACTIVE 区间则复用它（见下方 LIVE 分支的说明）。
                //
                // ★ 缺陷 2(a)：这里原先**只找 ACTIVE**，找不到就直接 openNewSession ——
                //   而"暂停监控/软删除"会把 ACTIVE OPEN 转成 ABANDONED OPEN（endTime 仍为 NULL），
                //   于是恢复检查会新建第二条场次，与那条 ABANDONED 的区间**重叠**，
                //   而后者再无任何自动关闭路径（规范 0.6.22 / doc_v2.4.md:866：
                //   "若当前只有 ABANDONED OPEN，则 RecoveryCheck 必须先依据结果处理该场次，
                //   再决定是否允许新建 ACTIVE Session"）。
                //   本分支的语义是"主播确认仍是 LIVE 且我们本来就处于 LIVE 状态"，
                //   所以那条 ABANDONED 很可能就是**同一次直播** → 在时间窗内复用为 ACTIVE，
                //   超出时间窗则先关闭再新建（判据见 [handleAbandonedOpenSession]）。
                val session = liveSessionDao.findActiveOpenSession(streamer.id)
                    ?: handleAbandonedOpenSession(
                        streamer = streamer,
                        now = now,
                        expectedIntervalSeconds = expectedIntervalSeconds,
                        allowReuse = true
                    )
                val intervalId = activeInterval?.intervalId ?: Ids.stableId("itv")
                if (activeInterval == null) {
                    reliableIntervalDao.insert(
                        intervalOf(intervalId, streamer, session?.stableId, now, observationSequence,
                            monitorGeneration, fencingToken)
                    )
                } else {
                    reliableIntervalDao.renewActive(intervalId, observationSequence, now)
                }
                if (session != null) {
                    liveSessionDao.attachIntervalAndLiveMeta(
                        id = session.id, intervalId = intervalId, at = now,
                        title = remote?.roomTitle, area = areaLabel(remote), cover = remote?.coverUrl, now = now
                    )
                } else {
                    openNewSession(streamer, remote, now)
                }
                val reconfirmEvent = liveEventOf(
                    streamer = streamer, sessionStableId = session?.stableId,
                    type = LiveEventType.LIVE_RECONFIRMED, at = now,
                    eventSequence = eventSequence, observationSequence = observationSequence,
                    monitorGeneration = monitorGeneration, fencingToken = fencingToken,
                    transitionId = transitionId, configVersion = configVersion
                )
                liveEventDao.insert(reconfirmEvent)
                committedStatus = ConfirmedLiveStatus.LIVE
                // 异常恢复后重新确认 LIVE：产生"正在直播"通知（0.6.5 / 233）。
                createStartNotification(
                    reconfirmEvent, streamer, remote, session?.stableId,
                    policy, configVersion,
                    com.example.bilimonitor.data.local.NotificationEventType.LIVE_RECONFIRMED
                )
            }

            transition.to == ConfirmedLiveStatus.LIVE && transition.from != ConfirmedLiveStatus.LIVE -> {
                // 开播确认：创建/复用 ACTIVE OPEN Session（0.6.5 / 0.6.22）。
                val existing = liveSessionDao.findActiveOpenSession(streamer.id)
                var sessionStableId: String?
                var sessionId: Long
                if (existing != null) {
                    sessionStableId = existing.stableId; sessionId = existing.id
                    // ★ 缺陷 2：并存的 ABANDONED OPEN（历史遗留 —— 暂停后新建场次造成的那种重叠）
                    //   必须在转换前先关掉：它不可能是"当前场次"（ACTIVE 那条才是），
                    //   但 endTime IS NULL 会让它永远悬着，并与本场次的区间重叠。
                    //   这里**只关闭、不复用**（allowReuse = false）。
                    handleAbandonedOpenSession(
                        streamer = streamer,
                        now = now,
                        expectedIntervalSeconds = expectedIntervalSeconds,
                        allowReuse = false,
                        // 结束时刻不可能晚于当前场次的开播时刻（否则修完还是重叠）
                        endTimeUpperBound = existing.startTime
                    )
                } else {
                    // ★ 缺陷 2：没有 ACTIVE OPEN 时**先处理 ABANDONED OPEN**，再决定是否新建
                    //   （规范 0.6.22 / doc_v2.4.md:866）。修复前这里只看 ACTIVE，而暂停监控/软删除
                    //   留下的恰恰是 ABANDONED OPEN（endTime 仍为 NULL）⇒ 恢复后新建出第二条未结束
                    //   场次，两条区间重叠，旧的那条再没有任何自动关闭路径。
                    //   复用（同一次直播的短暂中断）与关闭+新建（陈旧场次）的判据见
                    //   [handleAbandonedOpenSession] / [sameLiveRun]：
                    //     · 时间窗内（≤ 2 × 实际检查周期）→ reactivateAbandoned 复用：startTime、
                    //       区间、以及该场次上已有的 START 事件都保持连续，因此**不会**重复发开播通知
                    //       （同一次直播只该有一次开播通知）；
                    //     · 超出时间窗 → 先以 MONITOR_OBSERVED / PROVISIONAL 关闭（endTime 取断档
                    //       截断后的"最后一次可靠观察"，避免写出"时长 = 数天"的假场次），**再**新建场次
                    //       ⇒ 新场次上没有 START 事件，开播事件与开播通知照常产生
                    //       （修复前：陈旧场次被复活 ⇒ startTime 是几天前 + START 已存在
                    //       ⇒ 假时长 + 丢开播通知）。
                    //   FIRST_OBSERVATION 时不允许复用（沿用 0.6.5 的既有约束：首次确认 LIVE
                    //   不得伪造主播真实开播时间），但仍然会先把那条 ABANDONED 关闭掉。
                    val reused = handleAbandonedOpenSession(
                        streamer = streamer,
                        now = now,
                        expectedIntervalSeconds = expectedIntervalSeconds,
                        allowReuse = transition.reason !=
                            com.example.bilimonitor.data.local.TransitionReason.FIRST_OBSERVATION
                    )
                    if (reused != null) {
                        sessionStableId = reused.stableId; sessionId = reused.id
                    } else {
                        val created = openNewSession(streamer, remote, now)
                        sessionStableId = created.stableId; sessionId = created.id
                    }
                }
                // 区间插入必须复用已有的 ACTIVE 区间。
                // ROUND 不关闭区间（见文件头契约：ROUND 不算异常、区间保持有效），
                // 因此 ROUND → LIVE 时旧区间仍是 ACTIVE；若无条件再插一条，
                // 会违反部分唯一索引 idx_interval_one_active（ON CONFLICT ABORT），
                // 整个事务回滚 → 主播**永久卡在 ROUND**，每个 Tick 失败并写一条错误日志。
                val intervalId = activeInterval?.intervalId ?: Ids.stableId("itv")
                if (activeInterval == null) {
                    reliableIntervalDao.insert(
                        intervalOf(intervalId, streamer, sessionStableId, now, observationSequence,
                            monitorGeneration, fencingToken)
                    )
                } else {
                    reliableIntervalDao.renewActive(intervalId, observationSequence, now)
                }
                liveSessionDao.attachIntervalAndLiveMeta(
                    id = sessionId, intervalId = intervalId, at = now,
                    title = remote?.roomTitle, area = areaLabel(remote), cover = remote?.coverUrl, now = now
                )
                streamerDao.noteLiveStarted(streamer.id, now, now)
                // 普通首次确认不产生 START 通知（0.6.5）；正式确认才创建 START 事件与通知。
                if (transition.reason != com.example.bilimonitor.data.local.TransitionReason.FIRST_OBSERVATION &&
                    !liveEventDao.existsBySessionAndType(sessionStableId, LiveEventType.START)
                ) {
                    // 同一 Session 只允许一条 START 事件（0.6.36）；复活旧 Session 时不再重复发开播事件。
                    val event = liveEventOf(
                        streamer = streamer, sessionStableId = sessionStableId,
                        type = LiveEventType.START, at = now,
                        eventSequence = eventSequence, observationSequence = observationSequence,
                        monitorGeneration = monitorGeneration, fencingToken = fencingToken,
                        transitionId = transitionId, configVersion = configVersion
                    )
                    liveEventDao.insert(event)
                    createStartNotification(event, streamer, remote, sessionStableId, policy, configVersion)
                }
            }

            // ★ 用户要求：ROUND（轮播）按**未开播**处理 —— 与 OFFLINE 走同一个分支。
            //   原实现把 LIVE -> ROUND 单独留给末尾的 else（什么都不做），
            //   导致开播→轮播时场次永不结束、END 事件与下播通知永不产生、
            //   自动化记录全部失效（用户报告的 bug）。
            (transition.to == ConfirmedLiveStatus.OFFLINE ||
                transition.to == ConfirmedLiveStatus.ROUND) -> {
                // 下播确认：关播通知资格 = 当前区间 ACTIVE（0.2 / 0.6.6）。
                val wasIntervalActive = activeInterval != null &&
                    activeInterval.streamerMonitorGeneration == streamer.streamerMonitorGeneration
                val open = liveSessionDao.findActiveOpenSession(streamer.id)
                reliableIntervalDao.closeActiveByStreamer(
                    streamer.stableId, ReliableIntervalInvalidReason.SESSION_CLOSED, now
                )
                if (open != null) {
                    // 关闭口径（断档截断的判据、锚点为什么必须用 lastConfirmedAt、
                    // endConfidence 为什么是 PROVISIONAL）全部写在 [closeOpenSessionAsObserved] 里，
                    // ACTIVE 关播与 ABANDONED OPEN 补结束**共用同一个实现**，口径不可能分叉。
                    val outcome = closeOpenSessionAsObserved(
                        streamer = streamer, session = open, now = now,
                        expectedIntervalSeconds = expectedIntervalSeconds
                    )
                    if (outcome.rows > 0 && outcome.interrupted) {
                        // 留下可审计的痕迹：用户/诊断能看出"这一场的结束时间是被截断的"
                        logDao.insertAudit(
                            AuditLogEntity(
                                auditId = Ids.newId(), operationId = Ids.newId(), actor = "SYSTEM",
                                action = "SESSION_END_TRUNCATED", targetType = "live_session",
                                targetStableId = open.stableId, occurredAt = now,
                                detailJson = "{\"observedAt\":$now,\"lastCheckedAt\":${outcome.lastSeen}," +
                                    "\"intervalSeconds\":$expectedIntervalSeconds}"
                            )
                        )
                    }
                    if (outcome.rows > 0) {
                        streamerDao.noteLiveEnded(streamer.id, now, now)
                        if (wasIntervalActive) {
                            val event = liveEventOf(
                                streamer = streamer, sessionStableId = open.stableId,
                                type = LiveEventType.END, at = now,
                                eventSequence = eventSequence, observationSequence = observationSequence,
                                monitorGeneration = monitorGeneration, fencingToken = fencingToken,
                                transitionId = transitionId, configVersion = configVersion
                            )
                            liveEventDao.insert(event)
                            createEndNotification(event, streamer, policy, configVersion)
                        }
                    }
                }
                // ★ 缺陷 2：ABANDONED OPEN 场次在这里**无条件关闭**（规范 0.6.22：
                //   "确认 OFFLINE → 关闭该 ABANDONED Session，endTime = 本次可靠确认时间，
                //    endConfidence = PROVISIONAL，不因历史异常发送关播通知"；
                //   doc_v2.4.md:6362-6363 同义）。修复前这条路径**根本不存在** ——
                //   暂停监控留下的 ABANDONED OPEN 永远 endTime IS NULL，没有任何自动关闭出口，
                //   并且之后开播时会被当成"旧场次"复活（假时长 + 丢开播通知）。
                //   注意：这里既不发 END 事件也不发关播通知（历史异常不是下播事实）。
                //   ⚠️ 只处理 ABANDONED OPEN，**绝不**顺手回填其它任何历史场次 ——
                //   批量回填是数据修复工具的职责，不在监控 Tick 里做。
                closeAbandonedOpenAsObserved(
                    streamer = streamer, now = now, expectedIntervalSeconds = expectedIntervalSeconds,
                    // 若同时存在 ACTIVE OPEN（历史遗留的重叠数据），悬空场次的结束时刻
                    // 不可能晚于它的开播时刻
                    endTimeUpperBound = open?.startTime
                )
            }

            else -> {
                // 其余转换（LIVE -> UNKNOWN 等）：无额外写入。
                // 注意 ROUND 已并入上面的 OFFLINE 分支（见那里的说明），不再落到这里。
            }
        }
        // 注意：这里**不再**捕获 DuplicateOpenSessionSignal。
        // 捕获后正常返回 = 提交半截事务（状态已改、场次没建），见本文件上方说明与台账 H19。
        // 让信号继续向上传播，由 Room 回滚整个事务，下一 Tick 自然重试。

        // 实时房间资料落库（用户要求：直播间封面要实时）。
        // 放在这里而不是只在"开播那一刻"：封面/标题随时可能被主播改掉，
        // 每次成功观察都刷新一次才能保证二级页面看到的是**当前**封面。
        if (observation.result == ObservationResult.SUCCESS) {
            persistRemoteMetadata(streamer, remote, now)
            recordTitleChange(streamer, remote, now, policy, configVersion)
            recordAreaChange(streamer, remote, now, policy, configVersion)
        }

        // PendingTransition 复位：删除行，下一次观察重新建立（0.6.7）。
        streamerDao.deletePendingTransition(streamer.id)
        // sourceDataVersion 推进（触发集合封闭，0.6.14）。
        streamerDao.bumpSourceDataVersion(now)

        ApplyObservationResult.Applied(observationSequence, committedStatus)
    }

    /**
     * CAS 0 行时的统一出口（**唯一**构造"没写进去"结果的地方）。
     *
     * ★ 关于 [ApplyObservationResult.ConfigStale]（缺陷 3 顺带确认）：它**没有任何路径会返回** ——
     *   本函数与两条 CAS 路径只会产出 StaleSequence / StaleOrFenced / Invalid，
     *   全仓也搜不到第二处构造点。但本次**保留**它，理由有两条：
     *   1. 它是 [ApplyObservationResult] 这个 sealed interface 的公开契约成员，
     *      `MonitoringEngine`（`background` 包，本轮不归我改）在 when 分支里显式处理它
     *      （记 CONFIG_INVALID）；删掉它会让那个 when 的分支变成"未解析引用" → 编译失败，
     *      而"让配置代际失效的观察被拒绝"是规范里确实存在的语义（0.6.16：
     *      Tick 期间不得改配置，配置代际变化应使本轮写入作废）；
     *   2. 不为了"用上它"而新造逻辑：配置代际（configVersion）目前只参与写入留痕，
     *      并不参与 CAS；凭空加一条 configVersion CAS 会改变已确认的提交语义，
     *      属于另一个缺陷的范畴，不在本次修复范围。
     *   一句话：**保留 + 明确记录它当前不可达**，而不是删掉别人的契约或硬凑一个用途。
     */
    private suspend fun staleResult(streamerId: Long, sequence: Long): ApplyObservationResult {
        val fresh = streamerDao.findById(streamerId)
        return if (fresh != null && fresh.observationSequence >= sequence) {
            ApplyObservationResult.StaleSequence
        } else {
            ApplyObservationResult.StaleOrFenced
        }
    }

    /** 成功/失败观察的区间与盲区维护（0.6.6 / 0.6.9）。 */
    private suspend fun handleObservationSideEffects(
        streamer: StreamerEntity,
        result: ObservationResult,
        now: Long,
        /**
         * 本次失败的**结构化原始信号**（HTTP 状态码 / 业务 code / message / 限流头）。
         * 为空表示"引擎没拿到更具体的信号"（例如熔断期间未发请求的兜底路径）。
         */
        callFailure: com.example.bilimonitor.domain.policy.CallFailure? = null
    ) {
        if (result == ObservationResult.SUCCESS) {
            // ★ 恢复正常 = 边沿触发的"下降沿"：清掉这位主播的失败留痕抑制器，
            //   下次再失败（哪怕原因完全相同）重新记一条（缺陷 3）。
            failureLogGate.clear(streamer.stableId)
            // 关闭该主播的主播级盲区；若这是该父盲区下最后一个未恢复的主播，
            // 同时关闭父行。原实现只关子行，父行 endTime 永远为 NULL：
            // 受 `idx_gap_one_open ON monitoring_gap(scope) WHERE endTime IS NULL` 全局唯一约束，
            // 之后所有主播的故障都会复用这一行（reason/startedAt 仍是第一个主播的），
            // 且诊断页会长期显示一个永不消失的盲区。
            val gapIds = monitoringGapDao.openGapIdsForStreamer(streamer.stableId)
            monitoringGapDao.closeStreamerGap(streamer.stableId, now)
            for (gapId in gapIds) {
                if (!monitoringGapDao.hasOpenStreamerGapForGap(gapId)) {
                    monitoringGapDao.close(gapId, now)
                }
            }
            val active = reliableIntervalDao.findActiveByStreamer(streamer.stableId)
            if (active != null) {
                reliableIntervalDao.renewActive(active.intervalId, streamer.observationSequence + 1, now)
            }
        } else {
            val reason = when (result) {
                ObservationResult.TIMEOUT -> ReliableIntervalInvalidReason.TIMEOUT
                ObservationResult.NETWORK_ERROR -> ReliableIntervalInvalidReason.NETWORK_ERROR
                ObservationResult.API_ERROR -> ReliableIntervalInvalidReason.API_ERROR
                ObservationResult.PARTIAL -> ReliableIntervalInvalidReason.PARTIAL
                else -> ReliableIntervalInvalidReason.INVALID
            }
            reliableIntervalDao.invalidateActiveByStreamer(streamer.stableId, reason, now)
            if (!monitoringGapDao.hasOpenStreamerGap(streamer.stableId)) {
                val gap = monitoringGapDao.findOpenByScope(GapScope.STREAMER)
                    ?: MonitoringGapEntity(
                        gapId = Ids.stableId("gap"), startedAt = now, endTime = null,
                        scope = GapScope.STREAMER, reason = gapReasonOf(result), createdAt = now
                    ).also { monitoringGapDao.insert(it) }
                monitoringGapDao.insertStreamerGap(
                    MonitoringGapStreamerEntity(
                        gapId = gap.gapId, streamerStableId = streamer.stableId,
                        affectedStartAt = now, affectedEndAt = null,
                        reason = gapReasonOf(result), lastReliableAt = streamer.lastCheckedAt,
                        recoveredAt = null, createdAt = now
                    )
                )
            }
            // ★ 错误码优先采用**引擎给出的原因映射**（RoomFailureMapping）：
            //   限流/5xx 以前一律被记成 API_REJECTED（"接口拒绝"），
            //   而现在每个机制都有自己的错误码与 detail，诊断时一眼能分清。
            //   没有信号时回退到按 ObservationResult 推断 —— 保持原行为，不引入新的未知。
            val failureCode = callFailure
                ?.let { com.example.bilimonitor.domain.policy.RoomFailureMapping.appErrorOf(it.reason) }
                ?: when (result) {
                    ObservationResult.TIMEOUT -> AppError.NETWORK_TIMEOUT
                    ObservationResult.NETWORK_ERROR -> AppError.NETWORK_UNAVAILABLE
                    ObservationResult.API_ERROR -> AppError.API_REJECTED
                    else -> AppError.API_INVALID_RESPONSE
                }
            // ★ 边沿抑制（缺陷 3）：这一行是**全网故障时最热的写路径** ——
            //   修复前它无条件插入，98 位主播全失败 = 98 行/分钟 ≈ 14 万行/天，
            //   而诊断包只取最近 `DIAGNOSTIC_LIMIT = 500` 行：真正的故障现场会被自己刷掉。
            //   对照：本轮汇总日志与封禁探针失败**都已有**边沿抑制，唯独这条没有。
            //   指纹 = (主播, 错误码 + 失败原因)：同一主播同一原因连续失败只留第一条，
            //   原因变化（超时 → 限流）或换主播则各记一条；恢复时 clear（见上面 SUCCESS 分支）。
            //   事实没有被丢掉：第一位失败的主播行 + `detail` 里的原始信号 + 汇总日志都还在。
            val signature = com.example.bilimonitor.domain.policy.ErrorLogEdgeGate.signatureOf(
                errorCode = failureCode,
                reason = callFailure?.reason
            )
            if (failureLogGate.shouldRecord(streamer.stableId, signature)) {
                logDao.insertMonitoringError(
                    MonitoringErrorLogEntity(
                        errorId = Ids.newId(),
                        problemKey = Ids.problemKey("MONITORING", "UNKNOWN", streamer.stableId, null),
                        streamerStableId = streamer.stableId,
                        observationSequence = streamer.observationSequence,
                        occurredAt = now,
                        errorCode = failureCode,
                        // ★ detail 从"恒为 null"改为**携带原始信号**（用户诉求：
                        //   "必须把原始信号记进错误记录……便于日后验证判定是否正确"）。
                        //   这是当初无法定位问题的直接原因：入库后只剩一个粗粒度错误码，
                        //   HTTP 状态码、B 站业务 code、message 摘要全部丢失。
                        detail = describeFailure(callFailure)?.take(DETAIL_MAX_CHARS)
                    )
                )
            }
            streamerDao.bumpSourceDataVersion(now)
        }
    }

    /** 直播间标题变更记录：进行中场次内标题与最后记录不同即新增（PK 去重），并刷新主播当前标题。 */
    private suspend fun recordTitleChange(
        streamer: StreamerEntity,
        remote: RemoteLiveRoom?,
        now: Long,
        policy: StreamerNotificationPolicy?,
        configVersion: Long
    ) {
        val title = remote?.roomTitle?.takeIf { it.isNotBlank() } ?: return
        val openSession = liveSessionDao.findActiveOpenSession(streamer.id) ?: return
        val last = liveSessionTitleDao.latestTitle(openSession.stableId)
        if (last != title) {
            liveSessionTitleDao.insert(
                com.example.bilimonitor.data.local.entity.LiveSessionTitleEntity(
                    sessionStableId = openSession.stableId, title = title, observedAt = now
                )
            )
            // 标题变化通知（用户定稿功能，**默认关闭**）。
            // 只在"确实变了"时发；免打扰与主播开关都尊重。
            if (policy?.notifyTitleChange == true) {
                // payload 提前构造：发与被抑制用的是同一份内容（抑制留痕也要能看出"当时是什么"）
                val payload = NotificationPayloads.live(
                    eventKey = "change:TITLE:$title",
                    streamerStableId = streamer.stableId,
                    sessionStableId = openSession.stableId,
                    eventId = "",
                    name = streamer.name,
                    title = title,
                    area = areaLabel(remote) ?: streamer.areaName,
                    url = remote?.liveUrl ?: streamer.liveUrl,
                    cover = remote?.coverUrl ?: streamer.coverUrl,
                    previousValue = last
                )
                val changeType = com.example.bilimonitor.data.local.NotificationEventType.TITLE_CHANGED
                if (policy.quietHoursActive) {
                    // 被免打扰抑制也要留痕（2026 复查），理由见 recordSuppressed。
                    NotificationOutboxWriter.recordSuppressed(
                        db = db,
                        eventKey = NotificationOutboxWriter.changeEventKey(
                            changeType, streamer.stableId, openSession.stableId, title
                        ),
                        streamerId = streamer.id, sourceEventId = null,
                        eventType = changeType, payloadJson = payload, now = now,
                        reason = NotificationOutboxWriter.SUPPRESSED_QUIET_HOURS,
                        configVersion = configVersion
                    )
                } else {
                    NotificationOutboxWriter.createForChange(
                        db = db, streamerId = streamer.id, streamerStableId = streamer.stableId,
                        sessionStableId = openSession.stableId,
                        eventType = changeType,
                        changeKey = title, payloadJson = payload, now = now, configVersion = configVersion
                    )
                }
            }
        }
        streamerDao.updateRoomTitle(streamer.id, title, now)
    }

    /**
     * 直播分区变更记录（用户定稿功能）：进行中场次内分区与最后记录不同即新增（PK 去重）。
     *
     * 一场直播里主播可能换分区（例如 虚拟主播 → 唱见），要求**全部**记录下来 ——
     * 所以是"每变一次记一行"，而不是只留起止两个值。
     * 数据来自公开的直播状态接口（`area_v2_parent_name` / `area_name`），**不需要登录**。
     */
    private suspend fun recordAreaChange(
        streamer: StreamerEntity,
        remote: RemoteLiveRoom?,
        now: Long,
        policy: StreamerNotificationPolicy?,
        configVersion: Long
    ) {
        val label = areaLabel(remote)?.takeIf { it.isNotBlank() } ?: return
        val openSession = liveSessionDao.findActiveOpenSession(streamer.id) ?: return
        val last = liveSessionAreaDao.lastArea(openSession.stableId)
        if (last != label) {
            liveSessionAreaDao.insert(
                com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity(
                    sessionStableId = openSession.stableId,
                    areaLabel = label,
                    parentAreaName = remote?.parentAreaName,
                    areaName = remote?.areaName,
                    observedAt = now
                )
            )
            // 分区变化通知（用户定稿功能，**默认关闭**），规则同标题变化。
            if (policy?.notifyAreaChange == true) {
                val payload = NotificationPayloads.live(
                    eventKey = "change:AREA:$label",
                    streamerStableId = streamer.stableId,
                    sessionStableId = openSession.stableId,
                    eventId = "",
                    name = streamer.name,
                    title = remote?.roomTitle ?: streamer.roomTitle,
                    area = label,
                    url = remote?.liveUrl ?: streamer.liveUrl,
                    cover = remote?.coverUrl ?: streamer.coverUrl,
                    previousValue = last
                )
                val changeType = com.example.bilimonitor.data.local.NotificationEventType.AREA_CHANGED
                if (policy.quietHoursActive) {
                    NotificationOutboxWriter.recordSuppressed(
                        db = db,
                        eventKey = NotificationOutboxWriter.changeEventKey(
                            changeType, streamer.stableId, openSession.stableId, label
                        ),
                        streamerId = streamer.id, sourceEventId = null,
                        eventType = changeType, payloadJson = payload, now = now,
                        reason = NotificationOutboxWriter.SUPPRESSED_QUIET_HOURS,
                        configVersion = configVersion
                    )
                } else {
                    NotificationOutboxWriter.createForChange(
                        db = db, streamerId = streamer.id, streamerStableId = streamer.stableId,
                        sessionStableId = openSession.stableId,
                        eventType = changeType,
                        changeKey = label, payloadJson = payload, now = now, configVersion = configVersion
                    )
                }
            }
        }
    }

    /**
     * 把本次观察到的房间资料写回主播行（封面 / 标题 / 分区 / 头像 / 房间号）。
     *
     * 为什么必须做：`streamer.coverUrl` 原先**只有在用户手动"刷新资料"和新增主播时才写**，
     * 而关注导入进来的主播一律以 `coverUrl = null` 落库、监控 Tick 又从不补写 ——
     * 于是"实时直播间封面"在数据层根本不存在（实测库里 30 个主播的 coverUrl 全为 NULL，
     * 而同一个批量接口的响应里 `cover_from_user` 明明有值）。
     *
     * `updateRemoteMetadata` 全部字段用 COALESCE，传 null 不会覆盖已有值，
     * 所以这里可以无脑把远端返回的东西全部传进去。
     */
    private suspend fun persistRemoteMetadata(streamer: StreamerEntity, remote: RemoteLiveRoom?, now: Long) {
        if (remote == null) return
        runCatching {
            streamerDao.updateRemoteMetadata(
                streamerId = streamer.id,
                name = remote.name,
                avatarUrl = remote.avatarUrl,
                roomTitle = remote.roomTitle,
                parentAreaName = remote.parentAreaName,
                areaName = remote.areaName,
                coverUrl = remote.coverUrl,
                liveUrl = remote.liveUrl,
                roomId = remote.roomId,
                shortRoomId = remote.shortRoomId,
                now = now
            )
        }
    }

    /**
     * 「连续 N 次确认」的计数写入 —— **缺陷 1 的修复点**。
     *
     * 三件事必须一起做对，少一件计数就会跨代际、跨进程活下来：
     *  1. 行不存在 → [StreamerDao.ensurePendingTransition]（IGNORE）新建；
     *  2. 行存在但**代际已变** → [StreamerDao.resetPendingTransition]（REPLACE）**整行重建**。
     *     修复前这里调的是 `ensurePendingTransition(existing.copy(...))` —— IGNORE 对已存在的行是
     *     no-op，代际列与 `confirmationCount` 一个字节都没改（"代际重建"是死代码）；
     *  3. CAS 返回 0 行时**必须重读判断**，不能当成功（DAO 的 KDoc 一直写着"调用方重读后重算"）。
     *
     * 为什么读侧也必须过滤：代际变化时**计数已经被引擎读走并喂给了状态机**
     * （MonitoringEngine.applySafely → StateConfirmationPolicy.detect），
     * 所以只在这里重置是不够的 —— 老库 `endConfirmationCount = 2` 时，中断前攒下的 1 次计数
     * 会让恢复后的第一次观察直接凑满阈值（早一拍的假关播）。
     * 读侧的代际过滤写在 [StreamerDao.getPendingTransition] 的 SQL 里（同一事务的出口处），
     * 因此这里必须用**不过滤**的 [StreamerDao.getPendingTransitionAnyGeneration] 判断
     * "新建 / 重置 / 沿用"，否则会把"代际过期但确实存在"的行误判成"没有行"。
     *
     * @param monitorGeneration 全局监控代际（租约代际；进程重启、租约接管后都会变）
     */
    private suspend fun updateCounting(
        streamer: StreamerEntity,
        counting: StateConfirmationPolicy.Decision.Counting,
        monitorGeneration: Long,
        sequence: Long,
        now: Long
    ) {
        val existing = streamerDao.getPendingTransitionAnyGeneration(streamer.id)
        val plan = pendingWritePlan(
            rowExists = existing != null,
            rowMonitorGeneration = existing?.monitorGeneration ?: 0L,
            rowStreamerGeneration = existing?.streamerMonitorGeneration ?: 0L,
            monitorGeneration = monitorGeneration,
            streamerMonitorGeneration = streamer.streamerMonitorGeneration
        )
        val fresh = StreamerPendingTransitionEntity(
            streamerId = streamer.id,
            monitorGeneration = monitorGeneration,
            streamerMonitorGeneration = streamer.streamerMonitorGeneration,
            pendingTransition = PendingTransition.NONE,
            confirmationCount = 0,
            lastObservationSequence = sequence - 1,
            updatedAt = now
        )
        when (plan) {
            PendingWritePlan.Create -> streamerDao.ensurePendingTransition(fresh)
            // 代际变化：**覆盖**旧行（REPLACE），把计数真正归零。
            PendingWritePlan.ResetForNewGeneration -> streamerDao.resetPendingTransition(fresh)
            PendingWritePlan.Keep -> Unit
        }
        // 说明：这里**不再**预读一次"现在库里那一行"（旧实现用它当 CAS 的期望值 ⇒ 恒匹配）。
        // CAS 语句自己只认单调序号 + monitoringEnabled，代际在上面已经处理掉了。
        val written = streamerDao.updatePendingTransitionCas(
            streamerId = streamer.id,
            newTransition = counting.transition,
            newCount = counting.newCount,
            sequence = sequence,
            updatedAt = now
        )
        if (written == 0) {
            // ★ CAS 0 行 = 本事务之外已经把上下文改掉了（计数行被删 / 已有更新的观察写过 /
            //   主播刚被暂停）。**不能当成功**：按自己的旧值记账会覆盖别人的结论或凭空复活计数。
            //   这里**重读**那一行判断是哪一种（DAO KDoc 的"调用方重读后重算"，修复前调用方
            //   连返回值都不看）。判据是纯函数 [pendingCountSkipOf]，可单测；
            //   处置一律是"放弃本次计数 + 写审计"，理由见该函数注释（同事务重读不存在可重试的情形）。
            val afterCas = streamerDao.getPendingTransitionAnyGeneration(streamer.id)
            val skip = pendingCountSkipOf(
                rowExists = afterCas != null,
                rowLastObservationSequence = afterCas?.lastObservationSequence ?: 0L,
                mySequence = sequence,
                // 重读一次 streamer 行取"现在还在不在监控中"（只在 0 行这条罕见路径上多读一次）
                monitoringEnabled = streamerDao.findById(streamer.id)?.monitoringEnabled == true
            )
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "SYSTEM",
                    action = "PENDING_COUNT_SKIPPED", targetType = "streamer_pending_transition",
                    targetStableId = streamer.stableId, occurredAt = now,
                    detailJson = "{\"reason\":\"${skip?.name ?: "sql_mirror_drift"}\",\"sequence\":$sequence," +
                        "\"monitorGeneration\":$monitorGeneration}"
                )
            )
        }
    }

    /**
     * 一次"按监控观察到的口径关闭未结束场次"的结果。
     *
     * 为什么要有它：调用方需要据此决定**后续动作**（是否发 END 事件/关播通知）与**审计内容**，
     * 而这些值都是关闭过程中算出来的（`rows` 来自 CAS、`endTime` 来自断档截断）。
     */
    private data class CloseOutcome(
        /** [LiveSessionDao.casCloseSession] 的影响行数；0 = 没写进去（已被别的路径关闭）。 */
        val rows: Int,
        /** 实际写入的 endTime（断档截断 / 上界约束之后的值）。 */
        val endTime: Long,
        /** 断档截断的锚点 = 最后一次**成功**观察的时刻；null = 从未成功观察过。 */
        val lastSeen: Long?,
        /** true = endTime 不等于"确认时刻"（中间确实断过 / 被下一条场次的开播时刻约束住）。 */
        val interrupted: Boolean
    )

    /**
     * 把一条**未结束**的场次按"监控观察到"的口径关闭（`endSource = MONITOR_OBSERVED`、
     * `endConfidence = PROVISIONAL`、时长与结束时刻同时算好）。
     *
     * ★ 唯一实现：ACTIVE 的正常关播路径与 ABANDONED OPEN 的"补结束"路径**共用这一个函数**，
     *   因此两条路径的口径不可能分叉（用户要求："与现有 CAS 关场写法保持同口径"）。
     *
     * ★ 断档截断（台账 H19-A2）：进程被杀/长期不检查时，恢复后第一次观察到 OFFLINE
     *   若直接用 now 当关播时刻，会写出一条"时长 = 整个断档"的假场次
     *   （实测场景：09:00 开播、10:00 进程被杀、22:00 恢复 → 13 小时场次），
     *   并污染历史、统计、导出与备份。判据见 [truncatedEndTime]（抽成纯函数以便单测）。
     *   `confirmedOfflineAt` 仍记 now（那是我们真正确认下播的时间；对 ABANDONED 的补结束路径
     *   则是"本次可靠确认的时刻"），endConfidence 保持 PROVISIONAL —— 截断值是推断，不是观测。
     *   ★ 锚点必须用 lastConfirmedAt（= 最后一次**成功**观察），不能用 lastCheckedAt ——
     *   后者在失败观察时也会推进（`casConfirmedObservation` 无条件写 observedAt），
     *   于是"重启后先失败一次、再成功观察到下播"会让间隔看起来只有 1 分钟、
     *   截断不触发，13 小时假场次照样产生（第二轮审查给出的反例）。
     *   lastConfirmedAt 为 null（从未成功确认）时退回 lastCheckedAt。
     *
     * @param endTimeUpperBound 结束时刻的上界（可空）：同一主播**下一条**场次的开播时刻。
     *   同一时刻只可能有一场直播，所以待关闭场次的 endTime 不可能晚于它 ——
     *   历史遗留数据里"S1 悬空 + S2 已开始"时，没有这个上界就会把 S1 关到 now，
     *   反而制造出新的重叠区间与假时长。只在确实存在下一条场次时传入。
     */
    private suspend fun closeOpenSessionAsObserved(
        streamer: StreamerEntity,
        session: LiveSessionEntity,
        now: Long,
        expectedIntervalSeconds: Int,
        endTimeUpperBound: Long? = null
    ): CloseOutcome {
        val lastSeen = streamer.lastConfirmedAt ?: streamer.lastCheckedAt
        val truncated = truncatedEndTime(now, lastSeen, expectedIntervalSeconds)
        val effectiveEnd = endTimeUpperBound?.let { minOf(it, truncated) } ?: truncated
        val interrupted = effectiveEnd != now
        val duration = session.startTime?.let { ((effectiveEnd - it) / 1000).coerceAtLeast(0) }
        val rows = liveSessionDao.casCloseSession(
            id = session.id, endTime = effectiveEnd, endSource = DataSource.MONITOR_OBSERVED,
            endConfidence = DataConfidence.PROVISIONAL,
            durationSeconds = duration,
            durationConfidence = if (duration != null) DataConfidence.PROVISIONAL else null,
            confirmedOfflineAt = now,
            endTimeZone = clock.currentTimeZone(),
            expectedCorrectionVersion = session.correctionVersion, updatedAt = now
        )
        return CloseOutcome(rows = rows, endTime = effectiveEnd, lastSeen = lastSeen, interrupted = interrupted)
    }

    /**
     * 缺陷 2(a)：**转换之前**先处理该主播的 ABANDONED OPEN 场次 —— 要么复用为 ACTIVE，
     * 要么关闭它（`endTime` 按 [closeOpenSessionAsObserved] 的口径补上），**然后**才允许新建场次。
     *
     * 规范依据（doc_v2.4.md）：
     *  - :866  "若当前只有 ABANDONED OPEN，则 RecoveryCheck 必须先依据结果处理该场次，
     *           再决定是否允许新建 ACTIVE Session"；
     *  - :2173-2185 0.6.22 "同一主播最多一个 ABANDONED OPEN。重新启用时 RecoveryCheck：
     *           确认 OFFLINE → 关闭该 ABANDONED Session…；确认 LIVE → 复用该 Session 为 ACTIVE"；
     *  - :6361-6367 "确认 LIVE → 不自动把旧 ABANDONED Session 当作当前场次
     *           → 创建新的 ACTIVE Session"。
     *
     * 上面两条"确认 LIVE"的要求看似矛盾，其实是两种不同的输入：**同一次直播的短暂中断**
     * 应当复用（否则一次短暂暂停就把一场直播劈成两段）；**几天前的陈旧场次**决不能复用
     * （否则时长变成数天，且它上面已有 START 事件 ⇒ 新一场直播的开播通知永远发不出来）。
     * 区分二者的判据就是时间窗，见纯函数 [sameLiveRun]：
     *  - 窗口 = `2 × 本轮实际检查周期`，与既有的断档截断（[truncatedEndTime]）完全同一个口径；
     *  - 锚点 = `abandoned.updatedAt`（`abandon()` 把"停止跟踪这一刻"写在这里），
     *    **不是** `startTime` / `lastConfirmedLiveAt` —— 后两者在一场连续几小时的直播里
     *    可能一直停在几小时前（没有状态转换就不会刷新），拿它们判定会把同一次直播误关成两段；
     *  - 拿不到周期（<= 0）、时钟回拨、时间戳异常时**不关**（宁可复用也不误关，与
     *    [truncatedEndTime] "拿不到间隔就不猜"同一条纪律：只有能证明陈旧才关闭）。
     *
     * @param allowReuse false = 本次转换不允许复用（已存在 ACTIVE OPEN，或 FIRST_OBSERVATION），
     *   此时只做"关闭"这件事，绝不把旧场次当成当前场次。
     * @param endTimeUpperBound 关闭时的结束时刻上界（见 [closeOpenSessionAsObserved]）。
     * @return 被复用为 ACTIVE 的场次；null = 不存在 ABANDONED OPEN，或它已被关闭。
     */
    private suspend fun handleAbandonedOpenSession(
        streamer: StreamerEntity,
        now: Long,
        expectedIntervalSeconds: Int,
        allowReuse: Boolean,
        endTimeUpperBound: Long? = null
    ): LiveSessionEntity? {
        val abandoned = liveSessionDao.findAbandonedOpenSession(streamer.id) ?: return null
        if (!allowReuse ||
            !sameLiveRun(
                now = now,
                abandonedAt = abandoned.updatedAt,
                expectedIntervalSeconds = expectedIntervalSeconds
            )
        ) {
            closeAbandonedSessionRow(streamer, abandoned, now, expectedIntervalSeconds, endTimeUpperBound)
            return null
        }
        val reactivated = liveSessionDao.reactivateAbandoned(abandoned.id, now)
        if (reactivated > 0) {
            return abandoned.copy(
                lifecycleState = com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState.ACTIVE,
                updatedAt = now
            )
        }
        // 并发：另一个 Tick 已经把它复用/关闭了 ⇒ 以库里的现状为准（重读 ACTIVE OPEN），
        // 而不是把"我要复用"这件事当成已经成立。
        return liveSessionDao.findActiveOpenSession(streamer.id)
    }

    /**
     * 确认 OFFLINE 时**无条件**关闭 ABANDONED OPEN 场次（缺陷 2：修复前它没有任何自动关闭出口）。
     *
     * 语义来自 0.6.22（:2176-2179）与 :6362-6363：确认 OFFLINE ⇒ 关闭该 ABANDONED Session，
     * `endTime` = 本次可靠确认时间（由 [closeOpenSessionAsObserved] 按断档截断口径落笔）、
     * `endConfidence = PROVISIONAL`，**不产生 END 事件、不发关播通知**（历史异常不是下播事实）。
     * ⚠️ 只处理 ABANDONED OPEN：绝不顺手回填其它历史场次（批量回填属于数据修复工具）。
     *
     * @return 关闭的影响行数（0 = 没有该场次，或已被别的路径关闭）
     */
    private suspend fun closeAbandonedOpenAsObserved(
        streamer: StreamerEntity,
        now: Long,
        expectedIntervalSeconds: Int,
        endTimeUpperBound: Long? = null
    ): Int {
        val abandoned = liveSessionDao.findAbandonedOpenSession(streamer.id) ?: return 0
        return closeAbandonedSessionRow(streamer, abandoned, now, expectedIntervalSeconds, endTimeUpperBound)
    }

    /** 关闭一条 ABANDONED OPEN 行并留审计痕迹（两条调用路径共用，保证痕迹格式一致）。 */
    private suspend fun closeAbandonedSessionRow(
        streamer: StreamerEntity,
        abandoned: LiveSessionEntity,
        now: Long,
        expectedIntervalSeconds: Int,
        endTimeUpperBound: Long?
    ): Int {
        val outcome = closeOpenSessionAsObserved(
            streamer = streamer, session = abandoned, now = now,
            expectedIntervalSeconds = expectedIntervalSeconds,
            endTimeUpperBound = endTimeUpperBound
        )
        if (outcome.rows > 0) {
            // 这是**会改历史数据**的动作（给悬空场次补结束时间），必须可审计：
            // 事后能回答"哪一条、什么时候被谁（SYSTEM）补上的、补成了几点、依据是什么"。
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "SYSTEM",
                    action = "SESSION_ABANDONED_AUTO_CLOSED", targetType = "live_session",
                    targetStableId = abandoned.stableId, occurredAt = now,
                    detailJson = "{\"confirmedAt\":$now,\"endTime\":${outcome.endTime}," +
                        "\"lastSeenAt\":${outcome.lastSeen},\"intervalSeconds\":$expectedIntervalSeconds," +
                        "\"truncated\":${outcome.interrupted}}"
                )
            )
        }
        return outcome.rows
    }

    private suspend fun openNewSession(
        streamer: StreamerEntity,
        remote: RemoteLiveRoom?,
        now: Long
    ): LiveSessionEntity {
        val stableId = Ids.stableId("sess")
        val session = LiveSessionEntity(
            stableId = stableId,
            sessionKey = Ids.sessionKey(stableId),
            streamerId = streamer.id,
            startTime = now,
            endTime = null,
            startSource = DataSource.MONITOR_OBSERVED,
            endSource = null,
            durationSource = null,
            startConfidence = DataConfidence.PROVISIONAL,
            endConfidence = null,
            durationConfidence = null,
            startLocked = false,
            endLocked = false,
            durationLocked = false,
            titleAtStart = remote?.roomTitle,
            titleAtEnd = null,
            areaAtStart = areaLabel(remote),
            areaAtEnd = null,
            coverUrlAtStart = remote?.coverUrl,
            coverUrlAtEnd = null,
            durationSeconds = null,
            endReason = null,
            lifecycleState = com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState.ACTIVE,
            currentReliableIntervalId = null,
            lastConfirmedLiveAt = now,
            confirmedOfflineAt = null,
            autoUpdateProtected = false,
            correctionVersion = 0,
            createdAt = now,
            updatedAt = now,
            note = null,
            startTimeZone = clock.currentTimeZone()
        )
        streamerDao.bumpSourceDataVersion(now)
        try {
            liveSessionDao.insert(session)
        } catch (e: SQLiteConstraintException) {
            // 并发路径已创建 ACTIVE OPEN Session（0.6.15）：**这里就重读并复用**，
            // 而不是把信号丢给调用方 —— 调用方如果"捕获后正常返回"，Room 会提交半截事务
            // （状态已 CAS 成 LIVE、场次却没建），下一 Tick 不再产生转换，场次永久缺失（台账 H19）。
            // 真正复用不了（重读仍为空）才抛信号，让事务整体回滚。
            val existing = liveSessionDao.findActiveOpenSession(streamer.id)
            if (existing != null) {
                android.util.Log.w(
                    "Monitor",
                    "开播场次插入撞唯一约束，改为复用已有 OPEN session=${existing.stableId}"
                )
                return existing
            }
            throw DuplicateOpenSessionSignal
        }
        return session
    }

    /** 用于在事务内中止并映射为 DuplicateOpenSession 的信号。 */
    object DuplicateOpenSessionSignal : Exception("duplicate_active_open_session")

    private fun intervalOf(
        intervalId: String,
        streamer: StreamerEntity,
        sessionStableId: String?,
        now: Long,
        sequence: Long,
        monitorGeneration: Long,
        fencingToken: String
    ) = ReliableMonitorIntervalEntity(
        intervalId = intervalId,
        streamerStableId = streamer.stableId,
        sessionStableId = sessionStableId,
        startedAt = now,
        invalidatedAt = null,
        invalidReason = null,
        lastReliableObservationSequence = sequence,
        monitorGeneration = monitorGeneration,
        streamerMonitorGeneration = streamer.streamerMonitorGeneration,
        fencingToken = fencingToken,
        status = IntervalStatus.ACTIVE,
        updatedAt = now
    )

    private fun liveEventOf(
        streamer: StreamerEntity,
        sessionStableId: String?,
        type: LiveEventType,
        at: Long,
        eventSequence: Long,
        observationSequence: Long,
        monitorGeneration: Long,
        fencingToken: String,
        transitionId: String,
        configVersion: Long
    ) = LiveEventEntity(
        eventId = Ids.stableId("evt"),
        streamerId = streamer.id,
        streamerStableId = streamer.stableId,
        sessionStableId = sessionStableId,
        eventType = type,
        eventConfirmedAt = at,
        eventSequence = eventSequence,
        observationSequence = observationSequence,
        monitorGeneration = monitorGeneration,
        streamerMonitorGeneration = streamer.streamerMonitorGeneration,
        fencingToken = fencingToken,
        transitionId = transitionId,
        configVersion = configVersion,
        createdAt = at
    )

    private suspend fun createStartNotification(
        event: LiveEventEntity,
        streamer: StreamerEntity,
        remote: RemoteLiveRoom?,
        sessionStableId: String?,
        policy: StreamerNotificationPolicy,
        configVersion: Long,
        eventType: com.example.bilimonitor.data.local.NotificationEventType =
            com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED
    ) {
        val now = clock.nowWall()
        val payload = NotificationPayloads.live(
            eventKey = Ids.eventKeyForEvent(event.eventId),
            streamerStableId = streamer.stableId,
            sessionStableId = sessionStableId,
            eventId = event.eventId,
            name = streamer.name,
            title = remote?.roomTitle ?: streamer.roomTitle,
            area = areaLabel(remote) ?: streamer.areaName,
            url = remote?.liveUrl ?: streamer.liveUrl,
            cover = remote?.coverUrl
        )
        NotificationOutboxWriter.createForEvent(
            db = db, clock = clock,
            event = event, streamerId = streamer.id,
            payloadJson = payload, policy = policy, configVersion = configVersion, now = now,
            eventType = eventType
        )
    }

    private suspend fun createEndNotification(
        event: LiveEventEntity,
        streamer: StreamerEntity,
        policy: StreamerNotificationPolicy,
        configVersion: Long
    ) {
        val now = clock.nowWall()
        val payload = NotificationPayloads.live(
            eventKey = Ids.eventKeyForEvent(event.eventId),
            streamerStableId = streamer.stableId,
            sessionStableId = event.sessionStableId,
            eventId = event.eventId,
            name = streamer.name,
            title = null,
            area = null,
            url = streamer.liveUrl,
            cover = null
        )
        // 关播通知不走聚合（批量开播聚合只针对 LIVE 方向）。
        // 免打扰时段同样抑制关播通知：场次照常关闭，只是不打扰用户（原规范 22.3）。
        // ★ 两个"不发"的原因必须分开处理（2026 复查）：主播开关是**用户自己的设置**，
        //   不需要留痕；免打扰是**系统替他做的抑制**，必须留一条痕，否则"昨晚主播下播
        //   我没收到"无法与"通知丢了"区分。
        if (!policy.notifyEnd) return
        if (policy.quietHoursActive) {
            NotificationOutboxWriter.recordSuppressed(
                db = db, eventKey = Ids.eventKeyForEvent(event.eventId),
                streamerId = streamer.id, sourceEventId = event.eventId,
                eventType = com.example.bilimonitor.data.local.NotificationEventType.END_CONFIRMED,
                payloadJson = payload, now = now,
                reason = NotificationOutboxWriter.SUPPRESSED_QUIET_HOURS,
                configVersion = configVersion
            )
            return
        }
        NotificationOutboxWriter.createSingle(
            db = db, clock = clock,
            event = event, streamerId = streamer.id,
            eventType = com.example.bilimonitor.data.local.NotificationEventType.END_CONFIRMED,
            payloadJson = payload, now = now,
            enabled = true, configVersion = configVersion
        )
    }

    /**
     * 存储列 `streamer.freshnessStatus` 的写入值。
     *
     * ★ 判定式本身已搬到 [FreshnessPolicy.freshnessOf]（**公共纯函数**）：
     *   修复前它是这里的 `private`，于是"引擎写入的口径"与"界面读取时的理解"可以各走各的 ——
     *   界面甚至把这个**写入时刻的快照**当成了"此刻是否过期"（本次修复的根因）。
     *   现在两处引用同一个函数，只有一个真相。
     *
     * 这一层保留 `suspend` 与 try/catch 只因为它要读配置（IO）：配置读不出来时返回
     * [DataFreshness.UNKNOWN]（宁可说"不知道"，也不要拿一个默认值去写库）。
     *
     * @param tickPeriodSeconds 本轮实际检查周期（秒）；0 = 不知道（不抬高阈值）
     */
    private suspend fun freshnessOf(
        lastConfirmedAt: Long?,
        now: Long,
        tickPeriodSeconds: Int = 0
    ): DataFreshness {
        return try {
            val staleSeconds = configDao.getConfig()?.freshnessStaleSeconds
                ?: FreshnessPolicy.DEFAULT_THRESHOLD_SECONDS
            FreshnessPolicy.freshnessOf(
                lastConfirmedAt = lastConfirmedAt,
                now = now,
                thresholdSeconds = FreshnessPolicy.effectiveThresholdSeconds(
                    thresholdSeconds = staleSeconds,
                    tickPeriodSeconds = tickPeriodSeconds
                )
            )
        } catch (e: Exception) {
            DataFreshness.UNKNOWN
        }
    }

    /**
     * [pendingWritePlan] 的结论：这次观察该拿计数行怎么办（缺陷 1）。
     *
     * 为什么放在类里而不是 companion 里：companion 内部嵌套的类型在 Kotlin 里必须写成
     * `MonitorRepository.Companion.PendingWritePlan` 才能引用，单测里那个写法既长又容易被
     * 误当成"companion 里的私有细节"；放在类里就能写成 `MonitorRepository.PendingWritePlan`。
     */
    enum class PendingWritePlan {
        /** 没有计数行 → 新建（IGNORE：并发时先到的那一行说了算）。 */
        Create,

        /** 计数行属于**上一个代际** → 必须整行重建（REPLACE），把计数真正清零。 */
        ResetForNewGeneration,

        /** 计数行属于当前代际 → 沿用，继续累加。 */
        Keep
    }

    /** [pendingCountSkipOf] 的结论：这次计数为什么没写进去（只用于审计留痕）。 */
    enum class PendingCountSkip {
        /** 计数行已经不在了：转换已提交（复位）、暂停/软删除（删行）。 */
        RowGone,

        /** 行里有**更新的**观察序号：本次观察已过期，别人的计数才是当前值。 */
        NewerObservation,

        /** 主播当前不在监控中：已暂停/已软删除，不得再推进任何计数（缺陷 3 的语义）。 */
        MonitoringPaused
    }

    companion object {
        /**
         * 断档截断（台账 H19-A2）：决定"关播时刻该记成什么"。
         *
         * 背景：进程被杀/长时间没检查时，恢复后第一次观察到 OFFLINE 若直接用 `now` 当关播时刻，
         * 会写出一条"时长 = 整个断档"的假场次（09:00 开播、10:00 进程被杀、22:00 恢复 → 13 小时），
         * 并污染历史、统计、导出与备份。
         *
         * 判据：距**上一次成功观察**超过 2 个检查间隔 = 中间断过 → 把结束时刻收到
         * `lastCheckedAt`（最后一次确认"那时候还在播"）。
         *
         * 为什么阈值必须由调用方传入、不能写死：省电模式的调度周期是 15~240 分钟，
         * 写死 15 分钟会把正常的长周期误判成断档，把合法场次截短。
         * 拿不到间隔（为 0）或没有上次检查时间时**不猜**，一律返回 now。
         *
         * @return 实际应写入的 endTime；等于 [now] 表示没有截断
         */
        fun truncatedEndTime(now: Long, lastCheckedAt: Long?, intervalSeconds: Int): Long {
            if (lastCheckedAt == null || intervalSeconds <= 0) return now
            val gapMs = now - lastCheckedAt
            if (gapMs <= 0) return now
            return if (gapMs > 2L * intervalSeconds * 1000L) lastCheckedAt else now
        }

        /**
         * 缺陷 2 的时间窗判据：一条 ABANDONED OPEN 场次是否属于**同一次直播**的短暂中断。
         *
         * 窗口长度 = `2 × 本轮实际检查周期`，与 [truncatedEndTime] 的断档判据**同一个口径**
         * （"距上一次成功观察超过 2 个检查周期 ⇒ 中间断过"）—— 不新造配置项，
         * 也不会出现"截断认为断过、复用认为没断"这种自相矛盾。
         *
         * @param abandonedAt 锚点 = 该场次最后一次被写入的时刻（`abandon()` 把"停止跟踪这一刻"
         *   写在 `updatedAt` 上）。**不能用 startTime / lastConfirmedLiveAt**：一场连续三小时的
         *   直播里它们可能一直停在开播那一刻，会把同一次直播误判成陈旧并切成两段。
         *   已知边界（如实记录）：若用户在悬空期间手工修正过这条场次（`casManualCorrect` 也会推进
         *   `updatedAt`），锚点会被推后 ⇒ 这一次会被判成"同一次直播"而复用 —— 也就是**退化成修复前
         *   的行为**，不会比修复前更糟（既不误关，也不引入新的假数据）。
         * @return true = 同一次直播（可以复用该场次）；false = 陈旧场次（必须先关闭再新建）。
         *   拿不到周期（<= 0）或时间戳倒挂（时钟回拨/未来时间）时返回 **true** ——
         *   与 [truncatedEndTime] "拿不到间隔就不猜"同一条纪律：**只有能证明陈旧才关闭**。
         */
        fun sameLiveRun(now: Long, abandonedAt: Long, expectedIntervalSeconds: Int): Boolean {
            if (expectedIntervalSeconds <= 0) return true
            val idleMs = now - abandonedAt
            if (idleMs <= 0) return true
            return idleMs <= 2L * expectedIntervalSeconds * 1000L
        }

        /**
         * 缺陷 1 的核心判据（抽成纯函数以便单测）：代际变化时**是否必须重置计数**。
         *
         * 代际不一致有两种来源，都必须重置 —— 它们的共同含义是"这一行属于上一次监控运行"：
         *  - `monitorGeneration`（全局租约代际）：进程重启、租约被接管、恢复检查；
         *  - `streamerMonitorGeneration`（主播级代际）：暂停/恢复监控、软删除。
         *
         * 修复前的实现是 `ensurePendingTransition(existing.copy(monitorGeneration = 新代际))`，
         * 而那个 DAO 方法是 `OnConflictStrategy.IGNORE`、表 PK 就是 `streamerId`
         * ⇒ 行已存在时 SQLite 整条 INSERT 作废 ⇒ 代际与计数**根本没被重置**。
         */
        fun pendingWritePlan(
            rowExists: Boolean,
            rowMonitorGeneration: Long,
            rowStreamerGeneration: Long,
            monitorGeneration: Long,
            streamerMonitorGeneration: Long
        ): PendingWritePlan = when {
            !rowExists -> PendingWritePlan.Create
            rowMonitorGeneration != monitorGeneration ||
                rowStreamerGeneration != streamerMonitorGeneration -> PendingWritePlan.ResetForNewGeneration
            else -> PendingWritePlan.Keep
        }

        /**
         * 缺陷 1 的另一半：计数 CAS 返回 0 行时**为什么**没写进去（纯函数，可单测）。
         *
         * DAO 的 KDoc 一直写着"上下文变化时必须返回 0，调用方重读后重算"，
         * 而修复前调用方**根本不看返回值** —— 0 行被当成成功，这次计数就静默丢了
         * （与"任何降级都不允许静默发生"的仓规相反）。
         *
         * 判据与 [StreamerDao.updatePendingTransitionCas] 的 WHERE **一一对应**
         * （函数的每个分支都对应 SQL 里一条真实条件，这也是它能被单测保证的原因）：
         *  - 行不存在 ⇒ [PendingCountSkip.RowGone]（转换已提交复位 / 暂停或软删除删了行）；
         *  - `lastObservationSequence >= 本次序号` ⇒ [PendingCountSkip.NewerObservation]；
         *  - `monitoringEnabled = 1` 不成立 ⇒ [PendingCountSkip.MonitoringPaused]。
         *
         * 返回 null = 三条条件在重读结果里**全部成立**，即在同一事务快照下 SQL 本该写进去：
         * 这不可能发生（重读与那次 UPDATE 用的是同一个快照），返回 null 起的是
         * **"本函数与 SQL 是否还对得上"的自检**作用 —— 一旦它在审计里出现，
         * 就说明有人改了 SQL 而没改这里。因此这里**不编造**第四个枚举值来假装覆盖它。
         *
         * 注意：三个分支的处置都是"放弃本次计数 + 写一条审计"，**没有"重试"分支** ——
         * 在同一个事务里重读不可能读出一个"能写进去"的状态，
         * 造一个走不到的重试分支只会误导后来者。
         */
        fun pendingCountSkipOf(
            rowExists: Boolean,
            rowLastObservationSequence: Long,
            mySequence: Long,
            monitoringEnabled: Boolean
        ): PendingCountSkip? = when {
            !rowExists -> PendingCountSkip.RowGone
            rowLastObservationSequence >= mySequence -> PendingCountSkip.NewerObservation
            !monitoringEnabled -> PendingCountSkip.MonitoringPaused
            else -> null
        }

        fun areaLabel(remote: RemoteLiveRoom?): String? {
            val parent = remote?.parentAreaName?.takeIf { it.isNotBlank() }
            val child = remote?.areaName?.takeIf { it.isNotBlank() }
            return when {
                parent != null && child != null -> "$parent·$child"
                child != null -> child
                parent != null -> parent
                else -> null
            }
        }

        fun gapReasonOf(result: ObservationResult): GapReason = when (result) {
            ObservationResult.NETWORK_ERROR, ObservationResult.TIMEOUT -> GapReason.NETWORK_UNAVAILABLE
            ObservationResult.API_ERROR -> GapReason.API_UNAVAILABLE
            ObservationResult.PARTIAL -> GapReason.API_UNAVAILABLE
            else -> GapReason.UNKNOWN
        }
        // ★ 关于"限流被算进网络不可用"（缺陷 2）：本函数**不再需要**特殊处理 ——
        //   限流在 [RoomFailureMapping.observationResultOf] 里已经从 NETWORK_ERROR 改判为
        //   API_ERROR（服务端在正常工作、只是让我们退避），于是这里自然落进 API_UNAVAILABLE。
        //   口径只有那一张映射表一处，避免"这里再判一次、两处迟早不一致"。

        /**
         * 失败原始信号 → `monitoring_error_log.detail` 文本。
         *
         * 格式固定为 `key=value` 序列，便于日后用 grep/脚本统计"到底有多少次是 429、多少次是 5xx"：
         * `reason=… http=… api_code=… retry_after=… message=…`
         * 拿不到的字段写 `null`（**如实留空，不猜**）。
         */
        fun describeFailure(failure: com.example.bilimonitor.domain.policy.CallFailure?): String? {
            if (failure == null) return null
            return buildString {
                append("reason=").append(failure.reason.name)
                append(" http=").append(failure.httpStatus ?: "null")
                append(" api_code=").append(failure.apiCode ?: "null")
                if (failure.retryAfterSeconds != null) {
                    append(" retry_after=").append(failure.retryAfterSeconds)
                }
                append(" message=").append(failure.apiMessage ?: "null")
            }
        }

        /** `detail` 长度上限：诊断包会整包解压进内存，超长 message 会成倍放大（与引擎侧同一条理由）。 */
        const val DETAIL_MAX_CHARS = 320
    }
}
