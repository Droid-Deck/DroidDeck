#!/usr/bin/env bash
set -euo pipefail
if [ -z "$WEBHOOK" ]; then
  echo "::notice::No DISCORD_BUILDS_WEBHOOK secret; nothing posted"
  exit 0
fi
commit=$(gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA")
subject=$(jq -r '.commit.message | split("\n")[0]' <<<"$commit")
title=$(jq -r '.commit.message | split("\n") | map(select(length > 0)) | .[1] // ""' <<<"$commit")
pr=$(sed -n 's/^Merge pull request #\([0-9]*\).*/\1/p' <<<"$subject")
if [ -n "$pr" ]; then
  what="[#$pr]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/pull/$pr): ${title:-$subject}"
else
  what="[\`${GITHUB_SHA::7}\`]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/commit/$GITHUB_SHA): $subject"
fi
get=$(printf 'Download: [standard APK](<%s>) · [all variants](<%s>)' "$APK" "$RELEASE")
content=$(printf '**New signed Preview build** `main-%s`\n%s\nIn the app: **Updates → Preview**\n%s' "${GITHUB_SHA::7}" "$what" "$get")
jq -n --arg content "$content" '{content: $content, allowed_mentions: {parse: []}}' |
  curl -fsS -H 'Content-Type: application/json' -d @- "$WEBHOOK" -o /dev/null
echo "Posted: $content"
