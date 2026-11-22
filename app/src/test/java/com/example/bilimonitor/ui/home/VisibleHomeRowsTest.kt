package com.example.bilimonitor.ui.home

import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.entity.StreamerEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleHomeRowsTest {
    private val first = StreamerEntity(
        id = 1, stableId = "fixture", uid = 1, name = "A", nameLocked = false, roomId = null,
        shortRoomId = null, avatarUrl = null, roomTitle = null, parentAreaName = null, areaName = null,
        coverUrl = null, liveUrl = null, confirmedLiveStatus = ConfirmedLiveStatus.LIVE,
        lastObservationResult = ObservationResult.SUCCESS, observationSequence = 1, streamerMonitorGeneration = 1,
        monitoringEnabled = false, isFavorite = false, monitorPolicyStableId = null, lastCheckedAt = null,
        lastConfirmedAt = null, freshnessStatus = DataFreshness.FRESH, lastLiveStartedAt = null,
        lastLiveEndedAt = null, lastError = null, deletedAt = null, createdAt = 1, updatedAt = 1
    )
    private val rows = listOf(
        first.copy(id = 2, confirmedLiveStatus = ConfirmedLiveStatus.ROUND),
        first.copy(id = 3, confirmedLiveStatus = ConfirmedLiveStatus.OFFLINE, lastError = "ROOM_BANNED {\"lockedAt\":1,\"nextCheckAt\":2}"),
        first.copy(id = 4, confirmedLiveStatus = ConfirmedLiveStatus.UNKNOWN, lastError = "ROOM_BANNED invalid"),
        first.copy(id = 5, name = "Z", isFavorite = true), first
    )

    @Test fun statusThenFavoriteThenName() {
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), projectHomeRows(HomeFilter.ALL, rows).rows.map { it.id })
        assertEquals(setOf(5L), projectHomeRows(HomeFilter.FAVORITE, rows).ids)
    }

    @Test fun filterConstrainsBothSelectionAndBanBadges() {
        val live = projectHomeRows(HomeFilter.LIVE, rows)
        assertEquals(setOf(1L, 5L), live.ids)
        assertEquals(emptySet<Long>(), live.bannedIds)
        val offline = projectHomeRows(HomeFilter.OFFLINE, rows)
        assertEquals(setOf(2L, 3L, 4L), offline.ids)
        assertEquals(setOf(3L), offline.bannedIds)
    }
}
