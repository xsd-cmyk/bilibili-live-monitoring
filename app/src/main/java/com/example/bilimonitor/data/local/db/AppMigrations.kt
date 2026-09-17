package com.example.bilimonitor.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 唯一 Migration 契约（0.6.4.3）：全部带 WHERE 的部分唯一索引由 Migration.execSQL 建立。
 * 由于全新安装直接以当前版本 onCreate，同一组索引也在 onCreate/onOpen 中以
 * CREATE ... IF NOT EXISTS 幂等补齐（修复文档只依赖 Migration 的缺口）。
 */
object AppMigrations {

    // Room 迁移后的全量 schema 校验无法容忍 @Entity 未声明的部分唯一索引（WHERE 索引在 Room 中不可声明）。
    // 因此：每个迁移开始时先 DROP 全部部分索引让校验通过，由 onCreate/onOpen 的
    // createPartialIndexes（幂等）在每次打开时重建——语义等价于文档 0.6.4.3 的迁移创建要求。
    private val PARTIAL_INDEX_DROPS = listOf(
        "idx_live_session_one_active_open", "idx_live_session_one_abandoned_open",
        "idx_live_event_transition_effect", "idx_live_event_one_start", "idx_live_event_one_end",
        "idx_interval_one_active", "idx_gap_one_open", "idx_gap_streamer_one_open",
        "idx_outbox_aggregate_one", "idx_outbox_one_per_source_event",
        "idx_aggregate_one_in_progress", "idx_aggregate_event_one_active",
        "idx_recovery_one_running", "idx_recovery_one_requested",
        "idx_status_history_transition", "idx_attempt_unfinished"
    )

    fun dropPartialIndexes(db: SupportSQLiteDatabase) {
        PARTIAL_INDEX_DROPS.forEach { name ->
            db.execSQL("DROP INDEX IF EXISTS `$name`")
        }
    }

