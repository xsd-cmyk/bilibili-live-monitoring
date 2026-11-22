# 0622.11 · Dock 触控与镜片验收

环境：Windows / JDK 17，独立 Android 15 API 35 x86_64 模拟器，合成主播列表与合成壁纸。用 `adb install -r` 由 `0622.10` 覆盖至 `0622.11`，未清数据，仍在首页，使用原测试外观。

## 实测

- 960×2160、400dpi、字号 1.0，直接读取可访问标签边界。旧版首项 `[110,1809][295,1960]`，新版 `[50,1795][265,1995]`：约 74×60.4dp → 86×80dp。设备回归在上下沿内侧 1dp 注入短按，分别切到目标页；超出上沿 3dp 释放则不提交。
- 玻璃开启、模糊为 0，依次 `adb shell input motionevent DOWN/UP` 并用 `adb exec-out screencap -p` 取同位置静止、拿起、松手画面。首项中轴 25 像素宽带的 RGB 差分从 y=1779 到 2011，较轨道上下多 6.4/6.8dp（含亮边），释放后此带无残差。[截图：上为静止，下为长按](dock-rest-held.png)。
- `DockGestureInstrumentedTest`：500ms 系统阈值时按 300ms 后可跨项提交，35ms 就滑动不拾起；系统 1000ms 时 350ms 不拾起、1120ms 可提交。同时回归快速改选、语义点击、RTL、取消、返回起点、突出部分松手。
- 深色、字号 1.6、开卡片与 Dock 玻璃、12dp 模糊，四项仍各 215×200 像素；滚动切页及动画倍率 0 的长按滑到设置正常。

- 切回模拟器原显示设置（1080×2600、250dpi）并实际滚到底：轨道上沿 y=2358，最后一位主播名底边 y=2234、添加按钮图标底边 y=2285、设置最末说明段底边 y=2244，均在栏体上方。

## 构建与边界

- `gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 成功；379 项 JVM 测试均通过；Lint：0 错、210 条警告、3 条提示。
- `adb -s emulator-5748 shell am instrument -w com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner`：92 项通过，含本次两组 UI 用例。
- `aapt dump badging`：`0622.11` / 14；`apksigner verify --print-certs` 与上版 debug 包签名相同。交付包 `Kaoru-0622.11-debug.apk` SHA-256：`3e63d89bd3c13360fbaee829a99dbd58bdc1abdb6dad6146dd2fe4df1b70ce73`。
- 本次未在实体手机或 Android 12 上验手感，未采集帧时间，不宣称帧率提高。
