#!/usr/bin/env bash
#
# Builds the installable HAP.
#
# The build orchestrator (Hvigor), ohpm and Node are not in the public
# OpenHarmony SDK: they ship inside DevEco Studio. This script finds that
# copy, points the build at the SDK that DevEco Studio installed and produces
# an unsigned HAP.
#
# Usage:
#   tools/build_hap.sh [--sdk <sdk root>] [--native]
#
#   --sdk    Build against this SDK root instead of the one DevEco Studio
#            installed. The root has to hold the "openharmony" and/or "hms"
#            component directories, because Hvigor picks the API from them
#            based on the "runtimeOS" in build-profile.json5.
#   --native Rebuild the native module from source with the SDK's NDK
#            instead of shipping the prebuilt libraries that are in
#            entry/libs/arm64-v8a.
#
# The result is an unsigned HAP. A phone only installs a HAP that Huawei has
# signed, so open the project in DevEco Studio afterwards and use
# File > Project Structure > Signing Configs > Automatically generate
# signature.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root}"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

sdk_override=""
build_native=0
while [ $# -gt 0 ]; do
  case "$1" in
    --sdk)
      sdk_override="${2:-}"
      [ -n "${sdk_override}" ] || die "--sdk needs a path"
      shift 2
      ;;
    --sdk=*) sdk_override="${1#--sdk=}"; shift ;;
    --native) build_native=1; shift ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

# ------------------------------------------------------------- DevEco ----

deveco_app=""
for candidate in \
    "${DEVECO_HOME:-}" \
    "/Applications/DevEco-Studio.app" \
    "/Applications/DevEco Studio.app" \
    "${HOME}/Applications/DevEco-Studio.app" \
    "${HOME}/Applications/DevEco Studio.app"; do
  if [ -n "${candidate}" ] && [ -d "${candidate}/Contents" ]; then
    deveco_app="${candidate}"
    break
  fi
done

if [ -z "${deveco_app}" ]; then
  cat >&2 <<'EOT'
error: DevEco Studio was not found.

Hvigor, ohpm and Node ship inside DevEco Studio, so it has to be installed
before a HAP can be built. Install the macOS (Apple Silicon) build from

    https://developer.huawei.com/consumer/cn/download/

and run this script again. If DevEco Studio lives somewhere else, point
DEVECO_HOME at its app bundle:

    DEVECO_HOME=/path/to/DevEco-Studio.app tools/build_hap.sh
EOT
  exit 1
fi

contents="${deveco_app}/Contents"

resolve_tool() {  # resolve_tool <file name> <label> <known location>...
  local name="$1" label="$2"
  shift 2
  local candidate
  for candidate in "$@"; do
    if [ -x "${candidate}" ]; then printf '%s' "${candidate}"; return 0; fi
  done
  while IFS= read -r candidate; do
    if [ -x "${candidate}" ]; then printf '%s' "${candidate}"; return 0; fi
  done < <(find "${contents}" -maxdepth 6 -type f -name "${name}" 2>/dev/null)
  die "${label} was not found inside ${deveco_app}"
}

hvigorw="$(resolve_tool hvigorw Hvigor "${contents}/tools/hvigor/bin/hvigorw")"
ohpm_bin="$(resolve_tool ohpm ohpm "${contents}/tools/ohpm/bin/ohpm")"
node_bin="$(resolve_tool node Node "${contents}/tools/node/bin/node")"
node_dir="$(dirname "${node_bin}")"

# DevEco Studio bundles its own JDK.
if [ -d "${contents}/jbr/Contents/Home" ]; then
  export JAVA_HOME="${contents}/jbr/Contents/Home"
elif [ -d "${contents}/jre/Contents/Home" ]; then
  export JAVA_HOME="${contents}/jre/Contents/Home"
fi
export PATH="${node_dir}:$(dirname "${ohpm_bin}"):${PATH}"

log "using $(basename "${deveco_app}")"
log "  hvigor: ${hvigorw}"
log "  ohpm:   ${ohpm_bin}"
log "  node:   ${node_bin}"

# ---------------------------------------------------------------- SDK ----

sdk_root="${sdk_override}"
if [ -z "${sdk_root}" ]; then
  for candidate in \
      "${contents}/sdk/default" \
      "${contents}/sdk" \
      "${HOME}/Library/Huawei/Sdk/default" \
      "${HOME}/Library/Huawei/Sdk"; do
    if [ -d "${candidate}/openharmony" ] || [ -d "${candidate}/hms" ]; then
      sdk_root="${candidate}"
      break
    fi
  done
fi

shim_root=""
if [ -z "${sdk_root}" ]; then
  # Present a plain OpenHarmony SDK the way DevEco presents its own: a root
  # that holds the component directories.
  base="${OHOS_SDK_HOME:-}"
  [ -n "${base}" ] || die "no SDK found; pass --sdk <root> or set OHOS_SDK_HOME"
  shim_root="$(mktemp -d)"
  ln -s "${base}" "${shim_root}/openharmony"
  sdk_root="${shim_root}"
  log "using the OpenHarmony SDK at ${base}"
else
  log "using the SDK at ${sdk_root}"
fi

