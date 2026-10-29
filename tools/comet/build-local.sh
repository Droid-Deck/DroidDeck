#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
. "$repo/tools/docker_platform.sh"
image=${DROIDDECK_BUILD_IMAGE}
platform=${DROIDDECK_DOCKER_PLATFORM}
rust_cache="${HOME}/.cache/droiddeck-comet-rust${platform#linux/amd64}"
rust_cache=${rust_cache/\/arm64/-arm64}
docker run --rm --platform "$platform" -v "$repo:/src" \
  -v "${rust_cache}:/rust" -w /src \
  -e CARGO_HOME=/rust/cargo -e RUSTUP_HOME=/rust/rustup "$image" bash -lc '
    set -euo pipefail
    trap "chown -R '"$(id -u):$(id -g)"' app/build/comet app/src/main/assets/linuxfs/usr/local/lib/droiddeck-comet /rust 2>/dev/null || true" EXIT
    apt-get update -qq
    apt-get install -y -qq curl git unzip ca-certificates pkg-config >/dev/null
    git config --global --add safe.directory "*"
    export PATH=/rust/cargo/bin:$PATH
    if ! command -v cargo >/dev/null; then
      curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain 1.90.0
    fi
    export RUSTUP_TOOLCHAIN=1.90.0
    rustup target add aarch64-unknown-linux-gnu
    bash tools/comet/build.sh
  '
