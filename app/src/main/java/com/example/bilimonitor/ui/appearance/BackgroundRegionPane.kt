package com.example.bilimonitor.ui.appearance

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.repository.BackgroundCrop
import com.example.bilimonitor.ui.common.BackgroundRegionMath
import com.example.bilimonitor.ui.common.CropMath
import com.example.bilimonitor.ui.theme.LocalBackgroundViewportAspect

/**
 * 「选择显示区域」的框选界面（用户要求：自己决定拿图片的哪一块当背景）。
 *
 * ## 为什么取景框必须与屏幕同比例
 *
 * 背景的最终渲染是"把选中的区域按 `ContentScale.Crop` 铺满整个窗口"。如果取景框的比例
 * 与窗口不一致，用户框出来的东西就**不是**最终效果：渲染时还会再按窗口比例裁掉一条
 * （横图取中间那一块时最明显 —— 主体被切掉，而用户在取景框里明明看到它完整地在框内）。
 * 所以这里的框 = 可用区域里最大的、比例为 [LocalBackgroundViewportAspect]（背景层**实测**的
 * 窗口比例）的矩形，框内所见即最终所得。
 *
 * ## 交互
 *
 *  - 单指拖动平移、双指捏合缩放（与图标裁剪同一套 [CropMath]，只是框是矩形的）；
 *  - 框**外**的图片压暗，框内保持原样：一眼能看出哪一块会被用上、哪些会被切掉；
 *  - 「重置」回到默认的居中铺满（= 整图 Crop，也是没有框选时的行为），
 *    「取消」不保存任何东西，「确定」才写入区域参数。
 *
 * ## 性能
 *
 * 拖动/缩放**只改几何状态**（zoom + 位移两个 Float），不重新解码、不重新编码、不裁剪位图；
 * 屏幕上画的是调用方传来的**降采样**预览图（见 `AppearanceViewModel.REGION_EDIT_PX`）。
 * 真正的裁切只在渲染时按参数取区域（`BitmapPainter(srcOffset/srcSize)`），不做像素复制。
 */
