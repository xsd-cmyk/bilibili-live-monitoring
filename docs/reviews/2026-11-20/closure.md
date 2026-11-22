# 2026-11-20 审查缺陷修复与验收

范围是[原审查报告](report.md)的 16 个问题项（两条 Lint 错误合为一项），不是将所有历史设计差异都视为缺陷。保留原报告、[修复前的设备证据](device-evidence.txt)及名称刷新工作树；原来的 12 个定向验证已进入正常的设备测试套件，没有丢弃失败断言。实际 XML、设备运行器和升级读数的摘录见[修复后验收证据](verification-after.txt)。

## 修复对照

| 项 | 修复后的行为 | 证据 |
| --- | --- | --- |
| 1. 备注编辑 | 界面不回传未修改的分区／标题；仓储也只在该字段实际变化时改历史。 | `auditNotePreservesAreaTimeline` 验证修改、清空备注，以及两个分区的原始观察时间。 |
| 2. 盲区虚增时长 | 成功观察更新新鲜度，实际在播才更新场次的最后在播时间。连续的正常确认不误截断，长盲区回到最后在播证据。 | `auditGapEndsAtLastKnownLiveWithTwoConfirmations` 不再多计 14,520 秒；`auditClosureThresholdsWithAndWithoutGaps` 交叉验证阈值 1、2、3 与连续、无声长间隔、失败打断。 |
| 3. 暂停前的响应越代写入 | 批次、二分及重试保留请求身份；成功、失败和封禁探针均受主播代际和租约约束。 | `auditEngineCannotAdoptGenerationChangedDuringFetch` 在实际引擎的替代数据源返回前切换两次监控；主播仍为 `OFFLINE`、代际 3，未建场或推进序号。 |
| 4. 跳过仍覆盖另一 UID | 子项只认获准的主播及场次映射；跳过身份不写策略、事件、标题、分区、修正和标签／分组关联。采用备份身份时，已有引用跟随映射。 | `auditSkipDoesNotChangeAnotherUidPolicy`、`auditAdoptedIdentityRemapsExistingHistoryAndOutbox`。 |
| 5. 新旧包版本冲突 | 当前工作树为 `1120.1` / code 8，相对旧包 code 7 递增。Room 14 通过显式迁移引入可空观察时钟，不删库、不推算旧锚点。 | 下方 Debug 与 Release 覆盖安装；真库逐版迁移。 |
| 6. 回拨后通知误等 | 事件有效期与最近观察分开计算；同次开机比较单调时间，已知跨开机等待复核。无锚点的旧行只接受非未来且足够近的墙钟证据。 | `auditFreshObservationAfterWallRollback`、`auditRealConfirmationUsesElapsedUntilReboot`、`ObservationFreshnessTest`。 |
| 7–8. 未决执行、终态重入 | 单条数据库条件更新同时检查可执行前态与未决数。界面等待裁决落库；只允许取消预检／待决任务，执行和完成均需写权。 | `auditUndecidedRestoreDoesNotCommit`、`auditCancelledRestoreStaysCancelled`、`auditRestoreHasExactlyOneWinner`。 |
| 9–10. 新建及恢复场次关联断开 | 使用插入返回的真实主键；先确定最终场次，再绑定区间、事件及提醒，检查关联行数。 | `auditFreshSessionReferencesInterval`、`auditRecoveredSessionOwnsEvent`。 |
| 11. 手工时长冒充可靠覆盖 | 统计序列与缓存统计均把真实区间裁剪到所属场次及查询范围，对同一主播合并重叠；旧计算缓存不再命中。 | `auditReliableSecondsIntersectAndUnifyRealIntervals`：100 秒场次仅有 55 秒有效覆盖。`auditExportSeparatesManualDurationFromCoverage` 回读实际写出的历史和统计 HTML，一小时补录显示零可靠时长。 |
| 12. 默认日期取自其他主播 | 可选周期查询在数据库按主播及已结束场次过滤。 | `auditStatsSelectsThisStreamersLatestDay` 经实际统计 ViewModel 加载交叉日期样本。 |
| 13. 关注导入与登出竞争 | 在同一 Room 事务中校验预览前态、当前有效账号代际、写入所选项并完成任务；过代有独立取消结果，界面不再显示“导入完成”。 | `auditLogoutWinsBeforeImportAndTerminalCannotReplay` 控制两个协程的事务顺序，不用真实账号或凭证。 |
| 14. 引导误述后台时限 | 区分本应用的 `specialUse` 服务与 Android 15 的数据同步／媒体处理配额；保留省电、厂商限制和手动停止的提示。 | 对照 manifest、启动时传入的服务类型、引导文案和原报告引用的 Android 官方说明；不作为持续运行六小时的实测。 |
| 15. 名称刷新跳过数 | 手动全量以触发时的全部本地主播为分母，锁名和软删除各计一次；定时刷新只处理到期候选，批次发出前再次校验。 | `mixedBatchRetriesOnlyFailuresAndUsesPersistedSettings`、`deletedBetweenBatchesIsSkippedWithoutRequest`；已删除和锁定项均不在请求名单中。 |
| 16. Lint 错误未设门禁 | 保留通知权限及渠道检查，仅在已封装保护的局部标注；界面时钟显式更新 Compose 状态。两变体均以错误阻断构建。 | `lintDebug`、`lintRelease` 及对应 XML 的 severity 计数。警告与错误分开列出。 |

