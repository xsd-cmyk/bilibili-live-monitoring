package com.example.bilimonitor.ui.settings

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.common.FloatingTopBarTitle
import com.example.bilimonitor.ui.common.MessageBanner

/**
 * 「更换应用图标」页：**两个独立的轴**（图标 / 名字）各自一区，点哪一轴就换哪一轴。
 *
 * 逻辑全在 [IconPresetSwitcher] / [IconPresetViewModel] 里（解析三个数组、读真实状态、切换），
 * 本文件只管画。这里只解释界面上的几个决定。
 *
 * ## 为什么是两个分区，而底层却是一张"组合表"
 * 平台把 `android:icon` 与 `android:label` 绑在同一个 `<activity-alias>` 上，所以"只换图标"
 * 或"只换名字"在运行期都不存在：安装包里为「图标 i × 名字 j」的每个组合各有一个别名
 * （见 [IconPresetSwitcher] 文件头）。界面把这件事**如实拆成两个轴**呈现：
 *  · 分区「应用图标」：网格，点第 i 格 = 图标换成 i，**名字保持当前值**；
 *  · 分区「应用名称」：列表，点第 j 行 = 名字换成 j，**图标保持当前值**；
 * 两个值合起来就是一个格子 (i,j)，由 ViewModel 换算成要启用的那个别名。
 * 用户看到的"两个独立开关"，落到系统里永远是"启用另一个别名"。
 *
 * ## 「使用中」从哪来
 * 只从 `PackageManager` 读（见 [IconPresetViewModel.activation]），页面**不显示**
 * 本应用自己记的"上次选了谁"。用户可能在系统设置里改过、上一次切换也可能被系统中途结束，
 * 自己记一份迟早会显示成假的。清单里的过期状态同理：切换后立刻重读，而不是本地改个标志位。
 * 反查不到当前格子时（既不是主入口、也不在组合表里）**两个分区都不标「使用中」**，
 * 但顶部有一条常驻横幅说明原因 —— "不知道"要讲出来，而不是两边都空着让人猜。
 *
 * ## 为什么点一下要先弹确认框
 * 把图标切成预设时必须禁用 `MainActivity`，而**禁用正在使用的组件会让系统结束本应用** ——
 * 用户会觉得"应用莫名其妙自己退了"。既然这是必然发生的副作用，就必须在发生**之前**讲清楚。
 * 无提示地把应用弄退出，比不做这个功能更糟。
 *
 * ## 为什么整页只有一个可滚动容器（`LazyVerticalGrid`）
 * 顶部版权卡、各种横幅、分区说明、空态、底部说明都是这个网格里 `span = maxLineSpan` 的整行条目；
 * 名称轴那一列名字同理，一行一条（`span = maxLineSpan`），所以两个分区能共用同一个滚动容器。
 * 网格套在 `Column + verticalScroll` 里会因为收到无限高约束直接抛异常
 * （"Vertically scrollable component was measured with an infinity maximum height"），
 * 换成"给网格一个 weight 高度"又要手工协调两层滚动，都不如让网格自己当滚动容器干净。
 * 例外是**必须一直看得见**的东西：版权卡、清单缺失横幅、状态异常横幅、操作结果 —— 它们放在网格外，
 * 随着列表滚上去就等于没显示。
 */
