package com.example.bilimonitor.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appearanceSettingsDataStore by preferencesDataStore(name = "appearance_settings")

/**
 * 外观自定义（用户要求：可自行修改应用名称与图标，图标支持自己上传图片并在应用内裁剪）。
 *
 * ## 先划清 Android 的能力边界（这一点界面里也会如实写给用户）
 *
 * **第三方应用无法在运行时替换自己的桌面图标与名称**：`AndroidManifest` 是编译期固定的，
 * 系统设置里"应用信息"页显示的名称/图标同样改不了。能改的是这三处：
 *
 *  1. **应用内显示的名称** —— 完全可控（首页标题等），本类存的就是它；
 *  2. **桌面快捷方式（pinned shortcut）** —— 系统允许应用请求把一条快捷方式钉到桌面，
 *     其图标可以是任意 `Bitmap`（`Icon.createWithBitmap`）、标签可以是任意字符串。
 *     这是所有"图标修改器"类应用的通行做法：桌面上多一个你自定义的入口，
 *     应用抽屉里的原始图标保持不变；
 *  3. **通知里的图标** —— 大图标可用 Bitmap（`setLargeIcon`）。
 *
 * 之所以把边界写进注释和界面，而不是含糊地叫"修改应用图标"：用户按字面理解会以为
 * 应用抽屉里的图标也会变，然后在桌面上找不到自己"改过"的图标，以为功能坏了。
 *
 * ## 存储
 *
 * DataStore 存名称与图标文件名（应用私有目录里的裁剪结果），与 `QuietHoursRepository` 等一致：
 * 纯外观偏好，不参与任何业务判定，不动 `monitoring_config` 的 schema。
 */
