# Smart-glasses support

This describes the integrations implemented in DroidDeck as of 2026-11-13. A recognized product name alone does not establish that every firmware, phone, cable, or adapter supports every mode. Controls become available from the connected device's capabilities and the display modes Android actually exposes.

**3DoF** tracks head rotation: turn, tilt, and roll around a virtual screen. **6DoF** also tracks head position, so leaning or moving changes the view. Both apply to DroidDeck menus, non-Steam launchers, desktop sessions, and games when the tracked virtual screen is enabled. Direct SteamVR rendering uses the corresponding headset pose instead of the virtual-screen layout.

## Support levels

- **Integrated:** a device transport and controls are implemented. Tracking requires a working connection and available orientation data.
- **SDK-dependent:** integrated support requires the VITURE SDK imported through glasses settings. APKs do not include the proprietary SDK. Recognizing the display does not mean that SDK is installed.
- **Experimental:** implemented and opt-in, with the qualifications described below. This is not a general tracking-accuracy guarantee.
- **Display-only:** Android can provide a picture through a compatible display connection, but DroidDeck has no supported control/tracking connection for that device.

## Device matrix

| Brand / model family | Level and connection | App-wide 3DoF | App-wide 6DoF / SteamVR position | Luma stereo hand / Joy-Con position |
| --- | --- | --- | --- | --- |
| VITURE One / One Lite; Pro / Pro XR | Integrated legacy USB transport | Yes, when IMU connection is available | No | No |
| VITURE Luma / Luma Pro; Luma Cyber when identified by the SDK | Integrated Luma-family USB transport; Cyber identification uses the SDK market name | Yes, when IMU connection is available | No | No |
| VITURE Pro 2 / Pro 2 XR | Experimental USB orientation; SDK enables Gen2 device controls | Yes | No | No |
| VITURE Beast / Phantom Beast | SDK-dependent Gen2 transport and validated market name | Yes | No | No |
| **VITURE Luma Ultra** | SDK-dependent Carina transport | Yes | **Experimental, opt-in** | **Experimental, opt-in** |
| Recognized RayNeo / TCL Air-series devices | Integrated RayNeo USB transport | Yes, with orientation calibration controls | No | No |
| RayNeo GT-series devices matching the supported USB identity, including GT Max identification | Integrated GT USB/IMU path | Yes, with orientation calibration controls | No | No |
| **XREAL 1S** | **Experimental**; exact USB identity plus the device's USB-Ethernet control/IMU connection | Yes, when that connection is ready | No | No |
| **XREAL 1 Pro+** | **Experimental**; exact USB identity plus the device's USB-Ethernet control/IMU connection | Implemented; wearer movement validation pending | No | No |
| Rokid Max / Max 2; Rokid Air under the Max USB identity | Integrated Rokid USB transport (vendor control requests and HID IMU reports) | Yes | No | No |
| Other XREAL models; unrecognized glasses; display adapters and docks | Display-only where Android exposes a usable display; automatic glasses routing also requires display recognition | No supported app tracking connection | No | No |

RayNeo's Air-series recognition uses its known USB identity or a RayNeo/TCL Air name under the accepted vendor IDs; it is not a per-model certification list. The GT vendor path requires the specific supported product ID. Rokid recognition requires USB 04d2:162f (Max/Air) or 04d2:2002 (Max 2), plus a Rokid product name, since that vendor ID is shared with unrelated devices. Max 2 reports the USB product name "Rokid Max2". Hardware checks cover the Max and Max 2; the Air follows the same published protocol untested. XREAL support targets **1S (`3318:043e`) and 1 Pro+ (`3318:0444`)** through a direct driver without an XREAL SDK. The original One Pro is a different model; One, original One Pro, Air-family devices, and recovery-mode products remain excluded. VITURE Pro 2 uses its known USB identity for experimental orientation and the SDK's validated model for device controls. A dock does not supply the connected glasses' sensors by itself.

The SDK 2.4.2 hardware checks covered Luma Ultra, Phantom Beast and regular Beast on one phone. The wearer confirmed correct movement on Luma Ultra, correct directions and horizon on Phantom Beast, and all three rotation axes on regular Beast (USB product ID `0x1201`). Beast host tracking disables built-in tracking before using the SDK pose stream. Variant `0x1211` retains its previous axis mapping and has not been verified in this session. XREAL 1S remains an existing experimental integration and was not hardware-validated in this SDK import work; the matrix does not claim that every listed model has been tested.

