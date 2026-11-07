#!/usr/bin/env bash
# Build the experimental Mali DXVK payload in a pinned LLVM-mingw container.
# This is a CI component build only; it neither modifies the APK nor publishes
# a release asset. The baseline payload is deliberately built before the Mali
# gates so native loading can be verified independently of those gates.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd -P)
. "$root/tools/dxvk/source.env"
. "$root/tools/dxvk/mali-source.env"

die() {
  echo "error: $*" >&2
  exit 1
}

apply_patches() {
  local directory=$1 patch
  shopt -s nullglob
  for patch in "$directory"/*.patch; do
    git -C "$source" apply --check "$patch"
    git -C "$source" apply "$patch"
  done
  shopt -u nullglob
}

write_cross_file() {
  local name=$1 triplet=$2 family=$3 cpu=$4 target_args=$5 link_args=$6
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
c_args = ['-O2', '-fwrapv', '-fno-strict-aliasing', '-ggdb', '-ffunction-sections', '-fdata-sections', '-fno-omit-frame-pointer', '-ffile-prefix-map=$source=.', $target_args]
cpp_args = ['-O2', '-fwrapv', '-fno-strict-aliasing', '-ggdb', '-ffunction-sections', '-fdata-sections', '-fno-omit-frame-pointer', '-ffile-prefix-map=$source=.', $target_args]
c_link_args = [$link_args]
cpp_link_args = [$link_args]

[host_machine]
system = 'windows'
cpu_family = '$family'
cpu = '$cpu'
endian = 'little'
EOF
}

install_llvm_mingw() {
  local archive="$work/$DXVK_MALI_LLVM_MINGW_ARCHIVE"
  local toolchain_root="$work/llvm-mingw"

  curl --fail --location --proto '=https' --tlsv1.2 --retry 3 \
    --output "$archive" "$DXVK_MALI_LLVM_MINGW_URL"
  printf '%s  %s\n' "$DXVK_MALI_LLVM_MINGW_SHA256" "$archive" | sha256sum -c -
  mkdir -p "$toolchain_root"
  tar --extract --xz --file "$archive" --directory "$toolchain_root" \
    --strip-components=1 --no-same-owner --no-same-permissions
  toolchain="$toolchain_root/bin"
}

verify_arm64x_images() {
  local base=$1
  shift
  python3 - "$base" "$toolchain/llvm-readobj" "$@" <<'PY'
from pathlib import Path
import json
import struct
import subprocess
import sys

base = Path(sys.argv[1])
readobj = sys.argv[2]
paths = [Path(value) for value in sys.argv[3:]]


def fail(path, message):
    raise SystemExit(f'{path}: {message}')


def inspect(path):
    data = path.read_bytes()
    if len(data) < 0x40 or data[:2] != b'MZ':
        fail(path, 'not a PE file')
    pe_offset, = struct.unpack_from('<I', data, 0x3c)
    if pe_offset < 0x40 or pe_offset + 24 > len(data) or data[pe_offset:pe_offset + 4] != b'PE\0\0':
        fail(path, 'PE header not found')
    machine, section_count = struct.unpack_from('<HH', data, pe_offset + 4)
    optional_size, = struct.unpack_from('<H', data, pe_offset + 20)
    optional = pe_offset + 24
    if machine != 0xaa64:
        fail(path, f'outer PE machine {machine:#06x}, expected ARM64 0xaa64')
    if optional_size < 0xf0 or optional + optional_size > len(data):
        fail(path, 'truncated PE32+ optional header')
    magic, = struct.unpack_from('<H', data, optional)
    if magic != 0x20b:
        fail(path, f'optional header magic {magic:#06x}, expected PE32+')
    image_base, = struct.unpack_from('<Q', data, optional + 24)
    image_size, = struct.unpack_from('<I', data, optional + 56)
    directory_count, = struct.unpack_from('<I', data, optional + 108)
    if directory_count <= 10:
        fail(path, 'load-config data directory is absent')
    load_rva, load_directory_size = struct.unpack_from('<II', data, optional + 112 + 10 * 8)

    section_table = optional + optional_size
    if not section_count or section_table + section_count * 40 > len(data):
        fail(path, 'truncated PE section table')
    sections = []
    for index in range(section_count):
        offset = section_table + index * 40
        name = data[offset:offset + 8].split(b'\0', 1)[0].decode('ascii', 'replace')
        virtual_size, virtual_address, raw_size, raw_offset = struct.unpack_from('<IIII', data, offset + 8)
        if raw_offset + raw_size > len(data):
            fail(path, f'section {name!r} exceeds the file')
        sections.append({
            'name': name,
            'virtual_size': virtual_size,
            'virtual_address': virtual_address,
            'raw_size': raw_size,
            'raw_offset': raw_offset,
        })

    names = {section['name'] for section in sections}
    missing = sorted({'.hexpthk', '.a64xrm'} - names)
    if missing:
        fail(path, f'plain ARM64 image lacks ARM64X sections: {", ".join(missing)}')

    def rva_to_raw(rva, length):
        for section in sections:
            start = section['virtual_address']
            delta = rva - start
            if delta < 0 or delta + length > section['raw_size']:
                continue
            if delta + length <= max(section['virtual_size'], section['raw_size']):
                return section['raw_offset'] + delta
        fail(path, f'RVA {rva:#x} with size {length:#x} is not backed by a section')

    if not load_rva or load_directory_size < 0xe6:
        fail(path, 'load-config directory is too small for ARM64X metadata')
    load_raw = rva_to_raw(load_rva, 0xe6)
    load_size, = struct.unpack_from('<I', data, load_raw)
    if load_size < 0xe6:
        fail(path, f'load-config size {load_size:#x} is too small for ARM64X metadata')
    chpe_pointer, = struct.unpack_from('<Q', data, load_raw + 0xc8)
    dynamic_offset, = struct.unpack_from('<I', data, load_raw + 0xe0)
    dynamic_section, = struct.unpack_from('<H', data, load_raw + 0xe4)
    if not chpe_pointer or not (image_base <= chpe_pointer < image_base + image_size):
        fail(path, f'invalid CHPE metadata pointer {chpe_pointer:#x}')
    rva_to_raw(chpe_pointer - image_base, 1)
    if not dynamic_offset or not (1 <= dynamic_section <= len(sections)):
        fail(path, 'ARM64X dynamic relocation table is absent')
    relocation_section = sections[dynamic_section - 1]
    if dynamic_offset + 8 > relocation_section['raw_size']:
        fail(path, 'ARM64X dynamic relocation header exceeds its section')
    dynamic_raw = relocation_section['raw_offset'] + dynamic_offset
    dynamic_version, dynamic_size = struct.unpack_from('<II', data, dynamic_raw)
    if dynamic_version not in (1, 2) or not dynamic_size:
        fail(path, f'invalid ARM64X dynamic relocation table {dynamic_version=}, {dynamic_size=}')
    if dynamic_offset + 8 + dynamic_size > relocation_section['raw_size']:
        fail(path, 'ARM64X dynamic relocation table exceeds its section')

    output = subprocess.run(
        [readobj, '--file-headers', '--sections', '--coff-load-config', '--coff-exports', str(path)],
        check=True, capture_output=True, text=True,
    ).stdout
    if 'Format: COFF-ARM64X' not in output or 'HybridObject {' not in output or 'Format: COFF-ARM64EC' not in output:
        fail(path, 'llvm-readobj did not find both ARM64X and ARM64EC facets')

    try:
        display = str(path.relative_to(base))
    except ValueError:
        display = str(path)
    return {
        'abi': 'arm64x',
        'chpe_metadata_pointer': f'{chpe_pointer:#x}',
        'dynamic_relocation_section': dynamic_section,
        'dynamic_relocation_offset': f'{dynamic_offset:#x}',
        'dynamic_relocation_version': dynamic_version,
        'file': display,
        'hybrid_format': 'COFF-ARM64EC',
        'machine': f'{machine:#06x}',
        'outer_format': 'COFF-ARM64X',
        'sections': sorted(names),
    }


print(json.dumps([inspect(path) for path in paths], indent=2, sort_keys=True))
PY
}

check_coff_machine() {
  local object=$1 expected=$2
  python3 - "$object" "$expected" <<'PY'
from pathlib import Path
import struct
import sys

path = Path(sys.argv[1])
expected = int(sys.argv[2], 0)
data = path.read_bytes()
if len(data) < 2:
    raise SystemExit(f'{path}: truncated COFF object')
machine, = struct.unpack_from('<H', data)
if machine != expected:
    raise SystemExit(f'{path}: COFF machine {machine:#06x}, expected {expected:#06x}')
PY
}

verify_compiler_targets() {
  local architecture triplet expected abi compiler probe
  printf 'architecture\tcompiler_triplet\tmachine\tabi\tcompile_args\tlink_args\n' > "$compiler_targets"
  while IFS=$'\t' read -r architecture triplet expected abi; do
    compiler="$toolchain/${triplet}-clang"
    test -x "$compiler" || die "missing $architecture LLVM-mingw compiler: $compiler"
    probe="$work/compiler-target-$architecture.obj"
    if [ "$abi" = arm64x ]; then
      printf 'int droiddeck_compiler_target_probe;\n' | "$compiler" -marm64x -x c -c - -o "$probe"
    else
      printf 'int droiddeck_compiler_target_probe;\n' | "$compiler" -x c -c - -o "$probe"
    fi
    check_coff_machine "$probe" "$expected"
    if [ "$abi" = arm64x ]; then
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$architecture" "$triplet" "$expected" "$abi" -marm64x -marm64x >> "$compiler_targets"
    else
      printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$architecture" "$triplet" "$expected" "$abi" none none >> "$compiler_targets"
    fi
  done <<'EOF'
i386-windows	i686-w64-mingw32	0x014c	i386
x86_64-windows	x86_64-w64-mingw32	0x8664	x86_64
aarch64-windows	aarch64-w64-mingw32	0xaa64	arm64x
EOF
}

verify_arm64x_linker() {
  local probe="$work/compiler-target-arm64x.dll"
  printf '%s\n' \
    '__declspec(thread) int droiddeck_arm64x_tls = 0x4158;' \
    '__declspec(dllexport) int droiddeck_arm64x_probe(void) { return droiddeck_arm64x_tls; }' | \
    "$toolchain/aarch64-w64-mingw32-clang" -marm64x -shared -O2 -x c - -o "$probe"
  "$toolchain/aarch64-w64-mingw32-strip" --strip-unneeded "$probe"
  verify_arm64x_images "$work" "$probe" > "$work/compiler-target-arm64x.json"
}

check_payload() {
  local payload=$1 arm64x_report="$1/provenance/pe-arm64x.json"
  test -x "$payload/run-proton.py" || die "managed payload wrapper is not executable: $payload/run-proton.py"
  mkdir -p "$payload/provenance"
  verify_arm64x_images "$payload" \
    "$payload/dxvk-mali/aarch64-windows/d3d8.dll" \
    "$payload/dxvk-mali/aarch64-windows/d3d9.dll" \
    "$payload/dxvk-mali/aarch64-windows/d3d10core.dll" \
    "$payload/dxvk-mali/aarch64-windows/d3d11.dll" \
    "$payload/dxvk-mali/aarch64-windows/dxgi.dll" > "$arm64x_report"
  python3 - "$payload" "$toolchain/llvm-objdump" "$payload/provenance/pe-imports.json" <<'PY'
from pathlib import Path
import json
import re
import struct
import subprocess
import sys

payload = Path(sys.argv[1])
objdump = sys.argv[2]
report = Path(sys.argv[3])
machines = {
    'i386-windows': 0x014c,
    'x86_64-windows': 0x8664,
    'aarch64-windows': 0xaa64,
}
abis = {
    'i386-windows': 'i386',
    'x86_64-windows': 'x86_64',
    'aarch64-windows': 'arm64x',
}
names = ('d3d8', 'd3d9', 'd3d10core', 'd3d11', 'dxgi')
runtime = re.compile(r'^(lib(c\+\+|gcc|stdc\+\+|winpthread)|winpthread)([-._]|$)', re.I)
entries = []
for architecture, expected in machines.items():
    for name in names:
        dll = payload / 'dxvk-mali' / architecture / f'{name}.dll'
        if not dll.is_file():
            raise SystemExit(f'missing required DLL: {dll}')
        data = dll.read_bytes()
        if data[:2] != b'MZ' or len(data) < 0x40:
            raise SystemExit(f'{dll}: not a PE file')
        offset, = struct.unpack_from('<I', data, 0x3c)
        if data[offset:offset + 4] != b'PE\0\0':
            raise SystemExit(f'{dll}: PE header not found')
        machine, = struct.unpack_from('<H', data, offset + 4)
        if machine != expected:
            raise SystemExit(f'{dll}: PE machine {machine:#06x}, expected {expected:#06x}')
        header = subprocess.run([objdump, '-p', str(dll)], check=True,
                                capture_output=True, text=True).stdout
        imports = re.findall(r'^\s*DLL Name: (.+)$', header, re.MULTILINE)
        bad = [library for library in imports if runtime.match(library)]
        if bad:
            raise SystemExit(f'{dll}: unexpected dynamic C++/GCC/pthread runtime imports: {bad}')
        entries.append({
            'architecture': architecture,
            'abi': abis[architecture],
            'dll': str(dll.relative_to(payload)),
            'expected_machine': f'0x{expected:04x}',
            'machine': f'0x{machine:04x}',
            'imports': imports,
        })
report.write_text(json.dumps(entries, indent=2, sort_keys=True) + '\n')
PY
}

stamp_version() {
  local tag=$1
  python3 - "$source/version.h.in" "$tag" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
tag = sys.argv[2]
text = path.read_text()
replacement = f'#define DXVK_VERSION "{tag}"'
text, count = re.subn(r'^#define DXVK_VERSION ".*"$', replacement, text, flags=re.M)
if count != 1:
    raise SystemExit(f'{path}: expected one DXVK_VERSION declaration, found {count}')
path.write_text(text)
PY
}

write_payload_metadata() {
  local label=$1 source_epoch
  local payload="$out/$label"
  mkdir -p "$payload/dxvk-mali" "$payload/crossfiles"
  mkdir -p "$payload/provenance"
  install -m 0755 "$root/tools/hybris/run-proton.py" "$payload/run-proton.py"
  cp "$work/compiler-target-arm64x.json" "$payload/provenance/compiler-arm64x-probe.json"
  cp -a "$crossfiles/." "$payload/crossfiles/"
  cp "$source/LICENSE" "$payload/LICENSE"
  printf '%s\n' "$DXVK_REVISION" > "$payload/source-commit"
  printf '%s-droiddeck-%s\n' "$DXVK_MALI_BASE_VERSION" "$label" > "$payload/build-version"
  printf '%s\n' "$DXVK_MALI_CONTAINER_IMAGE" > "$payload/container-image"
  printf '%s\n' "$DXVK_MALI_PROTON_RECIPE" > "$payload/proton-recipe-commit"
  printf '%s\n' "$DXVK_MALI_ARM64X_RECIPE" > "$payload/arm64x-recipe-commit"
  git -C "$source" submodule status --recursive > "$payload/submodules.txt"
  git -C "$source" diff --binary > "$payload/downstream.patch"
  source_epoch=$(git -C "$source" show -s --format=%ct HEAD)
  tar --sort=name --mtime="@$source_epoch" --owner=0 --group=0 --numeric-owner \
    --exclude-vcs --zstd -cf "$payload/sourcearchive.tar.zst" -C "$source" .
  {
    printf 'container=%s\n' "$DXVK_MALI_CONTAINER_IMAGE"
    printf 'toolchain_release=%s\n' "$DXVK_MALI_LLVM_MINGW_VERSION"
    printf 'toolchain_archive=%s\n' "$DXVK_MALI_LLVM_MINGW_ARCHIVE"
    printf 'toolchain_url=%s\n' "$DXVK_MALI_LLVM_MINGW_URL"
    printf 'toolchain_sha256=%s\n' "$DXVK_MALI_LLVM_MINGW_SHA256"
    printf 'toolchain_path=%s\n' "$toolchain"
    printf 'proton_recipe=%s\n' "$DXVK_MALI_PROTON_RECIPE"
    printf 'arm64x_recipe=%s\n' "$DXVK_MALI_ARM64X_RECIPE"
    "$toolchain/i686-w64-mingw32-clang" --version
    "$toolchain/x86_64-w64-mingw32-clang" --version
    "$toolchain/aarch64-w64-mingw32-clang" --version
  } > "$payload/compiler.txt"
  cp "$tool_versions" "$payload/tool-versions.txt"
  cp "$compiler_targets" "$payload/compiler-targets.txt"
  check_payload "$payload"
  (
    cd "$payload"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
  )
}

build_payload() {
  local label=$1 architecture triplet family cpu builddir payload
  while IFS=$'\t' read -r architecture triplet family cpu; do
    builddir="$work/build-$architecture"
    payload="$out/$label/dxvk-mali"
    mkdir -p "$payload/$architecture"
    if [ -d "$builddir" ]; then
      meson setup --reconfigure "$builddir" "$source" --cross-file "$crossfiles/$architecture.ini" \
        --buildtype plain --prefix "$payload" --bindir "$architecture" --libdir "$architecture" \
        -Db_ndebug=true --strip
    else
      meson setup "$builddir" "$source" --cross-file "$crossfiles/$architecture.ini" \
        --buildtype plain --prefix "$payload" --bindir "$architecture" --libdir "$architecture" \
        -Db_ndebug=true --strip
    fi
    meson compile -C "$builddir" -j 2
    meson install -C "$builddir"
  done <<'EOF'
i386-windows	i686-w64-mingw32	x86	x86
x86_64-windows	x86_64-w64-mingw32	x86_64	x86_64
aarch64-windows	aarch64-w64-mingw32	aarch64	aarch64
EOF
}

package_payloads() {
  local archive="$out/dxvk-mali-payloads.tar.zst"
  # upload-artifact normalizes file modes. Keep both payloads in one tarball so
  # run-proton.py remains executable after a maintainer downloads and extracts it.
  tar --sort=name --mtime="@${SOURCE_DATE_EPOCH:?}" --owner=0 --group=0 --numeric-owner \
    --zstd -cf "$archive" -C "$out" baseline experimental
  (
    cd "$out"
    sha256sum "$(basename "$archive")" > dxvk-mali-payloads.SHA256SUMS
  )
}

inside_container() {
  root=$1
  out=$2
  work=$3
  . "$root/tools/dxvk/source.env"
  . "$root/tools/dxvk/mali-source.env"
  toolchain=
  source="$work/source"
  crossfiles="$work/crossfiles"
  tool_versions="$work/tool-versions.txt"
  export LC_ALL=C TZ=UTC

  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates curl git meson ninja-build glslang-tools pkg-config python3 xz-utils zstd
  install_llvm_mingw
  {
    printf 'llvm-mingw-release %s\n' "$DXVK_MALI_LLVM_MINGW_VERSION"
    printf 'llvm-mingw-archive-sha256 %s\n' "$DXVK_MALI_LLVM_MINGW_SHA256"
    git --version
    meson --version
    ninja --version
    glslangValidator --version
    pkg-config --version
    zstd --version
    "$toolchain/llvm-objdump" --version
    "$toolchain/llvm-readobj" --version
  } > "$tool_versions"

  compiler_targets="$work/compiler-targets.txt"
  verify_compiler_targets
  # Fail in seconds if the pinned compiler cannot produce the hybrid image
  # Wine needs for AMD64/ARM64EC games. A plain 0xaa64 image is not sufficient.
  verify_arm64x_linker

  mkdir -p "$source" "$crossfiles"
  git -C "$source" init -q
  git -C "$source" fetch --depth=1 https://github.com/doitsujin/dxvk.git "$DXVK_REVISION"
  git -C "$source" checkout -q --detach FETCH_HEAD
  test "$(git -C "$source" rev-parse HEAD)" = "$DXVK_REVISION"
  git -C "$source" submodule update --init --recursive --depth=1
  export SOURCE_DATE_EPOCH
  SOURCE_DATE_EPOCH=$(git -C "$source" show -s --format=%ct HEAD)

  write_cross_file i386-windows i686-w64-mingw32 x86 x86 \
    "'-mstackrealign', '-march=nocona', '-mtune=core-avx2', '-mfpmath=sse'" ""
  write_cross_file x86_64-windows x86_64-w64-mingw32 x86_64 x86_64 \
    "'-mcmodel=small', '-march=nocona', '-mtune=core-avx2', '-mfpmath=sse'" ""
  # ARM64X keeps a native AArch64 facet and an ARM64EC facet in one outer
  # 0xaa64 image. Both compilation and linking must receive -marm64x.
  write_cross_file aarch64-windows aarch64-w64-mingw32 aarch64 aarch64 \
    "'-march=armv8.2-a', '-mtune=cortex-x3', '-marm64x'" "'-marm64x'"

  apply_patches "$root/tools/dxvk/patches"
  python3 "$root/tools/dxvk/check_present_sync.py" "$source/src/dxvk/dxvk_presenter.cpp"
  stamp_version "$DXVK_MALI_BASE_VERSION-droiddeck-baseline"
  build_payload baseline
  write_payload_metadata baseline

  apply_patches "$root/tools/dxvk/mali-patches"
  stamp_version "$DXVK_MALI_BASE_VERSION-droiddeck-experimental"
  build_payload experimental
  write_payload_metadata experimental
  package_payloads
}

if [ "${1:-}" = --inside-container ]; then
  inside_container "${2:?source root required}" "${3:?output directory required}" "${4:?work directory required}"
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
work=$(mktemp -d "${TMPDIR:-/tmp}/droiddeck-dxvk-mali.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
"$runner" run --rm --pull=always \
  --user 0:0 \
  --volume "$root:/src:ro" \
  --volume "$out:/out:rw" \
  --volume "$work:/work:rw" \
  --workdir /work \
  "$DXVK_MALI_CONTAINER_IMAGE" \
  bash /src/tools/dxvk/build_mali.sh --inside-container /src /out /work
