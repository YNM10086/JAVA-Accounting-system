<#
.SYNOPSIS
    发补丁时把版本号往上推一格：v3.0.x / 补丁 NN / 日期 / desktop 版本号。

.DESCRIPTION
    一次改四处，都只改版本号，不碰别的代码：
      src\function\Sections.java    设置页版本行  v3.0.1 · 补丁 01（2026-11-16）
      src\function\WebServer.java   左栏 BUILD 徽标
      static\splash.html            开屏两处（版本行 + 右上角）
      desktop\package.json          1.0.1（打安装包时用）

    源码没动就不推：.last-bump 存的是「上次 bump 之后」的指纹，
    所以同一天里重复双击更新不会变成 3.0.2、3.0.3 一路往上涨。

.PARAMETER Root
    项目根目录，默认是本脚本的上一级。

.PARAMETER DryRun
    只打印会怎么改，一个文件都不写。

.PARAMETER Force
    忽略指纹，强制 +1。

.PARAMETER Stamp
    只把当前源码记为「已发布」，不动版本号（首次启用时用一次）。

.EXAMPLE
    .\bump-version.ps1 -DryRun
.EXAMPLE
    .\update.ps1 -Bump
#>
[CmdletBinding()]
param(
    [string]$Root,
    [switch]$DryRun,
    [switch]$Force,
    [switch]$Stamp
)

$ErrorActionPreference = 'Stop'

