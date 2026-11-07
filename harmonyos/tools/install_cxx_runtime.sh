#!/usr/bin/env bash
#
# Installs the C++ runtime the native module links against into the HAP.
#
# The app resolves its libraries by soname out of entry/libs/<abi>/, so
# libc++_shared.so has to ship next to libdroiddeck_native.so. Without it
# the module loads and then fails with "cannot open shared object file".
#
# The library comes from the OpenHarmony NDK, so it is exactly the one the
# module was built against.
#
# Usage: OHOS_SDK_HOME=/path/to/openharmony-sdk tools/install_cxx_runtime.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${OHOS_SDK_HOME:?set OHOS_SDK_HOME to the OpenHarmony SDK root}"
NDK="${OHOS_SDK_HOME}/native"

source="${NDK}/llvm/lib/aarch64-linux-ohos/c++/libc++_shared.so"
if [ ! -f "${source}" ]; then
  echo "the C++ runtime was not found at ${source}" >&2
  exit 1
fi

mkdir -p "${root}/entry/libs/arm64-v8a"
cp "${source}" "${root}/entry/libs/arm64-v8a/libc++_shared.so"

echo "==> installed entry/libs/arm64-v8a/libc++_shared.so"
file "${root}/entry/libs/arm64-v8a/libc++_shared.so"
