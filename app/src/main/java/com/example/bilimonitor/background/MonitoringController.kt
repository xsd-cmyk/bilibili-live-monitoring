package com.example.bilimonitor.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.domain.policy.FreshnessPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 统一监控控制层（原规范 41）：任何页面/通知动作/BootReceiver 都不得直接控制 Service，
 * 一律经由本控制器流转。
 */
@Singleton
class MonitoringController @Inject constructor(
    private val configRepository: ConfigRepository,
    private val engine: MonitoringEngine,
    @ApplicationContext private val context: Context,
    @Named("appScope") private val appScope: CoroutineScope,
    private val logDao: com.example.bilimonitor.data.local.dao.LogDao,
    private val clock: com.example.bilimonitor.core.AppClock,
    /** 保活心跳：进程被杀后由闹钟把应用唤醒并重新拉起服务（见 KeepAliveHeartbeatReceiver）。 */
    private val heartbeat: KeepAliveHeartbeat,
    /**
     * 高级保活仓库：`ensureRunning` 在"后台启动被拒"时会尝试用 root 身份代启动
     * （uid 0 对后台启动限制与导出检查都无条件放行，是本项目能拿到的最后一条路）。
     */
    private val advancedKeepAlive:
        com.example.bilimonitor.data.repository.AdvancedKeepAliveRepository
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    init {
        // 无障碍保活失效/恢复必须留下 HealthEvent（0.6.47.2 第 2 条"任何降级都不允许静默发生"）。
        //
        // 订阅范围收窄：只有当用户确实选择了 ACCESSIBILITY 时才订阅系统回调。
        // 原实现是无条件常驻订阅，而 AccessibilityKeepAlive.observe 内部用
        // 主线程 Handler 每 10 秒轮询一次系统服务 —— 选"前台通知"或从未开启无障碍的用户
        // 也会一直每 10 秒被唤醒一次（distinctUntilChanged 只抑制下游发射，不阻止轮询本身）。
        // 写法与 HealthRepository.accessibilityEnabled 保持一致：配置流 + flatMapLatest。
        appScope.launch {
            runCatching {
                configRepository.getConfigFlow()
                    .map { it?.backgroundKeepAliveChoice }
                    .distinctUntilChanged()
                    .flatMapLatest { choice ->
                        if (!accessibilitySelected(choice)) {
                            kotlinx.coroutines.flow.flowOf(null)
                        } else {
                            com.example.bilimonitor.background.AccessibilityKeepAlive.observe(context)
                        }
                    }
                    .collect { enabled ->
                        // null = 当前未选择无障碍，无需记录（也不该订阅系统回调）。
                        if (enabled == null) return@collect
                        val snapshot = runCatching { configRepository.getSnapshot() }.getOrNull() ?: return@collect
                        if (!snapshot.monitoringEnabled) return@collect
                        runCatching {
                            logDao.insertHealthEvent(
                                com.example.bilimonitor.data.local.entity.HealthEventEntity(
                                    healthEventId = com.example.bilimonitor.core.Ids.newId(),
                                    component = "KEEP_ALIVE:ACCESSIBILITY",
                                    fromStatus = null,
                                    toStatus = if (enabled) MonitorHealthStatus.HEALTHY
                                    else MonitorHealthStatus.DEGRADED,
                                    occurredAt = clock.nowWall(),
                                    episodeId = null
                                )
                            )
                        }
                    }
            }.onFailure { e ->
                // ★ 订阅本身失败必须留痕（第二轮审查发现）：原先这层 runCatching 什么都不做，
                //   配置流一旦抛错，保活健康事件就**永久停写**而且看不出原因 ——
                //   正是"任何降级都不允许静默发生"要禁止的情形。
                android.util.Log.e("MonitoringController", "无障碍保活观察订阅失败：${e.message}", e)
                runCatching {
                    logDao.insertAppError(
                        com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                            errorId = com.example.bilimonitor.core.Ids.newId(),
                            operationId = null,
                            occurredAt = clock.nowWall(),
                            errorCode = com.example.bilimonitor.data.local.AppError.UNKNOWN,
                            detail = "无障碍保活观察订阅失败：${e.message}".take(500)
                        )
                    )
                }
            }
        }
    }

    fun refreshNow() = engine.refreshNowAsync("manual")

    /** 按当前配置同步后台执行方式（应用启动 / 配置变更 / 开机时调用）。 */
    suspend fun syncWithConfig() {
        val snapshot = configRepository.getSnapshot() ?: return
        when {
            !snapshot.monitoringEnabled || snapshot.mode == MonitoringMode.MANUAL -> {
                stopRealtime()
                cancelPowerSavingWork()
            }
            // 实时意图：前台常驻通知、无障碍保活、以及两者叠加（双保险）都以实时模式运行。
            // 无障碍服务是"提高存活率"的增强手段，不是替代实时循环的方式 ——
            // 原实现只对 FOREGROUND_NOTIFICATION 启动前台服务，选择无障碍时
            // 落到 else 分支被降级成 15 分钟以上的周期任务，而界面仍显示"监控中（实时）"，
            // 属于 0.6.47.2 明令禁止的"伪装正常"。
            snapshot.mode == MonitoringMode.REALTIME &&
                foregroundKeepAliveSelected(snapshot.backgroundKeepAliveChoice) -> {
                cancelPowerSavingWork()
                startRealtime()
            }
            else -> {
                // 省电模式，或选择了非前台保活的实时意图降级为周期任务。
                stopRealtime()
                schedulePowerSaving(snapshot.intervalSeconds)
            }
        }
        // 心跳的排程只跟"实时模式 + 监控开着"走：
        //  · 实时模式：它是进程被杀后唯一的恢复入口 → 必须排；
        //  · 省电模式：WorkManager 的周期任务本身就是持久化的（进程死了 JobScheduler 也会拉起应用），
        //    再排一个 15 分钟闹钟只是白唤醒（代理审查指出：原来省电/手动模式也会排）；
        //  · 关掉监控：取消，否则会一直唤醒一个不需要运行的应用。
        if (snapshot.monitoringEnabled && snapshot.mode == MonitoringMode.REALTIME) {
            // ensureScheduled：已经在排就不重复设（避免每次配置变更都把触发时间往后推）
            heartbeat.ensureScheduled()
        } else {
            heartbeat.cancel()
        }
    }

    /**
     * 心跳恢复：确认监控该在跑，不在跑就尝试拉起来。
     *
     * 供 [KeepAliveHeartbeatReceiver] 调用（**进程可能是刚被它唤醒的**），
     * 也可以被其它"发现服务不在了"的路径复用。
     *
     * 返回**结果码**而不是 Boolean：调用方要把它写进审计/错误日志，
     * "为什么没拉起来"（配置读不到 / 被系统拒绝 / 本来就不该跑）必须能区分开。
     *
     * @return already_running / started_realtime / power_saving_rescheduled /
     *         monitoring_disabled / config_unavailable / start_rejected:&lt;异常类&gt; / manual_mode
     */
    suspend fun ensureRunning(reason: String): String {
        if (MonitoringService.isRunning.value) return "already_running"
        val snapshot = runCatching { configRepository.getSnapshot() }.getOrNull()
            ?: return "config_unavailable"
        if (!snapshot.monitoringEnabled) return "monitoring_disabled"
        if (snapshot.mode == MonitoringMode.MANUAL) return "manual_mode"
        if (snapshot.mode != MonitoringMode.REALTIME) {
            // 省电模式：由 WorkManager 负责（它自己是持久化的，不需要我们再拉）
            schedulePowerSaving(snapshot.intervalSeconds)
            return "power_saving_rescheduled"
        }
        android.util.Log.i("MonitoringController", "心跳恢复尝试（$reason）：拉起前台服务")
        return runCatching {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
            "started_realtime"
        }.getOrElse { e ->
            // 后台启动被拒（Android 12+ 的 FGS 限制）时如实回报，由调用方留痕。
            // 官方豁免之一是"已加入电池优化白名单"——设置页里有一键申请入口。
            // ★ root 档还有一条兜底（代理调研）：让 uid 0 代我们启动 —— AOSP 里只有 root/system
            //   对"后台启动限制"与"组件导出检查"无条件放行。
            //   Shizuku（uid 2000）做不到：我们的服务是 exported=false，shell 会被导出检查挡住；
            //   而为了保活把服务改成 exported 会让任何应用都能反复拉起它（耗电/骚扰面），不做。
            val viaRoot = runCatching { advancedKeepAlive.startServiceAsRoot() }.getOrDefault(false)
            if (viaRoot) "started_via_root" else "start_rejected:${e.javaClass.simpleName}"
        }
    }

    fun startRealtime() {
        val intent = Intent(context, MonitoringService::class.java)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // 后台启动被系统拒绝时降级为省电调度（0.6.47.2：不得静默伪装正常）。
            // **同时必须留痕**：否则用户选了"实时"却按 15 分钟跑，诊断包里查不到原因
            // （只能从"前台服务未运行"间接推断），异常类型本身也被丢掉。
            appScope.launch {
                runCatching {
                    logDao.insertAppError(
                        com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                            errorId = com.example.bilimonitor.core.Ids.newId(),
                            operationId = null,
                            occurredAt = clock.nowWall(),
                            errorCode = com.example.bilimonitor.data.local.AppError.BACKGROUND_EXECUTION_RESTRICTED,
                            detail = "startForegroundService rejected: ${e.message}".take(MAX_ERROR_DETAIL)
                        )
                    )
                }
                val snapshot = runCatching { configRepository.getSnapshot() }.getOrNull()
                schedulePowerSaving(snapshot?.intervalSeconds ?: 300)
            }
        }
    }

    fun stopRealtime() {
        context.stopService(Intent(context, MonitoringService::class.java))
    }

    /**
     * 让正在运行的常驻通知立即刷新内容（用户改了通知文案 / 点了恢复默认）。
     *
     * 走 `startService + ACTION_REFRESH_NOTIFICATION` 而不是"停掉再起"：
     * 前台服务中途停掉会有一段时间不在前台，系统可能顺手把进程降级甚至回收；
     * 服务在自己的 onStartCommand 里重发通知，通知栏不会出现空档。
     * 服务没在跑时这个调用也是无害的：系统会把服务拉起来，而服务自己会先校验配置
     * （`monitoringEnabled && mode == REALTIME`）—— 不满足就撤掉前台、`stopSelf()`，
     * 不会留下"显示监控中但一个 Tick 都不跑"的假活（见 MonitoringService 的 ACTION_REFRESH 分支）。
     */
    fun refreshForegroundNotification() {
        runCatching {
            context.startService(
                Intent(context, MonitoringService::class.java)
                    .setAction(MonitoringService.ACTION_REFRESH_NOTIFICATION)
            )
        }.onFailure {
            android.util.Log.w("MonitoringController", "刷新常驻通知失败：${it.message}")
        }
    }

    /**
     * 让 wakelock 立刻跟上「锁屏继续活跃」开关。
     *
     * 为什么要有它：[MonitoringService] 每轮 Tick 会同步一次，但监控间隔最长 3600 秒 ——
     * 用户刚拨开开关却要等一小时才生效，体验上就是"开关没用"。
     *
     * **服务没在跑时什么都不做**：没有监控循环时 wakelock 毫无意义，
     * 为了同步一个锁把前台服务拉起来是荒谬的（`isRunning` 是"服务真的在跑"的判据，
     * 见 MonitoringService 里那句"界面必须反映 FGS 是否真的在跑"）。
     */
    fun syncKeepAliveNow() {
        if (!MonitoringService.isRunning.value) return
        runCatching {
            context.startService(
                Intent(context, MonitoringService::class.java)
                    .setAction(MonitoringService.ACTION_SYNC_KEEP_ALIVE)
            )
        }.onFailure { e ->
            // 与 startRealtime 一致：被系统拒绝必须**落库**（原来只写 logcat，而诊断包不含 logcat，
            // 用户与排查者都看不到原因）—— 用户的"已开启锁屏继续活跃"此时是假的。
            android.util.Log.w("MonitoringController", "同步 wakelock 失败：${e.message}")
            appScope.launch { logStartRejected("同步 wakelock（锁屏继续活跃）", e) }
        }
    }

    /** 记录一次"后台启动服务被系统拒绝"（原因必须能进诊断包）。 */
    private suspend fun logStartRejected(what: String, e: Throwable) {
        runCatching {
            logDao.insertAppError(
                com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                    errorId = com.example.bilimonitor.core.Ids.newId(),
                    operationId = null, occurredAt = clock.nowWall(),
                    errorCode = com.example.bilimonitor.data.local.AppError.BACKGROUND_EXECUTION_RESTRICTED,
                    detail = ("$what：startService 被系统拒绝（${e.javaClass.simpleName}）：${e.message}。" +
                        "可在设置页「高级保活」里申请「忽略电池优化」以提高允许度。").take(500)
                )
            )
        }
    }

    fun schedulePowerSaving(intervalSeconds: Int) {
        // 周期换算**不再写在这里**：界面判「数据过期」时必须知道真实的调度周期
        // （省电模式被 clamp 到 15–240 分钟，配置里的 60 秒根本不是实际周期），
        // 两处各写一份 clamp 必然会漂移，而漂移的表现就是"永远显示过期"。
        // 因此换算放在 domain.policy.FreshnessPolicy（纯函数、可单测），这里反过来调用它。
        val minutes = FreshnessPolicy.powerSavingPeriodMinutes(intervalSeconds)
        val request = PeriodicWorkRequestBuilder<MonitoringWorker>(minutes.toLong(), TimeUnit.MINUTES)
            // 设备离线时不要让 Worker 白跑一轮：跑起来也只会把全部主播记成传输失败，
            // 连续 3 次就打开熔断、写"网络不可用"问题并给用户发通知 ——
            // 而用户只是没网，这条通知对他而言是废话。交给 WorkManager 等联网后再派发。
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPowerSavingWork() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "monitor_power_saving"

        /** 日志 detail 上限（与 MonitoringWorker 保持一致）。 */
        const val MAX_ERROR_DETAIL = 500

        /**
         * 该选择是否"要前台常驻通知"。
         *
         * 抽成函数是因为保活方式从"三选一"变成了"可叠加"（用户要求：无障碍打开之后
         * 依旧可以同时开启前台通知保活）。判断如果散落在多处，新增一个枚举值就很容易漏改其中一处 ——
         * 而漏改的表现是"某项保活静默不生效"，正是本项目一直在防的那类问题。
         */
        fun foregroundKeepAliveSelected(choice: BackgroundKeepAliveChoice?): Boolean =
            choice == BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION ||
                choice == BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND

        /** 该选择是否"要无障碍保活"（叠加模式下两者都为真）。 */
        fun accessibilitySelected(choice: BackgroundKeepAliveChoice?): Boolean =
            choice == BackgroundKeepAliveChoice.ACCESSIBILITY ||
                choice == BackgroundKeepAliveChoice.ACCESSIBILITY_AND_FOREGROUND
    }
}
