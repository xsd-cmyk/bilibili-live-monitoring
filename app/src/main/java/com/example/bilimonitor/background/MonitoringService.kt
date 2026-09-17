package com.example.bilimonitor.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.bilimonitor.MainActivity
import com.example.bilimonitor.R
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.notify.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 实时模式前台服务（原规范 237）：常驻通知 + 周期 Tick。
 * 用户可在通知栏看到"正在监控"常驻通知；服务由 MonitoringController 唯一控制。
 */
@AndroidEntryPoint
class MonitoringService : LifecycleService() {

    @Inject lateinit var engine: MonitoringEngine
    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var logDao: com.example.bilimonitor.data.local.dao.LogDao
    @Inject lateinit var notificationSettingsRepository:
        com.example.bilimonitor.data.repository.NotificationSettingsRepository

    private var loopJob: kotlinx.coroutines.Job? = null

    /** 通知模板缓存：`onStartCommand` 有 5 秒时限，不能在那里挂起读 DataStore。 */
    @Volatile
    private var cachedTemplate = com.example.bilimonitor.data.repository.ForegroundNotificationTemplate()

    /** 上一次真正发出去的数字，用于判断要不要重发通知（内容没变就不动，避免无谓刷新）。 */
    @Volatile
    private var postedSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        // ★ 这里**不能**置 isRunning=true（复查发现的缺陷）：服务也可能只是被
        //   ACTION_REFRESH_NOTIFICATION 拉起来刷新一次通知，那时它压根不跑监控循环，
        //   而 isRunning 是界面/诊断/健康横幅判断"到底在不在监控"的唯一依据 ——
        //   在 onCreate 里置真，等于让"只刷了一下通知"显示成"监控中"。
        //   现在只有真的进了前台（startInForeground 成功）才算数。
        // 先把用户自定义的模板读进缓存，之后再发通知（首帧仍可能用默认值，见 notificationContent 注释）
        lifecycleScope.launch { refreshNotificationContent() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // 用户在常驻通知上点「停止监控」（原规范 237）。
            // 顺序很关键：**必须先落库再停服务**。
            // 原实现在 stopSelf() 之后才用 lifecycleScope 写配置，而 onDestroy 会取消该 scope，
            // Room 事务可能被回滚 → 配置仍是 enabled → 下次冷启动 syncWithConfig 又把服务拉起，
            // 用户会觉得"关不掉"。
            loopJob?.cancel()
            lifecycleScope.launch {
                runCatching { configRepository.setMonitoringEnabled(false) }
                    .onFailure {
                        // 落库失败也必须停服务，否则通知上的按钮等于没反应
                        android.util.Log.w("MonitoringService", "停止监控时写入配置失败：${it.message}")
                    }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            // 用户在设置里改了通知内容（或点了恢复默认）：重读模板并重发通知。
            // 重发而不是"先停后起"——前台服务必须保持前台，中间不能有空档。
            //
            // ★ 但必须先确认**这个服务本来就该在跑**（复查发现的缺陷）：这条路径原先不校验配置，
            //   于是"监控已关闭 / 省电或手动模式"下点一下"保存通知内容"，系统就会把服务拉起来、
            //   挂上"主播监控运行中"的常驻通知，并把 isRunning 置真 —— 界面、健康横幅与诊断随即
            //   显示"监控中"，而实际上一个 Tick 都不会跑（服务假活，与"不得显示乐观状态"冲突）。
            //   判据与 runLoop 开头逐字一致，避免两处口径分叉。
            lifecycleScope.launch {
                val snapshot = runCatching { configRepository.getSnapshot() }.getOrNull()
                if (snapshot?.monitoringEnabled != true || snapshot.mode != MonitoringMode.REALTIME) {
                    // 不该跑：刷新无从谈起，撤掉前台状态，不留下任何"正在监控"的假象
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isRunning.value = false
                    stopSelf()
                    return@launch
                }
                refreshNotificationContent()
                runCatching { startInForeground() }
                // 该跑就得真跑：这个服务可能正是被这次刷新拉起来的，循环不在就补上
                if (loopJob == null || loopJob?.isActive != true) {
                    loopJob = lifecycleScope.launch { runLoop() }
                }
            }
            return START_STICKY
        }
        startInForeground()
        if (loopJob == null || loopJob?.isActive != true) {
            loopJob = lifecycleScope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private suspend fun runLoop() {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            // ★ 循环体整体要有异常边界（台账 H19-A1）：
            //   `getSnapshot()` 是可抛的（Room 调用 + quietHoursRepository.current() 按设计
            //   把异常向上抛），原先它和 delay 都在 try 之外 —— 一旦抛出：
            //     1) 循环直接结束，`stopSelf()` 被跳过 → 前台通知留着、isRunning 仍为 true，
            //        界面显示"监控中（实时）"却一个 Tick 都不会再跑（服务假活）；
            //     2) 异常经 lifecycleScope（无 CoroutineExceptionHandler）直达默认处理器，
            //        CrashRecorder 记一笔后杀掉进程 —— 若成因持久（DataStore 损坏），
            //        就是"每次冷启动拉起前台服务 → 立刻崩"的崩溃循环。
            //   现在：单轮失败记一条日志后继续下一轮，退出循环时一定 stopSelf()。
            try {
                val snapshot = configRepository.getSnapshot()
                if (snapshot?.monitoringEnabled != true || snapshot.mode != MonitoringMode.REALTIME) {
                    break
                }
                // ★ 引擎异常必须**穿透到下面的失败分支**，绝不在这里吞（缺陷：前台服务静默吞掉引擎异常）。
                //   `checkOnce` 只自吞 TimeoutCancellationException（见 MonitoringEngine），其余异常
                //   （`getSnapshot()`、`ensureLease` 引发的 Room 异常、`quietHoursRepository.current()`
                //   按设计向上抛）会原样冒出来。原实现在这里放了一个**空 catch**，两条后果：
                //     ① 不写日志、不打 Log.e —— 现场零线索，诊断包里也查不出任何东西；
                //     ② 更严重：它让紧随其后的 `consecutiveFailures = 0` 照常执行，失败计数
                //        **永远不可能增长**，`MAX_CONSECUTIVE_LOOP_FAILURES` 看门狗在这条路径上
                //        完全失效 —— isRunning 保持 true、常驻通知一直显示"监控中"，实际零 Tick，
                //        与项目硬规则"任何降级不允许静默发生"直接冲突。
                //   现在不捕获：异常落到下面同一套处理（Log.e + 写 application_error_log +
                //   consecutiveFailures++ + 到阈值如实退出服务），与 :122 起的写法完全一致；
                //   取消仍由外层 `catch (e: CancellationException) { throw e }` 原样直通。
                engine.checkOnce("fgs")
                // 数字变了就重发通知（用户自定义文案里的 {count}/{live} 要跟着走）。
                // 用"签名"比较而不是每次都 startForeground：避免每轮无谓刷新通知。
                refreshNotificationIfChanged()
                consecutiveFailures = 0
                delay(snapshot.intervalSeconds * 1000L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 取消是正常控制流，必须原样向上传播
            } catch (e: Exception) {
                // 连续失败不能无限"假活"（第二轮审查）：原先无论失败多少次都只是每 30 秒重试一次，
                // isRunning 仍为 true、界面显示"监控中"，实际一个 Tick 都没跑成。
                // 到阈值就如实退出服务（通知消失 + isRunning=false），把状态交给界面与省电调度。
                consecutiveFailures++
                android.util.Log.e(
                    "MonitoringService",
                    "监控循环单轮失败（连续 $consecutiveFailures 次），继续下一轮：${e.message}", e
                )
                runCatching {
                    logDao.insertAppError(
                        com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                            errorId = com.example.bilimonitor.core.Ids.newId(),
                            operationId = null,
                            occurredAt = System.currentTimeMillis(),
                            errorCode = com.example.bilimonitor.data.local.AppError.UNKNOWN,
                            detail = "前台服务监控循环失败（第 $consecutiveFailures 次）：${e.message}".take(500)
                        )
                    )
                }
                if (consecutiveFailures >= MAX_CONSECUTIVE_LOOP_FAILURES) {
                    android.util.Log.e("MonitoringService", "监控循环连续失败达到阈值，停止前台服务")
                    break
                }
                runCatching { delay(30_000L) }
            }
        }
        stopSelf()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE 是 API 34 才引入的类型位；
        // API 31–33 上传该值会被系统拒绝，因此低版本传 0（表示未声明类型）。
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            // specialUse 而非 dataSync：监控需要长期常驻，dataSync 在 Android 15+
            // 受「24 小时窗口内累计 6 小时」限制，到点会被系统强制停止（0.6.47.2 / 文档 0.5）。
            ServiceCompat.startForeground(this, SERVICE_NOTIFICATION_ID, notification, fgsType)
            // 真进了前台才算"在跑"（与 onCreate 不再置真相呼应）
            isRunning.value = true
        } catch (e: Exception) {
            // 系统仍可能拒绝前台启动（权限被收回、后台启动限制、厂商 ROM 策略）：
            // 不得崩溃，静默停止服务；下次打开 App 或开机时会再次尝试。
            // 降级不会静默——HealthRepository 会发现 serviceRunning=false 并如实显示。
            isRunning.value = false
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 「停止监控」动作（原规范 237）：让用户不进入应用也能停掉监控。
        val stopIntent = Intent(this, MonitoringService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val content = notificationContent()
        // 记下这次真正发出去的内容，供下一轮判断"要不要重发"
        postedSignature = content.first + "\u0000" + content.second
        return NotificationCompat.Builder(this, NotificationChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.first)
            .setContentText(content.second)
            // 用户可能写了较长正文：用 BigTextStyle 让展开后完整显示，而不是被截断
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.second))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止监控", stopPending)
            .build()
    }

