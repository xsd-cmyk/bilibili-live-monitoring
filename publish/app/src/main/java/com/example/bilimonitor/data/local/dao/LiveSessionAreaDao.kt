package com.example.bilimonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity
import kotlinx.coroutines.flow.Flow

/**
 * 场次分区变更记录（用户定稿功能）。
 *
 * 与标题记录同构：同一场次内每次分区变化记一行，PK(sessionStableId, areaLabel) 去重，
 * 场次删除时由外键 CASCADE 清理。数据源是**公开**的直播状态接口，不需要登录。
 */
@Dao
interface LiveSessionAreaDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LiveSessionAreaEntity): Long

    /** 该场次**最近一次**记录的分区（用于判断"是否变了"与通知里的"变化前"）。 */
    @Query("SELECT areaLabel FROM live_session_area WHERE sessionStableId = :sessionStableId ORDER BY observedAt DESC, areaLabel ASC LIMIT 1")
    suspend fun lastArea(sessionStableId: String): String?

    @Query("SELECT count(*) FROM live_session_area WHERE sessionStableId = :sessionStableId")
    suspend fun countForSession(sessionStableId: String): Int

    @Query("SELECT * FROM live_session_area ORDER BY observedAt ASC")
    fun observeAll(): Flow<List<LiveSessionAreaEntity>>

    @Query("SELECT * FROM live_session_area WHERE sessionStableId = :sessionStableId ORDER BY observedAt ASC, areaLabel ASC")
    suspend fun listForSession(sessionStableId: String): List<LiveSessionAreaEntity>

    @Query("SELECT * FROM live_session_area ORDER BY observedAt ASC")
    suspend fun listAll(): List<LiveSessionAreaEntity>

    /** 手工补录/修正替换分区时用：先清掉该场次已有的自动记录。 */
    @Query("DELETE FROM live_session_area WHERE sessionStableId = :sessionStableId")
    suspend fun deleteForSession(sessionStableId: String): Int
}
