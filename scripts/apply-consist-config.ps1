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

  <#
  **先补"整份都没装过"的世界**（notes/338 §6.6，2026-09-27）。

  以前的写法只有下面那个 `Get-ChildItem -Recurse -Filter 'mmtr-consist-types.json'` 循环 ——
  它**只能找到已经存在的文件**，里面那句 `if (-not (Test-Path ...)) { Copy-Item }` 是**死代码**
  （能被 Filter 找到就说明文件存在）。于是"这个世界从来没装过这份配置"这一种永远补不上：
  现场就是 `run\world` —— loom runServer 真正在用的世界 —— 一直没有这份文件，服务端一直按
  "没有车底配置"的缺省物理跑（整队车都是通用车 60 t / 100 kN / 600 kW / 40 km/h，
  SAF420 那 6M4T 的口径根本没生效），而脚本每次都报"扫描 N 份、新建 0"。
  后果最重的一条：**多节编组借牵引的那一节被判给了头车（无动力的控制车）⇒ 整列零牵引**
  （notes/338 §6.2，修在引擎里），这一层补的是配置本身。

  只装**缺**的：文件已存在的世界一步不动（下面那个循环按"合并"处理）。
  #>
  foreach ($mtrDir in Get-ChildItem $rootPath -Recurse -Directory -Filter 'mtr' -ErrorAction SilentlyContinue) {
    if ($mtrDir.FullName -match '\.bak') { continue }
    foreach ($namespaceDir in Get-ChildItem $mtrDir.FullName -Directory -ErrorAction SilentlyContinue) {
      foreach ($dimensionDir in Get-ChildItem $namespaceDir.FullName -Directory -ErrorAction SilentlyContinue) {
        $missing = Join-Path $dimensionDir.FullName 'mmtr-consist-types.json'
        if (-not (Test-Path $missing)) {
          Copy-Item -Force $configFile $missing
          $installed++
          Write-Output ('installed -> ' + $missing)
        }
      }
    }
  }

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
