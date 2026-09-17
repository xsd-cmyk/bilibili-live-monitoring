package com.example.bilimonitor.ui.common

import com.example.bilimonitor.data.repository.BackgroundCrop

/**
 * 背景图"显示区域"的几何（纯函数，便于单测）。
 *
 * ## 它到底在算什么
 *
 * 用户在编辑器里看到的取景框，**比例与真实屏幕一致**（宽/高 = 背景层实测的窗口比例）。
 * 框内那一块图片就是最终的背景：渲染侧把这块区域按 `ContentScale.Crop` 铺进同样比例的窗口，
 * 两者是同一个区域、同一个比例，所以"框选时看到的"就是"最终看到的"。
 *
 * 三处输入输出的关系（[PixelRegion] 是唯一的中间量，三处共用）：
 *
 * ```
 *   编辑器手势(zoom/offset) --cropFor--> BackgroundCrop(归一化，落 DataStore)
 *                                              |
 *                         像素Region <--pixelRegion--+
 *                                              |
 *                          渲染/预览 (srcOffset+srcSize, ContentScale.Crop)
 * ```
 *
 * ★ 为什么落库的是**归一化**参数而不是像素：同一个设置会作用在两张尺寸不同的位图上
 *   （渲染侧按屏幕尺寸解码、编辑器按最长边降采样解码），只有比例能同时对上两边。
 * ★ 为什么要有 [cropFor] / [viewStateFor] 这对互逆函数：用户点开编辑器时必须**接着上次的
 *   取景继续调**（而不是回到居中），否则"再次调整"等于每次都得重新框一遍。
 */
object BackgroundRegionMath {

