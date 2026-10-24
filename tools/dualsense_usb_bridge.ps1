<#
.SYNOPSIS
    Bridges a physically connected Sony PlayStation 5 DualSense / DualSense Edge controller
    (via USB or Bluetooth) on the Windows host PC to a running Android Emulator or device over TCP via adb reverse.

.DESCRIPTION
    - Enumerates USB and Bluetooth HID devices for Sony DualSense (VID 0x054C, PID 0x0CE6) and DualSense Edge (PID 0x0DF2).
    - Automatically detects USB (64B Input 0x01 / 48B Output 0x02) vs Bluetooth (78B Input 0x31 / 78B Output 0x31 with 0xA2 CRC-32).
    - Queries Feature Report 0x05 (41B IMU calibration) on startup, which also switches Bluetooth DualSense from 10B 0x01 mode to 78B 0x31 mode.
    - Normalizes 78B Bluetooth 0x31 input reports to canonical 64B 0x01 reports for the Android TCP stream.
    - Wraps 48B 0x02 output reports from Android into 78B 0x31 Bluetooth output reports (with sequence counter, 3.0s Lightbar guard, and 0xA2 CRC-32) when connected over Bluetooth.

.PARAMETER AdbPath
    Path to adb.exe. Defaults to $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe.

.PARAMETER DeviceSerial
    Target Android device/emulator serial. Defaults to emulator-5554.

.PARAMETER Port
    TCP port for the bridge connection. Defaults to 21337.

