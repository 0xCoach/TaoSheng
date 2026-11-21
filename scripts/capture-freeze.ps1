# 黑屏冻结现场采集（R4.4）：在冻结发生时立即运行，不要先强杀 App。
# 用法：powershell -File scripts/capture-freeze.ps1 [-Serial <adb序列号>] [-OutDir <输出目录>]
# 采集：logcat / window / SurfaceFlinger / gfxinfo 帧统计 / activity / meminfo / dbinfo /
#       uiautomator 视图树 / screencap / 进程线程状态。全部文本，含时间戳，不含录音正文。
param(
    [string]$Serial = "adb-6fb6e596-WaFDJN (3)._adb-tls-connect._tcp",
    [string]$OutDir = "$env:TEMP\freeze-evidence"
)
$Adb = "D:\Program Files\Oppo Connect\daemon\bin\adb.exe"
$Pkg = "app.resound"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dir = Join-Path $OutDir $stamp
New-Item -ItemType Directory -Force $dir | Out-Null
Write-Host "采集到 $dir"

function AdbShell($cmd, $out) {
    & $Adb -s $Serial shell $cmd > (Join-Path $dir $out) 2>&1
}

AdbShell "logcat -d -v threadtime" "logcat.txt"
AdbShell "dumpsys window" "window.txt"
AdbShell "dumpsys SurfaceFlinger" "surfaceflinger.txt"
AdbShell "dumpsys gfxinfo $Pkg framestats" "gfxinfo.txt"
AdbShell "dumpsys activity top" "activity-top.txt"
AdbShell "dumpsys activity oom" "activity-oom.txt"
AdbShell "dumpsys meminfo $Pkg" "meminfo.txt"
AdbShell "dumpsys dbinfo $Pkg" "dbinfo.txt"
AdbShell "dumpsys jobscheduler" "jobs.txt"
AdbShell "ps -A -o PID,TID,STAT,CMD | grep -E 'resound|render|hwui'" "threads.txt"
AdbShell "dumpsys power | head -40" "power.txt"

& $Adb -s $Serial shell "uiautomator dump /sdcard/freeze-ui.xml" > $null 2>&1
& $Adb -s $Serial pull /sdcard/freeze-ui.xml (Join-Path $dir "ui-tree.xml") > $null 2>&1
& $Adb -s $Serial shell "screencap -p /sdcard/freeze.png" > $null 2>&1
& $Adb -s $Serial pull /sdcard/freeze.png (Join-Path $dir "screen.png") > $null 2>&1

# 服务/录音状态
& $Adb -s $Serial shell "dumpsys activity services $Pkg" > (Join-Path $dir "services.txt") 2>&1
& $Adb -s $Serial shell "dumpsys media.audio_flinger | head -60" > (Join-Path $dir "audio.txt") 2>&1

Write-Host "完成。重点看：gfxinfo 帧计数是否停止增长；window 中 resound 窗口状态；
ui-tree 是否有内容（有树+黑屏=渲染/合成器问题；无树+黑屏=进程侧问题）。"
