# Steam Controller (2015) BLE Integration Design

## 1. Overview & Objectives

DroidDeck currently supports the unreleased Steam Controller (2025 model, codenamed "Triton") over Bluetooth Low Energy (BLE), but does not natively support the original Steam Controller (2015 model, codenamed "D0G"). When paired over BLE, the 2015 controller remains in "lizard mode" (fallback HID keyboard/mouse emulation) with no access to trackpads, gyro, analog triggers, or Steam Input integration.

This document outlines the architecture for adding native BLE support for the original Steam Controller (2015 model) in DroidDeck, designed to complement upstream development (tracking Discussion #149 with maintainer @xXJSONDeruloXx) and prepare a clean, upstreamable pull request.

---

## 2. Architectural Context & Repo Analysis

### 2.1 Existing Controller Infrastructure
- **`SteamControllerBle.kt` (`com.droiddeck.launcher.input`)**:
  - Connects to paired BLE devices named `Steam Controller*` or `Steam Ctrl*`.
  - Discovers custom Valve service `100F6C32-1735-4313-B402-38567131E5F3`.
  - Discovers Triton-specific input report characteristics: `INPUT_0X47` (`100F6C7C-...`) or `INPUT_0X45` (`100F6C7A-...`).
  - Expects 45-byte fixed state reports.
  - Sends Triton output/feature reports to dynamic characteristics `100f6cXX-...` (`0x80..0x85`).
  - Writes reports to `FakeInputWriter.writeTritonState(SLOT, reportId, data)`.
- **`FakeInputWriter.java`**:
  - Manages mmap ring buffer communicating with the Linux guest via POSIX shared memory.
  - Exposes `TRITON_OFFSET` block for Triton controller state (`writeTritonState`, `readTritonOutput`, `readTritonFeatureRequest`).
  - Exposes `DECK_IMU_BLOCK` for extra controls (pads, grips, IMU).
- **`SteamDeckPad.kt`**:
  - Generates guest sysfs nodes: `/dev/hidraw16` (Deck), `/dev/hidraw17` (Touch), `/dev/hidraw18` (Triton BLE: `0005:28DE:1303`), `/dev/hidraw19` (Triton Wired: `0003:28DE:1302`).
- **`fakeinput_steam.cpp`**:
  - Injected C++ library intercepting hidraw ioctls and reads/writes in the PRoot container.
  - Spawns `triton_report_thread` which reads `TritonSharedData` from the mmap buffer and streams reports to Steam via `/dev/hidraw18`.
- **`InputRouter.kt` / `TritonInputRouter.kt` / `DeckControls.kt`**:
  - Unified input routing engine. Translates physical controls into gamepad axes/buttons, desktop keyboard/mouse events, or macro bindings.

### 2.2 Key Differences: 2015 Model vs 2025 Model (Triton)

| Dimension | Steam Controller (2015 Model) | Steam Controller (2025 Model / Triton) |
|---|---|---|
| **USB/BLE Vendor & Product ID** | `28de:1106` (`D0G_BLE2_PID`) | `28de:1303` (BLE) / `28de:1302` (Wired) |
| **GATT Service UUID** | `100F6C32-1735-4313-B402-38567131E5F3` | `100F6C32-1735-4313-B402-38567131E5F3` |
| **Input Characteristic** | `100F6C33-1735-4313-B402-38567131E5F3` | `100F6C7A` (0x45) or `100F6C7C` (0x47) |
| **Report/Command Characteristic**| `100F6C34-1735-4313-B402-38567131E5F3` | `100F6C34` + Output characteristics `0x80..0x85` |
| **MTU / Packet Size** | Fixed at 23 bytes (BLE ATT payload <= 20 bytes) | Negotiates up to 517 bytes (payload 45 bytes) |
| **Packet Framing / Chunking** | Header byte with upper nibble `0x8` (more) / `0xC` (last) + seq `0..F` | Direct unfragmented frames |
| **Mode Switch Command** | `{0xC0, 0x87, 0x03, 0x08, 0x07, 0x00}` to `100F6C34` | `{0x87, 0x03, 0x09}` to `100F6C34` |
| **Input Report Format** | Variable-length chunked bitmask (`k_EBLEReportState` = 4) | Fixed 45-byte Triton report |
| **Physical Controls** | Dual trackpads, single analog stick, 2 rear grips (LG/RG), gyro | Dual trackpads, dual analog sticks, 4 rear grips, gyro |

---

## 3. Proposed Module Structure

```
com.droiddeck.launcher.input/
 ├── SteamControllerBle.kt               (Refactored BLE connection manager & dispatcher)
 ├── steamcontroller/
 │    ├── SteamControllerModel.kt        (Enum: TRITON_2025, ORIGINAL_2015)
 │    ├── SteamControllerProtocol.kt     (Interface for model-specific protocol handlers)
 │    ├── TritonProtocolHandler.kt       (Extracted 2025 Triton BLE protocol handler)
 │    ├── Sc2015ProtocolHandler.kt       (New: 2015 Steam Controller BLE protocol handler)
 │    ├── Sc2015PacketReassembler.kt     (New: BLE fragment reassembly buffer)
 │    ├── Sc2015ReportParser.kt          (New: Decodes Report 4 state & Report 5 status)
 │    └── Sc2015State.kt                 (Data classes: Buttons, Triggers, Stick, Pads, IMU)
 └── TritonInputRouter.kt                (InputRouter adapter for controller profiles)
```

### Class Diagram & Data Flow

```
                      +-----------------------------+
                      |     BluetoothGattCallback   |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |     SteamControllerBle      |  (Runs on HandlerThread)
                      +--------------+--------------+
                                     |
             +-----------------------+-----------------------+
             | Service Discovery                             | Service Discovery
             | finds 100F6C7A / 100F6C7C                     | finds 100F6C33
             v                                               v
+---------------------------+                 +-----------------------------+
|   TritonProtocolHandler   |                 |     Sc2015ProtocolHandler   |
+-------------+-------------+                 +--------------+--------------+
              |                                              |
              | 45-byte native frames                        | MTU 23 ATT Notifications
              |                                              v
              |                               +-----------------------------+
              |                               |    Sc2015PacketReassembler  |
              |                               |  (0x80..0x8F, 0xC0..0xCF)   |
              |                               +--------------+--------------+
              |                                              |
              |                                              | Complete Packet (e.g. 19-32 bytes)
              |                                              v
              |                               +-----------------------------+
              |                               |      Sc2015ReportParser     |
              |                               +--------------+--------------+
              |                                              |
              |                                              | Sc2015State (Typed)
              |                                              v
              |                               +-----------------------------+
              |                               |  Sc2015 -> Triton Adapter   |
              |                               | (Maps SC 2015 to 45B format)|
              |                               +--------------+--------------+
              |                                              |
              +----------------------+-----------------------+
                                     |
                                     v
                      +-----------------------------+
                      |      TritonInputRouter      | (Applies user profiles / remaps)
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |   FakeInputWriter (Java)    |
                      |   writeTritonState(slot)    |
                      +--------------+--------------+
                                     |
                                     v (POSIX Shared Memory /dev/shm)
                      +-----------------------------+
                      |    fakeinput_steam.cpp      |
                      |    (/dev/hidraw18)          |
                      +--------------+--------------+
                                     |
                                     v
                      +-----------------------------+
                      |      Steam Big Picture      |
                      +-----------------------------+
```

---

## 4. Reuse vs New-Code Decisions

### 4.1 Reused Components (No Breaking Changes)
1. **Bonded Device Detection & Scanning (`SteamControllerBle`)**:
   - `isSteamController()` already matches names starting with `Steam Controller` and `SteamController`. Both 2015 and 2025 controllers advertise this name prefix.
2. **Threading & Lifecycle Infrastructure**:
   - `HandlerThread("steam-controller-ble")` single-threaded Looper architecture ensures all GATT callbacks, writes, and reads run strictly serialized.
   - Suspension handling (`SessionState.suspended`) and exponential backoff retry.
3. **Session Integration**:
   - `SessionService.kt` (`wantsTriton = wantsDeck && SteamControllerBle.hasPairedController(this)`) reuses the existing activation path.
   - `SessionActivity.kt` reuses `SteamControllerBle.listener` to refresh UI motion and on-screen controls when connected.
4. **Input Abstraction & Pipeline (`TritonInputRouter` & `FakeInputWriter`)**:
   - By adapting the decoded SC 2015 inputs into the 45-byte Triton state structure, we reuse 100% of `FakeInputWriter`, `TritonInputRouter`, `fakeinput_steam.cpp` (`/dev/hidraw18`), and the guest sysfs description (`SteamDeckPad.kt`).
   - Every physical control on the 2015 Steam Controller maps cleanly into the Triton state structure:
     - Buttons: A, B, X, Y, LB, RB, LT Click, RT Click, Select (Back), Start, Steam (Guide), L3 (Stick Click), Left Grip (LG), Right Grip (RG), Left Pad Click, Right Pad Click.
     - Touches: Left Pad Touch (`STEAM_LEFTPAD_FINGERDOWN_MASK`), Right Pad Touch (`STEAM_RIGHTPAD_FINGERDOWN_MASK`).
     - Triggers: Analog LT, RT (0..32767).
     - Stick: Analog X, Y (-32768..32767).
     - Pads: Left Pad X, Y; Right Pad X, Y.
     - IMU: Accel (X, Y, Z), Gyro (X, Y, Z), Quaternions (W, X, Y, Z).

### 4.2 New Components
1. **Model Detection**:
   - In `subscribe()`, inspect discovered characteristics under `100F6C32-1735-4313-B402-38567131E5F3`.
   - If `100F6C33` is present, instantiate `Sc2015ProtocolHandler`.
   - If `100F6C7A` or `100F6C7C` is present, instantiate `TritonProtocolHandler`.
2. **De-framing / Packet Reassembler (`Sc2015PacketReassembler`)**:
   - Buffers fragmented notifications:
     - `0x80..0x8F`: Packet start/continue with sequence index.
     - `0xC0..0xCF`: Final packet with sequence index.
   - Resets state on sequence mismatch or unexpected framing byte.
3. **Mode Switch Engine**:
   - Writes `{0xC0, 0x87, 0x03, 0x08, 0x07, 0x00}` to `100F6C34` once CCCD notification on `100F6C33` is confirmed.
   - Periodic keep-alive / refresh every 3 seconds to guarantee the controller stays out of lizard mode.
4. **Report Parser (`Sc2015ReportParser`)**:
   - Parses `k_EBLEReportState` (Report ID 4) with variable chunk bitmask:
     - `k_EBLEButtonChunk1` (0x10): 3 bytes buttons.
     - `k_EBLEButtonChunk2` (0x20): 2 bytes triggers (analog).
     - `k_EBLEButtonChunk3` (0x40): 3 bytes buttons.
     - `k_EBLELeftJoystickChunk` (0x80): 4 bytes (stick X, Y).
     - `k_EBLELeftTrackpadChunk` (0x100): 4 bytes (pad X, Y).
     - `k_EBLERightTrackpadChunk` (0x200): 4 bytes (pad X, Y).
     - `k_EBLEIMUAccelChunk` (0x400): 6 bytes accel.
     - `k_EBLEIMUGyroChunk` (0x800): 6 bytes gyro.
     - `k_EBLEIMUQuatChunk` (0x1000): 8 bytes quat.
   - Parses `k_EBLEReportStatus` (Report ID 5):
     - Battery voltage (mV) and battery percentage (0..100).
5. **Debug Instrumentation**:
   - Gated behind a debug flag (`SC_DEBUG_LOG`): dumps raw incoming GATT hex packets, reassembly milestones, mode switch responses, and parsed state.

---

## 5. Lifecycle State Machine

```
[Disconnected / Idle]
        │
        ▼ (hasPairedController == true)
[Connect GATT (TRANSPORT_LE)]
        │
        ▼ (STATE_CONNECTED)
[Discover Services]
        │
        ▼ (Services Discovered)
[Inspect Characteristics under 100F6C32-...]
   ├── Contains 100F6C33 ──────────► [Model: SC 2015]
   └── Contains 100F6C7A/7C ───────► [Model: SC 2025 Triton]
        │
        ▼
[Subscribe CCCD Notification on Input Characteristic]
        │
        ▼ (onDescriptorWrite SUCCESS)
[Send Mode Switch Report to 100F6C34]
   SC 2015: {0xC0, 0x87, 0x03, 0x08, 0x07, 0x00}
   Triton:  {0x87, 0x03, 0x09, ...}
        │
        ▼ (onCharacteristicWrite SUCCESS / Lizard Off)
[Active Streaming Loop]
   ├── Incoming Notification -> Sc2015PacketReassembler -> Sc2015ReportParser
   │       └── Typed State -> Adapted Triton 45B -> TritonInputRouter -> FakeInputWriter
   ├── Keep-Alive Timer: Refresh Lizard Mode every 3s
   └── Output Polling: Poll haptic/rumble requests -> Send to 100F6C34
        │
        ▼ (Link Lost / Disconnect / Suspend)
[Clean Disconnect]
   ├── Clear queues, cancel keep-alive timer
   ├── Notify FakeInputWriter (connected = false)
   └── Schedule connect() retry after RETRY_MS (5s)
```

---

## 6. Risks, Trade-offs & Platform Constraints

1. **Android BluetoothGatt API Level Differences**:
   - Android 9 to 11: Requires `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION` for BLE scanning.
   - Android 12+ (API 31+): Requires runtime `BLUETOOTH_SCAN` (with `neverForLocation` attribute) and `BLUETOOTH_CONNECT`.
   - `onCharacteristicChanged` signature changed in API 33 (deprecated `(gatt, characteristic)` replaced with `(gatt, characteristic, value)`). DroidDeck already implements both with `@Suppress("DEPRECATION")`. We will maintain this dual implementation.
2. **GATT Callback Threading**:
   - Android invokes `BluetoothGattCallback` methods on an internal Binder IPC thread pool.
   - Any state mutation or GATT operation (e.g. `writeCharacteristic`) directly within the callback without thread synchronization causes race conditions or drops. All callback invocations must immediately post onto the dedicated `HandlerThread` Looper.
3. **Packet Loss over BLE & Reassembler Sync**:
   - Because BLE notifications are unacknowledged at the application layer, dropped packets could leave the reassembler in an invalid state.
   - The reassembler must validate the 4-bit sequence counter (`0..F`) and reset its buffer immediately if a sequence discontinuity is detected.
4. **MTU Renegotiation Behavior**:
   - The 2015 controller firmware locks MTU at 23 bytes. If `requestMtu(517)` fails or returns 23, the connection must seamlessly proceed without disconnecting.
5. **Battery & Latency Trade-offs**:
   - Calling `gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)` reduces BLE connection intervals (to ~11.25–15 ms) for low input latency. We should evaluate requesting high priority upon successful ValveMode entry.
6. **Hardware Limitation Documentation**:
   - End users must have Valve's official 2018 BLE firmware flashed (via WebHID on steamcontroller.tools) and pair using `Steam + Y`.
   - BLE mode has lower polling rate (~100 Hz max) compared to the 2.4 GHz USB dongle (~500 Hz).

---

## 7. Open Questions for Maintainer (@xXJSONDeruloXx)

1. **Input Pipeline Representation**:
   - *Question:* Should the 2015 Steam Controller feed into DroidDeck's existing Triton 45-byte ring buffer (`writeTritonState` / `/dev/hidraw18`), or would you prefer a separate virtual hidraw node (e.g. `/dev/hidraw22` exposing `28de:1106`) in `SteamDeckPad.kt` and `fakeinput_steam.cpp`?
   - *Recommendation:* Adapting to Triton's 45-byte format allows complete reuse of the existing guest hidraw shim and input routing with minimal diff and zero risk of breaking existing Linux container bindings.
2. **Architecture Modularization**:
   - *Question:* Do you prefer keeping both models in a unified `SteamControllerBle.kt` file, or extracting model protocol handlers into a `steamcontroller/` subpackage?
   - *Recommendation:* Extracting protocol handlers (`Sc2015ProtocolHandler`, `TritonProtocolHandler`) keeps `SteamControllerBle.kt` concise and focused strictly on Android GATT connection lifecycle and threading.
3. **Battery Reporting**:
   - *Question:* SC 2015 sends battery status packets (`k_EBLEReportStatus`, report ID 5) with voltage (mV) and battery percentage. Should this battery level be exposed to DroidDeck's HUD / battery indicator, or ignored?
4. **Connection Priority**:
   - *Question:* Should we invoke `requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)` upon entering ValveMode?
5. **Haptics & Rumble**:
   - *Question:* The 2015 Steam Controller supports basic haptic rumble commands over BLE via `100F6C34`. Should we wire DroidDeck's virtual gamepad rumble to SC 2015 haptics in this PR, or defer haptic rumble to a subsequent follow-up PR?
