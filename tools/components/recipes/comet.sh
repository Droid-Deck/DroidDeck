#!/usr/bin/env bash
set -euo pipefail
rustup toolchain install 1.90.0 --profile minimal
rustup target add --toolchain 1.90.0 aarch64-unknown-linux-gnu
RUSTUP_TOOLCHAIN=1.90.0 bash tools/comet/build.sh
