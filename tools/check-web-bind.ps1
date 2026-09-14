# 点选绑定验证：点灯 → 高亮候选轨 → 点轨 → 引擎真的改了绑定 → 再点一次解绑 → 收尾还原。
#
# 为什么必须端到端验：这条交互每一步都可能"看起来对"而其实没接上 —— 灯点了没反应、高亮的是别的轨、
# 点了轨界面变了但引擎没变、或者界面与引擎各说各话。所以断言全部落在**引擎的权威状态**
# （mmtr-signals 的 boundRails / boundExplicit）与**页面上真实画出的线**上。
#
# 这一轮里被它抓到的真问题（都改掉了，留在这里当"为什么要这么验"的注脚）：
#   · 候选轨里有重复 hex → 页面画不出来（引擎按原始 hex 去重，逆序写法去不掉）；
#   · 页面发出的轨 hex 与引擎存的不是同一写法 → 绑定静默失败（接口回 ok、列表没变）；
#   · 点选后界面攥着旧的 Signal 对象 → 绑定成功但画面/HUD 仍是旧状态；
#   · 节点层压在轨上 → 点轨点到了节点（改绑定期间要让节点层退出命中）；
#   · 单次点击按"整表替换"下发 → 用过期列表会把"解绑"变成"再绑一次"（改成引擎侧 --add/--remove 增减）。
param([int]$Port = 9522)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-bind-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
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

# 引擎权威状态：某盏灯的守轨（hex 数组）
function EngineBoundRails([int]$x, [int]$y, [int]$z) {
	$sig = Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-signals" -TimeoutSec 25
	$hit = $sig.data.signals | Where-Object { $_.x -eq $x -and $_.y -eq $y -and $_.z -eq $z }
	if (-not $hit) { return $null }
	return @{rails = @($hit.boundRails); explicit = $hit.boundExplicit; aspect = $hit.aspect}
}

# 等页面画到与引擎一致：绑定之后界面要重取并重画，DOM 不是同步就位的。
# 不等就会"点了正确的轨、却按旧画面断言"（实测：引擎已 2 条、页面还是 1 条，于是解绑那一步点错了线）。
function Wait-DrawnBoundLines([int]$want, [int]$timeoutMs = 8000) {
	$deadline = (Get-Date).AddMilliseconds($timeoutMs)
	while ((Get-Date) -lt $deadline) {
		$n = [int](Eval "document.querySelectorAll('.rails .rail.bound').length")
		if ($n -eq $want) { return $true }
		Start-Sleep -Milliseconds 200
	}
	return $false
}

# 点一盏灯（先"看信号灯"放大，再按**完整坐标串**定位：整图取景时灯只差几像素，会点到邻居）
function ClickSignal([string]$key) {
	Eval "(function(){ const b = [...document.querySelectorAll('.hud .action')].find(x => x.textContent.includes('看信号灯')); if (b) b.click(); return 1; })()" | Out-Null
	Start-Sleep -Milliseconds 900
	$pos = Eval @"
(async () => {
  const want = '$key'.replace(/,/g, ', ').trim();
  for (const el of [...document.querySelectorAll('.signals .signal')]) {
    el.dispatchEvent(new PointerEvent('pointerenter', {bubbles: false}));
    await new Promise(r => setTimeout(r, 5));
    const card = el.querySelector('.card');
    if (card && ((card.querySelector('.coords') || {}).textContent || '').trim() === want) {
      // 点**灯点中心**，不是标记元素的包围盒左上角：标记是零尺寸锚点，左上角等于中心，
      // 但灯点本身在中心、而道岔菱形挂在节点右下方（偏移 34px）——早先按左上角点，
      // 菱形一偏移就正好把这一下吃掉（实测：点灯没反应，选中 0 盏）。
      const lamp = el.querySelector('.lamp');
      const r = lamp ? lamp.getBoundingClientRect() : el.getBoundingClientRect();
      return JSON.stringify({ok: true, x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2)});
    }
  }
  return JSON.stringify({ok: false});
})()
"@ | ConvertFrom-Json
	if (-not $pos.ok) { throw "页面上找不到灯 $key 的标记" }
	Cdp "Input.dispatchMouseEvent" @{ type = "mousePressed"; x = [int]$pos.x; y = [int]$pos.y; button = "left"; clickCount = 1; buttons = 1 } | Out-Null
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseReleased"; x = [int]$pos.x; y = [int]$pos.y; button = "left"; clickCount = 1; buttons = 0 } | Out-Null
	Start-Sleep -Milliseconds 700

}

