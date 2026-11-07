#!/system/bin/sh
# Keeps the memory map of a running game process in the app's cache directory, so a crash address
# in Wine's log (handle_syscall_fault pc=...) can be matched to a library afterwards.
# On the phone, as the app (from Git Bash, with MSYS_NO_PATHCONV=1):
#   adb push tools/venus/watch-maps.sh /data/local/tmp/
#   adb shell "run-as com.droiddeck.launcher sh /data/local/tmp/watch-maps.sh 'Hollow Knight Silksong.exe'"
# Runs 15 minutes. Then: adb exec-out "run-as com.droiddeck.launcher cat cache/game-maps.txt" > maps.txt
name=${1:?process name, as ps shows it}
cd /data/user/0/com.droiddeck.launcher || exit 1
rm -f cache/game-maps.txt
i=0
while [ $i -lt 900 ]; do
    p=$(ps -A -o PID,NAME | grep -F "$name" | grep -v Crash | head -1 | awk '{print $1}')
    if [ -n "$p" ]; then
        cat /proc/$p/maps > cache/game-maps.tmp 2>/dev/null && mv cache/game-maps.tmp cache/game-maps.txt
    fi
    sleep 1
    i=$((i + 1))
done
