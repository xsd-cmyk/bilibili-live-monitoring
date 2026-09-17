package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AggregateEventBindingStatus
import com.example.bilimonitor.data.local.NotificationAggregateStatus
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.NotificationOutboxStatus
import com.example.bilimonitor.data.local.entity.LiveEventEntity
import com.example.bilimonitor.data.local.entity.NotificationAggregateEntity
import com.example.bilimonitor.data.local.entity.NotificationAggregateEventEntity
import com.example.bilimonitor.data.local.entity.NotificationIdRegistryEntity
import com.example.bilimonitor.data.local.entity.NotificationOutboxEntity
import com.example.bilimonitor.data.local.dao.NotificationAggregateDao
import com.example.bilimonitor.data.local.dao.NotificationIdRegistryDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import com.example.bilimonitor.data.local.db.AppDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class LiveNotificationPayload(
    val eventKey: String,
    val streamerStableId: String?,
    val sessionStableId: String?,
    val eventId: String?,
    val streamerName: String,
    val roomTitle: String?,
    val areaName: String?,
    val url: String?,
    val coverUrl: String?,
    /**
     * 变化类通知（标题/分区）的**变化前**取值，用于渲染成"旧 → 新"。
     * 带默认值：旧的通知 payload 里没有这个键，反序列化仍然正常。
     */
    val previousValue: String? = null
)

@Serializable
data class BatchNotificationPayload(
    val aggregateId: String,
    val windowStartWall: Long,
    val items: List<LiveNotificationPayload>
)

/**
 * 封禁状态变化通知的 payload（2026 复查新增）。
 *
 * 为什么单独一种 payload 而不复用 [LiveNotificationPayload]：封禁与"开播/下播/改标题"是不同的事实
 * —— 它没有直播间标题、没有分区、也没有"这一场播了多久"，硬塞进去只能占用无关字段
 * （roomTitle / previousValue），读起来就是假的。
 */
@Serializable
data class BanNotificationPayload(
    /** 主播名。 */
    val streamerName: String,
    /** 封禁状态的人话说明：无期限封禁 / 封禁至 … / 期限未知（[com.example.bilimonitor.domain.policy.RoomBanPolicy.lockTillLabel]）。 */
    val banLabel: String,
    /** true = 进入封禁；false = 解除封禁。 */
    val entered: Boolean
)

@Serializable
data class SystemProblemPayload(
    val problemKey: String,
    val component: String,
    val errorCode: String,
    val summary: String,
    val recovered: Boolean
)

object NotificationPayloads {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun live(
        eventKey: String, streamerStableId: String?, sessionStableId: String?,
        eventId: String, name: String, title: String?, area: String?,
        url: String?, cover: String?, previousValue: String? = null
    ): String = json.encodeToString(
        LiveNotificationPayload.serializer(),
        LiveNotificationPayload(
            eventKey, streamerStableId, sessionStableId, eventId, name, title, area, url, cover, previousValue
        )
    )

    fun encodeBatch(payload: BatchNotificationPayload): String =
        json.encodeToString(BatchNotificationPayload.serializer(), payload)

    fun decodeBatch(raw: String): BatchNotificationPayload? = runCatching {
        json.decodeFromString(BatchNotificationPayload.serializer(), raw)
    }.getOrNull()

    fun decodeLive(raw: String): LiveNotificationPayload? = runCatching {
        json.decodeFromString(LiveNotificationPayload.serializer(), raw)
    }.getOrNull()

    fun encodeBan(payload: BanNotificationPayload): String =
        json.encodeToString(BanNotificationPayload.serializer(), payload)

    fun decodeBan(raw: String): BanNotificationPayload? = runCatching {
        json.decodeFromString(BanNotificationPayload.serializer(), raw)
    }.getOrNull()

    fun encodeSystem(payload: SystemProblemPayload): String =
        json.encodeToString(SystemProblemPayload.serializer(), payload)

    fun decodeSystem(raw: String): SystemProblemPayload? = runCatching {
        json.decodeFromString(SystemProblemPayload.serializer(), raw)
    }.getOrNull()
}

