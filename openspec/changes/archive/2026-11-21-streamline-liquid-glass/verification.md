# 0622.9 界面与液态玻璃验收（2026-11-21）

## 同机对照

Windows，Android 15 / API 35 专用 x86_64 模拟器，1080×2600、250 dpi；同 Debug 签名、同一测试库、90 位 `Studio` 合成主播及一条既有合成记录。未使用个人备份。监控关闭，浅色、同一张条纹壁纸、Dock 玻璃开启、卡片玻璃关闭，模糊 28 dp、体色 0.28、卡片不透明度 0.72。采样时不并行运行构建、Lint 或设备测试。

每版从主播首页开始，预热一次四标签导航和列表往返。每轮：列表 4 次上下往返（滑动 350 ms，停 700 ms）；历史→统计→设置→主播重复 3 次（点按间隔 320 ms）。每种场景开始前 `dumpsys gfxinfo com.example.bilimonitor reset`，结束时取 Android 报告。准备相同样本并将 SDK platform-tools 加入 PATH 后，执行 `python benchmark.py <设备序列号> <输出前缀>` 可生成截图和 JSON。脚本不导入样本或改偏好。

| 场景 | 每轮 P50：旧 → 新（ms） | 每轮 P95：旧 → 新（ms） | 三轮中位 P50 / P95 | Android 标记的卡顿帧占比，三轮合计 |
| --- | --- | --- | --- | --- |
| 主播列表滚动 | 77、65、69 → 53、44、34 | 133、89、97 → 117、65、48 | 69 / 97 → **44 / 65** | 80.4% → 30.7% |
| 四标签切换 | 69、73、77 → 32、34、34 | 200、133、150 → 150、150、150 | 73 / 150 → **34 / 150** | 84.1% → 23.9% |

[逐轮原始数据](frame-samples.json)也保留了一版未交付的整屏交叉淡入淡出方案：切页 P95 中位数反升至 250 ms，因此最终版去掉一级页面叠绘，只动选中胶囊，详情页仍有短淡入淡出。新旧切页 P95 **持平**，并非所有帧都变快。这是 Debug 模拟器的绘制帧统计，不是点击到可交互时延，不能据此承诺真机帧率。

[同一壁纸和列表的 Dock 实拍对比](dock-comparison.png)：旧栏透出清晰的卡片边和文字；新版保留底图颜色、底缘折光和方向性高光，底栏图标与文字未被模糊。图片仅裁取模拟器画面并加版本标记。

## 构建与回归

- `gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 成功。JVM **379** 项，无失败、错误或跳过；包括首页列表筛选、排序和封禁标记范围的新回归。Lint **0 错误、210 警告、3 提示**。
- `aapt dump badging`：`com.example.bilimonitor`，`0622.9` / code 12，minSdk 31、targetSdk 35。`apksigner verify` 通过。交付件 `app/build/outputs/apk/Kaoru-0622.9-debug.apk` 与已安装的构建输出 SHA-256 均为 `646F0D2A1A2CD76B06D5BDCE038607CDD7F4482BA0025D98051E2E3B4128653F`。
- `adb -s emulator-5748 install -r` 从 `0622.8` 升到 `0622.9`，在未重写样本偏好时确认已有合成主播仍在、能直达首页。未变更数据库版本、业务备份、监控配置或迁移逻辑。

- `adb -s emulator-5748 shell am instrument -w com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner`：最终构建 **82 项设备测试通过**，含通知深链、真库、封禁历史和名称刷新回归。
- 用同一台模拟器切换亮/暗、零模糊、无壁纸、35% 壁纸不透明度、关闭 Dock 玻璃、仅卡片磨砂及普通底栏；逐项检查截图与导航元素。[玻璃边界截图](dock-modes.png)中零模糊仍有边缘折射，未模糊文字图标。已从主播卡打开详情核对底栏消失，再返回首页；系统动画倍率设为 0 后重复点历史、切统计，验证选中语义正确，随后恢复原系统设置。
- OpenSpec 单项、全量严格校验及 `git -c core.whitespace=cr-at-eol diff --check` 为本次文档与规范门槛。

## 限制与独立发现

- 在关闭监控的测试库上，覆盖安装后立即启动或强行停止后再启动，偶发 `MonitoringService` 未及时进入前台，被系统终止进程。相同操作用 `0622.8` 也复现相同异常；重新打开可进入原首页。记录为后台启动竞态，不归因于着色器，本次不改后台服务。
- 仅有 API 35 镜像：Android 12 的局部磨砂降级路径已编译，未在 API 31/32 设备实测；未测物理 GPU、高刷真机、不同厂商或 Release 性能。
