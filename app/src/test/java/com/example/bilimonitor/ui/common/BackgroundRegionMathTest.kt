package com.example.bilimonitor.ui.common

import com.example.bilimonitor.data.repository.BackgroundCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景图"显示区域"的几何。
 *
 * 这些数字直接决定用户框出来的东西会不会被用上：算错一处，用户在取景框里看到的
 * 与最终背景就不是同一块（这正是这类功能最常见的坑）。所以把"归一化 ↔ 像素"、
 * "区域 ↔ 编辑器取景"这两组换算和它们的互逆性钉死。
 */
class BackgroundRegionMathTest {

    /** 竖屏取景框（9:19 那种比例），与真实屏幕上量到的窗口比例同一个量级。 */
    private val boxWidth = 540f
    private val boxHeight = 1140f

    @Test
    fun `no crop means the whole image`() {
        val region = BackgroundRegionMath.pixelRegion(2000, 1500, null)
        assertEquals(0, region.left)
        assertEquals(0, region.top)
        assertEquals(2000, region.width)
        assertEquals(1500, region.height)
    }

    @Test
    fun `invalid crop falls back to the whole image`() {
        // 细成一条线的矩形：读取侧（BackgroundCrop.isValid）本来就拦得住，
        // 这里再验证一遍几何函数自己也不会拿它去裁 —— 缺键/坏值都必须是能正常显示的默认行为。
        val degenerate = BackgroundCrop(0.5f, 0.5f, 0.5001f, 1f)
        assertTrue("这种区域必须被判定为非法", !degenerate.isValid)
        val region = BackgroundRegionMath.pixelRegion(2000, 1500, degenerate)
        assertEquals(0, region.left)
        assertEquals(2000, region.width)
    }

    @Test
    fun `normalized crop maps onto pixels`() {
        val region = BackgroundRegionMath.pixelRegion(
            2000, 1500, BackgroundCrop(0.25f, 0.2f, 0.75f, 0.6f)
        )
        assertEquals(500, region.left)
        assertEquals(300, region.top)
        assertEquals(1000, region.width)
        assertEquals(600, region.height)
    }

    @Test
    fun `placement covers the box and maps the box centre back`() {
        val region = BackgroundRegionMath.PixelRegion(100, 50, 400, 200)
        val placement = BackgroundRegionMath.placement(region, 200f, 200f)
        // 400x200 放进 200x200：Crop 取较大的缩放 1.0，宽度溢出 200 → 左右各 -100
        assertEquals(1f, placement.scale, 0.0001f)
        assertEquals(-100f, placement.originX, 0.0001f)
        assertEquals(0f, placement.originY, 0.0001f)
        // 框中心必须落在区域中心（取色/命中测试全靠这条）
        assertEquals(200f, placement.regionX(100f), 0.0001f)
        assertEquals(100f, placement.regionY(100f), 0.0001f)
    }

    @Test
    fun `untouched view state stores no crop`() {
        // 铺满 + 居中 = 默认取景：不写区域参数，渲染侧继续走"整张图居中铺满"那条默认路径
        assertNull(
            BackgroundRegionMath.cropFor(
                2000, 1500, boxWidth, boxHeight, BackgroundRegionMath.ViewState.DEFAULT
            )
        )
    }

    @Test
    fun `a missing crop comes back as the default view state`() {
        // 老安装没有这个键：编辑器打开时必须落在"铺满 + 居中"，与渲染侧一致
        val state = BackgroundRegionMath.viewStateFor(
            BackgroundRegionMath.pixelRegion(2000, 1500, null), 2000, 1500, boxWidth, boxHeight
        )
        assertEquals(1f, state.zoom, 0.0001f)
        assertEquals(0f, state.offsetX, 0.01f)
        assertEquals(0f, state.offsetY, 0.01f)
    }