if (-not $Root) {
    $here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    $Root = Split-Path -Parent $here
}
$Root = (Resolve-Path $Root).Path.TrimEnd('\')

$secPath = Join-Path $Root 'src\function\Sections.java'
$webPath = Join-Path $Root 'src\function\WebServer.java'
$splashPath = Join-Path $Root 'static\splash.html'
$pkgPath = Join-Path $Root 'desktop\package.json'
$statePath = Join-Path $Root 'UPdateVersion\.last-bump'

foreach ($p in @($secPath, $webPath, $splashPath, $pkgPath)) {
    if (-not (Test-Path $p)) { Write-Output "[失败] 找不到 $p"; exit 2 }
}

function Read-Text([string]$Path) {
    return [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
}

# 不依赖 Get-FileHash（这台机器的 PS 5.1 里居然没有），直接用 .NET 算
function Get-Sha([string]$Path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
        return (([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLower())
    } finally { $sha.Dispose() }
}

# src\*.java + static\* 的内容指纹：用来判断「这次到底有没有新东西要发」
function Get-Fingerprint([string]$Root) {
    $files = @()
    $files += @(Get-ChildItem (Join-Path $Root 'src') -Recurse -File -Filter *.java)
    $files += @(Get-ChildItem (Join-Path $Root 'static') -Recurse -File)
    $sb = New-Object System.Text.StringBuilder
    foreach ($f in ($files | Sort-Object FullName)) {
        $rel = $f.FullName.Substring($Root.Length + 1).Replace('\', '/')
        [void]$sb.Append($rel).Append(':').Append((Get-Sha $f.FullName)).Append("`n")
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
        return (([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLower())
    } finally { $sha.Dispose() }
}

$fingerprint = Get-Fingerprint $Root

$state = @{}
if (Test-Path $statePath) {
    foreach ($line in (Get-Content $statePath -Encoding UTF8)) {
        if ($line -match '^\s*(hash|version|date)\s*=\s*(.+)$') { $state[$Matches[1]] = $Matches[2].Trim() }
    }
}

$sec = Read-Text $secPath
$m = [regex]::Match($sec, 'v(?<maj>\d+)\.(?<min>\d+)\.(?<pat>\d+) · 补丁 (?<seq>\d+)（(?<date>\d{4}-\d{2}-\d{2})）')
if (-not $m.Success) {
    Write-Output '[失败] src\function\Sections.java 里没找到版本行'
    Write-Output '       期望形如：v3.0.1 · 补丁 01（2026-11-16）'
    exit 2
}

$maj = $m.Groups['maj'].Value
$min = $m.Groups['min'].Value
$curVer = 'v' + $maj + '.' + $min + '.' + $m.Groups['pat'].Value
$curSeq = $m.Groups['seq'].Value

# 只把「现在这份源码」记为已发布，不动版本号（首次启用时用一次）
if ($Stamp) {
    $todayStamp = Get-Date -Format 'yyyy-MM-dd'
    if ($DryRun) {
        Write-Output ('当前版本 ' + $curVer + '，指纹 ' + $fingerprint)
        exit 0
    }
    $utf8Stamp = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($statePath, ("hash=$fingerprint`nversion=$curVer`ndate=$todayStamp`n"), $utf8Stamp)
    Write-Output ('已把当前源码记为 ' + $curVer + '，之后有改动才会推版本')
    exit 0
}

if (-not $Force -and $state['hash'] -eq $fingerprint) {
    Write-Output ("源码与上次发布一致，版本号保持 " + $curVer + "（补丁 " + $curSeq + "）—— 没有改动就不推版本")
    exit 0
}

$newPat = [int]$m.Groups['pat'].Value + 1
$newSeq = ([int]$m.Groups['seq'].Value + 1).ToString('00')
$newVer = 'v' + $maj + '.' + $min + '.' + $newPat
$today = Get-Date -Format 'yyyy-MM-dd'
$buildDate = Get-Date -Format 'yyyy.MM.dd'

# 1) 设置页的版本行
$secNew = $sec.Replace($m.Value, $newVer + ' · 补丁 ' + $newSeq + '（' + $today + '）')

# 2) 左栏 BUILD 徽标
$web = Read-Text $webPath
$webNew = $web -replace '(BUILD</span><span>)v\d+\.\d+\.\d+', ('${1}' + $newVer)
if ($webNew -eq $web) {
    Write-Output '[失败] WebServer.java 里没匹配到 BUILD 徽标的版本号'
    exit 2
}

# 3) 开屏两处
$splash = Read-Text $splashPath
$splashNew = $splash -replace 'v\d+\.\d+\.\d+ · BUILD \d{4}\.\d{2}\.\d{2}', ($newVer + ' · BUILD ' + $buildDate)
$splashNew = $splashNew -replace '(<div id="cornerMeta">)v\d+\.\d+\.\d+', ('${1}' + $newVer)
if ($splashNew -eq $splash) {
    Write-Output '[失败] splash.html 里没匹配到版本号'
    exit 2
}

# 4) 桌面壳版本（只影响以后重新打安装包时的文件名/自动更新比对）
$pkg = Read-Text $pkgPath
$pkgNew = $pkg -replace '("version"\s*:\s*")1\.0\.\d+', ('${1}1.0.' + $newPat)
if ($pkgNew -eq $pkg) {
    Write-Output '[提示] desktop\package.json 的版本不是 1.0.x，跳过'
}

Write-Output ("版本号：" + $curVer + "  ->  " + $newVer + "    补丁 " + $curSeq + "  ->  " + $newSeq + "    日期 " + $today)
Write-Output '  src\function\Sections.java    设置页版本行'
Write-Output '  src\function\WebServer.java   左栏 BUILD 徽标'
Write-Output '  static\splash.html            开屏版本行 + 右上角'
if ($pkgNew -ne $pkg) { Write-Output ('  desktop\package.json          ' + '1.0.' + ($newPat - 1) + ' -> 1.0.' + $newPat) }

if ($DryRun) {
    Write-Output '（DryRun：没有写入任何文件）'
    exit 0
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($secPath, $secNew, $utf8)
[IO.File]::WriteAllText($webPath, $webNew, $utf8)
[IO.File]::WriteAllText($splashPath, $splashNew, $utf8)
if ($pkgNew -ne $pkg) { [IO.File]::WriteAllText($pkgPath, $pkgNew, $utf8) }

# 记下「bump 之后」的指纹，下次没改动就不会再往上推
$newFingerprint = Get-Fingerprint $Root
[IO.File]::WriteAllText($statePath, ("hash=$newFingerprint`nversion=$newVer`ndate=$today`n"), $utf8)

Write-Output ("[完成] 已写入 " + $newVer + "，编译时新版本号会跟着进 class")
Write-Output '       改动还没提交，记得 git commit（版本号属于本次补丁的一部分）'
exit 0
