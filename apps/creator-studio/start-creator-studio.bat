@echo off
setlocal
chcp 65001 >nul
title MTR Creator Studio (original MTR 4.0.5 author client)
set "STUDIO=%~dp0"
rem 工作区路径单一真源（本工程在 mmtr/apps/creator-studio 下）
call "%STUDIO%..\..\..\env\workspace.env.bat"
if errorlevel 1 exit /b 1

if "%1"=="--check" goto :check

echo ================================================================
echo    MTR Creator Studio  -  original MTR 4.0.5 author client
echo    Usage:
echo      1) enter a Singleplayer world in the game window
echo      2) open the http://localhost:PORT/creator/ URL shown in chat/log
echo      (close the mmtr client first to free port 8888)
echo ================================================================
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK21 not found: %JAVA_HOME%
  pause
  exit /b 1
)
if not exist "%STUDIO%gradlew.bat" (
  echo [ERROR] gradlew.bat not found in %STUDIO%
  pause
  exit /b 1
)
cd /d "%STUDIO%"
echo Starting Minecraft + MTR (first run may take a minute)...
call gradlew.bat runClient --console=plain
echo.
echo Game exited with code %errorlevel%
pause
exit /b %errorlevel%

:check
if not exist "%JAVA_HOME%\bin\java.exe" ( echo [ERROR] JDK21 missing & exit /b 1 )
"%JAVA_HOME%\bin\java.exe" -version
echo STUDIO=%STUDIO%
echo JAVA_HOME=%JAVA_HOME%
if exist "%STUDIO%run\logs" (echo run/logs present) else (echo no run dir yet)
exit /b 0
