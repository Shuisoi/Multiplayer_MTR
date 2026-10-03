# Dev game client launcher (loom runClient, logs to fabric/run/dev-client.out.log).
$ErrorActionPreference = 'Continue'
# 工作区环境单一真源（`JAVA_HOME` 等）—— **必须**在这里取，不能靠机器上的环境变量：
# 2026-09-15 实测，本机用户级 JAVA_HOME 指向 `C:\Program Files\Java\jre1.8.0_431`（JRE 8），
# gradle 直接拒绝启动（"JAVA_HOME is set to an invalid directory"）—— 客户端脚本当时是漏掉的那一个
# （dev-server.ps1 / deploy-engine.ps1 早就补上了，同一个坑的第三处）。
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')
$mmtr = Split-Path -Parent $PSScriptRoot
$game = Join-Path $mmtr 'game'
$log = Join-Path $mmtr 'game\fabric\run\dev-client.out.log'
Set-Location $game
Remove-Item $log -ErrorAction SilentlyContinue
& .\gradlew.bat :fabric:runClient *> $log
