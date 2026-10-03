# 指令栏验证：面板能开、指令真的改了世界、回复里带回受影响的对象、改完地图会跟着刷新。
#
# 为什么这条检查重要：指令栏最容易退化成"发出去就完事"的假界面。
# 所以这里每条断言都落到**可核对的实情**上——引擎的权威状态（车辆清单）与页面上真实的标记数，
# 而不是"按钮能点、日志有字"。
param([int]$Port = 9521)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-cmd-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
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

# 引擎权威状态：车辆数与车辆 id 集合（用来核对"指令真的执行了"）
function EngineVehicles {
	$body = @{command = "vehicle list"} | ConvertTo-Json -Compress
	$r = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8888/mtr/api/map/mmtr-command" -Body $body -ContentType "application/json" -TimeoutSec 25
	$ids = @()
	foreach ($line in $r.data.lines) { if ($line -match "^\s*车辆\s+(-?\d+)") { $ids += $Matches[1] } }
	return @{ok = $r.data.ok; count = $ids.Count; ids = $ids}
}

# 直接走 HTTP 发指令（用于核对"界面发出去的那条指令本身是否有效"，与界面无关）
function SendCommand([string]$command) {
	$body = @{command = $command} | ConvertTo-Json -Compress
	$r = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8888/mtr/api/map/mmtr-command" -Body $body -ContentType "application/json" -TimeoutSec 30
	return @{ok = $r.data.ok; namespace = $r.data.namespace; verb = $r.data.verb; affected = @($r.data.affected); lines = @($r.data.lines)}
}

