package com.example.bilimonitor.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.local.db.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Singleton
class NotificationInteractionRepository @Inject constructor(private val db: AppDatabase, private val clock: AppClock) {
    suspend fun record(key: String, dismissed: Boolean) = db.withTransaction {
        val now = clock.nowWall()
        // An actual click/dismiss is positive evidence of delivery, even if activeNotifications missed it.
        val changed = db.notificationHistoryDao().recordInteraction(key,
            if (dismissed) "USER_DISMISSED" else "USER_CLICKED", now)
        if (changed > 0) {
            val acknowledged = db.notificationOutboxDao().acknowledgeInteraction(key, now)
            if (acknowledged > 0) {
                db.notificationOutboxDao().findByEventKey(key)?.aggregateId?.let { id ->
                    db.notificationAggregateDao().casStatus(id,
                        com.example.bilimonitor.data.local.NotificationAggregateStatus.FROZEN,
                        com.example.bilimonitor.data.local.NotificationAggregateStatus.DISPATCHED,
                        now, db.notificationAggregateDao().countActiveEvents(id))
                }
            }
            db.notificationHistoryDao().reconcileTerminalStatus(now)
        }
    }

    suspend fun recordFailure(error: Exception) {
        try {
            db.logDao().insertAppError(com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                com.example.bilimonitor.core.Ids.newId(), null, clock.nowWall(),
                com.example.bilimonitor.data.local.AppError.NOTIFICATION_UNAVAILABLE,
                "通知交互确认失败：${error.message}".take(500)))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { android.util.Log.e("NotificationInteraction", "通知交互及错误记录失败", error) }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationInteractionEntryPoint {
    fun interactions(): NotificationInteractionRepository
    @Named("appScope") fun scope(): CoroutineScope
}

object NotificationInteractions {
    fun record(context: Context, key: String, dismissed: Boolean = false, finished: () -> Unit = {}) {
        try {
            val entry = EntryPointAccessors.fromApplication(context, NotificationInteractionEntryPoint::class.java)
            entry.scope().launch {
                try { entry.interactions().record(key, dismissed) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { entry.interactions().recordFailure(e) }
                finally { finished() }
            }
        } catch (e: Exception) {
            android.util.Log.e("NotificationInteraction", "通知交互入口不可用", e)
            finished()
        }
    }
}

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("event_key") ?: return
        val pending = goAsync()
        NotificationInteractions.record(context, key, dismissed = true, finished = { pending.finish() })
    }
}
