#!/usr/bin/env bash
#
# Builds the DroidDeck runtime smoke-test artifact.
#
# The full runtime is an Arch Linux ARM rootfs with Steam, Proton and a
# desktop, which is far too big to build here. This artifact is the
# smallest thing that exercises the whole chain the port owns:
#
#   download -> unpack -> recognise as installed -> launch -> capture output
#
# It carries the same layout the supervisor looks for, and a statically
# linked guest program that runs on any Linux kernel, so installing it on a
# phone answers the question that matters first: does the HarmonyOS sandbox
# let the guest run at all?
#
# Usage:
#   OHOS_SDK_HOME=/path/to/openharmony-sdk tools/build_smoke_rootfs.sh

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

: "${OHOS_SDK_HOME:?set OHOS_SDK_HOME to the OpenHarmony SDK root}"
NDK="${OHOS_SDK_HOME}/native"
CC="${NDK}/llvm/bin/clang"

if [ ! -x "${CC}" ]; then
  echo "the OHOS NDK compiler was not found at ${CC}" >&2
  exit 1
fi

work="${WORK_DIR:-${TMPDIR:-/tmp}/droiddeck-smoke}"
version="0.6.9-harmony-smoke"

rm -rf "${work}"
mkdir -p "${work}/rootfs"

echo "==> building the guest session"
"${CC}" -target aarch64-linux-ohos --sysroot="${NDK}/sysroot" -D__MUSL__ \
  -static -O2 -Wall \
  -o "${work}/rootfs/usr_local_bin_droiddeck-session" \
  "${root}/tools/guest/session_smoke.c"

echo "==> laying out the rootfs"
mkdir -p "${work}/rootfs/etc/droiddeck/empty" \
         "${work}/rootfs/usr/bin" \
         "${work}/rootfs/usr/local/bin" \
         "${work}/rootfs/usr/share/vulkan/icd.d" \
         "${work}/rootfs/run" \
         "${work}/rootfs/etc/droiddeck"

mv "${work}/rootfs/usr_local_bin_droiddeck-session" \
   "${work}/rootfs/usr/local/bin/droiddeck-session"
chmod 0755 "${work}/rootfs/usr/local/bin/droiddeck-session"

# Upstream keeps the runtime's version in /.version at the root of the
# rootfs, and writes it after the archive has been extracted.
printf '%s' "${version}" > "${work}/rootfs/.version"
printf '%s' "${version}" > "${work}/rootfs/etc/droiddeck/version"

# The supervisor treats the runtime as installed once gamescope and the
# session script are both present.
printf '#!/bin/sh\nexit 0\n' > "${work}/rootfs/usr/bin/gamescope"
chmod 0755 "${work}/rootfs/usr/bin/gamescope"

cat > "${work}/rootfs/usr/share/vulkan/icd.d/panvk_icd_aarch64.json" <<'EOF'
{
  "file_format_version": "1.0.0",
  "ICD": {
    "library_path": "libvulkan_panvk.so",
    "api_version": "1.3.0"
  }
}
EOF

# The guest is statically linked, so it needs no loader and no libraries.
mkdir -p "${work}/rootfs/lib" "${work}/rootfs/usr/lib"

echo "==> packing the artifact"
archive="${root}/build/droiddeck-runtime-smoke-arm64.tar.gz"
mkdir -p "$(dirname "${archive}")"
tar -czf "${archive}" -C "${work}/rootfs" .

echo "==> done"
ls -la "${archive}"
echo
echo "Layout:"
tar -tzf "${archive}" | sed 's/^/  /'