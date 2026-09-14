# 道岔层验证：所有道岔都画出来 → 点开 → 换开通位 → **引擎真的变了** → 界面跟着变 → 再扳回去。
#
# 为什么必须端到端验：这条交互每一步都可能"看着对"而其实没接上 ——
# 道岔没画出来（数量对不上）、点了没反应、点了腿但下发的 via/branch 是错的、
# 引擎变了而页面没重取（显示还停在旧位）、或者把别的道岔扳了。
# 所以断言分两半：**页面真实画出来的东西**（DOM）与**引擎的权威状态**（mmtr-points）。
param([int]$Port = 9533)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-points-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1400,900",
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

# 引擎权威状态：全部道岔（x,y,z,via,manual,腿数）
function EnginePoints {
	$p = Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-points" -TimeoutSec 25
	return $p.data.points
}

# 某个道岔在引擎里的开通位
function EngineManual([int]$x, [int]$y, [int]$z) {
	$hit = (EnginePoints) | Where-Object { $_.x -eq $x -and $_.y -eq $y -and $_.z -eq $z } | Select-Object -First 1
	if ($null -eq $hit) { return -999 }
	return [int]$hit.manual
}

function WaitFor([scriptblock]$condition, [int]$timeoutSeconds = 12) {
	$deadline = (Get-Date).AddSeconds($timeoutSeconds)
	while ((Get-Date) -lt $deadline) {
		if (& $condition) { return $true }
		Start-Sleep -Milliseconds 300
	}
	return $false
}

