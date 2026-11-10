<#
.SYNOPSIS
  构建「人间烟火」桌面版：编译 Java → 复制资源 → 打包 JRE → 出 NSIS 安装包。

.EXAMPLE
  .\build.ps1                 # 完整构建
  .\build.ps1 -SkipJre        # 不打包 JRE（要求目标机已装 JDK 17+）
  .\build.ps1 -SkipNpm        # 依赖已装，跳过 npm install
#>
param(
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

Write-Host '== 1/5 编译 Java ==' -ForegroundColor Cyan
Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $libDst, $staticDst | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 17 -cp "$root\lib\*" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败' }

Write-Host '== 2/5 复制依赖与静态资源 ==' -ForegroundColor Cyan
Copy-Item (Join-Path $root 'lib\*.jar') $libDst -Force
Copy-Item (Join-Path $root 'static\*') $staticDst -Force -Recurse

Write-Host '== 3/5 打包精简 JRE ==' -ForegroundColor Cyan
if ($SkipJre) {
    Write-Host '   已跳过（目标机需自备 JDK 17+）'
} elseif (Test-Path (Join-Path $jre 'bin\java.exe')) {
    Write-Host '   已存在，跳过'
} else {
    & jlink --add-modules java.base,java.desktop,java.sql,java.naming,java.management,java.xml,java.logging,java.transaction.xa,jdk.httpserver,jdk.unsupported,jdk.charsets,jdk.crypto.ec `
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $jre
    if ($LASTEXITCODE -ne 0) { throw 'jlink 打包 JRE 失败' }
}

Write-Host '== 4/5 安装 npm 依赖 ==' -ForegroundColor Cyan
Push-Location $desktop
try {
    if ($SkipNpm -or (Test-Path (Join-Path $desktop 'node_modules'))) {
        Write-Host '   已存在，跳过'
    } else {
        npm install
        if ($LASTEXITCODE -ne 0) { throw 'npm install 失败' }
    }

    Write-Host '== 5/5 打包安装程序 ==' -ForegroundColor Cyan
    $env:ELECTRON_MIRROR = 'https://npmmirror.com/mirrors/electron/'
    $env:ELECTRON_BUILDER_BINARIES_MIRROR = 'https://npmmirror.com/mirrors/electron-builder-binaries/'
    npm run dist
    if ($LASTEXITCODE -ne 0) { throw 'electron-builder 打包失败' }
} finally {
    Pop-Location
}

$dist = Join-Path $desktop 'dist'
Get-ChildItem $dist -File | Sort-Object Length -Descending |
    Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } |
    Format-Table -AutoSize
