#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
cache=${DROIDDECK_XBOX_CACHE:-"${HOME}/.cache/droiddeck-xbox-build"}
image=${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v4}
mkdir -p "$cache"
python3 "$repo/tools/docker_preflight.py" "$image"
docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" \
    -v "$repo:/src" -v "$cache:/work" -w /src \
    -e HOME=/work/cross-x86_64/home -e CARGO_HOME=/work/cross-x86_64/cargo \
    -e RUSTUP_HOME=/work/cross-x86_64/rustup -e CARGO_TARGET_DIR=/work/cross-x86_64/target \
    -e XBOX_BUILD_DIR=/work/cross-x86_64/build "$image" bash -lc '
    set -euo pipefail
    container_home=$(getent passwd "$(id -u)" | cut -d: -f6 || true)
    if [[ -n "$container_home" && -w "$container_home" ]]; then
        export HOME="$container_home"
    else
        mkdir -p "$HOME"
    fi
    export PATH="$CARGO_HOME/bin:$PATH"
    export CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc
    export CXX_aarch64_unknown_linux_gnu=aarch64-linux-gnu-g++
    export AR_aarch64_unknown_linux_gnu=aarch64-linux-gnu-ar
    export PKG_CONFIG_ALLOW_CROSS=1
    export PKG_CONFIG_LIBDIR=/usr/lib/aarch64-linux-gnu/pkgconfig:/usr/share/pkgconfig
    export OPENSSL_DIR=/usr
    export OPENSSL_INCLUDE_DIR=/usr/include
    export OPENSSL_LIB_DIR=/usr/lib/aarch64-linux-gnu
    if ! command -v rustup >/dev/null; then
        version=$(python3 -c '\''import json;print(json.load(open("tools/xbox/backend.lock.json"))["rust"])'\'')
        curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain "$version"
    fi
    bash tools/xbox/build.sh
'
