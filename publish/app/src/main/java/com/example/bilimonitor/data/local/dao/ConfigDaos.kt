package com.example.bilimonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.RecoveryFinishReason
import com.example.bilimonitor.data.local.RecoverySessionStatus
import com.example.bilimonitor.data.local.StatisticsEligibility
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.CrashRecordEntity
import com.example.bilimonitor.data.local.entity.HealthEventEntity
import com.example.bilimonitor.data.local.entity.MonitoringConfigEntity
import com.example.bilimonitor.data.local.entity.MonitoringConfigRevisionEntity
import com.example.bilimonitor.data.local.entity.MonitoringErrorLogEntity
import com.example.bilimonitor.data.local.entity.ProblemStateEntity
import com.example.bilimonitor.data.local.entity.NotificationCooldownEntity
import com.example.bilimonitor.data.local.entity.RecoverySessionEntity
import com.example.bilimonitor.data.local.entity.SourceDataRevisionEntity
import com.example.bilimonitor.data.local.entity.StatisticsCacheEntity
import com.example.bilimonitor.data.local.entity.StatisticsRevisionEntity
import com.example.bilimonitor.data.local.entity.StatisticsSnapshotEntity
import com.example.bilimonitor.data.local.entity.StreamerMonitorPolicyEntity
import com.example.bilimonitor.data.local.entity.SystemRuntimeLockEntity
import com.example.bilimonitor.data.local.entity.TagEntity
import com.example.bilimonitor.data.local.entity.GroupEntity
import com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity
import com.example.bilimonitor.data.local.entity.StreamerGroupCrossRefEntity
import com.example.bilimonitor.data.local.entity.ExportSnapshotEntity
import com.example.bilimonitor.data.local.entity.ExportSnapshotLiveSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM monitoring_config WHERE singletonId = 1")
    suspend fun getConfig(): MonitoringConfigEntity?

    @Query("SELECT * FROM monitoring_config WHERE singletonId = 1")
    fun observeConfig(): Flow<MonitoringConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConfig(config: MonitoringConfigEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConfigRevision(revision: MonitoringConfigRevisionEntity)

    /** 配置变更：先写 revision，再原子推进 current version（0.6.14）。 */
    @Query(
        """
        UPDATE monitoring_config SET
            configVersion = :newConfigVersion, monitoringEnabled = :monitoringEnabled, mode = :mode,
            intervalSeconds = :intervalSeconds, batchSize = :batchSize, maxConcurrency = :maxConcurrency,
            timeoutSeconds = :timeoutSeconds, maxRetries = :maxRetries,
            retryBaseSeconds = :retryBaseSeconds, maxRetryDelaySeconds = :maxRetryDelaySeconds,
            circuitBreakerThreshold = :circuitBreakerThreshold,
            circuitBreakerRecoverySeconds = :circuitBreakerRecoverySeconds,
            aggregationEnabled = :aggregationEnabled, aggregationThreshold = :aggregationThreshold,
            aggregationWindowSeconds = :aggregationWindowSeconds,
            batchCooldownSeconds = :batchCooldownSeconds, freshnessStaleSeconds = :freshnessStaleSeconds,
            backgroundKeepAliveChoice = :keepAliveChoice, noGuaranteeAcknowledged = :noGuaranteeAcknowledged,
            noGuaranteeAcknowledgedAt = :noGuaranteeAcknowledgedAt,
            startConfirmationCount = :startConfirmationCount, endConfirmationCount = :endConfirmationCount,
            placeholderText = :placeholderText,
            retryFailureRatioPercent = :retryFailureRatioPercent,
            banRecheckIntervalSeconds = :banRecheckIntervalSeconds,
            circuitBreakerEnabled = :circuitBreakerEnabled,
            updatedAt = :now
        WHERE singletonId = 1 AND configVersion = :expectedConfigVersion
        """
    )
    suspend fun casUpdateConfig(
        expectedConfigVersion: Long, newConfigVersion: Long,
        monitoringEnabled: Boolean, mode: MonitoringMode,
        intervalSeconds: Int, batchSize: Int, maxConcurrency: Int,
        timeoutSeconds: Int, maxRetries: Int, retryBaseSeconds: Int, maxRetryDelaySeconds: Int,
        circuitBreakerThreshold: Int, circuitBreakerRecoverySeconds: Int,
        aggregationEnabled: Boolean, aggregationThreshold: Int, aggregationWindowSeconds: Int,
        batchCooldownSeconds: Int, freshnessStaleSeconds: Int,
        keepAliveChoice: BackgroundKeepAliveChoice, noGuaranteeAcknowledged: Boolean,
        noGuaranteeAcknowledgedAt: Long?,
        startConfirmationCount: Int, endConfirmationCount: Int, placeholderText: String,
        retryFailureRatioPercent: Int, banRecheckIntervalSeconds: Int,
        /** v10 熔断总开关（false = 失败批次一直重试、不暂停）。 */
        circuitBreakerEnabled: Boolean, now: Long
    ): Int

    @Query(
        """
        UPDATE monitoring_config
        SET backgroundKeepAliveChoice = :choice, noGuaranteeAcknowledged = :acknowledged,
            noGuaranteeAcknowledgedAt = :acknowledgedAt, updatedAt = :now
        WHERE singletonId = 1
        """
    )
    suspend fun updateKeepAliveChoice(choice: BackgroundKeepAliveChoice, acknowledged: Boolean, acknowledgedAt: Long?, now: Long): Int

    @Query("SELECT * FROM monitoring_config_revision WHERE configVersion = :version")
    suspend fun getRevision(version: Long): MonitoringConfigRevisionEntity?

    @Query("SELECT sourceDataVersion FROM source_data_revision WHERE singletonId = 1")
    suspend fun getSourceDataVersion(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceDataRevision(row: SourceDataRevisionEntity)

    // 统计版本：statisticalVersion 推进同样禁止应用层自增（0.6.14）。
    @Query(
        """
        INSERT INTO statistics_revision(statisticsVersion, sourceDataVersion, createdAt)
        SELECT COALESCE(MAX(statisticsVersion), 0) + 1, :sourceDataVersion, :now FROM statistics_revision
        """
    )
    suspend fun insertNextStatisticsRevision(sourceDataVersion: Long, now: Long)

    @Query("SELECT COALESCE(MAX(statisticsVersion), 0) FROM statistics_revision")
    suspend fun currentStatisticsVersion(): Long
}

@Dao
interface RuntimeLockDao {
    @Query("SELECT * FROM system_runtime_lock WHERE singletonId = 1")
    suspend fun get(): SystemRuntimeLockEntity?

    @Query("SELECT * FROM system_runtime_lock WHERE singletonId = 1")
    fun observe(): Flow<SystemRuntimeLockEntity?>

    /** 当前监控代际（恢复会话 CAS 用）。单行缺失时返回 0。 */
    @Query("SELECT COALESCE(monitorGeneration, 0) FROM system_runtime_lock WHERE singletonId = 1")
    suspend fun currentMonitorGeneration(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SystemRuntimeLockEntity)

    /** 获取或接管租约（0.6.35）：monitorGeneration 递增 + 新 fencingToken。 */
    @Query(
        """
        UPDATE system_runtime_lock
        SET monitoringLeaseId = :leaseId,
            runtimeInstanceId = :runtimeInstanceId,
            monitorGeneration = monitorGeneration + 1,
            fencingToken = :newFencingToken,
            leaseUntilWall = :leaseUntilWall,
            leaseUntilElapsed = :leaseUntilElapsed,
            leaseBootId = :bootId,
            updatedAt = :now
        WHERE singletonId = 1
          AND maintenanceMode = 'OFF'
          AND (monitoringLeaseId IS NULL
               OR monitoringLeaseId = :leaseId
               OR leaseUntilWall < :now)
        """
    )
    suspend fun acquireOrTakeoverLease(
        leaseId: String, runtimeInstanceId: String, newFencingToken: String,
        leaseUntilWall: Long, leaseUntilElapsed: Long, bootId: String, now: Long
    ): Int

    /** 心跳续期：只有当前持有者可以续期；不递增 generation、不更换 token。 */
    @Query(
        """
        UPDATE system_runtime_lock
        SET leaseUntilWall = :newLeaseUntilWall,
            leaseUntilElapsed = :newLeaseUntilElapsed,
            updatedAt = :now
        WHERE singletonId = 1
          AND monitoringLeaseId = :leaseId
          AND fencingToken = :fencingToken
          AND maintenanceMode = 'OFF'
        """
    )
    suspend fun renewLease(leaseId: String, fencingToken: String, newLeaseUntilWall: Long, newLeaseUntilElapsed: Long, now: Long): Int

    /** 释放租约（文档原文中的 workerInstanceId 在本表为 runtimeInstanceId，此处修正）。 */
    @Query(
        """
        UPDATE system_runtime_lock
        SET monitoringLeaseId = NULL, leaseUntilWall = NULL, leaseUntilElapsed = NULL,
            leaseBootId = NULL, fencingToken = NULL, runtimeInstanceId = NULL, updatedAt = :now
        WHERE singletonId = 1 AND monitoringLeaseId = :leaseId
        """
    )
    suspend fun releaseLease(leaseId: String, now: Long): Int

    /** 进入维护模式：夺取维护权与清空租约必须在同一条 UPDATE 内完成（0.6.35）。 */
    @Query(
        """
        UPDATE system_runtime_lock
        SET maintenanceMode = :mode, monitoringLeaseId = NULL,
            leaseUntilWall = NULL, leaseUntilElapsed = NULL, leaseBootId = NULL,
            fencingToken = NULL, runtimeInstanceId = NULL, updatedAt = :now
        WHERE singletonId = 1
          AND maintenanceMode = 'OFF'
          AND (monitoringLeaseId IS NULL OR leaseUntilWall < :now)
        """
    )
    suspend fun enterMaintenance(mode: MaintenanceMode, now: Long): Int

    /**
     * **用户主动发起**的维护接管：允许直接夺走仍在有效期内的监控租约。
     *
     * 为什么需要它：[enterMaintenance] 按 0.6.35 要求"租约已过期或不存在"才允许接管，
     * 而监控引擎每 180 秒 Tick 一次、租约 TTL 90 秒（`LEASE_DURATION_MS`），
     * 于是**约一半的时间**用户点"导出"都会撞上一个有效租约，
     * 得到一句与实际原因无关的"导出/恢复/清理正在进行，请稍后再试"。
     *
     * 为什么安全：真正的写入保护是 `monitorGeneration` + `fencingToken`，不是租约本身 ——
     * 这里与 [acquireOrTakeoverLease] 一样递增 generation、清空 token，
     * 引擎下一次续期/写入会拿到 0 行并判定 LEASE_LOST 而停止本轮 Tick
     * （引擎在 `ensureLease()` 返回 null 时直接 `return lease_lost`）。
     * 数据一致性因此由 fencing 保证，而非由"等租约自然过期"保证。
     */
    @Query(
        """
        UPDATE system_runtime_lock
        SET maintenanceMode = :mode, monitoringLeaseId = NULL,
            leaseUntilWall = NULL, leaseUntilElapsed = NULL, leaseBootId = NULL,
            fencingToken = NULL, runtimeInstanceId = NULL,
            monitorGeneration = monitorGeneration + 1, updatedAt = :now
        WHERE singletonId = 1
          AND maintenanceMode = 'OFF'
        """
    )
    suspend fun enterMaintenanceForcing(mode: MaintenanceMode, now: Long): Int

    /**
     * 冷启动时清掉残留的维护模式。
     *
     * 维护模式是**内存中操作的持久化标记**：进程若在导出/恢复/清理中途被杀，
     * 这个标记会永远留在库里。后果不止是"导出一直报正在进行" ——
     * 引擎的取租约语句要求 `maintenanceMode = 'OFF'`，于是**监控也会永久停摆**，
     * 用户只能清应用数据才能恢复。
     */
    @Query("UPDATE system_runtime_lock SET maintenanceMode = 'OFF', updatedAt = :now WHERE singletonId = 1 AND maintenanceMode <> 'OFF'")
    suspend fun resetStaleMaintenance(now: Long): Int

    @Query("UPDATE system_runtime_lock SET maintenanceMode = 'OFF', updatedAt = :now WHERE singletonId = 1 AND maintenanceMode = :mode")
    suspend fun exitMaintenance(mode: MaintenanceMode, now: Long): Int

    @Query("UPDATE source_data_revision SET sourceDataVersion = sourceDataVersion + 1, updatedAt = :now WHERE singletonId = 1")
    suspend fun bumpSourceDataVersion(now: Long): Int
}

@Dao
interface PolicyDao {
    @Query("SELECT * FROM streamer_monitor_policy WHERE streamerId = :streamerId")
    suspend fun get(streamerId: Long): StreamerMonitorPolicyEntity?

    @Query("SELECT * FROM streamer_monitor_policy WHERE streamerId = :streamerId")
    fun observe(streamerId: Long): Flow<StreamerMonitorPolicyEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: StreamerMonitorPolicyEntity)

    @Query("SELECT * FROM streamer_monitor_policy")
    suspend fun listAll(): List<StreamerMonitorPolicyEntity>
}

