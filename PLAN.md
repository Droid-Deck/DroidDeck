# Implementation Plan: Native Bluetooth LE Support for Steam Controller (2015)

## Done (with commit hashes)
- `2dfc2c00`: Initial exploration pass of DroidDeck codebase and reference implementations (`HIDDeviceBLESteamController.java`, `hid-steam.c`, `SDL_hidapi_steam.c`, `controller_structs.h`, `SdlBridge`); produced `DESIGN.md` and initialized `PLAN.md`.
- `81c3ad85`: Recorded pair-programming architectural decisions in `DESIGN.md` and `PLAN.md`.
- `f961c5e9`: Implemented `com.droiddeck.launcher.input.steamcontroller` package (`SteamControllerModel`, `SteamControllerDebug`, `Sc2015PacketReassembler`, `Sc2015State`, `Sc2015ReportParser`) plus JVM unit tests (`Sc2015PacketReassemblerTest`, `Sc2015ReportParserTest`).
- `6ebe1702`: Integrated Steam Controller 2015 BLE detection, MTU-23 handling, ValveMode switch (`{0xC0, 0x87, 0x03, 0x08, 0x07, 0x00}`), `CONNECTION_PRIORITY_HIGH`, fragmented notification reassembly, Triton 45-byte state translation, feature reply synthesis, and haptic pulse translation into `SteamControllerBle.kt`, with Robolectric unit tests in `SteamControllerBleTest.kt`.

## In progress
- **Hardware verification (Phases 1–4)**:
  - Testing BLE connection, service discovery, ValveMode switch, and full input/trackpad/gyro/grip/haptic pipeline on a physical Steam Controller (2015) with `adb logcat`.

## Next steps
- **Phase 5 — Robustness & UX**:
  - Verify reconnect resilience across session suspend/resume on physical hardware.
  - Evaluate exposing `SteamControllerBle.batteryPercent` / `batteryVoltageMv` in FusionHUD if desired by upstream.
  - Add user-facing documentation (2018 BLE firmware requirement via `steamcontroller.tools`, `Steam+Y` pairing combo, BLE vs 2.4GHz dongle limits).
- **Phase 6 — PR preparation**:
  - Final cleanup and consolidation based on hardware test logs.
  - Draft coordination message for GitHub Discussion #149 (`@xXJSONDeruloXx`) and upstream PR description.

## Open questions & Decisions
1. **Input Pipeline Representation (Option B vs Option A Constraint Discovered)**:
   - **Finding**: `app/src/main/cpp/fakeinput_steam.cpp` is SHA-256 pinned in `tools/components/preloads.lock.json` (`3d11e859...`), and `tools/prepare_windows_runtime.py` rejects local Windows APK builds (`ValueError: Unreviewed runtime/native/Rust changes: app/src/main/cpp/fakeinput_steam.cpp`) unless the Linux/x86/arm64 preload archives are rebuilt in Docker and re-locked.
   - **Resolution**: Activated **Option A** (translating `Sc2015State` in Kotlin into the 45-byte Triton report format on `/dev/hidraw18` and synthesizing feature replies in `Sc2015ReportParser.buildFeatureReply`), which requires zero modifications to `fakeinput_steam.cpp` and works directly with the prebuilt `libfakeinput.so` in local Windows builds. We will ask `@xXJSONDeruloXx` in Discussion #149 whether he prefers keeping this zero-native-diff Option A or adding a dedicated `/dev/hidraw22` (`0005:28DE:1106`) node when he regenerates `preloads.lock.json`.
2. **Modularization Strategy**:
   - **Decision**: Implemented Option B (`com.droiddeck.launcher.input.steamcontroller.*` subpackage with isolated unit tests).
3. **Battery Exposure**:
   - **Decision**: Decoded from BLE Report 5 (`k_EBLEReportStatus`), logged in debug, exposed on `SteamControllerBle.batteryVoltageMv` / `batteryPercent`, and reported in `GET_ATTRIBUTES_VALUES` (`0x83` tag `0x0B`).
4. **Connection Priority**:
   - **Decision**: Requests `BluetoothGatt.CONNECTION_PRIORITY_HIGH` upon entering ValveMode.
5. **Haptics/Rumble**:
   - **Decision**: Translates Triton haptic/rumble output reports (`0x80`–`0x85`) into 10-byte 2015 BLE haptic pulse packets (`0x8F`) on `100F6C34` with 25ms rate limiting.
