#!/bin/bash
# Builds the Linux side of the bridge for the runtime (glibc, aarch64):
#   tools/vkbridge/build-client-linux.sh <linuxfs-asset-dir> [cc]
# Produces, under the asset dir (staged into the runtime by SessionFiles):
#   usr/local/lib/vkbridge/libvulkan_droidbridge.so   the Vulkan ICD
#   usr/local/share/vkbridge/droidbridge_icd.json     its manifest
#   usr/local/bin/droiddeck-vkbridge-smoke            the session-start self-test
set -euo pipefail
d=$1
cc=${2:-aarch64-linux-gnu-gcc}
strip=${STRIP:-${cc%gcc}strip}
here=$(cd "$(dirname "$0")" && pwd)
inc="-I$here/third_party/Vulkan-Headers-1.4.341/include -I$here/common -I$here/common/gen"
mkdir -p "$d/usr/local/lib/vkbridge" "$d/usr/local/share/vkbridge" "$d/usr/local/bin"
"$cc" -O2 -fPIC -shared -fvisibility=hidden -DVKB_CLIENT -Wall -Wno-unused-parameter -Wno-missing-field-initializers \
    $inc -I"$here/client" -I"$here/client/compat" -pthread \
    -Wl,--no-undefined -Wl,-soname,libvulkan_droidbridge.so \
    -o "$d/usr/local/lib/vkbridge/libvulkan_droidbridge.so" \
    "$here"/client/*.c "$here/common/vkb_wire.c" "$here/common/gen/vkb_gen_structs.c" "$here/common/gen/vkb_gen_client.c" -ldl
"$strip" --strip-unneeded "$d/usr/local/lib/vkbridge/libvulkan_droidbridge.so"
"$cc" -O2 -Wall $inc -I"$here/tests" -o "$d/usr/local/bin/droiddeck-vkbridge-smoke" "$here/tests/smoke.c" -ldl
"$strip" --strip-unneeded "$d/usr/local/bin/droiddeck-vkbridge-smoke"
cat > "$d/usr/local/share/vkbridge/droidbridge_icd.json" <<JSON
{"file_format_version": "1.0.0", "ICD": {"library_path": "/usr/local/lib/vkbridge/libvulkan_droidbridge.so", "api_version": "1.4.341"}}
JSON
echo "built the vkbridge client into $d"
