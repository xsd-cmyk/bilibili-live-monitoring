package com.example.bilimonitor.background

import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.GapReason
import com.example.bilimonitor.data.local.GapScope
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.HealthEventEntity
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.entity.MonitoringErrorLogEntity
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.entity.ProblemStateEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapEntity
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.MonitoringGapDao
import com.example.bilimonitor.data.local.dao.ProblemDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.remote.bilibili.BiliLiveDataSource
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.data.repository.NotificationOutboxWriter
import com.example.bilimonitor.data.repository.NotificationPayloads
import com.example.bilimonitor.data.repository.SystemProblemPayload
import com.example.bilimonitor.data.repository.MonitorRepository
import com.example.bilimonitor.data.repository.NotificationRepository
import com.example.bilimonitor.data.repository.RuntimeLockRepository
import com.example.bilimonitor.data.repository.StreamerNotificationPolicy
import com.example.bilimonitor.domain.model.ApplyObservationResult
import com.example.bilimonitor.domain.model.BatchStatusOutcome
import com.example.bilimonitor.domain.model.DetectedTransition
import com.example.bilimonitor.domain.model.MonitoringConfigSnapshot
import com.example.bilimonitor.domain.model.MonitoringLease
import com.example.bilimonitor.domain.model.RemoteLiveRoom
import com.example.bilimonitor.domain.model.StreamerObservation
import com.example.bilimonitor.domain.policy.BanLogEdgeGate
import com.example.bilimonitor.domain.policy.BanLogPolicy
import com.example.bilimonitor.domain.policy.BanProbeOutcome
import com.example.bilimonitor.domain.policy.BanTransition
import com.example.bilimonitor.domain.policy.CallFailure
import com.example.bilimonitor.domain.policy.FreshnessPolicy
import com.example.bilimonitor.domain.policy.RoomFailureMapping
import com.example.bilimonitor.domain.policy.RoomFailureReason
import com.example.bilimonitor.domain.policy.RoundFailureCode
import com.example.bilimonitor.domain.policy.RoundFailureGate
import com.example.bilimonitor.domain.policy.RoundFailureLedger
import com.example.bilimonitor.domain.policy.RoundFailureRatio
import com.example.bilimonitor.domain.policy.StateConfirmationPolicy
import com.example.bilimonitor.domain.policy.StreamerMonitorContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class MonitoringResult(
    val ran: Boolean,
    val reason: String,
    val checked: Int = 0,
    val failures: Int = 0
)

/**
 * 监控引擎（原规范 10 / 0.6.47.3）：
 * 单实例租约 + 配置快照 + 批量观察 + 状态确认 + 原子提交 + 通知 Outbox。
 * Tick 不重叠（245.2）：并发调用被合并。
 */
