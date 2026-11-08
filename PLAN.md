# Implementation Plan: Native Bluetooth LE Support for Steam Controller (2015)

## Done (with commit hashes)
- `2dfc2c00`: Initial exploration pass of DroidDeck codebase and reference implementations (`HIDDeviceBLESteamController.java`, `hid-steam.c`, `SDL_hidapi_steam.c`, `controller_structs.h`, `SdlBridge`).
- `2dfc2c00`: Produced architectural design document: `DESIGN.md`.
- `fd816b3c`: Updated `DESIGN.md` and `PLAN.md` with pair-programming architectural decisions.

## In progress
- **Phase 0 — Research & design**:
  - Validating design assumptions with maintainer / team partner.
  - Reviewing BLE GATT characteristics, fragment reassembly strategy, and input pipeline adaptation.

## Next steps
- **Phase 1 — BLE GATT connection layer**:
  - Scan/filter for the 2015 controller (`Steam Controller*` / `Steam Ctrl*`).
  - Connect, discover services and characteristics (detect `100F6C33` for 2015 vs `100F6C7A`/`7C` for 2025 Triton).
  - Handle MTU negotiation (fallback safely if MTU 23 is enforced).
  - Subscribe to CCCD on `100F6C33` with Android version-compatible descriptors.
  - Add debug logging with raw hex dumps behind `SC_DEBUG_LOG` flag.
  - Provide hardware test checklist for Phase 1.
- **Phase 2 — Mode switch**:
  - Send the ValveMode feature report `{0xC0, 0x87, 0x03, 0x08, 0x07, 0x00}` to `100F6C34`.
  - Verify exit from lizard mode.
  - Implement 3-second keep-alive refresh to prevent reversion to lizard mode.
  - Handle re-sending on reconnect.
  - Provide hardware test checklist for Phase 2.
- **Phase 3 — Input report parsing**:
  - Implement `Sc2015PacketReassembler` (handling `0x80..0x8F` and `0xC0..0xCF`).
  - Implement `Sc2015ReportParser` for state report ID 4 (`k_EBLEReportState` with chunk bitmask) and status report ID 5 (`k_EBLEReportStatus`).
  - Unit tests for packet reassembly and report decoding with synthetic binary fixtures.
  - Provide hardware test checklist for Phase 3.
- **Phase 4 — Integration with the input pipeline**:
  - Map decoded 2015 events into DroidDeck's controller abstraction (`FakeInputWriter` / `TritonInputRouter` / `DeckControls`).
  - Verify full mapping: face buttons (A/B/X/Y), bumpers, triggers (analog + click), stick, both trackpads (coordinates + touches + clicks), rear grips (LG/RG), and IMU/gyro.
  - Hardware test checklist for Phase 4.
- **Phase 5 — Robustness & UX**:
  - Reconnect resilience during session suspension / resume.
  - Battery level reporting from status packet (Report 5).
  - User documentation (BLE firmware requirement via steamcontroller.tools, pairing combo Steam+Y, BLE bandwidth vs 2.4GHz dongle limits).
- **Phase 6 — PR preparation**:
  - Codebase cleanup and unit test verification (`./gradlew testDebugUnitTest`).
  - Finalize documentation and PR description.
  - Draft coordination message for GitHub Discussion #149.

## Open questions & Decisions
1. **Input Pipeline Representation**:
   - **Decision:** Target Option B (native hidraw node `0005:28DE:1106` via `/dev/hidraw22`) as primary implementation; preserve Option A (Triton 45-byte ring buffer translation) as fallback.
2. **Modularization Strategy**:
   - **Decision:** Option B (subpackage `com.droiddeck.launcher.input.steamcontroller.*` with isolated protocol handlers and unit tests).
3. **Battery Exposure**:
   - **Decision:** Decode and log in debug during early phases; evaluate FusionHUD exposure in Phase 5.
4. **Connection Priority**:
   - **Decision:** Request `BluetoothGatt.CONNECTION_PRIORITY_HIGH` upon entering ValveMode.
5. **Haptics/Rumble**:
   - **Decision:** Option A (implement rumble in Phase 4/5 after inputs are proven stable).
