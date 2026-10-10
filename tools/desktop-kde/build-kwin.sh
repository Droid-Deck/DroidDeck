#!/bin/bash
# Build the pinned KWin executable with the nested-scale patch on native aarch64 Linux.
# Its dependencies come from signature-checked Arch Linux ARM packages in a disposable chroot.
set -euo pipefail
out=$(realpath -m "${1:?out dir}")
here=$(cd "$(dirname "$0")" && pwd)
[ "$(uname -m)" = aarch64 ] || { echo 'KWin needs a native aarch64 runner' >&2; exit 1; }
version=6.7.5
source_sha=6baa910b732d93c48c90f9c1cc685cc93d0b8de0cdf138c24192c045bc3a48e2
mirror=https://fl.us.mirror.archlinuxarm.org/aarch64
builder=68B3537F39A313B3E574D06777193F152BDBE6A6
work=$(mktemp -d)
trap 'sudo umount "$work/root/proc" 2>/dev/null || true; sudo rm -rf "$work"' EXIT
mkdir -p "$work/db" "$work/root" "$work/pkgs" "$out"
fetch() { curl -fsSL --proto '=https' --proto-redir '=https' --retry 6 --retry-all-errors -o "$1" "$2"; }
for repo in core extra alarm; do
    fetch "$work/db/$repo.db" "$mirror/$repo/$repo.db"
    mkdir -p "$work/db/x_$repo"
    tar -xzf "$work/db/$repo.db" -C "$work/db/x_$repo"
done
touch "$work/runtime.txt"
python3 "$here/closure.py" "$work/db" "$work/runtime.txt" "$here/kwin-build-seeds.txt" > "$work/packages.txt"
# Refuse a repository whose KWin headers/libraries have moved beyond the pinned source.
grep -Eq '^extra/kwin-6\.7\.5-[0-9]+-aarch64\.pkg\.tar\.' "$work/packages.txt"
export work mirror builder here
download() {
    local entry=$1 sum=$2 file=${1#*/}
    fetch "$work/pkgs/$file" "$mirror/$entry"
    fetch "$work/pkgs/$file.sig" "$mirror/$entry.sig"
    gpgv --status-fd 1 --keyring "$here/archlinuxarm-builder.gpg" "$work/pkgs/$file.sig" "$work/pkgs/$file" 2>/dev/null \
        | grep -Eq "^\[GNUPG:\] VALIDSIG .* $builder\$"
    echo "$sum  $work/pkgs/$file" | sha256sum -c - >/dev/null
}
export -f fetch download
xargs -P 8 -n 2 bash -c 'set -euo pipefail; download "$@"' _ < "$work/packages.txt"
while read -r entry sum; do
    sudo tar -xf "$work/pkgs/${entry#*/}" -C "$work/root" --no-same-owner \
        --exclude=.PKGINFO --exclude=.MTREE --exclude=.INSTALL --exclude=.BUILDINFO --exclude=.CHANGELOG
done < "$work/packages.txt"
fetch "$work/kwin.tar.xz" "https://download.kde.org/stable/plasma/$version/kwin-$version.tar.xz"
echo "$source_sha  $work/kwin.tar.xz" | sha256sum -c -
sudo mkdir -p "$work/root/build" "$work/root/proc" "$work/root/dev"
sudo mknod -m 666 "$work/root/dev/null" c 1 3
sudo mknod -m 666 "$work/root/dev/zero" c 1 5
sudo mknod -m 666 "$work/root/dev/random" c 1 8
sudo mknod -m 666 "$work/root/dev/urandom" c 1 9
sudo mount -t proc proc "$work/root/proc"
sudo tar -xf "$work/kwin.tar.xz" -C "$work/root/build"
sudo patch -d "$work/root/build/kwin-$version" -p1 < "$here/patches/0001-respect-nested-desktop-scale.patch"
sudo chroot "$work/root" /usr/bin/env PATH=/usr/bin:/bin HOME=/root LC_ALL=C.UTF-8 \
    /usr/bin/cmake -S "/build/kwin-$version" -B /build/out -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_INSTALL_LIBEXECDIR=lib -DBUILD_TESTING=OFF
sudo chroot "$work/root" /usr/bin/env PATH=/usr/bin:/bin HOME=/root LC_ALL=C.UTF-8 \
    /usr/bin/cmake --build /build/out --target kwin_wayland --parallel 4
sudo cp "$work/root/build/out/bin/kwin_wayland" "$out/kwin_wayland"
sudo cp "$work/root/build/out/bin/libkwin.so.$version" "$out/libkwin.so.$version"
sudo chown "$(id -u):$(id -g)" "$out/kwin_wayland" "$out/libkwin.so.$version"
strip --strip-unneeded "$out/kwin_wayland" "$out/libkwin.so.$version"
# The backend lives in libkwin, not the small launcher. Catch accidentally shipping only the
# executable, which boots successfully but still loads the distribution's unpatched backend.
grep -aq DROIDDECK_NESTED_SCALE "$out/libkwin.so.$version"
{
    echo "KWin $version source sha256 $source_sha"
    echo "Patch sha256 $(sha256sum "$here/patches/0001-respect-nested-desktop-scale.patch" | cut -d' ' -f1)"
    echo "Executable sha256 $(sha256sum "$out/kwin_wayland" | cut -d' ' -f1)"
    echo "Library sha256 $(sha256sum "$out/libkwin.so.$version" | cut -d' ' -f1)"
    echo 'Build packages (Arch Linux ARM signatures verified):'
    cat "$work/packages.txt"
} > "$out/desktop-kde.kwin.txt"
