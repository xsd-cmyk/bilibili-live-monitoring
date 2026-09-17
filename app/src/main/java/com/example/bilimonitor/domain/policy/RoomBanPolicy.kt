package com.example.bilimonitor.domain.policy

/**
 * 一次封禁探针的结论（由数据源从 `room_init` 的响应构造）。
 *
 * 这里**只放信号**，判定规则全部在 [RoomBanPolicy] 里 —— 与 RetryPolicy / StateConfirmationPolicy
 * 同样的取舍：纯函数才好单测，而封禁这条链路恰恰是"没法真机复现、只能靠单测自证"的部分。
 *
 * @param isLocked `room_init.data.is_locked`：唯一可辨的封禁签名（实测）。
 * @param lockTill `lock_till` **归一成毫秒之后**的封禁到期时刻：-1 = 无期限封禁；
 *        > 0 = 限时封禁到期时刻（毫秒）；0/null = 未给 = 期限未知。
 *
 *        ★ 单位是毫秒，**不是**服务端原始取值：`room_init.lock_till` 是 unix **秒**，
 *        必须过一遍 [RoomBanPolicy.normalizeLockTillMillis] 再放进来。原实现直接把秒值透传到这里，
 *        于是 `1767225600`（2026-01-01）跟毫秒级的 `nowWall` 一比就"早于现在"，
 *        限时封禁被展示成「已过标注期限（1970-01-19 …）仍未解封」，还写进了
 *        `AddStreamerResult.AddedBanned.message` 与审计留痕（2026 修复）。
 *        归一之后 [RoomBanPolicy.lockTillLabel] 的毫秒比较才是自洽的，那里也有针对
 *        "疑似原始秒值"的防御性文案（见 `ROOM_INIT_LOCK_TILL_SECONDS_FLOOR`）。
 * @param isHidden `is_hidden`：**与封禁是两回事**（实测两者取值在封禁/正常房间上一致），
 *                 只做记录与诊断，不参与判定，也不产生第三种状态。
 * @param roomId `room_init.data.room_id`：探针响应里的**真实房间号**（`room_init` 的 `id`
 *               参数同时接受房间号与 uid，因此调用方未必知道房间号）。
 *               为什么要带回来：允许添加已封禁的直播间之后，主播行里的 roomId 就成了
 *               复查的唯一依据 —— 引擎只按 `streamer.roomId ?: shortRoomId` 发封禁探针
 *               （见 MonitoringEngine.refreshDueBanProbes），roomId 为 null 就永远复查不了，
 *               该主播会被**永久跳过**常规检查、封禁也永不解除。
 */
data class RoomBanProbeResult(
    val isLocked: Boolean,
    val lockTill: Long? = null,
    val isHidden: Boolean? = null,
    val apiCode: Int? = null,
    val apiMessage: String? = null,
    val roomId: Long? = null
)

/**
 * 一次封禁探针的**完整结论**：要么拿到了判定依据，要么**说清楚为什么没拿到**。
 *
 * ## 为什么不能让"没拿到"退化成一个 `null`（本次日志缺陷的根因之一）
 * 原实现 `probeBan` 返回 `RoomBanProbeResult?`，一个 `null` 同时代表
 * 「超时 / 连接失败 / HTTP 5xx / 限流风控（实测 -352）/ 解析失败 / 字段缺失」六种
 * **完全不同**的机制。后果有两层：
 *  ① 调用方只能写一句"未取得结论"，事后分不清"是网络不通还是被服务端拒了"；
 *  ② 更糟的是它被顺手记成 `AppError.API_REJECTED`（"接口被拒绝"）——
 *     用户按"谁拒绝了我"去查，方向从第一步就是错的，而真实原因可能只是一次超时。
 *
 * 因此把"未知"升级为**带失败分类的显式结果**：错误码一律经
 * [RoomFailureMapping.appErrorOf] 那张全项目唯一的映射表得到，原始信号随 [CallFailure] 一起带回。
 *
 * ★ 语义底线（与 [RoomBanPolicy.nextState] 的规则①一致）：[Failed] **不等于"未封禁"**，
 *   调用方不得据此解除封禁 —— 它只说明"这一次没问到"。引擎把 [Failed] 一律折算成
 *   `probe = null` 传给 [RoomBanPolicy.nextState]，因此**判定与调度逻辑逐字未变**。
 */
