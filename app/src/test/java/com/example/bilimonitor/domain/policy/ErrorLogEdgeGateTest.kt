package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺陷 3 的验收单测：观测失败留痕必须**边沿抑制**（同一主播 + 同一原因只记一条）。
 *
 * 修复前失败分支无条件往 `monitoring_error_log` 插一行：98 位主播全失败 = 98 行/分钟
 * ≈ 14 万行/天，而诊断包只取最近 500 行 —— 真正的故障现场会被自己刷掉。
 *
 * 这里驱动的是生产代码 [ErrorLogEdgeGate]（仓储 [com.example.bilimonitor.data.repository.MonitorRepository]
 * 的失败分支直接调用它的 [ErrorLogEdgeGate.shouldRecord] / [ErrorLogEdgeGate.clear]）。
 */
class ErrorLogEdgeGateTest {

    /** 模拟仓储的失败分支：应该落库时返回 true（= 真的会 insert 一行）。 */
    private fun gateShouldInsert(
        gate: ErrorLogEdgeGate,
        stableId: String,
        errorCode: AppError,
        reason: RoomFailureReason?
    ): Boolean = gate.shouldRecord(
        key = stableId,
        signature = ErrorLogEdgeGate.signatureOf(errorCode = errorCode, reason = reason)
    )

    @Test
    fun `验收_同一主播同一原因连续 10 轮只记 1 条`() {
        val gate = ErrorLogEdgeGate()
        var rows = 0
        repeat(10) {
            if (gateShouldInsert(gate, "str-1", AppError.NETWORK_TIMEOUT, RoomFailureReason.TIMEOUT)) rows++
        }
        assertEquals(1, rows)
        assertEquals(1, gate.tracked())
    }

    @Test
    fun `验收_原因变化各记 1 条`() {
        val gate = ErrorLogEdgeGate()
        var rows = 0
        repeat(5) { if (gateShouldInsert(gate, "str-1", AppError.NETWORK_TIMEOUT, RoomFailureReason.TIMEOUT)) rows++ }
        // 同一主播，原因从超时变成限流 → 这是新信息，必须记
        repeat(5) { if (gateShouldInsert(gate, "str-1", AppError.RATE_LIMITED, RoomFailureReason.RATE_LIMITED)) rows++ }
        // 又变成服务端明确拒绝 → 再记一条
        repeat(5) { if (gateShouldInsert(gate, "str-1", AppError.API_REJECTED, RoomFailureReason.API_REJECTED)) rows++ }
        assertEquals(3, rows)
    }

    @Test
    fun `验收_主播不同各自记 1 条`() {
        val gate = ErrorLogEdgeGate()
        var rows = 0
        for (i in 1..98) {
            repeat(10) { if (gateShouldInsert(gate, "str-$i", AppError.NETWORK_UNAVAILABLE, RoomFailureReason.NETWORK)) rows++ }
        }
        assertEquals("98 位主播各留一条事实（而不是 98 条/分钟）", 98, rows)
        assertEquals(98, gate.tracked())
    }

    @Test
    fun `验收_恢复后再次失败重新记一条`() {
        val gate = ErrorLogEdgeGate()
        var rows = 0
        repeat(3) { if (gateShouldInsert(gate, "str-1", AppError.NETWORK_TIMEOUT, RoomFailureReason.TIMEOUT)) rows++ }
        // 恢复（仓储在 SUCCESS 分支调用 clear）
        gate.clear("str-1")
        repeat(3) { if (gateShouldInsert(gate, "str-1", AppError.NETWORK_TIMEOUT, RoomFailureReason.TIMEOUT)) rows++ }
        assertEquals("下降沿：一段故障的结束与再次开始都值得留痕", 2, rows)
    }

    @Test
    fun `没有原始信号的回退路径_不同观察结果不会互相抑制`() {
        val gate = ErrorLogEdgeGate()
        // 引擎没给出 CallFailure 时，仓储按 ObservationResult 推断错误码（reason = null）：
        // 指纹若只按原因做，这两种完全不同的失败会塌成同一个 UNKNOWN 而互相抑制。
        assertTrue(gateShouldInsert(gate, "str-1", AppError.NETWORK_TIMEOUT, null))
        assertTrue(gateShouldInsert(gate, "str-1", AppError.API_REJECTED, null))
        assertFalse(gateShouldInsert(gate, "str-1", AppError.API_REJECTED, null))
    }

    @Test
    fun `指纹只由错误码与原因构成_不受上游文案波动影响`() {
        assertEquals(
            "NETWORK_TIMEOUT|TIMEOUT",
            ErrorLogEdgeGate.signatureOf(AppError.NETWORK_TIMEOUT, RoomFailureReason.TIMEOUT)
        )
        assertEquals(
            "NETWORK_UNAVAILABLE|UNKNOWN",
            ErrorLogEdgeGate.signatureOf(AppError.NETWORK_UNAVAILABLE, null)
        )
        // 同一 (码, 原因) 永远得到同一个指纹（message / http 状态码不参与）
        assertEquals(
            ErrorLogEdgeGate.signatureOf(AppError.API_REJECTED, RoomFailureReason.API_REJECTED),
            ErrorLogEdgeGate.signatureOf(AppError.API_REJECTED, RoomFailureReason.API_REJECTED)
        )
    }

    @Test
    fun `只增不减是 bug_key 由 clear 收敛`() {
        val gate = ErrorLogEdgeGate()
        gateShouldInsert(gate, "str-1", AppError.API_REJECTED, RoomFailureReason.API_REJECTED)
        gateShouldInsert(gate, "str-2", AppError.API_REJECTED, RoomFailureReason.API_REJECTED)
        assertEquals(2, gate.tracked())
        gate.clear("str-1")
        assertEquals(1, gate.tracked())
        gate.clear("str-2")
        assertEquals(0, gate.tracked())
        gate.clear("str-2") // 幂等
        assertEquals(0, gate.tracked())
    }

    @Test
    fun `全网故障的写库量_修复前后量级对比`() {
        val gate = ErrorLogEdgeGate()
        var rows = 0
        // 10 轮、98 位主播、同一原因（模拟一次持续 10 分钟的全网故障）
        repeat(10) {
            for (i in 1..98) {
                if (gateShouldInsert(gate, "str-$i", AppError.NETWORK_UNAVAILABLE, RoomFailureReason.NETWORK)) rows++
            }
        }
        assertEquals("修复前是 980 行（≈14 万行/天），现在 98 行", 98, rows)
        assertTrue("诊断包 500 行的额度不会再被同一件事占满", rows < 500)
    }

    @Test
    fun `并发下不会漏判_同一信号只有一个线程拿到 true`() {
        val gate = ErrorLogEdgeGate()
        val winners = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..16).map {
            Thread {
                if (gateShouldInsert(gate, "str-1", AppError.NETWORK_UNAVAILABLE, RoomFailureReason.NETWORK)) {
                    winners.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("ConcurrentHashMap.put 的返回值判定在并发下也只有一个赢家", 1, winners.get())
    }
}
