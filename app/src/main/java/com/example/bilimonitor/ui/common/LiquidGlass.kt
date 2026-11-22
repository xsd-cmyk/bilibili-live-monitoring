package com.example.bilimonitor.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.repository.AppearanceSettings
import com.example.bilimonitor.ui.theme.LocalEffectiveDarkTheme

@Composable
internal fun LiquidGlass(
    modifier: Modifier,
    scene: GraphicsLayer?,
    sceneOrigin: () -> Offset,
    corner: CornerSize,
    appearance: AppearanceSettings,
    overlay: Color = Color.Transparent,
    wallpaper: GlassWallpaper? = null,
    sharedBlur: Boolean = false,
    sampleScale: Float = 1f
) {
    Spacer(modifier.glassMaterial(scene, sceneOrigin, corner, appearance, overlay, wallpaper, sharedBlur, sampleScale))
}

@Composable
internal fun Modifier.glassMaterial(
    scene: GraphicsLayer?, sceneOrigin: () -> Offset, corner: CornerSize, appearance: AppearanceSettings,
    overlay: Color, wallpaper: GlassWallpaper?, sharedBlur: Boolean, sampleScale: Float,
    behindContent: Boolean = false
): Modifier {
    val density = LocalDensity.current
    val blur = if (sharedBlur) 0f else with(density) { appearance.glassBlur.dp.toPx() }
    var extent by remember { mutableStateOf(IntSize.Zero) }
    val radius = corner.toPx(androidx.compose.ui.geometry.Size(extent.width.toFloat(), extent.height.toFloat()), density)
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    val direct = remember(wallpaper, extent, radius, appearance.glassHighlight) {
        if (Build.VERSION.SDK_INT >= 33 && wallpaper != null && extent != IntSize.Zero)
            WallpaperLens(wallpaper, extent, radius, appearance.glassHighlight) else null
    }
    val effect = remember(extent, blur, radius, appearance.glassHighlight, direct) {
        if (direct != null) return@remember null
        val frost = if (blur > 0f) RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null
        if (Build.VERSION.SDK_INT >= 33 && extent.width > 0 && extent.height > 0) {
            val lens = lensEffect(extent, radius, appearance.glassHighlight)
            (if (frost == null) lens else RenderEffect.createChainEffect(lens, frost)).asComposeRenderEffect()
        } else frost?.asComposeRenderEffect()
    }
    val dark = LocalEffectiveDarkTheme.current
    val sample: DrawScope.() -> Unit = {
        if (origin != Offset.Unspecified) {
            val shift = sceneOrigin() - origin
            if (Build.VERSION.SDK_INT >= 33 && direct != null) direct.draw(drawContext.canvas.nativeCanvas, -shift, size)
            else if (scene != null) translate(shift.x, shift.y) {
                if (sampleScale == 1f) drawLayer(scene) else scale(sampleScale, Offset.Zero) { drawLayer(scene) }
            }
        }
    }
    return onSizeChanged { extent = it }.onGloballyPositioned { origin = if (behindContent) it.localToScreen(Offset.Zero) else it.positionInRoot() }
        .drawWithCache {
            val corners = CornerRadius(radius)
            val base = (if (dark) Color(0xFF101922) else Color(0xFFF6FAFF)).copy(alpha = appearance.glassTint)
            val glint = appearance.glassHighlight * if (dark) 0.40f else 0.66f
            val sheen = Brush.linearGradient(0f to Color.White.copy(alpha = glint),
                0.31f to Color.White.copy(alpha = glint * 0.09f), 0.65f to Color.Transparent,
                1f to Color(0xFFCBEFFF).copy(alpha = glint * 0.48f),
                start = Offset.Zero, end = Offset(size.width * 0.83f, size.height))
            val width = appearance.glassRimWidth.dp.toPx().coerceAtMost(size.minDimension / 2f)
            val rim = appearance.glassRimAlpha
            val edge = Brush.linearGradient(0f to Color.White.copy(alpha = rim),
                0.26f to Color.White.copy(alpha = rim * 0.68f),
                0.52f to Color(0xFF526683).copy(alpha = rim * 0.4f),
                0.78f to Color(0xFFD5F6FF).copy(alpha = rim * 0.8f), 1f to Color.White.copy(alpha = rim),
                end = Offset(size.width, size.height))
            val inset = Offset(width / 2f, width / 2f)
            val edgeSize = Size(size.width - width, size.height - width)
            val edgeRadius = CornerRadius((radius - width / 2f).coerceAtLeast(0f))
            val stroke = Stroke(width)
            val filtered = if (behindContent && effect != null) obtainGraphicsLayer().apply { renderEffect = effect } else null
            onDrawWithContent {
                if (!behindContent) drawContent()
                else if (filtered == null) sample() else {
                    filtered.record { sample() }
                    drawLayer(filtered)
                }
                drawRoundRect(base, cornerRadius = corners)
                if (overlay.alpha > 0f) drawRoundRect(overlay, cornerRadius = corners)
                if (glint > 0f) drawRoundRect(sheen, cornerRadius = corners)
                if (rim > 0f && width > 0f) drawRoundRect(edge, inset, edgeSize, edgeRadius, style = stroke)
                if (behindContent) drawContent()
            }
        }.then(if (behindContent) Modifier else
            (if (direct == null) Modifier.graphicsLayer { clip = true; renderEffect = effect } else Modifier).drawBehind(sample))
}