@Singleton
class AppearanceSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyName = stringPreferencesKey("app_display_name")
    private val keyIcon = stringPreferencesKey("app_icon_file")
    private val keyThemeColor = stringPreferencesKey("theme_color")
    private val keyBackground = stringPreferencesKey("background_image_file")
    /** 背景图版本号：换图时写新的时间戳，用来**强制产生状态变化**（见下）。 */
    private val keyBackgroundRevision = stringPreferencesKey("background_revision")
    private val keyBackgroundDim = stringPreferencesKey("background_dim")
    private val keyBackgroundAlpha = stringPreferencesKey("background_alpha")

    /**
     * 背景图的**显示区域**（用户框选：整张图的哪一块作为背景）。
     *
     * ★ 新键（见 [keyGlassCards] 的教训）：老安装盘上没有它 → 读不到 → [AppearanceSettings.backgroundCrop]
     *   落到 null → 渲染退回"整张图居中铺满"，也就是这个功能上线**之前**的行为。
     *   因此不需要任何迁移代码，也不会因为缺键而显示异常。
     * ★ 存的是**区域参数**，不是"裁好的图"：原图文件原样留着，反复调整取景不会重新编码 JPEG
     *   （见 [setBackgroundCrop]），"再次调整区域"也就不需要重新选图。
     * ★ 四个值放**同一个键**里（`left,top,right,bottom`）：它们是一个整体，拆成四个键就可能被读到
     *   "写了一半"的组合（左边界是新的、右边界还是旧的），那会渲染出一块谁都没选过的区域。
     */
    private val keyBackgroundCrop = stringPreferencesKey("background_crop")
    private val keyNavStyle = stringPreferencesKey("navigation_style")
    private val keyGlassDock = booleanPreferencesKey("glass_dock")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyCardDim = stringPreferencesKey("card_dim")
    private val keyCardAlpha = stringPreferencesKey("card_alpha")

    /**
     * 直播历史 / 统计一级列表的排序方式（用户要求：做成四选一，默认第 3 种）。
     *
     * ★ 这是一个**全新的键**，而不是复用已有键：见 [keyGlassCards] 的教训 ——
     *   同一个键名承载过的旧值会原样被读出来，改类型会崩、改语义会静默错。
     *   老安装盘上没有这个键 → 读不到 → 落到 [SessionSortOrder.DEFAULT]（第 3 种），
     *   因此**不需要写任何迁移代码**，升级上来就是用户要的默认行为。
     */
    private val keySessionSortOrder = stringPreferencesKey("session_sort_order")

    /**
     * 「开启数据过期提示」开关（用户要求：高级设置里可关，**默认开启**）。
     *
     * ★ 这是一个**全新的键名**（`freshness_expiry_hint`，此前没有任何版本用过）：
     *   见 [keyGlassCards] 的教训 —— 同一个键名承载过的旧值会按新类型被强转，
     *   `prefs[key] as Boolean?` 泛型擦除后不做检查，老安装会在读取处抛
     *   `ClassCastException` 直接崩。新键读不到 ⇒ 落到默认值 true ⇒
     *   **不需要任何迁移代码**，升级上来就是"过期提示开着"。
     *
     * ★ 为什么放这里（DataStore 展示偏好）而不是 `monitoring_config`：
     *   它是**纯展示**开关（只决定首页卡片标不标「· 数据过期」），不参与任何业务判定、
     *   不影响调度与落库；塞进配置表要同时改 schema、迁移、备份 DTO、诊断导出四处，
     *   而收益是零 —— 与名称/图标/导航样式等偏好是同一条取舍。
     */
    private val keyFreshnessExpiryHint = booleanPreferencesKey("freshness_expiry_hint")

    /**
     * 桌面图标切换的**进行中标记**（值 = 目标图标入口的全类名，如
     * `com.example.bilimonitor.IconPreset1`）。
     *
     * ★ 这是**纯诊断**用的键，**绝不是"当前使用哪个图标"的来源**。当前生效项只能从
     *   `PackageManager.getComponentEnabledSetting` 读：用户可能在系统设置里改过、
     *   也可能上一次切换中途被系统结束 —— 任何一种都会让"本应用自己记的偏好"变成谎话。
     *   它的唯一用途见 [setIconSwitchPending]。
     *
     * ★ 新增键而不是复用已有键：见 [keyGlassCards] 的教训（同一个键名承载过的旧值会原样被读出来）。
     *   老安装盘上没有这个键 → 读不到 → 不显示任何"上次切换"的提示，正是要的默认行为，
     *   因此不需要任何迁移代码。
     */
    private val keyIconSwitchPending = stringPreferencesKey("icon_switch_pending")

    /**
     * 用户**上次成功切换到**的图标/名称组合格子（值形如 `1,2`，即 `<图标下标>,<名称下标>`）。
     *
     * ★ 这是一个**全新的键**（与 [keyIconSwitchPending] 同样出于 [keyGlassCards] 的教训：
     *   同一个键名承载过的旧值会原样被读出来，改类型会崩、改语义会静默错）。
     *   老安装盘上没有它 → 读不到 → 返回 null → 更新后的自救援直接回落到默认入口
     *   （见 `background/LauncherEntryRescueReceiver`）：不崩，也不需要任何迁移代码。
     *
     * ★★ 它**只用于"更新后自救援"这一件事，绝不是"当前用的是哪个图标"的来源**。
     *   界面上的"使用中"仍然只能从 `PackageManager.getComponentEnabledSetting` 读：用户可能在
     *   系统设置里改过、上一次切换也可能中途被系统结束 —— 任何一处都会让"本应用自己记的偏好"
     *   变成谎话（见 `ui/settings/IconPresetSwitcher.kt` 文件头的"唯一事实来源：PackageManager"）。
     *   **请勿在任何界面逻辑里读这个键。**
     *
     * ★ 存**格子**（而不是组件名）：别名命名改过（单轴 IconPresetN -> 双轴 IconPreset_i_j）之后
     *   组件名会失效，而格子仍能通过当前的 `R.array.preset_alias_matrix` 重新解析出组件 ——
     *   正是救援要的语义；反过来，格子若在当前组合表里已经不存在，救援会明确回落到默认入口。
     */
    private val keyIconPresetChoiceCell = stringPreferencesKey("icon_preset_choice_cell")

    /**
     * 卡片玻璃开关。
     *
     * ★ 键名带 `_boolean` 后缀是**故意**的：旧版本把它当字符串存（"true"/"false"），
     *   而 Preferences 的取值是按类型强转的（`prefs[key] as T?`，泛型擦除后不做检查），
     *   同一个键名改成 Boolean 后，旧值会在**读取处**（`?: false` 之后的拆箱）抛
     *   ClassCastException —— 不是"读不出就回落默认"那么温和，老安装会直接崩。
     *   换个键名，旧值自然读不到；[keyGlassCardsLegacy] 只作为兼容回读。
     */
    private val keyGlassCards = booleanPreferencesKey("glass_cards_boolean")
    private val keyGlassCardsLegacy = stringPreferencesKey("glass_cards")
    private val keyGlassBlur = stringPreferencesKey("glass_blur")
    private val keyGlassTint = stringPreferencesKey("glass_tint")
    private val keyGlassHighlight = stringPreferencesKey("glass_highlight")
    private val keyGlassRimAlpha = stringPreferencesKey("glass_rim_alpha")
    private val keyGlassRimWidth = stringPreferencesKey("glass_rim_width")

    val flow: Flow<AppearanceSettings> = context.appearanceSettingsDataStore.data.map { prefs ->
        // 图标/背景文件可能被系统清理掉：读的时候校验存在性，避免界面上出现"已设置但加载不出来"
        val iconFileName = prefs[keyIcon]
        val backgroundFileName = prefs[keyBackground]
        AppearanceSettings(
            name = prefs[keyName] ?: DEFAULT_NAME,
            iconFile = iconFileName?.let { iconFile(it) }?.takeIf { it.exists() },
            themeColor = prefs[keyThemeColor]?.let { ColorHex.parse(it) },
            backgroundFile = backgroundFileName?.let { backgroundFile(it) }?.takeIf { it.exists() },
            backgroundRevision = prefs[keyBackgroundRevision]?.toLongOrNull() ?: 0L,
            // 显示区域：读不到（老安装没有、从没框选过）或值已损坏（被手工改过盘）都落到 null
            // = "整张图居中铺满"，与界面上的默认口径、与渲染侧的兜底是同一条路径。
            backgroundCrop = BackgroundCrop.parse(prefs[keyBackgroundCrop]),
            backgroundDim = prefs[keyBackgroundDim]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: DEFAULT_DIM,
            backgroundAlpha = prefs[keyBackgroundAlpha]?.toFloatOrNull()?.coerceIn(0.1f, 1f)
                ?: DEFAULT_ALPHA,
            // 底部导航样式：**默认 Dock**（用户定稿）。读不到键时也走 Dock，
            // 所以老安装升级上来会直接变成 Dock 样式，而不是"没设置过就用普通样式"。
            navigationStyle = prefs[keyNavStyle]
                ?.let { name -> runCatching { NavigationStyle.valueOf(name) }.getOrNull() }
                ?: NavigationStyle.DOCK,
            glassDock = prefs[keyGlassDock] ?: false,
            themeMode = prefs[keyThemeMode]?.let { n -> runCatching { ThemeMode.valueOf(n) }.getOrNull() } ?: ThemeMode.SYSTEM,
            cardDim = prefs[keyCardDim]?.toFloatOrNull()?.coerceIn(0f, 0.9f) ?: DEFAULT_CARD_DIM,
            cardAlpha = prefs[keyCardAlpha]?.toFloatOrNull()?.coerceIn(0.3f, 1f) ?: DEFAULT_CARD_ALPHA,
            glassCards = prefs[keyGlassCards]
                ?: prefs[keyGlassCardsLegacy]?.toBooleanStrictOrNull()
                ?: false,
            glassBlur = prefs[keyGlassBlur]?.toFloatOrNull()?.coerceIn(0f, 60f) ?: DEFAULT_GLASS_BLUR,
            glassTint = prefs[keyGlassTint]?.toFloatOrNull()?.coerceIn(0f, 0.8f) ?: DEFAULT_GLASS_TINT,
            glassHighlight = prefs[keyGlassHighlight]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: DEFAULT_GLASS_HIGHLIGHT,
            glassRimAlpha = prefs[keyGlassRimAlpha]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: DEFAULT_GLASS_RIM_ALPHA,
            glassRimWidth = prefs[keyGlassRimWidth]?.toFloatOrNull()?.coerceIn(0f, 3f) ?: DEFAULT_GLASS_RIM_WIDTH,
            // 列表排序：读不到键（老安装没有、首次安装还没写过）或值已无法识别（被手工改过盘、
            // 未来删过枚举项）都回落到 [SessionSortOrder.DEFAULT]（第 3 种）——
            // 与界面上的默认选项同一个来源，不会出现"选中项显示第 1 种、实际按第 3 种排"。
            sessionSortOrder = prefs[keySessionSortOrder]
                ?.let { name -> runCatching { SessionSortOrder.valueOf(name) }.getOrNull() }
                ?: SessionSortOrder.DEFAULT,
            // 数据过期提示：读不到键（老安装没有、首次安装还没写过）就是默认的"开启"。
            // 不用 runCatching 包 valueOf：这里存的是 Boolean，不是枚举名。
            freshnessExpiryHintEnabled = prefs[keyFreshnessExpiryHint] ?: DEFAULT_FRESHNESS_EXPIRY_HINT
        )
    }

    /**
     * 数据过期提示开关（用户要求：高级设置里可关，默认开）。
     *
     * 关掉之后首页卡片不再追加「· 数据过期」，**时间行照常显示** —— 用户明确要求
     * "封禁中仍显示时间，方便看到目前状态"，而"上次检查是多久以前"在开关关闭时同样是
     * 判断数据能不能信的唯一依据，不该被一起藏掉（见 `FreshnessPolicy.freshnessRowOf`）。
     */
    suspend fun setFreshnessExpiryHint(enabled: Boolean) {
        context.appearanceSettingsDataStore.edit { it[keyFreshnessExpiryHint] = enabled }
    }

    suspend fun setNavigationStyle(style: NavigationStyle) {
        context.appearanceSettingsDataStore.edit { it[keyNavStyle] = style.name }
    }

    /**
     * 液态玻璃效果（用户要求：可选开关，默认关）。
     *
     * ★ 关闭时**同时**关掉卡片玻璃（[keyGlassCards]）：卡片玻璃的半透明与整体磨砂都由这一套
     *   玻璃参数驱动，Dock 关掉之后它就成了一个没有入口可关的孤儿开关（用户反馈：
     *   关掉 Dock 玻璃后"卡片玻璃"开关随卡片一起消失，卡片再也回不到不透明）。
     *   放在同一个 edit 事务里写：分两次写会短暂出现"Dock 关了、卡片还玻璃着"的中间态。
     */
    suspend fun setGlassDock(enabled: Boolean) {
        context.appearanceSettingsDataStore.edit {
            it[keyGlassDock] = enabled
            // ★ 不在这里联动关闭卡片玻璃（用户要求两个开关**独立**：四种组合各自可达、
            //   关掉 Dock 玻璃不该顺手改掉卡片的设置）。原本这行会让"关 Dock"静默改掉卡片玻璃，
            //   而界面上再没有任何说明，属于"静默改变用户设置"。
        }
    }

    /**
     * 液态玻璃参数（用户要求：Dock 的玻璃效果同步应用到所有卡片，且参数可自定义）。
     *
     * @param cards      是否把玻璃效果也应用到卡片（卡片改为半透明 + 背景整体磨砂）
     * @param blur       模糊半径（dp）
     * @param tint       玻璃体色浓度 0~0.8
     * @param highlight  顶部高光强度 0~1
     * @param rimAlpha   亮边透明度 0~1
     * @param rimWidth   亮边宽度（dp）0~3
     */
    suspend fun setGlassParams(
        cards: Boolean,
        blur: Float,
        tint: Float,
        highlight: Float,
        rimAlpha: Float,
        rimWidth: Float
    ) {
        context.appearanceSettingsDataStore.edit {
            it[keyGlassCards] = cards
            it[keyGlassBlur] = blur.coerceIn(0f, 60f).toString()
            it[keyGlassTint] = tint.coerceIn(0f, 0.8f).toString()
            it[keyGlassHighlight] = highlight.coerceIn(0f, 1f).toString()
            it[keyGlassRimAlpha] = rimAlpha.coerceIn(0f, 1f).toString()
            it[keyGlassRimWidth] = rimWidth.coerceIn(0f, 3f).toString()
        }
    }

    /** 卡片外观（用户要求：卡片本身可调透明度与明暗）。 */
    suspend fun setCardAppearance(dim: Float, alpha: Float) {
        context.appearanceSettingsDataStore.edit {
            it[keyCardDim] = dim.coerceIn(0f, 0.9f).toString()
            it[keyCardAlpha] = alpha.coerceIn(0.3f, 1f).toString()
        }
    }

    /** 夜间模式（用户要求：可跟随系统，默认跟随）。 */
    suspend fun setThemeMode(mode: ThemeMode) {
        context.appearanceSettingsDataStore.edit { it[keyThemeMode] = mode.name }
    }

    /**
     * 切换一级列表的排序方式（用户要求四选一，见 [SessionSortOrder]，**默认第 3 种**）。
     *
     * 两个列表页（直播历史 / 统计）读的是**同一个键**：排序是"我怎么看这两张表"的口径，
     * 每页各存一份早晚会漂移成"历史页按开播、统计页按下播"，同一个主播在两页的先后位置对不上，
     * 用户会以为数据错了（这与「最近：…」必须两页同源是同一条理由）。
     */
    suspend fun setSessionSortOrder(order: SessionSortOrder) {
        context.appearanceSettingsDataStore.edit { it[keySessionSortOrder] = order.name }
    }

    /**
     * 桌面图标切换的进行中标记（见 [keyIconSwitchPending]）。
     *
     * 为什么需要它：切换图标要先启用新入口、再禁用旧入口，而禁用 `MainActivity`
     * （或禁用当前正被桌面使用的那个别名）会让应用进程被系统结束 —— 那一瞬间用户已经点了"确认"，
     * 却看不到任何结果提示。下一个打开应用的人（可能已经是几分钟后）需要知道上一次到底成没成：
     * 凭这个标记就能如实说明，而不是让这次操作**静默**。
     *
     * 写入时机：**在动系统设置之前**写目标组件；切换流程跑完（无论成功失败）立刻清除。
     * 因此"下次打开时标记还在" = 上一次切换过程中进程被系统结束了。
     *
     * @param componentClassName 目标组件的全类名；传 null 表示清除标记。
     */
    suspend fun setIconSwitchPending(componentClassName: String?) {
        context.appearanceSettingsDataStore.edit { prefs ->
            if (componentClassName == null) {
                prefs.remove(keyIconSwitchPending)
            } else {
                prefs[keyIconSwitchPending] = componentClassName
            }
        }
    }

    /** 读取进行中标记；没有（正常情况下）返回 null。**只用于诊断提示，不参与任何判定。** */
    suspend fun iconSwitchPending(): String? =
        context.appearanceSettingsDataStore.data.first()[keyIconSwitchPending]

    /**
     * 记录用户**成功切换**到的那个组合格子（见 [keyIconPresetChoiceCell]）。
     *
     * 只在切换**成功之后**写：切换失败时当前图标并没有变，把它记成"用户选了这一格"，下一次更新后的
     * 自救援就会把用户带到一个他从没生效过的组合上。传 null 表示清除。
     *
     * 值格式 `i,j`（两个非负整数，`,` 分隔）。这个格式**只在本类里读写**，调用方拿到的是一对下标；
     * 解析时任何一处不对都当作"没有记录"（见 [iconPresetChoiceCell]），不抛异常、不猜。
     */
    suspend fun setIconPresetChoiceCell(cell: Pair<Int, Int>?) {
        context.appearanceSettingsDataStore.edit { prefs ->
            if (cell == null) {
                prefs.remove(keyIconPresetChoiceCell)
            } else {
                prefs[keyIconPresetChoiceCell] = "${cell.first},${cell.second}"
            }
        }
    }

    /**
     * 读用户上次成功切换到的组合格子。**只用于更新后的自救援**（见 [keyIconPresetChoiceCell]）。
     *
     * 缺键（老安装从没写过）、段数不是 2、有非数字、有负数 —— 一律返回 null = "不知道用户选过什么"，
     * 由调用方回落到默认入口。这正是**合理降级**：宁可恢复成默认图标，也不能凭一个坏值去启用
     * 一个不存在的组合。
     */
    suspend fun iconPresetChoiceCell(): Pair<Int, Int>? {
        val raw = context.appearanceSettingsDataStore.data.first()[keyIconPresetChoiceCell] ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val iconIndex = parts[0].trim().toIntOrNull() ?: return null
        val nameIndex = parts[1].trim().toIntOrNull() ?: return null
        if (iconIndex < 0 || nameIndex < 0) return null
        return iconIndex to nameIndex
    }

    suspend fun current(): AppearanceSettings = flow.first()

    suspend fun setName(name: String) {
        val sanitized = sanitizeName(name)
        context.appearanceSettingsDataStore.edit { it[keyName] = sanitized }
    }

    /** 保存裁剪结果：写进应用私有目录，并只把**文件名**存进偏好（避免路径随安装变化而失效）。 */
    suspend fun saveIcon(bitmap: android.graphics.Bitmap) {
        val fileName = ICON_FILE_NAME
        // 这里不再包 runCatching{}.getOrElse{ throw it }：那是个空包装，异常原样抛出，
        // 而调用方（AppearanceViewModel）本来就用 runCatching 把失败渲染成提示。
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        context.appearanceSettingsDataStore.edit { it[keyIcon] = fileName }
    }

    /**
     * 恢复默认：删掉自定义名称、图标、主题色与背景图（含背景图的明暗/透明度）。
     *
     * ★ 必须返回**重置后**的完整设置：只清掉这几个键，导航样式、夜间模式、**列表排序**、卡片外观与
     *   玻璃参数都还留在盘上并继续生效。调用方若自行拼一份"默认 UiState"，页面显示的就是
     *   跟随系统 / Dock / 100% —— 与实际渲染不是一回事，用户接着拖滑块就是在错误基线上写回。
     *   「数据过期提示」同理**不重置**：它不是外观，而是"我怎么看监控数据"的口径
     *   （见 [keyFreshnessExpiryHint]，reset() 不列它就不会被清掉）。
     */
    suspend fun reset(): AppearanceSettings {
        runCatching { iconFile(ICON_FILE_NAME).delete() }
        runCatching { backgroundFile().delete() }
        context.appearanceSettingsDataStore.edit {
            it.remove(keyName)
            it.remove(keyIcon)
            it.remove(keyThemeColor)
            it.remove(keyBackground)
            it.remove(keyBackgroundDim)
            it.remove(keyBackgroundAlpha)
            // 背景显示区域属于"背景图"这一组，跟着一起清掉：图都没了，区域参数留着只会在
            // 下次设置背景图时莫名其妙地生效（一块随机位置）。
            it.remove(keyBackgroundCrop)
            // 导航样式**不重置**：它属于"用起来顺手"的选择，不该被"恢复默认外观"顺手改掉；
            // 列表排序同理（用户挑的是看列表的方式，与名称/图标/背景/主题色无关）。
        }
        return current()
    }

    // ---- 主题色（用户要求：调色板 / 吸管 / 颜色代码）----

    /** @param argb 颜色值；传 null 表示恢复默认主题色 */
    suspend fun setThemeColor(argb: Int?) {
        context.appearanceSettingsDataStore.edit { prefs ->
            if (argb == null) prefs.remove(keyThemeColor) else prefs[keyThemeColor] = ColorHex.format(argb)
        }
    }

    // ---- 背景图片（用户要求：可调明暗与透明度）----

    suspend fun saveBackground(bitmap: android.graphics.Bitmap) {
        context.openFileOutput(BACKGROUND_FILE_NAME, Context.MODE_PRIVATE).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        context.appearanceSettingsDataStore.edit {
            it[keyBackground] = BACKGROUND_FILE_NAME
            // ★ 必须在**每次**换图时写一个会变的值：
            //   文件名每次都相同，若只写文件名，AppearanceSettings 新旧值 equals 相等，
            //   collectAsStateWithLifecycle 不会触发重组，界面就永远用第一次那张图
            //   —— 表现为"已存在背景图时更换不生效，但移除后重新设置才生效"（用户反馈）。
            it[keyBackgroundRevision] = System.currentTimeMillis().toString()
            // 换图就作废旧区域：归一化矩形是**相对上一张图**选的，原样留给新图等于在用户
            // 没看过的新图上随手切一块（竖向图与横向图更是完全对不上）。新图默认居中铺满。
            it.remove(keyBackgroundCrop)
        }
    }

    suspend fun removeBackground() {
        runCatching { backgroundFile().delete() }
        context.appearanceSettingsDataStore.edit {
            it.remove(keyBackground)
            it.remove(keyBackgroundCrop)
        }
    }

    /**
     * 保存背景图的显示区域；传 null 表示"恢复默认：整张图居中铺满"。
     *
     * ★ **只写这一条参数，完全不碰图片文件**（用户要求：可反复调整取景）。
     *   对比"每次确定都把裁好的图写回文件"的做法：那样每调一次就重新编码一次 JPEG，
     *   画质会一次次掉（JPEG 是有损的、每次都会再丢一点），而且原始取景再也回不去；
     *   存参数则一次编码都不增加，随时能重新框、重新恢复默认。
     *
     * 非法值（越界、NaN、细成一条线）一律当作"没设置"删掉键 —— 宁可回到默认的居中铺满，
     * 也不要让渲染侧拿到一个会让整块背景变形的矩形。
     */
    suspend fun setBackgroundCrop(crop: BackgroundCrop?) {
        context.appearanceSettingsDataStore.edit { prefs ->
            if (crop == null || !crop.isValid) prefs.remove(keyBackgroundCrop)
            else prefs[keyBackgroundCrop] = crop.format()
        }
    }

    /** @param dim 0 = 原图，1 = 全黑（压暗）；@param alpha 图片本身的不透明度 */
    suspend fun setBackgroundAdjust(dim: Float, alpha: Float) {
        context.appearanceSettingsDataStore.edit {
            it[keyBackgroundDim] = dim.coerceIn(0f, 1f).toString()
            it[keyBackgroundAlpha] = alpha.coerceIn(0.1f, 1f).toString()
        }
    }

    fun backgroundFile(name: String = BACKGROUND_FILE_NAME): File = File(context.filesDir, name)

    fun loadBackgroundBitmap(): android.graphics.Bitmap? = runCatching {
        if (!backgroundFile().exists()) null
        else android.graphics.BitmapFactory.decodeFile(backgroundFile().absolutePath)
    }.getOrNull()

    /**
     * 按**最长边** [maxPx] 降采样解码背景图，供"框选显示区域"的编辑器预览用。
     *
     * 两步都不能省（与 MainActivity 里那份解码同理）：`inJustDecodeBounds` 只读文件头拿原始宽高、
     * 不分配像素内存，据此算出的 `inSampleSize` 才会在真正解码时生效。少了它，4000×3000 的照片
     * 会被按原尺寸解成 ≈48MB 的 ARGB_8888 位图 —— 而它还要每帧参与拖动重绘
     * （本项目此前就有"大图拖动卡顿"的反馈）。
     *
     * 只缩不放（`sample` 最小 1），也**不写回文件**：屏幕上框出来的最终结果仍然由渲染侧
     * 按**归一化**区域参数从原文件取，编辑器用缩小图不会让最终背景变糊。
     */
    fun loadBackgroundBitmapSampled(maxPx: Int): android.graphics.Bitmap? = runCatching {
        val file = backgroundFile()
        if (!file.exists() || maxPx <= 0) return@runCatching null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        // 只要"再降一半仍不小于目标"就继续降：保证长边不小于 maxPx、也不超过它的两倍，
        // 既不小于目标（免得编辑器里糊）、也不浪费内存。
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    /** 已保存的自定义图标文件；没有则返回 null。 */
    fun iconFile(name: String = ICON_FILE_NAME): File = File(context.filesDir, name)

    fun loadIconBitmap(): android.graphics.Bitmap? = runCatching {
        if (!iconFile().exists()) null
        else android.graphics.BitmapFactory.decodeFile(iconFile().absolutePath)
    }.getOrNull()

    companion object {
        const val DEFAULT_NAME = "主播监控"
        const val ICON_FILE_NAME = "custom_launcher_icon.png"
        const val BACKGROUND_FILE_NAME = "custom_background.jpg"
        const val MAX_NAME_LENGTH = 16
        const val DEFAULT_DIM = 0.35f
        const val DEFAULT_ALPHA = 1f
        /** 卡片透明度/明暗默认值 = 原样（不透明、不压暗） */
        const val DEFAULT_CARD_DIM = 0f
        const val DEFAULT_CARD_ALPHA = 1f
        /** 液态玻璃默认参数：模糊 28dp、体色 0.28、高光 0.45、亮边 0.55/1dp（即此前的固定值） */
        const val DEFAULT_GLASS_BLUR = 28f
        const val DEFAULT_GLASS_TINT = 0.28f
        const val DEFAULT_GLASS_HIGHLIGHT = 0.45f
        const val DEFAULT_GLASS_RIM_ALPHA = 0.55f
        const val DEFAULT_GLASS_RIM_WIDTH = 1f

        /**
         * 「开启数据过期提示」的默认值：**开**（用户要求默认开启）。
         *
         * 读不到键时必须落到这里：老安装（升级上来没有这个键）与首次安装都走这条路径，
         * 因此"默认开"对老用户同样成立，不需要迁移代码。
         */
        const val DEFAULT_FRESHNESS_EXPIRY_HINT = true

        /** 名称规整：去换行与首尾空白、限长；空串回落到默认名（不允许出现"没有名字"的应用）。 */
        fun sanitizeName(raw: String): String =
            raw.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME_LENGTH)
                .ifBlank { DEFAULT_NAME }
    }
}

