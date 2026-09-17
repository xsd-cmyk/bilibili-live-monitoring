package com.example.bilimonitor.ui.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.bilimonitor.data.local.dao.NotificationHistoryDao
import com.example.bilimonitor.data.local.entity.NotificationHistoryEntity
import com.example.bilimonitor.ui.common.FloatingTopBar
import com.example.bilimonitor.ui.home.formatTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationHistoryViewModel @Inject constructor(
    historyDao: NotificationHistoryDao,
    streamerDao: com.example.bilimonitor.data.local.dao.StreamerDao
) : ViewModel() {
    /** 当前展示条数上限；点"加载更多"按页递增。 */
    private val limit = MutableStateFlow(HISTORY_LIMIT)

    /**
     * `null` = 还没拿到第一次查询结果（初值），空列表 = 确实没有记录。
     *
     * 初值原本是 `emptyList()`，界面上无法区分这两种情况 ——
     * 每次进页面都先闪一下"暂无通知记录"再刷出内容。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<NotificationHistoryEntity>?> =
        limit.flatMapLatest { historyDao.observeRecent(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 界面用它判断"是不是已经到上限了"（决定要不要显示"加载更多"）。 */
    val shownLimit: StateFlow<Int> = limit

    /**
     * 主播名映射（stableId → 名字）。
     *
     * ★ 为什么走**读取端反查**而不是给历史表加列（2026 复查）：加列要迁移、要抬 DB 版本，
     *   而这里只需要"显示得出是谁"。代价是主播改名后旧记录显示新名字 —— 可以接受。
     *   用 `listAllIncludingDeleted()` 是为了让**已删除主播**的旧记录也还能显示名字。
     */
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names

    init {
        viewModelScope.launch {
            _names.value = runCatching { streamerDao.listAllIncludingDeleted() }
                .getOrDefault(emptyList())
                .associate { it.stableId to it.name }
        }
    }

    /** 再取一页（上限递增，查询用 flatMapLatest 自动重订阅）。 */
    fun loadMore() {
        limit.value += HISTORY_PAGE
    }
}

/**
 * 历史列表的条数上限（与查询一致）。
 *
 * 界面按它判断"是不是被截断了"，从而把话说清楚；两处写死同一个数字迟早会分叉，
 * 所以提成常量。
 */
private const val HISTORY_LIMIT = 200

/** 每次"加载更多"增加的条数。 */
private const val HISTORY_PAGE = 200

