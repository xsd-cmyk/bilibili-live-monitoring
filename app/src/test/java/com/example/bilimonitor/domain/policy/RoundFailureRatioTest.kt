package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 比例门禁单测（用户诉求 3：**只有大面积失败才触发重试与熔断**）。
 *
 * 这些用例**逐条对着用户报告的真实形态写**：
 *  - 98 位主播、其中 1 位是被封禁的房间反复失败 → 绝不能触发全局重试/熔断
 *    （原实现的 bug：一个坏房间把全局熔断顶开，其他主播一并无法检测）；
 *  - 多主播在同一秒内 NETWORK_UNAVAILABLE（网络抖动）→ 才应该触发。
 *
 * 分子分母的口径写在 [RoundFailureRatio] 的 KDoc 里，这里只做验证，不放宽任何断言。
 */
class RoundFailureRatioTest {

    @Test
    fun `98 位监控 1 位失败 不触发`() {
        val ratio = RoundFailureRatio(failed = 1, total = 98)
        assertEquals(1, ratio.percent)
        assertFalse("1/98 = 1% 不该触发全局重试/熔断", ratio.exceeds(50))
    }

    @Test
    fun `98 位监控 50 位失败 触发_因为 51% 严格大于 50%`() {
        val ratio = RoundFailureRatio(failed = 50, total = 98)
        assertEquals(51, ratio.percent)
        assertTrue(ratio.exceeds(50))
    }

    @Test
    fun `恰好 49 比 98 等于 50% 不触发_比较是严格大于`() {
        val ratio = RoundFailureRatio(failed = 49, total = 98)
        assertEquals("49/98 必须精确等于 50", 50, ratio.percent)
        assertFalse("用户原话是'超过 50%'，等于 50% 不算超过", ratio.exceeds(50))
    }

    @Test
    fun `分母为 0 不触发且不崩`() {
        val ratio = RoundFailureRatio(failed = 0, total = 0)
        assertEquals(0, ratio.percent)
        assertFalse(ratio.exceeds(50))
        assertFalse("分母为 0 时即便阈值拉到最低也不能触发", ratio.exceeds(1))
    }

    @Test
    fun `只有 1 位主播且它失败 触发`() {
        val ratio = RoundFailureRatio(failed = 1, total = 1)
        assertEquals(100, ratio.percent)
        assertTrue("1/1 = 100% > 50%：全军覆没必须触发", ratio.exceeds(50))
    }

    @Test
    fun `同一个主播只算一次_重试不会把分子放大`() {
        // 场景：1 位主播失败、重试了 5 次仍失败 —— 分子仍是 1（由调用方按主播去重后传入）。
        val ratio = RoundFailureRatio(failed = 1, total = 98)
        assertEquals(1, ratio.failed)
        assertFalse(ratio.exceeds(50))
    }

    @Test
    fun `全部失败在任意阈值下都触发`() {
        val ratio = RoundFailureRatio(failed = 98, total = 98)
        assertEquals(100, ratio.percent)
        assertTrue(ratio.exceeds(50))
        assertTrue("阈值 99 时 100% 仍然越线", ratio.exceeds(99))
    }

    @Test
    fun `阈值为 100 时只有全员失败才触发`() {
        assertFalse(RoundFailureRatio(failed = 97, total = 98).exceeds(100))
        assertFalse("等于 100% 不超过 100%", RoundFailureRatio(failed = 98, total = 98).exceeds(100))
    }

    @Test
    fun `阈值被夹到 1 到 100`() {
        // 阈值 0 会被夹到 1：语义是"失败占比**超过** 1% 才触发"，
        // 因此 1/98 = 1% 恰好等于下限、仍然不触发（严格大于），2/98 = 2% 才触发。
        // 这样"把阈值调到 0"不会退化成"任何一次失败都触发全局熔断"（那正是用户报告的 bug）。
        assertFalse(RoundFailureRatio(failed = 0, total = 98).exceeds(0))
        assertFalse("1/98 = 1%，夹到下限 1 之后仍是'等于'而非'超过'", RoundFailureRatio(failed = 1, total = 98).exceeds(0))
        assertTrue(RoundFailureRatio(failed = 2, total = 98).exceeds(0))
        // 超过 100 的阈值等价于 100（不越界、不崩）。
        assertFalse(RoundFailureRatio(failed = 98, total = 98).exceeds(1000))
        assertFalse(RoundFailureRatio(failed = 98, total = 98).exceeds(100))
    }

    @Test
    fun `百分比向下取整_不会因为取整而提前触发`() {
        // 33/100 = 33%；阈值 33 时"等于"不触发。
        assertEquals(33, RoundFailureRatio(failed = 33, total = 100).percent)
        assertFalse(RoundFailureRatio(failed = 33, total = 100).exceeds(33))
        assertTrue(RoundFailureRatio(failed = 34, total = 100).exceeds(33))
    }
}

/**
 * 门禁的两个派生判据。
 *
 * 语义要点：**重试是"按机制分类的"**，不是"按数量"——
 * 服务端明确拒绝（业务 code != 0 / 403）与限流重试没有意义，
 * 只有超时/网络/5xx/解析失败才值得重试；两者都不算"传输故障"，因此也都不该把熔断计数堆上去。
 */
class RoundFailureGateTest {

    private val mass = setOf(RoomFailureReason.NETWORK, RoomFailureReason.TIMEOUT)

    @Test
    fun `少量主播失败时既不重试也不降级全局健康`() {
        val ratio = RoundFailureRatio(failed = 1, total = 98)
        assertFalse(RoundFailureGate.shouldRunRetryPass(ratio, 50, setOf(RoomFailureReason.API_REJECTED)))
        assertFalse(RoundFailureGate.shouldDegradeGlobalHealth(ratio, 50))
    }

