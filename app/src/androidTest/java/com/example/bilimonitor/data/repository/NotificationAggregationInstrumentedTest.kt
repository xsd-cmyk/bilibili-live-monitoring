package com.example.bilimonitor.data.repository

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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通知合并阈值的**行为**验证（真 Room 库 + 真实事务路径）。
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

    private val clock = object : AppClock {
        override fun nowWall(): Long = wall
        override fun nowElapsed(): Long = elapsed
        override fun bootId(): String = "test-boot"
        override fun currentTimeZone(): String = "Asia/Shanghai"
    }

    private lateinit var repository: NotificationRepository

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
        elapsed += 10_000
        wall += 10_000
        repository.processWindowEnds()

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

    // ---------------------------------------------------------------- 造数据

    private fun policy(threshold: Int) = StreamerNotificationPolicy(
        notifyStart = true,
        notifyEnd = true,
        aggregationEnabled = true,
        aggregationThreshold = threshold,
        aggregationWindowSeconds = 5
    )

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

    // ---------------------------------------------------------------- 断言工具

    private fun batchCount(): Int =
        countOf("SELECT COUNT(*) FROM notification_outbox WHERE aggregateId IS NOT NULL")

    private fun singleCount(): Int =
        countOf("SELECT COUNT(*) FROM notification_outbox WHERE aggregateId IS NULL")

    private fun batchPayload(): String =
        stringOf("SELECT payloadJson FROM notification_outbox WHERE aggregateId IS NOT NULL LIMIT 1")

    private fun countOf(sql: String): Int = query(sql) { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun stringOf(sql: String): String = query(sql) { if (it.moveToFirst()) it.getString(0) else "" }

    private fun <T> query(sql: String, read: (Cursor) -> T): T =
        (db as RoomDatabase).query(sql, null).use(read)
}
