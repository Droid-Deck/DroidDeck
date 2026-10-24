# Android XR implementation validation — October 22, 2026

Checkout base: `65f44e2b807fdc6c05780460e66001ff7e87f893`.
Feature branch: `feat/android-xr`. No pre-existing user edits were present.

| Claim | Result |
| --- | --- |
| Shared production C++ core | 49 deterministic checks passed |
| Existing v1 tracking, Touch, chaperone C++ tests | Passed |
| Mock OpenXR optional binding dispatch | Five cases passed; no runtime execution |
| ASan and UBSan host checks | Passed with `ASAN_OPTIONS=detect_leaks=0` |
| Python command/Quest/chaperone/Windows runtime packaging tests | 31 tests passed |
| Diagnostics JNI, debug and release | Compiled and linked for arm64-v8a and x86_64 |
| Production OpenXR C++ source | ARM64 object compiled in debug/probe and release configurations |
| Production OpenXR library link | Blocked: real OpenXR loader binary unavailable locally |
| SteamVR driver | Host C++ compilation and shared-library link passed; ARM64 guest/runtime execution unverified |
| Diagnostics Kotlin/APK and ArCoreTestRule test | Blocked before dependency resolution; unverified |
| Production APK, Kotlin tests, merged manifests | Blocked before compilation; unverified |
| Manifest source XML / diagnostics ELF inspection | Passed; not a substitute for merged manifests or APK inspection |
| Jetpack XR emulator observations | None; no supported XR AVD available here |
| Native OpenXR startup, actual tracking, guest graphics, gameplay | Pending hardware |

Commands executed from `/workspace/DroidDeckXR`:

```bash
python3 tools/test_xr_common.py
ASAN_OPTIONS=detect_leaks=0 python3 tools/test_xr_common.py --sanitize --openxr-include build/androidxr-headers
python3 -m unittest tools.tests.test_windows_vr_build tools.tests.test_prepare_windows_runtime \
  tools.tests.test_androidxr_commands tools.tests.test_quest_xr_validation tools.tests.test_vr_chaperone
python3 tools/build_xr_native.py --sdk /workspace/android-sdk --openxr-include build/androidxr-headers
python3 tools/build_xr_native.py --sdk /workspace/android-sdk --release --openxr-include build/androidxr-headers
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk GRADLE_USER_HOME=/workspace/.gradle \
  bash tools/androidxr-diagnostics/gradlew -p tools/androidxr-diagnostics :app:assembleDebug --console=plain
JAVA_HOME=/workspace/jdk17 ANDROID_HOME=/workspace/android-sdk GRADLE_USER_HOME=/workspace/.gradle \
  bash gradlew :app:externalNativeBuildDebug :app:processDebugMainManifest \
  :app:processReleaseMainManifest -PskipRust=true --offline --console=plain
```

The native compile used unmodified OpenXR headers obtained from the official
Khronos OpenXR-SDK `release-1.1.49` through the GitHub connector. They live in
ignored build output, not a new vendored dependency. Compiler: NDK r27 clang 18,
production API 26/ARM64; diagnostics API 34/ARM64 and x86_64. Fixture implementation
is compiled only when explicitly enabled for debug/test targets. `llvm-nm` verified
fixture/test implementation symbols are absent from release JNI. `llvm-readelf`
verified ARM64/x86_64 ELF machines and dependencies limited to `libc`, `libm`,
and `libdl` (static libc++); see `build/androidxr-validation/elf.json`.
Source XML assertions verified the optional Google runtime library, native-only
unmanaged activity property, and separate diagnostics permission declarations.
Gradle-merged manifests were not produced.

The SteamVR host link used the repository's normal Wayland link stub (not an
OpenXR loader substitute) and NDK Vulkan headers copied under the build directory:

```bash
mkdir -p build/androidxr-validation/steamvr build/androidxr-validation/include
cp -R /workspace/android-sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vulkan build/androidxr-validation/include/
cp -R /workspace/android-sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/vk_video build/androidxr-validation/include/
cc -shared -fPIC -O2 -Wl,-soname,libwayland-client.so.0 -Itools/vr/third_party/wayland \
  -o build/androidxr-validation/steamvr/libwayland-client.so tools/vr/src/wayland_client_stub.c
cc -c -fPIC -O2 -Itools/vr/third_party/wayland \
  -o build/androidxr-validation/steamvr/xdg-shell-protocol.o app/src/main/cpp/waylandcomp/generated/xdg-shell-protocol.c
c++ -std=c++17 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers \
  -fvisibility=hidden -static-libstdc++ -static-libgcc -pthread -Wl,-z,defs \
  -Ibuild/androidxr-validation/include -Itools/vr/third_party/wayland -Iapp/src/main/cpp/waylandcomp/generated \
  -o build/androidxr-validation/steamvr/driver_droiddeck.so tools/vr/src/driver_droiddeck.cpp tools/vr/src/presenter.cpp \
  build/androidxr-validation/steamvr/xdg-shell-protocol.o -Lbuild/androidxr-validation/steamvr -lwayland-client -ldl
```

Build logs are under `build/androidxr-validation/`. Diagnostics Gradle failed
downloading Gradle 8.13 with `java.net.SocketException: Operation not permitted`.
Production Gradle 8.10.2 (cached) failed with `Could not determine a usable wildcard
IP for this machine`. SDK 36 and Jetpack XR artifacts are not locally installed;
only SDK 34 is available. The SDK has no emulator directory, and `/dev/kvm` is
absent. `adb devices` could not start in this sandbox (initially the default
`.android` path was read-only). No install/launch success is claimed.

Baseline, recorded **before edits**: `bash tools/test_local.sh` ran 926 tests with
3 failures, 41 errors, 58 skips. Most failures/errors involved disallowed sockets
and process inspection. Failing cases included Epic parent-process discovery,
Quest presentation, VR producer lease socket binding, and many broker/CDP tests.
Production Gradle already failed on the wildcard-IP error before this change.
These baseline failures are not attributed to the XR patch. Initial LeakSanitizer
execution later failed because this sandbox uses ptrace; the address/undefined
sanitizer rerun disabled leak detection explicitly and passed.

The Jetpack adapter test and the JNI fixture run on Android are authored but have
not executed here. The passed injected-data claim applies to the host C++ suite
and mock binding dispatch only. The same suite is callable from JNI, which has
compiled for both target ABIs; compilation does not establish Android execution.

Remaining acceptance gates: resolve/build the isolated Gradle project locally;
run its injected adapter test; inspect both projects' merged manifests/APK native
dependencies; run the supported Jetpack AVD with movement and right-hand pinch;
then complete the native-probe, guest, SteamVR and game hardware sequence in
[android-xr.md](android-xr.md). Production rootfs/native packs must be prepared by
the existing build workflow before claiming a complete production APK build.
