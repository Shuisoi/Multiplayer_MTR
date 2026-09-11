# 连线验证：线型规则 + "简化曲线方向正确"。
#
# 规则（用户要求）：
#   1. x 或 z 任一相同 → 直线；
#   2. 其余 → 曲线，但**方向正确的简化版**（不照搬真实几何）。
#
# 判据分两部分：
#   · 页面上量渲染出来的几何（线型、弯曲幅度上限）；
#   · 跟引擎接口的真实采样点对比**弯向符号** —— 这是"方向正确"的唯一硬判据。
param([int]$Port = 9410)

$ErrorActionPreference = "Stop"
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$profile = Join-Path $env:TEMP ("mmtr-rail-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
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
				Start-Sleep -Seconds 4
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
	$r = Cdp "Runtime.evaluate" @{ expression = $e; returnByValue = $true }
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
	Write-Output "=== 连线线型 + 简化曲线方向验证 ==="
	Write-Output ""

	# 页面上量：每条 path 的顶点、弦、最大垂距（用 getPointAtLength 采样真实渲染结果）
	$renderedJson = Eval @'
(() => {
  const map = document.querySelector('.map').getBoundingClientRect();
  // 坐标口径统一：**相对视口容器左上角**。
  // getPointAtLength 给的是相对 SVG 的局部坐标，而 SVG 在页面里位于 y=48（上边栏下方），
  // 所以必须加上容器的偏移再和容器矩形比。混口径会得出"所有内容都跑到视口上方"的假结论
  // （实测：报 35 条轨越界、最大 19.69px，其实内容老老实实在容器里）。
  const result = [...document.querySelectorAll('svg.rails path.rail')].map(p => {
    const total = p.getTotalLength();
    const a = p.getPointAtLength(0);
    const b = p.getPointAtLength(total);
    const chord = Math.hypot(b.x - a.x, b.y - a.y);
    const nx = chord > 1e-6 ? -(b.y - a.y) / chord : 0;
    const ny = chord > 1e-6 ? (b.x - a.x) / chord : 0;
    // 沿曲线采样，记录相对弦的偏移（带符号）与到弦的垂距
    let maxSigned = 0;
    let overflow = 0;
    for (let i = 0; i <= 24; i++) {
      const q = p.getPointAtLength(total * i / 24);
      const signed = (q.x - a.x) * nx + (q.y - a.y) * ny;
      if (Math.abs(signed) > Math.abs(maxSigned)) maxSigned = signed;
      const sx = q.x + map.left, sy = q.y + map.top;
      overflow = Math.max(overflow, map.left - sx, sx - map.right, map.top - sy, sy - map.bottom);
    }
    return {
      d: p.getAttribute('d'),
      isCurve: /Q/.test(p.getAttribute('d')),
      dx: Math.round((b.x - a.x) * 100) / 100,
      dy: Math.round((b.y - a.y) * 100) / 100,
      chord: Math.round(chord * 100) / 100,
      signed: Math.round(maxSigned * 100) / 100,
      sagitta: Math.round(Math.abs(maxSigned) * 100) / 100,
      verts: (p.getAttribute('d').match(/-?\d+(\.\d+)?/g) || []).length / 2,
      outside: Math.round(overflow * 100) / 100,
    };
  });
  return JSON.stringify({paths: result, hud: document.querySelector('.hud').innerText.replace(/\n/g, ' ')});
})()
'@
	$rendered = $renderedJson | ConvertFrom-Json
	$paths = @($rendered.paths)
	Write-Output ("渲染出的轨 = " + $paths.Count + " 条")
	Write-Output ("HUD: " + $rendered.hud.Trim())
	Write-Output ""

	$linePaths = @($paths | Where-Object { -not $_.isCurve })
	$curvePaths = @($paths | Where-Object { $_.isCurve })

	$badLines = @($linePaths | Where-Object { -not (([Math]::Abs($_.dx) -lt 0.5) -or ([Math]::Abs($_.dy) -lt 0.5)) })
	CheckTrue "按直线画的轨是轴对齐的（dx 或 dy ≈ 0）" ($badLines.Count -eq 0) ("直线 " + $linePaths.Count + " 条，其中不走轴的 " + $badLines.Count + " 条")
	CheckTrue "直线只有两个顶点（没有多余的采样点）" ((@($linePaths | Where-Object { $_.verts -ne 2 }).Count) -eq 0) ("顶点数不等于 2 的直线 " + (@($linePaths | Where-Object { $_.verts -ne 2 }).Count) + " 条")

	$axisCurves = @($curvePaths | Where-Object { $_.chord -gt 1 -and (([Math]::Abs($_.dx) -lt (0.005 * $_.chord)) -or ([Math]::Abs($_.dy) -lt (0.005 * $_.chord))) })
	CheckTrue "按曲线画的轨是斜向的（两轴都有分量）" ($axisCurves.Count -eq 0) ("走轴却画成曲线的 " + $axisCurves.Count + " 条")

	# 曲线的"可辨识度"按**弦长比例**判，不按绝对像素。
	# 这个缩放下整个 1600 格的线路只有 554px 高，曲线弦长中位数 7.3px——一条弦长 7px 的轨
	# 弯 0.3px 是几何事实（矢高只占弦长 4%），不是渲染 bug。用绝对像素阈值会把"这个尺度下
	# 本来就看不出来"误判成"没画曲线"（实测把 23 条正常曲线判成失败）。
	$noBend = @($curvePaths | Where-Object { $_.chord -gt 3 -and $_.sagitta -le (0.02 * $_.chord) })
	CheckTrue "曲线都有弯曲（矢高 &gt; 弦长的 2%）" ($noBend.Count -eq 0) ("没有弯曲的曲线 " + $noBend.Count + " 条")
	$visibleCurves = @($curvePaths | Where-Object { $_.sagitta -ge 0.5 }).Count
	Write-Output ("       曲线 " + $curvePaths.Count + " 条，其中屏幕弯曲量 &ge; 0.5px 的 " + $visibleCurves + " 条（其余在当前缩放下小于半个像素，几何上仍是曲线）")

	$ratios = @($curvePaths | ForEach-Object { [Math]::Round(100 * $_.sagitta / $_.chord, 1) })
	$ratioRange = if ($ratios.Count -gt 0) { "$(($ratios | Measure-Object -Minimum).Minimum)%..$(($ratios | Measure-Object -Maximum).Maximum)%" } else { "（无曲线）" }
	$tooBent = @($curvePaths | Where-Object { $_.chord -gt 1 -and $_.sagitta -gt (0.19 * $_.chord) })
	CheckTrue "弯曲幅度是简化的（矢高 &le; 弦长的 19%）" ($tooBent.Count -eq 0) ("过于夸张的 " + $tooBent.Count + " 条；矢高占弦长比例 " + $ratioRange)

	# ---- "方向正确"：取引擎真实采样点，用**同一个屏幕坐标空间**逐条比弯向符号 ----
	# 在页面里做：把真实采样点按当前摄像机投影到屏幕（和渲染用的是同一套映射与同一组端点），
	# 再分别算"渲染曲线"和"真实轨道"相对弦的偏移符号。符号一致 = 方向正确。
	# 这样避免了跨语言重复实现投影与平面坐标约定（那是很容易把符号搞反的地方）。
	$bendJson = Eval @'
(async () => {
  const data = await (await fetch('/mtr/api/map/mmtr-topology')).json();
  const rails = data.data.rails;
  // 直接用**渲染所用的那一份摄像机**（页面无条件挂出 __mmtrView）。
  // 不要自己从端点反推比例：那需要浮点匹配，容差一紧就漏轨、一松就错配，
  // 曾经因此得出一条假的"弯向不符"。
  const cam = window.__mmtrView().camera;
  const toScreen = (wx, wz) => ({x: (wx - cam.originX) * cam.scale, y: ((-wz) - cam.originY) * cam.scale});
  const parse = d => { const n = d.match(/-?\d+(\.\d+)?/g).map(Number); const p=[]; for (let i=0;i+1<n.length;i+=2) p.push({x:n[i],y:n[i+1]}); return p; };
  const els = [...document.querySelectorAll('svg.rails path.rail')];
  let compared = 0, mismatch = [], unmatched = 0;
  for (const rail of rails) {
    if (Math.abs(rail.x1 - rail.x2) < 0.05 || Math.abs(rail.z1 - rail.z2) < 0.05) continue; // 直线不参与弯向比对
    if (!rail.path || rail.path.length < 3) continue;
    const a = toScreen(rail.x1, rail.z1), b = toScreen(rail.x2, rail.z2);
    const el = els.find(p => {
      const q = parse(p.getAttribute('d')); const s = q[0], e = q[q.length-1];
      return (Math.hypot(s.x-a.x, s.y-a.y) < 0.6 && Math.hypot(e.x-b.x, e.y-b.y) < 0.6)
          || (Math.hypot(s.x-b.x, s.y-b.y) < 0.6 && Math.hypot(e.x-a.x, e.y-a.y) < 0.6);
    });
    if (!el) { unmatched++; continue; }
    const q = parse(el.getAttribute('d'));
    const s = q[0], e = q[q.length-1];
    const chord = Math.hypot(e.x-s.x, e.y-s.y);
    if (chord < 0.5) continue; // 屏幕上不足半像素的轨，弯向无法测量
    const nx = -(e.y-s.y)/chord, ny = (e.x-s.x)/chord;
    // 真实轨道：采样点相对弦的最大偏移（带符号）
    let maxS = 0;
    for (const pt of rail.path) {
      const sp = toScreen(pt[0], pt[2]);
      const signed = (sp.x-s.x)*nx + (sp.y-s.y)*ny;
      if (Math.abs(signed) > Math.abs(maxS)) maxS = signed;
    }
    if (Math.abs(maxS) < 0.05) continue; // 真实轨道几乎是直的，弯向无意义
    // 渲染曲线：二次贝塞尔的中点（= 曲线最弯处）
    const mid = el.getPointAtLength(el.getTotalLength()/2);
    const renderedSigned = (mid.x-s.x)*nx + (mid.y-s.y)*ny;
    // 弯曲量小于 0.02px 时符号是数值噪声，不计入比对（几何上仍算通过）
    if (Math.abs(renderedSigned) < 0.02) continue;
    compared++;
    if (Math.sign(maxS) !== Math.sign(renderedSigned)) {
      mismatch.push(`(${rail.x1},${rail.z1})→(${rail.x2},${rail.z2}) 弦长${chord.toFixed(1)}px 真实 ${Math.sign(maxS)} vs 渲染 ${Math.sign(renderedSigned)}`);
    }
  }
  return JSON.stringify({compared, mismatch: mismatch.length, mismatchDetail: mismatch, unmatched});
})()
'@
	$bend = $bendJson | ConvertFrom-Json
	$detail = if ($bend.mismatchDetail -and @($bend.mismatchDetail).Count -gt 0) { "：" + (@($bend.mismatchDetail) -join " / ") } else { "" }
	CheckTrue "曲线的弯向与真实轨道一致（逐条比对符号）" ([int]$bend.mismatch -eq 0) ("比对 " + $bend.compared + " 条，未匹配 DOM " + $bend.unmatched + " 条，弯向不符 " + $bend.mismatch + " 条" + $detail)

	# 端点是节点位置，节点圆点本身有半径，所以留 1px 容差
	$outside = @($paths | Where-Object { $_.outside -gt 1 })
	CheckTrue "所有轨都在视口容器内" ($outside.Count -eq 0) ("越界的 " + $outside.Count + " 条，最大越界 " + (($paths | Measure-Object outside -Maximum).Maximum) + "px")

	$hudLine = [int]([regex]::Match($rendered.hud, "直线\s+(\d+)").Groups[1].Value)
	$hudArc = [int]([regex]::Match($rendered.hud, "曲线\s+(\d+)").Groups[1].Value)
	CheckTrue "HUD 的直线/曲线条数与实际渲染一致" (($hudLine -eq $linePaths.Count) -and ($hudArc -eq $curvePaths.Count)) ("HUD " + $hudLine + "/" + $hudArc + "  vs 实际 " + $linePaths.Count + "/" + $curvePaths.Count)

	Write-Output ""
	Write-Output "曲线样例（弦长 / 矢高 / 占弦长比例 / 弯向）："
	$curvePaths | Sort-Object { -$_.chord } | Select-Object -First 5 | ForEach-Object {
		Write-Output ("  弦长 " + $_.chord + "  矢高 " + $_.sagitta + "（" + [Math]::Round(100 * $_.sagitta / $_.chord, 1) + "%）  弯向 " + $_.signed)
	}

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
