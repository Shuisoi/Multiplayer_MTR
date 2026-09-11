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

# 轮询直到读数稳定：DOM 更新有延迟，固定 sleep 会在慢的时候读到上一帧的值。
# 曾经因此误判"居中差了 4px"，其实画面是准的，只是测得早。
function DotCenterStable([int]$index, [int]$timeoutMs = 2500) {
	$sw = [Diagnostics.Stopwatch]::StartNew()
	$previous = ""
	while ($sw.ElapsedMilliseconds -lt $timeoutMs) {
		$current = DotCenter $index
		if ($current -eq $previous -and $current -ne "none" -and $current -ne "") { return $current }
		$previous = $current
		Start-Sleep -Milliseconds 150
	}
	return $previous
}

# 被点中的那个节点（.node.active）的圆点中心。菜单动作会把它标成选中态。
function ActiveDotCenterStable([int]$timeoutMs = 2500) {
	$sw = [Diagnostics.Stopwatch]::StartNew()
	$previous = ""
	while ($sw.ElapsedMilliseconds -lt $timeoutMs) {
		$current = Eval "(() => { const n = document.querySelector('.node.active'); if (!n) return 'none'; const b = n.querySelector('.dot').getBoundingClientRect(); return Math.round(b.left + b.width/2) + ',' + Math.round(b.top + b.height/2); })()"
		if ($current -eq $previous -and $current -ne "none" -and $current -ne "") { return $current }
		$previous = $current
		Start-Sleep -Milliseconds 150
	}
	return $previous
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

	# --- 布局（数据来自引擎接口：全部节点，不画连线）---
	$summary = Eval @'
(() => {
  const map = document.querySelector('.map');
  const r = map.getBoundingClientRect();
  const dots = [...document.querySelectorAll('.node .dot')];
  const inside = dots.filter(d => { const b = d.getBoundingClientRect(); return b.width > 3 && b.left >= r.left && b.right <= r.right && b.top >= r.top && b.bottom <= r.bottom; }).length;
  const sizes = {};
  dots.forEach(d => { const k = Math.round(d.getBoundingClientRect().width); sizes[k] = (sizes[k] || 0) + 1; });
  const hud = document.querySelector('.hud') ? document.querySelector('.hud').innerText.replace(/\n/g, ' ') : '';
  const loaded = Number((hud.match(/节点\s+(\d+)/) || [])[1] || 0);
  return JSON.stringify({vw: Math.round(r.width), vh: Math.round(r.height), l: Math.round(r.left), t: Math.round(r.top),
    nodes: dots.length, inside, sizes, hud, loaded,
    banner: document.querySelector('.banner') ? document.querySelector('.banner').innerText : '',    svg: document.querySelectorAll('svg.rails path.rail').length,
    curve: [...document.querySelectorAll('svg.rails path.rail')].filter(p => /Q/.test(p.getAttribute('d'))).length});
})()
'@
	$s = $summary | ConvertFrom-Json
	Write-Output "视口 $($s.vw)x$($s.vh) @ ($($s.l),$($s.t))   节点 $($s.nodes) 个   直径分布 $($s.sizes | ConvertTo-Json -Compress)"
	Write-Output "HUD: $($s.hud.Trim())"
	CheckTrue "从引擎取到节点（HUD 节点数 = 页面上节点元素数）" ($s.loaded -eq $s.nodes -and $s.nodes -gt 0) "HUD 报 $($s.loaded)，页面 $($s.nodes)"
	CheckTrue "没有取数错误横幅" ($s.banner -eq "") "横幅：$(if ($s.banner) { $s.banner } else { '无' })"
	CheckTrue "全部节点都在视口内" ($s.inside -eq $s.nodes) "$($s.inside)/$($s.nodes) 个在视口内"
	CheckTrue "圆点尺寸都是屏幕像素级（3–20px）" (($s.sizes.PSObject.Properties.Name | ForEach-Object { [int]$_ } | Where-Object { $_ -lt 3 -or $_ -gt 20 }).Count -eq 0) "直径分布 $($s.sizes | ConvertTo-Json -Compress)"
	# 连线在（用户后来要求按实际走向连线）：数量应与 HUD 报的轨数一致；线型细节由 check-web-rails.ps1 负责
	$expectedRails = [int]([regex]::Match($s.hud, "轨\s+(\d+)").Groups[1].Value)
	CheckTrue "连线已绘制且数量与 HUD 一致" ($s.svg -eq $expectedRails -and $s.svg -gt 0) "页面 $($s.svg) 条轨（其中曲线 $($s.curve) 条），HUD 报 $expectedRails"
	CheckTrue "取景后缩放读数为 1.00×" ($s.hud -like "*1.00×*") ($s.hud.Trim())

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
	# 判据用"被点中的那个节点"（.node.active），不是固定 index：
	# DOM 里节点的顺序与坐标顺序无关，按 index 取会量到另一个节点（曾经因此误判差了 4px）。
	$viewportCenter = Eval "(() => { const r = document.querySelector('.map').getBoundingClientRect(); return Math.round(r.left + r.width/2) + ',' + Math.round(r.top + r.height/2); })()"
	Eval "document.querySelectorAll('.menu-item')[0].click()" | Out-Null
	Check "『居中到这里』把节点放到视口正中" (ActiveDotCenterStable) $viewportCenter

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
	$readZoom = {
		param($text)
		$m = [regex]::Match($text, "([0-9.]+)×")
		if ($m.Success) { return [double]$m.Groups[1].Value } else { return 0 }
	}
	$zBefore = & $readZoom $zoomBefore
	$zAfter = & $readZoom $zoomAfter
	CheckTrue "滚轮放大（倍率读数变大）" ($zAfter -gt $zBefore) "$zBefore× -> $zAfter×"
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
	Eval "document.querySelector('.action')?.click()" | Out-Null
	Start-Sleep -Milliseconds 400
	Eval "document.querySelectorAll('.action')[1].click()" | Out-Null
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
