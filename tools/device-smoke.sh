#!/usr/bin/env bash
# Smoke test of a DroidDeck build on a real device, over plain adb (no python, no root).
#
# What it does, in order, and restores afterwards:
#   1. prints the device (model, Android, kernel, page size, SoC);
#   2. installs --apk when given and launches the app;
#   3. checks that the app starts and that nothing in the crash buffer belongs to it;
#   4. changes the display density and the window size and checks the app keeps its process
#      (and does not recreate the activity that is on top);
#   5. on a foldable cycles the device state (half-opened, and with --closed also closed) through
#      `cmd device_state`, then puts the original state back;
#   6. reads the newest session folder of a debug build: first frame, last frame rate, the
#      memory page size and the last process exits.
#
# Usage: tools/device-smoke.sh [--serial SERIAL] [--apk FILE] [--pkg PACKAGE]
#                              [--no-density] [--no-size] [--no-fold] [--closed]
# Exit status: 0 when no check failed, 1 when one did, 2 for a usage or device problem.
#
# Not a replacement for tools/droiddeck-scenario, which drives a game through the agent bridge;
# this covers what that does not: display changes and foldable states.
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

pkg=com.droiddeck.launcher
serial=${ANDROID_SERIAL:-}
apk=
do_density=1
do_size=1
do_fold=1
do_closed=0
adb_bin=${ADB:-adb}

while [ $# -gt 0 ]; do
  case $1 in
    --serial) [ $# -ge 2 ] || { echo "--serial needs a value" >&2; exit 2; }; serial=$2; shift ;;
    --apk) [ $# -ge 2 ] || { echo "--apk needs a value" >&2; exit 2; }; apk=$2; shift ;;
    --pkg) [ $# -ge 2 ] || { echo "--pkg needs a value" >&2; exit 2; }; pkg=$2; shift ;;
    --no-density) do_density=0 ;;
    --no-size) do_size=0 ;;
    --no-fold) do_fold=0 ;;
    --closed) do_closed=1 ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

adb_cmd=("$adb_bin")
[ -n "$serial" ] && adb_cmd+=(-s "$serial")
A() {
  local out rc
  out=$("${adb_cmd[@]}" "$@" 2>&1); rc=$?
  [ -z "$out" ] || printf '%s\n' "$out" | tr -d '\r'
  return $rc
}
sh_() {
  local command= arg
  for arg in "$@"; do command+="'${arg//\'/\'\\\'\'}' "; done
  A shell "$command"
}

pass=0 fail=0 skip=0
ok()   { echo "  PASS  $*"; pass=$((pass + 1)); }
bad()  { echo "  FAIL  $*"; fail=$((fail + 1)); }
note() { echo "  SKIP  $*"; skip=$((skip + 1)); }
head_() { printf '\n== %s\n' "$*"; }

state=$(A get-state)
if [ "$state" != "device" ]; then
  echo "no authorized device (adb get-state: $state)" >&2
  exit 2
fi

density_snapshot=$(sh_ wm density) || density_snapshot=
size_snapshot=$(sh_ wm size) || size_snapshot=
orig_density=$(echo "$density_snapshot" | sed -n 's/.*Physical density: \([0-9]*\).*/\1/p' | head -1)
override_density=$(echo "$density_snapshot" | sed -n 's/.*Override density: \([0-9]*\).*/\1/p' | head -1)
override_size=$(echo "$size_snapshot" | sed -n 's/.*Override size: \([0-9]*x[0-9]*\).*/\1/p' | head -1)
ds=$(sh_ cmd device_state state 2>/dev/null)
orig_state=$(echo "$ds" | sed -n "s/.*Committed state: DeviceState{identifier=\([0-9]*\),.*/\1/p" | head -1)
pre_override=$(echo "$ds" | sed -n "s/.*Override state: DeviceState{identifier=\([0-9]*\),.*/\1/p" | head -1)

density_changed=0 size_changed=0 fold_changed=0
wm_override() { sed -n "s/.*Override $1: \\([0-9x]*\\).*/\\1/p" | head -1; }
restore_density() {
  sh_ wm density "${override_density:-reset}" >/dev/null || return 1
  local current
  current=$(sh_ wm density) || return 1
  echo "$current" | grep -q 'Physical density:' || return 1
  [ "$(echo "$current" | wm_override density)" = "$override_density" ]
}
restore_size() {
  sh_ wm size "${override_size:-reset}" >/dev/null || return 1
  local current
  current=$(sh_ wm size) || return 1
  echo "$current" | grep -q 'Physical size:' || return 1
  [ "$(echo "$current" | wm_override size)" = "$override_size" ]
}
# An override that was already in place (a test lab pins one) is put back; otherwise the request is cancelled.
restore_fold() { if [ -n "$pre_override" ]; then sh_ cmd device_state state "$pre_override" >/dev/null; else sh_ cmd device_state state reset >/dev/null; fi; }

