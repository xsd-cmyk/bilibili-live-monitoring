package com.example.bilimonitor.ui.nav

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bilimonitor.ui.common.floatingCardShadowElevation
import com.example.bilimonitor.ui.data.DataScreen
import com.example.bilimonitor.ui.diagnostics.DiagnosticsScreen
import com.example.bilimonitor.ui.detail.StreamerDetailScreen
import com.example.bilimonitor.ui.history.HistoryListScreen
import com.example.bilimonitor.ui.history.StreamerHistoryScreen
import com.example.bilimonitor.ui.home.HomeScreen
import com.example.bilimonitor.ui.login.LoginScreen
import com.example.bilimonitor.ui.notifications.NotificationHistoryScreen
import com.example.bilimonitor.ui.onboarding.OnboardingScreen
import com.example.bilimonitor.ui.settings.IconPresetScreen
import com.example.bilimonitor.ui.settings.SettingsScreen
import com.example.bilimonitor.ui.settings.TaxonomyManageScreen
import com.example.bilimonitor.ui.stats.StatsListScreen
import com.example.bilimonitor.ui.stats.StreamerStatsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val HISTORY = "history"
    const val HISTORY_STREAMER = "history/streamer/{streamerId}"
    const val STATS = "stats"
    const val STATS_STREAMER = "stats/streamer/{streamerId}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val DATA = "data"
    const val NOTIFICATIONS = "notifications"
    const val DIAGNOSTICS = "diagnostics"

    /** 标签 / 分组管理（用户要求：可删除、可排序）。 */
    const val TAXONOMY = "taxonomy"

    /** 自定义应用名称与图标（用户要求）。 */
    const val APPEARANCE = "appearance"

    /** 更换应用图标（预设图标清单；本阶段只展示，不做切换生效）。 */
    const val ICON_PRESET = "icon_preset"
    const val DETAIL = "detail/{streamerId}"

    fun detail(streamerId: Long) = "detail/$streamerId"
    fun historyStreamer(streamerId: Long) = "history/streamer/$streamerId"
    fun statsStreamer(streamerId: Long) = "stats/streamer/$streamerId"
}

private data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

/**
 * Dock 栏圆角（dp）。形状与"实时玻璃裁剪路径"必须取自同一个值：
 * 裁剪半径与栏体圆角不一致时，玻璃会在四角溢出（或缩进）—— 那正是第一版的症状。
 */
private const val DOCK_CORNER_RADIUS_DP = 24

/**
 * Dock 栏样式（用户要求）：**悬浮**的圆角栏 —— 与屏幕左右下三边留出间距、
 * 自带圆角与阴影，而不是 Material 默认那种贴满整行、贴着屏幕底边的导航栏。
 *
 * 选中项用主色的半透明圆角块标出（而不是 M3 默认的胶囊指示器），
 * 这样它和"普通样式"一眼能区分开 —— 两个样式如果只是间距不同，用户会以为切换没生效。
 */
