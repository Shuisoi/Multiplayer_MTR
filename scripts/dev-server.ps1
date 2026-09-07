# Dev dedicated server launcher (loom runServer, logs to fabric/run/dev-server.out.log).
$ErrorActionPreference = 'Continue'
$mmtr = Split-Path -Parent $PSScriptRoot
$game = Join-Path $mmtr 'game'
$log = Join-Path $mmtr 'game\fabric\run\dev-server.out.log'
Set-Location $game
Remove-Item $log -ErrorAction SilentlyContinue
& .\gradlew.bat :fabric:runServer *> $log
