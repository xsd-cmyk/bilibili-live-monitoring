package com.example.bilimonitor.background

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 无障碍保活服务的实际可用性（0.6.47.2 第 4 条）。
 *
 * 规格要求：
 *  - 「无障碍服务『可用性』由系统回调实时更新；不得缓存为常量」；
 *  - 「用户选择与系统能力不一致时，界面必须显示实际状态，不得显示用户选择所暗示的乐观状态」。
 *
 * 因此这里既提供一次性查询（[isEnabled]），也提供**由系统回调驱动**的 [observe]。
 * 关键点：用户是在系统设置里开关这个服务的，回到 App 时进程可能还活着，
 * Settings.Secure 变化不会触发任何通知，必须靠 AccessibilityManager 的状态回调才能实时反映。
 */
object AccessibilityKeepAlive {

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, KeepAliveAccessibilityService::class.java)
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return false
        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry.trim()) == expected
        }
    }

    /** 系统回调驱动的可用性流；订阅期间才会注册监听。 */
    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

        // 先发一次当前值，保证订阅者立刻拿到状态而不是等第一次变化。
        trySend(isEnabled(appContext))

        val listener = AccessibilityManager.AccessibilityStateChangeListener { trySend(isEnabled(appContext)) }
        manager?.addAccessibilityStateChangeListener(listener)

        // 兜底轮询：部分 ROM 在权限页直接 kill/重启进程，回调不一定到达。
        // 间隔较长，仅用于纠偏，不构成轮询负担。
        val handler = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                trySend(isEnabled(appContext))
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.postDelayed(poll, POLL_INTERVAL_MS)

        awaitClose {
            manager?.removeAccessibilityStateChangeListener(listener)
            handler.removeCallbacks(poll)
        }
    }.distinctUntilChanged()

    private const val POLL_INTERVAL_MS = 10_000L

    /**
     * 跳转系统无障碍设置页，由用户手动开启本应用的服务。
     * 引导页选项二的文案已承诺"跳到系统设置，由用户手动开启"，原实现没有任何入口。
     */
    fun openSettings(context: Context) {
        val intents = listOf(
            android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            // 部分 ROM 只响应带组件名或带 package extra 的形式
            android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:show_fragment_args", context.packageName)
        )
        for (intent in intents) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        // 兜底：打开本应用的系统详情页，用户可自行进入无障碍
        runCatching {
            context.startActivity(
                android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