sealed interface BanProbeOutcome {

    /** 拿到了 `is_locked`：可以据此判定（true = 封禁，false = 未封禁）。 */
    data class Concluded(val result: RoomBanProbeResult) : BanProbeOutcome

    /**
     * 没拿到结论，并且知道**为什么**：`failure.reason` 是机制层分类
     * （超时 / 网络 / 限流 / 5xx / 服务端明确拒绝 / 响应不可用 / 解析失败），
     * 原始信号（HTTP 状态码、B 站业务 code、message、Retry-After）随 [CallFailure] 一并带回。
     */
    data class Failed(val failure: CallFailure) : BanProbeOutcome
}

/**
 * 「封禁中」状态的**判定规则**（独立于 [com.example.bilimonitor.data.local.ConfirmedLiveStatus]）。
 *
 * ## 为什么这个状态可以存在（与"网络异常"等情形的区分依据）
 *
 * 判据只有一条：`room_init.data.is_locked`。它是**服务端语义**、且是房间级字段，
 * 与下面这些失败模式在**信号层面**就不同源 —— 因此不存在"把网络异常误判成封禁"的路径：
 *
 * | 情形 | 在本实现里长什么样 | 会不会被当成封禁 |
 * |---|---|---|
 * | 网络超时 / 连接失败 | `probeBan` → [BanProbeOutcome.Failed]（TIMEOUT / NETWORK）→ 引擎按**未知**处理 | 不会（未知 ≠ 未封禁） |
 * | HTTP 5xx | 非 2xx → `HttpException` → [BanProbeOutcome.Failed]（SERVER_ERROR） | 不会 |
 * | 限流 / 风控（HTTP 429、实测 `code:-352`） | `code != 0` → [BanProbeOutcome.Failed]（API_REJECTED + 原始 code） | 不会 |
 * | 响应解析失败 / 接口改版 | 异常或 `data == null` → [BanProbeOutcome.Failed]（MALFORMED / MISSING） | 不会 |
 * | 鉴权失效 | `room_init` 是**公开接口、免登录**，本就不受登录态影响；若服务端仍返回非 0 code → [BanProbeOutcome.Failed] | 不会 |
 * | 房间号写错 / 房间不存在 / 主播注销 | `room_init` 返回非 0 code（实测 `getRoomInfoOld` 已废弃返回 -400 同型）→ [BanProbeOutcome.Failed] | 不会（不合并语义） |
 * | 隐藏房间 `is_hidden = true` | 字段单独记录，**不**参与判定 | 不会 |
 * | **真的被封禁** | HTTP 200 + `code = 0` + **`is_locked = true`** | **是** |
 *
 * 结论：唯一能产生"封禁"判定的输入是"服务端在**正常响应**里明确说 `is_locked = true`"。
 * 任何"没问到/问不到/被拒绝"都只会得到"未知"，而未知**不会**清除已有的封禁标记
 * （见 [nextState]），所以既不会误判、也不会误解除。
 *
 * ## 独立性（用户硬要求）
 *  - 封禁**不改写** `confirmedLiveStatus`：判定与推进走的是封禁专用的写入路径，
 *    不产生任何 `StatusHistory` / `LiveEvent` / `LiveSession` 变更；
 *  - 封禁**不产生**开播/下播通知；
 *  - 封禁**不参与** `StateConfirmationPolicy`（观察结果仍是原来的 SUCCESS/失败语义）。
 *
 * 它只影响两件事：**调度**（多久复查一次）与**展示**（健康中心「封禁中 N 位」）。
 */
object RoomBanPolicy {

