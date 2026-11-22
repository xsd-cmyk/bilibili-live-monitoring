package com.example.bilimonitor.ui.common

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.dialogGlass(shape: Shape, color: Color): Modifier {
    val backdrop = LocalCardBackdrop.current ?: return this
    return clip(shape).glassMaterial(backdrop.layer, backdrop.screenOrigin,
        (shape as? CornerBasedShape)?.topStart ?: CornerSize(0.dp), backdrop.appearance,
        color.copy(alpha = 0.40f), backdrop.wallpaper, sharedBlur = true, sampleScale = backdrop.sampleScale,
        behindContent = true)
}

@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    title: @Composable () -> Unit, text: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null
) {
    val color = AlertDialogDefaults.containerColor
    AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, dismissButton = dismissButton,
        title = title, text = text, modifier = Modifier.dialogGlass(AlertDialogDefaults.shape, color),
        containerColor = if (LocalCardBackdrop.current == null) color else Color.Transparent)
}
