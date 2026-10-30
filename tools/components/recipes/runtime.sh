#!/usr/bin/env bash
set -euo pipefail
D=app/src/main/assets/linuxfs
mkdir -p "$D"
VK_INCLUDE=$(ls -d "${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}}"/toolchains/llvm/prebuilt/*/sysroot/usr/include | head -1) \
  tools/vr/build.sh --native "$D/usr/local/lib/droiddeck-vr"
for script in tools/linuxfs/overlay/usr/local/bin/droiddeck-* tools/linuxfs/overlay/usr/local/bin/steam-compatibility; do
  install -Dm644 "$script" "$D/usr/local/bin/$(basename "$script")"
done
for f in tools/linuxfs/overlay/usr/bin/* tools/linuxfs/overlay/usr/bin/steamos-polkit-helpers/*; do
  [ -f "$f" ] && install -Dm644 "$f" "$D/${f#tools/linuxfs/overlay/}"
done
if [ -f tools/gamescope/release.env ]; then
  . tools/gamescope/release.env
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o gamescope.tzst "https://github.com/${GAMESCOPE_REPO:-${GITHUB_REPOSITORY}}/releases/download/${GAMESCOPE_TAG}/gamescope.tzst"
  echo "${GAMESCOPE_SHA256}  gamescope.tzst" | sha256sum -c -
  tar --use-compress-program=unzstd -xf gamescope.tzst -C "$D"
  test -f "$D/usr/local/bin/gamescope"
  rm gamescope.tzst
fi
. tools/linuxfs/uruntime.env
curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o uruntime "https://github.com/VHSgunzo/uruntime/releases/download/${URUNTIME_VERSION}/${URUNTIME_ASSET}"
echo "${URUNTIME_SHA256}  uruntime" | sha256sum -c -
install -Dm644 uruntime "$D/usr/local/lib/droiddeck/uruntime"
install -Dm644 tools/linuxfs/licenses/uruntime-LICENSE "$D/usr/local/share/licenses/uruntime/LICENSE"
rm uruntime
M="$D/usr/local/lib/mangoapp"
mkdir -p "$M" mango-pkgs
grep -v '^#' tools/mangoapp/packages.txt | while read -r sum url; do
  [ -n "$url" ] || continue
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o mango-pkgs/pkg.tar.zst "$url"
  echo "$sum  mango-pkgs/pkg.tar.zst" | sha256sum -c -
  tar --use-compress-program=unzstd -xf mango-pkgs/pkg.tar.zst -C mango-pkgs
  rm mango-pkgs/pkg.tar.zst
done
install -m644 mango-pkgs/usr/bin/mangoapp "$M/mangoapp"
for so in libfmt.so.10 libspdlog.so.1.13 libglfw.so.3 libtraceevent.so.1; do
  cp -L "mango-pkgs/usr/lib/$so" "$M/$so"
done
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wl,-soname,libtracefs.so.1 \
  -o "$M/libtracefs.so.1" tools/mangoapp/libtracefs-shim.c
aarch64-linux-gnu-strip --strip-unneeded "$M/libtracefs.so.1"
install -Dm644 tools/mangoapp/mangoapp "$D/usr/local/bin/mangoapp"
rm -rf mango-pkgs
ls -l "$M"
mkdir -p pw-pkgs
grep -v '^#' tools/pipewire/packages.txt | while read -r sum url; do
  [ -n "$url" ] || continue
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o pw-pkgs/pkg.tar.zst "$url"
  echo "$sum  pw-pkgs/pkg.tar.zst" | sha256sum -c -
  tar --use-compress-program=unzstd -xf pw-pkgs/pkg.tar.zst -C pw-pkgs
  rm pw-pkgs/pkg.tar.zst
done
tools/pipewire/assemble.sh pw-pkgs "$D/usr/local/lib/droiddeck-pipewire.tzst"
rm -rf pw-pkgs
ls -l "$D/usr/local/lib/droiddeck-pipewire.tzst"
T="$D/usr/local/lib/droiddeck-msitools"
mkdir -p "$T" msi-pkgs
grep -v '^#' tools/msitools/packages.txt | while read -r sum url; do
  [ -n "$url" ] || continue
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o msi-pkgs/pkg.tar.xz "$url"
  echo "$sum  msi-pkgs/pkg.tar.xz" | sha256sum -c -
  tar -xJf msi-pkgs/pkg.tar.xz -C msi-pkgs
  rm msi-pkgs/pkg.tar.xz
done
install -m644 msi-pkgs/usr/bin/msiinfo "$T/msiinfo"
install -m644 msi-pkgs/usr/bin/cabextract "$T/cabextract"
install -m644 msi-pkgs/usr/lib/7zip/7z "$T/7z"
install -m644 msi-pkgs/usr/lib/7zip/7z.so "$T/7z.so"
for so in libmsi-1.0.so.0 libgsf-1.so.114 libgcab-1.0.so.0; do
  cp -L "msi-pkgs/usr/lib/$so" "$T/$so"
done
for f in NOTICE GPL-2 GPL-3 LGPL-2.1 7zip-License 7zip-unRarLicense; do
  install -Dm644 "tools/msitools/licenses/$f" "$D/usr/local/share/licenses/droiddeck-msitools/$f"
done
rm -rf msi-pkgs
ls -l "$T"
install -Dm644 tools/linuxfs/desktop/droiddeck-desktop "$D/usr/local/bin/droiddeck-desktop"
install -Dm644 tools/linuxfs/desktop/droiddeck-gpu "$D/usr/local/bin/droiddeck-gpu"
install -Dm644 tools/linuxfs/desktop/droiddeck-desktop-gpu "$D/usr/local/bin/droiddeck-desktop-gpu"
install -Dm644 tools/linuxfs/desktop/kwin_wayland_wrapper "$D/usr/local/bin/kwin_wayland_wrapper"
install -Dm644 tools/linuxfs/desktop/firefox-droiddeck.js "$D/usr/lib/firefox/defaults/pref/droiddeck.js"
ls -lR "$D"
