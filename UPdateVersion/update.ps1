<#
.SYNOPSIS
    把项目当前源码编译后同步进「已安装」的 Bookkeeping System —— 免打包、免重装、可回滚。

.DESCRIPTION
    流程：
      1. 检查环境和 javac
      2. 编译 src → 暂存目录 .stage\classes
      3. 暂存 static\ 与 lib\*.jar
      4. 定位已安装目录（读注册表卸载项，不写死路径）
      5. 关闭正在运行的软件 → 备份旧版本到 backup\<日期-时间>
      6. 覆盖 classes / static / lib → 重新启动 → 写日志

  只替换 resources\runtime 下的 Java 产物；
  如果改的是 desktop\（Electron 外壳 main.js、preload 等），必须重新跑 build.ps1 出安装包，
  本脚本管不到 app.asar。

.PARAMETER InstallDir
    手动指定安装目录（目录下应有 resources\runtime）。默认自动从注册表找。

.PARAMETER NoRestart
    更新完不自动启动软件。

.PARAMETER Rollback
    还原最近一次备份（不编译、不更新）。

.PARAMETER KeepBackups
    不清理旧备份（默认只保留最近 5 份）。

.PARAMETER KeepCount
    保留几份备份，默认 5。

.EXAMPLE
    .\update.ps1
.EXAMPLE
    .\update.ps1 -NoRestart
.EXAMPLE
    .\update.ps1 -Rollback
#>
[CmdletBinding()]
param(
    [string]$InstallDir,
    [switch]$NoRestart,
    [switch]$Rollback,
    [switch]$KeepBackups,
    [int]$KeepCount = 5,
    [switch]$Elevated
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $here
$stage = Join-Path $here '.stage'
$backupRoot = Join-Path $here 'backup'
$logDir = Join-Path $here 'logs'
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
$script:logPath = $null

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$script:logPath = Join-Path $logDir ("update-$stamp.log")

function Say {
    param([string]$Message = '', [string]$Color = 'Gray')
    Write-Host $Message -ForegroundColor $Color
    try { Add-Content -Path $script:logPath -Value $Message -Encoding UTF8 } catch { }
}

function Fail {
    param([string]$Message)
    Say ''
    Say "[失败] $Message" 'Red'
    Say "日志：$script:logPath" 'DarkGray'
    exit 1
}

function Step {
    param([string]$Text)
    Say ''
    Say "== $Text ==" 'Cyan'
}

# ---------------------------------------------------------------- 工具函数

function Get-InstallDir {
    param([string]$Explicit)

    if ($Explicit) {
        $p = $Explicit.TrimEnd('\', '/')
        if (Test-Path (Join-Path $p 'resources\runtime\classes')) { return (Resolve-Path $p).Path }
        throw "指定的目录里没有 resources\runtime\classes：$p"
    }

    $cands = New-Object System.Collections.Generic.List[string]
    $roots = @(
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    foreach ($r in $roots) {
        $items = @(Get-ItemProperty $r -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like 'Bookkeeping System*' })
        foreach ($item in $items) {
            if ($item.InstallLocation) { $cands.Add($item.InstallLocation) }
            if ($item.UninstallString) {
                $m = [regex]::Match([string]$item.UninstallString, '"([^"]+)"')
                if (-not $m.Success) { $m = [regex]::Match([string]$item.UninstallString, '([A-Za-z]:\\[^"]*?\.exe)') }
                if ($m.Success) { $cands.Add((Split-Path -Parent $m.Groups[1].Value)) }
            }
        }
    }
    $cands.Add((Join-Path $env:LOCALAPPDATA 'Programs\Bookkeeping System'))
    $cands.Add('E:\Bookkeeping System\The-main')

    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c 'resources\runtime\classes'))) { return (Resolve-Path $c).Path }
    }
    return $null
}

function Get-AppProcesses {
    param([string]$Dir)
    return @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
            $_.Path -and $_.Path.StartsWith($Dir, [StringComparison]::OrdinalIgnoreCase)
        })
}

function Stop-AppProcesses {
    param([string]$Dir)

    $procs = Get-AppProcesses $Dir
    if ($procs.Count -eq 0) { Say '   软件当前没在运行' ; return }

    foreach ($p in $procs) { Say ("   关闭 {0} (PID {1})" -f $p.ProcessName, $p.Id) }
    foreach ($p in $procs) {
        try { if ($p.MainWindowHandle -ne 0) { $p.CloseMainWindow() | Out-Null } } catch { }
    }

    # 给窗口 8 秒正常退出时间
    for ($i = 0; $i -lt 16; $i++) {
        Start-Sleep -Milliseconds 500
        if ((Get-AppProcesses $Dir).Count -eq 0) { return }
    }

    # 还赖着就强杀（含它拉起的 java 子进程）
    Say '   有进程没响应关闭，改为强制结束' 'Yellow'
    foreach ($p in (Get-AppProcesses $Dir)) {
        try { Stop-Process -Id $p.Id -Force -ErrorAction Stop } catch { }
    }
    Start-Sleep -Milliseconds 1200
    if ((Get-AppProcesses $Dir).Count -gt 0) { throw '软件进程关不掉，请手动退出后再试' }
}

