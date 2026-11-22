package com.example.bilimonitor.background

import android.content.Context
import android.net.ConnectivityManager
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.data.repository.NameRefreshResult
import com.example.bilimonitor.data.repository.StreamerNameRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class ManualNameRefresh(val running: Boolean = false, val result: NameRefreshResult? = null, val failed: Boolean = false)

@Singleton
class NameRefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context, private val repository: StreamerNameRepository,
    private val clock: AppClock, @Named("appScope") private val scope: CoroutineScope
) {
    private val scheduling = Mutex()
    private val due = Channel<Unit>(Channel.CONFLATED)

    private val manual = MutableStateFlow(ManualNameRefresh())
    val manualState = manual.asStateFlow()

    fun refreshAll() {
        val previous = manual.value
        if (previous.running || !manual.compareAndSet(previous, ManualNameRefresh(running = true))) return
        scope.launch {
            try { manual.value = ManualNameRefresh(result = repository.refresh(force = true)) }
            catch (cancelled: CancellationException) { manual.value = ManualNameRefresh(); throw cancelled }
            catch (_: Exception) { manual.value = ManualNameRefresh(failed = true) }
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            for (wake in due) {
                try { repository.refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { android.util.Log.w(TAG, "名称获取未完成，由后台任务接续", error) }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    repository.changes.map { repository.nextDeadline() }.distinctUntilChanged().collectLatest {
                        val deadline = arm() ?: return@collectLatest
                        delay((deadline - clock.nowElapsed()).coerceAtLeast(0))
                        if (context.getSystemService(ConnectivityManager::class.java).activeNetwork != null) due.trySend(Unit)
                    }
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { android.util.Log.w(TAG, "名称调度稍后重试", error); delay(60_000) }
            }
        }
    }

    suspend fun arm(): Long? = withContext(Dispatchers.IO) {
        scheduling.withLock {
            val deadline = repository.nextDeadline()
            val key = deadline?.let { "$TAG:${clock.bootId()}:$it" }
            val manager = WorkManager.getInstance(context)
            if (key != null) {
                val request = OneTimeWorkRequestBuilder<NameRefreshWorker>().addTag(TAG).addTag(key)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay((deadline - clock.nowElapsed()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
                manager.enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, request).result.get()
            }
            val pending = WorkQuery.Builder.fromTags(listOf(TAG)).addStates(listOf(WorkInfo.State.ENQUEUED)).build()
            manager.getWorkInfos(pending).get().filter { key !in it.tags }.forEach { manager.cancelWorkById(it.id).result.get() }
            deadline
        }
    }

    private companion object { const val TAG = "streamer-name-refresh" }
}

@HiltWorker
class NameRefreshWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters,
    private val repository: StreamerNameRepository, private val scheduler: NameRefreshScheduler
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        repository.refresh()
        if (scheduler.arm() == 0L) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }
}
