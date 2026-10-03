<#
.SYNOPSIS
  Make the world's parked stock agree with the model dimensions the resource packs declare.

.DESCRIPTION
  A vehicle's dimensions live in TWO places and nothing keeps them in sync:

    * the resource pack - assets/mtr/mtr_custom_resources.json declares the model's
      length / width / bogie positions (the packager writes these from carLengthBlocks etc.);
    * the world save    - world/mtr/<dim>/<dim>/mmtr-rolling-stock.json stores a COPY per parked car.

  The engine lays out a consist from the SAVE copy (Siding.getTotalVehicleLength ->
  VehicleCar.getTotalLength), while the client draws and rides an anchor space built from the MODEL.
  When the two disagree the train is physically the wrong size: for the BR101 the save still said
  length 16.0 while the model is 32.3729, so the car's half-length in the engine was 8.0 m while the
  driver's seat anchors sit at car-local z = +=12.0 m - the driver was going to be placed 4 m BEYOND
  the end of their own car.

  This script reports every mismatch and, with -Apply, rewrites the save copy from the pack.

.PARAMETER Save
  The mmtr-rolling-stock.json to fix. Defaults to the overworld save of the dev run.

.PARAMETER PackDir
  Resource pack directory to read the model dimensions from. Defaults to the dev run's resourcepacks.
  EVERY zip in there is read, so the newest pack wins (it is read last).

.PARAMETER Apply
  Write the changes. Without it this is a dry run and nothing is modified.

.PARAMETER Force
  Allow writing while a dedicated server is still running. The server owns this file and rewrites it
  on its next save, so without -Force the script refuses rather than lose the edit silently.

.EXAMPLE
  pwsh -File mmtr\scripts\fix-rolling-stock-dims.ps1
  pwsh -File mmtr\scripts\fix-rolling-stock-dims.ps1 -Apply
#>
param(
	[string]$Save,
	[string]$PackDir,
	[switch]$Apply,
	[switch]$Force
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$mcRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Save) { $Save = Join-Path $mcRoot 'mmtr\game\fabric\run\world\mtr\minecraft\overworld\mmtr-rolling-stock.json' }
if (-not $PackDir) { $PackDir = Join-Path $mcRoot 'mmtr\game\fabric\run\resourcepacks' }

if (-not (Test-Path $Save)) { throw "save not found: $Save" }
if (-not (Test-Path $PackDir)) { throw "resource pack dir not found: $PackDir" }

# ---- the model dimensions, straight out of the packs ------------------------------------------------
$declared = @{}
$packs = Get-ChildItem -Path $PackDir -File -Filter *.zip | Sort-Object LastWriteTime
if ($packs.Count -eq 0) { throw "no resource packs in $PackDir" }
foreach ($pack in $packs) {
	$zip = [System.IO.Compression.ZipFile]::OpenRead($pack.FullName)
	try {
		$entry = $zip.Entries | Where-Object { $_.FullName -eq 'assets/mtr/mtr_custom_resources.json' } | Select-Object -First 1
		if (-not $entry) { continue }
		$reader = New-Object System.IO.StreamReader($entry.Open())
		$text = $reader.ReadToEnd()
		$reader.Close()
		$resources = $text | ConvertFrom-Json
		foreach ($vehicle in $resources.vehicles) {
			if (-not $vehicle.id) { continue }
			$declared[$vehicle.id] = [pscustomobject]@{
				length            = [double]$vehicle.length
				width             = [double]$vehicle.width
				bogie1Position    = [double]$vehicle.bogie1Position
				bogie2Position    = [double]$vehicle.bogie2Position
				couplingPadding1  = [double]$vehicle.couplingPadding1
				couplingPadding2  = [double]$vehicle.couplingPadding2
				pack              = $pack.Name
			}
		}
	} finally {
		$zip.Dispose()
	}
}

Write-Host '== model dimensions declared by the packs =='
foreach ($id in ($declared.Keys | Sort-Object)) {
	$d = $declared[$id]
	Write-Host ("  {0,-12} length={1,-10} width={2,-6} bogies={3} / {4}   [{5}]" -f $id, $d.length, $d.width, $d.bogie1Position, $d.bogie2Position, $d.pack)
}

