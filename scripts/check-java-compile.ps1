# check-java-compile.ps1 — typecheck the Fabric sources WITHOUT disturbing a running dev session.
#
# Why this exists (notes/195): under loom, `runClient` / `runServer` put `build/classes/java/main` on the
# classpath of a LIVE process, and the JVM loads classes lazily. So `gradlew :fabric:compileJava` while the
# user is in game can hand a running session a new class whose collaborators do not exist yet
# (`NoSuchMethodError` in the middle of play). Gradle also contends on the project lock that runServer's
# wrapper holds.
#
# This script compiles the same sources with plain javac, against the classpath the dev launch itself uses
# (loom writes it to build/loom-cache/argFiles/runClient), and writes the classes to a scratch directory.
# Nothing in build/ is touched, so it is safe to run at any time.
#
# Usage:
#   pwsh -File mmtr\scripts\check-java-compile.ps1                 # whole src/main/java
#   pwsh -File mmtr\scripts\check-java-compile.ps1 -Files a.java,b.java
#   pwsh -File mmtr\scripts\check-java-compile.ps1 -Quiet          # only the exit code + totals
#
# Exit code: 0 = compiled, 1 = compile errors, 2 = could not set up (no argfile / no javac).
param(
  [string[]]$Files = @(),
  [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$mcRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fabric = Join-Path $mcRoot 'mmtr\game\fabric'
$sourceRoot = Join-Path $fabric 'src\main\java'
$argFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
$outDir = Join-Path $mcRoot 'sandbox\javac-out'

if (-not (Test-Path $argFile)) {
  Write-Output "[check-java-compile] no $argFile - run the client once (gradlew :fabric:runClient) or use gradlew :fabric:compileJava while nothing is running"
  exit 2
}

# javac: the workspace JDK first (the same one the game runs on), then whatever Gradle downloaded.
$javac = Join-Path $mcRoot 'env\jdk-21\bin\javac.exe'
if (-not (Test-Path $javac)) {
  $javac = (Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}
if (-not $javac) { Write-Output '[check-java-compile] javac not found'; exit 2 }

# The argfile holds the classpath on its second line, with the spaces in the workspace path quoted as `" "`.
$classpath = ((Get-Content $argFile)[1] -replace '" "', ' ').Trim('"')
# javax.annotation is a COMPILE-only dependency, so it is not on the runtime classpath.
$jsr305 = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.findbugs" -Recurse -Filter 'jsr305-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jsr305) { $classpath = "$classpath;$($jsr305.FullName)" }

if ($Files.Count -eq 0) {
  # A handful of integration files import mod APIs that are compileOnly (not on the runtime classpath that
  # this check borrows), so plain javac cannot see them. They are listed rather than silently skipped: if
  # one of them starts failing for a REAL reason, the Gradle build is still the gate that catches it.
  $compileOnlyIntegrations = @('JadeConfig.java', 'WthitConfig.java')
  $Files = Get-ChildItem $sourceRoot -Recurse -Filter '*.java' |
    Where-Object { $compileOnlyIntegrations -notcontains $_.Name } |
    Select-Object -ExpandProperty FullName
}

Remove-Item $outDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $outDir | Out-Null

# -proc:none: the jar set declares a log4j annotation processor that plain javac cannot resolve, and this
# check is about the CODE compiling, not about running processors.
# Two traps in an @argfile, both silent-ish: it is split on whitespace (so the workspace path, which
# contains a space, must be quoted) and BACKSLASH IS AN ESCAPE CHARACTER inside it (so quoted Windows
# paths must use forward slashes or javac eats the separators).
$responseFile = Join-Path $mcRoot 'sandbox\javac-sources.txt'
$Files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content $responseFile -Encoding UTF8
& $javac -proc:none -nowarn -encoding UTF-8 -d $outDir -cp $classpath "@$responseFile" 2>&1 | ForEach-Object { $line = $_; if (-not $Quiet) { Write-Output $line } }
$exit = $LASTEXITCODE

$classes = (Get-ChildItem $outDir -Recurse -Filter '*.class' -ErrorAction SilentlyContinue).Count
if ($exit -eq 0) {
  Write-Output "[check-java-compile] OK - $($Files.Count) source file(s), $classes class file(s) -> $outDir"
} else {
  Write-Output "[check-java-compile] FAILED (javac exit $exit)"
}
exit $exit
