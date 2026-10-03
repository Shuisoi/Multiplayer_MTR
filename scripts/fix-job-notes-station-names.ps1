# fix-job-notes-station-names.ps1 - 把作业单 note 里的站名简称改成世界里的车站名（notes/356 §5）
#
# 为什么要有它：水牌（PID）显示的站名是**从世界对象现算**的（Station.getName()），
# 而作业单的 note 是作者手写的简称 —— 现场实测四处对不上：
#
#     note 写        世界里的车站名
#     朗园      ->   朗源
#     叶楼      ->   叶楼村
#     上水      ->   上水村
#     莫氏      ->   莫氏岛
#
# 用户口径（2026-10-01）：改作业单 note 去对齐世界站名（改站名会连带影响站牌/地图）。
#
# 三条守卫：
#   1. 默认**只报告不写盘**（要落盘加 -Apply）；
#   2. 有 dev 服务端/客户端在跑时拒绝写盘 —— 服务端停机保存时会用内存里那份旧作业单覆盖文件，
#      在线改等于白改（确实要强行改加 -AllowWhileRunning）；
#   3. 幂等：别名后面已经跟着全名时不再替换（"叶楼村" 不会变成 "叶楼村村"）。
#
# 用法：
#     pwsh -File mmtr\scripts\fix-job-notes-station-names.ps1
#     pwsh -File mmtr\scripts\fix-job-notes-station-names.ps1 -Apply
param(
	[string]$JobsPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'game\fabric\run\world\mtr\minecraft\overworld\mmtr-jobs.json'),
	[switch]$Apply,
	[switch]$AllowWhileRunning,
	[switch]$Strict
)

$ErrorActionPreference = 'Stop'

# 站名别称 -> 世界站名。**这张表与引擎探针 MmtrPidDevWorldProbeTests.KNOWN_NOTE_ALIASES 是同一张**，
# 改一处要改另一处（那边负责"以后对不上就报红"，这里负责"把现场数据改对"）。
$aliases = [ordered]@{
	'朗园' = '朗源'
	'叶楼' = '叶楼村'
	'上水' = '上水村'
	'莫氏' = '莫氏岛'
}

# note 字段的正则（现场文件是紧凑 JSON："note":"开到 下水1台"；无转义引号、无反斜杠）
$notePattern = '"note"\s*:\s*"([^"]*)"'

if (-not (Test-Path -LiteralPath $JobsPath)) {
	throw "找不到作业单文件：$JobsPath"
}

$raw = Get-Content -LiteralPath $JobsPath -Raw
$notes = [regex]::Matches($raw, $notePattern)
if ($notes.Count -eq 0) {
	throw "这个文件里一条 note 都没有（$JobsPath）—— 路径是不是指错了？"
}

$running = @(Get-CimInstance Win32_Process -Filter "Name like '%java%'" -ErrorAction SilentlyContinue |
	Where-Object { $_.CommandLine -and ($_.CommandLine -match 'mmtr-server-args|devlaunchinjector') })
$runningPids = ($running | ForEach-Object { $_.ProcessId }) -join ', '
if ($running.Count -gt 0 -and $Apply -and -not $AllowWhileRunning) {
	# 注意：这条消息写成**一整行**（不是跨行 + 拼接）—— PowerShell 不认"下一行以 + 开头"这种续行。
	throw "有 dev 会话在跑（pid $runningPids）：服务端停机保存会把内存里那份旧作业单写回 $JobsPath，在线改等于白改。先把服务端停掉再跑，或加 -AllowWhileRunning。"
}

# 别名后面已经跟着全名的剩余部分时不动它（幂等的关键）
$guard = @{}
foreach ($from in $aliases.Keys) {
	$to = $aliases[$from]
	$guard[$from] = if ($to.StartsWith($from) -and $to.Length -gt $from.Length) { $to.Substring($from.Length) } else { '' }
}

$counts = @{}
foreach ($from in $aliases.Keys) {
	$counts[$from] = 0
}
$touchedNotes = 0
$firstChange = $null

