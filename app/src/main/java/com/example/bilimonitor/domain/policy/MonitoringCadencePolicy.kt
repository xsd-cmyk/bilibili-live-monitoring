package com.example.bilimonitor.domain.policy

/** Start-to-start cadence; skip missed slots instead of replaying a burst of old checks. */
object MonitoringCadencePolicy {
    fun delayMs(startElapsed: Long, finishedElapsed: Long, intervalSeconds: Int): Long {
        val period = intervalSeconds.coerceAtLeast(1) * 1000L
        val elapsed = (finishedElapsed - startElapsed).coerceAtLeast(0L)
        return period - elapsed % period
    }

    fun needsWakeLock(enabled: Boolean, screenInteractive: Boolean, networkAvailable: Boolean,
        monitoredCount: Int?): Boolean = enabled && !screenInteractive && networkAvailable && monitoredCount != 0
}
