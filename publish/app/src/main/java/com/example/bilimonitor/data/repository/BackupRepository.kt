package com.example.bilimonitor.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.data.local.BackupScopeType
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.RestoreConflictAction
import com.example.bilimonitor.data.local.RestoreConflictType
import com.example.bilimonitor.data.local.RestoreFinishReason
import com.example.bilimonitor.data.local.RestoreRunStatus
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.dao.RestoreDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import com.example.bilimonitor.data.local.entity.StreamerEntity
import com.example.bilimonitor.data.local.entity.RestoreConflictEntity
import com.example.bilimonitor.data.local.entity.RestoreRunEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

// ---------- 备份文件 DTO（0.6.19） ----------

@Serializable
data class BackupManifest(
    val backupId: String,
    val backupSchemaVersion: Int,
    val formatVersion: Int,
    val roomSchemaVersion: Int,
    val createdAt: Long,
    val sourceDataVersion: Long,
    val scopeType: String,
    val selectedStreamerStableIds: List<String>,
    val recordCounts: Map<String, Long>,
    val checksumAlgorithm: String = "SHA-256",
    val checksum: String,
    /**
     * `settings` 段的校验和（对**文件里的原始 settings 文本**计算，口径与 [checksum] 一致）。
     *
     * ★ **必须带默认值 `null`**：旧备份文件里没有这个键，而 `Json { ignoreUnknownKeys = true }`
     *   只解决"多字段"，解决不了"缺字段" —— 没有默认值会让旧备份在恢复时**反序列化直接失败**，
     *   用户看到的是"备份文件损坏"这种完全误导的结论（本项目已经踩过两次）。
     *   缺这个键时按"旧文件未校验设置段"处理（不能判成损坏）。
     *   null 还用于一种情况：导出时设置段整体为空，没有可校验的文本。
     */
    val settingsChecksum: String? = null,
    val appVersionName: String,
    val producerDeviceBootId: String? = null
)

@Serializable
data class BackupBody(
    val streamers: List<BackupStreamer>,
    val tags: List<BackupTag>,
    val groups: List<BackupGroup>,
    val tagRefs: List<BackupTagRef>,
    val groupRefs: List<BackupGroupRef>,
    val sessions: List<BackupSession>,
    val events: List<BackupEvent>,
    val titles: List<BackupTitle> = emptyList(),
    val corrections: List<BackupCorrection>,
    val policies: List<BackupPolicy>,
    /**
     * 场次分区变更记录（用户定稿功能）。
     * 新增字段用默认值，配合 precheck 的"原始 body 校验和"策略，
     * 旧备份（没有这个键）依然能通过校验（详见 [BackupChecksum.ofRawBody]）。
     */
    val areas: List<BackupArea> = emptyList()
)

@Serializable
data class BackupArea(
    val sessionStableId: String,
    val areaLabel: String,
    val parentAreaName: String? = null,
    val areaName: String? = null,
    val observedAt: Long
)

@Serializable
data class BackupStreamer(
    val stableId: String, val uid: Long, val roomId: Long?, val shortRoomId: Long?,
    val name: String, val nameLocked: Boolean, val avatarUrl: String?, val roomTitle: String?,
    val parentAreaName: String?, val areaName: String?, val coverUrl: String?, val liveUrl: String?,
    val confirmedLiveStatus: String, val deleted: Boolean, val monitoringEnabled: Boolean,
    val isFavorite: Boolean, val createdAt: Long
)

@Serializable
data class BackupTag(val stableId: String, val name: String, val colorHex: String?, val sortOrder: Int)

@Serializable
data class BackupGroup(val stableId: String, val name: String, val sortOrder: Int, val collapsed: Boolean)

@Serializable
data class BackupTagRef(val tagStableId: String, val streamerStableId: String)

@Serializable
data class BackupGroupRef(val groupStableId: String, val streamerStableId: String)

@Serializable
data class BackupSession(
    val stableId: String, val streamerStableId: String, val startTime: Long?, val endTime: Long?,
    val startSource: String?, val endSource: String?, val durationSource: String?,
    val startConfidence: String?, val endConfidence: String?, val durationConfidence: String?,
    val startLocked: Boolean, val endLocked: Boolean, val durationLocked: Boolean,
    val titleAtStart: String?, val titleAtEnd: String?, val areaAtStart: String?, val areaAtEnd: String?,
    val coverUrlAtStart: String?, val coverUrlAtEnd: String?, val durationSeconds: Long?,
    val endReason: String?, val note: String?,
    val startTimeZone: String? = null, val endTimeZone: String? = null
)

@Serializable
data class BackupEvent(
    val eventId: String, val streamerStableId: String, val sessionStableId: String?,
    val eventType: String, val eventConfirmedAt: Long
)

@Serializable
data class BackupTitle(val sessionStableId: String, val title: String, val observedAt: Long)

@Serializable
data class BackupCorrection(
    val correctionId: String, val sessionStableId: String, val baseCorrectionVersion: Long,
    val newCorrectionVersion: Long, val changedFieldsJson: String, val source: String, val createdAt: Long
)

@Serializable
data class BackupPolicy(
    val streamerStableId: String, val enabled: Boolean, val intervalSeconds: Int?,
    val notifyStart: Boolean, val notifyEnd: Boolean, val notifyRound: Boolean,
    val startConfirmationCount: Int?, val endConfirmationCount: Int?,
    /** 标题/分区变化通知开关（可空：旧备份里没有这两个键，按"默认关闭"处理）。 */
    val notifyTitleChange: Boolean? = null,
    val notifyAreaChange: Boolean? = null
)

@Serializable
data class BackupFile(
    val manifest: BackupManifest,
    val body: BackupBody,
    /**
     * 应用级设置（用户要求：配置文件要覆盖"应用软件本身的设置"）。
     *
     * 放在 body 之外、默认 null，是为了**不破坏旧备份的校验和** ——
     * 校验和是对 body 的重新序列化结果算的，一旦往 body 里加字段，
     * 旧文件的 body 重新编码后会多出该字段，校验和必然不匹配，旧备份会被判为损坏。
     */
    val settings: BackupSettings? = null
)

@Serializable
data class BackupSettings(
    val monitoring: BackupMonitoringSettings? = null,
    val quietHours: BackupQuietHours? = null,
    val capacity: BackupCapacity? = null,
    val importMaxCount: Int? = null,
    /** 前台常驻通知的自定义文案（可空：旧备份里没有这一项，按"用默认文案"处理）。 */
    val notificationTitle: String? = null,
    val notificationText: String? = null
)

/** 监控引擎设置（对应 monitoring_config 单行表）。 */
@Serializable
data class BackupMonitoringSettings(
    val monitoringEnabled: Boolean,
    val mode: String,
    val intervalSeconds: Int,
    val batchSize: Int,
    val maxConcurrency: Int,
    val timeoutSeconds: Int,
    val maxRetries: Int,
    val retryBaseSeconds: Int,
    val maxRetryDelaySeconds: Int,
    val circuitBreakerThreshold: Int,
    val circuitBreakerRecoverySeconds: Int,
    val aggregationEnabled: Boolean,
    val aggregationThreshold: Int,
    val aggregationWindowSeconds: Int,
    val batchCooldownSeconds: Int,
    val freshnessStaleSeconds: Int,
    val startConfirmationCount: Int,
    val endConfirmationCount: Int,
    val placeholderText: String,
    /**
     * v9 新增两项。
     *
     * ★ **必须带默认值**：旧备份文件里没有这两个键，而 `Json { ignoreUnknownKeys = true }`
     *   只解决"多字段"，解决不了"缺字段" —— 没有默认值会让旧备份在恢复时**反序列化直接失败**，
     *   用户会看到"备份文件损坏"这种完全误导的结论。默认值同时保证：
     *   从旧备份恢复时这两项回到默认，而不是把本地现值改成 0。
     */
    val retryFailureRatioPercent: Int = 50,
    val banRecheckIntervalSeconds: Int = 1800,
    /**
     * 熔断总开关（v10 新增）。
     *
     * ★ **必须带默认值 `true`**，理由与上面两项逐字相同：旧备份文件里没有这个键，
     *   `Json { ignoreUnknownKeys = true }` 只解决"多字段"，解决不了"缺字段"——
     *   没有默认值会让旧备份在恢复时**反序列化直接失败**，用户看到的是
     *   "备份文件损坏"这种完全误导的结论（本项目已经踩过两次）。
     *   默认 `true` 还保证"从旧备份恢复"与"引入开关之前的既有行为"一致。
     */
    val circuitBreakerEnabled: Boolean = true
)

/** 免打扰时段（DataStore）。 */
@Serializable
data class BackupQuietHours(val enabled: Boolean, val startMinutes: Int, val endMinutes: Int)

/** 历史容量上限（DataStore）。 */
@Serializable
data class BackupCapacity(val mode: String, val maxRecords: Int)

data class RestoreSummary(
    val restoreRunId: String,
    val restoredCounts: Map<String, Long>,
    val skippedCounts: Map<String, Long>,
    val conflictCounts: Map<String, Long>,
    val warnings: List<String>
)

/**
 * 备份 body 的校验和计算（独立出来是为了能被单元测试直接覆盖）。
 *
 * 这里有一个**很容易踩的坑**：校验和如果按"把 body 反序列化成数据类、再重新序列化"
 * 的文本计算，那么每次给 `BackupBody` 加字段（哪怕带默认值），重新序列化出来的文本
 * 都会多出这个键，于是历史上所有备份的校验和立刻失配、被判定为"文件已损坏"。
 * 因此校验一律以**文件里的原始 body 文本**为准；重新编码的结果只作为兜底兼容。
 */
internal object BackupChecksum {
    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 文件里 `body` 元素的原始文本的哈希；结构升级后依然与当年写出的值一致。 */
    fun ofRawBody(json: Json, content: String): String? = runCatching {
        json.parseToJsonElement(content).jsonObject["body"]?.let { sha256(it.toString()) }
    }.getOrNull()

    /** 用当前数据类重新编码后的哈希（兼容老实现，只作兜底）。 */
    fun ofReencoded(json: Json, body: BackupBody): String =
        sha256(json.encodeToString(BackupBody.serializer(), body))

    /**
     * 文件里 `settings` 元素的原始文本的哈希（口径与 [ofRawBody] 逐字一致）。
     *
     * 为什么不"把 settings 反序列化再重新编码"算：那等于按**当前** DTO 补上所有新增的默认字段，
     * 于是以后每给 [BackupSettings] 加一个字段，历史上所有备份的设置段校验和立刻失配、被误判成
     * "文件已损坏" —— body 侧踩过这个坑（见本对象的说明），设置段不能重蹈。
     *
     * 返回 null = 文件里没有可用的 settings 文本（旧文件、字段缺失、或 JSON 结构不对）：
     * 此时调用方必须按"无法校验"降级，**不得**判成损坏。
     */
    fun ofRawSettings(json: Json, content: String): String? = runCatching {
        json.parseToJsonElement(content).jsonObject["settings"]
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.let { sha256(it.toString()) }
    }.getOrNull()