internal class GlassWallpaper(val bitmap: Bitmap?, val region: BackgroundRegionMath.PixelRegion?, val viewport: IntSize,
    val base: Int, val opacity: Float, val dim: Float) {
    val sampler = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        if (Build.VERSION.SDK_INT >= 33) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
    } }
}

@RequiresApi(33)
private class WallpaperLens(wall: GlassWallpaper, extent: IntSize, radius: Float, highlight: Float) {
    private val shader = lensShader(WALL_INPUT, extent, radius, highlight).apply {
        setColorUniform("base", wall.base)
        val region = wall.region
        val scale = if (region == null) 1f else maxOf(wall.viewport.width.toFloat() / region.width,
            wall.viewport.height.toFloat() / region.height).coerceAtLeast(0.001f)
        val startX = (region?.left ?: 0) + ((region?.width ?: 0) - wall.viewport.width / scale) / 2f
        val startY = (region?.top ?: 0) + ((region?.height ?: 0) - wall.viewport.height / scale) / 2f
        setFloatUniform("imageStart", startX, startY)
        setFloatUniform("inverseScale", 1f / scale)
        setFloatUniform("opacity", if (region == null) 0f else wall.opacity)
        setFloatUniform("dimming", if (region == null || wall.opacity <= 0f) 1f else 1f - wall.dim)
        wall.sampler?.let { setInputShader("wall", it) }
    }
    private val paint = Paint().also { it.shader = shader }
    private var lastOrigin = Offset.Unspecified
    fun draw(canvas: android.graphics.Canvas, origin: Offset, size: Size) {
        if (origin != lastOrigin) {
            shader.setFloatUniform("origin", origin.x, origin.y)
            lastOrigin = origin
        }
        canvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}

@RequiresApi(33)
private fun lensShader(input: String, extent: IntSize, corner: Float, highlight: Float) = RuntimeShader(input + LENS_SOURCE).apply {
    setFloatUniform("extent", extent.width.toFloat(), extent.height.toFloat())
    setFloatUniform("corner", corner)
    setFloatUniform("highlight", highlight)
}

@RequiresApi(33)
private fun lensEffect(extent: IntSize, corner: Float, highlight: Float): RenderEffect =
    RenderEffect.createRuntimeShaderEffect(lensShader(SCENE_INPUT, extent, corner, highlight), "scene")

private const val SCENE_INPUT = "uniform shader scene; half4 sampleScene(float2 xy) { return scene.eval(xy); }"
private const val WALL_INPUT = """
uniform shader wall;
layout(color) uniform half4 base;
uniform float2 origin, imageStart;
uniform float inverseScale, opacity, dimming;
half4 sampleScene(float2 xy) {
    half4 pixel = opacity > 0.0 ? wall.eval(imageStart + (xy + origin) * inverseScale) * opacity : half4(0.0);
    return half4((pixel.rgb + base.rgb * (1.0 - pixel.a)) * dimming, 1.0);
}
"""
private const val LENS_SOURCE = """
uniform float2 extent;
uniform float corner;
uniform float highlight;
half4 main(float2 xy) {
    float2 halfSize = extent * 0.5;
    float radius = min(corner, min(halfSize.x, halfSize.y));
    float2 centered = xy - halfSize;
    float2 delta = abs(centered) - halfSize + radius;
    float2 outside = max(delta, 0.0);
    float magnitude = length(outside);
    float distance = magnitude + min(max(delta.x, delta.y), 0.0) - radius;
    float depth = max(0.0, -distance) / max(radius, 1.0);
    if (depth >= 1.0) return sampleScene(xy);
    float bevel = 1.0 - smoothstep(0.0, 0.72, depth);
    float2 normal = sign(centered) * outside / max(magnitude, 0.001);
    float2 sampleAt = xy - normal * (radius * 0.90 * bevel * bevel);
    half4 glass = sampleScene(clamp(sampleAt, float2(0.5), extent - 0.5));
    float light = dot(normal, float2(-0.48, -0.88));
    float glint = highlight * (0.28 + 0.72 * abs(light)) * exp2(-15.0 * depth);
    float innerEdge = highlight * max(0.0, -light) * exp2(-24.0 * abs(depth - 0.16));
    glass.rgb *= 1.0 - 0.34 * innerEdge;
    glass.rgb = mix(glass.rgb, half3(glass.a), glint);
    return glass;
}
"""
