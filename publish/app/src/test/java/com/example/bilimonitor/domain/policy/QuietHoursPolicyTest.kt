package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免打扰时段判定单测（原规范 22.3：默认 23:00–07:00、默认关）。
 * 该能力此前完全不存在（策略表里的 overrideQuietHours 是死字段）。
 */
class QuietHoursPolicyTest {

    private val start = 23 * 60 // 23:00
    private val end = 7 * 60    // 07:00

    @Test
    fun `跨午夜时段在夜间为真`() {
        assertTrue(QuietHoursPolicy.contains(23 * 60, start, end))       // 23:00 起始点
        assertTrue(QuietHoursPolicy.contains(23 * 60 + 30, start, end))  // 23:30
        assertTrue(QuietHoursPolicy.contains(0, start, end))             // 00:00
        assertTrue(QuietHoursPolicy.contains(6 * 60 + 59, start, end))   // 06:59
    }

    @Test
    fun `跨午夜时段在白天为假`() {
        assertFalse(QuietHoursPolicy.contains(7 * 60, start, end))       // 07:00 结束点（不含）
        assertFalse(QuietHoursPolicy.contains(12 * 60, start, end))      // 12:00
        assertFalse(QuietHoursPolicy.contains(22 * 60 + 59, start, end)) // 22:59
    }

    @Test
    fun `同日内时段判定正确`() {
        // 13:00–15:00
        val s = 13 * 60
        val e = 15 * 60
        assertFalse(QuietHoursPolicy.contains(12 * 60 + 59, s, e))
        assertTrue(QuietHoursPolicy.contains(13 * 60, s, e))
        assertTrue(QuietHoursPolicy.contains(14 * 60 + 59, s, e))
        assertFalse(QuietHoursPolicy.contains(15 * 60, s, e))
    }

    @Test
    fun `起止相同视为未配置`() {
        assertFalse(QuietHoursPolicy.contains(0, 0, 0))
        assertFalse(QuietHoursPolicy.contains(12 * 60, 12 * 60, 12 * 60))
    }

    @Test
    fun `分钟数被规范化到一天之内`() {
        assertTrue(QuietHoursPolicy.contains(24 * 60 + 60, start, end)) // 25:00 → 01:00
        assertTrue(QuietHoursPolicy.contains(-60, start, end))          // -01:00 → 23:00
    }

    @Test
    fun `格式化与解析互逆`() {
        assertEquals("23:00", QuietHoursPolicy.format(23 * 60))
        assertEquals("07:00", QuietHoursPolicy.format(7 * 60))
        assertEquals("00:05", QuietHoursPolicy.format(5))
        assertEquals(23 * 60, QuietHoursPolicy.minutesOfDay(23, 0))
        assertEquals(7 * 60, QuietHoursPolicy.minutesOfDay(7, 0))
    }

    @Test
    fun `越界时分被夹住`() {
        assertEquals(23 * 60 + 59, QuietHoursPolicy.minutesOfDay(99, 99))
        assertEquals(0, QuietHoursPolicy.minutesOfDay(-5, -5))
    }

    @Test
    fun `按本地墙上时间取当日分钟数`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        // 2026-01-01 23:30 CST
        val epoch = java.time.LocalDateTime.of(2026, 1, 1, 23, 30)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(23 * 60 + 30, QuietHoursPolicy.nowMinutesOfDay(epoch, zone))
        assertTrue(QuietHoursPolicy.contains(QuietHoursPolicy.nowMinutesOfDay(epoch, zone), start, end))
    }
}
