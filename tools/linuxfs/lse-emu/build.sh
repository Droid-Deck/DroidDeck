#!/usr/bin/env bash
set -euo pipefail
D=${1:?asset directory}
CC=${CC:-aarch64-linux-gnu-gcc}
STRIP=${STRIP:-aarch64-linux-gnu-strip}
here=$(cd "$(dirname "$0")" && pwd)
out="$D/usr/local/lib/droiddeck-lse/liblse_emu.so"
mkdir -p "$(dirname "$out")"
"$CC" -shared -fPIC -O2 -march=armv8-a -mno-outline-atomics -Wall -Wextra -Wno-unused-parameter \
  -Wl,-z,now,-z,max-page-size=65536 -o "$out" "$here/lse_emu.c" -ldl
"$STRIP" --strip-unneeded "$out"
disassembly=$("${OBJDUMP:-aarch64-linux-gnu-objdump}" -d "$out")
if grep -qE $'\t((ld|st)(add|clr|eor|set|smax|smin|umax|umin)|swp|cas)[a-z]*\t' <<<"$disassembly"; then
  echo "::error::liblse_emu.so itself contains LSE instructions"; exit 1
fi
