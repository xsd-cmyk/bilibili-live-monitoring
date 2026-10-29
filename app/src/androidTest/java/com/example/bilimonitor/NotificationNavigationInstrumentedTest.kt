package com.example.bilimonitor

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.notify.NotificationClickHint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationNavigationInstrumentedTest {
    @Test fun coldStartMissingEventOpensHistoryAfterGraphIsReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EVENT_KEY, "missing-navigation-regression")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            assertVisible("通知历史")
        }
    }

    @Test fun coldStartMissingBatchShowsAnExplicitHistoryFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "agg:missing-navigation-regression"
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EVENT_KEY, key)
            .putExtra(NotificationClickHint.EXTRA, NotificationClickHint(key, NotificationEventType.BATCH_END).encode())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            assertVisible("打开通知历史")
        }
    }

    private fun assertVisible(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        var found = false
        while (android.os.SystemClock.elapsedRealtime() < deadline && !found) {
            instrumentation.waitForIdleSync()
            found = contains(instrumentation.uiAutomation.rootInActiveWindow, text)
            if (!found) Thread.sleep(100)
        }
        assertTrue("Expected visible navigation destination: $text", found)
    }

    private fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(text) == true) return true
        return (0 until node.childCount).any { contains(node.getChild(it), text) }
    }
}
