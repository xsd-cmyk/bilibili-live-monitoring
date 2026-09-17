package com.example.bilimonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilimonitor.core.EventSequences
import com.example.bilimonitor.data.local.ConfirmedLiveStatus
import com.example.bilimonitor.data.local.DataFreshness
import com.example.bilimonitor.data.local.GapReason
import com.example.bilimonitor.data.local.GapScope
import com.example.bilimonitor.data.local.IntervalStatus
import com.example.bilimonitor.data.local.LiveEventType
import com.example.bilimonitor.data.local.ObservationResult
import com.example.bilimonitor.data.local.PendingTransition
import com.example.bilimonitor.data.local.ReliableIntervalInvalidReason
import com.example.bilimonitor.data.local.entity.LiveEventEntity
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapEntity
import com.example.bilimonitor.data.local.entity.MonitoringGapStreamerEntity
import com.example.bilimonitor.data.local.entity.ReliableMonitorIntervalEntity
import com.example.bilimonitor.data.local.entity.StatusHistoryEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.local.entity.StreamerPendingTransitionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamerDao {
    /**
     * 唯一允许写 Streamer 确认状态与观察结果的入口（0.6.15 / 0.6.35 CAS 模板）。
     * 影响行数 0 → 调用方返回 StaleOrFenced / StaleSequence，禁止放宽条件重试。
     *
     * ★ 缺陷 3：本语句原先带一个**恒真条件**，本次删除
     * `AND streamerMonitorGeneration = :streamerMonitorGeneration` —— 期望值由调用方在
     * **同一个事务内**从这一行自己读出来（MonitorRepository.applyObservationOnly /
     * applyConfirmedObservationAtomically 都是先 findActiveByStableId，再把
     * streamer.streamerMonitorGeneration 原样传进来）⇒ 在 SQLite 里永远成立、等于没写。
     * 后果：Tick 中途把某位主播暂停之后，**在途**的那一次观察照样能提交转换
     * （甚至复活刚被 ABANDONED 的场次并发通知）—— 真正拦得住的只剩下面这两条。
     *
     * ## 真正的保护链（缺一不可，写在注释里以备后来者）
     *  1. `observationSequence < :observationSequence`：每主播**单调递增**的观察序号。
     *     旧序号（并发 Tick、旧进程重放、已提交过的观察）永远写不进去，序号也不会被写回退。
     *  2. `EXISTS(system_runtime_lock ... monitorGeneration + fencingToken + maintenanceMode = 'OFF')`：
     *     全局监控代际 + **fencing token** 必须仍是本进程持有的那份租约。另一个进程接管租约时
     *     `acquireOrTakeoverLease` 会递增 monitorGeneration 并换发新 token，旧进程的一切写入
     *     都会被这一条挡住（0.6.35）；维护模式（导出/恢复/清理）期间同样禁止写状态机。
     *  3. `monitoringEnabled = 1`（本次新增）：用户**已暂停**的主播不得再提交状态转换。
     *     暂停本身会递增主播级代际、失效区间、把 ACTIVE OPEN 转 ABANDONED
     *     （StreamerRepository.batchSetMonitoringEnabled），但那之后**在途**的这一次观察，
     *     只有这一条拦得住 —— 这正是"暂停拦不住在途观察"那个缺陷的修复点。
     * 已软删除（deletedAt 非空）的主播由调用方事务内的 findActiveByStableId 挡住（返回 Invalid），
     * 这里不重复判一次。
     */
    @Query(
        """
        UPDATE streamer
        SET confirmedLiveStatus   = :newStatus,
            lastObservationResult = :observationResult,
            observationSequence   = :observationSequence,
            lastCheckedAt         = :observedAt,
            lastConfirmedAt       = :eventConfirmedAt,
            freshnessStatus       = :freshness,
            updatedAt             = :updatedAt
        WHERE id = :streamerId
          AND observationSequence < :observationSequence
          AND monitoringEnabled = 1
          AND EXISTS (
              SELECT 1 FROM system_runtime_lock
              WHERE singletonId = 1
                AND monitorGeneration = :monitorGeneration
                AND fencingToken = :fencingToken
                AND maintenanceMode = 'OFF'
          )
        """
    )
    suspend fun casConfirmedObservation(
        streamerId: Long,
        newStatus: ConfirmedLiveStatus,
        observationResult: ObservationResult,
        observationSequence: Long,
        monitorGeneration: Long,
        fencingToken: String,
        observedAt: Long,
        eventConfirmedAt: Long?,
        freshness: DataFreshness,
        updatedAt: Long
    ): Int

    /**
     * PendingTransition 递增：**上下文已变化时必须返回 0**，调用方重读后重算（0.6.15）。
     *
     * ★ 缺陷 3：本语句原先带两个**恒真条件**，本次删除
     * `AND monitorGeneration = :expectedMonitorGeneration AND streamerMonitorGeneration =
     * :expectedStreamerGeneration` —— 这两个期望值由调用方在**同一个事务内**从本行自己读出来
     * （MonitorRepository.updateCounting 先 getPendingTransitionAnyGeneration 再把
     * row.monitorGeneration / row.streamerMonitorGeneration 填进来）⇒ 恒成立、等于没写。
     * 代际是否变化应当作为**"计数行是否作废"的判据**（读侧见 [getPendingTransition] 的代际过滤，
     * 写侧见 [resetPendingTransition] 与 MonitorRepository.pendingWritePlan），
     * 而不是这里的 CAS 条件 —— 拿刚读出来的值当栅栏，永远拦不住任何东西。
     *
     * 剩下的是真检查：
     *  - `lastObservationSequence < :sequence`：每主播**单调递增**的观察序号，
     *    过期的观察（并发 Tick / 旧进程重放）写不进来；
     *  - `EXISTS(streamer ... monitoringEnabled = 1)`：**已暂停**的主播不得再推进任何计数。
     *    与 [casConfirmedObservation] 的 `monitoringEnabled = 1` 是同一条语义（0.6.22 第 1 条：
     *    暂停不是下播事实，但也不该再产生状态机写入），写在这里让本语句**自包含**，
     *    不依赖"调用方一定先 CAS 过 streamer 行"这个外部前提。
     *
     * 全局租约与 fencing token 的保护在 [casConfirmedObservation]：本表没有可以当栅栏的代际列，
     * 也不应该把刚读出来的值当栅栏。
     */
    @Query(
        """
        UPDATE streamer_pending_transition
        SET pendingTransition = :newTransition, confirmationCount = :newCount,
            lastObservationSequence = :sequence, updatedAt = :updatedAt
        WHERE streamerId = :streamerId
          AND lastObservationSequence < :sequence
          AND EXISTS (
              SELECT 1 FROM streamer
              WHERE id = :streamerId AND monitoringEnabled = 1
          )
        """
    )
    suspend fun updatePendingTransitionCas(
        streamerId: Long,
        newTransition: PendingTransition,
        newCount: Int,
        sequence: Long,
        updatedAt: Long
    ): Int

    @Query("SELECT * FROM streamer WHERE stableId = :stableId AND deletedAt IS NULL")
    suspend fun findActiveByStableId(stableId: String): StreamerEntity?

    @Query("SELECT * FROM streamer WHERE id = :id")
    suspend fun findById(id: Long): StreamerEntity?

    @Query("SELECT * FROM streamer WHERE uid = :uid LIMIT 1")
    suspend fun findByUid(uid: Long): StreamerEntity?

    @Query("SELECT * FROM streamer WHERE deletedAt IS NULL AND monitoringEnabled = 1 ORDER BY isFavorite DESC, name")
    suspend fun listMonitorable(): List<StreamerEntity>

    @Query("SELECT * FROM streamer WHERE deletedAt IS NULL ORDER BY isFavorite DESC, name")
    fun observeActive(): Flow<List<StreamerEntity>>

    @Query("SELECT * FROM streamer ORDER BY id")
    suspend fun listAllIncludingDeleted(): List<StreamerEntity>

    @Query("SELECT * FROM streamer WHERE deletedAt IS NULL ORDER BY isFavorite DESC, name")
    suspend fun listActive(): List<StreamerEntity>

    @Query("UPDATE streamer SET monitoringEnabled = :enabled, updatedAt = :now WHERE id = :streamerId")
    suspend fun setMonitoringEnabled(streamerId: Long, enabled: Boolean, now: Long)

    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_tag_cross_ref r ON r.streamerId = s.id
        WHERE r.tagId = :tagId AND s.deletedAt IS NULL
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByTag(tagId: Long): Flow<List<StreamerEntity>>

    /**
     * 标签筛选 + 关键词搜索（原实现选中标签后走不带 query 的版本，
     * 导致搜索框仍可见可输入却完全失效）。
     */
    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_tag_cross_ref r ON r.streamerId = s.id
        WHERE r.tagId = :tagId AND s.deletedAt IS NULL
          AND (s.name LIKE '%' || :query || '%')
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByTagAndQuery(tagId: Long, query: String): Flow<List<StreamerEntity>>

    /** 分组筛选（原规范 16）：与标签筛选同构。 */
    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_group_cross_ref r ON r.streamerId = s.id
        WHERE r.groupId = :groupId AND s.deletedAt IS NULL
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByGroup(groupId: Long): Flow<List<StreamerEntity>>

    /** 分组筛选 + 关键词搜索（与标签侧保持一致，避免搜索框失效）。 */
    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_group_cross_ref r ON r.streamerId = s.id
        WHERE r.groupId = :groupId AND s.deletedAt IS NULL
          AND (s.name LIKE '%' || :query || '%')
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByGroupAndQuery(groupId: Long, query: String): Flow<List<StreamerEntity>>

    // ---- 多标签筛选 / 分组 ∧ 多标签组合筛选（用户要求：标签那一行支持同级多选）----
    //
    // 存储侧本来就不缺东西：`streamer_tag_cross_ref` 是 (streamerId, tagId) 多对多关联表，
    // 一个主播早就可以挂任意多个标签（详情页也一直能一个个加），所以**这一组查询不需要任何
    // schema 改动、不需要迁移**；缺的只是"一次按多个标签筛"的查询和上层状态类型。
    //
    // AND / OR 两种语义都在这里备好，选哪种由上层决定（两种都合理，属于产品语义，
    // 不该由 DAO 替用户拍板）：
    //   · observeByAnyTag / observeByGroupAndAnyTag = 命中任一选中标签即入选（并集）
    //   · observeByAllTags / observeByGroupAndAllTags = 同时具备全部选中标签（交集）
    //
    // 两个 Room/SQLite 的坑写在调用点附件，免得上层的实现者踩：
    //  1) `IN (:tagIds)` 在列表为空时会生成 `IN ()`，SQLite 直接报语法错 —— 空列表的兜底
    //     必须由调用方做（StreamerRepository.observeByTags 已兜底为"不加标签条件"）。
    //  2) AND 语义用 `GROUP BY s.id HAVING COUNT(DISTINCT r.tagId) = :tagCount`，
    //     [tagCount] 必须等于 tagIds.size，否则结果必然为空或直接退化。
    //
    // 关键词一律写成 `(:query = '' OR ...)`：类型 / 标签 / 分组三行筛选与搜索框是同时生效的，
    // 既有实现为"带不带 query"各写一条方法（observeByTag / observeByTagAndQuery），
    // 4 种筛选 × 2 = 8 条；把"空串即不过滤"收进 SQL 常量条件后 4 条就够，
    // 且与既有那两条分支的语义完全一致（空串 = 不加关键词条件）。

    /** 多标签筛选，**任一命中即入选**（OR 语义）+ 关键词（空串 = 不筛关键词）。 */
    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_tag_cross_ref r ON r.streamerId = s.id
        WHERE r.tagId IN (:tagIds) AND s.deletedAt IS NULL
          AND (:query = '' OR s.name LIKE '%' || :query || '%')
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByAnyTag(tagIds: List<Long>, query: String): Flow<List<StreamerEntity>>

    /** 多标签筛选，**必须同时具备全部选中标签**（AND 语义，[tagCount] = tagIds.size）+ 关键词。 */
    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_tag_cross_ref r ON r.streamerId = s.id
        WHERE r.tagId IN (:tagIds) AND s.deletedAt IS NULL
          AND (:query = '' OR s.name LIKE '%' || :query || '%')
        GROUP BY s.id
        HAVING COUNT(DISTINCT r.tagId) = :tagCount
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByAllTags(tagIds: List<Long>, tagCount: Int, query: String): Flow<List<StreamerEntity>>

    /** 分组 ∧ 多标签（OR 语义，见 [observeByAnyTag]）+ 关键词。 */
    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_group_cross_ref g ON g.streamerId = s.id
        JOIN streamer_tag_cross_ref r ON r.streamerId = s.id
        WHERE g.groupId = :groupId AND r.tagId IN (:tagIds) AND s.deletedAt IS NULL
          AND (:query = '' OR s.name LIKE '%' || :query || '%')
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByGroupAndAnyTag(groupId: Long, tagIds: List<Long>, query: String): Flow<List<StreamerEntity>>

    /** 分组 ∧ 多标签（AND 语义，[tagCount] = tagIds.size）+ 关键词。 */
    @Query(
        """
        SELECT s.* FROM streamer s
        JOIN streamer_group_cross_ref g ON g.streamerId = s.id
        JOIN streamer_tag_cross_ref r ON r.streamerId = s.id
        WHERE g.groupId = :groupId AND r.tagId IN (:tagIds) AND s.deletedAt IS NULL
          AND (:query = '' OR s.name LIKE '%' || :query || '%')
        GROUP BY s.id
        HAVING COUNT(DISTINCT r.tagId) = :tagCount
        ORDER BY s.isFavorite DESC, s.name
        """
    )
    fun observeByGroupAndAllTags(
        groupId: Long,
        tagIds: List<Long>,
        tagCount: Int,
        query: String
    ): Flow<List<StreamerEntity>>

    @Query("SELECT * FROM streamer WHERE deletedAt IS NULL AND (name LIKE '%' || :query || '%') ORDER BY isFavorite DESC, name")
    fun searchActive(query: String): Flow<List<StreamerEntity>>

    @Query("SELECT * FROM streamer WHERE id = :id")
    fun observeById(id: Long): Flow<StreamerEntity?>

    @Query("SELECT * FROM streamer WHERE deletedAt IS NULL AND confirmedLiveStatus = :status ORDER BY name")
    fun observeByStatus(status: ConfirmedLiveStatus): Flow<List<StreamerEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(streamer: StreamerEntity): Long

    @Query("SELECT * FROM streamer WHERE id = :streamerId AND deletedAt IS NULL")
    suspend fun findActiveById(streamerId: Long): StreamerEntity?

    /** 主播级监控代际唯一递增语句（0.6.16.1 第 5 条）。 */
    @Query("UPDATE streamer SET streamerMonitorGeneration = streamerMonitorGeneration + 1, updatedAt = :now WHERE id = :streamerId")
    suspend fun bumpStreamerMonitorGeneration(streamerId: Long, now: Long): Int

    /** 监控中检测到直播间标题变化：刷新主页卡片显示。 */
    @Query("UPDATE streamer SET roomTitle = :title, updatedAt = :now WHERE id = :streamerId AND (roomTitle IS NULL OR roomTitle <> :title)")
    suspend fun updateRoomTitle(streamerId: Long, title: String, now: Long): Int

    @Query("UPDATE streamer SET deletedAt = :now, updatedAt = :now WHERE id = :streamerId AND deletedAt IS NULL")
    suspend fun softDelete(streamerId: Long, now: Long): Int

    @Query("UPDATE streamer SET deletedAt = NULL, updatedAt = :now WHERE id = :streamerId AND deletedAt IS NOT NULL")
    suspend fun restore(streamerId: Long, now: Long): Int

    @Query("UPDATE streamer SET isFavorite = :favorite, updatedAt = :now WHERE id = :streamerId")
    suspend fun setFavorite(streamerId: Long, favorite: Boolean, now: Long): Int

    /** 用户手工编辑昵称：置 nameLocked = 1，远程刷新不得覆盖（249.9.1.1）。 */
    @Query("UPDATE streamer SET name = :name, nameLocked = 1, updatedAt = :now WHERE id = :streamerId")
    suspend fun setUserName(streamerId: Long, name: String, now: Long): Int

    @Query("UPDATE streamer SET lastLiveStartedAt = :at, updatedAt = :now WHERE id = :streamerId AND (lastLiveStartedAt IS NULL OR lastLiveStartedAt < :at)")
    suspend fun noteLiveStarted(streamerId: Long, at: Long, now: Long): Int

    /**
     * 写入 / 清除「封禁中」标记。
     *
     * 承载字段是既有的 `streamer.lastError`（TEXT、可空、无 CHECK 约束），
     * 内容由 `RoomBanCodec` 编码 —— 因此**不需要新列、不需要迁移**，
     * 且备份（整行导出）与诊断包会自动带上它。
     *
     * 为什么单独一条语句而不是并入 `casConfirmedObservation`：
     * 封禁状态必须**独立于**确认状态机 —— 它不参与 CAS 序号、不产生状态转换，
     * 也不应该在 CAS 失败（Stale/Fenced）时被一起丢弃。两条语句各管各的语义。
     */
    @Query("UPDATE streamer SET lastError = :banJson, updatedAt = :now WHERE id = :streamerId")
    suspend fun updateBanState(streamerId: Long, banJson: String?, now: Long): Int

    /**
     * 补写**数据新鲜度快照列**（`streamer.freshnessStatus`）—— 与 CAS 语句完全独立。
     *
     * ## 为什么需要这条语句
     * 这一列原先**只在"某位主播的一次观察被应用"时**被写（唯一写点 [casConfirmedObservation]），
     * 于是"本轮没被观察到"的主播（被封禁跳过的、引擎停跑期间的所有人）那一列会一直冻结在
     * 最后一次写入的值上 —— 而界面把它当成了"此刻是否过期"（本次修复的根因，
     * 详见 `domain.policy.FreshnessPolicy` 的文件说明）。引擎在**每轮开头**按同一个纯函数
     * 重算一次并写回（`MonitoringEngine.refreshFreshnessSnapshots`），这条语句就是那个写点。
     *
     * ## 为什么单独一条语句，而不是并进 [casConfirmedObservation]
     * 与 [updateBanState] 同一条取舍：这一列是**派生的展示缓存**，不参与 CAS 状态机。
     * 并进 CAS 会带来两个后果：① 补写要受 `observationSequence` / 代际 / fencing 条件约束，
     *   "没被观察到的主播"恰恰永远不满足这些条件 —— 等于没写；
     *   ② CAS 失败（Stale/Fenced）时补写会被一起丢弃。两条语句各管各的语义。
     *
     * ★ **绝不碰** `observationSequence` / `streamerMonitorGeneration` / `lastCheckedAt` /
     *   `lastConfirmedAt`：那些是 CAS 与调度的依据，写一个展示用的派生列不得影响它们
     *   （尤其 `lastCheckedAt` 一改，断档截断与"多久没检查"的展示都会跟着变）。
     * ★ `freshnessStatus <> :freshness`：值没变时影响 0 行。稳态下（每位主播都在被正常检查）
     *   这条补写每轮都是 0 次实际写入，不会白白消耗 WAL 与电量。
     */
    @Query(
        """
        UPDATE streamer
        SET freshnessStatus = :freshness
        WHERE id = :streamerId
          AND deletedAt IS NULL
          AND freshnessStatus <> :freshness
        """
    )
    suspend fun updateFreshnessSnapshot(streamerId: Long, freshness: DataFreshness): Int

    /** 该主播当前是否被标记为「封禁中」（按 lastError 的封禁前缀判定）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM streamer WHERE id = :streamerId AND lastError LIKE 'ROOM_BANNED %')")
    suspend fun isBanned(streamerId: Long): Boolean
    @Query("UPDATE streamer SET lastLiveEndedAt = :at, updatedAt = :now WHERE id = :streamerId")
    suspend fun noteLiveEnded(streamerId: Long, at: Long, now: Long): Int

    /** 远程资料刷新；name 仅在 nameLocked = 0 时覆盖；uid 不匹配影响 0 行（0.6.22.2）。 */
    @Query(
        """
        UPDATE streamer
        SET name = CASE WHEN :name IS NULL THEN name
                        WHEN nameLocked = 1 THEN name
                        ELSE :name END,
            avatarUrl = COALESCE(:avatarUrl, avatarUrl),
            roomTitle = COALESCE(:roomTitle, roomTitle),
            parentAreaName = COALESCE(:parentAreaName, parentAreaName),
            areaName = COALESCE(:areaName, areaName),
            coverUrl = COALESCE(:coverUrl, coverUrl),
            liveUrl = COALESCE(:liveUrl, liveUrl),
            roomId = COALESCE(:roomId, roomId),
            shortRoomId = COALESCE(:shortRoomId, shortRoomId),
            updatedAt = :now
        WHERE id = :streamerId
        """
    )
    suspend fun updateRemoteMetadata(
        streamerId: Long, name: String?, avatarUrl: String?, roomTitle: String?,
        parentAreaName: String?, areaName: String?, coverUrl: String?, liveUrl: String?,
        roomId: Long?, shortRoomId: Long?, now: Long
    ): Int

    /** 恢复冲突 MERGE/USE_BACKUP：uid 身份采纳（0.6.40）。 */
    @Query("UPDATE streamer SET uid = :uid, updatedAt = :now WHERE id = :streamerId")
    suspend fun updateIdentity(streamerId: Long, uid: Long, now: Long): Int

    /** 恢复冲突 USE_BACKUP：采纳备份 stableId 为最终身份（0.6.40）。 */
    @Query("UPDATE streamer SET stableId = :stableId, updatedAt = :now WHERE id = :streamerId")
    suspend fun updateStableId(streamerId: Long, stableId: String, now: Long): Int

    /**
     * 某位主播当前**属于现行代际**的挂起计数行；代际已变时返回 null（= 没有挂起计数）。
     *
     * ★ 缺陷 1 的读侧修复：原语句是 `SELECT * ... WHERE streamerId = :streamerId`，
     * 于是一个**跨代际存活**的旧计数行会被引擎当成"本次的连续计数"继续使用 ——
     * 老库 `endConfirmationCount = 2` 时，中断前攒下的 1 次计数会让恢复后的**第一次**观察
     * 直接凑满阈值，产生早一拍的假关播（`getPendingTransition` 唯一的另一个调用者是
     * MonitoringEngine.applySafely，它把这里读到的计数交给 StateConfirmationPolicy.detect）。
     *
     * 代际过滤条件与"计数行在哪个代际下有效"完全一致（写入侧见 [resetPendingTransition]）：
     *  - `monitorGeneration`：全局租约代际（进程重启 / 租约接管 / 恢复检查后递增）；
     *  - `streamerMonitorGeneration`：主播级代际（暂停、恢复监控、软删除后递增）。
     * 任一不匹配 ⇒ 这一行属于**上一次监控运行**，本次运行必须从 0 重新计数。
     *
     * 为什么放在 DAO 而不是调用方：唯一的读点是引擎（`background` 包里的 MonitoringEngine），
     * 那里读到的值直接进
     * 状态机判定 —— 只有让"过期的计数行"在出口处就不可见，才谈得上"代际变化即作废"。
     * 系统运行锁缺失时子查询为 NULL，比较不成立 ⇒ 同样返回 null（宁可当作没有计数）。
     */
    @Query(
        """
        SELECT p.* FROM streamer_pending_transition p
        JOIN streamer s ON s.id = p.streamerId
        WHERE p.streamerId = :streamerId
          AND p.monitorGeneration =
              (SELECT monitorGeneration FROM system_runtime_lock WHERE singletonId = 1)
          AND p.streamerMonitorGeneration = s.streamerMonitorGeneration
        """
    )
    suspend fun getPendingTransition(streamerId: Long): StreamerPendingTransitionEntity?

    /**
     * 与 [getPendingTransition] 同一行的**不过滤**读法，仅供写入路径判定"新建 / 重置 / 沿用"。
     *
     * 为什么写入路径必须用不过滤的读法：代际变化时那一行是**真的还在库里**，
     * 只是"作废"了。用过滤后的读法会误判成"没有行" → 走
     * [ensurePendingTransition]（IGNORE）→ 对已存在的行是 no-op ⇒ 旧计数永远不被重置，
     * 与被修复的那个缺陷一模一样。
     */
    @Query("SELECT * FROM streamer_pending_transition WHERE streamerId = :streamerId")
    suspend fun getPendingTransitionAnyGeneration(streamerId: Long): StreamerPendingTransitionEntity?

    /**
     * **新建**计数行：只在"该主播确实没有计数行"时使用。
     *
     * `OnConflictStrategy.IGNORE` 在这里是对的：并发的两个 Tick 同时新建时，先到的那一行说了算，
     * 后到的这一次观察不应该把别人刚写下的计数覆盖掉。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensurePendingTransition(row: StreamerPendingTransitionEntity)

    /**
     * **真正重置**计数行（缺陷 1 的写侧修复）：代际变化时必须覆盖旧行，而不是"已存在就跳过"。
     *
     * 为什么不能用 [ensurePendingTransition] 做这件事：表 PK 就是 `streamerId`
     * （见 CoreEntities.kt 的 StreamerPendingTransitionEntity），行已存在时 IGNORE 会让
     * SQLite **整条 INSERT 直接作废** —— 修复前 MonitorRepository 里那句
     * `existing.copy(monitorGeneration = …)` 在库里什么都没发生：代际列、`confirmationCount`
     * 全是旧值，紧接着的 CAS 又拿**旧行**当期望值（恒匹配）⇒ 计数跨代际、跨进程活了下来。
     *
     * 为什么 REPLACE 而不是 UPSERT：`OnConflictStrategy.REPLACE` 在任何 SQLite 版本上都是
     * "删旧行 + 插新行"，语义就是**整行重建**（正是这里需要的）；本表**没有任何子表**引用它
     * （外键方向是它指向 streamer，级联删除朝另一个方向），不存在级联误删的风险。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun resetPendingTransition(row: StreamerPendingTransitionEntity)

    @Query("DELETE FROM streamer_pending_transition WHERE streamerId = :streamerId")
    suspend fun deletePendingTransition(streamerId: Long)

    /** 唯一允许的 sourceDataVersion 递增语句（0.6.14）。 */
    @Query("UPDATE source_data_revision SET sourceDataVersion = sourceDataVersion + 1, updatedAt = :now WHERE singletonId = 1")
    suspend fun bumpSourceDataVersion(now: Long): Int
}

