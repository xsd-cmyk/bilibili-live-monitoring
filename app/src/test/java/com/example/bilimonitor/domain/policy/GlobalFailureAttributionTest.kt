package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.GapReason
import com.example.bilimonitor.data.local.ObservationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺陷 2 的验收单测：**全局降级必须看原因，不能只看比例**。
 *
 * 修复前的链路：占比越线 → `onTransportFailure()` → `openSystemGap(NETWORK_UNAVAILABLE)`
 * + 通知「网络不可用，监控出现盲区」，与原因无关。于是 98 位主播全被服务端以 `code=-352`
 * 拒绝（或全 429）时，主播级标 `API_UNAVAILABLE`、系统级标 `NETWORK_UNAVAILABLE`，两个标签互相矛盾。
 *
 * 这里锁定的判据：
 *  - 只有主导原因是真实传输故障（TIMEOUT / NETWORK / SERVER_ERROR）才允许走"网络不可用"；
 *  - 接口侧原因（限流 / 业务拒绝 / 响应不可用）**照常降级**（盲区是真的），但类型与文案如实；
 *  - 主导原因 = **仍失败的主播数最多**的那个；并列时保守选非传输（不许宣称网络故障）。
 */
class GlobalFailureAttributionTest {

    private val threshold = 50

    private fun attribution(counts: Map<RoomFailureReason, Int>, total: Int = 98, thresholdPercent: Int = threshold) =
        RoundFailureGate.globalFailureCause(
            ratio = RoundFailureRatio(failed = counts.values.sum(), total = total),
            thresholdPercent = thresholdPercent,
            reasonCounts = counts
        )

    // ---- 传输侧：这些才允许说"网络不可用" ----

    @Test
    fun `98 位全部超时_归因为网络事故`() {
        val a = attribution(mapOf(RoomFailureReason.TIMEOUT to 98))
        assertEquals(RoundFailureGate.GlobalFailureKind.TRANSPORT, a?.kind)
        assertEquals(GapReason.NETWORK_UNAVAILABLE, a?.gapReason)
        assertEquals(AppError.NETWORK_TIMEOUT, a?.appError)
        assertEquals("MONITORING:NETWORK_UNAVAILABLE:SYSTEM", a?.problemBaseKey)
        assertTrue(a!!.summary.contains("超时"))
    }

    @Test
    fun `98 位全部连接失败_归因为网络事故`() {
        val a = attribution(mapOf(RoomFailureReason.NETWORK to 98))
        assertEquals(RoundFailureGate.GlobalFailureKind.TRANSPORT, a?.kind)
        assertEquals(GapReason.NETWORK_UNAVAILABLE, a?.gapReason)
        assertEquals(AppError.NETWORK_UNAVAILABLE, a?.appError)
        assertEquals("网络不可用，监控出现盲区", a?.summary)
    }

    @Test
    fun `98 位全部 5xx_归因为网络事故（真实传输故障）`() {
        val a = attribution(mapOf(RoomFailureReason.SERVER_ERROR to 98))
        assertEquals(RoundFailureGate.GlobalFailureKind.TRANSPORT, a?.kind)
        assertEquals(GapReason.NETWORK_UNAVAILABLE, a?.gapReason)
        assertTrue(a!!.summary.contains("5xx"))
    }

    // ---- 接口侧：这些**不许**说"网络不可用"（缺陷 2 的核心） ----

    @Test
    fun `98 位全被服务端以 code 负 352 拒绝_不得报网络不可用而是接口被拒绝`() {
        val a = attribution(mapOf(RoomFailureReason.API_REJECTED to 98))
        assertEquals(RoundFailureGate.GlobalFailureKind.API, a?.kind)
        assertEquals(
            "系统级盲区必须与主播级同一个口径",
            GapReason.API_UNAVAILABLE,
            a?.gapReason
        )
        assertEquals(AppError.API_REJECTED, a?.appError)
        assertEquals("MONITORING:API_UNAVAILABLE:SYSTEM", a?.problemBaseKey)
        assertEquals("接口被拒绝，监控出现盲区", a?.summary)
        assertFalse("文案里不得出现'网络不可用'", a!!.summary.contains("网络"))
    }

