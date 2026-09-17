# ==============================================================================
#  generate-icon-presets.ps1
#  图标 / 名称 预设生成器（两个**独立可切换**的轴 -> 别名 = 两个轴的笛卡尔积）
#
#  为什么是笛卡尔积：<activity-alias> 的 android:icon 与 android:label 是**同一个组件
#  上的两个属性**，系统不提供"只改其中一个"的运行时接口。想让「图标」与「名字」各自
#  独立切换，只能为每个组合 (i, j) 各生成一个别名，运行时启用哪一个 = 同时选中了那一对。
#
#  轴与索引（契约，运行时侧按同一份实现，勿改语义）：
#    · 图标轴 i ∈ {0..N}
#        i = 0        -> 应用自己的图标，别名里写 @mipmap/ic_launcher
#        i >= 1       -> icons/presets/ 里第 i 张图（按**文件名**排序稳定编号），
#                        资源名 ic_launcher_preset_<i>
#    · 名称轴 j ∈ {0..M}
#        j = 0        -> 应用自己的名字，别名里写 @string/app_name
#        j >= 1       -> icons/names.txt 第 j 行（去空行、# 注释、去首尾空白、去重）
#    · 格子 (0,0) 不生成别名（它就是主入口 MainActivity 本身）
#      => 别名总数 = (N+1)*(M+1) - 1
#    · 组件名：IconPreset_<i>_<j>，例如 IconPreset_0_2、IconPreset_3_0
#      （android:name 就是这个字符串：不加前导点、不写全限定名。
#        manifest merger 会补成 <package>.IconPreset_<i>_<j>，本项目实测确认。）
#
#  兼容性：Windows PowerShell 5.1 / PowerShell 7+ 均可运行。
#  编码：本文件是 UTF-8 **带 BOM** 保存的 —— Windows PowerShell 5.1 读取无 BOM 的
#        UTF-8 脚本会按系统 ANSI 代码页解码，中文字符串会乱码并导致上百个语法错误。
#        改动本文件时请保持「UTF-8 with BOM + LF」；脚本运行结束会自己复核并打印 BOM=True。
#
#  用法（详细说明见 icons/presets/README.md）：
#    # 1) 放好图 + 写好 icons/names.txt 后生成（最常用，build-and-install.ps1 会自动调用）
#    powershell -File tools\generate-icon-presets.ps1
#
#    # 2) 批量导入外部目录的图片，导入后再生成
#    powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标'
#
#    # 3) 回到零预设状态（清空 icons/presets/ 与全部生成物）
#    powershell -File tools\generate-icon-presets.ps1 -Clear
#
#    # 4) 只看计划不写盘
#    powershell -File tools\generate-icon-presets.ps1 -DryRun
#
#  退出码：0 = 成功；1 = 出错（源目录不存在 / 文件名冲突 / 图片损坏 / names.txt 有非法名 /
#          manifest 非法 / **写盘后读回比对不一致** / **落盘复核或最终 BOM 自检不通过** 等）。
#  落盘纪律：manifest、res/values/icon_presets.xml、icons/presets/README.md 三个产物
#          一律经 Write-FileVerified 写入，并在写完后立刻从磁盘读回、逐字节比对；
#          随后还有一次"落盘复核"：重新读文件、独立数一遍别名数与数组条目数，
#          与本次计划不符就报错并退出码 1。**"我调用了写盘" 与 "文件确实变了" 分开验证。**
#  本脚本只做「文件生成」：不调用 Gradle、不碰设备、不改任何 Kotlin。
# ==============================================================================
[CmdletBinding()]
param(
    # 批量导入的源目录，可传多个（空格或逗号分隔，-Source 也可重复），支持通配符；源目录可以在项目外。
    [string[]]$Source,
    # 关掉递归（默认递归扫描子目录）
    [switch]$NoRecurse,
    # 导入时移动而非复制（默认复制，保留源目录）
    [switch]$Move,
    # 覆盖 icons/presets/ 里的同名文件（覆盖了哪些会逐条报告）
    [switch]$Force,
    # 清空 icons/presets/ 下的图片与全部生成物，回到「零预设」状态
    [switch]$Clear,
    # 只打印计划，不写任何文件
    [switch]$DryRun,
    # 不往 publish/ 镜像（默认会镜像 manifest / res / tools / README / names.txt / build-and-install.ps1）
    [switch]$NoMirror
)

Set-StrictMode -Version 3.0

# 控制台按 UTF-8 输出，避免中文摘要变成乱码（5.1 的默认输出编码是系统 ANSI）
try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [Console]::OutputEncoding = $utf8
    $OutputEncoding = $utf8
} catch { }

# System.Drawing 必须显式加载：Windows PowerShell 5.1 的默认会话里没有这个程序集，
# 用到 [System.Drawing.Bitmap] 时会直接报 "Unable to find type"。
try { Add-Type -AssemblyName System.Drawing -ErrorAction Stop } catch { }
try { [void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing') } catch { }
if (-not ('System.Drawing.Bitmap' -as [type])) {
    Write-Host '  [X] 无法加载 System.Drawing（本脚本用它做图片解码与高质量缩放）。' -ForegroundColor Red
    Write-Host '      请确认在 Windows 上运行，且 .NET Framework 的 System.Drawing 可用。' -ForegroundColor Red
    exit 1
}

$script:UTF8NoBom = New-Object System.Text.UTF8Encoding($false)
$script:UTF8Bom   = New-Object System.Text.UTF8Encoding($true)
$script:NL        = [string][char]10
$script:Dq        = [string][char]34
$script:Bar       = [string][char]124      # 竖线：清单数组的字段分隔符
$script:Lt        = [string][char]60       # <
$script:Gt        = [string][char]62       # >

# ------------------------------------------------------------------ 常量 / 全局
$script:Root            = Split-Path -Parent $PSScriptRoot          # 项目根（tools/ 的上级）
$script:AppMain         = Join-Path $script:Root 'app\src\main'
$script:ManifestPath    = Join-Path $script:AppMain 'AndroidManifest.xml'
$script:ValuesXmlPath   = Join-Path $script:AppMain 'res\values\icon_presets.xml'
$script:ResDir          = Join-Path $script:AppMain 'res'
$script:PresetsDir      = Join-Path $script:Root 'icons\presets'
$script:NamesPath       = Join-Path $script:Root 'icons\names.txt'
$script:PublishRoot     = Join-Path $script:Root 'publish'
$script:SelfPath        = Join-Path $PSScriptRoot 'generate-icon-presets.ps1'
$script:BuildScriptPath = Join-Path $script:Root 'build-and-install.ps1'
$script:ToolsMirrorRel  = 'tools\generate-icon-presets.ps1'
$script:ReadmeMirrorRel = 'icons\presets\README.md'
$script:NamesMirrorRel  = 'icons\names.txt'

# 两个轴的哨兵（第 0 项）
$script:DefaultIconRes    = 'ic_launcher'                  # i=0：应用自己的图标
$script:DefaultIconLabel  = '默认（应用自己的图标）'
$script:DefaultNameLabel  = '默认（应用自己的名字）'        # j=0：应用自己的名字（label 用 @string/app_name）
$script:DefaultNameValue  = ''                             # name_axis 第 0 项就写空字符串，运行时据此显示上面的哨兵文案
$script:NoNameFileHint    = '未提供 icons/names.txt（或它全为空）-> M=0，退化为只有图标轴（这是正常状态）'

# 规模警告阈值（不阻止，只警告：用户有权决定）
$script:WarnMatrixCells = 60

# 目标密度与标准尺寸（Android 官方 launcher icon 尺寸）
$script:Densities = [ordered]@{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}
$script:MaxIconPx    = 192                                        # 最高密度尺寸
$script:LowResPx     = 192                                        # 短边小于该值 -> 警告（不阻止）
$script:CopyExts     = @('.png', '.jpg', '.jpeg')                 # 允许放进 icons/presets/ 的图片
$script:OtherImgExts = @('.webp', '.bmp', '.gif', '.tif', '.tiff', '.heic', '.avif', '.ico')
$script:MarkerBegin  = '<!-- BEGIN GENERATED ICON ALIASES：由 tools/generate-icon-presets.ps1 生成，请勿手改 -->'
$script:MarkerEnd    = '<!-- END GENERATED ICON ALIASES -->'

# 生成物文件名模式。两处用途：
#   · 位图：ic_launcher_preset_<i>.png（i >= 1，i=0 就是 mipmap-*/ic_launcher.* 本身）
#   · 别名：IconPreset_<i>_<j>（本脚本当前命名）与 IconPreset<N>（上一代单轴命名，属"旧残留"）
$script:BitmapRegex      = '^ic_launcher_preset_([0-9]+)\.png$'
$script:AliasGridRegex   = '^IconPreset_([0-9]+)_([0-9]+)$'
$script:AliasLegacyRegex = '^IconPreset([0-9]+)$'

# ------------------------------------------------------------------ 输出工具
function Write-Head($m) { Write-Host ''; Write-Host ('=== ' + $m + ' ===') -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host ('  [OK] ' + $m) -ForegroundColor Green }
function Write-Info($m) { Write-Host ('  - ' + $m) -ForegroundColor Gray }
function Write-Warn2($m){ Write-Host ('  [!] ' + $m) -ForegroundColor Yellow }
function Write-Err2($m) { Write-Host ('  [X] ' + $m) -ForegroundColor Red }
function Bool-Cn([bool]$b) { if ($b) { return 'True' } else { return 'False' } }
function Die($m) {
    Write-Err2 $m
    Write-Host ''
    Write-Host '生成失败：未写入生成物（或已回滚），manifest / res 保持原样。' -ForegroundColor Red
    exit 1
}

# 已经写盘之后才失败（例如落盘复核 / 最终编码自检不过）。
# 与 Die 的区别只在文案：产物已经在磁盘上了，不能再说"未写入生成物"。
function Die-Final($m) {
    Write-Err2 $m
    Write-Host ''
    Write-Host '生成失败：产物已写入，但最终自检未通过 —— 请勿使用本次生成结果。' -ForegroundColor Red
    exit 1
}

function New-Counter {
    $c = [ordered]@{}
    foreach ($k in @('ScanDirs', 'Found', 'Imported', 'Renamed', 'Skipped', 'Overwritten',
                     'NonImage', 'AlreadyPresent', 'Moved', 'ClearedPresets', 'ClearedNames',
                     'DeletedPresets', 'LowRes', 'Cropped', 'OldBitmaps', 'OldAliases',
                     'StaleAliases', 'StaleBitmaps', 'MatrixEntries', 'Mirrored', 'MirroredStale',
                     'NamesDropped', 'NameDuplicates')) {
        $c[$k] = 0
    }
    return $c
}

function Inc($Counters, [string]$key) { $Counters[$key] = $Counters[$key] + 1 }
function Add2($Counters, [string]$key, [int]$n) { $Counters[$key] = $Counters[$key] + $n }

# ------------------------------------------------------------------ 文件 IO
function Test-SamePath([string]$a, [string]$b) {
    if (-not $a -or -not $b) { return $false }
    $fa = [System.IO.Path]::GetFullPath($a)
    $fb = [System.IO.Path]::GetFullPath($b)
    return [string]::Equals($fa, $fb, [System.StringComparison]::OrdinalIgnoreCase)
}

function Read-TextFileInfo([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    $text = $script:UTF8NoBom.GetString($bytes)
    if ($hasBom) { $text = $text.Substring(1) }
    return New-Object psobject -Property @{ Text = $text; HasBom = $hasBom }
}

function Write-TextFileWithStyle([string]$path, [string]$text, [bool]$hasBom) {
    $enc = $script:UTF8NoBom
    if ($hasBom) { $enc = $script:UTF8Bom }
    [System.IO.File]::WriteAllText($path, $text, $enc)
}

# 把文本编码成「带 / 不带 BOM 的 UTF-8 字节流」—— 写盘后逐字节读回比对用。
function Get-EncodedBytes([string]$text, [bool]$hasBom) {
    $enc = $script:UTF8NoBom
    if ($hasBom) { $enc = $script:UTF8Bom }
    $body = $enc.GetBytes($text)
    $pre = $enc.GetPreamble()          # 无 BOM 的编码器返回空数组
    if (-not $hasBom -or $pre.Length -eq 0) { return $body }
    $out = New-Object 'byte[]' ($pre.Length + $body.Length)
    [System.Array]::Copy($pre, 0, $out, 0, $pre.Length)
    [System.Array]::Copy($body, 0, $out, $pre.Length, $body.Length)
    return $out
}

function Test-SameBytes([byte[]]$a, [byte[]]$b) {
    if ($null -eq $a -or $null -eq $b) { return $false }
    if ($a.Length -ne $b.Length) { return $false }
    for ($i = 0; $i -lt $a.Length; $i++) { if ($a[$i] -ne $b[$i]) { return $false } }
    return $true
}

# ★★ 生成物落盘的**唯一出口**：写 -> 立刻从磁盘读回 -> 与"打算写的字节"逐字节比对。
#    "调用了写盘 API" != "文件真的变了"。本函数把这两件事分开验证：
#      · 抛异常 / 文件不存在 / 长度·内容·BOM 任一对不上 -> 立即报错并 exit 1，绝不打印 [OK]；
#      · 明确区分"内容已更新"与"内容未变（本来就是目标状态）"，
#        并在哈希与写入前完全相同时额外点出"这次写入等于没写（静默不落盘）"。
#    历史缺陷：manifest 与三个数组的写盘被一个**永远为 $false** 的开关短路，
#    脚本照样打印 [OK] 并退出 0 —— 就是本函数要根治的那类"报告成功、实际没做事"。
function Write-FileVerified([string]$path, [string]$text, [bool]$hasBom, [string]$what) {
    $expected = Get-EncodedBytes -text $text -hasBom $hasBom
    $existedBefore = Test-Path -LiteralPath $path -PathType Leaf
    $beforeHash = ''
    if ($existedBefore) { $beforeHash = Get-FileSha256 $path }
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    try { Write-TextFileWithStyle -path $path -text $text -hasBom $hasBom }
    catch {
        Write-Err2 ($what + '：写盘调用抛出异常 -> ' + $path)
        Write-Err2 ('        ' + $_.Exception.Message)
        Die ($what + ' 写入失败')
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Err2 ($what + '：写盘调用返回后文件仍不存在 -> ' + $path)
        Die ($what + ' 未落盘')
    }
    $actual = [System.IO.File]::ReadAllBytes($path)
    if (-not (Test-SameBytes -a $expected -b $actual)) {
        Write-Err2 ($what + '：写盘后读回比对不一致 —— 磁盘内容 != 本次打算写入的内容（静默不落盘）')
        Write-Err2 ('        文件：' + $path)
        Write-Err2 ('        预期 ' + $expected.Length + ' 字节，磁盘上读到 ' + $actual.Length + ' 字节')
        if ($existedBefore) {
            $nowHash = Get-FileSha256 $path
            if ($nowHash -eq $beforeHash) {
                Write-Err2 ('        磁盘文件的 SHA-256 与写入前完全一致（' + $nowHash.Substring(0, 16) +
                    '…）：这次写入等于没写')
            }
        }
        Die ($what + ' 读回校验失败')
    }
    $afterHash = Get-FileSha256 $path
    $changed = '内容未变（本来就是目标状态）'
    if (-not $existedBefore) { $changed = '新建文件' }
    elseif ($beforeHash -ne $afterHash) { $changed = '内容已更新' }
    Write-Info ($what + '：已写入并读回校验通过（' + $actual.Length + ' 字节，BOM=' + (Bool-Cn $hasBom) +
        '，' + $changed + '，SHA-256 ' + $afterHash.Substring(0, 16) + '…）')
    return (New-Object psobject -Property @{
        Path = $path; Bytes = $actual.Length; Sha256 = $afterHash; HasBom = $hasBom; Changed = $changed
    })
}

# 行尾统一 LF 的文本文件（生成物一律无 BOM）。同样走"写 -> 读回 -> 比对"。
function Write-LfFile([string]$path, [string[]]$lines, [string]$what) {
    if ([string]::IsNullOrEmpty($what)) { $what = Split-Path -Leaf $path }
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $text = (($lines -join $script:NL) + $script:NL)
    return (Write-FileVerified -path $path -text $text -hasBom $false -what $what)
}

function Get-FileSha256([string]$path) { return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }

function Get-ShortName([string]$p) {
    if ([string]::IsNullOrWhiteSpace($p)) { return '' }
    return $p.TrimEnd([char]46, [char]58, [char]92, [char]47)
}

function Format-ByteSize([long]$bytes) {
    if ($bytes -ge 1048576) { return ([math]::Round($bytes / 1MB, 2)).ToString() + ' MB' }
    if ($bytes -ge 1024)    { return ([math]::Round($bytes / 1KB, 0)).ToString() + ' KB' }
    return $bytes.ToString() + ' B'
}

# ------------------------------------------------------------------ 图片
function Get-ImageFormatName($rawFormat) {
    $g = $rawFormat.Guid
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Png).Guid)       { return 'PNG' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Jpeg).Guid)      { return 'JPEG' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Bmp).Guid)       { return 'BMP' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Gif).Guid)       { return 'GIF' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Tiff).Guid)      { return 'TIFF' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Icon).Guid)      { return 'ICON' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Emf).Guid)       { return 'EMF' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::Wmf).Guid)       { return 'WMF' }
    if ($g -eq ([System.Drawing.Imaging.ImageFormat]::MemoryBmp).Guid) { return 'MEMORYBMP' }
    return ('未知(' + $rawFormat.ToString() + ')')
}