# ---- the save copy ----------------------------------------------------------------------------------
# Read as UTF-8 explicitly: Windows PowerShell 5.1 would otherwise decode it with the ANSI code page.
$json = [System.IO.File]::ReadAllText($Save, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

$changes = New-Object System.Collections.Generic.List[object]
$unknown = New-Object System.Collections.Generic.List[string]
$before = 0.0
$after = 0.0

foreach ($depot in $json.depots) {
	foreach ($siding in $depot.sidings) {
		foreach ($car in $siding.cars) {
			$id = $car.vehicleId
			if (-not $declared.ContainsKey($id)) {
				if ($unknown -notcontains $id) { $unknown.Add($id) }
				continue
			}
			$d = $declared[$id]
			$before += [double]$car.length

			# Only the fields the pack actually declares are touched; everything else in the save is
			# left exactly as it was (the engine owns capacity/powered/consistTypeId).
			$fields = @('length', 'width', 'bogie1Position', 'bogie2Position', 'couplingPadding1', 'couplingPadding2')
			foreach ($field in $fields) {
				$old = if ($null -eq $car.$field) { $null } else { [double]$car.$field }
				$new = $d.$field
				if ($null -eq $new) { continue }
				if ($null -eq $old -or [math]::Abs($old - $new) -gt 1.0E-9) {
					$changes.Add([pscustomobject]@{ Siding = $siding.name; Vehicle = $id; Field = $field; Old = $old; New = $new })
				}
			}
			$after += $d.length
		}
	}
}

Write-Host ''
if ($changes.Count -eq 0) {
	Write-Host 'OK - every parked car already matches its pack.' -ForegroundColor Green
	exit 0
}

Write-Host "== differences ($($changes.Count)) =="
$changes | ForEach-Object {
	Write-Host ("  {0,-14} {1,-8} {2,-18} {3}  ->  {4}" -f $_.Siding, $_.Vehicle, $_.Field, $(if ($null -eq $_.Old) { '(unset)' } else { $_.Old }), $_.New)
}
Write-Host ''
Write-Host ("train length on the affected sidings: {0} -> {1} m" -f $before, $after)

# The consist has to FIT the siding rail (Siding.mmtrConsistWalkerFromYard refuses a train longer than
# the rail). A longer car can therefore turn "spawned fine" into "no consist body, and the cab command
# answers 不是编组体车". Say so here rather than let it be discovered in game.
if ($after -gt $before) {
	Write-Host ''
	Write-Host "NOTE: the stock got $([math]::Round($after - $before, 3)) m longer. A consist must fit its siding rail" -ForegroundColor Yellow
	Write-Host "      (otherwise no consist body is built and the cab command refuses it), so re-spawn or" -ForegroundColor Yellow
	Write-Host "      re-park the stock after this and check the yard still accepts it." -ForegroundColor Yellow
}
if ($unknown.Count -gt 0) {
	Write-Host ''
	Write-Host "  (no pack declares these vehicle ids, left alone: $($unknown -join ', '))" -ForegroundColor DarkGray
}

if (-not $Apply) {
	Write-Host ''
	Write-Host 'dry run - nothing written. Re-run with -Apply to patch the save.' -ForegroundColor Yellow
	exit 0
}

$runners = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
	Where-Object { $_.CommandLine -match 'KnotServer' }
if ($runners -and -not $Force) {
	Write-Host ''
	Write-Host 'REFUSING to write: a dedicated server is still running and owns this file.' -ForegroundColor Red
	Write-Host 'Stop the server first, then re-run with -Apply (or pass -Force to override).' -ForegroundColor Red
	exit 2
}

# Back up next to the save so a wrong edit is one copy away from being undone.
$backup = "$Save.bak-dims"
Copy-Item -Force $Save $backup

foreach ($depot in $json.depots) {
	foreach ($siding in $depot.sidings) {
		foreach ($car in $siding.cars) {
			if (-not $declared.ContainsKey($car.vehicleId)) { continue }
			$d = $declared[$car.vehicleId]
			foreach ($field in @('length', 'width', 'bogie1Position', 'bogie2Position', 'couplingPadding1', 'couplingPadding2')) {
				if ($null -ne $d.$field) { $car.$field = $d.$field }
			}
		}
	}
}

$out = $json | ConvertTo-Json -Depth 12 -Compress
[System.IO.File]::WriteAllText($Save, $out, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "written : $Save" -ForegroundColor Green
Write-Host "backup  : $backup"
