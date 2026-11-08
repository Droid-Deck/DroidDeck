# Steam Controller (2015 Model, "D0G") BLE Support

DroidDeck natively supports the original **2015 Steam Controller** (model 1001, codenamed `"D0G"`, BLE USB/HID ID `28de:1106`) over Bluetooth Low Energy alongside the 2025 Steam Controller (`"Triton"`, `28de:1303`).

---

## 1. User Setup & Pairing Guide

### Firmware Requirement (2018 BLE Firmware)
The original 2015 Steam Controller shipped with 2.4 GHz proprietary wireless firmware only. Valve released a Bluetooth Low Energy (BLE) firmware update in May 2018 (`5b02c125` / May 2018 BLE firmware).
- If your controller has never been updated to the BLE firmware, connect it via USB to a PC and flash the official BLE firmware using the community archive tool at **[steamcontroller.tools](https://steamcontroller.tools)** (or the archived Valve `ble.bat` firmware updater).

### Pairing & Switching Modes
The 2015 Steam Controller stores separate wireless pairings for its 2.4 GHz USB receiver and Bluetooth LE:
- **Start BLE Pairing Mode (`Steam + Y`)**: With the controller powered off, hold **`Y`** and press the **`Steam`** button. The Steam LED will blink rapidly and the controller will appear in Android Bluetooth settings as **`SteamController`**.
- **Boot in BLE Mode (`Steam + B`)**: If your controller was previously switched back to its 2.4 GHz dongle mode, hold **`B`** while pressing **`Steam`** to power it on in Bluetooth LE mode (a two-tone ascending chime confirms BLE mode).
- **Boot in 2.4 GHz Dongle Mode (`Steam + A`)**: Hold **`A`** while pressing **`Steam`** to switch back to the 2.4 GHz USB receiver.
- Once in BLE mode, simply pressing the **`Steam`** button powers the controller on in BLE mode and automatically connects to the last paired BLE host.

### BLE vs. 2.4 GHz Wireless Capabilities
| Feature | BLE Mode (`Steam + B` / `Steam + Y`) | 2.4 GHz Dongle (`Steam + A`) |
| :--- | :--- | :--- |
| **Buttons, Stick, Analog Triggers, Rear Grips (`LG`/`RG`)** | Full support | Full support |
| **Dual Trackpads (Touch + Coordinates + Click)** | Full 16-bit absolute coordinates | Full 16-bit absolute coordinates |
| **IMU (Gyroscope + Accelerometer + Orientation)** | Supported (enabled on demand by Steam Input) | Supported |
| **Haptics / Trackpad Pulse** | Supported (throttled to BLE GATT write throughput) | Full high-frequency audio haptics |
| **Battery Telemetry** | Voltage (mV) and percentage reported via Status Report `0x05` | Voltage and percentage reported |

---

## 2. GATT Architecture & Protocol Summary

Both the 2015 (`D0G`) and 2025 (`Triton`) controllers advertise the custom Valve BLE GATT service `100F6C32-1735-4313-B402-38567131E5F3`, but expose distinct characteristic layouts:

| Characteristic UUID | Model | Properties | Role |
| :--- | :--- | :--- | :--- |
| `100F6C33-1735-4313-B402-38567131E5F3` | **2015 (`ORIGINAL_2015`)** | `0x12` (`READ \| NOTIFY`) | Fragmented 19-byte BLE input/status reports (MTU 23) |
| `100F6C34-1735-4313-B402-38567131E5F3` | **2015 (`ORIGINAL_2015`)** | `0x0A` (`READ \| WRITE`) | Feature / command writes (mode switch, IMU toggle, haptics) |
| `100F6C7A-1735-4313-B402-38567131E5F3` | **2025 (`TRITON_2025`)** | `NOTIFY` | 45-byte full state report (`0x45`) |
| `100F6C7C-1735-4313-B402-38567131E5F3` | **2025 (`TRITON_2025`)** | `NOTIFY` | 45-byte simple state report (`0x47`) |

### Connection & Mode Switch Sequence (2015 Model)
1. **MTU Negotiation**: `requestMtu(517)` returns `mtu = 23` (`status = 0`) on the 2015 hardware. `SteamControllerBle` proceeds to service discovery regardless of negotiated MTU.
2. **Characteristic Detection**: Presence of `100F6C33` selects `SteamControllerModel.ORIGINAL_2015`.
3. **CCCD Subscription**: Writes `ENABLE_NOTIFICATION_VALUE` (`0x01, 0x00`) to `00002902-0000-1000-8000-00805F9B34FB` on `100F6C33`.
4. **Native ValveMode Handshake**: Immediately after `onDescriptorWrite` succeeds, writes `{0xC0, 0x87, 0x03, 0x08, 0x07, 0x00}` to `100F6C34` using `WRITE_TYPE_DEFAULT` and refreshes every 3 seconds (`LIZARD_REFRESH_MS`).
5. **Packet Reassembly & Input Translation**:
   - [`Sc2015PacketReassembler`](../../app/src/main/java/com/droiddeck/launcher/input/steamcontroller/Sc2015PacketReassembler.kt) reassembles multi-fragment BLE notifications (`0x80..0x8F` continuation, `0xC0..0xCF` final segment).
   - [`Sc2015ReportParser`](../../app/src/main/java/com/droiddeck/launcher/input/steamcontroller/Sc2015ReportParser.kt) decodes variable-chunk Report `0x04` (`k_EBLEReportState`) and battery telemetry Report `0x05` (`k_EBLEReportStatus`), adapting state and feature reports into the 45-byte `FakeInputWriter` ring buffer.
