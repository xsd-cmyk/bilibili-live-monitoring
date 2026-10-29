package com.example.bilimonitor.background

import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.db.AppDatabase
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.repository.NotificationRepository
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One deadline-driven consumer. DB invalidations wake it without cancelling an in-flight post. */
@Singleton
class NotificationDeliveryCoordinator @Inject constructor(
    private val db: AppDatabase,
    private val repository: NotificationRepository,
    private val dispatcher: NotificationDispatcher,
    private val clock: AppClock,
    @Named("appScope") private val scope: CoroutineScope
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val observer = launch {
                combine(db.notificationOutboxDao().observeDeliveryChanges(),
                    db.notificationAggregateDao().observeWindowChanges()) { _, _ -> Unit }
                    .retryWhen { error, _ ->
                        if (error is CancellationException) throw error
                        recordFailure(error)
                        delay(30_000L)
                        true
                    }.collect { wake.trySend(Unit) }
            }
            try {
                while (currentCoroutineContext().isActive) {
                    try {
                        val wait = nextDelay()
                        if (wait == null) {
                            wake.receive()
                        } else if (wait > 0) {
                            withTimeoutOrNull(wait) { wake.receive() }
                        } else {
                            repository.processWindowEnds()
                            dispatcher.dispatchDue()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordFailure(e)
                        delay(30_000L)
                    }
                }
            } finally { observer.cancel() }
        }
    }

    private suspend fun recordFailure(error: Throwable) {
        try {
            db.logDao().insertAppError(ApplicationErrorLogEntity(
                Ids.newId(), null, clock.nowWall(), AppError.NOTIFICATION_UNAVAILABLE,
                "通知调度失败，将在 30 秒后重试：${error.message}".take(500)))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.e("NotificationDelivery", "通知调度及错误记录失败", error) }
    }

    private suspend fun nextDelay(): Long? {
        val now = clock.nowWall()
        val elapsed = clock.nowElapsed()
        val boot = clock.bootId()
        val delivery = db.notificationOutboxDao().nextDeliveryDelay(now, elapsed, boot)
        val window = db.notificationAggregateDao().listInProgress().minOfOrNull {
            if (it.bootId == boot) it.windowEndElapsed - elapsed else it.windowEndWall - now
        }
        return listOfNotNull(delivery, window).minOrNull()?.coerceAtLeast(0L)
    }

    /** WorkManager owns its wake time only while doWork is running; finish short windows before returning. */
    suspend fun finishWorkerDelivery() {
        withTimeoutOrNull(12_000L) {
            while (currentCoroutineContext().isActive) {
                repository.processWindowEnds()
                dispatcher.dispatchDue()
                val next = nextDelay() ?: break
                if (next > 6_000L) break
                delay(next.coerceAtLeast(25L))
            }
        }
    }
}
