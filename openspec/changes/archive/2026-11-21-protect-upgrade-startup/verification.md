# 验证记录（2026-11-21）

Windows、JDK 17、Gradle Wrapper 8.11.1，API 35 独立模拟器。旧 APK 与当前 Debug APK 的签名证书摘要相同。以下均为本次执行；未操作用户手机、未测试 Release 或厂商后台。

- `gradlew.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 最终串行通过；Debug JVM 376 项，Lint 无错误（209 条警告、3 条信息）。一次并行校验遇到 Windows jar 文件占用，不算通过；以最终串行结果为准。
- `adb -s <测试机> shell am instrument -w com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner`：62 项通过。含两条 v13 迁移路径、缺失配置不补种、旧选择与默认值冲突、封禁历史往返及重复导入不翻倍。
- 真正的 `0622.5` APK 通过文件选择器恢复经授权的备份；`adb install -r` 覆盖 `0622.6` 后复现欢迎页，SQLite 仍是封禁记录分支 v13；原地覆盖 `0622.7` 后直接进首页，库为 v15。按旧 schema 的全部列和主键顺序比对配置、修订、场次、事件及封禁历史，摘要完全相同，`PRAGMA foreign_key_check` 无错误。
- 只在本次专用模拟器制造不支持的数据库版本，验证错误页没有写配置入口；还原样本版本后点“重新读取”，同一进程回到首页。这不是对用户设备修改 SQLite 的操作建议。
- 另行新装确实显示首次引导。在 `0622.7` 通过 SAF 导入原文件并重新导出，逐项比对原文件中全部六组设置和封禁历史，无字段差异。覆盖升级与 JSON 恢复是两项独立验证。测试期间关闭模拟器网络，仓库不保存私人文件、行内容或截图。
