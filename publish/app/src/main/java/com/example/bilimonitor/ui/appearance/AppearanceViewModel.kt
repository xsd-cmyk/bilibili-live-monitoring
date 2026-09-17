package com.example.bilimonitor.ui.appearance

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.repository.AppearanceSettingsRepository
import com.example.bilimonitor.data.repository.BackgroundCrop
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appearanceRepository: AppearanceSettingsRepository
) : ViewModel() {

    data class UiState(
        val name: String = AppearanceSettingsRepository.DEFAULT_NAME,
        val savedName: String = AppearanceSettingsRepository.DEFAULT_NAME,
        val iconBitmap: Bitmap? = null,
        /** 正在裁剪的原图（选了图片但还没保存裁剪结果）。 */
        val picking: Bitmap? = null,
        val busy: Boolean = false,
        val message: String? = null,
        /** 主题色（ARGB）；null = 默认 */
        val themeColor: Int? = null,
        /** 颜色代码输入框的当前文本（与 [themeColor] 分开：用户可能正在输入非法值） */
        val colorText: String = "",
        val backgroundBitmap: Bitmap? = null,
        /**
         * 背景图的**显示区域**（归一化矩形）；null = 整张图居中铺满（默认，也是本功能上线前的行为）。
         *
         * 与 DataStore 里存的那个键同源：界面按它画预览、渲染侧按它取区域。
         */
        val backgroundCrop: BackgroundCrop? = null,
        /**
         * 正在框选显示区域时用的**降采样预览图**（null = 没在框选）。
         *
         * 与 [backgroundBitmap] 分开是有意的：那张是原尺寸（吸管取色要按它取真实像素），
         * 这张只为拖动流畅而缩小，不进 DataStore、也不参与任何持久化。
         */
        val regionEditing: Bitmap? = null,
        val backgroundDim: Float = AppearanceSettingsRepository.DEFAULT_DIM,
        val backgroundAlpha: Float = AppearanceSettingsRepository.DEFAULT_ALPHA,
        /** 吸管模式：点击背景图取色 */
        val eyedropper: Boolean = false,
        /** 底部导航样式（默认 Dock） */
        val navigationStyle: com.example.bilimonitor.data.repository.NavigationStyle =
            com.example.bilimonitor.data.repository.NavigationStyle.DOCK,
        /** Dock 栏液态玻璃效果（可选，默认关） */
        val glassDock: Boolean = false,
        /** 夜间模式（默认跟随系统） */
        val themeMode: com.example.bilimonitor.data.repository.ThemeMode =
            com.example.bilimonitor.data.repository.ThemeMode.SYSTEM,
        /** 卡片压暗 0~0.9（用户要求：卡片可调明暗） */
        val cardDim: Float = com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_CARD_DIM,
        /** 卡片不透明度 0.3~1（用户要求：卡片可调透明度） */
        val cardAlpha: Float = com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_CARD_ALPHA,
        /** 液态玻璃是否也应用到卡片（用户要求：与 Dock 同步） */
        val glassCards: Boolean = false,
        val glassBlur: Float = com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_GLASS_BLUR,
        val glassTint: Float = com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_GLASS_TINT,
        val glassHighlight: Float = com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_GLASS_HIGHLIGHT,
        val glassRimAlpha: Float = com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_GLASS_RIM_ALPHA,
        val glassRimWidth: Float = com.example.bilimonitor.data.repository.AppearanceSettingsRepository.DEFAULT_GLASS_RIM_WIDTH
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = appearanceRepository.current()
            _state.value = _state.value.copy(
                name = current.name,
                savedName = current.name,
                iconBitmap = withContext(Dispatchers.IO) { appearanceRepository.loadIconBitmap() },
                themeColor = current.themeColor,
                colorText = current.themeColor?.let { com.example.bilimonitor.data.repository.ColorHex.format(it) }
                    ?: "",
                backgroundBitmap = withContext(Dispatchers.IO) { appearanceRepository.loadBackgroundBitmap() },
                backgroundCrop = current.backgroundCrop,
                backgroundDim = current.backgroundDim,
                backgroundAlpha = current.backgroundAlpha,
                navigationStyle = current.navigationStyle,
                glassDock = current.glassDock,
                themeMode = current.themeMode,
                cardDim = current.cardDim,
                cardAlpha = current.cardAlpha,
                glassCards = current.glassCards,
                glassBlur = current.glassBlur,
                glassTint = current.glassTint,
                glassHighlight = current.glassHighlight,
                glassRimAlpha = current.glassRimAlpha,
                glassRimWidth = current.glassRimWidth
            )
        }
    }

    /** 卡片玻璃开关（用户要求：把 Dock 的玻璃效果同步应用到所有卡片）。 */
    fun setGlassCards(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { appearanceRepository.setGlassParams(
                cards = enabled,
                blur = _state.value.glassBlur,
                tint = _state.value.glassTint,
                highlight = _state.value.glassHighlight,
                rimAlpha = _state.value.glassRimAlpha,
                rimWidth = _state.value.glassRimWidth
            ) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        glassCards = enabled,
                        message = if (enabled) "玻璃效果已同步到卡片" else "已关闭卡片玻璃效果"
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "设置失败：${it.message}") }
        }
    }

    /** 拖动玻璃参数：只改内存，松手才落库（与其它滑块同一策略）。 */
    fun previewGlassParams(
        blur: Float = _state.value.glassBlur,
        tint: Float = _state.value.glassTint,
        highlight: Float = _state.value.glassHighlight,
        rimAlpha: Float = _state.value.glassRimAlpha,
        rimWidth: Float = _state.value.glassRimWidth
    ) {
        _state.value = _state.value.copy(
            glassBlur = blur, glassTint = tint, glassHighlight = highlight,
            glassRimAlpha = rimAlpha, glassRimWidth = rimWidth
        )
    }

    fun commitGlassParams() {
        val s = _state.value
        viewModelScope.launch {
            runCatching {
                appearanceRepository.setGlassParams(
                    cards = s.glassCards, blur = s.glassBlur, tint = s.glassTint,
                    highlight = s.glassHighlight, rimAlpha = s.glassRimAlpha, rimWidth = s.glassRimWidth
                )
            }.onFailure { _state.value = _state.value.copy(message = "保存玻璃参数失败：${it.message}") }
        }
    }

    /** 卡片外观：拖动时只改内存，松手才落库（与背景图滑块同一策略）。 */
    fun previewCardAppearance(dim: Float, alpha: Float) {
        _state.value = _state.value.copy(cardDim = dim, cardAlpha = alpha)
    }

    fun commitCardAppearance() {
        val s = _state.value
        viewModelScope.launch {
            runCatching { appearanceRepository.setCardAppearance(s.cardDim, s.cardAlpha) }
                .onFailure { _state.value = _state.value.copy(message = "保存卡片外观失败：${it.message}") }
        }
    }

    /** 夜间模式（跟随系统 / 始终浅色 / 始终深色）。 */
    fun setThemeMode(mode: com.example.bilimonitor.data.repository.ThemeMode) {
        viewModelScope.launch {
            runCatching { appearanceRepository.setThemeMode(mode) }
                .onSuccess { _state.value = _state.value.copy(themeMode = mode, message = "夜间模式：${mode.label}") }
                .onFailure { _state.value = _state.value.copy(message = "设置失败：${it.message}") }
        }
    }

    /** 开启/关闭 Dock 栏的液态玻璃效果。 */
    fun setGlassDock(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { appearanceRepository.setGlassDock(enabled) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        // 两个开关独立（用户要求）：只改 Dock 玻璃，不碰卡片玻璃。
                        // 原本这里会与仓储层同一个事务地把 glassCards 置 false，等于"关 Dock"静默
                        // 改掉卡片的设置，而界面上已没有任何说明。
                        glassDock = enabled,
                        message = if (enabled) "已开启液态玻璃效果" else "已关闭液态玻璃效果"
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "设置失败：${it.message}") }
        }
    }

    /** 切换底部导航样式（用户要求：普通 / Dock，默认 Dock）。 */
    fun setNavigationStyle(style: com.example.bilimonitor.data.repository.NavigationStyle) {
        viewModelScope.launch {
            runCatching { appearanceRepository.setNavigationStyle(style) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        navigationStyle = style,
                        message = "底部导航已切换为「${style.label}」"
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "切换失败：${it.message}") }
        }
    }

    // ---- 主题色 ----

    fun onColorTextChanged(value: String) {
        // 输入过程中允许非法值：只有点"应用"时才校验，否则用户打字打到一半就被红字打断
        _state.value = _state.value.copy(colorText = value)
    }

    /** 直接选色（调色板 / 吸管）：立即生效并同步输入框。 */
    fun applyColor(argb: Int) {
        viewModelScope.launch {
            runCatching { appearanceRepository.setThemeColor(argb) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        themeColor = argb,
                        colorText = com.example.bilimonitor.data.repository.ColorHex.format(argb)
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "保存主题色失败：${it.message}") }
        }
    }

    /** 应用输入框里的颜色代码；空串 = 恢复默认主题色。 */
    fun applyColorText() {
        val text = _state.value.colorText
        if (!com.example.bilimonitor.data.repository.ColorHex.isValid(text)) {
            _state.value = _state.value.copy(message = "颜色代码格式不对，支持 #RGB / #RRGGBB / #AARRGGBB")
            return
        }
        val argb = com.example.bilimonitor.data.repository.ColorHex.parse(text)
        viewModelScope.launch {
            runCatching { appearanceRepository.setThemeColor(argb) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        themeColor = argb,
                        message = if (argb == null) "已恢复默认主题色" else "主题色已应用"
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "保存主题色失败：${it.message}") }
        }
    }

    /**
     * 吸管：从**背景图片**上取色。
     *
     * [x] / [y] 是**原图**上的归一化坐标（0~1），换算由界面负责：
     * 预览用的是 `ContentScale.Crop`，点击比例不能直接乘原图宽高（那等于假设 Fit），
     * 必须先按 Crop 的 scale/origin 反算 —— 见 `AppearanceScreen` 里的取色热区。
     *
     * 说明：真正"吸屏幕任意位置"的颜色需要无障碍服务的 `takeScreenshot`（API 30+）或
     * MediaProjection，前者要求用户额外开启无障碍、后者要弹录屏授权 —— 为了取个色弹录屏框，
     * 打扰大于收益。从自己设置的背景图取色既不需要任何权限，也是这个功能最常见的用法
     * （"主色跟着壁纸走"）。界面上如实写明取色来源。
     */
    fun pickColorFromBackground(x: Float, y: Float) {
        val bitmap = _state.value.backgroundBitmap ?: return
        val px = (x * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val py = (y * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val argb = runCatching { bitmap.getPixel(px, py) }.getOrNull() ?: return
        applyColor(argb)
        _state.value = _state.value.copy(eyedropper = false, message = "已从背景图取色 ${com.example.bilimonitor.data.repository.ColorHex.format(argb)}")
    }

    fun setEyedropper(on: Boolean) {
        _state.value = _state.value.copy(
            eyedropper = on,
            message = if (on) "点击下方背景图任意位置取色" else null
        )
    }

    // ---- 背景图 ----

    fun pickBackground(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(uri, MAX_BACKGROUND_PX) }
            if (bitmap == null) {
                _state.value = _state.value.copy(busy = false, message = "无法读取这张图片，请换一张试试")
                return@launch
            }
            // 换图会作废旧显示区域（仓储层在同一个事务里删键）：归一化矩形是相对**上一张图**
            // 选的，留给新图等于在用户没看过的新图上随手切一块。这里如实告诉用户，
            // 否则他会以为"框选没生效"。
            val hadCrop = _state.value.backgroundCrop != null
            runCatching { appearanceRepository.saveBackground(bitmap) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false,
                        backgroundBitmap = bitmap,
                        backgroundCrop = null,
                        message = if (hadCrop) {
                            "背景图已设置；显示区域已恢复为整张图居中铺满，可点「选择显示区域」重新框选"
                        } else {
                            "背景图已设置"
                        }
                    )
                }
                .onFailure { _state.value = _state.value.copy(busy = false, message = "保存背景图失败：${it.message}") }
        }
    }

    fun removeBackground() {
        viewModelScope.launch {
            runCatching { appearanceRepository.removeBackground() }
                .onSuccess {
                    _state.value = _state.value.copy(
                        backgroundBitmap = null,
                        backgroundCrop = null,
                        regionEditing = null,
                        message = "已移除背景图"
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "移除失败：${it.message}") }
        }
    }

    // ---- 背景图显示区域（用户要求：自己框选图片的哪一块作为背景）----

    /**
     * 打开"选择显示区域"的框选界面。
     *
     * ★ 这里只为**预览**降采样解码（最长边 [REGION_EDIT_PX]），而不是把原图整张塞进 Compose：
     *   相册里的图动辄 4000×3000 以上，整张参与每帧拖动重绘会明显卡顿（本项目此前就有
     *   "大图拖动卡顿"的反馈）。编辑器里拖的是几何量，落库的是**归一化区域**，
     *   所以预览用缩小图不会影响最终背景的清晰度（渲染侧另有按屏幕尺寸的解码）。
     */
    fun startRegionEdit() {
        if (_state.value.backgroundBitmap == null) {
            // 界面上的按钮在没有背景图时是禁用的，这里只是 VM 自身的兜底
            _state.value = _state.value.copy(message = "请先选择一张背景图片，再框选显示区域")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val sampled = withContext(Dispatchers.IO) {
                appearanceRepository.loadBackgroundBitmapSampled(REGION_EDIT_PX)
            }
            _state.value = _state.value.copy(
                busy = false,
                regionEditing = sampled,
                message = if (sampled == null) "无法读取已保存的背景图，请重新选择一张" else null
            )
        }
    }

    fun cancelRegionEdit() {
        _state.value = _state.value.copy(regionEditing = null)
    }

    /**
     * 保存框选结果（crop = null 表示恢复默认的"整张图居中铺满"）。
     *
     * ★ **不重写图片文件**，只写一条区域参数：反复调整取景不会一次次重新编码 JPEG
     *   （有损编码每写一次都会再掉一点画质），原图始终原样留着，"再次调整"也不需要重新选图。
     *   代价是渲染侧每次都要按参数取区域 —— 那是 `BitmapPainter(srcOffset/srcSize)` 的事，
     *   不复制像素、也不重新编码。
     */
    fun saveRegion(crop: BackgroundCrop?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { appearanceRepository.setBackgroundCrop(crop) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false,
                        regionEditing = null,
                        backgroundCrop = crop,
                        message = if (crop == null) {
                            "已恢复为整张图居中铺满"
                        } else {
                            "背景显示区域已更新（原图未改动，可随时再调整）"
                        }
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(busy = false, message = "保存显示区域失败：${it.message}")
                }
        }
    }

    /** 拖动滑块时只改内存里的值（不写盘），松手时才落库 —— 否则会疯狂写 DataStore。 */
    fun previewBackgroundAdjust(dim: Float, alpha: Float) {
        _state.value = _state.value.copy(backgroundDim = dim, backgroundAlpha = alpha)
    }

    fun commitBackgroundAdjust() {
        val s = _state.value
        viewModelScope.launch {
            runCatching { appearanceRepository.setBackgroundAdjust(s.backgroundDim, s.backgroundAlpha) }
                .onFailure { _state.value = _state.value.copy(message = "保存背景调整失败：${it.message}") }
        }
    }

    fun onNameChanged(value: String) {
        // 在输入处就限长，与界面上的"最多 16 字"一致：否则用户能一路打到 20 个字，
        // 点保存时被 sanitizeName 静默截断 —— 看到的和存下的不是一个东西。
        _state.value = _state.value.copy(
            name = value.take(AppearanceSettingsRepository.MAX_NAME_LENGTH)
        )
    }

    fun saveName() {
        val name = _state.value.name
        viewModelScope.launch {
            runCatching { appearanceRepository.setName(name) }
                .onSuccess {
                    val saved = appearanceRepository.current().name
                    _state.value = _state.value.copy(name = saved, savedName = saved, message = "应用名称已保存为「$saved」")
                }
                .onFailure { _state.value = _state.value.copy(message = "保存失败：${it.message}") }
        }
    }

    /**
     * 用户从系统文件选择器挑了一张图：读进内存准备裁剪。
     *
     * 按目标尺寸采样解码（最长边 [MAX_DECODE_PX]）：手机相册里动辄 4000×3000、几十 MB，
     * 直接全尺寸载入既慢又可能 OOM，而裁剪结果只要 512 级别。
     */
    fun pickImage(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = null)
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(uri) }
            _state.value = _state.value.copy(
                busy = false,
                picking = bitmap,
                message = if (bitmap == null) "无法读取这张图片，请换一张试试" else null
            )
        }
    }

    fun cancelPicking() {
        _state.value = _state.value.copy(picking = null)
    }

    /** 保存裁剪结果（由界面把取景框内的区域裁好传进来）。 */
    fun saveIcon(cropped: Bitmap) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { appearanceRepository.saveIcon(cropped) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        busy = false, picking = null, iconBitmap = cropped,
                        message = "图标已保存，可用「创建桌面快捷方式」把它放到桌面"
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(busy = false, message = "保存图标失败：${it.message}")
                }
        }
    }

    fun reset() {
        viewModelScope.launch {
            runCatching { appearanceRepository.reset() }
                .onSuccess { fresh ->
                    // 每一项都用重置后**真实生效**的设置回填，而不是整表换成 UiState() 的默认值：
                    // 导航样式、夜间模式、卡片外观与玻璃参数都不在重置范围内，它们仍留在盘上生效，
                    // 页面若显示成"跟随系统 / Dock / 100%"，用户接着拖滑块就会把这套错误基线写回去。
                    _state.value = _state.value.copy(
                        name = fresh.name,
                        savedName = fresh.name,
                        iconBitmap = null,
                        themeColor = fresh.themeColor,
                        colorText = fresh.themeColor
                            ?.let { com.example.bilimonitor.data.repository.ColorHex.format(it) } ?: "",
                        backgroundBitmap = null,
                        // 重置会连"显示区域"一起清掉（仓储层把它与背景图当同一组处理），
                        // 这里必须跟着回填：否则界面还显示"已框选自定义区域"、实际渲染已经是整图铺满。
                        backgroundCrop = fresh.backgroundCrop,
                        regionEditing = null,
                        backgroundDim = fresh.backgroundDim,
                        backgroundAlpha = fresh.backgroundAlpha,
                        navigationStyle = fresh.navigationStyle,
                        glassDock = fresh.glassDock,
                        themeMode = fresh.themeMode,
                        cardDim = fresh.cardDim,
                        cardAlpha = fresh.cardAlpha,
                        glassCards = fresh.glassCards,
                        glassBlur = fresh.glassBlur,
                        glassTint = fresh.glassTint,
                        glassHighlight = fresh.glassHighlight,
                        glassRimAlpha = fresh.glassRimAlpha,
                        glassRimWidth = fresh.glassRimWidth,
                        message = "已恢复默认：应用名称、图标、主题色、背景图（含明暗、透明度与显示区域）；" +
                            "导航样式、夜间模式、卡片外观与玻璃参数保持不变"
                    )
                }
                .onFailure { _state.value = _state.value.copy(message = "恢复失败：${it.message}") }
        }
    }

    /**
     * 请求把自定义图标钉到桌面（`requestPinShortcut`）。
     *
     * 这是 Android 上第三方应用唯一能"用自己上传的图片当图标"的官方途径：
     * 系统会弹一个确认框，用户同意后桌面上出现一个用我们提供的 Bitmap 与名称的入口。
     * 应用抽屉里的原始图标不变 —— 界面里必须如实说明，否则用户会以为功能没生效。
     *
     * 结果写进 [UiState.message] 由横幅展示：此前走 `onResult` 回调，而调用点传的是空 lambda
     * （注释写着"结果通过 banner 展示"，实际什么也没做）—— 用户点完按钮看不到任何反馈。
     */
    fun requestPinShortcut() {
        val bitmap = _state.value.iconBitmap
        if (bitmap == null) {
            // 界面上的按钮在没图标时是禁用的，这里只是 VM 自身的兜底：宁可给一句提示，也不静默返回
            _state.value = _state.value.copy(message = "请先选择并裁剪一张图片")
            return
        }
        val shortcuts = runCatching {
            context.getSystemService(android.content.pm.ShortcutManager::class.java)
        }.getOrNull()
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported) {
            _state.value = _state.value.copy(message = "当前桌面不支持固定快捷方式，请换用系统自带桌面后再试")
            return
        }
        val launched = runCatching {
            val intent = Intent(context, com.example.bilimonitor.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val shortcut = android.content.pm.ShortcutInfo.Builder(context, SHORTCUT_ID)
                .setShortLabel(_state.value.savedName)
                .setLongLabel(_state.value.savedName)
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(bitmap))
                .setIntent(intent)
                .build()
            shortcuts.requestPinShortcut(shortcut, null)
        }
        _state.value = _state.value.copy(
            message = if (launched.isSuccess) "已请求添加到桌面，请在系统弹窗中确认"
            else "请求失败：${launched.exceptionOrNull()?.message}"
        )
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun decodeSampled(uri: Uri, maxPx: Int = MAX_DECODE_PX): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    companion object {
        const val SHORTCUT_ID = "bili_monitor_custom_icon"
        private const val MAX_DECODE_PX = 1440

        /** 背景图解码上限：再大也只是当背景，2000px 足够铺满 1080p 屏幕且省内存。 */
        private const val MAX_BACKGROUND_PX = 2000

        /**
         * "框选显示区域"编辑器里预览图的最长边。
         *
         * 编辑器在手机上最多约 0.6 屏高（1080×2400 的机器上 ≈1400px），1600 足够 1:1 显示、
         * 还留出放大后的余量；同时它是远小于原图的（12MP 照片在这一步已经只占几 MB），
         * 拖动时才不会每帧重绘一张超大位图。**只影响预览**，不影响最终背景的清晰度。
         */
        private const val REGION_EDIT_PX = 1600
    }
}