    /**
     * 由探针结论算出新的封禁状态。
     *
     * @param previous 上一次的封禁状态（未封禁时为 null）
     * @param probe 本次探针结论；**null = 本次没有拿到可用结论**（超时/风控/5xx/解析失败/字段缺失）。
     *        引擎对 [BanProbeOutcome.Failed] 一律折算成这里的 `null`，
     *        因此"失败分类"只影响**日志记录**，不影响本函数的判定与调度。
     * @param nowWall 当前墙上时间
     * @param recheckIntervalSeconds 封禁中的复查间隔（用户可配，默认 1800）
     * @return 新的封禁状态；null 表示"未封禁"
     *
     * 三条规则：
     *  ① 探针为 null → **原样保持** previous（不动 `nextCheckAt`，避免把复查时刻推迟到下一次失败之后）。
     *     ★ 这条是"不得把风控/超时当成没被封禁"的落地处。
     *  ② `isLocked = false` → **立即解除**（返回 null），并恢复到正常检查间隔。
     *  ③ `isLocked = true` → 进入/续期封禁，复查时刻 = now + 间隔。
     */
    fun nextState(
        previous: RoomBanState?,
        probe: RoomBanProbeResult?,
        nowWall: Long,
        recheckIntervalSeconds: Int
    ): RoomBanState? {
        if (probe == null) return previous
        if (!probe.isLocked) return null
        return RoomBanState(
            lockedAtWall = previous?.lockedAtWall ?: nowWall,
            lastProbeAtWall = nowWall,
            nextCheckAtWall = nowWall + intervalMillis(recheckIntervalSeconds),
            lockTill = probe.lockTill,
            isHidden = probe.isHidden,
            apiCode = probe.apiCode,
            apiMessage = probe.apiMessage
        )
    }

    /** 封禁中的复查间隔（毫秒）；下限 1 分钟兜底，防止坏配置把复查拖成"永不检查"。 */
    fun intervalMillis(recheckIntervalSeconds: Int): Long =
        recheckIntervalSeconds.coerceIn(MIN_RECHECK_SECONDS, MAX_RECHECK_SECONDS) * 1000L

    /** 用户可配范围（与 ConfigRepository.merge 的校验、设置页 validRange 三处必须同步）。 */
    val RECHECK_RANGE = 60..86_400

    /** 默认复查间隔：30 分钟（用户诉求）。 */
    const val DEFAULT_RECHECK_SECONDS = 1800

    const val MIN_RECHECK_SECONDS = 60
    const val MAX_RECHECK_SECONDS = 86_400

    /**
     * 本轮该不该对这位主播发封禁探针。
     *
     * ## 策略与取舍（为什么不是"每轮都探"）
     * `room_init` 是**单个房间**的接口，而批量状态接口不返回 `is_locked`。
     * 若每轮对所有主播都探一次，98 位主播 = 每轮多 98 个请求（放大近百倍），
     * 与"降低服务器压力、耗电与账号风控风险"的项目原则直接冲突。
     * 因此只在**已经有理由怀疑**时才探：
     *  - **首次判定**：[suspicionRaised] —— 这一轮这位主播的检查失败了（含被服务端明确拒绝）。
     *    正常情况下零额外请求；只有失败者才各多花 1 个请求，且有每轮预算上限。
     *  - **复查**：已经在封禁中的主播，到期（[RoomBanState.nextCheckAtWall]）时探一次，
     *    期间**完全跳过**（不再进入批量检查），因此被封禁的主播不会拖慢其他主播。
     *
     * 已知的保守缺口（如实记录，不掩盖）：如果某个被封禁的房间在批量状态接口里
     * **既不失败也不报错**（表现为普通未开播），那么它不会被探到，也就不会被标成封禁。
     * 这属于"宁可漏判也不误判"的取舍 —— 用户明确要求不得把其它状态误判成封禁。
     */
    fun shouldProbe(ban: RoomBanState?, suspicionRaised: Boolean, nowWall: Long): Boolean {
        if (ban == null) return suspicionRaised
        return nowWall >= ban.nextCheckAtWall
    }