@Dao
interface RecoverySessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRequested(row: RecoverySessionEntity): Long

    @Query(
        """
        UPDATE recovery_session SET status = 'RUNNING'
        WHERE recoverySessionId = :id AND status = 'REQUESTED' AND monitorGeneration = :monitorGeneration
        """
    )
    suspend fun casToRunning(id: String, monitorGeneration: Long): Int

    @Query(
        """
        UPDATE recovery_session
        SET status = :terminalStatus, finishedAt = :now, finishReason = :finishReason
        WHERE recoverySessionId = :id AND status = 'RUNNING'
        """
    )
    suspend fun casToTerminal(id: String, terminalStatus: RecoverySessionStatus, finishReason: RecoveryFinishReason, now: Long): Int

    /**
     * 结算**尚未进入 RUNNING** 的会话（REQUESTED）。
     * `casToTerminal` 只匹配 RUNNING，用它去结算 REQUESTED 行会永远影响 0 行 ——
     * 残留的 REQUESTED 行受部分唯一索引 `idx_recovery_one_requested` 约束，
     * 会让本进程之后每次 runRecoveryCheck 的插入被 IGNORE 掉，
     * **恢复检查在本进程生命周期内被静默禁用**（只有下次冷启动才清）。
     */
    @Query(
        """
        UPDATE recovery_session
        SET status = :terminalStatus, finishedAt = :now, finishReason = :finishReason
        WHERE recoverySessionId = :id AND status = 'REQUESTED'
        """
    )
    suspend fun casRequestedToTerminal(id: String, terminalStatus: RecoverySessionStatus, finishReason: RecoveryFinishReason, now: Long): Int

    @Query("SELECT * FROM recovery_session WHERE status = 'RUNNING'")
    suspend fun findRunning(): List<RecoverySessionEntity>

    @Query("SELECT * FROM recovery_session WHERE status = 'REQUESTED'")
    suspend fun findRequested(): List<RecoverySessionEntity>

    /** 崩溃恢复：RUNNING 行一律结算为 TIMEOUT（0.6.33）。 */
    @Query(
        """
        UPDATE recovery_session
        SET status = 'TIMEOUT', finishedAt = :now, finishReason = 'TIMEOUT'
        WHERE status = 'RUNNING'
        """
    )
    suspend fun settleStaleRunning(now: Long): Int

    /** 崩溃恢复：REQUESTED 未被消费的行同样作废（原规范 177：恢复检查模式）。 */
    @Query(
        """
        UPDATE recovery_session
        SET status = 'CANCELLED', finishedAt = :now, finishReason = 'SUPERSEDED'
        WHERE status = 'REQUESTED'
        """
    )
    suspend fun cancelStaleRequested(now: Long): Int

    @Query("SELECT * FROM recovery_session WHERE recoverySessionId = :id")
    suspend fun findById(id: String): RecoverySessionEntity?

    @Query("SELECT * FROM recovery_session ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RecoverySessionEntity>

    @Query("SELECT * FROM recovery_session WHERE status IN ('REQUESTED','RUNNING') LIMIT 1")
    suspend fun findActive(): RecoverySessionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: RecoverySessionEntity): Long
}

