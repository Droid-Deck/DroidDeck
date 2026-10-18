#!/usr/bin/bash
# Runs inside an Arch Linux ARM container (menci/archlinuxarm:base-devel) on an arm64 runner. Arch Linux ARM ships
# no FEX package and FEX publishes no Linux binaries.
set -euxo pipefail
VERSION=${FEX_VERSION:-FEX-2610}
WORK=/work
cd "$WORK"
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
{ for m in https://ca.us.mirror.archlinuxarm.org https://fl.us.mirror.archlinuxarm.org https://de3.mirror.archlinuxarm.org https://nl.mirror.archlinuxarm.org; do echo "Server = $m/\$arch/\$repo"; done; cat /etc/pacman.d/mirrorlist; } > /etc/pacman.d/mirrorlist.new
mv /etc/pacman.d/mirrorlist.new /etc/pacman.d/mirrorlist
pacman -Syu --noconfirm --needed git zstd binutils cmake ninja clang lld llvm python squashfs-tools file patchelf xxhash

# The x86 sysroot is Arch x86_64 and multilib i686 packages unpacked by pacman, never run: no scriptlets or signature checks.
THUNKS=${FEX_THUNKS:-0}
X86ROOT=$WORK/x86root
if [ "$THUNKS" = 1 ]; then
  pacman -S --noconfirm --needed pkgconf libx11 libxcb libxrandr libxrender libxext xorgproto \
    libglvnd mesa wayland libdrm alsa-lib libxshmfence vulkan-headers vulkan-icd-loader
  rm -rf "$X86ROOT" && mkdir -p "$X86ROOT/var/lib/pacman" "$WORK/x86-cache"
  cat > "$WORK/x86-pacman.conf" <<'CONF'
[options]
Architecture = x86_64
SigLevel = Never
DisableSandbox
[core]
Server = https://geo.mirror.pkgbuild.com/$repo/os/$arch
[extra]
Server = https://geo.mirror.pkgbuild.com/$repo/os/$arch
[multilib]
Server = https://geo.mirror.pkgbuild.com/$repo/os/$arch
CONF
  pacman --config "$WORK/x86-pacman.conf" --root "$X86ROOT" --dbpath "$X86ROOT/var/lib/pacman" \
    --cachedir "$WORK/x86-cache" --noscriptlet --noconfirm --overwrite '*' -Sy \
    glibc linux-api-headers gcc gcc-libs libx11 libxcb xorgproto libxrandr libxrender libxext libxau \
    libxdmcp libglvnd mesa wayland libdrm alsa-lib libxshmfence \
    lib32-glibc lib32-gcc-libs lib32-libx11 lib32-libxcb lib32-libxrandr lib32-libxrender \
    lib32-libxext lib32-libglvnd lib32-mesa lib32-wayland lib32-libdrm lib32-alsa-lib lib32-libxshmfence
  ls "$X86ROOT/usr/include" | head -5
fi

rm -rf fex-src out && git clone -q --depth 1 --branch "$VERSION" --recurse-submodules --shallow-submodules https://github.com/FEX-Emu/FEX.git fex-src
for p in tools/fex/patches/*.patch; do patch -d fex-src -p1 --no-backup-if-mismatch < "$p"; done
# FEX's x86 toolchain files assume an x86 build machine; here the guest thunks need the x86 sysroot too.
# Arch's clang adds stack protection; its canary load in a guest thunk's prologue clobbered r11 (the host function).
if [ "$THUNKS" = 1 ]; then
  for t in fex-src/Data/CMake/toolchain_x86_64.cmake fex-src/Data/CMake/toolchain_x86_32.cmake; do
    printf '\nif (X86_DEV_ROOTFS AND NOT X86_DEV_ROOTFS STREQUAL "/")\n  set(CMAKE_SYSROOT "${X86_DEV_ROOTFS}")\nendif()\n' >> "$t"
    printf '\nset(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-stack-protector")\nset(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-stack-protector")\n' >> "$t"
  done
fi
# TUNE_CPU none: the default (native) would tune for the runner's Neoverse cores, not the device.
cmake -S fex-src -B fex-build -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr/local \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DUSE_LINKER=lld \
  -DTUNE_CPU=none -DTUNE_ARCH=armv8-a \
  -DBUILD_TESTING=OFF -DBUILD_FEXCONFIG=OFF -DENABLE_ASSERTIONS=OFF \
  $( [ "$THUNKS" = 1 ] && echo "-DBUILD_THUNKS=ON -DX86_DEV_ROOTFS=$X86ROOT" || echo "-DBUILD_THUNKS=OFF" ) \
  -DENABLE_CCACHE=OFF -DENABLE_OFFLINE_TELEMETRY=OFF \
  ${FEX_EXTRA_CXXFLAGS:+-DCMAKE_CXX_FLAGS="$FEX_EXTRA_CXXFLAGS"}
ninja -C fex-build
DESTDIR="$WORK/out" ninja -C fex-build install
install -Dm755 /usr/bin/unsquashfs out/usr/local/bin/unsquashfs
# The Linux runtime has no xxhash and no ld.so.cache: the library goes beside libFEXCore and the programs
# that need it find it through RUNPATH.
install -Dm644 "$(readlink -f /usr/lib/libxxhash.so.0)" out/usr/local/lib/libxxhash.so.0
bundled=$(cd out/usr/local/lib && ls -- *.so* | sort -u)
for f in out/usr/local/bin/*; do
  [ -L "$f" ] && continue
  file "$f" | grep -q ELF || continue
  readelf -d "$f" | awk '/NEEDED/ { gsub(/[\[\]]/, "", $5); print $5 }' | grep -qxF "$bundled" \
    && patchelf --set-rpath '$ORIGIN/../lib' "$f"
done
# Every NEEDED soname must resolve in a stock guest: bundled here or provided by the runtime (runtime-libs.txt).
for f in out/usr/local/bin/* out/usr/local/lib/*.so* out/usr/local/lib/fex-emu/HostThunks*/*.so; do
  [ -f "$f" ] && file "$f" | grep -q ELF || continue
  readelf -d "$f" | awk '/NEEDED/ { gsub(/[\[\]]/, "", $5); print $5 }'
done | sort -u | tee fex-needed.txt
missing=$(printf '%s\n' "$bundled" | cat - tools/fex/runtime-libs.txt | sort -u | comm -23 fex-needed.txt -)
[ -z "$missing" ] || { echo "not in the Linux runtime or this package:" $missing >&2; exit 1; }
for f in out/usr/local/bin/*; do
  [ -L "$f" ] || ! file "$f" | grep -q ELF && continue
  if readelf -d "$f" | awk '/NEEDED/ { gsub(/[\[\]]/, "", $5); print $5 }' | grep -qxF "$bundled"; then
    readelf -d "$f" | grep -q 'RUNPATH.*\$ORIGIN/\.\./lib' || { echo "$f needs a bundled library but has no RUNPATH" >&2; exit 1; }
  fi
done
(cd out && find . -type f -o -type l | sort) | tee fex-files.txt
tar -C out -c . | zstd -19 -T0 -o fex.tzst
sha256sum fex.tzst | tee fex.tzst.sha256
