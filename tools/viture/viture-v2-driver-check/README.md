# Production VITURE v2 driver hardware check

This harness calls the actual production `VitureV2Link.Companion.open` and packages
the freshly compiled `VitureV2Link*.class`, `VitureV2Protocol*.class`, and
`GlassesLink*.class` files from `app/build/tmp/kotlin-classes/debug`. It contains no
USB transport or protocol implementation of its own and no vendor SDK or native
library. The Kotlin standard library version matches the root Gradle build, 2.1.0.
The default build relies on Gradle's source/dependency checks to refresh classes
and rejects unexpected application-class dependencies, including an old
orientation-helper dependency. Source and bytecode hashes are recorded together;
file timestamps are not used because Gradle can restore classes from its cache.

Build from the workspace with:

```powershell
./tools/viture/viture-v2-driver-check/build.ps1
```

This first invokes `:app:compileDebugKotlin -PvitureSdkDir=`. If the parent task just
completed that compile, pass `-SkipCompile` to package those classes without starting
another Gradle build. That option requires the caller to ensure the classes are
current. The output is
`tools/viture/viture-v2-driver-check/out/viture-v2-driver-check-debug.apk`.
SDK discovery uses `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then `%LOCALAPPDATA%\Android\Sdk`.
JDK discovery uses `JAVA_HOME`, the optional `%USERPROFILE%\.jdks\jbr-17.0.9`, then
`javac.exe` on PATH. Explicit `-Sdk` and `-Java` override discovery. Kotlin cache
discovery respects `GRADLE_USER_HOME`, falling back to `%USERPROFILE%\.gradle`.
APK assets contain the upstream MIT license and a runtime manifest of the exact
production-class SHA256 hashes and Kotlin library hash. Every session logs this
manifest. The documented upstream protocol itself was derived from third-party
vendor SDK captures; this harness uses the existing SDK-free production driver.

Only an attached `35ca:1301` Pro 2 can be opened. After USB permission, the real
driver bootstraps its pose stream. `onReady` is posted to the main handler so the
link assignment is complete before control commands. The harness sends the
controller's internal IMU-off command, checks for zero new pose callbacks over
500 ms, resumes, captures for 10..15 seconds (default 12), pauses again, checks
another 500 ms, and closes the driver. A watchdog starts closure at 23.5 seconds,
reserving the driver's maximum 1.5-second join for a 25-second test limit after
permission. Android scheduling or system stalls can extend elapsed wall time.

```powershell
adb shell am start -n com.droiddeck.viturev2check/.MainActivity --ez auto true --ei seconds 12
```

JSONL logs are under
`/data/user/0/com.droiddeck.viturev2check/files/checks/driver-check-<wall-ms>.jsonl`.
They include every callback packet with actual length and base64 bytes, the exact
shared `xyzw` quaternion delivered by production code, tracking/ready events,
pause-window counts, errors, bad frames, total and capture pose counts, sample
rate, and final state. This checks the real driver's hardware/session behavior;
it does not run the full application UI or establish the user's physical axis
and recentering experience. Reopen the activity for a separate check.