    @Test
    fun `view state restores the crop that produced it`() {
        val state = BackgroundRegionMath.ViewState(zoom = 2f, offsetX = 0f, offsetY = 0f)
        val crop = requireNotNull(
            BackgroundRegionMath.cropFor(2000, 1500, boxWidth, boxHeight, state)
        ) { "放大 2 倍必须框出一块真正的区域" }

        // "再次调整区域"：进来时必须接着上次的取景，而不是回到居中
        val restored = BackgroundRegionMath.viewStateFor(
            BackgroundRegionMath.pixelRegion(2000, 1500, crop), 2000, 1500, boxWidth, boxHeight
        )
        assertEquals(state.zoom, restored.zoom, 0.01f)
        assertEquals(state.offsetX, restored.offsetX, 1f)
        assertEquals(state.offsetY, restored.offsetY, 1f)

        // 再确定一次，区域必须还是同一块（只允许取整带来的不到一个像素的差）
        val again = requireNotNull(
            BackgroundRegionMath.cropFor(2000, 1500, boxWidth, boxHeight, restored)
        )
        assertEquals(crop.left, again.left, 0.005f)
        assertEquals(crop.top, again.top, 0.005f)
        assertEquals(crop.right, again.right, 0.005f)
        assertEquals(crop.bottom, again.bottom, 0.005f)
    }

    @Test
    fun `panning changes the region but keeps it inside the image`() {
        val crop = requireNotNull(
            BackgroundRegionMath.cropFor(
                2000, 1500, boxWidth, boxHeight,
                // 拖到远超边界的位置（调用方本应先 clamp，这里验证 badcase 也不会越界）
                BackgroundRegionMath.ViewState(BackgroundRegionMath.MAX_ZOOM, 99999f, -99999f)
            )
        ) { "拖到边界外也必须选到一块区域" }
        val region = BackgroundRegionMath.pixelRegion(2000, 1500, crop)
        assertTrue("left 必须 >= 0，实际 ${region.left}", region.left >= 0)
        assertTrue("top 必须 >= 0，实际 ${region.top}", region.top >= 0)
        assertTrue("不能超出右边界", region.left + region.width <= 2000)
        assertTrue("不能超出下边界", region.top + region.height <= 1500)

        // ★ 只判"在图内"是不够的，还必须是**取景框那一整块**：这个用例第一次跑就是在这里失败的。
        //   手算（框 540x1140、图 2000x1500、zoom = MAX_ZOOM = 8）：
        //     baseScale = max(540/2000, 1140/1500) = 0.76  → scale = 0.76 × 8 = 6.08
        //     区域应有尺寸 = 框 / scale = 540/6.08 × 1140/6.08 ≈ 88.8 × 187.5 → 取整 88 × 187
        //     位移上限 = (显示尺寸 − 框)/2 = (12160−540)/2 = 5810、(9120−1140)/2 = 3990
        //   位移 ±99999 被夹住后，框贴在图片**左下角**：x 方向 5810−5810 = 0 → left = 0；
        //   y 方向 3990−(−3990) = 7980 → top = 7980/6.08 = 1312（1312+187 = 1499 = 下边界）。
        //   旧实现是把 left/top 直接钳进图片、再让宽高去迁就剩下的那点空间，于是得到 88×1 的窄缝：
        //   归一化高度 0.000667 < BackgroundCrop.MIN_SIZE(0.02) → isValid=false → cropFor 返回 null，
        //   用户一路拖到底点「确定」却什么都没存（静默降级）。所以这里把"尺寸不能被挤扁"也钉死。
        assertEquals("框必须贴住左边界", 0, region.left)
        // 下边界这里只能要求"贴住（±2px）"：区域先归一化（top=1312/1500、bottom=1499/1500）
        // 再由 pixelRegion 乘回去，两次都向下取整，最多差 2px（实测 top=1312、height=186）。
        assertTrue(
            "框必须贴住下边界（±2px），实际 ${region.top + region.height}",
            kotlin.math.abs(region.top + region.height - 1500) <= 2
        )
        assertTrue(
            "宽必须还是 框宽/scale ≈ 88.8（±1px），实际 ${region.width}",
            kotlin.math.abs(region.width - 88) <= 1
        )
        assertTrue(
            "高必须还是 框高/scale ≈ 187.5（±1px），实际 ${region.height}",
            kotlin.math.abs(region.height - 187) <= 1
        )
        // 比例对不上就等于"区域被边界挤扁了"：两边各允许 1px 取整误差 →
        // |w×1140 − h×540| ≤ 1×1140 + 1×540。88x186 时差 120（通过），88x1 时差 99780（失败）。
        assertTrue(
            "区域必须保持取景框的比例，实际 ${region.width}x${region.height}",
            kotlin.math.abs(region.width * 1140 - region.height * 540) <= 1140 + 540
        )
        // 用例名字里的"panning changes the region"也要真的成立：位移 +99999 把框推到左边界，
        // 必须与居中时（left = (12160−540)/2/6.08 ≈ 955 → 归一化 ≈ 0.478）不是同一块。
        val centered = requireNotNull(
            BackgroundRegionMath.cropFor(
                2000, 1500, boxWidth, boxHeight,
                BackgroundRegionMath.ViewState(BackgroundRegionMath.MAX_ZOOM, 0f, 0f)
            )
        ) { "放大 8 倍居中时也必须有一块区域" }
        assertTrue(
            "拖到底必须换到另一块区域，实际 left=${crop.left}（居中时 ${centered.left}）",
            crop.left < centered.left
        )
    }

