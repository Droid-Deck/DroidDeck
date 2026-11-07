#!/usr/bin/env bash
#
# Builds the installable HAP.
#
# The build orchestrator (Hvigor), ohpm and Node are not in the public
# OpenHarmony SDK: they ship inside DevEco Studio. This script therefore
# drives the copy inside DevEco Studio, and points the build at the SDK
# given in OHOS_SDK_HOME.
#
# Usage:
#   OHOS_SDK_HOME=/path/to/openharmony-sdk tools/build_hap.sh
#
# The result is an unsigned HAP. A phone only installs a HAP that Huawei
# has signed, so open the project in DevEco Studio afterwards and use
# File > Project Structure > Signing Configs > Automatically generate
# signature.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root}"

: "${OHOS_SDK_HOME:?set OHOS_SDK_HOME to the OpenHarmony SDK root}"
deveco="${DEVECO_HOME:-/Applications/DevEco-Studio.app/Contents/tools}"

if [ ! -x "${deveco}/hvigor/bin/hvigorw" ]; then
  echo "DevEco Studio's hvigor was not found at ${deveco}/hvigor/bin/hvigorw" >&2
  echo "Install DevEco Studio, or point DEVECO_HOME at its tools directory." >&2
  exit 1
fi

export PATH="${deveco}/node/bin:${deveco}/ohpm/bin:${PATH}"

# Hvigor reads the SDK location from local.properties, which is a local
# pointer and therefore not part of the repository.
printf 'sdk.dir=%s\n' "${OHOS_SDK_HOME}" > local.properties

echo "==> installing the build dependencies"
"${deveco}/ohpm/bin/ohpm" install --all --registry https://repo.harmonyos.com/ohpm/

echo "==> building the HAP"
"${deveco}/hvigor/bin/hvigorw" --mode module -p product=default assembleHap --no-daemon

echo "==> done"
find entry/build -name '*.hap' 2>/dev/null | head -5
