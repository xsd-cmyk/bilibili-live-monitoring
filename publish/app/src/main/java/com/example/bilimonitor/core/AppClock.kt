package com.example.bilimonitor.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID

/**
 * 双时钟规则（244.13 / 0.6.24 / 0.6.27）：
 *  - wallClock：墙上时间，用于展示与跨重启比较；
 *  - elapsed：elapsedRealtime，用于同一 bootId 内的窗口/租约判定；
 *  - bootId：开机实例标识。不同 bootId 的 elapsed 不得直接比较。
 */
interface AppClock {
    /** 网络时间优先（未同步时回退本地时间），见 NetworkTimeSource。 */
    fun nowWall(): Long
    fun nowElapsed(): Long
    fun bootId(): String
    /** 设备当前时区（IANA ID，如 Asia/Shanghai）；默认取手机时区。 */
    fun currentTimeZone(): String
}

class AndroidAppClock(
    context: Context,
    private val networkTimeSource: NetworkTimeSource
) : AppClock {
    private val appContext = context.applicationContext

    override fun nowWall(): Long = networkTimeSource.adjustedNow(System.currentTimeMillis())
    override fun nowElapsed(): Long = SystemClock.elapsedRealtime()
    override fun currentTimeZone(): String = java.util.TimeZone.getDefault().id

    override fun bootId(): String {
        // 优先使用系统提供的稳定开机计数。
        val bootCount = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        if (bootCount >= 0) return "boot-$bootCount"
        return bootAnchor
    }

    // 兜底必须是**开机时刻锚点**，不能是常量字符串。
    //
    // bootId 的用途之一是把"聚合窗口 / 投递租约"限定在同一次开机内
    // （`NotificationAggregateDao.findInProgress(bootId)`、
    // `MonitoringEngine` 里按 bootId 过滤待结算窗口）。而这些窗口的截止时间用的是
    // elapsedRealtime —— 开机会归零。原先兜底返回固定的 "boot-unknown"，
    // 两次开机拿到的是同一个 bootId，于是上一轮开机遗留的窗口会被当成"本次开机的窗口"，
    // 造成跨 boot 比较 elapsed（双时钟规则明令禁止），批量通知的窗口结算时机出错。
    //
    // 开机时刻 = 当前墙上时间 - 已开机时长：同一进程内稳定，跨开机必然不同。
    //
    // ★ 必须缓存（第二轮审查发现）：原先每次调用都重算 `now - elapsed`，
    //   一旦运行期间墙上时间被 NTP 或用户调整（网络校时正是本项目的常规路径），
    //   bootId 就变了 —— 那会让"正在收集的聚合窗口"被当成上一次开机的窗口而丢弃。
    private val bootAnchor: String by lazy {
        "boot-" + (System.currentTimeMillis() - SystemClock.elapsedRealtime())
    }
}

object Ids {
    fun newId(): String = UUID.randomUUID().toString()

    /** 跨设备稳定身份前缀：streamer / session / interval / gap 等统一使用带类型前缀的 UUID。 */
    fun stableId(prefix: String): String = "$prefix-" + UUID.randomUUID().toString()

    /** transitionId 固定生成规则（0.6.16.1 第 2 条）：{streamerStableId}:{eventSequence}:{from}->{to} */
    fun transitionId(streamerStableId: String, eventSequence: Long, from: String, to: String): String =
        "$streamerStableId:$eventSequence:$from->$to"

    /** eventKey 固定生成规则（0.6.12）：单事件 evt:{eventId} / 批量 agg:{aggregateId} / 系统问题 problem:{problemKey} */
    fun eventKeyForEvent(eventId: String): String = "evt:$eventId"
    fun eventKeyForAggregate(aggregateId: String): String = "agg:$aggregateId"
    fun eventKeyForProblem(problemKey: String): String = "problem:$problemKey"

    /** problemKey = "{component}:{problemType}:{scopeKey}"，激活时追加 episodeId（0.6.12）。 */
    fun problemKey(component: String, problemType: String, scopeKey: String, episodeId: String?): String =
        if (episodeId == null) "$component:$problemType:$scopeKey"
        else "$component:$problemType:$scopeKey:$episodeId"

    fun sessionKey(sessionStableId: String): String = "LIVEsession:$sessionStableId"
}

/**
 * UI 侧的"当前墙上时间"入口（244.13 双时钟规则）。
 *
 * 为什么需要它：`relativeTime` / `formatTime` 这类纯展示函数在 Composable 里被大量调用，
 * 逐个透传 AppClock 会污染所有调用点。但它们原先用 `System.currentTimeMillis()`，
 * 绕过了网络校时 —— 用户改系统时间就会让"3 分钟前""数据过期"等判断集体失真。
 *
 * 因此由 `BiliMonitorApp` 在启动时把容器里的唯一 AppClock 注册进来，
 * 展示层统一通过 [AppClocks.nowWall] 取时间；未注册时（如 JVM 单测）回退系统时间。
 */
object AppClocks {
    @Volatile
    private var delegate: AppClock? = null

    fun register(clock: AppClock) {
        delegate = clock
    }

    /** 网络校时后的墙上时间；未注册时回退系统时间。 */
    fun nowWall(): Long = delegate?.nowWall() ?: System.currentTimeMillis()

    /** 网络校时后的本地日期（导出文件名、统计默认桶等展示用途）。 */
    fun today(): java.time.LocalDate =
        java.time.Instant.ofEpochMilli(nowWall())
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    /** 网络校时后的本地时间戳字符串。 */
    fun stamp(pattern: String): String =
        java.time.Instant.ofEpochMilli(nowWall())
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}
