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

Set-Location $game

$round = 0
$startFailures = 0
# 开机清一次：上一轮如果是被强杀的，标记会留在这里，不清掉会变成"开机就重启"的死循环。
Remove-Item $marker -ErrorAction SilentlyContinue
Remove-Item $stopMarker -ErrorAction SilentlyContinue

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
	if ($stopRequested) {
		Remove-Item $stopMarker -ErrorAction SilentlyContinue
	}

	# 判据之一：这一轮新增的日志里有没有"Minecraft 服务端跑起来并结束"的痕迹。
	$roundLog = Read-LogSince $logOffset
	$ranServer = $roundLog -match 'Finished running server|Stopping server'

	Write-Output ("第 " + $round + " 轮结束：日志见服务端=" + $ranServer + "，重启标记=" + $restartRequested + "，停机标记=" + $stopRequested + "，gradle 退出码=" + $gradleExit)

	# 主动停机优先判断：这是用户明确要求的"停"，既不要重启，也**不是**启动失败
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