/**
 * 背景图的**显示区域**：归一化矩形（0~1，相对原图的宽与高）。
 *
 * 为什么存归一化比例而不是像素：同一个设置会作用在**两张尺寸不同的位图**上 ——
 *  - 渲染侧：MainActivity 按屏幕尺寸（还会按区域放大幅度）降采样解码同一个文件；
 *  - 编辑器：为了拖动流畅，另按最长边降采样解出一张更小的预览图。
 * 存比例的话两边算出来是同一块区域（差不到一个像素），存像素就会各算各的、
 * "框选时看到的"和"最终背景"对不上。
 *
 * 定义域：`0 <= left < right <= 1`、`0 <= top < bottom <= 1`。左上角为原点，与
 * `Bitmap.createBitmap` / `drawImage(srcOffset)` 一致（都是左上原点）。
 */
data class BackgroundCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * 是否是一个**能用**的区域：四个数都是有限值、落在图内、且长宽都不小于 [MIN_SIZE]。
     *
     * 这在读取处（[AppearanceSettingsRepository.flow]）就挡住了坏数据：手工改过盘、
     * 或未来某次写入被打断留下的"零宽矩形"会让渲染退化成整块背景被拉伸，属于必须拦住的输入。
     */
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            width >= MIN_SIZE && height >= MIN_SIZE

    /** 存储格式：`left,top,right,bottom`（`Float.toString()` 恒定用 `.` 作小数点，不受语言环境影响）。 */
    fun format(): String = "$left,$top,$right,$bottom"

    companion object {
        /**
         * 区域的最小边长（归一化）。
         *
         * 比这更小的区域铺满整屏就是一帧糊掉的马赛克，没有使用意义；同时它也挡住了
         * "手工改盘写成 0,0,0.0001,0.0001"这类会让渲染退化的值（[isValid] 里判的就是它）。
         * 取值 0.02 ≈ 原图短边的 1/50，比编辑器允许的最大放大倍数（8 倍）还宽松，不会误伤正常操作。
         */
        const val MIN_SIZE = 0.02f

        /**
         * 解析存储值；**任何一处不对就返回 null**（= 用默认的整张图居中铺满）：
         * 键不存在、段数不是 4、有非数字、有 NaN/Infinity、越界、细成一条线。
         * 一律"当作没设置过"，而不是抛异常或钳到边界 —— 缺键/坏值都必须是能正常显示的默认行为。
         */
        fun parse(raw: String?): BackgroundCrop? {
            val parts = raw?.split(',') ?: return null
            if (parts.size != 4) return null
            val values = parts.map { it.trim().toFloatOrNull() ?: return null }
            return BackgroundCrop(values[0], values[1], values[2], values[3]).takeIf { it.isValid }
        }
    }
}

