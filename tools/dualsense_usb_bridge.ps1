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
    [switch]$TestOnly,
    [switch]$SelfTest,
    [switch]$Emulate,
    [ValidateSet("DualSense", "DualSenseEdge")]
    [string]$ControllerType = "DualSense",
    [ValidateSet("USB", "Bluetooth")]
    [string]$Transport = "USB",
    [ValidateSet("Default", "Idle", "Sticks", "Buttons", "Edge", "Touchpad", "IMUNoise", "SteadyTurn", "Battery")]
    [string]$Scenario = "Default",
    [switch]$ValidateProtocol,
    [switch]$NoAdb
)

$ErrorActionPreference = "Stop"

if (-not ([System.Management.Automation.PSTypeName]'VirtualDualSenseController').Type) {
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
            if ((inBuf[1] & 0x01) == 0) return false;
            uint crcBytes = (uint)(inBuf[74] | (inBuf[75] << 8) | (inBuf[76] << 16) | (inBuf[77] << 24));
            if (crcBytes != 0 && ComputeBtCrc32(0xA1, inBuf, 0, 74) != crcBytes) {
                return false;
            }
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
            detail.cbSize = IntPtr.Size == 8 ? 8 : 4 + Marshal.SystemDefaultCharSize;
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
                        if (!isBluetooth || raw.Length == 0) continue;
                        byte[] payload;
                        if (outReportLen > raw.Length) {
                            payload = new byte[outReportLen];
                            Array.Copy(raw, payload, raw.Length);
                        } else {
                            payload = raw;
                        }
                        lock (writeLock) {
                            if (writeHandle == null || writeHandle.IsInvalid || writeHandle.IsClosed) continue;
                            int w = 0;
                            if (!WriteFile(writeHandle, payload, payload.Length, out w, IntPtr.Zero)) {
                                HidD_SetOutputReport(writeHandle, payload, payload.Length);
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
                        if ((copy[2] & 0x04) != 0) {
                            lock (writeLock) { deferred = null; }
                        }
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

    [DllImport("winmm.dll", EntryPoint = "timeBeginPeriod")]
    public static extern uint TimeBeginPeriod(uint uMilliseconds);

    [DllImport("winmm.dll", EntryPoint = "timeEndPeriod")]
    public static extern uint TimeEndPeriod(uint uMilliseconds);

    public static System.Threading.Thread StartUpstreamBridge(SafeFileHandle writeHandle, System.IO.Stream stream) {
        return StartUpstreamBridge(writeHandle, stream, false);
    }

    public static System.Threading.Thread StartVirtualUpstreamBridge(
        System.IO.Stream stream,
        bool isBluetooth,
        bool validateProtocol,
        bool isEdge,
        Action<byte[]> onReportReceived = null
    ) {
        System.Threading.Thread t = new System.Threading.Thread(() => {
            byte[] buf = new byte[48];
            try {
                while (true) {
                    if (!ReadFully(stream, buf, 0, 1)) return;
                    if (buf[0] == 0xB2) {
                        byte[] len = new byte[2];
                        if (!ReadFully(stream, len, 0, 2)) return;
                        byte[] raw = new byte[len[0] | (len[1] << 8)];
                        if (!ReadFully(stream, raw, 0, raw.Length)) return;
                        if (onReportReceived != null) onReportReceived(raw);
                        continue;
                    }
                    if (!ReadFully(stream, buf, 1, 47)) return;
                    byte[] copy = (byte[])buf.Clone();
                    if (validateProtocol) {
                        var res = OutputReportValidator.ValidateOutputReport(copy, isEdge);
                        if (!res.IsValid) {
                            Console.ForegroundColor = ConsoleColor.Red;
                            Console.WriteLine(string.Format("[PROTOCOL WARNING] Out report invalid: {0}", res.ErrorMessage));
                            Console.ResetColor();
                        }
                    }
                    if (onReportReceived != null) onReportReceived(copy);
                }
            } catch {}
        });
        t.IsBackground = true;
        t.Start();
        return t;
    }

    public static void StreamVirtualReports(System.IO.Stream stream, VirtualDualSenseController ctrl, string scenario) {
        TimeBeginPeriod(1);
        try {
            int tick = 0;
            Stopwatch sw = Stopwatch.StartNew();
            long nextTickMs = 0;
            byte[] normUsb = new byte[64];
            while (true) {
                ctrl.StepSimulation(tick);
                ctrl.ApplyScenario(scenario, tick);
                byte[] rep;
                if (ctrl.IsBluetooth) {
                    byte[] bt78 = ctrl.BuildInputReportBt78();
                    if (!NormalizeInputToUsb64(bt78, 78, normUsb, true)) {
                        throw new InvalidOperationException("Failed to normalize BT report");
                    }
                    rep = normUsb;
                } else {
                    rep = ctrl.BuildInputReport64();
                }
                stream.Write(rep, 0, 64);
                stream.Flush();
                tick++;
                nextTickMs += 4; // 250 Hz
                long waitMs = nextTickMs - sw.ElapsedMilliseconds;
                if (waitMs > 0) {
                    System.Threading.Thread.Sleep((int)waitMs);
                }
            }
        } finally {
            TimeEndPeriod(1);
        }
    }

    public static int RunSelfTest() {
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("=================================================================");
        Console.WriteLine("  DUALSENSE USB BRIDGE: IN-PROCESS PROTOCOL & SELF-TEST SUITE");
        Console.WriteLine("=================================================================");
        Console.ResetColor();

        int passed = 0;
        int total = 0;

        Action<string, bool, string> assertTest = (name, cond, details) => {
            total++;
            if (cond) {
                passed++;
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine(string.Format("[PASS] {0} - {1}", name, details));
            } else {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine(string.Format("[FAIL] {0} - {1}", name, details));
            }
            Console.ResetColor();
        };

        // 1. Virtual Controller USB 64B Input Report
        VirtualDualSenseController vCtrl = new VirtualDualSenseController();
        vCtrl.IsEdge = false;
        vCtrl.ApplyScenario("Idle");
        byte[] u64 = vCtrl.BuildInputReport64();
        assertTest("USB_Input_Report_Header", u64.Length == 64 && u64[0] == 0x01, "Length=64, ID=0x01");
        short accelZ = (short)(u64[26] | (u64[27] << 8));
        assertTest("USB_Idle_Rest_AccelZ", accelZ == 8192, "Accel Z = +8192 (+1G rest)");
        assertTest("USB_Idle_Sticks_Center", u64[1] == 128 && u64[2] == 128, "Left stick centered at (128,128)");

        // 2. DualSense Edge Features
        VirtualDualSenseController vCtrlEdge = new VirtualDualSenseController();
        vCtrlEdge.IsEdge = true;
        vCtrlEdge.ApplyScenario("Edge", 0);
        byte[] e64 = vCtrlEdge.BuildInputReport64();
        assertTest("Edge_Back_Paddles_L4_L5", (e64[10] & 0x50) == 0x50, "L4 (Fn1 0x40) & L5 (Paddle L 0x10) asserted in byte 10");
        assertTest("Edge_Tuning_Mode_Byte49", e64[49] == 0x0E, "Edge profile tuning indicator 0x0E present at byte 49");

        // 3. Bluetooth 0x31 78B Input Report & CRC-32
        VirtualDualSenseController vCtrlBt = new VirtualDualSenseController();
        vCtrlBt.IsBluetooth = true;
        vCtrlBt.ApplyScenario("Idle");
        byte[] bt78 = vCtrlBt.BuildInputReportBt78();
        assertTest("BT_Input_Report_Header", bt78.Length == 78 && bt78[0] == 0x31 && bt78[1] == 0x01, "Length=78, ID=0x31, Mode=0x01");
        uint crcExpected = ComputeBtCrc32(0xA1, bt78, 0, 74);
        uint crcActual = (uint)(bt78[74] | (bt78[75] << 8) | (bt78[76] << 16) | (bt78[77] << 24));
        assertTest("BT_Input_CRC32_Seed_A1", crcExpected == crcActual, string.Format("Computed 0x{0:X8} matches packet CRC", crcExpected));

        // 4. Normalization Engine
        byte[] normUsb = new byte[64];
        bool normOk = NormalizeInputToUsb64(bt78, 78, normUsb, true);
        assertTest("Normalize_BT78_To_USB64", normOk && normUsb[0] == 0x01 && normUsb[26] == u64[26], "78B BT successfully normalized to 64B USB");

        byte[] corruptBt = (byte[])bt78.Clone();
        corruptBt[74] ^= 0xFF;
        bool corruptOk = NormalizeInputToUsb64(corruptBt, 78, normUsb, true);
        assertTest("Normalize_Corrupt_CRC_Rejected", !corruptOk, "Corrupted seed 0xA1 CRC correctly rejected");

        byte[] basicBt = new byte[10];
        basicBt[0] = 0x01; basicBt[1] = 100; basicBt[2] = 150; basicBt[3] = 128; basicBt[4] = 128; basicBt[5] = 0x08; basicBt[8] = 200; basicBt[9] = 250;
        bool basicOk = NormalizeInputToUsb64(basicBt, 10, normUsb, false);
        assertTest("Normalize_Basic_BT10B_Fallback", basicOk && normUsb[1] == 100 && normUsb[5] == 200, "10B basic BT report unpacked to canonical 64B USB");

        // 5. Output Report Validator - 12 Trigger Profiles
        byte[] outTest = new byte[48];
        outTest[0] = 0x02; outTest[44] = 0x04; // Player 1

        // Off (0x05)
        outTest[11] = 0x05; outTest[22] = 0x05;
        var res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x05_Off", res.IsValid, "Mode 0x05 Off valid");

        // Simple Feedback (0x01)
        outTest[11] = 0x01; outTest[12] = 2; outTest[13] = 6;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x01_SimpleFeedback", res.IsValid, "Mode 0x01 Simple Feedback valid");

        // Simple Weapon (0x02)
        outTest[11] = 0x02; outTest[12] = 2; outTest[13] = 6; outTest[14] = 7;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x02_SimpleWeapon", res.IsValid, "Mode 0x02 Simple Weapon valid");

        // Simple Vibration (0x06)
        outTest[11] = 0x06; outTest[12] = 40; outTest[13] = 6; outTest[14] = 2;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x06_SimpleVibration", res.IsValid, "Mode 0x06 Simple Vibration valid");

        // Limited Feedback (0x11) & Limited Weapon (0x12)
        outTest[11] = 0x11; outTest[12] = 2; outTest[13] = 8;
        outTest[22] = 0x12; outTest[23] = 20; outTest[24] = 150; outTest[25] = 8;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x11_0x12_Limited", res.IsValid, "Modes 0x11 & 0x12 Limited Feedback/Weapon valid");

        // Feedback (0x21)
        outTest[11] = 0x21; outTest[12] = 0xFC; outTest[13] = 0x03; outTest[14] = 0xFF; outTest[15] = 0xFF; outTest[16] = 0x03;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x21_Feedback", res.IsValid, "Mode 0x21 Multi-Zone Feedback valid");

        // Bow (0x22)
        outTest[11] = 0x22; outTest[12] = 0x22; outTest[13] = 0x00; outTest[14] = 0x3E;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x22_Bow", res.IsValid, "Mode 0x22 Bow valid");

        // Galloping (0x23)
        outTest[11] = 0x23; outTest[12] = 0x30; outTest[13] = 0x00; outTest[14] = 0x19; outTest[15] = 20;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x23_Galloping", res.IsValid, "Mode 0x23 Galloping valid");

        // Weapon (0x25)
        outTest[11] = 0x25; outTest[12] = 0x24; outTest[13] = 0x00; outTest[14] = 0x06;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x25_Weapon", res.IsValid, "Mode 0x25 Weapon click valid");

        // Vibration (0x26)
        outTest[11] = 0x26; outTest[12] = 0xFC; outTest[13] = 0x03; outTest[20] = 40;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x26_Vibration", res.IsValid, "Mode 0x26 Multi-Zone Vibration valid");

        // Machine (0x27)
        outTest[11] = 0x27; outTest[12] = 0x24; outTest[13] = 0x00; outTest[14] = 0x2D; outTest[15] = 30; outTest[16] = 100;
        res = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Trigger_Profile_0x27_Machine", res.IsValid, "Mode 0x27 Machine valid");

        // Rejection of invalid trigger profile
        outTest[11] = 0x99;
        var resBad = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Reject_Invalid_Trigger_Profile", !resBad.IsValid, "Unknown profile mode 0x99 rejected");
        outTest[11] = 0x05;

        // 6. Player LEDs & Brightness Validation
        outTest[44] = 0x15; // Player 3
        outTest[43] = 0x01; // Medium
        var resLed = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Player_LED_Mask_0x15_Valid", resLed.IsValid, "Player 3 mask 0x15 with medium brightness valid");

        outTest[44] = 0x05; // Invalid mask
        var resBadLed = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Reject_Invalid_Player_LED_Mask", !resBadLed.IsValid, "Invalid player mask 0x05 rejected");
        outTest[44] = 0x04;

        // 7. Mic Mute LED & Rumble
        outTest[9] = 0x01; // Solid on
        outTest[3] = 200; outTest[4] = 250;
        var resRumble = OutputReportValidator.ValidateOutputReport(outTest);
        assertTest("Rumble_And_MicLED_Valid", resRumble.IsValid, "Rumble and solid mic LED valid");

        // 8. Bluetooth 3000 ms Startup Guard
        outTest[2] = 0x14; // Enable Lightbar RGB & Player LEDs
        outTest[45] = 255; outTest[46] = 0; outTest[47] = 255; // Magenta
        byte[] btWrappedInit = WrapUsbOutputToBt78(outTest, 0, true, 78);
        var guardCheckInit = OutputReportValidator.ValidateBtOutputReport(btWrappedInit, 1500);
        assertTest("BT_Startup_Guard_Shielded", guardCheckInit.IsValid && (btWrappedInit[3] & 0x04) == 0 && btWrappedInit[43] == 0x01, "RGB stripped & smooth fade forced during t < 3000ms");

        byte[] btWrappedReady = WrapUsbOutputToBt78(outTest, 1, false, 78);
        var guardCheckReady = OutputReportValidator.ValidateBtOutputReport(btWrappedReady, 3500);
        assertTest("BT_Startup_Guard_Unshielded", guardCheckReady.IsValid && (btWrappedReady[3] & 0x04) == 0x04 && btWrappedReady[46] == 255, "Full RGB transmitted after t >= 3000ms");

        // 9. Feature Report Synthesizer
        byte[] f05 = FeatureReportSynthesizer.CreateFeature05();
        assertTest("Feature_05_IMU_Calibration", f05.Length == 41 && f05[0] == 0x05, "41B calibration report synthesized");

        byte[] f09 = FeatureReportSynthesizer.CreateFeature09("DC:0C:2D:11:22:33", "E4:5F:01:44:55:66");
        assertTest("Feature_09_MAC_Pairing", f09.Length == 20 && f09[0] == 0x09, "20B pairing info synthesized");

        byte[] f20 = FeatureReportSynthesizer.CreateFeature20();
        assertTest("Feature_20_Firmware_Telemetry", f20.Length == 64 && f20[0] == 0x20, "64B firmware telemetry synthesized");

        byte[] f22 = FeatureReportSynthesizer.CreateFeature22();
        assertTest("Feature_22_BT_Patch_Info", f22.Length == 64 && f22[0] == 0x22, "64B BT patch report synthesized");

        // 10. Upstream Framing Engine
        using (System.IO.MemoryStream ms = new System.IO.MemoryStream()) {
            ms.Write(outTest, 0, 20);
            ms.Write(outTest, 20, 28);
            ms.Position = 0;
            List<byte[]> recList = new List<byte[]>();
            System.Threading.Thread th = StartVirtualUpstreamBridge(ms, false, false, false, b => recList.Add(b));
            System.Threading.Thread.Sleep(50);
            assertTest("Upstream_Framing_20B_28B_Reassembly", recList.Count == 1 && recList[0].Length == 48, "Fragmented 20B+28B reassembled into 1 complete report");
        }

        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("\n-----------------------------------------------------------------");
        Console.ForegroundColor = (passed == total) ? ConsoleColor.Green : ConsoleColor.Red;
        Console.WriteLine(string.Format("SELF-TEST SUMMARY: {0}/{1} Passed", passed, total));
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("-----------------------------------------------------------------");
        Console.ResetColor();

        return (passed == total) ? 0 : 1;
    }
}

public class VirtualDualSenseController {
    public ushort VendorId = 0x054C;
    public ushort ProductId = 0x0CE6;
    public bool IsEdge {
        get { return ProductId == 0x0DF2; }
        set { ProductId = value ? (ushort)0x0DF2 : (ushort)0x0CE6; }
    }
    public bool IsBluetooth = false;
    public byte SequenceNumber = 0;
    public uint SensorTimestamp = 0;

    public byte LeftStickX = 128;
    public byte LeftStickY = 128;
    public byte RightStickX = 128;
    public byte RightStickY = 128;

    public byte LeftTrigger = 0;
    public byte RightTrigger = 0;

    public byte Dpad = 8;
    public bool Square = false;
    public bool Cross = false;
    public bool Circle = false;
    public bool Triangle = false;

    public bool L1 = false;
    public bool R1 = false;
    public bool L2Click = false;
    public bool R2Click = false;
    public bool Create = false;
    public bool Options = false;
    public bool L3 = false;
    public bool R3 = false;

    public bool PS = false;
    public bool TouchpadClick = false;
    public bool MicMute = false;
    public bool L5 = false;
    public bool R5 = false;
    public bool L4 = false;
    public bool R4 = false;

    public short GyroX = 0;
    public short GyroY = 0;
    public short GyroZ = 0;
    public short AccelX = 0;
    public short AccelY = 0;
    public short AccelZ = 8192;

    public bool Touch0Down = false;
    public byte Touch0Id = 0;
    public int Touch0X = 0;
    public int Touch0Y = 0;

    public bool Touch1Down = false;
    public byte Touch1Id = 1;
    public int Touch1X = 0;
    public int Touch1Y = 0;

    public byte EdgeTuning = 0;
    public byte BatteryLevel = 10;
    public byte PowerState = 0x01;
    public bool HeadphonesPlugged = false;

    public byte[] BuildInputReport64() {
        byte[] p = new byte[64];
        p[0] = 0x01;
        p[1] = LeftStickX;
        p[2] = LeftStickY;
        p[3] = RightStickX;
        p[4] = RightStickY;
        p[5] = LeftTrigger;
        p[6] = RightTrigger;
        p[7] = SequenceNumber;

        byte b8 = (byte)(Dpad & 0x0F);
        if (Square)   b8 |= 0x10;
        if (Cross)    b8 |= 0x20;
        if (Circle)   b8 |= 0x40;
        if (Triangle) b8 |= 0x80;
        p[8] = b8;

        byte b9 = 0;
        if (L1)      b9 |= 0x01;
        if (R1)      b9 |= 0x02;
        if (L2Click) b9 |= 0x04;
        if (R2Click) b9 |= 0x08;
        if (Create)  b9 |= 0x10;
        if (Options) b9 |= 0x20;
        if (L3)      b9 |= 0x40;
        if (R3)      b9 |= 0x80;
        p[9] = b9;

        byte b10 = 0;
        if (PS)            b10 |= 0x01;
        if (TouchpadClick) b10 |= 0x02;
        if (MicMute)       b10 |= 0x04;
        if (IsEdge) {
            if (L5) b10 |= 0x10;
            if (R5) b10 |= 0x20;
            if (L4) b10 |= 0x40;
            if (R4) b10 |= 0x80;
        }
        p[10] = b10;

        p[16] = (byte)(GyroX & 0xFF);
        p[17] = (byte)((GyroX >> 8) & 0xFF);
        p[18] = (byte)(GyroY & 0xFF);
        p[19] = (byte)((GyroY >> 8) & 0xFF);
        p[20] = (byte)(GyroZ & 0xFF);
        p[21] = (byte)((GyroZ >> 8) & 0xFF);

        p[22] = (byte)(AccelX & 0xFF);
        p[23] = (byte)((AccelX >> 8) & 0xFF);
        p[24] = (byte)(AccelY & 0xFF);
        p[25] = (byte)((AccelY >> 8) & 0xFF);
        p[26] = (byte)(AccelZ & 0xFF);
        p[27] = (byte)((AccelZ >> 8) & 0xFF);

        p[28] = (byte)(SensorTimestamp & 0xFF);
        p[29] = (byte)((SensorTimestamp >> 8) & 0xFF);
        p[30] = (byte)((SensorTimestamp >> 16) & 0xFF);
        p[31] = (byte)((SensorTimestamp >> 24) & 0xFF);

        p[32] = 0x19;

        int cx0 = Math.Max(0, Math.Min(1919, Touch0X));
        int cy0 = Math.Max(0, Math.Min(1079, Touch0Y));
        p[33] = (byte)((Touch0Down ? 0x00 : 0x80) | (Touch0Id & 0x7F));
        p[34] = (byte)(cx0 & 0xFF);
        p[35] = (byte)(((cx0 >> 8) & 0x0F) | ((cy0 & 0x0F) << 4));
        p[36] = (byte)((cy0 >> 4) & 0xFF);

        int cx1 = Math.Max(0, Math.Min(1919, Touch1X));
        int cy1 = Math.Max(0, Math.Min(1079, Touch1Y));
        p[37] = (byte)((Touch1Down ? 0x00 : 0x80) | (Touch1Id & 0x7F));
        p[38] = (byte)(cx1 & 0xFF);
        p[39] = (byte)(((cx1 >> 8) & 0x0F) | ((cy1 & 0x0F) << 4));
        p[40] = (byte)((cy1 >> 4) & 0xFF);

        if (IsEdge && EdgeTuning != 0) {
            p[49] = EdgeTuning;
        }

        p[53] = (byte)(((PowerState & 0x0F) << 4) | (BatteryLevel & 0x0F));

        if (HeadphonesPlugged) {
            p[54] = 0x01;
        }

        return p;
    }

    public byte[] BuildInputReportBt78() {
        byte[] usb = BuildInputReport64();
        byte[] bt = new byte[78];
        bt[0] = 0x31;
        bt[1] = 0x01;
        Array.Copy(usb, 1, bt, 2, 63);

        uint crc = DualSenseBridge.ComputeBtCrc32(0xA1, bt, 0, 74);
        bt[74] = (byte)(crc & 0xFF);
        bt[75] = (byte)((crc >> 8) & 0xFF);
        bt[76] = (byte)((crc >> 16) & 0xFF);
        bt[77] = (byte)((crc >> 24) & 0xFF);

        return bt;
    }

    public void StepSimulation(int tick) {
        SequenceNumber = (byte)((SequenceNumber + 1) & 0xFF);
        SensorTimestamp += 12000;
    }

    public void ApplyScenario(string scenario, int tick = 0) {
        if (scenario == null) scenario = "idle";
        switch (scenario.ToLowerInvariant()) {
            case "default":
            case "idle":
            default:
                LeftStickX = 128; LeftStickY = 128;
                RightStickX = 128; RightStickY = 128;
                LeftTrigger = 0; RightTrigger = 0;
                Dpad = 8;
                Square = Cross = Circle = Triangle = false;
                L1 = R1 = L2Click = R2Click = Create = Options = L3 = R3 = false;
                PS = TouchpadClick = MicMute = false;
                L4 = R4 = L5 = R5 = false;
                EdgeTuning = 0;
                GyroX = GyroY = GyroZ = 0;
                AccelX = AccelY = 0; AccelZ = 8192;
                Touch0Down = Touch1Down = false;
                BatteryLevel = 10; PowerState = 0x01;
                break;

            case "sticks":
                double angle = (tick % 100) * (2.0 * Math.PI / 100.0);
                LeftStickX = (byte)Math.Max(0, Math.Min(255, (int)(128 + 120 * Math.Cos(angle))));
                LeftStickY = (byte)Math.Max(0, Math.Min(255, (int)(128 + 120 * Math.Sin(angle))));
                RightStickX = (byte)((tick * 3) % 256);
                RightStickY = (byte)(255 - ((tick * 3) % 256));
                LeftTrigger = (byte)((tick * 4) % 256);
                RightTrigger = (byte)(255 - ((tick * 4) % 256));
                break;

            case "buttons":
                int phase = (tick / 25) % 12;
                Square = (phase == 0);
                Cross = (phase == 1);
                Circle = (phase == 2);
                Triangle = (phase == 3);
                Dpad = (byte)(phase < 8 ? phase : 8);
                L1 = (phase == 4);
                R1 = (phase == 5);
                L2Click = (phase == 6);
                R2Click = (phase == 7);
                Create = (phase == 8);
                Options = (phase == 9);
                L3 = (phase == 10);
                R3 = (phase == 11);
                PS = (phase == 0 || phase == 6);
                MicMute = (phase == 2 || phase == 8);
                break;

            case "edge":
                IsEdge = true;
                int ePhase = (tick / 25) % 6;
                L4 = (ePhase == 0 || ePhase == 2 || ePhase == 4);
                R4 = (ePhase == 1 || ePhase == 3 || ePhase == 4);
                L5 = (ePhase == 0 || ePhase == 2 || ePhase == 5);
                R5 = (ePhase == 1 || ePhase == 3 || ePhase == 5);
                EdgeTuning = (byte)(ePhase % 2 == 0 ? 0x0E : 0x00);
                break;

            case "touchpad":
                Touch0Down = true;
                Touch0Id = 1;
                Touch0X = (tick * 20) % 1920;
                Touch0Y = 540;
                Touch1Down = (tick % 50 > 10);
                Touch1Id = 2;
                Touch1X = 960;
                Touch1Y = (tick * 15) % 1080;
                TouchpadClick = (tick % 60 < 10);
                break;

            case "imunoise":
                int n = tick % 17;
                GyroX = (short)((n - 8) / 2);
                GyroY = (short)(((n * 3) % 17 - 8) / 2);
                GyroZ = (short)(((n * 7) % 17 - 8) / 2);
                AccelX = (short)((n - 8) * 3);
                AccelY = (short)(((n * 5) % 17 - 8) * 3);
                AccelZ = (short)(8192 + ((n * 9) % 17 - 8) * 3);
                break;

            case "steadyturn":
                GyroX = 0;
                GyroY = 0;
                GyroZ = 500;
                AccelX = 0;
                AccelY = 0;
                AccelZ = 8192;
                break;

            case "battery":
                int bPhase = (tick / 20) % 11;
                BatteryLevel = (byte)bPhase;
                int sPhase = (tick / 80) % 4;
                PowerState = (byte)(sPhase == 0 ? 0x00 : (sPhase == 1 ? 0x01 : (sPhase == 2 ? 0x02 : 0x0A)));
                break;
        }
    }
}

public class OutputReportValidator {
    public struct ValidationResult {
        public bool IsValid;
        public string ErrorMessage;
        public string EffectSummary;

        public static ValidationResult Success(string summary) {
            ValidationResult r = new ValidationResult();
            r.IsValid = true;
            r.ErrorMessage = null;
            r.EffectSummary = summary;
            return r;
        }
        public static ValidationResult Fail(string error) {
            ValidationResult r = new ValidationResult();
            r.IsValid = false;
            r.ErrorMessage = error;
            r.EffectSummary = null;
            return r;
        }
    }

    public static ValidationResult ValidateOutputReport(byte[] report, bool isEdge = false) {
        if (report == null)
            return ValidationResult.Fail("Report is null");
        if (report.Length != 48)
            return ValidationResult.Fail(string.Format("Report length must be 48 bytes (got {0})", report.Length));
        if (report[0] != 0x02)
            return ValidationResult.Fail(string.Format("Report ID must be 0x02 (got 0x{0:X2})", report[0]));

        byte micLed = report[9];
        if (micLed > 0x02)
            return ValidationResult.Fail(string.Format("Invalid Mic Mute LED value: 0x{0:X2} (allowed: 0=Off, 1=Solid, 2=Pulse)", micLed));

        var rightTrigRes = ValidateTriggerBuffer("Right Trigger", report, 11);
        if (!rightTrigRes.IsValid) return rightTrigRes;

        var leftTrigRes = ValidateTriggerBuffer("Left Trigger", report, 22);
        if (!leftTrigRes.IsValid) return leftTrigRes;

        byte playerBrightness = (byte)(report[43] & 0x03);
        if (playerBrightness > 0x02)
            return ValidationResult.Fail(string.Format("Invalid Player LED brightness: 0x{0:X2} (allowed: 0, 1, 2)", playerBrightness));

        byte playerMask = report[44];
        if (playerMask != 0x00 && playerMask != 0x04 && playerMask != 0x0A &&
            playerMask != 0x15 && playerMask != 0x1B && playerMask != 0x1F) {
            return ValidationResult.Fail(string.Format("Invalid Player LED pattern mask: 0x{0:X2} (expected 0x00, 0x04, 0x0A, 0x15, 0x1B, or 0x1F)", playerMask));
        }

        string summary = string.Format("Rumble: [L={0}, R={1}] | Lightbar: RGB({2},{3},{4}) Setup=0x{5:X2} | PlayerLED: 0x{6:X2} Bright={7} | MicLED: {8} | RT: {9} | LT: {10}",
            report[4], report[3], report[45], report[46], report[47], report[42], playerMask, playerBrightness, micLed, rightTrigRes.EffectSummary, leftTrigRes.EffectSummary);
        return ValidationResult.Success(summary);
    }

    public static ValidationResult ValidateTriggerBuffer(string triggerName, byte[] report, int offset) {
        byte mode = report[offset];
        switch (mode) {
            case 0x00:
            case 0x05:
                return ValidationResult.Success(string.Format("{0}: Off (0x05)", triggerName));

            case 0x01:
            {
                byte start = report[offset + 1];
                byte force = report[offset + 2];
                if (start > 9)
                    return ValidationResult.Fail(string.Format("{0} Simple Feedback start position {1} > 9", triggerName, start));
                if (force > 8)
                    return ValidationResult.Fail(string.Format("{0} Simple Feedback force {1} > 8", triggerName, force));
                return ValidationResult.Success(string.Format("{0}: Simple Feedback (start={1}, force={2})", triggerName, start, force));
            }

            case 0x02:
            {
                byte start = report[offset + 1];
                byte end = report[offset + 2];
                byte force = report[offset + 3];
                if (start > 9)
                    return ValidationResult.Fail(string.Format("{0} Simple Weapon start position {1} > 9", triggerName, start));
                if (end > 9)
                    return ValidationResult.Fail(string.Format("{0} Simple Weapon end position {1} > 9", triggerName, end));
                if (end <= start)
                    return ValidationResult.Fail(string.Format("{0} Simple Weapon end position {1} <= start position {2}", triggerName, end, start));
                if (force > 8)
                    return ValidationResult.Fail(string.Format("{0} Simple Weapon force {1} > 8", triggerName, force));
                return ValidationResult.Success(string.Format("{0}: Simple Weapon (start={1}, end={2}, force={3})", triggerName, start, end, force));
            }

            case 0x06:
            {
                byte freq = report[offset + 1];
                byte amp = report[offset + 2];
                byte pos = report[offset + 3];
                if (amp > 8)
                    return ValidationResult.Fail(string.Format("{0} Simple Vibration amplitude {1} > 8", triggerName, amp));
                if (pos > 9)
                    return ValidationResult.Fail(string.Format("{0} Simple Vibration position {1} > 9", triggerName, pos));
                return ValidationResult.Success(string.Format("{0}: Simple Vibration (freq={1}, amp={2}, pos={3})", triggerName, freq, amp, pos));
            }

            case 0x11:
            {
                byte start = report[offset + 1];
                byte force = report[offset + 2];
                if (force > 10)
                    return ValidationResult.Fail(string.Format("{0} Limited Feedback force {1} > 10", triggerName, force));
                return ValidationResult.Success(string.Format("{0}: Limited Feedback (start={1}, force={2})", triggerName, start, force));
            }

            case 0x12:
            {
                byte start = report[offset + 1];
                byte end = report[offset + 2];
                byte force = report[offset + 3];
                if (force > 10)
                    return ValidationResult.Fail(string.Format("{0} Limited Weapon force {1} > 10", triggerName, force));
                return ValidationResult.Success(string.Format("{0}: Limited Weapon (start={1}, end={2}, force={3})", triggerName, start, end, force));
            }

            case 0x21:
            {
                ushort zones = (ushort)(report[offset + 1] | (report[offset + 2] << 8));
                if (zones > 0x03FF)
                    return ValidationResult.Fail(string.Format("{0} Feedback zones bitmask 0x{1:X4} exceeds 10 zones (max 0x03FF)", triggerName, zones));
                uint forcePacked = (uint)(report[offset + 3] | (report[offset + 4] << 8) | (report[offset + 5] << 16) | (report[offset + 6] << 24));
                return ValidationResult.Success(string.Format("{0}: Feedback (zones=0x{1:X3}, packedForces=0x{2:X8})", triggerName, zones, forcePacked));
            }

            case 0x22:
            {
                ushort zones = (ushort)(report[offset + 1] | (report[offset + 2] << 8));
                if (zones > 0x03FF)
                    return ValidationResult.Fail(string.Format("{0} Bow zones bitmask 0x{1:X4} exceeds 10 zones", triggerName, zones));
                byte forceLow = report[offset + 3];
                int pull = forceLow & 0x07;
                int snap = (forceLow >> 3) & 0x07;
                return ValidationResult.Success(string.Format("{0}: Bow (zones=0x{1:X3}, pull={2}, snap={3})", triggerName, zones, pull, snap));
            }

            case 0x23:
            {
                ushort zones = (ushort)(report[offset + 1] | (report[offset + 2] << 8));
                if (zones > 0x03FF)
                    return ValidationResult.Fail(string.Format("{0} Galloping zones bitmask 0x{1:X4} exceeds 10 zones", triggerName, zones));
                byte ratio = report[offset + 3];
                byte freq = report[offset + 4];
                return ValidationResult.Success(string.Format("{0}: Galloping (zones=0x{1:X3}, ratio=0x{2:X2}, freq={3})", triggerName, zones, ratio, freq));
            }

            case 0x25:
            {
                ushort zones = (ushort)(report[offset + 1] | (report[offset + 2] << 8));
                if (zones > 0x03FF)
                    return ValidationResult.Fail(string.Format("{0} Weapon zones bitmask 0x{1:X4} exceeds 10 zones", triggerName, zones));
                byte force = report[offset + 3];
                if (force > 7)
                    return ValidationResult.Fail(string.Format("{0} Weapon strength {1} > 7", triggerName, force));
                return ValidationResult.Success(string.Format("{0}: Weapon (zones=0x{1:X3}, force={2})", triggerName, zones, force));
            }

            case 0x26:
            {
                ushort zones = (ushort)(report[offset + 1] | (report[offset + 2] << 8));
                if (zones > 0x03FF)
                    return ValidationResult.Fail(string.Format("{0} Vibration zones bitmask 0x{1:X4} exceeds 10 zones", triggerName, zones));
                byte freq = report[offset + 9];
                return ValidationResult.Success(string.Format("{0}: Vibration (zones=0x{1:X3}, freq={2})", triggerName, zones, freq));
            }

            case 0x27:
            {
                ushort zones = (ushort)(report[offset + 1] | (report[offset + 2] << 8));
                if (zones > 0x03FF)
                    return ValidationResult.Fail(string.Format("{0} Machine zones bitmask 0x{1:X4} exceeds 10 zones", triggerName, zones));
                byte amp = report[offset + 3];
                byte freq = report[offset + 4];
                byte period = report[offset + 5];
                return ValidationResult.Success(string.Format("{0}: Machine (zones=0x{1:X3}, amp=0x{2:X2}, freq={3}, period={4})", triggerName, zones, amp, freq, period));
            }

            default:
                return ValidationResult.Fail(string.Format("{0} Unknown/Invalid profile mode 0x{1:X2}", triggerName, mode));
        }
    }

    public static ValidationResult ValidateBtOutputReport(byte[] btReport, long elapsedMs) {
        if (btReport == null)
            return ValidationResult.Fail("BT Report is null");
        if (btReport.Length < 78)
            return ValidationResult.Fail(string.Format("BT Report length {0} < 78", btReport.Length));
        if (btReport[0] != 0x31)
            return ValidationResult.Fail(string.Format("BT Report ID must be 0x31 (got 0x{0:X2})", btReport[0]));
        if ((btReport[1] & 0x0F) != 0x02)
            return ValidationResult.Fail(string.Format("BT Report mode low nibble must be 0x02 (got 0x{0:X2})", btReport[1]));

        uint expectedCrc = DualSenseBridge.ComputeBtCrc32(0xA2, btReport, 0, 74);
        uint actualCrc = (uint)(btReport[74] | (btReport[75] << 8) | (btReport[76] << 16) | (btReport[77] << 24));
        if (expectedCrc != actualCrc) {
            return ValidationResult.Fail(string.Format("BT Output CRC-32 mismatch (computed 0x{0:X8}, packet had 0x{1:X8})", expectedCrc, actualCrc));
        }

        if (elapsedMs < 3000) {
            if ((btReport[3] & 0x04) != 0) {
                return ValidationResult.Fail(string.Format("3000ms BT Guard VIOLATION: RGB enable bit set during startup ({0} ms < 3000 ms)", elapsedMs));
            }
            if (btReport[43] == 0x02) {
                return ValidationResult.Fail(string.Format("3000ms BT Guard VIOLATION: LightbarSetup=0x02 (ResetLights) sent during startup ({0} ms < 3000 ms)", elapsedMs));
            }
            if (btReport[46] != 0 || btReport[47] != 0 || btReport[48] != 0) {
                return ValidationResult.Fail(string.Format("3000ms BT Guard VIOLATION: RGB colors non-zero ({0},{1},{2}) during startup ({3} ms < 3000 ms)", btReport[46], btReport[47], btReport[48], elapsedMs));
            }
        }

        return ValidationResult.Success(string.Format("BT Report seq={0} valid (Guard active: {1})", (btReport[1] >> 4) & 0x0F, elapsedMs < 3000));
    }
}

public class FeatureReportSynthesizer {
    private static void SetShort(byte[] f, int idx, short val) {
        f[idx] = (byte)(val & 0xFF);
        f[idx + 1] = (byte)((val >> 8) & 0xFF);
    }

    private static void SetU16(byte[] f, int idx, ushort val) {
        f[idx] = (byte)(val & 0xFF);
        f[idx + 1] = (byte)((val >> 8) & 0xFF);
    }

    private static void SetU32(byte[] f, int idx, uint val) {
        f[idx] = (byte)(val & 0xFF);
        f[idx + 1] = (byte)((val >> 8) & 0xFF);
        f[idx + 2] = (byte)((val >> 16) & 0xFF);
        f[idx + 3] = (byte)((val >> 24) & 0xFF);
    }

    private static void SetMac(byte[] f, int offset, string mac) {
        string[] parts = mac.Split(new char[] { ':', '-' });
        for (int i = 0; i < 6 && i < parts.Length; i++) {
            byte b = Convert.ToByte(parts[i], 16);
            f[offset + (5 - i)] = b;
        }
    }

    public static byte[] CreateFeature05(
        short gyroBiasX = 0, short gyroBiasY = 0, short gyroBiasZ = 0,
        short gyroPlus = 8192, short gyroMinus = -8192,
        short gyroSpeedPlus = 1024, short gyroSpeedMinus = 1024,
        short accelPlus = 8192, short accelMinus = -8192
    ) {
        byte[] f = new byte[41];
        f[0] = 0x05;

        SetShort(f, 1, gyroBiasX);
        SetShort(f, 3, gyroBiasY);
        SetShort(f, 5, gyroBiasZ);

        SetShort(f, 7, gyroPlus);
        SetShort(f, 9, gyroMinus);
        SetShort(f, 11, gyroPlus);
        SetShort(f, 13, gyroMinus);
        SetShort(f, 15, gyroPlus);
        SetShort(f, 17, gyroMinus);

        SetShort(f, 19, gyroSpeedPlus);
        SetShort(f, 21, gyroSpeedMinus);

        SetShort(f, 23, accelPlus);
        SetShort(f, 25, accelMinus);
        SetShort(f, 27, accelPlus);
        SetShort(f, 29, accelMinus);
        SetShort(f, 31, accelPlus);
        SetShort(f, 33, accelMinus);

        return f;
    }

    public static byte[] CreateFeature09(string deviceMac = "DC:0C:2D:11:22:33", string hostMac = "E4:5F:01:44:55:66") {
        byte[] f = new byte[20];
        f[0] = 0x09;
        SetMac(f, 1, deviceMac);
        SetMac(f, 10, hostMac);
        return f;
    }

    public static byte[] CreateFeature20(
        string buildDate = "Jul  4 2025",
        string buildTime = "10:10:32",
        ushort fwType = 0x0001,
        ushort swSeries = 0x0002,
        uint hwInfo = 0x00000621,
        uint mainFw = 0x0110002A,
        uint sblFw = 0x00000630,
        uint dspFw = 0x0001003C
    ) {
        byte[] f = new byte[64];
        f[0] = 0x20;

        byte[] dateBytes = System.Text.Encoding.ASCII.GetBytes(buildDate.PadRight(11, ' '));
        Array.Copy(dateBytes, 0, f, 1, Math.Min(11, dateBytes.Length));

        byte[] timeBytes = System.Text.Encoding.ASCII.GetBytes(buildTime.PadRight(8, ' '));
        Array.Copy(timeBytes, 0, f, 12, Math.Min(8, timeBytes.Length));

        SetU16(f, 20, fwType);
        SetU16(f, 22, swSeries);
        SetU32(f, 24, hwInfo);
        SetU32(f, 28, mainFw);
        SetU32(f, 44, sblFw);
        SetU32(f, 48, dspFw);

        return f;
    }

    public static byte[] CreateFeature22(
        byte rev = 0x02,
        uint hwVersion = 0x00000621,
        uint fwVersion = 0x0110002A,
        string mac = "DC:0C:2D:11:22:33"
    ) {
        byte[] f = new byte[64];
        f[0] = 0x22;
        f[1] = rev;
        SetU32(f, 3, hwVersion);
        SetU32(f, 7, fwVersion);
        SetMac(f, 17, mac);
        return f;
    }
}
'@
}

# 1. SelfTest execution branch
if ($SelfTest) {
    $exitCode = [DualSenseBridge]::RunSelfTest()
    exit $exitCode
}

# 2. Resolve ADB path (unless TestOnly, SelfTest, or NoAdb)
if (-not $TestOnly -and -not $SelfTest -and -not $NoAdb) {
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

        $f09 = New-Object byte[] 20
        $f09[0] = 0x09
        if ([DualSenseBridge]::HidD_GetFeature($handle, $f09, 20)) {
            $devMac = ("{0:X2}:{1:X2}:{2:X2}:{3:X2}:{4:X2}:{5:X2}" -f $f09[6], $f09[5], $f09[4], $f09[3], $f09[2], $f09[1])
            $hostMac = ("{0:X2}:{1:X2}:{2:X2}:{3:X2}:{4:X2}:{5:X2}" -f $f09[15], $f09[14], $f09[13], $f09[12], $f09[11], $f09[10])
            Write-Host "Feature 0x09 (Pairing) OK: Device MAC=$devMac, Host MAC=$hostMac" -ForegroundColor Cyan
        }

        $f20 = New-Object byte[] 64
        $f20[0] = 0x20
        if ([DualSenseBridge]::HidD_GetFeature($handle, $f20, 64)) {
            $buildDate = [System.Text.Encoding]::ASCII.GetString($f20, 1, 11).Trim([char]0, ' ')
            $buildTime = [System.Text.Encoding]::ASCII.GetString($f20, 12, 8).Trim([char]0, ' ')
            $hw = [BitConverter]::ToUInt32($f20, 24)
            $fw = [BitConverter]::ToUInt32($f20, 28)
            Write-Host ("Feature 0x20 (Firmware) OK: Build '{0} {1}', HW 0x{2:X8}, FW 0x{3:X8}" -f $buildDate, $buildTime, $hw, $fw) -ForegroundColor Cyan
        }

        $f22 = New-Object byte[] 64
        $f22[0] = 0x22
        if ([DualSenseBridge]::HidD_GetFeature($handle, $f22, 64)) {
            Write-Host ("Feature 0x22 (BtPatchInfo) OK: Rev=0x{0:X2}, HW=0x{1:X8}, FW=0x{2:X8}" -f $f22[1], [BitConverter]::ToUInt32($f22, 3), [BitConverter]::ToUInt32($f22, 7)) -ForegroundColor Cyan
        }

        $testOut = New-Object byte[] 48
        $testOut[0] = 0x02
        $testOut[1] = 0x0F
        $testOut[2] = 0x55
        $testOut[9] = 0x01
        $testOut[11] = 0x21
        $testOut[12] = 0xFC
        $testOut[13] = 0x03
        $testOut[14] = 0xFF
        $testOut[15] = 0xFF
        $testOut[16] = 0x03
        $testOut[22] = 0x21
        $testOut[23] = 0xFC
        $testOut[24] = 0x03
        $testOut[25] = 0xFF
        $testOut[26] = 0xFF
        $testOut[27] = 0x03
        $testOut[42] = 0x02
        $testOut[43] = 0x00
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
        Write-Host "Test output report (0x21 Feedback + Cyan LED + Player 1) sent ($transportLabel). Written: $written bytes." -ForegroundColor Green

        Start-Sleep -Milliseconds 150
        [Array]::Clear($testOut, 11, 22)
        $testOut[11] = 0x05
        $testOut[22] = 0x05
        if ($isBluetooth) {
            $btBuf = [DualSenseBridge]::WrapUsbOutputToBt78($testOut, 1, $false, $outReportLen)
            if (-not [DualSenseBridge]::WriteFile($handle, $btBuf, $outReportLen, [ref]$written, [IntPtr]::Zero)) {
                [DualSenseBridge]::HidD_SetOutputReport($handle, $btBuf, $outReportLen) | Out-Null
            }
        } else {
            if (-not [DualSenseBridge]::WriteFile($handle, $testOut, 48, [ref]$written, [IntPtr]::Zero)) {
                [DualSenseBridge]::HidD_SetOutputReport($handle, $testOut, 48) | Out-Null
            }
        }
        Write-Host "Trigger reset (0x05 Off) sent ($transportLabel)." -ForegroundColor Green
        $handle.Close()
        exit 0
    }

    if ($Emulate) {
        if (-not $NoAdb) {
            Write-Host "Configuring adb reverse tcp:$Port tcp:$Port on $DeviceSerial..." -ForegroundColor Yellow
            try {
                & $AdbPath -s $DeviceSerial reverse tcp:$Port tcp:$Port
                if ($LASTEXITCODE -eq 0) {
                    $reverseActive = $true
                } else {
                    Write-Host "Notice: ADB reverse failed (code $LASTEXITCODE). Proceeding with localhost listener." -ForegroundColor Gray
                }
            } catch {
                Write-Host "Notice: ADB unavailable. Proceeding with localhost listener." -ForegroundColor Gray
            }
        }

        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        $isEdge = ($ControllerType -eq "DualSenseEdge")
        $isBt = ($Transport -eq "Bluetooth")
        Write-Host ("DualSense Virtual Controller Server [{0} / {1}] listening on 127.0.0.1:{2} (Scenario: {3})..." -f $ControllerType, $Transport, $Port, $Scenario) -ForegroundColor Green

        $virtualCtrl = [VirtualDualSenseController]::new()
        $virtualCtrl.IsEdge = $isEdge
        $virtualCtrl.IsBluetooth = $isBt
        $virtualCtrl.ApplyScenario($Scenario, 0)

        while ($true) {
            if (-not $listener.Pending()) {
                Start-Sleep -Milliseconds 50
                continue
            }

            $client = $listener.AcceptTcpClient()
            $client.NoDelay = $true
            $stream = $client.GetStream()
            Write-Host "Client connected to virtual controller server." -ForegroundColor Green

            $writerThread = [DualSenseBridge]::StartVirtualUpstreamBridge($stream, $isBt, $ValidateProtocol, $isEdge)

            try {
                [DualSenseBridge]::StreamVirtualReports($stream, $virtualCtrl, $Scenario)
            } catch {
                Write-Host "Client disconnected from virtual controller server." -ForegroundColor Yellow
            } finally {
                try { $stream.Close() } catch {}
                try { $client.Close() } catch {}
            }
        }
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
