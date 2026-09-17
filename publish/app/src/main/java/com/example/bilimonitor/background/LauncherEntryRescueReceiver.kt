package com.example.bilimonitor.background

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.bilimonitor.MainActivity
import com.example.bilimonitor.R
import com.example.bilimonitor.core.AppClock
import com.example.bilimonitor.core.Ids
import com.example.bilimonitor.data.local.AppError
import com.example.bilimonitor.data.local.MonitorHealthStatus
import com.example.bilimonitor.data.local.dao.LogDao
import com.example.bilimonitor.data.local.entity.ApplicationErrorLogEntity
import com.example.bilimonitor.data.local.entity.HealthEventEntity
import com.example.bilimonitor.data.repository.AppearanceSettingsRepository
import com.example.bilimonitor.notify.NotificationChannels
import com.example.bilimonitor.notify.NotificationPoster
import com.example.bilimonitor.ui.settings.IconPresetSwitcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 更新后的「桌面入口自救援」：收到 `android.intent.action.MY_PACKAGE_REPLACED` 时，
 * 若**本包一个启用的桌面入口都没有**，就把用户上次选的组合（或默认入口 `MainActivity`）恢复回来，
 * 并如实通知用户。
 *
 * ## 它修的是什么：自救援是唯一的出路
 *
 * 图标/名称预设靠 `<activity-alias>` 切换（见 [IconPresetSwitcher] 文件头），而
 * **组件的启用状态跨 APK 更新持久化**（`dumpsys package` 的 disabledComponents 就是它）。
 * 于是有这么一条真实发生过的故障链：
 *
 *  1. 用户切到某个别名 ⇒ `MainActivity` 被显式禁用（状态落进系统的 packages 记录里）；
 *  2. 项目随后**改了别名命名**（单轴 `IconPresetN` → 双轴 `IconPreset_i_j`）⇒ 旧别名组件在
 *     新包里不存在，它们的"已启用"覆盖状态随之消失；
 *  3. 重装 APK ⇒ `MainActivity` 的"已禁用"被保留，而别名没了；
 *  4. 结果：`queryIntentActivities(MAIN/LAUNCHER)` 对本包返回 0 条 —— 桌面无图标、
 *     点旧的显示"应用不可用"，**而且应用自己启动不了**，只能 `adb root` + `pm enable`
 *     或卸载重装（丢数据）。
 *
 * 第 4 步把"应用内自救"整条路堵死了：能执行代码的组件全都不可达。唯一还能碰到本应用代码的
 * 时机就是系统在"本包被替换"后发的这条广播 —— 这就是本类存在的全部理由。
 *
 * ## 三条纪律
 *
 *  1. **不信任广播本身**：广播只是"叫我来看一眼"，要不要动手完全由 [rescue] 里的门禁决定 ——
 *     只有 PackageManager 明确回答"本包此刻一个启用的 launcher 入口都没有"才会动作。
 *     拿不到答案（查询抛异常 / 系统没返回 launcher 查询结果）时**什么都不做**：
 *     "系统没告诉我"和"系统说没有"是两回事，把后者当前者会误改用户正在用的入口。
 *  2. **不碰 UI**：`onReceive` 里只有 action 判断 + `goAsync()`，其余全在 IO 线程上做
 *     （读 DataStore、查 PackageManager、写库、发通知），并带**硬超时**（`onReceive` 的窗口约 10 秒）。
 *  3. **不静默**：无论成功、部分成功还是失败都发通知 + 落一条诊断记录（见 [recordAppError]）；
 *     连"通知没发出去"本身也要写进去。
 *
 * ## 为什么放在 `background/`
 *
 * 它是一个**清单里声明的后台组件**，和 `BootReceiver` 是同一类东西（都由系统广播唤醒、
 * 都可能在没有 UI 的进程里跑），所以放 `background/` 与邻居一致；`notify/` 那边是"通知怎么渲染与
 * 投递"（[com.example.bilimonitor.notify.NotificationPoster] 等），而这里的主体是
 * PackageManager 的组件启用状态，通知只是它的一个出口（不是全部）。
 *
 * ## 与界面那份逻辑的关系（刻意复用，不另写一套）
 *
 * "现在到底启用的是哪一个"只有一份实现：[IconPresetSwitcher.readActivation]（唯一事实来源是
 * PackageManager）。本类直接复用它，包括它对"launcher 查询失败"与"确实一个都没有"的区分 ——
 * 那是本类唯一敢不敢动手的依据，抄一份到这边迟早会与界面口径漂移。
 */
