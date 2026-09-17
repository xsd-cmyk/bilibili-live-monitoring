package com.example.bilimonitor.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale

private val BiliPink = Color(0xFFFB7299)
private val BiliPinkDark = Color(0xFFC4667F)
private val LiveGreen = Color(0xFF2E9E5B)

private val LightColors = lightColorScheme(
    primary = BiliPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1EA),
    onPrimaryContainer = Color(0xFF3A0020),
    secondary = Color(0xFF6EA6FF),
    surface = Color(0xFFFFFBFF)
)

private val DarkColors = darkColorScheme(
    primary = BiliPinkDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1132),
    onPrimaryContainer = Color(0xFFFFD9E4)
)

/**
 * 应用"卡片外观"（用户要求：卡片本身可调透明度与明暗）。
 *
 * 为什么改 `colorScheme` 而不是逐个页面改 `Card(colors = ...)`：
 * Material3 的 `Card`/`Surface` 容器色全部取自 colorScheme 的 surface 系列，
 * 在主题层统一替换，**全应用的卡片一起生效**，不必去动几十处界面代码 ——
 * 也就不会出现"某个页面漏改、看起来像 bug"的情况。
 *
 * 各 surface 角色一起改是为了保持它们之间的相对层次（surfaceContainerLow/High 等），
 * 否则会出现"有的卡片变透明了、有的还是实心"。
 *
 * ★ 压暗必须**连带换前景色**：浅色主题的 surface 接近纯白、onSurface 接近纯黑，
 *   只压 surface 的话 dim 拉到 0.9 就是"近黑卡片 + 近黑正文"（用户反馈：字看不见了）。
 *   Card 的正文色来自 `contentColorFor(容器色)`，而 surface 系列一律映射到 onSurface、
 *   surfaceVariant 映射到 onSurfaceVariant —— 跟着底色翻转这两个就够了。
 *
 * @param dim   0~0.9，越大越暗（叠加黑色）
 * @param alpha 0.3~1，卡片的不透明度；小于 1 时背景图会透出来
 */
internal fun applyCardAppearance(
    scheme: ColorScheme,
    dim: Float,
    alpha: Float
): ColorScheme {
    val d = dim.coerceIn(0f, 0.9f)
    val a = alpha.coerceIn(0.3f, 1f)
    if (d == 0f && a == 1f) return scheme
    fun tint(c: Color): Color = Color(
        red = c.red * (1f - d),
        green = c.green * (1f - d),
        blue = c.blue * (1f - d),
        alpha = a
    )
    val surface = tint(scheme.surface)
    return scheme.copy(
        surface = surface,
        surfaceVariant = tint(scheme.surfaceVariant),
        surfaceContainer = tint(scheme.surfaceContainer),
        surfaceContainerLow = tint(scheme.surfaceContainerLow),
        surfaceContainerLowest = tint(scheme.surfaceContainerLowest),
        surfaceContainerHigh = tint(scheme.surfaceContainerHigh),
        surfaceContainerHighest = tint(scheme.surfaceContainerHighest),
        surfaceBright = tint(scheme.surfaceBright),
        // surfaceDim 本身就是"暗面"，压暗会让它更暗，但保持一致才有层次
        surfaceDim = tint(scheme.surfaceDim),
        onSurface = foregroundFor(surface, scheme.onSurface, OnSurfaceOnDark),
        onSurfaceVariant = foregroundFor(surface, scheme.onSurfaceVariant, OnSurfaceVariantOnDark)
    )
}

/**
 * 应用主题（用户要求：主题色可自定义）。
 *
 * @param customPrimary 用户选的品牌色（ARGB）。为 null 时用默认的 B 站粉。
 * @param transparentSurface 设置了自己背景图时为 true：把 Scaffold 会涂的 `background`
 *   设为透明，好让背景图透出来（卡片仍是不透明的 surface，保证文字可读）。
 *
 * 只替换 `primary` 系列与 `secondary` 系列：`primaryContainer` 由它按固定比例派生
 * （见 [containerFor]），这样选任何颜色都不会出现"主色与容器色撞在一起、文字看不清"。
 * 全量派生一套 M3 色调表需要 HCT 色彩空间，那是另一个量级的工作，
 * 而收益只在"更协调"这一点上 —— 不值得为此把主题层换成动态取色库。
 * 派生逻辑本身在 [applyPrimary] 里，预览/测试与这里共用同一份。
 */
