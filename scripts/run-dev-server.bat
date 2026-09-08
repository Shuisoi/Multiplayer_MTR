@echo off
cd /d "C:\Users\30354\Desktop\Shuisoi DEV\MC\mmtr\game"
set "JAVA_HOME=C:\Users\30354\Desktop\Shuisoi DEV\MC\tools\jdk-21.0.12.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo starting runServer %date% %time% >> "%TEMP%\mmtr-runserver.log"
call gradlew.bat :fabric:runServer --configure-on-demand --console=plain >> "%TEMP%\mmtr-runserver.log" 2>&1
echo runServer exited code %errorlevel% %date% %time% >> "%TEMP%\mmtr-runserver.log"
