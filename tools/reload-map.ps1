# 刷新地图并核对：页面渲染的东西与引擎当前的世界一致（刷新后新改的轨/灯/道岔都在）
param([int]$Port = 9555)
$ErrorActionPreference = "Stop"
# 工作区路径单一真源（脚本里不得写用户目录字面路径，由 mmtr\scripts\check-paths.ps1 强制）
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-reload-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1600,1000",
	"http://127.0.0.1:8888/index.html"
)
$script:n = 1
$script:ws = $null
$failed = 0

function Connect {
	for ($i = 0; $i -lt 30; $i++) {
		Start-Sleep -Milliseconds 500
		try {
			$page = (Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 5) | Where-Object { $_.url -like "*index.html*" } | Select-Object -First 1
			if ($page) {
				$script:ws = New-Object System.Net.WebSockets.ClientWebSocket
				$script:ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
				Start-Sleep -Seconds 8
				return
			}
		} catch { }
	}
	throw "无法连接 CDP"
}
function Cdp($method, $params) {
	$id = $script:n; $script:n++
	$payload = @{id = $id; method = $method; params = $params} | ConvertTo-Json -Depth 10 -Compress
	$bytes = [Text.Encoding]::UTF8.GetBytes($payload)
	$script:ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
	$buffer = New-Object byte[] 8388608
	$cts = New-Object Threading.CancellationTokenSource 15000
	$task = $script:ws.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cts.Token)
	if (-not $task.Wait(15000)) { throw "CDP 超时：$method" }
	return ([Text.Encoding]::UTF8.GetString($buffer, 0, $task.Result.Count) | ConvertFrom-Json)
}
function Eval([string]$e) {
	$r = Cdp "Runtime.evaluate" @{expression = $e; returnByValue = $true; awaitPromise = $true}
	if ($r.result.exceptionDetails) { throw ("求值异常：" + $r.result.exceptionDetails.text) }
	return $r.result.result.value
}
function CheckTrue([string]$name, [bool]$condition, [string]$detail) {
	if (-not $condition) { $script:failed++ }
	Write-Output ("[{0}] {1}" -f $(if ($condition) { "通过" } else { "失败" }), $name)
	Write-Output ("       {0}" -f $detail)
}

try {
	Connect
	# 真实刷新：走一次 Reload，等页面把三个 feed 重新取回来
	Cdp "Page.reload" @{ ignoreCache = $true } | Out-Null
	Start-Sleep -Seconds 8
	$ready = $false
	for ($i = 0; $i -lt 20; $i++) {
		$state = Eval "document.querySelectorAll('.nodes .node').length + ',' + document.querySelectorAll('.rails .rail').length"
		if ($state -notlike "0,*") { $ready = $true; break }
		Start-Sleep -Milliseconds 800
	}
	CheckTrue "刷新后页面把数据取回来了" $ready "节点/轨元素数：$state"

	$page = Eval @'
(() => {
  const hud = document.querySelector('.hud') ? document.querySelector('.hud').innerText.replace(/\n/g, ' ') : '';
  return JSON.stringify({
    domNodes: document.querySelectorAll('.nodes .node').length,
    domRails: document.querySelectorAll('.rails .rail').length,
    domSignals: document.querySelectorAll('.signals .signal').length,
    domPoints: document.querySelectorAll('.map .points .point').length,
    domConnected: document.querySelectorAll('.rails .rail.connected').length,
    hud,
    banner: document.querySelector('.banner') ? document.querySelector('.banner').innerText : '',
  });
})()
'@ | ConvertFrom-Json
	$engine = Eval @'
(async () => {
  const t = (await (await fetch('/mtr/api/map/mmtr-topology')).json()).data;
  const s = (await (await fetch('/mtr/api/map/mmtr-signals')).json()).data;
  const p = (await (await fetch('/mtr/api/map/mmtr-points')).json()).data;
  return JSON.stringify({nodes: t.nodes.length, rails: t.rails.length, signals: s.signals.length, points: p.points.length});
})()
'@ | ConvertFrom-Json

	Write-Output ""
	Write-Output "HUD: $($page.hud.Trim())"
	Write-Output "引擎: 节点 $($engine.nodes) 轨 $($engine.rails) 灯 $($engine.signals) 道岔 $($engine.points)"
	Write-Output ""
	CheckTrue "没有取数错误横幅" ($page.banner -eq "") "横幅：$(if ($page.banner) { $page.banner } else { '无' })"
	CheckTrue "节点数与引擎一致（含你新改的轨）" ($page.domNodes -eq $engine.nodes) "页面 $($page.domNodes) vs 引擎 $($engine.nodes)"
	CheckTrue "轨数与引擎一致" ($page.domRails -eq $engine.rails) "页面 $($page.domRails) vs 引擎 $($engine.rails)"
	CheckTrue "灯数与引擎一致" ($page.domSignals -eq $engine.signals) "页面 $($page.domSignals) vs 引擎 $($engine.signals)"
	CheckTrue "道岔数与引擎一致" ($page.domPoints -eq $engine.points) "页面 $($page.domPoints) vs 引擎 $($engine.points)"
	CheckTrue "刷新后没有残留的'联通轨'高亮（没选任何道岔时不该有）" ($page.domConnected -eq 0) "带 connected 的轨 $($page.domConnected) 条"

	$shot = Cdp "Page.captureScreenshot" @{ format = "png" }
	# 截图是验证证据，按工作区规矩落到 logs\<yyyy-MM>\，不落进仓库
	$outDir = Join-Path $LOGS (Get-Date -Format 'yyyy-MM')
	New-Item -ItemType Directory -Force -Path $outDir | Out-Null
	$out = Join-Path $outDir 'map-refresh.png'
	[System.IO.File]::WriteAllBytes($out, [Convert]::FromBase64String($shot.result.data))
	Write-Output ""
	Write-Output "已截图：$out"
} finally {
	try { $script:ws?.Dispose() } catch { }
	try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
	try { Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}
Write-Output ""
if ($failed -eq 0) { Write-Output "全部通过"; exit 0 }
Write-Output "$failed 项失败"; exit 1
