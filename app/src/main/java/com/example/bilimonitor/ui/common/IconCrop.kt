package com.example.bilimonitor.ui.common

/**
 * 取景框裁剪的几何计算（纯函数，便于单测）。
 *
 * 交互模型：图片先按 `ContentScale.Crop` 铺满取景框，用户再用双指缩放 + 拖动决定最终要哪一块。
 * 这里负责把"缩放倍数 + 位移"换算成**原图坐标系里的矩形**，并保证它始终落在图片范围内
 * （否则 `Bitmap.createBitmap` 会抛 IllegalArgumentException 或裁出空白边）。
 *
 * 所有偏移量的单位都是**取景框像素**（与手势给出的位移一致），换算关系：
 * `显示尺寸 = 原图尺寸 × 基准缩放 × 用户缩放`，其中
 * `基准缩放 = max(框宽 / 图宽, 框高 / 图高)`（= Crop 铺满所需的最小缩放）。
 *
 * ★ 取景框**不一定是正方形**：图标裁剪用的是正方形，而"背景图显示区域"用的是与屏幕同比例的
 *   矩形（竖屏约 9:19.5）。两处的缩放/位移/换算完全是同一套数学，所以这里按 `boxWidth/boxHeight`
 *   参数化，正方形只是它的一个特例（旧的那些 `boxSize` 重载仍在，内部转调）。
 *   复制一份"矩形版的 CropMath"是最糟的选择：两套数学迟早会漂移，而它们算的都是同一件事。
 */
object CropMath {

    /** 原图坐标系里的裁剪矩形（左上角 + 边长）。**正方形**取景框用，语义保持不变。 */
    data class CropRect(val left: Int, val top: Int, val size: Int)

    /** 原图坐标系里的裁剪矩形（左上角 + 宽高）。矩形取景框用。 */
    data class CropRegion(val left: Int, val top: Int, val width: Int, val height: Int)

    /** 铺满取景框所需的最小缩放（Crop 语义）。 */
    fun baseScale(imageWidth: Int, imageHeight: Int, boxSize: Float): Float =
        baseScale(imageWidth, imageHeight, boxSize, boxSize)

    /** 铺满取景框所需的最小缩放（Crop 语义）；取景框可以为矩形。 */
    fun baseScale(imageWidth: Int, imageHeight: Int, boxWidth: Float, boxHeight: Float): Float {
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return 1f
        return maxOf(boxWidth / imageWidth, boxHeight / imageHeight)
    }

    /**
     * 限制位移：拖动不能让取景框露出图片以外的区域。
     *
     * @param offset 用户拖动的累计位移（取景框像素，右/下为正）
     * @return 允许的位移；图片在该轴上不足以填满取景框时固定为 0（居中）
     */
    fun clampOffset(
        imageWidth: Int,
        imageHeight: Int,
        boxSize: Float,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<Float, Float> = clampOffset(
        imageWidth, imageHeight, boxSize, boxSize, zoom, offsetX, offsetY
    )

    /** [clampOffset] 的矩形取景框版本。 */
    fun clampOffset(
        imageWidth: Int,
        imageHeight: Int,
        boxWidth: Float,
        boxHeight: Float,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): Pair<Float, Float> {
        val scale = baseScale(imageWidth, imageHeight, boxWidth, boxHeight) * zoom.coerceAtLeast(1f)
        if (scale <= 0f) return 0f to 0f
        // 显示后的尺寸（取景框像素）
        val displayedW = imageWidth * scale
        val displayedH = imageHeight * scale
        val maxX = ((displayedW - boxWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((displayedH - boxHeight) / 2f).coerceAtLeast(0f)
        return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
    }

    /**
     * 计算原图上的**正方形**裁剪矩形（图标用）。
     *
     * @param zoom      ≥ 1 的用户缩放（1 = 刚好铺满）
     * @param offsetX/Y 位移（取景框像素）；越界不会算出图外的矩形（见 [sourceRegion]）
     */
    fun sourceRect(
        imageWidth: Int,
        imageHeight: Int,
        boxSize: Float,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): CropRect {
        val region = sourceRegion(imageWidth, imageHeight, boxSize, boxSize, zoom, offsetX, offsetY)
        // 正方形取景框下 width == height：同一个 boxSize、同一个 scale，且位移已被 [clampOffset]
        // 夹住 → 两侧的"图片边界钳制"都不会生效，区域尺寸恒为 boxSize / scale。
        // 仍然取较小者：让"绝不超出图片"这条不变量不依赖上面这段推导（日后若动了钳制语义也不会漏出去）。
        return CropRect(region.left, region.top, minOf(region.width, region.height))
    }

    /**
     * 计算原图上的裁剪矩形（**可以是矩形**，给"背景图显示区域"用）。
     *
     * ★ 位移**越界时在内部先夹住**（[clampOffset]），而不是把 left/top 钳到图片边、再让宽高去
     *   迁就剩下的那点空间。后者的洞：拖到远超边界时区域会被压成一条 1px 的窄缝
     *   ——尺寸与取景框完全不成比例，归一化之后还会小到 `BackgroundCrop.MIN_SIZE` 以下，
     *   于是整块区域被判非法、被上层丢掉（用户一路拖到底，点「确定」却什么都没存下来）。
     *   先夹位移则：区域尺寸**恒等于 取景框尺寸 / scale**，位置停在图片边上 —— 这才是
     *   "拖到底就停住"的语义，也才配得上本文件开头那句"保证它始终落在图片范围内"。
     *   已经夹过的位移过一遍 [clampOffset] 不变（幂等），所以正常路径（图标裁剪、背景取景
     *   都是先夹再调）逐位不变。
     *
     * @param zoom      ≥ 1 的用户缩放（1 = 刚好铺满）；< 1 按 1 算
     * @param offsetX/Y 位移（取景框像素）；越界会被夹住，调用方忘了夹也不会算出图外的区域
     */
    fun sourceRegion(
        imageWidth: Int,
        imageHeight: Int,
        boxWidth: Float,
        boxHeight: Float,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): CropRegion {
        if (imageWidth <= 0 || imageHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) {
            return CropRegion(0, 0, 1, 1)
        }
        val (safeX, safeY) = clampOffset(
            imageWidth, imageHeight, boxWidth, boxHeight, zoom, offsetX, offsetY
        )
        val scale = baseScale(imageWidth, imageHeight, boxWidth, boxHeight) * zoom.coerceAtLeast(1f)
        // 取景框左上角在"显示坐标系"里的位置：图片居中后按位移平移
        val displayedW = imageWidth * scale
        val displayedH = imageHeight * scale
        val leftInDisplayed = (displayedW - boxWidth) / 2f - safeX
        val topInDisplayed = (displayedH - boxHeight) / 2f - safeY
        val left = (leftInDisplayed / scale).toInt().coerceIn(0, imageWidth - 1)
        val top = (topInDisplayed / scale).toInt().coerceIn(0, imageHeight - 1)
        // 位移已夹住后，下面两个上界不会生效（由 baseScale 的定义可知 框宽/scale ≤ 图片宽）：
        // 它们只是"图比一个像素还小"之类退化输入的兜底，保证返回值永远是图片内的合法子矩形。
        val width = (boxWidth / scale).toInt().coerceIn(1, imageWidth - left)
        val height = (boxHeight / scale).toInt().coerceIn(1, imageHeight - top)
        return CropRegion(left, top, width, height)
    }
}
