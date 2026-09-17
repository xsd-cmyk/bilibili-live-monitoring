package com.example.bilimonitor.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.bilimonitor.data.local.convert.DshTypeConverters
import com.example.bilimonitor.data.local.dao.ConfigDao
import com.example.bilimonitor.data.local.dao.CrashRecordDao
import com.example.bilimonitor.data.local.dao.ExportDao
import com.example.bilimonitor.data.local.dao.LiveEventDao
import com.example.bilimonitor.data.local.dao.LiveSessionTitleDao
import com.example.bilimonitor.data.local.dao.LiveSessionDao
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.MonitoringGapDao
import com.example.bilimonitor.data.local.dao.NotificationAggregateDao
import com.example.bilimonitor.data.local.dao.NotificationHistoryDao
import com.example.bilimonitor.data.local.dao.NotificationIdRegistryDao
import com.example.bilimonitor.data.local.dao.AuthSessionDao
import com.example.bilimonitor.data.local.dao.FollowImportDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import com.example.bilimonitor.data.local.dao.RestoreDao
import com.example.bilimonitor.data.local.dao.PolicyDao
import com.example.bilimonitor.data.local.dao.ProblemDao
import com.example.bilimonitor.data.local.dao.RecoverySessionDao
import com.example.bilimonitor.data.local.dao.ReliableIntervalDao
import com.example.bilimonitor.data.local.dao.RuntimeLockDao
import com.example.bilimonitor.data.local.dao.StatisticsCacheDao
import com.example.bilimonitor.data.local.dao.StatisticsSnapshotDao
import com.example.bilimonitor.data.local.dao.StatusHistoryDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.dao.TaxonomyDao
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.AuthSessionEntity
import com.example.bilimonitor.data.local.entity.CrashRecordEntity
import com.example.bilimonitor.data.local.entity.ExportSnapshotEntity
import com.example.bilimonitor.data.local.entity.ExportSnapshotLiveSessionEntity
import com.example.bilimonitor.data.local.entity.FollowImportStagingEntity
import com.example.bilimonitor.data.local.entity.FollowImportTaskEntity
import com.example.bilimonitor.data.local.entity.GroupEntity
import com.example.bilimonitor.data.local.entity.HealthEventEntity
import com.example.bilimonitor.data.local.entity.LiveEventEntity
import com.example.bilimonitor.data.local.entity.LiveSessionCorrectionEntity
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import com.example.bilimonitor.data.local.entity.MonitoringConfigEntity
import com.example.bilimonitor.data.local.entity.MonitoringConfigRevisionEntity
import com.example.bilimonitor.data.local.entity.MonitoringErrorLogEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapStreamerEntity
import com.example.bilimonitor.data.local.entity.NotificationAggregateEntity
import com.example.bilimonitor.data.local.entity.NotificationAggregateEventEntity
import com.example.bilimonitor.data.local.entity.NotificationCooldownEntity
import com.example.bilimonitor.data.local.entity.NotificationDeliveryAttemptEntity
import com.example.bilimonitor.data.local.entity.NotificationHistoryEntity
import com.example.bilimonitor.data.local.entity.NotificationIdRegistryEntity
import com.example.bilimonitor.data.local.entity.NotificationOutboxEntity
import com.example.bilimonitor.data.local.entity.ProblemStateEntity
import com.example.bilimonitor.data.local.entity.RecoverySessionEntity
import com.example.bilimonitor.data.local.entity.ReliableMonitorIntervalEntity
import com.example.bilimonitor.data.local.entity.RestoreConflictEntity
import com.example.bilimonitor.data.local.entity.RestoreRunEntity
import com.example.bilimonitor.data.local.entity.SourceDataRevisionEntity
import com.example.bilimonitor.data.local.entity.StatisticsCacheEntity
import com.example.bilimonitor.data.local.entity.StatisticsRevisionEntity
import com.example.bilimonitor.data.local.entity.StatisticsSnapshotEntity
import com.example.bilimonitor.data.local.entity.StatusHistoryEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.local.entity.StreamerGroupCrossRefEntity
import com.example.bilimonitor.data.local.entity.StreamerMonitorPolicyEntity
import com.example.bilimonitor.data.local.entity.StreamerPendingTransitionEntity
import com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity
import com.example.bilimonitor.data.local.entity.SystemRuntimeLockEntity
import com.example.bilimonitor.data.local.entity.TagEntity

