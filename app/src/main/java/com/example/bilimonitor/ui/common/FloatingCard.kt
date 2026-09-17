package com.example.bilimonitor.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 浮动卡片：全应用"浮在背景图之上"的卡片（浮动标题栏、搜索卡、筛选卡、统计粒度卡、Dock）
 * **共用同一套外观规则**的唯一出处。
 *
 * ## 为什么要有这个文件（用户反馈："搜索框和标题栏下面有一个淡淡的白色方框"）
 *
 * 这几个组件此前各写各的 `Surface(...)`，参数**已经开始漂移**：
 * Dock 早已按 H54/H59 的结论改成 `surfaceContainer + tonalElevation = 0.dp + 玻璃态无阴影`，
 * 而标题栏 / 搜索卡 / 筛选卡 / 统计粒度卡仍然带着 `tonalElevation = 3.dp` 与
 * **恒定不变的 `shadowElevation = 6.dp`**。参数一旦各写各的，观感就不可能一致，
 * 用户看到的"某个页面的某个方框和别处不一样"就是这么来的。规则收到这一处之后，
 * 想改观感只改这个文件，不会再漏掉某一个页面。
 *
 * ## 规则一：`tonalElevation` 只能是 0 —— 传 3.dp 是**空操作**，但会骗人
 *
 * M3 的 `Surface` 只在 `color == colorScheme.surface` 时才把 `surfaceTint`（默认跟随 primary）
 * 按高度混进容器色：material3 1.3.1 的 `ColorSchemeKt.applyTonalElevation` 反编译后
 * 第一件事就是 `color.equals-impl0(scheme.getSurface())`，不相等直接原样返回。
 * 本项目的浮动卡片取的是 `surfaceContainer` / `surfaceContainerHigh`，**永远不等于 `surface`**，
 * 所以那两处 `tonalElevation = 3.dp` 从来没生效过（不是"效果很轻"，是零效果）。
 * Dock 当初"偏红且变不回纯白"就是踩了这条：它的容器色一度是 `surface`，于是主题色一变
 * 就被持续染上 primary 色（见 AppNavHost 里那段注释与台账 H59④）。
 *
 * 这里把 `tonalElevation` 显式留空（M3 默认 0.dp），不是为了省一个参数，而是：
 *  · 它是**死参数**，留着会让后来的人以为"这里靠高度做了层次"，从而照着抄；
 *  · 一旦哪天有人把容器色换成 `surface`（比如想"和列表卡片统一"），这个死参数会**立刻复活**，
 *    出现"只有某些主题色下才偏色"的玄学 bug —— 与 Dock 当初的症状一模一样。
 * 层次需要靠颜色角色表达（例如筛选卡用 `surfaceContainerHigh`），不靠高度色调。
 *
 * ## 规则二：卡片**半透明时不给阴影**（这才是"淡淡的白色方框"的来源）
 *
 * Android 的高度阴影是**平台用轮廓画的一圈黑色剪影**（Compose 侧
 * `DefaultShadowColor = Color.Black` 在 compose-ui 1.7.6 里已由反编译确认：
 * `GraphicsLayerScopeKt` 的静态初始化就是 `Color.Black`；本项目全库也没有任何地方改过
 * `ambientShadowColor` / `spotShadowColor`）。它有两个后果：
 *
 *  1. 不透明卡片：阴影只落在卡片**外面**，是想要的"浮起来"的提示；
 *  2. **半透明卡片**：卡片底下的东西本来就透得出来，而这圈黑色剪影是**实体几何**、不跟着
 *     卡片一起变淡，于是紧贴卡片下沿出现一块"硬边的暗色方块"——用户看到的就是
 *     "卡片本体之外、下面还有一个方框"。
 *
 * 更要紧的是**它只在复杂背景图上明显**：
 *  · 没设背景图时 Scaffold 的容器色是**不透明**的 `background`，卡片底色与它几乎同色，
 *    阴影也落在同一种浅色上 —— 整块东西糊在一起，看不出"多了一个框"；
 *  · 设了**纯色 / 浅色**背景图时同理，淡色卡片和淡色背景仍然融在一起；
 *  · 换成**色调复杂**的背景图（明暗交错、颜色多）之后，半透明卡片是一块发白的框、
 *    它下面的黑色剪影又是一块暗框，两者在复杂底色上同时显形 —— 就是用户报的现象。
 *
 * Dock 早就是这么处理的：`shadowElevation = if (glass) 0.dp else 8.dp`
 * （"玻璃模式下容器近乎透明，真正的观感由下面的模糊层提供"）。这里把同一条规则
 * **按容器色的实际 alpha 推导**，而不是按某个设置开关推导 —— 这样它不可能与外观设置漂移：
 * 用户把卡片调成不透明（卡片透明度 100%、卡片玻璃关闭）时阴影立刻回来，
 * 调成半透明时阴影自动让位。
 *
 * ## 参数
 *
 * @param color 容器色。默认 `surfaceContainer`（标题栏 / 搜索卡 / 统计粒度卡）；
 *   筛选卡需要再高一层时传 `surfaceContainerHigh`。**必须走 colorScheme 的 surface 系列**，
 *   外观设置里的卡片透明度 / 明暗 / 玻璃才会自动生效（见 Theme.applyCardAppearance）。
 * @param shape 圆角形状，默认与 Dock 同族的 22dp 圆角。
 */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        // 刻意不传 tonalElevation：M3 默认 0.dp，理由见文件头"规则一"。
        shadowElevation = floatingCardShadowElevation(color, FloatingCardShadow)
    ) {
        content()
    }
}

/** 不透明浮动卡片的阴影高度（原来的值，保持不变）。 */
val FloatingCardShadow: Dp = 6.dp

/**
 * 浮动卡片的阴影高度：**容器色不透明 → [opaque]；半透明（玻璃 / 卡片透明度 < 100%）→ 0dp**。
 *
 * 判据取容器色自己的 alpha 而不是某个设置项：`applyCardAppearance` 只在
 * （压暗 > 0 或 不透明度 < 1）时才会给 surface 系列写入 alpha，所以
 * "颜色是半透明的" 与 "卡片正在走玻璃/半透明外观" 是**同一件事**，不会各说各话。
 *
 * Dock 传 `opaque = 8.dp`（它的原值），其余浮动卡片用 [FloatingCardShadow]。
 */
fun floatingCardShadowElevation(fill: Color, opaque: Dp): Dp =
    if (fill.alpha < 1f) 0.dp else opaque
