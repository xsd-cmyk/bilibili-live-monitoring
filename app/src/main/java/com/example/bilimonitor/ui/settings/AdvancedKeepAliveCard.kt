package com.example.bilimonitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bilimonitor.data.privilege.PrivilegeEnvironment
import com.example.bilimonitor.data.privilege.PrivilegeLevel
import com.example.bilimonitor.data.repository.KeepAliveItem
import com.example.bilimonitor.data.repository.KeepAliveItemState
import com.example.bilimonitor.data.repository.KeepAliveReport

/**
 * 「高级保活（Root / Shizuku）」卡片。
 *
 * ## 为什么需要它（这段解释也写在界面上，用户必须看懂再开）
 * 屏幕一关，设备会挂起 CPU（suspend / Doze），**所有定时器停摆** ——
 * 前台服务只保证进程不被回收，挡不住这件事，表现就是"锁屏后不再检查"。
 *
 * 所以真正起作用的是两件事，缺一不可：
 *  1. **锁屏继续活跃**：持有 partial wakelock 让 CPU 不睡（零权限，本卡片第一个开关）；
 *  2. **让系统在 Doze 里也认它**：Doze 白名单 + 待机桶 ACTIVE + 后台 appops 放行。
 *     这三件都是 shell(adb) 级操作，普通应用做不到 —— **Shizuku 或 root** 才能做。
 *
 * ## 措辞纪律
 * 全程只说"显著提高存活率"。厂商 ROM（小米/华为/OPPO/vivo 等）有自家的后台清理策略，
 * 这些命令管不到 —— 不许在这里写"绝对不被杀"，那是骗人。
 */
