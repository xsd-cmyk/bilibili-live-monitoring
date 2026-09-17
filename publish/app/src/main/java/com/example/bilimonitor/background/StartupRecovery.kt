package com.example.bilimonitor.background

import com.example.bilimonitor.data.local.dao.RecoverySessionDao
import com.example.bilimonitor.data.local.dao.ExportDao
import com.example.bilimonitor.data.local.ExportSnapshotStatus
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.RecoverySessionStatus
import com.example.bilimonitor.data.local.RecoveryFinishReason
import com.example.bilimonitor.data.local.entity.RecoverySessionEntity
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.repository.IMPORT_INTERRUPTED_REASON
import com.example.bilimonitor.data.repository.NotificationRepository
import com.example.bilimonitor.data.repository.RuntimeLockRepository
import com.example.bilimonitor.notify.NotificationPoster
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动恢复（0.6.34 通知投递恢复 / 0.6.33 RecoverySession 结算 / 0.6.10 聚合崩溃恢复）。
 * 应用每次冷启动执行一次。
 */
@Singleton
class StartupRecovery @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val recoverySessionDao: RecoverySessionDao,
    private val exportDao: ExportDao,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val controller: MonitoringController,
    private val poster: NotificationPoster,
    private val maintenanceRepository: com.example.bilimonitor.data.repository.MaintenanceRepository,
    private val db: com.example.bilimonitor.data.local.db.AppDatabase,
    private val clock: AppClock,
    private val crashRecorder: com.example.bilimonitor.core.CrashRecorder,
    private val recoverySessionRepository: com.example.bilimonitor.data.repository.RecoverySessionRepository,
    private val logDao: com.example.bilimonitor.data.local.dao.LogDao,
    // 冷启动补投用（见下面的"补投已入队通知"一步）：同包，不需要 import
    private val notificationDispatcher: NotificationDispatcher
) {
    /** 上次冷启动是否从崩溃中恢复；供界面/诊断展示。 */
    @Volatile
    var lastRecoveredCrashCount: Int = 0
        private set

    suspend fun run() {
        val now = clock.nowWall()
        // ★ 每一步都必须留痕（台账 H19-2.6）：这里原先 13 处全是裸 runCatching，
        //   失败连一条日志都没有 —— 与仓库硬规则"任何降级都不允许静默发生"直接冲突，
        //   结果是"崩溃恢复没跑完 / 清理失败 / 投递结算失败"在诊断包里完全看不到。
        step("投递过期结算") {
            notificationRepository.settleExpiredDeliveries(runtimeLockRepository.runtimeInstanceId)
        }
        step("聚合崩溃恢复") { notificationRepository.recoverCrashedAggregates() }
        step("恢复会话结算") { recoverySessionDao.settleStaleRunning(now) }
        // 上一轮未被消费的 REQUESTED 一并作废（原规范 177）；随后开启本轮恢复检查。
        step("作废残留恢复请求") { recoverySessionRepository.cancelStaleRequested() }
        if (poster.notificationsEnabled()) {
            step("恢复失败通知") { notificationRepository.reviveFailed(now) }
            // ★ 冷启动要补投一次（复查发现的缺陷）：投递只由 tick 驱动（MonitoringEngine），
            //   而手动模式/监控关闭时根本没有 tick —— 那些"已入队但进程被杀"的通知会一直躺到
            //   24 小时 TTL 到期被判 EXPIRED，用户永远收不到。这里在"通知可用"的前提下投一轮
            //   （正因为放在 notificationsEnabled() 分支里，才不会在无权限时白烧 5 次尝试）。
            step("补投已入队通知") { notificationDispatcher.dispatchDue() }
        }
        step("作废未完成导出") {
            val unfinished = exportDao.findUnfinished()
            unfinished.forEach { exportDao.casStatus(it.snapshotId, it.status, ExportSnapshotStatus.CANCELLED) }
        }
        // 残留维护模式必须清掉：维护模式是"内存中操作的持久化标记"，
        // 进程若在导出/备份/恢复/清理中途被杀，标记会永远留在库里 ——
        // 后果不只是这些操作一直报"正在进行"：引擎取租约要求 maintenanceMode = 'OFF'，
        // 于是**监控会永久停摆**，用户只能清除应用数据。上面刚把未完成快照置为 CANCELLED，
        // 对应的维护权自然也该一并释放。
        step("清理残留维护模式") {
            val cleared = db.runtimeLockDao().resetStaleMaintenance(now)
            if (cleared > 0) {
                logDao.insertHealthEvent(
                    com.example.bilimonitor.data.local.entity.HealthEventEntity(
                        healthEventId = com.example.bilimonitor.core.Ids.newId(),
                        component = "MAINTENANCE",
                        fromStatus = MonitorHealthStatus.BLOCKED,
                        toStatus = MonitorHealthStatus.RECOVERING,
                        occurredAt = now,
                        episodeId = null
                    )
                )
            }
        }
        // 崩溃恢复：APPLYING 的恢复运行视为不完整恢复（0.6.33.1 第 4 条）
        step("结算中断的恢复运行") {
            db.restoreDao().findApplying().forEach {
                db.restoreDao().casTransition(
                    it.restoreRunId, com.example.bilimonitor.data.local.RestoreRunStatus.APPLYING,
                    com.example.bilimonitor.data.local.RestoreRunStatus.FAILED,
                    now, com.example.bilimonitor.data.local.RestoreFinishReason.FAILED, it.warningCount
                )
            }
        }
        // ★ 在途的导入任务同样必须结算（缺陷 3）：`FollowImportDao.cleanupFinished` 只回收
        //   "终态 + finishedAt 非空"的行，所以进程在 REQUESTED/FETCHING/PREVIEW/APPLYING
        //   中途被杀时，任务行连同它的 follow_import_staging 暂存行会**永远**留在库里
        //   （外键 CASCADE 只在父行被删时生效，而父行压根删不掉）；UI 下次冷启动还会把
        //   它当成"最近一次导入"读到中间态。口径与上面恢复运行一致：被中断 = 失败。
        step("结算中断的导入任务") {
            // cutoff 就用 run() 开头那个 now：本次启动之后新发起的导入（startedAt 更晚）
            // 属于当前会话，绝不能被当成"上次被杀留下的僵尸"结算掉（见 DAO 的说明）。
            val settled = db.followImportDao().settleInterrupted(now, IMPORT_INTERRUPTED_REASON, now)
            if (settled > 0) {
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = now,
                        errorCode = com.example.bilimonitor.data.local.AppError.UNKNOWN,
                        detail = ("冷启动结算了 ${settled} 个被中断的导入任务（进程结束时停在中间状态，" +
                            "这些导入没有跑完，已置为 FAILED 供用户重新导入）").take(500)
                    )
                )
            }
        }

        // 数据库健康自检（台账 H19-2.4 + 第二轮审查）：本项目把全部业务数据放在一个 SQLite 文件里，
        // 而此前**没有任何**完整性检查或损坏处理 —— 掉电写坏 WAL / 磁盘写满时，
        // Room 打不开库，应用启动即崩且无法自愈（用户只能清数据，等于丢光历史）。
        // quick_check 比 integrity_check 快得多，代价可以接受；有问题就如实写进健康事件，
        // 让用户在诊断页看到"库可能已损坏"，而不是面对一个打不开的应用。
        step("数据库完整性自检") {
            val problems = databaseHealthCheck()
            if (problems != null) {
                logDao.insertHealthEvent(
                    com.example.bilimonitor.data.local.entity.HealthEventEntity(
                        healthEventId = Ids.newId(),
                        component = "DATABASE",
                        fromStatus = MonitorHealthStatus.HEALTHY,
                        toStatus = MonitorHealthStatus.DEGRADED,
                        occurredAt = now,
                        episodeId = null
                    )
                )
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = now,
                        errorCode = com.example.bilimonitor.data.local.AppError.DATABASE_ERROR,
                        detail = "数据库自检异常：$problems".take(500)
                    )
                )
            }
        }

        // ★ 把开库阶段"压住没说"的问题捞出来（第二轮审查）：
        //   `AppMigrations.createPartialIndexes` 现在逐条独立保护（一条失败不再连带跳过其余 15 条），
        //   但失败只写进了 logcat —— 而诊断包不读 logcat。缺了部分唯一索引意味着
        //   "同一主播两条 ACTIVE 场次"这类约束静默失效，必须让用户/诊断看得见。
        step("报告索引重建失败") {
            val failed = com.example.bilimonitor.data.local.db.AppMigrations.drainFailedIndexes()
            if (failed.isNotEmpty()) {
                logDao.insertHealthEvent(
                    com.example.bilimonitor.data.local.entity.HealthEventEntity(
                        healthEventId = Ids.newId(),
                        component = "DATABASE_INDEX",
                        fromStatus = MonitorHealthStatus.HEALTHY,
                        toStatus = MonitorHealthStatus.DEGRADED,
                        occurredAt = now,
                        episodeId = null
                    )
                )
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = now,
                        errorCode = com.example.bilimonitor.data.local.AppError.DATABASE_ERROR,
                        detail = "以下部分唯一索引未能重建（唯一性约束可能失效）：${failed.joinToString()}"
                            .take(500)
                    )
                )
            }
        }

        // ★ 枚举列出现未知值时的可见化（第二轮审查）：
        //   `EnumSafe` 在无法写库的上下文里（Room 读行途中）只能记 logcat，
        //   这里把它攒下的记录落到 application_error_log —— 否则用户按提示"导出诊断包排查"
        //   却什么也查不到。未知枚举往往意味着外部改库、或恢复了一份来自更新版本的备份。
        step("报告未知枚举值") {
            val unknown = com.example.bilimonitor.data.local.convert.EnumSafe.drainUnknownValues()
            if (unknown.isNotEmpty()) {
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = now,
                        errorCode = com.example.bilimonitor.data.local.AppError.DATABASE_ERROR,
                        detail = "数据库中出现未知枚举值（已回退到保守缺省）：${unknown.joinToString()}"
                            .take(500)
                    )
                )
            }
        }

        // 崩溃自恢复（原规范 81 / 225）：标记上次崩溃已恢复，并写一条 HealthEvent 让用户可见。
        step("崩溃自恢复") {
            val recovered = crashRecorder.markPreviousCrashesRecovered()
            lastRecoveredCrashCount = recovered
            if (recovered > 0) {
                logDao.insertHealthEvent(
                    com.example.bilimonitor.data.local.entity.HealthEventEntity(
                        healthEventId = com.example.bilimonitor.core.Ids.newId(),
                        component = "PROCESS",
                        fromStatus = MonitorHealthStatus.DEGRADED,
                        toStatus = MonitorHealthStatus.RECOVERING,
                        occurredAt = now,
                        episodeId = null
                    )
                )
            }
        }

        step("同步监控配置") { controller.syncWithConfig() }
        // 恢复检查模式：真正补检一次，并留下可审计的 RecoverySession（原规范 177）
        //
        // ★ 但"每次冷启动都补检"是错的（台账 H19-A7）：WorkManager 唤醒进程时，
        //   Application.onCreate 会先补检一整轮，紧接着 Worker 自己又跑一轮 ——
        //   每次省电唤醒的 API 请求与写放大一倍；用户正常打开 App 也会连打两轮。
        //   补检的语义是"崩溃后补一次"，因此只在"确有未恢复的崩溃"或
        //   "距上次检查超过 2 个间隔（说明中间断过）"时才执行。
        if (shouldRunRecoveryCheck(now)) {
            step("恢复补检") {
                val generation = runtimeLockRepository.monitorGeneration()
                recoverySessionRepository.runRecoveryCheck(generation)
            }
        }
        // 历史容量/保留策略清理（低频）
        step("容量与保留清理") { maintenanceRepository.runCleanup() }
        step("崩溃记录清理") { crashRecorder.cleanup() }
    }

    /** 单步执行 + 失败留痕：失败不阻断后续步骤（启动流程要尽可能走完）。 */
    private suspend fun step(what: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { e ->
            android.util.Log.e("StartupRecovery", "$what 失败：${e.message}", e)
            runCatching {
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = com.example.bilimonitor.data.local.AppError.UNKNOWN,
                        detail = "启动恢复「$what」失败：${e.message}".take(500)
                    )
                )
            }
        }
    }

    /** @return null = 正常；非 null = 问题描述。 */
    private fun databaseHealthCheck(): String? = runCatching {
        db.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use { c ->
            if (c.moveToFirst()) {
                val result = c.getString(0)
                if (result != "ok") result else null
            } else null
        }
    }.getOrElse { "自检执行失败：${it.message}" }

    /**
     * 是否需要"崩溃后补检"。
     *
     * 判据：有未恢复的崩溃记录，或者距上次成功检查超过 2 个检查间隔。
     * 后者覆盖"进程被杀、没有留下崩溃记录"的情况（例如被系统低内存回收）。
     */
    private suspend fun shouldRunRecoveryCheck(now: Long): Boolean {
        if (lastRecoveredCrashCount > 0) return true
        return runCatching {
            val config = db.configDao().getConfig() ?: return@runCatching false
            if (!config.monitoringEnabled) return@runCatching false
            val interval = config.intervalSeconds.coerceAtLeast(60)
            val lastSeen = db.streamerDao().listMonitorable()
                .mapNotNull { it.lastCheckedAt }
                .maxOrNull()
                ?: return@runCatching true // 从未检查过（刚添加主播）→ 值得补检一次
            (now - lastSeen) > 2L * interval * 1000L
        }.getOrDefault(false)
    }
}
