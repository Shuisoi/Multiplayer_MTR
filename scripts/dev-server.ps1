# Dev dedicated server launcher (loom runServer, logs to fabric/run/dev-server.out.log).
#
# 前后端分离：dev 服务端默认**从磁盘**发 web 控制台（engine/website/dist/website/browser），
# 而不是发 jar 内嵌的那份。于是改前端只需要：
#     cd engine\website; npm run build      # 然后刷新浏览器
# 不必重打 jar、也不必重启这个服务端。
# 想要回退到 jar 内嵌的版本，把 MMTR_WEB_ROOT 置空即可（例如 $env:MMTR_WEB_ROOT = ''）。
#
# 一键重启（server restart）：引擎不能重启自己（JVM 退出就结束了），所以重启必须由**启动器**接力。
# 指令会在 run 目录写一个 mmtr-restart.request 标记；本脚本跑完一轮 gradle 之后看这个标记：
# 有标记 = 再启动一次（这就是重启），没标记 = 用户只是想停机，脚本结束。
#
# 停机也有标记（mmtr-stop.request）：**"是你要我停的"**。没有它的话，`server stop` 之后启动器
# 看到的是"没有重启标记 + 8888 不应答"，与"构建失败"长得一模一样 —— 于是它会把用户主动停机
# 当成失败去重试，白起一轮（实测被触发过）。意图必须由发起方写下来，不能靠启动器猜。
#
# 标记的读写在时间上必须分得开，否则会互相踩：
#   · 引擎写标记发生在**服务端 tick 里**，也就是 gradle 还在跑的时候；
#   · 启动器读标记必须等到 **gradle 退出之后**——那时引擎进程已经死了，不可能再写。
# 所以标记只在两处删除：开机时删一次（清掉上一轮崩溃留下的残留），以及 gradle 退出后读到它时删一次。
# 每一轮开头都去删标记是错的：那一轮的引擎随时可能正在写它，删早了这一轮就永远不重启。
#
# 还要区分两种"没起来"：
#   · 正常停机（gradle 跑了服务端、服务端自己停了）→ 没标记就结束；
#   · 真的没起来（构建失败 / 端口占着）→ 重试几次，否则一次网络抖动就让服务端
#     "停在那里不动了"，而这正是最让人困惑的状态。
# 判据不只看日志：服务端最终必须**在 8888 端口上应答**，那才是"能用了"。
$ErrorActionPreference = 'Continue'
# 工作区环境单一真源（`JAVA_HOME` 等）—— **必须**在这里取，不能靠机器上的环境变量：
# 2026-09-14 实测，本机用户级 JAVA_HOME 指向 `C:\Program Files\Java\jre1.8.0_431`（JRE 8），
# gradle 直接拒绝启动（"JAVA_HOME is set to an invalid directory"）。
# `run-dev-server.bat` 一直是 `call env\workspace.env.bat` 的，这个 .ps1 是漏掉的那一个。
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')
$mmtr = Split-Path -Parent $PSScriptRoot
$game = Join-Path $mmtr 'game'
$run = Join-Path $game 'fabric\run'
$log = Join-Path $run 'dev-server.out.log'
$marker = Join-Path $run 'mmtr-restart.request'
$stopMarker = Join-Path $run 'mmtr-stop.request'

$webRoot = Join-Path $mmtr 'engine\website\dist\website\browser'
if (Test-Path (Join-Path $webRoot 'index.html')) {
	$env:MMTR_WEB_ROOT = $webRoot
	Write-Output "web console will be served from disk: $webRoot"
} else {
	Write-Output "WARNING: $webRoot\index.html not found; falling back to the jar-embedded console"
	Remove-Item Env:\MMTR_WEB_ROOT -ErrorAction SilentlyContinue
}

