# 图标 / 名称 预设（icons/）

## ★ 新手请直接双击项目根目录的 `管理图标.cmd`

不用记任何参数：双击之后会出现一个中文菜单，按编号选就行（选 0 退出）。
为什么是双击 `.cmd` 而不是这个目录里的 `.ps1`：Windows 双击 `.ps1` 默认用记事本打开，
而且执行策略通常会拦住它 —— `.cmd` 已经带好了 `-ExecutionPolicy Bypass`，双击就能用。

| 菜单 | 做什么 |
| --- | --- |
| `1` | 添加图标（从文件夹批量导入，支持子目录、复制不删除原图） |
| `2` | 添加图标（打开 `icons/presets/` 文件夹，自己把图拖进去） |
| `3` | 添加 / 修改应用名称（写进 `icons/names.txt`，只改需要改的那一行） |
| `4` | 删除某个图标或名称（会二次确认，输入 `y` 才删） |
| `5` | 查看全部预设：图标/名称编号 + 组合对照表（只看，不改任何文件） |
| `6` | 生成 APK：重新生成 -> 编译出安装包 -> 打印安装包完整路径（不自动装机） |
| `7` | 清空全部预设（回到默认图标与名称，破坏性操作，要连续确认两次） |
| `8` | 使用说明（图片要求、出错怎么办、怎么装到手机） |
| `0` | 退出 |

- 图片要求：**PNG / JPG / JPEG**（WebP 请先转 PNG），正方形最好（不是正方形会自动按短边居中裁剪）；
  尺寸建议 `>= 432x432`，短边小于 192 会提示"太小、放上去会糊"但仍会生成。
- 改完图标或名字**一定要选 `6` 重新生成 APK**，再自己装到手机上（应用数据保留，不用先卸载）。
- 出错时菜单会先给一句**中文结论**（例如"这张图不是正方形，已按短边裁掉多余部分"），
  后面灰色的技术细节看不懂可以忽略，需要求助时截图发出来。
- 本 README 是 `tools/generate-icon-presets.ps1` **每次运行自动重写**的，手改会被覆盖；
  下面的"参数化用法"给进阶用户，平时不用看。

---

## 参数化用法（进阶）

两个**独立可切换的轴**：

- **图标轴** `i`：`i=0` = 应用自己的图标；`i>=1` = `icons/presets/` 里第 `i` 张图（按**文件名**排序）。
- **名称轴** `j`：`j=0` = 应用自己的名字（`@string/app_name`）；`j>=1` = `icons/names.txt` 的第 `j` 个有效行。

目录 `icons/presets/` 与文件 `icons/names.txt` 是**单一事实源**；`app/src/main/res/mipmap-*/ic_launcher_preset_*.png`、`res/values/icon_presets.xml`、`AndroidManifest.xml` 里的别名区块全是生成物，不要手改。

## 一、为什么是"笛卡尔积"

`<activity-alias>` 的 `android:icon` 与 `android:label` 是**同一个组件上的两个属性**，系统没有"运行时只改其中一个"的接口。想让图标和名字各自独立切换，只能为每个组合 `(i, j)` 各生成一个别名 `IconPreset_<i>_<j>`，运行时启用哪一个，就等于同时选中了那一对。

- 格子 `(0,0)` **不生成别名** —— 它就是主入口 `MainActivity` 本身。
- 所以别名总数 = `(N+1) × (M+1) − 1`（N = 图标数，M = 名字数）。
- 例：N=3、M=2 -> `4 × 3 − 1 = 11` 个别名，位图资源 `3 × 5 = 15` 个（每张图 5 个密度）。

## 二、怎么用

```powershell
# 方式 1（推荐）：把图放进 icons/presets/、名字写进 icons/names.txt，然后照常构建安装
# （build-and-install.ps1 会先自动调用本脚本重新生成）
.\build-and-install.ps1

# 方式 2：只重新生成，不构建（改完图/名字想立刻核对清单与资源时用）
powershell -File tools\generate-icon-presets.ps1

# 只打印计划不写盘
powershell -File tools\generate-icon-presets.ps1 -DryRun

# 方式 3：回到「零预设」状态（清空 icons/presets/ 的图 + 全部生成物 + 别名区块）
powershell -File tools\generate-icon-presets.ps1 -Clear
```

每次运行结束都会打印**矩阵规模**与下标对照表，例如：

