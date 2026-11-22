# 编译、测试与验收

以下命令从仓库根目录执行，示例使用 Windows PowerShell。只核对文档时做到第一步即可；编译成功、JVM 测试通过、模拟器通过、真机通过是四种不同的证据。

## 一、验证 OpenSpec 与改动范围

```powershell
openspec list --specs
openspec validate --all --strict --no-interactive
git diff --check
git status --short --untracked-files=all
```

预期列出本基线的八项能力，每项通过严格校验。查看未跟踪文件，不能只看 `git diff`；文档任务不应产生 Kotlin、资源、Room schema、Gradle 配置或 APK 的变更。校验器检查结构，不判断场景是否符合源码。

## 二、准备环境并编译

- 使用 JDK 17、Android SDK Platform 35 及匹配构建工具；连接设备另需 SDK Platform-Tools。首次构建需能取得 Gradle 发行包及 Gradle/Google Maven 依赖，或已有完整本机缓存。
- 按 [local.properties.example](../local.properties.example) 在本机 `local.properties` 填写真实 `sdk.dir`，或配置 `ANDROID_HOME`；勿提交本机路径。使用仓库 Wrapper（Gradle 8.11.1），不依赖历史文档中的便携依赖包或 `dist/`。工程参数以 [app/build.gradle.kts](../app/build.gradle.kts) 和 [版本目录](../gradle/libs.versions.toml) 为准：`minSdk 31`，`compileSdk/targetSdk 35`。

