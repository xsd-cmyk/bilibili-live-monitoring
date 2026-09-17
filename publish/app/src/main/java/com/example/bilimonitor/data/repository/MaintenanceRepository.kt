package com.example.bilimonitor.data.repository

import android.content.Context
import androidx.room.withTransaction
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

private val Context.maintenanceDataStore by preferencesDataStore(name = "maintenance")

/**
 * 维护：历史容量管理（原规范 56 / 249.4）+ 通知域保留策略（0.6.12）。
 *  - 容量模式：UNLIMITED / MAX_RECORDS=N（只清理已结束场次，进行中场次永不清）；
 *  - Outbox 终态保留 30 天；通知历史保留 90 天，单次清理上限 500 行；
 *  - 清理推进 sourceDataVersion 并写审计。
 */
@Singleton
class MaintenanceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val logDao: LogDao,
    private val clock: AppClock,
    private val problemDao: com.example.bilimonitor.data.local.dao.ProblemDao,
    @Named("appScope") private val scope: CoroutineScope
) {
    companion object {
        val KEY_CAPACITY_MODE = stringPreferencesKey("capacity_mode")
        val KEY_MAX_RECORDS = intPreferencesKey("max_records")
        const val MODE_UNLIMITED = "UNLIMITED"
        const val MODE_MAX_RECORDS = "MAX_RECORDS"
        const val CLEANUP_BATCH = 500

        /** 日志保底保留条数（原规范 214）：监控错误 10000，应用错误 5000。 */
        const val LOG_RETAIN_MONITORING = 10000
        const val LOG_RETAIN_APP = 5000

        /**
         * 容量预警阈值（原规范 56 / 249.4）：已结束场次达到上限的 95% 时提醒用户，
         * 达到 90% 视为低水位。原实现只有硬清理、没有任何预警，
         * 用户会在毫不知情的情况下丢掉最旧的历史记录。
         */
        const val CAPACITY_WARN_RATIO = 0.95
        const val CAPACITY_LOW_WATER_RATIO = 0.90
        private const val CAPACITY_PROBLEM_KEY = "STORAGE:CAPACITY:SESSIONS"
        private const val CAPACITY_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * 容量清理的每批条数。SQLite 的 `SQLITE_MAX_VARIABLE_NUMBER` 是 32766，
         * 而删除用的是 `id IN (:ids)` —— 每批留足余量，避免"一次绑定几万个变量"直接失败。
         */
        private const val CLEANUP_BATCH_SIZE = 500
    }

    val capacityMode: Flow<String> = context.maintenanceDataStore.data.map { it[KEY_CAPACITY_MODE] ?: MODE_UNLIMITED }
    val maxRecords: Flow<Int> = context.maintenanceDataStore.data.map { it[KEY_MAX_RECORDS] ?: 1000 }

    suspend fun setCapacity(mode: String, maxRecords: Int) {
        context.maintenanceDataStore.edit {
            it[KEY_CAPACITY_MODE] = mode
            if (mode == MODE_MAX_RECORDS) it[KEY_MAX_RECORDS] = maxRecords.coerceIn(10, 100000)
        }
    }

    /** 当前容量设置（供"配置文件"导出时读取）。 */
    suspend fun currentCapacity(): Pair<String, Int> = capacityModeFirst()

    data class CleanupSummary(
        val sessionsDeleted: Int,
        val outboxDeleted: Int,
        val historyDeleted: Int,
        val attemptsDeleted: Int = 0,
        val aggregatesDeleted: Int = 0,
        val monitoringErrorsDeleted: Int = 0,
        val appErrorsDeleted: Int = 0,
        val crashRecordsDeleted: Int = 0,
        val exportSnapshotsDeleted: Int = 0
    )

    /** 容量使用情况（供界面展示与预警判断）。 */
    data class CapacityUsage(
        val mode: String,
        val closedSessions: Int,
        val limit: Int?,
        val ratio: Double
    ) {
        val warning: Boolean get() = limit != null && ratio >= CAPACITY_WARN_RATIO
        val lowWater: Boolean get() = limit != null && ratio >= CAPACITY_LOW_WATER_RATIO
        val percentText: String get() = if (limit == null) "不限制" else "${(ratio * 100).toInt()}%"
    }

    /** 查询当前容量使用率（只读，不触发清理）。 */
    suspend fun capacityUsage(): CapacityUsage {
        val (mode, limit) = capacityModeFirst()
        val closed = db.liveSessionDao().countClosed()
        return if (mode != MODE_MAX_RECORDS || limit <= 0) {
            CapacityUsage(mode, closed, null, 0.0)
        } else {
            CapacityUsage(mode, closed, limit, closed.toDouble() / limit.toDouble())
        }
    }

    /**
     * 容量预警（原规范 56）：达到 95% 时发一次系统问题通知（7 天冷却），
     * 落到 `problem_state` 后由健康中心统一展示。容量回落到低水位以下自动清除。
     */
    private suspend fun checkCapacityWarning(now: Long) {
        val usage = capacityUsage()
        if (!usage.warning) {
            // 回落即解除，避免长期挂着一个已经不成立的问题
            val stale = problemDao.listActive().filter { it.problemKey.startsWith(CAPACITY_PROBLEM_KEY) }
            val cooldown = problemDao.getCooldown(CAPACITY_PROBLEM_KEY)
            stale.forEach { problemDao.upsert(it.copy(active = false, recoveredAt = now, updatedAt = now)) }
            // ★ 恢复通知（2026 复查）：容量问题原先只发"出现问题"，回落时无声消失 ——
            //   面板里那条不见了，却没有任何人告诉用户"已经恢复正常"。与监控类问题
            //   （网络/接口）的"有问题 → 已恢复"成对行为对齐。
            //   用 recoveredNotified 做"每个 episode 只发一次"的闸门 —— 这一列此前**写了却从不读**
            //   （本次修复让 ConfigDaos 里那句注释也过时了）。
            if (stale.isNotEmpty() && cooldown?.recoveredNotified != true) {
                val recoveredKey = stale.first().problemKey
                com.example.bilimonitor.data.repository.NotificationOutboxWriter.createSystemProblem(
                    db = db,
                    clock = clock,
                    problemKey = recoveredKey,
                    eventType = com.example.bilimonitor.data.local.NotificationEventType.SYSTEM_RECOVERED,
                    payloadJson = NotificationPayloads.encodeSystem(
                        SystemProblemPayload(
                            problemKey = recoveredKey,
                            component = "STORAGE",
                            errorCode = com.example.bilimonitor.data.local.AppError.UNKNOWN.name,
                            summary = "容量已回落到安全水位（当前 ${usage.percentText}），" +
                                "自动清理压力解除",
                            recovered = true
                        )
                    ),
                    now = now
                )
                // 保留冷却行并置 recoveredNotified：既记住"这一轮已经通知过恢复"，
                // 又为下一次进入预警时重置（下面进入分支会写 recoveredNotified = false）。
                problemDao.upsertCooldown(
                    com.example.bilimonitor.data.local.entity.NotificationCooldownEntity(
                        problemKey = CAPACITY_PROBLEM_KEY,
                        lastNotifiedAt = cooldown?.lastNotifiedAt,
                        cooldownUntil = null,
                        recoveredNotified = true,
                        updatedAt = now
                    )
                )
            } else {
                problemDao.clearCooldown(CAPACITY_PROBLEM_KEY)
            }
            return
        }
        val active = problemDao.listActive().firstOrNull { it.problemKey.startsWith(CAPACITY_PROBLEM_KEY) }
        if (active != null) return
        val episodeId = com.example.bilimonitor.core.Ids.newId()
        val problemKey = "$CAPACITY_PROBLEM_KEY:$episodeId"
        problemDao.upsert(
            com.example.bilimonitor.data.local.entity.ProblemStateEntity(
                problemKey = problemKey, active = true, episodeId = episodeId,
                firstObservedAt = now, recoveredAt = null, updatedAt = now
            )
        )
        val cooldown = problemDao.getCooldown(CAPACITY_PROBLEM_KEY)
        if (cooldown?.cooldownUntil != null && cooldown.cooldownUntil > now) return
        com.example.bilimonitor.data.repository.NotificationOutboxWriter.createSystemProblem(
            db = db,
            clock = clock,
            problemKey = problemKey,
            eventType = com.example.bilimonitor.data.local.NotificationEventType.SYSTEM_PROBLEM,
            payloadJson = NotificationPayloads.encodeSystem(
                SystemProblemPayload(
                    problemKey = problemKey,
                    component = "STORAGE",
                    errorCode = com.example.bilimonitor.data.local.AppError.UNKNOWN.name,
                    summary = "直播历史已达容量的 ${usage.percentText}（${usage.closedSessions}/${usage.limit}），" +
                        "继续增长将自动删除最旧的已结束场次",
                    recovered = false
                )
            ),
            now = now
        )
        problemDao.upsertCooldown(
            com.example.bilimonitor.data.local.entity.NotificationCooldownEntity(
                problemKey = CAPACITY_PROBLEM_KEY, lastNotifiedAt = now,
                cooldownUntil = now + CAPACITY_COOLDOWN_MS, recoveredNotified = false, updatedAt = now
            )
        )
    }

    /**
     * 启动/手动执行；与导出/恢复互斥（249.7）。
     *
     * 删除顺序由外键约束决定，不可调整：
     *   notification_delivery_attempt.outboxId → notification_outbox  (NO ACTION)
     *   notification_history.outboxId          → notification_outbox  (NO ACTION)
     *   notification_outbox.aggregateId        → notification_aggregate (NO ACTION)
     * 因此必须是「先删子行 → 再删 Outbox → 最后删聚合」。原实现先删 Outbox，
     * 在 PRAGMA foreign_keys=ON 下必然抛 FOREIGN KEY constraint failed，
     * 且异常被外层 runCatching 吞掉，导致通知历史与聚合清理永不执行。
     */
    suspend fun runCleanup(): Result<CleanupSummary> = runCatching {
        // ★ 用"接管版"维护锁（台账 H19-A3/B13）：导出/备份早已改成 takeOverForMaintenance，
        //   清理与恢复却还在用严格版 enterMaintenance（要求监控租约为空或已过期）。
        //   而引擎每轮 Tick 都会续上 90 秒租约、Tick 间隔又短于它 ——
        //   结果是：活跃用户的保留上限、Outbox/历史/日志清理、容量预警**从未真正执行过**
        //   （StartupRecovery 里"先跑一轮 Tick 再清理"必然失败并被 runCatching 吞掉），
        //   用户点「立即清理」还会随机得到"导出/恢复正在进行"这种与事实无关的原因。
        //   接管会递增 monitorGeneration 并清空 fencingToken，在途写入自然失效，数据安全有保证。
        check(runtimeLockRepository.takeOverForMaintenance(MaintenanceMode.DATABASE_REPAIR)) {
            "无法进入维护模式（清理已取消）"
        }
        try {
            val now = clock.nowWall()
            var sessionsDeleted = 0
            var outboxDeleted = 0
            var historyDeleted = 0
            var attemptsDeleted = 0
            var aggregatesDeleted = 0
            var monitoringErrorsDeleted = 0
            var appErrorsDeleted = 0
            var crashRecordsDeleted = 0
            var exportSnapshotsDeleted = 0

            val mode = capacityModeFirst()
            if (mode.first == MODE_MAX_RECORDS) {
                val closed = db.liveSessionDao().countClosed()
                val excess = closed - mode.second
                if (excess > 0) {
                    // ★ 分批删除（台账 H19-B4）：`DELETE ... WHERE id IN (:ids)` 会把每个 id 绑成
                    //   一个 SQL 变量，SQLite 的 SQLITE_MAX_VARIABLE_NUMBER = 32766。
                    //   用户把"最多保留"从"不限制"改小时 excess 轻松超过上限 → 抛
                    //   "too many SQL variables" → 被外层 runCatching 吞掉 →
                    //   而且阈值条件不变，**每次重试都以同样方式失败**，容量清理永久失效。
                    //   另外原实现只清事件与人工修正，漏了 reliable_monitor_interval
                    //   （该表没有外键，会留下 sessionStableId 悬空的区间行；
                    //   手动删除路径 HistoryRepository.deleteSession 是清的）。
                    var remaining = excess
                    while (remaining > 0) {
                        val batch = minOf(remaining, CLEANUP_BATCH_SIZE)
                        val ids = db.liveSessionDao().oldestClosedIds(batch)
                        if (ids.isEmpty()) break
                        db.withTransaction {
                            // 先删子表引用（修正/事件/区间），再删场次（外键为 NO ACTION）
                            for (id in ids) {
                                val session = db.liveSessionDao().findById(id) ?: continue
                                val events = db.liveEventDao().listBySession(session.stableId)
                                for (e in events) {
                                    // 事件可能被通知 Outbox / 聚合绑定引用（FK NO ACTION）：
                                    // 前者存在就保留（那条通知还要用这个事件）。
                                    if (db.notificationOutboxDao().existsForSourceEvent(e.eventId)) continue
                                    // ★ 绑定行要分两类看（2026 复查发现的缺陷）：`releaseAll` 之后的
                                    //   绑定行 `aggregateId` 已置 NULL，永远不会随聚合删除被 CASCADE 带走，
                                    //   而它们同样算"被引用" —— 于是只要事件进过一次聚合窗口，
                                    //   它的 live_event 就永远删不掉，"限制记录数"在这部分静默失效。
                                    //   这里只删 **active = 0**（已释放）的行；仍被进行中窗口持有的
                                    //   （active = 1）必须保留，否则批量通知会漏掉这条事件。
                                    db.notificationAggregateDao()
                                        .deleteReleasedBindingsForEvents(listOf(e.eventId))
                                    if (!db.notificationAggregateDao().bindingExistsForEvent(e.eventId)) {
                                        db.liveEventDao().deleteById(e.eventId)
                                    }
                                }
                                db.liveSessionCorrectionDao().deleteBySession(session.stableId)
                                // 与手动删除路径保持一致：区间表无外键，必须显式清理
                                db.reliableIntervalDao().deleteBySession(session.stableId)
                            }
                            db.liveSessionDao().deleteByIds(ids)
                        }
                        sessionsDeleted += ids.size
                        remaining -= ids.size
                    }
                    if (sessionsDeleted > 0) {
                        db.withTransaction {
                            db.streamerDao().bumpSourceDataVersion(now)
                            logDao.insertAudit(
                                AuditLogEntity(
                                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "SYSTEM",
                                    action = "CAPACITY_CLEANUP", targetType = "live_session",
                                    targetStableId = null, occurredAt = now,
                                    detailJson = "{\"deleted\":$sessionsDeleted,\"requested\":$excess}"
                                )
                            )
                        }
                    }
                }
            }

            val day = 24L * 60 * 60 * 1000

            // ---- 通知域保留策略 ----
            // 保留窗口：Outbox 30 天、通知历史 90 天（0.6.12）。
            // 两者的子行关系是 NO ACTION 外键，而 30~90 天区间天然重叠，
            // 因此**不能依赖调用顺序**：`cleanupTerminal` 的删除条件里已用
            // NOT EXISTS 排除仍有子行的行，顺序与批次大小都不再影响正确性。
            // （原实现靠"先清子行"，一旦子行清理被 LIMIT 截断就抛
            //   FOREIGN KEY constraint failed，且异常被吞掉 → 后续清理永不执行。）
            val outboxBefore = now - 30L * day
            val historyBefore = now - 90L * day

            // 1) 通知历史先按 90 天清理，尽量多释放引用
            historyDeleted += db.notificationHistoryDao().cleanupOld(historyBefore, CLEANUP_BATCH)

            // 2) 回收异常路径孤儿
            //    （原先这里还调 deleteUnstartedAttempts()，但 notification_delivery_attempt.startedAt
            //      是 NOT NULL 且插入时必写，`WHERE startedAt IS NULL` 恒不成立 —— 那是个永远为 0 的
            //      死清理，台账 H19-E7。方法已删除，别再按"有占位行要清"理解这段。）
            attemptsDeleted += db.notificationOutboxDao().deleteOrphanAttempts()

            // 3) 已结算 attempt 预清理（让下一轮能真正删掉对应 Outbox；截断不影响正确性）
            attemptsDeleted += db.notificationOutboxDao().cleanupSettledAttempts(outboxBefore, CLEANUP_BATCH)

            // 4) 删除终态且**已无子行**的 Outbox
            outboxDeleted = db.notificationOutboxDao().cleanupTerminal(outboxBefore, CLEANUP_BATCH)

            // 5) 聚合窗口终态保留 30 天（0.6.12）。
            //    聚合被 notification_outbox.aggregateId 以 NO ACTION 引用：
            //    这里同样用"无子行才删"的 DELETE，删不掉的聚合本轮跳过、下轮再试，
            //    而不是像原实现那样因为 FAILED 行永久卡死（实测复现过）。
            val aggregateBefore = now - 30L * day
            // 4.5) 先把"批量通知已终态且已过 TTL（不可能再复活）"的 FROZEN 聚合收尾成 EXPIRED：
            //      否则它会永久停在 FROZEN —— findInProgress 不取它、findTerminalBefore 也不认它，
            //      行与绑定行无限期堆积（复查发现的缺陷，两个独立代理分别指出）。
            db.notificationAggregateDao().settleStuckFrozen(now)
            val staleAggregates = db.notificationAggregateDao().findTerminalBefore(aggregateBefore, CLEANUP_BATCH)
            if (staleAggregates.isNotEmpty()) {
                val aggregateIds = staleAggregates.map { it.aggregateId }
                db.withTransaction {
                    outboxDeleted += db.notificationOutboxDao()
                        .deleteChildlessOutboxForAggregates(aggregateIds)
                    val deletable = aggregateIds.filterNot {
                        db.notificationAggregateDao().outboxExistsForAggregate(it)
                    }
                    if (deletable.isNotEmpty()) {
                        // notification_aggregate_event.aggregateId 为 CASCADE，绑定行随聚合一并删除。
                        db.notificationAggregateDao().deleteByIds(deletable)
                        aggregatesDeleted = deletable.size
                    }
                }
            }

            // 6) 日志保留（0.6.29 / 原规范 213-214）：监控错误 90 天 / 最近 10000 条，
            //    应用错误 7 天 / 最近 5000 条。原实现完全没有日志清理，这两张表无界增长。
            monitoringErrorsDeleted = logDao.cleanupMonitoringErrors(
                before = now - 90L * day, retainNewest = LOG_RETAIN_MONITORING, limit = CLEANUP_BATCH
            )
            appErrorsDeleted = logDao.cleanupAppErrors(
                before = now - 7L * day, retainNewest = LOG_RETAIN_APP, limit = CLEANUP_BATCH
            )

            // 7) 导出快照保留（原实现 ExportDao.deleteExpired 从未被调用 → 快照永久堆积）
            exportSnapshotsDeleted = db.exportDao().deleteExpired(now)

            // 8) 崩溃记录保留 90 天
            crashRecordsDeleted = db.crashRecordDao().cleanupOld(now - 90L * day, CLEANUP_BATCH)

            // 9) 其余外围表保留策略（原实现全部缺失，长期使用会无界增长）
            runCatching { db.restoreDao().cleanupFinished(now - 90L * day, CLEANUP_BATCH) }
            runCatching { db.followImportDao().cleanupFinished(now - 30L * day, CLEANUP_BATCH) }
            runCatching {
                db.statisticsCacheDao().evictCalculatedBefore(now - 30L * day)
            }
            // 通知 ID 注册表按 0.6.12 永久保留（eventKey→notificationId 稳定性依赖它），此处**不清理**。

            if (monitoringErrorsDeleted > 0 || appErrorsDeleted > 0) {
                logDao.insertAudit(
                    AuditLogEntity(
                        auditId = Ids.newId(), operationId = Ids.newId(), actor = "SYSTEM",
                        action = "LOG_CLEANUP", targetType = "log",
                        targetStableId = null, occurredAt = now,
                        detailJson = "{\"monitoring\":$monitoringErrorsDeleted,\"app\":$appErrorsDeleted}"
                    )
                )
            }

            // 8) 容量预警（原规范 56）：在清理之后重新评估，避免刚清完又误报
            checkCapacityWarning(now)

            CleanupSummary(
                sessionsDeleted = sessionsDeleted,
                outboxDeleted = outboxDeleted,
                historyDeleted = historyDeleted,
                attemptsDeleted = attemptsDeleted,
                aggregatesDeleted = aggregatesDeleted,
                monitoringErrorsDeleted = monitoringErrorsDeleted,
                appErrorsDeleted = appErrorsDeleted,
                crashRecordsDeleted = crashRecordsDeleted,
                exportSnapshotsDeleted = exportSnapshotsDeleted
            )
        } finally {
            // ★ 必须 NonCancellable：这是 suspend 的 Room 写，而 runCleanup() 会被 UI 的
            //   viewModelScope 调用（DataScreen 的 runBusy）—— 用户一离开页面协程就被取消，
            //   已取消的协程里这个写入不会执行 ⇒ maintenanceMode 永远停在 DATABASE_REPAIR。
            //   而引擎取租约的语句要求 maintenanceMode = 'OFF'（见 ConfigDaos），
            //   于是**监控会永久停摆**，只有冷启动的 resetStaleMaintenance 能自救。
            //   与 ExportRepository / BackupRepository 的同类修复同因。
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runtimeLockRepository.exitMaintenance(MaintenanceMode.DATABASE_REPAIR)
            }
        }
    }

    private suspend fun capacityModeFirst(): Pair<String, Int> {
        val prefs = context.maintenanceDataStore.data.first()
        return (prefs[KEY_CAPACITY_MODE] ?: MODE_UNLIMITED) to (prefs[KEY_MAX_RECORDS] ?: 1000)
    }
}
