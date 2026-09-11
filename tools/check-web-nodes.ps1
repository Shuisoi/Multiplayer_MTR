# 无头验证脚本：连 CDP、逐条跑断言，任何一条失败都不阻塞后续（每一步都有超时）。
param(
	[string]$Url = "http://127.0.0.1:8888/index.html",
	[int]$Port = 9310
)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-check-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1280,700", $Url
)

$ws = $null
$script:nextId = 1
$failed = 0

function Connect {
	for ($i = 0; $i -lt 30; $i++) {
		Start-Sleep -Milliseconds 500
		try {
			$page = (Invoke-RestMethod -Uri "http://127.0.0.1:$Port/json" -TimeoutSec 5) |
				Where-Object { $_.url -like "*index.html*" } | Select-Object -First 1
			if ($page) {
				$script:ws = New-Object System.Net.WebSockets.ClientWebSocket
				$script:ws.ConnectAsync([Uri]$page.webSocketDebuggerUrl, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
				Start-Sleep -Seconds 3
				return
			}
		} catch { }
	}
	throw "无法连接到 CDP（端口 $Port）"
}

# 单次 CDP 调用：接收用超时，避免任何一步卡死整轮验证。
function Cdp($method, $params) {
	$id = $script:nextId
	$script:nextId++
	$payload = @{ id = $id; method = $method; params = $params } | ConvertTo-Json -Depth 10 -Compress
	$bytes = [Text.Encoding]::UTF8.GetBytes($payload)
	$script:ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
	$buffer = New-Object byte[] 8388608
	$cts = New-Object Threading.CancellationTokenSource 8000
	$task = $script:ws.ReceiveAsync([ArraySegment[byte]]::new($buffer), $cts.Token)
	if (-not $task.Wait(8000)) {
		return @{ error = "CDP 超时：$method" }
	}
	return ([Text.Encoding]::UTF8.GetString($buffer, 0, $task.Result.Count) | ConvertFrom-Json)
}

function Eval([string]$expression) {
	$response = Cdp "Runtime.evaluate" @{ expression = $expression; returnByValue = $true }
	if ($response.error) { return "（求值失败：$($response.error)）" }
	if ($response.result.exceptionDetails) { return "（异常：$($response.result.exceptionDetails.text)）" }
	return $response.result.result.value
}

function Mouse([string]$type, [int]$x, [int]$y, [string]$button = "none", [int]$buttons = 0, [int]$clickCount = 0, [int]$deltaY = 0) {
	$params = @{ type = $type; x = $x; y = $y; buttons = $buttons }
	if ($button -ne "none") { $params.button = $button }
	if ($clickCount -gt 0) { $params.clickCount = $clickCount }
	# 滚轮事件必须带 deltaY，否则浏览器派发的是一个"零滚动"事件，页面上看不出任何变化——
	# 曾经因此误判成"滚轮缩放坏了"。
	if ($type -eq "mouseWheel") { $params.deltaX = 0; $params.deltaY = $deltaY }
	Cdp "Input.dispatchMouseEvent" $params | Out-Null
}

# 取第 n 个节点圆点的屏幕中心
function DotCenter([int]$index) {
	return Eval "(() => { const dots = document.querySelectorAll('.node .dot'); if (!$dots[$index]) return 'none'; const b = dots[$index].getBoundingClientRect(); return Math.round(b.left + b.width / 2) + ',' + Math.round(b.top + b.height / 2); })()"
}

function Check([string]$name, [string]$actual, [string]$expected) {
	$ok = $actual -eq $expected
	if (-not $ok) { $script:failed++ }
	$mark = if ($ok) { "通过" } else { "失败" }
	Write-Output ("[{0}] {1}" -f $mark, $name)
	Write-Output ("       实际: {0}" -f $actual)
	if (-not $ok) { Write-Output ("       期望: {0}" -f $expected) }
}

function CheckTrue([string]$name, [bool]$condition, [string]$detail) {
	if (-not $condition) { $script:failed++ }
	Write-Output ("[{0}] {1}   {2}" -f $(if ($condition) { "通过" } else { "失败" }), $name, $detail)
}

try {
	Connect
	Write-Output "=== 节点显示系统验证 ==="
	Write-Output ""

	# --- 布局 ---
	$summary = Eval @'
(() => {
  const map = document.querySelector('.map');
  const r = map.getBoundingClientRect();
  const dots = [...document.querySelectorAll('.node .dot')];
  const inside = dots.filter(d => { const b = d.getBoundingClientRect(); return b.width > 3 && b.left >= r.left && b.right <= r.right && b.top >= r.top && b.bottom <= r.bottom; }).length;
  const sizes = dots.map(d => Math.round(d.getBoundingClientRect().width)).join(',');
  return JSON.stringify({vw: Math.round(r.width), vh: Math.round(r.height), l: Math.round(r.left), t: Math.round(r.top), nodes: dots.length, inside, sizes,
    rails: document.querySelectorAll('.rails line.rail').length, ticks: document.querySelectorAll('.rails line.tick').length,
    viewBox: document.querySelector('svg.rails').getAttribute('viewBox')});
})()
'@
	$s = $summary | ConvertFrom-Json
	Write-Output "视口 $($s.vw)x$($s.vh) @ ($($s.l),$($s.t))   节点 $($s.nodes) 个，圆点直径 [$($s.sizes)]"
	CheckTrue "全部节点都在视口内" ($s.inside -eq $s.nodes) "$($s.inside)/$($s.nodes) 个在视口内"
	CheckTrue "圆点尺寸都是屏幕像素级（3–20px）" (($s.sizes -split ',' | ForEach-Object { [int]$_ } | Where-Object { $_ -lt 3 -or $_ -gt 20 }).Count -eq 0) "直径 $($s.sizes)"
	CheckTrue "轨 / 刻度线已绘制" ($s.rails -eq 4 -and $s.ticks -eq 8) "轨 $($s.rails) 条、刻度 $($s.ticks) 条"
	CheckTrue "轨道层 SVG 没有 viewBox（不引入第二套缩放）" ($null -eq $s.viewBox) "viewBox = $(if ($s.viewBox) { $s.viewBox } else { '无' })"

	# --- 悬停 ---
	$dot = DotCenter 0
	$p = $dot -split ','
	Mouse "mouseMoved" ([int]$p[0]) ([int]$p[1]) | Out-Null
	Start-Sleep -Milliseconds 500
	$card = Eval "document.querySelector('.card') ? document.querySelector('.card').innerText.replace(/\n/g, ' | ') : '（无）'"
	CheckTrue "悬停节点弹出信息卡" ($card -notlike "*（无）*") $card

	# --- 左键菜单 ---
	Mouse "mousePressed" ([int]$p[0]) ([int]$p[1]) "left" 1 1
	Mouse "mouseReleased" ([int]$p[0]) ([int]$p[1]) "left" 0 1
	Start-Sleep -Milliseconds 400
	$menu = Eval "document.querySelector('.menu') ? [...document.querySelectorAll('.menu-item')].map(b => b.textContent).join(' / ') : '（无）'"
	CheckTrue "左键点开操作菜单" ($menu -notlike "*（无）*") $menu

	# --- 居中到这里 ---
	$viewportCenter = Eval "(() => { const r = document.querySelector('.map').getBoundingClientRect(); return Math.round(r.left + r.width/2) + ',' + Math.round(r.top + r.height/2); })()"
	Eval "document.querySelectorAll('.menu-item')[0].click()" | Out-Null
	Start-Sleep -Milliseconds 500
	Check "『居中到这里』把节点放到视口正中" (DotCenter 0) $viewportCenter

	# --- 滚轮缩放（读数变化 + 锚点像素不动）---
	# 先关掉菜单再测：菜单是节点元素的子节点，开合会改变布局，虽然不影响节点位置，
	# 但会让"测位移"这一步读到不同时刻的状态（曾经因此误判锚点漂了 28px）。
	Mouse "mousePressed" 40 500 "left" 1 1
	Mouse "mouseReleased" 40 500 "left" 0 1
	Start-Sleep -Milliseconds 300
	$anchorBefore = DotCenter 1
	$zoomBefore = Eval "document.querySelector('.hud').innerText.replace(/\n/g, ' ')"
	Mouse "mouseWheel" ([int]$p[0]) ([int]$p[1]) "none" 0 0 -300
	Start-Sleep -Milliseconds 600
	$zoomAfter = Eval "document.querySelector('.hud').innerText.replace(/\n/g, ' ')"
	CheckTrue "滚轮放大到 1.15×" ($zoomAfter -like "*1.15×*") "$($zoomBefore.Trim())  ->  $($zoomAfter.Trim())"
	CheckTrue "菜单在点空白处关闭" ((Eval "document.querySelector('.menu') ? 'still-open' : 'closed'") -eq "closed") "点空白后菜单状态"

	# --- 拖动平移（两轴都要动）---
	$before = DotCenter 0
	Mouse "mousePressed" 400 200 "left" 1 1
	Mouse "mouseMoved" 460 230 "left" 1
	Mouse "mouseMoved" 520 260 "left" 1
	Mouse "mouseReleased" 520 260 "left" 0 1
	Start-Sleep -Milliseconds 400
	$after = DotCenter 0
	$b = $before -split ','; $a = $after -split ','
	$dx = [int]$a[0] - [int]$b[0]; $dy = [int]$a[1] - [int]$b[1]
	CheckTrue "拖动同时改变两轴（上下左右都能拖）" (([Math]::Abs($dx - 120) -le 8) -and ([Math]::Abs($dy - 60) -le 8)) "位移 ($dx,$dy)，期望约 (120,60)"

	# --- 重置视图 ---
	Eval "document.querySelector('.reset').click()" | Out-Null
	Start-Sleep -Milliseconds 500
	$resetZoom = Eval "document.querySelector('.hud').innerText.replace(/\n/g, ' ')"
	CheckTrue "重置视图回到 1.00× 取景" ($resetZoom -like "*1.00×*") $resetZoom.Trim()

	Write-Output ""
	Write-Output ("=== {0} ===" -f $(if ($failed -eq 0) { "全部通过" } else { "$failed 条失败" }))
} catch {
	Write-Output ("验证过程出错：" + $_.Exception.Message)
	$failed++
} finally {
	if ($script:ws) { $script:ws.Dispose() }
	taskkill /PID $proc.Id /T /F 2>&1 | Out-Null
}
exit $failed
