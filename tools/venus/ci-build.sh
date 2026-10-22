#!/bin/bash
# Builds the Mali (Venus) component in CI, from David Cimo's tools/venus recipes (build-venus-icd.sh,
# build-virgl-android.sh, build-layer.sh), into venus.tzst rooted at app/src/main:
#   jniLibs/arm64-v8a/  libvirgl_test_server.so, libvirgl_render_server.so, libvirglrenderer.so,
#                       libepoxy.so (bionic: the vtest server the app runs over the phone's Vulkan)
#   assets/venus/       libvulkan_virtio.so (glibc: Mesa's Venus ICD for the session)
#   assets/linuxfs/usr/local/...  the Mali compatibility layer and its manifest
# Needs: docker (Debian trixie for the glibc parts), the NDK in $ANDROID_NDK_HOME, meson, ninja.
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
W=${WORK:-$PWD/venus-work}
OUT=$W/out
rm -rf "$OUT" && mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/assets/venus" \
  "$OUT/assets/linuxfs/usr/local/lib/droiddeck-mali" "$OUT/assets/linuxfs/usr/local/share/vulkan/implicit_layer.d"

# --- Linux side: Mesa 26.2.4's Venus ICD and the Mali compatibility layer (aarch64 glibc) ---
docker run --rm --platform linux/amd64 -v "$W":/w -v "$REPO/tools/venus":/venus:ro -w /tmp debian:trixie bash -c '
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
dpkg --add-architecture arm64
apt-get update -qq
apt-get install -y -qq --no-install-recommends git ca-certificates curl crossbuild-essential-arm64 meson ninja-build \
  pkg-config python3-mako python3-yaml python3-packaging bison flex glslang-tools libwayland-bin wayland-protocols \
  libdrm-dev:arm64 libwayland-dev:arm64 libx11-dev:arm64 libx11-xcb-dev:arm64 libxcb1-dev:arm64 \
  libxcb-dri3-dev:arm64 libxcb-present-dev:arm64 libxcb-randr0-dev:arm64 libxcb-shm0-dev:arm64 \
  libxcb-sync-dev:arm64 libxcb-xfixes0-dev:arm64 libxshmfence-dev:arm64 libxrandr-dev:arm64 \
  libexpat1-dev:arm64 zlib1g-dev:arm64 libzstd-dev:arm64 >/dev/null
cat > /tmp/arm64.ini <<EOC
[binaries]
c = '"'"'aarch64-linux-gnu-gcc'"'"'
cpp = '"'"'aarch64-linux-gnu-g++'"'"'
ar = '"'"'aarch64-linux-gnu-ar'"'"'
strip = '"'"'aarch64-linux-gnu-strip'"'"'
nm = '"'"'aarch64-linux-gnu-nm'"'"'
pkg-config = '"'"'pkg-config'"'"'
[properties]
pkg_config_libdir = ['"'"'/usr/lib/aarch64-linux-gnu/pkgconfig'"'"', '"'"'/usr/share/pkgconfig'"'"']
[host_machine]
system = '"'"'linux'"'"'
cpu_family = '"'"'aarch64'"'"'
cpu = '"'"'armv8-a'"'"'
endian = '"'"'little'"'"'
EOC
git clone -q --depth 1 -b mesa-26.2.4 https://gitlab.freedesktop.org/mesa/mesa.git
(cd mesa && git apply /venus/mesa-venus-vtest.patch)
meson setup venus-build mesa --cross-file /tmp/arm64.ini --prefix=/usr --libdir=lib \
  --buildtype=release -Db_ndebug=true \
  -Dvulkan-drivers=virtio -Dgallium-drivers= -Dplatforms=x11,wayland \
  -Dllvm=disabled -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dopengl=false -Dgles1=disabled -Dgles2=disabled \
  -Dvideo-codecs= -Dvulkan-layers= -Dtools= -Dbuild-tests=false -Dvalgrind=disabled -Dlibunwind=disabled \
  -Dzstd=enabled -Dshared-glapi=disabled >/dev/null
