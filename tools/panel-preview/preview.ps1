<#
preview.ps1 - paint a vehicle's `hud` layout to a PNG without launching the game.

A dashboard is judged by looking at it. This runs the REAL client code path (`MmtrHudLayout.parse` +
`MmtrPanelCanvas`) headlessly, so the picture is what the panel will be, and iterating on a layout costs
a second instead of a client launch. See mmtr/tools/panel-preview/PanelPreview.java.

It compiles against the classpath a dev launch already uses (loom's runClient argfile) and writes
everything into sandbox/ - nothing under build/ is touched, so it is safe while the game is running
(same reason as mmtr/scripts/check-java-compile.ps1).

Usage:
  pwsh -File mmtr\tools\panel-preview\preview.ps1 -Anchor <mmtr_anchors_x.json> [-Out <png>] [-Speed 72]
       [-Limit 80] [-WidthM 3.16] [-HeightM 0.46] [-PxPerMetre 256]
#>
param(
  [Parameter(Mandatory = $true)][string]$Anchor,
  [string]$Out = '',
  [int]$Speed = 72,
  [long]$Limit = 80,
  [double]$WidthM = 0,
  [double]$HeightM = 0,
  [int]$PxPerMetre = 256
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$fabric = Join-Path $mcRoot 'mmtr\game\fabric'
$argFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
$work = Join-Path $mcRoot 'sandbox\panel-preview'

if (-not (Test-Path $argFile)) {
  Write-Output "[panel-preview] no $argFile - run the client once so loom writes its classpath"
  exit 2
}

$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
$java = Join-Path $mcRoot 'env\jdk-21\bin\java.exe'
if (-not (Test-Path $javac)) {
  $jdks = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -ErrorAction SilentlyContinue
  $javac = (Get-ChildItem $jdks.FullName -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
  $java = Join-Path (Split-Path -Parent $javac) 'java.exe'
}
if (-not (Test-Path $javac) -or -not (Test-Path $java)) { Write-Output '[panel-preview] no JDK found'; exit 2 }

$classpath = ((Get-Content $argFile)[1] -replace '" "', ' ').Trim('"')
$jsr305 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.findbugs" -Recurse -Filter 'jsr305-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jsr305) { $classpath = "$classpath;$($jsr305.FullName)" }

$outDir = Join-Path $work 'classes'
Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $outDir | Out-Null

# Only the classes the preview needs (not the whole tree): the layout, the canvas, the font and the
# mapping/data helpers they pull in are already compiled in build/classes, so they come from the
# classpath and javac only has to build the preview itself plus any file we just changed.
$changed = @(
  (Join-Path $fabric 'src\main\java\org\mtr\mod\render\panel\MmtrHudLayout.java'),
  (Join-Path $fabric 'src\main\java\org\mtr\mod\render\panel\MmtrPanelCanvas.java'),
  (Join-Path $fabric 'src\main\java\org\mtr\mod\render\panel\MmtrPanelFont.java'),
  (Join-Path $PSScriptRoot 'PanelPreview.java')
) | Where-Object { Test-Path $_ }

$responseFile = Join-Path $work 'sources.txt'
$changed | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $responseFile -Encoding UTF8
& $javac -proc:none -nowarn -encoding UTF-8 -d $outDir -cp $classpath "@$responseFile"
if ($LASTEXITCODE -ne 0) { Write-Output '[panel-preview] compile FAILED'; exit 1 }

if ([string]::IsNullOrEmpty($Out)) {
  $Out = Join-Path $mcRoot ('sandbox\vox_preview\panel_' + [System.IO.Path]::GetFileNameWithoutExtension($Anchor) + '.png')
}

$previewArgs = @($Anchor, $Out, $Speed, $Limit)
if ($WidthM -gt 0) { $previewArgs += $WidthM }
if ($HeightM -gt 0) { $previewArgs += $HeightM }
if ($WidthM -gt 0 -and $HeightM -gt 0) { $previewArgs += $PxPerMetre }

# Headless: this is Java2D only (BufferedImage + ImageIO), no GL and no game window.
# The -D flag MUST be quoted: unquoted, PowerShell reads "-Djava.awt.headless=true" as a parameter name
# (it fails with "找不到... .awt.headless=true", i.e. it ate the -Dj prefix).
& $java "-Djava.awt.headless=true" -cp "$outDir;$classpath" mmtr.panelpreview.PanelPreview @previewArgs
exit $LASTEXITCODE
