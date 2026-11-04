<#
  通过 adb 实时查看手机上"谁在发声"，并累计各应用的播放时长。
  用到的是 shell 权限才能看的 dumpsys audio / dumpsys media_session，
  信息比普通 App 能拿到的多（底层播放器的 uid、started/paused 状态）。

  用法（PowerShell 7）：
    .\tools\audio_monitor.ps1
    .\tools\audio_monitor.ps1 -Interval 1 -Adb D:\sdk\platform-tools\adb.exe
  Ctrl+C 退出，退出时打印累计时长。
#>
param(
    [string]$Adb = 'E:\Android\sdk2\platform-tools\adb.exe',
    [double]$Interval = 2,
    [switch]$Once
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# uid -> 包名（多用户/分身的 uid 取 % 100000 后再查）
$uidMap = @{}
foreach ($line in & $Adb shell pm list packages -U) {
    if ($line -match '^package:(\S+) uid:([\d,]+)') {
        foreach ($u in $Matches[2].Split(',')) { $uidMap[[int]$u % 100000] = $Matches[1] }
    }
}
function Get-Pkg([int]$uid) {
    $app = $uid % 100000
    if ($uidMap.ContainsKey($app)) { return $uidMap[$app] }
    if ($app -eq 1000) { return 'android(system)' }
    return "uid:$uid"
}

function Parse-Players([string[]]$lines) {
    foreach ($l in $lines) {
        if ($l -match 'AudioPlaybackConfiguration piid:(\d+) deviceIds:\[([^\]]*)\] type:android\.media\.(\w+) u/pid:(\d+)/(\d+) state:(\w+) attr:AudioAttributes: usage=(\w+)') {
            # SoundPool 是按键音/提示音，忽略
            if ($Matches[3] -eq 'SoundPool') { continue }
            [pscustomobject]@{
                Pkg   = Get-Pkg ([int]$Matches[4])
                Type  = $Matches[3]
                State = $Matches[6]
                Usage = $Matches[7] -replace '^USAGE_', ''
                Piid  = $Matches[1]
            }
        }
    }
}

function Parse-Sessions([string[]]$lines) {
    $inUser0 = $false
    $cur = $null
    foreach ($l in $lines) {
        if ($l -match '^Record for full_user=(\d+)') { $inUser0 = ($Matches[1] -eq '0'); continue }
        if ($l -match '^Audio playback') { break }
        if (-not $inUser0) { continue }
        if ($l -match '^\s+package=(\S+)') {
            if ($cur) { $cur }
            $cur = [ordered]@{ Pkg = $Matches[1]; Active = ''; State = ''; Meta = '' }
        } elseif ($cur) {
            if ($l -match '^\s+active=(\w+)') { $cur.Active = $Matches[1] }
            elseif ($l -match '^\s+state=PlaybackState \{state=(\w+)\(') { $cur.State = $Matches[1] }
            elseif ($l -match '^\s+state=null') { $cur.State = 'null' }
            elseif ($l -match '^\s+metadata: size=\d+, description=(.*)$') { $cur.Meta = $Matches[1] }
        }
    }
    if ($cur) { $cur }
}

$totals = @{}
$last = Get-Date
$start = $last

try {
    while ($true) {
        $raw = & $Adb shell 'dumpsys audio; echo @@SPLIT@@; dumpsys media_session'
        $split = [Array]::IndexOf($raw, '@@SPLIT@@')
        $players = @(Parse-Players $raw[0..($split - 1)])
        $sessions = @(Parse-Sessions $raw[($split + 1)..($raw.Length - 1)] | ForEach-Object { [pscustomobject]$_ })

        $now = Get-Date
        $dt = ($now - $last).TotalSeconds
        $last = $now
        $sounding = $players | Where-Object State -eq 'started' | Select-Object -ExpandProperty Pkg -Unique
        foreach ($p in $sounding) { $totals[$p] = ($totals[$p] ?? 0) + $dt }

        try { Clear-Host } catch { }
        Write-Host ("音频监控  {0:HH:mm:ss}  已运行 {1:hh\:mm\:ss}  （Ctrl+C 退出）" -f $now, ($now - $start)) -ForegroundColor Cyan

        Write-Host "`n▶ 底层播放器（dumpsys audio，SoundPool 已忽略）" -ForegroundColor Yellow
        if ($players) {
            $players | Sort-Object { $_.State -ne 'started' }, Pkg |
                Format-Table @{n='状态';e={ if ($_.State -eq 'started') { '● 发声中' } else { $_.State } }}, Pkg, Type, Usage -AutoSize | Out-Host
        } else { Write-Host '  （无）' }

        Write-Host "▶ 媒体会话（dumpsys media_session）" -ForegroundColor Yellow
        if ($sessions) {
            $sessions | Format-Table Pkg, Active, State, @{n='Metadata';e={$_.Meta}} -AutoSize -Wrap | Out-Host
        } else { Write-Host '  （无）' }

        Write-Host "▶ 本次累计发声时长" -ForegroundColor Yellow
        if ($totals.Count) {
            $totals.GetEnumerator() | Sort-Object Value -Descending |
                Format-Table @{n='应用';e={$_.Key}}, @{n='时长';e={ [TimeSpan]::FromSeconds([math]::Round($_.Value)).ToString() }} -AutoSize | Out-Host
        } else { Write-Host '  （暂无）' }

        if ($Once) { break }
        Start-Sleep -Seconds $Interval
    }
} finally {
    Write-Host "`n累计发声时长：" -ForegroundColor Cyan
    $totals.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object {
        Write-Host ("  {0,-40} {1}" -f $_.Key, [TimeSpan]::FromSeconds([math]::Round($_.Value)))
    }
}
