package com.example.bilimonitor.data.repository

import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.dao.LiveSessionDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

enum class StatsGranularity(val label: String) { DAY("按日"), WEEK("按周"), MONTH("按月"), YEAR("按年") }

data class SeriesPoint(val label: String, val seconds: Long, val sessionCount: Int)

data class StreamerSeries(
    val streamerId: Long,
    val name: String,
    val points: List<SeriesPoint>,
    val totalSeconds: Long,
    val sessionCount: Int
)

data class StatsSeriesResult(
    val streamerId: Long,
    val streamerName: String,
    val granularity: StatsGranularity,
    val bucketValue: String?,
    val customStart: Long?,
    val customEnd: Long?,
    val periodStart: Long,
    val periodEnd: Long,
    val bucketLabels: List<String>,
    val totals: StatsTotals,
    val points: List<SeriesPoint>
)

/**
 * 一级页面的主播统计卡片汇总（可按名字过滤）。
 *
 * [sessionCount] / [totalSeconds] 只统计**已结束**的场次：进行中的场次还没有最终时长，
 * 计进去就是假数据。而下面两个"最近"字段必须看得到进行中的那一场（见 StatsListScreen 的 combine），
 * 口径与直播历史一级页完全一致。
 */
data class StreamerStatsSummary(
    val streamerId: Long,
    val name: String,
    val avatarUrl: String?,
    val sessionCount: Int,
    val totalSeconds: Long,
    /** 最新一场的**开播**时刻：排序键之一（选项 2「按开播时间」的主键），直播中时也用于显示"开播 …"。 */
    val latestSessionStartAt: Long?,
    /** 最新一场的**下播**时刻，卡片上「最近：…」显示的它；null = 这一场还在进行中。 */
    val latestSessionEndAt: Long?
)

/**
 * 统计序列（用户定稿）：按主播独立统计；粒度支持 日 / 周 / 月 / 年。
 * （直接由场次计算；总量缓存沿用 compute() 的口径，序列不做缓存 —— 数据量级小，实时算更直观。）
 */
