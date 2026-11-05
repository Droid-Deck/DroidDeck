#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
build='
    export PATH=/work/cargo/bin:$PATH
    if ! command -v rustup >/dev/null; then
        version=$(python3 -c '\''import json;print(json.load(open("tools/xbox/backend.lock.json"))["rust"])'\'')
        curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain "$version"
    fi
    bash tools/xbox/build.sh
'
if [[ $(uname -m) = x86_64 ]]; then
    cache=${DROIDDECK_XBOX_CACHE:-"${HOME}/.cache/droiddeck-xbox-cross"}
    platform=linux/amd64
    image=droiddeck-xbox-cross:$(shasum -a 256 "$repo/tools/xbox/cross.Dockerfile" | cut -c1-12)
    docker image inspect "$image" >/dev/null 2>&1 \
        || docker build --platform "$platform" -t "$image" -f "$repo/tools/xbox/cross.Dockerfile" "$repo/tools/xbox"
else
    cache=${DROIDDECK_XBOX_CACHE:-"${HOME}/.cache/droiddeck-xbox-build"}
    platform=linux/arm64
    image=ubuntu:24.04
    build='
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
        build-essential pkg-config libssl-dev protobuf-compiler cmake curl git ca-certificates xz-utils python3 >/dev/null
'"$build"
fi
mkdir -p "$cache"
docker run --rm --platform "$platform" -v "$repo:/src" -v "$cache:/work" -w /src \
    -e CARGO_HOME=/work/cargo -e RUSTUP_HOME=/work/rustup -e XBOX_BUILD_DIR=/work/build "$image" \
    bash -lc "set -euo pipefail$build"
