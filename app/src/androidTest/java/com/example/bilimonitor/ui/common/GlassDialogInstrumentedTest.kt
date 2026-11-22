package com.example.bilimonitor.ui.common

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.MainActivity
import com.example.bilimonitor.data.repository.AppearanceSettings
import com.example.bilimonitor.ui.theme.AppBackground
import com.example.bilimonitor.ui.theme.BiliMonitorTheme
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassDialogInstrumentedTest {
    @Test fun dialogSamplesWallpaperAtScreenCoordinatesAndRestoresOpaqueMaterial() {
        val wallpaper = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            for (column in 0 until width) for (row in 0 until height) {
                setPixel(column, row, if (column < width / 2) Color.RED else Color.BLUE)
            }
        }
        val material = mutableStateOf<AppearanceSettings?>(null)
        val shown = mutableStateOf(true)
        val probe = AtomicReference<Pair<Offset, Offset>>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                BiliMonitorTheme(darkTheme = false) {
                    AppBackground(wallpaper, dim = 0f, alpha = 1f, cardMaterial = material.value) {
                        if (shown.value) AppAlertDialog(onDismissRequest = { shown.value = false }, title = { Text("壁纸采样") }, confirmButton = {}, text = {
                            Box(Modifier.fillMaxWidth().height(64.dp).onGloballyPositioned { coords ->
                                val middle = coords.size.height / 2f
                                probe.set(coords.localToScreen(Offset(coords.size.width * 0.2f, middle)) to
                                    coords.localToScreen(Offset(coords.size.width * 0.8f, middle)))
                            })
                        })
                    }
                }
            } }
            fun expect(glass: Boolean) {
                val deadline = SystemClock.uptimeMillis() + 6000
                while (SystemClock.uptimeMillis() < deadline) {
                    val bounds = probe.get()
                    if (bounds != null) {
                        val picture = automation.takeScreenshot()
                        if (picture != null) {
                            fun channel(point: Offset): Int {
                                val pixel = picture.getPixel(point.x.roundToInt(), point.y.roundToInt())
                                return Color.red(pixel) - Color.blue(pixel)
                            }
                            try {
                                val left = channel(bounds.first)
                                val right = channel(bounds.second)
                                if (if (glass) left > 45 && right < -45 else abs(left) < 35 && abs(right) < 35) return
                            } finally { picture.recycle() }
                        }
                    }
                    SystemClock.sleep(80)
                }
                fail("对话框未显示预期材质：glass=$glass")
            }
            expect(false)
            for (blur in listOf(0f, 28f)) {
                scenario.onActivity { material.value = AppearanceSettings(liquidCards = true, glassBlur = blur, glassTint = 0.1f) }
                expect(true)
                scenario.onActivity { material.value = null }
                expect(false)
            }
            scenario.onActivity { shown.value = false }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }
}
