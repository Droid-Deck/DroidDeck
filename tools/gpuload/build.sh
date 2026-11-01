#!/usr/bin/env bash
# Builds VK_LAYER_DROIDDECK_gpu_load for the arm64 guest: tools/gpuload/build.sh [--native] OUT
# OUT gets libVkLayer_droiddeck_gpuload.so and its implicit-layer manifest.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
. "$repo/tools/docker_platform.sh"

native=0
if [ "${1:-}" = --native ]; then native=1; shift; fi
out=${1:?usage: tools/gpuload/build.sh [--native] OUT}
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
[ -n "$vkinc" ] && [ -f "$vkinc/vulkan/vulkan_core.h" ] || { echo "tools/gpuload/build.sh: no Vulkan headers (set VK_INCLUDE)" >&2; exit 1; }

work="$repo/build/gpuload"
rm -rf "$work" && mkdir -p "$work/include" "$work/out"
cp -R "$vkinc/vulkan" "$work/include/"
if [ -d "$vkinc/vk_video" ]; then cp -R "$vkinc/vk_video" "$work/include/"; fi

cmd=(sh -c '
    set -e
    cc -std=c11 -O2 -Wall -Wextra -Werror -Itools/gpuload/src -o build/gpuload/test_gpuload_busy tools/gpuload/tests/test_gpuload_busy.c
    build/gpuload/test_gpuload_busy
    cc -std=c11 -O2 -Wall -Wextra -Werror -Ibuild/gpuload/include -Itools/gpuload/src -o build/gpuload/test_gpuload_ring tools/gpuload/tests/test_gpuload_ring.c
    build/gpuload/test_gpuload_ring
    aarch64-linux-gnu-gcc -std=c11 -shared -fPIC -O2 -Wall -Wextra -Werror -Wno-unused-parameter -Wno-missing-field-initializers \
        -fvisibility=hidden -pthread -Wl,-z,defs -Ibuild/gpuload/include -Itools/gpuload/src \
        -o build/gpuload/out/libVkLayer_droiddeck_gpuload.so tools/gpuload/src/vk_layer_droiddeck_gpuload.c
    aarch64-linux-gnu-strip --strip-unneeded build/gpuload/out/libVkLayer_droiddeck_gpuload.so')
if [ "$native" = 1 ]; then
    (cd "$repo" && "${cmd[@]}")
else
    docker run --rm --platform "${DROIDDECK_DOCKER_PLATFORM:-linux/amd64}" --user "$(id -u):$(id -g)" -v "$repo:/src" -w /src \
        "${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v3}" "${cmd[@]}"
fi

cp "$here/layer/VK_LAYER_DROIDDECK_gpu_load.json" "$work/out/"
mkdir -p "$out"
cp "$work/out/libVkLayer_droiddeck_gpuload.so" "$work/out/VK_LAYER_DROIDDECK_gpu_load.json" "$out/"
echo "built $out"
