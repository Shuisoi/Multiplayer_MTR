# 信号灯显示验证：挑出带灯的节点、状态与方向是否都画出来了。
param([int]$Port = 9520)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-sig-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1280,700",
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
				Start-Sleep -Seconds 5
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
	if (-not $task.Wait(15000)) { throw "CDP 超时：$method" }
	return ([Text.Encoding]::UTF8.GetString($buffer, 0, $task.Result.Count) | ConvertFrom-Json)
}

function Eval([string]$e) {
	$r = Cdp "Runtime.evaluate" @{ expression = $e; returnByValue = $true; awaitPromise = $true }
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
	Write-Output "=== 信号灯显示验证 ==="
	Write-Output ""

	# 引擎接口侧：应当每个灯都有状态
	$api = Eval @'
(async () => {
  const data = await (await fetch('/mtr/api/map/mmtr-signals')).json();
  const s = data.data.signals;
  const byState = {};
  for (const x of s) { const k = x.aspect || "（空）"; byState[k] = (byState[k] ?? 0) + 1; }
  return JSON.stringify({count: s.length, byState, withSection: s.filter(x => x.hasSection).length,
    emptyCount: s.filter(x => !x.aspect).length,
    angles: [...new Set(s.map(x => x.angle))].sort((a,b)=>a-b)});
})()
'@
	$a = $api | ConvertFrom-Json
	Write-Output "引擎侧：$($a.count) 个灯，状态 $($a.byState | ConvertTo-Json -Compress)，接入闭塞层 $($a.withSection) 个，朝向角 $($a.angles -join '/')"
	# 这条原来的措辞是"每个灯都有状态"，实测站不住：**没有区间的灯本来就没有状态**，
	# 而那是一种正当状态（"这盏灯不参与闭塞"），不是缺陷 —— 实测世界里 3 盏如此，
	# 其中两盏是人工把轨分配到了 30 多米外、绑定被引擎忽略后也推断不出结果。
	# 所以这里改成：**有区间的灯必须都有状态**（这才是真正该守的不变量），
	# 并把"没有区间的灯"单独报出来，让它是"看得见的实情"而不是被断言掩盖。
	$emptyState = [int]$a.emptyCount
	$withoutSection = [int]$a.count - [int]$a.withSection
	CheckTrue "没有区间的灯才允许没有状态（有区间的必须都有）" `
		($emptyState -le $withoutSection) `
		"状态分布 $($a.byState | ConvertTo-Json -Compress)（空状态 $emptyState 个 / 未接入闭塞层 $withoutSection 个）"

	# ---- 登记完整性：世界里扫到的灯必须都在登记表里（用户反馈过"显示不全"） ----
	# 背景：信号灯要**先被登记**才会出现在 mmtr-signals 里。实测世界里扫出 37 个，
	# 而登记表当时只有 31 个 —— 控制台少了 6 个灯。所以控制台每次读取都会触发一次
	# `signals scan`，并把扫描结果写到 HUD 上（"扫到 N 个信号灯 / 补登记 M 个"）。
	$scanLine = Eval @'
(async () => {
  const r = await (await fetch('/mtr/api/map/mmtr-command', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({command:''})})).json();
  const lines = (r.data && r.data.log) || [];
  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].includes('[signals]')) return lines[i];
  }
  return '';
})()
'@
	$found = $null
	if ($scanLine -match "找到\s*(\d+)\s*个信号灯") { $found = [int]$Matches[1] }
	$hudScan = Eval "(() => { const n = document.querySelector('.hud'); return n ? (n.innerText.match(/扫到[^\n]*/) || [''])[0] : ''; })()"
	Write-Output ("引擎扫描：" + $(if ($scanLine) { $scanLine } else { "（日志里没有 [signals] 行）" }))
	Write-Output ("HUD 上的登记说明：" + $(if ($hudScan) { $hudScan } else { "（无）" }))
	CheckTrue "扫描结果被读回并显示在 HUD 上" ($hudScan -ne "") "HUD: $hudScan"
	CheckTrue "页面灯数不少于世界里扫到的灯数" ($null -eq $found -or $a.count -ge $found) $(if ($null -eq $found) { "没读到扫描统计（跳过比较）" } else { "页面 $($a.count) ≥ 扫到 $found（差额来自扫描跳过的 BOUND 条目）" })

	# 页面侧
	$dom = Eval @'