@Composable
internal fun AdvancedKeepAliveCard(
    wakelockEnabled: Boolean,
    wakelockHeld: Boolean,
    currentLevel: PrivilegeLevel,
    power: com.example.bilimonitor.data.repository.AdvancedKeepAliveRepository.PowerExemption?,
    onRequestIgnoreBattery: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    env: PrivilegeEnvironment?,
    report: KeepAliveReport?,
    busy: Boolean,
    onToggleWakelock: (Boolean) -> Unit,
    onDetect: () -> Unit,
    onRequestShizuku: () -> Unit,
    onApply: (PrivilegeLevel) -> Unit,
    onRevert: () -> Unit,
    onProbe: () -> Unit
) {
    var showLog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "高级保活（Root / Shizuku）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "锁屏后设备会挂起 CPU，定时器全部停摆 —— 前台服务挡不住这件事。" +
                    "下面两步配合起来，锁屏后才会继续按间隔检查：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "① 让 CPU 不睡（partial wakelock，零权限）　② 让系统在 Doze 里也认它" +
                    "（Doze 白名单 / 待机桶 / appops，需要 Shizuku 或 root）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            // ── 免 root 加固：不需要 root / Shizuku 就能做，且优先级最高 ──
            Text("免 root 加固（建议先做这两项）", fontWeight = FontWeight.Medium)
            Text(
                when {
                    power == null -> "读取中…"
                    power.ignoringBatteryOptimizations ->
                        "✓ 已加入电池优化白名单：Doze 期间仍可用 wakelock 与网络，" +
                            "系统也不会以「应用空闲」停掉前台服务"
                    else ->
                        "✗ 未加入电池优化白名单：实测关屏约 3 分钟后系统会以" +
                            "「Stopping service due to app idle」停掉前台服务，" +
                            "此后再想自己启动前台服务也会被拒（Background start not allowed）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (power?.ignoringBatteryOptimizations == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestIgnoreBattery) { Text("申请忽略电池优化") }
                OutlinedButton(onClick = onOpenAutoStart) { Text("厂商自启动设置") }
            }
            Text(
                "厂商 ROM（小米 / 华为 / OPPO / vivo 等）有自家的后台清理策略，系统级命令管不到，" +
                    "只能在上面那个页面里把本应用设为「允许自启动 / 允许后台运行」。" +
                    "加入电池优化白名单会让待机耗电略有增加。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "精确闹钟：" + when {
                        power == null -> "读取中…"
                        power.canScheduleExactAlarms ->
                            "已允许 —— 心跳能在 Doze 里准点唤醒（用它排的闹钟即使应用在后台也允许启动前台服务）"
                        else -> "未允许 —— 心跳会退化为不精确唤醒（仍会触发，只是可能晚一些）；" +
                            "授予「忽略电池优化」也能达到同样效果"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (power?.canScheduleExactAlarms != true) {
                    TextButton(onClick = onOpenExactAlarm) { Text("去允许") }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("锁屏继续活跃", fontWeight = FontWeight.Medium)
                    Text(
                        "持有 partial wakelock：屏幕关闭后仍按间隔轮询。会明显增加耗电" +
                            "（只在实时模式的前台服务里持有，退出服务即释放）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = wakelockEnabled, onCheckedChange = onToggleWakelock)
            }
            if (wakelockEnabled) {
                Text(
                    if (wakelockHeld) {
                        "实际状态：正在持有 wakelock（实时模式下 CPU 不会休眠）"
                    } else {
                        "实际状态：未持有 wakelock —— 服务没在跑、或系统拒绝了取锁" +
                            "（这种情况锁屏后仍会停止轮询，详情见设置页的诊断包）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wakelockHeld) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            if (currentLevel != PrivilegeLevel.NONE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        currentLevel == PrivilegeLevel.ROOT && !wakelockEnabled ->
                            "注意：你用的是 Root 档，但「锁屏继续活跃」没开 —— 锁屏后仍会停止轮询" +
                                "（提权只负责让系统别拦，CPU 还是需要 wakelock 才不睡）。"
                        currentLevel == PrivilegeLevel.SHIZUKU && !wakelockEnabled ->
                            "注意：Shizuku 档已应用，但「锁屏继续活跃」没开 —— 锁屏后仍会停止轮询。"
                        else -> "当前档位：${levelLabel(currentLevel)}（已应用到系统）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!wakelockEnabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("环境", fontWeight = FontWeight.Medium)
            Text(
                "Root：" + when {
                    env == null -> "未检测（点「检测环境」；首次会弹出 root 授权框）"
                    env.rootAvailable -> "可用"
                    env.rootFailure != null -> "不可用（${env.rootFailure}）"
                    else -> "未检测"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                // ★ 判定顺序：先看"服务在不在、有没有授权"（这才是能不能用的判据），
                //   最后才提"装没装应用" —— 否则 Sui（用 Magisk 模块提供 Shizuku，没有独立应用）
                //   会被显示成"未安装"，而旁边的「用 Shizuku 应用」按钮却是亮的，自相矛盾。
                "Shizuku：" + when {
                    env == null -> "未检测"
                    env.shizukuRunning && env.shizukuAuthorized ->
                        "已授权（版本 ${env.shizukuVersion ?: "?"}，" +
                            "身份 uid=${env.shizukuUid ?: "?"}）" +
                            if (env.shizukuUid == 0) "；以 root 身份运行，可做内存回收保护" else ""
                    env.shizukuRunning -> "已运行但未授权（点下面的「申请 Shizuku 授权」）"
                    env.shizukuInstalled -> "已安装但未运行（在 Shizuku 应用里启动它）"
                    else -> "未检测到（需安装并启动 Shizuku，或用 Sui 之类的 root 方案提供）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDetect, enabled = !busy) { Text("检测环境") }
                OutlinedButton(onClick = onRequestShizuku, enabled = !busy) { Text("申请 Shizuku 授权") }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onApply(PrivilegeLevel.ROOT) },
                    enabled = !busy && env?.rootUsable == true
                ) { Text("用 Root 应用") }
                Button(
                    onClick = { onApply(PrivilegeLevel.SHIZUKU) },
                    enabled = !busy && env?.shizukuUsable == true
                ) { Text("用 Shizuku 应用") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onProbe, enabled = !busy) { Text("查看当前生效状态") }
                TextButton(onClick = onRevert, enabled = !busy) { Text("撤销（恢复系统默认）") }
            }

            if (busy) {
                Spacer(Modifier.height(6.dp))
                Text("处理中…（root 首次调用可能正在等你在授权框上点允许）", style = MaterialTheme.typography.bodySmall)
            }

            report?.let { r ->
                Spacer(Modifier.height(10.dp))
                Text("最近一次结果：${levelLabel(r.mode)} · ${r.summary()}", fontWeight = FontWeight.Medium)
                r.items.forEach { item -> KeepAliveItemRow(item) }
                if (r.log.isNotEmpty()) {
                    TextButton(onClick = { showLog = !showLog }) {
                        Text(if (showLog) "隐藏命令明细" else "查看命令明细（${r.log.size} 条）")
                    }
                    if (showLog) {
                        r.log.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "说明：以上都是系统级设置，能显著提高锁屏后的存活率，但不保证绝对不被杀 —— " +
                    "厂商 ROM（小米 / 华为 / OPPO / vivo 等）有自家的省电与后台清理策略，" +
                    "这些命令管不到，还需要在系统的「电池 / 后台管理」里把本应用设为" +
                    "「无限制 / 允许后台运行」。加入 Doze 白名单也会让待机耗电略有增加。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KeepAliveItemRow(item: KeepAliveItem) {
    val (mark, color) = when (item.state) {
        KeepAliveItemState.APPLIED -> "已生效" to MaterialTheme.colorScheme.primary
        KeepAliveItemState.NOT_APPLIED -> "未生效" to MaterialTheme.colorScheme.error
        KeepAliveItemState.UNSUPPORTED -> "本档不支持" to MaterialTheme.colorScheme.onSurfaceVariant
        KeepAliveItemState.FAILED -> "失败" to MaterialTheme.colorScheme.error
    }
    Text(
        "· ${item.name}：$mark —— ${item.detail}",
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

private fun levelLabel(level: PrivilegeLevel): String = when (level) {
    PrivilegeLevel.ROOT -> "Root"
    PrivilegeLevel.SHIZUKU -> "Shizuku"
    PrivilegeLevel.NONE -> "未启用"
}
