package com.example.bilimonitor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.notify.UrlPolicy
import com.example.bilimonitor.ui.detail.StreamerDetailScreen
import com.example.bilimonitor.ui.home.HomeScreen
import com.example.bilimonitor.ui.nav.BiliMonitorApp
import com.example.bilimonitor.ui.nav.Routes
import com.example.bilimonitor.ui.onboarding.OnboardingScreen
import com.example.bilimonitor.ui.settings.SettingsScreen
import com.example.bilimonitor.ui.theme.BiliMonitorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var appearanceRepository:
        com.example.bilimonitor.data.repository.AppearanceSettingsRepository
    @Inject lateinit var deepLinkResolver: com.example.bilimonitor.data.repository.NotificationDeepLinkResolver
    @Inject lateinit var notificationRepository: com.example.bilimonitor.data.repository.NotificationRepository
    @Inject lateinit var notificationDispatcher: com.example.bilimonitor.background.NotificationDispatcher
    @Inject lateinit var clock: com.example.bilimonitor.core.AppClock

    private var pendingUrl: String? by mutableStateOf(null)
    private var startDestination: String? by mutableStateOf(null)

    /**
     * 通知点击带入的深链目标（原规范 20/236）。
     * 原实现只把 URL 抛给系统浏览器、从不读取 eventKey，应用内从不导航。
     * 这里改为：解析 eventKey → 得到主播 id 或系统页 → 交给 Compose 导航消费。
     */
    private var pendingEventKey: String? by mutableStateOf(null)
    private var pendingTarget: com.example.bilimonitor.data.repository.NotificationDeepLinkResolver.Target? by mutableStateOf(null)

    /**
     * 已经消费过的深链 key / 直播页 URL。
     *
     * 只在"系统重放启动 Intent"这一条路径上参与去重（见 [handleIntent] 的 `dedupeAgainstHandled`）：
     * 进程还活着时靠 [Intent.removeExtra] 就摘干净了，但进程被系统回收过之后，系统服务器手里
     * 那份 Intent 副本仍带着 extras，我们改不到它 —— 重建时只能靠"这份内容处理过没有"来区分。
     * 存进 savedInstanceState，重建后仍然记得。
     */
    private var handledEventKey: String? = null
    private var handledUrl: String? = null

    /**
     * POST_NOTIFICATIONS 的授权结果。
     *
     * ★ 授权成功后必须**立刻**给那些"因缺权限而失败"的通知一次机会（复查发现的缺陷）：
     *   原先这里是空回调，而 `reviveFailed` 只在冷启动跑一次 —— 实时模式下前台服务能让进程
     *   存活数天，用户点了授权却什么都不会补发；等到下次冷启动时，超过 24 小时 TTL 的行
     *   已经永久失去资格（DAO 的 `expiresAt > :now` 条件）。用户以为"授权了就会收到"。
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching {
                    notificationRepository.reviveFailed(clock.nowWall())
                    notificationDispatcher.dispatchDue()
                }.onFailure {
                    android.util.Log.w("MainActivity", "通知能力恢复后的补投失败：${it.message}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先恢复"上一份启动 Intent 已经处理到哪儿了"。
        // 不能用"savedInstanceState 是否为 null"来代表"这是重建"：进程被系统杀掉后用户重新点
        // 通知时它同样不为 null，而那一次是货真价实的新点击，必须照常处理。
        handledEventKey = savedInstanceState?.getString(STATE_HANDLED_EVENT_KEY)
        handledUrl = savedInstanceState?.getString(STATE_HANDLED_URL)
        // 启动 Intent 参与去重：重建时它是被系统原样重放的那一份
        handleIntent(intent, dedupeAgainstHandled = true)

        lifecycleScope.launch {
            // 配置读取失败时**必须仍给出一个起始页**。
            // 原实现依赖 startDestination 非空才 setContent，一旦建库/种子失败
            // （磁盘满、迁移异常等）startDestination 恒为 null → setContent 永不调用 →
            // 用户看到无法自愈的永久白屏，连错误都没有。
            // 这里 config 为 null（含抛异常）时退回引导页，保证界面一定能出来；
            // 引导页的"确认选择"会走 completeOnboarding，从而在库可用时自愈。
            val config = runCatching { configRepository.getConfigFlow().first() }.getOrNull()
            val dest =
                if (config == null || config.backgroundKeepAliveChoice == com.example.bilimonitor.data.local.BackgroundKeepAliveChoice.UNSET) {
                    Routes.ONBOARDING
                } else {
                    Routes.HOME
                }
            startDestination = dest
            // 深链解析需要在组合前完成，避免首页闪一下再跳转
            pendingEventKey?.let { key ->
                pendingTarget = runCatching { deepLinkResolver.resolve(key) }.getOrNull()
                // 解析完立刻作废：这个 key 只代表"那一次点击"。留着它，下一次 Activity
                // 重建（旋转、进程恢复）就会被当成新的一次点击重新解析、重新导航。
                pendingEventKey = null
            }
            setContent {
                // 外观自定义（用户要求）：主题色 + 背景图（含明暗/透明度）。
                // 在 setContent 这一层读取，是因为主题与背景必须包住整个 NavHost —— 
                // 放到任何单个页面里都只能影响那一页。
                val appearance by appearanceRepository.flow.collectAsStateWithLifecycle(
                    initialValue = com.example.bilimonitor.data.repository.AppearanceSettings()
                )
                // 背景图按屏幕像素解码：壁纸是铺满屏幕的，解到屏幕尺寸就够，
                // 再大只是白占内存（12MP 照片按原尺寸解出来 ≈ 48MB，几张大图就 OOM）。
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current
                val targetWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
                val targetHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }

                // ★ key 必须包含 backgroundRevision：换背景图是**覆盖同一个文件**
                //   （custom_background.jpg），路径永远不变，只按路径缓存会一直命中原图 ——
                //   表现就是"背景图没有正确更换"。也**不要**改用文件路径或修改时间：
                //   文件名从来不变，而时间戳这种随手会动的量会让同一张图反复重解码。
                var backgroundBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                // 用户框选显示区域后，屏幕上只用到原图的一小块 —— 那一块的解码目标要按占比放大，
                // 否则一张 2000px 的图被框到 1/4、再放大 4 倍铺满屏幕就是糊的。
                // 文件本身的像素上限在保存时就定死了（最长边 2000px），要不到更多像素时
                // sample 会停在 1，因此这里放大目标只会"尽量取满"，不会凭空变清晰、也不会 OOM。
                val backgroundCrop = appearance.backgroundCrop
                val cropFractionWidth = backgroundCrop?.width?.takeIf { it > 0f } ?: 1f
                val cropFractionHeight = backgroundCrop?.height?.takeIf { it > 0f } ?: 1f
                val needWidthPx = (targetWidthPx / cropFractionWidth).toInt().coerceAtLeast(1)
                val needHeightPx = (targetHeightPx / cropFractionHeight).toInt().coerceAtLeast(1)
                LaunchedEffect(
                    appearance.backgroundFile?.absolutePath,
                    appearance.backgroundRevision,
                    // 区域一变，需要的解码像素数就变了（见上），必须重新解码：
                    // 少了这个 key，框选完回到界面还是那张按屏幕尺寸解的旧图。
                    backgroundCrop,
                    targetWidthPx,
                    targetHeightPx
                ) {
                    val file = appearance.backgroundFile
                    if (file == null) {
                        backgroundBitmap = null
                        return@LaunchedEffect
                    }
                    // 解码搬到 IO 线程：原来写在 remember {} 里，等于在组合期（主线程）解一张
                    // 全尺寸大图，首帧直接卡住。
                    val decoded = withContext(Dispatchers.IO) {
                        decodeSampledBitmap(file, needWidthPx, needHeightPx)
                    }
                    // 解不出来（文件损坏 / 被删 / OOM）时保留上一张：宁可继续显示旧壁纸，
                    // 也不要在换图那一瞬间闪成空白 —— 用户会以为图没选中。
                    if (decoded != null) backgroundBitmap = decoded
                }
                BiliMonitorTheme(
                    // 夜间模式：跟随系统 / 始终浅色 / 始终深色（用户要求，默认跟随）
                    darkTheme = when (appearance.themeMode) {
                        com.example.bilimonitor.data.repository.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                        com.example.bilimonitor.data.repository.ThemeMode.DARK -> true
                        com.example.bilimonitor.data.repository.ThemeMode.LIGHT -> false
                    },
                    customPrimary = appearance.themeColor,
                    transparentSurface = backgroundBitmap != null,
                    cardDim = appearance.cardDim,
                    cardAlpha = if (appearance.glassCards) (1f - appearance.glassTint).coerceAtLeast(0.35f) else appearance.cardAlpha
                ) {
                    com.example.bilimonitor.ui.theme.AppBackground(
                        bitmap = backgroundBitmap,
                        dim = appearance.backgroundDim,
                        alpha = appearance.backgroundAlpha,
                        // 显示区域（用户框选）在这里真正落地：只有这块区域会被铺满窗口，
                        // 与编辑器的取景框是同一比例、同一区域（依据见 BackgroundRegionMath）。
                        crop = appearance.backgroundCrop,
                        // 卡片玻璃：把壁纸本身模糊掉，半透明卡片透出的就是磨砂壁纸。
                        // ★ 模糊半径就是「模糊半径」参数：用户把它拉到 0，这里就是 0f ——
                        //   那一刻**壁纸并不磨砂**，卡片只剩半透明底色（在复杂背景上看起来
                        //   就是一层淡白色块）。这是刻意保留的行为（尊重用户把模糊设为 0 的
                        //   显式意图，不去替他"纠正"），因此文案只能写"磨砂程度由模糊参数决定"，
                        //   不能写成"开启卡片玻璃就把壁纸整体磨砂"。
                        // 判据只有 glassCards：Dock 玻璃不参与卡片与壁纸的渲染。
                        frostBlur = if (appearance.glassCards) appearance.glassBlur else 0f
                    ) {
                        BiliMonitorApp(
                            startDestination = dest,
                            pendingDeepLink = pendingTarget,
                            onDeepLinkConsumed = {
                                // 两个都要清：pendingTarget 是"待消费的目标"，
                                // pendingEventKey 是"待解析的原始 key" —— 只清前者，
                                // 它会在下一次重建时被重新解析并再导航一遍。
                                pendingTarget = null
                                pendingEventKey = null
                            },
                            onRequestNotificationPermission = { requestNotificationPermission() },
                            appearanceSettings = appearanceRepository
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // "处理过哪一份 Intent"必须跟着实例状态一起活下来，否则进程被回收后重建时
        // 系统重放启动 Intent，我们又会当成一次新的点击。
        outState.putString(STATE_HANDLED_EVENT_KEY, handledEventKey)
        outState.putString(STATE_HANDLED_URL, handledUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ★ 先把 key 取出来：handleIntent 会把它从 Intent 里摘掉（一次性消费），取晚了这里就拿不到。
        val key = intent.getStringExtra(EXTRA_EVENT_KEY)
        // onNewIntent 只会因为"系统把一份新 Intent 送到已存在的实例上"发生（用户点通知、
        // 通知上的动作按钮、兜底页转交），那就是一次真实的点击，不参与去重。
        handleIntent(intent, dedupeAgainstHandled = false)
        // Activity 已在前台时也要能响应：直接解析并交给已组合的导航
        if (key != null) {
            lifecycleScope.launch {
                pendingTarget = runCatching { deepLinkResolver.resolve(key) }.getOrNull()
                // 已经解析过，别再留着让下一次重建重复处理
                pendingEventKey = null
            }
        }
    }

    /**
     * 消费一次启动 / 新到达的 Intent。
     *
     * 一次性由两道保险共同保证：
     *  1. 读到之后立刻 [Intent.removeExtra] —— 本 Activity 是 singleTask，且 onNewIntent 里
     *     `setIntent` 会把同一个 Intent 对象继续留在实例上，配置变更（旋转）重建会把它原样
     *     再送一次；不摘掉就会重复拉起浏览器 + 重复导航同一条深链。
     *  2. [dedupeAgainstHandled] 的内容级去重 —— 进程被系统回收过之后，系统服务器手里那份
     *     Intent 副本仍带着 extras（我们改不到它），重建时只能靠"这份内容处理过没有"来识别。
     *     同一 eventKey / 同一 URL 视为同一个事件，不再处理第二遍。
     */
    private fun handleIntent(intent: Intent?, dedupeAgainstHandled: Boolean) {
        if (intent == null) return
        val key = intent.getStringExtra(EXTRA_EVENT_KEY)
        val url = intent.getStringExtra(EXTRA_URL)
        intent.removeExtra(EXTRA_EVENT_KEY)
        intent.removeExtra(EXTRA_URL)

        if (key != null && !(dedupeAgainstHandled && key == handledEventKey)) {
            handledEventKey = key
            pendingEventKey = key
        }
        if (url == null || (dedupeAgainstHandled && url == handledUrl)) return
        handledUrl = url
        val safe = UrlPolicy.validate(url)
        if (safe != null) {
            pendingUrl = safe
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(safe))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_EVENT_KEY = "eventKey"
        const val EXTRA_URL = "url"

        /** 已处理标记在 savedInstanceState 里的键 */
        private const val STATE_HANDLED_EVENT_KEY = "handledEventKey"
        private const val STATE_HANDLED_URL = "handledUrl"
    }
}

/**
 * 按目标尺寸降采样解码背景图。
 *
 * 两步都不能省：`inJustDecodeBounds` 只读文件头拿原始宽高（不分配像素内存），据此算出的
 * `inSampleSize` 才会在真正解码时生效。少了它，一张 12MP 的照片会被按原尺寸解成 ≈48MB 的
 * ARGB_8888 位图，换两张就是 OOM。
 *
 * 有意**不**调用 `Bitmap.recycle()` 回收上一张：它可能仍被上一帧的绘制 / RenderNode 引用，
 * 回收后继续绘制会直接崩（"trying to use a recycled bitmap"）。降采样之后单张只有屏幕大小
 * （几 MB），把引用换掉交给 GC 就够了，不值得为这点内存冒崩溃风险。
 */
private fun decodeSampledBitmap(
    file: java.io.File,
    reqWidth: Int,
    reqHeight: Int
): android.graphics.Bitmap? {
    if (reqWidth <= 0 || reqHeight <= 0) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
    }
    return runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath, options) }
        .getOrNull()
}

/** 2 的幂降采样：只要"再降一半仍不小于目标尺寸"就继续降；最小 1（只缩不放）。 */
private fun sampleSizeFor(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
        sample *= 2
    }
    return sample
}