package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.data.local.TransitionReason
import com.example.bilimonitor.domain.model.RemoteLiveRoom

/**
 * 状态确认策略的输入快照（来自 streamer 行 + pending 行 + interval 状态，均由 Tick 读取）。
 *
 * 注意：这里只保留**策略真正会读**的字段。
 * 原实现还带 `hasOpenStreamerGap` / `streamerStableId` / `streamerId` / `uid`，
 * 其中 `hasOpenStreamerGap` 每个主播每个 Tick 都要查一次库（`SELECT EXISTS`），
 * 却在 `detect` 中从未被读取 —— 纯属白耗。已移除以免误导后续维护者。
 */
data class StreamerMonitorContext(
    val confirmedLiveStatus: ConfirmedLiveStatus,
    val pendingTransition: PendingTransition,
    /**
     * SUSPECTED_OFFLINE 方向的连续确认次数。
     * 只有 [pendingTransition] == SUSPECTED_OFFLINE 时才应传入非 0 值：
     * 计数列在库中只有一个，切换方向必须从 0 重新计数，否则会把另一方向攒下的次数带过来
     * （例如 SUSPECTED_LIVE 已攒 2 次后转为 SUSPECTED_OFFLINE，会立刻凑满阈值误报关播）。
     * 该转换由调用方在构造上下文时完成，策略本身保持无状态。
     */
    val pendingConfirmationCount: Int,
    val hasActiveReliableInterval: Boolean,
    val startConfirmationCount: Int,
    val endConfirmationCount: Int,
    /**
     * SUSPECTED_LIVE 方向的连续确认次数（0.6.47.3）。
     * 与 SUSPECTED_OFFLINE 一样，仅在方向匹配时传入非 0 值。
     */
    val pendingLiveConfirmationCount: Int = 0
)

/**
 * 状态确认状态机（原规范 44 / 0.6.5 / 0.6.47.3）：
 *  - 只有 SUCCESS 观察参与转换；ERROR/PARTIAL/INVALID 保持上一次已确认状态；
 *  - LIVE 方向默认 1 次确认；OFFLINE 方向默认 1 次连续确认（"默认"指**新装库**的种子值，
 *    老库沿用库里的已有值、不静默改写；中间夹杂任何非 SUCCESS 归零）；
 *  - 首次观察（UNKNOWN）建立基线：立即确认，不产生 START 通知（reason = FIRST_OBSERVATION）；
 *  - 异常/盲区结束后重新确认 LIVE：LIVE_RECONFIRMED + "正在直播"（reason = RECOVERY_RECONFIRM）；
 *  - ROUND 是成功观察到的另一种业务状态，不算异常。
 */
object StateConfirmationPolicy {

    sealed interface Decision {
        /** 无状态变化；payload 表示是否需要把 pending 复位（SUCCESS 且与当前一致）。 */
        data object None : Decision

        /** 仅推进 pending 计数。 */
        data class Counting(val transition: PendingTransition, val newCount: Int) : Decision

        /** 确认转换（尚未提交，提交由 applyConfirmedObservationAtomically 完成）。 */
        data class Transition(
            val from: ConfirmedLiveStatus,
            val to: ConfirmedLiveStatus,
            val reason: TransitionReason,
            /** true 表示状态不变（LIVE→LIVE 的恢复再确认事件）。 */
            val reconfirmOnly: Boolean
        ) : Decision
    }

