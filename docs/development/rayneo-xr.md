# RayNeo Air / GT XR integration

Built on the current `feat/viture-xr` PR #732 at bdf876d47cc9bb6fa810d014370da5bbb3c28039.
The supplied historical hashes a127e66, 7f9dd75, e8e3e6b and f0b3297 were not
resolvable in the fetched branch; no native projection or synchronization files are changed.

## Discovery and transport

`Glasses.kt` owns USB permission, attachment/detachment and presentation-display
selection. There is no manifest USB VID filter: discovery uses `UsbManager.deviceList`.
VITURE discovery is retained. `RayNeoModel` accepts the scaffold's confirmed
`1bbb:af50`; other products under `1bbb` or bridge vendor `22d9` must identify
as RayNeo/TCL Air or GT in their USB product name. This covers named Air 2, 2s,
3, 4 Pro and GT devices without claiming arbitrary TCL phones or bridge chips.
Additional unnamed VID/PID pairs require captures before being allowlisted.
EDID/product display names containing RayNeo, Ray Neo, or TCL Air/GT use the existing
external-screen path. Android's supported modes, not a synthetic refresh value,
determine whether 120 Hz can be selected. Stereo modes may have a lower maximum rate.

`RayNeoLink` claims a paired IN/OUT interface and translates the controller's
internal commands to RayNeo packets. It requests device info (0x66/0), enables IMU
(0x66/1) and waits for acknowledgments, warms up for two seconds, and stops IMU
(0x66/2) when no pose consumer remains or the link closes. SBS uses commands 6/7.
Unsupported VITURE brightness, volume and electrochromic commands are never sent;
the VITURE hardware-control UI is hidden for RayNeo.

The parser/filter are adapted from the user's scaffold at
581dbf712ed601ece4cf5e4a7c1f52e780299c88. Sensor packets are framed with 0x99/0x65,
with little-endian acceleration at byte 4, gyro (degrees/sec) at 16 and a uint32
100us device tick at 40. Fragmented and coalesced reports are assembled before decoding.
Board 0x3a (Air 4 Pro) receives the scaffold's -20-degree X IMU mounting correction.

## Existing pose and SteamVR pipeline

The scaffold fuses gyro/accel into wxyz. The Android adapter converts its Y-up
coordinate basis and component order into VITURE's Z-up xyzw convention before
calling the same `Glasses.PoseSink` used by VITURE. `HeadTracker` and `HeadPose`
retain their existing recenter/seated-reference behavior. The path continues through
`WaylandCompositor.nativeSetCardboardPose`, the compositor's `headpose_shm.h`
shared-memory record and `tools/vr/src/driver_droiddeck.cpp`'s pose-file reader.
The OpenVR projection, presenter, Vulkan wait logic and vblank counters are untouched.

## Validation

Run `./gradlew -PskipRust=true :app:testDebugUnitTest --tests 'com.droiddeck.launcher.glasses.*'`.
Protocol tests cover malformed/fragmented packets, unsigned timestamps, model gating,
quaternion normalization, yaw basis and tick rollover. USB integration tests cover
permissions and stale pose callbacks. Hardware validation is still required per model:
USB enumeration, acknowledgment/stream startup, axes and recenter, hot unplug/replug,
SBS negotiation, Android's actual 120Hz mode, and SteamVR pose/frame pacing. GT
and alternate bridge firmware are candidate protocol-compatible devices, not
independently hardware-certified devices.

Attribution and the scaffold's Apache-2.0 license are in `docs/third-party/rayneo/`.
