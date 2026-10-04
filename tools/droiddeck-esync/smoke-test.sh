#!/bin/bash
set -euo pipefail

if [ $# -ne 4 ]; then
  echo "usage: $0 <proton-dir> <pack.tzst> <esstress.exe> <work-dir>" >&2
  exit 2
fi
tool=$(realpath "$1")
archive=$(realpath "$2")
exe=$(realpath "$3")
work=$(realpath -m "$4")
here=$(cd "$(dirname "$0")" && pwd)
guest=$here/../linuxfs/overlay/usr/local/bin/droiddeck-esync
rm -rf "$work"
mkdir -p "$work/pack"
tar --zstd -xf "$archive" -C "$work/pack"
python3 "$guest" build-shadow "$tool" "$work/pack" "$work/dist" >/dev/null
[ -e "$work/dist/files/bin" ] || ln -s bin-arm64 "$work/dist/files/bin"
export WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="

run() {
  local mode=$1 bin=$2 want=$3
  shift 3
  local prefix=$work/prefix-$mode
  mkdir -p "$prefix"
  (
    export WINEPREFIX=$prefix WINESERVER=$bin/wineserver "$@"
    timeout "${BOOT_TIMEOUT:-900}" "$bin/wine" wineboot -i > "$work/$mode-boot.log" 2>&1 || true
    "$bin/wineserver" -w || true
    if ! grep -q "$want" "$work/$mode-boot.log"; then
      echo "$mode: wine never said \"$want\""
      tail -n 30 "$work/$mode-boot.log"
      exit 1
    fi
    cp "$exe" "$prefix/drive_c/esstress.exe"
    status=0
    timeout "${STRESS_TIMEOUT:-1800}" "$bin/wine" 'C:\esstress.exe' > "$work/$mode-stress.log" 2>&1 || status=$?
    "$bin/wineserver" -k 2>/dev/null || true
    if ! grep -q '^esstress:' "$work/$mode-stress.log"; then
      echo "$mode: esstress did not finish (status $status)"
      tail -n 30 "$work/$mode-stress.log"
      exit 1
    fi
    grep -o '^FAIL [^ ]*:[0-9]*:' "$work/$mode-stress.log" | sort -u > "$work/$mode-failures" || true
    echo "$mode: $(grep -E '^esstress:' "$work/$mode-stress.log" | tail -n 1)"
  )
}

if ! run stock "$tool/files/bin-arm64" "server-side synchronization" PROTON_NO_NTSYNC=1 WINEESYNC=0 WINEFSYNC=0; then
  echo "stock Proton does not run here either; the pack cannot be judged on this machine"
  exit 3
fi
run esync "$work/dist/files/bin-arm64" "esync: up and running" PROTON_NO_NTSYNC=1 WINEESYNC=1 WINEFSYNC=0
if [ -s "$work/stock-failures" ]; then
  echo "stock fails these as well, so they do not count against the pack: $(tr '\n' ' ' < "$work/stock-failures")"
fi
new=$(comm -13 "$work/stock-failures" "$work/esync-failures")
if [ -n "$new" ]; then
  echo "esync: fails checks stock passes:"
  grep -F "$new" "$work/esync-stress.log" || echo "$new"
  exit 1
fi
