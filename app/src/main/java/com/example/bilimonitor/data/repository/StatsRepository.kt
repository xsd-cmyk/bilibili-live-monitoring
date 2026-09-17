package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.DataConfidence
import com.example.bilimonitor.data.local.StatisticsEligibility
import com.example.bilimonitor.data.local.dao.ConfigDao
import com.example.bilimonitor.data.local.dao.StatisticsCacheDao
import com.example.bilimonitor.data.local.dao.StatisticsSnapshotDao
import com.example.bilimonitor.data.local.dao.StreamerDao
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.LiveSessionEntity
import com.example.bilimonitor.data.local.entity.StatisticsCacheEntity
import com.example.bilimonitor.data.local.entity.StatisticsSnapshotEntity
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 统计查询（0.6.17 契约的载体；缓存键 = SHA-256(canonicalJson(query + sourceDataVersion))）。 */
data class StatsQuery(
    val rangeStartInclusive: Long,
    val rangeEndExclusive: Long,
    val timezone: String,
    val eligibility: StatisticsEligibility
)

@kotlinx.serialization.Serializable
data class StatsTotals(
    val sessionCount: Long,
    val confirmedSessionCount: Long,
    val provisionalSessionCount: Long,
    val correctedSessionCount: Long,
    val excludedSessionCount: Long,
    val monitoredSeconds: Long,
    val reliableMonitoredSeconds: Long
)

@kotlinx.serialization.Serializable
data class StatsBucketResult(
    val bucketKey: String,
    val label: String,
    val totals: StatsTotals
)

@kotlinx.serialization.Serializable
data class StatsResult(
    val statisticsVersion: Long,
    val sourceDataVersion: Long,
    val timezone: String,
    val eligibility: String,
    val generatedAt: Long,
    val totals: StatsTotals,
    val buckets: List<StatsBucketResult>
)

@kotlinx.serialization.Serializable
data class DailyDurationPoint(val day: String, val seconds: Long, val sessionCount: Int)

/**
 * 统计（0.6.17 / 原规范 83 可重建）：旧 sourceDataVersion 的缓存不可命中，计算后写入缓存。
 */
