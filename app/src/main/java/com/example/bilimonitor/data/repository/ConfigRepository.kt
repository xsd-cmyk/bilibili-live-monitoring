package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.dao.ConfigDao
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.PolicyDao
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.MonitoringConfigRevisionEntity
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.domain.model.MonitoringConfigSnapshot
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConfigUpdateResult {
    data object Applied : ConfigUpdateResult
    data object VersionConflict : ConfigUpdateResult
    data class Invalid(val reason: String) : ConfigUpdateResult
}

data class ConfigUpdate(
    val monitoringEnabled: Boolean? = null,
    val mode: MonitoringMode? = null,
    val intervalSeconds: Int? = null,
    val batchSize: Int? = null,
    val maxConcurrency: Int? = null,
    val timeoutSeconds: Int? = null,
    val maxRetries: Int? = null,
    val retryBaseSeconds: Int? = null,
    val maxRetryDelaySeconds: Int? = null,
    val circuitBreakerThreshold: Int? = null,
    val circuitBreakerRecoverySeconds: Int? = null,
    val aggregationEnabled: Boolean? = null,
    val aggregationThreshold: Int? = null,
    val aggregationWindowSeconds: Int? = null,
    val batchCooldownSeconds: Int? = null,
    val freshnessStaleSeconds: Int? = null,
    val startConfirmationCount: Int? = null,
    val endConfirmationCount: Int? = null,
    val placeholderText: String? = null,
    /** 触发重试/熔断的最小失败占比（%，默认 50，范围 1–100）。 */
    val retryFailureRatioPercent: Int? = null,
    /** 「封禁中」主播的复查间隔（秒，默认 1800，范围 60–86400）。 */
    val banRecheckIntervalSeconds: Int? = null,
    /**
     * 熔断总开关（v10 新增，默认 true = 保持既有行为）。
     * `false` ⇒ 失败的批次一直重试、继续请求，不再进入熔断暂停。
     */
    val circuitBreakerEnabled: Boolean? = null
)

