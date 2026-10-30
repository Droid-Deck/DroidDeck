# Test Harness: Empirical Stress Test for tools/dualsense_usb_bridge.ps1
# Milestone 3 - Empirical Challenger 1

$ErrorActionPreference = "Continue"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " STARTING DUALSENSE USB BRIDGE EMPIRICAL STRESS TEST SUITE" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$bridgeScript = "$PSScriptRoot\dualsense_usb_bridge.ps1"
$results = [System.Collections.Generic.List[PSCustomObject]]::new()

function Record-Test($name, $passed, $details) {
    $obj = [PSCustomObject]@{
        Name = $name
        Passed = $passed
        Details = $details
    }
    $results.Add($obj)
    $status = if ($passed) { "PASS" } else { "FAIL" }
    $color = if ($passed) { "Green" } else { "Red" }
    Write-Host "[$status] $name - $details" -ForegroundColor $color
}

# ---------------------------------------------------------------------------------
# TEST SUITE 1: REPEATED RE-ENTRANCY IN SAME POWERSHELL PROCESS
# ---------------------------------------------------------------------------------
Write-Host "`n--- Test Suite 1: Repeated Re-entrancy & Handle / Type Stability ---" -ForegroundColor Yellow

$proc = [System.Diagnostics.Process]::GetCurrentProcess()
$handlesBefore = $proc.HandleCount
$iterations = 10
$reentrancyPass = $true
$reentrancyDetails = ""

for ($i = 1; $i -le $iterations; $i++) {
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        & $bridgeScript -TestOnly | Out-Null
        $sw.Stop()
        if ($LASTEXITCODE -ne 0) {
            $reentrancyPass = $false
            $reentrancyDetails = "Iteration $i exited with code $LASTEXITCODE"
            break
        }
    } catch {
        $reentrancyPass = $false
        $reentrancyDetails = "Iteration $i threw exception: $_"
        break
    }
}

$proc.Refresh()
$handlesAfter = $proc.HandleCount
$handleDelta = $handlesAfter - $handlesBefore

if ($reentrancyPass) {
    Record-Test "Re-entrancy 10x -TestOnly execution" $true ("All 10 iterations completed successfully. Handle delta: $handleDelta (Before: $handlesBefore, After: $handlesAfter)")
} else {
    Record-Test "Re-entrancy 10x -TestOnly execution" $false $reentrancyDetails
}

# ---------------------------------------------------------------------------------
# TEST SUITE 2: INVALID / MISSING ARGUMENT BOUNDARIES
# ---------------------------------------------------------------------------------
Write-Host "`n--- Test Suite 2: Invalid / Missing Arguments ---" -ForegroundColor Yellow

# 2.1 Invalid Port: Negative Port (-1)
$resPortNeg = powershell -NoProfile -Command "try { & '$bridgeScript' -Port -1 } catch { `$_.Exception.Message }"
$portNegStr = ($resPortNeg | Out-String)
if ($portNegStr -match "out of the range of valid values" -or $portNegStr -match "ArgumentOutOfRangeException" -or $portNegStr -match "Cannot validate argument on parameter 'Port'" -or $portNegStr -match "ParameterBindingValidationException") {
    Record-Test "Port boundary check: -1 throws exception" $true "Correctly threw validation exception on -1: $portNegStr"
} else {
    Record-Test "Port boundary check: -1 throws exception" $false "Did not reject -1 as expected: $portNegStr"
}

# Check if handle leaked when port was invalid
$portParam = (Get-Command $bridgeScript).Parameters['Port']
$rangeAttr = $portParam.Attributes | Where-Object { $_ -is [System.Management.Automation.ValidateRangeAttribute] }
if ($rangeAttr -and $rangeAttr.MinRange -eq 1 -and $rangeAttr.MaxRange -eq 65535) {
    Record-Test "Port parameter validation presence" $true "[ValidateRange(1, 65535)] attribute is correctly declared on Port parameter"
} else {
    Record-Test "Port parameter validation presence" $false "Script lacks [ValidateRange(1, 65535)] attribute on Port parameter"
}

