package com.example.bilimonitor.domain.policy

import com.example.bilimonitor.data.local.NotificationEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRoutingPolicyTest {
    @Test fun `every event type has an explicit destination`() {
        val expected = mapOf(
            NotificationEventType.START_CONFIRMED to NotificationRoutingPolicy.Destination.LIVE_ROOM,
            NotificationEventType.LIVE_RECONFIRMED to NotificationRoutingPolicy.Destination.LIVE_ROOM,
            NotificationEventType.TITLE_CHANGED to NotificationRoutingPolicy.Destination.LIVE_ROOM,
            NotificationEventType.AREA_CHANGED to NotificationRoutingPolicy.Destination.LIVE_ROOM,
            NotificationEventType.END_CONFIRMED to NotificationRoutingPolicy.Destination.HISTORY,
            NotificationEventType.BATCH_LIVE to NotificationRoutingPolicy.Destination.BATCH,
            NotificationEventType.BATCH_END to NotificationRoutingPolicy.Destination.BATCH,
            NotificationEventType.BAN_ENTERED to NotificationRoutingPolicy.Destination.STREAMER,
            NotificationEventType.BAN_LIFTED to NotificationRoutingPolicy.Destination.STREAMER,
            NotificationEventType.SYSTEM_PROBLEM to NotificationRoutingPolicy.Destination.DIAGNOSTICS,
            NotificationEventType.SYSTEM_RECOVERED to NotificationRoutingPolicy.Destination.DIAGNOSTICS
        )
        assertEquals(NotificationEventType.entries.toSet(), expected.keys)
        expected.forEach { (type, destination) -> assertEquals(type.name, destination, NotificationRoutingPolicy.destination(type)) }
    }

    @Test fun `batch end cannot take the live room path`() {
        assertEquals(NotificationRoutingPolicy.Destination.BATCH,
            NotificationRoutingPolicy.destination(NotificationEventType.BATCH_END))
    }
}
