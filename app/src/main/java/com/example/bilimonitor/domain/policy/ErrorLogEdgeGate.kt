package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.AppError
import java.util.concurrent.ConcurrentHashMap

/**
 * 「同一位主播、同一个失败原因」只落一条的**边沿抑制器**（纯函数类，可 JVM 单测）。
 *
 * ## 为什么必须有它（缺陷 3：失败留痕无去重 ⇒ 98 条/分钟）
 * 观测失败分支修复前**无条件**往 `monitoring_error_log` 插一行：
 * 98 位主播全部失败时就是 98 行/分钟 ≈ **14 万行/天**，而诊断包只取最近
 * `DiagnosticExporter.DIAGNOSTIC_LIMIT = 500` 行 —— 真正的故障现场会被自己刷掉
 * （用户抱怨的"刷屏"就是它，量级比本轮汇总日志那条大 100 倍）。
 * 对照：本轮汇总日志与封禁探针失败**都已有**边沿抑制，唯独最热的这条没有。
 *
 * ## 语义（边沿触发，而不是"每轮一条"）
 *  - key = 主播 stableId；signature = 失败原因指纹（见 [signatureOf]）；
 *  - 同一 (key, signature) 连续出现 → 只第一条落库，其余抑制（事实保留，噪声消失）；
 *  - 原因**变化**（超时 → 限流）或主播不同 → 各记一条（那是新信息，不是重复）；
 *  - 该主播恢复正常 → 调用 [clear]；下次再失败（哪怕信号完全相同）重新记一条 ——
 *    "一段故障的结束"与"它再次开始"都值得留痕（下降沿）。
 *
 * ## 与 `BanLogEdgeGate` 的关系
 * 同构（同样的 `put` 返回值判定），但**刻意独立成类**而不是复用：
 *  - 这里的 key 语义是"主播 + 观测失败原因"，封禁那条是"主播 + 封禁探针信号"，
 *    共用一张表会互相把对方的指纹冲掉（同一主播上两条链路会同时活跃）；
 *  - 纯内存、零 IO：失败路径上最不该加的是一次查库，加一列/一次迁移更不值当。
 *
 * 代价（如实说明）：**进程重启后会重新记一条**。频率上界因此是
 * "每进程 × 每主播 × 每种原因一条"，而不是"每轮一条"—— 进程重启后在新日志里
 * 重新声明一次"这个故障还在"本身也是合理的。
 */
class ErrorLogEdgeGate {

    /** key（主播 stableId）→ 上次**已记录**的信号指纹。 */
    private val lastRecorded = ConcurrentHashMap<String, String>()

    /**
     * @return true = 与上次记录的信号不同（该落库）；false = 同一信号（抑制，不落库）。
     */
    fun shouldRecord(key: String, signature: String): Boolean =
        lastRecorded.put(key, signature) != signature

    /** 该主播恢复正常时调用：下次再出问题仍然值得记一条。 */
    fun clear(key: String) {
        lastRecorded.remove(key)
    }

    /** 当前跟踪的 key 数（供测试与诊断；只增不减是 bug，靠 [clear] 收敛）。 */
    fun tracked(): Int = lastRecorded.size

    companion object {
        /** 上游没给出任何失败信号时的指纹占位（**不许猜**成某个具体原因）。 */
        const val UNKNOWN_REASON = "UNKNOWN"

        /**
         * 失败原因指纹 = `错误码|原因`。
         *
         * 为什么把**错误码**也放进去：观测失败分支有一条"引擎没给出原始信号"的回退路径
         * （按 `ObservationResult` 推断错误码，此时 [reason] 为 null）——
         * 只按原因做指纹会让那条路径下所有失败都塌成同一个 `UNKNOWN`，
         * "网络超时"与"接口拒绝"混为一谈、后者被白白抑制掉。
         *
         * 为什么不带 HTTP 状态码 / 业务 code / message：那些字段会随上游文案波动，
         * 把它们放进指纹等于给抑制器开后门（message 一变就重新刷屏）——
         * 原始信号已经完整写在 `detail` 里，指纹只需要**稳定且够用**。
         */
        fun signatureOf(errorCode: AppError, reason: RoomFailureReason?): String =
            "${errorCode.name}|${reason?.name ?: UNKNOWN_REASON}"
    }
}
