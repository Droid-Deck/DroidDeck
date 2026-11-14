#!/usr/bin/env bash
#
# Fetches upstream DroidDeck's published Linux runtime.
#
# Upstream ships the runtime as a zstd-compressed tar:
#
#   https://github.com/Droid-Deck/DroidDeck-Components/releases/download/<tag>/<tag>.tar.zst
#
# with the current release listed in a small catalog. The port installs
# exactly this artifact, so this script is what proves the catalog, the
# download and the unpacker all line up.
#
# Usage:
#   tools/fetch_upstream_runtime.sh [destination]

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-${root}/build/upstream-runtime}"

CATALOG_URL="https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs-ubuntu.json"

mkdir -p "${destination}"
catalog="${destination}/catalog.json"

echo "==> fetching the release catalog"
curl -sL --max-time 120 -o "${catalog}" "${CATALOG_URL}"

url="$(python3 -c "
import json, sys
data = json.load(open('${catalog}'))
print(data['url'])
")"
sha256="$(python3 -c "
import json, sys
data = json.load(open('${catalog}'))
print(data['sha256'])
")"
version="$(python3 -c "
import json, sys
data = json.load(open('${catalog}'))
print(data['version'])
")"

archive="${destination}/linuxfs-${version}.tar.zst"

echo "==> runtime ${version}"
echo "    ${url}"

if [ ! -f "${archive}" ]; then
  echo "==> downloading"
  curl -L --max-time 3600 --progress-bar -o "${archive}" "${url}"
fi

echo "==> verifying the checksum"
actual="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [ "${actual}" != "${sha256}" ]; then
  echo "the checksum does not match: expected ${sha256}, got ${actual}" >&2
  exit 1
fi
echo "    ok"

echo "==> done: ${archive}"
ls -la "${archive}"