package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.ObservationResult

/**
 * 单个 uid / 单批房间状态请求**失败原因**的结构化表达。
 *
 * 为什么必须有它：原实现把"HTTP 请求失败"这件事压成一个 `null`，
 * `monitoring_error_log.detail` 恒为 `null`，于是一次失败到底是
 * 「超时 / 网络不可用 / HTTP 4xx 风控 / HTTP 5xx / 服务端拒绝 / 响应解析失败」
 * 在库里**完全不可区分** —— 这正是当初无法定位问题的根因。
 *
 * 本枚举刻意只描述**机制**，不描述"这位主播怎么了"：
 * 上游没有任何一个信号能唯一指向某个业务结论（详见 [RoundFailureGate] 的文件级说明），
 * 所以这里不允许出现 `BANNED` / `ROOM_CLOSED` 这类需要业务判定的取值。
 */
enum class RoomFailureReason {
    /** 连接/读取超时（`okhttp` SocketTimeout 或引擎侧 `withTimeout`）。 */
    TIMEOUT,

    /** 连接失败、DNS、Socket 关闭等 IO 异常；调用方未给出更具体分类时的兜底。 */
    NETWORK,

    /** HTTP 429 或带 `Retry-After` 的 4xx —— 服务端限流，**必须**与普通网络失败区分（原规范 246.5）。 */
    RATE_LIMITED,

    /** HTTP 5xx —— 服务端故障。 */
    SERVER_ERROR,

    /** HTTP 2xx 但业务 `code != 0`，或 HTTP 401/403/404/412 这类服务端明确拒绝。 */
    API_REJECTED,

    /** HTTP 2xx 且 `code == 0`，但该 uid 不在返回里、或 `live_status` 缺失/越界 —— 响应不可用。 */
    MISSING_IN_RESPONSE,

    /** 解析失败（字段类型变化、HTML 错误页、空响应体）。 */
    MALFORMED_RESPONSE;

    /** 是否可以安全地再试一次（限流与业务拒绝重试没有意义，只会加重风控）。 */
    val retryable: Boolean
        get() = when (this) {
            TIMEOUT, NETWORK, SERVER_ERROR, MALFORMED_RESPONSE -> true
            RATE_LIMITED, API_REJECTED, MISSING_IN_RESPONSE -> false
        }
}

/**
 * 一次批次请求失败携带的**原始信号**（全部可空：拿不到就如实留空，不猜）。
 *
 * 这些值只用于两件事：① 写进 `monitoring_error_log.detail` 供日后定位；
 * ② 给 [RoundFailureGate] 做保守的"要不要重试"判断。**不参与任何业务状态判定。**
 */
data class CallFailure(
    val reason: RoomFailureReason,
    /** HTTP 状态码（`HttpException.code()`）；非 HTTP 失败时为 null。 */
    val httpStatus: Int? = null,
    /** B 站业务错误码（响应体的 `code`）；不是业务错误时为 null。 */
    val apiCode: Int? = null,
    /** 服务端 `message` 摘要（已截断）。 */
    val apiMessage: String? = null,
    /** 服务端 `Retry-After` 头（秒）；仅限流时有意义。 */
    val retryAfterSeconds: Int? = null
) {
    /**
     * 是否可安全重试。
     *
     * 直接转发 [RoomFailureReason.retryable]，**不要在调用点各写一份判据** —— 判据只有一处
     * （限流与业务拒绝重试没有意义、只会加重风控），否则两处迟早不一致。
     */
    val retryable: Boolean get() = reason.retryable
}