@Dao
interface LiveSessionDao {
    /** 复用而非重复创建 ACTIVE OPEN Session（0.6.15）。 */
    /**
     * 本地**所有**未结束场次（不分主播、不分 lifecycleState）。
     *
     * 用途：恢复备份前把"本地已有的未结束场次"预置进去重集合 —— 只按备份内部去重是不够的：
     * 本地已有一条 ABANDONED OPEN 时，备份里再插一条同主播的未结束场次会撞
     * `idx_live_session_one_abandoned_open`（部分唯一索引，见 AppMigrations），
     * 而恢复里那条 insert 是唯一没有 runCatching 的写入 —— 一条场次就能掀翻整个事务。
     */
    @Query("SELECT * FROM live_session WHERE endTime IS NULL")
    suspend fun listOpenSessions(): List<LiveSessionEntity>

    @Query("SELECT * FROM live_session WHERE streamerId = :streamerId AND endTime IS NULL AND lifecycleState = 'ACTIVE' LIMIT 1")
    suspend fun findActiveOpenSession(streamerId: Long): LiveSessionEntity?

    /**
     * 该主播的 ABANDONED OPEN 场次（0.6.22：`endTime` 保持 NULL、`lifecycleState = 'ABANDONED'`）。
     *
     * 正常数据下最多一条（部分唯一索引 `idx_live_session_one_abandoned_open`）；
     * 异常数据（恢复旧备份、反复中断）下可能并存多条，此时**取最近开始的那一条** ——
     * 与 0.6.22 一致性检查"按 createdAt DESC 逐个处理"同口径，也避免没有 ORDER BY 时
     * `LIMIT 1` 按 rowid 取到最陈旧的一条：那一条恰恰是缺陷 2 里最该被判为"陈旧、必须关闭"
     * 的场次，取错了就会把它的 `startTime` 当成当前场次的开播时间（假时长）。
     */
    @Query("SELECT * FROM live_session WHERE streamerId = :streamerId AND endTime IS NULL AND lifecycleState = 'ABANDONED' ORDER BY COALESCE(startTime, createdAt) DESC LIMIT 1")
    suspend fun findAbandonedOpenSession(streamerId: Long): LiveSessionEntity?

