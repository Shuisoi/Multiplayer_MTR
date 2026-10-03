# check-java-compile.ps1 — typecheck the Fabric sources WITHOUT disturbing a running dev session.
#
# Why this exists (notes/195): under loom, `runClient` / `runServer` put `build/classes/java/main` on the
# classpath of a LIVE process, and the JVM loads classes lazily. So `gradlew :fabric:compileJava` while the
# user is in game can hand a running session a new class whose collaborators do not exist yet
# (`NoSuchMethodError` in the middle of play). Gradle also contends on the project lock that runServer's
# wrapper holds.
#
# This script compiles the same sources with plain javac, against the classpath the dev launch itself uses
# (loom writes it to build/loom-cache/argFiles/runClient), and writes the classes to a scratch directory.
# Nothing in build/ is touched, so it is safe to run at any time.
#
# Usage:
#   pwsh -File mmtr\scripts\check-java-compile.ps1                 # whole src/main/java
#   pwsh -File mmtr\scripts\check-java-compile.ps1 -Files a.java,b.java
#   pwsh -File mmtr\scripts\check-java-compile.ps1 -Quiet          # only the exit code + totals
#
# Exit code: 0 = compiled, 1 = compile errors, 2 = could not set up (no argfile / no javac).
param(
  [string[]]$Files = @(),
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fabric = Join-Path $mcRoot 'mmtr\game\fabric'
$sourceRoot = Join-Path $fabric 'src\main\java'
$argFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
$outDir = Join-Path $mcRoot 'sandbox\javac-out'

if (-not (Test-Path $argFile)) {
  Write-Output "[check-java-compile] no $argFile - run the client once (gradlew :fabric:runClient) or use gradlew :fabric:compileJava while nothing is running"
  exit 2
}

# javac: the workspace JDK first (the same one the game runs on), then whatever Gradle downloaded.
$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
if (-not (Test-Path $javac)) {
  $javac = (Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}
if (-not $javac) { Write-Output '[check-java-compile] javac not found'; exit 2 }

# The argfile holds the classpath on its second line, with the spaces in the workspace path quoted as `" "`.
$classpath = ((Get-Content $argFile)[1] -replace '" "', ' ').Trim('"')
# javax.annotation is a COMPILE-only dependency, so it is not on the runtime classpath.
$jsr305 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.findbugs" -Recurse -Filter 'jsr305-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jsr305) { $classpath = "$classpath;$($jsr305.FullName)" }

# `game/libs` 那份引擎 jar 是 `sync-engine.ps1` 同步过来的**副本**：忘了同步，这个检查就会给假绿灯。
# 实测 2026-09-23：删掉 `Siding.ACCELERATION_DEFAULT` 之后这里报 OK（对的是旧 jar），
# 直到 `runServer` 真去编译才炸在 `LegacyVehicleSound` 上 —— 所以这里**点名警告**：
# 引擎 classes 比 jar 新 ⇒ 本次结论只对旧引擎成立（同步必须停掉 dev 会话才能做，所以不能直接判失败）。
$engineClasses = Join-Path $mcRoot 'mmtr\engine\build\classes\java\main'
$engineJar = Join-Path $mcRoot 'mmtr\game\libs\Transport-Simulation-Core-0.0.1.jar'
if ((Test-Path $engineClasses) -and (Test-Path $engineJar)) {
  $newestClass = Get-ChildItem $engineClasses -Recurse -Filter '*.class' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($newestClass -and (Get-Item $engineJar).LastWriteTime -lt $newestClass.LastWriteTime) {
    Write-Output ('[check-java-compile] ⚠ 警告：game/libs 的引擎 jar（' + (Get-Item $engineJar).LastWriteTime + '）比引擎 classes（' + $newestClass.LastWriteTime + '）旧 —— 本次类型检查对的是**旧引擎**，引擎 API 的改动查不出来（先跑 mmtr\scripts\sync-engine.ps1，需要先停掉 dev 会话）。')
  }
}

if ($Files.Count -eq 0) {
  # A handful of integration files import mod APIs that are compileOnly (not on the runtime classpath that
  # this check borrows), so plain javac cannot see them. They are listed rather than silently skipped: if
  # one of them starts failing for a REAL reason, the Gradle build is still the gate that catches it.
  $compileOnlyIntegrations = @('JadeConfig.java', 'WthitConfig.java')
  $Files = Get-ChildItem $sourceRoot -Recurse -Filter '*.java' |
    Where-Object { $compileOnlyIntegrations -notcontains $_.Name } |
    Select-Object -ExpandProperty FullName
}

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $outDir | Out-Null

# -proc:none: the jar set declares a log4j annotation processor that plain javac cannot resolve, and this
# check is about the CODE compiling, not about running processors.
# Two traps in an @argfile, both silent-ish: it is split on whitespace (so the workspace path, which
# contains a space, must be quoted) and BACKSLASH IS AN ESCAPE CHARACTER inside it (so quoted Windows
# paths must use forward slashes or javac eats the separators).
$responseFile = Join-Path $mcRoot 'sandbox\javac-sources.txt'
$Files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $responseFile -Encoding UTF8
& $javac -proc:none -nowarn -encoding UTF-8 -d $outDir -cp $classpath "@$responseFile" 2>&1 | ForEach-Object { $line = $_; if (-not $Quiet) { Write-Output $line } }
$exit = $LASTEXITCODE

$classes = (Get-ChildItem $outDir -Recurse -Filter '*.class' -ErrorAction SilentlyContinue).Count

# 残树告警（notes/281）：本脚本把类编译到 $outDir，**不碰 build/classes**，所以它给的这个绿
# 只证明"源码彼此自洽"。实测 2026-09-25：build/classes/java/main 只有 238 个 class（应有 664）、
# org/mtr/mixin/ 整个不存在，而 gradle 的 `:fabric:compileJava` 报 **UP-TO-DATE** ——
# 于是 `runServer` 起手就崩在 `InvalidMixinException: ... was not found`（mixin 是启动时按类名
# 从 classpath 加载的）。那个绿对"游戏能不能起来"没有任何发言权，这里把差异**点名说出来**。
#
# 判据是"**每个源文件都有对应的 class**"，不是"class 总数差不多"：整个 org/mtr/mixin/（6 个类）
# 消失只占 664 的 0.9%，按比例怎么设阈值都看不见 —— 而崩掉启动的恰恰就是这 6 个。
# 只警告、不改退出码：这个脚本要能在 dev 会话运行时随时跑，不能因为别人会话的输出目录不完整就判失败。
if ($exit -eq 0) {
  $buildClasses = Join-Path $fabric 'build\classes\java\main'
  if (Test-Path $buildClasses) {
    $missing = @()
    foreach ($file in $Files) {
      $relative = $file.Substring($sourceRoot.Length).TrimStart('\', '/')
      $expected = Join-Path $buildClasses ([System.IO.Path]::ChangeExtension($relative, '.class'))
      if (-not (Test-Path $expected)) { $missing += $relative }
    }
    if ($missing.Count -gt 0) {
      Write-Output ('[check-java-compile] ⚠ 警告：build/classes/java/main **缺 ' + $missing.Count + ' 个源文件的 class**（共检查 ' + $Files.Count + ' 个）—— 输出目录是残的，游戏很可能起不来（mixin/类缺失）。前几个缺的：')
      $missing | Select-Object -First 8 | ForEach-Object { Write-Output ('      ' + $_) }
      Write-Output ('    修法：Remove-Item -Recurse -Force "' + $buildClasses + '"; cd mmtr\game; .\gradlew.bat :fabric:compileJava --console=plain --no-daemon')
      Write-Output '    注意 gradle 可能仍报 UP-TO-DATE（增量状态与磁盘不一致）—— 删掉输出目录后它才会真编译。详见 notes/281。'
    }
  }
}

if ($exit -eq 0) {
  Write-Output "[check-java-compile] OK - $($Files.Count) source file(s), $classes class file(s) -> $outDir"
} else {
  Write-Output "[check-java-compile] FAILED (javac exit $exit)"
}
exit $exit
