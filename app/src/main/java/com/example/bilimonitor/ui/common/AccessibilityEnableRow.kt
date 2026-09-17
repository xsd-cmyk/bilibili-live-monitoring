package com.example.bilimonitor.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bilimonitor.background.AccessibilityKeepAlive

/**
 * 无障碍保活开关行：显示**实际**状态 + 跳转系统设置的入口。
 *
 * 引导页选项二与设置页都承诺"跳到系统设置，由用户手动开启"，但原实现没有任何入口；
 * 同时规格 0.6.47.2 要求展示实际能力而不是用户选择暗示的乐观状态。
 * 状态由系统回调驱动（[AccessibilityKeepAlive.observe]），用户在系统设置里开关后回到
 * App 会实时更新，无需重启。
 */
@Composable
fun AccessibilityEnableRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // observe(context) 每次调用都新建一条 Flow（内容提供者 + 系统回调拼出来的），
    // 因此必须先 remember：否则每次重组都换掉上游，collect 的旧订阅被取消、新的重订。
    //
    // 用 collectAsStateWithLifecycle 而不是 collectAsState：lifecycle-runtime-compose 2.8.7
    // 对普通 Flow 同样有重载（原先注释里"只对 StateFlow 有重载"的说法不成立，
    // 正是它挡下了正确做法），并且它会在退到后台时解除订阅 ——
    // 这条流挂在系统无障碍回调上，界面不可见时继续订阅纯属耗电。
    val observeFlow = remember(context) { AccessibilityKeepAlive.observe(context) }
    val enabled by observeFlow.collectAsStateWithLifecycle(
        initialValue = AccessibilityKeepAlive.isEnabled(context)
    )

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (enabled) "无障碍服务：已开启" else "无障碍服务：未开启",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            if (!enabled) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "未开启时保活不会生效，监控可能被系统中断",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(onClick = { AccessibilityKeepAlive.openSettings(context) }) {
            Text(if (enabled) "去设置" else "去开启")
        }
    }
}
