package com.example.bilimonitor.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.MainActivity
import com.example.bilimonitor.R
import com.example.bilimonitor.data.repository.AppearanceSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 桌面图标切换的**全部逻辑**：解析清单、读"现在到底用的是哪一个"、执行切换。
 *
 * 界面（[IconPresetScreen]）只负责画，一行系统调用都不做 —— 这样"当前生效项"只有一个来源。
 *
 * ## 为什么能"换图标"：靠安装包里预先声明的别名
 * 应用桌面图标在 `AndroidManifest` 里是**编译期固定**的，运行期改不了。能做的只有：
 * 在清单里预先声明多个 `<activity-alias>`（各自带 MAIN/LAUNCHER、`targetActivity` 指向
 * `MainActivity`、且 `android:enabled="false"`），运行期用
 * `PackageManager.setComponentEnabledSetting` 在它们之间**启用一个、禁用其余**。
 * 桌面（Launcher）读的是"当前启用的那些 MAIN/LAUNCHER 组件"，于是图标就变了。
 *
 * 本文件与生成脚本之间的契约（两边必须一致）：
 *
 * ## 为什么"图标"与"名字"是两个轴，却要靠一张组合表
 * 平台约束：`<activity-alias>` 的 `android:icon` 与 `android:label` 绑在**同一个组件**上，
 * 运行期没有"只换名字"或"只换图标"的 API。想让两个轴各自独立可选，唯一的办法是
 * 为「图标 i × 名字 j」的**每一个组合**预先声明一个别名（笛卡尔积），运行期只挑一个组合启用。
 * 于是"两个轴"在代码里落成三份数据：两个轴 + 一张"格子 → 组件"的组合表。
 *
 *  - `R.array.icon_axis`：每项 `图标资源名|图标显示名`。**第 0 项是哨兵** —— 资源名是**空字符串**、
 *    显示名是「默认（应用自己的图标）」；i≥1 才是真图标；
 *  - `R.array.name_axis`：每项一个字符串。**第 0 项是哨兵** —— 空字符串，运行时就显示成
 *    「默认（应用自己的名字）」；j≥1 是自定义名字；
 *  - `R.array.preset_alias_matrix`：每项 `<i>|<j>|IconPreset_<i>_<j>`，**只含真的存在别名的格子**
 *    （除 (0,0) 外的所有格子）；第 3 段写简名或全名都认，但**必须**先用 [qualifyComponentName]
 *    补成全名才能交给 `ComponentName`（理由见下）；
 *  - 格子 (0,0)（默认图标 + 默认名字）**没有别名**，它由主入口 `MainActivity` 充当 ——
 *    所以"反查当前格子"与"算出切换目标"这两件事都必须把 `MainActivity` 当成 (0,0)，
 *    见 [DEFAULT_CELL] 与 [IconPresetManifest.cellOf]。
 *
 * ## 唯一事实来源：PackageManager
 * "当前是哪一个"**只**从 [PackageManager.getComponentEnabledSetting] 读，绝不从本应用
 * 自己的偏好里猜。理由很实在：用户可能在系统设置里改过、上一次切换也可能中途被系统结束 ——
 * 只要自己去记"我上次选了谁"，这两种情况下界面都会理直气壮地显示错的。见 [readActivation]。
 *
 * ## 组件名必须自己补包名（本文件唯一一处"少写一行就整个功能失效"的地方）
 * 清单里写的是 `android:name="IconPreset1"`（**没有前导点**）。清单那一侧没问题：
 * 合并/打包阶段会按清单的 `package` 把它补成 `com.example.bilimonitor.IconPreset1`
 * （构建产物 merged_manifest、APK 里的二进制清单、`dumpsys package` 的 Resolver Table
 * 三处都是这个全名）。**但 `ComponentName(packageName, "IconPreset1")` 不会做同样的补全**：
 * 它把类名原样存下来（`getClassName()` 依旧是 `"IconPreset1"`）。认识"相对名"这种写法的是
 * `PackageParser.buildClassName()`，而那只发生在系统解析安装包的时候，运行期没有这一步。
 * 于是 `setComponentEnabledSetting` 落在一个**根本不存在的组件**上：系统要么拒绝这次调用，
 * 要么静默不生效，读回校验必然得到"没启用" —— 表现就是"点切换必失败，而别名其实好好的"。
 * 换算只此一处，见 [qualifyComponentName]。
 */
object IconPresetSwitcher {

    /** 图标轴哨兵（下标 0）的显示名。数组里本来就写着这句，这里只是"整条数组读不出来"时的兜底。 */
    const val DEFAULT_ICON_LABEL = "默认（应用自己的图标）"

    /** 名称轴哨兵（下标 0）的显示名：数组里那一项是**空字符串**，运行时就显示这一句。 */
    const val DEFAULT_NAME_LABEL = "默认（应用自己的名字）"

    /**
     * (0,0) 这一格：默认图标 + 默认名字。
     *
     * 它**没有别名**，由主入口 `MainActivity` 充当（契约如此）。写成一个常量而不是到处现写
     * `0 to 0`：反查、切换目标、零入口兜底这三条路径都必须认同"这一格就是主入口"，
     * 一旦哪一处写成兄弟格子，就会出现"点了默认却启用了某个别名"这种极难查的故障。
     */
    val DEFAULT_CELL: Pair<Int, Int> = 0 to 0

    /** 条目分隔符。图标轴两段、组合表三段，段数各自校验（见 [parseManifest]）。 */
    private const val SEPARATOR = '|'

    /** 图标轴每项：`图标资源名|图标显示名`。 */
    private const val ICON_AXIS_SEGMENTS = 2

    /** 组合表每项：`<i>|<j>|组件名`。 */
    private const val MATRIX_SEGMENTS = 3

