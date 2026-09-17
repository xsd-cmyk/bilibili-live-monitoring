package com.example.bilimonitor.ui.appearance

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilimonitor.data.repository.AppearanceSettingsRepository
import com.example.bilimonitor.data.repository.ColorHex
import com.example.bilimonitor.ui.common.BackgroundRegionMath
import com.example.bilimonitor.ui.common.CropMath
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.FloatingTopBarTitle
import com.example.bilimonitor.ui.common.MessageBanner
import com.example.bilimonitor.ui.theme.isLight

/**
 * 外观与主题（设置页入口同名）：名称与图标、主题色、背景图、卡片外观、夜间模式、底部导航。
 *
 * 界面里必须如实写清 Android 的能力边界（见 [AppearanceSettingsRepository] 的说明）：
 * 应用内的名称可以随便改；**桌面图标要靠"固定快捷方式"**——系统会弹确认框，
 * 桌面上多一个自定义入口，而应用抽屉里的原始图标不变。
 * 不写清楚的话，用户改完在桌面上找不到变化，会以为功能坏了。
 */
// FlowRow 属于 foundation 的 @ExperimentalLayoutApi（compose-bom 2024.12.01 仍是实验 API），
// 这个 OptIn 是**必要**的，不是随手加的；理由同 DataScreen：不能换行的 Row 在窄屏/大字体下
// 会把排在后面的按钮整块裁掉，用户点不到。
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    viewModel: AppearanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 每个「上传图片」的地方都有两个来源入口，但**下游只留一条路**：
    // 相册（系统照片选择器）与本地文件（SAF 文档选择器）拿到的都是 content:// Uri，
    // 一律交给 viewModel.pickImage / pickBackground 去采样解码与落盘。
    // 不给相册单开一套解码/保存 —— 两条路各写一遍，日后必然有一套行为漂移
    // （比如相册那条忘了限制解码尺寸就会 OOM），这正是本项目要避免的「同一件事两份口径」。
    //
    // 相册入口用 PickVisualMedia：Android 13+ 是系统照片选择器，低版本有 backport，
    // 都**不需要任何存储权限**（用户只把选中的那一张授权给应用，比申请 READ_MEDIA_IMAGES
    // 那种「整个相册随便读」的权限克制得多）。下游是立刻解码成 Bitmap 存进私有目录，
    // 不依赖 Uri 的长期授权，所以也不必 takePersistableUriPermission。
    val albumPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.pickImage(it) } }
    val albumBackgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.pickBackground(it) } }
    // 本地文件入口沿用原来的 OpenDocument（SAF），同样不额外申请权限。
    // 变量名带上 file 前缀是为了与上面的 album* 成对，避免日后分不清哪个是哪个来源。
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.pickImage(it) } }
    val fileBackgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.pickBackground(it) } }

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { FloatingTopBarTitle("外观与主题") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MessageBanner(message = state.message, onDismiss = { viewModel.clearMessage() })
            if (state.picking != null) {
                CropPane(
                    source = state.picking!!,
                    onCancel = { viewModel.cancelPicking() },
                    onConfirm = { viewModel.saveIcon(it) }
                )
                return@Column
            }
            // 框选"背景图显示区域"：与图标裁剪同样是整页接管，但取景框是**与屏幕同比例的矩形**
            // （不是正方形），用的也是降采样后的预览图 —— 相机里的大图直接拖会卡。
            if (state.regionEditing != null) {
                BackgroundRegionPane(
                    source = state.regionEditing!!,
                    initialCrop = state.backgroundCrop,
                    busy = state.busy,
                    onCancel = { viewModel.cancelRegionEdit() },
                    onConfirm = { viewModel.saveRegion(it) }
                )
                return@Column
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("应用名称", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "用于应用内标题与桌面快捷方式的名称。系统设置里\"应用信息\"页显示的名称由安装包决定，无法在运行时修改。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = { viewModel.onNameChanged(it) },
                            label = { Text("应用名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "最多 ${AppearanceSettingsRepository.MAX_NAME_LENGTH} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.saveName() },
                            enabled = !state.busy && state.name != state.savedName
                        ) { Text("保存名称") }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("应用图标", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = state.iconBitmap
                            Box(
                                Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (icon != null) {
                                    Image(
                                        bitmap = icon.asImageBitmap(),
                                        contentDescription = "自定义图标预览",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text("未设置", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.size(12.dp))
                            Column {
                                Text(
                                    "上传一张图片并裁剪成方形，作为桌面快捷方式的图标。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // 两个来源入口并排。用 FlowRow 而不是 Row：窄屏 + 大字体下这两个按钮
                        // （尤其「从本地文件选择」有 7 个字）一行放不下，Row 不会换行、只会把
                        // 后面那个裁掉，用户就再也点不到本地文件入口了。
                        //
                        // 文案**不再随「是否已经选过图」变化**（原来未选是「选择图片」、已选是
                        // 「重新选择」）：拆成两个按钮后，它们的差别是「图从哪来」，若都写成
                        // 「重新选择」就分不清哪个走相册、哪个走文件；而「是否已选」由左边的
                        // 预览缩略图 / 「未设置」如实表达，重新挑一张本来就是幂等的（只替换），
                        // 不需要按钮再喊一遍。
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    albumPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !state.busy
                            ) { Text("从相册选择") }
                            Button(
                                onClick = { filePicker.launch(arrayOf("image/*")) },
                                enabled = !state.busy
                            ) { Text("从本地文件选择") }
                        }
                    }
                }

                // 「放到桌面」紧跟「应用名称 / 应用图标」：这三张卡讲的是同一件事 ——
                // 先起名、再选图标，最后把它固定到桌面。原来它排在页面靠后（在底部导航之后），
                // 用户改完名称与图标还得往下翻才能找到"放到桌面"，流程被割断。
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("放到桌面", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Android 不允许第三方应用替换自己的桌面图标（安装包里写死了）。" +
                                "系统支持的替代做法是\"固定快捷方式\"：点下面的按钮，系统会弹窗确认，" +
                                "同意后桌面上会出现一个用你自定义图标与名称的入口——" +
                                "应用抽屉里的原始图标保持不变。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            // 结果由 ViewModel 写进 state.message，再由页面顶部的横幅展示
                            // （原先这里传了个空 lambda，用户点完看不到任何反馈）
                            onClick = { viewModel.requestPinShortcut() },
                            enabled = !state.busy && state.iconBitmap != null
                        ) { Text("创建桌面快捷方式") }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("主题色", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "调色板选色、吸管从背景图取色，或直接填颜色代码（#RGB / #RRGGBB / #AARRGGBB）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        // 调色板：先给一组常用色，选完立即生效。
                        // 选中判据要带上"默认色"：themeColor == null 表示正在用默认的 B 站粉，
                        // 只比 themeColor 的话第一个色块永远不会打勾，用户看不出当前生效的是哪个。
                        val effectiveColor = state.themeColor ?: ThemePalette.DEFAULT
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemePalette.COLORS.forEach { argb ->
                                val selected = effectiveColor == argb
                                Box(
                                    // 48dp 触控区是 Material/无障碍的最小可点尺寸；色块本身仍画 36dp
                                    Modifier.size(48.dp)
                                        .selectable(
                                            selected = selected,
                                            role = Role.RadioButton,
                                            onClick = { viewModel.applyColor(argb) }
                                        )
                                        .semantics { contentDescription = "主题色 ${ColorHex.format(argb)}" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier.size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(argb)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        // 勾的颜色按色块亮度挑：写死白色时，浅色块上的勾几乎看不见
                                        if (selected) {
                                            Text(
                                                "✓",
                                                color = if (isLight(Color(argb))) Color.Black else Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.colorText,
                            onValueChange = { viewModel.onColorTextChanged(it) },
                            label = { Text("颜色代码") },
                            placeholder = { Text("#FB7299") },
                            singleLine = true,
                            isError = !com.example.bilimonitor.data.repository.ColorHex.isValid(state.colorText),
                            supportingText = {
                                Text(
                                    if (!com.example.bilimonitor.data.repository.ColorHex.isValid(state.colorText)) {
                                        "格式不对：支持 #RGB / #RRGGBB / #AARRGGBB"
                                    } else "留空并点应用 = 恢复默认主题色"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.applyColorText() }) { Text("应用") }
                            TextButton(
                                onClick = { viewModel.setEyedropper(!state.eyedropper) },
                                enabled = state.backgroundBitmap != null
                            ) { Text(if (state.eyedropper) "吸管：点击背景图" else "吸管取色") }
                        }
                        if (state.backgroundBitmap == null) {
                            Text(
                                "吸管需要先设置背景图片（真正的\"吸屏幕任意位置\"需要无障碍截屏或录屏授权，为取一个颜色弹录屏框不值得）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("背景图片", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "选一张图片作为页面背景，可调明暗与透明度。卡片本身保持不透明，避免影响文字阅读。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        val bg = state.backgroundBitmap
                        // 预览要画的是**框选出来的那块区域**（没框选过 = 整张图），而不是整张图：
                        // 否则用户框完回到这一页，看到的还是整张图，会以为框选没生效。
                        // 这块区域与渲染侧用的是同一个换算（BackgroundRegionMath.pixelRegion），
                        // 区别只是预览框的比例不等于屏幕比例，所以这里还会按 Crop 再居中裁一点。
                        val bgRegion = remember(bg, state.backgroundCrop) {
                            bg?.let {
                                BackgroundRegionMath.pixelRegion(
                                    it.width, it.height, state.backgroundCrop
                                )
                            }
                        }
                        if (bg != null && bgRegion != null) {
                            Box(
                                Modifier.fillMaxWidth().height(140.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            ) {
                                Image(
                                    // 只画区域内那一块：BitmapPainter 的 srcOffset/srcSize 不复制像素，
                                    // 也就不必为了预览再裁一份位图出来。
                                    painter = remember(bg, bgRegion) {
                                        BitmapPainter(
                                            image = bg.asImageBitmap(),
                                            srcOffset = IntOffset(bgRegion.left, bgRegion.top),
                                            srcSize = IntSize(bgRegion.width, bgRegion.height)
                                        )
                                    },
                                    contentDescription = "背景预览",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    alpha = state.backgroundAlpha
                                )
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(Color.Black.copy(alpha = state.backgroundDim))
                                )
                                if (state.eyedropper) {
                                    // ★ 吸管热区必须是预览图的**子节点**，而且排在图片与压暗层之后：
                                    //   放在兄弟位置会被排在预览图下面，手指点的地方与热区对不上
                                    //   （用户反馈：吸管点了没反应）。原先预览图自己那个
                                    //   clickable(eyedropper) 只是"取消吸管"，语义也是反的，已删除。
                                    Box(
                                        Modifier.fillMaxSize()
                                            .pointerInput(bg, bgRegion) {
                                                detectTapGestures { offset ->
                                                    // 预览画的是"框选区域按 Crop 铺满这个 140dp 的框"，
                                                    // 点击比例不能直接乘原图宽高（那等于假设预览画的是整图
                                                    // 且是 Fit）。这里按这块区域的 scale/origin 反算成
                                                    // 区域坐标，再加回区域在原图里的偏移 —— 与 Theme.kt 里
                                                    // BackgroundImageInfo 是同一套换算（都出自
                                                    // BackgroundRegionMath.placement），只是方向相反。
                                                    val placement = BackgroundRegionMath.placement(
                                                        bgRegion,
                                                        size.width.toFloat(),
                                                        size.height.toFloat()
                                                    )
                                                    val x = bgRegion.left + placement.regionX(offset.x)
                                                    val y = bgRegion.top + placement.regionY(offset.y)
                                                    viewModel.pickColorFromBackground(
                                                        (x / bg.width).coerceIn(0f, 1f),
                                                        (y / bg.height).coerceIn(0f, 1f)
                                                    )
                                                }
                                            }
                                            .background(Color.Black.copy(alpha = 0.25f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "点击这里取色",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                            if (state.backgroundCrop != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "预览显示的是你框选的那块区域；实际背景按屏幕比例铺满整屏。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // 与「应用图标」卡同一套口径：两个来源入口，但都汇进 viewModel.pickBackground
                        // 这一条下游路（解码、落盘、明暗/透明度都由同一处处理）。
                        // 这一行最多三个按钮（两个来源 + 移除），窄屏一定放不下，交给 FlowRow 折行：
                        // 最多把「移除」挤到下一行，三个入口一个都不会丢。
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    albumBackgroundPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !state.busy
                            ) { Text("从相册选择") }
                            Button(
                                onClick = { fileBackgroundPicker.launch(arrayOf("image/*")) },
                                enabled = !state.busy
                            ) { Text("从本地文件选择") }
                            if (bg != null) {
                                TextButton(onClick = { viewModel.removeBackground() }) { Text("移除") }
                            }
                        }
                        // 「选择显示区域」：只在已有背景图时可点 —— 没有图就没有可框的东西。
                        // 没图时**保留按钮并写明原因**（与"吸管取色"同一口径），而不是直接隐藏：
                        // 藏起来用户根本不知道有这个功能，只会以为背景图只能整张铺满。
                        // 已框选过也能再进来重新框（参数存在盘上，不需要重新选图）。
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { viewModel.startRegionEdit() },
                            enabled = !state.busy && bg != null
                        ) { Text("选择显示区域") }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                bg == null -> "先选一张背景图片，才能框选它的显示区域"
                                state.backgroundCrop == null ->
                                    "当前：整张图居中铺满（默认）。可框出任意一块作为背景，取景框比例与屏幕一致。"
                                else ->
                                    "当前：已框选一块显示区域（可再次调整，不需要重新选图；原图不会被改动）。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (bg != null) {
                            Spacer(Modifier.height(10.dp))
                            Text("明暗：${(state.backgroundDim * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = state.backgroundDim,
                                onValueChange = { viewModel.previewBackgroundAdjust(it, state.backgroundAlpha) },
                                onValueChangeFinished = { viewModel.commitBackgroundAdjust() },
                                valueRange = 0f..1f
                            )
                            Text("透明度：${(state.backgroundAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = state.backgroundAlpha,
                                onValueChange = { viewModel.previewBackgroundAdjust(state.backgroundDim, it) },
                                onValueChangeFinished = { viewModel.commitBackgroundAdjust() },
                                valueRange = 0.1f..1f
                            )
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("卡片外观", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        // 用户要求：卡片本身也能调透明度与明暗。
                        // 改的是主题里的 surface 系列，因此**全应用的卡片一起生效**；
                        // 透明度调低后背景图会透过卡片，配合"明暗"压暗/提亮可保持正文可读。
                        Text(
                            "调整所有卡片的透明度与明暗（背景图会透过半透明卡片显示）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        // 只有**卡片玻璃**开着时，卡片不透明度才由「玻璃浓度」换算（见 MainActivity 的
                        // cardAlpha = 1 - glassTint），这个滑块写进去的值会被覆盖 ——
                        // 与其让用户拖了没反应，不如直接禁用并说明原因。
                        // 判据里**不能**带上 glassDock：Dock 玻璃不改变卡片底色，
                        // 把它算进来只会让"只开 Dock"时这个滑块被无故禁用。
                        val glassActive = state.glassCards
                        Text("透明度：${(state.cardAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = state.cardAlpha,
                            onValueChange = { viewModel.previewCardAppearance(state.cardDim, it) },
                            onValueChangeFinished = { viewModel.commitCardAppearance() },
                            enabled = !glassActive,
                            valueRange = 0.3f..1f
                        )
                        if (glassActive) {
                            Text(
                                "已开启玻璃效果：卡片透明度由「玻璃浓度」决定" +
                                    "（当前 ${(state.glassTint * 100).toInt()}%），此滑块暂不生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("明暗（压暗）：${(state.cardDim * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = state.cardDim,
                            onValueChange = { viewModel.previewCardAppearance(it, state.cardAlpha) },
                            onValueChangeFinished = { viewModel.commitCardAppearance() },
                            valueRange = 0f..0.9f
                        )
                    }
                }

                // 「底部导航样式」紧跟「卡片外观」：两者都是"界面长什么样"的直接设置，
                // 而「夜间模式」是明暗主题、属于另一类，放中间会把这两张同类的卡片隔开。
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("底部导航样式", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Dock 栏是悬浮的圆角栏（默认），普通样式是贴底的整条导航栏。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        com.example.bilimonitor.data.repository.NavigationStyle.values().forEach { style ->
                            // 整行可点（理由同夜间模式的单选项）
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = state.navigationStyle == style,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.setNavigationStyle(style) }
                                    )
                            ) {
                                RadioButton(selected = state.navigationStyle == style, onClick = null)
                                Column {
                                    Text(style.label)
                                    Text(
                                        style.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // ★ 「液态玻璃」单独成卡，两个开关并列在这里（用户要求）。
                //   为什么要把它们从各自的卡片里搬出来：卡片玻璃原来挂在「背景图片」卡里
                //   （一张讲背景图的卡里塞"卡片玻璃"，用户找不到入口），Dock 玻璃则藏在
                //   「底部导航样式」卡里 —— 两个开关分处两张卡，用户既看不出它们的关系，
                //   也没法预期"只开其中一个"是什么效果。并列之后，玻璃相关的开关与参数
                //   集中在同一处，语义上也不再依附于导航样式。
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("液态玻璃", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        // 只如实写"参数怎么共用"这一件事，不在这里替两个开关的关系打包票：
                        // 开关之间的联动逻辑不在本页（见 AppearanceSettingsRepository.setGlassDock），
                        // 界面若写上"互不影响"，一旦联动还在就是又一句过度承诺。
                        Text(
                            "这 5 项参数由 Dock 与卡片共用：模糊半径与玻璃浓度同时作用于两者，" +
                                "高光与亮边只画在 Dock 上（卡片没有自己的高光与亮边）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        // Dock 玻璃（原先在「底部导航样式」卡里）。
                        // 只在选了 Dock 时可用 —— 普通样式是贴底的整条栏，没有"悬浮玻璃"可谈。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("液态玻璃效果")
                                Text(
                                    "Dock 栏做成毛玻璃：按栏体位置把背景图对应区域模糊后透出，带高光与亮边；" +
                                        "未设置背景图时只有半透明质感、没有可折射的内容。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.glassDock,
                                enabled = state.navigationStyle ==
                                    com.example.bilimonitor.data.repository.NavigationStyle.DOCK,
                                onCheckedChange = { viewModel.setGlassDock(it) }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // 卡片玻璃（原先在「背景图片」卡里）。
                        // 说明文字改过一次：原句承诺"把背景壁纸整体磨砂"，而「模糊半径」为 0 时
                        // 壁纸根本不磨砂 —— 那是过度承诺。用户决定**保留行为、改文案**：
                        // 现在这行如实写清"半透明由开关决定、磨砂与否由模糊半径决定"，
                        // 不再承诺"整体磨砂"，也不再提"Dock 玻璃关掉会一并关闭"（该联动正在被移除）。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("同时应用到所有卡片")
                            }
                            Switch(
                                checked = state.glassCards,
                                onCheckedChange = { viewModel.setGlassCards(it) }
                            )
                        }
                        // 与同卡片既有说明同款：labelSmall + onSurfaceVariant（小字，不是标题样式）。
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "卡片会改为半透明（背景壁纸透过卡片显示）；" +
                                "壁纸是否磨砂由「模糊半径」决定，设为 0 即不磨砂。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // ★ 两个开关**任一**开着就显示参数（这里原来是 if (state.glassDock)）：
                        //   参数是两者共用的，只开卡片玻璃时同样得能调；否则用户打开卡片玻璃后
                        //   找不到任何参数入口，只能一直用默认值（用户反馈：单独开一个时参数调不了）。
                        if (state.glassDock || state.glassCards) {
                            Spacer(Modifier.height(4.dp))
                            // 液态玻璃参数（用户要求：参数可自定义）。
                            // 抽成同一组参数给 Dock 与卡片共用，改一处两边一起变 ——
                            // 否则"同步应用"迟早会变成两套数值对不上。
                            GlassParamSlider(
                                label = "模糊半径",
                                value = state.glassBlur, range = 0f..60f, suffix = "dp",
                                onChange = { viewModel.previewGlassParams(blur = it) },
                                onCommit = { viewModel.commitGlassParams() }
                            )
                            GlassParamSlider(
                                label = "玻璃浓度",
                                value = state.glassTint, range = 0f..0.8f, percent = true,
                                onChange = { viewModel.previewGlassParams(tint = it) },
                                onCommit = { viewModel.commitGlassParams() }
                            )
                            GlassParamSlider(
                                label = "高光强度",
                                value = state.glassHighlight, range = 0f..1f, percent = true,
                                onChange = { viewModel.previewGlassParams(highlight = it) },
                                onCommit = { viewModel.commitGlassParams() }
                            )
                            GlassParamSlider(
                                label = "亮边透明度",
                                value = state.glassRimAlpha, range = 0f..1f, percent = true,
                                onChange = { viewModel.previewGlassParams(rimAlpha = it) },
                                onCommit = { viewModel.commitGlassParams() }
                            )
                            GlassParamSlider(
                                label = "亮边宽度",
                                value = state.glassRimWidth, range = 0f..3f, suffix = "dp",
                                onChange = { viewModel.previewGlassParams(rimWidth = it) },
                                onCommit = { viewModel.commitGlassParams() }
                            )
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("夜间模式", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        // 用户要求："是否跟随系统"的开关，默认跟随。
                        // 关掉跟随后必须还能选浅色/深色，否则"夜间模式"就只剩强制浅色一种结果。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("跟随系统")
                                Text(
                                    "开启时随系统的深色开关切换；关闭后可固定为浅色或深色。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.themeMode ==
                                    com.example.bilimonitor.data.repository.ThemeMode.SYSTEM,
                                onCheckedChange = { follow ->
                                    viewModel.setThemeMode(
                                        if (follow) com.example.bilimonitor.data.repository.ThemeMode.SYSTEM
                                        else com.example.bilimonitor.data.repository.ThemeMode.LIGHT
                                    )
                                }
                            )
                        }
                        if (state.themeMode != com.example.bilimonitor.data.repository.ThemeMode.SYSTEM) {
                            listOf(
                                com.example.bilimonitor.data.repository.ThemeMode.LIGHT,
                                com.example.bilimonitor.data.repository.ThemeMode.DARK
                            ).forEach { mode ->
                                // 整行可点：RadioButton 本体只有 ~20dp，只让它自己响应点击
                                // 会让"点文字没反应"（无障碍下也是整行才够 48dp 触控区）
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = state.themeMode == mode,
                                            role = Role.RadioButton,
                                            onClick = { viewModel.setThemeMode(mode) }
                                        )
                                ) {
                                    // onClick = null：点击交给整行处理，避免出现两个可点区域
                                    RadioButton(selected = state.themeMode == mode, onClick = null)
                                    Text(mode.label)
                                }
                            }
                        }
                    }
                }

                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text("恢复默认外观", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        // 如实列出这个按钮到底会重置什么：它原本放在「应用图标」卡里、
                        // 提示也只说名称与图标，实际上还会清掉主题色与背景图（含明暗/透明度）。
                        Text(
                            "会重置：应用名称、自定义图标、主题色、背景图（含背景的明暗、透明度与显示区域）。\n" +
                                "不会动：底部导航样式、夜间模式、卡片外观与玻璃参数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.reset() }, enabled = !state.busy) {
                            Text("恢复默认")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 裁剪取景框：正方形、可双指缩放 + 拖动。
 *
 * 手势状态只有"缩放倍数 + 位移"两个量，换算成原图裁剪矩形的工作全部交给
 * [CropMath]（纯函数、有单测）—— 这样"裁到哪里"这件事可以被测试，
 * 而不是只能靠肉眼在模拟器上试。
 *
 * ★ 取景框不再写死 `fillMaxWidth().aspectRatio(1f)`：这个 Column 不能滚动，正方形是
 *   "宽度决定高度"的 —— 横屏或大字体下正方形会高过可用空间，把下面的
 *   「使用这个区域 / 取消」顶出屏幕（用户反馈：点不到按钮）。
 *   改成外层 Box 拿 `weight(1f, fill = false)` 去适配剩余高度、内层再收成正方形，
 *   按钮区就始终留在屏内。
 */
@Composable
private fun CropPane(source: Bitmap, onCancel: () -> Unit, onConfirm: (Bitmap) -> Unit) {
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(0f to 0f) }
    var boxSize by remember { mutableStateOf(1f) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("拖动调整位置、双指缩放，取景框内的方形区域将成为图标", style = MaterialTheme.typography.bodySmall)
        // 外层 Box 只负责"占住剩余高度 + 居中"：权重让它先去适配剩余高度（fill = false =
        // 按内容取实际高度，不强行占满分到的份额），内层再画成正方形
        // （边长 = min(可用宽, 剩余高)）。直接给方形取景框加权重是不行的 ——
        // 一旦宽度被 fillMaxWidth 定死，aspectRatio 就无法为了高度再收窄自己，
        // 会退化成"宽 × 剩余高"的长方形，画面与手势区都会歪。
        Box(
            Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(source) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                            val rawX = offset.first + pan.x
                            val rawY = offset.second + pan.y
                            // 位移必须夹住：拖动不能让取景框露出图片以外的空白
                            val clamped = CropMath.clampOffset(
                                source.width, source.height, boxSize, newZoom, rawX, rawY
                            )
                            zoom = newZoom
                            offset = clamped
                        }
                    }
            ) {
                // 用 Canvas 绘制"显示坐标系"里的图片：位移与缩放直接体现在绘制参数上，
                // 与 CropMath 的换算保持同一套定义（显示尺寸 = 原图 × 基准缩放 × 用户缩放）
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    boxSize = size.minDimension
                    val base = CropMath.baseScale(source.width, source.height, boxSize)
                    val scale = base * zoom
                    val drawW = source.width * scale
                    val drawH = source.height * scale
                    val left = (boxSize - drawW) / 2f + offset.first
                    val top = (boxSize - drawH) / 2f + offset.second
                    drawImage(
                        image = source.asImageBitmap(),
                        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        srcSize = androidx.compose.ui.unit.IntSize(source.width, source.height),
                        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val rect = CropMath.sourceRect(
                    source.width, source.height, boxSize, zoom, offset.first, offset.second
                )
                val cropped = runCatching {
                    val raw = Bitmap.createBitmap(source, rect.left, rect.top, rect.size, rect.size)
                    // 统一输出 512×512：桌面图标不需要更大，也避免把大图存进私有目录
                    Bitmap.createScaledBitmap(raw, ICON_OUTPUT_PX, ICON_OUTPUT_PX, true)
                }.getOrNull()
                if (cropped != null) onConfirm(cropped)
            }) { Text("使用这个区域") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

private const val ICON_OUTPUT_PX = 512

/** 液态玻璃参数滑块（标签 + 数值 + Slider，松手才落库）。 */
@Composable
private fun GlassParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    percent: Boolean = false,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    val shown = if (percent) "${(value * 100).toInt()}%" else "${value.toInt()}$suffix"
    Text("$label：$shown", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onCommit,
        valueRange = range
    )
}

/** 调色板（用户要求：可在调色板上选色）。前两个是应用默认色与常用色，其余是通用色板。 */
object ThemePalette {
    /**
     * 默认主题色（未设置 themeColor 时生效的那个）。
     *
     * 必须与 `Theme.kt` 里的 `BiliPink` 保持同一个值：调色板要靠它判断"没选过色时哪个块是当前色"。
     */
    val DEFAULT = 0xFFFB7299.toInt()

    val COLORS = listOf(
        DEFAULT, // B 站粉（默认）
        0xFF6EA6FF.toInt(), // 蓝
        0xFF2E9E5B.toInt(), // 绿
        0xFF8E6EF2.toInt(), // 紫
        0xFFE8A03C.toInt(), // 橙
        0xFFE5484D.toInt(), // 红
        0xFF12A5A5.toInt(), // 青
        0xFF3B5BDB.toInt(), // 靛
        0xFFB5179E.toInt(), // 品红
        0xFF5C6B7A.toInt()  // 灰蓝
    )
}