restore() {
  local failed=0
  if [ "$density_changed" = 1 ]; then
    if restore_density; then density_changed=0; else bad "could not restore display density"; failed=1; fi
  fi
  if [ "$size_changed" = 1 ]; then
    if restore_size; then size_changed=0; else bad "could not restore window size"; failed=1; fi
  fi
  if [ "$fold_changed" = 1 ]; then
    if restore_fold; then fold_changed=0; else bad "could not restore fold override"; failed=1; fi
  fi
  return "$failed"
}
finish() {
  local status=$?
  restore || status=1
  printf '\n%d passed, %d failed, %d skipped\n' "$pass" "$fail" "$skip"
  [ "$fail" = 0 ] || status=1
  exit "$status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

top_activity() {
  sh_ dumpsys activity activities 2>/dev/null | sed -n 's/.*topResumedActivity=ActivityRecord{[^ ]* u[0-9]* \([^ ]*\) .*/\1/p' | head -1
}
app_pid() { sh_ pidof "$pkg" | awk '{print $1}'; }
app_crashes() {
  local out pat
  out=$(sh_ logcat -b crash -d) || return 1
  pat=$(printf '%s' "$pkg" | sed 's/[.[\*^$]/\\&/g')
  printf '%s\n' "$out" | grep -c -E "(Process: |Cmdline: |>>> )$pat([ ,<:]|$)" || true
}
relaunches_of() {  # $1 = activity class fragment
  sh_ logcat -b events -d 2>/dev/null | grep -E 'wm_(relaunch|destroy|create)_activity' | grep -c "$1"
}

head_ "Device"
echo "  model        $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model)"
echo "  android      $(sh_ getprop ro.build.version.release) (API $(sh_ getprop ro.build.version.sdk)), One UI $(sh_ getprop ro.build.version.oneui)"
echo "  kernel       $(sh_ uname -r)"
page=$(sh_ getconf PAGE_SIZE)
echo "  page size    $page"
echo "  soc          $(sh_ getprop ro.soc.manufacturer) $(sh_ getprop ro.soc.model) ($(sh_ getprop ro.board.platform))"
echo "  gpu          $(sh_ cat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null)"
echo "  display      $(sh_ wm size | tr '\n' ' ') $(sh_ wm density | tr '\n' ' ')"
[ "$page" = 4096 ] || [ "$page" = 16384 ] && ok "page size is $page" || bad "page size reads '$page'"

head_ "Install and launch"
sh_ logcat -b crash -c >/dev/null
sh_ logcat -b events -c >/dev/null
if [ -n "$apk" ]; then
  r=$(A install -r -t "$apk" | tail -1)
  case $r in *Success*) ok "installed $(basename "$apk")" ;; *) bad "install: $r"; exit 1 ;; esac
fi
packages=$(sh_ pm list packages "$pkg") || { echo "could not query installed packages" >&2; exit 2; }
if ! printf '%s\n' "$packages" | grep -Fxq "package:$pkg"; then echo "package $pkg is not installed" >&2; exit 2; fi
if ! launch=$(sh_ am start -n "$pkg/.MainActivity") || printf '%s\n' "$launch" | grep -q '^Error'; then
  bad "could not launch $pkg/.MainActivity"
fi
sleep 6
pid=$(app_pid)
[ -n "$pid" ] && ok "process running (pid $pid)" || bad "the app has no process after launch"
t=$(top_activity)
case $t in
  "$pkg"/*|*permissioncontroller*|*GrantPermissions*) ok "top activity: $t" ;;
  *) bad "top activity is '$t'" ;;
esac
if c=$(app_crashes); then
  [ "${c:-0}" = 0 ] && ok "nothing in the crash buffer belongs to the app" || bad "$c crash entries mention the app"
else
  bad "could not read the crash buffer"
fi

if [ "$do_density" = 1 ] && [ -n "$orig_density" ]; then
  head_ "Density change"
  target=$((orig_density * 5 / 4))
  before=$(top_activity); pid0=$(app_pid)
  sh_ logcat -b events -c >/dev/null
  density_changed=1
  wm_status=0
  sh_ wm density "$target" >/dev/null || wm_status=$?
  sleep 6
  applied=$(sh_ wm density) || wm_status=1
  after=$(top_activity); pid1=$(app_pid)
  if [ "$wm_status" -ne 0 ] || [ "$(echo "$applied" | wm_override density)" != "$target" ]; then
    bad "wm density failed"
  else
    if [ -n "$pid1" ] && [ "$pid0" = "$pid1" ]; then
      ok "same process after $orig_density -> $target dpi"
    else
      bad "process changed ($pid0 -> $pid1)"
    fi
    case $before in "$pkg"/*)
      n=$(relaunches_of "${before#*/}")
      [ "${n:-0}" = 0 ] && ok "$before was not recreated" || bad "$before was recreated ${n}x"
      [ "$after" = "$before" ] && ok "same top activity after the change" || bad "top activity changed ($before -> $after)"
    ;; *) note "top activity was not the app ($before)" ;; esac
  fi
  if restore_density; then
    density_changed=0
  else
    bad "could not restore display density"
  fi
  sleep 3
