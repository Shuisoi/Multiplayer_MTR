# Demo timetable (环形线时刻表演示): six 1-car HST services on the depot-987654 loop.
# Each consist leaves its depot siding, serves the ten stations of the loop once per round
# (1..3 on the x=-170 down spine, 4..10 on the x=-155 up corridor), then runs empty around
# the x=-147 / x=-176 return legs back into its own depot siding and loops again.
#
# Usage: after the dev server is up, run this script (PowerShell). Idempotent - re-running
# upserts the same six jobs (registry keys are the job ids).
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8888/mtr/api/map'

$depotId = '849401984139021720'
# Depot 987654 siding ids (rolling-stock manifest order T1..T6).
$sidings = @(
	'-4629294257679021237', # T1
	'1607594720369027173',  # T2
	'4321759533923363700',  # T3
	'3518737612429408379',  # T4
	'-7701010504948601156', # T5
	'139388029583209177'    # T6
)
# Service order of the loop: stations 1-3 x=-170 spine (southbound), stations 4-10 x=-155 (northbound).
$stops = @(
	'-8021057666741005855', # station 1 (x=-170 face)
	'8840155259502644057',  # station 2 (x=-170 face)
	'1058477666616733399',  # station 3 (x=-170 face)
	'8591964083314666171',  # station 4 (x=-155 face)
	'-16333677002112739',   # station 5 (x=-155 face)
	'-933898300966339206',  # station 6 (x=-155 face)
	'-82431849548192114',   # station 7 (x=-155 face)
	'9181441402015485493',  # station 8 (x=-155 face)
	'4022183260327049783',  # station 9 (x=-155 face)
	'8821945135290913670'   # station 10 (x=-155 face)
)

$car = @{ vehicleId = 'hst'; length = 15.0; width = 5.0; capacity = 400; bogie1Position = -5.0; bogie2Position = 5.0; couplingPadding1 = 0.0; couplingPadding2 = 0.0 }

function Post-Json($endpoint, $body) {
	$json = $body | ConvertTo-Json -Depth 12 -Compress
	$r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/$endpoint" -ContentType 'application/json' -Body $json -TimeoutSec 20
	Write-Output ("{0} -> HTTP {1}: {2}" -f $endpoint, $r.StatusCode, $r.Content)
}

for ($i = 0; $i -lt 6; $i++) {
	$train = $i + 1
	$jobId = 'TT-0' + $train
	$start = 30000 + $i * 90000   # staggered departures: 30s, 2min, 3min30s, ... headway 90s
	$steps = @()
	$due = $start
	for ($k = 0; $k -lt $stops.Count; $k++) {
		$due += 90000                                   # nominal 90s per stop incl dwell
		$steps += @{
			stepId          = ('s{0:D2}' -f ($k + 1))
			type            = 'MOVE_TO'
			targetId        = $stops[$k]
			dueTimeOfDayMs  = ($due + 360000)           # + queueing slack
			note            = ('station {0}' -f ($k + 1))
		}
	}
	$due += 900000                                      # empty return leg allowance
	$steps += @{
		stepId         = 'ret'
		type           = 'MOVE_TO'
		targetId       = $sidings[$i]                   # 退库: back into its own depot siding
		dueTimeOfDayMs = ($due + 420000)
		note           = 'return to depot siding'
	}
	$job = @{
		jobId           = $jobId
		consistId       = ''
		depotId         = $depotId
		sidingId        = $sidings[$i]
		startTimeOfDayMs = $start
		repeatDaily     = $true
		loop            = $true
		loopEveryMs     = 60000
		cars            = @($car)
		steps           = $steps
	}
	Post-Json 'mmtr-jobs-upsert' $job
	Write-Output ("upserted {0} siding {1} start {2}ms steps {3}" -f $jobId, $sidings[$i], $start, $steps.Count)
}
