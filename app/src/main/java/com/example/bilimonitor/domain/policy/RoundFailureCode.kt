package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.AppError

/**
 * 一轮 Tick **汇总级**错误码的选择规则（纯函数，可 JVM 单测）。
 *
 * ## 为什么需要它（缺陷：写死的 API_REJECTED）
 * 引擎在"本轮只有少数主播失败、没超过重试阈值"时会写一条汇总日志，修复前它的
 * `errorCode` **恒为** `AppError.API_REJECTED`（"接口被拒绝"），而同一行 detail 里的
 * `原因集合=` 却可能是 `TIMEOUT` / `RATE_LIMITED` / `MALFORMED_RESPONSE`……
 * 于是用户按"接口被拒绝"去查，方向从第一步就是错的（真实原因往往只是一次超时），
 * 而且统计上会把所有零散失败都算成"服务端拒绝"。
 *
 * 现在改成：由**本轮真实出现过的失败原因**（`RoundFailures.reasons`）选码，
 * 选不出来（集合为空）才用 [AppError.UNKNOWN]。
 *
 * ## 选择规则
 * 取集合中"最具体"的那个原因，再经 [RoomFailureMapping.appErrorOf] 这张**全项目唯一**的
 * 映射表得到错误码 —— 映射仍然只有一处，这里只决定"多个原因里挑哪一个代表本轮"：
 *
 *  1. [RoomFailureReason.TIMEOUT] / [RoomFailureReason.MALFORMED_RESPONSE] /
 *     [RoomFailureReason.MISSING_IN_RESPONSE]：机制明确、可区分（分别得到
 *     `NETWORK_TIMEOUT` / `API_INVALID_RESPONSE`），优先；
 *  2. [RoomFailureReason.RATE_LIMITED]：现在有独立错误码 `RATE_LIMITED`
 *     （`AppError` 里新增取值**不需要迁移**：枚举列没有 DDL CHECK，详见
 *     [RoomFailureMapping.appErrorOf] 的说明），因此它比"网络不可用"族更具体；
 *  3. [RoomFailureReason.SERVER_ERROR]：按"网络不可用"记录（真实传输故障）；
 *  4. [RoomFailureReason.API_REJECTED]：**只有在没有任何更具体的机制时才代表本轮**
 *     （例如集合就是 `{API_REJECTED}`，或还有兜底的 NETWORK）。这正是修复点：
 *     这个码重新变成"确实有服务端明确拒绝"的意思，而不是每次都写它；
 *  5. [RoomFailureReason.NETWORK]：兜底，永远排最后。
 *
 * 集合里的原因会**一个不丢**地写进 detail（`原因集合=…`），所以即便某个混合场景下
 * 选出来的码只代表其中一类，"这一轮到底有哪些机制出错"在库里依然查得到。
 */
object RoundFailureCode {

    /**
     * 代表本轮的失败原因；集合为空（本轮没有记录到任何失败原因）时返回 null。
     *
     * 顺序 = 从上到下"具体程度"，详见文件头。`SERVER_ERROR` / `NETWORK` 两者都映射到
     * `NETWORK_UNAVAILABLE`，它们之间的先后不影响最终错误码；`RATE_LIMITED` 现在有自己的
     * 错误码，因此排序会真实影响结果（它排在 `SERVER_ERROR` 之前）。
     */
    private val PRIORITY = listOf(
        RoomFailureReason.TIMEOUT,
        RoomFailureReason.MALFORMED_RESPONSE,
        RoomFailureReason.MISSING_IN_RESPONSE,
        RoomFailureReason.RATE_LIMITED,
        RoomFailureReason.SERVER_ERROR,
        RoomFailureReason.API_REJECTED,
        RoomFailureReason.NETWORK
    )

    /** 本轮的代表性原因；`reasons` 为空时返回 null（= 没有可映射的失败）。 */
    fun dominantReason(reasons: Collection<RoomFailureReason>): RoomFailureReason? =
        PRIORITY.firstOrNull { it in reasons }

    /**
     * 汇总日志的错误码。
     *
     * 空集合 ⇒ [AppError.UNKNOWN]：**不许猜**。集合为空意味着"有主播失败了，但一个失败原因
     * 都没被记录下来"（例如失败路径的上游没给出原因），此时任何一个具体错误码都是编的。
     */
    fun appErrorOf(reasons: Collection<RoomFailureReason>): AppError =
        dominantReason(reasons)?.let { RoomFailureMapping.appErrorOf(it) } ?: AppError.UNKNOWN
}
