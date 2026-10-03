# 一次性诊断：页面上真正画出来的候选轨/绑定轨，与引擎给的名字逐一对上。
param([int]$Port = 9523, [string]$Key = "-145,-60,-169")

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-diag-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
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
	$sig = Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-signals" -TimeoutSec 25
	$s = $sig.data.signals | Where-Object { $_.key -eq $Key }
	Write-Output "=== 引擎给的（$Key）==="
	Write-Output ("  候选 " + $s.candidateRails.Count + " 条：")
	$s.candidateRails | ForEach-Object { "    " + $_ }
	Write-Output ("  守 " + $s.boundRails.Count + " 条：")
	$s.boundRails | ForEach-Object { "    " + $_ }

	# 点灯（先放大，免得点到邻居）
	Eval "(function(){ const b = [...document.querySelectorAll('.hud .action')].find(x => x.textContent.includes('看信号灯')); if (b) b.click(); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 900
	$pos = Eval @"
(async () => {
  const want = '$Key'.replace(/,/g, ', ').trim();
  for (const el of [...document.querySelectorAll('.signals .signal')]) {
    el.dispatchEvent(new PointerEvent('pointerenter', {bubbles: false}));
    await new Promise(r => setTimeout(r, 5));
    const card = el.querySelector('.card');
    if (card && ((card.querySelector('.coords') || {}).textContent || '').trim() === want) {
      const r = el.getBoundingClientRect();
      return JSON.stringify({x: Math.round(r.left), y: Math.round(r.top)});
    }
  }
  return JSON.stringify({x: -1, y: -1});
})()
"@ | ConvertFrom-Json
	Cdp "Input.dispatchMouseEvent" @{ type = "mousePressed"; x = [int]$pos.x; y = [int]$pos.y; button = "left"; clickCount = 1; buttons = 1 } | Out-Null
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseReleased"; x = [int]$pos.x; y = [int]$pos.y; button = "left"; clickCount = 1; buttons = 0 } | Out-Null
	Start-Sleep -Milliseconds 800

	$dom = Eval @'
(() => {
  const rails = [...document.querySelectorAll('.rails .rail')];
  const cand = rails.filter(r => r.classList.contains("candidate")).map(r => r.getAttribute("d"));
  const bound = rails.filter(r => r.classList.contains("bound")).map(r => r.getAttribute("d"));
  const hits = [...document.querySelectorAll(".rails .hit")];
  return JSON.stringify({
    railTotal: rails.length, candidateLines: cand.length, boundLines: bound.length, hits: hits.length,
    signalTotal: document.querySelectorAll(".signals .signal").length,
    hud: document.querySelector(".hud").innerText.replace(/\n/g, " ").slice(0, 160),
    hitPointerEvents: hits.length > 0 ? getComputedStyle(hits[0]).pointerEvents : "n/a",
    hitStrokeWidth: hits.length > 0 ? getComputedStyle(hits[0]).strokeWidth : "n/a",
    svgPointerEvents: getComputedStyle(document.querySelector("svg.rails")).pointerEvents,
    sampleCand: cand.slice(0, 2),
  });
})()
'@ | ConvertFrom-Json
	Write-Output ""
	Write-Output "=== 页面真实画出来的 ==="
	Write-Output ("  轨线共 " + $dom.railTotal + " 条；候选线 " + $dom.candidateLines + "；绑定线 " + $dom.boundLines + "；命中区 " + $dom.hits)
	Write-Output ("  .hit 的 pointer-events=" + $dom.hitPointerEvents + "  stroke-width=" + $dom.hitStrokeWidth + "；svg.rails 的 pointer-events=" + $dom.svgPointerEvents)
	Write-Output ("  灯标记 " + $dom.signalTotal + " 个")
	Write-Output ("  HUD：" + $dom.hud)

	# ---- 事件追踪：点一下命中区，看看到底哪些事件到了哪里 ----
	Eval @'
(() => {
  window.__mmtrTrace = [];
  const hit = document.querySelector('.rails .hit');
  if (!hit) { window.__mmtrTrace.push('没有 .hit'); return 1; }
  for (const type of ['pointerdown', 'pointerup', 'click', 'mousedown', 'mouseup']) {
    hit.addEventListener(type, e => window.__mmtrTrace.push('hit:' + type + (e.defaultPrevented ? '(默认已阻止)' : '')), true);
  }
  document.querySelector('.map').addEventListener('pointerdown', () => window.__mmtrTrace.push('map:pointerdown'), true);
  window.__mmtrHit = hit;
  return 1;
})()
'@ | Out-Null
	$point = Eval @'
(() => {
  const hit = window.__mmtrHit;
  const len = hit.getTotalLength();
  const p = hit.getPointAtLength(len / 2).matrixTransform(hit.getScreenCTM());
  return JSON.stringify({x: Math.round(p.x), y: Math.round(p.y)});
})()
'@ | ConvertFrom-Json
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseMoved"; x = [int]$point.x; y = [int]$point.y; buttons = 0 } | Out-Null
	Cdp "Input.dispatchMouseEvent" @{ type = "mousePressed"; x = [int]$point.x; y = [int]$point.y; button = "left"; clickCount = 1; buttons = 1 } | Out-Null
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseReleased"; x = [int]$point.x; y = [int]$point.y; button = "left"; clickCount = 1; buttons = 0 } | Out-Null
	Start-Sleep -Milliseconds 800
	$trace = Eval "JSON.stringify(window.__mmtrTrace)"
	$top = Eval ("(() => { const el = document.elementFromPoint(" + $point.x + ", " + $point.y + "); return el ? (el.getAttribute('class') || el.tagName) : 'none'; })()")
	$pick = Eval "JSON.stringify(window.__mmtrPick ?? '（处理器没被调用）')"
	$canvas = Eval "JSON.stringify(window.__mmtrCanvas ?? '（画布没收到）')"
	$msgs = Eval "document.querySelectorAll('.n-message').length"
	Write-Output ""
	Write-Output ("=== 点 (" + $point.x + ", " + $point.y + ") 的事件轨迹（该点下方元素=" + $top + "）===")
	Write-Output ("  DOM 事件：" + $trace)
	Write-Output ("  Vue 处理器 onRailPick 收到：" + $pick)
	Write-Output ("  画布 MapCanvas.onPickRail 收到：" + $canvas)
	Write-Output ("  页面上弹出的消息条数：" + $msgs)
} catch {
	Write-Output ("出错：" + $_.Exception.Message)
} finally {
	if ($script:ws) { $script:ws.Dispose() }
	taskkill /PID $proc.Id /T /F 2>&1 | Out-Null
}

