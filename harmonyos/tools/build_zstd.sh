#!/usr/bin/env bash
#
# Builds libzstd for HarmonyOS 7 (aarch64, musl) and for the host.
#
# Upstream DroidDeck ships its Linux runtime as a zstd-compressed tar
# (`linuxfs-<version>.tar.zst`), and the OHOS NDK has zlib but no zstd, so
# the port has to bring its own decompressor. The archive is the real
# runtime, with Steam, Proton and the desktop, so supporting it is what
# makes the port installable.
#
# Usage:
#   OHOS_SDK_HOME=/path/to/openharmony-sdk tools/build_zstd.sh

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ZSTD_VERSION="1.5.6"
work="${WORK_DIR:-${TMPDIR:-/tmp}/droiddeck-zstd}"

: "${OHOS_SDK_HOME:?set OHOS_SDK_HOME to the OpenHarmony SDK root}"
NDK="${OHOS_SDK_HOME}/native"

if [ ! -d "${work}/zstd-${ZSTD_VERSION}" ]; then
  echo "==> fetching zstd ${ZSTD_VERSION}"
  mkdir -p "${work}"
  curl -sL -o "${work}/zstd.tar.gz" \
    "https://github.com/facebook/zstd/releases/download/v${ZSTD_VERSION}/zstd-${ZSTD_VERSION}.tar.gz"
  tar xf "${work}/zstd.tar.gz" -C "${work}"
fi

src="${work}/zstd-${ZSTD_VERSION}/lib"
if [ ! -d "${src}" ]; then
  echo "the zstd source was not found at ${src}" >&2
  exit 1
fi

sources=()
for dir in common compress decompress; do
  while IFS= read -r file; do
    sources+=("${file}")
  done < <(find "${src}/${dir}" -name '*.c' | sort)
done

echo "==> compiling ${#sources[@]} zstd sources for arm64 musl"
mkdir -p "${work}/ohos-obj"
for file in "${sources[@]}"; do
  object="${work}/ohos-obj/$(echo "${file#${src}/}" | tr '/' '_').o"
  "${NDK}/llvm/bin/clang" -target aarch64-linux-ohos --sysroot="${NDK}/sysroot" -D__MUSL__ \
    -O2 -fPIC -DZSTD_LEGACY_SUPPORT=0 -DZSTD_MULTITHREAD=0 \
    -I "${src}" -I "${src}/common" \
    -c "${file}" -o "${object}"
done
"${NDK}/llvm/bin/llvm-ar" rcs "${work}/libzstd.a" "${work}/ohos-obj"/*.o

echo "==> compiling zstd for the host"
mkdir -p "${work}/host-obj"
for file in "${sources[@]}"; do
  object="${work}/host-obj/$(echo "${file#${src}/}" | tr '/' '_').o"
  cc -O2 -fPIC -DZSTD_LEGACY_SUPPORT=0 -DZSTD_MULTITHREAD=0 \
    -I "${src}" -I "${src}/common" \
    -c "${file}" -o "${object}"
done
ar rcs "${work}/libzstd-host.a" "${work}/host-obj"/*.o

echo "==> installing into the project"
install_dir="${root}/entry/src/main/cpp/thirdparty/zstd"
mkdir -p "${install_dir}/include"
cp "${work}/libzstd.a" "${install_dir}/libzstd.a"
cp "${src}/zstd.h" "${src}/zstd_errors.h" "${install_dir}/include/"
cp "${work}/libzstd-host.a" "${install_dir}/libzstd-host.a"

echo "==> done"
ls -la "${install_dir}" "${install_dir}/include"