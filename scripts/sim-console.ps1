# 仿真控制台：把**现有控制台前端**接到一台**无头引擎 + 世界副本**上，用于"在地图上看着车按作业单跑"。
#
# 它是什么、不是什么（别误读）：
#   是 —— 独立 JVM 的无头引擎跑在一份**世界副本**上，配现有网页控制台，端口与线上 dev 服务端分开；
#         作业单在地图上真的会动（出库、停站开门、换端、卡住都看得见）。
#   不是 —— 不是"仿真台"（Gantt/占用/轨迹回放那一页**还不存在**，属于独立 Studio 程序）；
#         也**不碰线上存档**：世界先整份复制到 <工作区>\.tmp-sim-console\world，引擎只读写副本。
#
# 用法（在 mmtr\ 下）：
#   .\scripts\sim-console.ps1                     # 起（已有副本就复用；没有就从 dev 世界复制一份）
#   .\scripts\sim-console.ps1 -ReSnapshot         # 重新从 dev 世界复制副本（丢掉副本里的车/状态）
#   .\scripts\sim-console.ps1 -JobsFile <path>    # 把某份作业单装进副本（例如 P0 生成的 16 份）
#   .\scripts\sim-console.ps1 -Port 8901          # 换端口
#   .\scripts\sim-console.ps1 -Stop               # 停掉这台仿真控制台
#
# 前置：引擎 jar 必须已构建（.\scripts\sync-engine.ps1 会在 game/libs 落一份；本脚本用 build\libs 里那份）。
#       前端产物在 mmtr\engine\website\dist\website\browser（改前端要先 npm run build）。
param(
	[int]$Port = 8899,
	[switch]$ReSnapshot,
	[switch]$Stop,
	[string]$World,
	[string]$JobsFile
)

$ErrorActionPreference = 'Stop'
$mmtr = Split-Path -Parent $PSScriptRoot
# 工作区路径单一真源（env/workspace.env.ps1 导出 $MC_ROOT / $JDK21）
. (Join-Path (Split-Path $mmtr -Parent) 'env\workspace.env.ps1')

$workDir = Join-Path $MC_ROOT '.tmp-sim-console'
$worldDir = Join-Path $workDir 'world'
# 日志与 pid **按端口分开**：同时开两台（比如 8899 看副本、8901 看另一份作业单）时，
# 共用一份 pid 文件会让 -Stop 杀错/漏杀。
$logPath = Join-Path $workDir "engine-$Port.log"
$pidPath = Join-Path $workDir "pid-$Port.txt"
if (-not $World) { $World = Join-Path $mmtr 'game\fabric\run\world\mtr' }
$java = Join-Path $JDK21 'bin\java.exe'
$jar = (Get-ChildItem (Join-Path $mmtr 'engine\build\libs') -Filter 'Transport-Simulation-Core-*.jar' -ErrorAction SilentlyContinue |
	Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1).FullName
$web = Join-Path $mmtr 'engine\website\dist\website\browser'

# ---- 停 -------------------------------------------------------------------------------
if ($Stop) {
	if (-not (Test-Path $pidPath)) { Write-Output '没有记录到在跑的仿真控制台（pid 文件不存在）'; exit 0 }
	$oldPid = [int](Get-Content $pidPath -Raw).Trim()
	$killed = 0
	# cmd 包装进程 + 它下面的 java 一起收（java 是 cmd 的子进程，杀 cmd 不一定带走它）
	Get-CimInstance Win32_Process -Filter "ParentProcessId=$oldPid" -ErrorAction SilentlyContinue | ForEach-Object {
		Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; $killed++
	}
	Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
	Remove-Item $pidPath -Force -ErrorAction SilentlyContinue
	Write-Output "已停：pid=$oldPid（连带子进程 $killed 个）"
	exit 0
}

foreach ($required in @($java, $web)) {
	if (-not (Test-Path $required)) { throw "缺少：$required（引擎 jar 用 scripts\sync-engine.ps1 构建；前端产物要 npm run build）" }
}
if (-not $jar) { throw '找不到 engine\build\libs\Transport-Simulation-Core-*.jar —— 先跑 scripts\sync-engine.ps1' }

# ---- 副本 -----------------------------------------------------------------------------
if ($ReSnapshot -or -not (Test-Path (Join-Path $worldDir 'minecraft'))) {
	if (-not (Test-Path $World)) { throw "找不到世界源目录：$World" }
	if (Test-Path $worldDir) { Remove-Item $worldDir -Recurse -Force }
	New-Item -ItemType Directory -Force -Path $workDir | Out-Null
	Copy-Item $World $worldDir -Recurse -Force
	Write-Output "已复制世界副本：$World -> $worldDir（线上存档未改动）"
} else {
	Write-Output "复用已有副本：$worldDir（要重来加 -ReSnapshot）"
}

if ($JobsFile) {
	if (-not (Test-Path $JobsFile)) { throw "找不到作业单文件：$JobsFile" }
	$jobsTarget = Join-Path $worldDir 'minecraft\overworld\mmtr-jobs.json'
	Copy-Item $JobsFile $jobsTarget -Force
	$count = (Get-Content $jobsTarget -Raw | ConvertFrom-Json).jobs.Count
	Write-Output "已装作业单 $count 条：$JobsFile -> $jobsTarget"
}

# ---- 起 -------------------------------------------------------------------------------
# 端口占用就先说清楚，别起出一个"看着在跑、其实连的是别人"的假象
$occupied = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($occupied) { throw "端口 $Port 已被 pid=$($occupied.OwningProcess -join ',') 占用（换 -Port，或先 -Stop）" }

New-Item -ItemType Directory -Force -Path $workDir | Out-Null
# stdin 必须**一直开着**：Main.readConsoleInput() 读到 null（管道关闭）会直接 stop()，
# 于是 nohup 式后台起法会"起来就退"。这里用 ping 把管道写端占住（ping 输出丢进 NUL，永不写入 -> readLine 阻塞）。
$inner = "ping -n 100000 127.0.0.1 >NUL | `"$java`" -Dmmtr.web.root=`"$web`" -jar `"$jar`" -r `"$worldDir`" -p $Port minecraft/overworld > `"$logPath`" 2>&1"
$process = Start-Process cmd -ArgumentList '/c', $inner -PassThru -WindowStyle Hidden
Set-Content -Path $pidPath -Value $process.Id -Encoding ascii
Write-Output "已启动 pid=$($process.Id)；日志 $logPath"

for ($i = 1; $i -le 40; $i++) {
	Start-Sleep -Milliseconds 500
	if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
		Write-Output ''
		Write-Output "控制台： http://127.0.0.1:$Port/     （视图：区间地图 / 地图 / 时刻表）"
		Write-Output "看车动：切到「地图」；要读数用 POST /mtr/api/map/mmtr-trains 与 mmtr-job-states"
		Write-Output "停掉：   .\scripts\sim-console.ps1 -Stop"
		exit 0
	}
}
Write-Output "等了 20 s 端口 $Port 还没起来，看日志：$logPath"
Get-Content $logPath -Tail 30
exit 1
