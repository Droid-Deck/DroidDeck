#!/bin/bash
# vulkaninfo as the session's Linux processes see the GPU: through Venus, and through the Mali
# compatibility layer when DROIDDECK_MALI_COMPAT is 1. Needs a running session (the vtest server).
# Run from Git Bash: MSYS_NO_PATHCONV=1 bash tools/venus/guest-vulkaninfo.sh [0|1] > vulkaninfo.txt
set -euo pipefail
COMPAT=${1:-1}
A=/c/Users/derab/tools/platform-tools/adb.exe
PKG=com.droiddeck.launcher
F=/data/user/0/$PKG/files
APK=$($A shell pm path $PKG | head -1 | tr -d '\r' | sed 's/^package://')
L=${APK%/base.apk}/lib/arm64
$A exec-out "run-as $PKG sh -c 'PROOT_TMP_DIR=/data/user/0/$PKG/cache PROOT_LOADER=$L/libproot-loader.so \
  $L/libproot.so -r $F/linuxfs -w /root -b /dev -b /proc -b /sys -b $F /usr/bin/env -i HOME=/root \
  PATH=/usr/bin:/bin DROIDDECK_MALI_COMPAT=$COMPAT VK_ICD_FILENAMES=$F/venus/virtio_icd.json VN_DEBUG=vtest \
  VTEST_SOCKET_NAME=$F/venus/vtest.sock vulkaninfo 2>&1'"
