package com.example.bilimonitor.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.AuditLogEntity
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.privilege.KeepAliveCommands
import com.example.bilimonitor.data.privilege.PrivilegeEnvironment
import com.example.bilimonitor.data.privilege.PrivilegeLevel
import com.example.bilimonitor.data.privilege.PrivilegedShell
import com.example.bilimonitor.data.privilege.RootShell
import com.example.bilimonitor.data.privilege.ShellResult
import com.example.bilimonitor.data.privilege.ShizukuShell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.advancedKeepAliveDataStore by preferencesDataStore(name = "advanced_keep_alive")

/** 高级保活的用户开关。 */
data class AdvancedKeepAliveSettings(
    /** 锁屏后仍保持 CPU 清醒（partial wakelock）。这是"锁屏继续轮询"真正起作用的那一环。 */
    val wakelockEnabled: Boolean = false,
    /** 当前选择的提权档位（NONE = 只用应用自己的能力）。 */
    val privilegeLevel: PrivilegeLevel = PrivilegeLevel.NONE
)

/** 单条优化项的**实测**状态。 */
enum class KeepAliveItemState {
    /** 已确认生效（读回来验证过，不是"命令返回 0"就算）。 */
    APPLIED,

    /** 当前未生效。 */
    NOT_APPLIED,

    /** 这一档做不到（例如 Shizuku 改不了别人的 oom_score_adj）。 */
    UNSUPPORTED,

    /** 尝试了但失败，[KeepAliveItem.detail] 是原因。 */
    FAILED
}

data class KeepAliveItem(val name: String, val detail: String, val state: KeepAliveItemState)

/**
 * 一次"应用/回退/探测"的完整结果。
 *
 * [log] 保留每条命令与它的输出：用户报"开了没用"时，这是唯一能看清到底哪一步没生效的东西，
 * 也会进诊断包。
 */
data class KeepAliveReport(
    val mode: PrivilegeLevel,
    val items: List<KeepAliveItem>,
    val log: List<String>
) {
    val applied: Int get() = items.count { it.state == KeepAliveItemState.APPLIED }
    val failed: Int get() = items.count { it.state == KeepAliveItemState.FAILED }

    /** 给通知/摘要用的一句话（如实，不承诺"绝对不被杀"）。 */
    fun summary(): String = "生效 $applied/${items.size} 项" +
        if (failed > 0) "，失败 $failed 项" else ""
}

/**
 * 高级保活（Root / Shizuku）。
 *
 * ## 它解决的问题
 * 前台服务只保证进程不被回收，**不阻止 CPU 挂起**：屏幕一关，设备进 suspend/Doze，
 * 定时器停摆，轮询就断了。真正让 CPU 不睡的是 partial wakelock（[AdvancedKeepAliveSettings.wakelockEnabled]，
 * 由 MonitoringService 持有，无需任何权限）；但 **Doze 期间系统会忽略非白名单应用的 wakelock 与网络**，
 * 所以还要把它加进 Doze 白名单、待机桶设为 ACTIVE、后台 appops 放开 ——
 * 这三件都是 shell(adb) 级操作，普通应用做不到，Shizuku 或 root 才行。
 *
 * ## 诚实边界
 * 厂商 ROM（小米/华为/OPPO/vivo 等）有自家的省电与"后台清理"策略，**这些命令管不到**，
 * 只能在界面上引导用户去系统设置里加锁。本仓库一律说"显著提高存活率"，不说"绝对不被杀"。
 */
