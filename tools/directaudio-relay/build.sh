#!/usr/bin/env bash
set -euo pipefail
OUTDIR=$1
mkdir -p "$OUTDIR"
: "${NDK:?set NDK to the Android NDK root}"
API=28
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
case "$(uname -s):$(uname -m)" in
  Darwin:arm64) NDK_HOST=darwin-arm64 ;;
  Darwin:x86_64) NDK_HOST=darwin-x86_64 ;;
  Linux:x86_64) NDK_HOST=linux-x86_64 ;;
  Linux:aarch64|Linux:arm64) NDK_HOST=linux-aarch64 ;;
  *) echo "Unsupported build host: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
if [[ ! -d "$TOOLCHAIN" && "$NDK_HOST" == darwin-arm64 && -d "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin" ]]; then
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
fi
if [[ ! -d "$TOOLCHAIN" && "$NDK_HOST" == linux-aarch64 && -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
if [[ ! -x "$CC" ]]; then
  echo "Android NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi
OUT="$OUTDIR/libdirectaudiorelay.so"
"$CC" -O2 -Wall -Wextra -Wno-unused-parameter -Wno-sign-compare -fPIE -pie -Wl,-z,max-page-size=16384 \
    -I"$REPO/tools/aaudio-sink" \
    -o "$OUT" "$HERE/directaudio-relay.c" -laaudio -llog
"$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT"
NEEDED=$("$TOOLCHAIN/llvm-readelf" -d "$OUT" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')
echo "$(basename "$OUT") NEEDED: $NEEDED"
for lib in $NEEDED; do
  case $lib in libaaudio.so|liblog.so|libc.so|libm.so|libdl.so) ;;
  *) echo "ERROR: unexpected dependency $lib"; exit 1 ;;
  esac
done
ls -l "$OUT"
