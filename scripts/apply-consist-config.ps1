# Applies the MMTR consist-type config to every world in the dev run folder.
# Usage: powershell -File scripts/apply-consist-config.ps1 [-SavesRoot <path>] [-ConfigFile <path>]
param(
  [string]$SavesRoot = "$PSScriptRoot\..\game\fabric\run\saves",
  [string]$ConfigFile = "$PSScriptRoot\..\config-example\consist-types.json"
)
$savesRoot = [System.IO.Path]::GetFullPath($SavesRoot)
$configFile = [System.IO.Path]::GetFullPath($ConfigFile)
if (-not (Test-Path $configFile)) { throw "config not found: $configFile" }
$installed = 0
Get-ChildItem $savesRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
  $worldMtr = Join-Path $_.FullName 'mtr'
  if (Test-Path $worldMtr) {
    Get-ChildItem $worldMtr -Recurse -Directory | ForEach-Object {
      $hasRails = Test-Path (Join-Path $_.FullName 'rails')
      $hasConfig = Test-Path (Join-Path $_.FullName 'mmtr-consist-types.json')
      if ($hasRails) {
        if (-not $hasConfig) {
          Copy-Item -Force $configFile (Join-Path $_.FullName 'mmtr-consist-types.json')
          $installed++
          Write-Output ('installed -> ' + $_.FullName)
        } else {
          Write-Output ('already present -> ' + (Join-Path $_.FullName 'mmtr-consist-types.json'))
        }
      }
    }
  }
}
Write-Output ('done, installed into ' + $installed + ' dimension(s)')
