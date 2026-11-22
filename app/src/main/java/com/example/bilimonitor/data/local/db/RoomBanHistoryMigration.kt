package com.example.bilimonitor.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.bilimonitor.data.local.entity.RoomBanHistoryEntity
import com.example.bilimonitor.domain.policy.RoomBanCodec

internal object RoomBanHistoryMigration {
    fun backfill(db: SupportSQLiteDatabase) {
        db.query("""SELECT a.targetStableId, s.id, a.occurredAt, s.roomId,
            (SELECT MIN(l.occurredAt) FROM audit_log l WHERE l.targetStableId = a.targetStableId
                AND l.action = 'ROOM_BAN_LIFTED' AND l.occurredAt >= a.occurredAt
                AND NOT EXISTS (SELECT 1 FROM audit_log newer WHERE newer.targetStableId = a.targetStableId
                    AND newer.action = 'ROOM_BAN_ENTERED' AND newer.occurredAt > a.occurredAt AND newer.occurredAt <= l.occurredAt))
            FROM audit_log a JOIN streamer s ON s.stableId = a.targetStableId
            WHERE a.action = 'ROOM_BAN_ENTERED' AND a.occurredAt >= 0""").use { c ->
            while (c.moveToNext()) {
                val sid = c.getLong(1); val at = c.getLong(2)
                insert(db, c.getString(0), sid, at, if (c.isNull(4)) null else c.getLong(4),
                    null, if (c.isNull(3)) null else c.getLong(3))
            }
        }
        db.query("SELECT stableId, id, lastError, roomId FROM streamer WHERE lastError LIKE 'ROOM_BANNED %'").use { c ->
            while (c.moveToNext()) {
                val state = RoomBanCodec.decode(c.getString(2)) ?: continue
                if (state.lockedAtWall < 0 || state.apiMessage == "restored_requires_recheck") continue
                val roomId = if (c.isNull(3)) null else c.getLong(3)
                insert(db, c.getString(0), c.getLong(1), state.lockedAtWall, null, state.lockTill, roomId)
                db.execSQL("UPDATE room_ban_history SET liftedAt = NULL, lockTill = ? WHERE streamerId = ? AND detectedAt = ?",
                    arrayOf(state.lockTill, c.getLong(1), state.lockedAtWall))
            }
        }
    }

    private fun insert(db: SupportSQLiteDatabase, stable: String, id: Long, at: Long,
        liftedAt: Long?, lockTill: Long?, roomId: Long?) {
        db.execSQL("""INSERT OR IGNORE INTO room_ban_history
            (banId, streamerId, sessionStableId, detectedAt, liftedAt, lockTill, roomId)
            VALUES (?, ?, (SELECT stableId FROM live_session WHERE streamerId = ?
                AND COALESCE(startTime, createdAt) <= ? AND (endTime IS NULL OR endTime >= ?)
                ORDER BY COALESCE(startTime, createdAt) DESC, id DESC LIMIT 1), ?, ?, ?, ?)""",
            arrayOf(RoomBanHistoryEntity.key(stable, at), id, id, at, at, at, liftedAt, lockTill, roomId))
    }
}
