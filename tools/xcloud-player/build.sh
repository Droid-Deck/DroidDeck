#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
"${ESBUILD:-esbuild}" "$here/entry.ts" --bundle --format=iife --target=chrome110 --minify \
    --alias:uuid="$here/uuid.ts" --legal-comments=inline \
    --banner:js="/* DroidDeck Xbox Cloud Gaming player. Includes Greenlight's player (c) UnknownSKL (Jim Kroon), MIT. */" \
    --outfile="$repo/app/src/main/assets/xcloud/player.js"
