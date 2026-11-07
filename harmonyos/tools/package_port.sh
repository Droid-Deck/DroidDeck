#!/usr/bin/env bash
#
# Packages the HarmonyOS port into a zip that can be unpacked and built.
#
# The archive carries the port itself: the ArkTS launcher, the native
# module's sources, the prebuilt libraries, the runtime overlay and the
# tools that verify it. It does not carry the upstream runtime image,
# which is 291 MB and is downloaded on demand by
# tools/fetch_upstream_runtime.sh.
#
# Usage: tools/package_port.sh [output.zip]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "${root}/.." && pwd)"
output="${1:-${repo}/../DroidDeck-harmonyos7-port.zip}"

if [ ! -f "${root}/entry/libs/arm64-v8a/libdroiddeck_native.so" ]; then
  echo "the native library is missing; build it with cmake first" >&2
  exit 1
fi
if [ ! -f "${root}/entry/src/main/resources/rawfile/overlay.tar.gz" ]; then
  echo "the runtime overlay is missing; run tools/build_overlay_tar.sh first" >&2
  exit 1
fi

staging="$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-package-XXXXXX")"
mkdir -p "${staging}/harmonyos"

# The build tree holds gigabytes of downloaded archives and object files,
# so only the source tree and the artifacts that ship are copied.
(cd "${root}" && tar -cf - \
  --exclude='./build' \
  --exclude='./.hvigor' \
  --exclude='./.idea' \
  --exclude='./.cxx' \
  --exclude='*.log' \
  --exclude='*.symbols' \
  --exclude='*.so.0.*' \
  --exclude='./local.properties' \
  .) | (cd "${staging}/harmonyos" && tar -xf -)

echo "==> contents"
find "${staging}/harmonyos" -type f | wc -l

mkdir -p "$(dirname "${output}")"
(cd "${staging}" && zip -qr "${output}" harmonyos)

echo "==> wrote ${output}"
ls -la "${output}"
