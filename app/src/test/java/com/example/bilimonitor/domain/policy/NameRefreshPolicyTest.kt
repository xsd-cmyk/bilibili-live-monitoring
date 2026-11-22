package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class NameRefreshPolicyTest {
    private fun remaining(success: Boolean, elapsed: Long, wall: Long = 0, boot: String = "one",
        hours: Int = 24, minutes: Int = 1) = NameRefreshPolicy.remaining(1000, 10, "one", success,
        wall, elapsed, boot, hours, minutes)

    @Test fun successAndFailureHaveIndependentDeadlines() {
        assertEquals(1L, remaining(true, 86_400_009))
        assertEquals(0L, remaining(true, 86_400_010))
        assertEquals(1L, remaining(false, 60_009))
        assertEquals(0L, remaining(false, 60_010))
        assertEquals(0L, remaining(true, 21_600_010, hours = 6))
        assertEquals(120_000L, remaining(false, 60_010, minutes = 3))
    }

    @Test fun wallClockJumpsDoNotAffectSameBootAndOldElapsedIsIgnored() {
        assertEquals(59_000L, remaining(false, 1010, wall = 999_999_999))
        assertEquals(1L, remaining(false, 99, wall = 60_999, boot = "two"))
        assertEquals(0L, remaining(false, 10, wall = 900, boot = "two"))
        assertEquals(0L, NameRefreshPolicy.remaining(null, null, null, false, 0, 0, "one", 24, 1))
    }
}
