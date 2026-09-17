# ==============================================================================
#  生成「默认应用图标」：把第 1 张图做成 mipmap-*/ic_launcher.png
#
#  为什么是普通位图（而不是改成自适应图标）：
#    · 用户给的 9 张图都是 192×192 —— 这正好是 Android 传统启动图标在
#      xxxhdpi 下的标准尺寸（48dp × 4 = 192px），位图路径**一个像素都不用放大**；
#    · 预设图标（icons/presets/ → ic_launcher_preset_<i>.png）本来就是普通位图，
#      默认图标用同一套做法，桌面上 9 个图标的外观口径才一致（都不裁切、不描边）；
#    · 自适应图标要 108dp（xxxhdpi = 432px）的前景层，192px 源图会被放大 2.25 倍，
#      而且会被启动器按形状裁掉约 1/3 画面。
#  代价（如实写明）：Android 8+ 的启动器会给"非自适应"图标加自己的底板/缩放，
#  不同桌面（MIUI / Pixel）表现不一样。若要"整块铺满、按形状裁切"的现代观感，
#  就得改成自适应图标 —— 那是另一条路，需要同时改预设的生成方式。
# ==============================================================================
[CmdletBinding()]
param(
    [string]$Source = '.tmp\newicons\ic_launcher_1_192.png',
    [switch]$NoMirror
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath $Source)) { throw "找不到源图：$Source" }

# 与 tools\generate-icon-presets.ps1 的密度表逐字一致（48/72/96/144/192）
$densities = [ordered]@{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

function Resize-Png([string]$src, [string]$dst, [int]$size) {
    $img = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $src).Path)
    try {
        $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        try {
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            try {
                $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $g.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $g.Clear([System.Drawing.Color]::Transparent)
                $g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $size, $size)))
            }
            finally { $g.Dispose() }
            $dir = Split-Path -Parent $dst
            if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
            $bmp.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally { $bmp.Dispose() }
    }
    finally { $img.Dispose() }
}

$targets = @($root)
if (-not $NoMirror) {
    $pub = Join-Path $root 'publish'
    if (Test-Path -LiteralPath $pub) { $targets += $pub }
}

Write-Host ''
Write-Host '=== 生成默认应用图标（第 1 张图） ===' -ForegroundColor Cyan
foreach ($tree in $targets) {
    foreach ($k in $densities.Keys) {
        $dst = Join-Path $tree ('app\src\main\res\' + $k + '\ic_launcher.png')
        Resize-Png -src $Source -dst $dst -size $densities[$k]
        $len = (Get-Item -LiteralPath $dst).Length
        Write-Host ('  [OK] ' + $k + '\ic_launcher.png  ' + $densities[$k] + 'x' + $densities[$k] + '  ' + $len + ' 字节')
    }
    # 自适应图标的 XML 必须删掉：API 26+ 优先用它，留着就会盖掉位图
    $adaptive = Join-Path $tree 'app\src\main\res\mipmap-anydpi-v26\ic_launcher.xml'
    if (Test-Path -LiteralPath $adaptive) {
        Remove-Item -LiteralPath $adaptive -Force
        Write-Host ('  [OK] 已删除 ' + $k + '\..\mipmap-anydpi-v26\ic_launcher.xml（否则它会盖掉位图）')
    }
    $anydpiDir = Join-Path $tree 'app\src\main\res\mipmap-anydpi-v26'
    if ((Test-Path -LiteralPath $anydpiDir) -and @(Get-ChildItem -LiteralPath $anydpiDir -Force).Count -eq 0) {
        Remove-Item -LiteralPath $anydpiDir -Force
        Write-Host '  [OK] 空目录 mipmap-anydpi-v26 已删除'
    }
}

# 两棵树逐字节核对
if (@($targets).Count -gt 1) {
    Write-Host ''
    Write-Host '=== 两棵树逐字节核对 ===' -ForegroundColor Cyan
    $bad = 0
    foreach ($k in $densities.Keys) {
        $a = Join-Path $root ('app\src\main\res\' + $k + '\ic_launcher.png')
        $b = Join-Path $pub  ('app\src\main\res\' + $k + '\ic_launcher.png')
        $ha = (Get-FileHash -LiteralPath $a).Hash; $hb = (Get-FileHash -LiteralPath $b).Hash
        if ($ha -ne $hb) { $bad++; Write-Host ('  [X] ' + $k + ' 不一致') }
    }
    if ($bad -eq 0) { Write-Host '  [OK] 5 个密度的 ic_launcher.png 两棵树 SHA-256 完全一致' }
}
Write-Host ''
Write-Host '  [OK] 完成。' -ForegroundColor Green