```text
发现 3 个图标 × 2 个名字 -> 生成 11 个别名（(3+1)×(2+1)−1）、15 个位图资源、3+2 条轴条目、11 条矩阵条目（旧的 0 个已清理）
矩阵规模：(N+1) × (M+1) − 1 = 4 × 3 − 1 = 11 个别名
    i\j     [0]             [1]             [2]
    [i=0]   (0,0)           IconPreset_0_1  IconPreset_0_2
    [i=1]   IconPreset_1_0  IconPreset_1_1  IconPreset_1_2
    ...
```

## 三、名称轴：icons/names.txt 的格式

一行一个名字（UTF-8）。`#` 开头的行是注释，空行忽略，首尾空白自动去掉，**重复名只保留第一次出现**（去重不看大小写：`CCTV` 与 `cctv` 在桌面上根本分不开）。

```text
# 一行一个「应用名称」；# 开头是注释，空行忽略，重复名只保留第一个
主播监控
直播提醒
开播啦
```

- 名字里的 `&` `<` `>` `"` 会被自动 XML 转义，可以放心用。
- 名字里**不能有** `|`（它是 `icon_presets.xml` 里矩阵/轴的字段分隔符）或制表符：脚本会报错并逐条列出非法名（不静默丢弃）。
- `icons/names.txt` **不存在或全为空**是正常状态：`M=0`，退化成"只有图标轴"（仍生成 `IconPreset_i_0`）。
- 名字只影响别名的 `android:label`；它同时决定桌面上显示的应用名，所以一个"名字档"就是一个独立的桌面入口（可以和图标档任意组合）。

## 四、图标轴：icons/presets/ 里的图片

| 项 | 要求 |
| --- | --- |
| 格式 | PNG / JPG / JPEG（**WebP 请先转成 PNG** —— GDI+ 在 Windows 上解不了 WebP；.webp/.bmp/.gif 等会报错，其它扩展名按「非图片」跳过） |
| 形状 | **正方形**。不是正方形会按短边居中裁剪，并在摘要里逐条列出 |
| 尺寸 | 建议 >= 432x432（最高密度 xxxhdpi 需要 192x192）；短边 < 192 会**警告**但不阻止 |
| 编号 | 按**文件名**排序后的第 i 张 = 图标档 `i`（`ic_launcher_preset_<i>`）；显示名默认取文件名（去扩展名），可用同名 `.json` 覆盖 |
| 显示名 | 不能为空、不能含 | 或换行/制表符 |

可选：放一个与图片同名的 `.json` 覆盖显示名，例如 `cat.png` + `cat.json`：

```json
{ "displayName": "猫耳" }
```

- 当前只支持 `displayName` 一个字段；其他字段会被忽略并打印警告（不报错）。
- 排序键始终是**文件名**，`displayName` 只改显示，不影响编号顺序。

## 五、批量导入图标（把别处目录里的一堆图一次导进来）

```powershell
# 把一个目录（含子目录）里的图片全部复制进 icons/presets/，然后生成
powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标'

# 多个来源目录：-Source 可重复传，也可以逗号/空格分隔
powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -Source 'E:\另一批'

# 只扫这一层（不进子目录）/ 移动而不是复制 / 确认覆盖同名不同内容的文件
powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -NoRecurse
powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -Move
powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -Force
```

同名冲突的确定性规则：目标名冲突时按 `名字` -> `名字-2` -> `名字-3` … 找第一个空闲名，
每次改名都逐条打印（**图标显示名随之变化**）；内容完全相同（SHA-256）的图片视为已导入，
跳过并列出 —— 所以同一批图重复跑多少次，结果都一样（幂等）。

## 六、矩阵规模与「超过 60」警告

- 矩阵格数 = `(N+1) × (M+1)`，别名数 = 格数 `− 1`（扣掉 `(0,0)`）。
- **`(N+1)(M+1) − 1 > 60` 时脚本会打印黄色警告，但不阻止**：别名是真实的 `<activity-alias>` 组件，每一个都会被 `PackageManager` 与所有启动器枚举，几百个会明显拖慢安装/枚举，部分启动器还会把图标塞进"工作资料/第二页"之类的角落。**这是提醒你"是不是加太多了"，决定权在你。**
- 参考量级：`3 图标 × 2 名字 = 11`（舒服）；`8 × 8 = 80`（开始肉疼）；`20 × 20 = 440`（不建议）。