The final column refers specifically to Luma Ultra's stereo tracking cameras. DroidDeck's separate phone-camera and physical-controller features are independent of this matrix.

## Display and device controls

All display modes depend on the phone's video output, the cable/adapter, and Android's advertised modes. USB permission or a working sensor connection alone does not create a video display.

| Integration | Display selection | Device controls exposed by DroidDeck |
| --- | --- | --- |
| VITURE One | 1080p per eye; 2D and SBS at 60 Hz only | Brightness 0–6, volume 0–7, dimming toggle |
| VITURE One Lite | 1080p per eye; 2D and SBS at 60 Hz only | Brightness 0–6, volume 0–7; no electrochromic film |
| VITURE Pro / Pro XR | 1080p per eye; 2D at 60/90/120 Hz, SBS at 60 Hz | Brightness and volume 0–8, dimming toggle |
| VITURE Luma / Luma Pro / Luma Cyber | 1080p or 1200p per eye; 2D at 60/90/120 Hz, SBS at 60 Hz | Brightness and volume 0–8, dimming toggle |
| VITURE Luma Ultra | 1080p or 1200p per eye; 2D at 60/90/120 Hz, SBS at 60/90 Hz | Brightness and volume 0–8, dimming toggle; SDK exposure and tracking recovery |
| VITURE Beast / Phantom Beast | Native: 2D at 60/120 Hz, SBS and ultrawide at 60 Hz. Bypass: 2D at 60 Hz, SBS at 90 Hz. Both support 1080p and 1200p per eye; ultrawide is one wide image, not an eye pair | Brightness 0–8, volume 0–15, tint levels 0–8; built-in fixed/smooth-follow tracking, recenter, screen size, distance and side mode |
| VITURE Pro 2 | 1080p per eye; 2D at 60/90/120 Hz, SBS at 60 Hz | SDK brightness and volume 0–8; no electrochromic film |
| RayNeo Air/GT | 2D refresh choices follow the connected display's advertised modes for the current resolution; SBS at 60 Hz | Orientation calibration; no app brightness, volume, or dimming commands |
| XREAL 1S (experimental) | Existing 1S display-mode commands, including native 1200p/60 Hz and SBS | Brightness and dimming toggle; no app volume command |
| XREAL 1 Pro+ (experimental) | Native 1080p/60 Hz and full SBS 3840×1080/60 Hz verified; 90/120 Hz commands accepted, physical refresh validation pending | Brightness 0–9 as a conservative protocol subset; dimming toggle; no app volume command |
| Rokid Max / Max 2 | 1080p at 60 or 120 Hz in 2D; SBS at 1080p/60, 1200p/60, or 1200p/90 | Brightness; volume follows Android's USB audio volume; no dimming film |
| Rokid Air | 1080p/60 in 2D and SBS | Brightness; volume follows Android's USB audio volume |