@Singleton
class AdvancedKeepAliveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootShell: RootShell,
    private val shizukuShell: ShizukuShell,
    private val logDao: LogDao,
    private val clock: AppClock
) {
    private val keyWakelock = booleanPreferencesKey("wakelock_enabled")
    private val keyPrivilege = stringPreferencesKey("privilege_level")

    private val pkg: String get() = context.packageName

    val flow: Flow<AdvancedKeepAliveSettings> = context.advancedKeepAliveDataStore.data.map {
        AdvancedKeepAliveSettings(
            wakelockEnabled = it[keyWakelock] ?: false,
            privilegeLevel = PrivilegeLevel.parse(it[keyPrivilege])
        )
    }

    suspend fun current(): AdvancedKeepAliveSettings = flow.first()

    suspend fun setWakelockEnabled(enabled: Boolean) {
        context.advancedKeepAliveDataStore.edit { it[keyWakelock] = enabled }
    }

    suspend fun setPrivilegeLevel(level: PrivilegeLevel) {
        context.advancedKeepAliveDataStore.edit { it[keyPrivilege] = level.name }
    }

    /**
     * 当前这台设备上的「电源豁免」状态 —— **免 root 就有机会拿到的加固**，
     * 也是本模块里性价比最高的一项（见类文档里的实测证据）。
     */
    data class PowerExemption(
        /** 是否已加入电池优化白名单（= Doze 白名单）。 */
        val ignoringBatteryOptimizations: Boolean,
        /** 是否允许精确闹钟（心跳用它才能准点；不允许时退化成不精确的 setAndAllowWhileIdle）。 */
        val canScheduleExactAlarms: Boolean
    )

    /** 读取电源豁免状态（只读，不弹任何框）。 */
    fun powerExemption(): PowerExemption {
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
        return PowerExemption(
            ignoringBatteryOptimizations = runCatching {
                power?.isIgnoringBatteryOptimizations(pkg) == true
            }.getOrDefault(false),
            canScheduleExactAlarms = runCatching {
                alarm?.canScheduleExactAlarms() == true
            }.getOrDefault(false)
        )
    }

    /**
     * 「忽略电池优化」的系统对话框。
     *
     * 优先用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（一次点击直达本应用），
     * 少数 ROM 不认这个 action —— 那时退回 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
     * （打开列表页，让用户自己找到本应用）。两条都不认时返回 null，由界面如实说明"请手动去设置"。
     */
    fun batteryOptimizationIntent(): android.content.Intent? {
        val direct = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$pkg")
        )
        val resolved = runCatching {
            context.packageManager.resolveActivity(direct, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        if (resolved != null) return direct
        val list = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val listResolved = runCatching {
            context.packageManager.resolveActivity(list, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        return if (listResolved != null) list else null
    }

    /**
     * 「精确闹钟」授权页。
     *
     * 拿不到也不影响保活：心跳会退化为不精确的 `setAndAllowWhileIdle`（Doze 下仍会触发，只是可能晚些）。
     * 另外按 AOSP 的 javadoc，**在白名单（忽略电池优化）上的应用同样可以排精确闹钟** ——
     * 所以上面那个"申请忽略电池优化"其实也顺带解决了这一项。
     */
    fun exactAlarmSettingsIntent(): android.content.Intent? = runCatching {
        android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:$pkg"))
    }.getOrNull()

    /**
     * 厂商「自启动 / 后台运行」设置页。
     *
     * 厂商 ROM 有自家的后台清理策略，**系统级命令管不到**，只能引导用户去对应页面手动放行。
     * 这里按常见 ROM 的组件名依次尝试；都打不开就返回应用详情页
     * （**不假装成功**：界面会如实显示"没找到厂商设置页，已打开应用详情"）。
     */
    fun autoStartSettingsIntent(): android.content.Intent {
        val candidates = listOf(
            // 小米 / 红米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            // OPPO / 一加 / realme
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // 三星
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            // 魅族
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC"
        )
        for ((pkgName, cls) in candidates) {
            val intent = android.content.Intent().setComponent(
                android.content.ComponentName(pkgName, cls)
            )
            val resolved = runCatching {
                context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrNull()
            if (resolved != null) return intent
        }
        return android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg")
        )
    }

    /** Shizuku 授权申请要转交给 [ShizukuShell]（它管版本与可用性判断）。 */
    fun requestShizukuPermission(requestCode: Int): Boolean = shizukuShell.requestPermission(requestCode)

    /**
     * 环境探测。
     *
     * root 探测会**真的执行一次 `su -c id`** —— 首次会弹 root 授权框。
     * 这是刻意的：不实际执行就无法区分"没有 su"与"有 su 但用户没授权"，
     * 而这两种情况该给用户的提示完全不同。
     */
    suspend fun detectEnvironment(probeRoot: Boolean = true): PrivilegeEnvironment =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // ★ 整体放在 IO 上（代理审查发现）：里面几个 Shizuku 调用都是**同步 binder IPC**，
            //   在 Shizuku 服务忙/半死时会把主线程卡住（最坏 ANR），而调用方都在 Main。
            val rootResult = if (probeRoot) rootShell.probe() else (rootShell.isAvailable() to null)
            PrivilegeEnvironment(
                rootAvailable = rootResult.first,
                rootFailure = rootResult.second,
                shizukuInstalled = shizukuShell.isInstalled(),
                shizukuRunning = shizukuShell.isRunning(),
                shizukuAuthorized = shizukuShell.isAuthorized(),
                shizukuVersion = shizukuShell.version(),
                shizukuUid = shizukuShell.uid()
            )
        }

    private companion object {
        /** audit_log.detailJson 的长度上限（与项目其它写入点一致，避免撑大日志表与诊断包）。 */
        const val AUDIT_DETAIL_MAX = 800
    }

    /** 只读探测：现在实际生效了哪些（不做任何修改）。 */
    suspend fun probeStatus(mode: PrivilegeLevel): KeepAliveReport {
        if (mode == PrivilegeLevel.NONE) {
            // ★ 从来没应用过提权设置时**不要去猜 ROOT**（代理审查发现）：那会把
            //   "什么都没设过"显示成"root 不可用"这种吓人的失败，还可能真去跑一批 su 命令。
            return KeepAliveReport(
                mode,
                listOf(
                    KeepAliveItem(
                        "提权设置", "尚未应用任何提权保活设置（无需探测）",
                        KeepAliveItemState.NOT_APPLIED
                    )
                ),
                listOf("未应用任何提权设置：无需探测")
            )
        }
        val shell = shellFor(mode) ?: return KeepAliveReport(
            mode, listOf(KeepAliveItem("提权通道", unavailableReason(mode), KeepAliveItemState.FAILED)),
            listOf("提权通道不可用：${unavailableReason(mode)}")
        )
        val log = mutableListOf<String>()
        val items = mutableListOf<KeepAliveItem>()
        items += checkWhitelist(shell, log)
        items += checkStandbyBucket(shell, log)
        items += checkAppOps(shell, log)
        items += checkOomGuard(shell, log, mode)
        return KeepAliveReport(mode, items, log)
    }

    /** 应用：设置 + **逐项实测校验**（命令返回 0 不等于生效）。 */
    suspend fun apply(mode: PrivilegeLevel): KeepAliveReport {
        val shell = shellFor(mode) ?: return reportUnavailable(mode)
        val log = mutableListOf<String>()
        val items = mutableListOf<KeepAliveItem>()

        // 已经通过「忽略电池优化」豁免过的话，这条命令是多余的（效果相同）——
        // 但仍然读回一次，让报告里的状态来自**事实**而不是假设。
        if (powerExemption().ignoringBatteryOptimizations) {
            log += "[跳过] 加入 Doze 白名单 → 系统已通过「忽略电池优化」豁免（效果相同）"
        } else {
            exec(shell, KeepAliveCommands.cmdWhitelistAdd(pkg), "加入 Doze 白名单", log)
        }
        items += checkWhitelist(shell, log)

        exec(shell, KeepAliveCommands.cmdSetStandbyActive(pkg), "待机桶设为 ACTIVE", log)
        items += checkStandbyBucket(shell, log)

        for (op in KeepAliveCommands.APP_OPS) {
            exec(shell, KeepAliveCommands.cmdAppOpSet(pkg, op, "allow"), "appops $op = allow", log)
        }
        items += checkAppOps(shell, log)

        // uid 0 的 Shizuku（Sui / root 启动）同样有 CAP_SYS_RESOURCE，也能做这一项
        if (mode == PrivilegeLevel.ROOT || shizukuRunsAsRoot()) {
            exec(shell, KeepAliveCommands.cmdSetOomScoreAdj(pkg), "oom_score_adj 保护", log)
        }
        items += checkOomGuard(shell, log, mode)

        val report = KeepAliveReport(mode, items, log)
        recordReport("APPLY", report)
        if (report.applied > 0) {
            // 只有**确实有项生效**才把档位落盘（代理审查发现）：
            // 否则四项全失败时界面会显示"当前档位：Root（已应用到系统）"，与事实相反。
            setPrivilegeLevel(mode)
        } else {
            android.util.Log.w("AdvancedKeepAlive", "应用 ${mode.name} 档没有任何一项生效，不记录档位")
        }
        return report
    }

    /** 回退：把系统设置恢复成正常态。 */
    suspend fun revert(mode: PrivilegeLevel): KeepAliveReport {
        val shell = shellFor(mode) ?: return reportUnavailable(mode)
        val log = mutableListOf<String>()
        exec(shell, KeepAliveCommands.cmdWhitelistRemove(pkg), "移出 Doze 白名单", log)
        exec(shell, KeepAliveCommands.cmdSetStandbyWorkingSet(pkg), "待机桶恢复 WORKING_SET", log)
        for (op in KeepAliveCommands.APP_OPS) {
            // default = 交回系统按默认策略处理（不是 deny，避免把用户限制得更死）
            exec(shell, KeepAliveCommands.cmdAppOpSet(pkg, op, "default"), "appops $op = default", log)
        }
        // ★ root 档还要把 oom_score_adj 写回正常值（代理审查发现）：
        //   否则撤销之后本进程仍是 -1000，而应用自己"以为"已经撤销干净了。
        //   写 0 表示"不特殊干预"，之后由系统按进程状态自己算。
        if (mode == PrivilegeLevel.ROOT) {
            exec(shell, "pid=\$(pidof $pkg); [ -n \"\$pid\" ] && echo 0 > /proc/\$pid/oom_score_adj || true",
                "oom_score_adj 恢复默认（0）", log)
        }
        val items = mutableListOf<KeepAliveItem>()
        items += checkWhitelist(shell, log)
        items += checkStandbyBucket(shell, log)
        items += checkAppOps(shell, log)
        items += checkOomGuard(shell, log, mode)
        val report = KeepAliveReport(mode, items, log)
        recordReport("REVERT", report)
        // ★ 只有确认"没有项还生效"时才清掉档位（代理审查发现）：
        //   否则会出现"应用说已撤销、系统里白名单/ACTIVE/appops 仍在"，而且 level 清掉后
        //   oom 补设也不再跑，残留没人管。清不干净就保留档位并如实说明。
        val stillOn = items.count { it.state == KeepAliveItemState.APPLIED }
        if (stillOn == 0) {
            setPrivilegeLevel(PrivilegeLevel.NONE)
        } else {
            android.util.Log.w("AdvancedKeepAlive", "撤销后仍有 $stillOn 项生效，保留档位记录")
        }
        return report
    }

    /**
     * 把当前档位的提权设置**重放并校验**一次（幂等）。
     *
     * 为什么需要（代理调研）：这些系统项会"自己掉"——
     *   · 待机桶由系统按使用频率重新分级（不是设一次就永久 ACTIVE）；
     *   · 用户可能在系统设置里撤销「忽略电池优化」；
     *   · appops 在重装/清数据后回到默认；
     *   · 系统还会以"应用空闲"停掉前台服务（AOSP `ActiveServices.stopInBackgroundLocked`）。
     * 所以服务每次启动（含心跳拉起、开机、更新后）都重放一遍：掉哪项补哪项。
     * 每项本来就有读回校验，不会出现"以为设了其实没设"。
     */
    suspend fun reapplyIfNeeded(): KeepAliveReport? {
        val level = current().privilegeLevel
        if (level == PrivilegeLevel.NONE) return null
        val shell = shellFor(level) ?: run {
            android.util.Log.i("AdvancedKeepAlive", "提权通道当前不可用（${level.name}），跳过重放")
            return null
        }
        val log = mutableListOf<String>()
        val items = mutableListOf<KeepAliveItem>()

        // 只在"确实掉了"时才重放对应命令，避免每次启动都去改系统设置
        if (checkWhitelist(shell, log).state != KeepAliveItemState.APPLIED &&
            !powerExemption().ignoringBatteryOptimizations
        ) {
            exec(shell, KeepAliveCommands.cmdWhitelistAdd(pkg), "重放：加入 Doze 白名单", log)
        }
        items += checkWhitelist(shell, log)

        if (checkStandbyBucket(shell, log).state != KeepAliveItemState.APPLIED) {
            exec(shell, KeepAliveCommands.cmdSetStandbyActive(pkg), "重放：待机桶设为 ACTIVE", log)
        }
        items += checkStandbyBucket(shell, log)

        if (checkAppOps(shell, log).state != KeepAliveItemState.APPLIED) {
            for (op in KeepAliveCommands.APP_OPS) {
                exec(shell, KeepAliveCommands.cmdAppOpSet(pkg, op, "allow"), "重放：appops $op = allow", log)
            }
        }
        items += checkAppOps(shell, log)

        if (level == PrivilegeLevel.ROOT || shizukuRunsAsRoot()) {
            exec(shell, KeepAliveCommands.cmdSetOomScoreAdj(pkg), "重放：oom_score_adj 保护", log)
        }
        items += checkOomGuard(shell, log, level)

        val report = KeepAliveReport(level, items, log)
        // 只有真的做了动作（日志里有"重放"）才留痕，避免每次冷启动写一条审计
        if (log.any { it.contains("重放：") }) recordReport("REPLAY", report)
        return report
    }

    /**
     * 进程重启后补一次 oom 保护。
     *
     * 为什么需要：`oom_score_adj` 是**按进程**设置的，进程一换就没了。
     * 由前台服务每次启动时补一次最省事（root 档才做，其它档直接返回）。
     */
    suspend fun reapplyOomGuardIfRoot(): Boolean {
        if (current().privilegeLevel != PrivilegeLevel.ROOT) return false
        val shell = shellFor(PrivilegeLevel.ROOT) ?: return false
        val r = shell.exec(KeepAliveCommands.cmdSetOomScoreAdj(pkg))
        val ok = KeepAliveCommands.parseOomScoreAdj(r.stdout)
            ?.let { it <= KeepAliveCommands.OOM_SCORE_ADJ_PROTECTED } == true
        if (!ok) {
            // 补不上要留痕：否则"以为有保护、其实没有"这件事谁也发现不了
            runCatching {
                logDao.insertAppError(
                    ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = AppError.BACKGROUND_EXECUTION_RESTRICTED,
                        detail = "oom 保护补设失败：${r.failure ?: r.stderr.ifBlank { r.stdout }}".take(500)
                    )
                )
            }
        }
        return ok
    }

    // ---------------------------------------------------------------- 各项检查

    private suspend fun checkWhitelist(shell: PrivilegedShell, log: MutableList<String>): KeepAliveItem {
        val r = exec(shell, KeepAliveCommands.CMD_WHITELIST_LIST, "读取 Doze 白名单", log)
        if (!r.ok) {
            return KeepAliveItem("Doze 白名单", r.failure ?: "读取失败", KeepAliveItemState.FAILED)
        }
        val listed = KeepAliveCommands.parseWhitelist(r.stdout).contains(pkg)
        return if (listed) {
            KeepAliveItem("Doze 白名单", "已在白名单：Doze 期间仍可用网络与 wakelock", KeepAliveItemState.APPLIED)
        } else {
            KeepAliveItem("Doze 白名单", "不在白名单：Doze 期间系统会忽略 wakelock 与网络", KeepAliveItemState.NOT_APPLIED)
        }
    }

    private suspend fun checkStandbyBucket(shell: PrivilegedShell, log: MutableList<String>): KeepAliveItem {
        val r = exec(shell, KeepAliveCommands.cmdGetStandbyBucket(pkg), "读取待机桶", log)
        val bucket = KeepAliveCommands.parseStandbyBucket(r.stdout)
        return when {
            !r.ok || bucket == null ->
                KeepAliveItem("待机桶", r.failure ?: "读取失败", KeepAliveItemState.FAILED)
            KeepAliveCommands.isNotDegradedBucket(bucket) ->
                KeepAliveItem(
                    "待机桶",
                    "${KeepAliveCommands.bucketLabel(bucket)}：不会被按「不常用」降级后台配额",
                    KeepAliveItemState.APPLIED
                )
            else ->
                KeepAliveItem(
                    "待机桶",
                    "当前 ${KeepAliveCommands.bucketLabel(bucket)}（想让它不被降级需要 ACTIVE）",
                    KeepAliveItemState.NOT_APPLIED
                )
        }
    }

    private suspend fun checkAppOps(shell: PrivilegedShell, log: MutableList<String>): KeepAliveItem {
        val modes = mutableMapOf<String, String?>()
        for (op in KeepAliveCommands.APP_OPS) {
            val r = exec(shell, KeepAliveCommands.cmdGetAppOp(pkg, op), "读取 appops $op", log)
            modes[op] = if (r.ok) KeepAliveCommands.parseAppOpMode(r.stdout) else null
        }
        val bad = modes.filterValues { !KeepAliveCommands.appOpAllowed(it) }
        return if (bad.isEmpty()) {
            // 如实回显实际读到的模式（而不是笼统写"已放行"）：不同 ROM 的默认值不一样
            KeepAliveItem(
                "后台 appops",
                "均已放行（" + modes.entries.joinToString("、") { "${it.key}=${it.value}" } + "）",
                KeepAliveItemState.APPLIED
            )
        } else {
            KeepAliveItem(
                "后台 appops",
                "未放行：" + bad.keys.joinToString("、") + "（" +
                    bad.entries.joinToString("、") { "${it.key}=${it.value ?: "读取失败"}" } +
                    "；注意 foreground 只代表「仅前台」，后台仍受限）",
                KeepAliveItemState.NOT_APPLIED
            )
        }
    }

    private suspend fun checkOomGuard(
        shell: PrivilegedShell,
        log: MutableList<String>,
        mode: PrivilegeLevel
    ): KeepAliveItem {
        // ★ 不能只看档位：Shizuku 若由 root 启动（Sui / Magisk 模块）或本身跑在 uid 0，
        //   它就有 CAP_SYS_RESOURCE，同样能写 oom_score_adj（代理审查指出原来写死了"不支持"）。
        val canTouchOom = mode == PrivilegeLevel.ROOT || shizukuRunsAsRoot()
        if (!canTouchOom) {
            return KeepAliveItem(
                "内存回收保护",
                "需要 root：Shizuku 是 shell(uid 2000) 身份，改不了别的进程的 oom_score_adj" +
                    "（若用 Sui 等 root 方式运行 Shizuku，这项会自动变为可用）",
                KeepAliveItemState.UNSUPPORTED
            )
        }
        val r = exec(shell, KeepAliveCommands.cmdReadOomScoreAdj(pkg), "读取 oom_score_adj", log)
        val adj = KeepAliveCommands.parseOomScoreAdj(r.stdout)
        return when {
            adj == null -> KeepAliveItem("内存回收保护", "读不到（进程不存在或读取被拒）", KeepAliveItemState.FAILED)
            adj <= KeepAliveCommands.OOM_SCORE_ADJ_PROTECTED ->
                KeepAliveItem("内存回收保护", "oom_score_adj = $adj（低内存时不会被回收）", KeepAliveItemState.APPLIED)
            else ->
                KeepAliveItem("内存回收保护", "当前 oom_score_adj = $adj，未受保护", KeepAliveItemState.NOT_APPLIED)
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 取该档位的提权通道；**拿不到就返回 null**（调用方如实回报，不会假装成功）。
     *
     * ★ ROOT 档必须"缓存没有就真探测"（代理审查发现）：`RootShell.isAvailable()` 是**内存**缓存，
     *   进程重启后必然为 false —— 于是持久化过 ROOT 档的用户一重开应用就被告知"root 不可用"，
     *   「查看生效状态」「撤销」全部空转，`reapplyOomGuardIfRoot()` 也会在唯一需要它的时刻静默早退。
     *   探测会真跑一次 `su -c id`（首次可能弹授权框），但只在用户主动操作/服务启动时发生，
     *   而且 root 管理器会记住授权，代价可接受。
     */
    /**
     * 用 root 身份**代启动**监控服务（前台服务后台启动被系统拒绝时的最后一条路）。
     *
     * @return 命令是否成功发出（**不代表服务一定起来了**：服务自身的启动校验仍然生效）
     */
    suspend fun startServiceAsRoot(): Boolean {
        if (current().privilegeLevel != PrivilegeLevel.ROOT) return false
        val shell = shellFor(PrivilegeLevel.ROOT) ?: return false
        val component = "$pkg/com.example.bilimonitor.background.MonitoringService"
        val r = shell.exec("am start-foreground-service -n $component")
        val ok = r.ok && !r.stdout.contains("Error") && !r.stderr.contains("Error")
        if (!ok) {
            android.util.Log.w(
                "AdvancedKeepAlive",
                "root 代启动失败：exit=${r.exitCode} ${r.failure ?: r.stderr.ifBlank { r.stdout }}".take(300)
            )
        }
        return ok
    }

    /** Shizuku 服务是否以 uid 0 运行（Sui / root 启动的 Shizuku）——决定能否做 oom 保护。 */
    fun shizukuRunsAsRoot(): Boolean = runCatching {
        shizukuShell.isAvailable() && shizukuShell.uid() == 0
    }.getOrDefault(false)

    private suspend fun shellFor(mode: PrivilegeLevel): PrivilegedShell? = when (mode) {
        PrivilegeLevel.ROOT -> {
            if (rootShell.isAvailable()) {
                rootShell
            } else {
                val (ok, reason) = rootShell.probe()
                if (ok) rootShell else {
                    android.util.Log.i("AdvancedKeepAlive", "root 探测未通过：$reason")
                    null
                }
            }
        }
        PrivilegeLevel.SHIZUKU -> shizukuShell.takeIf { it.isAvailable() }
        PrivilegeLevel.NONE -> null
    }

    private fun unavailableReason(mode: PrivilegeLevel): String = when (mode) {
        PrivilegeLevel.ROOT -> "root 不可用：没找到可用的 su，或授权被拒绝"
        PrivilegeLevel.SHIZUKU -> "Shizuku 不可用：未安装、未运行或未授权"
        PrivilegeLevel.NONE -> "未选择提权方式"
    }

    private fun reportUnavailable(mode: PrivilegeLevel): KeepAliveReport = KeepAliveReport(
        mode,
        listOf(KeepAliveItem("提权通道", unavailableReason(mode), KeepAliveItemState.FAILED)),
        listOf("提权通道不可用：${unavailableReason(mode)}")
    )

    private suspend fun exec(
        shell: PrivilegedShell,
        command: String,
        label: String,
        log: MutableList<String>
    ): ShellResult {
        val r = shell.exec(command)
        log += buildString {
            append('[').append(if (r.ok) "OK" else "!!").append("] ").append(label)
            append(" → exit=").append(r.exitCode)
            r.failure?.let { append(" 失败=").append(it) }
            val out = r.stdout.trim().lines().take(3).joinToString(" / ")
            if (out.isNotEmpty()) append(" 输出=").append(out)
        }
        return r
    }

    /**
     * 记录一次应用/回退的结果。
     *
     * ★ **成功也是"发生过什么"，写审计表而不是错误表**：把"一切正常"写进
     * `application_error_log` 会淹掉真正的故障（项目自己的规矩，见 MonitoringEngine 的同型教训）。
     * 只有**确实有项失败**时才额外写一条错误日志 —— 那种情况才需要排查。
     *
     * `action` 用英文码（APPLY / REVERT），中文说明放在 detailJson 里：
     * `audit_log` 的 action 是要按前缀检索的标识，不是展示文案。
     */
    private suspend fun recordReport(action: String, report: KeepAliveReport) {
        val itemsText = report.items.joinToString(",") { "${it.name}=${it.state.name}" }
        val detail = buildString {
            append("{\"mode\":\"").append(report.mode.name)
            append("\",\"applied\":").append(report.applied)
            append(",\"total\":").append(report.items.size)
            append(",\"failed\":").append(report.failed)
            append(",\"items\":\"").append(itemsText.replace("\"", "'"))
            append("\"}")
        }
        runCatching {
            logDao.insertAudit(
                AuditLogEntity(
                    auditId = Ids.newId(),
                    // (operationId, action) 上有唯一索引：每条各生成一个
                    operationId = Ids.newId(),
                    actor = "USER",
                    action = "KEEP_ALIVE_$action",
                    targetType = "app",
                    targetStableId = pkg,
                    occurredAt = clock.nowWall(),
                    detailJson = detail.take(AUDIT_DETAIL_MAX)
                )
            )
        }
        if (report.failed > 0) {
            // 有项失败 = 需要排查：这一条才配进错误表（用 BACKGROUND_EXECUTION_RESTRICTED，
            // 不新增枚举值，避免牵动 DDL CHECK 与既有映射）
            runCatching {
                logDao.insertAppError(
                    ApplicationErrorLogEntity(
                        errorId = Ids.newId(), operationId = null, occurredAt = clock.nowWall(),
                        errorCode = AppError.BACKGROUND_EXECUTION_RESTRICTED,
                        detail = ("高级保活$action 有 ${report.failed} 项失败（${report.mode.name}）：" +
                            report.items.filter { it.state == KeepAliveItemState.FAILED }
                                .joinToString("；") { "${it.name}：${it.detail}" }).take(500)
                    )
                )
            }
        }
    }
}
