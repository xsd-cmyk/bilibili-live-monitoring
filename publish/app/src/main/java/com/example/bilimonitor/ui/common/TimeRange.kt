package com.example.bilimonitor.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 时间范围的口径定义与选择组件。
 *
 * 历史页筛选与导出共用**同一套**周期语义（按日/按周/按月/按年/自定义），
 * 避免出现"页面上筛出来的范围"和"导出出去的范围"算法不一致这种最难查的问题。
 * 原先这套逻辑私有在 `HistoryScreen` 里，导出需要它，因此提取到 common。
 */
enum class HistoryFilterType(val label: String) {
    ALL("全部"), DAY("按日"), WEEK("按周"), MONTH("按月"), YEAR("按年"), CUSTOM("自定义")
}

data class HistoryFilter(
    val type: HistoryFilterType = HistoryFilterType.ALL,
    val value: String? = null,
    val customStart: Long? = null,
    val customEnd: Long? = null
)

/** 周期 → [起, 止) 的毫秒边界；返回 null 表示"不限制时间"。 */
fun HistoryFilter.bounds(): Pair<Long, Long>? {
    val zone = java.time.ZoneId.systemDefault()
    fun dayBounds(d: java.time.LocalDate): Pair<Long, Long> =
        Pair(
            d.atStartOfDay(zone).toInstant().toEpochMilli(),
            d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
    return when (type) {
        HistoryFilterType.ALL -> null
        HistoryFilterType.DAY -> dayBounds(java.time.LocalDate.parse(value ?: return null))
        HistoryFilterType.WEEK -> {
            val monday = java.time.LocalDate.parse(value ?: return null)
            Pair(
                monday.atStartOfDay(zone).toInstant().toEpochMilli(),
                monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
        HistoryFilterType.MONTH -> {
            val first = java.time.LocalDate.parse((value ?: return null) + "-01")
            Pair(
                first.atStartOfDay(zone).toInstant().toEpochMilli(),
                first.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
        HistoryFilterType.YEAR -> {
            val first = java.time.LocalDate.parse((value ?: return null) + "-01-01")
            Pair(
                first.atStartOfDay(zone).toInstant().toEpochMilli(),
                first.plusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
        }
        HistoryFilterType.CUSTOM -> {
            // customStart / customEnd 存的是**所选当天 00:00**（见 DateParts.toEpoch）；
            // 直接拿 customEnd 当上界就是"结束日 00:00"，会漏掉整个结束日（台账 B20）。
            // 换算统一走 inclusiveDayRange()，与统计二级页/导出同源。
            val s = customStart ?: return null
            val e = customEnd ?: return null
            inclusiveDayRange(s, e)
        }
    }
}

/**
 * 「自定义」区间的**唯一权威换算**：两个"含当天"的日期边界（各自当天 00:00 的毫秒）
 * → 查询层用的 **[起, 止) 半开区间**（止 = 结束日次日 00:00）。
 *
 * 全项目时间口径（**这段注释就是包含性契约；改这里就要同步统计二级页与导出**）：
 *  - UI 侧：[HistoryFilter.customStart] / [HistoryFilter.customEnd]、`DateParts.toEpoch()`、
 *    对话框标签「结束日期（含当天）」—— 语义一律是**含结束日整天**，
 *    值是所选日期**当天 00:00** 的毫秒；
 *  - 查询侧：DAO 的 `COALESCE(startTime, createdAt) >= :from AND < :to`
 *    （`LiveSessionDao.listClosedBetween` / `listClosedForStreamersBetween`）、
 *    `StatsSeriesRepository.seriesCustom(start, end)` —— 一律是 **[起, 止) 半开区间**，
 *    上界**排他**。
 *
 * 两边的"结束日"含义相差一天，必须显式换算。本函数是唯一换算点：
 * [bounds] 的 CUSTOM 分支、统计二级页的自定义区间（StatsScreen/StreamerStatsViewModel）都走它，
 * 别再在页面里另写一份 `plusDays(1)` —— 口径分叉正是这个缺陷（台账 B20）的成因：
 * 统计二级页原先把"结束日 00:00"当成排他上界直接下传，`< :to` 便把整个结束日排除，
 * 用户选到"今天"时当天的记录一条都不出现，图表末尾还多出一根永远为空的柱子。
 *
 * 时区沿用**设备时区**（与 [bounds] 的其它分支、`DateParts`、`AppClocks.today()` 同一口径：
 * `ZoneId.systemDefault()`），不另造时区处理。注意**不能**用 `+86_400_000L` 代替
 * `plusDays(1)`：夏令时切换日一天不是 24 小时，加固定毫秒会偏一小时，进而漏掉/多算记录。
 */
fun inclusiveDayRange(startInclusive: Long, endInclusive: Long): Pair<Long, Long> {
    val zone = java.time.ZoneId.systemDefault()
    fun startOfDay(t: Long) =
        java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
    return Pair(
        startOfDay(startInclusive),
        // 结束日 → 次日 00:00（半开区间的排他上界），这样"结束日整天"都落在 [起, 止) 内
        java.time.Instant.ofEpochMilli(endInclusive).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    )
}

/** 供界面显示的范围描述。 */
fun HistoryFilter.label(): String = when (type) {
    HistoryFilterType.ALL -> "全部"
    HistoryFilterType.DAY -> "按日 · $value"
    HistoryFilterType.WEEK -> "按周 · 自 $value 起的一周"
    HistoryFilterType.MONTH -> "按月 · $value"
    HistoryFilterType.YEAR -> "按年 · $value 年"
    HistoryFilterType.CUSTOM -> "自定义 · ${formatRangeTime(customStart)} ~ ${formatRangeTime(customEnd)}"
}

/**
 * 导出用边界：把"全部"翻译成整个时间轴，其余与 [bounds] 完全一致。
 *
 * [bounds] 返回 null 表示"不限制时间"，对筛选而言就是不加条件；
 * 但导出必须拿到具体区间才能查库，因此这里给"全部"一个全开区间。
 */
fun HistoryFilter.exportBounds(): Pair<Long, Long>? = when (type) {
    HistoryFilterType.ALL -> 0L to Long.MAX_VALUE
    else -> bounds()
}

/** 本次选择是否已经可以拿去查询（自定义与按日等都需要具体日期）。 */
fun HistoryFilter.isUsable(): Boolean = when (type) {
    HistoryFilterType.ALL -> true
    HistoryFilterType.CUSTOM -> customStart != null && customEnd != null
    else -> value != null
}

private val rangeTimeFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatRangeTime(v: Long?): String = v?.let {
    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(rangeTimeFmt)
} ?: "—"

private fun deriveKeyFromCalendarDate(date: java.time.LocalDate, type: HistoryFilterType): String =
    when (type) {
        HistoryFilterType.WEEK ->
            date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString()
        HistoryFilterType.MONTH -> String.format("%04d-%02d", date.year, date.monthValue)
        HistoryFilterType.YEAR -> date.year.toString()
        else -> date.toString()
    }

/**
 * 时间范围选择对话框：按日/按周/按月/按年（打开日历选任意一天，自动归入所在周/月/年）
 * 或自定义起止日期。
 *
 * @param allowAll 是否提供"全部"（筛选需要，导出不需要 —— 导出必须明确范围）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeDialog(
    current: HistoryFilter,
    onApply: (HistoryFilter) -> Unit,
    onDismiss: () -> Unit,
    title: String = "选择时间范围",
    confirmLabel: String = "确定",
    allowAll: Boolean = false
) {
    val types = remember(allowAll) {
        HistoryFilterType.entries.filter { allowAll || it != HistoryFilterType.ALL }
    }
    var type by remember { mutableStateOf(if (current.type == HistoryFilterType.ALL && !allowAll) HistoryFilterType.DAY else current.type) }
    var value by remember { mutableStateOf(current.value) }
    val customStartParts = remember { DateParts().apply { setEpochDate(current.customStart) } }
    val customEndParts = remember { DateParts().apply { setEpochDate(current.customEnd) } }
    var showCalendar by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun valueDescription(v: String?): String = when {
        v == null -> "未选择"
        type == HistoryFilterType.WEEK -> "自 $v 起的一周"
        type == HistoryFilterType.YEAR -> "$v 年"
        else -> v
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                types.forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            type = t
                            // 换类型就把上一次的报错清掉：error 只在点"确定"时写入，
                            // 不清就会一直挂着，和上面"已选：xxx"的现状互相矛盾
                            // （例如已补好日期，还显示"请先打开日历选择日期"）。
                            error = null
                            if (t == HistoryFilterType.ALL || t == HistoryFilterType.CUSTOM) value = null
                        }
                    ) {
                        RadioButton(selected = type == t, onClick = {
                            type = t
                            error = null
                            if (t == HistoryFilterType.ALL || t == HistoryFilterType.CUSTOM) value = null
                        })
                        Text(t.label)
                    }
                }
                if (type in setOf(
                        HistoryFilterType.DAY, HistoryFilterType.WEEK,
                        HistoryFilterType.MONTH, HistoryFilterType.YEAR
                    )
                ) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已选：${valueDescription(value)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showCalendar = true }) { Text("打开日历") }
                    }
                    Text(
                        when (type) {
                            HistoryFilterType.WEEK -> "选择任意一天，自动定位到该天所在的一周（周一起算）"
                            HistoryFilterType.MONTH -> "选择该月任意一天"
                            HistoryFilterType.YEAR -> "选择该年任意一天"
                            else -> "选择具体日期"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (type == HistoryFilterType.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    // onChanged 里清旧报错：用户补齐/改正日期后，"请完整填写…"这类提示必须立刻消失，
                    // 否则它和下面已填好的日期自相矛盾。
                    DateFields(
                        label = "开始日期", parts = customStartParts,
                        modifier = Modifier.fillMaxWidth(), onChanged = { error = null }
                    )
                    DateFields(
                        label = "结束日期（含当天）", parts = customEndParts,
                        modifier = Modifier.fillMaxWidth(), onChanged = { error = null }
                    )
                }
                if (type == HistoryFilterType.CUSTOM &&
                    ((customStartParts.isComplete() && customStartParts.toEpoch() == null) ||
                        (customEndParts.isComplete() && customEndParts.toEpoch() == null))
                ) {
                    Text("存在无效日期（如 2 月 30 日），请检查", color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (type) {
                    HistoryFilterType.ALL -> onApply(HistoryFilter())
                    HistoryFilterType.CUSTOM -> {
                        val s = if (customStartParts.isComplete()) customStartParts.toEpoch() else null
                        val e = if (customEndParts.isComplete()) customEndParts.toEpoch() else null
                        if (s == null || e == null) {
                            error = "请完整填写开始和结束的年月日"; return@TextButton
                        }
                        if (e < s) {
                            error = "结束日期不能早于开始日期"; return@TextButton
                        }
                        onApply(HistoryFilter(HistoryFilterType.CUSTOM, null, s, e))
                    }
                    else -> {
                        if (value == null) {
                            error = "请先打开日历选择日期"; return@TextButton
                        }
                        onApply(HistoryFilter(type, value))
                    }
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showCalendar) {
        val initialMillis = runCatching {
            val date = value?.let { v ->
                when (type) {
                    HistoryFilterType.MONTH ->
                        java.time.YearMonth.parse(v, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")).atDay(1)
                    HistoryFilterType.YEAR -> java.time.LocalDate.of(v.toInt(), 1, 1)
                    else -> java.time.LocalDate.parse(v)
                }
            } ?: java.time.LocalDate.now()
            date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickerState.selectedDateMillis?.let {
                        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    }
                    if (date != null) value = deriveKeyFromCalendarDate(date, type)
                    // 选完日期即清掉"请先打开日历选择日期"这类旧报错
                    error = null
                    showCalendar = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("取消") } }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
