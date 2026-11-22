package com.example.bilimonitor.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.*
import com.example.bilimonitor.data.local.db.*
import com.example.bilimonitor.data.local.entity.*
import com.example.bilimonitor.domain.model.MonitoringLease
import com.example.bilimonitor.domain.policy.RoomBanCodec
import com.example.bilimonitor.domain.policy.RoomBanPolicy
import com.example.bilimonitor.domain.policy.RoomBanProbeResult
import com.example.bilimonitor.domain.policy.RoomBanState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBanHistoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private var wall = 1_700_000_000_000L
    private var elapsed = 10_000L
    private val clock = object : AppClock {
        override fun nowWall() = wall
        override fun nowElapsed() = elapsed
        override fun bootId() = "ban-test-boot"
        override fun currentTimeZone() = "Asia/Shanghai"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var monitor: MonitorRepository
    private lateinit var locks: RuntimeLockRepository
    @Before fun setUp() {
        context.deleteDatabase("room_ban_test.db")
        db = AppDatabaseFactory.createForTest(context, "room_ban_test.db")
        locks = RuntimeLockRepository(db.runtimeLockDao(), clock)
        monitor = MonitorRepository(db, db.streamerDao(), db.liveSessionDao(), db.liveEventDao(),
            db.statusHistoryDao(), db.reliableIntervalDao(), db.monitoringGapDao(),
            db.liveSessionTitleDao(), db.liveSessionAreaDao(), db.configDao(), db.logDao(), clock)
    }
    @After fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase("room_ban_test.db")
        context.deleteDatabase("room_ban_restore_test.db")
    }
    private fun ban(previous: RoomBanState? = null): RoomBanState = RoomBanPolicy.nextState(previous,
        RoomBanProbeResult(true, -1), wall, 1800)!!
    private fun advance(ms: Long) { wall += ms; elapsed += ms }
    private var testLease: MonitoringLease? = null
    private suspend fun apply(id: Long, state: RoomBanState?, lease: MonitoringLease? = null, period: Int = 60): Boolean {
        val currentLease = lease ?: testLease ?: locks.acquireLease()!!.also { testLease = it }
        return monitor.applyBanState(db.streamerDao().findById(id)!!, currentLease, state, period) {}
    }
    private fun legacyMarker(id: Long, state: RoomBanState) {
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET lastError = ? WHERE id = ?", arrayOf(RoomBanCodec.encode(state), id))
    }
    private fun history() = HistoryRepository(db, db.liveSessionDao(), db.liveSessionTitleDao(),
        db.liveEventDao(), db.streamerDao(), db.logDao(), clock)

    @Test fun confirmedBanClosesLiveSessionAndStopsLiveState() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val lease = locks.acquireLease()!!
        advance(90_000)
        assertTrue(apply(event.streamerId, ban(), lease, 60))
        val session = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
        assertEquals(wall, session.endTime)
        assertEquals(90L, session.durationSeconds)
        assertEquals("ROOM_BANNED", session.endReason)
        assertEquals(DataConfidence.PROVISIONAL, session.endConfidence)
        assertEquals(ConfirmedLiveStatus.OFFLINE, db.streamerDao().findById(event.streamerId)?.confirmedLiveStatus)
        assertEquals(1, db.roomBanHistoryDao().observeCount(event.streamerId).first())
        assertEquals(session.stableId, db.roomBanHistoryDao().listAll().single().sessionStableId)
        assertEquals(0, db.notificationOutboxDao().countActiveOutbox())
    }

    @Test fun rechecksDoNotIncrementCountsOrExtendClosedSession() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        advance(30_000)
        var state = ban()
        assertTrue(apply(event.streamerId, state))
        val ended = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
        repeat(10) {
            advance(1_800_000)
            state = ban(state)
            assertTrue(apply(event.streamerId, state))
        }
        assertEquals(1, db.roomBanHistoryDao().observeCount(event.streamerId).first())
        val after = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
        assertEquals(ended.endTime, after.endTime)
        assertEquals(ended.correctionVersion, after.correctionVersion)
    }

    @Test fun unbanThenNewBanAddsOneEpisodeEachTime() = runBlocking {
        val event = seedEvent(0)
        val first = ban()
        assertTrue(apply(event.streamerId, first))
        advance(60_000)
        assertTrue(apply(event.streamerId, null))
        assertEquals(wall, db.roomBanHistoryDao().findEpisode(event.streamerId, first.lockedAtWall)?.liftedAt)
        advance(60_000)
        assertTrue(apply(event.streamerId, ban()))
        assertEquals(2, db.roomBanHistoryDao().observeCount(event.streamerId).first())
        assertEquals(2, db.roomBanHistoryDao().listAll().map { it.banId }.distinct().size)
    }

    @Test fun staleMonitoringFenceCannotCloseOrCountABan() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val old = locks.acquireLease()!!
        val maintenance = RuntimeLockRepository.MaintenanceLease(MaintenanceMode.BACKUP)
        assertTrue(locks.takeOverForMaintenance(maintenance))
        assertFalse(apply(event.streamerId, ban(), old, 60))
        assertNull(db.liveSessionDao().findByStableId(event.sessionStableId!!)?.endTime)
        assertEquals(0, db.roomBanHistoryDao().listAll().size)
        assertTrue(locks.exitMaintenance(maintenance))
    }

    @Test fun knownBanRepairsOldOpenSessionUsingOriginalDetectionTime() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        advance(60_000)
        val known = ban()
        legacyMarker(event.streamerId, known)
        advance(3 * 86_400_000L)
        val lease = locks.acquireLease()!!
        assertTrue(apply(event.streamerId, known, lease, 60))
        val fixed = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
        assertEquals(known.lockedAtWall, fixed.endTime)
        assertEquals(60L, fixed.durationSeconds)
        assertEquals(1, db.roomBanHistoryDao().listAll().size)
    }

    @Test fun abandonedOpenSessionIsClosedButExistingManualEndIsPreserved() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val template = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!
        db.liveSessionDao().insert(template.copy(id = 0, stableId = "abandoned", sessionKey = "abandoned",
            startTime = wall - 5000, lifecycleState = LiveSessionLifecycleState.ABANDONED))
        db.liveSessionDao().insert(template.copy(id = 0, stableId = "manual", sessionKey = "manual",
            endTime = wall, endSource = DataSource.USER_CORRECTED, endConfidence = DataConfidence.CORRECTED,
            endLocked = true, durationSeconds = 42))
        advance(20_000)
        assertTrue(apply(event.streamerId, ban()))
        assertTrue(db.liveSessionDao().listOpenForStreamer(event.streamerId).isEmpty())
        assertEquals(DataSource.USER_CORRECTED, db.liveSessionDao().findByStableId("manual")?.endSource)
        assertEquals(42L, db.liveSessionDao().findByStableId("manual")?.durationSeconds)
    }

    @Test fun unknownProbeDoesNotCreateABanOrCloseASession() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val unknown = RoomBanPolicy.nextState(null, null, wall, 1800)
        assertFalse(apply(event.streamerId, unknown))
        assertNull(db.liveSessionDao().findByStableId(event.sessionStableId!!)?.endTime)
        assertEquals(0, db.roomBanHistoryDao().listAll().size)
    }

    @Test fun deletingASessionDoesNotLoseTheRoomsBanCount() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        advance(30_000)
        assertTrue(apply(event.streamerId, ban()))
        assertEquals(1, history().deleteSession(event.sessionStableId!!))
        assertEquals(1, db.roomBanHistoryDao().observeCount(event.streamerId).first())
    }

    @Test fun migrationBackfillsEvidenceAndDeduplicatesCurrentMarkerAndAudit() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val first = wall
        fun audit(id: String, action: String, at: Long) = AuditLogEntity(id, id, "SYSTEM", action,
            "streamer", event.streamerStableId, at, null)
        db.logDao().insertAudit(audit("first", "ROOM_BAN_ENTERED", first))
        db.logDao().insertAudit(audit("duplicate", "ROOM_BAN_ENTERED", first))
        db.logDao().insertAudit(audit("lifted", "ROOM_BAN_LIFTED", first + 60_000))
        advance(120_000)
        val current = ban()
        db.logDao().insertAudit(audit("second", "ROOM_BAN_ENTERED", wall))
        legacyMarker(event.streamerId, current)
        AppMigrations.V15_16__backfillRoomBans.migrate(db.openHelper.writableDatabase)
        AppMigrations.V15_16__backfillRoomBans.migrate(db.openHelper.writableDatabase)
        assertEquals(2, db.roomBanHistoryDao().listAll().size)
        assertEquals(first + 60_000, db.roomBanHistoryDao().findEpisode(event.streamerId, first)?.liftedAt)
        assertNull(db.roomBanHistoryDao().findEpisode(event.streamerId, wall)?.liftedAt)
        assertTrue(apply(event.streamerId, current))
        assertEquals(2, db.roomBanHistoryDao().listAll().size)
    }

    private fun backup(target: AppDatabase): BackupRepository {
        val quiet = QuietHoursRepository(context)
        val lock = RuntimeLockRepository(target.runtimeLockDao(), clock)
        val config = ConfigRepository(target, target.configDao(), target.policyDao(), target.logDao(), clock, quiet)
        val maintenance = MaintenanceRepository(context, target, lock, target.logDao(), clock, target.problemDao(), scope)
        return BackupRepository(context, target, target.restoreDao(), target.logDao(), lock, config, quiet, maintenance,
            ImportSettingsRepository(context), NotificationSettingsRepository(context), clock)
    }

    @Test fun backupRestorePreservesEpisodesAndRepeatedRestoreDoesNotDoubleCount() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        advance(30_000)
        assertTrue(apply(event.streamerId, ban()))
        val source = backup(db)
        val file = source.serializeBackup()
        assertEquals(1, file.body.roomBans.size)
        val content = source.serializeToString(file.copy(settings = null, manifest = file.manifest.copy(settingsChecksum = null)))
        val target = AppDatabaseFactory.createForTest(context, "room_ban_restore_test.db")
        try {
            val repo = backup(target)
            repeat(2) {
                val pre = repo.precheck(content) as BackupRepository.PrecheckResult.Ready
                repo.decideAll(pre.restoreRunId, RestoreConflictAction.USE_BACKUP)
                repo.applyRestore(pre.restoreRunId, content)
                assertEquals(1, target.roomBanHistoryDao().listAll().size)
                val restoredStreamer = target.streamerDao().findByStableId(event.streamerStableId)!!
                assertNull(RoomBanCodec.decode(restoredStreamer.lastError))
                assertEquals(file.body.roomBans.single().detectedAt, target.roomBanHistoryDao().listAll().single().detectedAt)
            }
            assertEquals("ROOM_BANNED", target.liveSessionDao().findByStableId(event.sessionStableId!!)?.endReason)
        } finally { target.close() }
    }

    @Test fun historyExportIncludesBansEvenWithoutRecordedSessions() = runBlocking {
        val event = seedEvent(0)
        assertTrue(apply(event.streamerId, ban()))
        val exporter = ExportRepository(context, db, locks,
            StatsSeriesRepository(db.liveSessionDao(), db.streamerDao(), clock), db.logDao(), clock)
        val exported = exporter.exportHistory(listOf(event.streamerId), wall - 1, wall + 1, "测试范围").getOrThrow()
        val resolver = context.contentResolver
        val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.query(downloads, arrayOf("_id"), "_display_name = ?", arrayOf(exported.fileName), null)!!.use {
            assertTrue(it.moveToFirst())
            android.content.ContentUris.withAppendedId(downloads, it.getLong(0))
        }
        try {
            val html = resolver.openInputStream(uri)!!.use { it.reader().readText() }
            assertTrue(html.contains("封禁：1 次"))
            assertTrue(html.contains("本范围内封禁 1 次"))
        } finally { resolver.delete(uri, null, null) }
    }
    private fun engine(data: com.example.bilimonitor.data.remote.bilibili.BiliLiveDataSource): com.example.bilimonitor.background.MonitoringEngine {
        val quiet = QuietHoursRepository(context)
        val config = ConfigRepository(db, db.configDao(), db.policyDao(), db.logDao(), clock, quiet)
        val notification = NotificationRepository(db, db.notificationAggregateDao(), db.notificationOutboxDao(),
            db.liveEventDao(), db.streamerDao(), clock, quiet)
        return com.example.bilimonitor.background.MonitoringEngine(config, db.streamerDao(), data, monitor,
            notification, locks, db.monitoringGapDao(), db.reliableIntervalDao(), db.problemDao(), db.logDao(),
            db, clock, scope)
    }

    private inner class FakeRooms(var batchBan: Boolean? = null, var remoteStatus: ConfirmedLiveStatus = ConfirmedLiveStatus.LIVE, var fail: Boolean = false,
        var partialFailure: Boolean = false, var probeLocked: Boolean? = true) : com.example.bilimonitor.data.remote.bilibili.BiliLiveDataSource {
        var batches = 0
        var probes = 0
        override suspend fun getRoomDetailByRoomId(roomId: Long): com.example.bilimonitor.domain.model.RemoteLiveRoom? = null
        override suspend fun probeBan(roomId: Long): com.example.bilimonitor.domain.policy.BanProbeOutcome {
            probes++
            return probeLocked?.let { com.example.bilimonitor.domain.policy.BanProbeOutcome.Concluded(RoomBanProbeResult(it, -1)) }
                ?: com.example.bilimonitor.domain.policy.BanProbeOutcome.Failed(
                    com.example.bilimonitor.domain.policy.CallFailure(com.example.bilimonitor.domain.policy.RoomFailureReason.TIMEOUT))
        }
        override suspend fun getRoomStatus(uidList: List<Long>): com.example.bilimonitor.domain.model.BatchStatusOutcome {
            batches++
            if (fail) return com.example.bilimonitor.domain.model.BatchStatusOutcome.Failed(
                com.example.bilimonitor.domain.policy.CallFailure(com.example.bilimonitor.domain.policy.RoomFailureReason.NETWORK))
            if (partialFailure) return com.example.bilimonitor.domain.model.BatchStatusOutcome.Responded(
                com.example.bilimonitor.domain.model.BatchRoomStatusResponse(uidList, emptyMap(), uidList.toSet(), emptySet(),
                    BatchResponseValidity.PARTIAL, failuresByUid = uidList.associateWith {
                        com.example.bilimonitor.domain.policy.CallFailure(com.example.bilimonitor.domain.policy.RoomFailureReason.API_REJECTED)
                    }))
            val rooms = uidList.associateWith { uid -> com.example.bilimonitor.domain.model.RemoteLiveRoom(
                uid, 100, null, "测试主播", null, "测试标题", null, null, null, null,
                remoteStatus, true, banLocked = batchBan) }
            return com.example.bilimonitor.domain.model.BatchStatusOutcome.Responded(
                com.example.bilimonitor.domain.model.BatchRoomStatusResponse(uidList, rooms, emptySet(), emptySet(),
                    BatchResponseValidity.VALID))
        }
    }

    @Test fun engineFailureProbeClosesTheSession() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        db.openHelper.writableDatabase.execSQL("UPDATE monitoring_config SET maxRetries = 0, circuitBreakerEnabled = 0")
        advance(30_000)
        val fake = FakeRooms(partialFailure = true)
        val result = engine(fake).checkOnce("manual")
        assertTrue(result.ran)
        assertTrue(fake.probes > 0)
        assertNotNull(db.liveSessionDao().findByStableId(event.sessionStableId!!)?.endTime)
        assertEquals(1, db.roomBanHistoryDao().listAll().size)
    }

    @Test fun explicitBatchBanCannotFirstCreateAFalseLiveSession() = runBlocking {
        val event = seedEvent(0)
        db.openHelper.writableDatabase.execSQL("UPDATE streamer SET confirmedLiveStatus = 'OFFLINE'")
        val fake = FakeRooms(batchBan = true)
        assertTrue(engine(fake).checkOnce("manual").ran)
        assertEquals(0, fake.probes)
        assertEquals(0, db.liveSessionDao().listAll().size)
        assertEquals(ConfirmedLiveStatus.OFFLINE, db.streamerDao().findById(event.streamerId)?.confirmedLiveStatus)
        assertEquals(1, db.roomBanHistoryDao().listAll().size)
        assertTrue(db.notificationOutboxDao().observeRecent(20).first().none {
            it.eventType == NotificationEventType.START_CONFIRMED || it.eventType == NotificationEventType.LIVE_RECONFIRMED })
    }

    @Test fun knownBanRepairDoesNotAddANetworkRequest() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        advance(30_000)
        legacyMarker(event.streamerId, ban())
        val fake = FakeRooms()
        assertTrue(engine(fake).checkOnce("manual").ran)
        assertEquals(0, fake.batches)
        assertEquals(0, fake.probes)
        assertNotNull(db.liveSessionDao().findByStableId(event.sessionStableId!!)?.endTime)
        assertEquals(1, db.roomBanHistoryDao().listAll().size)
    }

    @Test fun networkAndUnknownProbeNeverCloseTheSession() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        db.openHelper.writableDatabase.execSQL("UPDATE monitoring_config SET maxRetries = 0, circuitBreakerEnabled = 0")
        val fake = FakeRooms(fail = true, probeLocked = null)
        assertTrue(engine(fake).checkOnce("manual").ran)
        assertEquals(0, fake.probes)
        assertNull(db.liveSessionDao().findByStableId(event.sessionStableId!!)?.endTime)
        assertEquals(0, db.roomBanHistoryDao().listAll().size)
    }

    @Test fun aLaterLiveAfterUnbanCreatesANewSession() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        db.openHelper.writableDatabase.execSQL("UPDATE monitoring_config SET maxRetries = 0, circuitBreakerEnabled = 0")
        val fake = FakeRooms(partialFailure = true)
        val engine = engine(fake)
        assertTrue(engine.checkOnce("manual").ran)
        val firstEnd = db.liveSessionDao().findByStableId(event.sessionStableId!!)!!.endTime
        assertNotNull(firstEnd)
        advance(1_900_000)
        fake.partialFailure = false; fake.probeLocked = false
        assertTrue(engine.checkOnce("manual").ran)
        val sessions = db.liveSessionDao().listAll()
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.count { it.endTime == null })
        assertEquals(firstEnd, sessions.first { it.stableId == event.sessionStableId }.endTime)
        assertEquals(1, db.roomBanHistoryDao().listAll().size)
        assertNotNull(db.roomBanHistoryDao().listAll().single().liftedAt)
    }

    @Test fun offlineBatchWithoutFlagChecksRoomBeforeOrdinaryEnd() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val fake = FakeRooms(remoteStatus = ConfirmedLiveStatus.OFFLINE)
        assertTrue(engine(fake).checkOnce("manual").ran)
        assertEquals(1, fake.probes)
        assertEquals("ROOM_BANNED", db.liveSessionDao().findByStableId(event.sessionStableId!!)!!.endReason)
        assertTrue(db.notificationOutboxDao().observeRecent(20).first().none { it.eventType == NotificationEventType.END_CONFIRMED })
    }

    @Test fun longBlindIntervalIsNotBilledAndFailedRecheckDoesNotLift() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val lastLive = wall
        advance(86_400_000)
        val fake = FakeRooms(batchBan = true)
        val engine = engine(fake)
        assertTrue(engine.checkOnce("manual").ran)
        assertEquals(lastLive, db.liveSessionDao().findByStableId(event.sessionStableId!!)!!.endTime)
        advance(1_900_000); fake.probeLocked = null
        assertTrue(engine.checkOnce("manual").ran)
        assertNull(db.roomBanHistoryDao().listAll().single().liftedAt)
        assertNotNull(RoomBanCodec.decode(db.streamerDao().findById(event.streamerId)!!.lastError))
    }

    @Test fun staleSequenceAndStreamerGenerationCannotPublishBan() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val original = db.streamerDao().findById(event.streamerId)!!
        val lease = locks.acquireLease()!!
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE streamer SET streamerMonitorGeneration = streamerMonitorGeneration + 1")
        assertFalse(monitor.applyBanState(original, lease, ban(), 60) { fail("stale generation") })
        val renewed = db.streamerDao().findById(event.streamerId)!!
        sql.execSQL("UPDATE streamer SET observationSequence = observationSequence + 1")
        assertFalse(monitor.applyBanState(renewed, lease, ban(), 60) { fail("stale sequence") })
        assertNull(db.liveSessionDao().findByStableId(event.sessionStableId!!)!!.endTime)
        assertTrue(db.roomBanHistoryDao().listAll().isEmpty())
    }

    @Test fun failedOutboxWriteRollsBackEpisodeAndClosure() = runBlocking {
        val event = seedEvent(0); seedSession(event)
        val original = db.streamerDao().findById(event.streamerId)!!
        val lease = locks.acquireLease()!!
        val failure = runCatching { monitor.applyBanState(original, lease, ban(), 60) { error("outbox failed") } }
        assertTrue(failure.isFailure)
        assertEquals(original, db.streamerDao().findById(original.id))
        assertTrue(db.roomBanHistoryDao().listAll().isEmpty())
        assertNull(db.liveSessionDao().findByStableId(event.sessionStableId!!)!!.endTime)
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

}
