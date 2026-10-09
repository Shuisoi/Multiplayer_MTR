# 只读分析：从客户端日志里抽取 [MMTR-LIGHT] 的 draws 时间线。
# 用法：pwsh -File mmtr\sandbox\drawcount\analyze.ps1 [日志路径]
param([string]$Log = "mmtr\game\fabric\run\logs\latest.log")

$rows = New-Object System.Collections.ArrayList
foreach ($l in (Select-String -Path $Log -Pattern 'optimizer draws/frame=')) {
    $s = $l.Line
    if ($s -notmatch '^\[(\d\d):(\d\d):(\d\d)\]') { continue }
    $hh = $Matches[1]; $mm = $Matches[2]
    if ($s -notmatch '非钢轨 draws=([0-9.]+)\(') { continue }
    $non = [double]$Matches[1]
    if ($s -notmatch '钢轨 draws=([0-9.]+)\(') { continue }
    $rail = [double]$Matches[1]
    [void]$rows.Add([PSCustomObject]@{
        t     = "$hh`:$mm"
        non   = $non
        rail  = $rail
        total = $non + $rail
    })
}

Write-Host ("窗口数 = {0}" -f $rows.Count)
Write-Host ""
Write-Host "时间桶(5分钟)   窗口  非钢轨峰值  非钢轨中位  总draws峰值"
$rows | Group-Object -Property t | Sort-Object Name | ForEach-Object {
    $g = $_.Group
    $mx = ($g | Measure-Object non -Maximum).Maximum
    $md = ($g | Sort-Object non)[[int]($g.Count / 2)].non
    $tm = ($g | Measure-Object total -Maximum).Maximum
    "{0,-14} {1,5} {2,11} {3,11} {4,11}" -f $_.Name, $_.Count, $mx, $md, $tm
}
