package com.example.bilimonitor.domain.policy

object NameRefreshPolicy {
    val hours = 1..8760
    val minutes = 1..1440

    fun remaining(wall: Long?, elapsed: Long?, boot: String?, succeeded: Boolean,
        nowWall: Long, nowElapsed: Long, nowBoot: String, refreshHours: Int, retryMinutes: Int): Long {
        if (wall == null) return 0
        val interval = if (succeeded) refreshHours * 3_600_000L else retryMinutes * 60_000L
        val age = if (boot == nowBoot && elapsed != null) nowElapsed - elapsed else nowWall - wall
        return if (age < 0) 0 else (interval - age).coerceAtLeast(0)
    }
}
