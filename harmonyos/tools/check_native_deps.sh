#!/usr/bin/env bash
#
# Checks that the native module's dependencies can actually be resolved.
#
# A shared library records its dependencies by name in DT_NEEDED, and the
# loader looks each name up in the app's own library directory. A library
# whose SONAME does not match the file name that ships is therefore
# unresolvable on the device: the module loads, and then fails with
# "cannot open shared object file" the moment the compositor starts.
#
# This script reads the DT_NEEDED entries of every library the HAP ships
# and asserts that each one is either provided by the platform or present
# in entry/libs/arm64-v8a/ under exactly that name.
#
# Usage: tools/check_native_deps.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

: "${OHOS_SDK_HOME:?set OHOS_SDK_HOME to the OpenHarmony SDK root}"
READELF="${OHOS_SDK_HOME}/native/llvm/bin/llvm-readelf"
libs="${root}/entry/libs/arm64-v8a"

if [ ! -x "${READELF}" ]; then
  echo "llvm-readelf was not found at ${READELF}" >&2
  exit 1
fi

# The libraries the platform provides. Everything else has to ship.
platform=(
  libace_napi.z.so
  libace_ndk.z.so
  libhilog_ndk.z.so
  libnative_window.so
  libEGL.so
  libGLESv3.so
  libGLESv2.so
  libz.so
  libm.so
  libdl.so
  libc.so
  libc++_shared.so
)

failures=0

for library in "${libs}"/*.so "${libs}"/*.so.*; do
  [ -f "${library}" ] || continue
  name="$(basename "${library}")"
  echo "==> ${name}"

  soname="$("${READELF}" -d "${library}" | sed -n 's/.*Library soname: \[\(.*\)\].*/\1/p')"
  if [ -n "${soname}" ] && [ "${soname}" != "${name}" ]; then
    echo "    FAIL: the SONAME is '${soname}' but the file is '${name}'." >&2
    echo "    The loader looks dependencies up by name, so this library" >&2
    echo "    would not be found on the device." >&2
    failures=$((failures + 1))
  else
    echo "    soname ok (${soname:-none})"
  fi

  needed="$("${READELF}" -d "${library}" | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p')"
  for dependency in ${needed}; do
    skip=0
    for provided in "${platform[@]}"; do
      if [ "${dependency}" = "${provided}" ]; then
        skip=1
        break
      fi
    done
    if [ "${skip}" = 1 ]; then
      echo "    ok      ${dependency} (platform)"
      continue
    fi
    if [ -f "${libs}/${dependency}" ]; then
      echo "    ok      ${dependency} (shipped)"
      continue
    fi
    echo "    FAIL: ${dependency} is neither provided by the platform nor shipped." >&2
    failures=$((failures + 1))
  done
done

if [ "${failures}" -ne 0 ]; then
  echo "${failures} dependency problem(s) found." >&2
  exit 1
fi

echo "==> every native dependency resolves"
