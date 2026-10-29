package com.example.bilimonitor.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.bilimonitor.domain.policy.NotificationTimelinessSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.deliverySettingsStore by preferencesDataStore(name = "notification_delivery")

@Singleton
class NotificationDeliverySettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val live = intPreferencesKey("live_minutes")
    private val end = intPreferencesKey("end_minutes")
    private val change = intPreferencesKey("change_minutes")
    val flow = context.deliverySettingsStore.data.map {
        NotificationTimelinessSettings(it[live] ?: 5, it[end] ?: 10, it[change] ?: 3)
    }
    suspend fun current() = flow.first().also { check(it.isValid()) { "通知有效期配置损坏" } }
    suspend fun set(value: NotificationTimelinessSettings) {
        require(value.isValid()) { "有效期须为 1–1440 分钟" }
        context.deliverySettingsStore.edit {
            it[live] = value.liveMinutes
            it[end] = value.endMinutes
            it[change] = value.changeMinutes
        }
    }
}
