#!/usr/bin/env bash
# Shared native ABI checks for CI and local builds.
set -euo pipefail
D=${1:?asset directory}
NEED=$("${READELF:-readelf}" -d "$D/libfakeinput.so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p')
for bad in libstdc++.so.6 libgcc_s.so.1; do
  if printf '%s\n' "$NEED" | grep -qx "$bad"; then
    echo "::error::libfakeinput.so links $bad - the C++ runtime must stay static"; exit 1
  fi
done
syms() { "${READELF:-readelf}" -Ws "$1" | awk '$4 == "FUNC" && $5 == "GLOBAL" {sub(/@.*/, "", $8); print $8}'; }
FAKE=$(syms "$D/libfakeinput.so")
for sym in open openat ioctl read close poll ppoll select stat fstat access scandir; do
  grep -qx "$sym" <<<"$FAKE" || { echo "::error::libfakeinput.so does not export $sym"; exit 1; }
done
# The static C++ runtime stays private: every session process preloads this library,
# and an exported personality routine takes over other programs' unwinding (shadPS4
# aborted in pthread_exit).
if grep -qxE '__gxx_personality_v0|__cxa_throw|_Unwind_Resume' <<<"$FAKE"; then
  echo "::error::libfakeinput.so exports the C++ runtime"; exit 1
fi
SESSION=$(syms "$D/libblsession.so")
for sym in socket bind getsockname setsockopt statfs statvfs syscall shm_open shm_unlink mmap mmap64 munmap drmPrimeFDToHandle drmCloseBufferHandle; do
  grep -qx "$sym" <<<"$SESSION" || { echo "::error::libblsession.so does not export $sym"; exit 1; }
done
# Zink's GEM_CLOSE is caught at ioctl(); an exported drmIoctl made NMS replay old frames.
if grep -qx drmIoctl <<<"$SESSION"; then
  echo "::error::libblsession.so exports drmIoctl"; exit 1
fi
SSBS=$(syms "$D/libssbs.so")
for sym in sigaction signal bsd_signal sysv_signal; do
  grep -qx "$sym" <<<"$SSBS" || { echo "::error::libssbs.so does not export $sym"; exit 1; }
done
test -f "$D/usr/local/bin/droiddeck-session"
test -f "$D/usr/local/bin/droiddeck-proton-extra"
