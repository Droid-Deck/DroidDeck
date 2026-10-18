# Smart-glasses support

This describes the integrations implemented in DroidDeck as of 2026-10-17. A recognized product name alone does not establish that every firmware, phone, cable, or adapter supports every mode. Controls become available from the connected device's capabilities and the display modes Android actually exposes.

**3DoF** tracks head rotation: turn, tilt, and roll around a virtual screen. **6DoF** also tracks head position, so leaning or moving changes the view. Both apply to DroidDeck menus, non-Steam launchers, desktop sessions, and games when the tracked virtual screen is enabled. Direct SteamVR rendering uses the corresponding headset pose instead of the virtual-screen layout.

## Support levels

- **Integrated:** a device transport and controls are implemented. Tracking requires a working connection and available orientation data.
- **SDK-dependent:** integrated support requires an APK built with the optional VITURE SDK. Recognizing the display does not mean that SDK is included.
- **Experimental:** implemented and opt-in, with the qualifications described below. This is not a general tracking-accuracy guarantee.
- **Display-only:** Android can provide a picture through a compatible display connection, but DroidDeck has no supported control/tracking connection for that device.

## Device matrix

| Brand / model family | Level and connection | App-wide 3DoF | App-wide 6DoF / SteamVR position | Luma stereo hand / Joy-Con position |
| --- | --- | --- | --- | --- |
| VITURE One / One Lite; Pro / Pro XR | Integrated legacy USB transport | Yes, when IMU connection is available | No | No |
| VITURE Luma / Luma Pro; Luma Cyber when identified by the SDK | Integrated Luma-family USB transport; Cyber identification uses the SDK market name | Yes, when IMU connection is available | No | No |
| VITURE Pro 2 / Pro 2 XR | SDK-dependent Gen2 transport and device identification | Yes | No | No |
| VITURE Beast | SDK-dependent Gen2 transport | Yes | No | No |
| **VITURE Luma Ultra** | SDK-dependent Carina transport | Yes | **Experimental, opt-in** | **Experimental, opt-in** |
| Recognized RayNeo / TCL Air-series devices | Integrated RayNeo USB transport | Yes, with orientation calibration controls | No | No |
| RayNeo GT-series devices matching the supported USB identity, including GT Max identification | Integrated GT USB/IMU path | Yes, with orientation calibration controls | No | No |
| **XREAL 1S** | **Experimental**; exact USB identity plus the device's USB-Ethernet control/IMU connection | Yes, when that connection is ready | No | No |
| Other XREAL models; unrecognized glasses; display adapters and docks | Display-only where Android exposes a usable display; automatic glasses routing also requires display recognition | No supported app tracking connection | No | No |

RayNeo's Air-series recognition uses its known USB identity or a RayNeo/TCL Air name under the accepted vendor IDs; it is not a per-model certification list. The GT vendor path requires the specific supported product ID. XREAL support currently targets **1S only**: One, One Pro, Air-family devices, and recovery-mode products are not enabled by the 1S transport. VITURE Pro 2 is identified through SDK validation rather than a guessed product ID. A dock does not supply the connected glasses' sensors by itself.

The final column refers specifically to Luma Ultra's stereo tracking cameras. DroidDeck's separate phone-camera and physical-controller features are independent of this matrix.

## Display and device controls

All display modes depend on the phone's video output, the cable/adapter, and Android's advertised modes. USB permission or a working sensor connection alone does not create a video display.

| Integration | Display selection | Device controls exposed by DroidDeck |
| --- | --- | --- |
| VITURE One/Lite and Pro families | 1080p per eye; 2D and SBS | Brightness, volume, dimming toggle according to the family profile |
| VITURE Luma family | 1080p or 1200p per eye; 2D and SBS | Brightness, volume, dimming toggle |
| VITURE Luma Ultra | 1080p or 1200p per eye; 2D and SBS, including the family's 90 Hz SBS selection | Brightness, volume, dimming toggle |
| VITURE Beast | 1080p or 1200p per eye; SDK handles native/bypass display modes | Brightness, volume, dimming toggle |
| VITURE Pro 2 | 1080p per eye; 2D and SBS | Brightness and volume; no electrochromic-film control |
| RayNeo Air/GT | 2D/SBS switching; resolution and refresh follow available Android modes | Orientation calibration; no app brightness, volume, or dimming commands |
| XREAL 1S | Supported 1S display-mode commands, including native 1200p/60 Hz and SBS | Brightness and dimming toggle; no app volume command |

DroidDeck saves separate **2D resolution**, **3D resolution**, and **VR resolution** preferences. Unsupported saved choices fall back to the connected model's automatic behavior. Native **30 Hz** is offered only when Android advertises a matching mode and glasses presentation output is enabled. Higher refresh selections are constrained by the active 2D/SBS layout and model; a firmware-rejected switch is not treated as successful until the physical display confirms it.