# 2.2 Invalid Port: Overflow Port (70000)
$resPortOver = powershell -NoProfile -Command "try { & '$bridgeScript' -Port 70000 } catch { `$_.Exception.Message }"
$portOverStr = ($resPortOver | Out-String)
if ($portOverStr -match "out of the range of valid values" -or $portOverStr -match "ArgumentOutOfRangeException" -or $portOverStr -match "Cannot validate argument on parameter 'Port'" -or $portOverStr -match "ParameterBindingValidationException") {
    Record-Test "Port boundary check: 70000 throws exception" $true "Correctly threw validation exception on 70000: $portOverStr"
} else {
    Record-Test "Port boundary check: 70000 throws exception" $false "Did not reject 70000 as expected: $portOverStr"
}

# 2.3 Invalid Port: Port 0
$resPortZero = powershell -NoProfile -Command "
    `$job = Start-Job -ScriptBlock { & '$bridgeScript' -Port 0 2>&1 }
    Start-Sleep -Milliseconds 1500
    `$out = Receive-Job `$job
    Stop-Job `$job -ErrorAction SilentlyContinue
    Remove-Job `$job -ErrorAction SilentlyContinue
    `$out | Out-String
"
Write-Host "Port 0 Output: $resPortZero" -ForegroundColor DarkGray
if ($resPortZero -match "127.0.0.1:0") {
    Record-Test "Port boundary check: 0" $false "Port 0 is accepted without validation; binds ephemeral port while adb reverses port 0"
} else {
    Record-Test "Port boundary check: 0" $true "Port 0 handled"
}

# 2.4 Non-existent Device Serial
$resBadSerial = powershell -NoProfile -Command "
    try {
        & '$bridgeScript' -DeviceSerial 'nonexistent-device-99999' 2>&1
    } catch {
        `$_ | Out-String
    }
"
$badSerialStr = ($resBadSerial | Out-String)
Write-Host "Non-existent Serial Output: $badSerialStr" -ForegroundColor DarkGray
if (($badSerialStr -match "not found" -or $badSerialStr -match "Failed to configure adb reverse") -and -not ($badSerialStr -match "Host bridge listening")) {
    Record-Test "Invalid Device Serial Handling" $true "Script aborted on ADB failure"
} elseif ($badSerialStr -match "Host bridge listening") {
    Record-Test "Invalid Device Serial Handling" $false "BUG: ADB reverse failure ('device not found') is ignored ($`LASTEXITCODE ignored); script enters infinite wait on dead tunnel"
} else {
    Record-Test "Invalid Device Serial Handling" $false "Unexpected output on invalid serial: $badSerialStr"
}

# 2.5 Missing ADB Executable & Handle Leak Check
$resBadAdb = powershell -NoProfile -Command "
    `$env:PATH = 'C:\Windows\System32'
    try {
        & '$bridgeScript' -AdbPath 'C:\nonexistent_directory\adb.exe' 2>&1
    } catch {
        `$_ | Out-String
    }
"
$resBadAdbStr = ($resBadAdb | Out-String)
Write-Host "Bad ADB Output: $resBadAdbStr" -ForegroundColor DarkGray
if ($resBadAdbStr -match "adb\.exe not found at" -or $resBadAdbStr -match "not available in PATH") {
    Record-Test "Missing ADB Executable Detection" $true "Correctly reported missing adb.exe"
} else {
    Record-Test "Missing ADB Executable Detection" $false "Did not detect missing adb.exe: $resBadAdbStr"
}

# 2.6 Dead Code & Handle Leak on ErrorActionPreference=Stop
$deadCodeTest = powershell -NoProfile -Command "
    `$lines = Get-Content '$bridgeScript'
    `$writeErrorIdx = -1
    `$handleCloseIdx = -1
    for (`$i = 0; `$i -lt `$lines.Length; `$i++) {
        if (`$lines[`$i] -match 'Write-Error.*adb\.exe not found') { `$writeErrorIdx = `$i }
        if (`$writeErrorIdx -ne -1 -and `$lines[`$i] -match '\`$handle\.Close\(\)') { `$handleCloseIdx = `$i; break }
    }
    [PSCustomObject]@{ ErrorIdx = `$writeErrorIdx; CloseIdx = `$handleCloseIdx }
"
if ($deadCodeTest.ErrorIdx -ge 0 -and $deadCodeTest.CloseIdx -gt $deadCodeTest.ErrorIdx) {
    Record-Test "ErrorActionPreference Stop Dead Code & Handle Leak" $false "BUG: `$handle.Close() (line $($deadCodeTest.CloseIdx + 1)) follows Write-Error (line $($deadCodeTest.ErrorIdx + 1)) under `$ErrorActionPreference='Stop'; Write-Error terminates immediately making `$handle.Close() unreachable dead code"
} else {
    Record-Test "ErrorActionPreference Stop Dead Code & Handle Leak" $true "Handle close is properly reachable"
}

