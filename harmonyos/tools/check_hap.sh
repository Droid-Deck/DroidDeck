#!/usr/bin/env bash
#
# Checks a built HAP before it goes on a phone.
#
# The port has never produced a HAP, because Hvigor only ships inside DevEco
# Studio. So the first HAP has to be checked rather than trusted: this script
# looks inside the archive and says whether the thing that was built is the
# thing the port needs.
#
# It checks that:
#
#   * the archive is a readable zip with a module.json;
#   * module.json declares the bundle name, the ability and the permissions;
#   * libs/arm64-v8a holds exactly the libraries the port ships — no more and
#     no fewer, because a file that is not in the list is a file nobody
#     accounted for;
#   * every shipped library is an arm64 ELF, so nothing x86 slipped in;
#   * the runtime overlay is in the raw files, because the guest cannot start
#     without it;
#   * the ArkTS bytecode was compiled;
#   * no development leftovers went in.
#
# Usage:
#   tools/check_hap.sh [path to the .hap]
#
# With no argument it looks for the newest HAP under entry/build.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The libraries the port ships, and the only ones it may ship. The daemon is
# libpulseaudio.so: the port launches it in the shared-library form, so the
# standalone binary must not be in here.
expected_libraries=(
  libc++_shared.so
  libdroiddeck_native.so
  libprotocol-native.so
  libpulse-simple.so.0
  libpulse.so.0
  libpulseaudio.so
  libpulsecommon-17.0.so
  libpulsecore-17.0.so
  libwayland-server.so
  module-droiddeck-sink.so
  module-native-protocol-fd.so
  module-native-protocol-unix.so
)

bundle="com.droiddeck.launcher"
ability="EntryAbility"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- find the HAP ----------------------------------------------------------

hap="$1"
if [ -z "${hap}" ]; then
  hap="$(find "${root}/entry/build" -name '*.hap' -type f 2>/dev/null |
         xargs ls -t 2>/dev/null | head -1)"
  [ -n "${hap}" ] || die "no HAP was found under entry/build; build one with tools/build_hap.sh"
fi
[ -f "${hap}" ] || die "the HAP was not found: ${hap}"

echo "==> hap: ${hap}"
echo "==> size: $(wc -c < "${hap}" | tr -d ' ') bytes"

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

if ! unzip -qq -o "${hap}" -d "${work}" 2>/dev/null; then
  die "the HAP is not a readable zip"
fi

passed=0
failed=0
report() {  # report <state> <name> <detail>
  printf '  %-38s %-8s %s\n' "$2" "$1" "$3"
}
ok()   { passed=$((passed + 1)); report PASS "$1" "$2"; }
bad()  { failed=$((failed + 1)); report FAIL "$1" "$2"; }

echo
echo "==> what the HAP holds"

# --- the manifest ----------------------------------------------------------

if [ -f "${work}/module.json" ]; then
  ok "module.json" "the manifest is there"
  module="$(cat "${work}/module.json")"
  printf '%s' "${module}" | grep -q "${bundle}" \
    && ok "the bundle name" "${bundle}" \
    || bad "the bundle name" "expected ${bundle}"
  printf '%s' "${module}" | grep -q "${ability}" \
    && ok "the ability" "${ability}" \
    || bad "the ability" "expected ${ability}"
  printf '%s' "${module}" | grep -q 'ohos.permission.INTERNET' \
    && ok "the internet permission" "declared" \
    || bad "the internet permission" "the guest downloads the runtime with it"
  printf '%s' "${module}" | grep -q 'ohos.permission.KEEP_BACKGROUND_RUNNING' \
    && ok "the background permission" "declared" \
    || bad "the background permission" "the guest session needs it"
else
  bad "module.json" "it is not in the archive"
fi

# --- the libraries ---------------------------------------------------------

libdir="${work}/libs/arm64-v8a"
if [ ! -d "${libdir}" ]; then
  bad "libs/arm64-v8a" "the archive has no library directory"
else
  ok "libs/arm64-v8a" "the library directory is there"

  for library in "${expected_libraries[@]}"; do
    if [ -f "${libdir}/${library}" ]; then
      ok "${library}" "shipped"
    else
      bad "${library}" "MISSING"
    fi
  done

  # Anything in there that was not expected is a file nobody accounted for.
  for present in "${libdir}"/*; do
    [ -e "${present}" ] || continue
    name="$(basename "${present}")"
    found=0
    for library in "${expected_libraries[@]}"; do
      [ "${name}" = "${library}" ] && { found=1; break; }
    done
    [ "${found}" -eq 1 ] || bad "${name}" "UNEXPECTED"
  done

  # Every shipped library has to be an arm64 ELF.
  for library in "${expected_libraries[@]}"; do
    path="${libdir}/${library}"
    [ -f "${path}" ] || continue
    header="$(od -An -tx1 -N20 "${path}" | tr -d ' \n')"
    if [ "${header:0:8}" != "7f454c46" ]; then
      bad "${library}" "not an ELF file"
    elif [ "${header:8:2}" != "02" ]; then
      bad "${library}" "not a 64-bit ELF"
    elif [ "${header:38:2}${header:36:2}" != "00b7" ]; then
      bad "${library}" "not an arm64 binary"
    else
      ok "${library}" "arm64"
    fi
  done
fi

# --- the overlay and the bytecode ------------------------------------------

if [ -f "${work}/resources/rawfile/overlay.tar.gz" ]; then
  size="$(wc -c < "${work}/resources/rawfile/overlay.tar.gz" | tr -d ' ')"
  ok "the runtime overlay" "resources/rawfile/overlay.tar.gz (${size} bytes)"
else
  bad "the runtime overlay" "resources/rawfile/overlay.tar.gz is not in the archive"
fi

if [ -d "${work}/abc" ] || [ -d "${work}/ets" ]; then
  ok "the compiled ArkTS" "the bytecode is there"
else
  bad "the compiled ArkTS" "neither abc/ nor ets/ is in the archive"
fi

# --- leftovers -------------------------------------------------------------

leftovers="$(cd "${work}" && find . -name 'local.properties' -o -name '*.log' -o -name '__pycache__' | head -5)"
if [ -z "${leftovers}" ]; then
  ok "no development leftovers" "the archive is clean"
else
  bad "no development leftovers" "$(printf '%s' "${leftovers}" | tr '\n' ' ')"
fi

echo
echo "==> summary"
echo "  ${passed} passed, ${failed} failed"
if [ "${failed}" -ne 0 ]; then
  echo
  echo "  The HAP is not ready for a phone. The failures above say what is wrong."
  exit 1
fi

echo "  The HAP holds everything the port needs. Sign it, then install it:"
echo "      tools/sign_hap.sh --in <signed.hap>"