.PARAMETER TestOnly
    When specified, finds the controller, verifies hardware/firmware/calibration via Feature Reports 0x05 & 0x20,
    writes a test output report (Cyan lightbar, solid mic LED, Player 1 dot), and exits.
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
using System.Diagnostics;
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

    [StructLayout(LayoutKind.Sequential)]
    public struct HIDP_CAPS {
        public ushort Usage;
        public ushort UsagePage;
        public ushort InputReportByteLength;
        public ushort OutputReportByteLength;
        public ushort FeatureReportByteLength;
        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 17)]
        public ushort[] Reserved;
        public ushort NumberLinkCollectionNodes;
        public ushort NumberInputButtonCaps;
        public ushort NumberInputValueCaps;
        public ushort NumberInputDataIndices;
        public ushort NumberOutputButtonCaps;
        public ushort NumberOutputValueCaps;
        public ushort NumberOutputDataIndices;
        public ushort NumberFeatureButtonCaps;
        public ushort NumberFeatureValueCaps;
        public ushort NumberFeatureDataIndices;
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

    [DllImport("hid.dll", SetLastError = true)]
    public static extern bool HidD_SetOutputReport(SafeFileHandle HidDeviceObject, byte[] ReportBuffer, int ReportBufferLength);

    [DllImport("hid.dll", SetLastError = true)]
    public static extern bool HidD_GetPreparsedData(SafeFileHandle HidDeviceObject, out IntPtr PreparsedData);

    [DllImport("hid.dll", SetLastError = true)]
    public static extern bool HidD_FreePreparsedData(IntPtr PreparsedData);

    [DllImport("hid.dll", SetLastError = true)]
    public static extern int HidP_GetCaps(IntPtr PreparsedData, out HIDP_CAPS Capabilities);

    public static int GetOutputReportByteLength(SafeFileHandle handle, int fallbackLength) {
        if (handle == null || handle.IsInvalid || handle.IsClosed) return fallbackLength;
        IntPtr preparsed = IntPtr.Zero;
        if (!HidD_GetPreparsedData(handle, out preparsed) || preparsed == IntPtr.Zero) {
            return fallbackLength;
        }
        try {
            HIDP_CAPS caps;
            if (HidP_GetCaps(preparsed, out caps) == 0x00110000 && caps.OutputReportByteLength > 0) {
                return (int)caps.OutputReportByteLength;
            }
        } finally {
            HidD_FreePreparsedData(preparsed);
        }
        return fallbackLength;
    }

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

    private static readonly uint[] Crc32Table = BuildCrc32Table();

    private static uint[] BuildCrc32Table() {
        uint[] table = new uint[256];
        for (uint i = 0; i < 256; i++) {
            uint c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) != 0 ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            }
            table[i] = c;
        }
        return table;
    }

    public static uint ComputeBtCrc32(byte seed, byte[] data, int offset, int length) {
        uint crc = 0xFFFFFFFFu;
        crc = (crc >> 8) ^ Crc32Table[(crc ^ seed) & 0xFF];
        for (int i = 0; i < length; i++) {
            crc = (crc >> 8) ^ Crc32Table[(crc ^ data[offset + i]) & 0xFF];
        }
        return ~crc;
    }

    public static byte[] WrapUsbOutputToBt78(byte[] usb48, int seq, bool btInitializing, int outputReportLength = 78) {
        int bufLen = Math.Max(outputReportLength, 78);
        byte[] bt = new byte[bufLen];
        bt[0] = 0x31;
        bt[1] = (byte)(((seq & 0x0F) << 4) | 0x02);
        Array.Copy(usb48, 1, bt, 2, 47);
        if (btInitializing) {
            bt[3] = (byte)(bt[3] & ~0x04);
            bt[43] = 0x01;
            bt[46] = 0;
            bt[47] = 0;
            bt[48] = 0;
        }
        uint crc = ComputeBtCrc32(0xA2, bt, 0, 74);
        bt[74] = (byte)(crc & 0xFF);
        bt[75] = (byte)((crc >> 8) & 0xFF);
        bt[76] = (byte)((crc >> 16) & 0xFF);
        bt[77] = (byte)((crc >> 24) & 0xFF);
        return bt;
    }

    public static bool NormalizeInputToUsb64(byte[] inBuf, int readBytes, byte[] outUsb64, bool isBluetooth = false) {
        if (!isBluetooth && readBytes >= 64 && inBuf[0] == 0x01) {
            Array.Copy(inBuf, 0, outUsb64, 0, 64);
            return true;
        }
        if (readBytes >= 78 && inBuf[0] == 0x31) {
            outUsb64[0] = 0x01;
            Array.Copy(inBuf, 2, outUsb64, 1, 63);
            return true;
        }
        if (readBytes >= 10 && inBuf[0] == 0x01) {
            Array.Clear(outUsb64, 0, 64);
            outUsb64[0] = 0x01;
            outUsb64[1] = inBuf[1];
            outUsb64[2] = inBuf[2];
            outUsb64[3] = inBuf[3];
            outUsb64[4] = inBuf[4];
            outUsb64[5] = inBuf[8];
            outUsb64[6] = inBuf[9];
            outUsb64[8] = inBuf[5];
            outUsb64[9] = inBuf[6];
            outUsb64[10] = (byte)(inBuf[7] & 0x03);
            outUsb64[33] = 0x80;
            outUsb64[37] = 0x80;
            outUsb64[53] = 0x0A;
            return true;
        }
        return false;
    }

    public static bool IsBluetoothPath(string path) {
        if (string.IsNullOrEmpty(path)) return false;
        return path.IndexOf("{00001124-0000-1000-8000-00805f9b34fb}", StringComparison.OrdinalIgnoreCase) >= 0 ||
               path.IndexOf("_vid&0002054c", StringComparison.OrdinalIgnoreCase) >= 0 ||
               path.IndexOf("bth", StringComparison.OrdinalIgnoreCase) >= 0;
    }

    public static bool IsPathLive(string path) {
        if (string.IsNullOrEmpty(path)) return false;
        try {
            using (SafeFileHandle h = CreateFile(path, 0xC0000000u, 3u, IntPtr.Zero, 3u, 0u, IntPtr.Zero)) {
                if (h.IsInvalid) return false;
                byte[] f05 = new byte[41];
                f05[0] = 0x05;
                return HidD_GetFeature(h, f05, 41);
            }
        } catch {
            return false;
        }
    }

    public static string FindDualSensePath() {
        Guid hidGuid;
        HidD_GetHidGuid(out hidGuid);
        IntPtr hDevInfo = SetupDiGetClassDevs(ref hidGuid, IntPtr.Zero, IntPtr.Zero, 0x12);
        if (hDevInfo == (IntPtr)(-1)) return null;

        SP_DEVICE_INTERFACE_DATA ifData = new SP_DEVICE_INTERFACE_DATA();
        ifData.cbSize = Marshal.SizeOf(ifData);

        string bestMatch = null;
        string btMatch = null;

        for (int i = 0; SetupDiEnumDeviceInterfaces(hDevInfo, IntPtr.Zero, ref hidGuid, i, ref ifData); i++) {
            int reqSize = 0;
            SP_DEVICE_INTERFACE_DETAIL_DATA detail = new SP_DEVICE_INTERFACE_DETAIL_DATA();
            detail.cbSize = IntPtr.Size == 8 ? 8 : 5;
            if (SetupDiGetDeviceInterfaceDetail(hDevInfo, ref ifData, ref detail, 512, ref reqSize, IntPtr.Zero)) {
                string p = detail.devicePath ?? "";
                bool isUsbMatch = p.IndexOf("vid_054c&pid_0ce6", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                  p.IndexOf("vid_054c&pid_0df2", StringComparison.OrdinalIgnoreCase) >= 0;
                bool isBtMatch = (p.IndexOf("054c", StringComparison.OrdinalIgnoreCase) >= 0) &&
                                 (p.IndexOf("0ce6", StringComparison.OrdinalIgnoreCase) >= 0 ||
                                  p.IndexOf("0df2", StringComparison.OrdinalIgnoreCase) >= 0);
                if (isUsbMatch) {
                    if (p.IndexOf("mi_03", StringComparison.OrdinalIgnoreCase) >= 0 && IsPathLive(p)) {
                        SetupDiDestroyDeviceInfoList(hDevInfo);
                        return p;
                    }
                    if (bestMatch == null && p.IndexOf("&col", StringComparison.OrdinalIgnoreCase) < 0 && IsPathLive(p)) {
                        bestMatch = p;
                    }
                } else if (isBtMatch && btMatch == null && IsPathLive(p)) {
                    btMatch = p;
                }
            }
        }
        SetupDiDestroyDeviceInfoList(hDevInfo);
        return bestMatch ?? btMatch;
    }

    private static bool ReadFully(System.IO.Stream stream, byte[] buf, int offset, int count) {
        int end = offset + count;
        while (offset < end) {
            int n = stream.Read(buf, offset, end - offset);
            if (n <= 0) return false;
            offset += n;
        }
        return true;
    }

    public static System.Threading.Thread StartUpstreamBridge(SafeFileHandle writeHandle, System.IO.Stream stream, bool isBluetooth) {
        System.Threading.Thread t = new System.Threading.Thread(() => {
            byte[] buf = new byte[48];
            int seq = 0;
            int outReportLen = GetOutputReportByteLength(writeHandle, isBluetooth ? 78 : 48);
            Stopwatch sw = Stopwatch.StartNew();
            object writeLock = new object();
            byte[] deferred = null;
            System.Threading.Timer resend = null;
            Action<byte[], bool> sendBt = (usb, init) => {
                lock (writeLock) {
                    if (writeHandle == null || writeHandle.IsInvalid || writeHandle.IsClosed) return;
                    byte[] btBuf = WrapUsbOutputToBt78(usb, seq, init, outReportLen);
                    seq = (seq + 1) & 0x0F;
                    int w = 0;
                    if (!WriteFile(writeHandle, btBuf, outReportLen, out w, IntPtr.Zero)) {
                        HidD_SetOutputReport(writeHandle, btBuf, outReportLen);
                    }
                }
            };
            try {
                while (true) {
                    if (!ReadFully(stream, buf, 0, 1)) return;
                    if (buf[0] == 0xB2) {
                        // Variable-length Bluetooth-only report (haptics 0x32): [0xB2, length LE16, report].
                        byte[] len = new byte[2];
                        if (!ReadFully(stream, len, 0, 2)) return;
                        byte[] raw = new byte[len[0] | (len[1] << 8)];
                        if (!ReadFully(stream, raw, 0, raw.Length)) return;
                        if (!isBluetooth || raw.Length == 0 || raw.Length > outReportLen) continue;
                        byte[] padded = new byte[outReportLen];
                        Array.Copy(raw, padded, raw.Length);
                        lock (writeLock) {
                            if (writeHandle == null || writeHandle.IsInvalid || writeHandle.IsClosed) continue;
                            int w = 0;
                            if (!WriteFile(writeHandle, padded, outReportLen, out w, IntPtr.Zero)) {
                                HidD_SetOutputReport(writeHandle, padded, outReportLen);
                            }
                        }
                        continue;
                    }
                    if (!ReadFully(stream, buf, 1, 47)) return;
                    if (writeHandle == null || writeHandle.IsInvalid || writeHandle.IsClosed) continue;
                    int written = 0;
                    if (isBluetooth) {
                        long elapsed = sw.ElapsedMilliseconds;
                        bool btInit = elapsed < 3000;
                        byte[] copy = (byte[])buf.Clone();
                        lock (writeLock) { deferred = null; }
                        sendBt(copy, btInit);
                        // The handshake window strips the lightbar; replay the colour once the window closes.
                        if (btInit && (copy[2] & 0x04) != 0) {
                            lock (writeLock) { deferred = copy; }
                            if (resend != null) resend.Dispose();
                            resend = new System.Threading.Timer(_ => {
                                byte[] pending;
                                lock (writeLock) { pending = deferred; deferred = null; }
                                if (pending != null) sendBt(pending, false);
                            }, null, 3000 - elapsed + 50, System.Threading.Timeout.Infinite);
                        }
                    } else {
                        if (!WriteFile(writeHandle, buf, 48, out written, IntPtr.Zero)) {
                            HidD_SetOutputReport(writeHandle, buf, 48);
                        }
                    }
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

# 1. Resolve ADB path (unless TestOnly)
if (-not $TestOnly) {
    if (-not (Test-Path $AdbPath)) {
        $found = Get-Command "adb.exe" -ErrorAction SilentlyContinue
        if ($found) {
            $AdbPath = $found.Source
        } else {
            throw "adb.exe not found at '$AdbPath' and not available in PATH."
        }
    }
}

$handle = $null
$writeHandle = $null
$listener = $null
$reverseActive = $false

try {
    if ($TestOnly) {
        $path = [DualSenseBridge]::FindDualSensePath()
        if (-not $path) {
            Write-Error "No Sony DualSense (0x0CE6) or DualSense Edge (0x0DF2) USB or Bluetooth HID device detected on host."
            exit 1
        }
        $isBluetooth = [DualSenseBridge]::IsBluetoothPath($path)
        $transportLabel = if ($isBluetooth) { "Bluetooth (0x31 78-byte)" } else { "USB (0x01 64-byte / 0x02 48-byte)" }
        Write-Host "Found physical DualSense [$transportLabel] at: $path" -ForegroundColor Green

        $handle = [DualSenseBridge]::CreateFile($path, 0xC0000000, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
        if ($handle.IsInvalid) {
            throw "Failed to open DualSense handle. Win32 Error: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
        }

        $f05 = New-Object byte[] 41
        $f05[0] = 0x05
        if ([DualSenseBridge]::HidD_GetFeature($handle, $f05, 41)) {
            Write-Host "DualSense Feature 0x05 (IMU Calibration / BT 0x31 Mode Switch) read OK (41 bytes)." -ForegroundColor Cyan
        }

        $f20 = New-Object byte[] 64
        $f20[0] = 0x20
        if ([DualSenseBridge]::HidD_GetFeature($handle, $f20, 64)) {
            $hw = [BitConverter]::ToUInt16($f20, 24)
            $fw = [BitConverter]::ToUInt16($f20, 44)
            Write-Host ("DualSense Verified: HW 0x{0:X4}, FW 0x{1:X4}" -f $hw, $fw) -ForegroundColor Cyan
        }

        $testOut = New-Object byte[] 48
        $testOut[0] = 0x02
        $testOut[1] = 0x03
        $testOut[2] = 0x15
        $testOut[9] = 0x01
        $testOut[42] = 0x02
        $testOut[44] = 0x04
        $testOut[45] = 0x00
        $testOut[46] = 180
        $testOut[47] = 255
        $written = 0
        if ($isBluetooth) {
            $outReportLen = [DualSenseBridge]::GetOutputReportByteLength($handle, 78)
            $btBuf = [DualSenseBridge]::WrapUsbOutputToBt78($testOut, 0, $false, $outReportLen)
            if (-not [DualSenseBridge]::WriteFile($handle, $btBuf, $outReportLen, [ref]$written, [IntPtr]::Zero)) {
                [DualSenseBridge]::HidD_SetOutputReport($handle, $btBuf, $outReportLen) | Out-Null
            }
        } else {
            if (-not [DualSenseBridge]::WriteFile($handle, $testOut, 48, [ref]$written, [IntPtr]::Zero)) {
                [DualSenseBridge]::HidD_SetOutputReport($handle, $testOut, 48) | Out-Null
            }
        }
        Write-Host "Test output report sent ($transportLabel). Written: $written bytes." -ForegroundColor Green
        $handle.Close()
        exit 0
    }

    # Configure adb reverse and start TCP listener
    Write-Host "Configuring adb reverse tcp:$Port tcp:$Port on $DeviceSerial..." -ForegroundColor Yellow
    & $AdbPath -s $DeviceSerial reverse tcp:$Port tcp:$Port
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to configure adb reverse on device '$DeviceSerial' (exit code $LASTEXITCODE). Verify the device is connected, authorized, and online."
    }
    $reverseActive = $true

    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    $listener.Start()
    Write-Host "Host bridge listening on 127.0.0.1:$Port (auto-switching USB & Bluetooth hot-plug enabled)..." -ForegroundColor Green

    while ($true) {
        if (-not $listener.Pending()) {
            Start-Sleep -Milliseconds 150
            continue
        }
        # Wait for live physical DualSense before accepting TCP client so the Android app only promotes when a DualSense is actually connected
        $path = [DualSenseBridge]::FindDualSensePath()
        if (-not $path) {
            Start-Sleep -Milliseconds 1000
            continue
        }

        $client = $listener.AcceptTcpClient()
        $client.NoDelay = $true
        $stream = $client.GetStream()

        # Re-verify path right at connection time and open dedicated read + write handles
        $path = [DualSenseBridge]::FindDualSensePath()
        if (-not $path) {
            try { $stream.Close() } catch {}
            try { $client.Close() } catch {}
            continue
        }
        $isBluetooth = [DualSenseBridge]::IsBluetoothPath($path)
        $transportLabel = if ($isBluetooth) { "Bluetooth (0x31 78-byte)" } else { "USB (0x01 64-byte / 0x02 48-byte)" }

        $handle = [DualSenseBridge]::CreateFile($path, 0xC0000000, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
        $writeHandle = [DualSenseBridge]::CreateFile($path, 0xC0000000, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
        if ($handle.IsInvalid) {
            if ($writeHandle -and -not $writeHandle.IsInvalid) { try { $writeHandle.Close() } catch {} }
            try { $stream.Close() } catch {}
            try { $client.Close() } catch {}
            Start-Sleep -Milliseconds 500
            continue
        }

        # Query Feature 0x05 (switches Bluetooth DualSense from 10B 0x01 mode to 78B 0x31 extended mode)
        $f05 = New-Object byte[] 41
        $f05[0] = 0x05
        $ok05 = [DualSenseBridge]::HidD_GetFeature($handle, $f05, 41)
        if (-not $ok05) {
            try { $handle.Close() } catch {}
            if ($writeHandle -and -not $writeHandle.IsInvalid) { try { $writeHandle.Close() } catch {} }
            try { $stream.Close() } catch {}
            try { $client.Close() } catch {}
            Start-Sleep -Milliseconds 500
            continue
        }

        Write-Host "Emulator connected to DualSense [$transportLabel] at: $path" -ForegroundColor Green
        $readLen = if ($isBluetooth) { 78 } else { 64 }

        # Thread: Upstream (Emulator 48B 0x02 -> Physical Controller WriteFile on dedicated writeHandle)
        $writerThread = [DualSenseBridge]::StartUpstreamBridge($writeHandle, $stream, $isBluetooth)

        # Main loop: Downstream (Physical Controller ReadFile 64B/78B -> Normalize to 64B 0x01 -> TCP Send to Emulator)
        $inBuf = New-Object byte[] $readLen
        $usb64 = New-Object byte[] 64
        try {
            while ($client.Connected) {
                $readBytes = 0
                if ([DualSenseBridge]::ReadFile($handle, $inBuf, $readLen, [ref]$readBytes, [IntPtr]::Zero)) {
                    if ([DualSenseBridge]::NormalizeInputToUsb64($inBuf, $readBytes, $usb64, $isBluetooth)) {
                        $stream.Write($usb64, 0, 64)
                    }
                } else {
                    Write-Host "DualSense transport disconnected ($transportLabel). Re-scanning for USB/Bluetooth..." -ForegroundColor Yellow
                    break
                }
            }
        } catch {
            Write-Host "Client disconnected: $_" -ForegroundColor Yellow
        } finally {
            try { $stream.Close() } catch {}
            try { $client.Close() } catch {}
            if ($writeHandle -and -not $writeHandle.IsInvalid -and -not $writeHandle.IsClosed) {
                try { $writeHandle.Close() } catch {}
            }
            if ($handle -and -not $handle.IsInvalid -and -not $handle.IsClosed) {
                try { $handle.Close() } catch {}
            }
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
    if ($writeHandle -and -not $writeHandle.IsInvalid -and -not $writeHandle.IsClosed) {
        try { $writeHandle.Close() } catch {}
    }
    if ($handle -and -not $handle.IsInvalid -and -not $handle.IsClosed) {
        try { $handle.Close() } catch {}
    }
    if ($listener) {
        Write-Host "DualSense bridge stopped." -ForegroundColor Gray
    }
}
