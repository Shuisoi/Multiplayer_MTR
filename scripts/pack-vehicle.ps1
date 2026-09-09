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
foreach ($key in @('sourceObj', 'textureDir', 'outputDir')) { $params.$key = Resolve-McRoot $params.$key }
if (-not (Test-Path $params.sourceObj)) { Fail "sourceObj not found: $($params.sourceObj)" }
if (-not (Test-Path $params.textureDir)) { Fail "textureDir not found: $($params.textureDir)" }

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

$zip = Join-Path $params.outputDir "$outputPackName.zip"
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
	"assets/mtr/definition_$id.json",
	"assets/mtr/$id/$id.obj",
	"assets/mtr/$id/$id.mtl"
)
foreach ($entry in $required) {
	if ($entries -notcontains $entry) { $problems += "missing entry: $entry" }
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
  doorway_door_* / floor     generated by the packager, do not model them
  mmtr_hud[_<cab>]           dashboard face: centre = panel centre, normal = towards the driver,
                             the edge closest to world +Y = "up", the other = "right";
                             width/height are measured in metres. Optional fields:
                             panelFlipU, panelPxPerMetre, panelTwoSided.
  mmtr_cabdoor_<cab>_<n>     driver's cab door (cab 1 = A end, 2 = B end); also rendered as a
                             visible part named cabdoor_<cab>_<n>
  mmtr_seat_<cab>            driver seat / eye point (optional; normal = direction of travel)
  mmtr_ack_<cab>             AWS acknowledge button (optional)
  cab 1 = A end, cab 2 = B end; no suffix means cab 1. Anchors are data-only (stripped from the
  geometry) except mmtr_cabdoor_*, which stays visible.
'@ | Write-Host