    /**
     * 通知的标题与正文（用户要求：内容可自定义）。
     *
     * 两个细节：
     *  1. 模板来自 DataStore，**同步读缓存**：`onStartCommand` 必须在 5 秒内 `startForeground`，
     *     这里不能挂起等 IO。缓存由 [refreshNotificationContent] 在协程里刷新，
     *     首次启动时用默认值先发出去（宁可先显示默认文案，也不能因为读设置而错过 FGS 时限）。
     *  2. 主播数取自引擎最近一次 Tick 的快照（[MonitoringEngine.lastMonitoredCount]）——
     *     进程刚起来还没跑过 Tick 时是 null，此时占位符渲染成 `—` 而不是谎报 0。
     */
    private fun notificationContent(): Pair<String, String> {
        val template = cachedTemplate
        val monitored = engine.lastMonitoredCount
        val live = engine.lastLiveCount
        return com.example.bilimonitor.data.repository.NotificationTemplate.render(template.title, monitored, live) to
            com.example.bilimonitor.data.repository.NotificationTemplate.render(template.text, monitored, live)
    }

    /** 从 DataStore 刷新模板缓存（协程内调用；失败保留上一次的值）。 */
    private suspend fun refreshNotificationContent() {
        runCatching { notificationSettingsRepository.current() }
            .onSuccess { cachedTemplate = it }
    }

