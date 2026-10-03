# 开发世界备份 / 复原 —— 施工前的**还原点**。
#
# 为什么要有它：`gen_p2p.py` 这类"指哪打哪"的施工会**永久**改世界（落几百条 fill、
# 几十条 rail、一堆 setblock），出错时最贵的不是重做，而是**清不干净**：
# `/setblock ... air` 不触发 `BlockNode.onBreak2`（铁律 6），手工回滚必留幽灵轨。
# 所以规矩是：**动手前先取还原点，出事就整目录回滚**。
#
# 用法：
#   pwsh -File mmtr\scripts\world-backup.ps1                                  # 备份（标签=时间戳）
#   pwsh -File mmtr\scripts\world-backup.ps1 -Label before-p2p-4031
#   pwsh -File mmtr\scripts\world-backup.ps1 -Action list
#   pwsh -File mmtr\scripts\world-backup.ps1 -Action verify -Label before-p2p-4031
#   pwsh -File mmtr\scripts\world-backup.ps1 -Action restore -Label before-p2p-4031
#
# 备份**不用停服**：`save-off` → `save-all flush` → 等落盘 → 复制 → `save-on`。
# （save-off 之后区块只在内存里，磁盘上的 region 文件是冻结的，这是 Minecraft 的标准备份法。）
# 复原**必须停服**：进程持有 region 文件句柄，而且内存里的状态会覆盖回去。
# 复原本身也可回滚 —— 它会先把当前世界存成 `<时间戳>-pre-restore` 这一份。
#
# ⚠ 复原后客户端要重进（区块已变），服务端要重新起来（脚本不会替你起，避免抢 IDEA 的启动权）。

