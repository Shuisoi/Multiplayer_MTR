# 把新引擎 jar 部署到游戏端 —— **只在服务端已停的情况下**。
#
# 为什么必须停机再拷（2026-09-13 实测事故）：
#   服务端跑起来之后，Fabric 的类加载器是**从 `game/libs/Transport-Simulation-Core-0.0.1.jar` 里按需读 class**
#   的（懒加载）。在它还活着的时候覆盖这个 jar，等于把正在被读的 zip 换掉一半，于是：
#       Caused by: java.io.EOFException: Unexpected end of ZLIB input stream
#         at java.util.zip.ZipFile$ZipFileInflaterInputStream.fill
#         at net.fabricmc.loader.impl.launch.knot.KnotClassDelegate.getRawClassByteArray
#   现象不是"崩了"而是**更坏的一种**：服务端 tick 里持续抛异常、车辆全部消失（MMTR-HLTH vehicles=8 → 0）、
#   指令通道完全不应答（`mmtr-command` 请求超时），而地图接口还在正常回 200 —— 看起来像"引擎卡死"，
#   实际是半个 jar。恢复办法只有一个：杀掉这一轮让它用完整的 jar 重启。
#
# 用法：
#   .\scripts\deploy-engine.ps1              # 确认服务端已停 → 打包 → 拷贝
#   .\scripts\deploy-engine.ps1 -Build       # 先 gradlew shadowJar 再拷贝
#   .\scripts\deploy-engine.ps1 -Force       # 明知服务端在跑也要拷（会打印警告，事故自负）
param(
	[switch]$Build,
	[switch]$Force
)

$ErrorActionPreference = 'Stop'
# 工作区环境单一真源（JAVA_HOME 等）—— 与 dev-server.ps1 同一行写法。
# 为什么必须这样取：这里原先自己拼 `mmtr\env\jdk-21`，而 JDK 在**工作区根**（`MC\env\jdk-21`），
# 于是 `-Build` 直接死于 "JAVA_HOME is set to an invalid directory"（2026-09-14 实测，退出码 3）。
# 用户级 JAVA_HOME 又是 JRE 8，所以"继承环境变量"这条退路也不存在。
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')
$mmtr = Split-Path -Parent $PSScriptRoot
$engine = Join-Path $mmtr 'engine'
$built = Join-Path $engine 'build\libs\Transport-Simulation-Core-1.0.0.jar'
$target = Join-Path $mmtr 'game\libs\Transport-Simulation-Core-0.0.1.jar'

# 判据用"进程还在不在"，不用"8888 应不应答"：半个 jar 的状态下 8888 照样应答（实测），
# 而进程在不在是唯一的硬事实。
#
# 必须限定 `dli.env=server`（2026-09-15 实测，退出码 2 误报）：
#   客户端和开发服务端都是 fabric devlaunch 出来的，命令行里**都有** `-Dfabric.dli.config`，
#   只有 `-Dfabric.dli.env` 区分得开（客户端 `client`，服务端 `server`）。
#   原先只匹配 `dli.config`，于是"玩家开着客户端"就被当成"服务端还在跑"，部署被无理由拒绝。
#   客户端读的是同一个 `game\libs` 里的 jar，但它不是"正在被覆盖的那个类加载器"吗？
#   —— 是，所以客户端在跑时**也确实有风险**；但那不是本脚本要拦的对象（服务端才是 8888 的宿主），
#   误报比漏报更贵：玩家永远开着客户端，等于脚本永远不可用。客户端要换 jar，重开客户端即可。
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
	Where-Object { $_.CommandLine -match 'fabric\.dli\.config' -and $_.CommandLine -match 'fabric\.dli\.env=server' }
if ($running -and -not $Force) {
	Write-Output "服务端仍在运行（PID $($running.ProcessId -join ',')）—— 现在覆盖 jar 会把类加载器读坏。"
	Write-Output "先停：在游戏里执行 server stop，或等启动器那一轮结束。"
	Write-Output "（确实要强拷请加 -Force；那之后**必须**再重启服务端。）"
	exit 2
}
if ($running) {
	Write-Output "警告：服务端在运行，-Force 已指定 —— 拷完之后请立刻 server restart，否则类会读坏。"
}

if ($Build) {
	Push-Location $engine
	try {
		& .\gradlew.bat shadowJar --console=plain -q
		if ($LASTEXITCODE -ne 0) { throw "shadowJar 失败（退出码 $LASTEXITCODE）" }
	} finally {
		Pop-Location
	}
}

if (-not (Test-Path $built)) { throw "找不到 $built（先加 -Build）" }
Copy-Item $built $target -Force
Write-Output ("已部署: {0}  →  {1}" -f (Get-Item $built).LastWriteTime, $target)
Write-Output "现在可以起服务端（scripts\dev-server.ps1），或让启动器接力。"
