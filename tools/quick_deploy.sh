#!/usr/bin/env bash
# Kotlin/resource-only loop: reuses the assets build_local.sh already prepared, then signs and installs.
set -euo pipefail
repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "${repo_root}"
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"${HOME}/Library/Android/sdk"}}
export ANDROID_HOME=${sdk_dir} ANDROID_SDK_ROOT=${sdk_dir}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
out=${1:-"${TMPDIR:-/tmp}/droiddeck-quick-signed.apk"}

signing_env=${DROIDDECK_SIGNING_ENV:-}
if [[ -z "${signing_env}" ]]; then
    for candidate in "${repo_root}/.signing.env" \
            "$(dirname "$(git -C "${repo_root}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || echo .)")/.signing.env"; do
        [[ -f "${candidate}" ]] && { signing_env=${candidate}; break; }
    done
fi
[[ -f "${signing_env}" ]] || { echo "No signing env found; set DROIDDECK_SIGNING_ENV or add .signing.env." >&2; exit 1; }

build_tools=
while IFS= read -r dir; do
    [[ -x "${dir}/aapt" && -x "${dir}/zipalign" && -x "${dir}/apksigner" ]] && { build_tools=${dir}; break; }
done < <(ls -d "${sdk_dir}"/build-tools/* 2>/dev/null | sort -rV)
[[ -n "${build_tools}" ]] || { echo "No build-tools with aapt, zipalign and apksigner under ${sdk_dir}." >&2; exit 1; }

./gradlew -q :app:assembleRelease
(
    cd "$(dirname "${signing_env}")"
    set -a; . "${signing_env}"; set +a
    BUILD_TOOLS=${build_tools} "${repo_root}/tools/release/sign-apk.sh" \
        "${repo_root}/app/build/outputs/apk/release/app-release.apk" standard "${out}" | tail -1
)
"${repo_root}/tools/deploy_local.sh" "${out}" | tail -2
