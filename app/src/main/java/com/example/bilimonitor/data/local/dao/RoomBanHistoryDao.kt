package com.example.bilimonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilimonitor.data.local.entity.RoomBanHistoryEntity
import kotlinx.coroutines.flow.Flow

data class RoomBanCount(val streamerId: Long, val count: Int)

@Dao
interface RoomBanHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: RoomBanHistoryEntity): Long

    @Query("SELECT * FROM room_ban_history WHERE streamerId = :id AND detectedAt = :at LIMIT 1")
    suspend fun findEpisode(id: Long, at: Long): RoomBanHistoryEntity?

    @Query("SELECT streamerId, COUNT(*) AS count FROM room_ban_history GROUP BY streamerId")
    fun observeCounts(): Flow<List<RoomBanCount>>

    @Query("SELECT COUNT(*) FROM room_ban_history WHERE streamerId = :id")
    fun observeCount(id: Long): Flow<Int>

    @Query("SELECT * FROM room_ban_history WHERE streamerId = :id ORDER BY detectedAt DESC, banId DESC LIMIT :limit")
    fun observeRecent(id: Long, limit: Int): Flow<List<RoomBanHistoryEntity>>

    @Query("SELECT * FROM room_ban_history ORDER BY detectedAt ASC, banId ASC")
    suspend fun listAll(): List<RoomBanHistoryEntity>

    @Query("SELECT * FROM room_ban_history WHERE streamerId IN (:ids) AND detectedAt >= :from AND detectedAt < :to ORDER BY detectedAt DESC")
    suspend fun listForExport(ids: List<Long>, from: Long, to: Long): List<RoomBanHistoryEntity>

    @Query("""UPDATE room_ban_history SET sessionStableId = COALESCE(sessionStableId, :session),
        lockTill = :lockTill, roomId = COALESCE(:roomId, roomId)
        WHERE banId = :id AND (sessionStableId IS NULL AND :session IS NOT NULL
            OR lockTill IS NOT :lockTill OR (:roomId IS NOT NULL AND roomId IS NOT :roomId))""")
    suspend fun noteDetails(id: String, session: String?, lockTill: Long?, roomId: Long?): Int

    @Query("UPDATE room_ban_history SET liftedAt = :now WHERE streamerId = :id AND detectedAt = :at AND liftedAt IS NULL")
    suspend fun noteLifted(id: Long, at: Long, now: Long): Int
}