@Composable
fun BiliMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    customPrimary: Int? = null,
    transparentSurface: Boolean = false,
    /** 卡片外观：压暗 0~0.9 / 不透明度 0.3~1（用户要求） */
    cardDim: Float = 0f,
    cardAlpha: Float = 1f,
    content: @Composable () -> Unit
) {
    val withPrimary = applyPrimary(
        base = if (darkTheme) DarkColors else LightColors,
        customPrimary = customPrimary,
        darkTheme = darkTheme
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalEffectiveDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = applyCardAppearance(
            if (transparentSurface) withPrimary.copy(background = Color.Transparent) else withPrimary,
            cardDim, cardAlpha
        ),
            content = content
        )
    }
}

/** 简单亮度判断（sRGB 加权）：决定前景文字用深色还是白色。 */
internal fun isLight(color: Color): Boolean = srgbLuminance(color) > 0.6f

/** sRGB 加权亮度（0 = 黑，1 = 白）。[isLight] 与压暗后的前景判据共用同一套算法。 */
internal fun srgbLuminance(color: Color): Float =
    0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

/** 深色卡片上的正文 / 次要文字（即 M3 深色主题的 onSurface / onSurfaceVariant）。 */
private val OnSurfaceOnDark = Color(0xFFE6E1E5)
private val OnSurfaceVariantOnDark = Color(0xFFCAC4D0)

/**
 * 前景翻转的亮度阈值。
 *
 * 不复用 [isLight] 的 0.6：两者判的不是同一件事 —— 0.6 回答的是"这个颜色算不算浅色"
 * （用来挑主色上的文字），而前景该翻的临界点是**黑白文字对比度相等**的那个灰阶
 * （sRGB 加权约 0.46）；更早翻转会得到"中灰卡片 + 浅灰字"这种更难读的组合。
 */
private const val ForegroundFlipLuminance = 0.42f

/**
 * 底色被压暗到"深色底"、而前景仍然偏暗时，换成亮色前景。
 *
 * 只处理这一个方向：[applyCardAppearance] 只会压暗、不会提亮，所以不需要反向分支。
 */
private fun foregroundFor(container: Color, current: Color, lightOnDark: Color): Color =
    if (srgbLuminance(container) < ForegroundFlipLuminance &&
        srgbLuminance(current) < ForegroundFlipLuminance
    ) {
        lightOnDark
    } else {
        current
    }

/**
 * 由品牌色派生"容器色"：深色主题往暗里压、浅色主题往白里提。
 * 比例是试出来的（0.35 / 0.72），保证容器与主色同色系但可区分。
 */
internal fun containerFor(primary: Color, darkTheme: Boolean): Color = if (darkTheme) {
    Color(
        red = primary.red * 0.35f,
        green = primary.green * 0.35f,
        blue = primary.blue * 0.35f,
        alpha = 1f
    )
} else {
    Color(
        red = primary.red + (1f - primary.red) * 0.72f,
        green = primary.green + (1f - primary.green) * 0.72f,
        blue = primary.blue + (1f - primary.blue) * 0.72f,
        alpha = 1f
    )
}

/**
 * 就地把自定义主题色应用到一套配色方案上（纯函数，不依赖 Composable 环境）。
 *
 * 抽成单一函数的原因：这段派生逻辑此前在 [BiliMonitorTheme] 与 `schemeWith` 里各写了一遍，
 * 改一处忘一处就会出现"预览/测试看到的配色与实际渲染不一致"。
 *
 * @param customPrimary 用户选的品牌色（ARGB）。为 null 时原样返回 [base]（用默认的 B 站粉）。
 */
