package com.example.bilimonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilimonitor.data.local.NotificationAggregateStatus
import com.example.bilimonitor.data.local.NotificationHistoryDeliveryStatus
import com.example.bilimonitor.data.local.NotificationOutboxStatus
import com.example.bilimonitor.data.local.entity.NotificationAggregateEntity
import com.example.bilimonitor.data.local.entity.NotificationAggregateEventEntity
import com.example.bilimonitor.data.local.entity.NotificationDeliveryAttemptEntity
import com.example.bilimonitor.data.local.entity.NotificationHistoryEntity
import com.example.bilimonitor.data.local.entity.NotificationIdRegistryEntity
import com.example.bilimonitor.data.local.entity.NotificationOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationOutboxDao {
    /**
     * 唯一允许的 Outbox Claim（0.6.15）：只有 PENDING/RETRY_WAIT 且到达 nextAttemptAt 的行可被认领，
     * 原子抢占，不允许先读后写。
     *
     * `expiresAt > :now`：TTL（24h）已过的通知**不再投递**。
     * 停机一天以上再启动时，队列里的开播通知已经没有意义（主播早就下播了），
     * 投出去只会让用户看到一条错误的"正在直播"。
     * 这些行由 [expireOverduePending] 统一置为 EXPIRED。
     */
    @Query(
        """
        UPDATE notification_outbox
        SET status = 'PROCESSING', processingStartedAt = :now,
            leaseUntilWall = :leaseUntilWall, leaseUntilElapsed = :leaseUntilElapsed,
            leaseBootId = :bootId, workerInstanceId = :workerInstanceId,
            deliveryAttemptId = :deliveryAttemptId, attemptCount = attemptCount + 1
        WHERE outboxId = :outboxId
          AND status IN ('PENDING','RETRY_WAIT')
          AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
          AND expiresAt > :now
        """
    )
    suspend fun claimOutbox(
        outboxId: String, now: Long, leaseUntilWall: Long, leaseUntilElapsed: Long,
        bootId: String, workerInstanceId: String, deliveryAttemptId: String
    ): Int

    /**
     * 启动恢复：PROCESSING 且**租约已过期**的行。
     *
     * ★ 判定必须 boot 感知（2026 复查）：同一台设备、同一次开机内用单调时钟
     * `leaseUntilElapsed` 判定；跨开机（bootId 不同）才退回墙钟 `leaseUntilWall`。
     * 理由：`nowWall` 来自网络校时（`NetworkTimeSource`：未同步时用设备钟，同步后切到
     * elapsedRealtime + 服务器偏移），用户改系统时间也会动它 —— 只看墙钟时，
     * 时间**前跳**会把在途行误判成过期、被并发回收抢走重投（同一条通知重复提醒），
     * **后跳**则让在途行长期停在 PROCESSING（`findDue` 不取它）而投递停滞。
     * 这三列本来就是为此写入的（`claimOutbox` 同时写 wall / elapsed / bootId）。
     */
    @Query(
        """
        SELECT * FROM notification_outbox
        WHERE status = 'PROCESSING' AND (
              (leaseBootId = :bootId AND leaseUntilElapsed IS NOT NULL AND leaseUntilElapsed <= :elapsed)
              OR ((leaseBootId IS NULL OR leaseBootId <> :bootId OR leaseUntilElapsed IS NULL)
                  AND leaseUntilWall <= :now)
          )
        LIMIT :limit
        """
    )
    suspend fun findExpiredProcessing(now: Long, elapsed: Long, bootId: String, limit: Int): List<NotificationOutboxEntity>

    /** 取件查询。已过期（`expiresAt <= now`）的行不取，交由 [expireOverduePending] 结算。 */
    @Query(
        """
        SELECT * FROM notification_outbox
        WHERE status IN ('PENDING','RETRY_WAIT') AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)
          AND expiresAt > :now
        ORDER BY createdAt ASC LIMIT :limit
        """
    )
    suspend fun findDue(now: Long, limit: Int): List<NotificationOutboxEntity>

    /** 结果回写：必须带 status CAS，防止已 SENT 的行被旧 worker 覆盖。 */
    @Query(
        """
        UPDATE notification_outbox
        SET status = :newStatus, sentAt = :sentAt, lastError = :lastError,
            nextAttemptAt = :nextAttemptAt, leaseUntilWall = NULL, workerInstanceId = NULL
        WHERE outboxId = :outboxId AND status = 'PROCESSING' AND deliveryAttemptId = :deliveryAttemptId
        """
    )
    suspend fun finishAttempt(
        outboxId: String, deliveryAttemptId: String, newStatus: NotificationOutboxStatus,
        sentAt: Long?, nextAttemptAt: Long?, lastError: String?
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(outbox: NotificationOutboxEntity)

    @Query("SELECT * FROM notification_outbox WHERE outboxId = :outboxId")
    suspend fun findById(outboxId: String): NotificationOutboxEntity?

    @Query("SELECT * FROM notification_outbox WHERE eventKey = :eventKey LIMIT 1")
    suspend fun findByEventKey(eventKey: String): NotificationOutboxEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM notification_outbox WHERE sourceEventId = :eventId)")
    suspend fun existsForSourceEvent(eventId: String): Boolean

    /**
     * 场次删除用：取出**以这些事件为来源**的单事件 Outbox 行 id。
     *
     * `notification_outbox.sourceEventId` 是 NO ACTION 外键，因此"删 live_event"之前
     * 必须先删掉指向它的 Outbox 行，否则 SQLite 直接抛
     * `FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)`。
     *
     * 只取 `sourceEventId` 命中的行：**批量（聚合）通知的 `sourceEventId` 为 NULL**
     * ——它代表同一时间窗里的多位主播，不因为其中一位的场次被删就失去意义，必须保留。
     */
    @Query("SELECT outboxId FROM notification_outbox WHERE sourceEventId IN (:eventIds)")
    suspend fun idsForSourceEvents(eventIds: List<String>): List<String>

    /**
     * 删除这些 Outbox 的投递尝试行。
     * `notification_delivery_attempt.outboxId` 同样是 NO ACTION 外键，子行必须先删。
     */
    @Query("DELETE FROM notification_delivery_attempt WHERE outboxId IN (:outboxIds)")
    suspend fun deleteAttemptsForOutboxIds(outboxIds: List<String>): Int

    /**
     * 删除这些 Outbox 行（必须在删 `live_event` 之前执行）。
     *
     * 不动 `notification_id_registry`：0.6.12 要求 eventKey→notificationId 永久稳定，
     * 注册表行与 Outbox 行是两回事，删 Outbox 不代表可以回收 id。
     */
    @Query("DELETE FROM notification_outbox WHERE outboxId IN (:outboxIds)")
    suspend fun deleteByOutboxIds(outboxIds: List<String>): Int

    @Query("SELECT EXISTS(SELECT 1 FROM notification_outbox WHERE aggregateId = :aggregateId)")
    suspend fun existsForAggregate(aggregateId: String): Boolean

    /** 启动恢复（0.6.34 唯一写入者语句之一）。 */
    @Query(
        """
        UPDATE notification_delivery_attempt
        SET finishedAt = :now, result = 'DELIVERY_UNKNOWN', recoveredBy = :runtimeInstanceId
        WHERE finishedAt IS NULL
          AND outboxId IN (
              SELECT outboxId FROM notification_outbox
              WHERE status = 'PROCESSING' AND (
              (leaseBootId = :bootId AND leaseUntilElapsed IS NOT NULL AND leaseUntilElapsed <= :elapsed)
              OR ((leaseBootId IS NULL OR leaseBootId <> :bootId OR leaseUntilElapsed IS NULL)
                  AND leaseUntilWall <= :now)
          )
          )
        """
    )
    suspend fun settleExpiredAttempts(now: Long, elapsed: Long, bootId: String, runtimeInstanceId: String): Int

    @Query(
        """
        UPDATE notification_outbox
        SET status = CASE WHEN expiresAt <= :now THEN 'EXPIRED' ELSE 'RETRY_WAIT' END,
            nextAttemptAt = CASE WHEN expiresAt <= :now THEN NULL ELSE :now END,
            lastError = 'DELIVERY_UNKNOWN_ON_RECOVERY',
            leaseUntilWall = NULL,
            leaseUntilElapsed = NULL,
            workerInstanceId = NULL,
            deliveryAttemptId = NULL
        WHERE status = 'PROCESSING' AND (
              (leaseBootId = :bootId AND leaseUntilElapsed IS NOT NULL AND leaseUntilElapsed <= :elapsed)
              OR ((leaseBootId IS NULL OR leaseBootId <> :bootId OR leaseUntilElapsed IS NULL)
                  AND leaseUntilWall <= :now)
          )
        """
    )
    suspend fun recoverExpiredProcessing(now: Long, elapsed: Long, bootId: String): Int

    /**
     * 回收"孤儿 DELIVERY_UNKNOWN"行（2026 复查新增）。
     *
     * 背景：`NotificationOutboxStatus.DELIVERY_UNKNOWN` 是枚举里的一员，但**当前没有任何写入者** ——
     * 真出现（旧版本数据、将来的新路径）时，它既不在 `findDue` 的状态集合里（不会被投递），
     * 也不在 `cleanupTerminal` 的终态集合里（不会被清理），且 `recoverExpiredProcessing` 只认
     * PROCESSING ⇒ 永久卡死。这条语句把它按 TTL 分流到 RETRY_WAIT / EXPIRED，纳入既有闭环。
     */
    @Query(
        """
        UPDATE notification_outbox
        SET status = CASE WHEN expiresAt <= :now THEN 'EXPIRED' ELSE 'RETRY_WAIT' END,
            nextAttemptAt = CASE WHEN expiresAt <= :now THEN NULL ELSE :now END,
            lastError = 'ORPHAN_DELIVERY_UNKNOWN_RECOVERED',
            leaseUntilWall = NULL, leaseUntilElapsed = NULL,
            workerInstanceId = NULL, deliveryAttemptId = NULL
        WHERE status = 'DELIVERY_UNKNOWN'
        """
    )
    suspend fun recoverOrphanDeliveryUnknown(now: Long): Int

    /**
     * 把**从未被认领**就已超期的行直接结算为 EXPIRED。
     *
     * 为什么需要它：`claimOutbox`/`findDue` 现在都把 `expiresAt > now` 作为条件，
     * 若不做这一步，过期行会永远停留在 PENDING/RETRY_WAIT：既投不出去、又永远不被清理
     * （`cleanupTerminal` 只清终态），而且 `countActiveOutbox` 会一直把它们算成积压。
     */
    @Query(
        """
        UPDATE notification_outbox
        SET status = 'EXPIRED', nextAttemptAt = NULL, lastError = 'EXPIRED_BEFORE_DELIVERY',
            leaseUntilWall = NULL, leaseUntilElapsed = NULL,
            workerInstanceId = NULL, deliveryAttemptId = NULL
        WHERE status IN ('PENDING','RETRY_WAIT') AND expiresAt <= :now
        """
    )
    suspend fun expireOverduePending(now: Long): Int

    /**
     * 投递过程中抛异常时把该行**显式放回队列**（而不是留在 PROCESSING 等租约过期）。
     *
     * 为什么必须显式：PROCESSING 行只有"租约过期后的回收语句"能救，而回收原先只在
     * 冷启动与省电 Worker 里执行 —— 实时模式下进程可以连续存活数天，
     * 一次中断的投递就等于这条通知**永久丢失**（`findDue` 不会再取 PROCESSING）。
     *
     * 条件只写 `status = 'PROCESSING'`（不带 deliveryAttemptId）：
     * 异常是从 `deliverOne` 内部抛出的，调用方拿不到它的 attemptId；本应用是单进程写入者，
     * 不存在"另一个 worker 正持有同一行"的情况。
     */
    @Query(
        """
        UPDATE notification_outbox
        SET status = CASE
                WHEN expiresAt <= :now THEN 'EXPIRED'
                WHEN attemptCount >= :maxAttempts THEN 'FAILED'
                ELSE 'RETRY_WAIT' END,
            nextAttemptAt = CASE
                WHEN expiresAt <= :now OR attemptCount >= :maxAttempts THEN NULL
                ELSE :retryAt END,
            lastError = :lastError,
            leaseUntilWall = NULL, leaseUntilElapsed = NULL,
            workerInstanceId = NULL, deliveryAttemptId = NULL
        WHERE outboxId = :outboxId AND status = 'PROCESSING'
        """
    )
    suspend fun releaseProcessing(
        outboxId: String,
        now: Long,
        retryAt: Long,
        lastError: String,
        maxAttempts: Int
    ): Int

    /**
     * 把某条 Outbox 下**未结算**的尝试行收尾。
     *
     * 结果记为 `DELIVERY_UNKNOWN` 而不是 FAILED：异常可能发生在 `notify()` **之后**
     * （例如事务 B 或 history 写入失败），此时系统通知其实已经发出，
     * 标成 FAILED 会诱导后续流程用"没发出去"的结论做判断。
     */
    @Query(
        """
        UPDATE notification_delivery_attempt
        SET finishedAt = :now, result = 'DELIVERY_UNKNOWN', error = :error
        WHERE outboxId = :outboxId AND finishedAt IS NULL
        """
    )
    suspend fun settleAttemptByOutbox(outboxId: String, now: Long, error: String): Int

    @Insert
    suspend fun insertAttempt(attempt: NotificationDeliveryAttemptEntity)

    @Query("UPDATE notification_delivery_attempt SET finishedAt = :finishedAt, result = :result, error = :error WHERE attemptId = :attemptId AND finishedAt IS NULL")
    suspend fun finishAttemptRow(attemptId: String, finishedAt: Long, result: com.example.bilimonitor.data.local.DeliveryAttemptResult, error: String?): Int

    @Query("SELECT COUNT(*) FROM notification_outbox WHERE status IN ('PENDING','RETRY_WAIT','PROCESSING')")
    suspend fun countActiveOutbox(): Int

    /** 诊断包：按状态计数（只回一个数，不把行读进内存）。 */
    @Query("SELECT COUNT(*) FROM notification_outbox WHERE status = :status")
    suspend fun countByStatus(status: NotificationOutboxStatus): Int

    /**
     * 诊断包：最近若干条**终态**行。
     *
     * 用户报"没收到通知"时，需要能区分"根本没入队 / 卡在 PROCESSING / 投递失败"，
     * 这三种情况在诊断包里过去完全看不出来（只有一句"通知是否开启"）。
     * 只取状态与错误，payload 不导出（里面含主播昵称/房间标题，属于用户数据）。
     */
    @Query(
        """
        SELECT * FROM notification_outbox
        WHERE status IN ('SENT','FAILED','EXPIRED','CANCELLED')
        ORDER BY createdAt DESC LIMIT :limit
        """
    )
    suspend fun recentTerminal(limit: Int): List<NotificationOutboxEntity>

    @Query("SELECT * FROM notification_outbox ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotificationOutboxEntity>>

    /** 权限恢复扫描：未过期且 FAILED 的行转为 RETRY_WAIT（带上限分页，0.6.11）。 */
    @Query(
        """
        UPDATE notification_outbox
        SET status = 'RETRY_WAIT', nextAttemptAt = :now, lastError = NULL
        WHERE outboxId IN (
            SELECT outboxId FROM notification_outbox
            WHERE status = 'FAILED' AND expiresAt > :now
            LIMIT :limit
        )
        """
    )
    suspend fun reviveFailed(now: Long, limit: Int): Int

    /** 软删除主播：其未投递行置 CANCELLED（0.6.22.1）。 */
    @Query(
        """
        UPDATE notification_outbox
        SET status = 'CANCELLED', lastError = 'STREAMER_DELETED',
            leaseUntilWall = NULL, leaseUntilElapsed = NULL, workerInstanceId = NULL
        WHERE streamerId = :streamerId AND status IN ('PENDING','RETRY_WAIT')
        """
    )
    suspend fun cancelPendingByStreamer(streamerId: Long): Int

    /**
     * 清理：只允许清理终态行（0.6.12 保留策略）。
     * FAILED 亦为终态（0.6.11：重试耗尽/超期后不再自动复活），纳入清理避免无界增长。
     *
     * **关键**：删除条件里直接排除仍有子行引用的行。
     * `notification_history.outboxId` 与 `notification_delivery_attempt.outboxId` 都是
     * NO ACTION 外键且 `PRAGMA foreign_keys=ON`，而 history 保留 90 天、Outbox 只保留 30 天，
     * 30~90 天重叠区间内必然存在"Outbox 可删但 history 还没到期"的行。
     *
     * 原实现依赖"调用方先清完子行"，但只要子行清理被 `LIMIT` 截断（每次上限 500），
     * 这个前提就不成立 —— 实测会抛 `FOREIGN KEY constraint failed`，
     * 且异常被外层 runCatching 吞掉，导致后续的通知历史与聚合清理**永不执行**。
     *
     * 改为用 `NOT EXISTS` 把"无子行"作为**删除条件本身**后，顺序无关、批次大小无关：
     * 引用未到期的 Outbox 会被自动跳过，等其子行到期后自然在后续轮次被清理。
     */
    @Query(
        """
        DELETE FROM notification_outbox
        WHERE outboxId IN (
            SELECT o.outboxId FROM notification_outbox o
            WHERE o.status IN ('SENT','EXPIRED','CANCELLED','FAILED')
              AND o.createdAt < :before
              AND NOT EXISTS (SELECT 1 FROM notification_history h WHERE h.outboxId = o.outboxId)
              AND NOT EXISTS (SELECT 1 FROM notification_delivery_attempt a WHERE a.outboxId = o.outboxId)
            LIMIT :limit
        )
        """
    )
    suspend fun cleanupTerminal(before: Long, limit: Int): Int

    /**
     * 预清理已结算的投递尝试行，且其 Outbox 已属可清理终态。
     * 目的只是让下一轮 `cleanupTerminal` 能真正删掉 Outbox（它要求无子行）。
     * 同样受 `limit` 限制，但**不再影响正确性** —— 未清完的只是留到下一轮。
     */
    @Query(
        """
        DELETE FROM notification_delivery_attempt
        WHERE attemptId IN (
            SELECT a.attemptId FROM notification_delivery_attempt a
            JOIN notification_outbox o ON o.outboxId = a.outboxId
            WHERE a.finishedAt IS NOT NULL
              AND o.status IN ('SENT','EXPIRED','CANCELLED','FAILED')
              AND o.createdAt < :before
            LIMIT :limit
        )
        """
    )
    suspend fun cleanupSettledAttempts(before: Long, limit: Int): Int

    /** 未结算的投递尝试行，且其 Outbox 已经不存在（异常路径残留）。 */
    @Query("DELETE FROM notification_delivery_attempt WHERE finishedAt IS NULL AND outboxId NOT IN (SELECT outboxId FROM notification_outbox)")
    suspend fun deleteOrphanAttempts(): Int

    /*
     * 已删除：占位型投递尝试清理 `deleteUnstartedAttempts`。
     *
     * 它的条件是 `WHERE startedAt IS NULL`，而 `startedAt` 是 NOT NULL 且插入时必写
     * （`NotificationDispatcher.deliverOne` 的 insertAttempt），所以那条 SQL **恒不生效** ——
     * 维护页统计的"清理了占位 attempt"永远是 0（台账 H19-E7 / 第二轮审查）。
     * 调用点也已从 `MaintenanceRepository` 移除；真正的孤儿行由
     * `deleteOrphanAttempts`（Outbox 已不存在）与 `settleAttemptByOutbox`（异常路径显式收尾）覆盖。
     *
     * 若将来真要支持"先占位、再补 startedAt"，需要把列改成可空并写一次迁移，属于独立改动。
     */

    /**
     * 删除引用指定聚合、且**已无子行**的终态 Outbox。
     * `notification_outbox.aggregateId` 是 NO ACTION 外键，删聚合前必须先删这些行；
     * 但 SENT 的 Outbox 若其 history 尚未到期就不能删 —— 这类行会被 `NOT EXISTS` 跳过，
     * 对应聚合本轮保留，等下一轮再清（而不是像原实现那样永久卡住）。
     */
    @Query(
        """
        DELETE FROM notification_outbox
        WHERE outboxId IN (
            SELECT o.outboxId FROM notification_outbox o
            WHERE o.aggregateId IN (:aggregateIds)
              AND o.status IN ('SENT','EXPIRED','CANCELLED','FAILED')
              AND NOT EXISTS (SELECT 1 FROM notification_history h WHERE h.outboxId = o.outboxId)
              AND NOT EXISTS (SELECT 1 FROM notification_delivery_attempt a WHERE a.outboxId = o.outboxId)
        )
        """
    )
    suspend fun deleteChildlessOutboxForAggregates(aggregateIds: List<String>): Int
}

@Dao
interface NotificationIdRegistryDao {
    @Query("SELECT * FROM notification_id_registry WHERE eventKey = :eventKey")
    suspend fun findByEventKey(eventKey: String): NotificationIdRegistryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(row: NotificationIdRegistryEntity): Long

    /**
     * 下一个候选 id（`MAX(notificationId) + 1`）。
     *
     * **必须与插入放在同一个事务里**：这两步分开就是一个竞态窗口 ——
     * 两个入口读到同一个 candidate，后者撞 UNIQUE(notificationId) 索引。
     * 分配逻辑见 `NotificationOutboxWriter.getOrCreateNotificationId`，
     * 那里用 `db.withTransaction { … }` 把"读 MAX"和"插入占位"包在一起，
     * 因此调用方自己开不开事务都成立。
     */
    @Query("SELECT COALESCE(MAX(notificationId), 0) + 1 FROM notification_id_registry")
    suspend fun nextCandidate(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM notification_id_registry WHERE notificationId = :notificationId AND eventKey <> :eventKey)")
    suspend fun isTakenByOther(notificationId: Int, eventKey: String): Boolean
}

@Dao
interface NotificationAggregateDao {
    @Query("SELECT * FROM notification_aggregate WHERE bootId = :bootId AND status IN ('COLLECTING','READY') LIMIT 1")
    suspend fun findInProgress(bootId: String): NotificationAggregateEntity?

    @Query("SELECT * FROM notification_aggregate WHERE status IN ('COLLECTING','READY')")
    suspend fun listInProgress(): List<NotificationAggregateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(aggregate: NotificationAggregateEntity)

    @Query("SELECT * FROM notification_aggregate WHERE aggregateId = :aggregateId")
    suspend fun findById(aggregateId: String): NotificationAggregateEntity?

    @Query("UPDATE notification_aggregate SET status = :status, sentAt = :sentAt, eventCount = :eventCount WHERE aggregateId = :aggregateId AND status = :expected")
    suspend fun casStatus(aggregateId: String, expected: NotificationAggregateStatus, status: NotificationAggregateStatus, sentAt: Long?, eventCount: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bindEvent(row: NotificationAggregateEventEntity): Long

    @Query(
        """
        UPDATE notification_aggregate_event
        SET active = 0, aggregateId = NULL, bindingStatus = 'RELEASED', releasedAt = :now
        WHERE aggregateId = :aggregateId AND active = 1
        """
    )
    suspend fun releaseAll(aggregateId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM notification_aggregate_event WHERE aggregateId = :aggregateId AND active = 1")
    suspend fun countActiveEvents(aggregateId: String): Int

    @Query("SELECT * FROM notification_aggregate_event WHERE aggregateId = :aggregateId AND active = 1")
    suspend fun listActiveEvents(aggregateId: String): List<NotificationAggregateEventEntity>

    /** 崩溃恢复唯一可识别的不一致形态（0.6.10）。 */
    @Query(
        """
        SELECT DISTINCT a.aggregateId FROM notification_aggregate a
        JOIN notification_aggregate_event b ON b.aggregateId = a.aggregateId
        WHERE a.status = 'CANCELLED' AND b.active = 1
        """
    )
    suspend fun findCancelledWithActiveBindings(): List<String>

    @Query("SELECT * FROM notification_aggregate_event WHERE eventId = :eventId AND active = 1 LIMIT 1")
    suspend fun findActiveBinding(eventId: String): NotificationAggregateEventEntity?

    /** 事件是否被任何绑定行引用（含已 RELEASED 的审计行）——容量删除事件前必须检查（FK NO ACTION）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM notification_aggregate_event WHERE eventId = :eventId)")
    suspend fun bindingExistsForEvent(eventId: String): Boolean

    /**
     * 删除这些事件**已释放**的聚合绑定行（容量清理路径专用，2026 复查新增）。
     *
     * 为什么需要：`releaseAll` 把 `aggregateId` 置 NULL 之后，这些行再也不会随聚合删除被
     * CASCADE 带走（它们已经不挂在任何聚合上了），而 `bindingExistsForEvent` 又把它们算作引用 ——
     * 于是**只要一个事件进过一次聚合窗口**（哪怕那个窗口没凑够阈值），它的 `live_event` 行
     * 就永远删不掉，"限制记录数"在这部分静默失效。
     *
     * 与 [deleteBindingsForEvents]（场次删除路径，连 RELEASED 审计行一起删）的区别：
     * 这里**只删 `active = 0`**，把仍被进行中窗口持有的绑定（`active = 1`）留给批量通知 ——
     * 容量清理处理的确实都是最旧的已结束场次、绑定理应早已释放，但"理应"不能当正确性前提。
     */
    @Query("DELETE FROM notification_aggregate_event WHERE eventId IN (:eventIds) AND active = 0")
    suspend fun deleteReleasedBindingsForEvents(eventIds: List<String>): Int

    /**
     * 删除指向这些事件的聚合绑定行（含已 RELEASED 的审计行）。场次删除路径专用。
     *
     * 为什么必须删：`notification_aggregate_event.eventId` 是 NO ACTION 外键，
     * 而且**没有任何保留策略会清理它** —— 投递完成后 `releaseAll` 把 `aggregateId` 置 NULL，
     * 这些行不再挂在聚合上，既不会被"删聚合"级联带走（CASCADE 只对仍引用该聚合的行生效），
     * 也不在容量清理的范围内。于是**只要一个场次的开播事件进过一次聚合窗口，
     * 它就永久挡在 live_event 前面**：用户此后永远删不掉那条历史记录（外键失败）。
     * 事件本身被用户删除后，"这个事件属于哪个通知批次"的映射已失去意义，随事件一并删除。
     *
     * 只删 eventId 命中本场次的行：聚合实体、批量 Outbox、以及其它主播的绑定行都不动。
     */
    @Query("DELETE FROM notification_aggregate_event WHERE eventId IN (:eventIds)")
    suspend fun deleteBindingsForEvents(eventIds: List<String>): Int

    @Query("SELECT * FROM notification_aggregate WHERE status IN ('DISPATCHED','CANCELLED','EXPIRED') AND createdAt < :before LIMIT :limit")
    suspend fun findTerminalBefore(before: Long, limit: Int): List<NotificationAggregateEntity>

    /**
     * 收尾"卡在 FROZEN 的聚合"（复查发现的缺陷，两个独立代理分别指出）。
     *
     * 为什么必须有：批量 Outbox 一旦以终态收场却没投递成功（权限/渠道不可用 → 5 次尝试耗尽
     * → FAILED；或设备关机跨过 24 小时 TTL → EXPIRED），聚合就永久停在 FROZEN ——
     * `findInProgress` 只认 COLLECTING/READY（不会再处理它），`findTerminalBefore` 只认
     * DISPATCHED/CANCELLED/EXPIRED（保留策略也不会回收它）。后果是聚合行与它的绑定行
     * **无限期堆积**，而说明里那条聚合的状态永远与事实不符。
     *
     * 为什么条件里要求 `expiresAt <= :now`：Outbox 还在 TTL 内时，冷启动的
     * `reviveFailed` 仍有机会把它投出去（那时聚合必须留在 FROZEN 等 SENT）；
     * 过了 TTL 才是不可能再复活的终局。
     */
    @Query(
        """
        UPDATE notification_aggregate SET status = 'EXPIRED'
        WHERE status = 'FROZEN' AND EXISTS (
            SELECT 1 FROM notification_outbox o
            WHERE o.aggregateId = notification_aggregate.aggregateId
              AND o.status IN ('FAILED','EXPIRED','CANCELLED')
              AND o.expiresAt <= :now
        )
        """
    )
    suspend fun settleStuckFrozen(now: Long): Int

    /** 聚合是否仍被任何 Outbox 引用（含未到期的非终态行）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM notification_outbox WHERE aggregateId = :aggregateId)")
    suspend fun outboxExistsForAggregate(aggregateId: String): Boolean

    @Query("DELETE FROM notification_aggregate WHERE aggregateId IN (:aggregateIds)")
    suspend fun deleteByIds(aggregateIds: List<String>)

    @Query("SELECT * FROM notification_aggregate ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotificationAggregateEntity>>
}

@Dao
interface NotificationHistoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: NotificationHistoryEntity)

    @Query(
        """
        UPDATE notification_history
        SET deliveryStatus = :status, updatedAt = :now, detail = COALESCE(:detail, detail),
            attemptCount = :attemptCount,
            deliveredAt = CASE WHEN :status = 'SENT' THEN COALESCE(deliveredAt, :now) ELSE deliveredAt END
        WHERE outboxId = :outboxId
        """
    )
    suspend fun updateByOutboxId(outboxId: String, status: NotificationHistoryDeliveryStatus, attemptCount: Int, detail: String?, now: Long): Int

    @Query("SELECT * FROM notification_history ORDER BY recordedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotificationHistoryEntity>>

    @Query("SELECT * FROM notification_history WHERE outboxId = :outboxId LIMIT 1")
    suspend fun findByOutboxId(outboxId: String): NotificationHistoryEntity?

    /**
     * 用 Outbox 的真实终态校正通知历史（复查发现的缺陷）。
     *
     * 为什么需要：history 行只在**被认领投递**时创建，之后只有 Dispatcher 的事务 B 回写它；
     * 而租约回收（[NotificationOutboxDao.recoverExpiredProcessing]）、入队即过期
     * （[NotificationOutboxDao.expireOverduePending]）、软删除取消（[NotificationOutboxDao.cancelPendingByStreamer]）
     * 这三条纯 SQL 路径只改 Outbox、完全不碰 history —— 用户看到的就是"投递中"永远挂在那里，
     * 而 Outbox 早已 FAILED/EXPIRED/CANCELLED，诊断时无法判断到底发出去没有。
     * 这里把仍处于中间态的 history 行对齐到 Outbox 的终态与 lastError。
     */
    @Query(
        """
        UPDATE notification_history
        SET deliveryStatus = (
                SELECT CASE o.status
                    WHEN 'SENT' THEN 'SENT'
                    WHEN 'EXPIRED' THEN 'EXPIRED'
                    WHEN 'FAILED' THEN 'FAILED'
                    WHEN 'CANCELLED' THEN 'CANCELLED'
                    ELSE 'DELIVERY_UNKNOWN' END
                FROM notification_outbox o WHERE o.outboxId = notification_history.outboxId),
            detail = COALESCE(
                (SELECT o.lastError FROM notification_outbox o WHERE o.outboxId = notification_history.outboxId),
                detail),
            updatedAt = :now,
            deliveredAt = CASE
                WHEN (SELECT o.status FROM notification_outbox o
                      WHERE o.outboxId = notification_history.outboxId) = 'SENT'
                THEN COALESCE(deliveredAt, :now)
                ELSE deliveredAt END
        WHERE deliveryStatus IN ('CREATED','PENDING','PROCESSING')
          AND EXISTS (SELECT 1 FROM notification_outbox o
                      WHERE o.outboxId = notification_history.outboxId
                        AND o.status IN ('SENT','EXPIRED','FAILED','CANCELLED'))
        """
    )
    suspend fun reconcileTerminalStatus(now: Long): Int

    /** 保留策略（0.6.12）：保留 max(90 天, 最近 5000 条)，单次上限分页。 */
    @Query(
        """
        DELETE FROM notification_history WHERE historyId IN (
            SELECT historyId FROM notification_history
            WHERE recordedAt < :before
            ORDER BY recordedAt ASC
            LIMIT :limit
        )
        """
    )
    suspend fun cleanupOld(before: Long, limit: Int): Int

    /**
     * 删除这些 Outbox 的通知历史行（场次删除路径用）。
     *
     * `notification_history.outboxId` 是 NO ACTION 外键：只要历史行还在，
     * 对应的 Outbox 行就删不掉，进而挡住它引用的 `live_event`。
     * 这里的行描述的就是"那条被删场次的通知"，随场次一并消失是预期行为
     * （历史页的删除二次确认里已向用户说明）。
     */
    @Query("DELETE FROM notification_history WHERE outboxId IN (:outboxIds)")
    suspend fun deleteByOutboxIds(outboxIds: List<String>): Int
}
