#!/bin/bash
set -euo pipefail

if [ $# -lt 4 ]; then
  echo "usage: $0 <sdk-rootfs> <wine-source> <work-dir> <out-dir> [patch-dir]" >&2
  echo "env: BUILD_DIR (path the build runs at inside the SDK), SYNC_PROFILE (valve or cachyos), JOBS, SOURCE_DATE_EPOCH" >&2
  exit 2
fi
here=$(cd "$(dirname "$0")" && pwd)
sdk=$(realpath "$1")
src=$(realpath "$2")
work=$(realpath -m "$3")
out=$(realpath -m "$4")
patches=${5:+$(realpath "$5")}
build_dir=${BUILD_DIR:-/builds/proton/proton/build-dir}
rm -rf "$work"
mkdir -p "$work" "$out"
"$here/build-pack.sh" prepare "$src" "$work" ${patches:+"$patches"}
bwrap --unshare-user --uid 0 --gid 0 --unshare-ipc --unshare-pid \
  --bind "$sdk" / --proc /proc --dev /dev --tmpfs /tmp \
  --bind "$work" "$build_dir" --bind "$out" /out --ro-bind "$here/build-pack.sh" /build-pack.sh \
  --setenv PATH /opt/llvm-mingw/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  --setenv HOME /tmp --setenv LANG C.UTF-8 --setenv JOBS "${JOBS:-$(nproc)}" \
  --setenv SOURCE_DATE_EPOCH "${SOURCE_DATE_EPOCH:-1700000000}" --setenv SYNC_PROFILE "${SYNC_PROFILE:-valve}" \
  --chdir "$build_dir" \
  /bin/bash /build-pack.sh build "$build_dir" /out
sha256sum "$out/files/lib/wine/aarch64-unix/ntdll.so" "$out/files/bin-arm64/wineserver"
