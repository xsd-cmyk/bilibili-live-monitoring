package com.example.bilimonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilimonitor.data.local.entity.LiveSessionTitleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveSessionTitleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LiveSessionTitleEntity): Long

    /**
     * **最近一次**记录的标题（`ORDER BY observedAt DESC`）。
     *
     * 变更检测与通知一律用这个方法，用于判断"标题是否变了"以及给通知填"变化前"。
     *
     * 删掉过一个 `lastTitle()`：它名字叫 last，SQL 却是 `ORDER BY observedAt ASC LIMIT 1`，
     * 取的是本场**第一条**标题 —— 拿它当"变化前"会让改过两次以上的标题显示错误的旧值
     * （A→B→C 时通知里写成 A→C）。留着这种"名字与语义相反"的方法只会被误用。
     */
    @Query("SELECT title FROM live_session_title WHERE sessionStableId = :sessionStableId ORDER BY observedAt DESC, title DESC LIMIT 1")
    suspend fun latestTitle(sessionStableId: String): String?

    @Query("SELECT count(*) FROM live_session_title WHERE sessionStableId = :sessionStableId")
    suspend fun countForSession(sessionStableId: String): Int

    @Query("SELECT * FROM live_session_title ORDER BY observedAt ASC")
    fun observeAll(): Flow<List<LiveSessionTitleEntity>>

    @Query("SELECT * FROM live_session_title WHERE sessionStableId = :sessionStableId ORDER BY observedAt ASC, title ASC")
    suspend fun listForSession(sessionStableId: String): List<LiveSessionTitleEntity>

    @Query("SELECT * FROM live_session_title ORDER BY observedAt ASC")
    suspend fun listAll(): List<LiveSessionTitleEntity>
}