    @Test
    fun `98 位全被限流_如实报接口被限流（429）`() {
        val a = attribution(mapOf(RoomFailureReason.RATE_LIMITED to 98))
        assertEquals(RoundFailureGate.GlobalFailureKind.API, a?.kind)
        assertEquals(GapReason.API_UNAVAILABLE, a?.gapReason)
        assertEquals("限流现在有自己的错误码，不再借用'网络不可用'", AppError.RATE_LIMITED, a?.appError)
        assertTrue(a!!.summary.contains("限流"))
    }

    @Test
    fun `98 位全部响应无法解析_不得报网络不可用`() {
        val a = attribution(mapOf(RoomFailureReason.MALFORMED_RESPONSE to 98))
        assertEquals(RoundFailureGate.GlobalFailureKind.API, a?.kind)
        assertEquals(GapReason.API_UNAVAILABLE, a?.gapReason)
        assertEquals(AppError.API_INVALID_RESPONSE, a?.appError)
    }

    // ---- 主导原因：按"仍失败的主播数"取，而不是"集合里有没有出现" ----

    @Test
    fun `多数是接口拒绝_少数是超时_必须归因为接口事故`() {
        // 修复前的做法是"原因集合里有 TIMEOUT 就算传输故障"（RoundFailureCode 的优先级也偏向 TIMEOUT），
        // 于是 1 位超时 + 97 位被拒也会发「网络不可用」—— 归因被极少数样本带偏。
        val a = attribution(
            mapOf(
                RoomFailureReason.TIMEOUT to 1,
                RoomFailureReason.API_REJECTED to 97
            )
        )
        assertEquals(RoundFailureGate.GlobalFailureKind.API, a?.kind)
        assertEquals(RoomFailureReason.API_REJECTED, a?.dominantReason)
        assertEquals(GapReason.API_UNAVAILABLE, a?.gapReason)
    }

    @Test
    fun `多数是超时_少数是被拒_归因为网络事故`() {
        val a = attribution(
            mapOf(
                RoomFailureReason.TIMEOUT to 60,
                RoomFailureReason.API_REJECTED to 20
            )
        )
        assertEquals(RoundFailureGate.GlobalFailureKind.TRANSPORT, a?.kind)
        assertEquals(RoomFailureReason.TIMEOUT, a?.dominantReason)
    }

    @Test
    fun `计数并列时保守选非传输原因_绝不宣称网络不可用`() {
        val a = attribution(
            mapOf(
                RoomFailureReason.TIMEOUT to 30,
                RoomFailureReason.API_REJECTED to 30
            )
        )
        assertEquals(RoundFailureGate.GlobalFailureKind.API, a?.kind)
        assertEquals(GapReason.API_UNAVAILABLE, a?.gapReason)
    }

    @Test
    fun `并列判定与 Map 遍历顺序无关_同一现场每次归因都一样`() {
        // 批次是并发完成的，reasonCounts 的插入顺序每次运行都可能不同 ——
        // 归因不能因此飘（不可复现的日志等于没有日志）。
        val one = linkedMapOf(
            RoomFailureReason.TIMEOUT to 30,
            RoomFailureReason.API_REJECTED to 30
        )
        val other = linkedMapOf(
            RoomFailureReason.API_REJECTED to 30,
            RoomFailureReason.TIMEOUT to 30
        )
        assertEquals(
            RoundFailureGate.dominantReasonOf(one),
            RoundFailureGate.dominantReasonOf(other)
        )
        assertEquals(RoomFailureReason.API_REJECTED, RoundFailureGate.dominantReasonOf(one))
    }

    // ---- 不触发 / 未知 / 边界 ----

    @Test
    fun `占比没越线时不降级`() {
        assertNull(attribution(mapOf(RoomFailureReason.NETWORK to 49)))
        assertNull("49/98 = 50% 恰好等于阈值，不算超过", attribution(mapOf(RoomFailureReason.NETWORK to 49)))
        assertEquals(
            "50/98 = 51% 才越线",
            RoundFailureGate.GlobalFailureKind.TRANSPORT,
            attribution(mapOf(RoomFailureReason.NETWORK to 50))?.kind
        )
    }

