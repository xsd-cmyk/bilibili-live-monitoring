package com.example.bilimonitor.data.repository

import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.domain.policy.StateConfirmationPolicy
import com.example.bilimonitor.domain.policy.StreamerMonitorContext
import com.example.bilimonitor.domain.model.ApplyObservationResult
import com.example.bilimonitor.domain.model.MonitoringLease
import com.example.bilimonitor.domain.model.StreamerObservation
import com.example.bilimonitor.domain.model.RemoteLiveRoom
import com.example.bilimonitor.domain.model.DetectedTransition
import com.example.bilimonitor.data.local.entity.TagEntity
import com.example.bilimonitor.data.local.entity.GroupEntity
import com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity
import com.example.bilimonitor.data.local.entity.StreamerGroupCrossRefEntity
import com.example.bilimonitor.data.local.entity.LiveSessionTitleEntity
import com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity
import com.example.bilimonitor.data.local.FollowImportStatus
import com.example.bilimonitor.data.local.IntervalStatus
import com.example.bilimonitor.data.local.RestoreRunStatus
import com.example.bilimonitor.data.local.StatisticsEligibility
import com.example.bilimonitor.data.local.entity.AuthSessionEntity
import com.example.bilimonitor.data.local.entity.FollowImportStagingEntity
import com.example.bilimonitor.data.local.entity.FollowImportTaskEntity
import com.example.bilimonitor.data.local.entity.ReliableMonitorIntervalEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import com.example.bilimonitor.background.MonitoringEngine
import com.example.bilimonitor.data.local.BatchResponseValidity
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.RestoreConflictAction
import com.example.bilimonitor.data.local.TransitionReason
import com.example.bilimonitor.data.local.entity.StreamerMonitorPolicyEntity
import com.example.bilimonitor.data.remote.bilibili.BiliLiveDataSource
import com.example.bilimonitor.domain.model.BatchRoomStatusResponse
import com.example.bilimonitor.domain.model.BatchStatusOutcome
import com.example.bilimonitor.domain.policy.BanProbeOutcome
import com.example.bilimonitor.ui.stats.StreamerStatsViewModel
import android.database.Cursor
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.LiveEventType
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.db.AppDatabaseFactory
import com.example.bilimonitor.data.local.entity.LiveEventEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.room.withTransaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通知合并阈值的**行为**验证（真 Room 库 + 真实事务路径）—— 开播与下播两个方向都覆盖。
 *
 * ## 为什么要 instrumented
 * 这次改动改的是"聚合窗口什么时候定案"：单测能钉住判定规则（见
 * [com.example.bilimonitor.domain.policy.NotificationAggregationPolicyTest]），
 * 契约测试能钉住调用点写法，但**"窗口中途不得提前冻结、窗口关闭时一条通知含全部主播"
 * 只有在真库上跑一遍才敢说成立** —— 涉及 `notification_aggregate`、
 * `notification_aggregate_event`、`notification_outbox` 三张表与唯一索引/CAS。
 *
 * ## 被修掉的旧行为（本测试的第一条断言就是它）
 * 旧实现在窗口内计数**达到**阈值时就冻结窗口并创建批次，于是"阈值 4 + 一个 5 秒窗口里
 * 6 位主播开播"会变成两条通知（4 位 + 2 位单发）；用户要的是**一条含全部 6 位**。
 */
@RunWith(AndroidJUnit4::class)
class NotificationAggregationInstrumentedTest {

    private lateinit var db: AppDatabase
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "aggregation_test.db"

    /** 可控时钟：窗口是否到期由测试自己推进，不依赖真实时间。 */
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L
    private var boot = "test-boot"

    private val clock = object : AppClock {
        override fun nowWall(): Long = wall
        override fun nowElapsed(): Long = elapsed
        override fun bootId(): String = boot
        override fun currentTimeZone(): String = "Asia/Shanghai"
    }

    private lateinit var repository: NotificationRepository

    private fun auditMonitor() = MonitorRepository(db, db.streamerDao(), db.liveSessionDao(), db.liveEventDao(),
        db.statusHistoryDao(), db.reliableIntervalDao(), db.monitoringGapDao(), db.liveSessionTitleDao(),
        db.liveSessionAreaDao(), db.configDao(), db.logDao(), clock)

    private fun auditHistory() = HistoryRepository(db, db.liveSessionDao(), db.liveSessionTitleDao(),
        db.liveEventDao(), db.streamerDao(), db.logDao(), clock)

    private fun auditBackup(scope: kotlinx.coroutines.CoroutineScope): BackupRepository {
        val locks = RuntimeLockRepository(db.runtimeLockDao(), clock)
        val quiet = QuietHoursRepository(context)
        val config = ConfigRepository(db, db.configDao(), db.policyDao(), db.logDao(), clock, quiet)
        return BackupRepository(context, db, db.restoreDao(), db.logDao(), locks, config, quiet,
            MaintenanceRepository(context, db, locks, db.logDao(), clock, db.problemDao(), scope),
            ImportSettingsRepository(context), NotificationSettingsRepository(context), clock)
    }

    private suspend fun auditApply(row: StreamerEntity, from: ConfirmedLiveStatus, reason: TransitionReason, lease: MonitoringLease): ApplyObservationResult {
        val sequence = row.observationSequence + 1
        return auditMonitor().applyConfirmedObservationAtomically(null,
            DetectedTransition(row.stableId, from, ConfirmedLiveStatus.LIVE, "", sequence, row.streamerMonitorGeneration, reason),
            StreamerObservation(row.stableId, row.uid, ObservationResult.SUCCESS, wall), sequence,
            lease.monitorGeneration, lease.fencingToken, 1L, policy(4))
    }

    @Test fun auditNotePreservesAreaTimeline() = runBlocking {
        val event = seedEvent(71)
        seedSession(event)
        val stable = event.sessionStableId!!
        val row = db.liveSessionDao().findByStableId(stable)!!
        db.liveSessionDao().updateAreaAtStart(row.id, "A", wall)
        val areas = db.liveSessionAreaDao()
        for ((offset, label) in listOf("A", "B").withIndex()) areas.insert(LiveSessionAreaEntity(stable, label, null, label, wall + offset))
        assertEquals(CorrectionResult.Applied, auditHistory().applyManualCorrection(ManualCorrection(stable, null, null, null, "only-note", row.titleAtStart, "A")))
        assertEquals(listOf("A", "B"), areas.listForSession(stable).map { it.areaLabel })
        val beforeClear = areas.listForSession(stable)
        assertEquals(listOf(wall, wall + 1), beforeClear.map { it.observedAt })
        assertEquals(CorrectionResult.Applied, auditHistory().applyManualCorrection(ManualCorrection(stable, null, null, null, "", row.titleAtStart, "A")))
        assertEquals(beforeClear, areas.listForSession(stable))
    }

    @Test fun auditPausedGenerationRejectsOldResponse() = runBlocking {
        val event = seedEvent(72)
        val dao = db.streamerDao()
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET confirmedLiveStatus='OFFLINE' WHERE id=?", arrayOf(event.streamerId))
        val captured = dao.findById(event.streamerId)!!
        val lease = RuntimeLockRepository(db.runtimeLockDao(), clock).acquireLease()!!
        for (enabled in listOf(false, true)) db.withTransaction {
            dao.setMonitoringEnabled(captured.id, enabled, wall)
            dao.bumpStreamerMonitorGeneration(captured.id, wall)
            dao.deletePendingTransition(captured.id)
        }
        auditApply(captured, ConfirmedLiveStatus.OFFLINE, TransitionReason.CONFIRMATION_COUNT_REACHED, lease)
        val stale = auditMonitor().applyObservationOnly(StreamerObservation(captured.stableId, captured.uid, ObservationResult.NETWORK_ERROR, wall),
            null, null, captured.observationSequence + 1, lease.monitorGeneration, lease.fencingToken, requestGeneration = captured.streamerMonitorGeneration)
        org.junit.Assert.assertFalse(stale is ApplyObservationResult.Applied)
        assertEquals(false, auditMonitor().applyBanState(captured, lease, com.example.bilimonitor.domain.policy.RoomBanPolicy.nextState(null,
            com.example.bilimonitor.domain.policy.RoomBanProbeResult(true), wall, 1800), 60) { error("越代探针执行了副作用") })
        assertEquals(captured.observationSequence, dao.findById(captured.id)!!.observationSequence)
        val current = dao.findById(captured.id)!!
        assertEquals("status/sessions/generation", "OFFLINE/0/3", "${current.confirmedLiveStatus}/${db.liveSessionDao().listAll().size}/${current.streamerMonitorGeneration}")
    }

