package com.example.bilimonitor

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.bilimonitor.background.StartupRecovery
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.AppClocks
import com.example.bilimonitor.notify.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class BiliMonitorApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var startupRecovery: StartupRecovery

    @Inject lateinit var clock: AppClock

    @Inject lateinit var crashRecorder: com.example.bilimonitor.core.CrashRecorder

    @Inject
    @Named("appScope")
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 把容器里唯一的 AppClock 注册给展示层，避免 UI 直接读系统时间而绕过网络校时（244.13）。
        AppClocks.register(clock)
        // 未捕获异常落库（原规范 81/225）：原实现 crash_record 表从无读写，崩溃后毫无痕迹。
        crashRecorder.install(this)
        NotificationChannels.ensureCreated(this)
        appScope.launch(Dispatchers.IO) {
            // 启动恢复内部已经逐步留痕（StartupRecovery.step），这里再兜一层，
            // 但**不能**像原先那样静默吞掉（第二轮审查）：若连 run() 本身都抛了，
            // 至少 logcat 里要看得到。
            runCatching { startupRecovery.run() }.onFailure {
                android.util.Log.e("BiliMonitorApp", "启动恢复整体失败：${it.message}", it)
            }
        }
    }
}
