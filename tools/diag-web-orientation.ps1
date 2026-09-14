# 一次性诊断：把"世界坐标 ↔ 屏幕坐标"的配对打出来，直接看方向。
param([int]$Port = 9525)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-ordiag-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1400,900",
	"http://127.0.0.1:8888/index.html"
)
$script:n = 1; $script:ws = $null

function Connect {
	for ($i = 0; $i -lt 30; $i++) {
		Start-Sleep -Milliseconds 500
		try {
			$page = (Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 5) | Where-Object { $_.url -like "*index.html*" } | Select-Object -First 1
			if ($page) {
				$script:ws = New-Object System.Net.WebSockets.ClientWebSocket
				$script:ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
				Start-Sleep -Seconds 6
				return
			}
		} catch { }
	}
	throw "无法连接 CDP"
}
function Cdp($method, $params) {
	$id = $script:n; $script:n++
	$payload = @{ id = $id; method = $method; params = $params } | ConvertTo-Json -Depth 10 -Compress
	$bytes = [Text.Encoding]::UTF8.GetBytes($payload)
	$script:ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
	$buffer = New-Object byte[] 8388608
	$cts = New-Object Threading.CancellationTokenSource 15000
	$task = $script:ws.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cts.Token)
	if (-not $task.Wait(15000)) { throw "CDP 超时" }
	return ([Text.Encoding]::UTF8.GetString($buffer, 0, $task.Result.Count) | ConvertFrom-Json)
}
function Eval([string]$e) {
	$r = Cdp "Runtime.evaluate" @{ expression = $e; returnByValue = $true; awaitPromise = $true }
	if ($r.result.exceptionDetails) { throw ("求值异常：" + $r.result.exceptionDetails.text) }
	return $r.result.result.value
}

try {
	Connect
	# 页面用的是什么映射？直接读那个类的行为：拿两盏已知世界坐标的灯，看屏幕位置
	$raw = Eval @'
(async () => {
  const signals = (await (await fetch('/mtr/api/map/mmtr-signals')).json()).data.signals;
  const out = [];
  for (const el of document.querySelectorAll('.signals .signal[data-key]')) {
    const key = el.dataset.key;
    const s = signals.find(item => item.key === key);
    const t = el.style.transform.match(/translate\(([-\d.]+)px,\s*([-\d.]+)px\)/);
    if (s && t) out.push([key, s.x, s.y, s.z, Math.round(parseFloat(t[1])), Math.round(parseFloat(t[2]))]);
  }
  const sig = document.querySelector('.hud') ? (document.querySelector('.hud').innerText.match(/([0-9.]+)×/) || [])[1] : '';
  return JSON.stringify({zoom: sig, rows: out.slice(0, 12), total: out.length});
})()
'@ | ConvertFrom-Json
	Write-Output ("缩放读数：" + $raw.zoom + "；共 " + $raw.total + " 盏")
	Write-Output ""
	Write-Output "  key                   世界 x,y,z            屏幕 x,y"
	foreach ($row in $raw.rows) {
		Write-Output ("  " + $row[0].PadRight(20) + "  (" + $row[1] + "," + $row[2] + "," + $row[3] + ")".PadRight(18) + "  (" + $row[4] + "," + $row[5] + ")")
	}
} catch {
	Write-Output ("出错：" + $_.Exception.Message)
} finally {
	if ($script:ws) { $script:ws.Dispose() }
	taskkill /PID $proc.Id /T /F 2>&1 | Out-Null
}
