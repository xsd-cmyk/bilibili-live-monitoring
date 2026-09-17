package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺陷 1 的验收单测：**失败账面必须能回收**，否则重试轮后重算的比例恒等于第一轮，
 * 而"进入重试轮"的前提就是第一轮越线 ⇒ 只要走进重试轮，末轮必然被判成全局故障。
 *
 * 这里驱动的是**生产代码**：引擎 `RoundFailures` 只是 [RoundFailureLedger] 的加锁壳，
 * 而"末轮会不会触发全局降级"由 [RoundFailureGate.globalFailureCause] 决定
 * （引擎里的分支是 `else if (globalFailure != null) onGlobalFailure(...) else onTransportSuccess(...)`）。
 * 因此"`globalFailureCause(...) == null`"等价于"**不会**触发网络事故路径"。
 */
class RoundFailureLedgerTest {

    private val threshold = 50

    /** 一次 Tick 的收尾判定 —— 与引擎里 :403 那处调用逐字对应。 */
    private fun attribution(ledger: RoundFailureLedger, total: Int, thresholdPercent: Int = 50) =
        RoundFailureGate.globalFailureCause(
            ratio = RoundFailureRatio(failed = ledger.failedCount, total = total),
            thresholdPercent = thresholdPercent,
            reasonCounts = ledger.reasonCounts
        )

    @Test
    fun `验收_第一轮 30 比 98 失败_重试后全部成功_末轮比例为 0 且不触发全局降级`() {
        val ledger = RoundFailureLedger()
        val ids = (1..98).map { "str-$it" }
        // 第一轮：30 位超时失败
        ledger.record(RoomFailureReason.TIMEOUT, ids.take(30))
        assertEquals(30, ledger.failedCount)

        // 重试轮：这 30 位全部拿到有效结果 → **必须回收**
        ledger.resolve(ids.take(30))

        val ratio = RoundFailureRatio(failed = ledger.failedCount, total = 98)
        assertEquals("分子必须归零（修复前这里恒为 30）", 0, ratio.failed)
        assertEquals(0, ratio.percent)
        assertFalse(ratio.exceeds(threshold))
        assertTrue("重试成功后账面必须是空的（边沿抑制器也要靠它复位）", ledger.isEmpty)
        assertNull(
            "重试成功后**不得**触发全局降级（修复前必然触发 ⇒ 开盲区 + 发「网络不可用」）",
            attribution(ledger, 98)
        )
    }

    @Test
    fun `占比越线后重试全部成功_末轮回到健康且不触发全局降级`() {
        val ledger = RoundFailureLedger()
        val ids = (1..98).map { "str-$it" }
        // 第一轮：60/98 超时 = 61% > 50% → 门禁放行，进入重试轮
        ledger.record(RoomFailureReason.TIMEOUT, ids.take(60))
        val firstRound = RoundFailureRatio(ledger.failedCount, 98)
        assertTrue(
            "前提：第一轮确实越线并进入重试轮",
            RoundFailureGate.shouldRunRetryPass(firstRound, threshold, ledger.reasons)
        )

        ledger.resolve(ids.take(60))

        assertEquals(0, ledger.failedCount)
        assertNull("重试成功 ⇒ 末轮不是全局故障", attribution(ledger, 98))
    }

    @Test
    fun `重试后仍有 30 位失败_在阈值 20 下仍然触发全局降级`() {
        val ledger = RoundFailureLedger()
        val ids = (1..98).map { "str-$it" }
        ledger.record(RoomFailureReason.TIMEOUT, ids.take(60))
        // 重试救回 30 位，剩 30 位仍失败
        ledger.resolve(ids.take(30))

        assertEquals(30, ledger.failedCount)
        val attribution = attribution(ledger, total = 98, thresholdPercent = 20)
        assertEquals(
            "30/98 = 30% > 20%：仍然越线 ⇒ 仍然触发（修复不能变成'永不降级'）",
            RoundFailureGate.GlobalFailureKind.TRANSPORT,
            attribution?.kind
        )
        assertEquals(RoomFailureReason.TIMEOUT, attribution?.dominantReason)
    }

    @Test
    fun `重试后仍有 30 位失败_默认阈值 50 下不触发_与重试门禁同一口径`() {
        val ledger = RoundFailureLedger()
        val ids = (1..98).map { "str-$it" }
        ledger.record(RoomFailureReason.TIMEOUT, ids.take(60))
        ledger.resolve(ids.take(30))

        // 修复后的口径与重试门禁完全一致（同一个 RoundFailureRatio）：30/98 = 30% 不超过 50%，
        // 因此既不重试也不降级 —— 这正是"一个坏房间/少数主播失败不得开出系统盲区"的语义。
        assertNull(attribution(ledger, total = 98, thresholdPercent = 50))
    }

