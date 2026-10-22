# Camera hand tracking attribution

## Included

None of these files ship in the APK. The app downloads them on first use as one tracking pack
(`tracking.tar.zst`), built in CI by `tools/packs/build.sh` from the pinned sources
below, together with `libopencv_java4.so` and `libmediapipe_tasks_jni.so` taken unchanged from
the OpenCV and MediaPipe Maven AARs.

[MediaPipe](https://github.com/google-ai-edge/mediapipe) Tasks Vision 1.0.0
(`com.google.mediapipe:tasks-vision`, shared with the Luma stereo tracking pipeline;
copyright Google LLC and the MediaPipe authors, Apache License 2.0, `LICENSE_MEDIAPIPE` here) finds
the player's body in each camera frame. It comes from Maven Central with its dependencies
(Guava, Flogger, protobuf-javalite and Google's `datatransport`), all Apache-2.0.

`pose_landmarker_lite.task` in the tracking pack (below) is MediaPipe's Pose Landmarker (Lite)
model, BlazePose GHUM 3D, unchanged from
`https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task`
(SHA-256 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`). Its
[model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf)
licenses it under the Apache License 2.0 and lists its limits: one person, up to about 4 metres
from the camera, the head in view, and depth that is approximate rather than metric.

`hand_landmarker.task` in the tracking pack is MediaPipe's Hand Landmarker (full) model
bundle, a palm detector and a hand landmark model, unchanged from
`https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`
(SHA-256 `fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1`). Its
[model card](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Hand%20Tracking%20(Lite_Full)%20with%20Fairness%20Oct%202021.pdf)
licenses it under the Apache License 2.0; it gives 21 landmarks per hand in the picture and in metres,
based on the GHUM hand model, and is meant for prominently shown hands.

The Luma stereo pipeline defaults to the same Full bundle. The optional
`hand_landmarker_lite.task` is built from
the official `palm_detection_lite.tflite` and `hand_landmark_lite.tflite` assets at
`https://storage.googleapis.com/mediapipe-assets/`. It uses the matching tensor metadata and
associated labels from the Full task bundle. These models share the MediaPipe hand tracking
model card and Apache License 2.0. Source checksums and the generated bundle checksum are in
`tools/packs/tracking/hand_landmarker_lite.provenance.json`; `tools/build_hand_lite.py`
reproduces the bundle with MediaPipe 0.10.21 metadata tooling.

[CameraX](https://developer.android.com/jetpack/androidx/releases/camera) 1.4.2
(`androidx.camera:camera-camera2`, `camera-lifecycle`; copyright The Android Open Source Project,
Apache License 2.0) delivers the frames.

`tools/vr/src/hand_mesh.h` ports Inigo Quilez's signed distance to a polygon (`sdPolygon`, from
[2D distance functions](https://iquilezles.org/articles/distfunctions2d/) and his Shadertoy
"Polygon - exact", `wdBXRW`; copyright 2019 Inigo Quilez, MIT License, `LICENSE_SDPOLYGON` here) for
the glove's palm outline.

MediaPipe's task runner reports usage statistics through Google's `datatransport` library. The app
manifest removes that library's `CctBackendFactory` entry, so those reports have nowhere to go and
are dropped on the device. Camera frames are never stored or sent anywhere.

## References

No code from these projects is included. They were read to design and check the camera tracking
and the Joy-Con movement that it corrects:

- [JoyConVr](https://github.com/F8LEFT/JoyConVr) by F8LEFT and contributors (MIT), `android-camera`
  at 76466c28829396651be3c68143f6297ed68923e4: an Android app that runs MediaPipe's pose landmarker
  for Joy-Con VR hands, levels the camera with gravity and slows inference down as needed.
- Google's `GvrArmModel.cs` in the [Google VR SDK for Unity](https://github.com/googlevr/gvr-unity-sdk)
  v1.30.0 (Apache-2.0): its optional accelerometer mode moves the elbow by a filtered, integrated
  velocity and keeps the offset within a range, the idea behind the short Joy-Con movements here.
- MediaPipe's [pose landmarker guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker),
  [hand landmarker guide](https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker),
  `pose_landmarker_graph.cc` and `pose_landmarks_detector_graph.cc` (Apache-2.0): image and world
  landmarks, and the tracking and landmark smoothing that video mode applies to a single pose, which
  the app uses. MediaPipe Holistic's idea of finding the hands from the pose's wrists informed the hand
  crops.
- R. E. Kalman, "A New Approach to Linear Filtering and Prediction Problems", Journal of Basic
  Engineering 82(1), 1960, and the usual treatment of late measurements by replaying the stored
  inputs after them: the per-hand filter that joins Joy-Con movement and camera sightings.
- Android's [Camera2 reference](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
  (`SENSOR_ORIENTATION`, `LENS_FACING`, `LENS_INTRINSIC_CALIBRATION`, `CONTROL_ZOOM_RATIO_RANGE`,
  `SENSOR_INFO_TIMESTAMP_SOURCE`): choosing the camera, its focal length and turning frames upright.
- [hid-nintendo](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-nintendo.c)
  (GPL-2.0+): the Joy-Con IMU report, three samples every 10 ms, and its timestamps.
- E. Foxlin, "Pedestrian Tracking with Shoe-Mounted Inertial Sensors", IEEE Computer Graphics and
  Applications 25(6), 2005: zero-velocity updates while the sensor is still.
- I. Frosio, F. Pedersini, N. A. Borghese, "Autocalibration of MEMS Accelerometers", IEEE
  Transactions on Instrumentation and Measurement 58(6), 2009: learning an accelerometer's offset
  and scale from resting positions.

- [ALVR](https://github.com/alvr-org/ALVR) (MIT), `alvr/server_core/src/hand_gestures.rs` at
  ebec107b7e5f81677fb9c631d239daf2110bf573: turning tracked hands into controller input (thumb pinches
  to each finger, a pinch range from touching to pulling, grip from the other fingers, a held palm
  for the menu). The distances, mapping and timing here are DroidDeck's own.
- Meta Quest's hand tracking: the palm-toward-the-face pinch that opens the system menu, and the
  plain white hands it draws, which the gesture and the glove model follow.
- B. K. P. Horn, "Closed-form solution of absolute orientation using unit quaternions", Journal of
  the Optical Society of America A 4(4), 1987: fitting the skeleton's palm to the camera's.
- G. Casiez, N. Roussel, D. Vogel, "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in
  Interactive Systems", CHI 2012: the bare hands' position and turn filters.
- Valve's [OpenVR wiki](https://github.com/ValveSoftware/openvr/wiki) pages "Render Model Reference"
  and "Driver Render Models": the render model JSON, its components, `component_path` and visibility.
  The glove's tip, base, handgrip and grip poses are the numbers in SteamVR's Valve Index controller
  model (`valve_controller_knu_1_0_left` and `_right`), so the pointer leaves the hand as it leaves an
  Index controller, and its button parts showed which fields SteamVR needs to route a part to an input.
- S. F. F. Gibson, "Constrained Elastic Surface Nets", MICCAI 1998, and Mikola Lysenko's
  [Smooth Voxel Terrain (Part 2)](https://0fps.net/2012/07/12/smooth-voxel-terrain-part-2/): meshing
  the glove's distance field with surface nets.
- [HaGRID](https://github.com/hukenovs/hagrid) (A. Kapitanov et al., "HaGRID - HAnd Gesture Recognition
  Image Dataset", WACV 2024; CC BY-SA 4.0), through the 384p sample
  [cj-mills/hagrid-sample-30k-384p](https://huggingface.co/datasets/cj-mills/hagrid-sample-30k-384p):
  550 of its photos were used, on the development machine only, to check the pinch and grip distances.
  No picture is included.
- Inigo Quilez's articles on [smooth minimum](https://iquilezles.org/articles/smin/) and
  [3D distance functions](https://iquilezles.org/articles/distfunctions/) (the polynomial smooth
  minimum and the ellipsoid bound) and his talk "Rendering Worlds with Two Triangles" (NVScene 2008;
  ambient occlusion from a distance field): blending the glove's capsules, palm and wrist band, and
  baking its shading.

Nintendo Switch and Joy-Con are trademarks of Nintendo. This project is not affiliated with Nintendo,
Google, Meta or Valve.
