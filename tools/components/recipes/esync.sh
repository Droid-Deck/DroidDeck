#!/usr/bin/env bash
set -euo pipefail
if [ -f tools/droiddeck-esync/release.env ]; then
  . tools/droiddeck-esync/release.env
  release_url="https://github.com/${SYNC_BUNDLE_REPO}/releases/download/${SYNC_BUNDLE_TAG}"
  curl -fsSL --retry 3 -o sync-bundle.tzst "$release_url/$SYNC_BUNDLE_ASSET"
  echo "$SYNC_BUNDLE_SHA256  sync-bundle.tzst" | sha256sum -c -
  mkdir -p app/src/main/assets/droiddeck-esync
  tar --use-compress-program=unzstd -xf sync-bundle.tzst -C app/src/main/assets/droiddeck-esync
  rm sync-bundle.tzst
  curl -fsSL --retry 3 -o sync-index.json "$release_url/index.json"
  revoked=$(python3 -c 'import json, sys; print(" ".join(p["id"] for p in json.load(open(sys.argv[1]))["packs"] if p.get("revoked") is True))' sync-index.json)
  for id in $revoked; do
    if [ -e "app/src/main/assets/droiddeck-esync/packs/$id.tzst" ]; then
      echo "::warning::$SYNC_BUNDLE_ASSET carries revoked pack $id; it is left out of the APK (point tools/droiddeck-esync/release.env at a newer bundle)"
      rm -f "app/src/main/assets/droiddeck-esync/packs/$id.tzst"
    fi
  done
  rm sync-index.json
  test -f app/src/main/assets/droiddeck-esync/index.json
  test -f app/src/main/assets/droiddeck-esync/index.json.sig
  ls -lR app/src/main/assets/droiddeck-esync
fi
