# 「复制坐标」端到端核对：真的点菜单项 → 看剪贴板里到底进了什么。
#
# 为什么要端到端：这条功能有三层可能骗人 —— 菜单点了没反应、复制接口静默失败、
# 复制成功但内容不是那个坐标。所以断言全部落在**实际写入的文本**（页面把最后一次尝试挂在
# window.__mmtrLastCopy）与**坐标本身**上。
param([int]$Port = 9570)
$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-copy-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
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
function Eval([string]$e, [bool]$userGesture = $true) {
	# userGesture=true 是关键：剪贴板写入要求"真实用户手势"，无头浏览器默认没有，
	# 于是 writeText 会以 NotAllowedError 被拒 —— 那**不是**这条功能的真实状况（真人点一下就有手势）。
	$r = Cdp "Runtime.evaluate" @{expression = $e; returnByValue = $true; awaitPromise = $true; userGesture = $userGesture}
	if ($r.result.exceptionDetails) { throw ("求值异常：" + $r.result.exceptionDetails.text) }
	return $r.result.result.value
}
try {
	Connect
	# 剪贴板读写权限：无头环境默认是拒的（真人浏览器里点一下就自带许可）
	Cdp "Browser.grantPermissions" @{origin = "http://127.0.0.1:8888"; permissions = @("clipboardReadWrite", "clipboardSanitizedWrite")} | Out-Null
	Write-Output "=== 1) 节点菜单里的「复制坐标」 ==="
	# **按真实鼠标来**：pointerdown → pointerup → click 一路冒泡。
	# 只用 target.click() 会绕过"按下-抬起被父元素当成点击"这一类 bug（实测漏过一次：
	# 按在菜单项上时 pointerup 冒泡到节点 → 菜单先关掉 → click 落空 → 什么都不复制）。
	$info = Eval @'
(async () => {
  const nodes = [...document.querySelectorAll('.nodes .node')];
  if (!nodes.length) return JSON.stringify({error: '页面上没有节点'});
  const el = nodes[0];
  const r = el.getBoundingClientRect();
  const opts = {bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0, pointerId: 1, isPrimary: true};
  el.dispatchEvent(new PointerEvent('pointerdown', opts));
  el.dispatchEvent(new PointerEvent('pointerup', opts));
  await new Promise(res => setTimeout(res, 300));
  const items = [...document.querySelectorAll('.menu .menu-item, .menu .item, .menu button')];
  const labels = items.map(i => i.textContent.trim());
  const target = items.find(i => i.textContent.includes('指令用'));
  if (!target) return JSON.stringify({error: '没找到「复制坐标（指令用）」菜单项', labels});
  const tr = target.getBoundingClientRect();
  const tOpts = {bubbles: true, cancelable: true, clientX: tr.left + tr.width / 2, clientY: tr.top + tr.height / 2, button: 0, pointerId: 1, isPrimary: true};
  window.__mmtrLastCopy = null;
  // 真实鼠标的完整序列：按下、抬起、再 click
  target.dispatchEvent(new PointerEvent('pointerdown', tOpts));
  target.dispatchEvent(new PointerEvent('pointerup', tOpts));
  // 保真检查：真鼠标按下再抬起时，菜单项必须**还在 DOM 里**。真浏览器里元素若已被移除，
  // 就不会再有 click 落到它身上 —— 这就是"点『复制坐标』什么都没发生"的成因。
  const survived = document.contains(target);
  if (!survived) {
    return JSON.stringify({key: el.dataset.key, labels, last: window.__mmtrLastCopy || null, menuStillOpen: !!document.querySelector('.menu'), bug: 'pointerup 冒泡把菜单关掉了：菜单项在 click 之前就没了'});
  }
  target.dispatchEvent(new MouseEvent('click', tOpts));
  await new Promise(res => setTimeout(res, 700));
  return JSON.stringify({key: el.dataset.key, labels, last: window.__mmtrLastCopy || null, menuStillOpen: !!document.querySelector('.menu')});
})()
'@ | ConvertFrom-Json
	Write-Output ("   节点 key = " + $info.key)
	Write-Output ("   菜单项   = " + ($info.labels -join " | "))
	if ($info.error) { Write-Output ("   问题：" + $info.error) }
	if ($info.last) {
		Write-Output ("   复制结果 ok=" + $info.last.ok + " via=" + $info.last.via + " err=" + $info.last.error)
		Write-Output ("   本该复制的文本 = [" + $info.last.text + "]")
	}
	# 把剪贴板**读回来**：这才是"直接送进剪贴板"的硬证据（不是看页面说成功）
	$clip = Eval @'
(async () => {
  try { return await navigator.clipboard.readText(); } catch (e) { return "读取剪贴板失败：" + e; }
})()
'@ $false
	Write-Output ("   剪贴板实际内容 = [" + $clip + "]")
	Write-Output ("   一致 = " + ($clip -eq $info.last.text))
	Write-Output ("   点完菜单已收起 = " + (-not $info.menuStillOpen))
	if ($info.bug) { Write-Output ("   **BUG** " + $info.bug) }

	Write-Output ""
	Write-Output "=== 2) 灯卡片：复制坐标 / 送指令栏 ==="
	$lamp = Eval @'
(async () => {
  const lamps = [...document.querySelectorAll('.signals .signal')];
  if (!lamps.length) return JSON.stringify({error: '页面上没有灯'});
  const el = lamps[0];
  el.dispatchEvent(new PointerEvent('pointerenter', {bubbles: false}));
  await new Promise(res => setTimeout(res, 400));
  const card = el.querySelector('.card');
  const buttons = card ? [...card.querySelectorAll('button')].map(b => b.textContent.trim()) : [];
  const out = {key: el.dataset.key, hasCard: !!card, buttons};
  const copyBtn = card ? [...card.querySelectorAll('button')].find(b => b.textContent.includes('复制坐标')) : null;
  if (copyBtn) { copyBtn.click(); await new Promise(res => setTimeout(res, 600)); out.copyResult = window.__mmtrLastCopy || null; }
  const whyBtn = card ? [...card.querySelectorAll('button')].find(b => b.textContent.includes('为什么')) : null;
  if (whyBtn) { whyBtn.click(); await new Promise(res => setTimeout(res, 600)); }
  const input = document.querySelector('.console input.input, .mmtr-console input');
  out.consoleOpen = !!document.querySelector('.console, .mmtr-console');
  out.consoleInput = input ? input.value : null;
  return JSON.stringify(out);
})()
'@ | ConvertFrom-Json
	Write-Output ("   灯 key = " + $lamp.key + "  有卡片=" + $lamp.hasCard)
	Write-Output ("   卡片按钮 = [" + ($lamp.buttons -join ", ") + "]")
	if ($lamp.copyResult) { Write-Output ("   复制结果 ok=" + $lamp.copyResult.ok + " 内容=[" + $lamp.copyResult.text + "]") }
	Write-Output ("   指令栏已打开=" + $lamp.consoleOpen + "  输入行=[" + $lamp.consoleInput + "]")

	Write-Output ""
	Write-Output "=== 3) 点卡片上的坐标本体（最直接的那一下） ==="
	$direct = Eval @'
(async () => {
  const lamps = [...document.querySelectorAll('.signals .signal')];
  if (!lamps.length) return JSON.stringify({error: '页面上没有灯'});
  const el = lamps[0];
  el.dispatchEvent(new PointerEvent('pointerenter', {bubbles: false}));
  await new Promise(res => setTimeout(res, 350));
  const coords = el.querySelector('.card .coords');
  if (!coords) return JSON.stringify({error: '卡片上没有坐标元素'});
  window.__mmtrLastCopy = null;
  coords.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true, cancelable: true, button: 0, pointerId: 1, isPrimary: true}));
  coords.click();
  await new Promise(res => setTimeout(res, 500));
  let clip = "";
  try { clip = await navigator.clipboard.readText(); } catch (e) { clip = "读取失败：" + e; }
  return JSON.stringify({key: el.dataset.key, last: window.__mmtrLastCopy || null, clip, stillHovered: !!el.querySelector('.card')});
})()
'@ | ConvertFrom-Json
	Write-Output ("   灯 key = " + $direct.key)
	if ($direct.last) { Write-Output ("   复制结果 ok=" + $direct.last.ok + " via=" + $direct.last.via) }
	Write-Output ("   剪贴板实际内容 = [" + $direct.clip + "]")
	Write-Output ("   卡片仍在（点坐标没有误触发改绑定） = " + $direct.stillHovered)

	Write-Output ""
	Write-Output "=== 4) 模拟"剪贴板接口被拒"（最接近用户那种窗口） ==="
	Cdp "Browser.resetPermissions" @{} | Out-Null
	$denied = Eval @'
