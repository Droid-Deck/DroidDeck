# Pro 2 direct USB validation

These tests used a Samsung Galaxy Z Fold7 (SM-F976U), Android 17, with glasses
connected to the phone and wireless ADB used for installation and log collection.
The tested Pro 2 identifies as `35ca:1301`, with interface 0 exposing 64-byte
interrupt IN `0x81` and interrupt OUT `0x01`. Its firmware query returned
`30.0.00.001_20260714`. Results apply to this device and firmware.

## Protocol probe

The SDK-free Java probe in `tools/viture/viture-v2-probe` uses the attributed MIT
protocol. Every run queried firmware, enabled one stream, and sent stream-off at
completion. Each Pro 2 run received a successful firmware response and successful
start/stop acknowledgments. All three had zero malformed frames or samples.

| Run | Valid samples | Measured rate | Observation |
| --- | ---: | ---: | --- |
| Pose, initial 15-second window | 1,762 | 120.67 Hz | Normalized quaternions; components changed |
| Pose, 30-second motion window | 3,571 | 120.63 Hz | User turned, looked up/down, and tilted; all components varied |
| Raw, separate 15-second window | 1,471 | 100.77 Hz | Finite gyro and accelerometer values; acceleration magnitude 0.882–1.129 g |

Rates use host receipt time between the first and last samples. Query/setup time
reduces the sampling interval within each window. Although both stream requests
specified the upstream 120 Hz setting, the raw stream delivered approximately
101 Hz. Device counters are retained without assigning time units. Pose quaternion
norms were within 0.00000007 of one. These measurements do not establish accuracy,
drift, physical axis signs in a rendered scene, or 6DoF.

Raw gyro/accelerometer decoding is available for future work. The application uses
only the device's pose stream. Combined pose/raw streaming is deliberately excluded
because upstream observed acknowledgments without subsequent samples in that mode.

## Reproducing the checks

Build the application with `-PvitureSdkDir=` to explicitly exclude the optional
vendor SDK. No vendor SDK download, vendor account, binary upload, or repository
secret is required for Pro 2. See each tool's README/provenance document for Android
SDK/JDK requirements, build commands, USB permission, and log collection.

The protocol probe is separate from the application driver. The
`tools/viture/viture-v2-driver-check` harness packages the actual compiled
`VitureV2Link`, `VitureV2Protocol`, and `GlassesLink` classes and records their hashes.
It verifies readiness from poses and a stop/restart/stop/close sequence.

On the same Pro 2, the final production-driver check **passed**: 1,446 poses in the
12-second resumed capture at 120.65 Hz, plus three bootstrap poses. It reported one
ready callback, two tracking-on and two tracking-off callbacks, zero errors and
zero bad frames. Both 500 ms pause windows delivered zero further poses. After
close, readiness and tracking were both false. The complete sequence took 13.03 s.
This validates the production USB driver; it does not by itself exercise the full
DroidDeck UI or an in-game head-tracking session.

All 115 selected host-side tests passed. They cover wire framing/checksums,
bounded parsing, pose and raw layouts,
invalid samples, readiness without trusting ACKs, host-receipt cutoffs and
duplicate/backward device-tick rejection,
stop/restart behavior, transport failures, relative coordinate conversion, exact
USB model routing, consumer removal before the first restarted pose, and UI hiding
of unsupported hardware/display controls.

## Full application check

The complete debug APK built successfully with `:app:assembleDebug -PvitureSdkDir=`
and passed the repository's runtime-input verification and APK signature checks.
The APK contains the upstream MIT license and contains none of `libglasses.so`,
`libcarina_vio.so`, `libcloud_protocol.so`, or `libdroiddeck_viture.so`.

Installed as `com.droiddeck.launcher.xr`, version 0.5.0, on the same phone, it
recognized Pro 2 and exposed `supportsScreen3dof=true` after USB permission and
valid bootstrap samples. A five-second sample through DroidDeck's own `glasses`
agent endpoint returned **595 poses (118.98 Hz including startup)**, with quaternion
norms within 0.00000006 of one and zero bad frames. Tracking was false before the
sample, true during it, and false afterward; the connection remained ready with
no control error. This checks application integration and consumer-driven streaming,
not an in-game rendering or SteamVR session.

Tested APK SHA-256:
`f5a2484ea1708a143cb94a96e084188cb0d2b122154a2645d3127c66f6d04f21`.

## User game test

After installing the tested build, the user reported that the Pro 2 game test
passed. This adds user-reported in-game validation to the automated USB and
application checks above. The game title, test duration, and separate recentering
or SteamVR checks were not recorded.

## Local capture fingerprints

Full motion logs are local diagnostic artifacts, not required build inputs. These
SHA-256 values identify the captures behind the table above:

| Capture | SHA-256 |
| --- | --- |
| `pro2-pose-initial.jsonl` | `beb09072d8d7ce2b1378c41992b88787e0b09f3ab3ea330a413b3ef9e9f5b762` |
| `pro2-pose-motion.jsonl` | `d72f3bbd6564061e9bf4e06fec7f4c6dcdbfabeee626a99a7bff279b416ccc61` |
| `pro2-raw.jsonl` | `944ac2fe30c7b1a817bc4d3281ead620000be32aa2dbd96e6b104cf4d7063091` |
| `pro2-production-driver-final.jsonl` | `0f1c1073963a7c3052bf8bdbcb39e3ef8ec332367ab1a3e39b622d36fcd06db2` |
| `pro2-full-app.json` | `3e48d9c3e8a230ec84e09bb51395b8566618406abb93937b8e2b69c40b4854c5` |

The provenance in [README.md](README.md) applies to all these checks: the upstream
MIT project used SDK traffic captures in its research. This adaptation contains
and loads no vendor SDK; it does not claim independent protocol discovery.