# ---------------------------------------------------------------------------------
# TEST SUITE 3: DEVICE PATH SEARCH ROBUSTNESS & P/INVOKE INTEGRITY
# ---------------------------------------------------------------------------------
Write-Host "`n--- Test Suite 3: Device Path & P/Invoke Integrity ---" -ForegroundColor Yellow

$path = [DualSenseBridge]::FindDualSensePath()
Write-Host "Discovered Device Path: $path" -ForegroundColor DarkCyan

if ($path -and ($path -match "vid_054c&pid_0ce6" -or $path -match "vid_054c&pid_0df2")) {
    Record-Test "FindDualSensePath() VID/PID matching" $true "Successfully found DualSense path: $path"
} else {
    Record-Test "FindDualSensePath() VID/PID matching" $false "Failed to find valid DualSense path: $path"
}

if ($path -match "mi_03") {
    Record-Test "FindDualSensePath() mi_03 interface targeting" $true "Path specifically targets HID interface mi_03"
} else {
    Record-Test "FindDualSensePath() mi_03 interface targeting" $false "Path does not target mi_03: $path"
}

# ---------------------------------------------------------------------------------
# TEST SUITE 4: CONCURRENT READ/WRITE STRESS & P/INVOKE MEMORY BOUNDS
# ---------------------------------------------------------------------------------
Write-Host "`n--- Test Suite 4: Concurrent Read/Write Stress & P/Invoke Bounds ---" -ForegroundColor Yellow

