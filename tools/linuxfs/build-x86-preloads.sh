#!/bin/sh
set -eu
out=${1:?usage: build-x86-preloads.sh <linuxfs asset directory>}
for arch in x86_64 i386; do
  case $arch in
    x86_64) bits=-m64 ;;
    i386) bits=-m32 ;;
  esac
  mkdir -p "$out/$arch"
  gcc $bits -shared -fPIC -O2 -Wall -pthread \
    -o "$out/$arch/libblsession.so" tools/linuxfs/preload/*.c -ldl
  g++ $bits -shared -fPIC -O2 -Wall -Wno-attributes -Wno-nonnull-compare \
    -pthread -std=c++17 -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL \
    -o "$out/$arch/libfakeinput.so" app/src/main/cpp/fakeinput_steam.cpp -ldl
  strip --strip-unneeded "$out/$arch/libblsession.so" "$out/$arch/libfakeinput.so"
  for lib in "$out/$arch/libblsession.so" "$out/$arch/libfakeinput.so"; do
    newest=$(objdump -T "$lib" | grep -o 'GLIBC_2\.[0-9]*' | sort -u -t. -k2,2n | tail -1)
    minor=${newest#GLIBC_2.}
    if [ "$minor" -gt 31 ]; then
      echo "$lib needs $newest; Steam Linux Runtime 3.0 has glibc 2.31" >&2
      exit 1
    fi
    if objdump -p "$lib" | grep -q 'NEEDED.*lib\(stdc++\|gcc_s\)'; then
      echo "$lib links the C++ runtime dynamically" >&2
      exit 1
    fi
  done
done
