package com.example.bilimonitor.data.privilege

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 提权通道：借 shell(adb) 身份执行命令（**不需要 root**）。
 *
 * ## 能力边界（界面文案必须与此一致）
 * Shizuku 给的是 shell 身份，所以能做 shell 能做的：Doze 白名单、待机桶、appops。
 * 它**做不到**改别的进程的 `oom_score_adj`（那需要 CAP_SYS_RESOURCE，只有 root 有）——
 * 所以"防低内存杀手"这一项只有 root 档才有，界面上也要这么标。
 *
 * ## 为什么直接调 IShizukuService 而不是 bindUserService
 * 官方推荐的用户服务（`Shizuku.bindUserService`）适合"把长期运行的逻辑放进 Shizuku 进程"，
 * 而这里只需要**跑几条一次性命令**：直接取 binder 调 `newProcess` 更轻，
 * 不引入额外的 Service + AIDL + 生命周期管理。代价是依赖 `IShizukuService` 的接口稳定性 ——
 * 因此这里用 [Shizuku.getVersion] 做了版本闸门，并在失败时如实回报而不是静默降级。
 */
@Singleton
class ShizukuShell @Inject constructor(
    @ApplicationContext private val context: Context
) : PrivilegedShell {

    override val level: PrivilegeLevel = PrivilegeLevel.SHIZUKU

    override fun isAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 应用是否已安装（依赖清单里的 `<queries>`，否则 API 30+ 永远查不到）。 */
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isAuthorized(): Boolean = runCatching {
        isRunning() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun version(): Int? = runCatching { if (isRunning()) Shizuku.getVersion() else null }.getOrNull()

    /**
     * Shizuku 服务以什么 uid 运行：**2000 = shell**（普通 Shizuku），**0 = root**（Sui / root 启动）。
     *
     * 为什么要它：uid 0 的 Shizuku 拥有 CAP_SYS_RESOURCE，因此**也能**设置别的进程的
     * `oom_score_adj` —— 那么"内存回收保护"这一项对它就应当可用，而不是写死"本档不支持"。
     */
    fun uid(): Int? = runCatching { if (isRunning()) Shizuku.getUid() else null }.getOrNull()

    /** 请求授权：Shizuku 会弹它自己的确认框，结果通过 [Shizuku.addRequestPermissionResultListener] 回来。 */
    fun requestPermission(requestCode: Int): Boolean = runCatching {
        Shizuku.requestPermission(requestCode)
        true
    }.getOrElse {
        Log.w(TAG, "请求 Shizuku 授权失败：${it.message}")
        false
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShellResult(-1, "", "", "Shizuku 未运行或未授权")
        }
        val version = version() ?: 0
        if (version < MIN_VERSION) {
            return@withContext ShellResult(
                -1, "", "", "Shizuku 版本过低（$version，需要 $MIN_VERSION 及以上）"
            )
        }
        val service = try {
            IShizukuService.Stub.asInterface(Shizuku.getBinder())
        } catch (e: Throwable) {
            return@withContext ShellResult(-1, "", "", "取 Shizuku 服务失败：${e.message}")
        } ?: return@withContext ShellResult(-1, "", "", "Shizuku 服务为空")

        val remote: IRemoteProcess = try {
            service.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Throwable) {
            return@withContext ShellResult(-1, "", "", "Shizuku 执行失败：${e.message}")
        }

        try {
            coroutineScope {
                val out = async { readAll(remote.inputStream) }
                val err = async { readAll(remote.errorStream) }
                // ★ `remote.waitFor()` 是**阻塞的 binder 调用**，协程超时打断不了它
                //   （代理审查发现）：原先直接 `withTimeoutOrNull { remote.waitFor() }` 是假超时 ——
                //   命令其实成功也会被记成"超时"并丢掉输出，真卡死时反而永远不返回。
                //   现在把它放到独立线程上等，超时后**真的** destroy 掉远端进程。
                val waiting = async(Dispatchers.IO) { runCatching { remote.waitFor() } }
                val exit = withTimeoutOrNull(TIMEOUT_MS) { waiting.await() }
                if (exit == null) {
                    runCatching { remote.destroy() }
                    return@coroutineScope ShellResult(-1, "", "", "命令超时（${TIMEOUT_MS}ms，已终止）")
                }
                exit.fold(
                    onSuccess = { ShellResult(it, out.await(), err.await()) },
                    onFailure = { ShellResult(-1, "", "", "等待命令结束失败：${it.message}") }
                )
            }
        } catch (e: Throwable) {
            runCatching { remote.destroy() }
            ShellResult(-1, "", "", "读取 Shizuku 命令结果失败：${e.message}")
        }
    }

    private fun readAll(fd: ParcelFileDescriptor): String = runCatching {
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrDefault("")

    private companion object {
        const val TAG = "ShizukuShell"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** `IShizukuService.newProcess` 从早期版本就有；这里保守要求 11+（Sui/Shizuku 的现代线）。 */
        const val MIN_VERSION = 11
        const val TIMEOUT_MS = 15_000L
    }
}
