# 地图方向验证：世界 z 增大必须**往下**（屏幕上 y 更大），x 增大往右。
#
# 为什么要机器验：方向反了在没参照物时"看着也挺正常"，是最容易漏掉的一类问题（用户就是凭感觉发现的）。
# 判据用**世界坐标与屏幕坐标的单调性**，与画风无关：
#   同一 x 上，世界 z 越大 → 屏幕 y 越大（往下）；同一 z 上，世界 x 越大 → 屏幕 x 越大（往右）。
param([int]$Port = 9524)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-orient-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$proc = Start-Process -FilePath $edge -PassThru -ArgumentList @(
	"--headless=new", "--disable-gpu", "--remote-debugging-port=$Port", "--user-data-dir=$profile",
	"--no-first-run", "--no-default-browser-check", "--window-size=1400,900",
	"http://127.0.0.1:8888/index.html"
)
$script:n = 1; $script:ws = $null
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
	if (-not $task.Wait(15000)) { throw "CDP 超时" }
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
	Write-Output "=== 地图方向验证 ==="
	Write-Output ""

	# 节点标记上带的是"世界坐标"（悬停卡片里的 coords），位置是屏幕坐标；
	# 这里直接用页面自己的映射函数算（window.__mmtrView 暴露的 view 不含映射，所以用信号灯的 DOM 位置 + 引擎世界坐标对账）
	$result = Eval @'
(async () => {
  const signals = (await (await fetch('/mtr/api/map/mmtr-signals')).json()).data.signals;
  // 用标记上的 data-key 与世界坐标**逐盏**配对：DOM 顺序与引擎顺序不保证一致（实测按顺序配会出错，
  // 于是把正常的方向判成"反的"——这类"检查脚本自己错"最费时间，所以配对必须靠 key）。
  const pairs = [];
  for (const el of document.querySelectorAll('.signals .signal[data-key]')) {
    const key = el.dataset.key;
    const s = signals.find(item => item.key === key);
    const t = el.style.transform.match(/translate\(([-\d.]+)px,\s*([-\d.]+)px\)/);
    if (s && t) {
      pairs.push({key, worldX: s.x, worldZ: s.z, screenX: parseFloat(t[1]), screenY: parseFloat(t[2])});
    }
  }
  return JSON.stringify({count: pairs.length, pairs});
})()
'@ | ConvertFrom-Json

	if ($result.count -eq 0) {
		throw "页面上找不到带 data-key 的信号灯标记（无法核对方向）"
	}
	Write-Output ("按 key 配对成功 " + $result.count + " 盏灯")
	Write-Output ""

	$pairs = @($result.pairs)
	# 同一个世界列上会有**两盏灯**（MTR 的灯方块两格高，登记时两个格子都有条目，实测 y=-59 与 y=-60），
	# 它们在平面图上是同一个点、屏幕坐标也必然相同 —— 那是"没有大小关系"，不是"方向反了"。
	# 所以先按 (x, z) 去重再比单调性，否则会把正常情况判成失败（实测 60 对里 7 对都是这种）。
	$pairs = @($pairs | Group-Object { "" + $_.worldX + "," + $_.worldZ } | ForEach-Object { $_.Group | Select-Object -First 1 })
	Write-Output ("配对了 " + $result.count + " 盏灯，按世界列去重后 " + $pairs.Count + " 个平面位置")
	Write-Output ""

	# 判据 1：同一 x 上，世界 z 越大 → 屏幕 y 越大（往下）
	#
	# 违例要把**具体哪两盏**打出来：只报个数字的话，下一轮还是只能猜（实测被这一点拖过一轮）。
	$byX = $pairs | Group-Object worldX | Where-Object { $_.Count -ge 2 }
	$zViolations = @(); $zChecks = 0; $sampleZ = ""
	foreach ($group in $byX) {
		$sorted = @($group.Group | Sort-Object worldZ)
		for ($i = 0; $i -lt $sorted.Count - 1; $i++) {
			$zChecks++
			if ($sorted[$i + 1].screenY -le $sorted[$i].screenY) {
				$zViolations += ("z=" + $sorted[$i].worldZ + "→" + $sorted[$i + 1].worldZ + " 的屏幕 y=" + [Math]::Round($sorted[$i].screenY) + "→" + [Math]::Round($sorted[$i + 1].screenY) + "（" + $sorted[$i].key + " / " + $sorted[$i + 1].key + "）")
			}
		}
		if (-not $sampleZ) {
			$sampleZ = "x=" + $group.Name + " 上 z=" + (($sorted | ForEach-Object { $_.worldZ }) -join ",") + " 对应屏幕 y=" + (($sorted | ForEach-Object { [Math]::Round($_.screenY) }) -join ",")
		}
	}
	CheckTrue "世界 z 增大 → 屏幕往下（方向与游戏俯视一致）" ($zChecks -gt 0 -and $zViolations.Count -eq 0) ("同一 x 上比了 " + $zChecks + " 对，违例 " + $zViolations.Count + "；样例：" + $sampleZ + $(if ($zViolations.Count -gt 0) { "；违例：" + ($zViolations | Select-Object -First 3 | ForEach-Object { "`n         " + $_ }) } else { "" }))

	# 判据 2：同一 z 上，世界 x 越大 → 屏幕 x 越大（往右）
	$byZ = $pairs | Group-Object worldZ | Where-Object { $_.Count -ge 2 }
	$xViolations = @(); $xChecks = 0; $sampleX = ""
	foreach ($group in $byZ) {
		$sorted = @($group.Group | Sort-Object worldX)
		for ($i = 0; $i -lt $sorted.Count - 1; $i++) {
			$xChecks++
			if ($sorted[$i + 1].screenX -le $sorted[$i].screenX) {
				$xViolations += ("x=" + $sorted[$i].worldX + "→" + $sorted[$i + 1].worldX + " 的屏幕 x=" + [Math]::Round($sorted[$i].screenX) + "→" + [Math]::Round($sorted[$i + 1].screenX) + "（" + $sorted[$i].key + " / " + $sorted[$i + 1].key + "）")
			}
		}
		if (-not $sampleX) {
			$sampleX = "z=" + $group.Name + " 上 x=" + (($sorted | ForEach-Object { $_.worldX }) -join ",") + " 对应屏幕 x=" + (($sorted | ForEach-Object { [Math]::Round($_.screenX) }) -join ",")
		}
	}
	CheckTrue "世界 x 增大 → 屏幕往右" ($xChecks -gt 0 -and $xViolations.Count -eq 0) ("同一 z 上比了 " + $xChecks + " 对，违例 " + $xViolations.Count + "；样例：" + $sampleX + $(if ($xViolations.Count -gt 0) { "；违例：" + ($xViolations | Select-Object -First 3 | ForEach-Object { "`n         " + $_ }) } else { "" }))

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
