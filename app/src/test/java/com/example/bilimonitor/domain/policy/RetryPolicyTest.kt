package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重试与熔断纯规则单测（原规范 180 / 246.5）。
 * 这两组参数（maxRetries / retryBaseSeconds / maxRetryDelaySeconds /
 * circuitBreakerThreshold / circuitBreakerRecoverySeconds）此前只落库、从不读取。
 */
class RetryPolicyTest {

    @Test
    fun `总请求次数为首试加重试`() {
        assertEquals(1, RetryPolicy.totalAttempts(0))
        assertEquals(3, RetryPolicy.totalAttempts(2))
        assertEquals(6, RetryPolicy.totalAttempts(5))
    }

    @Test
    fun `重试次数被硬上限夹住`() {
        assertEquals(RetryPolicy.MAX_ATTEMPTS_CEILING + 1, RetryPolicy.totalAttempts(999))
        assertEquals(1, RetryPolicy.totalAttempts(-5))
    }

    @Test
    fun `退避按指数增长`() {
        // 种子值：base 5s，上限 60s
        assertEquals(5_000L, RetryPolicy.backoffMillis(0, 5, 60))
        assertEquals(10_000L, RetryPolicy.backoffMillis(1, 5, 60))
        assertEquals(20_000L, RetryPolicy.backoffMillis(2, 5, 60))
        assertEquals(40_000L, RetryPolicy.backoffMillis(3, 5, 60))
    }

    @Test
    fun `退避不超过上限`() {
        assertEquals(60_000L, RetryPolicy.backoffMillis(4, 5, 60))
        assertEquals(60_000L, RetryPolicy.backoffMillis(20, 5, 60))
    }

    @Test
    fun `退避参数非法时退化为安全值`() {
        assertEquals(1_000L, RetryPolicy.backoffMillis(0, 0, 0))
        assertEquals(1_000L, RetryPolicy.backoffMillis(-3, -1, -1))
    }

    @Test
    fun `熔断初始为关闭`() {
        assertFalse(RetryPolicy.BreakerState().isOpen(nowElapsed = 0L))
    }

    @Test
    fun `连续失败达到阈值后打开`() {
        var state = RetryPolicy.BreakerState()
        state = state.record(success = false, threshold = 3, recoverySeconds = 60, nowElapsed = 1_000L)
        assertFalse("第 1 次失败不应打开", state.isOpen(1_000L))
        state = state.record(success = false, threshold = 3, recoverySeconds = 60, nowElapsed = 2_000L)
        assertFalse("第 2 次失败不应打开", state.isOpen(2_000L))
        state = state.record(success = false, threshold = 3, recoverySeconds = 60, nowElapsed = 3_000L)
        assertTrue("第 3 次失败应打开", state.isOpen(3_000L))
    }

    @Test
    fun `熔断在恢复窗口结束后自动关闭`() {
        val state = RetryPolicy.BreakerState()
            .record(success = false, threshold = 1, recoverySeconds = 60, nowElapsed = 0L)
        assertTrue(state.isOpen(59_999L))
        assertFalse("窗口到期后应放行", state.isOpen(60_000L))
    }

    @Test
    fun `一次成功清零失败计数与熔断`() {
        var state = RetryPolicy.BreakerState()
        state = state.record(success = false, threshold = 2, recoverySeconds = 60, nowElapsed = 0L)
        state = state.record(success = false, threshold = 2, recoverySeconds = 60, nowElapsed = 0L)
        assertTrue(state.isOpen(0L))
        state = state.record(success = true, threshold = 2, recoverySeconds = 60, nowElapsed = 0L)
        assertFalse(state.isOpen(0L))
        assertEquals(0, state.consecutiveFailures)
    }

    @Test
    fun `阈值为 1 时首次失败即熔断`() {
        val state = RetryPolicy.BreakerState()
            .record(success = false, threshold = 1, recoverySeconds = 30, nowElapsed = 500L)
        assertTrue(state.isOpen(500L))
    }
}
