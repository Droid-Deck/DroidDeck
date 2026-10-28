#!/usr/bin/env bash
# Build the Xclipse DXVK 2.6.2 payload from pinned source in CI.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd -P)
. "$root/tools/dxvk/xclipse-source.env"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

apply_patches() {
  local patch
  shopt -s nullglob
  for patch in "$root"/tools/dxvk/xclipse-patches/*.patch; do
    git -C "$source" apply --check "$patch"
    git -C "$source" apply "$patch"
  done
  shopt -u nullglob
}

install_toolchain() {
  local archive="$work/$DXVK_XCLIPSE_LLVM_MINGW_ARCHIVE"
  local destination="$work/llvm-mingw"
  curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
    --output "$archive" "$DXVK_XCLIPSE_LLVM_MINGW_URL"
  printf '%s  %s\n' "$DXVK_XCLIPSE_LLVM_MINGW_SHA256" "$archive" | sha256sum -c -
  mkdir -p "$destination"
  tar --extract --xz --file "$archive" --directory "$destination" \
    --strip-components=1 --no-same-owner --no-same-permissions
  toolchain="$destination/bin"
}

write_cross_file() {
  local name=$1 triplet=$2 family=$3 cpu=$4 target_args=$5
  local file="$crossfiles/$name.ini"
  cat > "$file" <<EOF
[binaries]
c = '$toolchain/${triplet}-clang'
cpp = '$toolchain/${triplet}-clang++'
ar = '$toolchain/${triplet}-ar'
strip = '$toolchain/${triplet}-strip'
windres = '$toolchain/${triplet}-windres'

[properties]
needs_exe_wrapper = true

[built-in options]
c_args = ['-O2', '-fwrapv', '-fno-strict-aliasing', '-ffunction-sections', '-fdata-sections', '-ffile-prefix-map=$source=.', $target_args]
cpp_args = ['-O2', '-fwrapv', '-fno-strict-aliasing', '-ffunction-sections', '-fdata-sections', '-ffile-prefix-map=$source=.', $target_args]

[host_machine]
system = 'windows'
cpu_family = '$family'
cpu = '$cpu'
endian = 'little'
EOF
}

build_architecture() {
  local layout=$1 cross=$2 builddir="$work/build-$1"
  meson setup "$builddir" "$source" --cross-file "$crossfiles/$cross.ini" \
    --buildtype release --prefix "$payload" --bindir "$layout" --libdir "$layout" \
    -Dbuild_id=false --strip
  meson compile -C "$builddir" -j 2
  meson install -C "$builddir"
  find "$payload/$layout" -maxdepth 1 -type f ! -name '*.dll' -delete
  chmod 0755 "$payload/$layout"/*.dll
}

verify_toolchain_compatibility() {
  local triplet compiler object
  for triplet in i686-w64-mingw32 x86_64-w64-mingw32; do
    compiler="$toolchain/${triplet}-clang++"
    test -x "$compiler" || die "missing pinned Xclipse compiler: $compiler"
    object="$work/toolchain-compat-$triplet.obj"
    "$compiler" -std=c++17 -Wall -Wextra -Werror -c \
      "$root/tools/tests/dxvk_xclipse_toolchain_test.cpp" -o "$object"
    test -s "$object" || die "empty toolchain compatibility object: $object"
  done
}

record_pe_inventory() {
  python3 - "$payload" "$toolchain/llvm-objdump" <<'PY'
from pathlib import Path
import hashlib
import json
import re
import struct
import subprocess
import sys

payload = Path(sys.argv[1])
objdump = sys.argv[2]
machines = {'x32': (0x014c, 0x10b), 'x64': (0x8664, 0x20b)}
names = ('d3d8.dll', 'd3d9.dll', 'd3d10core.dll', 'd3d11.dll', 'dxgi.dll')
runtime = re.compile(r'^(lib(c\+\+|gcc|stdc\+\+|winpthread)|winpthread)([-._]|$)', re.I)
entries = []
for architecture, (expected_machine, expected_magic) in machines.items():
    for dll_name in names:
        dll = payload / architecture / dll_name
        if not dll.is_file():
            raise SystemExit(f'missing required DLL: {dll}')
        data = dll.read_bytes()
        if len(data) < 0x40 or data[:2] != b'MZ':
            raise SystemExit(f'{dll}: not a PE file')
        pe, = struct.unpack_from('<I', data, 0x3c)
        if pe < 0x40 or pe + 26 > len(data) or data[pe:pe + 4] != b'PE\0\0':
            raise SystemExit(f'{dll}: PE header not found')
        machine, = struct.unpack_from('<H', data, pe + 4)
        magic, = struct.unpack_from('<H', data, pe + 24)
        if (machine, magic) != (expected_machine, expected_magic):
            raise SystemExit(f'{dll}: PE identity {(machine, magic)}, expected {(expected_machine, expected_magic)}')
        header = subprocess.run([objdump, '-p', str(dll)], check=True,
                                capture_output=True, text=True).stdout
        imports = re.findall(r'^\s*DLL Name: (.+)$', header, re.MULTILINE)
        bad = [library for library in imports if runtime.match(library)]
        if bad:
            raise SystemExit(f'{dll}: unexpected dynamic compiler runtime imports: {bad}')
        entries.append({
            'architecture': architecture,
            'file': str(dll.relative_to(payload.parent)),
            'imports': imports,
            'machine': f'0x{machine:04x}',
            'optional_header_magic': f'0x{magic:03x}',
            'sha256': hashlib.sha256(data).hexdigest(),
            'size': len(data),
        })
(payload / 'provenance/pe-inventory.json').write_text(
    json.dumps(entries, indent=2, sort_keys=True) + '\n')
PY
}

write_metadata() {
  local producer_commit
  producer_commit=$(git -C "$root" rev-parse HEAD)
  mkdir -p "$payload/provenance" "$payload/patches"
  install -m 0644 "$source/LICENSE" "$payload/LICENSE"
  install -m 0644 "$root/tools/dxvk/xclipse-patches/0001-check-direct3d-bc-formats.patch" \
    "$payload/patches/0001-check-direct3d-bc-formats.patch"
  printf '%s\n' "$DXVK_XCLIPSE_REVISION" > "$payload/source-commit"
  python3 "$root/tools/dxvk/xclipse_component_record.py" \
    --write-recipe "$payload/provenance/build-recipe.json"
  git -C "$source" submodule status --recursive > "$payload/provenance/submodules.txt"
  git -C "$source" add -N .
  git -C "$source" diff --binary > "$payload/provenance/downstream.patch"
  tar --sort=name --mtime="@$SOURCE_DATE_EPOCH" --owner=0 --group=0 --numeric-owner \
    --exclude-vcs --zstd -cf "$payload/provenance/sourcearchive.tar.zst" -C "$source" .
  {
    printf 'container=%s\n' "$DXVK_XCLIPSE_CONTAINER_IMAGE"
    printf 'toolchain_release=%s\n' "$DXVK_XCLIPSE_LLVM_MINGW_VERSION"
    printf 'toolchain_archive=%s\n' "$DXVK_XCLIPSE_LLVM_MINGW_ARCHIVE"
    printf 'toolchain_url=%s\n' "$DXVK_XCLIPSE_LLVM_MINGW_URL"
    printf 'toolchain_sha256=%s\n' "$DXVK_XCLIPSE_LLVM_MINGW_SHA256"
    "$toolchain/i686-w64-mingw32-clang" --version
    "$toolchain/x86_64-w64-mingw32-clang" --version
  } > "$payload/provenance/compiler.txt"
  cp "$tool_versions" "$payload/provenance/tool-versions.txt"
  python3 - "$payload/provenance/build-manifest.json" "$producer_commit" \
      "$DXVK_XCLIPSE_REVISION" "$DXVK_XCLIPSE_VERSION" "$DXVK_XCLIPSE_CONTAINER_IMAGE" <<'PY'
from pathlib import Path
import json
import sys

path, producer, source, version, container = sys.argv[1:]
Path(path).write_text(json.dumps({
    'container_image': container,
    'producer_commit': producer,
    'source_commit': source,
    'source_version': version,
}, indent=2, sort_keys=True) + '\n')
PY
  record_pe_inventory
  (
    cd "$out"
    find "dxvk-$DXVK_XCLIPSE_VERSION" -type f \
      ! -path "*/provenance/SHA256SUMS" -print0 | sort -z | xargs -0 sha256sum \
      > "$payload/provenance/SHA256SUMS"
  )
}

