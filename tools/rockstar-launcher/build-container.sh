#!/usr/bin/env bash
# Builds the Rockstar launcher in a container, on the host's own architecture. Rust's
# i686-pc-windows-gnu target needs a DWARF2 mingw; Homebrew's i686 mingw uses SJLJ and fails to link.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=${1:?usage: build-container.sh <output directory>}
. "$repo/tools/docker_platform.sh"
cache=${DROIDDECK_BUILD_CACHE:-$HOME/.cache/droiddeck-build}/rockstar-container
mkdir -p "$out" "$cache/cargo" "$cache/target"
out=$(cd "$out" && pwd)
image=droiddeck-rockstar-build:v2-${docker_platform#linux/}

if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker build --platform "$docker_platform" -t "$image" - <<'EOF'
FROM public.ecr.aws/docker/library/ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo PATH=/opt/cargo/bin:$PATH
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends ca-certificates curl gcc libc6-dev \
        gcc-mingw-w64-i686-posix gcc-mingw-w64-x86-64-posix >/dev/null
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y -q --profile minimal --default-toolchain stable \
        -t i686-pc-windows-gnu -t x86_64-pc-windows-gnu && chmod -R a+rX /opt/rustup /opt/cargo
EOF
fi

docker run --rm --platform "$docker_platform" --user "$(id -u):$(id -g)" \
    -e CARGO_HOME=/cache/cargo -e CARGO_TARGET_DIR=/cache/target -e HOME=/tmp \
    -v "$repo:/src" -v "$out:/out" -v "$cache:/cache" -w /src "$image" \
    bash tools/rockstar-launcher/build.sh /out
