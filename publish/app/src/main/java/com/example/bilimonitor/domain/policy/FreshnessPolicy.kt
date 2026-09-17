package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.MonitoringMode

/**
 * 数据新鲜度（首页卡片上的「数据过期」）的**唯一判定处** —— 纯函数，无 Android 依赖，可 JVM 单测。
 *
 * ## 为什么必须有这个文件（本次修复的根因）
 *
 * 修复前，「数据过期」读的是 `streamer.freshnessStatus` 这个**存储列**，而那一列只在
 * **"某位主播的一次观察被应用"**时才会被写（唯一写点 `StreamerDao.casConfirmedObservation`）。
 * 于是它记录的是「写入那一刻的结论」，却被界面当成了「此刻是否过期」：
 *
 *  - 成功观察一定把 `lastConfirmedAt` 刷成 `now`（差值为 0）⇒ 那一刻必然写 FRESH；
 *  - 引擎**停跑**（或监控被关、租约丢失、Tick 超预算早退）⇒ 一次写入都没有 ⇒
 *    列永远冻结在最后一次写入的 FRESH 上 ⇒ 卡片上的「N 分钟前」一直涨，「数据过期」却永不出现。
 *
 * 修法：判定改成**现算**（界面每次重组/每 30s 用当前时间重算一次），并把判定式抽到这里，
 * 让界面与引擎**共用同一份规则**（引擎侧用它补写那列派生缓存，见 `MonitoringEngine` 的
 * `refreshFreshnessSnapshots`）—— 两个真相迟早会打架，这里只留一个。
 *
 * ## 「仍显示时间、但不标过期」是两条独立的规则
 * 用户明确要求：**封禁中**的主播仍要显示"上次检查是多久以前"，只是不要标「数据过期」
 * （封禁期间本来就不会有常规检查，标过期是在报告一件设计如此的事）。
 * 这两件事分别由 [isDataExpired] 与 [freshnessRowOf] 的 `timeText` 表达，
 * 由单测逐条锁定（见 `FreshnessPolicyTest`）。
 */
object FreshnessPolicy {

    /**
     * 阈值（秒）的合法范围 —— 与 `ConfigRepository.merge` 的校验（30–86400 秒）、
     * `MonitoringConfigEntity.freshnessStaleSeconds` 的既有取值**完全一致**。
     *
     * ★ 这里刻意**复用既有的 `freshnessStaleSeconds`**，不新增任何字段：
     *   它已经是唯一生效的阈值（引擎判 FRESH/STALE 用的就是它），再加一个"过期阈值"
     *   只会出现两个互相打架的阈值，而且新增字段要动 schema、迁移、备份 DTO 与诊断导出四处。
     */
    val THRESHOLD_SECONDS_RANGE = 30..86_400

    /**
     * 同一个阈值的**分钟**表达（设置页用分钟输入，取值范围 1–1440）。
     *
     * 1 分钟 = 60 秒、1440 分钟 = 86400 秒，正好把 [THRESHOLD_SECONDS_RANGE] 的
     * 可用区间整个覆盖，且换算结果永远落在合法范围内（提交时 ×60 不会撞上校验）。
     *
     * 已知取舍（如实记录）：既有的 30–59 秒**无法用整分钟表达**。见 [thresholdMinutesOf]。
     */
    val THRESHOLD_MINUTES_RANGE = 1..1_440

    /** 默认阈值：5 分钟（与 `DbSeed` / 既有默认 300 秒一致）。 */
    const val DEFAULT_THRESHOLD_SECONDS = 300

    /** 省电模式下 WorkManager 实际周期的下限/上限（分钟）：`schedulePowerSaving` 的 clamp 同源。 */
    const val POWER_SAVING_MIN_PERIOD_MINUTES = 15
    const val POWER_SAVING_MAX_PERIOD_MINUTES = 240

    /**
     * 省电模式的调度周期（分钟）。
     *
     * ★ 为什么调度侧的换算放在这里：判定"数据过期"必须知道**真实的**调度周期，
     *   而真实周期由调度器决定（`MonitoringController.schedulePowerSaving` 把周期
     *   `coerceIn(15, 240)` 分钟）。让调度器**反过来调用**这个函数，才能保证
     *   "界面以为的周期"与"实际排程的周期"永远是同一个数 —— 两处各写一份 clamp 必然漂移，
     *   而漂移的表现就是本节要修的"永远显示过期"。
     */
    fun powerSavingPeriodMinutes(intervalSeconds: Int): Int =
        (intervalSeconds / 60).coerceIn(POWER_SAVING_MIN_PERIOD_MINUTES, POWER_SAVING_MAX_PERIOD_MINUTES)

