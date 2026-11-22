package com.example.bilimonitor.ui.history

import com.example.bilimonitor.ui.common.AppAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.core.AppClocks
import com.example.bilimonitor.data.local.entity.RoomBanHistoryEntity
import com.example.bilimonitor.domain.policy.RoomBanPolicy
import com.example.bilimonitor.ui.home.formatTime

@Composable
fun RoomBanHistoryDialog(rows: List<RoomBanHistoryEntity>, count: Int, currentAt: Long?,
    hasMore: Boolean, onMore: () -> Unit, onDismiss: () -> Unit) {
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text("封禁记录 · 已记录 $count 次") },
        text = {
            Column {
                Text("按确认封禁时间记录；同一次封禁的复查不重复计数。旧记录仅补入已保存的证据。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                if (rows.isEmpty()) Text("暂无封禁记录")
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.banId }) { index, row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("第 ${count - index} 次 · ${formatTime(row.detectedAt)}",
                                style = MaterialTheme.typography.titleSmall)
                            Text(when {
                                row.liftedAt != null -> "确认解封：${formatTime(row.liftedAt)}"
                                row.detectedAt == currentAt -> "封禁中 · " + RoomBanPolicy.lockTillLabel(row.lockTill, AppClocks.nowWall())
                                else -> "解除时间未记录"
                            }, style = MaterialTheme.typography.bodySmall)
                            if (row.sessionStableId != null) Text("发生于已记录的直播场次期间",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                    if (hasMore) item { TextButton(onClick = onMore) { Text("加载更多封禁记录") } }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
