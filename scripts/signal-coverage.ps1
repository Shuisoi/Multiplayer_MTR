# 信号覆盖体检：**按"轨 × 方向"列出哪里没有信号灯**（也就是哪里切不出区间）。
#
# 为什么要它（notes/155 §15 的现场结论）：
#   引擎的方向区间 = "一架灯的走行，到下一架**面向本方向**的灯为止"。所以某条轨在某个方向上
#   **一架面向灯都没有**时，那个方向的走行会一路吞下去 —— 现场出现过 628 m / 26 段轨的"巨块区间"，
#   一台车停在站台上就能把整条线上同方向的车全按红。这不是引擎判错，而是**那个方向没布灯**。
#
# 判据来源（都是引擎自己的口径，不含猜测）：
#   · `mmtr-topology` 给每根轨的端点与它属于哪条"车道"（同一 x）；
#   · `mmtr-sections` 给每个**方向区间**（含 `direction.label` = 南行/北行/东行/西行）与它覆盖哪些轨；
#   · 于是"某轨在某方向有没有区间"= "那个方向有没有面向它的灯"。
#
# 用法：
#   .\scripts\signal-coverage.ps1            # 汇总 + 缺口清单
#   .\scripts\signal-coverage.ps1 -Brief     # 只给汇总与最长缺口
param(
	[switch]$Brief,
	[string]$ServerBase = 'http://127.0.0.1:8888/mtr/api/map/'
)

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')

function Get2([string]$endpoint) {
	(Invoke-WebRequest -Uri ($ServerBase + $endpoint) -TimeoutSec 40 -UseBasicParsing).Content | ConvertFrom-Json
}

$topology = Get2 'mmtr-topology'
$sections = Get2 'mmtr-sections'

# 轨 → 覆盖它的方向集合
$dirsByRail = @{}
foreach ($s in @($sections.data.sections)) {
	$label = $s.direction.label
	foreach ($sp in @($s.spans)) {
		if (-not $dirsByRail.ContainsKey($sp.hex)) { $dirsByRail[$sp.hex] = New-Object System.Collections.Generic.HashSet[string] }
		[void]$dirsByRail[$sp.hex].Add($label)
	}
}

# 车道：同一 x 的一组轨（本世界正线是 x≈-147 / -155 / -170 / -176 四条）
function LaneOf([double]$x1, [double]$x2) { return [Math]::Round((($x1 + $x2) / 2.0)) }

$rows = foreach ($r in @($topology.data.rails)) {
	$dirs = $(if ($dirsByRail.ContainsKey($r.hex)) { @($dirsByRail[$r.hex]) } else { @() })
	# 拓扑接口不带 length，按采样点自己量（与引擎的弧长同量级，够用来判"缺口多长"）
	$len = 0.0
	$pts = @($r.path)
	for ($i = 1; $i -lt $pts.Count; $i++) {
		$dx = [double]$pts[$i][0] - [double]$pts[$i - 1][0]
		$dz = [double]$pts[$i][2] - [double]$pts[$i - 1][2]
		$len += [Math]::Sqrt($dx * $dx + $dz * $dz)
	}
	[pscustomobject]@{
		hex    = $r.hex
		lane   = LaneOf $r.x1 $r.x2
		z1     = [Math]::Min($r.z1, $r.z2)
		z2     = [Math]::Max($r.z1, $r.z2)
		x1     = $r.x1; z1raw = $r.z1; x2 = $r.x2; z2raw = $r.z2
		length = $len
		dirs   = $dirs
		south  = ($dirs -contains '南行')
		north  = ($dirs -contains '北行')
		east   = ($dirs -contains '东行')
		west   = ($dirs -contains '西行')
	}
}

$total = $rows.Count
"轨总数 = $total（车道数 $((@($rows | Group-Object lane)).Count)）"
""
"=== 按车道 × 方向 的覆盖 ==="
$rows | Group-Object lane | Sort-Object Name | ForEach-Object {
	$g = $_.Group
	$n = $g.Count
	"  x={0,6}：{1,3} 根  |  南行 {2,3}/{1}  北行 {3,3}/{1}  东行 {4,3}/{1}  西行 {5,3}/{1}" -f `
		$_.Name, $n, @($g | Where-Object { $_.south }).Count, @($g | Where-Object { $_.north }).Count,
		@($g | Where-Object { $_.east }).Count, @($g | Where-Object { $_.west }).Count
}
""

"=== 某方向完全没覆盖的轨（补灯清单：给那个方向布灯） ==="
$gaps = @($rows | Where-Object { -not $_.south -or -not $_.north -or -not $_.east -or -not $_.west })
if ($gaps.Count -eq 0) {
	"  （没有：每根轨的每个方向都有区间）"
} elseif (-not $Brief) {
	foreach ($laneName in ($gaps | Group-Object lane | Sort-Object Name | ForEach-Object { $_.Name })) {
		$laneRails = @($gaps | Where-Object { $_.lane -eq $laneName } | Sort-Object z1)
		"  x=$laneName（$($laneRails.Count) 根）："
		foreach ($r in $laneRails) {
			$missing = @()
			if (-not $r.south) { $missing += '南行' }
			if (-not $r.north) { $missing += '北行' }
			if (-not $r.east) { $missing += '东行' }
			if (-not $r.west) { $missing += '西行' }
			"     ({0},{1})→({2},{3})  长 {4:N1} m   缺：{5}" -f $r.x1, $r.z1raw, $r.x2, $r.z2raw, $r.length, ($missing -join '/')
		}
	}
} else {
	"  （共 $($gaps.Count) 根，-Brief 略去清单）"
}
""

"=== 每个方向最长的**无区间**连续段（>80 m 才列） ==="
foreach ($laneName in ($rows | Group-Object lane | Sort-Object Name | ForEach-Object { $_.Name })) {
	$laneRails = @($rows | Where-Object { $_.lane -eq $laneName } | Sort-Object z1)
	foreach ($dirName in @('south', 'north', 'east', 'west')) {
		$run = 0.0; $best = 0.0; $from = $null; $to = $null; $runFrom = $null
		foreach ($r in $laneRails) {
			$covered = [bool]$r.$dirName
			if (-not $covered) {
				if ($run -eq 0.0) { $runFrom = $r.z1 }
				$run += $r.length
				if ($run -gt $best) { $best = $run; $from = $runFrom; $to = $r.z2 }
			} else {
				$run = 0.0
			}
		}
		if ($best -gt 80) {
			$dirCn = @{ south = '南行'; north = '北行'; east = '东行'; west = '西行' }[$dirName]
			"  x={0,6} {1}：最长 {2:N0} m（z {3} → {4}）" -f $laneName, $dirCn, $best, $from, $to
		}
	}
}
