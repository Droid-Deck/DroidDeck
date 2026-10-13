#!/usr/bin/env bash
# tools/build_local.sh without its native preparation: no Docker, no preload or proot builds, no
# pinned downloads, no audio sinks. Gradle builds the app (Kotlin, resources, the CMake JNI
# libraries and, unless DROIDDECK_SKIP_RUST=1, the store engines) around the runtime payload the
# last build_local.sh run prepared, and the APK is checked and signed the same way. When that
# payload no longer matches its sources (tools/release/runtime_inputs.py verify), run
# build_local.sh again.
set -euo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if tree_status=$(git -C "${repo_root}" status --porcelain 2>/dev/null); then
    if [[ -n "${tree_status}" ]]; then
        export DROIDDECK_BUILD_TREE_STATE=dirty
    else
        export DROIDDECK_BUILD_TREE_STATE=clean
    fi
else
    export DROIDDECK_BUILD_TREE_STATE=unknown
fi
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-"${HOME}/Library/Android/sdk"}}
java_dir=${JAVA_HOME:-"/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"}
build_variant=${DROIDDECK_BUILD_VARIANT:-release}
case "$build_variant" in
    debug) gradle_task=assembleDebug ;;
    release) gradle_task=assembleRelease ;;
    *) echo "DROIDDECK_BUILD_VARIANT must be debug or release" >&2; exit 1 ;;
esac

if [[ ! -x "${sdk_dir}/platform-tools/adb" ]]; then
    echo "Android SDK not found at ${sdk_dir}; set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
    exit 1
fi
if [[ ! -x "${java_dir}/bin/java" ]]; then
    echo "Java 17 not found at ${java_dir}; set JAVA_HOME." >&2
    exit 1
fi
for tool in zstd tar shasum unzip python3; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "${tool} is required to check the APK." >&2
        exit 1
    fi
done

