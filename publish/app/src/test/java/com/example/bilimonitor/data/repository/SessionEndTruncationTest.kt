package com.example.bilimonitor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 断档截断规则（台账 H19-A2）。
 *
 * 这条规则决定"进程死了一段时间之后，恢复时观察到的下播该记成几点"：
 * 记错就会写出一条"时长 = 整个断档"的假场次（实测场景：09:00 开播、10:00 进程被杀、
 * 22:00 恢复 → 13 小时场次），并污染历史、统计、导出与备份。
 *
 * 抽成纯函数就是为了能在这里把边界钉死 —— 尤其是"不能把正常的长周期误判成断档"
 * 这一侧：省电模式的调度周期可以是 15~240 分钟。
 */
class SessionEndTruncationTest {

    private val minute = 60_000L

    @Test
    fun `normal tick does not truncate`() {
        val now = 1_000_000_000_000L
        // 180 秒间隔，上一次检查就在 3 分钟前 → 正常节奏，关播时刻就是现在
        assertEquals(now, MonitorRepository.truncatedEndTime(now, now - 3 * minute, 180))
    }

    @Test
    fun `just inside the threshold does not truncate`() {
        val now = 1_000_000_000_000L
        // 阈值 = 2 × 180s = 6 分钟；差一秒没到就不动它（宁可留长一点，不要凭空截短）
        assertEquals(now, MonitorRepository.truncatedEndTime(now, now - (6 * minute - 1000), 180))
    }

    @Test
    fun `process death gap truncates to last seen`() {
        val now = 1_000_000_000_000L
        val lastSeen = now - 12 * 60 * minute // 12 小时前
        assertEquals(lastSeen, MonitorRepository.truncatedEndTime(now, lastSeen, 180))
    }

    @Test
    fun `long power saving interval is not mistaken for a gap`() {
        val now = 1_000_000_000_000L
        // 省电模式 240 分钟一个周期：4 小时没检查是**正常**的，不能截断
        assertEquals(now, MonitorRepository.truncatedEndTime(now, now - 4 * 60 * minute, 240 * 60))
    }

    @Test
    fun `unknown interval or missing last check never guesses`() {
        val now = 1_000_000_000_000L
        assertEquals(now, MonitorRepository.truncatedEndTime(now, now - 12 * 60 * minute, 0))
        assertEquals(now, MonitorRepository.truncatedEndTime(now, null, 180))
    }

    @Test
    fun `clock skew into the future does not truncate`() {
        val now = 1_000_000_000_000L
        // 用户改过系统时间 / 网络校时回拨，lastCheckedAt 落在 now 之后：不猜，按 now 记
        assertEquals(now, MonitorRepository.truncatedEndTime(now, now + 5 * minute, 180))
    }
}
