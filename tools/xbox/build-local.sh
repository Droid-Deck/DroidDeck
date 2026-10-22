#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
cache=${DROIDDECK_XBOX_CACHE:-"${HOME}/.cache/droiddeck-xbox-build"}
mkdir -p "$cache"
docker run --rm --platform linux/arm64 -v "$repo:/src" -v "$cache:/work" -w /src \
    -e CARGO_HOME=/work/cargo -e RUSTUP_HOME=/work/rustup -e XBOX_BUILD_DIR=/work/build ubuntu:24.04 bash -lc '
    set -euo pipefail
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
        build-essential pkg-config libssl-dev protobuf-compiler cmake curl git ca-certificates xz-utils python3 >/dev/null
    export PATH=/work/cargo/bin:$PATH
    if ! command -v rustup >/dev/null; then
        version=$(python3 -c '\''import json;print(json.load(open("tools/xbox/backend.lock.json"))["rust"])'\'')
        curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain "$version"
    fi
    bash tools/xbox/build.sh
'
