package com.example.bilimonitor.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.repository.GroupWithCount
import com.example.bilimonitor.data.repository.TagWithCount
import com.example.bilimonitor.ui.common.FloatingCard

/**
 * 首页的搜索框 + 状态/标签/分组筛选，整体收进**一张浮动卡片**
 * （用户要求：搜索框、分组、标签这些可点按的地方也像 Dock 一样悬浮起来）。
 *
 * ## 为什么整块抽成独立文件，而不是在原页面里加一层包装
 *
 * 这个页面此前尝试过"就地包一层 `Surface { Column { ... } }`"，结果与既有外层 `Column`
 * 的括号配对错位，两次都编不过（见台账 H36）。**问题不在括号本身，而在就地手术这个做法** ——
 * 在几十行既有代码里插两个开括号、再去文件尾部找该补几个闭括号，靠肉眼数。
 *
 * 抽成新文件后，结构在本文件里一次写完整：`Surface { Column { 搜索框; 三行筛选 } }`，
 * 开闭括号在同一个屏幕内成对可见，**结构错误不可能再靠"数括号"发生**；
 * 原页面只剩一行调用。以后再改筛选区也只需动这一个文件。
 *
 * ## 视觉
 *
 * 用 `Surface` + `surfaceContainer` + 22dp 圆角 + 阴影，与浮动标题栏、Dock 同一套语言；
 * 因为取的是主题的 surface 系列，它会**自动继承**外观设置里的卡片透明度/明暗，
 * 以及"液态玻璃同步到卡片"，不必为它单独写一套玻璃逻辑。
 */
@Composable
fun HomeFilterCard(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: HomeFilter,
    onFilterChange: (HomeFilter) -> Unit,
    /** 已选中的标签 id（**多选**，多选之间取交集 —— 用户定稿）。空集 = 不限标签。 */
    tagFilterIds: Set<Long>,
    /**
     * 报告"用户点了哪个标签"：传 id = 切换这个标签的选中态；传 null = 点的是「全部标签」，
     * 清空整个标签条件。
     *
     * 切换逻辑在 HomeViewModel.setTagFilter 里，这里**不自己算**选中/取消：
     * 上一版由 chip 自己写 `if (已选中) null else id`，单选下恰好等价，
     * 多选下就变成"每点一次整组选择被覆盖成单个 id"。
     */
    onTagFilterChange: (Long?) -> Unit,
    groups: List<GroupWithCount>,
    groupFilterId: Long?,
    onGroupFilterChange: (Long?) -> Unit,
    tags: List<TagWithCount>,
    /** 展开/收起由调用方（HomeViewModel）持有，这样切 Tab 回来仍是上次的状态 */
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // 可收起（用户要求）：筛选区占了近半屏，看列表时希望它让位。
    // 状态由 HomeViewModel 持有（用户要求：记住上次离开时的状态）——
    // 用 rememberSaveable 只在配置变更/同级重建时保留，切到别的 Tab 再回来会丢。

    // 收起后仍要一眼看出"现在筛的是什么"，否则用户会以为筛选失效了。
    //
    // 选中的标签名按**标签列表自身的顺序**取（`tags.filter { ... }` 的结果天然就是
    // 展示顺序），刻意不遍历 tagFilterIds 这个 Set：Set 的迭代顺序是实现细节
    // （HashSet 依哈希排列），同一组选择在不同进程里可能排出不同顺序，
    // 收起态的摘要文字就会毫无理由地跳来跳去。
    val selectedTagNames = tags.filter { it.id in tagFilterIds }.map { it.name }
    val summary = buildString {
        append(filter.label)
        groups.firstOrNull { it.id == groupFilterId }?.let { append(" · 分组「").append(it.name).append("」") }
        if (selectedTagNames.isNotEmpty()) {
            append(" · 标签「").append(selectedTagNames.joinToString("、")).append("」")
        }
        if (query.isNotBlank()) append(" · 搜索「").append(query).append("」")
    }

    // ★ 与浮动标题栏 / 搜索卡 / Dock 同一套外观规则，规则本体在 ui/common/FloatingCard.kt：
    //   tonalElevation 恒为 0、**卡片半透明时不给阴影** —— 后者就是用户报的
    //   "搜索框（筛选卡）下面还有一个淡淡的白色方框"：半透明卡片下沿那圈黑色平台阴影，
    //   在复杂背景图上会和卡片本体一起显形，原因见该文件"规则二"。
    FloatingCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        // 层次用**颜色角色**表达，而不是高度色调：原来是 surfaceContainer + tonalElevation = 3.dp，
        // 而 M3 只在 color == colorScheme.surface 时才叠加色调层（surfaceColorAtElevation），
        // 容器色一旦是 surface 系列，那个 elevation 就是空操作，想要的"再高一层"根本没生效。
        // surfaceContainerHigh 仍属于 surface 系列，外观设置里的卡片透明度/明暗/玻璃同步照旧生效。
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            // 整行可点，而不是只有小箭头能点
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "收起筛选" else summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                placeholder = { Text("搜索主播昵称") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            Spacer(Modifier.height(4.dp))

            // 状态筛选（全部 / 直播中 / 未开播 / 收藏）
            // 用可滚动容器：这一行原先是不带滚动的 Row，窄屏 + 大字体下第 4 个 chip「收藏」
            // 会被推出屏幕且没有任何办法滚到 —— 与标签/分组两行同一套处理。
            ScrollableChipRow(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                HomeFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { onFilterChange(f) },
                        label = { Text(f.label) }
                    )
                }
            }

            // 标签筛选（用户定稿：**同级多选**，多选之间取交集）。
            // 这一行与下面的分组行是两种不同的选择模型，所以走两个不同的 Composable：
            // 标签行只吃集合、分组行只吃单个 id。"分组不可多选"因此在类型上就写不出来，
            // 不依赖调用方自觉。
            MultiSelectChipRow(
                allLabel = "全部标签",
                allSelected = tagFilterIds.isEmpty(),
                onAllClick = { onTagFilterChange(null) },
                visible = tags.isNotEmpty(),
                items = tags.map { ChipItem(it.id, it.name, it.count) },
                selectedIds = tagFilterIds,
                onItemClick = { id -> onTagFilterChange(id) }
            )

            // 分组筛选（原规范 16）：**仍是单选**（[FilterChipRow] 的 selectedId 就是单个 id）。
            // 与标签不再是互斥关系 —— 两个维度可以同时生效，语义是 AND
            // （先落在这个分组里、再满足标签条件），见 HomeViewModel.setTagFilter()。
            FilterChipRow(
                allLabel = "全部分组",
                allSelected = groupFilterId == null,
                onAllClick = { onGroupFilterChange(null) },
                visible = groups.isNotEmpty(),
                items = groups.map { ChipItem(it.id, it.name, it.count) },
                selectedId = groupFilterId,
                onItemClick = { id -> onGroupFilterChange(if (groupFilterId == id) null else id) }
            )
            }   // if (expanded)
        }
    }
}

