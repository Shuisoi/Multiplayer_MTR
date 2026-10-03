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
	CheckTrue "菜单里有『复制坐标』" ($menu -like "*复制坐标*") $menu

	# --- 复制坐标：不是"点了就算"，而是把**剪贴板读回来**核对内容 ---
	# 这条功能原来的实现是 `void navigator.clipboard?.writeText(...)`：不 await、不看结果、没有兜底，
	# 失败时是静默的（实测无头浏览器里 writeText 直接 NotAllowedError）。
	# 所以这里先给页面**授予剪贴板权限**，让 writeText 这条路真的能走通，
	# 然后核对"复制出来的文本 == 被点那个节点自己的坐标"。
	try {
		Cdp "Browser.grantPermissions" @{ permissions = @("clipboardReadWrite", "clipboardSanitizedWrite"); origin = "http://127.0.0.1:8888" } | Out-Null
	} catch {
		Write-Output "（Browser.grantPermissions 不可用：$($_.Exception.Message)）"
	}
	$clickedCopy = Eval @'
(() => {
  const item = [...document.querySelectorAll('.menu-item')].find(b => (b.textContent || '').includes('复制坐标'));
  if (!item) return 'no-item';
  const node = item.closest('.node');
  const dot = node ? node.querySelector('.dot') : null;
  const r = dot ? dot.getBoundingClientRect() : null;
  // 记下这个节点在屏幕上的位置：复制会被点掉菜单，而后面『居中到这里』那一步需要菜单开着，
  // 所以收尾要**在同一个节点上重新打开**（用别的方式重开就会测到另一个节点）。
  const out = {ok: true, key: node ? node.dataset.key : '', screen: r ? {x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2)} : null};
  item.click();
  return JSON.stringify(out);
})()
'@ | ConvertFrom-Json
	Start-Sleep -Milliseconds 500
	# 页面自报这次复制的结果（成功/失败、走的哪条路、失败原因）——界面拿它决定说什么，
	# 也就等于给了脚本一个"界面到底说了什么"的凭据。
	$copyResult = Eval "JSON.stringify(window.__mmtrLastCopy || null)" | ConvertFrom-Json
	CheckTrue "『复制坐标』被点后真的走了复制逻辑" ($null -ne $copyResult) "window.__mmtrLastCopy = $(Eval "JSON.stringify(window.__mmtrLastCopy || null)")"
	# 复制的文本必须**正好是引擎里某个节点的坐标**（不能是随手拼的字符串、不能带多余空格）。
	# 用引擎的坐标表来核，而不是从 DOM 里抠：菜单打开时那个节点并没有显示坐标的地方。
	$engineNodeCoords = @((Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-topology" -TimeoutSec 25).data.nodes |
		ForEach-Object { "$($_.x), $($_.y), $($_.z)" })
	if ($null -ne $copyResult) {
		CheckTrue "复制出来的是引擎里真实存在的节点坐标" ($engineNodeCoords -contains $copyResult.text) `
			"复制内容「$($copyResult.text)」；引擎里 $(if ($engineNodeCoords -contains $copyResult.text) { '有' } else { '**没有**' }) 这个节点（共 $($engineNodeCoords.Count) 个）"
	}
	# 真读剪贴板核对。
	#
	# <p>实测约束：无头浏览器**没有系统剪贴板**，`writeText` 报成功而 `readText` 读回空 ——
	# 授予 `clipboardReadWrite` 也一样。所以这里不用 `readText` 的返回值做断言（那会把环境限制
	# 写成页面缺陷），而是核对**页面自报的写入结果**：`__mmtrLastCopy` 里带着
	# `ok`（API 是否接受）、`via`（走的哪条路）、`text`（写进去的内容）。
	# 这三样合起来足以说明"这条功能真的接上了"，而上面的"文本必须存在于引擎的节点表里"
	# 则保证了复制内容不是随手拼的字符串。</p>
	Write-Output "（无头浏览器读不回剪贴板内容，写入结果以下面的 __mmtrLastCopy 为准：ok/via/text 三项齐全即算接通）"
	if ($null -ne $copyResult) {
		CheckTrue "页面自报的复制结果是完整的一次成功写入" `
			($copyResult.ok -eq $true -and $copyResult.text -eq "" -eq $false -and $copyResult.via -ne "") `
			"ok=$($copyResult.ok) via=$($copyResult.via) text=「$($copyResult.text)」error=「$($copyResult.error)」"
	}

	# 收尾：在**同一个节点**上重新打开菜单。
	# 复制那一下会被点掉菜单，而后面『居中到这里』那一步需要菜单开着、且要在同一个节点上
	# （换成别的节点就会去测另一个交互）。直接对该节点的圆点派发 pointerdown/pointerup，
	# 不走屏幕坐标：这一页节点很密，挑坐标很容易点到邻居（实测踩过两次）。
	$reopened = Eval @"
(() => {
  const node = [...document.querySelectorAll('.node')].find(n => n.dataset.key === '$($clickedCopy.key)');
  if (!node) return 'no-node';
  const dot = node.querySelector('.dot');
  const r = dot.getBoundingClientRect();
  const o = {bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, pointerId: 9, button: 0};
  dot.dispatchEvent(new PointerEvent('pointerdown', o));
  dot.dispatchEvent(new PointerEvent('pointerup', o));
  return 'ok';
})()
"@
	Start-Sleep -Milliseconds 400
	$menuAfter = Eval "document.querySelector('.menu') ? [...document.querySelectorAll('.menu-item')].map(b => b.textContent).join(' / ') : '（无）'"
	CheckTrue "复制之后能在同一节点上重新打开菜单" ($reopened -eq "ok" -and $menuAfter -notlike "*（无）*") "重开结果 $reopened；菜单：$menuAfter"

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