@AndroidEntryPoint
class LauncherEntryRescueReceiver : BroadcastReceiver() {

    /**
     * 用户**上次成功切换**到的那个组合格子（`i to j`）。
     *
     * ★ 只用于救援时的"恢复上次组合"，**不是**"当前使用项"的来源（界面显示仍然只信
     * `PackageManager`，见 [AppearanceSettingsRepository.setIconPresetChoiceCell] 的注释）。
     */
    @Inject lateinit var appearanceRepository: AppearanceSettingsRepository

    /** 诊断记录（原规范 209 的 software error 通道 + 健康事件）。 */
    @Inject lateinit var logDao: LogDao

    /** 墙上时间统一走网络校时过的时钟（项目规则：不许直接读系统时间）。 */
    @Inject lateinit var clock: AppClock

    override fun onReceive(context: Context, intent: Intent) {
        // 只认这一条广播：显式比对 action，而不是"清单里配了就假定是它"。
        // 清单里这个接收器是 exported="false" 的（理由见 AndroidManifest.xml 的说明），
        // 但即便如此也不把广播当证据 —— 真正决定要不要动手的是下面的门禁。
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // onReceive 的执行窗口约 10 秒，而这里要做 DataStore 读、PackageManager 查询、写库、发通知，
        // 全是 IO：用 goAsync() 把它挪到 IO 线程，并加一个**硬超时**（超时就放弃并留痕）。
        // ★ 绝不在主线程上同步做这些事，也绝不在这条路径上碰任何 UI。
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val finished = withTimeoutOrNull(RESCUE_TIMEOUT_MS) { rescue(appContext) }
                if (finished == null) {
                    Log.w(TAG, "自救援超时（${RESCUE_TIMEOUT_MS}ms）已放弃；本次不保证任何改动，下次更新会再试")
                }
            } catch (t: Throwable) {
                // 绝不把异常抛回系统：这次的用户价值是"尽力把入口修回来"，不是让系统看到一次崩溃。
                // （兜底抓 Throwable 而不是 Exception：Hilt 注入失败会抛 Error 系，同样不能炸到系统。）
                Log.e(TAG, "自救援执行失败：${t.message}", t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    /**
     * 自救援的全部逻辑。**任何一条 return 都必须是"有意的决定"**（要么什么都不做，
     * 要么留下记录），没有"悄悄跳过"的分支。
     */
    private suspend fun rescue(context: Context) {
        val manifest = IconPresetSwitcher.parseManifest(context.resources, context.packageName)
        val defaultEntry = ComponentName(context, MainActivity::class.java)

        // ---------------- 门禁：本包现在到底有没有"活着的"桌面入口 ----------------
        //
        // 判据只有一份，且来自 PackageManager（[IconPresetSwitcher.readActivation]）：
        //  · enabledClassNames 空 = 已知宇宙（主入口 + 组合表里的全部别名）没有一个启用，
        //    并且系统自己的 MAIN/LAUNCHER 查询也没有返回任何本包组件；
        //  · launcherQueryFailed = 那次系统查询**失败**（"系统没告诉我"），不是"一个都没有"。
        val activation = runCatching { IconPresetSwitcher.readActivation(context, manifest) }.getOrNull()
        if (activation == null) {
            Log.w(TAG, "自救援放弃：读取组件启用状态时抛异常")
            recordAppError(
                "收到「本包被替换」广播，但读取本包组件的启用状态时抛异常，无法判定是否需要救援，" +
                    "本次未改动任何桌面入口。"
            )
            return
        }
        if (activation.launcherQueryFailed) {
            Log.w(TAG, "自救援放弃：系统没有返回本包 launcher 入口的查询结果")
            recordAppError(
                "收到「本包被替换」广播，但系统未返回本包 launcher 入口的查询结果，" +
                    "无法确认是否真的一个入口都没有，本次未改动任何桌面入口。"
            )
            return
        }
        if (activation.enabledClassNames.isNotEmpty()) {
            // ★ 需求第 4 条：**有**启用的入口就什么都不做 —— 不启用、不禁用、不通知、不落库，
            //   只在 logcat 留一行（更新后正常路径天天走这里，写库会把诊断页刷爆）。
            Log.i(
                TAG,
                "本包现有 ${activation.enabledClassNames.size} 个启用的桌面入口，无需救援"
            )
            return
        }

        // ---------------- 决定恢复哪一个入口 ----------------
        //
        // 优先级 1：用户上次选的那一格。★ 只有它**在当前的 preset_alias_matrix 里仍然存在**时才用：
        //           别名命名改过（单轴 -> 双轴）或图标/名字被删过，旧的组合在安装包里就已经没有了，
        //           那种情况下 IconPresetManifest.componentFor() 返回 null，正好用来判它。
        // 优先级 2：默认入口 MainActivity —— 组合表里 (0,0) 就是它，一定存在，是最稳的退路。
        //           老安装盘上没有这个键（读不到 -> null）时也走这一条：这是合理的降级，不崩。
        val rememberedCell = runCatching { appearanceRepository.iconPresetChoiceCell() }.getOrNull()
        val rememberedComponent = rememberedCell?.let { manifest.componentFor(it) }
        val target = rememberedComponent ?: defaultEntry
        val restoredLabel = if (
            rememberedCell != null && rememberedComponent != null && rememberedComponent != defaultEntry
        ) {
            manifest.axisLabel(rememberedCell)
        } else {
            null
        }
        val targetReason = when {
            rememberedCell == null ->
                "盘上没有「上次选择的组合」记录（老安装或从未切换过），用默认入口 MainActivity"
            rememberedComponent == null ->
                "上次记录的组合 ${rememberedCell?.first}×${rememberedCell?.second} 在当前组合表里已不存在" +
                    "（别名命名改过或该图标/名字已被删除），回落到默认入口 MainActivity"
            restoredLabel == null ->
                "用户上次选的就是默认入口 MainActivity"
            else ->
                "用户上次选的组合「${restoredLabel}」在当前组合表里仍然存在，恢复它"
        }
        Log.i(TAG, "自救援开始：${targetReason}")

        // ---------------- 启用 + 读回校验 ----------------
        val outcome = enableAndVerify(context, target)

        val title: String
        val text: String
        when (outcome) {
            is RescueOutcome.Restored -> {
                // 说明"恢复了什么"时不许含糊：恢复的是用户上次的组合就不能说成"恢复为默认"。
                val baseText = if (restoredLabel != null) {
                    "应用图标预设已恢复为上次选择的「${restoredLabel}」（更新后检测到没有任何桌面入口，已自动修复）"
                } else {
                    RESTORED_DEFAULT_TEXT
                }
                title = "应用图标已自动恢复"
                text = if (outcome.visibleEntryCount == 0) {
                    // 组件读回说启用了，系统却仍然报告零个桌面入口 —— 不敢替它宣布"桌面上有图标了"。
                    "${baseText}；但系统仍未报告任何桌面入口，请回到桌面确认 —— " +
                        "若桌面上仍然没有图标，请重新安装本应用。"
                } else {
                    "${baseText}。${REOPEN_HINT}"
                }
            }
            is RescueOutcome.Failed -> {
                title = "应用图标自动恢复未生效"
                text = "更新后检测到没有任何桌面入口，但自动恢复没有生效（${outcome.reason}）。" +
                    "请重新安装本应用，或到系统设置的「应用 → 本应用」里启用它的桌面入口。"
            }
        }

        val notificationPosted = postNotification(context, title, text)
        val outcomeText = when (outcome) {
            is RescueOutcome.Restored ->
                "已启用 ${target.flattenToShortString()}（${targetReason}）；" +
                    "恢复后系统报告的桌面入口数：" +
                    (outcome.visibleEntryCount?.toString() ?: "查询失败（无法证伪）")
            is RescueOutcome.Failed -> "恢复失败：${outcome.reason}"
        }
        val notifyText = if (notificationPosted) {
            "已发通知告知用户"
        } else {
            "★ 通知未能发出（通知权限被关闭或被系统限制），用户不会看到任何提示"
        }
        val detail = "更新后检测到本包没有任何启用的桌面入口（桌面图标消失、应用无法从桌面启动），" +
            "已执行自救援：${outcomeText}；${notifyText}。"
        recordAppError(detail)
        recordHealthEvent(
            from = MonitorHealthStatus.BLOCKED,
            // 恢复成功 -> 恢复中；失败 -> 仍是阻塞（这条记录的意义正是"试过了、还是没通"）。
            to = if (outcome is RescueOutcome.Restored) MonitorHealthStatus.RECOVERING
            else MonitorHealthStatus.BLOCKED
        )
        when (outcome) {
            is RescueOutcome.Restored -> Log.i(TAG, "自救援完成：${outcomeText}")
            is RescueOutcome.Failed -> Log.e(TAG, "自救援失败：${outcomeText}")
        }
    }

    /**
     * 启用 [target] 并**读回校验**（只调一次就宣布成功是不算数的）。
     *
     * 为什么不用 [IconPresetSwitcher.applySwitch]：那个函数的语义是"启用一个、禁用其余"，
     * 而救援只需要"把一个入口打开"。走到这里时门禁已经确认**没有任何入口是启用的**，
     * 所以再去禁用别的组件既没有意义，也违反"不要顺手动别的组件"这条要求；
     * 真正需要的那道读回校验（DEFAULT 回落到清单默认值）在 [isEnabledNow] 里单独实现。
     */
    private fun enableAndVerify(context: Context, target: ComponentName): RescueOutcome {
        val pm = context.packageManager

        // DONT_KILL_APP：这次恢复发生在进程刚被系统拉起来处理广播的时刻，没有理由再杀一次自己。
        val failure = runCatching {
            pm.setComponentEnabledSetting(
                target,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }.exceptionOrNull()
        if (failure != null) {
            return RescueOutcome.Failed(
                "系统拒绝了启用调用（${target.flattenToShortString()}）：" +
                    failure.javaClass.simpleName +
                    (failure.message?.let { "（${it}）" } ?: "")
            )
        }
        if (!isEnabledNow(pm, target)) {
            return RescueOutcome.Failed(
                "启用调用没有报错，但读回 ${target.flattenToShortString()} 仍是未启用"
            )
        }
        // 桌面级复核：组件说启用了，系统是不是也把它当成一个桌面入口了？
        // 查询失败（null）**不**据此判失败：拿不到答案不等于答案是"没有"。
        val visible = queryEnabledLauncherEntries(pm, context.packageName)
        return RescueOutcome.Restored(visibleEntryCount = visible?.size)
    }

    /**
     * [target] 此刻是不是真的可用 —— 问的是系统认不认，而不是"我们刚才调用过设置"。
     *
     * 判据与 [IconPresetSwitcher] 里的同名逻辑一致（那边是私有的，这里为救援单独实现一份，
     * 因为救援**不能**复用"启用一个、禁用其余"的 [IconPresetSwitcher.applySwitch]）：
     *  1. 设置值本身给了答案：ENABLED = 是，三种 DISABLED = 否；
     *  2. 只有设置值为 DEFAULT（**从未显式设置过**，全新安装或某些 ROM 把与清单一致的设置回写成
     *     DEFAULT）时才回落到 `getActivityInfo(component, 0).enabled`，也就是清单里写的默认值
     *     （主入口 true、别名 `enabled="false"` → false）。少了这一层，"恢复默认入口"会因为
     *     读到 DEFAULT 而被误判成失败，于是每次都发一条"恢复没生效"的假警报。
     *
     * 明确的 DISABLED 必须拦在回落之前：假如某个 ROM 的 `getActivityInfo` 连被禁用的组件也照样
     * 返回，只凭"查得到"就会把没真正启用成功当成成功。
     */
    private fun isEnabledNow(pm: PackageManager, component: ComponentName): Boolean {
        val setting = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull()
        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }.getOrDefault(false)
        }
    }

    /**
     * 问系统：本包此刻**有哪些 launcher 入口是启用的**（返回全限定类名，去重排序）。
     *
     * 刻意**不带** `MATCH_DISABLED_COMPONENTS`：被禁用的组件不参与匹配，于是"返回了什么"就等于
     * "系统认为哪些桌面入口是活的" —— 这正是救援要问的问题。`setPackage` 限定到本包，并再按包名
     * 过滤一次（个别 ROM 会忽略 `setPackage`，那种情况下返回别包的组件会让判据失真）。
     *
     * @return null = **这次查询失败了**。调用方必须把 null 与"空列表"区别对待：
     *         空 = 一个入口都没有（可以救）；null = 不知道（不许动手，见 [rescue] 的门禁）。
     */
    private fun queryEnabledLauncherEntries(pm: PackageManager, packageName: String): List<String>? =
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { it.activityInfo }
                .filter { it.packageName == packageName && !it.name.isNullOrEmpty() }
                .map { it.name }
                .distinct()
                .sorted()
                .toList()
        }.getOrNull()

