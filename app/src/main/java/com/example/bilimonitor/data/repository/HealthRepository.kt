package com.example.bilimonitor.data.repository

import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.dao.MonitoringGapDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import com.example.bilimonitor.data.local.dao.ProblemDao
import com.example.bilimonitor.data.local.dao.RecoverySessionDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.entity.MonitoringGapEntity
import com.example.bilimonitor.data.local.entity.ProblemStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 运行态表达（0.6.47.2）：把"用户选择"与"实际能力"分开，任何降级不静默。
 */
data class OverallHealth(
    val status: MonitorHealthStatus,
    val userMessage: String,
    val detail: String?,
    val keepAliveChoice: BackgroundKeepAliveChoice,
    val monitoringEnabled: Boolean,
    val monitoredCount: Int,
    val liveCount: Int,
    /**
     * 「封禁中」的主播数（用户诉求：与"监控中 N 位 / 其中 M 位在播"并列展示）。
     *
     * ★ 独立性：封禁**不是**一种 `ConfirmedLiveStatus`，所以它既不计入 [liveCount]，
     *   也不影响 [monitoredCount]（被封禁的主播仍然"在监控列表里"，只是请求被服务端拒绝）。
     *   它也不改变 [status]（封禁是**局部**事实，一个房间被封禁不代表监控整体降级）。
     */
    val bannedCount: Int = 0,
    val openSystemGap: MonitoringGapEntity?,
    val activeProblems: List<ProblemStateEntity>,
    val foregroundServiceRunning: Boolean = false
)

private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

