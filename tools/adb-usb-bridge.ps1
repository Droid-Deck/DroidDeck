# Makes the Android device attached to Windows reachable from WSL.
#
# WSL2 has no USB passthrough, so a device plugged into the machine is visible
# only to Windows adb. By default that server listens on 127.0.0.1 alone, which
# the WSL client cannot reach, and `adb devices` in WSL therefore comes back empty
# even though the device is connected and authorized. Restarting the server with
# -a makes it listen on every interface; the WSL shell profile then points at it
# with ADB_SERVER_SOCKET.
#
# Run this after a reboot, or whenever `adb devices` in WSL is empty while the
# device is plugged in. Safe to run repeatedly.

$ErrorActionPreference = 'Continue'

$adb = $null
foreach ($candidate in @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "C:\Users\minec\Downloads\PC drivers and programs\drivers\platform-tools\platform-tools\adb.exe"
)) {
    if (Test-Path $candidate) { $adb = $candidate; break }
}
if (-not $adb) {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { $adb = $found.Source }
}
if (-not $adb) {
    Write-Error "adb.exe not found. Install platform-tools or set the path above."
    exit 1
}

Write-Output "adb: $adb"

# Listen on all interfaces so the WSL client can reach this server.
& $adb kill-server 2>&1 | Out-Null
Start-Sleep -Seconds 2
Start-Process -FilePath $adb -ArgumentList '-a', '-P', '5037', 'nodaemon', 'server' -WindowStyle Hidden
Start-Sleep -Seconds 6

$devices = & $adb devices -l 2>&1
Write-Output $devices

if ($devices -match 'device product:') {
    $route = (wsl.exe -d Ubuntu -e bash -lc 'ip route show default' 2>&1) -join ' '
    if ($route -match 'via\s+([0-9.]+)') {
        Write-Output ""
        Write-Output "WSL reaches this server at $($Matches[1]):5037"
        Write-Output "The WSL shell profile sets ADB_SERVER_SOCKET=tcp:$($Matches[1]):5037"
    }
} else {
    Write-Warning "No device listed. Check the cable, and authorize the debugging prompt on the device."
    exit 1
}
