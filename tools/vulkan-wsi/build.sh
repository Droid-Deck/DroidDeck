#!/usr/bin/env bash
# CI build of a vendor-neutral X11 surface/swapchain layer.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
. "$root/tools/vulkan-wsi/source.env"
mkdir -p "${1:?output directory required}"
out=$(cd "$1" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fetch() {
  git init -q "$3"
  git -C "$3" fetch --depth=1 "$1" "$2"
  git -C "$3" checkout -q --detach FETCH_HEAD
  test "$(git -C "$3" rev-parse HEAD)" = "$2"
}
fetch https://github.com/ginkage/vulkan-wsi-layer.git "$WSI_REVISION" "$work/source"
fetch https://github.com/KhronosGroup/Vulkan-Headers.git "$VULKAN_HEADERS_REVISION" "$work/headers"
git -C "$work/source" apply --check "$root/tools/vulkan-wsi/patches/0001-link-x11-external-memory.patch"
git -C "$work/source" apply "$root/tools/vulkan-wsi/patches/0001-link-x11-external-memory.patch"
cmake -S "$work/source" -B "$work/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX=/usr -DVULKAN_CXX_INCLUDE="$work/headers/include" \
  -DBUILD_WSI_HEADLESS=OFF -DBUILD_WSI_WAYLAND=OFF -DBUILD_WSI_DISPLAY=OFF -DBUILD_WSI_X11=ON \
  -DSELECT_EXTERNAL_ALLOCATOR=dma_buf_heaps -DWSIALLOC_MEMORY_HEAP_NAME=system -DKERNEL_HEADER_DIR=/usr/include
cmake --build "$work/build" --parallel 4
DESTDIR="$work/stage" cmake --install "$work/build"
cp "$work/stage/usr/share/vulkan/implicit_layer.d/"* "$out/"
cp "$root/tools/vulkan-wsi/patches/0001-link-x11-external-memory.patch" "$out/downstream.patch"
cp "$work/source/LICENSE" "$out/LICENSE"
mkdir -p "$out/licenses/Vulkan-Headers"
cp "$root/tools/vulkan-wsi/licenses/Vulkan-Headers/"* "$out/licenses/Vulkan-Headers/"
printf '%s\n' "$WSI_REVISION" > "$out/source-commit"
printf '%s\n' "$VULKAN_HEADERS_REVISION" > "$out/vulkan-headers-commit"
tar --exclude-vcs --zstd -cf "$out/source.tar.zst" -C "$work/source" .
(cd "$out" && sha256sum ./*.so > SHA256SUMS)
