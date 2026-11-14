#!/bin/sh
set -eu
# Run on native 64-bit Linux/glibc. This is not an Android/Bionic library.
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output=${1:?usage: build.sh /absolute/build-directory [Vulkan-header-directory]}
mkdir -p "$output"
cc=${CC:-cc}
"$cc" -std=gnu11 -Wall -Wextra -Werror -O2 -fPIC -shared -s \
  "$base/low32.c" -ldl -o "$output/libdroiddeck-low32.so"
"$cc" -std=gnu11 -Wall -Wextra -Werror -O2 "$base/test-low32.c" -pthread -o "$output/test-low32"
LD_PRELOAD="$output/libdroiddeck-low32.so" "$output/test-low32"
"$cc" -std=gnu11 -Wall -Wextra -Werror -O2 -fPIC -shared \
  "$base/test-chain-hook.c" -ldl -o "$output/test-chain-hook.so"
"$cc" -std=gnu11 -Wall -Wextra -Werror -O2 "$base/test-preload-chain.c" -ldl -o "$output/test-preload-chain"
for preloads in "$output/libdroiddeck-low32.so:$output/test-chain-hook.so" \
                "$output/test-chain-hook.so:$output/libdroiddeck-low32.so"; do
  for mode in off trace low32; do
    LD_PRELOAD="$preloads" "$output/test-preload-chain" "$mode"
  done
done
if [ "$#" -ge 2 ]; then
  "$cc" -std=gnu11 -Wall -Wextra -Werror -O2 -s -I"$2" "$base/gpu-copy.c" -ldl -o "$output/gpu-copy-native"
  if command -v i686-w64-mingw32-gcc >/dev/null 2>&1; then
    i686-w64-mingw32-gcc -std=gnu11 -Wall -Wextra -Werror -O2 -s -I"$2" \
      "$base/gpu-copy.c" -o "$output/gpu-copy-x86.exe"
  fi
fi