    val V1_2__createPendingIndexes = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropPartialIndexes(db)
            // 部分索引由 onOpen 的 createPartialIndexes 幂等重建
        }
    }

    /**
     * 重建失败的部分索引名（进程内累积，供 `StartupRecovery` 写进健康事件/错误日志）。
     *
     * 为什么需要它：这些语句在过去是**平铺执行且不捕获异常**的，
     * 只要有一条因为既有数据违反唯一性而失败，`onOpen` 就会整体抛异常 → 应用打不开数据库；
     * 而如果简单地把整段包一个 try/catch，第一条失败又会**跳过其余 15 条**：
     * 缺了 `idx_live_session_one_active_open`，`findActiveOpenSession` 就变成任意取行、
     * `openNewSession` 的约束兜底也永不触发 —— 唯一性静默失效，比抛异常更难查（第二轮审查）。
     *
     * 因此改为**逐条独立保护**：一条失败不影响其余，失败的索引名记下来让用户/诊断可见。
     */
    private val failedIndexes = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun drainFailedIndexes(): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(failedIndexes.poll() ?: break)
        return out
    }

    /** 0.6.4.3 的全部部分唯一索引（唯一清单）。 */
    fun createPartialIndexes(db: SupportSQLiteDatabase) {
        val statements = partialIndexStatements()
        for ((name, sql) in statements) {
            runCatching { db.execSQL(sql) }.onFailure { e ->
                failedIndexes.add(name)
                android.util.Log.e("AppMigrations", "部分索引 $name 创建失败：${e.message}", e)
            }
        }
    }

    private fun partialIndexStatements(): List<Pair<String, String>> = listOf(
        "idx_live_session_one_active_open" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_live_session_one_active_open " +
                "ON live_session(streamerId) WHERE endTime IS NULL AND lifecycleState = 'ACTIVE'"),
        "idx_live_session_one_abandoned_open" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_live_session_one_abandoned_open " +
                "ON live_session(streamerId) WHERE endTime IS NULL AND lifecycleState = 'ABANDONED'"),
        "idx_live_event_transition_effect" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_live_event_transition_effect " +
                "ON live_event(streamerId, transitionId) WHERE transitionId IS NOT NULL"),
        "idx_live_event_one_start" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_live_event_one_start " +
                "ON live_event(sessionStableId) WHERE sessionStableId IS NOT NULL AND eventType = 'START'"),
        "idx_live_event_one_end" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_live_event_one_end " +
                "ON live_event(sessionStableId) WHERE sessionStableId IS NOT NULL AND eventType = 'END'"),
        "idx_interval_one_active" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_interval_one_active " +
                "ON reliable_monitor_interval(streamerStableId) WHERE status = 'ACTIVE'"),
        "idx_gap_one_open" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_gap_one_open " +
                "ON monitoring_gap(scope) WHERE endTime IS NULL"),
        "idx_gap_streamer_one_open" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_gap_streamer_one_open " +
                "ON monitoring_gap_streamer(streamerStableId) WHERE affectedEndAt IS NULL"),
        "idx_outbox_aggregate_one" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_aggregate_one " +
                "ON notification_outbox(aggregateId) WHERE aggregateId IS NOT NULL"),
        "idx_outbox_one_per_source_event" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_one_per_source_event " +
                "ON notification_outbox(sourceEventId) WHERE sourceEventId IS NOT NULL"),
        "idx_aggregate_one_in_progress" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_aggregate_one_in_progress " +
                "ON notification_aggregate(bootId) WHERE status IN ('COLLECTING','READY')"),
        "idx_aggregate_event_one_active" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_aggregate_event_one_active " +
                "ON notification_aggregate_event(eventId) WHERE active = 1"),
        "idx_recovery_one_running" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_one_running " +
                "ON recovery_session(status) WHERE status = 'RUNNING'"),
        "idx_recovery_one_requested" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_recovery_one_requested " +
                "ON recovery_session(status) WHERE status = 'REQUESTED'"),
        "idx_status_history_transition" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_status_history_transition " +
                "ON status_history(streamerId, transitionId) WHERE transitionId IS NOT NULL"),
        // ⚠️ 这个索引的"唯一"语义是**空的**（复查发现）：索引行里 finishedAt 恒为 NULL，
        //    而 SQLite 的唯一索引把每个 NULL 视为互不相等（lang_createindex），
        //    所以它并不能保证"同一 Outbox 只有一个未结算 attempt"——那条保证实际由
        //    `claimOutbox` 的条件 UPDATE（CAS 到 PROCESSING 并换 deliveryAttemptId）提供。
        //    为什么不改成 `UNIQUE(outboxId) WHERE finishedAt IS NULL`：历史数据里若已存在
        //    同一 Outbox 的两条未结算行，改建索引会在 onOpen 抛异常，而那条路径一旦抛异常
        //    就是"每次启动都崩"（见 AppDatabase.onOpen 的注释）。要改必须走一次迁移并在
        //    迁移里先去重，收益（一张没有 SELECT 的表）不值这个风险。
        //    另外该表**没有任何读取点**，所以这里保持原样、只把事实写清楚。
        "idx_attempt_unfinished" to
            ("CREATE UNIQUE INDEX IF NOT EXISTS idx_attempt_unfinished " +
                "ON notification_delivery_attempt(finishedAt) WHERE finishedAt IS NULL")
    )

    /**
     * 默认检查间隔 300 → 180（用户定稿）。
     * 只重置"未自定义过"的配置（intervalSeconds 仍为旧默认 300）；
     * 按 0.6.14 配置变更协议：先写 revision，再原子推进 current version。
     */
    val V2_3__defaultInterval180 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropPartialIndexes(db)
            db.execSQL(
                """
                INSERT INTO monitoring_config_revision(
                  configVersion, monitoringEnabled, mode, intervalSeconds, batchSize, maxConcurrency, timeoutSeconds,
                  maxRetries, retryBaseSeconds, maxRetryDelaySeconds, circuitBreakerThreshold, circuitBreakerRecoverySeconds,
                  aggregationEnabled, aggregationThreshold, aggregationWindowSeconds, batchCooldownSeconds, freshnessStaleSeconds,
                  backgroundKeepAliveChoice, noGuaranteeAcknowledged, noGuaranteeAcknowledgedAt, startConfirmationCount,
                  endConfirmationCount, placeholderText, createdAt)
                SELECT c.configVersion + 1, c.monitoringEnabled, c.mode, 180, c.batchSize, c.maxConcurrency, c.timeoutSeconds,
                  c.maxRetries, c.retryBaseSeconds, c.maxRetryDelaySeconds, c.circuitBreakerThreshold, c.circuitBreakerRecoverySeconds,
                  c.aggregationEnabled, c.aggregationThreshold, c.aggregationWindowSeconds, c.batchCooldownSeconds, c.freshnessStaleSeconds,
                  c.backgroundKeepAliveChoice, c.noGuaranteeAcknowledged, c.noGuaranteeAcknowledgedAt, c.startConfirmationCount,
                  c.endConfirmationCount, c.placeholderText, r.createdAt
                FROM monitoring_config c JOIN monitoring_config_revision r ON r.configVersion = c.configVersion
                WHERE c.singletonId = 1 AND c.intervalSeconds = 300
                """.trimIndent()
            )
            db.execSQL(
                """
                UPDATE monitoring_config
                SET configVersion = configVersion + 1, intervalSeconds = 180
                WHERE singletonId = 1 AND intervalSeconds = 300
                """.trimIndent()
            )
        }
    }

    /**
     * v4：新增场次标题变更表（用户定稿功能），并回填历史场次的起止标题。
     */
    val V3_4__sessionTitles = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropPartialIndexes(db)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `live_session_title` (
                  `sessionStableId` TEXT NOT NULL,
                  `title` TEXT NOT NULL,
                  `observedAt` INTEGER NOT NULL,
                  PRIMARY KEY(`sessionStableId`, `title`),
                  FOREIGN KEY(`sessionStableId`) REFERENCES `live_session`(`stableId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_session_title_sessionStableId` ON `live_session_title` (`sessionStableId`)")
            db.execSQL(
                """
                INSERT OR IGNORE INTO live_session_title(sessionStableId, title, observedAt)
                SELECT stableId, titleAtStart, COALESCE(startTime, createdAt)
                FROM live_session WHERE titleAtStart IS NOT NULL AND titleAtStart <> ''
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO live_session_title(sessionStableId, title, observedAt)
                SELECT stableId, titleAtEnd, COALESCE(endTime, createdAt)
                FROM live_session WHERE titleAtEnd IS NOT NULL AND titleAtEnd <> ''
                """.trimIndent()
            )
        }
    }

    /**
     * v5：场次记录创建/结束所在时区（用户定稿）。旧数据为 NULL（表示未知，展示时回退设备时区）。
     */
    val V4_5__sessionTimezones = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropPartialIndexes(db)
            db.execSQL("ALTER TABLE live_session ADD COLUMN startTimeZone TEXT")
            db.execSQL("ALTER TABLE live_session ADD COLUMN endTimeZone TEXT")
        }
    }

    /**
     * v6：数据库版本正常化。
     *
     * 背景：`versionCode` 长期固定为 1，而 `AppDatabase.version` 曾在 6 与 5 之间往返，
     * 导致装过 v6 中间版的设备升级到 v5 代码时，Room 走进降级路径 ——
     * `A migration from 6 to 5 was required but not found` —— **开库即崩溃且无法自愈**
     * （已在模拟器上实测复现）。
     *
     * 这里把版本确定在 6（只增不减），并显式声明 5→6 迁移：
     * 本轮只新增 DAO 方法、未改动任何表结构，因此本次迁移**不需要改表**，
     * 只需像其它迁移一样先摘掉部分索引，交由 onOpen 的幂等重建补齐
     * （Room 迁移后会做全量 schema 校验，而带 WHERE 的部分索引无法在 @Entity 中声明）。
     */
    val V5_6__versionNormalization = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropPartialIndexes(db)
            // 无表结构变更；部分索引由 AppMigrations.createPartialIndexes 在 onOpen 幂等重建。
        }
    }

    /**
     * v7：新增场次**直播分区**变更表（用户定稿功能），并回填历史场次的起止分区。
     *
     * 与 v4 的标题表同构：一场直播内分区可能变多次，要求全部记录下来。
     * 分区数据来自公开的直播状态接口，不依赖登录。
     */
    val V6_7__sessionAreas = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropPartialIndexes(db)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `live_session_area` (
                  `sessionStableId` TEXT NOT NULL,
                  `areaLabel` TEXT NOT NULL,
                  `parentAreaName` TEXT,
                  `areaName` TEXT,
                  `observedAt` INTEGER NOT NULL,
                  PRIMARY KEY(`sessionStableId`, `areaLabel`),
                  FOREIGN KEY(`sessionStableId`) REFERENCES `live_session`(`stableId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_live_session_area_sessionStableId` ON `live_session_area` (`sessionStableId`)")
            // 回填：老数据的起止分区（areaAtStart / areaAtEnd）各补一行
            db.execSQL(
                """
                INSERT OR IGNORE INTO live_session_area(sessionStableId, areaLabel, parentAreaName, areaName, observedAt)
                SELECT stableId, areaAtStart, NULL, areaAtStart, COALESCE(startTime, createdAt)
                FROM live_session WHERE areaAtStart IS NOT NULL AND areaAtStart <> ''
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO live_session_area(sessionStableId, areaLabel, parentAreaName, areaName, observedAt)
                SELECT stableId, areaAtEnd, NULL, areaAtEnd, COALESCE(endTime, createdAt)
                FROM live_session WHERE areaAtEnd IS NOT NULL AND areaAtEnd <> ''
                """.trimIndent()
            )
        }
    }

    /**
     * v8：主播策略新增「标题变化通知 / 分区变化通知」两个开关（用户定稿功能），**默认关闭**。
     *
     * 用 `DEFAULT 0` 与实体上的 `@ColumnInfo(defaultValue = "0")` 对齐 ——
     * 两边不一致会让 Room 在迁移后做 schema 校验时直接报
     * "Migration didn't properly handle..."（旧行必须拿到确定的默认值）。
     */
    val V7_8__notificationChangeSwitches = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            dropPartialIndexes(db)
            db.execSQL("ALTER TABLE streamer_monitor_policy ADD COLUMN notifyTitleChange INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE streamer_monitor_policy ADD COLUMN notifyAreaChange INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v9：监控配置新增两项（用户诉求）。
     *
     *  1. `retryFailureRatioPercent` —— 触发重试/熔断的**最小失败占比**（默认 50）：
     *     原实现里一个持续失败/被封禁的房间就能把全局熔断顶开，之后所有主播都不再被检测。
     *  2. `banRecheckIntervalSeconds` —— 「封禁中」主播的复查间隔（默认 1800 = 30 分钟）。
     *
     * ★ `DEFAULT` 必须与实体上的 `@ColumnInfo(defaultValue = ...)` 逐字一致，
     *   否则 Room 迁移后会做 schema 校验并直接报
     *   "Migration didn't properly handle ..."（旧行必须拿到确定的默认值）。
     *
     * ★ `monitoring_config_revision` 也要加：修订快照是"当前值的历史留痕"，
     *   只给当前表加列会让新旧两表的字段集分叉，恢复/审计时口径不一致。
     *
     * ⚠️ 待办（无法在无 Gradle 环境下完成）：`exportSchema = true` 的 schema JSON
     *   （`app/schemas/.../9.json`）只能由一次真实的 Gradle 构建导出。
     *   本迁移与实体已按 v9 写好，需要父代理跑一次构建生成 9.json 并纳入版本库，
     *   否则后续的 instrumented 迁移测试（Room 的 schema 校验）会因缺文件而失败。
     *
     * ★ 2026 修复（阻断级）：本迁移原先**漏了**首行的 `dropPartialIndexes(db)` ——
     *   其余 7 条迁移每条都有，只有它没有。后果不是"少删几个索引"这么轻：
     *   Room 在迁移后会做**全量 schema 校验**，而 WHERE 部分唯一索引无法在 `@Entity` 中声明，
     *   于是库里残留的 16 条部分唯一索引会让 `TableInfo.equals` 的索引集合比对不相等 ⇒
     *   `IllegalStateException("Migration didn't properly handle: ...")` ⇒ **开库即崩、无自愈路径**。
     *   只有 v8 → v9 这条升级路径会命中（v7 → v9、全新安装都不受影响），
     *   所以这条缺陷在"一路跟着升级上来"的机器上才炸 —— 也就是真实用户那台机器。
     */
    val V8_9__failureRatioAndBanRecheck = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 与其余 7 条迁移逐字一致：先摘掉全部部分唯一索引，让迁移后的 schema 校验通过；
            // 索引由 onOpen 的 createPartialIndexes（幂等、逐条独立保护）重建。
            dropPartialIndexes(db)
            db.execSQL(
                "ALTER TABLE monitoring_config ADD COLUMN retryFailureRatioPercent INTEGER NOT NULL DEFAULT 50"
            )
            db.execSQL(
                "ALTER TABLE monitoring_config ADD COLUMN banRecheckIntervalSeconds INTEGER NOT NULL DEFAULT 1800"
            )
            db.execSQL(
                "ALTER TABLE monitoring_config_revision ADD COLUMN retryFailureRatioPercent INTEGER NOT NULL DEFAULT 50"
            )
            db.execSQL(
                "ALTER TABLE monitoring_config_revision ADD COLUMN banRecheckIntervalSeconds INTEGER NOT NULL DEFAULT 1800"
            )
        }
    }

    /**
     * v10：监控配置新增「熔断开关」`circuitBreakerEnabled`（用户诉求：熔断要能一键关掉）。
     *
     * 语义：关掉之后，失败的批次**一直重试、继续请求**，不再因为连续失败而进入熔断暂停；
     * 打开（默认）时行为与引入本开关之前完全一致 —— 这是"新开关默认值必须保持既有行为"的硬要求，
     * 因此 DDL 用 `DEFAULT 1`（true），实体上写 `@ColumnInfo(defaultValue = "1")`。
     *
     * ★ `DEFAULT` 必须与实体上的 `@ColumnInfo(defaultValue = ...)` 逐字一致，
     *   否则 Room 迁移后会做**全量 schema 校验**并直接报
     *   "Migration didn't properly handle ..."：老行必须拿到确定的默认值。
     *
     * ★ `monitoring_config_revision` 也要加：修订快照是"当前值的历史留痕"，
     *   只给当前表加列会让新旧两表的字段集分叉，恢复/审计时口径不一致。
     *
     * ★ 首行的 `dropPartialIndexes(db)` **不能省** —— 这是刚踩过的坑（见 V8_9 的长注释）：
     *   迁移后 Room 做全量 schema 校验，而 WHERE 部分唯一索引无法在 @Entity 中声明，
     *   库里残留的 16 条部分唯一索引会让索引集合比对不相等 ⇒ 开库即崩。
     *
     * ⚠️ 待办（无法在无 Gradle 环境下完成）：`exportSchema = true` 的 schema JSON
     *   （`app/schemas/.../10.json`）只能由一次真实的 Gradle 构建导出。
     *   本迁移与实体已按 v10 写好，需要父代理跑一次构建生成 10.json 并纳入版本库。
     */
    val V9_10__circuitBreakerSwitch = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 与其余 8 条迁移逐字一致：先摘掉全部部分唯一索引，让迁移后的 schema 校验通过；
            // 索引由 onOpen 的 createPartialIndexes（幂等、逐条独立保护）重建。
            dropPartialIndexes(db)
            db.execSQL(
                "ALTER TABLE monitoring_config ADD COLUMN circuitBreakerEnabled INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE monitoring_config_revision ADD COLUMN circuitBreakerEnabled INTEGER NOT NULL DEFAULT 1"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(
        V1_2__createPendingIndexes,
        V2_3__defaultInterval180,
        V3_4__sessionTitles,
        V4_5__sessionTimezones,
        V5_6__versionNormalization,
        V6_7__sessionAreas,
        V7_8__notificationChangeSwitches,
        V8_9__failureRatioAndBanRecheck,
        V9_10__circuitBreakerSwitch
    )
}