@Singleton
class MonitoringEngine @Inject constructor(
    private val configRepository: ConfigRepository,
    private val streamerDao: StreamerDao,
    private val dataSource: BiliLiveDataSource,
    private val monitorRepository: MonitorRepository,
    private val notificationRepository: NotificationRepository,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val monitoringGapDao: MonitoringGapDao,
    private val reliableIntervalDao: com.example.bilimonitor.data.local.dao.ReliableIntervalDao,
    private val problemDao: ProblemDao,
    private val logDao: LogDao,
    private val dbHandle: com.example.bilimonitor.data.local.db.AppDatabase,
    private val clock: AppClock,
    @Named("appScope") private val scope: CoroutineScope,
    private val notificationDispatcher: NotificationDispatcher
) {
    private val tickMutex = Mutex()
    private var heldLease: MonitoringLease? = null

    /**
     * 最近一次 Tick 看到的主播数 / 正在直播数。
     *
     * 用途：前台常驻通知的自定义文案支持 `{count}` / `{live}` 占位符，
     * 而通知是在**主线程**的 `onStartCommand` 里构造的 —— 在那里查数据库正是本项目
     * 一直在修的那类问题（主线程 IO）。所以由已经拿到主播列表的 Tick 顺手发布，
     * 通知侧只读这个 `@Volatile` 快照（写读都在同一进程，可见性足够）。
     */
    @Volatile
    var lastMonitoredCount: Int? = null
        private set

    @Volatile
    var lastLiveCount: Int? = null
        private set

    private fun publishCounts(streamers: List<com.example.bilimonitor.data.local.entity.StreamerEntity>) {
        lastMonitoredCount = streamers.size
        lastLiveCount = streamers.count {
            // ROUND（轮播）不计入"在播"（用户要求：轮播按未开播处理），
            // 否则通知里的 {live} 数字会把轮播中的主播算成正在直播。
            it.confirmedLiveStatus == com.example.bilimonitor.data.local.ConfirmedLiveStatus.LIVE
        }
    }
    private val consecutiveTransportFailures = AtomicInteger(0)

    /**
     * 最近一次"大面积失败"的**归因**（缺陷 2）。
     *
     * 用途只有一个：熔断打开后的那一轮**没有发任何请求**（[breakerOpen] 分支直接返回），
     * 于是它无法自己判定"这次到底是网络断了还是被接口拒绝了"。此时沿用上一轮的归因，
     * 保证 SYSTEM 盲区的 `GapReason`、错误码与通知文案与真正发生的事故一致；
     * 进程重启后这里是 null，那时**如实**落到 `UNKNOWN`（宁可说"原因未能确定"，
     * 也不要沿用"网络不可用"这个修复前的老毛病）。
     */
    @Volatile
    private var lastGlobalFailure: RoundFailureGate.GlobalFailureAttribution? = null

    fun refreshNowAsync(reason: String = "manual") {
        // appScope 没有 CoroutineExceptionHandler，而 checkOnce 里有多处 DB 调用不在
        // runCatching 内（getSnapshot / listMonitorable / dispatchDue）。
        // 一旦抛 SQLiteException 会冒泡到默认未捕获处理器 → **进程崩溃**。
        // 服务 / Worker / 恢复路径都有各自的 catch，唯独"用户点刷新"这条最直接的路径没有。
        scope.launch {
            runCatching { checkOnce(reason) }
                .onFailure { logAppError(AppError.UNKNOWN, "manual refresh failed: ${it.message}") }
        }
    }

    suspend fun checkOnce(reason: String): MonitoringResult {
        val result = tickMutex.withLock {
            // Tick 级预算（台账 H19-A5）：单批最坏 = maxRetries 次尝试 × (timeout+5s) + 退避累积，
            // 理论上可以到几十分钟 —— 那期间用户看到"监控中"却长时间毫无变化。
            // ★ 这里原来写着"批次收集是全有或全无、已成功的批次也不会被写入"，**那句话是错的**：
            //   观察是**逐批**落库的（processOutcome → applyConfirmedObservationAtomically，
            //   每批各自一个事务），硬取消不会丢掉已经成功那几批的写入。真正会丢的是批次循环
            //   **之后**的收尾：末轮比例结算、系统级故障通知、健康事件、计数发布 ——
            //   也就是"这一轮为什么大面积失败"那条通知永远发不出去。
            //   （独立复查曾被这句错误的注释误导过，故订正。）
            val budgetMs = runCatching { configRepository.getSnapshot() }
                .getOrNull()?.let { (it.intervalSeconds * 1000L * 2).coerceIn(60_000L, 900_000L) }
                ?: 300_000L
            // ★ 把"预算到期时刻"（单调时钟上的绝对时刻）交给 runTick：外层 withTimeout 是**硬取消**，
            //   而熔断关闭之后重试轮没有别的闸门（熔断打开时 `if (breakerOpen(snapshot)) return@…`
            //   的提前收手顺带把重试轮压在预算内）。让重试轮自己看着预算收手，
            //   才能保证批次循环**之后**的收尾跑得完 —— 大面积故障通知就在那里。
            val deadlineElapsed = clock.nowElapsed() + budgetMs
            try {
                kotlinx.coroutines.withTimeout(budgetMs) { runTick(reason, deadlineElapsed) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                logAppError(
                    AppError.NETWORK_TIMEOUT,
                    "本轮检查超过预算 ${budgetMs / 1000}s 未完成，已放弃本轮（下一轮照常开始）"
                )
                MonitoringResult(false, "tick_budget_exceeded")
            }
        }
        // ★ 通知投递放在 Tick 互斥锁**之外**（台账 H19-E3）：
        //   投递可能包含封面图片下载（单条最长数秒）且最多 20 条串行，
        //   放在锁内会把手动刷新、Worker、恢复补检全部堵住，表现为"监控卡住"。
        runCatching { notificationDispatcher.dispatchDue() }
            .onFailure { logAppError(AppError.UNKNOWN, "通知投递失败：${it.message}") }
        return result
    }

    /**
     * @param deadlineElapsed 本轮预算到期的**单调时钟**时刻（见 [checkOnce]）。重试轮据此收手，
     *   保证批次循环之后的收尾（末轮比例结算、系统级故障通知、健康事件、计数发布）跑得完。
     */
    private suspend fun runTick(reason: String, deadlineElapsed: Long): MonitoringResult {
        val now = clock.nowWall()
        // ★ 封禁探针的额度是**每轮**的：两个预算对象是进程级单例，不在每轮开头补满，
        //   它们就变成"终身 8 次"—— 额度用尽之后再也不会探测封禁（既探不到新封禁，也复查不了解封），
        //   而文档与注释说的都是"每轮上限"。补满放在 Tick 互斥锁内、任何早退分支之前，
        //   保证"每轮恰好重置一次"，与这一轮走到哪个分支无关。
        probeBudget.reset()
        recheckProbeBudget.reset()
        val snapshot = configRepository.getSnapshot()
        if (snapshot == null) {
            logAppError(AppError.CONFIG_INVALID, "monitoring_config 缺失")
            return MonitoringResult(false, "config_missing")
        }
        if (!snapshot.monitoringEnabled) return MonitoringResult(false, "disabled")

        // 租约：优先续期；失败重新获取（0.6.35 续期契约）。
        val lease = ensureLease() ?: run {
            logHealth(MonitorHealthStatus.BLOCKED, "LEASE_LOST")
            return MonitoringResult(false, "lease_lost")
        }

        // 通知聚合窗口结算（窗口到期 → 批量/释放）。
        runCatching { notificationRepository.processWindowEnds() }

        // 熔断打开期间不发请求，直接如实记一次失败（0.6.35 / 原规范 180）。
        // ★ 熔断现在**只可能**由「本轮失败占比越线」触发（见 RoundFailureGate），
        //   所以能走到这里就说明上一轮确实是大面积失败，而不是一个坏房间把它顶开的。
        // ★ 本轮失败的原因按**上一轮的归因**写（缺陷 2）：这一轮连请求都没发出去，
        //   自己判不出原因；硬写"网络"会让被限流/被拒绝顶开的熔断在每位主播行上
        //   继续显示成网络故障，系统级与主播级又互相矛盾。
        // ★ 熔断总开关（用户可配，默认开）关闭时：熔断器一并复位，然后这条"暂停请求"的
        //   路径整体不生效 —— 请求照常发，也不会因为"关开关之前攒下的旧状态"而在重新打开
        //   开关后立刻多早退一轮。它**只**影响"连续失败后暂停请求"这一件事：比例门禁
        //   （重试轮）、系统盲区与问题通知都由本函数后面的逻辑独立决定，与这个开关无关。
        if (!snapshot.circuitBreakerEnabled) resetBreaker()
        if (breakerOpen(snapshot)) {
            val streamers = streamerDao.listMonitorable()
            publishCounts(streamers)
            // 熔断期间没有真实响应可记，只能记"因熔断未发请求"这一事实（detail 说明原因，
            // 免得日后又看到一批无来源的 NETWORK_ERROR 却查不出为什么）。
            // ★ 归因沿用上一轮（缺陷 2）：熔断只可能由"大面积失败"顶开，而上一轮的归因
            //   已经算出来了。硬写 NETWORK 会让"被接口限流顶开的熔断"在每位主播行上
            //   继续显示成网络故障 —— 系统级与主播级又矛盾了。
            val breakerAttribution = lastGlobalFailure
                ?: RoundFailureGate.GlobalFailureAttribution(
                    kind = RoundFailureGate.GlobalFailureKind.UNKNOWN,
                    dominantReason = null
                )
            val breakerFailure = CallFailure(
                reason = breakerAttribution.dominantReason ?: RoomFailureReason.NETWORK,
                apiMessage = "circuit_open：上一轮失败占比越线后熔断打开，本轮未发起请求"
            )
            streamers.forEach {
                applySafely(
                    it.stableId, it.uid,
                    RoomFailureMapping.observationResultOf(breakerFailure.reason), null, snapshot, lease,
                    failure = breakerFailure
                )
            }
            // 走与普通失败一致的路径，保持失败计数/盲区/问题通知的口径统一
            onGlobalFailure(snapshot, breakerAttribution)
            return MonitoringResult(true, "circuit_open", streamers.size, streamers.size)
        }

        val allStreamers = streamerDao.listMonitorable()
        publishCounts(allStreamers)

        // ---- 数据新鲜度快照补写（本次修复里"让存储列也诚实"的那一半）----
        // 存储列 `streamer.freshnessStatus` 原先**只在"某位主播的一次观察被应用"时**被写，
        // 于是没被观察到的主播（被封禁跳过的、以及本轮压根没跑到的）那一列会冻结在旧值上，
        // 而界面把"写入时刻的快照"当成了"此刻是否过期"—— 这就是用户报的 bug。
        // 界面侧已改成现算（FreshnessPolicy），这里再用**同一个纯函数**把列本身补正，
        // 免得库里长期留着一个与界面口径不一致的值（两个真相迟早会打架）。
        // 放在批次之前：本轮会被观察的主播紧接着会被 CAS 写入（值相同，稳态下 0 次额外写入），
        // 而真正需要这条补写的正是那些**不会**被观察到的人。
        refreshFreshnessSnapshots(allStreamers, snapshot)

        // ---- 「封禁中」主播的分流与低频复查（用户诉求 1 + 2）----
        //
        // 封禁状态**独立于** confirmedLiveStatus：它只影响调度与展示，
        // 不改状态、不产生通知、不碰历史场次（见 MonitorRepository.applyBanState 的说明）。
        // 到期的封禁主播走 `room_init` 复查（唯一能读出 is_locked 的接口）；
        // 未到期的**完全不进入本轮请求**，因此既不会拖慢其他主播，也不会浪费请求。
        val banned = mutableMapOf<String, com.example.bilimonitor.domain.policy.RoomBanState>()
        for (s in allStreamers) {
            com.example.bilimonitor.domain.policy.RoomBanCodec.decode(s.lastError)?.let { banned[s.stableId] = it }
        }
        // 到期的（含"确认次数已过"的边界）才复查；返回**复查后**的最新封禁集合。
        val bannedNow = refreshDueBanProbes(allStreamers, banned, snapshot, lease).toMutableSet()
        val streamers = allStreamers.filter { it.stableId !in bannedNow }
        if (bannedNow.isNotEmpty()) {
            // ★ 这是"本轮跳过了谁"这一**调度事实**，不是错误 —— 修复前它被记成
            //   `application_error_log` + `AppError.API_REJECTED`（"接口被拒绝"），每轮一条。
            //   两条后果（用户实测）：
            //    ① 语义撒谎：用户看到"每分钟一个接口拒绝"，会以为封禁主播仍在走常规检查、
            //       封禁功能没生效 —— 日志把排查方向直接带偏（真实情况一切正常）；
            //    ② 刷屏：每分钟 1 条 = 每天 1440 条；98 位主播的机器上，这类"一切正常"的提示
            //       会把**真正有用的错误**淹没。
            //   现在只写 logcat（统一 tag，无任何数据库写入）：需要时可观测，不需要时不占日志表。
            //   状态变化（进入/解除封禁）由 recordBanTransition 写审计表留痕，那一处才是值得留痕的事实。
            android.util.Log.i(
                TAG,
                "本轮跳过 ${bannedNow.size} 位封禁中主播（按 ${snapshot.banRecheckIntervalSeconds}s 复查一次），" +
                    "其余 ${streamers.size} 位照常检测；封禁明细见各主播行的 ROOM_BANNED 标记"
            )
        }
        // ★ 口径统一（缺陷 1 的另一半）：分母 = 本轮**参与检查**的主播数。
        //   `streamers` 就是这一批人（监控关闭 / 本轮封禁跳过的已在上面剔除），批次必然完整覆盖它。
        //   原实现在批次协程里 `checked += batch.size`（并发自增、非原子），失败数又另立一份
        //   局部累加变量 —— 两处一旦分叉，`MonitoringResult` 报出去的数字与内部判定用的集合
        //   就会各说各话。现在分母只有一个来源，分子只有一个来源（`RoundFailureLedger`）。
        val checked = streamers.size

        val batches = if (streamers.isEmpty()) emptyList() else streamers.chunked(snapshot.batchSize.coerceAtLeast(1))
        val uidsOfBatch = batches.map { batch -> batch.map { it.uid } }
        val stableIdsOfBatch = batches.map { batch -> batch.map { it.stableId }.toSet() }

        /*
         * ★ 用户诉求 3（本轮最重要的改动）：**只有大面积失败才触发重试与熔断**。
         *
         * 改成两段式：
         *   第一轮 —— 每个批次只请求**一次**（不重试、不计熔断）。它的唯一产物是"本轮谁失败"。
         *   第二轮 —— 仅当失败占比**严格超过** `retryFailureRatioPercent`（默认 50%）时，
         *             才对失败的批次按 `maxRetries` 重试、并按 `circuitBreakerThreshold` 计熔断。
         *
         * 为什么必须这样拆（原实现的 bug）：原先每个批次无条件重试、每次最终失败都计一次熔断，
         * 于是一个持续被服务端拒绝的房间会让它所在批次每轮都失败，连续 N 轮就把**全局**熔断顶开；
         * 熔断一开，整轮 Tick 直接 `circuit_open` 并把**所有**主播记成失败 —— 其他主播一并无法检测。
         * 现在熔断的**入口**被比例门禁卡住：少量失败（哪怕每轮都一样）在第一轮就地结束，
         * 既不重试也不计熔断，更不会波及别的主播。
         */
        val round = RoundFailures()
        /** 本 Tick 是否拿到过任何一次有效响应（用于把熔断计数清零）。 */
        var sawSuccess = false
        /** 二分隔离的额外请求预算（见 [RoundFailureGate.MAX_SPLIT_REQUESTS_PER_TICK]）。 */
        val splitBudget = SplitBudget()

        // 第一轮：每批只请求一次。状态提交与通知严格串行，只有网络请求并发
        // （0.6.15 的 CAS 提交契约不受影响）。
        forEachBatchWithCooldown(batches.size, snapshot) { index ->
            val batch = batches[index]
            val outcome = try {
                fetchOnce(batch.map { it.uid }, snapshot)
            } catch (e: Exception) {
                // 数据源内部已分类异常；这里兜住"未知异常"，但绝不再压成 null 丢掉原因。
                BatchStatusOutcome.Failed(
                    CallFailure(
                        reason = RoomFailureReason.MALFORMED_RESPONSE,
                        apiMessage = "未知异常：${e.message}".take(MEASURE_ERROR_DETAIL)
                    )
                )
            }
            sawSuccess = sawSuccess || outcome is BatchStatusOutcome.Responded
            // 失败主播与失败原因**同时**登记进账面 —— 这件事只在 [processOutcome] 里做一次
            // （它同时知道每个 uid 的结果与原因），Tick 里不再自己数一遍：
            // 局部累加变量与账面集合一旦分叉，末轮的比例判定就建立在错误的分子上（缺陷 1）。
            processOutcome(
                outcome = outcome,
                stableIds = batch.map { it.stableId },
                uids = uidsOfBatch[index],
                snapshot = snapshot,
                lease = lease,
                // 第一轮的拆分用**一次性请求**：拆分是"隔离坏房间"的手段，
                // 不能顺手把重试也带上，否则重试预算会被拆分悄悄放大。
                fetch = { uids -> fetchOnce(uids, snapshot) },
                splitBudget = splitBudget,
                round = round
            )
        }

        if (sawSuccess) recordRequestOutcome(success = true, snapshot)

        // ---- 比例门禁（用户可配，默认 50%）----
        // 分母 = 本轮参与检查的主播数；分子 = 最终未得到有效结果的主播数（同一主播只算 1 次）。
        // ★ 分子分母都只有**一个来源**：`checked` 与 `round.failedCount`（账面）。
        //   账面的"回收"发生在 [processOutcome] 里（重试拿到有效结果 → `resolve`），
        //   因此这里重算出来的 ratio 就是"末轮真相"，而不是"第一轮失败集合"（缺陷 1）。
        var ratio = RoundFailureRatio(failed = round.failedCount, total = checked)
        val gateOpen = RoundFailureGate.shouldRunRetryPass(
            ratio = ratio,
            thresholdPercent = snapshot.retryFailureRatioPercent,
            failureReasons = round.reasons
        )
        when {
            gateOpen && splitBudget.usedAny() -> {
                // 第一轮已经做过二分隔离：那些批次在本轮等同于"已经重问过一次"，
                // 再走一遍重试轮只会把请求量翻倍，因此不再进入第二轮。
                // ★ 错误码按本轮真实原因选：这条是**调度决策**的留痕，
                //   写死 NETWORK_UNAVAILABLE 会让"被服务端 429 限流"看起来像"网线掉了"（缺陷 2）。
                logAppError(
                    RoundFailureCode.appErrorOf(round.reasons),
                    "本轮失败占比 ${ratio.percent}%（${ratio.failed}/${ratio.total}）越线，" +
                        "但已执行过批次二分隔离，跳过二次重试以避免请求量翻倍"
                )
            }
            gateOpen -> {
                logAppError(
                    RoundFailureCode.appErrorOf(round.reasons),
                    "本轮失败占比 ${ratio.percent}%（${ratio.failed}/${ratio.total}）" +
                        "超过阈值 ${snapshot.retryFailureRatioPercent}%，进入重试轮"
                )
                forEachBatchWithCooldown(batches.size, snapshot) { index ->
                    val batch = batches[index]
                    val failedHere = batch.filter { it.stableId in round.failedStableIds }
                    if (failedHere.isEmpty()) return@forEachBatchWithCooldown
                    // ★ 预算不够再开新的一批就别开：宁可本轮少重试几批，也要把批次循环**之后**
                    //   的收尾跑完（系统级故障通知就在那里，而"不熔断"的语义下没有别的闸门）。
                    //   注意这不是静默跳过：没被重试到的主播仍留在 round 的失败账面上，
                    //   照常计入末轮比例，也会照常出现在失败日志与通知里。
                    if (remainingTickBudgetMs(deadlineElapsed, clock.nowElapsed()) <= TICK_TAIL_RESERVE_MS) {
                        return@forEachBatchWithCooldown
                    }
                    val outcome = try {
                        fetchEitherWithRetry(failedHere.map { it.uid }, snapshot, deadlineElapsed)
                    } catch (e: Exception) {
                        BatchStatusOutcome.Failed(
                            CallFailure(
                                reason = RoomFailureReason.MALFORMED_RESPONSE,
                                apiMessage = "未知异常：${e.message}".take(MEASURE_ERROR_DETAIL)
                            )
                        )
                    }
                    // 重试轮**不再拆分**：拆分只会放大请求量，且第一轮已经给过隔离机会。
                    // ★ 回收发生在这个函数内部：这一批里"重试后拿到有效结果"的主播会被
                    //   `round.resolve` 移出账面 —— 这正是缺陷 1 的修复点（原实现只加不减，
                    //   于是只要走进重试轮，末轮必然再次越线、必然误报全局降级）。
                    processOutcome(
                        outcome = outcome,
                        stableIds = failedHere.map { it.stableId },
                        uids = failedHere.map { it.uid },
                        snapshot = snapshot,
                        lease = lease,
                        fetch = { uids -> fetchOnce(uids, snapshot) },
                        splitBudget = SplitBudget(exhausted = true),
                        round = round
                    )
                    // 熔断提前收手：服务端明显不可用时不再把剩余批次打完。
                    // （没被重试到的失败主播留在账面上不动 —— 它们确实没拿到结果，如实计入。）
                    // （熔断总开关关闭时 [breakerOpen] 恒为 false ⇒ 这里不提前收手，
                    //   剩余的失败批次照常按 maxRetries 重试 —— 正是"不做熔断暂停"的语义。）
                    if (breakerOpen(snapshot)) return@forEachBatchWithCooldown
                }
                // 重试轮结束后**用同一份口径**重算：分子取账面（已被回收过），分母不变。
                ratio = RoundFailureRatio(failed = round.failedCount, total = checked)
            }
            round.failedStableIds.isNotEmpty() -> {
                // 少数主播失败：**不重试、不计熔断、不开系统盲区**，但必须留痕 ——
                // 否则"为什么这一位一直失败"在诊断包里又变成无来源的记录。
                //
                // ★ 错误码按**本轮真实出现过的失败原因**选（RoundFailureCode），
                //   不再无条件写死 API_REJECTED —— 修复前 detail 里写着"原因集合=[TIMEOUT]"、
                //   errorCode 却是"接口被拒绝"，用户按这个方向去查从第一步就是错的。
                //   detail 里仍然带上完整的原因集合，一个不丢。
                //
                // ★ 边沿抑制（同 BanLogEdgeGate 的做法）：这一行是**汇总**，
                //   而"某位主播持续失败"会让它每轮都成立 —— 每分钟一条会把真正有用的错误淹没。
                //   信号指纹 = 选出的错误码 + 原因集合（**不含人数**：人数在 1↔2 之间来回跳时
                //   指纹会跟着来回变，抑制就失效了；失败本身在各自的主播行里另有记录）。
                val failureCode = RoundFailureCode.appErrorOf(round.reasons)
                val signature = failureCode.name + "|" +
                    round.reasons.map { it.name }.sorted().joinToString(",")
                val detail = "本轮 ${ratio.failed}/${ratio.total} 位主播检查失败（占比 ${ratio.percent}%，" +
                    "未超过阈值 ${snapshot.retryFailureRatioPercent}%）：不触发重试与熔断，" +
                    "其余主播照常检测。原因集合=${round.reasons.joinToString(",")}" +
                    // 本轮出现过、但相关主播已经恢复的原因也照实写出来（否则"这轮到底出过什么"
                    // 会因为某人后来成功而丢失）。
                    if (round.observedReasons != round.reasons) {
                        "（本轮出现过的原因=${round.observedReasons.joinToString(",")}）"
                    } else {
                        ""
                    }
                if (roundFailureLogGate.shouldRecord(ROUND_FAILURE_LOG_KEY, signature)) {
                    logAppError(failureCode, detail)
                } else {
                    // 与上次同一信号（同一个码 + 同一组原因）：抑制落库，只留一行 logcat
                    // （需要现场排查时 `adb logcat -s MonitoringEngine` 仍看得到）。
                    android.util.Log.d(TAG, "本轮少数主播失败（与上次同一信号，已抑制重复落库）：$detail")
                }
            }
        }

        // 本轮无人失败 = 上一个"少数失败"的信号已经结束：清掉抑制器，
        // 下次再出问题（哪怕原因集合完全相同）仍然值得再记一条（边沿触发的"下降沿"）。
        if (round.isEmpty) roundFailureLogGate.clear(ROUND_FAILURE_LOG_KEY)

        // ---- Tick 汇总后的全局健康判定（缺陷 1 + 缺陷 2 的汇合点）----
        // 判据与重试门禁**同一个比例**（口径统一），且**按主导原因归因**：
        //  - 占比没越线 → 传输健康（关盲区、清计数）；
        //  - 占比越线且主导原因是真实传输故障 → 网络事故（SYSTEM 盲区 = NETWORK_UNAVAILABLE）；
        //  - 占比越线但主导原因是接口侧（限流 / 业务拒绝 / 响应不可用）→ **仍然降级**
        //    （盲区是真的，用户必须知道），但事故类型与文案如实写成接口侧，
        //    不再谎称"网络不可用"（修复前无论什么原因都走 onTransportFailure ⇒ 归因错误）。
        // 原实现还有第二个问题：判据是"只要还有失败批次就按系统故障处理" ——
        // 一个坏房间就能开出系统盲区并发"网络不可用"通知（用户报告的"其他主播一并无法检测"）。
        val globalFailure = RoundFailureGate.globalFailureCause(
            ratio = ratio,
            thresholdPercent = snapshot.retryFailureRatioPercent,
            reasonCounts = round.reasonCounts
        )
        if (batches.isEmpty()) {
            // 没有可监控主播：不做任何健康判定
        } else if (globalFailure != null) {
            onGlobalFailure(snapshot, globalFailure)
        } else {
            onTransportSuccess(snapshot)
        }

        runCatching { notificationRepository.processWindowEnds() }
        scheduleWindowEndFlush()
        // ★ 报出去的失败主播数与内部判定**同源**（账面），不再是另一份局部累加变量。
        return MonitoringResult(true, reason, checked, round.failedCount)
    }

    /**
     * 对**已到期**的封禁主播做一次 `room_init` 复查，返回复查后仍在封禁中的主播 stableId 集合。
     *
     * 三种结果（**判定与调度逐字未变**，改的只是"记什么、记到哪、什么频率"）：
     *  - `is_locked = false` → **立即解除**：清掉标记，下一轮就回到正常间隔（用户诉求 2），
     *    并写一条**审计**留痕（"解除封禁"是值得留痕的状态变化）；
     *  - `is_locked = true`  → 续期：复查时刻推到 now + `banRecheckIntervalSeconds`。
     *    **状态没有变化** → 不写任何库（只写一行 logcat）；
     *  - 探针拿不到结论（超时/风控/5xx/解析失败）→ **原样保持**（未知不得解除封禁），
     *    按**真实失败分类**记一条监控错误（边沿触发：同一信号不重复落库）。
     *
     * ## 每轮探针预算（[recheckProbeBudget]，与首次判定路径同样的 [MAX_BAN_PROBES_PER_TICK]）
     * 探针失败时 `nextCheckAtWall` 不被推迟（规则①：未知不得改写调度），因此失败的封禁主播
     * **下一轮还会到期**。若不给这条路径一个每轮上限，98 位全封禁且持续失败 =
     * **98 个 `room_init` 请求/分钟**（复查路径在本轮批次之前执行，请求量先于其他工作发生）——
     * 既雪上加霜，也会被服务端当成异常流量。
     *
     * 超预算的留痕：**只写 logcat**，不写错误日志。理由与 [noteBanProbeDeferred] 相同 ——
     * "自己设定的每轮额度用完了"不是外部故障，`AppError` 里没有一个取值能诚实表达它。
     * 留痕分两层：① 每位被推迟的主播一行（[noteBanProbeDeferred]，同一主播同一信号只写一次）；
     * ② 本轮一条汇总（Log.w，带上"多少位到期、多少位被推迟"），
     * 这样"预算是不是长期不够用"一眼能看出来，而不会每轮多出 98 条记录。
     */
    private suspend fun refreshDueBanProbes(
        all: List<com.example.bilimonitor.data.local.entity.StreamerEntity>,
        banned: Map<String, com.example.bilimonitor.domain.policy.RoomBanState>,
        snapshot: MonitoringConfigSnapshot,
        lease: MonitoringLease
    ): Set<String> {
        if (banned.isEmpty()) return emptySet()
        val now = clock.nowWall()
        val stillBanned = linkedSetOf<String>()
        var due = 0
        var deferred = 0
        for (streamer in all) {
            val ban = banned[streamer.stableId] ?: continue
            if (com.example.bilimonitor.domain.policy.RoomBanPolicy.shouldSkipRegularCheck(ban, now)) {
                stillBanned += streamer.stableId
                continue
            }
            due++
            val roomId = streamer.roomId ?: streamer.shortRoomId
            // 既没有 roomId 也没有 shortRoomId：探针根本发不出去，**不占用本轮预算**
            // （额度是"请求"的额度；这条路径与首次判定路径同一条取舍，见 probeBanIfWarranted）。
            if (roomId != null && !recheckProbeBudget.tryAcquire()) {
                // 预算用尽：本轮不探。**保持封禁标记**（未知不等于未封禁），复查时刻也不推迟，
                // 于是下一轮还会到期 —— 这正是需要每轮上限的原因（见本函数的 KDoc）。
                stillBanned += streamer.stableId
                deferred++
                noteBanProbeDeferred(streamer, roomId, path = "到期复查")
                continue
            }
            when (val outcome = probeBanSafely(roomId)) {
                is BanProbeOutcome.Failed -> {
                    // ★「问不出来」绝不等于「没被封禁」：不解除、不新增。
                    //   与原实现一致地把 previous 原样写回（nextState 规则①返回的就是它），
                    //   复查时刻**不被推迟** —— 判定与调度语义保持逐字不变。
                    stillBanned += streamer.stableId
                    monitorRepository.applyBanState(streamer.id, ban)
                    recordBanProbeFailure(
                        streamer, roomId, keptBanned = true, failure = outcome.failure, now = now
                    )
                }
                is BanProbeOutcome.Concluded -> {
                    val next = com.example.bilimonitor.domain.policy.RoomBanPolicy.nextState(
                        previous = ban,
                        probe = outcome.result,
                        nowWall = now,
                        recheckIntervalSeconds = snapshot.banRecheckIntervalSeconds
                    )
                    // 拿到结论 = 这条链路恢复正常：解除该主播的重复抑制记录，
                    // 下次真出问题（哪怕信号完全相同）仍然值得再记一条。
                    banLogGate.clear(streamer.stableId)
                    if (next != null) {
                        stillBanned += streamer.stableId
                        monitorRepository.applyBanState(streamer.id, next)
                        // 续期复查：状态未变 → 只写 logcat（修复前这里每轮一条 API_REJECTED）。
                        noteBanSteady(streamer, next, now)
                    } else {
                        // 解除封禁：清标记 + 审计留痕（不再占用错误日志）。
                        monitorRepository.applyBanState(streamer.id, null)
                        recordBanTransition(
                            streamer, BanTransition.LIFTED, next = null, now = now,
                            roomId = roomId, snapshot = snapshot
                        )
                    }
                }
            }
        }
        if (deferred > 0) {
            // 本轮汇总（每轮至多一条）：既让人看见"预算长期不够用"，又不至于每轮多出 98 条记录。
            android.util.Log.w(
                TAG,
                "封禁复查探针预算用尽（每轮上限 ${MAX_BAN_PROBES_PER_TICK} 次）：本轮 $due 位到期，" +
                    "其中 $deferred 位未复查，封禁判定与解封判定都推迟到下一轮" +
                    "（不是错误，故意不写错误日志；需要时可提高该上限或拉长复查间隔）"
            )
        }
        return stillBanned
    }

    /**
     * 把**数据新鲜度快照列**（`streamer.freshnessStatus`）按当前时间重算一遍。
     *
     * ## 修的是什么
     * 那一列原先只在"某位主播的一次观察被应用"时被写（`StreamerDao.casConfirmedObservation`），
     * 也就是**只有被观察到的**主播才会被更新。于是：
     *  - 被封禁跳过的主播（`shouldSkipRegularCheck`，可能连续几天不参与常规检查）；
     *  - 本轮没跑到的（引擎停跑、监控关闭、租约丢失、Tick 超预算早退）
     * 这些人的列会一直冻结在最后一次写入的值上 —— 而界面把它当成了"此刻是否过期"，
     * 于是「数据过期」永不出现、「N 分钟前」一直涨（用户报的 bug）。
     *
     * 这里只做**补写**：界面已改成现算（`FreshnessPolicy.isDataExpired`），
     * 但库里长期留着一个与界面口径不一致的值迟早会误导人（诊断包、备份、日后新增的读法），
     * 所以用**同一个纯函数**把列本身也修正。
     *
     * ## 三条实现约束
     *  ① 只用独立 UPDATE 写 `freshnessStatus` 一列（`StreamerDao.updateFreshnessSnapshot`），
     *     **绝不碰** `observationSequence` / 代际 / fencing / `lastCheckedAt` —— 那些属于 CAS 状态机；
     *  ② 阈值取**有效阈值**（`max(配置阈值, 2 × 实际周期)`），与界面现算用的是同一个函数，
     *     否则省电模式下这一列会一直写 STALE，而界面说"没过期"（两个真相）；
     *  ③ 值没变就不写（DAO 里还有 `freshnessStatus <> :freshness` 兜底）：
     *     稳态下每位主播都在被正常检查，这一趟是 0 次实际写入，不会白白消耗 WAL 与电量。
     *
     * 失败处理：整趟只记一条错误日志（不是每位主播一条）——写库失败的成因通常是全局的
     * （磁盘/数据库），98 条同因记录只会把错误日志刷满。
     */
    private suspend fun refreshFreshnessSnapshots(
        all: List<com.example.bilimonitor.data.local.entity.StreamerEntity>,
        snapshot: MonitoringConfigSnapshot
    ) {
        try {
            val now = clock.nowWall()
            val threshold = FreshnessPolicy.effectiveThresholdSeconds(
                thresholdSeconds = snapshot.freshnessStaleSeconds,
                tickPeriodSeconds = FreshnessPolicy.tickPeriodSeconds(
                    snapshot.mode, snapshot.intervalSeconds
                )
            )
            var written = 0
            for (streamer in all) {
                val computed = FreshnessPolicy.freshnessOf(streamer.lastConfirmedAt, now, threshold)
                if (computed == streamer.freshnessStatus) continue
                written += streamerDao.updateFreshnessSnapshot(streamer.id, computed)
            }
            if (written > 0) {
                android.util.Log.d(
                    TAG,
                    "数据新鲜度快照补写：本轮更新 $written 位主播（阈值 ${threshold}s，" +
                        "其中封禁中被跳过的也会如实老化为 STALE —— " +
                        "「封禁中不标过期」是展示规则，存储列只记录事实）"
                )
            }
        } catch (e: Exception) {
            logAppError(AppError.DATABASE_ERROR, "数据新鲜度快照补写失败：${e.message}")
        }
    }

    /**
     * 常规检查失败后的封禁探针（**首次判定**入口，用户要求"不要每次都多打一次请求"）。
     *
     * 触发条件（两选一，见 [com.example.bilimonitor.domain.policy.RoomBanPolicy.shouldProbe]）：
     *  - 该主播**本轮检查失败** → 探一次 `room_init`。正常情况下零额外请求；
     *    即使整轮全失败，也受每轮探针预算限制。
     *  - 该主播**响应里自带明确的封禁标记** → **零请求**直接判定（批量接口文档里就有
     *    `is_locked` / `lock_till`，有就白拿）。
     *  - 该主播**本轮成功且没有任何封禁标记** → 若之前有封禁标记则顺带解除（不花请求）。
     *
     * 拿不到结论（超时/风控/5xx/解析失败）时**什么都不做** —— 不解除、不新增，
     * 这正是"绝不把风控/网络异常判成封禁、也绝不据此解除封禁"的落地处。
     */
    private suspend fun probeBanIfWarranted(
        streamer: com.example.bilimonitor.data.local.entity.StreamerEntity,
        observationResult: ObservationResult,
        remote: RemoteLiveRoom?,
        snapshot: MonitoringConfigSnapshot,
        lease: MonitoringLease
    ): Boolean {
        val now = clock.nowWall()
        val previous = com.example.bilimonitor.domain.policy.RoomBanCodec.decode(streamer.lastError)
        // ① 零请求判定：批量响应自带封禁标记（`is_locked` / 未过期的 `lock_till`）。
        val flag = com.example.bilimonitor.domain.policy.RoomBanPolicy.lockFromBatchFlags(
            isLocked = remote?.banLocked,
            lockTillMillis = remote?.banLockTillMillis,
            nowWall = now
        )
        if (flag == true) {
            return persistBan(
                streamer, previous,
                com.example.bilimonitor.domain.policy.RoomBanProbeResult(
                    isLocked = true,
                    lockTill = remote?.banLockTillMillis,
                    isHidden = remote?.hidden,
                    apiCode = null,
                    apiMessage = "batch_flags"
                ),
                snapshot, now
            )
        }
        // ② 明确未封禁且此前也没有封禁标记 → 无需任何动作。
        if (flag == false && previous == null && observationResult == ObservationResult.SUCCESS) return false
        // ③ 探针门槛：本轮失败才探（正常成功且无标记的主播不花这个请求）。
        val shouldProbe = com.example.bilimonitor.domain.policy.RoomBanPolicy.shouldProbe(
            ban = previous,
            suspicionRaised = observationResult != ObservationResult.SUCCESS,
            nowWall = now
        )
        if (!shouldProbe) {
            // 成功观察 + 之前有封禁标记 + 探针未到期：不擅自解除（避免在一轮网络抖动里
            // 把封禁标记抹掉），交给到期复查去判定。
            return false
        }
        val roomId = streamer.roomId ?: streamer.shortRoomId ?: return false
        if (!probeBudget.tryAcquire()) {
            // 预算用尽不是错误：这是本项目自己设定、写在常量里的每轮请求上限在按设计生效
            // （见 MAX_BAN_PROBES_PER_TICK），而且**并不静默** —— 这位主播本轮本来就因为
            // 观察失败在 monitoring_error_log 里留下了一条记录（常规失败路径），
            // 这里被推迟的只是"封禁判定"这一步。因此只写 logcat，不写错误日志。
            noteBanProbeDeferred(streamer, roomId, path = "首次判定")
            return false
        }
        when (val outcome = probeBanSafely(roomId)) {
            is BanProbeOutcome.Failed -> {
                // 「问不出来」绝不等于「没被封禁」：不解除、不新增，按**真实失败分类**留痕。
                recordBanProbeFailure(
                    streamer, roomId, keptBanned = previous != null, failure = outcome.failure, now = now
                )
                return false
            }
            is BanProbeOutcome.Concluded -> {
                val probe = outcome.result
                if (!probe.isLocked && previous == null) return false
                return persistBan(streamer, previous, probe, snapshot, now)
            }
        }
    }

    /** 写入封禁状态（进入/续期/解除），并按"是否真的发生了变化"决定记什么、记到哪。 */
    private suspend fun persistBan(
        streamer: com.example.bilimonitor.data.local.entity.StreamerEntity,
        previous: com.example.bilimonitor.domain.policy.RoomBanState?,
        probe: com.example.bilimonitor.domain.policy.RoomBanProbeResult,
        snapshot: MonitoringConfigSnapshot,
        now: Long
    ): Boolean {
        val next = com.example.bilimonitor.domain.policy.RoomBanPolicy.nextState(
            previous = previous,
            probe = probe,
            nowWall = now,
            recheckIntervalSeconds = snapshot.banRecheckIntervalSeconds
        )
        val roomId = streamer.roomId ?: streamer.shortRoomId
        if (next == null) {
            if (previous == null) return false
            monitorRepository.applyBanState(streamer.id, null)
            banLogGate.clear(streamer.stableId)
            // 解除封禁 = 状态变化 → 审计留痕（不再是 API_REJECTED 的错误记录）。
            recordBanTransition(
                streamer, BanTransition.LIFTED, next = null, now = now, roomId = roomId, snapshot = snapshot
            )
            return false
        }
        monitorRepository.applyBanState(streamer.id, next)
        // 拿到结论 = 这条链路恢复正常：解除重复抑制，下次真出问题仍然值得再记一条。
        banLogGate.clear(streamer.stableId)
        when (BanLogPolicy.transitionOf(previous, next)) {
            // 首次判定为封禁 → 值得留痕的状态变化。
            BanTransition.ENTERED -> recordBanTransition(
                streamer, BanTransition.ENTERED, next = next, now = now, roomId = roomId, snapshot = snapshot
            )
            // BanTransition.NONE（已有标记、这次只是再次确认）与 LIFTED（上面已处理）：
            // 都不是变化 → 不写库，只写一行 logcat。
            else -> noteBanSteady(streamer, next, now)
        }
        return true
    }

    /**
     * 探针调用 + 契约兜底。
     *
     * [BiliLiveDataSource.probeBan] 的契约是**绝不抛异常**（失败一律走 [BanProbeOutcome.Failed]），
     * 所以这里的 catch 只可能被"实现违约"触发。既然如此就如实归类（解析/未知异常），
     * 而不是像原来那样无论什么原因都记成 `API_REJECTED`（"接口被拒绝"）。
     */
    private suspend fun probeBanSafely(roomId: Long?): BanProbeOutcome {
        if (roomId == null) {
            // 既没有 roomId 也没有 shortRoomId：探针根本发不出去。
            // 这是"响应不可用"（缺少必要条件），不是"接口被拒绝"，也不是"未被封禁"。
            return BanProbeOutcome.Failed(
                CallFailure(
                    reason = RoomFailureReason.MISSING_IN_RESPONSE,
                    apiMessage = "该主播既没有 roomId 也没有 shortRoomId，无法发起封禁探针"
                )
            )
        }
        return try {
            dataSource.probeBan(roomId)
        } catch (e: Exception) {
            BanProbeOutcome.Failed(
                CallFailure(
                    reason = RoomFailureReason.MALFORMED_RESPONSE,
                    apiMessage = "探针抛出异常（数据源契约上不应发生）：${e.message}"
                        .take(MEASURE_ERROR_DETAIL)
                )
            )
        }
    }

    /**
     * 封禁**状态变化**时的留痕（进入 / 解除）—— 写 `audit_log`，**不进错误日志**。
     *
     * ## 为什么不能记成 API_REJECTED（缺陷 1 的核心）
     * "进入封禁"是服务端在**正常响应**里明确给出的业务状态（`is_locked = true`），
     * "解除封禁"更是监控恢复正常 —— 这两件事都**没有出错**。
     * 而 `AppError` 里没有任何一个取值表示"主播不可用 / 被封禁"；硬套 `API_REJECTED`
     * （"接口被拒绝"）会让用户以为封禁主播仍在走常规检查、封禁功能没生效，
     * 把排查方向直接带偏 —— 这正是要修的误导。
     * 项目自己的取舍记录写着同一条道理：[RoomFailureMapping.appErrorOf] 连 `RATE_LIMITED`
     * 都因为"没有合适的错误码、新增取值会牵动 DDL CHECK 与迁移"而按"网络不可用"记录，
     * 何况这里根本**没有出错**。
     *
     * ## 为什么是 audit_log
     *  - 它正是"**发生过什么**"的归属地（actor=SYSTEM，与 MonitorRepository 写
     *    SESSION_END_TRUNCATED 同一个取舍），而错误日志只该留"需要排查的故障"；
     *  - 能归因到具体主播（targetStableId），原始信号进 detailJson（is_locked / lock_till /
     *    api_code / message），事后可自证判定是否正确；
     *  - 不参与错误码统计，也不会在诊断页的"软件运行错误日志"里冒充错误。
     *
     * 已知边界（如实记录，留给父代理决策）：`DiagnosticExporter` 目前**不导出** `audit_log`，
     * 因此这条留痕不出现在诊断包里。但状态变化并非静默：封禁状态本身在首页/详情页显示为
     * 「封禁中」、诊断页显示"封禁中 N 位"，`streamer.lastError` 里的 `ROOM_BANNED` 标记
     * 会随**备份**整行导出（备份导出的是 streamer 整行）。若要让审计留痕也进诊断包，
     * 需要单独改 DiagnosticExporter（注意 `DiagnosticExporterTest` 的 entryCount 断言已过时：
     * 断言 6，实际产出 7）。
     */
    private suspend fun recordBanTransition(
        streamer: com.example.bilimonitor.data.local.entity.StreamerEntity,
        transition: BanTransition,
        next: com.example.bilimonitor.domain.policy.RoomBanState?,
        now: Long,
        roomId: Long?,
        snapshot: MonitoringConfigSnapshot
    ) {
        val action = when (transition) {
            BanTransition.ENTERED -> "ROOM_BAN_ENTERED"
            BanTransition.LIFTED -> "ROOM_BAN_LIFTED"
            BanTransition.NONE -> return
        }
        val detail = if (next != null) {
            com.example.bilimonitor.domain.policy.RoomBanCodec.evidence(next, now) +
                " next_recheck_in=${snapshot.banRecheckIntervalSeconds}s"
        } else {
            "probe=room_init room_id=${roomId ?: "null"} is_locked=false" +
                "（恢复正常检查间隔 ${snapshot.intervalSeconds}s）"
        }
        android.util.Log.i(TAG, "封禁状态变化 ${action}：stable=${streamer.stableId.takeLast(8)} $detail")
        runCatching {
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "SYSTEM",
                    action = action, targetType = "streamer",
                    targetStableId = streamer.stableId, occurredAt = now,
                    detailJson = detail.take(LOG_DETAIL_MAX_CHARS)
                )
            )
        }.onFailure {
            // 留痕写入失败必须自己留痕，否则"状态变化"就真的静默了。
            // 这里用 DATABASE_ERROR 是诚实的：失败原因就是写库失败。
            logAppError(AppError.DATABASE_ERROR, "封禁状态留痕写入失败（${action}）：${it.message}")
        }
        // ★ 通知用户（2026 复查新增）：封禁/解封原先**只写审计表**，用户不看应用就完全不知道 ——
        //   而封禁中的主播每 30 分钟才探一次，可能几天都发现不了。只在状态变化时发一条
        //   （本函数只在 ENTERED/LIFTED 被调用；每轮复查的"仍封禁"走 noteBanSteady，只进 logcat）。
        //   刻意**不受免打扰抑制**：这不是"主播开播了"那类可以等到天亮的打扰，而是
        //   "你监控的对象出事/恢复了"的状态事实，且频率极低（进入/解除各一条）。
        runCatching {
            val entered = transition == BanTransition.ENTERED
            val label = next?.let {
                com.example.bilimonitor.domain.policy.RoomBanPolicy.lockTillLabel(it.lockTill, now)
            } ?: "已恢复正常检查间隔"
            com.example.bilimonitor.data.repository.NotificationOutboxWriter.createForChange(
                db = dbHandle,
                streamerId = streamer.id,
                streamerStableId = streamer.stableId,
                // 封禁不是场次事件：没有 sessionStableId（eventKey 里落成 "-"）
                sessionStableId = null,
                eventType = if (entered) {
                    com.example.bilimonitor.data.local.NotificationEventType.BAN_ENTERED
                } else {
                    com.example.bilimonitor.data.local.NotificationEventType.BAN_LIFTED
                },
                // changeKey 里带本次转换时刻：解封后**再次**被封（哪怕同为无期限）也必须能发出去，
                // 只用 lockTill 做键的话第二次会被 eventKey 幂等检查拦掉。
                changeKey = (if (entered) "entered@" else "lifted@") + now,
                payloadJson = com.example.bilimonitor.data.repository.NotificationPayloads.encodeBan(
                    com.example.bilimonitor.data.repository.BanNotificationPayload(
                        streamerName = streamer.name,
                        banLabel = label,
                        entered = entered
                    )
                ),
                now = now,
                configVersion = snapshot.configVersion
            )
        }.onFailure {
            // 通知写不进去也必须留痕：否则"用户没收到封禁通知"这件事在库里查不到原因。
            logAppError(AppError.DATABASE_ERROR, "封禁通知写入失败（${action}）：${it.message}")
        }
    }

    /**
     * 复查再次确认"仍在封禁中"（**状态未变**）时的可观测性：**只写 logcat**。
     *
     * 为什么不落库：这不是新事实，只是同一事实的再次确认（默认每 30 分钟一次）；
     * 把它记进错误日志就是把"一切正常"写进错误表 —— 与本次要修的刷屏同型。
     * 需要看细节时按统一 tag 抓 logcat 即可。
     */
    private fun noteBanSteady(
        streamer: com.example.bilimonitor.data.local.entity.StreamerEntity,
        state: com.example.bilimonitor.domain.policy.RoomBanState,
        now: Long
    ) {
        android.util.Log.d(
            TAG,
            "封禁复查仍为封禁中（状态未变，不重复记录）：stable=${streamer.stableId.takeLast(8)}" +
                " next_recheck_in=${(state.nextCheckAtWall - now).coerceAtLeast(0L) / 1000}s"
        )
    }

    /**
     * 探针预算用尽（[MAX_BAN_PROBES_PER_TICK]）时的可观测性：**只写 logcat**（同一主播不重复刷）。
     *
     * 为什么不算错误：这是本项目**自己设定**的每轮请求上限在按设计生效，不是外部故障；
     * `AppError` 里 13 个取值没有一个能诚实表达"本轮额度用完"，
     * 硬套一个（原来是 `API_REJECTED`）就是撒谎。
     *
     * @param path 哪条路径被推迟（"首次判定" / "到期复查"）—— 两条路径各有独立的每轮额度，
     *        抑制指纹必须把路径算进去，否则同一主播在两条路径上交替撞额度时只会留一条记录。
     */
    private fun noteBanProbeDeferred(
        streamer: com.example.bilimonitor.data.local.entity.StreamerEntity,
        roomId: Long,
        path: String
    ) {
        if (!banLogGate.shouldRecord(streamer.stableId, "PROBE_BUDGET_EXHAUSTED|$path")) return
        android.util.Log.w(
            TAG,
            "封禁探针预算已用尽（每轮上限 ${MAX_BAN_PROBES_PER_TICK} 次，路径=$path）：room_id=$roomId" +
                " 本轮未探，封禁判定推迟到下一轮（不是错误，故意不写错误日志）"
        )
    }

    /**
     * 探针**真正失败**（超时 / 网络 / 限流风控 / 5xx / 服务端明确拒绝 / 响应不可用 / 解析失败）
     * 时的留痕 —— 这才是错误，而且用**真实分类**。
     *
     * ## 错误码：全项目唯一的那张映射表
     * `errorCode = RoomFailureMapping.appErrorOf(failure.reason)`：
     * 超时 → `NETWORK_TIMEOUT`、网络/限流/5xx → `NETWORK_UNAVAILABLE`、
     * **只有服务端明确拒绝**才是 `API_REJECTED`、解析失败 → `API_INVALID_RESPONSE`。
     * 修复前无论哪种原因都写 `API_REJECTED`：用户按"接口被拒绝"去查，
     * 从第一步就错了（真实原因往往只是一次超时）。
     * 原始信号（reason/http/api_code/retry_after/message）照旧进 detail，
     * 复用 `MonitorRepository.describeFailure` 的既有格式，可用同一套脚本统计。
     *
     * ## 为什么落 monitoring_error_log 而不是 application_error_log
     * 这条事实的主体是**某位主播**这次没查成：表里有 `streamerStableId`（能归到人），
     * 诊断页的「监控错误日志」与诊断包都会带上它；
     * 而应用级错误日志留给"软件本身出问题"（配置缺失 / Tick 超预算 / 写库失败…）。
     *
     * ## 为什么必须**边沿触发**
     * 探针失败不推进 `nextCheckAtWall`（未知不得改写调度），因此同一位主播**每轮**都会再试一次；
     * 每次都落库就是"每分钟一条"的老问题换个马甲。[banLogGate] 保证同一信号只记一条。
     */
    private suspend fun recordBanProbeFailure(
        streamer: com.example.bilimonitor.data.local.entity.StreamerEntity,
        roomId: Long?,
        keptBanned: Boolean,
        failure: CallFailure,
        now: Long
    ) {
        val signature = "${failure.reason.name}|${failure.httpStatus}|${failure.apiCode}|${failure.apiMessage}"
        if (!banLogGate.shouldRecord(streamer.stableId, signature)) {
            android.util.Log.d(
                TAG,
                "封禁探针再次失败（与上次同一信号，已抑制重复落库）：" +
                    "stable=${streamer.stableId.takeLast(8)} " +
                    MonitorRepository.describeFailure(failure).orEmpty()
            )
            return
        }
        val detail = buildString {
            append("封禁探针未取得结论：probe=room_init room_id=").append(roomId ?: "null").append(' ')
            append(MonitorRepository.describeFailure(failure).orEmpty())
            append(
                if (keptBanned) {
                    "；该主播的封禁标记保持不变（未知不得解除封禁，复查时刻不推迟）"
                } else {
                    "；本次未判定为封禁（未知不等于未封禁）"
                }
            )
        }
        android.util.Log.w(TAG, "封禁探针失败：stable=${streamer.stableId.takeLast(8)} $detail")
        runCatching {
            logDao.insertMonitoringError(
                MonitoringErrorLogEntity(
                    errorId = Ids.newId(),
                    problemKey = Ids.problemKey("MONITORING", "UNKNOWN", streamer.stableId, null),
                    streamerStableId = streamer.stableId,
                    observationSequence = streamer.observationSequence,
                    occurredAt = now,
                    errorCode = RoomFailureMapping.appErrorOf(failure.reason),
                    detail = detail.take(LOG_DETAIL_MAX_CHARS)
                )
            )
        }.onFailure {
            logAppError(AppError.DATABASE_ERROR, "封禁探针失败留痕写入失败：${it.message}")
        }
    }

    /**
     * 封禁探针的**每轮预算** —— 首次判定路径（常规检查失败后的探针）。
     *
     * 为什么必须有：探针是"每主播一次"的请求（批量接口不返回 is_locked），
     * 若整轮 98 位主播全失败（例如断网），无预算限制就会在断网时再打 98 个请求 ——
     * 既是雪上加霜，也会被服务端当成异常流量。8 次/轮 ≈ 8% 的额外流量上限。
     *
     * ★ 额度在 [runTick] 开头由 [ProbeBudget.reset] 补满：它原先**从不重置**，
     *   于是"每轮 8 次"实际上退化成了"本进程一共 8 次"—— 8 次用完之后封禁探针
     *   在进程剩余的生命周期里再也不发，既发现不了新封禁，也复查不了解封。
     */
    private val probeBudget = ProbeBudget(MAX_BAN_PROBES_PER_TICK)

    /**
     * 封禁探针的每轮预算 —— **到期复查路径**（[refreshDueBanProbes]），额度同为
     * [MAX_BAN_PROBES_PER_TICK]。
     *
     * 修复前这条路径**没有任何上限**：探针失败不推迟 `nextCheckAtWall`（未知不得改写调度），
     * 于是 98 位全封禁且持续失败 = 98 个 `room_init` 请求/分钟。
     *
     * ★ 为什么与首次判定路径**各用一个**、而不是共用同一个 8 次：复查路径在本轮批次**之前**
     *   执行（封禁分流），共用额度会让"98 位全封禁"的场景把额度全部吃在复查上，
     *   首次判定路径（= 新封禁的唯一发现入口）永远探不到 —— 那是把一个 bug 换成另一个功能缺失。
     *   两个独立额度 ⇒ 每轮封禁探针总数上限 = 16 次，仍然是"每轮有界"。
     */
    private val recheckProbeBudget = ProbeBudget(MAX_BAN_PROBES_PER_TICK)

    /**
     * 封禁链路日志的**边沿抑制器**（详见 [BanLogEdgeGate] 的说明）。
     *
     * 只在 Tick 互斥锁内被访问（[checkOnce] 的 tickMutex），本来没有并发问题；
     * 内部仍用 ConcurrentHashMap，避免日后有人把某处调用挪到锁外时踩坑。
     */
    private val banLogGate = BanLogEdgeGate()

    /**
     * 「本轮少数主播失败」**汇总日志**的边沿抑制器。
     *
     * 与 [banLogGate] 同一个类、同一个理由：一位主播持续失败就会让那条汇总每轮都成立，
     * 每轮落一条库 = 把"一切照旧"写进错误表。key 是固定常量（这条日志一轮至多一条），
     * 信号指纹 = 选出的错误码 + 原因集合；本轮无人失败时 [runTick] 会 [BanLogEdgeGate.clear] 它。
     */
    private val roundFailureLogGate = BanLogEdgeGate()

    /** 每轮 Tick 允许的封禁探针次数上限（用户要求"不要每次都多打一次请求"的量化约束）。 */
    private class ProbeBudget(private val limit: Int) {
        private var remaining = limit

        /**
         * 每轮 Tick 开头把额度补满。
         *
         * 没有它，"每轮 8 次"会退化成"每个进程 8 次"（[probeBudget] 的注释里有完整说明）。
         * 只在 Tick 互斥锁内调用，因此不需要额外同步。
         */
        fun reset() {
            remaining = limit
        }

        fun tryAcquire(): Boolean = if (remaining <= 0) false else {
            remaining--
            true
        }
    }

    /**
     * 逐批请求 + 批次间冷却的**唯一实现**（两轮请求共用，避免两处各写一份冷却逻辑而分叉）。
     *
     * 冷却按"距上一次请求**开始**至少 cooldown"计时：所有批次几乎同时启动，
     * 若按"上次结束时间"计算，后来者此刻读到的还是旧值（0），于是不等待、直接阻塞在并发闸上，
     * 上一批一结束就立刻发请求 —— 冷却等于没生效。闸门内预约下一次允许开始的时刻，
     * 先后顺序由互斥锁串行化，与请求耗时无关。
     */
    private suspend fun forEachBatchWithCooldown(
        batchCount: Int,
        snapshot: com.example.bilimonitor.domain.model.MonitoringConfigSnapshot,
        body: suspend (Int) -> Unit
    ) {
        val cooldownMs = snapshot.batchCooldownSeconds.coerceAtLeast(0) * 1000L
        // maxConcurrency 此前只落库、从不读取（引擎是串行 for 循环）。
        val parallelism = snapshot.maxConcurrency.coerceIn(1, 16)
        val gate = kotlinx.coroutines.sync.Semaphore(parallelism)
        var nextAllowedStartAt = 0L
        val cooldownGate = kotlinx.coroutines.sync.Mutex()
        kotlinx.coroutines.coroutineScope {
            val jobs = (0 until batchCount).map { index ->
                async {
                    if (cooldownMs > 0) {
                        cooldownGate.withLock {
                            val nowElapsed = clock.nowElapsed()
                            val wait = nextAllowedStartAt - nowElapsed
                            if (wait > 0) delay(wait)
                            nextAllowedStartAt = clock.nowElapsed() + cooldownMs
                        }
                    }
                    gate.acquire()
                    try {
                        body(index)
                    } finally {
                        // 只把**请求本身**放在并发闸内，避免批次持着许可睡觉。
                        gate.release()
                    }
                }
            }
            jobs.forEach { it.await() }
        }
    }

    /**
     * 一轮 Tick 里"哪些主播最终没拿到有效结果"的汇总 —— 账面 [RoundFailureLedger] 的**加锁壳**。
     *
     * 判定逻辑（含"回收"）全部在 [RoundFailureLedger] 里：那是纯数据类，可以 JVM 单测，
     * 而"重试成功后必须回收"这条不变式正是本次修复的核心（原实现只加不减 ⇒ 必然误报）。
     *
     * ★ 为什么加锁责任放在这里：批次请求是**并发**的（`maxConcurrency` 个协程），
     * 而 `linkedMapOf` 不是线程安全的 —— 并发写入可能丢元素，甚至把内部链表写坏。
     * 加锁只在这一处，避免"写入口分散、某一条路径忘了加锁"。
     * 读取（Tick 汇总、比例门禁）发生在所有批次 `await` 之后，不持锁也不会看到中间态。
     */
    private class RoundFailures {
        private val mutex = Mutex()
        private val ledger = RoundFailureLedger()

        val failedStableIds: Set<String> get() = ledger.failedStableIds
        val failedCount: Int get() = ledger.failedCount
        val isEmpty: Boolean get() = ledger.isEmpty
        val reasons: Set<RoomFailureReason> get() = ledger.reasons
        val observedReasons: Set<RoomFailureReason> get() = ledger.observedReasons
        val reasonCounts: Map<RoomFailureReason, Int> get() = ledger.reasonCounts

        /** 记一次**无法归属到具体主播**的批次级失败信号（只用于诊断，不参与归因）。 */
        suspend fun observe(reason: RoomFailureReason) = mutex.withLock { ledger.observe(reason) }

        /** 记一次失败并归到这些主播名下（重试会**覆盖**同一主播的旧原因）。 */
        suspend fun record(reason: RoomFailureReason, stableIds: Collection<String>) =
            mutex.withLock { ledger.record(reason, stableIds) }

        /** 记一次"确实失败、但上游没有给出原因"：计入分子，不编造原因。 */
        suspend fun recordUnknownReason(stableIds: Collection<String>) =
            mutex.withLock { ledger.recordUnknownReason(stableIds) }

        /** 回收：这些主播已经拿到有效结果（重试/二分隔离成功），从账面移除。 */
        suspend fun resolve(stableIds: Collection<String>) =
            mutex.withLock { ledger.resolve(stableIds) }
    }

    /** 二分隔离的额外请求预算（互斥保证并发批次不会把预算用超）。 */
    private class SplitBudget(private val exhausted: Boolean = false) {
        private val mutex = Mutex()
        private var remaining = if (exhausted) 0 else RoundFailureGate.MAX_SPLIT_REQUESTS_PER_TICK
        private var used = 0

        /** 返回 true 表示本次拆分已获授权（预算已扣）。 */
        suspend fun tryAcquire(): Boolean = mutex.withLock {
            if (remaining <= 0) false else {
                remaining--
                used++
                true
            }
        }

        /** 本轮是否用过任何一次拆分（用于决定跳过二次重试）。 */
        fun usedAny(): Boolean = used > 0
    }

    /**
     * 处理一个批次的请求结果：写观察、并在**逻辑拒绝**时做一次二分隔离
     * （把"一个坏房间拖累同批正常主播"降到最小）。
     *
     * ★ 本函数是账面 [RoundFailures] 的**唯一写入口**（失败、原因、回收三件事都在这里发生）：
     *   - 失败 → `record(reason, stableIds)`：分子与归因描述同一批人；
     *   - 成功 → `resolve(stableIds)`：**回收**，让末轮的比例与"此刻还剩谁没拿到结果"一致；
     *   - 批次级信号（整批被拒、子批重问失败）→ `observe(reason)`：只留痕，不参与归因。
     *   调用方（Tick）因此不再需要自己维护任何失败计数 —— 那是缺陷 1 的另一半
     *   （局部计数与内部集合两个真相）。
     *
     * @param stableIds 与 [uids]**同序**的主播 stableId 列表。
     */
    private suspend fun processOutcome(
        outcome: BatchStatusOutcome,
        stableIds: List<String>,
        uids: List<Long>,
        snapshot: com.example.bilimonitor.domain.model.MonitoringConfigSnapshot,
        lease: MonitoringLease,
        fetch: suspend (List<Long>) -> BatchStatusOutcome,
        splitBudget: SplitBudget,
        round: RoundFailures
    ) {
        when (outcome) {
            is BatchStatusOutcome.Failed -> {
                val failure = outcome.failure
                val split = if (RoundFailureGate.shouldSplitBatch(failure, uids.size) && splitBudget.tryAcquire()) {
                    // 显式传三个实参：`onFailure` 是 suspend 函数类型且有默认参数（depth），
                    // 尾随 lambda 在这个签名上会让 Kotlin 的实参映射变得不明确，直接写清楚。
                    // 子批的失败是**批次级**信号：只进"本轮出现过"的账，不归到具体主播名下
                    // （每一位失败主播的原因在下面按 uid 精确登记）。
                    splitBatch(uids, fetch, { recorded -> round.observe(recorded.reason) })
                } else {
                    null
                }
                if (split != null) {
                    // 隔离子批：拿到有效结果的立刻写观察并**从账面回收**（大主播批里有坏房间时，
                    // 同批的正常主播就是这样被救回来的）；剩下的按各自 uid 的真实原因记账。
                    for ((uid, remote) in split.responded) {
                        val stableId = stableIdFor(uid, stableIds, uids)
                        applySafely(stableId, uid, ObservationResult.SUCCESS, remote, snapshot, lease)
                        round.resolve(listOf(stableId))
                    }
                    for ((uid, perUidFailure) in split.failed) {
                        val stableId = stableIdFor(uid, stableIds, uids)
                        // 用**该 uid 自己**的失败信号，而不是整批第一次请求的那个原因：
                        // 子批重问时它可能已经变成超时/解析失败，那才是这个主播此刻的事实
                        // （主播级标签、detail 与归因因此共用同一个原因）。
                        applySafely(
                            stableId, uid,
                            RoomFailureMapping.observationResultOf(perUidFailure.reason), null,
                            snapshot, lease, perUidFailure
                        )
                        round.record(perUidFailure.reason, listOf(stableId))
                    }
                    return
                }
                // 不拆分：整批按同一个结构化原因记录（限流/超时/5xx/明确拒绝在 detail 里可区分）。
                // 原因与失败主播**同时**登记：归因（主导原因）与分子必须描述同一批人。
                round.record(failure.reason, stableIds)
                for ((index, stableId) in stableIds.withIndex()) {
                    applySafely(
                        stableId, uids.getOrNull(index) ?: continue,
                        RoomFailureMapping.observationResultOf(failure.reason), null,
                        snapshot, lease, failure
                    )
                }
                return
            }

            is BatchStatusOutcome.Responded -> {
                val response = outcome.response
                // 整批被服务端拒绝（code != 0）：与"网络失败"是不同事实，必须分开记。
                findCallFailure(response)?.let { round.observe(it.reason) }
                for ((index, stableId) in stableIds.withIndex()) {
                    val uid = uids.getOrNull(index) ?: continue
                    val remote = response.resultsByUid[uid]
                    val failure = response.failuresByUid[uid]
                    val observationResult = when {
                        // uid 不匹配判为 INVALID，不得据此修改任何主播状态（0.6.22.2）。
                        remote != null && remote.uid != uid -> ObservationResult.INVALID
                        remote == null -> failure
                            ?.let { RoomFailureMapping.observationResultOf(it.reason) }
                            ?: ObservationResult.INVALID
                        !remote.remoteStatusValid || remote.remoteLiveStatus == null ->
                            ObservationResult.INVALID
                        else -> ObservationResult.SUCCESS
                    }
                    val effectiveFailure =
                        if (observationResult == ObservationResult.SUCCESS) null
                        else failure ?: findCallFailure(response)
                    if (observationResult == ObservationResult.SUCCESS) {
                        // ★ 回收（缺陷 1 的核心）：本轮早先失败过、现在拿到有效结果的主播
                        //   必须从账面移出 —— 否则末轮重算的比例恒等于第一轮的失败集合，
                        //   而"进入重试轮"的前提就是第一轮越线 ⇒ 必然误报全局降级。
                        //   第一轮就成功的主播本来就不在账面上，这里是幂等的空操作。
                        round.resolve(listOf(stableId))
                    } else if (effectiveFailure != null) {
                        round.record(effectiveFailure.reason, listOf(stableId))
                    } else {
                        // 确实失败、但整批也没有可用的失败信号：计入分子，**不编造原因**
                        // （归因会因此落到 UNKNOWN，而不是被硬塞一个"网络不可用"）。
                        round.recordUnknownReason(listOf(stableId))
                    }
                    applySafely(
                        stableId, uid, observationResult, remote, snapshot, lease,
                        failure = effectiveFailure
                    )
                    // ★ 封禁判定（用户诉求 1）：失败 → 探一次 room_init；成功但带封禁标记 → 零请求判定。
                    //   放在观察写入**之后**：即使探针失败，观察本身也已经如实落库。
                    if (observationResult != ObservationResult.SUCCESS || remote?.banLocked == true) {
                        runCatching {
                            probeBanIfWarranted(
                                streamerDao.findActiveByStableId(stableId) ?: return@runCatching,
                                observationResult, remote, snapshot, lease
                            )
                        }.onFailure { e ->
                            // 兜底：封禁判定链路里的**未预期异常**（例如写库失败）。
                            // ★ 用 UNKNOWN 而不是 API_REJECTED：这里抛异常的是**我们自己**，
                            //   没有任何接口拒绝过这次调用；把它记成"接口被拒绝"就是撒谎
                            //   （与缺陷 1 同一类错误）。detail 带原始异常，边界清楚。
                            // ★ 边沿触发：若异常是持续性的（例如库一直写不进去），每轮都会再抛一次，
                            //   不做抑制就会重新变成"每分钟一条"。
                            val signature = "BAN_PATH_EXCEPTION|${e.javaClass.simpleName}|${e.message}"
                            if (banLogGate.shouldRecord(stableId, signature)) {
                                logAppError(
                                    AppError.UNKNOWN,
                                    "封禁判定失败（不影响本次观察）：stable=${stableId.takeLast(8)} ${e.message}"
                                )
                            }
                        }
                    }
                }
                return
            }

            else -> {
                // 未知结果类型（sealed 接口在别的模块实现时可能出现）：如实记录，不得静默丢弃。
                val failure = CallFailure(
                    reason = RoomFailureReason.MALFORMED_RESPONSE,
                    apiMessage = "unknown BatchStatusOutcome"
                )
                round.record(failure.reason, stableIds)
                for ((index, stableId) in stableIds.withIndex()) {
                    applySafely(
                        stableId, uids.getOrNull(index) ?: continue,
                        ObservationResult.INVALID, null, snapshot, lease, failure
                    )
                }
                return
            }
        }
    }

    /** 二分隔离：见 [RoundFailureGate.shouldSplitBatch]。 */
    private suspend fun splitBatch(
        uids: List<Long>,
        fetch: suspend (List<Long>) -> BatchStatusOutcome,
        onFailure: suspend (CallFailure) -> Unit,
        depth: Int = 0
    ): SplitResult? {
        if (depth >= RoundFailureGate.MAX_SPLIT_DEPTH) return null
        val half = uids.size / 2
        if (half <= 0) return null
        val parts = listOf(uids.take(half), uids.drop(half))
        val responded = linkedMapOf<Long, RemoteLiveRoom?>()
        val failed = linkedMapOf<Long, CallFailure>()
        for (part in parts) {
            val outcome = try {
                fetch(part)
            } catch (e: Exception) {
                BatchStatusOutcome.Failed(
                    CallFailure(
                        reason = RoomFailureReason.MALFORMED_RESPONSE,
                        apiMessage = "拆分重问异常：${e.message}".take(MEASURE_ERROR_DETAIL)
                    )
                )
            }
            when (outcome) {
                is BatchStatusOutcome.Responded -> {
                    val response = outcome.response
                    findCallFailure(response)?.let { onFailure(it) }
                    for (uid in part) {
                        val remote = response.resultsByUid[uid]
                        if (remote != null && remote.uid == uid) {
                            responded[uid] = remote
                        } else {
                            failed[uid] = response.failuresByUid[uid] ?: CallFailure(
                                reason = RoomFailureReason.MISSING_IN_RESPONSE,
                                apiCode = response.apiCode,
                                apiMessage = response.apiMessage
                            )
                        }
                    }
                }
                is BatchStatusOutcome.Failed -> {
                    onFailure(outcome.failure)
                    part.forEach { failed[it] = outcome.failure }
                }
                else -> part.forEach {
                    failed[it] = CallFailure(
                        reason = RoomFailureReason.MALFORMED_RESPONSE,
                        apiMessage = "unknown BatchStatusOutcome"
                    )
                }
            }
        }
        return SplitResult(responded, failed)
    }

    /** 二分隔离的结果：拿到有效房间数据的 uid → 房间，与仍未拿到的 uid → 原因。 */
    private class SplitResult(
        val responded: Map<Long, RemoteLiveRoom?>,
        val failed: Map<Long, CallFailure>
    )

    /** uid → 同序 stableId 的查表（列表由同一批主播派生，长度一致）。 */
    private fun stableIdFor(uid: Long, stableIds: List<String>, uids: List<Long>): String {
        val index = uids.indexOf(uid)
        return stableIds.getOrElse(if (index >= 0) index else 0) { stableIds.first() }
    }

    /**
     * 响应里是否带有"整批级别"的失败信号。
     *
     * 为什么需要它：`responseValidity == INVALID` 在两种完全不同的情况下都会出现 ——
     * ① 服务端业务拒绝（`code != 0`）；② `code == 0` 但这一批一个 uid 都没返回。
     * 把 ① 当成传输失败正是原实现"一个坏房间顶开全局熔断"的根因，
     * 所以这里只认**显式**的业务拒绝信号（`callFailure` / `apiCode != 0`）。
     */
    private fun findCallFailure(response: com.example.bilimonitor.domain.model.BatchRoomStatusResponse): CallFailure? {
        response.callFailure?.let { return it }
        val code = response.apiCode
        if (code != null && code != 0) {
            return CallFailure(
                reason = RoomFailureReason.API_REJECTED,
                apiCode = code,
                apiMessage = response.apiMessage
            )
        }
        return null
    }

    /**
     * 聚合窗口到期释放不能等下一个 Tick（最长可能滞留一个完整监控周期）。
     * Tick 结束后预约一次到期即时的窗口结算 + 投递（幂等，重复执行无副作用）。
     */
    private suspend fun scheduleWindowEndFlush() {
        val windows = runCatching {
            dbHandle.notificationAggregateDao().listInProgress()
        }.getOrNull().orEmpty()
        val nextEnd = windows.filter { it.bootId == clock.bootId() }
            .minOfOrNull { it.windowEndElapsed } ?: return
        val delayMs = (nextEnd - clock.nowElapsed()).coerceIn(0, 60_000L) + 300
        scope.launch {
            // 整个延迟任务都要有异常边界（台账 H19-A4）：这是 fire-and-forget 协程，
            // 而 appScope 没有 CoroutineExceptionHandler —— 任何未捕获异常都会直达默认处理器，
            // 被 CrashRecorder 记一笔后**杀掉进程**。原先只有 processWindowEnds 被包住，
            // dispatchDue() 里的 DB 查询（findDue）裸奔，一次 SQLiteException 就能崩应用。
            runCatching {
                kotlinx.coroutines.delay(delayMs)
                notificationRepository.processWindowEnds()
                notificationDispatcher.dispatchDue()
            }.onFailure {
                logAppError(AppError.UNKNOWN, "聚合窗口到期投递失败：${it.message}")
            }
        }
    }

    private suspend fun applySafely(
        streamerStableId: String,
        uid: Long,
        result: ObservationResult,
        remote: RemoteLiveRoom?,
        snapshot: MonitoringConfigSnapshot,
        lease: MonitoringLease,
        /**
         * 本次失败的**结构化原始信号**（HTTP 状态码 / 业务 code / message 摘要）。
         * 原先这里恒为 null，于是库里所有失败长得一模一样、事后无法定位。
         */
        failure: CallFailure? = null
    ) {
        try {
            val streamer = streamerDao.findActiveByStableId(streamerStableId) ?: return
            val observedAt = clock.nowWall()
            val observation = StreamerObservation(streamerStableId, uid, result, observedAt)
            val sequence = streamer.observationSequence + 1
            val pending = streamerDao.getPendingTransition(streamer.id)
            val context = StreamerMonitorContext(
                confirmedLiveStatus = streamer.confirmedLiveStatus,
                pendingTransition = pending?.pendingTransition
                    ?: com.example.bilimonitor.data.local.PendingTransition.NONE,
                // 两个方向共用一个 confirmationCount 列，因此只有方向匹配时才把计数交给策略，
                // 否则策略会把上一方向攒下的次数当成自己的（见 StreamerMonitorContext 注释）。
                pendingConfirmationCount = if (pending?.pendingTransition ==
                    com.example.bilimonitor.data.local.PendingTransition.SUSPECTED_OFFLINE
                ) pending.confirmationCount else 0,
                pendingLiveConfirmationCount = if (pending?.pendingTransition ==
                    com.example.bilimonitor.data.local.PendingTransition.SUSPECTED_LIVE
                ) pending.confirmationCount else 0,
                hasActiveReliableInterval = reliableIntervalDao.findActiveByStreamer(streamer.stableId) != null,
                startConfirmationCount = snapshot.startCountFor(streamer.stableId),
                endConfirmationCount = snapshot.endCountFor(streamer.stableId)
            )
            val decision = StateConfirmationPolicy.detect(context, result, remote)
            // 通知策略在 Tick 内构建一次，三条分支共用 ——
            // 之前只在"转换提交"分支里构建，普通观察路径就没有策略可用，
            // 导致标题/分区变化这类"非转换事件"永远发不出通知。
            val notificationPolicy = StreamerNotificationPolicy(
                notifyStart = snapshot.notifyStartFor(streamer.stableId),
                notifyEnd = snapshot.notifyEndFor(streamer.stableId),
                aggregationEnabled = snapshot.aggregationEnabled,
                aggregationThreshold = snapshot.aggregationThreshold,
                aggregationWindowSeconds = snapshot.aggregationWindowSeconds,
                // 标题/分区变化通知（默认关闭）
                notifyTitleChange = snapshot.notifyTitleChangeFor(streamer.stableId),
                notifyAreaChange = snapshot.notifyAreaChangeFor(streamer.stableId),
                // 免打扰在 Tick 内一次性判定；主播级覆盖优先（原规范 22.3）
                quietHoursActive = snapshot.quietHoursRangeFor(streamer.stableId)?.let { (s, e) ->
                    com.example.bilimonitor.domain.policy.QuietHoursPolicy.contains(
                        nowMinutesOfDay = com.example.bilimonitor.domain.policy.QuietHoursPolicy
                            .nowMinutesOfDay(observedAt),
                        startMinutes = s,
                        endMinutes = e
                    )
                } ?: false
            )
            val outcome = when (decision) {
                is StateConfirmationPolicy.Decision.Transition -> {
                    val transition = DetectedTransition(
                        streamerStableId = streamer.stableId,
                        from = decision.from,
                        to = decision.to,
                        transitionId = "",
                        observationSequence = sequence,
                        streamerMonitorGeneration = streamer.streamerMonitorGeneration,
                        reason = decision.reason
                    )
                    monitorRepository.applyConfirmedObservationAtomically(
                        remote = remote,
                        transition = transition,
                        observation = observation,
                        observationSequence = sequence,
                        monitorGeneration = lease.monitorGeneration,
                        fencingToken = lease.fencingToken,
                        configVersion = snapshot.configVersion,
                        policy = notificationPolicy,
                        // 断档截断需要知道"正常的检查间隔是多少"
                        // ★ 这里必须用**实际周期**（FreshnessPolicy.tickPeriodSeconds），
                        //   不能用配置里的 intervalSeconds：省电模式把真实周期 clamp 到 ≥15 分钟，
                        //   而 intervalSeconds 可能还是 60s —— 阈值 2×60=120s 远小于真实间隔（约 900s）
                        //   ⇒ **每次关播都被判成"断档"**，endTime 回退到上次成功确认的时刻
                        //   （时长系统性少算约一个周期），并且每次都写一条 SESSION_END_TRUNCATED 审计，
                        //   把真正的断档信号淹没。下面两条 applyObservationOnly 路径一直用的是这个函数，
                        //   两条路径同源之后，"正常的检查间隔是多少"只有一个来源。
                        expectedIntervalSeconds = FreshnessPolicy.tickPeriodSeconds(
                            snapshot.mode, snapshot.intervalSeconds
                        )
                    )
                }
                is StateConfirmationPolicy.Decision.Counting ->
                    monitorRepository.applyObservationOnly(
                        observation, remote, decision, sequence,
                        lease.monitorGeneration, lease.fencingToken,
                        policy = notificationPolicy, configVersion = snapshot.configVersion,
                        // 数据新鲜度阈值要按**实际**周期抬高（省电模式 ≥15 分钟），
                        // 否则失败观察会把那一列写成 STALE，而界面（用同一个纯函数）说没过期。
                        expectedIntervalSeconds = FreshnessPolicy.tickPeriodSeconds(
                            snapshot.mode, snapshot.intervalSeconds
                        )
                    )
                StateConfirmationPolicy.Decision.None ->
                    monitorRepository.applyObservationOnly(
                        observation, remote, null, sequence,
                        lease.monitorGeneration, lease.fencingToken,
                        policy = notificationPolicy, configVersion = snapshot.configVersion,
                        expectedIntervalSeconds = FreshnessPolicy.tickPeriodSeconds(
                            snapshot.mode, snapshot.intervalSeconds
                        )
                    )
            }
            // 所有非 Applied 结果都必须留下痕迹。
            // StaleSequence / StaleOrFenced 意味着**这次观察根本没写进数据库**
            // （代际或 fencing token 已过期），转换与状态更新全部丢失；
            // 原实现只处理 Invalid，其余静默丢弃、Tick 仍报告 ran=true，
            // 违反本仓库"任何降级都不允许静默发生"的硬规则，也让 CAS 失败在诊断包里不可见。
            when (outcome) {
                is ApplyObservationResult.Applied -> Unit
                is ApplyObservationResult.Invalid ->
                    logAppError(AppError.API_INVALID_RESPONSE, "apply invalid: ${outcome.reason}")
                ApplyObservationResult.StaleSequence ->
                    logAppError(AppError.UNKNOWN, "apply stale_sequence: 观察序号过期，本次观察未写入")
                ApplyObservationResult.StaleOrFenced ->
                    logAppError(
                        AppError.BACKGROUND_EXECUTION_RESTRICTED,
                        "apply stale_or_fenced: 代际/fencing token 已失效，本次观察未写入"
                    )
                ApplyObservationResult.ConfigStale ->
                    logAppError(AppError.CONFIG_INVALID, "apply config_stale: 配置代际已变化，本次观察未写入")
                ApplyObservationResult.DuplicateOpenSession ->
                    logAppError(AppError.DATABASE_ERROR, "apply duplicate_open_session: 存在重复的 OPEN 场次")
            }
        } catch (e: Exception) {
            // 单主播异常隔离（246.2）。
            logAppError(AppError.UNKNOWN, "applyObservation failed: ${e.message}")
        }
    }

    // ---- 请求级重试（maxRetries / retryBaseSeconds / maxRetryDelaySeconds，原规范 246.5）----

    /**
     * 单批请求的**一次**尝试（不重试）。这是我们能拿到的**最原始的一层**：
     * HTTP 状态码、业务 `code`/`message`、超时/IO 异常都在 [BatchStatusOutcome] 里分类保留，
     * 不再像原实现那样被压成 `null`。
     *
     * 引擎外层的 `withTimeout` 是"批次总预算"（单批超时 + 5s 余量）；
     * 协程超时（`TimeoutCancellationException`）同样归到 [RoomFailureReason.TIMEOUT]。
     */
    private suspend fun fetchOnce(
        uids: List<Long>,
        snapshot: MonitoringConfigSnapshot
    ): BatchStatusOutcome = try {
        withTimeout(snapshot.timeoutSeconds * 1000L + SINGLE_ATTEMPT_SLACK_MS) {
            dataSource.getRoomStatus(uids)
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        BatchStatusOutcome.Failed(CallFailure(reason = RoomFailureReason.TIMEOUT))
    }

    /**
     * 带重试的批次请求 —— **只在比例门禁放行后的二次重试轮里调用**。
     *
     * 失败按 `retryBaseSeconds * 2^n` 退避重试，上限 `maxRetryDelaySeconds`；
     * 熔断按**批次**计数（不是按每次 HTTP 尝试，台账 H19-A10）：
     * 只有"本批次的最后一次尝试仍失败"才算一次失败，否则阈值 3 时一个批次的 3 次重试
     * 就能自己把全局熔断打开（配了 maxRetries 反而更快熔断，与用户预期相反）。
     *
     * ★ `deadlineElapsed`（本轮预算到期时刻）：退避 + 下一次尝试 + 收尾余量装不下时，
     *   按"最后一次尝试"的口径处理 —— 如实计一次熔断失败后返回结果，
     *   **不让单个批次把整轮拖进硬取消**（那会连收尾一起丢）。
     */
    private suspend fun fetchEitherWithRetry(
        uids: List<Long>,
        snapshot: MonitoringConfigSnapshot,
        deadlineElapsed: Long
    ): BatchStatusOutcome {
        val attempts = com.example.bilimonitor.domain.policy.RetryPolicy.totalAttempts(snapshot.maxRetries)
        var result: BatchStatusOutcome =
            BatchStatusOutcome.Failed(CallFailure(reason = RoomFailureReason.NETWORK))
        for (attempt in 0 until attempts) {
            // （熔断总开关关闭时 [breakerOpen] 恒为 false ⇒ 这里不 break，
            //   本批次会把 maxRetries 次尝试走完，与"熔断从未打开"完全一致。）
            if (breakerOpen(snapshot)) break
            result = fetchOnce(uids, snapshot)
            val failure = transportFailureOf(result)
            // ★ 服务端**明确拒绝**（业务 code != 0、401/403/404/412）不重试：
            //   它不是"暂时性故障"，重试既不会变好，还会顺带把熔断计数堆到阈值 ——
            //   而那正是用户报告的 bug（一个坏房间把全局熔断顶开）。它照常记录，只是不再重问。
            if (failure == null) {
                recordRequestOutcome(success = true, snapshot)
                return result
            }
            if (!failure.retryable) {
                recordRequestOutcome(success = false, snapshot, failure = failure)
                return result
            }
            val backoffMs = com.example.bilimonitor.domain.policy.RetryPolicy.backoffMillis(
                attempt = attempt,
                baseSeconds = snapshot.retryBaseSeconds,
                maxDelaySeconds = snapshot.maxRetryDelaySeconds
            )
            val lastAttempt = attempt == attempts - 1
            // ★ 装不下（退避 + 下一次尝试 + 收尾余量）就按"最后一次尝试"处理：
            //   口径与 lastAttempt **完全一致**（如实计一次熔断失败再返回），
            //   于是最坏情况是"这一批少重试几次"，而不是整轮被硬取消、收尾全部丢失。
            val fitsBudget = retryFitsInBudget(
                remainingMs = remainingTickBudgetMs(deadlineElapsed, clock.nowElapsed()),
                backoffMs = backoffMs,
                attemptBudgetMs = snapshot.timeoutSeconds * 1000L + SINGLE_ATTEMPT_SLACK_MS,
                tailReserveMs = TICK_TAIL_RESERVE_MS
            )
            if (lastAttempt || !fitsBudget) {
                recordRequestOutcome(success = false, snapshot, failure = failure)
                return result
            }
            delay(backoffMs)
        }
        return result
    }

    /**
     * 这次结果是否构成"传输类失败"（会参与重试与熔断计数的那些）。
     *
     * 只有机制性失败才算：超时 / 连接失败 / 5xx / 解析失败。
     * 限流（429）与业务拒绝（`code != 0`、403/404/412）**不算** ——
     * 它们代表"服务端在正常工作，只是拒绝了这个请求/这个房间"，
     * 与"服务不可用"是两件事，混在一起就会让一个坏房间顶开全局熔断。
     */
    private fun transportFailureOf(outcome: BatchStatusOutcome): CallFailure? = when (outcome) {
        is BatchStatusOutcome.Failed ->
            outcome.failure.takeIf { it.reason != RoomFailureReason.RATE_LIMITED && it.retryable }
        is BatchStatusOutcome.Responded -> {
            val response = outcome.response
            val code = response.apiCode
            if (code != null && code != 0) null
            else response.callFailure?.takeIf { it.reason != RoomFailureReason.RATE_LIMITED }
        }
        else -> null
    }

    // ---- 熔断器（circuitBreakerThreshold / circuitBreakerRecoverySeconds，原规范 180）----

    private val breakerMutex = Mutex()

    /**
     * 熔断状态。写入在 [breakerMutex] 内，但读取发生在多个并发 `async`
     * （[fetchEitherWithRetry] 的每次尝试前）与 Tick 起点 —— 无锁读一个普通可变字段
     * 在 JMM 下可能长时间看不到新值，表现为"熔断已打开却仍在发请求"。
     * 因此标记为 `@Volatile`（状态本身是不可变 data class，替换引用即可）。
     */
    @Volatile
    private var breaker = com.example.bilimonitor.domain.policy.RetryPolicy.BreakerState()

    /**
     * 熔断是否**生效**（= 处于打开状态 **且** 总开关没被关掉）。
     *
     * 判据本体在顶层纯函数 [breakerGateOpen] 里（可 JVM 单测，边界与理由见那里的说明）；
     * 这里只负责把"当前快照 + 当前熔断状态 + 当前时钟"喂给它。三处调用点
     * （Tick 起点早退、重试轮提前收手、重试循环内 break）都传**当前**快照。
     */
    private fun breakerOpen(snapshot: MonitoringConfigSnapshot): Boolean =
        breakerGateOpen(snapshot.circuitBreakerEnabled, breaker, clock.nowElapsed())

    /**
     * 熔断器复位 —— 只在总开关关闭时由 [runTick] 调用。
     *
     * 为什么要复位而不是"关掉开关就不管它"：熔断状态是**跨 Tick 存活**的进程内状态。
     * 用户在熔断打开期间把开关关掉，那个 `openUntilElapsed` 还会留在内存里；之后再把开关打开，
     * 就会"刚打开就先白等一个恢复窗口"（这一轮明明没有任何失败）。关掉开关的语义是
     * "暂停请求这条机制不存在"，所以连同它的状态一起清掉。
     *
     * 写入放在 [breakerMutex] 内：Tick 之间不重叠（[tickMutex]），但 `breaker` 的**所有**
     * 写入都在这一把锁里，这里复用同一把锁，保证"该状态只有一个写入入口"在代码上成立。
     */
    private suspend fun resetBreaker() {
        breakerMutex.withLock {
            val idle = com.example.bilimonitor.domain.policy.RetryPolicy.BreakerState()
            // 已经是空闲态就不重复写字段（开关长期关闭时，这个函数每轮都会被调用一次）。
            if (breaker != idle) breaker = idle
        }
    }

    /**
     * 记一次请求结果 —— **只服务于熔断**（连续失败达到阈值 → 暂停请求一段时间）。
     *
     * ★ 总开关关闭时整条路径都不走：不累计连续失败、不打开熔断、也不写"熔断打开"的留痕。
     *   它**只**影响"连续失败后暂停请求"这一件事 —— 比例门禁（[RoundFailureGate.shouldRunRetryPass]）
     *   决定的重试轮、以及 `runTick` 末尾的盲区/问题通知，都不经过这里，也不会因为这个开关被关掉。
     *
     * @param failure 触发本次计数的失败信号（成功时为 null）。熔断打开时的留痕按它选码，
     *   不再一律写 `NETWORK_UNAVAILABLE`（见函数体里的说明）。
     */
    private suspend fun recordRequestOutcome(
        success: Boolean,
        snapshot: MonitoringConfigSnapshot,
        failure: CallFailure? = null
    ) {
        // ★ 开关关闭 ⇒ 熔断永不打开：连"连续失败"都不计（计了也没有出口，见 [breakerOpen]）。
        if (!snapshot.circuitBreakerEnabled) return
        breakerMutex.withLock {
            val before = breaker.isOpen(clock.nowElapsed())
            breaker = breaker.record(
                success = success,
                threshold = snapshot.circuitBreakerThreshold,
                recoverySeconds = snapshot.circuitBreakerRecoverySeconds,
                nowElapsed = clock.nowElapsed()
            )
            val after = breaker.isOpen(clock.nowElapsed())
            if (!before && after) {
                // ★ 错误码按**顶开熔断的那次失败原因**选（缺陷 2 的口径统一）：熔断也可能被
                //   MALFORMED_RESPONSE / MISSING_IN_RESPONSE 这类**非传输**原因顶开，一律写
                //   NETWORK_UNAVAILABLE 就是把"响应解析不了 / 缺少数据"说成"网络故障"。
                //   选码与类别都走既有能力（[breakerTraceCodeOf] → [RoundFailureCode] →
                //   [RoomFailureMapping]；[breakerTraceKindOf] → [RoundFailureGate.isTransportFailure]），
                //   与"少数主播失败"汇总日志、系统级归因同源；拿不到原因时如实落 UNKNOWN。
                val trigger = failure?.reason
                val triggerLabel = trigger?.name ?: "未能确定"
                val triggerKind = breakerTraceKindOf(trigger)
                logAppError(
                    breakerTraceCodeOf(trigger),
                    "熔断打开：连续 ${breaker.consecutiveFailures} 次请求失败，" +
                        "触发原因=${triggerLabel}（${triggerKind}），" +
                        "${snapshot.circuitBreakerRecoverySeconds}s 内不再发起请求"
                )
            }
        }
    }

    private suspend fun ensureLease(): MonitoringLease? {
        val current = heldLease
        if (current != null && runtimeLockRepository.renew(current)) return current
        val acquired = runtimeLockRepository.acquireLease()
        heldLease = acquired
        return acquired
    }

    /**
     * 主动释放监控租约（台账 H19-A12）。
     *
     * 租约原先**从不主动释放**（`runtimeLockRepository.release` 全仓库没有调用者），只能靠 TTL 过期回收。
     * 于是"停止监控后 90 秒内又重启"（快速重启、安装更新、START_STICKY 被系统拉起）时，
     * `acquireLease` 会因为旧租约仍未过期而返回 null，第一次 Tick 直接 `lease_lost` ——
     * 恢复检查会话还会被结算成 FAILED，之后要等一个完整间隔才自愈。
     *
     * 服务停止/销毁时调用，可以让下一次启动的第一次 Tick 立刻就绪。
     */
    suspend fun releaseLease() {
        // ★ 有 Tick 在飞时**不释放**（代理审查发现）：释放会清掉 fencingToken，
        //   在飞那一轮剩余主播的观察写会被 CAS 拒绝，还会被记成"代际/fencing token 已失效" ——
        //   **归因是错的**（真实原因是服务在停机时主动释放了租约）。
        //   用 tryLock：拿不到就放弃。租约本来就有 TTL 会自然过期，晚一点回收没有任何副作用，
        //   而误伤一个正在跑的 Tick 会丢掉真实观察数据。
        if (!tickMutex.tryLock()) {
            android.util.Log.i(
                "MonitoringEngine",
                "有 Tick 正在执行，跳过主动释放租约（交给 TTL 过期回收，避免误伤在飞的写入）"
            )
            return
        }
        try {
            val current = heldLease ?: return
            runCatching { runtimeLockRepository.release(current) }
                .onFailure { logAppError(AppError.DATABASE_ERROR, "释放监控租约失败：${it.message}") }
            heldLease = null
        } finally {
            tickMutex.unlock()
        }
    }

    /**
     * 大面积失败时的**统一降级入口**（缺陷 2：归因由 [attribution] 决定，不再一律"网络不可用"）。
     *
     * 三件事都必须跟着**主导原因**走，否则系统级与主播级会互相矛盾：
     *  ① SYSTEM 盲区的 `GapReason`（网络事故 / 接口事故 / 原因未知）；
     *  ② `application_error_log` 与通知 payload 的错误码；
     *  ③ 用户看到的文案（"网络不可用" vs "接口被限流（429）" vs "接口被拒绝"）。
     * 事件本身**照常降级**（盲区照开、通知照发）—— 降级不静默这条规则没有被放宽，
     * 放宽的只是"把接口侧问题说成网络故障"这个**错误归因**。
     *
     * @param attribution 由 [RoundFailureGate.globalFailureCause] 判定；熔断分支沿用上一轮的结论。
     */
    private suspend fun onGlobalFailure(
        snapshot: MonitoringConfigSnapshot,
        attribution: RoundFailureGate.GlobalFailureAttribution
    ) {
        val now = clock.nowWall()
        // 记下本轮归因：熔断打开后的下一轮（不发请求）要用它，见 [lastGlobalFailure]。
        lastGlobalFailure = attribution
        // 计数恢复（244.12）：进程重启后内存计数会归零，不能直接从头数，
        // 否则长间隔模式（省电模式 15 分钟一周期）永远凑不满阈值、系统问题通知发不出来。
        //
        // 但"上一条健康事件是 DEGRADED"**不足以**证明故障仍在持续：
        // 如果故障早已结束、只是当时进程已经死了（没能写出 HEALTHY 事件），
        // 那条 DEGRADED 会一直留在表里，于是新进程的**第一次**失败就被当成第 3 次，
        // 立刻误报"网络不可用"。
        // 因此追加两个条件：SYSTEM 盲区必须仍然**未关闭**（这是故障仍在持续的权威证据），
        // 且距离该盲区开始不超过一个宽限窗口。
        val degradationOngoing = runCatching {
            val openGap = monitoringGapDao.findOpenByScope(GapScope.SYSTEM)
            openGap != null && (now - openGap.startedAt) <= COUNT_RESTORE_GRACE_MS
        }.getOrDefault(false)
        if (consecutiveTransportFailures.get() == 0 && degradationOngoing) {
            consecutiveTransportFailures.set(PROBLEM_THRESHOLD - 1)
        }
        val failures = consecutiveTransportFailures.incrementAndGet()
        // ★ 盲区原因按主导原因选（缺陷 2 的修复点）：修复前这里恒为 NETWORK_UNAVAILABLE，
        //   于是"98 位全被 code=-352 拒绝"会被记成网络不可用，与主播级的 API_UNAVAILABLE 打架。
        openSystemGap(attribution.gapReason, now)
        if (failures >= PROBLEM_THRESHOLD) {
            // problemKey 按事故类型分段：网络事故与接口事故各成一个 episode，
            // 不会互相"顶替"（否则先开的那个问题的恢复通知永远发不出去）。
            val baseKey = attribution.problemBaseKey
            val active = problemDao.listActive().firstOrNull { it.problemKey.startsWith(baseKey) }
            if (active == null) {
                val episodeId = Ids.newId()
                val problemKey = "$baseKey:$episodeId"
                problemDao.upsert(
                    ProblemStateEntity(
                        problemKey = problemKey, active = true, episodeId = episodeId,
                        firstObservedAt = now, recoveredAt = null, updatedAt = now
                    )
                )
                val cooldown = problemDao.getCooldown(baseKey)
                if (cooldown?.cooldownUntil == null || cooldown.cooldownUntil <= now) {
                    NotificationOutboxWriter.createSystemProblem(
                        db = dbProvider(), clock = clock,
                        problemKey = problemKey,
                        eventType = com.example.bilimonitor.data.local.NotificationEventType.SYSTEM_PROBLEM,
                        payloadJson = NotificationPayloads.encodeSystem(
                            SystemProblemPayload(
                                problemKey = problemKey, component = "MONITORING",
                                // 错误码与文案都来自同一次归因：不再固定写成网络故障。
                                errorCode = attribution.appError.name,
                                summary = attribution.summary, recovered = false
                            )
                        ),
                        now = now
                    )
                    problemDao.upsertCooldown(
                        com.example.bilimonitor.data.local.entity.NotificationCooldownEntity(
                            problemKey = baseKey, lastNotifiedAt = now,
                            cooldownUntil = now + COOLDOWN_MS, recoveredNotified = false, updatedAt = now
                        )
                    )
                }
            }
        }
    }

    private suspend fun onTransportSuccess(snapshot: MonitoringConfigSnapshot) {
        consecutiveTransportFailures.set(0)
        // 事故已结束：清掉上一轮的归因，下一次事故重新判定（不许沿用旧结论）。
        lastGlobalFailure = null
        val now = clock.nowWall()
        val openGap = monitoringGapDao.findOpenByScope(GapScope.SYSTEM)
        if (openGap != null) {
            monitoringGapDao.close(openGap.gapId, now)
            logHealth(MonitorHealthStatus.HEALTHY, "SYSTEM_GAP_CLOSED")
        }
        // ★ 恢复要覆盖**所有**系统级事故（网络 / 接口 / 未知），不只是网络那一个：
        //   接口侧事故现在也会开 SYSTEM 盲区（原因不同、事实相同），漏掉它的恢复通知
        //   会让"监控已恢复"永远不出现（问题面板里挂着一个永不结束的 episode）。
        for (baseKey in SYSTEM_PROBLEM_BASE_KEYS) {
            // 故障已恢复：清除冷却，使下一次新故障能立即通知（原规范 90）。
            // 原实现只写不删，恢复后 30 分钟内再次故障会被自己的冷却窗口静默吞掉。
            problemDao.clearCooldown(baseKey)
            val active = problemDao.listActive().firstOrNull { it.problemKey.startsWith(baseKey) }
            if (active != null) {
                problemDao.upsert(active.copy(active = false, recoveredAt = now, updatedAt = now))
                NotificationOutboxWriter.createSystemProblem(
                    db = dbProvider(), clock = clock,
                    problemKey = active.problemKey,
                    eventType = com.example.bilimonitor.data.local.NotificationEventType.SYSTEM_RECOVERED,
                    payloadJson = NotificationPayloads.encodeSystem(
                        SystemProblemPayload(
                            problemKey = active.problemKey, component = "MONITORING",
                            // 恢复通知的码与文案跟着**当初那个 episode** 的类型走：
                            // 接口事故恢复时说"网络已恢复"同样是错误归因（方向会被带偏）。
                            errorCode = recoveryErrorOf(baseKey).name,
                            summary = recoverySummaryOf(baseKey), recovered = true
                        )
                    ),
                    now = now
                )
            }
        }
    }

    /** `problemKey` 的中段（形如 `MONITORING:<段>:SYSTEM:<episodeId>`）。 */
    private fun problemSegmentOf(baseKey: String): String = baseKey.split(':').getOrNull(1) ?: ""

    /** 恢复通知的错误码：跟着**该 episode 自己的**事故类型走（不许一律说成网络）。 */
    private fun recoveryErrorOf(baseKey: String): AppError = when (problemSegmentOf(baseKey)) {
        GapReason.API_UNAVAILABLE.name -> AppError.API_REJECTED
        GapReason.NETWORK_UNAVAILABLE.name -> AppError.NETWORK_UNAVAILABLE
        else -> AppError.UNKNOWN
    }

    /** 恢复通知的文案：事故类型不同，恢复的说法也必须不同（否则同样是把人往错方向带）。 */
    private fun recoverySummaryOf(baseKey: String): String = when (problemSegmentOf(baseKey)) {
        GapReason.API_UNAVAILABLE.name -> "接口已恢复，监控继续"
        GapReason.NETWORK_UNAVAILABLE.name -> "网络已恢复，监控继续"
        else -> "监控已恢复"
    }

    private suspend fun openSystemGap(reason: GapReason, now: Long) {
        val existing = monitoringGapDao.findOpenByScope(GapScope.SYSTEM)
        if (existing != null) return
        monitoringGapDao.insert(
            MonitoringGapEntity(
                gapId = Ids.stableId("gap"), startedAt = now, endTime = null,
                scope = GapScope.SYSTEM, reason = reason, createdAt = now
            )
        )
        logHealth(MonitorHealthStatus.DEGRADED, "SYSTEM_GAP_OPEN")
    }

    /**
     * 健康事件。
     *
     * ★ `note` 不是装饰参数（台账 H19-A6）：健康表没有承载原因的列，
     *   原先 note 被直接丢弃，于是诊断页只能看到一条"MONITORING → BLOCKED"，
     *   分不清是租约被抢（LEASE_LOST）、维护模式占用还是别的 ——
     *   "任何降级都不允许静默发生"这条规则在这里实际没有落地。
     *   现在把原因同时写进日志表，诊断包按时间戳就能对上。
     */
    private suspend fun logHealth(to: MonitorHealthStatus, note: String) {
        val now = clock.nowWall()
        // ★ 边沿触发（台账 H19-A6 的补充）：checkOnce 每轮都会在取不到租约时调 `logHealth(BLOCKED)`，
        //   原实现每轮都插一条 —— 实测库里已经堆了 51 条一模一样的 "MONITORING → BLOCKED"，
        //   诊断页全是噪声；现在再加上原因日志会翻倍。
        //   因此只在"状态确实发生变化"时写事件与原因，持续处于同一状态不再重复记录。
        val previous = runCatching { logDao.lastHealthStatus("MONITORING") }.getOrNull()
        if (previous == to) return
        runCatching {
            logDao.insertHealthEvent(
                HealthEventEntity(
                    healthEventId = Ids.newId(), component = "MONITORING",
                    fromStatus = previous, toStatus = to, occurredAt = now,
                    episodeId = null
                )
            )
        }
        if (to == MonitorHealthStatus.BLOCKED || to == MonitorHealthStatus.DEGRADED) {
            runCatching {
                logDao.insertAppError(
                    com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = now,
                        errorCode = AppError.BACKGROUND_EXECUTION_RESTRICTED,
                        detail = "监控健康度变为 $to：$note".take(500)
                    )
                )
            }
        }
    }

    private suspend fun logAppError(code: AppError, detail: String?) {
        runCatching {
            logDao.insertAppError(
                com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                    errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                    errorCode = code,
                    // 截断（第二轮审查发现）：异常 message 可能把整段响应体带进来，
                    // 不截断会让 DB 行、诊断包与解压内存成倍膨胀。
                    detail = detail?.take(500)
                )
            )
        }
    }

    private fun dbProvider(): com.example.bilimonitor.data.local.db.AppDatabase = dbHandle

    companion object {
        const val PROBLEM_THRESHOLD = 3
        const val COOLDOWN_MS = 30 * 60 * 1000L

        /**
         * 封禁链路"状态提示"的统一 logcat tag。
         *
         * 为什么封禁的跳过/续期只进 logcat 而不写库：那些记录既不是错误、又每轮都会重复，
         * 写进错误日志只会用"一切正常"淹没真正有用的错误（理由见 recordBanTransition /
         * noteBanSteady 的注释）。需要现场排查时用 `adb logcat -s MonitoringEngine` 抓。
         */
        const val TAG = "MonitoringEngine"

        /**
         * 写进 `detail` / `detailJson` 的文本上限。
         *
         * 与 `logAppError` 的 500 同一条理由：上游 message 可能把整段响应体带进来，
         * 不截断会让数据库行、诊断包与解压内存成倍膨胀。
         */
        const val LOG_DETAIL_MAX_CHARS = 500

        /**
         * 每轮 Tick 允许的封禁探针（`room_init`）次数上限。
         *
         * 为什么是 8：探针是"每主播一次"的请求（批量状态接口不返回 is_locked），
         * 若整轮 98 位主播全部失败（例如真断网），无上限就会在断网时再打 98 个请求 ——
         * 既雪上加霜，也会被服务端当成异常流量。8 次 ≈ 额外流量 <10%，
         * 而"一两个坏房间"这种真实场景远低于这个额度。
         */
        const val MAX_BAN_PROBES_PER_TICK = 8

        /**
         * 「本轮少数主播失败」汇总日志在 [BanLogEdgeGate] 里的固定 key。
         *
         * 这条日志一轮至多一条，所以 key 不需要按主播区分；用常量而不是字面量，
         * 是为了让"记录点"与"清除点"（[runTick] 里本轮无人失败时 clear）不可能写岔。
         */
        const val ROUND_FAILURE_LOG_KEY = "ROUND_PARTIAL_FAILURE"

        /**
         * 引擎内部异常摘要的截断长度。
         * 与 `MonitoringWorker.MAX_ERROR_DETAIL` 同一条理由：异常 message 可能把整段响应体
         * 或 HTML 错误页带进来，不截断会让数据库行、诊断包与解压内存成倍膨胀。
         */
        const val MEASURE_ERROR_DETAIL = 160

        /**
         * 进程重启后恢复"连续传输失败计数"的宽限窗口。
         * 只有当 SYSTEM 盲区仍未关闭、且开始时间在这个窗口内，才认为故障确实还在持续。
         */
        const val COUNT_RESTORE_GRACE_MS = 2 * 60 * 60 * 1000L

        /**
         * 全部系统级事故的 `problemKey` 前缀（网络事故 / 接口事故 / 原因未知各一个）。
         *
         * 恢复路径要把**每一个**都收掉：漏掉任何一个，"监控已恢复"就永远不会出现，
         * 问题面板里会挂着一个永不结束的 episode。列表由枚举直接派生（不手抄字符串），
         * 新增事故类型时不可能忘记同步这里。
         */
        val SYSTEM_PROBLEM_BASE_KEYS: List<String> =
            RoundFailureGate.GlobalFailureKind.values().map { RoundFailureGate.problemBaseKeyOf(it) }
    }
}

