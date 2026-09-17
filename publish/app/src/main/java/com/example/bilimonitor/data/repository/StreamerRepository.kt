package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.GapReason
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.ReliableIntervalInvalidReason
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.MonitoringGapDao
import com.example.bilimonitor.data.local.dao.NotificationOutboxDao
import com.example.bilimonitor.data.local.dao.PolicyDao
import com.example.bilimonitor.data.local.dao.ReliableIntervalDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.dao.TaxonomyDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.local.entity.StreamerMonitorPolicyEntity
import com.example.bilimonitor.data.remote.bilibili.BiliLiveDataSource
import com.example.bilimonitor.domain.model.BatchStatusOutcome
import com.example.bilimonitor.domain.policy.BanProbeOutcome
import com.example.bilimonitor.domain.policy.CallFailure
import com.example.bilimonitor.domain.policy.RoomBanPolicy
import com.example.bilimonitor.domain.policy.RoomBanProbeResult
import com.example.bilimonitor.domain.policy.RoomBanState
import com.example.bilimonitor.domain.policy.RoomFailureReason
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AddStreamerResult {
    data class Added(val streamer: StreamerEntity) : AddStreamerResult
    data class Restored(val streamer: StreamerEntity) : AddStreamerResult
    data object AlreadyExists : AddStreamerResult
    data class NotFound(val reason: String? = null) : AddStreamerResult
    data class Invalid(val reason: String) : AddStreamerResult

    /**
     * 添加**成功**，但该直播间当前**已被封禁**（产品决策：允许添加已封禁的直播间）。
     *
     * 为什么仍然单独一个分支、不并进 [Added]：封禁是一个**确定的事实**
     * （`room_init.data.is_locked` 或批量响应自带的 `is_locked`），界面必须把它说出来 ——
     * 用户需要知道"加进来了，但它现在不会开播、也不会被误报成开播/下播"。
     * 并进 [Added] 只会让提示退化成一句"已添加"，与"任何降级都不允许静默"的项目原则冲突。
     *
     * 语义上它是**成功**：主播行已落库、审计已写过、封禁标记也已写在那一行上
     * （见 [StreamerRepository.addStreamer] 的顺序说明），引擎的封禁复查会自动接管。
     *
     * @param lockTill 封禁期限：null/0 = 期限未知，负数 = 无期限，> 0 = 到期时刻（毫秒）。
     * @param message 可直接展示的成功提示，由 [RoomBanPolicy.addSuccessMessage] 生成 ——
     *                与落库、调度用的是同一份 `lock_till` 解析。
     */
    data class AddedBanned(
        val streamer: StreamerEntity,
        val lockTill: Long?,
        val message: String
    ) : AddStreamerResult
}