function Get-ImageInfo([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)          # 先读进内存，立刻释放文件句柄
    $ms = New-Object System.IO.MemoryStream(,$bytes)
    $bmp = $null
    try { $bmp = [System.Drawing.Bitmap]::FromStream($ms, $true, $true) }
    catch { $ms.Dispose(); return $null }
    $w = $bmp.Width; $h = $bmp.Height
    $fmt = Get-ImageFormatName $bmp.RawFormat
    $bmp.Dispose()
    $ms.Dispose()
    return New-Object psobject -Property @{ Width = $w; Height = $h; Format = $fmt; Bytes = $bytes }
}

function Save-ScaledPng([System.Drawing.Bitmap]$src, [int]$size, [string]$destPath, [bool]$dryRun) {
    $destDir = Split-Path -Parent $destPath
    if (-not $dryRun -and -not (Test-Path -LiteralPath $destDir)) {
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    }
    $iw = $src.Width; $ih = $src.Height
    $side = [Math]::Min($iw, $ih)
    $sx = [int][Math]::Floor(($iw - $side) / 2.0)          # 非正方形 -> 按短边居中裁剪
    $sy = [int][Math]::Floor(($ih - $side) / 2.0)
    $srcRect = New-Object System.Drawing.Rectangle($sx, $sy, $side, $side)
    $dstRect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $dst = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $g = [System.Drawing.Graphics]::FromImage($dst)
        try {
            $g.CompositingMode    = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
            $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $g.DrawImage($src, $dstRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
        }
        finally { $g.Dispose() }
        if (-not $dryRun) { $dst.Save($destPath, [System.Drawing.Imaging.ImageFormat]::Png) }
    }
    finally { $dst.Dispose() }
}

# ------------------------------------------------------------------ 预设目录扫描
function Get-PresetFiles([string]$dir) {
    $images = New-Object System.Collections.ArrayList
    $nonImage = New-Object System.Collections.ArrayList
    $unsupported = New-Object System.Collections.ArrayList
    if (Test-Path -LiteralPath $dir) {
        # 排序：按文件名（Ordinal）稳定编号 —— 与 README 中「图标轴编号」的承诺一致
        $all = @(Get-ChildItem -LiteralPath $dir -File -Force | Sort-Object -Property Name)
        foreach ($f in $all) {
            $ext = $f.Extension.ToLowerInvariant()
            if ($script:CopyExts -contains $ext) { [void]$images.Add($f) }
            elseif ($script:OtherImgExts -contains $ext) { [void]$unsupported.Add($f) }
            else { [void]$nonImage.Add($f) }        # .json / README.md 之类的附属文件，属正常
        }
    }
    return New-Object psobject -Property @{ Images = $images; NonImage = $nonImage; Unsupported = $unsupported }
}

# 读取同名 .json 覆盖（当前只支持 displayName 一个字段）
function Get-JsonOverride([string]$dir, [string]$stem) {
    $jsonPath = Join-Path $dir ($stem + '.json')
    if (-not (Test-Path -LiteralPath $jsonPath -PathType Leaf)) {
        return New-Object psobject -Property @{ HasOverride = $false; DisplayName = '' }
    }
    try {
        $raw = Read-TextFileInfo $jsonPath
        $obj = $raw.Text | ConvertFrom-Json
    }
    catch { Die ('同名 JSON 解析失败：' + (Split-Path -Leaf $jsonPath) + ' -> ' + $_.Exception.Message) }
    if ($null -eq $obj) { Die ('同名 JSON 是空的：' + (Split-Path -Leaf $jsonPath)) }
    $has = $false; $name = ''
    foreach ($p in $obj.PSObject.Properties) {
        if ($p.Name -ceq 'displayName') {
            $name = [string]$p.Value
            if ([string]::IsNullOrWhiteSpace($name)) { Die ('同名 JSON 的 displayName 为空：' + (Split-Path -Leaf $jsonPath)) }
            $has = $true
        }
        else {
            Write-Warn2 ('忽略未知字段 "' + $p.Name + '" (' + (Split-Path -Leaf $jsonPath) + ')，当前只支持 displayName')
        }
    }
    return New-Object psobject -Property @{ HasOverride = $has; DisplayName = $name }
}

# 图标显示名（icon_axis 的第 2 段）：会写进 XML 文本，所以只禁「|」（字段分隔符）与换行。
function Assert-ValidIconLabel([string]$name, [string]$fileName) {
    if ([string]::IsNullOrWhiteSpace($name)) {
        Die ('图标显示名不能为空：' + $fileName + ' —— 请重命名文件，或用同名 .json 的 displayName 指定')
    }
    if ($name.IndexOf($script:Bar) -ge 0 -or $name.IndexOf("`r") -ge 0 -or $name.IndexOf("`n") -ge 0 -or $name.IndexOf("`t") -ge 0) {
        Die ('图标显示名里不能出现 ' + $script:Bar + ' 或换行/制表符：' + $fileName + '（显示名="' + $name + '"）')
    }
    # 只做"字符层面"的检查；名称里的 & < > " 由 XML 转义器处理，不禁止。
    if ($name.IndexOfAny([System.IO.Path]::GetInvalidFileNameChars()) -ge 0) {
        Write-Warn2 ('图标显示名包含文件系统非法字符（不影响构建，只影响可读性）：' + $name)
    }
}

# ------------------------------------------------------------------ 名称轴：icons/names.txt
# 返回 psobject：
#   Names  = ArrayList of 名称（j 从 1 开始，即 Names[j-1] 是第 j 个名字）
#   Exists = 文件是否存在
#   Raw    = 有内容的行数（去注释后，去重前；用于报告"少了几个"）
#   Dups   = 被去重掉的名称列表
# 规则：一行一个名字（UTF-8）；# 开头 = 注释；空行忽略；首尾空白去掉；重复名只保留首次出现。
# 非法名（含 | 或含换行）报错并列出全部非法名 —— 不静默丢弃。
function Read-NameAxis([string]$path) {
    $names = New-Object System.Collections.ArrayList
    $dups = New-Object System.Collections.ArrayList
    $invalid = New-Object System.Collections.ArrayList
    $raw = 0
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return New-Object psobject -Property @{ Names = $names; Exists = $false; Raw = 0; Dups = $dups }
    }
    $info = Read-TextFileInfo $path
    # 统一换行符：CRLF / CR / LF 都当分隔符（txt 常常被各种编辑器改行尾）
    $text = $info.Text.Replace("`r`n", "`n").Replace("`r", "`n")
    $seen = @{}
    $lineNo = 0
    foreach ($line in ($text -split "`n")) {
        $lineNo = $lineNo + 1
        $t = $line.Trim()
        if ($t.Length -eq 0) { continue }
        if ($t.StartsWith('#')) { continue }
        if ($t.IndexOf($script:Bar) -ge 0 -or $t.IndexOf("`t") -ge 0) {
            [void]$invalid.Add(('第 ' + $lineNo + ' 行："' + $t + '"（' +
                $(if ($t.IndexOf($script:Bar) -ge 0) { '含 ' + $script:Bar + '（矩阵字段分隔符）' } else { '含制表符' }) + '）'))
            continue
        }
        $raw = $raw + 1
        $key = $t.ToLowerInvariant()          # 去重不看大小写：'CCTV' 与 'cctv' 在桌面上无法区分
        if ($seen.ContainsKey($key)) {
            [void]$dups.Add($t)
            continue
        }
        $seen[$key] = $true
        [void]$names.Add($t)
    }
    if ($invalid.Count -gt 0) {
        Write-Err2 ('icons/names.txt 里有非法名字（' + $invalid.Count + ' 条），已全部列出：')
        foreach ($iv in $invalid) { Write-Host ('        ' + $iv) -ForegroundColor Red }
        # ★ 这里必须用括号把整个表达式括起来：写成 Die 'a' + $x + 'b|c' 时，
        #   PowerShell 会把裸露的 | 当成管道运算符，消息被截断、后半段还会被当成命令执行。
        Die ('请修掉这些名字（不能含 ' + $script:Bar +
             ' / 制表符；名称里的 & < > " 是允许的，会被 XML 转义）后重跑')
    }
    return New-Object psobject -Property @{ Names = $names; Exists = $true; Raw = $raw; Dups = $dups }
}

# ------------------------------------------------------------------ 批量导入
function Add-ScanDir([System.Collections.ArrayList]$list, [string]$path) {
    $resolved = @()
    try { $resolved = @(Resolve-Path -Path $path -ErrorAction Stop) } catch { $resolved = @() }
    if ($resolved.Count -eq 0) {
        Write-Err2 ('源目录不存在，或通配符没有匹配到任何东西：' + $path)
        Die '-Source 指定的源目录不可用'
    }
    foreach ($r in $resolved) {
        if (-not (Test-Path -LiteralPath $r.Path -PathType Container)) {
            Write-Err2 ('不是目录（-Source 必须是目录）：' + $r.Path)
            Die '-Source 必须是目录，不能是文件'
        }
        [void]$list.Add((New-Object psobject -Property @{ Path = $r.Path }))
    }
}

function Get-CandidateNames([string]$stem, [string]$ext, [int]$max, [hashtable]$taken) {
    # 返回 ArrayList（一个对象，不会被管道展开）。调用方不要再用 @() 包一层：
    # @(返回数组的函数) 会把整个数组当成单个元素再包一层，$cands[0] 就变成整个数组了。
    # 序号插在扩展名之前：猫耳.png -> 猫耳-2.png（而不是 猫耳.png-2）
    $out = New-Object System.Collections.ArrayList
    for ($i = 1; $i -le $max; $i++) {
        $n = $stem + $ext
        if ($i -gt 1) { $n = $stem + '-' + $i + $ext }
        if (-not $taken.ContainsKey($n.ToLowerInvariant())) { [void]$out.Add($n) }
    }
    return $out
}

function Test-TargetTaken([string]$destDir, [string]$name, [hashtable]$taken) {
    if ($taken.ContainsKey($name.ToLowerInvariant())) { return $true }
    if (Test-Path -LiteralPath (Join-Path $destDir $name) -PathType Leaf) { return $true }
    return $false
}

# 确定性命名：名字.ext -> 名字-2.ext -> 名字-3.ext ... 返回第一个空闲名 + 途中被占用的名字
# 注意：不要在调用处写 @(Resolve-ImportTarget ...) —— 函数返回单个 psobject，
# 用 @() 包一层会得到单元素数组，后续 .Name 就可能取到数组而不是字符串。
function Resolve-ImportTarget([string]$destDir, [string]$stem, [string]$ext, [hashtable]$taken) {
    $cands = Get-CandidateNames -stem $stem -ext $ext -max 200 -taken $taken
    $blocked = New-Object System.Collections.ArrayList
    if ($cands.Count -eq 0) {
        return (New-Object psobject -Property @{ Name = ($stem + '-x' + $ext); Free = @() })
    }
    foreach ($c in $cands) {
        if (-not (Test-TargetTaken -destDir $destDir -name $c -taken $taken)) {
            return (New-Object psobject -Property @{ Name = [string]$c; Free = $blocked.ToArray() })
        }
        [void]$blocked.Add($c)
    }
    return (New-Object psobject -Property @{ Name = [string]$cands[$cands.Count - 1]; Free = @() })
}

# 内容已在 icons/presets/ 里 -> 返回它（幂等：同一批图片重复导入不会多出一份）。
# 三种命中方式：
#   a) 哈希直接命中目录里的文件（最常见）；
#   b) 源文件本身就是命中的那个文件（InPlace，例如 -Source 指到了 icons/presets/ 自身）；
#   c) 名字带确定性序号（猫耳-2.png）且去掉序号后的"本名"也在库里
#      —— 说明上一批把它改名落到了这里，这次仍算「已导入」。
# 返回 $null 表示内容不在库里，需要真正导入。
# 注意：判断"是不是同一个文件"必须比较完整路径，不能只比较所在目录 ——
# 外部源目录里的文件按内容命中库内文件时，两者所在目录相同但路径不同，那属于
# 「已导入过」，必须如实报告，不能当成 InPlace 静默略过。
function Find-ExistingMatch([string]$presetsDir, [string]$srcFullPath, [string]$hash, [hashtable]$hashIndex,
                            [string]$srcName, [string]$srcStem, [string]$srcExt, [hashtable]$taken) {
    if ($hashIndex.ContainsKey($hash)) {
        $hit = @($hashIndex[$hash])[0]
        $inPlace = Test-SamePath $hit.FullName $srcFullPath
        return (New-Object psobject -Property @{ Name = [string]$hit.Name; Renamed = $false; InPlace = $inPlace })
    }
    if ($srcStem -match '^(?<root>.+)-[0-9]+$') {
        $rootName = $Matches['root'] + $srcExt
        $rootPath = Join-Path $presetsDir $rootName
        if ((Test-Path -LiteralPath $rootPath -PathType Leaf) -and
            (-not (Test-TargetTaken -destDir $presetsDir -name $srcName -taken $taken)) -and
            ((Get-FileSha256 $rootPath) -eq $hash)) {
            return (New-Object psobject -Property @{ Name = $srcName; Renamed = $true; InPlace = $false })
        }
    }
    return $null
}

