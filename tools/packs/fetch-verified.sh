#!/usr/bin/env bash
set -euo pipefail
url=${1:?URL required}
expected=${2:?SHA-256 required}
destination=${3:?destination required}
[[ "$expected" =~ ^[a-f0-9]{64}$ ]] || { echo 'Invalid download SHA-256' >&2; exit 1; }
cache=${DROIDDECK_PACK_CACHE:-"$HOME/.cache/droiddeck-pack-inputs"}
mkdir -p "$cache" "$(dirname "$destination")"
kept="$cache/$expected"
verify() {
    if command -v sha256sum >/dev/null; then
        echo "$expected  $1" | sha256sum -c - >/dev/null 2>&1
    else
        echo "$expected  $1" | shasum -a 256 -c - >/dev/null 2>&1
    fi
}
if [ -f "$kept" ] && verify "$kept"; then
    echo "Verified pack input cache hit: $expected"
else
    part=$(mktemp "$cache/$expected.part.XXXXXX")
    trap 'rm -f "$part"' EXIT
    curl -fsSL --retry 4 --retry-all-errors --connect-timeout 15 --max-time 180 --retry-max-time 240 \
        -o "$part" "$url"
    verify "$part" || { echo "Checksum mismatch for $url" >&2; exit 1; }
    if [ -d "$kept" ]; then
        rm -rf -- "$kept"
    fi
    mv -f "$part" "$kept"
    echo "Downloaded and verified pack input: $expected"
fi
cp "$kept" "$destination"
verify "$destination" || { echo 'Copied pack input checksum mismatch' >&2; exit 1; }
