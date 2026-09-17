package com.example.bilimonitor.data.repository

import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.domain.model.RemoteLiveRoom
import com.example.bilimonitor.domain.policy.StateConfirmationPolicy
import com.example.bilimonitor.domain.policy.StreamerMonitorContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PendingTransition 的**代际重置**与 CAS 返回值处置（缺陷 1）。
 *
 * 修的缺陷：`updateCounting` 在代际变化时写 `existing.copy(monitorGeneration = …)`，
 * 而那条语句走的是 `@Insert(onConflict = IGNORE)`、表 PK 就是 `streamerId`
 * ⇒ 行已存在时 SQLite **整条 INSERT 作废** ⇒ 代际与计数根本没重置；
 * 紧接着的 CAS 期望值又是从**重读到的旧行**取的 ⇒ 恒匹配 ⇒
 * 计数**跨代际、跨进程**活了下来。老库 `endConfirmationCount = 2` 时，
 * 中断前后的两次观察会被当成"连续 2 次"，产生早一拍的假关播/假开播。
 *
 * 判据被抽成纯函数 [MonitorRepository.pendingWritePlan] / [MonitorRepository.pendingCountSkipOf]，
 * 本文件把两侧都钉死；其中「老库阈值 2」的用例还**显式复现了修复前的行为**（负向对照）。
 */
class PendingTransitionGenerationTest {

    // ---- 判据 1：代际变化时是否必须重置计数行 ----

    @Test
    fun `没有计数行时新建`() {
        assertEquals(
            MonitorRepository.PendingWritePlan.Create,
            MonitorRepository.pendingWritePlan(
                rowExists = false,
                rowMonitorGeneration = 0L,
                rowStreamerGeneration = 0L,
                monitorGeneration = 9L,
                streamerMonitorGeneration = 4L
            )
        )
    }

    @Test
    fun `同一代际沿用计数`() {
        assertEquals(
            MonitorRepository.PendingWritePlan.Keep,
            MonitorRepository.pendingWritePlan(
                rowExists = true,
                rowMonitorGeneration = 9L,
                rowStreamerGeneration = 4L,
                monitorGeneration = 9L,
                streamerMonitorGeneration = 4L
            )
        )
    }

    @Test
    fun `全局监控代际变化必须重建计数行`() {
        // 进程重启 / 租约被接管：monitorGeneration 递增，主播级代际不变
        assertEquals(
            MonitorRepository.PendingWritePlan.ResetForNewGeneration,
            MonitorRepository.pendingWritePlan(
                rowExists = true,
                rowMonitorGeneration = 8L,
                rowStreamerGeneration = 4L,
                monitorGeneration = 9L,
                streamerMonitorGeneration = 4L
            )
        )
    }

    @Test
    fun `主播级代际变化必须重建计数行`() {
        // 暂停后恢复监控 / 软删除后恢复：streamerMonitorGeneration 递增
        assertEquals(
            MonitorRepository.PendingWritePlan.ResetForNewGeneration,
            MonitorRepository.pendingWritePlan(
                rowExists = true,
                rowMonitorGeneration = 9L,
                rowStreamerGeneration = 4L,
                monitorGeneration = 9L,
                streamerMonitorGeneration = 5L
            )
        )
    }

    // ---- 验收用例：老库 endConfirmationCount = 2 ----

    /**
     * 负向对照 + 修复后行为，用一个用例把两件事都写清楚：
     *
     *  1. **修复前**：代际重建是死代码（IGNORE ⇒ no-op），那一行仍是"中断前攒下的 1 次"，
     *     而阈值是 2 ⇒ 恢复后的**第一次**观察就让 `StateConfirmationPolicy.detect` 判定
     *     `Transition(OFFLINE)` —— 一次观察就"确认"了关播（早一拍的假关播）。
     *     下面第一个断言就是这个修复前行为，它**必须**成立，否则说明我们对缺陷的理解是错的。
     *  2. **修复后**：`pendingWritePlan` 判定必须重建（计数归零），于是同一次观察只得到
     *     `Counting(SUSPECTED_OFFLINE, 1)`，要等第二次连续确认才会真的关播。
     */
    @Test
    fun `老库阈值 2：中断恢复后的第一次观察不得直接判定关播`() {
        // ① 修复前：沿用中断前的计数（1）⇒ 直接凑满阈值 2
        val preFix = detectOffline(
            pending = PendingTransition.SUSPECTED_OFFLINE,
            count = 1,
            endConfirmationCount = 2
        )
        assertEquals(
            ConfirmedLiveStatus.OFFLINE,
            (preFix as StateConfirmationPolicy.Decision.Transition).to
        )

        // ② 修复后：代际变化 ⇒ 必须重建（这正是被 IGNORE 吃掉的那一步）
        assertEquals(
            MonitorRepository.PendingWritePlan.ResetForNewGeneration,
            MonitorRepository.pendingWritePlan(
                rowExists = true,
                rowMonitorGeneration = 1L,
                rowStreamerGeneration = 1L,
                monitorGeneration = 2L,
                streamerMonitorGeneration = 1L
            )
        )

        // ③ 计数归零后，同一次观察只能"记一笔"，不能确认关播
        val afterFix = detectOffline(
            pending = PendingTransition.NONE,
            count = 0,
            endConfirmationCount = 2
        ) as StateConfirmationPolicy.Decision.Counting
        assertEquals(PendingTransition.SUSPECTED_OFFLINE, afterFix.transition)
        assertEquals(1, afterFix.newCount)
    }

