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
    binfmt_magics=$(cat /proc/sys/fs/binfmt_misc/* 2>/dev/null || true)
    if [[ $(uname -s) = Linux && $binfmt_magics != *'magic 7f454c460201010000000000000000000200b700'* ]]; then
        echo "The Xbox backend tests re-execute their arm64 binaries, which needs an aarch64 binfmt handler on the host." >&2
        echo "Register one with: docker run --privileged --rm tonistiigi/binfmt --install arm64" >&2
        exit 1
    fi
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
threads=()
[[ -n "${RUST_TEST_THREADS:-}" ]] && threads=(-e RUST_TEST_THREADS="$RUST_TEST_THREADS")
docker run --rm --platform "$platform" -v "$repo:/src" -v "$cache:/work" -w /src \
    -e CARGO_HOME=/work/cargo -e RUSTUP_HOME=/work/rustup -e XBOX_BUILD_DIR=/work/build \
    ${threads[@]+"${threads[@]}"} "$image" \
    bash -lc "set -euo pipefail
    trap 'chown -R $(id -u):$(id -g) /work /src/app/src/main/assets/linuxfs /src/app/build 2>/dev/null || true' EXIT$build"
