package com.example.bilimonitor.core

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络时间源（用户定稿）：
 *  - 优先使用网络时间：每次成功收到服务器响应时，从 Date 头校准"服务器时间 - 设备单调时钟"偏移量；
 *  - nowWall() = 设备单调时钟 + 偏移量（不受用户手动改系统时间影响，且单调无跳变）；
 *  - 从未同步过（如离线启动）时回退使用本地系统时间。
 * 偏移量仅存内存：进程重启后会在第一次网络请求时自动重新校准。
 */
@Singleton
class NetworkTimeSource @Inject constructor() {

    /** Long.MIN_VALUE 作为"从未同步"哨兵值（elapsedRealtime 永远为正，不会冲突）。 */
    private val offsetMillis = AtomicLong(Long.MIN_VALUE)

    /** 把服务器 Date 头换算成毫秒并校准偏移。由 OkHttp 拦截器在每次响应时调用。 */
    fun onServerDate(serverMillis: Long) {
        if (serverMillis <= 0) return
        offsetMillis.set(serverMillis - SystemClock.elapsedRealtime())
    }

    fun hasSync(): Boolean = offsetMillis.get() != Long.MIN_VALUE

    /** 网络时间（毫秒）；未同步过时返回 fallback（本地墙上时间）。 */
    fun adjustedNow(fallbackWall: Long): Long {
        val offset = offsetMillis.get()
        return if (offset == Long.MIN_VALUE) fallbackWall else SystemClock.elapsedRealtime() + offset
    }
}
