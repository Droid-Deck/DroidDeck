# SDK-free Android VITURE Pro 2 probe

Protocol and the pose test vector are adapted from Holger Bartnick's MIT-licensed
[elasticjava/viture-v2](https://github.com/elasticjava/viture-v2), pinned to commit
`ece8c30485f7d357fefa362ae74f3eabac9b3899`. Consult `LICENSE.upstream` for its license;
the APK also includes this notice at `assets/LICENSE.upstream`.
Sources consulted: upstream `PROTOCOL.md`, runtime `src/lib.rs` and `src/usbfs.rs`.
The upstream document says its protocol was derived from official SDK USB captures
and then verified using an independent implementation. This is third-party
SDK-derived protocol documentation, not a claim of clean-room discovery.

This standalone Java application uses Android's USB host APIs. It includes no
vendor SDK, vendor native library, project Kotlin source, or project SDK trace.
The prior local SDK capture directories and downloaded vendor SDK were excluded
from this work. Its build uses the Java sources and manifest in this directory.

Only VITURE Pro 2 `35ca:1301` (decimal PID 4865), interface 0, is accepted. The
active interface must be HID alternate 0 with exactly two endpoints: one 64-byte
interrupt IN and one 64-byte interrupt OUT. Endpoint addresses come from the
device descriptors. Standard GET_CONFIGURATION and GET_INTERFACE reads identify
the current transport; the probe changes neither setting. The input request is
queued before any protocol command.

The probe queries firmware with `0x3003`, enables either pose at 120 Hz
(`0x0301 [1,2]`) or raw at 120 Hz (`0x0301 [2,2]`), and sends stream off
(`0x0301 [0,0]`) in `finally`. It does not query serial numbers or change display,
brightness, volume, film, or calibration settings. Pose and raw are separate modes.

Every input completion retains its actual byte count and base64 bytes. Decoding
requires the v2 preamble, bounded declared length, byte-sum checksum, and exact
known event payload size. Pose requires finite quaternion components and norm
within 0.05 of 1. Raw samples require finite gyro/acceleration components;
acceleration norm is logged without assuming that moving hardware has a 1 g
reading. Valid frame, pose, raw, malformed frame, and malformed sample counters
remain separate. Device tick units are unknown. Transport completion is not a
firmware ACK; control ACKs are logged independently.

Stream off is attempted on orderly completion, stop, activity destruction, and
caught errors after a stream-enable attempt. Input reads remain active for a
bounded off-response grace period before cancellation. Resource closing does not
depend on successful logging. Process termination, unplugging, or power loss can
prevent cleanup.

Build with `./tools/viture/viture-v2-probe/build.ps1`. SDK discovery uses
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, then `%LOCALAPPDATA%\Android\Sdk`. JDK discovery
uses `JAVA_HOME`, the optional `%USERPROFILE%\.jdks\jbr-17.0.9`, then `javac.exe` on
PATH. Explicit `-Sdk` and `-Java` override discovery. Thirteen protocol checks run
before APK compilation and signing. The APK is written to
`tools/viture/viture-v2-probe/out/viture-v2-probe-debug.apk` and uses the repository
test key. No Gradle dependency or vendor library is required.

After installation, launch the default pose capture:

```powershell
adb shell am start -n com.droiddeck.viturev2probe/.MainActivity --ez auto true --ei seconds 15
```

For raw gyro and accelerometer data, pass `--es mode raw`:

```powershell
adb shell am start -n com.droiddeck.viturev2probe/.MainActivity --ez auto true --es mode raw --ei seconds 15
```

`mode` accepts `pose` or `raw` and defaults to `pose`. Automatic launch defaults to
PID 4865 and interface 0; if supplied, `--ei pid` and `--ei interface` must match
those exact values. `seconds` defaults to 15 and is clamped to 1..30. Descriptor
reads and bounded cleanup add a few seconds around that capture window. Allow
the USB permission dialog once. The UI provides a Pro 2 capture button using the
selected mode and a stop button.

Logs are app-private JSONL files under
`/data/user/0/com.droiddeck.viturev2probe/files/captures/` named
`probe-<wall-time-ms>-pid-4865-if-0.jsonl`.
Use `adb shell run-as com.droiddeck.viturev2probe ls files/captures` to enumerate them
and `adb exec-out run-as com.droiddeck.viturev2probe cat files/captures/<name>` to read.
For another launch with new extras, finish the existing activity or force-stop only
after `session_end` is present so the stream-off cleanup can complete.