elif [ "$do_density" = 1 ]; then
  note "could not read the original display density"
fi

if [ "$do_size" = 1 ]; then
  head_ "Window size change"
  phys=$(echo "$size_snapshot" | sed -n 's/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1)
  set -- $phys
  if [ -n "${1:-}" ]; then
    w=$(( ($2 * 2 / 3) / 2 * 2 )); h=$1   # a narrower, taller window, like a cover screen
    pid0=$(app_pid)
    size_changed=1
    wm_status=0
    sh_ wm size "${w}x${h}" >/dev/null || wm_status=$?
    sleep 6
    applied=$(sh_ wm size) || wm_status=1
    pid1=$(app_pid)
    if [ "$wm_status" -ne 0 ] || [ "$(echo "$applied" | wm_override size)" != "${w}x${h}" ]; then
      bad "wm size failed"
    elif [ -n "$pid1" ] && [ "$pid0" = "$pid1" ]; then
      ok "same process at ${w}x${h}"
    else
      bad "process changed ($pid0 -> $pid1)"
    fi
    if restore_size; then
      size_changed=0
    else
      bad "could not restore window size"
    fi
    sleep 3
  else
    note "could not read the physical size"
  fi
fi

if [ "$do_fold" = 1 ]; then
  head_ "Foldable states"
  states=$(sh_ cmd device_state print-states 2>/dev/null)
  if ! echo "$states" | grep -q "identifier"; then
    note "no device_state service (not a foldable, or not permitted)"
  elif [ -z "$orig_state" ]; then
    note "could not read the current device_state identifier"
  else
    id_of() { echo "$states" | sed -n "s/.*identifier=\([0-9]*\), name='$1'.*/\1/p" | head -1; }
    half=$(id_of HALF_OPENED); closed=$(id_of CLOSED)
    cycle() {  # $1 name  $2 id
      pid0=$(app_pid)
      fold_changed=1
      sh_ cmd device_state state "$2" >/dev/null; sleep 8
      got=$(sh_ cmd device_state state | sed -n "s/.*Committed state: DeviceState{identifier=[0-9]*, name='\([A-Z_]*\)'.*/\1/p" | head -1)
      pid1=$(app_pid)
      [ "$got" = "$1" ] && ok "device state is $1" || note "asked for $1, committed '$got'"
      [ -n "$pid1" ] && [ "$pid0" = "$pid1" ] && ok "same process in $1" || bad "process changed in $1 ($pid0 -> $pid1)"
      restore_fold; sleep 6
    }
    if [ -n "$half" ]; then cycle HALF_OPENED "$half"; else note "no HALF_OPENED state"; fi
    if [ "$do_closed" = 1 ] && [ -n "$closed" ]; then
      cycle CLOSED "$closed"
      note "CLOSED turns the screens off: the session pauses (Background behaviour), Resume it from the app"
    fi
    got=$(sh_ cmd device_state state | sed -n "s/.*Committed state: DeviceState{identifier=\([0-9]*\),.*/\1/p" | head -1)
    [ "$got" = "$orig_state" ] && ok "device state restored ($orig_state)" || bad "device state is $got, was $orig_state"
  fi
fi

head_ "Session log (debug builds)"
dir=$(sh_ run-as "$pkg" ls -t files/logs 2>/dev/null | head -1)
if [ -z "$dir" ] || echo "$dir" | grep -q -i "not debuggable\|unknown package"; then
  note "no readable session folder (release build, or no session has run)"
else
  echo "  newest session  $dir"
  ev=$(sh_ run-as "$pkg" cat "files/logs/$dir/events.jsonl" 2>/dev/null)
  echo "$ev" | grep -q '"frame.first"' && ok "first frame was drawn" || note "no frame.first in $dir"
  echo "$ev" | grep -q '"session.failed"' && bad "session.failed: $(echo "$ev" | grep '"session.failed"' | tail -1 | cut -c1-160)"
  fps=$(sh_ run-as "$pkg" cat "files/logs/$dir/app.log" 2>/dev/null | grep 'last 10 s:' | tail -1 | sed 's/.*last 10 s: //' | cut -c1-70)
  [ -n "$fps" ] && echo "  last stats      $fps"
  sh_ run-as "$pkg" cat "files/logs/$dir/device.txt" 2>/dev/null | grep -E 'Memory page size|^Kernel' | sed 's/^/  report          /'
fi

[ "$fail" = 0 ]