@Composable
fun BackgroundRegionPane(
    /** 用于预览的位图（通常已降采样）；只有点「确定」时才把它换算成归一化区域。 */
    source: Bitmap,
    /** 当前已保存的区域（null = 默认居中铺满）：用它恢复上次的取景，而不是每次从居中重来。 */
    initialCrop: BackgroundCrop?,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: (BackgroundCrop?) -> Unit
) {
    val aspect = rememberBackgroundViewportAspect()
    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    // 取景框 = 可用区域里最大的、比例 = 屏幕比例的矩形（居中）。
    // 一次算好给"绘制"和"手势"两处共用：两处若各算一遍，差一点点就会让
    // "手指拖到的"和"画出来的"对不上（拖动手感会发飘）。
    val frame = remember(areaSize, aspect) { regionFrame(areaSize, aspect) }

    // 有效最大缩放：与落库侧（BackgroundRegionMath.cropFor 内部的钳制）调用的是**同一个函数**。
    // 手势缩不到"区域会小于 BackgroundCrop.MIN_SIZE"的倍数，用户就不会遇到
    // "缩了半天、点「确定」却什么都没存"（静默降级）。两处各算一份必然漂移，所以只留一处算式。
    val maxZoom = remember(source, frame) {
        BackgroundRegionMath.maxZoomFor(source.width, source.height, frame.width, frame.height)
    }

    var state by remember(source) { mutableStateOf(BackgroundRegionMath.ViewState.DEFAULT) }
    var activeCrop by remember(source, initialCrop) { mutableStateOf(initialCrop) }

    // 框的尺寸就绪（或发生变化：转屏、分屏、大字体）时，按**当前选中的区域**重新摆一次。
    // 不能把 zoom/位移原样留着：同一组数值换到另一个尺寸的框上，框住的是另一块区域 ——
    // 用户会看到"什么都没动，取景自己变了"。
    LaunchedEffect(frame.width, frame.height) {
        if (frame.width > 0f && frame.height > 0f) {
            state = BackgroundRegionMath.viewStateFor(
                region = BackgroundRegionMath.pixelRegion(source.width, source.height, activeCrop),
                imageWidth = source.width,
                imageHeight = source.height,
                boxWidth = frame.width,
                boxHeight = frame.height
            )
        }
    }

    val image = remember(source) { source.asImageBitmap() }
    val aspectLabel = remember(aspect) { formatAspect(aspect) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "拖动移动位置、双指缩放，取景框内的区域就是最终的背景",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "取景框比例与屏幕一致（${aspectLabel}），框内会等比铺满整个屏幕、不会拉伸变形；" +
                "框外压暗的部分不会被用到。原图不会被修改，随时可以再调整。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // 拖动时图片会被放大到超出这块区域，必须裁掉：否则它会画到标题栏/按钮区上面去
                .clipToBounds()
                .onSizeChanged { areaSize = it }
                .pointerInput(source, frame, maxZoom) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        // 上限用 maxZoom（= 这张图 + 这个取景框还框得出合法区域的倍数），
                        // 与落库侧同一个口径：手指能缩到的最大倍数，恰好就是"点确定一定存得下"的倍数。
                        val zoom = (state.zoom * gestureZoom).coerceIn(1f, maxZoom)
                        // 位移必须夹住：拖动不能让取景框露出图片以外的空白
                        val clamped = CropMath.clampOffset(
                            source.width, source.height,
                            frame.width, frame.height,
                            zoom, state.offsetX + pan.x, state.offsetY + pan.y
                        )
                        // 先算出新状态再一起写：直接写回 state 再读它虽然也能拿到新值，
                        // 但"读自己刚写的状态"这种依赖太脆，日后改成别的方式就会被悄悄改坏。
                        val next = BackgroundRegionMath.ViewState(zoom, clamped.first, clamped.second)
                        state = next
                        activeCrop = BackgroundRegionMath.cropFor(
                            source.width, source.height,
                            frame.width, frame.height, next
                        )
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (frame.width <= 0f || frame.height <= 0f) return@Canvas
                val scale = CropMath.baseScale(
                    source.width, source.height, frame.width, frame.height
                ) * state.zoom
                val drawWidth = source.width * scale
                val drawHeight = source.height * scale
                // 与 CropMath 的换算同一套定义：显示尺寸 = 原图 × 基准缩放 × 用户缩放，
                // 图片先相对取景框居中，再叠加位移。
                val left = frame.left + (frame.width - drawWidth) / 2f + state.offsetX
                val top = frame.top + (frame.height - drawHeight) / 2f + state.offsetY
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(source.width, source.height),
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    // 取整会让两侧各差不到一像素，用 ceil 补上：宁可多画一点（会被裁掉），
                    // 也不要在边缘露出一条底色。
                    dstSize = IntSize(
                        kotlin.math.ceil(drawWidth).toInt(),
                        kotlin.math.ceil(drawHeight).toInt()
                    ),
                    filterQuality = FilterQuality.Medium
                )
                // 框外压暗：四条矩形围住取景框（比 clipPath 便宜，也不需要 saveLayer）
                val scrim = Color.Black.copy(alpha = 0.62f)
                drawRect(scrim, size = Size(size.width, frame.top.coerceAtLeast(0f)))
                drawRect(
                    scrim,
                    topLeft = Offset(0f, frame.bottom),
                    size = Size(size.width, (size.height - frame.bottom).coerceAtLeast(0f))
                )
                drawRect(
                    scrim,
                    topLeft = Offset(0f, frame.top),
                    size = Size(frame.left.coerceAtLeast(0f), frame.height)
                )
                drawRect(
                    scrim,
                    topLeft = Offset(frame.right, frame.top),
                    size = Size((size.width - frame.right).coerceAtLeast(0f), frame.height)
                )
                // 框线画两层（深色打底 + 白色亮线）：浅色图与深色图上都要看得清边界，
                // 单色线在背景图接近同色时会"消失"，用户就不知道框在哪了。
                drawRect(
                    color = Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(frame.left, frame.top),
                    size = Size(frame.width, frame.height),
                    style = Stroke(width = 4.dp.toPx())
                )
                drawRect(
                    color = Color.White,
                    topLeft = Offset(frame.left, frame.top),
                    size = Size(frame.width, frame.height),
                    style = Stroke(width = 1.5f.dp.toPx())
                )
            }
        }
        Text(
            when {
                busy -> "正在保存…"
                activeCrop == null -> "当前取景：整张图居中铺满（默认）"
                else -> "当前取景：已框选一块区域，点「确定」后生效"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onConfirm(activeCrop) },
                enabled = !busy
            ) { Text("确定") }
            TextButton(
                // 只重置**取景**：回到默认的整张图居中铺满，仍要点「确定」才落库 ——
                // 让「重置」直接落库的话，误触一次就把用户之前框好的区域悄悄删了。
                onClick = {
                    state = BackgroundRegionMath.ViewState.DEFAULT
                    activeCrop = null
                },
                enabled = !busy && state != BackgroundRegionMath.ViewState.DEFAULT
            ) { Text("重置") }
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
        }
    }
}

