#!/usr/bin/env bash
# Host-side tests for dxvk-swap.sh: no Android, no Proton, no game. The PE
# stubs are only as large as the header the script reads; DLL contents are
# arbitrary bytes plus the markers the script greps for.
set -euo pipefail
export LC_ALL=C

here=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
swap=$here/../dxvk-swap.sh
work=$(mktemp -d "${TMPDIR:-/tmp}/dxvk-swap-test.XXXXXX")
trap 'rm -rf "$work"' EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); printf 'ok %d - %s\n' "$((pass + 1))" "$1"; }
not_ok() { fail=$((fail + 1)); printf 'not ok - %s\n' "$1" >&2; }

expect_ok() {
	local name=$1
	shift
	if "$@" >"$work/out" 2>&1; then ok "$name"; else
		not_ok "$name"
		sed 's/^/    /' "$work/out" >&2
	fi
}

expect_fail() {
	local name=$1 pattern=$2
	shift 2
	if "$@" >"$work/out" 2>&1; then
		not_ok "$name (unexpected success)"
		sed 's/^/    /' "$work/out" >&2
	elif ! grep -q "$pattern" "$work/out"; then
		not_ok "$name (missing message: $pattern)"
		sed 's/^/    /' "$work/out" >&2
	else
		ok "$name"
	fi
}

# mkpe PATH MACHINE [marker]
mkpe() {
	python3 - "$@" <<'PY'
import struct, sys
path, machine = sys.argv[1], int(sys.argv[2], 16)
data = bytearray(0x200)
data[0:2] = b'MZ'
struct.pack_into('<I', data, 0x3c, 0x80)
data[0x80:0x84] = b'PE\0\0'
struct.pack_into('<H', data, 0x84, machine)
body = b''.join(b'mali-open: dxvk compat relaxation: %s absent, allowed\0' % m.encode()
                for m in sys.argv[3:])
open(path, 'wb').write(bytes(data) + body)
PY
}

