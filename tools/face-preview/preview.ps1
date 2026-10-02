# face-preview/preview.ps1 - 把一块**动态面**画成 PNG，不用起客户端（notes/359 · F1）
#
# 与 pid-preview 同一条思路：跑**真的那条链**（MmtrFaceDocument 解析 → MmtrFaceElements 画 →
# MmtrPanelCanvas 出图），所以"改一段 JSON → 看一眼"是一秒钟的事。区别是它认的是 faces 段
# （元素 / 条件 / 模板），而不是写死的两行水牌版式；老的水牌版式用 -Builtin pid|next 也照样能出图。
#
# 用法：
#   # 先看这块车有哪些面、有哪些锚点（不知道 -Face 写什么时先跑这个）
#   pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <mmtr_anchors_x.json> -List
#
#   # 一块面 × 四个状态各出一张
#   foreach ($p in 'running','stopped','return','idle') {
#     pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -Preset $p -Out ".\sandbox\face-preview\face_1_$p.png"
#   }
#
#   # 换个尺寸看排版（比例坐标 ⇒ 同一份文档自动等比）
#   pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -WidthM 1.6 -HeightM 0.12
#
#   # 覆写任意字段（字段表见 mmtr\tools\face-studio\fields.json）
#   pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -Data speed=0.02,pid.next=干沙
#
#   # v2（F3）：看**第 2 页、8 秒处**的样子（动画/轮播都是时间的函数 ⇒ 不给时刻只能看到第 0 帧）
#   pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -Page 1 -TimeMs 8000 -Out .\sandbox\face-preview\p1_t8s.png
#
#   # image 元素的图片从哪找（每个目录是一个资源包根，里面有 assets\；不给就自己找 resourcepacks）
#   pwsh -File mmtr\tools\face-preview\preview.ps1 -Anchor <json> -Face face_1 -Pack .\run\resourcepacks\MMTR_SAF420_v37
#
# 只在 sandbox\ 下写文件（不给 -Out 时缺省落到 sandbox\face-preview\），不碰 build\，所以游戏开着也能跑。
# -Pack 指向的资源包目录只读不写；-TmpDir 缺省也是 sandbox\face-preview\tmp。
param(
	[Parameter(Mandatory = $true)][string]$Anchor,
	[string]$Face = '',
	[ValidateSet('', 'pid', 'next')][string]$Builtin = '',
	[string]$Vehicle = 'preview',
	[string]$Out = '',
	[ValidateSet('running', 'stopped', 'return', 'idle')][string]$Preset = 'running',
	# 覆写字段：`字段=值` 的列表（逗号或多次 -Data 都给）
	[string[]]$Data = @(),
	[double]$WidthM = 0,
	[double]$HeightM = 0,
	[int]$PxPerMetre = 0,
	# v2（F3）：动画/轮播看哪一瞬间（毫秒）；连续看就用 0,500,1000… 各出一张
	[long]$TimeMs = 0,
	# v2（F3）：硬看第几页（0 起；默认 -1 = 让文档自己选：pageExpr 或 pageSeconds 轮转）
	[int]$Page = -1,
	# v2（F3）：image 元素的资源包目录（`;` 分隔多个；不给就自己去找 resourcepacks）
	[string]$Pack = '',
	# image 元素用的图片要过 ImageIO：它的临时缓存目录写不了时会**读不出任何图**（症状与"图没找到"一样是洋红框）。
	# 默认指向 sandbox\face-preview\tmp（一样不碰 build\）；要换就传 -TmpDir。
	[string]$TmpDir = '',
	[switch]$List,
	[switch]$Force,
	[switch]$PrintData
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$fabric = Join-Path $mcRoot 'mmtr\game\fabric'
$argFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
$work = Join-Path $mcRoot 'sandbox\face-preview'

if (-not (Test-Path $argFile)) {
	Write-Output "[face-preview] 没有 $argFile —— 先起一次客户端，让 loom 写出它的 classpath"
	exit 2
}
if (-not (Test-Path $Anchor)) {
	Write-Output "[face-preview] 找不到锚点文件：$Anchor"
	exit 2
}

$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
$java = Join-Path $mcRoot 'env\jdk-21\bin\java.exe'
if (-not (Test-Path $javac)) {
	$jdks = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -ErrorAction SilentlyContinue
	$javac = (Get-ChildItem $jdks.FullName -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
	$java = Join-Path (Split-Path -Parent $javac) 'java.exe'
}
if (-not (Test-Path $javac) -or -not (Test-Path $java)) { Write-Output '[face-preview] 找不到 JDK'; exit 2 }

$classpath = ((Get-Content $argFile)[1] -replace '" "', ' ').Trim('"')
$jsr305 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.findbugs" -Recurse -Filter 'jsr305-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jsr305) { $classpath = "$classpath;$($jsr305.FullName)" }

$outDir = Join-Path $work 'classes'
if (-not (Test-Path (Join-Path $outDir 'mmtr\facepreview\FacePreview.class'))) {
	Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
	New-Item -ItemType Directory -Force $outDir | Out-Null

	# 面系统这一整套 + 画布/字体 + 预览本体。其余（Init / IGui / 映射）走 classpath 上已编好的类。
	$sources = @(
		'render\panel\MmtrPanelCanvas.java',
		'render\panel\MmtrPanelFont.java',
		'render\panel\MmtrFaceElements.java',
		'render\panel\MmtrFaceImages.java',
		'mmtr\MmtrPidText.java',
		'mmtr\face\MmtrFaceSource.java',
		'mmtr\face\MmtrFaceLogic.java',
		'mmtr\face\MmtrFaceText.java',
		'mmtr\face\MmtrFaceFields.java',
		'mmtr\face\MmtrFaceData.java',
		'mmtr\face\MmtrFaceDocument.java',
		'mmtr\face\MmtrFaceAnim.java',
		'mmtr\face\MmtrFaceGeometry.java',
		'mmtr\face\MmtrFaceSchema.java',
		# notes/361（F4）：SPI 那一组 —— MmtrFaceData 会问"扩展字段登记表"，
		# 于是这几个也必须一起编（否则 javac 报找不到 MmtrFaceExtension 等符号）。
		'mmtr\face\MmtrFaceWarnings.java',
		'mmtr\face\MmtrFaceFilter.java',
		'mmtr\face\MmtrFaceExtension.java',
		'mmtr\face\MmtrFaceField.java',
		'render\panel\MmtrFaceElementRenderer.java',
		'render\panel\MmtrFaceRegistrar.java'
	) | ForEach-Object { Join-Path $fabric "src\main\java\org\mtr\mod\$_" } | Where-Object { Test-Path $_ }
	$sources += (Join-Path $PSScriptRoot 'FacePreview.java')

	$responseFile = Join-Path $work 'sources.txt'
	$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $responseFile -Encoding UTF8
	& $javac -proc:none -nowarn -encoding UTF-8 -d $outDir -cp $classpath "@$responseFile"
	if ($LASTEXITCODE -ne 0) { Write-Output '[face-preview] 编译失败'; exit 1 }
}

$previewArgs = @('--anchors', $Anchor, '--vehicle', $Vehicle)
if ($List) {
	$previewArgs += '--list'
} else {
	if (-not [string]::IsNullOrEmpty($Face)) { $previewArgs += @('--face', $Face) }
	if (-not [string]::IsNullOrEmpty($Builtin)) { $previewArgs += @('--builtin', $Builtin) }
	$previewArgs += @('--preset', $Preset)
	# 不给 -Out 时**落到 sandbox\face-preview\**：FacePreview 的缺省是"当前目录下 face-preview-<面名>.png"，
	# 从 mmtr\ 里跑就会在仓库里掉一张图（"只在 sandbox\ 下写文件"这条就没守住）。这里按 $work 补全。
	$outPath = $Out
	if ([string]::IsNullOrEmpty($outPath)) {
		$outPath = Join-Path $work ('face-preview-' + $(if ([string]::IsNullOrEmpty($Face)) { 'builtin' } else { $Face }) + '.png')
	}
	$previewArgs += @('--out', $outPath)
	if ($WidthM -gt 0 -and $HeightM -gt 0) { $previewArgs += @('--widthM', "$WidthM", '--heightM', "$HeightM") }
	if ($PxPerMetre -gt 0) { $previewArgs += @('--pxPerMetre', "$PxPerMetre") }
	# v2 的三个开关：只透传给 FacePreview（它自己解析），这里不做任何加工 ——
	# 少传一个的表现是"动画看着不动/图片是洋红框"，作者会以为是文档写错了。
	# --timeMs 给 0 也要传：0 = 第一帧，是"看一眼版式"最常用的那个时刻。
	$previewArgs += @('--timeMs', "$TimeMs")
	if ($Page -ge 0) { $previewArgs += @('--page', "$Page") }
	if (-not [string]::IsNullOrEmpty($Pack)) { $previewArgs += @('--pack', $Pack) }
	if ($Force) { $previewArgs += '--force' }
	if ($PrintData) { $previewArgs += '--print-data' }
	# -Data 支持 `-Data a=1,b=2` 与 `-Data a=1 -Data b=2` 两种写法
	foreach ($pair in ($Data | ForEach-Object { $_ -split ',' } | Where-Object { $_ -match '=' })) {
		$previewArgs += @('--data', $pair)
	}
}

# -D 必须连引号一起给：不加引号 PowerShell 会把 -Djava.awt.headless=true 当参数名（吃掉 -Dj 前缀）。
# stdout.encoding=UTF-8：不然中文输出在 GBK 控制台上是乱码（作者读的就是这些行）。
# java.io.tmpdir 也要给：ImageIO 读图时会往临时目录写缓存文件，写不了就"读不出任何图"
# （症状是洋红占位框 + Can't create cache file，很容易被当成"我的 src 写错了"）。落在 sandbox 下，不碰 build\。
if ([string]::IsNullOrEmpty($TmpDir)) { $TmpDir = Join-Path $work 'tmp' }
New-Item -ItemType Directory -Force $TmpDir | Out-Null
& $java "-Djava.awt.headless=true" "-Dstdout.encoding=UTF-8" "-Dfile.encoding=UTF-8" "-Djava.io.tmpdir=$TmpDir" -cp "$outDir;$classpath" mmtr.facepreview.FacePreview @previewArgs
exit $LASTEXITCODE