    /**
     * 「封禁中」的**展示/调度**唯一入口：该不该在本轮跳过这位主播的常规检查。
     *
     * 语义（也是单测里"30 分钟内不再检查、到期恢复检查"的依据）：
     *  - 未封禁 → 永不跳过；
     *  - 封禁中且未到复查时刻 → 跳过；
     *  - 封禁中且已到复查时刻 → 不跳过（走封禁专用复查路径）。
     *
     * 边界：`nextCheckAtWall` 恰等于 now 时**恢复检查**（`>=` 而非 `>`）——
     * 时间边界上宁可早一秒检查，也不要因为差一毫秒把主播多冻一轮。
     */
    fun shouldSkipRegularCheck(ban: RoomBanState?, nowWall: Long): Boolean {
        if (ban == null) return false
        return nowWall < ban.nextCheckAtWall
    }

    /**
     * 封禁期限的人类可读说明（健康中心/诊断包展示用）。
     * `-1` = 无期限封禁；`> 0` = 限时封禁到期时刻（**毫秒**）；其余 = 未给出期限。
     *
     * ★ 入参必须已经过 [normalizeLockTillMillis]，本函数**不做单位换算**
     *   （这是"只有一个归一入口"的落地处）—— 但会**识别**那种"忘了归一"的原始秒值
     *   并拒绝为它编一个日期，理由见下。
     */
    fun lockTillLabel(lockTill: Long?, nowWall: Long): String = when {
        lockTill == null || lockTill == 0L -> "期限未知"
        lockTill < 0L -> "无期限封禁"
        // 防御：小于 SECONDS_MILLIS_FLOOR 的正数**不可能是**合法的毫秒时刻
        // （那意味着 1973 年之前），只可能是没归一过的原始秒值（或垃圾值）。
        // 为什么必须挡住：放它过去会走下面的 else，于是 `1767225600` 被按毫秒格式化成
        // "1970-01-19"，输出「已过标注期限（1970-01-19 21:33）仍未解封」——
        // 把"我少乘了 1000"说成"服务端给的期限已经过了"，排查方向从第一步就是错的。
        // 这里只给"无法识别"，既不说封禁到什么时候，也绝不说它已经过期。
        lockTill < SECONDS_MILLIS_FLOOR -> "期限未知（时间戳单位无法识别）"
        lockTill > nowWall -> "封禁至 ${formatWall(lockTill)}"
        // 到期时间已过但服务端仍报 is_locked：如实说明"已过标注期限仍未解封"，
        // 不擅自当成未封禁（服务端才是权威）。
        else -> "已过标注期限（${formatWall(lockTill)}）仍未解封"
    }

