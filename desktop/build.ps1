<#
.SYNOPSIS
  构建「Bookkeeping System」桌面版：编译 Java → 复制资源 → 打包 JRE → 出 NSIS 安装包（可选发布到 GitHub Releases）。

.EXAMPLE
  .\build.ps1                          # 完整构建，版本号不变
  .\build.ps1 -Version 1.0.1           # 改版本号后构建
  .\build.ps1 -Version 1.0.1 -Publish  # 构建并发布到 GitHub Releases（需 GH_TOKEN）
  .\build.ps1 -SkipJre -SkipNpm        # 快速构建（依赖与 JRE 已就绪时）
#>
param(
    [string]$Version,
    [switch]$Publish,
    [switch]$SkipJre,
    [switch]$SkipNpm
)

$ErrorActionPreference = 'Stop'

$desktop = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $desktop
$runtime = Join-Path $desktop 'runtime'
$classes = Join-Path $runtime 'classes'
$libDst = Join-Path $runtime 'lib'
$staticDst = Join-Path $runtime 'static'
$jre = Join-Path $runtime 'jre'

# ---------- 版本号 ----------
if ($Version) {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "版本号格式应为 1.2.3，收到：$Version" }
    $pkgPath = Join-Path $desktop 'package.json'
    $raw = [IO.File]::ReadAllText($pkgPath, [Text.Encoding]::UTF8)
    $new = [regex]::Replace($raw, '("version"\s*:\s*")[^"]+(")', '${1}' + $Version + '${2}')
    [IO.File]::WriteAllText($pkgPath, $new, (New-Object Text.UTF8Encoding($false)))
    Write-Host "== 版本号 -> $Version ==" -ForegroundColor Cyan
}

# ---------- 0/6 图标 ----------
Write-Host '== 0/6 生成应用图标 ==' -ForegroundColor Cyan
$iconClasses = Join-Path $root '.build_tmp\icon'
$iconSource = Join-Path $root 'tools\icon\IconGen.java'
$iconOut = Join-Path $desktop 'build\icon.ico'
New-Item -ItemType Directory -Force -Path $iconClasses | Out-Null
& javac -encoding UTF-8 -d $iconClasses $iconSource
if ($LASTEXITCODE -ne 0) { throw '图标生成器编译失败' }
& java -cp $iconClasses IconGen $iconOut
if ($LASTEXITCODE -ne 0) { throw '图标生成失败' }

Write-Host '== 1/6 编译 Java ==' -ForegroundColor Cyan
Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libDst, $staticDst | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 17 -cp "$root\lib\*" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败' }

Write-Host '== 2/6 复制依赖与静态资源 ==' -ForegroundColor Cyan
Copy-Item (Join-Path $root 'lib\*.jar') $libDst -Force
Copy-Item (Join-Path $root 'static\*') $staticDst -Force -Recurse

Write-Host '== 3/6 打包精简 JRE ==' -ForegroundColor Cyan
if ($SkipJre) {
    Write-Host '   已跳过（目标机需自备 JDK 17+）'
} elseif (Test-Path (Join-Path $jre 'bin\java.exe')) {
    Write-Host '   已存在，跳过'
} else {
    & jlink --add-modules java.base,java.desktop,java.sql,java.naming,java.management,java.xml,java.logging,java.transaction.xa,java.net.http,jdk.httpserver,jdk.unsupported,jdk.charsets,jdk.crypto.ec `
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $jre
    if ($LASTEXITCODE -ne 0) { throw 'jlink 打包 JRE 失败' }
}

Write-Host '== 4/6 安装 npm 依赖 ==' -ForegroundColor Cyan
Push-Location $desktop
try {
    if ($SkipNpm -or (Test-Path (Join-Path $desktop 'node_modules'))) {
        Write-Host '   已存在，跳过'
    } else {
        npm install
        if ($LASTEXITCODE -ne 0) { throw 'npm install 失败' }
    }

    Write-Host '== 5/6 打包安装程序 ==' -ForegroundColor Cyan
    $env:ELECTRON_MIRROR = 'https://npmmirror.com/mirrors/electron/'
    $env:ELECTRON_BUILDER_BINARIES_MIRROR = 'https://npmmirror.com/mirrors/electron-builder-binaries/'

    if ($Publish) {
        if (-not $env:GH_TOKEN) {
            throw "发布到 GitHub Releases 需要 GH_TOKEN 环境变量（Personal Access Token，勾选 repo 权限）。`n先执行：`$env:GH_TOKEN='ghp_xxx'"
        }
        npx electron-builder --win nsis --x64 --publish always
    } else {
        npx electron-builder --win nsis --x64 --publish never
    }
    if ($LASTEXITCODE -ne 0) { throw 'electron-builder 打包失败' }
} finally {
    Pop-Location
}

$dist = Join-Path $desktop 'dist'
Get-ChildItem $dist -File | Sort-Object Length -Descending |
    Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } |
    Format-Table -AutoSize

if ($Publish) {
    Write-Host '已上传到 GitHub Releases：https://github.com/YNM10086/JAVA-Accounting-system/releases' -ForegroundColor Green
    Write-Host '（首次发布的 release 记得确认不是 Draft 状态，否则应用检查更新看不到它）'
}
