# run-fabric-tests-offline.ps1 — 在沙箱里编译并运行 fabric 侧的 JUnit 用例，**不碰 build\**。
#
# 为什么需要它（notes/195/281）：dev 会话活着时 `gradlew :fabric:test` 会把类写进
# build\classes\java\main，而那个目录在活进程的 classpath 上 —— 会把新类塞给正在玩的客户端。
# 本脚本沿用 check-java-compile 的做法（javac + loom 的 argfile classpath），把类写进 sandbox\，
# 再用 JUnit 的 Launcher API 跑（mmtr\scripts\lib\FabricTestRunner.java）。
#
# ★ 所有 javac/java 调用都通过 **JDK 的 @argfile** 传参（2026-10-01 实测）：
#   loom 的 classpath 一项就有 31,357 字符（真实数字，见下面 [fabric-tests] 那行），
#   而 Windows 的命令行上限是 32,767 —— 把 `-cp <31k>` 直接写在命令行上，进程**根本起不来**，
#   报的是"文件名或扩展名太长"（像是路径问题，其实是命令行长度）。于是命令行上只剩 `@文件`。
#   @argfile 的引号规则：引号只用来包住空白，**反斜杠在引号内是转义**（Gradle 自己也是这么写的），
#   所以路径里的空格写成 `Shuisoi" "DEV`，绝不能把整条路径塞进一对引号里。
#
# 引擎：默认用 mmtr\engine\build\libs 里**最新**的 shadow jar（新引擎 API 才看得见）；
# 加 -OldEngine 可以退回 game\libs 的 jar（用来确认"对旧引擎也能编译"）。
#
# 用法：
#   pwsh -File mmtr\scripts\run-fabric-tests-offline.ps1                                  # 全部用例
#   pwsh -File mmtr\scripts\run-fabric-tests-offline.ps1 -Tests org.mtr.mod.client.MmtrClientRoutesTests
param(
  [string[]]$Tests = @(),
  [switch]$OldEngine,
  [switch]$KeepQuiet
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fabric = Join-Path $mcRoot 'mmtr\game\fabric'
$sourceRoot = Join-Path $fabric 'src\main\java'
$testRoot = Join-Path $fabric 'src\test\java'
$argFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
$mainOut = Join-Path $mcRoot 'sandbox\javac-out-tests\main'
$testOut = Join-Path $mcRoot 'sandbox\javac-out-tests\test'
$runnerOut = Join-Path $mcRoot 'sandbox\javac-out-tests\runner'
$runnerSource = Join-Path $PSScriptRoot 'lib\FabricTestRunner.java'

if (-not (Test-Path $argFile)) {
  Write-Output "[fabric-tests] no $argFile - 先跑过一次 runClient（或停机后 :fabric:compileJava）"
  exit 2
}

$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
$java = Join-Path $mcRoot 'env\jdk-21\bin\java.exe'

# 引擎：新版优先（shadow jar 是**重定位过**的，与游戏侧一致；classes 目录不是，会造出一堆假错）
$engineJar = Join-Path $mcRoot 'mmtr\game\libs\Transport-Simulation-Core-0.0.1.jar'
$newEngine = Get-ChildItem (Join-Path $mcRoot 'mmtr\engine\build\libs') -Filter 'Transport-Simulation-Core-*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notlike '*sources*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $OldEngine -and $newEngine) { $engineJar = $newEngine.FullName }
Write-Output "[fabric-tests] 引擎 = $engineJar"

$classpath = ((Get-Content $argFile)[1] -replace '" "', ' ').Trim('"')
$jsr305 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.findbugs" -Recurse -Filter 'jsr305-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
$junitJars = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1" -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -match '^(junit-jupiter|junit-platform|opentest4j|apiguardian)' -and $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
  Select-Object -ExpandProperty FullName