    /**
     * 发一条用户可见的通知。**恢复成功也要发**（本项目原则：任何降级都不允许静默发生）——
     * 用户桌面上的图标与名字被换掉了，他必须知道发生了什么、以及去哪儿改回来。
     *
     * 渠道用 [NotificationChannels.SYSTEM]（"系统问题通知"）：这是一条系统级问题的如实告知，
     * 不是主播动态。通知权限被关时 `notify()` 是**静默无效**的（系统不报错），所以先判一次权限，
     * 把"发不出去"如实返回给调用方落库 —— 否则这件事就彻底无声无息了。
     *
     * @return true = 通知已交给系统；false = 没发出去（权限/异常/渠道）。
     */
    private fun postNotification(context: Context, title: String, text: String): Boolean {
        if (!NotificationPoster.hasPermission(context)) {
            Log.w(TAG, "自救援通知未发出：通知权限不可用")
            return false
        }
        // ★ 渠道判据（复查发现的缺陷）：上面 KDoc 的返回值说明里写着"权限/异常/**渠道**"三种
        //   失败，但代码原先只判了权限 —— 渠道被关时 notify() 同样静默无效，而这里会返回
        //   true（"已交给系统"），调用方据此把"没发出去"记成成功。
        if (!NotificationPoster.isChannelEnabled(context, NotificationChannels.SYSTEM)) {
            Log.w(TAG, "自救援通知未发出：系统问题通知渠道已被关闭")
            return false
        }
        return runCatching {
            // 进程可能是被这条广播刚拉起来的，渠道未必已经建好（Application.onCreate 里也会建，
            // 但这里不依赖那个时序）；ensureCreated 本身是幂等的。
            NotificationChannels.ensureCreated(context)
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, NotificationChannels.SYSTEM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build()
            NotificationManagerCompat.from(context).notify(RESCUE_NOTIFICATION_ID, notification)
            true
        }.getOrElse {
            Log.w(TAG, "自救援通知发送失败：${it.message}")
            false
        }
    }

