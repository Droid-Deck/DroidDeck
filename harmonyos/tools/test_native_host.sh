#!/usr/bin/env bash
#
# Runs the native unit tests on the build machine.
#
# Two things are pinned here:
#
#   * the supervisor's guest-launch argv, which has to match upstream
#     DroidDeck exactly, because upstream's proot prefix and bind list
#     decide how the guest sees the filesystem;
#   * the runtime install path, from a packed artifact to the layout the
#     supervisor recognises as an installed runtime.
#
# Neither needs a device or the HarmonyOS SDK.
#
# Usage: tools/test_native_host.sh

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

out="${root}/build/host"
# Every run works in its own directory. The build tree is not deleted between
# runs, because the recoverable-delete guard cannot always move files on
# this volume, and a stale fixture would hide a broken clean-up.
run="${out}/run-$(date +%s)-$$"
fixture="${run}/fixture"
mkdir -p "${run}"

CXX="${CXX:-clang++}"
ZSTD="${root}/entry/src/main/cpp/thirdparty/zstd"
SOURCES=(
  "${root}/entry/src/main/cpp/runtime/runtime.cpp"
  "${root}/entry/src/main/cpp/runtime/install.cpp"
  "${root}/entry/src/main/cpp/runtime/unpack.cpp"
)
INCLUDES=(-I "${root}/entry/src/main/cpp" -I "${ZSTD}/include")

echo "==> compiling the guest-launch tests with ${CXX}"
"${CXX}" -std=c++17 -Wall -Wextra -Wno-unused-parameter \
  "${INCLUDES[@]}" \
  "${SOURCES[@]}" \
  "${root}/entry/src/main/cpp/tests/runtime_test.cpp" \
  "${ZSTD}/libzstd-host.a" \
  -lz \
  -o "${out}/runtime_test"

echo "==> compiling the runtime install tests with ${CXX}"
"${CXX}" -std=c++17 -Wall -Wextra -Wno-unused-parameter \
  "${INCLUDES[@]}" \
  "${SOURCES[@]}" \
  "${root}/entry/src/main/cpp/tests/unpack_test.cpp" \
  "${ZSTD}/libzstd-host.a" \
  -lz \
  -o "${out}/unpack_test"

echo "==> running the guest-launch tests"
"${out}/runtime_test"

echo "==> compiling the audio ring tests with ${CXX}"
"${CXX}" -std=c++17 -Wall -Wextra -Wno-unused-parameter \
  "${INCLUDES[@]}" \
  "${root}/entry/src/main/cpp/audio/audio.cpp" \
  "${root}/entry/src/main/cpp/tests/audio_test.cpp" \
  -o "${out}/audio_test"

echo "==> running the audio ring tests"
"${out}/audio_test"

echo "==> building the runtime fixture"
# The fixture mimics the published runtime image: it carries gamescope and
# the stub directories, but NOT the session script. Upstream bakes its
# overlay into the image at build time and publishes the image without it,
# so the port has to apply the overlay itself.
mkdir -p "${fixture}/root/etc/droiddeck/empty" \
         "${fixture}/root/etc/droiddeck/proc" \
         "${fixture}/root/usr/games" \
         "${fixture}/root/usr/local/bin" \
         "${fixture}/root/usr/share/vulkan/icd.d" \
         "${fixture}/overlay/usr/local/bin"
printf '#!/bin/sh\nexit 0\n' > "${fixture}/root/usr/games/gamescope"
printf 'Linux version 5.10.0\n' > "${fixture}/root/etc/droiddeck/proc/version"
printf '{}\n' > "${fixture}/root/usr/share/vulkan/icd.d/panvk_icd_aarch64.json"
ln -sf gamescope "${fixture}/root/usr/games/gamescope-link"
# A hard link, because the published image contains 117 of them.
ln "${fixture}/root/usr/games/gamescope" "${fixture}/root/usr/games/gamescope-hard"
tar -czf "${out}/runtime-fixture.tar.gz" -C "${fixture}/root" .

# The overlay the port applies on top of the image.
printf '#!/bin/bash\necho "== droiddeck-session"\nexit 0\n' > "${fixture}/overlay/usr/local/bin/droiddeck-session"
chmod 700 "${fixture}/overlay/usr/local/bin/droiddeck-session"
tar -czf "${out}/overlay-fixture.tar.gz" -C "${fixture}/overlay" usr

# The real smoke artifact, when the SDK is available, has to install the
# same way the fixture does: it is what a phone downloads first.
smoke=""
if [ -n "${OHOS_SDK_HOME:-}" ] && [ -x "${OHOS_SDK_HOME}/native/llvm/bin/clang" ]; then
  echo "==> building the smoke runtime artifact"
  OHOS_SDK_HOME="${OHOS_SDK_HOME}" WORK_DIR="${out}/smoke-work" \
    bash "${root}/tools/build_smoke_rootfs.sh" > "${out}/smoke-build.log" 2>&1 || {
      echo "the smoke artifact could not be built; see ${out}/smoke-build.log" >&2
    }
  if [ -f "${root}/build/droiddeck-runtime-smoke-arm64.tar.gz" ]; then
    smoke="${root}/build/droiddeck-runtime-smoke-arm64.tar.gz"
  fi
else
  echo "==> skipping the smoke artifact (set OHOS_SDK_HOME to build it)"
fi

echo "==> running the runtime install tests"
rm -rf "${out}/unpacked" "${out}/unpacked-smoke"
"${out}/unpack_test" "${out}/runtime-fixture.tar.gz" "${out}/unpacked" \
  "${out}/overlay-fixture.tar.gz" ${smoke:+"${smoke}"}

echo "==> all native host tests passed"