    @Test fun auditEngineCannotAdoptGenerationChangedDuringFetch() = runBlocking {
        val event = seedEvent(143)
        val dao = db.streamerDao()
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET confirmedLiveStatus='OFFLINE' WHERE id=?", arrayOf(event.streamerId))
        val captured = dao.findById(event.streamerId)!!
        var fetched = false
        val source = object : BiliLiveDataSource {
            override suspend fun getRoomStatus(uidList: List<Long>): BatchStatusOutcome {
                fetched = true
                assertEquals(listOf(captured.uid), uidList)
                db.withTransaction {
                    for (enabled in listOf(false, true)) {
                        dao.setMonitoringEnabled(captured.id, enabled, wall)
                        dao.bumpStreamerMonitorGeneration(captured.id, wall)
                    }
                }
                val room = RemoteLiveRoom(captured.uid, captured.roomId, null, null, null, null, null, null, null, null, ConfirmedLiveStatus.LIVE, true)
                return BatchStatusOutcome.Responded(BatchRoomStatusResponse(
                    uidList, mapOf(captured.uid to room), emptySet(), emptySet(), BatchResponseValidity.VALID))
            }
            override suspend fun getRoomDetailByRoomId(roomId: Long): RemoteLiveRoom? = error("无需额外请求")
            override suspend fun probeBan(roomId: Long): BanProbeOutcome = error("无需探针")
        }
        val config = ConfigRepository(db, db.configDao(), db.policyDao(), db.logDao(), clock, QuietHoursRepository(context))
        val engine = MonitoringEngine(config, dao, source, auditMonitor(), repository,
            RuntimeLockRepository(db.runtimeLockDao(), clock), db.monitoringGapDao(), db.reliableIntervalDao(), db.problemDao(), db.logDao(), db, clock, this)
        engine.checkOnce("audit")
        org.junit.Assert.assertTrue(fetched)
        val current = dao.findById(captured.id)!!
        assertEquals(ConfirmedLiveStatus.OFFLINE, current.confirmedLiveStatus)
        assertEquals(3L, current.streamerMonitorGeneration)
        assertEquals(captured.observationSequence, current.observationSequence)
        assertEquals(0, db.liveSessionDao().listAll().size)
    }

    @Test fun auditFreshSessionReferencesInterval() = runBlocking {
        val event = seedEvent(73)
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET confirmedLiveStatus='UNKNOWN' WHERE id=?", arrayOf(event.streamerId))
        val captured = db.streamerDao().findById(event.streamerId)!!
        auditApply(captured, ConfirmedLiveStatus.UNKNOWN, TransitionReason.FIRST_OBSERVATION, RuntimeLockRepository(db.runtimeLockDao(), clock).acquireLease()!!)
        val created = db.liveSessionDao().findActiveOpenSession(captured.id)!!
        val interval = db.reliableIntervalDao().findActiveByStreamer(captured.stableId)!!
        assertEquals(created.stableId, interval.sessionStableId)
        assertEquals("new session references the actual interval", interval.intervalId, created.currentReliableIntervalId)
    }

    @Test fun auditRecoveredSessionOwnsEvent() = runBlocking {
        val event = seedEvent(74)
        val captured = db.streamerDao().findById(event.streamerId)!!
        auditApply(captured, ConfirmedLiveStatus.LIVE, TransitionReason.RECOVERY_RECONFIRM, RuntimeLockRepository(db.runtimeLockDao(), clock).acquireLease()!!)
        val session = db.liveSessionDao().findActiveOpenSession(captured.id)!!
        val interval = db.reliableIntervalDao().findActiveByStreamer(captured.stableId)!!
        val recovery = db.liveEventDao().listAll().single { it.eventType == LiveEventType.LIVE_RECONFIRMED }
        assertEquals(listOf(session.stableId, session.stableId), listOf(interval.sessionStableId, recovery.sessionStableId))
        assertEquals(interval.intervalId, session.currentReliableIntervalId)
        closeWindow()
        val queued = db.notificationOutboxDao().findDue(wall, 10).single()
        assertEquals(session.stableId, NotificationPayloads.decodeLive(queued.payloadJson)!!.sessionStableId)
    }

