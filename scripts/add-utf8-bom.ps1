# add-utf8-bom.ps1 - make workspace PowerShell scripts readable by Windows PowerShell 5.1.
#
# WHY: Windows PowerShell 5.1 decodes a file with no BOM using the ANSI code page (GBK on this
# machine). The workspace scripts are UTF-8 and full of Chinese comments, so 5.1 mis-decodes the
# bytes and the pairing shifts. Two different symptoms come out of that, and the second is why this
# script does NOT try to be clever about which files need fixing:
#
#   1. the file fails to PARSE ("Unexpected token '}'") - 38 files did, including
#      env\workspace.env.ps1, the single source of truth for JAVA_HOME;
#   2. the file parses with ZERO errors but a mangled comment has EATEN the next line - which is
#      exactly what had happened to mmtr\scripts\dev-client.ps1: its dot-source of
#      env\workspace.env.ps1 was gone from the AST, so the launcher ran gradle with the machine's
#      JRE 8 JAVA_HOME. "Does it parse?" cannot see this; the statement just is not there.
#
# So the rule is uniform: every .ps1 in the workspace gets a UTF-8 BOM, whether or not it currently
# misbehaves. A UTF-8 BOM is the only in-file way to state the encoding, and PowerShell 7 reads it
# fine. ASCII-only scripts are unaffected in behaviour and only gain the BOM bytes.
#
# This only PREPENDS the three BOM bytes; file content is never re-encoded or rewritten.
#
#   powershell -File mmtr\scripts\add-utf8-bom.ps1 -WhatIfOnly
#   powershell -File mmtr\scripts\add-utf8-bom.ps1
param(
	[string]$Root = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
	[switch]$WhatIfOnly
)

$ErrorActionPreference = 'Stop'
$exclude = '\\(build|node_modules|\.gradle|\.angular|dist|out|classes|\.git)\\'
$bom = [byte[]](0xEF, 0xBB, 0xBF)

$candidates = Get-ChildItem -Path $Root -Recurse -File -Force -Filter *.ps1 -ErrorAction SilentlyContinue |
	Where-Object { $_.FullName -notmatch $exclude }

$added = 0
$skipped = 0
foreach ($file in $candidates) {
	$bytes = [System.IO.File]::ReadAllBytes($file.FullName)
	$hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
	$rel = $file.FullName.Replace($Root + '\', '')

	if ($hasBom) {
		$skipped++
		continue
	}

	# Report which of the two symptoms 5.1 shows for this file, so the log states the damage.
	$errs = $null
	[void][System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$null, [ref]$errs)
	$state = if ($errs -and $errs.Count -gt 0) { 'PARSE FAIL' } else { 'parses (lines may be swallowed)' }

	if ($WhatIfOnly) {
		Write-Host ("  would add BOM [{0}]: {1}" -f $state, $rel)
		$added++
		continue
	}

	$out = New-Object byte[] ($bytes.Length + 3)
	[Array]::Copy($bom, 0, $out, 0, 3)
	[Array]::Copy($bytes, 0, $out, 3, $bytes.Length)
	[System.IO.File]::WriteAllBytes($file.FullName, $out)
	Write-Host ("  +BOM [{0}] {1}" -f $state, $rel)
	$added++
}

Write-Host ''
if ($WhatIfOnly) {
	Write-Host ("would add a UTF-8 BOM to {0} file(s); {1} already have one" -f $added, $skipped)
} else {
	Write-Host ("added a UTF-8 BOM to {0} file(s); {1} already had one" -f $added, $skipped)
}

