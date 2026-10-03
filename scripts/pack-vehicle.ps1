<#
.SYNOPSIS
  MMTR vehicle pack: one command from a Blender OBJ to a validated resource pack zip.

.DESCRIPTION
  Wraps tools/obj-mtr-packager/pack_vehicle.js (the geometry/JSON/zip generator) with the checks that
  were previously done by hand, so a broken pack cannot reach the game:

    1. config sanity      - the config parses, the source OBJ and texture folder exist
    2. packaging          - runs the node packager
    3. zip validation     - every entry name is lowercase (MTR lowercases resource paths, an uppercase
                            name silently loads as an empty model), and the required MTR files plus the
                            OBJ/MTL/at least one PNG are present
    4. anchor report      - parses mmtr_anchors_<id>.json and prints the anchors with the fields the
                            client reads (kind/cab/size, plus panelFlipU/panelPxPerMetre/panelTwoSided)
    5. naming reminder    - prints the model naming conventions the pack relies on

.PARAMETER Config
  Path to the vehicle config json (see tools/obj-mtr-packager/example/vehicle.hst_h.json).

.PARAMETER Version
  Optional pack version. When given, outputPackName becomes "<base>_v<Version>" so packs never
  overwrite each other and the game always keeps the newest one enabled.

.EXAMPLE
  powershell -File mmtr\scripts\pack-vehicle.ps1 mmtr\tools\obj-mtr-packager\example\vehicle.hst_h.json -Version 13
#>
param(
	[Parameter(Mandatory = $true)][string]$Config,
	[int]$Version = 0
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repo = Split-Path -Parent $PSScriptRoot
$packager = Join-Path $repo 'tools\obj-mtr-packager\pack_vehicle.js'
# 配置里的路径可用 ${MC_ROOT} 占位符（见 tools/obj-mtr-packager/paths.js），避免机器相关绝对路径入库。
$mcRoot = Split-Path -Parent $repo
function Resolve-McRoot([string]$value) { if ($value) { return $value.Replace('${MC_ROOT}', $mcRoot) } else { return $value } }

function Fail($message) {
	Write-Host ''
	Write-Host "PACK FAILED: $message" -ForegroundColor Red
	exit 1
}

# ---- 1. config sanity ------------------------------------------------------------------------------
if (-not (Test-Path $Config)) { Fail "config not found: $Config" }
$Config = (Resolve-Path $Config).Path
if (-not (Test-Path $packager)) { Fail "packager not found: $packager" }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail 'node is not on PATH' }

try {
	# Read as UTF-8 explicitly: Windows PowerShell 5.1 defaults to the ANSI code page, which mangles
	# the Chinese name/description fields and makes ConvertFrom-Json fail.
	$params = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
} catch {
	Fail "config is not valid json: $($_.Exception.Message)"
}

$id = $params.id
if (-not $id) { Fail 'config has no "id"' }