function Invoke-BatchImport {
    param(
        [System.Collections.ArrayList]$Dirs,
        [string]$PresetsDir,
        $Counters,
        [bool]$MoveFiles,
        [bool]$Force,
        [bool]$Recurse,
        [bool]$DryRun
    )

    # ---- 扫描源目录
    $scan = New-Object System.Collections.ArrayList
    $skipLog = New-Object System.Collections.ArrayList
    $seenByHash = @{}
    foreach ($d in $Dirs) {
        $files = @(Get-ChildItem -LiteralPath $d.Path -File -Recurse:$Recurse -Force -ErrorAction SilentlyContinue |
                   Sort-Object -Property FullName)
        $img = New-Object System.Collections.ArrayList
        $otherImg = New-Object System.Collections.ArrayList
        $nonImg = New-Object System.Collections.ArrayList
        foreach ($f in $files) {
            $e = $f.Extension.ToLowerInvariant()
            if ($script:CopyExts -contains $e) { [void]$img.Add($f) }
            elseif ($script:OtherImgExts -contains $e) { [void]$otherImg.Add($f) }
            else { [void]$nonImg.Add($f) }
        }
        Inc $Counters 'ScanDirs'
        Add2 $Counters 'Found' $img.Count
        Write-Info ('扫描 ' + (Get-ShortName $d.Path) + ' -> 图片 ' + $img.Count + ' 个，非图片 ' + $nonImg.Count +
            ' 个，不支持的图片格式 ' + $otherImg.Count + ' 个（递归=' + $Recurse + '）')
        foreach ($f in $nonImg) {
            [void]$skipLog.Add((New-Object psobject -Property @{ File = $f.FullName; Reason = '非图片文件（扩展名 ' + $f.Extension + '）' }))
            Inc $Counters 'NonImage'; Inc $Counters 'Skipped'
        }
        foreach ($f in $otherImg) {
            [void]$skipLog.Add((New-Object psobject -Property @{ File = $f.FullName; Reason = '不支持的图片格式（本脚本只处理 PNG / JPG / JPEG）' }))
            Inc $Counters 'NonImage'; Inc $Counters 'Skipped'
        }
        foreach ($f in $img) {
            $h = Get-FileSha256 $f.FullName
            if ($seenByHash.ContainsKey($h)) {
                [void]$skipLog.Add((New-Object psobject -Property @{
                    File = $f.FullName
                    Reason = '内容与本批已扫描的文件完全相同：' + (Get-ShortName $seenByHash[$h])
                }))
                Inc $Counters 'Skipped'
                continue
            }
            $seenByHash[$h] = $f.FullName
            [void]$scan.Add((New-Object psobject -Property @{ File = $f; Hash = $h }))
        }
    }

    # ---- 现有 icons/presets/ 的占用情况
    $existing = @()
    if (Test-Path -LiteralPath $PresetsDir) {
        $existing = @(Get-ChildItem -LiteralPath $PresetsDir -File -Force |
                      Where-Object { $script:CopyExts -contains $_.Extension.ToLowerInvariant() } |
                      Sort-Object -Property Name)
    }
    $hashIndex = @{}
    foreach ($e in $existing) {
        $eh = Get-FileSha256 $e.FullName
        if (-not $hashIndex.ContainsKey($eh)) { $hashIndex[$eh] = New-Object System.Collections.ArrayList }
        [void]$hashIndex[$eh].Add($e)
    }

    $destFull = [System.IO.Path]::GetFullPath($PresetsDir)
    $taken = @{}
    $plan = New-Object System.Collections.ArrayList

    foreach ($s in $scan) {
        $srcDirFull = [System.IO.Path]::GetFullPath((Split-Path -Parent $s.File.FullName))
        $alreadyHere = Test-SamePath $srcDirFull $destFull
        $srcStem = [System.IO.Path]::GetFileNameWithoutExtension($s.File.Name)
        $srcExt = $s.File.Extension.ToLowerInvariant()

        # 1) 源文件本来就在 icons/presets/ 里 / 内容已在库里 -> 「已导入」，不改动（保证重复运行幂等）
        $matched = Find-ExistingMatch -presetsDir $PresetsDir -srcFullPath $s.File.FullName -hash $s.Hash `
                                      -hashIndex $hashIndex -srcName $s.File.Name -srcStem $srcStem `
                                      -srcExt $srcExt -taken $taken
        if ($null -ne $matched) {
            $kind = 'SkipDuplicate'
            $renTo = ''
            if ($matched.InPlace) { $kind = 'InPlace' }
            if ($matched.Renamed) { $renTo = $matched.Name }   # 只是确定性序号，说明它就是本次的落点
            [void]$plan.Add((New-Object psobject -Property @{ Kind = $kind; Src = $s.File; Name = [string]$matched.Name; RenameTo = $renTo }))
            if ($kind -eq 'SkipDuplicate') { Inc $Counters 'Skipped'; Inc $Counters 'AlreadyPresent' }
            $taken[$matched.Name.ToLowerInvariant()] = $true
            continue
        }
        if ($alreadyHere) { continue }   # 源就在目标目录里但内容没匹配上（同名不同内容且被占用）-> 极罕见，跳过

        # 2) -Force + 同名文件已存在 -> 明确覆盖（逐条记录）
        if ($Force -and (Test-TargetTaken -destDir $PresetsDir -name $s.File.Name -taken $taken)) {
            [void]$plan.Add((New-Object psobject -Property @{ Kind = 'Overwrite'; Src = $s.File; Name = $s.File.Name; RenameTo = '' }))
            $taken[$s.File.Name.ToLowerInvariant()] = $true
            continue
        }

        # 3) 确定性命名：名字.ext -> 名字-2.ext -> 名字-3.ext ...（目标名冲突一律加序号，绝不覆盖）
        $r = Resolve-ImportTarget -destDir $PresetsDir -stem $srcStem -ext $srcExt -taken $taken
        $kind = 'Import'
        $renameTo = ''
        if ($r.Free.Count -gt 0) {
            $kind = 'ImportRenamed'
            $renameTo = [string]$r.Name
            Inc $Counters 'Renamed'
        }
        [void]$plan.Add((New-Object psobject -Property @{ Kind = $kind; Src = $s.File; Name = [string]$r.Name; RenameTo = $renameTo }))
        $taken[$r.Name.ToLowerInvariant()] = $true
    }

    # ---- 真正落盘
    $overwriteLog = New-Object System.Collections.ArrayList
    if (-not $DryRun) { New-Item -ItemType Directory -Force -Path $PresetsDir | Out-Null }
    foreach ($p in $plan) {
        if ($p.Kind -eq 'InPlace' -or $p.Kind -eq 'SkipDuplicate') { continue }
        $dest = Join-Path $PresetsDir $p.Name
        if ($DryRun) {
            if ($p.Kind -eq 'Overwrite') { Inc $Counters 'Overwritten'; [void]$overwriteLog.Add($p.Name) }
            else { Inc $Counters 'Imported' }
            continue
        }
        if ($p.Kind -eq 'Overwrite') {
            Inc $Counters 'Overwritten'
            [void]$overwriteLog.Add($p.Name)
            Write-Warn2 ('覆盖 icons/presets/' + $p.Name + '（旧内容已丢弃）')
        }
        else { Inc $Counters 'Imported' }
        if ($MoveFiles) {
            if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
            [System.IO.File]::Move($p.Src.FullName, $dest)
            Inc $Counters 'Moved'
        }
        else {
            Copy-Item -LiteralPath $p.Src.FullName -Destination $dest -Force
        }
    }

    # ---- 逐条报告
    $renames = @($plan | Where-Object { $_.RenameTo -ne '' })
    if ($renames.Count -gt 0) {
        Write-Info ('批量重命名 ' + $renames.Count + ' 条（同名冲突，确定性加序号；图标显示名随之变化）：')
        foreach ($d in $renames) {
            $oldName = [System.IO.Path]::GetFileNameWithoutExtension($d.Src.Name)
            $newName = [System.IO.Path]::GetFileNameWithoutExtension($d.Name)
            Write-Host ('        ' + $d.Src.Name + '  ->  ' + $d.Name + '   (显示名 "' + $oldName + '" -> "' + $newName + '")') -ForegroundColor Yellow
        }
    }
    if ($overwriteLog.Count -gt 0) {
        Write-Info ('被覆盖 ' + $overwriteLog.Count + ' 个（-Force）：')
        foreach ($o in $overwriteLog) { Write-Host ('        icons/presets/' + $o) -ForegroundColor Yellow }
    }
    $already = @($plan | Where-Object { $_.Kind -eq 'SkipDuplicate' })
    if ($already.Count -gt 0) {
        Write-Info ('已导入过（内容相同，未重复添加）' + $already.Count + ' 条：')
        foreach ($d in $already) {
            $arrowName = $d.Name
            if ($d.RenameTo -ne '') { $arrowName = $d.Src.Name + ' -> ' + $d.Name }
            Write-Host ('        ' + $arrowName) -ForegroundColor Gray
        }
    }
    if ($skipLog.Count -gt 0) {
        Write-Info ('扫描阶段跳过 ' + $skipLog.Count + ' 条（逐条原因）：')
        foreach ($d in $skipLog) { Write-Host ('        ' + $d.File + '：' + $d.Reason) -ForegroundColor Yellow }
    }

    return New-Object psobject -Property @{
        Dirs       = $Dirs
        Found      = $Counters['Found']
        Imported   = $Counters['Imported']
        Reused     = $already.Count
        Renames    = $renames
        Overwrites = $overwriteLog
        Skips      = $skipLog
    }
}

# ------------------------------------------------------------------ 生成：位图 / 资源数组 / manifest 区块
function Remove-OldBitmaps($Counters, [bool]$DryRun) {
    $removed = New-Object System.Collections.ArrayList
    foreach ($k in $script:Densities.Keys) {
        $dir = Join-Path $script:ResDir $k
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $hits = @(Get-ChildItem -LiteralPath $dir -File -Force | Where-Object { $_.Name -match $script:BitmapRegex })
        foreach ($h in $hits) {
            [void]$removed.Add($k + '/' + $h.Name)
            Inc $Counters 'OldBitmaps'
            if (-not $DryRun) { Remove-Item -LiteralPath $h.FullName -Force }
        }
        # 清空后不留空目录，保持工作区干净（下次生成会自动重建）
        if (-not $DryRun) {
            $left = @(Get-ChildItem -LiteralPath $dir -Force)
            if ($left.Count -eq 0) { Remove-Item -LiteralPath $dir -Force }
        }
    }
    return $removed
}

# 双保险：整块重建之后，再扫一遍"下标超出现有图标数"的位图并删掉。
# （正常路径上 Remove-OldBitmaps 已经清空过，这里是防止旧版本脚本残留 / 手工放进来的图）
function Remove-StaleBitmaps([int]$iconCount, $Counters, [bool]$DryRun) {
    $stale = New-Object System.Collections.ArrayList
    foreach ($k in $script:Densities.Keys) {
        $dir = Join-Path $script:ResDir $k
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $hits = @(Get-ChildItem -LiteralPath $dir -File -Force | Where-Object { $_.Name -match $script:BitmapRegex })
        foreach ($h in $hits) {
            $mm = [regex]::Match($h.Name, $script:BitmapRegex)
            $n = [int]$mm.Groups[1].Value
            if ($n -ge 1 -and $n -le $iconCount) { continue }
            [void]$stale.Add($k + '/' + $h.Name)
            Inc $Counters 'StaleBitmaps'
            if (-not $DryRun) { Remove-Item -LiteralPath $h.FullName -Force }
        }
    }
    if ($stale.Count -gt 0) {
        if ($DryRun) { Write-Warn2 ('DryRun：本会清理 ' + $stale.Count + ' 个下标超界的位图：' + ($stale -join ', ')) }
        else { Write-Warn2 ('清理 ' + $stale.Count + ' 个下标超界的位图（不再需要的格子）：' + ($stale -join ', ')) }
    }
    return $stale
}

# 用真正的 XML 序列化器产出「XML 片段文本」（不是拼串）：
#   · 每个元素是 XmlDocument 里的真节点 -> 属性值自动转义（& < > " 都安全）
#   · 元素上带 xmlns:android 声明，所以片段能单独解析、也能原样插进 manifest
#   · 序列化时强制 LF + UTF-8 + 不缩进，缩进由我们给（保持一致、可读）
function ConvertTo-XmlFragment([System.Xml.XmlNode]$node) {
    $sw = New-Object System.IO.StringWriter
    $settings = New-Object System.Xml.XmlWriterSettings
    $settings.Indent = $false
    $settings.OmitXmlDeclaration = $true
    $settings.NewLineChars = $script:NL
    $settings.NewLineHandling = [System.Xml.NewLineHandling]::None
    $settings.Encoding = $script:UTF8NoBom
    $settings.ConformanceLevel = [System.Xml.ConformanceLevel]::Fragment
    $w = $null
    try {
        $w = [System.Xml.XmlWriter]::Create($sw, $settings)
        $node.WriteTo($w)
        $w.Flush()
    }
    finally {
        if ($null -ne $w) { $w.Dispose() }
    }
    return $sw.ToString()
}

# 生成一个 <activity-alias> 元素（返回 DOM 节点，由调用方序列化）。
# iconSpec / labelSpec 形如 '@mipmap/ic_launcher_preset_3'、'@string/app_name'、或一个字面名字。
function New-AliasNode([string]$aliasName, [string]$iconSpec, [string]$labelSpec) {
    $doc = New-Object System.Xml.XmlDocument
    $el = $doc.CreateElement('activity-alias')
    # 先在元素上声明 android 前缀（属性名字面量写 "xmlns:android"）：
    #   · 片段因此能**单独**被解析，不依赖外层 manifest 的 xmlns；
    #   · 前缀是我们指定的 "android"，而不是 XmlWriter 自己编的 p1:/d1p1:；
    #   · 它排在属性列表最前面，后面的属性顺序与契约里手写的样子一致。
    [void]$el.SetAttribute('xmlns:android', $script:AndroidNs)
    # ★ [void] 一个都不能省：SetAttribute() 返回被写入的字符串（XmlElement.SetAttribute(string,string,string)），
    #   不吞掉的话它们会跟着 return 一起进入输出流，函数就"返回 9 个对象"而不是一个元素。
    [void]$el.SetAttribute('name', $script:AndroidNs, $aliasName)
    [void]$el.SetAttribute('enabled', $script:AndroidNs, 'false')
    # API 31+ 强制要求带 intent-filter 的组件显式声明 exported，漏了会安装失败
    [void]$el.SetAttribute('exported', $script:AndroidNs, 'true')
    [void]$el.SetAttribute('targetActivity', $script:AndroidNs, '.MainActivity')
    [void]$el.SetAttribute('icon', $script:AndroidNs, $iconSpec)
    [void]$el.SetAttribute('label', $script:AndroidNs, $labelSpec)
    [void]$doc.AppendChild($el)

    $filter = $doc.CreateElement('intent-filter')
    $action = $doc.CreateElement('action')
    [void]$action.SetAttribute('name', $script:AndroidNs, 'android.intent.action.MAIN')
    $category = $doc.CreateElement('category')
    [void]$category.SetAttribute('name', $script:AndroidNs, 'android.intent.category.LAUNCHER')
    [void]$filter.AppendChild($action)
    [void]$filter.AppendChild($category)
    [void]$el.AppendChild($filter)
    return $el
}

# 别名区块文本 = BEGIN 标记 + 每个格子一个 <activity-alias> + 空行 + END 标记。
# $Entries 的每一项：Alias / Icon / Label（都是字符串）。
function New-GeneratedBlock($Entries) {
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add($script:MarkerBegin)
    [void]$lines.Add('')
    $i2 = '    '
    foreach ($e in $Entries) {
        # 接收端不要写 [System.Xml.XmlNode]：函数的返回值经 PowerShell 包装后是 Object[]，
        # 绑到强类型参数上会抛 "Cannot convert the System.Object[] ... to XmlNode"。
        # 这里收 object，再自己确认只有一个节点。
        $nodes = @(New-AliasNode -aliasName $e.Alias -iconSpec $e.Icon -labelSpec $e.Label)
        if ($nodes.Count -ne 1 -or -not ($nodes[0] -is [System.Xml.XmlNode])) {
            Die ('内部错误：别名 ' + $e.Alias + ' 的元素构造结果异常（count=' + $nodes.Count + '）')
        }
        $frag = ConvertTo-XmlFragment -node $nodes[0]
        foreach ($fl in ($frag -split $script:NL)) {
            [void]$lines.Add($i2 + $fl)
        }
    }
    [void]$lines.Add('')
    [void]$lines.Add($script:MarkerEnd)
    return ($lines -join $script:NL)
}

# 生成区块自检：解析出来，逐个核对属性与 intent-filter —— 任何一个格子写错都在写盘前拦下。
function Assert-GeneratedBlock([string]$blockText, $Entries) {
    $frag = New-Object System.Xml.XmlDocument
    try {
        $frag.PreserveWhitespace = $true
        $frag.LoadXml($script:Lt + 'wrap' + $script:Gt + $blockText + $script:Lt + '/wrap' + $script:Gt)
    }
    catch {
        Write-Err2 ('生成区块自身不是合法 XML（未写盘）：' + $_.Exception.Message)
        Die '生成区块校验失败'
    }
    $wrapped = $frag.DocumentElement
    $nodes = @($wrapped.SelectNodes('activity-alias'))
    if ($nodes.Count -ne $Entries.Count) {
        Write-Err2 ('生成区块里的 activity-alias 数量 ' + $nodes.Count + ' != 期望 ' + $Entries.Count)
        Die '生成区块校验失败'
    }
    $seen = @{}
    for ($k = 0; $k -lt $nodes.Count; $k++) {
        $n = $nodes[$k]
        $e = $Entries[$k]
        $nm = $n.GetAttribute('name', $script:AndroidNs)
        if ($nm -ne $e.Alias) { Write-Err2 ('第 ' + ($k + 1) + ' 个别名 name="' + $nm + '" != "' + $e.Alias + '"'); Die '生成区块校验失败' }
        if ($seen.ContainsKey($nm)) { Write-Err2 ('别名重名：' + $nm); Die '生成区块校验失败' }
        $seen[$nm] = $true
        if ($n.GetAttribute('enabled', $script:AndroidNs) -ne 'false') { Write-Err2 ('别名 ' + $nm + ' 的 enabled 不是 false'); Die '生成区块校验失败' }
        if ($n.GetAttribute('exported', $script:AndroidNs) -ne 'true') { Write-Err2 ('别名 ' + $nm + ' 缺 android:exported="true"（API 31+ 会安装失败）'); Die '生成区块校验失败' }
        if ($n.GetAttribute('targetActivity', $script:AndroidNs) -ne '.MainActivity') { Write-Err2 ('别名 ' + $nm + ' 的 targetActivity 不是 .MainActivity'); Die '生成区块校验失败' }
        if ($n.GetAttribute('icon', $script:AndroidNs) -ne $e.Icon) { Write-Err2 ('别名 ' + $nm + ' 的 icon 不符：' + $n.GetAttribute('icon', $script:AndroidNs)); Die '生成区块校验失败' }
        if ($n.GetAttribute('label', $script:AndroidNs) -ne $e.Label) { Write-Err2 ('别名 ' + $nm + ' 的 label 不符：' + $n.GetAttribute('label', $script:AndroidNs)); Die '生成区块校验失败' }
        $f = @($n.SelectNodes('intent-filter'))
        if ($f.Count -ne 1) { Write-Err2 ('别名 ' + $nm + ' 的 intent-filter 数量 = ' + $f.Count); Die '生成区块校验失败' }
        $a = @($f[0].SelectNodes('action'))
        $c = @($f[0].SelectNodes('category'))
        if ($a.Count -ne 1 -or $a[0].GetAttribute('name', $script:AndroidNs) -ne 'android.intent.action.MAIN') {
            Write-Err2 ('别名 ' + $nm + ' 缺 action MAIN'); Die '生成区块校验失败'
        }
        if ($c.Count -ne 1 -or $c[0].GetAttribute('name', $script:AndroidNs) -ne 'android.intent.category.LAUNCHER') {
            Write-Err2 ('别名 ' + $nm + ' 缺 category LAUNCHER'); Die '生成区块校验失败'
        }
    }
}