@Database(
    entities = [
        // 核心业务事实
        StreamerEntity::class, TagEntity::class, GroupEntity::class,
        StreamerTagCrossRefEntity::class, StreamerGroupCrossRefEntity::class,
        LiveSessionEntity::class, LiveEventEntity::class, StatusHistoryEntity::class,
        com.example.bilimonitor.data.local.entity.LiveSessionTitleEntity::class,
        com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity::class,
        StreamerPendingTransitionEntity::class, ReliableMonitorIntervalEntity::class,
        MonitoringGapEntity::class, MonitoringGapStreamerEntity::class,
        LiveSessionCorrectionEntity::class,
        // 通知
        NotificationAggregateEntity::class, NotificationAggregateEventEntity::class,
        NotificationOutboxEntity::class, NotificationDeliveryAttemptEntity::class,
        NotificationIdRegistryEntity::class, NotificationHistoryEntity::class,
        NotificationCooldownEntity::class, ProblemStateEntity::class,
        // 配置与运行态
        MonitoringConfigEntity::class, MonitoringConfigRevisionEntity::class,
        SourceDataRevisionEntity::class, SystemRuntimeLockEntity::class,
        StreamerMonitorPolicyEntity::class,
        // 统计
        StatisticsCacheEntity::class, StatisticsSnapshotEntity::class,
        StatisticsRevisionEntity::class,
        // 备份恢复导出
        ExportSnapshotEntity::class, ExportSnapshotLiveSessionEntity::class,
        RestoreRunEntity::class, RestoreConflictEntity::class,
        // 账号与外围
        AuthSessionEntity::class, FollowImportTaskEntity::class, FollowImportStagingEntity::class,
        AuditLogEntity::class, MonitoringErrorLogEntity::class, ApplicationErrorLogEntity::class,
        CrashRecordEntity::class, HealthEventEntity::class, RecoverySessionEntity::class
    ],
    version = AppDatabase.DB_VERSION,
    exportSchema = true,
    autoMigrations = []
)
@TypeConverters(DshTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun streamerDao(): StreamerDao
    abstract fun liveSessionDao(): LiveSessionDao
    abstract fun liveEventDao(): LiveEventDao
    abstract fun statusHistoryDao(): StatusHistoryDao
    abstract fun reliableIntervalDao(): ReliableIntervalDao
    abstract fun monitoringGapDao(): MonitoringGapDao
    abstract fun liveSessionCorrectionDao(): com.example.bilimonitor.data.local.dao.LiveSessionCorrectionDao
    abstract fun liveSessionTitleDao(): LiveSessionTitleDao
    abstract fun liveSessionAreaDao(): com.example.bilimonitor.data.local.dao.LiveSessionAreaDao
    abstract fun notificationOutboxDao(): NotificationOutboxDao
    abstract fun notificationIdRegistryDao(): NotificationIdRegistryDao
    abstract fun notificationAggregateDao(): NotificationAggregateDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun configDao(): ConfigDao
    abstract fun runtimeLockDao(): RuntimeLockDao
    abstract fun policyDao(): PolicyDao
    abstract fun recoverySessionDao(): RecoverySessionDao
    abstract fun crashRecordDao(): CrashRecordDao
    abstract fun logDao(): LogDao
    abstract fun problemDao(): ProblemDao
    abstract fun statisticsCacheDao(): StatisticsCacheDao
    abstract fun statisticsSnapshotDao(): StatisticsSnapshotDao
    abstract fun taxonomyDao(): TaxonomyDao
    abstract fun exportDao(): ExportDao
    abstract fun followImportDao(): FollowImportDao
    abstract fun restoreDao(): RestoreDao
    abstract fun authSessionDao(): AuthSessionDao

    companion object {
        const val DB_NAME = "bilibili_monitor.db"
        const val BUSY_TIMEOUT_MS = 5_000

        /**
         * Room schema 版本的**唯一来源**：`@Database(version = DB_VERSION)` 在此引用，
         * 其余需要版本号的地方（备份清单、诊断包、版本守卫）也一律引用它，
         * 避免出现"多处各写一个数字、升版时漏改某处"的问题。
         *
         * v9 = 新增两个监控配置项（触发重试/熔断的最小失败占比、封禁复查间隔），
         * 见 [AppMigrations.V8_9__failureRatioAndBanRecheck]。
         *
         * v10 = 新增「熔断总开关」`circuitBreakerEnabled`（默认 true，保持既有行为），
         * 见 [AppMigrations.V9_10__circuitBreakerSwitch]。
         */
        const val DB_VERSION = 10
    }
}

