#!/usr/bin/env bash
set -euo pipefail
curl -fsSL --retry 3 -o pa.tar.gz "$COMPONENT_SOURCE_URL"
echo "$COMPONENT_SOURCE_SHA256  pa.tar.gz" | sha256sum -c -
mkdir -p pa13 && tar -xzf pa.tar.gz -C pa13 --strip-components=1 && rm pa.tar.gz