function Invoke-Mirror {
    param([string]$Source, [string]$Target)

    $out = & robocopy $Source $Target /MIR /NFL /NDL /NJH /NJS /NP /R:2 /W:1 2>&1
    $code = $LASTEXITCODE
    if ($code -ge 8) { throw ("robocopy 失败（代码 $code）：" + (($out | Out-String).Trim())) }
}

function Assert-Writable {
    param([string]$Dir)

    $probe = Join-Path $Dir ('.wtest-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    try {
        New-Item -ItemType File -Path $probe -Force | Out-Null
        Remove-Item $probe -Force
    } catch {
        if ($Elevated) { throw "即使以管理员身份运行也无法写入：$Dir" }
        Say '   安装目录需要管理员权限，正在提权重试…' 'Yellow'
        $argList = @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Elevated')
        if ($InstallDir) { $argList += @('-InstallDir', ('"' + $InstallDir + '"')) }
        if ($NoRestart) { $argList += '-NoRestart' }
        if ($Rollback) { $argList += '-Rollback' }
        Start-Process powershell -Verb RunAs -ArgumentList ($argList -join ' ')
        Say '   已在新的管理员窗口中继续，这个窗口可以关掉了。' 'Yellow'
        exit 0
    }
}

function Start-App {
    param([string]$Dir)

    if ($NoRestart) { Say '   已跳过启动（-NoRestart）' ; return }
    $exe = Join-Path $Dir 'Bookkeeping System.exe'
    if (-not (Test-Path $exe)) { Say "   没找到 $exe，跳过启动" 'Yellow' ; return }
    Start-Process -FilePath $exe -WorkingDirectory $Dir | Out-Null
    Start-Sleep -Seconds 3
    $alive = @(Get-AppProcesses $Dir)
    if ($alive.Count -gt 0) { Say ("   已启动：{0} (PID {1})" -f $alive[0].ProcessName, $alive[0].Id) }
    else { Say '   启动后进程立刻退出了，请手动打开看看（日志：%APPDATA%\BookkeepingSystem\desktop.log）' 'Yellow' }
}