# Hvigor reads the SDK location from local.properties, which is a local
# pointer and therefore not part of the repository.
printf 'sdk.dir=%s\nnodejs.dir=%s\n' "${sdk_root}" "${node_dir}" > local.properties

runtime_os="$(sed -n 's/.*"runtimeOS"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' build-profile.json5 | head -1)"
[ -n "${runtime_os}" ] || runtime_os="HarmonyOS"

case "${runtime_os}" in
  HarmonyOS) component="${sdk_root}/hms" ;;
  OpenHarmony) component="${sdk_root}/openharmony" ;;
  *) die "unknown runtimeOS in build-profile.json5: ${runtime_os}" ;;
esac

metadata=""
for name in oh-uni-package.json uni-package.json; do
  if [ -f "${component}/ets/${name}" ]; then
    metadata="${component}/ets/${name}"
    break
  fi
done

read_metadata() {  # read_metadata <field>
  python3 - "${metadata}" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    print(json.load(handle).get(sys.argv[2], ""))
PY
}

candidates=()
add_candidate() {
  local value="$1" existing
  [ -n "${value}" ] || return 0
  for existing in ${candidates[@]+"${candidates[@]}"}; do
    [ "${existing}" = "${value}" ] && return 0
  done
  candidates+=("${value}")
}

if [ -n "${metadata}" ]; then
  platform_version="$(read_metadata platformVersion)"
  api_version="$(read_metadata apiVersion)"
  log "SDK ${component}: platform ${platform_version}, API ${api_version}"
  add_candidate "${platform_version}"
  add_candidate "${platform_version}(${api_version})"
  add_candidate "${platform_version} (${api_version})"
  add_candidate "${api_version}"
else
  log "no SDK metadata in ${component}; keeping the version from build-profile.json5"
fi

if [ ${#candidates[@]} -eq 0 ]; then
  current="$(sed -n 's/.*"compileSdkVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' build-profile.json5 | head -1)"
  add_candidate "${current}"
fi

# ------------------------------------------------------- build setup ----

backup_dir="$(mktemp -d)"
cp build-profile.json5 "${backup_dir}/build-profile.json5"
cp entry/build-profile.json5 "${backup_dir}/entry-build-profile.json5"

cleanup() {
  cp "${backup_dir}/build-profile.json5" build-profile.json5
  cp "${backup_dir}/entry-build-profile.json5" entry/build-profile.json5
  rm -rf "${backup_dir}"
  [ -n "${shim_root}" ] && rm -rf "${shim_root}"
  return 0
}
trap cleanup EXIT

if [ "${build_native}" -eq 0 ]; then
  log "shipping the prebuilt native libraries"
  python3 tools/patch_build_profile.py drop-native entry/build-profile.json5
else
  log "rebuilding the native module from source"
fi

# Hvigor packages entry/libs/arm64-v8a as it stands. Keep a copy so that a
# build that clears the directory cannot lose the prebuilt libraries.
prebuilt_backup="$(mktemp -d)"
if [ -d entry/libs/arm64-v8a ]; then
  cp -R entry/libs/arm64-v8a/. "${prebuilt_backup}/"
fi

log "installing the build dependencies"
if ! "${ohpm_bin}" install --all; then
  log "retrying without the development dependencies"
  if ! "${ohpm_bin}" install --production; then
    log "linking the native package by hand"
    mkdir -p entry/oh_modules
    ln -sfn ../src/main/cpp/types/libdroiddeck_native entry/oh_modules/libdroiddeck_native.so
  fi
fi

# ------------------------------------------------------------- build ----

build_log="${backup_dir}/hvigor.log"
status=1
for version in ${candidates[@]+"${candidates[@]}"}; do
  python3 tools/patch_build_profile.py set-sdk-version build-profile.json5 "${version}"
  log "building with SDK version '${version}'"
  set +e
  "${hvigorw}" --mode module -p module=entry@default -p product=default \
    assembleHap --no-daemon 2>&1 | tee "${build_log}"
  status=${PIPESTATUS[0]}
  set -e
  [ "${status}" -eq 0 ] && break
  # Only a version string that the installed SDK does not recognise is worth
  # another attempt; anything else is a real build error.
  if ! grep -qiE 'unable to find|sdk version|component:|does not match|not (installed|found)' "${build_log}"; then
    break
  fi
  log "the SDK did not accept '${version}', trying the next spelling"
done

# Put the prebuilt libraries back if the build cleared them.
if [ -d "${prebuilt_backup}" ]; then
  for library in "${prebuilt_backup}"/*; do
    [ -e "${library}" ] || continue
    name="$(basename "${library}")"
    [ -e "entry/libs/arm64-v8a/${name}" ] || cp "${library}" "entry/libs/arm64-v8a/${name}"
  done
  rm -rf "${prebuilt_backup}"
fi

[ "${status}" -eq 0 ] || die "the HAP build failed; see ${build_log}"

log "done"
find entry/build -name '*.hap' -type f 2>/dev/null | while read -r hap; do
  printf '\n%s\n' "${hap}"
  unzip -l "${hap}" 2>/dev/null | grep -E 'libs/|module\.json|\.abc|resources\.index' | head -30
done
