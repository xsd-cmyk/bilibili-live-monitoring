package com.example.bilimonitor.ui.nav

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.navigation.NavController
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import kotlinx.coroutines.flow.first

private const val TabMillis = 200
private const val PageMillis = 240
internal data class TabSwap(val from: String, val to: String)
internal class TabPresentation {
    var swap by mutableStateOf<TabSwap?>(null)
    val panes = mutableStateMapOf<String, State<Float>>()
    val positions = mutableStateMapOf<String, Int>()
    var hostLeft = 0f
    private var preview by mutableStateOf<Pair<NavBackStackEntry, NavBackStackEntry>?>(null)

    fun chromeRoute(current: NavBackStackEntry?): String? = current?.destination?.route?.takeIf { it in tabRoutes }
        ?: preview?.takeIf { it.first === current }?.second?.destination?.route

    fun attachPreview(child: NavBackStackEntry, tab: NavBackStackEntry) { preview = child to tab }
    fun detachPreview(tab: NavBackStackEntry) { if (preview?.second === tab) preview = null }

    fun select(controller: NavHostController, route: String) {
        val from = controller.currentDestination?.route ?: return
        if (from !in tabRoutes || route !in tabRoutes || from == route) return
        swap = TabSwap(from, route)
        controller.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private val LocalPageController = staticCompositionLocalOf<NavHostController> { error("Missing page host") }
private val LocalTabs = staticCompositionLocalOf<TabPresentation> { error("Missing page host") }

internal fun NavGraphBuilder.tabPage(route: String, content: @Composable () -> Unit) {
    composable(route) { entry -> TabPane(entry, route, content) }
}

@Composable
private fun AnimatedContentScope.TabPane(entry: NavBackStackEntry, route: String, content: @Composable () -> Unit) {
    val tabs = LocalTabs.current
    val swap = tabs.swap
    val visible = transition.animateFloat(transitionSpec = {
        if (swap == null) tween(PageMillis, easing = LinearEasing) else tween(TabMillis)
    }, label = "tabVisibility") { if (it == EnterExitState.Visible) 1f else 0f }
    val controller = LocalPageController.current
    val previewOwner = remember(entry) {
        controller.currentBackStackEntry?.takeIf {
            it.destination.route !in tabRoutes && controller.previousBackStackEntry === entry
        }
    }
    DisposableEffect(tabs, entry, visible) {
        tabs.panes[route] = visible
        if (previewOwner != null) tabs.attachPreview(previewOwner, entry)
        onDispose {
            tabs.detachPreview(entry)
            if (tabs.panes[route] === visible) { tabs.panes.remove(route); tabs.positions.remove(route) }
        }
    }
    LaunchedEffect(swap, entry) {
        if (swap?.to == route) {
            entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
            if (tabs.swap === swap) tabs.swap = null
        }
    }
    val increasing = swap != null && tabRoutes.indexOf(swap.to) > tabRoutes.indexOf(swap.from)
    val rightward = increasing == (LocalLayoutDirection.current == LayoutDirection.Ltr)
    val arriving = route == swap?.to
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        tabs.positions[route] = (it.positionInRoot().x - tabs.hostLeft).roundToInt()
    }.drawWithContent {
        if (swap == null) { drawContent(); return@drawWithContent }
        if (route != swap.from && route != swap.to) return@drawWithContent
        val fraction = tabs.panes[swap.to]?.value ?: 0f
        val boundary = size.width * if (rightward) 1f - fraction else fraction
        val right = rightward == arriving
        clipRect(left = if (right) boundary else 0f, right = if (right) size.width else boundary) {
            this@drawWithContent.drawContent()
        }
    }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val progress = swap?.let { tabs.panes[it.to]?.value } ?: 0f
            val shift = if (swap == null) 0f else (if (arriving) 1f - progress else -progress) * size.width / 12f
            translationX = if (rightward) shift else -shift
        }) { content() }
    }
}

@Composable
internal fun PageNavHost(
    navController: NavHostController, startDestination: String, modifier: Modifier = Modifier,
    tabs: TabPresentation = remember { TabPresentation() }, backEdge: () -> Int = { 0 }, builder: NavGraphBuilder.() -> Unit
) {
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    DisposableEffect(navController, tabs) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (tabs.swap?.to != destination.route) tabs.swap = null
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    fun requested(from: NavBackStackEntry, to: NavBackStackEntry): Boolean = tabs.swap?.let {
        it.from == from.destination.route && it.to == to.destination.route
    } == true
    val tabIn = fadeIn(tween(TabMillis), initialAlpha = 1f)
    val tabOut = fadeOut(tween(TabMillis), targetAlpha = 1f)
    CompositionLocalProvider(LocalTabs provides tabs, LocalPageController provides navController) {
        NavHost(navController, startDestination, modifier.onGloballyPositioned { tabs.hostLeft = it.positionInRoot().x }.clipToBounds(),
            enterTransition = { if (requested(initialState, targetState)) tabIn else slideInHorizontally(tween(PageMillis)) { sign * it } },
            exitTransition = { if (requested(initialState, targetState)) tabOut else slideOutHorizontally(tween(PageMillis)) { -sign * it } },
            popEnterTransition = { if (requested(initialState, targetState)) tabIn else {
                val side = backEdge().takeUnless { it == 0 } ?: sign
                slideInHorizontally(tween(PageMillis, easing = LinearEasing)) { -side * it }
            } },
            popExitTransition = { if (requested(initialState, targetState)) tabOut else {
                val side = backEdge().takeUnless { it == 0 } ?: sign
                slideOutHorizontally(tween(PageMillis, easing = LinearEasing)) { side * it }
            } }, builder = builder)
    }
}