function Get-OldAliasCountInManifest([string]$text) {
    $i = $text.IndexOf($script:MarkerBegin, [System.StringComparison]::Ordinal)
    if ($i -lt 0) { return 0 }
    $j = $text.IndexOf($script:MarkerEnd, $i, [System.StringComparison]::Ordinal)
    if ($j -lt 0) { return 0 }
    return ([regex]::Matches($text.Substring($i, $j - $i), '<activity-alias\b')).Count
}

# 清掉「标记区块之外」的本脚本命名别名（旧版本脚本 / 手工拷贝留下的）。
# 只在确实存在时动它；任何结构异常都中止并保留原文件。
function Remove-AliasesOutsideMarkers([string]$text, $Counters, [bool]$DryRun) {
    $removed = New-Object System.Collections.ArrayList
    $t = $text
    $bi = $t.IndexOf($script:MarkerBegin, [System.StringComparison]::Ordinal)
    $guard = 0
    while ($true) {
        $guard = $guard + 1
        if ($guard -gt 200) { Die '清理标记区块外的闲聊名时循环次数异常（>200），已中止，manifest 未改动' }
        $bi = $t.IndexOf($script:MarkerBegin, [System.StringComparison]::Ordinal)
        if ($bi -lt 0) { $bi = $t.Length }      # 没有标记区块：整份文件都算"区块外"
        $open = $t.IndexOf('<activity-alias', [System.StringComparison]::Ordinal)
        if ($open -lt 0 -or $open -gt $bi) { break }   # 区块外（含标记之后）没有别名的，走人
        $gt = $t.IndexOf('>', $open)
        if ($gt -lt 0) { Die 'manifest 里的 <activity-alias 没有结束的 ">"，已中止，manifest 未改动' }
        $selfClosed = ($t.Substring($open, $gt - $open + 1).EndsWith('/>'))
        $closeTag = '</activity-alias>'
        $end = $gt
        if (-not $selfClosed) {
            $end = $t.IndexOf($closeTag, $gt, [System.StringComparison]::Ordinal)
            if ($end -lt 0) { Die 'manifest 里的 <activity-alias> 没有对应的 </activity-alias>，已中止，manifest 未改动' }
            $end = $end + $closeTag.Length
        }
        else {
            $end = $gt + 1
        }
        $seg = $t.Substring($open, $end - $open)
        $m = [regex]::Match($seg, 'android:name\s*=\s*"([^"]*)"')
        if (-not $m.Success) { Die '标记区块外有一个没有 android:name 的 activity-alias，已中止，manifest 未改动' }
        $nm = $m.Groups[1].Value
        if ($nm -notmatch $script:AliasGridRegex -and $nm -notmatch $script:AliasLegacyRegex) {
            # 不是本脚本生成的别名（用户自己写的）-> 立刻停手，避免误删
            Write-Err2 ('标记区块外有一个不是本脚本生成的 activity-alias：android:name="' + $nm + '"')
            Write-Err2 ('        为避免误删，脚本不继续。请手工确认：要么删掉它，要么把它的名字改成别的（不要用 IconPreset_<i>_<j> / IconPreset<N>）')
            Die '检测到标记区块外的外来别名'
        }
        [void]$removed.Add($nm)
        Inc $Counters 'StaleAliases'
        # 连同它前面那一整行的缩进空白一起删掉，不留空行
        $lineStart = $t.LastIndexOf($script:NL, $open)
        if ($lineStart -lt 0) { $lineStart = -1 }
        if ($t.Substring($lineStart + 1, $open - $lineStart - 1).Trim().Length -ne 0) { $lineStart = $open - 1 }
        $t = $t.Substring(0, $lineStart + 1) + $t.Substring($end)
    }
    if ($removed.Count -gt 0) {
        if ($DryRun) { Write-Warn2 ('DryRun：本会删除标记区块外的 ' + $removed.Count + ' 个旧别名：' + ($removed -join ', ')) }
        else { Write-Warn2 ('删除标记区块外的 ' + $removed.Count + ' 个旧别名（旧命名/残留，避免指向不存在的资源）：' + ($removed -join ', ')) }
    }
    return New-Object psobject -Property @{ Text = $t; Removed = $removed }
}

# ★ 本函数只做「读原文件 -> 在内存里生成新区块文本 -> 重新解析校验」，**一个字节都不写盘**。
#   写盘统一在「写入生成物」那一步由 Write-FileVerified 完成（写完立刻读回比对）。
#   历史缺陷：这里曾有一个受 $Pending.WriteManifest 控制的提前写盘分支，而那个标志
#   从初始化到脚本结束**永远是 $false** —— 于是"先校验、后统一落盘"退化成"只校验、从不落盘"，
#   脚本却照旧打印 [OK] 并退出 0。同一个 $Pending 开关也短路了三个数组的写盘。
function Update-ManifestGenerationBlock([string]$blockText, $Entries, $Counters, [bool]$DryRun) {
    $info = Read-TextFileInfo $script:ManifestPath
    $origText = $info.Text
    $text = $origText
    $nl = $script:NL
    $oldCount = Get-OldAliasCountInManifest $text

    # 0) 先清掉标记区块之外的旧别名（旧版本脚本残留），再做区块替换
    $strip = Remove-AliasesOutsideMarkers -text $text -Counters $Counters -DryRun $DryRun
    $staleCount = @($strip.Removed).Count
    $text = $strip.Text

    $bi = $text.IndexOf($script:MarkerBegin, [System.StringComparison]::Ordinal)
    if ($bi -ge 0) {
        $ei = $text.IndexOf($script:MarkerEnd, $bi, [System.StringComparison]::Ordinal)
        if ($ei -lt 0) { Die ('manifest 里有 BEGIN 标记但没有 END 标记：' + $script:ManifestPath + ' —— 请手工修复后再跑') }
        $markerCount = ([regex]::Matches($text, [regex]::Escape($script:MarkerBegin))).Count
        if ($markerCount -gt 1) { Die ('manifest 里有 ' + $markerCount + ' 对生成标记，只允许 1 对：' + $script:ManifestPath + ' —— 请手工删掉多余的') }
        # 复用文件里 BEGIN 标记已有的行首缩进，保证重复运行字节级一致
        $lineStart = $text.LastIndexOf($nl, $bi)
        if ($lineStart -lt 0) { $lineStart = -1 }
        $indent = $text.Substring($lineStart + 1, $bi - $lineStart - 1)
        if ($indent.Trim().Length -ne 0) { $indent = '' }
        $mode = 'marker'
        $rangeStart = $lineStart + 1
        $rangeEnd = $ei + $script:MarkerEnd.Length
    }
    else {
        $close = '</application>'
        $ci = $text.LastIndexOf($close, [System.StringComparison]::Ordinal)
        if ($ci -lt 0) { Die ('manifest 里找不到 </application>，无法插入生成区块：' + $script:ManifestPath) }
        $lineStart = $text.LastIndexOf($nl, $ci)
        if ($lineStart -lt 0) { Die 'manifest 结构异常：</application> 之前没有换行' }
        # 首次插入：放在 </application> 之前，缩进对齐 application 的子节点（8 空格）
        $indent = '        '
        $mode = 'insert'
        $rangeStart = -1
        $rangeEnd = -1
    }

    # 把区块按 indent 缩进；空行不写缩进（否则会留下尾随空格）
    $blockLines = New-Object System.Collections.ArrayList
    foreach ($bl in ($blockText -split $nl)) {
        if ($bl.Trim().Length -eq 0) { [void]$blockLines.Add('') } else { [void]$blockLines.Add($indent + $bl) }
    }
    $block = ($blockLines -join $nl)

    if ($mode -eq 'marker') {
        # 用"行首到 END 标记"的区间整体替换，重复运行也不会丢缩进
        $newText = $text.Substring(0, $rangeStart) + $block + $text.Substring($rangeEnd)
    }
    else {
        $newText = $text.Substring(0, $lineStart + 1) + $block + $nl + $text.Substring($lineStart + 1)
    }

    # 重新解析确认是合法 XML；非法就不写盘（$origText 保持不动）
    try {
        $x = New-Object System.Xml.XmlDocument
        $x.PreserveWhitespace = $true
        $x.LoadXml($newText)
    }
    catch {
        Write-Err2 ('生成区块会让 manifest 变成非法 XML，已放弃写入（原文件未改动）：' + $_.Exception.Message)
        Die 'manifest 校验失败'
    }
    $appCount = $x.SelectNodes('//application').Count
    if ($appCount -ne 1) { Write-Err2 ('manifest 校验失败：application 节点数 = ' + $appCount); Die 'manifest 校验失败' }
    $appNode = $x.SelectSingleNode('//application')
    $aliasNodes = @($appNode.SelectNodes('activity-alias'))
    if ($aliasNodes.Count -ne $Entries.Count) {
        Write-Err2 ('manifest 校验失败：activity-alias 数量 ' + $aliasNodes.Count + ' != 期望 ' + $Entries.Count)
        Die 'manifest 校验失败'
    }
    $names = @{}
    foreach ($an in $aliasNodes) {
        $anName = $an.GetAttribute('name', $script:AndroidNs)
        if ($an.GetAttribute('exported', $script:AndroidNs) -ne 'true') {
            Write-Err2 ('manifest 校验失败：别名 ' + $anName + ' 没有 android:exported="true"（API 31+ 会安装失败）')
            Die 'manifest 校验失败'
        }
        if (@($an.SelectNodes('intent-filter')).Count -lt 1) {
            Write-Err2 ('manifest 校验失败：别名 ' + $anName + ' 没有 intent-filter')
            Die 'manifest 校验失败'
        }
        if ($names.ContainsKey($anName)) { Write-Err2 ('manifest 校验失败：别名重名 ' + $anName); Die 'manifest 校验失败' }
        $names[$anName] = $true
    }
    if ($newText.IndexOf($script:MarkerBegin) -lt 0 -or $newText.IndexOf($script:MarkerEnd) -lt 0) {
        Write-Err2 'manifest 校验失败：写回文本里缺少生成标记'
        Die 'manifest 校验失败'
    }

    $Counters['OldAliases'] = $oldCount
    return New-Object psobject -Property @{ Text = $newText; Mode = $mode; OldCount = $oldCount; OldBitmaps = $staleCount }
}

# ------------------------------------------------------------------ res/values/icon_presets.xml（三个数组）
# 三个数组的分工（运行时侧按同一份契约读）：
#   icon_axis            第 i 项 = 图标档：'图标资源名|图标显示名'，第 0 项资源名留空 = 应用自己的图标
#   name_axis            第 j 项 = 名字档：名字本身，第 0 项留空 = 应用自己的名字（@string/app_name）
#   preset_alias_matrix  每项 = '<i>|<j>|IconPreset_<i>_<j>'，只包含真的生成了别名的格子（除 (0,0) 外全部）
function New-ValuesXmlLines([string[]]$IconAxis, [string[]]$NameAxis, [string[]]$MatrixEntries) {
    $lt = $script:Lt; $gt = $script:Gt; $bar = $script:Bar
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add($lt + 'resources' + $gt)
    [void]$lines.Add('    <!--')
    [void]$lines.Add('      图标 / 名称 预设清单（本文件由 tools/generate-icon-presets.ps1 生成，请勿手改）。')
    [void]$lines.Add('')
    [void]$lines.Add('      为什么是两个数组 + 一个矩阵：<activity-alias> 的 android:icon 与 android:label 是同一个')
    [void]$lines.Add('      组件上的两个属性，没法运行时只改其中一个；所以「图标 i × 名字 j」的每个组合各生成一个')
    [void]$lines.Add('      别名 IconPreset_<i>_<j>，启用哪一个 = 同时选中了 (i, j)。')
    [void]$lines.Add('')
    [void]$lines.Add('      · icon_axis：每项「图标资源名' + $bar + '图标显示名」。第 0 项 = 应用自己的图标（资源名留空，')
    [void]$lines.Add('        别名里写 @mipmap/ic_launcher）；第 i>=1 项 = ic_launcher_preset_<i>，按 icons/presets/ 文件名排序。')
    [void]$lines.Add('      · name_axis：每项一个名字。第 0 项 = 空字符串，表示"应用自己的名字"（别名里写 @string/app_name）；')
    [void]$lines.Add('        第 j>=1 项来自 icons/names.txt 的第 j 个有效行（去注释、去空白、去重）。')
    [void]$lines.Add('      · preset_alias_matrix：每项「i' + $bar + 'j' + $bar + '组件名」，只列真的生成了别名的格子 ——')
    [void]$lines.Add('        格子 (0,0) 不生成别名（它就是主入口 MainActivity 本身），所以条数 = (N+1)*(M+1)-1。')
    [void]$lines.Add('      · 组件名要换算成 <packageName>.IconPreset_<i>_<j> 再交给 ComponentName：')
    [void]$lines.Add('        裸简名（"IconPreset_0_2"）不会被 ComponentName 按包名补全，会落在一个不存在的组件上。')
    [void]$lines.Add('')
    [void]$lines.Add('      ★ 将来发 release 的坑：release 开了 isShrinkResources，资源压缩器看不到 getIdentifier 这种')
    [void]$lines.Add('        "按名字查找"，没有 R.xxx 直接引用的预设图标会被当成无用资源删掉，需要')
    [void]$lines.Add('        app/src/main/res/raw/keep.xml + tools:keep 保住（详见 icons/presets/README.md，本阶段刻意不建）。')
    [void]$lines.Add('        debug 构建不受影响。')
    [void]$lines.Add('    -->')
    [void]$lines.Add('')

    if ($IconAxis.Count -eq 0) {
        [void]$lines.Add('    ' + $lt + 'string-array name="icon_axis" /' + $gt)
    }
    else {
        [void]$lines.Add('    ' + $lt + 'string-array name="icon_axis"' + $gt)
        foreach ($it in $IconAxis) { [void]$lines.Add('        ' + $lt + 'item' + $gt + $it + $lt + '/item' + $gt) }
        [void]$lines.Add('    ' + $lt + '/string-array' + $gt)
    }
    [void]$lines.Add('')
    if ($NameAxis.Count -eq 0) {
        [void]$lines.Add('    ' + $lt + 'string-array name="name_axis" /' + $gt)
    }
    else {
        [void]$lines.Add('    ' + $lt + 'string-array name="name_axis"' + $gt)
        foreach ($it in $NameAxis) { [void]$lines.Add('        ' + $lt + 'item' + $gt + $it + $lt + '/item' + $gt) }
        [void]$lines.Add('    ' + $lt + '/string-array' + $gt)
    }
    [void]$lines.Add('')
    if ($MatrixEntries.Count -eq 0) {
        [void]$lines.Add('    ' + $lt + 'string-array name="preset_alias_matrix" /' + $gt)
    }
    else {
        [void]$lines.Add('    ' + $lt + 'string-array name="preset_alias_matrix"' + $gt)
        foreach ($it in $MatrixEntries) { [void]$lines.Add('        ' + $lt + 'item' + $gt + $it + $lt + '/item' + $gt) }
        [void]$lines.Add('    ' + $lt + '/string-array' + $gt)
    }
    [void]$lines.Add($lt + '/resources' + $gt)
    return $lines
}

