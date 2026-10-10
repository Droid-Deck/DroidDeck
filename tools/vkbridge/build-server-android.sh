#!/bin/bash
# Builds the bridge server for the device (bionic, arm64-v8a, API 26) with the NDK's clang:
#   tools/vkbridge/build-server-android.sh <ndk-dir> <jniLibs-out-dir>
# The output is an executable named like a library (libvkbridge_server.so) so that Android
# extracts it into the native library directory, the one place an app may exec from.
set -euo pipefail
ndk=$1
out=$2/arm64-v8a
here=$(cd "$(dirname "$0")" && pwd)
srv=$(cd "$here/../../app/src/main/cpp/vkbridge" && pwd)
cc=$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang | head -1)
[ -x "$cc" ] || { echo "no NDK clang under $ndk" >&2; exit 1; }
mkdir -p "$out"
"$cc" -O2 -g0 -fPIE -pie -Wall -Wno-unused-parameter -Wno-missing-field-initializers \
    -I"$here/third_party/Vulkan-Headers-1.4.341/include" -I"$here/common" -I"$here/common/gen" -I"$srv" \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
    -o "$out/libvkbridge_server.so" \
    "$srv"/*.c "$here/common/vkb_wire.c" "$here/common/gen/vkb_gen_structs.c" "$here/common/gen/vkb_gen_server.c" \
    -ldl -llog -lm
"$(dirname "$cc")/llvm-strip" --strip-unneeded "$out/libvkbridge_server.so"
# The same server as a JNI library the app loads into its own process (the default): only there
# does Android hand it the driver the app gets (VkBridgeNative, server_main.c).
"$cc" -O2 -g0 -fPIC -shared -DVKB_JNI -Wall -Wno-unused-parameter -Wno-missing-field-initializers \
    -I"$here/third_party/Vulkan-Headers-1.4.341/include" -I"$here/common" -I"$here/common/gen" -I"$srv" \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,--no-undefined -Wl,-soname,libvkbridge_jni.so \
    -o "$out/libvkbridge_jni.so" \
    "$srv"/*.c "$here/common/vkb_wire.c" "$here/common/gen/vkb_gen_structs.c" "$here/common/gen/vkb_gen_server.c" \
    -ldl -llog -lm
"$(dirname "$cc")/llvm-strip" --strip-unneeded "$out/libvkbridge_jni.so"
echo "built $out/libvkbridge_server.so and libvkbridge_jni.so"