param(
	[ValidateSet('backup', 'restore', 'list', 'verify')][string]$Action = 'backup',
	[string]$Label = '',
	[switch]$Force,        # restore: 校验不过 / 目标已存在时仍然继续
	[switch]$NoSave        # backup: 完全不碰服务器（它没在跑时用）
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSScriptRoot
. (Join-Path (Split-Path $here -Parent) 'env\workspace.env.ps1')

$RunDir = Join-Path $MC_ROOT 'mmtr\game\fabric\run'
$World = Join-Path $RunDir 'world'
$BackupRoot = Join-Path $RunDir 'world-backups'
$RconPy = Join-Path $MC_ROOT 'sandbox\rcon.py'
$RCON_PORT = 25575

function Get-Stamp { Get-Date -Format 'yyyyMMdd-HHmmss' }

function Test-ServerUp {
	$c = Get-NetTCPConnection -State Listen -LocalPort $RCON_PORT -ErrorAction SilentlyContinue
	return [bool]$c
}

function Invoke-Rcon([string]$Command) {
	& python $RconPy $Command 2>&1 | Out-String | Write-Verbose
}

function Copy-World([string]$Source, [string]$Dest) {
	<# robocopy 而不是 Copy-Item：**必须排除 session.lock** ——
	   服务端进程一直持着它的句柄，连读都会报"另一个程序已锁定文件的一部分"（实测）。
	   它只是"这个世界正在被谁用"的标记文件，服务端启动时会重建，不属于要备份的数据。 #>
	& robocopy $Source $Dest /E /XF session.lock /NFL /NDL /NJH /NJS /R:1 /W:1 | Out-Null
	if ($LASTEXITCODE -ge 8) { throw "robocopy 失败（退出码 $LASTEXITCODE）：$Source → $Dest" }
}

function Get-EngineState {
	<# 把引擎的**权威状态**抄下来：回滚之后拿它对账（rail 条数/hex、平台、灯数）。
	   方块回滚对了但引擎没回滚，是最难查的一种"看起来好了"。 #>
	if (-not (Test-ServerUp)) { return [ordered]@{ note = '备份时服务器没在跑，未取引擎状态' } }
	try {
		$base = 'http://127.0.0.1:8888/mtr/api/map'
		$plats = Invoke-RestMethod "$base/mmtr-platforms" -TimeoutSec 15
		$lamps = Invoke-RestMethod "$base/mmtr-lamps" -TimeoutSec 15
		$body = @{ command = 'rail list' } | ConvertTo-Json -Compress
		$rails = Invoke-RestMethod -Method Post -Uri "$base/mmtr-command" -ContentType 'application/json' -Body $body -TimeoutSec 30
		$hexes = @($rails.data.affected | Sort-Object)
		return [ordered]@{
			railCount = @($rails.data.lines | Where-Object { $_ -match '→' }).Count
			railHexes = $hexes
			lampCount = @($lamps.data.lamps).Count
			platforms = @($plats.data.platforms | ForEach-Object {
				[ordered]@{ station = $_.stationName; hex = $_.platformHex; railHex = $_.railHex
					x1 = $_.x1; y1 = $_.y1; z1 = $_.z1; x2 = $_.x2; y2 = $_.y2; z2 = $_.z2 } })
		}
	} catch {
		return [ordered]@{ error = "$_" }
	}
}

function Get-Manifest([string]$Dir) {
	# 用 [pscustomobject] 而不是 [ordered]@{}：后者 `Measure-Object -Property bytes` 数不出来（实测得 0）
	$files = Get-ChildItem $Dir -Recurse -File -Force
	$entries = foreach ($f in $files) {
		[pscustomobject]@{
			path = $f.FullName.Substring($Dir.Length).TrimStart('\')
			bytes = $f.Length
			sha256 = (Get-FileHash $f.FullName -Algorithm SHA256).Hash
		}
	}
	return @($entries)
}

function Resolve-Backup([string]$Name) {
	if (-not (Test-Path $BackupRoot)) { return $null }
	$dirs = Get-ChildItem $BackupRoot -Directory | Sort-Object Name -Descending
	if (-not $Name) { return $dirs | Select-Object -First 1 }
	$exact = $dirs | Where-Object { $_.Name -eq $Name }
	if ($exact) { return $exact | Select-Object -First 1 }
	return $dirs | Where-Object { $_.Name -like "*$Name*" } | Select-Object -First 1
}

function Invoke-Backup {
	if (-not (Test-Path $World)) { throw "找不到世界目录：$World" }
	$name = if ($Label) { "$(Get-Stamp)-$Label" } else { Get-Stamp }
	$dest = Join-Path $BackupRoot $name
	if (Test-Path $dest) { throw "备份已存在：$dest（换个 -Label）" }
	New-Item -ItemType Directory -Path $dest -Force | Out-Null

	$up = Test-ServerUp
	$paused = $false
	try {
		if ($up -and -not $NoSave) {
			Write-Host '① save-off + save-all flush（冻结磁盘上的 region 文件）'
			Invoke-Rcon 'save-off'
			Invoke-Rcon 'save-all flush'
			Start-Sleep -Seconds 3
			$paused = $true
		} elseif (-not $up) {
			Write-Host '① 服务器没在跑，直接复制'
		} else {
			Write-Warning '① -NoSave：服务器在跑但没冻结磁盘写入 —— 这份备份可能不是一致快照'
		}

		Write-Host "② 复制世界 → world-backups\$name\world"
		$sw = [System.Diagnostics.Stopwatch]::StartNew()
		Copy-World $World (Join-Path $dest 'world')
		$sw.Stop()

		Write-Host '③ 抄引擎权威状态'
		$engine = Get-EngineState
		$engine | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $dest 'engine-state.json') -Encoding utf8

		Write-Host '④ 写 manifest（逐文件 sha256，供 verify/restore 校验）'
		$manifest = Get-Manifest (Join-Path $dest 'world')
		$total = ($manifest | Measure-Object -Property bytes -Sum).Sum
		[pscustomobject]@{
			label = $name
			createdAt = (Get-Date).ToString('o')
			serverWasUp = $up
			fileCount = $manifest.Count
			totalBytes = $total
			engine = $engine
			files = $manifest
		} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $dest 'manifest.json') -Encoding utf8
	} finally {
		if ($paused) {
			Invoke-Rcon 'save-on'
			Write-Host '⑤ save-on'
		}
	}

	$mb = [math]::Round($total / 1MB, 1)
	Write-Host ''
	Write-Host "✔ 备份完成：$dest"
	Write-Host "  $($manifest.Count) 个文件 / $mb MB / 用时 $([math]::Round($sw.Elapsed.TotalSeconds, 1)) s"
	if ($engine.railCount) { Write-Host "  引擎状态：rail $($engine.railCount) 条、灯 $($engine.lampCount) 盏、平台 $(@($engine.platforms).Count) 个" }
	Write-Host "  回滚：pwsh -File mmtr\scripts\world-backup.ps1 -Action restore -Label $name"
}

