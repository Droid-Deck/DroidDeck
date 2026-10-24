#!/usr/bin/env bash
set -euo pipefail
export NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}}"
tools/proot/build.sh app/src/main/jniLibs/arm64-v8a
