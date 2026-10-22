#!/usr/bin/env bash
set -euo pipefail
OUTDIR=$(mkdir -p "$1" && cd "$1" && pwd)
: "${NDK:?set NDK to the Android NDK root}"
API=26
ABI=arm64-v8a
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/source.env"
case "$(uname -s):$(uname -m)" in
  Darwin:*) NDK_HOST=darwin-x86_64 ;;
  Linux:x86_64) NDK_HOST=linux-x86_64 ;;
  Linux:aarch64|Linux:arm64) NDK_HOST=linux-aarch64 ;;
  *) echo "Unsupported build host: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
if [[ ! -d "$TOOLCHAIN" && -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi
CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
if [[ ! -x "$CC" ]]; then
  echo "Android NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
# tuxera.com answers 403 from CI runners; the tag archive carries identical
# libntfs-3g sources and headers, differing only in autotools-generated files.
curl -fsSL --retry 3 -o "$WORK/ntfs-3g.tar.gz" \
    "https://github.com/tuxera/ntfs-3g/archive/refs/tags/${NTFS3G_VERSION}.tar.gz"
echo "${NTFS3G_SHA256}  $WORK/ntfs-3g.tar.gz" | shasum -a 256 -c -
mkdir -p "$WORK/ntfs-3g"
tar -xzf "$WORK/ntfs-3g.tar.gz" -C "$WORK/ntfs-3g" --strip-components=1
SRC="$WORK/ntfs-3g"
mkdir -p "$WORK/config"
cp "$HERE/ntfs3g-config.h" "$WORK/config/config.h"

# Upstream's libntfs_3g_la_SOURCES minus unix_io.c/win32_io.c, which
# NO_NTFS_DEVICE_DEFAULT_IO_OPS replaces with the app's USB transport.
SOURCES=(acls.c attrib.c attrlist.c bitmap.c bootsect.c cache.c collate.c
         compat.c compress.c debug.c device.c dir.c ea.c efs.c index.c inode.c
         ioctl.c lcnalloc.c logfile.c logging.c mft.c misc.c mst.c object_id.c
         realpath.c reparse.c runlist.c security.c unistr.c volume.c xattrs.c)

OBJS=()
for source in "${SOURCES[@]}"; do
  "$CC" -c -O2 -fPIC -ffile-prefix-map="$WORK"=. \
      -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DHAVE_CONFIG_H \
      -I"$WORK/config" -I"$SRC/include/ntfs-3g" -I"$SRC/include" \
      -Wno-unused-parameter -Wno-sign-compare \
      -o "$WORK/${source%.c}.o" "$SRC/libntfs-3g/$source"
  OBJS+=("$WORK/${source%.c}.o")
done
"$TOOLCHAIN/llvm-ar" rcs "$WORK/libntfs-3g.a" "${OBJS[@]}"

install -d "$OUTDIR/$ABI" "$OUTDIR/include/ntfs-3g" "$OUTDIR/licenses"
install -m644 "$WORK/libntfs-3g.a" "$OUTDIR/$ABI/libntfs-3g.a"
install -m644 "$SRC"/include/ntfs-3g/*.h "$OUTDIR/include/ntfs-3g/"
install -m644 "$WORK/config/config.h" "$OUTDIR/include/ntfs-3g/config.h"
install -m644 "$SRC/COPYING" "$OUTDIR/licenses/ntfs-3g-COPYING"

"$TOOLCHAIN/llvm-nm" --defined-only "$OUTDIR/$ABI/libntfs-3g.a" > "$WORK/symbols.txt"
for symbol in ntfs_device_alloc ntfs_device_mount ntfs_umount \
              ntfs_pathname_to_inode ntfs_readdir ntfs_attr_pread; do
  grep -q " T $symbol\$" "$WORK/symbols.txt" || { echo "ERROR: $symbol missing from libntfs-3g.a" >&2; exit 1; }
done
echo "libntfs-3g ${NTFS3G_VERSION}: $OUTDIR/$ABI/libntfs-3g.a"
