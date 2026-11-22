package com.example.bilimonitor.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.repository.AppearanceSettings

internal class CardBackdrop(val layer: GraphicsLayer?, val wallpaper: GlassWallpaper?, val origin: () -> Offset, val appearance: AppearanceSettings, val sampleScale: Float, val screenOrigin: () -> Offset)
internal val LocalCardBackdrop = staticCompositionLocalOf<CardBackdrop?> { null }

@Composable
private fun CardPanel(modifier: Modifier, shape: Shape, color: Color, content: @Composable (Boolean) -> Unit) {
    val backdrop = LocalCardBackdrop.current
    Box(modifier, propagateMinConstraints = true) {
        if (backdrop != null) {
            val scheme = MaterialTheme.colorScheme
            val tint = when (color) {
                scheme.primaryContainer, scheme.secondaryContainer, scheme.errorContainer, scheme.inverseSurface -> color.copy(alpha = 0.76f)
                else -> Color.Black.copy(alpha = backdrop.appearance.cardDim)
            }
            LiquidGlass(Modifier.matchParentSize().clip(shape), backdrop.layer, backdrop.origin,
                (shape as? CornerBasedShape)?.topStart ?: CornerSize(0.dp), backdrop.appearance, tint, backdrop.wallpaper, sharedBlur = true, sampleScale = backdrop.sampleScale)
        }
        content(backdrop != null)
    }
}

@Composable
internal fun AppCard(
    modifier: Modifier = Modifier, colors: CardColors = CardDefaults.cardColors(), onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    CardPanel(modifier, CardDefaults.shape, colors.containerColor) { glass ->
        val palette = if (glass) colors.copy(containerColor = Color.Transparent) else colors
        if (onClick == null) Card(colors = palette, content = content)
        else Card(onClick = onClick, colors = palette, content = content)
    }
}

@Composable
internal fun AppCardSurface(
    modifier: Modifier = Modifier, shape: Shape, color: Color, border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp, onClick: (() -> Unit)? = null, content: @Composable () -> Unit
) {
    val ink = contentColorFor(color)
    CardPanel(modifier, shape, color) { glass ->
        val fill = if (glass) Color.Transparent else color
        val shadow = if (glass) 0.dp else shadowElevation
        if (onClick == null) Surface(shape = shape, color = fill, contentColor = ink, border = border,
            shadowElevation = shadow, content = content)
        else Surface(onClick = onClick, shape = shape, color = fill, contentColor = ink, border = border,
            shadowElevation = shadow, content = content)
    }
}

@Composable
internal fun GlassInfoPanel(content: @Composable () -> Unit) {
    if (LocalCardBackdrop.current == null) { content(); return }
    AppCardSurface(modifier = Modifier.padding(horizontal = 16.dp), shape = CardDefaults.shape,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) { content() }
    }
}