# 结构自检：三个数组都在、条数对得上、矩阵项自洽（i/j 在范围内、组件名能反推下标）。
function Assert-ValuesXml([string]$xmlText, [int]$IconCount, [int]$NameCount, [int]$MatrixCount) {
    $doc = New-Object System.Xml.XmlDocument
    try {
        $doc.PreserveWhitespace = $true
        $doc.LoadXml($xmlText)
    }
    catch {
        Write-Err2 ('icon_presets.xml 不是合法 XML（未写盘）：' + $_.Exception.Message)
        Die 'icon_presets.xml 校验失败'
    }
    $expected = @('icon_axis', 'name_axis', 'preset_alias_matrix')
    $arrays = @($doc.DocumentElement.SelectNodes('string-array'))
    if ($arrays.Count -ne $expected.Count) {
        Write-Err2 ('icon_presets.xml 里的 string-array 数量 = ' + $arrays.Count + '，期望 ' + $expected.Count + '（只允许这三个）')
        Die 'icon_presets.xml 校验失败'
    }
    $found = @{}
    foreach ($a in $arrays) { $found[$a.GetAttribute('name')] = @($a.SelectNodes('item')) }
    foreach ($en in $expected) {
        if (-not $found.ContainsKey($en)) { Write-Err2 ('icon_presets.xml 缺少数组 ' + $en); Die 'icon_presets.xml 校验失败' }
    }
    if ($found['icon_axis'].Count -ne ($IconCount + 1)) {
        Write-Err2 ('icon_axis 条数 ' + $found['icon_axis'].Count + ' != N+1 = ' + ($IconCount + 1)); Die 'icon_presets.xml 校验失败'
    }
    if ($found['name_axis'].Count -ne ($NameCount + 1)) {
        Write-Err2 ('name_axis 条数 ' + $found['name_axis'].Count + ' != M+1 = ' + ($NameCount + 1)); Die 'icon_presets.xml 校验失败'
    }
    if ($found['preset_alias_matrix'].Count -ne $MatrixCount) {
        Write-Err2 ('preset_alias_matrix 条数 ' + $found['preset_alias_matrix'].Count + ' != ' + $MatrixCount); Die 'icon_presets.xml 校验失败'
    }
    # 第 0 项是哨兵：按契约它的资源名留空、只写显示名，所以这一项本来就没有 |。
    # 第 i>=1 项必须是「资源名|显示名」两段，且资源名能与下标互相反推。
    $ia = @($found['icon_axis'])
    if ($ia.Count -ge 1) {
        if ($ia[0].InnerText.IndexOf($script:Bar) -ge 0) {
            Write-Err2 ('icon_axis 第 0 项（哨兵）不该含 ' + $script:Bar + '：' + $ia[0].InnerText); Die 'icon_presets.xml 校验失败'
        }
        if ($ia[0].InnerText.Trim().Length -eq 0) {
            Write-Err2 'icon_axis 第 0 项（哨兵）的显示名不能为空'; Die 'icon_presets.xml 校验失败'
        }
    }
    for ($k = 1; $k -lt $ia.Count; $k++) {
        $parts = $ia[$k].InnerText.Split([char]124)
        if ($parts.Length -ne 2) {
            Write-Err2 ('icon_axis 第 ' + $k + ' 项不是「资源名' + $script:Bar + '显示名」两段：' + $ia[$k].InnerText); Die 'icon_presets.xml 校验失败'
        }
        if ($parts[0] -ne ('ic_launcher_preset_' + $k)) {
            Write-Err2 ('icon_axis 第 ' + $k + ' 项的资源名与下标不符：' + $ia[$k].InnerText); Die 'icon_presets.xml 校验失败'
        }
    }
    foreach ($it in $found['preset_alias_matrix']) {
        $parts = $it.InnerText.Split([char]124)
        if ($parts.Length -ne 3) { Write-Err2 ('preset_alias_matrix 条目不是三段：' + $it.InnerText); Die 'icon_presets.xml 校验失败' }
        $di = 0; $dj = 0
        if (-not [int]::TryParse($parts[0], [ref]$di) -or -not [int]::TryParse($parts[1], [ref]$dj)) {
            Write-Err2 ('preset_alias_matrix 的 i/j 不是整数：' + $it.InnerText); Die 'icon_presets.xml 校验失败'
        }
        if ($di -lt 0 -or $di -gt $IconCount -or $dj -lt 0 -or $dj -gt $NameCount) {
            Write-Err2 ('preset_alias_matrix 的下标越界：' + $it.InnerText); Die 'icon_presets.xml 校验失败'
        }
        if (($di -eq 0) -and ($dj -eq 0)) { Write-Err2 'preset_alias_matrix 不该包含 (0,0) 格子'; Die 'icon_presets.xml 校验失败' }
        if ($parts[2] -ne ('IconPreset_' + $di + '_' + $dj)) {
            Write-Err2 ('preset_alias_matrix 的组件名与下标不符：' + $it.InnerText); Die 'icon_presets.xml 校验失败'
        }
    }
}

# ------------------------------------------------------------------ 落盘复核（只读磁盘，独立数一遍）
# 这三个函数是"我调用了写盘"与"文件确实变了"之间的最后一道闸：
# 它们**不接收内存里的字符串**，全部重新从磁盘读文件、重新解析、重新计数，
# 再与本次计划的数量/名字逐项比对；任何一项不符 -> 报错 + 退出码 1。
function Assert-ManifestFileOnDisk($Entries, [int]$BeforeCount) {
    if (-not (Test-Path -LiteralPath $script:ManifestPath -PathType Leaf)) {
        Die ('落盘复核：磁盘上没有 manifest：' + $script:ManifestPath)
    }
    $info = Read-TextFileInfo $script:ManifestPath
    $text = $info.Text
    $rawTotal = ([regex]::Matches($text, '<activity-alias\b')).Count
    $inBlock = Get-OldAliasCountInManifest $text
    if ($rawTotal -ne $Entries.Count) {
        Write-Err2 ('落盘复核：磁盘 manifest 全文有 ' + $rawTotal + ' 个 <activity-alias>，期望 ' + $Entries.Count + ' 个')
        Write-Err2 ('        脚本本次"打算写"的是 ' + $Entries.Count + ' 个 —— 说明写盘没生效，或文件被别的进程改回去了')
        Die '落盘复核失败：manifest 别名数量与计划不符'
    }
    if ($inBlock -ne $Entries.Count) {
        Write-Err2 ('落盘复核：BEGIN/END 生成区块内 ' + $inBlock + ' 个别名 != 期望 ' + $Entries.Count + ' 个')
        Die '落盘复核失败：manifest 生成区块内容与计划不符'
    }
    $doc = New-Object System.Xml.XmlDocument
    try {
        $doc.PreserveWhitespace = $true
        $doc.LoadXml($text)
    }
    catch {
        Write-Err2 ('落盘复核：磁盘 manifest 不是合法 XML：' + $_.Exception.Message)
        Die '落盘复核失败：manifest 非法'
    }
    if ($doc.SelectNodes('//application').Count -ne 1) {
        Write-Err2 '落盘复核：磁盘 manifest 的 application 节点数不为 1'
        Die '落盘复核失败：manifest 结构异常'
    }
    $appNode = $doc.SelectSingleNode('//application')
    $nodes = @($appNode.SelectNodes('activity-alias'))
    if ($nodes.Count -ne $Entries.Count) {
        Write-Err2 ('落盘复核：application 下的 activity-alias 数量 ' + $nodes.Count + ' != ' + $Entries.Count)
        Die '落盘复核失败：manifest 别名数量与计划不符'
    }
    $onDisk = @{}
    foreach ($n in $nodes) { $onDisk[$n.GetAttribute('name', $script:AndroidNs)] = $n }
    foreach ($e in $Entries) {
        if (-not $onDisk.ContainsKey($e.Alias)) {
            Write-Err2 ('落盘复核：磁盘 manifest 里缺少计划中的别名 ' + $e.Alias)
            Die '落盘复核失败：manifest 内容不完整'
        }
        $n = $onDisk[$e.Alias]
        if ($n.GetAttribute('icon', $script:AndroidNs) -ne $e.Icon) {
            Write-Err2 ('落盘复核：别名 ' + $e.Alias + ' 的 icon="' + $n.GetAttribute('icon', $script:AndroidNs) +
                '" != 计划 "' + $e.Icon + '"')
            Die '落盘复核失败：manifest 别名属性不符'
        }
        if ($n.GetAttribute('label', $script:AndroidNs) -ne $e.Label) {
            Write-Err2 ('落盘复核：别名 ' + $e.Alias + ' 的 label="' + $n.GetAttribute('label', $script:AndroidNs) +
                '" != 计划 "' + $e.Label + '"')
            Die '落盘复核失败：manifest 别名属性不符'
        }
        if ($n.GetAttribute('enabled', $script:AndroidNs) -ne 'false' -or
            $n.GetAttribute('exported', $script:AndroidNs) -ne 'true') {
            Write-Err2 ('落盘复核：别名 ' + $e.Alias + ' 的 enabled/exported 不符（应为 enabled=false、exported=true）')
            Die '落盘复核失败：manifest 别名属性不符'
        }
        if ($n.GetAttribute('targetActivity', $script:AndroidNs) -ne '.MainActivity') {
            Write-Err2 ('落盘复核：别名 ' + $e.Alias + ' 的 targetActivity 不是 .MainActivity')
            Die '落盘复核失败：manifest 别名属性不符'
        }
        if (@($n.SelectNodes('intent-filter')).Count -ne 1) {
            Write-Err2 ('落盘复核：别名 ' + $e.Alias + ' 的 intent-filter 数量不是 1')
            Die '落盘复核失败：manifest 别名属性不符'
        }
    }
    # 反向核对：磁盘上不允许留有计划之外的 IconPreset_<i>_<j> / IconPreset<N> 残留。
    # "图标/名字变少后旧别名还在"正是本次缺陷最危险的后果，必须由磁盘事实来判定。
    $extra = New-Object System.Collections.ArrayList
    foreach ($k in @($onDisk.Keys)) {
        if ($k -match $script:AliasGridRegex -or $k -match $script:AliasLegacyRegex) {
            $want = $false
            foreach ($e in $Entries) { if ($e.Alias -ceq $k) { $want = $true; break } }
            if (-not $want) { [void]$extra.Add($k) }
        }
    }
    if ($extra.Count -gt 0) {
        Write-Err2 ('落盘复核：磁盘 manifest 上仍有计划之外的旧别名 ' + $extra.Count + ' 个：' + ($extra -join ', '))
        Die '落盘复核失败：manifest 里有残留别名'
    }
    Write-Info ('AndroidManifest.xml：磁盘实测 <activity-alias> ' + $BeforeCount + ' -> ' + $rawTotal + ' 个' +
        '（期望 ' + $Entries.Count + ' 个，区块内 ' + $inBlock + ' 个）；' +
        $Entries.Count + ' 个计划别名的 icon/label/targetActivity/enabled/exported/intent-filter 逐个核对通过，无残留')
    return (New-Object psobject -Property @{
        AliasCount = $rawTotal; InBlock = $inBlock; Before = $BeforeCount; HasBom = $info.HasBom
    })
}

function Assert-ValuesFileOnDisk([int]$IconCount, [int]$NameCount, [int]$MatrixCount) {
    if (-not (Test-Path -LiteralPath $script:ValuesXmlPath -PathType Leaf)) {
        Die ('落盘复核：磁盘上没有 ' + $script:ValuesXmlPath)
    }
    $info = Read-TextFileInfo $script:ValuesXmlPath
    if ($info.HasBom) {
        Write-Err2 '落盘复核：icon_presets.xml 必须是无 BOM 的 UTF-8，但磁盘上这份带 BOM'
        Die '落盘复核失败：icon_presets.xml 编码不符'
    }
    # 复用既有结构自检：直接在**磁盘文本**上跑，任何一项不符都会 Die
    Assert-ValuesXml -xmlText $info.Text -IconCount $IconCount -NameCount $NameCount -MatrixCount $MatrixCount
    $doc = New-Object System.Xml.XmlDocument
    $doc.PreserveWhitespace = $true
    $doc.LoadXml($info.Text)
    $counts = @{}
    foreach ($a in @($doc.DocumentElement.SelectNodes('string-array'))) {
        $counts[$a.GetAttribute('name')] = @($a.SelectNodes('item')).Count
    }
    $total = [int]$counts['icon_axis'] + [int]$counts['name_axis'] + [int]$counts['preset_alias_matrix']
    $expTotal = ($IconCount + 1) + ($NameCount + 1) + $MatrixCount
    if ($total -ne $expTotal) {
        Write-Err2 ('落盘复核：磁盘数组条目合计 ' + $total + ' != 期望 ' + $expTotal)
        Die '落盘复核失败：icon_presets.xml 内容与计划不符'
    }
    Write-Info ('res/values/icon_presets.xml：磁盘实测 icon_axis ' + $counts['icon_axis'] + ' 条、name_axis ' +
        $counts['name_axis'] + ' 条、preset_alias_matrix ' + $counts['preset_alias_matrix'] + ' 条，共 ' + $total + ' 条' +
        '（期望 ' + ($IconCount + 1) + ' / ' + ($NameCount + 1) + ' / ' + $MatrixCount + '，BOM=False，XML 校验通过）')
    return (New-Object psobject -Property @{
        IconAxis = $counts['icon_axis']; NameAxis = $counts['name_axis']
        Matrix = $counts['preset_alias_matrix']; TotalCount = $total
    })
}

function Assert-ReadmeFileOnDisk([string]$BlockText) {
    $md = Join-Path $script:PresetsDir 'README.md'
    if (-not (Test-Path -LiteralPath $md -PathType Leaf)) { Die ('落盘复核：磁盘上没有 ' + $md) }
    $info = Read-TextFileInfo $md
    if ($info.Text.IndexOf($BlockText, [System.StringComparison]::Ordinal) -lt 0) {
        Write-Err2 '落盘复核：README.md 里找不到本次生成的别名区块（说明它写的不是本次内容）'
        Die '落盘复核失败：README 内容与计划不符'
    }
    $n = ([regex]::Matches($BlockText, '<activity-alias\b')).Count
    Write-Info ('icons/presets/README.md：磁盘实测内嵌生成区块 ' + $n + ' 个别名，与本次区块逐字符一致（BOM=False）')
    if ($info.HasBom) {
        Write-Err2 '落盘复核：README.md 必须是无 BOM 的 UTF-8，但磁盘上这份带 BOM'
        Die '落盘复核失败：README.md 编码不符'
    }
    return (New-Object psobject -Property @{ BlockAliases = $n })
}

# ------------------------------------------------------------------ 矩阵输出
function Write-Matrix([int]$IconCount, [int]$NameCount, [int]$Columns, $Matrix) {
    $colW = 15
    $maxAliasLen = 0
    foreach ($m in $Matrix) {
        if ($m.Alias.Length -gt $maxAliasLen) { $maxAliasLen = $m.Alias.Length }
        if (($m.J.ToString().Length + 2) -gt $colW) { $colW = $m.J.ToString().Length + 2 }
    }
    if ($colW -lt ($maxAliasLen + 2)) { $colW = $maxAliasLen + 2 }

    $line = ('    ' + 'i\j').PadRight(9)
    for ($j = 0; $j -le $NameCount; $j++) {
        $line = $line + ('[' + $j + ']').PadRight($colW)
    }
    Write-Host $line -ForegroundColor DarkGray

    for ($i = 0; $i -le $IconCount; $i++) {
        $cell = '[i=' + $i + ']'
        $line = ('    ' + $cell).PadRight(9)
        for ($j = 0; $j -le $NameCount; $j++) {
            if ($i -eq 0 -and $j -eq 0) { $line = $line + '(0,0)'.PadRight($colW); continue }
            $line = $line + ('IconPreset_' + $i + '_' + $j).PadRight($colW)
        }
        Write-Host $line -ForegroundColor Gray
    }
    Write-Host '    （(0,0) = 主入口 MainActivity 本身，不生成别名）' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '    下标对照（列 = j = 名字，行 = i = 图标）：' -ForegroundColor DarkGray
    Write-Host ('      j=0  -> ' + $script:DefaultNameLabel) -ForegroundColor Gray
    for ($j = 1; $j -le $NameCount; $j++) {
        Write-Host ('      j=' + $j + '  -> ' + $script:NameAxis[$j]) -ForegroundColor Gray
    }
    Write-Host ('      i=0  -> ' + $script:DefaultIconLabel + '（@mipmap/ic_launcher）') -ForegroundColor Gray
    for ($i = 1; $i -le $IconCount; $i++) {
        Write-Host ('      i=' + $i + '  -> ic_launcher_preset_' + $i + '（' + $script:IconLabels[$i] + '）') -ForegroundColor Gray
    }
}

