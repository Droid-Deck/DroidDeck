#!/usr/bin/env bash
# Builds DroidDeck's bionic runtime pack: the Android-side (bionic libc) pieces a bionic DroidDeck-Proton layer
# needs inside DroidDeck's gamescope session. The Proton layer itself comes from The412Banner/proton-wine releases;
# GPU drivers, DXVK/VKD3D and FEX stay app options. Binaries target Android 11+ (API 30: the fake-input
# library hooks statx).
#
# usage: NDK=<Android NDK root> tools/bionic-runtime/build.sh <work dir> <out dir>
# host needs: curl git tar zstd python3, wayland-scanner, and the X11/xcb/wayland/glm headers
#   (Debian/Ubuntu: libwayland-bin libwayland-dev libx11-dev libx11-xcb-dev libxcb1-dev libxcb-composite0-dev libglm-dev)
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${1:?usage: build.sh <work dir> <out dir>}
OUT=${2:?usage: build.sh <work dir> <out dir>}
: "${NDK:?set NDK to the Android NDK root}"
API=30

# Pinned inputs (bump together with their sha256).
IMAGEFS_URL=https://github.com/The412Banner/winlator-contents/releases/download/base-assets-v1/imagefs.tar.zst
IMAGEFS_SHA256=7838756e6a05c91afff68f4bf12aa2780f815877753f8dd354e203e99b9caf8a   # same pin as Bannerlator app/build.gradle
BL_COMMIT=410c969dfe37ed0c687d6a5ee3327ea21a53e0f8                                  # The412Banner/Bannerlator
BL_ASSETS=https://raw.githubusercontent.com/The412Banner/Bannerlator/$BL_COMMIT/app/src/main/assets
ADAPTER_SHA256=c572b590faf3e14066a1ed86887a6f4502fd01ecd4efadf9eac70db0954d8120   # wayland/adapter/libvulkan_wrapper.so
ADAPTER_JSON_SHA256=a161ea72366fac8b4248d3e2e1469c3122b21aad62a495728ea645ce678b0c0f
FFMPEG8_SHA256=34ca9076b6b69bc96ee0d53fcb7b9d0d8df869b9f1255dd686bf89cc590b1b08   # ffmpeg8.tzst
GAMESCOPE_TAG=3.16.29
GAMESCOPE_COMMIT=8f212644c46460549035971ab914421180f61a7c
GAMESCOPE_PATCHES="0117-wsi-cache-present-bypass-check.patch 0119-wsi-default-images-under-limiter.patch"
VULKAN_HEADERS_TAG=v1.4.341
# The bionic DroidDeck-Proton layer the pack is made for: linked against (libwayland-client) and checked against, not packed.
LAYER_URL=https://github.com/The412Banner/proton-wine/releases/download/build-bionic-layers-20261030-v16/DroidDeck-proton-11.0-12.8-arm64ec.wcp
LAYER_SHA256=a5e05e35214f34603f8cf9a362bb6b4bce9f18d773ee689ecfaff6136a1e9003   # proton-wine 067c719d, layer v16
LAYER_VERSION=12.8
# Packed into the DroidDeck-Bionic tool next to the layer: FEX with Android-built unix libraries, and default
# DXVK / VKD3D-Proton (DroidDeck components can swap these in files/lib/wine/{dxvk,vkd3d-proton} later).
FEX_URL=https://github.com/The412Banner/Nightlies/releases/download/FEXCore-2611.1/FEXCore-2611.1-unix.wcp
FEX_SHA256=caca997e5e077feb5d9b7cf0f2c680cd93b442278903e3f3e7b1bd9cd544770c
DXVK_URL=https://github.com/The412Banner/Nightlies/releases/download/Dxvk-gplasync-Linux/dxvk-gplasync-2.4.1-1-pre-reg-linux.wcp
DXVK_SHA256=379b5dcde331de45a5f0ac7775d2c657c9c1510616afb9143ab8cdfbfa8e5342
VKD3D_URL=https://github.com/Droid-Deck/DroidDeck-Components/releases/download/linux-nightly/DD-vkd3d-proton-arm64ec-3.1%2B64-nightly.wcp
VKD3D_SHA256=92b8aa023e5242a6f7a69770b808b213474de7869f0d387ea99867a59e539b4e

case "$(uname -s):$(uname -m)" in
  Linux:x86_64) NDK_HOST=linux-x86_64 ;;
  Linux:aarch64|Linux:arm64) NDK_HOST=linux-aarch64 ;;
  Darwin:arm64) NDK_HOST=darwin-arm64 ;;
  Darwin:x86_64) NDK_HOST=darwin-x86_64 ;;
  *) echo "unsupported host $(uname -s):$(uname -m)" >&2; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