    private fun formatWall(millis: Long): String = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(millis))
    }.getOrElse { millis.toString() }

    /**
     * 「已添加，但该直播间当前处于封禁中」的**成功**提示文案（产品决策：允许添加已封禁的直播间）。
     *
     * 为什么必须说清楚三件事：
     *  - **已添加**：它是成功提示，不是错误提示（原先的实现直接拒绝添加）；
     *  - **哪种封禁**：无期限 / 封禁至某时刻 / 期限未知，直接复用 [lockTillLabel] ——
     *    与落库、调度用的是同一份 `lock_till` 解析，界面不会和引擎各说各话；
     *  - **不会开播、也不会误报**：封禁主播被常规检查跳过（[shouldSkipRegularCheck]），
     *    封禁本身也不参与状态机，所以既不会有开播，也不会产生开播/下播通知。
     *
     * 为什么文案放在策略层而不是 UI 层：这里能被 JVM 单测逐字锁定（本项目 UI 层没有测试基建），
     * 而"三种期限表达是否都说得对"恰恰是这条产品行为最容易写错的地方。
     *
     * @param recheckIntervalSeconds 引擎实际使用的封禁复查间隔（`banRecheckIntervalSeconds`），
     *        与 [nextState] 的入参同源，避免提示里的"较低频率"与实际调度不一致。
     */
    fun addSuccessMessage(
        name: String,
        lockTill: Long?,
        recheckIntervalSeconds: Int,
        nowWall: Long
    ): String {
        // 向上取整：宁可把间隔说得比实际略长，也不要让用户以为马上就会再查一次。
        val seconds = recheckIntervalSeconds.coerceIn(MIN_RECHECK_SECONDS, MAX_RECHECK_SECONDS)
        val minutes = (seconds + 59) / 60
        return "已添加：${name}（该直播间当前处于封禁中：${lockTillLabel(lockTill, nowWall)}）。" +
            "它现在不会开播，监控也不会误报开播/下播；" +
            "解封前按较低频率（约 ${minutes} 分钟一次）自动复查，解封后自动恢复正常监控。"
    }

    /**
     * 解析 `lock_till` —— **两种编码都支持，但只在"确实表示一个封禁期限"时才返回值**。
     *
     * 为什么要防着两种编码：同一个字段名在两个接口上的语义不同（实测 + 官方文档）：
     *  - `room_init.lock_till`：**unix 秒**，`-1` = 无期限，`0` = 未封禁；
     *  - `get_status_info_by_uids.lock_till`：**日期时间字符串**，未封禁时是 `"0000-00-00 00:00:00"`。
     *
     * 保守规则（宁可返回 null = 期限未知，也不猜）：
     *  - 空白 / `"0000-00-00 00:00:00"` / `"0"` / 纯 0 → null（未封禁的信息，不构成封禁证据）；
     *  - `"-1"` → -1（无期限封禁）；
     *  - 纯数字 > 0 → 秒级时间戳（`room_init` 形态）；
     *  - 可解析的 `yyyy-MM-dd HH:mm:ss` → 该时刻的毫秒（批量接口形态）；
     *  - 其它任何东西 → null。
     *
     * ★ 本函数**不判断"是否被封禁"**，只翻译"封禁到什么时候"；
     *   是否封禁只由 `is_locked`（探针）决定。
     */
    fun parseLockTill(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.startsWith("0000-00-00")) return null
        // 纯数字：`room_init` 形态 = **unix 秒**。统一交给 normalizeLockTillMillis 换算成毫秒，
        // 调用方不必区分单位（判据见 normalizeEpochMillis：小于 1e11 的值只可能是秒，
        // 1.7e9 秒 = 2023 年，而 1.7e9 毫秒 = 1970 年，两种可能的量级差 1000 倍，不会混淆）。
        text.toLongOrNull()?.let { raw -> return normalizeLockTillMillis(raw) }
        return runCatching {
            val pattern = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            pattern.isLenient = false
            pattern.parse(text)?.time
        }.getOrNull()
    }

    /**
     * `room_init.lock_till` 的**数值**归一 —— 探针路径上唯一的单位换算入口。
     *
     * 为什么探针路径也必须走这里（缺陷 2）：`room_init.lock_till` 是 unix **秒**，
     * 而 [RoomBanProbeResult.lockTill] / [RoomBanState.lockTill] / [lockTillLabel] 全线按
     * **毫秒**比较与格式化。原实现在 `BiliLiveApi.probeBan` 里写 `lockTill = data.lockTill`
     * 直接透传秒值，于是服务端返回 `1767225600`（2026-01-01）时 `lockTill > nowWall`
     * 恒为 false ⇒ 限时封禁被展示成「已过标注期限（1970-01-19 …）仍未解封」，
     * 并被写进 `AddStreamerResult.AddedBanned.message` 与审计留痕。
     *
     * 为什么不做成"在 DTO 上标单位 + 各处自行换算"：那会让秒/毫秒的知识散落到多个调用点
     * （探针、批量字符串、DTO 注解），而这条链路的正确性恰恰只能靠单测自证 ——
     * 归一入口只能有一个，就是这里（[parseLockTill] 也把数值分支交给它）。
     *
     * 规则（每条都有单测）：
     *  - `null` / `0` → `null` = **未知**（服务端"没给期限"与"没封禁"都不构成封禁证据）；
     *  - `< 0` → 原样保留（`-1` = 无期限封禁，这是服务端的哨兵值）；
     *  - `> 0` → 交给 [normalizeEpochMillis]（`< SECONDS_MILLIS_FLOOR` 视为秒并 ×1000）。
     *
     * ★ 绝不把"值不可用"折算成 0 或某个默认时刻：`0` 在这里是"未知"而不是"1970 年到期"，
     *   否则一次字段缺失就会被展示成"期限已过"，那是比单位错误更糟的谎。
     */
    fun normalizeLockTillMillis(value: Long?): Long? = when {
        value == null || value == 0L -> null
        else -> normalizeEpochMillis(value)
    }

    /**
     * 把"可能是秒、也可能是毫秒"的时间戳归一成毫秒（负数原样保留，语义由调用方定）。
     *
     * 阈值 [SECONDS_MILLIS_FLOOR]（1e11）：任何 1973 年之后的**秒**级时间戳都小于它，
     * 任何 2001 年之后的**毫秒**级都大于它 —— 两种可能相差 1000 倍，不会混淆。
     */
    fun normalizeEpochMillis(value: Long): Long = when {
        value < 0L -> value
        value < SECONDS_MILLIS_FLOOR -> value * 1000L
        else -> value
    }

    /**
     * 秒/毫秒的分界（1e11 毫秒 = 1973-03-03）：**唯一**的单位判据。
     *
     * 三处都引用它，避免各写一个字面量而慢慢分叉：
     *  - [normalizeEpochMillis] —— 单位换算；
     *  - [lockTillLabel] —— 识别"忘了归一的原始秒值"，不给它编造 1970 年的日期；
     *  - 单测 —— 断言边界两侧的行为。
     */
    const val SECONDS_MILLIS_FLOOR = 100_000_000_000L

    /**
     * 批量状态接口自带的封禁信息 → 封禁判定（`null` = 未知，**不是**"未封禁"）。
     *
     * 采信规则（与 [nextState] 同一条底线：只有明确的封禁证据才判封禁）：
     *  - `is_locked == true` → 封禁；
     *  - `is_locked == false` → 明确未封禁（返回 false，可用于解除）；
     *  - `is_locked` 缺失时，只有 `lock_till` 解析出 **-1（无期限）或未来时刻** 才判封禁 ——
     *    这两个取值在正常房间上不会出现（未封禁时该字段是 `"0000-00-00 00:00:00"`），
     *    因此不存在与"网络异常/风控/5xx/解析失败"混淆的可能：那些情形根本走不到这里
     *    （它们要么没有响应体，要么整批 code != 0）。
     */
    fun lockFromBatchFlags(isLocked: Boolean?, lockTillMillis: Long?, nowWall: Long): Boolean? = when {
        isLocked != null -> isLocked
        lockTillMillis == null -> null
        lockTillMillis < 0L -> true
        lockTillMillis > nowWall -> true
        else -> null
    }
}

