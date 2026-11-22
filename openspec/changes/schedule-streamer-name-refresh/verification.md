# 名称刷新验收记录（2026-11-20）

环境：Windows、Microsoft OpenJDK 17.0.19、Gradle Wrapper 8.11.1、SDK 35。设备为以只读副本启动的 `BiliMonitor_Test`，Android 15 / API 35，未使用个人账号。

| 检查 | 操作与结果 |
| --- | --- |
| 编译与 JVM | `gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 成功；最终 JVM 报告 373 项，失败、错误、跳过均为 0。覆盖间隔边界、同次开机调钟、跨开机及备份新旧值。 |
| 设备测试 | `gradlew.bat --offline :app:connectedDebugAndroidTest` 最终 39/39 通过、0 跳过。默认 Gradle 缓存首次缺 UTP 依赖，改用本机已备齐的 Gradle 缓存后通过。新测覆盖模拟远程混合响应、断网、重开库、自定义间隔、在途人工修改与删除恢复、维护围栏、并发手动／自动请求和 v12 迁移。 |
| 真实覆盖安装 | 在测试模拟器安装 0622.4，确认 `PRAGMA user_version` 为 12，添加一项人工锁名样本；执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 后版本为 13，原名和锁均保留，刷新时间未知，配置为 24/1，16 个部分索引存在，`PRAGMA foreign_key_check` 无结果。另用独立空库验证新安装，不以清库代替迁移。未验证其他分支的同号数据库。 |
| 设置与立即获取 | 模拟器不登录，暂停直播监控，测试主播保留缓存名。飞行模式下在设置页点击全量按钮，显示「成功 0，待重试 1，跳过 0」；原名不变。正常周期填 0 显示范围错误，保存 6 小时成功；冷启动后读数为 6/1。 |
| 后台重试 | 恢复联网，冷启动并返回桌面；默认一分钟到期后系统调度执行 `NameRefreshWorker`。同一开机两次完成间隔约 82 秒，记录版本从 1 到 2，名称保留、直播观察序号仍为 8。此验证不承诺后台准点。 |
| 规范与改动 | `openspec validate --all --strict --no-interactive`：9 项通过。仓库按文件保留 CRLF/LF，使用 `git -c core.whitespace=cr-at-eol diff --check` 检查，未发现空白问题。 |

未测：物理设备、厂商后台清理、连续 24 小时自然运行、实际主播改名。成功、空白、身份错配等返回值用固定响应复现，不依赖线上账号。手工准备模拟器及切换运行状态时另观察到 `MonitoringService` 前台启动超时；未确认复现条件，与名称重试分开记录。
