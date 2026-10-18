# XREAL 1S / One Pro feasibility — 2026-10-14

The user owns XREAL 1S and also wants One Pro support. Supporting these in DroidDeck
is feasible based on existing open drivers, but the supplied Monado Air driver is
not their transport. The initial investigation below preceded implementation.
An experimental native 1S driver is now enabled; see the hardware validation
section at the end for what has actually been verified.

## Supplied Monado source

Inspected `C:/Users/conno/Downloads/monado-main/monado-main/src/xrt/drivers/xreal_air/`
and `src/xrt/targets/common/target_builder_xreal_air.c`.

| Model | USB vendor:product | Sensor/control interface | Sensor report |
| --- | --- | --- | --- |
| Air | 3318:0424 | 3 / 4 | 64 bytes |
| Air 2 | 3318:0428 | 3 / 4 | 64 bytes |
| Air 2 Pro | 3318:0432 | 3 / 4 | 64 bytes |
| Air 2 Ultra | 3318:0426 | 2 / 0 | 512 bytes |

This driver implements HID IMU input, segmented factory-calibration JSON reads,
gravity/gyro fusion, brightness 0–7 and 2D/3D mode commands. It does not register
1S or One Pro. Copying those Air HID commands to the new products is unjustified.
Its source carries Tobias Frisch's BSL-1.0 notice.

## Applicable One-family references