/**
 * 「封禁中」状态的持久化表示。
 *
 * ## 为什么不加数据库列（schema 取舍）
 * 用户允许加列（并要求配套迁移），但 `streamer` 是本项目**最大也最敏感**的表
 * （7 个既有迁移中有 3 个动过它，且它是 CAS 写入的中心）。这里选择用既有的
 * `streamer.lastError`（TEXT、可空、**无 CHECK 约束**、已被诊断包导出、已被备份整行导出）
 * 承载一段紧凑 JSON：零 schema 改动、零迁移风险，且**备份/恢复自动带上**
 * （备份导出的是 streamer 整行）。
 *
 * 代价（如实说明）：`lastError` 同时承担"通用错误说明"的角色，
 * 因此约定 **`ROOM_BANNED ` 前缀 = 本状态标记**，非此前缀的值一律当作普通错误文本，
 * 不会被解析成封禁（[RoomBanCodec.decode] 对任何解析失败都返回 null —— 解析不出来就是"没封禁"，
 * 绝不会因为一段坏文本把主播标成封禁）。
 */
data class RoomBanState(
    /** 首次判定为封禁的时刻。 */
    val lockedAtWall: Long,
    /** 最近一次探针时刻。 */
    val lastProbeAtWall: Long,
    /** 下一次允许复查的时刻（调度依据）。 */
    val nextCheckAtWall: Long,
    val lockTill: Long?,
    val isHidden: Boolean?,
    val apiCode: Int?,
    val apiMessage: String?
)

