@echo off
rem MC 工作区环境变量单一真源（batch 版）
rem 用法（从仓库脚本里）：call "%~dp0..\..\env\workspace.env.bat"
rem 约定：工作区内任何脚本都不得再写机器相关的绝对路径（用户目录字面路径一律走本文件的变量）。
for %%I in ("%~dp0..") do set "MC_ROOT=%%~fI"
set "JDK21=%MC_ROOT%\env\jdk-21"
set "JDK17=%MC_ROOT%\env\jdk-17"
set "IDEA=%MC_ROOT%\env\idea"
set "MODS=%MC_ROOT%\vendor\mods"
set "MODELS=%MC_ROOT%\assets\models"
set "PACKAGER=%MC_ROOT%\mmtr\tools\obj-mtr-packager"
if exist "%JDK21%\bin\java.exe" (
  set "JAVA_HOME=%JDK21%"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)
