# ==============================================================================
#  制作依赖包.ps1 —— 把"编译 APK 所需的全部工具链"打包进项目文件夹里的 build-env\
#
#  为什么需要它：
#    管理图标.cmd 那套东西要在**别人的电脑**上也能编译出 APK。而那台电脑通常没有
#    JDK、没有 Android SDK、也没有已经下载好的 Gradle 依赖 —— 三个缺一个就编不出来。
#    本脚本把这三样 + Gradle 发行包一起复制到 <项目根>\build-env\，之后整个文件夹
#    可以整体拷贝/压缩发给别人，对方**不用装任何东西**（也不需要联网）。
#
#  它在哪台机器上跑：
#    **有完整工具链的那台**（也就是开发机：装了 JDK、Android SDK，并且至少成功编译过一次）。
#    跑一次即可；以后依赖升级了再跑一次（是增量覆盖，不会重复下载）。
#
#  用法：
#    powershell -NoProfile -ExecutionPolicy Bypass -File tools\制作依赖包.ps1
#    可选参数 -TargetRoot <文件夹>   （默认 = 本脚本所在树的根目录）
#
#  ★ 别把它 build-env 提交进 git：JDK 里的 lib\modules 单个文件就有 120MB 以上，
#    超过 GitHub 的 100MB/文件硬上限，推不上去。依赖包应当作为一个 zip 单独分发
#    （网盘 / GitHub Release），*.gitignore 里已经把 build-env/ 排除掉了。
# ==============================================================================
[CmdletBinding()]
param(
    # 打包到哪棵树的根目录（默认 = 本脚本所在 tools\ 的上一级）
    [string]$TargetRoot = ''
)

$ErrorActionPreference = 'Stop'

function Say  { param([string]$m) Write-Host ('  ' + $m) }
function Head { param([string]$m) Write-Host ''; Write-Host ('=== ' + $m + ' ===') -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host ('  [OK] ' + $m) -ForegroundColor Green }
function Warn { param([string]$m) Write-Host ('  [!]  ' + $m) -ForegroundColor Yellow }
function Die  { param([string]$m) Write-Host ('  [X]  ' + $m) -ForegroundColor Red; exit 1 }