/** 通知历史中心（原规范 48）：通知审计结果，独立于 Outbox 生命周期。 */
@Composable
fun NotificationHistoryScreen(onBack: () -> Unit, viewModel: NotificationHistoryViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    val shownLimit by viewModel.shownLimit.collectAsStateWithLifecycle()
    // 取到局部变量：委托属性（by）无法智能转换，用局部 val 就不必写 `!!`
    val list = items
    Scaffold(
        topBar = { FloatingTopBar(title = { Text("通知历史") }, navigationIcon = {
            // 与全项目其它页面统一：返回用箭头图标，而不是中文「返回」文字按钮
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }) }
    ) { padding ->
        when {
            // 尚未加载：转圈，而不是先显示"暂无通知记录"再跳变
            list == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { Text("暂无通知记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ★ 列表有上限就必须说出来（复查发现的缺陷）：查询是 `LIMIT 200`，而库里的
                //   记录保留 90 天，98 位主播几天就能超过 —— 原先更早的记录静默消失，
                //   用户会以为"根本没发过"，与"通知审计"这个用途正好相反。
                if (list.size >= shownLimit) {
                    item {
                        Text(
                            "已显示最近 $shownLimit 条（更早的记录不在这个列表里，需要时可在诊断包里查）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 列表原先没有翻页入口：到 200 条就到此为止，用户以为"更早的没发过"
                        TextButton(onClick = { viewModel.loadMore() }) { Text("加载更多") }
                    }
                }
                items(list, key = { it.historyId }) { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                // 主播名前置（系统类通知没有主播，就只显示类别）
                                Text(
                                    listOfNotNull(
                                        h.streamerStableId?.let { names[it] },
                                        eventLabel(h.eventKey, h.detail)
                                    ).joinToString(" · "),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(statusLabel(h.deliveryStatus.name), color = statusColor(h.deliveryStatus.name),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                "${formatTime(h.recordedAt)} · 投递 ${h.attemptCount} 次" +
                                    // ★ 原因读 detail 而不是 reason（复查发现的缺陷）：
                                    //   `reason` 这一列**从来没有被写过**（唯一写入点恒为 null，
                                    //   唯一的 UPDATE 也不碰它），失败原因一直存在 `detail` 里 ——
                                    //   于是界面上"失败"后面永远没有解释，而库里其实有原因。
                                    (h.detail?.takeIf { it.isNotBlank() }
                                        ?.let { " · ${deliveryReasonLabel(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 事件类型文案。
 *
 * 系统类事件：出问题写 `problem:<problemKey>`，恢复写 `problem:<problemKey>:recovered`
 * （见 NotificationOutboxWriter.createSystemProblem）。原实现的判断顺序把
 * `detail == "RECOVERED"` / `contains(":recovered")` 放在 `startsWith("problem:")` 之前，
 * 但 `detail` 实际存的是投递错误信息、不含 "RECOVERED"，且前缀分支会先命中，
 * 导致**恢复通知一直被显示成"系统问题通知"**。这里改为先看 `:recovered` 后缀。
 */
private fun eventLabel(eventKey: String, detail: String?): String = when {
    detail == "RECOVERED" || eventKey.endsWith(":recovered") -> "系统恢复通知"
    eventKey.startsWith("evt:") -> "开播/关播通知"
    eventKey.startsWith("agg:") -> "批量开播通知"
    eventKey.startsWith("problem:") -> "系统问题通知"
    // 直播标题/分区变化通知（NotificationOutboxWriter.createForChange 生成
    // `change:TITLE:...` / `change:AREA:...`）。不补这两个分支就会一律显示成"通知"，
    // 用户看不出这条到底在说什么。
    // ★ 前缀必须与写入端逐字一致（复查发现的缺陷）：createForChange 生成的是
    //   "change:${eventType.name}:…"，而枚举名是 TITLE_CHANGED / AREA_CHANGED ——
    //   原先写的是 "change:TITLE:" / "change:AREA:"，第 13 个字符就分叉（'_' vs ':'），
    //   两个分支恒不命中，这两类通知在历史里一律显示成"通知"。
    eventKey.startsWith("change:TITLE_CHANGED:") -> "标题变化通知"
    eventKey.startsWith("change:AREA_CHANGED:") -> "分区变化通知"
    // 封禁状态变化（NotificationEventType.BAN_ENTERED / BAN_LIFTED，同样由 createForChange 生成）
    eventKey.startsWith("change:BAN_ENTERED:") -> "封禁通知"
    eventKey.startsWith("change:BAN_LIFTED:") -> "解封通知"
    else -> "通知"
}

/**
 * 投递状态文案。
 * 必须覆盖 NotificationHistoryDeliveryStatus 的全部取值（CREATED / PENDING / PROCESSING /
 * SENT / DELIVERY_UNKNOWN / FAILED / EXPIRED / CANCELLED）——原实现缺 PROCESSING、
 * CREATED、DELIVERY_UNKNOWN，这三类会把英文枚举名直接显示给用户。
 */
/**
 * 投递失败原因的**用户可读**文案（复查发现的缺陷）。
 *
 * 已知的内部码给中文；其余（例如 OkHttp 的异常文本）原样显示 —— 那比"失败了"三个字有用得多，
 * 而且这些字符串本来就只可能在诊断场景被看到。
 */
private fun deliveryReasonLabel(reason: String): String = when {
    reason == "PERMISSION_DENIED" -> "通知权限未授予"
    reason.startsWith("CHANNEL_DISABLED") -> "通知渠道已被关闭"
    reason == "PAYLOAD_INVALID" -> "通知内容无法解析"
    reason == "DELIVERY_UNKNOWN_ON_RECOVERY" -> "进程中断，投递结果未知"
    reason == "EXPIRED_BEFORE_DELIVERY" -> "未投递就过期"
    reason == "STREAMER_DELETED" -> "主播已删除，通知作废"
    reason == "DISPATCH_EXCEPTION" -> "投递时发生异常"
    else -> reason
}

private fun statusLabel(status: String) = when (status) {
    "CREATED" -> "已创建"
    "PENDING" -> "待投递"
    "PROCESSING" -> "投递中"
    "SENT" -> "已送达"
    "DELIVERY_UNKNOWN" -> "结果未知"
    "FAILED" -> "失败"
    "EXPIRED" -> "已过期"
    "CANCELLED" -> "已取消"
    else -> status
}

@Composable
private fun statusColor(status: String) = when (status) {
    "SENT" -> MaterialTheme.colorScheme.primary
    "FAILED", "EXPIRED" -> MaterialTheme.colorScheme.error
    "DELIVERY_UNKNOWN" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}