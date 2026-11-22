package com.example.bilimonitor.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bilimonitor.data.repository.AppearanceSettings
import com.example.bilimonitor.data.repository.NavigationStyle
import com.example.bilimonitor.ui.data.DataScreen
import com.example.bilimonitor.ui.detail.StreamerDetailScreen
import com.example.bilimonitor.ui.diagnostics.DiagnosticsScreen
import com.example.bilimonitor.ui.history.HistoryListScreen
import com.example.bilimonitor.ui.history.StreamerHistoryScreen
import com.example.bilimonitor.ui.home.HomeScreen
import com.example.bilimonitor.ui.login.LoginScreen
import com.example.bilimonitor.ui.notifications.NotificationHistoryScreen
import com.example.bilimonitor.ui.onboarding.OnboardingScreen
import com.example.bilimonitor.ui.settings.IconPresetScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.bilimonitor.ui.settings.BackgroundSettingsScreen
import com.example.bilimonitor.ui.settings.MonitorSettingsScreen
import com.example.bilimonitor.ui.settings.NoticeSettingsScreen
import com.example.bilimonitor.ui.settings.SettingsGroup
import com.example.bilimonitor.ui.settings.SettingsViewModel
import com.example.bilimonitor.ui.settings.SettingsScreen
import com.example.bilimonitor.ui.settings.TaxonomyManageScreen
import com.example.bilimonitor.ui.stats.StatsListScreen
import com.example.bilimonitor.ui.stats.StreamerStatsScreen
import com.example.bilimonitor.ui.theme.AppBackground
import kotlinx.coroutines.flow.first

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val HISTORY = "history"
    const val HISTORY_STREAMER = "history/streamer/{streamerId}?session={session}"
    const val STATS = "stats"
    const val STATS_STREAMER = "stats/streamer/{streamerId}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val DATA = "data"
    const val NOTIFICATIONS = "notifications"
    const val NOTIFICATION_BATCH = "notifications/batch/{eventKey}"
    fun notificationBatch(key: String) = "notifications/batch/${android.net.Uri.encode(key)}"
    const val DIAGNOSTICS = "diagnostics"

    /** 标签 / 分组管理（用户要求：可删除、可排序）。 */
    const val TAXONOMY = "taxonomy"

    /** 自定义应用名称与图标（用户要求）。 */
    const val APPEARANCE = "appearance"

    /** 更换应用图标（预设图标清单；本阶段只展示，不做切换生效）。 */
    const val ICON_PRESET = "icon_preset"
    const val DETAIL = "detail/{streamerId}"

    fun detail(streamerId: Long) = "detail/$streamerId"
    fun historyStreamer(streamerId: Long, session: String? = null) =
        "history/streamer/$streamerId" + (session?.let { "?session=${android.net.Uri.encode(it)}" } ?: "")
    fun statsStreamer(streamerId: Long) = "stats/streamer/$streamerId"
}

private val bottomDestinations = listOf(
    BottomDestination(Routes.HOME, "主播", Icons.Filled.Home),
    BottomDestination(Routes.HISTORY, "历史", Icons.Filled.History),
    BottomDestination(Routes.STATS, "统计", Icons.Filled.BarChart),
    BottomDestination(Routes.SETTINGS, "设置", Icons.Filled.Settings)
)
internal val tabRoutes = bottomDestinations.map { it.route }

internal data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

