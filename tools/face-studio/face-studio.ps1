# face-studio.ps1 —— **车辆动态面工作室**：本地起一个回环 web 服务，浏览器里看/调动态面（notes/359 · F2）
#
# 为什么是 web：作者要迭代的是"一块屏上怎么排字"，而排版的好坏只能用眼睛判。浏览器是最便宜的画布
# （零安装、零构建、改完刷新即见），而且能顺手把已有的仿真台链进来。页面与数据都是本机文件，
# 服务只绑 127.0.0.1 且**只读**（见 FaceServe.java 的注释）。
#
# 用法：
#   pwsh -File mmtr\tools\face-studio\face-studio.ps1                      # 起服务（默认 8910），不开浏览器
#   pwsh -File mmtr\tools\face-studio\face-studio.ps1 -Action open          # 起服务 + 打开浏览器
#   pwsh -File mmtr\tools\face-studio\face-studio.ps1 -Action open -Anchors sandbox\face-preview\anchors_saf420cab_a_v36.json
#                                                                          # ↑ 直接把这块车的锚点喂给页面（不用手选文件）
#   pwsh -File mmtr\tools\face-studio\face-studio.ps1 -Action stop
#
# 只在 sandbox\ 下写文件（编译产物/日志/pid），不碰 build\，所以游戏开着也能跑。
param(
	[ValidateSet('serve', 'open', 'stop', 'build')][string]$Action = 'serve',
	[int]$Port = 8910,
	# 用哪个工作区相对路径的锚点 JSON 打开页面（可选）
	[string]$Anchors = ''
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$web = $PSScriptRoot
$work = Join-Path $mcRoot 'sandbox\face-studio'
$outDir = Join-Path $work 'classes'
$pidPath = Join-Path $work "pid-$Port.txt"
$logPath = Join-Path $work "serve-$Port.log"

$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
$java = Join-Path $mcRoot 'env\jdk-21\bin\java.exe'
if (-not (Test-Path $javac)) {
	$jdks = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -ErrorAction SilentlyContinue
	$javac = (Get-ChildItem $jdks.FullName -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
	$java = Join-Path (Split-Path -Parent $javac) 'java.exe'
}
if (-not (Test-Path $javac) -or -not (Test-Path $java)) { Write-Output '[face-studio] 找不到 JDK'; exit 2 }

function Compile-Serve {
	New-Item -ItemType Directory -Force $outDir | Out-Null
	# 零依赖：只有它自己
	& $javac -proc:none -nowarn -encoding UTF-8 -d $outDir (Join-Path $web 'FaceServe.java')
	if ($LASTEXITCODE -ne 0) { Write-Output '[face-studio] FaceServe 编译失败'; exit 1 }
	Write-Output "[face-studio] 已编译 -> $outDir"
}

if ($Action -eq 'build') {
	Compile-Serve
	exit 0
}

if ($Action -eq 'stop') {
	if (-not (Test-Path $pidPath)) { Write-Output '[face-studio] 没有记录到在跑的工作室（pid 文件不存在）'; exit 0 }
	$oldPid = [int](Get-Content $pidPath -Raw).Trim()
	Stop-Process -Id $oldPid -Force -ErrorAction SilentlyContinue
	Remove-Item $pidPath -Force -ErrorAction SilentlyContinue
	Write-Output "[face-studio] 已停：pid=$oldPid"
	exit 0
}

# ---- serve / open ---------------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $outDir 'FaceServe.class'))) { Compile-Serve }
$occupied = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($occupied) {
	$owner = ($occupied.OwningProcess | Select-Object -First 1)
	Write-Output "[face-studio] 端口 $Port 已被 pid=$owner 占用（先 -Action stop，或换 -Port）"
	if ($Action -eq 'open') {
		# 已经在跑就当"打开页面"用 —— 作者双击两次不该报错
		$url = "http://127.0.0.1:$Port/"
		if ($Anchors) { $url += '?anchors=/data/' + ($Anchors -replace '\\', '/') }
		Start-Process $url
	}
	exit 0
}

# 路径里有空格（工作区目录名）⇒ Start-Process 是把 ArgumentList 用空格拼一行的，必须**显式加引号**
$process = Start-Process $java -ArgumentList @(
	'-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', "`"$outDir`"", 'FaceServe',
	'--web', "`"$web`"", '--root', "`"$mcRoot`"", '--port', "$Port"
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $logPath -RedirectStandardError "$logPath.err"
Set-Content -Path $pidPath -Value $process.Id -Encoding ascii

for ($i = 1; $i -le 20; $i++) {
	Start-Sleep -Milliseconds 300
	if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
		$url = "http://127.0.0.1:$Port/"
		if ($Anchors) { $url += '?anchors=/data/' + ($Anchors -replace '\\', '/') }
		Write-Output ''
		Write-Output "动态面工作室： $url     （pid=$($process.Id)）"
		Write-Output "停止：pwsh -File mmtr\tools\face-studio\face-studio.ps1 -Action stop       日志：$logPath"
		if ($Action -eq 'open') { Start-Process $url }
		exit 0
	}
}
Write-Output "[face-studio] 端口 $Port 没起来，看日志：$logPath"
Get-Content $logPath -ErrorAction SilentlyContinue | Select-Object -Last 20
exit 1