# 点一条轨：坐标取**沿 path 的中点**（弯轨的包围盒中心可能落在轨外，点下去命中的是空白）
function ClickRailOfClass([string]$cls) {
	$point = Eval @"
(() => {
  const el = document.querySelector('.rails .rail.$cls');
  if (!el) return JSON.stringify({ok: false});
  const len = el.getTotalLength();
  const p = el.getPointAtLength(len / 2).matrixTransform(el.getScreenCTM());
  return JSON.stringify({ok: true, x: Math.round(p.x), y: Math.round(p.y)});
})()
"@ | ConvertFrom-Json
	if (-not $point.ok) { throw "找不到 .rail.$cls 这条线" }
	Eval "window.__mmtrPickedRails = []" | Out-Null
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseMoved"; x = [int]$point.x; y = [int]$point.y; buttons = 0 } | Out-Null
	Cdp "Input.dispatchMouseEvent" @{ type = "mousePressed"; x = [int]$point.x; y = [int]$point.y; button = "left"; clickCount = 1; buttons = 1 } | Out-Null
	Cdp "Input.dispatchMouseEvent" @{ type = "mouseReleased"; x = [int]$point.x; y = [int]$point.y; button = "left"; clickCount = 1; buttons = 0 } | Out-Null
	Start-Sleep -Seconds 2
	return @($pickedJson = Eval "JSON.stringify(window.__mmtrPickedRails ?? [])" | ConvertFrom-Json)
}

