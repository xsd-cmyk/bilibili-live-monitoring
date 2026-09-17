package com.example.bilimonitor.domain.policy

/**
 * 一轮 Tick 内「哪些主播最终没拿到有效结果」的**账面**（纯数据、无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么必须有它（缺陷 1：失败集合只增不减 ⇒ 必然误报"全局降级"）
 * 修复前引擎里的 `RoundFailures` 只有 `+=`、**没有任何移除路径**：第一轮失败的 stableId
 * 会一直留在集合里，重试轮里"已经拿到有效结果"的主播也不会被移出。后果是**必然误报**：
 *  ① 进入重试轮的**前提**就是第一轮失败占比越线（[RoundFailureRatio.exceeds]）；
 *  ② 末轮重算 ratio 用的却是"第一轮失败集合"（只增不减）⇒ 只要走进重试轮，末轮必然再次越线
 *     ⇒ 必然调用引擎的全局降级路径 ⇒ 开 SYSTEM 盲区，连续 3 轮后再发「网络不可用，监控出现盲区」。
 * 这恰好发生在"大面积瞬时失败 → 重试成功"这个功能**唯一的目标场景**上（100% 误报）。
 *
 * ## 口径（与 [RoundFailureRatio] 一致，且只有一份真相）
 *  - 分子 = [failedStableIds]：以**主播**为单位、一轮内去重（同一主播重试多少次都只算 1）；
 *  - [reasonCounts] 恒等于 [failedStableIds] 中每位主播**最近一次**失败原因的计数，
 *    于是"还剩谁在失败"与"这些人为什么失败"永远是同一时刻的同一批数据 ——
 *    全局归因（[RoundFailureGate.globalFailureCause]）就建立在这个不变式上；
 *  - 重试（或批次二分隔离）后拿到有效结果的主播**必须**调用 [resolve] 回收：
 *    只加不减就是上面那条必然误报；
 *  - [observedReasons] 保留本轮**出现过**的全部原因（含已被回收的），只用于日志 detail：
 *    "这一轮到底有哪些机制出错"不能因为某人后来恢复了就丢掉。
 *
 * ## 线程安全
 * 本类**自己不加锁**（调用方持锁使用，见引擎里的 `RoundFailures` 包装）：批次请求是并发的，
 * 加锁责任放在唯一入口，避免两处各自加锁而漏掉一条路径。
 */
class RoundFailureLedger {

    /** 仍处于失败状态的主播 → 它**最近一次**的失败原因；null = 确实失败、但上游没给出原因。 */
    private val failedByStreamer = linkedMapOf<String, RoomFailureReason?>()

    /** 本轮出现过的全部原因（含已回收的）。 */
    private val observed = linkedSetOf<RoomFailureReason>()

    /** 仍然失败的主播（比例门禁的分子）。 */
    val failedStableIds: Set<String> get() = failedByStreamer.keys

    /** 与 [failedStableIds] 同源的失败主播数（禁止另立一份局部累加变量）。 */
    val failedCount: Int get() = failedByStreamer.size

    val isEmpty: Boolean get() = failedByStreamer.isEmpty()

    /** 主导原因判定用的计数：原因 → 仍失败的主播数（原因未知的主播不计入）。 */
    val reasonCounts: Map<RoomFailureReason, Int>
        get() = failedByStreamer.values.filterNotNull().groupingBy { it }.eachCount()

    /** 仍然失败的主播的失败原因集合（= [reasonCounts] 的键集）。 */
    val reasons: Set<RoomFailureReason> get() = reasonCounts.keys

    /** 本轮**出现过**的全部原因（含已回收的）。 */
    val observedReasons: Set<RoomFailureReason> get() = observed

    /**
     * 记一次**无法归属到具体主播**的失败信号（整批被服务端拒绝、子批重问失败…）。
     *
     * 只进 [observedReasons]：它没有对应的失败主播，因此**不参与**主导原因判定 ——
     * 否则一个坏批次就能把整轮的归因带偏。
     */
    fun observe(reason: RoomFailureReason) {
        observed += reason
    }

    /**
     * 记一次失败，并把它归到 [stableIds] 名下。
     *
     * 同一主播再次失败会**覆盖**旧原因：重试轮里"上次超时、这次被限流"必须以最新一次为准，
     * 否则归因会停留在已经过去的原因上（[reasonCounts] 也就不是"这批人现在为什么失败"）。
     */
    fun record(reason: RoomFailureReason, stableIds: Collection<String>) {
        observed += reason
        for (stableId in stableIds) failedByStreamer[stableId] = reason
    }

    /**
     * 记一次"确实失败、但上游没给出原因"（例如 uid 缺失且整批也没有可用的失败信号）。
     *
     * **不编造原因**：计入分子（占比必须如实），但不进 [reasonCounts]
     * （归因只能建立在真实信号上，宁可落到 [RoundFailureGate.GlobalFailureKind.UNKNOWN]）。
     */
    fun recordUnknownReason(stableIds: Collection<String>) {
        for (stableId in stableIds) failedByStreamer[stableId] = null
    }

    /**
     * 回收：这些主播已经拿到有效结果，从账面上移除（**本次修复的核心动作**）。
     *
     * 幂等：本来就不在账面上的 id 是空操作（第一轮就成功的主播天然不在账面上）。
     */
    fun resolve(stableIds: Collection<String>) {
        if (stableIds.isEmpty()) return
        for (stableId in stableIds) failedByStreamer.remove(stableId)
    }
}
