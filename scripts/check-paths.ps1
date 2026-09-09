<#
.SYNOPSIS
  MC 工作区路径守卫：禁止第一方脚本/配置里出现机器相关的绝对路径。

.DESCRIPTION
  整理工作区的目标之一是「搬目录不会断链」，前提是不再有任何脚本硬编码 C:\Users\...。
  本脚本扫描第一方目录（mmtr/ 与 bin/，排除构建产物），把命中分成两类：

    FAIL - 代码/配置（.ps1 .bat .cmd .js .cjs .mjs .json .gradle .kts .properties .yml）
           出现字面绝对路径。必须改成 env/workspace.env.* 导出的变量，
           或打包器配置里的 ${MC_ROOT} 占位符。
    WARN - 文档（.md）里出现绝对路径。文档不影响运行，但会让后来者照抄错路径。

  退出码：0 = 无 FAIL；1 = 有 FAIL。适合放进提交前钩子。

.EXAMPLE
  pwsh -File mmtr\scripts\check-paths.ps1
  pwsh -File mmtr\scripts\check-paths.ps1 -Fix        # 只打印建议，不自动改
#>
param(
	[switch]$Fix
)

$ErrorActionPreference = 'Stop'
$mcRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$scanRoots = @('mmtr', 'bin') | ForEach-Object { Join-Path $mcRoot $_ }
$codeExt = @('.ps1', '.bat', '.cmd', '.js', '.cjs', '.mjs', '.json', '.gradle', '.kts', '.properties', '.yml', '.yaml', '.toml')
$docExt = @('.md')
$exclude = '\\(build|\.gradle|node_modules|\.angular|dist|run|\.git|out|classes)\\'
$selfExclude = @('mmtr\scripts\check-paths.ps1')   # 守卫自身要写模式串
$patterns = @('C:\Users\', 'C:/Users/')

$fails = New-Object System.Collections.Generic.List[object]
$warns = New-Object System.Collections.Generic.List[object]

foreach ($root in $scanRoots) {
	if (-not (Test-Path $root)) { continue }
	Get-ChildItem $root -Recurse -File -Force -ErrorAction SilentlyContinue |
		Where-Object { $_.FullName -notmatch $exclude } |
		ForEach-Object {
			$ext = $_.Extension.ToLower()
			if ($codeExt -notcontains $ext -and $docExt -notcontains $ext) { return }
			$rel = $_.FullName.Replace($mcRoot + '\', '')
			if ($selfExclude -contains $rel) { return }
			Select-String -LiteralPath $_.FullName -Pattern $patterns -SimpleMatch -ErrorAction SilentlyContinue | ForEach-Object {
				$hit = [pscustomobject]@{ File = $rel; Line = $_.LineNumber; Text = $_.Line.Trim() }
				if ($docExt -contains $ext) { $warns.Add($hit) } else { $fails.Add($hit) }
			}
		}
}

if ($fails.Count -eq 0 -and $warns.Count -eq 0) {
	Write-Host 'check-paths: OK - 没有发现机器相关绝对路径' -ForegroundColor Green
	exit 0
}

if ($warns.Count -gt 0) {
	Write-Host ''
	Write-Host "WARN - 文档里的绝对路径（$($warns.Count) 处）" -ForegroundColor Yellow
	$warns | ForEach-Object { Write-Host ("  {0}:{1}  {2}" -f $_.File, $_.Line, $_.Text) }
}

if ($fails.Count -gt 0) {
	Write-Host ''
	Write-Host "FAIL - 代码/配置里的绝对路径（$($fails.Count) 处）" -ForegroundColor Red
	$fails | ForEach-Object { Write-Host ("  {0}:{1}  {2}" -f $_.File, $_.Line, $_.Text) }
	Write-Host ''
	Write-Host '改法：PowerShell 脚本 dot-source env\workspace.env.ps1；batch 脚本 call env\workspace.env.bat；'
	Write-Host '      打包器配置用 ${MC_ROOT} 占位符（mmtr\tools\obj-mtr-packager\paths.js 解析）。'
	exit 1
}

exit 0