/**
 * 颜色代码的解析与格式化（纯函数，便于单测）。
 *
 * 用户要求"用 16 位的颜色代码"——这里按业界常见的十六进制写法实现，
 * 并**同时接受三种长度**，因为用户手上的色值来源五花八门：
 *  - `#RGB`（12 位简写，如 `#F09`）
 *  - `#RRGGBB`（24 位，最常用，如 `#FB7299`）
 *  - `#AARRGGBB`（32 位带透明度，如 `#80FB7299`）
 *
 * 井号可省略、大小写不敏感；**空串返回 null 表示"用默认主题色"**，
 * 解析失败也返回 null —— 由界面区分"没填"和"填错了"（调用方看 [isValid]）。
 */
object ColorHex {

    /** @return ARGB 值；无法解析或为空时返回 null */
    fun parse(raw: String?): Int? {
        val text = raw?.trim()?.removePrefix("#")?.removePrefix("0x")?.removePrefix("0X") ?: return null
        if (text.isEmpty()) return null
        if (!text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (text.length) {
            3 -> {
                // #RGB → #RRGGBB（每位重复一次）
                val r = text[0].digitToInt(16) * 17
                val g = text[1].digitToInt(16) * 17
                val b = text[2].digitToInt(16) * 17
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> (0xFF shl 24) or text.toInt(16)
            8 -> text.toLong(16).toInt() // 已含 alpha
            else -> null
        }
    }

    /** 输入是否"看起来是合法的颜色代码"（供界面提示用；空串视为合法 = 用默认色）。 */
    fun isValid(raw: String?): Boolean = raw.isNullOrBlank() || parse(raw) != null

    fun format(argb: Int): String = "#%08X".format(argb)
}

/** 底部导航样式（用户要求：可选普通 / Dock，默认 Dock）。 */
enum class NavigationStyle(val label: String, val description: String) {
    DOCK("Dock 栏", "悬浮圆角栏，与屏幕边缘留出间距，不占满整行"),
    NORMAL("普通样式", "贴底的整条导航栏，Material 默认观感")
}

/**
 * 夜间模式（用户要求：可跟随系统，**默认跟随**）。
 *
 * 做成三态而不是一个布尔：开关只能表达"跟不跟随"，不跟随之后还要能选浅色还是深色 ——
 * 只给布尔的话，用户关掉跟随就只剩"强制浅色"一种可能，"夜间模式"这四个字落不了地。
 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("始终浅色"),
    DARK("始终深色")
}

/** 当前外观设置；[iconFile] / [backgroundFile] 非空表示用户自定义过。 */
data class AppearanceSettings(
    val name: String = AppearanceSettingsRepository.DEFAULT_NAME,
    val iconFile: File? = null,
    /** 自定义主题色（ARGB）；null = 用默认的 B 站粉 */
    val themeColor: Int? = null,
    val backgroundFile: File? = null,
    /** 背景图版本号（每次换图递增），用于触发重组；与 [backgroundFile] 一起决定何时重新解码。 */
    val backgroundRevision: Long = 0L,
    /**
     * 背景图的显示区域（归一化，见 [BackgroundCrop]）。
     *
     * null = 用户没框选过 → 整张图 `ContentScale.Crop` 居中铺满（**默认，也是本功能上线前的行为**）。
     * 老安装读不到这个键时就是 null，因此升级上来看到的背景与升级前一模一样。
     */
    val backgroundCrop: BackgroundCrop? = null,
    /** 背景压暗程度 0~1（1 = 全黑） */
    val backgroundDim: Float = AppearanceSettingsRepository.DEFAULT_DIM,
    /** 背景图片不透明度 0.1~1 */
    val backgroundAlpha: Float = AppearanceSettingsRepository.DEFAULT_ALPHA,
    /** 底部导航样式，默认 Dock */
    val navigationStyle: NavigationStyle = NavigationStyle.DOCK,
    /** Dock 栏是否使用液态玻璃效果（默认关，用户自行开启） */
    val glassDock: Boolean = false,
    /** 夜间模式，**默认跟随系统** */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 卡片压暗程度 0~0.9（0 = 原样）；用户要求：卡片本身可调明暗 */
    val cardDim: Float = AppearanceSettingsRepository.DEFAULT_CARD_DIM,
    /** 卡片不透明度 0.3~1（1 = 完全不透明）；用户要求：卡片本身可调透明度 */
    val cardAlpha: Float = AppearanceSettingsRepository.DEFAULT_CARD_ALPHA
,
    /** 液态玻璃是否也应用到卡片（用户要求：与 Dock 同步） */
    val glassCards: Boolean = false,
    /** 玻璃模糊半径（dp） */
    val glassBlur: Float = AppearanceSettingsRepository.DEFAULT_GLASS_BLUR,
    /** 玻璃体色浓度 */
    val glassTint: Float = AppearanceSettingsRepository.DEFAULT_GLASS_TINT,
    /** 顶部高光强度 */
    val glassHighlight: Float = AppearanceSettingsRepository.DEFAULT_GLASS_HIGHLIGHT,
    /** 亮边透明度 */
    val glassRimAlpha: Float = AppearanceSettingsRepository.DEFAULT_GLASS_RIM_ALPHA,
    /** 亮边宽度（dp） */
    val glassRimWidth: Float = AppearanceSettingsRepository.DEFAULT_GLASS_RIM_WIDTH,
    /** 一级列表（直播历史 / 统计）的排序方式（用户要求：四选一，**默认第 3 种**） */
    val sessionSortOrder: SessionSortOrder = SessionSortOrder.DEFAULT,
    /**
     * 是否在首页卡片上标注「· 数据过期」（用户要求：高级设置里可关，**默认开启**）。
     *
     * 关掉只影响这一个标记，**不影响时间行**（"上次检查是多久以前"照常显示），
     * 也不影响监控与落库 —— 判定规则见 `domain.policy.FreshnessPolicy`。
     */
    val freshnessExpiryHintEnabled: Boolean = AppearanceSettingsRepository.DEFAULT_FRESHNESS_EXPIRY_HINT
)

/**
 * 一级列表的排序方式（用户原话：做成选项，一个是按下播时间排序，一个是按开播时间排序，
 * 还有一个是依旧按下播时间排序但**默认置顶直播中的主播**；第 4 个是"按下播时间排序，
 * 正在直播的按最近一场有下播时间的记录来排"；默认用第 3 个）。
 *
 * 四个选项的差别只在"未下播（正在直播）的主播怎么放"，这不是排版洁癖，而是三种需求：
 *  - [END_TIME]：看"最近结束了什么"，没下播的没有下播时间可排 → 一律排到最后；
 *  - [START_TIME]：看"最近开播了什么"（H13 时用户明确要求过的口径，保留为一个选项而不是删掉，
 *    因为那条要求并没有被撤销，只是不再是默认）；
 *  - [END_TIME_LIVE_FIRST]（默认）：看"现在谁在播 + 最近结束了什么"，在播的固定在最上面；
 *  - [END_TIME_LIVE_LAST_CLOSED]：同样按下播时间排，但**在播的不置顶也不沉底**，改用
 *    "这个主播最近一次真正结束直播是什么时候"（最近一场有下播时间的场次）给它定位。
 *
 * ★ 新值追加在**末尾**而不是插在第 3 项前面：下拉直接渲染 `entries`，插进去会挪动默认项在
 *   菜单里的位置（用户记住的是"默认在第 3 行"）；追加还顺带保证已有三项的 `ordinal` 不变。
 *   DataStore 存的是 `name` 而不是序号，因此追加对已存档的用户取值没有任何影响。
 *
 * 枚举定义在仓库这一侧（与 [NavigationStyle] / [ThemeMode] 同一处）：它既是持久化的取值
 * （DataStore 里存的是 `name`），也是两个页面共用的口径，放在谁家页面里都会诱导另一页再抄一份。
 */
enum class SessionSortOrder(val label: String, val description: String) {
    // ★ 枚举顺序 = 下拉里的显示顺序（用户要求：把原第 3 项提到第 1 位）。
    //   重排对**已保存的设置是安全的**：DataStore 里存的是 name 而不是 ordinal
    //   （写入侧 `order.name`、读取侧 `valueOf(name)`），老用户的选择不会因重排而漂移。
    //   文案统一为「维度 · 直播中的处理」+ 一句说明；不用括号（用户明确嫌括号吵）。
    END_TIME_LIVE_FIRST(
        "下播时间 · 直播中置顶",
        "直播中的主播排在最前，其余按最近下播时间从新到旧；这是默认排序"
    ),
    END_TIME_LIVE_LAST_CLOSED(
        "下播时间 · 直播中按上一场",
        "全部按最近下播时间排序；直播中的用他上一场的下播时间参与排序，没结束过直播的排在最后"
    ),
    END_TIME(
        "下播时间 · 直播中排最后",
        "按最近下播时间从新到旧；直播中的还没有下播时间，因此排在最后"
    ),
    START_TIME(
        "开播时间",
        "按最近开播时间从新到旧"
    );

    companion object {
        /**
         * 默认值 = 下拉里的第 1 项（用户定稿：把它提到最前，选项值本身没变）。
         *
         * 读不到键时必须落到这里：老安装（升级上来没有这个键）与首次安装都走这条路径，
         * 因此"默认项"对老用户同样成立，不需要迁移代码。
         */
        val DEFAULT: SessionSortOrder = END_TIME_LIVE_FIRST
    }
}