@Dao
interface CrashRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: CrashRecordEntity): Long

    @Query("SELECT * FROM crash_record ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CrashRecordEntity>>

    @Query("SELECT * FROM crash_record ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CrashRecordEntity>

    @Query("SELECT * FROM crash_record WHERE recoveredAt IS NULL ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentUnrecovered(limit: Int): List<CrashRecordEntity>

    @Query("UPDATE crash_record SET recoveredAt = :now WHERE crashId = :crashId AND recoveredAt IS NULL")
    suspend fun markRecovered(crashId: String, now: Long): Int

    /** 保留策略：崩溃记录保留 90 天，避免无界增长。 */
    @Query(
        """
        DELETE FROM crash_record
        WHERE crashId IN (
            SELECT crashId FROM crash_record WHERE occurredAt < :before LIMIT :limit
        )
        """
    )
    suspend fun cleanupOld(before: Long, limit: Int): Int
}

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAudit(row: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY occurredAt DESC LIMIT :limit")
    fun observeAudit(limit: Int): Flow<List<AuditLogEntity>>

    @Insert
    suspend fun insertMonitoringError(row: MonitoringErrorLogEntity)

    @Query("SELECT * FROM monitoring_error_log ORDER BY occurredAt DESC LIMIT :limit")
    fun observeMonitoringErrors(limit: Int): Flow<List<MonitoringErrorLogEntity>>

    @Insert
    suspend fun insertAppError(row: ApplicationErrorLogEntity)

    @Query("SELECT * FROM application_error_log ORDER BY occurredAt DESC LIMIT :limit")
    fun observeAppErrors(limit: Int): Flow<List<ApplicationErrorLogEntity>>

    @Insert
    suspend fun insertHealthEvent(row: HealthEventEntity)

    @Query("SELECT * FROM health_event ORDER BY occurredAt DESC LIMIT :limit")
    fun observeHealthEvents(limit: Int): Flow<List<HealthEventEntity>>

    /** 指定组件最近一次健康状态；用于进程重启后恢复"当前是否处于故障期"的判断。 */
    @Query("SELECT toStatus FROM health_event WHERE component = :component ORDER BY occurredAt DESC LIMIT 1")
    suspend fun lastHealthStatus(component: String): MonitorHealthStatus?

    // ---- 诊断包导出（原规范 219）----

    @Query("SELECT * FROM monitoring_error_log ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentMonitoringErrors(limit: Int): List<MonitoringErrorLogEntity>

    @Query("SELECT * FROM application_error_log ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentAppErrors(limit: Int): List<ApplicationErrorLogEntity>

    @Query("SELECT * FROM health_event ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun recentHealthEvents(limit: Int): List<HealthEventEntity>

    @Query("SELECT * FROM monitoring_error_log WHERE streamerStableId = :streamerStableId ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun listErrorsByStreamer(streamerStableId: String, limit: Int): List<MonitoringErrorLogEntity>

    /**
     * 传输类错误在指定时刻之后的条数。
     * 用于让「连续传输失败次数」在进程重启后仍然可恢复：
     * 该计数原本只存在内存 AtomicInteger 中，重启即归零，导致间隔较长的
     * 监控模式（省电模式 15 分钟一周期）永远凑不满阈值、系统问题通知无法触发。
     *
     * ★ 白名单口径必须与 [com.example.bilimonitor.domain.policy.RoundFailureGate.isTransportFailure]
     *   一致（"真正的传输故障"= 网络断了 / 超时 / 服务端 5xx）：
     *   `TIMEOUT → NETWORK_TIMEOUT`、`NETWORK/SERVER_ERROR → NETWORK_UNAVAILABLE`
     *   （见 [com.example.bilimonitor.domain.policy.RoundFailureMapping.appErrorOf]）。
     *
     *   为什么**移除** `API_REJECTED`：它表示"服务端在正常工作、只是拒绝了这个请求"
     *   （`code != 0`、401/403/404/412），把它算成传输故障就是归因错误 ——
     *   会把用户引向"查路由器、查流量"，而真实原因可能是风控。同理 `RATE_LIMITED`
     *   （429 / 实测 `code:-352`）也**不得**计入：它有独立错误码，属于接口侧问题。
     *
     *   `UNKNOWN` 保留：它是"有失败行但一个原因信号都没有"的兜底桶，
     *   既不属于传输故障也不属于接口问题；剔除它会让该计数比原来更窄，
     *   在信息不足时更不容易凑满阈值（本项目在归因不明时宁可说"原因未能确定"）。
     *
     * ⚠️ 现状说明（2026-02 全仓库 grep 核实）：本方法**全仓库零调用者**
     *   （只有 KSP/Room 生成的 `LogDao_Impl` 与 R8 mapping 里有痕迹）。
     *   因此这次改动是**口径对齐**（避免下一个人照着错口径接上去），不是行为修复。
     */
    @Query(
        """
        SELECT COUNT(*) FROM monitoring_error_log
        WHERE occurredAt >= :since
          AND errorCode IN ('NETWORK_UNAVAILABLE','NETWORK_TIMEOUT','UNKNOWN')
        """
    )
    suspend fun countTransportErrorsSince(since: Long): Int

    /**
     * 保留策略（0.6.29 / 原规范 214）：超过 90 天且已超出「最近 retainNewest 条」范围的行才可删除。
     * 当前实现的日志表只有 errorCode、没有 severity 列，无法按"优先删 DEBUG/INFO"分级，
     * 因此统一采用"保底保留最近 N 条"的最保守策略。
     */
    @Query(
        """
        DELETE FROM monitoring_error_log
        WHERE errorId IN (
            SELECT errorId FROM monitoring_error_log
            WHERE occurredAt < :before
              AND errorId NOT IN (
                  SELECT errorId FROM monitoring_error_log
                  ORDER BY occurredAt DESC LIMIT :retainNewest
              )
            ORDER BY occurredAt ASC
            LIMIT :limit
        )
        """
    )
    suspend fun cleanupMonitoringErrors(before: Long, retainNewest: Int, limit: Int): Int

    /** 应用错误日志保留 7 天 / 最近 5000 条。 */
    @Query(
        """
        DELETE FROM application_error_log
        WHERE errorId IN (
            SELECT errorId FROM application_error_log
            WHERE occurredAt < :before
              AND errorId NOT IN (
                  SELECT errorId FROM application_error_log
                  ORDER BY occurredAt DESC LIMIT :retainNewest
              )
            ORDER BY occurredAt ASC
            LIMIT :limit
        )
        """
    )
    suspend fun cleanupAppErrors(before: Long, retainNewest: Int, limit: Int): Int
}

@Dao
interface ProblemDao {
    @Query("SELECT * FROM problem_state WHERE problemKey = :problemKey")
    suspend fun get(problemKey: String): ProblemStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ProblemStateEntity)

    @Query("SELECT * FROM problem_state WHERE active = 1")
    suspend fun listActive(): List<ProblemStateEntity>

    /**
     * 有上限的 `listActive()`：诊断包只要一份快照，不需要把全部活动问题读进内存。
     *
     * 为什么不直接把上面那个改成带参版本：它还被 `MaintenanceRepository`（容量告警的陈旧判断）
     * 与 `MonitoringEngine`（熔断/恢复判定）用 `firstOrNull { problemKey.startsWith(...) }`
     * 查特定 key —— 那两处**必须看到全量**，截断会漏判，所以无参版本保持原样，另开有界版本。
     *
     * 为什么必须配 `ORDER BY`：只写 `LIMIT` 不排序时，被截掉的是哪一批由 SQLite 自行决定，
     * 同一台设备两次导出可能给出不同子集；其它诊断查询都是"按时间倒序 + LIMIT"，这里对齐。
     */
    @Query("SELECT * FROM problem_state WHERE active = 1 ORDER BY firstObservedAt DESC LIMIT :limit")
    suspend fun listActive(limit: Int): List<ProblemStateEntity>

    @Query("SELECT * FROM problem_state WHERE active = 1")
    fun observeActive(): Flow<List<ProblemStateEntity>>

    @Query("SELECT * FROM notification_cooldown WHERE problemKey = :problemKey")
    suspend fun getCooldown(problemKey: String): NotificationCooldownEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCooldown(row: NotificationCooldownEntity)

    /**
     * 故障恢复后清除冷却记录（原规范 90）。
     * 原实现只写不删，导致一次故障之后 30 分钟内再次故障不再发系统通知。
     *
     * 注：`recoveredNotified` 原先只被写成 false、从不读取（这行注释曾如实记录该事实）；
     * 2026 复查起，容量问题的「已恢复」通知会读它，做"每个 episode 只发一次"的闸门
     * （见 MaintenanceRepository.checkCapacityWarning），所以它不再是死字段。
     * 而**删除**本行仍然只发生在"没有活跃问题、且恢复通知已经发过（或本来就没有问题）"时。
     */
    @Query("DELETE FROM notification_cooldown WHERE problemKey = :problemKey")
    suspend fun clearCooldown(problemKey: String): Int
}

@Dao
interface StatisticsCacheDao {
    @Query("SELECT * FROM statistics_cache WHERE cacheKey = :cacheKey AND sourceDataVersion = :sourceDataVersion LIMIT 1")
    suspend fun findValid(cacheKey: String, sourceDataVersion: Long): StatisticsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: StatisticsCacheEntity)

    /** 旧版本结果不得覆盖新版本（245.8）。 */
    @Query("DELETE FROM statistics_cache WHERE sourceDataVersion < :sourceDataVersion")
    suspend fun evictOlderThan(sourceDataVersion: Long): Int

    @Query("DELETE FROM statistics_cache WHERE calculatedAt < :before")
    suspend fun evictCalculatedBefore(before: Long): Int
}

@Dao
interface StatisticsSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: StatisticsSnapshotEntity)

    @Query("SELECT * FROM statistics_snapshot ORDER BY generatedAt DESC LIMIT 1")
    suspend fun latest(): StatisticsSnapshotEntity?

    /**
     * 同一 `sourceDataVersion` 下的规范快照（原规范 144 / 0.6.38）。
     * sourceDataVersion 一旦推进即视为失效，保证快照与业务数据同代。
     */
    @Query(
        """
        SELECT * FROM statistics_snapshot
        WHERE sourceDataVersion = :sourceDataVersion
          AND queryFingerprint = :queryFingerprint
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY generatedAt DESC LIMIT 1
        """
    )
    suspend fun findValid(sourceDataVersion: Long, queryFingerprint: String, now: Long): StatisticsSnapshotEntity?

    @Query("SELECT * FROM statistics_snapshot ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<StatisticsSnapshotEntity>

    /** 保留策略：只保留最近 [keep] 个快照，避免无界增长。 */
    @Query(
        """
        DELETE FROM statistics_snapshot
        WHERE snapshotId NOT IN (
            SELECT snapshotId FROM statistics_snapshot ORDER BY generatedAt DESC LIMIT :keep
        )
        """
    )
    suspend fun trimTo(keep: Int): Int

    @Query("DELETE FROM statistics_snapshot WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int
}

@Dao
interface TaxonomyDao {
    @Query("SELECT * FROM tag ORDER BY sortOrder, name")
    fun observeTags(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM `group` ORDER BY sortOrder, name")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: GroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignTag(ref: StreamerTagCrossRefEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignGroup(ref: StreamerGroupCrossRefEntity)

    @Query("DELETE FROM streamer_tag_cross_ref WHERE streamerId = :streamerId AND tagId = :tagId")
    suspend fun unassignTag(streamerId: Long, tagId: Long)

    @Query("DELETE FROM streamer_group_cross_ref WHERE streamerId = :streamerId AND groupId = :groupId")
    suspend fun unassignGroup(streamerId: Long, groupId: Long)

    @Query("SELECT * FROM tag ORDER BY sortOrder, name")
    suspend fun listTags(): List<TagEntity>

    @Query("SELECT * FROM `group` ORDER BY sortOrder, name")
    suspend fun listGroups(): List<GroupEntity>

    @Query("SELECT * FROM streamer_tag_cross_ref")
    suspend fun listTagRefs(): List<StreamerTagCrossRefEntity>

    @Query("SELECT * FROM streamer_group_cross_ref")
    suspend fun listGroupRefs(): List<StreamerGroupCrossRefEntity>

    @Query("SELECT * FROM tag WHERE stableId = :stableId")
    suspend fun findTagByStableId(stableId: String): TagEntity?

    @Query("SELECT * FROM `group` WHERE stableId = :stableId")
    suspend fun findGroupByStableId(stableId: String): GroupEntity?

    @Query("SELECT * FROM tag WHERE name = :name LIMIT 1")
    suspend fun findTagByName(name: String): TagEntity?

    @Query("SELECT t.* FROM tag t JOIN streamer_tag_cross_ref r ON r.tagId = t.id WHERE r.streamerId = :streamerId ORDER BY t.name")
    suspend fun tagsOfStreamer(streamerId: Long): List<TagEntity>

    @Query("DELETE FROM tag WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    // ---- 分组（原规范 16）----

    @Query("SELECT * FROM `group` WHERE name = :name LIMIT 1")
    suspend fun findGroupByName(name: String): GroupEntity?

    @Query(
        """
        SELECT g.* FROM `group` g
        JOIN streamer_group_cross_ref r ON r.groupId = g.id
        WHERE r.streamerId = :streamerId
        ORDER BY g.sortOrder, g.name
        """
    )
    suspend fun groupsOfStreamer(streamerId: Long): List<GroupEntity>

    @Query("DELETE FROM `group` WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    @Query("UPDATE `group` SET collapsed = :collapsed WHERE id = :groupId")
    suspend fun setGroupCollapsed(groupId: Long, collapsed: Boolean)

    // ---- 排序（用户要求：标签/分组支持排序）----
    //
    // `tag.sortOrder` / `group.sortOrder` **本来就是建表字段**（schemas/2.json 里就已经有，
    // 此后七个迁移没有一个动过它），所以排序同样不需要 schema 改动、不需要迁版本：
    // 缺的只是"改这一行"的语句和上层入口（见 TaxonomyRepository.moveTag / moveGroup）。
    // 现状要留意：createAndAssign 给每个新标签都写 sortOrder = 0，库里可能有一堆并列 0 的行，
    // 那时的顺序完全由 `ORDER BY sortOrder, name` 的 name 兜底 —— 上层重排时把整表归一化成
    // 0..n-1 才能让"排到第几位"真正生效。

    @Query("UPDATE tag SET sortOrder = :sortOrder WHERE id = :tagId")
    suspend fun updateTagSortOrder(tagId: Long, sortOrder: Int)

    @Query("UPDATE `group` SET sortOrder = :sortOrder WHERE id = :groupId")
    suspend fun updateGroupSortOrder(groupId: Long, sortOrder: Int)

    // ---- 多个主播与同一个标签/分组的批量关联（首页多选态要用）----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignTags(refs: List<StreamerTagCrossRefEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assignGroups(refs: List<StreamerGroupCrossRefEntity>)

    @Query("DELETE FROM streamer_tag_cross_ref WHERE streamerId = :streamerId")
    suspend fun clearTagsOfStreamer(streamerId: Long)

    @Query("DELETE FROM streamer_group_cross_ref WHERE streamerId = :streamerId")
    suspend fun clearGroupsOfStreamer(streamerId: Long)

    @Query("DELETE FROM streamer_tag_cross_ref WHERE tagId = :tagId AND streamerId IN (:streamerIds)")
    suspend fun unassignTagFrom(tagId: Long, streamerIds: List<Long>)

    @Query("DELETE FROM streamer_group_cross_ref WHERE groupId = :groupId AND streamerId IN (:streamerIds)")
    suspend fun unassignGroupFrom(groupId: Long, streamerIds: List<Long>)

    // ---- 删除前的影响面：会因此丢掉这个标签/分组的主播数 ----
    //
    // 只数**未软删**的主播，与首页可见口径一致：软删主播的关联行还留着（将来恢复仍然带标签），
    // 但它们不在任何列表里，把它们算进"会影响 N 位主播"会让确认框的数字对不上用户看到的东西。

    @Query(
        """
        SELECT COUNT(*) FROM streamer_tag_cross_ref r
        JOIN streamer s ON s.id = r.streamerId
        WHERE r.tagId = :tagId AND s.deletedAt IS NULL
        """
    )
    suspend fun countStreamersOfTag(tagId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM streamer_group_cross_ref r
        JOIN streamer s ON s.id = r.streamerId
        WHERE r.groupId = :groupId AND s.deletedAt IS NULL
        """
    )
    suspend fun countStreamersOfGroup(groupId: Long): Int

    /** 首页卡片要显示"这个主播有哪些标签"：一次取回这批主播的全部关联行，避免 N 次查询。 */
    @Query("SELECT * FROM streamer_tag_cross_ref WHERE streamerId IN (:streamerIds)")
    suspend fun tagRefsOf(streamerIds: List<Long>): List<StreamerTagCrossRefEntity>

    @Query("SELECT * FROM streamer_group_cross_ref WHERE streamerId IN (:streamerIds)")
    suspend fun groupRefsOf(streamerIds: List<Long>): List<StreamerGroupCrossRefEntity>
}

@Dao
interface ExportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(row: ExportSnapshotEntity)

    @Query("UPDATE export_snapshot SET status = :next WHERE snapshotId = :id AND status = :expectedCurrent")
    suspend fun casStatus(id: String, expectedCurrent: com.example.bilimonitor.data.local.ExportSnapshotStatus, next: com.example.bilimonitor.data.local.ExportSnapshotStatus): Int

    @Query("SELECT * FROM export_snapshot WHERE status IN ('BUILDING','READY','CONSUMING')")
    suspend fun findUnfinished(): List<ExportSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSessionRow(row: ExportSnapshotLiveSessionEntity)

    @Query("SELECT * FROM export_snapshot_live_session WHERE snapshotId = :snapshotId ORDER BY sessionStableId")
    suspend fun listSessionRows(snapshotId: String): List<ExportSnapshotLiveSessionEntity>

    @Query("DELETE FROM export_snapshot WHERE snapshotId IN (:ids)")
    suspend fun deleteSnapshots(ids: List<String>)

    @Query("DELETE FROM export_snapshot WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int
}
