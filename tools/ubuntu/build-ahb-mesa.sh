#!/bin/sh
set -eu
src=${1:-/build/mesa-p2/mesa-26.0.8}
out=${2:-/build/mesa-out}
for patchfile in /repo/tools/ubuntu/patches/mesa/*.patch; do
    if patch -d "$src" -p1 --dry-run --forward < "$patchfile" >/dev/null 2>&1; then
        patch -d "$src" -p1 --forward < "$patchfile"
    elif ! patch -d "$src" -p1 --dry-run --reverse < "$patchfile" >/dev/null 2>&1; then
        echo "Incompatible Mesa patch: $patchfile" >&2
        exit 1
    fi
done
python3 /repo/tools/ubuntu/mesa-ahb.py "$src"
if [ ! -f "$out/build.ninja" ]; then
    CC=/opt/cross/cc CXX=/opt/cross/cxx meson setup "$out" "$src" --buildtype=release \
    -Dplatforms=wayland,x11 -Dgallium-drivers= -Dvulkan-drivers=freedreno \
    -Dfreedreno-kmds=msm,kgsl -Dllvm=disabled -Dglx=disabled -Degl=disabled \
    -Dgbm=disabled -Dgles1=disabled -Dgles2=disabled -Dbuild-tests=false \
    -Dvideo-codecs= -Dvulkan-beta=true
fi
ninja -C "$out" -j6 src/freedreno/vulkan/libvulkan_freedreno.so
