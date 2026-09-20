package com.example.bilimonitor.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.bilimonitor.core.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保活心跳（AlarmManager）。
 *
 * ## 它解决什么
 * 前台服务 + wakelock + 提权（Doze 白名单/待机桶/appops/oom）能大幅降低被杀概率，
 * 但**杀不掉"所有被杀的可能"**：低内存、厂商清理、用户从最近任务划掉，都可能让进程消失。
 * 进程一旦消失，[MonitoringService] 的 `START_STICKY` 只在"系统主动回收"时才保证重启，
 * 厂商 ROM 与 force-stop 都不吃这一套。
 *
 * 所以需要一条**独立于进程之外**的唤醒入口：`AlarmManager` 的闹钟由 system_server 持有，
 * **应用进程已经死了它照样会触发**（前提是没有被 force-stop），触发时系统会拉起应用进程
 * 来投递广播 —— 那就是把监控服务重新拉起来的时机（见 [KeepAliveHeartbeatReceiver]）。
 *
 * ## 为什么用 `…AndAllowWhileIdle`
 * 这两个方法在 Doze 期间也能触发（普通 `set()` 会被推迟到维护窗口甚至更久）。
 * 精确闹钟（`setExactAndAllowWhileIdle`）需要 `SCHEDULE_EXACT_ALARM` 权限，
 * Android 14+ 默认不授予、用户可在设置里关掉 —— 拿不到时**退化为
 * `setAndAllowWhileIdle`**（不精确、但 Doze 下仍会触发），而不是干脆不设。
 *
 * ## 边界（必须如实）
 * - 用户**强行停止**应用后，系统会清掉它的所有闹钟与任务 —— 心跳也救不回来，
 *   只能等用户下次手动打开。这一点无解，界面与文档不许承诺"永不死"。
 * - 心跳间隔是常量（15 分钟）：Doze 下 `…WhileIdle` 类闹钟本来就有最小间隔，
 *   设得更密没有意义（系统会忽略）。
 */
@Singleton
class KeepAliveHeartbeat @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: AppClock,
    /** 排程失败要落错误表（原先只写 logcat，而诊断包不含 logcat → 链断了没人知道）。 */
    private val logDao: com.example.bilimonitor.data.local.dao.LogDao,
    @javax.inject.Named("appScope") private val appScope: kotlinx.coroutines.CoroutineScope
) {

    /** 是否已有排队的闹钟（用 FLAG_NO_CREATE 查，不会创建）。 */
    fun isScheduled(): Boolean = runCatching {
        pendingIntent(PendingIntent.FLAG_NO_CREATE) != null
    }.getOrDefault(false)

    /** 精确闹钟权限是否可用（拿不到也能用不精确的 setAndAllowWhileIdle，只是会晚一些）。 */
    fun canScheduleExact(): Boolean = runCatching {
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == true
    }.getOrDefault(false)

    /**
     * **确保心跳是排着的**：没排上就补排（每个 Tick / 配置同步 / 权限变化时都调一次）。
     *
     * 为什么需要它（代理调研 + AOSP 源码）：用户撤销 `SCHEDULE_EXACT_ALARM` 时，
     * 系统会**删除**所有 `setExact*` / `setAlarmClock` 闹钟；ROM 升级、清数据、被"省电优化"
     * 干掉闹钟也都可能让这条链悄悄断掉 —— 而链一断，进程再被杀就没人拉得起来了。
     * 断链必须能被发现并自愈，不能只靠"设过一次就永远有效"。
     */
    fun ensureScheduled(): Boolean {
        if (isScheduled()) return true
        Log.w(TAG, "心跳不在排程中，补排一次")
        schedule()
        return isScheduled()
    }


    /** 安排下一次心跳（幂等：同一个 PendingIntent 会覆盖上一次）。 */
    fun schedule() {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarm == null) {
            Log.w(TAG, "拿不到 AlarmManager，心跳未安排")
            return
        }
        val intent = pendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = clock.nowWall() + INTERVAL_MS
        val exact = runCatching { alarm.canScheduleExactAlarms() }.getOrDefault(false)
        runCatching {
            if (exact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                // 没有精确闹钟权限：仍然用 …AndAllowWhileIdle，保证 Doze 里能被唤醒
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
            Log.i(TAG, "心跳已安排：${INTERVAL_MS / 60_000} 分钟后（精确=$exact）")
        }.onFailure { e ->
            // 安排失败必须留痕：否则"以为有心跳、其实没有"这件事谁也发现不了。
            // ★ 而且要落**错误表**（代理审查指出原来只有 logcat，而诊断包不含 logcat）：
            //   心跳链断掉意味着"进程被杀后没人拉起"，这是必须能排查的故障。
            Log.e(TAG, "安排心跳失败：${e.message}", e)
            appScope.launch {
                runCatching {
                    logDao.insertAppError(
                        com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                            errorId = com.example.bilimonitor.core.Ids.newId(),
                            operationId = null, occurredAt = clock.nowWall(),
                            errorCode = com.example.bilimonitor.data.local.AppError.BACKGROUND_EXECUTION_RESTRICTED,
                            detail = ("保活心跳排程失败（进程被杀后将无人拉起）：" +
                                "${e.javaClass.simpleName} ${e.message}").take(500)
                        )
                    )
                }
            }
        }
    }

    /** 取消心跳（用户关闭监控时调用；否则会一直唤醒一个不需要运行的应用）。 */
    fun cancel() {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = pendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching { alarm?.cancel(intent) }
        runCatching { intent.cancel() }
        Log.i(TAG, "心跳已取消")
    }

    private fun pendingIntent(flags: Int): PendingIntent? = runCatching {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, KeepAliveHeartbeatReceiver::class.java)
                .setAction(ACTION_HEARTBEAT),
            flags or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    companion object {
        private const val TAG = "KeepAliveHeartbeat"
        const val ACTION_HEARTBEAT = "com.example.bilimonitor.action.KEEP_ALIVE_HEARTBEAT"
        private const val REQUEST_CODE = 0x4B41 // "KA"

        /**
         * 心跳间隔。
         *
         * 15 分钟是刻意选的：Doze 下 `setAndAllowWhileIdle` 一类闹钟的最小间隔本来就在
         * 15 分钟量级（更密会被系统忽略或推迟），而且"恢复被杀的进程"这件事不需要更实时 ——
         * 真正要求实时的是**进程还活着**时的那条轮询循环（那是前台服务 + wakelock 的职责）。
         */
        const val INTERVAL_MS = 15 * 60 * 1000L
    }
}
