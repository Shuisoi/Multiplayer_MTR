# Sync engine shadow jar into game/libs (upstream-style integration; Gradle versions differ so no composite build)
$ErrorActionPreference = 'Stop'
$mmtr = Split-Path -Parent $PSScriptRoot
$tools = Join-Path (Split-Path $mmtr -Parent) 'tools'
$jh = Get-ChildItem $tools -Directory | Where-Object { $_.Name -like 'jdk-21*' } | Select-Object -First 1
if (-not $jh) { throw 'JDK 21 not found under tools/' }
$env:JAVA_HOME = $jh.FullName
$env:Path = (Join-Path $jh.FullName 'bin') + ';' + $env:Path
$log = Join-Path $env:TEMP 'engine-shadow.log'
& (Join-Path $mmtr 'engine\gradlew.bat') -p (Join-Path $mmtr 'engine') shadowJar --console=plain --no-daemon *> $log
if ($LASTEXITCODE -ne 0) { Get-Content $log | Select-Object -Last 40; throw 'engine shadowJar failed' }
$jar = Get-ChildItem (Join-Path $mmtr 'engine\build\libs') -Filter 'Transport-Simulation-Core-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
Copy-Item -Force $jar.FullName (Join-Path $mmtr 'game\libs\Transport-Simulation-Core-0.0.1.jar')
Write-Output ('synced: ' + $jar.Name + ' -> game/libs/Transport-Simulation-Core-0.0.1.jar (' + $jar.Length + ' bytes)')
