# Sync engine shadow jar into game/libs (upstream-style integration; Gradle versions differ so no composite build)
#
# ⚠️ 不要在 dev server / client 运行中同步（notes/216）：Fabric 对 game/libs 里的 jar **懒加载**，
# 进程起来时抓的是那个文件的句柄；覆盖之后它下一次读新类就会
# `ZipException: ZipFile invalid LOC header (bad signature)` —— 现场表现是"能上驾驶室、车一动不动"，
# 而且服务端每个驾驶包都在 PacketDriveControl.runServer 里抛异常（静默失败，最难查的一类）。
# 所以本脚本默认**检测到 loom 开发进程在跑就拒绝**；确实要换 jar 就先停服，或用 -Force 明确承担风险。
param(
  [switch]$Force
)
$ErrorActionPreference = 'Stop'
$mmtr = Split-Path -Parent $PSScriptRoot
# 工作区路径单一真源（env/workspace.env.ps1 导出 $JDK21 并设置 JAVA_HOME）
. (Join-Path (Split-Path $mmtr -Parent) 'env\workspace.env.ps1')
if (-not (Test-Path (Join-Path $JDK21 'bin\java.exe'))) { throw "JDK 21 not found at $JDK21" }

if (-not $Force) {
  $running = Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -match 'devlaunchinjector\.Main' }
  if ($running) {
    $list = ($running | ForEach-Object { "PID $($_.ProcessId)（起于 $($_.CreationDate)）" }) -join '、'
    throw ("拒绝同步：还有 loom 开发进程在跑（$list）。运行中换 game/libs 的 jar 会让它读到坏 zip，" +
      "现场表现是'能进驾驶室但车不动'（notes/216）。请先停掉 dev server/client，或显式 -Force。")
  }
}

$log = Join-Path $env:TEMP 'engine-shadow.log'
& (Join-Path $mmtr 'engine\gradlew.bat') -p (Join-Path $mmtr 'engine') shadowJar --console=plain --no-daemon *> $log
if ($LASTEXITCODE -ne 0) { Get-Content $log | Select-Object -Last 40; throw 'engine shadowJar failed' }
$jar = Get-ChildItem (Join-Path $mmtr 'engine\build\libs') -Filter 'Transport-Simulation-Core-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
Copy-Item -Force $jar.FullName (Join-Path $mmtr 'game\libs\Transport-Simulation-Core-0.0.1.jar')
Write-Output ('synced: ' + $jar.Name + ' -> game/libs/Transport-Simulation-Core-0.0.1.jar (' + $jar.Length + ' bytes)')
Write-Output '提示：服务端/客户端需要重启才会用上新 jar。'
