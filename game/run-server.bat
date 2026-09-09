@echo off
rem MMTR dedicated fabric server launcher (keeps stdin open so gradle runServer does not exit)
cd /d "%~dp0"
rem 工作区路径单一真源
call "%~dp0..\..\env\workspace.env.bat"
set "JH=%JDK21%"
if exist "%JH%\bin\java.exe" ( set "JAVA_HOME=%JH%" ) else ( set "JAVA_HOME=C:\Program Files\Java\jdk-21" )
set "PATH=%JAVA_HOME%\bin;%PATH%"
powershell -NoProfile -Command "Start-Sleep -Seconds 86400" | gradlew.bat :fabric:runServer
