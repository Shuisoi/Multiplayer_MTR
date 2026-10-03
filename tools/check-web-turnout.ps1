# 单开道岔（物理道岔：一处一个位置 0/1）在**网页上**能不能正常设？端到端验：
#   面板显示 ↔ 引擎位置 → 点另一条腿 → 引擎位置真的翻 → 禁行侧跟着换 → 再扳回原状。
#
# 与 check-web-points.ps1 的分工：那个脚本验的是**老式按进向各设 0/1 的岔口**；
# 单开道岔"一个节点三条腿、位置是节点级的"是新模型，网页是按条目逐行显示的，必须单独验。
param([int]$Port = 9535, [int]$X = -67, [int]$Y = -60, [int]$Z = -139)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-turnout-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
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

function Short([string]$hex) {
	if ([string]::IsNullOrEmpty($hex)) { return "（空）" }
	return $hex.Substring(0, 12) + "…"
}

function NodeEntries {
	$p = Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-points" -TimeoutSec 25
	return @($p.data.points | Where-Object { $_.x -eq $X -and $_.y -eq $Y -and $_.z -eq $Z })
}

function NodePosition {
	$e = NodeEntries
	if (@($e).Count -eq 0) { return -999 }
	return [int]$e[0].position
}

function WaitFor([scriptblock]$condition, [int]$timeoutSeconds = 12) {
	$deadline = (Get-Date).AddSeconds($timeoutSeconds)
	while ((Get-Date) -lt $deadline) {
		if (& $condition) { return $true }
		Start-Sleep -Milliseconds 300
	}
	return $false
}

# 在这个节点上，按"条目序号"（与引擎 feed 同序）点开面板，返回面板里腿按钮的轨 hex
function OpenPanel([int]$index) {
	return Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const t = list[$index]; if (!t) return 'NO_MARKER'; const d = t.querySelector('.diamond'); const r = d.getBoundingClientRect(); d.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true, cancelable:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2, pointerId:1, button:0})); return 'OK'; })()"
}

