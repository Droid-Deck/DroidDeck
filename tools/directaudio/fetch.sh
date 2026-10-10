#!/usr/bin/env bash
# Fetches the DirectAudio pieces DroidDeck ships from the pinned release in tools/directaudio/release.env
# and puts them where the build reads them. Four zips, checksummed:
#   directaudio-linux-wine11.zip            the game driver for Valve Proton 11 / Experimental / GE-Proton
#   directaudio-linux-wine11-systhread.zip  the game driver for Proton-CachyOS (system-thread mmdevapi)
#   directaudio-linux-relay.zip             the Android-side helper (speaker + microphone for games)
#   directaudio-linux-sink.zip              the PulseAudio sinks; the app ships module-directaudio-native-sink
#
#   tools/directaudio/fetch.sh <repo root> <dir to receive the sink modules>
#
# Driver sets -> app/src/main/assets/directaudio/<set>/ (generated, ignored by git)
# Helper      -> app/src/main/jniLibs/arm64-v8a/libdirectaudiorelay.so (generated, ignored by git)
# Sink        -> <sink dir>/module-directaudio-native-sink.so for the audio bundle, next to the
#                module-aaudio-sink.so tools/aaudio-sink builds (the fallback).
set -euo pipefail
repo_root=$1
sink_out=$2
# shellcheck source=release.env
. "${repo_root}/tools/directaudio/release.env"
for v in DIRECTAUDIO_REPO DIRECTAUDIO_TAG DIRECTAUDIO_WINE11_SHA256 DIRECTAUDIO_SYSTHREAD_SHA256 DIRECTAUDIO_RELAY_SHA256 DIRECTAUDIO_SINK_SHA256; do
  [[ -n "${!v:-}" && "${!v}" != TBD ]] || { echo "tools/directaudio/release.env: ${v} is not set" >&2; exit 1; }
done
work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

fetch() {
  local name=$1 sha=$2
  curl -fsSL --retry 3 -o "${work}/${name}" \
    "https://github.com/${DIRECTAUDIO_REPO}/releases/download/${DIRECTAUDIO_TAG}/${name}"
  echo "${sha}  ${work}/${name}" | sha256sum -c - >/dev/null || { echo "${name}: checksum mismatch" >&2; exit 1; }
}
fetch directaudio-linux-wine11.zip "${DIRECTAUDIO_WINE11_SHA256}"
fetch directaudio-linux-wine11-systhread.zip "${DIRECTAUDIO_SYSTHREAD_SHA256}"
fetch directaudio-linux-relay.zip "${DIRECTAUDIO_RELAY_SHA256}"
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
# Each set says which interface it was built for; the name must not lie.
grep -q '^abi wine11$' "${assets}/linux-wine11/version.txt"
grep -q '^abi systhread$' "${assets}/linux-wine11-systhread/version.txt"

mkdir -p "${work}/relay" "${work}/sink" "${sink_out}" "${repo_root}/app/src/main/jniLibs/arm64-v8a"
unzip -q "${work}/directaudio-linux-relay.zip" -d "${work}/relay"
install -m755 "${work}/relay/libdirectaudiorelay.so" "${repo_root}/app/src/main/jniLibs/arm64-v8a/libdirectaudiorelay.so"
unzip -q "${work}/directaudio-linux-sink.zip" -d "${work}/sink"
install -m755 "${work}/sink/module-directaudio-native-sink.so" "${sink_out}/"
echo "DirectAudio ${DIRECTAUDIO_TAG}: two driver sets, the relay helper and the client sink in place"
