#!/usr/bin/env bash
# Builds droiddeck-upc.exe for Wine and stages it with the guest scripts.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-"$here/../../app/src/main/assets/linuxfs/usr/local/lib/droiddeck-ubisoft"}
target=x86_64-pc-windows-gnu
rustup target add "$target" >/dev/null 2>&1 || true
cargo build --manifest-path "$here/Cargo.toml" --release --locked --target "$target"
mkdir -p "$out"
install -m644 "${CARGO_TARGET_DIR:-$here/target}/$target/release/droiddeck-upc.exe" "$out/droiddeck-upc.exe"
