param(
  [ValidateSet('start','stop','status','restart')] [string]$Action = 'status',
  [string]$RconPassword = 'mmtr-dev-rcon'
)
$ErrorActionPreference = 'Stop'
$root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$bat     = Join-Path $root 'run-server.bat'
$outLog  = Join-Path $root 'run-server-console.log'
$errLog  = Join-Path $root 'run-server-console.err.log'
$mcPort  = 25565
$rconPort= 25575

function Get-McPid {
  try { $l = Get-NetTCPConnection -State Listen -LocalPort $mcPort -ErrorAction Stop; return $l.OwningProcess } catch { return $null }
}

function Send-Rcon([string]$password,[string]$command) {
  $c = New-Object System.Net.Sockets.TcpClient
  $c.Connect('127.0.0.1', $rconPort)
  if (-not $c.Connected) { $c.Close(); throw 'rcon connect failed' }
  $s = $c.GetStream()
  $rd = New-Object System.IO.BinaryReader($s)
  $wr = New-Object System.IO.BinaryWriter($s)
  $script:rconId = 0
  function Write-Packet([int]$type,[string]$body) {
    $script:rconId++
    $id = $script:rconId
    $bodyBytes = [Text.Encoding]::ASCII.GetBytes($body)
    $len = 4 + 4 + $bodyBytes.Length + 2
    $wr.Write([int]$len); $wr.Write([int]$id); $wr.Write([int]$type)
    $wr.Write($bodyBytes); $wr.Write([byte]0); $wr.Write([byte]0); $wr.Flush()
  }
  function Read-Packet {
    $len = $rd.ReadInt32(); $buf = $rd.ReadBytes($len)
    $id = [BitConverter]::ToInt32($buf,0); $type = [BitConverter]::ToInt32($buf,4)
    $msg = [Text.Encoding]::ASCII.GetString($buf,8,$buf.Length-10).TrimEnd([char]0)
    return @{id=$id; type=$type; msg=$msg}
  }
  Write-Packet 3 $password
  $login = Read-Packet
  Write-Packet 2 $command
  $resp = Read-Packet
  $wr.Close(); $rd.Close(); $s.Close(); $c.Close()
  return @{loginId=$login.id; respId=$resp.id; msg=$resp.msg}
}

switch ($Action) {
  'start' {
    if (Get-McPid) { Write-Output "server already running (pid $(Get-McPid))"; exit 1 }
    $p = Start-Process -FilePath $bat -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru
    Write-Output "started launcher pid $($p.Id) -> polling port $mcPort (see $outLog)"
    for ($i=0; $i -lt 120; $i++) {
      Start-Sleep -Seconds 1
      $mcp = Get-McPid
      if ($mcp) { Write-Output "server up on $mcPort (mc pid $mcp, launcher $($p.Id))"; exit 0 }
    }
    Write-Output "server did not open $mcPort within 120s; see $outLog / $errLog"
    exit 1
  }
  'status' {
    $mcp = Get-McPid
    if ($mcp) { Write-Output "UP  mc-port=$mcPort pid=$mcp" } else { Write-Output "DOWN" }
    exit 0
  }
  'stop' {
    $mcp = Get-McPid
    if (-not $mcp) { Write-Output "server already stopped"; exit 0 }
    try {
      $r = Send-Rcon $RconPassword 'stop'
      Write-Output ("rcon stop sent (login " + $r.loginId + ", resp " + $r.respId + ")")
      for ($i=0; $i -lt 60; $i++) { Start-Sleep -Seconds 1; if (-not (Get-McPid)) { Write-Output 'server stopped gracefully'; exit 0 } }
      Write-Output 'graceful stop timed out; forcing kill'
      Stop-Process -Id $mcp -Force -ErrorAction SilentlyContinue
    } catch {
      Write-Output ("rcon stop failed (" + $_.Exception.Message + "); forcing kill of pid $mcp")
      Stop-Process -Id $mcp -Force -ErrorAction SilentlyContinue
    }
    exit 0
  }
  'restart' {
    & $PSCommandPath 'stop' $RconPassword
    Start-Sleep -Seconds 3
    & $PSCommandPath 'start' $RconPassword
  }
}