ninja -C venus-build src/virtio/vulkan/libvulkan_virtio.so
aarch64-linux-gnu-strip --strip-unneeded -o /w/out/assets/venus/libvulkan_virtio.so venus-build/src/virtio/vulkan/libvulkan_virtio.so
echo "venus icd min glibc: $(aarch64-linux-gnu-objdump -T /w/out/assets/venus/libvulkan_virtio.so | grep -o "GLIBC_[0-9.]*" | sort -Vu | tail -1)"
SDK=vulkan-sdk-1.4.357.0
git -c advice.detachedHead=false clone -q --depth 1 -b $SDK https://github.com/KhronosGroup/Vulkan-Headers.git /tmp/vulkan
git -c advice.detachedHead=false clone -q --depth 1 -b $SDK https://github.com/KhronosGroup/SPIRV-Headers.git /tmp/spirv
mkdir -p /tmp/bcdec
curl -sSfL -o /tmp/bcdec/bcdec.h https://raw.githubusercontent.com/iOrange/bcdec/80859ed3b7afb1c527a2a99d70c61457bea72d0c/bcdec.h
L=/w/out/assets/linuxfs/usr/local
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -pthread \
  -I/tmp/vulkan/include -I/tmp/spirv/include -I/tmp/bcdec \
  -o $L/lib/droiddeck-mali/libVkLayer_droiddeck_mali_compat.so /venus/layer/*.c
aarch64-linux-gnu-strip --strip-unneeded $L/lib/droiddeck-mali/libVkLayer_droiddeck_mali_compat.so
cp /venus/layer/VkLayer_droiddeck_mali_compat.json $L/share/vulkan/implicit_layer.d/
chmod -R a+rwX /w/out
'

# --- Android side: libepoxy + virglrenderer 1.3.0 (venus, vtest) for arm64 with the NDK ---
NDK=${ANDROID_NDK_HOME:?}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
API=29
A=$W/android
AOUT=$A/out
mkdir -p "$A/cross" "$A/compat/log" "$A/compat/cutils" "$AOUT/lib/pkgconfig"
cd "$A"
git clone -q --depth 1 -b 1.5.10 https://github.com/anholt/libepoxy.git
git clone -q --depth 1 -b v1.4.365 https://github.com/KhronosGroup/Vulkan-Headers.git
git clone -q --depth 1 -b virglrenderer-1.3.0 https://gitlab.freedesktop.org/virgl/virglrenderer.git
git -C virglrenderer apply "$REPO/tools/venus/virglrenderer-android.patch"
git -C virglrenderer apply "$REPO/tools/venus/virglrenderer-destroy-unknown-pipeline.patch"
git -C virglrenderer apply "$REPO/tools/venus/virglrenderer-destroy-mutex-after-objects.patch"
cat > compat/log/log.h <<'EOC'
#pragma once
#include <android/log.h>
#define LOG_PRI(prio, tag, ...) __android_log_print(prio, tag, __VA_ARGS__)
EOC
cat > compat/cutils/properties.h <<'EOC'
#pragma once
#include <string.h>
#include <sys/system_properties.h>
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#define PROPERTY_KEY_MAX 32
static inline int property_get(const char *key, char *value, const char *def)
{
   int len = __system_property_get(key, value);
   if (len <= 0 && def) { strcpy(value, def); len = (int)strlen(def); }
   return len;
}
EOC
cat > "$AOUT/lib/pkgconfig/vulkan.pc" <<EOC
prefix=$A/Vulkan-Headers
includedir=\${prefix}/include
Name: vulkan
Description: Vulkan headers
Version: 1.4.365
Cflags: -I\${includedir}
Libs: -lvulkan
EOC
cat > cross/android.ini <<EOC
[binaries]
c = '$TC/aarch64-linux-android$API-clang'
cpp = '$TC/aarch64-linux-android$API-clang++'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
pkg-config = '/usr/bin/pkg-config'
[built-in options]
c_args = ['-I$A/Vulkan-Headers/include', '-I$A/compat']
c_link_args = ['-llog', '-lnativewindow', '-Wl,-rpath=\$ORIGIN', '-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-rpath=\$ORIGIN', '-Wl,-z,max-page-size=16384']
[properties]
pkg_config_libdir = '$AOUT/lib/pkgconfig'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOC
export PKG_CONFIG_LIBDIR=$AOUT/lib/pkgconfig PKG_CONFIG_PATH=
meson setup epoxy-build libepoxy --cross-file cross/android.ini --prefix="$AOUT" --libdir=lib \
  --buildtype=release -Degl=yes -Dglx=no -Dx11=false -Dtests=false >/dev/null
ninja -C epoxy-build install >/dev/null
meson setup virgl-build virglrenderer --cross-file cross/android.ini --prefix="$AOUT" --libdir=lib \
  --buildtype=release -Dvenus=true -Dplatforms=auto -Drender-server-worker=thread \
  -Dvulkan-dload=true -Dcheck-gl-errors=false -Dtests=false >/dev/null
ninja -C virgl-build install >/dev/null
J=$OUT/jniLibs/arm64-v8a
cp "$AOUT/bin/virgl_test_server" "$J/libvirgl_test_server.so"
cp "$AOUT/libexec/virgl_render_server" "$J/libvirgl_render_server.so"
cp "$AOUT/lib/libvirglrenderer.so" "$AOUT/lib/libepoxy.so" "$J/"
for f in "$J"/*.so; do "$TC/llvm-strip" --strip-unneeded "$f"; done

cd "$OUT"
find . -type f | sort
tar --owner=0 --group=0 --numeric-owner --sort=name -cf - . | zstd -19 -T0 -q -o "$W/venus.tzst"
sha256sum "$W/venus.tzst" | tee "$W/venus.tzst.sha256"
