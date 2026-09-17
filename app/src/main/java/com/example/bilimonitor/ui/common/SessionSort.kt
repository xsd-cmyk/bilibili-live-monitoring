package com.example.bilimonitor.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.bilimonitor.data.repository.SessionSortOrder

/**
 * 一级列表（直播历史 / 统计）排序的**唯一实现**，两个页面都调这里。
 *
 * 为什么排序要抽成一份而不是各页写一行 `sortedByDescending`：排序是"两个列表看起来是不是
 * 同一份数据"的口径。同一个主播在历史页排第 3、在统计页排第 7，用户不会认为"两页用途不同"，
 * 只会认为"数据错了"—— 与 `RecentSessionText.kt`（「最近：…」只有一处字面量）是同一条理由。
 *
 * [SessionSortOrder] 的取值与默认值定义在 `AppearanceSettingsRepository.kt`：它同时是持久化的
 * 取值，放在仓库一侧才不会诱导页面各抄一份默认值。
 *
 * @param startAt 该行"最新一场"的**开播**时刻（null = 这一行还没有任何场次）
 * @param endAt   同一场的**下播**时刻（null = 还在直播，或者根本没有场次，见 [isLiveNow]）
 * @param lastClosedEndAt 该行**最近一场"有下播时间"的场次**的下播时刻（null = 从没结束过直播）。
 *   只有选项 4（[SessionSortOrder.END_TIME_LIVE_LAST_CLOSED]）读它：最新一场可能正在进行、
 *   `endTime` 为 null，光靠 [endAt] 拿不到"上一次下播是什么时候"。给默认值（恒为 null）是为了
 *   不强迫别处调用点跟着改，但**两个列表页都必须真的传** —— 不传的话在播的行在选项 4 下一律沉底，
 *   选项 4 就退化成选项 1，用户切换时看不出任何变化。
 * @param tieBreak 兜底次序，只在前面的时间键**完全相等**时生效。统计页传总时长，
 *   这样它原来的"按总时长排序"没有被丢掉，而是降级成同组内的次序（组内时间相同时仍按时长从大到小）；
 *   历史页保持默认（恒等）→ 完全等价于改动前的 `sortedByDescending { startAt }`。
 *
 * 排序是**稳定**的（`sortedWith`）：所有键都相等时保持入参顺序，也就是 DAO 的返回顺序，
 * 不会出现"每次重组顺序都在变"的抖动。
 */