internal fun applyPrimary(base: ColorScheme, customPrimary: Int?, darkTheme: Boolean): ColorScheme {
    if (customPrimary == null) return base
    val primary = Color(customPrimary)
    val container = containerFor(primary, darkTheme)
    return base.copy(
        primary = primary,
        onPrimary = if (isLight(primary)) Color(0xFF1A1A1A) else Color.White,
        primaryContainer = container,
        onPrimaryContainer = if (isLight(container)) Color(0xFF1A1A1A) else Color.White,
        // secondary 系列也纳入自定义主题色（用户反馈：监控中的紫色横幅、标签选中态的
        // 紫色背景没有跟着主题色走）。根因是 M3 的 FilterChip 选中态取 secondaryContainer、
        // 横幅类组件也多用它 —— 只替换 primary 系列时，它们会保留色彩方案里的默认紫色。
        secondary = primary,
        onSecondary = if (isLight(primary)) Color(0xFF1A1A1A) else Color.White,
        secondaryContainer = container,
        onSecondaryContainer = if (isLight(container)) Color(0xFF1A1A1A) else Color.White
    )
}

/**
 * 背景图在窗口里的实际摆放（用于"液态玻璃"Dock 取同一块像素做模糊）。
 *
 * Compose 没有"采样背后内容"的公开 API（`Modifier.blur` 只模糊自己，
 * 系统 `setBackgroundBlurRadius` 是整窗口生效）。但背景图是**我们自己画的**，
 * 所以可以把它的缩放与原点告诉下层，让任意一条浮层按自己的屏幕位置
 * 裁出对应区域再模糊 —— 这才是真实的"玻璃下透出壁纸"，而不是拿半透明色糊弄。
 *
 * @param scale  ContentScale.Crop 后的缩放倍数（**显示区域** → 窗口像素）
 * @param originX/Y 显示区域左上角在窗口坐标里的位置（Crop 居中后通常为负）
 * @param srcLeft/srcTop/srcWidth/srcHeight 当前显示的**区域**在原图里的像素矩形：
 *   用户框选过显示区域时它不是整张图（见 [AppBackground] 的 crop 参数）。少了这几个字段，
 *   玻璃取样就会按整张图去算 —— 用户框选之后 Dock 里透出的那块壁纸会整体错位。
 *   ★ 它们的取值与 [AppBackground] 里交给 `BitmapPainter` 的 `srcOffset/srcSize` **同源**
 *   （都由 `BackgroundRegionMath.pixelRegion` 算出来），这是"玻璃与背景对得上"的依据。
 */
data class BackgroundImageInfo(
    val bitmap: android.graphics.Bitmap,
    val scale: Float,
    val originX: Float,
    val originY: Float,
    val srcLeft: Int = 0,
    val srcTop: Int = 0,
    val srcWidth: Int = bitmap.width,
    val srcHeight: Int = bitmap.height
) {
    /** 把窗口坐标系里的矩形换算成原图上的像素矩形（供模糊层取样）。 */
    fun sourceRect(x: Float, y: Float, width: Float, height: Float): android.graphics.Rect {
        val left = srcLeft + ((x - originX) / scale).toInt()
        val top = srcTop + ((y - originY) / scale).toInt()
        val right = srcLeft + ((x + width - originX) / scale).toInt().coerceAtMost(srcWidth)
        val bottom = srcTop + ((y + height - originY) / scale).toInt().coerceAtMost(srcHeight)
        return android.graphics.Rect(
            left.coerceIn(srcLeft, (srcLeft + srcWidth - 1).coerceAtLeast(srcLeft)),
            top.coerceIn(srcTop, (srcTop + srcHeight - 1).coerceAtLeast(srcTop)),
            right.coerceAtLeast(srcLeft + 1),
            bottom.coerceAtLeast(srcTop + 1)
        )
    }
}

