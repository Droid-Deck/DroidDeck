#!/usr/bin/env bash
#
# Tests the device smoke test without a phone.
#
# The smoke test talks to a phone over hdc, so it cannot run until one is
# connected. This test stands in for hdc with a stub that answers the
# commands the smoke test asks, and checks that it reads the port's log back
# out of the phone and draws the right conclusion from it.
#
# Usage: tools/test_device_smoke.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
smoke="${root}/tools/device_smoke.sh"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# --- the stub hdc ---------------------------------------------------------

mkdir -p "${work}/stub"
cat > "${work}/stub/hdc" <<'STUB'
#!/bin/bash
# A stand-in for hdc: it answers the commands the smoke test asks.
case "$1" in
  list)
    if [ "${STUB_NO_DEVICE:-0}" = "1" ]; then
      echo "[Empty]"
      exit 0
    fi
    echo "mock-device-1"
    exit 0
    ;;
  install)
    echo "${STUB_DIR}/install"
    if [ "${STUB_INSTALL_FAILS:-0}" = "1" ]; then
      echo "install failed"
    else
      echo "install bundle successfully."
    fi
    exit 0
    ;;
  shell)
    shift
    case "$*" in
      "aa start -b com.droiddeck.launcher -a EntryAbility")
        cat "${STUB_DIR}/start"
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

cat > "${work}/install" <<'EOF'
install bundle successfully.
EOF

cat > "${work}/start" <<'EOF'
start ability successfully.
EOF

# A log from a phone where everything came up.
cat > "${work}/hilog" <<'HILOG'
01-01 00:00:00.000  1000  1000 I DroidDeck: gpu renderer=Mali-G78 vendor=ARM model=78
01-01 00:00:00.001  1000  1000 I DroidDeck: gles version=OpenGL ES 3.2 V@0.0.0
01-01 00:00:01.000  1000  1000 I DroidDeck: XComponent surface created: 1080x2400
01-01 00:00:01.500  1000  1000 I DroidDeck: the input ring is ready: /data/storage/el2/base/haps/entry/files/runtime/input.sock
01-01 00:00:02.000  1000  1000 I DroidDeck: uinput gamepad created
01-01 00:00:02.500  1000  1000 I DroidDeck: the PulseAudio server started, pid=4242
01-01 00:00:03.000  1000  1000 I DroidDeck: guest audio socket connected: /run/droiddeck/pulse/native
01-01 00:00:04.000  1000  1000 I DroidDeck: applied the overlay: 91 files
01-01 00:00:04.100  1000  1000 W DroidDeck: dma-buf import is unavailable without a Vulkan driver; the guest has to use shared memory
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
output="$(STUB_NO_DEVICE=1 bash "${smoke}" --hdc "${work}/stub/hdc" 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -ne 0 ]; then
  echo "  ok    it exits non-zero"
else
  echo "  FAIL  it should exit non-zero"
  failures=$((failures + 1))
fi
check "it says why" "no phone is connected" "${output}"

# --- a phone where the port came up ---------------------------------------

echo "==> a phone where the port came up"
output="$(bash "${smoke}" --hdc "${work}/stub/hdc" --no-launch 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -eq 0 ]; then
  echo "  ok    it succeeds"
else
  echo "  FAIL  it should succeed"
  failures=$((failures + 1))
fi
check "the GPU" "the GPU gave the app a context *PASS *Mali-G78" "${output}"
check "the ES version" "the OpenGL ES version *PASS *OpenGL ES 3.2" "${output}"
check "the surface" "the compositor surface *PASS *1080x2400" "${output}"
check "the input path" "the input path *PASS */data/storage" "${output}"
check "the gamepad" "the virtual gamepad *PASS" "${output}"
check "the audio server" "the audio server *PASS *pid 4242" "${output}"
check "the guest audio" "the guest's audio *PASS" "${output}"
check "the runtime" "the guest runtime on the phone *PASS *overlay applied: 91 files" "${output}"
check "the summary" "The launcher is running on this phone" "${output}"

# --- a phone where the app never came up -----------------------------------

echo "==> a phone where the app never came up"
cat > "${work}/hilog" <<'HILOG'
01-01 00:00:00.000  1000  1000 W DroidDeck: no EGL display, GPU unknown
HILOG
output="$(bash "${smoke}" --hdc "${work}/stub/hdc" --no-launch 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -ne 0 ]; then
  echo "  ok    it exits non-zero"
else
  echo "  FAIL  it should exit non-zero"
  failures=$((failures + 1))
fi
check "the GPU is a failure" "the GPU gave the app a context *FAIL" "${output}"
check "the surface is a failure" "the compositor surface *FAIL" "${output}"
check "the summary says so" "did not come up far enough to draw" "${output}"

# --- a phone with nothing in the log ---------------------------------------

echo "==> a phone that wrote nothing"
: > "${work}/hilog"
output="$(bash "${smoke}" --hdc "${work}/stub/hdc" --no-launch 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -ne 0 ]; then
  echo "  ok    it exits non-zero"
else
  echo "  FAIL  it should exit non-zero"
  failures=$((failures + 1))
fi
check "it says so" "the port wrote nothing to the log" "${output}"

# --- installing a HAP ------------------------------------------------------

echo "==> installing a HAP"
cat > "${work}/hilog" <<'HILOG'
01-01 00:00:00.000  1000  1000 I DroidDeck: gpu renderer=Mali-G78 vendor=ARM model=78
01-01 00:00:00.001  1000  1000 I DroidDeck: gles version=OpenGL ES 3.2 V@0.0.0
01-01 00:00:01.000  1000  1000 I DroidDeck: XComponent surface created: 1080x2400
HILOG
touch "${work}/app.hap"
output="$(bash "${smoke}" --hdc "${work}/stub/hdc" --hap "${work}/app.hap" --no-launch 2>&1)"
check "it installs" "installing app.hap" "${output}"
check "the install succeeded" "install bundle successfully" "${output}"

echo "==> an install that fails"
output="$(STUB_INSTALL_FAILS=1 bash "${smoke}" --hdc "${work}/stub/hdc" --hap "${work}/app.hap" --no-launch 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -ne 0 ]; then
  echo "  ok    it exits non-zero"
else
  echo "  FAIL  it should exit non-zero"
  failures=$((failures + 1))
fi
check "it says so" "the install did not succeed" "${output}"

if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed." >&2
  exit 1
fi

echo "==> the device smoke test behaves as it should (${checks} checks)"
