#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-"$here/../../app/src/main/assets/linuxfs/usr/local/lib/droiddeck-epic"}
target=x86_64-pc-windows-gnu
rustup target add "$target" >/dev/null 2>&1 || true
cargo build --manifest-path "$here/Cargo.toml" --release --locked --target "$target"
mkdir -p "$out"
install -m644 "${CARGO_TARGET_DIR:-$here/target}/$target/release/EpicGamesLauncher.exe" "$out/EpicGamesLauncher.exe"