In **Automatic** picture mode, ordinary flat content is duplicated in full into both eyes on an SBS display. **Virtual screen** adds a configurable screen plane and tracking. **Side-by-side game** is for content that already contains separate eye images. SteamVR uses its direct stereo view and field-of-view control.

## Setup and app-wide behavior

1. Connect the glasses through a compatible video/data connection. Accept USB access when requested. For XREAL 1S, Android must also expose the glasses' USB-Ethernet network; the app leaves Steam's Internet connection on its normal network.
2. Open **Setup → Smart glasses**. Enable glasses output and **3DoF virtual screen**. This selects the tracked screen and saves the setting without forcing SBS or changing brightness.
3. Adjust screen width and distance, then recenter while looking where the screen should sit. The same settings apply when navigating DroidDeck, opening a non-Steam launcher, or entering a game/desktop session.
4. On an SDK-enabled Luma Ultra, optionally enable positional tracking and set the floor from the current eye height. Use the tracking setup and recovery controls for calibration or recentering. SteamVR startup defers Luma positional initialization until two seconds after SteamVR reports running.

The phone remains the touch/control surface. DroidDeck mirrors its own visible UI into a tracked glasses presentation; it does not capture Android's entire desktop or other apps. Android 29+ can include app dialogs/popups, while Android 26–28 captures the activity's decor only. System keyboards and permission prompts, secure windows, and separately composed `SurfaceView` content stay on their existing surfaces. Menu recentering does not change the VR floor origin. See [tracked-menu behavior](development/glasses-menu-tracking.md).

## Luma Ultra tracking qualifications

Positional tracking, bare-hand controllers, and camera-assisted Joy-Cons are separate options and default off. Bare hands and camera-assisted Joy-Cons are mutually exclusive.

- **Head tracking:** the SDK supplies the positional pose. The app checks freshness and connection/origin generations and provides tracking-loss and recenter recovery. Screen and floor origins are separate.
- **Bare hands:** Full palm + Full landmarks is the default. Raw grayscale is replicated into RGB, MediaPipe applies model tensor normalization, and calibration corrects landmark coordinates before stereo triangulation. Full landmarks support palm orientation, pinch, and grab. The small saved-image comparison favored Full over Lite but does not establish universal hand-detection quality; see the [comparison results](development/hand-model-comparison-2026-10-16.md).
- **Controller visibility:** the last accepted visual pose can remain for up to 350 ms from capture time during a short detection gap. Invalid/stale gestures release independently. Normal hand mode has no stationary dead zone; shoulder-based **Steady hand aim** is a separate opt-in setting.
- **Joy-Cons:** camera position uses wrist landmark 0. Physical buttons and IMU orientation remain the input sources, with an arm estimate when a usable camera position is unavailable. Camera wrist position does not make a physical controller fully optically tracked in every pose. Screen and Joy-Con recenter actions are separate.
- **Calibration:** the included 640×480 Luma Ultra stereo profile is a model-specific starting point. A matching saved device profile takes precedence. The shared profile is labeled separately from an individually distance-verified calibration; optional board collection and distance validation refine a device profile.
- **RGB camera:** the third camera remains diagnostic-only. It is not used for controller depth, hand tracking, or camera passthrough. The failed hybrid stereo matcher and temporary USB transport experiments are not part of the live pipeline.

## Build and troubleshooting references

Luma Ultra, Beast, and Pro 2 control/tracking require the optional, externally supplied **VITURE Android SDK 2.4.0** in the APK build. Without it, DroidDeck retains its legacy VITURE and RayNeo transports and the separate XREAL integration; it cannot advertise SDK-dependent tracking. Vendor SDK headers and binaries are not stored in this repository. See [VITURE SDK setup](development/viture-sdk.md).

If only a flat picture is available, check video output first, then the control connection. A disabled tracking toggle can mean missing SDK support, missing USB/network access, or a device without a supported IMU transport. SBS or a new resolution requires a confirmed physical display mode; selecting a preference cannot add a mode the phone/display does not expose.

- [Luma spatial setup, calibration, and diagnostics](development/luma-spatial-tracking.md)
- [Tracked DroidDeck menu implementation](development/glasses-menu-tracking.md)
- [Agent diagnostics and capture commands](agent-control.md)
- [RayNeo attribution and protocol references](third-party/rayneo/README.md)

The capability definitions are maintained in [VITURE models](../app/src/main/java/com/droiddeck/launcher/glasses/VitureProtocol.kt), [RayNeo models](../app/src/main/java/com/droiddeck/launcher/glasses/RayNeoModel.kt), [XREAL 1S](../app/src/main/java/com/droiddeck/launcher/glasses/XrealModel.kt), and [connection capability routing](../app/src/main/java/com/droiddeck/launcher/glasses/Glasses.kt). Update this matrix when those integrations change.
