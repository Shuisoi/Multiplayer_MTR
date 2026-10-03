@echo off
chcp 65001 >nul
rem ===========================================================================
rem  MTR vehicle geometry verify + preview render  -- one-click entry
rem  No hard-coded absolute paths: read vars from env\workspace.env.bat (repo rule)
rem ===========================================================================
setlocal
call "%~dp0..\..\..\env\workspace.env.bat" 2>nul

if "%BLENDER_EXE%"=="" (
  if exist "D:\SteamLibrary\steamapps\common\Blender\blender.exe" set "BLENDER_EXE=D:\SteamLibrary\steamapps\common\Blender\blender.exe"
)
if "%BLENDER_EXE%"=="" (
  for %%V in (5.2 5.1 5.0 4.5) do (
    if exist "C:\Program Files\Blender Foundation\Blender %%V\blender.exe" set "BLENDER_EXE=C:\Program Files\Blender Foundation\Blender %%V\blender.exe"
  )
)
if "%BLENDER_EXE%"=="" (
  echo [ERROR] blender.exe not found. Set BLENDER_EXE, or add it to env\workspace.env.bat
  exit /b 2
)

set "PROBE=%~dp0"
if "%~1"=="" (
  echo Usage:
  echo   verify.bat ^<blend^> [object]           4-layer geometry verify
  echo   verify.bat ^<blend^> [object] --render  verify + render preview
  echo.
  echo Example:
  echo   verify.bat "%%MC_ROOT%%\assets\models\blender\br101\br101.blend" body
  exit /b 1
)

set "BLEND=%~1"
set "OBJ=%~2"
if "%OBJ%"=="" set "OBJ=body"

echo [1/2] VERIFY: %BLEND%  object=%OBJ%
"%BLENDER_EXE%" --background "%BLEND%" --python "%PROBE%verify_geometry.py" -- --object "%OBJ%" --expect-json "%PROBE%br101_body_design.json"
set "RC=%ERRORLEVEL%"

if /i "%~3"=="--render" (
  echo.
  echo [2/2] RENDER
  "%BLENDER_EXE%" --background "%BLEND%" --python "%PROBE%render_preview.py" -- "%~dp1preview"
)

echo.
if "%RC%"=="0" (echo RESULT: PASS) else (echo RESULT: FAIL ^(exit %RC%^))
exit /b %RC%