@Composable
private fun DockNavigationBar(
    destinations: List<BottomDestination>,
    currentRoute: String?,
    onSelect: (BottomDestination) -> Unit,
    glass: Boolean = false,
    /** 把 Dock 的窗口矩形上报给上层：实时玻璃需要**全屏**绘制再按这个矩形裁剪。 */
    onBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /** 实时玻璃层是否已由上层全屏绘制（是则本层不再画壁纸，避免盖住实时内容）。 */
    realtimeActive: Boolean = false,
    /** 背景图的压暗与不透明度：玻璃取样的那块像素必须按同样方式处理过，否则会比周围亮。 */
    backgroundDim: Float = 0f,
    backgroundAlpha: Float = 1f,
    /** 液态玻璃参数（用户要求：可自定义）。 */
    glassBlur: Float = 28f,
    glassTint: Float = 0.28f,
    glassHighlight: Float = 0.45f,
    glassRimAlpha: Float = 0.55f,
    glassRimWidth: Float = 1f
) {
    // 玻璃效果要知道"自己盖在背景图的哪一块"：用 onGloballyPositioned 拿窗口坐标
    val boundsState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val backgroundInfo = com.example.bilimonitor.ui.theme.LocalBackgroundImageInfo.current
    val dockRealtimeActive = realtimeActive

    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            // 系统导航栏内边距：Dock 是"悬浮"的，不会像 Material 的 NavigationBar 那样
            // 自己处理 windowInsets —— 少了这一行，栏体会被系统导航键压住一截（实测如此）。
            .navigationBarsPadding()
            // 浮动内边距（用户要求：让 Dock 更「悬浮」、离屏幕边缘更远）：左右 →44dp、上下 →26dp（第一版 32/16 差别肉眼不明显，直接加到位）。
            // 这两个常量就是「悬浮感」的全部调节点，想更飘就继续加大。
            .padding(horizontal = 44.dp, vertical = 26.dp)
    ) {
        // Dock 的容器色：玻璃态近乎透明（真正的观感由下面的模糊层提供），
        // 非玻璃态取 surfaceContainer —— 与浮动标题栏/搜索卡/筛选卡同一个颜色角色。
        val dockFill = if (glass) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.surfaceContainer
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                // ★ 量的是**栏体本身**，不是外面那层带 padding 的 Box。
                //   第一版量在 Box 上（含 24dp 左右留白 + 10dp 上下留白），
                //   于是取到的背景区域比栏体更宽更高、还偏了 24dp —— 玻璃里透出的
                //   那块壁纸与栏体背后真正的内容对不上，看起来就是"效果出错了"。
                // ★ 必须量 positionInRoot，不能量 positionInWindow：这个矩形最终交给
                //   **最外层 Box 里的全屏 Canvas** 做 clipPath，而 Canvas 的坐标原点就是根组合。
                //   本应用不是 edge-to-edge（MainActivity 没调 enableEdgeToEdge），窗口原点在状态栏之上、
                //   根组合原点在状态栏之下 —— 两者恒定差一个状态栏高度，于是玻璃会被整体画低一条状态栏，
                //   Dock 本体反而没有模糊。两个坐标系混用是这里唯一且致命的错误。
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    val r = androidx.compose.ui.geometry.Rect(
                        pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height
                    )
                    boundsState.value = r
                    onBounds?.invoke(r)
                },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(DOCK_CORNER_RADIUS_DP.dp),
            // 玻璃模式下容器近乎透明：真正的观感由下面的模糊层提供
            color = dockFill,
            // ★ 色调叠加的坑（用户报告的 Dock 偏红且"变不回纯白"）：M3 的 Surface 只在
            //   color == colorScheme.surface 时，才会把 surfaceTint（默认跟随 primary）按高度混合进去。
            //   所以主题色一变、或从玻璃态切回实色，Dock 就被染上主题色且再也回不到纯白。
            //   改用 surfaceContainer 并去掉高度色调：既绕开混合，又与浮动标题栏/筛选卡同一套观感。
            tonalElevation = 0.dp,
            // ★ 阴影高度与其余浮动卡片走**同一条规则**（见 ui/common/FloatingCard.kt 的"规则二"）：
            //   容器色半透明时不给阴影。玻璃态（Transparent）本来就是 0dp；而外观设置把卡片调成
            //   半透明（卡片透明度 < 100% / 卡片玻璃）时，Dock 走的实色分支也会变成半透明 ——
            //   那时若仍留 8dp 黑色阴影，Dock 下沿就会出现一圈"硬边暗框"，
            //   与用户报的"卡片下面还有一个淡淡的方框"是同一个现象。
            shadowElevation = floatingCardShadowElevation(dockFill, 8.dp)
        ) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                // ★ 玻璃层必须用 matchParentSize（**不参与测量**），不能用 fillMaxSize：
                //   这个 Box 的高度是自适应的，而 bottomBar 槽位传进来的最大高度是整屏，
                //   fillMaxSize 会把玻璃层撑成整屏高 —— 结果是导航项被顶到屏幕最上面、
                //   整屏内容被这层模糊+蒙版糊住。这正是"液态玻璃效果出错"的真正原因。
                //   写成 matchParentSize 后，尺寸由下面的 Row 决定，玻璃层只负责画在它背后。
                if (glass) {
                    androidx.compose.foundation.layout.Box(Modifier.matchParentSize()) {
                        GlassBackdrop(
                            bounds = boundsState.value,
                            info = backgroundInfo,
                            cornerRadiusDp = DOCK_CORNER_RADIUS_DP,
                            bgDim = backgroundDim,
                            bgAlpha = backgroundAlpha,
                            blurRadius = glassBlur,
                            tintAlpha = glassTint,
                            highlightAlpha = glassHighlight,
                            rimAlpha = glassRimAlpha,
                            rimWidthDp = glassRimWidth,
                            realtimeActive = dockRealtimeActive
                        )
                    }
                }
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                destinations.forEach { dest ->
                    val selected = currentRoute == dest.route
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .clickable { onSelect(dest) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .padding(horizontal = 14.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                dest.icon,
                                contentDescription = dest.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            dest.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * 液态玻璃背景层（用户要求：Dock 栏可选液态玻璃样式）。
 *
 * **为什么这里能做出真实的背景模糊**：Compose 没有"采样背后内容"的公开 API
 * （`Modifier.blur` 只模糊自己，系统 `setBackgroundBlurRadius` 是整窗口生效、
 * 不适用于页内一条浮层；第三方 Haze 库需要新依赖，本项目构建是离线的）。
 * 但背景图是**我们自己画的** —— 于是由 [com.example.bilimonitor.ui.theme.LocalBackgroundImageInfo]
 * 把背景图的缩放与原点传下来，这里按 Dock 在窗口里的实际位置裁出对应区域、
 * 用 `RenderEffect` 模糊后画在自己这一层。得到的是"玻璃下面真的透出壁纸"，
 * 而不是拿半透明色糊弄。
 *
 * 在此之上补三样，才像玻璃而不像一块磨砂贴纸：
 *  1. **极淡的体色蒙版**：模糊后仍会透出壁纸的颜色，压一点对比度；
 *  2. **顶部镜面高光渐变**；
 *  3. **1px 亮边**（玻璃的"棱"）—— 没有它就只是个半透明矩形。
 *
 * 用户没设背景图时退化为"半透明蒙版 + 高光 + 亮边"：仍有玻璃质感，
 * 只是没有可折射的内容。
 */
@Composable
private fun GlassBackdrop(
    bounds: androidx.compose.ui.geometry.Rect,
    info: com.example.bilimonitor.ui.theme.BackgroundImageInfo?,
    cornerRadiusDp: Int,
    bgDim: Float = 0f,
    bgAlpha: Float = 1f,
    blurRadius: Float = 28f,
    tintAlpha: Float = 0.28f,
    highlightAlpha: Float = 0.45f,
    rimAlpha: Float = 0.55f,
    rimWidthDp: Float = 1f,
    /** 实时层已由上层全屏绘制时，本层不再画壁纸（否则会盖住实时内容）。 */
    realtimeActive: Boolean = false
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadiusDp.dp)
    val isDark = com.example.bilimonitor.ui.theme.LocalEffectiveDarkTheme.current
    // ★ 实时背景层（用户要求：液态玻璃改成实时渲染）：有它就用它，
    //   取的是"滚动中的真实内容"，而不是静态壁纸 —— 这是真正的背景模糊。
    val realtimeLayer = com.example.bilimonitor.ui.theme.LocalRealtimeBackdropLayer.current
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().clip(shape)) {
        if (realtimeLayer != null && bounds.width > 0f && bounds.height > 0f) {
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = android.graphics.RenderEffect
                        .createBlurEffect(blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                    // 把图层里"Dock 所在的那块"平移到本层原点：平移量就是 Dock 的窗口位置
                    translationX = -bounds.left
                    translationY = -bounds.top
                }
            ) {
                drawLayer(realtimeLayer)
            }
        } else if (!realtimeActive && info != null && bounds.width > 0f && bounds.height > 0f) {
            val src = androidx.compose.runtime.remember(info, bounds) {
                info.sourceRect(bounds.left, bounds.top, bounds.width, bounds.height)
            }
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        renderEffect = android.graphics.RenderEffect
                            .createBlurEffect(blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP)
                            .asComposeRenderEffect()
                        // 背景若被用户调过透明度，这里也要一样淡 —— 否则玻璃块比周围"实"
                        alpha = bgAlpha.coerceIn(0f, 1f)
                    }
            ) {
                drawImage(
                    image = info.bitmap.asImageBitmap(),
                    srcOffset = androidx.compose.ui.unit.IntOffset(src.left, src.top),
                    srcSize = androidx.compose.ui.unit.IntSize(src.width(), src.height()),
                    dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                )
            }
            // ★ 复刻 AppBackground 的压暗层：用户把背景调暗了，玻璃下取样的那块像素
            //   也必须一样暗。第一版漏了这一步 —— 背景被调暗 35% 时，
            //   这块没跟着暗的玻璃就会变成背景上一个"发亮的洞"，也就是"效果出错了"。
            if (bgDim > 0f) {
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Color.Black.copy(alpha = bgDim.coerceIn(0f, 1f))
                    )
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                if (isDark) androidx.compose.ui.graphics.Color.Black.copy(alpha = tintAlpha)
                else androidx.compose.ui.graphics.Color.White.copy(alpha = tintAlpha)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to androidx.compose.ui.graphics.Color.White.copy(alpha = highlightAlpha * (if (isDark) 0.36f else 1f)),
                    0.45f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f),
                    1f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0f)
                )
            )
        )
        Box(
            Modifier.fillMaxSize().border(
                width = rimWidthDp.dp,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = rimAlpha * (if (isDark) 0.4f else 1f)),
                shape = shape
            )
        )
    }
}