# ------------------------------------------------------------------ icons/presets/README.md
function Write-PresetsReadme([string]$blockText, [int]$IconCount, [int]$NameCount, [int]$AliasTotal) {
    $md = Join-Path $script:PresetsDir 'README.md'
    New-Item -ItemType Directory -Force -Path $script:PresetsDir | Out-Null
    $bt = [string][char]96      # 反引号，避免在下面的字符串里转义
    $bar = $script:Bar
    $esc = [string][char]92     # 反斜杠：表格单元里要写 "\|" 才不会被当成列分隔
    # 注意：多行拼接必须把 "+" 留在**行尾**（PowerShell 5.1 不认识行首的 "+"，
    # 会报 "Missing closing ')' in expression."）。为可读性这里统一先在变量里拼好再进数组。
    $sSource = '目录 `icons/presets/` 与文件 `icons/names.txt` 是**单一事实源**；' +
               '`app/src/main/res/mipmap-*/ic_launcher_preset_*.png`、`res/values/icon_presets.xml`、' +
               '`AndroidManifest.xml` 里的别名区块全是生成物，不要手改。'
    $sWhy = '`<activity-alias>` 的 `android:icon` 与 `android:label` 是**同一个组件上的两个属性**，' +
            '系统没有"运行时只改其中一个"的接口。想让图标和名字各自独立切换，只能为每个组合 `(i, j)` ' +
            '各生成一个别名 `IconPreset_<i>_<j>`，运行时启用哪一个，就等于同时选中了那一对。'
    $sNameRule = '一行一个名字（UTF-8）。`#` 开头的行是注释，空行忽略，首尾空白自动去掉，**重复名只保留第一次出现**' +
                 '（去重不看大小写：`CCTV` 与 `cctv` 在桌面上根本分不开）。'
    $sNameBar = '- 名字里**不能有** `' + $bar + '`（它是 `icon_presets.xml` 里矩阵/轴的字段分隔符）或制表符：' +
                '脚本会报错并逐条列出非法名（不静默丢弃）。'
    $sNameEffect = '- 名字只影响别名的 `android:label`；它同时决定桌面上显示的应用名，' +
                   '所以一个"名字档"就是一个独立的桌面入口（可以和图标档任意组合）。'
    $sImgFormat = '| 格式 | PNG / JPG / JPEG（**WebP 请先转成 PNG** —— GDI+ 在 Windows 上解不了 WebP；' +
                  '.webp/.bmp/.gif 等会报错，其它扩展名按「非图片」跳过） |'
    $sImgNumber = '| 编号 | 按**文件名**排序后的第 i 张 = 图标档 `i`（`ic_launcher_preset_<i>`）；' +
                  '显示名默认取文件名（去扩展名），可用同名 `.json` 覆盖 |'
    $sWarn60 = '- **`(N+1)(M+1) − 1 > 60` 时脚本会打印黄色警告，但不阻止**：别名是真实的 `<activity-alias>` 组件，' +
               '每一个都会被 `PackageManager` 与所有启动器枚举，几百个会明显拖慢安装/枚举，' +
               '部分启动器还会把图标塞进"工作资料/第二页"之类的角落。**这是提醒你"是不是加太多了"，决定权在你。**'
    $sArtBitmap = '| `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_preset_<i>.png` | ' +
                  '48 / 72 / 96 / 144 / 192 px，`HighQualityBicubic` + `HighQuality` PixelOffset + `AntiAlias`；' +
                  '`i` 从 1 开始（`i=0` 就是应用自己的 `mipmap-*/ic_launcher`，不重复生成） |'
    $sArtValues = '| `app/src/main/res/values/icon_presets.xml` | 三个数组：`icon_axis`（`资源名' + $esc + $bar + '显示名`，' +
                  '第 0 项资源名留空）、`name_axis`（第 0 项空字符串）、`preset_alias_matrix`（`i' + $esc + $bar +
                  'j' + $esc + $bar + '组件名`） |'
    $sArtMirror = '| `publish/` 下的镜像副本 | manifest / res / tools / 本 README / `names.txt` / build-and-install.ps1（' +
                  '`publish/icons/presets/` 里的图片**不镜像**，那是本地素材） |'
    $sRebuild = '每次运行都**整块重建**：图标减少时旧的 `ic_launcher_preset_M.png` 会被删掉，别名区块整体重写，' +
                '不会留下指向不存在资源的别名（那种 manifest 会安装失败）。' +
                'manifest 用 XML 解析 + 重新解析校验，非法就放弃写入并保留原文件。'
    $sLimit1 = '1. **新增/改名/删名都必须重装 APK**（`adb install -r`，**应用数据保留**）。' +
               '别名是清单里的组件，只能随 APK 一起进系统；`build-and-install.ps1` 本来就会装，正常流程无感。'
    $sLimit2 = '2. **release 构建开了资源压缩**（`app/build.gradle.kts` 里 `isShrinkResources = true`）：' +
               '运行时用 `getIdentifier` 按名字找资源，压缩器看不到这种引用，会把预设图标当无用资源删掉。' +
               '将来真发 release 时需要新建 `app/src/main/res/raw/keep.xml`：'
    $sLimit4 = '4. 这些别名是 `android:enabled="false"`，只有在运行时用 ' +
               '`PackageManager.setComponentEnabledSetting` 启用之后才会出现在启动器里。'
    $lines = @(
        '# 图标 / 名称 预设（icons/）'
        ''
        '## ★ 新手请直接双击项目根目录的 `管理图标.cmd`'
        ''
        '不用记任何参数：双击之后会出现一个中文菜单，按编号选就行（选 0 退出）。'
        '为什么是双击 `.cmd` 而不是这个目录里的 `.ps1`：Windows 双击 `.ps1` 默认用记事本打开，'
        '而且执行策略通常会拦住它 —— `.cmd` 已经带好了 `-ExecutionPolicy Bypass`，双击就能用。'
        ''
        '| 菜单 | 做什么 |'
        '| --- | --- |'
        '| `1` | 添加图标（从文件夹批量导入，支持子目录、复制不删除原图） |'
        '| `2` | 添加图标（打开 `icons/presets/` 文件夹，自己把图拖进去） |'
        '| `3` | 添加 / 修改应用名称（写进 `icons/names.txt`，只改需要改的那一行） |'
        '| `4` | 删除某个图标或名称（会二次确认，输入 `y` 才删） |'
        '| `5` | 查看全部预设：图标/名称编号 + 组合对照表（只看，不改任何文件） |'
        '| `6` | 生成 APK：重新生成 -> 编译出安装包 -> 打印安装包完整路径（不自动装机） |'
        '| `7` | 清空全部预设（回到默认图标与名称，破坏性操作，要连续确认两次） |'
        '| `8` | 使用说明（图片要求、出错怎么办、怎么装到手机） |'
        '| `0` | 退出 |'
        ''
        '- 图片要求：**PNG / JPG / JPEG**（WebP 请先转 PNG），正方形最好（不是正方形会自动按短边居中裁剪）；'
        '  尺寸建议 `>= 432x432`，短边小于 192 会提示"太小、放上去会糊"但仍会生成。'
        '- 改完图标或名字**一定要选 `6` 重新生成 APK**，再自己装到手机上（应用数据保留，不用先卸载）。'
        '- 出错时菜单会先给一句**中文结论**（例如"这张图不是正方形，已按短边裁掉多余部分"），'
        '  后面灰色的技术细节看不懂可以忽略，需要求助时截图发出来。'
        '- 本 README 是 `tools/generate-icon-presets.ps1` **每次运行自动重写**的，手改会被覆盖；'
        '  下面的"参数化用法"给进阶用户，平时不用看。'
        ''
        '---'
        ''
        '## 参数化用法（进阶）'
        ''
        '两个**独立可切换的轴**：'
        ''
        ('- **图标轴** `i`：`i=0` = 应用自己的图标；`i>=1` = `icons/presets/` 里第 `i` 张图（按**文件名**排序）。')
        ('- **名称轴** `j`：`j=0` = 应用自己的名字（`@string/app_name`）；`j>=1` = `icons/names.txt` 的第 `j` 个有效行。')
        ''
        $sSource
        ''
        '## 一、为什么是"笛卡尔积"'
        ''
        $sWhy
        ''
        ('- 格子 `(0,0)` **不生成别名** —— 它就是主入口 `MainActivity` 本身。')
        ('- 所以别名总数 = `(N+1) × (M+1) − 1`（N = 图标数，M = 名字数）。')
        ('- 例：N=3、M=2 -> `4 × 3 − 1 = 11` 个别名，位图资源 `3 × 5 = 15` 个（每张图 5 个密度）。')
        ''
        '## 二、怎么用'
        ''
        '```powershell'
        '# 方式 1（推荐）：把图放进 icons/presets/、名字写进 icons/names.txt，然后照常构建安装'
        '# （build-and-install.ps1 会先自动调用本脚本重新生成）'
        '.\build-and-install.ps1'
        ''
        '# 方式 2：只重新生成，不构建（改完图/名字想立刻核对清单与资源时用）'
        'powershell -File tools\generate-icon-presets.ps1'
        ''
        '# 只打印计划不写盘'
        'powershell -File tools\generate-icon-presets.ps1 -DryRun'
        ''
        '# 方式 3：回到「零预设」状态（清空 icons/presets/ 的图 + 全部生成物 + 别名区块）'
        'powershell -File tools\generate-icon-presets.ps1 -Clear'
        '```'
        ''
        '每次运行结束都会打印**矩阵规模**与下标对照表，例如：'
        ''
        '```text'
        '发现 3 个图标 × 2 个名字 -> 生成 11 个别名（(3+1)×(2+1)−1）、15 个位图资源、3+2 条轴条目、11 条矩阵条目（旧的 0 个已清理）'
        '矩阵规模：(N+1) × (M+1) − 1 = 4 × 3 − 1 = 11 个别名'
        '    i\j     [0]             [1]             [2]'
        '    [i=0]   (0,0)           IconPreset_0_1  IconPreset_0_2'
        '    [i=1]   IconPreset_1_0  IconPreset_1_1  IconPreset_1_2'
        '    ...'
        '```'
        ''
        '## 三、名称轴：icons/names.txt 的格式'
        ''
        $sNameRule
        ''
        '```text'
        '# 一行一个「应用名称」；# 开头是注释，空行忽略，重复名只保留第一个'
        '主播监控'
        '直播提醒'
        '开播啦'
        '```'
        ''
        '- 名字里的 `&` `<` `>` `"` 会被自动 XML 转义，可以放心用。'
        $sNameBar
        '- `icons/names.txt` **不存在或全为空**是正常状态：`M=0`，退化成"只有图标轴"（仍生成 `IconPreset_i_0`）。'
        $sNameEffect
        ''
        '## 四、图标轴：icons/presets/ 里的图片'
        ''
        ('| 项 | 要求 |')
        ('| --- | --- |')
        $sImgFormat
        ('| 形状 | **正方形**。不是正方形会按短边居中裁剪，并在摘要里逐条列出 |')
        ('| 尺寸 | 建议 >= 432x432（最高密度 xxxhdpi 需要 192x192）；短边 < 192 会**警告**但不阻止 |')
        $sImgNumber
        ('| 显示名 | 不能为空、不能含 ' + $bar + ' 或换行/制表符 |')
        ''
        '可选：放一个与图片同名的 `.json` 覆盖显示名，例如 `cat.png` + `cat.json`：'
        ''
        '```json'
        '{ "displayName": "猫耳" }'
        '```'
        ''
        '- 当前只支持 `displayName` 一个字段；其他字段会被忽略并打印警告（不报错）。'
        '- 排序键始终是**文件名**，`displayName` 只改显示，不影响编号顺序。'
        ''
        '## 五、批量导入图标（把别处目录里的一堆图一次导进来）'
        ''
        '```powershell'
        '# 把一个目录（含子目录）里的图片全部复制进 icons/presets/，然后生成'
        "powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标'"
        ''
        '# 多个来源目录：-Source 可重复传，也可以逗号/空格分隔'
        "powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -Source 'E:\另一批'"
        ''
        '# 只扫这一层（不进子目录）/ 移动而不是复制 / 确认覆盖同名不同内容的文件'
        "powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -NoRecurse"
        "powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -Move"
        "powershell -File tools\generate-icon-presets.ps1 -Source 'D:\下载的图标' -Force"
        '```'
        ''
        '同名冲突的确定性规则：目标名冲突时按 `名字` -> `名字-2` -> `名字-3` … 找第一个空闲名，'
        '每次改名都逐条打印（**图标显示名随之变化**）；内容完全相同（SHA-256）的图片视为已导入，'
        '跳过并列出 —— 所以同一批图重复跑多少次，结果都一样（幂等）。'
        ''
        '## 六、矩阵规模与「超过 60」警告'
        ''
        ('- 矩阵格数 = `(N+1) × (M+1)`，别名数 = 格数 `− 1`（扣掉 `(0,0)`）。')
        $sWarn60
        ('- 参考量级：`3 图标 × 2 名字 = 11`（舒服）；`8 × 8 = 80`（开始肉疼）；`20 × 20 = 440`（不建议）。')
        ''
        '## 七、生成物（都能随时删掉重新生成）'
        ''
        '| 产物 | 说明 |'
        '| --- | --- |'
        $sArtBitmap
        $sArtValues
        ('| `AndroidManifest.xml` 里 `BEGIN/END GENERATED ICON ALIASES` 之间的区块 | `(N+1)(M+1)-1` 个 `<activity-alias>` |')
        $sArtMirror
        ''
        $sRebuild
        ''
        '## 八、已知限制'
        ''
        $sLimit1
        $sLimit2
        ''
        '   ```xml'
        '   <resources xmlns:tools="http://schemas.android.com/tools"'
        '              tools:keep="@mipmap/ic_launcher_preset_*" />'
        '   ```'
        ''
        '   **本阶段刻意不创建这个文件**，只在此提醒。debug 构建不受影响。'
        ('3. 别名越多，`PackageManager` 与启动器的枚举开销越大：见第六节的 60 阈值。')
        $sLimit4
        ''
        '<!-- 下面是本次生成区块的实际内容，仅供人工核对；改它没用，下次生成会覆盖 -->'
        ''
        '```xml'
    )
    $lines += $blockText.Split([char]10)
    $lines += @('```', '')
    # README 也走同一条"写 -> 读回 -> 逐字节比对"的路径。
    # 注意历史缺陷的线索就在这里：README 当时是**唯一**写成功的产物 —— 因为它不经
    # $Pending 开关、直接调用写盘函数；manifest 与三个数组则被那个开关短路。根因就在这个差异上。
    return (Write-LfFile -path $md -lines $lines -what 'icons/presets/README.md（含本次生成区块的实际内容）')
}

