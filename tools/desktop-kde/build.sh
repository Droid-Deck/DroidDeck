#!/bin/bash
set -euo pipefail
out=${1:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
source "$here/kwin.env"
source "$here/mesa.env"
mirror=${ALARM_MIRROR:-https://fl.us.mirror.archlinuxarm.org/aarch64}
case $mirror in https://*) ;; *) echo "ALARM_MIRROR must be https" >&2; exit 1 ;; esac
builder=68B3537F39A313B3E574D06777193F152BDBE6A6
fetch() { curl -fsSL --proto '=https' --proto-redir '=https' --retry 6 --retry-delay 5 --retry-all-errors -o "$1" "$2"; }
work=$(mktemp -d); mkdir -p "$out" "$work/db" "$work/root"
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
  url=$mirror/$entry
  [ "$file" != "$DESKTOP_MESA_PACKAGE" ] || url=$DESKTOP_MESA_URL
  [ -s "$pkgs/$file" ] || fetch "$pkgs/$file" "$url"
  fetch "$work/pkg.sig" "$url.sig"
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
mkdir -p "$work/root/usr/lib/droiddeck"
${CC_AARCH64:-aarch64-linux-gnu-gcc} -shared -fPIC -O2 -Wall -Wextra \
  -o "$work/root/usr/lib/droiddeck/libdroiddeck-root.so" "$here/root/root.c" -ldl
${STRIP_AARCH64:-aarch64-linux-gnu-strip} --strip-unneeded "$work/root/usr/lib/droiddeck/libdroiddeck-root.so"
python3 "$here/pacman-db.py" "$work/db" "$pkgs" "$work/pkglist.txt" "$here/runtime-packages.txt" \
  "$here/mirror.txt" "${DESKTOP_KDE_RELEASE_TAG:-}" "$work/root" "$out/repo"

if [ -n "${KDE_KWIN_OVERRIDE:-}" ]; then
  kwin_package=$(awk '$1 ~ /^extra\/kwin-.*-aarch64\.pkg\.tar\./ {print $1}' "$work/pkglist.txt")
  case $kwin_package in
    "extra/kwin-$DESKTOP_KWIN_VERSION"-[0-9]*-aarch64.pkg.tar.*) ;;
    *) echo "KWin package $kwin_package does not match the built KWin $DESKTOP_KWIN_VERSION" >&2; exit 1 ;;
  esac
  install -m 755 "$KDE_KWIN_OVERRIDE/kwin_wayland" "$work/root/usr/bin/kwin_wayland"
  install -m 755 "$KDE_KWIN_OVERRIDE/libkwin.so.$DESKTOP_KWIN_VERSION" "$work/root/usr/lib/libkwin.so.$DESKTOP_KWIN_VERSION"
fi

if [ -d "$work/root/usr/share/locale" ]; then
  for dir in "$work/root"/usr/share/locale/*/; do
    loc=${dir#$work/root/usr/share/locale/}; loc=${loc%/}
    case $loc in en*) continue ;; esac
    [ -d "$dir/LC_MESSAGES" ] || continue
    file=$out/desktop-kde-i18n-$loc.tar.zst
    tar -C "$work/root" --zstd -cf "$file" "./usr/share/locale/$loc"
    printf '%s %s %s\n' "$loc" "$(sha256sum "$file" | cut -d' ' -f1)" "$(stat -c %s "$file")"
  done > "$out/desktop-kde.i18n.txt"
  echo "i18n: $(wc -l < "$out/desktop-kde.i18n.txt") language packs"
fi

rm -rf "$work/root/usr/share/doc" "$work/root/usr/share/info" \
       "$work/root/usr/share/gtk-doc" "$work/root/usr/include" "$work/root/usr/lib/pkgconfig" \
       "$work/root/usr/lib/cmake" "$work/root/usr/share/help"
find "$work/root/usr/share/locale" -mindepth 1 -maxdepth 1 -type d ! -name 'en*' -exec rm -rf {} + 2>/dev/null || true
find "$work/root/usr/lib" -name '*.a' -delete
chmod -R u+rwX "$work/root"

sed 's#^[a-z]*/##' "$work/pkglist.txt" > "$out/desktop-kde.packages.txt"
tar -C "$work/root" --zstd -cf "$out/desktop-kde.tar.zst" .
sha256sum "$out/desktop-kde.tar.zst" | awk '{print $1}' > "$out/desktop-kde.sha256"
ls -l "$out/desktop-kde.tar.zst"
