@echo off
rem MMTR dedicated fabric server launcher (keeps stdin open so gradle runServer does not exit)
cd /d "%~dp0"
set "JH=%~dp0..\..\tools\jdk-21.0.12.1+1"
if exist "%JH%\bin\java.exe" ( set "JAVA_HOME=%JH%" ) else ( set "JAVA_HOME=C:\Program Files\Java\jdk-21" )
set "PATH=%JAVA_HOME%\bin;%PATH%"
powershell -NoProfile -Command "Start-Sleep -Seconds 86400" | gradlew.bat :fabric:runServer
