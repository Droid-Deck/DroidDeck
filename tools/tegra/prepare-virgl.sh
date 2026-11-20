#!/usr/bin/env bash
set -euo pipefail

jni_dir=$1
licenses=$2
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT

# Termux's build uses Android EGL/GLES without a DRM render node.
url=https://packages.termux.dev/apt/termux-main/pool/main/v/virglrenderer-android/virglrenderer-android_1.3.0-1_aarch64.deb
digest=f1a7297bc0edf701b2955fcc9ba679707e092e1b78d9f1d620ba9e4567aa0c5b
curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o "$scratch/virgl.deb" "$url"
printf '%s  %s\n' "$digest" "$scratch/virgl.deb" | sha256sum -c -
dpkg-deb -x "$scratch/virgl.deb" "$scratch"
prefix="$scratch/data/data/com.termux/files/usr"
lib="$prefix/opt/virglrenderer-android/lib"
server=libdroiddeck_tegra_vtest.so
renderer=libdroiddeck_tegra_virgl.so
epoxy=libdroiddeck_tegra_epoxy.so

# Keep this server and its dependencies separate from the Venus component in the APK.
install -m755 "$prefix/bin/virgl_test_server_android" "$scratch/$server"
install -m755 "$lib/libvirglrenderer.so" "$scratch/$renderer"
install -m755 "$lib/libepoxy.so" "$scratch/$epoxy"
patchelf --page-size 16384 --remove-rpath --replace-needed libvirglrenderer.so "$renderer" \
    --replace-needed libepoxy.so "$epoxy" "$scratch/$server"
patchelf --page-size 16384 --remove-rpath --set-soname "$renderer" \
    --replace-needed libepoxy.so "$epoxy" "$scratch/$renderer"
patchelf --page-size 16384 --remove-rpath --set-soname "$epoxy" "$scratch/$epoxy"
mkdir -p "$jni_dir" "$licenses"
install -m755 "$scratch/$server" "$scratch/$renderer" "$scratch/$epoxy" "$jni_dir/"
install -m644 "$prefix/share/doc/virglrenderer-android"/COPYING-* "$licenses/"
