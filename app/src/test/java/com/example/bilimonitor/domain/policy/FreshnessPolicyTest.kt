package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.MonitoringMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据新鲜度（「数据过期」）判定单测。
 *
 * ## 为什么这块必须有单测（而且只能靠单测）
 * 用户报的缺陷是"引擎停跑后「数据过期」永不出现、时间却一直涨"，它的成因是
 * **判定读了一个"写入时刻的快照"**；修复把判定改成"用当前时间现算"，
 * 而"当前时间"在真机上是没法回拨的 —— 只有把判定抽成纯函数（本文件的被测对象）
 * 才能把"多久之后算过期""省电模式会不会永远显示过期""封禁中到底标不标"
 * 这些边界逐条钉死。修复前这块的覆盖率是 **0**（`app/src` 下搜
 * `relativeTime|freshnessStatus|数据过期` 命中 0）。
 */
class FreshnessPolicyTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    /** 常规监控：60 秒一轮，阈值 5 分钟（默认配置）。 */
    private fun expired(
        elapsedMillis: Long,
        thresholdSeconds: Int = FreshnessPolicy.DEFAULT_THRESHOLD_SECONDS,
        tickPeriodSeconds: Int = 60,
        banned: Boolean = false,
        lastConfirmedAt: Long? = now - elapsedMillis,
        lastCheckedAt: Long? = now - elapsedMillis
    ): Boolean = FreshnessPolicy.isDataExpired(
        now = now,
        lastConfirmedAt = lastConfirmedAt,
        lastCheckedAt = lastCheckedAt,
        thresholdSeconds = thresholdSeconds,
        tickPeriodSeconds = tickPeriodSeconds,
        banned = banned
    )

    // ---- 1) 阈值边界 ----

    @Test
    fun `未到阈值不判过期`() {
        assertFalse(expired(elapsedMillis = 4 * minute))
    }

    @Test
    fun `超过阈值判过期`() {
        assertTrue(expired(elapsedMillis = 5 * minute + 1))
    }

    @Test
    fun `恰好等于阈值不过期（严格大于才过期）`() {
        // 与 freshnessOf 的 `<=` 判 FRESH 严丝合缝：不会出现"列说新鲜、界面说过期"
        assertFalse(expired(elapsedMillis = 5 * minute))
        assertEquals(
            DataFreshness.FRESH,
            FreshnessPolicy.freshnessOf(now - 5 * minute, now, 300)
        )
        // 多 1 毫秒就过期，且此时存储列也会写 STALE —— 两者同步翻转
        assertTrue(expired(elapsedMillis = 5 * minute + 1))
        assertEquals(
            DataFreshness.STALE,
            FreshnessPolicy.freshnessOf(now - 5 * minute - 1, now, 300)
        )
    }

    @Test
    fun `阈值下限与上限都能正常判定`() {
        // ★ 这里必须把周期传 0：既有校验允许 30–86400 秒（ConfigRepository.merge），
        //   而有效阈值 = max(阈值, 2 × 周期) —— 周期 60 秒时 30 秒的阈值会被抬到 120 秒，
        //   测的就不是"阈值本身"了（阈值与周期的协调另有专门的用例）。
        assertFalse(expired(elapsedMillis = 30_000, thresholdSeconds = 30, tickPeriodSeconds = 0))
        assertTrue(expired(elapsedMillis = 31_000, thresholdSeconds = 30, tickPeriodSeconds = 0))
        assertFalse(expired(elapsedMillis = 86_400_000L, thresholdSeconds = 86_400, tickPeriodSeconds = 0))
        assertTrue(expired(elapsedMillis = 86_400_001L, thresholdSeconds = 86_400, tickPeriodSeconds = 0))
    }

    // ---- 2) 基准时间：确认时间优先，null 退回检查时间，都 null ⇒ 不判过期 ----

    @Test
    fun `两个时间都为 null 时不判过期`() {
        // 从来没有检查过 = 没有基准，"过期"无从谈起（宁可不说，也不要编一个）
        assertFalse(
            FreshnessPolicy.isDataExpired(
                now = now, lastConfirmedAt = null, lastCheckedAt = null,
                thresholdSeconds = 300, tickPeriodSeconds = 60, banned = false
            )
        )
        assertNull(FreshnessPolicy.baseTimeOf(null, null))
    }

    @Test
    fun `没有确认时间时退回最后检查时间`() {
        assertFalse(
            FreshnessPolicy.isDataExpired(
                now = now, lastConfirmedAt = null, lastCheckedAt = now - minute,
                thresholdSeconds = 300, tickPeriodSeconds = 60, banned = false
            )
        )
        assertTrue(
            FreshnessPolicy.isDataExpired(
                now = now, lastConfirmedAt = null, lastCheckedAt = now - 10 * minute,
                thresholdSeconds = 300, tickPeriodSeconds = 60, banned = false
            )
        )
        assertEquals(now - minute, FreshnessPolicy.baseTimeOf(null, now - minute))
    }

    @Test
    fun `确认时间优先于最后检查时间`() {
        // 最后检查刚发生（失败观察会推进 lastCheckedAt），但确认已经过去 10 分钟 ⇒ 过期
        assertTrue(
            FreshnessPolicy.isDataExpired(
                now = now, lastConfirmedAt = now - 10 * minute, lastCheckedAt = now - 1_000L,
                thresholdSeconds = 300, tickPeriodSeconds = 60, banned = false
            )
        )
        // 反过来：刚确认过、最后检查是很久以前（时序异常）⇒ 以确认时间为准，不过期
        assertFalse(
            FreshnessPolicy.isDataExpired(
                now = now, lastConfirmedAt = now - 1_000L, lastCheckedAt = now - 10 * minute,
                thresholdSeconds = 300, tickPeriodSeconds = 60, banned = false
            )
        )
        assertEquals(now - 1_000L, FreshnessPolicy.baseTimeOf(now - 1_000L, now - 10 * minute))
    }

    @Test
    fun `时钟回拨不判过期`() {
        // 网络校时/用户改时间都可能让 now < 基准；此时判过期等于凭空造出一个告警
        assertFalse(expired(elapsedMillis = -5 * minute))
    }

    // ---- 3) 封禁中：恒不判过期（用户要求"仍显示时间，但不标过期"）----

    @Test
    fun `封禁中恒不判过期（哪怕数据已经过期很久）`() {
        assertFalse(expired(elapsedMillis = 3 * 24 * 60 * minute, banned = true))
        // 未封禁的同一位主播（同样的时间）却是过期的 —— 差异只来自封禁这一个输入
        assertTrue(expired(elapsedMillis = 3 * 24 * 60 * minute, banned = false))
    }

    @Test
    fun `封禁中不影响时间文案（仍显示时间）`() {
        val checkedAt = now - 42 * minute
        val bannedRow = FreshnessPolicy.freshnessRowOf(
            now = now, lastConfirmedAt = checkedAt, lastCheckedAt = checkedAt,
            thresholdSeconds = 300, tickPeriodSeconds = 60,
            banned = true, hintEnabled = true, timeTextOf = { "T:$it" }
        )
        val normalRow = FreshnessPolicy.freshnessRowOf(
            now = now, lastConfirmedAt = checkedAt, lastCheckedAt = checkedAt,
            thresholdSeconds = 300, tickPeriodSeconds = 60,
            banned = false, hintEnabled = true, timeTextOf = { "T:$it" }
        )
        assertFalse("封禁中不标过期", bannedRow.showExpiredMark)
        assertTrue("未封禁且已过期要标出来", normalRow.showExpiredMark)
        // ★ 用户原话："仍显示时间，方便看到目前状态" —— 两种情况下时间文案完全一致
        assertEquals("T:$checkedAt", bannedRow.timeText)
        assertEquals(bannedRow.timeText, normalRow.timeText)
    }

    // ---- 4) 阈值与调度周期的协调（防"省电模式永远显示过期"）----

    @Test
    fun `省电模式周期 900 秒时有效阈值被抬到 1800 秒`() {
        val powerSavingPeriod = FreshnessPolicy.tickPeriodSeconds(
            MonitoringMode.POWER_SAVING, intervalSeconds = 60
        )
        assertEquals("省电模式周期被 clamp 到 15 分钟", 900, powerSavingPeriod)
        assertEquals(
            "有效阈值 = max(300, 2 × 900) = 1800",
            1_800L,
            FreshnessPolicy.effectiveThresholdSeconds(300, powerSavingPeriod)
        )
        // 一个完整周期（900s）绰绰有余地没过期 —— 这正是修复前会误报的那个点
        assertFalse(expired(elapsedMillis = 900_000L, tickPeriodSeconds = powerSavingPeriod))
        assertFalse(expired(elapsedMillis = 1_800_000L, tickPeriodSeconds = powerSavingPeriod))
        // 1900 秒（超过 2 个周期）才过期
        assertTrue(expired(elapsedMillis = 1_900_000L, tickPeriodSeconds = powerSavingPeriod))
    }

    @Test
    fun `省电模式周期的两种极端配置都算对`() {
        assertEquals("30 秒配置被抬到 15 分钟下限", 900, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.POWER_SAVING, 30))
        assertEquals("10 小时配置被压到 240 分钟上限", 14_400, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.POWER_SAVING, 36_000))
    }

    @Test
    fun `有效阈值不会被周期拉低，只会被抬高`() {
        // 阈值 30 分钟、周期 60 秒 ⇒ 仍按 30 分钟算（不能因为周期短就把阈值改小）
        assertEquals(1_800L, FreshnessPolicy.effectiveThresholdSeconds(1_800, 60))
        // 阈值 5 分钟、周期 60 秒 ⇒ 5 分钟
        assertEquals(300L, FreshnessPolicy.effectiveThresholdSeconds(300, 60))
        // 周期未知（0）时就是配置值本身
        assertEquals(300L, FreshnessPolicy.effectiveThresholdSeconds(300, 0))
        // 坏值（负数）不得把阈值算成负数
        assertEquals(0L, FreshnessPolicy.effectiveThresholdSeconds(-100, -100))
    }

    @Test
    fun `实时模式与手动模式的周期就是配置值`() {
        assertEquals(60, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.REALTIME, 60))
        assertEquals(300, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.MANUAL, 300))
        // 手动模式没有周期（0 秒配置）时不抬高阈值
        assertEquals(300L, FreshnessPolicy.effectiveThresholdSeconds(300, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.MANUAL, 0)))
    }

    // ---- 5) 开关关闭 ⇒ 不显示过期标记（但时间照旧）----

    @Test
    fun `开关关闭时不显示过期标记`() {
        val checkedAt = now - 60 * minute
        val off = FreshnessPolicy.freshnessRowOf(
            now = now, lastConfirmedAt = checkedAt, lastCheckedAt = checkedAt,
            thresholdSeconds = 300, tickPeriodSeconds = 60,
            banned = false, hintEnabled = false, timeTextOf = { "T:$it" }
        )
        assertFalse("开关关闭 ⇒ 即使已过期也不标", off.showExpiredMark)
        assertEquals("时间行照旧显示", "T:$checkedAt", off.timeText)

        val on = FreshnessPolicy.freshnessRowOf(
            now = now, lastConfirmedAt = checkedAt, lastCheckedAt = checkedAt,
            thresholdSeconds = 300, tickPeriodSeconds = 60,
            banned = false, hintEnabled = true, timeTextOf = { "T:$it" }
        )
        assertTrue(on.showExpiredMark)
        assertEquals("开关不影响时间文案", off.timeText, on.timeText)
    }

    // ---- 6) 状态行的其余分支 ----

    @Test
    fun `过期与否都不隐藏时间（时间渲染是独立分支）`() {
        val checkedAt = now - 3 * minute
        val freshRow = FreshnessPolicy.freshnessRowOf(
            now = now, lastConfirmedAt = checkedAt, lastCheckedAt = checkedAt,
            thresholdSeconds = 300, tickPeriodSeconds = 60,
            banned = false, hintEnabled = true, timeTextOf = { "T:$it" }
        )
        val staleRow = FreshnessPolicy.freshnessRowOf(
            now = now, lastConfirmedAt = checkedAt, lastCheckedAt = checkedAt,
            thresholdSeconds = 1, tickPeriodSeconds = 0,
            banned = false, hintEnabled = true, timeTextOf = { "T:$it" }
        )
        assertFalse(freshRow.showExpiredMark)
        assertTrue(staleRow.showExpiredMark)
        assertEquals("过期标记变了，时间文案必须一字不变", freshRow.timeText, staleRow.timeText)
    }

    @Test
    fun `从未检查过时只不显示时间也不标过期`() {
        val row = FreshnessPolicy.freshnessRowOf(
            now = now, lastConfirmedAt = null, lastCheckedAt = null,
            thresholdSeconds = 300, tickPeriodSeconds = 60,
            banned = false, hintEnabled = true, timeTextOf = { "T:$it" }
        )
        assertFalse(row.showExpiredMark)
        assertNull("没有检查时间就不画时间行（与改动前的 lastCheckedAt?.let 一致）", row.timeText)
    }

    // ---- 7) 存储列判定（引擎写入的那个派生列）----

    @Test
    fun `存储列判定与改动前逐字一致`() {
        assertEquals("从未确认过 ⇒ UNKNOWN", DataFreshness.UNKNOWN, FreshnessPolicy.freshnessOf(null, now, 300))
        assertEquals(DataFreshness.FRESH, FreshnessPolicy.freshnessOf(now, now, 300))
        assertEquals(DataFreshness.FRESH, FreshnessPolicy.freshnessOf(now - 300_000L, now, 300))
        assertEquals(DataFreshness.STALE, FreshnessPolicy.freshnessOf(now - 300_001L, now, 300))
        assertEquals(DataFreshness.UNKNOWN, FreshnessPolicy.freshnessOf(null, now, 1_800))
    }

    // ---- 8) 设置页的分钟换算 ----

    @Test
    fun `默认 300 秒恰好显示 5 分钟`() {
        assertEquals(5, FreshnessPolicy.thresholdMinutesOf(300))
        assertEquals(300, FreshnessPolicy.thresholdSecondsOf(5))
    }

    @Test
    fun `30 到 59 秒向上取整到 1 分钟而不是 0`() {
        // 整除会得到 0，而 0 落在 validRange = 1..1440 之外 ⇒ 设置页一进去就报"请输入 1–1440"
        assertEquals(1, FreshnessPolicy.thresholdMinutesOf(30))
        assertEquals(1, FreshnessPolicy.thresholdMinutesOf(59))
        assertEquals(1, FreshnessPolicy.thresholdMinutesOf(60))
        // 61–119 秒显示 2 分钟（不精确，但 ≥ 真实阈值，且不点保存就不会写库）
        assertEquals(2, FreshnessPolicy.thresholdMinutesOf(61))
        assertEquals(2, FreshnessPolicy.thresholdMinutesOf(119))
        assertEquals(1, FreshnessPolicy.thresholdMinutesOf(1))
        assertEquals(1, FreshnessPolicy.thresholdMinutesOf(0))
    }

    @Test
    fun `分钟换算的上界就是既有校验的上界`() {
        assertEquals(1_440, FreshnessPolicy.thresholdMinutesOf(FreshnessPolicy.THRESHOLD_SECONDS_RANGE.last))
        assertEquals(30, FreshnessPolicy.thresholdSecondsOf(1) / 2)
        // 提交时的分钟→秒永远落在既有校验 30–86400 秒之内（不会出现"界面能填、保存报参数非法"）
        for (minutes in FreshnessPolicy.THRESHOLD_MINUTES_RANGE) {
            assertTrue(
                "minutes=$minutes 换算后越界",
                FreshnessPolicy.thresholdSecondsOf(minutes) in FreshnessPolicy.THRESHOLD_SECONDS_RANGE
            )
        }
        // 越界输入被夹到边界（不抛异常、也不写出非法值）
        assertEquals(60, FreshnessPolicy.thresholdSecondsOf(0))
        assertEquals(86_400, FreshnessPolicy.thresholdSecondsOf(99_999))
    }
}
