package com.example.bilimonitor.ui.nav

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

internal fun Modifier.dockScrub(
    count: Int, selected: Int, layoutDirection: LayoutDirection, onPreview: (Float?) -> Unit, onCommit: (Int) -> Unit
): Modifier = pointerInput(count, selected, layoutDirection) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val width = size.width
        val height = size.height
        if (count < 1 || width < 1 || height < 1) return@awaitEachGesture
        fun inside(point: Offset) = point.x >= 0f && point.x < width && point.y >= 0f && point.y < height
        if (!inside(down.position)) return@awaitEachGesture
        fun index(point: Offset): Float {
            val visual = (point.x / width * count - 0.5f).coerceIn(0f, (count - 1).toFloat())
            return if (layoutDirection == LayoutDirection.Rtl) count - 1 - visual else visual
        }
        fun tab(point: Offset): Int {
            val visual = (point.x / width * count).toInt().coerceIn(0, count - 1)
            return if (layoutDirection == LayoutDirection.Rtl) count - 1 - visual else visual
        }
        var dragging = false
        try {
            onPreview(tab(down.position).toFloat())
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.singleOrNull()
                if (change == null || change.id != down.id || change.isConsumed || size.width != width || size.height != height) {
                    event.changes.forEach { it.consume() }
                    break
                }
                val position = change.position
                if (!dragging) {
                    val delta = position - down.position
                    val horizontal = abs(delta.x)
                    val vertical = abs(delta.y)
                    if (vertical > viewConfiguration.touchSlop && vertical >= horizontal) {
                        change.consume()
                        break
                    }
                    dragging = horizontal > viewConfiguration.touchSlop && horizontal > vertical
                }
                if (!change.pressed) {
                    if (dragging || !inside(position)) change.consume()
                    if (dragging && inside(position)) {
                        val target = tab(position)
                        if (target != selected) onCommit(target)
                    }
                    break
                }
                if (dragging) {
                    change.consume()
                    onPreview(index(position))
                }
            }
        } finally {
            onPreview(null)
        }
    }
}