@Singleton
class StatsSeriesRepository @Inject constructor(
    private val liveSessionDao: LiveSessionDao,
    private val streamerDao: StreamerDao,
    private val clock: AppClock
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFmt = DateTimeFormatter.ofPattern("MM-dd")
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM")

    // 这里曾有一个 streamerSummaries(nameQuery)：零调用者，且实现是
    // `liveSessionDao.listAll()` 全表载入后再在内存里分组 —— 数据量一大就是纯浪费。
    // 一级页面的汇总现在由 StatsListScreen 自己的 combine 管线算（见 StatsListScreen:145）。
    // 数据类 StreamerStatsSummary 仍在使用，保留。

    suspend fun allBucketValues(g: StatsGranularity): List<String> {
        val sessions = liveSessionDao.listAll().filter { it.endTime != null }
        return sessions.map { bucketKeyOf(it, g) }.distinct().sortedDescending()
    }

    /** 桶值 → 区间 [start, end)。 */
    private fun boundsOf(startDay: LocalDate, endDayExclusive: LocalDate): Pair<Long, Long> =
        Pair(
            startDay.atStartOfDay(zone).toInstant().toEpochMilli(),
            endDayExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        )

    fun periodBounds(g: StatsGranularity, value: String): Pair<Long, Long> {
        return when (g) {
            StatsGranularity.DAY -> {
                val d = LocalDate.parse(value)
                boundsOf(d, d.plusDays(1))
            }
            StatsGranularity.WEEK -> {
                val monday = LocalDate.parse(value)
                boundsOf(monday, monday.plusDays(7))
            }
            StatsGranularity.MONTH -> {
                val first = LocalDate.parse(value + "-01")
                boundsOf(first, first.plusMonths(1))
            }
            StatsGranularity.YEAR -> {
                val first = LocalDate.parse(value + "-01-01")
                boundsOf(first, first.plusYears(1))
            }
        }
    }

    fun currentBucketValue(g: StatsGranularity): String =
        bucketKeyOf(com.example.bilimonitor.core.AppClocks.today(), g, null)

    /** 单主播统计序列：粒度 + 桶值。 */
    suspend fun seriesFor(streamerId: Long, g: StatsGranularity, value: String): StatsSeriesResult {
        val bounds = periodBounds(g, value)
        return computeFor(streamerId, bounds.first, bounds.second, g, value, null, null, null, null)
    }

    /**
     * 单主播统计序列：自定义起止日期。跨度超过 92 天自动按月分桶。
     *
     * ★ 口径（[startInclusive, endExclusive) 半开区间，**必需**）：
     *   [startInclusive] = 用户所选**开始日**当天 00:00 的毫秒；
     *   [endExclusive]   = 用户所选**结束日之后一天** 00:00 的毫秒 —— 即"结束日次日 00:00"。
     *
     * 为什么必须是半开区间：本方法把 [endExclusive] 直接交给
     * `liveSessionDao.listClosedBetween(from, to)`，而该 SQL 用的是 `>= :from AND < :to`
     * （MonitorDaos.kt 的 listClosedBetween，排他上界）。
     * 这里原先收的是"结束日当天 00:00"，配上 `< :to` 就把**整个结束日**排除在外 ——
     * 用户选到"今天"时，今天的场次一场都进不来；而且 `days` 被多算一天，
     * 图表末尾会多出一根永远为空的柱子、总计与柱状图对不上（台账 B20）。
     *
     * 调用方若要传"结束日当天 00:00"（用户界面的自然口径），
     * 必须先用 `inclusiveDayRange(...)`（ui/common/TimeRange.kt）换算成次日 00:00。
     * 换算只此一处，别在页面里各写一份 `plusDays(1)` —— 口径分叉正是 B20 的成因。
     */
    suspend fun seriesCustom(streamerId: Long, startInclusive: Long, endExclusive: Long): StatsSeriesResult {
        val start = startInclusive
        val end = endExclusive
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        // 上界是排他的：end 是"结束日**次日** 00:00"（口径见上面的说明），它取到的自然日就是
        // 最后一天的下一天 —— 因此下面两者之差正好等于区间覆盖的整天数。
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        // ★ 天数按**自然日**算（ChronoUnit.DAYS.between），不再用 (end - start) / 86_400_000L：
        //   ① 夏令时切换日只有 23/25 小时，除固定毫秒会多算或少算一天 —— TimeRange.kt 已为
        //      同一类错误立过规矩：不能用 `+86_400_000L` 代替 `plusDays(1)`；
        //   ② 下界可能落在当天中间（"全部"范围以首场开播时刻兜底，from = 0 时即如此），
        //      整除会把跨午夜的最后一整天截掉，末尾场次随即被 indexOf 的 coerceIn 夹进
        //      前一个桶 —— 9/13 23:00 与 9/14 01:00 两场只生成一个桶，9/14 的落进 9/13。
        // 半开区间 [start, end) 覆盖的整天数就是 labels 的个数，不能 +1
        // （原先的 +1 会给图表多塞一根空柱，并让末桶把最后两个自然日并成一根）。
        val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt().coerceAtLeast(0)
        return if (days > 92) {
            val first = startDate.withDayOfMonth(1)
            // 上界是排他的：end 本身已经是"结束日的次日 00:00"，因此最后一个自然日是 end - 1ms。
            // 原先直接拿 end 取月，会多算一个月（用户选到 1/31 时按月分桶会出现 2 月的空柱）。
            val last = Instant.ofEpochMilli(end - 1L).atZone(zone).toLocalDate().withDayOfMonth(1)
            val labels = generateSequence(first) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(last) }
                .map { it.format(monthFmt) }
                .toList()
            val monthIndexer: (com.example.bilimonitor.data.local.entity.LiveSessionEntity) -> Int = { ses ->
                val t = ses.startTime ?: ses.createdAt
                val ym = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().withDayOfMonth(1)
                java.time.temporal.ChronoUnit.MONTHS.between(first, ym).toInt()
            }
            computeFor(streamerId, start, end, StatsGranularity.MONTH, null, start, end, labels, monthIndexer)
        } else {
            // 标签用 plusDays 逐个生成（而不是 start + it * 86_400_000L）：夏令时切换日加固定
            // 毫秒会偏一小时，标签就会与真实自然日错位，柱子内容和标题对不上。
            val labels = (0 until days).map { startDate.plusDays(it.toLong()).format(dayFmt) }
            val dayIndexer: (com.example.bilimonitor.data.local.entity.LiveSessionEntity) -> Int = { ses ->
                val t = ses.startTime ?: ses.createdAt
                val d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
                java.time.temporal.ChronoUnit.DAYS.between(startDate, d).toInt()
            }
            computeFor(streamerId, start, end, StatsGranularity.DAY, null, start, end, labels, dayIndexer)
        }
    }

    private suspend fun computeFor(
        streamerId: Long,
        start: Long,
        end: Long,
        g: StatsGranularity,
        value: String?,
        customStart: Long?,
        customEnd: Long?,
        explicitLabels: List<String>?,
        explicitIndexer: ((com.example.bilimonitor.data.local.entity.LiveSessionEntity) -> Int)?
    ): StatsSeriesResult {
        val sessions = liveSessionDao.listClosedBetween(start, end)
            .filter { it.streamerId == streamerId }
        val streamer = streamerDao.findById(streamerId)
        val labels = explicitLabels ?: bucketLabels(g, value ?: "")

        // 分桶下标：显式索引函数优先；否则按粒度内置规则计算。
        // 注意 WEEK 不能用 MM-dd 标签反解析日期（无年份）—— 用区间起点做日期差。
        fun indexOf(s: com.example.bilimonitor.data.local.entity.LiveSessionEntity): Int {
            explicitIndexer?.let { return it(s) }
            // 与查询口径保持一致：按**开播时间**分桶（缺失时退回创建时间）。
            // 原先用的是 endTime，于是"23:50 开播、次日 00:30 结束"会被算进第二天。
            val t = s.startTime ?: s.createdAt
            val zoned = Instant.ofEpochMilli(t).atZone(zone)
            val i = when (g) {
                StatsGranularity.DAY -> zoned.hour
                StatsGranularity.WEEK -> java.time.temporal.ChronoUnit.DAYS.between(
                    Instant.ofEpochMilli(start).atZone(zone).toLocalDate(),
                    zoned.toLocalDate()
                ).toInt()
                StatsGranularity.MONTH -> zoned.dayOfMonth - 1
                StatsGranularity.YEAR -> zoned.monthValue - 1
            }
            // 落在区间外的场次（例如"全部"范围下起点早于首桶）**夹取到最近的桶**，
            // 而不是返回 -1 被丢弃：原实现把它们算进 totals 却不进任何柱子，
            // 同一页面的"总计"与柱状图自相矛盾（台账 H19-B9）。
            return i.coerceIn(0, labels.lastIndex.coerceAtLeast(0))
        }

        val points = labels.mapIndexed { i, label ->
            val inBucket = sessions.filter { indexOf(it) == i }
            SeriesPoint(label, inBucket.sumOf { it.durationSeconds ?: 0L }, inBucket.size)
        }

        // 与原 StatsRepository.compute 的口径保持一致：
        //  - excludedSessionCount：同一区间内**尚未结束**（或无法判定结束）的场次数 ——
        //    原先的实现是"再跑一遍同一个查询再相减"，两者完全相同，于是恒为 0，
        //    统计页与导出里的"已排除"是一个永远输出 0 的假指标（台账 H19-C8）。
        //    现在改为真的去数"区间内未结束"的场次。
        //  - reliableMonitoredSeconds：开播或关播可信度为 CORRECTED（人工修正）的场次时长之和。
        val excluded = liveSessionDao.countUnclosedForStreamerBetween(streamerId, start, end)
        val reliableSeconds = sessions
            .filter {
                it.startConfidence == com.example.bilimonitor.data.local.DataConfidence.CORRECTED ||
                    it.endConfidence == com.example.bilimonitor.data.local.DataConfidence.CORRECTED
            }
            .sumOf { it.durationSeconds ?: 0L }

        val totals = StatsTotals(
            sessionCount = sessions.size.toLong(),
            confirmedSessionCount = sessions.count { it.startConfidence == com.example.bilimonitor.data.local.DataConfidence.CONFIRMED }.toLong(),
            provisionalSessionCount = sessions.count { it.startConfidence == com.example.bilimonitor.data.local.DataConfidence.PROVISIONAL }.toLong(),
            correctedSessionCount = sessions.count { it.startConfidence == com.example.bilimonitor.data.local.DataConfidence.CORRECTED }.toLong(),
            excludedSessionCount = excluded.toLong(),
            monitoredSeconds = sessions.sumOf { it.durationSeconds ?: 0L },
            reliableMonitoredSeconds = reliableSeconds
        )

        return StatsSeriesResult(
            streamerId = streamerId,
            streamerName = streamer?.name ?: "未知主播",
            granularity = g,
            bucketValue = value,
            customStart = customStart,
            customEnd = customEnd,
            periodStart = start,
            periodEnd = end,
            bucketLabels = labels,
            totals = totals,
            points = points
        )
    }

    /** 一个桶内的细分标签：按日 = 0~23 时；按周 = 周一至周日；按月 = 1~当月天数；按年 = 1~12 月。 */
    private fun bucketLabels(g: StatsGranularity, value: String): List<String> = when (g) {
        StatsGranularity.DAY -> (0..23).map { "${it}时" }
        StatsGranularity.WEEK -> {
            val monday = LocalDate.parse(value)
            (0..6).map { monday.plusDays(it.toLong()).format(dayFmt) }
        }
        StatsGranularity.MONTH -> {
            val first = LocalDate.parse("$value-01")
            val days = first.lengthOfMonth()
            (1..days).map { it.toString() }
        }
        StatsGranularity.YEAR -> (1..12).map { "${it}月" }
    }

    private fun bucketKeyOf(session: com.example.bilimonitor.data.local.entity.LiveSessionEntity, g: StatsGranularity): String {
        // 与统计口径一致：用开播时间归属周期（原先用 endTime，会让 23:50 开播的场次
        // 在"可选周期"列表里落到第二天，而它的时长却算在第一天）。
        val t = session.startTime ?: session.endTime ?: session.createdAt
        return bucketKeyOf(t, g)
    }

    private fun bucketKeyOf(epochMillis: Long, g: StatsGranularity): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return bucketKeyOf(date, g, epochMillis)
    }

    private fun bucketKeyOf(date: LocalDate, g: StatsGranularity, epochMillis: Long?): String = when (g) {
        StatsGranularity.DAY -> date.toString()
        StatsGranularity.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        StatsGranularity.MONTH -> date.format(monthFmt)
        StatsGranularity.YEAR -> date.year.toString()
    }
}
