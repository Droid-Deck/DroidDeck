#!/usr/bin/env bash
# Install the pinned DXVK DLLs into one Proton prefix, prove which d3d11.dll
# Wine loads, and revert from the timestamped backup. See README.md.
set -euo pipefail
export LC_ALL=C

ME=$(basename "$0")
DEFAULT_APPID=3405340
FILES=(d3d11 dxgi d3d10core)
RELAXATIONS=(geometryShader shaderCullDistance)

usage() {
	cat <<'EOF'
usage: dxvk-swap.sh <swap|revert|probe|status> --prefix <pfx> [options]

common:
  --prefix PATH        Proton prefix (…/compatdata/3405340/pfx)
  --dlls PATH          directory with d3d11/dxgi/d3d10core.dll
                       (default: /tmp/oc/dxvk-dlls)
  --variant NAME       subdirectory of --dlls: aarch64 or arm64ec
  --appid N            required compatdata app id (default: 3405340)
  --allow-any-prefix   skip the app id check (dangerous)
  --dry-run            validate and print every operation, write nothing
  -h, --help           this text

swap:
  --allow-missing-dlls proceed when system32/d3d11.dll is absent (fresh
                       prefix); the machine-type check stays mandatory
  --force              only used by revert: restore into a prefix whose
                       manifest names a different prefix

revert:
  --backup DIR         backup directory (default: newest dxvk-backup-*)

probe:
  --proton PATH        Proton installation directory or its proton script
  --exe PATH           Windows PE to run
  --steam-root PATH    STEAM_COMPAT_CLIENT_INSTALL_PATH (optional)
  --log-dir DIR        where proton/DXVK logs go (default: next to prefix)
  --timeout SECONDS    kill the probe after this long (default: 180)
EOF
}

die() { printf '%s: error: %s\n' "$ME" "$*" >&2; exit 1; }
note() { printf '%s: %s\n' "$ME" "$*" >&2; }

run() {
	if ((dry_run)); then
		printf 'dry-run:'
		printf ' %q' "$@"
		printf '\n'
	else
		"$@"
	fi
}

# Print the PE machine type as four hex digits. ARM64 is 0xaa64; ARM64EC and
# x86-64 PE files both carry 0x8664.
pe_machine() {
	python3 - "$1" <<'PY'
import struct, sys
try:
    data = open(sys.argv[1], 'rb').read()
except OSError:
    sys.exit(2)
if len(data) < 0x40 or data[:2] != b'MZ':
    sys.exit(2)
off = struct.unpack_from('<I', data, 0x3c)[0]
if off + 6 > len(data) or data[off:off + 4] != b'PE\0\0':
    sys.exit(2)
print('%04x' % struct.unpack_from('<H', data, off + 4)[0])
PY
}

pe_machine_name() {
	case $1 in
	aa64) echo 'ARM64 (aarch64)' ;;
	8664) echo 'x86-64 / ARM64EC (0x8664)' ;;
	014c) echo 'x86 (0x014c)' ;;
	*) echo "machine 0x$1" ;;
	esac
}

sha256() { sha256sum "$1" | cut -d' ' -f1; }

# Read one record from the backup manifest (used by revert/probe).
manifest_value() {
	local file=$1 key=$2
	[[ -f $file ]] || return 1
	sed -n "s/^$key //p" "$file" | head -1
}

mode=
prefix=
dlls=/tmp/oc/dxvk-dlls
variant=
appid=$DEFAULT_APPID
allow_any_prefix=0
allow_missing=0
force=0
dry_run=0
backup=
proton=
exe=
steam_root=
log_dir=
timeout_s=180

# Command must come first so `swap --dry-run` cannot be mistaken for `revert`.
case ${1:-} in
swap | revert | probe | status | help | -h | --help) ;;
*) usage >&2; die 'missing or unknown command' ;;
esac
mode=$1
shift
case $mode in help | -h | --help) usage; exit 0 ;; esac

