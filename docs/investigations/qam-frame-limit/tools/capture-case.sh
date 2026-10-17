#!/bin/bash
# capture-case.sh <name> [busy-loops]
# Pans the camera (right stick) for 14 s, records 10 s of the screen, and saves the matching slice
# of gamescope's DD_FRAMETRACE log plus the compositor's logcat lines into $WORK/runs/<name>.
# Optional busy loops add CPU load inside the guest for 40 s.
: "${ADB_SERIAL:?set ADB_SERIAL}" "${WORK:?set WORK to a scratch directory}"
HERE="$(cd "$(dirname "$0")" && pwd)"; CTL="$(git rev-parse --show-toplevel)/tools/droiddeckctl"
n=$1; load=${2:-0}; out=$WORK/runs/$n; mkdir -p "$out"
[ "$load" -gt 0 ] && GT=10 "$HERE/guest.sh" "for i in \$(seq 1 $load); do nohup timeout 40 sh -c 'while :; do :; done' >/dev/null 2>&1 & done" && sleep 4
adb -s "$ADB_SERIAL" logcat -c
t0=$(GT=10 "$HERE/guest.sh" 'tail -c 300 /tmp/ddtrace.log | tail -1 | cut -d" " -f1')
python3 "$CTL" input stick right 0.6 0 --hold 14000 >/dev/null 2>&1 &
sleep 2
python3 "$CTL" record "$out/clip.mp4" --seconds 10 --display 0 >/dev/null 2>&1
wait
t1=$(( t0 + 16000000000 ))
GT=60 "$HERE/guest.sh" "awk -v a=$t0 -v b=$t1 '\$1>=a && \$1<=b' /tmp/ddtrace.log" > "$out/trace.txt"
adb -s "$ADB_SERIAL" logcat -d | grep -E 'DroidDeckWayland|LsfgEngine|FrameGen' > "$out/log.txt"
# With patches/compositor-frame-trace.patch applied and files/comptrace.on present:
adb -s "$ADB_SERIAL" shell "su -c 'awk -v a=$t0 -v b=$t1 \"\\\$1>=a && \\\$1<=b\" /data/user/0/com.droiddeck.launcher/files/comptrace.log 2>/dev/null'" > "$out/ctrace.txt"
wc -l "$out"/trace.txt "$out"/ctrace.txt
