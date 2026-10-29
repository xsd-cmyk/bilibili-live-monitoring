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