@Singleton
class ConfigRepository @Inject constructor(
    private val db: AppDatabase,
    private val configDao: ConfigDao,
    private val policyDao: PolicyDao,
    private val logDao: LogDao,
    private val clock: AppClock,
    private val quietHoursRepository: QuietHoursRepository
) {
    fun getConfigFlow() = configDao.observeConfig()

    /** 便捷开关：供通知栏「停止监控」等非界面路径使用（走同一条 CAS 更新，带审计）。 */
    suspend fun setMonitoringEnabled(enabled: Boolean): ConfigUpdateResult =
        update(ConfigUpdate(monitoringEnabled = enabled))

    /** MonitoringConfigSnapshot（0.6.16）：主播级阈值/通知策略在同一事务内解析，Tick 期间不重读。 */
    suspend fun getSnapshot(): MonitoringConfigSnapshot? {
        val config = configDao.getConfig() ?: return null
        val quiet = quietHoursRepository.current()
        val snapshotBase = MonitoringConfigSnapshot(
            configVersion = config.configVersion,
            monitoringEnabled = config.monitoringEnabled,
            mode = config.mode,
            intervalSeconds = config.intervalSeconds,
            batchSize = config.batchSize,
            maxConcurrency = config.maxConcurrency,
            timeoutSeconds = config.timeoutSeconds,
            maxRetries = config.maxRetries,
            retryBaseSeconds = config.retryBaseSeconds,
            maxRetryDelaySeconds = config.maxRetryDelaySeconds,
            circuitBreakerThreshold = config.circuitBreakerThreshold,
            circuitBreakerRecoverySeconds = config.circuitBreakerRecoverySeconds,
            circuitBreakerEnabled = config.circuitBreakerEnabled,
            aggregationEnabled = config.aggregationEnabled,
            aggregationThreshold = config.aggregationThreshold,
            aggregationWindowSeconds = config.aggregationWindowSeconds,
            batchCooldownSeconds = config.batchCooldownSeconds,
            backgroundKeepAliveChoice = config.backgroundKeepAliveChoice,
            noGuaranteeAcknowledged = config.noGuaranteeAcknowledged,
            startConfirmationCount = config.startConfirmationCount,
            endConfirmationCount = config.endConfirmationCount,
            placeholderText = config.placeholderText,
            // v9 两个新配置：默认值刻意与实体默认值/DDL DEFAULT 保持一致
            // （任一处不同步，老库升级后读到的生效值就会与设置页显示的值不一致）。
            retryFailureRatioPercent = config.retryFailureRatioPercent,
            banRecheckIntervalSeconds = config.banRecheckIntervalSeconds,
            // 既有的数据新鲜度阈值（不是新配置）：引擎在 Tick 内补写 freshnessStatus 那一列时
            // 要用它，而 Tick 期间不重读配置是本快照的硬约束。
            freshnessStaleSeconds = config.freshnessStaleSeconds,
            quietHoursEnabled = quiet.enabled,
            quietHoursStartMinutes = quiet.startMinutes,
            quietHoursEndMinutes = quiet.endMinutes
        )
        val active = db.streamerDao().listMonitorable()
        val counts = mutableMapOf<String, Pair<Int, Int>>()
        val notify = mutableMapOf<String, Pair<Boolean, Boolean>>()
        val notifyTitle = mutableMapOf<String, Boolean>()
        val notifyArea = mutableMapOf<String, Boolean>()
        val quietOverride = mutableMapOf<String, Boolean>()
        for (s in active) {
            val p = policyDao.get(s.id)
            if (p != null) {
                counts[s.stableId] = Pair(
                    p.startConfirmationCount ?: config.startConfirmationCount,
                    p.endConfirmationCount ?: config.endConfirmationCount
                )
                notify[s.stableId] = Pair(p.notifyStart, p.notifyEnd)
                notifyTitle[s.stableId] = p.notifyTitleChange
                notifyArea[s.stableId] = p.notifyAreaChange
                // 主播级免打扰覆盖（0.6.47.3 同款"NULL = 跟随全局"语义）
                p.overrideQuietHours?.let { quietOverride[s.stableId] = it }
            }
        }
        return snapshotBase.copy(
            effectiveConfirmationCounts = counts,
            effectiveQuietHours = quietOverride,
            effectiveNotifyPolicy = notify,
            effectiveNotifyTitleChange = notifyTitle,
            effectiveNotifyAreaChange = notifyArea
        )
    }

    suspend fun update(update: ConfigUpdate): ConfigUpdateResult {        val now = clock.nowWall()
        return db.withTransaction {
                val config = configDao.getConfig() ?: return@withTransaction ConfigUpdateResult.Invalid("配置不存在") as ConfigUpdateResult
                val merged = merge(config, update) ?: return@withTransaction ConfigUpdateResult.Invalid("参数非法") as ConfigUpdateResult
                val newVersion = config.configVersion + 1
                configDao.insertConfigRevision(
                    MonitoringConfigRevisionEntity(
                        configVersion = newVersion,
                        monitoringEnabled = merged.monitoringEnabled,
                        mode = merged.mode,
                        intervalSeconds = merged.intervalSeconds,
                        batchSize = merged.batchSize,
                        maxConcurrency = merged.maxConcurrency,
                        timeoutSeconds = merged.timeoutSeconds,
                        maxRetries = merged.maxRetries,
                        retryBaseSeconds = merged.retryBaseSeconds,
                        maxRetryDelaySeconds = merged.maxRetryDelaySeconds,
                        circuitBreakerThreshold = merged.circuitBreakerThreshold,
                        circuitBreakerRecoverySeconds = merged.circuitBreakerRecoverySeconds,
                        aggregationEnabled = merged.aggregationEnabled,
                        aggregationThreshold = merged.aggregationThreshold,
                        aggregationWindowSeconds = merged.aggregationWindowSeconds,
                        batchCooldownSeconds = merged.batchCooldownSeconds,
                        freshnessStaleSeconds = merged.freshnessStaleSeconds,
                        backgroundKeepAliveChoice = merged.backgroundKeepAliveChoice,
                        noGuaranteeAcknowledged = merged.noGuaranteeAcknowledged,
                        noGuaranteeAcknowledgedAt = merged.noGuaranteeAcknowledgedAt,
                        startConfirmationCount = merged.startConfirmationCount,
                        endConfirmationCount = merged.endConfirmationCount,
                        placeholderText = merged.placeholderText,
                        retryFailureRatioPercent = merged.retryFailureRatioPercent,
                        banRecheckIntervalSeconds = merged.banRecheckIntervalSeconds,
                        circuitBreakerEnabled = merged.circuitBreakerEnabled,
                        createdAt = now
                    )
                )
                val rows = configDao.casUpdateConfig(
                    expectedConfigVersion = config.configVersion,
                    newConfigVersion = newVersion,
                    monitoringEnabled = merged.monitoringEnabled,
                    mode = merged.mode,
                    intervalSeconds = merged.intervalSeconds,
                    batchSize = merged.batchSize,
                    maxConcurrency = merged.maxConcurrency,
                    timeoutSeconds = merged.timeoutSeconds,
                    maxRetries = merged.maxRetries,
                    retryBaseSeconds = merged.retryBaseSeconds,
                    maxRetryDelaySeconds = merged.maxRetryDelaySeconds,
                    circuitBreakerThreshold = merged.circuitBreakerThreshold,
                    circuitBreakerRecoverySeconds = merged.circuitBreakerRecoverySeconds,
                    aggregationEnabled = merged.aggregationEnabled,
                    aggregationThreshold = merged.aggregationThreshold,
                    aggregationWindowSeconds = merged.aggregationWindowSeconds,
                    batchCooldownSeconds = merged.batchCooldownSeconds,
                    freshnessStaleSeconds = merged.freshnessStaleSeconds,
                    keepAliveChoice = merged.backgroundKeepAliveChoice,
                    noGuaranteeAcknowledged = merged.noGuaranteeAcknowledged,
                    noGuaranteeAcknowledgedAt = merged.noGuaranteeAcknowledgedAt,
                    startConfirmationCount = merged.startConfirmationCount,
                    endConfirmationCount = merged.endConfirmationCount,
                    placeholderText = merged.placeholderText,
                    retryFailureRatioPercent = merged.retryFailureRatioPercent,
                    banRecheckIntervalSeconds = merged.banRecheckIntervalSeconds,
                    circuitBreakerEnabled = merged.circuitBreakerEnabled,
                    now = now
                )
                if (rows == 0) ConfigUpdateResult.VersionConflict else ConfigUpdateResult.Applied
        }
    }
    /**
     * 0.6.47.1 completeOnboarding：选择落库 + 同事务写 AuditLog(action='SET_KEEPALIVE_CHOICE')。
     */
    suspend fun completeOnboarding(
        choice: BackgroundKeepAliveChoice,
        noGuaranteeAcknowledged: Boolean,
        mode: MonitoringMode = MonitoringMode.POWER_SAVING
    ): Boolean {
        val now = clock.nowWall()
        return db.withTransaction {
                val ack = choice == BackgroundKeepAliveChoice.NO_GUARANTEE && noGuaranteeAcknowledged
                val rows = configDao.updateKeepAliveChoice(
                    choice = choice,
                    acknowledged = ack,
                    acknowledgedAt = if (ack) now else null,
                    now = now
                )
                if (rows > 0) {
                    val newMode = when (choice) {
                        BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION -> MonitoringMode.REALTIME
                        BackgroundKeepAliveChoice.ACCESSIBILITY -> MonitoringMode.REALTIME
                        // 双保险（用户要求）：无障碍与前台常驻通知**同时**生效，同样是实时模式
                        BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND -> MonitoringMode.REALTIME
                        BackgroundKeepAliveChoice.NO_GUARANTEE -> MonitoringMode.POWER_SAVING
                        BackgroundKeepAliveChoice.UNSET -> mode
                    }
                    val config = configDao.getConfig()
                    if (config != null) {
                        configDao.insertConfigRevision(
                            MonitoringConfigRevisionEntity(
                                configVersion = config.configVersion,
                                monitoringEnabled = config.monitoringEnabled,
                                mode = newMode,
                                intervalSeconds = config.intervalSeconds,
                                batchSize = config.batchSize,
                                maxConcurrency = config.maxConcurrency,
                                timeoutSeconds = config.timeoutSeconds,
                                maxRetries = config.maxRetries,
                                retryBaseSeconds = config.retryBaseSeconds,
                                maxRetryDelaySeconds = config.maxRetryDelaySeconds,
                                circuitBreakerThreshold = config.circuitBreakerThreshold,
                                circuitBreakerRecoverySeconds = config.circuitBreakerRecoverySeconds,
                                aggregationEnabled = config.aggregationEnabled,
                                aggregationThreshold = config.aggregationThreshold,
                                aggregationWindowSeconds = config.aggregationWindowSeconds,
                                batchCooldownSeconds = config.batchCooldownSeconds,
                                freshnessStaleSeconds = config.freshnessStaleSeconds,
                                backgroundKeepAliveChoice = choice,
                                noGuaranteeAcknowledged = ack,
                                noGuaranteeAcknowledgedAt = if (ack) now else null,
                                startConfirmationCount = config.startConfirmationCount,
                                endConfirmationCount = config.endConfirmationCount,
                                placeholderText = config.placeholderText,
                                retryFailureRatioPercent = config.retryFailureRatioPercent,
                                banRecheckIntervalSeconds = config.banRecheckIntervalSeconds,
                                circuitBreakerEnabled = config.circuitBreakerEnabled,
                                createdAt = now
                            )
                        )
                        configDao.casUpdateConfig(
                            expectedConfigVersion = config.configVersion,
                            newConfigVersion = config.configVersion,
                            monitoringEnabled = config.monitoringEnabled,
                            mode = newMode,
                            intervalSeconds = config.intervalSeconds,
                            batchSize = config.batchSize,
                            maxConcurrency = config.maxConcurrency,
                            timeoutSeconds = config.timeoutSeconds,
                            maxRetries = config.maxRetries,
                            retryBaseSeconds = config.retryBaseSeconds,
                            maxRetryDelaySeconds = config.maxRetryDelaySeconds,
                            circuitBreakerThreshold = config.circuitBreakerThreshold,
                            circuitBreakerRecoverySeconds = config.circuitBreakerRecoverySeconds,
                            aggregationEnabled = config.aggregationEnabled,
                            aggregationThreshold = config.aggregationThreshold,
                            aggregationWindowSeconds = config.aggregationWindowSeconds,
                            batchCooldownSeconds = config.batchCooldownSeconds,
                            freshnessStaleSeconds = config.freshnessStaleSeconds,
                            keepAliveChoice = choice,
                            noGuaranteeAcknowledged = ack,
                            noGuaranteeAcknowledgedAt = if (ack) now else null,
                            startConfirmationCount = config.startConfirmationCount,
                            endConfirmationCount = config.endConfirmationCount,
                            placeholderText = config.placeholderText,
                            retryFailureRatioPercent = config.retryFailureRatioPercent,
                            banRecheckIntervalSeconds = config.banRecheckIntervalSeconds,
                            circuitBreakerEnabled = config.circuitBreakerEnabled,
                            now = now
                        )
                    }
                    logDao.insertAudit(
                        AuditLogEntity(
                            auditId = Ids.newId(),
                            operationId = Ids.newId(),
                            actor = "USER",
                            action = "SET_KEEPALIVE_CHOICE",
                            targetType = "monitoring_config",
                            targetStableId = "1",
                            occurredAt = now,
                            detailJson = "{\"choice\":\"$choice\"}"
                        )
                    )
                }
                rows > 0
        }
    }

    private fun merge(
        config: com.example.bilimonitor.data.local.entity.MonitoringConfigEntity,
        update: ConfigUpdate
    ): com.example.bilimonitor.data.local.entity.MonitoringConfigEntity? {
        val interval = update.intervalSeconds ?: config.intervalSeconds
        val batch = update.batchSize ?: config.batchSize
        val concurrency = update.maxConcurrency ?: config.maxConcurrency
        val timeout = update.timeoutSeconds ?: config.timeoutSeconds
        val retries = update.maxRetries ?: config.maxRetries
        val startCount = update.startConfirmationCount ?: config.startConfirmationCount
        val endCount = update.endConfirmationCount ?: config.endConfirmationCount
        val aggThreshold = update.aggregationThreshold ?: config.aggregationThreshold
        // 这些参数此前完全没有校验。它们直接决定引擎里的 delay 与熔断窗口，
        // 坏值（例如 maxRetryDelaySeconds = 2^31）会让一次 Tick 睡上数天，
        // 而 withTimeout 只包住请求、不覆盖 delay，监控会实质停摆且没有任何兜底。
        val retryBase = update.retryBaseSeconds ?: config.retryBaseSeconds
        val maxRetryDelay = update.maxRetryDelaySeconds ?: config.maxRetryDelaySeconds
        val cbThreshold = update.circuitBreakerThreshold ?: config.circuitBreakerThreshold
        val cbRecovery = update.circuitBreakerRecoverySeconds ?: config.circuitBreakerRecoverySeconds
        // v10 熔断总开关：Boolean 没有"非法值"可校验，唯一的合并规则就是
        // "没传（null）就保持库里现值" —— 这正是老版本客户端/旧备份不带该字段时的正确回落。
        val cbEnabled = update.circuitBreakerEnabled ?: config.circuitBreakerEnabled
        val aggWindow = update.aggregationWindowSeconds ?: config.aggregationWindowSeconds
        val batchCooldown = update.batchCooldownSeconds ?: config.batchCooldownSeconds
        val freshnessStale = update.freshnessStaleSeconds ?: config.freshnessStaleSeconds

        // 检查间隔下限 30s（用户要求）：必须与设置页 NumberInputSetting 的 validRange 同步 ——
        // 只改一处会出现"界面能填、保存却报参数非法"（或反过来"界面拦住、库里其实存得下"）的割裂。
        // 注意这里校验的是**合并后**的值：老库里已存的 <30 的值会让本次以及后续任何更新都被这一行拒掉，
        // 用户必须先把间隔改成 ≥30 才能再改其它设置；按项目原则不做静默改写。
        if (interval < 30 || interval > 3600) return null
        if (batch < 1 || batch > 100) return null
        if (concurrency < 1 || concurrency > 16) return null
        if (timeout < 5 || timeout > 60) return null
        if (retries < 0 || retries > 5) return null
        if (startCount < 1 || endCount < 1 || aggThreshold < 1) return null
        // 退避与熔断：给每个参数一个明确上界，避免坏配置把 Tick 拖成"永睡"
        if (retryBase < 1 || retryBase > 60) return null
        if (maxRetryDelay < 1 || maxRetryDelay > 300) return null
        if (maxRetryDelay < retryBase) return null
        if (cbThreshold < 1 || cbThreshold > 20) return null
        if (cbRecovery < 1 || cbRecovery > 3600) return null
        if (aggWindow < 1 || aggWindow > 300) return null
        if (batchCooldown < 0 || batchCooldown > 300) return null
        if (freshnessStale < 30 || freshnessStale > 86_400) return null

        // ★ v9 新增两项（用户可配）。三处范围必须逐字同步：
        //   ① 这里（落库校验）② 设置页 NumberInputSetting 的 validRange ③ 策略对象里的常量
        //   （RoundFailureRatio.THRESHOLD_RANGE / RoomBanPolicy.RECHECK_RANGE）。
        //   只改一处就会出现"界面能填、保存却报参数非法"（或反过来"界面拦住、库里其实存得下"）。
        val retryRatio = update.retryFailureRatioPercent ?: config.retryFailureRatioPercent
        val banRecheck = update.banRecheckIntervalSeconds ?: config.banRecheckIntervalSeconds
        val ratioRange = com.example.bilimonitor.domain.policy.RoundFailureRatio.THRESHOLD_RANGE
        if (retryRatio !in ratioRange) return null
        if (banRecheck !in com.example.bilimonitor.domain.policy.RoomBanPolicy.RECHECK_RANGE) return null

        return config.copy(
            monitoringEnabled = update.monitoringEnabled ?: config.monitoringEnabled,
            mode = update.mode ?: config.mode,
            intervalSeconds = interval,
            batchSize = batch,
            maxConcurrency = concurrency,
            timeoutSeconds = timeout,
            maxRetries = retries,
            retryBaseSeconds = retryBase,
            maxRetryDelaySeconds = maxRetryDelay,
            circuitBreakerThreshold = cbThreshold,
            circuitBreakerRecoverySeconds = cbRecovery,
            circuitBreakerEnabled = cbEnabled,
            aggregationEnabled = update.aggregationEnabled ?: config.aggregationEnabled,
            aggregationThreshold = aggThreshold,
            aggregationWindowSeconds = aggWindow,
            batchCooldownSeconds = batchCooldown,
            freshnessStaleSeconds = freshnessStale,
            startConfirmationCount = startCount,
            endConfirmationCount = endCount,
            placeholderText = update.placeholderText ?: config.placeholderText,
            retryFailureRatioPercent = retryRatio,
            banRecheckIntervalSeconds = banRecheck
        )
    }
}