try {
	Connect

	$entries = NodeEntries
	if (@($entries).Count -eq 0) { throw "节点 $X,$Y,$Z 上没有道岔条目" }
	$position0 = [int]$entries[0].position
	$prohibited0 = [string]$entries[0].prohibited
	$stemHex = [string]$entries[0].stem
	Write-Output "节点 $X,$Y,$Z：$(@($entries).Count) 个条目，引擎位置=$position0"
	Write-Output "  根部=$((Short $stemHex))  当前禁行=$((Short $prohibited0))"
	$i = 0
	foreach ($e in $entries) {
		$legHexes = @($e.legs | ForEach-Object { $_.hex })
		$legText = (@($legHexes | ForEach-Object { (Short $_) }) -join ", ")
		Write-Output ("   [$i] via=$((Short $e.via)) form=$($e.form) manual=$($e.manual) 腿=$(@($e.legs).Count)（$legText）")
		$i++
	}

	# 根部那一行：它是**唯一能表达两个位置**的一行（引擎把节点位置翻译回这一行的腿号）。
	$stemIndex = -1
	for ($j = 0; $j -lt @($entries).Count; $j++) {
		if ([string]$entries[$j].via -eq $stemHex) { $stemIndex = $j; break }
	}
	if ($stemIndex -lt 0) { throw "接口里没有 via=根部 的条目" }
	$stemLegs = @($entries[$stemIndex].legs | ForEach-Object { $_.hex })
	if (@($stemLegs).Count -lt 2) { throw "根部这一行只有 $(@($stemLegs).Count) 条腿，无法换位" }
	# 岔股 / 正线远端：位置 0 时禁行的是岔股，位置 1 时禁行的是正线远端
	if ($position0 -eq 0) {
		$branchHex = $prohibited0
		$farHex = @($stemLegs | Where-Object { $_ -ne $prohibited0 })[0]
	} else {
		$farHex = $prohibited0
		$branchHex = @($stemLegs | Where-Object { $_ -ne $prohibited0 })[0]
	}
	Write-Output "根部行 = 条目 [$stemIndex]；岔股=$((Short $branchHex))  正线远端=$((Short $farHex))"

	# 页面上的标记数：**一处物理道岔只画一个**（引擎给的是三行 —— 每个进向一行）
	$marks = Eval "Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z').length"
	CheckTrue "页面上这个节点只画了 1 个道岔标记（不再是逐进向的三行）" ($marks -eq 1) `
		"引擎 $(@($entries).Count) 个条目（3 个进向）→ 页面 $marks 个标记"

	$openResult = OpenPanel 0
	CheckTrue "能点开这个道岔的面板" ($openResult -eq "OK") "节点 $X,$Y,$Z 的菱形"
	Start-Sleep -Milliseconds 400

	$panelText = Eval "(() => { const t = Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$X,$Y,$Z'); return t ? t.querySelector('.card').innerText.replace(/\s+/g, ' ') : 'NO_MARKER'; })()"
	Write-Output "面板文字：$panelText"
	$pair0 = Eval "(() => { const t = Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$X,$Y,$Z'); const b = Array.from(t.querySelectorAll('.position')).find(x => x.querySelector('b').textContent.trim() === '0'); return b ? b.querySelector('.position-pair').textContent.trim() : 'NO_PAIR'; })()"
	CheckTrue "「位置 0」写出了它接通的是哪两条轨（用坐标）" ("$pair0" -match '^\(-?\d+, -?\d+\) ↔ \(-?\d+, -?\d+\)$') `
		"页面显示 '$pair0'"

	$legButtons = Eval "(() => { const t = Array.from(document.querySelectorAll('.map .points .point')).find(e => e.dataset.key === '$X,$Y,$Z'); return t ? t.querySelectorAll('.legs .leg').length : -1; })()"
	CheckTrue "单开道岔不再列出逐进向的腿按钮（那正是「能从岔股开到正线远端」的误读来源）" ($legButtons -eq 0) `
		"页面上的腿按钮数 = $legButtons"

	$shown = Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const t = list[0]; const n = t ? t.querySelector('.leg-number') : null; return n ? n.textContent.trim() : ''; })()"
	CheckTrue "菱形上的数字 = 引擎的道岔位置（物理道岔显示节点位置，不显示逐行腿号）" ("$shown" -eq "$position0") `
		"引擎位置=$position0，页面显示 '$shown'"

	$positionButtons = Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const t = list[0]; return t ? Array.from(t.querySelectorAll('.position')).map(b => b.querySelector('b').textContent.trim() + '=' + b.querySelector('span').textContent.trim()).join(' | ') : 'NO_MARKER'; })()"
	CheckTrue "根部那一行有「位置 0 / 位置 1」两个按钮" ("$positionButtons" -like "0=正线贯通*1=岔股开放*") `
		"按钮：$positionButtons"

	$prohibitedFact = Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const t = list[0]; if (!t) return ''; const dts = Array.from(t.querySelectorAll('.facts dt')); const i = dts.findIndex(d => d.textContent.trim() === '禁行'); return i < 0 ? 'NO_FACT' : t.querySelectorAll('.facts dd')[i].textContent.trim(); })()"
	$expectedProhibitedName = if ($position0 -eq 0) { "岔股" } else { "正线远端" }
	CheckTrue "面板写出了当前禁行的是哪一侧" ("$prohibitedFact" -like "$expectedProhibitedName*") `
		"页面显示 '$prohibitedFact'，期望以 '$expectedProhibitedName' 开头"

	# 扳到**另一个位置**：点根部那一行的「位置 N」按钮
	$targetPosition = if ($position0 -eq 0) { 1 } else { 0 }
	$targetRailHex = if ($targetPosition -eq 1) { $branchHex } else { $farHex }
	Write-Output "扳动：位置 $position0 → $targetPosition（点「位置 $targetPosition」按钮，对应轨 $((Short $targetRailHex))）"

	# 扳动前的视图（用户报过"扳道岔地图回中"）：用灯位在屏幕上的坐标当凭据 —— 视图一变它必然变
	$viewBefore = Eval "(() => { const marks = Array.from(document.querySelectorAll('.map .signals .signal')); const zoom = document.querySelector('.hud')?.innerText.match(/缩放\s*([\d.]+×)/); return (marks[0]?.style.transform ?? '') + '|' + (marks[marks.length - 1]?.style.transform ?? '') + '|' + (zoom ? zoom[1] : ''); })()"
	Write-Output "扳动前视图：$viewBefore"

	$click = Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const t = list[0]; if (!t) return 'NO_MARKER'; const b = Array.from(t.querySelectorAll('.position')).find(x => x.querySelector('b').textContent.trim() === '$targetPosition'); if (!b) return 'NO_POSITION_BUTTON'; const r = b.getBoundingClientRect(); b.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true, cancelable:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2, pointerId:2, button:0})); return 'OK'; })()"
	CheckTrue "能点到「位置 $targetPosition」按钮" ($click -eq "OK") "$click"

	$flipped = WaitFor { (NodePosition) -eq $targetPosition } 15
	CheckTrue "点完之后引擎的道岔位置真的变成了 $targetPosition" $flipped "实测 $(NodePosition)"

	if ($flipped) {
		$now = NodeEntries
		$prohibitedNow = [string]$now[0].prohibited
		$expectedProhibited = if ($targetPosition -eq 1) { $farHex } else { $branchHex }
		CheckTrue "禁行侧跟着换（0 禁岔股 / 1 禁正线远端）" ($prohibitedNow -eq $expectedProhibited) `
			"引擎现在禁行 $((Short $prohibitedNow))，期望 $((Short $expectedProhibited))"

		# 界面重取后：菱形数字与"点亮的轨"都要跟上
		$refreshed = WaitFor {
			$t = Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const el = list[0]; const n = el ? el.querySelector('.leg-number') : null; return n ? n.textContent.trim() : ''; })()"
			return ("$t" -ne "$shown")
		} 15
		$newShown = Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const el = list[0]; const n = el ? el.querySelector('.leg-number') : null; return n ? n.textContent.trim() : ''; })()"
		CheckTrue "界面重取后根部行的数字也变了" $refreshed "新显示 '$newShown'（原 '$shown'）"

		$highlight = WaitFor {
			$m = Eval "(() => { const el = document.querySelector('.rails .rail.connected'); return el ? el.dataset.hex : ''; })()"
			return ($m -eq $targetRailHex)
		} 15
		$marked = Eval "(() => { const el = document.querySelector('.rails .rail.connected'); return el ? el.dataset.hex : ''; })()"
		CheckTrue "地图上点亮的轨 = 新开通的那条腿" $highlight `
			"点亮 $((Short $marked))，期望 $((Short $targetRailHex))"

		# 回中问题：扳道岔引起的数据重取**不许**动视图（用户报的"操作道岔地图回中"）
		Start-Sleep -Milliseconds 800
		$viewAfter = Eval "(() => { const marks = Array.from(document.querySelectorAll('.map .signals .signal')); const zoom = document.querySelector('.hud')?.innerText.match(/缩放\s*([\d.]+×)/); return (marks[0]?.style.transform ?? '') + '|' + (marks[marks.length - 1]?.style.transform ?? '') + '|' + (zoom ? zoom[1] : ''); })()"
		CheckTrue "扳道岔之后视图没有回中（灯位屏幕坐标与缩放都没变）" ("$viewAfter" -eq "$viewBefore") `
			"前 '$viewBefore' / 后 '$viewAfter'"
	}

	# 扳回原状（点原来的那个位置按钮）
	$backClick = Eval "(() => { const list = Array.from(document.querySelectorAll('.map .points .point')).filter(e => e.dataset.key === '$X,$Y,$Z'); const t = list[0]; if (!t) return 'NO_MARKER'; const b = Array.from(t.querySelectorAll('.position')).find(x => x.querySelector('b').textContent.trim() === '$position0'); if (!b) return 'NO_POSITION_BUTTON'; const r = b.getBoundingClientRect(); b.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true, cancelable:true, clientX:r.left+r.width/2, clientY:r.top+r.height/2, pointerId:3, button:0})); return 'OK'; })()"
	$restored = WaitFor { (NodePosition) -eq $position0 } 15
	CheckTrue "能扳回原状（脚本不留副作用）" ($backClick -eq "OK" -and $restored) `
		"$backClick → 实测位置 $(NodePosition)（期望 $position0）"
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
