package com.example.bilimonitor.domain.policy

/**
 * 请求级重试与熔断的纯计算规则（原规范 180 / 246.5）。
 *
 * 抽成不依赖 Android 与配置对象的纯函数，便于单测覆盖（0.6.28 门槛），
 * 同时让引擎只负责调度、不内嵌阈值算术。
 */
object RetryPolicy {

    /** 重试次数硬上限，防止配置被写坏导致单批次长时间占用 Tick。 */
    const val MAX_ATTEMPTS_CEILING = 10

    /** 总请求次数 = 首次 + 重试次数。 */
    fun totalAttempts(maxRetries: Int): Int = maxRetries.coerceIn(0, MAX_ATTEMPTS_CEILING) + 1

    /**
     * 第 [attempt] 次失败后的退避毫秒数（attempt 从 0 开始）：
     * `base * 2^attempt`，上限 [maxDelaySeconds]。
     */
    fun backoffMillis(attempt: Int, baseSeconds: Int, maxDelaySeconds: Int): Long {
        val base = baseSeconds.coerceAtLeast(1) * 1000L
        val cap = maxDelaySeconds.coerceAtLeast(1) * 1000L
        val shift = attempt.coerceIn(0, 6)
        return (base shl shift).coerceAtMost(cap)
    }

    /** 请求级熔断的最小内部状态（与 Android 时钟解耦）。 */
    data class BreakerState(
        val consecutiveFailures: Int = 0,
        val openUntilElapsed: Long = 0L
    ) {
        fun isOpen(nowElapsed: Long): Boolean = openUntilElapsed > 0L && nowElapsed < openUntilElapsed

        /**
         * 记录一次请求结果。
         * 成功 → 清零；失败累计达到 [threshold] → 打开 [recoverySeconds] 秒。
         */
        fun record(success: Boolean, threshold: Int, recoverySeconds: Int, nowElapsed: Long): BreakerState {
            if (success) return BreakerState()
            val failures = consecutiveFailures + 1
            val limit = threshold.coerceAtLeast(1)
            return if (failures >= limit) {
                BreakerState(
                    consecutiveFailures = failures,
                    openUntilElapsed = nowElapsed + recoverySeconds.coerceAtLeast(1) * 1000L
                )
            } else {
                BreakerState(consecutiveFailures = failures, openUntilElapsed = 0L)
            }
        }
    }
}
