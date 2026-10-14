# VITURE Android SDK integration

The optional adapter is built against the user-supplied VITURE XR Glasses SDK
2.4.0. Set `droiddeck.vitureSdk=C:/path/to/VITURE_XR_Glasses_SDK_for_Android`
in the ignored `local.properties`, or pass `-PvitureSdkDir=/path/to/sdk`.
The directory must contain `include/` and `android/arm64-v8a/`.

Gradle compiles `droiddeck_viture`, packages `libglasses`, `libcarina_vio`, and
`libcloud_protocol`, and includes the SDK's LICENSE and NOTICE under
`assets/licenses/viture`. Vendor headers and binaries stay outside the repository.
A build without the optional SDK still supports the existing legacy VITURE and
RayNeo transports, but does not advertise control for SDK-dependent glasses.

## Supported app functions

| Device | Transport and tracking | App controls |
| --- | --- | --- |
| Pro 2 XR | SDK Gen2 pose callbacks, observed OpenGL axes converted to NWU; negotiated pose frequency | 1080p 2D through 120 Hz, SBS 60 Hz, brightness 0–8, volume 0–8 |
| Luma Ultra | SDK Carina USB transport; OpenGL poses converted to NWU | 1200p 2D through 120 Hz, SBS 60/90 Hz, brightness, volume, binary dimming |
| Beast | SDK Gen2; native/bypass display commands selected by capability and current mode | 1200p output, SBS, brightness, volume 0–15, dimming toggle |

Pro 2 identification uses the SDK's PID validation and market name, not a guessed
USB ID. Pro 2 has no electrochromic film control. Its display profile uses the
[official 50° FOV and 1080p/SBS limits](https://www.viture.com/academy/product/viture-pro-2-xr-glasses).
Device firmware can reject individual modes; Android's physical display size
must confirm SBS before the app considers the switch successful.

All three feed the existing `Glasses.PoseSink`, head recentering, compositor and
SteamVR pipeline. Ultra explicitly selects 3DoF before SDK initialization because
the current pipeline consumes orientation. Positional 6DoF and camera passthrough
are outside this integration. The SDK's optional statistics reporter is not enabled.

The SDK owns USB interface selection and I/O. `UsbDeviceConnection` remains open
until stop/shutdown/destroy finish. A generation token rejects callbacks from an
old connection. Device brightness, volume and dimming events update the app.
Beast native and standard display codes have separate meanings; they are mapped
explicitly. Host tracking suspends native DOF to prevent applying rotation twice
and restores the previous native DOF on stop. Older firmware rejecting a bypass
mode can fall back to the native table, with rollback on failure.

`Game on glasses` uses Android's existing Presentation output. USB control alone
does not create a video display: the phone, cable and adapter must expose a usable
external display. Discovery checks both display name and product metadata, with
a single unnamed-display fallback when recognized glasses USB is present.

Screen width and distance apply to **Virtual screen** rendering. In **Automatic**,
ordinary 2D games stay flat, ordinary games with a confirmed SBS display use a
virtual screen, and a running SteamVR session uses the direct stereo view.
The direct view uses SteamVR field of view instead of virtual-screen dimensions
while SteamVR is running. A plain side-by-side game fills each eye and does not
use the SteamVR field-of-view or recenter controls.
The settings UI and session renderer share this mode selection through
`GlassesRendering`; home-screen changes configure sessions rather than resizing
Android's mirrored launcher. Regular refresh rate applies to 2D output; SBS uses
the model's separate 3D refresh rate.
Software picture controls remain available while a display is connected even if
USB control is lost; hardware controls and head tracking still require USB.
Presentation refresh preferences are revalidated on display changes even when
the requested Hz is unchanged, and keep both dimensions of the active mode.

## Validation

Run `:app:externalNativeBuildDebug` and
`:app:testDebugUnitTest --tests 'com.droiddeck.launcher.glasses.*'` with the SDK
configured. Compile `app/src/test/cpp/viture_display_modes_test.cpp` with the NDK
C++17 compiler and the SDK include directory to check native/standard mappings
against the vendor constants. The Kotlin tests cover model capabilities, axis
conversion, invalid poses, rejected tracking starts and stale callbacks.

Physical acceptance checks remain: connect each model, grant USB permission,
verify the Play SteamVR control, verify game output moves to the glasses,
test head rotation/recenter and SBS, then unplug/reconnect. Building and unit
testing do not establish hardware compatibility for every firmware revision.

### Pro 2 pose-axis correction, 2026-10-14

The connected Pro 2 delivered approximately 200 normalized poses/sec. The wearer
reported yaw moving the view vertically, pitch rolling it, and roll turning it
horizontally. Directed captures showed a left turn primarily increasing quaternion
Y (approximately 0.004 to 0.253), while repeated upward looks varied quaternion X
(approximately -0.187 to +0.082) with Y/Z remaining near zero. This is the OpenGL
right/up/back basis, despite the SDK 2.4.0 generic Gen2 callback documentation
describing NWU.

The adapter therefore selects the existing OpenGL-to-shared-glasses conversion
for the SDK-identified Pro 2 family as well as Carina. Other Gen2 models retain
their previous mapping. Tests exercise model selection and yaw/pitch/roll through
the complete shared `HeadPose` conversion and recenter path. This fixes a model
coordinate mismatch; it does not claim to run a vendor sensor-calibration command
or save a RayNeo-style calibration for VITURE. All 188 glasses/shared-pose/
SteamVR-launch/UI regression checks passed, packaged runtime inputs verified,
and the corrected build was installed on the phone. Visual verification is pending.