/**
 * 一轮 Tick 的失败占比门禁（用户诉求：一个坏房间不得拖垮全体检测）。
 *
 * ## 语义定义（写进注释是为了让边界不被后续改动悄悄改掉）
 *  - **分母 [total]**：本轮**参与检查**的主播数，即本轮真正发起了请求的那些主播。
 *    被调度跳过的（监控关闭 / 已被本轮隔离的坏房间）不计入分母。
 *  - **分子 [failed]**：本轮**最终未能得到有效结果**的主播数。
 *  - **同一个主播连续失败多次只算 1**：分母与分子都以主播为单位，且一轮内按主播去重 ——
 *    重试次数、批次划分都不会让某个主播被重复计数（否则"重试"会自我放大成"更多失败"）。
 *  - **百分比取整**：`failed * 100 / total` 向下取整。
 *  - **比较是严格大于**：`percent > thresholdPercent` 才触发。
 *    因此默认阈值 50 时，`49/98 = 50%` **不触发**，`50/98 = 51%` 触发，
 *    与用户原话"超过 50% 才触发"逐字一致。
 *  - **分母为 0**：不触发（没有检查过任何主播，不能据此判定系统故障）。
 *  - **只有 1 位主播且它失败**：`1/1 = 100% > 50%` → 触发（这是唯一能反映"全军覆没"的信号）。
 */
data class RoundFailureRatio(val failed: Int, val total: Int) {

    /** 失败占比（百分比，向下取整）。分母为 0 时为 0。 */
    val percent: Int
        get() = if (total <= 0) 0 else (failed.toLong() * 100L / total.toLong()).toInt()

    /**
     * 是否允许本轮触发"重试 / 熔断 / 全局降级"。
     *
     * 阈值被夹到 1..100：0 会让任何一次失败都触发全局熔断（回到用户报告的 bug），
     * 而 100 只在**全员失败**时触发 —— 两者都是有意义的极端配置，故保留但不越界。
     */
    fun exceeds(thresholdPercent: Int): Boolean {
        val limit = thresholdPercent.coerceIn(1, 100)
        if (total <= 0 || failed <= 0) return false
        return percent > limit
    }

    companion object {
        /** 用户可配范围（与 `ConfigRepository.merge` 的校验、设置页 `validRange` 三处必须同步）。 */
        val THRESHOLD_RANGE = 1..100

        /** 新装默认：超过 50% 才触发。 */
        const val DEFAULT_THRESHOLD_PERCENT = 50
    }
}

/**
 * 请求级重试与熔断的**总门禁**（用户诉求 3）。
 *
 * ## 与既有 `circuitBreakerThreshold` 的关系（两者共存，不冲突）
 *  - 既有 `circuitBreakerThreshold`（连续 N 次失败后暂停请求）**语义不变**，
 *    但它现在只在"本轮失败占比已越线"的**二次重试轮**里才被计数。
 *  - 新增的比例门禁决定"这一轮**要不要**进入重试与熔断计数"；
 *    次数阈值决定"越线之后**何时**真正打开熔断"。
 *  - 于是：少数主播失败（1 个坏房间 / 一批坏房间）→ 不越线 → 不重试、不计熔断、其余主播照常检测；
 *    大面积失败（网络断了）→ 越线 → 重试 + 计熔断 + 打开系统盲区。
 *
 * ## 为什么"一个坏房间"不会再拖垮全体（原实现的 bug 链路）
 * 原实现里 `fetchBatchWithRetry` 对**每一个批次**无条件重试，且每次最终失败都计一次熔断；
 * 一个持续被拒绝的房间会让它所在批次每轮都失败，连续 N 轮就把**全局**熔断打开，
 * 之后整轮 Tick 直接返回 `circuit_open` 且把所有主播记成 NETWORK_ERROR —— 其他主播一并无法检测。
 * 现在熔断的入口被比例门禁卡住，这条链路不再成立。
 */
object RoundFailureGate {

    /**
     * 本轮是否应该进入"二次重试轮"。
     *
     * 三个条件同时满足才重试：
     *  ① 失败占比**严格超过**阈值；
     *  ② 至少有 1 个主播失败（分母/分子都 > 0）；
     *  ③ 失败里至少有一个是[RoomFailureReason.retryable]的（全是限流/业务拒绝时重试毫无意义）。
     */
    fun shouldRunRetryPass(
        ratio: RoundFailureRatio,
        thresholdPercent: Int,
        failureReasons: Collection<RoomFailureReason>
    ): Boolean = ratio.exceeds(thresholdPercent) && failureReasons.any { it.retryable }

