# 0622.10 · Dock 交互验收（2026-11-21）

## 行为
- 短按由原生 `selectable` 提交导航；键盘/辅助功能仍走这条入口。玻璃选中镜片放大的是已绘制的栏体，不是图标文字。首尾两条有限弹簧令镜片横向拉伸再收拢，反向改选时直接改变目标。
- 悬浮 Dock 的玻璃开关不影响手势。长按超过系统阈值才拿起镜片；滑动期间不导航，栏内释放时提交落点，系统取消或栏外释放时复位。贴底 Material 导航没有挂载这套手势。

## 自动化
Windows、Gradle Wrapper、API 35 专用 `KaoruUpgrade` x86_64 模拟器，使用合成主播和测试壁纸，不使用个人备份或操作手机。

- `gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 构建成功，379 项 JVM 测试通过，无跳过；Lint 0 错误、210 警告、3 提示。
- `adb -s emulator-5748 shell am instrument -w -e class com.example.bilimonitor.ui.nav.DockGestureInstrumentedTest com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner`：4 项通过。用真触摸事件检查取消、栏外抬手、滑回原标签、长按不移动、点按改选、辅助功能点击、LTR/RTL 连续跨标签。断言的是提交列表，不仅检查动画最后停在哪。
- `adb install -r` 保留 `0622.9` 的应用数据。`aapt dump badging` 确认 `0622.10`／code 13，`apksigner verify --print-certs` 检查与 `0622.9` 的 Debug 签名相同。命名 APK 与安装使用的构建输出 SHA-256 均为 `97AF835D97F1C2D5E37AB19DE3B62E601F5A3B4765C803C26081D4917031922B`。

- `adb -s emulator-5748 shell am instrument -w com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner`：最终包 86 项设备测试通过，包含上述四项。

## 实际界面
[模拟器屏幕录制：点击与长按拖动](dock-motion.gif)。在真实导航页运行 `adb shell input tap`、`input draganddrop`，并单独注入 DOWN/MOVE/UP：在「主播」页按住「历史」后移到「统计」，松手前界面仍选中「主播」，松手后才选中「统计」。录屏裁取 Dock，未用示意图替代。

深色玻璃和关闭玻璃的悬浮 Dock 都能拖到「设置」；同一手势在普通贴底栏不切页，三种模式的点按均正常。系统动画倍率临时设为 0，仍可长按拖动并选择目标；验收后恢复原设置。

## 同机帧耗时
两版使用同台模拟器、同一合成列表及条纹壁纸、浅色玻璃 Dock、28 dp 模糊与 250 dpi。分别预热后运行三轮[固定手势脚本](../2026-11-21-streamline-liquid-glass/benchmark.py)；每轮单独重置 `dumpsys gfxinfo`。三轮百分位的中位数列在下表，卡顿比例由总卡顿帧数除以总帧数。[逐轮样本](frame-samples.json)。

| 场景 | P50 毫秒：旧 → 新 | P95 毫秒：旧 → 新 | 卡顿帧比例：旧 → 新 |
| --- | --- | --- | --- |
| 主播列表滚动 | 57 → 32 | 93 → 48 | 61.5% → 17.7% |
| 切换四个标签 | 42 → 36 | 450 → 105 | 86.3% → 25.6% |

模拟器和 Debug 调度波动较大，不从此对照推算真机帧率。这是滚动和点按切页的绘制统计，不是长按识别时延。

## 范围
本次不变更后台服务、数据库、备份或既有的 API 31/32 磨砂降级。未测 Android 12 实机、具体厂商的高刷新率 GPU、TalkBack 用户端或 Release 性能。