<#
★★ 更正（2026-10-02，notes/362）：下面这段把"`%TEMP%` 写不进去"当成服务端起不来的**根因**，是错的。

   真正的根因是**完整性级别**：DSH 沙箱在工作区根目录 MC 上打了一个显式的 Low 完整性标签
   （`icacls MC` → `Mandatory Label\Low Mandatory Level:(OI)(CI)(NW)`），它继承给工作区内所有文件；
   **镜像位于工作区内的一切可执行文件都以 Low 令牌运行**（实测 `env\jdk-21\bin\java.exe` 的 JVM 是
   Low，工作区内 `env\idea\bin\idea64.exe` 也是 Low；工作区外的 `.gradle\jdks\…` 才是 High）。
   Low 令牌下 `Files.isWritable()` 对**任何**路径都返回 false（真实写入却成功），而 fabric-loader
   的运行期重映射用的是 JDK 的 zipfs：

       jdk.zipfs/jdk/nio/zipfs/ZipFileSystem.java:172-174
           writeable = Files.isWritable(zfpath);  this.readOnly = !writeable;

   于是 tinyremapper 抛 "the jar file …\processedMods\worldedit-….jar can't be written"（那个 jar 其实
   写得进去）。真正要换的是**启动用的 JDK**：下面 `$lowLabel` 那段会自动换到工作区外的 JDK。
   注意：IDEA 自身就是 Low ⇒ 它 fork 的守护进程与 runServer 也一定是 Low ⇒ **从 IDEA 里起不来服务端**。

   `%TEMP%` 写不进去只是同一个 Low 令牌的**另一个症状**（不是根因），所以下面这段改动仍然保留。
#>
<#
★ 给服务端的 JVM 一个**可写的临时目录**（2026-10-02 实测，notes/362）。

这台机器上 `%TEMP%`（`C:\Users\<你>\AppData\Local\Temp`）**PowerShell 能写、JVM 不能写**：

    java.io.tmpdir = C:\Users\30354\AppData\Local\Temp\
    ✗ 临时目录不可写: java.nio.file.AccessDeniedException: ...\Temp\probe3231661387922866489.txt

症状不是"临时文件失败"这么直白，而是**服务端起不来、报的却是一个毫不相干的错**：

    Failed to remap mods!
    java.io.IOException: the jar file ...\run\.fabric\processedMods\worldedit-…jar can't be written

（fabric 重映射模组要经临时文件；临时目录不可写，它就把失败归到"那个 jar 写不了"上。同一个根因在
 `ImageIO` 那边的症状是"面文档里的图全变洋红占位框" —— 见 notes/360 §5 与本文件下面的 `--no-daemon`。）

所以显式给一个工作区内的临时目录。客户端由 IDEA 拉起时 `java.io.tmpdir` 是 IDEA 自己那份（可写），
所以"客户端能起、服务端起不来"曾经看起来像"共用 run 目录的锁问题" —— 其实是两件事，锁只是其中一次的表象。
#>
$tmpRoot = Join-Path $MC_ROOT 'sandbox\tmp'
New-Item -ItemType Directory -Force -Path $tmpRoot | Out-Null
# ★ 值**必须带引号**：工作区路径里有空格，不引的话 JVM 会把 `-Djava.io.tmpdir=` 截成空值
# （实测症状：`Picked up JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=` + `java.io.tmpdir is set to a directory that doesn't exist`）
$env:JAVA_TOOL_OPTIONS = (@('-Djava.io.tmpdir="' + $tmpRoot + '"', $env:JAVA_TOOL_OPTIONS) | Where-Object { $_ }) -join ' '
Write-Output "JVM temp dir: $tmpRoot"

<#
★ 工作区内的 JDK 跑在 Low 完整性令牌下 ⇒ fabric 运行期重映射必然失败（见 notes/362）。
  有可用的工作区外 JDK 就换成它 —— `JAVA_HOME` 与 `PATH` 必须一起换，否则 gradlew 还会从 PATH 里
  找到那个 Low 的 java。Low 是**镜像文件上的标签**决定的，跟谁启动、用不用守护进程无关；
  只是要留意别去 attach IDEA fork 出来的守护进程（那一整棵子树都是 Low）。