    /**
     * 数字或模板变化时重发常驻通知。
     *
     * 只在**渲染结果真的变了**时才重发：前台通知每轮都刷新没有意义，
     * 而且部分 ROM 会对频繁更新常驻通知做限流。
     */
    private fun refreshNotificationIfChanged() {
        val content = notificationContent()
        val signature = content.first + "\u0000" + content.second
        if (signature == postedSignature) return
        runCatching { startInForeground() }
            .onFailure { android.util.Log.w("MonitoringService", "刷新常驻通知失败：${it.message}") }
    }

    override fun onDestroy() {
        isRunning.value = false
        loopJob?.cancel()
        // ★ 主动释放监控租约（台账 H19-A12）：租约原先从不主动释放，只能等 90 秒 TTL 过期回收，
        //   于是"停止后 90 秒内又启动"（快速重启/安装更新/START_STICKY 被系统拉起）时，
        //   第一次 Tick 因为旧租约仍有效而直接 lease_lost（恢复检查会话还会被结算成 FAILED），
        //   要再等一个完整间隔才自愈。这里同步释放，让下一次启动的第一次 Tick 立刻可用。
        //   用 runBlocking 是有意的：onDestroy 返回后进程随时可能被杀，挂起的协程未必来得及跑。
        //   但必须带超时（第二轮审查）：Room 执行器被大事务（清理/恢复/导出）占满时，
        //   无上限的 runBlocking 会变成一次主线程 ANR。释放租约只是优化（不做也只是等 90 秒 TTL），
        //   不值得为它冒 ANR 的风险。
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(2_000L) { engine.releaseLease() }
        }
        super.onDestroy()
    }

    companion object {
        /**
         * 常驻通知 id。
         *
         * ★ 必须**大于** [com.example.bilimonitor.data.repository.NotificationOutboxWriter.MAX_NOTIFICATION_ID]：
         *   主播通知的 id 由分配器从 1 开始按 `MAX+1` 递增发放（注册表只增不减），
         *   任何落在 `1..2_000_000` 里的固定 id 迟早会被某条主播通知占用 ——
         *   而 `notify` 的身份键是 (包名, tag, id)，两者完全相同时通知会**互相顶掉**：
         *   那条直播通知被常驻通知覆盖，此后每次 `stopForeground(STOP_FOREGROUND_REMOVE)`
         *   还会把它一起删掉（反向则用户失去"停止监控"入口），而库里仍记着"已送达"。
         *   原先这里是 1001，正好落在区间内部（复查发现的缺陷）。
         *   自救援通知同理取 2_000_001，见 [com.example.bilimonitor.background.LauncherEntryRescueReceiver]。
         */
        const val SERVICE_NOTIFICATION_ID = 2_000_002
        const val ACTION_STOP = "com.example.bilimonitor.action.STOP_MONITORING"

        /** 设置里改了通知内容后，用它让常驻通知立即刷新（见 onStartCommand 的处理）。 */
        const val ACTION_REFRESH_NOTIFICATION = "com.example.bilimonitor.action.REFRESH_MONITORING_NOTIFICATION"

        /** 前台循环连续失败到该次数就停止服务，避免"显示监控中、实际零检查"的假活。 */
        const val MAX_CONSECUTIVE_LOOP_FAILURES = 5

        /** 运行态表达（0.6.47.2）：界面必须反映 FGS 是否真的在跑，而不是用户选择的意图。 */
        val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
}
