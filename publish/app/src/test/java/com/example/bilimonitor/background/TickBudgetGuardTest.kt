package com.example.bilimonitor.background

import com.example.bilimonitor.domain.policy.RetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tick 预算下的重试收手判据（第二轮独立复查发现的问题 1）。
 *
 * ## 为什么需要它
 * 熔断总开关**打开**时，重试轮会在连续失败达阈值后提前收手，顺带把整轮压在预算内；
 * 用户把开关**关掉**（"失败就一直重试、不要暂停请求"）之后，重试轮没有任何内部闸门，
 * 只剩外层 `withTimeout(budgetMs)` —— 而那是**硬取消**：一旦命中，
 * `runTick` 里批次循环**之后**的收尾（末轮比例结算、系统级故障通知、健康事件、计数发布）
 * 全部不会执行。也就是说，关掉熔断之后，"这一轮为什么大面积失败"那条通知反而发不出去。
 *
 * 于是让重试轮自己看着预算收手：装不下"退避 + 下一次尝试 + 收尾余量"就按最后一次尝试处理。
 * 本文件锁的是这条判据的边界 —— 纯粹的算术，不需要设备。
 */
class TickBudgetGuardTest {

    // ------------------------------------------------------------ remainingTickBudgetMs

    @Test
    fun `未到期时返回剩余毫秒`() {
        assertEquals(42_000L, remainingTickBudgetMs(deadlineElapsed = 100_000L, nowElapsed = 58_000L))
    }

    @Test
    fun `恰好到期返回 0`() {
        assertEquals(0L, remainingTickBudgetMs(deadlineElapsed = 100_000L, nowElapsed = 100_000L))
    }

    @Test
    fun `已过期返回 0 而不是负数`() {
        // 负数会让"剩余 < 退避"这类比较反向成立，必须夹住。
        assertEquals(0L, remainingTickBudgetMs(deadlineElapsed = 100_000L, nowElapsed = 130_000L))
    }

    // ------------------------------------------------------------ retryFitsInBudget

    @Test
    fun `三样刚好装得下就允许重试`() {
        // 剩余 = 退避 5s + 尝试 13s + 收尾 15s = 33s，边界取等号也算装得下。
        assertTrue(
            retryFitsInBudget(
                remainingMs = 33_000L, backoffMs = 5_000L,
                attemptBudgetMs = 13_000L, tailReserveMs = 15_000L
            )
        )
    }

    @Test
    fun `少 1 毫秒就不允许`() {
        assertFalse(
            retryFitsInBudget(
                remainingMs = 32_999L, backoffMs = 5_000L,
                attemptBudgetMs = 13_000L, tailReserveMs = 15_000L
            )
        )
    }

    @Test
    fun `剩余为 0 时一律不允许（即使退避与尝试都很小）`() {
        assertFalse(
            retryFitsInBudget(
                remainingMs = 0L, backoffMs = 0L,
                attemptBudgetMs = 0L, tailReserveMs = 1L
            )
        )
    }

    @Test
    fun `收尾余量本身必须被算进去`() {
        // 退避 + 尝试装得下，但装不下收尾 ⇒ 仍然不允许（这正是"别把预算吃干"的那条约束）。
        val backoff = 5_000L
        val attempt = 13_000L
        assertTrue(
            retryFitsInBudget(backoff + attempt, backoff, attempt, tailReserveMs = 0L)
        )
        assertFalse(
            retryFitsInBudget(backoff + attempt, backoff, attempt, tailReserveMs = TICK_TAIL_RESERVE_MS)
        )
    }

    @Test
    fun `剩余越多越宽松（单调性）`() {
        val args = { remaining: Long ->
            retryFitsInBudget(
                remainingMs = remaining, backoffMs = 5_000L,
                attemptBudgetMs = 13_000L, tailReserveMs = TICK_TAIL_RESERVE_MS
            )
        }
        assertFalse(args(10_000L))
        assertTrue(args(60_000L))
        assertTrue(args(600_000L))
    }

    // ------------------------------------------------------------ 常量自洽

    @Test
    fun `收尾余量必须为正、且不超过预算下限 60s`() {
        // checkOnce 的预算下限是 60s（intervalSeconds*2 再 coerceIn(60s, 900s)）：
        // 余量若 ≥ 下限，重试轮就永远开不了张，等于把重试功能整体关掉。
        assertTrue("收尾余量必须为正", TICK_TAIL_RESERVE_MS > 0L)
        assertTrue("收尾余量不得吃掉整个预算下限", TICK_TAIL_RESERVE_MS < 60_000L)
    }

    @Test
    fun `单次尝试余量与 fetchOnce 的 withTimeout 同源`() {
        assertTrue(SINGLE_ATTEMPT_SLACK_MS > 0L)
    }

    // ------------------------------------------------------------ 为什么必须有这条判据（算术留证）

    @Test
    fun `默认预算下 单个批次的完整重试就装不下`() {
        // 用户能把 maxRetries 调到 5（ConfigRepository.merge 允许 0..5）：
        // 6 次尝试 × (8s 超时 + 5s 余量) + 退避 5+10+20+40+60 = 78s + 135s = 213s，
        // 而默认配置（intervalSeconds=60）的预算只有 2×60 = 120s。
        // ⇒ 没有收手判据时，**一个批次**就能把整轮吃穿，收尾全部丢失。
        val attempts = RetryPolicy.totalAttempts(maxRetries = 5)
        var worstMs = 0L
        for (attempt in 0 until attempts) {
            worstMs += 8_000L + SINGLE_ATTEMPT_SLACK_MS
            if (attempt < attempts - 1) {
                worstMs += RetryPolicy.backoffMillis(attempt, baseSeconds = 5, maxDelaySeconds = 60)
            }
        }
        assertTrue(
            "算出来 $worstMs ms；若不再超过默认 120s 预算，本判据的存在理由就变了，需重新评估",
            worstMs > 120_000L
        )
    }
}