/** chip 行的一项（把标签/分组统一成同一形状，两行共用一套渲染）。 */
private data class ChipItem(val id: Long, val name: String, val count: Int)

/**
 * 一行"可横向滚动的筛选 chip"。
 *
 * 标签可以无限创建，整行塞不下时原先会把后面的 chip 直接挤出屏幕**且无法滚到**
 * （用户就再也点不到某个标签）。所以「全部X」固定在左侧，其余横向滚动 —— 随时能取消筛选。
 */
@Composable
private fun FilterChipRow(
    allLabel: String,
    allSelected: Boolean,
    onAllClick: () -> Unit,
    visible: Boolean,
    items: List<ChipItem>,
    selectedId: Long?,
    onItemClick: (Long) -> Unit
) {
    if (!visible) return
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = allSelected,
            onClick = onAllClick,
            label = { Text(allLabel) }
        )
        Spacer(Modifier.width(8.dp))
        ScrollableChipRow {
            items.forEach { item ->
                FilterChip(
                    selected = selectedId == item.id,
                    onClick = { onItemClick(item.id) },
                    label = { Text("${item.name}(${item.count})") }
                )
            }
        }
    }
}

/**
 * 一行"可横向滚动的**多选**筛选 chip"（标签行专用，用户要求：标签支持同级多选）。
 *
 * ## 为什么新增一个，而不是把 [FilterChipRow] 的 `selectedId: Long?` 改成集合两行共用
 *
 * 分组被明确要求保持单选。若两行共用同一个"集合版"入口，分组行在**类型上也允许**传多个 id，
 * "分组不可同级多选"就只剩调用方的自觉 —— 违反了编译器不会吭声，而这恰恰是被排除的用法。
 * 拆成两个各自收窄的入口后，标签行只能传集合、分组行只能传单个 id，误用直接编译不过，
 * 而不是等到线上才发现分组被多选了。
 *
 * 其余全部沿用 [FilterChipRow]：左端「全部X」固定不动、其余横向滚动（标签可以无限创建，
 * 塞不下就点不到），chip 的类型、间距、选中态也一模一样 —— 两行看上去必须是同一套控件，
 * 区别只在选择模型。
 */
@Composable
private fun MultiSelectChipRow(
    allLabel: String,
    allSelected: Boolean,
    onAllClick: () -> Unit,
    visible: Boolean,
    items: List<ChipItem>,
    selectedIds: Set<Long>,
    onItemClick: (Long) -> Unit
) {
    if (!visible) return
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = allSelected,
            onClick = onAllClick,
            label = { Text(allLabel) }
        )
        Spacer(Modifier.width(8.dp))
        ScrollableChipRow {
            items.forEach { item ->
                FilterChip(
                    // 每个 chip 各自判断自己在不在选中集合里 —— 多选的表现就只有这一处差别
                    selected = item.id in selectedIds,
                    onClick = { onItemClick(item.id) },
                    label = { Text("${item.name}(${item.count})") }
                )
            }
        }
    }
}

/**
 * 一行可横向滚动的 chip，状态/标签/分组三行共用。
 *
 * 抽成公共容器是因为"塞不下就点不到"这个坑三行都会踩：不可滚动的 Row 会把右侧 chip
 * 推到屏幕外，用户没有任何手势能滚到它（标签可以无限创建，状态行在大字体下也一样）。
 * 传进来的 [modifier] 作用在滚动之前，于是横向 padding 属于视口自身 ——
 * chip 不会贴着卡片边缘被裁掉。
 */
@Composable
private fun ScrollableChipRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