# ${MC_ROOT} 占位符**由 node 侧 paths.js 统一解析**（优先环境变量 MC_ROOT，
# 否则按 packager 自身位置推断），所以这里把原值交给打包器，不要自己替换。
# 这里只为"存在性预检"算一份本地路径：注意 $repo = <MC_ROOT>\mmtr，
# 因此 $mcRoot 只往上退**一层**（原来退成 $repo 的父目录用 Split-Path -Parent $repo 是对的，
# 但下面模板替换曾把占位符换成空串，导致预检和打包器拿到不同路径）。
$mcRoot = Split-Path -Parent $repo
function Resolve-McRoot([string]$value) {
	if (-not $value) { return $value }
	return $value.Replace('${MC_ROOT}', $mcRoot)
}
foreach ($key in @('sourceObj', 'textureDir', 'outputDir')) {
	$resolved = Resolve-McRoot $params.$key
	if (-not $resolved) { Fail "config has no `"$key`"" }
	if (-not (Test-Path $resolved)) { Fail "$key not found: $resolved" }
}

$outputPackName = $params.outputPackName
if ($Version -gt 0) {
	$base = ($outputPackName -replace '_v\d+$', '')
	$outputPackName = "${base}_v$Version"
}

Write-Host "== MMTR vehicle pack =="
Write-Host "  id           : $id"
Write-Host "  config       : $Config"
Write-Host "  source obj   : $($params.sourceObj)"
Write-Host "  pack name    : $outputPackName"

# ---- 2. run the packager ---------------------------------------------------------------------------
$packerConfig = $Config
if ($Version -gt 0) {
	$packerConfig = Join-Path $env:TEMP "mmtr-pack-$id-$Version.json"
	$params.outputPackName = $outputPackName
	# Write without a BOM: node's JSON.parse rejects a leading BOM.
	[System.IO.File]::WriteAllText($packerConfig, ($params | ConvertTo-Json -Depth 12), (New-Object System.Text.UTF8Encoding($false)))
}

& node $packager $packerConfig
if ($LASTEXITCODE -ne 0) { Fail "packager exited with $LASTEXITCODE" }

$zip = Join-Path (Resolve-McRoot $params.outputDir) "$outputPackName.zip"
if (-not (Test-Path $zip)) { Fail "packager produced no zip at $zip" }

# ---- 3. zip validation -----------------------------------------------------------------------------
$zipFile = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
	$entries = $zipFile.Entries | ForEach-Object { $_.FullName }
} finally {
	$zipFile.Dispose()
}

$problems = @()
$upper = $entries | Where-Object { $_ -cmatch '[A-Z]' }
if ($upper) { $problems += "entries with uppercase letters (MTR lowercases paths, so these load as empty): $($upper -join ', ')" }

$required = @(
	'pack.mcmeta',
	'assets/mtr/mtr_custom_resources.json',
	"assets/mtr/properties_$id.json",
	"assets/mtr/definition_$id.json"
)
foreach ($entry in $required) {
	if ($entries -notcontains $entry) { $problems += "missing entry: $entry" }
}

# 模型/贴图**不能**按 "<id>.obj" 猜文件名。打包器写的是 assets/mtr/<id>/<源文件名>.obj：
# 实测 SAF420（以及现役的 SAF420_v4.zip）= modelResource "mtr:saf420cab_a/saf420cab.obj"
# —— 目录是 id、文件名是源 OBJ 的名字。按 "<id>.obj" 校验会把**完全正常的包**判成失败
# （vehicle.saf420cab_a.json 一直如此，只有源文件名恰好等于 id 时才碰巧通过）。
# 真正的判据是：**包里声明的每一条资源路径都必须真的存在**（游戏就是照这些路径去找的）。
$resourceProblems = @()
$zipFile2 = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
	$resourceEntry = $zipFile2.Entries | Where-Object { $_.FullName -eq 'assets/mtr/mtr_custom_resources.json' }
	if ($resourceEntry) {
		$reader = New-Object System.IO.StreamReader($resourceEntry.Open())
		$customResources = $reader.ReadToEnd() | ConvertFrom-Json
		$reader.Close()
		foreach ($vehicle in $customResources.vehicles) {
			foreach ($model in $vehicle.models) {
				foreach ($field in @('modelResource', 'textureResource', 'modelPropertiesResource', 'positionDefinitionsResource')) {
					$value = $model.$field
					if (-not $value) { continue }
					if ($value -like 'minecraft:*') { continue }   # 原版贴图在游戏自己的 jar 里，包里本来就不该有
					$path = $value -replace '^mtr:', 'assets/mtr/' -replace '^minecraft:', 'assets/minecraft/'
					if ($entries -notcontains $path) { $resourceProblems += "declared but missing from the zip: $field = $value" }
				}
			}
		}
	} else {
		$resourceProblems += 'assets/mtr/mtr_custom_resources.json could not be read back for validation'
	}
} finally {
	$zipFile2.Dispose()
}
$problems += $resourceProblems
# 另外做一条形状检查：id 目录下必须有且只有一份 OBJ 与配对的 MTL（名字不管，路径由上面那条保证）。
foreach ($extension in @('.obj', '.mtl')) {
	$hits = @($entries | Where-Object { $_ -match "^assets/mtr/$id/.*$([regex]::Escape($extension))$" })
	if ($hits.Count -eq 0) { $problems += "no $extension under assets/mtr/$id/" }
}
if (-not ($entries | Where-Object { $_ -match "^assets/mtr/$id/.*\.png$" })) { $problems += "no textures under assets/mtr/$id/" }

$anchorEntry = "assets/mtr/mmtr_anchors_$id.json"
$hasAnchors = $entries -contains $anchorEntry

if ($problems.Count -gt 0) {
	Write-Host ''
	$problems | ForEach-Object { Write-Host "  ! $_" -ForegroundColor Yellow }
	Fail 'zip validation failed'
}

$zipSizeKb = [math]::Round((Get-Item $zip).Length / 1KB, 1)
Write-Host ''
Write-Host "== pack ok ==" -ForegroundColor Green
Write-Host "  zip    : $zip ($zipSizeKb KB, $($entries.Count) entries)"
Write-Host "  anchors: $(if ($hasAnchors) { $anchorEntry } else { 'none (model has no mmtr_* faces)' })"

# ---- 3.6 ★★ 打包后自检（"造出来了但游戏里看不见"那一类静默失败）---------------------------------
# 本项目反复踩：包造好了、进游戏车不见。每回原因都不同（notes/191 同名组覆盖 / 192 导出轴向 /
# 194 OBJ 布局 / R56 r35 用 64x64 调色板盖掉 256x256 涂装图 -> UV 落进透明区）。
# 这里把每条已知会静默失败的条件都变成显式断言，**失败就 exit 1，不许发这个包**。
$checkScript = Join-Path $PSScriptRoot '..\tools\obj-mtr-packager\check_pack.py'
if (Test-Path $checkScript) {
	Write-Host ''
	Write-Host '== post-pack self check ==' -ForegroundColor Cyan
	$py = if (Get-Command python -ErrorAction SilentlyContinue) { 'python' } else { 'py' }
	& $py $checkScript $zip --config $Config
	if ($LASTEXITCODE -ne 0) {
		Fail "post-pack self check failed (see the [FAIL] lines above) - do NOT ship $zip"
	}
} else {
	Write-Host '  (skipped: check_pack.py not found)' -ForegroundColor DarkYellow
}

# ---- 4. anchor report ------------------------------------------------------------------------------
if ($hasAnchors) {
	$zipFile = [System.IO.Compression.ZipFile]::OpenRead($zip)
	try {
		$reader = New-Object System.IO.StreamReader($zipFile.GetEntry($anchorEntry).Open())
		$anchors = ($reader.ReadToEnd() | ConvertFrom-Json).anchors
		$reader.Close()
	} finally {
		$zipFile.Dispose()
	}

	Write-Host ''
	Write-Host '  name                  kind     cab  size (w x h m)   panel'
	foreach ($anchor in $anchors) {
		$panel = if ($anchor.kind -eq 'hud') {
			"flipU=$([bool]$anchor.panelFlipU) pxPerMetre=$(if ($anchor.panelPxPerMetre) { $anchor.panelPxPerMetre } else { 'default' }) twoSided=$([bool]$anchor.panelTwoSided)"
		} else { '-' }
		'{0,-22}{1,-9}{2,-5}{3,-18}{4}' -f $anchor.name, $anchor.kind, $(if ($anchor.cab) { $anchor.cab } else { '-' }), ('{0:N2} x {1:N2}' -f $anchor.widthM, $anchor.heightM), $panel | Write-Host
	}

	$hudCount = ($anchors | Where-Object { $_.kind -eq 'hud' }).Count
	if ($hudCount -gt 1) {
		Write-Host "  note: $hudCount hud anchors - the client only draws the dashboard of the car the driver rides." -ForegroundColor DarkGray
	}
}

# ---- 5. naming reminder ----------------------------------------------------------------------------
Write-Host ''
Write-Host '== model naming (what the pack and the client read) =='
@'
  body                       all static car geometry (one part)
  interior                   interior fittings (INTERIOR_TRANSLUCENT, see-through)
  door_l_<n> / door_r_<n>    passenger door leaves, n numbered nose -> tail, one object per leaf
  <leaf>_glass               that leaf's window glass. MUST be its own object whose name is the leaf
                             name + "_glass": the packager then gives it INTERIOR_TRANSLUCENT and the
                             SAME doorZMultiplier as its leaf, so the glass opens with the door
                             (see notes/353). Do NOT merge it into the leaf - one part = one
                             renderStage, and a door leaf is locked to EXTERIOR (CUTOUT_BRIGHT would
                             make the whole leaf unlit).
  doorway_door_* / floor     generated by the packager, do not model them
  mmtr_hud[_<cab>]           dashboard face: centre = panel centre, normal = towards the driver,
                             the edge closest to world +Y = "up", the other = "right";
                             width/height are measured in metres. Optional fields:
                             panelFlipU, panelPxPerMetre, panelTwoSided.
  mmtr_cabdoor_<cab>_<n>     driver's cab door (cab 1 = A end, 2 = B end); also rendered as a
                             visible part named cabdoor_<cab>_<n>
  mmtr_seat_<cab>            driver seat / eye point (optional; normal = direction of travel)
  mmtr_ack_<cab>             AWS acknowledge button (optional)
  mmtr_pid_<cab>[_<n>]       destination board (水牌): centre = board centre, normal = AWAY from the car
                             (the reader stands on the platform), up = the text's up, size in metres.
                             The client paints plate + text (班次号 + 本趟终点) - do NOT also model a
                             visible board face; anchor geometry is stripped. Pairs per end like the
                             lamps, and the B-end car must rename it (_1 -> _2). Judged by
                             mmtr/tools/anchor-check/verify_pid.js (P1..P7) - notes/357.
  mmtr_next_<cab>[_<n>]      next-station board (下一站 X): same, but the normal points INTO the car
  cab 1 = A end, cab 2 = B end; no suffix means cab 1. Anchors are data-only (stripped from the
  geometry) except mmtr_cabdoor_*, which stays visible.
'@ | Write-Host
