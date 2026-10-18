# Joy-Con VR controller attribution

## Adapted code

`tools/vr/src/arm_model.h` ports the arm model of Google's `GvrArmModel.cs` from the
[Google VR SDK for Unity](https://github.com/googlevr/gvr-unity-sdk)
(`Assets/GoogleVR/Scripts/Controller/ArmModel/GvrArmModel.cs` at
f999ed727a1d238c246ba4a504b77d55b59c9e9a; copyright 2016 Google Inc., Apache License 2.0, LICENSE_GVR
here). It is rewritten in C++ in OpenVR's coordinate frame. The rest positions, extension
angles, elbow bend, extension weight and the torso following the gaze are Google's. The neck
offset is left out, as `GvrArmModel` leaves it out by default.

`tools/vr/src/hand_skeleton.h` adapts `hand_simulation.cpp` from Valve's OpenVR
`handskeletonsimulation` driver sample
([ValveSoftware/openvr](https://github.com/ValveSoftware/openvr), `samples/drivers/drivers/handskeletonsimulation`
at 0924064316de3effbcd1acf1e309182a2deb1c05; copyright Valve Corporation, BSD-3-Clause,
`tools/vr/src/OPENVR_LICENSE`). Valve's sample is inspired by Moshi Turner's hand simulation in
[Monado](https://gitlab.freedesktop.org/monado/monado) (`src/xrt/auxiliary/util/u_hand_simulation.c`).

`tools/vr/src/index_pose.h` uses the Valve Index tip rotation and grip offset listed in Monado's
`src/xrt/auxiliary/vive/vive_poses.c` (at 8b259763fdd23c46592fd4cdd30b4eea997d4b70; copyright
Collabora, Ltd., author Daniel Willmott, Boost Software License 1.0, LICENSE_MONADO here).

## References

No code from these projects is included. They were read to learn how Joy-Cons, Android and
SteamVR behave, and to check this implementation:

- [hid-nintendo](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-nintendo.c), the Linux
  Joy-Con driver by Daniel J. Ogorchock and contributors (GPL-2.0+): button codes, stick axes, IMU
  axes and units, and the rumble channels Android exposes.
- [Nintendo_Switch_Reverse_Engineering](https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering)
  by dekuNukem: the Joy-Con's buttons, sensors and rumble.
- [SDL](https://github.com/libsdl-org/SDL) (zlib): how Switch controller sensors map to a game
  controller's axes.
- [Android Open Source Project](https://github.com/aosp-mirror/platform_frameworks_base) (Apache-2.0):
  `data/keyboards/Generic.kl`, which Android applies to Joy-Cons, and the `InputDevice` sensor and
  vibrator APIs.
- [ALVR](https://github.com/alvr-org/ALVR) (MIT): presenting controllers to SteamVR as Index
  controllers and handling their haptic events.
- [OpenComposite](https://gitlab.com/znixian/OpenOVR) (GPL-3.0): cross-check of the Index input
  profile and skeleton.
- [PSMoveSteamVRBridge](https://github.com/HipsterSloth/PSMoveSteamVRBridge) (Apache-2.0): driving
  SteamVR hands from orientation-only controllers.
- [JoyConVr](https://github.com/F8LEFT/JoyConVr) by F8LEFT (MIT): a Joy-Con button layout for SteamVR
  and recentering by holding a button.
- [Daydream-Controller-SteamVR](https://github.com/krnelpan1c/Daydream-Controller-SteamVR) by
  krnelpan1c: snapping a 3DoF controller's heading to the view by holding Home.
- [joyconvr](https://github.com/yoyobuae/joyconvr) by yoyobuae: Joy-Con sensor axes in SteamVR.
- [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) (GPL-3.0) and
  [Dolphin](https://github.com/dolphin-emu/dolphin) (GPL-2.0+): reading a game controller's motion
  sensors through Android's `InputDevice` API.

Nintendo Switch and Joy-Con are trademarks of Nintendo. Valve Index and SteamVR are trademarks of
Valve Corporation. This project is not affiliated with either.
