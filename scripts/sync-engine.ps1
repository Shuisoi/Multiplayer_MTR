# Sync engine shadow jar into game/libs (upstream-style integration; Gradle versions differ so no composite build)
$ErrorActionPreference = 'Stop'
$mmtr = Split-Path -Parent $PSScriptRoot
# 工作区路径单一真源（env/workspace.env.ps1 导出 $JDK21 并设置 JAVA_HOME）
. (Join-Path (Split-Path $mmtr -Parent) 'env\workspace.env.ps1')
if (-not (Test-Path (Join-Path $JDK21 'bin\java.exe'))) { throw "JDK 21 not found at $JDK21" }
$log = Join-Path $env:TEMP 'engine-shadow.log'
& (Join-Path $mmtr 'engine\gradlew.bat') -p (Join-Path $mmtr 'engine') shadowJar --console=plain --no-daemon *> $log
if ($LASTEXITCODE -ne 0) { Get-Content $log | Select-Object -Last 40; throw 'engine shadowJar failed' }
$jar = Get-ChildItem (Join-Path $mmtr 'engine\build\libs') -Filter 'Transport-Simulation-Core-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
Copy-Item -Force $jar.FullName (Join-Path $mmtr 'game\libs\Transport-Simulation-Core-0.0.1.jar')
Write-Output ('synced: ' + $jar.Name + ' -> game/libs/Transport-Simulation-Core-0.0.1.jar (' + $jar.Length + ' bytes)')
