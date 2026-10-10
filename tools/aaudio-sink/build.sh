#!/usr/bin/env bash
# Compiles module-aaudio-sink.so against the upstream PulseAudio 13.0 headers and the daemon's own
# libraries the app ships (app/src/main/jniLibs/arm64-v8a), with the Android NDK.
#   NDK=<ndk root> tools/aaudio-sink/build.sh <pulseaudio-13.0 source dir> <output dir>
# Produces module-aaudio-sink.so in the output dir (the fallback sink; the DirectAudio sinks come
# from the pinned DirectAudio release, see tools/directaudio/fetch.sh).
set -euo pipefail
PA_SRC=$1
OUTDIR=$2
mkdir -p "$OUTDIR"
: "${NDK:?set NDK to the Android NDK root}"
API=26
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
LIBS="$REPO/app/src/main/jniLibs/arm64-v8a"
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
test -f "$PA_SRC/src/pulse/version.h.in"
INC=$(mktemp -d)
mkdir -p "$INC/pulse"
sed -e 's/@PA_MAJOR@/13/g' -e 's/@PA_MINOR@/0/g' -e 's/@PA_API_VERSION@/12/g' -e 's/@PA_PROTOCOL_VERSION@/33/g' \
    "$PA_SRC/src/pulse/version.h.in" > "$INC/pulse/version.h"
cp "$HERE/config.h" "$HERE/ltdl.h" "$INC/"
build_one() {
  local src=$1 OUT=$2 extra=$3
  # PulseAudio 13's own headers (pulsecore/atomic.h) predate clang's int-conversion error; that
  # one is downgraded for them, the modules themselves compile clean.
  "$CC" -O2 -shared -fPIC -Wall -Wno-unused-parameter -Wno-error=int-conversion -Wno-visibility -DHAVE_CONFIG_H \
      -I"$INC" -I"$PA_SRC/src" -I"$HERE" \
      -o "$OUT" "$HERE/$src" \
      -L"$LIBS" -l:libpulsecore-13.0.so -l:libpulsecommon-13.0.so -l:libpulse.so $extra
  "$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT"
  # What the daemon looks for, and nothing linked that a device would not have.
  for sym in pa__init pa__done pa__get_author pa__get_description pa__get_usage pa__get_version pa__load_once; do
    "$TOOLCHAIN/llvm-nm" -D --defined-only "$OUT" | grep -q " $sym$" || { echo "ERROR: $OUT does not export $sym"; exit 1; }
  done
  local NEEDED
  NEEDED=$("$TOOLCHAIN/llvm-readelf" -d "$OUT" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')
  echo "$(basename "$OUT") NEEDED: $NEEDED"
  for lib in $NEEDED; do
    case $lib in libpulsecore-13.0.so|libpulsecommon-13.0.so|libpulse.so|libaaudio.so|libc.so|libm.so|libdl.so) ;;
    *) echo "ERROR: unexpected dependency $lib"; exit 1 ;;
    esac
  done
  ls -l "$OUT"
}
build_one module-aaudio-sink.c "$OUTDIR/module-aaudio-sink.so" -laaudio
