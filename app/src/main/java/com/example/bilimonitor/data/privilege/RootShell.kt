package com.example.bilimonitor.data.privilege

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * root 提权通道：用 `su -c <命令>` 执行。
 *
 * ## 为什么要试多个路径
 * `su` 不一定在应用进程的 PATH 里：Magisk 24+ 把它放在 `/debug_ramdisk/su`，
 * 旧版放在 `/sbin/su`，还有 `/system/bin/su`、`/system/xbin/su` 等历史位置。
 * 只写 `Runtime.exec("su")` 在部分设备上会直接抛 IOException（"没有 su"），
 * 而用户明明是 root —— 那种"检测不到"比功能不可用更让人困惑。
 *
 * ## 首次调用会弹授权框
 * root 管理器（Magisk / KernelSU / APatch）会在第一次调用时弹窗问"是否允许"。
 * 用户点了拒绝就是 exit != 0；这正是 [ShellResult.failure] 要如实传达的东西，
 * 不能把它当成"设备没有 root"。
 */
@Singleton
class RootShell @Inject constructor() : PrivilegedShell {

    override val level: PrivilegeLevel = PrivilegeLevel.ROOT

    /** 真正跑通过（exitCode == 0）的 su 路径 —— [isAvailable] 只认它。 */
    @Volatile
    private var cachedSuPath: String? = null

    /** 只是"存在"的 su 路径（授权可能被拒），用于下次优先尝试，**不代表可用**。 */
    @Volatile
    private var suPathFound: String? = null

    /**
     * 轻量可用性判断：**只说明"本进程内曾经成功跑通过 su"**，不弹窗、不执行任何提权动作。
     *
     * ★ 冷启动后它一定是 false（缓存是内存的）：所以调用方不能拿它当"root 不可用"的依据，
     *   需要真判断时用 [probe]（会真跑一次 `su -c id`，首次可能弹授权框）。
     *   持久化的档位在 [com.example.bilimonitor.data.repository.AdvancedKeepAliveRepository]
     *   里通过 `ensureShell` 触发探测，避免"重开应用就说 root 不可用"。
     */
    override fun isAvailable(): Boolean = cachedSuPath != null

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val candidates = buildList {
            cachedSuPath?.let { add(it) }
            suPathFound?.let { add(it) }
            add("su")
            addAll(SU_PATHS)
        }.distinct()

        // ★ 整次调用的总预算（代理审查发现）：每个候选各等 20 秒的话，8 个候选最坏 160 秒，
        //   UI 会一直停在"处理中…"，root 授权框还可能被反复弹。超过预算就不再换路径。
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        var lastFailure: String? = null
        for (su in candidates) {
            if (System.currentTimeMillis() >= deadline) {
                lastFailure = (lastFailure ?: "") + "；已用完 ${TOTAL_BUDGET_MS / 1000} 秒总预算，停止尝试其它 su 路径"
                break
            }
            val result = runOnce(su, command)
            if (result.exitCode == 0) {
                cachedSuPath = su
                return@withContext result
            }
            // 命令跑起来了但返回非 0：说明这条 su 路径是**存在的**（可能是授权被拒、
            // 也可能只是这条命令本身失败）。不再试下一个路径，直接把真实结果交出去，
            // 避免把"授权被拒"报成"没有 su"。
            //
            // ★ 但**不能**因此把它记成"root 可用"（代理审查发现）：
            //   授权被拒同样是 exit != 0，而 cachedSuPath 是 isAvailable() 的唯一依据，
            //   一旦写进去，卡片会显示"Root：可用"、按钮点亮、诊断包 keepAliveRootEverWorked=true ——
            //   全是假的。这里只记"这条路径存在"（suPathFound），可用与否只认 exitCode == 0。
            if (result.failure == null) {
                suPathFound = su
                return@withContext result
            }
            lastFailure = result.failure
        }
        ShellResult(-1, "", "", lastFailure ?: "找不到可用的 su")
    }

    private fun runOnce(su: String, command: String): ShellResult = try {
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(false)
            .start()
        // 输出很小（都是几行状态文本），先等再读不会撑满管道。
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            ShellResult(-1, "", "", "命令超时（${TIMEOUT_SECONDS}s，可能弹出了授权确认框）")
        } else {
            val out = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            val err = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
            ShellResult(process.exitValue(), out, err)
        }
    } catch (e: IOException) {
        // 最常见的两种：没有 su（No such file）、SELinux 拒绝执行
        Log.i(TAG, "调用 $su 失败：${e.message}")
        ShellResult(-1, "", "", "无法执行 $su：${e.message}")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        ShellResult(-1, "", "", "被中断")
    }

    /** 探测 + 首次授权：执行 `id` 并确认输出里是 uid=0。 */
    suspend fun probe(): Pair<Boolean, String?> {
        val r = exec("id")
        if (r.ok && r.stdout.contains("uid=0")) return true to null
        return false to (r.failure ?: r.stderr.ifBlank { r.stdout }.ifBlank { "su 返回非 0" }.trim())
    }

    private companion object {
        const val TAG = "RootShell"
        const val TIMEOUT_SECONDS = 20L

        /** 一次 exec 的总时间预算：超时就不再换 su 路径（避免最坏 8×20 秒的等待）。 */
        const val TOTAL_BUDGET_MS = 45_000L

        /** 历史与现行 root 方案里 su 的常见位置。 */
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        )
    }
}
