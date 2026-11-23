#!/usr/bin/env bash
set -uo pipefail
workspace="${1:?}"
find /tmp -maxdepth 1 -user "$(id -u)" \( -name 'robolectric-*' -o -name 'junit*' -o -name 'gradle-*' -o -name 'kotlin-*' \) -mmin +180 -exec rm -rf --one-file-system -- {} +
free=$(df --output=avail -k "$workspace" | tail -1)
if [ "$free" -lt 20971520 ]; then
  rm -rf --one-file-system -- "${HOME:?}/.gradle/caches/build-cache-1" "${HOME:?}/.gradle/.tmp"
fi
df -h /tmp "$workspace"
du -xsh /tmp "${HOME:?}/.gradle" "${HOME:?}/.cargo" "${HOME:?}/.m2" "$(dirname "$workspace")" 2>/dev/null | sort -h
exit 0
