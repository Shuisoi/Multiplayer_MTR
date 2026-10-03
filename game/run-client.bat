@echo off
rem MMTR dev client launcher (Fabric) - double-click it, or run it from a normal terminal.
rem
rem NOTES/349 - why NOT from IDEA, and why not env\jdk-21:
rem   DSH's sandbox runs any executable whose image is INSIDE the workspace with a LOW integrity
rem   token, and integrity is INHERITED:
rem     * IDEA lives inside the workspace (env\idea) => idea64 is LOW => the single-use gradle
rem       daemon it forks is LOW too => loom's zipfs writes fail:
rem       "Could not merge JARs! ... java.nio.file.ReadOnlyFileSystemException";
rem     * that LOW daemon also CANNOT SEE High processes (OpenProcess is denied), so it treats a
rem       live neighbour's loom cache lock as "process does not exist", steals it, rebuilds the
rem       cache - and hits the zipfs wall again. Failure is self-amplifying.
rem   So: 1) do not run gradle from IDEA; 2) JAVA_HOME must be an OUT-OF-WORKSPACE JDK.
rem
rem   Second measured limit: while the dev SERVER is running it keeps the loom cache open
rem   (mappings.jar), so a client build fails in "Failed to setup mappings". Stop the server first.
rem   Keep this file ASCII-ONLY: non-ASCII bytes break cmd.exe parsing (notes/196).
cd /d "%~dp0"
rem single source of truth for workspace paths (also provides GRADLE_USER_HOME / TEMP)
call "%~dp0..\..\env\workspace.env.bat"

rem pick an out-of-workspace JDK 21 (gradle-provisioned; the ".2" suffix can change => wildcard)
set "OUTSIDE_JDK="
for /d %%D in ("%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21*") do set "OUTSIDE_JDK=%%~fD"
if not defined OUTSIDE_JDK (
	echo [run-client] no out-of-workspace JDK 21 under "%USERPROFILE%\.gradle\jdks" - see notes/349
	exit /b 2
)
set "JAVA_HOME=%OUTSIDE_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem guard: a workspace JAVA_HOME is a LOW image => loom will fail. Refuse early. NOTE: no
rem parentheses in the message - an unescaped "(" inside a parenthesised block breaks the parser.
echo "%JAVA_HOME%" | findstr /I /C:"%MC_ROOT%" >nul && (
	echo [run-client] JAVA_HOME is inside the workspace: %JAVA_HOME% - LOW image, see notes/349
	exit /b 3
)

echo [run-client] JAVA_HOME=%JAVA_HOME%
echo [run-client] GRADLE_USER_HOME=%GRADLE_USER_HOME%
echo [run-client] starting the client (first run rebuilds the loom cache, then it is incremental)

rem keep gradle's stdin open (a closed stdin makes the runClient build finish early - same trick
rem as run-server.bat)
powershell -NoProfile -Command "Start-Sleep -Seconds 86400" | gradlew.bat :fabric:runClient -Dorg.gradle.java.home="%JAVA_HOME%" --console=plain
