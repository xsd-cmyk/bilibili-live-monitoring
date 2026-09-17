package com.example.bilimonitor.core

import android.content.Context
import com.example.bilimonitor.data.local.dao.CrashRecordDao
import com.example.bilimonitor.data.local.entity.CrashRecordEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 崩溃记录与自恢复（原规范 81 / 225，0.6.33 / 0.6.34）。
 *
 * 原实现的 `crash_record` 表**没有任何读写代码**，「崩溃后恢复」这条能力实际不存在：
 * 进程被系统杀死或未捕获异常退出后，用户与开发者都无从知道发生过什么。
 *
 * 这里分两半：
 *  - [install]：注册未捕获异常处理器，把崩溃落库（写入必须快，因此用 runBlocking 立即落盘）；
 *  - [recordStartup]/[markRecovered]：每次冷启动登记一次「进程实例」，
 *    并把上一次未标记恢复的崩溃记录标记为已恢复 —— 这正好是"崩溃后自恢复"的可审计痕迹。
 */
@Singleton
class CrashRecorder @Inject constructor(
    private val crashRecordDao: CrashRecordDao,
    private val clock: AppClock
) {
    /** 本进程实例 id；用于区分"同一次崩溃"与"多次崩溃"。 */
    val processInstanceId: String = Ids.newId()

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 崩溃写入必须同步完成，否则进程随即终止、记录丢失；
            // 但**不能无限等**：若崩溃发生在写事务中或写锁被占用，runBlocking 会把主线程
            // 卡在这里，表现为"崩溃了却不退出"（最后被系统强杀，记录照样丢）。
            // 给 1 秒预算，超时就放弃记录、直接放行给原处理器。
            runCatching {
                runBlocking {
                    withTimeoutOrNull(CRASH_WRITE_TIMEOUT_MS) {
                        crashRecordDao.insert(
                            CrashRecordEntity(
                                crashId = Ids.newId(),
                                bootId = clock.bootId(),
                                occurredAt = clock.nowWall(),
                                processInstanceId = processInstanceId,
                                exitReason = summarize(throwable),
                                recoveredAt = null
                            )
                        )
                    }
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 冷启动登记：把上次未标记恢复的崩溃记录标为已恢复，返回本次恢复的条数。
     * 崩溃记录本身在崩溃时已写入，这里只做「恢复」标记，因此不会为正常启动产生噪声。
     */
    suspend fun markPreviousCrashesRecovered(): Int {
        val now = clock.nowWall()
        val unrecovered = runCatching { crashRecordDao.recentUnrecovered(CRASH_SCAN_LIMIT) }
            .getOrDefault(emptyList())
        var recovered = 0
        for (row in unrecovered) {
            // 同一次进程实例内的记录不算"上次崩溃"
            if (row.processInstanceId == processInstanceId) continue
            recovered += runCatching { crashRecordDao.markRecovered(row.crashId, now) }.getOrDefault(0)
        }
        return recovered
    }

    /** 供诊断包与健康中心展示。 */
    suspend fun recent(limit: Int): List<CrashRecordEntity> =
        runCatching { crashRecordDao.recent(limit) }.getOrDefault(emptyList())

    suspend fun cleanup(days: Int = CRASH_RETAIN_DAYS): Int {
        val before = clock.nowWall() - days.toLong() * 24 * 60 * 60 * 1000
        return runCatching { crashRecordDao.cleanupOld(before, CLEANUP_LIMIT) }.getOrDefault(0)
    }

    /** 只保留异常类型与首帧位置，避免把用户数据写进日志（原规范 212 脱敏）。 */
    private fun summarize(t: Throwable): String {
        val first = t.stackTrace.firstOrNull()
        val where = if (first != null) {
            "${first.className.substringAfterLast('.')}.${first.methodName}:${first.lineNumber}"
        } else "unknown"
        return "${t::class.java.simpleName}: ${t.message?.take(200) ?: ""} @ $where"
    }

    companion object {
        const val CRASH_SCAN_LIMIT = 20
        const val CLEANUP_LIMIT = 200
        const val CRASH_RETAIN_DAYS = 90

        /** 崩溃落库的时间预算：超时即放弃记录，保证崩溃处理不被拖住。 */
        const val CRASH_WRITE_TIMEOUT_MS = 1000L
    }
}