/** 取景框矩形（可用区域坐标，像素）：[left]/[top] 是它在可用区域里的左上角。 */
private data class RegionFrame(
    val width: Float,
    val height: Float,
    val left: Float,
    val top: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * 可用区域 [areaSize] 里最大的、比例为 [aspect]（宽/高）的矩形（居中）。
 *
 * 比例未知（首帧还没量到）时返回空矩形：调用方据此跳过这一帧的绘制与换算，
 * 而不是拿一个 0 去除（会得到 NaN，进而把整块背景算成一团乱码）。
 */
private fun regionFrame(areaSize: IntSize, aspect: Float): RegionFrame {
    val width = areaSize.width.toFloat()
    val height = areaSize.height.toFloat()
    if (width <= 0f || height <= 0f || aspect <= 0f || !aspect.isFinite()) {
        return RegionFrame(0f, 0f, 0f, 0f)
    }
    val frameWidth = minOf(width, height * aspect)
    val frameHeight = frameWidth / aspect
    return RegionFrame(frameWidth, frameHeight, (width - frameWidth) / 2f, (height - frameHeight) / 2f)
}

/**
 * 取景框比例：优先用背景层**实测**的窗口比例（唯一权威来源，见 [LocalBackgroundViewportAspect]），
 * 取不到（首帧、或不在背景层之下）才退回 Configuration 推算。
 *
 * 退回值只是"这一帧的兜底"：一旦量到真实尺寸就会换成真值，用户此时才可能开始拖动。
 */
@Composable
private fun rememberBackgroundViewportAspect(): Float {
    val measured = LocalBackgroundViewportAspect.current
    val configuration = LocalConfiguration.current
    return remember(measured, configuration) {
        when {
            measured > 0f && measured.isFinite() -> measured
            configuration.screenWidthDp > 0 && configuration.screenHeightDp > 0 ->
                configuration.screenWidthDp.toFloat() / configuration.screenHeightDp
            // 最后的兜底：竖屏手机的常见比例。写 0 会让取景框画不出来（整页空白），
            // 那才是真正的"显示异常"。
            else -> 9f / 19.5f
        }
    }
}

/** 比例的可读写法（1 : 2.11 / 1.78 : 1）。用 `Float.toString()` 拼而不是 `String.format`：
 *  它**恒定**用 `.` 作小数点，不受系统语言影响（某些语言下 `format("%.2f")` 会输出 "2,11"）。 */
private fun formatAspect(aspect: Float): String {
    val round2 = { value: Float -> kotlin.math.round(value * 100f) / 100f }
    return if (aspect >= 1f) {
        "${round2(aspect)} : 1"
    } else {
        "1 : ${round2(1f / aspect)}"
    }
}