    @Test
    fun `restoring a region chosen at another aspect stays inside that region`() {
        // 竖屏时选的区域，切到横屏再进编辑器：取景框比例与区域不一致，
        // 应当按 Crop 取这块区域**居中**的一块（与渲染侧对同一组参数的处理一致），
        // 而不是把整个区域拉扁或者跑到区域外面去。
        val crop = BackgroundCrop(0.4f, 0.25f, 0.6f, 0.75f)
        val region = BackgroundRegionMath.pixelRegion(2000, 1500, crop)
        val state = BackgroundRegionMath.viewStateFor(region, 2000, 1500, 1140f, 540f)
        assertTrue("缩放不能小于铺满（否则取景框会露出图外），实际 ${state.zoom}", state.zoom >= 1f)

        val again = requireNotNull(
            BackgroundRegionMath.cropFor(2000, 1500, 1140f, 540f, state)
        )
        assertTrue("左边界不能越过已选区域", again.left >= crop.left - 0.001f)
        assertTrue("上边界不能越过已选区域", again.top >= crop.top - 0.001f)
        assertTrue("右边界不能越过已选区域", again.right <= crop.right + 0.001f)
        assertTrue("下边界不能越过已选区域", again.bottom <= crop.bottom + 0.001f)
    }

    @Test
    fun `degenerate inputs do not crash`() {
        assertEquals(1, BackgroundRegionMath.pixelRegion(0, 0, null).width)
        assertNull(BackgroundRegionMath.cropFor(0, 0, boxWidth, boxHeight, BackgroundRegionMath.ViewState.DEFAULT))
        assertEquals(
            BackgroundRegionMath.ViewState.DEFAULT,
            BackgroundRegionMath.viewStateFor(BackgroundRegionMath.PixelRegion(0, 0, 0, 0), 0, 0, 0f, 0f)
        )
        // 还没量到框的尺寸（首帧）时也不能崩：cropFor 返回 null = 不写区域，保持默认
        assertNull(
            BackgroundRegionMath.cropFor(
                2000, 1500, 0f, 0f, BackgroundRegionMath.ViewState(2f, 10f, 10f)
            )
        )

        // 图片比取景框还小（小图 + 竖屏框）：必须给出一块**合法**区域，而不是崩掉或算出图外的矩形。
        // 手算（200x100 配 540x1140、zoom 2）：baseScale = max(540/200, 1140/100) = 11.4 → scale = 22.8；
        // 区域 = 540/22.8 × 1140/22.8 ≈ 23.7 × 50 → 取整 23x50，居中落在 (88,25)，右/下边 111/75 ≤ 200/100。
        val small = requireNotNull(
            BackgroundRegionMath.cropFor(
                200, 100, boxWidth, boxHeight, BackgroundRegionMath.ViewState(2f, 0f, 0f)
            )
        ) { "图比框还小时也必须给出一块区域" }
        val smallRegion = BackgroundRegionMath.pixelRegion(200, 100, small)
        assertTrue(
            "区域必须完整落在图内，实际 ${smallRegion.left},${smallRegion.top} " +
                "${smallRegion.width}x${smallRegion.height}",
            smallRegion.left >= 0 && smallRegion.top >= 0 &&
                smallRegion.left + smallRegion.width <= 200 &&
                smallRegion.top + smallRegion.height <= 100
        )

        // 1x1 的图：任何缩放都只能框到那一个像素（区域尺寸的下限是 1px），
        // 所以结果必须是"整张图"这一块合法区域，而不是 0 宽/图外坐标/抛异常。
        val onePixel = requireNotNull(
            BackgroundRegionMath.cropFor(
                1, 1, boxWidth, boxHeight, BackgroundRegionMath.ViewState(2f, 0f, 0f)
            )
        ) { "1x1 的图也必须给出一块区域" }
        val onePixelRegion = BackgroundRegionMath.pixelRegion(1, 1, onePixel)
        assertEquals(0, onePixelRegion.left)
        assertEquals(0, onePixelRegion.top)
        assertEquals(1, onePixelRegion.width)
        assertEquals(1, onePixelRegion.height)
    }

