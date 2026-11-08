#!/usr/bin/env bash
# Builds libddnatv-hook.so, the library that lets the glibc rootfs run on Android without proot.
# usage: tools/nativeguest/build.sh <output.so> [compiler, default aarch64-linux-gnu-gcc]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:?output path}
cc=${2:-aarch64-linux-gnu-gcc}
"$cc" -std=gnu11 -shared -fPIC -O2 -fno-delete-null-pointer-checks -Wall -Wno-nonnull-compare \
    -Wno-format-truncation -Wno-stringop-truncation -Wl,-z,max-page-size=16384 \
    -o "$out" "$here/ddnatv-hook.c" -ldl
"${cc%gcc}strip" --strip-unneeded "$out"
