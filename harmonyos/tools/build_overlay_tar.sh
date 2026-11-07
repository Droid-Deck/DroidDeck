#!/usr/bin/env bash
# Builds the Linux runtime overlay that ships inside the HAP.
#
# Upstream DroidDeck bakes tools/linuxfs/overlay/ into the published runtime
# image at build time. The published image therefore already contains
# /usr/local/bin/droiddeck-session. The HarmonyOS port downloads that same
# image, so it has to apply the overlay itself after unpacking.
#
# The result is a gzip tar whose paths are relative to the rootfs
# ("usr/local/bin/droiddeck-session"), which is exactly what the native
# extractor expects. File modes are preserved, because the session script has
# to stay executable.
#
# The overlay carries upstream's tools/linuxfs/overlay/ and the six desktop
# files from upstream's tools/linuxfs/desktop/. Upstream bakes both into the
# image it builds and stages the desktop files from its APK assets; the
# published image has neither, so the port has to supply them itself.
# Without /usr/local/bin/droiddeck-desktop the session script has no desktop
# to start.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
overlay="${here}/runtime/overlay"
output="${here}/artifacts/droiddeck-overlay-arm64.tar.gz"
rawfile="${here}/entry/src/main/resources/rawfile/overlay.tar.gz"

if [ ! -d "${overlay}" ]; then
  echo "overlay not found: ${overlay}" >&2
  exit 1
fi

mkdir -p "${here}/artifacts" "$(dirname "${rawfile}")"

# The archive has to use plain ustar headers, because that is what the
# native extractor reads: it looks for the name in the first 100 bytes of the
# header and never parses pax extended headers. GNU tar and bsdtar spell the
# format differently, so the flavour is detected.
if tar --version 2>/dev/null | head -1 | grep -q "bsdtar"; then
  format_args=(--format gnutar)
else
  format_args=(--format=gnu)
fi

# Every top-level entry of the overlay is packed, not just "usr": the
# clipboard autostart file belongs in etc/xdg/autostart.
entries=()
while IFS= read -r entry; do
  entries+=("${entry}")
done < <(cd "${overlay}" && find . -mindepth 1 -maxdepth 1 -print | sed 's|^\./||' | sort)

tar -czf "${output}" "${format_args[@]}" -C "${overlay}" "${entries[@]}"

cp "${output}" "${rawfile}"

echo "overlay: $(find "${overlay}" -type f | wc -l | tr -d ' ') files"
echo "wrote ${output} ($(wc -c < "${output}" | tr -d ' ') bytes)"
echo "wrote ${rawfile}"
