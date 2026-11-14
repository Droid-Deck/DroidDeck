#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out=$(realpath -m "${1:?output directory}")
mkdir -p "$out"

docker run --rm -i --platform linux/arm64 \
    --mount "type=bind,source=$repo,target=/repo,readonly" \
    --mount "type=bind,source=$out,target=/out" \
    -e "DROIDDECK_BUILD_JOBS=${DROIDDECK_BUILD_JOBS:-6}" \
    -e "DROIDDECK_OUTPUT_OWNER=$(id -u):$(id -g)" \
    ubuntu:26.04 bash -s <<'BUILD'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
version=4:6.6.6-0ubuntu0.1
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/ubuntu.sources
apt-get update -qq
apt-get install -y -qq build-essential cmake ninja-build python3 dpkg-dev patch >/dev/null
mkdir -p /work
cd /work
apt-get source -qq "kwin=$version"
src=/work/kwin-6.6.6
test "$(dpkg-parsechangelog -l "$src/debian/changelog" -S Version)" = "$version"
apt-get build-dep -y -qq "$src" >/dev/null
for patchfile in /repo/tools/ubuntu/patches/kwin/*.patch; do
    patch -d "$src" -p1 --forward < "$patchfile"
done
python3 /repo/tools/ubuntu/kwin-independent.py "$src"
cmake -S "$src" -B /work/build -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF -DKWIN_BUILD_KCMS=OFF -DKWIN_BUILD_DOC=OFF \
    -DKWIN_BUILD_SCREENLOCKER=ON -DCMAKE_INSTALL_PREFIX=/usr
cmake --build /work/build --target kwin kwin_wayland --parallel "$DROIDDECK_BUILD_JOBS"
install -m644 /work/build/bin/libkwin.so.6 /out/libkwin.so.6
install -m755 /work/build/bin/kwin_wayland /out/kwin_wayland
install -m644 "$src/LICENSES/GPL-2.0-or-later.txt" /out/LICENSE
python3 - <<'PY'
import hashlib
import json
from pathlib import Path
import subprocess

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

repo = Path("/repo")
helpers = ["build-kwin-runtime.sh", "kwin-independent.py", "kwin-independent.cpp",
           "kwin-passthrough.cpp", "kwin-output-layout.h", "kwin-ahb-output.h",
           "kwin-output-opacity.h", "kwin-independent-filter.h"]
sources = [repo / "tools/ubuntu" / name for name in helpers]
sources += list((repo / "tools/ubuntu/patches/kwin").glob("*.patch"))
sources += [repo / "app/src/main/cpp/waylandcomp/src/ahb_broker_protocol.h"]
out = Path("/out")
outputs = {name: digest(out / name) for name in ("libkwin.so.6", "kwin_wayland", "LICENSE")}
(out / "kwin.sha256").write_text("".join(f"{value}  {name}\n" for name, value in sorted(outputs.items())))
record = {
    "kwin": "6.6.6",
    "systemPackage": "4:6.6.6-0ubuntu0.1+droiddeck1",
    "sources": {path.relative_to(repo).as_posix(): digest(path) for path in sources},
    "upstream": {path.name: digest(path) for path in Path("/work").glob("kwin_*") if path.is_file()},
    "outputs": dict(outputs, **{"kwin.sha256": digest(out / "kwin.sha256")}),
    "dependencies": subprocess.check_output(["dpkg-query", "-W"], text=True),
}
(out / "build.json").write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
PY
chown -R "$DROIDDECK_OUTPUT_OWNER" /out
BUILD
