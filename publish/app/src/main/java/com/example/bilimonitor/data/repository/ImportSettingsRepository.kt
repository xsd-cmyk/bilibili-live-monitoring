package com.example.bilimonitor.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.importSettingsDataStore by preferencesDataStore(name = "import_settings")

/**
 * 导入相关的可配置项。
 *
 * 存储用 DataStore 而非 `monitoring_config` 表：该表结构由 0.6.29 DDL 固定，
 * 为一个纯偏好设置加列会牵动 schema 版本与迁移；这里的值不参与任何业务事实判定，
 * 与 [QuietHoursRepository] 的取舍一致。
 */
@Singleton
class ImportSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyMaxCount = intPreferencesKey("import_max_count")

    /** 单次导入的条数上限（自定义数量与"全部获取"共用）。默认 [IMPORT_MAX_COUNT]。 */
    val maxCount: Flow<Int> = context.importSettingsDataStore.data.map {
        (it[keyMaxCount] ?: IMPORT_MAX_COUNT).coerceIn(1, ABSOLUTE_MAX)
    }

    suspend fun currentMaxCount(): Int = maxCount.first()

    suspend fun setMaxCount(value: Int) {
        context.importSettingsDataStore.edit { it[keyMaxCount] = value.coerceIn(1, ABSOLUTE_MAX) }
    }

    companion object {
        /**
         * 硬上限。用户可把上限调高，但总得有个边界：
         * 每个导入的主播都会按检查间隔产生请求，无上限会让监控引擎被自己的列表压垮。
         */
        const val ABSOLUTE_MAX = 100000
    }
}
