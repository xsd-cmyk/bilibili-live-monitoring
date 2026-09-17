package com.example.bilimonitor.ui.common

import com.example.bilimonitor.data.repository.ExportRepository
import kotlinx.coroutines.CancellationException


/**
 * 执行一次"直播历史导出"，把结果翻译成给用户看的一句话。
 *
 * 两个入口（主播卡片上的导出、直播历史一级界面的导出）共用它，
 * 保证同一套时间范围语义、同一套失败提示。
 *
 * 提示里的文件名是 [ExportRepository.ExportResult.fileName]，也就是**磁盘上的实际名字**：
 * MediaStore 遇到同名文件会自动追加 " (1)"，报"请求名"会让用户按提示在 Download 里找不到文件。
 */
suspend fun performHistoryExport(
    exportRepository: ExportRepository,
    streamerIds: List<Long>,
    filter: HistoryFilter
): String {
    if (streamerIds.isEmpty()) return "请先选择至少一位主播"
    val bounds = filter.exportBounds() ?: return "请先选择时间范围"
    android.util.Log.i(
        "HistoryExport",
        "开始导出：主播=${streamerIds.size} 位，范围=${filter.label()}，[${
            bounds.first
        }, ${bounds.second})"
    )
    val result = exportRepository
        .exportHistory(streamerIds, bounds.first, bounds.second, filter.label())
    result.exceptionOrNull()?.let { e ->
        // 取消不是失败：原样重抛，别让它走到下面的 onFailure —— 否则界面上会出现
        // "导出失败：StandaloneCoroutine was cancelled" 这种内部英文串
        //（用户只是离开了页面，本来不该看到任何失败提示）。
        if (e is CancellationException) throw e
        android.util.Log.w("HistoryExport", "导出失败", e)
    }
    return result.fold(
        onSuccess = {
            android.util.Log.i(
                "HistoryExport",
                "导出成功：${it.fileName}（${it.streamerCount} 位主播 / ${it.sessionCount} 场，" +
                    "其中 ${it.emptyStreamerCount} 位无记录）"
            )
            val who = if (it.streamerCount == 1) "" else "${it.streamerCount} 位主播、"
            val empty = if (it.emptyStreamerCount > 0) "，${it.emptyStreamerCount} 位无记录" else ""
            "已导出到 Download/${it.fileName}（$who${it.sessionCount} 场$empty）"
        },
        onFailure = { "导出失败：${it.message ?: "未知错误"}" }
    )
}

/**
 * 执行一次"统计图表导出"。
 *
 * 规则、时间范围语义、命名与失败提示都与 [performHistoryExport] 一致（用户要求），
 * 差别只在导出内容：那边是逐场记录，这边是按桶聚合的序列 + 图表。
 */
suspend fun performStatsExport(
    exportRepository: ExportRepository,
    streamerIds: List<Long>,
    filter: HistoryFilter
): String {
    if (streamerIds.isEmpty()) return "请先选择至少一位主播"
    val bounds = filter.exportBounds() ?: return "请先选择时间范围"
    android.util.Log.i(
        "StatsExport",
        "开始导出统计：主播=${streamerIds.size} 位，范围=${filter.label()}"
    )
    val result = exportRepository.exportStats(streamerIds, bounds.first, bounds.second, filter.label())
    result.exceptionOrNull()?.let { e ->
        // 与 performHistoryExport 同一条规矩：取消原样重抛，不翻译成"导出失败"
        if (e is CancellationException) throw e
        android.util.Log.w("StatsExport", "导出失败", e)
    }
    return result.fold(
        onSuccess = {
            android.util.Log.i("StatsExport", "导出成功：${it.fileName}（${it.sessionCount} 场）")
            val who = if (it.streamerCount == 1) "" else "${it.streamerCount} 位主播、"
            val empty = if (it.emptyStreamerCount > 0) "，${it.emptyStreamerCount} 位无记录" else ""
            "已导出到 Download/${it.fileName}（$who${it.sessionCount} 场$empty）"
        },
        onFailure = { "导出失败：${it.message ?: "未知错误"}" }
    )
}