#>
$lowLabel = icacls (Join-Path $JDK21 'bin\java.exe') 2>$null | Select-String 'Mandatory Label\\Low'
if ($lowLabel) {
	$highJdk = $null
	$candidates = @()
	if ($env:MC_HIGH_JDK) { $candidates += $env:MC_HIGH_JDK }
	$candidates += (Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\jdks') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
	foreach ($candidate in $candidates) {
		$exe = Join-Path $candidate 'bin\java.exe'
		if ((Test-Path $exe) -and -not (icacls $exe 2>$null | Select-String 'Mandatory Label\\Low')) {
			<#
			 * ★ 候选里必须挑 **21+** 的（2026-10-03 实测）：`~\.gradle\jdks` 下
			 * `eclipse_adoptium-17-…` 的目录名排在 `eclipse_adoptium-21-…` 前面，
			 * 而"第一个非 Low 的"就会选中 17 ⇒ 构建当场死在
			 * `Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM.`
			 * （engine 与 game 的 buildSrc 都要求 21）。版本从 JDK 自带的 `release` 文件读，不猜目录名。
			 #>
			$major = 0
			$release = Join-Path $candidate 'release'
			if (Test-Path $release) {
				$match = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
				if ($match) {
					$major = [int]$match.Matches[0].Groups[1].Value
				}
			}
			if ($major -ge 21) {
				$highJdk = $candidate
				break
			}
		}
	}
	if ($highJdk) {
		$env:JAVA_HOME = $highJdk
		$lowBin = (Join-Path $JDK21 'bin').TrimEnd('\')
		$env:Path = (@((Join-Path $highJdk 'bin')) + ($env:Path -split ';' | Where-Object { $_ -and ($_.TrimEnd('\') -ne $lowBin) })) -join ';'
		Write-Output "★ 工作区内的 JDK 是 Low 完整性（fabric 重映射会报 can't be written）→ 改用工作区外的 JDK: $highJdk"
	} else {
		Write-Output "★ 警告：工作区内的 JDK 是 Low 完整性，且没找到可用的工作区外 JDK —— 服务端很可能起不来（fabric 运行期重映射会失败）。见 mmtr\notes\362。"
	}
}
Set-Location $game

$round = 0
$startFailures = 0
# 开机清一次：上一轮如果是被强杀的，标记会留在这里，不清掉会变成"开机就重启"的死循环。
Remove-Item $marker -ErrorAction SilentlyContinue
Remove-Item $stopMarker -ErrorAction SilentlyContinue
<#
**停机标记要按"这一轮写的"算**（2026-09-15 实测）：上一轮服务端**优雅停机**时会在退出过程里补写
`mmtr-stop.request`，那次写的时刻可能落在**下一轮已经开机之后**（部署/启动脚本先跑，旧 JVM 的
shutdown 钩子后落盘）—— 于是新一轮刚把服务端拉起来，读完标记就判"是你要我停的"、自己结束了，
现场表现为"部署完服务端没起来"（日志：`日志见服务端=True，停机标记=True`）。
所以这里记一个本轮时钟，只有**比它新**的标记才算数。
#>
$roundStartedAt = Get-Date

<#
从字节偏移 $from 开始，读出日志里"这一轮"新增的部分。

为什么按字节偏移读，而不是每轮先删日志：日志要**追加**（cmd 的 `>>`），这样启动器万一自己挂了，
服务端的日志也不会跟着断在一半——事后还能看出它到底跑到哪一步。代价是判据必须只看新增的那一段，
否则上一轮的 "Stopping server" 会让这一轮看起来"跑过了"，把"启动失败"误判成"正常停机"。
#>
function Read-LogSince([long]$from) {
	if (-not (Test-Path $log)) {
		return ''
	}
	try {
		$fs = [System.IO.File]::Open($log, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
		try {
			if ($from -gt 0 -and $from -le $fs.Length) {
				$fs.Seek($from, [System.IO.SeekOrigin]::Begin) | Out-Null
			}
			$reader = New-Object System.IO.StreamReader($fs)
			$text = $reader.ReadToEnd()
			$reader.Dispose()
			return $text
		} finally {
			$fs.Dispose()
		}
	} catch {
		# 读不到就当没有（判据会退化成"没跑过"，最坏情况是多重试一次，不会漏掉服务端）
		return ''
	}
}

while ($true) {
	$round++
	$logOffset = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
	Write-Output ("=== 启动服务端（第 " + $round + " 轮）===")
	# 用 cmd 的 `>>` 收日志，而不是 PowerShell 的 `*>`：
	# `*>` 会把启动器自己的输出流接到 gradle 上，gradle 退出时那条管道可能以异常方式关闭，
	# 于是**启动器自己被带走**（实测：服务端还活着，启动器的循环却没了，第 4 轮之后再没人接力）。
	# 让 cmd 自己做重定向，启动器的 stdio 保持干净。
	#
	# 刻意**不加** -NoNewWindow：那个开关让子进程共用启动器的控制台，等于又把两者的流绑回一起；
	# 让它开自己的控制台，父子之间的唯一联系就只剩"等它退出 + 看退出码"。
	$launch = Start-Process -FilePath "cmd.exe" -ArgumentList @("/c", "gradlew.bat :fabric:runServer >> `"$log`" 2>&1") -WorkingDirectory $game -PassThru -Wait
	$gradleExit = $launch.ExitCode

	# 到这里 gradle 已经退出，引擎进程没了 → 现在读标记是安全的（不再有并发写）。
	$restartRequested = Test-Path $marker
	$restartDetail = ''
	if ($restartRequested) {
		$restartDetail = (Get-Content $marker -Raw -ErrorAction SilentlyContinue)
		Remove-Item $marker -ErrorAction SilentlyContinue
	}
	$stopRequested = Test-Path $stopMarker

	# 判据之一：这一轮新增的日志里有没有"Minecraft 服务端跑起来并结束"的痕迹。
	$roundLog = Read-LogSince $logOffset
	$ranServer = $roundLog -match 'Finished running server|Stopping server'

	Write-Output ("第 " + $round + " 轮结束：日志见服务端=" + $ranServer + "，重启标记=" + $restartRequested + "，停机标记=" + $stopRequested + "，gradle 退出码=" + $gradleExit)

	# 主动停机优先判断：这是用户明确要求的"停"，既不要重启，也**不是**启动失败
	# （只认本轮写的标记：旧 JVM 退出时补写的那份不算，见 $roundStartedAt 的说明）
	$stopRequested = $false
	if (Test-Path $stopMarker) {
		$stopRequested = (Get-Item $stopMarker).LastWriteTime -ge $roundStartedAt
		if (-not $stopRequested) {
			Write-Output ("忽略上一轮留下的停机标记（写在 " + (Get-Item $stopMarker).LastWriteTime + "，早于本轮开机 " + $roundStartedAt + "）")
		}
		Remove-Item $stopMarker -ErrorAction SilentlyContinue
	}
	if ($stopRequested) {
		Write-Output "检测到停机标记（server stop）：这是主动停机，启动器结束（不当成启动失败重试）。"
		break
	}

	# 重启：意图已经由引擎写下的标记确认过，这里**不要**再去探测端口 ——
	# 端口探测得等世界加载完（实测 MTR 的接口在世界起来后一分多钟才应答），
	# 在那之前探只会得到"没应答"，而那次失败的探测会把已经确认的重启又误判成"构建失败"去多起一轮
	# （实测日志里出现过"第 1 轮结束：8888 应答=False → 第 1 次重试"，就是这么来的）。
	if ($restartRequested) {
		Write-Output ("检测到重启标记" + $(if ($restartDetail) { "（" + $restartDetail.Trim() + "）" } else { "" }) + "，3 秒后再启动一次…")
		Start-Sleep -Seconds 3
		continue
	}

	# 只有"没人要求重启、也没人要求停机"时才需要判断这一轮到底起没起来。
	# 判据（比日志硬）：8888 上到底有没有人在应答；给足时间等世界与接口起来。
	$endpoint = "http://127.0.0.1:8888/mtr/api/map/mmtr-trains"
	$listening = $false
	foreach ($attempt in 1..30) {
		try {
			$probe = Invoke-WebRequest -Uri $endpoint -TimeoutSec 5 -SkipHttpErrorCheck
			if (($probe.StatusCode -eq 200) -and ($probe.Headers['Content-Type'] -like 'application/json*')) {
				$listening = $true
				break
			}
		} catch {
			# 还没监听：继续等
		}
		Start-Sleep -Seconds 2
	}

	if (-not $listening -and $startFailures -lt 3) {
		$startFailures++
		Write-Output ("这一轮没起来（8888 不应答，日志见服务端=" + $ranServer + "；常见原因是 Forge 插件解析的网络抖动），第 " + $startFailures + " 次重试…")
		Start-Sleep -Seconds 5
		continue
	}

	Write-Output "没有重启标记，启动器结束。"
	break
}
