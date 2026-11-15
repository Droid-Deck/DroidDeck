#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$1"
out=$(cd "$1" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

tool() {
  local found
  found=$(command -v "$1" || ls /usr/lib/llvm-*/bin/"$1" 2>/dev/null | sort -V | tail -n1 || true)
  [ -n "$found" ] || { echo "dirt-ws2: $1 not found" >&2; exit 1; }
  echo "$found"
}
clang=$(tool clang)
dlltool=$(tool llvm-dlltool)
tool ld.lld > /dev/null

python3 "$here/gen.py" generate "$here" "$work"
"$dlltool" -m i386 -k -d "$here/kernel32.def" -l "$work/libkernel32.a"
"$clang" --target=i686-w64-mingw32 -O2 -Wall -Wextra -Werror -ffreestanding -fno-stack-protector -fno-builtin \
  -nostdlib -shared -fuse-ld=lld -o "$work/ws2_32.dll" "$here/ws2_32.c" "$work/thunks.c" "$work/ws2_32.def" \
  -L"$work" -lkernel32 -Wl,--entry,DllMain@12 -Wl,--no-insert-timestamp
python3 "$here/gen.py" verify "$here" "$work/ws2_32.dll"
install -m 644 "$work/ws2_32.dll" "$out/ws2_32.dll"
