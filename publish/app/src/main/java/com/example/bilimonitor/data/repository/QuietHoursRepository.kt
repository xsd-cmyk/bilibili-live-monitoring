package com.example.bilimonitor.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bilimonitor.domain.model.MonitoringConfigSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.quietHoursDataStore by preferencesDataStore(name = "quiet_hours")

/**
 * 免打扰时段（原规范 22.3）。
 *
 * 规格要求「可配、默认关、默认 23:00–07:00」，但实现中该能力**完全不存在**：
 * `StreamerMonitorPolicyEntity.overrideQuietHours` 是死字段，全局也没有任何存储位置。
 *
 * 存储选择 DataStore 而非 monitoring_config 表：该表结构由 0.6.29 DDL 固定，
 * 为一个纯偏好设置加列会牵动 schema 版本与迁移；DataStore 与业务事实无关，
 * 且与 MaintenanceRepository 已有的 preferencesDataStore 用法一致。
 */
@Singleton
class QuietHoursRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyEnabled = booleanPreferencesKey("quiet_hours_enabled")
    private val keyStart = intPreferencesKey("quiet_hours_start_minutes")
    private val keyEnd = intPreferencesKey("quiet_hours_end_minutes")

    data class QuietHours(
        val enabled: Boolean = false,
        val startMinutes: Int = MonitoringConfigSnapshot.DEFAULT_QUIET_START,
        val endMinutes: Int = MonitoringConfigSnapshot.DEFAULT_QUIET_END
    )

    val flow: Flow<QuietHours> = context.quietHoursDataStore.data.map { p ->
        QuietHours(
            enabled = p[keyEnabled] ?: false,
            startMinutes = p[keyStart] ?: MonitoringConfigSnapshot.DEFAULT_QUIET_START,
            endMinutes = p[keyEnd] ?: MonitoringConfigSnapshot.DEFAULT_QUIET_END
        )
    }

    /**
     * 读取当前免打扰设置。
     *
     * **刻意不做容错回退**：若读取失败就返回默认值（enabled = false），
     * 免打扰会在用户毫不知情的情况下失效 —— 用户以为夜里不会被打扰，实际会被通知吵醒。
     * 让异常向上传播，由 `ConfigRepository.getSnapshot()` 的调用方
     * （MonitoringEngine 会记 CONFIG_INVALID 并跳过本轮 Tick）统一暴露。
     */
    suspend fun current(): QuietHours = flow.first()

    suspend fun set(enabled: Boolean, startMinutes: Int, endMinutes: Int) {
        context.quietHoursDataStore.edit { p ->
            p[keyEnabled] = enabled
            p[keyStart] = startMinutes.mod(24 * 60)
            p[keyEnd] = endMinutes.mod(24 * 60)
        }
    }
}
