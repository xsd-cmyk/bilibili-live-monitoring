# 0622.12 · 页面转场、系统预测性返回及直接滑选

Windows、JDK 17、Android 15 / API 35 专用 x86_64 模拟器，使用合成主播列表与合成壁纸。没有使用用户备份、账号或真实直播间。此次只改导航与呈现。

## 交互验收

- 在 960×2160、400dpi 的屏幕，从首页向统计注入 **90ms** 横滑，释放后统计被选中；无长按等待。镜片预览经过中间入口不导航。8 项 Dock 设备用例含立即横拖（系统长按阈值设为 1500ms）、点击与语义点击、上下沿、栏外释放、纵向、第二指介入、系统取消、RTL。系统动画倍率设为 0 后也能切到设置，再返回首页。
- 点历史、连续改选设置/统计、直接拖回首页、进入主播详情并从屏幕边缘返回：[模拟器录屏](navigation-demo.mp4)。壁纸与底栏不随单个页面滑走，子页进退的两个内容区域相邻而不叠字。
- 使用专用机的系统手势导航、启用系统返回动画，从**左侧屏幕边缘**起滑，停住可见上一页，向起点撤回再松手仍在详情；从**右侧屏幕边缘**滑入完成，回到首页。[从左至右：详情／预览中途／取消后](back-cancel.jpg)。恢复原系统导航模式、屏幕规格和动画设置后，分别用 `KEYCODE_BACK` 和工具栏返回按钮验证详情回首页。
- `PageNavigationInstrumentedTest` 用生产 `PageNavHost`、三个纯色目的地和 Activity 返回分发器，在 0.18 与 0.65 进度时用 `PixelCopy` 检查上一页可见面积增长，期间栈顶仍是子页；取消恢复全部原色，再次返回只弹一层。早期用固定中点像素去推断进度的断言不稳定，最终以两次预览的可见面积变化及真实栈顶为判据。

## 构建与覆盖安装

- `gradlew.bat --offline --console=plain :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 成功；最终更新测试后单独重建测试包并运行 Lint。379 项 JVM 用例均通过，无跳过；Lint 为 0 错、211 警告、3 提示，新增的一条说明返回 opt-in 属性仅在 API 33+ 使用（minSdk 31）。
- `adb -s emulator-5748 install -r app/build/outputs/apk/debug/app-debug.apk`，从 `0622.11` 覆盖安装最终应用包成功，原列表与玻璃外观仍可用。安装测试包后，`adb -s emulator-5748 shell am instrument -w com.example.bilimonitor.test/androidx.test.runner.AndroidJUnitRunner` 全套 **93/93 通过**。
- `aapt dump badging`：`com.example.bilimonitor`、`0622.12` / 15。`apksigner verify --print-certs` 验证与 `0622.11` debug 包同签名。`Kaoru-0622.12-debug.apk` 的 SHA-256 为 `65a8075a7c459dd3a82a3fe838cd47d7c23323710ce0ef74fe63fb7d2b6685cb`。
- 未实测实体手机、其他 Android 版本、各厂商返回手势或帧率。预测进度取决于系统与导航方式；不具备进度时走常规返回，不自行拦截系统边缘。