/** 当前**生效**的深色状态（用户可能强制浅色/深色，不能各处置接 isSystemInDarkTheme）。 */
val LocalEffectiveDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 实时背景层（用户要求：液态玻璃改成实时渲染）。
 *
 * 由内容侧把「整个界面录进去的 GraphicsLayer」广播下来，玻璃层每帧取这一层里
 * 自己那块区域做模糊 —— 这才是**真正的实时背景模糊**：
 * 折射的是滚动中的列表内容，而不只是静态壁纸。
 *
 * 为什么能行：Compose UI 1.7+ 提供 `rememberGraphicsLayer()` + `DrawScope.drawLayer()`，
 * 可以把一段内容的绘制录进图层再在别处重绘（Haze 库的原理）。
 * 在本项目里它替代了原先"只能采样自己画的壁纸"的限制。
 *
 * 为空（未提供）时玻璃层退回壁纸采样方案。
 */
val LocalRealtimeBackdropLayer =
    androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.graphics.layer.GraphicsLayer?> { null }
/** 供浮层读取当前背景图信息；为空表示用户没设背景图。 */
val LocalBackgroundImageInfo = androidx.compose.runtime.staticCompositionLocalOf<BackgroundImageInfo?> { null }

/**
 * 背景层**实测**的窗口宽高比（宽/高）。
 *
 * ★ 为什么不能各页面自己拿 `LocalConfiguration.screenWidthDp/screenHeightDp` 去算：
 *   那个值在 API 31+ 不含系统栏，而背景图铺的是 [AppBackground] 那个 Box 的**实际**尺寸
 *   （本应用的内容区与它差多少，比例就差多少）。差几个百分点，用户按比例框出来的区域
 *   渲染时就会被 `ContentScale.Crop` 再切掉一条 —— 正是"框选所见 ≠ 最终背景"的根源。
 *   所以这里下发的是 AppBackground 用 `onSizeChanged` 量到的那份数值，全应用只有这一份来源。
 *
 * 0f = 还没量到（首帧）或不在 [AppBackground] 之下：调用方应退回 Configuration 推算。
 */
val LocalBackgroundViewportAspect = androidx.compose.runtime.staticCompositionLocalOf { 0f }

/**
 * 背景图片层（用户要求：可调明暗与透明度）。
 *
 * 画在**所有界面之下**：放在 `setContent` 最外层的 Box 里，配合
 * [BiliMonitorTheme] 的 `transparentSurface = true`，Scaffold 的容器色就不会盖住它。
 *
 * @param dim 0 = 原图，1 = 全黑压暗；@param alpha 图片本身不透明度
 * @param crop 用户框选的显示区域（归一化）；null = 整张图居中铺满（默认，也是本功能上线前的行为）
 * @param frostBlur 背景整体磨砂半径（dp）。>0 时壁纸本身被模糊，半透明卡片透出的就是磨砂壁纸。
 */
