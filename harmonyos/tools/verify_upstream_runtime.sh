#!/usr/bin/env bash
#
# Verifies the port's install path against the real published runtime image.
#
# The small fixture in the host tests proves the logic; this proves the port
# can install the artifact a phone actually downloads: upstream's linuxfs
# release, a 291 MB zstd-compressed tar with 26,022 entries.
#
# The image is fetched by tools/fetch_upstream_runtime.sh into
# build/upstream-runtime/. When it is not there, the script says so and
# stops, because downloading 291 MB is not something to do by surprise.
#
# Usage: tools/verify_upstream_runtime.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

out="${root}/build/host"
runtime_dir="${root}/build/upstream-runtime"
catalog="${runtime_dir}/catalog.json"
overlay="${root}/artifacts/droiddeck-overlay-arm64.tar.gz"

if [ ! -f "${catalog}" ]; then
  echo "the release catalog is not downloaded." >&2
  echo "run tools/fetch_upstream_runtime.sh first." >&2
  exit 1
fi

# The archive is named after the release the catalog names, so a new
# release does not need this script to be edited.
archive="$(python3 -c "
import json, os, sys
data = json.load(open(sys.argv[1]))
version = data.get('version', '')
print(os.path.join(os.path.dirname(sys.argv[1]), 'linuxfs-%s.tar.zst' % version))
" "${catalog}")"

if [ ! -f "${archive}" ]; then
  echo "the published runtime is not downloaded: ${archive}" >&2
  echo "run tools/fetch_upstream_runtime.sh first." >&2
  exit 1
fi

if [ ! -f "${overlay}" ]; then
  echo "building the overlay" >&2
  bash "${root}/tools/build_overlay_tar.sh" >&2
fi

CXX="${CXX:-clang++}"
ZSTD="${root}/entry/src/main/cpp/thirdparty/zstd"

mkdir -p "${out}"
echo "==> compiling the real runtime test"
"${CXX}" -std=c++17 -Wall -Wextra -Wno-unused-parameter \
  -I "${root}/entry/src/main/cpp" -I "${ZSTD}/include" \
  "${root}/entry/src/main/cpp/runtime/runtime.cpp" \
  "${root}/entry/src/main/cpp/runtime/install.cpp" \
  "${root}/entry/src/main/cpp/runtime/unpack.cpp" \
  "${root}/entry/src/main/cpp/tests/real_runtime_test.cpp" \
  "${ZSTD}/libzstd-host.a" \
  -lz \
  -o "${out}/real_runtime_test"

sha=""
if [ -f "${catalog}" ]; then
  sha="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('sha256',''))" "${catalog}")"
fi

# Every run works in its own directory. The work area defaults to a local
# temporary directory, because the published image has 26,022 entries and
# writing them to a network volume takes hours. The volume this repository
# lives on cannot always move files, so nothing is deleted between runs.
work="${DDK_VERIFY_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-verify-XXXXXX")}"
run="${work}/run"
mkdir -p "${run}"
echo "==> work area: ${run}"

echo "==> unpacking the published image (this takes a few minutes)"
"${out}/real_runtime_test" "${archive}" "${run}/root" "${overlay}" ${sha:+"${sha}"}

echo "==> the published runtime installs correctly"