(async () => {
  const nodes = [...document.querySelectorAll('.nodes .node')];
  const el = nodes[1] || nodes[0];
  const r = el.getBoundingClientRect();
  const opts = {bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0, pointerId: 1, isPrimary: true};
  el.dispatchEvent(new PointerEvent('pointerdown', opts));
  el.dispatchEvent(new PointerEvent('pointerup', opts));
  await new Promise(res => setTimeout(res, 300));
  const items = [...document.querySelectorAll('.menu .item, .node-menu .item, .menu button')];
  const target = items.find(i => i.textContent.includes('指令用'));
  window.__mmtrLastCopy = null;
  if (target) { target.click(); await new Promise(res => setTimeout(res, 700)); }
  let clip = "";
  try { clip = await navigator.clipboard.readText(); } catch (e) { clip = "（读不到：" + e.name + "）"; }
  return JSON.stringify({key: el.dataset.key, last: window.__mmtrLastCopy || null, clip});
})()
'@ | ConvertFrom-Json
	Write-Output ("   节点 key = " + $denied.key)
	if ($denied.last) {
		Write-Output ("   复制结果 ok=" + $denied.last.ok + " via=" + $denied.last.via)
		if (-not $denied.last.ok) { Write-Output ("   失败原因 = " + $denied.last.error) }
	}
	Write-Output ("   剪贴板实际内容 = [" + $denied.clip + "]")
} finally {
	try { $script:ws?.Dispose() } catch { }
	try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
	try { Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}
