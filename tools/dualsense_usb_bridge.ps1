<#
.SYNOPSIS
    Bridges a physically connected Sony PlayStation 5 DualSense / DualSense Edge controller
    on the Windows host PC to a running Android Emulator or connected device over TCP via adb reverse.

.DESCRIPTION
    - Enumerates USB HID devices for Sony DualSense (VID 0x054C, PID 0x0CE6) and DualSense Edge (PID 0x0DF2).
    - Reads 64-byte input reports (0x01) continuously via Win32 ReadFile and streams them to Android.
    - Receives 48-byte output reports (0x02) from Android and writes them directly to the physical controller
      via Win32 WriteFile (supporting Adaptive Triggers, RGB Lightbar, Player LEDs, Rumble).

.PARAMETER AdbPath
    Path to adb.exe. Defaults to $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe.

.PARAMETER DeviceSerial
    Target Android device/emulator serial. Defaults to emulator-5554.

.PARAMETER Port
    TCP port for the bridge connection. Defaults to 21337.

.PARAMETER TestOnly
    When specified, finds the controller, verifies hardware/firmware versions via Feature Report 0x20,
    writes a 48-byte test output report (Cyan lightbar, solid mic LED, Player 1 dot), and exits.
#>

[CmdletBinding()]
param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$DeviceSerial = "emulator-5554",
    [ValidateRange(1, 65535)]
    [int]$Port = 21337,
    [switch]$TestOnly
)

$ErrorActionPreference = "Stop"

if (-not ([System.Management.Automation.PSTypeName]'DualSenseBridge').Type) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using Microsoft.Win32.SafeHandles;

public class DualSenseBridge {
    [StructLayout(LayoutKind.Sequential)]
    public struct SP_DEVICE_INTERFACE_DATA {
        public int cbSize;
        public Guid interfaceClassGuid;
        public int flags;
        public IntPtr reserved;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    public struct SP_DEVICE_INTERFACE_DETAIL_DATA {
        public int cbSize;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 512)]
        public string devicePath;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct HIDD_ATTRIBUTES {
        public int Size;
        public ushort VendorID;
        public ushort ProductID;
        public ushort VersionNumber;
    }

    [DllImport("setupapi.dll", SetLastError = true)]
    public static extern IntPtr SetupDiGetClassDevs(ref Guid classGuid, IntPtr enumerator, IntPtr hwndParent, int flags);

    [DllImport("setupapi.dll", SetLastError = true)]
    public static extern bool SetupDiEnumDeviceInterfaces(IntPtr deviceInfoSet, IntPtr deviceInfoData, ref Guid interfaceClassGuid, int memberIndex, ref SP_DEVICE_INTERFACE_DATA deviceInterfaceData);

