package com.example.bilimonitor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ABANDONED OPEN 场次的处置判据（缺陷 2）。
 *
 * 修的缺陷：转换路径**从不处理 ABANDONED OPEN** —— RECOVERY_RECONFIRM 分支只找 ACTIVE，
 * 找不到就 `openNewSession`；LIVE 分支只要命中 `findAbandonedOpenSession` 就
 * `reactivateAbandoned`（**没有任何时间窗**）。后果有两类：
 *  - 暂停监控 → S1 转 ABANDONED（endTime 仍为 NULL）→ 恢复后仍 LIVE → 新建 S2
 *    ⇒ 同一主播两条未结束、区间重叠的场次，而 S1 永远没有自动关闭路径；
 *  - 之后开播时命中几天前的 S1 → 复活它 ⇒ `startTime` 是几天前（关播时长 = 数天），
 *    且它上面已有 START 事件 ⇒ `existsBySessionAndType` 为真 ⇒ **不发开播通知**。
 *
 * 修复把"复用"限制在**能证明是同一次直播**的时间窗内（判据 [MonitorRepository.sameLiveRun]，
 * 窗口 = 2 × 实际检查周期，与既有的断档截断 [MonitorRepository.truncatedEndTime] 同口径）；
 * 超出窗口的陈旧场次先关闭（结束时刻同样走断档截断口径，因此不会写出"数天"的假时长）再新建。
 */
class AbandonedOpenSessionHandlingTest {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L

    /** 正常的检查周期：180 秒 ⇒ 窗口 = 360 秒 = 6 分钟。 */
    private val interval = 180

    // ---- 时间窗判据 ----

    @Test
    fun `短暂中断内视为同一次直播（可复用为 ACTIVE）`() {
        // 用户暂停监控 2 分钟后恢复、主播仍在直播：规范 0.6.22 的"复用该 Session 为 ACTIVE"
        assertTrue(MonitorRepository.sameLiveRun(now, now - 2 * minute, interval))
    }

    @Test
    fun `窗口边界（正好 2 个周期）仍算同一次直播`() {
        // 与 truncatedEndTime 同侧：差一点没到阈值就"不动它"，宁可复用也不误关
        assertTrue(MonitorRepository.sameLiveRun(now, now - 6 * minute, interval))
    }

    @Test
    fun `刚过窗口即判陈旧`() {
        assertFalse(MonitorRepository.sameLiveRun(now, now - (6 * minute + 1_000), interval))
    }

    @Test
    fun `几天前的陈旧场次不复用`() {
        // 规范/文档 :6365-6366 "不自动把旧 ABANDONED Session 当作当前场次"
        assertFalse(MonitorRepository.sameLiveRun(now, now - 3 * 24 * 60 * minute, interval))
    }

    @Test
    fun `省电模式的长周期不会被误判成陈旧`() {
        // 4 小时没检查，但周期本身就是 240 分钟 ⇒ 仍是同一次直播（与断档截断同一条纪律）
        assertTrue(MonitorRepository.sameLiveRun(now, now - 4 * 60 * minute, 240 * 60))
    }

    @Test
    fun `拿不到周期时不猜、保持复用`() {
        // 引擎没传周期（旧调用方 / 未知模式）时不能凭一个默认值把场次关掉
        assertTrue(MonitorRepository.sameLiveRun(now, now - 3 * 24 * 60 * minute, 0))
    }

    @Test
    fun `时钟回拨不误关`() {
        // 用户改过系统时间 / 网络校时回拨：锚点落在 now 之后 ⇒ 不能据此判陈旧
        assertTrue(MonitorRepository.sameLiveRun(now, now + 5 * minute, interval))
    }

    // ---- 陈旧场次的结束时刻（后果 B 的"假时长"） ----

    /**
     * 陈旧场次被关闭时，结束时刻必须落在**最后一次可靠观察**上，而不是 `now` ——
     * 否则"3 天前开播、今天才被处理"的这一场会写出 3 天的假时长，
     * 正是缺陷 2 后果 B 要消除的东西（修复前它的表现是"被复活"，时长同样是数天）。
     */
    @Test
    fun `陈旧场次关闭后的时长取自最后一次可靠观察而不是数天`() {
        val startedAt = now - 3 * 24 * 60 * minute
        val lastSeen = startedAt + 2 * 60 * minute // 3 天前的最后一次成功观察（开播 2 小时后）

        val endTime = MonitorRepository.truncatedEndTime(now, lastSeen, interval)

        assertEquals(lastSeen, endTime)
        assertEquals(2 * 60 * 60L, (endTime - startedAt) / 1000) // 2 小时，而不是 3 天
    }

    @Test
    fun `短暂中断后确认下播：结束时刻就是确认时刻`() {
        // 窗口内的中断（刚暂停一分钟就观察到 OFFLINE）：没有"断档"可言，按 now 记
        assertEquals(now, MonitorRepository.truncatedEndTime(now, now - minute, interval))
    }

    @Test
    fun `无任何可靠观察时不猜结束时刻`() {
        // 从未成功观察过（lastSeen = null）：只能记 now，不得凭空造一个更早的时刻
        val abandonedUpdatedAt = now - 3 * 24 * 60 * minute
        assertEquals(now, MonitorRepository.truncatedEndTime(now, null, interval))
        // 同时：窗口判据在这种情况下也**不会**误判（锚点存在、但周期有值 ⇒ 按陈旧处理），
        // 两条规则各自独立，互不掩盖
        assertFalse(MonitorRepository.sameLiveRun(now, abandonedUpdatedAt, interval))
    }
}
