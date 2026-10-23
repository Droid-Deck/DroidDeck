#!/usr/bin/env bash
# CI component build; this does not change the APK or an installed Proton depot.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
. "$root/tools/dxvk/source.env"
mkdir -p "${1:?output directory required}"
out=$(cd "$1" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git init -q "$work/source"
git -C "$work/source" fetch --depth=1 https://github.com/doitsujin/dxvk.git "$DXVK_REVISION"
git -C "$work/source" checkout -q --detach FETCH_HEAD
test "$(git -C "$work/source" rev-parse HEAD)" = "$DXVK_REVISION"
git -C "$work/source" submodule update --init --recursive --depth=1
for patch in "$root"/tools/dxvk/patches/*.patch; do
  git -C "$work/source" apply --check "$patch"
  git -C "$work/source" apply "$patch"
done
python3 "$root/tools/dxvk/check_present_sync.py" "$work/source/src/dxvk/dxvk_presenter.cpp"
for arch in 32 64; do
  meson setup "$work/build$arch" "$work/source" --cross-file "$work/source/build-win$arch.txt" \
    --buildtype release --prefix "$out" --bindir "x$arch" --libdir "x$arch" -Db_ndebug=if-release --strip
  meson compile -C "$work/build$arch" -j 4
  meson install -C "$work/build$arch"
done
cp "$work/source/LICENSE" "$out/LICENSE"
printf '%s\n' "$DXVK_REVISION" > "$out/source-commit"
git -C "$work/source" submodule status --recursive > "$out/submodules.txt"
git -C "$work/source" diff > "$out/downstream.patch"
tar --exclude-vcs --zstd -cf "$out/source.tar.zst" -C "$work/source" .
(cd "$out" && find x32 x64 -name '*.dll' -type f -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