    /**
     * 是否**允许降级**（占比是否越线）：只回答"要不要把这件事升级成系统级事件"。
     *
     * 与重试门禁共用同一个比例判据：局部失败不再开系统盲区、不再发"网络不可用"通知，
     * 但每个失败主播自己的监控错误日志照常记录（降级**不静默**，只是不再升级成全局事故）。
     *
     * ★ 与 [globalFailureCause] 的分工（缺陷 2）：
     *   本函数只管"要不要降级"，**不管"按哪种原因降级"**。归因（网络事故 / 接口事故 / 原因未知）
     *   由 [globalFailureCause] 决定，生产路径一律用它；保留本函数是因为
     *   "占比越线但原因全是接口拒绝"**仍然要降级**（用户必须知道监控出现盲区），
     *   只是不允许再宣称"网络不可用" —— 这条语义由单测锁定。
     */
    fun shouldDegradeGlobalHealth(ratio: RoundFailureRatio, thresholdPercent: Int): Boolean =
        ratio.exceeds(thresholdPercent)

    /**
     * 该失败原因是否属于**真实传输故障**（网络断了/超时/服务端 5xx）。
     *
     * 只有这一组才允许宣称"网络不可用"：限流（429/`-352` 风控）与业务拒绝
     * （`code != 0`、401/403/404/412）意味着**服务端在正常工作，只是拒绝了这个请求**，
     * 把它们记成"网络故障"就是归因错误（缺陷 2），而且会把用户引向完全错误的排查方向
     * （查路由器、查流量，而真实原因是风控）。
     *
     * 与 [RoomFailureReason.retryable] 的区别（两者不同，别合并）：
     *  - `retryable` 回答"再问一次有没有意义"（解析失败也算 retryable）；
     *  - 本判据回答"这是不是传输链路的问题"（解析失败不算：链路是通的，只是内容不能用）。
     */
    fun isTransportFailure(reason: RoomFailureReason): Boolean = when (reason) {
        RoomFailureReason.TIMEOUT,
        RoomFailureReason.NETWORK,
        RoomFailureReason.SERVER_ERROR -> true
        RoomFailureReason.RATE_LIMITED,
        RoomFailureReason.API_REJECTED,
        RoomFailureReason.MISSING_IN_RESPONSE,
        RoomFailureReason.MALFORMED_RESPONSE -> false
    }

    /**
     * 大面积失败的**归因类别**：决定开哪种 SYSTEM 盲区、发什么文案。
     *
     * 修复前引擎只有一个动作（`onTransportFailure()` → `GapReason.NETWORK_UNAVAILABLE`），
     * 于是 98 位主播全被服务端以 `code=-352` 拒绝（或全 429）时也发「网络不可用，监控出现盲区」——
     * 同一个 `-352` 在**主播级**被标 `API_UNAVAILABLE`、在**系统级**被标 `NETWORK_UNAVAILABLE`，
     * 两个标签互相矛盾。现在归因与主播级用的是同一张映射表（[RoomFailureMapping]）。
     */
    enum class GlobalFailureKind(val gapReason: com.example.bilimonitor.data.local.GapReason) {
        /** 真实传输故障（超时 / 连接失败 / 5xx）：才允许说"网络不可用"。 */
        TRANSPORT(com.example.bilimonitor.data.local.GapReason.NETWORK_UNAVAILABLE),

        /** 接口侧问题（限流 / 业务拒绝 / 响应不可用）：如实说"接口被拒绝或被限流"。 */
        API(com.example.bilimonitor.data.local.GapReason.API_UNAVAILABLE),

        /** 拿到了失败主播、但一个原因信号都没有：如实说"原因未能确定"，不许假定网络故障。 */
        UNKNOWN(com.example.bilimonitor.data.local.GapReason.UNKNOWN)
    }

