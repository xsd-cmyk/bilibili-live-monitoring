package com.example.bilimonitor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "room_ban_history", foreignKeys = [
    ForeignKey(entity = StreamerEntity::class, parentColumns = ["id"], childColumns = ["streamerId"])
], indices = [Index("streamerId"), Index("sessionStableId"), Index(value = ["streamerId", "detectedAt"], unique = true)])
data class RoomBanHistoryEntity(
    @PrimaryKey val banId: String, val streamerId: Long, val sessionStableId: String?,
    val detectedAt: Long, val liftedAt: Long?, val lockTill: Long?, val roomId: Long?
) {
    companion object {
        fun key(streamerStableId: String, detectedAt: Long) = "room-ban:$streamerStableId:$detectedAt"
    }
}