    /**
     * 极端宽高比：缩放上限必须收窄，而且收窄到"区域刚好还合法"的位置。
     *
     * 4000x500 的横幅图配 540x1140 的竖屏框，旧实现在 8 倍时区域只有 29x62 像素
     * （归一化宽 0.00725 < BackgroundCrop.MIN_SIZE(0.02)），落库侧判它非法直接丢掉 ——
     * 用户缩放了、也点了「确定」，却什么都没存下来（状态文案还跳回"整张图居中铺满"）。
     */
    @Test
    fun `extreme aspect caps the zoom so the region stays legal`() {
        val imageWidth = 4000
        val imageHeight = 500
        val cap = BackgroundRegionMath.maxZoomFor(imageWidth, imageHeight, boxWidth, boxHeight)

        // 手算（框 540x1140、图 4000x500、MIN_SIZE = 0.02）：
        //   baseScale = max(540/4000, 1140/500) = 2.28
        //   宽：区域宽 = 540/(2.28 × zoom) 要 >= 0.02 × 4000 = 80px → zoom <= 540/(2.28×80) ≈ 2.9605
        //   高：区域高 = 1140/(2.28 × zoom) 要 >= 0.02 × 500 = 10px → zoom <= 50（不构成限制）
        //   80px 再补 2px 取整余量（按 82px 算）→ 上限 ≈ 540/(2.28×82) ≈ 2.8883
        assertTrue("极端比例必须收窄上限，实际 ${cap}", cap < BackgroundRegionMath.MAX_ZOOM)
        assertTrue("上限要贴着理论值 2.9605，不能随手收小，实际 ${cap}", cap > 2.9605f * 0.95f)
        assertTrue("上限绝不能超过理论值（那就不合法了），实际 ${cap}", cap <= 2.9606f)

        // 上限处必须**恰好合法**：两边都 >= MIN_SIZE，且被限制的那一边只比下限多一点点
        val atCap = requireNotNull(
            BackgroundRegionMath.cropFor(
                imageWidth, imageHeight, boxWidth, boxHeight,
                BackgroundRegionMath.ViewState(cap, 0f, 0f)
            )
        ) { "上限处的缩放必须能框出一块能用的区域" }
        assertTrue("宽必须 >= MIN_SIZE，实际 ${atCap.width}", atCap.width >= BackgroundCrop.MIN_SIZE)
        assertTrue("高必须 >= MIN_SIZE，实际 ${atCap.height}", atCap.height >= BackgroundCrop.MIN_SIZE)
        assertTrue(
            "上限处要贴着下限（恰好合法），实际 ${atCap.width}",
            atCap.width < BackgroundCrop.MIN_SIZE * 1.05f
        )

        // 边界值：区域尺寸会被写成整数像素，而下限是按**归一化浮点值**判的 —— 正好压在下限上的
        // 80px（= 0.02 × 4000）算出来其实比 0.02f 小 2e-8，会被判非法。上限必须留出这点取整余量，
        // 这条断言把这个浮点陷阱钉住，免得日后有人把余量当成多余的。
        val exactlyAtFloor = BackgroundCrop(0.5f, 0.5f, 0.5f + 80f / imageWidth, 1f)
        assertEquals(80f / imageWidth, exactlyAtFloor.width, 0.0001f)
        assertTrue("整数像素正好压在下限上时必须算非法", !exactlyAtFloor.isValid)

        // 反过来也要成立：超过上限 5% 就该框不出合法区域了，说明上限卡在边界上（没有收得过狠）
        val over = CropMath.sourceRegion(imageWidth, imageHeight, boxWidth, boxHeight, cap * 1.05f, 0f, 0f)
        val overCrop = BackgroundCrop(
            left = over.left.toFloat() / imageWidth,
            top = over.top.toFloat() / imageHeight,
            right = (over.left + over.width).toFloat() / imageWidth,
            bottom = (over.top + over.height).toFloat() / imageHeight
        )
        assertTrue("超过上限 5% 就该框不出合法区域了，实际 ${over.width}px", !overCrop.isValid)

        // 预览侧（BackgroundRegionPane 的手势）与落库侧共用上面这一个上限：手指最多缩到 cap，
        // 落库拿到的一定是合法区域 —— "能缩、确定后却静默什么都没存"这条路被堵死了。
        val gestureZoom = (1f * BackgroundRegionMath.MAX_ZOOM).coerceIn(1f, cap) // 手势想把 1 倍捏到 8 倍
        assertTrue("手势必须先被夹到上限内，实际 ${gestureZoom}", gestureZoom <= cap)
        val fromGesture = requireNotNull(
            BackgroundRegionMath.cropFor(
                imageWidth, imageHeight, boxWidth, boxHeight,
                BackgroundRegionMath.ViewState(gestureZoom, 0f, 0f)
            )
        ) { "手势夹过之后落库必须成功" }
        assertTrue("手势路径落库的区域必须合法，实际 ${fromGesture}", fromGesture.isValid)

        // 落库侧要更硬：**即使传入超限的 zoom** 也得存下一块合法区域（旧实现在这里是 null），
        // 拖到四个角也一样 —— 区域尺寸恒等于取景框 / scale，不会被图片边界挤扁。
        for (offsetX in listOf(0f, 99999f, -99999f)) {
            for (offsetY in listOf(0f, 99999f, -99999f)) {
                val stored = requireNotNull(
                    BackgroundRegionMath.cropFor(
                        imageWidth, imageHeight, boxWidth, boxHeight,
                        BackgroundRegionMath.ViewState(BackgroundRegionMath.MAX_ZOOM, offsetX, offsetY)
                    )
                ) { "8 倍（超限）加位移 (${offsetX}, ${offsetY}) 也必须存下区域，不能静默降级" }
                assertTrue("存下的区域必须合法，实际 ${stored}", stored.isValid)
                assertTrue("宽必须 >= MIN_SIZE，实际 ${stored.width}", stored.width >= BackgroundCrop.MIN_SIZE)
                assertTrue("高必须 >= MIN_SIZE，实际 ${stored.height}", stored.height >= BackgroundCrop.MIN_SIZE)
            }
        }
    }