    /**
     * 一次大面积失败的**归因结论**（[globalFailureCause] 的返回值）。
     *
     * [dominantReason] 为 null 表示本轮没有任何可用的原因信号（见 [GlobalFailureKind.UNKNOWN]）。
     */
    data class GlobalFailureAttribution(
        val kind: GlobalFailureKind,
        val dominantReason: RoomFailureReason?
    ) {
        /** 写进 `monitoring_gap.reason` 的取值：按主导原因选，不再一律 `NETWORK_UNAVAILABLE`。 */
        val gapReason: com.example.bilimonitor.data.local.GapReason get() = kind.gapReason

        /**
         * 落进 `application_error_log` / 通知 payload 的错误码。
         *
         * 经 [RoomFailureMapping.appErrorOf] 这张**全项目唯一**的映射表得到 ——
         * 与每位失败主播自己那条记录用的是同一个函数，因此系统级与主播级不可能再互相矛盾。
         */
        val appError: AppError
            get() = dominantReason?.let { RoomFailureMapping.appErrorOf(it) } ?: AppError.UNKNOWN

        /** `problemKey` 的中段（形如 `MONITORING:<本段>:SYSTEM:<episodeId>`）。 */
        val problemSegment: String get() = gapReason.name

        /** 该事故的 `problemKey` 前缀（不同原因各成一个 problem episode，互不覆盖）。 */
        val problemBaseKey: String get() = problemBaseKeyOf(kind)

        /** 用户可读摘要（通知正文）。按**具体机制**给文案，不按粗粒度分组给文案。 */
        val summary: String
            get() = when (dominantReason) {
                null -> "监控出现盲区，失败原因未能确定"
                RoomFailureReason.TIMEOUT -> "请求超时，监控出现盲区"
                RoomFailureReason.NETWORK -> "网络不可用，监控出现盲区"
                RoomFailureReason.SERVER_ERROR -> "服务端错误（5xx），监控出现盲区"
                RoomFailureReason.RATE_LIMITED -> "接口被限流（429），监控出现盲区"
                RoomFailureReason.API_REJECTED -> "接口被拒绝，监控出现盲区"
                RoomFailureReason.MISSING_IN_RESPONSE -> "接口返回缺少数据，监控出现盲区"
                RoomFailureReason.MALFORMED_RESPONSE -> "接口返回无法解析，监控出现盲区"
            }
    }

    /**
     * 某类事故的 `problemKey` 前缀（`MONITORING:<类型>:SYSTEM`）。
     *
     * 公开出来是因为**恢复路径**也要用它（引擎要把每一类事故都收掉、并给出对应的恢复文案），
     * 让"开"与"收"共用同一个字符串来源 —— 手抄两份迟早会写岔，写岔的后果是一个
     * 永远不结束的问题 episode。
     */
    fun problemBaseKeyOf(kind: GlobalFailureKind): String = "MONITORING:${kind.gapReason.name}:SYSTEM"

    /**
     * 归因用的**主导原因**：仍失败的主播数最多的那个原因。
     *
     * 并列（计数相同）时按 [DOMINANCE_TIE_BREAK] 的固定顺序取第一个命中者，而不是靠
     * `Map` 的遍历顺序 —— 批次是**并发**完成的，`reasonCounts` 的插入顺序每次运行都可能不同，
     * 用遍历顺序做并列判定会让"同一现场"在两次 Tick 里得到不同的归因（不可复现的日志等于没有日志）。
     *
     * @return null = 没有任何可归属的原因信号（[reasonCounts] 为空或全为 0）。
     */
    fun dominantReasonOf(reasonCounts: Map<RoomFailureReason, Int>): RoomFailureReason? {
        val max = reasonCounts.values.maxOrNull() ?: return null
        if (max <= 0) return null
        return DOMINANCE_TIE_BREAK.firstOrNull { (reasonCounts[it] ?: 0) == max }
    }

