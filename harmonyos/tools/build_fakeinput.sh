#!/usr/bin/env bash
#
# Builds the guest's fake-input library for aarch64-linux-gnu.
#
# Upstream DroidDeck does not use /dev/uinput. It writes evdev events into
# a shared ring buffer and injects them into the guest's Steam client
# through libfakeinput.so, which is preloaded into that process. That works
# without a privileged device node, which is what HarmonyOS does not give an
# app.
#
# The library runs *inside the guest*, so it has to be built for the guest's
# ABI: the published runtime is Ubuntu, which is glibc. The port's own
# toolchain is musl, so a glibc sysroot is assembled here from Debian's
# packages and the SDK's clang is pointed at it.
#
# The result is a guest library, so it is shipped as a rawfile and copied
# into the rootfs at install time — not as one of the HAP's own libraries.
#
# Usage: tools/build_fakeinput.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="${WORK_DIR:-${TMPDIR:-/tmp}/droiddeck-fakeinput}"
sysroot="${work}/sysroot"

: "${OHOS_SDK_HOME:?set OHOS_SDK_HOME to the OpenHarmony SDK root}"
NDK="${OHOS_SDK_HOME}/native"
CLANG="${NDK}/llvm/bin/clang"
LLD="${NDK}/llvm/bin/ld.lld"

if [ ! -x "${CLANG}" ]; then
  echo "the NDK compiler was not found at ${CLANG}" >&2
  exit 1
fi

# Debian's bookworm aarch64 packages: the glibc headers and runtime, the
# kernel headers, and the compiler runtime the linker expects.
#
# The exact file names carry the package version, and Debian supersedes
# those versions in place, so the paths are resolved from the package index
# instead of being pinned here.
DEBIAN="http://deb.debian.org/debian"
INDEX="${DEBIAN}/dists/bookworm/main/binary-arm64/Packages.gz"
PACKAGES=(
  "libc6-dev"
  "libc6"
  "linux-libc-dev"
  "libgcc-12-dev"
  "libgcc-s1"
  "libstdc++-12-dev"
)

mkdir -p "${work}/debs" "${sysroot}"

echo "==> reading the package index"
curl -sL --max-time 300 -o "${work}/debs/Packages.gz" "${INDEX}"
if [ ! -s "${work}/debs/Packages.gz" ]; then
  echo "the Debian package index could not be downloaded: ${INDEX}" >&2
  exit 1
fi

for package in "${PACKAGES[@]}"; do
  path="$(python3 -c '
import gzip, sys
package = sys.argv[1]
current = None
with gzip.open(sys.argv[2], "rt", errors="replace") as fh:
    for line in fh:
        if line.startswith("Package: "):
            current = line.split(":", 1)[1].strip()
        elif line.startswith("Filename: ") and current == package:
            print(line.split(":", 1)[1].strip())
            break
' "${package}" "${work}/debs/Packages.gz")"
  if [ -z "${path}" ]; then
    echo "the package ${package} is not in the index" >&2
    exit 1
  fi
  name="$(basename "${path}")"
  target="${work}/debs/${name}"
  if [ ! -f "${target}" ]; then
    echo "==> fetching ${name}"
    curl -sL --max-time 900 -o "${target}" "${DEBIAN}/${path}"
  fi
  if ! ar t "${target}" >/dev/null 2>&1; then
    echo "${name} is not a Debian archive" >&2
    exit 1
  fi
  member="$(ar t "${target}" | grep '^data.tar')"
  ar p "${target}" "${member}" | tar -x -C "${sysroot}"
done

# Debian's merged-/usr layout: /lib/aarch64-linux-gnu is a symlink to
# /usr/lib/aarch64-linux-gnu. The glibc linker scripts name the runtime
# by absolute path, so the links have to exist in the sysroot.
mkdir -p "${sysroot}/lib/aarch64-linux-gnu"
for library in libc.so.6 ld-linux-aarch64.so.1 libm.so.6 libpthread.so.0 \
               libdl.so.2 librt.so.1 libgcc_s.so.1; do
  place=""
  for candidate in lib/aarch64-linux-gnu usr/lib/aarch64-linux-gnu; do
    if [ -f "${sysroot}/${candidate}/${library}" ] && [ ! -L "${sysroot}/${candidate}/${library}" ]; then
      place="${candidate}"
      break
    fi
  done
  [ -n "${place}" ] || continue
  link="${sysroot}/lib/aarch64-linux-gnu/${library}"
  # Debian's merged-/usr puts the runtime in one place and links it from
  # the other; the glibc linker scripts name it by absolute path.
  if [ ! -e "${link}" ]; then
    ln -sfn "../../${place}/${library}" "${link}"
  fi
done
# The glibc linker script names the loader by absolute path.
if [ -f "${sysroot}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" ]; then
  ln -sfn "aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "${sysroot}/lib/ld-linux-aarch64.so.1"
elif [ -f "${sysroot}/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" ]; then
  ln -sfn "../usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
    "${sysroot}/lib/ld-linux-aarch64.so.1"
fi

GCC_DIR="${sysroot}/usr/lib/gcc/aarch64-linux-gnu/12"
if [ ! -d "${GCC_DIR}" ]; then
  echo "the compiler runtime was not unpacked" >&2
  exit 1
fi
# The compiler runtime's own linker script names libgcc_s.so.1 without a
# path, so the library is placed next to it.
if [ -f "${sysroot}/lib/aarch64-linux-gnu/libgcc_s.so.1" ]; then
  cp "${sysroot}/lib/aarch64-linux-gnu/libgcc_s.so.1" "${GCC_DIR}/"
fi

# The source is upstream's, copied into the port the same way the
# compositor's sources were.
source_file="${root}/entry/src/main/cpp/input/fakeinput_steam.cpp"
if [ ! -f "${source_file}" ]; then
  echo "the guest input shim's source is missing: ${source_file}" >&2
  exit 1
fi

echo "==> building libfakeinput.so for aarch64-linux-gnu"
"${CLANG}" \
  --target=aarch64-linux-gnu \
  --sysroot="${sysroot}" \
  -fuse-ld="${LLD}" \
  -B"${GCC_DIR}" \
  -L"${sysroot}/lib/aarch64-linux-gnu" \
  -L"${sysroot}/usr/lib/aarch64-linux-gnu" \
  -O2 -fPIC -shared -w \
  -o "${root}/entry/src/main/resources/rawfile/libfakeinput.so" \
  "${source_file}" \
  -ldl

echo "==> done"
file "${root}/entry/src/main/resources/rawfile/libfakeinput.so"