* [XRLinuxDriver](https://github.com/wheaney/XRLinuxDriver), inspected commit
  `36e2139dd482f5984112d0c3ebafcc07a78f99c0`. `src/devices/xreal.c` lists vendor
  `3318`, One Pro products `0435/0436`, and 1S products `043d/043e`. These use the
  non-HID transport. Its README explicitly lists both models and requires current
  firmware with glasses stabilization/anchor disabled when using external tracking.
* [xrealOneDeviceKit](https://github.com/wheaney/xrealOneDeviceKit), inspected commit
  `f5833f611265f418b1ea549da20dc4764d86a4ec`. Its IMU adapter consumes the One TCP
  driver, interprets gyro as radians/sec and acceleration as m/s², then feeds fusion.
  Its factory-calibration JSON hook returns unsupported. The maintained Linux
  integration disables its SBS-control capability when no MCU connection is open;
  a static model table alone is not proof of working display switching.
* [xreal_one_driver](https://github.com/rohitsangwan01/xreal_one_driver), MIT,
  inspected commit `3e912315ec16e94e9a47688770d649cfef293624`. Opens TCP
  `169.254.2.1:52998` with a two-second timeout, assembles 84-byte IMU records,
  reads a timestamp at byte 14, gyro floats at 34 and acceleration floats at 46.
  This is a transport/parser reference, not a drop-in Android glasses controller.

The source check confirms a practical path for both requested models. It does not
establish that this Samsung phone exposes the glasses' USB Ethernet interface to
ordinary Android apps, or establish the physical tracking axes on this hardware.

## DroidDeck integration path

1. Attach the 1S and inspect its USB descriptors, presentation display identity,
   Ethernet network/link properties and whether the IMU endpoint is reachable.
   Obtain the same evidence for One Pro before claiming hardware validation.
2. Add exact model discovery and external-display selection. Preserve each panel's
   Android-advertised dimensions and modes; do not assume the Air's resolution.
3. Implement a bounded One-family stream parser and cancellable socket transport.
   On Android, bind the socket to the relevant Ethernet `Network` when available,
   leaving Steam's Internet route alone. If Android does not expose that link,
   a USB network transport is additional work; no root requirement is assumed.
4. Establish gyro units, timestamp deltas and axis mapping using stationary,
   directed yaw, pitch and roll captures. Route the validated fused pose through
   the same glasses/HeadTracker/SteamVR pipeline as VITURE and RayNeo. Avoid layering
   DroidDeck tracking on top of the glasses' own anchor/stabilization.
5. Gate brightness and automatic stereo switching separately on a verified control
   protocol. IMU streaming alone does not justify showing those controls as working.
6. Test malformed/fragmented/coalesced records, missing network, timeout/reconnect,
   USB detach, screen handoff, SteamVR launch and each physical movement direction.

At this initial investigation stage, no external SDK was installed, firmware was
modified, or XREAL commands were sent.

## Further source investigation while the phone charges

Inspected [xr-tools](https://github.com/0xcaff/xr-tools), commit
`65c11105434a998f09ac4a5f8e4de8736bb4d36c`, particularly
`packages/xreal_one_driver/src/proto/net/`, `src/proto/usb/mod.rs`, and the IMU
example. This describes considerably more of the One protocol than the simple
TCP IMU reader above:

* Reports use TCP `169.254.2.1:52998`; controls use `169.254.2.1:52999`.
  Both have a two-byte message identifier followed by a four-byte **big-endian**
  body length. Control messages additionally contain a big-endian transaction ID
  and protobuf payload. A port must bound lengths, use unique transaction IDs,
  enforce deadlines, and close both sockets on detach.
* Sensor messages `28 36` have a **128-byte body**, hence 134 bytes including the
  header. The older reader's 84-byte extraction is not the complete frame format.
  Body offsets: device ID 0, device timestamp 8, report type 24, gyro 28,
  acceleration 40, magnetometer 52, temperature 64 and IMU ID 68. Numeric sensor
  fields are little-endian; timestamp is documented as nanoseconds, gyro rad/s,
  acceleration m/s². Type 11 is IMU; type 4 is magnetometer. Do not interpret every
  report as gyro data or use arrival time as the device's integration interval.
* Control `27 1f` reads factory JSON: IMU bias, temperature-dependent gyro bias,
  and display-to-IMU transforms. Its example subtracts the factory biases, fuses
  gyro/acceleration and applies the display rotation. This is preferable to
  guessing a fixed mounting angle, but the example's frame convention still
  needs conversion and directed-motion verification in DroidDeck.
* `27 1c` sets brightness 0–9; `27 27` sets dimming; `28 22` selects regular/SBS
  input; `27 5e/5f` reads/sets display configuration; `28 a6/a7` reads/sets EIS.
  Documented display configurations are 1920×1080 at 60/90/120 Hz and 3840×1080
  at 60 Hz. These are evidence for One/One Pro, not confirmed 1S modes. Preserve
  the Android-advertised modes and verify controls separately on 1S firmware.
* `28 29` is labelled buttons-enabled/buttons-disabled scene mode. Do not assume
  that command disables stabilization; the separate EIS control exists.
* This project's USB detection labels `3318:0436` One Pro and `3318:0438` One as
  normal devices, and `0435/0437` as **recovery** devices. XRLinuxDriver accepting
  both IDs does not justify opening recovery products as ordinary glasses or
  sending firmware commands. xr-tools does not register 1S; its applicability
  there is a protocol-family inference, not hardware validation.

No license file or Cargo license declaration was found in this inspected xr-tools
revision. No implementation was copied into DroidDeck. Use protocol observations
for an independently written adapter, or resolve source licensing before a port.
The MIT TCP driver and GPL XRLinuxDriver remain explicitly licensed references.

Also inspected XRLinuxDriver commit
[`05d0cfb`](https://github.com/wheaney/XRLinuxDriver/commit/05d0cfb86abaacbf073e1a285ad4eabf681ccb05).
It moves per-device pitch adjustment into the core pose path, after coordinate
conversion; it does not add an Android transport or new One control protocol.
Its pitch constants should not replace the GT mapping already verified by the user.

Next hardware check remains read-only: enumerate the 1S USB interfaces, display
and Ethernet link; attempt bounded reads from both TCP services, save the factory
configuration, then capture stationary/yaw/pitch/roll data. No XREAL support is
advertised in this build. Android Ethernet availability and 1S control compatibility
are still unverified.

## Official XREAL documentation and SDK inspection

Checked the user's [official documentation](https://docs.xreal.com/) on
2026-10-14. This establishes an additional integration candidate alongside the
direct TCP driver; it does not make that candidate a drop-in Android controller.

* The [device page](https://docs.xreal.com/XREALDevices/XREAL%20Glasses) explicitly
  includes One, One Pro and 1S in the X1-based One family with rotational tracking.
* [SDK 3.1.0 release notes](https://docs.xreal.com/Release%20Note/XREAL%20SDK%203.1.0)
  add positional tracking for the One series with XREAL Eye and updated firmware.
  Beam Pro and Samsung S25 are its listed tested hosts. This does not establish
  6DoF on a camera-less 1S or validation on the user's SM-F976U.
* [ControlGlasses](https://docs.xreal.com/Tools/ControlGlasses) is a required server
  for official SDK apps on compatible Android phones. It supplies device services,
  3D switching and dynamically loaded algorithm libraries. Beam Pro uses MyGlasses
  instead. An SDK integration would have a companion-app/version dependency.
* The [quickstart](https://docs.xreal.com/Getting%20Started%20with%20XREAL%20SDK)
  describes Unity XR Plugin integration, ARM64 and OpenGL ES3. The
  [dual-display guide](https://docs.xreal.com/Tools/Dual%20Screen%20Display)
  confirms separate phone/glasses views and continued glasses output while the
  phone runs another app. This demonstrates the desired behavior in the SDK;
  it does not itself supply a native Kotlin implementation.
* The [compatibility page](https://docs.xreal.com/XREALDevices/Compatibility)
  currently flags unresolved Android 16 compatibility. The
  [FAQ](https://docs.xreal.com/Frequently%20Asked%20Questions) also says DeX can
  interfere with SDK display operation, and Android 16 USB permission may require
  camera permission first when the device exposes an enabled UVC interface. Apply
  that permission requirement only when relevant; ordinary tracking must not
  start camera capture or enable UVC automatically.

Downloaded, but did not install or bundle, the official release archive linked
from the 3.1.0 release notes:
`https://public-resource.xreal.com/download/XREALSDK_Release_3.1.0.20251125/com.xreal.xr.tar.gz`.
SHA-256: `fd7d0fce103712e7acc85bfcbb8b127007e1d84f64596efb6c11580bea058d90`.
Size: 248,169,074 bytes.

Package inspection found Android AARs including `nr_api` and `nr_loader`, ARM64
`libXREALXRPlugin.so` / `libXREALNativeSessionManager.so`, and Java
`com.xreal.sdk.display.GlassesDisplay` plus display/surface listeners. The C# layer
calls native exports for tracking type, resolution, refresh rate, component poses
and session lifecycle; the loader also integrates Unity subsystems. Presence of
these libraries does not establish a supported standalone JNI API or permission
to redistribute a stripped subset. No C headers were present in the archive file
listing inspected. No SDK source or binaries were added to the app.

Decision: retain both candidates. Before choosing the official native-library
route, establish non-Unity initialization, head-pose access, ControlGlasses
compatibility, display-surface ownership and distribution terms. Before choosing
direct TCP, establish the Android network link and 1S protocol behavior. Compare
both against DroidDeck's existing compositor instead of replacing its rendering
architecture merely to fit the SDK's Unity sample.

## Native 1S implementation and hardware validation

The pre-implementation checkpoint is `4b045519`. The new driver uses independently
written Kotlin TCP framing, control transactions, sensor parsing and gyro/gravity
fusion. It does not bundle XREAL SDK binaries or copy the unlicensed xr-tools
source. Protocol observations came from the references above and
[xr-tools](https://github.com/0xcaff/xr-tools), inspected commit
`65c11105434a998f09ac4a5f8e4de8736bb4d36c`.

Verified on the connected SM-F976U (Android reports release 17):

* Normal 1S USB identity is `3318:043e`, product name `XREAL 1S`. Only that exact
  product enables this driver; Air, One Pro and alternate/recovery IDs are excluded.
* Android exposes NCM/ECM Ethernet networks at `169.254.2.10/24` and
  `169.254.1.10/24`. Individual sockets bound to the first network reach the
  glasses at `169.254.2.1`, control port 52999 and report port 52998. The process
  default network remains unchanged, preserving Steam's Internet connectivity.
* Requests require the high bit of the transaction ID; responses clear that bit.
  Replies must match both command and transaction before values are published.
* GET_DISPLAY returned payload `22 02 10 1d`: mode **29**, with Android reporting
  **1920 x 1200 at 60 Hz**. This differs from the older One's mode 2 (1080 lines).
  The 1S default 60 Hz mapping preserves mode 29.
* A two-second capture delivered **2,000 valid IMU poses (1,000 Hz)**. Reports
  use the 128-byte body, type 11, little-endian device nanoseconds and float
  radians/sec / m/s² format. The framing handles partial and coalesced TCP reads.

The driver plugs into the shared glasses display and head-pose paths, including
SteamVR. The FOV calculation now uses the actual per-eye aspect ratio, including
the 1S's 16:10 display. Controls use acknowledged commands, bounded queues and
timeouts; connection failures clear tracking state and retry. Volume is hidden
because no volume protocol has been established. Missing Ethernet is reported
without requesting HID permission or claiming USB interfaces from Android.

Directed wearer captures on the same device established the 1S sensor basis:
level acceleration is approximately `(0, -9.65, -1.07)` m/s²; turning left gives
negative gyro Y; looking up gives positive gyro X; tilting toward the right
shoulder gives positive gyro Z with negative acceleration X. Both vectors are
mapped as `(x, -y, -z)` into the shared right/up/back basis before initial gravity
alignment and fusion. No RayNeo GT mounting angle is reused. Regression tests
exercise those measured signs through `HeadPose`, check an upright first pose
before recenter, and preserve the device's radians/sec and nanosecond units.

Stereo switching was verified at 3840 x 1080 / 60 Hz, returning to native
1920 x 1200. On 1S, SET_INPUT performs the EDID switch itself; immediately sending
SET_DISPLAY returns busy status 5001 (`22 03 08 89 27`). The driver now polls
GET_DISPLAY across the temporary empty reply (`22 00`) and only sends a second
mode command if the settled mode differs. Hardware retesting kept the connection
and sensor stream running through the switch. The busy status is never accepted
as a successful acknowledgement.

After installing the corrected build on 2026-10-14, the wearer confirmed that
the view and motion feel good in response to the upright-view, yaw, pitch and
head-tilt check. The SteamVR-requested session was running with a rendered first
frame, stereo output and active tracking. All 181 glasses, shared-pose, SteamVR
launch and glasses-UI regression checks passed; packaged runtime inputs verified.

The wearer also confirmed on 2026-10-14 that XREAL 1S brightness control works.
Higher refresh rates were not tested.

Still experimental: dimmer, 90/120 Hz compatibility modes and
extended gameplay stability need hardware validation. One successful view/motion
check does not establish long-session stability. Users must select Follow mode and disable the glasses' own
stabilization. Factory calibration and XREAL Eye/6DoF are not implemented.
One Pro is not enabled or claimed as tested.