    /**
     * 落一条**用户可见**的诊断记录（诊断页的「软件运行错误」）。
     *
     * 为什么成功也要记：这条通道修的是"应用从桌面打不开"，属于必须留痕的降级；而且通知可能被
     * 用户划掉、或根本没有通知权限 —— 库里这条记录是唯一持久的证据（诊断包也会带上它）。
     * 写库失败只记 logcat：这里已经没有任何"更上一层"的地方可以报告了，但不能因此崩掉。
     */
    private suspend fun recordAppError(detail: String) {
        runCatching {
            logDao.insertAppError(
                ApplicationErrorLogEntity(
                    errorId = Ids.newId(),
                    operationId = null,
                    occurredAt = clock.nowWall(),
                    // 复用既有的 AppError 通道（不新增枚举值：那会影响 DDL CHECK 与既有映射）。
                    errorCode = AppError.UNKNOWN,
                    detail = detail.take(MAX_DETAIL)
                )
            )
        }.onFailure { Log.e(TAG, "自救援记录写入失败：${it.message}", it) }
    }

    /** 健康事件（诊断页的「健康事件」）：让"桌面入口曾经整个不可用"这件事在时间线上留下一笔。 */
    private suspend fun recordHealthEvent(from: MonitorHealthStatus, to: MonitorHealthStatus) {
        runCatching {
            logDao.insertHealthEvent(
                HealthEventEntity(
                    healthEventId = Ids.newId(),
                    component = HEALTH_COMPONENT,
                    fromStatus = from,
                    toStatus = to,
                    occurredAt = clock.nowWall(),
                    episodeId = null
                )
            )
        }.onFailure { Log.e(TAG, "自救援健康事件写入失败：${it.message}", it) }
    }

