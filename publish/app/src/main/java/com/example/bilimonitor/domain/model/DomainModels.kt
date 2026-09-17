package com.example.bilimonitor.domain.model

import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.BatchResponseValidity
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.TransitionReason

/** 一次状态转换的候选判定结果（0.6.15 唯一契约）。 */
data class DetectedTransition(
    val streamerStableId: String,
    val from: ConfirmedLiveStatus,
    val to: ConfirmedLiveStatus,
    val transitionId: String,
    val observationSequence: Long,
    val streamerMonitorGeneration: Long,
    val reason: TransitionReason
)

/** applyConfirmedObservationAtomically 的唯一返回类型（0.6.15）。 */
sealed interface ApplyObservationResult {
    data class Applied(val committedSequence: Long, val newStatus: ConfirmedLiveStatus) : ApplyObservationResult
    data object StaleSequence : ApplyObservationResult
    data object StaleOrFenced : ApplyObservationResult
    data object ConfigStale : ApplyObservationResult
    data object DuplicateOpenSession : ApplyObservationResult
    data class Invalid(val reason: String) : ApplyObservationResult
}

/** 单个主播的一次观察结果（0.6.15）。 */
data class StreamerObservation(
    val streamerStableId: String,
    val uid: Long,
    val result: ObservationResult,
    val observedAt: Long
)

/** 远程房间资料；除 uid 外全部可空（249.9.1）。 */
data class RemoteLiveRoom(
    val uid: Long,
    val roomId: Long?,
    val shortRoomId: Long?,
    val name: String?,
    val avatarUrl: String?,
    val roomTitle: String?,
    val parentAreaName: String?,
    val areaName: String?,
    val coverUrl: String?,
    val liveUrl: String?,
    val remoteLiveStatus: ConfirmedLiveStatus?,
    val remoteStatusValid: Boolean,
    /**
     * 批量状态接口自带的封禁标记（`is_locked`）。
     *
     * ★ 三态，**默认 null = 未知**（不是 false）：
     * 该接口并非总是返回这个字段，把"没返回"当成"没被封禁"会让封禁判定在一次上游裁剪后静默失灵。
     * 只有在**明确 true** 时才允许产生封禁判定（见 RoomBanPolicy）。
     */
    val banLocked: Boolean? = null,
    /**
     * 封禁期限（毫秒；-1 = 无期限）。来源可能是 `room_init.lock_till`（unix 秒）
     * 或批量接口的 `lock_till` 日期字符串，解析规则见 RoomBanPolicy.parseLockTill。
     */
    val banLockTillMillis: Long? = null,
    /** `is_hidden`：与封禁无关，仅记录。 */
    val hidden: Boolean? = null
)

/** 批量响应整体语义（0.6.25）。 */
data class BatchRoomStatusResponse(
    val requestedUids: List<Long>,
    val resultsByUid: Map<Long, RemoteLiveRoom?>,
    val missingUids: Set<Long>,
    val duplicateUids: Set<Long>,
    val responseValidity: BatchResponseValidity,
    /**
     * 逐个 uid 的**失败原因**（用户诉求：原始信号必须留痕）。
     *
     * 此前调用方只看到 `resultsByUid[uid] == null`，无法区分"这个 uid 超时了"、
     * "这个 uid 服务端明确拒绝了"、"整个响应是坏的"，于是错误日志的 detail 恒为 null。
     * 现在无论成功失败，只要这个 uid 没拿到有效房间数据，就会在这里有一条原因。
     */
    val failuresByUid: Map<Long, com.example.bilimonitor.domain.policy.CallFailure> = emptyMap(),
    /**
     * 整批级别的失败原因（超时/IO/解析/业务拒绝/限流/5xx）。
     * 与 [failuresByUid] 的区别：这里描述的是"这次请求本身的机制结果"，可用于日志与重试判定。
     */
    val callFailure: com.example.bilimonitor.domain.policy.CallFailure? = null,
    /** 响应体里的业务 `code`（B 站语义，成功为 0）；解析失败时为 null。 */
    val apiCode: Int? = null,
    /** 响应体里的 `message` 摘要（已截断）。 */
    val apiMessage: String? = null
)

