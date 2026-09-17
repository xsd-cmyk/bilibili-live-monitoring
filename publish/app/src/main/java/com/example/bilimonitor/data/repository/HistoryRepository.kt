package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.DataConfidence
import com.example.bilimonitor.data.local.DataSource
import com.example.bilimonitor.data.local.dao.LiveEventDao
import com.example.bilimonitor.data.local.dao.LiveSessionDao
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.dao.LiveSessionTitleDao
import com.example.bilimonitor.data.local.entity.LiveSessionCorrectionEntity
import com.example.bilimonitor.data.local.entity.LiveSessionTitleEntity
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class ManualCorrection(
    val sessionStableId: String,
    val startTime: Long?,
    val endTime: Long?,
    val durationSeconds: Long?,
    val note: String?,
    /** 修正后的直播间标题（可选）。 */
    val title: String? = null,
    /** 修正后的直播分区（可选，留空表示不改动）。 */
    val area: String? = null
)

sealed interface CorrectionResult {
    data object Applied : CorrectionResult
    data object Conflict : CorrectionResult
    data object NotFound : CorrectionResult
    data class Invalid(val reason: String) : CorrectionResult
}

@Singleton
class HistoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val liveSessionDao: LiveSessionDao,
    private val liveSessionTitleDao: LiveSessionTitleDao,
    private val liveEventDao: LiveEventDao,
    private val streamerDao: StreamerDao,
    private val logDao: LogDao,
    private val clock: AppClock
) {
    fun observeRecentSessions(limit: Int = 200): Flow<List<LiveSessionEntity>> =
        liveSessionDao.observeRecent(limit)

    fun observeSessionsByStreamer(streamerId: Long, limit: Int = 100): Flow<List<LiveSessionEntity>> =
        liveSessionDao.observeByStreamer(streamerId, limit)

    suspend fun sessionByStableId(stableId: String): LiveSessionEntity? =
        liveSessionDao.findByStableId(stableId)

    suspend fun eventsBySession(sessionStableId: String) =
        liveEventDao.listBySession(sessionStableId)

    /** 该场次记录到的全部分区（可能多次变更）。 */
    suspend fun areasBySession(sessionStableId: String) =
        db.liveSessionAreaDao().listForSession(sessionStableId)

    /**
     * 人工修正（原规范 111 / 113 / 249.12）：
     * correctionVersion CAS + Locked 字段保护 + 修正审计记录。
     * startLocked=1 的字段不可被自动更新覆盖（远程自动逻辑不会写 locked 字段）。
     */
    suspend fun applyManualCorrection(correction: ManualCorrection): CorrectionResult {
        val session = liveSessionDao.findByStableId(correction.sessionStableId)
            ?: return CorrectionResult.NotFound
        val now = clock.nowWall()

        // 三项缺一补全（用户定稿）：开播时间 / 下播时间 / 时长，填任意两项即推导第三项；
        // 不足两项时保持原时间不动（仅标题/备注可改）。
        var newStart = correction.startTime
        var newEnd = correction.endTime
        var newDuration = correction.durationSeconds
        val provided = listOfNotNull(newStart, newEnd, newDuration).size
        if (provided >= 2) {
            when {
                newStart != null && newDuration != null && newEnd == null ->
                    newEnd = newStart + newDuration * 1000
                newEnd != null && newDuration != null && newStart == null ->
                    newStart = (newEnd - newDuration * 1000).coerceAtLeast(0)
                newStart != null && newEnd != null && newDuration == null ->
                    newDuration = ((newEnd - newStart) / 1000).coerceAtLeast(0)
            }
        }
        val start = newStart ?: session.startTime
        val end = newEnd ?: session.endTime
        val duration = newDuration ?: session.durationSeconds
        val timesChanged = provided >= 2
        if (start != null && end != null && end < start) {
            return CorrectionResult.Invalid("下播时间不能早于开播时间")
        }
        if (!timesChanged && correction.note == session.note &&
            (correction.title == null || correction.title == session.titleAtStart)
        ) {
            return CorrectionResult.Invalid("至少需要提供两项：开播时间 / 下播时间 / 时长")
        }
        // 手动补出下播时间视为人工关播确认
        val confirmedOffline = if (correction.endTime != null && session.endTime == null) newEnd else null

        return db.withTransaction {
            val rows = liveSessionDao.casManualCorrect(
                id = session.id,
                startTime = if (timesChanged) start else null,
                endTime = if (timesChanged) end else null,
                durationSeconds = if (timesChanged) duration else null,
                startSource = if (timesChanged && start != null) DataSource.USER_CORRECTED.name else null,
                endSource = if (timesChanged && end != null) DataSource.USER_CORRECTED.name else null,
                durationSource = if (timesChanged && duration != null) DataSource.USER_CORRECTED.name else null,
                startConfidence = if (timesChanged && start != null) DataConfidence.CORRECTED.name else null,
                endConfidence = if (timesChanged && end != null) DataConfidence.CORRECTED.name else null,
                durationConfidence = if (timesChanged && duration != null) DataConfidence.CORRECTED.name else null,
                confirmedOfflineAt = confirmedOffline,
                note = correction.note,
                expectedCorrectionVersion = session.correctionVersion,
                now = now
            )
            if (rows == 0) return@withTransaction CorrectionResult.Conflict
            // 标题修正：更新首标题并记入标题变更表
            val newTitle = correction.title?.takeIf { it.isNotBlank() }
            if (newTitle != null && newTitle != session.titleAtStart) {
                liveSessionDao.updateTitleAtStart(session.id, newTitle, now)
                liveSessionTitleDao.insert(
                    LiveSessionTitleEntity(session.stableId, newTitle, now)
                )
            }
            // 分区修正（可留空 = 不改）：填了就**替换**该场次的分区记录 ——
            // 自动记录可能把换分区记成好几条，人工填的是"我认为这一场属于哪个分区"这一件事。
            val newArea = correction.area?.takeIf { it.isNotBlank() }
            if (newArea != null) {
                db.liveSessionAreaDao().deleteForSession(session.stableId)
                db.liveSessionAreaDao().insert(
                    com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity(
                        sessionStableId = session.stableId,
                        areaLabel = newArea,
                        parentAreaName = null,
                        areaName = newArea,
                        observedAt = now
                    )
                )
                liveSessionDao.updateAreaAtStart(session.id, newArea, now)
            }
            db.liveSessionCorrectionDao().insert(
                LiveSessionCorrectionEntity(
                    correctionId = Ids.newId(),
                    sessionStableId = session.stableId,
                    baseCorrectionVersion = session.correctionVersion,
                    newCorrectionVersion = session.correctionVersion + 1,
                    changedFieldsJson = changedFieldsJson(correction),
                    source = DataSource.USER_CORRECTED,
                    createdAt = now,
                    operationId = Ids.newId()
                )
            )
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "MANUAL_CORRECT_SESSION", targetType = "live_session",
                    targetStableId = session.stableId, occurredAt = now,
                    detailJson = changedFieldsJson(correction)
                )
            )
            streamerDao.bumpSourceDataVersion(now)
            CorrectionResult.Applied
        }
    }

    /** 手工创建直播记录（原规范 108.5）：三项填二补全第三；标题与分区都可留空。 */
    suspend fun createManualSession(
        streamerStableId: String,
        startTime: Long?,
        endTime: Long?,
        durationSeconds: Long?,
        title: String?,
        note: String?,
        area: String? = null
    ): CorrectionResult {
        val streamer = streamerDao.findActiveByStableId(streamerStableId)
            ?: return CorrectionResult.NotFound
        var s = startTime
        var e = endTime
        var d = durationSeconds
        val provided = listOfNotNull(s, e, d).size
        if (provided < 2) return CorrectionResult.Invalid("至少需要填写两项：开播时间 / 下播时间 / 时长")
        when {
            s != null && d != null && e == null -> e = s + d * 1000
            e != null && d != null && s == null -> s = (e - d * 1000).coerceAtLeast(0)
            s != null && e != null && d == null -> d = ((e - s) / 1000).coerceAtLeast(0)
        }
        if (s != null && e != null && e < s) {
            return CorrectionResult.Invalid("下播时间不能早于开播时间")
        }
        val now = clock.nowWall()
        val stableId = Ids.stableId("sess")
        val duration = d
        return db.withTransaction {
            liveSessionDao.insert(
                LiveSessionEntity(
                    stableId = stableId,
                    sessionKey = Ids.sessionKey(stableId),
                    streamerId = streamer.id,
                    startTime = s,
                    endTime = e,
                    startSource = DataSource.USER_ENTERED,
                    endSource = if (endTime != null) DataSource.USER_ENTERED else null,
                    durationSource = if (duration != null) DataSource.USER_ENTERED else null,
                    startConfidence = DataConfidence.CORRECTED,
                    endConfidence = if (e != null) DataConfidence.CORRECTED else null,
                    durationConfidence = if (duration != null) DataConfidence.CORRECTED else null,
                    startLocked = true,
                    endLocked = e != null,
                    durationLocked = duration != null,
                    titleAtStart = null,
                    titleAtEnd = null,
                    areaAtStart = null,
                    areaAtEnd = null,
                    coverUrlAtStart = null,
                    coverUrlAtEnd = null,
                    durationSeconds = duration,
                    endReason = "USER_ENTERED",
                    lifecycleState = com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState.ACTIVE,
                    currentReliableIntervalId = null,
                    lastConfirmedLiveAt = null,
                    confirmedOfflineAt = e,
                    autoUpdateProtected = false,
                    correctionVersion = 0,
                    createdAt = now,
                    updatedAt = now,
                    note = note
                )
            )
            val finalTitle = title?.takeIf { it.isNotBlank() }
            if (finalTitle != null) {
                liveSessionDao.updateTitleAtStartByStableId(stableId, finalTitle, now)
                liveSessionTitleDao.insert(
                    LiveSessionTitleEntity(stableId, finalTitle, now)
                )
            }
            // 分区：手工补录时可留空 —— 留空就什么都不写，不会伪造一个"未知分区"
            val finalArea = area?.takeIf { it.isNotBlank() }
            if (finalArea != null) {
                liveSessionDao.updateAreaAtStart(
                    liveSessionDao.findByStableId(stableId)!!.id, finalArea, now
                )
                db.liveSessionAreaDao().insert(
                    com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity(
                        sessionStableId = stableId,
                        areaLabel = finalArea,
                        parentAreaName = null,
                        areaName = finalArea,
                        observedAt = now
                    )
                )
            }
            streamerDao.bumpSourceDataVersion(now)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "CREATE_MANUAL_SESSION", targetType = "live_session",
                    targetStableId = stableId, occurredAt = now, detailJson = null
                )
            )
            CorrectionResult.Applied
        }
    }

    /**
     * 删除一条直播记录（用户在历史页手动删除）。
     *
     * **删除顺序由外键约束决定，不可调整**（`PRAGMA foreign_keys = ON`，
     * 见 `AppDatabaseFactory` 的 onOpen —— 建库与每次开库都会重新打开）：
     *
     *   notification_history.outboxId          → notification_outbox (NO ACTION)
     *   notification_delivery_attempt.outboxId → notification_outbox (NO ACTION)
     *   notification_outbox.sourceEventId      → live_event         (NO ACTION)
     *   notification_aggregate_event.eventId   → live_event         (NO ACTION)
     *   live_session_title / live_session_area → live_session       (CASCADE)
     *
     * 原实现直接 `DELETE FROM live_event WHERE sessionStableId = ?`：只要该场次的事件被
     * 通知域引用过，这一句就抛 `FOREIGN KEY constraint failed (787)`，而整个删除在一个
     * 事务里 → 连主表都删不掉，用户看到的就是"这条记录删不掉"。
     * **自动监控生成的场次会带这种引用**：主播通知策略默认 `notifyStart` / `notifyEnd` = true
     *（`StreamerRepository.updatePolicy` 建策略行时的默认值），于是开播事件会进聚合窗口
     * 留下一条绑定行、关播事件会走单事件 Outbox 留下 `sourceEventId` —— 这些场次就删不掉；
     * 人工补录的场次没有 `live_event` 行（`createManualSession` 不写事件表），
     * 删的时候没有子行要处理，于是一直能删 —— 这正是"自动的删不掉、人工的能删"。
     *（例外：把开播/关播通知都关掉、事件从未进过通知管道的自动场次，旧实现本来也能删。）
     *
     * 因此这里按依赖顺序**先删子行、再删父行**，全部在一个事务里：
     * 通知历史 → 投递尝试 → 单事件 Outbox → 聚合绑定 → `live_event` → 场次子表 → `live_session`。
     * 任何一步失败整体回滚，不会留下"子行删了、父行还在"的半截状态。
     *
     * 删完递增 sourceDataVersion：统计缓存与诊断包都以它为失效依据，
     * 不递增的话统计页会继续显示已删除的场次。
     *
     * @return 实际删除的场次数（0 表示记录不存在）
     */
    suspend fun deleteSession(sessionStableId: String): Int {
        val now = clock.nowWall()
        return db.withTransaction {
            val exists = liveSessionDao.findByStableId(sessionStableId) ?: return@withTransaction 0

            // 第 1 步：取出本场次的事件 id。通知域的两处 NO ACTION 外键都指向
            // live_event.eventId，所以"事件能不能删掉"决定整条删除能不能成功。
            val eventIds = liveEventDao.listBySession(sessionStableId).map { it.eventId }
            var deletedOutbox = 0
            var deletedBindings = 0
            if (eventIds.isNotEmpty()) {
                // 第 2 步：单事件 Outbox（开播 / 关播通知）。它的两个子表也是 NO ACTION 外键，
                // 顺序必须是 通知历史 → 投递尝试 → Outbox 自己，否则删 Outbox 就被挡住。
                val outboxIds = db.notificationOutboxDao().idsForSourceEvents(eventIds)
                if (outboxIds.isNotEmpty()) {
                    db.notificationHistoryDao().deleteByOutboxIds(outboxIds)
                    db.notificationOutboxDao().deleteAttemptsForOutboxIds(outboxIds)
                    deletedOutbox = db.notificationOutboxDao().deleteByOutboxIds(outboxIds)
                }
                // 第 3 步：聚合绑定行（含已 RELEASED 的审计行 —— 它们没有别的清理路径，
                // 留着就是永久挡在 live_event 前面的行）。
                deletedBindings = db.notificationAggregateDao().deleteBindingsForEvents(eventIds)
            }

            // 第 4 步：事件本身 —— 走到这里已经没有任何行引用它们了。
            val deletedEvents = liveEventDao.deleteBySession(sessionStableId)
            // 第 5 步：场次的其余子表（修正审计 / 关播资格区间没有外键；分区表有外键但无外部引用）。
            val deletedCorrections = db.liveSessionCorrectionDao().deleteBySession(sessionStableId)
            val deletedIntervals = db.reliableIntervalDao().deleteBySession(sessionStableId)
            val deletedAreas = db.liveSessionAreaDao().deleteForSession(sessionStableId)
            // 第 6 步：最后删父行；`live_session_title` 靠它的 ON DELETE CASCADE 清理
            //（分区行上面已显式删过，只为让审计数字准确）。
            val deleted = liveSessionDao.deleteByStableId(sessionStableId)
            if (deleted > 0) {
                streamerDao.bumpSourceDataVersion(now)
                logDao.insertAudit(
                    AuditLogEntity(
                        auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                        action = "DELETE_SESSION", targetType = "live_session",
                        targetStableId = sessionStableId, occurredAt = now,
                        detailJson = "{\"streamerId\":${exists.streamerId},\"events\":$deletedEvents," +
                            "\"corrections\":$deletedCorrections,\"intervals\":$deletedIntervals," +
                            "\"areas\":$deletedAreas,\"outbox\":$deletedOutbox," +
                            "\"bindings\":$deletedBindings}"
                    )
                )
            }
            deleted
        }
    }

    private fun reconcileDuration(start: Long?, end: Long?, duration: Long?): Long? {
        if (start != null && end != null) return ((end - start) / 1000).coerceAtLeast(0)
        return duration
    }

    private fun changedFieldsJson(c: ManualCorrection): String {
        val fields = mutableListOf<String>()
        if (c.startTime != null) fields.add("\"startTime\"")
        if (c.endTime != null) fields.add("\"endTime\"")
        if (c.durationSeconds != null) fields.add("\"durationSeconds\"")
        if (c.note != null) fields.add("\"note\"")
        if (c.title != null) fields.add("\"title\"")
        if (c.area != null) fields.add("\"area\"")
        return "[${fields.joinToString(",")}]"
    }
}
