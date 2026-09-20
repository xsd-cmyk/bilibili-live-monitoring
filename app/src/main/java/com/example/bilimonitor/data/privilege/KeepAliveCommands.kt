package com.example.bilimonitor.data.privilege

/**
 * 高级保活用的**命令与解析**（纯逻辑，不碰 Android API，所以能被 JVM 单测钉死）。
 *
 * ## 这一层到底在做什么
 * 「锁屏之后也要一直活跃」在 Android 上要同时满足两件事：
 *
 *  1. **CPU 不能睡**：前台服务只保证进程不被回收，**不阻止 CPU 挂起** ——
 *     屏幕一关，设备进入 suspend/Doze，定时器停摆，监控轮询自然就断了。
 *     真正让 CPU 不睡的是 partial wakelock（无需任何权限，见 MonitoringService）。
 *  2. **系统得认这个 wakelock**：Doze 期间系统会忽略非白名单应用的 wakelock 与网络。
 *     把它放进 Doze 白名单、待机桶设为 ACTIVE、后台 appops 放开，wakelock 才真正有效。
 *
 * 第 2 步全部是 shell(adb) 级操作 —— 普通应用做不到，**Shizuku 或 root** 才能做。
 * 这个文件只负责"发什么命令、怎么读结果"，执行通道见 [RootShell] / [ShizukuShell]。
 *
 * ## 边界（必须如实告诉用户，不许承诺做不到的事）
 * 这些都是**系统级**设置，管不到厂商 ROM 自家的省电策略（小米/华为/OPPO/vivo 等
 * 会用自己的机制杀后台）。所以本模块的措辞一律是"显著提高存活率"，不是"绝对不被杀"。
 */
object KeepAliveCommands {

    /** Doze 白名单里的包名列表（`cmd deviceidle whitelist` 的输出是逗号分隔的名字）。 */
    const val CMD_WHITELIST_LIST = "cmd deviceidle whitelist"

    /** 待机桶查询：输出形如 `10`（ACTIVE=10 / WORKING_SET=20 / FREQUENT=30 / RARE=40 / RESTRICTED=45 / NEVER=50）。 */
    fun cmdGetStandbyBucket(pkg: String) = "am get-standby-bucket $pkg"

    /** appops 查询：`cmd appops get <pkg> <op>` 输出形如 `RUN_IN_BACKGROUND: allow`。 */
    fun cmdGetAppOp(pkg: String, op: String) = "cmd appops get $pkg $op"

    /** 当前进程的 oom_score_adj（负数越小越不容易被 LMK 回收）。 */
    fun cmdReadOomScoreAdj(pkg: String) =
        "cat /proc/\$(pidof $pkg)/oom_score_adj 2>/dev/null || echo missing"

    /** 加入 Doze 白名单。加号是"添加"，见 AOSP `cmd deviceidle` 的用法。 */
    fun cmdWhitelistAdd(pkg: String) = "cmd deviceidle whitelist +$pkg"

    /** 移出 Doze 白名单。 */
    fun cmdWhitelistRemove(pkg: String) = "cmd deviceidle whitelist -$pkg"

    /** 待机桶设为 ACTIVE：系统不会再因为"不常用"而降级它的后台配额。 */
    fun cmdSetStandbyActive(pkg: String) = "am set-standby-bucket $pkg active"

    /** 回退：设回 WORKING_SET（系统的正常态，之后由系统自己按使用频率重新分级）。 */
    fun cmdSetStandbyWorkingSet(pkg: String) = "am set-standby-bucket $pkg working_set"

    fun cmdAppOpSet(pkg: String, op: String, mode: String) = "cmd appops set $pkg $op $mode"

    /** root 专用：把本进程的 oom_score_adj 压到最低，让低内存杀手不再回收它。 */
    fun cmdSetOomScoreAdj(pkg: String) =
        "pid=\$(pidof $pkg); [ -n \"\$pid\" ] && echo -1000 > /proc/\$pid/oom_score_adj && cat /proc/\$pid/oom_score_adj"

