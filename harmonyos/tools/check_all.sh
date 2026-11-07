#!/usr/bin/env bash
#
# Runs every check the port has, in one command.
#
# Each check is a separate tool under tools/, so a reviewer can run any one of
# them on its own. This script runs all of them and says which passed, which
# failed and which could not run, because the point of the list is to be run
# as a whole.
#
# Usage:
#   tools/check_all.sh [--full]
#
#   --full   also run the check that unpacks the published 291 MB runtime
#            image. Without it, that check is skipped when the image has not
#            been downloaded yet.
#
# The checks that need the OpenHarmony SDK are skipped when OHOS_SDK_HOME
# does not point at one.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root}" || exit 1

full=0
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
    --full) full=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

python="${PYTHON:-python3}"
[ -x "${OHOS_SDK_HOME:-}/native/build/cmake/ohos.toolchain.cmake" ] || true

passed=0
failed=0
skipped=0
failed_names=""

report() {  # report <state> <name> <detail>
  printf '  %-8s %-34s %s\n' "$1" "$2" "$3"
}

run() {  # run <name> <command...>
  local name="$1"; shift
  local output
  output="$("$@" 2>&1)"
  local status=$?
  if [ "${status}" -eq 0 ]; then
    passed=$((passed + 1))
    report PASS "${name}" "$(printf '%s' "${output}" | tail -1 | cut -c1-70)"
  else
    failed=$((failed + 1))
    failed_names="${failed_names} ${name}"
    report FAIL "${name}" "$(printf '%s' "${output}" | tail -1 | cut -c1-70)"
  fi
}

skip() {  # skip <name> <reason>
  skipped=$((skipped + 1))
  report SKIP "$1" "$2"
}

echo "==> the port's checks"
echo

# --- the ones that need the SDK -------------------------------------------

if [ -n "${OHOS_SDK_HOME:-}" ] && [ -d "${OHOS_SDK_HOME}" ]; then
  run "typecheck_arkts.py" "${python}" tools/typecheck_arkts.py
  run "check_app_config.py" "${python}" tools/check_app_config.py
else
  skip "typecheck_arkts.py" "set OHOS_SDK_HOME to the API 26 SDK"
  skip "check_app_config.py" "set OHOS_SDK_HOME to the API 26 SDK"
fi

# --- the ones that need nothing -------------------------------------------

run "check_native_api.py" "${python}" tools/check_native_api.py
run "check_guest_paths.py" "${python}" tools/check_guest_paths.py
run "check_native_deps.sh" bash tools/check_native_deps.sh
run "test_native_host.sh" bash tools/test_native_host.sh
run "test_check_device_capabilities.sh" bash tools/test_check_device_capabilities.sh
run "test_device_smoke.sh" bash tools/test_device_smoke.sh

# --- the ones that need the published runtime image ------------------------

archive="$(ls -1 build/upstream-runtime/linuxfs-*.tar.zst 2>/dev/null | head -1)"

if [ -n "${archive}" ]; then
  run "check_guest_tools.py" "${python}" tools/check_guest_tools.py "${archive}"
  if [ "${full}" -eq 1 ]; then
    run "verify_upstream_runtime.sh" bash tools/verify_upstream_runtime.sh
  else
    skip "verify_upstream_runtime.sh" "pass --full to unpack the published image"
  fi
else
  skip "check_guest_tools.py" "the runtime image has not been downloaded"
  skip "verify_upstream_runtime.sh" "the runtime image has not been downloaded"
fi

echo
echo "==> summary"
echo "  ${passed} passed, ${failed} failed, ${skipped} skipped"
if [ "${failed}" -ne 0 ]; then
  echo "  failed:${failed_names}" >&2
  exit 1
fi
