package com.example.bilimonitor.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.data.repository.NotificationRepository
import com.example.bilimonitor.data.repository.RuntimeLockRepository
import dagger.hilt.android.AndroidEntryPoint

/**
 * 省电模式 Worker（原规范 58.1 / 11.2 模式 B）：WorkManager 低频容错任务。
 *
 * 三条纪律：
 *  - **只在真的处于省电模式时跑**：原实现只排除 MANUAL，于是实时前台服务与周期任务并存时
 *    会把所有主播的请求打两遍；
 *  - **结果必须留痕**：`checkOnce` 的返回值与异常都要落 `application_error_log` ——
 *    否则省电模式下引擎持续失败（例如长期 lease_lost）时，用户只看到"数据不再更新"，
 *    诊断包里一点线索都没有；
 *  - 只有**意外异常**才 `Result.retry()`：`ran=false` 这种"正常地没跑"（未开启/租约被占）
 *    重试没有意义，反而会按 WorkManager 退避反复唤醒。
 */
@HiltWorker
class MonitoringWorker @dagger.assisted.AssistedInject constructor(
    @dagger.assisted.Assisted context: Context,
    @dagger.assisted.Assisted params: WorkerParameters,
    private val engine: MonitoringEngine,
    private val configRepository: ConfigRepository,
    private val notificationRepository: NotificationRepository,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val logDao: LogDao,
    private val clock: AppClock
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 读配置也可能抛（Room/DataStore），这层必须自己兜住并留痕 ——
        // 否则 Worker 直接失败、日志里什么都没有，正是 A8 想消除的那类静默（第二轮审查发现）。
        val snapshot = runCatching { configRepository.getSnapshot() }
            .onFailure { e ->
                runCatching {
                    logDao.insertAppError(
                        ApplicationErrorLogEntity(
                            errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                            errorCode = AppError.CONFIG_INVALID,
                            detail = "power saving worker 读取配置失败：${e.message}".take(MAX_ERROR_DETAIL)
                        )
                    )
                }
            }
            .getOrNull()
        if (snapshot?.monitoringEnabled != true || snapshot.mode != MonitoringMode.POWER_SAVING) {
            return Result.success()
        }
        // 这两步是"顺手把积压处理掉"，失败不影响本轮 Tick 的判定，但同样要留痕。
        runCatching { notificationRepository.settleExpiredDeliveries(runtimeLockRepository.runtimeInstanceId) }
            .onFailure { logError("settle expired deliveries failed: ${it.message}") }
        runCatching { notificationRepository.processWindowEnds() }
            .onFailure { logError("process window ends failed: ${it.message}") }

        val outcome = runCatching { engine.checkOnce("workmanager") }
        outcome.exceptionOrNull()?.let { e ->
            logError("power saving tick threw: ${e.message}")
            return Result.retry()
        }
        val result = outcome.getOrNull()
        if (result != null && (!result.ran || result.failures > 0)) {
            logError(
                "power saving tick degraded: ran=${result.ran} reason=${result.reason} " +
                    "checked=${result.checked} failures=${result.failures}"
            )
        }
        return Result.success()
    }

    private suspend fun logError(detail: String) {
        runCatching {
            logDao.insertAppError(
                ApplicationErrorLogEntity(
                    errorId = Ids.newId(),
                    operationId = null,
                    occurredAt = clock.nowWall(),
                    errorCode = AppError.BACKGROUND_EXECUTION_RESTRICTED,
                    detail = detail.take(MAX_ERROR_DETAIL)
                )
            )
        }
    }

    companion object {
        /** 日志 detail 上限，避免一条超长 message 撑大日志表与诊断包。 */
        const val MAX_ERROR_DETAIL = 500
    }
}

/**
 * 开机/应用更新恢复（原规范 11.5）：重新调度监控。
 *
 * 改为"只入队一个一次性 Worker"，不在广播里做实际工作：
 * `onReceive` 的执行窗口由 `goAsync()` 决定（约 10 秒），而 `syncWithConfig()`
 * 内部有 Room 读写与 WorkManager 入队 —— 低端机或首次启动（WorkManager 需要初始化）时
 * 可能超窗被系统判为超时，结果是"开机后监控没被拉起，而且不留任何痕迹"。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // ★ 前台服务必须**在广播上下文里**启动（第二轮审查发现的问题）：
        //   Android 12+ 禁止后台启动前台服务，`ACTION_BOOT_COMPLETED` 是豁免场景之一，
        //   但这个豁免绑定"在广播接收器里发起启动"—— 把 startForegroundService 挪进
        //   WorkManager 的 Worker 会丢掉豁免（Worker 跑在 JobScheduler 上下文里），
        //   结果是开机后实时监控起不来、静默降级成 15 分钟周期任务。
        //   服务自身在 onStartCommand 里会读配置：监控未开启时直接 START_NOT_STICKY 退出。
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, MonitoringService::class.java)
            )
        }.onFailure {
            android.util.Log.w("BootReceiver", "开机启动监控服务失败：${it.message}")
        }
        // DB 同步与省电模式调度交给 Worker（不受广播 ~10 秒窗口限制）
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                BootSyncWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BootSyncWorker>().build()
            )
        }
    }
}

/** 承接开机后的监控重新调度：真正的 IO 在 Worker 里做，不受广播窗口限制。 */
@HiltWorker
class BootSyncWorker @dagger.assisted.AssistedInject constructor(
    @dagger.assisted.Assisted context: Context,
    @dagger.assisted.Assisted params: WorkerParameters,
    private val controller: MonitoringController,
    private val logDao: LogDao,
    private val clock: AppClock
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = runCatching { controller.syncWithConfig() }
        outcome.exceptionOrNull()?.let { e ->
            runCatching {
                logDao.insertAppError(
                    ApplicationErrorLogEntity(
                        errorId = Ids.newId(),
                        operationId = null,
                        occurredAt = clock.nowWall(),
                        errorCode = AppError.BACKGROUND_EXECUTION_RESTRICTED,
                        detail = "boot sync failed: ${e.message}".take(MonitoringWorker.MAX_ERROR_DETAIL)
                    )
                )
            }
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "monitor_boot_sync"
    }
}
