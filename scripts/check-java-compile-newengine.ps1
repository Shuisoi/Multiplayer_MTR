# check-java-compile-newengine.ps1 — 与 check-java-compile.ps1 同一套做法，但把
# **刚编译出来的**引擎放在 classpath **最前**（那个脚本借的是 dev 启动的 classpath，里面是旧 jar）。
#
# 为什么需要它：check-java-compile.ps1 借的是 dev 启动的 classpath，里面那份引擎是 game\libs 的
# **旧 jar**；而同步新引擎必须先停掉 dev 会话（notes/195/281）。于是"客户端代码用了引擎新 API"
# 这件事在同步之前根本查不出来 —— 它会给一个假的"找不到符号"，或者更坏：给假绿灯。
# 本脚本不动 build\、不动 game\libs，只是把 mmtr\engine\build\libs 里最新那份 shadow jar 顶到最前面。
#
# 用法：pwsh -File mmtr\scripts\check-java-compile-newengine.ps1 [-Quiet]
param(
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fabric = Join-Path $mcRoot 'mmtr\game\fabric'
$sourceRoot = Join-Path $fabric 'src\main\java'
$argFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
$engineLibs = Join-Path $mcRoot 'mmtr\engine\build\libs'
# **必须用 shadow jar**，不能用 classes 目录：classes 目录里是未重定位的 fastutil（it.unimi.dsi…），
# 而游戏侧用的是重定位后的 org.mtr.libraries.it.unimi.dsi… —— 把 classes 目录顶到最前面会凭空产生
# 一百多条"类型不匹配"（实测 119 条），把真正的错淹掉。
$engineJar = Get-ChildItem $engineLibs -Filter 'Transport-Simulation-Core-*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*sources*' } |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
$outDir = Join-Path $mcRoot 'sandbox\javac-out-newengine'

if (-not (Test-Path $argFile)) {
  Write-Output "[check-java-compile-newengine] no $argFile - run the client once or use gradlew :fabric:compileJava while nothing is running"
  exit 2
}
if (-not $engineJar) {
  Write-Output "[check-java-compile-newengine] no shadow jar in $engineLibs - run (cd mmtr\engine; .\gradlew.bat shadowJar) first"
  exit 2
}

$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
if (-not (Test-Path $javac)) {
  Write-Output '[check-java-compile-newengine] javac not found'
  exit 2
}

$classpath = ((Get-Content $argFile)[1] -replace '" "', ' ').Trim('"')
$jsr305 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.findbugs" -Recurse -Filter 'jsr305-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jsr305) { $classpath = "$classpath;$($jsr305.FullName)" }
# 关键一行：新引擎 shadow jar 顶到最前（javac 先命中它，而不是 game\libs 里的旧 jar）
$classpath = "$($engineJar.FullName);$classpath"
Write-Output "[check-java-compile-newengine] 引擎（新）= $($engineJar.FullName)  $($engineJar.LastWriteTime)"

$compileOnlyIntegrations = @('JadeConfig.java', 'WthitConfig.java')
$files = Get-ChildItem $sourceRoot -Recurse -Filter '*.java' |
  Where-Object { $compileOnlyIntegrations -notcontains $_.Name } |
  Select-Object -ExpandProperty FullName

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $outDir | Out-Null
$responseFile = Join-Path $mcRoot 'sandbox\javac-sources-newengine.txt'
$files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $responseFile -Encoding UTF8

& $javac -proc:none -nowarn -encoding UTF-8 -d $outDir -cp $classpath "@$responseFile" 2>&1 |
  ForEach-Object { if (-not $Quiet) { Write-Output $_ } }
$exit = $LASTEXITCODE
$classes = (Get-ChildItem $outDir -Recurse -Filter '*.class' -ErrorAction SilentlyContinue).Count
if ($exit -eq 0) {
  Write-Output "[check-java-compile-newengine] OK - $($files.Count) source file(s), $classes class file(s) -> $outDir (classpath = 新引擎 shadow jar + 旧 jar 环境)"
} else {
  Write-Output "[check-java-compile-newengine] FAILED (javac exit $exit)"
  # ★ 这条提示是给"引擎加了新 API、游戏侧却报找不到符号"的人看的：本脚本顶到最前的是
  #   `mmtr\engine\build\libs` 里的 **shadow jar**，它**不会**因为改源码而自动更新 ——
  #   引擎改完必须先跑一次 `shadowJar`，否则这里报的是**假错误**（实测：引擎的
  #   `Simulator.mmtrDuties` 明明已经在 classes 里，本脚本仍然报"找不到符号"）。
  Write-Output "[check-java-compile-newengine] 提示：本脚本用的是 build\libs 的 shadow jar；引擎源码改过之后要先 (cd mmtr\engine; .\gradlew.bat shadowJar)"
}
exit $exit
