package com.example.bilimonitor.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.background.MonitoringController
import com.example.bilimonitor.data.local.BackgroundKeepAliveChoice
import com.example.bilimonitor.data.local.MonitoringMode
import com.example.bilimonitor.data.local.entity.MonitoringConfigEntity
import com.example.bilimonitor.data.repository.ConfigRepository
import com.example.bilimonitor.data.repository.ConfigUpdate
import com.example.bilimonitor.data.repository.ConfigUpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val monitoringController: MonitoringController,
    private val quietHoursRepository: com.example.bilimonitor.data.repository.QuietHoursRepository,
    private val notificationSettingsRepository:
        com.example.bilimonitor.data.repository.NotificationSettingsRepository,
    /** 展示偏好（数据过期提示开关）走 DataStore，与 `monitoring_config` 无关（见仓库里的键说明）。 */
    private val appearanceSettingsRepository:
        com.example.bilimonitor.data.repository.AppearanceSettingsRepository,
    /** 高级保活（Root / Shizuku）：开关走 DataStore，提权命令走 Shizuku/root 通道。 */
    private val advancedKeepAliveRepository:
        com.example.bilimonitor.data.repository.AdvancedKeepAliveRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val config: StateFlow<MonitoringConfigEntity?> =
        configRepository.getConfigFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 免打扰时段（原规范 22.3，默认关）。 */
    val quietHours: StateFlow<com.example.bilimonitor.data.repository.QuietHoursRepository.QuietHours> =
        quietHoursRepository.flow
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.example.bilimonitor.data.repository.QuietHoursRepository.QuietHours()
            )

    fun setQuietHours(enabled: Boolean, startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch {
            runCatching { quietHoursRepository.set(enabled, startMinutes, endMinutes) }
                .onSuccess {
                    message.value = if (enabled) {
                        "免打扰已开启：${com.example.bilimonitor.domain.policy.QuietHoursPolicy.format(startMinutes)}" +
                            "–${com.example.bilimonitor.domain.policy.QuietHoursPolicy.format(endMinutes)}"
                    } else "免打扰已关闭"
                }
                .onFailure { message.value = "保存失败：${it.message}" }
        }
    }

    val message = MutableStateFlow<String?>(null)

    /** 前台常驻通知的内容模板（用户要求：可自定义）。 */
    val notificationTemplate: StateFlow<com.example.bilimonitor.data.repository.ForegroundNotificationTemplate> =
        notificationSettingsRepository.flow
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.example.bilimonitor.data.repository.ForegroundNotificationTemplate()
            )

    fun saveNotificationTemplate(title: String, text: String) {
        viewModelScope.launch {
            runCatching { notificationSettingsRepository.set(title, text) }
                .onSuccess {
                    // 保存后立刻让常驻通知换上新文案（服务没在跑时该调用无害）
                    monitoringController.refreshForegroundNotification()
                    message.value = "通知内容已保存"
                }
                .onFailure { message.value = "保存失败：${it.message}" }
        }
    }

    fun resetNotificationTemplate() {
        viewModelScope.launch {
            runCatching { notificationSettingsRepository.reset() }
                .onSuccess {
                    monitoringController.refreshForegroundNotification()
                    message.value = "通知内容已恢复默认"
                }
                .onFailure { message.value = "恢复失败：${it.message}" }
        }
    }

    /**
     * 通知是否可用（含"权限已授予"与"系统里没被整体关掉"两个条件）。
     *
     * ★ 委托给 [com.example.bilimonitor.notify.NotificationPoster.hasPermission]（复查发现的缺陷）：
     *   这里原先自己写了一份 `checkSelfPermission(POST_NOTIFICATIONS)`，而那个权限是 API 33 才
     *   引入的 —— 在 Android 12/12L（minSdk=31）上它恒为 DENIED，于是设置页**永久**显示
     *   "通知权限未授予：后台仍可监控，但无法发出通知"这条假警报，而通知其实完全正常。
     *   NotificationPoster 里早就为此写了兼容分支并注明原因，两份实现必须合并成一份。
     */
    fun notificationsEnabled(): Boolean =
        com.example.bilimonitor.notify.NotificationPoster.hasPermission(appContext)

    fun update(transform: (ConfigUpdate) -> ConfigUpdate) {
        viewModelScope.launch {
            val result = runCatching { configRepository.update(transform(ConfigUpdate())) }
                .getOrElse { message.value = "保存失败：${it.message}"; return@launch }
            when (result) {
                ConfigUpdateResult.Applied -> {
                    monitoringController.syncWithConfig()
                    message.value = "设置已保存"
                }
                ConfigUpdateResult.VersionConflict -> message.value = "设置已被其他修改更新，请重试"
                is ConfigUpdateResult.Invalid -> message.value = result.reason
            }
        }
    }

    fun changeKeepAliveChoice(choice: BackgroundKeepAliveChoice, acknowledged: Boolean) {
        viewModelScope.launch {
            val ok = runCatching { configRepository.completeOnboarding(choice, acknowledged) }
                .getOrElse { message.value = "更新失败：${it.message}"; return@launch }
            if (ok) {
                monitoringController.syncWithConfig()
                message.value = "保活方式已更新"
            } else {
                message.value = "更新失败，请重试"
            }
        }
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        update { it.copy(monitoringEnabled = enabled) }
    }

    /**
     * 「开启数据过期提示」开关（用户要求：高级设置里可关，默认开）。
     *
     * 只影响首页卡片上是否追加「· 数据过期」这一个标记：时间行、监控调度、落库全都不变
     * （判定规则见 `domain.policy.FreshnessPolicy`）。存在 DataStore 展示偏好里，
     * 因此**必须**走 appearanceSettingsRepository 而不是 configRepository.update。
     */
    val freshnessExpiryHintEnabled: StateFlow<Boolean> =
        appearanceSettingsRepository.flow
            .map { it.freshnessExpiryHintEnabled }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.example.bilimonitor.data.repository.AppearanceSettingsRepository
                    .DEFAULT_FRESHNESS_EXPIRY_HINT
            )

    fun setFreshnessExpiryHint(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { appearanceSettingsRepository.setFreshnessExpiryHint(enabled) }
                .onSuccess { message.value = if (enabled) "已开启数据过期提示" else "已关闭数据过期提示" }
                .onFailure { message.value = "保存失败：${it.message}" }
        }
    }

    // ─────────────────────────────── 高级保活（Root / Shizuku）

    /** 开关状态（DataStore）。 */
    val advancedKeepAlive:
        StateFlow<com.example.bilimonitor.data.repository.AdvancedKeepAliveSettings> =
        advancedKeepAliveRepository.flow.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.example.bilimonitor.data.repository.AdvancedKeepAliveSettings()
        )

    /** 环境探测结果；null = 还没探测过（界面据此显示"未检测"，不假装知道）。 */
    private val _privilegeEnv =
        MutableStateFlow<com.example.bilimonitor.data.privilege.PrivilegeEnvironment?>(null)
    val privilegeEnv: StateFlow<com.example.bilimonitor.data.privilege.PrivilegeEnvironment?> = _privilegeEnv

    /** 最近一次"应用 / 回退 / 探测"的逐项结果。 */
    private val _keepAliveReport =
        MutableStateFlow<com.example.bilimonitor.data.repository.KeepAliveReport?>(null)
    val keepAliveReport: StateFlow<com.example.bilimonitor.data.repository.KeepAliveReport?> =
        _keepAliveReport

    private val _keepAliveBusy = MutableStateFlow(false)
    val keepAliveBusy: StateFlow<Boolean> = _keepAliveBusy

    /**
     * wakelock 的**实际**持有状态（不是"开关开着"）。
     *
     * 为什么必须显示：开关开着但锁没拿到（系统拒绝/取锁异常）时，用户会以为"锁屏没关系了"，
     * 而实际锁屏后照样停 —— 这正是本项目禁止的"显示乐观状态"。
     */
    val wakelockHeld: StateFlow<Boolean> =
        com.example.bilimonitor.background.MonitoringService.isWakelockHeld

    /**
     * 电源豁免状态（免 root 加固）。
     *
     * 为什么单独一条流：[忽略电池优化] 是**系统状态**，不是 Compose 状态 ——
     * 用户去系统对话框里点完回来，必须重新读一次，否则界面会一直停在旧结论上
     * （与通知权限那条完全同型，见 SettingsScreen 里的 LifecycleResumeEffect）。
     */
    private val _powerExemption =
        MutableStateFlow<com.example.bilimonitor.data.repository.AdvancedKeepAliveRepository.PowerExemption?>(null)
    val powerExemption:
        StateFlow<com.example.bilimonitor.data.repository.AdvancedKeepAliveRepository.PowerExemption?> =
        _powerExemption

    /** 读一次电源豁免状态（进页面、以及从系统设置返回时各调一次）。 */
    fun refreshPowerExemption() {
        _powerExemption.value = runCatching { advancedKeepAliveRepository.powerExemption() }.getOrNull()
    }

    /** 「忽略电池优化」：优先弹系统的一次性授权框，ROM 不认就退回列表页。 */
    fun requestIgnoreBatteryOptimizations() {
        val intent = advancedKeepAliveRepository.batteryOptimizationIntent()
        if (intent == null) {
            message.value = "这台设备没有「忽略电池优化」的设置页，请到系统设置 → 应用 → 电池 里手动放行"
            return
        }
        // 系统页面必须以 NEW_TASK 启动（从非 Activity 上下文）
        runCatching { appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message.value = "打开系统设置失败：${it.message}" }
    }

    /** 厂商「自启动 / 后台运行」设置页。 */
    fun openAutoStartSettings() {
        val intent = advancedKeepAliveRepository.autoStartSettingsIntent()
        val isAppDetails = intent.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        runCatching { appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess {
                message.value = if (isAppDetails) {
                    "没找到厂商的自启动设置页，已打开应用详情 —— 请手动找到「自启动 / 后台运行」并允许"
                } else {
                    "已打开厂商的自启动设置页，请把本应用设为允许"
                }
            }
            .onFailure { message.value = "打开设置失败：${it.message}" }
    }

    /**
     * 精确闹钟授权页。
     *
     * 拿不到并不影响保活（心跳退化为不精确唤醒），所以这里不吓唬用户，只如实说明；
     * 而且"忽略电池优化"白名单本身就能排精确闹钟 —— 那条才是更值得点的。
     */
    fun openExactAlarmSettings() {
        val intent = advancedKeepAliveRepository.exactAlarmSettingsIntent()
        if (intent == null) {
            message.value = "这台设备没有「精确闹钟」设置页；不影响保活（心跳会退化为不精确唤醒）"
            return
        }
        runCatching { appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message.value = "打开设置失败：${it.message}" }
    }

    /**
     * Shizuku 授权结果回调。
     *
     * 必须注册：用户点了「申请 Shizuku 授权」并在弹窗里允许之后，环境状态才会变 ——
     * 不注册的话界面会一直停在"已运行但未授权"，用户以为没生效又反复点。
     */
    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            viewModelScope.launch {
                _privilegeEnv.value = runCatching {
                    advancedKeepAliveRepository.detectEnvironment(probeRoot = false)
                }.getOrNull()
                message.value = if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "Shizuku 已授权，现在可以用「用 Shizuku 应用」了"
                } else {
                    "Shizuku 授权被拒绝（在 Shizuku 应用里可以随时重新授权）"
                }
            }
        }

    init {
        runCatching {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        // 进页面先做一次**不触发授权框**的探测（probeRoot = false）：
        // 直接调 su 会在用户没点任何按钮时弹 root 授权框，那太唐突。
        viewModelScope.launch {
            _privilegeEnv.value = runCatching {
                advancedKeepAliveRepository.detectEnvironment(probeRoot = false)
            }.getOrNull()
        }
    }

    override fun onCleared() {
        runCatching {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        super.onCleared()
    }

    fun setAdvancedWakelock(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { advancedKeepAliveRepository.setWakelockEnabled(enabled) }
                .onSuccess {
                    // 立刻让已在运行的前台服务同步 wakelock；否则要等下一轮 Tick（最长 3600 秒）
                    monitoringController.syncKeepAliveNow()
                    message.value = if (enabled) {
                        "已开启锁屏继续活跃（实时模式下持有 wakelock，耗电会增加）"
                    } else {
                        "已关闭锁屏继续活跃"
                    }
                }
                .onFailure { message.value = "保存失败：${it.message}" }
        }
    }

    /** 检测环境（[probeRoot] = true 时真的执行一次 su，首次会弹授权框）。 */
    fun refreshPrivilegeEnvironment(probeRoot: Boolean = true) {
        viewModelScope.launch {
            _keepAliveBusy.value = true
            runCatching { advancedKeepAliveRepository.detectEnvironment(probeRoot) }
                .onSuccess {
                    _privilegeEnv.value = it
                    message.value = "环境已检测：Root " + (if (it.rootAvailable) "可用" else "不可用") +
                        "；Shizuku " + when {
                            !it.shizukuInstalled -> "未安装"
                            !it.shizukuRunning -> "未运行"
                            !it.shizukuAuthorized -> "未授权"
                            else -> "已授权"
                        }
                }
                .onFailure { message.value = "检测失败：${it.message}" }
            _keepAliveBusy.value = false
        }
    }

    fun requestShizukuPermission() {
        message.value = if (advancedKeepAliveRepository.requestShizukuPermission(SHIZUKU_REQUEST_CODE)) {
            "已请求 Shizuku 授权，请在弹窗中允许"
        } else {
            "请求失败：Shizuku 未安装、未运行或版本过低"
        }
    }

    fun applyAdvancedKeepAlive(level: com.example.bilimonitor.data.privilege.PrivilegeLevel) {
        viewModelScope.launch {
            _keepAliveBusy.value = true
            val env = runCatching {
                advancedKeepAliveRepository.detectEnvironment(
                    probeRoot = level == com.example.bilimonitor.data.privilege.PrivilegeLevel.ROOT
                )
            }.getOrNull()
            _privilegeEnv.value = env ?: _privilegeEnv.value
            val usable = when (level) {
                com.example.bilimonitor.data.privilege.PrivilegeLevel.ROOT -> env?.rootUsable == true
                com.example.bilimonitor.data.privilege.PrivilegeLevel.SHIZUKU -> env?.shizukuUsable == true
                com.example.bilimonitor.data.privilege.PrivilegeLevel.NONE -> false
            }
            if (!usable) {
                message.value = when (level) {
                    com.example.bilimonitor.data.privilege.PrivilegeLevel.ROOT ->
                        "Root 不可用：${env?.rootFailure ?: "没找到可用的 su"}"
                    else -> "Shizuku 不可用：请先安装并启动 Shizuku，再点「申请 Shizuku 授权」"
                }
                _keepAliveBusy.value = false
                return@launch
            }
            runCatching { advancedKeepAliveRepository.apply(level) }
                .onSuccess { report ->
                    _keepAliveReport.value = report
                    runCatching { advancedKeepAliveRepository.setPrivilegeLevel(level) }
                    val warn = if (!advancedKeepAlive.value.wakelockEnabled) {
                        "；注意：「锁屏继续活跃」还没开，锁屏后仍会停止轮询"
                    } else ""
                    message.value = "高级保活已应用（${report.summary()}）$warn"
                }
                .onFailure { message.value = "应用失败：${it.message}" }
            _keepAliveBusy.value = false
        }
    }

    fun revertAdvancedKeepAlive() {
        viewModelScope.launch {
            _keepAliveBusy.value = true
            val level = advancedKeepAlive.value.privilegeLevel
            val target = if (level == com.example.bilimonitor.data.privilege.PrivilegeLevel.NONE) {
                com.example.bilimonitor.data.privilege.PrivilegeLevel.ROOT
            } else {
                level
            }
            runCatching { advancedKeepAliveRepository.revert(target) }
                .onSuccess { report ->
                    _keepAliveReport.value = report
                    runCatching { advancedKeepAliveRepository.setPrivilegeLevel(
                        com.example.bilimonitor.data.privilege.PrivilegeLevel.NONE
                    ) }
                    message.value = "已撤销：${report.summary()}"
                }
                .onFailure { message.value = "撤销失败：${it.message}" }
            _keepAliveBusy.value = false
        }
    }

    fun probeAdvancedStatus() {
        viewModelScope.launch {
            _keepAliveBusy.value = true
            val level = advancedKeepAlive.value.privilegeLevel
            val target = if (level == com.example.bilimonitor.data.privilege.PrivilegeLevel.NONE) {
                com.example.bilimonitor.data.privilege.PrivilegeLevel.ROOT
            } else {
                level
            }
            runCatching { advancedKeepAliveRepository.probeStatus(target) }
                .onSuccess { _keepAliveReport.value = it }
                .onFailure { message.value = "读取失败：${it.message}" }
            _keepAliveBusy.value = false
        }
    }

    private companion object {
        /** Shizuku 授权请求码（回调只用来刷新界面，不参与业务分支）。 */
        const val SHIZUKU_REQUEST_CODE = 1001
    }
}
