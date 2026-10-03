# 检查每一盏灯："区间走行方向"与"灯的朝向"是否同向（点积 > 0）。
#
# 为什么需要这条检查：朝向的符号一旦反了 180°，颜色会**成对**互换（对着库内的绿、对着库外的红），
# 看上去像"某一盏灯配错了"，而不像"全世界的朝向反了"。只有把"朝向 · 区间走向"整体算一遍，
# 才能一眼看出是少数灯配错、还是全体反了。
#
# 铁律：司机只有正对灯面时才看得见灯，所以迎着灯开来的列车与灯朝同一个方向 ——
# 区间的走行方向必须与灯的朝向同向。区间走向从 spans[0].points 的前两个采样点量出（不依赖弧的排序）。
#
# 用法：pwsh -File tools\check-lamp-direction.ps1 [-OnlyBound]
param(
    [switch]$OnlyBound,
    [string]$Api = "http://127.0.0.1:8888/mtr/api/map"
)

$ErrorActionPreference = "Stop"

$signals = (Invoke-RestMethod "$Api/mmtr-signals" -TimeoutSec 60).data.signals
$sections = (Invoke-RestMethod "$Api/mmtr-sections" -TimeoutSec 60).data.sections

function SectionHeading($section) {
    if (-not $section.spans -or $section.spans.Count -eq 0) { return $null }
    $pts = $section.spans[0].points
    if (-not $pts -or $pts.Count -lt 4) { return $null }
    $dx = [double]$pts[2] - [double]$pts[0]
    $dz = [double]$pts[3] - [double]$pts[1]
    $n = [Math]::Sqrt($dx * $dx + $dz * $dz)
    if ($n -lt 1e-9) { return $null }
    return @{ x = $dx / $n; z = $dz / $n }
}

function Facing([double]$angleDeg) {
    # 灯**面朝**哪一边，用世界向量表示。
    #
    # 这里必须与引擎的 headingOf 差 180°，理由是实测校准出来的，不是推导：
    # 引擎的 headingOf 给的是**列车走行方向**那一套（角 0 → (0,-1)），而本脚本要的是
    # "灯面朝哪边"，两者正好相反。实测依据：世界里的 (-65,-60,-139) 角=180、区间朝南(+z) 走，
    # 按下面的式子 Facing(180)=(0,+1) 与走向点积 = +1 —— 正是"灯与它守护的方向同向"；
    # 若改用 headingOf 那套（(0,-1)），同一盏灯就会算出 -1，把 71 盏里 30 盏healthy的灯
    # 报成"反向"（第一版就是这么错的，那种假警报比没有检查更坏）。
    $rad = $angleDeg * [Math]::PI / 180.0
    return @{ x = [Math]::Sin($rad); z = [Math]::Cos($rad) }
}

$checked = 0
$bad = @()
$noSection = 0
$noGeometry = 0
foreach ($sig in $signals) {
    if ($OnlyBound -and -not $sig.boundExplicit) { continue }
    $sec = $sections | Where-Object { ([string]$_.id) -eq ([string]$sig.key) -or ([string]$_.id) -like ([string]$sig.key + "#*") } | Select-Object -First 1
    if (-not $sec) { $noSection++; continue }
    $dir = SectionHeading $sec
    if ($null -eq $dir) { $noGeometry++; continue }
    $f = Facing ([double]$sig.angle)
    $dot = $f.x * $dir.x + $f.z * $dir.z
    $checked++
    if ($dot -le 0) {
        $bad += ("灯 {0} 角={1} 走向=({2:N2},{3:N2}) 朝向=({4:N2},{5:N2}) 点积={6:N2} 人工={7} 区间长={8:N0}m" -f `
            $sig.key, $sig.angle, $dir.x, $dir.z, $f.x, $f.z, $dot, $sig.boundExplicit, $sec.length)
    }
}

Write-Output ("检查了 {0} 盏有区间的灯{1}；无区间 {2} 盏；区间无采样点 {3} 盏" -f `
    $checked, $(if ($OnlyBound) { "（只含人工绑定）" } else { "" }), $noSection, $noGeometry)
if ($bad.Count -eq 0) {
    Write-Output "全部同向：区间走行方向与灯的朝向一致（点积 > 0）"
    exit 0
}
Write-Output ("反向的灯 {0} 盏（这些灯的颜色会与它们面对的方向相反）：" -f $bad.Count)
$bad | ForEach-Object { "  " + $_ }
exit 1
