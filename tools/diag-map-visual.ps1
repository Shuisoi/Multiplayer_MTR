# 画面核对：地图上真的画出了什么（用可测的数字，不靠看图）
param([int]$Port = 9556)
$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-visual-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1600,1000",
	"http://127.0.0.1:8888/index.html"
)
$script:n = 1
$script:ws = $null
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
try {
	Connect
	Write-Output "=== 视口内可见性（越界=取景没算进去）==="
	Write-Output (Eval @'
(() => {
  const map = document.querySelector('.map').getBoundingClientRect();
  const inside = sel => [...document.querySelectorAll(sel)].filter(e => {
    const b = e.getBoundingClientRect();
    return b.left >= map.left - 1 && b.right <= map.right + 1 && b.top >= map.top - 1 && b.bottom <= map.bottom + 1;
  }).length;
  const total = sel => document.querySelectorAll(sel).length;
  const dots = [...document.querySelectorAll('.node .dot')].map(d => d.getBoundingClientRect());
  const sizes = {};
  dots.forEach(b => { const k = Math.round(b.width); sizes[k] = (sizes[k] || 0) + 1; });
  return JSON.stringify({
    viewport: {w: Math.round(map.width), h: Math.round(map.height)},
    nodes: {total: total('.nodes .node'), inside: inside('.nodes .node')},
    rails: {total: total('.rails .rail'), inside: inside('.rails .rail')},
    signals: {total: total('.signals .signal'), inside: inside('.signals .signal')},
    points: {total: total('.map .points .point'), inside: inside('.map .points .point')},
    dotSizes: sizes,
  }, null, 1);
})()
'@)
	Write-Output ""
	Write-Output "=== 灯的状态颜色分布（画出来的）==="
	Write-Output (Eval @'
(() => {
  const byColor = {};
  for (const l of document.querySelectorAll('.signals .signal .lamp')) {
    const c = getComputedStyle(l).backgroundColor;
    byColor[c] = (byColor[c] || 0) + 1;
  }
  const arrows = document.querySelectorAll('.signals .signal .arrow').length;
  return JSON.stringify({lamps: document.querySelectorAll('.signals .signal .lamp').length, byColor, arrows});
})()
'@)
	Write-Output ""
	Write-Output "=== 道岔菱形（画出来的）==="
	Write-Output (Eval @'
(() => {
  const points = [...document.querySelectorAll('.map .points .point')];
  const withNumber = points.filter(p => /^\d+$/.test((p.querySelector('.leg-number') || {}).textContent || '')).length;
  const filled = points.filter(p => !p.classList.contains('is-default')).length;
  const mins = points.map(p => { const b = p.querySelector('.hit').getBoundingClientRect(); return Math.round(Math.min(b.width, b.height)); });
  const sizes = {}; mins.forEach(s => sizes[s] = (sizes[s] || 0) + 1);
  return JSON.stringify({total: points.length, withNumber, filled, hitSizes: sizes});
})()
'@)
	Write-Output ""
	Write-Output "=== 取景是否覆盖全部内容（内容框 vs 视口）==="
	Write-Output (Eval @'
(() => {
  const v = window.__mmtrView ? window.__mmtrView() : null;
  if (!v) return 'no __mmtrView';
  return JSON.stringify({zoom: Math.round(v.zoom * 1000) / 1000, scale: Math.round(v.camera.scale * 10000) / 10000, fit: v.lastFit ? {content: v.lastFit.content, viewport: v.lastFit.viewport} : null}, null, 1);
})()
'@)
	Write-Output ""
	Write-Output "=== 那 13 盏未接入的灯（灰的）长什么样 ==="
	Write-Output (Eval @'
(async () => {
  const s = (await (await fetch('/mtr/api/map/mmtr-signals')).json()).data.signals;
  const unknown = s.filter(x => !x.aspect);
  return '未接入 ' + unknown.length + ' 盏: ' + unknown.slice(0, 6).map(x => x.key + '(候选' + (x.candidateRails||[]).length + ')').join('  ');
})()
'@)
} finally {
	try { $script:ws?.Dispose() } catch { }
	try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
	try { Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}
