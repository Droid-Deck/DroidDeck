#!/usr/bin/env bash
# The rooted session's chroot runner: the small C program (app/src/main/cpp/rootrun) that mounts the
# guest's filesystems, chroots and execs. It is built here rather than by the app's CMake so that the
# artifact is a known one, and installed under the same lib*.so name the native library directory
# uses - an Android app can only execute what the package manager has extracted there.
#
# usage: build.sh <output directory>
set -euo pipefail
OUTDIR=$(mkdir -p "$1" && cd "$1" && pwd)
: "${NDK:?set NDK to the Android NDK root}"
API=26
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../app/src/main/cpp/rootrun/rootrun.c"
case "$(uname -s):$(uname -m)" in
  Darwin:*) NDK_HOST=darwin-x86_64 ;;
  Linux:x86_64) NDK_HOST=linux-x86_64 ;;
  Linux:aarch64|Linux:arm64) NDK_HOST=linux-aarch64 ;;
  *) echo "Unsupported build host: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
if [[ ! -d "$TOOLCHAIN" && -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
if [[ ! -x "$CC" ]]; then
  echo "Android NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi
# Dynamic, not static: a static link emits a TLS segment at 8-byte alignment, which Bionic refuses
# on arm64 ("executable's TLS segment is underaligned ... needs to be at least 64") - the runner
# would not start at all. A dynamic link has no TLS segment, and libc is on the device already.
# 16 KB max-page-size, like every other library here: a 16 KB-page kernel refuses a 4 KB-only ELF,
# and a 4 KB kernel loads an aligned one unchanged.
"$CC" -O2 -Wall -Wextra -Wl,-z,max-page-size=16384 -o "$OUTDIR/librootrun.so" "$SRC"
"$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUTDIR/librootrun.so"
NEEDED=$("$TOOLCHAIN/llvm-readelf" -d "$OUTDIR/librootrun.so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')
echo "librootrun.so NEEDED: ${NEEDED:-none}"
for lib in $NEEDED; do
  case $lib in libc.so|libdl.so|libm.so) ;;
  *) echo "ERROR: unexpected dependency $lib" >&2; exit 1 ;;
  esac
done
# Bionic refuses an executable with an underaligned TLS segment; a dynamic link must not have one.
if "$TOOLCHAIN/llvm-readelf" -l "$OUTDIR/librootrun.so" | grep -q "^  TLS"; then
  echo "ERROR: librootrun.so has a TLS segment; Bionic will refuse it on arm64" >&2
  exit 1
fi
ls -l "$OUTDIR/librootrun.so"
