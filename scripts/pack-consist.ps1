<#
.SYNOPSIS
  MMTR consist pack: several vehicle models in ONE resource pack, from config to validated zip.

.DESCRIPTION
  Wraps tools/obj-mtr-packager/pack_consist.js with the same checks pack-vehicle.ps1 does for a
  single car, because a formation is only ONE model id away from being wrong in a way nothing reports:

    1. config sanity      - parses, every car has an id / sourceObj / textureDir that exists, ids unique
    2. packaging          - runs pack_consist.js (one zip, one vehicles array, merged sounds)
    3. zip validation     - entry names lowercase and '/'-separated (MTR lowercases resource paths, an
                            uppercase name silently loads as an empty model), required files present per
                            car, and EVERY resource path the pack declares must really be in the zip
    4. per-car self check - check_pack.py runs against the PER-CAR zip pack_consist.js already produced
                            in its work dir. Do NOT point it at the merged zip: check_pack.py reads ONE
                            obj/properties pair (first for materials, last for bounds) and on a
                            3-vehicle pack that mixes two different cars into one verdict.
    5. anchor report      - per car: anchors with their packed-space z, which is what decides
                            "which end of the consist does this cab think it is" (see the orientation
                            notes in consist/saf420.json; the in-game criterion is verify_lights.js L4)
    6. naming reminder    - the model naming conventions the pack relies on

.PARAMETER Config
  Path to the consist config json (see tools/obj-mtr-packager/consist/saf420.json).

.PARAMETER Version
  Optional pack version. When given, packName becomes "<base>_v<Version>".

.EXAMPLE
  pwsh -File mmtr\scripts\pack-consist.ps1 mmtr\tools\obj-mtr-packager\consist\saf420.json
#>
param(
	[Parameter(Mandatory = $true)][string]$Config,
	[int]$Version = 0
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repo = Split-Path -Parent $PSScriptRoot
$mcRoot = Split-Path -Parent $repo
$packager = Join-Path $repo 'tools\obj-mtr-packager\pack_consist.js'

function Fail($message) {
	Write-Host ''
	Write-Host "PACK FAILED: $message" -ForegroundColor Red
	exit 1
}
function Resolve-McRoot([string]$value) {
	if (-not $value) { return $value }
	return $value.Replace('${MC_ROOT}', $mcRoot)
}

# ---- 1. config sanity ------------------------------------------------------------------------------
if (-not (Test-Path $Config)) { Fail "config not found: $Config" }
$Config = (Resolve-Path $Config).Path
if (-not (Test-Path $packager)) { Fail "packager not found: $packager" }
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Fail 'node is not on PATH' }