function Remove-OldBackups {
    if ($KeepBackups) { return }
    $dirs = @(Get-ChildItem $backupRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    if ($dirs.Count -le $KeepCount) { return }
    foreach ($d in ($dirs | Select-Object -Skip $KeepCount)) {
        try { Remove-Item $d.FullName -Recurse -Force -ErrorAction Stop ; Say ("   清理旧备份 " + $d.Name) 'DarkGray' } catch { }
    }
}

# ---------------------------------------------------------------- 回滚分支

Say '消费记录系统 · 本地更新脚本' 'White'
Say ("时间：{0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) 'DarkGray'
Say ("项目：{0}" -f $root) 'DarkGray'

$installDir = $null
try { $installDir = Get-InstallDir $InstallDir }
catch { Fail $_.Exception.Message }

if (-not $installDir) {
    Fail "没找到已安装的 Bookkeeping System。`n       先装一次安装包，或用 -InstallDir 指定目录（目录下应有 resources\runtime）。"
}

if ($Rollback) {
    Step '回滚到最近一次备份'
    $latest = @(Get-ChildItem $backupRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1)
    if ($latest.Count -eq 0) { Fail "没有可用的备份（$backupRoot 是空的）" }
    $bk = $latest[0].FullName
    Say ("   备份：{0}" -f $bk)
    if (-not (Test-Path (Join-Path $bk 'classes'))) { Fail '该备份里没有 classes，无法回滚' }

    Assert-Writable (Join-Path $installDir 'resources\runtime')
    Stop-AppProcesses $installDir
    foreach ($name in @('classes', 'static', 'lib')) {
        $src = Join-Path $bk $name
        if (Test-Path $src) {
            Invoke-Mirror $src (Join-Path $installDir "resources\runtime\$name")
            Say ("   还原 $name")
        }
    }
    Say ''
    Say ('[完成] 已回滚到 ' + $bk) 'Green'
    Say ("       安装目录：{0}" -f $installDir) 'Green'
    Start-App $installDir
    Say ''
    Say '按任意键关闭…' 'DarkGray'
    exit 0
}

# ---------------------------------------------------------------- 1. 环境

Step '1/6 检查环境'

foreach ($need in @('src', 'lib', 'static')) {
    if (-not (Test-Path (Join-Path $root $need))) { Fail "项目里缺 $need 目录：$root" }
}

$javac = $null
$cmd = Get-Command javac -ErrorAction SilentlyContinue
if ($cmd) { $javac = $cmd.Source }
if (-not $javac -and $env:JAVA_HOME) { $c = Join-Path $env:JAVA_HOME 'bin\javac.exe'; if (Test-Path $c) { $javac = $c } }
if (-not $javac) {
    $probe = @(Get-ChildItem 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium', 'E:\java pack' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'bin\javac.exe' } | Where-Object { Test-Path $_ })
    if ($probe.Count -gt 0) { $javac = $probe[0] }
}
if (-not $javac) { Fail '找不到 javac。请确认系统装了 JDK 17+ 且 javac 在 PATH 里（或设置 JAVA_HOME）。' }

$javacVersion = (& $javac -version 2>&1 | Out-String).Trim()
Say ("   javac：{0}  ({1})" -f $javac, $javacVersion)
Say ("   安装目录：{0}" -f $installDir)

Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 2. 编译

Step '2/6 编译 Java'

Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
$stageClasses = Join-Path $stage 'classes'
New-Item -ItemType Directory -Force -Path $stageClasses | Out-Null

$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { Fail "src 下没有 .java 文件" }
Say ("   源码 {0} 个" -f $sources.Count)

$compileOut = & $javac -encoding UTF-8 --release 17 -cp (Join-Path $root 'lib\*') -d $stageClasses @sources 2>&1
if ($LASTEXITCODE -ne 0) {
    $compileOut | Select-Object -Last 25 | ForEach-Object { Say ("   " + $_) 'Red' }
    Fail '编译失败，已中止（安装目录里的文件一个都没动）'
}
$warn = @($compileOut | Where-Object { $_ -match '\S' })
if ($warn.Count -gt 0) { Say ("   编译警告 {0} 行（不影响运行）" -f $warn.Count) 'DarkGray' }

foreach ($must in @('System\The_main.class', 'function\WebServer.class')) {
    if (-not (Test-Path (Join-Path $stageClasses $must))) { Fail "编译产物里缺少 $must，中止" }
}
Say ("   产物 " + (Get-ChildItem $stageClasses -Recurse -File).Count + " 个 class")

# ---------------------------------------------------------------- 3. 暂存

Step '3/6 暂存静态资源与依赖'

$stageStatic = Join-Path $stage 'static'
$stageLib = Join-Path $stage 'lib'
New-Item -ItemType Directory -Force -Path $stageStatic, $stageLib | Out-Null
Copy-Item (Join-Path $root 'static\*') $stageStatic -Recurse -Force
Copy-Item (Join-Path $root 'lib\*.jar') $stageLib -Force
Say ("   static {0} 个文件 / lib {1} 个 jar" -f (Get-ChildItem $stageStatic -Recurse -File).Count, (Get-ChildItem $stageLib -File).Count)

# ---------------------------------------------------------------- 4. 关闭 + 备份

Step '4/6 关闭软件并备份旧版本'

$runtimeDir = Join-Path $installDir 'resources\runtime'
Assert-Writable $runtimeDir
Stop-AppProcesses $installDir

$backupDir = Join-Path $backupRoot $stamp
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
foreach ($name in @('classes', 'static', 'lib')) {
    $src = Join-Path $runtimeDir $name
    if (Test-Path $src) { Invoke-Mirror $src (Join-Path $backupDir $name) }
}
$bkSize = ((Get-ChildItem $backupDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB)
Say ("   备份 {0}（{1:N1} MB）" -f $backupDir, $bkSize)

# ---------------------------------------------------------------- 5. 写入

Step '5/6 写入安装目录'

foreach ($name in @('classes', 'static', 'lib')) {
    Invoke-Mirror (Join-Path $stage $name) (Join-Path $runtimeDir $name)
    Say ("   " + $name + " 已更新")
}

if (-not (Test-Path (Join-Path $runtimeDir 'classes\function\WebServer.class'))) {
    Fail "写入后校验失败：$runtimeDir\classes\function\WebServer.class 不存在。可用 rollback.bat 还原。"
}

# ---------------------------------------------------------------- 6. 收尾

Step '6/6 收尾'
Remove-OldBackups
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

Say ''
Say '[完成] 已把当前源码更新到安装目录' 'Green'
Say ("       安装目录：{0}" -f $installDir) 'Green'
Say ("       备份：{0}" -f $backupDir) 'Green'
Say ("       回滚：双击 {0}\rollback.bat" -f $here) 'Green'
Say ("       日志：{0}" -f $script:logPath) 'DarkGray'

Start-App $installDir

Say ''
Say '按任意键关闭…' 'DarkGray'
