#!/usr/bin/bash
# Runs INSIDE an Arch Linux ARM container (menci/archlinuxarm:base-devel) on an arm64 runner.
# Builds FEX for the runtime, with tools/fex/patches, (Arch Linux ARM ships no package and FEX publishes no Linux
# binaries), plus the runtime's own unsquashfs to unpack FEX's x86 rootfs image, and packs both
# as fex.tzst (usr/local/...) for droiddeck-steam-x64 to stage. FEX_THUNKS=1 adds the host
# thunks (see below).
set -euxo pipefail
VERSION=${FEX_VERSION:-FEX-2609}
WORK=/work
cd "$WORK"
# Same pacman workarounds as tools/gamescope/build-in-arch.sh.
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
{ for m in https://ca.us.mirror.archlinuxarm.org https://fl.us.mirror.archlinuxarm.org https://de3.mirror.archlinuxarm.org https://nl.mirror.archlinuxarm.org; do echo "Server = $m/\$arch/\$repo"; done; cat /etc/pacman.d/mirrorlist; } > /etc/pacman.d/mirrorlist.new
mv /etc/pacman.d/mirrorlist.new /etc/pacman.d/mirrorlist
pacman -Syu --noconfirm --needed git zstd binutils cmake ninja clang lld llvm python squashfs-tools file

# FEX_THUNKS=1: host thunks too - an x86 program's libvulkan/libGL/libX11/... calls go to the
# runtime's own arm64 libraries (Turnip, Mesa), so the x86-64 client draws on the GPU. The host
# half is arm64 and needs the graphics dev packages; the guest half is cross-compiled with clang
# against an x86 sysroot: Arch Linux x86_64 (and multilib i686) packages unpacked into a root of
# their own by pacman - never run, so no scriptlets and no signature checks.
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
# Our proot answers openat2 with ENOSYS (tools/proot/PATCHES.md, 0008); FEX opened every rootfs
# path with openat2 and fell through to the arm64 guest's files on anything but EXDEV.
for p in tools/fex/patches/*.patch; do patch -d fex-src -p1 --no-backup-if-mismatch < "$p"; done
# FEX's x86 toolchain files assume an x86 build machine: on this arm64 one the guest thunks need the
# x86 sysroot for their headers, C runtime objects and libgcc too, not just the generator.
if [ "$THUNKS" = 1 ]; then
  for t in fex-src/Data/CMake/toolchain_x86_64.cmake fex-src/Data/CMake/toolchain_x86_32.cmake; do
    printf '\nif (X86_DEV_ROOTFS AND NOT X86_DEV_ROOTFS STREQUAL "/")\n  set(CMAKE_SYSROOT "${X86_DEV_ROOTFS}")\nendif()\n' >> "$t"
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
# Nothing outside the runtime's own libraries: every needed soname must resolve in a stock guest.
for f in out/usr/local/bin/*; do
  file "$f" | grep -q ELF || continue
  readelf -d "$f" | awk '/NEEDED/ { gsub(/[\[\]]/, "", $5); print $5 }'
done | sort -u | tee fex-needed.txt
(cd out && find . -type f -o -type l | sort) | tee fex-files.txt
tar -C out -c . | zstd -19 -T0 -o fex.tzst
sha256sum fex.tzst | tee fex.tzst.sha256