/**
 * 开库回调里的失败必须留痕，但**不能**把异常抛出去（那会让应用打不开数据库）。
 *
 * 这里刻意只写 logcat：开库阶段还没有可用的数据库连接（我们正卡在这一步），
 * 写库会递归回到同一个失败点。真正的用户可见提示由启动后的 `StartupRecovery`
 * 通过 `PRAGMA quick_check` 结果补写（见 StartupRecovery 的库健康自检）。
 */
internal fun reportDatabaseProblem(what: String, e: Throwable) {
    android.util.Log.e("AppDatabase", "$what：${e.message}", e)
}

object AppDatabaseFactory {
    fun create(context: Context): AppDatabase = build(context, AppDatabase.DB_NAME)

    /**
     * 独立数据库实例，仅供 instrumented 测试使用（不触碰应用正式数据）。
     * 与正式库使用完全相同的迁移、单行种子与部分索引装配，保证测的是真实结构。
     */
    fun createForTest(context: Context, name: String): AppDatabase = build(context, name)

    private fun build(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            // WAL 是"读事务与写事务并发"可用的前提（0.6.4.1）。
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .openHelperFactory(BusyTimeoutOpenHelperFactory(AppDatabase.BUSY_TIMEOUT_MS))
            .addMigrations(*AppMigrations.ALL)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // 0.6.4.5：单例行缺失会让所有 CAS 静默失败（影响 0 行且不报错）。
                    db.execSQL("PRAGMA foreign_keys = ON")
                    runCatching { DbSeed.ensureSingletonRows(db, System.currentTimeMillis()) }
                        .onFailure { reportDatabaseProblem("初始化单例行失败", it) }
                    runCatching { AppMigrations.createPartialIndexes(db) }
                        .onFailure { reportDatabaseProblem("创建部分唯一索引失败", it) }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    // 每次打开都强制开启外键；并自检补种单例行。
                    db.execSQL("PRAGMA foreign_keys = ON")
                    // ★ 这里的每一步都不能把异常抛出去（台账 H19-B2 / 2.4）：
                    //   onOpen 抛异常 = 数据库打不开 = 应用**每次启动都崩**，而用户唯一的
                    //   自救手段是清除应用数据（等于丢掉全部历史）。
                    //   最容易踩的是 createPartialIndexes：`CREATE UNIQUE INDEX IF NOT EXISTS`
                    //   在索引不存在时其实是**对既有数据的唯一性校验**，库里一旦有重复的
                    //   ACTIVE 场次/未关闭盲区，它就会抛 SQLiteConstraintException。
                    runCatching { DbSeed.ensureSingletonRows(db, System.currentTimeMillis()) }
                        .onFailure { reportDatabaseProblem("补种单例行失败", it) }
                    runCatching { AppMigrations.createPartialIndexes(db) }
                        .onFailure { reportDatabaseProblem("重建部分唯一索引失败（数据可能违反唯一性约束）", it) }
                }
            })
            .build()
}

/**
 * busy_timeout 必须落在每一个连接上（0.6.4.1）。
 * 文档示例中的 `config.copy(...)` 在 SDK 中不存在（Configuration 为 Java Builder 类），
 * 此处按同一语义以 Configuration.builder 重建实现。
 */
