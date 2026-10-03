@echo off
rem MMTR dedicated fabric server launcher (keeps stdin open so gradle runServer does not exit)
rem
rem NOTES/349 - JAVA_HOME must point OUTSIDE the workspace:
rem   DSH's sandbox runs any executable whose image is INSIDE the workspace with a LOW integrity
rem   token, and integrity is INHERITED. This file used to set JAVA_HOME=%JDK21% (env\jdk-21,
rem   a workspace image => LOW), so the single-use gradle daemon was LOW as well and loom's
rem   zipfs writes (Minecraft jar merge / yarn mappings) died with
rem   "Could not merge JARs! ... java.nio.file.ReadOnlyFileSystemException".
rem   Use an out-of-workspace JDK for BOTH the launcher and the daemon.
rem   Keep this file ASCII-ONLY: non-ASCII bytes break cmd.exe parsing (notes/196).
cd /d "%~dp0"
rem single source of truth for workspace paths (also provides GRADLE_USER_HOME / TEMP)
call "%~dp0..\..\env\workspace.env.bat"

rem pick an out-of-workspace JDK 21 (gradle-provisioned; the ".2" suffix can change => wildcard)
set "OUTSIDE_JDK="
for /d %%D in ("%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21*") do set "OUTSIDE_JDK=%%~fD"
if not defined OUTSIDE_JDK (
	echo [run-server] no out-of-workspace JDK 21 under "%USERPROFILE%\.gradle\jdks" - see notes/349
	exit /b 2
)
set "JAVA_HOME=%OUTSIDE_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem guard: a workspace JAVA_HOME is a LOW image => loom will fail. Refuse early instead of
rem letting the user guess why zipfs blew up. NOTE: no parentheses in either message - an
rem unescaped "(" or ")" inside a parenthesised block breaks the batch parser.
echo "%JAVA_HOME%" | findstr /I /C:"%MC_ROOT%" >nul && (
	echo [run-server] JAVA_HOME is inside the workspace: %JAVA_HOME% - LOW image, see notes/349
	exit /b 3
)

echo [run-server] JAVA_HOME=%JAVA_HOME%
echo [run-server] GRADLE_USER_HOME=%GRADLE_USER_HOME%
rem A running server keeps the loom cache open (mappings.jar holds a handle) => stop the server
rem before building/starting the client, otherwise the client build fails while setting up yarn.
powershell -NoProfile -Command "Start-Sleep -Seconds 86400" | gradlew.bat :fabric:runServer -Dorg.gradle.java.home="%JAVA_HOME%" --console=plain
