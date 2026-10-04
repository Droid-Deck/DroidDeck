#!/usr/bin/env bash
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
image_name=${DROIDDECK_BUILD_IMAGE:-droiddeck-local-cross:24.04-v2}
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
if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required to cross-compile the glibc ARM64 preload libraries." >&2
    exit 1
fi
for tool in curl tar zstd shasum unzip; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        echo "${tool} is required to build the CI-equivalent APK." >&2
        exit 1
    fi
done
if [[ -f "${repo_root}/tools/gamescope/release.env" || -f "${repo_root}/tools/wlroots/release.env" ]] \
        && ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI is required to download the pinned Gamescope and wlroots release assets." >&2
    exit 1
fi

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
bundle_asset="${repo_root}/app/src/main/assets/pulseaudio.tzst"
bundle_backup="${staging_dir}/pulseaudio.original.tzst"
bundle_replaced=0
linuxfs_dir="${repo_root}/app/src/main/assets/linuxfs"
linuxfs_backup="${staging_dir}/linuxfs.original"
linuxfs_preexisting=0
linuxfs_replaced=0
cleanup() {
    local exit_code=$?
    trap - EXIT
    if [[ "${bundle_replaced}" == 1 ]]; then
        cp -p "${bundle_backup}" "${bundle_asset}" || exit_code=1
    fi
    if [[ "${linuxfs_replaced}" == 1 ]]; then
        rm -rf -- "${linuxfs_dir}" || exit_code=1
        if [[ "${linuxfs_preexisting}" == 1 ]]; then
            mv "${linuxfs_backup}" "${linuxfs_dir}" || exit_code=1
        fi
    fi
    rm -rf -- "${staging_dir}" || exit_code=1
    exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ -d "${linuxfs_dir}" ]]; then
    cp -a "${linuxfs_dir}" "${linuxfs_backup}"
    linuxfs_preexisting=1
fi
linuxfs_replaced=1
rm -rf -- "${linuxfs_dir}"
mkdir -p "${linuxfs_dir}"

# Docker Desktop's VM restarts now and then, and until its engine has loaded its image store it
# answers "No such image" for images it has. The rebuild that follows hangs on the registry
# (the base image's credentials go through docker-credential-desktop), so give a restarting daemon
# up to a minute before deciding the image is really missing.
image_present=0
for _ in $(seq 1 30); do
    if inspect_error=$(docker image inspect "${image_name}" 2>&1 >/dev/null); then image_present=1; break; fi
    sleep 2
done
if [[ "${image_present}" = 0 ]]; then
    echo "Build image ${image_name} not found (${inspect_error:-no error}); building it." >&2
    docker build --platform linux/amd64 -t "${image_name}" \
        -f "${repo_root}/tools/local-cross.Dockerfile" "${repo_root}"
fi

