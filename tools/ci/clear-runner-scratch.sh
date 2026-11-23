#!/usr/bin/env bash
set -uo pipefail
workspace="${1:?}"
find /tmp -maxdepth 1 -user "$(id -u)" \( -name 'robolectric-*' -o -name 'junit*' -o -name 'gradle-*' -o -name 'kotlin-*' \) -mmin +180 -print0 |
  while IFS= read -r -d '' entry; do
    [ -z "$(find "$entry" -xdev -mmin -180 -print -quit 2>/dev/null)" ] && rm -rf --one-file-system --preserve-root=all -- "$entry"
  done
free=$(df --output=avail -k "$workspace" | tail -1)
if [ "$free" -lt 20971520 ]; then
  rm -rf --one-file-system --preserve-root=all -- "${HOME:?}/.gradle/caches/build-cache-1" "${HOME:?}/.gradle/.tmp"
fi
df -h /tmp "$workspace"
du -xsh /tmp "${HOME:?}/.gradle" "${HOME:?}/.cargo" "${HOME:?}/.m2" "$(dirname "$workspace")" 2>/dev/null | sort -h
exit 0
