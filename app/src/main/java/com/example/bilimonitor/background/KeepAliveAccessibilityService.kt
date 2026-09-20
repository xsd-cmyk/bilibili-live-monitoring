package com.example.bilimonitor.background

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 保活无障碍服务（0.6.47 选项：无障碍保活）。
 *
 * 它提高后台存活率，但**不读取、不上传、不记录任何屏幕内容或输入事件**（原规范 244.9），
 * 也不拥有任何业务逻辑（0.6.41）。
 *
 * ★ 除了"让系统更不愿意杀掉这个应用"之外，它还有一条**不依赖闹钟**的恢复通道：
 * 无障碍服务由 system_server 绑定，**进程被杀后系统会重新拉起应用并重绑**
 * （见 [onServiceConnected]）。用户开了无障碍保活，就等于给保活链多上了一道保险；
 * 没开的用户完全不会走到这里，因此对他们是零影响。
 */
@AndroidEntryPoint
class KeepAliveAccessibilityService : AccessibilityService() {

    @Inject lateinit var controller: MonitoringController

    /** 应用级作用域：`onServiceConnected` 返回后工作还要继续（不能挂在服务生命周期上）。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * 系统重新绑定本服务时，顺手确认监控服务在跑。
     *
     * 为什么值得做：这是**官方允许且不需要任何豁免**的拉起时机（重绑由 system_server 发起），
     * 与"闹钟心跳"互为独立通道 —— 撤销精确闹钟权限会杀掉闹钟，但杀不掉这条。
     * 结果照常留痕（`ensureRunning` 的返回值会进审计/错误日志），不静默。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.launch {
            runCatching {
                val result = controller.ensureRunning("accessibility_rebound")
                android.util.Log.i("KeepAliveAccessibility", "无障碍重绑：恢复结果=$result")
            }.onFailure {
                android.util.Log.w("KeepAliveAccessibility", "无障碍重绑恢复失败：${it.message}")
            }
        }
    }
}
