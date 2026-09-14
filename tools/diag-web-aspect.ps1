# 网页信号灯显示核对：页面上每个灯点渲染出来的颜色 vs 引擎对该盏灯的判定
param([int]$Port = 9560)
$ErrorActionPreference = "Stop"
# 工作区路径单一真源（脚本里不得写用户目录字面路径，由 mmtr\scripts\check-paths.ps1 强制）
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-webaspect-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
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
	# 页面上每个灯：坐标 / 渲染出来的颜色 / 引擎说的状态文本
	$rows = Eval @'
(() => {
  const out = [];
  for (const el of document.querySelectorAll('.signals .signal')) {
    const lamp = el.querySelector('.lamp');
    const color = lamp ? getComputedStyle(lamp).backgroundColor : '';
    const card = el.querySelector('.card');
    el.dispatchEvent(new PointerEvent('pointerenter', {bubbles: false}));
    out.push({key: el.dataset.key || '', color});
  }
  return JSON.stringify(out);
})()
'@ | ConvertFrom-Json
	$engine = (Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-signals" -TimeoutSec 25).data.signals
	$engineByKey = @{}
	foreach ($s in $engine) { $engineByKey[$s.key] = $s }

	# 颜色 → 引擎状态名
	$colorToAspect = @{
		"rgb(239, 68, 68)"  = "RED"
		"rgb(245, 158, 11)" = "SINGLE_YELLOW"
		"rgb(234, 179, 8)"  = "DOUBLE_YELLOW"
		"rgb(34, 197, 94)"  = "GREEN"
		"rgb(107, 114, 128)" = "（未接入）"
	}
	$bad = 0
	Write-Output "--- 页面颜色 与 引擎判定 不一致的灯 ---"
	foreach ($r in $rows) {
		$e = $engineByKey[$r.key]
		if ($null -eq $e) { continue }
		$engineAspect = if ([string]::IsNullOrEmpty($e.aspect)) { "（未接入）" } else { $e.aspect }
		$pageAspect = if ($colorToAspect.ContainsKey($r.color)) { $colorToAspect[$r.color] } else { $r.color }
		if ($pageAspect -ne $engineAspect) {
			$bad++
			Write-Output ("  灯 {0}  页面={1}  引擎={2}  角={3}" -f $r.key.PadRight(16), $pageAspect.PadRight(14), $engineAspect.PadRight(14), $e.angle)
		}
	}
	Write-Output ("  合计 {0} 盏不一致（页面共 {1} 盏，引擎 {2} 盏）" -f $bad, $rows.Count, @($engine).Count)

	# 颜色分布对照
	Write-Output ""
	Write-Output "--- 页面渲染出来的颜色分布 ---"
	$byColor = $rows | Group-Object color | Sort-Object Count -Descending
	foreach ($g in $byColor) { Write-Output ("  {0}  {1} 盏" -f $g.Name.PadRight(22), $g.Count) }

	# 聚焦到信号灯那一块再截一张
	Eval "(() => { const bs = [...document.querySelectorAll('button.action')]; const b = bs.find(x => x.textContent.includes('看信号灯')); if (b) b.click(); return 1; })()" | Out-Null
	Start-Sleep -Seconds 2
	$shot = Cdp "Page.captureScreenshot" @{ format = "png" }
	# 截图是验证证据，按工作区规矩落到 logs\<yyyy-MM>\，不落进仓库
	$outDir = Join-Path $LOGS (Get-Date -Format 'yyyy-MM')
	New-Item -ItemType Directory -Force -Path $outDir | Out-Null
	$out = Join-Path $outDir 'web-signals.png'
	[System.IO.File]::WriteAllBytes($out, [Convert]::FromBase64String($shot.result.data))
	Write-Output ""
	Write-Output "已截图（聚焦信号灯后）：$out"
} finally {
	try { $script:ws?.Dispose() } catch { }
	try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
	try { Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}