@Singleton
class StatsRepository @Inject constructor(
    private val db: AppDatabase,
    private val streamerDao: StreamerDao,
    private val statsCacheDao: StatisticsCacheDao,
    private val statisticsSnapshotDao: StatisticsSnapshotDao,
    private val configDao: ConfigDao,
    private val clock: AppClock
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun compute(query: StatsQuery): StatsResult {
        val sdv = configDao.getSourceDataVersion() ?: 0L
        val fingerprint = fingerprintOf(query, sdv)
        val cacheKey = sha256(fingerprint)

        // 1) 同一 sourceDataVersion 下已有规范快照 → 直接复用（原规范 144：数据版本化）。
        //    快照随 sourceDataVersion 推进自动失效，因此与缓存不同，不存在"跨代命中"的可能。
        runCatching {
            statisticsSnapshotDao.findValid(sdv, fingerprint, clock.nowWall())
        }.getOrNull()?.let { snap ->
            runCatching { json.decodeFromString(StatsResult.serializer(), snap.payloadJson) }
                .getOrNull()?.let { return it }
        }

        // 2) 行级缓存
        statsCacheDao.findValid(cacheKey, sdv)?.let { cached ->
            runCatching { json.decodeFromString(StatsResult.serializer(), cached.payloadJson) }
                .getOrNull()?.let { return it }
        }

        val sessions = db.liveSessionDao().listClosedBetween(query.rangeStartInclusive, query.rangeEndExclusive)
        val zone = runCatching { ZoneId.of(query.timezone) }.getOrDefault(ZoneId.systemDefault())
        // 主播名映射必须覆盖**全部**主播（含已软删除的）：原实现只用 listMonitorable()，
        // 于是已删除/已暂停主播的历史场次在"按主播"分桶里统一显示为"未知主播"，
        // 用户看到的历史与统计对不上。
        val nameById = streamerDao.listAllIncludingDeleted().associate { it.id to it.name }

        val includeProvisional = query.eligibility != StatisticsEligibility.CONFIRMED_ONLY
        val eligible = sessions.filter { s ->
            val startOk = when (s.startConfidence) {
                DataConfidence.CORRECTED, DataConfidence.CONFIRMED -> true
                DataConfidence.PROVISIONAL, DataConfidence.RAW -> includeProvisional
                else -> false
            }
            startOk
        }

        fun totalsOf(list: List<LiveSessionEntity>): StatsTotals = StatsTotals(
            sessionCount = list.size.toLong(),
            confirmedSessionCount = list.count { it.startConfidence == DataConfidence.CONFIRMED }.toLong(),
            provisionalSessionCount = list.count { it.startConfidence == DataConfidence.PROVISIONAL }.toLong(),
            correctedSessionCount = list.count { it.startConfidence == DataConfidence.CORRECTED }.toLong(),
            excludedSessionCount = (sessions.size - list.size).toLong(),
            monitoredSeconds = list.sumOf { it.durationSeconds ?: 0L },
            reliableMonitoredSeconds = list
                .filter { it.startConfidence == DataConfidence.CORRECTED || it.endConfidence == DataConfidence.CORRECTED }
                .sumOf { it.durationSeconds ?: 0L }
        )

        val totals = totalsOf(eligible)

        val byDay = eligible.groupBy { s ->
            Instant.ofEpochMilli(s.endTime ?: s.startTime ?: query.rangeStartInclusive)
                .atZone(zone).toLocalDate().toString()
        }
        val dayBuckets = byDay.toSortedMap().map { (day, list) ->
            StatsBucketResult(day, day, totalsOf(list))
        }

        val byStreamer = eligible.groupBy { nameById[it.streamerId] ?: "未知主播" }
        val streamerBuckets = byStreamer.map { (name, list) ->
            StatsBucketResult(name, name, totalsOf(list))
        }.sortedByDescending { it.totals.monitoredSeconds }

        val statisticsVersion = configDao.currentStatisticsVersion()
        val result = StatsResult(
            statisticsVersion = statisticsVersion,
            sourceDataVersion = sdv,
            timezone = query.timezone,
            eligibility = query.eligibility.name,
            generatedAt = clock.nowWall(),
            totals = totals,
            buckets = dayBuckets + streamerBuckets
        )
        val payload = json.encodeToString(StatsResult.serializer(), result)
        db.withTransaction {
            statsCacheDao.insert(
                StatisticsCacheEntity(
                    cacheKey = cacheKey,
                    statisticsVersion = statisticsVersion,
                    sourceDataVersion = sdv,
                    queryFingerprint = fingerprint,
                    timezone = query.timezone,
                    eligibility = query.eligibility,
                    calculatedAt = clock.nowWall(),
                    payloadJson = payload
                )
            )
            statsCacheDao.evictOlderThan(sdv)

            // 落统计快照（原规范 144：数据版本化；此前 statistics_snapshot 表从无写入）。
            // 与行级缓存互补：快照按 sourceDataVersion 整代失效，供"重算/回放/跨设备校验"使用。
            statisticsSnapshotDao.insert(
                StatisticsSnapshotEntity(
                    snapshotId = com.example.bilimonitor.core.Ids.newId(),
                    cacheKey = cacheKey,
                    statisticsVersion = statisticsVersion,
                    sourceDataVersion = sdv,
                    timezone = query.timezone,
                    eligibility = query.eligibility,
                    queryFingerprint = fingerprint,
                    generatedAt = clock.nowWall(),
                    // 快照不过期：其有效性由 sourceDataVersion 决定，而不是时间
                    expiresAt = null,
                    payloadJson = payload
                )
            )
            statisticsSnapshotDao.trimTo(SNAPSHOT_KEEP)
        }
        return result
    }

    /** 快照列表（诊断/排查用）。 */
    suspend fun recentSnapshots(limit: Int = 10): List<StatisticsSnapshotEntity> =
        runCatching { statisticsSnapshotDao.recent(limit) }.getOrDefault(emptyList())

    /** 清理过期快照（保留策略由 trimTo 保证，这里处理显式过期）。 */
    suspend fun cleanupSnapshots(): Int =
        runCatching { statisticsSnapshotDao.deleteExpired(clock.nowWall()) }.getOrDefault(0)

    suspend fun last7Days(): List<DailyDurationPoint> {
        val now = clock.nowWall()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = db.liveSessionDao().listClosedBetween(start, end)
        val byDay = sessions.mapNotNull { s ->
            val endAt = s.endTime ?: return@mapNotNull null
            Instant.ofEpochMilli(endAt).atZone(zone).toLocalDate().toString() to s
        }.groupBy({ it.first }, { it.second })
        return (0..6).map { i ->
            val day = today.minusDays((6 - i).toLong()).toString()
            val list = byDay[day].orEmpty()
            DailyDurationPoint(day, list.sumOf { it.durationSeconds ?: 0L }, list.size)
        }
    }

    private fun fingerprintOf(query: StatsQuery, sdv: Long): String =
        "{\"eligibility\":\"${query.eligibility.name}\"," +
            "\"rangeEndExclusive\":${query.rangeEndExclusive}," +
            "\"rangeStartInclusive\":${query.rangeStartInclusive}," +
            "\"sourceDataVersion\":$sdv," +
            "\"timezone\":\"${query.timezone}\"}"

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 统计快照保留个数（滚动窗口，避免无界增长）。 */
        const val SNAPSHOT_KEEP = 20
    }
}
