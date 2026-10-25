#!/usr/bin/env bash
set -euo pipefail
tag=main-${GITHUB_SHA::7}
cat signed/SHA256SUMS-*.txt > signed/SHA256SUMS.txt
rm signed/SHA256SUMS-*.txt
notes="$(cat notes-head.md)

Signed build of $GITHUB_SERVER_URL/$GITHUB_REPOSITORY/commit/$GITHUB_SHA - in main, not yet in a DroidDeck release; for testing.
Commit: $GITHUB_SHA
VersionCode: $(sed -n 's/^ *versionCode \([0-9][0-9]*\)$/\1/p' app/build.gradle)
Each apk installs over a DroidDeck release and keeps its data. \`standard\` is the normal app; the others are the same app under another package name, installed beside it.

SHA-256:
\`\`\`
$(cat signed/SHA256SUMS.txt)
\`\`\`"
gh release delete "$tag" -R "$CI_REPO" --yes --cleanup-tag 2>/dev/null || true
gh release create "$tag" -R "$CI_REPO" --target main --prerelease \
  --title "main ${GITHUB_SHA::7}" --notes "$notes" signed/*
gh release list -R "$CI_REPO" -L 200 --json tagName,publishedAt \
  --jq '[.[] | select(.tagName | startswith("main-"))] | sort_by(.publishedAt) | reverse | .['"$KEEP"':][] | .tagName' |
  while IFS= read -r old; do
    if [ "$old" = "$tag" ]; then
      continue
    fi
    if ! gh release delete "$old" -R "$CI_REPO" --yes --cleanup-tag 2>/dev/null; then
      if gh release view "$old" -R "$CI_REPO" >/dev/null 2>&1; then
        echo "::error::Could not delete old release $old"
        exit 1
      fi
    fi
  done
published=$(gh release view "$tag" -R "$CI_REPO" --json tagName,assets)
jq -e --arg tag "$tag" '.tagName == $tag and (.assets | length) > 0' <<< "$published" >/dev/null
url="$GITHUB_SERVER_URL/$CI_REPO/releases/tag/$tag"
apk=$(cd signed && ls DroidDeck-*.apk | awk 'length(a) == 0 || length($0) < length(a) { a = $0 } END { print a }')
echo "release=$url" >> "$GITHUB_OUTPUT"
echo "apk=$GITHUB_SERVER_URL/$CI_REPO/releases/download/$tag/$apk" >> "$GITHUB_OUTPUT"
echo "Published $url"
