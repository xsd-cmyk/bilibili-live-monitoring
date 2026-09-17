package com.example.bilimonitor.domain.policy

/**
 * 免打扰时段判定（原规范 22.3：默认 23:00–07:00、默认关闭）。
 *
 * 抽成纯函数以便单测：时段支持**跨午夜**（start > end），
 * 例如 23:00–07:00 表示 [1380, 1440) ∪ [0, 420)。
 */
object QuietHoursPolicy {

    /** 该分钟数是否落在免打扰区间内（支持跨午夜）。 */
    fun contains(nowMinutesOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean {
        val now = nowMinutesOfDay.mod(24 * 60)
        val start = startMinutes.mod(24 * 60)
        val end = endMinutes.mod(24 * 60)
        if (start == end) return false // 零长度区间视为未配置
        return if (start < end) {
            now >= start && now < end
        } else {
            // 跨午夜
            now >= start || now < end
        }
    }

    /** 把时分折算成当日起始分钟数。 */
    fun minutesOfDay(hour: Int, minute: Int): Int =
        (hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59))

    fun format(minutes: Int): String {
        val m = minutes.mod(24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 本地墙上时间 → 当日分钟数。 */
    fun nowMinutesOfDay(epochMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Int {
        val t = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        return minutesOfDay(t.hour, t.minute)
    }
}