// ---- 引擎里可以离线证明的纯判定（抽成顶层函数只为能被 JVM 单测钉死边界）----
//
// 本文件的主体是一个依赖 Android / Room / 协程的 @Singleton 类，没有它自己的 JVM 测试；
// 而下面这三行判定恰恰是本次改动里"错一个字符就等于没修"的地方，所以它们被抽成纯函数，
// 由 `MonitoringEngineCircuitBreakerTest` 逐条锁死。

/**
 * 熔断总开关的**生效判据**：开关打开 **且** 处于熔断窗口。
 *
 * 为什么把开关并进判据、而不是只在写入侧拦住"打开熔断"：[RetryPolicy.BreakerState]
 * 是跨 Tick 存活的内存状态，用户在熔断打开期间关掉开关时它还停在"打开"。
 * 若只在写入侧拦，Tick 起点的 `circuit_open` 早退仍会命中 —— 请求照旧发不出去，
 * 与"关熔断"的意图正好相反。并进判据之后，开关关闭期间那条早退路径不可能被走到。
 */
internal fun breakerGateOpen(
    enabled: Boolean,
    state: com.example.bilimonitor.domain.policy.RetryPolicy.BreakerState,
    nowElapsed: Long
): Boolean = enabled && state.isOpen(nowElapsed)

