# demo-job-pushpull-upsert.ps1 — 把「推挽循环」作业单写进引擎（notes/232 的实机结论落地）。
#
# 为什么要有这个脚本：
#   2026-09-21 现场的作业单（TT-LOOP-1-3）原来是 16 步、含**南端折返 stub 的牵出—推进**。
#   用户换乘 2 节编组（br101+p1 = 35.97 m）之后那套动作再也规划不出来 —— 南端折返轨只有 23 m，
#   装不下编组，"整列停进 stub"在物理上就不成立（用户诊断，notes/232 有时间线与尺寸）。
#   修法不是改引擎，而是**换一种作业单**：机车+客车本来就不需要掉头线，
#   两端各做一次**原地换端**（CHANGE_ENDS，车不动、引擎翻司机台）就够了。
#
# 这份作业单的形状：
#   去程 2 站 1 台 → 3 站 1 台（各自开关门）→ 3 站原地换端 → 回程 2 站 1 台 → 1 站 1 台 → 1 站原地换端 → 循环
#   每一步 MOVE_TO 都在**当前朝向上向前开**，一个"目标在身后"的规划都不需要。
#
# 用法：pwsh -File mmtr\scripts\demo-job-pushpull-upsert.ps1 [-Base http://127.0.0.1:8888] [-JobId TT-LOOP-1-3]
param(
	[string]$Base = 'http://127.0.0.1:8888',
	[string]$JobId = 'TT-LOOP-1-3',
	[string]$DepotId = '849401984139021720',
	[string]$SidingId = '-4629294257679021237'
)

$ErrorActionPreference = 'Stop'
$api = "$Base/mtr/api/map"

# 站台 id（这台服务器上作业单原本用的那几个；换世界要按 query depots / 站台列表改）
$p1 = '-8021057666741005855'   # 1 站 1 台
$p2 = '8840155259502644057'    # 2 站 1 台
$p3 = '954364252968674217'     # 3 站 1 台

$due = 86399000   # 当天末刻："不设具体时刻，轮到就走"
function Step([string]$id, [string]$type, [string]$targetId, [string]$note) {
	[ordered]@{ stepId = $id; type = $type; targetId = $targetId; targetIndex = -1; dueTimeOfDayMs = $due; note = $note }
}

# 两节编组：机车带车钩接缝，客车无动力 —— 与列车表 mmtr-rolling-stock 里那条股道一致
$carLoco = [ordered]@{ vehicleId = 'br101'; length = 19.9652; width = 3.0836; capacity = 0; bogie1Position = -5.723; bogie2Position = 5.723; couplingPadding1 = 0.0; couplingPadding2 = 0.0; powered = $true; consistTypeId = ''; mmtrCouplerAfter = $true; mmtrAutoCoupler = $true }
$carCoach = [ordered]@{ vehicleId = 'p1'; length = 16; width = 5; capacity = 400; bogie1Position = -5; bogie2Position = 5; couplingPadding1 = 0; couplingPadding2 = 0; powered = $false; consistTypeId = ''; mmtrCouplerAfter = $false; mmtrAutoCoupler = $true }

$body = [ordered]@{
	jobId           = $JobId
	depotId         = $DepotId
	sidingId        = $SidingId
	startTimeOfDayMs = 1000
	repeatDaily     = $true
	loop            = $true
	loopEveryMs     = 5000
	cars            = @($carLoco, $carCoach)
	steps           = @(
		(Step 'm2o' 'MOVE_TO' $p2 '去程到 2 站 1 台')
		(Step 's2o' 'SERVE' $p2 '2 站 1 台开关门')
		(Step 'm3o' 'MOVE_TO' $p3 '去程到 3 站 1 台')
		(Step 's3o' 'SERVE' $p3 '3 站 1 台开关门')
		(Step 'ce1' 'CHANGE_ENDS' '0' '3 站 1 台原地换端（掉头）')
		(Step 'm2b' 'MOVE_TO' $p2 '回程到 2 站 1 台')
		(Step 's2b' 'SERVE' $p2 '2 站 1 台开关门')
		(Step 'm1b' 'MOVE_TO' $p1 '回程到 1 站 1 台')
		(Step 's1b' 'SERVE' $p1 '1 站 1 台开关门')
		(Step 'ce2' 'CHANGE_ENDS' '0' '1 站 1 台原地换端（掉头）')
	)
}

$json = $body | ConvertTo-Json -Depth 12 -Compress
$temp = [System.IO.Path]::GetTempFileName()
try {
	# 用 -InFile 送 UTF-8 字节：作业单里的中文说明（note）不能被控制台代码页搞坏
	[System.IO.File]::WriteAllText($temp, $json, (New-Object System.Text.UTF8Encoding($false)))
	$response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$api/mmtr-jobs-upsert" -ContentType 'application/json; charset=utf-8' -InFile $temp -TimeoutSec 20
	Write-Output ("job upsert -> HTTP {0}: {1}" -f $response.StatusCode, $response.Content)
} finally {
	Remove-Item $temp -ErrorAction SilentlyContinue
}

$jobs = (Invoke-WebRequest -UseBasicParsing -Uri "$api/mmtr-jobs" -TimeoutSec 15).Content | ConvertFrom-Json
$job = $jobs.data.jobs | Where-Object { $_.jobId -eq $JobId }
if ($null -eq $job) { throw "作业 $JobId 没有写进去" }
Write-Output ("{0}：{1} 步，{2} 节车" -f $JobId, $job.steps.Count, $job.cars.Count)
$job.steps | ForEach-Object { Write-Output ("  {0} {1} {2}" -f $_.type, $_.targetId, $_.note) }
Write-Output "提示：改完作业单后要让引擎重铺一次（POST $api/mmtr-manifest-reset），循环才会从第 1 步重跑。"
