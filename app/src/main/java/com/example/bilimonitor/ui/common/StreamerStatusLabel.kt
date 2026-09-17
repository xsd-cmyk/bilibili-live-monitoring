package com.example.bilimonitor.ui.common

import com.example.bilimonitor.data.local.ConfirmedLiveStatus

/**
 * 首页卡片与主播详情页那枚状态标签的**唯一**文案口径。
 *
 * ## 为什么要抽成一个纯函数
 * "封禁优先于「待确认」"是用户能直接看见的产品行为，而埋在 Compose 里的
 * `when (status)` 分支既没法被单测逐字锁定，又会在两个页面各存一份、早晚各自漂移
 * （详情页原本就有一份逐字相同的副本）。抽成纯函数后，
 * `ui.common.StreamerStatusLabelTest` 可以把"封禁 ⇒ 封禁中"和"未封禁的四条文案逐字不变"
 * 一起钉死 —— 本项目 UI 层没有插桩测试基建，纯函数是这类文案规则唯一能被自动验证的形态。
 *
 * ## 为什么封禁优先于「待确认」（本次需求的理由）
 * 被封禁的主播，`confirmedLiveStatus` 会一直停在 `UNKNOWN`、`lastObservationResult` 是 `INVALID`：
 * 引擎对封禁中的主播**跳过常规检查**（`RoomBanPolicy.shouldSkipRegularCheck`），
 * 复查时刻之外根本不去问房间状态，状态机于是永远推不出 LIVE / OFFLINE。
 * 所以「待确认」这四个字在这里是**误导**：它读起来像"还没轮到这位主播 / 这次没查出来"，
 * 而事实恰好相反 —— 封禁是一条**已经确认**的独立信号（`room_init.is_locked = true`，
 * 判据与反例见 `RoomBanPolicy` 顶部那张表），信息量比 UNKNOWN 更高。
 * 因此只要封禁标记在，就一律显示「封禁中」，与 [status] 取什么值无关。
 *
 * ## 刻意不做的事
 *  - **不动** `confirmedLiveStatus` 本身（封禁独立于确认状态，它仍是 UNKNOWN）；
 *  - **不看** `lastObservationResult`：改动前它就不参与文案（UNKNOWN 一律「待确认」，
 *    不管那次观察是 INVALID 还是 TIMEOUT），本次同样不把它拉进来 ——
 *    未封禁时的文案必须与改动前逐字一致。
 *
 * @param status 已确认的直播状态；封禁时被忽略，但仍是必填，避免调用方误以为"封禁就不用传了"。
 * @param banned `streamer.lastError` 经 `RoomBanCodec.isBanned` 解出的封禁标记。
 *   **刻意不给默认值**：漏传直接编译失败，不可能静默退回「待确认」。
 */
fun streamerStatusLabel(status: ConfirmedLiveStatus, banned: Boolean): String =
    if (banned) BANNED_STATUS_LABEL else when (status) {
        ConfirmedLiveStatus.LIVE -> "直播中"
        ConfirmedLiveStatus.ROUND -> "轮播中"
        ConfirmedLiveStatus.OFFLINE -> "未开播"
        ConfirmedLiveStatus.UNKNOWN -> "待确认"
    }

/**
 * 「封禁中」的字面量。
 *
 * 保持私有是刻意的：对外只有 [streamerStatusLabel] 一个入口，单测也必须自己写出这四个字，
 * 文案一旦被改动就会有用例失败，而不是跟着常量一起"自动通过"。
 */
private const val BANNED_STATUS_LABEL = "封禁中"
