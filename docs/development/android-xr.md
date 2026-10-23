# Experimental Android XR support

This contribution adds an Android XR entry while keeping the existing Quest native runtime, Activity, controller path and tracking ABI.

## Boundaries

```text
SessionActivity
  ├─ QuestXrActivity → existing questxr runtime (unchanged)
  └─ AndroidXrActivity → droiddeckandroidxr → xr-core runtime
                                              ├─ OpenXR lifecycle and stereo
                                              ├─ runtime hand actions and joints
                                              └─ existing guest frame transport
SteamVR driver
  ├─ legacy tracking session → existing Quest controller path
  └─ explicit Android XR session → hand input and skeleton sidecars
```

Android XR uses its own native library and Activity. `xr-core` contains the Android XR implementation and portable input contracts; this PR does not migrate Quest to that implementation. Quest startup and tracking/controller files retain their upstream behavior. The normal PadBridge filter remains unchanged; stricter headset-input filtering is opt-in from the Android XR input adapter.

## Entry and lifecycle

System features select Android XR after the existing Quest detection. Capability checks happen inside the Full Space unmanaged Activity. Optional OpenXR-library and hand-tracking declarations do not establish device compatibility.

The Activity requests hand input, associates ownership with the current Steam session, and starts the guest after the XR session becomes visible or focused. A retiring Activity cannot hide or stop its replacement. Initialization or guest failure returns to the panel. Closing immersive mode stops SteamVR and can interrupt an active game.

Reference spaces prefer STAGE, LOCAL_FLOOR, then LOCAL. No fabricated floor is added to LOCAL. A reference-space change ends the Android XR session until a restarted guest can produce matching frame poses.

## Input

The Android XR runtime reads `XR_EXT_hand_interaction` when available. A capability-selected fallback uses `XR_EXT_hand_tracking` with `XR_FB_hand_tracking_aim`. These are runtime extension providers, not separate platform gesture recognizers.

Aim and grip remain distinct. Runtime select/pinch drives existing SteamVR trigger components; runtime grasp drives grip when available. Tracking/focus/session/origin discontinuities release held input and require release before rearming. The runtime does not infer gestures from camera images or invent missing sticks or face buttons.

The 26 OpenXR joints are converted to SteamVR's 31-bone parent-space skeleton. Android XR uses a dedicated tracked-hand device pair with a fixed input profile and separate render models. Joint tracking depends on permission and actual tracker availability. A Dashboard hand model alone does not prove application skeletal input.

SteamVR's reference gloves are skinned GLB meshes, not ordinary controller OBJ models. Android XR loads the slim left/right gloves from the installed SteamVR resources; Valve assets are not redistributed in the APK. Its GLES renderer updates skin matrices from the same real joints used by the SteamVR skeleton. There are no pinch-driven visual poses in this path. The wrist and finger coordinate bases differ.

The existing `scene-focus.json` controls local glove visibility: only a live SteamVR Dashboard or the background/vrstartup scene shows them. Games keep ownership of their hand graphics and receive both SteamVR skeletal motion ranges. While local gloves own menu/background visuals, an Android XR input flag selects pose-only render models so SteamVR does not draw duplicate static gloves. Games and a failed local model load retain the controller-model fallback, including games that only consume ordinary render models. Tracking loss and focus loss hide local gloves. The guest stream has no depth buffer, so local gloves cannot use guest scene depth for occlusion; menu overlap needs physical acceptance.

Holding both runtime pinch values requests a system-button pulse after releasing trigger/grip. Ordinary gamepads route through PadBridge; View/Select + Menu/Start requests the same pulse. A gamepad does not become a tracked hand, and applications must support gamepad navigation. Orientation, drag and menu recall still need hardware acceptance.

## Protocol and rendering

The existing 296-byte tracking protocol v1 is unchanged. Quest continues to publish `active=1`; Android XR uses an explicit active-session marker to select its additional input path. The driver reads input-v2 and skeleton sidecars only in that Android XR session. Sidecars carry session generation, origin epoch and timestamps, with atomic snapshots and single-writer ownership.

Frames use the existing guest transport. The Android XR receiver can acknowledge GPU-buffer completion off its display thread; the Quest receiver remains unchanged. The existing Quest display clock, head-velocity behavior and Vulkan capture implementation are retained.

## Validation limits

The main application is the only APK target. Device-specific GPU drivers, per-chip tuning, scheduling experiments, Quest hand/controller switching and unrelated game fixes are excluded.

This reduced contribution needs fresh Android XR end-to-end acceptance. Automated contracts, application compilation and Linux driver checks cannot establish headset behavior or smooth gameplay. Dashboard jitter and application skeletal input remain open acceptance items. See the [runbook](android-xr-runbook.md).
