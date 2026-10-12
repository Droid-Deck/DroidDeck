#!/usr/bin/env bash
# Builds the Cardboard VR pieces for the guest (linuxarm64) into tools/vr/out with the local cross
# image, the same one tools/build_local.sh builds the preloads with:
#   out/droiddeck   the SteamVR driver (driver_droiddeck.so + manifest + settings)
#   out/layer       VK_LAYER_DROIDDECK_vr, the Vulkan layer SteamVR's compositor needs on Turnip/KGSL
# Vulkan headers come from the pinned NDK (the cross image has none).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
image=${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v3}
out="$here/out"

sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
vkinc=$(ls -d "$sdk"/ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include 2>/dev/null | head -1)
[ -n "$vkinc" ] || { echo "no NDK Vulkan headers under $sdk/ndk" >&2; exit 1; }

rm -rf "$out/droiddeck" "$out/layer" "$out/vkinclude"
mkdir -p "$out/droiddeck/bin/linuxarm64" "$out/layer" "$out/vkinclude"
cp -R "$here/driver_droiddeck/." "$out/droiddeck/"
cp "$here/layer/VK_LAYER_DROIDDECK_vr.json" "$out/layer/"
cp -R "$vkinc/vulkan" "$out/vkinclude/"
if [ -d "$vkinc/vk_video" ]; then cp -R "$vkinc/vk_video" "$out/vkinclude/"; fi

run() { docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" -v "$repo:/src" -w /src "$image" "$@"; }

# -z defs: an unresolved symbol fails the build here rather than the driver load in vrserver.
run aarch64-linux-gnu-g++ -std=c++17 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers \
    -fvisibility=hidden -static-libstdc++ -static-libgcc -pthread -Wl,-z,defs -Itools/vr/out/vkinclude \
    -o tools/vr/out/droiddeck/bin/linuxarm64/driver_droiddeck.so tools/vr/src/driver_droiddeck.cpp tools/vr/src/presenter.cpp -ldl
run aarch64-linux-gnu-gcc -std=c11 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -pthread \
    -Wl,-z,defs -Itools/vr/out/vkinclude -o tools/vr/out/layer/libVkLayer_droiddeck_vr.so tools/vr/src/vk_layer_droiddeck_vr.c -ldl
echo "built $out/droiddeck and $out/layer"
