#!/bin/bash
# Builds the KDE Plasma desktop package for the Linux runtime: the closure of seeds.txt over Arch
# Linux ARM's aarch64 repositories, minus what the runtime image already carries, plus overlay/,
# as a zstd tarball that extracts over the rootfs (DesktopCatalog's "tar" kind).
#
#   tools/desktop-kde/build.sh <out dir>
#
# The runtime's own packages (runtime-packages.txt, "name version" from its pacman db) are not
# shipped again, except where something in the closure asks for a version of one the runtime does
# not have (a versioned dependency on it, or on a library it provides): then the repository's
# build comes along and replaces it. Nothing is deleted on extraction, so a library whose soname
# moved keeps its old file for what the runtime linked against it.
#
# Every package is fetched over HTTPS and must carry a valid signature by the Arch Linux ARM Build
# System key (archlinuxarm-builder.gpg; primary fingerprint pinned below, as archlinuxarm.org
# publishes it and archlinuxarm-keyring trusts it) and match the sha256 the repository database
# gives for it; anything else stops the build. The repository databases themselves are not signed
# by Arch Linux ARM, so they decide only which signed packages are taken. desktop-kde.packages.txt
# lists every package taken, with its sha256: what a release was built from.
#
# Needs curl, gpgv, tar, xz, zstd, python3.
set -euo pipefail
out=${1:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
mirror=${ALARM_MIRROR:-https://fl.us.mirror.archlinuxarm.org/aarch64}
case $mirror in https://*) ;; *) echo "ALARM_MIRROR must be https" >&2; exit 1 ;; esac
builder=68B3537F39A313B3E574D06777193F152BDBE6A6
fetch() { curl -fsSL --proto '=https' --proto-redir '=https' --retry 6 --retry-delay 5 --retry-all-errors -o "$1" "$2"; }
work=$(mktemp -d); mkdir -p "$out" "$work/db" "$work/root"
# PKG_CACHE keeps the downloaded packages between builds (a local rebuild after an overlay change).
pkgs=${PKG_CACHE:-$work/pkgs}; mkdir -p "$pkgs"
trap 'rm -rf "$work"' EXIT

for repo in core extra alarm; do
  fetch "$work/db/$repo.db" "$mirror/$repo/$repo.db"
  mkdir -p "$work/db/x_$repo" && tar -xzf "$work/db/$repo.db" -C "$work/db/x_$repo"
done

python3 "$here/closure.py" "$work/db" "$here/runtime-packages.txt" "$here/seeds.txt" > "$work/pkglist.txt"
echo "kde: $(wc -l < "$work/pkglist.txt") packages beyond the runtime's own"

while read -r entry sum; do
  file=${entry#*/}
  [ -s "$pkgs/$file" ] || fetch "$pkgs/$file" "$mirror/$entry"
  fetch "$work/pkg.sig" "$mirror/$entry.sig"
  if ! gpgv --status-fd 1 --keyring "$here/archlinuxarm-builder.gpg" "$work/pkg.sig" "$pkgs/$file" 2>/dev/null \
      | grep -Eq "^\[GNUPG:\] VALIDSIG .* $builder\$"; then
    echo "$file: no valid Arch Linux ARM Build System signature" >&2; rm -f "$pkgs/$file"; exit 1
  fi
  if [ "$(sha256sum "$pkgs/$file" | cut -d' ' -f1)" != "$sum" ]; then
    echo "$file: sha256 differs from the repository database's" >&2; rm -f "$pkgs/$file"; exit 1
  fi
  tar -xf "$pkgs/$file" -C "$work/root" --no-same-owner --no-same-permissions \
    --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
done < "$work/pkglist.txt"

cp -a "$here/overlay/." "$work/root/"

# Weight that no desktop on a phone reads.
rm -rf "$work/root/usr/share/doc" "$work/root/usr/share/man" "$work/root/usr/share/info" \
       "$work/root/usr/share/gtk-doc" "$work/root/usr/include" "$work/root/usr/lib/pkgconfig" \
       "$work/root/usr/lib/cmake" "$work/root/usr/share/help"
# English only, on purpose: the desktop session runs in C.UTF-8 and nothing hands the app's language
# to KDE, so other translations would be weight no one sees. They come back with localizing the
# desktop itself (LANGUAGE from the app's language at session start).
find "$work/root/usr/share/locale" -mindepth 1 -maxdepth 1 -type d ! -name 'en*' -exec rm -rf {} + 2>/dev/null || true
find "$work/root/usr/lib" -name '*.a' -delete
chmod -R u+rwX "$work/root"

sed 's#^[a-z]*/##' "$work/pkglist.txt" > "$out/desktop-kde.packages.txt"  # "file sha256", each verified above
tar -C "$work/root" --zstd -cf "$out/desktop-kde.tar.zst" .
sha256sum "$out/desktop-kde.tar.zst" | awk '{print $1}' > "$out/desktop-kde.sha256"
ls -l "$out/desktop-kde.tar.zst"
