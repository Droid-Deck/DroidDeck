#!/usr/bin/env bash
# CI-only full libhybris build, using the upstream pinned cross-toolchain recipe.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out=${1:?output directory required}
mkdir -p "$out"
out=$(cd "$out" && pwd)
. "$root/tools/hybris/source.env"
work=$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-hybris.XXXXXX")
trap 'rm -rf "$work"' EXIT
fetch() {
  git init -q "$3"
  git -C "$3" fetch --depth=1 "$1" "$2"
  git -C "$3" checkout -q --detach FETCH_HEAD
  test "$(git -C "$3" rev-parse HEAD)" = "$2"
}
fetch https://github.com/taowen/libhybris.git "$HYBRIS_REVISION" "$work/libhybris"
fetch https://github.com/taowen/arlinux-rootfs.git "$PROTOCOL_REVISION" "$work/rootfs"
HYBRIS_TLS_SOURCE="$work/libhybris" python3 -m unittest discover \
  -s "$root/tools/tests" -p test_hybris_tls_capacity.py
# The feature-chain regression compiles patched production source. Supply the
# pinned checkout explicitly so component CI cannot silently skip this gate.
HYBRIS_VENDOR_SOURCE="$work/libhybris" python3 -m unittest discover \
  -s "$root/tools/tests" -p test_hybris_vendor_compat.py
HYBRIS_PLACED_SOURCE="$work/libhybris" python3 -m unittest discover \
  -s "$root/tools/tests" -p test_placed_memory_layer.py
python3 -m unittest discover \
  -s "$root/tools/tests" -p test_hybris_mapping_lock.py
python3 -m unittest discover \
  -s "$root/tools/tests" -p test_hybris_inline_shader.py
HYBRIS_CLIP_SOURCE="$work/libhybris" python3 -m unittest discover \
  -s "$root/tools/tests" -p test_hybris_clip_shader.py
export CONTAINER_ENGINE=podman
builder_image="${BUILDER_IMAGE:-}"
if [[ -z "$builder_image" ]]; then
  builder_image="$("$work/libhybris/tools/ensure-builder.sh")"
fi
mkdir -p "$work/vulkan-headers"
"$CONTAINER_ENGINE" run --rm --network none "$builder_image" \
  tar -C /usr/include -cf - vulkan vk_video | tar -C "$work/vulkan-headers" -xf -
HYBRIS_PUSH_SOURCE="$work/libhybris" VULKAN_INCLUDE="$work/vulkan-headers" python3 -m unittest discover \
  -s "$root/tools/tests" -p test_hybris_push_descriptors.py
git -C "$work/libhybris" apply --check "$root/tools/hybris/powervr-hal-version.patch"
git -C "$work/libhybris" apply "$root/tools/hybris/powervr-hal-version.patch"
for patch in "$root"/tools/hybris/patches/*.patch; do
  git -C "$work/libhybris" apply --check "$patch"
  git -C "$work/libhybris" apply "$patch"
done
# Include new source files in the patch shipped with the runtime's licenses.
git -C "$work/libhybris" add --intent-to-add .
export ARLINUX_WSI_PROTOCOL_DIR="$work/rootfs/graphics-protocols"
"$work/libhybris/tools/build-aarch64.sh" --out "$work/build"

ndk="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "$ndk" ]]; then
  echo 'An Android NDK is required to build the BC push-descriptor probes.' >&2
  exit 1
fi
OUT="$work/build" \
  BIONIC_CC="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" \
  "$work/libhybris/tests/baseline/build.sh"
mkdir -p "$out/probes"
cp "$work/build/bundle/probe-glibc" "$work/build/bundle/probe-manifest.json" "$out/probes/"
(cd "$out/probes" && sha256sum probe-glibc probe-manifest.json > SHA256SUMS)

mkdir -p "$work/package/lib" "$work/package/licenses"
python3 "$root/tools/hybris/component_record.py" --write-recipe "$work/package/build-recipe.json"
cp -a "$work/build/install/usr/lib/hybris/." "$work/package/lib/"
find "$work/package/lib" -name '*.la' -delete
rm -rf "$work/package/lib/include" "$work/package/lib/pkgconfig" "$work/package/lib/bin"
# Resolve the wrapper's private dependencies without putting its EGL/Vulkan frontends
# on the session's LD_LIBRARY_PATH. The Linux runtime keeps owning its Mesa loader.
while IFS= read -r -d '' library; do
  patchelf --set-rpath '$ORIGIN:$ORIGIN/../..' "$library"
done < <(find "$work/package/lib" -type f -name '*.so*' -print0)
for library in libhybris-vulkan-icd.so.0 libhybris-common.so.1 libVkLayer_hybris_compat.so \
    libVkLayer_DROIDDECK_placed_memory.so VkLayer_DROIDDECK_placed_memory.json libhybris/linker/q.so; do
  test -s "$work/package/lib/$library"
done
cp "$work/libhybris"/LICENSE.* "$work/package/licenses/"
cp "$work/build/manifest.json" "$work/package/build-manifest.json"
printf '%s\n' "$HYBRIS_REVISION" > "$work/package/source-commit"
# Ship the exact patched source and protocol license with the runtime.
git -C "$work/libhybris" diff > "$work/package/licenses/powervr.patch"
git -C "$work/libhybris" archive HEAD | zstd -T0 -q -o "$work/package/licenses/libhybris-source.tar.zst"
cp "$ARLINUX_WSI_PROTOCOL_DIR/wayland-android.xml" "$work/package/licenses/"
(cd "$work/package" && find lib -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
tar --zstd -cf "$out/runtime.tzst" -C "$work/package" .
sha256sum "$out/runtime.tzst" | cut -d' ' -f1 > "$out/version.txt"
printf 'Built libhybris %s for Android HAL access from AArch64 Linux\n' "$HYBRIS_REVISION"
