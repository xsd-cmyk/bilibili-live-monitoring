package com.example.bilimonitor.data.repository

import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.MaintenanceMode
import com.example.bilimonitor.data.local.dao.NameTarget
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.remote.bilibili.StreamerNameSource
import com.example.bilimonitor.domain.policy.NameRefreshPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class NameRefreshResult(val success: Int, val retry: Int, val skipped: Int)

@Singleton
class StreamerNameRepository @Inject constructor(
    private val db: AppDatabase, private val source: StreamerNameSource, private val clock: AppClock
) {
    private val gate = Mutex()
    private val dao = db.streamerDao()

    val changes = combine(dao.observeNameTargets().distinctUntilChanged(),
        db.configDao().observeConfig().map { it?.let { cfg -> cfg.nameRefreshHours to cfg.nameRetryMinutes } }.distinctUntilChanged(),
        db.runtimeLockDao().observe().map { it?.maintenanceMode }.distinctUntilChanged()
    ) { _, _, _ -> Unit }

    private data class Plan(val targets: List<NameTarget>, val deletedCount: Int, val epoch: Long?, val hours: Int, val minutes: Int,
        val batchSize: Int, val timeout: Int, val wall: Long, val elapsed: Long, val boot: String) {
        fun remaining(row: NameTarget) = NameRefreshPolicy.remaining(row.nameAttemptWall, row.nameAttemptElapsed,
            row.nameAttemptBoot, row.nameAttemptSucceeded, wall, elapsed, boot, hours, minutes)
    }

    private suspend fun plan(countDeleted: Boolean = false, onlyId: Long? = null) = db.withTransaction {
        val config = checkNotNull(db.configDao().getConfig())
        val lock = db.runtimeLockDao().get()
        Plan(dao.nameTargets(), if (countDeleted) dao.deletedNameCount(onlyId) else 0, lock?.takeIf { it.maintenanceMode == MaintenanceMode.OFF }?.nameMaintenanceEpoch,
            config.nameRefreshHours, config.nameRetryMinutes, config.batchSize, config.timeoutSeconds,
            clock.nowWall(), clock.nowElapsed(), clock.bootId())
    }

    suspend fun nextDeadline(): Long? {
        val plan = plan()
        if (plan.epoch == null) return null
        val delay = plan.targets.asSequence().filterNot { it.nameLocked }.minOfOrNull(plan::remaining) ?: return null
        return if (delay == 0L) 0L else plan.elapsed + delay
    }

    suspend fun refresh(force: Boolean = false, onlyId: Long? = null): NameRefreshResult {
        val requested = plan(countDeleted = force, onlyId = onlyId)
        val targets = requested.targets.filter { (onlyId == null || it.id == onlyId) &&
            (force || (!it.nameLocked && requested.remaining(it) == 0L)) }
        return gate.withLock {
            val current = plan()
            val epoch = current.epoch
            if (epoch == null || epoch != requested.epoch) return@withLock NameRefreshResult(0, 0, targets.size + requested.deletedCount)
            val byId = current.targets.associateBy { it.id }
            val eligible = targets.filter { !it.nameLocked && it == byId[it.id] && (force || current.remaining(it) == 0L) }
            val shared = targets.mapNotNull { original ->
                byId[original.id]?.takeIf { finished -> !original.nameLocked && !finished.nameLocked &&
                    original.uid == finished.uid && original.stableId == finished.stableId &&
                    original.streamerMonitorGeneration == finished.streamerMonitorGeneration &&
                    finished.nameRevision > original.nameRevision && finished.nameAttemptWall != null }
            }
            var success = shared.count { it.nameAttemptSucceeded }
            var retry = shared.size - success
            for (planned in eligible.chunked(current.batchSize.coerceIn(1, 100))) {
                val batch = db.withTransaction {
                    val lock = db.runtimeLockDao().get()
                    if (lock?.maintenanceMode != MaintenanceMode.OFF || lock.nameMaintenanceEpoch != epoch) emptyList()
                    else planned.intersect(dao.unlockedNameTargets(planned.map { it.id }).toSet()).toList()
                }
                if (batch.isEmpty()) continue
                val names = try {
                    withTimeoutOrNull(current.timeout * 1000L) { source.fetch(batch.map { it.uid }) }.orEmpty()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyMap() }
                db.withTransaction {
                    val wall = clock.nowWall()
                    val elapsed = clock.nowElapsed()
                    val boot = clock.bootId()
                    for (row in batch) {
                        val name = names[row.uid]
                        if (dao.finishNameAttempt(row.id, row.uid, row.stableId, row.nameRevision,
                                row.streamerMonitorGeneration, epoch, name, wall, elapsed, boot) == 1) {
                            if (name == null) retry++ else success++
                        }
                    }
                }
            }
            NameRefreshResult(success, retry, targets.size + requested.deletedCount - success - retry)
        }
    }
}
