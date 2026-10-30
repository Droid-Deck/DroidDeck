#!/usr/bin/env bash
# Builds the add-on packs the app downloads on first use instead of shipping in the APK:
#   proot.tar.zst     the patched proot and its loader (built by tools/proot)
#   directaudio.tar.zst  the Wine DirectAudio drivers (fetched by tools/directaudio/fetch.sh)
#   tracking.tar.zst  hand-tracking models and the OpenCV/MediaPipe native libraries
#   xbox.tar.zst      the Xbox backend (built by tools/xbox) for /usr/local/lib/droiddeck-xbox
#   comet.tar.zst     the GOG Galaxy companion (built by tools/comet) for /usr/local/lib/droiddeck-comet
# Only CI publishes these (.github/workflows/publish-packs.yml); the APK built from the same
# directory pins each pack's SHA-256.
# Usage: tools/packs/build.sh <out-dir>
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=${1:?usage: build.sh <out-dir>}
mkdir -p "$out"
out=$(cd "$out" && pwd)
linuxfs="$repo/app/src/main/assets/linuxfs/usr/local/lib"
# shellcheck source=tracking/release.env
source "$here/tracking/release.env"
# shellcheck source=butler/release.env
source "$here/butler/release.env"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

fetch() {
    bash "$here/fetch-verified.sh" "$@"
}

pack() {
    local name=$1 src=$2
    shift 2
    for required in "$@"; do
        [ -f "$src/$required" ] || { echo "$name pack: $src/$required is missing" >&2; exit 1; }
    done
    python3 -I - "$src" "$work/$name.tar" <<'EOF'
import os, sys, tarfile
src, dest = sys.argv[1], sys.argv[2]
files = []
for base, dirs, names in os.walk(src):
    dirs.sort()
    for name in names:
        path = os.path.join(base, name)
        if os.path.islink(path) or not os.path.isfile(path):
            sys.exit(f"{path}: only regular files can be packed")
        files.append(os.path.relpath(path, src))
with tarfile.open(dest, "w", format=tarfile.USTAR_FORMAT) as tar:
    for rel in sorted(files):
        path = os.path.join(src, rel)
        info = tar.gettarinfo(path, arcname=rel)
        info.mtime, info.uid, info.gid, info.uname, info.gname = 0, 0, 0, "", ""
        info.mode = 0o755 if os.stat(path).st_mode & 0o111 else 0o644
        with open(path, "rb") as f:
            tar.addfile(info, f)
EOF
    zstd -q -19 -f "$work/$name.tar" -o "$out/$name.tar.zst"
    echo "$name $(sha256 "$out/$name.tar.zst") $(wc -c < "$out/$name.tar.zst" | tr -d ' ')"
}

mkdir "$work/tracking"
fetch "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" \
    fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1 "$work/tracking/hand_landmarker.task"
fetch "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task" \
    59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a "$work/tracking/pose_landmarker_lite.task"
cp "$here/tracking/hand_landmarker_lite.task" "$work/tracking/"
[ "$(sha256 "$work/tracking/hand_landmarker_lite.task")" = 173c0f47ecee7e935bd4a1e93aa580756b4180c1307c07d709313e82c28672c8 ] || {
    echo "checksum mismatch for hand_landmarker_lite.task" >&2; exit 1; }
fetch "https://repo1.maven.org/maven2/org/opencv/opencv/${OPENCV_VERSION}/opencv-${OPENCV_VERSION}.aar" \
    "$OPENCV_AAR_SHA256" "$work/opencv.aar"
fetch "https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/${MEDIAPIPE_VERSION}/tasks-core-${MEDIAPIPE_VERSION}.aar" \
    "$MEDIAPIPE_AAR_SHA256" "$work/tasks-core.aar"
unzip -q -j "$work/opencv.aar" jni/arm64-v8a/libopencv_java4.so -d "$work/tracking"
unzip -q -j "$work/tasks-core.aar" jni/arm64-v8a/libmediapipe_tasks_jni.so -d "$work/tracking"
chmod 0644 "$work/tracking"/*

mkdir "$work/proot"
install -m755 "$repo/app/src/main/jniLibs/arm64-v8a/libproot.so" "$repo/app/src/main/jniLibs/arm64-v8a/libproot-loader.so" "$work/proot/"
pack proot "$work/proot" libproot.so libproot-loader.so
pack directaudio "$repo/app/src/main/assets/directaudio" \
    linux-wine11/version.txt linux-wine11-systhread/version.txt
pack tracking "$work/tracking" hand_landmarker.task hand_landmarker_lite.task pose_landmarker_lite.task \
    libopencv_java4.so libmediapipe_tasks_jni.so
pack xbox "$linuxfs/droiddeck-xbox" version xodus-cli xodus-service
pack comet "$linuxfs/droiddeck-comet" version comet

mkdir "$work/butler"
fetch "https://broth.itch.zone/butler/linux-arm64/${BUTLER_VERSION}/archive/default" \
    "$BUTLER_ZIP_SHA256" "$work/butler.zip"
unzip -q -j "$work/butler.zip" butler 7z.so libc7zip.so -d "$work/butler"
printf '%s\n' "$BUTLER_VERSION" > "$work/butler/version"
pack butler "$work/butler" version butler 7z.so libc7zip.so
