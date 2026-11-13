#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${1:-"$here/../../app/src/main/resources/rockstar/helpers"}
mkdir -p "$out"
for target in i686-pc-windows-gnu x86_64-pc-windows-gnu; do
    rustup target add "$target" >/dev/null 2>&1 || true
    cargo build --manifest-path "$here/Cargo.toml" --release --locked --target "$target"
    name=Launcher64.exe
    [[ "$target" == i686-* ]] && name=Launcher32.exe
    install -m644 "${CARGO_TARGET_DIR:-$here/target}/$target/release/Launcher.exe" "$out/$name"
done
