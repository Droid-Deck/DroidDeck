#!/usr/bin/env bash
#
# Tests the capability probe without a phone.
#
# The probe talks to a phone over hdc, so it cannot be run until one is
# connected. This test stands in for hdc with a stub that answers the
# commands the probe asks, and checks that the probe reads the facts and
# draws the right conclusion from them.
#
# Usage: tools/test_check_device_capabilities.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
probe="${root}/tools/check_device_capabilities.sh"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# --- the stub hdc ---------------------------------------------------------

mkdir -p "${work}/stub"
cat > "${work}/stub/hdc" <<'STUB'
#!/bin/bash
# A stand-in for hdc: it answers the commands the capability probe asks.
case "$1" in
  list)
    if [ "${STUB_NO_DEVICE:-0}" = "1" ]; then
      echo "[Empty]"
      exit 0
    fi
    echo "mock-device-1"
    exit 0
    ;;
  shell)
    shift
    case "$*" in
      "uname -m")
        echo "aarch64"
        ;;
      "param get const.product.model")
        echo "ALN-AL80"
        ;;
      "cat /proc/cpuinfo")
        cat "${STUB_DIR}/cpuinfo"
        ;;
      "cat /proc/meminfo")
        cat "${STUB_DIR}/meminfo"
        ;;
      "df -k /data")
        cat "${STUB_DIR}/df"
        ;;
      "hilog -T DroidDeck -x")
        cat "${STUB_DIR}/hilog"
        ;;
      *)
        exit 0
        ;;
    esac
    exit 0
    ;;
esac
exit 0
STUB
chmod +x "${work}/stub/hdc"

# --- the phone we pretend to have ------------------------------------------

cat > "${work}/cpuinfo" <<'CPUINFO'
processor	: 0
BogoMIPS	: 38.40
processor	: 1
BogoMIPS	: 38.40
processor	: 2
BogoMIPS	: 38.40
processor	: 3
BogoMIPS	: 38.40
processor	: 4
BogoMIPS	: 38.40
processor	: 5
BogoMIPS	: 38.40
processor	: 6
BogoMIPS	: 38.40
processor	: 7
BogoMIPS	: 38.40

Hardware	: Kirin 9020
CPU architecture: 8
CPU part	: 0xd41
CPU revision	: 0
CPU implementer	: 0x41
CPU variant	: 0x0
CPU part	: 0xd41
CPU revision	: 0

Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp
CPU implementer	: 0x41
CPU architecture: 8
CPU variant	: 0x0
CPU part	: 0xd41
CPU revision	: 0
CPUINFO

printf 'MemTotal:        8123456 kB\nMemFree:         3210987 kB\nMemAvailable:    5123456 kB\n' > "${work}/meminfo"
printf 'Filesystem 1K-blocks     Used Available Use%% Mounted on\n/dev/block       118000000 20000000 98000000  17%% /data\n' > "${work}/df"

cat > "${work}/hilog" <<'HILOG'
01-01 00:00:00.000  1000  1000 I DroidDeck: gpu renderer=Mali-G78 vendor=ARM model=78
01-01 00:00:00.001  1000  1000 I DroidDeck: gles version=OpenGL ES 3.2 V@0.0.0
HILOG

export STUB_DIR="${work}"

failures=0
checks=0
check() {  # check <description> <pattern> <text>
  checks=$((checks + 1))
  if printf '%s' "$3" | grep -q -- "$2"; then
    echo "  ok    $1"
  else
    echo "  FAIL  $1 (expected to find '$2')"
    failures=$((failures + 1))
  fi
}

# --- no phone --------------------------------------------------------------

echo "==> a phone that is not connected"
output="$(STUB_NO_DEVICE=1 bash "${probe}" "${work}/stub/hdc" 2>&1)"
status=$?
check "it says why" "no phone is connected" "${output}"
checks=$((checks + 1))
if [ "${status}" -ne 0 ]; then
  echo "  ok    it exits non-zero"
else
  echo "  FAIL  it should exit non-zero"
  failures=$((failures + 1))
fi

# --- a phone that can run the port ----------------------------------------

echo "==> a phone that can run the port"
output="$(bash "${probe}" "${work}/stub/hdc" 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -eq 0 ]; then
  echo "  ok    it succeeds"
else
  echo "  FAIL  it should succeed"
  failures=$((failures + 1))
fi
check "the architecture" "CPU architecture *PASS *aarch64" "${output}"
check "the model" "Model *INFO *ALN-AL80" "${output}"
check "the CPU" "CPU *INFO *Kirin 9020" "${output}"
check "the cores" "CPU cores *PASS *8" "${output}"
check "the memory" "RAM *PASS *7933 MiB" "${output}"
check "the storage" "Free storage *PASS *93 GiB" "${output}"
check "OpenGL ES" "OpenGL ES 3 *PASS *Mali-G78" "${output}"
check "the summary" "can run the launcher and the guest session" "${output}"
check "it says the guest is on the CPU" "the guest renders on the CPU" "${output}"

# --- a phone whose GPU cannot give the app a context ------------------------

echo "==> a phone without an EGL context"
: > "${work}/hilog"
printf '01-01 00:00:00.000  1000  1000 W DroidDeck: no EGL display, GPU unknown\n' > "${work}/hilog"
output="$(bash "${probe}" "${work}/stub/hdc" 2>&1)"
check "OpenGL ES is a failure" "OpenGL ES 3 *FAIL" "${output}"
check "the summary says so" "OpenGL ES 3 is unproven" "${output}"

# --- a phone that is not aarch64 -------------------------------------------

echo "==> a phone that is not aarch64"
cat > "${work}/stub/hdc" <<'STUB'
#!/bin/bash
case "$1" in
  list) echo "mock-device-1" ;;
  shell)
    shift
    case "$*" in
      "uname -m") echo "x86_64" ;;
      *) exit 0 ;;
    esac
    ;;
esac
exit 0
STUB
chmod +x "${work}/stub/hdc"
output="$(bash "${probe}" "${work}/stub/hdc" 2>&1)"
check "the architecture is a failure" "CPU architecture *FAIL *x86_64" "${output}"

if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed." >&2
  exit 1
fi

echo "==> the capability probe behaves as it should (${checks} checks)"