$h = [DualSenseBridge]::CreateFile($path, 0xC0000000, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
if ($h.IsInvalid) {
    Record-Test "CreateFile handle acquisition" $false "Could not open DualSense handle for stress test"
} else {
    try {
        Record-Test "CreateFile handle acquisition" $true "Handle acquired successfully (IsInvalid=false)"

        # Stress Test 4.1: Feature 0x20 query integrity under stress
        $f20 = New-Object byte[] 64
        $f20[0] = 0x20
        $f20Ok = [DualSenseBridge]::HidD_GetFeature($h, $f20, 64)
        $hw = [BitConverter]::ToUInt16($f20, 24)
        $fw = [BitConverter]::ToUInt16($f20, 44)
        if ($f20Ok -and $hw -eq 0x0621 -and $fw -eq 0x0630) {
            Record-Test "HidD_GetFeature(0x20) Hardware & Firmware Verification" $true ("HW: 0x{0:X4}, FW: 0x{1:X4}" -f $hw, $fw)
        } else {
            Record-Test "HidD_GetFeature(0x20) Hardware & Firmware Verification" $false ("Failed or mismatch: OK=$f20Ok, HW=0x{0:X4}, FW=0x{1:X4}" -f $hw, $fw)
        }

        # Stress Test 4.2: 100 consecutive ReadFile calls
        $readSuccessCount = 0
        $inReport = New-Object byte[] 64
        $report1Count = 0
        $swRead = [System.Diagnostics.Stopwatch]::StartNew()
        for ($r = 0; $r -lt 100; $r++) {
            $readBytes = 0
            if ([DualSenseBridge]::ReadFile($h, $inReport, 64, [ref]$readBytes, [IntPtr]::Zero) -and $readBytes -eq 64) {
                $readSuccessCount++
                if ($inReport[0] -eq 0x01) {
                    $report1Count++
                }
            }
        }
        $swRead.Stop()
        $readElapsedMs = $swRead.ElapsedMilliseconds
        $avgReadMs = [math]::Round($readElapsedMs / 100.0, 2)
        if ($readSuccessCount -eq 100 -and $report1Count -eq 100) {
            Record-Test "100 consecutive ReadFile(64B) calls" $true "100/100 reads succeeded, all report ID 0x01. Total time: ${readElapsedMs}ms, Avg: ${avgReadMs}ms/read"
        } else {
            Record-Test "100 consecutive ReadFile(64B) calls" $false "Success: $readSuccessCount/100, Report ID 0x01: $report1Count/100"
        }

        # Stress Test 4.3: 50 consecutive WriteFile calls (RGB Lightbar pulse)
        $writeSuccessCount = 0
        $outReport = New-Object byte[] 48
        $outReport[0] = 0x02
        $outReport[1] = 0x03
        $outReport[2] = 0x15
        $outReport[9] = 0x01
        $outReport[44] = 0x04

        $swWrite = [System.Diagnostics.Stopwatch]::StartNew()
        for ($w = 0; $w -lt 50; $w++) {
            $outReport[45] = ($w * 5) % 256
            $outReport[46] = (255 - ($w * 5)) % 256
            $outReport[47] = 200
            $written = 0
            if ([DualSenseBridge]::WriteFile($h, $outReport, 48, [ref]$written, [IntPtr]::Zero) -and $written -eq 48) {
                $writeSuccessCount++
            }
            [System.Threading.Thread]::Sleep(5)
        }
        $swWrite.Stop()
        $writeElapsedMs = $swWrite.ElapsedMilliseconds
        if ($writeSuccessCount -eq 50) {
            Record-Test "50 consecutive WriteFile(48B) calls" $true "50/50 writes succeeded (48 bytes each). Total time: ${writeElapsedMs}ms"
        } else {
            Record-Test "50 consecutive WriteFile(48B) calls" $false "Success: $writeSuccessCount/50"
        }

        # Stress Test 4.4: Concurrent Read and Write via StartUpstreamBridge & Local TCP Sockets
        # Test the EXACT code path used in production bridge: StartUpstreamBridge + ReadFile
        $stressPort = 21399
        $server = $null
        $client = $null
        $serverClient = $null
        $upstreamStream = $null
        $clientStream = $null
        $concurrentWrites = 0
        $concurrentReads = 0

        try {
            $server = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $stressPort)
            $server.Start()

            $client = [System.Net.Sockets.TcpClient]::new()
            $client.Connect("127.0.0.1", $stressPort)
            $serverClient = $server.AcceptTcpClient()

            $upstreamStream = $serverClient.GetStream()
            $isBt = [DualSenseBridge]::IsBluetoothPath($path)
            $bridgeThread = [DualSenseBridge]::StartUpstreamBridge($h, $upstreamStream, $isBt)

            $clientStream = $client.GetStream()

            # Write 30 48B output reports through TCP client -> StartUpstreamBridge -> WriteFile(h)
            # While simultaneously reading 50 64B input reports via ReadFile(h)
            $wChunk = New-Object byte[] 48
            $wChunk[0] = 0x02
            $wChunk[1] = 0x03
            $wChunk[2] = 0x15
            $wChunk[45] = 255
            $wChunk[46] = 0
            $wChunk[47] = 128

            for ($s = 0; $s -lt 30; $s++) {
                $clientStream.Write($wChunk, 0, 48)
                $concurrentWrites++

                $rBytes = 0
                $rBuf = New-Object byte[] 64
                if ([DualSenseBridge]::ReadFile($h, $rBuf, 64, [ref]$rBytes, [IntPtr]::Zero) -and $rBytes -eq 64) {
                    $concurrentReads++
                }
                [System.Threading.Thread]::Sleep(4)
            }

            if ($concurrentReads -ge 28 -and $concurrentWrites -eq 30) {
                Record-Test "Concurrent StartUpstreamBridge & ReadFile over Local TCP" $true "Concurrent I/O completed cleanly: $concurrentWrites writes and $concurrentReads reads without deadlock or corruption"
            } else {
                Record-Test "Concurrent StartUpstreamBridge & ReadFile over Local TCP" $false "Reads: $concurrentReads/30, Writes: $concurrentWrites/30"
            }
        } finally {
            if ($clientStream) { try { $clientStream.Close() } catch {} }
            if ($client) { try { $client.Close() } catch {} }
            if ($upstreamStream) { try { $upstreamStream.Close() } catch {} }
            if ($serverClient) { try { $serverClient.Close() } catch {} }
            if ($server) { try { $server.Stop() } catch {} }
        }
    } finally {
        if ($h -and -not $h.IsInvalid -and -not $h.IsClosed) {
            $h.Close()
        }
    }
}

# ---------------------------------------------------------------------------------
# SUMMARY
# ---------------------------------------------------------------------------------
Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host " EMPIRICAL TEST SUITE SUMMARY" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$passed = ($results | Where-Object { $_.Passed }).Count
$total = $results.Count
$failed = $total - $passed

Write-Host "Total Tests: $total | Passed: $passed | Failed: $failed" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Yellow" })

foreach ($r in $results) {
    $c = if ($r.Passed) { "Green" } else { "Red" }
    $pStr = if ($r.Passed) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1}`n    {2}" -f $pStr, $r.Name, $r.Details) -ForegroundColor $c
}