    [DllImport("setupapi.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern bool SetupDiGetDeviceInterfaceDetail(IntPtr deviceInfoSet, ref SP_DEVICE_INTERFACE_DATA deviceInterfaceData, ref SP_DEVICE_INTERFACE_DETAIL_DATA deviceInterfaceDetailData, int deviceInterfaceDetailDataSize, ref int requiredSize, IntPtr deviceInfoData);

    [DllImport("setupapi.dll", SetLastError = true)]
    public static extern bool SetupDiDestroyDeviceInfoList(IntPtr deviceInfoSet);

    [DllImport("hid.dll", SetLastError = true)]
    public static extern void HidD_GetHidGuid(out Guid hidGuid);

    [DllImport("hid.dll", SetLastError = true)]
    public static extern bool HidD_GetAttributes(SafeFileHandle HidDeviceObject, ref HIDD_ATTRIBUTES Attributes);

    [DllImport("hid.dll", SetLastError = true)]
    public static extern bool HidD_GetFeature(SafeFileHandle HidDeviceObject, byte[] ReportBuffer, int ReportBufferLength);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern SafeFileHandle CreateFile(
        string lpFileName,
        uint dwDesiredAccess,
        uint dwShareMode,
        IntPtr lpSecurityAttributes,
        uint dwCreationDisposition,
        uint dwFlagsAndAttributes,
        IntPtr hTemplateFile);

    public static SafeFileHandle CreateFile(
        string lpFileName,
        int dwDesiredAccess,
        int dwShareMode,
        IntPtr lpSecurityAttributes,
        int dwCreationDisposition,
        int dwFlagsAndAttributes,
        IntPtr hTemplateFile) {
        return CreateFile(lpFileName, (uint)dwDesiredAccess, (uint)dwShareMode, lpSecurityAttributes, (uint)dwCreationDisposition, (uint)dwFlagsAndAttributes, hTemplateFile);
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool ReadFile(SafeFileHandle hFile, byte[] lpBuffer, int nNumberOfBytesToRead, out int lpNumberOfBytesRead, IntPtr lpOverlapped);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool WriteFile(SafeFileHandle hFile, byte[] lpBuffer, int nNumberOfBytesToWrite, out int lpNumberOfBytesWritten, IntPtr lpOverlapped);

    public static string FindDualSensePath() {
        Guid hidGuid;
        HidD_GetHidGuid(out hidGuid);
        IntPtr hDevInfo = SetupDiGetClassDevs(ref hidGuid, IntPtr.Zero, IntPtr.Zero, 0x12);
        if (hDevInfo == (IntPtr)(-1)) return null;

        SP_DEVICE_INTERFACE_DATA ifData = new SP_DEVICE_INTERFACE_DATA();
        ifData.cbSize = Marshal.SizeOf(ifData);

        string bestMatch = null;

        for (int i = 0; SetupDiEnumDeviceInterfaces(hDevInfo, IntPtr.Zero, ref hidGuid, i, ref ifData); i++) {
            int reqSize = 0;
            SP_DEVICE_INTERFACE_DETAIL_DATA detail = new SP_DEVICE_INTERFACE_DETAIL_DATA();
            detail.cbSize = IntPtr.Size == 8 ? 8 : 5;
            if (SetupDiGetDeviceInterfaceDetail(hDevInfo, ref ifData, ref detail, 512, ref reqSize, IntPtr.Zero)) {
                string p = detail.devicePath ?? "";
                if (p.IndexOf("vid_054c&pid_0ce6", StringComparison.OrdinalIgnoreCase) >= 0 ||
                    p.IndexOf("vid_054c&pid_0df2", StringComparison.OrdinalIgnoreCase) >= 0) {
                    if (p.IndexOf("mi_03", StringComparison.OrdinalIgnoreCase) >= 0) {
                        SetupDiDestroyDeviceInfoList(hDevInfo);
                        return p;
                    }
                    if (bestMatch == null && p.IndexOf("&col", StringComparison.OrdinalIgnoreCase) < 0) {
                        bestMatch = p;
                    }
                }
            }
        }
        SetupDiDestroyDeviceInfoList(hDevInfo);
        return bestMatch;
    }

    public static System.Threading.Thread StartUpstreamBridge(SafeFileHandle handle, System.IO.Stream stream) {
        System.Threading.Thread t = new System.Threading.Thread(() => {
            byte[] buf = new byte[48];
            try {
                while (true) {
                    int offset = 0;
                    while (offset < 48) {
                        int n = stream.Read(buf, offset, 48 - offset);
                        if (n <= 0) return;
                        offset += n;
                    }
                    int written = 0;
                    WriteFile(handle, buf, 48, out written, IntPtr.Zero);
                }
            } catch {}
        });
        t.IsBackground = true;
        t.Start();
        return t;
    }
}
'@
}

# 1. Enumerate and open physical DualSense
$path = [DualSenseBridge]::FindDualSensePath()
if (-not $path) {
    Write-Error "No Sony DualSense (0x0CE6) or DualSense Edge (0x0DF2) USB HID device detected on host."
    exit 1
}
Write-Host "Found physical DualSense at: $path" -ForegroundColor Green

$handle = $null
$listener = $null
$reverseActive = $false

try {
    # Open handle with GENERIC_READ | GENERIC_WRITE (0xC0000000) and FILE_SHARE_READ | FILE_SHARE_WRITE (3)
    $handle = [DualSenseBridge]::CreateFile($path, 0xC0000000, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
    if ($handle.IsInvalid) {
        throw "Failed to open DualSense handle. Win32 Error: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }

    # 2. Query Feature Report 0x20 (Hardware & Firmware info)
    $f20 = New-Object byte[] 64
    $f20[0] = 0x20
    if ([DualSenseBridge]::HidD_GetFeature($handle, $f20, 64)) {
        $hw = [BitConverter]::ToUInt16($f20, 24)
        $fw = [BitConverter]::ToUInt16($f20, 44)
        Write-Host ("DualSense Verified: HW 0x{0:X4}, FW 0x{1:X4}" -f $hw, $fw) -ForegroundColor Cyan
    } else {
        Write-Warning "Could not read Feature 0x20 from device."
    }

    # 3. TestOnly Mode: send 48-byte test report and exit
    if ($TestOnly) {
        # Send test output report (Cyan lightbar, solid mic LED, Player 1 center dot)
        $testOut = New-Object byte[] 48
        $testOut[0] = 0x02   # Report ID
        $testOut[1] = 0x03   # ucEnableBits1: rumble emulation + classic rumble
        $testOut[2] = 0x15   # ucEnableBits2: mic mute LED + lightbar RGB + player LED
        $testOut[9] = 0x01   # ucMicLightMode: Solid On
        $testOut[44] = 0x04  # ucPadLights: Player 1 (center dot)
        $testOut[45] = 0x00  # Red
        $testOut[46] = 128   # Green
        $testOut[47] = 255   # Blue (Cyan-Blue)
        $written = 0
        [DualSenseBridge]::WriteFile($handle, $testOut, 48, [ref]$written, [IntPtr]::Zero) | Out-Null
        Write-Host "Test output report sent. Written: $written bytes." -ForegroundColor Green
        $handle.Close()
        exit 0
    }

    # 4. Resolve ADB path
    if (-not (Test-Path $AdbPath)) {
        $found = Get-Command "adb.exe" -ErrorAction SilentlyContinue
        if ($found) {
            $AdbPath = $found.Source
        } else {
            throw "adb.exe not found at '$AdbPath' and not available in PATH."
        }
    }

    # 5. Bridge Mode: configure adb reverse and start TCP listener
    Write-Host "Configuring adb reverse tcp:$Port tcp:$Port on $DeviceSerial..." -ForegroundColor Yellow
    & $AdbPath -s $DeviceSerial reverse tcp:$Port tcp:$Port
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to configure adb reverse on device '$DeviceSerial' (exit code $LASTEXITCODE). Verify the device is connected, authorized, and online."
    }
    $reverseActive = $true

    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
    $listener.Start()
    Write-Host "Host bridge listening on 127.0.0.1:$Port. Waiting for DroidDeck in emulator..." -ForegroundColor Green

    while ($true) {
        $client = $listener.AcceptTcpClient()
        $client.NoDelay = $true
        Write-Host "Emulator connected! Starting bidirectional bridge..." -ForegroundColor Green
        $stream = $client.GetStream()

        # Thread: Upstream (Emulator -> Physical Controller WriteFile 48B)
        $writerThread = [DualSenseBridge]::StartUpstreamBridge($handle, $stream)

        # Main loop: Downstream (Physical Controller ReadFile 64B -> TCP Send to Emulator)
        $inBuf = New-Object byte[] 64
        try {
            while ($client.Connected) {
                $readBytes = 0
                if ([DualSenseBridge]::ReadFile($handle, $inBuf, 64, [ref]$readBytes, [IntPtr]::Zero)) {
                    if ($readBytes -eq 64) {
                        $stream.Write($inBuf, 0, 64)
                    }
                } else {
                    break
                }
            }
        } catch {
            Write-Host "Client disconnected: $_" -ForegroundColor Yellow
        } finally {
            try { $stream.Close() } catch {}
            try { $client.Close() } catch {}
        }
    }
} finally {
    if ($listener) {
        Write-Host "Shutting down DualSense bridge..." -ForegroundColor Yellow
        try { $listener.Stop() } catch {}
    }
    if ($reverseActive) {
        try {
            & $AdbPath -s $DeviceSerial reverse --remove tcp:$Port 2>$null
        } catch {}
    }
    if ($handle -and -not $handle.IsInvalid -and -not $handle.IsClosed) {
        try { $handle.Close() } catch {}
    }
    if ($listener) {
        Write-Host "DualSense bridge stopped." -ForegroundColor Gray
    }
}
