package com.example.bilimonitor.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationSettingsDataStore by preferencesDataStore(name = "notification_settings")

/**
 * 前台常驻通知的**内容模板**（用户要求：通知内容可自定义）。
 *
 * 存储用 DataStore 而不是 `monitoring_config` 表：这是一条纯展示偏好，
 * 不参与任何业务事实判定（状态机、CAS、租约都与它无关），
 * 为一个偏好去动那张表的 schema 与迁移不划算 —— 与 [QuietHoursRepository]、
 * [ImportSettingsRepository] 的取舍一致。
 *
 * 支持占位符（见 [NotificationTemplate]）：`{count}` 监控中的主播数、`{live}` 正在直播的主播数。
 * 之所以给占位符而不是纯自由文本：前台通知是"常驻"的，
 * 用户既想写自己的话（"我在盯直播"），也想让它带上实时数字。
 */
@Singleton
class NotificationSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyTitle = stringPreferencesKey("foreground_title")
    private val keyText = stringPreferencesKey("foreground_text")

    /** 当前模板（已做长度与空值规整）。 */
    val flow: Flow<ForegroundNotificationTemplate> = context.notificationSettingsDataStore.data.map {
        ForegroundNotificationTemplate(
            title = it[keyTitle] ?: NotificationTemplate.DEFAULT_TITLE,
            text = it[keyText] ?: NotificationTemplate.DEFAULT_TEXT
        )
    }

    suspend fun current(): ForegroundNotificationTemplate = flow.first()

    suspend fun set(title: String, text: String) {
        val sanitized = ForegroundNotificationTemplate(
            title = NotificationTemplate.sanitizeTitle(title),
            text = NotificationTemplate.sanitizeText(text)
        ).normalized()
        context.notificationSettingsDataStore.edit {
            it[keyTitle] = sanitized.title
            it[keyText] = sanitized.text
        }
    }

    /** 恢复默认：直接删键，而不是把默认值写进去 —— 这样以后改默认文案时用户能跟着更新。 */
    suspend fun reset() {
        context.notificationSettingsDataStore.edit {
            it.remove(keyTitle)
            it.remove(keyText)
        }
    }
}

/** 用户保存的模板；空串统一收敛为默认值，避免出现"通知空白"。 */
data class ForegroundNotificationTemplate(
    val title: String = NotificationTemplate.DEFAULT_TITLE,
    val text: String = NotificationTemplate.DEFAULT_TEXT
) {
    fun normalized(): ForegroundNotificationTemplate = ForegroundNotificationTemplate(
        title = title.ifBlank { NotificationTemplate.DEFAULT_TITLE },
        text = text.ifBlank { NotificationTemplate.DEFAULT_TEXT }
    )
}

/**
 * 前台通知模板的渲染与校验（纯函数，便于单测）。
 *
 * 规则：
 *  - `{count}` → 监控中的主播数；`{live}` → 正在直播的主播数；
 *  - **认不出的占位符原样保留**（用户写了 `{foo}` 就让他看见 `{foo}`，
 *    而不是悄悄吞掉 —— 静默改写用户输入比留着更让人困惑）；
 *  - 数字未知时（进程刚起来还没检查过）显示 `—`，而不是谎报 0；
 *  - 标题/正文都有长度上限：系统通知栏对超长文本会截断或换行错乱，
 *    而前台通知是常驻的，难看一次要看很久。
 */
object NotificationTemplate {

    /**
     * 用户**没自定义过**标题（DataStore 里没存过 `foreground_title` 键）时使用的默认标题。
     *
     * 注意它带 `{live}`：进程刚起来、引擎还没跑完第一次 Tick 时直播数是未知的（null），
     * 渲染结果是「—位主播正在直播」——这里**刻意接受**这个略显生硬的中间态，
     * 因为项目的硬规则是"数字未知就显示 `—`，绝不谎报 0"（见 [render] 与 [UNKNOWN_COUNT]）。
     * 该状态只在启动后极短时间内出现，一旦首轮 Tick 完成就会被真实数字覆盖。
     */
    const val DEFAULT_TITLE = "主播监控运行中"
    const val DEFAULT_TEXT = "{live}位主播正在直播"

    const val MAX_TITLE_LENGTH = 40
    const val MAX_TEXT_LENGTH = 120

    /** 未知数字时的占位（不谎报 0）。 */
    const val UNKNOWN_COUNT = "—"

    fun render(template: String, monitoredCount: Int?, liveCount: Int?): String =
        template
            .replace("{count}", monitoredCount?.toString() ?: UNKNOWN_COUNT)
            .replace("{live}", liveCount?.toString() ?: UNKNOWN_COUNT)

    fun sanitizeTitle(raw: String): String = sanitize(raw, MAX_TITLE_LENGTH)

    fun sanitizeText(raw: String): String = sanitize(raw, MAX_TEXT_LENGTH)

    private fun sanitize(raw: String, max: Int): String =
        raw.replace('\n', ' ').replace('\r', ' ').trim().take(max)
}
