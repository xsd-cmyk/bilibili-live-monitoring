package com.example.bilimonitor.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 心跳接收器：**进程已经死了也会被系统唤醒**，这是"被杀之后还能回来"的唯一入口。
 *
 * 被唤醒后做三件事（顺序有讲究）：
 *  1. **先续订下一次心跳** —— 后面的恢复动作无论成败，心跳链都不能断；
 *  2. 看看监控服务在不在跑（`MonitoringService.isRunning`；进程是新的，所以它一定是 false，
 *     除非服务真的活着），不在就尝试拉起（[MonitoringController.ensureRunning]）；
 *  3. **如实留痕**：恢复了写审计（"发生过什么"），没恢复成写错误日志（`ApplicationErrorLogEntity`），
 *     因为"该在跑却没拉起来"正是需要排查的故障。
 *
 * 注意：广播有 ~10 秒执行预算，所以这里用 `goAsync()` + 超时，绝不会因为读库慢而 ANR。
 */
@AndroidEntryPoint
class KeepAliveHeartbeatReceiver : BroadcastReceiver() {

    @Inject lateinit var heartbeat: KeepAliveHeartbeat
    @Inject lateinit var controller: MonitoringController
    @Inject lateinit var logDao: LogDao
    @Inject lateinit var clock: AppClock

    override fun onReceive(context: Context, intent: Intent) {
        // ★ 精确闹钟权限被撤销/重新授予时，系统会**删掉**所有 setExact* 闹钟（AOSP 明确行为）——
        //   不接这个广播，心跳链就会静默断掉。这个广播本身也是"允许启动前台服务"的时机，
        //   所以顺手把恢复也做一遍。
        if (intent.action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    Log.i(TAG, "精确闹钟权限变化：重排心跳")
                    heartbeat.schedule()
                    val result = withTimeoutOrNull(8_000L) {
                        controller.ensureRunning("exact_alarm_changed")
                    } ?: "timeout"
                    Log.i(TAG, "权限变化后的恢复结果=$result")
                    if (result != "already_running") record(result)
                } catch (e: Throwable) {
                    Log.e(TAG, "处理精确闹钟权限变化失败：${e.message}", e)
                } finally {
                    runCatching { pending.finish() }
                }
            }
            return
        }
        // ★ 三条"零成本恢复入口"（代理调研）：改时间/时区/语言时系统会发这些广播，
        //   它们在 AOSP 允许原因码里同样允许启动前台服务，且允许静态注册 ——
        //   与闹钟心跳互为独立通道（闹钟可能被系统删掉，这些不会）。
        if (intent.action in RECOVERY_BROADCASTS) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val result = withTimeoutOrNull(8_000L) {
                        controller.ensureRunning("broadcast:${intent.action}")
                    } ?: "timeout"
                    Log.i(TAG, "系统广播恢复入口（${intent.action}）结果=$result")
                    if (result != "already_running" && result != "monitoring_disabled" &&
                        result != "manual_mode"
                    ) {
                        record(result)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "处理系统广播恢复失败：${e.message}", e)
                } finally {
                    runCatching { pending.finish() }
                }
            }
            return
        }
        if (intent.action != KeepAliveHeartbeat.ACTION_HEARTBEAT) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                // ① 先续订：这一步失败=心跳链断掉，必须最先做
                heartbeat.schedule()
                // ② 恢复（读库 + 启动服务，8 秒预算内完成；超时就放弃并留痕）
                val result = withTimeoutOrNull(8_000L) { controller.ensureRunning("heartbeat") }
                    ?: "timeout"
                Log.i(TAG, "心跳触发：恢复结果=$result")
                // 只有**真的发生了什么**才写审计（代理审查指出：原来连"本来就没事"也每 15 分钟写一条，
                // 约 96 行/天的纯噪声，而 audit_log 目前没有保留策略、也不进诊断包）。
                if (result != "already_running") record(result)
            } catch (e: Throwable) {
                Log.e(TAG, "心跳处理失败：${e.message}", e)
                runCatching { record("threw: ${e.javaClass.simpleName}") }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private suspend fun record(result: String) {
        runCatching {
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "SYSTEM",
                    action = "KEEP_ALIVE_HEARTBEAT", targetType = "app",
                    targetStableId = null, occurredAt = clock.nowWall(),
                    detailJson = "{\"result\":\"$result\"}"
                )
            )
        }
        // "本该在跑却没能拉起来"是故障，要进错误表（其余结果只是审计）
        if (result.startsWith("start_rejected") || result == "timeout" ||
            result.startsWith("threw") || result == "config_unavailable"
        ) {
            runCatching {
                logDao.insertAppError(
                    ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = AppError.BACKGROUND_EXECUTION_RESTRICTED,
                        detail = "保活心跳未能恢复监控服务（$result）。" +
                            "常见原因：应用被系统限制后台启动、或未加入电池优化白名单。" +
                            "可在设置页「高级保活」里申请「忽略电池优化」。".take(500)
                    )
                )
            }
        }
    }

    private companion object {
        const val TAG = "KeepAliveHeartbeat"

        /** 见 AndroidManifest 里对应 intent-filter 的说明。 */
        val RECOVERY_BROADCASTS = setOf(
            android.content.Intent.ACTION_TIME_CHANGED,
            android.content.Intent.ACTION_TIMEZONE_CHANGED,
            android.content.Intent.ACTION_LOCALE_CHANGED
        )
    }
}
