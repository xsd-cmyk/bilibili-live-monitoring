package com.example.bilimonitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilimonitor.data.local.RestoreConflictAction
import com.example.bilimonitor.data.local.RestoreConflictType
import com.example.bilimonitor.data.local.RestoreFinishReason
import com.example.bilimonitor.data.local.RestoreRunStatus
import com.example.bilimonitor.data.local.entity.AuthSessionEntity
import com.example.bilimonitor.data.local.entity.FollowImportStagingEntity
import com.example.bilimonitor.data.local.entity.FollowImportTaskEntity
import com.example.bilimonitor.data.local.entity.RestoreConflictEntity
import com.example.bilimonitor.data.local.entity.RestoreRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowImportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTask(task: FollowImportTaskEntity)

    @Query("SELECT * FROM follow_import_task ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestTask(): FollowImportTaskEntity?

    @Query("SELECT * FROM follow_import_task WHERE importTaskId = :taskId")
    suspend fun getTask(taskId: String): FollowImportTaskEntity?

    @Query("SELECT * FROM follow_import_task WHERE importTaskId = :taskId")
    fun observeTask(taskId: String): Flow<FollowImportTaskEntity?>

    @Query("UPDATE follow_import_task SET status = :status, finishedAt = :finishedAt, stagingCount = :stagingCount, error = :error WHERE importTaskId = :taskId")
    suspend fun updateTask(taskId: String, status: String, finishedAt: Long?, stagingCount: Long, error: String?)

    /**
     * 冷启动结算**在途**导入任务（缺陷 3）：全部置 FAILED、写 finishedAt 与原因。
     *
     * 为什么必须有：`cleanupFinished` 只删"终态 + finishedAt 非空"的行，而进程在
     * REQUESTED/FETCHING/PREVIEW/APPLYING 中途被杀时，任务行既不是终态、finishedAt 也是
     * NULL —— 这行连同它的 `follow_import_staging` 暂存行**永远不会**被保留策略回收
     * （外键 CASCADE 只在父行被删时生效，而父行压根删不掉）。同时 UI 的 `latestTask()`
     * 会一直读到这个中间态，表现为"上次的导入永远卡在正在获取"。
     *
     * 为什么用 `NOT IN (终态)` 而不是列举中间态：未知/未来新增的状态一律当作"没跑完"，
     * 结算成 FAILED 才既可清理又不撒谎（`EnumSafe` 读未知枚举值回退的目标也是 FAILED）。
     * PREVIEW 也算在途：预览只活在内存里，进程一死用户就再也够不到那份预览。
     *
     * @return 实际结算的行数（0 表示上次是正常退出，不必留痕）。
     */
    @Query(
        """
        UPDATE follow_import_task
        SET status = 'FAILED', finishedAt = :now, error = :reason
        WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED') AND startedAt <= :startupCutoff
        """
    )
    suspend fun settleInterrupted(now: Long, reason: String, startupCutoff: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStaging(rows: List<FollowImportStagingEntity>)

    @Query("SELECT * FROM follow_import_staging WHERE importTaskId = :taskId")
    suspend fun listStaging(taskId: String): List<FollowImportStagingEntity>

    @Query("DELETE FROM follow_import_staging WHERE importTaskId = :taskId")
    suspend fun clearStaging(taskId: String)

    /** 保留策略：终态导入任务保留 30 天（暂存行随任务级联删除）。 */
    @Query(
        """
        DELETE FROM follow_import_task
        WHERE importTaskId IN (
            SELECT importTaskId FROM follow_import_task
            WHERE status IN ('COMPLETED','FAILED','CANCELLED')
              AND finishedAt IS NOT NULL AND finishedAt < :before
            LIMIT :limit
        )
        """
    )
    suspend fun cleanupFinished(before: Long, limit: Int): Int
}

@Dao
interface RestoreDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: RestoreRunEntity)

    @Query("SELECT * FROM restore_run WHERE restoreRunId = :id")
    suspend fun getRun(id: String): RestoreRunEntity?

    @Query("SELECT * FROM restore_run WHERE restoreRunId = :id")
    fun observeRun(id: String): Flow<RestoreRunEntity?>

    /** 状态迁移必须带 status CAS（0.6.33.1）。 */
    @Query("UPDATE restore_run SET status = :next, finishedAt = :finishedAt, finishReason = :finishReason, warningCount = :warningCount WHERE restoreRunId = :id AND status = :expected")
    suspend fun casTransition(id: String, expected: RestoreRunStatus, next: RestoreRunStatus, finishedAt: Long?, finishReason: RestoreFinishReason?, warningCount: Int): Int

    @Query("SELECT * FROM restore_run WHERE status = 'APPLYING'")
    suspend fun findApplying(): List<RestoreRunEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConflicts(rows: List<RestoreConflictEntity>)

    @Query("SELECT * FROM restore_conflict WHERE restoreRunId = :runId")
    suspend fun listConflicts(runId: String): List<RestoreConflictEntity>

    @Query("SELECT * FROM restore_conflict WHERE restoreRunId = :runId")
    fun observeConflicts(runId: String): Flow<List<RestoreConflictEntity>>

    /** 冲突决策必须带"尚未决策"CAS（0.6.33.1）。 */
    @Query("UPDATE restore_conflict SET decision = :action WHERE conflictId = :conflictId AND decision IS NULL")
    suspend fun decide(conflictId: String, action: RestoreConflictAction): Int

    @Query("UPDATE restore_conflict SET decision = :action WHERE restoreRunId = :runId AND decision IS NULL")
    suspend fun decideAll(runId: String, action: RestoreConflictAction): Int

    /** 保留策略：终态恢复运行保留 90 天（冲突行随 restore_run 级联删除）。 */
    @Query(
        """
        DELETE FROM restore_run
        WHERE restoreRunId IN (
            SELECT restoreRunId FROM restore_run
            WHERE status IN ('COMPLETED','FAILED','CANCELLED') AND finishedAt IS NOT NULL AND finishedAt < :before
            LIMIT :limit
        )
        """
    )
    suspend fun cleanupFinished(before: Long, limit: Int): Int
}

@Dao
interface AuthSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: AuthSessionEntity)

    @Query("SELECT COALESCE(MAX(authGeneration), 0) FROM auth_session")
    suspend fun currentGeneration(): Long

    @Query("UPDATE auth_session SET revokedAt = :now WHERE revokedAt IS NULL")
    suspend fun revokeAll(now: Long): Int
}