    /**
     * 各模式下**实际**多久检查一次（秒）：
     *  - [MonitoringMode.POWER_SAVING]：WorkManager 的周期，被 clamp 到 15–240 分钟；
     *  - [MonitoringMode.REALTIME]：前台服务按 `intervalSeconds` 循环（`MonitoringService`）；
     *  - [MonitoringMode.MANUAL]：只在打开 App 时检查 —— 没有"周期"可言，这里按配置值返回，
     *    于是长时间不开 App 就会如实标成过期（这是符合事实的，不粉饰）。
     */
    fun tickPeriodSeconds(mode: MonitoringMode, intervalSeconds: Int): Int = when (mode) {
        MonitoringMode.POWER_SAVING -> powerSavingPeriodMinutes(intervalSeconds) * 60
        MonitoringMode.REALTIME, MonitoringMode.MANUAL -> intervalSeconds.coerceAtLeast(0)
    }

    /**
     * **有效阈值** = `max(阈值, 2 × 调度周期)`，单位秒。
     *
     * ## 为什么必须有这一行（否则是把一个 bug 换成另一个 bug）
     * 省电模式的周期被 clamp 到 **≥15 分钟**（900 秒），而默认阈值只有 300 秒 ——
     * 也就是说"上一次成功确认"与"下一次检查"之间**必然**超过阈值，
     * 若直接拿 300 秒判过期，省电模式下会**永远显示过期**：那不是修复，是换了一种错法。
     * 有效阈值按 2 个周期算，与项目里"断档截断"的既有口径一致
     * （`MonitorRepository.truncatedEndTime`：超过 **2 个**检查间隔才算中间断过），
     * 于是"能追得上"的判定与"算不算断档"的判定用的是同一个时间尺度。
     */
    fun effectiveThresholdSeconds(thresholdSeconds: Int, tickPeriodSeconds: Int): Long {
        val threshold = thresholdSeconds.coerceAtLeast(0).toLong()
        val byTick = 2L * tickPeriodSeconds.coerceAtLeast(0).toLong()
        return maxOf(threshold, byTick)
    }

    /**
     * 过期判定的**基准时间**：确认时间优先，为 null 退回最后检查时间。
     *
     * 与既有语义一致（`MonitorRepository.freshnessOf` 用 `lastConfirmedAt`；
     * 卡片上的「N 分钟前」用 `lastCheckedAt`）。两者都为 null = 从来没有检查过 ⇒
     * **不判过期**（没有基准就没有"过期"可言，宁可不说也不要编一个）。
     */
    fun baseTimeOf(lastConfirmedAt: Long?, lastCheckedAt: Long?): Long? =
        lastConfirmedAt ?: lastCheckedAt

    /**
     * 数据是否**已过期**（界面与引擎唯一的判定入口）。
     *
     * 语义：`!banned && now - 基准 > 有效阈值`。
     *
     *  - **严格大于**：恰好等于阈值算"还没过期"（与 [freshnessOf] 的 `<=` 判 FRESH 严丝合缝，
     *    不会出现"列说新鲜、界面说过期"的自相矛盾）；
     *  - `banned = true` ⇒ **恒 false**：封禁期间主播在发请求之前就被过滤
     *    （`MonitoringEngine` 的封禁分流），`lastCheckedAt` 本来就不会推进，
     *    此时标「数据过期」是在报告一件设计如此的事，用户要的是"仍显示时间、但不标过期"；
     *  - 两个时间都为 null ⇒ false（见 [baseTimeOf]）；
     *  - 时钟回拨（`now < 基准`，网络校时/用户改时间都可能）⇒ false，不判过期。
     */
    fun isDataExpired(
        now: Long,
        lastConfirmedAt: Long?,
        lastCheckedAt: Long?,
        thresholdSeconds: Int,
        tickPeriodSeconds: Int,
        banned: Boolean
    ): Boolean {
        if (banned) return false
        val base = baseTimeOf(lastConfirmedAt, lastCheckedAt) ?: return false
        val elapsed = now - base
        if (elapsed <= 0L) return false
        return elapsed > effectiveThresholdSeconds(thresholdSeconds, tickPeriodSeconds) * 1000L
    }

