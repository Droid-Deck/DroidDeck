#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)

native=0
if [ "${1:-}" = --native ]; then native=1; shift; fi
out=${1:-$here/out}
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac

vkinc=${VK_INCLUDE:-}
if [ -z "$vkinc" ]; then
    for ndk in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_LATEST_HOME:-}" \
               $(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/ndk/* 2>/dev/null | sort -r); do
        [ -n "$ndk" ] || continue
        cand=$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/sysroot/usr/include 2>/dev/null | head -1)
        if [ -n "$cand" ] && [ -f "$cand/vulkan/vk_layer.h" ]; then vkinc=$cand; break; fi
    done
fi
[ -n "$vkinc" ] && [ -f "$vkinc/vulkan/vulkan_core.h" ] || { echo "tools/vr/build.sh: no Vulkan headers (set VK_INCLUDE)" >&2; exit 1; }

work="$repo/build/vr"
rm -rf "$work" && mkdir -p "$work/include"
cp -R "$vkinc/vulkan" "$work/include/"
if [ -d "$vkinc/vk_video" ]; then cp -R "$vkinc/vk_video" "$work/include/"; fi
mkdir -p "$work/out/droiddeck/bin/linuxarm64" "$work/out/layer"

compile() {
    local cmd=(sh -c '
        set -e
        mkdir -p build/vr/stub
        aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wl,-soname,libwayland-client.so.0 -Itools/vr/third_party/wayland \
            -o build/vr/stub/libwayland-client.so tools/vr/src/wayland_client_stub.c
        aarch64-linux-gnu-gcc -c -fPIC -O2 -Itools/vr/third_party/wayland -o build/vr/xdg-shell-protocol.o \
            app/src/main/cpp/waylandcomp/generated/xdg-shell-protocol.c
        aarch64-linux-gnu-g++ -std=c++17 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers \
            -fvisibility=hidden -static-libstdc++ -static-libgcc -pthread -Wl,-z,defs -Ibuild/vr/include \
            -Itools/vr/third_party/wayland -Iapp/src/main/cpp/waylandcomp/generated \
            -o build/vr/out/droiddeck/bin/linuxarm64/driver_droiddeck.so tools/vr/src/driver_droiddeck.cpp tools/vr/src/presenter.cpp \
            build/vr/xdg-shell-protocol.o -Lbuild/vr/stub -lwayland-client -ldl
        aarch64-linux-gnu-gcc -std=c11 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -pthread \
            -Wl,-z,defs -Ibuild/vr/include -o build/vr/out/layer/libVkLayer_droiddeck_vr.so tools/vr/src/vk_layer_droiddeck_vr.c -ldl
        aarch64-linux-gnu-strip --strip-unneeded build/vr/out/droiddeck/bin/linuxarm64/driver_droiddeck.so \
            build/vr/out/layer/libVkLayer_droiddeck_vr.so')
    if [ "$native" = 1 ]; then
        (cd "$repo" && "${cmd[@]}")
    else
        docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" -v "$repo:/src" -w /src \
            "${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v3}" "${cmd[@]}"
    fi
}
compile

cp -R "$here/driver_droiddeck/." "$work/out/droiddeck/"
cp "$here/layer/VK_LAYER_DROIDDECK_vr.json" "$here/layer/VK_LAYER_DROIDDECK_vr_implicit.json" "$work/out/layer/"
rm -rf "$out/droiddeck" "$out/layer"
mkdir -p "$out"
cp -R "$work/out/droiddeck" "$work/out/layer" "$out/"
echo "built $out/droiddeck and $out/layer"
