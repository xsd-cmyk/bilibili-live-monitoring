package com.example.bilimonitor.background

import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.repository.MonitorRepository
import com.example.bilimonitor.domain.policy.FreshnessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 断档截断传进去的"正常检查间隔"必须是**实际周期**，不是配置里的 `intervalSeconds`。
 *
 * 缺陷现场：引擎的**转换路径**（唯一关场路径）曾传 `snapshot.intervalSeconds`，
 * 而默认配置是 `POWER_SAVING + intervalSeconds=60`，真实周期却被 clamp 到 900s ——
 * 阈值 `2×60=120s` 远小于真实间隔 ⇒ **每次关播都被判成"断档"**，
 * `endTime` 回退到上次成功确认的时刻（时长系统性少算约一个周期），
 * 并且每次都写一条 `SESSION_END_TRUNCATED` 审计，把真正的断档信号淹没。
 *
 * 为什么纯逻辑的测试发现不了它：`MonitorRepository.truncatedEndTime` 本身是对的
 * （见 `SessionEndTruncationTest`），错的是**调用方传进去的间隔**。
 * 所以这里钉的是"实际周期"这个值的契约：省电模式下 60s 的配置 ⇒ 900s 的实际周期，
 * 引擎的三条路径现在都传 `FreshnessPolicy.tickPeriodSeconds(...)`（同一个来源）。
 */
class MonitoringEngineSessionEndIntervalTest {

    private val now = 1_000_000_000_000L

    @Test
    fun `省电模式的实际周期是 15 分钟而不是配置里的 60 秒`() {
        assertEquals(900, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.POWER_SAVING, 60))
        // 配置本身就大于下限时按配置值（240 分钟是上限）
        assertEquals(3600, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.POWER_SAVING, 3600))
        // 实时模式：前台服务就是按配置值循环的，配置值就是实际周期
        assertEquals(60, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.REALTIME, 60))
        // 手动模式：只在打开 App 时检查，"没有周期"这件事如实按配置值（长时间不开就如实标过期）
        assertEquals(60, FreshnessPolicy.tickPeriodSeconds(MonitoringMode.MANUAL, 60))
    }

    @Test
    fun `正常的长周期关播不再被判成断档`() {
        val real = FreshnessPolicy.tickPeriodSeconds(MonitoringMode.POWER_SAVING, 60)
        // 上一次检查恰好在一个周期之前 —— 省电模式下这就是**正常节奏**
        val lastCheckedAt = now - real * 1000L
        assertEquals(
            "实际周期下不该截断，关播时刻就是现在",
            now,
            MonitorRepository.truncatedEndTime(now, lastCheckedAt, real)
        )
    }

    @Test
    fun `传配置间隔就会把正常关播截短（这正是被修掉的那次传参）`() {
        val real = FreshnessPolicy.tickPeriodSeconds(MonitoringMode.POWER_SAVING, 60)
        val lastCheckedAt = now - real * 1000L
        // 修复前转换路径传的是 60（配置值）：阈值 2×60s < 真实间隔 ⇒ 判成断档、时长少算一个周期
        val wrong = MonitorRepository.truncatedEndTime(now, lastCheckedAt, 60)
        assertEquals("阈值 2×60s 小于真实间隔 ⇒ 会被判成断档", lastCheckedAt, wrong)
        assertNotEquals(
            "两条路径传的值必须不同（否则这条测试就没有意义）",
            wrong,
            MonitorRepository.truncatedEndTime(now, lastCheckedAt, real)
        )
    }
}
