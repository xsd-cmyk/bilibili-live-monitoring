package com.example.bilimonitor.data.repository

/**
 * 导出文件的命名规则（纯函数，便于单测钉住）。
 *
 * 规则（用户定稿，含两次补充）：
 *  - 单主播：`最早日期_最晚日期_主播名_类型.html`
 *  - 多主播：`最早日期_最晚日期_首位主播名等N位_类型.html`
 *
 * 四个刻意的决定：
 *  1. **时间范围取实际导出数据的日期**，不是用户选择的范围：选了"按年 2026"但只有两天有记录时，
 *     文件名应当只有那两天，否则名字与内容不符。
 *  2. **N 取"选择的主播数"而非"有记录的主播数"**：报告里每位被选中的主播都会出现
 *     （没记录的也有空分组），名字要与报告范围一致。
 *  3. **类型段是后加的**（用户反馈）：同一主播、同一时间范围的「直播历史」与「统计图表」
 *     原本会生成完全同名的文件，在 Download 里分不清哪个是哪个。
 *     刻意**不加导出时刻** —— 否则同一份数据每次导出都是新文件名，反而更难管理；
 *     同名 = 同内容，这一可预测性更重要。同类型重复导出时由 MediaStore 自动追加 `(1)`。
 *  4. **多主播的名字里补首位主播名**（用户反馈）：原先只写"N位主播"，于是 {甲,乙} 与 {丙,丁}
 *     生成的**同名**文件内容完全不同 —— "同名 = 同内容"这条承诺在多主播时并不成立。
 *     补一个可辨识片段即可，N 位仍然照写（报告范围必须一眼可见）。
 */
internal object ExportNaming {

    /** 导出类型：只用于文件名区分，不影响内容结构。 */
    enum class Kind(val label: String) {
        HISTORY("直播历史"),
        STATS("统计图表")
    }

    /** 历史导出用：从快照行里取时间戳（优先开播时间，缺失时退回关播时间）。 */
    fun forRows(rows: List<Map<String, String?>>, selectedNames: List<String>, kind: Kind): String {
        val times = rows.mapNotNull { (it["startTime"] ?: it["endTime"])?.toLongOrNull() }
        return of(times, selectedNames, kind)
    }

    fun of(times: List<Long>, selectedNames: List<String>, kind: Kind): String {
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zone = java.time.ZoneId.systemDefault()
        fun day(t: Long): String = java.time.Instant.ofEpochMilli(t).atZone(zone).format(dayFmt)
        val earliest = times.minOrNull()?.let { day(it) } ?: NO_RECORD
        val latest = times.maxOrNull()?.let { day(it) } ?: NO_RECORD
        val subject = if (selectedNames.size == 1) {
            sanitize(selectedNames.first())
        } else {
            multiSubject(selectedNames)
        }
        return "${earliest}_${latest}_${subject}_${kind.label}.html"
    }

    /** 去掉文件系统不允许的字符，避免生成非法文件名（Windows/Android 共用同一套限制）。 */
    fun sanitize(name: String): String {
        val cleaned = name.map { if (it in ILLEGAL_CHARS || it.code < 0x20) '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')
        return cleaned.ifBlank { "主播" }.take(MAX_NAME_LENGTH)
    }

    /**
     * 多主播的可辨识片段：`首位主播名等N位`。
     *
     * 为什么不能只写"N位主播"：{甲,乙} 与 {丙,丁} 会生成同名文件而内容不同，而 [of] 的
     * "同名 = 同内容"恰恰是靠文件名做到的 —— 重名时 MediaStore 追加 `(1)`，
     * 两份内容不同的报告就此变得无法分辨。
     * 长度受 [MAX_NAME_LENGTH] 约束：首位名字截到"上限 - 后缀长度"，整体不超过上限
     * （文件名其余部分是定长的日期段与类型段，离文件系统的长度限制还很远）。
     */
    private fun multiSubject(names: List<String>): String {
        val suffix = "等${names.size}位"
        val head = sanitize(names.first())
            .take((MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1))
            .trimEnd()
        return head + suffix
    }

    private const val NO_RECORD = "无记录"
    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"
    private const val MAX_NAME_LENGTH = 60
}
