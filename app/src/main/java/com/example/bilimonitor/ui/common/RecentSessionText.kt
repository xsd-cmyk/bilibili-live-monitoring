package com.example.bilimonitor.ui.common

import com.example.bilimonitor.ui.home.formatTime

/**
 * 一级列表（直播历史 / 统计）里那行「最近：…」的文案。
 *
 * 抽成一份共用实现，是因为这两个页面对"最近"的**口径必须一致**：同一个含义在两页显示不同
 * （例如一页是开播时间、另一页是下播时间）会让人以为是数据错了。
 *
 * [startAt] / [endAt] 是**同一场**（该主播最新一场）的开播与下播时刻：
 *  - 已结束（`endTime` 有值）：显示**下播**时刻 —— 用户原话这么定的口径：
 *    "最近时间应该是显示最新一场直播的下播时间，而不是开播时间"；
 *  - 还在直播（`endTime` 为 null）：没有下播时间可显示，退一步显示开播时刻并标明"直播中"
 *    （用户定稿：这两个字以前写的是"进行中"，与首页状态标签"直播中"统一）。
 *    这样界面既不会出现 null / 空白，也不会假装那一场已经结束；比只显示"—"更能说明
 *    "此刻就在播"，也避免用户把上一场的下播时间误当成最新一场的；
 *  - 完全查不到场次（库里没有，或该主播的场次落在列表查询窗口之外）：仍是"—"（与旧行为一致）。
 *
 * 时间格式化复用 [formatTime]（yyyy-MM-dd HH:mm），不在这里另写一份：
 * 复制一份格式串，两处早晚会各自漂移。
 */
fun recentSessionText(startAt: Long?, endAt: Long?): String = when {
    endAt != null -> "最近：${formatTime(endAt)}"
    startAt != null -> "最近：直播中（开播 ${formatTime(startAt)}）"
    else -> "最近：—"
}
