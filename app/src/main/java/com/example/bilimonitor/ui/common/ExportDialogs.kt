package com.example.bilimonitor.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 可被勾选的导出对象（历史导出与统计导出共用）。 */
data class ExportCandidate(val id: Long, val name: String)

/**
 * 导出对象选择：一位或多位主播（多位合并成同一个文件）。
 *
 * 历史导出与统计导出**共用同一个对话框** —— 用户明确要求两个导出的规则一致，
 * 分成两份实现迟早会漂移（一个支持搜索一个不支持、一个能全选一个不能）。
 */
@Composable
fun ExportSelectDialog(
    title: String,
    streamers: List<ExportCandidate>,
    selected: Set<Long>,
    query: String,
    onQuery: (String) -> Unit,
    onToggle: (Long) -> Unit,
    // 「全选」把**当前可见**（已按 query 过滤）的那批主播回传给调用方：
    // 过滤逻辑只该有这里一份（调用方各自再抄一遍迟早漂移），而且若按传入的整份列表全选，
    // "先搜索、再全选"就会把屏幕上没显示的主播也勾上。
    onSelectAll: (List<ExportCandidate>) -> Unit,
    onClearAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = streamers.filter { query.isBlank() || it.name.contains(query.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title（已选 ${selected.size}）") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索主播名字") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 文案点明"当前结果"：搜索过滤生效时，全选选的是屏幕上这批
                    TextButton(onClick = { onSelectAll(filtered) }) { Text("全选当前结果") }
                    TextButton(onClick = onClearAll) { Text("清空") }
                    Text(
                        if (selected.size > 1) "将合并成一份文件" else "多选可合并导出",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (filtered.isEmpty()) {
                    Text("没有匹配的主播", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // heightIn(max) 而不是写死高度：AlertDialog 的 text 槽本身不滚动，
                    // 横屏/小屏/大字体下固定 320dp 会把列表底部和"下一步"按钮一起顶出可视区；
                    // 只设上限既保证约束有界（LazyColumn 不会因无限高约束崩溃），
                    // 内容少时也不会白留一大块空白。
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.id }) { s ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(s.id) }
                            ) {
                                Checkbox(
                                    checked = s.id in selected,
                                    onCheckedChange = { onToggle(s.id) }
                                )
                                Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selected.isNotEmpty()) { Text("下一步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
