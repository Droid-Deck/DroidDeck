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
git -C "$work/libhybris" apply --check "$root/tools/hybris/powervr-hal-version.patch"
git -C "$work/libhybris" apply "$root/tools/hybris/powervr-hal-version.patch"
for patch in "$root"/tools/hybris/patches/*.patch; do
  git -C "$work/libhybris" apply --check "$patch"
  git -C "$work/libhybris" apply "$patch"
done
export ARLINUX_WSI_PROTOCOL_DIR="$work/rootfs/graphics-protocols"
export CONTAINER_ENGINE=podman
"$work/libhybris/tools/build-aarch64.sh" --out "$work/build"

mkdir -p "$work/package/lib" "$work/package/licenses"
cp -a "$work/build/install/usr/lib/hybris/." "$work/package/lib/"
find "$work/package/lib" -name '*.la' -delete
rm -rf "$work/package/lib/include" "$work/package/lib/pkgconfig" "$work/package/lib/bin"
# Resolve the wrapper's private dependencies without putting its EGL/Vulkan frontends
# on the session's LD_LIBRARY_PATH. The Linux runtime keeps owning its Mesa loader.
while IFS= read -r -d '' library; do
  patchelf --set-rpath '$ORIGIN:$ORIGIN/../..' "$library"
done < <(find "$work/package/lib" -type f -name '*.so*' -print0)
for library in libhybris-vulkan-icd.so.0 libhybris-common.so.1 libVkLayer_hybris_compat.so libhybris/linker/q.so; do
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
