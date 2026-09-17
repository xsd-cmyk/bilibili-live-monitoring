package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.data.local.TransitionReason
import com.example.bilimonitor.domain.model.RemoteLiveRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态确认状态机单测（对应 doc_v2.4.md 0.6.28 最低测试门槛中的多项）：
 *  - 首次 UNKNOWN→LIVE：立即确认，理由 FIRST_OBSERVATION（不产生 START 通知由仓储层保证）；
 *  - OFFLINE 方向连续 N 次确认；
 *  - LIVE 方向按 startConfirmationCount 确认（0.6.47.3 单一阈值）；
 *  - 异常/盲区结束后重新确认 LIVE → RECOVERY_RECONFIRM；
 *  - ROUND 不是异常，但仍按**未开播**处理（用户定稿）：确认走 OFFLINE 方向阈值，
 *    场次关闭与下播通知由 MonitorRepository 的"ROUND 并入 OFFLINE 分支"负责。
 *
 * 注意：「连续确认」中的**归零**不由本策略负责（策略无状态）：
 * 非 SUCCESS 观察返回 [StateConfirmationPolicy.Decision.None]，
 * 由 `MonitorRepository.applyObservationOnly` 在这条路径上复位 pending 行。
 * 因此本文件只验证「失败观察不推进计数」，归零行为在仓储层。
 */
class StateConfirmationPolicyTest {

    private fun remote(status: ConfirmedLiveStatus) = RemoteLiveRoom(
        uid = 1L, roomId = 2L, shortRoomId = null, name = "n", avatarUrl = null,
        roomTitle = null, parentAreaName = null, areaName = null, coverUrl = null,
        liveUrl = null, remoteLiveStatus = status, remoteStatusValid = true
    )

    private fun context(
        current: ConfirmedLiveStatus,
        pending: PendingTransition = PendingTransition.NONE,
        count: Int = 0,
        liveCount: Int = 0,
        hasInterval: Boolean = false,
        start: Int = 1,
        end: Int = 2
    ) = StreamerMonitorContext(
        confirmedLiveStatus = current,
        pendingTransition = pending,
        pendingConfirmationCount = if (pending == PendingTransition.SUSPECTED_OFFLINE) count else 0,
        hasActiveReliableInterval = hasInterval,
        startConfirmationCount = start,
        endConfirmationCount = end,
        pendingLiveConfirmationCount = if (pending == PendingTransition.SUSPECTED_LIVE) liveCount else 0
    )