    /** 关播资格与人工修正共用：correctionVersion 必须 CAS（0.6.15）。 */
    @Query(
        """
        UPDATE live_session
        SET endTime = :endTime, endSource = :endSource, endConfidence = :endConfidence,
            durationSeconds = :durationSeconds, durationConfidence = :durationConfidence,
            confirmedOfflineAt = :confirmedOfflineAt, endTimeZone = :endTimeZone,
            correctionVersion = correctionVersion + 1, updatedAt = :updatedAt
        WHERE id = :id AND endTime IS NULL AND correctionVersion = :expectedCorrectionVersion
        """
    )
    suspend fun casCloseSession(
        id: Long, endTime: Long, endSource: com.example.bilimonitor.data.local.DataSource,
        endConfidence: com.example.bilimonitor.data.local.DataConfidence,
        durationSeconds: Long?, durationConfidence: com.example.bilimonitor.data.local.DataConfidence?,
        confirmedOfflineAt: Long, endTimeZone: String?,
        expectedCorrectionVersion: Long, updatedAt: Long
    ): Int

    @Query("SELECT * FROM live_session WHERE id = :id")
    suspend fun findById(id: Long): LiveSessionEntity?

    @Query("SELECT * FROM live_session WHERE stableId = :stableId LIMIT 1")
    suspend fun findByStableId(stableId: String): LiveSessionEntity?