@Singleton
class HealthRepository @Inject constructor(
    private val configRepository: ConfigRepository,
    private val streamerDao: StreamerDao,
    private val monitoringGapDao: MonitoringGapDao,
    private val problemDao: ProblemDao,
    private val clock: AppClock,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) {

    /**
     * 无障碍保活的实时可用性。规格 0.6.47.2 第 4 条要求该状态"由系统回调实时更新，
     * 不得缓存为常量"，因此这里订阅系统回调，而不是在进入页面时查一次。
     * 未选择无障碍（含"双保险"选择）时不订阅系统服务，避免无谓开销（用 false 占位）。
     */
    private val accessibilityEnabled: kotlinx.coroutines.flow.Flow<Boolean> =
        configRepository.getConfigFlow().flatMapLatest { config ->
            if (com.example.bilimonitor.background.MonitoringController
                    .accessibilitySelected(config?.backgroundKeepAliveChoice)
            ) {
                com.example.bilimonitor.background.AccessibilityKeepAlive.observe(appContext)
            } else {
                kotlinx.coroutines.flow.flowOf(false)
            }
        }

    fun observeHealth(): Flow<OverallHealth> =
        combine(
            configRepository.getConfigFlow(),
            monitoringGapDao.observeOpenSystemGap(),
            problemDao.observeActive(),
            com.example.bilimonitor.background.MonitoringService.isRunning,
            accessibilityEnabled
        ) { config, gap, problems, serviceRunning, accessibilityOn ->
            Quintuple(config, gap, problems, serviceRunning, accessibilityOn)
        }.let { flow ->
            kotlinx.coroutines.flow.flow {
                flow.collect { (config, gap, problems, serviceRunning, accessibilityOn) ->
                    val streamers = runCatching { streamerDao.listMonitorable() }.getOrDefault(emptyList())
                    // 健康中心「监控状态」里的**在播主播数**（分子）：只统计确认状态为 LIVE 的主播。
                    // ★ 用户定稿：轮播（ROUND）按**未开播**处理，因此不计数 ——
                    //   原实现写成 `LIVE || ROUND`，与 MonitoringEngine.publishCounts 里
                    //   `lastLiveCount`（前台常驻通知 {live} 占位符）的口径不一致，
                    //   于是同一个用户看到两个"在播"数字（通知里 N 位、健康中心 N+M 位）。
                    //   这条口径已在两处统一为"只认 LIVE"，改动前请先确认 MonitoringEngine 那处，
                    //   不要只把其中一处改回 `LIVE || ROUND`（那会让两处再次矛盾）。
                    // 注意分母是下面的 monitoredCount = streamers.size（所有在监控的主播，含轮播与未开播）。
                    val liveCount = streamers.count {
                        it.confirmedLiveStatus == com.example.bilimonitor.data.local.ConfirmedLiveStatus.LIVE
                    }
                    // 「封禁中」计数：解 `streamer.lastError` 里的封禁标记（RoomBanCodec）。
                    // 为什么从 lastError 派生而不是加列：见 RoomBanCodec 的说明 ——
                    // 零 schema 改动、零迁移风险，且备份（整行导出）自动带上。
                    // `RoomBanCodec.decode` 对任何解析失败都返回 null，因此一段普通错误文本
                    // **不会**被算成封禁（宁可少算，也不虚报）。
                    val bannedCount = streamers.count {
                        com.example.bilimonitor.domain.policy.RoomBanCodec.decode(it.lastError) != null
                    }
                    val choice = config?.backgroundKeepAliveChoice ?: BackgroundKeepAliveChoice.UNSET
                    // 保活方式可叠加（用户要求）：分别判断"这一项要不要"，而不是拿枚举做等值比较
                    val wantsForeground = com.example.bilimonitor.background.MonitoringController
                        .foregroundKeepAliveSelected(choice)
                    val wantsAccessibility = com.example.bilimonitor.background.MonitoringController
                        .accessibilitySelected(choice)
                    val enabled = config?.monitoringEnabled ?: false
                    val notificationsOn = com.example.bilimonitor.notify.NotificationPoster.hasPermission(appContext)

                    // 依据 0.6.47.2 的状态映射表：**每一条**降级都要有互不相同的用户可见文案，
                    // 不得显示用户选择所暗示的乐观状态。
                    // 说明：该表的细粒度状态（USER_ACTION_REQUIRED / SYSTEM_RESTRICTED）在唯一枚举
                    // MonitorHealthStatus 中并不存在 —— 这是文档自身的冲突。此处按"不损失功能"落地：
                    // 粗粒度状态复用既有的 DEGRADED/STOPPED（避免为文档冲突做 schema 迁移），
                    // 但 userMessage 逐条区分，用户能据此采取不同动作。
                    val (status, message) = when {
                        choice == BackgroundKeepAliveChoice.UNSET ->
                            MonitorHealthStatus.STOPPED to "尚未选择后台保活方式，点此设置"
                        !enabled ->
                            MonitorHealthStatus.STOPPED to "监控已关闭，点此前往设置开启"
                        // ★ 双保险（用户要求）：两个信号都要看 —— 无障碍失效时提醒重新开启，
                        //   前台通知没起来时也要提醒（只报其中一个会让用户以为"另一个也没问题"）。
                        wantsAccessibility && !accessibilityOn && wantsForeground && !serviceRunning ->
                            MonitorHealthStatus.DEGRADED to "两种保活都未生效：请重新开启无障碍服务，并确认通知权限"
                        wantsAccessibility && !accessibilityOn ->
                            MonitorHealthStatus.DEGRADED to "无障碍保活已失效，请重新开启"
                        wantsForeground && !serviceRunning && !notificationsOn ->
                            // 通知权限被拒 → 即使用户开了前台保活也起不来，必须明确告诉用户去授权
                            MonitorHealthStatus.DEGRADED to "需要通知权限才能后台监控，点此授权"
                        gap != null ->
                            MonitorHealthStatus.DEGRADED to "监控盲区：${gapReasonLabel(gap.reason.name)}，这段时间没有监控到"
                        problems.isNotEmpty() ->
                            MonitorHealthStatus.DEGRADED to "存在问题：${gapReasonLabel(problems.first().problemKey.substringAfter(':').substringBefore(':'))}"
                        wantsForeground && !serviceRunning ->
                            MonitorHealthStatus.DEGRADED to "系统已限制后台运行，监控间隔已自动放慢"
                        choice == BackgroundKeepAliveChoice.NO_GUARANTEE && !serviceRunning ->
                            MonitorHealthStatus.DEGRADED to "后台监控已停止，打开 App 可恢复"
                        choice == BackgroundKeepAliveChoice.NO_GUARANTEE ->
                            // 这一条**保留**限定语：用户自己选了"不启用保活"，说成光秃秃的"监控中"
                            // 等于把"可能漏掉通知"这个已知风险藏起来（0.6.47.2 禁止显示乐观状态）。
                            // 它不是装饰性括号，所以没跟着上面两条一起去掉。
                            MonitorHealthStatus.HEALTHY to "监控中 · 不保证后台持续"
                        serviceRunning ->
                            // 用户要求：这里只留"监控中"，不再跟"（实时）"或
                            // "（实时，无障碍 + 前台通知双保险）"这类括号说明 ——
                            // 横幅要一眼看清，保活方式在设置页「后台保活」里本来就有完整展示。
                            MonitorHealthStatus.HEALTHY to "监控中"
                        else ->
                            MonitorHealthStatus.DEGRADED to "监控未在后台运行，打开应用可恢复"
                    }
                    emit(
                        OverallHealth(
                            status = status,
                            userMessage = message,
                            detail = gap?.reason?.name,
                            keepAliveChoice = choice,
                            monitoringEnabled = enabled,
                            monitoredCount = streamers.size,
                            liveCount = liveCount,
                            bannedCount = bannedCount,
                            openSystemGap = gap,
                            activeProblems = problems,
                            foregroundServiceRunning = serviceRunning
                        )
                    )
                }
            }
        }

    private fun gapReasonLabel(reason: String): String = when (reason) {
        "NETWORK_UNAVAILABLE" -> "网络不可用"
        "API_UNAVAILABLE" -> "接口不可用"
        "DATABASE_BLOCKED" -> "数据库阻塞"
        "MONITORING_STOPPED" -> "监控停止"
        "RUNTIME_CRASH" -> "进程异常"
        "UNKNOWN" -> "未知问题"
        else -> reason
    }
}
