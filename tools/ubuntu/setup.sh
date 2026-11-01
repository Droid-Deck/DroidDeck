#!/bin/bash
set -euo pipefail
umask 022
here=$(cd "$(dirname "$0")" && pwd)
debs=${1:?directory with the DroidDeck-built .deb files}
runtime_id=${2:?runtime id}
stage=${3:?runtime or desktop}
export DEBIAN_FRONTEND=noninteractive DROIDDECK_APT_RESTORING=1 LANG=C.UTF-8 LC_ALL=C.UTF-8
repo=/var/lib/droiddeck/apt

install_held() {
  local held=() deb name
  for deb in "$repo"/*.deb; do
    name=$(dpkg-deb -f "$deb" Package)
    dpkg-query -W -f '${Status}' "$name" 2>/dev/null | grep -q ' ok installed' || continue
    held+=("$name=$(dpkg-deb -f "$deb" Version)")
  done
  [ ${#held[@]} -gt 0 ] || return 0
  apt-get install -y --no-install-recommends --allow-downgrades "${held[@]}"
  apt-mark hold "${held[@]%%=*}" >/dev/null
}

build_package() {
  local name=$1 overlay=$2 protected=$3 summary=$4 pkg depends
  pkg=$(mktemp -d)
  mkdir -p "$pkg/DEBIAN"
  cp -r "$overlay/." "$pkg/"
  rm -rf "$pkg/usr/local"
  [ -d "$overlay/usr/local" ] && cp -r "$overlay/usr/local/." /usr/local/
  (cd "$pkg" && find . -path ./DEBIAN -prune -o \( -type f -o -type l \) -print | sed 's#^\.##') | sort > "$pkg.files"
  {
    echo '#!/bin/sh'
    echo 'set -e'
    echo 'if [ "$1" = install ] || [ "$1" = upgrade ]; then'
    echo '  :'
    while read -r path; do
      owner=$(dpkg-query -S "$path" 2>/dev/null | grep -v '^diversion' | cut -d: -f1 | head -1) || true
      [ -n "$owner" ] && [ "$owner" != "$name" ] && echo "  dpkg-divert --package $name --rename --divert '$path.distrib' --add '$path'"
    done < "$pkg.files"
    echo 'fi'
  } > "$pkg/DEBIAN/preinst"
  {
    echo '#!/bin/sh'
    echo 'set -e'
    echo 'if [ "$1" = remove ] || [ "$1" = purge ]; then'
    echo "  dpkg-divert --list $name | awk '{print \$3}' | while read -r f; do rm -f \"\$f\"; dpkg-divert --package $name --rename --remove \"\$f\"; done"
    echo 'fi'
  } > "$pkg/DEBIAN/postrm"
  chmod 755 "$pkg/DEBIAN/preinst" "$pkg/DEBIAN/postrm"
  depends=$(grep -v '^$' "$protected" | paste -sd, | sed 's/,/, /g')
  cat > "$pkg/DEBIAN/control" <<EOF
Package: $name
Version: 1.0+${runtime_id//-/.}
Architecture: all
Maintainer: DroidDeck <maxjividen@gmail.com>
Protected: yes
Priority: required
Depends: $depends
Section: admin
Description: $summary
 DroidDeck needs these files and packages inside its Ubuntu runtime.
 Removing them would break it, so apt refuses to.
EOF
  dpkg-deb --root-owner-group -b "$pkg" "/tmp/$name.deb"
  dpkg -i "/tmp/$name.deb"
  rm -rf "$pkg" "$pkg.files" "/tmp/$name.deb"
}

install_turnip() {
  local multiarch=/usr/lib/aarch64-linux-gnu work
  source "$here/base.env"
  work=$(mktemp -d)
  curl -fsSL --proto '=https' --proto-redir '=https' --retry 6 --retry-delay 5 --retry-all-errors -o "$work/turnip.zip" "$DD_TURNIP_URL"
  echo "$DD_TURNIP_SHA256  $work/turnip.zip" | sha256sum -c - >/dev/null
  unzip -q "$work/turnip.zip" -d "$work/turnip"
  dpkg-divert --package droiddeck-runtime --rename --divert "$multiarch/libvulkan_freedreno.so.distrib" --add "$multiarch/libvulkan_freedreno.so"
  install -m 644 "$work/turnip/linux/libvulkan_freedreno.so" "$multiarch/libvulkan_freedreno.so"
  mkdir -p /usr/share/licenses/dd-turnip
  cp -r "$work/turnip/LICENSES" "$work/turnip/NOTICE" "$work/turnip/linux/meta.json" /usr/share/licenses/dd-turnip/
  rm -rf "$work"
  if ldd "$multiarch/libvulkan_freedreno.so" 2>&1 | grep 'not found'; then
    echo "DD-Turnip needs libraries this runtime does not ship; add their packages to packages-runtime.txt" >&2
    return 1
  fi
}

divert_to() {
  local path=$1 target=$2 owner=$3
  [ -e "$path" ] || [ -L "$path" ] || return 0
  dpkg-divert --package "$owner" --rename --divert "$path.distrib" --add "$path"
  ln -sf "$target" "$path"
}

record_apt_state() {
  mkdir -p /usr/share/droiddeck/apt
  apt-mark showmanual > /usr/share/droiddeck/apt/system-manual.txt
  for f in /etc/apt/sources.list /etc/apt/sources.list.d/* /etc/apt/keyrings/* /etc/apt/trusted.gpg.d/* /usr/share/keyrings/*; do
    [ -f "$f" ] && echo "$f $(sha256sum "$f" | cut -d' ' -f1)"
  done > /usr/share/droiddeck/apt/system-sources.txt
}

finish() {
  sed -i 's/^Types: deb deb-src$/Types: deb/' /etc/apt/sources.list.d/ubuntu.sources
  record_apt_state
  apt-get clean
  rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin /var/log/apt/* /var/log/dpkg.log /var/tmp/*
  find /tmp -mindepth 1 -maxdepth 1 ! -name droiddeck -exec rm -rf {} +
  rm -f /etc/machine-id /var/lib/dbus/machine-id /etc/ssh/ssh_host_*
  : > /etc/machine-id
}

if [ "$stage" = runtime ]; then
  printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
  chmod 755 /usr/sbin/policy-rc.d
  cp -r "$here/apt/." /etc/apt/
  sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/ubuntu.sources
  echo 'man-db man-db/auto-update boolean false' | debconf-set-selections
  apt-get update
  apt-get -y full-upgrade

  mkdir -p "$repo"
  cp "$debs"/*.deb "$repo/"
  apt-get install -y --no-install-recommends apt-utils ca-certificates
  (cd "$repo" && apt-ftparchive packages . > Packages && apt-ftparchive release . > Release)
  echo "deb [trusted=yes lang=none] file:$repo ./" > /etc/apt/sources.list.d/droiddeck.list
  built=$(for deb in "$repo"/*.deb; do dpkg-deb -f "$deb" Package; done | sort -u | paste -sd' ')
  printf 'Package: %s\nPin: version /\\+droiddeck/\nPin-Priority: 1001\n' "$built" > /etc/apt/preferences.d/droiddeck-built
  apt-get update

  grep -v '^$' "$here/packages-runtime.txt" | xargs apt-get install -y --no-install-recommends
  install_held
  build_package droiddeck-runtime "$here/overlay-runtime" "$here/protected-runtime.txt" "DroidDeck's Ubuntu runtime"

  update-alternatives --install /usr/bin/sudo sudo /usr/local/bin/sudo 1000
  update-alternatives --set sudo /usr/local/bin/sudo
  divert_to /usr/bin/su /usr/local/bin/su droiddeck-runtime
  multiarch=/usr/lib/aarch64-linux-gnu
  ln -sf liblua5.4.so.0 "$multiarch/liblua.so.5.4"
  divert_to "$multiarch/gamescope/libVkLayer_FROG_gamescope_wsi_aarch64.so" ../../libVkLayer_FROG_gamescope_wsi_aarch64.so droiddeck-runtime
  install_turnip

  mkdir -p /var/cache/droiddeck-flatpak /usr/share/droiddeck/apt /etc/droiddeck/proc /etc/droiddeck/empty /root/Desktop
  touch /var/cache/droiddeck-flatpak/installed
  cp -r "$here/proc/." /etc/droiddeck/proc/
  echo "$runtime_id" > /usr/share/droiddeck/apt/runtime-id

  echo "LANG=C.UTF-8" > /etc/default/locale
  printf 'DroidDeck\n' > /etc/hostname
  . /etc/os-release
  printf 'NAME="%s"\nVERSION="%s"\nID=%s\nID_LIKE=debian\nPRETTY_NAME="%s"\nVERSION_ID="%s"\nVERSION_CODENAME=%s\nUBUNTU_CODENAME=%s\nHOME_URL="https://github.com/Droid-Deck/DroidDeck"\nSUPPORT_URL="https://github.com/Droid-Deck/DroidDeck/issues"\nBUG_REPORT_URL="https://github.com/Droid-Deck/DroidDeck/issues"\nLOGO=distributor-logo-steamdeck\nVARIANT="DroidDeck"\nVARIANT_ID=droiddeck\n' \
    "$NAME" "$VERSION" "$ID" "$PRETTY_NAME" "$VERSION_ID" "$VERSION_CODENAME" "$UBUNTU_CODENAME" > /etc/os-release.droiddeck
  dpkg-divert --package droiddeck-runtime --rename --divert /usr/lib/os-release.distrib --add /usr/lib/os-release
  mv /etc/os-release.droiddeck /usr/lib/os-release
  ln -sf ../usr/lib/os-release /etc/os-release
  finish
  exit 0
fi

[ "$stage" = desktop ] || { echo "unknown stage $stage" >&2; exit 1; }
sed -i 's/^Types: deb$/Types: deb deb-src/' /etc/apt/sources.list.d/ubuntu.sources
install -d -m 0755 /etc/apt/keyrings
install -m 0644 "$here/mozilla.asc" /etc/apt/keyrings/packages.mozilla.org.asc
cat > /etc/apt/sources.list.d/mozilla.sources <<'EOF'
Types: deb
URIs: https://packages.mozilla.org/apt
Suites: mozilla
Components: main
Signed-By: /etc/apt/keyrings/packages.mozilla.org.asc
EOF
cat > /etc/apt/preferences.d/mozilla <<'EOF'
Package: firefox* thunderbird*
Pin: origin packages.mozilla.org
Pin-Priority: 1000
EOF
apt-get update
[[ $(apt-cache policy firefox) == *packages.mozilla.org* ]] || { echo "packages.mozilla.org is unreachable" >&2; exit 1; }

grep -v '^$' "$here/packages-desktop.txt" | xargs apt-get install -y firefox
install_held
build_package droiddeck-desktop "$here/overlay-desktop" "$here/protected-desktop.txt" "DroidDeck's Plasma desktop"

divert_to /usr/bin/pkexec /usr/local/bin/pkexec droiddeck-desktop
divert_to /usr/lib/snapd/snap-confine /usr/local/bin/droiddeck-snap-confine droiddeck-desktop
mkdir -p /snap/bin /var/lib/snapd/desktop/applications
rm -rf /etc/skel/Desktop
finish