while (($#)); do
	case $1 in
	--prefix) prefix=${2:?--prefix needs a value}; shift 2 ;;
	--dlls) dlls=${2:?--dlls needs a value}; shift 2 ;;
	--variant) variant=${2:?--variant needs a value}; shift 2 ;;
	--appid) appid=${2:?--appid needs a value}; shift 2 ;;
	--allow-any-prefix) allow_any_prefix=1; shift ;;
	--allow-missing-dlls) allow_missing=1; shift ;;
	--force) force=1; shift ;;
	--dry-run) dry_run=1; shift ;;
	--backup) backup=${2:?--backup needs a value}; shift 2 ;;
	--proton) proton=${2:?--proton needs a value}; shift 2 ;;
	--exe) exe=${2:?--exe needs a value}; shift 2 ;;
	--steam-root) steam_root=${2:?--steam-root needs a value}; shift 2 ;;
	--log-dir) log_dir=${2:?--log-dir needs a value}; shift 2 ;;
	--timeout) timeout_s=${2:?--timeout needs a value}; shift 2 ;;
	-h | --help) usage; exit 0 ;;
	*) die "unknown option: $1" ;;
	esac
done

[[ -n $prefix ]] || die '--prefix is required'
[[ -d $prefix ]] || die "prefix does not exist: $prefix"
prefix=$(cd "$prefix" && pwd -P)
# Accept the compatdata directory itself and append pfx.
if [[ ! -d $prefix/drive_c && -d $prefix/pfx/drive_c ]]; then
	prefix=$(cd "$prefix/pfx" && pwd -P)
fi
sys32=$prefix/drive_c/windows/system32
[[ -d $sys32 ]] || die "not a Proton prefix (no drive_c/windows/system32): $prefix"
prefix_parent=$(dirname "$prefix")