The VITURE ranges above are model defaults. Imported SDKs that provide maximum-value queries supply the connected device's actual brightness, volume and tint limits; older SDKs retain the supported model defaults. Lite and Pro 2 never expose tint controls. One/Lite's 60 Hz limit follows their [product-specific hardware specifications](https://www.viture.com/academy/xr-glasses/pro-one-series), rather than the SDK's combined One/Lite/Pro enum table. Beast's native and bypass selections follow the SDK's [per-mode support tables](https://www.viture.com/developer/glasses-sdk/glasses), which are narrower than the list of defined constants.

XREAL 1 Pro+ uses the official [57° diagonal field of view](https://tutorials.xreal.com/docs/glasses/pro-plus/specifications/). XREAL documents [14 physical brightness levels in Follow mode with Stabilizer off](https://tutorials.xreal.com/docs/glasses/pro-plus/display-sound/); their mapping to the direct control protocol is not yet verified, so DroidDeck keeps the known 0–9 subset. Brightness, dimming, physical 90/120 Hz output, and wearer movement still need Pro+ validation. XREAL Eye, camera capture, and 6DoF are not implemented. See the [dated hardware findings](development/xreal-investigation.md#native-1-pro-implementation-and-hardware-validation--2026-11-13).

Default diagonal field of view is 43° for One/Lite, 46° for Pro, 50° for Pro 2 and base Luma, 52° for Luma Pro/Cyber/Ultra, and 58° for Beast/Phantom Beast. Luma Pro/Cyber optics are selected from an explicit USB product name or the SDK market name. When the USB name is absent, known Luma Pro product IDs use the Pro optics; unidentified Luma-family variants retain the existing fallback until identified. See [Luma specifications](https://www.viture.com/academy/xr-glasses/luma-series) and [Pro 2 specifications](https://www.viture.com/academy/xr-glasses/pro-2). RayNeo's 59° GT Max profile requires an explicit GT Max name or its verified GT board identity `0x41`; standard GT board `0x40` selects 46°, and unknown variants keep their fallback.

DroidDeck saves separate **2D resolution**, **3D resolution**, and **VR resolution** preferences. Unsupported saved choices fall back to the connected model's automatic behavior. Native **30 Hz** is offered only when Android advertises a matching mode and glasses presentation output is enabled; Beast/Phantom SDK display modes do not offer 30 Hz. Higher refresh selections are constrained by the active 2D/SBS layout and model. In particular, Rokid Max/Max 2 offer 90 Hz SBS only at 1200p, not 1080p; RayNeo refresh choices follow the connected display rather than a fixed brand-wide list. A firmware-rejected switch is not treated as successful until the physical display confirms it.

In **Automatic** picture mode, ordinary flat content is duplicated in full into both eyes on an SBS display. **Virtual screen** adds a configurable screen plane and tracking. **Side-by-side game** is for content that already contains separate eye images. SteamVR uses its direct stereo view and field-of-view control.

With **game on glasses** the compositor draws the guest pointer into its own scene as the last blended draw and mirrors it into both eyes for a side-by-side picture, while the phone stops drawing its Compose cursor. Over the app's own UI — the drawer, the PC keyboard, the achievements panel, PiP controls and the phone panel shown while the glasses have the picture — Android's pointer returns instead, so those controls are still clickable with a mouse.

The pointer is left out of the picture when it cannot be drawn where the game is: while a game holds the pointer lock (it draws its own), and for a game the compositor cannot import — an AHardwareBuffer-only surface, or HDR frames it has no texture for. Those games stay on their own display layer, because composing the scene would drop the game itself. An importable HDR game that would otherwise stay on its own layer is composed through the screen swapchain, so there the pointer does fit.

## Setup and app-wide behavior

1. Connect the glasses through a compatible video/data connection. Accept USB access when requested. For XREAL 1S and 1 Pro+, Android must also expose the glasses' USB-Ethernet network; the app leaves Steam's Internet connection on its normal network. Select **Follow** in the glasses' own menu and turn **Stabilizer off** before enabling DroidDeck tracking.
2. Open **Setup → Smart glasses**. For VITURE glasses, choose **Get SDK from VITURE** on the **Display** tab. On the developer page, download **VITURE XR Glasses SDK for Android (2.4.x)** and leave the ZIP intact. Return to DroidDeck, choose **Import VITURE SDK**, select that ZIP, and confirm after validation. This enables supported SDK features without rebuilding the app. Enable glasses output and **3DoF virtual screen** to select the tracked screen without forcing SBS or changing brightness.
3. Adjust screen width and distance, then recenter while looking where the screen should sit. The same settings apply when navigating DroidDeck, opening a non-Steam launcher, or entering a game/desktop session.
4. On an SDK-enabled Luma Ultra, optionally enable positional tracking and set the floor from the current eye height. Use the tracking setup and recovery controls for calibration or recentering. SteamVR startup defers Luma positional initialization until two seconds after SteamVR reports running.

Beast and Phantom Beast can handle fixed-screen or smooth-follow head tracking on the glasses. Their **Advanced** tab exposes native mode and built-in tracking after SDK import. DroidDeck pauses its own head tracking while built-in tracking is active and restores the user's host-tracking preference when it is turned off. SteamVR uses host headset poses and temporarily suspends built-in tracking to avoid applying movement twice. Importing the SDK alone does not change these device modes.

The phone remains the touch/control surface. DroidDeck mirrors its own visible UI into a tracked glasses presentation; it does not capture Android's entire desktop or other apps. Android 29+ can include app dialogs/popups, while Android 26–28 captures the activity's decor only. System keyboards and permission prompts, secure windows, and separately composed `SurfaceView` content stay on their existing surfaces. Menu recentering does not change the VR floor origin. See [tracked-menu behavior](development/glasses-menu-tracking.md).

## Luma Ultra tracking qualifications

Positional tracking, bare-hand controllers, and camera-assisted Joy-Cons are separate options and default off. Bare hands and camera-assisted Joy-Cons are mutually exclusive.

- **Head tracking:** the SDK supplies the positional pose. The app checks freshness and connection/origin generations and provides tracking-loss and recenter recovery. Screen and floor origins are separate.
- **Bare hands:** Full palm + Full landmarks is the default. Raw grayscale is replicated into RGB, MediaPipe applies model tensor normalization, and calibration corrects landmark coordinates before stereo triangulation. Full landmarks support palm orientation, pinch, and grab. The small saved-image comparison favored Full over Lite but does not establish universal hand-detection quality; see the [comparison results](development/hand-model-comparison-2026-10-16.md).
- **Controller visibility:** the last accepted visual pose can remain for up to 350 ms from capture time during a short detection gap. Invalid/stale gestures release independently. Normal hand mode has no stationary dead zone; shoulder-based **Steady hand aim** is a separate opt-in setting.
- **Joy-Cons:** camera position uses wrist landmark 0. Physical buttons and IMU orientation remain the input sources, with an arm estimate when a usable camera position is unavailable. Camera wrist position does not make a physical controller fully optically tracked in every pose. Screen and Joy-Con recenter actions are separate.
- **Calibration:** the included 640×480 Luma Ultra stereo profile is a model-specific starting point. A matching saved device profile takes precedence. The shared profile is labeled separately from an individually distance-verified calibration; optional board collection and distance validation refine a device profile.
- **RGB camera:** a live preview is available from the Device tab. This camera is not used for controller depth or hand tracking. RGB hand tracking remains a [separate implementation plan](development/viture-rgb-hand-tracking-plan.md), including the RGB-only path for regular Beast; Phantom Beast has no camera. The failed hybrid stereo matcher and temporary USB transport experiments are not part of the live pipeline.

## Build and troubleshooting references

Luma Ultra and Beast control/tracking, and Pro 2 device controls, require the externally supplied **VITURE Android SDK 2.4.x**. Import it from glasses settings; builds cannot bundle it. SDK 2.4.1 or newer adds Phantom Beast identification and queried control ranges. The Advanced tab exposes supported device features, including native screen controls and Luma Ultra exposure/recovery. The Device tab includes RGB preview only for models with a camera. Without an import, DroidDeck retains its legacy VITURE, experimental Pro 2 orientation, and other brands' transports. Vendor SDK headers and binaries are not stored in this repository. See [VITURE SDK setup](development/viture-sdk.md).

If only a flat picture is available, check video output first, then the control connection. A disabled tracking toggle can mean missing SDK support, missing USB/network access, or a device without a supported IMU transport. SBS or a new resolution requires a confirmed physical display mode; selecting a preference cannot add a mode the phone/display does not expose.

- [Luma spatial setup, calibration, and diagnostics](development/luma-spatial-tracking.md)
- [Tracked DroidDeck menu implementation](development/glasses-menu-tracking.md)
- [Agent diagnostics and capture commands](agent-control.md)
- [RayNeo attribution and protocol references](third-party/rayneo/README.md)

The capability definitions are maintained in [VITURE models](../app/src/main/java/com/droiddeck/launcher/glasses/VitureProtocol.kt), [RayNeo models](../app/src/main/java/com/droiddeck/launcher/glasses/RayNeoModel.kt), [XREAL models](../app/src/main/java/com/droiddeck/launcher/glasses/XrealModel.kt), [Rokid](../app/src/main/java/com/droiddeck/launcher/glasses/RokidModel.kt), and [connection capability routing](../app/src/main/java/com/droiddeck/launcher/glasses/Glasses.kt). Update this matrix when those integrations change.
