# VITURE Android SDK integration

For the cross-brand feature matrix and user setup, see [smart-glasses support](../smart-glasses-support.md).

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
SteamVR pipeline. Ultra selects 3DoF or the opt-in positional 6DoF mode before SDK
initialization. Its positional poses and stereo cameras feed the
[Luma spatial pipeline](luma-spatial-tracking.md); camera passthrough is not implemented.
The SDK's optional statistics reporter is not enabled.

The SDK owns USB interface selection and I/O. `UsbDeviceConnection` remains open
until stop/shutdown/destroy finish. A generation token rejects callbacks from an
old connection. Device brightness, volume and dimming events update the app.
Beast native and standard display codes have separate meanings; they are mapped
explicitly. Host tracking suspends native DOF to prevent applying rotation twice
and restores the previous native DOF on stop. Older firmware rejecting a bypass
mode can fall back to the native table, with rollback on failure.

`Game on glasses` uses Android's existing Presentation output. USB control alone
does not create a video display: the phone, cable and adapter must expose a usable
external display. Enabling 3D also enables and saves `Game on glasses` before
requesting the stereo mode, so the picture uses the glasses' presentation instead
of the mirrored phone output. Returning to 2D keeps the game on the glasses.
Discovery checks both display name and product metadata, with
a single unnamed-display fallback when recognized glasses USB is present.

Screen width and distance apply to **Virtual screen** rendering. In **Automatic**,
ordinary 2D games stay flat. On an SBS display, the complete flat frame is fitted
into each eye without cropping or head tracking. A running SteamVR session uses
the direct stereo view. Games or videos that already contain separate eye images
use the explicit **Side-by-side game** picture setting. Switching SBS on does not
activate a virtual screen or head tracking for ordinary games.
The direct view uses SteamVR field of view instead of virtual-screen dimensions
while SteamVR is running. A plain side-by-side game fills each eye and does not
use the SteamVR field-of-view or recenter controls.

All supported glasses with an available head-tracking connection have a separate
**3DoF virtual screen** toggle, including VITURE, RayNeo and XREAL 1S. Availability
comes from the connected transport's IMU capability rather than a model whitelist.
The app renders this screen without enabling a built-in 3DoF mode or changing
brightness. Enabling it selects Virtual screen, head tracking and Game on glasses,
and saves those preferences without changing the glasses' 2D/SBS mode. It works
with flat games in either display mode. Disabling it returns the picture to Automatic; switching
SBS on or off preserves the 3DoF selection. Virtual screens center on the current
viewing direction when tracking starts or the user recenters, including the
up/down angle. Direct SteamVR views retain their gravity-aligned recentering.

The settings UI, menu presentation, and session renderer share this mode selection
through `GlassesRendering`. Setup → Smart glasses configures DroidDeck menus,
non-Steam launchers, desktop sessions, and games. Tracked menus use the app's own
glasses presentation while phone controls remain available; see
[tracked menus](glasses-menu-tracking.md). Regular refresh rate applies to 2D
output; SBS uses the model's separate 3D refresh rate.
Both the home menu and session drawer use **Display**, **VR**, **Tracking**, and
**Device** tabs. Display groups connection and output controls, 2D/3D resolution
and active refresh rate, then picture and virtual-screen geometry. VR contains
SteamVR controls, automatic 3D, VR resolution, and field of view. Tracking groups
head orientation, position and floor, and hands with camera setup. Device contains
brightness, volume, and sunblock. Unsupported hardware tabs are hidden. Recenter
is the first action on every session tab while head tracking is enabled for a
3DoF/6DoF virtual screen or SteamVR.

Display saves **2D resolution** and **3D resolution**; VR saves **VR resolution**. Values are per eye: 1920 x 1080 or, on tall VITURE
models, 1920 x 1200. Automatic keeps the existing model default; VR defaults to
following the active 2D/3D preference. Home settings expose all three profiles,
while a running session shows the active profile next to its refresh control.
Switching resolution retains the refresh preference and does not change brightness,
color processing, or the session's game-rendering resolution. Android selects an
advertised mode with matching dimensions and the closest available refresh rate,
without crossing the 2D/SBS layout. VITURE's USB command selects the matching
resolution family first. A saved resolution unsupported by another attached model
falls back to its automatic behavior. Resolution controls wait for a pending SBS
transition to finish before accepting another change.

Both lists offer 30 Hz only when Android advertises a matching native display mode at the current
resolution and **Game on glasses** is enabled. Android's presentation selects that mode without a
vendor command. A saved 30 Hz choice falls back to 60 Hz if the display or eye layout lacks it;
mirrored output never substitutes a 30 fps rendering limit.
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
