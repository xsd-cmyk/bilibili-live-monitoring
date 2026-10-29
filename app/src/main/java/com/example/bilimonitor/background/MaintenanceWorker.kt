package com.example.bilimonitor.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.bilimonitor.data.repository.MaintenanceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Daily, charging-only retention; never interrupts an active monitoring lease. */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MaintenanceRepository,
    private val logDao: com.example.bilimonitor.data.local.dao.LogDao,
    private val clock: com.example.bilimonitor.core.AppClock
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = repository.runCleanup(background = true)
        result.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            if (it is com.example.bilimonitor.data.repository.MaintenanceBusyException) {
                return if (runAttemptCount < 3) Result.retry() else Result.success()
            }
            try {
                logDao.insertAppError(com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                    com.example.bilimonitor.core.Ids.newId(), null, clock.nowWall(),
                    com.example.bilimonitor.data.local.AppError.DATABASE_ERROR,
                    "定期清理失败：${it.message}".take(500)))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("MaintenanceWorker", "清理失败且无法记录", e) }
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "daily-retention", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