try {
	Connect

	# ---------------------------------------------------------------- 1. 全部道岔都画出来了
	$engine = EnginePoints
	# 开始时给所有道岔拍一张"开通位快照"：收尾要证明"只动了目标那一个"。
	# 键用**节点 + 盆轨**（一个节点上可以有多个道岔、每个进向一个，只用坐标会挤成一条）。
	$beforePoints = @{}
	foreach ($p in $engine) { $beforePoints["$($p.x),$($p.y),$($p.z)|$($p.via)"] = [int]$p.manual }
	# 页面上**一处物理道岔只画一个**（单开道岔的三行按节点合并，见 TopologyView.displayPoints），
	# 所以页面数 = 非单开道岔的条目数 + 单开道岔的节点数。
	$turnoutNodes = @{}
	foreach ($p in $engine) { if ($null -ne $p.position) { $turnoutNodes["$($p.x),$($p.y),$($p.z)"] = $true } }
	$legacyEntries = @($engine | Where-Object { $null -eq $_.position }).Count
	$engineCount = $legacyEntries + @($turnoutNodes.Keys).Count
	$domCount = Eval "document.querySelectorAll('.map .points .point').length"
	CheckTrue "画出的道岔数 = 合并后的引擎道岔数" ($domCount -eq $engineCount) `
		"引擎条目 $(@($engine).Count) 个（老式岔口 $legacyEntries + 单开道岔 $(@($turnoutNodes.Keys).Count) 处）→ 期望 $engineCount 个标记，页面 $domCount 个"

	# 每个道岔都带 data-key，且 key 与引擎的节点坐标一一对应
	$domKeys = Eval "Array.from(document.querySelectorAll('.map .points .point')).map(e => e.dataset.key)"
	$engineKeys = @($engine | ForEach-Object { "$($_.x),$($_.y),$($_.z)" } | Select-Object -Unique)
	$missing = @($engineKeys | Where-Object { $domKeys -notcontains $_ })
	CheckTrue "引擎的每个道岔节点都能在页面上按坐标找到" ($missing.Count -eq 0) `
		"缺失 $($missing.Count) 个：$(($missing | Select-Object -First 3) -join ' / ')"

	# 画出来的菱形里有当前开通位数字
	$withNumber = Eval "Array.from(document.querySelectorAll('.map .points .point .leg-number')).filter(e => /^-?\d+$/.test(e.textContent.trim())).length"
	CheckTrue "每个道岔都显示了当前开通位数字" ($withNumber -eq $engineCount) `
		"带数字的 $withNumber / $engineCount"

	# ---------------------------------------------------------------- 2. 挑一个**老式**（非单开道岔）的 2 腿岔口做换向
	# （单开道岔的换位另有 check-web-turnout.ps1 验：它的操作是"位置 0/1"两个按钮，不是逐条腿）
	$target = $engine | Where-Object { $null -eq $_.position -and @($_.legs).Count -ge 2 } | Select-Object -First 1
	if ($null -eq $target) { throw "世界里没有老式带腿的道岔，无法验证换向" }
	$tx = [int]$target.x; $ty = [int]$target.y; $tz = [int]$target.z
	$legCount = @($target.legs).Count
	$originalManual = [int]$target.manual
	$originalActive = if ($originalManual -ge 0) { $originalManual } else { 0 }
	$otherLeg = if ($originalActive -eq 0) { 1 } else { 0 }
	Write-Output ""
	Write-Output "换向用的道岔：$tx,$ty,$tz  腿数 $legCount  原开通=$originalActive（manual=$originalManual）→ 目标腿 $otherLeg"

	# 点开这个道岔
	$clicked = Eval "(() => { const e = Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$tx,$ty,$tz')?.querySelector('.diamond'); if (!e) return false; const r = e.getBoundingClientRect(); const o = {bubbles:true, cancelable:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2, pointerId:1, button:0}; e.dispatchEvent(new PointerEvent('pointerdown', o)); return true; })()"
	CheckTrue "点道岔能展开面板" ($clicked -eq $true) "在 ($tx,$ty,$tz) 的菱形上派发了 pointerdown"

	Start-Sleep -Milliseconds 500
	$panelLegs = Eval "(Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$tx,$ty,$tz')?.querySelectorAll('.legs .leg') ?? []).length"
	CheckTrue "面板里的腿按钮数 = 引擎给的腿数" ($panelLegs -eq $legCount) `
		"引擎 $legCount 条，页面 $panelLegs 个按钮"

	$activeText = Eval "(() => { const b = Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$tx,$ty,$tz')?.querySelector('.legs .leg.active b'); return b ? b.textContent.trim() : ''; })()"
	CheckTrue "面板标出的'当前开通位'与引擎一致" ($activeText -eq "$originalActive") `
		"引擎 manual=$originalManual（生效 $originalActive），页面高亮 '$activeText'"

	# ---------------------------------------------------------------- 2b. 选中的道岔要把它**当前联通的那条轨**点亮
	# 这是用户要求的那条："选择道岔时，把当前联通的道岔高亮显示" ——
	# 卡片上只写"开通 leg 0（直通）"是看不出世界图里到底是哪条轨的，所以断言落在**那条轨**上。
	$engineActiveHex = [string]$target.legs[$originalActive].hex
	$marked = Eval "(() => { const el = document.querySelector('.rails .rail.connected'); return el ? el.dataset.hex : ''; })()"
	$markedShort = if ($marked.Length -gt 12) { $marked.Substring(0, 12) + "…" } else { $marked }
	CheckTrue "选中的道岔把当前联通的轨点亮了" ($marked -eq $engineActiveHex) `
		"引擎说腿 $originalActive 是轨 $($engineActiveHex.Substring(0, 12))…，地图上点亮的是 '$markedShort'"

	$selectedRing = Eval "document.querySelectorAll('.map .points .point.selected').length"
	CheckTrue "选中的道岔自己有高亮环（认得出亮线归谁）" ($selectedRing -eq 1) "带 selected 的道岔 $selectedRing 个"

	# ---------------------------------------------------------------- 3. 点另一条腿：引擎必须真的变
	$picked = Eval "(() => { const bs = Array.from((Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$tx,$ty,$tz')?.querySelectorAll('.legs .leg') ?? [])); const b = bs.find(x => x.querySelector('b').textContent.trim() === '$otherLeg'); if (!b) return false; const r = b.getBoundingClientRect(); const o = {bubbles:true, cancelable:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2, pointerId:2, button:0}; b.dispatchEvent(new PointerEvent('pointerdown', o)); return true; })()"
	CheckTrue "能点到目标腿的按钮" ($picked -eq $true) "腿 $otherLeg"

	$changed = WaitFor { (EngineManual $tx $ty $tz) -eq $otherLeg } 15
	CheckTrue "引擎里的开通位真的变了" $changed `
		"期望 manual=$otherLeg，实测 manual=$(EngineManual $tx $ty $tz)"

	# ---------------------------------------------------------------- 4. 界面跟着变（重取数据后）
	$refreshed = WaitFor {
		$t = Eval "(() => { const b = Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$tx,$ty,$tz')?.querySelector('.legs .leg.active b'); if (b) return b.textContent.trim(); const n = Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$tx,$ty,$tz')?.querySelector('.leg-number'); return n ? n.textContent.trim() : ''; })()"
		return ($t -eq "$otherLeg")
	} 15
	CheckTrue "界面重取后显示的也是新开通位" $refreshed "期望 '$otherLeg'"

	# 换腿之后，地图上点亮的那条轨必须**跟着换成新腿**（否则"高亮"就只是个装饰，不反映现实）
	$otherHex = [string]$target.legs[$otherLeg].hex
	$markedAfter = WaitFor {
		$m = Eval "(() => { const el = document.querySelector('.rails .rail.connected'); return el ? el.dataset.hex : ''; })()"
		return ($m -eq $otherHex)
	} 15
	CheckTrue "换腿后点亮的轨也跟着换" $markedAfter `
		"期望新腿的轨 $($otherHex.Substring(0, 12))…"

	# ---------------------------------------------------------------- 5. 只动了这一个道岔
	# 用**快照对比**而不是"别的道岔都是 0"：世界里本来就可能有别的道岔被人设在非 0 位（实测就有），
	# 那种前提会把"世界被人动过"当成脚本失败。
	#
	# 身份用**节点 + 盆轨**（`x,y,z|via`），不是只用节点坐标：同一个节点上可以有好几个道岔
	# （每个进向一个），只用坐标做键会把它们挤成一条 —— 实测就因此报出"同一个键 1→0 又 1→0"
	# 这种看不懂的漂移（那个节点上其实有 3 个道岔条目）。
	$after = EnginePoints
	$afterByIdentity = @{}
	foreach ($p in $after) { $afterByIdentity["$($p.x),$($p.y),$($p.z)|$($p.via)"] = [int]$p.manual }
	$drifted = @()
	$vanished = @()
	foreach ($identity in $beforePoints.Keys) {
		if ($identity -like "$tx,$ty,$tz|*") { continue }
		if (-not $afterByIdentity.ContainsKey($identity)) {
			$vanished += $identity
			continue
		}
		if ($afterByIdentity[$identity] -ne $beforePoints[$identity]) {
			$drifted += "$identity $($beforePoints[$identity])→$($afterByIdentity[$identity])"
		}
	}
	CheckTrue "扳一个道岔没有连带改动别的" ($drifted.Count -eq 0) `
		"按(节点+盆轨)比对了 $($beforePoints.Count - 1) 个其余道岔，开通位变化 $($drifted.Count) 个：$(($drifted | Select-Object -First 3) -join ' / ')"
	# "点集合本身变了"（世界重扫/发现层面的变化）单独报出来：它与"我扳了道岔"是两件事，
	# 混在一起就会把世界自己的变化写成脚本失败。
	if ($vanished.Count -gt 0) {
		Write-Output ("       注意：有 $($vanished.Count) 个道岔条目在本次动作中从接口里消失（世界重扫/发现层面的变化，与本次扳动无关）")
	}

	# ---------------------------------------------------------------- 6. 收尾：扳回原状
	$restored = Eval "(() => { const bs = Array.from((Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$tx,$ty,$tz')?.querySelectorAll('.legs .leg') ?? [])); const b = bs.find(x => x.querySelector('b').textContent.trim() === '$originalActive'); if (!b) return false; const r = b.getBoundingClientRect(); b.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true, cancelable:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2, pointerId:3, button:0})); return true; })()"
	$back = WaitFor { (EngineManual $tx $ty $tz) -eq $originalActive } 15
	CheckTrue "换向能扳回原状（脚本不留副作用）" ($restored -and $back) `
		"期望回到 $originalActive，实测 $(EngineManual $tx $ty $tz)"
} finally {
	try { $script:ws?.Dispose() } catch { }
	try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
	try { Remove-Item $profile -Recurse -Force -ErrorAction SilentlyContinue } catch { }
}

Write-Output ""
if ($failed -eq 0) {
	Write-Output "全部通过"
	exit 0
}
Write-Output "$failed 项失败"
exit 1