# 固定一组互相匹配的版本（5.14.4 / 1.14.4），避免缓存里两代 jar 混用
$junitJars = $junitJars | Where-Object { $_ -match '(5\.14\.4|1\.14\.4|opentest4j-1\.3\.0|apiguardian-api-1\.1\.2)' }
Write-Output "[fabric-tests] junit jar 数 = $($junitJars.Count)"

$fullClasspath = (@($engineJar) + @($classpath) + @($junitJars) + @($jsr305.FullName)) -join ';'
Write-Output "[fabric-tests] classpath 字符数 = $($fullClasspath.Length)（命令行上限 32767 ⇒ 全部走 @argfile）"

Remove-Item (Join-Path $mcRoot 'sandbox\javac-out-tests') -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $mainOut, $testOut, $runnerOut | Out-Null

# 路径 → @argfile 里的写法：空格要引起来（反斜杠留在引号外才是字面量）
function Q([string]$value) { return ($value -replace ' ', '" "') }
function Write-ArgFile([string]$path, [string[]]$items) {
  Set-Content -LiteralPath $path -Value $items -Encoding utf8NoBOM
}

$compileOnlyIntegrations = @('JadeConfig.java', 'WthitConfig.java')
$mainFiles = Get-ChildItem $sourceRoot -Recurse -Filter '*.java' | Where-Object { $compileOnlyIntegrations -notcontains $_.Name } | Select-Object -ExpandProperty FullName
$mainArgFile = Join-Path $mcRoot 'sandbox\javac-args-tests-main.txt'
Write-ArgFile $mainArgFile (@('-proc:none', '-nowarn', '-encoding', 'UTF-8', '-d', (Q $mainOut), '-cp', (Q $fullClasspath)) + ($mainFiles | ForEach-Object { Q ($_ -replace '\\', '/') }))
& $javac "@$mainArgFile" 2>&1 | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { Write-Output '[fabric-tests] 主源码编译失败'; exit 1 }

$testFiles = Get-ChildItem $testRoot -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$testArgFile = Join-Path $mcRoot 'sandbox\javac-args-tests-test.txt'
Write-ArgFile $testArgFile (@('-proc:none', '-nowarn', '-encoding', 'UTF-8', '-d', (Q $testOut), '-cp', (Q "$mainOut;$fullClasspath")) + ($testFiles | ForEach-Object { Q ($_ -replace '\\', '/') }))
& $javac "@$testArgFile" 2>&1 | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { Write-Output '[fabric-tests] 用例源码编译失败'; exit 1 }
Write-Output "[fabric-tests] 编译 OK：主 $($mainFiles.Count) 个文件 / 用例 $($testFiles.Count) 个文件"

& $javac -proc:none -nowarn -encoding UTF-8 -d $runnerOut -cp (@($junitJars) -join ';') $runnerSource 2>&1 | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) { Write-Output '[fabric-tests] runner 编译失败'; exit 1 }

if ($Tests.Count -eq 0) {
  # 默认跑全部用例类：按文件名推出全限定类名
  $Tests = $testFiles | ForEach-Object {
    $rel = $_.Substring($testRoot.Length).TrimStart('\', '/')
    ($rel -replace '\\', '.') -replace '\.java$', ''
  }
}
Write-Output "[fabric-tests] 跑 $($Tests.Count) 个用例类"
$runArgFile = Join-Path $mcRoot 'sandbox\java-args-tests-run.txt'
Write-ArgFile $runArgFile (@('-Dstdout.encoding=UTF-8', '-cp', (Q "$runnerOut;$testOut;$mainOut;$fullClasspath"), 'FabricTestRunner') + $Tests)
# 工作目录必须是 game\fabric（gradle 的 test 任务就在那里跑）：有两条用例按**相对路径**读
# src\main\resources\...，在仓库根目录跑会 NoSuchFileException —— 那是 runner 的锅，不是代码的。
Push-Location $fabric
try {
  & $java "@$runArgFile"
  $exit = $LASTEXITCODE
} finally {
  Pop-Location
}
exit $exit
