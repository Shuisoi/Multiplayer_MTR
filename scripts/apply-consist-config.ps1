# Applies the MMTR consist-type config to every dev world / save.
#
# 2026-09-21（notes/216）：这个脚本以前只装 `run\saves`，而且"文件已存在就跳过" —— 于是
# **服务端真正在用的那份**（loom runServer 的工作目录是 `run\`，世界在 `run\world\`）永远拿不到新条目：
# 现场表现是"三手柄键位不生效、车不动"，日志里 `[MMTR-DRV] 车底解析：… 说话的车=（谁都没配） → NOTCHED`。
# 现在两处都装，并且**增量合并**（缺哪个车底就补哪个、`carTypeIds` 按 key 合并，已存在的不动）。
#
# Usage:
#   pwsh -File mmtr\scripts\apply-consist-config.ps1                       # run\world + run\saves
#   pwsh -File mmtr\scripts\apply-consist-config.ps1 -Roots <a>,<b>        # 自定义
#   pwsh -File mmtr\scripts\apply-consist-config.ps1 -Force                # 整份覆盖（不合并）
param(
  [string[]]$Roots = @(
    "$PSScriptRoot\..\game\fabric\run\world",
    "$PSScriptRoot\..\game\fabric\run\saves"
  ),
  [string]$ConfigFile = "$PSScriptRoot\..\config-example\consist-types.json",
  [switch]$Force
)
$ErrorActionPreference = 'Stop'
$configFile = [System.IO.Path]::GetFullPath($ConfigFile)
if (-not (Test-Path $configFile)) { throw "config not found: $configFile" }
$source = Get-Content -Raw $configFile | ConvertFrom-Json

$installed = 0
$merged = 0
$scanned = 0

foreach ($root in $Roots) {
  $rootPath = [System.IO.Path]::GetFullPath($root)
  if (-not (Test-Path $rootPath)) { Write-Output ("skip (不存在): " + $rootPath); continue }
  # 只认 ...\mtr\<namespace>\<dimension>\mmtr-consist-types.json；跳过备份目录（*.bak-*）与 .bak 文件。
  # （别按目录层级硬判：loom 的 runServer 是 run\world\mtr\minecraft\overworld\，存档那边是
  #   saves\<世界>\mtr\minecraft\overworld\ —— 层级不同、`mtr` 的位置也不同。）
  Get-ChildItem $rootPath -Recurse -Filter 'mmtr-consist-types.json' -ErrorAction SilentlyContinue | ForEach-Object {
    if ($_.FullName -match '\.bak') { return }
    if ($_.FullName -notmatch '\\mtr\\') { return }
    $scanned++
    if (-not (Test-Path $_.FullName)) {
      Copy-Item -Force $configFile $_.FullName
      $installed++
      Write-Output ('installed -> ' + $_.FullName)
      return
    }
    if ($Force) {
      Copy-Item -Force $configFile $_.FullName
      $installed++
      Write-Output ('overwritten -> ' + $_.FullName)
      return
    }
    $target = Get-Content -Raw $_.FullName | ConvertFrom-Json
    $changed = $false

    # 1) 缺哪个车底就补哪个（按 id 认；已存在的一律不动，免得覆盖现场调过的参数）
    if (-not $target.consistTypes) { $target | Add-Member -NotePropertyName consistTypes -NotePropertyValue @() ; $changed = $true }
    foreach ($type in $source.consistTypes) {
      if (-not ($target.consistTypes | Where-Object { $_.id -eq $type.id })) {
        $target.consistTypes = @($target.consistTypes) + $type
        $changed = $true
      }
    }

    # 2) carTypeIds 按 key 合并（现场已有的键优先）
    if ($source.carTypeIds) {
      if (-not $target.carTypeIds) {
        $target | Add-Member -NotePropertyName carTypeIds -NotePropertyValue ([pscustomobject]@{})
        $changed = $true
      }
      foreach ($property in $source.carTypeIds.PSObject.Properties) {
        if (-not $target.carTypeIds.PSObject.Properties[$property.Name]) {
          $target.carTypeIds | Add-Member -NotePropertyName $property.Name -NotePropertyValue $property.Value
          $changed = $true
        }
      }
    }

    # 3) 兜底车底：只在原文件没写时补上（显式写过就尊重现场的）
    if (-not $target.defaultConsistTypeId -and $source.defaultConsistTypeId) {
      $target | Add-Member -NotePropertyName defaultConsistTypeId -NotePropertyValue $source.defaultConsistTypeId
      $changed = $true
    }

    if ($changed) {
      ($target | ConvertTo-Json -Depth 12) | Set-Content -NoNewline -Encoding utf8NoBOM $_.FullName
      $merged++
      Write-Output ('merged -> ' + $_.FullName)
    } else {
      Write-Output ('up to date -> ' + $_.FullName)
    }
  }
}
Write-Output ("done: 扫描 $scanned 份，新建 $installed，合并 $merged")
