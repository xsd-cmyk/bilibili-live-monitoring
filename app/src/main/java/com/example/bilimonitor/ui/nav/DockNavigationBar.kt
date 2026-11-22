package com.example.bilimonitor.ui.nav

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.repository.AppearanceSettings
import com.example.bilimonitor.ui.common.LiquidGlass
import com.example.bilimonitor.ui.common.floatingCardShadowElevation

private val DockCorner = 40.dp
internal val DockGutter = 8.dp
private val DockOuterMargin = 10.dp
private val DockMinHeight = 80.dp
private val DockContentGap = 12.dp
internal val DefaultDockClearance = DockMinHeight + (DockOuterMargin + DockGutter) * 2 + DockContentGap
internal val LocalDockClearance = compositionLocalOf<Dp?> { null }

@Composable
internal fun DockNavigationBar(
    destinations: List<BottomDestination>, currentRoute: String?, onSelect: (BottomDestination) -> Unit,
    scene: GraphicsLayer?, sceneOrigin: () -> Offset, appearance: AppearanceSettings,
    interactive: Boolean = true, onClearanceChanged: (Dp) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val fill = if (scene == null) colors.surfaceContainer else Color.Transparent
    val density = LocalDensity.current
    val material = if (scene != null) rememberGraphicsLayer() else null
    val selectedIndex = destinations.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val preview = remember { mutableStateOf<Float?>(null) }
    val select by rememberUpdatedState(onSelect)
    Box(Modifier.fillMaxWidth().navigationBarsPadding()
        .onSizeChanged { onClearanceChanged(with(density) { it.height.toDp() } + DockContentGap) }
        .padding(horizontal = 12.dp, vertical = DockOuterMargin)) {
        Surface(modifier = Modifier.matchParentSize().padding(DockGutter), shape = RoundedCornerShape(DockCorner),
            color = fill, tonalElevation = 0.dp, shadowElevation = floatingCardShadowElevation(fill, 8.dp)) {
            if (material != null && scene != null) {
                LiquidGlass(Modifier.fillMaxSize().drawWithContent {
                    material.record { this@drawWithContent.drawContent() }
                    drawLayer(material)
                }, scene, sceneOrigin, CornerSize(DockCorner), appearance)
            }
        }
        DockLens(Modifier.matchParentSize(), destinations.size, selectedIndex, preview, material, appearance)
        Row(Modifier.fillMaxWidth().padding(DockGutter).height(IntrinsicSize.Min).selectableGroup()
            .then(if (!interactive) Modifier else Modifier.dockScrub(destinations.size, selectedIndex, LocalLayoutDirection.current, onPreview = { preview.value = it },
                onCommit = { select(destinations[it]) })), verticalAlignment = Alignment.CenterVertically) {
            destinations.forEach { dest ->
                val selected = dest.route == currentRoute
                val ink = if (selected) colors.onPrimaryContainer else colors.onSurface
                val interaction = remember(dest.route) { MutableInteractionSource() }
                Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = DockMinHeight)
                    .selectable(selected, enabled = interactive, interactionSource = interaction, indication = null, role = Role.Tab) {
                        if (!selected) onSelect(dest)
                    }.then(if (material == null) Modifier.clip(RoundedCornerShape(16.dp))
                        .indication(interaction, LocalIndication.current) else Modifier).padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    val feedback = if (material != null) Modifier.indication(interaction, ripple(bounded = false, radius = 21.dp)) else Modifier
                    Box(feedback) { Icon(dest.icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = ink) }
                    Spacer(Modifier.height(3.dp))
                    Text(dest.label, style = MaterialTheme.typography.labelMedium, color = ink)
                }
            }
        }
    }
}
