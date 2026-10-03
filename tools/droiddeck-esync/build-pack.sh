#!/bin/bash
set -euo pipefail

usage() {
  echo "usage: $0 prepare <wine-source> <build-dir> [patch-dir]" >&2
  echo "       $0 build <build-dir> <out-dir>" >&2
  echo "       $0 all <wine-source> <out-dir> [patch-dir]" >&2
  exit 2
}

prepare() {
  local origin=$1 b=$2 patches=${3:-}
  local s=$b/src-wine
  mkdir -p "$s"
  rsync --filter=:C --exclude '*~' --exclude .git --exclude compile_commands.json \
    --exclude configure --exclude autom4te.cache --exclude include/config.h.in --exclude include/config.h.ink \
    --exclude include/wine/vulkan.h --exclude dlls/vulkan-1/vulkan-1.spec \
    --exclude dlls/winevulkan/loader_thunks.c --exclude dlls/winevulkan/loader_thunks.h \
    --exclude dlls/winevulkan/vulkan_thunks.c --exclude dlls/winevulkan/vulkan_thunks.h \
    --exclude dlls/winevulkan/winevulkan.json --exclude dlls/winevulkan/winevulkan.spec \
    --exclude dlls/ntdll/ntsyscalls.h --exclude dlls/win32u/win32syscalls.h \
    --exclude include/wine/server_protocol.h --exclude server/request_handlers.h --exclude server/request_trace.h \
    -Oarx --delete "$origin/" "$s/"
  for f in include/wine/server_protocol.h server/request_handlers.h server/request_trace.h; do
    cp -a "$origin/$f" "$s/$f"
  done
  if [ -n "$patches" ]; then
    local p found=0
    for p in "$patches"/*.patch; do
      [ -f "$p" ] || continue
      patch -d "$s" -p1 --forward --no-backup-if-mismatch -s < "$p"
      found=1
    done
    [ "$found" = 1 ] || { echo "no patches in $patches" >&2; exit 1; }
  fi
  (
    cd "$s"
    XDG_CACHE_HOME=$b/cache dlls/winevulkan/make_vulkan -x vk.xml -X video.xml
    tools/make_specfiles || true
    tools/make_requests
  ) > "$b/generate.log" 2>&1
  touch "$b/.prepared"
}

build() {
  local b=$1 out=$2
  local s=$b/src-wine o=$b/obj-wine-aarch64 d=$b/dst-wine-aarch64
  [ -f "$b/.prepared" ] || { echo "$b is not prepared" >&2; exit 1; }
  local jobs=${JOBS:-$(nproc)}
  local epoch=$(( ${SOURCE_DATE_EPOCH:-1700000000} - 20 ))
  local flags args pe_common i386_flags x86_64_flags conf
  case ${SYNC_PROFILE:-valve} in
    valve)
      flags="-Wno-discarded-qualifiers -march=armv8.2-a -mtune=cortex-x3 -O2 -fwrapv -fno-strict-aliasing -ggdb -ffunction-sections -fdata-sections -fno-omit-frame-pointer -ffile-prefix-map=$s=."
      args=(--enable-werror --with-mingw=clang --disable-tests --enable-archs=arm64ec,aarch64,i386,x86_64)
      pe_common="-O2 -fwrapv -fno-strict-aliasing -ggdb -ffunction-sections -fdata-sections -fno-omit-frame-pointer -ffile-prefix-map=$s=."
      i386_flags="-Wno-discarded-qualifiers -mstackrealign -march=nocona -mtune=core-avx2 -mfpmath=sse $pe_common"
      x86_64_flags="-Wno-discarded-qualifiers -mcmodel=small -march=nocona -mtune=core-avx2 -mfpmath=sse $pe_common"
      conf="../$(basename "$s")/configure"
      ;;
    cachyos)
      flags="-Wno-discarded-qualifiers -Wno-stringop-overflow -Wno-incompatible-pointer-types -O2 -march=armv8.2-a -mtune=cortex-x3 -fwrapv -fno-strict-aliasing -ffunction-sections -fdata-sections -fno-omit-frame-pointer -ffile-prefix-map=$s=."
      args=(--with-mingw=clang --enable-build-id --disable-tests --enable-archs=arm64ec,aarch64,i386,x86_64)
      pe_common="-fwrapv -fno-strict-aliasing -ffunction-sections -fdata-sections -fno-omit-frame-pointer -ffile-prefix-map=$s=."
      i386_flags="-Wno-discarded-qualifiers -Wno-stringop-overflow -Wno-incompatible-pointer-types -mstackrealign -O2 -march=nocona -mtune=core-avx2 -pipe -mfpmath=sse $pe_common"
      x86_64_flags="-Wno-discarded-qualifiers -Wno-stringop-overflow -Wno-incompatible-pointer-types -mcmodel=small -O2 -march=nocona -mtune=core-avx2 -pipe -mfpmath=sse $pe_common"
      conf="$s/configure"
      ;;
    *)
      echo "unknown SYNC_PROFILE ${SYNC_PROFILE}" >&2
      exit 2
      ;;
  esac
  local env=(STRIP=llvm-strip AR=aarch64-linux-gnu-ar RANLIB=aarch64-linux-gnu-ranlib
    CC=aarch64-linux-gnu-gcc CXX=aarch64-linux-gnu-g++ LD=aarch64-linux-gnu-ld
    RC=aarch64-w64-mingw32-windres WIDL=aarch64-w64-mingw32-widl PKG_CONFIG=aarch64-linux-gnu-pkg-config
    "PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig"
    "CFLAGS=$flags" "CPPFLAGS=$flags" "CXXFLAGS=-std=c++17 $flags" "LDFLAGS=" "SOURCE_DATE_EPOCH=$epoch"
    "CROSSCFLAGS=$flags" "CROSSLDFLAGS="
    "aarch64_CFLAGS=$flags" "aarch64_CPPFLAGS=$flags" "aarch64_CXXFLAGS=-std=c++17 $flags" "aarch64_LDFLAGS="
    i386_AR=i686-w64-mingw32-ar i386_RANLIB=i686-w64-mingw32-ranlib i386_CC=i686-w64-mingw32-gcc
    i386_CXX=i686-w64-mingw32-g++ i386_LD=i686-w64-mingw32-ld
    "i386_CFLAGS=$i386_flags" "i386_CPPFLAGS=$i386_flags" "i386_CXXFLAGS=-std=c++17 $i386_flags" "i386_LDFLAGS="
    "i386_PKG_CONFIG_LIBDIR=/usr/lib/i386-w64-mingw32/pkgconfig:/usr/share/pkgconfig"
    x86_64_AR=x86_64-w64-mingw32-ar x86_64_RANLIB=x86_64-w64-mingw32-ranlib x86_64_CC=x86_64-w64-mingw32-gcc
    x86_64_CXX=x86_64-w64-mingw32-g++ x86_64_LD=x86_64-w64-mingw32-ld
    "x86_64_CFLAGS=$x86_64_flags" "x86_64_CPPFLAGS=$x86_64_flags" "x86_64_CXXFLAGS=-std=c++17 $x86_64_flags" "x86_64_LDFLAGS="
    "x86_64_PKG_CONFIG_LIBDIR=/usr/lib/x86_64-w64-mingw32/pkgconfig:/usr/share/pkgconfig")
  export PATH=/opt/llvm-mingw/bin:$PATH
  ( cd "$s" && autoreconf -fi && rm -rf autom4te.cache ) > "$b/autoreconf.log" 2>&1
  rm -rf "$o" "$d"
  mkdir -p "$o" "$d"
  ( cd "$o" && env "${env[@]}" "$conf" -C --prefix="$d" --libdir="$d/lib" --host=aarch64-linux-gnu "${env[@]}" \
      "${args[@]}" ) > "$b/configure.log" 2>&1
  ( cd "$o" && env "${env[@]}" make -j"$jobs" __tooldeps__ ) > "$b/tools.log" 2>&1
  ( cd "$o" && env "${env[@]}" make -j"$jobs" dlls/ntdll/ntdll.so server/wineserver ) > "$b/make.log" 2>&1
  mkdir -p "$out/files/lib/wine/aarch64-unix" "$out/files/bin-arm64"
  llvm-objcopy --strip-debug --set-section-flags .text=contents,alloc,load,readonly,code \
    "$o/dlls/ntdll/ntdll.so" "$out/files/lib/wine/aarch64-unix/ntdll.so"
  llvm-objcopy --strip-debug "$o/server/wineserver" "$out/files/bin-arm64/wineserver"
  chmod 0755 "$out/files/lib/wine/aarch64-unix/ntdll.so" "$out/files/bin-arm64/wineserver"
  cp "$s/include/wine/server_protocol.h" "$s/server/request_handlers.h" "$s/server/request_trace.h" "$out/"
  sed -n 's/^#define SERVER_PROTOCOL_VERSION \([0-9]*\)$/\1/p' "$out/server_protocol.h" > "$out/protocol"
}

[ $# -ge 1 ] || usage
case $1 in
  prepare)
    [ $# -ge 3 ] || usage
    prepare "$(realpath "$2")" "$(realpath -m "$3")" "${4:+$(realpath "$4")}"
    ;;
  build)
    [ $# -eq 3 ] || usage
    build "$(realpath "$2")" "$(realpath -m "$3")"
    ;;
  all)
    [ $# -ge 3 ] || usage
    b=${BUILD_DIR:-/builds/proton/proton/build-dir}
    prepare "$(realpath "$2")" "$b" "${4:+$(realpath "$4")}"
    build "$b" "$(realpath -m "$3")"
    ;;
  *)
    usage
    ;;
esac