    @Test
    fun `重试后 60 位仍失败_越线且归因为传输事故`() {
        val ledger = RoundFailureLedger()
        val ids = (1..98).map { "str-$it" }
        ledger.record(RoomFailureReason.NETWORK, ids.take(60))
        // 重试一位都没救回来
        val attribution = attribution(ledger, 98)
        assertEquals(RoundFailureGate.GlobalFailureKind.TRANSPORT, attribution?.kind)
        assertEquals(RoomFailureReason.NETWORK, attribution?.dominantReason)
    }

    @Test
    fun `部分回收后分子按回收后的账面计算_不会保留第一轮的失败集合`() {
        val ledger = RoundFailureLedger()
        val ids = (1..98).map { "str-$it" }
        ledger.record(RoomFailureReason.TIMEOUT, ids.take(60))
        ledger.resolve(ids.take(40)) // 重试救回 40 位

        assertEquals(20, ledger.failedCount)
        assertEquals(20, RoundFailureRatio(ledger.failedCount, 98).percent)
        assertNull("20/98 = 20% 未越线：末轮按**回收后**的账面判定，不得再按第一轮的 60", attribution(ledger, 98))
    }

    @Test
    fun `重试会覆盖同一主播的旧原因_主导原因取最新一次`() {
        val ledger = RoundFailureLedger()
        // 第一轮：60 位超时
        ledger.record(RoomFailureReason.TIMEOUT, (1..60).map { "str-$it" })
        // 重试：这 60 位这次被服务端限流拒绝（原因变了，必须按最新的算）
        ledger.record(RoomFailureReason.RATE_LIMITED, (1..60).map { "str-$it" })

        assertEquals(60, ledger.failedCount)
        assertEquals(mapOf(RoomFailureReason.RATE_LIMITED to 60), ledger.reasonCounts)
        assertEquals(setOf(RoomFailureReason.RATE_LIMITED), ledger.reasons)
        // 但"本轮出现过什么"要如实保留（诊断用）：超时确实发生过
        assertEquals(
            setOf(RoomFailureReason.TIMEOUT, RoomFailureReason.RATE_LIMITED),
            ledger.observedReasons
        )
        assertEquals(
            "原因变成限流之后不得再归因为网络事故（否则又会说'网络不可用'）",
            RoundFailureGate.GlobalFailureKind.API,
            attribution(ledger, 98)?.kind
        )
    }

    @Test
    fun `回收是幂等的_不在账面上的 id 是空操作`() {
        val ledger = RoundFailureLedger()
        ledger.record(RoomFailureReason.TIMEOUT, listOf("a", "b"))
        ledger.resolve(listOf("zzz")) // 第一轮就成功的主播天然不在账面上
        assertEquals(2, ledger.failedCount)
        ledger.resolve(listOf("a"))
        ledger.resolve(listOf("a"))
        assertEquals(setOf("b"), ledger.failedStableIds)
        ledger.resolve(listOf("b"))
        assertTrue(ledger.isEmpty)
        assertEquals(0, ledger.failedCount)
    }

    @Test
    fun `失败但没有任何原因信号_计入分子但不编造原因`() {
        val ledger = RoundFailureLedger()
        ledger.recordUnknownReason((1..60).map { "str-$it" })

        assertEquals(60, ledger.failedCount)
        assertTrue("原因计数必须为空（不许猜）", ledger.reasonCounts.isEmpty())
        assertTrue(ledger.reasons.isEmpty())
        val attribution = attribution(ledger, 98)
        assertEquals(
            "归因只能落到 UNKNOWN，而不是硬塞一个'网络不可用'",
            RoundFailureGate.GlobalFailureKind.UNKNOWN,
            attribution?.kind
        )
        assertEquals(com.example.bilimonitor.data.local.GapReason.UNKNOWN, attribution?.gapReason)
    }

    @Test
    fun `批次级信号只进出现过集合_不参与归因`() {
        val ledger = RoundFailureLedger()
        // 整批被拒的批次级信号（没有对应的失败主播）
        ledger.observe(RoomFailureReason.API_REJECTED)
        assertEquals(setOf(RoomFailureReason.API_REJECTED), ledger.observedReasons)
        assertTrue("没有失败主播的批次级信号不得成为主导原因", ledger.reasonCounts.isEmpty())

        // 60 位真的超时失败（批次级噪声仍在，但归因必须只看"仍失败的人"）
        ledger.record(RoomFailureReason.TIMEOUT, (1..60).map { "str-$it" })
        assertEquals(mapOf(RoomFailureReason.TIMEOUT to 60), ledger.reasonCounts)
        assertEquals(RoomFailureReason.TIMEOUT, attribution(ledger, 98)?.dominantReason)
    }
}
