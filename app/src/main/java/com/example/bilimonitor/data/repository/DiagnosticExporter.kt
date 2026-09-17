package com.example.bilimonitor.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.AppClocks
import com.example.bilimonitor.data.local.NotificationOutboxStatus
import com.example.bilimonitor.data.local.db.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一键导出诊断包（原规范 219）。
 *
 * 结构固定为：
 * ```
 * app_info.json           版本 / 设备 / 时区 / bootId
 * monitoring_logs.json    监控错误日志
 * application_errors.json 软件运行错误日志
 * health_snapshot.json    当前健康状态 + 未恢复盲区 + 活动问题 + 最近健康事件
 * settings_sanitized.json 脱敏后的监控配置
 * migration_info.json     数据库实际版本与代码编译版本
 * notification_pipeline.json 通知各状态计数 + 最近终态行（**不含 payload**）
 * ```
 *
 * **耦合点（新增迁移时必看）**：`MIGRATION_PAIRS` 必须与 `AppMigrations.ALL` 保持同步，
 * 否则诊断包会谎报"这台设备的库能升到哪一版"。
 *
 * 硬性边界（原规范 219 / 原规范 138）：
 *  - **严禁包含登录凭证**：不读取 `CredentialStore`，也不写入任何 cookie/token/账号信息；
 *  - **默认不导出主播历史**：只导出主播数量等聚合信息，避免诊断包过大。
 *
 * JSON 采用手写序列化而非反射序列化：结构固定、字段少，
 * 且能保证"新增字段不会意外把实体里的敏感列带出去"。
 */
