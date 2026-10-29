package com.example.bilimonitor.domain.policy

import org.junit.Assert.*
import org.junit.Test

class MonitoringCadencePolicyTest {
    @Test fun `request duration does not extend the configured period`() {
        assertEquals(50_000L, MonitoringCadencePolicy.delayMs(1000, 11_000, 60))
    }
    @Test fun `overrun skips the missed check instead of sending a burst`() {
        assertEquals(50_000L, MonitoringCadencePolicy.delayMs(1000, 71_000, 60))
    }
    @Test fun `multiple missed periods remain bounded to one future slot`() {
        assertEquals(59_000L, MonitoringCadencePolicy.delayMs(0, 181_000, 60))
    }
    @Test fun `zero interval cannot cause a spin loop`() {
        assertEquals(1000L, MonitoringCadencePolicy.delayMs(0, 0, 0))
    }
    @Test fun `lock is kept for connected screen off realtime monitoring`() {
        assertTrue(MonitoringCadencePolicy.needsWakeLock(true, false, true, 3))
    }
    @Test fun `screen on does not need an additional cpu lock`() {
        assertFalse(MonitoringCadencePolicy.needsWakeLock(true, true, true, 3))
    }
    @Test fun `offline and empty monitoring release the cpu lock`() {
        assertFalse(MonitoringCadencePolicy.needsWakeLock(true, false, false, 3))
        assertFalse(MonitoringCadencePolicy.needsWakeLock(true, false, true, 0))
    }
    @Test fun `unknown count does not quietly disable realtime monitoring`() {
        assertTrue(MonitoringCadencePolicy.needsWakeLock(true, false, true, null))
    }
    @Test fun `user disabling the lock takes priority`() {
        assertFalse(MonitoringCadencePolicy.needsWakeLock(false, false, true, 3))
    }
}
