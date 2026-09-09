@echo off
rem MMTR engine shadowJar -> game/libs sync (batch version; PS wrapper trips on gradle stderr under $ErrorActionPreference Stop)
rem 工作区路径单一真源
call "%~dp0..\..\env\workspace.env.bat"
if errorlevel 1 exit /b 1
cd /d "%~dp0..\engine"
call gradlew.bat shadowJar --console=plain --no-daemon
if errorlevel 1 exit /b 1
for /f "delims=" %%j in ('dir /b /o-d build\libs\Transport-Simulation-Core-*.jar ^| findstr /v /i "sources javadoc"') do (
  copy /y "build\libs\%%j" "..\game\libs\Transport-Simulation-Core-0.0.1.jar" >nul
  echo SYNCED %%j
)
