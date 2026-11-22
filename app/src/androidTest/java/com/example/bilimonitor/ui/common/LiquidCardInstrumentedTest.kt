package com.example.bilimonitor.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.graphics.Point
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.MainActivity
import com.example.bilimonitor.data.repository.AppearanceSettings
import com.example.bilimonitor.data.repository.AppearanceSettingsRepository
import com.example.bilimonitor.ui.theme.AppBackground
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LiquidCardInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun independentPreferenceSurvivesRepositoryRecreation() = runBlocking {
        val repo = AppearanceSettingsRepository(instrumentation.targetContext)
        val before = repo.current()
        try {
            for (enabled in listOf(true, false)) {
                repo.setLiquidCards(enabled)
                assertEquals(before.copy(liquidCards = enabled), AppearanceSettingsRepository(instrumentation.targetContext).current())
            }
        } finally {
            repo.setLiquidCards(before.liquidCards)
        }
    }

    @Test fun cardAloneSamplesWallpaperAndRestoresOpaqueSurface() {
        val enabled = mutableStateOf(false)
        val blur = mutableStateOf(0f)
        val cachedFrost = CountDownLatch(1)
        val left = Point()
        val right = Point()
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            val paint = android.graphics.Paint().apply { color = Color.RED }
            android.graphics.Canvas(this).drawRect(0f, 0f, 100f, 200f, paint)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AppBackground(bitmap, 0f, 1f, cardMaterial = if (enabled.value)
                        AppearanceSettings(liquidCards = true, glassBlur = blur.value, glassTint = 0.12f) else null) {
                        val source = LocalCardBackdrop.current?.wallpaper?.bitmap
                        androidx.compose.runtime.SideEffect {
                            if (blur.value > 0f && source != null && source !== bitmap) cachedFrost.countDown()
                        }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            AppCard(Modifier.size(220.dp, 92.dp).onGloballyPositioned {
                                val pos = it.positionInWindow()
                                left.set((pos.x + it.size.width * 0.18f).toInt(), (pos.y + it.size.height * 0.55f).toInt())
                                right.set((pos.x + it.size.width * 0.82f).toInt(), left.y)
                            }) { Text("材质") }
                        }
                    }
                }
            }
            fun pixels(start: Point = left, end: Point = right, predicate: (Int, Int) -> Boolean): Pair<Int, Int> {
                val deadline = SystemClock.uptimeMillis() + 5000L
                do {
                    instrumentation.waitForIdleSync()
                    if (start.x > 0 && end.x > start.x) {
                        val result = CountDownLatch(1)
                        val rect = Rect(start.x, start.y, end.x + 1, start.y + 1)
                        val shot = Bitmap.createBitmap(rect.width(), 1, Bitmap.Config.ARGB_8888)
                        var status = PixelCopy.ERROR_UNKNOWN
                        scenario.onActivity { activity ->
                            PixelCopy.request(activity.window, rect, shot, { status = it; result.countDown() }, Handler(Looper.getMainLooper()))
                        }
                        assertTrue(result.await(3, TimeUnit.SECONDS))
                        try {
                            if (status == PixelCopy.SUCCESS) {
                                val first = shot.getPixel(0, 0)
                                val last = shot.getPixel(shot.width - 1, 0)
                                if (predicate(first, last)) return first to last
                            }
                        } finally { shot.recycle() }
                    }
                    SystemClock.sleep(60)
                } while (SystemClock.uptimeMillis() < deadline)
                error("卡片像素未达到预期，采样点 $left / $right")
            }
            val original = pixels { first, last -> first == last && Color.red(first) > 30 }
            scenario.onActivity { enabled.value = true }
            pixels { first, last -> Color.red(first) - Color.red(last) > 90 && Color.blue(last) - Color.blue(first) > 90 }
            val middle = (left.x + right.x) / 2
            val gap = (right.x - left.x) / 16
            val nearLeft = Point(middle - gap, left.y)
            val nearRight = Point(middle + gap, right.y)
            val outsideLeft = Point(nearLeft.x, left.y - (right.x - left.x) / 2)
            val outsideRight = Point(nearRight.x, outsideLeft.y)
            val outside = pixels(outsideLeft, outsideRight) { first, last -> Color.red(first) - Color.red(last) > 130 }
            val clear = pixels(nearLeft, nearRight) { first, last -> Color.red(first) - Color.red(last) > 130 }
            scenario.onActivity { blur.value = 28f }
            if (android.os.Build.VERSION.SDK_INT >= 33) assertTrue("等待共用的模糊纹理", cachedFrost.await(6, TimeUnit.SECONDS))
            pixels(nearLeft, nearRight) { first, last -> Color.red(first) - Color.red(last) in
                1 until (Color.red(clear.first) - Color.red(clear.second)) * 4 / 5 }
            pixels(outsideLeft, outsideRight) { first, last -> (first to last) == outside }
            scenario.onActivity { enabled.value = false }
            pixels { first, last -> (first to last) == original }
        }
    }
}
