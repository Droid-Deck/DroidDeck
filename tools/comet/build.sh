#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
out=${1:-"$repo/app/src/main/assets/linuxfs/usr/local/lib/droiddeck-comet"}
cache=${COMET_BUILD_DIR:-"$repo/app/build/comet"}
commit=3d7488ee72f83b7e8462a70fd4c4638d6da71360
mkdir -p "$cache" "$out"
if [ ! -d "$cache/source/.git" ]; then
  git clone https://github.com/imLinguin/comet.git "$cache/source"
fi
git -C "$cache/source" reset --hard "$commit"
git -C "$cache/source" submodule update --init
git -C "$cache/source" apply "$repo/tools/comet/droiddeck.patch"
cd "$cache/source"
cargo build --locked --release --target aarch64-unknown-linux-gnu
install -m644 target/aarch64-unknown-linux-gnu/release/comet "$out/comet"
zip="$cache/dummy-service.zip"
if ! echo "7fc5facd1fce4d516cd3ce527e7e4dbe0bfd17649cea805e8ea7c37d4ba58d9a  $zip" | sha256sum -c - >/dev/null 2>&1; then
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o "$zip" https://github.com/imLinguin/comet/releases/download/v0.3.2/dummy-service.zip
fi
echo "7fc5facd1fce4d516cd3ce527e7e4dbe0bfd17649cea805e8ea7c37d4ba58d9a  $zip" | sha256sum -c -
unzip -o "$zip" GalaxyCommunication.exe update-permissions.exe -d "$out"
install -m644 "$repo/tools/comet/LICENSE" "$out/LICENSE"
printf '%s\n' "$commit" > "$out/version"