[[ -d "$TC" ]] || TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android${API}-clang"
CXX="$TC/aarch64-linux-android${API}-clang++"
READELF="$TC/llvm-readelf"
STRIP="$TC/llvm-strip"
[[ -x "$CC" && -x "$CXX" ]] || { echo "no NDK clang under $TC" >&2; exit 1; }

fetch() { # url sha256 dest
  curl -fsSL --retry 5 --retry-delay 3 --retry-connrefused -o "$3" "$1"
  echo "$2  $3" | sha256sum -c -
}

rm -rf "$WORK"; mkdir -p "$WORK" "$OUT"
PACK="$WORK/droiddeck-bionic-runtime"
mkdir -p "$PACK"/{lib,layer,preload,bin,adapter,share,etc}

echo "== imagefs (Bannerlator base, pinned)"
fetch "$IMAGEFS_URL" "$IMAGEFS_SHA256" "$WORK/imagefs.tar.zst"
mkdir -p "$WORK/imagefs"
tar --zstd -xf "$WORK/imagefs.tar.zst" -C "$WORK/imagefs"
IMG="$WORK/imagefs/usr/lib"
[[ -d "$IMG" ]] || { echo "imagefs has no usr/lib" >&2; exit 1; }

echo "== bionic DroidDeck-Proton layer (pinned; its lib/ is on the library path at run time)"
fetch "$LAYER_URL" "$LAYER_SHA256" "$WORK/layer.wcp"
mkdir -p "$WORK/layer"
tar --zstd -xf "$WORK/layer.wcp" -C "$WORK/layer" lib
LAYERLIB="$WORK/layer/lib"

echo "== Bannerlator assets (pinned): Wayland adapter, FFmpeg 8"
fetch "$BL_ASSETS/wayland/adapter/libvulkan_wrapper.so" "$ADAPTER_SHA256" "$PACK/adapter/libvulkan_wrapper.so"
fetch "$BL_ASSETS/wayland/adapter/adapter.json" "$ADAPTER_JSON_SHA256" "$PACK/adapter/adapter.json"
fetch "$BL_ASSETS/ffmpeg8.tzst" "$FFMPEG8_SHA256" "$WORK/ffmpeg8.tzst"
cp "$HERE/icd.json" "$PACK/adapter/icd.json"

