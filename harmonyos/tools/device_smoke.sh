#!/usr/bin/env bash
#
# Runs the port on a connected phone and reports what it managed to do.
#
# The capability probe says whether the phone *could* run the port. This goes
# further: it installs the HAP, starts the app, and reads the port's own log
# back out of the phone, so every part of the port that can be checked on a
# device is checked and reported in one place.
#
# What it looks for, and the log line that proves it:
#
#   the GPU gave the app an ES context   gpu renderer=... / gles version=...
#   the compositor has a surface          XComponent surface created: ...
#   the input path is live                the input ring is ready: ...
#   the virtual gamepad exists            uinput gamepad created
#   the audio server is up                the PulseAudio server started
#   the guest's audio reached the phone   guest audio socket connected: ...
#   the guest runtime installed           applied the overlay: N files
#
# The last one only appears once the runtime image has been unpacked on the
# phone, which takes a while; it is reported as missing, not as a failure.
#
# Usage:
#   tools/device_smoke.sh [--hap path/to/entry-default-signed.hap]
#                         [--seconds N] [--hdc path/to/hdc] [--no-launch]
#
# The phone has to be connected over USB with developer mode and USB
# debugging enabled, so that `hdc list targets` shows it.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BUNDLE="com.droiddeck.launcher"
ABILITY="EntryAbility"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

hap=""
hdc_override=""
seconds=10
launch=1

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    --hap) hap="$2"; shift 2 ;;
    --seconds) seconds="$2"; shift 2 ;;
    --hdc) hdc_override="$2"; shift 2 ;;
    --no-launch) launch=0; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

# --- find hdc --------------------------------------------------------------

find_hdc() {
  local candidate
  for candidate in \
      "${hdc_override}" \
      "${OHOS_SDK_HOME:-}/toolchains/hdc" \
      "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc" \
      "${HOME}/Library/Huawei/Sdk/default/openharmony/toolchains/hdc"; do
    [ -n "${candidate}" ] && [ -x "${candidate}" ] && { printf '%s' "${candidate}"; return 0; }
  done
  command -v hdc 2>/dev/null && return 0
  return 1
}

hdc="$(find_hdc)" || die "hdc was not found; point OHOS_SDK_HOME at the SDK or pass --hdc"
echo "==> hdc: ${hdc}"

shell() { "${hdc}" shell "$@" 2>/dev/null | tr -d '\r'; }

# --- the device ------------------------------------------------------------

targets="$("${hdc}" list targets 2>/dev/null | tr -d '\r' | grep -v '^$' | grep -v 'Empty')"
[ -z "${targets}" ] && die "no phone is connected; check USB debugging on the phone"
echo "==> device: ${targets}"

# --- install ---------------------------------------------------------------

if [ -n "${hap}" ]; then
  [ -f "${hap}" ] || die "the HAP was not found: ${hap}"
  echo "==> installing $(basename "${hap}")"
  install_output="$("${hdc}" install "${hap}" 2>&1 | tr -d '\r')"
  printf '%s\n' "${install_output}" | sed 's/^/    /'
  if printf '%s' "${install_output}" | grep -qi 'fail\|error'; then
    echo
    echo "==> the install did not succeed"
    exit 1
  fi
else
  echo "==> not installing anything (no --hap); using the app already on the phone"
fi

# --- start -----------------------------------------------------------------

started=0
if [ "${launch}" -eq 1 ]; then
  echo "==> starting ${BUNDLE}/${ABILITY}"
  start_output="$(shell aa start -b "${BUNDLE}" -a "${ABILITY}")"
  printf '%s\n' "${start_output}" | sed 's/^/    /'
  if printf '%s' "${start_output}" | grep -qi 'success'; then
    started=1
  fi
  echo "==> waiting ${seconds}s for the app to come up"
  sleep "${seconds}"
else
  echo "==> not starting the app (--no-launch)"
  started=1
fi

# --- what the port managed to do -------------------------------------------

log="$(shell hilog -T DroidDeck -x)"
if [ -z "${log}" ]; then
  echo
  echo "==> the port wrote nothing to the log"
  echo "    Either the app did not start, or it failed before the native module"
  echo "    was loaded. Open the app by hand and run this again."
  exit 1
fi

report() {  # report <label> <state> <detail>
  printf '  %-34s %-8s %s\n' "$1" "$2" "$3"
}

detail() {  # detail <sed expression>
  printf '%s' "${log}" | sed -n "$1" | head -1
}

echo
echo "==> what the port did on this phone"

renderer="$(detail 's/.*gpu renderer=\([^ ]*\) vendor=\([^ ]*\) model=\([^ ]*\).*/\1 (\2, model \3)/p')"
if [ -n "${renderer}" ]; then
  report "the GPU gave the app a context" PASS "${renderer}"
else
  report "the GPU gave the app a context" FAIL "no renderer in the log"
fi

gles="$(detail 's/.*gles version=\(.*\)/\1/p')"
if [ -n "${gles}" ]; then
  report "the OpenGL ES version" PASS "${gles}"
else
  report "the OpenGL ES version" FAIL "the app did not report one"
fi

surface="$(detail 's/.*XComponent surface created: \(.*\)/\1/p')"
if [ -n "${surface}" ]; then
  report "the compositor surface" PASS "${surface}"
else
  report "the compositor surface" FAIL "the surface was never created"
fi

ring="$(detail 's/.*the input ring is ready: \(.*\)/\1/p')"
if [ -n "${ring}" ]; then
  report "the input path" PASS "${ring}"
else
  report "the input path" FAIL "the input ring was never created"
fi

gamepad="$(detail 's/.*uinput gamepad \(.*\)/\1/p')"
if [ -n "${gamepad}" ]; then
  report "the virtual gamepad" PASS "${gamepad}"
else
  report "the virtual gamepad" MISSING "the phone gave the app no /dev/uinput"
fi

pulse="$(detail 's/.*the PulseAudio server started, pid=\(.*\)/pid \1/p')"
if [ -n "${pulse}" ]; then
  report "the audio server" PASS "${pulse}"
else
  report "the audio server" FAIL "the PulseAudio server did not start"
fi

guest_audio="$(detail 's/.*guest audio socket connected: \(.*\)/\1/p')"
if [ -n "${guest_audio}" ]; then
  report "the guest's audio" PASS "${guest_audio}"
else
  report "the guest's audio" MISSING "no guest session is running"
fi

overlay="$(detail 's/.*applied the overlay: \(.*\) files/\1 files/p')"
if [ -n "${overlay}" ]; then
  report "the guest runtime on the phone" PASS "overlay applied: ${overlay}"
else
  report "the guest runtime on the phone" MISSING "the runtime has not been unpacked yet"
fi

echo
echo "==> summary"
if [ -n "${renderer}" ] && [ -n "${surface}" ]; then
  echo "  The launcher is running on this phone: it has an OpenGL ES context and"
  echo "  a surface, so the compositor is presenting."
  [ -n "${overlay}" ] || echo "  The guest runtime still has to be unpacked on the phone;"
  echo "  that happens from the app, and takes a while for the 291 MB image."
  exit 0
fi

echo "  The launcher did not come up far enough to draw. The log above says"
echo "  which part failed; the port's own messages are tagged DroidDeck."
[ "${started}" -eq 1 ] || echo "  The app did not report a successful start."
exit 1
