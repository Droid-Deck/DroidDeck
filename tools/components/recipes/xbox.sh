#!/usr/bin/env bash
set -euo pipefail
rustup toolchain install 1.99.0 --profile minimal
bash tools/xbox/build.sh
