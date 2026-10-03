# pid-preview/preview.ps1 - 把一款车的水牌版式画成 PNG，不用起客户端（notes/358）
#
# 为什么需要它：「不同车型有不同尺寸和排版的水牌」—— 排版好不好看只能看图，而"改一行 → 起客户端 →
# 找车 → 截图"一分钟都下不来。这里跑**真的那条链**（MmtrPidLayout.parse + MmtrPanelCanvas，
# 与游戏同一份 Java2D 与同一套字体），一秒钟出一张图。
#
# 用法：
#   pwsh -File mmtr\tools\pid-preview\preview.ps1 -Anchor <mmtr_anchors_x.json> [-Board pid|next] `
#        [-Service 00101] [-Terminus 海山] [-Next 鸥湾] [-Out <png>] [-WidthM 1.24 -HeightM 0.22 -PxPerMetre 512]
#
# 尺寸默认从锚点里的 widthM/heightM 读（与游戏里完全一致）；给了 -WidthM/-HeightM 就覆盖 —— 用来回答
# "这块牌做成 1.6 × 0.12 m 好不好看"。版式里的位置/字号全是比例，所以同一份版式在任何尺寸下自动等比。
#
# 只在 sandbox\ 下写文件，不碰 build\，所以游戏开着也能跑（同 mmtr/scripts/check-java-compile.ps1）。
param(
	[Parameter(Mandatory = $true)][string]$Anchor,
	[ValidateSet('pid', 'next')][string]$Board = 'pid',
	[string]$Service = '00101',
	[string]$Terminus = '海山',
	[string]$Next = '鸥湾',
	[string]$Out = '',
	[double]$WidthM = 0,
	[double]$HeightM = 0,
	[int]$PxPerMetre = 512
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$fabric = Join-Path $mcRoot 'mmtr\game\fabric'
$argFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
$work = Join-Path $mcRoot 'sandbox\pid-preview'

if (-not (Test-Path $argFile)) {
	Write-Output "[pid-preview] 没有 $argFile —— 先起一次客户端，让 loom 写出它的 classpath"
	exit 2
}
if (-not (Test-Path $Anchor)) {
	Write-Output "[pid-preview] 找不到锚点文件：$Anchor"
	exit 2
}

$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
$java = Join-Path $mcRoot 'env\jdk-21\bin\java.exe'
if (-not (Test-Path $javac)) {
	$jdks = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -ErrorAction SilentlyContinue
	$javac = (Get-ChildItem $jdks.FullName -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
	$java = Join-Path (Split-Path -Parent $javac) 'java.exe'
}
if (-not (Test-Path $javac) -or -not (Test-Path $java)) { Write-Output '[pid-preview] 找不到 JDK'; exit 2 }

$classpath = ((Get-Content $argFile)[1] -replace '" "', ' ').Trim('"')
$jsr305 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.findbugs" -Recurse -Filter 'jsr305-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jsr305) { $classpath = "$classpath;$($jsr305.FullName)" }

$outDir = Join-Path $work 'classes'
Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $outDir | Out-Null

# 只编"我们刚改过的那几个文件 + 预览本体"：其余（Init/映射/工具类）走 classpath 上已编好的类。
$sources = @(
	(Join-Path $fabric 'src\main\java\org\mtr\mod\render\panel\MmtrPidLayout.java'),
	(Join-Path $fabric 'src\main\java\org\mtr\mod\render\panel\MmtrPanelCanvas.java'),
	(Join-Path $fabric 'src\main\java\org\mtr\mod\render\panel\MmtrPanelFont.java'),
	(Join-Path $fabric 'src\main\java\org\mtr\mod\mmtr\MmtrPidText.java'),
	(Join-Path $PSScriptRoot 'PidPreview.java')
) | Where-Object { Test-Path $_ }

$responseFile = Join-Path $work 'sources.txt'
$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $responseFile -Encoding UTF8
& $javac -proc:none -nowarn -encoding UTF-8 -d $outDir -cp $classpath "@$responseFile"
if ($LASTEXITCODE -ne 0) { Write-Output '[pid-preview] 编译失败'; exit 1 }

if ([string]::IsNullOrEmpty($Out)) {
	$Out = Join-Path $work ('board_' + $Board + '_' + [System.IO.Path]::GetFileNameWithoutExtension($Anchor) + '.png')
}

$previewArgs = @($Anchor, $Out, $Board, $Service, $Terminus, $Next)
if ($WidthM -gt 0 -and $HeightM -gt 0) { $previewArgs += $WidthM; $previewArgs += $HeightM; $previewArgs += $PxPerMetre }

# -D 必须连引号一起给：不加引号 PowerShell 会把 -Djava.awt.headless=true 当参数名（吃掉 -Dj 前缀）。
& $java "-Djava.awt.headless=true" -cp "$outDir;$classpath" mmtr.pidpreview.PidPreview @previewArgs
exit $LASTEXITCODE
