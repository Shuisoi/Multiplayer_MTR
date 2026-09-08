@echo off
rem MMTR engine shadowJar -> game/libs sync (batch version; PS wrapper trips on gradle stderr under $ErrorActionPreference Stop)
cd /d "C:\Users\30354\Desktop\Shuisoi DEV\MC\mmtr\engine"
set "JAVA_HOME=C:\Users\30354\Desktop\Shuisoi DEV\MC\tools\jdk-21.0.12.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call gradlew.bat shadowJar --console=plain --no-daemon
if errorlevel 1 exit /b 1
for /f "delims=" %%j in ('dir /b /o-d build\libs\Transport-Simulation-Core-*.jar ^| findstr /v /i "sources javadoc"') do (
  copy /y "build\libs\%%j" "..\game\libs\Transport-Simulation-Core-0.0.1.jar" >nul
  echo SYNCED %%j
)
