#!/bin/bash
# Function, source line and disassembly at offsets into an aarch64 library, with the NDK's tools.
# Run in WSL: bash addr2line.sh <library> <offset>...
# The offset is the crash pc minus the library's start in the process's maps (tools/venus/watch-maps.sh).
LIB=${1:?library}
shift
B=/home/delo/android-sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin
md5sum "$LIB"
$B/llvm-addr2line -f -C -i -e "$LIB" "$@"
for a in "$@"; do
    start=$(printf "0x%x" $((a - 48)))
    stop=$(printf "0x%x" $((a + 16)))
    $B/llvm-objdump -d --no-show-raw-insn --start-address=$start --stop-address=$stop "$LIB" | tail -n +7
done