    /**
     * 判定一次观察的结果。
     *
     * 说明：原先还有一个 `observationSequence: Long` 形参，但从未被使用，已移除。
     * 「连续确认」中的**归零**不由本函数负责（本函数是无状态的）：
     * 非 SUCCESS 观察返回 [Decision.None]，由 `MonitorRepository.applyObservationOnly`
     * 在这条路径上复位 pending 行（见该处注释）。
     */
    fun detect(
        context: StreamerMonitorContext,
        observation: ObservationResult,
        remote: RemoteLiveRoom?
    ): Decision {
        if (observation != ObservationResult.SUCCESS) return Decision.None
        val remoteStatus = remote?.remoteLiveStatus ?: return Decision.None

        val current = context.confirmedLiveStatus
        if (current == remoteStatus) {
            // 与当前一致但区间已失效（盲区/异常/代际重置后）：恢复再确认（0.6.5）。
            if (current == ConfirmedLiveStatus.LIVE && !context.hasActiveReliableInterval) {
                return Decision.Transition(
                    from = current, to = current,
                    reason = TransitionReason.RECOVERY_RECONFIRM, reconfirmOnly = true
                )
            }
            // 连续确认要求中间无变化：一致的成功观察把计数复位（原规范 44）。
            return if (context.pendingTransition != PendingTransition.NONE ||
                context.pendingConfirmationCount != 0
            ) {
                Decision.Counting(PendingTransition.NONE, 0)
            } else {
                Decision.None
            }
        }

        // 首次观察：建立基线，立即确认。
        if (current == ConfirmedLiveStatus.UNKNOWN) {
            return Decision.Transition(
                from = current, to = remoteStatus,
                reason = TransitionReason.FIRST_OBSERVATION, reconfirmOnly = false
            )
        }

        return when {
            // OFFLINE 方向：连续 N 次确认（N 取自库里的 endConfirmationCount，新装库默认 1）。
            // ★ 用户要求：ROUND（轮播）按**未开播**处理，因此它的确认也走这条、
            //   用 endConfirmationCount —— 与 MonitorRepository 里
            //   "ROUND 并入 OFFLINE 分支"保持一致；也避免 B 站状态抖动时
            //   把一场真实直播误切成两段。
            remoteStatus == ConfirmedLiveStatus.OFFLINE ||
                remoteStatus == ConfirmedLiveStatus.ROUND -> {
                val needed = context.endConfirmationCount.coerceAtLeast(1)
                val newCount = if (context.pendingTransition == PendingTransition.SUSPECTED_OFFLINE)
                    context.pendingConfirmationCount + 1 else 1
                if (newCount >= needed) {
                    Decision.Transition(
                        // 目标状态写回**实际观察到的**状态：轮播仍记为 ROUND（界面/历史要能看出是轮播），
                        // 但"要不要关场次"由 MonitorRepository 决定 —— 那里 ROUND 与 OFFLINE 同分支。
                        from = current, to = remoteStatus,
                        reason = TransitionReason.CONFIRMATION_COUNT_REACHED, reconfirmOnly = false
                    )
                } else {
                    Decision.Counting(PendingTransition.SUSPECTED_OFFLINE, newCount)
                }
            }

            // LIVE 方向：连续 N 次确认，N = startConfirmationCount（默认 1 → 立即生效）。
            // （ROUND 已移到上面的 OFFLINE 方向，不再走这里。）
            // 0.6.47.3：确认阈值是"单一阈值"，同一个计数同时决定状态变更与是否通知，
            // 因此 startConfirmationCount 必须在这里生效 —— 原实现恒按 1 次处理，
            // 使该配置项（全局与主播级）完全失效。
            else -> {
                val needed = context.startConfirmationCount.coerceAtLeast(1)
                val newCount = if (context.pendingTransition == PendingTransition.SUSPECTED_LIVE)
                    context.pendingLiveConfirmationCount + 1 else 1
                if (newCount >= needed) {
                    Decision.Transition(
                        from = current, to = remoteStatus,
                        reason = TransitionReason.CONFIRMATION_COUNT_REACHED, reconfirmOnly = false
                    )
                } else {
                    Decision.Counting(PendingTransition.SUSPECTED_LIVE, newCount)
                }
            }
        }
    }

    fun transitionIdOf(
        streamerStableId: String,
        eventSequence: Long,
        from: ConfirmedLiveStatus,
        to: ConfirmedLiveStatus
    ): String = Ids.transitionId(streamerStableId, eventSequence, from.name, to.name)
}
