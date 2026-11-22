package com.example.bilimonitor.domain.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationFreshnessTest {
    @Test fun wallJumpsDoNotChangeSameBootAge() {
        for (wall in listOf(1L, 12_000_000L)) {
            assertTrue(NotificationTimelinessPolicy.observationFresh(wall, 130_000, "a", 4_000_000, 10_000, "a", 60))
            assertFalse(NotificationTimelinessPolicy.observationFresh(wall, 130_001, "a", 4_000_000, 10_000, "a", 60))
        }
    }

    @Test fun neverCompareElapsedAcrossBootsOrIncompleteAnchors() {
        assertFalse(NotificationTimelinessPolicy.observationFresh(50_000, 10, "b", 50_000, 8, "a", 60))
        assertFalse(NotificationTimelinessPolicy.observationFresh(50_000, 10, "b", 50_000, 8, null, 60))
    }

    @Test fun legacyAgeRequiresBoundedNonFutureWallEvidence() {
        assertTrue(NotificationTimelinessPolicy.observationFresh(50_000, 10, "a", 40_000, null, null, 60))
        assertFalse(NotificationTimelinessPolicy.observationFresh(50_000, 10, "a", 60_000, null, null, 60))
        assertFalse(NotificationTimelinessPolicy.observationFresh(50_000, 10, "a", null, null, null, 60))
    }
}
