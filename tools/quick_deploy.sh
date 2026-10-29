#!/usr/bin/env bash
# Kotlin/resource-only loop: reuses the assets build_local.sh already prepared, then signs and installs.
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "${repo_root}"
sdk_dir=${ANDROID_HOME:-"${HOME}/Library/Android/sdk"}
export ANDROID_HOME=${sdk_dir} ANDROID_SDK_ROOT=${sdk_dir}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
out=${1:-"${TMPDIR:-/tmp}/droiddeck-quick-signed.apk"}
./gradlew -q :app:assembleRelease
set -a; . ./.signing.env; set +a
BUILD_TOOLS=$(ls -d "${sdk_dir}"/build-tools/* | sort -V | tail -1) \
    tools/release/sign-apk.sh app/build/outputs/apk/release/app-release.apk standard "${out}" | tail -1
tools/deploy_local.sh "${out}" | tail -2