@Composable
fun AppBackground(
    bitmap: android.graphics.Bitmap?,
    dim: Float,
    alpha: Float,
    crop: com.example.bilimonitor.data.repository.BackgroundCrop? = null,
    frostBlur: Float = 0f,
    content: @Composable () -> Unit
) {
    // 计算背景图的摆放方式并广播给下层（液态玻璃 Dock 需要它来取"背后的像素"）。
    // ★ 尺寸必须**实测**，不能用 LocalConfiguration.screenWidthDp 推算：
    //   那个值在 API 31+ 不含系统栏，而本应用是 edge-to-edge 的（内容铺进状态栏/导航栏区域），
    //   两者不相等 —— 差多少，玻璃取样的区域就整体偏多少。
    var windowSize by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
    }
    // 用户框选的显示区域 → 原图像素矩形。null（默认）/坏值 / 图片太小都落到"整张图"，
    // 也就是升级前的行为。
    val region = androidx.compose.runtime.remember(bitmap, crop) {
        bitmap?.let {
            com.example.bilimonitor.ui.common.BackgroundRegionMath.pixelRegion(
                it.width, it.height, crop
            )
        }
    }
    val info = androidx.compose.runtime.remember(bitmap, windowSize, region) {
        if (bitmap == null || region == null || region.width <= 0 || region.height <= 0 ||
            windowSize.width == 0 || windowSize.height == 0
        ) {
            null
        } else {
            // ★ 这里算的是**显示区域**的缩放与原点（不是整张图的）：
            //   下面实际画出来的也只有这块区域（见 BitmapPainter 的 srcOffset/srcSize），
            //   两者用同一组数字，玻璃层按窗口位置取样时才会与背景严丝合缝。
            val scale = maxOf(
                windowSize.width.toFloat() / region.width,
                windowSize.height.toFloat() / region.height
            )
            BackgroundImageInfo(
                bitmap = bitmap,
                scale = scale,
                originX = (windowSize.width - region.width * scale) / 2f,
                originY = (windowSize.height - region.height * scale) / 2f,
                srcLeft = region.left,
                srcTop = region.top,
                srcWidth = region.width,
                srcHeight = region.height
            )
        }
    }
    // 背景铺满的窗口比例：取景框要按它来画（见 [LocalBackgroundViewportAspect] 的说明）。
    // 只有这里**实测**到的才是真值；0 表示还没量到（首帧），调用方应退回 Configuration 推算。
    val viewportAspect = if (windowSize.width > 0 && windowSize.height > 0) {
        windowSize.width.toFloat() / windowSize.height.toFloat()
    } else {
        0f
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBackgroundImageInfo provides info,
        LocalBackgroundViewportAspect provides viewportAspect
    ) {
        Box(
            Modifier.fillMaxSize().onSizeChanged {
                if (it != windowSize) windowSize = it
            }
        ) {
            if (bitmap != null && alpha > 0f && region != null) {
                // ★ 只画用户框选的那块区域：`BitmapPainter(srcOffset, srcSize)` + `ContentScale.Crop`
                //   与"把这块区域先裁成一张新图、再用 ContentScale.Crop 铺满"是同一个结果，
                //   但不复制像素、不重新编码，也就不会有额外的一份位图常驻内存。
                //   区域比例与窗口比例一致时（用户在编辑器里看到的就是这个比例）Crop 恰好了无裁切，
                //   即"所见即所得"；比例不一致（选完之后转了屏）时退回居中裁切，与编辑器里的
                //   处理一致（见 BackgroundRegionMath.viewStateFor 的说明）。
                val painter = androidx.compose.runtime.remember(bitmap, region) {
                    androidx.compose.ui.graphics.painter.BitmapPainter(
                        image = bitmap.asImageBitmap(),
                        srcOffset = androidx.compose.ui.unit.IntOffset(region.left, region.top),
                        srcSize = androidx.compose.ui.unit.IntSize(region.width, region.height)
                    )
                }
                Image(
                    painter = painter,
                    contentDescription = null,
                    // 整体磨砂：把壁纸本身模糊掉。这是"卡片也玻璃化"的关键 ——
                    // 卡片只需半透明（主题层已统一处理 surface 系列），透出来的就是磨砂壁纸，
                    // 不必去改上百处 Card { } 调用点。
                    modifier = if (frostBlur > 0f) {
                        Modifier.fillMaxSize().graphicsLayer {
                            renderEffect = android.graphics.RenderEffect
                                .createBlurEffect(frostBlur, frostBlur, android.graphics.Shader.TileMode.CLAMP)
                                .asComposeRenderEffect()
                        }
                    } else {
                        Modifier.fillMaxSize()
                    },
                    contentScale = ContentScale.Crop,
                    alpha = alpha.coerceIn(0f, 1f)
                )
                // 压暗层：把整体亮度降下来，避免浅色背景把正文对比度冲淡
                if (dim > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f))))
                }
            }
            content()
        }
    }
}

object StatusColors {
    val Live = LiveGreen
    val Offline = Color(0xFF9E9E9E)
    val Round = Color(0xFFE8A03C)
    val Unknown = Color(0xFF757575)
    val Stale = Color(0xFFBF7A28)
}