    /**
     * 组件名的合法形状：类名的一段，或带包名的全限定名（允许清单里那种前导点写法）。
     *
     * 为什么要在解析阶段就卡这一条：清单是**脚本生成**的，一个多余的空格、一个中文、
     * 或者整条被写坏，都会让 `ComponentName` 指向一个不存在的组件 ——
     * 那种错误在切换时才暴露（而且是"点了没反应"），不如在这里就报出来。
     * 这也顺带挡住把任意字符串拼进组件名的可能。
     *
     * 为什么把"全限定名"也放进来（原先只允许简名，见到 `.` 就判非法）：
     * 数组那一段的职责只是"指出是哪一个组件"，`IconPreset1` / `.IconPreset1` /
     * `com.example.bilimonitor.IconPreset1` 在安装包里是**同一个**组件，都会被
     * [qualifyComponentName] 归一到同一个全名上，判定不会因此变松；反过来，只认简名会让
     * "生成脚本哪天改成写全名"变成"三个预设全部被判成非法条目"这种莫名其妙的故障。
     */
    private val COMPONENT_NAME_PATTERN =
        Regex("""\.?[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")

    /**
     * 把数组里的组件名换算成**安装包里注册的全限定类名**。
     *
     * 三种写法都要认：
     *  · `IconPreset1`                          -> `<packageName>.IconPreset1`（生成脚本现在写的就是这种）
     *  · `.IconPreset1`                         -> `<packageName>.IconPreset1`（清单里 `.MainActivity` 那种写法）
     *  · `com.example.bilimonitor.IconPreset1`  -> 原样（已经是全名）
     *
     * ★ 为什么**必须**有这一步：`ComponentName(pkg, cls)` 只把两个字符串原样装进去，
     *   不会像系统解析安装包那样按包名补全（见文件头的"组件名必须自己补包名"）。
     *   传简名进去得到的是一个不存在的组件，`setComponentEnabledSetting` 与 `getActivityInfo`
     *   全都找不到它 —— 这正是"点切换就报失败、别名其实好好的"的根因。
     */
    fun qualifyComponentName(packageName: String, componentName: String): String = when {
        componentName.startsWith(".") -> packageName + componentName
        componentName.contains('.') -> componentName
        else -> "${packageName}.${componentName}"
    }

    /**
     * 读一个字符串数组资源；读不出来就记一条 missing 并返回 null。
     *
     * 为什么不像以前那样直接 `resources.getStringArray(R.array.xxx)`：现在有**三个**数组，
     * 必须能分别报告是哪一个没读出来 —— 生成脚本只跑了一半、或 release 的资源压缩把某个数组
     * 删掉时，"整页空白"和"我能告诉你少的是哪一份数据"差别极大。
     * 传进来的 [arrayResId] 仍然是编译期常量：数组名写错在编译期就会被发现。
     */
    private fun readStringArray(
        resources: Resources,
        arrayResId: Int,
        arrayName: String,
        missing: MutableList<String>
    ): List<String>? {
        val array = runCatching { resources.getStringArray(arrayResId) }.getOrNull()
        if (array == null) {
            missing += "（${arrayName} 读取失败）"
            return null
        }
        return array.toList()
    }

    /**
     * 把图标资源名解析成资源 id（0 = 两种类型里都没有）。
     *
     * ★ mipmap 与 drawable 在 Android 资源系统里是**两个独立类型**，只查其中一个必然漏掉另一种，
     *   这正是"位图明明打进了 APK 却被报成缺失"的原因。两种都必须试：
     *   · 先 mipmap —— 启动器图标按 Android 惯例就放这里（生成脚本放的是
     *     `res/mipmap-xxx/ic_launcher_preset_N.png`，清单里别名的 android:icon 也写 `@mipmap/...`；
     *     此前这里写死 "drawable"，与那两处都不一致，于是预设图标全被判成 missing）；
     *   · 再回落到 drawable —— 历史遗留或手工放入的图标可能落在 res/drawable/xxx.png，
     *     只认 mipmap 会以同样的方式把它们误报成缺失。
     *
     * 整个查找包 runCatching：`getIdentifier` 在极端情况下会抛（资源表损坏），
     * 而"报缺失"与"整页崩掉"相比，前者才是我们要的降级。
     */
    @Suppress("DiscouragedApi")
    private fun resolveIconResId(resources: Resources, packageName: String, resName: String): Int =
        runCatching {
            val mipmapResId = resources.getIdentifier(resName, "mipmap", packageName)
            if (mipmapResId != 0) {
                mipmapResId
            } else {
                resources.getIdentifier(resName, "drawable", packageName)
            }
        }.getOrDefault(0)

    /**
     * 解析三个数组，拼成"图标轴 + 名称轴 + 组合表"（契约见文件头）。
     *
     * 三种"降级"全都会进 [IconPresetManifest.missing] 并且**不静默**（页面上有一条常驻横幅）：
     * 脚本把清单生成坏了，最典型的表现就是"少了一个图标"或"整条读不出来"，
     * 悄悄跳过会让这种错误永远没人发现。
     *
     * `getIdentifier` 被 lint 标为 DiscouragedApi（比 `R.drawable.xxx` 慢、也躲不过资源压缩），
     * 但图标资源那里**必须**用它：资源名是以字符串形式写在数组里的，编译期根本不存在对应的
     * R 常量 —— 这正是"加图标不用改 Kotlin"的前提（见 [resolveIconResId]）。
     */
    @Suppress("DiscouragedApi")
    fun parseManifest(resources: Resources, packageName: String): IconPresetManifest {
        val missing = mutableListOf<String>()

        // ---------------- 图标轴 ----------------
        val iconEntries = readStringArray(
            resources, R.array.icon_axis, "R.array.icon_axis（图标轴）", missing
        ) ?: emptyList()
        val icons = mutableListOf<IconAxisOption>()
        val iconLabels = mutableMapOf<Int, String>()

        iconEntries.forEachIndexed { index, rawEntry ->
            val entry = rawEntry.trim()
            val parts = entry.split(SEPARATOR).map { it.trim() }

            if (index == 0) {
                // ★ 下标 0 是**哨兵**：契约规定它的资源名就是空字符串，代表"用应用自己的图标"。
                //   所以这里绝不能把它当成"缺少图标资源名"的坏条目 —— 那会把哨兵判成缺失，
                //   界面上就再也没有"回到默认图标"这一格了（它还是切换失败时唯一的退路，
                //   见 applySwitch 的「零入口兜底」）。
                //   连分隔符都没写（`默认（应用自己的图标）`）也认：下标就是身份，语义没有歧义。
                val label = if (parts.size >= ICON_AXIS_SEGMENTS) parts[1] else entry
                if (parts.size >= ICON_AXIS_SEGMENTS && parts[0].isNotEmpty()) {
                    // 与契约不符，但语义仍然是哨兵（下标决定身份）：照常渲染成「默认」，
                    // 同时如实记一条 —— 生成脚本写错了不该没人知道。
                    missing += "（icon_axis 第 0 项理应是哨兵（资源名为空），实际是「${parts[0]}」，已按「默认」处理）"
                }
                val sentinelLabel = label.ifEmpty { DEFAULT_ICON_LABEL }
                iconLabels[0] = sentinelLabel
                icons += IconAxisOption(index = 0, label = sentinelLabel, resId = 0, isDefault = true)
                return@forEachIndexed
            }

            if (parts.size != ICON_AXIS_SEGMENTS) {
                // ★ 变量一律写成 ${...}：中文是合法的 Kotlin 标识符字符，
                //   "$entry（" 这类写法里的全角括号虽然不是字母（此处恰好安全），
                //   但习惯一松，迟早会写出 "$label里" 这种被当成变量 label里 而编译不过的代码。
                missing += "「${entry}」（应为「图标资源名|图标显示名」两段，实际 ${parts.size} 段）"
                return@forEachIndexed
            }

            val resName = parts[0]
            if (resName.isEmpty()) {
                // i≥1 是**真图标**，没有资源名就没有任何东西可画。
                missing += "「${entry}」（缺少图标资源名）"
                return@forEachIndexed
            }

            val resId = resolveIconResId(resources, packageName, resName)
            if (resId == 0) {
                // 资源压缩（release 的 isShrinkResources）看不到 getIdentifier 这种按名查找，
                // 没有被 R.xxx 直接引用的预设图标会被当成无用资源删掉 —— 这里就是它的报警点。
                // 描述里刻意不写 "drawable"：上面 mipmap / drawable 两种类型都已经试过，
                // 再写死类型名只会把排查方向带偏。
                missing += "「${resName}」（图标资源不存在：mipmap 与 drawable 两种类型都查过了）"
                // 图没解析出来，但"这个下标叫什么"仍要记下来：反查当前格子、以及提示文案里
                // 都要能说出是哪一个 —— 只报一个资源名，用户对不上界面上的任何一格。
                iconLabels[index] = parts[1].ifEmpty { resName }
                return@forEachIndexed
            }

            // 没写显示名时用资源名兜底：界面上不允许出现没有名字的格子。
            val label = parts[1].ifEmpty { resName }
            iconLabels[index] = label
            icons += IconAxisOption(index = index, label = label, resId = resId, isDefault = false)
        }

        // ---------------- 名称轴 ----------------
        val nameEntries = readStringArray(
            resources, R.array.name_axis, "R.array.name_axis（名称轴）", missing
        ) ?: emptyList()
        val names = mutableListOf<NameAxisOption>()
        val nameLabels = mutableMapOf<Int, String>()

        nameEntries.forEachIndexed { index, rawEntry ->
            val entry = rawEntry.trim()
            if (index == 0) {
                // ★ 下标 0 是哨兵：契约规定它就是**空字符串**，显示时换成「默认（应用自己的名字）」。
                //   所以空字符串在这里是**正常数据**、不是坏条目（本文件最容易判错的一处）。
                if (entry.isNotEmpty()) {
                    missing += "（name_axis 第 0 项理应是空字符串（哨兵），实际是「${entry}」，已按「默认」处理）"
                }
                nameLabels[0] = DEFAULT_NAME_LABEL
                names += NameAxisOption(index = 0, label = DEFAULT_NAME_LABEL, isDefault = true)
                return@forEachIndexed
            }
            if (entry.isEmpty()) {
                // j≥1 是自定义名字，空字符串没有意义：格子上会是一行空白，读屏也念不出东西。
                missing += "（name_axis 第 ${index} 项是空字符串）"
                return@forEachIndexed
            }
            nameLabels[index] = entry
            names += NameAxisOption(index = index, label = entry, isDefault = false)
        }

        // 数组整条读不出来（或条目全被判坏）时也必须留下哨兵这一格：「默认图标 / 默认名字」
        // 是最终退路，不能因为资源读失败就从界面上消失。
        if (icons.none { it.isDefault }) {
            iconLabels[0] = DEFAULT_ICON_LABEL
            icons.add(0, IconAxisOption(index = 0, label = DEFAULT_ICON_LABEL, resId = 0, isDefault = true))
        }
        if (names.none { it.isDefault }) {
            nameLabels[0] = DEFAULT_NAME_LABEL
            names.add(0, NameAxisOption(index = 0, label = DEFAULT_NAME_LABEL, isDefault = true))
        }

        // ---------------- 组合表 ----------------
        // 两个轴的**有效长度**：下标 0 的哨兵按上面的兜底恒存在，所以至少是 1。
        // 越界判据用它、而不是"渲染出来的格子数"：某个图标资源没解析出来时，它的下标在组合表里
        // 仍然合法（那一格能不能点由 componentFor 决定），不该被误报成越界。
        val iconAxisCount = maxOf(iconEntries.size, 1)
        val nameAxisCount = maxOf(nameEntries.size, 1)

        val aliasByCell = mutableMapOf<Pair<Int, Int>, String>()
        val cellByClassName = mutableMapOf<String, Pair<Int, Int>>()
        val matrixEntries = readStringArray(
            resources, R.array.preset_alias_matrix, "R.array.preset_alias_matrix（图标×名字组合表）", missing
        )
        if (matrixEntries != null) {
            matrixEntries.forEach { rawEntry ->
                val entry = rawEntry.trim()
                if (entry.isEmpty()) {
                    missing += "（组合表里有空条目）"
                    return@forEach
                }

                val parts = entry.split(SEPARATOR).map { it.trim() }
                if (parts.size != MATRIX_SEGMENTS) {
                    missing += "「${entry}」（应为「图标下标|名字下标|组件名」三段，实际 ${parts.size} 段）"
                    return@forEach
                }

                val iconIndex = parts[0].toIntOrNull()
                val nameIndex = parts[1].toIntOrNull()
                if (iconIndex == null || nameIndex == null || iconIndex < 0 || nameIndex < 0) {
                    missing += "「${entry}」（前两段必须是 ≥0 的整数下标）"
                    return@forEach
                }
                if (iconIndex >= iconAxisCount || nameIndex >= nameAxisCount) {
                    // 越界 = 组合表与两个轴不同步（生成脚本只更新了一半的典型形态）。
                    missing += "「${entry}」（下标越界：图标轴 ${iconAxisCount} 项、名称轴 ${nameAxisCount} 项）"
                    return@forEach
                }

                val simpleName = parts[2]
                if (!COMPONENT_NAME_PATTERN.matches(simpleName)) {
                    missing += "「${simpleName}」（组件名不是合法的类名）"
                    return@forEach
                }
                // ★ 在这里就换算成安装包里注册的全名，后面**只用全名**（构造 ComponentName 要用它）。
                //   漏掉这一步就是"切换永远失败"的根因，理由见 [qualifyComponentName]。
                val className = qualifyComponentName(packageName, simpleName)
                val cell = iconIndex to nameIndex
                if (cell == DEFAULT_CELL || className == MainActivity::class.java.name) {
                    // (0,0) 由主入口充当，**没有别名**；组合表里出现它（或任何指向主 Activity 的条目）
                    // 都是与契约不符的数据：照它切换只是把"默认入口"再启用一遍，界面上还会多出一格
                    // 和「默认」抢同一个组件。忽略并说明。
                    // 比较必须用**全限定名**：数组里写 `com.example.bilimonitor.MainActivity` 或
                    // `.MainActivity` 指的是同一个组件，用简名比会漏掉它们。
                    missing += "「${entry}」（指向主入口 MainActivity 的组合没有别名（(0,0) 由它充当），已忽略）"
                    return@forEach
                }
                if (aliasByCell.containsKey(cell)) {
                    missing += "（组合表里 ${iconIndex}×${nameIndex} 这一格出现了多次，已采用最后一条）"
                    // 旧的那条映射要一起撤掉，否则"组件 → 格子"的反查会指回被替换掉的旧组件。
                    aliasByCell[cell]?.let { previous -> cellByClassName.remove(previous) }
                }
                aliasByCell[cell] = className
                cellByClassName[className] = cell
            }

            // 缺格统计（只报一条汇总）：契约说组合表含"除 (0,0) 外的所有格子"，缺格就意味着
            // 某些组合点了没反应 —— 那必须让用户看见，但一格一条会把横幅刷爆。
            val absentCells = iconAxisCount * nameAxisCount - 1 - aliasByCell.size
            if (absentCells > 0) {
                missing += "（有 ${absentCells} 个「图标 × 名字」组合在组合表里没有别名，这些组合暂时无法切换）"
            }
        }

        return IconPresetManifest(
            packageName = packageName,
            mainClassName = MainActivity::class.java.name,
            icons = icons,
            names = names,
            aliasByCell = aliasByCell,
            cellByClassName = cellByClassName,
            iconLabels = iconLabels,
            nameLabels = nameLabels,
            missing = missing
        )
    }

    /**
     * 读"现在生效的是哪一格"、"一共有几个入口开着"。
     *
     * **只问 PackageManager**（见文件头的"唯一事实来源"），绝不从本应用自己的偏好里猜。
     *
     * 三步：
     *  1. 在**已知宇宙**（主入口 + 组合表里的每个别名）里逐个问"你启用了吗"；
     *  2. 再问一次系统"本包此刻有哪些 launcher 入口是活的"（[enabledLauncherClassNames]）。
     *     这一步是**诊断**，不是门禁：少了它，一个"在清单里、却不在组合表里"的入口会让本函数
     *     得出"一个都没启用"的结论 —— 而桌面上明明有图标。那是最坏的一种错：
     *     界面会催用户去"恢复"，而用户其实什么都不缺；
     *  3. 当前生效项：**主入口优先**（与旧行为一致），其次是组合表里能反查到的那一个。
     *     同时启用多个时（异常状态，桌面上会出现多个图标）也只报这一个，
     *     界面另有一条横幅专门说明"同时启用了 N 个"，不需要这里猜一个"更可能的"。
     *
     * @return [IconActivationState.currentCell] 为 null 且 [IconActivationState.currentClassName]
     *         不为 null，表示**反查不到**（既不是主入口、也不在组合表里）—— 界面据此给出明确说明，
     *         而不是把两个轴都留空。
     */
    fun readActivation(context: Context, manifest: IconPresetManifest): IconActivationState {
        val pm = context.packageManager
        val mainEntry = ComponentName(context, MainActivity::class.java)

        val known = (listOf(mainEntry) + manifest.allComponents()).distinct()
        val enabledKnown = known.filter { isComponentEnabled(pm, it) }.map { it.className }

        // 查询失败时返回 null（不是空列表）："系统没告诉我"和"系统说一个都没有"是两回事，
        // 后者会让界面理直气壮地宣布"应用无法从桌面启动"。
        val enabledFromSystem = runCatching {
            enabledLauncherClassNames(pm, context.packageName)
        }.getOrNull()

        val enabled = (enabledKnown + (enabledFromSystem ?: emptyList())).distinct().sorted()

        val currentClassName = when {
            mainEntry.className in enabled -> mainEntry.className
            else -> enabled.firstOrNull { manifest.cellOf(it) != null }
        }

        return IconActivationState(
            currentClassName = currentClassName,
            enabledClassNames = enabled.toSet(),
            // 主入口经 [IconPresetManifest.cellOf] 恒等于 (0,0)，所以这里不需要再特判。
            currentCell = currentClassName?.let { manifest.cellOf(it) },
            launcherQueryFailed = enabledFromSystem == null
        )
    }

    /**
     * 问系统：本包此刻**有哪些 launcher 入口是启用的**（返回全限定类名，去重并排序）。
     *
     * 刻意**不带** `MATCH_DISABLED_COMPONENTS`：被禁用的组件不参与匹配，于是"返回了什么"
     * 就等于"系统认为哪些桌面入口是活的"—— 这正是我们要问的问题。
     * 带 `setPackage` 限定到本包，并再按包名过滤一次：个别 ROM 的实现会忽略 setPackage，
     * 那种情况下返回别包的组件会让"当前生效项"变成别人的 Activity。
     *
     * 排序（而不是依赖返回顺序）：结果要参与"当前生效项"的判定，顺序不稳定就意味着
     * 同一个状态每次打开显示不同的"使用中"。
     */
    private fun enabledLauncherClassNames(pm: PackageManager, packageName: String): List<String> {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo }
            .mapNotNull { info ->
                val name = info.name
                if (info.packageName != packageName || name.isNullOrEmpty()) {
                    null
                } else {
                    ComponentName(info.packageName, name).className
                }
            }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * 组件现在是否处于"启用"状态。
     *
     * `getComponentEnabledSetting` 对**从未显式设置过**的组件返回
     * [PackageManager.COMPONENT_ENABLED_STATE_DEFAULT]（全新安装、清除数据后都是这个值），
     * 这时必须回落到 `AndroidManifest` 里写的默认值：别名是 `enabled="false"`、主 Activity 是 true。
     * 判据是 `getActivityInfo(component, 0)`：不带 `MATCH_DISABLED_COMPONENTS` 时，
     * 被禁用的组件查不到（抛 NameNotFoundException），**查得到就等于"默认是启用的"** ——
     * 那个异常在这里不是错误，它就是"默认禁用"这个答案，所以 `getOrDefault(false)` 是对的。
     *
     * 最外层再包一层 `runCatching`：个别 ROM 的实现会在这里抛别的异常，
     * 而"整页崩掉"和"当成未启用"相比，后者才是我们要的降级。
     */
    private fun isComponentEnabled(pm: PackageManager, component: ComponentName): Boolean {
        val setting = runCatching { pm.getComponentEnabledSetting(component) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }.getOrDefault(false)
        }
    }

    /**
     * 执行切换：把 [target] 启用，把 [allComponents] 里其余的全部禁用。
     *
     * ## 顺序是硬约束：**先启用目标，再禁用其它**
     *
     * 反过来（先禁用当前项、再启用目标）会留下一个"一个都没启用"的窗口：
     *  · 窗口期内进程被系统结束、或用户退回桌面 —— 桌面上这个应用就不见了，
     *    用户只能去系统设置里找回来，多半会以为应用被卸载了；
     *  · 更糟的是"禁用成功、启用失败"，那个窗口就是**永久**的。
     * 先启后禁最坏也只是"短时间内桌面上有两个图标"，用户不会丢应用 —— 这就是全部理由。
     *
     * 所以：**第一步没有确凿成功，第二步一律不执行**（见 [IconSwitchOutcome.EnableFailed]）。
     *
     * ## 为什么还要一层「零入口」兜底（[defaultEntry]）
     *
     * 上面两道门禁挡住的是**前两步**的失败（那时当前入口还好好的，图标不会丢）。剩下最后一个
     * 缝隙在**收尾复核**（本函数末尾）：目标已经显示启用、其它入口也已经关掉，系统却在之后把它
     * 回滚成未启用。这时桌面上可能一个入口都不剩 —— 用户从桌面**再也打不开这个应用**，
     * 多半会以为它被卸载了，只能去系统设置里翻出来。所以在这个分支里重新启用 [defaultEntry]
     * （也就是主入口 `MainActivity`，它同时承载 (0,0) 这一格）：
     * **最坏情况从"用户丢失应用"降级为"切换失败但应用还在"**。
     *
     * 恢复本身也是系统调用、也可能失败，所以它的结果同样要读回校验，并且如实上报 ——
     * 见 [DefaultEntryState]（成功 / 仍不可用 / 恢复调用都没成功，三种文案完全不同）。
     *
     * @param defaultEntry 默认入口，**恒为主入口 `MainActivity`**（由调用方给出，见
     *        [IconPresetManifest.componentFor] 对 [DEFAULT_CELL] 的处理），而不是在这里按类名去猜：
     *        **一旦这里认错了组件，"兜底"本身就是个谎言**（它会把最后一道保险押在一个
     *        根本不该被启用的别名上）。
     * @return [IconSwitchOutcome.Success] 表示目标已启用（可能有个别旧入口没关掉，如实列出）；
     *         [IconSwitchOutcome.EnableFailed] 表示切换被放弃，[IconSwitchOutcome.EnableFailed.entryState]
     *         记录"此刻还有没有入口可用、兜底做没做成"。
     */
    fun applySwitch(
        context: Context,
        target: ComponentName,
        allComponents: List<ComponentName>,
        defaultEntry: ComponentName
    ): IconSwitchOutcome {
        val pm = context.packageManager

        // ---- 第 1 步：先启用目标 ----
        // DONT_KILL_APP：不禁用/启用都不要顺手杀掉本进程（用户还在看着这个页面）。
        // 注意：这个标志并不能保证进程一定活着 —— 禁用 MainActivity 本身仍可能让系统结束本应用，
        // 这正是界面上必须提前告知"切换后应用会退出"的原因。
        val setFailure = runCatching {
            pm.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }.exceptionOrNull()

        // ★ 两种失败**必须分开报**（此前是合并成一句话的）：
        //   · 调用本身抛异常 = 系统拒绝了这次调用（组件名/权限/参数的问题）；
        //   · 调用没报错但读回仍是"未启用" = 系统收下了却没照做。
        //   这两者的排查方向完全不同。合并的代价在真机上已经出现过一次：界面上那句
        //   "安装包里可能没有这个图标别名"其实出自**读回**分支，却让清单成了头号嫌疑对象，
        //   而真正的问题（传给系统的类名是简名）从头到尾没被说出来。
        //   目标入口一律用 `flattenToShortString()`：正常显示成 `pkg/.Name`，
        //   而"类名没补包名"这种坏状态会显示成 `pkg/Name` —— 那是本次故障在界面上的指纹。
        if (setFailure != null) {
            // ★★ 第二步绝不执行：当前生效的那个入口还开着，用户桌面上的图标还在。
            return IconSwitchOutcome.EnableFailed(
                "系统拒绝了这次启用调用（目标入口：${target.flattenToShortString()}）：" +
                    setFailure.javaClass.simpleName +
                    (setFailure.message?.let { "（${it}）" } ?: "") + "。" +
                    describeTargetPresence(pm, target) +
                    "当前图标保持不变。"
            )
        }

        // **读回校验**，而不是只看有没有抛异常：`setComponentEnabledSetting` 对
        // "清单里根本不存在的组件"（例如脚本生成的清单条目与 AndroidManifest 不同步）
        // 可能既不报错也不生效。只看异常会把这种失败当成成功，接着就把当前图标也关掉。
        if (!isComponentEnabledNow(pm, target)) {
            // ★★ 第二步绝不执行：当前生效的那个入口还开着，用户桌面上的图标还在。
            return IconSwitchOutcome.EnableFailed(
                "启用调用没有报错，但读回这个组件仍是未启用（目标入口：${target.flattenToShortString()}，" +
                    "getComponentEnabledSetting 读回 ${describeEnabledSetting(pm, target)}）。" +
                    describeTargetPresence(pm, target) +
                    "当前图标保持不变。"
            )
        }

        // ---- 第 2 步：再禁用其余的（当前生效的那个 + 所有非目标别名）----
        // 逐个包 runCatching：某一个关不掉不该让整次切换回滚 —— 目标已经启用了，
        // 应用是可用的；关不掉的入口会让桌面多出一个图标，如实报给用户即可。
        val disableFailures = mutableListOf<String>()
        allComponents.filter { it != target }.forEach { other ->
            runCatching {
                pm.setComponentEnabledSetting(
                    other,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure { disableFailures += other.className }
        }

        // ---- 收尾复核 ----
        // 目标必须仍然启用。正常情况下不可能变，但个别 ROM 在"同时只允许一个 launcher 组件"
        // 之类的策略下可能把它连带关掉 —— 那是最危险的状态（一个都不剩），必须大声报出来。
        if (!isComponentEnabledNow(pm, target)) {
            // ★ 「零入口」兜底：目标确实没留下来，而它之外的入口在第 2 步已经被关掉了，
            //   此刻桌面上可能一个图标都不剩（用户从桌面打不开应用）。
            //   所以这里必须把**默认入口**重新启用回来，让"至少有一个入口是开着的"重新成立 ——
            //   这把最坏情况从"用户丢失应用"降级为"切换失败但应用还在"。
            //   恢复动作本身也查结果（失败就如实说"桌面可能没有图标"），绝不假装成功。
            val entryState = restoreEntry(pm, target, defaultEntry)
            return IconSwitchOutcome.EnableFailed(
                "切换后目标图标没有保持启用（系统回滚了这次设置），可以再试一次。",
                entryState = entryState
            )
        }

        return IconSwitchOutcome.Success(disableFailures = disableFailures)
    }

    /**
     * 把 [PackageManager.getComponentEnabledSetting] 的读回值说成人话（只用于失败文案）。
     *
     * `DEFAULT` 与三个 `DISABLED` 的含义完全不同：前者是"系统里根本没有这条设置"（切换失败的
     * 典型形态就是它 —— 调用打在了不存在的组件上），后者是"显式设置过，就是关闭"。
     * 失败提示里带上它，下一次真机复现时不用连 logcat 就能分辨。
     */
    private fun describeEnabledSetting(pm: PackageManager, component: ComponentName): String {
        val setting = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull()
        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ENABLED"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DISABLED"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "DISABLED_USER"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "DISABLED_UNTIL_USED"
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "DEFAULT（系统里没有这条设置，组件只能是没被改过）"
            null -> "读取失败"
            else -> "未知值 ${setting}"
        }
    }

    /**
     * "这个组件到底在不在安装包里" —— **只用来把失败原因说准，绝不参与成败判定**。
     *
     * 为什么必须带 `MATCH_DISABLED_COMPONENTS`：不带这个标志时被禁用的组件根本查不到
     * （`getActivityInfo` 抛 `NameNotFoundException`），于是"存在但系统没让它生效"与
     * "安装包里压根没有这个组件"变成同一句话。别名在清单里都是 `enabled="false"`，
     * 所以不带标志时**任何**别名都"查不到" —— 那句"安装包里可能没有这个图标别名"就是这么来的。
     *
     * 为什么值得分开说：两种失败的处理方式完全不同（组件不存在要回头查清单/生成脚本；
     * 组件存在却没生效要去看系统的组件启用策略）。说错方向比不说更费时间。
     *
     * 为什么只用于文案、不拿它当门禁：这个查询本身在个别 ROM 上也可能查不到（安全策略等），
     * 若用它拦下切换，就会把本来能成功的切换挡在门外。诊断可以出错，门禁不能出错。
     */
    private fun describeTargetPresence(pm: PackageManager, target: ComponentName): String {
        val exists = runCatching {
            pm.getActivityInfo(target, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.isSuccess
        return if (exists) {
            "PackageManager 能查到它（被禁用也能查到），所以不是「别名不存在」，而是系统没让它生效。"
        } else {
            "PackageManager 连 MATCH_DISABLED_COMPONENTS 都查不到它，" +
                "这个安装包里可能确实没有这个组件（清单/生成脚本没同步）。"
        }
    }

    /**
     * 此刻这个组件**是不是真的可用** —— 问的是系统认不认，而不是"我们刚才调用过设置"。
     *
     * 判据分两层：
     *  1. 设置值本身给了答案：ENABLED = 是，三种 DISABLED = 否；
     *  2. 只有设置值为 DEFAULT（**从未显式设置过**）时才回落到 `getActivityInfo(component, 0).enabled`，
     *     也就是清单里写的那个默认值（主 Activity 是 true，别名是 `enabled="false"` → false）。
     *
     * 为什么要有第 2 层：某些 ROM 会把"与清单默认值一致的设置"回写成 DEFAULT。这时若只认第 1 层，
     * 切回默认图标（`MainActivity`，清单默认启用）就会因为读到 DEFAULT 而**误判成失败**，
     * 于是这个功能在这类机器上会变成"点一次失败一次"。回落读清单默认值正好给出正确答案。
     *
     * 为什么明确的 DISABLED 必须拦在回落之前：假如某个 ROM 的 `getActivityInfo` 连被禁用的组件
     * 也照样返回，那么只凭"查得到"就会把**没真正启用成功**当成成功，接着去禁用当前图标 ——
     * 那正是我们最怕的"一个都不剩"。拦住明确的 DISABLED 之后这条路径就不成立了。
     */
    private fun isComponentEnabledNow(pm: PackageManager, component: ComponentName): Boolean {
        val setting = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull()
        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            // DEFAULT（或设置值读不出来）：回落到清单默认值；查不到就按"不可用"处理。
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }.getOrDefault(false)
        }
    }

    /**
     * 「零入口」兜底：把 [defaultEntry] 重新启用，并**查回结果**（只调一次就宣布成功是不算数的）。
     *
     * 三种结果分开报（见 [DefaultEntryState]），因为用户要做的事完全不同：
     *  · 恢复成功 -> "已恢复为默认图标"，桌面上的图标回来了，收工；
     *  · 调用没抛异常但读回仍不可用 -> 系统收下了却没照做，让用户去系统设置里手动启用；
     *  · 调用直接抛异常 -> 这次恢复根本没送出去（组件名/权限/ROM 策略），同样如实说。
     *
     * 调用前先判一次"是不是本来就开着"，而不是无脑重设：
     *  · 已经开着就不必再打一次系统调用（第 2 步没能关掉它时就会出现这种情况）；
     *  · 读回用的是 [isComponentEnabledNow]，它对 DEFAULT 会回落到清单默认值，
     *    而 `MainActivity` 在清单里是默认启用 —— 于是"系统把它回写成 DEFAULT"这类时序差异
     *    会被正确地当成"已启用"，不会让一次本来成功的兜底被判成失败。
     *
     * 唯一跳过这次预读的情况是**目标本身就是默认入口**（用户点的那一格是「默认」）：
     * 它此刻必定是关着的，没有可读的东西，直接尝试恢复。
     */
    private fun restoreEntry(
        pm: PackageManager,
        target: ComponentName,
        defaultEntry: ComponentName
    ): DefaultEntryState {
        // 目标本身就是默认入口：它现在（复核失败时）是关着的，重读没有意义，直接尝试恢复，
        // 失败时也只能是 StillDisabled —— 没有"另一个更该恢复的入口"这回事。
        if (defaultEntry == target) {
            return enableEntryOrFail(pm, defaultEntry)
        }
        if (isComponentEnabledNow(pm, defaultEntry)) {
            // 默认入口本来就开着（例如第 2 步里它没被关掉）：已经有入口了，不需要再动任何东西。
            return DefaultEntryState.Restored
        }
        return enableEntryOrFail(pm, defaultEntry)
    }

    /** 真正去启用 [entry] 并读回校验。抽出来是为了让"目标就是默认入口"那条路径也走同一套判据。 */
    private fun enableEntryOrFail(pm: PackageManager, entry: ComponentName): DefaultEntryState {
        val failure = runCatching {
            pm.setComponentEnabledSetting(
                entry,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }.exceptionOrNull()
        if (failure != null) {
            return DefaultEntryState.RestoreFailed(
                "${entry.flattenToShortString()}：" + failure.javaClass.simpleName +
                    (failure.message?.let { "（${it}）" } ?: "")
            )
        }
        if (!isComponentEnabledNow(pm, entry)) {
            return DefaultEntryState.StillDisabled
        }
        return DefaultEntryState.Restored
    }
}

/**
 * 图标轴上的一个选项（含下标 0 的哨兵「默认（应用自己的图标）」）。
 *
 * [resId] = 0 当且仅当 [isDefault] 为 true：默认项的图标是应用自身的 launcher 图标，
 * 只能从 `PackageManager` 取（`ApplicationInfo`），不是 drawable 资源。
 * 渲染处因此**不能**对 0 调 `painterResource`（会抛 IllegalArgumentException 把整页弄崩）。
 *
 * [index] 必须原样带着走：组合表的第一个数字就是它。界面上的排列顺序可以变，
 * 但"这一格是第几个图标"只能由它回答 —— 换名字时要靠它去查组合表。
 */
data class IconAxisOption(
    val index: Int,
    val label: String,
    val resId: Int,
    val isDefault: Boolean
)

/** 名称轴上的一个选项（含下标 0 的哨兵「默认（应用自己的名字）」，它在数组里是空字符串）。 */
data class NameAxisOption(
    val index: Int,
    val label: String,
    val isDefault: Boolean
)

/**
 * 三个数组拼出来的结果：图标轴 + 名称轴 + 组合表。
 *
 * [missing] 里放的是**人能看懂的短描述**（哪一条、为什么不可用），它会原样显示在页面的常驻横幅上：
 * 存在的意义就是让失败可见，所以不能塞进日志了事。
 */
data class IconPresetManifest(
    val packageName: String,
    /** 主入口（`MainActivity`）全限定类名 —— 契约规定它**就是** (0,0) 那一格（没有别名）。 */
    val mainClassName: String,
    /** 图标轴上**画得出来**的项（下标 0 恒为哨兵；解析失败的图标不在这里，见 [missing]）。 */
    val icons: List<IconAxisOption>,
    /** 名称轴上画得出来的项（下标 0 恒为哨兵）。 */
    val names: List<NameAxisOption>,
    /** 格子 → 组件**全限定**类名（已补包名，见 [IconPresetSwitcher.qualifyComponentName]）。 */
    val aliasByCell: Map<Pair<Int, Int>, String>,
    /** 组件全限定类名 → 格子，[aliasByCell] 的反查表。 */
    val cellByClassName: Map<String, Pair<Int, Int>>,
    /**
     * 下标 → 显示名，**含解析失败、没画出来的那些项**：反查当前格子、以及各种提示文案里都要能
     * 说出"是哪一个图标 / 哪一个名字"，否则用户对不上界面上的任何一格。
     */
    val iconLabels: Map<Int, String>,
    val nameLabels: Map<Int, String>,
    val missing: List<String>
) {
    /** 除哨兵外还有没有真的预设图标（没有时图标分区显示空态说明）。 */
    val hasPresetIcons: Boolean get() = icons.any { !it.isDefault }

    /** 除哨兵外还有没有真的预设名字（没有时名称分区显示空态说明）。 */
    val hasPresetNames: Boolean get() = names.any { !it.isDefault }

    /**
     * 组件全限定类名 → 组合格子。
     *
     * ★ 主入口**恒等于** (0,0)：它没有别名，反查时若漏掉这一条，正常使用的应用会被判成
     *   "反查不到"，于是两个分区都不标「使用中」—— 那是最常见的一种误报。
     *   返回 null 只有一种含义：这个组件既不是主入口、也不在组合表里（清单与资源不同步）。
     */
    fun cellOf(componentClassName: String): Pair<Int, Int>? = when (componentClassName) {
        mainClassName -> IconPresetSwitcher.DEFAULT_CELL
        else -> cellByClassName[componentClassName]
    }

    /**
     * 格子 → 组件。**只认 [aliasByCell] 里真有的格子**，(0,0) 单独返回主入口。
     *
     * 传进 [ComponentName] 的一律是**已补包名**的全限定类名：
     * `ComponentName(packageName, "IconPreset_1_2")` 不会按包名补全（见
     * [IconPresetSwitcher.qualifyComponentName]），本项目刚因此出过一次线上故障。
     *
     * @return null = 这一格在安装包里没有别名（生成脚本与两个轴不同步）。
     *         调用方**不要**改用别的组件顶上，如实把这一格报出来即可。
     */
    fun componentFor(cell: Pair<Int, Int>): ComponentName? = if (cell == IconPresetSwitcher.DEFAULT_CELL) {
        ComponentName(packageName, mainClassName)
    } else {
        aliasByCell[cell]?.let { ComponentName(packageName, it) }
    }

    /**
     * 参与"启用一个、禁用其余"的全部桌面入口：主入口 + 组合表里的每个别名（去重）。
     *
     * 刻意**不**包含"系统报告的多余入口"（见 [IconPresetSwitcher.readActivation] 的注释）：
     * 那些组件本页不认识，关掉一个不认识的桌面入口不是本页该做的事 —— 最坏也只是桌面上多一个
     * 图标，而认错组件的代价可能是砍掉一个用户正在用的入口。
     */
    fun allComponents(): List<ComponentName> =
        (listOfNotNull(componentFor(IconPresetSwitcher.DEFAULT_CELL)) +
            aliasByCell.values.map { ComponentName(packageName, it) }).distinct()

    fun iconLabelOf(index: Int): String = iconLabels[index] ?: "图标 #${index}"

    fun nameLabelOf(index: Int): String = nameLabels[index] ?: "名字 #${index}"

    /** 提示文案里说清一个格子的固定说法：`图标「X」＋名称「Y」`。 */
    fun axisLabel(cell: Pair<Int, Int>): String =
        "图标「${iconLabelOf(cell.first)}」＋名称「${nameLabelOf(cell.second)}」"

    /** 确认框标题里用的短组合名：`X + Y`（外面还会再套一层「」，所以这里不再加引号）。 */
    fun combinedLabel(cell: Pair<Int, Int>): String =
        "${iconLabelOf(cell.first)} + ${nameLabelOf(cell.second)}"
}

/**
 * 从 `PackageManager` 读到的真实启用状态。
 *
 * [currentClassName] 为 null 表示**一个都没启用** —— 那是个真会发生的坏状态
 * （用户之前切换失败、或在系统设置里手动关过），此时应用无法从桌面启动，
 * 界面必须允许点任意一格来恢复，包括「默认」。
 *
 * [currentCell] 是"当前生效的是哪一格"，**只由 [currentClassName] 反查得来**。
 * 它为 null 而 [currentClassName] 不为 null 时含义很明确：这个入口既不是主入口、也不在组合表里
 * （手动改过 / 清单与资源不同步）—— 界面此时**不给任何一个轴标「使用中」**（不知道就是不知道），
 * 但要给一条常驻说明把原因讲清楚，而不是让两个分区都空着。
 *
 * [enabledClassNames] 来自 `PackageManager` 自己的报告（本包所有已启用的 launcher 入口），
 * 因此可能包含本页不认识的组件：那样桌面上就会多出图标，界面照实说。
 */
data class IconActivationState(
    val currentClassName: String?,
    val enabledClassNames: Set<String>,
    val currentCell: Pair<Int, Int>?,
    /** "本包还有哪些 launcher 入口在启用"这次查询失败了 —— 那么"一个都没启用"这个结论也不可靠。 */
    val launcherQueryFailed: Boolean
)

/** 切换结果。**没有"不确定"这一档**：要么目标确实启用了，要么明确放弃（当前图标不变）。 */
sealed interface IconSwitchOutcome {
    /**
     * 目标已启用。
     * @param disableFailures 没能关掉的旧入口全类名（正常为空；非空表示桌面可能多出图标）。
     */
    data class Success(val disableFailures: List<String>) : IconSwitchOutcome

    /**
     * 切换失败、已放弃。**桌面上有没有图标，看 [entryState]，不要按"失败"两个字想当然。**
     *
     * 除了"前三步就失败"（那时当前入口一个都没动，桌面上的图标还在），还有最危险的一种：
     * 收尾复核发现目标没保住启用 —— 此刻其它入口都已经关掉了，桌面可能一个图标都不剩。
     * 那种情况会尽力把「默认」入口恢复回来，[entryState] 就是这次恢复的**实际结果**：
     * 成功 = 桌面还有图标（切换失败，但应用还在）；失败 = 桌面可能真的空了，必须明说。
     *
     * @param reason 切换失败本身的原因（**不包含**恢复结果，恢复结果一律看 [entryState]）。
     */
    data class EnableFailed(
        val reason: String,
        val entryState: DefaultEntryState = DefaultEntryState.Unknown
    ) : IconSwitchOutcome
}

/**
 * 切换失败之后，"桌面入口"此刻的真实状态 —— 也就是那次兜底恢复的结果。
 *
 * 为什么要单独一个类型，而不是在失败文案里拼一句话：**用户要做的事完全不同**。
 * 恢复成功时用户只需要再点一次；恢复失败时用户从桌面已经打不开应用了，得去系统设置里启用、
 * 或者重装。这两种情况混成一句"切换失败"，第二种就被静默掉了 —— 那正是本类型存在的理由。
 */
sealed interface DefaultEntryState {
    /** 恢复动作没走到（例如前两步就失败了，当前入口一直好好开着）—— **不表示"没有入口"**。 */
    data object Unknown : DefaultEntryState

    /** 默认入口（`MainActivity`）确实处于启用状态：桌面上有图标，应用还在。 */
    data object Restored : DefaultEntryState

    /** 调用没报错，但读回默认入口仍是未启用 —— 系统收下了却没照做。 */
    data object StillDisabled : DefaultEntryState

    /**
     * 恢复调用本身就失败了（异常、权限、ROM 策略等）。
     * @param detail 组件全名 + 异常类型与消息，供用户回报问题时逐字引用。
     */
    data class RestoreFailed(val detail: String) : DefaultEntryState
}

/** 给用户看的一条操作结果。[isError] 为 true 时页面用错误样式且**不会自动消失**。 */
data class IconPresetMessage(val text: String, val isError: Boolean)

/**
 * 一次切换的目标：**一个组合格子**（图标 i × 名字 j），以及由它换算出来的真实组件。
 *
 * 为什么不让界面直接拿着组件去切换：界面点的是"某一格"，而"哪一格对应哪个组件"这件事只有
 * [IconPresetManifest.componentFor] 说了算（它里面才是补包名、查组合表的地方）。把结果装成
 * 这个类型传下去，界面就没机会自己拼类名 —— 本项目刚因为"简名直接塞进 ComponentName"
 * 出过一次线上故障。
 *
 * [assumedOtherAxis] 为 true 表示"另一个轴当前无法判定（[IconActivationState.currentCell] 反查不到），
 * 已按默认项处理"：确认框必须把这句话说出来，否则用户会以为另一个轴保持不变。
 */
data class IconSwitchTarget(
    val iconIndex: Int,
    val nameIndex: Int,
    val component: ComponentName,
    /** 组合名，例如 `猫耳 + 默认（应用自己的名字）`，用于确认框标题与结果提示。 */
    val label: String,
    val assumedOtherAxis: Boolean
)

/**
 * 「更换应用图标」页的状态与动作。
 *
 * 页面上的两个轴（图标 / 名字）在这里合成"一格"，再由这一格算出要启用哪个组件 ——
 * 界面只负责把 [switchTargetForIcon] / [switchTargetForName] 的结果交给确认框。
 *
 * 为什么值得单独一个 ViewModel（而不是像此前那样全写在 composable 里）：
 *  1. 切换要写 DataStore（进行中标记）并在 IO 线程上做系统调用，需要协程作用域；
 *  2. 切换结果、以及"上次切换没跑完"的提示，必须能扛住屏幕旋转/分屏重建 ——
 *     用 `remember` 存的话用户一转屏提示就没了。
 */
@HiltViewModel
class IconPresetViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appearanceRepository: AppearanceSettingsRepository
) : ViewModel() {

    /**
     * 清单：图标轴 + 名称轴 + 组合表。资源数组是**编译期常量**，进程存活期间不会变，
     * 所以只解析一次，也不需要任何加载中状态：它是同步瞬间读完的，转个圈才是假装在加载。
     */
    val manifest: IconPresetManifest =
        IconPresetSwitcher.parseManifest(appContext.resources, appContext.packageName)

    /**
     * 主入口（`MainActivity`）：它就是 (0,0) 那一格（默认图标 + 默认名字），
     * 同时也是「零入口兜底」要恢复的那个组件。只算一次，与 [IconPresetManifest.mainClassName]
     * 指的是同一个组件。
     */
    private val mainEntry: ComponentName = ComponentName(appContext, MainActivity::class.java)

    /**
     * 当前启用状态。初始值同步读一次：这只是几个本地 binder 调用（组件数量是个位数），
     * 比"先转圈再显示"更符合本项目的脾气 —— 状态本来就是立刻可得的。
     */
    private val _activation =
        MutableStateFlow(IconPresetSwitcher.readActivation(appContext, manifest))
    val activation: StateFlow<IconActivationState> = _activation.asStateFlow()

    private val _message = MutableStateFlow<IconPresetMessage?>(null)
    val message: StateFlow<IconPresetMessage?> = _message.asStateFlow()

    init {
        reportInterruptedSwitchIfAny()
    }

    /**
     * 上次切换是否"没跑完"（切换过程中进程被系统结束了）。
     *
     * 这是把 [AppearanceSettingsRepository.setIconSwitchPending] 那个诊断标记用掉的地方：
     * 标记还在 + 目标确实已启用 = 上次其实成功了（只是没来得及回报）；否则就是没成功。
     * 两种情况都**如实说出来**，然后立刻清掉标记（它只对"上一次"负责，不能每次打开都冒出来）。
     */
    private fun reportInterruptedSwitchIfAny() {
        viewModelScope.launch {
            val pending = runCatching { appearanceRepository.iconSwitchPending() }.getOrNull()
                ?: return@launch
            runCatching { appearanceRepository.setIconSwitchPending(null) }

            // 先刷新一次真实状态再比对：标记是"我们打算做什么"，PackageManager 才是"做成了什么"。
            refreshActivation()
            val current = _activation.value.currentClassName
            _message.value = if (current == pending) {
                IconPresetMessage(
                    "上次的图标切换已完成：${describeCurrent(current)}。",
                    isError = false
                )
            } else {
                IconPresetMessage(
                    "上次的图标切换没有完成：${describeCurrent(current)}。可以再试一次。",
                    isError = true
                )
            }
        }
    }

    /**
     * 点了**图标轴**第 [iconIndex] 项：名字轴保持"当前看到的那一个"。
     *
     * @return 需要确认的切换目标；null = **不要弹确认框**（原因见 [buildSwitchTarget]）。
     */
    fun switchTargetForIcon(iconIndex: Int): IconSwitchTarget? {
        val currentCell = _activation.value.currentCell
        return buildSwitchTarget(
            cell = iconIndex to (currentCell?.second ?: IconPresetSwitcher.DEFAULT_CELL.second),
            assumedOtherAxis = currentCell == null
        )
    }

    /**
     * 点了**名称轴**第 [nameIndex] 项：图标轴保持"当前看到的那一个"。
     *
     * @return 需要确认的切换目标；null = **不要弹确认框**（原因见 [buildSwitchTarget]）。
     */
    fun switchTargetForName(nameIndex: Int): IconSwitchTarget? {
        val currentCell = _activation.value.currentCell
        return buildSwitchTarget(
            cell = (currentCell?.first ?: IconPresetSwitcher.DEFAULT_CELL.first) to nameIndex,
            assumedOtherAxis = currentCell == null
        )
    }

    /**
     * 把"点了某一格"翻译成"该启用哪个组件"。
     *
     * 三条规矩：
     *  1. 点的就是当前这一格 ⇒ 返回 null 且**什么都不做**（切到自己没有意义，更不该弹一个
     *     "应用会退出"的确认框吓人）；
     *  2. 这一格在组合表里没有别名 ⇒ 返回 null，同时把原因写进消息横幅 —— **绝不**临时换一个
     *     "差不多的组件"顶上：那会让界面上写的和实际生效的对不上，而这正是本页最不能出的错；
     *  3. 当前格子反查不到（[IconActivationState.currentCell] 为 null）⇒ 另一轴按哨兵（默认）处理，
     *     并把 [IconSwitchTarget.assumedOtherAxis] 置起来，让确认框**明说**这件事 ——
     *     "不知道另一个轴现在是什么"时，唯一诚实的做法就是告诉用户我们要把它设成什么。
     *
     * 组件一律经 [IconPresetManifest.componentFor] 取得（内部已用
     * [IconPresetSwitcher.qualifyComponentName] 补过包名），这里不自己拼类名。
     */
    private fun buildSwitchTarget(cell: Pair<Int, Int>, assumedOtherAxis: Boolean): IconSwitchTarget? {
        if (cell == _activation.value.currentCell) return null

        val component = manifest.componentFor(cell)
        if (component == null) {
            _message.value = IconPresetMessage(
                "「${manifest.axisLabel(cell)}」这个组合在安装包里没有对应的桌面入口" +
                    "（组合表 R.array.preset_alias_matrix 里缺这一格），所以还不能切换。" +
                    "换一个组合试试；如果整片都点不动，说明清单没有按新的组合重新生成。",
                isError = true
            )
            return null
        }

        return IconSwitchTarget(
            iconIndex = cell.first,
            nameIndex = cell.second,
            component = component,
            label = manifest.combinedLabel(cell),
            assumedOtherAxis = assumedOtherAxis
        )
    }

    /**
     * 切换到 [target] 这个**组合格子**。调用前界面必须已经用对话框告知"应用会退出"
     * （见 [IconPresetScreen]）。
     *
     * 步骤与理由（与"按图标单项切换"那版完全一致，只是目标从"某一项"变成"某一格"）：
     *  1. 先落"进行中"标记（诊断用，见仓库里的注释）—— 万一进程被杀，下次打开还能如实说明，
     *     而不是让整次操作静默；
     *  2. 在 IO 线程上执行 [IconPresetSwitcher.applySwitch]（先启用目标、再禁用其它，绝不产生空集；
     *     万一收尾复核发现目标没保住，它还会把默认入口恢复回来，见 [DefaultEntryState]）；
     *  3. 无论成功失败先清标记，再刷新状态、给出结果提示。
     *     第 3 步有可能**根本执行不到**（进程被系统结束），那就由标记在下一次打开时补上说明。
     */
    fun switchTo(target: IconSwitchTarget) {
        viewModelScope.launch {
            runCatching {
                appearanceRepository.setIconSwitchPending(target.component.className)
            }

            val outcome = withContext(Dispatchers.IO) {
                IconPresetSwitcher.applySwitch(
                    context = appContext,
                    target = target.component,
                    // "关掉其余"的集合 = 主入口 + 组合表里的每个别名（见 allComponents 的注释）。
                    allComponents = manifest.allComponents(),
                    // 兜底恢复用的默认入口**恒为主入口**：它同时承载 (0,0)，是"至少还有一个入口
                    // 活着"的那一个（见 [IconPresetSwitcher.applySwitch] 的「零入口兜底」）。
                    // 不用"图标轴第 0 项"那种间接说法：那是一个格子，不是组件。
                    defaultEntry = mainEntry
                )
            }

            runCatching { appearanceRepository.setIconSwitchPending(null) }

            when (outcome) {
                is IconSwitchOutcome.EnableFailed ->
                    _message.value = IconPresetMessage(
                        "切换失败：${outcome.reason}${describeEntryState(outcome.entryState, target.label)}",
                        isError = true
                    )

                is IconSwitchOutcome.Success -> {
                    // ★ 把"用户选了这一格"落盘，**只用于更新后的自救援**（见
                    //   [AppearanceSettingsRepository.setIconPresetChoiceCell] 的注释：界面上的
                    //   "使用中"依然只信 PackageManager，**这个键不参与任何界面判定**）。
                    //   放在刷新状态之前写：禁用正在使用的组件可能让系统结束本进程，
                    //   这一步越早，越不容易卡在"切换已成功、记录还没落盘"之间被杀掉。
                    //   写失败不改变本次切换的结论（目标确实启用了）：那只会让**将来**的更新后
                    //   自救援回落到默认入口 —— 与老安装缺这个键时的行为完全一致，是可接受的降级。
                    runCatching {
                        appearanceRepository.setIconPresetChoiceCell(
                            target.iconIndex to target.nameIndex
                        )
                    }
                    // 进程还活着才走得到这里：直接重读 PackageManager 刷新两个轴的"使用中"标记。
                    refreshActivation()
                    _message.value = successMessage(target, outcome.disableFailures)
                }
            }
        }
    }

    /**
     * 成功提示 —— 但"成功"只说明**目标启用了**，不等于桌面上只剩这一个图标。
     *
     * 所以这里再复核一次真实状态：除目标之外**还有哪些入口开着**。多出来的入口有两种来源，
     * 处理方式完全不同，文案也必须分开：
     *  · 关不掉的旧入口（[disableFailures]）：系统拒绝了我们的禁用调用，重试可能有用；
     *  · 本页不认识的入口（在组合表里查不到）：本页**不会**去动它们
     *    （见 [IconPresetManifest.allComponents]），只能提示去重新生成清单或重装 ——
     *    把它们说成"没关掉"会把人引到错误的排查方向上。
     */
    private fun successMessage(target: IconSwitchTarget, disableFailures: List<String>): IconPresetMessage {
        val leftovers = _activation.value.enabledClassNames.filter { it != target.component.className }
        if (disableFailures.isEmpty() && leftovers.isEmpty()) {
            return IconPresetMessage(
                "已切换到「${target.label}」。请回到桌面确认图标与名称（部分启动器有几秒延迟）。",
                isError = false
            )
        }
        return IconPresetMessage(
            buildString {
                append("已启用「${target.label}」，但桌面上可能同时出现多个图标：")
                if (disableFailures.isNotEmpty()) {
                    append("有 ${disableFailures.size} 个旧入口没能关闭（${disableFailures.joinToString("、")}）")
                }
                if (leftovers.isNotEmpty()) {
                    if (disableFailures.isNotEmpty()) append("；")
                    append(
                        "系统还报告 ${leftovers.size} 个本页不认识的入口处于启用状态" +
                            "（${leftovers.joinToString("、")}）—— 它们不在组合表里，本页不会去动它们，" +
                            "请重新生成清单（让组合表与清单一致）或重装本应用"
                    )
                }
                append("。")
            },
            isError = true
        )
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** 重新问一次 PackageManager。切换之后、以及发现异常状态时调用。 */
    private fun refreshActivation() {
        _activation.value = IconPresetSwitcher.readActivation(appContext, manifest)
    }

    /**
     * 把「零入口兜底」的结果说给用户听（见 [DefaultEntryState]）。
     *
     * 三种结果的语气刻意分开，因为用户接下来要做的事完全不同：
     *  · [DefaultEntryState.Restored] —— 切换失败但应用还在，**明说"已恢复为默认图标"**，
     *    用户回桌面就能继续用，只需要再点一次切换；
     *  · [DefaultEntryState.StillDisabled] / [DefaultEntryState.RestoreFailed] —— 恢复也没成，
     *    桌面可能真的空了，必须**明说"桌面可能没有图标"并给出两条自救路径**（系统设置里启用、
     *    或重装）。这里绝不能说成"已恢复"，否则用户回到桌面找不到应用，会以为是应用被卸载了；
     *  · [DefaultEntryState.Unknown] —— 目标是「默认」那一格时它本来就是关的，没有"恢复成功"
     *    这回事，所以只说"当前图标保持不变"，不承诺任何恢复动作。
     *
     * ★ 字符串模板一律写 ${...}：中文是合法的 Kotlin 标识符字符，
     *   `"$targetLabel（"` 这种写法会被解析成变量 `targetLabel（` 而编译不过。
     */
    private fun describeEntryState(state: DefaultEntryState, targetLabel: String): String = when (state) {
        DefaultEntryState.Restored ->
            "已恢复为默认入口（「${IconPresetSwitcher.DEFAULT_ICON_LABEL} + " +
                "${IconPresetSwitcher.DEFAULT_NAME_LABEL}」），桌面上还有图标，应用可以正常打开；" +
                "想换到「${targetLabel}」可以再试一次。"
        DefaultEntryState.StillDisabled ->
            "★ 恢复默认图标也没能生效（系统收下了设置却没有启用它）：桌面可能没有图标，" +
                "请到系统设置的「应用 → 本应用」里启用它的桌面入口，或重新安装本应用。"
        is DefaultEntryState.RestoreFailed ->
            "★ 恢复默认图标时系统直接拒绝了调用（${state.detail}）：桌面可能没有图标，" +
                "请重新安装本应用，或到系统设置的「应用 → 本应用」里启用它的桌面入口。"
        DefaultEntryState.Unknown ->
            "本次切换没有改动任何桌面入口，当前图标保持不变。"
    }

    /**
     * 把组件全类名说成人话（用于提示文案）。
     *
     * 反查得到格子时说的是"图标「X」＋名称「Y」"：这一页切换的单位就是**组合**，
     * 只说一个名字会让用户以为"刚才那次只换了图标"。反查不到就原样报类名 ——
     * 那正是需要用户/开发者看到的信息（清单与资源不同步、或被人手动改过）。
     *
     * 注意这里用的是 `$变量` 之外的 `${...}` 写法：中文是合法的 Kotlin 标识符字符，
     * `"...$label里"` 会被当成变量 `label里` 而编译不过。
     */
    private fun describeCurrent(className: String?): String {
        if (className == null) return "当前没有识别到已启用的桌面入口"
        val cell = manifest.cellOf(className)
        return "当前生效的是${if (cell == null) className else manifest.axisLabel(cell)}"
    }
}