    /**
     * 删除一条直播记录（用户在历史页手动删除）。
     * 子表清理由 [com.example.bilimonitor.data.repository.HistoryRepository.deleteSession] 统一负责 ——
     * 只有 `live_session_title` 有 ON DELETE CASCADE，其余（事件 / 修正 / 关播资格区间）必须显式删。
     */
    @Query("DELETE FROM live_session WHERE stableId = :stableId")
    suspend fun deleteByStableId(stableId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: LiveSessionEntity): Long

    @Query("UPDATE live_session SET lifecycleState = 'ACTIVE', updatedAt = :now WHERE id = :id AND lifecycleState = 'ABANDONED' AND endTime IS NULL")
    suspend fun reactivateAbandoned(id: Long, now: Long): Int

    @Query("UPDATE live_session SET titleAtStart = :title, updatedAt = :now WHERE id = :id")
    suspend fun updateTitleAtStart(id: Long, title: String, now: Long): Int

    @Query("UPDATE live_session SET titleAtStart = :title, updatedAt = :now WHERE stableId = :stableId")
    suspend fun updateTitleAtStartByStableId(stableId: String, title: String, now: Long): Int

    /** 人工修正分区：把起止分区都写成同一个值（人工填的是"这一场属于哪个分区"）。 */
    @Query("UPDATE live_session SET areaAtStart = :area, areaAtEnd = :area, updatedAt = :now WHERE id = :id")
    suspend fun updateAreaAtStart(id: Long, area: String, now: Long): Int