    /**
     * 存储列 `streamer.freshnessStatus` 的判定 —— **与界面同源**（引擎写、界面读同一份规则）。
     *
     * 语义与修复前的 `MonitorRepository.freshnessOf` 逐字相同（`<=` 判 FRESH、null 判 UNKNOWN），
     * 只是搬到了公共位置：原来是 `private`，于是引擎写一套、界面自己猜一套。
     *
     * 注意它与 [isDataExpired] 的关系：**恰好等于阈值时**这里给 FRESH、那里给"不过期"，
     * 两者一致（一个说"还新鲜"，一个说"还没过期"），不存在"列 FRESH 但界面标过期"的组合。
     *
     * @param thresholdSeconds **有效阈值**（调用方用 [effectiveThresholdSeconds] 算好再传）
     */
    fun freshnessOf(lastConfirmedAt: Long?, now: Long, thresholdSeconds: Long): DataFreshness {
        if (lastConfirmedAt == null) return DataFreshness.UNKNOWN
        return if (now - lastConfirmedAt <= thresholdSeconds * 1000L) DataFreshness.FRESH
        else DataFreshness.STALE
    }

    /**
     * 阈值展示：秒 → 分钟（设置页用分钟输入）。
     *
     * ★ **向上取整**（`(seconds + 59) / 60`），不是简单除 60：
     *   老库里可能存在 30–59 秒的阈值（历史配置/手工改盘），整除会显示成 **0**，
     *   而 0 落在 `validRange = 1..1440` 之外 ⇒ 输入框一进页面就变红报错、保存被禁用，
     *   用户面对一个他从没设过的非法值。向上取整到 1 分钟既落在合法范围内，
     *   又永远不小于真实阈值（宁可把阈值说得略长，也不要显示得比实际更"容易过期"）。
     *   代价（如实说明）：30–59 秒与 61–119 秒这些中间值显示得不精确（分别显示 1 与 2 分钟）；
     *   而且**只要用户不点「保存」就不会写库**，所以这个不精确只停留在显示层。
     */
    fun thresholdMinutesOf(seconds: Int): Int =
        ((seconds + 59) / 60).coerceIn(THRESHOLD_MINUTES_RANGE.first, THRESHOLD_MINUTES_RANGE.last)

    /** 阈值提交：分钟 → 秒（设置页 → `freshnessStaleSeconds`）。范围外的输入被夹到边界。 */
    fun thresholdSecondsOf(minutes: Int): Int =
        minutes.coerceIn(THRESHOLD_MINUTES_RANGE.first, THRESHOLD_MINUTES_RANGE.last) * 60

    /**
     * 状态行要渲染的内容：是否追加「· 数据过期」标记 + 时间文案。
     *
     * 抽成纯数据 + 纯函数是为了**让"仍显示时间"这条用户要求可以被单测逐条锁定**
     * （本项目 UI 层没有测试基建，Composable 里的 if 分支测不到）。
     */
    data class FreshnessRow(
        /** 是否渲染「· 数据过期」 */
        val showExpiredMark: Boolean,
        /** 时间文案（如「· 12 分钟前」）；null = 不渲染时间 */
        val timeText: String?
    )

    /**
     * 算出状态行要渲染什么。
     *
     * 两条规则各自独立：
     *  ① `showExpiredMark = 开关开启 && 已过期`（[isDataExpired] 内部已含"封禁中不判过期"）；
     *  ② `timeText` **只**取决于 `lastCheckedAt` 是否存在 —— 与是否过期、是否封禁、
     *     开关是否打开**全都无关**。用户原话：「封禁中仍显示时间，方便看到目前状态」；
     *     而且"上次检查是多久以前"本身就是判断数据能不能信的依据，任何情况下都不该被藏掉。
     *     （封禁期间显示的是"最后一次**常规**检查"的时间 —— 主播在发请求前就被过滤，
     *     低频复查只写 `lastError` 不推 `lastCheckedAt`，所以这个数字会一直变大，这是符合预期的。）
     *
     * @param timeTextOf 时间文案的生成方式由 UI 注入（它要读设备时区/时间格式，不该进领域层）
     */
    fun freshnessRowOf(
        now: Long,
        lastConfirmedAt: Long?,
        lastCheckedAt: Long?,
        thresholdSeconds: Int,
        tickPeriodSeconds: Int,
        banned: Boolean,
        hintEnabled: Boolean,
        timeTextOf: (Long) -> String
    ): FreshnessRow {
        val expired = isDataExpired(
            now = now,
            lastConfirmedAt = lastConfirmedAt,
            lastCheckedAt = lastCheckedAt,
            thresholdSeconds = thresholdSeconds,
            tickPeriodSeconds = tickPeriodSeconds,
            banned = banned
        )
        return FreshnessRow(
            showExpiredMark = hintEnabled && expired,
            timeText = lastCheckedAt?.let(timeTextOf)
        )
    }
}