package_payload() {
  local archive="$out/dxvk-$DXVK_XCLIPSE_VERSION.tar.gz"
  tar --sort=name --mtime="@$SOURCE_DATE_EPOCH" --owner=0 --group=0 --numeric-owner \
    -cf - -C "$out" "dxvk-$DXVK_XCLIPSE_VERSION" | gzip -n -9 > "$archive"
  sha256sum "$archive" > "$archive.sha256"
}

inside_container() {
  root=$1
  out=$2
  work=$3
  . "$root/tools/dxvk/xclipse-source.env"
  source="$work/source"
  crossfiles="$work/crossfiles"
  payload="$out/dxvk-$DXVK_XCLIPSE_VERSION"
  tool_versions="$work/tool-versions.txt"
  export LC_ALL=C TZ=UTC

  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates curl git gzip meson ninja-build glslang-tools pkg-config python3 xz-utils zstd
  install_toolchain
  verify_toolchain_compatibility
  {
    printf 'llvm-mingw-release %s\n' "$DXVK_XCLIPSE_LLVM_MINGW_VERSION"
    printf 'llvm-mingw-archive-sha256 %s\n' "$DXVK_XCLIPSE_LLVM_MINGW_SHA256"
    git --version
    meson --version
    ninja --version
    glslangValidator --version
    zstd --version
    "$toolchain/llvm-objdump" --version
  } > "$tool_versions"

  mkdir -p "$source" "$crossfiles" "$payload"
  git -C "$source" init -q
  git -C "$source" fetch --depth=1 https://github.com/doitsujin/dxvk.git \
    "refs/tags/v$DXVK_XCLIPSE_VERSION:refs/tags/v$DXVK_XCLIPSE_VERSION"
  test "$(git -C "$source" rev-parse "v$DXVK_XCLIPSE_VERSION")" = "$DXVK_XCLIPSE_TAG_OBJECT"
  test "$(git -C "$source" rev-parse "v$DXVK_XCLIPSE_VERSION^{}")" = "$DXVK_XCLIPSE_REVISION"
  git -C "$source" checkout -q --detach "$DXVK_XCLIPSE_REVISION"
  test "$(git -C "$source" rev-parse HEAD)" = "$DXVK_XCLIPSE_REVISION"
  git -C "$source" submodule update --init --recursive --depth=1
  export SOURCE_DATE_EPOCH
  SOURCE_DATE_EPOCH=$(git -C "$source" show -s --format=%ct HEAD)

  write_cross_file win32 i686-w64-mingw32 x86 x86 \
    "'-mstackrealign', '-march=nocona', '-mtune=core-avx2', '-mfpmath=sse'"
  write_cross_file win64 x86_64-w64-mingw32 x86_64 x86_64 \
    "'-mcmodel=small', '-march=nocona', '-mtune=core-avx2', '-mfpmath=sse'"
  apply_patches
  build_architecture x32 win32
  build_architecture x64 win64
  write_metadata
  package_payload
}

if [[ "${1:-}" == --inside-container ]]; then
  inside_container "${2:?source root required}" "${3:?output directory required}" \
    "${4:?work directory required}"
  exit 0
fi

out_arg=${1:?output directory required}
mkdir -p "$out_arg"
out=$(cd "$out_arg" && pwd -P)
if find "$out" -mindepth 1 -maxdepth 1 -print -quit | grep -q .; then
  die "output directory must be empty: $out"
fi
if command -v podman >/dev/null 2>&1; then
  runner=podman
elif command -v docker >/dev/null 2>&1; then
  runner=docker
else
  die "podman or docker is required for the pinned LLVM-mingw build"
fi
temp_root=${TMPDIR:-${RUNNER_TEMP:-/tmp}}
work=$(mktemp -d "$temp_root/droiddeck-dxvk-xclipse.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
"$runner" run --rm --pull=always --user 0:0 \
  --volume "$root:/src:ro" --volume "$out:/out:rw" --volume "$work:/work:rw" \
  --workdir /work "$DXVK_XCLIPSE_CONTAINER_IMAGE" \
  bash /src/tools/dxvk/build_xclipse.sh --inside-container /src /out /work
