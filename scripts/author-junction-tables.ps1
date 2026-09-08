# Author the junction leg tables (进向表) the demo world needs:
#   Terminal turnback at the x=-155 south terminus (车站10 之后的换向区):
#   a train arriving on the x=-155 -> x=-147 diagonal (#9) at node (-147,-169) may take
#   - leg 0: the dead spur #61 (straight-ish, stays available),
#   - leg 1: the x=-147 northbound line #38 (the ~160deg 掉头 lead back to the depot).
# Geometry alone would reject leg 1 (near-180 fold); the table makes it explicit and
# addressable by the plan/authority layers as branch 1 (道岔=1 -> cross -> release).
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8888/mtr/api/map'
$topo = (Invoke-WebRequest -UseBasicParsing -Uri "$base/mmtr-topology" -TimeoutSec 15).Content | ConvertFrom-Json

function Find-RailHex([object[]]$p1, [object[]]$p2) {
	foreach ($rail in $topo.data.rails) {
		$a = @([int]$rail.x1, [int]$rail.z1)
		$b = @([int]$rail.x2, [int]$rail.z2)
		$key1 = ($p1 -join ',') ; $key2 = ($p2 -join ',')
		if (($a -join ',' -eq $key1 -and $b -join ',' -eq $key2) -or ($a -join ',' -eq $key2 -and $b -join ',' -eq $key1)) {
			return $rail.hex
		}
	}
	throw "rail not found for ($($p1 -join ',')) -> ($($p2 -join ','))"
}

$viaHex  = Find-RailHex @(-155,-189) @(-147,-169)   # x=-155 -> x=-147 diagonal (掉头入口)
$spurHex = Find-RailHex @(-147,-147) @(-147,-169)   # x=-147 dead spur (straight-ish, leg 0)
$lineHex = Find-RailHex @(-147,-169) @(-147,-204)   # x=-147 northbound line (掉头 leg 1)

# Node height y: read from the topology node feed for (-147,-169).
$nodeY = -60
foreach ($node in $topo.data.nodes) {
	if ([int]$node.x -eq -147 -and [int]$node.z -eq -169) { $nodeY = [int]$node.y; break }
}

$body = @{
	x    = -147
	y    = $nodeY
	z    = -169
	via  = $viaHex
	legs = @($spurHex, $lineHex)
} | ConvertTo-Json -Depth 6 -Compress
$r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/mmtr-junction-legs-upsert" -ContentType 'application/json' -Body $body -TimeoutSec 15
Write-Output ("junction table upsert -> HTTP {0}: {1}" -f $r.StatusCode, $r.Content)

$list = (Invoke-WebRequest -UseBasicParsing -Uri "$base/mmtr-junction-legs" -TimeoutSec 15).Content | ConvertFrom-Json
Write-Output ("entries now: {0}" -f $list.data.entries.Count)