    /**
     * 暂停监控 / 软删除：ACTIVE OPEN → ABANDONED，**`endTime` 保持 NULL**
     * （0.6.22：软删除、关闭监控、退出应用都不是下播事实，不得直接填结束时间）。
     *
     * 补结束时间的唯一自动路径在 MonitorRepository（缺陷 2）：
     * 之后第一次**确认 LIVE 或 OFFLINE** 时先处理这条 ABANDONED OPEN
     * （同一次直播的短暂中断 → 复用为 ACTIVE；超出时间窗的陈旧场次 → 以
     * `MONITOR_OBSERVED / PROVISIONAL` 关闭并新建场次）。
     * 除此之外**不存在**任何批量回填：本语句与 [casCloseSession] 是仅有的两个出口。
     */
    @Query("UPDATE live_session SET lifecycleState = 'ABANDONED', updatedAt = :now WHERE id = :id AND lifecycleState = 'ACTIVE' AND endTime IS NULL")
    suspend fun abandon(id: Long, now: Long): Int

    @Query("UPDATE live_session SET currentReliableIntervalId = :intervalId, titleAtStart = COALESCE(:title, titleAtStart), areaAtStart = COALESCE(:area, areaAtStart), coverUrlAtStart = COALESCE(:cover, coverUrlAtStart), lastConfirmedLiveAt = :at, updatedAt = :now WHERE id = :id")
    suspend fun attachIntervalAndLiveMeta(
        id: Long, intervalId: String, at: Long,
        title: String?, area: String?, cover: String?, now: Long
    ): Int