@Composable
fun BiliMonitorApp(
    startDestination: String,
    pendingDeepLink: com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target?,
    onDeepLinkConsumed: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    /**
     * 外观设置（底部导航样式从这里读）。
     *
     * 由 MainActivity 传入而不是在这里用 hiltViewModel：主题与背景图已经在那一层消费了同一份设置，
     * 两处各建一个 ViewModel 只会让"同一个设置有两个来源"。
     */
    appearanceSettings: com.example.bilimonitor.data.repository.AppearanceSettingsRepository
) {
    val navController = rememberNavController()

    val bottomDestinations = listOf(
        BottomDestination(Routes.HOME, "主播", Icons.Filled.Home),
        BottomDestination(Routes.HISTORY, "历史", Icons.Filled.History),
        BottomDestination(Routes.STATS, "统计", Icons.Filled.BarChart),
        BottomDestination(Routes.SETTINGS, "设置", Icons.Filled.Settings)
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }

    // 底部导航样式（用户要求）：默认 Dock。这里读一次外观设置即可 ——
    // 用户在设置页改完会立即重组，不需要额外的状态同步机制。
    val appearance by appearanceSettings.flow.collectAsStateWithLifecycle(
        initialValue = com.example.bilimonitor.data.repository.AppearanceSettings()
    )
    val navigationStyle = appearance.navigationStyle
    val glassDock = appearance.glassDock

    // 通知点击深链（原规范 20/236）：解析出的目标在组合完成后导航一次并消费掉。
    LaunchedEffect(pendingDeepLink) {
        val target = pendingDeepLink ?: return@LaunchedEffect
        when (target) {
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.Streamer ->
                navController.navigate(Routes.detail(target.streamerId))
            // 下播通知（用户要求）：进该主播的**直播历史二级页**（Routes.HISTORY_STREAMER
            // → StreamerHistoryScreen），而不是监控卡片详情页 —— 主播已经下播，用户此刻要看的是
            // "这一场播了多久 / 历史场次"，历史页用 streamerId 现取自己的数据。
            // 开播类仍走上面的详情页分支（它正常路径是客户端跳板，这里是打不开客户端时的兜底），
            // 两者共用 `evt:` 前缀，靠解析器的独立变体区分，导航层不猜 eventType。
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.StreamerHistory ->
                navController.navigate(Routes.historyStreamer(target.streamerId))
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.Diagnostics ->
                navController.navigate(Routes.DIAGNOSTICS)
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.Unknown ->
                Unit // 事件已被清理：留在当前页，不误跳
        }
        onDeepLinkConsumed()
    }

    // 实时背景层：把 Scaffold（整页内容）的绘制录进图层，供 Dock 玻璃每帧取景。
    val contentLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    // Dock 的根组合矩形：由 DockNavigationBar 上报，实时玻璃层据此裁剪。
    var dockGlassRect by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    }

    // ★ 需要整屏录制层的唯一条件。录制是每帧一次全屏离屏渲染，代价实打实：
    //   之前它无条件挂在 Scaffold 上，于是哪怕用户关掉玻璃、切到普通导航样式，
    //   每次滚动仍在白付这笔开销。三个条件缺一不可。
    val needLayer = navigationStyle == com.example.bilimonitor.data.repository.NavigationStyle.DOCK &&
        glassDock && showBottomBar

    // ★ dockGlassRect 是"只赋值、从不重置"的状态，而玻璃闸门里带着 showBottomBar 之类的
    //   临时条件：一旦某个时刻不满足，旧矩形会永远留在那里 —— 表现就是切到普通样式或离开
    //   底栏页面后，标准导航栏上方浮着一块模糊幽灵矩形。这里显式清干净。
    LaunchedEffect(navigationStyle, showBottomBar, glassDock) {
        if (!needLayer) dockGlassRect = androidx.compose.ui.geometry.Rect.Zero
    }

    // 玻璃圆角必须按当前 density 换算成像素：写死 66f 只在 density 2.75 上恰好等于 24dp，
    // 换机型（2.0 / 3.0 …）裁剪半径就和 Dock 形状对不上，四角会露出或吃掉一块。
    val dockCornerRadiusPx = with(LocalDensity.current) { DOCK_CORNER_RADIUS_DP.dp.toPx() }

    androidx.compose.runtime.CompositionLocalProvider(
        com.example.bilimonitor.ui.theme.LocalRealtimeBackdropLayer provides
            // ★ 闸门只有一个：**只有真的在录制时才把图层交出去**。needLayer 为 false 时
            //   Scaffold 上的 drawWithContent 根本没挂，contentLayer 里留的是上一帧的旧画面
            //   （或从未录制过的空图层）—— 谁按旧矩形、低透明度把它画出来，就是"幽灵矩形"。
            // ★ 这里**曾经**还串了一个 appearance.glassCards（"卡片玻璃没开就不提供图层"），
            //   那是错的，已移除。图层唯一的消费方是 Dock 的 GlassBackdrop（只在 glassDock
            //   为真时组合），而 GlassBackdrop 是"有实时层就画实时层、否则退回静态壁纸"，
            //   偏偏它的调用点传的是 realtimeActive = true（含义是"实时层由上层全屏画好了，
            //   本层不要再画壁纸"）。于是"只开 Dock 玻璃"时：实时层被这个闸门掐掉、壁纸分支
            //   又被 realtimeActive 关掉 —— Dock 里既没有实时内容也没有壁纸，只剩半透明蒙版 +
            //   高光 + 亮边（用户要"两个开关各自生效"，这就是那一侧的失效点）。
            //   卡片玻璃从来就不消费这个图层（它靠壁纸磨砂 + 半透明底色），所以那个条件
            //   只会让"只开 Dock"失效，同时每帧的整屏录制照旧在跑 —— 白付一份全屏离屏渲染。
            if (needLayer) contentLayer else null
    ) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        // 按需录制：只有"Dock + 玻璃 + 底栏可见"时才挂，其余情况 modifier 链上什么都不加。
        modifier = Modifier.then(
            if (needLayer) {
                Modifier.drawWithContent {
                    // 每帧录制一次：录完立刻原样画出来，同时对 Dock 透明的那部分提供可采样图层。
                    contentLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(contentLayer)
                }
            } else Modifier
        ),
        // 内容不保留**底部**系统栏内边距（用户要求：列表要铺到页面最底边）：
        // 默认 WindowInsets 会把导航键区域也算作内边距，卡片因此永远离底边差一截。
        // 只留顶部与左右 —— Dock 自己带 navigationBarsPadding，仍会浮在导航键上方。
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            // consumeWindowInsets 是 M3 的约定：Scaffold 已经把 padding 交给我们了，
            // 必须声明"这些 inset 我消费掉了"，否则每个页面自己的 Scaffold 会再算一遍 ——
            // 横屏时左右各翻倍、底部 inset 又冒回来。
            // imePadding 补的是 IME：上面的 contentWindowInsets 只留了顶部与左右，
            // 底部 IME 一并被去掉，而全项目没有别处处理键盘。Android 15+ 起键盘默认不再
            // resize 内容（decorFitsSystemWindows 语义变化），不补这一层的话，
            // 搜索列表底部与 FAB 会被键盘永久盖住。键盘收起时该 inset 为 0，不影响原布局。
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onDone = {
                        onRequestNotificationPermission()
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenStreamer = { navController.navigate(Routes.detail(it)) },
                    // 健康横幅的「点此设置/授权」需要它；改成必填后漏传会直接编译失败，避免再次静默成死链
                    onNavigate = { route -> navController.navigate(route) }
                )
            }
            composable(Routes.HISTORY) {
                HistoryListScreen(onOpenStreamer = { navController.navigate(Routes.historyStreamer(it)) })
            }
            composable(
                Routes.HISTORY_STREAMER,
                arguments = listOf(navArgument("streamerId") { type = NavType.LongType })
            ) { entry ->
                val streamerId = entry.arguments?.getLong("streamerId") ?: 0L
                StreamerHistoryScreen(
                    streamerId = streamerId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.STATS) {
                StatsListScreen(onOpenStreamer = { navController.navigate(Routes.statsStreamer(it)) })
            }
            composable(
                Routes.STATS_STREAMER,
                arguments = listOf(navArgument("streamerId") { type = NavType.LongType })
            ) { entry ->
                val streamerId = entry.arguments?.getLong("streamerId") ?: 0L
                StreamerStatsScreen(
                    streamerId = streamerId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onReonboard = {
                        navController.navigate(Routes.ONBOARDING) {
                            popUpTo(Routes.HOME) { inclusive = false }
                        }
                    },
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                    onOpenData = { navController.navigate(Routes.DATA) },
                    onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenAppearance = { navController.navigate(Routes.APPEARANCE) },
                    onOpenTaxonomy = { navController.navigate(Routes.TAXONOMY) },
                    onOpenIconPreset = { navController.navigate(Routes.ICON_PRESET) }
                )
            }
            composable(Routes.TAXONOMY) {
                TaxonomyManageScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.APPEARANCE) {
                com.example.bilimonitor.ui.appearance.AppearanceScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ICON_PRESET) {
                IconPresetScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOGIN) { LoginScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DATA) { DataScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.NOTIFICATIONS) { NotificationHistoryScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { navController.popBackStack() }) }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("streamerId") { type = NavType.LongType })
            ) { entry ->
                val streamerId = entry.arguments?.getLong("streamerId") ?: 0L
                StreamerDetailScreen(
                    streamerId = streamerId,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                    // 详情页的两个快捷入口（用户要求）：跳到**该主播**的直播历史 / 统计二级页。
                    // 路由用页面回传的 id 拼（与上面这个 streamerId 是同一个值），
                    // 不在导航层从组合作用域里"顺手"读一个 id —— 谁点的、点的哪个主播都在参数上。
                    //
                    // 与历史 / 统计一级页点进二级页走的是**同一条路径**：navigate 后面不加
                    // popUpTo / launchSingleTop，纯粹压栈，所以这两页按返回必然回到本详情页；
                    // 而 `history/streamer/...`、`stats/streamer/...` 都不在底栏 tab 列表里，
                    // showBottomBar 自然为 false —— Dock 隐藏是既有行为，无需额外处理。
                    onOpenHistory = { navController.navigate(Routes.historyStreamer(it)) },
                    onOpenStats = { navController.navigate(Routes.statsStreamer(it)) }
                )
            }
        }
    }

        // ★ 实时玻璃层（用户要求：所有用液态玻璃的地方都走实时层）。
        //   必须在**全屏**画布上绘制录制层，只把 Dock 那块圆角矩形裁出来。
        //   第一版把它放在 Dock 自己的小画布里去平移 —— 平移后的内容整块被小画布裁掉，
        //   表现就是"什么都没透出来"。这是绘制载体选错，不是参数问题。
        //   闸门必须带 navigationStyle：切成"普通样式"后 Dock 不在组合里、不再上报矩形，
        //   若仍按 glassDock 画玻璃，就会拿旧矩形在标准导航栏上方画出一块幽灵。
        //   这里也要求 needLayer 给出的图层确实在录制，否则 drawLayer 画的是上一帧的残影。
        if (needLayer && dockGlassRect.width > 0f) {
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        renderEffect = android.graphics.RenderEffect
                            .createBlurEffect(
                                appearance.glassBlur,
                                appearance.glassBlur,
                                android.graphics.Shader.TileMode.CLAMP
                            )
                            .asComposeRenderEffect()
                    }
            ) {
                val r = dockGlassRect
                clipPath(
                    path = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                r,
                                // 与 Dock 形状同源（DOCK_CORNER_RADIUS_DP）+ 按 density 换算，
                                // 不再写死只在 density 2.75 成立的 66f。
                                androidx.compose.ui.geometry.CornerRadius(
                                    dockCornerRadiusPx,
                                    dockCornerRadiusPx
                                )
                            )
                        )
                    }
                ) {
                    drawLayer(contentLayer)
                }
            }
        }
        // Dock 做成**浮层**而不是 Scaffold 的 bottomBar（用户要求：页面内容要能延伸到 Dock 下面）。
        // bottomBar 槽位会为它预留高度，内容是"停在 Dock 上方"的；
        // 挪出来叠在内容之上后，列表就能从半透明的 Dock 底下滚过去。
        // 注意：内容区因此不再自动避让 Dock，各页列表需要自己的底部 contentPadding
        // 才能把最后一项完整滚出来（见台账 H41）。
        if (showBottomBar) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // 底部导航样式可切换（用户要求）：普通（贴底整条）/ Dock（悬浮圆角），默认 Dock。
                val style = navigationStyle
                val onSelect: (BottomDestination) -> Unit = { dest ->
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                when (style) {
                    com.example.bilimonitor.data.repository.NavigationStyle.NORMAL ->
                        NavigationBar {
                            bottomDestinations.forEach { dest ->
                                NavigationBarItem(
                                    selected = currentRoute == dest.route,
                                    onClick = { onSelect(dest) },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label) }
                                )
                            }
                        }
                    com.example.bilimonitor.data.repository.NavigationStyle.DOCK ->
                        DockNavigationBar(
                            destinations = bottomDestinations,
                            currentRoute = currentRoute,
                            onSelect = onSelect,
                            glass = glassDock,
                            onBounds = { dockGlassRect = it },
                            realtimeActive = true,
                            // 背景图的压暗/透明度要一起传下去：玻璃取样的那块像素
                            // 必须与周围背景受到同样的处理，否则会比周围亮一大截。
                            backgroundDim = appearance.backgroundDim,
                            backgroundAlpha = appearance.backgroundAlpha,
                            glassBlur = appearance.glassBlur,
                            glassTint = appearance.glassTint,
                            glassHighlight = appearance.glassHighlight,
                            glassRimAlpha = appearance.glassRimAlpha,
                            glassRimWidth = appearance.glassRimWidth
                        )
                }
            }
        }
    }   // 外层 Box
    }   // CompositionLocalProvider（实时背景层）—— 必须包住 Scaffold + 全屏玻璃画布 + Dock：
        // 三者都在读 LocalRealtimeBackdropLayer。第一版只包了 Scaffold，于是 Dock 里的
        // GlassBackdrop 永远读到 null，实时分支和壁纸分支全成了死代码，玻璃只能是半透明蒙版。
}