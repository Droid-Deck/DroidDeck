#!/usr/bin/env bash
#
# Tests the HAP checker without building a HAP.
#
# A HAP is a zip, so the checker can be pointed at an archive built from the
# port's own files: the real libraries, a manifest and the overlay. That is
# enough to prove the checker accepts a good archive and rejects each way a
# HAP can be wrong — a missing library, a stray one, a library for the wrong
# architecture, the wrong bundle name, a missing overlay.
#
# Usage: tools/test_check_hap.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="${root}/tools/check_hap.sh"
libs="${root}/entry/libs/arm64-v8a"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

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

# --- build a HAP out of the port's own files -------------------------------

build_hap() {  # build_hap <destination>
  local dest="$1" stage="${work}/stage"
  rm -rf "${stage}"
  mkdir -p "${stage}/libs/arm64-v8a" "${stage}/resources/rawfile" "${stage}/abc"
  cp "${libs}"/* "${stage}/libs/arm64-v8a/" 2>/dev/null
  cat > "${stage}/module.json" <<'JSON'
{
  "app": {
    "bundleName": "com.droiddeck.launcher",
    "vendor": "Droid-Deck",
    "versionCode": 170,
    "versionName": "0.6.9-harmony"
  },
  "module": {
    "name": "entry",
    "type": "entry",
    "mainElement": "EntryAbility",
    "abilities": [ { "name": "EntryAbility", "srcEntry": "./ets/entryability/EntryAbility.ets" } ],
    "requestPermissions": [
      { "name": "ohos.permission.INTERNET" },
      { "name": "ohos.permission.KEEP_BACKGROUND_RUNNING" }
    ]
  }
}
JSON
  cp "${root}/entry/src/main/resources/rawfile/overlay.tar.gz" \
     "${stage}/resources/rawfile/overlay.tar.gz"
  echo "compiled" > "${stage}/abc/entry.abc"
  (cd "${stage}" && zip -qr "${dest}" .)
}

# --- the libraries the port ships -------------------------------------------

echo "==> the port's own libraries"
expected="libc++_shared.so libdroiddeck_native.so libprotocol-native.so libpulse-simple.so.0 libpulse.so.0 libpulseaudio.so libpulsecommon-17.0.so libpulsecore-17.0.so libwayland-server.so module-droiddeck-sink.so module-native-protocol-fd.so module-native-protocol-unix.so"
present="$(cd "${libs}" && ls -1 | tr '\n' ' ' | sed 's/ $//')"
check "the port ships exactly the libraries the checker expects" "${expected}" "${present}"
if ls "${libs}/pulseaudio" >/dev/null 2>&1; then
  echo "  FAIL  the unused standalone pulseaudio binary is still in libs/arm64-v8a"
  failures=$((failures + 1))
else
  echo "  ok    the unused standalone pulseaudio binary is not shipped"
fi
checks=$((checks + 1))

# --- a good HAP ------------------------------------------------------------

echo "==> a HAP that holds everything"
hap="${work}/good.hap"
build_hap "${hap}"
output="$(bash "${checker}" "${hap}" 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -eq 0 ]; then
  echo "  ok    it succeeds"
else
  echo "  FAIL  it should succeed"
  failures=$((failures + 1))
fi
check "the manifest" "module.json *PASS" "${output}"
check "the bundle name" "the bundle name *PASS *com.droiddeck.launcher" "${output}"
check "the ability" "the ability *PASS *EntryAbility" "${output}"
check "the permissions" "the internet permission *PASS" "${output}"
check "the native module" "libdroiddeck_native.so *PASS *arm64" "${output}"
check "the C++ runtime" "libc++_shared.so *PASS *arm64" "${output}"
check "the daemon" "libpulseaudio.so *PASS *arm64" "${output}"
check "the overlay" "the runtime overlay *PASS" "${output}"
check "the bytecode" "the compiled ArkTS *PASS" "${output}"
check "the summary" "0 failed" "${output}"

# --- a HAP without the C++ runtime -----------------------------------------

echo "==> a HAP without the C++ runtime"
hap="${work}/no-cxx.hap"
build_hap "${hap}"
zip -d "${hap}" 'libs/arm64-v8a/libc++_shared.so' >/dev/null 2>&1
output="$(bash "${checker}" "${hap}" 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -ne 0 ]; then
  echo "  ok    it fails"
else
  echo "  FAIL  it should fail"
  failures=$((failures + 1))
fi
check "it names the library" "libc++_shared.so *FAIL *MISSING" "${output}"

# --- a HAP with a library nobody accounted for ------------------------------

echo "==> a HAP with a stray library"
hap="${work}/stray.hap"
build_hap "${hap}"
cp "${libs}/libdroiddeck_native.so" /tmp/ddk-stray.so
(cd "${work}" && mkdir -p stray/libs/arm64-v8a && cp /tmp/ddk-stray.so stray/libs/arm64-v8a/libsurprise.so)
(cd "${work}/stray" && zip -qr "${hap}" .)
output="$(bash "${checker}" "${hap}" 2>&1)"
check "it names the file" "libsurprise.so *FAIL *UNEXPECTED" "${output}"

# --- a HAP with a library for the wrong architecture ------------------------

echo "==> a HAP with an x86-64 library"
hap="${work}/x86.hap"
build_hap "${hap}"
stage="${work}/stage"
cp "${libs}/libdroiddeck_native.so" "${stage}/libs/arm64-v8a/libdroiddeck_native.so"
# e_machine is at offset 18; 0x3e is x86-64.
printf '\076\000' | dd of="${stage}/libs/arm64-v8a/libdroiddeck_native.so" bs=1 seek=18 conv=notrunc 2>/dev/null
rm -f "${hap}"
(cd "${stage}" && zip -qr "${hap}" .)
output="$(bash "${checker}" "${hap}" 2>&1)"
check "it names the library" "libdroiddeck_native.so *FAIL *not an arm64 binary" "${output}"

# --- a HAP with the wrong bundle name ---------------------------------------

echo "==> a HAP with the wrong bundle name"
hap="${work}/wrong-bundle.hap"
build_hap "${hap}"
stage="${work}/stage"
sed -i '' 's/com.droiddeck.launcher/com.example.other/' "${stage}/module.json"
rm -f "${hap}"
(cd "${stage}" && zip -qr "${hap}" .)
output="$(bash "${checker}" "${hap}" 2>&1)"
check "it names the bundle" "the bundle name *FAIL" "${output}"

# --- a HAP without the overlay ----------------------------------------------

echo "==> a HAP without the runtime overlay"
hap="${work}/no-overlay.hap"
build_hap "${hap}"
zip -d "${hap}" 'resources/rawfile/overlay.tar.gz' >/dev/null 2>&1
output="$(bash "${checker}" "${hap}" 2>&1)"
check "it names the overlay" "the runtime overlay *FAIL" "${output}"

# --- a HAP with development leftovers ---------------------------------------

echo "==> a HAP with development leftovers"
hap="${work}/leftovers.hap"
build_hap "${hap}"
stage="${work}/stage"
echo "sdk.dir=/tmp/whatever" > "${stage}/local.properties"
rm -f "${hap}"
(cd "${stage}" && zip -qr "${hap}" .)
output="$(bash "${checker}" "${hap}" 2>&1)"
check "it names the leftovers" "no development leftovers *FAIL" "${output}"

# --- not a HAP at all ------------------------------------------------------

echo "==> something that is not a HAP"
notahap="${work}/not.hap"
echo "this is not a zip" > "${notahap}"
output="$(bash "${checker}" "${notahap}" 2>&1)"
status=$?
checks=$((checks + 1))
if [ "${status}" -ne 0 ]; then
  echo "  ok    it fails"
else
  echo "  FAIL  it should fail"
  failures=$((failures + 1))
fi
check "it says why" "not a readable zip" "${output}"

rm -f /tmp/ddk-stray.so

if [ "${failures}" -ne 0 ]; then
  echo "${failures} check(s) failed." >&2
  exit 1
fi

echo "==> the HAP checker behaves as it should (${checks} checks)"
