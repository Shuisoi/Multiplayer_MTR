# check-font-assets.ps1 — 守卫：`assets/*/font/*.json` 里引用的每个 TTF 都必须真的在位且是 TrueType。
#
# 为什么需要它（notes/222）：「字体全部变方框」的根因就是把 provider 的
#   "file": "mtr:font/din1451alt.ttf"     ← 我写的（错）
# 当成了包内路径。MC 的 TrueTypeGlyphProviderDefinition 会自己加 `font/` 前缀
# （类文件里有 "font/" 字面量），所以 JSON 里只能写 **文件名**：
#   "file": "mtr:din1451alt.ttf"          ← 正确（上游 mtr.json 就是这么写的）
# 结果：解析到 assets/mtr/font/font/…（不存在）⇒ provider 加载失败 ⇒ 那个字体没有字形 ⇒ 屏幕上全是方框。
# 这条守卫把「JSON 引用的文件是否在位」变成提交前就能查的事实，而不是进游戏后靠眼睛发现。
#
# Usage:
#   pwsh -File mmtr\scripts\check-font-assets.ps1                      # 查仓库资源
#   pwsh -File mmtr\scripts\check-font-assets.ps1 -AssetsRoot <dir>    # 查夹具目录（red-proof 用，别动真文件）
# Exit: 0 = 全部在位；1 = 有引用缺失或不是 TTF
param(
  [string]$AssetsRoot = ""
)
$ErrorActionPreference = 'Stop'
$mmtr = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrEmpty($AssetsRoot)) {
  $AssetsRoot = Join-Path $mmtr 'game\fabric\src\main\resources\assets'
}
$AssetsRoot = [System.IO.Path]::GetFullPath($AssetsRoot)
$roots = Get-ChildItem $AssetsRoot -Directory -ErrorAction SilentlyContinue |
  ForEach-Object { Join-Path $_.FullName 'font' } | Where-Object { Test-Path $_ }

$checked = 0
$problems = 0
foreach ($fontDir in $roots) {
  $namespace = Split-Path (Split-Path $fontDir -Parent) -Leaf
  Get-ChildItem $fontDir -Filter '*.json' | ForEach-Object {
    $jsonFile = $_
    $json = Get-Content -Raw $jsonFile.FullName | ConvertFrom-Json
    foreach ($provider in $json.providers) {
      if ($provider.type -ne 'ttf') { continue }
      $checked++
      $reference = [string]$provider.file
      # MC 的规则：<namespace>:<路径> → assets/<namespace>/font/<路径>
      if ($reference -notmatch '^([^:]+):(.+)$') {
        Write-Output ("BAD  " + $jsonFile.Name + " -> file 不是 <namespace>:<path> 形式：" + $reference)
        $problems++
        continue
      }
      $ns = $Matches[1]
      $relative = $Matches[2]
      $resolved = Join-Path (Join-Path (Join-Path $AssetsRoot $ns) 'font') $relative
      if (-not (Test-Path $resolved)) {
        Write-Output ("MISS " + $jsonFile.Name + " -> " + $reference + "  解析到 " + $resolved + "（不存在）")
        Write-Output ("     ★ 提示：MC 会自己加 font/ 前缀，所以 file 只写文件名（例：""" + $ns + ":" + (Split-Path $relative -Leaf) + """）")
        $problems++
        continue
      }
      # TrueType/OpenType 文件头：00010000 / 'true' / 'OTTO' / 'ttcf'
      $bytes = [System.IO.File]::ReadAllBytes($resolved)[0..3]
      $magic = ($bytes | ForEach-Object { $_.ToString('X2') }) -join ''
      if ($magic -notin @('00010000', '74727565', '4F54544F', '74746366')) {
        Write-Output ("BAD  " + $jsonFile.Name + " -> " + $reference + "  文件头不是字体：" + $magic)
        $problems++
        continue
      }
      Write-Output ("OK   " + $jsonFile.Name + " -> " + $reference + " (" + [math]::Round((Get-Item $resolved).Length / 1KB) + " KB)")
    }
  }
}

if ($problems -eq 0) {
  Write-Output ("check-font-assets: OK - " + $checked + " 个 ttf 引用全部在位")
  exit 0
} else {
  Write-Output ("check-font-assets: FAILED - " + $problems + " 处问题 / 共 " + $checked + " 个引用")
  exit 1
}
