# StorageCast ADB deploy + log helper.
# Waits for an Android device (USB or wireless ADB), installs the freshly built
# debug APK, grants runtime permissions, launches the app, and streams the app's
# Cast/transcode logs to a capture file the agent can read.
#
# Usage:
#   pwsh -File scripts/deploy-and-log.ps1            # wait for device, deploy, tail logs
#   pwsh -File scripts/deploy-and-log.ps1 -NoWait    # deploy now (device already connected)
#
# Wireless ADB (Android 11+): on the phone enable Developer options ->
# Wireless debugging, then either:
#   adb pair <ip>:<pairPort> <code>   (one-time)
#   adb connect <ip>:<port>
# USB: enable USB debugging, plug in, accept the authorization prompt.

param(
    [switch]$NoWait
)

$ErrorActionPreference = "Stop"
$adb = "D:\AndroidVMs\SDK\platform-tools\adb.exe"
$pkg = "com.storagecast"
$activity = "$pkg/.ui.MainActivity"
$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\debug\app-debug.apk"
$logFile = Join-Path $PSScriptRoot "..\logcat_capture.txt"

if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path $apk)) { throw "APK not found at $apk - run: .\gradlew.bat assembleDebug" }

if (-not $NoWait) {
    Write-Host "Waiting for an Android device on ADB (connect USB debugging or wireless ADB)..."
    & $adb wait-for-device
}

Write-Host "Device:"
& $adb devices -l

Write-Host "Installing $apk ..."
& $adb install -r $apk

# Grant runtime permissions so the app can list local videos without manual taps.
foreach ($perm in @("android.permission.READ_MEDIA_VIDEO", "android.permission.POST_NOTIFICATIONS")) {
    try { & $adb shell pm grant $pkg $perm 2>$null } catch { }
}

Write-Host "Launching $activity ..."
& $adb shell am start -n $activity | Out-Null

# Fresh log buffer, then stream the app's own tags to the capture file.
& $adb logcat -c
Write-Host "Streaming logs to $logFile (Ctrl+C to stop). Now start the cast on the phone."
& $adb logcat -v time -s `
    VideoDetail MediaServer TranscodeStreamer MediaProber CastCompat `
    HlsTranscodeSession Fmp4Writer StorageCastApp AppLogger `
    | Tee-Object -FilePath $logFile