/**
 * 熔断打开时的留痕**错误码**（按顶开熔断的那次失败原因选）。
 *
 * 复用全项目唯一的选码路径（[RoundFailureCode] → [RoomFailureMapping]）：
 * 只有 TIMEOUT / NETWORK / SERVER_ERROR 才会得到"网络不可用"族的码，
 * 解析失败 / 缺数据 → `API_INVALID_RESPONSE`、限流 → `RATE_LIMITED`、明确拒绝 → `API_REJECTED`；
 * 拿不到原因（null）时如实落 [AppError.UNKNOWN] —— **不许猜"网络不可用"**。
 */
internal fun breakerTraceCodeOf(reason: RoomFailureReason?): AppError =
    RoundFailureCode.appErrorOf(listOfNotNull(reason))

/**
 * 写进 `detail` 的类别文案：只有真实传输故障才允许说"传输故障"。
 *
 * 判据复用 [RoundFailureGate.isTransportFailure]（只读引用，不改那个文件）——
 * 与本项目"归因必须诚实"的口径同一处来源：链路是通的、只是内容不能用（解析失败）
 * 不算传输故障。null 时如实写"原因未定"。
 */
internal fun breakerTraceKindOf(reason: RoomFailureReason?): String = when {
    reason == null -> "原因未定"
    RoundFailureGate.isTransportFailure(reason) -> "传输故障"
    else -> "非传输原因"
}

