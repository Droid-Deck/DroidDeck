#!/usr/bin/env bash
set -euo pipefail
D=${1:?output directory}
mkdir -p "$D"
aarch64-linux-gnu-g++ -shared -fPIC -O2 -Wall -Wno-attributes -Wno-nonnull-compare \
  -pthread -std=c++17 -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL \
  -o "$D/libfakeinput.so" app/src/main/cpp/fakeinput_steam.cpp -ldl
aarch64-linux-gnu-strip --strip-unneeded "$D/libfakeinput.so"
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
  -o "$D/libblsession.so" tools/linuxfs/preload/*.c -ldl
aarch64-linux-gnu-strip --strip-unneeded "$D/libblsession.so"
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
  -o "$D/libblfastpath.so" tools/proot/fastpath/fastpath.c -ldl
aarch64-linux-gnu-strip --strip-unneeded "$D/libblfastpath.so"
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -nostdlib -ffreestanding -fno-stack-protector \
  -fno-tree-loop-distribute-patterns -Wl,-z,defs -o "$D/libblaudit.so" tools/proot/fastpath/audit.c
aarch64-linux-gnu-strip --strip-unneeded "$D/libblaudit.so"
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
  -o "$D/libssbs.so" tools/linuxfs/ssbs/ssbs_adapter.c -ldl
aarch64-linux-gnu-strip --strip-unneeded "$D/libssbs.so"
mkdir -p "$D/usr/local/bin"
aarch64-linux-gnu-gcc -O2 -Wall -Wextra -o "$D/usr/local/bin/droiddeck-clipboard" tools/linuxfs/clipboard/clipboard.c -ldl
aarch64-linux-gnu-strip --strip-unneeded "$D/usr/local/bin/droiddeck-clipboard"