## 七、生成物（都能随时删掉重新生成）

| 产物 | 说明 |
| --- | --- |
| `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_preset_<i>.png` | 48 / 72 / 96 / 144 / 192 px，`HighQualityBicubic` + `HighQuality` PixelOffset + `AntiAlias`；`i` 从 1 开始（`i=0` 就是应用自己的 `mipmap-*/ic_launcher`，不重复生成） |
| `app/src/main/res/values/icon_presets.xml` | 三个数组：`icon_axis`（`资源名\|显示名`，第 0 项资源名留空）、`name_axis`（第 0 项空字符串）、`preset_alias_matrix`（`i\|j\|组件名`） |
| `AndroidManifest.xml` 里 `BEGIN/END GENERATED ICON ALIASES` 之间的区块 | `(N+1)(M+1)-1` 个 `<activity-alias>` |
| `publish/` 下的镜像副本 | manifest / res / tools / 本 README / `names.txt` / build-and-install.ps1（`publish/icons/presets/` 里的图片**不镜像**，那是本地素材） |

每次运行都**整块重建**：图标减少时旧的 `ic_launcher_preset_M.png` 会被删掉，别名区块整体重写，不会留下指向不存在资源的别名（那种 manifest 会安装失败）。manifest 用 XML 解析 + 重新解析校验，非法就放弃写入并保留原文件。

## 八、已知限制

1. **新增/改名/删名都必须重装 APK**（`adb install -r`，**应用数据保留**）。别名是清单里的组件，只能随 APK 一起进系统；`build-and-install.ps1` 本来就会装，正常流程无感。
2. **release 构建开了资源压缩**（`app/build.gradle.kts` 里 `isShrinkResources = true`）：运行时用 `getIdentifier` 按名字找资源，压缩器看不到这种引用，会把预设图标当无用资源删掉。将来真发 release 时需要新建 `app/src/main/res/raw/keep.xml`：

   ```xml
   <resources xmlns:tools="http://schemas.android.com/tools"
              tools:keep="@mipmap/ic_launcher_preset_*" />
   ```

   **本阶段刻意不创建这个文件**，只在此提醒。debug 构建不受影响。
3. 别名越多，`PackageManager` 与启动器的枚举开销越大：见第六节的 60 阈值。
4. 这些别名是 `android:enabled="false"`，只有在运行时用 `PackageManager.setComponentEnabledSetting` 启用之后才会出现在启动器里。

<!-- 下面是本次生成区块的实际内容，仅供人工核对；改它没用，下次生成会覆盖 -->

```xml
<!-- BEGIN GENERATED ICON ALIASES：由 tools/generate-icon-presets.ps1 生成，请勿手改 -->

    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_0_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_0_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_0_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_1_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_1" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_1_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_1" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_1_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_1" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_1_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_1" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_2_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_2" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_2_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_2" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_2_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_2" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_2_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_2" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_3_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_3" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_3_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_3" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_3_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_3" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_3_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_3" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_4_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_4" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_4_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_4" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_4_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_4" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_4_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_4" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_5_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_5" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_5_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_5" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_5_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_5" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_5_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_5" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_6_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_6" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_6_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_6" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_6_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_6" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_6_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_6" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_7_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_7" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_7_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_7" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_7_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_7" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_7_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_7" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_8_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_8" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_8_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_8" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_8_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_8" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_8_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_8" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_9_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_9" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_9_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_9" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_9_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_9" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_9_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_9" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_10_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_10" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_10_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_10" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_10_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_10" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_10_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_10" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_11_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_11" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_11_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_11" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_11_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_11" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_11_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_11" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_12_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_12" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_12_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_12" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_12_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_12" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_12_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_12" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_13_0" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_13" android:label="@string/app_name"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_13_1" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_13" android:label="主播监控"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_13_2" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_13" android:label="直播通知器"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>
    <activity-alias xmlns:android="http://schemas.android.com/apk/res/android" android:name="IconPreset_13_3" android:enabled="false" android:exported="true" android:targetActivity=".MainActivity" android:icon="@mipmap/ic_launcher_preset_13" android:label="豆豆知道了"><intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter></activity-alias>

<!-- END GENERATED ICON ALIASES -->
```