echo "== libraries from the imagefs"
copy_lib() { # name: copies the file and every link on the way to it, as links (Android's own libraries are skipped)
  local name=$1 src=$IMG/$1 target
  [[ -e "$src" || -L "$src" ]] || { echo "missing in imagefs: $name" >&2; return 1; }
  [[ -L "$src" && "$(readlink "$src")" == /* ]] && return 0
  while [[ -L "$src" ]]; do
    target=$(readlink "$src")
    [[ -e "$PACK/lib/$(basename "$src")" || -L "$PACK/lib/$(basename "$src")" ]] || ln -s "$(basename "$target")" "$PACK/lib/$(basename "$src")"
    src="$(dirname "$src")/$target"
  done
  [[ -e "$PACK/lib/$(basename "$src")" ]] || cp "$src" "$PACK/lib/"
}
grep -v -e '^#' -e '^$' "$HERE/runtime-libs.txt" | while read -r name; do copy_lib "$name"; done
[[ -e "$PACK/lib/libSDL2-2.0.so.0" ]] || ln -s libSDL2-2.0.so "$PACK/lib/libSDL2-2.0.so.0"
mkdir -p "$PACK/lib/gstreamer-1.0"
grep -v -e '^#' -e '^$' "$HERE/gst-plugins.txt" | while read -r p; do cp "$IMG/gstreamer-1.0/libgst$p.so" "$PACK/lib/gstreamer-1.0/"; done
tar --zstd -xf "$WORK/ffmpeg8.tzst" -C "$PACK/lib"
for f in "$PACK"/lib/libav*.so.* "$PACK"/lib/libsw*.so.*; do  # soname links for the FFmpeg 8 files
  [[ -L "$f" ]] && continue
  so=$("$READELF" -d "$f" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')
  [[ -n "$so" && ! -e "$PACK/lib/$so" ]] && ln -s "$(basename "$f")" "$PACK/lib/$so"
done
# GStreamer plugins: add the libraries they need from the imagefs; drop plugins needing something it lacks
python3 "$HERE/check-needed.py" "$READELF" "$PACK" --available "$LAYERLIB" --fill "$IMG" --prune lib/gstreamer-1.0
cp -a "$WORK/imagefs/usr/share/X11" "$PACK/share/"
cp -a "$WORK/imagefs/etc/fonts" "$WORK/imagefs/etc/tls" "$PACK/etc/"

echo "== gamescope $GAMESCOPE_TAG WSI layer (+ DroidDeck patches: $GAMESCOPE_PATCHES)"
GS="$WORK/gamescope"
git clone -q --depth 1 --branch "$GAMESCOPE_TAG" https://github.com/ValveSoftware/gamescope "$GS"
[[ "$(git -C "$GS" rev-parse HEAD)" == "$GAMESCOPE_COMMIT" ]] || { echo "gamescope $GAMESCOPE_TAG is not $GAMESCOPE_COMMIT" >&2; exit 1; }
git -C "$GS" submodule update -q --init --depth 1 subprojects/vkroots || git -C "$GS" submodule update -q --init subprojects/vkroots
for p in $GAMESCOPE_PATCHES; do git -C "$GS" apply "$ROOT/tools/gamescope/patches/$p"; done
git clone -q --depth 1 --branch "$VULKAN_HEADERS_TAG" https://github.com/KhronosGroup/Vulkan-Headers "$WORK/vulkan-headers"
INC="$WORK/inc"; GEN="$WORK/gen"; mkdir -p "$INC" "$GEN"
cp -r "$WORK/vulkan-headers/include/vulkan" "$WORK/vulkan-headers/include/vk_video" "$INC/"
cp -r /usr/include/X11 /usr/include/xcb /usr/include/glm "$INC/"
cp /usr/include/wayland-*.h "$INC/"
for p in gamescope-swapchain gamescope-limiter; do
  wayland-scanner client-header "$GS/protocol/$p.xml" "$GEN/$p-client-protocol.h"
  wayland-scanner private-code "$GS/protocol/$p.xml" "$GEN/$p-protocol.c"
  "$CC" -O2 -fPIC -c -I"$INC" "$GEN/$p-protocol.c" -o "$GEN/$p-protocol.o"
done
LINK=(-L"$PACK/lib" -Wl,-rpath-link,"$PACK/lib" -L"$LAYERLIB" -Wl,-rpath-link,"$LAYERLIB")
"$CXX" -std=c++20 -O2 -fPIC -shared -DWLR_USE_UNSTABLE -D_GNU_SOURCE -include "$HERE/bionic_compat.h" \
  -I"$GS/subprojects/vkroots" -I"$GEN" -I"$INC" -I"$GS/layer" -I"$GS/src" \
  "$GS/layer/VkLayer_FROG_gamescope_wsi.cpp" "$GEN"/*.o "${LINK[@]}" \
  -lwayland-client -lX11-xcb -lX11 -lxcb -lxcb-composite -static-libstdc++ -Wl,--no-undefined \
  -o "$PACK/layer/libVkLayer_FROG_gamescope_wsi_aarch64.so"
cp "$HERE/VkLayer_FROG_gamescope_wsi.aarch64.json" "$PACK/layer/"

echo "== DroidDeck fake input (app/src/main/cpp/fakeinput_steam.cpp), heap-tagging off, window tagger"
"$CXX" -shared -fPIC -O2 -Wall -Wno-attributes -pthread -std=c++17 "$ROOT/app/src/main/cpp/fakeinput_steam.cpp" \
  -ldl -static-libstdc++ -o "$PACK/preload/libfakeinput.so"
"$CC" -shared -fPIC -O2 -Wall "$HERE/notag.c" -ldl -o "$PACK/preload/libnotag.so"
"$CC" -shared -fPIC -O2 -Wall "$HERE/execshim.c" -ldl -o "$PACK/preload/libexecshim.so"
"$CC" -O2 -Wall "$HERE/gstag.c" -o "$PACK/bin/gstag"
"$STRIP" --strip-unneeded "$PACK/layer/"*.so "$PACK/preload/"*.so "$PACK/bin/gstag"

echo "== every library a pack file needs must be in the pack or on Android"
python3 "$HERE/check-needed.py" "$READELF" "$PACK" --available "$LAYERLIB"

echo "== manifest"
REV=$(git -C "$ROOT" rev-parse --short=8 HEAD)
cp "$HERE/NOTICE" "$PACK/NOTICE"
{
  echo "droiddeck-bionic-runtime $REV ($(date -u +%Y-%m-%dT%H:%M:%SZ), Android API $API, NDK $(basename "$NDK"))"
  echo "imagefs $IMAGEFS_URL sha256 $IMAGEFS_SHA256"
  echo "bannerlator $BL_COMMIT (wayland/adapter, ffmpeg8.tzst)"
  echo "layer $LAYER_URL sha256 $LAYER_SHA256 (not packed)"
  echo "gamescope $GAMESCOPE_TAG $GAMESCOPE_COMMIT + $GAMESCOPE_PATCHES; vkroots $(git -C "$GS/subprojects/vkroots" rev-parse HEAD); Vulkan-Headers $VULKAN_HEADERS_TAG"
  echo
  (cd "$PACK" && find . -type f ! -name MANIFEST.txt | sort | xargs sha256sum)
} > "$PACK/MANIFEST.txt"
tar --zstd -cf "$OUT/droiddeck-bionic-runtime-$REV.tar.zst" -C "$WORK" droiddeck-bionic-runtime
(cd "$OUT" && sha256sum "droiddeck-bionic-runtime-$REV.tar.zst" > "droiddeck-bionic-runtime-$REV.tar.zst.sha256")
du -sh "$PACK" "$OUT"/*.tar.zst

echo "== DroidDeck-Bionic tool: layer + this pack + FEX + DXVK/VKD3D + tool scripts, in Proton's layout"
NAME="DroidDeck-Bionic-$LAYER_VERSION-r${BUILD_NUMBER:-0}"
T="$WORK/tool/$NAME"
mkdir -p "$T/files" "$T/bionic"
tar --zstd -xf "$WORK/layer.wcp" -C "$T/files" bin lib share prefixPack.txz
mkdir -p "$WORK/pfx" && tar -xJf "$T/files/prefixPack.txz" -C "$WORK/pfx" && rm "$T/files/prefixPack.txz"
mv "$WORK/pfx/.wine" "$T/files/default_pfx"
# No drives in the template: its links point into Winlator, and a Z: -> / link here once let DroidDeck's tool delete
# walk the whole device. launch.sh makes C: and Z: in each game's own prefix.
rm -rf "$T/files/default_pfx/dosdevices"
for c in FEX:"$FEX_URL":"$FEX_SHA256" DXVK:"$DXVK_URL":"$DXVK_SHA256" VKD3D:"$VKD3D_URL":"$VKD3D_SHA256"; do
  n=${c%%:*}; rest=${c#*:}; sha=${rest##*:}; url=${rest%:*}
  fetch "$url" "$sha" "$WORK/$n.wcp"
  mkdir -p "$WORK/$n" && tar --zstd -xf "$WORK/$n.wcp" -C "$WORK/$n"
done
cp "$WORK/FEX/aarch64-unix/"*.so "$T/files/lib/wine/aarch64-unix/"
cp "$WORK/FEX/system32/"*.dll "$T/files/lib/wine/aarch64-windows/"
cp -r "$WORK/DXVK/files/lib/wine/dxvk" "$WORK/VKD3D/files/lib/wine/vkd3d-proton" "$T/files/lib/wine/"
cp -a "$PACK" "$T/files/runtime"
install -m 755 "$HERE/tool/proton" "$T/proton"
install -m 755 "$HERE/tool/launch.sh" "$T/bionic/launch.sh"
cp "$HERE/tool/desktop.reg" "$T/bionic/"
cp "$HERE/tool/toolmanifest.vdf" "$T/"
sed -e "s/@NAME@/$NAME/" -e "s/@DISPLAY@/DroidDeck-Bionic $LAYER_VERSION r${BUILD_NUMBER:-0}/" "$HERE/tool/compatibilitytool.vdf.in" > "$T/compatibilitytool.vdf"
echo "$(date -u +%s) $NAME" > "$T/version"
{
  echo "$NAME: DroidDeck runtime pack $REV + layer + FEX/DXVK/VKD3D"
  echo "fex $FEX_URL sha256 $FEX_SHA256"
  echo "dxvk $DXVK_URL sha256 $DXVK_SHA256"
  echo "vkd3d $VKD3D_URL sha256 $VKD3D_SHA256"
} > "$T/SOURCES.txt"
python3 "$HERE/check-needed.py" "$READELF" "$T/files" --available "$T/files/lib"
echo "== no link may point out of the tool (DroidDeck deletes old tool folders)"
python3 - "$T" <<'PY'
import os, sys
root = os.path.realpath(sys.argv[1])
bad = []
for d, dirs, files in os.walk(root):
    for n in dirs + files:
        p = os.path.join(d, n)
        if os.path.islink(p):
            t = os.path.realpath(p)
            if t != root and not t.startswith(root + os.sep):
                bad.append("%s -> %s" % (os.path.relpath(p, root), os.readlink(p)))
if bad:
    sys.exit("links out of the tool:\n" + "\n".join(bad))
print("no links out of the tool")
PY
tar -C "$WORK/tool" -cf - "$NAME" | xz -T0 -6 > "$OUT/$NAME-aarch64.tar.xz"
(cd "$OUT" && sha512sum "$NAME-aarch64.tar.xz" > "$NAME-aarch64.sha512sum")
du -sh "$T" "$OUT/$NAME-aarch64.tar.xz"
