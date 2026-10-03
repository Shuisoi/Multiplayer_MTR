# 现场看一趟"按时刻表跑"的采样器：把**计划层派发**与**车上的实况**按同一条时间轴记下来。
#
# 为什么要它（notes/152、153、155 的现场排查都靠这一类读数）：
#   计划层与车在引擎里是两套状态 —— 派发器说"这一步派出去了"，车可能还在等道岔/等信号。
#   只看其中一个都会得出错的结论（"计划明明有这一步，车怎么不动"）。
#   所以每次采样同时取三样：
#     ① 派发器：当日时刻、已派步数、跳过步数、每条交路走到第几步；
#     ② 车：任务种类/任务态/门/所在轨/走行里程/停稳标志；
#     ③ 服务端日志里这一段时间新出现的 [MMTR-PLAN] / [MMTR-PT] 动作行（开门停站、换端、让位…）。
#
# 用法：
#   .\scripts\watch-plan-run.ps1                       # 40 分钟、每 20 秒一次
#   .\scripts\watch-plan-run.ps1 -Minutes 15 -IntervalSeconds 10
param(
	[int]$Minutes = 40,
	[int]$IntervalSeconds = 20
)

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')
$mmtr = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8888/mtr/api/map/'
$serverLog = Join-Path $mmtr 'game\fabric\run\dev-server.out.log'
$outDir = Join-Path (Split-Path -Parent $mmtr) 'logs\2026-09'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir ("plan-watch-" + (Get-Date -Format 'HHmmss') + ".log")

function Get2([string]$endpoint) {
	(Invoke-WebRequest -Uri ($base + $endpoint) -TimeoutSec 25 -UseBasicParsing).Content | ConvertFrom-Json
}

function Say([string]$text) {
	$text | Tee-Object -FilePath $out -Append
}

# 日志读到哪里了：按字节偏移续读，避免每轮重扫 11 MB。
$logOffset = 0L
if (Test-Path $serverLog) { $logOffset = (Get-Item $serverLog).Length }

Say ("# 采样开始 " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + "  服务端日志=" + $serverLog)
$rounds = [Math]::Max(1, [int]($Minutes * 60 / $IntervalSeconds))
for ($i = 1; $i -le $rounds; $i++) {
	try {
		$events = Get2 'mmtr-plan-events'
		$diagrams = Get2 'mmtr-plan-diagrams'
		$trains = Get2 'mmtr-trains'
		$day = [TimeSpan]::FromMilliseconds($events.data.dayTimeMs).ToString('hh\:mm\:ss')
		$line = $diagrams.data.lines[0]
		$busy = @($trains.data.trains | Where-Object { $_.mission -ne $null }).Count
		Say ("[{0}] 当日 {1}  已派 {2}  跳过 {3}  在途 {4}" -f (Get-Date -Format 'HH:mm:ss'), $day, $line.dispatchedTotal, $line.skippedSteps, $busy)
		foreach ($w in $line.workings) {
			$vid = '' + $w.vehicleId
			Say ("    交路 {0}  车 {1}  第 {2}/{3} 步  等={4}  下一步={5} {6}" -f $w.consistId,
				$vid.Substring(0, [Math]::Min(8, $vid.Length)), $w.dispatchedSteps, $w.steps, $w.awaitingTaskId, $w.nextKind, $w.nextDueMs)
		}
		foreach ($t in ($trains.data.trains | Where-Object { $_.mission -ne $null } | Sort-Object vehicleId)) {
			$m = $t.mission
			$vid = '' + $t.vehicleId
			Say ("    车 {0}  {1}/{2}  门={3}  台/轨={4}  进度={5}m  停稳={6}" -f `
				$vid.Substring(0, [Math]::Min(8, $vid.Length)), $m.kind, $m.state,
				$t.doorsOpen, ('' + $t.currentRail), [Math]::Round($t.railProgressM), $t.stoppedAtTarget)
		}
	} catch {
		Say ("[{0}] 采样失败：{1}" -f (Get-Date -Format 'HH:mm:ss'), $_.Exception.Message)
	}
	if (Test-Path $serverLog) {
		$stream = [System.IO.File]::Open($serverLog, 'Open', 'Read', 'ReadWrite')
		try {
			if ($stream.Length -gt $logOffset) {
				$stream.Seek($logOffset, 'Begin') | Out-Null
				$reader = New-Object System.IO.StreamReader($stream)
				$chunk = $reader.ReadToEnd()
				$logOffset = $stream.Length
				foreach ($l in ($chunk -split "`r?`n" | Where-Object { $_ -match '\[MMTR-(PLAN|PT|MSG)\]' })) {
					Say ("    日志 " + $l.Trim())
				}
			}
		} finally {
			$stream.Dispose()
		}
	}
	Start-Sleep -Seconds $IntervalSeconds
}
Say ("# 采样结束 " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + "  写入 " + $out)
