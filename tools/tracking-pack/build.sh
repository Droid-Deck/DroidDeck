#!/usr/bin/env bash
# Builds tracking-pack.tar.zst: the hand-tracking models and the OpenCV/MediaPipe native
# libraries the app downloads on first use instead of shipping in the APK.
# Usage: tools/tracking-pack/build.sh <out-dir>
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=${1:?usage: build.sh <out-dir>}
mkdir -p "$out"
out=$(cd "$out" && pwd)
# shellcheck source=release.env
source "$here/release.env"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/pack"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

fetch() {
    local url=$1 expected=$2 dest=$3
    curl -fsSL --retry 4 -o "$dest" "$url"
    local actual
    actual=$(sha256 "$dest")
    if [ "$actual" != "$expected" ]; then
        echo "checksum mismatch for $url: $actual" >&2
        exit 1
    fi
}

fetch "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" \
    fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1 "$work/pack/hand_landmarker.task"
fetch "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task" \
    59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a "$work/pack/pose_landmarker_lite.task"

# Built by tools/build_hand_lite.py (see hand_landmarker_lite.provenance.json); kept at the
# commit that last shipped it in the APK.
git -C "$repo" show 31a6c3d0:app/src/main/assets/models/hand_landmarker_lite.task > "$work/pack/hand_landmarker_lite.task"
[ "$(sha256 "$work/pack/hand_landmarker_lite.task")" = 173c0f47ecee7e935bd4a1e93aa580756b4180c1307c07d709313e82c28672c8 ] || {
    echo "checksum mismatch for hand_landmarker_lite.task" >&2; exit 1; }

fetch "https://repo1.maven.org/maven2/org/opencv/opencv/${OPENCV_VERSION}/opencv-${OPENCV_VERSION}.aar" \
    "$OPENCV_AAR_SHA256" "$work/opencv.aar"
fetch "https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/${MEDIAPIPE_VERSION}/tasks-core-${MEDIAPIPE_VERSION}.aar" \
    "$MEDIAPIPE_AAR_SHA256" "$work/tasks-core.aar"
unzip -q -j "$work/opencv.aar" jni/arm64-v8a/libopencv_java4.so -d "$work/pack"
unzip -q -j "$work/tasks-core.aar" jni/arm64-v8a/libmediapipe_tasks_jni.so -d "$work/pack"

python3 -I - "$work/pack" "$work/tracking-pack.tar" <<'EOF'
import os, sys, tarfile
src, dest = sys.argv[1], sys.argv[2]
with tarfile.open(dest, "w", format=tarfile.USTAR_FORMAT) as tar:
    for name in sorted(os.listdir(src)):
        info = tar.gettarinfo(os.path.join(src, name), arcname=name)
        info.mtime, info.uid, info.gid, info.uname, info.gname, info.mode = 0, 0, 0, "", "", 0o644
        with open(os.path.join(src, name), "rb") as f:
            tar.addfile(info, f)
EOF
zstd -q -19 -f "$work/tracking-pack.tar" -o "$out/tracking-pack.tar.zst"

echo "TRACKING_PACK_SHA256=$(sha256 "$out/tracking-pack.tar.zst")"
echo "TRACKING_PACK_SIZE=$(wc -c < "$out/tracking-pack.tar.zst" | tr -d ' ')"