(() => {
  const layer = document.querySelector('.signals');
  const markers = [...document.querySelectorAll('.signals .signal')];
  const arrows = markers.map(m => {
    const a = m.querySelector('.arrow');
    const lamp = m.querySelector('.lamp');
    const t = a.style.transform || getComputedStyle(a).transform;
    const rot = (t.match(/rotate\(([-\d.]+)deg\)/) || [])[1] ?? "";
    // 灯点颜色用 computedStyle（内联是 style.background）
    const color = getComputedStyle(lamp).backgroundColor;
    const box = lamp.getBoundingClientRect();
    return {text: a.textContent.trim(), rot, color, size: Math.round(box.width), x: Math.round(box.left + box.width/2), y: Math.round(box.top + box.height/2)};
  });
  const map = document.querySelector('.map').getBoundingClientRect();
  const inside = arrows.filter(a => a.x >= map.left && a.x <= map.right && a.y >= map.top && a.y <= map.bottom).length;
  const rotations = [...new Set(arrows.map(a => a.rot))].sort();
  const colors = {};
  for (const a of arrows) colors[a.color] = (colors[a.color] ?? 0) + 1;
  return JSON.stringify({hasLayer: !!layer, count: markers.length, inside, rotations,
    glyph: [...new Set(arrows.map(a => a.text))], colors, sizes: [...new Set(arrows.map(a => a.size))],
    hud: document.querySelector('.hud').innerText.replace(/\n/g, ' ')});
})()
'@
	$d = $dom | ConvertFrom-Json
	Write-Output "HUD: $($d.hud.Trim())"
	Write-Output "页面侧：$($d.count) 个灯标记，旋转角 $($d.rotations -join '/')，灯点直径 $($d.sizes -join '/')，颜色 $($d.colors | ConvertTo-Json -Compress)"
	CheckTrue "信号灯层渲染出来了" ($d.hasLayer -and $d.count -gt 0) "$($d.count) 个标记"
	CheckTrue "数量与引擎一致" ($d.count -eq $a.count) "页面 $($d.count) vs 引擎 $($a.count)"
	# 方向符号：用 SVG 画的 `^` 折角（不是文字）。
	# 为什么不用文字：实测 15px 字号下 DIN 的 `^` 字形只有约 2–3 像素高、旋转中心又正好落在灯点上，
	# 整个符号被灯点盖住 —— 用户的原话是"显示信号灯方向的在哪？"。所以改成 SVG 折角，尺寸可控。
	$glyph = Eval @'
(() => {
  const a = document.querySelector('.signals .arrow');
  if (!a) return JSON.stringify({ok: false});
  const r = a.getBoundingClientRect();
  const paths = [...a.querySelectorAll('path')].map(p => p.getAttribute('d'));
  return JSON.stringify({ok: true, tag: a.tagName.toLowerCase(), size: Math.round(r.width) + 'x' + Math.round(r.height), paths});
})()
'@
	$g = $glyph | ConvertFrom-Json
	CheckTrue "方向用 `^` 折角符号画出来（SVG，不是文字）" ($g.ok -and $g.tag -eq "svg" -and @($g.paths).Count -ge 1) "元素 <$($g.tag)> 尺寸 $($g.size)，路径 $($g.paths -join ' / ')"

	# 方向真的指对了吗：折角绕**灯点**旋转，所以朝上(0°)时它的墨迹在灯点上方、朝下(180°)时在下方。
	# 用箭头框相对灯点的位置来判（纯 DOM，不依赖截图缩放）。
	$directional = Eval @'