@Singleton
class StreamerRepository @Inject constructor(
    private val db: AppDatabase,
    private val streamerDao: StreamerDao,
    private val taxonomyDao: TaxonomyDao,
    private val policyDao: PolicyDao,
    private val reliableIntervalDao: ReliableIntervalDao,
    private val monitoringGapDao: MonitoringGapDao,
    private val outboxDao: NotificationOutboxDao,
    private val logDao: LogDao,
    private val dataSource: BiliLiveDataSource,
    private val clock: AppClock,
    /**
     * 封禁标记的唯一写入入口（[MonitorRepository.applyBanState]）。
     *
     * 为什么不直接调 `streamerDao.updateBanState`：封禁状态必须**独立于**确认状态机，
     * 那条 SQL 的独立性契约（不碰 confirmedLiveStatus / 观察序号 / 代际 / 历史场次 / 通知）
     * 由 applyBanState 集中守护；在添加路径上手写第二份写入就等于把契约抄成两份，
     * 日后改一处漏一处。仓储之间互相注入在本项目是既有做法（BackupRepository 注入 6 个仓储）。
     */
    private val monitorRepository: MonitorRepository
) {
    fun observeStreamers(query: String?): Flow<List<StreamerEntity>> =
        if (query.isNullOrBlank()) streamerDao.observeActive() else streamerDao.searchActive(query.trim())

    fun observeByTag(tagId: Long): Flow<List<StreamerEntity>> = streamerDao.observeByTag(tagId)

    /** 标签 + 关键词组合筛选（两者同时生效，避免搜索框在标签筛选下失效）。 */
    fun observeByTag(tagId: Long, query: String?): Flow<List<StreamerEntity>> =
        if (query.isNullOrBlank()) streamerDao.observeByTag(tagId)
        else streamerDao.observeByTagAndQuery(tagId, query.trim())

    /** 分组 + 关键词组合筛选（与标签同构，原规范 16）。 */
    fun observeByGroup(groupId: Long, query: String?): Flow<List<StreamerEntity>> =
        if (query.isNullOrBlank()) streamerDao.observeByGroup(groupId)
        else streamerDao.observeByGroupAndQuery(groupId, query.trim())

    /**
     * 多标签筛选（用户要求：标签那一行支持同级多选）。
     *
     * [requireAll] 是**语义开关**，两种都合理、都已在 DAO 备好，由上层（HomeViewModel）决定，
     * 这个仓储不替产品拍板：
     *  - `false` = 并集：命中任一选中标签即入选（"看看这几个标签里都有谁"）
     *  - `true`  = 交集：必须同时具备全部选中标签（"同时属于这几个标签的"）
     *
     * 空列表的兜底放在这里而不是 DAO：Room 对空 `IN (:tagIds)` 会生成 `IN ()`，
     * SQLite 直接报语法错；而"一个标签都没选"本来就等于"不加标签条件"。
     */
    fun observeByTags(tagIds: List<Long>, query: String?, requireAll: Boolean): Flow<List<StreamerEntity>> {
        if (tagIds.isEmpty()) return observeStreamers(query)
        val q = query.orEmpty().trim()
        return if (requireAll) streamerDao.observeByAllTags(tagIds, tagIds.size, q)
        else streamerDao.observeByAnyTag(tagIds, q)
    }

    /**
     * 分组 ∧ 多标签组合筛选（用户要求：分组不可同级多选，但可以和标签多选叠加）。
     *
     * 分组侧恒为单选（[groupId]），标签侧多选；两者是 AND 关系 —— 先落在该分组里、
     * 再满足标签条件。标签为空时退化成既有的分组筛选，不会变成空结果。
     */
    fun observeByGroupAndTags(
        groupId: Long,
        tagIds: List<Long>,
        query: String?,
        requireAll: Boolean
    ): Flow<List<StreamerEntity>> {
        if (tagIds.isEmpty()) return observeByGroup(groupId, query)
        val q = query.orEmpty().trim()
        return if (requireAll) streamerDao.observeByGroupAndAllTags(groupId, tagIds, tagIds.size, q)
        else streamerDao.observeByGroupAndAnyTag(groupId, tagIds, q)
    }

    /**
     * 添加主播（原规范 54 / 249.10.0）：UID/房间号/链接解析 → 远程校验 → 落库。
     * 已软删除的同 UID 走"恢复原实体"路径，不得先删除后新增。
     */
    suspend fun addStreamer(input: String, mode: InputMode = InputMode.ROOM): AddStreamerResult {
        val parsed = parseStreamerInput(input, mode)
            ?: return AddStreamerResult.Invalid("请填写有效的${mode.label}（纯数字）或直播间链接")
        val resolved = resolveUid(parsed) ?: return AddStreamerResult.NotFound(
            "按${mode.label}「${input.trim()}」没有找到对应主播；" +
                "可切换为${if (mode == InputMode.ROOM) InputMode.UID.label else InputMode.ROOM.label}再试，或直接粘贴直播间链接"
        )

        val existing = streamerDao.findByUid(resolved)
        val now = clock.nowWall()
        if (existing != null) {
            if (existing.deletedAt != null) {
                db.withTransaction {
                    streamerDao.restore(existing.id, now)
                    streamerDao.bumpStreamerMonitorGeneration(existing.id, now)
                    streamerDao.deletePendingTransition(existing.id)
                    logDao.insertAudit(
                        AuditLogEntity(
                            auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                            action = "RESTORE_STREAMER", targetType = "streamer",
                            targetStableId = existing.stableId, occurredAt = now, detailJson = null
                        )
                    )
                }
                return AddStreamerResult.Restored(streamerDao.findById(existing.id)!!)
            }
            return AddStreamerResult.AlreadyExists
        }

        // 远程校验 + 取基础资料（249.9.1：拿不到的字段保持 NULL，昵称缺失用占位文案）。
        //
        // ★ 失败原因不再被压成一句"远程请求失败"（用户实测反馈：封禁房间添加时提示误导）。
        //   原因分类由数据源给出（超时/网络/限流/5xx/业务拒绝/解析失败），
        //   封禁则由批量响应自带的 `is_locked` 或**一次** `room_init` 探针精确确认。
        val outcome = runCatching { dataSource.getRoomStatus(listOf(resolved)) }.getOrNull()
        val remote = (outcome as? BatchStatusOutcome.Responded)?.response
        val info = remote?.resultsByUid?.get(resolved)

        // ---- 「已被封禁」**允许添加**（产品决策，取代原先的拒绝添加）----
        //
        // 为什么要允许：封禁是**临时**的（用户实测那个房间后来解封了），而"拒绝添加"
        // 等于把"暂时封禁"当成"永久不可用" —— 用户既加不进来，也拿不到任何后续复查，
        // 只能靠反复手动重试去猜它什么时候解封。
        // 加进来之后由引擎的封禁复查自动接管（默认 30 分钟一次，解封后自动恢复常规监控）。
        //
        // 判定依旧只认**明确证据**，规则全部来自 RoomBanPolicy（纯函数、有单测）：
        //  ① 批量响应自带 `is_locked = true` → 零额外请求直接判定；
        //  ② 批量接口拿不到该 uid 的 info（实测封禁房间往往整批报错或被裁剪掉）→
        //     花**一次** `room_init` 探针，把它与"超时/限流/5xx/接口拒绝/解析失败"区分开。
        //     ★ 探针拿不到结论 ⇒ **不是封禁** ⇒ 仍然按原分类返回失败：
        //       风控（-352）、超时、5xx 一律照旧如实归类，绝不说成"封禁"。
        val config = runCatching { db.configDao().getConfig() }.getOrNull()
        // 与引擎同源：MonitoringConfigSnapshot.banRecheckIntervalSeconds 也是这个字段，
        // 因此提示里的"较低频率"与实际调度不会各说各话。
        val recheckSeconds = config?.banRecheckIntervalSeconds ?: RoomBanPolicy.DEFAULT_RECHECK_SECONDS
        // 探针带回来的真实房间号（`room_init` 的 id 参数可能收到的是 uid）。
        var probeRoomId: Long? = null
        // 非空 = 该直播间确实被封禁，本次添加走"照常建流 + 写封禁标记"路径。
        var banState: RoomBanState? = null
        if (info == null) {
            // 探针现在回答两种事：「拿到了结论」或「为什么没拿到」（[BanProbeOutcome]）。
            // 添加路径只关心**拿到结论且 is_locked = true** 那一种；失败与"明确未封禁"一律
            // 按原来的"未找到"处理 —— 判定语义**逐字未变**，变的只是失败原因不再被压成含糊的 null
            // （它现在带着失败分类回到引擎，由引擎按真实分类记错误码）。
            val probe = runCatching { dataSource.probeBan(probeIdOf(parsed, resolved)) }
                .getOrNull()
                ?.let { (it as? BanProbeOutcome.Concluded)?.result }
            // 显式判空（而不是 `probe?.isLocked != true`）：让下面的 probe.roomId 拿到确定的非空类型，
            // 不依赖编译器的安全调用智能转换。
            if (probe == null || !probe.isLocked) {
                return AddStreamerResult.NotFound(
                    addFailureReason(parsed, (outcome as? BatchStatusOutcome.Failed)?.failure)
                )
            }
            probeRoomId = probe.roomId
            banState = RoomBanPolicy.nextState(
                previous = null,
                probe = probe,
                nowWall = now,
                recheckIntervalSeconds = recheckSeconds
            )
        } else if (info.banLocked == true) {
            // 批量响应自带封禁标记：零额外请求，采信规则与 MonitoringEngine 用的是同一份实现。
            // 注意 `is_locked = false` 时这里**不会**命中（`banLocked` 可能就是 false）：
            // 那是"房间没被封"，即使主播**账号**被封（B 站错误码 -102 是账号级封停，
            // 与房间级 is_locked 是两件事），添加也照常进行 —— 不把账号级封停并进房间封禁语义。
            banState = RoomBanPolicy.nextState(
                previous = null,
                probe = RoomBanProbeResult(
                    isLocked = true,
                    lockTill = info.banLockTillMillis,
                    isHidden = info.hidden,
                    apiCode = null,
                    apiMessage = "batch_flags"
                ),
                nowWall = now,
                recheckIntervalSeconds = recheckSeconds
            )
        }
        val placeholder = config?.placeholderText ?: "[待获取]"
        val name = info?.name?.takeIf { it.isNotBlank() } ?: placeholder
        // 房间号只取**服务端回给我们的**值：批量响应 → 探针响应（`room_init` 的 id 参数
        // 既接受房间号也接受 uid，所以用户输入的那个数字**不能**当作房间号落库 ——
        // resolveUid 在主路径失败时会换一种解释重试，输入的数字可能其实是 uid）。
        // 两个来源都拿不到就保持 NULL（249.9.1：拿不到就 NULL，不写占位值）。
        // 为什么这里要尽力拿：引擎的封禁复查只按 `roomId ?: shortRoomId` 发探针
        // （见 MonitoringEngine.refreshDueBanProbes），roomId 为 NULL 就永远复查不了 ——
        // 该主播会被永久跳过常规检查、封禁也永不解除，变成一个"加了却再也不检查"的僵尸行。
        val roomId = info?.roomId ?: probeRoomId

        val entity = StreamerEntity(
            stableId = Ids.stableId("str"),
            uid = resolved,
            roomId = roomId,
            shortRoomId = info?.shortRoomId,
            name = name,
            nameLocked = info?.name == null,
            avatarUrl = info?.avatarUrl,
            roomTitle = info?.roomTitle,
            parentAreaName = info?.parentAreaName,
            areaName = info?.areaName,
            coverUrl = info?.coverUrl,
            liveUrl = info?.liveUrl,
            confirmedLiveStatus = com.example.bilimonitor.data.local.ConfirmedLiveStatus.UNKNOWN,
            lastObservationResult = ObservationResult.INVALID,
            observationSequence = 0,
            streamerMonitorGeneration = 0,
            monitoringEnabled = true,
            isFavorite = false,
            monitorPolicyStableId = null,
            lastCheckedAt = null,
            lastConfirmedAt = null,
            freshnessStatus = DataFreshness.UNKNOWN,
            lastLiveStartedAt = null,
            lastLiveEndedAt = null,
            lastError = null,
            deletedAt = null,
            createdAt = now,
            updatedAt = now
        )
        val id = db.withTransaction {
            val newId = streamerDao.insert(entity)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "ADD_STREAMER", targetType = "streamer",
                    targetStableId = entity.stableId, occurredAt = now, detailJson = null
                )
            )
            newId
        }
        // ★ 顺序保证：封禁标记必须在**主播行已经建好之后**才写。
        //   updateBanState 是"按 streamerId 更新既有行"的单列 UPDATE，行还不存在时它只会
        //   得到一次 rows = 0 的空更新 —— 标记静默丢失 ⇒ 引擎不会跳过常规检查、也不会用
        //   30 分钟复查接管它，用户看到的就成了"加了个被封禁的主播却一直按常规频率白问"。
        //   因此：先 insert（含审计）→ 再 applyBanState → 最后回读整行作为返回值。
        //
        // 已知残留边界（如实记录，本方法内不解决）：极端情况下上游可能给出 is_locked 却缺
        // room_id（字段被裁剪），此时实体 roomId 为 NULL，而引擎的复查只按 `roomId ?: shortRoomId`
        // 发探针 —— 这条标记将无法自动解除（保守方向：宁可永不自动解除，也不误报开播）。
        // 该边界与引擎既有的"批量标记零请求判定"路径同源（那条路径同样可能在 roomId 为 NULL
        // 时写标记），要彻底修应修在引擎侧；这里不引入"有时不写标记"的第三种行为。
        val ban = banState
        if (ban != null) monitorRepository.applyBanState(id, ban)
        val saved = streamerDao.findById(id)!!
        return if (ban != null) {
            AddStreamerResult.AddedBanned(
                streamer = saved,
                lockTill = ban.lockTill,
                message = RoomBanPolicy.addSuccessMessage(saved.name, ban.lockTill, recheckSeconds, now)
            )
        } else {
            AddStreamerResult.Added(saved)
        }
    }

    /** 软删除（0.6.22.1）：单事务完成全部伴随变更，不发送关播通知。 */
    suspend fun softDelete(streamerId: Long) {
        val now = clock.nowWall()
        db.withTransaction {
            val streamer = streamerDao.findById(streamerId) ?: return@withTransaction
            streamerDao.softDelete(streamerId, now)
            streamerDao.bumpStreamerMonitorGeneration(streamerId, now)
            streamerDao.deletePendingTransition(streamerId)
            reliableIntervalDao.invalidateActiveByStreamer(
                streamer.stableId, ReliableIntervalInvalidReason.STREAMER_DELETED, now
            )
            // ACTIVE OPEN → ABANDONED（endTime 保持 NULL）。
            val open = db.liveSessionDao().findActiveOpenSession(streamerId)
            if (open != null) db.liveSessionDao().abandon(open.id, now)
            // 关闭该主播的盲区；若已无其他未恢复主播，父行一并关闭（否则父行永久 endTime IS NULL，
            // 受 scope 唯一约束会被后续故障复用，见 MonitorRepository 同一处理）。
            val gapIds = monitoringGapDao.openGapIdsForStreamer(streamer.stableId)
            monitoringGapDao.closeStreamerGap(streamer.stableId, now)
            for (gapId in gapIds) {
                if (!monitoringGapDao.hasOpenStreamerGapForGap(gapId)) {
                    monitoringGapDao.close(gapId, now)
                }
            }
            // 未发送的 Outbox 置 CANCELLED；已发送与历史保留。
            outboxDao.cancelPendingByStreamer(streamerId)
            streamerDao.bumpSourceDataVersion(now)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "SOFT_DELETE_STREAMER", targetType = "streamer",
                    targetStableId = streamer.stableId, occurredAt = now, detailJson = null
                )
            )
        }
    }

    suspend fun setFavorite(streamerId: Long, favorite: Boolean) {
        streamerDao.setFavorite(streamerId, favorite, clock.nowWall())
    }

    /** 批量操作（原规范 53）：幂等，单事务完成。 */
    suspend fun batchSetFavorite(streamerIds: List<Long>, favorite: Boolean) {
        val now = clock.nowWall()
        db.withTransaction {
            streamerIds.forEach { streamerDao.setFavorite(it, favorite, now) }
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = if (favorite) "BATCH_FAVORITE" else "BATCH_UNFAVORITE",
                    targetType = "streamer", targetStableId = streamerIds.joinToString(","),
                    occurredAt = now, detailJson = null
                )
            )
        }
    }

    suspend fun batchSetMonitoringEnabled(streamerIds: List<Long>, enabled: Boolean) {
        val now = clock.nowWall()
        db.withTransaction {
            for (id in streamerIds) {
                val s = streamerDao.findById(id) ?: continue
                if (s.monitoringEnabled == enabled) continue
                streamerDao.setMonitoringEnabled(id, enabled, now)
                // 重新纳入监控需递增代际（0.6.16.1 第 5 条第 3 项）；暂停只失效区间
                streamerDao.bumpStreamerMonitorGeneration(id, now)
                streamerDao.deletePendingTransition(id)
                if (!enabled) {
                    reliableIntervalDao.invalidateActiveByStreamer(s.stableId, ReliableIntervalInvalidReason.MANUAL_STOP, now)
                    val open = db.liveSessionDao().findActiveOpenSession(id)
                    if (open != null) db.liveSessionDao().abandon(open.id, now)
                }
            }
            streamerDao.bumpSourceDataVersion(now)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = if (enabled) "BATCH_RESUME_MONITOR" else "BATCH_PAUSE_MONITOR",
                    targetType = "streamer", targetStableId = streamerIds.joinToString(","),
                    occurredAt = now, detailJson = null
                )
            )
        }
    }

    suspend fun batchSoftDelete(streamerIds: List<Long>) {
        streamerIds.forEach { softDelete(it) }
    }

    /** 关注导入专用：直接落库（资料来自账号接口，不再远程校验）。 */
    suspend fun importStreamer(uid: Long, name: String, avatarUrl: String?): AddStreamerResult {
        val existing = streamerDao.findByUid(uid)
        val now = clock.nowWall()
        if (existing != null) {
            if (existing.deletedAt != null) {
                db.withTransaction {
                    streamerDao.restore(existing.id, now)
                    streamerDao.bumpStreamerMonitorGeneration(existing.id, now)
                    // 代际已前进，残留的挂起计数必须一并清除 ——
                    // 否则会被新代际的第一次观察当成"已连续确认若干次"，
                    // 可能直接凑满阈值误报状态转换。addStreamer 的恢复分支早有这一步，
                    // 导入路径原先漏了。
                    streamerDao.deletePendingTransition(existing.id)
                }
                return AddStreamerResult.Restored(streamerDao.findById(existing.id)!!)
            }
            return AddStreamerResult.AlreadyExists
        }
        // 占位昵称取配置值（可配），与 addStreamer 的取值口径保持一致；
        // 原先两个路径一个读配置、一个写死 "[待获取]"。
        val placeholder = runCatching { db.configDao().getConfig()?.placeholderText }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "[待获取]"
        val entity = StreamerEntity(
            stableId = Ids.stableId("str"), uid = uid, roomId = null, shortRoomId = null,
            name = name.ifBlank { placeholder }, nameLocked = false,
            avatarUrl = avatarUrl, roomTitle = null, parentAreaName = null, areaName = null,
            coverUrl = null, liveUrl = null,
            confirmedLiveStatus = com.example.bilimonitor.data.local.ConfirmedLiveStatus.UNKNOWN,
            lastObservationResult = ObservationResult.INVALID,
            observationSequence = 0, streamerMonitorGeneration = 0,
            monitoringEnabled = true, isFavorite = false, monitorPolicyStableId = null,
            lastCheckedAt = null, lastConfirmedAt = null,
            freshnessStatus = DataFreshness.UNKNOWN,
            lastLiveStartedAt = null, lastLiveEndedAt = null, lastError = null,
            deletedAt = null, createdAt = now, updatedAt = now
        )
        val id = db.withTransaction {
            val newId = streamerDao.insert(entity)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "IMPORT_STREAMER", targetType = "streamer",
                    targetStableId = entity.stableId, occurredAt = now, detailJson = null
                )
            )
            newId
        }
        return AddStreamerResult.Added(streamerDao.findById(id)!!)
    }

    suspend fun setUserName(streamerId: Long, name: String) {
        streamerDao.setUserName(streamerId, name.trim(), clock.nowWall())
    }

    suspend fun updatePolicy(
        streamerId: Long,
        enabled: Boolean? = null,
        intervalSeconds: Int? = null,
        notifyStart: Boolean? = null,
        notifyEnd: Boolean? = null,
        startConfirmationCount: Int? = null,
        endConfirmationCount: Int? = null,
        notifyTitleChange: Boolean? = null,
        notifyAreaChange: Boolean? = null
    ) {
        val now = clock.nowWall()
        db.withTransaction {
            val current = policyDao.get(streamerId) ?: StreamerMonitorPolicyEntity(
                streamerId = streamerId, enabled = true, intervalSeconds = null,
                notifyStart = true, notifyEnd = true, notifyRound = false,
                // 变化类通知默认关闭（用户定稿）
                notifyTitleChange = false, notifyAreaChange = false,
                startConfirmationCount = null, endConfirmationCount = null,
                aggregationEnabledOverride = null, aggregationThresholdOverride = null,
                overrideQuietHours = null, updatedAt = now
            )
            policyDao.upsert(
                current.copy(
                    enabled = enabled ?: current.enabled,
                    intervalSeconds = intervalSeconds ?: current.intervalSeconds,
                    notifyStart = notifyStart ?: current.notifyStart,
                    notifyEnd = notifyEnd ?: current.notifyEnd,
                    notifyTitleChange = notifyTitleChange ?: current.notifyTitleChange,
                    notifyAreaChange = notifyAreaChange ?: current.notifyAreaChange,
                    startConfirmationCount = startConfirmationCount ?: current.startConfirmationCount,
                    endConfirmationCount = endConfirmationCount ?: current.endConfirmationCount,
                    updatedAt = now
                )
            )
            // 主播级策略变化 → 代际递增 + 在途区间失效（0.6.16.1 第 5 条）。
            val changed = (enabled != null && enabled != current.enabled) ||
                (intervalSeconds != null && intervalSeconds != current.intervalSeconds) ||
                (startConfirmationCount != null && startConfirmationCount != current.startConfirmationCount) ||
                (endConfirmationCount != null && endConfirmationCount != current.endConfirmationCount)
            if (changed) {
                streamerDao.bumpStreamerMonitorGeneration(streamerId, now)
                val streamer = streamerDao.findById(streamerId)
                if (streamer != null) {
                    reliableIntervalDao.invalidateActiveByStreamer(
                        streamer.stableId, ReliableIntervalInvalidReason.GENERATION_CHANGED, now
                    )
                }
                streamerDao.deletePendingTransition(streamerId)
            }
            // 策略变更落审计（与 SET_KEEPALIVE_CHOICE 同口径）。
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "UPDATE_STREAMER_POLICY", targetType = "streamer_monitor_policy",
                    targetStableId = streamerId.toString(), occurredAt = now,
                    detailJson = null
                )
            )
        }
    }

    suspend fun getPolicy(streamerId: Long): StreamerMonitorPolicyEntity? = policyDao.get(streamerId)

    suspend fun refreshMetadata(streamerId: Long): Boolean {
        val streamer = streamerDao.findById(streamerId) ?: return false
        val outcome = runCatching { dataSource.getRoomStatus(listOf(streamer.uid)) }.getOrNull()
        val remote = (outcome as? com.example.bilimonitor.domain.model.BatchStatusOutcome.Responded)?.response
        val info = remote?.resultsByUid?.get(streamer.uid) ?: return false
        streamerDao.updateRemoteMetadata(
            streamerId = streamerId,
            name = info.name,
            avatarUrl = info.avatarUrl,
            roomTitle = info.roomTitle,
            parentAreaName = info.parentAreaName,
            areaName = info.areaName,
            coverUrl = info.coverUrl,
            liveUrl = info.liveUrl,
            roomId = info.roomId,
            shortRoomId = info.shortRoomId,
            now = clock.nowWall()
        )
        return true
    }

    // ---- 输入解析（实现见 StreamerInputParser，此处仅为兼容转发） ----

    /**
     * 解析用户输入。URL 形态自带语义、不受 [mode] 影响；只有纯数字才需要用 [mode] 决定。
     * 详见 [StreamerInputParser]。
     */
    fun parseStreamerInput(input: String, mode: InputMode = InputMode.ROOM): ParsedInput? =
        StreamerInputParser.parse(input, mode)

    /**
     * 解析为 UID。
     * 主路径由 [mode]（URL 则自带语义）决定；主路径失败时回退另一种解释，
     * 让用户输错模式也能成功，而不是直接报"找不到"。
     */
    private suspend fun resolveUid(parsed: ParsedInput): Long? = when (parsed) {
        is ParsedInput.Uid -> resolveByUid(parsed.uid) ?: resolveByRoomId(parsed.uid)
        is ParsedInput.RoomId -> resolveByRoomId(parsed.roomId) ?: resolveByUid(parsed.roomId)
    }
    private suspend fun resolveByUid(uid: Long): Long? {
        if (uid <= 0) return null
        val outcome = runCatching { dataSource.getRoomStatus(listOf(uid)) }.getOrNull()
        val resp = (outcome as? com.example.bilimonitor.domain.model.BatchStatusOutcome.Responded)?.response
        return resp?.resultsByUid?.get(uid)?.uid?.takeIf { it > 0 }
    }

    /**
     * 添加失败时的**精确原因**（只在**已经确认没有封禁证据**之后才会走到这里）。
     *
     * 封禁判定不在这里做，而是由 [addStreamer] 用一次 `room_init` 探针完成：
     * 那里的结论决定"照常建流 + 写封禁标记"还是"按这里的分类如实报错"。
     * 因此本函数只管一件事 —— **绝不**把风控（-352）/超时/5xx/解析失败说成"封禁"。
     */
    private fun addFailureReason(parsed: ParsedInput, failure: CallFailure?): String {
        // 没有封禁证据 → 如实说明这次请求到底怎么了（不再一律"远程查询失败"）。
        val label = when (parsed) {
            is ParsedInput.Uid -> "UID「${parsed.uid}」"
            is ParsedInput.RoomId -> "房间号「${parsed.roomId}」"
        }
        val cause = when (failure?.reason) {
            RoomFailureReason.TIMEOUT -> "请求超时"
            RoomFailureReason.NETWORK -> "网络不可用"
            RoomFailureReason.RATE_LIMITED ->
                "被服务端限流（HTTP ${failure.httpStatus ?: "429"}），请稍后再试"
            RoomFailureReason.SERVER_ERROR ->
                "服务端错误（HTTP ${failure.httpStatus ?: "5xx"}），请稍后再试"
            RoomFailureReason.API_REJECTED ->
                "接口拒绝了这次请求（HTTP ${failure.httpStatus ?: "-"} / code ${failure.apiCode ?: "-"}" +
                    (failure.apiMessage?.let { "：$it" } ?: "）")
            RoomFailureReason.MALFORMED_RESPONSE -> "接口返回无法解析"
            RoomFailureReason.MISSING_IN_RESPONSE,
            null -> "接口没有返回这个直播间（可能未开播或不存在直播间）"
        }
        return "按$label 查询失败：$cause"
    }

    /** 探针用的 id：优先用户输入的原始号码，否则用已解析出的 uid。 */
    private fun probeIdOf(parsed: ParsedInput, resolvedUid: Long): Long = when (parsed) {
        is ParsedInput.RoomId -> parsed.roomId
        is ParsedInput.Uid -> parsed.uid
    }.takeIf { it > 0 } ?: resolvedUid

    private suspend fun resolveByRoomId(roomId: Long): Long? {
        if (roomId <= 0) return null
        val detail = runCatching { dataSource.getRoomDetailByRoomId(roomId) }.getOrNull()
        return detail?.uid?.takeIf { it > 0 }
    }
}
