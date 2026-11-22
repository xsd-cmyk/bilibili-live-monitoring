# 0622.8 验收（2026-11-21）

环境：Windows、JDK 17、项目 Gradle Wrapper，Android 15 / API 35 专用 x86_64 模拟器。仅使用合成记录，未使用用户备份或操作用户手机。

| 检查 | 命令及结果 |
| --- | --- |
| 构建与静态检查 | `gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 成功。JVM 377 项，无失败、错误或跳过；Lint 0 错误、209 警告、3 提示。 |
| 设备回归 | `adb -s emulator-5748 shell am instrument -w com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner`：82 项通过，其中 20 项封禁链路回归。覆盖真实 Room、批量明确封禁优先于矛盾的在播、下播候选探针、探针失败、失效请求、事务回滚、复查去重、解封再开播、长盲区、人工结束、删除、备份和 HTML 导出。 |
| 覆盖安装 | `0622.7` 的 v15 库含一条合成的在播场次和已确认封禁标记。未卸载，使用 `adb install -r` 覆盖 `0622.8`。初版迁移因运行时部分索引未临时移除而失败，事务回滚到 v15。按既有迁移约定修正并重试后升级为 v16，原主播仍在，下一轮巡检以首次证据结算为 60 秒、OFFLINE、ROOM_BANNED。`PRAGMA integrity_check` 为 ok，`foreign_key_check` 无行。 |
| 界面 | 通过模拟器界面打开历史：一级列表显示「已记录封禁 1 次」，二级显示「因封禁结束」及起止时间，明细显示首次确认时间和当前封禁状态。测试库此前未完成首次设置，首次设置正常完成后可进入历史。 |
| 产物 | `aapt dump badging` 确认 versionName 0622.8、versionCode 11；`apksigner verify --print-certs` 验证与本次覆盖的 0622.7 Debug 签名相同。 |

未验证：真实平台触发封禁、不同厂商后台、Release 签名分发。无确认证据的历史不回推；测试不代表能检测到设备离线期间发生并解除的封禁。
