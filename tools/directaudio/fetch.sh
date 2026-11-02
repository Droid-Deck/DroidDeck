#!/usr/bin/env bash
set -euo pipefail
repo_root=$1
sink_out=$2
. "${repo_root}/tools/directaudio/release.env"
for v in DIRECTAUDIO_REPO DIRECTAUDIO_TAG DIRECTAUDIO_WINE11_SHA256 DIRECTAUDIO_SYSTHREAD_SHA256 DIRECTAUDIO_SINK_SHA256 DIRECTAUDIO_SOURCE_COMMIT DIRECTAUDIO_SOURCE_SHA256; do
  [[ -n "${!v:-}" && "${!v}" != TBD ]] || { echo "tools/directaudio/release.env: ${v} is not set" >&2; exit 1; }
done
work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

cache=${DROIDDECK_BUILD_CACHE:-"${HOME}/.cache/droiddeck-build"}
mkdir -p "${cache}"
fetch() {
  local name=$1 sha=$2 kept="${cache}/$2-$1" part
  if ! echo "${sha}  ${kept}" | sha256sum -c - >/dev/null 2>&1; then
    part=$(mktemp "${kept}.part.XXXXXX")
    curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o "${part}" \
      "https://github.com/${DIRECTAUDIO_REPO}/releases/download/${DIRECTAUDIO_TAG}/${name}"
    echo "${sha}  ${part}" | sha256sum -c - >/dev/null || { echo "${name}: checksum mismatch" >&2; rm -f "${part}"; exit 1; }
    mv "${part}" "${kept}"
  fi
  cp "${kept}" "${work}/${name}"
}
fetch directaudio-linux-wine11.zip "${DIRECTAUDIO_WINE11_SHA256}"
fetch directaudio-linux-wine11-systhread.zip "${DIRECTAUDIO_SYSTHREAD_SHA256}"
source_archive="${cache}/${DIRECTAUDIO_SOURCE_SHA256}-directaudio-source.tar.gz"
if ! echo "${DIRECTAUDIO_SOURCE_SHA256}  ${source_archive}" | sha256sum -c - >/dev/null 2>&1; then
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 20 -o "${work}/source.tar.gz" \
    "https://github.com/${DIRECTAUDIO_REPO}/archive/${DIRECTAUDIO_SOURCE_COMMIT}.tar.gz"
  echo "${DIRECTAUDIO_SOURCE_SHA256}  ${work}/source.tar.gz" | sha256sum -c - >/dev/null
  mv "${work}/source.tar.gz" "${source_archive}"
fi
mkdir "${work}/source"
tar -xzf "${source_archive}" --strip-components=1 -C "${work}/source"
fetch directaudio-linux-sink.zip "${DIRECTAUDIO_SINK_SHA256}"

assets="${repo_root}/app/src/main/assets/directaudio"
rm -rf "${assets}"
for set in linux-wine11 linux-wine11-systhread; do
  mkdir -p "${assets}/${set}"
  unzip -q "${work}/directaudio-${set}.zip" -d "${assets}/${set}"
  for f in aarch64-unix/winedirectaudio.so aarch64-windows/winedirectaudio.drv i386-windows/winedirectaudio.drv version.txt; do
    test -f "${assets}/${set}/${f}" || { echo "${set}: ${f} missing from the release zip" >&2; exit 1; }
  done
  rm -f "${assets}/${set}/README.md"
done
grep -q '^abi wine11$' "${assets}/linux-wine11/version.txt"
grep -q '^abi systhread$' "${assets}/linux-wine11-systhread/version.txt"

mkdir -p "${work}/sink" "${sink_out}" "${repo_root}/app/src/main/jniLibs/arm64-v8a"
"${repo_root}/tools/directaudio/build-relay.sh" "${repo_root}" "${work}/source"
unzip -q "${work}/directaudio-linux-sink.zip" -d "${work}/sink"
install -m755 "${work}/sink/module-directaudio-native-sink.so" "${sink_out}/"
echo "DirectAudio ${DIRECTAUDIO_TAG}: two driver sets, the relay helper and the client sink in place"
