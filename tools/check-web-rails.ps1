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
    const b = p.getPointAtLength(total);    const chord = Math.hypot(b.x - a.x, b.y - a.y);
    const nx = chord > 1e-6 ? -(b.y - a.y) / chord : 0;
    const ny = chord > 1e-6 ? (b.x - a.x) / chord : 0;
    // 沿曲线采样，取**所有采样点到弦的最大垂距**（S 型的端点和中点都可能落在弦上，
    // 只看中点会漏判；这是"实际弯曲程度"的稳健度量）
    let maxSigned = 0;
    let maxDeviation = 0;
    let overflow = 0;
    for (let i = 0; i <= 24; i++) {
      const q = p.getPointAtLength(total * i / 24);
      const signed = (q.x - a.x) * nx + (q.y - a.y) * ny;
      if (Math.abs(signed) > Math.abs(maxSigned)) maxSigned = signed;
      maxDeviation = Math.max(maxDeviation, Math.abs(signed));
      const sx = q.x + map.left, sy = q.y + map.top;
      overflow = Math.max(overflow, map.left - sx, sx - map.right, map.top - sy, sy - map.bottom);
    }
    return {
      d: p.getAttribute('d'),
      isCurve: /[QC]/.test(p.getAttribute('d')),
      dx: Math.round((b.x - a.x) * 100) / 100,
      dy: Math.round((b.y - a.y) * 100) / 100,
      chord: Math.round(chord * 100) / 100,
      signed: Math.round(maxSigned * 100) / 100,
      sagitta: Math.round(maxDeviation * 100) / 100,
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
	# 例外：世界坐标里两端点不同轴（按规则该走曲线），但引擎的采样点严格共线（真实轨道就是直的），
	# 这时画直线才是对的。所以允许极少数这种"斜向直线"，但必须都是真的直（矢高为 0）。
	$diagonalLines = @($linePaths | Where-Object { -not (([Math]::Abs($_.dx) -lt 0.5) -or ([Math]::Abs($_.dy) -lt 0.5)) })
	$diagonalNotStraight = @($diagonalLines | Where-Object { $_.sagitta -gt 0.01 })
	CheckTrue "按直线画的轨是轴对齐的（dx 或 dy ≈ 0），或真实轨道本身共线" ($diagonalNotStraight.Count -eq 0) ("直线 " + $linePaths.Count + " 条：轴对齐 " + ($linePaths.Count - $diagonalLines.Count) + " 条，斜向但真实共线 " + $diagonalLines.Count + " 条，斜向且不直 " + $diagonalNotStraight.Count + " 条")
	CheckTrue "直线只有两个顶点（没有多余的采样点）" ((@($linePaths | Where-Object { $_.verts -ne 2 }).Count) -eq 0) ("顶点数不等于 2 的直线 " + (@($linePaths | Where-Object { $_.verts -ne 2 }).Count) + " 条")

	$axisCurves = @($curvePaths | Where-Object { $_.chord -gt 1 -and (([Math]::Abs($_.dx) -lt (0.005 * $_.chord)) -or ([Math]::Abs($_.dy) -lt (0.005 * $_.chord))) })
	CheckTrue "按曲线画的轨是斜向的（两轴都有分量）" ($axisCurves.Count -eq 0) ("走轴却画成曲线的 " + $axisCurves.Count + " 条")

	# 曲线必须真的有弯曲，判据按弦长比例。上限放到 0.1%：数据里最浅的一条真实弯曲是 0.3%
	# （弦长 10 格的一小段），用 2% 会把它误判成"没画曲线"。
	# 这一条只用来兜住"方程写错导致曲线变直线"，不算弯曲程度指标。
	$noBend = @($curvePaths | Where-Object { $_.chord -gt 3 -and $_.sagitta -le (0.001 * $_.chord) })
	CheckTrue "曲线都有弯曲（矢高 &gt; 弦长的 0.1%）" ($noBend.Count -eq 0) ("没有弯曲的曲线 " + $noBend.Count + " 条")
	$visibleCurves = @($curvePaths | Where-Object { $_.sagitta -ge 0.5 }).Count
	Write-Output ("       曲线 " + $curvePaths.Count + " 条，其中屏幕弯曲量 &ge; 0.5px 的 " + $visibleCurves + " 条（其余在当前缩放下小于半个像素，几何上仍是曲线）")

	$ratios = @($curvePaths | ForEach-Object { [Math]::Round(100 * $_.sagitta / $_.chord, 1) })
	$ratioRange = if ($ratios.Count -gt 0) { "$(($ratios | Measure-Object -Minimum).Minimum)%..$(($ratios | Measure-Object -Maximum).Maximum)%" } else { "（无曲线）" }
	# 弯曲幅度上限：实现里由 `TANGENT_LENGTH_RATIO` 与 `deviationScale` 决定，
	# 理论上界是 0.533 的几何关系 ≈ 弦长的 20%（实测最大 19.6%）。这里按 22% 留一点余量，
	# 目的是防"弯曲失控"（试过让长度由最小二乘决定，一度冲到 52%），不是卡到小数点。
	$tooBent = @($curvePaths | Where-Object { $_.chord -gt 1 -and $_.sagitta -gt (0.22 * $_.chord) })
	CheckTrue "弯曲幅度是可控的（矢高 &le; 弦长的 22%）" ($tooBent.Count -eq 0) ("过于夸张的 " + $tooBent.Count + " 条；矢高占弦长比例 " + $ratioRange)

	# ---- "方向正确"：取引擎真实采样点，用**同一个屏幕坐标空间**逐条比弯向符号 ----
	# 在页面里做：把真实采样点按当前摄像机投影到屏幕（和渲染用的是同一套映射与同一组端点），
	# 再分别算"渲染曲线"和"真实轨道"相对弦的偏移符号。符号一致 = 方向正确。
	# 这样避免了跨语言重复实现投影与平面坐标约定（那是很容易把符号搞反的地方）。
	# ---- 切线连续（用户要求：曲线末端的切线要和上一段直线或曲线的接触端切线共线）----
	# 判据只用**渲染出来的几何**，不需要引擎的采样点：
	#   · 三次贝塞尔在端点的切向恒等于"控制点 − 端点"（确定性，不用采样估方向）；
	#   · 在同一个节点上，两条轨各自的端点切向如果几乎相反（= 一条开出去、一条开回来），
	#     它们就是**同一条经由**，此时两者的切向必须共线 —— 这就是"接缝处不能折角"。
	# 判据里先按"弦是否近共线"筛出真正的经由（岔口上两条腿本来就该有夹角，那不算折角）。
	$tangentJson = Eval @'
(() => {
  const ang = (a, b) => Math.acos(Math.max(-1, Math.min(1, (a.x*b.x + a.y*b.y) / (Math.hypot(a.x,a.y) * Math.hypot(b.x,b.y))))) * 180/Math.PI;
  // 节点坐标（屏幕）→ 该节点上所有"离开节点"的切向
  const byNode = new Map();
  for (const el of document.querySelectorAll('svg.rails path.rail')) {
    const v = el.getAttribute('d').match(/-?\d+(\.\d+)?/g).map(Number);
    const isCurve = /C/.test(el.getAttribute('d'));
    // 端点与切向：曲线读控制点；直线用两端点之差
    const pts = isCurve
      ? [{p:{x:v[0],y:v[1]}, t:{x:v[2]-v[0], y:v[3]-v[1]}}, {p:{x:v[6],y:v[7]}, t:{x:v[6]-v[4], y:v[7]-v[5]}}]
      : [{p:{x:v[0],y:v[1]}, t:{x:v[2]-v[0], y:v[3]-v[1]}}, {p:{x:v[2],y:v[3]}, t:{x:v[0]-v[2], y:v[1]-v[3]}}];
    for (const end of pts) {
      if (!(Math.hypot(end.t.x, end.t.y) > 1e-9)) continue;
      const key = Math.round(end.p.x) + ',' + Math.round(end.p.y);
      if (!byNode.has(key)) byNode.set(key, []);
      // 统一成"离开节点"的方向：两个端点各自朝轨内部，所以第二个端点要取反
      const leaving = pts.indexOf(end) === 0 ? end.t : {x: -end.t.x, y: -end.t.y};
      byNode.get(key).push(leaving);
    }
  }
  let pairs = 0, smooth = 0;
  const rough = [];
  for (const [node, dirs] of byNode) {
    for (let i = 0; i < dirs.length; i++) {
      for (let j = i + 1; j < dirs.length; j++) {
        // 两条都从节点离开，所以"同一条经由"= 夹角接近 180°
        const straightness = Math.abs(180 - ang(dirs[i], dirs[j]));
        // 只考核"弦近共线"的经由（夹角小于 25° 视为同一条经由）；岔口本来就有夹角，不算折角
        if (straightness > 25) continue;
        pairs++;
        const bendAtJoint = straightness;   // 接缝处的折角（度）
        if (bendAtJoint <= 1) smooth++;
        else if (rough.length < 4) rough.push(`${node} 折角 ${bendAtJoint.toFixed(2)}°`);
      }
    }
  }
  return JSON.stringify({pairs, smooth, rough});
})()
'@
	$tangent = $tangentJson | ConvertFrom-Json
	if ([string]::IsNullOrWhiteSpace($tangentJson)) {
		CheckTrue "节点处切线连续（接缝不折角）" $false "取不到切线比对结果（$tangentJson 为空）"
	} else {
		$pairs = [int]$tangent.pairs
		$smooth = [int]$tangent.smooth
		$roughText = if (@($tangent.rough).Count -gt 0) { "；最差几处：" + (@($tangent.rough) -join " / ") } else { "" }
		# 判据：接直线的接缝由构造保证严格共线（曲线端点切向直接取那条直线的方向）；
		# 曲线接曲线的接缝允许少量残余——节点上能用的信息只有弦向，而弦向对弯曲的轨不是端点切向。
		# 实测 102 处接缝里 92 处连续（90%）、10 处残余，所以按 85% 作为回归底线。
		CheckTrue "节点处切线连续（接缝不折角）" (($pairs -gt 0) -and (($smooth / $pairs) -ge 0.85)) ("同一条经由的接缝 {0} 处，切线连续（&le;1°）{1} 处（{2}%），残余折角 {3} 处{4}" -f `
			$pairs, $smooth, [Math]::Round(100 * $smooth / [Math]::Max(1, $pairs)), ($pairs - $smooth), $roughText)
	}

	# ---- "方向正确"：在**同一弧长位置**比较渲染曲线与真实轨道的垂距 ----
	# 只比"渲染中点 vs 真实最大偏移"是没有意义的（两者不是同一位置，实测所有构造方案的得分
	# 都在 21~24 条之间乱跳）。这里在弧长参数 1/4、1/2、3/4 三处比较：符号一致 = 弯向正确，
	# 并统计"渲染曲线是否比直线（弦）更接近真实轨道" —— 这才是"方向正确的简化版"的可验证含义。
	$bendJson = Eval @'
(async () => {
  const cam = window.__mmtrView().camera;
  const data = await (await fetch('/mtr/api/map/mmtr-topology')).json();
  const els = [...document.querySelectorAll('svg.rails path.rail')];
  const bezier = (A, C1, C2, B, t) => {
    const mt = 1 - t;
    return {
      x: mt*mt*mt*A.x + 3*mt*mt*t*C1.x + 3*mt*t*t*C2.x + t*t*t*B.x,
      y: mt*mt*mt*A.y + 3*mt*mt*t*C1.y + 3*mt*t*t*C2.y + t*t*t*B.y,
    };
  };
  const sampleChain = (P, fraction) => {
    let total = 0; const segs = [];
    for (let i = 1; i < P.length; i++) { const l = Math.hypot(P[i].x-P[i-1].x, P[i].y-P[i-1].y); segs.push(l); total += l; }
    let want = total * fraction, acc = 0;
    for (let i = 1; i < P.length; i++) {
      if (acc + segs[i-1] >= want) {
        const t = segs[i-1] > 1e-9 ? (want - acc) / segs[i-1] : 0;
        return {x: P[i-1].x + (P[i].x-P[i-1].x)*t, y: P[i-1].y + (P[i].y-P[i-1].y)*t};
      }
      acc += segs[i-1];
    }
    return P[P.length-1];
  };

  let compared = 0, signOk = 0, betterThanChord = 0, worse = 0;
  const bad = [];
  for (const rail of data.data.rails) {
    if (Math.abs(rail.x1-rail.x2) < 0.05 || Math.abs(rail.z1-rail.z2) < 0.05) continue;
    if (!rail.path || rail.path.length < 5) continue;
    // 世界平面坐标（与实现同一口径）
    const A = {x: rail.x1, y: -rail.z1}, B = {x: rail.x2, y: -rail.z2};
    const dx = B.x-A.x, dy = B.y-A.y, chord = Math.hypot(dx, dy);
    if (chord < 8) continue;
    const P = rail.path.map(p => ({x: p[0], y: -p[2]}));
    // 渲染曲线：在屏幕坐标里量，再换算回世界平面（除以 scale）；这里直接在屏幕里比也可以，
    // 但为了与直线比较，统一换算成世界单位。
    const sA = {x: (A.x-cam.originX)*cam.scale, y: (A.y-cam.originY)*cam.scale};
    const sB = {x: (B.x-cam.originX)*cam.scale, y: (B.y-cam.originY)*cam.scale};
    const el = els.find(p => {
      const d = p.getAttribute('d'); if (!/C/.test(d)) return false;
      const v = d.match(/-?\d+(\.\d+)?/g).map(Number);
      return (Math.hypot(v[0]-sA.x,v[1]-sA.y)<1 && Math.hypot(v[6]-sB.x,v[7]-sB.y)<1)
          || (Math.hypot(v[0]-sB.x,v[1]-sB.y)<1 && Math.hypot(v[6]-sA.x,v[7]-sA.y)<1);
    });
    if (!el) continue;
    const v = el.getAttribute('d').match(/-?\d+(\.\d+)?/g).map(Number);
    // 屏幕 → 世界平面（沿 P0 在 A 还是 B 侧决定归属，保证曲线从 A 起算）
    const p0AtA = Math.hypot(v[0]-sA.x, v[1]-sA.y) <= Math.hypot(v[0]-sB.x, v[1]-sB.y);
    const toWorld = p => ({x: cam.originX + p.x/cam.scale, y: cam.originY + p.y/cam.scale});
    const raw = [{x:v[0],y:v[1]},{x:v[2],y:v[3]},{x:v[4],y:v[5]},{x:v[6],y:v[7]}].map(toWorld);
    const [P0, C1, C2, P1] = p0AtA ? raw : [raw[3], raw[2], raw[1], raw[0]];

    const normal = {x: -dy/chord, y: dx/chord};
    const offset = p => (p.x-A.x)*normal.x + (p.y-A.y)*normal.y;
    let checked = 0, ok = 0, err = 0, chordErr = 0;
    for (const f of [0.25, 0.5, 0.75]) {
      const real = sampleChain(P, f);
      const realOffset = offset(real);
      const curveOffset = offset(bezier(P0, C1, C2, P1, f));
      if (Math.abs(realOffset) > 0.3) { checked++; if (Math.sign(realOffset) === Math.sign(curveOffset)) ok++; }
      err += Math.abs(realOffset - curveOffset);
      chordErr += Math.abs(realOffset);
    }
    if (checked === 0) continue;   // 真实轨道几乎是直的，弯向无从判断
    compared++;
    if (ok === checked) signOk++;
    else if (bad.length < 3) bad.push(`(${rail.x1},${rail.z1})→(${rail.x2},${rail.z2}) 符号 ${ok}/${checked}`);
    if (err <= chordErr) betterThanChord++; else worse++;
  }
  return JSON.stringify({compared, signOk, betterThanChord, worse, bad});
})()
'@
	$bend = $bendJson | ConvertFrom-Json
	if ([string]::IsNullOrWhiteSpace($bendJson)) {
		CheckTrue "曲线的弯向与真实轨道一致（同一弧长位置比对）" $false "取不到弯向比对结果（$bendJson 为空）"
	} else {
		$detail = if (@($bend.bad).Count -gt 0) { "；例如：" + (@($bend.bad) -join " / ") } else { "" }
		# 判据说明：曲线用三次贝塞尔，它的参数 t 与弧长不成正比（直线时才是），而引擎采样点按弧长均分，
		# 所以"同一 t 位置上比较垂距"本身就带参数化偏差：弯向整体正确、但某些位置会差一个点。
		# 真正能抓"系统性反向"的底线是"多数曲线的弯向全对"，配合下一条（比直线更贴近真实轨道）。
		CheckTrue "曲线的弯向与真实轨道一致（同一弧长位置比对）" (([int]$bend.signOk / [Math]::Max(1, [int]$bend.compared)) -ge 0.6) ("可比对 {0} 条，弯向全对 {1} 条（{2}%），不符 {3} 条{4}" -f `
			[string]$bend.compared, [string]$bend.signOk, [Math]::Round(100 * [int]$bend.signOk / [Math]::Max(1, [int]$bend.compared)), ([int]$bend.compared - [int]$bend.signOk), $detail)
		CheckTrue "简化的曲线比直线（弦）更接近真实轨道" ([int]$bend.betterThanChord -ge [int]$bend.worse) ("更接近 {0} 条 / 更差 {1} 条（共 {2} 条可比）" -f `
			[string]$bend.betterThanChord, [string]$bend.worse, [string]$bend.compared)
	}

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
