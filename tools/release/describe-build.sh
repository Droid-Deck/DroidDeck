#!/usr/bin/env bash
set -euo pipefail
pr=$(gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/pulls" \
  --jq '[.[] | select(.merged_at != null and .base.ref == "main")] | first // empty')
if [ -n "$pr" ]; then
  number=$(jq -r .number <<<"$pr")
  title=$(jq -r .title <<<"$pr")
  merged=$(jq -r '.merged_at[:10]' <<<"$pr")
  summary=$(jq -r '.body // ""' <<<"$pr" | tr -d '\r' | { grep -v '^🤖' || true; } |
    awk '!p && /^#/ { next } NF { p = 1; print; next } p { exit }' | head -n 6 | cut -c1-400)
  {
    echo "**Merged to main** - [PR #$number]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/pull/$number): $title (merged $merged)"
    if [ -n "$summary" ]; then
      echo
      echo "$summary"
    fi
  } > notes-head.md
else
  subject=$(gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA" --jq '.commit.message | split("\n")[0]')
  echo "**Pushed to main** without a PR - $subject" > notes-head.md
fi
cat notes-head.md