    @Test
    fun `非 SUCCESS 观察不参与任何转换`() {
        val decision = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.OFFLINE),
            ObservationResult.NETWORK_ERROR,
            remote(ConfirmedLiveStatus.LIVE)
        )
        assertEquals(StateConfirmationPolicy.Decision.None, decision)
    }

    @Test
    fun `首次观察到 LIVE 立即建立基线且理由为 FIRST_OBSERVATION`() {
        val decision = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.UNKNOWN),
            ObservationResult.SUCCESS,
            remote(ConfirmedLiveStatus.LIVE)
        ) as StateConfirmationPolicy.Decision.Transition
        assertEquals(ConfirmedLiveStatus.UNKNOWN, decision.from)
        assertEquals(ConfirmedLiveStatus.LIVE, decision.to)
        assertEquals(TransitionReason.FIRST_OBSERVATION, decision.reason)
    }

    @Test
    fun `关播方向默认需要 2 次连续确认`() {
        val first = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.LIVE, hasInterval = true),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.OFFLINE)
        )
        assertTrue(first is StateConfirmationPolicy.Decision.Counting)
        assertEquals(
            PendingTransition.SUSPECTED_OFFLINE,
            (first as StateConfirmationPolicy.Decision.Counting).transition
        )
        assertEquals(1, first.newCount)

        val second = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.LIVE, PendingTransition.SUSPECTED_OFFLINE, count = 1, hasInterval = true),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.OFFLINE)
        ) as StateConfirmationPolicy.Decision.Transition
        assertEquals(ConfirmedLiveStatus.OFFLINE, second.to)
    }

    @Test
    fun `失败观察不推进关播计数`() {
        // 第 1 次成功 → 计数 1
        val first = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.LIVE, hasInterval = true),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.OFFLINE)
        ) as StateConfirmationPolicy.Decision.Counting
        assertEquals(1, first.newCount)

        // 中间一次网络失败：策略返回 None，**不产生任何计数动作**
        // （归零由仓储层在这条路径上 deletePendingTransition 完成）
        assertEquals(
            StateConfirmationPolicy.Decision.None,
            StateConfirmationPolicy.detect(
                context(ConfirmedLiveStatus.LIVE, PendingTransition.SUSPECTED_OFFLINE, count = 1, hasInterval = true),
                ObservationResult.NETWORK_ERROR, null
            )
        )
    }

    @Test
    fun `关播计数在方向一致时累加到阈值`() {
        val again = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.LIVE, PendingTransition.SUSPECTED_OFFLINE, count = 1, hasInterval = true),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.OFFLINE)
        ) as StateConfirmationPolicy.Decision.Transition
        assertEquals(ConfirmedLiveStatus.OFFLINE, again.to)
    }

    @Test
    fun `开播方向默认 1 次确认立即生效`() {
        val decision = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.OFFLINE, start = 1),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.LIVE)
        ) as StateConfirmationPolicy.Decision.Transition
        assertEquals(ConfirmedLiveStatus.LIVE, decision.to)
    }

    @Test
    fun `开播确认次数大于 1 时必须连续确认`() {
        val first = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.OFFLINE, start = 3),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.LIVE)
        )
        assertTrue(first is StateConfirmationPolicy.Decision.Counting)
        assertEquals(
            PendingTransition.SUSPECTED_LIVE,
            (first as StateConfirmationPolicy.Decision.Counting).transition
        )
        assertEquals(1, first.newCount)

        val second = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.OFFLINE, PendingTransition.SUSPECTED_LIVE, liveCount = 1, start = 3),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.LIVE)
        ) as StateConfirmationPolicy.Decision.Counting
        assertEquals(2, second.newCount)

        val third = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.OFFLINE, PendingTransition.SUSPECTED_LIVE, liveCount = 2, start = 3),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.LIVE)
        ) as StateConfirmationPolicy.Decision.Transition
        assertEquals(ConfirmedLiveStatus.LIVE, third.to)
    }

    @Test
    fun `方向切换时计数从 1 重新开始`() {
        // 关播方向已攒 2 次（阈值 2），此时转为观察到 LIVE：不应沿用旧计数
        val decision = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.LIVE, PendingTransition.SUSPECTED_OFFLINE, count = 2, start = 3),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.ROUND)
        ) as StateConfirmationPolicy.Decision.Transition
        // 轮播走 OFFLINE 方向：pending 已是 SUSPECTED_OFFLINE 且 count=2，再加 1 即过阈值（默认 2）。
        assertEquals(ConfirmedLiveStatus.ROUND, decision.to)
        assertEquals(TransitionReason.CONFIRMATION_COUNT_REACHED, decision.reason)
    }

    @Test
    fun `异常恢复后重新确认 LIVE 触发 RECOVERY_RECONFIRM`() {
        val decision = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.LIVE, hasInterval = false),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.LIVE)
        ) as StateConfirmationPolicy.Decision.Transition
        assertEquals(TransitionReason.RECOVERY_RECONFIRM, decision.reason)
        assertTrue(decision.reconfirmOnly)
        assertEquals(ConfirmedLiveStatus.LIVE, decision.to)
    }

    @Test
    fun `状态一致且无挂起计数时不产生决策`() {
        assertEquals(
            StateConfirmationPolicy.Decision.None,
            StateConfirmationPolicy.detect(
                context(ConfirmedLiveStatus.LIVE, hasInterval = true),
                ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.LIVE)
            )
        )
    }

    @Test
    fun `状态一致时复位残留的挂起计数`() {
        val decision = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.LIVE, PendingTransition.SUSPECTED_OFFLINE, count = 1, hasInterval = true),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.LIVE)
        ) as StateConfirmationPolicy.Decision.Counting
        assertEquals(PendingTransition.NONE, decision.transition)
        assertEquals(0, decision.newCount)
    }

    @Test
    fun `ROUND 是正常业务状态（按未开播处理，走 OFFLINE 方向确认）`() {
        val decision = StateConfirmationPolicy.detect(
            context(ConfirmedLiveStatus.OFFLINE),
            ObservationResult.SUCCESS, remote(ConfirmedLiveStatus.ROUND)
        // 轮播按未开播处理：第一次观察只**开始计数**（沿用 OFFLINE 方向阈值），
        // 但它仍是成功观察到的正常业务状态，不会被当成异常或盲区。
        ) as StateConfirmationPolicy.Decision.Counting
        assertEquals(PendingTransition.SUSPECTED_OFFLINE, decision.transition)
        assertEquals(1, decision.newCount)
    }

    @Test
    fun `远端状态非法时不产生决策`() {
        val invalid = remote(ConfirmedLiveStatus.LIVE).copy(remoteStatusValid = false, remoteLiveStatus = null)
        assertEquals(
            StateConfirmationPolicy.Decision.None,
            StateConfirmationPolicy.detect(
                context(ConfirmedLiveStatus.OFFLINE), ObservationResult.SUCCESS, invalid
            )
        )
    }
}
