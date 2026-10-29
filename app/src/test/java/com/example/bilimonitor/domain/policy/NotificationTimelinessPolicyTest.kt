package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.NotificationEventType
import org.junit.Assert.*
import org.junit.Test

class NotificationTimelinessPolicyTest {
    private val defaults = NotificationTimelinessSettings()

    @Test fun `defaults distinguish live end and metadata alerts`() {
        assertEquals(300_000L, NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.START_CONFIRMED, defaults))
        assertEquals(600_000L, NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.END_CONFIRMED, defaults))
        assertEquals(180_000L, NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.AREA_CHANGED, defaults))
    }
    @Test fun `batches use the same validity as individual events`() {
        assertEquals(NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.START_CONFIRMED, defaults),
            NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.BATCH_LIVE, defaults))
        assertEquals(NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.END_CONFIRMED, defaults),
            NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.BATCH_END, defaults))
    }
    @Test fun `clock moved backwards does not make a same boot event new again`() {
        assertEquals(310_000L, NotificationTimelinessPolicy.ageMs(1, 320_000, "a", 1_000_000, 10_000, "a"))
    }
    @Test fun `clock moved forwards does not prematurely age a same boot event`() {
        assertEquals(1000L, NotificationTimelinessPolicy.ageMs(9_000_000, 11_000, "a", 1_000_000, 10_000, "a"))
    }
    @Test fun `after reboot uptime cannot be compared to the previous boot`() {
        assertEquals(600_000L, NotificationTimelinessPolicy.ageMs(1_600_000, 10, "b", 1_000_000, 1000, "a"))
    }
    @Test fun `legacy events use the original wall timestamp`() {
        assertEquals(600_000L, NotificationTimelinessPolicy.ageMs(1_600_000, 10, "b", 1_000_000, null, null))
    }
    @Test fun `exactly at validity limit is expired`() {
        val age = NotificationTimelinessPolicy.ageMs(300_000, 300_000, "a", 0, 0, "a")
        assertTrue(age >= NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.START_CONFIRMED, defaults))
    }
    @Test fun `negative ages never create a negative delay`() {
        assertEquals(0L, NotificationTimelinessPolicy.ageMs(1, 1, "a", 20, null, null))
    }
    @Test fun `invalid user settings are rejected instead of clamped`() {
        assertFalse(NotificationTimelinessSettings(liveMinutes = 0).isValid())
        assertFalse(NotificationTimelinessSettings(endMinutes = 1441).isValid())
        assertFalse(NotificationTimelinessSettings(changeMinutes = -1).isValid())
        assertTrue(NotificationTimelinessSettings(1, 1440, 3).isValid())
    }
    @Test fun `custom validity affects both single and batch alerts`() {
        val custom = NotificationTimelinessSettings(2, 7, 1)
        assertEquals(120_000L, NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.BATCH_LIVE, custom))
        assertEquals(420_000L, NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.BATCH_END, custom))
        assertEquals(60_000L, NotificationTimelinessPolicy.lifetimeMs(NotificationEventType.TITLE_CHANGED, custom))
    }
}