    @Test fun auditSkipDoesNotChangeAnotherUidPolicy() = runBlocking {
        val event = seedEvent(75)
        seedSession(event)
        val session = event.sessionStableId!!
        val taxonomy = db.taxonomyDao()
        val tag = taxonomy.insertTag(TagEntity(stableId = "audit-tag", name = "组内标签", colorHex = null, sortOrder = 1))
        val group = taxonomy.insertGroup(GroupEntity(stableId = "audit-group", name = "分组", sortOrder = 1, collapsed = false))
        taxonomy.assignTag(StreamerTagCrossRefEntity(event.streamerId, tag))
        taxonomy.assignGroup(StreamerGroupCrossRefEntity(event.streamerId, group))
        db.liveSessionTitleDao().insert(LiveSessionTitleEntity(session, "备份标题", wall))
        db.liveSessionAreaDao().insert(LiveSessionAreaEntity(session, "A", null, "A", wall))
        assertEquals(CorrectionResult.Applied, auditHistory().applyManualCorrection(ManualCorrection(session, null, null, null, "备份备注")))
        val settings = StreamerMonitorPolicyEntity(streamerId = event.streamerId, enabled = true, intervalSeconds = null, notifyStart = true, notifyEnd = true, notifyRound = false, startConfirmationCount = null, endConfirmationCount = null, aggregationEnabledOverride = null, aggregationThresholdOverride = null, overrideQuietHours = null, updatedAt = wall)
        db.policyDao().upsert(settings)
        val backup = auditBackup(this)
        val text = backup.serializeToString(backup.serializeBackup())
        for (table in listOf("streamer_tag_cross_ref", "streamer_group_cross_ref", "live_session_title", "live_session_area", "live_session_correction", "live_event")) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM $table")
        }
        db.streamerDao().updateIdentity(event.streamerId, 99075, wall)
        db.policyDao().upsert(settings.copy(notifyStart = false))
        val ready = backup.precheck(text) as BackupRepository.PrecheckResult.Ready
        assertEquals(1, ready.conflicts.size)
        backup.decideAll(ready.restoreRunId, RestoreConflictAction.SKIP)
        assertEquals(1L, backup.applyRestore(ready.restoreRunId, text).skippedCounts["streamers"])
        assertEquals("policy on skipped local UID", false, db.policyDao().get(event.streamerId)!!.notifyStart)
        assertEquals(0, taxonomy.listTagRefs().size + taxonomy.listGroupRefs().size)
        assertEquals(0, db.liveEventDao().listAll().size + db.liveSessionCorrectionDao().listAll().size)
        assertEquals(0, db.liveSessionTitleDao().listAll().size + db.liveSessionAreaDao().listAll().size)
    }

    @Test fun auditUndecidedRestoreDoesNotCommit() = runBlocking {
        val event = seedEvent(76)
        val backup = auditBackup(this)
        val text = backup.serializeToString(backup.serializeBackup())
        db.streamerDao().updateIdentity(event.streamerId, 99076, wall)
        val ready = backup.precheck(text) as BackupRepository.PrecheckResult.Ready
        assertEquals(1, ready.conflicts.size)
        runCatching { backup.applyRestore(ready.restoreRunId, text) }
        assertEquals(RestoreRunStatus.WAITING_DECISIONS, db.restoreDao().getRun(ready.restoreRunId)!!.status)
    }

    @Test fun auditCancelledRestoreStaysCancelled() = runBlocking {
        seedEvent(77)
        val backup = auditBackup(this)
        val text = backup.serializeToString(backup.serializeBackup())
        val ready = backup.precheck(text) as BackupRepository.PrecheckResult.Ready
        backup.cancelRun(ready.restoreRunId)
        runCatching { backup.applyRestore(ready.restoreRunId, text) }
        assertEquals(RestoreRunStatus.CANCELLED, db.restoreDao().getRun(ready.restoreRunId)!!.status)
    }

    @Test fun startupDoesNotTreatMissingOrConflictingSettingsAsFresh() = runBlocking {
        fun config() = ConfigRepository(db, db.configDao(), db.policyDao(), db.logDao(), clock, QuietHoursRepository(context))
        val chosen = com.example.bilimonitor.data.local.BackgroundKeepAliveChoice.FOREGROUND_NOTIFICATION
        assertEquals(com.example.bilimonitor.data.local.BackgroundKeepAliveChoice.UNSET, config().startupChoice())
        assertEquals(true, config().completeOnboarding(chosen, false))
        assertEquals(chosen, config().startupChoice())
        db.openHelper.writableDatabase.execSQL("UPDATE monitoring_config SET backgroundKeepAliveChoice='UNSET'")
        assertEquals(true, runCatching { config().startupChoice() }.exceptionOrNull() is IllegalStateException)
        db.openHelper.writableDatabase.execSQL("DELETE FROM monitoring_config")
        db.close()
        db = AppDatabaseFactory.createForTest(context, dbName)
        assertEquals(true, runCatching { config().startupChoice() }.exceptionOrNull() is IllegalStateException)
        assertEquals(null, db.configDao().getConfig())
    }

    @Test fun legacyBanHistorySurvivesBackupRestoreWithoutDuplication() = runBlocking {
        val event = seedEvent(86)
        val row = com.example.bilimonitor.data.local.entity.RoomBanHistoryEntity(
            "room-ban:${event.streamerStableId}:100", event.streamerId, null, 100, 120, null, 8123)
        db.roomBanHistoryDao().insert(row)
        val backup = auditBackup(this)
        val file = backup.serializeBackup()
        assertEquals(1L, file.manifest.recordCounts["roomBans"])
        val text = backup.serializeToString(file)
        db.openHelper.writableDatabase.execSQL("DELETE FROM room_ban_history")
        repeat(2) {
            val ready = backup.precheck(text) as BackupRepository.PrecheckResult.Ready
            backup.applyRestore(ready.restoreRunId, text)
            assertEquals(listOf(row), db.roomBanHistoryDao().listAll())
        }
    }

    @Test fun auditAvailableSchemaMatrix() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val folder = AppDatabase::class.java.name
        val failures = mutableListOf<String>()
        val variants = (2 until AppDatabase.DB_VERSION).map { it to false } + (13 to true)
        for ((version, legacyBanSchema) in variants) {
            val filename = "audit_from_${version}_${legacyBanSchema}.db"
            try {
                context.deleteDatabase(filename)
                val source = if (legacyBanSchema) 12 else version
                val json = assets.open("$folder/$source.json").bufferedReader().use { org.json.JSONObject(it.readText()).getJSONObject("database") }
                android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(filename), null).use { old ->
                    val entities = json.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val definition = entities.getJSONObject(index)
                        val name = definition.getString("tableName")
                        old.execSQL(definition.getString("createSql").replace("\u0024{TABLE_NAME}", name))
                        val indices = definition.getJSONArray("indices")
                        for (offset in 0 until indices.length()) old.execSQL(indices.getJSONObject(offset).getString("createSql").replace("\u0024{TABLE_NAME}", name))
                    }
                    if (legacyBanSchema) {
                        old.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
                        old.execSQL("INSERT INTO room_master_table VALUES(42,'4035387a29fe3ce5f206f5c5662ea3e6')")
                        old.execSQL("""CREATE TABLE room_ban_history(banId TEXT NOT NULL PRIMARY KEY, streamerId INTEGER NOT NULL REFERENCES streamer(id),
                            sessionStableId TEXT, detectedAt INTEGER NOT NULL, liftedAt INTEGER, lockTill INTEGER, roomId INTEGER)""")
                    }
                    if (version == 15) old.execSQL("CREATE UNIQUE INDEX idx_live_session_one_active_open ON live_session(streamerId) WHERE endTime IS NULL AND lifecycleState = 'ACTIVE'")
                    old.version = version
                }
                val upgraded = AppDatabaseFactory.createForTest(context, filename)
                try {
                    val sql = upgraded.openHelper.writableDatabase
                    assertEquals(AppDatabase.DB_VERSION, sql.version)
                    sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                    sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND sql LIKE '% WHERE %'").use { it.moveToFirst(); assertEquals(16, it.getInt(0)) }
                } finally { upgraded.close() }
            } catch (error: Throwable) { failures.add("v$version:${error.message?.take(120)}") }
            finally { context.deleteDatabase(filename) }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun auditFreshObservationAfterWallRollback() = runBlocking {
        val event = seedEvent(79)
        seedSession(event)
        val row = createSingleFor(event, NotificationEventType.START_CONFIRMED)
        withDeliveryGuard { guard ->
            assertEquals("Ready", guard.prepare(row).javaClass.simpleName)
            elapsed += 10_000
            wall -= 3_600_000
            db.openHelper.writableDatabase.execSQL("UPDATE streamer SET lastConfirmedAt=? WHERE id=?", arrayOf(wall, event.streamerId))
            assertEquals("a fresh confirmation must release a still-valid notification", "Ready", guard.prepare(row).javaClass.simpleName)
        }
    }

    @Test fun auditManualDurationIsNotMonitoredCoverage() = runBlocking {
        val event = seedEvent(80)
        assertEquals(CorrectionResult.Applied, auditHistory().createManualSession(event.streamerStableId, wall, wall + 3_600_000L, null, null, null))
        val series = StatsSeriesRepository(db.liveSessionDao(), db.streamerDao(), clock)
        val day = series.allBucketValues(event.streamerId, StatsGranularity.DAY).single()
        val totals = series.seriesFor(event.streamerId, StatsGranularity.DAY, day).totals
        assertEquals(3600L, totals.monitoredSeconds)
        assertEquals(0L, totals.reliableMonitoredSeconds)
    }

    @Test fun auditGapEndsAtLastKnownLiveWithTwoConfirmations() = runBlocking {
        val event = seedEvent(83)
        seedSession(event)
        val streamer = db.streamerDao().findById(event.streamerId)!!
        val lease = RuntimeLockRepository(db.runtimeLockDao(), clock).acquireLease()!!
        val monitor = auditMonitor()
        auditApply(streamer, ConfirmedLiveStatus.LIVE, TransitionReason.RECOVERY_RECONFIRM, lease)
        suspend fun observe(result: ObservationResult, status: ConfirmedLiveStatus?, counting: StateConfirmationPolicy.Decision.Counting?, sequence: Long) {
            val remote = status?.let { RemoteLiveRoom(streamer.uid, streamer.roomId, null, null, null, null, null, null, null, null, it, true) }
            monitor.applyObservationOnly(StreamerObservation(streamer.stableId, streamer.uid, result, wall), remote, counting, sequence, lease.monitorGeneration, lease.fencingToken, policy(4), 1L, expectedIntervalSeconds = 60, requestGeneration = streamer.streamerMonitorGeneration)
        }
        wall += 60_000; elapsed += 60_000
        observe(ObservationResult.SUCCESS, ConfirmedLiveStatus.LIVE, null, 3)
        val lastLive = wall
        wall += 60_000; elapsed += 60_000
        observe(ObservationResult.NETWORK_ERROR, null, null, 4)
        org.junit.Assert.assertNull(db.reliableIntervalDao().findActiveByStreamer(streamer.stableId))
        wall += 4 * 3_600_000; elapsed += 4 * 3_600_000
        observe(ObservationResult.SUCCESS, ConfirmedLiveStatus.OFFLINE, StateConfirmationPolicy.Decision.Counting(PendingTransition.SUSPECTED_OFFLINE, 1), 5)
        wall += 60_000; elapsed += 60_000
        monitor.applyConfirmedObservationAtomically(null, DetectedTransition(streamer.stableId, ConfirmedLiveStatus.LIVE, ConfirmedLiveStatus.OFFLINE, "", 6, streamer.streamerMonitorGeneration, TransitionReason.CONFIRMATION_COUNT_REACHED), StreamerObservation(streamer.stableId, streamer.uid, ObservationResult.SUCCESS, wall), 6, lease.monitorGeneration, lease.fencingToken, 1L, policy(4), expectedIntervalSeconds = 60)
        val closed = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
        assertEquals("seconds added after the last observed LIVE", 0L, (closed.endTime!! - lastLive) / 1000)
    }

    private suspend fun auditTick(stable: String, state: ConfirmedLiveStatus?, threshold: Int, lease: MonitoringLease) {
        val row = db.streamerDao().findByStableId(stable)!!
        val pending = db.streamerDao().getPendingTransition(row.id)
        val result = if (state == null) ObservationResult.NETWORK_ERROR else ObservationResult.SUCCESS
        val remote = state?.let { RemoteLiveRoom(row.uid, row.roomId, null, null, null, null, null, null, null, null, it, true) }
        val transition = pending?.pendingTransition ?: PendingTransition.NONE
        val context = StreamerMonitorContext(confirmedLiveStatus = row.confirmedLiveStatus, pendingTransition = transition,
            pendingConfirmationCount = if (transition == PendingTransition.SUSPECTED_OFFLINE) pending!!.confirmationCount else 0,
            hasActiveReliableInterval = db.reliableIntervalDao().findActiveByStreamer(stable) != null,
            startConfirmationCount = 1, endConfirmationCount = threshold)
        val sequence = row.observationSequence + 1
        val observation = StreamerObservation(stable, row.uid, result, wall)
        val monitor = auditMonitor()
        val decision = StateConfirmationPolicy.detect(context, result, remote)
        val applied = if (decision is StateConfirmationPolicy.Decision.Transition) {
            monitor.applyConfirmedObservationAtomically(remote, DetectedTransition(stable, decision.from, decision.to, "", sequence,
                row.streamerMonitorGeneration, decision.reason), observation, sequence, lease.monitorGeneration, lease.fencingToken, 1L, policy(4), 60)
        } else monitor.applyObservationOnly(observation, remote, decision as? StateConfirmationPolicy.Decision.Counting, sequence,
            lease.monitorGeneration, lease.fencingToken, policy(4), 1L, expectedIntervalSeconds = 60, requestGeneration = row.streamerMonitorGeneration)
        org.junit.Assert.assertTrue(applied is ApplyObservationResult.Applied)
    }

    @Test fun auditClosureThresholdsWithAndWithoutGaps() = runBlocking {
        val lease = RuntimeLockRepository(db.runtimeLockDao(), clock).acquireLease(7 * 86_400_000L)!!
        fun advance(ms: Long) { wall += ms; elapsed += ms }
        for (threshold in 1..3) for (gap in 0..2) {
            val event = seedEvent(100 + threshold * 3 + gap)
            seedSession(event)
            val stable = event.streamerStableId
            auditTick(stable, ConfirmedLiveStatus.LIVE, threshold, lease)
            advance(60_000)
            auditTick(stable, ConfirmedLiveStatus.LIVE, threshold, lease)
            val lastLive = wall
            if (gap == 2) {
                if (threshold > 1) { advance(60_000); auditTick(stable, ConfirmedLiveStatus.OFFLINE, threshold, lease) }
                advance(60_000); auditTick(stable, null, threshold, lease)
                assertEquals(0, db.streamerDao().getPendingTransition(event.streamerId)?.confirmationCount ?: 0)
            }
            if (gap > 0) advance(4 * 3_600_000)
            repeat(threshold) { advance(60_000); auditTick(stable, ConfirmedLiveStatus.OFFLINE, threshold, lease) }
            val closed = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
            assertEquals("threshold=$threshold gap=$gap", if (gap == 0) wall else lastLive, closed.endTime)
            assertEquals(wall, closed.confirmedOfflineAt)
        }
    }

    @Test fun auditRealConfirmationUsesElapsedUntilReboot() = runBlocking {
        val event = seedEvent(134)
        seedSession(event)
        val lease = RuntimeLockRepository(db.runtimeLockDao(), clock).acquireLease()!!
        val queued = createSingleFor(event, NotificationEventType.START_CONFIRMED)
        withDeliveryGuard { guard ->
            wall -= 3_600_000; elapsed += 10_000
            auditTick(event.streamerStableId, ConfirmedLiveStatus.LIVE, 2, lease)
            assertEquals("Ready", guard.prepare(queued).javaClass.simpleName)
            wall += 86_400_000; elapsed += 1_000
            assertEquals("Ready", guard.prepare(queued).javaClass.simpleName)
            wall -= 86_400_000; boot = "second-boot"; elapsed = 2_000
            assertEquals("AwaitObservation", guard.prepare(queued).javaClass.simpleName)
            auditTick(event.streamerStableId, ConfirmedLiveStatus.LIVE, 2, lease)
            assertEquals("Ready", guard.prepare(queued).javaClass.simpleName)
        }
    }

    @Test fun auditLogoutWinsBeforeImportAndTerminalCannotReplay() = runBlocking {
        val auth = db.authSessionDao()
        val tasks = db.followImportDao()
        suspend fun preview(id: String, generation: Long, uid: Long) {
            tasks.insertTask(FollowImportTaskEntity(id, generation, FollowImportStatus.PREVIEW, wall, null, 1, null))
            tasks.insertStaging(listOf(FollowImportStagingEntity(id, uid, generation, """{"uid":$uid,"name":"测试"}""", wall)))
        }
        auth.insert(AuthSessionEntity("generation-1", 1, null, wall, null))
        preview("before-logout", 1, 10101)
        val holding = CompletableDeferred<Unit>()
        val finishLogout = CompletableDeferred<Unit>()
        val logout = launch {
            db.withTransaction {
                auth.insert(AuthSessionEntity("logout", 2, null, wall, wall))
                auth.revokeAll(wall)
                holding.complete(Unit)
                finishLogout.await()
            }
        }
        holding.await()
        val waitingImport = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            commitFollowPreview(db, clock, "before-logout", setOf(10101))
        }
        finishLogout.complete(Unit); logout.join()
        assertEquals(FollowImportResult.AccountChanged, waitingImport.await())
        assertEquals(FollowImportStatus.CANCELLED, tasks.getTask("before-logout")!!.status)
        org.junit.Assert.assertNull(db.streamerDao().findByUid(10101))
        auth.insert(AuthSessionEntity("generation-3", 3, null, wall, null))
        preview("active", 3, 10103)
        assertEquals(FollowImportResult.Applied(1, 0, 0), commitFollowPreview(db, clock, "active", setOf(10103)))
        org.junit.Assert.assertTrue(runCatching { commitFollowPreview(db, clock, "active", setOf(10103)) }.isFailure)
        assertEquals(FollowImportStatus.COMPLETED, tasks.getTask("active")!!.status)
    }

    @Test fun auditAdoptedIdentityRemapsExistingHistoryAndOutbox() = runBlocking {
        val event = seedEvent(147)
        seedSession(event)
        val backup = auditBackup(this)
        val text = backup.serializeToString(backup.serializeBackup())
        val localStable = "local-before-restore"
        db.streamerDao().updateStableId(event.streamerId, localStable, wall)
        db.openHelper.writableDatabase.execSQL("UPDATE live_event SET streamerStableId=? WHERE eventId=?", arrayOf(localStable, event.eventId))
        val queued = createSingleFor(event.copy(streamerStableId = localStable), NotificationEventType.START_CONFIRMED)
        val ready = backup.precheck(text) as BackupRepository.PrecheckResult.Ready
        assertEquals(1, ready.conflicts.size)
        backup.decideAll(ready.restoreRunId, RestoreConflictAction.USE_BACKUP)
        backup.applyRestore(ready.restoreRunId, text)
        assertEquals(event.streamerStableId, db.streamerDao().findById(event.streamerId)!!.stableId)
        assertEquals(event.streamerStableId, db.liveEventDao().findById(event.eventId)!!.streamerStableId)
        val rewritten = db.notificationOutboxDao().findById(queued.outboxId)!!
        assertEquals(event.streamerStableId, NotificationPayloads.decodeLive(rewritten.payloadJson)!!.streamerStableId)
    }

    @Test fun auditExportSeparatesManualDurationFromCoverage() = runBlocking {
        val event = seedEvent(148)
        assertEquals(CorrectionResult.Applied, auditHistory().createManualSession(event.streamerStableId, wall, wall + 3_600_000, null, "审查场次", null))
        val series = StatsSeriesRepository(db.liveSessionDao(), db.streamerDao(), clock)
        val exporter = ExportRepository(context, db, RuntimeLockRepository(db.runtimeLockDao(), clock), series, db.logDao(), clock)
        val store = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        fun readAndRemove(report: ExportRepository.ExportResult): String {
            assertEquals(1, report.sessionCount)
            val resolver = context.contentResolver
            val id = resolver.query(store, arrayOf("_id"), "_display_name = ?", arrayOf(report.fileName), "_id DESC")!!.use {
                org.junit.Assert.assertTrue(it.moveToFirst()); it.getLong(0)
            }
            val uri = android.content.ContentUris.withAppendedId(store, id)
            return try { resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }
            finally { resolver.delete(uri, null, null) }
        }
        val ids = listOf(event.streamerId)
        val history = readAndRemove(exporter.exportHistory(ids, wall, wall + 86_400_000, "验收").getOrThrow())
        val stats = readAndRemove(exporter.exportStats(ids, wall, wall + 86_400_000, "验收").getOrThrow())
        org.junit.Assert.assertTrue(history.contains("审查场次"))
        org.junit.Assert.assertTrue(stats.contains("1小时0分") && stats.contains("可靠监控时长：0分0秒"))
    }

    @Test fun auditRestoreHasExactlyOneWinner() = runBlocking {
        seedEvent(145)
        val backup = auditBackup(this)
        val text = backup.serializeToString(backup.serializeBackup())
        val run = (backup.precheck(text) as BackupRepository.PrecheckResult.Ready).restoreRunId
        val results = List(2) { async { runCatching { backup.applyRestore(run, text) } } }.map { it.await() }
        assertEquals(1, results.count { it.isSuccess })
        backup.cancelRun(run)
        assertEquals(RestoreRunStatus.COMPLETED, db.restoreDao().getRun(run)!!.status)
        org.junit.Assert.assertTrue(runCatching { backup.applyRestore(run, text) }.isFailure)
    }

    @Test fun auditReliableSecondsIntersectAndUnifyRealIntervals() = runBlocking {
        val event = seedEvent(146)
        val start = wall
        assertEquals(CorrectionResult.Applied, auditHistory().createManualSession(event.streamerStableId, start, start + 100_000, null, null, null))
        val session = db.liveSessionDao().listAll().single()
        for ((index, bounds) in listOf(-10_000L to 30_000L, 20_000L to 50_000L, 90_000L to 150_000L).withIndex()) {
            db.reliableIntervalDao().insert(ReliableMonitorIntervalEntity("interval-$index", event.streamerStableId, session.stableId,
                start + bounds.first, start + bounds.second, null, 1, 1, 1, "test", IntervalStatus.CLOSED, start + bounds.second))
        }
        val series = StatsSeriesRepository(db.liveSessionDao(), db.streamerDao(), clock)
        val totals = series.seriesCustom(event.streamerId, start, start + 95_000).totals
        val stats = StatsRepository(db, db.streamerDao(), db.statisticsCacheDao(), db.statisticsSnapshotDao(), db.configDao(), clock)
        val cached = stats.compute(StatsQuery(start, start + 95_000, "UTC", StatisticsEligibility.INCLUDE_CORRECTED)).totals
        assertEquals(55L, totals.reliableMonitoredSeconds)
        assertEquals(55L, cached.reliableMonitoredSeconds)
        assertEquals(100L, totals.monitoredSeconds)
    }

    @Test fun auditStatsSelectsThisStreamersLatestDay() = runBlocking {
        val first = seedEvent(81)
        val second = seedEvent(82)
        val history = auditHistory()
        assertEquals(CorrectionResult.Applied, history.createManualSession(first.streamerStableId, wall, wall + 10_000, null, null, null))
        assertEquals(CorrectionResult.Applied, history.createManualSession(second.streamerStableId, wall + 3 * 86_400_000L, wall + 3 * 86_400_000L + 10_000, null, null, null))
        val series = StatsSeriesRepository(db.liveSessionDao(), db.streamerDao(), clock)
        val export = ExportRepository(context, db, RuntimeLockRepository(db.runtimeLockDao(), clock), series, db.logDao(), clock)
        val vm = StreamerStatsViewModel(series, export)
        vm.load(first.streamerId)
        val state = kotlinx.coroutines.withTimeout(5_000) { vm.state.first { !it.loading && it.result != null } }
        assertEquals("default period on a single streamer's stats screen", 1L, state.result!!.totals.sessionCount)
    }

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = AppDatabaseFactory.createForTest(context, dbName)
        repository = NotificationRepository(
            db,
            db.notificationAggregateDao(),
            db.notificationOutboxDao(),
            db.liveEventDao(),
            db.streamerDao(),
            clock,
            QuietHoursRepository(context)
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun 超过阈值时合并成一条含全部主播的通知_且窗口中途不得提前定案() = runBlocking {
        val threshold = 4
        val count = 6
        val policy = policy(threshold)

        repeat(count) { index ->
            val event = seedEvent(index)
            NotificationOutboxWriter.createForEvent(
                db = db, clock = clock, event = event, streamerId = event.streamerId,
                payloadJson = payloadOf(event),
                policy = policy, configVersion = 1L, now = clock.nowWall()
            )
        }

        // ★ 核心断言：窗口还没关闭时**一条通知都不该发** ——
        //   旧实现在第 4 位（达到阈值）时就冻结并发了批次，这一条会直接失败。
        assertEquals("窗口未关闭前不得出现合并通知（旧实现会在这里就发）", 0, batchCount())
        assertEquals("窗口未关闭前也不该有单事件通知", 0, singleCount())

        // 推进到窗口之外（窗口 5 秒），再走真实的窗口结算路径
        closeWindow()

        assertEquals("超过阈值 → 恰好一条合并通知", 1, batchCount())
        assertEquals("合并之后不该再逐条单发", 0, singleCount())
        val payload = NotificationPayloads.decodeBatch(batchPayload())
        assertEquals("合并通知里必须包含**全部** $count 位主播（不是只装阈值个）", count, payload?.items?.size)
    }

    @Test
    fun 正好等于阈值时逐个单独发() = runBlocking {
        val threshold = 4
        val policy = policy(threshold)
        repeat(threshold) { index ->
            val event = seedEvent(index)
            NotificationOutboxWriter.createForEvent(
                db = db, clock = clock, event = event, streamerId = event.streamerId,
                payloadJson = payloadOf(event),
                policy = policy, configVersion = 1L, now = clock.nowWall()
            )
        }
        elapsed += 10_000
        wall += 10_000
        repository.processWindowEnds()

        assertEquals("正好等于阈值 → 不合并", 0, batchCount())
        assertEquals("应当逐个单独发 $threshold 条", threshold, singleCount())
    }

    @Test
    fun 阈值一时两位主播就合并() = runBlocking {
        val policy = policy(1)
        repeat(2) { index ->
            val event = seedEvent(index)
            NotificationOutboxWriter.createForEvent(
                db = db, clock = clock, event = event, streamerId = event.streamerId,
                payloadJson = payloadOf(event),
                policy = policy, configVersion = 1L, now = clock.nowWall()
            )
        }
        elapsed += 10_000
        wall += 10_000
        repository.processWindowEnds()

        assertEquals("阈值 1 = 超过 1 位就合并 → 两位主播合成一条", 1, batchCount())
        assertEquals(0, singleCount())
        assertEquals(2, NotificationPayloads.decodeBatch(batchPayload())?.items?.size)
    }

    @Test
    fun 一位主播不合并() = runBlocking {
        val policy = policy(4)
        val event = seedEvent(0)
        NotificationOutboxWriter.createForEvent(
            db = db, clock = clock, event = event, streamerId = event.streamerId,
            payloadJson = payloadOf(event),
            policy = policy, configVersion = 1L, now = clock.nowWall()
        )
        elapsed += 10_000
        wall += 10_000
        repository.processWindowEnds()

        assertEquals("只有一位主播时不该合并", 0, batchCount())
        assertEquals("应当是单事件通知", 1, singleCount())
    }

    // ---------------------------------------------------------------- 下播方向（v11）

    @Test
    fun 超过阈值时把这一批下播合并成一条_且类型是下播() = runBlocking {
        val threshold = 4
        val count = 6
        val policy = policy(threshold)
        repeat(count) { index -> bindEnd(seedEndEvent(index), policy) }

        assertEquals("窗口未关闭前不得发出下播合并通知", 0, batchCount())
        closeWindow()

        assertEquals("超过阈值 → 恰好一条下播合并通知", 1, batchCount(eventType = "BATCH_END"))
        assertEquals("开播方向不该被牵动", 0, batchCount(eventType = "BATCH_LIVE"))
        assertEquals("合并之后不该再逐条单发", 0, singleCount())
        val payload = NotificationPayloads.decodeBatch(batchPayload(eventType = "BATCH_END"))
        assertEquals("必须包含全部 $count 位下播主播", count, payload?.items?.size)
        assertEquals(
            "payload 必须标记方向（渲染文案靠它区分「正在直播」与「已下播」）",
            "END", payload?.kind
        )
    }

    @Test
    fun 下播正好等于阈值时逐条单发() = runBlocking {
        val threshold = 4
        val policy = policy(threshold)
        repeat(threshold) { index -> bindEnd(seedEndEvent(index), policy) }

        closeWindow()

        assertEquals("正好等于阈值 → 不合并", 0, batchCount())
        assertEquals("应当逐个单独发 $threshold 条", threshold, singleCount())
    }

    @Test
    fun 关掉下播合并开关时仍然逐条单发() = runBlocking {
        val policy = policy(4).copy(endAggregationEnabled = false)
        repeat(6) { index -> bindEnd(seedEndEvent(index), policy) }

        closeWindow()

        assertEquals("开关关掉就不该有下播合并", 0, batchCount(eventType = "BATCH_END"))
        assertEquals("应当是 6 条单发", 6, singleCount())
    }

    @Test
    fun 开播与下播各自成窗_同一个突发里不会串成一条() = runBlocking {
        val threshold = 2
        val policy = policy(threshold)

        // 3 位主播"先开播、后下播"，两件事交错出现在同一个时间窗内（复用同一批主播与场次）
        repeat(3) { index ->
            val start = seedEvent(index)
            NotificationOutboxWriter.createForEvent(
                db = db, clock = clock, event = start, streamerId = start.streamerId,
                payloadJson = payloadOf(start), policy = policy, configVersion = 1L, now = clock.nowWall()
            )
            bindEnd(seedEndEventFor(start, index), policy)
        }
        closeWindow()

        // ★ 核心断言：两个方向各自一个窗口、各自一条通知；不会把"谁开播"和"谁下播"混在一起
        assertEquals("开播方向一条", 1, batchCount(eventType = "BATCH_LIVE"))
        assertEquals("下播方向一条", 1, batchCount(eventType = "BATCH_END"))
        assertEquals("两条通知各自 3 位（不是把 6 位混成一条）",
            3, NotificationPayloads.decodeBatch(batchPayload(eventType = "BATCH_LIVE"))?.items?.size)
        assertEquals(
            3, NotificationPayloads.decodeBatch(batchPayload(eventType = "BATCH_END"))?.items?.size
        )
        assertEquals("两个方向都合并了，不该有单发", 0, singleCount())
    }

    // ---------------------------------------------------------------- 造数据

    private fun policy(threshold: Int) = StreamerNotificationPolicy(
        notifyStart = true,
        notifyEnd = true,
        aggregationEnabled = true,
        aggregationThreshold = threshold,
        aggregationWindowSeconds = 5
    )

    /**
     * 给**已经存在的**开播事件配一个下播事件（同一个主播、同一场）。
     *
     * ★ 必须复用主播与场次：真实场景里 END 属于"刚下播的那个主播"，
     *   而且 `streamer.uid` 上有唯一约束 —— 重复建主播会直接 SQLITE_CONSTRAINT_UNIQUE
     *   （这条是测试自己踩出来的：一开始给同一个序号又建了一个主播）。
     */
    private suspend fun seedEndEventFor(start: LiveEventEntity, index: Int): LiveEventEntity {
        val end = LiveEventEntity(
            eventId = "evt-test-end-$index",
            streamerId = start.streamerId,
            streamerStableId = start.streamerStableId,
            sessionStableId = start.sessionStableId,
            eventType = LiveEventType.END,
            eventConfirmedAt = clock.nowWall(),
            eventSequence = 100L + index,
            observationSequence = 1L,
            monitorGeneration = 1L,
            streamerMonitorGeneration = 1L,
            fencingToken = null,
            transitionId = null,
            configVersion = 1L,
            createdAt = clock.nowWall()
        )
        db.liveEventDao().insert(end)
        return end
    }

    /** 造"独立主播的下播事件"（自己的主播 + 场次），供只看下播方向的用例使用。 */
    private suspend fun seedEndEvent(index: Int): LiveEventEntity =
        seedEndEventFor(seedEvent(index), index)

    /** 走真实写入路径把下播事件投进聚合窗口（与 MonitorRepository 的调用方式一致）。 */
    private suspend fun bindEnd(event: LiveEventEntity, policy: StreamerNotificationPolicy) {
        NotificationOutboxWriter.createForEvent(
            db = db, clock = clock, event = event, streamerId = event.streamerId,
            payloadJson = payloadOf(event),
            policy = policy, configVersion = 1L, now = clock.nowWall(),
            eventType = com.example.bilimonitor.data.local.NotificationEventType.END_CONFIRMED
        )
    }

    /** 推进到窗口之外，再走真实的窗口结算路径。 */
    private suspend fun closeWindow() {
        elapsed += 10_000
        wall += 10_000
        repository.processWindowEnds()
    }

    private suspend fun seedEvent(index: Int): LiveEventEntity {
        val streamerId = db.streamerDao().insert(
            StreamerEntity(
                stableId = "str-test-$index",
                uid = 10_000L + index,
                roomId = 100L + index,
                shortRoomId = null,
                name = "测试主播$index",
                nameLocked = false,
                avatarUrl = null,
                roomTitle = "房间标题$index",
                parentAreaName = null,
                areaName = "虚拟主播",
                coverUrl = null,
                liveUrl = "https://live.bilibili.com/${100 + index}",
                confirmedLiveStatus = ConfirmedLiveStatus.LIVE,
                lastObservationResult = ObservationResult.SUCCESS,
                observationSequence = 1L,
                streamerMonitorGeneration = 1L,
                monitoringEnabled = true,
                isFavorite = false,
                monitorPolicyStableId = null,
                lastCheckedAt = clock.nowWall(),
                lastConfirmedAt = clock.nowWall(),
                freshnessStatus = DataFreshness.FRESH,
                lastLiveStartedAt = clock.nowWall(),
                lastLiveEndedAt = null,
                lastError = null,
                deletedAt = null,
                createdAt = clock.nowWall(),
                updatedAt = clock.nowWall()
            )
        )
        val event = LiveEventEntity(
            eventId = "evt-test-$index",
            streamerId = streamerId,
            streamerStableId = "str-test-$index",
            sessionStableId = "ses-test-$index",
            eventType = LiveEventType.START,
            eventConfirmedAt = clock.nowWall(),
            eventSequence = index.toLong() + 1,
            observationSequence = 1L,
            monitorGeneration = 1L,
            streamerMonitorGeneration = 1L,
            fencingToken = null,
            transitionId = null,
            configVersion = 1L,
            createdAt = clock.nowWall()
        )
        db.liveEventDao().insert(event)
        return event
    }

    private fun payloadOf(event: LiveEventEntity): String = NotificationPayloads.live(
        eventKey = com.example.bilimonitor.core.Ids.eventKeyForEvent(event.eventId),
        streamerStableId = event.streamerStableId,
        sessionStableId = event.sessionStableId,
        eventId = event.eventId,
        name = "测试主播",
        title = "房间标题",
        area = "虚拟主播",
        url = null,
        cover = null
    )

    @Test
    fun 批量下播始终进入批次列表_不依赖仍活跃的绑定() = runBlocking {
        val p = policy(1)
        bindEnd(seedEndEvent(0), p)
        bindEnd(seedEndEvent(1), p)
        closeWindow()
        val row = db.notificationOutboxDao().findDue(wall, 20).single()
        val resolver = notificationResolver()
        org.junit.Assert.assertTrue(resolver.resolve(row.eventKey) is NotificationDeepLinkResolver.Target.Batch)
        db.notificationAggregateDao().releaseAll(row.aggregateId!!, wall)
        val details = resolver.batchDetails(row.eventKey)!!
        org.junit.Assert.assertTrue(details.isEnd)
        assertEquals(2, details.members.size)
    }

    @Test
    fun 下播通知携带场次_清理原事件后仍可用点击快照定位() = runBlocking {
        val event = seedEndEvent(0)
        val row = createSingleFor(event, com.example.bilimonitor.data.local.NotificationEventType.END_CONFIRMED)
        val hint = com.example.bilimonitor.notify.NotificationClickHint(row.eventKey, row.eventType,
            event.streamerStableId, event.sessionStableId).encode()
        val target = notificationResolver().resolve(row.eventKey) as NotificationDeepLinkResolver.Target.StreamerHistory
        assertEquals(event.sessionStableId, target.sessionStableId)
        db.notificationOutboxDao().deleteByOutboxIds(listOf(row.outboxId))
        db.openHelper.writableDatabase.execSQL("DELETE FROM live_event WHERE eventId = ?", arrayOf(event.eventId))
        val fallback = notificationResolver().resolve(row.eventKey, hint) as NotificationDeepLinkResolver.Target.StreamerHistory
        assertEquals(event.streamerId, fallback.streamerId)
        assertEquals(event.sessionStableId, fallback.sessionStableId)
    }

    @Test
    fun 不同事件即使哈希碰撞也拥有不同的PendingIntent() = runBlocking {
        val row = createSingleFor(seedEvent(0), com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val poster = com.example.bilimonitor.notify.NotificationPoster(context, clock)
        val a = poster.contentIntent(row.copy(eventKey = "Aa", notificationId = 4242), null)
        val b = poster.contentIntent(row.copy(eventKey = "BB", notificationId = 4242), null)
        try { org.junit.Assert.assertNotEquals(a, b) }
        finally { a.cancel(); b.cancel() }
    }

    @Test
    fun 调度依据最早到期时间_在途租约使用同次开机的单调时钟() = runBlocking {
        val row = createSingleFor(seedEvent(0), com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
        val dao = db.notificationOutboxDao()
        assertEquals(0L, dao.nextDeliveryDelay(wall, elapsed, clock.bootId()))
        dao.awaitObservation(row.outboxId, wall + 30_000)
        assertEquals(30_000L, dao.nextDeliveryDelay(wall, elapsed, clock.bootId()))
        wall += 30_000
        elapsed += 30_000
        assertEquals(1, dao.claimOutbox(row.outboxId, wall, wall + 30_000, elapsed + 30_000,
            clock.bootId(), "test-worker", "attempt"))
        assertEquals(30_000L, dao.nextDeliveryDelay(wall + 3_600_000, elapsed, clock.bootId()))
    }

    @Test
    fun 旧事件延后入队不能重新获得提醒有效期() = runBlocking {
        val event = seedEvent(0)
        db.openHelper.writableDatabase.execSQL("UPDATE live_event SET eventConfirmedAt = ? WHERE eventId = ?",
            arrayOf(wall - 25 * 60 * 60 * 1000L, event.eventId))
        val old = db.liveEventDao().findById(event.eventId)!!
        val row = createSingleFor(old, com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
        withDeliveryGuard { guard ->
            val result = guard.prepare(row) as com.example.bilimonitor.background.NotificationDeliveryGuard.Result.Skip
            assertEquals("NOTIFICATION_STALE", result.reason)
        }
    }

    @Test
    fun 场次已结束时不补发正在直播通知() = runBlocking {
        val event = seedEvent(0)
        seedSession(event)
        val row = createSingleFor(event, com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
        db.openHelper.writableDatabase.execSQL("UPDATE live_session SET endTime = ? WHERE stableId = ?",
            arrayOf(wall + 1000, event.sessionStableId))
        withDeliveryGuard { guard ->
            val result = guard.prepare(row) as com.example.bilimonitor.background.NotificationDeliveryGuard.Result.Skip
            assertEquals("STATE_SUPERSEDED", result.reason)
        }
    }

    @Test
    fun 批量发送前过滤失效成员_人数与载荷同步变化() = runBlocking {
        val p = policy(1)
        repeat(2) { index ->
            val event = seedEvent(index)
            seedSession(event)
            NotificationOutboxWriter.createForEvent(db, clock, event, event.streamerId, payloadOf(event), p, 1, wall)
        }
        closeWindow()
        val row = db.notificationOutboxDao().findDue(wall, 20).single()
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET confirmedLiveStatus = 'OFFLINE' WHERE stableId = 'str-test-0'")
        withDeliveryGuard { guard ->
            val ready = guard.prepare(row) as com.example.bilimonitor.background.NotificationDeliveryGuard.Result.Ready
            val payload = NotificationPayloads.decodeBatch(ready.row.payloadJson)!!
            assertEquals(1, payload.items.size)
            assertEquals("str-test-1", payload.items.single().streamerStableId)
        }
    }

    @Test
    fun 数据陈旧时等待正常检查_不把旧状态当成实时事实() = runBlocking {
        val event = seedEvent(0)
        seedSession(event)
        val row = createSingleFor(event, com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET lastConfirmedAt = ? WHERE id = ?",
            arrayOf(wall - 24 * 60 * 60 * 1000L, event.streamerId))
        withDeliveryGuard { guard ->
            org.junit.Assert.assertTrue(guard.prepare(row) is com.example.bilimonitor.background.NotificationDeliveryGuard.Result.AwaitObservation)
        }
    }

    @Test
    fun 用户点击或划去后_结果未知的通知不会再次入队() = runBlocking {
        repeat(2) { index ->
            val row = createSingleFor(seedEvent(index), com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
            recordPendingHistory(row)
            db.notificationOutboxDao().awaitObservation(row.outboxId, wall + 5000, "NOT_VISIBLE_AFTER_POST")
            com.example.bilimonitor.notify.NotificationInteractionRepository(db, clock).record(row.eventKey, index == 1)
            assertEquals(com.example.bilimonitor.data.local.NotificationOutboxStatus.SENT,
                db.notificationOutboxDao().findById(row.outboxId)?.status)
            assertEquals(if (index == 1) "USER_DISMISSED" else "USER_CLICKED",
                db.notificationHistoryDao().findByOutboxId(row.outboxId)?.reason)
        }
        assertEquals(0, db.notificationOutboxDao().findDue(wall + 6000, 20).size)
    }

    @Test
    fun 交互确认同时结算批次_不留下永久冻结的聚合() = runBlocking {
        val p = policy(1)
        repeat(2) { index ->
            val event = seedEvent(index)
            NotificationOutboxWriter.createForEvent(db, clock, event, event.streamerId, payloadOf(event), p, 1, wall)
        }
        closeWindow()
        val row = db.notificationOutboxDao().findDue(wall, 20).single()
        recordPendingHistory(row)
        com.example.bilimonitor.notify.NotificationInteractionRepository(db, clock).record(row.eventKey, false)
        assertEquals(com.example.bilimonitor.data.local.NotificationAggregateStatus.DISPATCHED,
            db.notificationAggregateDao().findById(row.aggregateId!!)?.status)
    }

    @Test
    fun 能力恢复只重启对应失败项_不反复重试无效载荷或仍关闭的渠道() = runBlocking {
        val errors = listOf("PERMISSION_DENIED", "CHANNEL_DISABLED:available", "CHANNEL_DISABLED:blocked", "PAYLOAD_INVALID")
        errors.forEachIndexed { index, error ->
            val row = createSingleFor(seedEvent(index), com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
            db.openHelper.writableDatabase.execSQL("UPDATE notification_outbox SET status = 'FAILED', lastError = ? WHERE outboxId = ?",
                arrayOf(error, row.outboxId))
        }
        assertEquals(2, db.notificationOutboxDao().reviveCapabilityFailures(wall, listOf("CHANNEL_DISABLED:available"), 50))
        assertEquals(2, db.notificationOutboxDao().findDue(wall, 20).size)
    }

    private suspend fun recordPendingHistory(row: com.example.bilimonitor.data.local.entity.NotificationOutboxEntity) {
        db.notificationHistoryDao().insert(com.example.bilimonitor.data.local.entity.NotificationHistoryEntity(
            "hist-${row.outboxId}", row.eventKey, row.outboxId, null, row.notificationId,
            null, row.aggregateId, com.example.bilimonitor.data.local.NotificationHistoryDeliveryStatus.PENDING,
            1, null, null, wall, wall, "NOT_VISIBLE_AFTER_POST"))
    }

    private fun notificationResolver() = NotificationDeepLinkResolver(db.notificationOutboxDao(),
        db.liveEventDao(), db.notificationAggregateDao(), db.streamerDao())

    private suspend fun createSingleFor(event: LiveEventEntity, type: com.example.bilimonitor.data.local.NotificationEventType):
        com.example.bilimonitor.data.local.entity.NotificationOutboxEntity {
        NotificationOutboxWriter.createSingle(db, clock, event, event.streamerId, type, payloadOf(event), wall, true)
        return db.notificationOutboxDao().findByEventKey("evt:${event.eventId}")!!
    }

    private suspend fun withDeliveryGuard(block: suspend (com.example.bilimonitor.background.NotificationDeliveryGuard) -> Unit) {
        val quiet = QuietHoursRepository(context)
        val old = quiet.current()
        try {
            quiet.set(false, old.startMinutes, old.endMinutes)
            val config = ConfigRepository(db, db.configDao(), db.policyDao(), db.logDao(), clock, quiet)
            block(com.example.bilimonitor.background.NotificationDeliveryGuard(db, config,
                NotificationDeliverySettingsRepository(context), quiet, clock))
        } finally { quiet.set(old.enabled, old.startMinutes, old.endMinutes) }
    }

    private suspend fun seedSession(event: LiveEventEntity) {
        db.liveSessionDao().insert(com.example.bilimonitor.data.local.entity.LiveSessionEntity(
            stableId = event.sessionStableId!!, sessionKey = "LIVEsession:${event.sessionStableId}",
            streamerId = event.streamerId, startTime = wall, endTime = null,
            startSource = null, endSource = null, durationSource = null,
            startConfidence = null, endConfidence = null, durationConfidence = null,
            startLocked = false, endLocked = false, durationLocked = false,
            titleAtStart = "测试", titleAtEnd = null, areaAtStart = null, areaAtEnd = null,
            coverUrlAtStart = null, coverUrlAtEnd = null, durationSeconds = null, endReason = null,
            lifecycleState = com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState.ACTIVE,
            currentReliableIntervalId = null, lastConfirmedLiveAt = wall, confirmedOfflineAt = null,
            autoUpdateProtected = false, correctionVersion = 0, createdAt = wall, updatedAt = wall, note = null))
    }

    @Test
    fun 失败的同类维护申请不能释放当前持有者() = runBlocking {
        val locks = RuntimeLockRepository(db.runtimeLockDao(), clock)
        val a = RuntimeLockRepository.MaintenanceLease(com.example.bilimonitor.data.local.MaintenanceMode.BACKUP)
        val b = RuntimeLockRepository.MaintenanceLease(a.mode)
        org.junit.Assert.assertTrue(locks.takeOverForMaintenance(a))
        org.junit.Assert.assertFalse(locks.takeOverForMaintenance(b))
        org.junit.Assert.assertFalse(locks.exitMaintenance(b))
        assertEquals(a.owner, db.runtimeLockDao().get()?.fencingToken)
        org.junit.Assert.assertTrue(locks.exitMaintenance(a))
        org.junit.Assert.assertFalse(locks.exitMaintenance(a))
    }

    @Test
    fun 取消发生在维护提交之后仍能释放_旧持有者不能释放新操作() = runBlocking {
        val locks = RuntimeLockRepository(db.runtimeLockDao(), clock)
        val a = RuntimeLockRepository.MaintenanceLease(com.example.bilimonitor.data.local.MaintenanceMode.DATABASE_REPAIR)
        val job = launch {
            try {
                org.junit.Assert.assertTrue(locks.takeOverForMaintenance(a))
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                kotlinx.coroutines.yield()
            } finally { locks.exitMaintenance(a) }
        }
        job.join()
        assertEquals(com.example.bilimonitor.data.local.MaintenanceMode.OFF, db.runtimeLockDao().get()?.maintenanceMode)
        val b = RuntimeLockRepository.MaintenanceLease(a.mode)
        org.junit.Assert.assertTrue(locks.takeOverForMaintenance(b))
        org.junit.Assert.assertFalse(locks.exitMaintenance(a))
        assertEquals(b.owner, db.runtimeLockDao().get()?.fencingToken)
        org.junit.Assert.assertTrue(locks.exitMaintenance(b))
    }

    @Test
    fun 定期清理不抢占有效监控租约() = runBlocking {
        val locks = RuntimeLockRepository(db.runtimeLockDao(), clock)
        val monitor = locks.acquireLease()!!
        val maintenance = RuntimeLockRepository.MaintenanceLease(com.example.bilimonitor.data.local.MaintenanceMode.DATABASE_REPAIR)
        wall += 3_600_000 // Wall changes must not make background cleanup steal a valid lease.
        org.junit.Assert.assertFalse(locks.enterMaintenance(maintenance))
        org.junit.Assert.assertFalse(locks.exitMaintenance(maintenance))
        assertEquals(monitor.fencingToken, db.runtimeLockDao().get()?.fencingToken)
        locks.release(monitor)
        org.junit.Assert.assertTrue(locks.enterMaintenance(maintenance))
        org.junit.Assert.assertTrue(locks.exitMaintenance(maintenance))
    }

    @Test
    fun 汇总覆盖超过一千条历史_筛选在分页之前执行() = runBlocking {
        val oldEvent = seedEvent(0)
        seedSession(oldEvent)
        val template = db.liveSessionDao().findByStableId(oldEvent.sessionStableId!!)!!
        db.openHelper.writableDatabase.execSQL("UPDATE live_session SET endTime = ?, durationSeconds = 60 WHERE stableId = ?",
            arrayOf(wall + 60_000, template.stableId))
        val recentEvent = seedEvent(1)
        db.withTransaction {
            repeat(1001) { i ->
                val start = wall + (i + 1) * 120_000L
                db.liveSessionDao().insert(template.copy(id = 0, stableId = "many-$i", sessionKey = "many-$i",
                    streamerId = recentEvent.streamerId, startTime = start, endTime = start + 60_000,
                    durationSeconds = 60, titleAtStart = if (i == 0) "unique old title" else "recent"))
            }
        }
        val summaries = db.liveSessionDao().observeSummaries().first().associateBy { it.streamerId }
        assertEquals(1, summaries[oldEvent.streamerId]?.sessionCount)
        assertEquals(60L, summaries[oldEvent.streamerId]?.totalSeconds)
        assertEquals(1001, summaries[recentEvent.streamerId]?.sessionCount)
        assertEquals(60_060L, summaries[recentEvent.streamerId]?.totalSeconds)
        val filtered = db.liveSessionDao().observeFiltered(recentEvent.streamerId, Long.MIN_VALUE,
            Long.MAX_VALUE, "unique old", 200).first()
        assertEquals("many-0", filtered.single().stableId)
        val dateFiltered = db.liveSessionDao().observeFiltered(recentEvent.streamerId, wall + 120_000,
            wall + 240_000, "", 200).first()
        assertEquals("many-0", dateFiltered.single().stableId)
        assertEquals(201, db.liveSessionDao().observeFiltered(recentEvent.streamerId, Long.MIN_VALUE,
            Long.MAX_VALUE, "", 201).first().size)
    }

    @Test
    fun 备注编辑保留时间来源_空备注可清除且审计只列实际修改() = runBlocking {
        val event = seedEvent(0)
        seedSession(event)
        db.openHelper.writableDatabase.execSQL("UPDATE live_session SET endTime = ?, durationSeconds = 60, startSource = 'MONITOR_OBSERVED', startConfidence = 'CONFIRMED', note = 'old' WHERE stableId = ?",
            arrayOf(wall + 60_000, event.sessionStableId))
        val before = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
        val history = HistoryRepository(db, db.liveSessionDao(), db.liveSessionTitleDao(), db.liveEventDao(),
            db.streamerDao(), db.logDao(), clock)
        val correction = ManualCorrection(before.stableId, before.startTime, before.endTime,
            before.durationSeconds, "updated")
        assertEquals(CorrectionResult.Applied, history.applyManualCorrection(correction))
        val after = db.liveSessionDao().findByStableId(before.stableId)!!
        assertEquals(before.startSource, after.startSource)
        assertEquals(before.startConfidence, after.startConfidence)
        org.junit.Assert.assertFalse(after.startLocked)
        assertEquals("[\"note\"]", stringOf("SELECT changedFieldsJson FROM live_session_correction ORDER BY createdAt DESC LIMIT 1"))
        assertEquals(CorrectionResult.Applied, history.applyManualCorrection(
            ManualCorrection(before.stableId, null, null, null, null, noteChanged = true)))
        org.junit.Assert.assertNull(db.liveSessionDao().findByStableId(before.stableId)?.note)
    }

    @Test
    fun 部分陈旧的批次立即处理健康成员_待复核成员幂等转为单条() = runBlocking {
        repeat(2) { i ->
            val event = seedEvent(i); seedSession(event)
            NotificationOutboxWriter.createForEvent(db, clock, event, event.streamerId, payloadOf(event), policy(1), 1, wall)
        }
        closeWindow()
        val dao = db.notificationOutboxDao()
        val batch = dao.findDue(wall, 20).single()
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET lastConfirmedAt = 1 WHERE stableId = 'str-test-1'")
        withDeliveryGuard { guard ->
            val prepared = guard.prepare(batch) as com.example.bilimonitor.background.NotificationDeliveryGuard.Result.Ready
            assertEquals(1, NotificationPayloads.decodeBatch(prepared.row.payloadJson)?.items?.size)
            assertEquals("str-test-1", prepared.deferred.single().streamerStableId)
            val dispatcher = com.example.bilimonitor.background.NotificationDispatcher(db, dao,
                db.notificationHistoryDao(), db.notificationAggregateDao(), db.logDao(),
                RuntimeLockRepository(db.runtimeLockDao(), clock),
                com.example.bilimonitor.notify.NotificationPoster(context, clock), clock, guard)
            dispatcher.dispatchDue()
            org.junit.Assert.assertTrue(dao.findById(batch.outboxId)!!.attemptCount > 0)
            assertEquals(1, NotificationPayloads.decodeBatch(dao.findById(batch.outboxId)!!.payloadJson)?.items?.size)
            val deferred = dao.findByEventKey("evt:evt-test-1")!!
            assertEquals(0, deferred.attemptCount)
            assertEquals(1, db.notificationAggregateDao().countActiveEvents(batch.aggregateId!!))
            dispatcher.dispatchDue()
            assertEquals(1, singleCount())
            assertEquals(0, dao.findById(deferred.outboxId)?.attemptCount)
        }
    }

    @Test
    fun 系统时间前后跳动不改变同次开机通知有效期和重试截止时间() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val row = createSingleFor(event, com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
        withDeliveryGuard { guard ->
            val ready = (guard.prepare(row) as com.example.bilimonitor.background.NotificationDeliveryGuard.Result.Ready).row
            db.openHelper.writableDatabase.execSQL("UPDATE notification_outbox SET expiresAt = ? WHERE outboxId = ?",
                arrayOf(ready.expiresAt, row.outboxId))
            val dao = db.notificationOutboxDao()
            wall += 600_000; elapsed += 10_000
            assertEquals(0, dao.expireOverduePending(wall, elapsed, clock.bootId()))
            assertEquals(1, dao.findDue(wall, 20, elapsed, clock.bootId()).size)
            org.junit.Assert.assertTrue(guard.prepare(dao.findById(row.outboxId)!!) is com.example.bilimonitor.background.NotificationDeliveryGuard.Result.Ready)
            dao.awaitObservation(row.outboxId, row.createdAt + 15_000)
            wall -= 3_600_000
            assertEquals(5_000L, dao.nextDeliveryDelay(wall, elapsed, clock.bootId()))
            assertEquals(0, dao.findDue(wall, 20, elapsed, clock.bootId()).size)
            elapsed += 5_000
            assertEquals(1, dao.findDue(wall, 20, elapsed, clock.bootId()).size)
            assertEquals(1, dao.claimOutbox(row.outboxId, wall, wall + 30_000, elapsed + 30_000,
                clock.bootId(), "worker", "attempt"))
            elapsed += 31_000
            dao.recoverExpiredProcessing(wall, elapsed, clock.bootId())
            assertEquals(com.example.bilimonitor.data.local.NotificationOutboxStatus.RETRY_WAIT, dao.findById(row.outboxId)?.status)
            elapsed += 300_000
            assertEquals(1, dao.expireOverduePending(wall, elapsed, clock.bootId()))
            assertEquals(0, dao.findDue(wall, 20, elapsed, clock.bootId()).size)
        }
    }

    @Test
    fun 跨开机和旧通知使用墙钟回退() = runBlocking {
        val row = createSingleFor(seedEvent(0), com.example.bilimonitor.data.local.NotificationEventType.START_CONFIRMED)
        assertEquals(1, db.notificationOutboxDao().expireOverduePending(row.expiresAt + 1, 1, "other-boot"))
    }

    @Test
    fun 健康计数随主播变化发射而无需更改配置() = runBlocking {
        val event = seedEvent(0)
        val values = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = launch {
            db.streamerDao().observeHealthGroups().collect { groups -> values.send(groups.sumOf { it.count }) }
        }
        try {
            assertEquals(1, kotlinx.coroutines.withTimeout(5000) { values.receive() })
            db.streamerDao().setMonitoringEnabled(event.streamerId, false, wall)
            assertEquals(0, kotlinx.coroutines.withTimeout(5000) { values.receive() })
        } finally { job.cancel(); job.join(); values.close() }
    }

    @Test
    fun 审计日志保留新记录_按批次清除过期记录() = runBlocking {
        repeat(4) { i -> db.logDao().insertAudit(com.example.bilimonitor.data.local.entity.AuditLogEntity(
            "audit-$i", "op-$i", "SYSTEM", "TEST", "test", null, wall - i * 1000, null)) }
        assertEquals(1, db.logDao().cleanupAudit(wall - 1500, 100, 1))
        assertEquals(3, countOf("SELECT COUNT(*) FROM audit_log"))
        assertEquals(1, db.logDao().cleanupAudit(wall - 1500, 100, 10))
        assertEquals(2, countOf("SELECT COUNT(*) FROM audit_log"))
    }

    // ---------------------------------------------------------------- 断言工具

    private fun batchCount(eventType: String? = null): Int =
        if (eventType == null) {
            countOf("SELECT COUNT(*) FROM notification_outbox WHERE aggregateId IS NOT NULL")
        } else {
            countOf(
                "SELECT COUNT(*) FROM notification_outbox " +
                    "WHERE aggregateId IS NOT NULL AND eventType = '$eventType'"
            )
        }

    private fun singleCount(): Int =
        countOf("SELECT COUNT(*) FROM notification_outbox WHERE aggregateId IS NULL")

    private fun batchPayload(eventType: String? = null): String =
        if (eventType == null) {
            stringOf("SELECT payloadJson FROM notification_outbox WHERE aggregateId IS NOT NULL LIMIT 1")
        } else {
            stringOf(
                "SELECT payloadJson FROM notification_outbox " +
                    "WHERE aggregateId IS NOT NULL AND eventType = '$eventType' LIMIT 1"
            )
        }

    private fun countOf(sql: String): Int = query(sql) { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun stringOf(sql: String): String = query(sql) { if (it.moveToFirst()) it.getString(0) else "" }

    private fun <T> query(sql: String, read: (Cursor) -> T): T =
        (db as RoomDatabase).query(sql, null).use(read)
}
