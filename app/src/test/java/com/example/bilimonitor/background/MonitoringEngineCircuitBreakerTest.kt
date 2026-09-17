package com.example.bilimonitor.background

import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.domain.policy.RetryPolicy
import com.example.bilimonitor.domain.policy.RoomFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监控引擎里两条**可以离线证明**的熔断判定（实现在 `MonitoringEngine.kt` 末尾的顶层纯函数里）。
 *
 * 为什么必须单独钉死它们：引擎本体依赖 Android / Room / 协程，没有 JVM 测试；
 * 而下面这两件事一旦写错，就等于两项修复都没做 ——
 *  ① 「熔断总开关关闭 ⇒ 请求照常发、不进入 circuit_open 早退」；
 *  ② 「熔断打开时的留痕错误码跟**真实原因**走，不再一律写网络不可用」。
 *
 * 这两条判定因此被抽成纯函数（[breakerGateOpen] / [breakerTraceCodeOf] / [breakerTraceKindOf]），
 * 由本测试逐条锁死；引擎侧只负责把"当前快照 + 当前状态 + 当前时钟"喂进去。
 */
class MonitoringEngineCircuitBreakerTest {

    @Test
    fun `开关关闭时即使熔断状态还停在打开也不生效`() {
        // 用户运行中把开关关掉：BreakerState 是**跨 Tick 存活**的内存状态，
        // 此刻它可能还停在"打开"（见 breakerGateOpen 的说明）。
        val opened = RetryPolicy.BreakerState(consecutiveFailures = 3, openUntilElapsed = 300_000L)
        assertFalse(
            "开关关闭 ⇒ circuit_open 早退分支必须走不到（请求照常发出去）",
            breakerGateOpen(enabled = false, state = opened, nowElapsed = 0L)
        )
        assertFalse(
            "恢复窗口内也一样：开关关闭就是「不做熔断暂停」，与窗口还剩多久无关",
            breakerGateOpen(enabled = false, state = opened, nowElapsed = 299_999L)
        )
    }

    @Test
    fun `开关打开时判据与修复前逐字一致`() {
        val opened = RetryPolicy.BreakerState(consecutiveFailures = 3, openUntilElapsed = 300_000L)
        assertTrue("窗口内仍然熔断", breakerGateOpen(enabled = true, state = opened, nowElapsed = 0L))
        assertTrue("差一毫秒没到期仍然熔断", breakerGateOpen(enabled = true, state = opened, nowElapsed = 299_999L))
        assertFalse("窗口到期即放行", breakerGateOpen(enabled = true, state = opened, nowElapsed = 300_000L))
        assertFalse("从未失败过就不会熔断", breakerGateOpen(enabled = true, state = RetryPolicy.BreakerState(), nowElapsed = 0L))
        // 阈值语义本身没有变化（这一段由 RetryPolicyTest 覆盖，这里只固定"开关打开 = 原行为"）。
        val justOpened = RetryPolicy.BreakerState()
            .record(success = false, threshold = 1, recoverySeconds = 60, nowElapsed = 1_000L)
        assertTrue(breakerGateOpen(enabled = true, state = justOpened, nowElapsed = 1_000L))
    }

    @Test
    fun `熔断留痕的错误码按触发原因选而不是一律网络不可用`() {
        // 传输类：这三者才允许说"网络"（TIMEOUT 有自己的码）
        assertEquals(AppError.NETWORK_TIMEOUT, breakerTraceCodeOf(RoomFailureReason.TIMEOUT))
        assertEquals(AppError.NETWORK_UNAVAILABLE, breakerTraceCodeOf(RoomFailureReason.NETWORK))
        assertEquals(AppError.NETWORK_UNAVAILABLE, breakerTraceCodeOf(RoomFailureReason.SERVER_ERROR))
        // 非传输类：修复前这些也会被写成 NETWORK_UNAVAILABLE（本次修复点）
        assertEquals(AppError.API_INVALID_RESPONSE, breakerTraceCodeOf(RoomFailureReason.MALFORMED_RESPONSE))
        assertEquals(AppError.API_INVALID_RESPONSE, breakerTraceCodeOf(RoomFailureReason.MISSING_IN_RESPONSE))
        assertEquals(AppError.RATE_LIMITED, breakerTraceCodeOf(RoomFailureReason.RATE_LIMITED))
        assertEquals(AppError.API_REJECTED, breakerTraceCodeOf(RoomFailureReason.API_REJECTED))
    }

    @Test
    fun `拿不到原因时如实记原因未定而不是猜网络`() {
        assertEquals(AppError.UNKNOWN, breakerTraceCodeOf(null))
        assertEquals("原因未定", breakerTraceKindOf(null))
    }

    @Test
    fun `只有真实传输故障才被标成传输故障`() {
        assertEquals("传输故障", breakerTraceKindOf(RoomFailureReason.TIMEOUT))
        assertEquals("传输故障", breakerTraceKindOf(RoomFailureReason.NETWORK))
        assertEquals("传输故障", breakerTraceKindOf(RoomFailureReason.SERVER_ERROR))
        // 链路是通的、只是内容不能用（解析失败/缺数据）或服务端明确拒绝：都不算传输故障
        assertEquals("非传输原因", breakerTraceKindOf(RoomFailureReason.MALFORMED_RESPONSE))
        assertEquals("非传输原因", breakerTraceKindOf(RoomFailureReason.MISSING_IN_RESPONSE))
        assertEquals("非传输原因", breakerTraceKindOf(RoomFailureReason.RATE_LIMITED))
        assertEquals("非传输原因", breakerTraceKindOf(RoomFailureReason.API_REJECTED))
    }
}
