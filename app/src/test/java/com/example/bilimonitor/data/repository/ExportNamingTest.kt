package com.example.bilimonitor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出文件命名规则（用户定稿 + 一次补充）。
 *
 * 为什么值得单测：文件名是用户唯一能看到的"这是哪份报告"的线索，
 * 而它已经被改过一次（历史与统计同名导致分不清）。规则里还包含两条容易改坏的约定：
 * 时间范围取**实际数据**的日期（不是用户选的范围）、N 取**选择的主播数**（不是有记录的主播数）。
 */
class ExportNamingTest {

    /** 2026-09-13 12:00 与 2026-09-15 12:00（本地时区构造，避免固定时间戳在不同时区漂移）。 */
    private val day1 = java.time.LocalDate.of(2026, 9, 13)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val day3 = java.time.LocalDate.of(2026, 9, 15)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `single streamer history export`() {
        assertEquals(
            "2026-09-13_2026-09-15_月见兔_直播历史.html",
            ExportNaming.of(listOf(day1, day3), listOf("月见兔"), ExportNaming.Kind.HISTORY)
        )
    }

    @Test
    fun `same streamer and range produce different names for the two export types`() {
        val times = listOf(day1, day3)
        val history = ExportNaming.of(times, listOf("月见兔"), ExportNaming.Kind.HISTORY)
        val stats = ExportNaming.of(times, listOf("月见兔"), ExportNaming.Kind.STATS)
        // 这正是用户反馈的问题：两者曾经完全同名，在 Download 里分不清
        assertNotEquals(history, stats)
        assertEquals("2026-09-13_2026-09-15_月见兔_统计图表.html", stats)
    }

    @Test
    fun `multi streamer uses the number of selected streamers`() {
        // 3 位被选中、其中只有 1 位有记录：名字仍要写 3 位（报告里空分组也会出现）。
        // ★ 前缀是首位主播名：「N位主播」这种写法会让 {甲,乙} 与 {丙,丁} 生成同名而内容
        //   不同的文件，而本函数的"同名 = 同内容"正是靠文件名做到的（见 ExportNaming 的说明）。
        assertEquals(
            "2026-09-13_2026-09-13_甲等3位_统计图表.html",
            ExportNaming.of(listOf(day1), listOf("甲", "乙", "丙"), ExportNaming.Kind.STATS)
        )
    }

    @Test
    fun `different streamer sets with the same count do not collide`() {
        // 人数相同、日期段相同、类型相同 —— 只靠"首位主播名"才能区分开。
        // 这条用例锁住的是"同名 = 同内容"这个前提：一旦退回"N位主播"，两人数相同的集合
        // 就会生成同名文件，MediaStore 只能追加 (1)，两份不同内容的报告再也分不清。
        val a = ExportNaming.of(listOf(day1), listOf("甲", "乙"), ExportNaming.Kind.STATS)
        val b = ExportNaming.of(listOf(day1), listOf("丙", "丁"), ExportNaming.Kind.STATS)
        assertNotEquals(a, b)
    }

    @Test
    fun `no records still yields a usable name`() {
        assertEquals(
            "无记录_无记录_月见兔_直播历史.html",
            ExportNaming.of(emptyList(), listOf("月见兔"), ExportNaming.Kind.HISTORY)
        )
    }

    @Test
    fun `rows pick start time and fall back to end time`() {
        val rows = listOf(
            mapOf("startTime" to day1.toString(), "endTime" to day3.toString()),
            // 缺开播时间的行用关播时间兜底，否则这一天会被算漏
            mapOf("startTime" to null, "endTime" to day3.toString())
        )
        assertEquals(
            "2026-09-13_2026-09-15_月见兔_直播历史.html",
            ExportNaming.forRows(rows, listOf("月见兔"), ExportNaming.Kind.HISTORY)
        )
    }

    @Test
    fun `illegal characters in streamer name are replaced`() {
        assertEquals("a_b_c", ExportNaming.sanitize("a/b\\c"))
        assertEquals("主播", ExportNaming.sanitize("   "))
        // 结尾的点在 Windows 上会导致文件不可创建
        assertEquals("名字", ExportNaming.sanitize("名字..."))
    }

    @Test
    fun `overlong streamer name is truncated`() {
        val long = "主播".repeat(80)
        assertTrue(ExportNaming.sanitize(long).length <= 60)
    }
}