    /**
     * 直播历史一律**按"直播时间"（开播时间）排序**（用户要求）。
     *
     * 之前的排序键是 `COALESCE(endTime, startTime, createdAt)`：已结束的场次按**关播**时间排，
     * 进行中的场次按开播时间排，两者混在一条轴上 —— 于是"先开播、后关播"的记录会
     * 压过"后开播"的记录，看起来就像按创建时间/关播时间乱排。
     * `createdAt` 只作为开播时间缺失时的兜底（异常中断或历史遗留行可能没有 startTime）。
     */
    @Query("SELECT * FROM live_session WHERE streamerId = :streamerId AND endTime IS NOT NULL ORDER BY COALESCE(startTime, createdAt) DESC LIMIT :limit")
    fun observeClosedByStreamer(streamerId: Long, limit: Int): Flow<List<LiveSessionEntity>>

    @Query("SELECT * FROM live_session WHERE streamerId = :streamerId ORDER BY COALESCE(startTime, createdAt) DESC LIMIT :limit")
    fun observeByStreamer(streamerId: Long, limit: Int): Flow<List<LiveSessionEntity>>

    @Query("SELECT * FROM live_session ORDER BY COALESCE(startTime, createdAt) DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LiveSessionEntity>>

    // ★ 时间口径统一（台账 H19-B9）：统计页原先用的是 `endTime` 落在区间内，
    //   而历史页与导出用的是 `COALESCE(startTime, createdAt)` 落在区间内 ——
    //   同一时间范围下，"开播在区间之前、关播在区间内"的场次会被统计计入却不出现在
    //   历史/导出明细里（反之亦然），导出的历史表与统计图表能互相矛盾。
    //   现在统一为**开播时间**口径（与历史/导出、与用户对"这场直播属于哪一天"的直觉一致）。
    @Query("SELECT * FROM live_session WHERE endTime IS NOT NULL AND COALESCE(startTime, createdAt) >= :from AND COALESCE(startTime, createdAt) < :to ORDER BY COALESCE(startTime, createdAt)")
    suspend fun listClosedBetween(from: Long, to: Long): List<LiveSessionEntity>

    /**
     * 统计"被排除的场次"用（台账 H19-C8）：区间内**尚未结束**的场次。
     * 原先的实现是"再跑一遍同一个查询再相减"，结果恒为 0 —— 一个永远输出 0 的假指标。
     */
    @Query("SELECT count(*) FROM live_session WHERE streamerId = :streamerId AND endTime IS NULL AND COALESCE(startTime, createdAt) >= :from AND COALESCE(startTime, createdAt) < :to")
    suspend fun countUnclosedForStreamerBetween(streamerId: Long, from: Long, to: Long): Int

    /**
     * 导出用：指定主播集合在 [from, to) 内的已结束场次。
     *
     * 时间判定用 `COALESCE(startTime, createdAt)`（与历史页筛选口径一致）：
     * 用户理解的"这条记录属于哪一天"是**开播那天**，而不是关播那天。
     */
    @Query(
        """
        SELECT * FROM live_session
        WHERE endTime IS NOT NULL
          AND streamerId IN (:streamerIds)
          AND COALESCE(startTime, createdAt) >= :from
          AND COALESCE(startTime, createdAt) < :to
        ORDER BY COALESCE(startTime, createdAt)
        """
    )
    suspend fun listClosedForStreamersBetween(
        streamerIds: List<Long>, from: Long, to: Long
    ): List<LiveSessionEntity>

    @Query("SELECT * FROM live_session ORDER BY id")
    suspend fun listAll(): List<LiveSessionEntity>

    @Query("SELECT count(*) FROM live_session WHERE endTime IS NOT NULL")
    suspend fun countClosed(): Int

    /** 容量清理：删除最旧的已结束场次（保留进行中），返回删除的 id 集合。 */
    @Query("SELECT id FROM live_session WHERE endTime IS NOT NULL ORDER BY endTime ASC LIMIT :limit")
    suspend fun oldestClosedIds(limit: Int): List<Long>

    @Query("DELETE FROM live_session WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 人工修正时间字段：endLocked/startLocked 保护 + correctionVersion CAS（249.12）。 */
    @Query(
        """
        UPDATE live_session
        SET startTime = COALESCE(:startTime, startTime),
            endTime = COALESCE(:endTime, endTime),
            durationSeconds = COALESCE(:durationSeconds, durationSeconds),
            startSource = COALESCE(:startSource, startSource),
            endSource = COALESCE(:endSource, endSource),
            durationSource = COALESCE(:durationSource, durationSource),
            startConfidence = COALESCE(:startConfidence, startConfidence),
            endConfidence = COALESCE(:endConfidence, endConfidence),
            durationConfidence = COALESCE(:durationConfidence, durationConfidence),
            startLocked = CASE WHEN :startTime IS NOT NULL THEN 1 ELSE startLocked END,
            endLocked = CASE WHEN :endTime IS NOT NULL THEN 1 ELSE endLocked END,
            durationLocked = CASE WHEN :durationSeconds IS NOT NULL THEN 1 ELSE durationLocked END,
            confirmedOfflineAt = COALESCE(:confirmedOfflineAt, confirmedOfflineAt),
            note = COALESCE(:note, note),
            correctionVersion = correctionVersion + 1,
            updatedAt = :now
        WHERE id = :id AND correctionVersion = :expectedCorrectionVersion
        """
    )
    suspend fun casManualCorrect(
        id: Long, startTime: Long?, endTime: Long?, durationSeconds: Long?,
        startSource: String?, endSource: String?, durationSource: String?,
        startConfidence: String?, endConfidence: String?, durationConfidence: String?,
        confirmedOfflineAt: Long?,
        note: String?, expectedCorrectionVersion: Long, now: Long
    ): Int

    @Query("SELECT * FROM live_session WHERE stableId = :sessionStableId LIMIT 1")
    suspend fun findBySessionStableId(sessionStableId: String): LiveSessionEntity?
}

/**
 * live_event 事件表 DAO。
 *
 * ## 为什么是 `abstract class` 而不是 `interface`（本次修复引入，Room 官方支持的 DAO 形态）
 * 序号推进需要一个**可单测的纯函数**（见 [EventSequences.nextEventSequence]），
 * 这就必须在 DAO 上放一个**带方法体**的方法。而 Kotlin `interface` 的带体方法要走
 * `DefaultImpls` 桥接；本项目是 Room 2.6.1 + KSP，生成的是 **Java** 实现类
 * （`LiveEventDao_Impl`），它拿不到该桥接 ⇒ 编译期直接报"未实现抽象方法"。
 * `abstract class` 的非抽象方法会被生成的子类原样继承，是这里唯一安全的写法。
 * 其余方法一律保持 `abstract`，Room 的处理方式与 interface 完全相同。
 */
@Dao
abstract class LiveEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(event: LiveEventEntity)

    /**
     * 分配下一个 eventSequence（0.6.16.1）。
     *
     * **调用点签名逐字未变**（`MonitorRepository` 只取返回值），变的是语义：
     * 从"`live_event` 的 MAX + 1"升级为"三路高水位取 max 再 + 1"，
     * 规则、证明与取舍全部写在 [EventSequences.nextEventSequence] 的 KDoc 里。
     *
     * 修的是什么（阻断级缺陷）：`live_event` 行**会被删除**（删场次、容量清理），
     * 而 `status_history` **只增不减**。只取 `live_event` 的 MAX 会在"删掉最近一条已结束场次"
     * 之后**回退**，下一次转换重新拿到同一个 `eventSequence`；由于
     * `transitionId = "{streamerStableId}:{eventSequence}:{from}->{to}"` 是确定性的，
     * 新行与历史行的 transitionId **逐字相同**，撞上 `status_history` 上的唯一索引
     * `idx_status_history_transition(streamerId, transitionId)`（insert 用 ABORT）
     * ⇒ 整个转换事务回滚（状态/场次/事件/通知全丢）⇒ 下一 Tick 再撞一次
     * ⇒ 该主播**永久不再产生任何状态转换**，且每 Tick 写一条无抑制的错误。
     *
     * 三条查询都在调用方 `MonitorRepository` 的 `withTransaction` 内执行，
     * 与紧随其后的 `status_history` 插入共享同一事务快照，因此不会读到半截状态。
     */
    suspend fun nextEventSequence(streamerId: Long): Long = EventSequences.nextEventSequence(
        liveEventMaxSequence = maxEventSequence(streamerId),
        statusHistoryMaxSequence = maxStatusHistorySequence(streamerId),
        statusHistoryRowCount = countStatusHistory(streamerId)
    )

    /** 第一路高水位：事件表里已有的最大序号（与旧行为一致，保证已有序号不被重复发放）。 */
    @Query("SELECT COALESCE(MAX(eventSequence), 0) FROM live_event WHERE streamerId = :streamerId")
    abstract suspend fun maxEventSequence(streamerId: Long): Long

    /**
     * 第二路高水位：`status_history` 里能解析出的最大 seq。
     *
     * ## 解析为什么可靠
     * transitionId 的格式是**固定**的 `{streamerStableId}:{eventSequence}:{from}->{to}`：
     *  - `streamerStableId` 全仓只由 `Ids.stableId("str")` 产生 = `"str-" + UUID`，
     *    UUID 的字符集是 `0-9a-f-`，**不含冒号**（备份恢复写回的也是本应用自己导出的同一格式）；
     *  - `from` / `to` 是 `ConfirmedLiveStatus` 枚举名，同样不含冒号。
     *  因此 `instr(transitionId, ':')` 找到的**第一个**冒号，必然是"前缀"与"序号"的分隔符，
     *  `substr(..., 第一个冒号 + 1)` 恰好得到 `"{seq}:{from}->{to}"`，再交给 SQLite 的
     *  `CAST(... AS INTEGER)` —— 它取**最长的数字前缀**（遇到 `:` 停止），正好就是 seq。
     *
     * ## 解析失败也不会造成危害
     * 极端情况（有人手工改过备份里的 stableId，使其带冒号；或将来格式变更）下 CAST 只会得到
     * 一个偏小甚至 0 的值。调用方 [nextEventSequence] 用 `max(...)` 合并三路，
     * **偏小不会让序号回退**（另有第三路兜底），偏大也只是让序号跳一下，
     * 不存在任何正确性代价 —— 这也是"解析 + 行数兜底"两条路都要保留的原因。
     */
    @Query(
        """
        SELECT COALESCE(MAX(CAST(substr(transitionId, instr(transitionId, ':') + 1) AS INTEGER)), 0)
        FROM status_history
        WHERE streamerId = :streamerId AND transitionId IS NOT NULL
        """
    )
    abstract suspend fun maxStatusHistorySequence(streamerId: Long): Long

    /**
     * 第三路高水位：**完全不依赖解析**的兜底上界 —— `status_history` 的行数。
     *
     * 每次成功转换恰好写 1 行 `status_history`，且第 i 次转换拿到的序号 `S_i <= i`
     * （归纳：写入时 `live_event` 里所有序号都来自更早的转换 `S_j <= j < i`，故 `MAX <= i-1`）。
     * 该表**从不删除**，所以行数只增不减，永远 ≥ 历史上发放过的任何序号。
     * 即使第二路的解析整体失效（返回 0），这一路也能独立保证"下一次转换的 transitionId 未被占用"。
     */
    @Query("SELECT COUNT(*) FROM status_history WHERE streamerId = :streamerId")
    abstract suspend fun countStatusHistory(streamerId: Long): Long

    @Query("SELECT * FROM live_event WHERE sessionStableId = :sessionStableId ORDER BY eventConfirmedAt")
    abstract suspend fun listBySession(sessionStableId: String): List<LiveEventEntity>

    @Query("SELECT * FROM live_event WHERE streamerId = :streamerId ORDER BY eventConfirmedAt DESC LIMIT :limit")
    abstract suspend fun listRecentByStreamer(streamerId: Long, limit: Int): List<LiveEventEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM live_event WHERE sessionStableId = :sessionStableId AND eventType = :type)")
    abstract suspend fun existsBySessionAndType(sessionStableId: String, type: LiveEventType): Boolean

