# 只读分析：把"画到的车=?"与"非钢轨 draws"两条时间线并排输出。
param([string]$Log = "mmtr\game\fabric\run\logs\latest.log")

$ev = New-Object System.Collections.ArrayList
foreach ($l in (Select-String -Path $Log -Pattern '^\[\d\d:\d\d:\d\d\]')) {
    $s = $l.Line
    if ($s -notmatch '^\[(\d\d):(\d\d):(\d\d)\]') { continue }
    $t = "$($Matches[1]):$($Matches[2]):$($Matches[3])"
    if ($t -lt $script:From) { continue }
    if ($s -match 'optimizer draws/frame=\S+ .*?非钢轨 draws=([0-9.]+)\(') {
        [void]$ev.Add([PSCustomObject]@{ t=$t; kind='DRAW'; v=[double]$Matches[1]; note='' })
    } elseif ($s -match '车灯：画到的车=(\d+) .*?染色draw=(\d+)') {
        [void]$ev.Add([PSCustomObject]@{ t=$t; kind='CARS'; v=[double]$Matches[1]; note="染色draw=$($Matches[2])" })
    }
}
$ev | Sort-Object t | ForEach-Object { "{0}  {1,-5} {2,7}  {3}" -f $_.t, $_.kind, $_.v, $_.note }
