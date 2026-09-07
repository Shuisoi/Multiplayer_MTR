# Dev game client launcher (loom runClient, logs to fabric/run/dev-client.out.log).
$ErrorActionPreference = 'Continue'
$mmtr = Split-Path -Parent $PSScriptRoot
$game = Join-Path $mmtr 'game'
$log = Join-Path $mmtr 'game\fabric\run\dev-client.out.log'
Set-Location $game
Remove-Item $log -ErrorAction SilentlyContinue
& .\gradlew.bat :fabric:runClient *> $log