    /** 需要放开的 appops（都是"后台运行/唤醒"这一类）。 */
    val APP_OPS = listOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "WAKE_LOCK")

    /**
     * "不会被按不常用降级"的两个取值。
     *
     * ★ 这个 CLI 有**两套常量尺度**，而且实测无法区分它当前用的是哪一套：
     *   · 内部（AppStandbyController）：ACTIVE=**5**、WORKING_SET=10、FREQUENT=20、RARE=30…
     *   · 公开（UsageStatsManager）：EXEMPTED=**5**、ACTIVE=10、WORKING_SET=20、FREQUENT=30…
     *   实测（Android 15 模拟器）：`am get-standby-bucket <包名>` 读回 **5**，而同一时刻
     *   `am get-standby-bucket`（整表 dump）对同一个应用读回 **10**；**移出 Doze 白名单后
     *   单包名仍读回 5**，所以 5 也不是"白名单态 EXEMPTED"。
     *   结论：光看数字**无法**确定它叫 ACTIVE 还是 EXEMPTED —— 那就不要硬报名字（那是撒谎），
     *   只报"属于哪一类"：5/10 = 不会被按不常用降级；20 及以上 = 会被降级（数字越大越差）。
     */
    const val BUCKET_NOT_DEGRADED_A = 5
    const val BUCKET_NOT_DEGRADED_B = 10

    /** 桶值 → 如实描述（**不猜名字**，只说"会不会被降级"这一能确定的事实）。 */
    fun bucketLabel(bucket: Int?): String = when (bucket) {
        null -> "读取失败"
        5, 10 -> "$bucket（不会被按「不常用」降级；该 CLI 在单包名与整表两种读法下用的是" +
            "两套常量，仅凭数字无法确定叫 ACTIVE 还是 EXEMPTED）"
        20 -> "20（WORKING_SET：会被降级）"
        30, 35, 40, 45, 50 -> "$bucket（更差的档位，后台配额更少）"
        else -> "$bucket（未知档位）"
    }

    /** 是否处于"不会被按不常用降级"的状态（5 与 10 都算，见上面的尺度说明）。 */
    fun isNotDegradedBucket(bucket: Int?): Boolean =
        bucket != null && (bucket == BUCKET_NOT_DEGRADED_A || bucket == BUCKET_NOT_DEGRADED_B)

    /** 期望的 oom_score_adj 目标值；读到 <= 该值即视为已保护。 */
    const val OOM_SCORE_ADJ_PROTECTED = -1000

    /**
     * 解析 `cmd deviceidle whitelist` 的输出。
     *
     * 实测（API 35）每行是 `system-excidle,<包名>,<uid>` 这样的**三元组**，多行堆叠：
     * ```
     * system-excidle,com.android.shell,2000
     * user,com.example.bilimonitor,10153
     * ```
     * 第一段是白名单类型（system / system-excidle / user），第三段是 uid。
     * 因此按行取**中间那段**，而不是把所有 token 都收进集合 —— 后者会把 uid 与类型串
     * 也当成包名（单测里正是这一条先失败，才改成现在这样）。纯数字的 token 一律不认。
     */
    fun parseWhitelist(output: String): Set<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val candidate = when {
                    parts.isEmpty() -> null
                    parts.size >= 3 -> parts[1] // <类型>,<包名>,<uid>
                    parts.size == 2 -> parts[1] // <类型>,<包名>
                    else -> parts[0]            // 整行一个包名
                }
                candidate?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
            }
            .toSet()

    /** 解析 `am get-standby-bucket` 的输出（可能夹着别的文字，取第一个整数）。 */
    fun parseStandbyBucket(output: String): Int? =
        Regex("""\b(\d{1,3})\b""").find(output)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 解析 `cmd appops get <pkg> <op>` 的输出。
     *
     * 形如 `RUN_IN_BACKGROUND: allow`（也可能写作 `RUN_IN_BACKGROUND: ignore`）。
     * 返回冒号后面的模式串（小写）；解析不出来返回 null。
     */
    fun parseAppOpMode(output: String): String? {
        val m = Regex(""":\s*([A-Za-z_]+)""").find(output) ?: return null
        return m.groupValues[1].lowercase()
    }

    /** 解析 oom_score_adj：返回整数；`missing`（进程不存在）返回 null。 */
    fun parseOomScoreAdj(output: String): Int? =
        output.trim().lineSequence().firstOrNull { it.trim().isNotEmpty() }
            ?.trim()?.toIntOrNull()

    /**
     * 该 appop 是否**真的**放行。
     *
     * ★ 只认 `allow`（代理审查指出）：`foreground` 的语义是"仅前台可用"，
     * 对我们这三个后台类 op（RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND / WAKE_LOCK）
     * 恰恰等于"后台没放行"，把它判成放行会得出与 op 语义相反的结论。
     */
    fun appOpAllowed(mode: String?): Boolean = mode == "allow"
}