    @Query("SELECT * FROM live_event WHERE eventId = :eventId")
    abstract suspend fun findById(eventId: String): LiveEventEntity?

    @Query("SELECT * FROM live_event ORDER BY eventConfirmedAt")
    abstract suspend fun listAll(): List<LiveEventEntity>

    @Query("DELETE FROM live_event WHERE eventId = :eventId")
    abstract suspend fun deleteById(eventId: String)

    /** 删除某场次的全部事件（场次被删除时一并清理，避免留下孤立的开播/关播记录）。 */
    @Query("DELETE FROM live_event WHERE sessionStableId = :sessionStableId")
    abstract suspend fun deleteBySession(sessionStableId: String): Int
}

@Dao
interface StatusHistoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: StatusHistoryEntity)

    @Query("SELECT * FROM status_history WHERE streamerId = :streamerId ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun listByStreamer(streamerId: Long, limit: Int): List<StatusHistoryEntity>

    @Query("SELECT * FROM status_history WHERE streamerId = :streamerId ORDER BY occurredAt DESC LIMIT :limit")
    fun observeByStreamer(streamerId: Long, limit: Int): Flow<List<StatusHistoryEntity>>
}

@Dao
interface ReliableIntervalDao {
    @Query("SELECT * FROM reliable_monitor_interval WHERE streamerStableId = :streamerStableId AND status = 'ACTIVE' LIMIT 1")
    suspend fun findActiveByStreamer(streamerStableId: String): ReliableMonitorIntervalEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(interval: ReliableMonitorIntervalEntity)

