package com.example.bilimonitor.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标裁剪的几何计算。
 *
 * 这些数字直接决定用户裁出来的是什么：算错会裁到画面外的空白，
 * 或者拖动时图片"跑出"取景框。所以把边界（不越界、缩放下限、极端长宽比）钉死。
 */
class CropMathTest {

    @Test
    fun `base scale makes the shorter side fill the box`() {
        // 宽图：高度是短板 → 按高度铺满
        assertEquals(2f, CropMath.baseScale(200, 100, 200f))
        // 高图：宽度是短板
        assertEquals(2f, CropMath.baseScale(100, 200, 200f))
        // 正方形
        assertEquals(1f, CropMath.baseScale(200, 200, 200f))
    }

    @Test
    fun `zoom one takes the centered square`() {
        // 400x200 的图，取景框 200：基准缩放 1，显示 400x200，居中后左右各溢出 100
        val rect = CropMath.sourceRect(400, 200, 200f, zoom = 1f, offsetX = 0f, offsetY = 0f)
        assertEquals(100, rect.left)
        assertEquals(0, rect.top)
        assertEquals(200, rect.size)
    }

    @Test
    fun `dragging right moves the crop window left`() {
        val base = CropMath.sourceRect(400, 200, 200f, 1f, 0f, 0f)
        val dragged = CropMath.sourceRect(400, 200, 200f, 1f, 100f, 0f)
        // 图片往右拖 → 取景框相对图片左移
        assertEquals(base.left - 100, dragged.left)
        assertEquals(0, dragged.top)
    }

    @Test
    fun `crop rect never leaves the image`() {
        // 拖到远超边界的位置（调用方本应先 clamp，这里验证 badcase 也不会越界）
        val rect = CropMath.sourceRect(400, 200, 200f, 1f, 9999f, -9999f)
        assertTrue("left 必须 >= 0，实际 ${rect.left}", rect.left >= 0)
        assertTrue("top 必须 >= 0，实际 ${rect.top}", rect.top >= 0)
        assertTrue("不能超出右边界", rect.left + rect.size <= 400)
        assertTrue("不能超出下边界", rect.top + rect.size <= 200)
    }

    @Test
    fun `zoom in shrinks the crop size`() {
        val atOne = CropMath.sourceRect(400, 200, 200f, 1f, 0f, 0f)
        val atTwo = CropMath.sourceRect(400, 200, 200f, 2f, 0f, 0f)
        assertTrue("放大后裁剪区域必须更小", atTwo.size < atOne.size)
        assertEquals(100, atTwo.size)
    }

    @Test
    fun `zoom below one is treated as one`() {
        // 手势可能给出 <1 的缩放，但那会让取景框露出图片外的区域
        val rect = CropMath.sourceRect(400, 200, 200f, 0.3f, 0f, 0f)
        assertEquals(CropMath.sourceRect(400, 200, 200f, 1f, 0f, 0f), rect)
    }

    @Test
    fun `clamp offset keeps the image covering the box`() {
        // 400x200 铺满 200 的框：水平最多各溢出 100，垂直没有余量
        val (x, y) = CropMath.clampOffset(400, 200, 200f, 1f, offsetX = 500f, offsetY = 500f)
        assertEquals(100f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    @Test
    fun `clamp offset allows more travel when zoomed in`() {
        val (x, _) = CropMath.clampOffset(400, 200, 200f, zoom = 2f, offsetX = 9999f, offsetY = 0f)
        // 放大 2 倍后显示宽 800，可移动范围 (800-200)/2 = 300
        assertEquals(300f, x, 0.001f)
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertEquals(CropMath.CropRect(0, 0, 1), CropMath.sourceRect(0, 0, 200f, 1f, 0f, 0f))
        assertEquals(1f, CropMath.baseScale(0, 100, 200f))
        val rect = CropMath.sourceRect(1, 1, 200f, 1f, 0f, 0f)
        assertEquals(1, rect.size)
    }
}