    @Test
    fun `分母为 0 时不降级`() {
        assertNull(attribution(emptyMap(), total = 0))
        assertNull(attribution(emptyMap(), total = 98))
    }

    @Test
    fun `占比越线但一个原因信号都没有_归因为未知而不是网络`() {
        // 引擎里这种情况来自"确实失败但上游没有给出原因"（RoundFailureLedger.recordUnknownReason）。
        val a = attribution(emptyMap())
        assertNull("没有失败主播就没有事故", a)
        // 有失败主播但拿不到原因时（引擎传的是"空计数 + 越线比例"），归因落到 UNKNOWN：
        val unknown = RoundFailureGate.globalFailureCause(
            ratio = RoundFailureRatio(failed = 60, total = 98),
            thresholdPercent = threshold,
            reasonCounts = emptyMap()
        )
        assertEquals(RoundFailureGate.GlobalFailureKind.UNKNOWN, unknown?.kind)
        assertEquals(GapReason.UNKNOWN, unknown?.gapReason)
        assertEquals(AppError.UNKNOWN, unknown?.appError)
        assertEquals("监控出现盲区，失败原因未能确定", unknown?.summary)
    }

    @Test
    fun `是否越线（要不要降级）与按什么原因降级是两件事`() {
        // 旧判据仍在：占比越线就算降级（用户必须知道监控出现盲区）……
        assertTrue(RoundFailureGate.shouldDegradeGlobalHealth(RoundFailureRatio(60, 98), 50))
        // ……但归因说明它不是网络事故（修复前这里会被记成 NETWORK_UNAVAILABLE）。
        assertEquals(
            RoundFailureGate.GlobalFailureKind.API,
            attribution(mapOf(RoomFailureReason.API_REJECTED to 60))?.kind
        )
    }

    @Test
    fun `传输故障判据表`() {
        assertTrue(RoundFailureGate.isTransportFailure(RoomFailureReason.TIMEOUT))
        assertTrue(RoundFailureGate.isTransportFailure(RoomFailureReason.NETWORK))
        assertTrue(RoundFailureGate.isTransportFailure(RoomFailureReason.SERVER_ERROR))
        assertFalse(RoundFailureGate.isTransportFailure(RoomFailureReason.RATE_LIMITED))
        assertFalse(RoundFailureGate.isTransportFailure(RoomFailureReason.API_REJECTED))
        assertFalse(RoundFailureGate.isTransportFailure(RoomFailureReason.MISSING_IN_RESPONSE))
        assertFalse(RoundFailureGate.isTransportFailure(RoomFailureReason.MALFORMED_RESPONSE))
    }

    @Test
    fun `三类事故的 problemKey 前缀互不相同_开与收共用同一来源`() {
        val keys = RoundFailureGate.GlobalFailureKind.values().map { RoundFailureGate.problemBaseKeyOf(it) }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains("MONITORING:NETWORK_UNAVAILABLE:SYSTEM"))
        assertTrue(keys.contains("MONITORING:API_UNAVAILABLE:SYSTEM"))
        assertTrue(keys.contains("MONITORING:UNKNOWN:SYSTEM"))
    }

    // ---- 主播级口径：限流不再被记成"网络" ----

    @Test
    fun `限流的主播级标签与错误码不再是网络不可用`() {
        assertEquals(
            "修复前是 NETWORK_ERROR，于是主播级盲区被记成 NETWORK_UNAVAILABLE",
            ObservationResult.API_ERROR,
            RoomFailureMapping.observationResultOf(RoomFailureReason.RATE_LIMITED)
        )
        assertEquals(
            AppError.RATE_LIMITED,
            RoomFailureMapping.appErrorOf(RoomFailureReason.RATE_LIMITED)
        )
        // gapReasonOf 是纯函数（ObservationResult → GapReason）：限流既然已经是 API_ERROR，
        // 主播级盲区自然落成 API_UNAVAILABLE，与系统级同一个口径。
        assertEquals(
            GapReason.API_UNAVAILABLE,
            com.example.bilimonitor.data.repository.MonitorRepository.gapReasonOf(ObservationResult.API_ERROR)
        )
    }
}