class BusyTimeoutOpenHelperFactory(private val busyTimeoutMs: Int) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val wrapped = object : SupportSQLiteOpenHelper.Callback(configuration.callback.version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.query("PRAGMA busy_timeout = $busyTimeoutMs").close()
                configuration.callback.onCreate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                db.query("PRAGMA busy_timeout = $busyTimeoutMs").close()
                configuration.callback.onUpgrade(db, oldVersion, newVersion)
            }

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                db.query("PRAGMA busy_timeout = $busyTimeoutMs").close()
                configuration.callback.onDowngrade(db, oldVersion, newVersion)
            }

            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.query("PRAGMA busy_timeout = $busyTimeoutMs").close()
                configuration.callback.onConfigure(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.query("PRAGMA busy_timeout = $busyTimeoutMs").close()
                configuration.callback.onOpen(db)
            }
        }
        val newConfig = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(wrapped)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(newConfig)
    }
}

object DbSeed {
    /**
     * 建库与每次打开都必须保证的单例行（幂等，0.6.4.5）。
     *
     * 这里的字面量只决定**新装库**的初始值：种子语句是 `INSERT OR IGNORE`，
     * 行已存在（老安装）时整条语句是 no-op。因此把关播确认次数的默认值从 2 改成 1
     * **不会**动到既有库里的既有值 —— 用户可能已经手动调过那个数字，
     * 静默覆盖等于篡改他的设置（这与 `V2_3__defaultInterval180` 那种
     * "只在值仍等于旧默认时才迁移"的取舍不同：这里选择完全不碰老数据）。
     */
    fun ensureSingletonRows(db: SupportSQLiteDatabase, now: Long) {
        db.execSQL(
            "INSERT OR IGNORE INTO system_runtime_lock(singletonId, maintenanceMode, monitorGeneration, updatedAt) " +
                "VALUES (1, 'OFF', 0, $now)"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO source_data_revision(singletonId, sourceDataVersion, updatedAt) " +
                "VALUES (1, 0, $now)"
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO monitoring_config(
                singletonId, configVersion, monitoringEnabled, mode, intervalSeconds, batchSize, maxConcurrency,
                timeoutSeconds, maxRetries, retryBaseSeconds, maxRetryDelaySeconds, circuitBreakerThreshold,
                circuitBreakerRecoverySeconds, aggregationEnabled, aggregationThreshold, aggregationWindowSeconds,
                batchCooldownSeconds, freshnessStaleSeconds, backgroundKeepAliveChoice, noGuaranteeAcknowledged,
                noGuaranteeAcknowledgedAt, startConfirmationCount, endConfirmationCount, placeholderText,
                retryFailureRatioPercent, banRecheckIntervalSeconds, circuitBreakerEnabled, updatedAt
            ) VALUES (
                1, 1, 1, 'POWER_SAVING', 60, 50, 4,
                8, 2, 5, 60, 3,
                60, 1, 4, 5,
                5, 300, 'UNSET', 0,
                NULL, 1, 1, '[待获取]',
                50, 1800, 1, $now)  -- 新装默认：intervalSeconds=60、timeoutSeconds=8、batchCooldownSeconds=5、retryFailureRatioPercent=50、banRecheckIntervalSeconds=1800、circuitBreakerEnabled=1；INSERT OR IGNORE ⇒ 老库既有值一律不动（endConfirmationCount=1 同此理）
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO monitoring_config_revision(
                configVersion, monitoringEnabled, mode, intervalSeconds, batchSize, maxConcurrency,
                timeoutSeconds, maxRetries, retryBaseSeconds, maxRetryDelaySeconds, circuitBreakerThreshold,
                circuitBreakerRecoverySeconds, aggregationEnabled, aggregationThreshold, aggregationWindowSeconds,
                batchCooldownSeconds, freshnessStaleSeconds, backgroundKeepAliveChoice, noGuaranteeAcknowledged,
                noGuaranteeAcknowledgedAt, startConfirmationCount, endConfirmationCount, placeholderText,
                retryFailureRatioPercent, banRecheckIntervalSeconds, circuitBreakerEnabled, createdAt
            ) VALUES (
                1, 1, 'POWER_SAVING', 60, 50, 4,
                8, 2, 5, 60, 3,
                60, 1, 4, 5,
                5, 300, 'UNSET', 0,
                NULL, 1, 1, '[待获取]',
                50, 1800, 1, $now)  -- 同上：修订快照与当前值必须同口径
            """.trimIndent()
        )
    }
}