# 替换只改 note 的**值**那一段（不重建 "note":"…" 外壳，避免空格写法被顺手改掉）。
#
# 为什么不用 [regex]::Replace 的 MatchEvaluator 脚本块：那种脚本块跑在子作用域里，
# 里面的 $touchedNotes++ 改的是一份副本（实测：别名命中数对了，但"合计改动 0 条"）——
# 于是这里改成"倒着做字符串手术"：从后往前替换，前面的下标就不会失效。
$noteMatches = [regex]::Matches($raw, $notePattern)
$builder = New-Object System.Text.StringBuilder ($raw)
for ($i = $noteMatches.Count - 1; $i -ge 0; $i--) {
	$note = $noteMatches[$i].Groups[1].Value
	$replaced = $note
	foreach ($from in $aliases.Keys) {
		$lookahead = if ($guard[$from]) { '(?!' + [regex]::Escape($guard[$from]) + ')' } else { '' }
		$before = $replaced
		$replaced = [regex]::Replace($replaced, [regex]::Escape($from) + $lookahead, $aliases[$from])
		if ($replaced -ne $before) {
			$counts[$from]++
		}
	}
	if ($replaced -ne $note) {
		$touchedNotes++
		if (-not $firstChange) {
			$firstChange = "「$note」 -> 「$replaced」"
		}
		[void]$builder.Remove($noteMatches[$i].Groups[1].Index, $noteMatches[$i].Groups[1].Length)
		[void]$builder.Insert($noteMatches[$i].Groups[1].Index, $replaced)
	}
}
$updated = $builder.ToString()

# ── 站名核对：note 里出现的"站名段"必须都是世界车站表里的名字 ────────────────────────────────
#
# 为什么要有这一步：脚本的目标不是"替换成功"，而是"水牌上的名字与作业单对得上"。
# 世界车站名从存档里读 —— MTR 的存档是**二进制-ish** 的：名字夹在长度前缀字节之间
# （实测 `name` 后是 A9、名字后是 A5），按 UTF-8 解出来那些字节就是 U+FFFD，
# 所以"分隔符"这一类比的是**控制字符或 U+FFFD**，"值"这一类收字母/数字/空格（够用即可，不当解析器）。
function Get-WorldStationNames([string]$p) {
	$stationsDir = Join-Path (Split-Path -Parent $p) 'stations'
	if (-not (Test-Path -LiteralPath $stationsDir)) {
		return @()
	}
	$names = New-Object System.Collections.Generic.List[string]
	foreach ($file in Get-ChildItem -LiteralPath $stationsDir -Recurse -File) {
		$text = Get-Content -LiteralPath $file.FullName -Raw
		foreach ($m in [regex]::Matches($text, 'name[\x00-\x1f\uFFFD]+([\p{L}\p{N} ]+)')) {
			[void]$names.Add($m.Groups[1].Value)
		}
	}
	return $names
}

# note 里的站名段："开到 鸥湾1台" / "鸥湾1台 开关门停站" -> "鸥湾"；"回库（…）"这类取不出 -> 跳过
function Get-NoteStationTokens([string]$content) {
	$tokens = New-Object System.Collections.Generic.List[string]
	foreach ($m in [regex]::Matches($content, $notePattern)) {
		$note = $m.Groups[1].Value.Replace('开到', '').Trim()
		$platformMark = $note.IndexOf('台')
		if ($platformMark -le 0) {
			continue
		}
		$end = $platformMark
		while ($end -gt 0 -and [char]::IsDigit($note[$end - 1])) {
			$end--
		}
		$token = $note.Substring(0, $end).Trim()
		if ($token) {
			[void]$tokens.Add($token)
		}
	}
	return $tokens
}

$stationNames = Get-WorldStationNames $JobsPath
$tokens = @(Get-NoteStationTokens $updated | Sort-Object -Unique)

