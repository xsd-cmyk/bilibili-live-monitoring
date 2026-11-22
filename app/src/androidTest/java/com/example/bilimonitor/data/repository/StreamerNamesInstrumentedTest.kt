package com.example.bilimonitor.data.repository

import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.db.AppDatabaseFactory
import com.example.bilimonitor.data.remote.bilibili.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamerNamesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val filename = "name-refresh-test.db"
    private lateinit var db: AppDatabase
    private var elapsed = 1000L
    private val clock = object : AppClock {
        override fun nowElapsed() = elapsed
        override fun nowWall() = elapsed + 100_000
        override fun bootId() = "name-test-boot"
        override fun currentTimeZone() = "UTC"
    }
    private val calls = mutableListOf<List<Long>>()
    private var respond: suspend (List<Long>) -> BiliStatusResponse = { error("unexpected request") }
    private val source = StreamerNameSource(object : BiliLiveApi {
        override suspend fun getRoomStatusByUids(uids: List<Long>): BiliStatusResponse {
            calls.add(uids)
            return respond(uids)
        }
        override suspend fun getRoomDetail(roomId: Long): BiliRoomDetailResponse = error("unexpected room detail")
        override suspend fun getRoomInit(roomId: Long): BiliRoomInitResponse = error("unexpected room probe")
    })

    @Before fun open() { context.deleteDatabase(filename); db = AppDatabaseFactory.createForTest(context, filename) }
    @After fun close() { db.close(); context.deleteDatabase(filename) }

    private val insert = """INSERT INTO streamer (id,stableId,uid,name,nameLocked,confirmedLiveStatus,lastObservationResult,
        observationSequence,streamerMonitorGeneration,monitoringEnabled,isFavorite,freshnessStatus,createdAt,updatedAt)
        VALUES (?, 'stable-' || ?, ?, '旧名', ?, 'OFFLINE','SUCCESS',8,1,0,0,'FRESH',0,0)"""
    private fun seed(uid: Int, locked: Boolean = false) = db.openHelper.writableDatabase.execSQL(insert,
        arrayOf(uid, uid, uid, if (locked) 1 else 0))

    @Test fun deletedBetweenBatchesIsSkippedWithoutRequest() = runBlocking {
        for (uid in 1..2) seed(uid)
        db.openHelper.writableDatabase.execSQL("UPDATE monitoring_config SET batchSize=1")
        respond = { uids ->
            db.streamerDao().softDelete(2, clock.nowWall())
            BiliStatusResponse(0, data = mapOf("1" to BiliRoomInfo(uid = uids.single(), uname = "甲")))
        }
        assertEquals(NameRefreshResult(1, 0, 1), StreamerNameRepository(db, source, clock).refresh(force = true))
        assertEquals(listOf(listOf(1L)), calls)
    }

    @Test fun mixedBatchRetriesOnlyFailuresAndUsesPersistedSettings() = runBlocking {
        val names = StreamerNameRepository(db, source, clock)
        assertEquals(NameRefreshResult(0, 0, 0), names.refresh(force = true))
        for (uid in 1..5) seed(uid, locked = uid == 4)
        db.streamerDao().softDelete(5, clock.nowWall())
        respond = { BiliStatusResponse(0, data = mapOf("1" to BiliRoomInfo(uid = 1, uname = " 甲 "),
            "2" to BiliRoomInfo(uid = 2, uname = "   "), "3" to BiliRoomInfo(uid = 9, uname = "不属于丙"))) }
        assertEquals(NameRefreshResult(1, 2, 2), names.refresh(force = true))
        assertEquals(listOf(1L, 2L, 3L), calls.single())
        assertEquals("甲", db.streamerDao().findById(1)!!.name)
        assertEquals("旧名", db.streamerDao().findById(2)!!.name)
        val config = ConfigRepository(db, db.configDao(), db.policyDao(), db.logDao(), clock, QuietHoursRepository(context))
        assertEquals(ConfigUpdateResult.Applied, config.update(ConfigUpdate(nameRefreshHours = 6, nameRetryMinutes = 3)))
        assertTrue(config.update(ConfigUpdate(nameRetryMinutes = 0)) is ConfigUpdateResult.Invalid)
        assertEquals(3, config.getSnapshot()!!.nameRetryMinutes)
        elapsed += 179_999
        names.refresh()
        assertEquals(1, calls.size)
        db.close(); db = AppDatabaseFactory.createForTest(context, filename)
        val reopened = StreamerNameRepository(db, source, clock)
        elapsed++
        respond = { BiliStatusResponse(0, data = mapOf("2" to BiliRoomInfo(uid = 2, uname = "乙"))) }
        assertEquals(NameRefreshResult(1, 1, 0), reopened.refresh())
        assertEquals(listOf(2L, 3L), calls.last())
        elapsed += 180_000
        respond = { throw java.io.IOException("offline") }
        assertEquals(NameRefreshResult(0, 1, 0), reopened.refresh())
        assertEquals(listOf(3L), calls.last())
        assertEquals(8L, db.streamerDao().findById(3)!!.observationSequence)
    }

    @Test fun userEditDeleteRestoreAndMaintenanceFenceLateResults() = runBlocking {
        val names = StreamerNameRepository(db, source, clock)
        seed(1); seed(2); seed(3)
        respond = { uids ->
            db.streamerDao().setUserName(1, "手动", clock.nowWall())
            db.streamerDao().softDelete(2, clock.nowWall()); db.streamerDao().restore(2, clock.nowWall())
            db.streamerDao().bumpStreamerMonitorGeneration(2, clock.nowWall())
            BiliStatusResponse(0, data = uids.associate { it.toString() to BiliRoomInfo(uid = it, uname = "新名") })
        }
        assertEquals(NameRefreshResult(1, 0, 2), names.refresh(force = true))
        assertEquals("手动", db.streamerDao().findById(1)!!.name)
        assertEquals("旧名", db.streamerDao().findById(2)!!.name)
        respond = {
            val locks = db.runtimeLockDao()
            locks.enterMaintenanceForcing(MaintenanceMode.RESTORE, clock.nowWall(), "owner")
            locks.exitMaintenance(MaintenanceMode.RESTORE, clock.nowWall(), "owner")
            BiliStatusResponse(0, data = mapOf("3" to BiliRoomInfo(uid = 3, uname = "迟到")))
        }
        assertEquals(NameRefreshResult(0, 0, 1), names.refresh(force = true, onlyId = 3))
        assertEquals("新名", db.streamerDao().findById(3)!!.name)
        db.streamerDao().unlockName(1, clock.nowWall())
        respond = { BiliStatusResponse(0, data = mapOf("1" to BiliRoomInfo(uid = 1, uname = "远程"))) }
        assertEquals(1, names.refresh(onlyId = 1).success)
        assertEquals("远程", db.streamerDao().findById(1)!!.name)
    }

    @Test fun automaticAndManualRefreshShareAnInflightBatch() = runBlocking {
        val names = StreamerNameRepository(db, source, clock)
        seed(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        respond = {
            entered.complete(Unit); release.await()
            BiliStatusResponse(-1, data = mapOf("1" to BiliRoomInfo(uid = 1, uname = "无效结果")))
        }
        val automatic = async { names.refresh() }
        entered.await()
        val manual = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { names.refresh(force = true) }
        db.withTransaction { Unit }
        release.complete(Unit)
        assertEquals(NameRefreshResult(0, 1, 0), automatic.await())
        assertEquals(NameRefreshResult(0, 1, 0), manual.await())
        assertEquals(1, calls.size)
        assertEquals("旧名", db.streamerDao().findById(1)!!.name)
    }

    @Test fun upgradeFromExportedV12PreservesLockedNamesAndPartialIndexes() = runBlocking {
        db.close()
        val schema = instrumentation.context.assets.open("com.example.bilimonitor.data.local.db.AppDatabase/12.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(filename)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (offset in 0 until indices.length()) old.execSQL(indices.getJSONObject(offset)
                    .getString("createSql").replace("\u0024{TABLE_NAME}", table))
            }
            old.execSQL(insert, arrayOf(1, 1, 1, 1))
            old.version = 12
        }
        db = AppDatabaseFactory.createForTest(context, filename)
        val row = db.streamerDao().findById(1)!!
        assertEquals("旧名", row.name); assertTrue(row.nameLocked); assertNull(row.nameAttemptWall)
        val config = db.configDao().getConfig()!!
        assertEquals(24, config.nameRefreshHours); assertEquals(1, config.nameRetryMinutes)
        db.openHelper.readableDatabase.query("SELECT sql FROM sqlite_master WHERE name='idx_live_session_one_active_open'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getString(0).contains("WHERE", ignoreCase = true))
        }
    }
}