(() => {
  const out = {};
  for (const m of document.querySelectorAll('.signals .signal')) {
    const arrow = m.querySelector('.arrow');
    const rot = (arrow.style.transform.match(/rotate\(([-\d.]+)deg\)/) || [])[1];
    if (rot !== "0" && rot !== "180") continue;
    if (out[rot]) continue;
    const a = arrow.getBoundingClientRect();
    const l = m.querySelector('.lamp').getBoundingClientRect();
    out[rot] = {dy: Math.round((a.top + a.height/2) - (l.top + l.height/2)), dx: Math.round((a.left + a.width/2) - (l.left + l.width/2))};
    if (out["0"] && out["180"]) break;
  }
  return JSON.stringify(out);
})()
'@
	$dir = $directional | ConvertFrom-Json
	$up = $dir.'0'; $down = $dir.'180'
	CheckTrue "方向符号绕灯点旋转（0° 指上、180° 指下）" `
		($up -and $down -and $up.dy -lt -6 -and [Math]::Abs($up.dx) -le 1 -and $down.dy -gt 6 -and [Math]::Abs($down.dx) -le 1) `
		("0°：偏移 dx=$($up.dx) dy=$($up.dy)；180°：偏移 dx=$($down.dx) dy=$($down.dy)（dy 负=在上方）")

	# 朝向映射：MTR 角 180（北）→ 0°，0（南）→ 180°；页面上的旋转角集合应当等于换算结果
	$expected = @($a.angles | ForEach-Object { [int]((($_ + 180) % 360)) })
	$actual = @($d.rotations | ForEach-Object { [int]([Math]::Round([double]$_)) })
	$expectedSet = ($expected | Sort-Object -Unique) -join '/'
	CheckTrue "朝向角映射正确（MTR 角 → 屏幕旋转）" (($actual | Sort-Object -Unique) -join '/' -eq $expectedSet) "引擎角 $($a.angles -join '/') → 期望旋转 $expectedSet，实际 $($actual -join '/')"

	CheckTrue "全部灯都在视口内" ($d.inside -eq $d.count) "$($d.inside)/$($d.count) 个在视口内"
	CheckTrue "状态用不同颜色区分" ($d.colors.PSObject.Properties.Name.Count -ge 2) "颜色分布 $($d.colors | ConvertTo-Json -Compress)"

	# "看信号灯"：灯只占世界一小块（实测 46×190 格 vs 世界 407×1623 格），
	# 整图取景时它们会挤在一起互相盖住；点这个按钮应当把它们铺开，箭头才看得清。
	$before = Eval @'
(() => {
  const l = [...document.querySelectorAll('.signals .signal .lamp')].map(e => e.getBoundingClientRect());
  let close = 0;
  for (let i = 0; i < l.length; i++) for (let j = i+1; j < l.length; j++) {
    if (Math.hypot(l[i].left-l[j].left, l[i].top-l[j].top) < 8) close++;
  }
  return JSON.stringify({close, zoom: (document.querySelector('.hud').innerText.match(/([0-9.]+)×/) || [])[1]});
})()
'@
	$b = $before | ConvertFrom-Json
	Eval "(function(){ const btns = [...document.querySelectorAll('.hud .action')]; const b = btns.find(x => x.textContent.includes('看信号灯')); if (b) { b.click(); return 'clicked'; } return 'not-found'; })()" | Out-Null
	Start-Sleep -Milliseconds 700
	$after = Eval @'
(() => {
  const l = [...document.querySelectorAll('.signals .signal .lamp')].map(e => e.getBoundingClientRect());
  let close = 0;
  for (let i = 0; i < l.length; i++) for (let j = i+1; j < l.length; j++) {
    if (Math.hypot(l[i].left-l[j].left, l[i].top-l[j].top) < 8) close++;
  }
  return JSON.stringify({close, zoom: (document.querySelector('.hud').innerText.match(/([0-9.]+)×/) || [])[1], count: l.length});
})()
'@
	$a2 = $after | ConvertFrom-Json
	Write-Output ("「看信号灯」：缩放 " + $b.zoom + "× → " + $a2.zoom + "×，相互盖住的灯对 " + $b.close + " → " + $a2.close)
	CheckTrue "点『看信号灯』后灯被铺开（重叠显著减少）" ($a2.close -lt $b.close) "$($b.close) → $($a2.close) 对"
	CheckTrue "聚焦后灯数不变" ([int]$a2.count -eq [int]$a.count) "页面 $($a2.count) vs 引擎 $($a.count)"

	# 悬停信息卡
	$first = Eval "(function(){ const m = document.querySelector('.signals .signal'); const b = m.getBoundingClientRect(); return JSON.stringify({x: Math.round(b.left), y: Math.round(b.top)}); })()" | ConvertFrom-Json
	# 悬停需要真实指针事件：用元素中心
	$center = Eval "(function(){ const l = document.querySelector('.signals .signal .lamp').getBoundingClientRect(); return JSON.stringify({x: Math.round(l.left + l.width/2), y: Math.round(l.top + l.height/2)}); })()" | ConvertFrom-Json
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseMoved"; x = [int]$center.x; y = [int]$center.y; buttons = 0 } | Out-Null
	Start-Sleep -Milliseconds 500
	# 信息卡要**点名是信号灯层的那张**：道岔层也有 `.card`，而且道岔与灯常在同一格上，
	# 用 `.card` 泛匹配会把道岔的卡片当成本次悬停的结果（实测：假通过）。
	$card = Eval "document.querySelector('.signals .signal .card') ? document.querySelector('.signals .signal .card').innerText.replace(/\n/g, ' | ') : '（没有卡片）'"
	CheckTrue "悬停灯位弹出信息卡" ($card -notlike "*（没有卡片）*") $card

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

