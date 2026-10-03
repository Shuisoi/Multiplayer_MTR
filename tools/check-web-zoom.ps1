# 锚点缩放专项验证：连续滚轮放大/缩小，检查"光标下的世界点"是否保持不动。
# 判据用世界坐标（不是屏幕像素）：锚点在缩放前后映射回屏幕应当落回同一个像素（±1px）。
param([int]$Port = 9330)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-zoom-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1280,700",
	"http://127.0.0.1:8888/index.html?cameraDebug=1"
)
$script:n = 1
$script:ws = $null
$failed = 0

function Connect {
	for ($i = 0; $i -lt 30; $i++) {
		Start-Sleep -Milliseconds 500
		try {
			$page = (Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 5) | Where-Object { $_.url -like "*cameraDebug*" } | Select-Object -First 1
			if ($page) {
				$script:ws = New-Object System.Net.WebSockets.ClientWebSocket
				$script:ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
				Start-Sleep -Seconds 3
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
	$cts = New-Object Threading.CancellationTokenSource 8000
	$task = $script:ws.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cts.Token)
	if (-not $task.Wait(8000)) { return @{ error = "超时" } }
	return ([Text.Encoding]::UTF8.GetString($buffer, 0, $task.Result.Count) | ConvertFrom-Json)
}

function Eval([string]$e) {
	$r = Cdp "Runtime.evaluate" @{ expression = $e; returnByValue = $true }
	if ($r.result.exceptionDetails) { return "（异常）" }
	return $r.result.result.value
}

function Wheel([int]$x, [int]$y, [int]$deltaY) {
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseWheel"; x = $x; y = $y; deltaX = 0; deltaY = $deltaY; buttons = 0 } | Out-Null
	Start-Sleep -Milliseconds 350
}

try {
	Connect
	Write-Output "=== 锚点缩放验证（世界坐标判据）==="
	Write-Output ""

	# 三处光标位置：节点上、空白处、靠边处
	$points = @(@(604, 274), @(400, 150), @(1000, 500))
	foreach ($point in $points) {
		$x = $point[0]; $y = $point[1]
		Eval "window.__zooms = []" | Out-Null
		# 放大两次、缩小一次，锚点每次都必须回落到同一像素
		Wheel $x $y -300
		Wheel $x $y -300
		Wheel $x $y 300
		$log = Eval "JSON.stringify(window.__zooms)"
		$zooms = $log | ConvertFrom-Json
		$errors = @()
		foreach ($z in $zooms) {
			$dx = [Math]::Abs($z.anchorScreenAfter.x - $z.screenX)
			$dy = [Math]::Abs($z.anchorScreenAfter.y - $z.screenY)
			$errors += [Math]::Round([Math]::Max($dx, $dy), 3)
		}
		$worst = ($errors | Measure-Object -Maximum).Maximum
		$ok = $worst -le 1.0
		if (-not $ok) { $script:failed++ }
		$scales = ($zooms | ForEach-Object { $_.beforeScale.ToString("0.00") + "→" + $_.afterScale.ToString("0.00") }) -join "  "
		Write-Output ("[{0}] 光标 ({1},{2})  锚点最大偏移 {3}px   比例 {4}" -f $(if ($ok) { "通过" } else { "失败" }), $x, $y, $worst, $scales)
	}

	Write-Output ""
	Write-Output ("=== {0} ===" -f $(if ($failed -eq 0) { "全部通过" } else { "$failed 条失败" }))
} catch {
	Write-Output ("出错：" + $_.Exception.Message)
	$failed++
} finally {
	if ($script:ws) { $script:ws.Dispose() }
	taskkill /PID $proc.Id /T /F 2>&1 | Out-Null
}
exit $failed
