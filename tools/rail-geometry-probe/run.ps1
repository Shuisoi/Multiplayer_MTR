<#
.SYNOPSIS
  MTR 轨道几何自检：对发布的引擎 jar 跑真 RailMath，核对几何规则。

.DESCRIPTION
  .dsh/skills/mmtr-track-building 里的几何表（90 度转角半径 = min(along,lateral)、
  S 弯 R=(L^2+o^2)/(4o)、纯圆角弧长 = pi*R/2 等）全部由本探针实测得出。
  引擎对非法组合**不报错**（静默给「直线 + R=min」，或给零长轨），所以这些假设
  必须能反复验证 —— 本脚本就是那个验证入口。

  退出码：0 = 全部通过；1 = 有 MISMATCH。

.EXAMPLE
  pwsh -File mmtr\tools\rail-geometry-probe\run.ps1
#>

$ErrorActionPreference = 'Stop'

# 探针有中文输出；不设成 UTF-8 的话 PowerShell 会按 OEM 代码页解码 java 的 stdout，全是乱码。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$here = $PSScriptRoot
# 本文件在 <repo>\mmtr\tools\rail-geometry-probe\ ，引擎 jar 在 <repo>\mmtr\game\libs\
$engineDir = Split-Path -Parent (Split-Path -Parent $here)
$jar = Join-Path $engineDir 'game\libs\Transport-Simulation-Core-0.0.1.jar'

if (-not (Test-Path $jar)) {
	throw "找不到引擎 jar：$jar`n先构建引擎（mmtr\engine）或确认 libs 目录存在。"
}

$out = Join-Path $here 'out'
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "引擎 jar: $jar"
Write-Host "编译中（--release 21：PATH 上 javac 与 java 可能不同版本）..."

# --release 21 是必须的：本机 PATH 上 javac 25 / java 21，
# 不加会得到 UnsupportedClassVersionError (class file version 69.0 / up to 65.0)。
& javac --release 21 -encoding UTF-8 -cp $jar -d $out (Join-Path $here 'RailProbe.java')
if ($LASTEXITCODE -ne 0) {
	throw "javac 失败（退出码 $LASTEXITCODE）"
}

# 引擎 jar 是 shaded 的，只需它一个，不用另外挂 fastutil。
# -Dstdout.encoding：java 默认按 native.encoding 写 stdout，管道的另一端会得到乱码。
# 必须加引号：PowerShell 会把裸的 -Dfoo.bar=baz 拆坏（实测报 ClassNotFoundException: /bar=baz）。
& java "-Dstdout.encoding=UTF-8" "-Dfile.encoding=UTF-8" -cp "$jar;$out" RailProbe
exit $LASTEXITCODE