    /** 一次自救援的结果。三种情况的通知文案完全不同，所以必须分开（不允许"失败说成成功"）。 */
    private sealed interface RescueOutcome {
        /**
         * 组件读回确认已启用。
         *
         * @param visibleEntryCount 恢复后系统报告的桌面入口数；null = 那次查询失败（无法证伪）。
         *        0 = 组件说启用了、系统却仍然一个入口都不认 —— 那种情况**不能说"桌面上有图标了"**。
         */
        data class Restored(val visibleEntryCount: Int?) : RescueOutcome

        /** 启用调用抛异常，或读回仍是未启用。 */
        data class Failed(val reason: String) : RescueOutcome
    }

    companion object {
        private const val TAG = "LauncherEntryRescue"

        /**
         * `onReceive` 的执行窗口约 10 秒。留足余量：超时后放弃（协程被取消），
         * `pending.finish()` 仍会在 finally 里执行，系统不会因为我们而 ANR。
         */
        private const val RESCUE_TIMEOUT_MS = 8_000L

        /**
         * 通知 id。★ 取值必须避开 outbox 的 id 空间：`NotificationOutboxWriter` 从 1 开始分配、
         * 上限 `MAX_NOTIFICATION_ID = 2_000_000`，所以取它 +1 就永远不会与主播通知互相覆盖
         * （前台常驻通知用的是 `MonitoringService.SERVICE_NOTIFICATION_ID = 1001`）。
         */
        private const val RESCUE_NOTIFICATION_ID = 2_000_001

        /** 诊断记录的最大长度，与项目其它写入点一致（避免一条超长文本撑大日志表与诊断包）。 */
        private const val MAX_DETAIL = 500

        /** 健康事件里的组件名（该列是自由字符串，诊断页原样显示）。 */
        private const val HEALTH_COMPONENT = "LAUNCHER_ENTRY"

        /**
         * 需求逐字指定的通知正文（默认入口那一档）。**改这句话等于改需求**：
         * 用户要能一眼看出"发生了什么（更新后没有任何桌面入口）+ 系统已经替我做了什么（恢复为默认）"。
         */
        private const val RESTORED_DEFAULT_TEXT =
            "应用图标预设已恢复为默认（更新后检测到没有任何桌面入口，已自动修复）"

        /** 告知用户去哪儿改回自己想要的图标/名字（设置页里的入口名逐字取自 SettingsScreen）。 */
        private const val REOPEN_HINT = "可以到「设置 → 外观与主题 → 更换应用图标」重新选择。"
    }
}