/**
 * 单批**一次尝试**除 `timeoutSeconds` 之外再给的余量。
 *
 * 与 [MonitoringEngine.fetchOnce] 的 `withTimeout(timeoutSeconds*1000 + 本值)` 同源 ——
 * 预算判据要用到"下一次尝试最坏要花多久"，两处一旦分家，判据就会低估/高估，
 * 所以这里只留一个常量。
 */
internal const val SINGLE_ATTEMPT_SLACK_MS = 5_000L

/**
 * 本轮预算里留给"批次循环**之后**"那段收尾的时间。
 *
 * 收尾包括：末轮比例结算、系统级故障归因与**通知**、健康事件、计数发布、审计落库。
 * 它们都是轻量写库（通知投递在 Tick 互斥锁之外，不计入本轮预算），15s 有充足余量。
 *
 * 为什么必须留：熔断关闭后重试轮没有别的闸门，若让请求把预算吃干，
 * 外层 `withTimeout` 的硬取消会让"这一轮为什么大面积失败"那条通知**永远发不出去** ——
 * 也就是说，用户关掉熔断之后，最需要通知的那种故障反而收不到通知。
 */
internal const val TICK_TAIL_RESERVE_MS = 15_000L

/** 距本轮预算到期还剩多少毫秒；已过期返回 0（绝不返回负数，免得判据反向成立）。 */
internal fun remainingTickBudgetMs(deadlineElapsed: Long, nowElapsed: Long): Long =
    (deadlineElapsed - nowElapsed).coerceAtLeast(0L)

/**
 * 现在还能不能再退避一次、并发出下一次尝试。
 *
 * 三样都要装得下：本次退避 [backoffMs]、下一次尝试的预算 [attemptBudgetMs]、
 * 以及收尾余量 [tailReserveMs]。装不下时调用方按"最后一次尝试"处理
 * （如实计一次失败并返回结果），于是最坏情况是"这一批少重试几次"。
 */
internal fun retryFitsInBudget(
    remainingMs: Long,
    backoffMs: Long,
    attemptBudgetMs: Long,
    tailReserveMs: Long
): Boolean = remainingMs >= backoffMs + attemptBudgetMs + tailReserveMs