@Composable
fun BiliMonitorApp(
    startDestination: String,
    pendingDeepLink: com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target?,
    onDeepLinkConsumed: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    appearance: AppearanceSettings,
    backgroundBitmap: android.graphics.Bitmap?,
    backEdge: () -> Int = { 0 }
) {
    val navController = rememberNavController()
    val notificationContext = androidx.compose.ui.platform.LocalContext.current

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val tabs = remember { TabPresentation() }
    val activeTab = currentRoute?.takeIf { it in tabRoutes }
    val chromeRoute = tabs.chromeRoute(backStackEntry)
    val showBottomBar = chromeRoute != null

    val navigationStyle = appearance.navigationStyle
    val glassDock = appearance.glassDock

    // 通知点击深链（原规范 20/236）：解析出的目标在组合完成后导航一次并消费掉。
    LaunchedEffect(pendingDeepLink) {
        val target = pendingDeepLink ?: return@LaunchedEffect
        // NavHost installs its graph in a later composition effect. A fast cold-start resolver
        // can finish before that; wait for the first actual entry before navigating.
        navController.currentBackStackEntryFlow.first()
        when (target) {
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.Streamer ->
                navController.navigate(Routes.detail(target.streamerId))
            // 下播通知（用户要求）：进该主播的**直播历史二级页**（Routes.HISTORY_STREAMER
            // → StreamerHistoryScreen），而不是监控卡片详情页 —— 主播已经下播，用户此刻要看的是
            // "这一场播了多久 / 历史场次"，历史页用 streamerId 现取自己的数据。
            // 开播类仍走上面的详情页分支（它正常路径是客户端跳板，这里是打不开客户端时的兜底），
            // 两者共用 `evt:` 前缀，靠解析器的独立变体区分，导航层不猜 eventType。
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.StreamerHistory ->
                navController.navigate(Routes.historyStreamer(target.streamerId, target.sessionStableId))
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.Batch ->
                navController.navigate(Routes.notificationBatch(target.eventKey))
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.Diagnostics ->
                navController.navigate(Routes.DIAGNOSTICS)
            is com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target.Unknown -> {
                navController.navigate(Routes.NOTIFICATIONS)
                android.widget.Toast.makeText(notificationContext, "通知对应记录已清理，已打开通知历史", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        onDeepLinkConsumed()
    }

    val dockClearance = remember { mutableStateOf(DefaultDockClearance) }
    val needLayer = navigationStyle == NavigationStyle.DOCK && glassDock && showBottomBar
    val scene = if (needLayer) rememberGraphicsLayer() else null
    SideEffect { scene?.compositingStrategy = if (appearance.liquidCards) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    val origin = remember { mutableStateOf(Offset.Zero) }
    val capture = if (scene == null) Modifier else Modifier.onGloballyPositioned { origin.value = it.positionInRoot() }
        .drawWithContent {
            scene.record { this@drawWithContent.drawContent() }
            drawLayer(scene)
        }

    Box(Modifier.fillMaxSize()) {
        AppBackground(bitmap = backgroundBitmap, dim = appearance.backgroundDim, alpha = appearance.backgroundAlpha,
            crop = appearance.backgroundCrop, frostBlur = if (appearance.glassCards && !appearance.liquidCards) appearance.glassBlur else 0f,
            cardMaterial = appearance.takeIf { it.liquidCards },
            modifier = capture) {
            CompositionLocalProvider(LocalDockClearance provides
                (if (navigationStyle == NavigationStyle.DOCK) dockClearance.value else null)) {
                ScreenHost(navController, startDestination, onRequestNotificationPermission, tabs, backEdge)
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
                    .layout { measurable, constraints ->
                        val bar = measurable.measure(constraints)
                        layout(bar.width, bar.height) {
                            val offset = if (tabs.swap != null) 0 else tabs.positions[chromeRoute]
                            if (offset != null) bar.placeWithLayer(offset, 0)
                        }
                    }
                    .then(if (activeTab == null) Modifier.clearAndSetSemantics {} else Modifier)
            ) {
                // 底部导航样式可切换（用户要求）：普通（贴底整条）/ Dock（悬浮圆角），默认 Dock。
                val style = navigationStyle
                val onSelect: (BottomDestination) -> Unit = { tabs.select(navController, it.route) }
                when (style) {
                    com.example.bilimonitor.data.repository.NavigationStyle.NORMAL ->
                        NavigationBar {
                            bottomDestinations.forEach { dest ->
                                NavigationBarItem(
                                    enabled = activeTab != null,
                                    selected = chromeRoute == dest.route,
                                    onClick = { onSelect(dest) },
                                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                                    label = { Text(dest.label) }
                                )
                            }
                        }
                    com.example.bilimonitor.data.repository.NavigationStyle.DOCK ->
                        DockNavigationBar(
                            destinations = bottomDestinations,
                            currentRoute = chromeRoute,
                            interactive = activeTab != null,
                            onSelect = onSelect,
                            scene = scene,
                            sceneOrigin = { origin.value },
                            appearance = appearance,
                            onClearanceChanged = { dockClearance.value = it }
                        )
                }
            }
        }
    }
}

@Composable
private fun ScreenHost(navController: NavHostController, startDestination: String, onRequestNotificationPermission: () -> Unit, tabs: TabPresentation, backEdge: () -> Int) {
    val notificationContext = androidx.compose.ui.platform.LocalContext.current
    Scaffold(
        // 内容不保留**底部**系统栏内边距（用户要求：列表要铺到页面最底边）：
        // 默认 WindowInsets 会把导航键区域也算作内边距，卡片因此永远离底边差一截。
        // 只留顶部与左右 —— Dock 自己带 navigationBarsPadding，仍会浮在导航键上方。
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        PageNavHost(
            navController = navController, tabs = tabs, backEdge = backEdge,
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
                            launchSingleTop = true
                        }
                    }
                )
            }
            tabPage(Routes.HOME) {
                HomeScreen(
                    onOpenStreamer = { navController.navigate(Routes.detail(it)) },
                    // 健康横幅的「点此设置/授权」需要它；改成必填后漏传会直接编译失败，避免再次静默成死链
                    onNavigate = { route -> if (route in tabRoutes) tabs.select(navController, route) else navController.navigate(route) }
                )
            }
            tabPage(Routes.HISTORY) {
                HistoryListScreen(onOpenStreamer = { navController.navigate(Routes.historyStreamer(it)) })
            }
            composable(
                Routes.HISTORY_STREAMER,
                arguments = listOf(navArgument("streamerId") { type = NavType.LongType },
                    navArgument("session") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                val streamerId = entry.arguments?.getLong("streamerId") ?: 0L
                StreamerHistoryScreen(
                    streamerId = streamerId,
                    focusedSession = entry.arguments?.getString("session"),
                    onBack = { navController.popBackStack() }
                )
            }
            tabPage(Routes.STATS) {
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
            tabPage(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenGroup = { navController.navigate(it.route) },
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                    onOpenData = { navController.navigate(Routes.DATA) },
                    onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onOpenAppearance = { navController.navigate(Routes.APPEARANCE) },
                    onOpenTaxonomy = { navController.navigate(Routes.TAXONOMY) },
                    onOpenIconPreset = { navController.navigate(Routes.ICON_PRESET) }
                )
            }
            SettingsGroup.entries.forEach { group ->
                composable(group.route) { entry ->
                    val owner = remember(entry) { navController.getBackStackEntry(Routes.SETTINGS) }
                    val viewModel: SettingsViewModel = hiltViewModel(owner)
                    val onBack: () -> Unit = { navController.popBackStack() }
                    when (group) {
                        SettingsGroup.MONITOR -> MonitorSettingsScreen(viewModel, onBack)
                        SettingsGroup.NOTICES -> NoticeSettingsScreen(viewModel, onBack)
                        SettingsGroup.BACKGROUND -> BackgroundSettingsScreen(viewModel, onBack) {
                            navController.navigate(Routes.ONBOARDING) { popUpTo(Routes.HOME) { inclusive = false } }
                        }
                    }
                }
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
            composable(Routes.NOTIFICATION_BATCH,
                arguments = listOf(navArgument("eventKey") { type = NavType.StringType })
            ) {
                com.example.bilimonitor.ui.notifications.NotificationBatchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenHistory = { navController.navigate(Routes.NOTIFICATIONS) },
                    onOpenMember = { member, isEnd ->
                        if (member.streamerId == null) {
                            android.widget.Toast.makeText(notificationContext, "主播记录已删除", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (isEnd) {
                            navController.navigate(Routes.historyStreamer(member.streamerId, member.sessionStableId))
                        } else {
                            notificationContext.startActivity(
                                com.example.bilimonitor.notify.LiveRoomRedirectActivity.intent(
                                    notificationContext, null, member.url, member.eventKey, member.hint))
                        }
                    }
                )
            }
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
}
