package com.example.bilimonitor.data.privilege

/**
 * 一次提权命令的执行结果。
 *
 * `exitCode = -1` 专门表示**命令根本没跑起来**（没有 su、被拒绝授权、超时），
 * 与"跑起来了但返回非 0"区分开 —— 前者要提示用户去处理环境，后者是命令本身失败。
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** 人类可读的失败原因（成功为 null）。 */
    val failure: String? = null
) {
    val ok: Boolean get() = exitCode == 0 && failure == null
}

/** 可用的提权等级，按能力从弱到强。 */
enum class PrivilegeLevel {
    /** 只能做应用自己做得到的事（wakelock、前台服务）。 */
    NONE,

    /** Shizuku：借 shell(adb) 身份 —— 能做 Doze 白名单 / 待机桶 / appops。 */
    SHIZUKU,

    /** root：shell 的全部 + 能改别人进程的 oom_score_adj（防低内存杀手）。 */
    ROOT;

    companion object {
        fun parse(raw: String?): PrivilegeLevel =
            entries.firstOrNull { it.name == raw } ?: NONE
    }
}

/** 环境探测结果（用于界面如实显示"你现在能用哪一档"）。 */
data class PrivilegeEnvironment(
    val rootAvailable: Boolean,
    /** root 不可用时的原因（例如"未授权/没有 su"）。 */
    val rootFailure: String? = null,
    /** Shizuku 应用是否已安装。 */
    val shizukuInstalled: Boolean = false,
    /** Shizuku 服务是否在运行（binder 可达）。 */
    val shizukuRunning: Boolean = false,
    /** 本应用是否已获得 Shizuku 授权。 */
    val shizukuAuthorized: Boolean = false,
    /** Shizuku 服务版本（用于判断 API 兼容性）。 */
    val shizukuVersion: Int? = null,
    /** Shizuku 服务运行身份：2000 = shell，0 = root（Sui / root 启动的 Shizuku）。 */
    val shizukuUid: Int? = null
) {
    val rootUsable: Boolean get() = rootAvailable
    val shizukuUsable: Boolean get() = shizukuRunning && shizukuAuthorized
}

/** 提权通道：以 root 或 shell 身份执行一条 shell 命令。 */
interface PrivilegedShell {

    val level: PrivilegeLevel

    /** 该通道当前是否可用（不做任何会弹窗的动作）。 */
    fun isAvailable(): Boolean

    /** 执行一条命令；不抛异常，失败信息放进 [ShellResult]。 */
    suspend fun exec(command: String): ShellResult
}
