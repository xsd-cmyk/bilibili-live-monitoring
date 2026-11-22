package com.example.bilimonitor.ui.nav

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bilimonitor.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PageNavigationInstrumentedTest {
    private val frameClock = BroadcastFrameClock()
    private var frameNanos = 0L

    private fun MainActivity.testContent(content: @Composable () -> Unit) {
        val recomposer = Recomposer(lifecycleScope.coroutineContext + frameClock)
        lifecycleScope.launch(frameClock) {
            try { recomposer.runRecomposeAndApplyChanges() } finally { recomposer.cancel() }
        }
        setContent(parent = recomposer, content = content)
    }

    private fun step() {
        frameNanos += 16_666_667L
        frameClock.sendFrame(frameNanos)
    }

    @Test fun predictiveProgressShowsPreviousPageButOnlyCommitPops() {
        val tabs = TabPresentation()
        var side = 1
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: NavHostController
            scenario.onActivity { activity ->
                activity.testContent {
                    controller = rememberNavController()
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        PageNavHost(controller, Routes.HOME, tabs = tabs, backEdge = { side }) {
                            tabPage(Routes.HOME) { Box(Modifier.fillMaxSize().background(Color.Green)) }
                            tabPage(Routes.SETTINGS) { Box(Modifier.fillMaxSize().background(Color.Red)) }
                            composable("child") { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                        }
                    }
                }
            }
            waitForStrip(scenario) { strip -> strip.all { it == android.graphics.Color.GREEN } }
            scenario.onActivity { controller.navigate(Routes.SETTINGS) }
            waitForStrip(scenario) { strip -> strip.all { it == android.graphics.Color.RED } }
            scenario.onActivity {
                controller.navigate("child")
                assertTrue("离场页仍在组合，不能当作返回预览", Routes.SETTINGS in tabs.panes)
                assertEquals(null, tabs.chromeRoute(controller.currentBackStackEntry))
            }
            waitForStrip(scenario) { strip -> android.graphics.Color.BLUE in strip && android.graphics.Color.RED in strip }
            scenario.onActivity { assertEquals(null, tabs.chromeRoute(controller.currentBackStackEntry)) }
            waitForStrip(scenario) { strip -> strip.all { it == android.graphics.Color.BLUE } }
            fun preview(edge: Int) {
                side = if (edge == BackEventCompat.EDGE_LEFT) 1 else -1
                val deadline = SystemClock.uptimeMillis() + 5000L
                var ready = false
                do {
                    scenario.onActivity {
                        step()
                        ready = controller.currentBackStackEntry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                    }
                    if (!ready) SystemClock.sleep(32)
                } while (!ready && SystemClock.uptimeMillis() < deadline)
                assertTrue("当前路由未完成转场", ready)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, edge)) }
                fun advance(progress: Float) = scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f * progress, 200f, progress, edge))
                }
                advance(0.18f)
                val early = waitForStrip(scenario) { strip -> (if (side == 1) strip.first() else strip.last()) == android.graphics.Color.RED &&
                    (if (side == 1) strip.last() else strip.first()) == android.graphics.Color.BLUE }
                val redSamples = early.count { it == android.graphics.Color.RED }
                advance(0.65f)
                waitForStrip(scenario) { strip -> strip.count { it == android.graphics.Color.RED } > redSamples &&
                    (if (side == 1) strip.last() else strip.first()) == android.graphics.Color.BLUE }
                scenario.onActivity {
                    assertEquals("child", controller.currentDestination?.route)
                    assertEquals(Routes.SETTINGS, tabs.chromeRoute(controller.currentBackStackEntry))
                    assertTrue("底栏应跟随真实上一页", (tabs.positions[Routes.SETTINGS] ?: 0) * side < 0)
                }
            }
            preview(BackEventCompat.EDGE_LEFT)
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            waitForStrip(scenario) { strip -> strip.all { it == android.graphics.Color.BLUE } }
            scenario.onActivity { assertEquals("child", controller.currentDestination?.route) }
            preview(BackEventCompat.EDGE_RIGHT)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForStrip(scenario) { strip -> strip.all { it == android.graphics.Color.RED } }
            scenario.onActivity {
                assertEquals(Routes.SETTINGS, controller.currentDestination?.route)
                assertEquals(Routes.HOME, controller.previousBackStackEntry?.destination?.route)
            }
        }
    }

    @Test fun tabWipesCoverTheWindowAndRestoreHomeAfterOnboarding() {
        val green = android.graphics.Color.GREEN
        val red = android.graphics.Color.RED
        val cyan = android.graphics.Color.CYAN
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var controller: NavHostController
            val tabs = TabPresentation()
            scenario.onActivity { activity -> activity.testContent {
                controller = rememberNavController()
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    PageNavHost(controller, Routes.ONBOARDING, Modifier.background(Color.Magenta), tabs) {
                        composable(Routes.ONBOARDING) { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                        tabPage(Routes.HOME) { Box(Modifier.fillMaxSize().background(Color.Green)) }
                        tabPage(Routes.HISTORY) { Box(Modifier.fillMaxSize().background(Color.Cyan)) }
                        tabPage(Routes.SETTINGS) { Box(Modifier.fillMaxSize().background(Color.Red)) }
                    }
                }
            } }
            waitForStrip(scenario) { colors -> colors.all { it == android.graphics.Color.BLUE } }
            scenario.onActivity { controller.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } }
            waitForStrip(scenario) { colors -> colors.all { it == green } }
            fun select(route: String) = scenario.onActivity { tabs.select(controller, route) }
            fun joined(left: Int, right: Int) = waitForStrip(scenario) { colors ->
                val seam = colors.indexOf(right)
                seam in 1 until colors.size && colors.take(seam).all { it == left } && colors.drop(seam).all { it == right }
            }
            select(Routes.SETTINGS)
            joined(green, red)
            waitForStrip(scenario) { colors -> colors.all { it == red } }
            select(Routes.HOME)
            joined(green, red)
            waitForStrip(scenario) { colors -> colors.all { it == green } }
            select(Routes.SETTINGS)
            joined(green, red)
            select(Routes.HISTORY)
            waitForStrip(scenario) { colors -> colors.all { it == cyan } }
            scenario.onActivity {
                assertEquals(Routes.HOME, controller.previousBackStackEntry?.destination?.route)
                tabs.select(controller, Routes.HISTORY)
                assertEquals(Routes.HOME, controller.previousBackStackEntry?.destination?.route)
                it.onBackPressedDispatcher.onBackPressed()
            }
            waitForStrip(scenario) { colors -> colors.all { it == green } }
            scenario.onActivity { assertEquals(null, controller.previousBackStackEntry) }
        }
    }

    private fun waitForStrip(scenario: ActivityScenario<MainActivity>, matches: (List<Int>) -> Boolean): List<Int> {
        val deadline = SystemClock.uptimeMillis() + 6000
        var samples = emptyList<Int>()
        val shot = Bitmap.createBitmap(41, 1, Bitmap.Config.ARGB_8888)
        try {
            do {
                val done = CountDownLatch(1)
                var status = PixelCopy.ERROR_UNKNOWN
                scenario.onActivity { activity ->
                    step()
                    val view = activity.window.decorView
                    val top = view.height / 2
                    PixelCopy.request(activity.window, Rect(0, top, view.width, top + 1), shot,
                        { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(done.await(3, TimeUnit.SECONDS))
                if (status == PixelCopy.SUCCESS) {
                    samples = (0 until shot.width).map { shot.getPixel(it, 0) }
                    if (matches(samples)) return samples
                }
                SystemClock.sleep(32)
            } while (SystemClock.uptimeMillis() < deadline)
            error("未观察到目标转场画面，最后采样：${samples.map(Integer::toHexString)}")
        } finally { shot.recycle() }
    }
}
