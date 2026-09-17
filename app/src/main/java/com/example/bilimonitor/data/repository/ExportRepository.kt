package com.example.bilimonitor.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.ExportSnapshotStatus
import com.example.bilimonitor.data.local.ExportType
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.ExportSnapshotEntity
import com.example.bilimonitor.data.local.entity.ExportSnapshotLiveSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTML 导出（原规范 131-138 / 0.6.18 方案 B）：
 * 唯一路径 = 维护模式 → 临时快照表（单事务复制）→ 分页读取临时表 → 流式写出 → 状态机闭环。
 *
 * 导出范围由**主播集合 + 时间范围**决定（用户定稿）：
 *  - 单个主播：只选时间范围；
 *  - 多个主播：合并成一份文件；
 *  - 文件命名 = 实际导出数据的时间跨度（精确到日）+ 主播名 / 首位主播名等N位。
 */
@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val statsSeriesRepository: StatsSeriesRepository,
    private val logDao: LogDao,
    private val clock: AppClock
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 报告页脚里的应用名：**从资源里取，不写死**。
     *
     * 为什么：应用已改名为 Kaoru（strings.xml 的 app_name），而页脚原先硬编码"主播监控" ——
     * 用户按页脚去找应用会对不上号，而且以后每次改名都得记得回来改这处字符串。
     * 用 R.string.app_name（就是启动器上显示的名字、跟随系统语言）而不是 loadLabel：
     * 后者在某些 ROM 上会带回 Activity 的 label。
     */
    private val appName: String = context.getString(com.example.bilimonitor.R.string.app_name)

    data class ExportResult(
        val fileName: String,
        val sessionCount: Int,
        /** 本次选择的主播数（含没有记录的）。 */
        val streamerCount: Int = 1,
        /** 其中在该时间范围内没有记录、在报告里是空分组的主播数。 */
        val emptyStreamerCount: Int = 0
    )

    /**
     * 导出指定主播在 [from, to) 内的直播历史。
     *
     * @param streamerIds 主播 id 集合（调用方保证非空）
     * @param rangeLabel 时间范围的可读描述，写进报告抬头
     */
    /**
     * 本次**历史导出**是否已经建出快照行（只给取消日志区分"取消在哪个阶段"用）。
     *
     * 为什么是字段而不是局部变量（复查发现的缺陷）：`onFailure` 的 lambda 挂在 runCatching
     * 的**结果**上，在 runCatching 的 lambda **之外**求值 —— 它看不到其体内的局部变量，
     * 局部写法的直接后果就是编译不过（Unresolved reference 'snapshotCreated'）。
     * 与 [com.example.bilimonitor.data.repository.BackupRepository] 里的降级计数同一个处理。
     * 导出互斥由维护权（takeOverForMaintenance）保证，同一时刻只有一次导出，故无并发写；
     * 每次 [exportHistory] 开头都会先清零。
     */
    private var historySnapshotCreated = false

    suspend fun exportHistory(
        streamerIds: List<Long>,
        from: Long,
        to: Long,
        rangeLabel: String
    ): Result<ExportResult> = runCatching {
        require(streamerIds.isNotEmpty()) { "没有选择主播" }
        val snapshotId = Ids.newId()
        val now = clock.nowWall()
        // 主播名一律从库里取（而不是由界面传进来）：名字可能已被改过，
        // 而且**没记录的主播也要出现在报告里**，快照表里没有它们的行。
        val streamers = db.streamerDao().listAllIncludingDeleted()
        val namesById = streamers.associate { it.id to it.name }
        // uid 只用于"同名主播"的显示区分（见 sectionTitlesOf），不参与任何统计口径
        val uidsById = streamers.associate { it.id to it.uid }
        val selectedNames = streamerIds.map { namesById[it] ?: "未知主播（$it）" }
        // 报告的分组与计数一律按 **id**，名字只在最终显示时映射：B 站昵称可以重名
        // （streamer.name 没有唯一索引），按名字分组会让两位同名主播的每一节都列出两人的
        // 全部场次、时长翻倍，而抬头"场次 N"又是对的 —— 同一份文件自相矛盾。
        val sectionTitles = sectionTitlesOf(streamerIds, selectedNames, uidsById)

        // 1-2. 进入维护模式 + 建快照记录（导出互斥由维护权保证，0.6.33.2 #5）
        // 用"用户主动接管"版本：否则监控租约有效期内（每 Tick 后 90 秒）导出必然失败，
        // 而报错信息会说是"导出/恢复/清理正在进行"——与真实原因完全无关。
        var sessionCount = 0
        historySnapshotCreated = false
        try {
            // ★ 取维护权必须放在 try **里面**（复查发现的缺陷）：它是 suspend 的 Room 写，
            //   协程已被取消时可能在 withContext 边界抛 CancellationException，而那一刻
            //   UPDATE 可能**已经提交**。放在 try 之外时 finally 不会执行 ⇒ maintenanceMode
            //   停在 'BACKUP'，而引擎取租约要求 'OFF' ⇒ **监控停摆到下次冷启动**。
            //   （与 applyRestore 的同型修复同一个道理；导出两条路径都要。）
            check(runtimeLockRepository.takeOverForMaintenance(MaintenanceMode.BACKUP)) {
                "无法取得维护权（导出/恢复/清理正在进行），请稍后再试"
            }
            val exportDao = db.exportDao()
            exportDao.insertSnapshot(
                ExportSnapshotEntity(
                    snapshotId = snapshotId, exportType = ExportType.HTML,
                    sourceDataVersion = db.configDao().getSourceDataVersion() ?: 0L,
                    createdAt = now, expiresAt = now + 24 * 60 * 60 * 1000,
                    status = ExportSnapshotStatus.BUILDING
                )
            )

            // 3-4. 单事务复制到临时快照表 + 置 READY
            db.withTransaction {
                // 时间判定与历史页筛选口径一致：用开播时间（缺失时退回创建时间）
                val sessions = db.liveSessionDao()
                    .listClosedForStreamersBetween(streamerIds, from, to)
                sessionCount = sessions.size
                val names = namesById
                // 分区：一场直播可能换过多个，导出时按观察顺序拼成 "A → B"
                val areasBySession = db.liveSessionAreaDao().listAll()
                    .groupBy { it.sessionStableId }
                    .mapValues { (_, list) ->
                        list.sortedBy { it.observedAt }.map { it.areaLabel }.distinct().joinToString(" → ")
                    }
                for (s in sessions) {
                    val row = mapOf(
                        "stableId" to s.stableId,
                        // ★ 同时写 id 与显示名：分组/计数按 id（昵称可重名），只有显示时才用名字
                        "streamerId" to s.streamerId.toString(),
                        "streamer" to (names[s.streamerId] ?: "未知"),
                        "startTime" to s.startTime,
                        "endTime" to s.endTime,
                        "durationSeconds" to s.durationSeconds,
                        "startConfidence" to s.startConfidence?.name,
                        "endConfidence" to s.endConfidence?.name,
                        "titleAtStart" to s.titleAtStart,
                        // 没有分区记录的老场次回退到 session 上的起止分区
                        "areas" to (areasBySession[s.stableId]
                            ?: listOfNotNull(s.areaAtStart, s.areaAtEnd).distinct().joinToString(" → ")),
                        "note" to s.note
                    )
                    exportDao.insertSessionRow(
                        ExportSnapshotLiveSessionEntity(
                            snapshotId = snapshotId,
                            sessionStableId = s.stableId,
                            rowJson = json.encodeToString(
                                kotlinx.serialization.serializer<Map<String, String?>>(),
                                row.mapValues { it.value?.toString() }
                            )
                        )
                    )
                }
                exportDao.casStatus(snapshotId, ExportSnapshotStatus.BUILDING, ExportSnapshotStatus.READY)
                // 记下"快照真的建出来了"：取消日志不能断言一个不存在的快照（见 onFailure）。
                historySnapshotCreated = true
            }

            // 5. 快照已 READY，后续读取走临时表、不再触碰业务表。
        } catch (e: Throwable) {
            // 构建阶段失败/被取消：快照会永远留在 BUILDING（findUnfinished() 会一直看得见它，
            // 而 deleteExpired 只清已到期的行）。终态必须现在写，且必须在 NonCancellable 里
            // （见 markSnapshotTerminal）—— 协程被取消后再做挂起写根本不会落库。
            markSnapshotTerminal(snapshotId, terminalStatusFor(e))
            throw e
        } finally {
            // ★ 释放维护权是 suspend 的 Room 写：协程被取消后这句话不会执行，
            //   maintenanceMode 就永久停在 'BACKUP'；而取租约（acquireOrTakeoverLease）
            //   与再次进入维护都要求 'OFF'，于是**监控拿不到租约、彻底停摆**，
            //   只有下次冷启动的 resetStaleMaintenance 能自救（ConfigDaos 的注释写明了这个后果）。
            exitMaintenanceSafely()
        }

        // 6-7. 读取临时快照并写出。
        // 状态机必须闭环：无论中途哪一步失败，都不能把快照永久留在 CONSUMING
        // （原实现只置 CONSUMING 而失败时无人回收，`deleteExpired` 只处理已到期的行，
        //   而本流程创建的快照 expiresAt = 创建+24h —— 期间诊断包会持续暴露不一致）。
        val exportDao = db.exportDao()
        try {
            // 状态迁移必须检查 CAS 结果：原实现全部丢弃返回值，迁移失败仍继续并报告成功。
            check(
                exportDao.casStatus(snapshotId, ExportSnapshotStatus.READY, ExportSnapshotStatus.CONSUMING) > 0
            ) { "导出状态异常：快照未处于 READY，可能已被清理或并发消费" }
            val rows = exportDao.listSessionRows(snapshotId)
            // ★ 行解码失败原先被静默丢弃（mapNotNull { runCatching { … }.getOrNull() }）：
            //   统计丢了几行用户完全看不出来，却依旧报告"导出成功"。这里数出来，
            //   一条写进 application_error_log、一条如实标在报告抬头。
            val decoded = ArrayList<Map<String, String?>>(rows.size)
            var unparsableRows = 0
            for (row in rows) {
                val parsed = runCatching {
                    json.decodeFromString(kotlinx.serialization.serializer<Map<String, String?>>(), row.rowJson)
                }.getOrNull()
                if (parsed == null) unparsableRows++ else decoded.add(parsed)
            }
            // 全部行都解析失败 = 报告里不会有任何内容，绝不能生成一份空报告还说成功
            check(decoded.isNotEmpty() || rows.isEmpty()) {
                "快照里 ${rows.size} 行数据全部无法解析，未生成文件"
            }
            // 进行中的场次不在取数结果里（查询带 endTime IS NOT NULL）：先数一次，
            // 才能把"确实没有记录"与"只有一场还没结束"分开说，而不是一律断言"没有直播记录"。
            val inProgressCount = countInProgress(streamerIds, from, to)
            check(decoded.isNotEmpty()) {
                "所选主播在该范围内暂无已结束的直播记录${inProgressHint(inProgressCount)}，未生成文件"
            }
            if (unparsableRows > 0) {
                logExportFailure(
                    "直播历史导出：${unparsableRows} 行快照数据无法解析，已从报告中跳过（共 ${rows.size} 行）"
                )
            }

            val withRecordIds = decoded.mapNotNull { it["streamerId"]?.toLongOrNull() }.toSet()
            val emptyStreamerCount = streamerIds.count { it !in withRecordIds }
            val requestedName = buildFileNameFromRows(decoded, selectedNames, ExportNaming.Kind.HISTORY)
            // ★ HTML 拼装 + 写盘放到 IO/Default（台账 H19-2.1/C4/D1）：
            //   记录数没有上限（用户可选"全部"），几千行时字符串拼装与 MediaStore 写入
            //   都是实打实的主线程阻塞 —— 封面保存路径已经因为同类问题真崩过一次。
            val html = withContext(Dispatchers.Default) {
                buildHtml(
                    rows = decoded,
                    rangeLabel = rangeLabel,
                    streamerIds = streamerIds,
                    selectedNames = selectedNames,
                    sectionTitles = sectionTitles,
                    emptyStreamerCount = emptyStreamerCount,
                    unparsableRows = unparsableRows,
                    inProgressCount = inProgressCount
                )
            }
            // ★ 回读 MediaStore 的实际文件名（同名时系统会自动追加 " (1)"）：
            //   原先回报的是**请求名**，用户按提示去 Download 里找的是另一个名字，找不到文件。
            val writtenName = writeToDownloads(requestedName, html, "text/html")

            // ★ 文件已经写出去了，后面的事务只做"记账"（台账 H19-C10）：
            //   原先 CAS 或审计抛异常会让整个导出返回 failure，用户看到"导出失败"，
            //   但 Download 里其实已经有文件了 —— 于是反复重试、产生一堆同名副本。
            //   记账失败只降级为警告，不改写"导出成功"这个事实。
            val bookkeeping = runCatching {
                check(
                    exportDao.casStatus(snapshotId, ExportSnapshotStatus.CONSUMING, ExportSnapshotStatus.COMPLETED) > 0
                ) { "导出状态异常：快照未处于 CONSUMING" }
                logDao.insertAudit(
                    AuditLogEntity(
                        auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                        action = "EXPORT_HTML", targetType = "export_snapshot", targetStableId = snapshotId,
                        occurredAt = clock.nowWall(),
                        detailJson = "{\"sessions\":${decoded.size},\"streamers\":${selectedNames.size}," +
                            "\"emptyStreamers\":$emptyStreamerCount}"
                    )
                )
            }
            bookkeeping.onFailure {
                runCatching {
                    logDao.insertAppError(
                        ApplicationErrorLogEntity(
                            errorId = Ids.newId(), operationId = null,
                            occurredAt = clock.nowWall(),
                            errorCode = AppError.UNKNOWN,
                            detail = "导出已成功但记账失败：${it.message}".take(500)
                        )
                    )
                }
            }
            return@runCatching ExportResult(
                fileName = writtenName,
                sessionCount = decoded.size,
                streamerCount = selectedNames.size,
                emptyStreamerCount = emptyStreamerCount
            )
        } catch (e: Throwable) {
            // 失败/取消即把快照置为终态，避免留在 READY/CONSUMING 成为永久孤儿。
            // ★ 必须是 Throwable 而不是 Exception（复查发现的缺陷）：OOM 之类的 Error 不是
            //   Exception，只接 Exception 时它们会把快照永久留在 CONSUMING，而 deleteExpired
            //   只清已到期的行 —— 构建阶段那条 catch 早就是 Throwable 了，这里口径必须一致。
            markSnapshotTerminal(snapshotId, terminalStatusFor(e))
            throw e
        }
    }.onFailure { e ->
        // ★ 取消必须**原样重抛**：runCatching 会把 CancellationException 一并吞成 failure，
        //   吞掉之后用户看到的是"导出失败：StandaloneCoroutine was cancelled"这种内部英文串，
        //   而上层（viewModelScope）也失去了"这次是被取消"的语义 —— 离开页面本来不该报错。
        if (e is CancellationException) {
            // ★ 不能一律说"快照已置为 CANCELLED"（复查发现的缺陷）：取维护权阶段就被取消时
            //   根本没有快照行，那样写会在诊断包里留下一条与事实相反的记录。
            logExportFailure(
                if (historySnapshotCreated) {
                    "直播历史导出被取消（用户离开页面或主动取消），快照已置为 CANCELLED"
                } else {
                    "直播历史导出被取消（用户离开页面或主动取消），发生在建立快照之前"
                }
            )
            throw e
        }
        // 失败只在这里记一条：拿不到维护权、DAO 异常、写盘失败、MediaStore 失败原先
        // 只变成 UI banner + logcat，诊断包里查不到任何痕迹。
        logExportFailure("直播历史导出失败：${e.summary()}")
    }

    /**
     * 文件命名：规则集中在 [ExportNaming]（纯函数 + 单测），这里只负责取值与转发。
     *
     * 历史导出与统计导出**共用同一规则**，唯一区别是类型段（`直播历史` / `统计图表`）——
     * 加上它是因为用户反馈"同一主播同一区间的两份报告会生成同名文件，分不清哪个是哪个"。
     */
    private fun buildFileNameFromRows(
        rows: List<Map<String, String?>>,
        selectedNames: List<String>,
        kind: ExportNaming.Kind
    ): String = ExportNaming.forRows(rows, selectedNames, kind)

    /** 时间戳列表 + 选择的主播 + 导出类型 → 文件名（统计导出用）。 */
    private fun buildFileName(
        times: List<Long>,
        selectedNames: List<String>,
        kind: ExportNaming.Kind
    ): String = ExportNaming.of(times, selectedNames, kind)

    /**
     * 导出指定主播在 [from, to) 内的**统计图表**（用户要求：规则与命名和历史导出完全一致）。
     *
     * 与历史导出的差异只有一个：内容是按桶聚合的序列 + 内联 SVG 柱状图，
     * 而不是逐场记录。其余规则一致：
     *  - 入口 = 统计一级页（选一位或多位）与二级页（单个主播）；
     *  - 时间范围 = 全部 / 按日 / 按周 / 按月 / 按年 / 自定义；
     *  - 命名 = 实际数据的最早~最晚日期 + 主播名 / 首位主播名等N位；
     *  - 没记录的主播照样出现（空分组），全是空则不生成文件。
     *
     * 图表用**内联 SVG**而不是 JS 图表库：导出的 HTML 要能离线双击打开，
     * 依赖 CDN 的图表库在没网时会白屏。
     */
    suspend fun exportStats(
        streamerIds: List<Long>,
        from: Long,
        to: Long,
        rangeLabel: String
    ): Result<ExportResult> = runCatching {
        require(streamerIds.isNotEmpty()) { "没有选择主播" }
        val streamers = db.streamerDao().listAllIncludingDeleted()
        val namesById = streamers.associate { it.id to it.name }
        // uid 只用于"同名主播"的显示区分（见 sectionTitlesOf）
        val uidsById = streamers.associate { it.id to it.uid }
        val selectedNames = streamerIds.map { namesById[it] ?: "未知主播（$it）" }
        val sectionTitles = sectionTitlesOf(streamerIds, selectedNames, uidsById)
        val now = clock.nowWall()

        var sessionCount = 0
        var fileName = ""
        // 统计导出不建快照：取消时不需要（也不能）改快照状态，所以取消文案里不出现"快照"二字
        // （历史导出那边才会按"取消发生在建快照之前/之后"区分措辞）。
        var emptyStreamerCount = 0
        try {
            // ★ 与历史导出同因：取维护权放进 try，否则它在取消边界抛异常时 finally 不执行，
            //   maintenanceMode 会停在 'BACKUP'（监控停摆到下次冷启动）。
            check(runtimeLockRepository.takeOverForMaintenance(MaintenanceMode.BACKUP)) {
                "无法取得维护权（导出/恢复/清理正在进行），请稍后再试"
            }
            val sessions = db.liveSessionDao().listClosedForStreamersBetween(streamerIds, from, to)
            // 进行中的场次被上面这条查询的 `endTime IS NOT NULL` 排除在外：先数一次，
            // 才能在"没有记录"的提示与报告抬头里如实说明，而不是一律断言"没有直播记录"。
            val inProgressCount = countInProgress(streamerIds, from, to)
            check(sessions.isNotEmpty()) {
                "所选主播在该范围内暂无已结束的直播记录${inProgressHint(inProgressCount)}，未生成图表"
            }
            sessionCount = sessions.size
            val times = sessions.mapNotNull { it.startTime ?: it.createdAt }
            // "全部"（0..MAX）时用真实数据范围兜底：统计分桶需要一个有限区间
            // ★ 判"全部"要**两个哨兵一起看**（复查发现的缺陷）：原先只看 `from > 0L`，
            //   而 0 也是合法时间戳 —— 自定义起始年填 ≤1969 时 from 为负，会被误当成"全部"，
            //   于是桶数/标签/抬头区间都内缩，与用户选的区间、以及统计页直通 seriesCustom 的口径
            //   全部对不上。真正的"全部"是 TimeRange 里那个 (0, Long.MAX_VALUE) 哨兵对。
            val effectiveFrom = if (from > 0L || to != Long.MAX_VALUE) from else (times.minOrNull() ?: now)
            val effectiveTo = if (to == Long.MAX_VALUE) {
                // ★ 兜底上界必须是"最后一场所在日期的**次日 00:00**"，不能加固定的 86_400_000L：
                //   ① 加固定毫秒得到的是"最后一场时刻 + 24 小时"，抬头会写「~ 次日」，
                //      而文件名（ExportNaming.of(times)）只到最后一天 —— 同一份报告自相矛盾；
                //   ② 跨午夜的两场（9/13 23:00 + 9/14 01:00）跨度只有 26 小时，
                //      StatsSeriesRepository 按毫秒差算出的桶数是 1，9/14 的场次会被 coerceIn
                //      夹进 9/13 的桶里。用 plusDays(1) 而不是 +86_400_000L 是本仓库立下的规矩
                //      （TimeRange.kt：夏令时切换日不是 24 小时，加固定毫秒会偏一小时）。
                val zone = java.time.ZoneId.systemDefault()
                val last = times.maxOrNull() ?: now
                java.time.Instant.ofEpochMilli(last).atZone(zone).toLocalDate()
                    .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            } else to

            val withRecordIds = sessions.map { it.streamerId }.toSet()
            emptyStreamerCount = streamerIds.count { it !in withRecordIds }

            // ★ 上界口径（台账 B20）：effectiveTo（含"全部"兜底）是**排他上界**，
            //   与上面 listClosedForStreamersBetween(from, to) 的 `>= :from AND < :to`、
            //   与 buildStatsHtml 里显示的 `day(to - 1)` 三处完全一致。
            //   原先这里传的是 `effectiveTo - 1`，等于把上界从"结束日次日 00:00"拉回
            //   "结束日 00:00"，于是导出的统计图表同样漏掉结束日整天；
            //   删除那个 -1 就是本次修复的导出侧一半。
            //   ★ 这里刻意保留 Result 而不是 getOrNull()：取数失败与"确实没有数据"必须分开 ——
            //   原先异常被吞成 null，报告就写"该时间范围内没有直播记录"，
            //   用户据此得出"我没直播过"的错误结论。
            val seriesResults = streamerIds.map { id ->
                runCatching { statsSeriesRepository.seriesCustom(id, effectiveFrom, effectiveTo) }
            }
            var statsFailureCount = 0
            seriesResults.forEachIndexed { index, result ->
                result.exceptionOrNull()?.let { e ->
                    // 取消要原样传播：runCatching 会把 CancellationException 一起吞进来
                    if (e is CancellationException) throw e
                    statsFailureCount++
                    val who = selectedNames.getOrNull(index) ?: "未知主播"
                    logExportFailure("统计导出：主播「$who」的统计取数失败：${e.summary()}")
                }
            }
            // ★ 全部取数失败 = 报告里不会有任何数字（抬头会写成"合计 0"），绝不能生成这样一份
            //   文件还说成功（复查发现的缺陷）。与历史导出"全部行都无法解析"同一条纪律。
            check(streamerIds.isEmpty() || statsFailureCount < streamerIds.size) {
                "所选主播的统计取数全部失败（共 ${streamerIds.size} 位），未生成文件"
            }
            fileName = buildFileName(times, selectedNames, ExportNaming.Kind.STATS)
            // ★ 统计报告的 HTML（SVG + 分桶表格）也是 CPU 密集型拼装，与历史导出对齐切到 Default
            //   （第二轮审查发现：exportHistory 改了、exportStats 漏了，几千行时仍是主线程阻塞）。
            val html = withContext(Dispatchers.Default) {
                buildStatsHtml(
                    streamerNames = selectedNames,
                    sectionTitles = sectionTitles,
                    series = seriesResults,
                    rangeLabel = rangeLabel,
                    from = effectiveFrom,
                    to = effectiveTo,
                    emptyStreamerCount = emptyStreamerCount,
                    inProgressCount = inProgressCount,
                    // 取数失败的主播没有计入合计：抬头必须说清楚，否则用户会把"合计"当成自己的全部
                    statsFailureCount = statsFailureCount
                )
            }
            // 回读实际落盘的文件名：同名时 MediaStore 会追加 " (1)"，请求名不是磁盘上的名字
            fileName = writeToDownloads(fileName, html, "text/html")
        } finally {
            // 与历史导出同因：释放维护权的挂起写必须在 NonCancellable 里做
            exitMaintenanceSafely()
        }

        // ★ 文件已经写出去了，后面的记账失败不该改写结论（第二轮审查发现：这里与 exportHistory
        //   的半修状态不一致 —— 审计抛异常会让"已生成文件"的统计导出返回 failure，
        //   用户看到"导出失败"却能在 Download 里找到文件，于是反复重试产生副本）。
        runCatching {
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "EXPORT_STATS", targetType = "export_report", targetStableId = fileName,
                    occurredAt = clock.nowWall(),
                    detailJson = "{\"sessions\":$sessionCount,\"streamers\":${selectedNames.size}}"
                )
            )
        }.onFailure { e ->
            runCatching {
                logDao.insertAppError(
                    ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = AppError.UNKNOWN,
                        detail = "统计导出已成功但记账失败：${e.message}".take(500)
                    )
                )
            }
        }
        ExportResult(
            fileName = fileName,
            sessionCount = sessionCount,
            streamerCount = selectedNames.size,
            emptyStreamerCount = emptyStreamerCount
        )
    }.onFailure { e ->
        // 与历史导出同一条规矩：取消原样重抛（runCatching 会吞掉 CancellationException），
        // 其余失败留一条 application_error_log（诊断包只看这张表）。
        if (e is CancellationException) {
            logExportFailure("统计导出被取消（用户离开页面或主动取消）")
            throw e
        }
        logExportFailure("统计导出失败：${e.summary()}")
    }

    /** 统计报告：每位主播一节（汇总 + SVG 柱状图 + 分桶表格），空主播同样出现。 */
    private fun buildStatsHtml(
        streamerNames: List<String>,
        sectionTitles: List<String>,
        series: List<Result<StatsSeriesResult>>,
        rangeLabel: String,
        from: Long,
        to: Long,
        emptyStreamerCount: Int,
        inProgressCount: Int?,
        /** 统计取数失败的主播数：这些主播没有计入"合计"，抬头要如实说明。 */
        statsFailureCount: Int = 0
    ): String {
        val dayFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zone = java.time.ZoneId.systemDefault()
        fun day(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).format(dayFmt)
        fun duration(seconds: Long): String {
            val h = seconds / 3600; val m = (seconds % 3600) / 60
            return if (h > 0) "${h}小时${m}分" else "${m}分${seconds % 60}秒"
        }

        val subject = if (streamerNames.size == 1) esc(streamerNames.first())
        else "${streamerNames.size} 位主播（${streamerNames.take(20).joinToString("、") { esc(it) }}${if (streamerNames.size > 20) " 等" else ""}）"

        val totalSessions = series.sumOf { it.getOrNull()?.totals?.sessionCount ?: 0L }
        val totalSeconds = series.sumOf { it.getOrNull()?.totals?.monitoredSeconds ?: 0L }

        val sections = streamerNames.mapIndexed { index, name ->
            // 标题用 sectionTitles（同名主播已补 uid）；"取数失败"与"确实没有数据"必须分开：
            // 前者原先被 runCatching 吞成 null，报告就写"该时间范围内没有直播记录" —— 把故障说成了事实。
            val title = esc(sectionTitles.getOrNull(index) ?: name)
            val outcome = series.getOrNull(index)
            val failure = outcome?.exceptionOrNull()
            val s = outcome?.getOrNull()
            if (failure != null) {
                """<h2 class="streamer">$title<span class="meta">取数失败</span></h2>
<p class="empty">该主播的统计取数失败：${esc(failure.summary())}（已记入应用错误日志）</p>"""
            } else if (s == null || s.points.all { it.seconds == 0L && it.sessionCount == 0 }) {
                """<h2 class="streamer">$title<span class="meta">0 场</span></h2>
<p class="empty">该范围内暂无已结束的直播记录</p>"""
            } else {
                val t = s.totals
                val avg = if (t.sessionCount > 0) t.monitoredSeconds / t.sessionCount else 0L
                """<h2 class="streamer">$title
                <span class="meta">${t.sessionCount} 场　·　${duration(t.monitoredSeconds)}</span></h2>
<div class="stats">总计：${duration(t.monitoredSeconds)}　|　平均每场：${duration(avg)}　|　
已确认 ${t.confirmedSessionCount} / 暂定 ${t.provisionalSessionCount} / 人工修正 ${t.correctedSessionCount}　|　
可靠监控时长：${duration(t.reliableMonitoredSeconds)}</div>
${barChartSvg(s)}
<table><tr><th>${esc(s.granularity.label)}区间</th><th>场次</th><th>时长</th></tr>
${s.points.joinToString("\n") { p ->
                    "<tr><td>${esc(p.label)}</td><td>${p.sessionCount}</td><td>${duration(p.seconds)}</td></tr>"
                }}</table>"""
            }
        }

        val generated = com.example.bilimonitor.core.AppClocks.stamp("yyyy-MM-dd HH:mm:ss")
        return """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<title>直播统计导出</title>
<style>body{font-family:system-ui,sans-serif;margin:24px;color:#222;line-height:1.5}
table{border-collapse:collapse;width:100%;margin:8px 0 24px}
th,td{border:1px solid #ddd;padding:8px;font-size:14px;text-align:left}
th{background:#FB7299;color:#fff}
h1{font-size:22px}
h2.streamer{font-size:17px;margin:24px 0 0;padding-bottom:6px;border-bottom:2px solid #FB7299}
h2.streamer .meta{font-size:12px;font-weight:400;color:#888;margin-left:8px}
p.empty{margin:8px 0 24px;color:#999;font-size:13px}
.stats{background:#f7f7f7;padding:8px 12px;border-radius:8px;margin:10px 0;font-size:13px}
.summary{background:#f7f7f7;padding:12px;border-radius:8px;margin:16px 0}
.chart{margin:8px 0 4px}</style></head>
<body><h1>直播统计导出</h1>
<div class="summary">导出对象：$subject<br>
选择的时间范围：${esc(rangeLabel)}　|　实际统计区间：${day(from)} ~ ${day((to - 1).coerceAtLeast(from))}　|　
主播：${streamerNames.size} 位（其中 $emptyStreamerCount 位无记录）<br>
合计场次：$totalSessions　|　合计时长：${duration(totalSeconds)}${inProgressReportNote(inProgressCount)}${
            if (statsFailureCount > 0)
                "　|　其中 $statsFailureCount 位主播取数失败、未计入合计（明细见各分节）"
            else ""
        }<br>
生成时间：$generated<br>
说明：时长按"软件确认的开播/关播时刻"计算，非主播真实开播时间；分桶依据每场的开播时刻（该字段缺失时退回记录创建时刻），不是结束时刻。</div>
${sections.joinToString("\n")}
<p style="color:#999;font-size:12px">由 ${esc(appName)} App 导出；图表为内联 SVG，可离线查看。</p>
</body></html>"""
    }

    /**
     * 内联 SVG 柱状图。
     *
     * 不引外部图表库：离线打开也不能白屏。标签过多时按步长抽稀，避免挤成一团。
     */
    private fun barChartSvg(s: StatsSeriesResult): String {
        val points = s.points
        if (points.isEmpty()) return ""
        val w = 720.0; val h = 200.0
        val padLeft = 8.0; val padBottom = 26.0; val padTop = 10.0
        val plotW = w - padLeft * 2
        val plotH = h - padBottom - padTop
        val maxSeconds = (points.maxOfOrNull { it.seconds } ?: 0L).coerceAtLeast(1L)
        val slot = plotW / points.size
        val barW = (slot * 0.62).coerceAtLeast(1.5)
        val labelStep = ((points.size / 16) + 1)

        fun fmt(seconds: Long): String {
            val hh = seconds / 3600; val mm = (seconds % 3600) / 60
            return if (hh > 0) "${hh}h${mm}m" else "${mm}m"
        }

        // ★ SVG 坐标必须显式指定 Locale.ROOT：德语/法语等区域的默认小数点是逗号，
        //   "%.1f".format(x) 会写出 x="12,5" 这种非法坐标 —— 柱状图整片空白或全叠在左缘
        //   （表格里的数字不受影响，所以这个问题只在图上看得出来）。
        fun pct(v: Double): String = "%.1f".format(java.util.Locale.ROOT, v)

        val bars = points.mapIndexed { i, p ->
            val barH = plotH * (p.seconds.toDouble() / maxSeconds.toDouble())
            val x = padLeft + slot * i + (slot - barW) / 2
            val y = padTop + (plotH - barH)
            val tip = "${p.label}：${fmt(p.seconds)}（${p.sessionCount} 场）"
            """<rect x="${pct(x)}" y="${pct(y)}" width="${pct(barW)}" height="${pct(barH)}" fill="#FB7299"><title>${esc(tip)}</title></rect>"""
        }.joinToString("\n")

        val labels = points.mapIndexedNotNull { i, p ->
            if (i % labelStep != 0) null
            else {
                val x = padLeft + slot * i + slot / 2
                """<text x="${pct(x)}" y="${pct(h - 8)}" font-size="10" fill="#888" text-anchor="middle">${esc(p.label)}</text>"""
            }
        }.joinToString("\n")

        return """<svg class="chart" viewBox="0 0 $w $h" width="100%" height="$h" role="img" aria-label="时长分布">
<line x1="$padLeft" y1="${padTop + plotH}" x2="${w - padLeft}" y2="${padTop + plotH}" stroke="#ddd"/>
<text x="$padLeft" y="${padTop + 8}" font-size="10" fill="#888">峰值 ${fmt(maxSeconds)}</text>
$bars
$labels
</svg>"""
    }

    /**
     * 转义 HTML（248.15：输出安全边界）。 */
    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * 生成报告（用户定稿：**按主播分组排版**）。
     *
     * 关键点：分组依据是**选中的主播 id 列表**，不是快照里出现的名字 ——
     * 选了但没有记录的主播同样会出现，标题下写"该范围内暂无已结束的直播记录"。
     * 这样"我导出了哪几位"与"报告里看到哪几位"永远一致。
     *
     * 为什么组键必须是 id：B 站昵称可以重名（streamer.name 无唯一索引），按名字分组会让
     * 两位同名主播的每一节都列出两人的全部场次、各写一遍"N 场 · 时长"，而抬头"场次 N"
     * 又是对的 —— 同一份文件自相矛盾。名字只在最终显示时才映射，确实重名时由
     * [sectionTitlesOf] 补 uid 区分。
     */
    private fun buildHtml(
        rows: List<Map<String, String?>>,
        rangeLabel: String,
        streamerIds: List<Long>,
        selectedNames: List<String>,
        sectionTitles: List<String>,
        emptyStreamerCount: Int,
        unparsableRows: Int,
        inProgressCount: Int?
    ): String {
        val total = rows.size
        val totalSeconds = rows.sumOf { it["durationSeconds"]?.toLongOrNull() ?: 0L }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val zone = java.time.ZoneId.systemDefault()
        fun time(v: String?): String =
            v?.toLongOrNull()?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).format(fmt) } ?: "—"
        fun duration(v: String?): String {
            val s = v?.toLongOrNull() ?: return "—"
            val h = s / 3600; val m = (s % 3600) / 60
            return if (h > 0) "${h}小时${m}分" else "${m}分${s % 60}秒"
        }

        // 行按**主播 id** 分组（理由见方法注释）；组内按开播时间倒序（最近的在前）
        val rowsByStreamer = rows.groupBy { it["streamerId"] ?: "" }

        val subject = if (selectedNames.size == 1) esc(selectedNames.first())
        else "${selectedNames.size} 位主播（${selectedNames.take(20).joinToString("、") { esc(it) }}${if (selectedNames.size > 20) " 等" else ""}）"

        fun sessionRow(r: Map<String, String?>): String {
            val conf = r["startConfidence"] ?: "—"
            val confColor = when (conf) {
                "CORRECTED" -> "#2E7D32"
                "PROVISIONAL" -> "#E8A03C"
                else -> "#757575"
            }
            val note = r["note"]?.takeIf { it.isNotBlank() }
            return """<tr><td>${esc(r["titleAtStart"])}</td>
               <td>${esc(r["areas"]?.takeIf { it.isNotBlank() } ?: "—")}</td>
               <td>${time(r["startTime"])} → ${time(r["endTime"])}</td>
               <td>${duration(r["durationSeconds"])}</td>
               <td style="color:$confColor">${esc(conf)}</td>
               ${if (note != null) "<td>${esc(note)}</td>" else "<td>—</td>"}</tr>"""
        }

        val sections = streamerIds.mapIndexed { index, streamerId ->
            val own = (rowsByStreamer[streamerId.toString()] ?: emptyList())
                .sortedByDescending { it["startTime"]?.toLongOrNull() ?: 0 }
            val ownSeconds = own.sumOf { it["durationSeconds"]?.toLongOrNull() ?: 0L }
            // 空分组只写"0 场"：再跟一个"0分0秒"是噪音
            val meta = if (own.isEmpty()) "0 场" else "${own.size} 场　·　${duration(ownSeconds.toString())}"
            val title = esc(sectionTitles.getOrNull(index) ?: selectedNames.getOrNull(index) ?: "未知主播")
            val heading = """<h2 class="streamer">$title
                <span class="meta">$meta</span></h2>"""
            if (own.isEmpty()) {
                // 取数查询带 `endTime IS NOT NULL`：能走到这里的只有"没有**已结束**的场次"，
                // 原话"没有直播记录"会把"进行中"也算成"没播过"。
                "$heading\n<p class=\"empty\">该范围内暂无已结束的直播记录</p>"
            } else {
                """$heading
<table><tr><th>标题</th><th>分区</th><th>时间</th><th>时长</th><th>可信度</th><th>备注</th></tr>
${own.joinToString("\n") { sessionRow(it) }}</table>"""
            }
        }

        // 丢行与进行中场次都是"必须让用户看见"的事实，但都不该打断导出：
        // 用一行小字标在抬头，而不是只写进日志（用户看报告时不会去翻 logcat）。
        val inProgressNote = inProgressReportNote(inProgressCount)
        val unparsableNote = if (unparsableRows > 0) {
            "<span style=\"color:#E8A03C\">注意：有 $unparsableRows 行数据无法解析，未包含在本报告中。</span><br>\n"
        } else ""

        val generated = com.example.bilimonitor.core.AppClocks.stamp("yyyy-MM-dd HH:mm:ss")
        return """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<title>直播历史导出</title>
<style>body{font-family:system-ui,sans-serif;margin:24px;color:#222;line-height:1.5}
table{border-collapse:collapse;width:100%;margin:8px 0 24px}
th,td{border:1px solid #ddd;padding:8px;font-size:14px;text-align:left}
th{background:#FB7299;color:#fff}
h1{font-size:22px}
h2.streamer{font-size:17px;margin:24px 0 0;padding-bottom:6px;border-bottom:2px solid #FB7299}
h2.streamer .meta{font-size:12px;font-weight:400;color:#888;margin-left:8px}
p.empty{margin:8px 0 24px;color:#999;font-size:13px}
.summary{background:#f7f7f7;padding:12px;border-radius:8px;margin:16px 0}</style></head>
<body><h1>直播历史导出</h1>
<div class="summary">导出对象：$subject<br>
选择的时间范围：${esc(rangeLabel)}　|　主播：${selectedNames.size} 位（其中 $emptyStreamerCount 位无记录）　|　场次：$total　|　总时长：${duration(totalSeconds.toString())}${inProgressNote}<br>
${unparsableNote}生成时间：$generated<br>
数据可信度说明：<span style="color:#E8A03C">PROVISIONAL</span>=自动记录（暂定），<span style="color:#2E7D32">CORRECTED</span>=人工修正，CONFIRMED=已确认。</div>
$sections
<p style="color:#999;font-size:12px">由 ${esc(appName)} App 导出；startTime 为软件确认开播时刻，非主播真实开播时间。</p>
</body></html>"""
    }

    /**
     * 报告里每节标题用的显示名。
     *
     * 为什么需要：B 站昵称**可以重名**，`streamer.name` 没有唯一索引。两位同名主播的报告里
     * 会出现两节一模一样的标题，用户无法判断哪一节是自己的主播（分组与计数本身按 id 做，
     * 见 [buildHtml] / [buildStatsHtml]）。只在**确实重名**时补 B 站 uid 区分 ——
     * uid 就显示在主播详情页（"UID 12345"），平时不加后缀、不给用户添噪音。
     */
    private fun sectionTitlesOf(
        streamerIds: List<Long>,
        selectedNames: List<String>,
        uidsById: Map<Long, Long>
    ): List<String> {
        val nameCounts = selectedNames.groupingBy { it }.eachCount()
        return selectedNames.mapIndexed { index, name ->
            val uid = streamerIds.getOrNull(index)?.let { uidsById[it] }
            if ((nameCounts[name] ?: 0) > 1 && uid != null) "$name（uid $uid）" else name
        }
    }

    /** 取消 → CANCELLED，其它异常 → FAILED（都是实体里既有取值，不新增枚举值）。 */
    private fun terminalStatusFor(e: Throwable): ExportSnapshotStatus =
        if (e is CancellationException) ExportSnapshotStatus.CANCELLED else ExportSnapshotStatus.FAILED

    /**
     * 把快照从任一"未完成态"推进到终态（失败/取消）。
     *
     * 为什么逐个 CAS：快照可能在 BUILDING（复制中）/ READY（待消费）/ CONSUMING（写出中）
     * 任一阶段失败，只回收 CONSUMING 会让另外两种变成孤儿（`findUnfinished()` 会一直看得见
     * 它们，而 `deleteExpired` 只清已到期的行，本流程创建的行要 24 小时后才到期）。
     * CAS 返回 0 = 状态不匹配（例如已 COMPLETED），继续试下一个；全不匹配说明这行已被清理。
     *
     * 内部自带 NonCancellable：调用点都在 catch/finally 里，此时协程**可能已经被取消**，
     * 任何挂起写都会立刻抛 CancellationException 而根本不落库 ——
     * 这正是"快照留在中间态 / 维护权不释放"的同一个成因（见 [exitMaintenanceSafely]）。
     */
    private suspend fun markSnapshotTerminal(snapshotId: String, next: ExportSnapshotStatus) {
        withContext(NonCancellable) {
            val dao = db.exportDao()
            for (state in listOf(
                ExportSnapshotStatus.CONSUMING, ExportSnapshotStatus.READY, ExportSnapshotStatus.BUILDING
            )) {
                // ★ 收尾失败绝不能顶掉原始异常（复查发现的缺陷）：调用点紧接着就是 `throw e`，
                //   若让 SQLiteFullException 之类冒出去，用户看到的会是它而不是真正的原因，
                //   取消也会被改写成失败。所以各自 runCatching。
                val changed = runCatching { dao.casStatus(snapshotId, state, next) }.getOrDefault(0)
                if (changed > 0) return@withContext
            }
        }
    }

    /**
     * 释放维护权。
     *
     * ★ 为什么必须 NonCancellable：`exitMaintenance` 是 suspend 的 Room 写，而导出由界面的
     *   `viewModelScope.launch` 调用，**用户离开页面就会取消协程**；取消之后再执行挂起写会
     *   立刻抛 CancellationException，finally 里这一句就等于没写 —— `maintenanceMode` 永久停在
     *   'BACKUP'，而取租约（acquireOrTakeoverLease）与再次进入维护都要求 'OFF'，
     *   于是**监控拿不到租约、彻底停摆**，只有下次冷启动的 resetStaleMaintenance 能自救
     *   （ConfigDaos 的注释自己写明了"监控也会永久停摆"这个后果）。
     *
     * 释放失败同样要留痕：那意味着上面这个后果已经发生，诊断包里不能只有"监控无缘无故停了"。
     */
    private suspend fun exitMaintenanceSafely() {
        withContext(NonCancellable) {
            val released = runCatching {
                runtimeLockRepository.exitMaintenance(MaintenanceMode.BACKUP)
            }
            if (released.getOrNull() != true) {
                // ★ 返回 0 **不等于**释放失败（复查发现的缺陷）：exitMaintenance 带
                //   `WHERE maintenanceMode = :mode`，返回 0 只说明"当前不是 BACKUP" ——
                //   可能已被冷启动的 resetStaleMaintenance 改成 OFF，那其实是**已释放**。
                //   所以回读一次当前模式再下结论，否则会写一条与事实相反的告警。
                val mode = runCatching { db.runtimeLockDao().get()?.maintenanceMode }.getOrNull()
                if (mode != null && mode != MaintenanceMode.OFF) {
                    logExportFailure("导出收尾：释放维护权后 maintenanceMode 仍是 '$mode'（监控将取不到租约）")
                } else if (released.isFailure) {
                    logExportFailure("导出收尾：释放维护权抛异常：${released.exceptionOrNull()?.summary()}")
                }
            }
        }
    }

    /**
     * 导出失败/被取消的统一留痕。
     *
     * 为什么必须写 application_error_log：诊断包只看这张表，而原先真正的失败
     * （拿不到维护权、DAO 异常、写盘失败、MediaStore 失败）只变成界面 banner + logcat，
     * 事后在诊断包里查不到任何痕迹。
     *
     * detail 截到 320 字符：整段堆栈会把诊断包里的这一列撑爆，而排查只需要原因摘要。
     * 内部自带 NonCancellable：调用点可能在协程已取消之后（见 [exitMaintenanceSafely]）。
     */
    private suspend fun logExportFailure(detail: String) {
        withContext(NonCancellable) {
            runCatching {
                logDao.insertAppError(
                    ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = AppError.EXPORT_FAILED, detail = detail.take(320)
                    )
                )
            }
        }
    }

    /** 异常摘要：类名 + message（message 可能为空），只用于日志 detail。 */
    private fun Throwable.summary(): String =
        this::class.java.simpleName.ifBlank { "异常" } + "：" +
            (message?.takeIf { it.isNotBlank() } ?: "无详细信息")

    /**
     * 区间内**尚未结束**（进行中）的场次数；null = 至少有一位主播数不出来。
     *
     * 为什么单独数一次：导出的取数查询（[com.example.bilimonitor.data.local.dao.LiveSessionDao.listClosedForStreamersBetween]）
     * 带 `endTime IS NOT NULL`，进行中的场次被静默排除，于是"确实没有记录"与"只有一场还没结束"
     * 在报告里长得一模一样。这里复用既有的 `countUnclosedForStreamerBetween`
     * （同一套 `COALESCE(startTime, createdAt)` 口径），没有新增查询。
     *
     * 为什么失败时返回 null 而不是 0：这只是抬头的一句补充说明，
     * **宁可不说，也不能写一个偏小的数字**（部分主播计数失败时报出的 N 会小于真实值）。
     */
    private suspend fun countInProgress(streamerIds: List<Long>, from: Long, to: Long): Int? {
        var total = 0
        for (id in streamerIds) {
            total += try {
                db.liveSessionDao().countUnclosedForStreamerBetween(id, from, to)
            } catch (e: CancellationException) {
                throw e      // 取消要原样传播，不能当成"数不出来"
            } catch (e: Exception) {
                return null  // 数不出来就不写这一句
            }
        }
        return total
    }

    /** 失败提示里的进行中场次说明："（另有 N 场尚未结束）"；数不出来时什么都不加。 */
    private fun inProgressHint(count: Int?): String =
        if (count != null && count > 0) "（另有 $count 场尚未结束）" else ""

    /** 报告抬头里的进行中场次说明：与 [inProgressHint] 同源，措辞更完整。 */
    private fun inProgressReportNote(count: Int?): String =
        if (count != null && count > 0) "　|　另有 $count 场尚未结束（进行中），未计入本报告" else ""

    /**
     * 写进系统 Download 目录，返回**实际落盘的文件名**。
     *
     * 为什么要回读：MediaStore 遇到同名文件会自动追加 " (1)"，而回给用户/日志的一直是**请求名**，
     * 用户按提示去 Download 里找的是另一个名字（找不到文件）。[ExportResult.fileName] 因此取这里的返回值。
     */
    private suspend fun writeToDownloads(name: String, content: String, mime: String): String =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建导出文件")
            try {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("无法写入导出文件")
                // ★ "转正"（IS_PENDING=0）必须和写入在同一个 try 里，且必须检查返回值：
                //   原先它在 try/catch 之外，抛异常时就跳过了下面的清理 —— Download 里留下一条
                //   用户看不见的 IS_PENDING=1 半成品；返回值又被丢弃，于是"转正失败"（文件对用户
                //   不可见）照样报告"导出成功"。
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                if (resolver.update(uri, done, null, null) <= 0) {
                    throw IllegalStateException("无法完成导出文件写入")
                }
            } catch (e: Exception) {
                // ★ 写失败要清掉半成品（台账 H19-C11）：否则 Download 里留一条对用户不可见的
                //   IS_PENDING=1 行，只能等系统 7 天后回收。封面保存路径早有这个 delete，导出没有。
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            // 回读实际文件名；查不到就退回请求名（某个 provider 不支持这列时，宁给请求名也不给空串）
            runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: name
        }
}
