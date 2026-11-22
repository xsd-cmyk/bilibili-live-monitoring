package com.example.bilimonitor.ui.nav

import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.MainActivity
import com.example.bilimonitor.data.repository.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class DockGestureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val commits = CopyOnWriteArrayList<String>()
    private val tabs = listOf("One", "Two", "Three", "Four").map { BottomDestination(it, it, Icons.Filled.Home) }

    private fun withDock(direction: LayoutDirection = LayoutDirection.Ltr, systemTimeout: Long? = null, check: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var route by remember { mutableStateOf("One") }
                    val platform = LocalViewConfiguration.current
                    val config = remember(platform, systemTimeout) {
                        if (systemTimeout == null) platform else object : androidx.compose.ui.platform.ViewConfiguration by platform {
                            override val longPressTimeoutMillis: Long = requireNotNull(systemTimeout)
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides direction, LocalViewConfiguration provides config) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DockNavigationBar(tabs, route, { commits.add(it.route); route = it.route },
                                null, { Offset.Zero }, AppearanceSettings())
                        }
                    }
                }
            }
            node("One")
            check()
        }
    }

    private fun node(label: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.text?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 8000
        do {
            find(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            Thread.sleep(60)
        } while (SystemClock.uptimeMillis() < deadline)
        error("No tab labeled $label")
    }

    private fun point(label: String): PointF {
        val rect = Rect().also { node(label).getBoundsInScreen(it) }
        return PointF(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun send(action: Int, point: PointF, start: Long) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun tap(point: PointF) {
        val start = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, point, start)
        send(MotionEvent.ACTION_UP, point, start)
        instrumentation.waitForIdleSync()
    }

    private fun press(point: PointF): Long {
        val start = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, point, start)
        return start
    }

    private fun tabBounds(label: String): Rect {
        var tab = node(label)
        while (!tab.isClickable) tab = checkNotNull(tab.parent)
        return Rect().also { tab.getBoundsInScreen(it) }
    }

    @Test fun fullHeightEdgesSelectAndOverflowDoesNotCommit() = withDock {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val second = tabBounds("Two")
        val third = tabBounds("Three")
        assertTrue(second.height() >= 80f * density - 1f)
        assertTrue(second.width() >= 48f * density - 1f)
        assertEquals(second.right, third.left)
        tap(PointF(second.exactCenterX(), second.top + density))
        tap(PointF(third.exactCenterX(), third.bottom - density))
        val start = press(point("Two"))
        send(MotionEvent.ACTION_MOVE, point("Four"), start)
        val overhang = PointF(second.exactCenterX(), second.top - 3f * density)
        send(MotionEvent.ACTION_MOVE, overhang, start)
        send(MotionEvent.ACTION_UP, overhang, start)
        instrumentation.waitForIdleSync()
        assertEquals(listOf("Two", "Three"), commits.toList())
    }

    @Test fun immediateDragIgnoresSystemLongPressTimeout() = withDock(systemTimeout = 1500L) {
        val second = point("Two")
        val third = point("Three")
        val start = press(second)
        send(MotionEvent.ACTION_MOVE, third, start)
        assertTrue(commits.isEmpty())
        send(MotionEvent.ACTION_UP, third, start)
        instrumentation.waitForIdleSync()
        assertEquals(listOf("Three"), commits.toList())
    }

    @Test fun verticalDragCannotBecomeTapOrHorizontalSelection() = withDock {
        val second = point("Two")
        val third = point("Three")
        val start = press(second)
        send(MotionEvent.ACTION_MOVE, PointF(second.x, second.y + tabBounds("Two").height() / 3f), start)
        send(MotionEvent.ACTION_MOVE, third, start)
        send(MotionEvent.ACTION_UP, third, start)
        instrumentation.waitForIdleSync()
        assertTrue(commits.isEmpty())
    }

    @Test fun secondFingerCancelsDrag() = withDock {
        val second = point("Two")
        val third = point("Three")
        val last = point("Four")
        val start = press(second)
        send(MotionEvent.ACTION_MOVE, third, start)
        fun multi(action: Int) {
            val positions = listOf(third, last)
            val props = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(2) { index -> MotionEvent.PointerCoords().apply {
                x = positions[index].x; y = positions[index].y; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        multi(MotionEvent.ACTION_POINTER_DOWN)
        multi(MotionEvent.ACTION_POINTER_UP)
        send(MotionEvent.ACTION_UP, third, start)
        instrumentation.waitForIdleSync()
        assertTrue(commits.isEmpty())
    }

    @Test fun tapRetargetAndSemanticClickDoNotDuplicate() = withDock {
        val second = point("Two")
        val last = point("Four")
        tap(second); tap(last); tap(last)
        var action = node("Three")
        while (!action.isClickable) action = checkNotNull(action.parent)
        assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(listOf("Two", "Four", "Three"), commits.toList())
    }

    private fun verifyScrub() {
        val second = point("Two")
        val third = point("Three")
        val last = point("Four")
        val start = press(second)
        send(MotionEvent.ACTION_MOVE, third, start)
        send(MotionEvent.ACTION_MOVE, last, start)
        instrumentation.waitForIdleSync()
        assertTrue(commits.isEmpty())
        send(MotionEvent.ACTION_UP, last, start)
        instrumentation.waitForIdleSync()
        assertEquals(listOf("Four"), commits.toList())
    }

    @Test fun directDragPreviewsUntilRelease() = withDock { verifyScrub() }
    @Test fun rtlReleaseMapsToVisibleTab() = withDock(LayoutDirection.Rtl) { verifyScrub() }

    @Test fun cancellationAndOutsideReleaseLeaveOriginalRoute() = withDock {
        val second = point("Two")
        val third = point("Three")
        val outside = PointF(third.x, third.y - 300f)
        val firstHold = press(second)
        send(MotionEvent.ACTION_MOVE, third, firstHold)
        send(MotionEvent.ACTION_CANCEL, third, firstHold)
        val nextHold = press(second)
        send(MotionEvent.ACTION_MOVE, outside, nextHold)
        send(MotionEvent.ACTION_UP, outside, nextHold)
        val returnHold = press(second)
        val origin = point("One")
        send(MotionEvent.ACTION_MOVE, origin, returnHold)
        send(MotionEvent.ACTION_UP, origin, returnHold)
        instrumentation.waitForIdleSync()
        assertTrue(commits.isEmpty())
        val stationary = press(second)
        send(MotionEvent.ACTION_UP, second, stationary)
        instrumentation.waitForIdleSync()
        assertEquals(listOf("Two"), commits.toList())
    }
}