/**
 * Outbox 写入器。
 *
 * **事务契约**：可以在调用方已开启的 Room 事务内调用（聚合 ↔ Outbox ↔ 事件三条记录同一事务创建，0.6.13）；
 * **不在事务内调用也是安全的** —— notificationId 的"读 MAX + 插入占位"两步已被
 * [getOrCreateNotificationId] 用 `db.withTransaction` 包在一起（Room 的嵌套事务会并入外层事务），
 * 因此不存在"两个入口拿到同一个 candidate"的竞态。
 * （此前 `createSystemProblem` 的三处调用点确实不在事务内，事务边界只写在注释里。）
 */
object NotificationOutboxWriter {

    const val OUTBOX_TTL_MS = 24 * 60 * 60 * 1000L
    const val MAX_NOTIFICATION_ID = 2_000_000

    /**
     * 被免打扰抑制的留痕原因（写进 `notification_outbox.lastError`）。
     *
     * 为什么要有这些行：抑制原先**什么都不留**，于是"昨晚主播开播我没收到"这件事，
     * 在库里无法区分"被免打扰挡了（预期行为）"与"通知丢了（故障）"。
     * 这些行是 `CANCELLED` 终态：不会被投递（`findDue` 只取 PENDING/RETRY_WAIT），
     * 也没有通知历史行（历史行只在被认领投递时创建），所以**不会灌满用户看的历史列表**；
     * 但诊断包取终态行（含 CANCELLED），排查时一眼能看到。
     */
    const val SUPPRESSED_QUIET_HOURS = "QUIET_HOURS"

    /**
     * 变化类事件的 eventKey 格式（唯一真相）。
     *
     * 抽出来是因为**免打扰留痕也要算同一个 key**（再给它加 `suppressed:` 前缀），
     * 两处各写一份格式的话，将来改前缀就会让留痕与真实事件对不上。
     */
    fun changeEventKey(
        eventType: NotificationEventType,
        streamerStableId: String,
        sessionStableId: String?,
        changeKey: String
    ): String = "change:${eventType.name}:$streamerStableId:${sessionStableId ?: "-"}:$changeKey"

    /**
     * 分配重试次数。正常路径一次就成功；反复失败只可能是并发写同一 eventKey
     * 或 id 空间接近耗尽，两者都不该无限重试。
     */
    private const val ALLOCATE_ATTEMPTS = 8

    /** 线性探测兜底的上限：只在分配连续冲突时才走到，避免"探测到 200 万"这种病态循环。 */
    private const val PROBE_LIMIT = 5000