    /** 清单里声明的设置段校验和是否与文件里的原始 settings 文本相符。 */
    fun matchesSettings(json: Json, content: String, expected: String): Boolean =
        expected == ofRawSettings(json, content)

    /** 清单里的校验和是否与文件内容相符。 */
    fun matches(json: Json, content: String, body: BackupBody, expected: String): Boolean =
        expected == ofRawBody(json, content) || expected == ofReencoded(json, body)
}

/**
 * 备份 / 恢复（0.6.19 / 0.6.20 / 0.6.33.1 / 原规范 55）。
 * Runtime lease/fencingToken/notificationId/登录凭证不得进入业务备份（0.6.39）。
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val restoreDao: RestoreDao,
    private val logDao: LogDao,
    private val runtimeLockRepository: RuntimeLockRepository,
    private val configRepository: ConfigRepository,
    private val quietHoursRepository: QuietHoursRepository,
    private val maintenanceRepository: MaintenanceRepository,
    private val importSettingsRepository: ImportSettingsRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val clock: AppClock
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        const val FORMAT_VERSION = 1

        /**
         * 备份结构版本。
         *  - 1：主播 / 标签 / 分组 / 场次 / 事件 / 标题 / 修正 / 策略
         *  - 2：在 [BackupFile.settings] 中加入应用设置（监控、免打扰、容量、导入上限）
         *
         * 版本只用于"新文件被旧应用拒绝"的方向校验（`> BACKUP_SCHEMA_VERSION` 才拒绝），
         * 旧版本文件仍可导入；`settings` 缺失时按"文件里没有设置"处理。
         */
        const val BACKUP_SCHEMA_VERSION = 2

        /**
         * 可读入内存的单份备份文件上限。
         *
         * 读取路径是 `readBytes().toString(UTF_8)`：**任意大小**的文件都会被整份读进内存，
         * 用户误选一个几百 MB 的文件时 OOM 被 `getOrNull()` 一并吞掉，
         * 表现出来只是"无法读取所选文件" —— 用户完全不知道是自己选错了文件。
         * 这里超限时直接拒绝并说明原因（备份是 JSON 文本，正常几 MB 量级）。
         */
        const val MAX_BACKUP_FILE_BYTES = 64L * 1024 * 1024

        /** 审计里回写的警告条数上限（超出的部分合并成"…等 N 条"，避免一条审计行被刷爆）。 */
        private const val AUDIT_WARNING_MAX_ITEMS = 10

        /** 审计里回写的警告文本总长上限（字符）。 */
        private const val AUDIT_WARNING_MAX_CHARS = 500

        /**
         * 改名（→ Kaoru）之前导出的文件名格式，必须继续能匹配到。
         * 写死在这里是刻意的：它是**历史事实**，不该跟着 [R.string.app_name] 一起变。
         */
        private const val OLD_BACKUP_NAME_PATTERN = "主播监控备份_%.json"
    }

    // ---------- 导出 ----------

    suspend fun serializeBackup(): BackupFile {
        val now = clock.nowWall()
        val sdv = db.configDao().getSourceDataVersion() ?: 0L
        val streamers = db.streamerDao().listAllIncludingDeleted()
        val tags = db.taxonomyDao().listTags()
        val groups = db.taxonomyDao().listGroups()
        val tagRefs = db.taxonomyDao().listTagRefs()
        val groupRefs = db.taxonomyDao().listGroupRefs()
        val sessions = db.liveSessionDao().listAll()
        val events = db.liveEventDao().listAll()
        val corrections = db.liveSessionCorrectionDao().listAll()
        val titleRows = db.liveSessionTitleDao().listAll()

        val sidById = streamers.associate { it.id to it.stableId }
        val tagIdByStable = tags.associate { it.stableId to it.id }
        val groupIdByStable = groups.associate { it.stableId to it.id }
        val sessionIdByStable = sessions.associate { it.id to it.stableId }

        val body = BackupBody(
            streamers = streamers.map {
                BackupStreamer(
                    it.stableId, it.uid, it.roomId, it.shortRoomId, it.name, it.nameLocked,
                    it.avatarUrl, it.roomTitle, it.parentAreaName, it.areaName, it.coverUrl, it.liveUrl,
                    it.confirmedLiveStatus.name, it.deletedAt != null, it.monitoringEnabled,
                    it.isFavorite, it.createdAt
                )
            },
            tags = tags.map { BackupTag(it.stableId, it.name, it.colorHex, it.sortOrder) },
            groups = groups.map { BackupGroup(it.stableId, it.name, it.sortOrder, it.collapsed) },
            tagRefs = tagRefs.mapNotNull { r ->
                val s = sidById[r.streamerId] ?: return@mapNotNull null
                val t = tags.firstOrNull { it.id == r.tagId }?.stableId ?: return@mapNotNull null
                BackupTagRef(t, s)
            },
            groupRefs = groupRefs.mapNotNull { r ->
                val s = sidById[r.streamerId] ?: return@mapNotNull null
                val g = groups.firstOrNull { it.id == r.groupId }?.stableId ?: return@mapNotNull null
                BackupGroupRef(g, s)
            },
            sessions = sessions.map { s ->
                BackupSession(
                    s.stableId, sidById[s.streamerId] ?: "", s.startTime, s.endTime,
                    s.startSource?.name, s.endSource?.name, s.durationSource?.name,
                    s.startConfidence?.name, s.endConfidence?.name, s.durationConfidence?.name,
                    s.startLocked, s.endLocked, s.durationLocked,
                    s.titleAtStart, s.titleAtEnd, s.areaAtStart, s.areaAtEnd,
                    s.coverUrlAtStart, s.coverUrlAtEnd, s.durationSeconds, s.endReason, s.note,
                    s.startTimeZone, s.endTimeZone
                )
            },
            events = events.mapNotNull { e ->
                val sid = sidById[e.streamerId] ?: return@mapNotNull null
                BackupEvent(e.eventId, sid, e.sessionStableId, e.eventType.name, e.eventConfirmedAt)
            },
            titles = titleRows.map { t -> BackupTitle(t.sessionStableId, t.title, t.observedAt) },
            corrections = corrections.map { c ->
                BackupCorrection(c.correctionId, c.sessionStableId, c.baseCorrectionVersion,
                    c.newCorrectionVersion, c.changedFieldsJson, c.source.name, c.createdAt)
            },
            policies = db.policyDao().listAll().mapNotNull { p ->
                val sid = sidById[p.streamerId] ?: return@mapNotNull null
                BackupPolicy(sid, p.enabled, p.intervalSeconds, p.notifyStart, p.notifyEnd,
                    p.notifyRound, p.startConfirmationCount, p.endConfirmationCount,
                    p.notifyTitleChange, p.notifyAreaChange)
            },
            areas = db.liveSessionAreaDao().listAll().map {
                BackupArea(it.sessionStableId, it.areaLabel, it.parentAreaName, it.areaName, it.observedAt)
            }
        )

        val bodyJson = json.encodeToString(BackupBody.serializer(), body)
        val settings = readSettings()
        // 设置段也要入校验（缺陷修复）：原先只校验 body，于是"只改 settings"的
        // 篡改/半损坏文件能通过预检，监控间隔、免打扰、容量、导入上限、通知文案被静默写库。
        // 对**内存里这份 settings 的编码文本**取哈希 —— precheck 复算时用的也是**文件里的原始文本**，
        // 两者是同一个序列化器写出的同构文本，所以能对上；换成"重新编码 DTO"就会重蹈 body 的覆辙。
        val settingsChecksum = BackupChecksum.sha256(
            json.encodeToString(BackupSettings.serializer(), settings)
        )
        val manifest = BackupManifest(
            backupId = Ids.newId(),
            backupSchemaVersion = BACKUP_SCHEMA_VERSION,
            formatVersion = FORMAT_VERSION,
            roomSchemaVersion = AppDatabaseVersionProvider.version,
            createdAt = now,
            sourceDataVersion = sdv,
            scopeType = BackupScopeType.ALL_DATA.name,
            selectedStreamerStableIds = emptyList(),
            recordCounts = mapOf(
                "streamers" to body.streamers.size.toLong(),
                "sessions" to body.sessions.size.toLong(),
                "events" to body.events.size.toLong(),
                "titles" to body.titles.size.toLong(),
                "corrections" to body.corrections.size.toLong(),
                "tags" to body.tags.size.toLong(),
                "groups" to body.groups.size.toLong(),
                "policies" to body.policies.size.toLong(),
                "areas" to body.areas.size.toLong()
            ),
            checksum = BackupChecksum.sha256(bodyJson),
            settingsChecksum = settingsChecksum,
            appVersionName = appVersionName(),
            producerDeviceBootId = clock.bootId()
        )
        return BackupFile(manifest, body, settings = settings)
    }

    /**
     * 读取应用级设置。
     *
     * 刻意**不包含**与设备绑定的项：后台保活方式（前台通知/无障碍/不保证）与引导页的
     * 免责确认属于"这台设备上用户已经授予的能力"，恢复到另一台设备上会凭空声称一个
     * 并不存在的权限（例如无障碍服务没开却显示"已启用"）。同理不含登录凭证与运行时锁。
     */
    private suspend fun readSettings(): BackupSettings = BackupSettings(
        monitoring = runCatching {
            db.configDao().getConfig()?.let { c ->
                BackupMonitoringSettings(
                    monitoringEnabled = c.monitoringEnabled,
                    mode = c.mode.name,
                    intervalSeconds = c.intervalSeconds,
                    batchSize = c.batchSize,
                    maxConcurrency = c.maxConcurrency,
                    timeoutSeconds = c.timeoutSeconds,
                    maxRetries = c.maxRetries,
                    retryBaseSeconds = c.retryBaseSeconds,
                    maxRetryDelaySeconds = c.maxRetryDelaySeconds,
                    circuitBreakerThreshold = c.circuitBreakerThreshold,
                    circuitBreakerRecoverySeconds = c.circuitBreakerRecoverySeconds,
                    aggregationEnabled = c.aggregationEnabled,
                    aggregationThreshold = c.aggregationThreshold,
                    aggregationWindowSeconds = c.aggregationWindowSeconds,
                    batchCooldownSeconds = c.batchCooldownSeconds,
                    freshnessStaleSeconds = c.freshnessStaleSeconds,
                    startConfirmationCount = c.startConfirmationCount,
                    endConfirmationCount = c.endConfirmationCount,
                    placeholderText = c.placeholderText,
                    retryFailureRatioPercent = c.retryFailureRatioPercent,
                    banRecheckIntervalSeconds = c.banRecheckIntervalSeconds,
                    circuitBreakerEnabled = c.circuitBreakerEnabled
                )
            }
        }.getOrNull(),
        quietHours = runCatching {
            quietHoursRepository.current().let {
                BackupQuietHours(it.enabled, it.startMinutes, it.endMinutes)
            }
        }.getOrNull(),
        capacity = runCatching {
            val (mode, max) = maintenanceRepository.currentCapacity()
            BackupCapacity(mode, max)
        }.getOrNull(),
        importMaxCount = runCatching { importSettingsRepository.currentMaxCount() }.getOrNull(),
        // 通知文案：只在用户改过时才写进备份（没改过就留空，恢复时也用默认值）
        notificationTitle = runCatching { notificationSettingsRepository.current().title }.getOrNull(),
        notificationText = runCatching { notificationSettingsRepository.current().text }.getOrNull()
    )

    /** 从包信息读取版本名（原先硬编码 "1.0"，versionName 升到 1.1 后清单里仍是旧值）。 */
    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    fun serializeToString(file: BackupFile): String =
        json.encodeToString(BackupFile.serializer(), file)

    /** 写入公共下载目录（MediaStore，无需存储权限）。返回文件名。 */
    suspend fun exportBackup(): Result<String> = runCatching {
        // 维护权获取结果必须检查：原实现忽略返回值，与其他三处（导出 HTML、清理、恢复）
        // 的 `check(...)` 口径不一致，导致导出可与清理/恢复并发运行、读到不一致的数据。
        // 用户主动点"导出备份"时用接管版本：租约在有效期内也能立即取得维护权，
        // 否则监控每次 Tick 后的 90 秒内备份都会莫名失败。
        try {
            // ★ 取维护权必须在 try 里（复查发现的缺陷）：它是 suspend 的 Room 写，协程被取消时
            //   可能在 withContext 边界抛 CancellationException，而那一刻 UPDATE 可能已提交 ——
            //   放在 try 之外则 finally 不执行，maintenanceMode 停在 'BACKUP'，
            //   而引擎取租约要求 'OFF' ⇒ **监控停摆到下次冷启动**。
            //   （与导出 HTML / 恢复的同型修复一致。）
            check(runtimeLockRepository.takeOverForMaintenance(com.example.bilimonitor.data.local.MaintenanceMode.BACKUP)) {
                "无法取得维护权（导出/恢复/清理正在进行），请稍后再试"
            }
            val file = serializeBackup()
            val content = serializeToString(file)
            val name = "${appName()}备份_${timeStamp()}.json"
            writeToDownloads(name, content, "application/json")
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "EXPORT_BACKUP", targetType = "backup", targetStableId = file.manifest.backupId,
                    occurredAt = clock.nowWall(),
                    detailJson = "{\"sessions\":${file.manifest.recordCounts["sessions"]}}"
                )
            )
            name
        } finally {
            // ★ 释放维护权必须放在 NonCancellable 里（与 ExportRepository 同因：取消时 suspend
            //   收尾不会执行）：本函数由 UI 的 viewModelScope.launch 调用，用户一离开页面协程就被取消，
            //   于是这个 Room 写根本不执行 —— system_runtime_lock.maintenanceMode 永远停在 BACKUP，
            //   而引擎取租约要求 maintenanceMode='OFF'，监控会**永久停摆**，
            //   之后每次导出/恢复/清理还都报"正在进行"，只能等冷启动自救。
            withContext(NonCancellable) {
                runtimeLockRepository.exitMaintenance(com.example.bilimonitor.data.local.MaintenanceMode.BACKUP)
            }
        }
    }

    private suspend fun writeToDownloads(name: String, content: String, mime: String) = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            // ★ 写失败要清掉半成品（台账 H19-C11）：否则 Download 里会留一条
            //   IS_PENDING=1 的行 —— 用户看不见、文件管理器也不显示，只能等系统 7 天后回收。
            //   封面保存路径早就有这个 delete，导出这条一直没有。
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun timeStamp(): String = com.example.bilimonitor.core.AppClocks.stamp("yyyyMMdd_HHmmss")

    // ---------- 恢复 ----------

    sealed interface PrecheckResult {
        data class Ready(
            val restoreRunId: String,
            val manifest: BackupManifest,
            val conflicts: List<RestoreConflictEntity>,
            val newStreamers: Int,
            val sessionsToRestore: Int
        ) : PrecheckResult
        data class Rejected(val reason: String) : PrecheckResult
    }

    /** 恢复预检（0.6.33.1 PRECHECK → WAITING_DECISIONS；校验和/记录数逐项比对，原规范 184）。 */
    suspend fun precheck(content: String): PrecheckResult {
        // ★ 有些失败发生在"读文件"这一步而不是"解析"（超过大小上限、文件读不出来）——
        //   那种情况下 content 是空的，若一路走到解析分支就会报"文件格式无法解析"，
        //   而调用方还会把真实原因（例如"文件过大，已拒绝读取"）拼在后面：
        //   两句话互相矛盾，用户会去查 JSON 而不是查文件。这里先如实说"没读到内容"。
        if (content.isBlank()) return PrecheckResult.Rejected("未读取到文件内容（原因见后面括号里的说明）")
        val now = clock.nowWall()
        val runId = Ids.newId()
        // ★ 解析整份 JSON 是纯 CPU 工作，大备份（几 MB）在低端机上是数百毫秒级 ——
        //   原先在调用方线程（= 主线程）执行，恢复预检会把界面卡住（台账 H19-2.1/D1）。
        val file = withContext(Dispatchers.Default) { parseBackupFile(content) }
            ?: return PrecheckResult.Rejected("文件格式无法解析")

        val manifest = file.manifest
        if (manifest.formatVersion != FORMAT_VERSION) {
            return PrecheckResult.Rejected("备份容器版本不兼容（${manifest.formatVersion}），拒绝导入")
        }
        if (manifest.backupSchemaVersion > BACKUP_SCHEMA_VERSION) {
            return PrecheckResult.Rejected("备份结构版本过新（${manifest.backupSchemaVersion}），请升级应用")
        }
        if (manifest.roomSchemaVersion > AppDatabaseVersionProvider.version) {
            return PrecheckResult.Rejected("备份来自更新的数据库结构（${manifest.roomSchemaVersion}），无法直接入库")
        }
        // 校验和优先用**原始 body 元素**复算：这样即便之后往 BackupBody 里加字段，
        // 旧文件（body 里没有新键）依然能通过校验。
        // 直接重新编码数据类会把新字段（带默认值）补进去，导致所有旧备份被判成"文件已损坏"。
        if (!withContext(Dispatchers.Default) {
                BackupChecksum.matches(json, content, file.body, manifest.checksum)
            }
        ) {
            return PrecheckResult.Rejected("内容校验和不匹配，文件可能已损坏")
        }
        // 设置段校验（缺陷修复）：原先 settings 完全不参与校验，改坏/篡改设置段（监控间隔、
        // 免打扰、容量、导入上限、通知文案）仍能通过预检并被静默写库。
        // 只在"清单声明了设置校验和"且"文件里确实取得到原始 settings 文本"时才判定：
        // 旧备份没有这个键（按未校验处理），导出时设置段整体为空的文件也没有可校验的文本。
        // 已知限制：若文件里的 settings 不是 JSON 对象（例如被改成字符串），原始文本取不到，
        // 这里同样退化成"未校验" —— 不额外判损坏，因为反序列化那一步会把它挡掉。
        if (manifest.settingsChecksum != null) {
            val actualSettingsChecksum = withContext(Dispatchers.Default) {
                BackupChecksum.ofRawSettings(json, content)
            }
            if (actualSettingsChecksum != null && actualSettingsChecksum != manifest.settingsChecksum) {
                return PrecheckResult.Rejected("应用设置校验和不匹配，文件可能已损坏")
            }
        }
        val actual = mapOf(
            "streamers" to file.body.streamers.size.toLong(),
            "sessions" to file.body.sessions.size.toLong(),
            "events" to file.body.events.size.toLong(),
            "titles" to file.body.titles.size.toLong(),
            "corrections" to file.body.corrections.size.toLong(),
            "tags" to file.body.tags.size.toLong(),
            "groups" to file.body.groups.size.toLong(),
            "policies" to file.body.policies.size.toLong(),
            "areas" to file.body.areas.size.toLong()
        )
        // 只比对清单里**确实存在**的键：旧备份没有 "areas" 这一项，
        // 若把缺失当成不一致，所有旧备份都会被误判为"记录数与清单不一致"。
        val mismatched = actual.filter { (k, v) -> manifest.recordCounts[k]?.let { it != v } == true }
        if (mismatched.isNotEmpty()) {
            return PrecheckResult.Rejected("记录数与清单不一致：${mismatched.keys.joinToString()}")
        }

        // stableId/uid 冲突矩阵（0.6.20）
        val localByStable = db.streamerDao().listAllIncludingDeleted().associateBy { it.stableId }
        val localByUid = db.streamerDao().listAllIncludingDeleted().associateBy { it.uid }
        val conflicts = mutableListOf<RestoreConflictEntity>()
        var newStreamers = 0
        val existingSessionKeys = db.liveSessionDao().listAll().map { it.sessionKey }.toSet()
        var sessionsToRestore = 0

        for (bs in file.body.streamers) {
            val byStable = localByStable[bs.stableId]
            val byUid = localByUid[bs.uid]
            when {
                // 同一实体：无冲突。
                // 注意：**不在这里累计 sessionsToRestore** —— 原来的实现既在这里按主播累加一次，
                // 又在下面按场次维度再累加一次，两个条件重叠，预检会报出约两倍的场次数
                // （实测：1 个已存在主播 + 10 场新场次 → 报 20），而实际只恢复 10 场。
                byStable != null && byStable.uid == bs.uid -> Unit
                byStable != null && byStable.uid != bs.uid -> {
                    conflicts += conflict(runId, RestoreConflictType.STABLE_ID_DIFFERENT_UID_SAME, bs.stableId, bs.uid, byStable.stableId, byStable.uid)
                }
                byStable == null && byUid != null -> {
                    conflicts += conflict(runId, RestoreConflictType.STABLE_ID_SAME_UID_DIFFERENT, bs.stableId, bs.uid, byUid.stableId, byUid.uid)
                }
                byStable == null && byUid == null -> newStreamers++
            }
        }
        // 场次维度的唯一权威计数：与 applyRestore 的跳过条件保持一致
        // （sessionKey 已存在则跳过；主播不在备份范围内则跳过）。
        val backupStreamerByStable = file.body.streamers.associateBy { it.stableId }
        sessionsToRestore += file.body.sessions.count { s ->
            val local = localByStable[s.streamerStableId]
            val key = "LIVEsession:${s.stableId}"
            s.streamerStableId.isNotBlank() &&
                key !in existingSessionKeys &&
                (local == null || local.uid == backupStreamerByStable[s.streamerStableId]?.uid)
        }

        restoreDao.insertRun(
            RestoreRunEntity(
                restoreRunId = runId, backupId = manifest.backupId, startedAt = now,
                status = if (conflicts.isEmpty()) RestoreRunStatus.PRECHECK else RestoreRunStatus.WAITING_DECISIONS,
                finishedAt = null, finishReason = null, warningCount = 0
            )
        )
        if (conflicts.isNotEmpty()) restoreDao.insertConflicts(conflicts)
        return PrecheckResult.Ready(runId, manifest, conflicts, newStreamers, sessionsToRestore)
    }

    private fun conflict(
        runId: String, type: RestoreConflictType,
        backupStableId: String?, backupUid: Long?,
        localStableId: String?, localUid: Long?
    ) = RestoreConflictEntity(
        conflictId = Ids.newId(), restoreRunId = runId, type = type,
        backupStableId = backupStableId, backupUid = backupUid,
        localStableId = localStableId, localUid = localUid, decision = null
    )

    /**
     * 应用恢复（0.6.26 / 0.6.33.1）：单维护事务；APPLYING 是唯一允许写业务表的阶段。
     * 返回 RestoreSummary。
     */
    suspend fun applyRestore(runId: String, content: String): RestoreSummary {
        val now = clock.nowWall()
        val run = restoreDao.getRun(runId) ?: return RestoreSummary(runId, emptyMap(), emptyMap(), emptyMap(), listOf("恢复运行不存在"))
        // 终态不可逆（0.6.33.1）：已 COMPLETED/FAILED/CANCELLED 的运行不得再次执行
        val advanced = restoreDao.casTransition(runId, run.status, RestoreRunStatus.APPLYING, null, null, run.warningCount)
        if (advanced == 0) {
            return RestoreSummary(runId, emptyMap(), emptyMap(), emptyMap(), listOf("该恢复已执行或已取消，不能重复执行"))
        }
        // 维护权（0.6.26）：恢复期间必须冻结监控引擎的写入。
        // 进入 APPLYING 之后、解析与写库之前获取。
        //
        // ★ 用接管版（台账 H19-B13）：严格版 enterMaintenance 要求监控租约为空或已过期，
        //   而引擎每轮 Tick 都续 90 秒租约 —— 结果是恢复在实时模式下约一半时间**必然失败**，
        //   提示还是与事实无关的"导出/恢复/清理正在进行"。导出侧早已改用接管版，
        //   接管会递增 monitorGeneration + 清空 fencingToken，在途写入自然失效，安全性不变。
        //
        // ★ 这一步原先写在 try **之外**（缺陷修复）：takeOverForMaintenance 抛异常时
        //   catch/finally 都不覆盖 —— 既不把 restore_run 结算成 FAILED，也不释放维护权，
        //   而清理 SQL 只删终态行、冷启动只处理 findApplying() 之外的运行，
        //   这一行会**永久停在 APPLYING**。放进 try 之后，任何失败路径都必然留下终态。
        // ★ 每次恢复开头就把降级计数清零（字段见 [unknownSessionEnums] 说明）：
        //   原先清零点在事务内部，而"维护权失败 / 文件解析失败"这两条提前 return 的路径
        //   走不到那里 —— 上一次的计数会被这一次当成自己的降级次数报进警告。
        unknownSessionEnums = 0
        try {
            check(runtimeLockRepository.takeOverForMaintenance(com.example.bilimonitor.data.local.MaintenanceMode.RESTORE)) {
                "无法进入维护模式，请稍后再试"
            }
        val file = withContext(Dispatchers.Default) { parseBackupFile(content) } ?: run {
            restoreDao.casTransition(runId, RestoreRunStatus.APPLYING, RestoreRunStatus.FAILED, now, RestoreFinishReason.FAILED, 0)
            return RestoreSummary(runId, emptyMap(), emptyMap(), emptyMap(), listOf("文件解析失败"))
        }
        // 应用设置段要在事务之前取出来：事务内那条 APPLY_RESTORE 审计要把 settings 的恢复量
        // （以及本步产生的警告）一起写进 detailJson，而那一步早于"7) 应用设置"。
        val settings = file.settings
        val warnings = mutableListOf<String>()
        /** 事务里那条 APPLY_RESTORE 审计覆盖到的警告条数（见它的写入处）。 */
        var warningsAtAudit = 0
        val conflicts = restoreDao.listConflicts(runId)
        val undecided = conflicts.count { it.decision == null }
        if (undecided > 0) warnings.add("存在 $undecided 条未决策冲突，按跳过处理")

        var addedStreamers = 0L; var updatedStreamers = 0L; var skippedStreamers = 0L
        var restoredSessions = 0L; var skippedSessions = 0L
        // 同一主播的"重复未结束场次"必须单独计数：它既不是"已存在"也不是"不在范围内"，
        // 用户需要看到的是"备份里有 N 条未结束场次自相矛盾，只恢复了第一条"。
        // （不再用它做跳过原因：原因由 restoreSessionReason 统一给出。）
        var duplicateOpenSessions = 0L
        // 场次枚举列（来源/置信度）降级次数：攒成一条聚合警告，不逐条刷屏。
        var restoredEvents = 0L
        var restoredCorrections = 0L
        // 标签必须分开计"新建"与"本地已有"（缺陷修复）：原来一个 restoredTags 把两者混在一起，
        // 用户分不出"标签建出来了"和"本来就有"；分组此前**完全没统计**。
        var newTags = 0L
        var existingTags = 0L
        var restoredGroups = 0L
        // 归属行（"某位主播属于哪个标签 / 哪个分组"）单独计数：它与上面的标签**实体**计数
        // 不是同一种量纲，混在一个数字里两边都会被淹没 —— 用户看不出"标签建出来了，但归属没回来"。
        var restoredTagRefs = 0L
        var restoredGroupRefs = 0L
        var restoredSettings = 0L
        // 子行跳过计数（台账 H19-B7）：恢复摘要里必须能看出"有几条没进去"，
        // 而不是只给一个偏小的成功数字。
        var skippedEvents = 0L
        var skippedTitles = 0L
        var skippedAreas = 0L
        // 归属行的跳过计数：标签/分组自己的循环曾把"对象找不到"直接 continue 掉，
        // 于是一条归属凭空消失、摘要里却只有漂亮的成功数字（见第 5 步的注释）。
        var skippedTagRefs = 0L
        var skippedGroupRefs = 0L

        // 冲突决策映射：backupStableId -> action（未决策按 SKIP）
        val decisionByStable = conflicts.associate {
            it.backupStableId!! to (it.decision ?: RestoreConflictAction.SKIP)
        }

        db.withTransaction {
            val streamerDao = db.streamerDao()
            val localByStable = streamerDao.listAllIncludingDeleted().associateBy { it.stableId }
            val localByUid = streamerDao.listAllIncludingDeleted().associateBy { it.uid }

            // 1) 主播
            val ownedStableIds = mutableSetOf<String>()
            for (bs in file.body.streamers) {
                val action = decisionByStable[bs.stableId] ?: RestoreConflictAction.USE_BACKUP
                val byStable = localByStable[bs.stableId]
                val byUid = localByUid[bs.uid]
                when {
                    byStable != null && byStable.uid == bs.uid -> {
                        // 同一实体：以备份为权威，写回删除态与开关（监控开关 / 收藏）。
                        // ★ monitoringEnabled 才是监控引擎真正的开关（缺陷修复）：原先这个分支
                        //   只动删除态，备份里"已暂停"的主播恢复到本机后**仍在监控**，
                        //   用户以为恢复的是备份状态，实际被静默改成了本机状态。
                        if (bs.deleted && byStable.deletedAt == null) {
                            streamerDao.softDelete(byStable.id, now)
                        } else if (!bs.deleted && byStable.deletedAt != null) {
                            streamerDao.restore(byStable.id, now)
                        }
                        // 只在值确实不同时才发 UPDATE：这两个 DAO 都会写 updatedAt，
                        // 无差别调用会把每个主播的 updatedAt 都刷成本次恢复时间（无意义的状态变更）。
                        if (bs.monitoringEnabled != byStable.monitoringEnabled) {
                            streamerDao.setMonitoringEnabled(byStable.id, bs.monitoringEnabled, now)
                            // ★ 只写开关一列会留下两处不一致（复查发现的缺陷）——
                            //   这里必须与"用户在界面里暂停/恢复监控"同一套收尾
                            //   （StreamerRepository.batchSetMonitoringEnabled）：
                            //   否则本地的 ACTIVE OPEN 场次无人接管，历史里挂着一场永不结束的直播，
                            //   而暂停后引擎也不会再替它做断档收尾；待确认计数与监控代际同理。
                            streamerDao.bumpStreamerMonitorGeneration(byStable.id, now)
                            streamerDao.deletePendingTransition(byStable.id)
                            if (!bs.monitoringEnabled) {
                                db.reliableIntervalDao().invalidateActiveByStreamer(
                                    byStable.stableId,
                                    com.example.bilimonitor.data.local.ReliableIntervalInvalidReason.MANUAL_STOP,
                                    now
                                )
                                val open = db.liveSessionDao().findActiveOpenSession(byStable.id)
                                if (open != null) db.liveSessionDao().abandon(open.id, now)
                            }
                        }
                        // setFavorite 返回受影响行数，这里不需要，只看是否真的不同。
                        if (bs.isFavorite != byStable.isFavorite) {
                            streamerDao.setFavorite(byStable.id, bs.isFavorite, now)
                        }
                        ownedStableIds += bs.stableId
                        updatedStreamers++
                    }
                    byStable != null && byStable.uid != bs.uid -> {
                        when (action) {
                            RestoreConflictAction.USE_BACKUP, RestoreConflictAction.MERGE -> {
                                streamerDao.updateIdentity(byStable.id, bs.uid, now)
                                ownedStableIds += bs.stableId
                                updatedStreamers++
                            }
                            RestoreConflictAction.KEEP_LOCAL -> {
                                ownedStableIds += bs.stableId
                                skippedStreamers++
                            }
                            RestoreConflictAction.SKIP -> skippedStreamers++
                        }
                    }
                    byStable == null && byUid != null -> {
                        when (action) {
                            RestoreConflictAction.USE_BACKUP, RestoreConflictAction.MERGE -> {
                                // 采纳备份 stableId 为最终身份（0.6.40）
                                streamerDao.updateStableId(byUid.id, bs.stableId, now)
                                ownedStableIds += bs.stableId
                                updatedStreamers++
                            }
                            RestoreConflictAction.KEEP_LOCAL -> {
                                ownedStableIds += byUid.stableId
                                skippedStreamers++
                            }
                            RestoreConflictAction.SKIP -> skippedStreamers++
                        }
                    }
                    else -> {
                        streamerDao.insert(
                            StreamerEntity(
                                stableId = bs.stableId, uid = bs.uid, roomId = bs.roomId,
                                shortRoomId = bs.shortRoomId, name = bs.name, nameLocked = bs.nameLocked,
                                avatarUrl = bs.avatarUrl, roomTitle = bs.roomTitle,
                                parentAreaName = bs.parentAreaName, areaName = bs.areaName,
                                coverUrl = bs.coverUrl, liveUrl = bs.liveUrl,
                                // 走同一个 enumOrNull：这条以前是裸 EnumSafe.parse，降级了也不计数，
                                // 于是"有 N 个枚举值本版本不认识"从来不包括主播状态这一项。
                                confirmedLiveStatus = enumOrNull(
                                    com.example.bilimonitor.data.local.ConfirmedLiveStatus.values(),
                                    bs.confirmedLiveStatus,
                                    com.example.bilimonitor.data.local.ConfirmedLiveStatus.UNKNOWN
                                ) ?: com.example.bilimonitor.data.local.ConfirmedLiveStatus.UNKNOWN,
                                lastObservationResult = com.example.bilimonitor.data.local.ObservationResult.INVALID,
                                observationSequence = 0,
                                streamerMonitorGeneration = 0,
                                monitoringEnabled = bs.monitoringEnabled,
                                isFavorite = bs.isFavorite,
                                monitorPolicyStableId = null,
                                lastCheckedAt = null, lastConfirmedAt = null,
                                freshnessStatus = com.example.bilimonitor.data.local.DataFreshness.UNKNOWN,
                                lastLiveStartedAt = null, lastLiveEndedAt = null,
                                lastError = null,
                                deletedAt = if (bs.deleted) now else null,
                                createdAt = bs.createdAt, updatedAt = now
                            )
                        )
                        ownedStableIds += bs.stableId
                        addedStreamers++
                    }
                }
            }

            // 2) 场次（按 sessionKey 幂等；父主播必须在本备份范围内）
            val localSessionStableIds = db.liveSessionDao().listAll().map { it.stableId }.toSet()
            val sidToInt = streamerDao.listAllIncludingDeleted().associateBy { it.stableId }
            // ★ 同一主播的未结束场次只允许一条（缺陷修复）：库上有两个唯一部分索引
            //   （AppMigrations 的 idx_live_session_one_active_open / one_abandoned_open），
            //   备份里若同一主播有 ≥2 条 endTime 为空的场次，原先第二条会直接撞索引 ——
            //   而且这个 insert 是整段恢复里**唯一没有 runCatching 的写入**，异常直接掀翻
            //   整个事务：用户看到 UNIQUE constraint failed，前面恢复的数据全部回滚。
            //   两条都在 backup 端，用户无从修正，所以按 (主播, 状态) 去重：
            //   保留备份顺序里的第一条，其余跳过并计入 warnings（不伪造 endTime，
            //   也不静默丢弃）。
            // ★ 去重必须把**本地已有的**未结束场次一起算进来（复查发现的缺陷）：
            //   只记"本次插入的行"等于假设本地没有未结束场次。本地若已有一条 ABANDONED OPEN
            //   （旧备份恢复、反复中断都会留下），备份里再插一条同主播的未结束场次照样会撞
            //   部分唯一索引，而这条 insert 是整段恢复里唯一没有 runCatching 的写入 ——
            //   一条场次就把整个事务掀翻，前面恢复的数据全部回滚。
            val openSessionKeys = mutableSetOf<String>().apply {
                db.liveSessionDao().listOpenSessions().forEach {
                    add("${it.streamerId}:${it.lifecycleState.name}")
                }
            }
            for (bs in file.body.sessions) {
                val reason = restoreSessionReason(bs, localSessionStableIds, ownedStableIds, sidToInt)
                if (reason != null) {
                    skippedSessions++
                    if (reason.isNotEmpty()) warnings.add(reason)
                } else if (bs.endTime == null) {
                    // 用本地主播 id 而不是备份里的 stableId：冲突决策可能已经把身份
                    // 改写成另一个 stableId，最终落到哪一行以本地 id 为准。
                    val lifecycleState = com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState.ABANDONED
                    val streamer = sidToInt[bs.streamerStableId]!!
                    if (!openSessionKeys.add("${streamer.id}:${lifecycleState.name}")) {
                        duplicateOpenSessions++
                        skippedSessions++
                    } else {
                        insertBackupSession(bs, streamer.id, lifecycleState)
                        restoredSessions++
                    }
                } else {
                    val streamer = sidToInt[bs.streamerStableId]!!
                    insertBackupSession(
                        bs, streamer.id,
                        com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState.ACTIVE
                    )
                    restoredSessions++
                }
            }
            if (duplicateOpenSessions > 0) {
                // 一条聚合警告：逐条 add 会把摘要刷屏，反而把"备份本身自相矛盾"这件事淹掉。
                warnings.add(
                    "有 ${duplicateOpenSessions} 条未结束场次未恢复：同一位主播本地已有（或备份里有重复的）" +
                        "未结束场次，而唯一索引只允许一条 —— 未恢复的那几条不会伪造结束时间"
                )
            }
            if (unknownSessionEnums > 0) {
                warnings.add(
                    "有 ${unknownSessionEnums} 个枚举值本版本不认识，已按保守值恢复" +
                        "（来源=ESTIMATED、可信度=RAW、状态=UNKNOWN）"
                )
            }

            // 3) 事件（按 eventId 幂等）
            for (be in file.body.events) {
                val streamer = if (db.liveEventDao().findById(be.eventId) != null) null else sidToInt[be.streamerStableId]
                if (streamer != null) runCatching {
                    db.liveEventDao().insert(
                        com.example.bilimonitor.data.local.entity.LiveEventEntity(
                            eventId = be.eventId, streamerId = streamer.id,
                            streamerStableId = be.streamerStableId, sessionStableId = be.sessionStableId,
                            // 用 EnumSafe 而不是裸 valueOf（第二轮审查发现）：
                            // 一份来自更新版本、含本版本不认识的枚举名的备份，
                            // 原先会让整个恢复事务抛异常 —— 现在退化成"该字段取保守值 + 记一条日志"，
                            // 至少其余数据能恢复进来。
                            eventType = com.example.bilimonitor.data.local.convert.EnumSafe.parse(
                                com.example.bilimonitor.data.local.LiveEventType.values(),
                                be.eventType,
                                com.example.bilimonitor.data.local.LiveEventType.START
                            ) ?: com.example.bilimonitor.data.local.LiveEventType.START,
                            eventConfirmedAt = be.eventConfirmedAt,
                            eventSequence = 0, observationSequence = null,
                            monitorGeneration = null, streamerMonitorGeneration = null,
                            fencingToken = null, transitionId = null, configVersion = null,
                            createdAt = now
                        )
                    )
                    restoredEvents++
                }.onFailure { e ->
                    // ★ 静默失败必须记账（台账 H19-B7）：例如同一 session 的重复 START 事件会撞
                    //   idx_live_event_one_start 被丢弃 —— 原先只在控制台消失，用户只看到"事件 3"
                    //   这个偏小的数字，既不知道少了几条也不知道为什么。
                    skippedEvents++
                    warnings.add("事件 ${be.eventId.takeLast(8)} 未恢复：${e.message}")
                }
            }

            // 3.5) 场次标题（按 PK 幂等；父场次须在本次恢复范围内）
            for (bt in file.body.titles) {
                val sessionExists = db.liveSessionDao().findByStableId(bt.sessionStableId) != null
                if (!sessionExists) continue
                runCatching {
                    db.liveSessionTitleDao().insert(
                        com.example.bilimonitor.data.local.entity.LiveSessionTitleEntity(
                            sessionStableId = bt.sessionStableId, title = bt.title, observedAt = bt.observedAt
                        )
                    )
                }.onFailure {
                    skippedTitles++
                    warnings.add("标题记录未恢复（${bt.sessionStableId.takeLast(8)}）：${it.message}")
                }
            }

            // 3.6) 场次分区（按 PK 幂等；父场次须在本次恢复范围内）
            for (ba in file.body.areas) {
                val sessionExists = db.liveSessionDao().findByStableId(ba.sessionStableId) != null
                if (!sessionExists) continue
                runCatching {
                    db.liveSessionAreaDao().insert(
                        com.example.bilimonitor.data.local.entity.LiveSessionAreaEntity(
                            sessionStableId = ba.sessionStableId,
                            areaLabel = ba.areaLabel,
                            parentAreaName = ba.parentAreaName,
                            areaName = ba.areaName,
                            observedAt = ba.observedAt
                        )
                    )
                }.onFailure {
                    skippedAreas++
                    warnings.add("分区记录未恢复（${ba.sessionStableId.takeLast(8)}）：${it.message}")
                }
            }

            // 4) 修正审计（按 correctionId 幂等）
            // ★ 幂等集合在循环外只物化一次（缺陷修复）：原先是"每条修正都 listBySession
            //   并把该场次的整表拉回来再 any{}" —— 修正多时是 O(n²) 次查询 + 反复物化。
            val correctionIds = db.liveSessionCorrectionDao().listAll().map { it.correctionId }.toSet()
            for (bc in file.body.corrections) {
                val already = bc.correctionId in correctionIds
                if (!already) runCatching {
                    db.liveSessionCorrectionDao().insert(
                        com.example.bilimonitor.data.local.entity.LiveSessionCorrectionEntity(
                            correctionId = bc.correctionId, sessionStableId = bc.sessionStableId,
                            baseCorrectionVersion = bc.baseCorrectionVersion,
                            newCorrectionVersion = bc.newCorrectionVersion,
                            changedFieldsJson = bc.changedFieldsJson,
                            source = com.example.bilimonitor.data.local.convert.EnumSafe.parse(
                                com.example.bilimonitor.data.local.DataSource.values(),
                                bc.source,
                                com.example.bilimonitor.data.local.DataSource.USER_CORRECTED
                            ) ?: com.example.bilimonitor.data.local.DataSource.USER_CORRECTED,
                            createdAt = bc.createdAt,
                            operationId = Ids.newId()
                        )
                    )
                    restoredCorrections++
                }
            }

            // 5) 标签/分组 + 引用
            val tagStableToInt = mutableMapOf<String, Long>()
            for (t in file.body.tags) {
                val existing = db.taxonomyDao().findTagByStableId(t.stableId)
                val id = existing?.id ?: db.taxonomyDao().insertTag(
                    com.example.bilimonitor.data.local.entity.TagEntity(
                        stableId = t.stableId, name = t.name, colorHex = t.colorHex, sortOrder = t.sortOrder
                    )
                )
                tagStableToInt[t.stableId] = id
                if (existing == null) newTags++ else existingTags++
            }
            for (r in file.body.tagRefs) {
                val tagId = tagStableToInt[r.tagStableId]
                val streamer = sidToInt[r.streamerStableId]
                // ★ 找不到对象时不能再静默 continue（原先正是如此）：标签在、主播在、归属却没了，
                //   用户恢复完只会看到"标签筛选下一个人都没有"，且没有任何提示。计入跳过并出警告。
                if (tagId == null || streamer == null) { skippedTagRefs++; continue }
                // 口径与上面的标签实体计数一致：assignTag 是 IGNORE 插入，"本来就有"和"这次写入"
                // 都算恢复成功（幂等重跑不会让数字越跑越小），只有"对象找不到"才算跳过。
                db.taxonomyDao().assignTag(com.example.bilimonitor.data.local.entity.StreamerTagCrossRefEntity(streamer.id, tagId))
                restoredTagRefs++
            }
            // 与标签侧同构：先把 stableId -> 本地自增 id 记下来，引用行才有得可查。
            // 原先这里丢掉了 insertGroup 的返回值 —— 这正是"想补 groupRefs 循环也补不出来"的原因。
            val groupStableToInt = mutableMapOf<String, Long>()
            for (g in file.body.groups) {
                val existing = db.taxonomyDao().findGroupByStableId(g.stableId)
                val id = existing?.id ?: db.taxonomyDao().insertGroup(
                    com.example.bilimonitor.data.local.entity.GroupEntity(
                        stableId = g.stableId, name = g.name, sortOrder = g.sortOrder, collapsed = g.collapsed
                    )
                )
                groupStableToInt[g.stableId] = id
                restoredGroups++
            }
            // ★ 分组归属（既有缺陷修复）：导出侧一直写着 body.groupRefs，恢复侧此前**只恢复分组实体、
            //   从不恢复归属行** —— 用备份恢复后"主播属于哪个分组"会静默消失，分组筛选变空，
            //   而恢复摘要照样显示成功。这里与 tagRefs 完全同构地补回来：同样的 IGNORE 冲突处理
            //   （重复恢复是幂等的 no-op）、同样的跳过计数与警告口径。
            for (r in file.body.groupRefs) {
                val groupId = groupStableToInt[r.groupStableId]
                val streamer = sidToInt[r.streamerStableId]
                if (groupId == null || streamer == null) { skippedGroupRefs++; continue }
                db.taxonomyDao().assignGroup(com.example.bilimonitor.data.local.entity.StreamerGroupCrossRefEntity(streamer.id, groupId))
                restoredGroupRefs++
            }
            // 归属行被跳过必须让用户看见（warnings 由 DataScreen 的恢复摘要显示）。
            // 统一在循环外出**一条聚合**警告：缺失几百条时逐条 add 会把摘要刷屏，
            // 反而把"少了一批归属"这件事淹掉。
            // 统计"备份里缺了标题/分区变化开关"的主播数。
            // ★ 声明必须放在下面的警告与循环**之前** —— Kotlin 的局部变量不能先用后声明。
            var policiesMissingChangeKeys = 0
            if (skippedTagRefs > 0) {
                warnings.add("有 $skippedTagRefs 条标签归属未恢复：备份里的标签或主播在本地找不到对应记录")
            }
            if (skippedGroupRefs > 0) {
                warnings.add("有 $skippedGroupRefs 条分组归属未恢复：备份里的分组或主播在本地找不到对应记录")
            }
            if (policiesMissingChangeKeys > 0) {
                // 如实说明"哪些字段没有被备份覆盖"，否则用户会以为恢复出来的就是备份里的全部设置
                warnings.add(
                    "有 $policiesMissingChangeKeys 位主播的「标题/分区变化通知」开关在备份里没有记录" +
                        "（旧版备份）：这些主播**保留本地当前设置**，未被改动"
                )
            }

            // 6) 主播策略
            for (p in file.body.policies) {
                val streamer = sidToInt[p.streamerStableId] ?: continue
                // ★ 缺键 ≠ 用户关掉了它（2026 复查）：这两个开关是后来才加的，旧备份里没有这两个键，
                //   而原先写死 `?: false` —— 恢复一份旧备份会把用户**显式打开**的
                //   "标题/分区变化通知"静默关掉，事后也无法从摘要里看出发生了什么。
                //   "整段覆盖"只应覆盖备份**确实带了**的字段，缺键时保留本地现值。
                if (p.notifyTitleChange == null || p.notifyAreaChange == null) {
                    policiesMissingChangeKeys++
                }
                val currentPolicy = runCatching { db.policyDao().get(streamer.id) }.getOrNull()
                db.policyDao().upsert(
                    com.example.bilimonitor.data.local.entity.StreamerMonitorPolicyEntity(
                        streamerId = streamer.id, enabled = p.enabled, intervalSeconds = p.intervalSeconds,
                        notifyStart = p.notifyStart, notifyEnd = p.notifyEnd, notifyRound = p.notifyRound,
                        // 旧备份没有这两个开关 → **保留本地现值**（缺键不等于"用户关掉了它"）；
                        // 本地也没有该行时才退到默认关闭。
                        notifyTitleChange = p.notifyTitleChange ?: currentPolicy?.notifyTitleChange ?: false,
                        notifyAreaChange = p.notifyAreaChange ?: currentPolicy?.notifyAreaChange ?: false,
                        startConfirmationCount = p.startConfirmationCount,
                        endConfirmationCount = p.endConfirmationCount,
                        aggregationEnabledOverride = null, aggregationThresholdOverride = null,
                        overrideQuietHours = null, updatedAt = now
                    )
                )
            }

            db.streamerDao().bumpSourceDataVersion(now)
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                    action = "APPLY_RESTORE",
                    // ★ 跨设备稳定的身份是备份 id，不是本次运行的 id（缺陷修复）：
                    //   原先 targetStableId=runId，按备份文件检索"这份备份被恢复过几次"必然落空
                    //   （runId 换台设备就换一个）。运行 id 仍留在 detailJson 里，本机排查照样能对上。
                    targetType = "backup", targetStableId = file.manifest.backupId, occurredAt = now,
                    // 警告文本必须落库（缺陷修复）：原先 warnings 只回给 UI，恢复完就散失，
                    // 事后翻审计只看得到漂亮的成功数字，看不出"有 N 条没恢复"。
                    detailJson = "{\"runId\":\"$runId\",\"added\":$addedStreamers," +
                        "\"updated\":$updatedStreamers,\"sessions\":$restoredSessions," +
                        "\"tagsNew\":$newTags,\"tagsExisting\":$existingTags,\"groups\":$restoredGroups," +
                        "\"settings\":$restoredSettings,\"warnings\":${warningsDetailJson(warnings)}}".also {
                        // 记下"这条审计覆盖到第几条警告"：设置段在事务之外，之后可能还有新增，
                        // 收尾时要补一条（否则按 APPLY_RESTORE 检索与按 warningCount 检索会得出两个结论）。
                        warningsAtAudit = warnings.size
                    }
                )
            )
        }

        // 7) 应用设置（用户要求：配置文件覆盖"应用软件本身的设置"）。
        // 走各自的仓库接口而不是直接写表/写 DataStore：监控设置需要 configVersion CAS 与
        // 修订快照（`ConfigRepository.update`），容量/免打扰/导入上限也各有取值校验与默认值。
        // 放在 Room 事务之外：这三项都是 DataStore 写入，不参与该事务。
        // settings 已在事务之前取出（APPLY_RESTORE 审计要用到设置恢复量）。
        if (settings == null) {
            warnings.add("该文件不含应用设置（旧版备份），监控/免打扰/容量等保持本地现值")
        } else {
            settings.monitoring?.let { m ->
                val result = configRepository.update(
                    ConfigUpdate(
                        monitoringEnabled = m.monitoringEnabled,
                        mode = runCatching { com.example.bilimonitor.data.local.MonitoringMode.valueOf(m.mode) }.getOrNull(),
                        intervalSeconds = m.intervalSeconds,
                        batchSize = m.batchSize,
                        maxConcurrency = m.maxConcurrency,
                        timeoutSeconds = m.timeoutSeconds,
                        maxRetries = m.maxRetries,
                        retryBaseSeconds = m.retryBaseSeconds,
                        maxRetryDelaySeconds = m.maxRetryDelaySeconds,
                        circuitBreakerThreshold = m.circuitBreakerThreshold,
                        circuitBreakerRecoverySeconds = m.circuitBreakerRecoverySeconds,
                        aggregationEnabled = m.aggregationEnabled,
                        aggregationThreshold = m.aggregationThreshold,
                        aggregationWindowSeconds = m.aggregationWindowSeconds,
                        batchCooldownSeconds = m.batchCooldownSeconds,
                        freshnessStaleSeconds = m.freshnessStaleSeconds,
                        startConfirmationCount = m.startConfirmationCount,
                        endConfirmationCount = m.endConfirmationCount,
                        placeholderText = m.placeholderText,
                        // v9 两项随备份恢复（旧备份用默认值，见 BackupMonitoringSettings 的说明）。
                        retryFailureRatioPercent = m.retryFailureRatioPercent,
                        banRecheckIntervalSeconds = m.banRecheckIntervalSeconds,
                        // v10 熔断开关同样随备份恢复；旧备份缺这个键时 DTO 落到默认 true（= 既有行为）。
                        circuitBreakerEnabled = m.circuitBreakerEnabled
                    )
                )
                when (result) {
                    ConfigUpdateResult.Applied -> restoredSettings++
                    ConfigUpdateResult.VersionConflict ->
                        warnings.add("监控设置未恢复：配置已被并发修改，请重试")
                    is ConfigUpdateResult.Invalid ->
                        warnings.add("监控设置未恢复：${result.reason}")
                }
            }
            settings.quietHours?.let {
                runCatching { quietHoursRepository.set(it.enabled, it.startMinutes, it.endMinutes) }
                    .onSuccess { restoredSettings++ }
                    .onFailure { e -> warnings.add("免打扰设置未恢复：${e.message}") }
            }
            settings.capacity?.let {
                runCatching { maintenanceRepository.setCapacity(it.mode, it.maxRecords) }
                    .onSuccess { restoredSettings++ }
                    .onFailure { e -> warnings.add("容量设置未恢复：${e.message}") }
            }
            settings.importMaxCount?.let {
                runCatching { importSettingsRepository.setMaxCount(it) }
                    .onSuccess { restoredSettings++ }
                    .onFailure { e -> warnings.add("导入上限未恢复：${e.message}") }
            }
            // 通知文案：两个字段分别可空（旧备份没有），只要有一个就整组写入，
            // 空的那一半交给仓库的规整逻辑回落到默认值 —— 不会写出"标题恢复了一半"的状态。
            if (settings.notificationTitle != null || settings.notificationText != null) {
                runCatching {
                    notificationSettingsRepository.set(
                        title = settings.notificationTitle
                            ?: NotificationTemplate.DEFAULT_TITLE,
                        text = settings.notificationText ?: NotificationTemplate.DEFAULT_TEXT
                    )
                }
                    .onSuccess { restoredSettings++ }
                    .onFailure { e -> warnings.add("通知内容未恢复：${e.message}") }
            }
        }

        // ★ 设置阶段新增的警告也要能被审计检索到（复查发现的缺陷）：
        //   事务里那条 APPLY_RESTORE 写在设置段**之前**，它序列化的 warnings 天然不含后面新增的，
        //   于是"按 APPLY_RESTORE 检索"与"按 restore_run.warningCount 检索"会得到两个结论。
        //   只在真有新增时才补写，避免刷屏。
        val settingsPhaseWarnings = warnings.drop(warningsAtAudit)
        if (settingsPhaseWarnings.isNotEmpty()) {
            runCatching {
                logDao.insertAudit(
                    AuditLogEntity(
                        auditId = Ids.newId(), operationId = Ids.newId(), actor = "USER",
                        action = "APPLY_RESTORE_SETTINGS_WARNINGS",
                        targetType = "backup", targetStableId = file.manifest.backupId, occurredAt = now,
                        detailJson = "{\"runId\":\"$runId\",\"warnings\":" +
                            warningsDetailJson(settingsPhaseWarnings) + "}"
                    )
                )
            }
        }

        // ★ 完成态 CAS 必须检查返回值（台账 H19-B6）：cancelRun() 可能已经把这一行改成
        //   CANCELLED/FAILED，此时若还返回"恢复完成"，UI 与 restore_run 会互相矛盾。
        val completed = restoreDao.casTransition(
            runId, RestoreRunStatus.APPLYING, RestoreRunStatus.COMPLETED, now,
            RestoreFinishReason.COMPLETED, warnings.size
        )
        if (completed == 0) {
            warnings.add("数据已写入，但恢复运行记录已被并发取消/失败，状态未标记为完成（以冲突决策页与数据为准）")
        }
        return RestoreSummary(
            restoreRunId = runId,
            restoredCounts = mapOf(
                "streamers" to addedStreamers, "sessions" to restoredSessions,
                "events" to restoredEvents, "corrections" to restoredCorrections,
                // 标签分"新建 / 本地已有"两项：合成一个数字时分不出"标签建出来了"和"本来就有"。
                "tagsNew" to newTags, "tagsExisting" to existingTags,
                // 分组原先完全没统计（恢复完分组却不进摘要，用户只能靠肉眼确认）。
                "groups" to restoredGroups,
                // 更新量（写回开关/删除态/身份改写的已存在主播）单独列一项，UI 要显示。
                "updated" to updatedStreamers,
                // 归属行的恢复量必须能被用户看见：只报"标签 N 个"而归属丢了，
                // 摘要看起来依然是成功的（这正是原先那个静默缺陷的表象）。
                "tagRefs" to restoredTagRefs, "groupRefs" to restoredGroupRefs,
                "settings" to restoredSettings
            ),
            skippedCounts = mapOf(
                "streamers" to skippedStreamers, "sessions" to skippedSessions,
                "events" to skippedEvents, "titles" to skippedTitles, "areas" to skippedAreas,
                "tagRefs" to skippedTagRefs, "groupRefs" to skippedGroupRefs
            ),
            conflictCounts = conflicts.groupBy { it.type.name }.mapValues { it.value.size.toLong() },
            warnings = warnings
        )
        } catch (e: Exception) {
            // ★ 异常必须把运行记录结算成 FAILED（台账 H19-2.3）：
            //   事务内任何异常（未知枚举、外键冲突、磁盘满）都会冒泡到这里，
            //   原先只有 finally 释放维护权，restore_run 会**永远停在 APPLYING** ——
            //   同进程内既不阻塞新恢复、也没法在诊断页解释"上次到底成没成"，
            //   只能等下一次冷启动由 StartupRecovery 兜底改成 FAILED。
            // ★ 结算与留痕都要放在 NonCancellable 里（与 ExportRepository 同因：取消时 suspend
            //   收尾不会执行）：用户离开页面导致协程取消时，这两条 Room 写若不执行，
            //   restore_run 会停在 APPLYING、错误日志里也没有任何记录。
            withContext(NonCancellable) {
                runCatching {
                    restoreDao.casTransition(
                        runId, RestoreRunStatus.APPLYING, RestoreRunStatus.FAILED,
                        clock.nowWall(), RestoreFinishReason.FAILED, 0
                    )
                }
                // ★ 失败必须留痕（缺陷修复）：原先失败只结算状态 + 重抛，application_error_log 里
                //   一条都没有 —— 用户按提示"导出诊断包排查"时什么也查不到，只能复现一次。
                //   结算与日志都不能掩盖原始异常：这里各自 runCatching，最后仍重抛 e。
                runCatching {
                    logDao.insertAppError(
                        ApplicationErrorLogEntity(
                            errorId = Ids.newId(),
                            // 恢复是一步用户操作，operationId 用本次运行 id，方便与 restore_run / 审计对齐。
                            operationId = runId,
                            occurredAt = clock.nowWall(),
                            // 归因不许撒谎：只有"维护权竞争失败、无法解析、维护机制本身不可用"这类
                            // 与环境/文件不兼容有关的原因才报 RESTORE_INCOMPATIBLE；其余
                            // （唯一索引冲突、外键、磁盘满、未知枚举）都是入库失败，报 DATABASE_ERROR。
                            errorCode = if (e is IllegalStateException) {
                                com.example.bilimonitor.data.local.AppError.RESTORE_INCOMPATIBLE
                            } else {
                                com.example.bilimonitor.data.local.AppError.DATABASE_ERROR
                            },
                            detail = ("${e::class.java.simpleName}: ${e.message}").take(320)
                        )
                    )
                }
            }
            throw e
        } finally {
            // 无论成功、失败还是提前返回，都必须释放维护权（否则监控永久停在维护模式）。
            // 同样必须 NonCancellable：取消时这个 Room 写不执行的话，maintenanceMode 会永远停在
            // RESTORE，引擎取不到租约（要求 maintenanceMode='OFF'）⇒ 监控永久停摆。
            withContext(NonCancellable) {
                runtimeLockRepository.exitMaintenance(com.example.bilimonitor.data.local.MaintenanceMode.RESTORE)
            }
        }
    }

    /** 返回 null = 可恢复；空串 = 静默跳过（已存在）；非空 = 跳过原因。 */
    private fun restoreSessionReason(
        bs: BackupSession, localStableIds: Set<String>,
        ownedStableIds: Set<String>, sidToInt: Map<String, com.example.bilimonitor.data.local.entity.StreamerEntity>
    ): String? {
        if (bs.stableId in localStableIds) return ""
        if (bs.streamerStableId !in ownedStableIds)
            return "场次 ${bs.stableId.takeLast(8)} 的主播不在备份范围内，已跳过"
        if (sidToInt[bs.streamerStableId] == null) return ""
        return null
    }

    /**
     * 插入一条备份场次（未结束场次的"是否重复"判断留在调用方，因为跳过原因要分别计数）。
     *
     * 抽出来的另一个原因：未结束场次与已结束场次只差 `lifecycleState` 一个字段，
     * 原先这段构造在两种分支里各写一遍（其中未结束那条还漏了唯一索引的约束），
     * 抽成一处后"未结束"这条路径只有一种写法，不会再漏。
     */
    private suspend fun insertBackupSession(
        bs: BackupSession, streamerId: Long,
        lifecycleState: com.example.bilimonitor.data.local.entity.LiveSessionLifecycleState
    ) {
        // 一次取时，createdAt 与 updatedAt 必须逐字相同（新行不存在"被改过"这回事）。
        val at = clock.nowWall()
        db.liveSessionDao().insert(
            LiveSessionEntity(
                stableId = bs.stableId,
                sessionKey = "LIVEsession:${bs.stableId}",
                streamerId = streamerId,
                startTime = bs.startTime, endTime = bs.endTime,
                startSource = enumOrNull(
                    com.example.bilimonitor.data.local.DataSource.values(), bs.startSource,
                    // 认不出的来源值不能兜成 MONITOR_OBSERVED（那是"我们观察到"的具体主张），
                    // ESTIMATED（估算）才是诚实的兜底。
                    com.example.bilimonitor.data.local.DataSource.ESTIMATED
                ),
                endSource = enumOrNull(
                    com.example.bilimonitor.data.local.DataSource.values(), bs.endSource,
                    // 认不出的来源值不能兜成 MONITOR_OBSERVED（那是"我们观察到"的具体主张），
                    // ESTIMATED（估算）才是诚实的兜底。
                    com.example.bilimonitor.data.local.DataSource.ESTIMATED
                ),
                durationSource = enumOrNull(
                    com.example.bilimonitor.data.local.DataSource.values(), bs.durationSource,
                    // 认不出的来源值不能兜成 MONITOR_OBSERVED（那是"我们观察到"的具体主张），
                    // ESTIMATED（估算）才是诚实的兜底。
                    com.example.bilimonitor.data.local.DataSource.ESTIMATED
                ),
                startConfidence = enumOrNull(com.example.bilimonitor.data.local.DataConfidence.values(), bs.startConfidence),
                endConfidence = enumOrNull(com.example.bilimonitor.data.local.DataConfidence.values(), bs.endConfidence),
                durationConfidence = enumOrNull(com.example.bilimonitor.data.local.DataConfidence.values(), bs.durationConfidence),
                startLocked = bs.startLocked, endLocked = bs.endLocked, durationLocked = bs.durationLocked,
                titleAtStart = bs.titleAtStart, titleAtEnd = bs.titleAtEnd,
                areaAtStart = bs.areaAtStart, areaAtEnd = bs.areaAtEnd,
                coverUrlAtStart = bs.coverUrlAtStart, coverUrlAtEnd = bs.coverUrlAtEnd,
                durationSeconds = bs.durationSeconds,
                endReason = bs.endReason,
                lifecycleState = lifecycleState,
                currentReliableIntervalId = null,
                lastConfirmedLiveAt = null,
                confirmedOfflineAt = bs.endTime,
                autoUpdateProtected = false,
                correctionVersion = 0,
                createdAt = at, updatedAt = at,
                note = bs.note,
                startTimeZone = bs.startTimeZone,
                endTimeZone = bs.endTimeZone
            )
        )
    }

    /**
     * 场次枚举列取值的统一入口：与事件循环同用 [EnumSafe.parse]。
     *
     * 原先用 `runCatching { valueOf(it) }.getOrNull()`：本版本不认识的枚举名会**静默**变成 NULL，
     * 既丢了数据也不计警告（与事件侧"退化成保守值 + 记账"的口径相反）。
     * 这里保持"认不出就 NULL"（NULL 本就是这些列的合法含义：没记过），但降一次级记一次数，
     * 由调用方汇总成一条聚合警告 —— 用户能看到"有几个来源/置信度没恢复"。
     * EnumSafe 内部还会把该未知值记进启动期的应用错误日志（诊断包能查到）。
     */
    /**
     * 本次恢复里「枚举值本版本不认识、已按保守值降级」的次数。
     *
     * 为什么放在字段上而不是 applyRestore 的局部变量：[enumOrNull] 是独立方法，
     * 访问不到那边的局部变量（第一版修复就是这么写错的，编译直接报 Unresolved reference）。
     * 恢复全程由维护权 + 状态 CAS 串行化，所以这里是单飞的；
     * 但**每次 applyRestore 开头必须清零**，否则上一次的数字会串进这一次的聚合警告。
     */
    private var unknownSessionEnums = 0

    private fun <T : Enum<T>> enumOrNull(values: Array<T>, raw: String?, fallback: T = values.first()): T? {
        if (raw == null) return null
        // 认不出时 EnumSafe 返回 fallback 而不是 null（见它的签名），所以"是否降级"以
        // "有没有同名常量"为准 —— 否则同时合法的 RAW / MONITOR_OBSERVED 会被误记成降级。
        if (values.none { it.name == raw }) unknownSessionEnums++
        return com.example.bilimonitor.data.local.convert.EnumSafe.parse(values, raw, values.first())
    }

    suspend fun decideAll(runId: String, action: RestoreConflictAction) {
        restoreDao.decideAll(runId, action)
    }

    suspend fun cancelRun(runId: String) {
        val run = restoreDao.getRun(runId) ?: return
        restoreDao.casTransition(runId, run.status, RestoreRunStatus.CANCELLED, clock.nowWall(), RestoreFinishReason.CANCELLED, run.warningCount)
    }

    suspend fun listConflicts(runId: String): List<RestoreConflictEntity> = restoreDao.listConflicts(runId)

    /**
     * 从 Download 读取最新的备份文件（`<应用名>备份_*.json`）。
     *
     * 匹配**新旧两种前缀**：应用改名为 Kaoru 之后导出的是「Kaoru备份_*.json」，
     * 但用户以前导出的「主播监控备份_*.json」也必须能找到 ——
     * 否则升级后这个按钮会说"你没有备份过"，而文件其实就躺在 Download 里。
     *
     * ★ 改为 suspend + IO（台账 H19-2.1/D1）：原先是非 suspend 的普通函数，
     *   调用方 `viewModelScope.launch`（主线程）会在这里做 ContentResolver 查询 + 整文件读取，
     *   备份到几 MB 时直接卡住主线程（ANR 风险）。仓库层负责线程归属，调用方不必关心。
     *
     * 注意：Android 11+ 应用**只能看到自己创建的** MediaStore 行 —— 清过应用数据之后
     * 这里查不到旧备份是正常的，UI 需要把"看不到"和"不存在"分开说（见 DataScreen）。
     */
    suspend fun readLatestBackup(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            // 大小列：读取前先判上限，见下方 backupSizeRejectReason
            MediaStore.Downloads.SIZE
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? OR ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        // query 也可能抛（SecurityException / 底层 IO），不能让异常冒到 viewModelScope 里崩掉界面
        val cursor = runCatching {
            resolver.query(
                uri, projection, selection,
                arrayOf("${appName()}备份_%.json", OLD_BACKUP_NAME_PATTERN),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )
        }.getOrNull() ?: return@withContext null
        cursor.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                val name = c.getString(1)
                val size = c.getLong(2).takeIf { it > 0 }
                // 超限时不返回 null：把原因放进"文件名"，UI 才能把这句提示显示给用户
                // （返回 null 会被 UI 说成"文件可能已被移动或没有读取权限"，与事实不符）。
                backupSizeRejectReason(size)?.let { reason -> return@withContext "$name（$reason）" to "" }
                val fileUri = android.content.ContentUris.withAppendedId(uri, id)
                val content = runCatching {
                    resolver.openInputStream(fileUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (content != null) return@withContext name to content
            }
        }
        return@withContext null
    }

    /**
     * 读取用户用系统文件选择器挑中的配置文件。
     *
     * 之前只能从 Download 目录里按文件名前缀猜"最新的那个"，用户把配置放到
     * 网盘/U 盘/微信下载目录就完全没法导入。用 SAF（`ACTION_OPEN_DOCUMENT`）
     * 拿到的是可用 `ContentResolver` 直接读的 URI，不需要任何存储权限。
     *
     * ★ 读取前先按大小拒绝（缺陷修复）：`readBytes()` 对**任意大小**的文件整份读进内存，
     *   误选几百 MB 的文件会 OOM，而 OOM 又被 `getOrNull()` 吞掉 —— 用户只看到
     *   "无法读取所选文件"，完全不知道是自己选错了文件。
     *
     * @return 文件名 to 内容；读取失败返回 null；**超过 [MAX_BACKUP_FILE_BYTES] 时
     *   返回"文件名（拒绝原因）" to 空串** —— 让预检以"文件格式无法解析（含原因）"如实回话，
     *   而不是让 UI 用"文件可能已被移动或没有读取权限"误导用户。
     */
    suspend fun readPickedBackup(uri: android.net.Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: "所选文件"
        // 拿不到大小（某些 provider 不实现 SIZE）时视为"无法判断"，不据此拒绝 ——
        // 宁可让读取自己去失败，也不能因为"查不到大小"就拒收一份合法备份。
        // 超限时同样把原因拼进文件名，让 UI 的提示说的是事实（见 backupSizeRejectReason）。
        backupSizeRejectReason(querySizeBytes(uri))?.let { reason -> return@withContext "$name（$reason）" to "" }
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@runCatching null
            name to content
        }.getOrNull()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()

    /**
     * 文档 URI 的字节数：先问 provider 的 SIZE 列，拿不到再退回文件描述符的实际长度。
     * 两者都拿不到就返回 null（= 无法判断大小，调用方不得据此拒绝）。
     */
    private fun querySizeBytes(uri: android.net.Uri): Long? {
        val byColumn = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
        }.getOrNull()
        if (byColumn != null && byColumn >= 0) return byColumn
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 }
    }

    fun parseBackupFile(content: String): BackupFile? = runCatching {
        json.decodeFromString(BackupFile.serializer(), content)
    }.getOrNull()

    /**
     * 把本次恢复的警告文本编成 `detailJson` 里的 `warnings` 数组。
     *
     * 为什么必须落库：warnings 原先只回给 UI，恢复摘要一关就散失 —— 事后翻审计只看到
     * "added/sessions"这些成功数字，看不出"有 N 条没恢复"，用户与排障者都被误导。
     *
     * 长度上限（[AUDIT_WARNING_MAX_ITEMS] 条 / [AUDIT_WARNING_MAX_CHARS] 字符）：
     * 一条审计行不该被几百条警告刷爆（缺失成百条归属时正是这种情形），
     * 超出的部分合并成"…等 N 条"，**不静默截断**。
     *
     * 转义只处理 JSON 必需的两个字符（`"` 与 `\`），控制字符统一替换成 `?`：
     * 警告文本里只有中文与异常摘要，不需要引入完整 JSON 编码器。
     */
    private fun warningsDetailJson(warnings: List<String>): String {
        val shown = warnings.take(AUDIT_WARNING_MAX_ITEMS)
        var text = shown.joinToString(",") { "\"" + it.escapedForJson() + "\"" }
        val hidden = warnings.size - shown.size
        if (hidden > 0) text += ",\"…等 $hidden 条\""
        if (text.length > AUDIT_WARNING_MAX_CHARS) {
            text = text.take(AUDIT_WARNING_MAX_CHARS) + "…（已截断，共 ${warnings.size} 条）"
        }
        return "[$text]"
    }

    private fun String.escapedForJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").map { if (it.code < 0x20) '?' else it }.joinToString("")

    /**
     * 备份文件名用的应用名（跟随 [R.string.app_name]，不再写死旧名"主播监控"）。
     * 取不到就回落到"备份"，宁可文件名难看一点，也不能让导出整个失败。
     */
    private fun appName(): String =
        runCatching { context.getString(com.example.bilimonitor.R.string.app_name) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "备份"

    /**
     * 单份备份文件的大小上限检查。
     *
     * @return null = 在限内；非空 = 拒绝原因文本。
     *   **超限时把原因拼进返回的文件名**是刻意的：UI 对"读不出来"只有一句
     *   "无法读取所选文件（可能已被移动或没有读取权限）"，而超限完全是另一回事 ——
     *   用户需要看到自己选错了文件（见 readPickedBackup / readLatestBackup）。
     */
    private fun backupSizeRejectReason(bytes: Long?): String? =
        if (bytes != null && bytes > MAX_BACKUP_FILE_BYTES) {
            "文件过大（${bytes / 1024 / 1024} MB，上限 ${MAX_BACKUP_FILE_BYTES / 1024 / 1024} MB），已拒绝读取"
        } else {
            null
        }
}

/** Room schema 版本单一来源（编译期由 AppDatabase.version 提供，避免循环依赖用常量桥接）。 */
object AppDatabaseVersionProvider {
    /**
     * 当前代码编译时的数据库版本。
     *
     * **必须与 `AppDatabase` 的 `version` 保持一致** —— 本文件曾硬编码 5，
     * 而库已升到 6，导致备份清单双向判错：
     *  - 写出的备份声明 v5，拿到真正 v6 的设备上会被当作"旧结构"放行；
     *  - 读入守卫的上界变成 5，来自 v7+ 的备份反倒被放行。
     * 因此这里改为直接引用 `AppDatabase.DB_VERSION`，从根上避免再次脱节。
     */
    const val version = com.example.bilimonitor.data.local.db.AppDatabase.DB_VERSION
}