try {
	$params = [System.IO.File]::ReadAllText($Config, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
} catch {
	Fail "config is not valid json: $($_.Exception.Message)"
}

$outputPackName = $params.packName
if (-not $outputPackName) { Fail 'config has no "packName"' }
if (-not $params.cars -or $params.cars.Count -eq 0) { Fail 'config has no "cars"' }
$outDir = Resolve-McRoot $params.outputDir
if (-not $outDir) { Fail 'config has no "outputDir"' }
if (-not (Test-Path $outDir)) { Fail "outputDir not found: $outDir" }

$ids = @()
foreach ($car in $params.cars) {
	if (-not $car.id) { Fail 'a car has no "id"' }
	if ($ids -contains $car.id) { Fail "duplicate car id: $($car.id)" }
	$ids += $car.id
	foreach ($key in @('sourceObj', 'textureDir')) {
		$resolved = Resolve-McRoot $car.$key
		if (-not $resolved) { Fail "$($car.id) has no `"$key`"" }
		if (-not (Test-Path $resolved)) { Fail "$($car.id) $key not found: $resolved" }
	}
}

if ($Version -gt 0) {
	$base = ($outputPackName -replace '_v\d+$', '')
	$outputPackName = "${base}_v$Version"
}

# The packager's work dir: pin it so the per-car zips and params_<i>.json are where we expect.
$workDir = Join-Path $env:TEMP "mmtr-consist-$outputPackName"

Write-Host '== MMTR consist pack =='
Write-Host "  pack name    : $outputPackName"
Write-Host "  config       : $Config"
Write-Host "  cars         : $($ids.Count) ($($ids -join ', '))"
Write-Host "  output dir   : $outDir"
Write-Host "  work dir     : $workDir"

# ---- 2. run the packager ---------------------------------------------------------------------------
# Always hand the packager an explicit workDir (so the per-car zips and params_<i>.json are findable)
# and the pack name we resolved above; node's JSON.parse rejects a leading BOM, so write without one.
$packerConfig = Join-Path $env:TEMP "mmtr-consist-$outputPackName.json"
$params.packName = $outputPackName
$params | Add-Member -NotePropertyName workDir -NotePropertyValue $workDir -Force
[System.IO.File]::WriteAllText($packerConfig, ($params | ConvertTo-Json -Depth 12), (New-Object System.Text.UTF8Encoding($false)))

& node $packager $packerConfig
if ($LASTEXITCODE -ne 0) { Fail "packager exited with $LASTEXITCODE" }

$zip = Join-Path $outDir "$outputPackName.zip"
if (-not (Test-Path $zip)) { Fail "packager produced no zip at $zip" }

# ---- 3. zip validation -----------------------------------------------------------------------------
$zipFile = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
	$entries = @($zipFile.Entries | ForEach-Object { $_.FullName })
	$resourceEntry = $zipFile.Entries | Where-Object { $_.FullName -eq 'assets/mtr/mtr_custom_resources.json' } | Select-Object -First 1
	$declared = $null
	if ($resourceEntry) {
		$reader = New-Object System.IO.StreamReader($resourceEntry.Open())
		$declared = $reader.ReadToEnd() | ConvertFrom-Json
		$reader.Close()
	}
} finally {
	$zipFile.Dispose()
}

$problems = @()
$upper = $entries | Where-Object { $_ -cmatch '[A-Z]' }
if ($upper) { $problems += "entries with uppercase letters (MTR lowercases paths, so these load as empty): $($upper -join ', ')" }
$back = $entries | Where-Object { $_ -like '*\*' }
if ($back) { $problems += "entries with backslashes (paths must use '/'): $($back -join ', ')" }

$required = @('pack.mcmeta', 'assets/mtr/mtr_custom_resources.json')
foreach ($id in $ids) {
	$required += "assets/mtr/properties_$id.json"
	$required += "assets/mtr/definition_$id.json"
}
foreach ($entry in $required) {
	if ($entries -notcontains $entry) { $problems += "missing entry: $entry" }
}

if ($declared) {
	$declaredIds = @($declared.vehicles | ForEach-Object { $_.id })
	foreach ($id in $ids) {
		if ($declaredIds -notcontains $id) { $problems += "the pack declares no vehicle with id=$id" }
	}
	foreach ($vehicle in $declared.vehicles) {
		foreach ($model in $vehicle.models) {
			foreach ($field in @('modelResource', 'textureResource', 'modelPropertiesResource', 'positionDefinitionsResource')) {
				$value = $model.$field
				if (-not $value) { continue }
				if ($value -like 'minecraft:*') { continue }   # vanilla textures live in the game jar, not the pack
				$path = $value -replace '^mtr:', 'assets/mtr/' -replace '^minecraft:', 'assets/minecraft/'
				if ($entries -notcontains $path) { $problems += "declared but missing from the zip: $field = $value" }
			}
		}
	}
} else {
	$problems += 'assets/mtr/mtr_custom_resources.json could not be read back for validation'
}

foreach ($id in $ids) {
	foreach ($extension in @('.obj', '.mtl')) {
		$hits = @($entries | Where-Object { $_ -match "^assets/mtr/$id/.*$([regex]::Escape($extension))$" })
		if ($hits.Count -eq 0) { $problems += "no $extension under assets/mtr/$id/" }
	}
	if (-not ($entries | Where-Object { $_ -match "^assets/mtr/$id/.*\.png$" })) { $problems += "no textures under assets/mtr/$id/" }
}

if ($problems.Count -gt 0) {
	Write-Host ''
	$problems | ForEach-Object { Write-Host "  ! $_" -ForegroundColor Yellow }
	Fail 'zip validation failed'
}

$zipSizeKb = [math]::Round((Get-Item $zip).Length / 1KB, 1)
Write-Host ''
Write-Host '== pack ok ==' -ForegroundColor Green
Write-Host "  zip    : $zip ($zipSizeKb KB, $($entries.Count) entries)"

# ---- 4. per-car post-pack self check ---------------------------------------------------------------
$checkScript = Join-Path $repo 'tools\obj-mtr-packager\check_pack.py'
if (Test-Path $checkScript) {
	$py = if (Get-Command python -ErrorAction SilentlyContinue) { 'python' } else { 'py' }
	for ($i = 0; $i -lt $ids.Count; $i++) {
		$id = $ids[$i]
		$carZip = Join-Path $workDir "zips\${id}_tmp.zip"
		$carParams = Join-Path $workDir "params_$i.json"
		if (-not (Test-Path $carZip)) { Fail "per-car zip missing: $carZip" }
		if (-not (Test-Path $carParams)) { Fail "per-car params missing: $carParams" }
		Write-Host ''
		Write-Host "== post-pack self check: $id ==" -ForegroundColor Cyan
		& $py $checkScript $carZip --config $carParams
		if ($LASTEXITCODE -ne 0) { Fail "post-pack self check failed for $id - do NOT ship $zip" }
	}
} else {
	Write-Host '  (skipped: check_pack.py not found)' -ForegroundColor DarkYellow
}

# ---- 4b. per-car anchor checks: the failures that would ship silently -------------------------------
# Every one of these has shipped a broken model in this project before, with no error anywhere:
#   verify_doors      a doorway's two leaves sliding INTO each other (this is a per-car config number,
#                     and rotationDegY=180 mirrors the packed geometry, so the sign set for one car is
#                     WRONG for the other - measured: SAF420_v4 all AWAY, SAF420_v28 all TOWARD)
#   verify_lights     lamp anchor normals pointing into the car, or cab 1/2 swapped on the reversed car
#   verify_mtr_obj    an OBJ MTR reads differently than a viewer does (index collapse -> invisible parts)
$checks = @(
	@{ tool = 'tools\obj-mtr-packager\verify_mtr_obj.js'; args = @('--config') },
	@{ tool = 'tools\anchor-check\verify_doors.js'; args = @('--config') },
	@{ tool = 'tools\anchor-check\verify_lights.js'; args = @() }
)
foreach ($check in $checks) {
	$script = Join-Path $repo $check.tool
	if (-not (Test-Path $script)) { Write-Host "  (skipped: $($check.tool) not found)" -ForegroundColor DarkYellow; continue }
	Write-Host ''
	Write-Host "== per-car check: $([IO.Path]::GetFileName($script)) ==" -ForegroundColor Cyan
	for ($i = 0; $i -lt $ids.Count; $i++) {
		$carParams = Join-Path $workDir "params_$i.json"
		Write-Host "  -- $($ids[$i])" -ForegroundColor DarkGray
		$arguments = @($script) + $check.args + @($carParams)
		& node @arguments
		$code = $LASTEXITCODE
		if ($code -eq 1) { Fail "$([IO.Path]::GetFileName($script)) FAILED for $($ids[$i]) - do NOT ship $zip" }
		if ($code -ge 2) { Write-Host "  ! $([IO.Path]::GetFileName($script)) could not run for $($ids[$i]) (exit $code) - check by hand" -ForegroundColor Yellow }
	}
}

# ---- 5. anchor report (per car; packed-space z is what decides the consist end) ---------------------
foreach ($id in $ids) {
	$anchorEntry = "assets/mtr/mmtr_anchors_$id.json"
	if ($entries -notcontains $anchorEntry) { continue }
	$zipFile = [System.IO.Compression.ZipFile]::OpenRead($zip)
	try {
		$reader = New-Object System.IO.StreamReader($zipFile.GetEntry($anchorEntry).Open())
		$anchors = ($reader.ReadToEnd() | ConvertFrom-Json).anchors
		$reader.Close()
	} finally {
		$zipFile.Dispose()
	}
	Write-Host ''
	Write-Host "  anchors $id (packed space: z sign = which end of the consist)" -ForegroundColor Cyan
	Write-Host '    name                     kind          cab  z         x        y'
	foreach ($anchor in ($anchors | Sort-Object z)) {
		'    {0,-24} {1,-13} {2,-4} {3,-9} {4,-8} {5}' -f $anchor.name, $anchor.kind, $(if ($anchor.cab) { $anchor.cab } else { '-' }), ('{0:N3}' -f $anchor.z), ('{0:N3}' -f $anchor.x), ('{0:N3}' -f $anchor.y) | Write-Host
	}
	$cabs = @($anchors | Where-Object { $_.cab } | ForEach-Object { $_.cab } | Sort-Object -Unique)
	$signs = @($anchors | Where-Object { $_.cab } | ForEach-Object { [math]::Sign($_.z) } | Sort-Object -Unique)
	if ($cabs.Count -gt 1) {
		Write-Host "    ! this car carries anchors for more than one cab ($($cabs -join ',')) - check groupRename" -ForegroundColor Yellow
	}
	if ($signs.Count -gt 1) {
		Write-Host "    ! anchors of one cab sit on BOTH sides of z=0 - check rotationDegY / groupRename" -ForegroundColor Yellow
	}
}

# ---- 6. naming reminder ----------------------------------------------------------------------------
Write-Host ''
Write-Host '== model naming (what the pack and the client read) =='
@'
  body                       all static car geometry (one part)
  interior                   interior fittings
  door_l_<n> / door_r_<n>    passenger door leaves, n numbered nose -> tail, one object per leaf
  <leaf>_glass               that leaf's window glass (opens with the door; see notes/353)
  mmtr_*                     anchors (data); windshield/wipersweep MUST be VISIBLE in the .blend or the
                             OBJ export silently drops them (notes/354)
  door leaves, anchors, rotationDegY and groupRename are all per car - see the config's own notes.
'@ | Write-Host