mk_dlls() {
	local root=$1 machine=$2
	shift 2
	mkdir -p "$root/aarch64" "$root/arm64ec"
	mkpe "$root/aarch64/d3d11.dll" "$machine" "$@"
	mkpe "$root/aarch64/dxgi.dll" "$machine"
	mkpe "$root/aarch64/d3d10core.dll" "$machine"
	mkpe "$root/arm64ec/d3d11.dll" 8664 "$@"
	mkpe "$root/arm64ec/dxgi.dll" 8664
	mkpe "$root/arm64ec/d3d10core.dll" 8664
	(
		cd "$root"
		sha256sum aarch64/*.dll arm64ec/*.dll
	) >"$root/SHA256SUMS"
}

mk_prefix() {
	local root=$1 machine=$2 id=${3:-3405340}
	mkdir -p "$root/compatdata/$id/pfx/drive_c/windows/system32"
	local s=$root/compatdata/$id/pfx/drive_c/windows/system32
	printf 'original d3d11 machine 0x%s\n' "$machine" >"$s/d3d11.dll"
	mkpe "$s/d3d11.dll" "$machine"
	mkpe "$s/dxgi.dll" "$machine"
	mkpe "$s/d3d10core.dll" "$machine"
}

good_marker=(geometryShader shaderCullDistance)

# --- fixtures ---------------------------------------------------------------
mk_dlls "$work/dlls" aa64 "${good_marker[@]}"
mk_prefix "$work/pfx-ok" aa64
PFX=$work/pfx-ok/compatdata/3405340/pfx

# --- guard: wrong app id ----------------------------------------------------
mk_prefix "$work/pfx-other" aa64 999999
expect_fail 'refuses a non-Megabonk prefix' 'not compatdata/3405340' \
	"$swap" swap --prefix "$work/pfx-other/compatdata/999999/pfx" --dlls "$work/dlls" --variant aarch64
[[ ! -e $work/pfx-other/compatdata/999999/dxvk-backup-* ]] && ok 'no backup written for rejected prefix' || not_ok 'backup leaked into rejected prefix'

# --- guard: machine mismatch ------------------------------------------------
mk_prefix "$work/pfx-arm64ec" aa64
expect_fail 'refuses an ARM64EC staging set on an aarch64 prefix' 'machine type mismatch' \
	"$swap" swap --prefix "$work/pfx-arm64ec/compatdata/3405340/pfx" --dlls "$work/dlls" --variant arm64ec

# --- guard: unreviewed DLL --------------------------------------------------
mk_dlls "$work/dlls-bad" aa64 geometryShader
expect_fail 'refuses a d3d11.dll without both relaxation markers' 'lacks the .shaderCullDistance. relaxation marker' \
	"$swap" swap --prefix "$PFX" --dlls "$work/dlls-bad" --variant aarch64

# --- dry run ----------------------------------------------------------------
expect_ok 'swap --dry-run succeeds' \
	"$swap" swap --prefix "$PFX" --dlls "$work/dlls" --variant aarch64 --dry-run
grep -q 'dry-run: cp -p' "$work/out" && ok 'dry-run prints the copy steps' || not_ok 'dry-run printed no copy steps'
[[ ! -e $work/pfx-ok/compatdata/3405340/dxvk-backup-* ]] && ok 'dry-run wrote no backup' || not_ok 'dry-run wrote a backup'

# --- swap -------------------------------------------------------------------
expect_ok 'swap installs the staged DLLs' \
	"$swap" swap --prefix "$PFX" --dlls "$work/dlls" --variant aarch64
backup=$(find "$work/pfx-ok/compatdata/3405340" -maxdepth 1 -name 'dxvk-backup-*' | head -1)
[[ -n $backup && -f $backup/d3d11.dll && -f $backup/staged/d3d11.dll ]] && ok 'backup has originals and staged copies' || not_ok 'backup layout wrong'
[[ -f $backup/MANIFEST ]] && grep -q '^machine 0xaa64$' "$backup/MANIFEST" && ok 'manifest records machine type' || not_ok 'manifest missing machine'
cmp -s "$work/dlls/aarch64/d3d11.dll" "$PFX/drive_c/windows/system32/d3d11.dll" && ok 'installed d3d11.dll matches staged' || not_ok 'installed d3d11.dll differs'
grep -aq 'geometryShader absent, allowed' "$PFX/drive_c/windows/system32/d3d11.dll" && ok 'installed d3d11.dll carries the shim markers' || not_ok 'installed d3d11.dll lost its markers'

# --- probe ------------------------------------------------------------------
cat >"$work/proton" <<'EOF'
#!/usr/bin/env bash
[[ ${1:-} == run ]] || exit 9
mkdir -p "$DXVK_LOG_PATH"
echo 'trace:loaddll:load_native_dll Loaded L"C:\\windows\\system32\\d3d11.dll" from L"C:\\windows\\system32\\d3d11.dll"'
echo 'trace:loaddll:load_builtin_dll Loaded L"C:\\windows\\system32\\kernel32.dll"'
printf 'warn:  mali-open: dxvk compat relaxation: geometryShader absent, allowed\n' >"$DXVK_LOG_PATH/probe_d3d11.log"
printf 'warn:  mali-open: dxvk compat relaxation: shaderCullDistance absent, allowed\n' >>"$DXVK_LOG_PATH/probe_d3d11.log"
EOF
chmod +x "$work/proton"
printf 'probe exe\n' >"$work/probe.exe"

expect_ok 'probe proves the native DLL and both relaxations' \
	"$swap" probe --prefix "$PFX" --dlls "$work/dlls" --variant aarch64 \
	--proton "$work/proton" --exe "$work/probe.exe" --log-dir "$work/probe-log"
grep -q 'load: .*load_native_dll.*system32.*d3d11.dll' "$work/out" &&
	ok 'probe prints the +loaddll native load line' || not_ok 'probe did not print the load line'
grep -q 'relaxed: .*geometryShader' "$work/out" && ok 'probe prints the relaxation lines' || not_ok 'probe printed no relaxation lines'

cat >"$work/proton-builtin" <<'EOF'
#!/usr/bin/env bash
echo 'trace:loaddll:load_builtin_dll Loaded L"C:\\windows\\system32\\d3d11.dll" at 0x1000: builtin'
EOF
chmod +x "$work/proton-builtin"
expect_fail 'probe rejects a builtin d3d11.dll' 'no native system32' \
	"$swap" probe --prefix "$PFX" --dlls "$work/dlls" --variant aarch64 \
	--proton "$work/proton-builtin" --exe "$work/probe.exe" --log-dir "$work/probe-log2"

cat >"$work/proton-quiet" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$work/proton-quiet"
expect_fail 'probe refuses when the prefix no longer holds the staged DLL' 'not the staged build' \
	bash -c 'cp "$1" "$2"; shift 2; exec "$@"' _ "$work/dlls/aarch64/dxgi.dll" \
	"$PFX/drive_c/windows/system32/d3d11.dll" \
	"$swap" probe --prefix "$PFX" --dlls "$work/dlls" --variant aarch64 \
	--proton "$work/proton-quiet" --exe "$work/probe.exe" --log-dir "$work/probe-log3"

# --- revert -----------------------------------------------------------------
expect_ok 'revert restores the originals' \
	"$swap" revert --prefix "$PFX" --backup "$backup"
cmp -s "$backup/d3d11.dll" "$PFX/drive_c/windows/system32/d3d11.dll" && ok 'revert d3d11.dll is byte-identical' || not_ok 'revert d3d11.dll differs'
cmp -s "$backup/dxgi.dll" "$PFX/drive_c/windows/system32/dxgi.dll" && ok 'revert dxgi.dll is byte-identical' || not_ok 'revert dxgi.dll differs'
! grep -aq 'geometryShader absent, allowed' "$PFX/drive_c/windows/system32/d3d11.dll" && ok 'revert removed the shim DLL' || not_ok 'shim DLL still installed'

expect_fail 'revert refuses a backup from another prefix' 'belongs to' \
	"$swap" revert --prefix "$work/pfx-arm64ec/compatdata/3405340/pfx" --backup "$backup"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
((fail == 0))