    @Query(
        """
        UPDATE reliable_monitor_interval
        SET status = 'INVALIDATED', invalidatedAt = :now, invalidReason = :reason, updatedAt = :now
        WHERE streamerStableId = :streamerStableId AND status = 'ACTIVE'
        """
    )
    suspend fun invalidateActiveByStreamer(streamerStableId: String, reason: ReliableIntervalInvalidReason, now: Long): Int

    @Query(
        """
        UPDATE reliable_monitor_interval
        SET status = 'CLOSED', invalidatedAt = :now, invalidReason = :reason, updatedAt = :now
        WHERE streamerStableId = :streamerStableId AND status = 'ACTIVE'
        """
    )
    suspend fun closeActiveByStreamer(streamerStableId: String, reason: ReliableIntervalInvalidReason, now: Long): Int

    @Query("UPDATE reliable_monitor_interval SET lastReliableObservationSequence = :sequence, updatedAt = :now WHERE intervalId = :intervalId AND status = 'ACTIVE'")
    suspend fun renewActive(intervalId: String, sequence: Long, now: Long): Int

    @Query("SELECT * FROM reliable_monitor_interval WHERE sessionStableId = :sessionStableId ORDER BY startedAt")
    suspend fun listBySession(sessionStableId: String): List<ReliableMonitorIntervalEntity>

    /** 场次被删除时一并清理其"关播资格"区间：它们只对该场次有意义。 */
    @Query("DELETE FROM reliable_monitor_interval WHERE sessionStableId = :sessionStableId")
    suspend fun deleteBySession(sessionStableId: String): Int
}

@Dao
interface LiveSessionCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: com.example.bilimonitor.data.local.entity.LiveSessionCorrectionEntity)

    @Query("SELECT * FROM live_session_correction WHERE sessionStableId = :sessionStableId ORDER BY createdAt DESC")
    suspend fun listBySession(sessionStableId: String): List<com.example.bilimonitor.data.local.entity.LiveSessionCorrectionEntity>

    @Query("SELECT * FROM live_session_correction ORDER BY createdAt")
    suspend fun listAll(): List<com.example.bilimonitor.data.local.entity.LiveSessionCorrectionEntity>

    @Query("DELETE FROM live_session_correction WHERE sessionStableId = :sessionStableId")
    suspend fun deleteBySession(sessionStableId: String)
}

@Dao
interface MonitoringGapDao {
    @Query("SELECT * FROM monitoring_gap WHERE scope = :scope AND endTime IS NULL LIMIT 1")
    suspend fun findOpenByScope(scope: GapScope): MonitoringGapEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(gap: MonitoringGapEntity): Long

    @Query("UPDATE monitoring_gap SET endTime = :endTime WHERE gapId = :gapId AND endTime IS NULL")
    suspend fun close(gapId: String, endTime: Long): Int

    @Query("SELECT * FROM monitoring_gap_streamer WHERE streamerStableId = :streamerStableId AND affectedEndAt IS NULL LIMIT 1")
    suspend fun findOpenStreamerGap(streamerStableId: String): MonitoringGapStreamerEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStreamerGap(row: MonitoringGapStreamerEntity): Long

    @Query(
        """
        UPDATE monitoring_gap_streamer
        SET affectedEndAt = :endAt, recoveredAt = :endAt, lastReliableAt = :endAt
        WHERE streamerStableId = :streamerStableId AND affectedEndAt IS NULL
        """
    )
    suspend fun closeStreamerGap(streamerStableId: String, endAt: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM monitoring_gap_streamer WHERE streamerStableId = :streamerStableId AND affectedEndAt IS NULL)")
    suspend fun hasOpenStreamerGap(streamerStableId: String): Boolean

    /** 该父盲区下是否还有未恢复的主播行。 */
    @Query("SELECT EXISTS(SELECT 1 FROM monitoring_gap_streamer WHERE gapId = :gapId AND affectedEndAt IS NULL)")
    suspend fun hasOpenStreamerGapForGap(gapId: String): Boolean

    @Query("SELECT gapId FROM monitoring_gap_streamer WHERE streamerStableId = :streamerStableId AND affectedEndAt IS NULL")
    suspend fun openGapIdsForStreamer(streamerStableId: String): List<String>

    @Query("SELECT * FROM monitoring_gap ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MonitoringGapEntity>>

    @Query("SELECT * FROM monitoring_gap WHERE scope = 'SYSTEM' AND endTime IS NULL LIMIT 1")
    fun observeOpenSystemGap(): Flow<MonitoringGapEntity?>

    /**
     * 当前**尚未恢复**的主播级盲区（affectedEndAt IS NULL）。
     * 诊断页原先只订阅了 monitoring_gap（父表）并把结果丢弃，主播级盲区对用户完全不可见。
     */
    @Query("SELECT * FROM monitoring_gap_streamer WHERE affectedEndAt IS NULL ORDER BY affectedStartAt DESC LIMIT :limit")
    fun observeOpenStreamerGaps(limit: Int): Flow<List<MonitoringGapStreamerEntity>>

    /** 一次性查询版（诊断包导出用）。 */
    @Query("SELECT * FROM monitoring_gap_streamer WHERE affectedEndAt IS NULL ORDER BY affectedStartAt DESC LIMIT :limit")
    suspend fun openStreamerGaps(limit: Int): List<MonitoringGapStreamerEntity>
}