docker run --rm --platform linux/amd64 \
    --user "$(id -u):$(id -g)" \
    -v "${repo_root}:/src" -w /src "${image_name}" bash -lc '
        set -euo pipefail
        d=app/src/main/assets/linuxfs
        mkdir -p "$d"
        aarch64-linux-gnu-g++ -shared -fPIC -O2 -Wall -Wno-attributes -Wno-nonnull-compare \
            -pthread -std=c++17 -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL \
            -o "$d/libfakeinput.so" app/src/main/cpp/fakeinput_steam.cpp -ldl
        aarch64-linux-gnu-strip --strip-unneeded "$d/libfakeinput.so"
        aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
            -o "$d/libblsession.so" tools/linuxfs/preload/*.c -ldl
        aarch64-linux-gnu-strip --strip-unneeded "$d/libblsession.so"
        aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
            -o "$d/libblfastpath.so" tools/proot/fastpath/fastpath.c -ldl
        aarch64-linux-gnu-strip --strip-unneeded "$d/libblfastpath.so"
        mkdir -p "$d/usr/local/bin"
        aarch64-linux-gnu-gcc -O2 -Wall -Wextra -o "$d/usr/local/bin/droiddeck-clipboard" tools/linuxfs/clipboard/clipboard.c -ldl
        aarch64-linux-gnu-strip --strip-unneeded "$d/usr/local/bin/droiddeck-clipboard"
        for script in tools/linuxfs/overlay/usr/local/bin/bannerlator-*; do
            install -Dm644 "$script" "$d/usr/local/bin/$(basename "$script")"
        done
        for f in tools/linuxfs/overlay/usr/bin/* tools/linuxfs/overlay/usr/bin/steamos-polkit-helpers/*; do
            [ -f "$f" ] && install -Dm644 "$f" "$d/${f#tools/linuxfs/overlay/}"
        done
        install -Dm644 tools/linuxfs/desktop/droiddeck-desktop "$d/usr/local/bin/droiddeck-desktop"
        install -Dm644 tools/linuxfs/desktop/droiddeck-gpu "$d/usr/local/bin/droiddeck-gpu"
        install -Dm644 tools/linuxfs/desktop/droiddeck-desktop-gpu "$d/usr/local/bin/droiddeck-desktop-gpu"
        install -Dm644 tools/linuxfs/desktop/autostart "$d/etc/xdg/labwc/autostart"
        install -Dm644 tools/linuxfs/desktop/rc.xml "$d/etc/xdg/labwc/rc.xml"
        install -Dm644 tools/linuxfs/desktop/panel.conf "$d/etc/xdg/lxqt/panel.conf"
        install -Dm644 tools/linuxfs/desktop/firefox-droiddeck.js \
            "$d/usr/lib/firefox/defaults/pref/droiddeck.js"

        need=$(aarch64-linux-gnu-readelf -d "$d/libfakeinput.so" | sed -n "s/.*NEEDED.*\\[\\(.*\\)\\]/\\1/p")
        for bad in libstdc++.so.6 libgcc_s.so.1; do
            if printf "%s\\n" "$need" | grep -qx "$bad"; then
                echo "libfakeinput.so links $bad; the C++ runtime must stay static" >&2
                exit 1
            fi
        done
        syms() { aarch64-linux-gnu-readelf -Ws "$1" | awk '\''$4 == "FUNC" && $5 == "GLOBAL" {sub(/@.*/, "", $8); print $8}'\''; }
        fake=$(syms "$d/libfakeinput.so")
        for sym in open openat ioctl read close poll ppoll select stat fstat access scandir; do
            printf "%s\\n" "$fake" | grep -qx "$sym" || {
                echo "libfakeinput.so does not export $sym" >&2
                exit 1
            }
        done
        session=$(syms "$d/libblsession.so")
        for sym in socket bind getsockname setsockopt statfs statvfs; do
            printf "%s\\n" "$session" | grep -qx "$sym" || {
                echo "libblsession.so does not export $sym" >&2
                exit 1
            }
        done
        test -f "$d/usr/local/bin/bannerlator-session"
        test -f "$d/usr/local/bin/bannerlator-proton-extra"
    '

github_repo=${DROIDDECK_GITHUB_REPOSITORY:-}
if [[ -z "${github_repo}" ]]; then
    origin_url=$(git -C "${repo_root}" remote get-url origin)
    case "${origin_url}" in
        https://github.com/*) github_repo=${origin_url#https://github.com/} ;;
        ssh://git@github.com/*) github_repo=${origin_url#ssh://git@github.com/} ;;
        git@github.com:*) github_repo=${origin_url#git@github.com:} ;;
        *)
            echo "Cannot determine the GitHub repository from origin: ${origin_url}" >&2
            echo "Set DROIDDECK_GITHUB_REPOSITORY=owner/repo." >&2
            exit 1
            ;;
    esac
    github_repo=${github_repo%.git}
fi

# Pinned downloads are kept between builds, named by their checksum, so a rebuild fetches nothing
# it already has. A cached file is checked again before use; a bad one is fetched anew.
cache_dir=${DROIDDECK_BUILD_CACHE:-"${HOME}/.cache/droiddeck-build"}
mkdir -p "${cache_dir}"
# cached <sha256> <name> <command...>: prints the cached path, running the command (which writes
# to "$out") only when the cache has no file with that checksum.
cached() {
    local sha=$1 name=$2 out
    shift 2
    out="${cache_dir}/${sha}-${name}"
    if [[ -f "${out}" ]] && printf '%s  %s\n' "${sha}" "${out}" | shasum -a 256 -c - >/dev/null 2>&1; then
        echo "${out}"
        return 0
    fi
    rm -f "${out}.part"
    out="${out}.part" "$@" >&2
    printf '%s  %s\n' "${sha}" "${out}.part" | shasum -a 256 -c - >&2
    mv "${out}.part" "${out}"
    echo "${out}"
}

