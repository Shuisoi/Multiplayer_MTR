# MC 工作区环境变量单一真源（PowerShell 版）
#
# 用法（从仓库脚本里）：
#     . (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'env\workspace.env.ps1')
#
# 约定：工作区内任何脚本都不得再写机器相关的绝对路径（用户目录字面路径一律走本文件的变量）；
#       需要定位工具/第三方/资产时一律用本文件导出的变量。
#       守卫脚本：mmtr\scripts\check-paths.ps1

$MC_ROOT = Split-Path -Parent $PSScriptRoot          # MC\env -> MC
$env:MC_ROOT = $MC_ROOT

# 可重装工具链
$JDK21 = Join-Path $MC_ROOT 'env\jdk-21'
$JDK17 = Join-Path $MC_ROOT 'env\jdk-17'
$IDEA  = Join-Path $MC_ROOT 'env\idea'

# 第三方只读输入
$MODS     = Join-Path $MC_ROOT 'vendor\mods'
$UPSTREAM = Join-Path $MC_ROOT 'vendor\upstream\MTR-4.0.5'

# 自有资产与产物
$MODELS    = Join-Path $MC_ROOT 'assets\models'
$ARTIFACTS = Join-Path $MC_ROOT 'artifacts'
$LOGS      = Join-Path $MC_ROOT 'logs'

# 第一方代码
$PACKAGER  = Join-Path $MC_ROOT 'mmtr\tools\obj-mtr-packager'
$SCRIPTS   = Join-Path $MC_ROOT 'mmtr\scripts'

# 默认把 JDK 21 设为当前 JAVA_HOME（engine 与 game 都要求 21）
if (Test-Path (Join-Path $JDK21 'bin\java.exe')) {
	$env:JAVA_HOME = $JDK21
	$env:Path = (Join-Path $JDK21 'bin') + ';' + $env:Path
}