/**
 * 一次批量房间状态请求的结果：**要么拿到响应，要么带着原因失败**。
 *
 * 原来数据源直接返回 `null` 表示失败，把"原因"彻底丢掉；
 * 现在失败也带着 [CallFailure]，引擎据此决定要不要重试、并把原始信号写进错误日志。
 */
sealed interface BatchStatusOutcome {
    data class Responded(val response: BatchRoomStatusResponse) : BatchStatusOutcome
    data class Failed(val failure: com.example.bilimonitor.domain.policy.CallFailure) : BatchStatusOutcome

    /** 成功语义只在"拿到了响应"时成立（响应内部仍可能是 PARTIAL/INVALID，由上层判定）。 */
    val responded: Boolean get() = this is Responded
}

/** 配置快照完整字段（0.6.16）；同一 Tick 的所有批次只使用一个 Snapshot。 */
data class MonitoringConfigSnapshot(
    val configVersion: Long,
    val monitoringEnabled: Boolean,
    val mode: MonitoringMode,
    val intervalSeconds: Int,
    val batchSize: Int,
    val maxConcurrency: Int,
    val timeoutSeconds: Int,
    val maxRetries: Int,
    val retryBaseSeconds: Int,
    val maxRetryDelaySeconds: Int,
    val circuitBreakerThreshold: Int,
    val circuitBreakerRecoverySeconds: Int,
    /**
     * 熔断总开关（v10 新增配置，默认 **true**）。
     *
     * 语义：`true` = 连续失败达到 [circuitBreakerThreshold] 后进入熔断暂停，
     * 暂停 [circuitBreakerRecoverySeconds] 秒再尝试恢复检测；
     * `false` = **不做熔断暂停**，失败的批次一直重试、继续请求。
     *
     * 默认值刻意写成 `true`（而不是让调用方必须显式传）：引入本开关时默认行为
     * 必须与之前**完全一致**，且带默认值可以让其它构造点不因新增参数而编译失败。
     */
    val circuitBreakerEnabled: Boolean = true,
    val aggregationEnabled: Boolean,
    val aggregationThreshold: Int,
    val aggregationWindowSeconds: Int,
    val batchCooldownSeconds: Int,
    val backgroundKeepAliveChoice: BackgroundKeepAliveChoice,
    val noGuaranteeAcknowledged: Boolean,
    val startConfirmationCount: Int,
    val endConfirmationCount: Int,
    val placeholderText: String,
    /**
     * 触发重试/熔断的**最小失败占比**（%，默认 50，范围 1–100）。
     * 语义见 [com.example.bilimonitor.domain.policy.RoundFailureRatio]：
     * 分母 = 本轮参与检查的主播数，分子 = 本轮最终失败的主播数（同一主播只算 1 次），
     * **严格大于**该值才触发重试与熔断。
     */
    val retryFailureRatioPercent: Int = com.example.bilimonitor.domain.policy.RoundFailureRatio.DEFAULT_THRESHOLD_PERCENT,
    /**
     * 「封禁中」主播的复查间隔（秒，默认 1800）。
     * 封禁判定依据 `room_init.data.is_locked`，见 [com.example.bilimonitor.domain.policy.RoomBanPolicy]。
     */
    val banRecheckIntervalSeconds: Int = com.example.bilimonitor.domain.policy.RoomBanPolicy.DEFAULT_RECHECK_SECONDS,
    /** 主播级策略解析后的生效阈值快照：streamerStableId -> (start, end)。Tick 期间不得重读。 */
    val effectiveConfirmationCounts: Map<String, Pair<Int, Int>> = emptyMap(),
    /**
     * 免打扰时段（原规范 22.3）：文档要求可配、默认关。
     * 以「当天零点起的分钟数」表示，支持跨午夜（start > end）。
     * 存储沿用 MaintenanceRepository 的 preferencesDataStore，不涉及数据库迁移。
     */
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinutes: Int = DEFAULT_QUIET_START,
    val quietHoursEndMinutes: Int = DEFAULT_QUIET_END,
    /** 主播级免打扰覆盖：streamerStableId -> 是否启用。 */
    val effectiveQuietHours: Map<String, Boolean> = emptyMap(),
    /** 主播级免打扰时段覆盖：streamerStableId -> (start, end)。 */
    val effectiveQuietHoursRange: Map<String, Pair<Int, Int>> = emptyMap(),
    /** 主播级通知开关快照：streamerStableId -> (notifyStart, notifyEnd)。 */
    val effectiveNotifyPolicy: Map<String, Pair<Boolean, Boolean>> = emptyMap(),
    /** 标题变化通知：streamerStableId -> 是否开启（缺省 = 关）。 */
    val effectiveNotifyTitleChange: Map<String, Boolean> = emptyMap(),
    /** 分区变化通知：streamerStableId -> 是否开启（缺省 = 关）。 */
    val effectiveNotifyAreaChange: Map<String, Boolean> = emptyMap(),
    /**
     * 数据新鲜度阈值（秒，默认 300，范围 30–86400）—— 「数据过期」的判定依据。
     *
     * 它是 `monitoring_config.freshnessStaleSeconds` 的**既有**字段（不是新增配置），
     * 放进快照是因为引擎现在要在 Tick 内补写 `streamer.freshnessStatus` 那一列
     * （见 `MonitoringEngine.refreshFreshnessSnapshots`）：Tick 期间不重读配置是本快照的
     * 硬约束（0.6.16），而补写与界面现算必须用同一个阈值，否则两处会各说各话。
     *
     * ★ 引擎用的**有效阈值**还要再按实际周期抬高：`max(本值, 2 × 周期)`，
     *   见 `domain.policy.FreshnessPolicy.effectiveThresholdSeconds`（省电模式周期 ≥15 分钟）。
     */
    val freshnessStaleSeconds: Int = com.example.bilimonitor.domain.policy.FreshnessPolicy.DEFAULT_THRESHOLD_SECONDS
) {
    fun startCountFor(streamerStableId: String): Int =
        effectiveConfirmationCounts[streamerStableId]?.first ?: startConfirmationCount

    fun endCountFor(streamerStableId: String): Int =
        effectiveConfirmationCounts[streamerStableId]?.second ?: endConfirmationCount

    fun notifyStartFor(streamerStableId: String): Boolean =
        effectiveNotifyPolicy[streamerStableId]?.first ?: true

    fun notifyEndFor(streamerStableId: String): Boolean =
        effectiveNotifyPolicy[streamerStableId]?.second ?: true

    /**
     * 标题/分区变化通知：**缺省为关**（与开播/关播通知的缺省为开相反）。
     * 直播中改标题可能很频繁，默认打开就是打扰；必须是用户显式选择。
     */
    fun notifyTitleChangeFor(streamerStableId: String): Boolean =
        effectiveNotifyTitleChange[streamerStableId] ?: false

    fun notifyAreaChangeFor(streamerStableId: String): Boolean =
        effectiveNotifyAreaChange[streamerStableId] ?: false

    /** 该主播生效的免打扰区间；未启用返回 null。主播级覆盖优先于全局。 */
    fun quietHoursRangeFor(streamerStableId: String): Pair<Int, Int>? {
        val enabled = effectiveQuietHours[streamerStableId] ?: quietHoursEnabled
        if (!enabled) return null
        return effectiveQuietHoursRange[streamerStableId]
            ?: (quietHoursStartMinutes to quietHoursEndMinutes)
    }

    companion object {
        /** 原规范 22.3 的默认时段：23:00–07:00。 */
        const val DEFAULT_QUIET_START = 23 * 60
        const val DEFAULT_QUIET_END = 7 * 60
    }
}

/** 监控租约（0.6.35）。 */
data class MonitoringLease(
    val leaseId: String,
    val runtimeInstanceId: String,
    val monitorGeneration: Long,
    val fencingToken: String,
    val leaseUntilWall: Long,
    val leaseUntilElapsed: Long,
    val bootId: String
)

/** tick 执行环境。 */
data class TickContext(
    val snapshot: MonitoringConfigSnapshot,
    val lease: MonitoringLease,
    val clock: AppClock
)