if ((allow_any_prefix == 0)); then
	case $prefix in
	*/compatdata/"$appid"/pfx | */compatdata/"$appid"/pfx/*) ;;
	*)
		die "prefix is not compatdata/$appid (Megabonk): $prefix
pass --allow-any-prefix only for a deliberate, reviewed exception"
		;;
	esac
fi

resolve_source() {
	[[ -d $dlls ]] || die "DLL directory does not exist: $dlls"
	dlls=$(cd "$dlls" && pwd -P)
	local rel=
	if [[ -n $variant ]]; then
		if [[ -d $dlls/$variant ]]; then
			rel=$variant/
			dlls=$dlls/$variant
		elif [[ $(basename "$dlls") != "$variant" ]]; then
			die "no variant directory '$variant' under $dlls"
		fi
	else
		rel=
	fi
	local f
	for f in "${FILES[@]}"; do
		[[ -f $dlls/$f.dll ]] || die "missing $dlls/$f.dll"
	done
	src_rel=$rel
}

verify_hashes() {
	local f expected actual rel
	for f in "${FILES[@]}"; do
		rel=$src_rel$f.dll
		expected=
		if [[ -f $dlls/../SHA256SUMS ]]; then
			expected=$(awk -v p="$rel" -v b="$(basename "$dlls")/$f.dll" '$2 == p || $2 == b {print $1}' "$dlls/../SHA256SUMS")
		elif [[ -f $dlls/SHA256SUMS ]]; then
			expected=$(awk -v p="$rel" '$2 == p {print $1}' "$dlls/SHA256SUMS")
		fi
		actual=$(sha256 "$dlls/$f.dll")
		if [[ -n $expected && $expected != "$actual" ]]; then
			die "SHA-256 mismatch for $rel: manifest $expected, file $actual"
		fi
		if [[ -z $expected ]]; then
			note "warning: no SHA-256SUMS entry for $rel; using staged file as-is"
		fi
		printf '%s  %s\n' "$actual" "$f.dll"
	done
}

verify_markers() {
	local f marker missing=0
	for marker in "${RELAXATIONS[@]}"; do
		if ! grep -aq "mali-open: dxvk compat relaxation: $marker absent, allowed" "$dlls/d3d11.dll"; then
			note "error: staged d3d11.dll lacks the '$marker' relaxation marker"
			missing=1
		fi
	done
	((missing == 0)) || die "refusing to install a d3d11.dll that is not the reviewed shim build"
}

verify_machine() {
	local f m= staging_m= target=
	for f in "${FILES[@]}"; do
		m=$(pe_machine "$dlls/$f.dll") || die "cannot read PE machine type: $dlls/$f.dll"
		[[ -n $staging_m ]] || staging_m=$m
		[[ $m == "$staging_m" ]] || die "staged DLLs mix machine types ($staging_m and $m)"
	done
	staged_machine=$staging_m
	staged_machine_name=$(pe_machine_name "$staged_machine")

	target=$sys32/d3d11.dll
	if [[ ! -f $target ]]; then
		((allow_missing)) || die "prefix has no system32/d3d11.dll; refusing to guess its machine type (pass --allow-missing-dlls for a fresh/broken prefix)"
		note "warning: prefix has no system32/d3d11.dll; machine check skipped"
		return 0
	fi
	local target_m
	target_m=$(pe_machine "$target") || die "cannot read PE machine type: $target"
	if [[ $target_m != "$staged_machine" ]]; then
		die "machine type mismatch: prefix d3d11.dll is $(pe_machine_name "$target_m"), staged DLLs are $staged_machine_name
stage the matching variant (aarch64 = 0xaa64, arm64ec = 0x8664)"
	fi
	# The other two DLLs are replaced too, so hold them to the same machine.
	for f in dxgi d3d10core; do
		[[ -f $sys32/$f.dll ]] || continue
		m=$(pe_machine "$sys32/$f.dll") || die "cannot read PE machine type: $sys32/$f.dll"
		[[ $m == "$staged_machine" ]] || die "machine type mismatch: $f.dll is $(pe_machine_name "$m"), staged DLLs are $staged_machine_name"
	done
	installed_machine=$target_m
}

make_backup() {
	local stamp f
	stamp=$(date +%Y%m%d-%H%M%S)
	backup=$prefix_parent/dxvk-backup-$stamp
	local n=1
	while [[ -e $backup ]]; do
		backup=$prefix_parent/dxvk-backup-$stamp-$n
		n=$((n + 1))
	done
	note "backup: $backup"
	if ((dry_run)); then
		printf 'dry-run: mkdir -p %q\n' "$backup/staged"
		for f in "${FILES[@]}"; do
			[[ -f $sys32/$f.dll ]] && printf 'dry-run: cp -p %q %q\n' "$sys32/$f.dll" "$backup/$f.dll"
			printf 'dry-run: cp -p %q %q\n' "$dlls/$f.dll" "$backup/staged/$f.dll"
		done
		printf 'dry-run: write %q (origin, machine 0x%s, SHA-256 for staged DLLs)\n' "$backup/MANIFEST" "$staged_machine"
		return 0
	fi
	run mkdir -p "$backup/staged"
	for f in "${FILES[@]}"; do
		[[ -f $sys32/$f.dll ]] && run cp -p "$sys32/$f.dll" "$backup/$f.dll"
		run cp -p "$dlls/$f.dll" "$backup/staged/$f.dll"
	done
	{
		printf 'origin %s\n' "$prefix"
		printf 'appid %s\n' "$appid"
		printf 'created %s\n' "$(date -Is)"
		printf 'source %s\n' "$dlls"
		printf 'machine 0x%s\n' "$staged_machine"
		( cd "$backup/staged" && sha256sum "${FILES[@]/%/.dll}" )
	} >"$backup/MANIFEST"
	run chmod 0644 "$backup/MANIFEST"
}

cmd_swap() {
	resolve_source
	verify_hashes
	verify_markers
	verify_machine
	if ((dry_run)); then
		note "dry-run: prefix $prefix"
		note "dry-run: staged $(pe_machine_name "$staged_machine") DLLs from $dlls"
	fi
	make_backup
	local f tmp
	for f in "${FILES[@]}"; do
		tmp=$sys32/.$f.dll.dxvk-swap.$$
		run cp -p "$backup/staged/$f.dll" "$tmp"
		run mv -f "$tmp" "$sys32/$f.dll"
	done
	if ((dry_run == 0)); then
		for f in "${FILES[@]}"; do
			[[ $(sha256 "$sys32/$f.dll") == "$(sha256 "$backup/staged/$f.dll")" ]] ||
				die "post-install hash mismatch for $f.dll"
		done
		note "installed $(pe_machine_name "$staged_machine") DXVK into $sys32"
		note "revert with: $0 revert --prefix $prefix --backup $backup"
	fi
}

find_backup() {
	if [[ -n $backup ]]; then
		[[ -d $backup ]] || die "backup directory does not exist: $backup"
		backup=$(cd "$backup" && pwd -P)
		return
	fi
	local candidate
	candidate=$(ls -1d "$prefix_parent"/dxvk-backup-* 2>/dev/null | sort | tail -1 || true)
	[[ -n $candidate ]] || die "no dxvk-backup-* directory next to $prefix"
	backup=$candidate
}

cmd_revert() {
	find_backup
	local origin f restored=0
	origin=$(manifest_value "$backup/MANIFEST" origin || true)
	if [[ -n $origin && $origin != "$prefix" ]]; then
		if ((force)); then
			note "warning: manifest origin is $origin, reverting into $prefix anyway (--force)"
		else
			die "backup belongs to $origin, not $prefix (use --force to override)"
		fi
	fi
	for f in "${FILES[@]}"; do
		[[ -f $backup/$f.dll ]] || continue
		local tmp=$sys32/.$f.dll.dxvk-revert.$$
		run cp -p "$backup/$f.dll" "$tmp"
		run mv -f "$tmp" "$sys32/$f.dll"
		restored=$((restored + 1))
	done
	((restored > 0)) || die "backup $backup contains no DLLs to restore"
	if ((dry_run == 0)); then
		note "reverted $restored DLL(s) from $backup"
	fi
}

cmd_status() {
	resolve_source
	verify_hashes >/dev/null
	verify_markers
	verify_machine
	local f
	for f in "${FILES[@]}"; do
		if [[ -f $sys32/$f.dll ]]; then
			if [[ $(sha256 "$sys32/$f.dll") == "$(sha256 "$dlls/$f.dll")" ]]; then
				printf '%s: staged\n' "$f.dll"
			else
				printf '%s: %s (not staged)\n' "$f.dll" "$(pe_machine_name "$(pe_machine "$sys32/$f.dll")")"
			fi
		else
			printf '%s: missing\n' "$f.dll"
		fi
	done
}

cmd_probe() {
	[[ -n $proton ]] || die '--proton is required for probe'
	[[ -n $exe ]] || die '--exe is required for probe'
	[[ -f $exe ]] || die "probe executable does not exist: $exe"
	resolve_source
	exe=$(cd "$(dirname "$exe")" && pwd -P)/$(basename "$exe")

	if [[ -d $proton ]]; then
		proton_dir=$(cd "$proton" && pwd -P)
		proton=$proton_dir/proton
	else
		[[ -f $proton ]] || die "proton script does not exist: $proton"
		proton_dir=$(cd "$(dirname "$proton")" && pwd -P)
		proton=$proton_dir/$(basename "$proton")
	fi
	[[ -x $proton ]] || die "proton is not executable: $proton"

	# The installed DLL must be the staged one before the probe, otherwise the
	# probe would only test whatever Proton last copied in.
	local f
	for f in "${FILES[@]}"; do
		[[ -f $sys32/$f.dll ]] || die "missing $sys32/$f.dll; run swap first"
		[[ $(sha256 "$sys32/$f.dll") == "$(sha256 "$dlls/$f.dll")" ]] ||
			die "$f.dll in the prefix is not the staged build; run swap again (Proton may have re-copied dist DXVK)"
	done
	verify_markers

	local stamp
	stamp=$(date +%Y%m%d-%H%M%S)
	log_dir=${log_dir:-$prefix_parent/dxvk-probe-$stamp}
	run mkdir -p "$log_dir"
	if ((dry_run)); then
		log_dir=${log_dir:-$prefix_parent/dxvk-probe-dry-run}
	fi

	local compat_data=
	case $prefix in
	*/compatdata/*/pfx) compat_data=${prefix%/pfx} ;;
	esac
	local env_args=(
		WINEPREFIX="$prefix"
		WINEDEBUG=+loaddll
		DXVK_LOG_PATH="$log_dir"
		DXVK_LOG_LEVEL=info
	)
	[[ -n $compat_data ]] && env_args+=(STEAM_COMPAT_DATA_PATH="$compat_data")
	[[ -n $steam_root ]] && env_args+=(STEAM_COMPAT_CLIENT_INSTALL_PATH="$steam_root")

	note "probe: $exe"
	note "probe: WINEDEBUG=+loaddll DXVK_LOG_PATH=$log_dir"
	note "probe: through $proton run (not raw wine)"
	if ((dry_run)); then
		printf 'dry-run: (cd %q && env' "$proton_dir"
		printf ' %q' "${env_args[@]}"
		printf ' timeout %q %q run %q) > %q 2>&1\n' "$timeout_s" "$proton" "$exe" "$log_dir/proton.log"
		return 0
	fi

	local rc=0
	(
		cd "$proton_dir"
		env "${env_args[@]}" timeout "$timeout_s" "$proton" run "$exe"
	) >"$log_dir/proton.log" 2>&1 || rc=$?
	[[ $rc -eq 124 ]] && note "warning: probe timed out after ${timeout_s}s"

	local trace=$log_dir/proton.log
	# Wine +loaddll writes the module decision to stderr. DXVK writes the
	# relaxation warnings to its own log in DXVK_LOG_PATH.
	mapfile -t loads < <(grep -a 'd3d11\.dll' "$trace" | grep -a 'load_native_dll\|load_builtin_dll' || true)
	local line native=
	for line in "${loads[@]}"; do
		printf 'load: %s\n' "$line"
		case $line in
		*load_native_dll*system32*d3d11.dll*) native=$line ;;
		esac
	done
	[[ -n $native ]] || die "no native system32\\d3d11.dll load found in $trace (builtin or not loaded)"

	local name found=0
	for name in "${RELAXATIONS[@]}"; do
		line=$(grep -aRh -m1 "mali-open: dxvk compat relaxation: $name absent, allowed" "$log_dir" || true)
		if [[ -z $line ]]; then
			note "missing relaxation log line: $name"
		else
			printf 'relaxed: %s\n' "$line"
			found=$((found + 1))
		fi
	done
	((found == ${#RELAXATIONS[@]})) || die "the loaded d3d11.dll did not log every relaxation line (see $log_dir)"

	# Proton may re-copy its dist DXVK on launch. If that happened, the proof
	# above still stands for this run but the prefix no longer holds our DLLs.
	if [[ $(sha256 "$sys32/d3d11.dll") != "$(sha256 "$dlls/d3d11.dll")" ]]; then
		note "warning: Proton replaced d3d11.dll after launch; run swap again before playing"
	else
		note "post-probe: staged d3d11.dll is still installed"
	fi
	note "probe passed: native d3d11.dll loaded and both relaxation lines logged"
}

case $mode in
swap) cmd_swap ;;
revert) cmd_revert ;;
probe) cmd_probe ;;
status) cmd_status ;;
esac
