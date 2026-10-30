#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/../.."
groups=${PYTHON_TEST_GROUPS:-3}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
index=0
while read -r file; do
  module=$(basename "$file" .py)
  echo "$module" >> "$work/group-$((index % groups)).list"
  index=$((index + 1))
done < <(ls -S tools/tests/test_*.py)
pids=()
for list in "$work"/group-*.list; do
  (
    PYTHONPATH=tools/tests python3 -m unittest $(cat "$list") > "${list%.list}.log" 2>&1
    echo $? > "${list%.list}.status"
  ) &
  pids+=($!)
done
wait "${pids[@]}"
failed=0
for list in "$work"/group-*.list; do
  echo "::group::$(basename "$list"): $(tr '\n' ' ' < "$list" | cut -c1-120)"
  cat "${list%.list}.log"
  echo "::endgroup::"
  if [ "$(cat "${list%.list}.status")" != 0 ]; then
    failed=1
    echo "::error::$(basename "$list") failed"
  fi
done
exit "$failed"