@Composable
fun IconPresetScreen(
    onBack: () -> Unit,
    // 默认参数由 Hilt 提供：调用方（AppNavHost）不需要知道本页有 ViewModel，
    // 与 SettingsScreen 的写法一致。
    viewModel: IconPresetViewModel = hiltViewModel()
) {
    val manifest = viewModel.manifest
    val activation by viewModel.activation.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val appIconPainter = rememberAppIconPainter()

    // 等待用户确认的目标**格子**；非 null 时显示确认框。用 remember 而不是 rememberSaveable：
    // IconSwitchTarget 不是 Parcelable，而转屏后重新弹一次框（或干脆不弹）都不会造成错误状态。
    var pendingSwitch by remember { mutableStateOf<IconSwitchTarget?>(null) }

    // 当前生效的格子 (i,j)：两个分区的「使用中」都只由它来。
    // 为 null 而 currentClassName 不为 null ⇒ 反查不到（见下面那条常驻横幅）。
    val currentCell = activation.currentCell

    Scaffold(
        topBar = {
            FloatingTopBar(
                title = { FloatingTopBarTitle("更换应用图标") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ★ 版权提示放在网格**外面**（列表上方、且不随列表滚走）：
            //   这是用户明确要求必须显示的一句话，随着列表滚上去就等于没显示。
            CopyrightNoticeCard()

            // 清单里有解析不了的条目时常驻（autoDismissMillis = null）：它是安装包/生成脚本的问题，
            // 不是一次操作的结果，没有"过两秒就没人需要看"的说法。
            if (manifest.missing.isNotEmpty()) {
                MessageBanner(
                    message = "预设清单里有 ${manifest.missing.size} 处数据有问题" +
                        "（对应的条目已跳过、或按「默认」处理）：" +
                        manifest.missing.joinToString("、"),
                    isError = true,
                    autoDismissMillis = null
                )
            }

            // "系统说本包还有哪些入口活着"这次没问出来：那么下面"一个都没有 / 反查不到"这类结论
            // 都不牢靠，必须先说清楚（不静默，也不假装确定）。
            if (activation.launcherQueryFailed) {
                MessageBanner(
                    message = "这次没能向系统确认当前有哪些桌面入口处于启用状态，" +
                        "下面的「使用中」可能不完整（重启应用或重进本页可以再试一次）。",
                    isError = true,
                    autoDismissMillis = null
                )
            }

            // 状态异常也必须一直看得见 —— 这两种都不是"操作结果"，而是当前这台机器上的事实。
            if (activation.currentClassName == null) {
                // 一个都没启用：应用此刻**无法从桌面启动**。必须让用户看懂并给出恢复路径，
                // 所以下面每一格（两个分区的「默认」项都算）在此时都是可点的。
                MessageBanner(
                    message = "当前没有任何已启用的桌面入口，应用无法从桌面启动。" +
                        "请点下面任意一格恢复（两个分区里的「默认」项始终可用）。",
                    isError = true,
                    autoDismissMillis = null
                )
            } else if (activation.enabledClassNames.size > 1) {
                MessageBanner(
                    message = "有 ${activation.enabledClassNames.size} 个桌面入口同时启用，" +
                        "桌面上会出现多个图标。重新点选任意一格即可修复。",
                    isError = true,
                    autoDismissMillis = null
                )
            }

            // ★ 反查不到当前格子：**如实说明，并且两个分区都不标「使用中」**。
            //   不知道就是不知道 —— 硬给某一格打勾、或者两边都空着不解释，都会让用户以为功能坏了。
            //   同时提前说清"另一个轴会被设成默认"，否则用户会以为另一个轴保持不变。
            if (activation.currentClassName != null && currentCell == null) {
                MessageBanner(
                    message = "当前生效的桌面入口是 ${activation.currentClassName}，" +
                        "它既不是主入口、也不在预设组合表里（可能被手动改过，或清单与资源不同步）。" +
                        "因此下面两个分区都无法标出「使用中」—— 连「现在另一个轴是什么」都不知道。" +
                        "点任意一格时，另一个轴会按「默认」处理，确认框里会写明这一点。",
                    isError = true,
                    autoDismissMillis = null
                )
            }

            // 操作结果：成功类自动消失（它只回答"刚才那一下成没成"），
            // 失败类**不自动消失**（本项目原则：不允许静默失败）。
            MessageBanner(
                message = message?.text,
                isError = message?.isError == true,
                autoDismissMillis = if (message?.isError == true) null else 3000L,
                onDismiss = viewModel::dismissMessage
            )

            LazyVerticalGrid(
                // Adaptive 而不是 Fixed：固定列数在大屏/横屏上会把格子拉得极宽、
                // 小屏上又会挤成很窄一条；76dp 的下限让常见 360dp 窄屏排 3 列、
                // 411dp 排 4 列，正好落在"3 或 4 列、按屏宽自适应"这个要求上。
                // 名称轴的每一行都是 `maxLineSpan` 的整行条目，所以它不受列数影响。
                columns = GridCells.Adaptive(minSize = 76.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    // 高度由 Column 的剩余空间决定（网格自己不滚动外层、外层也不滚动网格）
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---------------- 分区一：应用图标 ----------------
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AxisSectionCard(
                        title = "应用图标",
                        currentText = when {
                            currentCell != null -> "当前使用：${manifest.iconLabelOf(currentCell.first)}"
                            activation.currentClassName == null -> "当前使用：没有识别到已启用的桌面入口"
                            else -> "当前使用：无法判定（当前入口不在组合表里，见上方提示）"
                        },
                        description = if (manifest.hasPresetIcons) {
                            "共 ${manifest.icons.size - 1} 个可选图标（另有「${IconPresetSwitcher.DEFAULT_ICON_LABEL}」" +
                                "＝应用自己的图标）。点任意一格即可换图标，名字保持不变；切换前会先弹确认框。"
                        } else {
                            "目前只有「${IconPresetSwitcher.DEFAULT_ICON_LABEL}」这一项可选，没有别的预设图标。" +
                                "点它可以回到应用自己的图标 —— 这一项始终可用。"
                        }
                    )
                }

                // 刻意**不传 key**：清单是脚本生成的字符串数组，一旦出现重名条目，
                // 用组件名/显示名当 key 会让 Compose 直接抛"同一个 key 被用了两次"而整页崩溃；
                // 位置 key 则只会多画一个格子 —— 一个能看懂的重复，好过一个崩溃。
                items(manifest.icons) { option ->
                    IconAxisCell(
                        option = option,
                        isCurrent = currentCell?.first == option.index,
                        appIconPainter = appIconPainter,
                        onClick = { pendingSwitch = viewModel.switchTargetForIcon(option.index) }
                    )
                }

                if (!manifest.hasPresetIcons) {
                    // 「默认」那一格**照样画**（它很可能就是当前生效项），空态卡只解释"为什么只有这一格"。
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyIconAxisCard() }
                }

                // ---------------- 分区二：应用名称 ----------------
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AxisSectionCard(
                        title = "应用名称",
                        currentText = when {
                            currentCell != null -> "当前使用：${manifest.nameLabelOf(currentCell.second)}"
                            activation.currentClassName == null -> "当前使用：没有识别到已启用的桌面入口"
                            else -> "当前使用：无法判定（当前入口不在组合表里，见上方提示）"
                        },
                        description = if (manifest.hasPresetNames) {
                            "共 ${manifest.names.size - 1} 个可选名字（另有「${IconPresetSwitcher.DEFAULT_NAME_LABEL}」" +
                                "＝应用自己的名字）。点任意一行即可换名字，图标保持不变；切换前会先弹确认框。"
                        } else {
                            "目前只有「${IconPresetSwitcher.DEFAULT_NAME_LABEL}」这一项可选，没有别的预设名字。" +
                                "点它可以回到应用自己的名字 —— 这一项始终可用。"
                        },
                        // 应用里**已经**有一个"自定义应用名称"功能（走桌面快捷方式），与本页做的事
                        // 完全不同；不解释清楚，用户会以为两个功能重复、或以为改的是同一处。
                        extraNote = "这里的名字会改应用在桌面上的显示名（做法是切换桌面入口）；" +
                            "外观设置里的「自定义应用名称」是另建一个桌面快捷方式入口，不改应用本身的名字。" +
                            "系统「应用信息」页里的名字由安装包决定，本页改不了。"
                    )
                }

                // 一行一个名字：整行 span，视觉与设置页的列表项一致。
                items(manifest.names, span = { GridItemSpan(maxLineSpan) }) { option ->
                    NameAxisRow(
                        option = option,
                        isCurrent = currentCell?.second == option.index,
                        onClick = { pendingSwitch = viewModel.switchTargetForName(option.index) }
                    )
                }

                if (!manifest.hasPresetNames) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyNameAxisCard() }
                }

                item(span = { GridItemSpan(maxLineSpan) }) { SwitchNoteCard() }
            }
        }
    }

    // 确认框：只在真正要切走的时候弹（点到当前那一格时 ViewModel 返回 null，连框都不会出现）。
    pendingSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("切换到「${target.label}」？") },
            text = {
                Column {
                    // 第一句是必须让用户看到的代价（切换必然禁用正在使用的那个入口）。
                    Text("切换后应用会退出，请从桌面重新打开。部分启动器需要几秒钟刷新图标。")
                    // 当前格子反查不到时，另一个轴**不是**"保持不变"，而是被设成默认项 —— 必须明说。
                    if (target.assumedOtherAxis) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "注意：当前生效的入口不在组合表里，无法确定另一个轴现在是什么，" +
                                "所以这次会把另一个轴设为「默认」项。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 第二段如实说明"切换失败会怎样"和"没退出怎么办"：
                    // 不承诺做不到的事，也不隐瞒可能发生的事。
                    // ★ "不会出现一个图标都不剩"这句现在有两层保障（顺序：先启用新的入口再关旧的；
                    //   收尾复核若发现新入口没保住启用，就把「默认」入口恢复回来，见
                    //   IconPresetSwitcher.applySwitch 的「零入口兜底」），所以可以照实说；
                    //   万一连恢复都失败，操作结果那条横幅会明说"桌面可能没有图标"。
                    Text(
                        "切换会先启用新入口、再关闭旧入口；如果系统拒绝启用新入口，" +
                            "本次切换会被放弃，当前入口保持不变（不会出现一个图标都不剩的情况）。" +
                            "若应用没有自动退出，也请回到桌面确认新图标与新名字是否已生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.switchTo(target)
                        // 立即关框：切换结果由页面顶部的提示条回答。
                        // （进程若被系统结束，这个框和提示条会一起消失 —— 那一次的结果
                        //   由 ViewModel 里的"进行中标记"在下次打开时补上说明。）
                        pendingSwitch = null
                    }
                ) { Text("确认切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 应用自身的 launcher 图标（「默认」那一格用的图）。
 *
 * 它不是 `res/drawable` 下的资源，只能通过 `PackageManager` 取，所以拿不到时返回 null
 * （格子退化成"有名字、没图"，而不是崩掉）。整个取值过程包 `runCatching`：
 * `getApplicationIcon` 在极端情况下会抛 `NameNotFoundException`。
 */
@Composable
private fun rememberAppIconPainter(): Painter? {
    val context = LocalContext.current
    val drawable: Drawable? = remember {
        runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()
            ?: runCatching {
                context.applicationInfo.loadIcon(context.packageManager)
            }.getOrNull()
    }
    return remember(drawable) {
        // 必须给**显式**尺寸：图标若是 ColorDrawable 之类没有固有尺寸的 Drawable，
        // 用默认的 intrinsicWidth 会抛 "width and height must be > 0"。
        val bitmap = runCatching {
            // 位置参数而不是具名参数：具名要依赖扩展函数形参名，没必要为此冒风险。
            drawable?.toBitmap(144, 144, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) null else BitmapPainter(bitmap.asImageBitmap())
    }
}

/**
 * 版权提示卡。文案由用户逐字指定（含末尾句号），**不要改标点**。
 *
 * 用 `surfaceContainer`（与浮动标题栏同色）而不是 error/warning 配色：这句话是版权告知，
 * 不是错误告警；把它画成红色会让用户以为应用出了故障。
 */
@Composable
private fun CopyrightNoticeCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                "图片版权提示",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text("若图片侵权，请联系软件制作人删除。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 分区说明卡（图标轴与名称轴共用同一套视觉）。视觉与设置页的 `SettingsScreen.SettingSectionCard`
 * 一致（`Card` + 14dp 内边距 + titleMedium 半粗标题），这样本页看起来是设置页的自然延伸，
 * 而不是另一个应用。
 *
 * 第二行**用文字重复一遍"当前使用哪个"**：格子/行上的选中标记（描边 + 对勾）是给"扫一眼"用的，
 * 但这句文字才是明确的结论 —— 也让使用大字体/读屏的用户不必去猜徽标。
 * 反查不到当前格子时这句会写成"无法判定"，与顶部那条常驻横幅互相印证（不一致的界面比不显示更糟）。
 *
 * [extraNote] 只给名称轴用：用来区分"本页改的是桌面显示名"与"外观设置里的自定义应用名称"。
 */
@Composable
private fun AxisSectionCard(
    title: String,
    currentText: String,
    description: String,
    extraNote: String? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                currentText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (extraNote != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    extraNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 图标轴的空态卡（只在"除「默认」外没有别的图标"时出现）。
 *
 * 必须说清"空"的成因：空列表至少有两种完全不同的成因 ——"确实没有"和"资源没打进包/读取失败了"，
 * 用户没法从界面上区分。两种都在这段文案里点明，并说明**「默认」这一项仍然可用** ——
 * 否则用户会以为"没有预设 = 这个功能坏了，也回不到默认图标了"。
 */
@Composable
private fun EmptyIconAxisCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "还没有可选的预设图标",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "安装包里目前只有「${IconPresetSwitcher.DEFAULT_ICON_LABEL}」这一项可选 —— " +
                    "它始终可用，点它可以回到应用自己的图标。这不是加载失败、也不是还没加载完：" +
                    "本页数据在打开时就已经同步读完了；若上方提示里报出了图标资源缺失，" +
                    "那是清单写了、图却没打进安装包（构建问题）。" +
                    "把图片放进项目并重新构建后，预设图标会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 名称轴的空态卡。与图标轴同样的要求：说清成因，并明说**「默认」这一项仍然可用**。
 */
@Composable
private fun EmptyNameAxisCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "还没有可选的预设名字",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "安装包里目前只有「${IconPresetSwitcher.DEFAULT_NAME_LABEL}」这一项可选 —— " +
                    "它始终可用，点它可以回到应用自己的名字（也就是安装包里写的那个名字）。" +
                    "这不是加载失败：本页数据在打开时就已经同步读完了。" +
                    "预设名字由生成脚本写进 R.array.name_axis，加好名字并重新构建后会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 一个图标格子：图标 + 名称（+ 当前项的选中标记）。
 *
 * ## 选中标记用「描边 + 右上角对勾」
 *  - 只靠描边（颜色）不行：色觉障碍用户看不出差别，所以再给一个**形状**信号（对勾）；
 *  - 不用"使用中"这行文字：它会给选中格多占一行，同一行里的格子高度不齐，网格看起来是歪的；
 *    文字版结论放在分区卡的「当前使用：…」里（见 [AxisSectionCard]），那里读起来更清楚。
 *
 * ## 已经是当前项的格子不可点
 * 切换到自己没有任何意义（而且会白跑一次"禁用其它"）。所以当前项画成**不可点**的样子：
 * 不挂 clickable、不响应点击，读屏读到的也是"当前使用中"而不是"可切换"。
 * 注意这个规则**只在"确实认出了当前格子"时成立** —— 一个都没启用、或当前入口反查不到时
 * 每一格都必须可点，否则用户没有恢复路径。
 *
 * ## 无障碍
 * 整格对外只暴露**一个**语义节点、一句话：`mergeDescendants` 把图与名字合成一个节点，
 * `contentDescription` 直接给出结论（名字 + 是不是当前项），`selected` 同时把选中态
 * 作为状态暴露出去（读屏会念"已选中"）。图本身再给描述只会让读屏把同一个名字念两遍，
 * 所以 [Image] 的 contentDescription 恒为 null。
 *
 * ★ 点这一格**只改图标**：名字轴由 ViewModel 保持当前值（见
 * [IconPresetViewModel.switchTargetForIcon]），所以这里传的只是"图标轴的第几项"。
 */
@Composable
private fun IconAxisCell(
    option: IconAxisOption,
    isCurrent: Boolean,
    appIconPainter: Painter?,
    onClick: () -> Unit
) {
    // resId = 0 只可能是「默认」项：它的图标来自 PackageManager 而不是 drawable 资源，
    // 所以不能对 0 调 painterResource（那会抛 IllegalArgumentException 把整页弄崩）。
    val painter: Painter? = if (option.isDefault) appIconPainter else painterResource(option.resId)

    val shape = RoundedCornerShape(14.dp)
    val border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    val color = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val base = Modifier
        .fillMaxWidth()
        .semantics(mergeDescendants = true) {
            contentDescription = if (isCurrent) {
                "${option.label}，当前使用中"
            } else {
                "${option.label}，应用图标"
            }
            selected = isCurrent
        }

    if (isCurrent) {
        // 不带 onClick 的 Surface 重载 = 真正的不可点（不是"点了没反应"的假禁用）。
        Surface(modifier = base, shape = shape, color = color, border = border) {
            IconAxisCellContent(option, painter, isCurrent)
        }
    } else {
        Surface(
            modifier = base.clickable(
                onClickLabel = "切换到这个图标",
                role = Role.Button,
                onClick = onClick
            ),
            shape = shape,
            color = color,
            border = border
        ) {
            IconAxisCellContent(option, painter, isCurrent)
        }
    }
}

/** 格子内容。抽出来是为了让"可点/不可点"两个分支画得**完全一样**，只有交互属性不同。 */
@Composable
private fun IconAxisCellContent(option: IconAxisOption, painter: Painter?, isCurrent: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(56.dp)) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            if (isCurrent) {
                Surface(
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopEnd),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            option.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            // 单行 + 省略：长名称会把格子撑高、把网格排得参差不齐
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 名称轴的一行（整行可点，视觉与设置页的列表项一致）。
 *
 * 标记方式与图标格子**刻意保持一致**：主色描边 + 右侧对勾徽标 + 主色加粗文字 + `selected` 语义。
 * 两个分区的"使用中"必须一眼看出是同一种东西，否则用户会以为其中一个不是"当前项"。
 * 文字不省略（名字通常比图标名长），但限制两行以内，避免超长名字把列表撑散。
 *
 * ★ 点这一行**只改名字**：图标轴由 ViewModel 保持当前值（见
 * [IconPresetViewModel.switchTargetForName]）。
 */
@Composable
private fun NameAxisRow(option: NameAxisOption, isCurrent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val border = if (isCurrent) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    val color = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val base = Modifier
        .fillMaxWidth()
        .semantics(mergeDescendants = true) {
            contentDescription = if (isCurrent) {
                "${option.label}，当前使用中"
            } else {
                "${option.label}，应用名称"
            }
            selected = isCurrent
        }

    if (isCurrent) {
        Surface(modifier = base, shape = shape, color = color, border = border) {
            NameAxisRowContent(option, isCurrent)
        }
    } else {
        Surface(
            modifier = base.clickable(
                onClickLabel = "切换到这个名字",
                role = Role.Button,
                onClick = onClick
            ),
            shape = shape,
            color = color,
            border = border
        ) {
            NameAxisRowContent(option, isCurrent)
        }
    }
}

/** 名称行的内容（与"可点/不可点"无关，两个分支共用，保证画得完全一样）。 */
@Composable
private fun NameAxisRowContent(option: NameAxisOption, isCurrent: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            option.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (isCurrent) {
            Spacer(Modifier.width(10.dp))
            Surface(
                modifier = Modifier.size(18.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(2.dp)
                )
            }
        }
    }
}

/**
 * 底部如实说明卡 —— 本页最重要的一张：**如实比假装能用更重要**。
 *
 * 五件事写清楚：两个轴可以独立组合、切换后为什么要重开、桌面刷新为什么可能慢、
 * 失败时不会丢图标、以及"使用中"这个结论是从哪来的。用户看到"切换后应用自己退了"时最容易
 * 理解为"应用崩了/被杀了"，所以必须由页面自己先把原因讲出来。
 *
 * "两个轴独立组合"这条要放在最前面：用户提的原始需求就是"把图标和名字两个切换分开"，
 * 而实现上每个组合都是一个独立入口 —— 不解释，用户会以为是"两个开关各自记在本地"。
 */
@Composable
private fun SwitchNoteCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "图标与名字是两个独立的轴",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "「应用图标」与「应用名称」各自一区，可以自由组合：换图标不会改名字，换名字也不会改图标。" +
                    "安装包里为每个组合准备了一个独立的桌面入口，切换就是启用其中一个、关掉其余的。\n\n" +
                    "换入口时会关掉旧的，而关掉正在使用的入口会让系统结束本应用，" +
                    "所以切换完成后请从桌面重新打开。部分启动器刷新图标与名字有延迟，" +
                    "等几秒或重启桌面就能看到。\n\n" +
                    "顺序上永远是「先启用新的、再关掉旧的」，并且启用后还要读回校验：" +
                    "万一新入口没启用成功，这次切换会被放弃；收尾时若发现新入口没保住，还会把「默认」入口" +
                    "恢复回来 —— 所以切换失败也不会出现「一个图标都不剩」。如果连恢复都失败，" +
                    "页面会直接说明「桌面可能没有图标」并给出恢复方法，不会假装成功。\n\n" +
                    "本页显示的「使用中」直接读系统记录（PackageManager），不是本应用自己记的偏好：" +
                    "哪怕你在系统设置里手动改过、或上一次切换中途被系统结束，这里显示的也是真实生效的那一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
