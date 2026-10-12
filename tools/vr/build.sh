#!/usr/bin/env bash
# Builds the SteamVR driver (linuxarm64) into tools/vr/out/droiddeck with the local cross image,
# the same one tools/build_local.sh builds the preloads with.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
image=${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v3}
out="$here/out/droiddeck"
rm -rf "$out" && mkdir -p "$out/bin/linuxarm64"
cp -R "$here/driver_droiddeck/." "$out/"
docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" -v "$repo:/src" -w /src "$image" \
    aarch64-linux-gnu-g++ -std=c++17 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter \
    -fvisibility=hidden -static-libstdc++ -static-libgcc -pthread \
    -o tools/vr/out/droiddeck/bin/linuxarm64/driver_droiddeck.so tools/vr/src/driver_droiddeck.cpp
# The Vulkan layer for vrcompositor; Vulkan headers from the pinned NDK (the cross image has none).
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
vkinc=$(ls -d "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include 2>/dev/null | head -1)
[ -n "$vkinc" ] || { echo "no NDK Vulkan headers under $sdk/ndk" >&2; exit 1; }
rm -rf "$here/out/vkinclude" && mkdir -p "$here/out/vkinclude" && cp -R "$vkinc/vulkan" "$vkinc/vk_video" "$here/out/vkinclude/" 2>/dev/null || cp -R "$vkinc/vulkan" "$here/out/vkinclude/"
mkdir -p "$here/out/layer"
cp "$here/layer/VK_LAYER_DROIDDECK_vr.json" "$here/out/layer/"
docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" -v "$repo:/src" -w /src "$image" \
    aarch64-linux-gnu-gcc -std=c11 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -pthread \
    -Itools/vr/out/vkinclude -o tools/vr/out/layer/libVkLayer_droiddeck_vr.so tools/vr/src/vk_layer_droiddeck_vr.c
echo "built $out and $here/out/layer"
