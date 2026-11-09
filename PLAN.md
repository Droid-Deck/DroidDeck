# Implementation Plan: Native Bluetooth LE Support for Steam Controller (2015)

## Done (with commit hashes)
- `2dfc2c00`: Initial exploration pass of DroidDeck codebase and reference implementations (`HIDDeviceBLESteamController.java`, `hid-steam.c`, `SDL_hidapi_steam.c`, `controller_structs.h`, `SdlBridge`); produced `DESIGN.md` and initialized `PLAN.md`.
- `81c3ad85`: Recorded pair-programming architectural decisions in `DESIGN.md` and `PLAN.md`.
- `f961c5e9`: Implemented `com.droiddeck.launcher.input.steamcontroller` package (`SteamControllerModel`, `SteamControllerDebug`, `Sc2015PacketReassembler`, `Sc2015State`, `Sc2015ReportParser`) plus JVM unit tests (`Sc2015PacketReassemblerTest`, `Sc2015ReportParserTest`).
- `6ebe1702`: Integrated Steam Controller 2015 BLE detection, MTU-23 handling, ValveMode switch (`{0xC0, 0x87, 0x03, 0x08, 0x07, 0x00}`), `CONNECTION_PRIORITY_HIGH`, fragmented notification reassembly, Triton 45-byte state translation, and feature reply synthesis into `SteamControllerBle.kt`, with Robolectric unit tests in `SteamControllerBleTest.kt`.
- `50c3185f`: Recorded Phases 1–4 commits and `preloads.lock.json` native C++ lock constraint in `PLAN.md`.
- `d4622bf4`: Completed physical hardware verification on Steam Controller (2015 BLE `28de:1106`), gated hot-path debug logs behind lazy `SteamControllerDebug.log`, updated `DESIGN.md`, and added user/developer guide `docs/development/steam-controller-2015-ble.md`.

## In progress
- **Phase 6 — Upstream PR & Discussion #149 submission**:
  - Push branch and open upstream PR / post coordination update in GitHub Discussion #149 (`@xXJSONDeruloXx`).

## Next steps
- Follow up on maintainer feedback in Discussion #149 regarding whether to keep the zero-native-diff Triton 45-byte translation on `/dev/hidraw18` or add a dedicated `/dev/hidraw22` (`0005:28DE:1106`) node when `preloads.lock.json` is next regenerated.
- Optional follow-up: surface `SteamControllerBle.batteryPercent` in FusionHUD if desired by upstream.

## Open questions & Decisions
1. **Input Pipeline Representation (Option B vs Option A Constraint Discovered)**:
   - **Finding**: `app/src/main/cpp/fakeinput_steam.cpp` is SHA-256 pinned in `tools/components/preloads.lock.json` (`3d11e859...`), and `tools/prepare_windows_runtime.py` rejects local Windows APK builds (`ValueError: Unreviewed runtime/native/Rust changes: app/src/main/cpp/fakeinput_steam.cpp`) unless the Linux/x86/arm64 preload archives are rebuilt in Docker and re-locked.
   - **Resolution**: Activated **Option A** (translating `Sc2015State` in Kotlin into the 45-byte Triton report format on `/dev/hidraw18` and synthesizing feature replies in `Sc2015ReportParser.buildFeatureReply`), which requires zero modifications to `fakeinput_steam.cpp` and works directly with the prebuilt `libfakeinput.so`. Verified on physical hardware (buttons, dual trackpads with multi-touch coordinates/clicks, analog triggers, analog stick, rear grips `LG`/`RG`, IMU accel+gyro 2-fragment reassembly, and battery telemetry).
2. **Modularization Strategy**:
   - **Decision**: Implemented Option B (`com.droiddeck.launcher.input.steamcontroller.*` subpackage with isolated unit tests).
3. **Battery Exposure**:
   - **Decision**: Decoded from BLE Report 5 (`k_EBLEReportStatus`), exposed on `SteamControllerBle.batteryVoltageMv` / `batteryPercent`, and reported in `GET_ATTRIBUTES_VALUES` (`0x83` tag `0x0B`). Verified on physical hardware (`3432 mV`, `100%`).
4. **Connection Priority**:
   - **Decision**: Requests `BluetoothGatt.CONNECTION_PRIORITY_HIGH` upon entering ValveMode (`interval=12 latency=0` verified on Android 15).
5. **Haptics/Rumble**:
   - **Decision**: Non-feature output reports are suppressed for `ORIGINAL_2015` in `SteamControllerBle.sendOutputReport` to protect the voice-coil actuators and keep the PR focused on input streaming.