## 构建和真库测试

环境：Windows、Microsoft OpenJDK 17.0.19、Gradle Wrapper 8.11.1、Android SDK 35，API 35 使用只读 `BiliMonitor_Test`（`emulator-5592`），最低支持版本另建 API 31 AOSP x86_64 只读 AVD（`emulator-5596`）。以下为本轮实际运行，不引用旧发布的通过数。

```powershell
$env:ANDROID_SERIAL = 'emulator-5592'
.\gradlew.bat --offline --continue :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleRelease :app:lintDebug :app:lintRelease :app:connectedDebugAndroidTest
adb -s emulator-5596 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5596 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5596 shell am instrument -w -r com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner
openspec validate --all --strict --no-interactive
git -c core.whitespace=cr-at-eol diff --check
```

| 检查 | 结果 |
| --- | --- |
| 规范与差异 | 严格校验在归档前 **10/10**、归档后 **9/9**（8 能力、原名称刷新变更仍在途）；`git diff --check` 通过。[独立变更及任务](../../../openspec/changes/archive/2026-11-20-repair-audit-integrity/tasks.md)已归档。 |
| 构建 | Debug、Debug AndroidTest、启用 R8 和资源压缩的 Release 均构建成功。未配置生产签名，未发布。 |
| JVM | Debug **376/376**、Release **376/376**；失败、错误、跳过均为 0。 |
| API 35 真库及导航 | **60/60**，失败、错误、跳过均为 0。涵盖原审查的全部 12 个定向用例，另有真实导出、竞态、时钟和证据边界回归。 |
| API 31 最低版本 | 同一套 Debug 与 AndroidTest APK **60/60**；运行器 60 次开始、60 次通过，0 失败、错误或跳过。未将未运行的 API 32–34 计入。 |
| Lint | Debug **0 错误、205 警告、3 信息**；Release **0 错误、164 警告、3 信息**。剩余警告未被当作错误抑制或宣称已清零。 |
| 旧库迁移 | 在设备上按仓内导出的每个旧 schema **v2–v13** 独立建库并升级到 v14；各库 `foreign_key_check` 无错误、部分唯一索引 16 个。没有独立 v1 schema 样本，不将其计入实测。 |

首次组合构建曾因 Room 不接受查询别名 `end` 以及改签名后遗漏的调用点中止；修复后重新构建和运行套件，未把失败运行计入通过数。

## 同签名升级与正常降级拒绝

- **Debug：** 在 v12 APK 建立锁名样本（观察序号 21、主播代际 5），不卸载升级到审查时的 v13 APK，再写入名称修订号 9。以 `adb install -r` 升级当前 APK，冷／热启动后回读 v14，身份、人工锁、序号、代际、名称修订均保留；两个新增时钟列为 `NULL`。分别正常安装旧 v12 和 v13 包（均不加 `-d`），两次均得到 `INSTALL_FAILED_VERSION_DOWNGRADE`。
- **Release：** 对旧版 `b147c69` 与当前 Release 的压缩包使用同一临时验收签名。旧源码／构建输入的 217 个文件与该提交逐字节核对（统一换行）。在 v12 写入锁名主播和带备注、时长 30 秒、修订号 3 的场次，再用 `adb install -r` 直升 v14。回读主播序号 27、代际 6、场次及备注不变，外键错误 0，部分唯一索引 16 个。正常反装旧 Release 被拒绝，新版仍为 code 8。签名仅用于模拟器验收，不能替代分发证书。

## 验收边界

修复阻止以后出现同类错误，不会凭空重建旧版已删掉的分区历史，也不批量改写缺少证据的既有场次。未做跨厂商实体机、真实 B 站账号／客户端、Root／Shizuku 保活及整夜耗电测试，未做 64 MiB 临界大库压测；模拟器通过不代表这些场景已验收。原报告列出的未归因前台启动超时仍无本次可归因复现，不算作已修缺陷。
