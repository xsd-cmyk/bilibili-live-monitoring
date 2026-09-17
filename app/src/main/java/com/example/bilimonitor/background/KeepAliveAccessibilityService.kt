package com.example.bilimonitor.background

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍保活服务（0.6.47 选项二，实验性）：
 * 仅用于提高后台存活率；不读取、不上传、不记录任何屏幕内容或输入事件（244.9）。
 * 不拥有任何业务事实写权限（0.6.41）。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
