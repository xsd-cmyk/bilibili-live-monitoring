package com.example.bilimonitor.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 浮动标题栏（用户要求：去掉每个页面头顶那条大标题栏，改成类似 Dock 的浮动卡片）。
 *
 * 与 Material3 `TopAppBar` 的区别：
 *  - **不占满整行**：左右各留 12dp、上下 8dp，是一张**浮起来的圆角卡片**（22dp 圆角 + 阴影），
 *    与悬浮 Dock 是同一种视觉语言；
 *  - **标题左右居中于整页、上下居中于卡片**（用户明确要求）：标题放在一个撑满卡片宽度的
 *    Box 里居中（绝对居中），而不是放在"左右按钮夹出来的剩余空间"里 —— 后者只要右侧有按钮，
 *    标题就会被顶得偏左；
 *  - 绝对居中会带来"标题压到按钮下面"的风险，所以标题的左右留白按**两侧按钮的实测宽度**
 *    动态计算（见下），不再是写死的 56dp；
 *  - 颜色取 `surfaceContainer` —— 这样它**自动继承**外观设置里的卡片透明度/明暗，
 *    以及"液态玻璃同步到卡片"的效果（见 Theme.kt 的 `applyCardAppearance`），
 *    不必为标题栏单独写一套玻璃逻辑。
 *
 * 界面的配合要求：`title` 请用 [FloatingTopBarTitle]，它是单行 + 省略号的封装 ——
 * 这个参数是个 slot，组件没法给调用方传进来的任意 `Text` 补 `maxLines`，
 * 而标题一换行就会把浮动卡片撑高、也更容易和按钮抢位置。
 *
 * 参数刻意与 `TopAppBar` 保持同名（`title` / `navigationIcon` / `actions`），
 * 因此各页面的调用点只需把函数名换掉即可，语义与层级都不用动。
 */
@Composable
fun FloatingTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
        actions: @Composable RowScope.() -> Unit = {},
    /**
     * 标题在卡片内的对齐方式，默认居中。
     *
     * 首页多选态右侧有 5 个按钮，标题再绝对居中必然与它们争位置；
     * 那种场景把标题缩短并传 Alignment.CenterStart 放到左侧空位，是唯一不打架的摆法。
     */
    titleAlignment: Alignment = Alignment.Center
) {
    // 两侧按钮的实测宽度（px）：绝对居中的标题能"让"出多少位置，只能量出来。
    // 用 onSizeChanged 而不是 onGloballyPositioned —— 两者回调时机相同（前者内部就是后者
    // 加了一次尺寸比较），但省掉每次布局的 LayoutCoordinates 构造。
    var leadingWidth by remember { mutableIntStateOf(0) }
    var trailingWidth by remember { mutableIntStateOf(0) }
    var barWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val reservedPerSide = with(density) {
        val maxSide = maxOf(leadingWidth, trailingWidth)
        // 实测宽度包含按钮自身的左右内边距（IconButton 48dp 里图标只占 24dp，TextButton 也各留
        // 12dp），减掉它再加一道缝，才既压不到字、又不白占标题的地方。
        val wanted = maxSide - VisualPadding.toPx() + SideGap.toPx()
        // 两侧**对称**留白，标题才落在整页中线上；上限保证标题至少还剩 MinTitleWidth 可读宽度
        // ——按钮极多的页面（首页多选态 5 个按钮）两侧加起来会超过整页宽度，不留上限的话
        // 标题会被挤成 0 宽，直接消失。
        val cap = barWidth / 2f - MinTitleWidth.toPx()
        wanted.coerceIn(0f, cap.coerceAtLeast(0f)).toDp()
    }
    // ★ 外观规则统一收在 ui/common/FloatingCard.kt（标题栏 / 搜索卡 / 筛选卡 / 统计粒度卡 / Dock 同一套）：
    //   · 容器色走 colorScheme 的 surface 系列 ⇒ 外观设置里的卡片透明度/明暗/玻璃自动生效；
    //   · tonalElevation 恒为 0 —— 原来这里写的 3.dp 是**空操作**（M3 只在容器色 == surface
    //     时才混 surfaceTint），见该文件"规则一"；
    //   · **卡片半透明时不给阴影** —— 用户报的"标题栏下面有一个淡淡的白色方框"就是
    //     半透明卡片下沿那圈黑色平台阴影，见该文件"规则二"。
    FloatingCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        // ★ 标题要相对**整张卡片（也就是整页）**居中，而不是在"左右按钮夹出来的
        //   剩余空间"里居中 —— 后者只要右侧有按钮（如刷新、导出），标题就会被顶得偏左，
        //   看起来就是"没居中"（用户反馈）。所以用 Box 叠放：标题绝对居中、左右按钮各自贴边，
        //   绘制顺序还是"标题先画、按钮后画"，按钮永远压在上面，可点可用。
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .onSizeChanged { if (it.width != barWidth) barWidth = it.width }
        ) {
            Box(
                Modifier
                    // 水平：撑满卡片宽度 + 按实测宽度对称留白 → 文字落在整页中线；
                    // 垂直：align(Center) → 落在卡片高度中线。
                    // 少了 align(Center) 时，它作为 Box 的默认 TopStart 子项会贴着卡片顶部
                    // （用户反馈："左右在页面中间，上下应该在卡片的中间"）。
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = reservedPerSide),
                contentAlignment = titleAlignment
            ) { title() }
            Row(
                Modifier.align(Alignment.CenterStart)
                    .onSizeChanged { if (it.width != leadingWidth) leadingWidth = it.width },
                verticalAlignment = Alignment.CenterVertically
            ) {
                navigationIcon()
            }
            Row(
                Modifier.align(Alignment.CenterEnd)
                    .onSizeChanged { if (it.width != trailingWidth) trailingWidth = it.width },
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions()
            }
        }
    }
}

/**
 * 标题栏专用标题：单行 + 超出省略。
 *
 * 见 [FloatingTopBar] 的说明：`title` 是个 slot，组件没法给调用方传进来的任意 `Text` 补
 * `maxLines`，所以约束放在这个封装里，各页面统一用它，别再手写 `Text(...)`。
 */
@Composable
fun FloatingTopBarTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** 实测按钮宽度里属于按钮自身内边距的部分（IconButton 48dp 中图标只占 24dp）。 */
private val VisualPadding = 12.dp

/** 标题与两侧按钮之间至少留出的缝。 */
private val SideGap = 4.dp

/** 标题至少保留的可读宽度：按钮再多，标题也不至于被挤成 0 宽而消失。 */
private val MinTitleWidth = 56.dp
