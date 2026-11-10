#!/bin/sh
set -eu
export LANG=C.UTF-8
src=/build/kwin-6.6.6
mkdir -p /build
if [ ! -d "$src/debian" ]; then
    dpkg-source -x /work/kwin_6.6.6-0ubuntu0.1.dsc "$src"
    for patchfile in /repo/tools/ubuntu/patches/kwin/*.patch; do
        patch -d "$src" -p1 < "$patchfile"
    done
fi
python3 /repo/tools/ubuntu/kwin-independent.py "$src"
if [ -x /opt/cross/cxx ]; then
    set -- -DCMAKE_C_COMPILER=/opt/cross/cc -DCMAKE_CXX_COMPILER=/opt/cross/cxx
else
    set --
fi
cmake -S "$src" -B /build/out -G Ninja "$@" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF -DKWIN_BUILD_KCMS=OFF -DKWIN_BUILD_DOC=OFF \
    -DKWIN_BUILD_SCREENLOCKER=ON -DCMAKE_INSTALL_PREFIX=/usr
cmake --build /build/out --target kwin kwin_wayland --parallel 6
python3 /repo/tools/ubuntu/kwin-independent.py "$src"
cmake --build /build/out --target kwin kwin_wayland --parallel 6
mkdir -p /work/result
library=$(find /build/out -name libkwin.so.6 -print -quit)
test -n "$library"
cp -L "$library" /work/result/
binary=$(find /build/out -type f -name kwin_wayland -print -quit)
test -n "$binary"
cp "$binary" /work/result/
sha256sum /work/result/libkwin.so.6 /work/result/kwin_wayland | sed 's|/work/result/||' > /work/result/kwin.sha256
cp "$src/LICENSES/GPL-2.0-or-later.txt" /work/result/LICENSE
python3 - <<'PY'
from pathlib import Path
import hashlib, json, subprocess
def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()
sources = ["/repo/tools/ubuntu/kwin-independent.cpp", "/repo/tools/ubuntu/kwin-independent.py",
           "/repo/tools/ubuntu/kwin-output-layout.h",
           "/repo/tools/ubuntu/kwin-ahb-output.h",
           "/repo/tools/ubuntu/kwin-output-opacity.h",
           "/repo/tools/ubuntu/kwin-independent-filter.h",
           "/repo/tools/ubuntu/kwin-passthrough.cpp"]
sources += [str(path) for path in Path("/work").glob("kwin_6.6.6*.tar.*") if path.is_file()]
record = {"kwin": "6.6.6", "systemPackage": "4:6.6.6-0ubuntu0.1+droiddeck1",
          "sources": {Path(p).name: digest(p) for p in sources},
          "outputs": {p.name: digest(p) for p in Path("/work/result").iterdir()
                      if p.is_file() and p.name != "build.json"},
          "dependencies": subprocess.check_output(
              ["dpkg-query", "-W", "libqt6core6t64", "libkf6configcore6", "libc6"]).decode()}
Path("/work/result/build.json").write_text(json.dumps(record, indent=2))
PY