    @Test
    fun `同一代际内计数照常累加到阈值`() {
        // 反向保护：重置只发生在代际变化时，正常连续的第二次观察必须能过阈值
        val second = detectOffline(
            pending = PendingTransition.SUSPECTED_OFFLINE,
            count = 1,
            endConfirmationCount = 2
        ) as StateConfirmationPolicy.Decision.Transition
        assertEquals(ConfirmedLiveStatus.OFFLINE, second.to)
    }

    // ---- 判据 2：计数 CAS 返回 0 行时为什么没写进去 ----

    @Test
    fun `计数行已被删除时判为 RowGone`() {
        assertEquals(
            MonitorRepository.PendingCountSkip.RowGone,
            MonitorRepository.pendingCountSkipOf(
                rowExists = false,
                rowLastObservationSequence = 0L,
                mySequence = 42L,
                monitoringEnabled = true
            )
        )
    }

    @Test
    fun `已有更新的观察写过计数时判为 NewerObservation`() {
        assertEquals(
            MonitorRepository.PendingCountSkip.NewerObservation,
            MonitorRepository.pendingCountSkipOf(
                rowExists = true,
                rowLastObservationSequence = 43L,
                mySequence = 42L,
                monitoringEnabled = true
            )
        )
        // 序号相同也算"别人已经写过这一次"：CAS 用的是严格小于
        assertEquals(
            MonitorRepository.PendingCountSkip.NewerObservation,
            MonitorRepository.pendingCountSkipOf(
                rowExists = true,
                rowLastObservationSequence = 42L,
                mySequence = 42L,
                monitoringEnabled = true
            )
        )
    }

    @Test
    fun `主播已暂停时判为 MonitoringPaused`() {
        assertEquals(
            MonitorRepository.PendingCountSkip.MonitoringPaused,
            MonitorRepository.pendingCountSkipOf(
                rowExists = true,
                rowLastObservationSequence = 41L,
                mySequence = 42L,
                monitoringEnabled = false
            )
        )
    }

    /**
     * 三条条件都成立却仍然 0 行 = 不可能（重读与那次 UPDATE 用同一个事务快照）。
     * 返回 null 是**自检**：一旦审计里出现 `sql_mirror_drift`，说明 SQL 改了而这里没跟上。
     * 这里显式断言它，避免将来有人"顺手"给一个假的枚举值糊过去。
     */
    @Test
    fun `三条件都成立时返回 null（SQL 与函数镜像未漂移的自检）`() {
        assertNull(
            MonitorRepository.pendingCountSkipOf(
                rowExists = true,
                rowLastObservationSequence = 41L,
                mySequence = 42L,
                monitoringEnabled = true
            )
        )
    }

    // ---- 测试夹具 ----

    /** 主播当前 LIVE、远端观察到 OFFLINE，走"连续 N 次确认"的关播方向。 */
    private fun detectOffline(
        pending: PendingTransition,
        count: Int,
        endConfirmationCount: Int
    ): StateConfirmationPolicy.Decision = StateConfirmationPolicy.detect(
        context = StreamerMonitorContext(
            confirmedLiveStatus = ConfirmedLiveStatus.LIVE,
            pendingTransition = pending,
            pendingConfirmationCount = count,
            hasActiveReliableInterval = true,
            startConfirmationCount = 1,
            endConfirmationCount = endConfirmationCount
        ),
        observation = ObservationResult.SUCCESS,
        remote = RemoteLiveRoom(
            uid = 1L, roomId = 2L, shortRoomId = null, name = "n", avatarUrl = null,
            roomTitle = null, parentAreaName = null, areaName = null, coverUrl = null,
            liveUrl = null, remoteLiveStatus = ConfirmedLiveStatus.OFFLINE, remoteStatusValid = true
        )
    )
}
