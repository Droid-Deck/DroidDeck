#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
out="$repo/app/build/amazon-engine"
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${NDK:-${ANDROID_NDK_LATEST_HOME:-}}}}
[[ -d "$ANDROID_NDK_HOME" ]] || { echo 'Android NDK required for Amazon engine' >&2; exit 1; }
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$repo/app/src/main/rust/stores/target"}
export RUSTC=$(rustup which --toolchain stable rustc)
mkdir -p "$out"
cd "$repo/app/src/main/rust/stores/amazon"
rustup run stable cargo ndk --target arm64-v8a --platform 26 --output-dir "$repo/app/build/amazon-native" build --release --locked
cp "$repo/app/build/amazon-native/arm64-v8a/libdroiddeckamazon.so" "$out/libdroiddeckamazon.so"
python3 "$repo/tools/stores/amazon-manifest.py" "$out" "$ANDROID_NDK_HOME"