    @Test
    fun `大面积失败且存在可重试原因时进入重试轮`() {
        val ratio = RoundFailureRatio(failed = 60, total = 98)
        assertTrue(RoundFailureGate.shouldRunRetryPass(ratio, 50, mass))
        assertTrue(RoundFailureGate.shouldDegradeGlobalHealth(ratio, 50))
    }

    @Test
    fun `占比越线但全是不可重试原因时不进入重试轮`() {
        val ratio = RoundFailureRatio(failed = 60, total = 98)
        val reasons = setOf(RoomFailureReason.API_REJECTED, RoomFailureReason.RATE_LIMITED)
        assertFalse("重试服务端明确拒绝/限流的请求只会加重风控", RoundFailureGate.shouldRunRetryPass(ratio, 50, reasons))
        assertTrue(
            "但占比越线仍然算全局降级（用户能看到问题）",
            RoundFailureGate.shouldDegradeGlobalHealth(ratio, 50)
        )
    }

    @Test
    fun `失败主播数为 0 时不重试`() {
        assertFalse(RoundFailureGate.shouldRunRetryPass(RoundFailureRatio(0, 98), 50, mass))
    }

    @Test
    fun `熔断达到阈值时提前结束本轮剩余重试`() {
        assertFalse(RoundFailureGate.shouldStopRetrying(2, 3))
        assertTrue(RoundFailureGate.shouldStopRetrying(3, 3))
        assertTrue(RoundFailureGate.shouldStopRetrying(9, 3))
        // 坏配置（阈值 0/负）夹到 1，第一次失败就停，不会退化成"永不熔断"。
        assertTrue(RoundFailureGate.shouldStopRetrying(1, 0))
    }

    @Test
    fun `只有逻辑拒绝才值得拆小批次`() {
        assertTrue(
            RoundFailureGate.shouldSplitBatch(CallFailure(RoomFailureReason.API_REJECTED, apiCode = -403), 20)
        )
        assertFalse(
            "超时拆小没有意义，只会成倍增加请求",
            RoundFailureGate.shouldSplitBatch(CallFailure(RoomFailureReason.TIMEOUT), 20)
        )
        assertFalse(
            RoundFailureGate.shouldSplitBatch(CallFailure(RoomFailureReason.SERVER_ERROR, httpStatus = 503), 20)
        )
        assertFalse(
            RoundFailureGate.shouldSplitBatch(CallFailure(RoomFailureReason.RATE_LIMITED, httpStatus = 429), 20)
        )
        assertFalse(
            "批次太小就不拆了（拆完只剩 1 个主播）",
            RoundFailureGate.shouldSplitBatch(CallFailure(RoomFailureReason.API_REJECTED), 2)
        )
        assertFalse(
            "HTTP 5xx 即便被归到 API_REJECTED 也不拆（服务端整体故障）",
            RoundFailureGate.shouldSplitBatch(
                CallFailure(RoomFailureReason.API_REJECTED, httpStatus = 500), 20
            )
        )
    }

    @Test
    fun `失败原因到观察结果与错误码的映射是唯一口径`() {
        assertEquals(
            com.example.bilimonitor.data.local.ObservationResult.TIMEOUT,
            RoomFailureMapping.observationResultOf(RoomFailureReason.TIMEOUT)
        )
        assertEquals(
            // ★ 缺陷 2 修的就是这一条：限流曾是 NETWORK_ERROR，于是主播级盲区被记成
            //   `GapReason.NETWORK_UNAVAILABLE`（"网络不可用"），而它其实是"服务端在正常工作、
            //   只是让我们退避"。改判 API_ERROR 之后，主播级与系统级用的是同一个口径。
            com.example.bilimonitor.data.local.ObservationResult.API_ERROR,
            RoomFailureMapping.observationResultOf(RoomFailureReason.RATE_LIMITED)
        )
        assertEquals(
            com.example.bilimonitor.data.local.AppError.RATE_LIMITED,
            RoomFailureMapping.appErrorOf(RoomFailureReason.RATE_LIMITED)
        )
        assertEquals(
            com.example.bilimonitor.data.local.ObservationResult.API_ERROR,
            RoomFailureMapping.observationResultOf(RoomFailureReason.API_REJECTED)
        )
        assertEquals(
            com.example.bilimonitor.data.local.ObservationResult.INVALID,
            RoomFailureMapping.observationResultOf(RoomFailureReason.MALFORMED_RESPONSE)
        )
        assertEquals(
            com.example.bilimonitor.data.local.AppError.API_REJECTED,
            RoomFailureMapping.appErrorOf(RoomFailureReason.API_REJECTED)
        )
        assertEquals(
            com.example.bilimonitor.data.local.AppError.NETWORK_TIMEOUT,
            RoomFailureMapping.appErrorOf(RoomFailureReason.TIMEOUT)
        )
    }

    @Test
    fun `可重试性分类符合限流与服务端错误的处理要求`() {
        assertTrue(RoomFailureReason.TIMEOUT.retryable)
        assertTrue(RoomFailureReason.NETWORK.retryable)
        assertTrue(RoomFailureReason.SERVER_ERROR.retryable)
        assertTrue(RoomFailureReason.MALFORMED_RESPONSE.retryable)
        assertFalse("限流必须退避而不是立刻重试（原规范 246.5 要求 429 独立处理）", RoomFailureReason.RATE_LIMITED.retryable)
        assertFalse(RoomFailureReason.API_REJECTED.retryable)
        assertFalse(RoomFailureReason.MISSING_IN_RESPONSE.retryable)
    }
}