    /** 原图上的像素矩形（左上角 + 宽高，左上角为原点）。 */
    data class PixelRegion(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * 编辑器的视图状态。
     *
     * @param zoom    ≥ 1，相对"这张图刚好铺满取景框"的倍数（1 = 铺满，与 [CropMath] 同一口径）
     * @param offsetX/Y 位移，单位是**取景框像素**（与手势给出的位移一致）
     */
    data class ViewState(val zoom: Float, val offsetX: Float, val offsetY: Float) {
        companion object {
            /** 默认取景：铺满 + 居中 = 整个背景图居中铺满（本功能上线前的行为）。 */
            val DEFAULT = ViewState(1f, 0f, 0f)
        }
    }

    /** 一块区域按 Crop 语义铺进目标框时的摆放：`框坐标 = 区域坐标 × scale + origin`。 */
    data class Placement(val scale: Float, val originX: Float, val originY: Float) {
        /** 框坐标 → 区域内的像素坐标（取色/命中测试用）。 */
        fun regionX(boxX: Float): Float = (boxX - originX) / scale

        fun regionY(boxY: Float): Float = (boxY - originY) / scale
    }

    /**
     * 缩放的**绝对**上限（编辑器）。
     *
     * 比图标裁剪的 6 倍略大：背景要框的可能是照片里很小的一个主体；而 8 倍已经能让
     * 2000px 的长边只剩 250px（再放大就是马赛克，没有意义）。
     *
     * ★ 它只是上界，不是"这张图能放到多少"：具体能放到几倍由 [maxZoomFor] 按当前图与取景框算。
     */
    const val MAX_ZOOM = 8f

    /**
     * 区域像素的取整余量（[maxZoomFor] 用）。
     *
     * 取 2 而不是 1：区域尺寸会被 [CropMath.sourceRegion] 写成整数像素，向下取整最多损失接近 1px；
     * 而下限是按**归一化浮点值**判的 —— 正好压在下限上的整数像素会被判成非法（4000px 的图上
     * 80px 宽的归一化值比 0.02f 小 2e-8）。于是 1px 补取整、1px 让结果**严格**大于下限。
     */
    private const val REGION_PX_MARGIN = 2

    /**
     * 有效最大缩放：编辑器此刻**真正**能放到的倍数（介于 1 与 [MAX_ZOOM] 之间）。
     *
     * ★ 为什么不能只用 [MAX_ZOOM]：存储侧把"归一化后任一边小于 [BackgroundCrop.MIN_SIZE]"的区域
     *   判为非法（那种区域铺满整屏就是一帧马赛克），而区域尺寸 = 取景框 ÷ (基准缩放 × zoom)：
     *   图相对取景框越扁，同样的 zoom 框出来的区域越小。4000x500 的横幅图配 540x1140 的竖屏框，
     *   8 倍时区域只有 29x62 像素（归一化宽 0.00725 < 0.02）→ 落库那一侧判它非法、直接丢掉：
     *   用户明明缩放了、也点了「确定」，却什么都没存下来，状态文案还跳回"整张图居中铺满"。
     *
     * ★ 所以上限 = "区域宽高归一化后都还 >= MIN_SIZE 的最大 zoom"，再收一点点取整余量。
     *   预览侧（`BackgroundRegionPane` 的手势 clamp、[viewStateFor] 的恢复 clamp）与落库侧
     *   （[cropFor]）**都调用本函数** —— 全工程只有这一处算式。两边各算一份的话，只要有一处
     *   偏一点就又会出现"预览能缩、落库被判非法"的不一致，也就是这个缺陷的成因。
     *
     * 为什么比"刚好卡在下限"再少 2px 的区域：见 [REGION_PX_MARGIN]。
     *
     * @return 1f ~ [MAX_ZOOM]。图或取景框尺寸无效时返回 [MAX_ZOOM]（不额外限制：这些输入本来
     *         也走不到落库那一步）；下限夹到 1f，因为调用方写的是 `coerceIn(1f, 上限)`，
     *         上限小于 1 会直接抛异常 —— 图相对取景框扁到约 24:1 以上时确实无解（见 [cropFor]）。
     */
    fun maxZoomFor(
        imageWidth: Int,
        imageHeight: Int,
        boxWidth: Float,
        boxHeight: Float
    ): Float {
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return MAX_ZOOM
        val base = CropMath.baseScale(imageWidth, imageHeight, boxWidth, boxHeight)
        if (base <= 0f || !base.isFinite()) return MAX_ZOOM
        val needWidth = requiredRegionPx(imageWidth)
        val needHeight = requiredRegionPx(imageHeight)
        // 区域尺寸 = 框 / (base × zoom)，要求它 >= need 像素，解出 zoom 的上限。
        // need 为 1 表示"1 个像素就已经达标"（图这一边不足 50px），这一轴不构成限制。
        val widthBound = if (needWidth <= 1) Float.MAX_VALUE else boxWidth / (base * needWidth)
        val heightBound = if (needHeight <= 1) Float.MAX_VALUE else boxHeight / (base * needHeight)
        return minOf(MAX_ZOOM, widthBound, heightBound).coerceAtLeast(1f)
    }

    /**
     * 某一边上"区域至少要有多少像素"才算合法：归一化下限 [BackgroundCrop.MIN_SIZE] 换算成像素，
     * 再留 [REGION_PX_MARGIN] 个像素给取整与浮点误差。
     *
     * 图这一边不足 50px（= 1 / MIN_SIZE）时，1 个像素本身就已经大于 MIN_SIZE，返回 1 表示不限制。
     */
    private fun requiredRegionPx(imageSize: Int): Int {
        if (imageSize * BackgroundCrop.MIN_SIZE < 1f) return 1
        return ((imageSize * BackgroundCrop.MIN_SIZE).toInt() + REGION_PX_MARGIN)
            .coerceAtMost(imageSize)
    }

    /**
     * 归一化区域 → 原图像素矩形。
     *
     * crop 为 null、非法，或图片尺寸不可用时返回**整张图**（= 默认的居中铺满），
     * 这样调用方不必写分支：缺键的老安装、坏值、没框选过，走的都是同一条默认路径。
     */
    fun pixelRegion(imageWidth: Int, imageHeight: Int, crop: BackgroundCrop?): PixelRegion {
        val width = imageWidth.coerceAtLeast(1)
        val height = imageHeight.coerceAtLeast(1)
        if (crop == null || !crop.isValid || imageWidth <= 0 || imageHeight <= 0) {
            return PixelRegion(0, 0, width, height)
        }
        val left = (crop.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val top = (crop.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        // 宽高各自钳到图内：浮点截断最多差一个像素，取整后是原图上的 1px 偏移，
        // 肉眼不可见，也不会出现"右边界越过图外"（那会让 Crop 采样到空白）。
        val regionWidth = (crop.width * imageWidth).toInt().coerceIn(1, imageWidth - left)
        val regionHeight = (crop.height * imageHeight).toInt().coerceIn(1, imageHeight - top)
        return PixelRegion(left, top, regionWidth, regionHeight)
    }

    /** 一块区域按 Crop 铺进某个框（渲染、预览缩略图、取色反算共用这一处换算）。 */
    fun placement(region: PixelRegion, boxWidth: Float, boxHeight: Float): Placement {
        val scale = CropMath.baseScale(region.width, region.height, boxWidth, boxHeight)
        return Placement(
            scale = scale,
            originX = (boxWidth - region.width * scale) / 2f,
            originY = (boxHeight - region.height * scale) / 2f
        )
    }

    /**
     * 已有区域 → 编辑器的初始视图状态：让这块区域**按同一个比例**重新出现在取景框里，
     * 也就是用户上次确定时看到的样子（"再次调整"从当前取景继续，而不是从居中重来）。
     *
     * 区域比例与取景框比例不一致时（例如竖屏选好之后横屏再进来）用 `max` 取较大的缩放 =
     * Crop 居中：框里显示的是这块区域**居中裁出来的一小块**，与渲染侧对同一组参数的处理完全一致
     * —— 用户一进编辑器看到的，就是此刻真正生效的取景。
     *
     * ★ 缩放钳到 [maxZoomFor] 而不是 [MAX_ZOOM]：恢复出来的取景必须**仍然框得出合法区域**，
     *   否则用户一进编辑器就又被摆到"点确定没生效"的位置上（见 [maxZoomFor] 的说明）。
     */
    fun viewStateFor(
        region: PixelRegion,
        imageWidth: Int,
        imageHeight: Int,
        boxWidth: Float,
        boxHeight: Float
    ): ViewState {
        val base = CropMath.baseScale(imageWidth, imageHeight, boxWidth, boxHeight)
        if (base <= 0f || region.width <= 0 || region.height <= 0 ||
            imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f
        ) {
            return ViewState.DEFAULT
        }
        val scale = maxOf(boxWidth / region.width, boxHeight / region.height)
        val zoom = (scale / base).coerceIn(1f, maxZoomFor(imageWidth, imageHeight, boxWidth, boxHeight))
        val realScale = base * zoom
        // 取景框在这块区域上覆盖到的部分（比例不一致时是区域居中裁出来的一块）
        val visibleWidth = boxWidth / realScale
        val visibleHeight = boxHeight / realScale
        val frameLeft = region.left + (region.width - visibleWidth) / 2f
        val frameTop = region.top + (region.height - visibleHeight) / 2f
        // 与 CropMath.sourceRegion 的换算互逆：框左上角在图上的位置
        //   frameLeft = (imageWidth - boxWidth / scale) / 2 - offsetX / scale
        // 解出 offsetX 即下面这行。区域 = 整图时它恰好是 0（居中），这正是默认取景。
        val rawX = realScale * (imageWidth / 2f - frameLeft) - boxWidth / 2f
        val rawY = realScale * (imageHeight / 2f - frameTop) - boxHeight / 2f
        val (x, y) = CropMath.clampOffset(
            imageWidth, imageHeight, boxWidth, boxHeight, zoom, rawX, rawY
        )
        return ViewState(zoom, x, y)
    }

    /**
     * 编辑器视图状态 → 归一化区域（点"确定"时落库的就是它）。
     *
     * 返回 null 表示"**默认取景**"（既没缩放也没平移）：这时不写区域参数，渲染侧继续走
     * "整张图居中铺满"那条默认路径。两者在屏幕上完全等价，但只留一份"当前取景"的来源，
     * 就不会出现"存了一个 0,0,1,1 的区域，和缺键的默认行为差半个像素"这类对不上的情况。
     *
     * 只有两种 null，**越界拖动不在其中**：
     *  1. 上面那种默认取景；
     *  2. 图相对取景框扁到"任何缩放都框不出合法区域"（比例超过约 24:1，阈值由 [maxZoomFor] 决定）
     *     —— 这时 [maxZoomFor] 会夹到 1，用户根本缩放不了，写不写区域都不改变最终画面。
     * 除这两种以外，只要用户缩放或拖动过，这里**一定**返回一块合法区域：上限由 [maxZoomFor]
     * 给出，而预览侧用的是同一个上限，所以不存在"能缩、确定后却静默什么都没存"的倍数。
     * 位移越界（用户一路拖到底、甚至调用方忘了夹）走的是 clamp：区域照样合法，见
     * [CropMath.sourceRegion]。
     */
    fun cropFor(
        imageWidth: Int,
        imageHeight: Int,
        boxWidth: Float,
        boxHeight: Float,
        state: ViewState
    ): BackgroundCrop? {
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return null
        val untouched = state.zoom <= 1.0001f &&
            kotlin.math.abs(state.offsetX) < 0.5f &&
            kotlin.math.abs(state.offsetY) < 0.5f
        if (untouched) return null
        // 视图状态的合法域是 zoom ∈ [1, maxZoomFor(...)]（编辑器手势就是这么夹的，
        // [viewStateFor] 恢复取景时也这么夹）。越界的值只可能来自坏调用方，这里按同一个域夹一次：
        // 落库侧与预览侧共用同一个上限，才既不会"能缩、确定后区域被判非法丢掉"（静默降级），
        // 也不会出现两处口径不一致。早先这里夹的是 MAX_ZOOM —— 极宽/极高的图在 8 倍处区域会小于
        // BackgroundCrop.MIN_SIZE，于是被下面的 takeIf 丢掉：用户明明框了一块，点确定却什么都没存。
        // 位移不在这里夹：那是 CropMath.sourceRegion 的职责（它内部会夹），夹两层只会多一处会漂移的数学。
        val zoom = state.zoom.coerceIn(1f, maxZoomFor(imageWidth, imageHeight, boxWidth, boxHeight))
        val region = CropMath.sourceRegion(
            imageWidth, imageHeight, boxWidth, boxHeight,
            zoom, state.offsetX, state.offsetY
        )
        val crop = BackgroundCrop(
            left = region.left.toFloat() / imageWidth,
            top = region.top.toFloat() / imageHeight,
            right = (region.left + region.width).toFloat() / imageWidth,
            bottom = (region.top + region.height).toFloat() / imageHeight
        )
        return crop.takeIf { it.isValid }
    }
}
