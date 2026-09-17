package com.example.bilimonitor.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 用户反馈条（原规范 28：任何操作结果都必须可见）。
 *
 * 说明：项目中 HomeScreen / DataScreen / StatsScreen / LoginScreen 各自实现了提示渲染，
 * 而 StreamerDetailScreen / HistoryScreen / SettingsScreen / HistoryListScreen 此前只维护了
 * message 状态却没有任何渲染点，导致保存失败、参数非法、修正结果等反馈静默丢失。
 * 这里抽出一个统一组件供这些页面复用。
 *
 * [autoDismissMillis] 不为 null 时，展示到期后自动调用 [onDismiss]（对应各页面原有的自动清理计时器）。
 *
 * 标记 `liveRegion = Polite`：这是"操作结果"，读屏必须主动念出来 ——
 * 否则不点屏幕就永远听不到保存失败之类的反馈（无障碍用户等于看不到这条提示）。
 */
@Composable
fun MessageBanner(
    message: String?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    autoDismissMillis: Long? = 2500L,
    onDismiss: () -> Unit = {}
) {
    if (message == null) return

    if (autoDismissMillis != null) {
        LaunchedEffect(message) {
            delay(autoDismissMillis)
            onDismiss()
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.inverseSurface
            )
        ) {
            Text(
                message,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}
