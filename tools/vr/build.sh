#!/usr/bin/env bash
# Builds the SteamVR driver (linuxarm64) into tools/vr/out/droiddeck with the local cross image,
# the same one tools/build_local.sh builds the preloads with.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
image=${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v3}
out="$here/out/droiddeck"
rm -rf "$out" && mkdir -p "$out/bin/linuxarm64"
cp -R "$here/driver_droiddeck/." "$out/"
docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" -v "$repo:/src" -w /src "$image" \
    aarch64-linux-gnu-g++ -std=c++17 -shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter \
    -fvisibility=hidden -static-libstdc++ -static-libgcc -pthread \
    -o tools/vr/out/droiddeck/bin/linuxarm64/driver_droiddeck.so tools/vr/src/driver_droiddeck.cpp
echo "built $out"