    /**
     * notificationId 分配（0.6.12）：同一 eventKey 永远复用同一个 id，区间耗尽返回失败。
     *
     * 实现要点：
     *  - 先查 eventKey（绝大多数调用是重复事件，命中最快）；
     *  - 未命中则在**一个事务内**"读 `MAX+1` → `INSERT OR IGNORE` 占位 → 复查"，
     *    冲突就换下一个候选（`MAX` 会随并发插入变大，因此每轮都在前进）；
     *  - 最后才用带 `isTakenByOther` 的**有界**线性探测兜底。
     *
     * 原实现在兜底分支里 `allIds()` 全表装载成 Set —— 在没有外层事务的调用点
     * （系统问题通知）这是一次全表扫描，且此后仍要逐个 INSERT 探测，代价随通知量线性增长。
     */
    suspend fun getOrCreateNotificationId(
        db: AppDatabase,
        eventKey: String,
        now: Long
    ): Result<Int> {
        val registryDao = db.notificationIdRegistryDao()
        registryDao.findByEventKey(eventKey)?.let { return Result.success(it.notificationId) }

        db.withTransaction { allocateWithin(registryDao, eventKey, now) }
            ?.let { return Result.success(it) }

        var id = 1
        while (id <= MAX_NOTIFICATION_ID && id <= PROBE_LIMIT) {
            if (!registryDao.isTakenByOther(id, eventKey)) {
                if (registryDao.insertOrIgnore(NotificationIdRegistryEntity(eventKey, id, now)) != -1L) {
                    return Result.success(id)
                }
                registryDao.findByEventKey(eventKey)?.let { return Result.success(it.notificationId) }
            }
            id++
        }
        // ★ 分配失败必须留痕（复查发现的缺陷）：四个调用点全都是 `getOrNull() ?: return`，
        //   于是"id 空间耗尽 ⇒ 这条通知彻底消失"在整条链路上不留任何痕迹 ——
        //   outbox、history、application_error_log 一行都没有，用户报"某次开播没通知"时无从查起。
        runCatching {
            db.logDao().insertAppError(
                com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                    errorId = Ids.newId(), operationId = null, occurredAt = now,
                    errorCode = com.example.bilimonitor.data.local.AppError.NOTIFICATION_UNAVAILABLE,
                    detail = ("notificationId 分配失败（空间耗尽或连续冲突），该事件未入队：" +
                        eventKey).take(500)
                )
            )
        }
        return Result.failure(IllegalStateException("notificationId 空间耗尽"))
    }

    /** 事务内分配：返回分配到的 id，null 表示连续冲突（交给调用方走兜底探测）。 */
    private suspend fun allocateWithin(
        registryDao: NotificationIdRegistryDao,
        eventKey: String,
        now: Long
    ): Int? {
        repeat(ALLOCATE_ATTEMPTS) {
            // 每轮先复查：冲突常常来自"同一 eventKey 刚被并发写入"，那种情况下复用它才是正确结果。
            registryDao.findByEventKey(eventKey)?.let { return it.notificationId }
            val candidate = registryDao.nextCandidate()
            if (candidate > MAX_NOTIFICATION_ID) return null
            // Room 的 @Insert(IGNORE) 在冲突时可靠地返回 -1（与 @Query INSERT 不同）。
            if (registryDao.insertOrIgnore(NotificationIdRegistryEntity(eventKey, candidate, now)) != -1L) {
                return candidate
            }
        }
        return null
    }

    /** START / LIVE_RECONFIRMED 的通知路径：聚合或单事件（0.6.10 / 233）。 */
    suspend fun createForEvent(
        db: AppDatabase,
        clock: AppClock,
        event: LiveEventEntity,
        streamerId: Long,
        payloadJson: String,
        policy: StreamerNotificationPolicy,
        configVersion: Long,
        now: Long,
        eventType: NotificationEventType = NotificationEventType.START_CONFIRMED
    ) {
        if (!policy.notifyStart) return
        // 免打扰时段（原规范 22.3）：抑制开播类通知，但**不影响状态与历史记录** ——
        // 场次、事件、状态历史照常提交，只是不打扰用户。
        if (policy.quietHoursActive) {
            // ★ 抑制也要留痕（2026 复查）：只写 Outbox 终态行、不建历史行，见 recordSuppressed。
            recordSuppressed(
                db = db, eventKey = Ids.eventKeyForEvent(event.eventId),
                streamerId = streamerId, sourceEventId = event.eventId,
                eventType = eventType, payloadJson = payloadJson, now = now,
                reason = SUPPRESSED_QUIET_HOURS, configVersion = configVersion
            )
            return
        }
        if (policy.aggregationEnabled && policy.aggregationThreshold > 1) {
            bindOrCreateAggregate(db, clock, event, streamerId, payloadJson, policy, configVersion, now, eventType)
        } else {
            createSingle(
                db = db, clock = clock, event = event, streamerId = streamerId,
                eventType = eventType,
                payloadJson = payloadJson, now = now, enabled = true, configVersion = configVersion
            )
        }
    }

    private suspend fun bindOrCreateAggregate(
        db: AppDatabase,
        clock: AppClock,
        event: LiveEventEntity,
        streamerId: Long,
        payloadJson: String,
        policy: StreamerNotificationPolicy,
        configVersion: Long,
        now: Long,
        eventType: NotificationEventType = NotificationEventType.START_CONFIRMED
    ) {
        val aggDao = db.notificationAggregateDao()
        val outboxDao = db.notificationOutboxDao()
        val bootId = clock.bootId()
        var agg: NotificationAggregateEntity? = aggDao.findInProgress(bootId)
        if (agg == null) {
            val windowMs = policy.aggregationWindowSeconds * 1000L
            val candidate = NotificationAggregateEntity(
                aggregateId = Ids.stableId("agg"),
                createdAt = now,
                windowStartWall = now,
                windowEndWall = now + windowMs,
                windowStartElapsed = clock.nowElapsed(),
                windowEndElapsed = clock.nowElapsed() + windowMs,
                bootId = bootId,
                threshold = policy.aggregationThreshold,
                eventCount = 0,
                status = NotificationAggregateStatus.COLLECTING,
                sentAt = null,
                expiresAt = null,
                configVersion = configVersion
            )
            val inserted = try {
                aggDao.insert(candidate); true
            } catch (e: Exception) {
                false
            }
            agg = if (inserted) candidate else aggDao.findInProgress(bootId)
        }
        val aggregate = agg ?: run {
            createSingle(db, clock, event, streamerId, eventType, payloadJson, now, true, configVersion)
            return
        }
        val bound = aggDao.bindEvent(
            NotificationAggregateEventEntity(
                bindingId = Ids.newId(),
                aggregateId = aggregate.aggregateId,
                eventId = event.eventId,
                active = 1,
                bindingStatus = AggregateEventBindingStatus.BOUND,
                boundAt = now,
                releasedAt = null
            )
        )
        if (bound == -1L) {
            // 事件已在其他进行中窗口内（异常防御）：回退单事件路径。
            createSingle(db, clock, event, streamerId, eventType, payloadJson, now, true, configVersion)
            return
        }
        val count = aggDao.countActiveEvents(aggregate.aggregateId)
        if (count >= aggregate.threshold) {
            // 达到阈值：冻结窗口 + Batch Outbox 同事务创建（0.6.10）。
            aggDao.casStatus(aggregate.aggregateId, aggregate.status, NotificationAggregateStatus.FROZEN, null, count)
            if (!outboxDao.existsForAggregate(aggregate.aggregateId)) {
                createBatchOutbox(db, clock, aggregate.aggregateId, now, configVersion)
            }
        } else {
            aggDao.casStatus(aggregate.aggregateId, aggregate.status, NotificationAggregateStatus.COLLECTING, null, count)
        }
    }

    suspend fun createBatchOutbox(
        db: AppDatabase,
        clock: AppClock,
        aggregateId: String,
        now: Long,
        configVersion: Long
    ) {
        val aggDao = db.notificationAggregateDao()
        val outboxDao = db.notificationOutboxDao()
        val eventDao = db.liveEventDao()
        val streamerDao = db.streamerDao()
        val aggregate = aggDao.findById(aggregateId) ?: return
        if (outboxDao.existsForAggregate(aggregateId)) return
        val bindings = aggDao.listActiveEvents(aggregateId)
        val items = bindings.mapNotNull { b ->
            val event = eventDao.findById(b.eventId) ?: return@mapNotNull null
            val streamer = streamerDao.findById(event.streamerId) ?: return@mapNotNull null
            LiveNotificationPayload(
                eventKey = Ids.eventKeyForEvent(event.eventId),
                streamerStableId = streamer.stableId,
                sessionStableId = event.sessionStableId,
                eventId = event.eventId,
                streamerName = streamer.name,
                roomTitle = streamer.roomTitle,
                areaName = streamer.areaName,
                url = streamer.liveUrl,
                coverUrl = streamer.coverUrl
            )
        }
        val payload = NotificationPayloads.encodeBatch(
            BatchNotificationPayload(aggregateId, aggregate.windowStartWall, items)
        )
        val eventKey = Ids.eventKeyForAggregate(aggregateId)
        val notificationId = getOrCreateNotificationId(db, eventKey, now)
            .getOrNull() ?: return
        outboxDao.insert(
            NotificationOutboxEntity(
                outboxId = Ids.newId(),
                eventKey = eventKey,
                sourceEventId = null,
                streamerId = null,
                aggregateId = aggregateId,
                problemKey = null,
                notificationId = notificationId,
                eventType = NotificationEventType.BATCH_LIVE,
                payloadJson = payload,
                status = NotificationOutboxStatus.PENDING,
                nextAttemptAt = now,
                attemptCount = 0,
                createdAt = now,
                expiresAt = maxOf(now, aggregate.windowEndWall) + OUTBOX_TTL_MS,
                processingStartedAt = null,
                leaseUntilWall = null,
                leaseUntilElapsed = null,
                leaseBootId = null,
                workerInstanceId = null,
                deliveryAttemptId = null,
                sentAt = null,
                lastError = null,
                configVersion = configVersion
            )
        )
    }

    /**
     * 记录一条"**被抑制、不会投递**"的通知（2026 复查新增）。
     *
     * 用途：免打扰期间本来该发的通知。写 `CANCELLED` 终态行 + `lastError = reason`，
     * 让"为什么没收到"可查（原先一行都不留，故障与预期行为无法区分）。
     *
     * ★ eventKey 会加 `suppressed:` 前缀：这一行只是留痕，**绝不能占用真实事件的 eventKey** ——
     *   否则同一变化在免打扰结束后再次出现时，`createForChange` / `createSingle` 的幂等检查
     *   会命中这一行，于是"该发的反而不发了"。
     *
     * 不做投递尝试（attemptCount 恒为 0、nextAttemptAt 为 null）：它本来就不该被发出去。
     */
    suspend fun recordSuppressed(
        db: AppDatabase,
        eventKey: String,
        streamerId: Long?,
        sourceEventId: String?,
        eventType: NotificationEventType,
        payloadJson: String,
        now: Long,
        reason: String,
        configVersion: Long = 0
    ) {
        val outboxDao = db.notificationOutboxDao()
        val key = "suppressed:$eventKey"
        if (outboxDao.findByEventKey(key) != null) return
        val notificationId = getOrCreateNotificationId(db, key, now).getOrNull() ?: return
        outboxDao.insert(
            NotificationOutboxEntity(
                outboxId = Ids.newId(),
                eventKey = key,
                sourceEventId = sourceEventId,
                streamerId = streamerId,
                aggregateId = null,
                problemKey = null,
                notificationId = notificationId,
                eventType = eventType,
                payloadJson = payloadJson,
                status = NotificationOutboxStatus.CANCELLED,
                nextAttemptAt = null,
                attemptCount = 0,
                createdAt = now,
                expiresAt = now + OUTBOX_TTL_MS,
                processingStartedAt = null,
                leaseUntilWall = null,
                leaseUntilElapsed = null,
                leaseBootId = null,
                workerInstanceId = null,
                deliveryAttemptId = null,
                sentAt = null,
                lastError = reason,
                configVersion = configVersion
            )
        )
    }

    /** 单事件 Outbox：eventKey = evt:{eventId}，sourceEventId 幂等锚点（0.6.9.1）。 */
    suspend fun createSingle(
        db: AppDatabase,
        clock: AppClock,
        event: LiveEventEntity,
        streamerId: Long,
        eventType: NotificationEventType,
        payloadJson: String,
        now: Long,
        enabled: Boolean,
        configVersion: Long = 0
    ) {
        if (!enabled) return
        val outboxDao = db.notificationOutboxDao()
        if (outboxDao.existsForSourceEvent(event.eventId)) return
        val eventKey = Ids.eventKeyForEvent(event.eventId)
        val notificationId = getOrCreateNotificationId(db, eventKey, now)
            .getOrNull() ?: return
        outboxDao.insert(
            NotificationOutboxEntity(
                outboxId = Ids.newId(),
                eventKey = eventKey,
                sourceEventId = event.eventId,
                streamerId = streamerId,
                aggregateId = null,
                problemKey = null,
                notificationId = notificationId,
                eventType = eventType,
                payloadJson = payloadJson,
                status = NotificationOutboxStatus.PENDING,
                nextAttemptAt = now,
                attemptCount = 0,
                createdAt = now,
                expiresAt = now + OUTBOX_TTL_MS,
                processingStartedAt = null,
                leaseUntilWall = null,
                leaseUntilElapsed = null,
                leaseBootId = null,
                workerInstanceId = null,
                deliveryAttemptId = null,
                sentAt = null,
                lastError = null,
                configVersion = configVersion
            )
        )
    }

    /**
     * 变化类通知（直播标题变化 / 直播分区变化）。
     *
     * 与开播/关播通知不同，**不写入 `live_event`**：事件表承载的是"直播状态转换"这一业务事实，
     * 而改标题/换分区只是直播过程中的元数据变化，混进去会污染事件序列、状态历史与统计口径。
     * 这里直接按自己的 eventKey 写 Outbox（`notification_outbox` 的三个来源列
     * `sourceEventId / aggregateId / problemKey` 本来就都可为空）。
     *
     * ★ 复用范围（2026 复查起）：**封禁/解封通知**也走这个函数（`sessionStableId` 传 null、
     *   `eventType` 传 BAN_ENTERED / BAN_LIFTED）。同样不写 live_event，理由同上 ——
     *   封禁不是场次事件，没有场次可挂。三类事件的 eventKey 前缀一致（都是 `change:`），
     *   通知历史的类别文案按 `change:<枚举名>:` 分流。
     *
     * @param changeKey 变化内容的标识（新值），与主播、场次、类型一起构成 eventKey → 天然幂等：
     *                  同一场次里的同一个新标题只会通知一次。
     */
    suspend fun createForChange(
        db: AppDatabase,
        streamerId: Long,
        streamerStableId: String,
        sessionStableId: String?,
        eventType: NotificationEventType,
        changeKey: String,
        payloadJson: String,
        now: Long,
        configVersion: Long
    ) {
        val eventKey = changeEventKey(eventType, streamerStableId, sessionStableId, changeKey)
        val outboxDao = db.notificationOutboxDao()
        if (outboxDao.findByEventKey(eventKey) != null) return
        val notificationId = getOrCreateNotificationId(db, eventKey, now)
            .getOrNull() ?: return
        outboxDao.insert(
            NotificationOutboxEntity(
                outboxId = Ids.newId(),
                eventKey = eventKey,
                sourceEventId = null,
                streamerId = streamerId,
                aggregateId = null,
                problemKey = null,
                notificationId = notificationId,
                eventType = eventType,
                payloadJson = payloadJson,
                status = NotificationOutboxStatus.PENDING,
                nextAttemptAt = now,
                attemptCount = 0,
                createdAt = now,
                expiresAt = now + OUTBOX_TTL_MS,
                processingStartedAt = null,
                leaseUntilWall = null,
                leaseUntilElapsed = null,
                leaseBootId = null,
                workerInstanceId = null,
                deliveryAttemptId = null,
                sentAt = null,
                lastError = null,
                configVersion = configVersion
            )
        )
    }

    /**
     * 系统问题 Outbox：`sourceEventId / aggregateId / problemKey` 三个来源列里只填 problemKey。
     *
     * 注意：**数据库里并没有"三形态互斥"的 CHECK 约束**（实测 `sqlite_master` 中该表只有 4 个外键，
     * 三个来源列都是可空普通列；`createForChange` 正是合法地三列全空）。
     * 这条约束只存在于调用约定里，不要在代码或文档里把它当成数据库保证。
     */
    suspend fun createSystemProblem(
        db: AppDatabase,
        clock: AppClock,
        problemKey: String,
        eventType: NotificationEventType,
        payloadJson: String,
        now: Long,
        configVersion: Long = 0
    ) {
        val outboxDao = db.notificationOutboxDao()
        // 同一 problemKey 的"出现问题"与"已恢复"是两个通知事件：
        // 恢复事件 eventKey 追加 :recovered，避免被 eventKey 幂等检查拦截。
        val eventKey = if (eventType == NotificationEventType.SYSTEM_RECOVERED) {
            Ids.eventKeyForProblem(problemKey) + ":recovered"
        } else {
            Ids.eventKeyForProblem(problemKey)
        }
        if (outboxDao.findByEventKey(eventKey) != null) return
        val notificationId = getOrCreateNotificationId(db, eventKey, now)
            .getOrNull() ?: return
        outboxDao.insert(
            NotificationOutboxEntity(
                outboxId = Ids.newId(),
                eventKey = eventKey,
                sourceEventId = null,
                streamerId = null,
                aggregateId = null,
                problemKey = problemKey,
                notificationId = notificationId,
                eventType = eventType,
                payloadJson = payloadJson,
                status = NotificationOutboxStatus.PENDING,
                nextAttemptAt = now,
                attemptCount = 0,
                createdAt = now,
                expiresAt = now + OUTBOX_TTL_MS,
                processingStartedAt = null,
                leaseUntilWall = null,
                leaseUntilElapsed = null,
                leaseBootId = null,
                workerInstanceId = null,
                deliveryAttemptId = null,
                sentAt = null,
                lastError = null,
                configVersion = configVersion
            )
        )
    }
}