if [[ -f "${repo_root}/tools/gamescope/release.env" ]]; then
    . "${repo_root}/tools/gamescope/release.env"
    gamescope_archive=$(cached "${GAMESCOPE_SHA256}" gamescope.tzst \
        bash -c 'gh release download "$0" -R "$1" -p gamescope.tzst -O "$out"' "${GAMESCOPE_TAG}" "${github_repo}")
    zstd -dc "${gamescope_archive}" | tar -xf - -C "${linuxfs_dir}"
    test -f "${linuxfs_dir}/usr/local/bin/gamescope"
fi

if [[ -f "${repo_root}/tools/wlroots/release.env" ]]; then
    . "${repo_root}/tools/wlroots/release.env"
    wlroots_archive=$(cached "${WLROOTS_SHA256}" wlroots.tzst \
        bash -c 'gh release download "$0" -R "$1" -p wlroots.tzst -O "$out"' "${WLROOTS_TAG}" "${github_repo}")
    zstd -dc "${wlroots_archive}" | tar -xf - -C "${linuxfs_dir}"
    test -f "${linuxfs_dir}/usr/local/lib/droiddeck-wlroots/libwlroots-0.20.so"
fi

mango_dir="${linuxfs_dir}/usr/local/lib/mangoapp"
mango_pkgs="${staging_dir}/mango-pkgs"
mkdir -p "${mango_dir}" "${mango_pkgs}"
while read -r package_sha256 package_url; do
    [[ -n "${package_url}" ]] || continue
    package_archive=$(cached "${package_sha256}" "$(basename "${package_url}")" \
        bash -c 'curl -fsSL --retry 3 -o "$out" "$0"' "${package_url}")
    zstd -dc "${package_archive}" | tar -xf - -C "${mango_pkgs}"
done < <(grep -v '^#' "${repo_root}/tools/mangoapp/packages.txt")
install -m644 "${mango_pkgs}/usr/bin/mangoapp" "${mango_dir}/mangoapp"
for library in libfmt.so.10 libspdlog.so.1.13 libglfw.so.3 libtraceevent.so.1; do
    cp -L "${mango_pkgs}/usr/lib/${library}" "${mango_dir}/${library}"
done
# Ours, not the package's: GPU memory without tracefs (tools/mangoapp/libtracefs-shim.c).
docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" -v "${repo_root}:/src" -w /src "${image_name}" \
    aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wl,-soname,libtracefs.so.1 \
    -o app/src/main/assets/linuxfs/usr/local/lib/mangoapp/libtracefs.so.1 tools/mangoapp/libtracefs-shim.c
mkdir -p "${linuxfs_dir}/usr/local/bin"
install -m644 "${repo_root}/tools/mangoapp/mangoapp" "${linuxfs_dir}/usr/local/bin/mangoapp"

pa_source=${DROIDDECK_PA13_SOURCE_DIR:-"${staging_dir}/pulseaudio-13.0"}
if [[ -z "${DROIDDECK_PA13_SOURCE_DIR:-}" ]]; then
    pa_tarball="${cache_dir}/pulseaudio-13.0.tar.gz"
    if [[ ! -s "${pa_tarball}" ]] || ! tar -tzf "${pa_tarball}" >/dev/null 2>&1; then
        curl -fsSL -o "${pa_tarball}.part" \
            https://github.com/pulseaudio/pulseaudio/archive/refs/tags/v13.0.tar.gz
        mv "${pa_tarball}.part" "${pa_tarball}"
    fi
    mkdir -p "${pa_source}"
    tar -xzf "${pa_tarball}" -C "${pa_source}" --strip-components=1
fi
if [[ ! -f "${pa_source}/src/pulse/version.h.in" ]]; then
    echo "PulseAudio 13.0 source not found at ${pa_source}; set DROIDDECK_PA13_SOURCE_DIR." >&2
    exit 1
fi