    /**
     * 这个修复**不能**顺手缩小正常图片的可缩放范围：常用尺寸的上限必须仍然等于
     * [BackgroundRegionMath.MAX_ZOOM]。
     *
     * 上限只在"再放大区域就小到 MIN_SIZE 以下"时才收窄（例如 4000x500 配竖屏框），
     * 而正常比例的照片在 8 倍处离下限还很远（2000x1500 配 540x1140 时区域是 88x187 像素）。
     */
    @Test
    fun `normal aspect keeps the full zoom range`() {
        val normalSizes = listOf(
            2000 to 1500, // 编辑器预览的常见尺寸（AppearanceViewModel.REGION_EDIT_PX 量级）
            4000 to 3000, // 12MP 照片
            1080 to 2400, // 竖屏截图
            2400 to 1080, // 横屏截图
            1440 to 1080, // 1080p 壁纸
            1000 to 1000, // 正方形
            800 to 600,
            3000 to 4000, // 竖拍照片
            2000 to 1125, // 16:9
            1024 to 768
        )
        for ((width, height) in normalSizes) {
            assertEquals(
                "竖屏取景框下 ${width}x${height} 的上限被收窄了",
                BackgroundRegionMath.MAX_ZOOM,
                BackgroundRegionMath.maxZoomFor(width, height, boxWidth, boxHeight),
                0.0001f
            )
            assertEquals(
                "横屏取景框下 ${width}x${height} 的上限被收窄了",
                BackgroundRegionMath.MAX_ZOOM,
                BackgroundRegionMath.maxZoomFor(width, height, boxHeight, boxWidth),
                0.0001f
            )
        }

        // 8 倍处仍然框得出合法区域，而且区域尺寸与收紧前逐位一致（手算 540/6.08 × 1140/6.08 ≈ 88x187）
        val crop = requireNotNull(
            BackgroundRegionMath.cropFor(
                2000, 1500, boxWidth, boxHeight,
                BackgroundRegionMath.ViewState(BackgroundRegionMath.MAX_ZOOM, 0f, 0f)
            )
        ) { "正常图片在 8 倍处必须还能存下区域" }
        assertEquals("宽应当还是 88/2000", 88f / 2000f, crop.width, 0.002f)
        assertEquals("高应当还是 187/1500", 187f / 1500f, crop.height, 0.002f)
        assertTrue("8 倍处必须合法，实际 ${crop}", crop.isValid)
    }