# ------------------------------------------------------------------ 镜像
function Invoke-Mirror($Counters, [bool]$DryRun) {
    if (-not (Test-Path -LiteralPath $script:PublishRoot)) {
        Write-Warn2 'publish/ 不存在，跳过镜像'
        return
    }
    $pairs = New-Object System.Collections.ArrayList
    $resRoot = Join-Path $script:AppMain 'res'
    foreach ($f in (Get-ChildItem -LiteralPath $resRoot -Recurse -File)) {
        $rel = $f.FullName.Substring($script:AppMain.Length + 1)
        [void]$pairs.Add((New-Object psobject -Property @{ Src = $f.FullName; Dst = (Join-Path (Join-Path $script:PublishRoot 'app\src\main') $rel) }))
    }
    [void]$pairs.Add((New-Object psobject -Property @{ Src = $script:ManifestPath;    Dst = (Join-Path $script:PublishRoot 'app\src\main\AndroidManifest.xml') }))
    [void]$pairs.Add((New-Object psobject -Property @{ Src = $script:SelfPath;        Dst = (Join-Path $script:PublishRoot $script:ToolsMirrorRel) }))
    [void]$pairs.Add((New-Object psobject -Property @{ Src = (Join-Path $script:PresetsDir 'README.md'); Dst = (Join-Path $script:PublishRoot $script:ReadmeMirrorRel) }))
    [void]$pairs.Add((New-Object psobject -Property @{ Src = $script:NamesPath;       Dst = (Join-Path $script:PublishRoot $script:NamesMirrorRel) }))
    [void]$pairs.Add((New-Object psobject -Property @{ Src = $script:BuildScriptPath; Dst = (Join-Path $script:PublishRoot 'build-and-install.ps1') }))

    $n = 0
    foreach ($p in $pairs) {
        if (-not (Test-Path -LiteralPath $p.Src)) { continue }
        if (Test-Path -LiteralPath $p.Dst) {
            if ((Get-FileHash -LiteralPath $p.Src).Hash -eq (Get-FileHash -LiteralPath $p.Dst).Hash) { continue }
        }
        if (-not $DryRun) {
            $d = Split-Path -Parent $p.Dst
            if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
            Copy-Item -LiteralPath $p.Src -Destination $p.Dst -Force
        }
        $n = $n + 1
    }

    # 生成物是"镜像的权威内容"：图标数量变少 / 跑过 -Clear 时，publish 侧残留的
    # ic_launcher_preset_*.png 必须删掉，否则 publish 树与 app 树不再逐字节相同。
    # 只删"生成物模式"的文件，publish 里其它文件（README、源代码等）一律不碰。
    $stale = New-Object System.Collections.ArrayList
    foreach ($k in $script:Densities.Keys) {
        $dir = Join-Path (Join-Path $script:PublishRoot 'app\src\main\res') $k
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $hits = @(Get-ChildItem -LiteralPath $dir -File -Force | Where-Object { $_.Name -match $script:BitmapRegex })
        foreach ($h in $hits) {
            if (-not (Test-Path -LiteralPath (Join-Path (Join-Path $script:ResDir $k) $h.Name))) {
                [void]$stale.Add($k + '/' + $h.Name)
                if (-not $DryRun) { Remove-Item -LiteralPath $h.FullName -Force }
            }
        }
        if (-not $DryRun) {
            $left = @(Get-ChildItem -LiteralPath $dir -Force)
            if ($left.Count -eq 0) { Remove-Item -LiteralPath $dir -Force }
        }
    }
    $Counters['MirroredStale'] = $stale.Count
    if ($stale.Count -gt 0) {
        Write-Info ('清理 publish/ 侧 ' + $stale.Count + ' 个已不存在的生成物：' + ($stale -join ', '))
    }
    $Counters['Mirrored'] = $n

    # 镜像同样"写完读回"：publish/ 是交付树，源与目标必须逐字节相同。
    # 复制完再重新哈希比对一次（不是看 Copy-Item 有没有报错），不一致就报错退出。
    if (-not $DryRun) {
        $checked = 0
        $mismatch = New-Object System.Collections.ArrayList
        foreach ($p in $pairs) {
            if (-not (Test-Path -LiteralPath $p.Src)) { continue }
            $checked = $checked + 1
            if (-not (Test-Path -LiteralPath $p.Dst)) {
                [void]$mismatch.Add((Get-ShortName $p.Dst) + '（镜像后不存在）')
                continue
            }
            if ((Get-FileHash -LiteralPath $p.Src).Hash -ne (Get-FileHash -LiteralPath $p.Dst).Hash) {
                [void]$mismatch.Add((Get-ShortName $p.Dst))
            }
        }
        if ($mismatch.Count -gt 0) {
            Write-Err2 ('镜像校验失败：publish/ 下有 ' + $mismatch.Count + ' 个文件与源不一致：' + ($mismatch -join ', '))
            Die '镜像校验失败'
        }
        Write-Info ('镜像读回复核通过：' + $checked + ' 组 源/publish 副本 SHA-256 完全一致')
    }
}

# ------------------------------------------------------------------ BOM / 行尾自检
function Write-EncodingSelfCheck([bool]$DryRun) {
    Write-Head '编码自检（UTF-8 BOM / 行尾）'
    $targets = @(
        (New-Object psobject -Property @{ Path = $script:SelfPath; MustBom = $true;  What = '脚本本体（5.1 必须带 BOM，否则按 ANSI 解码 -> 大量语法错误）' }),
        (New-Object psobject -Property @{ Path = $script:ValuesXmlPath; MustBom = $false; What = '生成物（res/values/icon_presets.xml）' }),
        (New-Object psobject -Property @{ Path = $script:NamesPath; MustBom = $false; What = '名称轴配置（icons/names.txt）' }),
        (New-Object psobject -Property @{ Path = $script:ManifestPath; MustBom = $false; What = 'AndroidManifest.xml（保持文件原有风格）' }),
        (New-Object psobject -Property @{ Path = (Join-Path $script:PresetsDir 'README.md'); MustBom = $false; What = 'icons/presets/README.md' })
    )
    $violations = New-Object System.Collections.ArrayList
    foreach ($t in $targets) {
        if (-not (Test-Path -LiteralPath $t.Path)) { Write-Info ((Get-ShortName $t.Path) + '：不存在，跳过'); continue }
        $b = [System.IO.File]::ReadAllBytes($t.Path)
        $bom = ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF)
        $crlf = 0
        for ($i = 0; $i -lt $b.Length - 1; $i++) { if ($b[$i] -eq 13 -and $b[$i + 1] -eq 10) { $crlf = $crlf + 1 } }
        $eol = 'LF only'
        if ($crlf -gt 0) { $eol = ('LF=' + ([regex]::Matches($script:UTF8NoBom.GetString($b), "`n").Count) + '，CRLF=' + $crlf) }
        $mark = 'True'
        if (-not $bom) { $mark = 'False' }
        $line = (Get-ShortName $t.Path) + '  BOM=' + $mark + '  行尾：' + $eol + '  （' + $t.What + '）'
        if ($t.MustBom -and -not $bom) {
            Write-Err2 ($line + ' <== 必须是 BOM=True！')
            [void]$violations.Add($t.Path)
        }
        elseif ($t.MustBom) { Write-Ok $line }
        else { Write-Info $line }
    }

    # 最后再报一次"磁盘上真实的数量"：让"脚本自述"与"文件内容"在同一屏里可以直接对照。
    # 这是全脚本最后一段输出，也是"报告成功、实际没做事"的最后一道拦网。
    if (-not $DryRun -and (Test-Path -LiteralPath $script:ManifestPath -PathType Leaf) -and
        (Test-Path -LiteralPath $script:ValuesXmlPath -PathType Leaf)) {
        $mi = Read-TextFileInfo $script:ManifestPath
        $vi = Read-TextFileInfo $script:ValuesXmlPath
        $mv = ([regex]::Matches($mi.Text, '<activity-alias\b')).Count
        $items = ([regex]::Matches($vi.Text, '<item>')).Count
        Write-Info ('磁盘实测（重新读文件数出来的，不是内存里的计划值）：manifest 别名 ' + $mv +
            ' 个、icon_presets.xml 数组条目 ' + $items + ' 条')
    }

    if ($violations.Count -gt 0) {
        Die-Final ('最终编码自检未通过：' + $violations.Count + ' 个文件不满足 BOM 要求（见上）')
    }
}

