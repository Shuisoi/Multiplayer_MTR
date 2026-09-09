@echo off
call "%~dp0..\..\env\workspace.env.bat"
if errorlevel 1 exit /b 1
cd /d "%~dp0..\game"
echo starting runServer %date% %time% >> "%TEMP%\mmtr-runserver.log"
call gradlew.bat :fabric:runServer --configure-on-demand --console=plain >> "%TEMP%\mmtr-runserver.log" 2>&1
echo runServer exited code %errorlevel% %date% %time% >> "%TEMP%\mmtr-runserver.log"