fun <T> List<T>.sortedBySessionSortOrder(
    order: SessionSortOrder,
    startAt: (T) -> Long?,
    endAt: (T) -> Long?,
    lastClosedEndAt: (T) -> Long? = { null },
    tieBreak: (T) -> Long = { 0L }
): List<T> {
    // 时间缺失一律当成"最小"，于是 DESC 排序时自然沉底。
    // 用 Long.MIN_VALUE 而不是 0L：真实时间戳（System.currentTimeMillis）必为正，
    // 两者对现有数据等价，但 MIN_VALUE 表达的是"没有这个时间"，不会再被误读成"1970 年"。
    val byEndDesc = compareByDescending<T> { endAt(it) ?: Long.MIN_VALUE }
    val byStartDesc = compareByDescending<T> { startAt(it) ?: Long.MIN_VALUE }
    val byTieBreakDesc = compareByDescending<T> { tieBreak(it) }

    // 选项 4 的复合键："这一行最近一次**真正结束**直播是什么时候"。
    // 最新一场已经结束（endAt 有值）就直接用它；最新一场还在播（endAt == null）时退到
    // lastClosedEndAt ＝ 该主播最近一场有下播时间的场次，也就是"上一次下播是什么时候"。
    // 两者都没有（正在播第一场、或压根没有场次）→ MIN_VALUE，与其它选项一致地沉底。
    val byEffectiveEndDesc = compareByDescending<T> {
        endAt(it) ?: lastClosedEndAt(it) ?: Long.MIN_VALUE
    }

    val comparator = when (order) {
        // 按开播时间：H13 时用户明确要求的口径（原默认行为），现降级为选项之一。
        SessionSortOrder.START_TIME -> byStartDesc.then(byTieBreakDesc)

        // 按下播时间：严格按字面执行 —— 没下播的就没有下播时间，一律排到最后。
        // ★ 不把 null 当成"无穷大"顶到最前：那正是第 3 项（直播中置顶）在做的事，
        //   两项会变成同一个结果，用户切换时看不出任何变化。
        SessionSortOrder.END_TIME -> byEndDesc.then(byStartDesc).then(byTieBreakDesc)

        // 默认项：先按"是否在直播中"分组（在播的组排前面），组内再按下播时间从新到旧。
        SessionSortOrder.END_TIME_LIVE_FIRST ->
            compareBy<T> { if (isLiveNow(startAt(it), endAt(it))) 0 else 1 }
                .then(byEndDesc)   // 在播的一组 endAt 全为 null → 该键恒定，靠下一键排
                .then(byStartDesc) // 在播的一组内：最近开播的在上
                .then(byTieBreakDesc)

        // 选项 4：整体仍按下播时间从新到旧，但在播的行**既不置顶也不沉底** ——
        // 它拿"上一次下播的时刻"参与排序，等于在回答"这个主播最近一次真正下播是什么时候"。
        // ★ 必须与另外两项都有区别，否则用户切过来看不出变化：
        //   - 与 END_TIME 的区别：在播的行不再垫底，而是按上一场的下播时间插进中间；
        //   - 与 END_TIME_LIVE_FIRST 的区别：在播的行不再固定在最上面，名次由上一场下播时间决定。
        SessionSortOrder.END_TIME_LIVE_LAST_CLOSED ->
            byEffectiveEndDesc.then(byStartDesc).then(byTieBreakDesc)
    }
    return sortedWith(comparator)
}

/**
 * 这一行是否"正在直播"。
 *
 * ★ 必须同时要求 `startAt != null`：一个**从没被记录过场次**的主播，两个时间都是 null，
 *   只判 `endAt == null` 会把它当成"正在直播"顶到列表最上面 —— 用户看到的是
 *   "从没播过的主播排在了所有在播主播之前"，比不置顶还糟。
 */
fun isLiveNow(startAt: Long?, endAt: Long?): Boolean = startAt != null && endAt == null

/**
 * 列表排序的四选一入口（用户要求做成选项，默认第 3 种：[SessionSortOrder.DEFAULT]）。
 *
 * 放在两个列表页自己的浮动标题栏里（"排序"按钮 + 下拉），而不是塞进设置页，理由：
 *  1. 排序是"我看这张表的方式"，用户是在**看列表的那一刻**想改它，就地可切不用跳设置；
 *  2. 两个页面各自渲染同一个组件，选项文字与当前选中项天然一致，不会一页有入口一页没有；
 *  3. 设置页的"外观"卡片归外观代理维护，本页不插手可以少一处改动面。
 *
 * 按钮上只写"排序"两个字、不写当前选项：四个选项的名字都很长（"按下播时间排序，直播中置顶"），
 * 塞进浮动标题栏会把标题挤掉（`FloatingTopBar` 的留白是按两侧按钮实测宽度算的）。
 * 当前选项在下拉里用 RadioButton 标出，点开即见。
 */
@Composable
fun SessionSortSelector(
    current: SessionSortOrder,
    onSelect: (SessionSortOrder) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("排序") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SessionSortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(order.label)
                            Text(
                                order.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    // 四选一语义就用 RadioButton（与设置页"运行模式"等处一致）：
                    // 它比"选中项加个勾"更能说明"这是单选，不是开关"。
                    leadingIcon = { RadioButton(selected = order == current, onClick = null) },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    }
                )
            }
        }
    }
}