ndk_version=${DROIDDECK_NDK_VERSION:-}
if [[ -z "${ndk_version}" ]]; then
    # Only complete NDKs: an interrupted sdkmanager install leaves a directory without source.properties.
    ndk_path=$(find "${sdk_dir}/ndk" -mindepth 2 -maxdepth 2 -name source.properties -print | xargs -n1 dirname | sort -V | tail -1)
    ndk_version=${ndk_path##*/}
fi
if [[ -z "${ndk_version}" || ! -d "${sdk_dir}/ndk/${ndk_version}" ]]; then
    echo "No Android NDK found under ${sdk_dir}/ndk; set DROIDDECK_NDK_VERSION." >&2
    exit 1
fi
export ANDROID_HOME="${sdk_dir}"
export ANDROID_SDK_ROOT="${sdk_dir}"
export JAVA_HOME="${java_dir}"
export NDK="${sdk_dir}/ndk/${ndk_version}"

staging_dir=$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-build.XXXXXX")
cleanup() {
    local exit_code=$?
    trap - EXIT
    rm -rf -- "${staging_dir}" || exit_code=1
    exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "${repo_root}"
if ! python3 tools/release/runtime_inputs.py verify; then
    echo "The runtime payload from tools/build_local.sh is missing or stale; run tools/build_local.sh, then this again." >&2
    exit 1
fi

# The GOG / Epic / Amazon download engines are Rust (app/src/main/rust/stores); Gradle's
# buildRustStores task cross-compiles them with cargo-ndk, which needs the Android target and the
# cargo-ndk binary installed once. DROIDDECK_SKIP_RUST=1 builds an apk without the engines (the
# stores then report themselves unavailable) for a machine without a Rust toolchain.
gradle_rust_arg=""
if [[ "${DROIDDECK_SKIP_RUST:-0}" == 1 ]]; then
    gradle_rust_arg="-PskipRust=true"
else
    export PATH="${HOME}/.cargo/bin:${PATH}"
    for tool in rustup cargo; do
        if ! command -v "${tool}" >/dev/null 2>&1; then
            echo "${tool} is required for the store engines (https://rustup.rs); set DROIDDECK_SKIP_RUST=1 to build without them." >&2
            exit 1
        fi
    done
    rustup target add --toolchain stable aarch64-linux-android
    if ! command -v cargo-ndk >/dev/null 2>&1; then
        cargo install cargo-ndk --locked
    fi
fi

./gradlew "${gradle_task}" --console=plain -PndkVersion="${ndk_version}" ${gradle_rust_arg}
python3 tools/release/check_session_assets.py "app/build/outputs/apk/${build_variant}/app-${build_variant}.apk"
python3 tools/release/runtime_inputs.py apk "app/build/outputs/apk/${build_variant}/app-${build_variant}.apk"

apk="${repo_root}/app/build/outputs/apk/${build_variant}/app-${build_variant}.apk"
audio_check="${staging_dir}/audio-check"
mkdir -p "${audio_check}"
unzip -p "${apk}" assets/pulseaudio.tzst | zstd -dc | tar -xf - -C "${audio_check}"
for audio_file in \
    pactl \
    modules/arm64/module-aaudio-sink.so \
    modules/arm64/module-directaudio-native-sink.so; do
    if [[ ! -f "${audio_check}/${audio_file}" ]]; then
        echo "APK audio bundle is missing ${audio_file}." >&2
        exit 1
    fi
done

readelf_path=$(find "${NDK}/toolchains/llvm/prebuilt" -name llvm-readelf | head -1)
[[ -x "${readelf_path}" ]] || { echo "NDK llvm-readelf is missing" >&2; exit 1; }
stores_check=()
[[ "${DROIDDECK_SKIP_RUST:-0}" != 1 ]] || stores_check+=(--without-stores)
python3 tools/release/check_android_libraries.py "${apk}" --readelf "${readelf_path}" "${stores_check[@]}"
git diff --exit-code -- app/src/main/rust/stores/Cargo.lock

build_tools=$(find "${sdk_dir}/build-tools" -mindepth 1 -maxdepth 1 -type d -print | sort -V | tail -1)
if [[ ! -x "${build_tools}/zipalign" || ! -x "${build_tools}/apksigner" ]]; then
    echo "Android build-tools with zipalign/apksigner are required under ${sdk_dir}/build-tools." >&2
    exit 1
fi

"${build_tools}/zipalign" -p -f 4 "${apk}" "${apk}.aligned"
"${build_tools}/apksigner" sign \
    --ks "${repo_root}/keystore/testkey.p12" --ks-type PKCS12 --ks-pass pass:android \
    --ks-key-alias testkey --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "${apk}" "${apk}.aligned"
rm -f "${apk}.aligned" "${apk}.idsig"

signature_output=$("${build_tools}/apksigner" verify --min-sdk-version 21 --verbose --print-certs "${apk}")
printf '%s\n' "${signature_output}"
if ! unzip -l "${apk}" | grep -E 'META-INF/.*\.(SF|RSA|DSA)$' >/dev/null; then
    echo "APK signature check failed: JAR signature files are missing." >&2
    exit 1
fi
for scheme in \
    'Verified using v1 scheme (JAR signing): true' \
    'Verified using v2 scheme (APK Signature Scheme v2): true' \
    'Verified using v3 scheme (APK Signature Scheme v3): true'; do
    grep -qF "${scheme}" <<<"${signature_output}" || {
        echo "APK signature check failed: ${scheme}" >&2
        exit 1
    }
done
grep -qi 'CN=Android, OU=Android, O=Android' <<<"${signature_output}" || {
    echo "APK signature check failed: the signer is not the AOSP testkey." >&2
    exit 1
}

# With DroidDeck's own key at hand, sign as CI's main builds are (tools/release/sign-apk.sh), so
# the apk installs over a release instead of being refused as a different signer. The key's
# location and password live in an untracked .signing.env (RELEASE_KEYSTORE, RELEASE_STORE_PASSWORD,
# RELEASE_KEY_ALIAS), looked for in this checkout and then in the main one, which worktrees share.
signing_env=${DROIDDECK_SIGNING_ENV:-}
if [[ -z "${signing_env}" ]]; then
    for candidate in "${repo_root}/.signing.env" \
            "$(dirname "$(git -C "${repo_root}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || echo .)")/.signing.env"; do
        [[ -f "${candidate}" ]] && { signing_env=${candidate}; break; }
    done
fi
if [[ -n "${signing_env}" ]]; then
    echo "Signing with DroidDeck's key (${signing_env})"
    (
        set -a
        # shellcheck disable=SC1090
        cd "$(dirname "${signing_env}")"
        . "${signing_env}"
        set +a
        BUILD_TOOLS="${build_tools}" "${repo_root}/tools/release/sign-apk.sh" "${apk}" standard "${apk}.release"
    )
    mv "${apk}.release" "${apk}"
fi

printf 'APK: %s\n' "${apk}"
printf 'SHA-256: '
shasum -a 256 "${apk}" | awk '{print $1}'