```powershell
java -version
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

预期 `BUILD SUCCESSFUL`，应用包位于 `app/build/outputs/apk/debug/app-debug.apk`。这是编译应用与设备测试包，**没有执行测试**。在类 Unix 环境可用 `bash ./gradlew` 替换 `.\gradlew.bat`。如需检查发布变体，执行 `:app:assembleRelease`；缺少本机 `keystore.properties` 或 `BILIMONITOR_*` 签名材料时 release 不签名，不将其当作可覆盖安装的发布包。

## 三、分层测试

| 层次 | 命令（PowerShell） | 检查点 |
| --- | --- | --- |
| 纯 JVM 定向回归 | `.\gradlew.bat :app:testDebugUnitTest --tests "*StateConfirmationPolicyTest"` | 先核对首次观察、阈值、轮播与失败；按变更换成对应测试类。 |
| 全部 JVM 用例 | `.\gradlew.bat :app:testDebugUnitTest` | 策略、时钟、输入、校验、SQL 契约等。报告：`app/build/reports/tests/testDebugUnitTest/index.html`。检查用例数、失败和跳过，而非只看编译。 |
| Android 静态检查 | `.\gradlew.bat :app:lintDebug` | 阅读 `app/build/reports/lint-results-debug.html`。Debug、Release 均以 Lint 错误阻断，警告不阻断；同时核对 XML 报告的错误数。 |
| 真库与设备导航 | `adb devices -l` 后执行 `.\gradlew.bat :app:connectedDebugAndroidTest` | 使用已授权、状态为 `device` 的 API 31+ 测试机或模拟器；Android 15 可对照发布记录。报告在 `app/build/reports/androidTests/connected/` 下；确认未因没有设备而跳过。 |

[JVM 测试](../app/src/test/java/com/example/bilimonitor/)中有些用例只检查 SQL 字符串，不证明 SQLite 真正执行约束。[设备测试](../app/src/androidTest/java/com/example/bilimonitor/)中 `NotificationAggregationInstrumentedTest` 也覆盖维护竞争、旧历史检索、备注、时钟和诊断读数；`NotificationNavigationInstrumentedTest` 覆盖冷启动回退；`DiagnosticExporterTest` 检查真实 ZIP 内容及凭证边界。它们不能替代实际 B 站客户端、OEM 调度或旧数据库覆盖安装。

## 四、按场景验收

使用专用测试设备或模拟器、可控主播和无敏感数据的样本。首次安装和升级要分开验收：新安装检查种子及引导；升级时先在旧版导出备份并记录样本，保留安装数据，用**同签名**的新 APK 执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。卸载重装不能证明迁移成功。

| 场景 | 操作 | 通过依据 |
| --- | --- | --- |
| 首次与重新添加 | 不登录，用房间号／链接添加，重复添加；软删除后再添加。 | 同一 UID 不新增重复行；恢复后原场次可查。选择“不启用保活”须确认风险。 |
| 状态、盲区与恢复 | 在受控状态序列或策略测试中复现“首次已在播”“下播阈值 2，中间失败”“从在播转轮播”；在设备上断网再恢复。 | 首次不误发开播；失败不凑次数；轮播不是在播；恢复时按当前可靠区间判断资格，而不是补造盲区内的开关播。 |
| 通知与时钟 | 用设备测试构造阈值与阈值加一的批次；点开播、下播、批量及已清理目标。在有待发项时前后调钟，再还原。 | 单条／整批按严格阈值分流；批量先见成员。下播到对应场次；找不到给出回退；同次开机不因墙钟跳变重复投递或意外过期。 |
| 历史、统计与导出 | 仅改备注并清空；查找首批之外的旧标题。选有／无记录的两位主播导出，断网打开 HTML。 | 备注可清空且时间来源不变；旧场次可查；有数据时保留空分组，历史和统计文件可区分，图表不需联网。 |
| 备份、维护与迁移 | 在测试机预检损坏的副本；用有效备份恢复不同调度模式；导出期间退出页面。覆盖升级到当前数据库版本后核对主播、场次、备注与设置，并验证普通反向安装被拒绝。 | 损坏文件不会写库；成功恢复后调度与新配置一致；取消不遗留维护锁。旧样本保留、应用可再次检查；必要时对比 schema、部分索引并执行 `PRAGMA foreign_key_check`。 |
| 诊断与保活 | 暂停、恢复主播并导出诊断；切换通知权限、亮／锁屏、联网／离线，再切换省电模式。 | 人数和实际能力如实显示；ZIP 有通知摘要且无凭证；没有对象、亮屏或断网时释放额外唤醒锁。记录拒绝或降级，不能用一次通知推定整夜可靠。 |
| 账号及外观 | 使用测试账号拉取候选，勾选部分后导入；另一次预览后登出。切换背景、导航样式、预置图标，再重启。 | 只导入选择项，过代任务不能写入；退出不丢本地历史。外观持久化且与监控无关；预置启动入口与自定义快捷方式分别检查。 |

真实客户端跳转、厂商后台清理、Root／Shizuku 及长时间功耗，按 [0622.3 通知与功耗清单](../docs/TEST_CHECKLIST_0622.3.md) 和 [0622.4 升级与回归清单](../docs/TEST_CHECKLIST_0622.4.md)执行。记录版本／签名来源、设备／系统、模式与间隔、确认与送达时间、预期／实际落点、锁屏及网络状态；功耗需提供对照时长与系统电池页，无分项实测则写“待确认”。

## 验证记录如何解读

- **历史发布证据：** [缺陷台账 H78/H79](../docs/DEFECT_LEDGER.md) 记录 `0622.4` 通过 371 项 JVM 用例、35 项 Android 15 模拟器用例以及 v11→v12 覆盖安装。2026-10-29 用户反馈测试完成、未发现问题，未提供逐机型、逐项或量化功耗结果。
- **初始文档基线：** 执行 OpenSpec 严格校验并核对文档链接和改动范围；未重新构建 APK、运行 Gradle 测试或做设备验收。历史结果不是本次测试报告。

- **1120.1 修复验证：** [审查修复与验收](../docs/reviews/2026-11-20/closure.md)记录真库迁移、同签名覆盖安装、按变体统计的 JVM/Lint 结果及仍待真机确认的范围。
- **0622.7 升级验证：** [缺陷台账 H81](../docs/DEFECT_LEDGER.md)区分真实 `0622.5` v13 封禁历史库与开发树同号名称刷新库。覆盖升级检查当前设置和历史；独立的全新安装用 JSON 恢复后再次导出，不能替代覆盖升级证据。