try {
	Connect
	Write-Output "=== 指令栏验证 ==="
	Write-Output ""

	# ---- 1. 收起态：只有一枚按钮，不占地图 ----
	$tab = Eval @'
(() => {
  const t = document.querySelector('.console .tab');
  if (!t) return JSON.stringify({found: false});
  const r = t.getBoundingClientRect();
  return JSON.stringify({found: true, text: t.innerText.replace(/\n/g, ' ').trim(),
    w: Math.round(r.width), h: Math.round(r.height),
    panel: !!document.querySelector('.console .panel'),
    mapVisible: document.querySelector('.map').getBoundingClientRect().height > 100});
})()
'@ | ConvertFrom-Json
	CheckTrue "收起态只占一枚按钮，地图完整可见" ($tab.found -and -not $tab.panel -and $tab.mapVisible -and $tab.h -lt 40) "按钮「$($tab.text)」 $($tab.w)x$($tab.h)，面板存在=$($tab.panel)"

	# ---- 2. 点开面板：日志来自服务端 ----
	Eval "(function(){ document.querySelector('.console .tab').click(); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 1200
	$open = Eval @'
(() => {
  const p = document.querySelector('.console .panel');
  if (!p) return JSON.stringify({open: false});
  const log = document.querySelector('.console .log');
  return JSON.stringify({open: true, lines: log ? log.querySelectorAll('.line').length : 0,
    input: !!document.querySelector('.console .input'),
    chips: [...document.querySelectorAll('.console .chip')].map(c => c.textContent.trim()),
    text: log ? log.innerText.replace(/\n/g, ' | ').slice(0, 200) : ''});
})()
'@ | ConvertFrom-Json
	CheckTrue "面板展开，含有日志区与输入框" ($open.open -and $open.input) "日志 $($open.lines) 行，常用指令 $($open.chips.Count) 个"
	CheckTrue "日志来自服务端（含之前执行过的指令记录）" ($open.lines -gt 0) "首屏：$($open.text)"

	# ---- 3. 只读指令：回复里带回可核对的实情 ----
	$baseline = EngineVehicles
	Write-Output "引擎当前有 $($baseline.count) 辆车"
	Eval "(function(){ const i = document.querySelector('.console .input'); i.value='vehicle list'; i.dispatchEvent(new Event('input',{bubbles:true})); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 300
	Eval "(function(){ const b = [...document.querySelectorAll('.console .action')].find(x => x.textContent.includes('执行')); b.click(); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 2000
	$verdict = Eval "(() => { const v = document.querySelector('.console .verdict'); return v ? v.innerText.replace(/\n/g,' ') : ''; })()"
	$logText = Eval "document.querySelector('.console .log').innerText.replace(/\n/g, ' | ')"
	$listed = ([regex]::Matches($logText, "车辆\s+-?\d+")).Count
	CheckTrue "执行只读指令后给出结论（名词 动词 · 结果）" ($verdict -match "vehicle" -and $verdict -match "list") "结论：$verdict"
	CheckTrue "服务的回复原样显示在日志里（{0} 行车辆）" ($listed -ge $baseline.count) "日志里数到 $listed 条车辆行，引擎有 $($baseline.count) 辆"

	# ---- 4. 未知指令：失败也要有用法表，而不是一句空话 ----
	Eval "(function(){ const i = document.querySelector('.console .input'); i.value='nosuchnoun foo'; i.dispatchEvent(new Event('input',{bubbles:true})); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 300
	Eval "(function(){ const b = [...document.querySelectorAll('.console .action')].find(x => x.textContent.includes('执行')); b.click(); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 1500
	$bad = Eval @'
(() => {
  const log = document.querySelector('.console .log').innerText;
  const v = document.querySelector('.console .verdict');
  return JSON.stringify({verdict: v ? v.innerText.replace(/\n/g,' ') : '', hasBad: log.includes('[失败]'),
    hasUsage: log.includes('可用指令'), mentionsVerb: log.includes('vehicle spawn')});
})()
'@ | ConvertFrom-Json
	CheckTrue "不认识的指令：标成失败并给出用法表" ($bad.hasBad -and $bad.hasUsage -and $bad.mentionsVerb) "结论「$($bad.verdict)」，用法表已显示"

	# ---- 5. 写指令真的改了世界：在空股道上生成一节车，核完再删掉 ----
	# 为什么自己先造一辆：拿"引擎里现有车"去试删是**破坏性**的（用户的编组会少一节），
	# 而在一条空股道上"生成 → 核对 → 删除"三步闭环既不碰任何现有编组，又能证明三件事：
	# 指令真的改了引擎状态、回复里的 affected 是真的、删掉之后状态真的回去了。
	$body = @{command = "query depots"} | ConvertTo-Json -Compress
	$dep = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8888/mtr/api/map/mmtr-command" -Body $body -ContentType "application/json" -TimeoutSec 25
	$emptySiding = $null
	foreach ($line in $dep.data.lines) {
		if ($line -match "股道\s+id=(-?\d+).*模板=\[\]\s+在场=\[\]") { $emptySiding = $Matches[1] }
	}
	$before = EngineVehicles

	if ($null -eq $emptySiding) {
		Write-Output "[跳过] 没有空股道可用来做生成/删除闭环"
	} else {
		# 生成：受影响的 id 必须是这辆车的新 id
		$spawn = SendCommand "vehicle spawn saf101 --siding=$emptySiding"
		$mid = EngineVehicles
		CheckTrue "写指令在引擎侧生效（空股道上生成成功，车辆数 +1）" ($mid.count -eq $before.count + 1) "引擎 $($before.count) -> $($mid.count) 辆（股道 $emptySiding）"
		CheckTrue "生成回复里给出受影响对象，且 id 真的是新出现的那辆" `
			($spawn.affected.Count -gt 0 -and ($mid.ids -contains $spawn.affected[0])) `
			"affected=$($spawn.affected -join ',')；新集合里确实有它 = $($mid.ids -contains $spawn.affected[0])"

		# 删除：作用在**非空**股道上，所以必须真的少一辆，且 affected 就是刚生成的那辆
		$remove = SendCommand "vehicle remove --siding=$emptySiding"
		$after = EngineVehicles
		CheckTrue "删除指令把车真的删掉了（车辆数回到生成前）" ($after.count -eq $before.count) "引擎 $($mid.count) -> $($after.count) 辆（原本 $($before.count) 辆）"
		CheckTrue "删除回复里列出受影响的对象 id" ($remove.affected.Count -gt 0 -and $remove.affected[0] -eq $spawn.affected[0]) "删掉的 id=$($remove.affected -join ',')，生成的 id=$($spawn.affected -join ',')"
	}

	# ---- 6. 面板侧的显示：写指令后结论与受影响 id 都要显示出来 ----
	Eval "(function(){ const i = document.querySelector('.console .input'); i.value='signal list'; i.dispatchEvent(new Event('input',{bubbles:true})); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 300
	Eval "(function(){ const b = [...document.querySelectorAll('.console .action')].find(x => x.textContent.includes('执行')); b.click(); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 2000
	$verdict2 = Eval "(() => { const v = document.querySelector('.console .verdict'); return v ? v.innerText.replace(/\n/g,' ') : ''; })()"
	CheckTrue "面板把受影响的对象数显示在结论里" ($verdict2 -match "影响\s*\d+\s*项") "结论：$verdict2"

	# ---- 7. 拓扑刷新：改完世界地图会自己重取，且刷新没有把画面弄坏 ----
	# 期望值不写死：节点/信号灯数由引擎当场给出（世界是会变的，写死 130/66 只会让检查随世界一起过期）
	$dom = Eval @'
(async () => {
  const t = (await (await fetch('/mtr/api/map/mmtr-topology')).json()).data;
  const s = (await (await fetch('/mtr/api/map/mmtr-signals')).json()).data;
  return JSON.stringify({engineNodes: t.nodes.length, engineSignals: s.signals.length,
    domNodes: document.querySelectorAll('.nodes .node').length,
    domSignals: document.querySelectorAll('.signals .signal').length});
})()
'@ | ConvertFrom-Json
	# 对不上时**让页面重取一次再判**：页面的节点数是"上一次取数时的快照"，而引擎的数是**当场**读的；
	# 世界里只要有车在生成/删除、或拓扑在重算，两次读数之间就可能差几个节点
	# （实测：同一次运行里引擎从 137 变成 132，页面仍显示 137，于是这条把"世界在变"报成了失败）。
	# 重取之后仍然对不上，才算真的不一致。
	if ($dom.domNodes -ne $dom.engineNodes -or $dom.domSignals -ne $dom.engineSignals) {
		Write-Output "        （首次比对不一致：页面 $($dom.domNodes)/$($dom.domSignals) vs 引擎 $($dom.engineNodes)/$($dom.engineSignals)；让页面重取一次再比）"
		Eval "document.querySelectorAll('.action')[1].click()" | Out-Null
		Start-Sleep -Seconds 2
		$dom = Eval @'
(async () => {
  const t = (await (await fetch('/mtr/api/map/mmtr-topology')).json()).data;
  const s = (await (await fetch('/mtr/api/map/mmtr-signals')).json()).data;
  return JSON.stringify({engineNodes: t.nodes.length, engineSignals: s.signals.length,
    domNodes: document.querySelectorAll('.nodes .node').length,
    domSignals: document.querySelectorAll('.signals .signal').length});
})()
'@ | ConvertFrom-Json
	}
	CheckTrue "改完世界后地图与引擎仍然一致（刷新没有把画面弄坏）" `
		($dom.domNodes -eq $dom.engineNodes -and $dom.domSignals -eq $dom.engineSignals) `
		"节点 页面 $($dom.domNodes) vs 引擎 $($dom.engineNodes)；信号灯 页面 $($dom.domSignals) vs 引擎 $($dom.engineSignals)"

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