# ================================================================== 主流程
try {
    Write-Head '图标 / 名称 预设生成器'
    $Counters = New-Counter
    $script:AndroidNs = 'http://schemas.android.com/apk/res/android'
    $script:IconAxis  = New-Object System.Collections.ArrayList
    $script:NameAxis  = New-Object System.Collections.ArrayList
    $script:IconLabels = New-Object System.Collections.ArrayList

    Write-Info ('项目根：' + $script:Root)
    if ($DryRun) { Write-Warn2 'DryRun 模式：只打印计划，不写任何文件' }

    if (-not (Test-Path -LiteralPath $script:ManifestPath -PathType Leaf)) { Die ('找不到 manifest：' + $script:ManifestPath) }

    # ---- 1. -Clear
    $cleared = New-Object System.Collections.ArrayList
    $clearedNames = New-Object System.Collections.ArrayList
    if ($Clear) {
        Write-Head '清空（-Clear）'
        $scanClear = Get-PresetFiles $script:PresetsDir
        foreach ($f in $scanClear.Images) {
            [void]$cleared.Add($f.Name)
            Inc $Counters 'ClearedPresets'
            if (-not $DryRun) { Remove-Item -LiteralPath $f.FullName -Force }
        }
        $clearTxt = '（本来就没有）'
        if ($cleared.Count -gt 0) { $clearTxt = ($cleared -join ', ') }
        Write-Info ('删除 icons/presets/ 下 ' + $cleared.Count + ' 个预设图片：' + $clearTxt)
        # -Clear 语义 = 回到「零预设」：两份输入源一起清（names.txt 只清内容，保留文件与格式说明）
        if (Test-Path -LiteralPath $script:NamesPath -PathType Leaf) {
            $ni = Read-TextFileInfo $script:NamesPath
            $kept = New-Object System.Collections.ArrayList
            foreach ($l in ($ni.Text.Replace("`r`n", "`n").Replace("`r", "`n") -split "`n")) {
                $t = $l.Trim()
                if ($t.Length -eq 0 -or $t.StartsWith('#')) { [void]$kept.Add($l.TrimEnd()) }
                else { [void]$clearedNames.Add($t) }
            }
            if ($clearedNames.Count -gt 0 -and -not $DryRun) {
                # [void]：Write-LfFile 现在返回"写入报告"对象（供调用方核对），这里只要副作用
                [void](Write-LfFile -path $script:NamesPath -lines ([string[]]$kept.ToArray()) `
                                    -what 'icons/names.txt（-Clear 后保留注释说明）')
            }
            $nt = '（本来就没有名字）'
            if ($clearedNames.Count -gt 0) { $nt = ($clearedNames -join ', ') }
            Write-Info ('删除 icons/names.txt 里 ' + $clearedNames.Count + ' 个名字（文件与 # 注释说明保留）：' + $nt)
        }
        $Counters['ClearedNames'] = $clearedNames.Count
    }

    # ---- 2. 批量导入
    $import = $null
    if ($Source) {
        Write-Head '批量导入'
        $dirs = New-Object System.Collections.ArrayList
        foreach ($s in $Source) {
            if ([string]::IsNullOrWhiteSpace($s)) { continue }
            Add-ScanDir -list $dirs -path $s
        }
        if ($dirs.Count -eq 0) { Die '-Source 没有提供任何可用目录' }
        $import = Invoke-BatchImport -Dirs $dirs -PresetsDir $script:PresetsDir -Counters $Counters `
                                     -MoveFiles ([bool]$Move) -Force ([bool]$Force) `
                                     -Recurse (-not $NoRecurse) -DryRun ([bool]$DryRun)
        if ($import.Found -eq 0) {
            Write-Warn2 ('扫描到 0 个图片（源目录：' + (($dirs | ForEach-Object { $_.Path }) -join '; ') + '）')
            Write-Warn2 ('本次没有导入任何东西 —— 请确认源目录里确实有 PNG / JPG / JPEG 文件（本脚本不做"扫描到 0 个"的静默成功）')
        }
    }

    # ---- 3. 图标轴：扫描 icons/presets/
    Write-Head '轴 1：图标（icons/presets/）'
    if (-not (Test-Path -LiteralPath $script:PresetsDir)) {
        New-Item -ItemType Directory -Force -Path $script:PresetsDir | Out-Null
        Write-Info ('已新建空目录 ' + $script:PresetsDir)
    }
    $scan = Get-PresetFiles $script:PresetsDir
    if ($scan.Unsupported.Count -gt 0) {
        Write-Err2 'icons/presets/ 里有不支持的图片格式（本脚本只处理 PNG / JPG / JPEG）：'
        foreach ($f in $scan.Unsupported) { Write-Host ('        ' + $f.Name) -ForegroundColor Red }
        Die '请把上述文件转成 PNG / JPG / JPEG 后重跑'
    }
    if ($scan.NonImage.Count -gt 0) {
        Write-Info ('忽略非图片文件 ' + $scan.NonImage.Count + ' 个（同名 .json / README.md 属正常）：' +
            ((@($scan.NonImage) | ForEach-Object { $_.Name }) -join ', '))
    }

    # 图标显示名冲突：同名不同扩展名 -> 报错（否则无法确定用哪张图）
    $byStem = @{}
    foreach ($f in $scan.Images) {
        $stem = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
        if ($byStem.ContainsKey($stem)) { $byStem[$stem] = @($byStem[$stem]) + @($f) }
        else { $byStem[$stem] = @($f) }
    }
    $stemConflicts = @($byStem.Keys | Where-Object { @($byStem[$_]).Count -gt 1 })
    if ($stemConflicts.Count -gt 0) {
        Write-Err2 '文件名重复（同名不同扩展名），无法确定用哪张图：'
        foreach ($s in $stemConflicts) {
            Write-Host ('        "' + $s + '"：' + ((@($byStem[$s]) | ForEach-Object { $_.Name }) -join ', ')) -ForegroundColor Red
        }
        Die '请删掉其中一个（或改名）后重跑'
    }

    # ---- 4. 逐个校验图片，生成图标轴索引表（i 从 1 开始）
    $iconEntries = New-Object System.Collections.ArrayList
    $idx = 0
    foreach ($f in $scan.Images) {
        $stem = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
        $json = Get-JsonOverride -dir $script:PresetsDir -stem $stem
        $label = $stem
        if ($json.HasOverride) { $label = $json.DisplayName }
        Assert-ValidIconLabel -name $label -fileName $f.Name

        if ($f.Length -eq 0) { Write-Err2 ('图片文件是空的（0 字节）：' + $f.Name); Die '图片损坏' }
        $info = Get-ImageInfo $f.FullName
        if ($null -eq $info) { Write-Err2 ('不是图片或已损坏，无法解码：' + $f.Name); Die '图片损坏' }
        # 真实编码格式必须与扩展名一致：aapt 是按内容解码的，扩展名说谎会让"PNG 资源"里装着 JPEG，
        # 且前面报给用户的格式也是错的 —— 所以这里大声报错而不是默默接受。
        $declared = $f.Extension.ToLowerInvariant()
        $actual = $info.Format
        if (($declared -eq '.png' -and $actual -ne 'PNG') -or
            (($declared -eq '.jpg' -or $declared -eq '.jpeg') -and $actual -ne 'JPEG')) {
            Write-Err2 ('扩展名与实际编码格式不符：' + $f.Name + ' 其实是 ' + $actual + ' 编码。')
            Write-Err2 ('        请改成正确的扩展名（' + $f.Name + ' -> ' +
                [System.IO.Path]::GetFileNameWithoutExtension($f.Name) + '.' + $actual.ToLowerInvariant() + '）后重跑')
            Die '图片格式与扩展名不符'
        }
        $short = [Math]::Min($info.Width, $info.Height)
        if ($short -lt $script:LowResPx) {
            Write-Warn2 ('分辨率过低：' + $f.Name + ' 只有 ' + $info.Width + 'x' + $info.Height +
                '（短边 ' + $short + ' < ' + $script:LowResPx + '），放大到 ' + $script:MaxIconPx + 'px 会发虚 —— 仍按你的要求生成')
            Inc $Counters 'LowRes'
        }
        if ($info.Width -ne $info.Height) {
            Write-Warn2 ('不是正方形：' + $f.Name + ' = ' + $info.Width + 'x' + $info.Height +
                '，按短边 ' + $short + ' 居中裁剪（裁剪而不是报错，理由见 README）')
            Inc $Counters 'Cropped'
        }
        $idx = $idx + 1
        [void]$iconEntries.Add((New-Object psobject -Property @{
            Index       = $idx
            SourceFile  = $f.Name
            DisplayName = $label
            IconRes     = 'ic_launcher_preset_' + $idx
            Width       = $info.Width
            Height      = $info.Height
            Bytes       = $info.Bytes
        }))
    }
    $iconCount = $iconEntries.Count

    # 图标轴数组 + 显示名表（第 0 项是哨兵）
    [void]$script:IconAxis.Add($script:DefaultIconLabel)        # 资源名留空：'' + '|' + 哨兵文案
    [void]$script:IconLabels.Add($script:DefaultIconLabel)
    foreach ($e in $iconEntries) {
        [void]$script:IconAxis.Add($e.IconRes + $script:Bar + $e.DisplayName)
        [void]$script:IconLabels.Add($e.DisplayName)
    }
    if ($iconCount -eq 0) {
        Write-Info ('发现 0 个预设图标 -> N=0（图标轴只有 i=0 哨兵 = 应用自己的图标），这是正常状态')
    }
    else {
        Write-Info ('发现 ' + $iconCount + ' 个预设图标 -> N=' + $iconCount + '，共 ' + ($iconCount + 1) + ' 个图标档（含 i=0 哨兵）')
    }

    # ---- 5. 名称轴：读取 icons/names.txt
    Write-Head '轴 2：名称（icons/names.txt）'
    $names = Read-NameAxis $script:NamesPath
    if (-not $names.Exists) {
        Write-Info $script:NoNameFileHint
    }
    elseif ($names.Names.Count -eq 0) {
        Write-Info ('icons/names.txt 存在但没有有效名字行（去注释/空行后 0 条）-> M=0，' +
            '退化为只有图标轴（这是正常状态）')
    }
    else {
        Write-Info ('icons/names.txt：有效行 ' + $names.Raw + ' 条 -> 去重后 ' + $names.Names.Count + ' 个名字（M=' + $names.Names.Count + '）')
    }
    if ($names.Dups.Count -gt 0) {
        $Counters['NameDuplicates'] = $names.Dups.Count
        Write-Warn2 ('重复名已去重（只保留第一次出现）' + $names.Dups.Count + ' 条：' + ($names.Dups -join ', '))
    }
    $nameCount = $names.Names.Count

    # 名称轴数组（第 0 项是空字符串哨兵 = 应用自己的名字）
    [void]$script:NameAxis.Add($script:DefaultNameValue)
    foreach ($nm in $names.Names) { [void]$script:NameAxis.Add($nm) }

    # ---- 6. 矩阵：为每个格子生成别名条目（(0,0) 除外）
    Write-Head '别名矩阵'
    $gridTotal = ($iconCount + 1) * ($nameCount + 1)
    $aliasTotal = $gridTotal - 1
    Write-Info ('矩阵格数 (N+1)×(M+1) = ' + ($iconCount + 1) + '×' + ($nameCount + 1) + ' = ' + $gridTotal +
        '，扣掉 (0,0) 主入口 -> 别名数 = ' + $aliasTotal)
    if ($aliasTotal -gt $script:WarnMatrixCells) {
        Write-Warn2 ('别名数 ' + $aliasTotal + ' 超过建议上限 ' + $script:WarnMatrixCells + '：' +
            '每个别名都是真实组件，PackageManager 与所有启动器都要枚举它们，安装/枚举会明显变慢，')
        Write-Warn2 ('        部分启动器还会把图标塞进角落。**不阻止你**，但请确认确实需要这么多组合')
        Write-Warn2 ('        （可以少放几张图 / 少写几个名字，或者删掉一些组合的来源）')
    }

    $aliasEntries = New-Object System.Collections.ArrayList
    $matrix = New-Object System.Collections.ArrayList
    for ($i = 0; $i -le $iconCount; $i++) {
        for ($j = 0; $j -le $nameCount; $j++) {
            if ($i -eq 0 -and $j -eq 0) { continue }     # (0,0) = MainActivity 本身
            $alias = 'IconPreset_' + $i + '_' + $j
            $iconSpec = '@mipmap/ic_launcher'
            if ($i -ge 1) { $iconSpec = '@mipmap/ic_launcher_preset_' + $i }
            $labelSpec = '@string/app_name'
            if ($j -ge 1) { $labelSpec = [string]$script:NameAxis[$j] }
            [void]$aliasEntries.Add((New-Object psobject -Property @{
                I = $i; J = $j; Alias = $alias; Icon = $iconSpec; Label = $labelSpec
            }))
            [void]$matrix.Add((New-Object psobject -Property @{
                I = $i; J = $j; Alias = $alias; Entry = ($i.ToString() + $script:Bar + $j.ToString() + $script:Bar + $alias)
            }))
        }
    }
    $Counters['MatrixEntries'] = $matrix.Count
    if ($aliasEntries.Count -ne $aliasTotal) { Die ('内部错误：别名条目数 ' + $aliasEntries.Count + ' != ' + $aliasTotal) }

    # ---- 7. 先做两个"文本生成物"的全部生成与校验（此时一个字节都没写）
    Write-Head '生成预设清单（三个数组）'
    # 注意：不要写 [string[]]$x.ToArray()。PowerShell 先做成员访问、再做类型转换，
    # 而 [string[]]$x 会把 $x 变成字符串，于是 .ToArray() 变成"在 String 上找 ToArray" -> 运行时报错。
    # ArrayList 交给 [string[]] 参数自己转换即可；数组用 @(...) 包一层。
    $arrayLines = New-ValuesXmlLines -IconAxis $script:IconAxis `
                                     -NameAxis $script:NameAxis `
                                     -MatrixEntries @($matrix | ForEach-Object { $_.Entry })
    $arrayText = ($arrayLines -join $script:NL) + $script:NL
    [void](Assert-ValuesXml -xmlText $arrayText -IconCount $iconCount -NameCount $nameCount -MatrixCount $matrix.Count)
    Write-Info ($script:ValuesXmlPath.Replace($script:Root + '\', '') + '：icon_axis ' + $script:IconAxis.Count +
        ' 条、name_axis ' + $script:NameAxis.Count + ' 条、preset_alias_matrix ' + $matrix.Count + ' 条（XML 校验通过）')

    Write-Head '更新 AndroidManifest.xml 生成区块（先校验，后统一落盘）'
    $block = New-GeneratedBlock -Entries $aliasEntries
    Assert-GeneratedBlock -blockText $block -Entries $aliasEntries
    $man = Update-ManifestGenerationBlock -blockText $block -Entries $aliasEntries -Counters $Counters `
                                          -DryRun ([bool]$DryRun)
    $mi = $man.Text.IndexOf($script:MarkerBegin, [System.StringComparison]::Ordinal)
    $beforeMarker = $man.Text.Substring(0, $mi)
    $lineNo = ($beforeMarker -split $script:NL).Count
    $modeTxt = '替换两个标记之间的内容（marker）'
    if ($man.Mode -eq 'insert') { $modeTxt = '首次插入，位于 </application> 之前（insert）' }
    Write-Info ('区块模式：' + $modeTxt)
    Write-Info ('BEGIN 标记位置：' + $script:ManifestPath.Replace($script:Root + '\', '') + ' 第 ' + $lineNo + ' 行')
    Write-Info ('别名数量：' + $man.OldCount + ' -> ' + $aliasEntries.Count +
        '（区块外残留别名清理 ' + $Counters['StaleAliases'] + ' 个）')
    Write-Ok ('manifest 重新解析校验通过（application=1，activity-alias=' + $aliasEntries.Count + '，全部 exported=true 且带 LAUNCHER intent-filter）')

    # ---- 8. 位图（整块重建：先删旧文件，再按当前图标列表生成）
    # 放在这里：文档校验已经全部通过，写位图不会白费；而 manifest 还没写，
    # 所以即使后面 manifest 写失败，磁盘上也不会出现"新清单 + 旧 manifest"这种装上去缺资源的组合。
    $oldBitmaps = Remove-OldBitmaps -Counters $Counters -DryRun ([bool]$DryRun)
    Write-Head '生成位图资源'
    $bitmapCount = 0
    foreach ($e in $iconEntries) {
        $srcPath = Join-Path $script:PresetsDir $e.SourceFile
        $bmp = $null
        $ms = $null
        try {
            $bytes = [System.IO.File]::ReadAllBytes($srcPath)
            $ms = New-Object System.IO.MemoryStream(,$bytes)
            $bmp = [System.Drawing.Bitmap]::FromStream($ms, $true, $true)
            foreach ($k in $script:Densities.Keys) {
                $px = $script:Densities[$k]
                $dest = Join-Path (Join-Path $script:ResDir $k) ($e.IconRes + '.png')
                Save-ScaledPng -src $bmp -size $px -destPath $dest -dryRun ([bool]$DryRun)
                $bitmapCount = $bitmapCount + 1
            }
            Write-Info ('i=' + $e.Index + '  ' + $e.IconRes + '  <-  ' + $e.SourceFile +
                '  (' + $e.Width + 'x' + $e.Height + ', ' + (Format-ByteSize $e.Bytes.Length) + ')  显示名="' + $e.DisplayName + '"')
        }
        catch {
            Write-Err2 ('生成位图失败：' + $e.SourceFile + ' -> ' + $_.Exception.Message)
            Die '位图生成失败'
        }
        finally {
            if ($null -ne $bmp) { $bmp.Dispose() }
            if ($null -ne $ms) { $ms.Dispose() }
        }
    }
    if ($iconCount -eq 0) { Write-Info '（没有预设图标 -> 不生成任何预设位图；i=0 用的是应用自己的 mipmap-*/ic_launcher）' }
    [void](Remove-StaleBitmaps -iconCount $iconCount -Counters $Counters -DryRun ([bool]$DryRun))

    # ---- 9. 统一落盘：manifest -> 三个数组 -> README
    # 三个产物全部无条件走 Write-FileVerified（写 -> 读回 -> 逐字节比对），**没有任何条件开关**。
    # 历史缺陷：这里（以及 Update-ManifestGenerationBlock 内部）曾被 $Pending.WriteManifest /
    # $Pending.WriteValues 两个**永远为 $false** 的标志短路 —— manifest 与三个数组一个字节都没写，
    # 脚本却照样打印 [OK] 并退出 0。现在"要写哪些文件"由代码顺序固定，不由标志位决定。
    # manifest 先写：万一它写失败，脚本会立刻中止，不会出现"数组已更新、manifest 还是旧的"。
    Write-Head '写入生成物'
    $diskMan = $null
    $diskVal = $null
    $diskReadme = $null
    if ($DryRun) {
        Write-Warn2 'DryRun：跳过全部写盘（manifest / 三个数组 / README），也不会做落盘复核'
    }
    else {
        $minfo = Read-TextFileInfo $script:ManifestPath
        $manWrite = Write-FileVerified -path $script:ManifestPath -text $man.Text -hasBom $minfo.HasBom `
                                       -what 'AndroidManifest.xml（别名区块）'
        $valWrite = Write-FileVerified -path $script:ValuesXmlPath -text $arrayText -hasBom $false `
                                       -what 'res/values/icon_presets.xml（三个数组）'
        $readmeWrite = Write-PresetsReadme -blockText $block -IconCount $iconCount -NameCount $nameCount -AliasTotal $aliasTotal

        # ---- 10. 落盘复核：只信磁盘。重新读文件、重新解析、独立数一遍（不看内存里的字符串）。
        Write-Head '落盘复核（重新读回磁盘，独立数一遍）'
        $diskMan = Assert-ManifestFileOnDisk -Entries $aliasEntries -BeforeCount $man.OldCount
        $diskVal = Assert-ValuesFileOnDisk -IconCount $iconCount -NameCount $nameCount -MatrixCount $matrix.Count
        $diskReadme = Assert-ReadmeFileOnDisk -BlockText $block
        Write-Ok ('磁盘三处产物与本次计划完全一致：manifest 别名 ' + ([string]$man.OldCount) + ' -> ' +
            ([string]$diskMan.AliasCount) + ' 个、数组 ' + ([string]$diskVal.TotalCount) + ' 条（icon_axis ' +
            ([string]$diskVal.IconAxis) + ' / name_axis ' + ([string]$diskVal.NameAxis) + ' / matrix ' +
            ([string]$diskVal.Matrix) + '）、README 区块 ' + ([string]$diskReadme.BlockAliases) + ' 个别名')
    }

    # ---- 11. 镜像
    if (-not $NoMirror) {
        Write-Head '镜像到 publish/'
        Invoke-Mirror -Counters $Counters -DryRun ([bool]$DryRun)
        Write-Info ('同步 ' + $Counters['Mirrored'] + ' 个文件到 publish/' +
            '（app\src\main 全树 + tools\generate-icon-presets.ps1 + icons\presets\README.md + icons\names.txt + build-and-install.ps1）')
    }

    # ---- 12. 摘要
    Write-Head '摘要'
    if ($import) {
        $scanDirsTxt = (($import.Dirs | ForEach-Object { Get-ShortName $_.Path }) -join '、')
        Write-Host ('  批量导入：源 ' + $import.Dirs.Count + ' 个目录（' + $scanDirsTxt + '），扫描 ' + $import.Found +
            ' 个文件 -> 导入 ' + $import.Imported + '、重命名 ' + $import.Renames.Count +
            '、跳过 ' + $Counters['Skipped'] + '、覆盖 ' + $Counters['Overwritten']) -ForegroundColor White
        Write-Host ('    跳过 ' + $Counters['Skipped'] + ' 个的原因分布：非图片/不支持格式 ' + $Counters['NonImage'] +
            '、内容已在库里（含改名落点）' + $Counters['AlreadyPresent']) -ForegroundColor Gray
        foreach ($s in $import.Skips) { Write-Host ('      - 跳过 ' + $s.File + '：' + $s.Reason) -ForegroundColor Gray }
        foreach ($a in $import.Renames) { Write-Host ('      - 改名 ' + $a.Src.Name + ' -> ' + $a.Name) -ForegroundColor Gray }
        foreach ($a in $import.Overwrites) { Write-Host ('      - 覆盖 icons/presets/' + $a) -ForegroundColor Gray }
        if ($Move) { Write-Host ('    以移动方式导入 ' + $Counters['Moved'] + ' 个（源文件已从原目录移除）') -ForegroundColor Gray }
        if ($Counters['AlreadyPresent'] -gt 0) {
            Write-Host ('    已导入过（内容相同，未重复添加）' + $Counters['AlreadyPresent'] + ' 个；幂等：重复跑同一源目录结果不变') -ForegroundColor Gray
        }
    }
    if ($Clear) {
        $clearTxt = '（本来就没有）'
        if ($cleared.Count -gt 0) { $clearTxt = ($cleared -join ', ') }
        Write-Host ('  -Clear：删除 icons/presets/ 下 ' + $cleared.Count + ' 个预设图片 ' + $clearTxt) -ForegroundColor White
        Write-Host ('  -Clear：删除 icons/names.txt 里 ' + $clearedNames.Count + ' 个名字') -ForegroundColor White
    }

    Write-Host ''
    if ($aliasTotal -eq 0) {
        Write-Host '发现 0 个图标 × 0 个名字 -> 已清空别名与三个数组（这是正常状态）' -ForegroundColor White
    }
    else {
        # 注意：在 Windows PowerShell 5.1 里，若 "+" 的左侧是数字，右侧字符串会被尝试当成数字解析
        # （$n + ' 个图标…' 会抛 "Cannot convert value ... to type System.Int32"），
        # 所以下面凡是数字开头的拼接都先 [string] 转一下。
        $summary = '发现 ' + $iconCount + ' 个图标 × ' + $nameCount + ' 个名字 -> 生成 ' + $aliasTotal + ' 个别名（(' +
            $iconCount + '+1)×(' + $nameCount + '+1)−1）、' + $bitmapCount + ' 个位图资源、' +
            $script:IconAxis.Count + '+' + $script:NameAxis.Count + ' 条轴条目、' + $matrix.Count + ' 条矩阵条目（旧的 ' +
            $Counters['OldAliases'] + ' 个已清理）'
        Write-Host $summary -ForegroundColor White
    }
    Write-Host ('矩阵规模：(N+1) × (M+1) − 1 = (' + $iconCount + '+1) × (' + $nameCount + '+1) − 1 = ' +
        $gridTotal + ' − 1 = ' + $aliasTotal + ' 个别名') -ForegroundColor White
    if ($aliasTotal -gt $script:WarnMatrixCells) {
        Write-Warn2 ('[警告] 别名数 ' + $aliasTotal + ' > ' + $script:WarnMatrixCells + '：建议上限已超，请确认是否真的需要这么多组合（脚本不会阻止你）')
    }
    Write-Host ''
    Write-Matrix -IconCount $iconCount -NameCount $nameCount -Columns $gridTotal -Matrix $matrix

    if ($Counters['LowRes'] -gt 0) {
        Write-Warn2 ([string]$Counters['LowRes'] + ' 个图标分辨率低于 ' + [string]$script:LowResPx + 'px，已放大生成（可能发虚）')
    }
    if ($Counters['Cropped'] -gt 0) {
        Write-Warn2 ([string]$Counters['Cropped'] + ' 个图标不是正方形，已按短边居中裁剪')
    }
    if ($DryRun) { Write-Warn2 'DryRun：以上均为计划，未写入任何文件' }

    Write-EncodingSelfCheck -DryRun ([bool]$DryRun)

    Write-Host ''
    # 收尾这句只描述"计划"；真实落盘情况由上面"落盘复核"那一段的磁盘实测数字背书。
    $diskTxt = '（DryRun：未写盘）'
    if (-not $DryRun) {
        $diskTxt = '；磁盘读回复核：manifest 别名 ' + ([string]$diskMan.AliasCount) + ' 个（' + ([string]$manWrite.Bytes) +
            ' 字节）、数组 ' + ([string]$diskVal.TotalCount) + ' 条（' + ([string]$valWrite.Bytes) + ' 字节）、README ' +
            ([string]$readmeWrite.Bytes) + ' 字节'
    }
    Write-Ok ('完成。退出码 0。别名 ' + $aliasTotal + ' 个、位图 ' + $bitmapCount + ' 个、三个数组共 ' +
        ($script:IconAxis.Count + $script:NameAxis.Count + $matrix.Count) + ' 条' + $diskTxt)
    Write-Host ''
    exit 0
}
catch {
    Write-Host ''
    Write-Err2 ('未处理的错误：' + $_.Exception.Message)
    Write-Host ($_.ScriptStackTrace) -ForegroundColor DarkGray
    exit 1
}
