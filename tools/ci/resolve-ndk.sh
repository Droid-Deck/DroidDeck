#!/usr/bin/env bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
source "$repo/tools/ci/toolchains.env"
ndk="$ANDROID_HOME/ndk/$CI_ANDROID_NDK_VERSION"
if [ ! -f "$ndk/source.properties" ]; then
  sdkmanager --sdk_root="$ANDROID_HOME" --install "ndk;$CI_ANDROID_NDK_VERSION"
fi
actual=$(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties" | tr -d '\r')
test "$actual" = "$CI_ANDROID_NDK_VERSION"
{
  echo "NDK_VERSION=$actual"
  echo "ANDROID_NDK_HOME=$ndk"
  echo "ANDROID_NDK_ROOT=$ndk"
  echo "ANDROID_NDK_LATEST_HOME=$ndk"
} >> "$GITHUB_ENV"
