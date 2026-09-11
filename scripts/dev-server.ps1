# Dev dedicated server launcher (loom runServer, logs to fabric/run/dev-server.out.log).
#
# 前后端分离：dev 服务端默认**从磁盘**发 web 控制台（engine/website/dist/website/browser），
# 而不是发 jar 内嵌的那份。于是改前端只需要：
#     cd engine\website; npm run build      # 然后刷新浏览器
# 不必重打 jar、也不必重启这个服务端。
# 想要回退到 jar 内嵌的版本，把 MMTR_WEB_ROOT 置空即可（例如 $env:MMTR_WEB_ROOT = ''）。
$ErrorActionPreference = 'Continue'
$mmtr = Split-Path -Parent $PSScriptRoot
$game = Join-Path $mmtr 'game'
$log = Join-Path $mmtr 'game\fabric\run\dev-server.out.log'

$webRoot = Join-Path $mmtr 'engine\website\dist\website\browser'
if (Test-Path (Join-Path $webRoot 'index.html')) {
	$env:MMTR_WEB_ROOT = $webRoot
	Write-Output "web console will be served from disk: $webRoot"
} else {
	Write-Output "WARNING: $webRoot\index.html not found; falling back to the jar-embedded console"
	Remove-Item Env:\MMTR_WEB_ROOT -ErrorAction SilentlyContinue
}

Set-Location $game
Remove-Item $log -ErrorAction SilentlyContinue
& .\gradlew.bat :fabric:runServer *> $log