function Invoke-Verify([string]$Name) {
	$bk = Resolve-Backup $Name
	if (-not $bk) { throw "找不到备份（-Label '$Name'）" }
	$manifestPath = Join-Path $bk.FullName 'manifest.json'
	if (-not (Test-Path $manifestPath)) { throw "备份里没有 manifest.json：$($bk.FullName)" }
	$m = Get-Content $manifestPath -Raw | ConvertFrom-Json
	$root = Join-Path $bk.FullName 'world'
	$bad = @()
	foreach ($f in $m.files) {
		$p = Join-Path $root $f.path
		if (-not (Test-Path $p)) { $bad += "$($f.path)：缺文件"; continue }
		if ((Get-Item $p).Length -ne $f.bytes) { $bad += "$($f.path)：大小不符"; continue }
		if ((Get-FileHash $p -Algorithm SHA256).Hash -ne $f.sha256) { $bad += "$($f.path)：sha256 不符" }
	}
	Write-Host "备份 $($bk.Name)：$($m.fileCount) 个文件 / $([math]::Round($m.totalBytes / 1MB, 1)) MB"
	if ($bad.Count) {
		Write-Host "✘ 校验失败 $($bad.Count) 项"
		$bad | Select-Object -First 20 | ForEach-Object { Write-Host "   - $_" }
		return $false
	}
	Write-Host '✔ 校验通过（逐文件 sha256 一致）'
	return $true
}

function Invoke-List {
	if (-not (Test-Path $BackupRoot)) { Write-Host '（还没有任何备份）'; return }
	Get-ChildItem $BackupRoot -Directory | Sort-Object Name -Descending | ForEach-Object {
		$mp = Join-Path $_.FullName 'manifest.json'
		$info = if (Test-Path $mp) { Get-Content $mp -Raw | ConvertFrom-Json } else { $null }
		$size = if ($info) { [math]::Round($info.totalBytes / 1MB, 1) } else { '?' }
		$engine = if ($info -and $info.engine.railCount) {
			"rail $($info.engine.railCount) / 灯 $($info.engine.lampCount) / 平台 $(@($info.engine.platforms).Count)"
		} else { '-' }
		"{0,-44} {1,8} MB  {2}" -f $_.Name, $size, $engine
	}
}

function Invoke-Restore([string]$Name) {
	$bk = Resolve-Backup $Name
	if (-not $bk) { throw "找不到备份（-Label '$Name'）" }
	Write-Host "还原点：$($bk.Name)"

	if (-not (Invoke-Verify $Name) -and -not $Force) {
		throw '备份校验没过；确认要用它就用 -Force（但先想清楚：这份备份可能本来就是坏的）'
	}

	if (Test-ServerUp) {
		Write-Host '① 停服（复原必须停：进程持有 region 句柄，且内存状态会覆盖回去）'
		Invoke-Rcon 'save-off'
		Invoke-Rcon 'stop'
		$deadline = (Get-Date).AddSeconds(120)
		while ((Get-Date) -lt $deadline) {
			Start-Sleep -Seconds 3
			$still = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
				Where-Object { $_.CommandLine -match 'devlaunchinjector\.Main' }
			if (-not $still -and -not (Test-ServerUp)) { break }
		}
		if (Test-ServerUp) { throw '服务器没能在 120 s 内停干净，已中止（世界没动）' }
		Write-Host '   已停'
	}

	$pre = "$(Get-Stamp)-pre-restore"
	Write-Host "② 先把当前世界存成还原点 $pre（复原本身也可回滚）"
	New-Item -ItemType Directory -Path (Join-Path $BackupRoot $pre) -Force | Out-Null
	Copy-World $World (Join-Path $BackupRoot "$pre\world")
	$preManifest = Get-Manifest (Join-Path $BackupRoot "$pre\world")
	[pscustomobject]@{ label = $pre; createdAt = (Get-Date).ToString('o'); note = 'restore 前自动存的一份'
		fileCount = $preManifest.Count
		totalBytes = ($preManifest | Measure-Object -Property bytes -Sum).Sum
		files = $preManifest } |
		ConvertTo-Json -Depth 6 | Set-Content (Join-Path $BackupRoot "$pre\manifest.json") -Encoding utf8

	Write-Host "③ 用 $($bk.Name) 覆盖世界"
	Remove-Item $World -Recurse -Force
	Copy-World (Join-Path $bk.FullName 'world') $World

	Write-Host ''
	Write-Host "✔ 复原完成（世界 = $($bk.Name) 的快照）"
	Write-Host "  撤销本次复原：pwsh -File mmtr\scripts\world-backup.ps1 -Action restore -Label $pre"
	Write-Host "  重启服务端：pwsh -File mmtr\scripts\dev-server.ps1"
	Write-Host "  ⚠ 客户端要重进（区块已换）"
}

switch ($Action) {
	'backup' { Invoke-Backup }
	'restore' { Invoke-Restore $Label }
	'verify' { if (-not (Invoke-Verify $Label)) { exit 1 } }
	'list' { Invoke-List }
}
