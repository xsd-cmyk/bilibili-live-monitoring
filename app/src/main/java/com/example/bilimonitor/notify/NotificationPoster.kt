package com.example.bilimonitor.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.bilimonitor.MainActivity
import com.example.bilimonitor.R
import com.example.bilimonitor.data.local.NotificationEventType
import com.example.bilimonitor.data.local.entity.NotificationOutboxEntity
import com.example.bilimonitor.data.repository.BatchNotificationPayload
import com.example.bilimonitor.data.repository.LiveNotificationPayload
import com.example.bilimonitor.data.repository.NotificationPayloads
import com.example.bilimonitor.data.repository.SystemProblemPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DeliveryOutcome {
    data class Done(val result: com.example.bilimonitor.data.local.DeliveryAttemptResult, val error: String?) : DeliveryOutcome
}

/**
 * 通知发送实现：事务外调用 NotificationManager.notify（0.6.11）。
 * 结果只有 SENT / FAILED（UNKNOWN 由崩溃恢复流程结算）。
 */
@Singleton
class NotificationPoster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: okhttp3.OkHttpClient
) {

    /**
     * 大图标专用客户端：在共享客户端基础上把超时收紧到 [ICON_TIMEOUT_SECONDS]。
     * 共享客户端是为接口请求配的 15s，用在"最多 20 条串行投递"的路径上代价太高。
     */
    private val iconClient: okhttp3.OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(ICON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(ICON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(ICON_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 投递后核对：该 id 是否真的出现在本应用的活跃通知里。
     *
     * 为什么要复核两次：`notify()` 是异步交给系统服务的，刚调用完立刻查可能还查不到 ——
     * 那种"假阴性"会让一条**已经送达**的通知被记成结果未知并重发（用户被同一条通知再提醒一次）。
     * 因此先查一次，未命中则短暂等待再查一次；两次都没命中才判为"没送达"。
     *
     * 查询本身失败（个别 ROM 限制该 API）时按**已送达**处理：宁可漏报一次"没显示"，
     * 也不要凭一次失败就误报并触发重发。
     */
    private suspend fun isActiveAfterPost(notificationId: Int): Boolean {
        if (hasActiveNotification(notificationId)) return true
        kotlinx.coroutines.delay(ACTIVE_CHECK_RETRY_DELAY_MS)
        return hasActiveNotification(notificationId)
    }

    private fun hasActiveNotification(notificationId: Int): Boolean = runCatching {
        NotificationManagerCompat.from(context).activeNotifications.any { it.id == notificationId }
    }.getOrDefault(true)

    suspend fun post(outbox: NotificationOutboxEntity): DeliveryOutcome.Done = withContext(Dispatchers.IO) {
        if (!NotificationPoster.hasPermission(context)) {
            return@withContext DeliveryOutcome.Done(
                com.example.bilimonitor.data.local.DeliveryAttemptResult.FAILED,
                "PERMISSION_DENIED"
            )
        }
        // ★ 渠道被用户关掉（IMPORTANCE_NONE）时 `notify()` 是**静默无效**的：系统不报错、
        //   不抛异常，调用方看不出任何区别。原先只判权限，于是"没发出去"被记成 SENT ——
        //   通知历史、诊断包、聚合状态三方都显示一切正常，而用户什么都没收到。
        //   判据放在构造通知**之前**：渠道关着时连封面都不用下载，也不该白白重试 5 次。
        val channelId = NotificationPoster.channelIdFor(outbox.eventType)
        if (!NotificationPoster.isChannelEnabled(context, channelId)) {
            return@withContext DeliveryOutcome.Done(
                com.example.bilimonitor.data.local.DeliveryAttemptResult.FAILED,
                "CHANNEL_DISABLED:$channelId"
            )
        }
        val builder = when (outbox.eventType) {
            NotificationEventType.BATCH_LIVE -> buildBatch(outbox)
            NotificationEventType.SYSTEM_PROBLEM, NotificationEventType.SYSTEM_RECOVERED -> buildSystem(outbox)
            // 封禁类有自己的 payload：若落到 buildSingle 会解不出 LiveNotificationPayload，
            // 直接判成 PAYLOAD_INVALID，通知永远发不出去（所以这个分支是必需的）。
            NotificationEventType.BAN_ENTERED, NotificationEventType.BAN_LIFTED -> buildBan(outbox)
            else -> buildSingle(outbox)
        } ?: return@withContext DeliveryOutcome.Done(
            com.example.bilimonitor.data.local.DeliveryAttemptResult.FAILED,
            "PAYLOAD_INVALID"
        )
        try {
            NotificationManagerCompat.from(context).notify(outbox.notificationId, builder)
            // ★ 投递后核对（2026 复查新增）：`notify()` 不抛异常**不等于**通知真的出现了 ——
            //   系统在若干情况下会静默丢弃（单应用活跃通知条数达到上限、
            //   渠道在投递瞬间被关、厂商 ROM 的额外限制），而这里原先一律记 SENT：
            //   通知历史与诊断包全绿，用户却什么都没看到。
            //   核对一次代价很低（查询本应用自己的活跃通知），查不到就记 DELIVERY_UNKNOWN，
            //   走既有重试路径（同 id 重发是**覆盖**，不会多出一条通知）。
            if (!isActiveAfterPost(outbox.notificationId)) {
                return@withContext DeliveryOutcome.Done(
                    com.example.bilimonitor.data.local.DeliveryAttemptResult.DELIVERY_UNKNOWN,
                    "NOT_VISIBLE_AFTER_POST"
                )
            }
            DeliveryOutcome.Done(com.example.bilimonitor.data.local.DeliveryAttemptResult.SENT, null)
        } catch (e: SecurityException) {
            DeliveryOutcome.Done(com.example.bilimonitor.data.local.DeliveryAttemptResult.FAILED, "PERMISSION_DENIED")
        } catch (e: Exception) {
            DeliveryOutcome.Done(com.example.bilimonitor.data.local.DeliveryAttemptResult.FAILED, e.message)
        }
    }

    /**
     * 通知的**主体点击**目标。开播类与下播类**分别对待**（用户要求）。
     *
     *  - **开播类**（START_CONFIRMED / LIVE_RECONFIRMED / BATCH_LIVE）→ 进直播间，且优先唤起
     *    哔哩哔哩客户端。实现放在 [LiveRoomRedirectActivity] 这个透明跳板里，而不是在这里用
     *    `resolveActivity` 判断 —— 原因见那个类的注释（包可见性 + `<queries>` 匹配规则会让
     *    "装了客户端"的分支静默失效，表现成"永远只跳浏览器"，而我们什么都看不出来）。
     *    `PendingIntent` 必须在创建时确定目标，跳板是唯一能"点击那一刻再决定"的做法。
     *  - **下播类**（END_CONFIRMED）→ 进**应用内该主播的直播历史二级页**（用户指定；原先这里
     *    写的是"详情页"，与解析器的实际落点不符 —— 见 NotificationDeepLinkResolver 里
     *    `endOfLive` 分支，那里跳到 `history/streamer/{id}`）。不再经过跳板、也不唤起
     *    客户端/浏览器。理由：点到这条通知时主播已经不在播，送他去直播间只会看到一个
     *    "已结束"的页面；而他此刻要看的正是"谁下播了、这一场播了多久"，那是历史页的内容。
     *    应用内目标沿用既有机制 —— 由 [appIntent] 带上 `eventKey`，
     *    交给 `NotificationDeepLinkResolver` 解析成主播 id，**不另造一套深链协议**。
     *
     * 没有直播间地址的通知（例如系统问题通知）维持原行为：点开应用内页面。
     */
    private fun contentIntent(
        eventType: NotificationEventType,
        eventKey: String,
        url: String?
    ): PendingIntent {
        val safe = UrlPolicy.validate(url)
        val target = if (safe != null && !isOfflineEvent(eventType)) {
            LiveRoomRedirectActivity.intent(
                context = context,
                roomId = roomIdFromUrl(safe),
                httpsUrl = safe,
                eventKey = eventKey
            )
        } else {
            appIntent(eventType, eventKey, url)
        }
        return PendingIntent.getActivity(
            context,
            eventKey.hashCode(),
            target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 下播类事件：点击一律落回应用内，不再唤起客户端/浏览器。批量、开播类都不受影响。 */
    private fun isOfflineEvent(eventType: NotificationEventType): Boolean =
        eventType == NotificationEventType.END_CONFIRMED

    /**
     * 应用内页面（主播详情 / 播放历史 / 诊断页）：作为"没有直播间地址"时的兜底。
     *
     * 注：开播类通知**只有主体点击这一个落点**（进直播间），"查看记录"按钮已按用户要求移除 ——
     * 所以开播通知不再有进应用内的入口，这是刻意的产品取舍，不是漏掉了什么。
     *
     * ★ 下播类事件**必须不透传 url**：`MainActivity.handleIntent` 只要在 Intent 里看到
     * `EXTRA_URL` 就会立刻 `ACTION_VIEW` 把浏览器拉起来。下播通知这次改动的全部意义就是
     * "别再往直播间跳"，透传 url 等于一次点击同时打开浏览器和应用，把这条需求原地抵消。
     */
    private fun appIntent(eventType: NotificationEventType, eventKey: String, url: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_EVENT_KEY, eventKey)
            putExtra(
                MainActivity.EXTRA_URL,
                if (isOfflineEvent(eventType)) null else UrlPolicy.validate(url)
            )
        }

    /**
     * 从直播间地址里取房间号：只接受**纯数字**的最后一段。
     *
     * 与 UI 侧同款校验（那里也要拼 `bilibili://live/<id>`）：这个值来自本地库/接口，
     * 属于数据而不是可信输入，不允许把任意字符串拼进深链交给系统打开。
     */
    private fun roomIdFromUrl(url: String?): Long? {
        val last = url?.trim()?.trimEnd('/')?.substringAfterLast('/') ?: return null
        return last.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun buildSingle(outbox: NotificationOutboxEntity): Notification? {
        val payload = NotificationPayloads.decodeLive(outbox.payloadJson) ?: return null
        val name = payload.streamerName.ifBlank { "主播" }
        val (title, text) = when (outbox.eventType) {
            NotificationEventType.END_CONFIRMED -> "$name 已下播" to "本次直播结束"
            // 标题/分区变化（用户定稿功能，默认关闭）：渲染成"旧 → 新"
            NotificationEventType.TITLE_CHANGED -> "$name 修改了直播标题" to changeText(
                payload.previousValue, payload.roomTitle, "（原来是空的）"
            )
            NotificationEventType.AREA_CHANGED -> "$name 更换了直播分区" to changeText(
                payload.previousValue, payload.areaName, "（原来是空的）"
            )
            else -> "$name 正在直播" to listOfNotNull(
                payload.roomTitle?.takeIf { it.isNotBlank() }?.let { "$name 正在直播：$it" } ?: "$name 正在直播",
                payload.areaName
            ).joinToString("　")
        }
        val b = NotificationCompat.Builder(context, channelIdFor(outbox.eventType))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(outbox.eventType, outbox.eventKey, payload.url))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
        // 「查看记录」按钮已按用户要求移除：它太占位置，通知只保留主体点击这一个落点。
        // （原先这里留着一个 `if (… != null) { }` 空块，读起来像还有第二个动作。）
        payload.coverUrl?.let { decodeBitmap(it) }?.let { b.setLargeIcon(it) }
        return b.build()
    }

    /** 变化类通知的正文："《旧标题》→《新标题》"。 */
    private fun changeText(previous: String?, current: String?, emptyHint: String): String {
        val to = current?.takeIf { it.isNotBlank() } ?: return emptyHint
        val from = previous?.takeIf { it.isNotBlank() } ?: return "现在是：$to"
        return "$from\n→ $to"
    }

    private fun buildBatch(outbox: NotificationOutboxEntity): Notification? {
        val payload = NotificationPayloads.decodeBatch(outbox.payloadJson) ?: return null
        if (payload.items.isEmpty()) return null
        val names = payload.items.map { it.streamerName.ifBlank { "主播" } }
        val title = "${names.size} 位主播正在直播"
        // ★ 合并通知代表的是"这一批里的**全部**主播"（阈值语义见
        //   NotificationAggregationPolicy：超过阈值就把他们全部合并成一条），
        //   所以正文要尽量把名字都列出来，而不是像原先那样硬取前 5 位 ——
        //   人多时正文只剩「等」，用户根本看不出合并了谁。
        val text = com.example.bilimonitor.data.repository.NotificationTemplate
            .joinStreamerNames(names)
        val firstUrl = payload.items.firstNotNullOfOrNull { it.url }
        return NotificationCompat.Builder(context, channelIdFor(outbox.eventType))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(outbox.eventType, outbox.eventKey, firstUrl))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            // 批量通知主体点击进第一位主播的直播间（同一个优先级：客户端 → 浏览器）
            .build()
    }

    /**
     * 封禁状态变化通知（2026 复查新增）。
     *
     * 与开播/下播不同，这条通知**没有直播间可跳**（房间正被封着）：点进去落主播详情页
     * （那里显示「封禁中」标签与封禁期限），payload 不带 url，所以自然走 [appIntent] 分支。
     */
    private fun buildBan(outbox: NotificationOutboxEntity): Notification? {
        val payload = NotificationPayloads.decodeBan(outbox.payloadJson) ?: return null
        val name = payload.streamerName.ifBlank { "主播" }
        val title = if (payload.entered) "$name 的直播间被封禁" else "$name 的直播间已解封"
        val text = if (payload.entered) payload.banLabel else "已恢复正常检查间隔"
        return NotificationCompat.Builder(context, channelIdFor(outbox.eventType))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(outbox.eventType, outbox.eventKey, null))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun buildSystem(outbox: NotificationOutboxEntity): Notification? {
        val payload = NotificationPayloads.decodeSystem(outbox.payloadJson) ?: return null
        val title = if (payload.recovered) "监控已恢复" else "监控出现问题"
        return NotificationCompat.Builder(context, channelIdFor(outbox.eventType))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(payload.summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.summary))
            // ★ 系统问题通知原先**没有 contentIntent**，点了没有任何反应 —— 而 [contentIntent]
            //   的 KDoc 写着"没有直播间地址的通知（例如系统问题通知）维持原行为：点开应用内页面"。
            //   用户收到"监控出现问题"却点不进来看是什么问题，只能自己去桌面找图标。
            //   这里补上应用内跳转（url 传 null：系统问题通知本来就没有直播间地址）。
            .setContentIntent(contentIntent(outbox.eventType, outbox.eventKey, null))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
    }

    /**
     * 下载通知大图标。
     *
     * 原实现用 `URL(url).openStream()`：**无超时、无大小上限**，且在网络线程上同步执行 ——
     * 一张异常大的图或一个挂住的连接就能把投递流程卡死。
     *
     * 现在有三道限制：
     *  1. **超时收紧到 5s**（共享客户端是 15s）——投递在 Tick 互斥锁内串行执行，
     *     单条最坏 15s × 20 条会把整轮监控拖住；
     *  2. 体积上限 [MAX_ICON_BYTES]；
     *  3. **按目标尺寸采样解码**：只限压缩字节是不够的，
     *     一张 10000×10000 的纯色 PNG 压缩后可能只有几百 KB，全尺寸解码却是约 400MB（ARGB_8888）→ OOM。
     */
    private fun decodeBitmap(url: String?): android.graphics.Bitmap? {
        val safe = UrlPolicy.validateImage(url) ?: return null
        return runCatching {
            val request = okhttp3.Request.Builder().url(safe).build()
            iconClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                if (body.contentLength() > MAX_ICON_BYTES) return@use null
                val bytes = body.byteStream().use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_ICON_BYTES) return@use null
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
                // 体积超限时上面的 use 块会返回 null，这里必须有可空分支
                bytes?.let { decodeSampled(it) }
            }
        }.getOrElse { e ->
            // ★ CancellationException 必须原样重抛（本项目硬规则）：投递有 15 秒总预算
            //   （NotificationDispatcher.DISPATCH_BUDGET_MS），超时取消若在这里被 runCatching
            //   吞掉，post() 会继续往下走到 notify() —— 通知**真的发出去了**，而这一行已被
            //   记成 DELIVERY_UNKNOWN 进入重试，用户被同一条通知再提醒一次。
            if (e is CancellationException) throw e
            null
        }
    }

    /** 先读尺寸（不分配像素），再按 2 的幂次降采样到不超过 [ICON_TARGET_PX]。 */
    private fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_TARGET_PX ||
            bounds.outHeight / (sample * 2) >= ICON_TARGET_PX
        ) {
            sample *= 2
        }
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        /** 通知大图标体积上限；超过则放弃大图，不影响通知本体送达。 */
        const val MAX_ICON_BYTES = 2L * 1024 * 1024

        /** 大图标解码目标边长：超过就按 2 的幂次降采样，避免全尺寸位图 OOM。 */
        const val ICON_TARGET_PX = 512

        /** 大图标下载超时（秒）。 */
        const val ICON_TIMEOUT_SECONDS = 5L

        /** 投递后核对的第二次查询延迟：给系统服务一点入队时间，避免"假阴性"触发重发。 */
        const val ACTIVE_CHECK_RETRY_DELAY_MS = 300L

        /**
         * POST_NOTIFICATIONS 是 API 33 才引入的运行时权限；minSdk=31。
         * 在 API 31/32 上该权限未定义，checkSelfPermission 恒返回 DENIED，
         * 若直接检查会导致 Android 12/12L 上所有通知被判为 PERMISSION_DENIED 而永久失败。
         * 因此 33 以下改用 areNotificationsEnabled()。
         *
         * ★ 33 以上**两个条件都要看**（复查发现的缺陷）：权限授予之后，用户仍可在系统设置里
         *   把该应用的"所有通知"整体关掉 —— 那时 checkSelfPermission 依旧是 GRANTED，而
         *   notify() 会被系统静默丢弃。只看权限就会把"没发出去"记成 SENT：“通知历史/诊断包”
         *   显示一切正常，用户却什么都没收到。
         *
         * ★ 这个函数是**全应用唯一**的"通知可用吗"判据：设置页、健康检查、诊断包、自救援广播
         *   都调它。任何地方再自己写一份 checkSelfPermission，都会在 API 31/32 上恒为 false，
         *   表现成"设置页永久显示通知权限未授予"这类假警报（见 SettingsViewModel）。
         */
        fun hasPermission(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)

        /**
         * 事件类型 → 渠道 id。
         *
         * 为什么收敛成一个函数：投递前的"渠道是否被关掉"检查与真正构造通知时的渠道必须**同源**，
         * 两处各写一套字符串，迟早会分叉成"检查的是 A 渠道、发的是 B 渠道"。
         */
        fun channelIdFor(eventType: NotificationEventType): String = when (eventType) {
            NotificationEventType.SYSTEM_PROBLEM, NotificationEventType.SYSTEM_RECOVERED ->
                NotificationChannels.SYSTEM
            else -> NotificationChannels.LIVE
        }

        /**
         * 该渠道是否处于"能显示"的状态（`IMPORTANCE_NONE` = 用户在系统里把它关了）。
         *
         * 渠道关掉时 `notify()` 不报错也不抛异常，只是什么都不显示 —— 这是本仓库最忌讳的
         * "静默降级"，所以投递前必须先问一次，把结果如实写进投递记录。
         * 渠道查不到（还没建好、或某些 ROM 查询抛异常）时按"可用"处理：`ensureCreated` 在
         * Application.onCreate 就建了，真建不出来时通知会以默认重要性显示，不该被误判成失败。
         */
        fun isChannelEnabled(context: Context, channelId: String): Boolean {
            val channel = runCatching {
                NotificationManagerCompat.from(context).getNotificationChannel(channelId)
            }.getOrNull() ?: return true
            return channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
        }

        /**
         * 头像/封面只允许 https（248.15 HTML/图片输出安全边界）。
         *
         * 原实现连同 `http` 一起放行，与注释、也与严格版
         * `NotificationChannels.validateUrl`（仅 https + 域名白名单）不一致：
         * 明文地址虽然会被 network security config 拦掉，但"校验说允许"会误导后续维护。
         */
        fun UrlPolicy.validateImage(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return runCatching {
                val uri = Uri.parse(url.trim())
                if (uri.scheme == "https") url.trim() else null
            }.getOrNull()
        }
    }
}