/** [RoomBanState] ↔ `streamer.lastError` 文本的编解码。 */
object RoomBanCodec {

    /** 前缀是**唯一**的封禁标记；改动它会静默丢弃历史标记（所以写成常量并加测试）。 */
    const val PREFIX = "ROOM_BANNED "

    /** 文本长度上限：`lastError` 不是无限字段，且诊断包会整行导出。 */
    const val MAX_CHARS = 480

    fun encode(state: RoomBanState): String {
        val message = state.apiMessage?.replace('"', '\'').orEmpty()
        val hidden = state.isHidden?.toString() ?: "null"
        val lockTill = state.lockTill?.toString() ?: "null"
        val apiCode = state.apiCode?.toString() ?: "null"
        val json = "{\"lockedAt\":${state.lockedAtWall},\"lastProbeAt\":${state.lastProbeAtWall}," +
            "\"nextCheckAt\":${state.nextCheckAtWall},\"lockTill\":$lockTill,\"isHidden\":$hidden," +
            "\"apiCode\":$apiCode,\"message\":\"$message\"}"
        return (PREFIX + json).take(MAX_CHARS)
    }

    /**
     * 解码。**任何异常/不匹配都返回 null（= 未封禁）** —— 解析不出来绝不当成封禁。
     */
    fun decode(raw: String?): RoomBanState? {
        val text = raw?.takeIf { it.startsWith(PREFIX) } ?: return null
        return runCatching {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(text.removePrefix(PREFIX))
                .let { it as kotlinx.serialization.json.JsonObject }
            fun long(key: String): Long? = (json[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.content != "null" }?.content?.toLongOrNull()
            val nextCheckAt = long("nextCheckAt") ?: return null
            val lockedAt = long("lockedAt") ?: return null
            val lastProbeAt = long("lastProbeAt") ?: lockedAt
            val isHidden = (json["isHidden"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.content != "null" }?.content?.toBooleanStrictOrNull()
            val apiCode = long("apiCode")?.toInt()
            val message = (json["message"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.content != "null" }?.content?.takeIf { it.isNotBlank() }
            RoomBanState(
                lockedAtWall = lockedAt,
                lastProbeAtWall = lastProbeAt,
                nextCheckAtWall = nextCheckAt,
                lockTill = long("lockTill"),
                isHidden = isHidden,
                apiCode = apiCode,
                apiMessage = message
            )
        }.getOrNull()
    }

    fun isBanned(raw: String?): Boolean = decode(raw) != null

    /**
     * 封禁判定的**原始信号摘要**（用户要求：至少带上 `is_locked` / `lock_till` /
     * API `code` / `message`，以便日后自证判定是否正确）。
     *
     * 现在写进 `audit_log.detailJson`（状态变化的留痕），而不是错误日志 ——
     * "进入封禁"是服务端明确给出的业务状态，不是错误（理由见 MonitoringEngine.recordBanTransition）。
     */
    fun evidence(state: RoomBanState, nowWall: Long): String =
        "probe=room_init is_locked=true lock_till=${state.lockTill ?: "null"}" +
            "(${RoomBanPolicy.lockTillLabel(state.lockTill, nowWall)})" +
            " is_hidden=${state.isHidden ?: "null"} api_code=${state.apiCode ?: "null"}" +
            " message=${state.apiMessage ?: "null"}"
}

/**
 * 封禁状态的**变化**（记录策略的输入）：只有"变了"才值得写一条留痕。
 *
 * 为什么把这个判据抽成纯枚举 + 纯函数：记录频率的正确性没法在真机上"看一眼"验证，
 * 只能靠 JVM 单测证明 —— "连续 10 轮状态不变 ⇒ 0 条留痕"是本次修复的核心验收点，
 * 而它必须由一个不含数据库、不含 Android 依赖的函数来保证。
 */
enum class BanTransition { NONE, ENTERED, LIFTED }

/**
 * 封禁链路的**记录策略**（纯函数，可单测）。
 *
 * 三条规则（与用户要求逐条对应）：
 *  ① 进入封禁（首次判定为封禁）→ [BanTransition.ENTERED]，值得留痕；
 *  ② 解除封禁（复查得到 `is_locked = false`）→ [BanTransition.LIFTED]，值得留痕；
 *  ③ 其余（未封禁 / 仍在封禁中的一次续期复查 / 每轮的跳过）→ [BanTransition.NONE]，
 *     **不写任何库**（跳过与续期只进 logcat）。
 *
 * 为什么"续期复查"不算变化：它不是新事实，只是同一事实的再次确认（默认每 30 分钟一次）。
 * 而"每轮都记一条"正是把错误日志刷满、让真正有用的错误找不到的那类记录。
 */
object BanLogPolicy {

    /**
     * 由"变化前 / 变化后"的封禁状态判定这是哪一种变化。
     * 两侧都为 null（始终未封禁）或都非 null（始终封禁中）时返回 [BanTransition.NONE]。
     */
    fun transitionOf(previous: RoomBanState?, next: RoomBanState?): BanTransition = when {
        previous == null && next != null -> BanTransition.ENTERED
        previous != null && next == null -> BanTransition.LIFTED
        else -> BanTransition.NONE
    }
}

/**
 * 封禁链路日志的**边沿抑制器**：同一个 key 的同一个信号连续出现时，只在第一次放行。
 *
 * ## 为什么必须有它（否则"刷屏"会换个马甲回来）
 * 探针失败时 [RoomBanPolicy.nextState] 按规则①**原样返回** previous —— 复查时刻不被推迟
 * （这是正确的：未知不得改写调度）。代价是这一位主播**下一轮还会被复查一次**，
 * 只要失败持续（例如一段时间的风控），每轮都会再失败一次。
 * 若每次失败都落库，98 位主播的机器上错误日志又会变成"每分钟一条" —— 与本次要修的缺陷同型。
 *
 * 三处调用点都是"同一件事会持续重复发生"的地方：
 *  ① 探针失败（超时 / 风控 / 5xx / 解析失败…）；
 *  ② 探针预算用尽（每个失败主播每轮都会撞到同一个上限）；
 *  ③ 封禁判定链路的未预期异常（若是持续性的，例如写库一直失败，每轮都会再抛一次）。
 *
 * ## 为什么用内存而不是查库/加列
 *  - 加一列 = 迁移 + 老库兼容，为了一个"去重指纹"不值当；
 *  - 每次失败前先查一次库 = 在最该省资源的失败路径上再加一次 IO。
 *  代价（如实说明）：**进程重启后会重新记一条**。频率上界因此是
 *  "每个进程 × 每个 key × 每种信号一条"，而不是"每轮一条" ——
 *  进程重启后在新日志里重新声明一次"这个故障还在"本身也是合理的。
 *
 * ## 无界增长？
 * key 是主播 stableId，数量上界 = 本进程内出现过这些信号的主播数（用户那台机器是 98）。
 * 探针拿到结论或解除封禁时调用 [clear]，把已恢复的主播及时移出。
 */
class BanLogEdgeGate {

    /** key（主播 stableId）→ 上次**已记录**的信号指纹。 */
    private val lastRecorded = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * @return true = 与上次记录的信号不同（该记一条）；false = 同一信号（抑制，不落库）。
     */
    fun shouldRecord(key: String, signature: String): Boolean =
        lastRecorded.put(key, signature) != signature

    /** 该主播恢复正常（探针拿到结论 / 解除封禁）时调用：下次再出问题仍然值得记一条。 */
    fun clear(key: String) {
        lastRecorded.remove(key)
    }

    /** 当前跟踪的 key 数（供测试与诊断；只增不减是 bug，靠 [clear] 收敛）。 */
    fun tracked(): Int = lastRecorded.size
}
