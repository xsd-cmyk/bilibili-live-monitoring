# build-and-install.ps1
# Build debug APK, install to emulator, launch, and self-check for crashes.
# Usage:  .\build-and-install.ps1 [-SkipBuild] [-NoLaunch]
param(
    [switch]$SkipBuild,
    [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'

# 机器相关配置：可写在同目录的 .local.ps1 里（该文件不进仓库），没有就用下面的默认来源。
# 脚本本身**不写死任何本机路径** —— 换机器不必改脚本。
$LocalConfig = Join-Path $PSScriptRoot '.local.ps1'
if (Test-Path -LiteralPath $LocalConfig) { . $LocalConfig }

# SDK：.local.ps1 的 $Sdk > local.properties 的 sdk.dir > 环境变量 > 标准安装位置
if (-not $Sdk) {
    $localProps = Join-Path $PSScriptRoot 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $line = Select-String -Path $localProps -Pattern '^\s*sdk\.dir=' | Select-Object -First 1
        if ($line) {
            $Sdk = ($line.Line -replace '^\s*sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\').Trim()
        }
    }
}
if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$Adb      = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulator = Join-Path $Sdk 'emulator\emulator.exe'

# AVD 目录与名称：.local.ps1 > 环境变量 > 用户默认目录；名称默认 BiliMonitor_Test
if (-not $AvdHome) {
    $AvdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { Join-Path $env:USERPROFILE '.android\avd' }
}
if (-not $AvdName) { $AvdName = 'BiliMonitor_Test' }
$Package  = 'com.example.bilimonitor'
$Apk      = 'app\build\outputs\apk\debug\app-debug.apk'

Set-Location $PSScriptRoot

function Step($m) { Write-Host ''; Write-Host ('=== ' + $m + ' ===') -ForegroundColor Cyan }
function Ok($m)   { Write-Host ('  [OK] ' + $m) -ForegroundColor Green }
function Warn($m) { Write-Host ('  [!]  ' + $m) -ForegroundColor Yellow }
function Die($m)  { Write-Host ('  [X]  ' + $m) -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- 1. emulator
Step 'Check emulator'
$online = & $Adb devices 2>$null | Select-String -Pattern 'emulator-\d+\s+device'
if (-not $online) {
    Warn 'Emulator not running, starting it...'
    $env:ANDROID_AVD_HOME = $AvdHome
    Start-Process -FilePath $Emulator `
        -ArgumentList '-avd', $AvdName, '-gpu', 'swiftshader_indirect', '-no-snapshot-save' `
        -WorkingDirectory (Join-Path $Sdk 'emulator')

    # 等启动期间 adb 会往 stderr 打 "no devices/emulators found"；
    # 在 $ErrorActionPreference='Stop' 下这会被当成终止性错误直接中断脚本，
    # 所以这段必须临时降级为 Continue。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $Adb start-server 2>$null | Out-Null
    $deadline = (Get-Date).AddMinutes(8)
    $boot = ''
    while ((Get-Date) -lt $deadline) {
        $boot = ((& $Adb shell getprop sys.boot_completed 2>$null) -join '').Trim()
        if ($boot -eq '1') { break }
        Start-Sleep -Seconds 5
    }
    $ErrorActionPreference = $prevEap
    if ($boot -ne '1') { Die 'Emulator boot timed out after 8 minutes' }
}
$m = & $Adb devices 2>$null | Select-String -Pattern 'emulator-\d+\s+device'
Ok ('Device online: ' + $m.ToString().Trim())

# ------------------------------------------------- 1.5 icon presets (generated)
# 从 icons/presets/ 重新生成别名/位图/清单，必须在 assembleDebug 之前跑，否则图标不会进 APK。
# 目录不存在时脚本会静默新建空目录并继续（不阻断构建）；生成失败则直接停下来报错。
Step 'Generate icon presets'
if (-not (Test-Path '.\tools\generate-icon-presets.ps1')) {
    Warn 'tools\generate-icon-presets.ps1 not found, skipping icon presets'
}
else {
    & '.\tools\generate-icon-presets.ps1' -NoMirror
    if ($LASTEXITCODE -ne 0) { Die 'Icon preset generation failed (see output above)' }
}

# ---------------------------------------------------------------- 2. build
if (-not $SkipBuild) {
    Step 'Build debug APK'
    $log = & .\gradlew.bat :app:assembleDebug --offline 2>&1
    $errors = $log | Select-String -Pattern '^e: '
    if ($errors) {
        Write-Host ''
        $errors | Select-Object -First 40 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Die 'Compile failed (errors above)'
    }
    if (-not ($log | Select-String -Pattern 'BUILD SUCCESSFUL')) {
        $log | Select-Object -Last 25 | ForEach-Object { Write-Host $_ }
        Die 'Build did not succeed'
    }
}
if (-not (Test-Path $Apk)) { Die ('APK not found: ' + $Apk) }
$info = Get-Item $Apk
$size = [math]::Round($info.Length / 1MB, 1)
Ok ('APK ' + $size + ' MB, built at ' + $info.LastWriteTime.ToString('HH:mm:ss'))

# ---------------------------------------------------------------- 3. install
Step 'Install to emulator'
$install = & $Adb install -r $Apk 2>&1
if (($install -join ' ') -notmatch 'Success') {
    $install | ForEach-Object { Write-Host $_ }
    Die 'Install failed'
}
Ok 'Installed (existing app data kept)'
& $Adb shell pm grant $Package android.permission.POST_NOTIFICATIONS 2>$null | Out-Null

# ---------------------------------------------------------------- 4. launch + self-check
if (-not $NoLaunch) {
    Step 'Launch and self-check'
    & $Adb logcat -c 2>$null | Out-Null
    & $Adb shell am start -n ($Package + '/.MainActivity') 2>&1 | Out-Null
    Start-Sleep -Seconds 15

    $procId = ((& $Adb shell pidof $Package) -join '').Trim()
    if (-not $procId) {
        Write-Host ''
        & $Adb logcat -d -v brief 2>$null |
            Select-String -Pattern 'FATAL EXCEPTION' -Context 0, 25 |
            Select-Object -First 1 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Die 'Process gone after launch (crashed)'
    }
    Ok ('Process alive, pid=' + $procId)

    $bad = & $Adb logcat -d -v brief 2>$null |
        Select-String -Pattern 'FATAL EXCEPTION|AndroidRuntime.*Exception|SQLiteConstraintException|migration from'
    if ($bad) {
        Write-Host ''
        $bad | Select-Object -First 15 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
        Die 'Crash or database exception detected'
    }
    Ok 'No crash, no database exception'
}

Write-Host ''
Write-Host 'Done. Ready for manual testing.' -ForegroundColor Green