    /**
     * 大面积失败的**归因判定**（缺陷 2 的核心）：占比越线**且**原因属于真实传输故障，
     * 才允许走"网络不可用"这条事故路径；纯接口拒绝/限流的大面积失败照常降级（盲区是真的），
     * 但事故类型与文案如实写成"接口被拒绝/被限流"。
     *
     * 三分支（每一支都**不静默**：盲区照开、通知照发，只是不再撒谎）：
     *  - [GlobalFailureKind.TRANSPORT]：`TIMEOUT` / `NETWORK` / `SERVER_ERROR`；
     *  - [GlobalFailureKind.API]：`RATE_LIMITED` / `API_REJECTED` / `MISSING_IN_RESPONSE` /
     *    `MALFORMED_RESPONSE`（服务端在正常工作，是它拒绝了这次调用或给不出可用数据）；
     *  - [GlobalFailureKind.UNKNOWN]：有失败主播但没有任何原因信号。
     *
     * @return null = 占比没越线（不降级，调用方应走"传输健康"路径）。
     */
    fun globalFailureCause(
        ratio: RoundFailureRatio,
        thresholdPercent: Int,
        reasonCounts: Map<RoomFailureReason, Int>
    ): GlobalFailureAttribution? {
        if (!ratio.exceeds(thresholdPercent)) return null
        val dominant = dominantReasonOf(reasonCounts)
        val kind = when {
            dominant == null -> GlobalFailureKind.UNKNOWN
            isTransportFailure(dominant) -> GlobalFailureKind.TRANSPORT
            else -> GlobalFailureKind.API
        }
        return GlobalFailureAttribution(kind = kind, dominantReason = dominant)
    }

    /**
     * 计数并列时的固定顺序：**非传输原因排在传输原因之前**。
     *
     * 这是**保守方向**：并列意味着两类原因一样多，"到底是网络还是接口"没有定论，
     * 此时绝不允许宣称"网络不可用"（那正是缺陷 2 要消灭的误报）；
     * 接口侧的原因按"越具体越靠前"排，传输侧同理。
     */
    private val DOMINANCE_TIE_BREAK = listOf(
        RoomFailureReason.RATE_LIMITED,
        RoomFailureReason.API_REJECTED,
        RoomFailureReason.MISSING_IN_RESPONSE,
        RoomFailureReason.MALFORMED_RESPONSE,
        RoomFailureReason.SERVER_ERROR,
        RoomFailureReason.TIMEOUT,
        RoomFailureReason.NETWORK
    )

    /**
     * 二次重试轮里，连续失败是否已达到 `circuitBreakerThreshold`：到了就提前结束本轮的剩余重试。
     *
     * 为什么要提前结束：熔断的语义是"服务端明显不可用，别再打了"，
     * 与"这一轮还剩几个批次要重试"无关；继续重试只会把退避时间叠加成几十分钟的 Tick。
     */
    // ★ 生产代码**已不再调用**本函数：重试轮的收手判据换成了 `breakerOpen(snapshot)`
    //   （= 熔断总开关 && 熔断状态，见 MonitoringEngine 的重试轮与 fetchEitherWithRetry）。
    //   保留它只是因为它仍锁定"连续失败达阈值"这一条边界语义、且有单测覆盖；
    //   **不要**再拿它当"现在是怎么收手的"的答案（那是"两个真相"）。
    fun shouldStopRetrying(consecutiveFailures: Int, breakerThreshold: Int): Boolean =
        consecutiveFailures >= breakerThreshold.coerceAtLeast(1)

    /**
     * 逻辑拒绝（HTTP 2xx + 业务 code != 0、或 403/404/412 这类明确拒绝）时，
     * 是否值得把这批**拆小**重问一次。
     *
     * 目的（用户痛点的一半）：批次是按 uid 拼的，一个坏房间会把**同批的其他正常主播**
     * 一起拖成失败。拆小之后坏房间会被隔离到越来越小的子批，
     * 同批的正常主播即可拿到真实状态。
     *
     * 只在**明确的逻辑拒绝**上做：网络错误/超时/5xx 是"整条链路都不通"，
     * 拆小只会成倍增加请求量而不会改善任何结果。
     */
    fun shouldSplitBatch(failure: CallFailure, batchSize: Int): Boolean =
        failure.reason == RoomFailureReason.API_REJECTED &&
            (failure.httpStatus == null || failure.httpStatus < 500) &&
            batchSize >= MIN_SPLIT_BATCH_SIZE