@Singleton
class DiagnosticExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val clock: AppClock
) {
    data class DiagnosticResult(val fileName: String, val entryCount: Int, val bytes: Int)

    /**
     * 组装全部分片、打包并写入「下载」；返回的文件名是**磁盘上的真实名字**。
     *
     * 取消语义：协程被取消时抛出的 `CancellationException` 会原样向上抛（见下方 onFailure），
     * 不会被折叠成 `Result.failure`，避免 UI 把"用户自己返回了"当成"导出失败"。
     */
    suspend fun export(): Result<DiagnosticResult> = runCatching {
        val now = clock.nowWall()

        // 先在协程内把数据全部取出，再做纯字符串拼装（避免在构建器里调用挂起函数）。
        val monitoringLogs = db.logDao().recentMonitoringErrors(DIAGNOSTIC_LIMIT)
        val appErrors = db.logDao().recentAppErrors(DIAGNOSTIC_LIMIT)
        val healthEvents = db.logDao().recentHealthEvents(DIAGNOSTIC_LIMIT)
        val lastHealth = db.logDao().lastHealthStatus(COMPONENT_MONITORING)
        // 诊断包也要限量：problem_state 会随"主播数 × 故障类型"增长，
        // 不带 LIMIT 的全表读进内存，恰恰是"排查卡顿"时最不该做的事。
        val activeProblems = runCatching { db.problemDao().listActive(DIAGNOSTIC_LIMIT) }
            .getOrDefault(emptyList())
        val systemGap = runCatching {
            db.monitoringGapDao().findOpenByScope(com.example.bilimonitor.data.local.GapScope.SYSTEM)
        }.getOrNull()
        val streamerGaps = runCatching { db.monitoringGapDao().openStreamerGaps(DIAGNOSTIC_LIMIT) }
            .getOrDefault(emptyList())
        val monitoredCount = runCatching { db.streamerDao().listMonitorable().size }.getOrDefault(0)
        val config = db.configDao().getConfig()
        val sourceDataVersion = runCatching { db.configDao().getSourceDataVersion() }.getOrNull()
        val dbVersion = runCatching { db.openHelper.readableDatabase.version }.getOrDefault(-1)

        val entries = linkedMapOf<String, String>()
        entries["app_info.json"] = J.obj(
            // 应用名从资源取：改名（主播监控 → Kaoru）后这里若不跟着变，
            // 诊断包就等于在应用身份这一栏对支持人员撒谎。
            J.s("appName", appDisplayName()),
            J.s("packageName", context.packageName),
            J.s("versionName", appVersionName()),
            J.n("versionCode", appVersionCode()),
            J.n("androidSdkInt", android.os.Build.VERSION.SDK_INT),
            J.s("deviceModel", android.os.Build.MODEL),
            J.s("deviceAbi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
            J.s("timeZone", clock.currentTimeZone()),
            J.s("bootId", clock.bootId()),
            J.n("exportedAt", now)
        )

        entries["monitoring_logs.json"] = J.arr(monitoringLogs.map { r ->
            J.obj(
                J.n("occurredAt", r.occurredAt),
                J.s("errorCode", r.errorCode.name),
                J.s("problemKey", r.problemKey),
                J.s("streamerStableId", r.streamerStableId),
                J.s("detail", r.detail)
            )
        })

        entries["application_errors.json"] = J.arr(appErrors.map { r ->
            J.obj(
                J.n("occurredAt", r.occurredAt),
                J.s("errorCode", r.errorCode.name),
                J.s("operationId", r.operationId),
                J.s("detail", r.detail)
            )
        })

        entries["health_snapshot.json"] = J.obj(
            J.s("lastMonitoringHealth", lastHealth?.name),
            J.b("foregroundServiceRunning",
                com.example.bilimonitor.background.MonitoringService.isRunning.value),
            J.b("notificationsEnabled",
                com.example.bilimonitor.notify.NotificationPoster.hasPermission(context)),
            J.n("monitoredStreamerCount", monitoredCount),
            J.s("openSystemGap", systemGap?.let { "${it.reason.name}@${it.startedAt}" }),
            J.arrOf("openStreamerGaps", streamerGaps.map { g ->
                J.obj(
                    J.n("affectedStartAt", g.affectedStartAt),
                    J.s("streamerStableId", g.streamerStableId),
                    J.s("reason", g.reason.name)
                )
            }),
            J.arrOf("activeProblems", activeProblems.map { p ->
                J.obj(
                    J.n("firstObservedAt", p.firstObservedAt),
                    J.s("problemKey", p.problemKey)
                )
            }),
            J.arrOf("healthEvents", healthEvents.map { h ->
                J.obj(
                    J.n("occurredAt", h.occurredAt),
                    J.s("component", h.component),
                    J.s("fromStatus", h.fromStatus?.name),
                    J.s("toStatus", h.toStatus.name)
                )
            }),
            J.n("snapshotAt", now)
        )

        // 脱敏配置：只导出行为参数。不含账号、凭证、主播列表。
        entries["settings_sanitized.json"] = if (config == null) {
            J.obj(J.s("error", "monitoring_config 缺失"), J.n("exportedAt", now))
        } else {
            J.obj(
                J.s("mode", config.mode.name),
                J.b("monitoringEnabled", config.monitoringEnabled),
                // 「无保证保活」的确认状态：用户说"我明明确认过/没确认过"时，
                // 只有这两个字段能证明界面到底存了什么（都不含凭证）。
                J.b("noGuaranteeAcknowledged", config.noGuaranteeAcknowledged),
                J.n("noGuaranteeAcknowledgedAt", config.noGuaranteeAcknowledgedAt),
                J.s("backgroundKeepAliveChoice", config.backgroundKeepAliveChoice.name),
                J.n("configVersion", config.configVersion),
                J.n("intervalSeconds", config.intervalSeconds),
                J.n("batchSize", config.batchSize),
                J.n("maxConcurrency", config.maxConcurrency),
                J.n("timeoutSeconds", config.timeoutSeconds),
                J.n("maxRetries", config.maxRetries),
                J.n("retryBaseSeconds", config.retryBaseSeconds),
                J.n("maxRetryDelaySeconds", config.maxRetryDelaySeconds),
                J.n("circuitBreakerThreshold", config.circuitBreakerThreshold),
            J.n("retryFailureRatioPercent", config.retryFailureRatioPercent),
            J.n("banRecheckIntervalSeconds", config.banRecheckIntervalSeconds),
                J.n("circuitBreakerRecoverySeconds", config.circuitBreakerRecoverySeconds),
                // v10 熔断总开关：**新增 monitoring_config 列时必须同步这里**，否则用户报
                // "熔断还在暂停、没按我改的走"时，支持人员看不到这个开关的状态
                // （本项目已经漏过两次"新增配置没进诊断包"：本次补上的
                //   noGuaranteeAcknowledged / noGuaranteeAcknowledgedAt / placeholderText
                //   就是第二次；手工逐列列举天生容易漏，动过实体就回来对一遍）。
                J.b("circuitBreakerEnabled", config.circuitBreakerEnabled),
                J.b("aggregationEnabled", config.aggregationEnabled),
                J.n("aggregationThreshold", config.aggregationThreshold),
                J.n("aggregationWindowSeconds", config.aggregationWindowSeconds),
                J.n("batchCooldownSeconds", config.batchCooldownSeconds),
                J.n("freshnessStaleSeconds", config.freshnessStaleSeconds),
                J.n("startConfirmationCount", config.startConfirmationCount),
                // 昵称缺失时界面显示的占位文案（249.9.1.1）：用户截图里的"[待获取]"
                // 就是它，改了必须能在诊断包里看到。
                J.s("placeholderText", config.placeholderText),
                J.n("endConfirmationCount", config.endConfirmationCount),
                J.n("configUpdatedAt", config.updatedAt),
                J.n("exportedAt", now)
            )
        }

        // 迁移信息（原规范 185）：同时给出实际版本与编译版本，
        // 若实际 > 编译 说明存在降级风险（当前实现未开启破坏性迁移，会直接抛错）。
        entries["migration_info.json"] = J.obj(
            J.n("currentDatabaseVersion", dbVersion),
            J.n("compiledDatabaseVersion", COMPILED_DB_VERSION),
            J.b("destructiveMigrationAllowed", false),
            J.b("downgradeSupported", false),
            J.arrOf("availableMigrationPaths", MIGRATION_PAIRS.map { (from, to) ->
                J.obj(J.n("from", from), J.n("to", to))
            }),
            J.n("sourceDataVersion", sourceDataVersion),
            J.n("exportedAt", now)
        )

        // 通知流水线快照（用户报"没收到通知"时唯一能区分原因的证据）。
        // 只导出状态与错误文本：payload 里含主播昵称/房间标题，属用户数据，不导出。
        entries["notification_pipeline.json"] = J.obj(
            J.b("notificationsEnabled",
                com.example.bilimonitor.notify.NotificationPoster.hasPermission(context)),
            J.arrOf("countsByStatus", listOf(
                NotificationOutboxStatus.PENDING, NotificationOutboxStatus.RETRY_WAIT,
                NotificationOutboxStatus.PROCESSING, NotificationOutboxStatus.SENT,
                NotificationOutboxStatus.FAILED, NotificationOutboxStatus.EXPIRED,
                NotificationOutboxStatus.CANCELLED, NotificationOutboxStatus.DELIVERY_UNKNOWN
            ).map { status ->
                J.obj(J.s("status", status.name), J.n("count", runCatching {
                    db.notificationOutboxDao().countByStatus(status)
                }.getOrDefault(-1)))
            }),
            J.n("activeOutbox", runCatching { db.notificationOutboxDao().countActiveOutbox() }.getOrDefault(-1)),
            J.arrOf("recentTerminal", runCatching {
                db.notificationOutboxDao().recentTerminal(NOTIFICATION_SNAPSHOT_LIMIT)
            }.getOrDefault(emptyList()).map { row ->
                J.obj(
                    J.s("eventType", row.eventType.name),
                    J.s("status", row.status.name),
                    J.n("attemptCount", row.attemptCount),
                    J.n("createdAt", row.createdAt),
                    J.n("expiresAt", row.expiresAt),
                    J.s("lastError", row.lastError)
                )
            }),
            J.n("snapshotAt", now)
        )

        // zip 压缩与 MediaStore 写入都是实打实的 IO（内存 deflate 还是纯 CPU 的同步阻塞），
        // 这里原本两条都在主线程上跑，而这个按钮恰恰是用户"觉得卡"时才点的。
        val bytes = zipOf(entries)
        // 文件名精确到秒（与备份导出一致）：只到"日"的话，同一天导出第二次会被 MediaStore
        // 落成 `... (1).zip`，而提示里报的还是不带 (1) 的名字 —— 文案就撒谎了。
        val stamp = AppClocks.stamp("yyyyMMdd_HHmmss")
        val requestedName = "BiliMonitor_Diagnostics_$stamp.zip"
        // 回报「磁盘上真实的名字」（回读 DISPLAY_NAME），而不是我们请求的名字。
        val actualName = writeToDownloads(requestedName, bytes)
        DiagnosticResult(actualName, entries.size, bytes.size)
    }.onFailure {
        // 取消不是失败：点完导出马上返回上一页会取消 viewModelScope，抛出的
        // CancellationException 必须原样重抛，否则页面会弹一句假的"导出失败：…"。
        // （此时可能已插入的 IS_PENDING=1 行由 writeToDownloads 自己清理。）
        if (it is CancellationException) throw it
        // ★ 非取消失败必须留痕（复查发现的缺陷）：原先只变成 5 秒后自动消失的界面文案 + logcat，
        //   application_error_log 里一行都没有 —— 而同轮的历史/统计导出都补了这条留痕。
        //   两条线口径要一致，否则用户报"诊断包老是导出失败"时，诊断包里恰好查不到这件事。
        runCatching {
            db.logDao().insertAppError(
                com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity(
                    errorId = com.example.bilimonitor.core.Ids.newId(),
                    operationId = null,
                    occurredAt = clock.nowWall(),
                    errorCode = com.example.bilimonitor.data.local.AppError.EXPORT_FAILED,
                    detail = ("诊断包导出失败：${it::class.java.simpleName}: ${it.message}").take(320)
                )
            )
        }
    }

    // ---- 手写 JSON 构建（纯函数，无挂起、无反射）----

    private object J {
        fun q(s: String): String {
            val out = StringBuilder(s.length + 8)
            for (ch in s) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
                }
            }
            return "\"$out\""
        }

        /** 字符串字段；null → JSON null。 */
        fun s(key: String, v: String?): String = "${q(key)}:" + if (v == null) "null" else q(v)

        /** 数值字段；接受任意 Number（Int/Long 等），null → JSON null。 */
        fun n(key: String, v: Number?): String = "${q(key)}:" + (v?.toString() ?: "null")

        fun b(key: String, v: Boolean): String = "${q(key)}:$v"

        fun obj(vararg parts: String): String = parts.joinToString(",", "{", "}")

        fun arrOf(key: String, items: List<String>): String =
            "${q(key)}:" + items.joinToString(",", "[", "]")

        fun arr(items: List<String>): String = items.joinToString(",", "[", "]")
    }

    // ---- 打包与落盘 ----

    /**
     * 打包成 zip。
     *
     * 为什么改成 `suspend` + `withContext(Dispatchers.IO)`：内存 deflate 是**同步的纯 CPU**
     * 工作，跑在主线程上会直接掉帧；而调用它的按钮正是用户"觉得卡"的时候点的。
     */
    private suspend fun zipOf(entries: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        bos.toByteArray()
    }

    /**
     * 写入「下载」并返回**磁盘上真正的文件名**（回读 DISPLAY_NAME）。
     *
     * 为什么不用入参名回报：MediaStore 遇到重名会自动改成 `名字 (1).zip`，
     * 直接回报请求名的话，提示里的文件名就不是磁盘上的文件名 —— 文案就撒谎了。
     *
     * 整段切到 `Dispatchers.IO`；失败时删掉刚插入的行 —— 否则「下载」里会留下一条
     * 用户看不见、要等系统约 7 天才回收的 `IS_PENDING=1` 幽灵行。
     */
    private suspend fun writeToDownloads(name: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建诊断包文件")

            // insert 之后的每一步（写入 / 转正 / 回读）都在同一个 try 里：
            // 任何一步失败都要先清掉这条占位行再重抛，不能留幽灵行。
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("无法写入诊断包")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                // 转正必须校验影响行数：返回 0 说明这行没能变成用户可见状态，
                // 此时静默当成功就是谎报"导出成功"。
                val updated = resolver.update(uri, values, null, null)
                if (updated == 0) throw IllegalStateException("诊断包写入后无法转为可见状态")

                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    // 空串也要当成"没读到"：某些 provider 会返回空白名，
                    // 直接用它会让提示变成"已导出到 Download/（7 个文件…）"（复查发现的缺口）。
                    ?.takeIf { it.isNotBlank() }
                    ?: name
            } catch (e: Exception) {
                // 协程被取消（用户点了返回）时也会走到这里：半成品同样要清掉。
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }

    /** 应用显示名：取 `strings.xml` 的 `app_name`（与设置页「关于」同一来源，不写字面量）。 */
    private fun appDisplayName(): String = runCatching {
        context.getString(com.example.bilimonitor.R.string.app_name)
    }.getOrDefault("unknown")

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun appVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrNull() ?: -1L

    companion object {
        /** 诊断包内每类记录上限，避免包体过大。 */
        const val DIAGNOSTIC_LIMIT = 500

        /** 通知流水线快照条数上限。 */
        const val NOTIFICATION_SNAPSHOT_LIMIT = 50

        private const val COMPONENT_MONITORING = "MONITORING"

        /**
         * 当前代码编译时的 Room 版本，与 `AppDatabase` 共用**同一個**常量，
         * 不再各写一个数字（此前备份清单与诊断包各硬编码过版本号，升版时漏改会导致判错）。
         */
        const val COMPILED_DB_VERSION = com.example.bilimonitor.data.local.db.AppDatabase.DB_VERSION

        /**
         * 可用的迁移路径清单。
         *
         * **耦合点**：这份列表必须与 `AppMigrations.ALL` 保持同步 —— 它只是给支持人员看的
         * "这台设备的库能升到哪一版"，一旦漏改就会谎报（此前硬编码停在 5→6，
         * 而库已经到 8，等于告诉支持人员"升不上去"）。
         * 更彻底的做法是由 `AppMigrations` 暴露 (from,to) 列表，这里直接引用；
         * 那需要改动 `AppMigrations.kt`，已记入台账待办。
         *
         * ★ 2026 补齐：清单此前停在 `7 to 8`，而库已经到 9、现在到 10 —— 谎报"升不上去"。
         *   本次把 `8 to 9`（失败占比 + 封禁复查间隔）与 `9 to 10`（熔断开关）一并补上。
         */
        val MIGRATION_PAIRS: List<Pair<Int, Int>> = listOf(
            1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 9, 9 to 10
        )
    }
}
