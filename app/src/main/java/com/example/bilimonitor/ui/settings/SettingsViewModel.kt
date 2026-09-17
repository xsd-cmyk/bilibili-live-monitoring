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
}
