<#
.SYNOPSIS
  把页面模板渲染成 HTML 并用 headless Edge/Chrome 截图，不需要 MySQL、不需要启动服务器。

.EXAMPLE
  .\preview.ps1 -Page main
  .\preview.ps1 -Page query -Open
  .\preview.ps1 -Page form -Width 900 -Height 1200
  .\preview.ps1 -Body my-body.html -Out .\shot.png
#>
param(
    [ValidateSet('main', 'query', 'form')]
    [string]$Page = 'main',

    [string]$Body = '',

    [string]$Out = '',

    [int]$Width = 1280,

    [int]$Height = 1000,

    [switch]$Open
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $scriptDir '..\..')).Path
$build = Join-Path $root '.build_tmp\page-preview'
$classes = Join-Path $build 'classes'
$harness = Join-Path $build 'harness'

New-Item -ItemType Directory -Force -Path $classes, $harness | Out-Null

# 1. 编译项目（PagePreview 反射调用 WebServer 的私有模板方法）
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 --release 17 -cp "$root\lib\*" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw '项目编译失败' }

# 2. 编译预览器
& javac -encoding UTF-8 --release 17 -cp $classes -d $harness (Join-Path $scriptDir 'PagePreview.java')
if ($LASTEXITCODE -ne 0) { throw 'PagePreview 编译失败' }

# 3. 渲染 HTML
$htmlOut = Join-Path $build 'page.html'
$argPage = if ($Body) { (Resolve-Path $Body).Path } else { $Page }
& java "-Dproject.root=$root" -cp "$classes;$harness" PagePreview $argPage $htmlOut
if ($LASTEXITCODE -ne 0) { throw '页面渲染失败' }

# 4. 找浏览器
$candidates = @(
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe",
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe"
)
$browser = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $browser) { throw '没找到 Edge/Chrome，无法截图' }

# 5. 截图
if (-not $Out) {
    $Out = Join-Path $build "shot-$Page.png"
}
if (Test-Path $Out) { Remove-Item -Force $Out }
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $browser --headless=new --disable-gpu --hide-scrollbars `
    "--screenshot=$Out" "--window-size=$Width,$Height" `
    --virtual-time-budget=6000 (([uri]$htmlOut).AbsoluteUri) 2>$null | Out-Null
$ErrorActionPreference = $prevEap

if (-not (Test-Path $Out)) { throw '截图失败' }

Write-Output $Out
if ($Open) { Start-Process $Out }