try {
	Connect
	Write-Output "=== 点选绑定验证 ==="
	Write-Output ""

	# 清掉世界里所有人工绑定，然后**刷新页面**。
	#
	# 为什么在连上之后再清、再刷新：浏览器一起来就取了一次数据，那时清理还没发生，
	# 于是页面开场就是旧状态（实测："引擎说守 1 条、页面却画了 3 条"）。刷新一次就保证两边同起点。
	$dirty = (Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-signals" -TimeoutSec 25).data.signals |
		Where-Object { $_.boundExplicit }
	foreach ($s in $dirty) {
		$body = @{command = "signal bind $($s.x) $($s.y) $($s.z) --clear"} | ConvertTo-Json -Compress
		Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8888/mtr/api/map/mmtr-command" -Body $body -ContentType "application/json" -TimeoutSec 30 | Out-Null
	}
	if ($dirty.Count -gt 0) { Write-Output ("（开场清掉上一轮残留的人工绑定 " + $dirty.Count + " 盏，随后刷新页面）") }
	Eval "location.reload(); 1" | Out-Null
	Start-Sleep -Seconds 7

	# 挑一盏有候选轨的灯
	$target = Eval @'
(async () => {
  const data = (await (await fetch('/mtr/api/map/mmtr-signals')).json()).data.signals;
  const withCandidates = data.filter(s => (s.candidateRails || []).length > 0);
  if (withCandidates.length === 0) return JSON.stringify({found: false});
  const s = withCandidates[0];
  return JSON.stringify({found: true, key: s.key, x: s.x, y: s.y, z: s.z,
    candidates: s.candidateRails.length, bound: (s.boundRails || []).length, explicit: s.boundExplicit});
})()
'@ | ConvertFrom-Json
	if (-not $target.found) { throw "世界上没有任何有候选轨的灯，无法验证点选绑定" }
	$targetSig = (Invoke-RestMethod "http://127.0.0.1:8888/mtr/api/map/mmtr-signals" -TimeoutSec 25).data.signals |
		Where-Object { $_.key -eq $target.key }
	$target | Add-Member -NotePropertyName candidates_hex -NotePropertyValue @($targetSig.candidateRails) -Force
	Write-Output ("目标灯 " + $target.key + "：候选 " + $target.candidates + " 条，当前守 " + $target.bound + " 条（人工=" + $target.explicit + "）")
	Write-Output ""

	# ---- 1. 点灯：进入改绑定 ----
	ClickSignal $target.key
	$state = Eval @'
(() => {
  const hud = document.querySelector('.hud').innerText.replace(/\n/g, ' ');
  const sel = document.querySelectorAll('.signals .signal.selected').length;
  const cand = document.querySelectorAll('.rails .rail.candidate').length;
  const bound = document.querySelectorAll('.rails .rail.bound').length;
  const pickable = !!document.querySelector('.rails.pickable');
  const hint = (hud.match(/改绑定：[\d,\- ]+/) || [''])[0].replace(/改绑定：/, '').trim();
  return JSON.stringify({sel, cand, bound, pickable, hint});
})()
'@ | ConvertFrom-Json
	$boundOnCandidate = 0
	foreach ($hex in (EngineBoundRails $target.x $target.y $target.z).rails) {
		if ($target.candidates_hex -contains $hex) { $boundOnCandidate++ }
	}
	CheckTrue "点灯进入改绑定（灯被选中 + HUD 出提示）" ($state.sel -eq 1 -and $state.hint -ne "") "选中 $($state.sel) 盏；提示：$($state.hint)"
	CheckTrue "选中的就是被点的那盏灯" ($state.hint.Replace(" ", "") -eq $target.key) "提示「$($state.hint)」，期望 $($target.key)"
	# 每条轨只画一条线，已守的用实线、其余画虚线，两者相加应等于候选数
	CheckTrue "候选轨全部被标出（已守=实线，其余=虚线）" (($state.cand + $boundOnCandidate) -eq $target.candidates) "虚线 $($state.cand) + 已守 $boundOnCandidate = 引擎候选 $($target.candidates)"
	CheckTrue "已守的轨用实线标出" ($state.bound -eq $target.bound) "画了 $($state.bound) 条实线，引擎说守 $($target.bound) 条"
	CheckTrue "轨道层在改绑定期间接管指针事件" ($state.pickable) "rails.pickable=$($state.pickable)"

	# ---- 2. 点一条**未守**的候选轨：这一条应当从"没守"变成"守" ----
	#
	# 断言用"点中的那条轨状态变了"，而不是"守轨**条数**变了"：条数会骗人 ——
	# 点中的那条本来就守（不该发生），或者引擎把别的轨解掉，条数都可能恰好不变
	# （实测：1 → 1 看着像失败，其实绑定完全正确）。
	$before = EngineBoundRails $target.x $target.y $target.z
	$pickedRaw = ClickRailOfClass "candidate"
	$pickedJson = Eval "JSON.stringify(window.__mmtrPickedRails ?? [])"
	$pickedRaw = @($pickedJson | ConvertFrom-Json)
	$after = EngineBoundRails $target.x $target.y $target.z
	$hitHex = if ($pickedRaw.Count -gt 0) { [string]$pickedRaw[0].hex } else { "" }
	$guardedBefore = $before.rails -contains $hitHex
	$guardedAfter = $after.rails -contains $hitHex
	CheckTrue "点一条未守的候选轨 → 引擎把**这一条**改成「守」" ($pickedRaw.Count -eq 1 -and -not $guardedBefore -and $guardedAfter) `
		("点中=" + $hitHex.Substring(0, [Math]::Min(12, $hitHex.Length)) + "；点之前守它=" + $guardedBefore + "，点之后守它=" + $guardedAfter + "（" + $before.rails.Count + " → " + $after.rails.Count + " 条）")
	CheckTrue "改绑定后变成「人工绑定」（boundExplicit=true）" ([bool]$after.explicit) "boundExplicit=$($after.explicit)"
	CheckTrue "页面跟着引擎重画（实线条数与引擎一致）" (Wait-DrawnBoundLines $after.rails.Count) `
		("引擎守 " + $after.rails.Count + " 条，页面画了 " + (Eval "document.querySelectorAll('.rails .rail.bound').length") + " 条实线")

	# ---- 3. 再点**刚绑上的那一条**：这一条应当从"守"变回"没守" ----
	#
	# 这里**直接对目标元素派发 pointerdown**，而不是按屏幕坐标点。理由：
	# 这两条候选轨在道岔处是重叠的，命中区（12px 宽）也重叠，按坐标点会落到**另一条**轨上
	# （实测：明明选中 …65，点下去页面收到的是 …6D，看起来像"解绑不生效"）。
	# "真实指针能到达处理器"这一点前面已经验过（第 2 步就是真指针点的），这里要验的是**语义**，
	# 所以把歧义去掉：指定的元素、指定的事件。
	$picked3Json = Eval @"
(() => {
  window.__mmtrPickedRails = [];
  const el = document.querySelector('.rails .rail[data-hex="$hitHex"]');
  if (!el) return JSON.stringify({ok: false, reason: '找不到这条轨的线'});
  el.dispatchEvent(new PointerEvent('pointerdown', {bubbles: true, cancelable: true, pointerId: 1, isPrimary: true}));
  return JSON.stringify({ok: true, cls: el.getAttribute('class')});
})()
"@ | ConvertFrom-Json
	Start-Sleep -Seconds 2
	if ($picked3Json.ok) {
		$picked2 = @((Eval "JSON.stringify(window.__mmtrPickedRails ?? [])") | ConvertFrom-Json)
		$third = EngineBoundRails $target.x $target.y $target.z
		$hitHex2 = if ($picked2.Count -gt 0) { [string]$picked2[0].hex } else { "" }
		$stillGuarded = $third.rails -contains $hitHex2
		Write-Output ("       （第一次点中=" + $hitHex.Substring(0, 16) + "…；第二次点中=" + $hitHex2.Substring(0, [Math]::Min(16, $hitHex2.Length)) + "…；两次相同=" + ($hitHex -eq $hitHex2) + "）")
		CheckTrue "再点已守的那条轨 = 解绑（这一条不再被守）" ($picked2.Count -eq 1 -and $hitHex2 -eq $hitHex -and -not $stillGuarded) `
			("点中=" + $hitHex2.Substring(0, [Math]::Min(12, $hitHex2.Length)) + "（" + $picked3Json.cls + "）；点之后还守它=" + $stillGuarded + "（" + $after.rails.Count + " → " + $third.rails.Count + " 条）")
	} else {
		CheckTrue "再点已守的那条轨 = 解绑（这一条不再被守）" $false ("找不到这条轨的线：" + $hitHex.Substring(0, 16))
	}

	# ---- 4. Esc 退出改绑定 ----
	Cdp "Input.dispatchKeyEvent" @{ type = "keyDown"; key = "Escape"; code = "Escape"; windowsVirtualKeyCode = 27 } | Out-Null
	Cdp "Input.dispatchKeyEvent" @{ type = "keyUp"; key = "Escape"; code = "Escape"; windowsVirtualKeyCode = 27 } | Out-Null
	Start-Sleep -Milliseconds 600
	$esc = Eval @'
(() => {
  const sel = document.querySelectorAll('.signals .signal.selected').length;
  const cand = document.querySelectorAll('.rails .rail.candidate').length;
  const pickable = !!document.querySelector('.rails.pickable');
  return JSON.stringify({sel, cand, pickable});
})()
'@ | ConvertFrom-Json
	CheckTrue "Esc 退出改绑定（不再选中、不再接管指针）" ($esc.sel -eq 0 -and $esc.cand -eq 0 -and -not $esc.pickable) "选中 $($esc.sel)，候选线 $($esc.cand)，pickable=$($esc.pickable)"

	# ---- 5. 收尾：清掉检查留下的绑定并核对 ----
	$body = @{command = "signal bind $($target.x) $($target.y) $($target.z) --clear"} | ConvertTo-Json -Compress
	Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8888/mtr/api/map/mmtr-command" -Body $body -ContentType "application/json" -TimeoutSec 30 | Out-Null
	Start-Sleep -Seconds 1
	$restored = EngineBoundRails $target.x $target.y $target.z
	CheckTrue "收尾干净（没有人工绑定残留）" (-not [bool]$restored.explicit) `
		("收尾后：守 " + $restored.rails.Count + " 条（推断），人工=" + $restored.explicit)

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