# 站名核对：note 里的每一个"站名段"都必须是世界车站表里的名字（这才是这次改动的验收判据）。
# 改前/改后各报一次 —— "改了 935 条"只说明脚本动了手，"改前 4 个对不上、改后 0 个"才说明改对了。
function Get-UnknownNoteStations([string]$content) {
	if ($stationNames.Count -eq 0) {
		return @()
	}
	return @(Get-NoteStationTokens $content | Sort-Object -Unique | Where-Object { $stationNames -notcontains $_ })
}

$unknownBefore = @(Get-UnknownNoteStations $raw)
$unknown = @(Get-UnknownNoteStations $updated)

Write-Host "作业单文件：$JobsPath"
Write-Host ("note 条数：{0}    文件字符数：{1}" -f $notes.Count, $raw.Length)
Write-Host ''
foreach ($from in $aliases.Keys) {
	Write-Host ("  {0} -> {1}：{2} 条 note 命中" -f $from, $aliases[$from], $counts[$from])
}
Write-Host ("  合计改动 note：{0} 条" -f $touchedNotes)
if ($firstChange) {
	Write-Host ("  例：{0}" -f $firstChange)
}

Write-Host ''
if ($stationNames.Count -eq 0) {
	Write-Host "  站名核对：读不到世界车站表（$(Join-Path (Split-Path -Parent $JobsPath) 'stations') 不存在）—— 跳过"
} else {
	Write-Host ("  站名核对：note 里有 {0} 个不同的站名段（{1}）；世界里 {2} 个车站" -f $tokens.Count, ($tokens -join '/'), $stationNames.Count)
	if ($unknownBefore.Count -eq 0) {
		Write-Host '  改前：✔ 本来就全部是世界车站名'
	} else {
		Write-Host ("  改前：✘ {0} 个站名段不是世界车站名 —— {1}" -f $unknownBefore.Count, ($unknownBefore -join ', '))
	}
	if ($unknown.Count -eq 0) {
		Write-Host '  改后：✔ 全部是世界车站名 —— 水牌上的字与作业单对得上'
	} else {
		Write-Host ("  改后：✘ 仍有 {0} 个对不上 —— {1}（别称表要补，或作业单要改）" -f $unknown.Count, ($unknown -join ', '))
	}
}

if ($touchedNotes -eq 0) {
	Write-Host ''
	Write-Host '没有需要改的 note（要么已经对齐了，要么这张别称表要更新）。'
	if ($Strict -and $unknown.Count -gt 0) {
		throw "严格模式：还有 $($unknown.Count) 个站名段不是世界车站名（$($unknown -join ', ')）"
	}
	return
}

# 写盘前自检：改动量必须恰好等于"别名换全名"带来的长度差（说明只动了 note 里的别称）
$expectedDelta = 0
foreach ($from in $aliases.Keys) {
	$expectedDelta += ($aliases[$from].Length - $from.Length) * $counts[$from]
}
$actualDelta = $updated.Length - $raw.Length
if ($actualDelta -ne $expectedDelta) {
	throw "自检失败：字符数变化 $actualDelta，期望 $expectedDelta（说明改动不止发生在 note 的别称上）"
}

if (-not $Apply) {
	Write-Host ''
	Write-Host '（只报告，没有写盘。要落盘加 -Apply；服务端在跑时请先停服。）'
	if ($Strict -and $unknown.Count -gt 0) {
		throw "严格模式：还有 $($unknown.Count) 个站名段不是世界车站名"
	}
	return
}

$backup = $JobsPath + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
Copy-Item -LiteralPath $JobsPath -Destination $backup

# UTF-8 **无 BOM**：引擎按 UTF-8 读，BOM 会变成一个不可见的首字符混进第一个键名
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($JobsPath, $updated, $utf8NoBom)

Write-Host ''
Write-Host ("已写盘（改动 {0} 条 note，字符数 {1}），原文件备份在 {2}" -f $touchedNotes, $actualDelta, (Split-Path -Leaf $backup))
if ($Strict -and $unknown.Count -gt 0) {
	throw "严格模式：写盘之后仍有 $($unknown.Count) 个站名段不是世界车站名（$($unknown -join ', ')）"
}
