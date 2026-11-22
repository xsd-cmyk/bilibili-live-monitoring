# 设置切入、底栏与全卡片玻璃验收

## 范围
- 原来底栏从“仍在组合的任意一级页”取路由；导航动画结束前离场页仍在组合。现在只接受当前一级目的地，或者专为当前二级栈项重新入场的真实上一页；没有测得位置时不在原点绘制。
- 设置从一个可滚动的大表单改为总开关及分组入口。监控策略包含运行模式、检查与确认、名称刷新、高级请求；通知设置包含有效期、合并、免打扰与条件展示的前台通知模板；后台运行保留权限与保活能力的选择、说明和实际状态。与旧设置共享仓储和校验，不迁库、不改备份键。
- 卡片开关覆盖列表空态、批量通知不可用提示、应用内标准对话框、时间筛选日历与列表排序菜单。只给背景加材质，系统授权窗、文件选择器、二维码及图片内容不做折射。屏幕内卡片读本窗口坐标；独立弹窗读屏幕坐标以对齐同一张壁纸。

## 验证方法
Android 15 / API 35 专用 AVD `KaoruUpgrade`、硬件 GPU、同签名 debug APK、1080×2400 / 480 dpi、手势导航。用合成壁纸、约 90 个合成主播，关闭监控；未读取或导入私人备份。

```powershell
.\gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb -s emulator-5748 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5748 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5748 shell am instrument -w com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5748 shell cmd package compile -f -m speed com.example.bilimonitor
python measure_settings.py --adb adb --serial emulator-5748
```

分别启用 0 / 28 dp，同一数据下用三个标签轮换作为来源，每组切入 9 次。每次操作清零 `gfxinfo`，0.65 秒后读取该窗口的统计，再通过界面树确认已看到「开启监控」。另从新增的有效 `framestats` 行提取本次最慢的主线程输入到提交绘制时间（`HandleInputStart → SyncQueued`）；不是端到端触摸延迟。每个窗口的 P95 是 Android 的直方图读数，下表取九次 P95 的中位数，不是合并所有帧重算 P95。

## 全玻璃实测

| 版本 | 模糊 dp | 总帧数 | 慢帧（占比） | 整帧 P95 中位数 ms | 主线程最慢帧中位数 ms | 有效主线程帧中位数 |
|---|---:|---:|---:|---:|---:|---:|
| 0622.13 | 0 | 374 | 107 (28.6%) | 109 | 594 | 4 |
| 0622.13 | 28 | 279 | 234 (83.9%) | 133 | 708 | 3 |
| 0622.14 | 0 | 240 | 104 (43.3%) | 150 | 124 | 11 |
| 0622.14 | 28 | 256 | 83 (32.4%) | 150 | 102 | 14 |

[逐次读数](settings-frames.csv)。设置切入期间，主线程最慢帧和首次从主播页进入时的停顿均缩短，尤其是带模糊时；28 dp 的慢帧占比也降低。**整帧 P95 没有同步改善**：两种模糊值都达到 150 ms，零模糊慢帧占比还高于旧版。不能据此宣称整体帧率提高或已消除卡顿。模拟器与宿主负载波动明显；早先只读直方图的预跑也有大幅波动，不与这 36 个可核实目标页面的窗口合并。尚无 90/120 Hz 真机数据。

## 界面与手势

- 实际从设置进入三个分组并返回；每次确认二级页无底栏标签，返回为「开启监控」所在的设置页。打开保活选择、取消，检查未更改选项。历史页打开排序、主播详情打开直播历史筛选与日历再取消；输入不匹配的搜索词，出现「没有匹配的主播」面板。[设置、弹窗、排序与日历截图](screens.jpg)。
- 在全玻璃「后台运行」页从系统左缘拖动再撤回，右缘拖动并提交；两侧均显示真实设置首页与随页移动的 Dock。撤回回到后台页，正文区域截图和开始时逐像素一致；提交只回到设置。[两侧预览与撤回](back-preview.jpg)。
- 弹窗像素回归以红蓝两半壁纸区分同屏与跨窗口的坐标，分别在 0 / 28 dp 下开启和关闭材质。不以“节点能点击”代替材质测试。

## 构建与回归结果

`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功；379 项 JVM 测试全部通过，Lint 0 error、212 warning。最终测试包完整设备运行 `OK (95 tests)`，86.293 秒。中间版曾因合并通知文案测试仍读取旧源码路径而失败，已改为核对实际通知页。逐帧导航回归最初按墙钟轮询，曾漏掉交接中间帧；最终测试用独立组合时钟推进每帧，保留颜色、方向、底栏归属与返回栈断言，且另外执行了真实系统手势。弹窗用例在退出 Activity 前先关闭测试窗口。

## 安装与边界
从 0622.13 / 16 覆盖安装 0622.14 / 17：相同包名和调试签名，无卸载清数据。签名证书 SHA-256：`0e54cdbb184f29dbfc0b257b33590493c3db6e79546761e132dd76c60ed7d0d5`。APK：`app/build/outputs/apk/Kaoru-0622.14-debug.apk`，SHA-256：`dd622ef4358cbc133832399533ba03e59687b86dfb60e39996d3325a8f2948eb`。

在专用测试机反复强停、冷启时新旧版均曾出现已知的 `MonitoringService` 前台服务启动超时；未到达首页的启动不作为有效样本。这不是本次 UI 修复的范围。未测厂商真机、Android 12 渲染路径及系统授权窗；未以真实直播间作为验收数据。