function Get-DirSizeMB {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    $sum = (Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
    if (-not $sum) { return 0 }
    return [math]::Round($sum / 1MB, 1)
}

# 传给原生程序（robocopy / java）的目录路径，末尾**不能**留反斜杠：
#   Windows 的命令行解析里 `"C:\dir\"` 的 `\"` 是一个转义引号，会把后面所有参数
#   一起吞掉 —— 实测表现是 robocopy 报 "No Destination Directory Specified"。
#   （$env:JAVA_HOME 恰好经常带尾反斜杠，所以这不是假想问题。）
function Normalize-DirPath {
    param([string]$Path)
    $p = ([string]$Path).Trim()
    if ($p.Length -le 3) { return $p }   # "C:\" 这种盘根不能去尾巴
    return $p.TrimEnd('\')
}

# 跑原生程序（java / robocopy）并把 stdout+stderr 一起收回来。
# ★ 必须临时把 ErrorActionPreference 降成 Continue：这些程序会往 **stderr** 写正常内容
#   （java -version 就是典型），而 EAP=Stop 下 PowerShell 会把原生的 stderr 输出
#   当成**终止性错误**直接中断脚本（本仓库台账里 adb 踩过同一个坑）。
function Invoke-NativeCapture {
    param([string]$Exe, [string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& $Exe @Arguments 2>&1 | Out-String)
        $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prev }
    return [pscustomobject]@{ Text = [string]$text; Code = [int]$code }
}

# robocopy 的退出码是"位标志"：0-7 都算成功（1=有文件被复制，2=有额外文件…），>=8 才是失败。
# 用 Copy-Item 不行：Gradle 依赖缓存里有接近 260 字符的长路径，Copy-Item 会直接抛错。
function Copy-Tree {
    param([string]$From, [string]$To, [string]$What, [string[]]$Exclude = @())
    $From = Normalize-DirPath $From
    $To = Normalize-DirPath $To
    if (-not (Test-Path -LiteralPath $From)) { Die ('找不到要打包的目录：' + $From + '（' + $What + '）') }
    if (-not (Test-Path -LiteralPath $To)) { New-Item -ItemType Directory -Force -Path $To | Out-Null }
    # 注意：不能把数组存进 $args（那是 PowerShell 的自动变量），换个名字。
    $rcArgs = @($From, $To, '/E', '/COPY:DAT', '/DCOPY:DAT', '/R:1', '/W:1',
                '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/MT:16')
    # 排除运行期锁文件：Gradle 守护进程正开着它们，robocopy 会 ERROR 33（文件被占用）
    # 并最终以退出码 9 收场；这些锁本来也不该进依赖包（换台机器会重新生成）。
    if (@($Exclude).Count -gt 0) { $rcArgs += @('/XF') + $Exclude }
    $res = Invoke-NativeCapture -Exe 'robocopy' -Arguments $rcArgs
    $code = [int]$res.Code
    if ($code -ge 8) {
        ($res.Text -split "`r?`n") | Select-Object -Last 20 | ForEach-Object { Write-Host ('    ' + $_) -ForegroundColor Red }
        Die ('复制失败（robocopy 退出码 ' + $code + '）：' + $What)
    }
    Ok ($What + ' -> ' + $To + '（' + (Get-DirSizeMB $To) + ' MB）')
}

function Read-JavaProperties {
    param([string]$Path)
    # local.properties 是 Java properties：反斜杠要转义，冒号也可能被转义（C\:\\...）
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $t = ([string]$line).Trim()
        if ($t.Length -eq 0 -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -lt 1) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim().Replace('\\', '\').Replace('\:', ':').Replace('\=', '=')
        $map[$k] = $v
    }
    return $map
}

function Find-GradleDistDir {
    param([string]$GradleUserHome, [string]$Version, [string]$Kind)
    $dists = Join-Path $GradleUserHome 'wrapper\dists'
    $name = 'gradle-' + $Version + '-' + $Kind
    $top = Join-Path $dists $name
    if (-not (Test-Path -LiteralPath $top)) { return $null }
    # 真正的解压结果在"哈希子目录"里（哈希由 distributionUrl 决定，不能自己编）
    foreach ($d in (Get-ChildItem -LiteralPath $top -Directory -ErrorAction SilentlyContinue)) {
        $inner = Join-Path $d.FullName ('gradle-' + $Version)
        if (Test-Path -LiteralPath $inner) { return $d.FullName }
    }
    return $null
}

# ------------------------------------------------------------------ 0. 目标
$script:ToolsDir = $PSScriptRoot
if (-not $TargetRoot) { $TargetRoot = Split-Path -Parent $PSScriptRoot }
if (-not (Test-Path -LiteralPath $TargetRoot)) { Die ('目标文件夹不存在：' + $TargetRoot) }
$TargetRoot = Normalize-DirPath (Resolve-Path -LiteralPath $TargetRoot).Path
$envRoot = Join-Path $TargetRoot 'build-env'

Head '打包编译依赖到 build-env'
Say ('来源机器：' + $env:COMPUTERNAME)
Say ('目标文件夹：' + $TargetRoot)
Say ('依赖包目录：' + $envRoot)

if (-not (Test-Path -LiteralPath (Join-Path $TargetRoot 'gradlew.bat'))) {
    Warn ('目标文件夹里没有 gradlew.bat —— 它可能不是项目根目录。仍会继续打包，但请确认目标选对了。')
}

# ------------------------------------------------------------------ 1. JDK
Head '1/4 JDK'
$jdk = ''
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $jdk = (Resolve-Path -LiteralPath $env:JAVA_HOME).Path
}
else {
    $jc = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($jc) { $jdk = Split-Path -Parent (Split-Path -Parent $jc.Source) }
}
if (-not $jdk) { Die '找不到 JDK：请设置 JAVA_HOME 指向 JDK 17（编译要求 Java 17）。' }
$jdk = Normalize-DirPath $jdk
# 本脚本后面要调用工程的 gradlew.bat（--stop），它优先看 JAVA_HOME
$env:JAVA_HOME = $jdk
$javaExe = Join-Path $jdk 'bin\java.exe'
$jv = Invoke-NativeCapture -Exe $javaExe -Arguments @('-version')
$javaVer = ''
foreach ($ln in ($jv.Text -split "`r?`n")) {
    if ([string]$ln -match 'version\s+"') { $javaVer = ([string]$ln).Trim(); break }
}
if (-not $javaVer) { $javaVer = '(没读到版本号)' }
Say ('JDK：' + $jdk)
Say ('版本：' + [string]$javaVer)
if ([string]$javaVer -notmatch 'version "17') { Warn '编译要求 Java 17；当前不是 17，打出来的包在别人机器上可能编译失败。' }
Copy-Tree -From $jdk -To (Join-Path $envRoot 'jdk') -What 'JDK'

# ------------------------------------------------------------------ 2. Android SDK
Head '2/4 Android SDK'
# 来源优先级（顺序很重要，见下面那条 ★）：
#   ① ANDROID_HOME / ANDROID_SDK_ROOT（显式指定的那台机器上的 SDK）
#   ② local.properties 的 sdk.dir —— **但它不能指向 build-env 自己**
#   ③ 常见安装位置
# ★ 为什么必须排除 build-env：管理图标.ps1 会把 local.properties 改写成指向
#   build-env\android-sdk（这是它该做的事）。于是"再跑一次制作脚本"就会拿依赖包当来源，
#   变成自己拷自己 —— 包里少了什么，重新打包也补不回来（实测漏掉过 build-tools 34.0.0，
#   而 AGP 恰恰要那一版）。
$sdk = ''
if ($env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platforms'))) { $sdk = $env:ANDROID_HOME }
if (-not $sdk -and $env:ANDROID_SDK_ROOT -and (Test-Path -LiteralPath (Join-Path $env:ANDROID_SDK_ROOT 'platforms'))) { $sdk = $env:ANDROID_SDK_ROOT }
$lp = Read-JavaProperties -Path (Join-Path $TargetRoot 'local.properties')
if (-not $sdk -and $lp.ContainsKey('sdk.dir')) {
    $cand = [string]$lp['sdk.dir']
    $insideEnv = $false
    try { $insideEnv = $cand.Replace('/', '\').ToLower().StartsWith(($envRoot.ToLower() + '\')) } catch { $insideEnv = $false }
    if ($insideEnv) {
        Warn ('local.properties 里的 sdk.dir 指向依赖包自己（' + $cand + '），已跳过它 —— 否则就是"自己拷自己"。')
    }
    elseif (Test-Path -LiteralPath (Join-Path $cand 'platforms')) { $sdk = $cand }
}
# ★ 面包屑：上次制作依赖包时用的来源路径，记在 build-env\source.properties 里。
#   为什么需要它：管理图标.ps1 会把 local.properties 改写成指向 build-env\android-sdk，
#   于是"在做过包的这台机器上重做包"就没有别的来源可用了（而 SDK 常在自定义路径上，
#   常见位置里根本没有）。读不到/路径不存在都会自然跳过。
if (-not $sdk) {
    $crumbs = Read-JavaProperties -Path (Join-Path $envRoot 'source.properties')
    foreach ($k in @('sdk.dir', 'android.sdk')) {
        if ($crumbs.ContainsKey($k)) {
            $cand = [string]$crumbs[$k]
            if (Test-Path -LiteralPath (Join-Path $cand 'platforms')) {
                $sdk = $cand
                Say ('SDK 来源取自 build-env\source.properties：' + $sdk)
                break
            }
        }
    }
}
if (-not $sdk) {
    $common = @()
    if ($env:LOCALAPPDATA) { $common += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    if ($env:USERPROFILE) { $common += (Join-Path $env:USERPROFILE 'Android\Sdk') }
    if ($env:USERPROFILE) { $common += (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk') }
    $common += 'C:\Android\Sdk'
    foreach ($cand in $common) {
        if (Test-Path -LiteralPath (Join-Path $cand 'platforms')) { $sdk = $cand; break }
    }
}
if (-not $sdk) {
    foreach ($cand in @("$env:LOCALAPPDATA\Android\Sdk", "$env:USERPROFILE\AppData\Local\Android\Sdk")) {
        if (Test-Path -LiteralPath $cand) { $sdk = $cand; break }
    }
}
if (-not $sdk) { Die '找不到 Android SDK：请在 local.properties 里写 sdk.dir，或设置 ANDROID_HOME。' }
$sdk = Normalize-DirPath (Resolve-Path -LiteralPath $sdk).Path
Say ('SDK：' + $sdk)

# compileSdk 从 app\build.gradle.kts 读，避免"打包的 platform 和工程要的不是一个版本"
$compileSdk = 35
$bg = Join-Path $TargetRoot 'app\build.gradle.kts'
if (Test-Path -LiteralPath $bg) {
    $m = [regex]::Match([string](Get-Content -LiteralPath $bg -Raw -Encoding UTF8), 'compileSdk\s*=\s*(\d+)')
    if ($m.Success) { $compileSdk = [int]$m.Groups[1].Value }
}
$platformSrc = Join-Path $sdk ('platforms\android-' + $compileSdk)
if (-not (Test-Path -LiteralPath $platformSrc)) { Die ('SDK 里没有 platforms\android-' + $compileSdk + '，请先用 Android Studio / sdkmanager 装好。') }
Say ('platforms：android-' + $compileSdk)

# build-tools：**整份拷贝**，不要只挑"最高版本"。
# ★ 这里踩过一次实测坑（.tmp/verify-portable.cmd 的干净环境实测）：
#   只打最高版（35.0.0）时，离线编译报 `Failed to find Build Tools revision 34.0.0` ——
#   AGP 要哪一版是它内部的默认值，工程文件里读不出来；开发机编得过，只是因为它同时装了 34 和 35。
#   能站得住的推理是：**这台机器能编译这个工程 ⇒ 它装着的这套 build-tools 一定够用**，
#   所以整份拷过去，别猜版本号。代价是多几十到一百多 MB，换来"换台机器不会因为少一个版本而失败"。
$btAll = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
           Sort-Object { try { [version]$_.Name } catch { [version]'0.0.0' } } -Descending)
if (@($btAll).Count -eq 0) { Die 'SDK 里没有 build-tools，请先安装（例如 34.0.0 / 35.0.0）。' }
$btList = @($btAll | ForEach-Object { $_.Name })
Say ('build-tools：' + ($btList -join ', ') + '（整份打包）')

foreach ($pair in @(
    @{ s = $platformSrc;                        d = ('android-sdk\platforms\android-' + $compileSdk); w = ('platform android-' + $compileSdk) },
    @{ s = (Join-Path $sdk 'platform-tools');   d = 'android-sdk\platform-tools';                     w = 'platform-tools（adb，可选但装上更省事）' }
)) {
    if (Test-Path -LiteralPath $pair.s) {
        Copy-Tree -From $pair.s -To (Join-Path $envRoot $pair.d) -What $pair.w
    }
    else { Warn ('跳过（本机没有）：' + $pair.w) }
}
foreach ($bt in $btList) {
    Copy-Tree -From (Join-Path $sdk ('build-tools\' + $bt)) `
              -To (Join-Path $envRoot ('android-sdk\build-tools\' + $bt)) -What ('build-tools ' + $bt)
}
# licenses：AGP 只在"需要自动下载组件"时才校验它；一起打包可以少一类玄学失败
$lic = Join-Path $sdk 'licenses'
if (Test-Path -LiteralPath $lic) { Copy-Tree -From $lic -To (Join-Path $envRoot 'android-sdk\licenses') -What 'licenses' }

# ------------------------------------------------------------------ 3. Gradle 发行包
Head '3/4 Gradle 发行包'
$guh = ''
if ($env:GRADLE_USER_HOME -and (Test-Path -LiteralPath $env:GRADLE_USER_HOME)) { $guh = $env:GRADLE_USER_HOME }
elseif (Test-Path -LiteralPath (Join-Path $env:USERPROFILE '.gradle')) { $guh = (Join-Path $env:USERPROFILE '.gradle') }
if (-not $guh) { Die '找不到 Gradle 用户目录（%USERPROFILE%\.gradle）。' }
Say ('Gradle 用户目录：' + $guh)

$wrapProps = Join-Path $TargetRoot 'gradle\wrapper\gradle-wrapper.properties'
if (-not (Test-Path -LiteralPath $wrapProps)) { Die ('缺少 ' + $wrapProps) }
$url = ''
foreach ($line in (Get-Content -LiteralPath $wrapProps -Encoding UTF8)) {
    if ([string]$line -match '^\s*distributionUrl\s*=\s*(.+)$') { $url = $Matches[1].Trim() }
}
if (-not $url) { Die 'gradle-wrapper.properties 里没找到 distributionUrl。' }
$mf = [regex]::Match($url, 'gradle-([0-9][^-]*)-(bin|all)\.zip')
if (-not $mf.Success) { Die ('看不懂 distributionUrl：' + $url) }
$gver = $mf.Groups[1].Value
$gkind = $mf.Groups[2].Value
Say ('工程要的 Gradle：' + $gver + '（' + $gkind + '）')
Say ('下载地址（不会重新下载，只从本机缓存复制）：' + $url)

$distDir = Find-GradleDistDir -GradleUserHome $guh -Version $gver -Kind $gkind
if (-not $distDir) {
    Die ('本机缓存里没有 gradle-' + $gver + '-' + $gkind + '。请先用本工程的 gradlew.bat 成功编译一次（会自动下载），再跑本脚本。')
}
$distDir = Normalize-DirPath $distDir
$distDstParent = Join-Path $envRoot ('gradle-home\wrapper\dists\gradle-' + $gver + '-' + $gkind)
Copy-Tree -From $distDir -To (Join-Path $distDstParent (Split-Path -Leaf $distDir)) -What ('gradle-' + $gver + ' 发行包')

# ------------------------------------------------------------------ 4. 依赖缓存
Head '4/4 Gradle 依赖缓存（--offline 编译的关键）'
$modSrc = Join-Path $guh 'caches\modules-2'
if (-not (Test-Path -LiteralPath $modSrc)) { Die ('找不到依赖缓存：' + $modSrc + '（请先成功编译一次）') }
Say '正在复制……这一步文件很多（几十万个），要花几分钟，属正常。'
# 先让本工程的 Gradle 守护进程退出：它开着 modules-2.lock，复制会被拒（ERROR 33）。
# 尽力而为：失败也无所谓，下面的 /XF 已经把锁文件排除掉了。
$stopBat = Join-Path $TargetRoot 'gradlew.bat'
if (Test-Path -LiteralPath $stopBat) {
    Say '先停掉本工程的 Gradle 守护进程（释放依赖缓存上的文件锁）……'
    $st = Invoke-NativeCapture -Exe 'cmd.exe' -Arguments @('/c', ('"' + (Normalize-DirPath $stopBat) + '" --stop'))
    $stLine = ([string]$st.Text -split "`r?`n" | Where-Object { ([string]$_).Trim().Length -gt 0 } | Select-Object -First 1)
    if ($stLine) { Say ('  ' + ([string]$stLine).Trim()) }
}
Copy-Tree -From $modSrc -To (Join-Path $envRoot 'gradle-home\caches\modules-2') `
          -What '已下载的依赖包' -Exclude @('modules-2.lock', '*.lock')

# ------------------------------------------------------------------ 5. 说明文件
Head '生成说明文件'
$totalMb = Get-DirSizeMB $envRoot
$stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
$manifest = @(
    '编译依赖包（build-env）',
    '============================================================',
    '这个文件夹是"编译 APK 需要的全部工具"，由 tools\制作依赖包.ps1 自动生成。',
    '有了它，管理图标.cmd 在**没有装 JDK / Android Studio 的电脑**上也能编译出 APK，',
    '而且全程不需要联网（Gradle 用 --offline）。',
    '',
    '目录结构',
    '  jdk\                        便携 JDK（编译要求 Java 17）',
    '  android-sdk\platforms\      Android 平台（编译目标）',
    '  android-sdk\build-tools\    编译工具（aapt2 / d8 / zipalign 等）',
    '  android-sdk\platform-tools\ adb（装到手机时可选）',
    '  android-sdk\licenses\       许可文件',
    '  gradle-home\                Gradle 的"用户目录"（用 GRADLE_USER_HOME 指到这里）',
    '    wrapper\dists\            Gradle 发行包本体（不用再联网下载）',
    '    caches\modules-2\         已下载好的第三方依赖（离线编译靠它）',
    '',
    '本次打包信息',
    ('  生成时间   ：' + $stamp)
    ('  来源机器   ：' + $env:COMPUTERNAME)
    ('  JDK        ：' + $jdk)
    ('  Android SDK：' + $sdk + '（platform android-' + $compileSdk + '、build-tools ' + ($btList -join ' + ') + '）')
    ('  Gradle     ：' + $gver + '-' + $gkind + '（源自 ' + $guh + '）')
    ('  总体积     ：' + $totalMb + ' MB')
    '',
    '怎么用',
    '  1. 把 build-env 和 管理图标.cmd、tools\、icons\、app\ 等**放在同一个文件夹里**，',
    '     也就是保持"打开文件夹就能看到 管理图标.cmd"这个结构。',
    '  2. 双击 管理图标.cmd，它会自动把 JDK / SDK / Gradle 指向本文件夹里的这一份，',
    '     并在窗口里打印"摆放检查"结果。',
    '  3. 不需要设 JAVA_HOME / ANDROID_HOME，也不需要装 Android Studio。',
    '',
    '注意',
    '  · 不要把它单独移动或改名（脚本按固定相对路径找它：<项目根>\build-env）。',
    '  · 不要提交到 GitHub：JDK 的 lib\modules 单文件就超过 100MB，超过 GitHub 上限。',
    '    分发请把整个文件夹（或只把 build-env）压成 zip 发网盘，或用 GitHub Release 附件。',
    '  · 依赖升级后（改了 gradle\libs.versions.toml 等）需要重新跑一次 tools\制作依赖包.ps1，',
    '    否则离线编译会报"缺少已下载的依赖"。',
    '  · build-tools 是"整份"打包的（可能有好几个版本）：AGP 具体要哪一版是它内部的默认值，',
    '    工程文件里读不出来。只打最高版会在别的机器上报 Failed to find Build Tools revision x.y.z。'
)
# 面包屑（机器可读）：下次重新打包时用来找回来源，尤其是 SDK 在自定义路径的情况
try {
    $crumbs = @(
        ('# 制作依赖包.ps1 写下的来源记录（换机器打包时可忽略/可删）'),
        ('sdk.dir=' + $sdk),
        ('jdk.dir=' + $jdk),
        ('gradle.user.home=' + $guh)
    )
    [System.IO.File]::WriteAllLines((Join-Path $envRoot 'source.properties'),
        [string[]]$crumbs, (New-Object System.Text.UTF8Encoding($false)))
}
catch { Warn ('source.properties 写入失败：' + [string]$_.Exception.Message) }

$manifestPath = Join-Path $envRoot '依赖包说明.txt'
# 带 BOM 写：记事本 / 部分编辑器读无 BOM 的 UTF-8 会按 ANSI 解码，中文变乱码
[System.IO.File]::WriteAllLines($manifestPath, [string[]]$manifest, (New-Object System.Text.UTF8Encoding($true)))
Ok ('说明文件：' + $manifestPath)

Head '完成'
Ok ('依赖包已就绪：' + $envRoot + '（' + $totalMb + ' MB）')
Say ''
Say '接下来建议做一次实测（在有网络、但故意不用本机环境的方式下）：'
Say '  set "JAVA_HOME=<文件夹>\build-env\jdk"'
Say '  set "ANDROID_HOME=<文件夹>\build-env\android-sdk"'
Say '  set "GRADLE_USER_HOME=<文件夹>\build-env\gradle-home"'
Say '  gradlew.bat :app:assembleDebug --offline'
Say '编译成功就说明这个文件夹可以整包发给别人了。'
Say ''
Warn '别忘了：build-env\ 已经在 .gitignore 里（不进仓库），分发请用 zip / Release 附件。'
exit 0
