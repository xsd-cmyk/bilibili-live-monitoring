package com.example.bilimonitor.ui.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.repository.AppearanceSettings
import kotlin.math.abs

private val leadingSpring = spring<Float>(dampingRatio = 0.81f, stiffness = 1050f, visibilityThreshold = 0.002f)
private val trailingSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 260f, visibilityThreshold = 0.002f)

@Composable
internal fun DockLens(
    modifier: Modifier, count: Int, selected: Int, preview: State<Float?>,
    material: GraphicsLayer?, appearance: AppearanceSettings
) {
    val held = preview.value
    val target = held ?: selected.toFloat()
    val head = animateFloatAsState(target, leadingSpring, label = "dockHead")
    val tail = animateFloatAsState(target, trailingSpring, label = "dockTail")
    val lift = animateFloatAsState(if (held == null) 0f else 1f, trailingSpring, label = "dockLift")
    val accent = MaterialTheme.colorScheme.primaryContainer
    val contour = MaterialTheme.colorScheme.onSurface
    Spacer(modifier.graphicsLayer().drawWithCache {
        val gutter = DockGutter.roundToPx().toFloat()
        val trackWidth = (size.width - gutter * 2f).coerceAtLeast(0f)
        val trackHeight = (size.height - gutter * 2f).coerceAtLeast(1f)
        val cell = trackWidth / count.coerceAtLeast(1)
        val restWidth = minOf((cell - 4.dp.toPx()).coerceAtLeast(1f), 84.dp.toPx())
        val restHeight = (trackHeight - 14.dp.toPx()).coerceAtLeast(1f)
        val restSize = Size(restWidth, restHeight)
        val path = Path()
        val sheenClip = Path()
        val body = if (material == null) accent else accent.copy(alpha = 0.36f + 0.55f * appearance.glassTint)
        val shine = appearance.glassHighlight
        val rim = appearance.glassRimAlpha
        val streak = Brush.linearGradient(0f to Color.Transparent, 0.38f to Color.Transparent,
            0.72f to Color.White.copy(alpha = 0.70f * shine), 1f to Color.Transparent,
            end = Offset(restWidth, restHeight * 0.4f))
        val edge = Brush.linearGradient(0f to Color.White.copy(alpha = rim),
            0.40f to Color.White.copy(alpha = rim * 0.18f), 0.68f to contour.copy(alpha = rim * 0.36f),
            1f to Color.White.copy(alpha = rim * 0.88f),
            end = Offset(restWidth, restHeight))
        val stroke = Stroke(appearance.glassRimWidth.dp.toPx())
        onDrawBehind {
            if (size.width < 1f || size.height < 1f) return@onDrawBehind
            val direction = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f
            val visualHead = if (direction > 0f) head.value else count - 1 - head.value
            val lag = ((head.value - tail.value) * cell * direction).coerceIn(-restWidth * 0.85f, restWidth * 0.85f)
            val raised = lift.value.coerceIn(0f, 1f)
            val strain = abs(lag) / restWidth
            val heldWidth = if (material == null) restWidth * 1.12f else minOf(cell * 1.18f, 114.dp.toPx())
            val heldHeight = if (material == null) minOf(trackHeight, restHeight * 1.10f) else trackHeight + 12.dp.toPx()
            val side = if (material == null) gutter else gutter * 0.3f
            val width = (restWidth + raised * (heldWidth - restWidth) + abs(lag)).coerceAtMost((size.width - 2f * side).coerceAtLeast(0f))
            val height = restHeight * (1f - 0.16f * strain * (1f - raised)) + raised * (heldHeight - restHeight)
            if (width <= 0f) return@onDrawBehind
            val left = (gutter + (visualHead + 0.5f) * cell - lag * 0.43f - width / 2f).coerceIn(side, maxOf(side, size.width - side - width))
            val top = (size.height - height) / 2f
            val center = Offset(left + width / 2f, size.height / 2f)
            val radius = minOf(width, height) / 2f
            val corner = CornerRadius(radius * restWidth / width, radius * restHeight / height)
            if (material != null) {
                path.reset()
                path.addRoundRect(RoundRect(Rect(left, top, left + width, top + height), CornerRadius(minOf(width, height) / 2f)))
                val zoom = 1.10f + 0.22f * raised + 0.20f * strain
                clipPath(path) { scale(zoom, center) { translate(gutter, gutter) { drawLayer(material) } } }
            }
            translate(left, top) {
                scale(width / restWidth, height / restHeight, Offset.Zero) {
                    drawRoundRect(body, size = restSize, cornerRadius = corner)
                    if (material != null) {
                        if (shine > 0f) {
                            sheenClip.reset()
                            sheenClip.addRoundRect(RoundRect(Rect(Offset.Zero, restSize), corner))
                            clipPath(sheenClip) {
                                translate(lag * 0.48f - restWidth * 0.12f, 0f) {
                                    drawRect(streak, size = restSize, alpha = 0.28f + 0.46f * strain + 0.26f * raised)
                                }
                            }
                        }
                        if (stroke.width > 0f) drawRoundRect(edge, size = restSize, cornerRadius = corner, style = stroke)
                    }
                }
            }
        }
    })
}
