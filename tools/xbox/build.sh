#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
cache=${XBOX_BUILD_DIR:-"$repo/app/build/xbox"}
out=${1:-"$repo/app/src/main/assets/linuxfs/usr/local/lib/droiddeck-xbox"}
mkdir -p "$cache" "$out"
source_dir=$(python3 "$repo/tools/xbox/prepare.py" "$cache")
rust_version=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["rust"])' "$repo/tools/xbox/backend.lock.json")
export RUSTUP_TOOLCHAIN=$rust_version
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$cache/target"}
rustup target add aarch64-unknown-linux-gnu
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=${CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER:-aarch64-linux-gnu-gcc}
if [[ $(uname -m) = aarch64 ]]; then export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=cc; fi
cd "$source_dir"
cargo build --locked --release --no-default-features --target aarch64-unknown-linux-gnu -p xodus-cli -p xodus-service
if [[ $(uname -m) = aarch64 ]]; then
    cargo test --locked --release --no-default-features --target aarch64-unknown-linux-gnu -p xodus-cli
    cargo test --locked --release --no-default-features --target aarch64-unknown-linux-gnu -p xodus credential_
    cargo test --locked --release --no-default-features --target aarch64-unknown-linux-gnu -p msixvc streaming_
fi
for binary in xodus-cli xodus-service; do
    install -m755 "$CARGO_TARGET_DIR/aarch64-unknown-linux-gnu/release/$binary" "$out/$binary"
done
python3 "$repo/tools/xbox/runtime.py" "$cache/runtime" "$out/windows"
install -m644 "$repo/tools/xbox/LICENSE.xodus" "$out/LICENSE.xodus"
install -m644 "$repo/tools/xbox/LICENSE.nexus" "$out/LICENSE.nexus"
install -m644 "$repo/tools/xbox/LICENSE.LGPL-2.1" "$out/LICENSE.LGPL-2.1"
install -m644 "$repo/tools/xbox/LICENSE.nghttp2" "$out/LICENSE.nghttp2"
cp "$repo/tools/xbox/"*.lock.json "$out/"
python3 - "$out" "$repo/tools/xbox/droiddeck-credentials.patch" <<'PY'
import hashlib,json,pathlib,sys
out=pathlib.Path(sys.argv[1])
files={str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for p in out.rglob('*') if p.is_file() and p.name!='version'}
files['credentialPatch']=hashlib.sha256(pathlib.Path(sys.argv[2]).read_bytes()).hexdigest()
(out/'version').write_text(hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()+'\n')
PY
tar -cJf "$cache/xodus-droiddeck-source.tar.xz" --exclude=.git --exclude=target -C "$source_dir" . -C "$repo" tools/xbox