sink_output="${staging_dir}/sink-out"
"${repo_root}/tools/aaudio-sink/build.sh" "${pa_source}" "${sink_output}"
# proot is rebuilt only when its sources (source.env, the patches, the build script) changed since
# the libraries in jniLibs were built.
proot_out="${repo_root}/app/src/main/jniLibs/arm64-v8a"
proot_inputs=$(cd "${repo_root}/tools/proot" && find . -type f ! -name '*.pyc' | LC_ALL=C sort | xargs shasum -a 256 | shasum -a 256 | cut -d' ' -f1)
if [[ -f "${proot_out}/libproot.so" && -f "${proot_out}/libproot-loader.so" \
        && "$(cat "${proot_out}/.proot-inputs" 2>/dev/null)" = "${proot_inputs}" ]]; then
    echo "proot: sources unchanged, keeping ${proot_out}/libproot.so"
else
    "${repo_root}/tools/proot/build.sh" "${proot_out}"
    echo "${proot_inputs}" > "${proot_out}/.proot-inputs"
fi

cp -p "${bundle_asset}" "${bundle_backup}"
bundle_dir="${staging_dir}/pulseaudio-bundle"
mkdir -p "${bundle_dir}"
zstd -dc "${bundle_asset}" | tar -xf - -C "${bundle_dir}"
if [[ -e "${bundle_dir}/modules/arm64/module-aaudio-sink.so" \
        || -e "${bundle_dir}/modules/arm64/module-directaudio-sink.so" ]]; then
    echo "The committed audio bundle already contains a built ARM64 sink." >&2
    exit 1
fi
install -m755 "${sink_output}/module-aaudio-sink.so" \
    "${bundle_dir}/modules/arm64/module-aaudio-sink.so"
install -m755 "${sink_output}/module-directaudio-sink.so" \
    "${bundle_dir}/modules/arm64/module-directaudio-sink.so"
tar -cf - -C "${bundle_dir}" . | zstd -19 -T0 -c > "${staging_dir}/pulseaudio.tzst"
bundle_replaced=1
mv "${staging_dir}/pulseaudio.tzst" "${bundle_asset}"

cd "${repo_root}"
./gradlew "${gradle_task}" --console=plain -PndkVersion="${ndk_version}"
python3 tools/release/check_session_assets.py "app/build/outputs/apk/${build_variant}/app-${build_variant}.apk"
cp -p "${bundle_backup}" "${bundle_asset}"
bundle_replaced=0

apk="${repo_root}/app/build/outputs/apk/${build_variant}/app-${build_variant}.apk"
audio_check="${staging_dir}/audio-check"
mkdir -p "${audio_check}"
unzip -p "${apk}" assets/pulseaudio.tzst | zstd -dc | tar -xf - -C "${audio_check}"
for audio_file in \
    pactl \
    modules/arm64/module-aaudio-sink.so \
    modules/arm64/module-directaudio-sink.so; do
    if [[ ! -f "${audio_check}/${audio_file}" ]]; then
        echo "APK audio bundle is missing ${audio_file}." >&2
        exit 1
    fi
done

docker run --rm --platform linux/amd64 -e build_variant="${build_variant}" -v "${repo_root}:/src:ro" -w /src "${image_name}" \
    bash -lc '
        set -euo pipefail
        apk=app/build/outputs/apk/${build_variant}/app-${build_variant}.apk
        work=$(mktemp -d)
        unzip -q "$apk" "lib/arm64-v8a/*" -d "$work"
        cd "$work/lib/arm64-v8a"
        system="libc.so libm.so libdl.so liblog.so libandroid.so libz.so libvulkan.so libstdc++.so
            libGLESv2.so libEGL.so libnativewindow.so libjnigraphics.so libaaudio.so
            libOpenSLES.so libmediandk.so libcamera2ndk.so libsync.so libneuralnetworks.so"
        fail=0
        for so in *.so; do
            for need in $(readelf -d "$so" | sed -n "s/.*NEEDED.*\\[\\(.*\\)\\]/\\1/p"); do
                [ -f "$need" ] && continue
                case " $(echo $system) " in *" $need "*) continue ;; esac
                echo "missing: $so -> $need"
                fail=1
            done
        done
        [ "$fail" -eq 0 ]
        echo "every NEEDED resolves"
    '

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
        . "${signing_env}"
        set +a
        BUILD_TOOLS="${build_tools}" "${repo_root}/tools/release/sign-apk.sh" "${apk}" standard "${apk}.release"
    )
    mv "${apk}.release" "${apk}"
fi

printf 'APK: %s\n' "${apk}"
printf 'SHA-256: '
shasum -a 256 "${apk}" | awk '{print $1}'