    /**
     * 退化输入下的上限：不能崩，也不能小于 1。
     *
     * 上限小于 1 是**会炸**的：调用方写的是 `coerceIn(1f, 上限)`，Kotlin 的 coerceIn 在
     * min > max 时直接抛 IllegalArgumentException。所以无论输入多离谱，返回值都必须在 [1, MAX_ZOOM]。
     */
    @Test
    fun `zoom cap survives degenerate inputs`() {
        // 图尺寸没读到 / 取景框还没量到（首帧）：不额外限制（这些输入本来也走不到落库那一步）
        assertEquals(
            BackgroundRegionMath.MAX_ZOOM,
            BackgroundRegionMath.maxZoomFor(0, 0, boxWidth, boxHeight),
            0.0001f
        )
        assertEquals(
            BackgroundRegionMath.MAX_ZOOM,
            BackgroundRegionMath.maxZoomFor(2000, 1500, 0f, 0f),
            0.0001f
        )
        // 1x1 与"图比框还小"：区域最小只能是 1 个像素，而 1px 在这么小的图上远大于 MIN_SIZE → 不收窄
        assertEquals(
            BackgroundRegionMath.MAX_ZOOM,
            BackgroundRegionMath.maxZoomFor(1, 1, boxWidth, boxHeight),
            0.0001f
        )
        assertEquals(
            BackgroundRegionMath.MAX_ZOOM,
            BackgroundRegionMath.maxZoomFor(100, 100, boxWidth, boxHeight),
            0.0001f
        )

        // 离谱比例（比取景框还长几十倍）也在 [1, MAX_ZOOM] 内，绝不返回 0 或负数
        for ((width, height) in listOf(1 to 1, 3 to 3, 50 to 50, 200 to 100, 4000 to 168, 8000 to 4, 4 to 8000)) {
            val cap = BackgroundRegionMath.maxZoomFor(width, height, boxWidth, boxHeight)
            assertTrue("${width}x${height} 的上限必须 >= 1，实际 ${cap}", cap >= 1f)
            assertTrue(
                "${width}x${height} 的上限必须 <= MAX_ZOOM，实际 ${cap}",
                cap <= BackgroundRegionMath.MAX_ZOOM
            )
        }

        // 退化输入下 cropFor 的既有行为不变：1x1 的图仍然给出"整张图"这一块合法区域
        val onePixel = requireNotNull(
            BackgroundRegionMath.cropFor(
                1, 1, boxWidth, boxHeight, BackgroundRegionMath.ViewState(2f, 0f, 0f)
            )
        ) { "1x1 的图也必须给出一块区域" }
        assertEquals(1f, onePixel.width, 0.0001f)
        assertEquals(1f, onePixel.height, 0.0001f)
    }
}