    /** 拆分深度上限：2^3 = 8 段，足以把 1 个坏房间从一批 100 里隔离出来（还要受请求预算限制）。 */
    const val MAX_SPLIT_DEPTH = 3

    /** 每轮 Tick 允许的拆分请求总量上限 —— 拆分是"额外请求"，必须有硬预算，防止请求放大。 */
    const val MAX_SPLIT_REQUESTS_PER_TICK = 8

    /** 小于该规模的批次不再拆（拆完只剩 1 个主播时，拆与不拆是同一件事）。 */
    const val MIN_SPLIT_BATCH_SIZE = 4
}

/**
 * 失败原因 → 观察结果 / 错误码的**唯一映射表**。
 *
 * 为什么要有这张表：原来每个失败点各写各的（批次失败一律 `NETWORK_ERROR`、
 * uid 缺失一律 `API_ERROR`），口径散落在引擎各处，改一处就会与另一处矛盾。
 * 集中在这里之后，"某个原始信号最终被记成什么"只需要看一张表。
 */
object RoomFailureMapping {

    fun observationResultOf(reason: RoomFailureReason): ObservationResult = when (reason) {
        RoomFailureReason.TIMEOUT -> ObservationResult.TIMEOUT
        RoomFailureReason.NETWORK,
        RoomFailureReason.SERVER_ERROR -> ObservationResult.NETWORK_ERROR
        // ★ 限流（HTTP 429 / 带 Retry-After 的 4xx）从 NETWORK_ERROR 改到 API_ERROR（缺陷 2）：
        //   服务端在正常工作、只是拒绝了这次调用，归到"网络"会让主播级盲区被记成
        //   `GapReason.NETWORK_UNAVAILABLE`（MonitorRepository.gapReasonOf），与"接口被限流"的事实矛盾。
        RoomFailureReason.RATE_LIMITED,
        RoomFailureReason.API_REJECTED -> ObservationResult.API_ERROR
        RoomFailureReason.MISSING_IN_RESPONSE,
        RoomFailureReason.MALFORMED_RESPONSE -> ObservationResult.INVALID
    }

    /**
     * 落进 `monitoring_error_log.errorCode` 的应用错误码。
     *
     * 每个机制都有自己的错误码：限流 → [AppError.RATE_LIMITED]（不再借用"网络不可用"），
     * 原始信号（HTTP 429 / `Retry-After` / 业务 code）照旧完整写进 `detail`。
     *
     * ★ 关于"新增 `AppError` 取值要不要迁移"（本轮已核实，结论写在这里免得再被误传）：
     *   **不需要**。`monitoring_error_log` / `application_error_log` 的 `errorCode` 在
     *   `app/schemas/.../9.json` 里就是 `errorCode TEXT NOT NULL`（**没有 CHECK 约束**），
     *   全库 schema 里也搜不到任何 `CHECK(`；枚举经 `DshTypeConverters` 以 `Enum.name` 存 TEXT，
     *   Room 不为枚举生成 CHECK。因此新增取值不动 DDL、不动 `AppDatabase.DB_VERSION`、
     *   不需要迁移脚本。唯一要注意的是读路径：`EnumSafe.parse` 对不认识的字符串回退
     *   `AppError.UNKNOWN`（老版本读到新取值时的降级行为），不会崩。
     */
    fun appErrorOf(reason: RoomFailureReason): AppError = when (reason) {
        RoomFailureReason.TIMEOUT -> AppError.NETWORK_TIMEOUT
        RoomFailureReason.NETWORK,
        RoomFailureReason.SERVER_ERROR -> AppError.NETWORK_UNAVAILABLE
        RoomFailureReason.RATE_LIMITED -> AppError.RATE_LIMITED
        RoomFailureReason.API_REJECTED -> AppError.API_REJECTED
        RoomFailureReason.MISSING_IN_RESPONSE,
        RoomFailureReason.MALFORMED_RESPONSE -> AppError.API_INVALID_RESPONSE
    }
}
